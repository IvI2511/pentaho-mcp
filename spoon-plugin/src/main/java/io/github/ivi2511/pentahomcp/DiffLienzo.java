package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Display;
import org.pentaho.di.core.gui.GCInterface;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.gui.PrimitiveGCInterface.EColor;
import org.pentaho.di.core.gui.PrimitiveGCInterface.EFont;
import org.pentaho.di.core.gui.PrimitiveGCInterface.ELineStyle;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.JobPainter;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.TransPainter;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.diff.Diferencia;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Como;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Que;
import io.github.ivi2511.pentahomcp.diff.ResultadoDiff;

/**
 * Las insignias del diff en el lienzo de Spoon (diseño E1): + añadido, − quitado, ~ cambiado, » renombrado, ↔ movido,
 * sobre cada step o entrada y en el medio de cada salto, y los steps quitados como «fantasmas» con borde rayado en su
 * posición de antes. Es solo dibujo: el documento no cambia (ni se marca como modificado) y las insignias se van al
 * cerrar la lista de cambios.
 * <p>
 * <b>Cómo se dibuja.</b> Kettle llama al final de cada pintado del lienzo a las extensiones {@code TransPainterEnd} y
 * {@code JobPainterEnd} con el TransPainter o JobPainter (con la escala ya puesta en el GC):
 * {@link DiffPintorTrans} y {@link DiffPintorJob} llaman a {@link #pintar}. Las marcas viven en los datos del
 * Display de SWT ({@link #CLAVE}) y solo con tipos de java.util, así funcionan aunque Kettle cargue esas extensiones en
 * otro classloader que el resto del plugin. (UI)
 */
final class DiffLienzo {

  /** Dato del Display: IdentityHashMap&lt;documento, marcas&gt;. */
  static final String CLAVE = "pentaholive.diff.marcas";

  // claves de las marcas de un documento (Map<String, Object>)
  static final String NODOS = "nodes";
  static final String SALTOS = "hops";
  static final String FANTASMAS = "ghosts";
  static final String SALTOS_FANTASMA = "ghostHops";

  /** Colores de las insignias (RGB). */
  static final int[] VERDE = { 34, 139, 58 };
  static final int[] ROJO = { 200, 40, 40 };
  static final int[] AMBAR = { 214, 132, 0 };
  static final int[] AZUL = { 40, 98, 196 };
  static final int[] GRIS = { 110, 110, 110 };

  private DiffLienzo() {
  }

  // ================================================================ marcas (sin SWT: se prueban sin pantalla)

  /**
   * Las marcas de un lado del diff. {@code antes} = el documento de la versión de antes (quitados, cambiados,
   * renombrados con su nombre de antes); si no, la de después (añadidos, cambiados, renombrados, movidos y, con
   * {@code fantasmas}, los quitados dibujados donde estaban).
   */
  static Map<String, Object> marcas( ResultadoDiff r, boolean antes, boolean fantasmas ) {
    Map<String, String> nodos = new LinkedHashMap<String, String>();
    Map<String, String> saltos = new LinkedHashMap<String, String>();
    List<Object[]> ghosts = new ArrayList<Object[]>();
    List<String[]> ghostHops = new ArrayList<String[]>();
    Map<String, String> nuevoDeViejo = new HashMap<String, String>();
    for ( Map.Entry<String, String> e : r.renombrados.entrySet() ) {
      nuevoDeViejo.put( e.getValue(), e.getKey() );
    }
    Que qn = r.nodos();
    for ( Diferencia d : r.cambios ) {
      if ( d.que == qn ) {
        if ( antes ) {
          if ( d.como == Como.QUITADO || d.como == Como.CAMBIADO || d.como == Como.RENOMBRADO
            || d.como == Como.MOVIDO ) {
            nodos.put( d.nombreAntes != null ? d.nombreAntes : d.nombre, d.como.simbolo );
          }
        } else if ( d.como == Como.QUITADO ) {
          if ( fantasmas && d.posAntes != null ) {
            ghosts.add( new Object[] { d.nombre, d.posAntes[ 0 ], d.posAntes[ 1 ] } );
          }
        } else {
          nodos.put( d.nombre, d.como.simbolo );
        }
      } else if ( d.que == Que.SALTO ) {
        if ( antes ) {
          if ( d.como == Como.QUITADO ) {
            saltos.put( d.desde + "\u0001" + d.hasta, d.como.simbolo );
          } else if ( d.como == Como.CAMBIADO ) {
            saltos.put( viejo( d.desde, r ) + "\u0001" + viejo( d.hasta, r ), d.como.simbolo );
          }
        } else if ( d.como == Como.QUITADO ) {
          if ( fantasmas ) {
            String desde = nuevoDeViejo.containsKey( d.desde ) ? nuevoDeViejo.get( d.desde ) : d.desde;
            String hasta = nuevoDeViejo.containsKey( d.hasta ) ? nuevoDeViejo.get( d.hasta ) : d.hasta;
            ghostHops.add( new String[] { desde, hasta } );
          }
        } else {
          saltos.put( d.desde + "\u0001" + d.hasta, d.como.simbolo );
        }
      }
    }
    Map<String, Object> m = new HashMap<String, Object>();
    m.put( NODOS, nodos );
    m.put( SALTOS, saltos );
    m.put( FANTASMAS, ghosts );
    m.put( SALTOS_FANTASMA, ghostHops );
    return m;
  }

