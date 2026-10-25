package io.github.ivi2511.pentahomcp.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * La fachada del diff semántico: de dónde sale cada versión ({@link Fuente}: un archivo, una revisión de git, el XML
 * de un documento abierto) y la comparación con sus etiquetas.
 */
public final class Diff {

  private Diff() {
  }

  /** Una versión de un .ktr/.kjb: su XML y de dónde salió. */
  public static final class Fuente {
    public final byte[] xml;
    /** Para los títulos: la ruta, «git HEAD: x.ktr», «abierto en Spoon»... */
    public final String etiqueta;

    public Fuente( byte[] xml, String etiqueta ) {
      this.xml = xml;
      this.etiqueta = etiqueta;
    }

    public static Fuente texto( String xml, String etiqueta ) {
      return new Fuente( xml.getBytes( StandardCharsets.UTF_8 ), etiqueta );
    }
  }

  public static Fuente archivo( File f ) throws IOException {
    File a = f.getAbsoluteFile();
    if ( !a.isFile() ) {
      throw new IOException( "File not found: " + a );
    }
    return new Fuente( Files.readAllBytes( a.toPath() ), a.getPath() );
  }

  /** La versión de una revisión de git (HEAD, HEAD~1, main, un hash). */
  public static Fuente git( File f, String revision ) throws Git.GitError {
    String rev = revision == null || revision.trim().isEmpty() ? "HEAD" : revision.trim();
    return new Fuente( Git.mostrar( f, rev ), "git " + rev + ": " + f.getName() );
  }

  /**
   * Interpreta una referencia a la versión de antes: "git:REV" (o "HEAD", "HEAD~n"), o la ruta de un archivo (relativa
   * a la carpeta de {@code base}). {@code base} es el archivo de la versión de después (para git y rutas relativas).
   */
  public static Fuente referencia( String ref, File base ) throws IOException, Git.GitError {
    String r = ref == null ? "" : ref.trim();
    if ( r.isEmpty() ) {
      throw new IOException( "Nothing to compare with" );
    }
    String rev = revision( r );
    if ( rev != null ) {
      if ( base == null ) {
        throw new IOException( "A git revision needs a file that is in a git repository" );
      }
      return git( base, rev );
    }
    File f = new File( r );
    if ( !f.isAbsolute() && base != null && base.getAbsoluteFile().getParentFile() != null ) {
      File rel = new File( base.getAbsoluteFile().getParentFile(), r );
      if ( rel.isFile() ) {
        f = rel;
      }
    }
    return archivo( f );
  }

  /** "git:main" -> "main"; "HEAD", "HEAD~2", "HEAD^" -> tal cual; otra cosa -> null (es una ruta). */
  public static String revision( String ref ) {
    if ( ref.regionMatches( true, 0, "git:", 0, 4 ) ) {
      return ref.substring( 4 ).trim().isEmpty() ? "HEAD" : ref.substring( 4 ).trim();
    }
    if ( ref.matches( "(HEAD|@)([~^]\\d*)*" ) ) {
      return ref;
    }
    return null;
  }

  public static ResultadoDiff comparar( Fuente antes, Fuente despues, boolean ignorarPosiciones )
    throws Xmls.XmlInvalido {
    Documento a = Documento.leer( antes.xml );
    Documento b = Documento.leer( despues.xml );
    if ( !a.tipo.equals( b.tipo ) ) {
      throw new Xmls.XmlInvalido( "Cannot compare a " + a.tipo + " (" + antes.etiqueta + ") with a " + b.tipo + " ("
        + despues.etiqueta + ")" );
    }
    ResultadoDiff r = Comparador.comparar( a, b, ignorarPosiciones );
    r.etiquetaAntes = antes.etiqueta;
    r.etiquetaDespues = despues.etiqueta;
    return r;
  }
}
