package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * {@link Contexto} simple para probar un recolector sin InformeServicio ni ContextoKettle (WP2):
 * getStepFields directo con memo, sin timeout. Ejemplo:
 *
 * <pre>
 * ContextoPrueba ctx = ContextoPrueba.de( "03", "es" );
 * Seccion s = new SecLinaje().armar( ctx );
 * </pre>
 *
 * @dueño WP0
 */
public final class ContextoPrueba implements Contexto {

  /** Lo que los recolectores pasaron a {@link #aviso(String)}. */
  public final List<String> avisos = Collections.synchronizedList( new ArrayList<String>() );
  /** Lo que pasaron a progreso().paso(...). */
  public final List<String> pasos = Collections.synchronizedList( new ArrayList<String>() );
  /** Ponerlo en true para probar la cancelacion. */
  public volatile boolean cancelar;

  private final Captura captura;
  private final Pedido pedido;
  private final Tema tema;
  private final long fin;
  private final Map<StepMeta, RowMetaInterface> salidas = new HashMap<>();
  private final Map<StepMeta, RowMetaInterface> entradas = new HashMap<>();
  private final Progreso progreso = new Progreso() {
    @Override
    public void paso( String texto, int hecho, int total ) {
      pasos.add( texto + " (" + hecho + "/" + total + ")" );
    }

    @Override
    public boolean cancelado() {
      return cancelar;
    }
  };

  public ContextoPrueba( Captura captura, Pedido pedido, Tema tema ) {
    this.captura = captura;
    this.pedido = pedido == null ? new Pedido() : pedido;
    this.tema = tema == null ? new Tema() : tema;
    this.fin = System.currentTimeMillis() + this.pedido.timeoutMs;
  }

  /** Contexto sobre la captura de un ejemplo (por prefijo: "03", "10"...) con un Pedido por defecto. */
  public static ContextoPrueba de( String ejemplo, String idioma ) throws Exception {
    Pedido p = new Pedido();
    p.idioma = idioma;
    return new ContextoPrueba( EntornoKettle.captura( ejemplo ), p, new Tema() );
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
    return Rotulos.normalizar( pedido.idioma );
  }

  @Override
  public Tema tema() {
    return tema;
  }

  @Override
  public Rotulos rotulos( String area ) {
    return Rotulos.de( area, idioma() );
  }

  @Override
  public List<StepMeta> orden() {
    if ( captura != null && captura.copia instanceof TransMeta ) {
      return ReportMotor.ordenTrans( (TransMeta) captura.copia );
    }
    return Collections.emptyList();
  }

  @Override
  public synchronized RowMetaInterface salida( StepMeta s ) throws KettleStepException {
    RowMetaInterface r = salidas.get( s );
    if ( r == null ) {
      r = ( (TransMeta) captura.copia ).getStepFields( s );
      salidas.put( s, r );
    }
    return r;
  }

  @Override
  public synchronized RowMetaInterface entrada( StepMeta s ) throws KettleStepException {
    RowMetaInterface r = entradas.get( s );
    if ( r == null ) {
      r = ( (TransMeta) captura.copia ).getPrevStepFields( s );
      entradas.put( s, r );
    }
    return r;
  }

  @Override
  public Progreso progreso() {
    return progreso;
  }

  @Override
  public boolean cancelado() {
    return cancelar;
  }

  @Override
  public long msRestantes() {
    return Math.max( 0L, fin - System.currentTimeMillis() );
  }

  @Override
  public void aviso( String texto ) {
    avisos.add( texto );
  }
}