  private static String viejo( String nuevo, ResultadoDiff r ) {
    String v = r.renombrados.get( nuevo );
    return v == null ? nuevo : v;
  }

  // ================================================================ registro en el Display

  @SuppressWarnings( "unchecked" )
  private static Map<Object, Object> registro( Display d, boolean crear ) {
    Object o = d.getData( CLAVE );
    if ( o instanceof Map ) {
      return (Map<Object, Object>) o;
    }
    if ( !crear ) {
      return null;
    }
    Map<Object, Object> m = new IdentityHashMap<Object, Object>();
    d.setData( CLAVE, m );
    return m;
  }

  /** Pone las marcas en el lienzo de un documento abierto y lo redibuja. (UI) */
  static void poner( Display d, Object meta, Map<String, Object> marcas ) {
    registro( d, true ).put( meta, marcas );
  }

  /** Quita las marcas de un documento. (UI) */
  static void quitar( Display d, Object meta ) {
    Map<Object, Object> m = registro( d, false );
    if ( m != null ) {
      m.remove( meta );
    }
  }

  static boolean tiene( Display d, Object meta ) {
    Map<Object, Object> m = registro( d, false );
    return m != null && m.containsKey( meta );
  }

  // ================================================================ dibujo

  /** Desde las extensiones TransPainterEnd / JobPainterEnd. Nunca lanza. (UI) */
  static void pintar( Object pintor ) {
    try {
      Display d = Display.getCurrent();
      if ( d == null ) {
        return;
      }
      Map<Object, Object> reg = registro( d, false );
      if ( reg == null || reg.isEmpty() ) {
        return;
      }
      Object meta = pintor instanceof TransPainter ? ( (TransPainter) pintor ).getTransMeta()
        : pintor instanceof JobPainter ? ( (JobPainter) pintor ).getJobMeta() : null;
      Object x = meta == null ? null : reg.get( meta );
      if ( !( x instanceof Map ) ) {
        return;
      }
      org.pentaho.di.core.gui.BasePainter<?, ?> bp = (org.pentaho.di.core.gui.BasePainter<?, ?>) pintor;
      dibujar( bp.getGc(), meta, (Map<?, ?>) x, bp.getOffset(), bp.getIconsize(), bp.getLinewidth() );
    } catch ( Throwable t ) {
      // nunca romper el pintado del lienzo
    }
  }

