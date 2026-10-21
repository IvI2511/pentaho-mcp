package io.github.ivi2511.pentahomcp.informes;

/**
 * Formatos de salida. {@link #clave} es el nombre que usa la tool MCP build_report (argumento formats).
 *
 * @dueño WP0
 */
public enum Formato {

  /** HTML interactivo: diagramas propios en SVG (y Mermaid inline solo si queda alguno, como el modelo ER). */
  HTML( "html", "html" ),
  /** HTML liviano / vista previa IE11: sin JS, diagramas pre-renderizados. */
  HTML_ESTATICO( "html_light", "html" ),
  PDF( "pdf", "pdf" ),
  MARKDOWN( "md", "md" ),
  PPTX( "pptx", "pptx" ),
  /** Word: titulos reales, indice que Word completa al abrir, tablas con cabecera repetida. */
  DOCX( "docx", "docx" ),
  /** Excel: una hoja por tabla y por grafico, mas un indice. */
  XLSX( "xlsx", "xlsx" ),
  /** Carpeta &lt;base&gt;_imagenes con PNG y SVG de cada diagrama y grafico (extension vacia = carpeta). */
  IMAGENES( "images", "" ),
  /** El modelo tal cual (ModeloJson). */
  JSON( "json", "json" );

  public final String clave;
  /** Sin punto; vacia si el destino es una carpeta (IMAGENES). */
  public final String extension;

  Formato( String clave, String extension ) {
    this.clave = clave;
    this.extension = extension;
  }

  /** true si el destino es una carpeta y no un archivo. */
  public boolean esCarpeta() {
    return extension.isEmpty();
  }

  /**
   * true si el formato usa Edge/Chrome cuando lo hay: el PDF lo imprime el navegador y PowerPoint, Word e
   * imagenes pre-renderizan con el los diagramas Mermaid que queden (sin navegador salen como tabla o no salen).
   */
  public boolean usaNavegador() {
    return this == PDF || this == PPTX || this == DOCX || this == IMAGENES;
  }

  /** El formato con esa clave (sin distinguir mayusculas), o null. */
  public static Formato deClave( String clave ) {
    for ( Formato f : values() ) {
      if ( f.clave.equalsIgnoreCase( clave == null ? "" : clave.trim() ) ) {
        return f;
      }
    }
    return null;
  }
}
