package io.github.ivi2511.pentahomcp;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.laf.BasePropertyHandler;
import org.pentaho.di.laf.LAFFactory;
import org.pentaho.di.laf.PropertyHandler;

/**
 * Le avisa al lanzador de Pentaho Live (IDCLauncher.exe, convencion 03) que Spoon ya esta en pantalla,
 * para que cierre su splash.
 *
 * El lanzador corre "pentaho-live.cmd --launcher" con la variable IDC_LAUNCHER_READY_FILE (la ruta de un
 * archivo vacio en %TEMP%\IDC\Launcher, que todavia no existe) y se cierra cuando el archivo aparece.
 * Spoon la hereda: pentaho-live.cmd, cmd.exe y Spoon.bat no la tocan. Sin la variable (Spoon abierto a
 * mano, o por un agente con pentaho-live) no se escribe nada.
 *
 * Con el lanzador, pentaho-live.cmd agrega -Dorg.pentaho.di.laf.alt=.../laf-pentaho-live.properties, que
 * cambia el splash_image de PDI por un PNG de 1x1: el splash de PDI no se ve y queda solo el del lanzador.
 * Eso se deshace aca, con o sin variable: Ayuda > Acerca de abre new Splash(display, shell) con el mismo
 * splash_image y solo se cierra con un clic sobre la imagen; con 1x1 seria un modal invisible que traba
 * Spoon. El splash de arranque ya cargo su imagen, asi que no cambia.
 *
 * Listo = aparece una Shell visible que no estaba al llamar a programar() (la ventana principal, o un
 * dialogo de arranque como el login de repositorio). A los 15 s se escribe igual (un MessageBox nativo no
 * es una Shell, pero el timer de SWT corre dentro de su bucle modal), y si la JVM termina antes la escribe
 * el gancho de salida.
 *
 * Nada de esto puede trabar ni romper el arranque de Spoon: todo va en catch (Throwable) y al log.
 */
final class SenalLanzador implements Runnable {

  /** La pone IDCLauncher.exe (ready=signal) en el entorno del proceso que arranca. */
  static final String VARIABLE = "IDC_LAUNCHER_READY_FILE";

  /** System property de PDI con un .properties de look and feel que pisa a ui/laf.properties. */
  static final String PROPIEDAD_LAF = "org.pentaho.di.laf.alt";

  /** El override propio (plugins/pentaho-mcp/ui/), con el splash de 1x1. */
  static final String LAF_PROPIO = "laf-pentaho-live.properties";

  /** El look and feel de PDI, relativo a data-integration (Spoon.bat hace cd ahi) o en kettle-ui-swt. */
  static final String LAF_BASE = "ui/laf.properties";

  static final String SPLASH_VACIO = "splash-vacio.png";

  /** Nombre que arma el lanzador: ready-{pid}-{Guid "N"}.signal. */
  private static final Pattern NOMBRE = Pattern.compile( "ready-[0-9]+-[0-9a-fA-F]{32}\\.signal" );

  static final long CADA_MS = 250;

  /** Sin ventana nueva a los 15 s, se escribe igual. */
  static final long PLAZO_MS = 15000;

  /** Lo maximo que espera el lanzador (fallback_ms del ini es menor): despues no se deja basura en %TEMP%. */
  static final long SALIDA_MAX_MS = 300000;

  private static boolean programado;

  private final Path ruta;
  private final long inicio;
  private volatile boolean escrito;

  private Display display;
  private final List<Shell> previas = new ArrayList<Shell>();

  SenalLanzador( Path ruta, long inicio ) {
    this.ruta = ruta;
    this.inicio = inicio;
  }

  /**
   * Primera linea de McpLifecycleListener.onStart, en el hilo de UI y antes de que se abra la ventana
   * principal. Una sola vez por JVM. Nunca lanza.
   */
  static void programar() {
    final long inicio = System.nanoTime();
    try {
      synchronized ( SenalLanzador.class ) {
        if ( programado ) {
          return;
        }
        programado = true;
      }
    } catch ( Throwable t ) {
      return;
    }
    try {
      restaurarLookAndFeel();
    } catch ( Throwable t ) {
      error( "Pentaho MCP: no se pudo restaurar el splash de PDI", t );
    }
    try {
      String valor = System.getenv( VARIABLE );
      if ( valor == null || valor.isEmpty() ) {
        return;
      }
      Path ruta = rutaValida( valor );
      if ( ruta == null ) {
        basico( "Pentaho MCP: " + VARIABLE + " no es un archivo de senal del lanzador, se ignora: " + valor );
        return;
      }
      new SenalLanzador( ruta, inicio ).arrancar();
    } catch ( Throwable t ) {
      error( "Pentaho MCP: no se pudo programar la senal del lanzador", t );
    }
  }

