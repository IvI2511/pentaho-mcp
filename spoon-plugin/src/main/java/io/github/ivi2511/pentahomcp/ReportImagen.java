package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.AreaOwner;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobEntryResult;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.JobPainter;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.TransPainter;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.core.PropsUI;
import org.pentaho.di.ui.spoon.SWTGC;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.job.JobGraph;
import org.pentaho.di.ui.spoon.trans.TransGraph;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Imagen del lienzo de una transformacion o un job, como "Exportar imagen" de Spoon.
 *
 * Spoon lo hace con TransGraph.getTransformationImage / JobGraph.getJobImage, pero esos toman el
 * desplazamiento de las barras de scroll del lienzo (la imagen depende de donde este mirando el
 * usuario). Aca se arma el mismo TransPainter / JobPainter con las barras en null (desplazamiento 0),
 * sobre un SWTGC propio, y despues se recorta al area ocupada.
 *
 * Hilos: {@link #pintar} en el hilo de UI (SWT); {@link #png} fuera, porque ImageData e ImageLoader
 * son datos puros.
 */
final class ReportImagen {

  /** Margen alrededor de lo dibujado, en pixeles de la imagen final. */
  static final int MARGEN = 20;

  /** Lienzo maximo a pintar (pixeles por lado) antes de recortar. */
  static final int MAX_LADO = 8000;

  /** Lo que sale del hilo de UI: los pixeles y la escala que se uso de verdad. */
  static final class Pintado {
    ImageData datos;
    float escala;
    String nota;
  }

  private ReportImagen() {
  }

  /** Pinta el documento abierto en Spoon, con el estado de su ultima ejecucion si la hubo. (UI) */
  static Pintado pintar( Spoon sp, AbstractMeta meta, float escala ) throws ToolError {
    Trans trans = null;
    Job job = null;
    if ( meta instanceof TransMeta ) {
      TransGraph g = sp.delegates.trans.findTransGraphOfTransformation( (TransMeta) meta );
      trans = g == null ? null : g.getTrans();
    } else {
      JobGraph g = sp.delegates.jobs.findJobGraphOfJob( (JobMeta) meta );
      job = g == null ? null : g.getJob();
    }
    return pintar( sp.getDisplay(), meta, escala, trans, job );
  }

  /** Pinta el documento entero, sin recortar. Sin Spoon, para poder probarlo con un Display suelto. (UI) */
  static Pintado pintar( Device device, AbstractMeta meta, float escalaPedida, Trans trans, Job job )
    throws ToolError {
    Point max = maximo( meta );
    if ( max == null ) {
      throw new ToolError( "The canvas of '" + Docs.nombre( meta ) + "' is empty: there is nothing to capture" );
    }
    float escala = escalaPedida;
    Pintado p = new Pintado();
    // el texto de los nombres puede salirse a la derecha y abajo del icono: holgura extra
    int ancho = Math.round( ( max.x + 300 ) * escala );
    int alto = Math.round( ( max.y + 150 ) * escala );
    if ( ancho > MAX_LADO || alto > MAX_LADO ) {
      float f = Math.min( (float) MAX_LADO / ancho, (float) MAX_LADO / alto );
      escala = escala * f;
      ancho = Math.min( MAX_LADO, Math.round( ancho * f ) );
      alto = Math.min( MAX_LADO, Math.round( alto * f ) );
      p.nota = "The canvas is very large: scale reduced to " + String.format( java.util.Locale.ROOT, "%.2f", escala );
    }
    p.escala = escala;

    PropsUI props = PropsUI.getInstance();
    FontData nota = props.getNoteFont();
    Point area = new Point( ancho, alto );
    SWTGC gc = new SWTGC( device, area, props.getIconSize() );
    Image img = null;
    try {
      if ( meta instanceof TransMeta ) {
        TransMeta tm = (TransMeta) meta;
        TransPainter tp = new TransPainter( gc, tm, area, null, null, null, null, null,
          new ArrayList<AreaOwner>(), new ArrayList<StepMeta>(), props.getIconSize(), props.getLineWidth(), 1, 0,
          props.isAntiAliasingEnabled(), nota.getName(), nota.getHeight(), trans, false );
        tp.setMagnification( escala );
        tp.buildTransformationImage();
      } else {
        JobMeta jm = (JobMeta) meta;
        JobPainter jp = new JobPainter( gc, jm, area, null, null, null, null, null, new ArrayList<AreaOwner>(),
          new ArrayList<JobEntryCopy>(), props.getIconSize(), props.getLineWidth(), 1, 0,
          props.isAntiAliasingEnabled(), nota.getName(), nota.getHeight() );
        jp.setMagnification( escala );
        jp.setJobEntryResults( job == null ? new ArrayList<JobEntryResult>() : job.getJobEntryResults() );
        jp.setActiveJobEntries( new ArrayList<JobEntryCopy>() );
        jp.drawJob();
      }
      img = (Image) gc.getImage();
      p.datos = img.getImageData();
    } finally {
      gc.dispose(); // GC, colores, fuentes y transform; la imagen no
      if ( img != null && !img.isDisposed() ) {
        img.dispose();
      }
    }
    return p;
  }

  /** Esquina inferior derecha de lo que hay en el lienzo (coordenadas reales), o null si esta vacio. */
  static Point maximo( AbstractMeta meta ) {
    int x = -1;
    int y = -1;
    int icono = 32;
    try {
      icono = PropsUI.getInstance().getIconSize();
    } catch ( Throwable ignored ) {
      // sin PropsUI (pruebas): 32
    }
    if ( meta instanceof TransMeta ) {
      for ( StepMeta s : ( (TransMeta) meta ).getSteps() ) {
        x = Math.max( x, s.getLocation().x + icono );
        y = Math.max( y, s.getLocation().y + icono );
      }
    } else if ( meta instanceof JobMeta ) {
      for ( JobEntryCopy c : ( (JobMeta) meta ).getJobCopies() ) {
        x = Math.max( x, c.getLocation().x + icono );
        y = Math.max( y, c.getLocation().y + icono );
      }
    }
    for ( int i = 0; i < meta.nrNotes(); i++ ) {
      NotePadMeta n = meta.getNote( i );
      x = Math.max( x, n.getLocation().x + Math.max( n.getWidth(), 0 ) );
      y = Math.max( y, n.getLocation().y + Math.max( n.getHeight(), 0 ) );
    }
    return x < 0 ? null : new Point( x, y );
  }

  /**
   * Recorta al rectangulo que no es fondo (mas el margen) y codifica en PNG. El fondo es el color
   * de la esquina inferior derecha, que queda siempre vacia por la holgura de pintar(). (fuera de UI)
   */
  static byte[] png( ImageData src ) {
    int fondo = src.getPixel( src.width - 1, src.height - 1 );
    int x0 = src.width;
    int y0 = src.height;
    int x1 = -1;
    int y1 = -1;
    int[] fila = new int[ src.width ];
    for ( int y = 0; y < src.height; y++ ) {
      src.getPixels( 0, y, src.width, fila, 0 );
      for ( int x = 0; x < src.width; x++ ) {
        if ( fila[ x ] != fondo ) {
          if ( x < x0 ) {
            x0 = x;
          }
          if ( x > x1 ) {
            x1 = x;
          }
          y0 = Math.min( y0, y );
          y1 = y;
        }
      }
    }
    ImageData out;
    if ( x1 < 0 ) {
      out = src; // todo fondo: no deberia pasar con algo dibujado
    } else {
      x0 = Math.max( 0, x0 - MARGEN );
      y0 = Math.max( 0, y0 - MARGEN );
      x1 = Math.min( src.width - 1, x1 + MARGEN );
      y1 = Math.min( src.height - 1, y1 + MARGEN );
      int w = x1 - x0 + 1;
      int h = y1 - y0 + 1;
      out = new ImageData( w, h, src.depth, src.palette );
      int[] buf = new int[ w ];
      for ( int y = 0; y < h; y++ ) {
        src.getPixels( x0, y0 + y, w, buf, 0 );
        out.setPixels( 0, y, w, buf, 0 );
      }
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] { out };
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    loader.save( bytes, SWT.IMAGE_PNG );
    return bytes.toByteArray();
  }

  static String base64( byte[] b ) {
    return Base64.getEncoder().encodeToString( b );
  }
}
