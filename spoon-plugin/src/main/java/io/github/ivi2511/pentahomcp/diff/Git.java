package io.github.ivi2511.pentahomcp.diff;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * El git del sistema (si está), para leer una versión de un archivo de un commit: {@code git show <rev>:./<archivo>}
 * desde la carpeta del archivo. Solo lee: nunca cambia nada del repositorio ni de la configuración. Sin JGit (el
 * bytecode es Java 8).
 */
public final class Git {

  /** Tope de cada comando. */
  static final long TOPE_MS = 20000;
  /** Revisiones aceptadas: nada que git pueda leer como una opción ni rutas raras. */
  static final Pattern REVISION = Pattern.compile( "[A-Za-z0-9_][A-Za-z0-9_./~^@{}:+-]{0,199}" );

  /** Un commit para elegir: hash corto, fecha y asunto. */
  public static final class Commit {
    public final String hash;
    public final String fecha;
    public final String asunto;

    Commit( String hash, String fecha, String asunto ) {
      this.hash = hash;
      this.fecha = fecha;
      this.asunto = asunto;
    }
  }

  /** git no está, el archivo no está en un repositorio o la revisión no tiene ese archivo. */
  public static final class GitError extends Exception {
    private static final long serialVersionUID = 1L;

    public GitError( String msg ) {
      super( msg );
    }
  }

  private Git() {
  }

  /** El ejecutable: "git" del PATH, o el de Git for Windows si no está en el PATH. */
  static String ejecutable() {
    String env = System.getenv( "PENTAHO_LIVE_GIT" );
    if ( env != null && !env.trim().isEmpty() ) {
      return env.trim();
    }
    String os = System.getProperty( "os.name", "" ).toLowerCase( java.util.Locale.ROOT );
    if ( os.contains( "win" ) ) {
      String path = System.getenv( "PATH" );
      if ( path != null ) {
        for ( String d : path.split( File.pathSeparator ) ) {
          if ( new File( d, "git.exe" ).isFile() ) {
            return new File( d, "git.exe" ).getPath();
          }
        }
      }
      for ( String c : Arrays.asList( System.getenv( "ProgramFiles" ) + "\\Git\\cmd\\git.exe",
        System.getenv( "LOCALAPPDATA" ) + "\\Programs\\Git\\cmd\\git.exe" ) ) {
        if ( new File( c ).isFile() ) {
          return c;
        }
      }
    }
    return "git";
  }

  /** true si hay un git que responde. */
  public static boolean disponible() {
    try {
      return correr( new File( "." ).getAbsoluteFile(), "--version" ).codigo == 0;
    } catch ( Exception e ) {
      return false;
    }
  }

  /** El contenido del archivo en esa revisión (HEAD, HEAD~1, main, un hash...). */
  public static byte[] mostrar( File archivo, String revision ) throws GitError {
    String rev = revision == null ? "HEAD" : revision.trim();
    if ( !REVISION.matcher( rev ).matches() ) {
      throw new GitError( "Not a valid git revision: " + rev );
    }
    File f = archivo.getAbsoluteFile();
    File dir = f.getParentFile();
    Salida s;
    try {
      s = correr( dir, "show", rev + ":./" + f.getName() );
    } catch ( IOException e ) {
      throw new GitError( "git is not available (" + e.getMessage() + "). Install Git or put it in the PATH." );
    }
    if ( s.codigo != 0 ) {
      String err = s.error.trim();
      if ( err.contains( "not a git repository" ) ) {
        throw new GitError( f.getName() + " is not in a git repository (" + dir + ")" );
      }
      throw new GitError( "git show " + rev + " failed for " + f.getName() + ": " + ( err.isEmpty() ? "exit "
        + s.codigo : primeraLinea( err ) ) );
    }
    return s.salida;
  }

  /** Los últimos commits que tocaron el archivo (más nuevo primero), hasta n. Vacío si no hay git o repositorio. */
  public static List<Commit> historial( File archivo, int n ) {
    List<Commit> out = new ArrayList<Commit>();
    File f = archivo.getAbsoluteFile();
    try {
      Salida s = correr( f.getParentFile(), "log", "-n", String.valueOf( n ), "--date=short",
        "--format=%h%x09%ad%x09%s", "--", f.getName() );
      if ( s.codigo != 0 ) {
        return out;
      }
      for ( String l : new String( s.salida, StandardCharsets.UTF_8 ).split( "\n" ) ) {
        String[] p = l.split( "\t", 3 );
        if ( p.length == 3 ) {
          out.add( new Commit( p[ 0 ].trim(), p[ 1 ].trim(), p[ 2 ].trim() ) );
        }
      }
    } catch ( Exception e ) {
      // sin git
    }
    return out;
  }

  static String primeraLinea( String s ) {
    int i = s.indexOf( '\n' );
    return i < 0 ? s : s.substring( 0, i ).trim();
  }

  static final class Salida {
    int codigo;
    byte[] salida;
    String error;
  }

  static Salida correr( File dir, String... args ) throws IOException {
    List<String> cmd = new ArrayList<String>();
    cmd.add( ejecutable() );
    cmd.add( "-c" );
    cmd.add( "core.quotepath=off" );
    cmd.addAll( Arrays.asList( args ) );
    ProcessBuilder pb = new ProcessBuilder( cmd );
    pb.directory( dir );
    pb.environment().put( "GIT_TERMINAL_PROMPT", "0" );
    pb.environment().put( "GIT_OPTIONAL_LOCKS", "0" );
    final Process p = pb.start();
    p.getOutputStream().close();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    Thread t1 = bombear( p.getInputStream(), out );
    Thread t2 = bombear( p.getErrorStream(), err );
    try {
      if ( !p.waitFor( TOPE_MS, TimeUnit.MILLISECONDS ) ) {
        p.destroyForcibly();
        throw new IOException( "git did not answer in " + TOPE_MS / 1000 + " s" );
      }
      t1.join( 2000 );
      t2.join( 2000 );
    } catch ( InterruptedException e ) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException( "interrupted" );
    }
    Salida s = new Salida();
    s.codigo = p.exitValue();
    s.salida = out.toByteArray();
    s.error = new String( err.toByteArray(), StandardCharsets.UTF_8 );
    return s;
  }

  private static Thread bombear( final InputStream in, final ByteArrayOutputStream out ) {
    Thread t = new Thread( () -> {
      byte[] b = new byte[ 8192 ];
      int n;
      try {
        while ( ( n = in.read( b ) ) > 0 ) {
          out.write( b, 0, n );
        }
      } catch ( IOException ignorada ) {
        // proceso terminado
      }
    }, "pentaho-live-git" );
    t.setDaemon( true );
    t.start();
    return t;
  }
}
