package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.logging.KettleLogStore;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Sev;
import io.github.ivi2511.pentahomcp.lint.Textos;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * Los comandos de proyecto de Pentaho Live (0.7, X1 + E5 + P4), sin Spoon. Los lanza {@code pentaho-live <comando>}
 * (launcher/pentaho-live.cmd y launcher/pentaho-live) y el workflow de CI que crea init:
 *
 * <pre>
 *   pentaho-live init [carpeta] [--name N] [--lang en|es|pt] [--dry-run]
 *   pentaho-live lint &lt;archivos o carpetas&gt;... [--profile ruta|none] [--min-score N] [--lang en|es|pt]
 *                     [--format text|json|github] [--summary archivo.md] [--robustness]
 *   pentaho-live profile [carpeta o archivo]          (el perfil efectivo, en JSON)
 *   pentaho-live test [--all] [pruebas, .ktr o carpetas]... [--format text|json|github] [--summary archivo.md]
 *                     [--timeout segundos]
 * </pre>
 *
 * lint busca el perfil de cada archivo subiendo carpetas (.pentaho-live/lint.yaml) y lo compara con su min_score (o
 * con --min-score, que manda). Códigos de salida: 0 todo bien; 1 algún archivo por debajo del mínimo o que no se pudo
 * leer; 2 un error de uso o de perfil. init: 0, o 2 si no pudo crear algo. test (P4: las pruebas con datasets dorados
 * en la CI, en JVMs aparte como test_run_all): 0 todas pasan o no hay ninguna; 1 alguna falla, da error o no terminó a
 * tiempo; 2 un error de uso. La salida de Kettle (sus logs) va a stderr, sin el ruido de un PDI sin Spoon ({@link
 * #ruido}): stdout queda para el resultado (--format json se puede leer con una herramienta). test habla en/es/pt
 * (--lang o el idioma del sistema, como lint y diff; textos en informes/textos/proyecto_*.properties).
 */
public final class ProyectoMain {

  static final int OK = 0;
  static final int DEBAJO = 1;
  static final int ERROR = 2;

  private final PrintStream out;
  private final PrintStream err;
  /** Carpeta de trabajo (las pruebas la cambian). */
  File cwd = new File( "" ).getAbsoluteFile();
  /** Las pruebas ya iniciaron Kettle. */
  boolean kettleListo;

  ProyectoMain( PrintStream out, PrintStream err ) {
    this.out = out;
    this.err = err;
  }

  public static void main( String[] args ) {
    PrintStream o = utf8( System.out );
    PrintStream e = utf8( System.err );
    System.setOut( e ); // los logs de Kettle no ensucian el resultado
    int codigo;
    try {
      codigo = new ProyectoMain( o, e ).correr( args );
    } catch ( Throwable t ) {
      e.println( "pentaho-live: " + t );
      codigo = ERROR;
    }
    o.flush();
    e.flush();
    System.exit( codigo );
  }

  static final class Uso extends Exception {
    private static final long serialVersionUID = 1L;

    Uso( String m ) {
      super( m );
    }
  }

  int correr( String[] args ) {
    if ( args.length == 0 ) {
      ayuda();
      return ERROR;
    }
    List<String> resto = new ArrayList<String>();
    Collections.addAll( resto, args );
    String cmd = resto.remove( 0 ).toLowerCase( Locale.ROOT );
    try {
      switch ( cmd ) {
        case "init":
          return init( resto );
        case "lint":
          return lint( resto );
        case "profile":
        case "lint-profile":
          return perfil( resto );
        case "test":
        case "tests":
          return pruebas( resto );
        case "help":
        case "--help":
        case "-h":
          ayuda();
          return OK;
        default:
          throw new Uso( "unknown command '" + cmd + "'" );
      }
    } catch ( Uso u ) {
      err.println( "pentaho-live " + cmd + ": " + u.getMessage() );
      err.println( "Run 'pentaho-live help' for the usage." );
      return ERROR;
    }
  }

  void ayuda() {
    out.println( "Pentaho Live - project commands (no Spoon needed):" );
    out.println( "  pentaho-live init [folder] [--name N] [--lang en|es|pt] [--dry-run]" );
    out.println( "      creates only what is missing: etl/ datasets/ tests/ contracts/ env/, .gitignore, .gitattributes," );
    out.println( "      .pentaho-live/lint.yaml (lint profile), README.md and .github/workflows/pentaho-live.yml" );
    out.println( "  pentaho-live lint <files or folders> [--profile path|none] [--min-score N] [--lang en|es|pt]" );
    out.println( "                    [--format text|json|github] [--summary file.md] [--robustness]" );
    out.println( "      lints every .ktr/.kjb with the project's profile; exit 1 if one is below the minimum score" );
    out.println( "  pentaho-live profile [folder or file]   the lint profile that applies there (JSON)" );
    out.println( "  pentaho-live test [--all] [tests, .ktr files or folders] [--format text|json|github]" );
    out.println( "                    [--summary file.md] [--timeout seconds]" );
    out.println( "      runs the golden-dataset tests (*.test.json; every one under the current folder by default)" );
    out.println( "      in a separate JVM; exit 1 if one fails" );
  }

  // ================================================================ init

  int init( List<String> a ) throws Uso {
    String carpeta = null;
    String nombre = null;
    String lang = Locale.getDefault().getLanguage();
    boolean ensayo = false;
    for ( int i = 0; i < a.size(); i++ ) {
      String x = a.get( i );
      if ( x.equals( "--name" ) ) {
        nombre = valor( a, ++i, x );
      } else if ( x.equals( "--lang" ) ) {
        lang = valor( a, ++i, x );
      } else if ( x.equals( "--dry-run" ) ) {
        ensayo = true;
      } else if ( x.startsWith( "--" ) ) {
        throw new Uso( "unknown option " + x );
      } else if ( carpeta == null ) {
        carpeta = x;
      } else {
        throw new Uso( "only one folder, got also '" + x + "'" );
      }
    }
    File raiz = carpeta == null ? cwd : absoluto( carpeta );
    ProyectoInit.Hecho h;
    try {
      h = ProyectoInit.crear( raiz, nombre, lang, ensayo );
    } catch ( IOException e ) {
      err.println( "pentaho-live init: " + e.getMessage() );
      return ERROR;
    }
    out.println( ( ensayo ? "Pentaho Live init (dry run, nothing written): " : "Pentaho Live init: " ) + h.raiz );
    out.print( ProyectoInit.texto( h ) );
    int n = h.cuantos( ensayo ? ProyectoInit.SE_CREARIA : ProyectoInit.CREADO );
    int ya = h.cuantos( ProyectoInit.EXISTE );
    out.println( ( ensayo ? n + " would be created" : n + " created" ) + ", " + ya + " already there (never "
      + "overwritten)." );
    if ( !h.textconv ) {
      out.println( "Note: .gitattributes has the diff driver lines commented: this Pentaho Live has no "
        + "'pentaho-live textconv' yet." );
    }
    if ( !ensayo ) {
      out.println( "Next: put the .ktr/.kjb in etl/ and run 'pentaho-live lint etl'." );
    }
    return OK;
  }

  // ================================================================ profile

  int perfil( List<String> a ) throws Uso {
    if ( a.size() > 1 ) {
      throw new Uso( "one folder or profile file" );
    }
    File f = a.isEmpty() ? cwd : absoluto( a.get( 0 ) );
    PerfilEquipo.Busqueda b = f.isFile() && !esEtl( f ) ? PerfilEquipo.resolver( f.getPath(), null, cwd )
      : PerfilEquipo.buscar( f.isFile() ? f.getParentFile() : f );
    out.println( bonito( PerfilEquipo.busquedaJson( b ) ) );
    return b.error != null ? ERROR : OK;
  }

  // ================================================================ lint

  /** Un archivo revisado. */
  static final class Revisado {
    File archivo;
    String ruta;
    Resultado res;
    String error;
    Integer minimo;
    boolean pasa;
    String perfil;
    String perfilRuta;
  }

  int lint( List<String> a ) throws Uso {
    List<String> rutas = new ArrayList<String>();
    String pedido = null;
    Integer min = null;
    String lang = Locale.getDefault().getLanguage();
    String formato = "text";
    String resumen = null;
    boolean robustez = false;
    for ( int i = 0; i < a.size(); i++ ) {
      String x = a.get( i );
      if ( x.equals( "--profile" ) ) {
        pedido = valor( a, ++i, x );
      } else if ( x.equals( "--min-score" ) ) {
        String v = valor( a, ++i, x );
        try {
          min = Integer.parseInt( v.trim() );
        } catch ( NumberFormatException e ) {
          throw new Uso( "--min-score must be a whole number 0-100, got '" + v + "'" );
        }
        if ( min < 0 || min > 100 ) {
          throw new Uso( "--min-score must be between 0 and 100" );
        }
      } else if ( x.equals( "--lang" ) ) {
        lang = valor( a, ++i, x );
      } else if ( x.equals( "--format" ) ) {
        formato = valor( a, ++i, x ).toLowerCase( Locale.ROOT );
        if ( !formato.equals( "text" ) && !formato.equals( "json" ) && !formato.equals( "github" ) ) {
          throw new Uso( "--format must be text, json or github" );
        }
      } else if ( x.equals( "--summary" ) ) {
        resumen = valor( a, ++i, x );
      } else if ( x.equals( "--robustness" ) ) {
        robustez = true;
      } else if ( x.startsWith( "--" ) ) {
        throw new Uso( "unknown option " + x );
      } else {
        rutas.add( x );
      }
    }
    if ( rutas.isEmpty() ) {
      throw new Uso( "which files? Example: pentaho-live lint etl   (a folder: every .ktr/.kjb inside)" );
    }
    List<File> archivos = new ArrayList<File>();
    for ( String r : rutas ) {
      File f = absoluto( r );
      if ( !f.exists() ) {
        throw new Uso( "not found: " + r );
      }
      juntar( f, archivos );
    }
    lang = Textos.normalizar( lang );
    // un --profile explícito se revisa antes de iniciar Kettle (un error de perfil es de uso)
    PerfilEquipo.Busqueda fijo = null;
    if ( pedido != null ) {
      fijo = PerfilEquipo.resolver( pedido, null, cwd );
      if ( fijo.error != null ) {
        err.println( "pentaho-live lint: " + fijo.error );
        return ERROR;
      }
    }
    if ( archivos.isEmpty() ) {
      out.println( "pentaho-live lint: no .ktr/.kjb in " + rutas + " - nothing to check." );
      escribirResumen( resumen, Collections.<Revisado>emptyList(), min );
      return OK;
    }
    iniciarKettle();
    Map<File, PerfilEquipo.Busqueda> porCarpeta = new LinkedHashMap<File, PerfilEquipo.Busqueda>();
    List<Revisado> hechos = new ArrayList<Revisado>();
    for ( File f : archivos ) {
      Revisado rv = new Revisado();
      rv.archivo = f;
      rv.ruta = relativa( f );
      PerfilEquipo.Busqueda b = fijo;
      if ( b == null ) {
        File d = f.getParentFile();
        b = porCarpeta.get( d );
        if ( b == null ) {
          b = PerfilEquipo.buscar( d );
          porCarpeta.put( d, b );
        }
      }
      try {
        AbstractMeta m = cargar( f );
        Opciones op = new Opciones();
        op.lang = lang;
        op.robustez = robustez;
        op.perfil = b.perfil;
        LintEntorno env = new LintEntorno( m );
        op.entorno = env;
        rv.res = LintMotor.analizar( m, op );
        LintPropuestas.completar( rv.res, m, lang );
        if ( b.perfil == null && b.error != null ) {
          rv.res.perfilArchivo = b.archivo == null ? null : b.archivo.getAbsolutePath();
          rv.res.perfilError = b.error;
        }
      } catch ( Throwable t ) {
        rv.error = RunMotor.primeraLinea( t );
      }
      if ( b.perfil != null ) {
        rv.perfil = b.perfil.nombre;
        rv.perfilRuta = b.perfil.archivo == null ? null : b.perfil.archivo.getAbsolutePath();
      }
      rv.minimo = min != null ? min : b.perfil != null ? b.perfil.minScore : null;
      rv.pasa = rv.error == null && ( rv.minimo == null || rv.res.score >= rv.minimo );
      if ( b.perfil == null && b.error != null && rv.error == null ) {
        rv.pasa = false; // un perfil roto no deja pasar la CI en silencio
        rv.error = "lint profile: " + b.error;
      }
      hechos.add( rv );
    }
    if ( formato.equals( "json" ) ) {
      out.println( bonito( json( hechos, min, pedido ) ) );
    } else if ( formato.equals( "github" ) ) {
      github( hechos );
      texto( hechos );
    } else {
      texto( hechos );
    }
    escribirResumen( resumen, hechos, min );
    for ( Revisado rv : hechos ) {
      if ( !rv.pasa ) {
        return DEBAJO;
      }
    }
    return OK;
  }

  private void iniciarKettle() {
    if ( kettleListo ) {
      return;
    }
    if ( System.getProperty( "KETTLE_PLUGIN_BASE_FOLDERS" ) == null ) {
      // los plugins de PDI (JSON input, Excel writer…) están en data-integration/plugins, junto a lib/
      File lib = carpetaDeLib();
      if ( lib != null && new File( lib.getParentFile(), "plugins" ).isDirectory() ) {
        System.setProperty( "KETTLE_PLUGIN_BASE_FOLDERS", new File( lib.getParentFile(), "plugins" ).getPath() );
      }
    }
    silenciarRuido();
    try {
      KettleEnvironment.init( false );
    } catch ( Exception e ) {
      throw new IllegalStateException( "Kettle did not start: " + RunMotor.primeraLinea( e ), e );
    }
    kettleListo = true;
  }

  /**
   * Sin Spoon, PDI no tiene SWT ni las clases de los diálogos: al registrar sus plugins Kettle escribe en la consola,
   * por cada uno, «Unexpected error registering jar plugin file» y un ClassNotFoundException del diálogo con su pila
   * (unas 1.700 líneas en stderr en cada lint de la CI). Kettle escribe su log por KettleLogStore.OriginalSystemErr/Out
   * (campos públicos que toma de System.err/out al cargarse): se envuelven con {@link SinRuido}, que tira solo esos
   * pares. Los demás errores (un plugin roto por otra cosa, un .ktr que no carga) siguen saliendo por stderr.
   */
  void silenciarRuido() {
    if ( !( KettleLogStore.OriginalSystemErr instanceof SinRuido ) ) {
      KettleLogStore.OriginalSystemErr = SinRuido.sobre( err );
    }
    if ( !( KettleLogStore.OriginalSystemOut instanceof SinRuido ) ) {
      KettleLogStore.OriginalSystemOut = SinRuido.sobre( err );
    }
  }

  /** La clase que falta es de la interfaz: org.pentaho.di.ui.*, SWT/JFace o el diálogo de un step (…Dialog). */
  static final Pattern CLASE_DE_UI = Pattern.compile( "(ClassNotFoundException|NoClassDefFoundError):\\s*("
    + "org[./]eclipse[./](swt|jface)[./]|org[./]pentaho[./]di[./]ui[./]|[\\w$./]*Dialog\\b)" );
  static final String AL_REGISTRAR = "Unexpected error registering jar plugin file";

  /** Un registro del log de Kettle que dice que falta una clase de la interfaz (ruido sin Spoon). */
  static boolean ruido( String registro ) {
    return registro != null && CLASE_DE_UI.matcher( registro ).find();
  }

  /**
   * Los registros del log de Kettle sin el ruido de un PDI sin Spoon: el aviso «registering jar plugin file» se guarda
   * hasta ver el siguiente; si ese es la clase de interfaz que falta, se tiran los dos; si no, sale el aviso y lo que
   * siga. Una clase de interfaz que falta sola también se tira. Kettle escribe cada registro con println(String).
   */
  static final class SinRuido extends PrintStream {
    private String guardado;

    private SinRuido( PrintStream destino ) throws UnsupportedEncodingException {
      super( destino, true, "UTF-8" );
    }

    static PrintStream sobre( PrintStream destino ) {
      try {
        return new SinRuido( destino );
      } catch ( UnsupportedEncodingException e ) {
        return destino;
      }
    }

    @Override
    public synchronized void println( String x ) {
      String antes = guardado;
      guardado = null;
      if ( x != null && x.contains( AL_REGISTRAR ) ) {
        if ( antes != null ) {
          super.println( antes );
        }
        guardado = x;
        return;
      }
      if ( ruido( x ) ) {
        return; // con el aviso de antes, si lo había
      }
      if ( antes != null ) {
        super.println( antes );
      }
      super.println( x );
    }

    @Override
    public void println( Object x ) {
      println( String.valueOf( x ) );
    }

    @Override
    public synchronized void flush() {
      if ( guardado != null ) {
        String g = guardado;
        guardado = null;
        super.println( g );
      }
      super.flush();
    }
  }

  private static File carpetaDeLib() {
    try {
      File jar = new File( KettleEnvironment.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
      return jar.getParentFile();
    } catch ( Exception e ) {
      return null;
    }
  }

  static AbstractMeta cargar( File f ) throws Exception {
    return f.getName().toLowerCase( Locale.ROOT ).endsWith( ".kjb" ) ? new JobMeta( f.getAbsolutePath(), null )
      : new TransMeta( f.getAbsolutePath() );
  }

  static boolean esEtl( File f ) {
    String n = f.getName().toLowerCase( Locale.ROOT );
    return n.endsWith( ".ktr" ) || n.endsWith( ".kjb" );
  }

  /** Los .ktr/.kjb de f (una carpeta: recursiva, sin carpetas ocultas como .git), en orden. */
  static void juntar( File f, List<File> out ) {
    if ( f.isFile() ) {
      if ( esEtl( f ) && !out.contains( f ) ) {
        out.add( f );
      }
      return;
    }
    File[] hijos = f.listFiles();
    if ( hijos == null ) {
      return;
    }
    List<File> l = new ArrayList<File>();
    Collections.addAll( l, hijos );
    Collections.sort( l );
    for ( File h : l ) {
      if ( h.isDirectory() && !h.getName().startsWith( "." ) ) {
        juntar( h, out );
      } else if ( h.isFile() ) {
        juntar( h, out );
      }
    }
  }

  // ================================================================ test

  /** Lo que tarda como mucho la corrida entera de {@code pentaho-live test}, por defecto (30 min, como la CI). */
  static final int ESPERA_PRUEBAS_S = 1800;

  /**
   * {@code pentaho-live test}: las pruebas con datasets dorados (P1) sin Spoon, para la CI (P4). Sin rutas (o con
   * --all), todas las {@code *.test.json} debajo de la carpeta actual; si no, las de cada carpeta, las de cada .ktr
   * (en sus carpetas tests/) o cada prueba nombrada. Corren como test_run_all: {@link PruebasProcesos}, en JVMs
   * aparte con su propio KETTLE_HOME, una por entorno (locale, zona, encoding).
   */
  int pruebas( List<String> a ) throws Uso {
    List<String> rutas = new ArrayList<String>();
    String formato = "text";
    String resumen = null;
    String lang = Locale.getDefault().getLanguage();
    int espera = ESPERA_PRUEBAS_S;
    for ( int i = 0; i < a.size(); i++ ) {
      String x = a.get( i );
      if ( x.equals( "--all" ) ) {
        continue; // lo mismo que sin rutas: todas las de la carpeta actual (o las de las carpetas dadas)
      } else if ( x.equals( "--lang" ) ) {
        lang = valor( a, ++i, x ); // la consola, las anotaciones y el resumen; el JSON queda con sus códigos
      } else if ( x.equals( "--format" ) ) {
        formato = valor( a, ++i, x ).toLowerCase( Locale.ROOT );
        if ( !formato.equals( "text" ) && !formato.equals( "json" ) && !formato.equals( "github" ) ) {
          throw new Uso( "--format must be text, json or github" );
        }
      } else if ( x.equals( "--summary" ) ) {
        resumen = valor( a, ++i, x );
      } else if ( x.equals( "--timeout" ) ) {
        String v = valor( a, ++i, x );
        try {
          espera = Integer.parseInt( v.trim() );
        } catch ( NumberFormatException e ) {
          throw new Uso( "--timeout must be a number of seconds, got '" + v + "'" );
        }
        if ( espera < 10 ) {
          throw new Uso( "--timeout must be at least 10 seconds" );
        }
      } else if ( x.startsWith( "--" ) ) {
        throw new Uso( "unknown option " + x );
      } else {
        rutas.add( x );
      }
    }
    Rotulos t = Rotulos.de( AREA, lang );
    if ( rutas.isEmpty() ) {
      rutas.add( "." );
    }
    List<File> pruebas = new ArrayList<File>();
    for ( String r : rutas ) {
      File f = absoluto( r );
      if ( !f.exists() ) {
        throw new Uso( "not found: " + r );
      }
      if ( f.isDirectory() ) {
        juntarPruebas( f, pruebas );
      } else if ( f.getName().toLowerCase( Locale.ROOT ).endsWith( DefPrueba.EXTENSION ) ) {
        agregar( f, pruebas );
      } else if ( f.getName().toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) ) {
        List<File> deKtr = PruebasArchivos.deTransformacion( f );
        if ( deKtr.isEmpty() ) {
          err.println( t.f( "test_none_for", relativa( f ) ) );
        }
        for ( File p : deKtr ) {
          agregar( p, pruebas );
        }
      } else {
        throw new Uso( "not a test (" + DefPrueba.EXTENSION + "), a .ktr or a folder: " + r );
      }
    }
    boolean json = formato.equals( "json" );
    if ( pruebas.isEmpty() ) {
      if ( json ) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.putArray( "tests" );
        o.put( "passed", 0 ).put( "failed", 0 );
        out.println( bonito( o ) );
      } else {
        out.println( t.f( "test_none", DefPrueba.EXTENSION, rutas ) );
      }
      escribirResumenPruebas( resumen, Collections.<ResultadoPrueba>emptyList(), t );
      return OK;
    }
    List<PruebasProcesos.Tarea> tareas = new ArrayList<PruebasProcesos.Tarea>();
    for ( File p : pruebas ) {
      tareas.add( PruebasProcesos.Tarea.correr( p, null ) );
    }
    ( json ? err : out ).println( t.f( "test_running", pruebas.size() ) );
    List<ResultadoPrueba> rs;
    try {
      rs = PruebasProcesos.correrYEsperar( tareas, espera * 1000L, new ArrayList<File>() );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
      err.println( t.s( "test_interrupted" ) );
      return ERROR;
    }
    for ( int i = 0; i < rs.size(); i++ ) {
      ResultadoPrueba r = rs.get( i );
      if ( r.archivoPrueba == null ) {
        r.archivoPrueba = pruebas.get( i ).getPath();
      }
      if ( r.prueba == null ) {
        r.prueba = PruebasMotor.nombreDeArchivo( pruebas.get( i ) );
      }
    }
    if ( json ) {
      ObjectNode o = JsonNodeFactory.instance.objectNode();
      ArrayNode l = o.putArray( "tests" );
      int pasan = 0;
      for ( ResultadoPrueba r : rs ) {
        ObjectNode x = PruebasTools.compacto( r, !r.paso() );
        if ( r.archivoPrueba != null ) {
          x.put( "test_file", relativa( new File( r.archivoPrueba ) ) );
        }
        l.add( x );
        pasan += r.paso() ? 1 : 0;
      }
      o.put( "passed", pasan ).put( "failed", rs.size() - pasan );
      out.println( bonito( o ) );
    } else {
      if ( formato.equals( "github" ) ) {
        githubPruebas( rs, t );
      }
      textoPruebas( rs, t );
    }
    escribirResumenPruebas( resumen, rs, t );
    for ( ResultadoPrueba r : rs ) {
      if ( !r.paso() ) {
        return DEBAJO;
      }
    }
    return OK;
  }

  private static void agregar( File f, List<File> l ) {
    File a = f.getAbsoluteFile().toPath().normalize().toFile(); // sin «./» de «pentaho-live test .»
    if ( !l.contains( a ) ) {
      l.add( a );
    }
  }

  /** Los {@code *.test.json} de una carpeta y sus subcarpetas (sin las ocultas como .git), en orden. */
  static void juntarPruebas( File dir, List<File> out ) {
    File[] hijos = dir.listFiles();
    if ( hijos == null ) {
      return;
    }
    List<File> l = new ArrayList<File>();
    Collections.addAll( l, hijos );
    Collections.sort( l );
    for ( File h : l ) {
      if ( h.isDirectory() ) {
        if ( !h.getName().startsWith( "." ) && !h.getName().equals( "node_modules" ) ) {
          juntarPruebas( h, out );
        }
      } else if ( h.getName().toLowerCase( Locale.ROOT ).endsWith( DefPrueba.EXTENSION ) ) {
        agregar( h, out );
      }
    }
  }

  /** Los textos de test (informes/textos/proyecto_*.properties). */
  static final String AREA = "proyecto";

  /** Lo que falló de una prueba, en una línea por salida (para la consola, las anotaciones y el resumen). */
  static List<String> detalle( ResultadoPrueba r, Rotulos t ) {
    List<String> l = new ArrayList<String>();
    for ( ResultadoPrueba.Resumen s : r.salidas ) {
      if ( s.paso ) {
        continue;
      }
      StringBuilder b = new StringBuilder( t.f( ResultadoPrueba.Resumen.TABLA.equals( s.tipo ) ? "d_table" : "d_output",
        s.nombre ) ).append( ": " );
      if ( s.problema != null ) {
        b.append( t.tiene( "d_" + s.problema ) ? t.s( "d_" + s.problema ) : s.problema.replace( '_', ' ' ) );
      } else {
        List<String> p = new ArrayList<String>();
        if ( s.faltan != null && s.faltan > 0 ) {
          p.add( t.f( "d_missing", s.faltan ) );
        }
        if ( s.sobran != null && s.sobran > 0 ) {
          p.add( t.f( "d_extra", s.sobran ) );
        }
        if ( s.distintas != null && s.distintas > 0 ) {
          p.add( t.f( "d_changed", s.distintas ) );
        }
        if ( s.clavesRepetidas != null && s.clavesRepetidas > 0 ) {
          p.add( t.f( "d_dup", s.clavesRepetidas ) );
        }
        if ( s.columnasFaltan != null && !s.columnasFaltan.isEmpty() ) {
          p.add( t.f( "d_missing_cols", s.columnasFaltan ) );
        }
        if ( s.columnasSobran != null && !s.columnasSobran.isEmpty() ) {
          p.add( t.f( "d_extra_cols", s.columnasSobran ) );
        }
        b.append( p.isEmpty() ? t.s( "d_differs" ) : String.join( ", ", p ) );
        if ( s.filasEsperadas != null && s.filasReales != null ) {
          b.append( ' ' ).append( t.f( "d_rows", s.filasEsperadas, s.filasReales ) );
        }
        String e = ejemplo( r, s.nombre, t );
        if ( e != null ) {
          b.append( "; " ).append( e );
        }
      }
      l.add( b.toString() );
    }
    return l;
  }

  /**
   * La primera diferencia de esa salida (una celda que cambió, una fila que falta o sobra, una clave repetida), para que
   * la anotación y el resumen digan qué cambió sin abrir Spoon. null si la prueba no trae ejemplos.
   */
  static String ejemplo( ResultadoPrueba r, String salida, Rotulos t ) {
    if ( r.caso == null || r.caso.tablas == null || salida == null ) {
      return null;
    }
    for ( ResultadoCaso.ComparacionTabla ct : r.caso.tablas ) {
      if ( ct == null || !salida.equals( ct.tabla ) || ct.comparacion == null || ct.comparacion.ejemplos == null ) {
        continue;
      }
      Comparacion.Ejemplos e = ct.comparacion.ejemplos;
      if ( e.distintas != null && !e.distintas.isEmpty() ) {
        Comparacion.Ejemplo x = e.distintas.get( 0 );
        return t.f( "d_example_changed", clave( x ), x.columna, x.esperado, x.real );
      }
      if ( e.faltan != null && !e.faltan.isEmpty() ) {
        return t.f( "d_example_missing", clave( e.faltan.get( 0 ) ) );
      }
      if ( e.sobran != null && !e.sobran.isEmpty() ) {
        return t.f( "d_example_extra", clave( e.sobran.get( 0 ) ) );
      }
      if ( e.clavesRepetidas != null && !e.clavesRepetidas.isEmpty() ) {
        Comparacion.Ejemplo x = e.clavesRepetidas.get( 0 );
        return t.f( "d_example_dup", clave( x ), x.veces == null ? 2 : x.veces );
      }
    }
    return null;
  }

  private static String clave( Comparacion.Ejemplo x ) {
    return x.clave != null ? x.clave : x.fila != null ? String.valueOf( x.fila ) : "?";
  }

  /** El estado de una prueba en el idioma (passed, failed, error, cancelled; otro, tal cual). */
  static String estado( ResultadoPrueba r, Rotulos t ) {
    return r.estado != null && t.tiene( "st_" + r.estado ) ? t.s( "st_" + r.estado ) : String.valueOf( r.estado );
  }

  /** Los segundos como los da la prueba (0.87), con coma decimal en es y pt. */
  static String segundos( ResultadoPrueba r, Rotulos t ) {
    if ( r.segundos == null ) {
      return null;
    }
    String s = String.valueOf( r.segundos );
    return "en".equals( t.idioma() ) ? s : s.replace( '.', ',' );
  }

  private static String primera( String s ) {
    if ( s == null ) {
      return "";
    }
    int i = s.indexOf( '\n' );
    return ( i < 0 ? s : s.substring( 0, i ) ).trim();
  }

  void textoPruebas( List<ResultadoPrueba> rs, Rotulos t ) {
    int pasan = 0;
    String ok4 = String.format( "%-4s", t.s( "test_ok" ) );
    String mal4 = String.format( "%-4s", t.s( "test_fail" ) );
    for ( ResultadoPrueba r : rs ) {
      boolean ok = r.paso();
      pasan += ok ? 1 : 0;
      String archivo = r.archivoPrueba == null ? "" : "  " + relativa( new File( r.archivoPrueba ) );
      String seg = segundos( r, t );
      out.println( "  " + ( ok ? ok4 : mal4 ) + "  " + r.prueba + "  " + estado( r, t ) + ( seg == null ? "" : " " + t
        .f( "test_in", seg ) ) + archivo );
      if ( !ok ) {
        if ( r.mensaje != null ) {
          out.println( "        " + primera( r.mensaje ) );
        }
        for ( String d : detalle( r, t ) ) {
          out.println( "        " + d );
        }
      }
    }
    int fallan = rs.size() - pasan;
    out.println( fallan > 0 ? t.f( "test_total_failed", rs.size(), pasan, fallan ) : t.f( "test_total", rs.size(),
      pasan ) );
  }

  /** Anotaciones de GitHub Actions: un ::error en el archivo de cada prueba que no pasa. */
  void githubPruebas( List<ResultadoPrueba> rs, Rotulos t ) {
    for ( ResultadoPrueba r : rs ) {
      if ( r.paso() ) {
        continue;
      }
      StringBuilder m = new StringBuilder( t.f( "gh_message", r.prueba, estado( r, t ) ) );
      if ( r.mensaje != null ) {
        m.append( ": " ).append( primera( r.mensaje ) );
      }
      for ( String d : detalle( r, t ) ) {
        m.append( '\n' ).append( d );
      }
      out.println( "::error file=" + prop( r.archivoPrueba == null ? "" : relativa( new File( r.archivoPrueba ) ) )
        + ",title=" + prop( t.f( "gh_title", r.prueba ) ) + "::" + datos( m.toString() ) );
    }
  }

  /** --summary: una tabla Markdown al final del archivo (el $GITHUB_STEP_SUMMARY de Actions). */
  void escribirResumenPruebas( String ruta, List<ResultadoPrueba> rs, Rotulos t ) {
    if ( ruta == null || ruta.trim().isEmpty() ) {
      return;
    }
    StringBuilder b = new StringBuilder( "## " ).append( t.s( "md_title" ) ).append( "\n\n" );
    if ( rs.isEmpty() ) {
      b.append( t.f( "md_none", DefPrueba.EXTENSION ) ).append( '\n' );
    } else {
      int pasan = 0;
      for ( ResultadoPrueba r : rs ) {
        pasan += r.paso() ? 1 : 0;
      }
      b.append( pasan == rs.size() ? t.f( "md_all", rs.size() ) : t.f( "md_some", rs.size() - pasan, rs.size() ) )
        .append( "\n\n" ).append( t.s( "md_head" ) ).append( "\n|---|---|---|---|---|\n" );
      for ( ResultadoPrueba r : rs ) {
        List<String> d = detalle( r, t );
        String det = r.paso() ? "" : md( ( r.mensaje == null ? "" : primera( r.mensaje ) ) + ( d.isEmpty() ? ""
          : ( r.mensaje == null ? "" : "; " ) + String.join( "; ", d ) ) );
        String seg = segundos( r, t );
        b.append( "| " ).append( md( r.prueba ) ).append( " | `" ).append( r.archivoPrueba == null ? "" : relativa(
          new File( r.archivoPrueba ) ).replace( "`", "" ) ).append( "` | " ).append( r.paso() ? estado( r, t ) : "**"
            + estado( r, t ) + "**" ).append( " | " ).append( seg == null ? "-" : seg ).append( " | " ).append( det )
          .append( " |\n" );
      }
    }
    b.append( '\n' );
    try ( Writer w = new OutputStreamWriter( new FileOutputStream( absoluto( ruta ), true ), StandardCharsets.UTF_8 ) ) {
      w.write( b.toString() );
    } catch ( IOException e ) {
      err.println( t.f( "test_no_summary", ruta, e.getMessage() ) );
    }
  }

  // ---------------------------------------------------------------- salidas

  private void texto( List<Revisado> hechos ) {
    String ultimoPerfil = "";
    int pasan = 0;
    for ( Revisado rv : hechos ) {
      String p = rv.perfil == null ? "(no lint profile)" : "Profile: " + rv.perfil + " (" + rv.perfilRuta + ")";
      if ( !p.equals( ultimoPerfil ) ) {
        out.println( p );
        ultimoPerfil = p;
      }
      if ( rv.error != null && rv.res == null ) {
        out.println( "  FAIL  " + rv.ruta + ": " + rv.error );
        continue;
      }
      pasan += rv.pasa ? 1 : 0;
      Resultado r = rv.res;
      out.println( "  " + ( rv.pasa ? "ok  " : "FAIL" ) + "  " + r.score + " " + r.grade + "  " + rv.ruta + "  ("
        + r.counts.get( "error" ) + " errors, " + r.counts.get( "warning" ) + " warnings, " + r.counts.get( "info" )
        + " info" + ( rv.minimo == null ? "" : "; minimum " + rv.minimo ) + ")" );
      if ( rv.error != null ) {
        out.println( "        " + rv.error );
      }
      for ( Hallazgo h : r.findings ) {
        if ( h.severity.almenos( Sev.WARNING ) || !rv.pasa ) {
          out.println( "        " + h.severity.codigo() + "  " + h.rule + "  " + h.message );
        }
      }
    }
    int fallan = hechos.size() - pasan;
    out.println( hechos.size() + " file(s): " + pasan + " passed" + ( fallan > 0 ? ", " + fallan
      + " below the minimum score or not readable" : "" ) + "." );
  }

  /** Anotaciones de GitHub Actions (::error / ::warning) en el archivo de cada hallazgo. */
  private void github( List<Revisado> hechos ) {
    for ( Revisado rv : hechos ) {
      if ( rv.res == null ) {
        out.println( "::error file=" + prop( rv.ruta ) + ",title=pentaho-live lint::" + datos( rv.error ) );
        continue;
      }
      for ( Hallazgo h : rv.res.findings ) {
        if ( h.severity.almenos( Sev.WARNING ) ) {
          out.println( "::" + ( h.severity == Sev.ERROR ? "error" : "warning" ) + " file=" + prop( rv.ruta ) + ",title="
            + prop( h.rule ) + "::" + datos( h.message ) );
        }
      }
      if ( !rv.pasa ) {
        out.println( "::error file=" + prop( rv.ruta ) + ",title=lint score::" + datos( "Score " + rv.res.score
          + " is below the minimum " + rv.minimo + ( rv.perfil == null ? "" : " of the profile " + rv.perfil )
          + ( rv.error == null ? "" : " (" + rv.error + ")" ) ) );
      }
    }
  }

  private static String datos( String s ) {
    return s == null ? "" : s.replace( "%", "%25" ).replace( "\r", "%0D" ).replace( "\n", "%0A" );
  }

  private static String prop( String s ) {
    return datos( s ).replace( ":", "%3A" ).replace( ",", "%2C" );
  }

  private ObjectNode json( List<Revisado> hechos, Integer min, String pedido ) {
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    if ( min != null ) {
      o.put( "min_score", min );
    }
    if ( pedido != null ) {
      o.put( "profile_option", pedido );
    }
    ArrayNode fs = o.putArray( "files" );
    int pasan = 0;
    for ( Revisado rv : hechos ) {
      ObjectNode x = fs.addObject();
      x.put( "file", rv.ruta ).put( "passed", rv.pasa );
      if ( rv.minimo != null ) {
        x.put( "min_score", rv.minimo );
      }
      if ( rv.error != null ) {
        x.put( "error", rv.error );
      }
      if ( rv.res != null ) {
        x.put( "score", rv.res.score ).put( "grade", rv.res.grade );
        x.set( "lint", LintJson.resultado( rv.res ) );
      }
      pasan += rv.pasa ? 1 : 0;
    }
    o.put( "passed", pasan ).put( "failed", hechos.size() - pasan );
    return o;
  }

  /** --summary: una tabla Markdown al final del archivo (el $GITHUB_STEP_SUMMARY de Actions). */
  private void escribirResumen( String ruta, List<Revisado> hechos, Integer min ) {
    if ( ruta == null || ruta.trim().isEmpty() ) {
      return;
    }
    StringBuilder b = new StringBuilder( "## Pentaho Live lint\n\n" );
    if ( hechos.isEmpty() ) {
      b.append( "No .ktr/.kjb to check.\n" );
    } else {
      String p = hechos.get( 0 ).perfil;
      b.append( p == null ? "No lint profile" : "Profile: **" + md( p ) + "**" ).append( min != null ? " · minimum "
        + min : "" ).append( "\n\n| File | Score | Errors | Warnings | Info | Result |\n|---|---|---|---|---|---|\n" );
      for ( Revisado rv : hechos ) {
        Resultado r = rv.res;
        b.append( "| `" ).append( rv.ruta.replace( "`", "" ) ).append( "` | " ).append( r == null ? "-" : r.score + " "
          + r.grade ).append( " | " ).append( r == null ? "-" : r.counts.get( "error" ) ).append( " | " ).append( r
            == null ? "-" : r.counts.get( "warning" ) ).append( " | " ).append( r == null ? "-" : r.counts.get( "info" ) )
          .append( " | " ).append( rv.pasa ? "passed" : "**failed**" + ( rv.minimo != null && r != null ? " (min "
            + rv.minimo + ")" : "" ) + ( rv.error != null ? ": " + md( rv.error ) : "" ) ).append( " |\n" );
      }
    }
    b.append( '\n' );
    try ( Writer w = new OutputStreamWriter( new FileOutputStream( absoluto( ruta ), true ), StandardCharsets.UTF_8 ) ) {
      w.write( b.toString() );
    } catch ( IOException e ) {
      err.println( "pentaho-live lint: could not write the summary to " + ruta + ": " + e.getMessage() );
    }
  }

  private static String md( String s ) {
    return s.replace( "|", "\\|" ).replace( "\n", " " );
  }

  // ---------------------------------------------------------------- ayudas

  private File absoluto( String r ) {
    File f = new File( r );
    return f.isAbsolute() ? f : new File( cwd, r ).getAbsoluteFile();
  }

  private String relativa( File f ) {
    String a = f.getAbsolutePath();
    String c = cwd.getAbsolutePath();
    if ( c.endsWith( File.separator + "." ) ) {
      c = c.substring( 0, c.length() - 2 );
    }
    if ( a.startsWith( c + File.separator ) ) {
      a = a.substring( c.length() + 1 );
    }
    return a.replace( File.separatorChar, '/' );
  }

  private static String valor( List<String> a, int i, String opcion ) throws Uso {
    if ( i >= a.size() ) {
      throw new Uso( opcion + " needs a value" );
    }
    return a.get( i );
  }

  static String bonito( ObjectNode o ) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString( o );
    } catch ( IOException e ) {
      return o.toString();
    }
  }

  static PrintStream utf8( PrintStream p ) {
    try {
      return new PrintStream( p, true, "UTF-8" );
    } catch ( UnsupportedEncodingException e ) {
      return p;
    }
  }
}
