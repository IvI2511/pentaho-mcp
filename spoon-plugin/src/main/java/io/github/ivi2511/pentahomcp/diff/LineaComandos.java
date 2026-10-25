package io.github.ivi2511.pentahomcp.diff;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Los comandos de git y de consola de Pentaho Live (diseño E2), sin Spoon ni Kettle: solo el jar del plugin y Java.
 * Los lanza {@code pentaho-live <comando>} (launcher/pentaho-live.cmd y launcher/pentaho-live):
 *
 * <pre>
 *   pentaho-live normalize [--check] [--stdout] archivo-o-carpeta...
 *   pentaho-live diff [--format text|markdown|json] [--positions] [--lang en|es|pt] antes.ktr despues.ktr
 *   pentaho-live diff [--rev REV] archivo.ktr              (la revisión de git, HEAD por defecto, contra el archivo)
 *   pentaho-live diff [--rev REV] [carpetas]               (cada .ktr/.kjb que cambió desde REV: la CI de una PR)
 *   pentaho-live textconv [--positions] [--lang en|es|pt] archivo.ktr   (para git: diff.kettle.textconv)
 *   pentaho-live pre-commit                                (lo llama el hook de pre-commit)
 *   pentaho-live git-setup                                 (las líneas de .gitattributes y git config)
 * </pre>
 *
 * Códigos de salida: normalize 0 (o 1 con --check si algún archivo no está normalizado); diff 0 sin diferencias, 1
 * con diferencias; textconv 0; 2 si hubo un error.
 */
public final class LineaComandos {

  static final int OK = 0;
  static final int DIFERENTE = 1;
  static final int ERROR = 2;

  private final PrintStream out;
  private final PrintStream err;
  /** Carpeta de trabajo (las pruebas la cambian); null = la del proceso. */
  File cwd;

  LineaComandos( PrintStream out, PrintStream err ) {
    this.out = out;
    this.err = err;
  }

  public static void main( String[] args ) {
    PrintStream o = utf8( System.out );
    PrintStream e = utf8( System.err );
    int codigo;
    try {
      codigo = new LineaComandos( o, e ).correr( args );
    } catch ( Throwable t ) {
      e.println( "pentaho-live: " + t );
      codigo = ERROR;
    }
    o.flush();
    e.flush();
    System.exit( codigo );
  }

  private static PrintStream utf8( PrintStream p ) {
    try {
      return new PrintStream( new FileOutputStream( p == System.err ? java.io.FileDescriptor.err
        : java.io.FileDescriptor.out ), true, "UTF-8" );
    } catch ( UnsupportedEncodingException e ) {
      return p;
    }
  }

  int correr( String[] args ) {
    if ( args.length == 0 ) {
      ayuda();
      return ERROR;
    }
    String cmd = args[ 0 ].toLowerCase( Locale.ROOT );
    List<String> resto = new ArrayList<String>( Arrays.asList( args ).subList( 1, args.length ) );
    try {
      switch ( cmd ) {
        case "normalize":
          return normalizar( resto );
        case "diff":
          return diff( resto );
        case "textconv":
          return textconv( resto );
        case "pre-commit":
          return preCommit( resto );
        case "git-setup":
          gitSetup();
          return OK;
        case "help":
        case "--help":
        case "-h":
          ayuda();
          return OK;
        default:
          err.println( "pentaho-live: unknown command '" + args[ 0 ] + "'" );
          ayuda();
          return ERROR;
      }
    } catch ( Uso u ) {
      err.println( "pentaho-live " + cmd + ": " + u.getMessage() );
      return ERROR;
    }
  }

  /** Un argumento mal puesto. */
  static final class Uso extends Exception {
    private static final long serialVersionUID = 1L;

    Uso( String m ) {
      super( m );
    }
  }

  // ================================================================ normalize

