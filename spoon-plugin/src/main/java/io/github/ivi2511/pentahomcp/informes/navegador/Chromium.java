package io.github.ivi2511.pentahomcp.informes.navegador;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ivi2511.pentahomcp.informes.Progreso;

/**
 * Edge o Chrome headless (diseno §3.2): ubicar el ejecutable y correrlo con timeout, perfil temporal
 * corto y unico por corrida y semaforo de 2.
 * <p>
 * Cada corrida usa {@code --user-data-dir=%TEMP%\plive-<8 caracteres>} (corto: con rutas largas hay
 * errores de MAX_PATH) que se borra al terminar. La salida estandar y la de errores van a archivos (el
 * pipe nunca se llena). Si se vence el timeout o se cancela: {@code taskkill /PID <pid> /T /F} (si el
 * runtime es Java 9+, por reflexion), {@code destroyForcibly()} y un barrido por linea de comandos que
 * mata cualquier msedge/chrome que siga vivo con ese perfil (funciona tambien con Java 8).
 * <p>
 * Sin SWT ni Kettle: corre en los workers del pool de informes.
 *
 * @dueño WP8
 */
public final class Chromium {

  /** Timeout por defecto de una corrida (diseno §1.4). */
  public static final long TIMEOUT_MS = 60000L;

  /**
   * System property: ruta a msedge.exe/chrome.exe que gana sobre la busqueda, o "none" para hacer como
   * si no hubiera navegador (pruebas y maquinas donde Edge headless molesta).
   */
  public static final String PROPIEDAD = "pentaho.live.navegador";

  /** Prefijo de los perfiles temporales en %TEMP%. */
  public static final String PREFIJO_PERFIL = "plive-";

  private static final Semaphore SEMAFORO = new Semaphore( 2, true );
  private static final AtomicInteger ACTIVOS = new AtomicInteger();
  private static final AtomicInteger MAXIMO = new AtomicInteger();
  private static final Set<String> PERFILES = Collections.newSetFromMap( new ConcurrentHashMap<String, Boolean>() );
  private static final Set<String> CARPETAS = Collections.newSetFromMap( new ConcurrentHashMap<String, Boolean>() );
  /** Navegadores corriendo ahora, con su perfil: los mata el gancho de cierre de la JVM si Spoon se cierra. */
  private static final Map<Process, String> VIVOS = new ConcurrentHashMap<Process, String>();
  private static final AtomicBoolean GANCHO = new AtomicBoolean();
  private static final Object BUSQUEDA = new Object();
  /**
   * En Windows, CreateProcess hereda TODOS los handles heredables del padre: si dos corridas arrancan a
   * la vez, un navegador se lleva los archivos de stdout/stderr del otro y esa carpeta no se puede borrar
   * hasta que termine. Se arranca de a uno (Java cierra sus copias apenas vuelve start()).
   */
  private static final Object ARRANQUE = new Object();
  private static volatile boolean buscado;
  private static volatile File encontrado;
  private static volatile boolean limpiado;

  private static final List<String> BASE = Collections.unmodifiableList( Arrays.asList(
    "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check", "--disable-extensions",
    "--disable-background-networking", "--disable-component-update", "--disable-sync", "--no-pings",
    "--disable-breakpad", "--mute-audio", "--hide-scrollbars" ) );

  private Chromium() {
  }

  /** Lo que devolvio una corrida terminada (con o sin error del navegador). */
  public static final class Corrida {
    /** Codigo de salida del proceso. */
    public int codigo;
    /** Milisegundos de reloj, sin contar la espera del semaforo. */
    public long ms;
    /** El --user-data-dir que se uso (ya borrado si se pudo). */
    public String perfil;
    /** La salida estandar completa (por ejemplo la de --dump-dom), si se pidio guardarla; si no, null. */
    public byte[] salida;
    /** El final de la salida de errores (hasta 4 KB), para diagnosticar. */
    public String errores = "";
  }

