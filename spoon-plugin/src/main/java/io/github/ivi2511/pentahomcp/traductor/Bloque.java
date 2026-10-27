package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.List;

/**
 * La traducción de un step (o de una entrada de job): líneas de Python sin sangría base, su estado y las notas de lo
 * que difiere de PDI. En el .py es un párrafo con un comentario de cabecera; en el notebook, una celda de texto (la
 * explicación) y una de código (más la vista previa de {@link #vista}).
 */
public final class Bloque {

  /** Nombre del step o de la entrada, tal como está en el lienzo. */
  public final String paso;
  /** Id de plugin (CsvInput, FilterRows, TRANS...). */
  public final String tipo;
  public Estado estado = Estado.EXACTO;
  /** Una línea que explica qué hace, en el idioma del contexto (null: el escritor usa DescribirStep). */
  public String explicacion;
  /** Las líneas de código, sin sangría base. */
  public final List<String> codigo = new ArrayList<>();
  /** Diferencias con PDI y avisos (van como comentarios «# NOTA:» y al resumen). */
  public final List<String> notas = new ArrayList<>();
  /** Variable (DataFrame) que el notebook muestra después del bloque; null = ninguna. */
  public String vista;
  /** Sugerencia de gráfico para el notebook (null = ninguno): ver {@link Grafico}. */
  public Grafico grafico;

  public Bloque( String paso, String tipo ) {
    this.paso = paso;
    this.tipo = tipo;
  }

  /** Agrega una línea de código. */
  public Bloque l( String linea ) {
    codigo.add( linea );
    return this;
  }

  /** Agrega una nota y baja el estado a APROXIMADO si estaba en EXACTO. */
  public Bloque aproximado( String nota ) {
    notas.add( nota );
    estado = estado.peor( Estado.APROXIMADO );
    return this;
  }

  /** Agrega una nota y deja el bloque en MANUAL. */
  public Bloque manual( String nota ) {
    notas.add( nota );
    estado = Estado.MANUAL;
    return this;
  }

  /** Una nota informativa que no cambia el estado. */
  public Bloque nota( String nota ) {
    notas.add( nota );
    return this;
  }

  /** Qué graficar en el notebook: barras de y por x, histograma de y o línea de y por x. */
  public static final class Grafico {
    public final String tipo; // "barras", "histograma", "linea"
    public final String df;
    public final String x;
    public final String y;

    public Grafico( String tipo, String df, String x, String y ) {
      this.tipo = tipo;
      this.df = df;
      this.x = x;
      this.y = y;
    }
  }
}