  int normalizar( List<String> args ) throws Uso {
    boolean revisar = sacar( args, "--check" );
    boolean stdout = sacar( args, "--stdout" );
    sinOpciones( args );
    if ( args.isEmpty() ) {
      throw new Uso( "which files? Example: pentaho-live normalize etl/*.ktr  (or a folder)" );
    }
    List<File> archivos = new ArrayList<File>();
    for ( String a : args ) {
      File f = archivo( a );
      if ( f.isDirectory() ) {
        buscar( f, archivos );
      } else if ( f.isFile() ) {
        archivos.add( f );
      } else {
        err.println( "pentaho-live normalize: not found: " + f );
        return ERROR;
      }
    }
    int cambiados = 0;
    int errores = 0;
    for ( File f : archivos ) {
      try {
        byte[] antes = Files.readAllBytes( f.toPath() );
        String n = Normalizador.normalizar( antes );
        byte[] despues = n.getBytes( StandardCharsets.UTF_8 );
        boolean distinto = !Arrays.equals( antes, despues );
        if ( stdout ) {
          out.print( n );
          continue;
        }
        if ( !distinto ) {
          continue;
        }
        cambiados++;
        if ( revisar ) {
          out.println( "not normalized: " + f.getPath() );
        } else {
          Files.write( f.toPath(), despues );
          out.println( "normalized: " + f.getPath() );
        }
      } catch ( Exception e ) {
        errores++;
        err.println( "pentaho-live normalize: " + f.getPath() + ": " + e.getMessage() );
      }
    }
    if ( errores > 0 ) {
      return ERROR;
    }
    if ( revisar && cambiados > 0 ) {
      err.println( cambiados + " file(s) not normalized. Run: pentaho-live normalize <files>" );
      return DIFERENTE;
    }
    return OK;
  }

  private static void buscar( File dir, List<File> out ) {
    File[] hs = dir.listFiles();
    if ( hs == null ) {
      return;
    }
    Arrays.sort( hs );
    for ( File h : hs ) {
      if ( h.isDirectory() && !h.getName().startsWith( "." ) ) {
        buscar( h, out );
      } else if ( esKettle( h.getName() ) ) {
        out.add( h );
      }
    }
  }

  static boolean esKettle( String nombre ) {
    String n = nombre.toLowerCase( Locale.ROOT );
    return n.endsWith( ".ktr" ) || n.endsWith( ".kjb" );
  }

  // ================================================================ diff

  int diff( List<String> args ) throws Uso {
    String formato = valor( args, "--format", "text" ).toLowerCase( Locale.ROOT );
    String idioma = valor( args, "--lang", idiomaPorDefecto() );
    String rev = valor( args, "--rev", null );
    boolean posiciones = sacar( args, "--positions" );
    sinOpciones( args );
    if ( !Arrays.asList( "text", "markdown", "md", "json" ).contains( formato ) ) {
      throw new Uso( "--format is text, markdown or json" );
    }
    if ( args.isEmpty() || rev != null && ( args.size() > 1 || archivo( args.get( 0 ) ).isDirectory() ) ) {
      return diffCambios( rev == null ? "HEAD" : rev, args, formato, idioma, posiciones );
    }
    try {
      Diff.Fuente antes;
      Diff.Fuente despues;
      if ( args.size() == 1 ) {
        File f = archivo( args.get( 0 ) );
        despues = Diff.archivo( f );
        antes = Diff.git( f, rev == null ? "HEAD" : rev );
      } else if ( args.size() == 2 ) {
        if ( rev != null ) {
          throw new Uso( "--rev goes with one file: pentaho-live diff --rev HEAD~1 file.ktr" );
        }
        File b = archivo( args.get( 1 ) );
        String ref = args.get( 0 );
        antes = Diff.referencia( Diff.revision( ref ) == null ? archivo( ref ).getPath() : ref, b );
        despues = Diff.archivo( b );
      } else {
        throw new Uso( "pentaho-live diff before.ktr after.ktr  |  pentaho-live diff [--rev REV] file.ktr" );
      }
      ResultadoDiff r = Diff.comparar( antes, despues, !posiciones );
      if ( formato.equals( "json" ) ) {
        out.println( Salidas.json( r, idioma ) );
      } else if ( formato.startsWith( "m" ) ) {
        out.print( Salidas.markdown( r, idioma ) );
      } else {
        out.print( Salidas.texto( r, idioma ) );
      }
      return r.iguales() ? OK : DIFERENTE;
    } catch ( Uso u ) {
      throw u;
    } catch ( Exception e ) {
      err.println( "pentaho-live diff: " + e.getMessage() );
      return ERROR;
    }
  }

  /** Un .ktr/.kjb que cambió entre la revisión y la carpeta de trabajo. */
  static final class Cambio {
    /** A (nuevo), D (borrado) o M (cambiado; también T). */
    final char estado;
    /** Relativa a la raíz del repositorio, con «/». */
    final String ruta;

    Cambio( char estado, String ruta ) {
      this.estado = estado;
      this.ruta = ruta;
    }
  }

