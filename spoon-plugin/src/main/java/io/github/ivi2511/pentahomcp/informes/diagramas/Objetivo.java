package io.github.ivi2511.pentahomcp.informes.diagramas;

/**
 * Caja donde va a ir el diagrama y cuanta letra hace falta. Unidades: px CSS para PDF, DOCX y HTML (1 px =
 * 0,75 pt) y puntos para PPTX (PptxDiapos pone 1 pt por px). La letra de referencia del diagrama es la de
 * los nombres (12 px); la efectiva es 12 * escala * unidad.
 * <ul>
 * <li>A4 / LETTER: la columna de la hoja impresa menos el titulo y la nota de la figura.</li>
 * <li>DOCX: A4 con los margenes de Word (2,5 cm).</li>
 * <li>DIAPOSITIVA: la zona util de una diapositiva 16:9 de PptxDiapos debajo del titulo.</li>
 * <li>PANTALLA: la columna de la hoja del HTML y lo que se ve sin desplazar; nunca se agranda.</li>
 * </ul>
 *
 * @dueño WP6
 */
public final class Objetivo {

  /** A4 vertical: 210 - 2*16 mm de ancho; 297 - 18 - 20 mm de alto menos titulo y nota de la figura. */
  public static final Objetivo A4 = new Objetivo( "a4", 672, 920, 1.25, 0.75, 7.0, 8.5 );
  /** Carta vertical: 215,9 - 2*16 mm; 279,4 - 38 mm menos titulo y nota. */
  public static final Objetivo LETTER = new Objetivo( "letter", 695, 852, 1.25, 0.75, 7.0, 8.5 );
  /** Word A4 con margenes de 2,5 cm (160 x 247 mm) menos el pie de la figura. */
  public static final Objetivo DOCX = new Objetivo( "docx", 605, 880, 1.25, 0.75, 7.0, 8.5 );
  /** Diapositiva 16:9: ancho util 880 x (494 - 80 del contenido - subtitulo y nota). */
  public static final Objetivo DIAPOSITIVA = new Objetivo( "diapositiva", 880, 390, 1.6, 1.0, 10.0, 12.0 );
  /** Pantalla: columna de la hoja del HTML (920 - 2*40 px) y lo que se ve sin desplazar (~640 px). */
  public static final Objetivo PANTALLA = new Objetivo( "pantalla", 840, 640, 1.0, 1.0, 10.0, 12.0 );

  /** Letra de referencia de los nombres (px del dibujo sin escalar). */
  public static final double LETRA = 12;

  public final String nombre;
  public final double ancho;
  public final double alto;
  /** Cuanto se puede agrandar (vectorial) sin que quede desproporcionado. */
  public final double maxEscala;
  /** Puntos (o px en pantalla) por unidad de la caja. */
  public final double unidad;
  /** Letra minima e ideal en pt (px en pantalla). */
  public final double letraMin;
  public final double letraIdeal;

  public Objetivo( String nombre, double ancho, double alto, double maxEscala, double unidad, double letraMin,
                   double letraIdeal ) {
    this.nombre = nombre;
    this.ancho = ancho;
    this.alto = alto;
    this.maxEscala = maxEscala;
    this.unidad = unidad;
    this.letraMin = letraMin;
    this.letraIdeal = letraIdeal;
  }

  /** La misma caja con otro ancho y alto (por ejemplo, una diapositiva con menos lugar). */
  public Objetivo conCaja( double nuevoAncho, double nuevoAlto ) {
    return new Objetivo( nombre, nuevoAncho, nuevoAlto, maxEscala, unidad, letraMin, letraIdeal );
  }

  public double proporcion() {
    return ancho / alto;
  }

  /** Escala a la que entra un dibujo de w x h (sin tope). */
  public double escala( double w, double h ) {
    return Math.min( ancho / w, alto / h );
  }

  /** Escala con el tope de agrandamiento. */
  public double escalaFinal( double w, double h ) {
    return Math.min( maxEscala, escala( w, h ) );
  }

  /** Letra efectiva (pt, o px en pantalla) de un dibujo de w x h en esta caja. */
  public double letra( double w, double h ) {
    return LETRA * escalaFinal( w, h ) * unidad;
  }

  @Override
  public String toString() {
    return nombre;
  }
}
