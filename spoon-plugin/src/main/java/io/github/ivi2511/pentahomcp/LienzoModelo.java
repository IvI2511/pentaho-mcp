package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.errorhandling.StreamInterface;
import org.pentaho.di.ui.core.PropsUI;

/**
 * El lienzo de un documento como lo ve {@link LienzoLayout}: steps (o entradas de un job) con su posicion y el tamano
 * de su nombre, hops con su tipo (normal, lookup/info, error; en jobs el "si falla" cuenta como error) y notas con el
 * tamano con el que Spoon las pinta. Mide con SWT (fuentes de Spoon) si hay Display en el hilo y PropsUI esta
 * iniciado; si no, con metricas aproximadas (pruebas, Pan).
 */
final class LienzoModelo {

  final LienzoLayout.Grafo grafo = new LienzoLayout.Grafo();
  /** StepMeta o JobEntryCopy, en el orden del documento (el indice es el de getStep/getJobEntry). */
  final List<Object> nodos = new ArrayList<Object>();
  /** Las notas, en el orden del documento. */
  final List<NotePadMeta> notas = new ArrayList<NotePadMeta>();

  private LienzoModelo() {
  }

  /** Lee el documento. Mide con el medidor que corresponda al hilo y lo cierra. */
  static LienzoModelo de( AbstractMeta m ) {
    Medidor med = medidor();
    try {
      return de( m, med );
    } finally {
      med.cerrar();
    }
  }