  /**
   * {@code pentaho-live diff [--rev REV] [carpetas o archivos]} sin un archivo solo: cada .ktr/.kjb que cambió entre la
   * revisión (HEAD por defecto) y la carpeta de trabajo, como en una pull request. Lo usa el workflow de CI que crea
   * {@code pentaho-live init}: {@code pentaho-live diff --rev origin/main --format markdown}. Los archivos nuevos y los
   * borrados van con una línea (sin listar cada step). Códigos: 0 sin cambios, 1 con cambios, 2 error.
   */
  int diffCambios( String rev, List<String> rutas, String formato, String idioma, boolean posiciones ) {
    if ( !Git.REVISION.matcher( rev ).matches() ) {
      err.println( "pentaho-live diff: not a valid git revision: " + rev );
      return ERROR;
    }
    File dir = cwd != null ? cwd.getAbsoluteFile() : new File( "." ).getAbsoluteFile();
    Rotulos t = Salidas.rotulos( idioma );
    List<Cambio> cambios;
    File repo;
    try {
      Git.Salida top = Git.correr( dir, "rev-parse", "--show-toplevel" );
      if ( top.codigo != 0 ) {
        err.println( "pentaho-live diff: not in a git repository (" + dir + ")" );
        return ERROR;
      }
      repo = new File( new String( top.salida, StandardCharsets.UTF_8 ).trim() );
      Git.Salida v = Git.correr( dir, "rev-parse", "--verify", "--quiet", rev + "^{commit}" );
      if ( v.codigo != 0 ) {
        err.println( "pentaho-live diff: unknown git revision '" + rev + "'. In a shallow CI checkout fetch it first, "
          + "for example: git fetch --depth=1 origin main:refs/remotes/origin/main" );
        return ERROR;
      }
      List<String> cmd = new ArrayList<String>( Arrays.asList( "diff", "--name-status", "-z", "--no-renames", rev,
        "--" ) );
      for ( String r : rutas ) {
        File f = archivo( r );
        if ( !f.exists() ) {
          err.println( "pentaho-live diff: not found: " + f );
          return ERROR;
        }
        cmd.add( f.getPath() );
      }
      Git.Salida d = Git.correr( dir, cmd.toArray( new String[ 0 ] ) );
      if ( d.codigo != 0 ) {
        err.println( "pentaho-live diff: git diff failed: " + Git.primeraLinea( d.error.trim() ) );
        return ERROR;
      }
      cambios = cambios( new String( d.salida, StandardCharsets.UTF_8 ) );
    } catch ( IOException e ) {
      err.println( "pentaho-live diff: git is not available (" + e.getMessage() + ")" );
      return ERROR;
    }
    boolean json = formato.equals( "json" );
    boolean md = formato.startsWith( "m" );
    StringBuilder sb = new StringBuilder();
    Salidas.Json j = new Salidas.Json();
    if ( json ) {
      j.abrir().campo( "revision", rev ).campo( "count", cambios.size() ).nombre( "files" ).abrirLista();
    } else if ( md ) {
      sb.append( "## " ).append( Salidas.md( t.f( "files.title", rev ) ) ).append( "\n\n" );
    } else {
      sb.append( t.f( "files.title", rev ) ).append( '\n' );
    }
    if ( cambios.isEmpty() && !json ) {
      sb.append( t.f( "files.none", rev ) ).append( '\n' );
    } else if ( !json ) {
      sb.append( t.f( cambios.size() == 1 ? "files.count.1" : "files.count", cambios.size() ) ).append( '\n' );
    }
    int errores = 0;
    for ( Cambio c : cambios ) {
      File f = new File( repo, c.ruta );
      String estado = c.estado == 'A' ? "added" : c.estado == 'D' ? "removed" : "modified";
      String linea = null;
      ResultadoDiff r = null;
      try {
        if ( c.estado == 'A' || c.estado == 'D' ) {
          byte[] xml = c.estado == 'A' ? Files.readAllBytes( f.toPath() ) : mostrar( repo, rev, c.ruta );
          Documento doc = Documento.leer( xml );
          boolean trans = Documento.TRANSFORMACION.equals( doc.tipo );
          linea = c.estado == 'D' ? t.s( trans ? "files.removed_transformation" : "files.removed_job" )
            : t.f( trans ? "files.added_transformation" : "files.added_job", doc.nodos.size() );
        } else {
          Diff.Fuente antes = new Diff.Fuente( mostrar( repo, rev, c.ruta ), rev + ":" + c.ruta );
          Diff.Fuente despues = new Diff.Fuente( Files.readAllBytes( f.toPath() ), c.ruta );
          r = Diff.comparar( antes, despues, !posiciones );
        }
      } catch ( Exception e ) {
        errores++;
        linea = t.f( "files.unreadable", e.getMessage() );
        estado = "error";
      }
      if ( json ) {
        j.abrir().campo( "file", c.ruta ).campo( "status", estado );
        if ( r != null ) {
          j.nombre( "diff" ).crudo( Salidas.json( r, idioma ) );
        } else {
          j.campo( "message", linea );
        }
        j.cerrar();
      } else if ( md ) {
        sb.append( '\n' );
        if ( r != null ) {
          sb.append( "`" ).append( c.ruta.replace( "`", "" ) ).append( "`\n\n" ).append( Salidas.markdown( r, idioma ) );
        } else {
          sb.append( "### `" ).append( c.ruta.replace( "`", "" ) ).append( "`\n\n" ).append( Salidas.md( linea ) )
            .append( '\n' );
        }
      } else {
        sb.append( '\n' ).append( "== " ).append( c.ruta ).append( '\n' );
        sb.append( r != null ? Salidas.texto( r, idioma ) : "  " + linea + "\n" );
      }
    }
    if ( json ) {
      j.cerrarLista().cerrar();
      out.println( j.toString() );
    } else {
      out.print( sb );
    }
    return errores > 0 ? ERROR : cambios.isEmpty() ? OK : DIFERENTE;
  }

