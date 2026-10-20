package io.github.ivi2511.pentahomcp.informes;

/**
 * Formatos de salida. {@link #clave} es el nombre que usa la tool MCP build_report (argumento formats).
 *
 * @dueño WP0
 */
public enum Formato {

  /** HTML interactivo: Mermaid inline, unos 2,6 MB. */
  HTML( "html", "html" ),
  /** HTML liviano / vista previa IE11: sin JS, diagramas pre-renderizados. */
  HTML_ESTATICO( "html_light", "html" ),
  PDF( "pdf", "pdf" ),
  MARKDOWN( "md", "md" ),
  PPTX( "pptx", "pptx" ),
  /** El modelo tal cual (ModeloJson). */
  JSON( "json", "json" );

  public final String clave;
  /** Sin punto. */
  public final String extension;

  Formato( String clave, String extension ) {
    this.clave = clave;
    this.extension = extension;
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