  static LienzoModelo de( AbstractMeta m, Medidor med ) {
    LienzoModelo r = new LienzoModelo();
    LienzoLayout.Grafo g = r.grafo;
    g.icono = med.icono();
    g.grilla = med.grilla();
    if ( m instanceof TransMeta ) {
      TransMeta tm = (TransMeta) m;
      Map<StepMeta, Integer> idx = new HashMap<StepMeta, Integer>();
      for ( StepMeta s : tm.getSteps() ) {
        Point t = med.nombre( s.getName() );
        idx.put( s, g.nodo( s.getLocation(), t.x, t.y ) );
        r.nodos.add( s );
      }
      for ( int i = 0; i < tm.nrTransHops(); i++ ) {
        TransHopMeta h = tm.getTransHop( i );
        Integer a = idx.get( h.getFromStep() );
        Integer b = idx.get( h.getToStep() );
        if ( a == null || b == null ) {
          continue;
        }
        g.arista( a, b, tipo( h.getFromStep(), h.getToStep() ) );
      }
    } else if ( m instanceof JobMeta ) {
      JobMeta jm = (JobMeta) m;
      Map<JobEntryCopy, Integer> idx = new HashMap<JobEntryCopy, Integer>();
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        Point t = med.nombre( c.getName() );
        int i = g.nodo( c.getLocation(), t.x, t.y );
        idx.put( c, i );
        if ( g.inicio < 0 && c.isStart() ) {
          g.inicio = i;
        }
        r.nodos.add( c );
      }
      for ( int i = 0; i < jm.nrJobHops(); i++ ) {
        JobHopMeta h = jm.getJobHop( i );
        Integer a = idx.get( h.getFromEntry() );
        Integer b = idx.get( h.getToEntry() );
        if ( a == null || b == null ) {
          continue;
        }
        g.arista( a, b, !h.isUnconditional() && !h.getEvaluation() ? LienzoLayout.ERROR : LienzoLayout.NORMAL );
      }
    }
    for ( int i = 0; i < m.nrNotes(); i++ ) {
      NotePadMeta n = m.getNote( i );
      Point t = med.nota( n );
      Point p = n.getLocation();
      g.nota( p == null ? 0 : p.x, p == null ? 0 : p.y, t.x, t.y );
      r.notas.add( n );
    }
    return r;
  }

  /** El hop de 'a' a 'b': error si a le manda sus filas con error, info si b lo lee como stream de info (lookup). */
  static int tipo( StepMeta a, StepMeta b ) {
    try {
      if ( a.isSendingErrorRowsToStep( b ) ) {
        return LienzoLayout.ERROR;
      }
    } catch ( Throwable ignorada ) {
      // sigue
    }
    try {
      for ( StreamInterface s : b.getStepMetaInterface().getStepIOMeta().getInfoStreams() ) {
        if ( a.getName().equalsIgnoreCase( s.getStepname() ) || s.getStepMeta() == a ) {
          return LienzoLayout.INFO;
        }
      }
    } catch ( Throwable ignorada ) {
      // un step sin IO meta: normal
    }
    return LienzoLayout.NORMAL;
  }

  // ---------------------------------------------------------------- medidores

  /** Mide texto como lo pinta Spoon. */
  interface Medidor {
    /** Ancho y alto del nombre de un step con la fuente del lienzo. */
    Point nombre( String nombre );

    /** Ancho y alto con que se pinta una nota (max(width, texto + 10), max(height, texto + 10)). */
    Point nota( NotePadMeta n );

    int icono();

    int grilla();

    void cerrar();
  }

  /** SWT si hay Display en este hilo y PropsUI iniciado; si no, el aproximado. */
  static Medidor medidor() {
    try {
      Display d = Display.getCurrent();
      if ( d != null && PropsUI.isInitialized() ) {
        return new MedidorSwt( d );
      }
    } catch ( Throwable sinSwt ) {
      // aproximado
    }
    return new MedidorAprox();
  }

  /** Tamano pintado de una nota a partir de lo que mide su texto (BasePainter.drawNote, NOTE_MARGIN = 5). */
  static Point tamNota( NotePadMeta n, Point texto ) {
    Point t = n.getNote() == null || n.getNote().isEmpty() ? new Point( 10, 10 ) : texto;
    return new Point( Math.max( n.getWidth(), t.x + 10 ), Math.max( n.getHeight(), t.y + 10 ) );
  }

  /** Segoe UI 9: ~7 px por letra (8 en negrita) y 15 por linea; escala con el tamano de la fuente. */
  static final class MedidorAprox implements Medidor {
    @Override
    public Point nombre( String nombre ) {
      return new Point( ( nombre == null ? 0 : nombre.length() ) * 7, 15 );
    }

    @Override
    public Point nota( NotePadMeta n ) {
      double esc = n.getFontSize() > 0 ? n.getFontSize() / 9.0 : 1.0;
      String[] l = ( n.getNote() == null ? "" : n.getNote() ).split( "\n", -1 );
      int w = 0;
      for ( String s : l ) {
        w = Math.max( w, (int) Math.ceil( s.length() * ( n.isFontBold() ? 8 : 7 ) * esc ) );
      }
      return tamNota( n, new Point( w, (int) Math.ceil( l.length * 15 * esc ) ) );
    }

    @Override
    public int icono() {
      try {
        return PropsUI.isInitialized() ? PropsUI.getInstance().getIconSize() : 32;
      } catch ( Throwable t ) {
        return 32;
      }
    }

    @Override
    public int grilla() {
      try {
        return PropsUI.isInitialized() ? Math.max( 1, PropsUI.getInstance().getCanvasGridSize() ) : 16;
      } catch ( Throwable t ) {
        return 16;
      }
    }

    @Override
    public void cerrar() {
    }
  }

  /** GC de SWT sobre una imagen de 1x1 con las fuentes de Spoon (la del lienzo y la de cada nota). Hilo de UI. */
  static final class MedidorSwt implements Medidor {
    private final Display d;
    private final Image img;
    private final GC gc;
    private final Font grafo;
    private final FontData nota;
    private final Map<String, Font> fuentes = new HashMap<String, Font>();
    private final int icono;
    private final int grilla;

    MedidorSwt( Display d ) {
      this.d = d;
      PropsUI p = PropsUI.getInstance();
      img = new Image( d, 1, 1 );
      gc = new GC( img );
      grafo = new Font( d, p.getGraphFont() );
      nota = p.getNoteFont();
      icono = p.getIconSize();
      grilla = Math.max( 1, p.getCanvasGridSize() );
    }

    @Override
    public Point nombre( String nombre ) {
      gc.setFont( grafo );
      org.eclipse.swt.graphics.Point p = gc.textExtent( nombre == null ? "" : nombre );
      return new Point( p.x, p.y );
    }

    @Override
    public Point nota( NotePadMeta n ) {
      String nombre = n.getFontName() == null || n.getFontName().isEmpty() ? nota.getName() : n.getFontName();
      int tam = n.getFontSize() == -1 ? nota.getHeight() : n.getFontSize();
      int estilo = ( n.isFontBold() ? SWT.BOLD : 0 ) | ( n.isFontItalic() ? SWT.ITALIC : 0 );
      String k = nombre + "|" + tam + "|" + estilo;
      Font f = fuentes.get( k );
      if ( f == null ) {
        f = new Font( d, nombre, Math.max( 1, tam ), estilo );
        fuentes.put( k, f );
      }
      gc.setFont( f );
      org.eclipse.swt.graphics.Point p = gc.textExtent( n.getNote() == null ? "" : n.getNote() );
      return tamNota( n, new Point( p.x, p.y ) );
    }

    @Override
    public int icono() {
      return icono;
    }

    @Override
    public int grilla() {
      return grilla;
    }

    @Override
    public void cerrar() {
      gc.dispose();
      img.dispose();
      grafo.dispose();
      for ( Font f : fuentes.values() ) {
        f.dispose();
      }
    }
  }
}