  /** La salida de {@code git diff --name-status -z}: solo los .ktr/.kjb, en orden. */
  static List<Cambio> cambios( String z ) {
    List<Cambio> l = new ArrayList<Cambio>();
    String[] p = z.split( "\u0000" );
    for ( int i = 0; i + 1 < p.length; i += 2 ) {
      String est = p[ i ].trim();
      String ruta = p[ i + 1 ];
      if ( est.isEmpty() || !esKettle( ruta ) ) {
        continue;
      }
      char e = Character.toUpperCase( est.charAt( 0 ) );
      l.add( new Cambio( e == 'A' || e == 'D' ? e : 'M', ruta ) );
    }
    return l;
  }

  /** El contenido de una ruta del repositorio en una revisión ({@code git show REV:ruta}, desde la raíz). */
  private static byte[] mostrar( File repo, String rev, String ruta ) throws IOException, Git.GitError {
    Git.Salida s = Git.correr( repo, "show", rev + ":" + ruta );
    if ( s.codigo != 0 ) {
      throw new Git.GitError( "git show " + rev + ":" + ruta + " failed: " + Git.primeraLinea( s.error.trim() ) );
    }
    return s.salida;
  }

  // ================================================================ textconv

  int textconv( List<String> args ) throws Uso {
    String idioma = valor( args, "--lang", idiomaPorDefecto() );
    boolean posiciones = sacar( args, "--positions" );
    sinOpciones( args );
    if ( args.size() != 1 ) {
      throw new Uso( "one file: pentaho-live textconv file.ktr" );
    }
    File f = archivo( args.get( 0 ) );
    byte[] b;
    try {
      b = Files.readAllBytes( f.toPath() );
    } catch ( IOException e ) {
      err.println( "pentaho-live textconv: " + e.getMessage() );
      return ERROR;
    }
    try {
      out.print( Volcado.texto( Documento.leer( b ), posiciones, idioma ) );
    } catch ( Xmls.XmlInvalido e ) {
      // XML roto (marcas de conflicto, a medio escribir): git muestra el texto tal cual
      out.print( new String( b, StandardCharsets.UTF_8 ) );
    }
    return OK;
  }

  // ================================================================ pre-commit

  /**
   * Para el hook de pre-commit: normaliza los .ktr/.kjb preparados (staged). Si el archivo no tiene otros cambios sin
   * preparar, lo reescribe normalizado y lo vuelve a preparar; si los tiene, no lo toca y el commit se corta con un
   * aviso (normalizar a mano y volver a preparar).
   */
  int preCommit( List<String> args ) throws Uso {
    sinOpciones( args );
    File raiz = cwd == null ? new File( "." ).getAbsoluteFile() : cwd;
    try {
      Git.Salida top = Git.correr( raiz, "rev-parse", "--show-toplevel" );
      if ( top.codigo != 0 ) {
        err.println( "pentaho-live pre-commit: not in a git repository" );
        return ERROR;
      }
      File repo = new File( new String( top.salida, StandardCharsets.UTF_8 ).trim() );
      Git.Salida st = Git.correr( repo, "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z" );
      int problemas = 0;
      for ( String ruta : new String( st.salida, StandardCharsets.UTF_8 ).split( "\u0000" ) ) {
        if ( ruta.isEmpty() || !esKettle( ruta ) ) {
          continue;
        }
        Git.Salida preparado = Git.correr( repo, "show", ":" + ruta );
        if ( preparado.codigo != 0 ) {
          continue;
        }
        String n;
        try {
          n = Normalizador.normalizar( preparado.salida );
        } catch ( Xmls.XmlInvalido e ) {
          err.println( "pentaho-live pre-commit: " + ruta + ": " + e.getMessage() );
          problemas++;
          continue;
        }
        byte[] nb = n.getBytes( StandardCharsets.UTF_8 );
        if ( Arrays.equals( nb, preparado.salida ) ) {
          continue;
        }
        File f = new File( repo, ruta );
        byte[] trabajo = f.isFile() ? Files.readAllBytes( f.toPath() ) : null;
        if ( trabajo == null || !Arrays.equals( trabajo, preparado.salida ) ) {
          err.println( "pentaho-live pre-commit: " + ruta + " is not normalized and has unstaged changes. Run: "
            + "pentaho-live normalize \"" + ruta + "\" and stage it again." );
          problemas++;
          continue;
        }
        Files.write( f.toPath(), nb );
        Git.Salida add = Git.correr( repo, "add", "--", ruta );
        if ( add.codigo != 0 ) {
          err.println( "pentaho-live pre-commit: git add " + ruta + " failed: " + add.error.trim() );
          problemas++;
          continue;
        }
        out.println( "pentaho-live: normalized " + ruta );
      }
      return problemas > 0 ? DIFERENTE : OK;
    } catch ( IOException e ) {
      err.println( "pentaho-live pre-commit: " + e.getMessage() );
      return ERROR;
    }
  }