  /** Dibuja las marcas de un documento con el GC del pintor (coordenadas reales + offset). */
  static void dibujar( GCInterface gc, Object meta, Map<?, ?> marcas, Point offset, int icono, int linea ) {
    int ox = offset == null ? 0 : offset.x;
    int oy = offset == null ? 0 : offset.y;
    try {
      gc.setAntialias( true );
      // saltos quitados (fantasma): línea roja rayada
      Object gh = marcas.get( SALTOS_FANTASMA );
      Map<String, int[]> posFantasma = new HashMap<String, int[]>();
      Object gs = marcas.get( FANTASMAS );
      if ( gs instanceof List ) {
        for ( Object o : (List<?>) gs ) {
          Object[] g = (Object[]) o;
          posFantasma.put( (String) g[ 0 ], new int[] { (Integer) g[ 1 ], (Integer) g[ 2 ] } );
        }
      }
      if ( gh instanceof List ) {
        gc.setLineStyle( ELineStyle.DASH );
        gc.setLineWidth( Math.max( 1, linea ) + 1 );
        color( gc, ROJO, true );
        for ( Object o : (List<?>) gh ) {
          String[] s = (String[]) o;
          int[] a = posicion( meta, s[ 0 ], posFantasma );
          int[] b = posicion( meta, s[ 1 ], posFantasma );
          if ( a != null && b != null ) {
            // como los hops de Spoon: de borde a borde de los iconos (el del fantasma es la caja rayada, 2 px mas
            // grande), no de centro a centro por encima del icono
            int c = icono / 2;
            int[] l = recorte( a[ 0 ] + ox + c, a[ 1 ] + oy + c, b[ 0 ] + ox + c, b[ 1 ] + oy + c,
              c + ( posFantasma.containsKey( s[ 0 ] ) ? 4 : 2 ), c + ( posFantasma.containsKey( s[ 1 ] ) ? 4 : 2 ) );
            if ( l != null ) {
              gc.drawLine( l[ 0 ], l[ 1 ], l[ 2 ], l[ 3 ] );
              flecha( gc, l );
            }
          }
        }
      }
      // steps quitados (fantasma): caja roja rayada con el nombre
      for ( Map.Entry<String, int[]> e : posFantasma.entrySet() ) {
        int x = e.getValue()[ 0 ] + ox;
        int y = e.getValue()[ 1 ] + oy;
        gc.setLineStyle( ELineStyle.DASH );
        gc.setLineWidth( 2 );
        color( gc, ROJO, true );
        gc.drawRoundRectangle( x - 2, y - 2, icono + 4, icono + 4, 8, 8 );
        gc.setLineStyle( ELineStyle.SOLID );
        gc.setFont( EFont.SMALL );
        Point t = gc.textExtent( e.getKey() );
        gc.drawText( e.getKey(), x + icono / 2 - t.x / 2, y + icono + 6, true );
        insignia( gc, x + icono, y, Como.QUITADO.simbolo, 9 );
      }
      // saltos marcados: insignia en el medio
      Object ss = marcas.get( SALTOS );
      if ( ss instanceof Map ) {
        for ( Map.Entry<?, ?> e : ( (Map<?, ?>) ss ).entrySet() ) {
          String[] p = ( (String) e.getKey() ).split( "\u0001", 2 );
          int[] a = posicion( meta, p[ 0 ], null );
          int[] b = p.length > 1 ? posicion( meta, p[ 1 ], null ) : null;
          if ( a != null && b != null ) {
            int mx = ( a[ 0 ] + b[ 0 ] ) / 2 + ox + icono / 2;
            int my = ( a[ 1 ] + b[ 1 ] ) / 2 + oy + icono / 2;
            insignia( gc, mx, my, (String) e.getValue(), 7 );
          }
        }
      }
      // steps y entradas marcados: borde de color e insignia arriba a la derecha
      Object ns = marcas.get( NODOS );
      if ( ns instanceof Map ) {
        for ( Map.Entry<?, ?> e : ( (Map<?, ?>) ns ).entrySet() ) {
          int[] a = posicion( meta, (String) e.getKey(), null );
          if ( a == null ) {
            continue;
          }
          int x = a[ 0 ] + ox;
          int y = a[ 1 ] + oy;
          gc.setLineStyle( ELineStyle.SOLID );
          gc.setLineWidth( 2 );
          color( gc, colorDe( (String) e.getValue() ), true );
          gc.drawRoundRectangle( x - 3, y - 3, icono + 6, icono + 6, 8, 8 );
          insignia( gc, x + icono, y, (String) e.getValue(), 9 );
        }
      }
    } finally {
      gc.setLineStyle( ELineStyle.SOLID );
      gc.setLineWidth( Math.max( 1, linea ) );
      gc.setForeground( EColor.BLACK );
      gc.setBackground( EColor.BACKGROUND );
      gc.setFont( EFont.GRAPH );
    }
  }

  /**
   * El tramo visible de la recta de centro (x1, y1) a centro (x2, y2): sale del cuadrado de semilado {@code m1}
   * alrededor del primero y llega al de semilado {@code m2} alrededor del segundo. null si los cuadrados se tocan (no
   * queda nada que dibujar).
   */
  static int[] recorte( int x1, int y1, int x2, int y2, int m1, int m2 ) {
    double dx = x2 - x1;
    double dy = y2 - y1;
    double mx = Math.max( Math.abs( dx ), Math.abs( dy ) );
    if ( mx <= m1 + m2 ) {
      return null;
    }
    double t1 = m1 / mx;
    double t2 = 1 - m2 / mx;
    return new int[] { (int) Math.round( x1 + dx * t1 ), (int) Math.round( y1 + dy * t1 ), (int) Math.round( x1 + dx
      * t2 ), (int) Math.round( y1 + dy * t2 ) };
  }