  /** Se vencio el timeout: el proceso y sus hijos ya se mataron. */
  public static final class Agotado extends TimeoutException {
    private static final long serialVersionUID = 1L;
    /** El --user-data-dir de la corrida que se mato. */
    public final String perfil;

    Agotado( String msg, String perfil ) {
      super( msg );
      this.perfil = perfil;
    }
  }

  // ------------------------------------------------------------------------------------- ubicar

  /** msedge.exe o chrome.exe (propiedad, registro, Program Files), o null si no hay. Con cache. */
  public static File ubicar() {
    return ubicar( null );
  }

  /**
   * Igual que {@link #ubicar()}, pero primero prueba {@code preferido} (Tema.navegador): si es un
   * archivo que existe gana sobre la busqueda; si no existe se ignora.
   */
  public static File ubicar( String preferido ) {
    String prop = System.getProperty( PROPIEDAD );
    if ( prop != null && !prop.trim().isEmpty() ) {
      String p = prop.trim();
      if ( "none".equalsIgnoreCase( p ) || "ninguno".equalsIgnoreCase( p ) ) {
        return null;
      }
      File f = new File( p );
      if ( f.isFile() ) {
        return f;
      }
    }
    if ( preferido != null && !preferido.trim().isEmpty() ) {
      File f = new File( preferido.trim() );
      if ( f.isFile() ) {
        return f;
      }
    }
    if ( !buscado ) {
      synchronized ( BUSQUEDA ) {
        if ( !buscado ) {
          encontrado = buscar();
          buscado = true;
        }
      }
    }
    return encontrado;
  }

  /** Olvida el resultado guardado de la busqueda (por ejemplo, si el usuario instalo un navegador). */
  public static void olvidar() {
    synchronized ( BUSQUEDA ) {
      buscado = false;
      encontrado = null;
    }
  }

