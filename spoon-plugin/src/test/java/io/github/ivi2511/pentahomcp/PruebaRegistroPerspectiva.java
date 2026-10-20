package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.annotations.LifecyclePlugin;
import org.pentaho.di.core.plugins.LifecyclePluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPerspectiveManager;
import org.pentaho.di.ui.spoon.SpoonPlugin;
import org.pentaho.di.ui.spoon.SpoonPluginCategories;
import org.pentaho.di.ui.spoon.SpoonPluginInterface;
import org.pentaho.di.ui.spoon.SpoonPluginManager;
import org.pentaho.di.ui.spoon.SpoonPluginType;

/**
 * Registro de la perspectiva con el jar real (diseno §1.2, §1.3 y §8.1; experimento 1 del Anexo A).
 * <p>
 * Arma el jar del plugin con las clases compiladas de main (las mismas que empaqueta build.ps1) y lo pone
 * en {@code <prueba>\registro\plugins\pentaho-mcp\} con los POI en {@code lib\}, como el zip. Despues corre
 * una JVM aparte (el {@link Arnes}) con el cwd ahi: {@code SpoonPluginType} solo mira {@code plugins}
 * relativo al cwd y {@code SpoonPluginManager.<clinit>} corre una vez por JVM, asi que cada variante (normal,
 * apagada por system property, apagada por kettle.properties) necesita su propia JVM. Las clases del plugin
 * NO estan en el classpath del arnes: solo se cargan por el KettleURLClassLoader del jar.
 * <p>
 * {@code -Dpruebas.jar=<ruta>} (o la variable PRUEBAS_JAR) prueba otro jar ya construido, por ejemplo el de
 * {@code dist\pentaho-mcp\}.
 *
 * @dueño WP1
 */
public class PruebaRegistroPerspectiva {

  static final String CLASE_SPOON = "io.github.ivi2511.pentahomcp.ReportsSpoonPlugin";
  static final long TOPE_MS = 240000;

  private File base;
  private File plugins;
  private File arnes;
  private File jar;