  /**
   * Si Spoon arranco con el splash oculto (laf-pentaho-live.properties), vuelve a poner ui/laf.properties
   * delante: OverlayPropertyHandler.loadProps hace addFirst, y BasePropertyHandler usa el mismo handler de
   * LAFFactory. Devuelve true si hizo falta y quedo bien.
   */
  static boolean restaurarLookAndFeel() {
    String alt = System.getProperty( PROPIEDAD_LAF );
    if ( alt == null || !alt.trim().endsWith( LAF_PROPIO ) ) {
      return false;
    }
    PropertyHandler handler = LAFFactory.getHandler( PropertyHandler.class );
    if ( handler == null || !handler.loadProps( LAF_BASE ) ) {
      error( "Pentaho MCP: no se pudo volver a cargar " + LAF_BASE + " (Ayuda > Acerca de no va a verse)", null );
      return false;
    }
    String splash = BasePropertyHandler.getProperty( "splash_image" );
    if ( splash == null || splash.endsWith( SPLASH_VACIO ) ) {
      error( "Pentaho MCP: el splash de PDI sigue oculto despues de cargar " + LAF_BASE + ": " + splash, null );
      return false;
    }
    detallado( "Pentaho MCP: splash de PDI restaurado (" + splash + ")" );
    return true;
  }

  /**
   * La ruta de la variable si tiene la forma que arma el lanzador: absoluta, sin "." ni "..",
   * ...\IDC\Launcher\ready-{pid}-{32 hex}.signal (carpetas sin distinguir mayusculas). No se compara con
   * %TEMP%: el lanzador usa GetTempPath (TMP antes que TEMP, a veces nombres 8.3). Alcanza con esto porque
   * solo se crea un archivo vacio, y solo si no existe. Si no, null.
   */
  static Path rutaValida( String valor ) {
    if ( valor == null || valor.isEmpty() ) {
      return null;
    }
    Path p;
    try {
      p = Paths.get( valor );
    } catch ( InvalidPathException e ) {
      return null;
    }
    if ( !p.isAbsolute() || !p.equals( p.normalize() ) ) {
      return null;
    }
    Path nombre = p.getFileName();
    Path padre = p.getParent();
    Path abuelo = padre == null ? null : padre.getParent();
    if ( nombre == null || abuelo == null || padre.getFileName() == null || abuelo.getFileName() == null ) {
      return null;
    }
    if ( !NOMBRE.matcher( nombre.toString() ).matches()
      || !"Launcher".equalsIgnoreCase( padre.getFileName().toString() )
      || !"IDC".equalsIgnoreCase( abuelo.getFileName().toString() ) ) {
      return null;
    }
    return p;
  }

  private void arrancar() {
    detallado( "Pentaho MCP: senal del lanzador programada (" + ruta.getFileName() + ")" );
    try {
      Runtime.getRuntime().addShutdownHook( new Thread( () -> {
        if ( ms() <= SALIDA_MAX_MS ) {
          escribir( "salida" );
        }
      }, "PentahoMCP-senal-lanzador" ) );
    } catch ( Throwable t ) {
      error( "Pentaho MCP: no se pudo registrar el gancho de salida de la senal del lanzador", t );
    }
    Display actual = Display.getCurrent();
    if ( actual != null ) {
      empezar( actual );
      return;
    }
    final Display ui = UiThread.display();
    if ( ui == null ) {
      escribir( "sin pantalla" );
      return;
    }
    ui.asyncExec( () -> empezar( ui ) );
  }

  /** En el hilo de UI: guarda las Shell ya visibles (el splash de PDI) y empieza a sondear. */
  private void empezar( Display d ) {
    try {
      display = d;
      for ( Shell s : d.getShells() ) {
        if ( !s.isDisposed() && s.isVisible() ) {
          previas.add( s );
        }
      }
      // timerExec y no asyncExec: corre despues de pintar
      d.timerExec( (int) CADA_MS, this );
    } catch ( Throwable t ) {
      error( "Pentaho MCP: no se pudo sondear las ventanas para el lanzador", t );
      escribir( "error" );
    }
  }