  // ================================================================ git-setup y ayuda

  void gitSetup() {
    PrintWriter w = new PrintWriter( new OutputStreamWriter( out, StandardCharsets.UTF_8 ), true );
    w.println( "# 1. In .gitattributes (commit it, the whole team gets it):" );
    w.println( "*.ktr diff=kettle" );
    w.println( "*.kjb diff=kettle" );
    w.println();
    w.println( "# 2. Once in each clone (it goes to .git/config):" );
    w.println( "git config diff.kettle.textconv \"pentaho-live textconv\"" );
    w.println( "git config diff.kettle.cachetextconv true" );
    w.println();
    w.println( "# 3. Optional: normalize .ktr/.kjb on every commit (.git/hooks/pre-commit, executable):" );
    w.println( "#!/bin/sh" );
    w.println( "exec pentaho-live pre-commit" );
    w.flush();
  }

  void ayuda() {
    out.println( "Pentaho Live - git and console commands (no Spoon needed):" );
    out.println( "  pentaho-live normalize [--check] [--stdout] <files or folders>   canonical XML of .ktr/.kjb" );
    out.println( "  pentaho-live diff [--format text|markdown|json] [--positions] [--lang en|es|pt] <before> <after>" );
    out.println( "  pentaho-live diff [--rev REV] <file>      the git revision (HEAD by default) against the file" );
    out.println( "  pentaho-live diff [--rev REV] [--format markdown] [<folders>]   every .ktr/.kjb changed since REV" );
    out.println( "  pentaho-live textconv [--positions] <file> semantic text of one file, for git's textconv" );
    out.println( "  pentaho-live pre-commit                    what the optional pre-commit hook runs" );
    out.println( "  pentaho-live git-setup                     the .gitattributes and git config lines" );
    out.println( "Exit codes: 0 ok / identical, 1 differences (diff) or not normalized (--check), 2 error." );
  }

  // ================================================================ argumentos

  private File archivo( String a ) {
    File f = new File( a );
    if ( !f.isAbsolute() && cwd != null ) {
      f = new File( cwd, a );
    }
    return f.getAbsoluteFile();
  }

  static boolean sacar( List<String> args, String opcion ) {
    return args.remove( opcion );
  }

  static String valor( List<String> args, String opcion, String def ) throws Uso {
    for ( int i = 0; i < args.size(); i++ ) {
      String a = args.get( i );
      if ( a.equals( opcion ) ) {
        if ( i + 1 >= args.size() ) {
          throw new Uso( opcion + " needs a value" );
        }
        String v = args.get( i + 1 );
        args.remove( i + 1 );
        args.remove( i );
        return v;
      }
      if ( a.startsWith( opcion + "=" ) ) {
        args.remove( i );
        return a.substring( opcion.length() + 1 );
      }
    }
    return def;
  }

  static void sinOpciones( List<String> args ) throws Uso {
    for ( String a : args ) {
      if ( a.startsWith( "--" ) ) {
        throw new Uso( "unknown option " + a );
      }
    }
  }

  static String idiomaPorDefecto() {
    String v = System.getenv( "PENTAHO_LIVE_LANG" );
    if ( v != null && !v.trim().isEmpty() ) {
      return v.trim();
    }
    return Locale.getDefault().getLanguage();
  }
}
