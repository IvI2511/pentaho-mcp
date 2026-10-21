package io.github.ivi2511.pentahomcp.informes.render;

import java.util.List;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.diagramas.Diagramas;
import io.github.ivi2511.pentahomcp.informes.diagramas.Dibujo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Objetivo;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;

/**
 * Lo que comparten los renderizadores para dibujar un {@link Diagrama} con el motor propio: los dibujos de su
 * grafo para la caja de cada salida (HTML: pantalla, PDF: A4 o carta, PPTX: diapositiva, DOCX: hoja de Word)
 * y cuando el lienzo de Spoon va ademas del diagrama propio.
 * <p>
 * El diagrama propio es la figura principal. El lienzo (la captura real de Spoon) solo acompana si es de
 * proporciones razonables: una tira de 4345 x 928 px de un flujo largo queda ilegible en una hoja o una
 * diapositiva, asi que ahi se omite (en el HTML va plegado, en el PowerPoint al anexo).
 *
 * @dueño WP6
 */
final class Figuras {

  /** Ancho / alto maximo del lienzo para mostrarlo junto al diagrama propio. */
  static final double PROPORCION_LIENZO = 3.0;

  private Figuras() {
  }

  /**
   * Los dibujos del diagrama propio para el objetivo, o null si el bloque no tiene grafo o el motor fallo
   * (entonces va lo de siempre: el Mermaid pre-renderizado o la tabla alternativa).
   */
  static List<Dibujo> dibujos( Diagrama d, Objetivo o, String idioma ) {
    if ( d == null || d.grafo == null || d.grafo.nodos == null || d.grafo.nodos.isEmpty() ) {
      return null;
    }
    try {
      List<Dibujo> l = Diagramas.dibujar( d.grafo, o, idioma );
      return l == null || l.isEmpty() ? null : l;
    } catch ( Throwable t ) {
      return null;
    }
  }

  /** true si el diagrama se dibuja con el motor propio (tiene grafo con nodos). */
  static boolean propio( Diagrama d ) {
    return d != null && d.grafo != null && d.grafo.nodos != null && !d.grafo.nodos.isEmpty();
  }

  /** El lienzo de Spoon del diagrama, o null si no tiene. */
  static Imagen lienzo( Diagrama d ) {
    return d != null && d.imagen != null && d.imagen.datos != null && d.imagen.datos.length > 0 ? d.imagen : null;
  }

  /** true si la imagen no es una tira: ancho / alto hasta {@link #PROPORCION_LIENZO}. */
  static boolean proporcionada( Imagen im ) {
    if ( im == null ) {
      return false;
    }
    int w = im.ancho;
    int h = im.alto;
    if ( ( w <= 0 || h <= 0 ) && im.datos != null ) {
      try {
        int[] wh = PptxDiapos.medir( im.datos );
        w = wh[ 0 ];
        h = wh[ 1 ];
      } catch ( RuntimeException e ) {
        return false;
      }
    }
    return w > 0 && h > 0 && w / (double) h <= PROPORCION_LIENZO;
  }

  /** «Parte 2 de 3» en el idioma del informe. */
  static String parte( String idioma, int i, int n ) {
    return Rotulos.de( Diagramas.AREA, idioma ).f( "figura.parte", i, n );
  }
}
