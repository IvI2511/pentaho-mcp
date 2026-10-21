package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayList;
import java.util.List;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Esquema;

/**
 * Diagramas propios (flujo, job y linaje de campos) dibujados en Java en vez de Mermaid: mas cuadrados
 * (una cadena larga se envuelve en bandas, como una serpiente que se lee de izquierda a derecha y de arriba
 * abajo), con la letra que pide cada salida y con menos cruces. Sin Kettle ni navegador: mismo resultado en
 * HTML, PDF, PPTX y DOCX, y deterministico (la misma entrada da el mismo SVG).
 * <p>
 * Uso: el recolector arma un {@link Grafo}; cada renderizador pide los dibujos para su caja:
 *
 * <pre>
 * List&lt;Dibujo&gt; ds = Diagramas.dibujar( grafo, Objetivo.A4, informe.idioma );
 * for ( Dibujo d : ds ) { ... d.svgEscalado() ... d.titulo ... }
 * </pre>
 *
 * Si en la caja la letra quedaria chica el diagrama sale partido en varias figuras ({@link Dibujo#parte} de
 * {@link Dibujo#partes}). Pasos: {@link Capas} (capas y ficticios), {@link Orden} (cruces), {@link Envoltura}
 * (bandas), {@link Posiciones} (coordenadas), {@link Rutas} (lineas), {@link Disposicion} (la mejor
 * variante), {@link Partir} y {@link DiagramaSvg}.
 *
 * @dueño WP6
 */
public final class Diagramas {

  /** Area de textos de este paquete (fantasmas, partes, leyenda). */
  public static final String AREA = "diagramas";

  private Diagramas() {
  }

  /** Los dibujos del grafo para el objetivo, con los textos propios en ese idioma (en | es | pt). */
  public static List<Dibujo> dibujar( Grafo g, Objetivo obj, String idioma ) {
    return dibujar( g, obj, Rotulos.de( AREA, idioma ) );
  }

  /**
   * Los dibujos del grafo para el objetivo. rot puede ser de cualquier area: se usa su idioma con los textos
   * de {@link #AREA}. Nunca devuelve una lista vacia (un grafo vacio da un recuadro en blanco).
   */
  public static List<Dibujo> dibujar( Grafo g, Objetivo obj, Rotulos rot ) {
    Rotulos r = rot == null ? Rotulos.de( AREA, "en" ) : AREA.equals( rot.area() ) ? rot : Rotulos.de( AREA, rot
      .idioma() );
    Objetivo o = obj == null ? Objetivo.PANTALLA : obj;
    Grafo p = g.preparada();
    List<Grafo> partes = p.linaje() ? Partir.linaje( p, o, r ) : Partir.flujo( p, o, r );
    List<Dibujo> out = new ArrayList<>();
    int i = 1;
    for ( Grafo x : partes ) {
      String titulo = g.titulo == null ? "" : g.titulo;
      if ( partes.size() > 1 ) {
        titulo = ( titulo + " " + r.f( "parte", i, partes.size() ) ).trim();
      }
      out.add( dibujarUna( x, o, r, titulo, i, partes.size() ) );
      i++;
    }
    return out;
  }

  /** Los dibujos del grafo de un Diagrama del modelo (Diagrama.grafo); lista vacia si no hay grafo. */
  public static List<Dibujo> dibujar( Esquema e, Objetivo obj, String idioma ) {
    if ( e == null || e.nodos == null || e.nodos.isEmpty() ) {
      return new ArrayList<>();
    }
    return dibujar( Grafo.desde( e ), obj, idioma );
  }

  /** Un solo dibujo sin partir (aunque la letra quede chica): para el Markdown y las comparaciones. */
  public static Dibujo dibujarEntero( Grafo g, Objetivo obj, String idioma ) {
    Rotulos r = Rotulos.de( AREA, idioma );
    return dibujarUna( g.preparada(), obj == null ? Objetivo.PANTALLA : obj, r, g.titulo == null ? "" : g.titulo, 1,
      1 );
  }

  static Dibujo dibujarUna( Grafo x, Objetivo o, Rotulos r, String titulo, int parte, int partes ) {
    double extra = DiagramaSvg.altoLeyenda( x, r );
    Disposicion.Eleccion e = Disposicion.elegir( x, o, extra );
    DiagramaSvg s = DiagramaSvg.dibujar( e.d, titulo, r, o );
    return new Dibujo( s.svg, s.ancho, s.alto, o, parte, partes, titulo, e.d.tb ? "TB" : "LR", e.d.bandas(),
      e.d.cruces );
  }
}