  /** Una punta de flecha sólida al final del tramo (los hops de Spoon también la tienen). */
  private static void flecha( GCInterface gc, int[] l ) {
    double ang = Math.atan2( l[ 3 ] - l[ 1 ], l[ 2 ] - l[ 0 ] );
    int largo = 9;
    gc.setLineStyle( ELineStyle.SOLID );
    for ( double d : new double[] { -0.45, 0.45 } ) {
      gc.drawLine( l[ 2 ], l[ 3 ], (int) Math.round( l[ 2 ] - largo * Math.cos( ang + d ) ), (int) Math.round( l[ 3 ]
        - largo * Math.sin( ang + d ) ) );
    }
    gc.setLineStyle( ELineStyle.DASH );
  }

  static int[] colorDe( String simbolo ) {
    if ( Como.AGREGADO.simbolo.equals( simbolo ) ) {
      return VERDE;
    }
    if ( Como.QUITADO.simbolo.equals( simbolo ) ) {
      return ROJO;
    }
    if ( Como.RENOMBRADO.simbolo.equals( simbolo ) ) {
      return AZUL;
    }
    if ( Como.MOVIDO.simbolo.equals( simbolo ) ) {
      return GRIS;
    }
    return AMBAR;
  }

  private static void color( GCInterface gc, int[] c, boolean frente ) {
    if ( frente ) {
      gc.setForeground( c[ 0 ], c[ 1 ], c[ 2 ] );
    } else {
      gc.setBackground( c[ 0 ], c[ 1 ], c[ 2 ] );
    }
  }

  /** Un círculo de color con el símbolo en blanco, centrado en (cx, cy). + y − con líneas (no dependen de la fuente). */
  static void insignia( GCInterface gc, int cx, int cy, String simbolo, int radio ) {
    int[] c = colorDe( simbolo );
    color( gc, c, false );
    gc.fillRoundRectangle( cx - radio, cy - radio, radio * 2, radio * 2, radio * 2, radio * 2 );
    gc.setForeground( 255, 255, 255 );
    gc.setLineStyle( ELineStyle.SOLID );
    gc.setLineWidth( 1 );
    gc.drawRoundRectangle( cx - radio, cy - radio, radio * 2, radio * 2, radio * 2, radio * 2 );
    int l = radio / 2 + 1;
    if ( Como.AGREGADO.simbolo.equals( simbolo ) || Como.QUITADO.simbolo.equals( simbolo ) ) {
      gc.setLineWidth( 2 );
      gc.drawLine( cx - l, cy, cx + l, cy );
      if ( Como.AGREGADO.simbolo.equals( simbolo ) ) {
        gc.drawLine( cx, cy - l, cx, cy + l );
      }
      return;
    }
    gc.setFont( EFont.SMALL );
    Point t = gc.textExtent( simbolo );
    gc.drawText( simbolo, cx - t.x / 2, cy - t.y / 2, true );
  }

  /** Posición (real, sin offset) de un step o entrada por su clave (nombre, o nombre#n en un job), o la del fantasma. */
  static int[] posicion( Object meta, String clave, Map<String, int[]> fantasmas ) {
    if ( fantasmas != null && fantasmas.containsKey( clave ) ) {
      return fantasmas.get( clave );
    }
    if ( meta instanceof TransMeta ) {
      StepMeta s = ( (TransMeta) meta ).findStep( clave );
      return s == null ? null : new int[] { s.getLocation().x, s.getLocation().y };
    }
    if ( meta instanceof JobMeta ) {
      String nombre = clave;
      int nr = 0;
      int i = clave.lastIndexOf( '#' );
      if ( i > 0 && clave.substring( i + 1 ).matches( "\\d+" ) ) {
        nombre = clave.substring( 0, i );
        nr = Integer.parseInt( clave.substring( i + 1 ) );
      }
      JobEntryCopy c = ( (JobMeta) meta ).findJobEntry( nombre, nr, true );
      return c == null ? null : new int[] { c.getLocation().x, c.getLocation().y };
    }
    return null;
  }
}