  private static File buscar() {
    if ( !esWindows() ) {
      for ( String c : new String[] { "/usr/bin/microsoft-edge", "/usr/bin/google-chrome", "/usr/bin/chromium",
        "/usr/bin/chromium-browser", "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" } ) {
        File f = new File( c );
        if ( f.isFile() ) {
          return f;
        }
      }
      return null;
    }
    String[][] navegadores = { { "msedge.exe", "Microsoft\\Edge\\Application" },
      { "chrome.exe", "Google\\Chrome\\Application" } };
    for ( String[] n : navegadores ) {
      for ( String raiz : new String[] { "HKLM", "HKCU" } ) {
        File f = registro( raiz + "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + n[ 0 ] );
        if ( f != null ) {
          return f;
        }
      }
      for ( String var : new String[] { "ProgramFiles(x86)", "ProgramFiles", "ProgramW6432", "LOCALAPPDATA" } ) {
        String base = System.getenv( var );
        if ( base != null && !base.isEmpty() ) {
          File f = new File( base, n[ 1 ] + "\\" + n[ 0 ] );
          if ( f.isFile() ) {
            return f;
          }
        }
      }
    }
    return null;
  }

  /** {@code reg query <clave> /ve}: el valor por defecto si apunta a un archivo que existe. */
  private static File registro( String clave ) {
    File tmp = null;
    try {
      tmp = File.createTempFile( "plive-reg", ".txt" );
      ProcessBuilder pb = new ProcessBuilder( "reg", "query", clave, "/ve" );
      pb.redirectErrorStream( true );
      pb.redirectOutput( tmp );
      Process pr = arrancar( pb );
      pr.getOutputStream().close();
      if ( !pr.waitFor( 10, TimeUnit.SECONDS ) ) {
        pr.destroyForcibly();
        return null;
      }
      if ( pr.exitValue() != 0 ) {
        return null;
      }
      String texto = new String( leer( tmp ), Charset.defaultCharset() );
      for ( String linea : texto.split( "\\r?\\n" ) ) {
        int i = linea.indexOf( "REG_SZ" );
        int largo = "REG_SZ".length();
        if ( i < 0 ) {
          i = linea.indexOf( "REG_EXPAND_SZ" );
          largo = "REG_EXPAND_SZ".length();
        }
        if ( i >= 0 ) {
          String ruta = linea.substring( i + largo ).trim();
          if ( ruta.startsWith( "\"" ) && ruta.endsWith( "\"" ) && ruta.length() > 1 ) {
            ruta = ruta.substring( 1, ruta.length() - 1 );
          }
          File f = new File( expandir( ruta ) );
          return f.isFile() ? f : null;
        }
      }
    } catch ( IOException e ) {
      return null;
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      if ( tmp != null ) {
        tmp.delete();
      }
    }
    return null;
  }

  private static String expandir( String s ) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while ( i < s.length() ) {
      int a = s.indexOf( '%', i );
      int b = a < 0 ? -1 : s.indexOf( '%', a + 1 );
      if ( a < 0 || b < 0 ) {
        sb.append( s.substring( i ) );
        break;
      }
      String v = System.getenv( s.substring( a + 1, b ) );
      sb.append( s, i, a ).append( v == null ? s.substring( a, b + 1 ) : v );
      i = b + 1;
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------------------------- correr

  /**
   * Corre el navegador headless con esos argumentos (sin el ejecutable). Se agregan solos
   * {@code --headless=new}, {@code --user-data-dir} unico y los flags de §3.2. La salida va a un archivo.
   *
   * @return el codigo de salida; si se vence el timeout mata el proceso y lanza {@link Agotado}.
   */
  public static int correr( List<String> argumentos, long timeoutMs ) throws Exception {
    return ejecutar( null, argumentos, timeoutMs, false, null ).codigo;
  }

  /**
   * Corre el navegador y devuelve el detalle.
   *
   * @param navegador      el ejecutable; null = {@link #ubicar()}.
   * @param argumentos     los de la tarea (--print-to-pdf=..., --dump-dom, la URL...).
   * @param timeoutMs      tope; &lt;= 0 = {@link #TIMEOUT_MS}.
   * @param guardarSalida  true = la salida estandar queda en {@link Corrida#salida}; false = se descarta.
   * @param p              cancelacion; null = nunca se cancela. Si cancela, mata todo y lanza
   *                       CancellationException.
   * @throws IllegalStateException si no hay navegador.
   * @throws Agotado               si se vencio el timeout (ya sin procesos vivos).
   */
  public static Corrida ejecutar( File navegador, List<String> argumentos, long timeoutMs, boolean guardarSalida,
                                  Progreso p ) throws Exception {
    File exe = navegador != null ? navegador : ubicar();
    if ( exe == null ) {
      throw new IllegalStateException( "No hay Edge ni Chrome" );
    }
    long tope = timeoutMs > 0 ? timeoutMs : TIMEOUT_MS;
    limpiarViejos();
    adquirir( p );
    int activos = ACTIVOS.incrementAndGet();
    MAXIMO.accumulateAndGet( activos, Math::max );
    File perfil = new File( temporal(), PREFIJO_PERFIL + nuevoId() );
    String rutaPerfil = perfil.getAbsolutePath();
    File io = null;
    Process pr = null;
    boolean termino = false;
    Corrida c = new Corrida();
    c.perfil = rutaPerfil;
    try {
      // stdout y stderr en una carpeta propia: los hijos del navegador heredan esos handles y a veces los
      // sueltan tarde, asi que se borra la carpeta entera con reintentos
      io = carpetaTrabajo();
      File errores = new File( io, "err.txt" );
      File salida = new File( io, "out.txt" );
      PERFILES.add( rutaPerfil );
      List<String> cmd = new ArrayList<>();
      cmd.add( exe.getAbsolutePath() );
      cmd.addAll( BASE );
      cmd.add( "--user-data-dir=" + rutaPerfil );
      if ( argumentos != null ) {
        cmd.addAll( argumentos );
      }
      ProcessBuilder pb = new ProcessBuilder( cmd );
      pb.directory( temporal() );
      pb.redirectOutput( ProcessBuilder.Redirect.to( salida ) );
      pb.redirectError( ProcessBuilder.Redirect.to( errores ) );
      long t0 = System.currentTimeMillis();
      pr = arrancar( pb );
      VIVOS.put( pr, rutaPerfil );
      ganchoDeCierre();
      try {
        pr.getOutputStream().close();
      } catch ( IOException e ) {
        // sin stdin: da igual
      }
      long limite = t0 + tope;
      while ( true ) {
        long resta = limite - System.currentTimeMillis();
        if ( resta <= 0 ) {
          break;
        }
        if ( pr.waitFor( Math.min( resta, 100L ), TimeUnit.MILLISECONDS ) ) {
          termino = true;
          break;
        }
        if ( p != null && p.cancelado() ) {
          matar( pr, rutaPerfil, exe );
          throw new CancellationException( "Cancelado" );
        }
      }
      if ( !termino ) {
        matar( pr, rutaPerfil, exe );
        throw new Agotado( "El navegador no termino en " + tope + " ms", rutaPerfil );
      }
      c.codigo = pr.exitValue();
      c.ms = System.currentTimeMillis() - t0;
      c.errores = cola( errores, 4096 );
      if ( guardarSalida && salida.isFile() ) {
        c.salida = leer( salida );
      }
      return c;
    } catch ( InterruptedException e ) {
      if ( pr != null ) {
        matar( pr, rutaPerfil, exe );
      }
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      if ( pr != null ) {
        VIVOS.remove( pr );
      }
      ACTIVOS.decrementAndGet();
      SEMAFORO.release();
      if ( !borrar( perfil ) && esWindows() ) {
        // algo sigue usando el perfil: un hijo vivo. Se lo mata y se reintenta.
        try {
          procesos( rutaPerfil, exe, true );
        } catch ( IOException e ) {
          // queda para limpiarViejos
        }
        borrar( perfil );
      }
      if ( io != null && !borrar( io ) ) {
        alSalir( io );
      }
    }
  }

  /**
   * Una vez por JVM: al cerrarse Spoon (o la JVM por cualquier motivo) mata los navegadores que sigan
   * corriendo, con sus hijos, y borra sus perfiles (diseno §8.3, punto 11: no queda ningun msedge colgado).
   * Corre en el hilo del gancho: rapido y sin tirar nada.
   */
  private static void ganchoDeCierre() {
    if ( !GANCHO.compareAndSet( false, true ) ) {
      return;
    }
    try {
      Thread h = new Thread( Chromium::cerrarTodo, "pentaho-live-chromium-cierre" );
      Runtime.getRuntime().addShutdownHook( h );
    } catch ( IllegalStateException | SecurityException e ) {
      // la JVM ya se esta cerrando o no se permite: queda el barrido de limpiarViejos
    }
  }

  /** Mata todos los navegadores vivos de esta JVM y borra sus perfiles. Lo usa el gancho de cierre. */
  static int cerrarTodo() {
    int n = 0;
    for ( Map.Entry<Process, String> e : new ArrayList<>( VIVOS.entrySet() ) ) {
      Process pr = e.getKey();
      try {
        if ( pr.isAlive() ) {
          Long pid = pid( pr );
          if ( pid != null && esWindows() ) {
            Process tk = new ProcessBuilder( "taskkill", "/PID", String.valueOf( pid ), "/T", "/F" )
              .redirectErrorStream( true ).redirectOutput( descarte() ).start();
            tk.waitFor( 3, TimeUnit.SECONDS );
          }
          pr.destroyForcibly();
          pr.waitFor( 2, TimeUnit.SECONDS );
          n++;
        }
      } catch ( Throwable ignorada ) {
        // cerrando la JVM: lo que se pueda
      }
      VIVOS.remove( pr );
      try {
        borrarUnaVez( new File( e.getValue() ) );
      } catch ( Throwable ignorada ) {
        // queda para limpiarViejos
      }
    }
    return n;
  }

  /** Cuantos navegadores de esta JVM estan corriendo ahora. */
  public static int vivos() {
    return VIVOS.size();
  }

  private static void adquirir( Progreso p ) throws InterruptedException {
    while ( !SEMAFORO.tryAcquire( 200, TimeUnit.MILLISECONDS ) ) {
      if ( p != null && p.cancelado() ) {
        throw new CancellationException( "Cancelado" );
      }
    }
  }

  /**
   * Mata el proceso y todos sus hijos: taskkill /T con el pid (Java 9+), destroyForcibly, y un barrido
   * de los msedge/chrome que sigan con ese perfil en la linea de comandos.
   */
  private static void matar( Process pr, String perfil, File exe ) {
    Long pid = pid( pr );
    if ( pid != null && esWindows() ) {
      try {
        Process tk = arrancar( new ProcessBuilder( "taskkill", "/PID", String.valueOf( pid ), "/T", "/F" )
          .redirectErrorStream( true ).redirectOutput( descarte() ) );
        tk.getOutputStream().close();
        if ( !tk.waitFor( 10, TimeUnit.SECONDS ) ) {
          tk.destroyForcibly();
        }
      } catch ( IOException e ) {
        // sigue con destroyForcibly y el barrido
      } catch ( InterruptedException e ) {
        Thread.currentThread().interrupt();
      }
    }
    pr.destroyForcibly();
    try {
      pr.waitFor( 5, TimeUnit.SECONDS );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
    }
    if ( esWindows() ) {
      // hasta 3 vueltas: un hijo puede nacer mientras se mata al padre
      for ( int i = 0; i < 3; i++ ) {
        try {
          if ( procesos( perfil, exe, true ).isEmpty() ) {
            break;
          }
        } catch ( IOException e ) {
          break;
        }
      }
    }
  }

  /** Process.pid() existe desde Java 9: por reflexion, para compilar con --release 8. */
  private static Long pid( Process pr ) {
    try {
      Method m = Process.class.getMethod( "pid" );
      Object v = m.invoke( pr );
      return v instanceof Long ? (Long) v : null;
    } catch ( ReflectiveOperationException | RuntimeException e ) {
      return null;
    }
  }

  /**
   * Los PID de los msedge.exe/chrome.exe (o el ejecutable dado) vivos cuya linea de comandos contiene
   * {@code fragmento} (por ejemplo la ruta de un perfil). Con {@code matarlos} los mata. Usa
   * PowerShell + CIM (wmic ya no viene en Windows 11). Solo Windows; en otro sistema, lista vacia.
   */
  public static List<Long> procesos( String fragmento, File exe, boolean matarlos ) throws IOException {
    return new ArrayList<>( lineasDeComando( fragmento, exe, matarlos ).keySet() );
  }

  /**
   * Como {@link #procesos}, pero con la linea de comandos de cada PID (para filtrar por varios perfiles
   * con una sola llamada a PowerShell).
   */
  public static Map<Long, String> lineasDeComando( String fragmento, File exe, boolean matarlos )
    throws IOException {
    Map<Long, String> pids = new LinkedHashMap<>();
    if ( !esWindows() || fragmento == null || fragmento.isEmpty() ) {
      return pids;
    }
    Set<String> nombres = new LinkedHashSet<>( Arrays.asList( "msedge.exe", "chrome.exe" ) );
    if ( exe != null ) {
      nombres.add( exe.getName().toLowerCase( Locale.ROOT ) );
    }
    StringBuilder filtro = new StringBuilder();
    for ( String n : nombres ) {
      filtro.append( filtro.length() == 0 ? "" : " OR " ).append( "Name='" ).append( n.replace( "'", "" ) ).append(
        "'" );
    }
    // El fragmento va en base64 dentro del comando (que a su vez va en -EncodedCommand): la linea de
    // comandos de PowerShell nunca lo contiene en claro.
    String frag64 = Base64.getEncoder().encodeToString( fragmento.getBytes( StandardCharsets.UTF_8 ) );
    String ps = "$ErrorActionPreference='SilentlyContinue';[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
      + "$f=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + frag64 + "'));"
      + "Get-CimInstance Win32_Process -Filter \"" + filtro + "\" | Where-Object { $_.CommandLine -and "
      + "$_.CommandLine.IndexOf($f, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | ForEach-Object { "
      + ( matarlos ? "Stop-Process -Id $_.ProcessId -Force; " : "" )
      + "'PID=' + $_.ProcessId + [char]9 + ($_.CommandLine -replace '[\\r\\n]', ' ') }";
    String cod = Base64.getEncoder().encodeToString( ps.getBytes( StandardCharsets.UTF_16LE ) );
    File tmp = File.createTempFile( "plive-ps", ".txt" );
    try {
      Process pr = arrancar( new ProcessBuilder( powershell(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
        "Bypass", "-EncodedCommand", cod ).redirectErrorStream( true ).redirectOutput( tmp ) );
      pr.getOutputStream().close();
      if ( !pr.waitFor( 60, TimeUnit.SECONDS ) ) {
        pr.destroyForcibly();
        throw new IOException( "PowerShell no respondio" );
      }
      String texto = new String( leer( tmp ), StandardCharsets.UTF_8 );
      for ( String linea : texto.split( "\\r?\\n" ) ) {
        String l = linea.trim();
        int tab = l.indexOf( '\t' );
        if ( l.startsWith( "PID=" ) ) {
          try {
            pids.put( Long.parseLong( ( tab < 0 ? l.substring( 4 ) : l.substring( 4, tab ) ).trim() ), tab < 0 ? ""
              : l.substring( tab + 1 ) );
          } catch ( NumberFormatException e ) {
            // linea rara: se ignora
          }
        }
      }
      return pids;
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
      throw new IOException( "Interrumpido", e );
    } finally {
      tmp.delete();
    }
  }

  /** pb.start() de a uno: ver {@link #ARRANQUE}. */
  private static Process arrancar( ProcessBuilder pb ) throws IOException {
    synchronized ( ARRANQUE ) {
      return pb.start();
    }
  }

  private static String powershell() {
    String raiz = System.getenv( "SystemRoot" );
    if ( raiz != null ) {
      File f = new File( raiz, "System32\\WindowsPowerShell\\v1.0\\powershell.exe" );
      if ( f.isFile() ) {
        return f.getAbsolutePath();
      }
    }
    return "powershell";
  }

  private static File descarte() {
    return new File( esWindows() ? "NUL" : "/dev/null" );
  }

  // ------------------------------------------------------------------------------------- utilidades

  /** Los perfiles (--user-data-dir) que uso esta JVM, para verificar que no quede nada vivo. */
  public static Set<String> perfilesUsados() {
    return Collections.unmodifiableSet( new LinkedHashSet<>( PERFILES ) );
  }

  /** Las carpetas de trabajo que creo esta JVM (todas deberian estar borradas al terminar cada tarea). */
  public static Set<String> carpetasUsadas() {
    return Collections.unmodifiableSet( new LinkedHashSet<>( CARPETAS ) );
  }

  /** Cuantas corridas hubo a la vez como maximo en esta JVM (el semaforo lo limita a 2). */
  public static int maximoSimultaneo() {
    return MAXIMO.get();
  }

  /** Carpeta de trabajo nueva y vacia: %TEMP%\plive\n\&lt;id&gt; (corta). La borra quien la pide. */
  public static File carpetaTrabajo() throws IOException {
    File d = new File( new File( temporal(), "plive" ), "n" + File.separator + nuevoId() );
    if ( !d.mkdirs() && !d.isDirectory() ) {
      throw new IOException( "No se pudo crear " + d );
    }
    CARPETAS.add( d.getAbsolutePath() );
    return d;
  }

  /** file:///C:/... de un archivo local, apto para la linea de comandos del navegador. */
  public static String url( File f ) {
    String u = f.getAbsoluteFile().toURI().toASCIIString();
    if ( u.startsWith( "file:/" ) && !u.startsWith( "file:///" ) ) {
      u = "file:///" + u.substring( "file:/".length() );
    }
    return u;
  }

  /** Borra un archivo o una carpeta entera, con reintentos (el navegador suelta los archivos tarde). */
  public static boolean borrar( File f ) {
    if ( f == null || !f.exists() ) {
      return true;
    }
    for ( int i = 0; i < 10; i++ ) {
      borrarUnaVez( f );
      if ( !f.exists() ) {
        return true;
      }
      try {
        Thread.sleep( 150 );
      } catch ( InterruptedException e ) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return !f.exists();
  }

  /** Lo que no se pudo borrar ahora se intenta al cerrar la JVM (y si no, limpiarViejos al dia). */
  private static void alSalir( File f ) {
    f.deleteOnExit();
    File[] hijos = f.listFiles();
    if ( hijos != null ) {
      for ( File h : hijos ) {
        alSalir( h );
      }
    }
  }

  private static void borrarUnaVez( File f ) {
    File[] hijos = f.isDirectory() ? f.listFiles() : null;
    if ( hijos != null ) {
      for ( File h : hijos ) {
        borrarUnaVez( h );
      }
    }
    f.delete();
  }

  static File temporal() {
    return new File( System.getProperty( "java.io.tmpdir" ) ).getAbsoluteFile();
  }

  static boolean esWindows() {
    return System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT ).startsWith( "windows" );
  }

  /** 8 caracteres [0-9a-z]: todos los ids del mismo largo, asi ninguno es prefijo de otro. */
  static String nuevoId() {
    String s = Long.toString( ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36 );
    while ( s.length() < 8 ) {
      s = "0" + s;
    }
    return s.substring( s.length() - 8 );
  }

  /** Una vez por JVM: borra perfiles y carpetas de trabajo de mas de 1 dia (de corridas que murieron). */
  private static void limpiarViejos() {
    if ( limpiado ) {
      return;
    }
    limpiado = true;
    long limite = System.currentTimeMillis() - 24L * 3600L * 1000L;
    File[] viejos = temporal().listFiles( ( dir, n ) -> n.startsWith( PREFIJO_PERFIL ) && n.length() == PREFIJO_PERFIL
      .length() + 8 );
    if ( viejos != null ) {
      for ( File v : viejos ) {
        if ( v.isDirectory() && v.lastModified() < limite ) {
          borrarUnaVez( v );
        }
      }
    }
    File[] trabajo = new File( new File( temporal(), "plive" ), "n" ).listFiles();
    if ( trabajo != null ) {
      for ( File v : trabajo ) {
        if ( v.lastModified() < limite ) {
          borrarUnaVez( v );
        }
      }
    }
  }

  static byte[] leer( File f ) throws IOException {
    try ( InputStream in = new FileInputStream( f ) ) {
      ByteArrayOutputStream bo = new ByteArrayOutputStream( (int) Math.min( Integer.MAX_VALUE, Math.max( 16, f
        .length() ) ) );
      byte[] buf = new byte[ 65536 ];
      int n;
      while ( ( n = in.read( buf ) ) > 0 ) {
        bo.write( buf, 0, n );
      }
      return bo.toByteArray();
    }
  }

  private static String cola( File f, int max ) {
    try {
      byte[] b = leer( f );
      int desde = Math.max( 0, b.length - max );
      return new String( b, desde, b.length - desde, Charset.defaultCharset() ).trim();
    } catch ( IOException e ) {
      return "";
    }
  }
}
