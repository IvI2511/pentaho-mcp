package io.github.ivi2511.pentahomcp;

import java.util.Date;

import org.eclipse.swt.graphics.Device;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.job.JobGraph;
import org.pentaho.di.ui.spoon.trans.TransGraph;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;

/**
 * Toma la {@link Captura} en el hilo de UI (diseno §1.4): copia desligada (ReportTools.clonar) con
 * repo y metastore de Spoon, lienzo a escala 2 (ReportImagen.pintar) y la ultima corrida
 * (TransGraph.getTrans / JobGraph.getJob). No activa la pestana ni cambia de perspectiva.
 * <p>
 * Lo caro (codificar el PNG, recolectar) lo hace despues el worker: aca solo se copian datos.
 *
 * @dueño WP2
 */
final class CapturaUi {

  /** Escala del lienzo: se pinta al doble y se muestra al 50 % (nitidez en HiDPI y en el PDF). */
  static final float ESCALA = 2f;

  private CapturaUi() {
  }

  /** (UI) La captura de un documento abierto en Spoon. */
  static Captura tomar( Spoon sp, AbstractMeta vivo ) {
    if ( vivo == null ) {
      throw new IllegalArgumentException( "No document to capture" );
    }
    Captura c = new Captura();
    c.capturado = new Date();
    c.docId = Docs.id( vivo );
    c.nombre = Docs.nombre( vivo );
    c.archivo = vacioANull( vivo.getFilename() );
    c.tipo = vivo instanceof JobMeta ? Captura.JOB : Captura.TRANSFORMACION;
    c.sinGuardar = vivo.hasChanged();

    AbstractMeta copia = ReportTools.clonar( vivo );
    LeccionGuia.copiarRegistro( vivo, copia ); // realClone no copia los atributos: la guia lee el registro
    if ( sp != null ) {
      copia.setRepository( sp.getRepository() );
      copia.setMetaStore( sp.getMetaStore() );
    }
    c.copia = copia;

    if ( sp != null ) {
      try {
        if ( vivo instanceof TransMeta ) {
          TransGraph g = sp.delegates.trans.findTransGraphOfTransformation( (TransMeta) vivo );
          c.ultimaTrans = g == null ? null : g.getTrans();
        } else {
          JobGraph g = sp.delegates.jobs.findJobGraphOfJob( (JobMeta) vivo );
          c.ultimoJob = g == null ? null : g.getJob();
        }
      } catch ( Throwable e ) {
        McpLifecycleListener.LOG.logDebug( "Informes: no se pudo leer la ultima corrida: " + e );
      }
      try {
        // pinta el documento vivo: con los iconos de estado de la ultima corrida, como lo ve el usuario
        lienzo( c, ReportImagen.pintar( sp, vivo, ESCALA ) );
      } catch ( Throwable e ) {
        // lienzo vacio (ToolError) o un problema de pintado: el informe sale sin la imagen
        sinLienzo( c, vivo, e );
      }
    }
    return c;
  }

  /**
   * (UI) Pinta el lienzo de un documento que no esta abierto (sin corrida) y lo deja en la captura.
   * Si el lienzo esta vacio o algo falla, la captura queda sin imagen.
   */
  static void pintarSuelto( Device d, Captura c ) {
    try {
      lienzo( c, ReportImagen.pintar( d, c.copia, ESCALA, null, null ) );
    } catch ( Throwable e ) {
      sinLienzo( c, c.copia, e );
    }
  }

  /**
   * El lienzo no se pudo pintar: si el documento tiene steps o entradas no es un lienzo vacio, asi que
   * queda en el log (nivel basico, no debug) y en la captura, y el informe lo avisa.
   */
  private static void sinLienzo( Captura c, AbstractMeta m, Throwable e ) {
    boolean vacio = m instanceof TransMeta ? ( (TransMeta) m ).nrSteps() == 0
      : m instanceof JobMeta && ( (JobMeta) m ).nrJobEntries() == 0;
    if ( vacio ) {
      McpLifecycleListener.LOG.logDebug( "Informes: documento vacio, sin lienzo: " + e );
      return;
    }
    String motivo = e.getMessage() == null || e.getMessage().trim().isEmpty() ? e.getClass().getSimpleName()
      : e.getClass().getSimpleName() + ": " + e.getMessage().trim();
    c.lienzoFallo = motivo.length() > 300 ? motivo.substring( 0, 300 ) + "..." : motivo;
    McpLifecycleListener.LOG.logBasic( "Informes: no se pudo pintar el lienzo de '" + c.nombre + "': " + motivo );
  }

  /**
   * Deja los pixeles crudos y una Imagen sin datos que solo lleva la escala real (ReportImagen la baja
   * en lienzos gigantes). El worker la completa con InformeServicio.codificarLienzo.
   */
  private static void lienzo( Captura c, ReportImagen.Pintado p ) {
    if ( p == null || p.datos == null ) {
      return;
    }
    c.lienzoCrudo = p.datos;
    Imagen marca = new Imagen();
    marca.mime = Imagen.PNG;
    marca.escala = p.escala > 0 ? p.escala : ESCALA;
    c.lienzo = marca;
  }

  private static String vacioANull( String s ) {
    return s == null || s.trim().isEmpty() ? null : s;
  }
}
