package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lanza la JVM hija de {@code stress_test} ({@link EstresMain}) y la espera (docs/robustez-diseno.md §5.3). Copia
 * mínima del lanzador del corrector ({@code CorrectorProcesos}, en feat/corrector):
 * <b>0.6.1: unificar con CorrectorProcesos</b> (un solo lanzador). Classpath = el del plugin (su jar y lib/*, o la
 * carpeta de clases en las pruebas) + {@code <PDI>/classes}, {@code lib/*}, {@code libswt/<os>/*};
 * {@code -DKETTLE_PLUGIN_BASE_FOLDERS=<PDI>/plugins}; los {@code --add-opens} del padre; {@code KETTLE_HOME},
 * {@code user.home}, metastore y {@code java.io.tmpdir} propios en el sandbox (también como variables de entorno,
 * quitando las KETTLE_*, JAVA_TOOL_OPTIONS y _JAVA_OPTIONS); directorio de trabajo = el sandbox; salida al log.
 *
 * @dueño R2c
 */
final class EstresProcesos {

  private static final List<String> FLAGS_CON_VALOR = Arrays.asList( "--add-opens", "--add-exports", "--add-reads",
    "--add-modules" );
  private static final List<String> PREFIJOS_COPIADOS = Arrays.asList( "--add-opens=", "--add-exports=",
    "--add-reads=", "--add-modules=", "--illegal-access=", "-Djava.locale.providers=", "-Djava.endorsed.dirs=" );

  private EstresProcesos() {
  }

  /** Corre la hija con el trabajo y espera hasta timeoutMs; devuelve el código de salida o -1 si se la mató. */
  static int correr( File pdi, File sandbox, File trabajo, File log, long timeoutMs ) throws IOException,
    InterruptedException {
    File kettleHome = new File( sandbox, "kettle_home" );
    File home = new File( sandbox, "home" );
    File metastore = new File( sandbox, "metastore" );
    File tmp = new File( sandbox, "tmp" );
    for ( File d : Arrays.asList( new File( kettleHome, ".kettle" ), home, metastore, tmp, log.getParentFile() ) ) {
      if ( !d.isDirectory() && !d.mkdirs() && !d.isDirectory() ) {
        throw new IOException( "cannot create " + d );
      }
    }
    List<String> cmd = new ArrayList<String>();
    cmd.add( java().getAbsolutePath() );
    cmd.add( "-Xmx512m" );
    cmd.addAll( flagsDe( ManagementFactory.getRuntimeMXBean().getInputArguments() ) );
    cmd.add( "-Dfile.encoding=UTF-8" );
    cmd.add( "-Djava.awt.headless=true" );
    cmd.add( "-DKETTLE_PLUGIN_BASE_FOLDERS=" + new File( pdi, "plugins" ).getAbsolutePath() );
    File swt = new File( new File( pdi, "libswt" ), carpetaSwt() );
    cmd.add( "-Djava.library.path=" + swt.getAbsolutePath() );
    cmd.add( "-Djava.io.tmpdir=" + tmp.getAbsolutePath() );
    cmd.add( "-DKETTLE_HOME=" + kettleHome.getAbsolutePath() );
    cmd.add( "-DPENTAHO_METASTORE_FOLDER=" + metastore.getAbsolutePath() );
    cmd.add( "-Duser.home=" + home.getAbsolutePath() );
    cmd.add( "-cp" );
    cmd.add( String.join( File.pathSeparator, classpath( pdi ) ) );
    cmd.add( EstresMain.class.getName() );
    cmd.add( trabajo.getAbsolutePath() );
    ProcessBuilder pb = new ProcessBuilder( cmd );
    pb.directory( sandbox );
    Map<String, String> env = pb.environment();
    for ( Iterator<String> it = env.keySet().iterator(); it.hasNext(); ) {
      String k = it.next().toUpperCase( Locale.ROOT );
      if ( k.startsWith( "KETTLE_" ) || k.startsWith( "PENTAHO_METASTORE" ) || k.equals( "JAVA_TOOL_OPTIONS" )
        || k.equals( "_JAVA_OPTIONS" ) || k.equals( "JDK_JAVA_OPTIONS" ) ) {
        it.remove();
      }
    }
    env.put( "KETTLE_HOME", kettleHome.getAbsolutePath() );
    env.put( "PENTAHO_METASTORE_FOLDER", metastore.getAbsolutePath() );
    pb.redirectErrorStream( true );
    pb.redirectOutput( ProcessBuilder.Redirect.appendTo( log ) );
    Process p = pb.start();
    try {
      p.getOutputStream().close();
    } catch ( IOException ignored ) {
      // ya cerrado
    }
    if ( !p.waitFor( timeoutMs, TimeUnit.MILLISECONDS ) ) {
      matar( p );
      return -1;
    }
    return p.exitValue();
  }

  /** Mata la hija y sus hijos (Java 9+, ProcessHandle por reflexión: el plugin compila para Java 8). */
  static void matar( Process p ) {
    try {
      Class<?> ph = Class.forName( "java.lang.ProcessHandle" );
      Object h = Process.class.getMethod( "toHandle" ).invoke( p );
      java.util.stream.Stream<?> hijos = (java.util.stream.Stream<?>) ph.getMethod( "descendants" ).invoke( h );
      for ( Object x : hijos.toArray() ) {
        try {
          ph.getMethod( "destroyForcibly" ).invoke( x );
        } catch ( Throwable ignored ) {
          // sigue con los demás
        }
      }
    } catch ( Throwable ignored ) {
      // Java 8: solo la hija
    }
    p.destroyForcibly();
    try {
      p.waitFor( 10, TimeUnit.SECONDS );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
    }
  }

  static List<String> flagsDe( List<String> args ) {
    List<String> l = new ArrayList<String>();
    for ( int i = 0; args != null && i < args.size(); i++ ) {
      String a = args.get( i ) == null ? "" : args.get( i ).trim();
      if ( FLAGS_CON_VALOR.contains( a ) ) {
        if ( i + 1 < args.size() ) {
          l.add( a );
          l.add( args.get( ++i ).trim() );
        }
        continue;
      }
      for ( String p : PREFIJOS_COPIADOS ) {
        if ( a.startsWith( p ) ) {
          l.add( a );
          break;
        }
      }
    }
    return l;
  }

  /** El del plugin y después {@code <PDI>/classes}, {@code lib/*} y {@code libswt/<os>/*}. */
  static List<String> classpath( File pdi ) {
    List<String> cp = new ArrayList<String>();
    File f = codigo();
    if ( f == null ) {
      throw new IllegalStateException( "cannot find where the plugin classes are" );
    }
    cp.add( f.getAbsolutePath() );
    File lib = new File( f.getParentFile(), "lib" );
    if ( f.isFile() && lib.isDirectory() ) {
      cp.add( lib.getAbsolutePath() + File.separator + "*" );
    }
    cp.add( new File( pdi, "classes" ).getAbsolutePath() );
    cp.add( new File( pdi, "lib" ).getAbsolutePath() + File.separator + "*" );
    File swt = new File( new File( pdi, "libswt" ), carpetaSwt() );
    if ( swt.isDirectory() ) {
      cp.add( swt.getAbsolutePath() + File.separator + "*" );
    }
    return cp;
  }

  static String carpetaSwt() {
    String os = System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT );
    return os.contains( "win" ) ? "win64" : os.contains( "mac" ) ? "osx64" : "linux";
  }

  /**
   * data-integration: {@code -Dpruebas.pdi} (pruebas), {@code plugins/pentaho-mcp/../..} si esta clase viene del jar
   * instalado, o el user.dir si es un PDI (Spoon arranca con cwd = data-integration). null si no se encuentra.
   */
  static File pdi() {
    String prop = System.getProperty( "pruebas.pdi" );
    if ( prop != null && esPdi( new File( prop ) ) ) {
      return new File( prop );
    }
    File f = codigo();
    if ( f != null && f.isFile() ) {
      File dir = f.getParentFile();
      File cand = dir == null || dir.getParentFile() == null ? null : dir.getParentFile().getParentFile();
      if ( cand != null && esPdi( cand ) ) {
        return cand;
      }
    }
    File cwd = new File( System.getProperty( "user.dir", "." ) ).getAbsoluteFile();
    return esPdi( cwd ) ? cwd : null;
  }

  static boolean esPdi( File d ) {
    File[] l = new File( d, "lib" ).listFiles( ( x, n ) -> n.startsWith( "kettle-engine-" ) && n.endsWith( ".jar" ) );
    return l != null && l.length > 0;
  }

  private static File codigo() {
    try {
      URL u = EstresProcesos.class.getProtectionDomain().getCodeSource().getLocation();
      return new File( u.toURI() );
    } catch ( Exception e ) {
      return null;
    }
  }

  /** El java de esta JVM: javaw en Windows (desde Spoon, java.exe abriría una consola). */
  static File java() {
    File bin = new File( System.getProperty( "java.home" ), "bin" );
    File javaw = new File( bin, "javaw.exe" );
    if ( javaw.isFile() ) {
      return javaw;
    }
    File exe = new File( bin, "java.exe" );
    return exe.isFile() ? exe : new File( bin, "java" );
  }

  /** Memoria física libre en MB (-1 si no se sabe). */
  static long memoriaLibreMb() {
    try {
      Object os = ManagementFactory.getOperatingSystemMXBean();
      Class<?> i = Class.forName( "com.sun.management.OperatingSystemMXBean" );
      if ( !i.isInstance( os ) ) {
        return -1;
      }
      return ( (Number) i.getMethod( "getFreePhysicalMemorySize" ).invoke( os ) ).longValue() / ( 1024 * 1024 );
    } catch ( Throwable e ) {
      return -1;
    }
  }
}
