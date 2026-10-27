package io.github.ivi2511.pentahomcp.traductor;

/**
 * Lo que la persona elige al traducir (diálogo IDC › Traducir a Python… o la herramienta translate_to_python):
 * formato, biblioteca, idioma de comentarios y celdas, y qué texto y gráficos agregar.
 */
public final class Opciones {

  /** "py" o "ipynb". */
  public String formato = "ipynb";
  public Destino destino = Destino.PANDAS;
  /** "es", "en" o "pt": comentarios del .py y celdas de texto del notebook. */
  public String idioma = "es";
  /** Título del notebook o del script (null: el nombre de la transformación o del job). */
  public String titulo;
  /** Autor (null: no se pone). */
  public String autor;
  /** Texto propio de introducción, en Markdown (null: ninguno). */
  public String introduccion;
  /** Una explicación por step (qué hace y por qué), además del código. */
  public boolean explicaciones = true;
  /** Vista previa de cada DataFrame (head / show) en el notebook. */
  public boolean vistas = true;
  /** Gráficos de análisis exploratorio (EDA) con matplotlib: distribuciones, nulos, agregados. */
  public boolean eda = false;
  /** Notas de lo que difiere de PDI (redondeo, nulos, formatos...). */
  public boolean notas = true;

  public boolean notebook() {
    return "ipynb".equalsIgnoreCase( formato );
  }
}
