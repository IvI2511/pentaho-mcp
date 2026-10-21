package io.github.ivi2511.pentahomcp.informes.diagramas;

import io.github.ivi2511.pentahomcp.informes.graficos.SvgAPng;

/**
 * Una figura lista: el SVG de una parte del diagrama y como va en su {@link Objetivo}.
 * <ul>
 * <li>{@link #svg}: width/height = viewBox = tamano natural ({@link #ancho} x {@link #alto} px), como los
 * graficos de GraficosSvg (para el .svg del Markdown y para Batik).</li>
 * <li>{@link #svgEscalado()}: el mismo SVG con width/height = el tamano final en la caja (HTML inline y
 * PDF: asi la figura nunca pasa del alto de la pagina).</li>
 * <li>{@link #png(double)}: PNG con Batik al tamano final por un factor (PPTX: 2).</li>
 * </ul>
 *
 * @dueño WP6
 */
public final class Dibujo {

  /** SVG 1.1 con width/height iguales al viewBox. */
  public final String svg;
  /** Tamano natural del SVG en px. */
  public final double ancho;
  public final double alto;
  /** Escala en la caja del objetivo (con el tope de agrandamiento). */
  public final double escala;
  /** Letra efectiva de los nombres en el objetivo (pt; px en pantalla). */
  public final double letra;
  /** Parte (desde 1) y total de partes; 1 de 1 si no se partio. */
  public final int parte;
  public final int partes;
  /** Titulo de la figura (con "(1 de 3)" si se partio). */
  public final String titulo;
  /** LR o TB, cuantas bandas y cuantos cruces quedaron. */
  public final String orientacion;
  public final int bandas;
  public final int cruces;
  public final Objetivo objetivo;

  Dibujo( String svg, double ancho, double alto, Objetivo obj, int parte, int partes, String titulo,
          String orientacion, int bandas, int cruces ) {
    this.svg = svg;
    this.ancho = ancho;
    this.alto = alto;
    this.objetivo = obj;
    this.escala = obj.escalaFinal( ancho, alto );
    this.letra = obj.letra( ancho, alto );
    this.parte = parte;
    this.partes = partes;
    this.titulo = titulo;
    this.orientacion = orientacion;
    this.bandas = bandas;
    this.cruces = cruces;
  }

  /** Ancho final en la caja (px; pt en la diapositiva). */
  public double anchoFinal() {
    return ancho * escala;
  }

  /** Alto final en la caja (px; pt en la diapositiva). */
  public double altoFinal() {
    return alto * escala;
  }

  /** Proporcion ancho / alto. */
  public double proporcion() {
    return ancho / alto;
  }

  /** El SVG con width/height = tamano final (el viewBox sigue siendo el natural). */
  public String svgEscalado() {
    return conTamano( Math.round( anchoFinal() * 100 ) / 100.0, Math.round( altoFinal() * 100 ) / 100.0 );
  }

  /** El SVG con width/height dados (en px) y el viewBox natural. */
  public String conTamano( double w, double h ) {
    String cab = "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" width=\"" + (int) Math.ceil( ancho )
      + "\" height=\"" + (int) Math.ceil( alto ) + "\"";
    if ( !svg.startsWith( cab ) ) {
      return svg;
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" width=\"" + DiagramaSvg.n( w )
      + "\" height=\"" + DiagramaSvg.n( h ) + "\"" + svg.substring( cab.length() );
  }

  /** PNG del tamano final por factor (2 = doble resolucion para PPTX). */
  public byte[] png( double factor ) throws Exception {
    int w = (int) Math.round( anchoFinal() * Math.max( 0.1, factor ) );
    w = Math.max( 1, Math.min( SvgAPng.MAX_ANCHO, w ) );
    return SvgAPng.png( svg, w );
  }

  @Override
  public String toString() {
    return String.format( java.util.Locale.ROOT, "%s %d/%d %s r=%d %.0fx%.0f s=%.2f letra=%.1f cruces=%d", objetivo,
      parte, partes, orientacion, bandas, ancho, alto, escala, letra, cruces );
  }
}