  public void preparar() throws Exception {
    base = new File( EntornoKettle.carpeta(), "registro" );
    EntornoKettle.borrar( base.toPath() );
    plugins = new File( base, "plugins" );
    File carpeta = new File( plugins, "pentaho-mcp" );
    File lib = new File( carpeta, "lib" );
    lib.mkdirs();

    String externo = System.getProperty( "pruebas.jar", System.getenv( "PRUEBAS_JAR" ) );
    jar = new File( carpeta, "pentaho-mcp-core-prueba.jar" );
    if ( externo != null && !externo.trim().isEmpty() ) {
      Files.copy( new File( externo.trim() ).toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING );
    } else {
      armarJar( clasesMain(), jar );
    }
    File poi = new File( EntornoKettle.pdi(), "plugins/pdi-core-plugins/lib" );
    for ( String n : new String[] { "poi-4.1.1.jar", "poi-ooxml-4.1.1.jar", "poi-ooxml-schemas-4.1.1.jar" } ) {
      Files.copy( new File( poi, n ).toPath(), new File( lib, n ).toPath(), StandardCopyOption.REPLACE_EXISTING );
    }
    EntornoKettle.escribir( new File( carpeta, "version.xml" ),
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<version>prueba</version>\n" );

    // el arnes, solo: sin las demas clases de prueba ni las de main
    arnes = new File( base, "arnes" );
    File origen = new File( PruebaRegistroPerspectiva.class.getProtectionDomain().getCodeSource().getLocation()
      .toURI() );
    String rel = "io/github/ivi2511/pentahomcp/";
    File destino = new File( arnes, rel );
    destino.mkdirs();
    File[] clases = new File( origen, rel ).listFiles();
    int n = 0;
    if ( clases != null ) {
      for ( File f : clases ) {
        if ( f.getName().startsWith( "PruebaRegistroPerspectiva$Arnes" ) ) {
          Files.copy( f.toPath(), new File( destino, f.getName() ).toPath(), StandardCopyOption.REPLACE_EXISTING );
          n++;
        }
      }
    }
    cierto( n > 0, "no se encontro la clase del arnes en " + origen );
  }

  // ---------------------------------------------------------------- en esta JVM

  /** Las dos anotaciones sin classLoaderGroup (la trampa del sketch, §1.2 y §1.3). */
  public void pruebaAnotacionesSinGrupo() {
    SpoonPlugin sp = ReportsSpoonPlugin.class.getAnnotation( SpoonPlugin.class );
    noNulo( sp, "@SpoonPlugin" );
    igual( "PentahoLiveReports", sp.id(), "id del @SpoonPlugin" );
    igual( "", sp.classLoaderGroup(), "@SpoonPlugin sin classLoaderGroup" );
    SpoonPluginCategories cat = ReportsSpoonPlugin.class.getAnnotation( SpoonPluginCategories.class );
    noNulo( cat, "@SpoonPluginCategories" );
    cierto( java.util.Arrays.asList( cat.value() ).contains( "spoon" ), "categoria spoon" );
    LifecyclePlugin lp = McpLifecycleListener.class.getAnnotation( LifecyclePlugin.class );
    noNulo( lp, "@LifecyclePlugin" );
    igual( "", lp.classLoaderGroup(), "@LifecyclePlugin sin classLoaderGroup" );
    igual( "pentaho-live-reports", ReportsPerspective.ID, "id de la perspectiva" );
  }

  /** Valores del interruptor y el chequeo de loaders. */
  public void pruebaInterruptor() {
    for ( String v : new String[] { "off", "OFF", " Off ", "false", "0", "no", "disabled" } ) {
      cierto( Interruptor.apagado( v ), "'" + v + "' apaga" );
    }
    for ( String v : new String[] { null, "", "on", "true", "1", "yes", "offf" } ) {
      falso( Interruptor.apagado( v ), "'" + v + "' no apaga" );
    }
    String antes = System.getProperty( Interruptor.PROPIEDAD );
    try {
      System.setProperty( Interruptor.PROPIEDAD, "off" );
      cierto( Interruptor.informesApagados(), "system property off" );
      System.setProperty( Interruptor.PROPIEDAD, "on" );
      falso( Interruptor.informesApagados(), "system property on" );
    } finally {
      if ( antes == null ) {
        System.clearProperty( Interruptor.PROPIEDAD );
      } else {
        System.setProperty( Interruptor.PROPIEDAD, antes );
      }
    }
    igual( "bootstrap", Interruptor.identidadLoader( null ), "loader null" );
    String sp = System.getProperty( Interruptor.PROP_LOADER_SPOON );
    String lc = System.getProperty( Interruptor.PROP_LOADER_LIFECYCLE );
    try {
      System.setProperty( Interruptor.PROP_LOADER_SPOON, "a@1" );
      System.setProperty( Interruptor.PROP_LOADER_LIFECYCLE, "a@1" );
      nulo( Interruptor.loadersDistintos(), "mismo loader" );
      System.setProperty( Interruptor.PROP_LOADER_LIFECYCLE, "b@2" );
      contiene( Interruptor.loadersDistintos(), "different classloaders", "loaders distintos" );
      System.clearProperty( Interruptor.PROP_LOADER_LIFECYCLE );
      nulo( Interruptor.loadersDistintos(), "falta uno (perspectiva apagada)" );
    } finally {
      restaurar( Interruptor.PROP_LOADER_SPOON, sp );
      restaurar( Interruptor.PROP_LOADER_LIFECYCLE, lc );
    }
  }

  /** Sin Spoon ni Display: nada lanza, misma instancia, nombres traducidos. */
  public void pruebaPluginSinSpoon() throws Exception {
    EntornoKettle.iniciar();
    ReportsSpoonPlugin plugin = new ReportsSpoonPlugin();
    plugin.applyToContainer( "spoon", null );
    nulo( plugin.getLifecycleListener(), "sin SpoonLifecycleListener" );
    SpoonPerspective p1 = plugin.getPerspective();
    SpoonPerspective p2 = plugin.getPerspective();
    noNulo( p1, "perspectiva" );
    cierto( p1 == p2, "getPerspective devuelve siempre la misma instancia" );
    igual( "Reports", p1.getDisplayName( Locale.ENGLISH ), "nombre en" );
    igual( "Informes", p1.getDisplayName( new Locale( "es", "AR" ) ), "nombre es" );
    igual( "Relatórios", p1.getDisplayName( new Locale( "pt", "BR" ) ), "nombre pt" );
    igual( "Reports", p1.getDisplayName( null ), "nombre sin locale" );
    igual( "Reports", p1.getDisplayName( Locale.JAPANESE ), "otro idioma -> en" );
    nulo( p1.getActiveMeta(), "getActiveMeta null" );
    nulo( p1.getPerspectiveIcon(), "sin icono" );
    cierto( p1.getOverlays().isEmpty() && p1.getEventHandlers().isEmpty(), "sin overlays ni handlers" );
    igual( Boolean.FALSE, p1.getClass().getMethod( "onFileClose" ).invoke( p1 ), "onFileClose por reflexion" );
    p1.setActive( true ); // sin UI todavia: no hace nada y no lanza
    p1.setActive( false );
    nulo( p1.getUI(), "sin Display no hay UI, pero tampoco excepcion" );
    cierto( System.getProperty( Interruptor.PROP_LOADER_SPOON ) != null, "el constructor marca su loader" );
  }

  // ---------------------------------------------------------------- con el jar real, en otra JVM

  /** La perspectiva se registra y comparte loader y clases con el @LifecyclePlugin. */
  public void pruebaRegistroConJarReal() throws Exception {
    Map<String, String> r = correrArnes( "normal", null, false );
    igual( "true", r.get( "life_encontrado" ), "@LifecyclePlugin registrado" );
    igual( "true", r.get( "spoon_encontrado" ), "@SpoonPlugin registrado" );
    contiene( r.get( "spoon_dir" ), "registro", "el SpoonPlugin sale de la carpeta temporal" );
    contiene( r.get( "life_dir" ), "registro", "el lifecycle sale de la carpeta temporal" );
    igual( "", r.get( "life_grupo" ), "lifecycle sin grupo" );
    igual( "", r.get( "spoon_grupo" ), "spoon plugin sin grupo" );
    igual( "true", r.get( "spoon_instanciado" ), "SpoonPluginManager instancio el plugin" );
    igual( "true", r.get( "loader_no_es_app" ), "el plugin carga con su propio loader, no el de la app" );
    igual( "true", r.get( "loader_mismo" ), "MISMO classloader que el @LifecyclePlugin" );
    igual( "true", r.get( "clase_compartida" ), "InformeServicio es la misma clase para los dos" );
    igual( "true", r.get( "docs_compartida" ), "Docs es la misma clase para los dos" );
    igual( "true", r.get( "prop_spoon_ok" ), "pentaho.live.loader.spoon con la identidad del loader" );
    igual( "false", r.get( "perspectiva_null" ), "hay perspectiva" );
    igual( "true", r.get( "perspectiva_misma" ), "getPerspective dos veces: misma instancia" );
    igual( "true", r.get( "registrada" ), "SpoonPerspectiveManager la tiene" );
    igual( "1", r.get( "registrada_veces" ), "una sola vez" );
    igual( "true", r.get( "registrada_misma" ), "la registrada es la del plugin" );
    igual( "Reports", r.get( "nombre_en" ), "nombre en" );
    igual( "Informes", r.get( "nombre_es" ), "nombre es" );
    igual( "Relatórios", r.get( "nombre_pt" ), "nombre pt" );
    igual( "true", r.get( "meta_activa_null" ), "getActiveMeta null" );
    igual( "true", r.get( "overlays_vacios" ), "sin overlays" );
    igual( "false", r.get( "on_file_close" ), "onFileClose como lo llama Spoon" );
    igual( "true", r.get( "fin" ), "el arnes termino" );
  }

  /** {@code -DPENTAHO_LIVE_REPORTS=off}: el plugin se instancia, pero sin perspectiva; el MCP sigue. */
  public void pruebaApagadoPorPropiedad() throws Exception {
    Map<String, String> r = correrArnes( "off-prop", "-DPENTAHO_LIVE_REPORTS=off", false );
    apagada( r );
  }

  /** {@code PENTAHO_LIVE_REPORTS=off} en kettle.properties (lo carga KettleEnvironment.init antes). */
  public void pruebaApagadoPorKettleProperties() throws Exception {
    Map<String, String> r = correrArnes( "off-kettle", null, true );
    apagada( r );
  }

  private static void apagada( Map<String, String> r ) {
    igual( "true", r.get( "life_encontrado" ), "el MCP sigue registrado" );
    igual( "true", r.get( "spoon_instanciado" ), "el plugin se instancia igual" );
    igual( "off", r.get( "propiedad" ), "la propiedad llego" );
    igual( "true", r.get( "perspectiva_null" ), "getPerspective devuelve null" );
    igual( "true", r.get( "perspectiva_misma" ), "y null las dos veces" );
    igual( "false", r.get( "registrada" ), "no hay perspectiva pentaho-live-reports" );
    igual( "true", r.get( "fin" ), "el arnes termino" );
  }

  // ---------------------------------------------------------------- JVM aparte

  private Map<String, String> correrArnes( String variante, String extra, boolean kettleProperties )
    throws Exception {
    File dir = new File( base, "corrida-" + variante );
    EntornoKettle.borrar( dir.toPath() );
    File kh = new File( dir, "kettle-home" );
    File kettle = new File( kh, ".kettle" );
    kettle.mkdirs();
    if ( kettleProperties ) {
      EntornoKettle.escribir( new File( kettle, "kettle.properties" ), "PENTAHO_LIVE_REPORTS=off\n" );
    }
    File home = new File( dir, "home" );
    home.mkdirs();
    File ms = new File( dir, "metastore" );
    ms.mkdirs();
    File pdi = EntornoKettle.pdi();

    List<String> cmd = new ArrayList<String>();
    cmd.add( new File( System.getProperty( "java.home" ), "bin/java" ).getPath() );
    if ( !System.getProperty( "java.specification.version", "1.8" ).startsWith( "1." ) ) {
      for ( String m : new String[] { "java.base/java.net", "java.base/java.lang", "java.base/sun.net.www.protocol.jar" } ) {
        cmd.add( "--add-opens" );
        cmd.add( m + "=ALL-UNNAMED" );
      }
    }
    cmd.add( "-Xmx1024m" );
    cmd.add( "-Djava.locale.providers=COMPAT,SPI" );
    cmd.add( "-Dfile.encoding=UTF-8" );
    cmd.add( "-Dstdout.encoding=UTF-8" );
    cmd.add( "-Djava.awt.headless=true" );
    cmd.add( "-Djava.library.path=" + new File( pdi, "libswt/win64" ).getPath() );
    cmd.add( "-DKETTLE_HOME=" + kh.getPath() );
    cmd.add( "-DKETTLE_PLUGIN_BASE_FOLDERS=" + plugins.getPath() );
    cmd.add( "-DPENTAHO_METASTORE_FOLDER=" + ms.getPath() );
    cmd.add( "-Duser.home=" + home.getPath() );
    if ( extra != null ) {
      cmd.add( extra );
    }
    cmd.add( "-cp" );
    cmd.add( arnes.getPath() + File.pathSeparator + new File( pdi, "lib" ).getPath() + File.separator + "*"
      + File.pathSeparator + new File( pdi, "libswt/win64" ).getPath() + File.separator + "*" + File.pathSeparator
      + new File( pdi, "classes" ).getPath() );
    cmd.add( Arnes.class.getName() );

    File log = new File( dir, "arnes.log" );
    ProcessBuilder pb = new ProcessBuilder( cmd );
    pb.directory( base ); // SpoonPluginType mira "plugins" relativo al cwd
    pb.redirectErrorStream( true );
    pb.redirectOutput( log );
    Map<String, String> env = pb.environment();
    env.put( "KETTLE_HOME", kh.getPath() );
    env.remove( "KETTLE_PLUGIN_BASE_FOLDERS" );
    env.remove( Interruptor.PROPIEDAD );
    Process p = pb.start();
    if ( !p.waitFor( TOPE_MS, TimeUnit.MILLISECONDS ) ) {
      p.destroyForcibly();
      falla( "el arnes no termino en " + TOPE_MS / 1000 + " s; ver " + log );
    }
    String salida = new String( Files.readAllBytes( log.toPath() ), StandardCharsets.UTF_8 );
    Map<String, String> r = new LinkedHashMap<String, String>();
    for ( String linea : salida.split( "\\r?\\n" ) ) {
      int i = linea.indexOf( "R:" );
      int eq = linea.indexOf( '=', i + 2 );
      if ( i == 0 && eq > 0 ) {
        r.put( linea.substring( 2, eq ), linea.substring( eq + 1 ).trim() );
      }
    }
    if ( r.containsKey( "error" ) || p.exitValue() != 0 ) {
      falla( "el arnes fallo (" + p.exitValue() + "): " + r.get( "error" ) + "\n" + cola( salida, 40 ) );
    }
    System.out.println( "  [" + variante + "] " + r );
    return r;
  }

  private static String cola( String s, int lineas ) {
    String[] l = s.split( "\\r?\\n" );
    StringBuilder sb = new StringBuilder();
    for ( int i = Math.max( 0, l.length - lineas ); i < l.length; i++ ) {
      sb.append( "    " ).append( l[ i ] ).append( '\n' );
    }
    return sb.toString();
  }

  /** La carpeta (o jar) de donde se cargaron las clases de main. */
  private static File clasesMain() throws Exception {
    URL u = ReportsSpoonPlugin.class.getProtectionDomain().getCodeSource().getLocation();
    return new File( u.toURI() );
  }

  /** El jar del plugin desde las clases y recursos de main, con el manifiesto de build.ps1. */
  static void armarJar( File clases, File jar ) throws IOException {
    if ( clases.isFile() ) {
      Files.copy( clases.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING );
      return;
    }
    Manifest mf = new Manifest();
    mf.getMainAttributes().put( Attributes.Name.MANIFEST_VERSION, "1.0" );
    mf.getMainAttributes().put( Attributes.Name.IMPLEMENTATION_TITLE, "pentaho-mcp" );
    mf.getMainAttributes().put( Attributes.Name.IMPLEMENTATION_VERSION, "prueba" );
    try ( OutputStream fo = new FileOutputStream( jar ); JarOutputStream jo = new JarOutputStream( fo, mf ) ) {
      agregar( jo, clases.toPath(), clases.toPath() );
    }
    try ( JarFile jf = new JarFile( jar ) ) {
      for ( String e : new String[] { "io/github/ivi2511/pentahomcp/ReportsSpoonPlugin.class",
        "io/github/ivi2511/pentahomcp/McpLifecycleListener.class",
        "io/github/ivi2511/pentahomcp/informes/textos/ui_es.properties" } ) {
        if ( jf.getEntry( e ) == null ) {
          throw new IOException( "el jar no tiene " + e );
        }
      }
      int n = 0;
      for ( Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); en.nextElement() ) {
        n++;
      }
      if ( n < 50 ) {
        throw new IOException( "jar sospechosamente chico: " + n + " entradas" );
      }
    }
  }

