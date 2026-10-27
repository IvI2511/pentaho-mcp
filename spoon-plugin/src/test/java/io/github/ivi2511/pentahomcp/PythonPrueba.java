package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Python para las pruebas del traductor: el de PENTAHO_LIVE_PYTHON (por ejemplo un venv con pandas y pyspark) o el
 * «python» del PATH. Sin Python, las pruebas que lo necesitan se saltean.
 */
public final class PythonPrueba {

  private static String python;
  private static boolean buscado;

  private PythonPrueba() {
  }

  /** Ejecutable de Python, o null si no hay. */
  public static synchronized String python() {
    if ( !buscado ) {
      buscado = true;
      String env = System.getenv( "PENTAHO_LIVE_PYTHON" );
      for ( String c : env != null && !env.isEmpty() ? new String[] { env } : new String[] { "python", "python3" } ) {
        try {
          Resultado r = correr( null, 60, c, "--version" );
          if ( r.codigo == 0 ) {
            python = c;
            break;
          }
        } catch ( Exception e ) {
          // probar el siguiente
        }
      }
    }
    return python;
  }

  /** ¿Este Python tiene el módulo? */
  public static boolean tiene( String modulo ) {
    if ( python() == null ) {
      return false;
    }
    try {
      return correr( null, 120, python(), "-c", "import " + modulo ).codigo == 0;
    } catch ( Exception e ) {
      return false;
    }
  }

  /** El .py compila (py_compile); sin Python no comprueba nada. */
  public static void compila( File py ) throws Exception {
    if ( python() == null ) {
      return;
    }
    Resultado r = correr( null, 120, python(), "-m", "py_compile", py.getAbsolutePath() );
    Verificador.cierto( r.codigo == 0, py.getName() + " compila: " + r.salida );
  }

  /** Corre un comando y junta stdout + stderr. */
  public static Resultado correr( File dir, int segundos, String... cmd ) throws Exception {
    List<String> l = new ArrayList<>( Arrays.asList( cmd ) );
    ProcessBuilder pb = new ProcessBuilder( l ).redirectErrorStream( true );
    if ( dir != null ) {
      pb.directory( dir );
    }
    pb.environment().put( "PYTHONIOENCODING", "utf-8" );
    File log = File.createTempFile( "py", ".log" );
    pb.redirectOutput( log );
    Process p = pb.start();
    if ( !p.waitFor( segundos, TimeUnit.SECONDS ) ) {
      p.destroyForcibly();
      throw new IllegalStateException( "Python no terminó en " + segundos + " s: " + l );
    }
    Resultado r = new Resultado();
    r.codigo = p.exitValue();
    r.salida = new String( Files.readAllBytes( log.toPath() ), StandardCharsets.UTF_8 );
    log.delete();
    return r;
  }

  public static final class Resultado {
    public int codigo;
    public String salida;
  }
}
