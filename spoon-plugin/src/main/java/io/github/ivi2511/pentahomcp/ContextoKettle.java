package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * El {@link Contexto} real (diseno §7.3): lo arma InformeServicio para cada pedido y lo comparten
 * todos los recolectores.
 * <ul>
 * <li><b>Campos con memo y timeout compartidos.</b> getStepFields / getPrevStepFields sobre la copia,
 * una vez por step (el resultado o el error quedan anotados). Todas las llamadas del pedido gastan un
 * mismo presupuesto de {@link #MS_CAMPOS} (y nunca mas que lo que queda del pedido). Si una llamada
 * se pasa, el calculo de campos queda cerrado para el resto del pedido: el hilo vencido puede seguir
 * tocando la copia y no se le superpone otro.</li>
 * <li><b>Bases de archivo que no existen.</b> Si el step (para la salida) o uno anterior usa una base
 * SQLite/H2/HSQLDB cuyo archivo no existe, no se calcula: Kettle se conectaria y la crearia vacia
 * (diseno §2.4.4). Queda anotado el error con el motivo.</li>
 * <li><b>Plazo total</b> de {@link Pedido#timeoutMs} desde que se crea.</li>
 * <li><b>Cancelacion</b> por el Progreso del pedido, por {@link #cancelar()} o por interrupcion.</li>
 * <li><b>Avisos</b> que terminan en Informe.avisos.</li>
 * </ul>
 * Se usa desde un worker (nunca la UI). Es seguro entre hilos.
 *
 * @dueño WP2
 */
final class ContextoKettle implements Contexto {

  /** Tope del calculo de campos de todo el pedido (diseno §2.5: "campos 60 s"). */
  static final long MS_CAMPOS = 60000;

  private final Captura captura;
  private final Pedido pedido;
  private final Tema tema;
  private final Progreso externo;
  private final String idioma;
  private final long fin;
  private final List<String> avisos = Collections.synchronizedList( new ArrayList<String>() );
  private final Map<StepMeta, Object> salidas = new IdentityHashMap<StepMeta, Object>();
  private final Map<StepMeta, Object> entradas = new IdentityHashMap<StepMeta, Object>();
  private final Progreso progreso;
  private volatile boolean cancelado;
  private long msCamposGastados;
  private boolean camposVencidos;
  private List<StepMeta> orden;

  ContextoKettle( Captura captura, Pedido pedido, Tema tema, Progreso progreso ) {
    this.captura = captura;
    this.pedido = pedido == null ? new Pedido() : pedido;
    this.tema = tema == null ? new Tema() : tema;
    this.externo = progreso == null ? Progreso.NULO : progreso;
    this.idioma = Rotulos.normalizar( this.pedido.idioma );
    this.fin = System.currentTimeMillis() + Math.max( 0L, this.pedido.timeoutMs );
    this.progreso = new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
        try {
          externo.paso( texto, hecho, total );
        } catch ( RuntimeException ignored ) {
          // un Progreso que falla no corta el informe
        }
      }

      @Override
      public boolean cancelado() {
        return ContextoKettle.this.cancelado();
      }
    };
  }

  @Override
  public Captura captura() {
    return captura;
  }

  @Override
  public Pedido pedido() {
    return pedido;
  }

  @Override
  public String idioma() {
    return idioma;
  }

  @Override
  public Tema tema() {
    return tema;
  }

  @Override
  public Rotulos rotulos( String area ) {
    return Rotulos.de( area, idioma );
  }

  @Override
  public synchronized List<StepMeta> orden() {
    if ( orden == null ) {
      orden = captura != null && captura.copia instanceof TransMeta
        ? Collections.unmodifiableList( ReportMotor.ordenTrans( (TransMeta) captura.copia ) )
        : Collections.<StepMeta>emptyList();
    }
    return orden;
  }

  @Override
  public RowMetaInterface salida( StepMeta s ) throws KettleStepException {
    return campos( s, false );
  }

  @Override
  public RowMetaInterface entrada( StepMeta s ) throws KettleStepException {
    return campos( s, true );
  }

  /**
   * Memo + presupuesto compartido. Sincronizado: getStepFields no es seguro entre hilos sobre la
   * misma TransMeta, y asi dos recolectores nunca calculan a la vez.
   */
  private synchronized RowMetaInterface campos( final StepMeta s, final boolean entrada )
    throws KettleStepException {
    if ( !( captura != null && captura.copia instanceof TransMeta ) ) {
      throw new KettleStepException( "Not a transformation: there are no step fields" );
    }
    if ( s == null ) {
      throw new KettleStepException( "No step" );
    }
    Map<StepMeta, Object> memo = entrada ? entradas : salidas;
    Object previo = memo.get( s );
    if ( previo != null ) {
      return devolver( previo );
    }
    if ( camposVencidos ) {
      throw new KettleStepException( rotulos( InformeServicio.AREA ).s( "dic.timeout" ) );
    }
    long tope = Math.min( MS_CAMPOS - msCamposGastados, msRestantes() );
    if ( tope <= 0 || cancelado() ) {
      throw new KettleStepException( rotulos( InformeServicio.AREA ).s( "dic.timeout" ) );
    }
    final TransMeta tm = (TransMeta) captura.copia;
    String falta = baseFaltante( tm, s, entrada );
    if ( falta != null ) {
      KettleStepException e = new KettleStepException( falta );
      memo.put( s, e );
      throw e;
    }
    long t0 = System.currentTimeMillis();
    Object r;
    try {
      r = RunMotor.enSegundoPlano( new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          try {
            return entrada ? tm.getPrevStepFields( s ) : tm.getStepFields( s );
          } catch ( KettleStepException e ) {
            return e;
          } catch ( Throwable e ) {
            return new KettleStepException( RunMotor.mensaje( e ), e );
          }
        }
      }, tope, null, "Computing the fields" );
    } catch ( ToolError e ) {
      camposVencidos = true;
      r = new KettleStepException( rotulos( InformeServicio.AREA ).s( "dic.timeout" ) );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
      cancelar();
      throw new KettleStepException( rotulos( InformeServicio.AREA ).s( "motivo.cancelado" ) );
    } catch ( Exception e ) {
      r = new KettleStepException( RunMotor.mensaje( e ), e );
    } finally {
      msCamposGastados += System.currentTimeMillis() - t0;
    }
    if ( r == null ) {
      r = new KettleStepException( "Kettle returned no fields for '" + s.getName() + "'" );
    }
    memo.put( s, r );
    return devolver( r );
  }

  /**
   * Motivo traducido si calcular los campos tocaria una base de archivo que no existe (SQLite, H2,
   * HSQLDB...): un Table input se conecta para dar sus campos y Kettle crearia el archivo vacio
   * (diseno §2.4.4). Mira los steps anteriores y, para la salida, tambien el propio step. null si no.
   */
  private String baseFaltante( TransMeta tm, StepMeta s, boolean entrada ) {
    try {
      String[] falta = SecDestino.baseFaltantePara( tm, s, !entrada );
      if ( falta == null ) {
        return null;
      }
      // relativa a la carpeta del documento: el informe no muestra C:\Users\<usuario>\... (diseno §6, riesgo 20)
      String ruta = InformeServicio.Txt.relativa( falta[ 1 ], captura.archivo );
      return rotulos( InformeServicio.AREA ).f( "dic.base_falta", falta[ 0 ], ruta == null ? falta[ 1 ] : ruta );
    } catch ( RuntimeException ignored ) {
      return null; // si el chequeo falla, se calcula como siempre
    }
  }

  private static RowMetaInterface devolver( Object r ) throws KettleStepException {
    if ( r instanceof RowMetaInterface ) {
      return (RowMetaInterface) r;
    }
    throw (KettleStepException) r;
  }

  @Override
  public Progreso progreso() {
    return progreso;
  }

  @Override
  public boolean cancelado() {
    if ( cancelado ) {
      return true;
    }
    try {
      if ( externo.cancelado() ) {
        cancelado = true;
      }
    } catch ( RuntimeException ignored ) {
      // un Progreso que falla no cancela
    }
    return cancelado;
  }

  /** Marca el pedido como cancelado: los recolectores lo ven en cancelado(). */
  void cancelar() {
    cancelado = true;
  }

  @Override
  public long msRestantes() {
    return Math.max( 0L, fin - System.currentTimeMillis() );
  }

  @Override
  public void aviso( String texto ) {
    if ( texto != null && !texto.trim().isEmpty() && !avisos.contains( texto ) ) {
      avisos.add( texto );
    }
  }

  /** Copia de los avisos juntados hasta ahora. */
  List<String> avisos() {
    synchronized ( avisos ) {
      return new ArrayList<String>( avisos );
    }
  }

  /** Lo que se gasto del presupuesto de campos (para las pruebas). */
  synchronized long msCamposGastados() {
    return msCamposGastados;
  }
}