  private static void agregar( JarOutputStream jo, Path raiz, Path p ) throws IOException {
    File f = p.toFile();
    String nombre = raiz.relativize( p ).toString().replace( '\\', '/' );
    if ( f.isDirectory() ) {
      if ( !nombre.isEmpty() ) {
        jo.putNextEntry( new JarEntry( nombre + "/" ) );
        jo.closeEntry();
      }
      File[] hijos = f.listFiles();
      if ( hijos != null ) {
        java.util.Arrays.sort( hijos );
        for ( File h : hijos ) {
          agregar( jo, raiz, h.toPath() );
        }
      }
      return;
    }
    jo.putNextEntry( new JarEntry( nombre ) );
    try ( InputStream in = new FileInputStream( f ) ) {
      byte[] buf = new byte[ 16384 ];
      int n;
      while ( ( n = in.read( buf ) ) > 0 ) {
        jo.write( buf, 0, n );
      }
    }
    jo.closeEntry();
  }

  private static void restaurar( String k, String v ) {
    if ( v == null ) {
      System.clearProperty( k );
    } else {
      System.setProperty( k, v );
    }
  }

  // ================================================================ el arnes (otra JVM)

  /**
   * Emula el orden de {@code Spoon.main} / {@code new Spoon()} / {@code Spoon.init()} para el descubrimiento
   * de plugins, sin Spoon. Solo usa la API de PDI: las clases del plugin llegan por el loader del jar.
   * Imprime lineas {@code R:clave=valor}.
   */
  public static final class Arnes {