  /** Un tic del sondeo, en el hilo de UI. */
  @Override
  public void run() {
    try {
      if ( escrito || display.isDisposed() ) {
        return; // si Spoon se cerro antes, escribe el gancho de salida
      }
      for ( Shell s : display.getShells() ) {
        if ( !s.isDisposed() && s.isVisible() && !previas.contains( s ) ) {
          escribir( "ventana" );
          return;
        }
      }
      if ( ms() >= PLAZO_MS ) {
        escribir( "plazo" );
        return;
      }
      display.timerExec( (int) CADA_MS, this );
    } catch ( Throwable t ) {
      error( "Pentaho MCP: fallo el sondeo de ventanas para el lanzador", t );
      escribir( "error" );
    }
  }

  /**
   * Crea el archivo vacio, una sola vez. motivo: ventana, plazo, salida, sin pantalla o error. Si el lanzador
   * ya cerro (Esc, su boton de la barra de tareas o fallback_ms antes de que aparezca Spoon), no: borra la
   * senal al cerrar, y una escrita despues quedaria en %TEMP% para siempre.
   */
  synchronized void escribir( String motivo ) {
    if ( escrito ) {
      return;
    }
    escrito = true;
    try {
      if ( !lanzadorVivo( pidLanzador( ruta ) ) ) {
        detallado( "Pentaho MCP: el lanzador ya cerro, no se escribe su senal (" + motivo + ", " + ms() + " ms)" );
        return;
      }
      Files.createFile( ruta );
      detallado( "Pentaho MCP: senal del lanzador escrita (" + motivo + ", " + ms() + " ms)" );
    } catch ( FileAlreadyExistsException e ) {
      detallado( "Pentaho MCP: la senal del lanzador ya estaba (" + motivo + ", " + ms() + " ms)" );
    } catch ( Throwable t ) {
      error( "Pentaho MCP: no se pudo escribir la senal del lanzador " + ruta, t );
    }
  }

  boolean escrito() {
    return escrito;
  }

  /** El pid del lanzador, del nombre que arma (ready-{pid}-{guid}.signal), o -1. */
  static long pidLanzador( Path ruta ) {
    try {
      String nombre = ruta.getFileName().toString();
      int desde = nombre.indexOf( '-' ) + 1;
      return Long.parseLong( nombre.substring( desde, nombre.indexOf( '-', desde ) ) );
    } catch ( RuntimeException e ) {
      return -1;
    }
  }

  /**
   * False solo si se sabe que ese proceso ya no corre. ProcessHandle es de Java 9 y el plugin compila para
   * Java 8: va por reflexion, y sin el (o si algo falla) se toma como vivo, que es lo de antes.
   */
  static boolean lanzadorVivo( long pid ) {
    if ( pid <= 0 ) {
      return true;
    }
    try {
      Class<?> handle = Class.forName( "java.lang.ProcessHandle" );
      Optional<?> proceso = (Optional<?>) handle.getMethod( "of", long.class ).invoke( null, pid );
      return proceso.isPresent() && Boolean.TRUE.equals( handle.getMethod( "isAlive" ).invoke( proceso.get() ) );
    } catch ( Throwable t ) {
      return true;
    }
  }

  private long ms() {
    return TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - inicio );
  }

  private static void detallado( String mensaje ) {
    try {
      McpLifecycleListener.LOG.logDetailed( mensaje );
    } catch ( Throwable ignored ) {
      // el log no puede romper el arranque
    }
  }

  private static void basico( String mensaje ) {
    try {
      McpLifecycleListener.LOG.logBasic( mensaje );
    } catch ( Throwable ignored ) {
      // el log no puede romper el arranque
    }
  }

  private static void error( String mensaje, Throwable t ) {
    try {
      if ( t == null ) {
        McpLifecycleListener.LOG.logError( mensaje );
      } else {
        McpLifecycleListener.LOG.logError( mensaje, t );
      }
    } catch ( Throwable ignored ) {
      // el log no puede romper el arranque
    }
  }
}