    private Arnes() {
    }

    public static void main( String[] args ) {
      int codigo = 0;
      try {
        correr();
      } catch ( Throwable t ) {
        t.printStackTrace( System.out );
        r( "error", t.toString().replace( '\n', ' ' ) );
        codigo = 1;
      }
      System.out.flush();
      System.exit( codigo ); // Kettle deja hilos no daemon
    }

    static void r( String k, Object v ) {
      System.out.println( "R:" + k + "=" + v );
      System.out.flush();
    }

    static void correr() throws Exception {
      r( "cwd", new File( "." ).getCanonicalPath() );
      // Spoon.registerUIPluginObjectTypes(): SpoonPluginType ANTES de KettleEnvironment.init()
      PluginRegistry.addPluginType( SpoonPluginType.getInstance() );
      KettleClientEnvironment.getInstance().setClient( KettleClientEnvironment.ClientType.SPOON );
      KettleEnvironment.init();
      r( "propiedad", System.getProperty( "PENTAHO_LIVE_REPORTS" ) );
      PluginRegistry reg = PluginRegistry.getInstance();
      PluginInterface life = reg.findPluginWithId( LifecyclePluginType.class, "PentahoMCP" );
      PluginInterface sp = reg.findPluginWithId( SpoonPluginType.class, "PentahoLiveReports" );
      r( "life_encontrado", life != null );
      r( "spoon_encontrado", sp != null );
      if ( life == null || sp == null ) {
        r( "fin", false );
        return;
      }
      r( "life_dir", life.getPluginDirectory() );
      r( "spoon_dir", sp.getPluginDirectory() );
      r( "life_grupo", life.getClassLoaderGroup() == null ? "" : life.getClassLoaderGroup() );
      r( "spoon_grupo", sp.getClassLoaderGroup() == null ? "" : sp.getClassLoaderGroup() );

      // new Spoon(): new LifecycleSupport() instancia cada @LifecyclePlugin
      Object lifecycle = reg.loadClass( life );
      // Spoon.init(): primer uso de SpoonPluginManager -> <clinit> -> pluginAdded -> addPerspective
      SpoonPluginInterface nuestro = null;
      for ( SpoonPluginInterface p : SpoonPluginManager.getInstance().getPlugins() ) {
        if ( p.getClass().getName().equals( CLASE_SPOON ) ) {
          nuestro = p;
        }
      }
      r( "spoon_instanciado", nuestro != null );
      if ( nuestro == null ) {
        r( "fin", false );
        return;
      }
      ClassLoader cl1 = lifecycle.getClass().getClassLoader();
      ClassLoader cl2 = nuestro.getClass().getClassLoader();
      r( "loader_clase", cl1 == null ? "null" : cl1.getClass().getName() );
      r( "loader_mismo", cl1 == cl2 );
      r( "loader_no_es_app", cl1 != ClassLoader.getSystemClassLoader() && cl1 != Arnes.class.getClassLoader() );
      r( "clase_compartida", Class.forName( "io.github.ivi2511.pentahomcp.InformeServicio", false, cl1 ) == Class
        .forName( "io.github.ivi2511.pentahomcp.InformeServicio", false, cl2 ) );
      r( "docs_compartida", Class.forName( "io.github.ivi2511.pentahomcp.Docs", false, cl1 ) == Class.forName(
        "io.github.ivi2511.pentahomcp.Docs", false, cl2 ) );
      Method ident = Class.forName( "io.github.ivi2511.pentahomcp.Interruptor", true, cl2 ).getDeclaredMethod(
        "identidadLoader", ClassLoader.class );
      ident.setAccessible( true );
      r( "prop_spoon_ok", String.valueOf( ident.invoke( null, cl2 ) ).equals( System.getProperty(
        "pentaho.live.loader.spoon" ) ) );

      SpoonPerspective p1 = nuestro.getPerspective();
      SpoonPerspective p2 = nuestro.getPerspective();
      r( "perspectiva_null", p1 == null );
      r( "perspectiva_misma", p1 == p2 );
      SpoonPerspective registrada = null;
      int veces = 0;
      for ( SpoonPerspective p : SpoonPerspectiveManager.getInstance().getPerspectives() ) {
        if ( "pentaho-live-reports".equals( p.getId() ) ) {
          registrada = p;
          veces++;
        }
      }
      r( "registrada", registrada != null );
      r( "registrada_veces", veces );
      r( "registrada_misma", registrada != null && registrada == p1 );
      if ( registrada != null ) {
        r( "nombre_en", registrada.getDisplayName( Locale.ENGLISH ) );
        r( "nombre_es", registrada.getDisplayName( new Locale( "es", "ES" ) ) );
        r( "nombre_pt", registrada.getDisplayName( new Locale( "pt", "BR" ) ) );
        r( "meta_activa_null", registrada.getActiveMeta() == null );
        r( "overlays_vacios", registrada.getOverlays() != null && registrada.getOverlays().isEmpty()
          && registrada.getEventHandlers() != null && registrada.getEventHandlers().isEmpty() );
        // Spoon.tabCloseSelected: getClass().getMethod("onFileClose").invoke(...) -> Boolean
        r( "on_file_close", registrada.getClass().getMethod( "onFileClose" ).invoke( registrada ) );
      }
      r( "fin", true );
    }
  }
}
