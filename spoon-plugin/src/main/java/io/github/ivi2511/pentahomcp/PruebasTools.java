package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * Herramientas MCP de las pruebas de transformaciones (0.7.0, P1): {@code test_create} (modo snapshot),
 * {@code test_run}, {@code test_run_all} y {@code test_list}. Las que corren lanzan la JVM de las pruebas
 * ({@link PruebasProcesos}) y esperan; nada corre en Spoon. Con la transformacion abierta, por defecto se prueba lo que
 * se ve (una copia de su getXML(), cambios sin guardar incluidos), como la autocorreccion del corrector; el archivo
 * nunca se toca. La ventana «Pruebas…» del menu IDC usa los mismos metodos ({@link #tareas}, {@link #crearTarea}).
 *
 * @dueño P1 (0.7.0)
 */
final class PruebasTools {

  static final String AUTO = "auto";
  static final int MAX_ESPERA_S = 1800;
  /** Variables que nunca se copian a una prueba (contraseñas, tokens). */
  private static final Pattern SECRETA = Pattern.compile( "(?i).*(pass|pwd|clave|secret|token|key).*" );

  private PruebasTools() {
  }

  static void register( McpHttpServer s ) {
    s.add( Tools.tool( "test_list",
      "Lists the regression tests (golden-dataset tests) of a transformation: the *.test.json files in a tests/ or "
        + "pruebas/ folder next to it (or in its two parent folders) whose 'transformation' points to it, or every "
        + "test of a 'folder'. For each test: its file, the steps replaced by datasets, the captured output steps "
        + "and tables, its tweaks and the last result in this Spoon session. Read-only.",
      Tools.schema().document()
        .str( "transformation_file", "Absolute path of a .ktr on disk, instead of an open document", false )
        .str( "folder", "Absolute path of a tests folder: list all its tests, whatever transformation they test",
          false ).build(),
      a -> listar( a ) ) );

    s.add( Tools.tool( "test_create",
      "Creates a regression test by SNAPSHOT: runs the transformation (the open document by default, unsaved "
        + "changes included; steps that write files or tables are skipped, so nothing is written) in a separate JVM, "
        + "freezes the rows that come out of the input steps as datasets (CSV in tests/datasets/<name>/) and the rows "
        + "of the output_steps as golden files (tests/golden/<name>/), and writes tests/<name>.test.json. When the "
        + "test runs, each input step is replaced by its dataset (same field types and formats) and each output is "
        + "compared row by row with its golden file: missing, extra and changed rows. Columns that change between "
        + "runs (system dates, random values) are detected and ignored. Variables the transformation uses are "
        + "copied from Spoon into the test (never ones that look like passwords). Freeze what is correct today: a "
        + "later change that alters those outputs makes the test fail. The transformation must be saved to a .ktr "
        + "(the test points to it). Returns the files written, the warnings and the result of running the new test "
        + "once.",
      Tools.schema().document()
        .str( "transformation_file", "Absolute path of the .ktr, instead of an open document", false )
        .str( "name", "Test name: letters, digits, - and _ (the file is <name>.test.json)", true )
        .strList( "output_steps", "Steps whose output rows are frozen as golden data and compared when the test "
          + "runs (usually the last step of each branch, or a step whose result matters)", true )
        .strList( "input_steps", "Steps whose output is replaced by a frozen dataset. Default: every source step "
          + "(no incoming hop) that leads to the outputs, except Data grid and Generate rows", false )
        .raw( "key_columns", mapaDeListas( "Output step -> columns that identify a row (compare by key; default: "
          + "compare as a multiset, row order ignored)" ), false )
        .raw( "ignore_columns", mapaDeListas( "Output step -> columns that are not compared" ), false )
        .raw( "tweaks", ajustes(), false )
        .str( "folder", "Absolute path of the tests folder. Default: tests/ next to the .ktr", false )
        .str( "description", "What the test checks", false )
        .bool( "replace", "Overwrite an existing test with the same name (default false)", false )
        .raw( "environment", entorno(), false )
        .raw( "test_databases", basesDePrueba(), false )
        .raw( "tables", tablas(), false )
        .raw( "db_scripts", mapaDeListas( "Connection -> absolute paths of SQL scripts with this test's source data, "
          + "run in the test databases after their own scripts" ), false )
        .integer( "timeout_s", "How long to wait for the snapshot (default 600)", false ).build(),
      a -> crear( a ) ) );

    s.add( Tools.tool( "test_run",
      "Runs one regression test in a separate JVM and returns its result: status (passed, failed, error), why, and "
        + "for each captured output the row diff against its golden file (missing rows, extra rows, changed values "
        + "with key, column, expected and actual; up to max_examples of each). If the transformation fails, the "
        + "error and the tail of its log. By default it tests what is open in Spoon (unsaved changes included); "
        + "source=file tests the saved .ktr.",
      Tools.schema().document()
        .str( "test", "Test name (looked up among the tests of the transformation or the folder) or absolute path "
          + "of a .test.json", true )
        .str( "transformation_file", "Absolute path of a .ktr whose tests are searched", false )
        .str( "folder", "Absolute path of the tests folder where the name is searched", false )
        .strEnum( "source", "auto (default): the open document if the transformation is open in Spoon, else the "
          + "file; open_document; file", false, AUTO, ResultadoPrueba.DEL_DOCUMENTO, ResultadoPrueba.DEL_ARCHIVO )
        .integer( "timeout_s", "How long to wait (default 300)", false ).build(),
      a -> correrUna( a ) ) );

    s.add( Tools.tool( "test_run_all",
      "Runs every regression test of a transformation (or of a tests folder) in one separate JVM and returns a "
        + "summary (passed, failed, errors) and each result; the failing ones with their row diffs.",
      Tools.schema().document()
        .str( "transformation_file", "Absolute path of a .ktr: run its tests", false )
        .str( "folder", "Absolute path of a tests folder: run all its tests", false )
        .strEnum( "source", "auto (default), open_document or file (see test_run)", false, AUTO,
          ResultadoPrueba.DEL_DOCUMENTO, ResultadoPrueba.DEL_ARCHIVO )
        .integer( "timeout_s", "How long to wait (default 900)", false ).build(),
      a -> correrTodas( a ) ) );
  }

  // ------------------------------------------------------------------ esquemas

  private static ObjectNode mapaDeListas( String desc ) {
    ObjectNode o = Tools.obj().put( "type", "object" ).put( "description", desc );
    o.putObject( "additionalProperties" ).put( "type", "array" ).putObject( "items" ).put( "type", "string" );
    return o;
  }

  private static ObjectNode ajustes() {
    ObjectNode o = Tools.obj().put( "type", "array" ).put( "description", "Changes applied to the copy that runs "
      + "(never to the file). Default: skip_step for every step that writes files or tables. skip_step: the step "
      + "does nothing and its rows pass through (a Table output that does not write); remove_step: the step and its "
      + "hops are removed; set_variable / set_parameter: a value for the run" );
    ObjectNode it = o.putObject( "items" ).put( "type", "object" );
    ObjectNode p = it.putObject( "properties" );
    p.putObject( "type" ).put( "type", "string" ).put( "description", "The tweak" ).putArray( "enum" ).add(
      DefPrueba.Ajuste.SALTAR ).add( DefPrueba.Ajuste.QUITAR ).add( DefPrueba.Ajuste.VARIABLE ).add(
      DefPrueba.Ajuste.PARAMETRO );
    p.putObject( "step" ).put( "type", "string" ).put( "description", "Step (skip_step, remove_step)" );
    p.putObject( "name" ).put( "type", "string" ).put( "description", "Variable or parameter name" );
    p.putObject( "value" ).put( "type", "string" ).put( "description", "Its value" );
    p.putObject( "note" ).put( "type", "string" ).put( "description", "Why (optional)" );
    it.putArray( "required" ).add( "type" );
    return o;
  }

  private static ObjectNode entorno() {
    ObjectNode o = Tools.obj().put( "type", "object" ).put( "description", "The test JVM: locale (default: Spoon's, "
      + "e.g. es_ES), timezone (default: Spoon's), encoding (UTF-8), timeout_s per run (120) and max_rows per output "
      + "(1000000)" );
    ObjectNode p = o.putObject( "properties" );
    p.putObject( "locale" ).put( "type", "string" ).put( "description", "es_ES, en_US..." );
    p.putObject( "timezone" ).put( "type", "string" ).put( "description", "Europe/Madrid, UTC..." );
    p.putObject( "encoding" ).put( "type", "string" ).put( "description", "file.encoding of the test JVM" );
    p.putObject( "timeout_s" ).put( "type", "integer" ).put( "description", "Seconds per run" );
    p.putObject( "max_rows" ).put( "type", "integer" ).put( "description", "Rows per output and dataset" );
    return o;
  }

  private static ObjectNode basesDePrueba() {
    ObjectNode o = Tools.obj().put( "type", "array" ).put( "description", "Disposable test databases (same format "
      + "as the grader's packs): before the test runs each one is dropped, created again and filled with its "
      + "scripts, and the transformation's connections are pointed there (by name, database or type and host). "
      + "The name must end in _test, _prueba, _correccion or _grading. Never a password here (password_variable)" );
    ObjectNode it = o.putObject( "items" ).put( "type", "object" );
    ObjectNode p = it.putObject( "properties" );
    p.putObject( "connection" ).put( "type", "string" ).put( "description", "Connection name in the transformation" );
    p.putObject( "type" ).put( "type", "string" ).put( "description", "H2, MYSQL, MARIADB or POSTGRESQL" );
    p.putObject( "host" ).put( "type", "string" ).put( "description", "Server (empty for an H2 file database)" );
    p.putObject( "port" ).put( "type", "string" ).put( "description", "Port" );
    p.putObject( "database" ).put( "type", "string" ).put( "description", "The test database (dropped and created "
      + "again), e.g. ventas_test; for H2 without a server, a file path" );
    p.putObject( "original_database" ).put( "type", "string" ).put( "description", "The real database: never "
      + "touched" );
    p.putObject( "user" ).put( "type", "string" ).put( "description", "User" );
    p.putObject( "password_variable" ).put( "type", "string" ).put( "description", "Kettle or environment variable "
      + "with the password" );
    p.putObject( "scripts" ).put( "type", "array" ).put( "description", "SQL scripts (paths relative to the tests "
      + "folder) run in order in the fresh database" ).putObject( "items" ).put( "type", "string" );
    it.putArray( "required" ).add( "connection" ).add( "type" ).add( "database" );
    return o;
  }

  private static ObjectNode tablas() {
    ObjectNode o = Tools.obj().put( "type", "array" ).put( "description", "Tables read from the test databases "
      + "after the run and compared with their golden CSV (needs test_databases; the whole transformation runs)" );
    ObjectNode it = o.putObject( "items" ).put( "type", "object" );
    ObjectNode p = it.putObject( "properties" );
    p.putObject( "name" ).put( "type", "string" ).put( "description", "Table" );
    p.putObject( "connection" ).put( "type", "string" ).put( "description", "Its connection" );
    p.putObject( "key_columns" ).put( "type", "array" ).put( "description", "Columns that identify a row" )
      .putObject( "items" ).put( "type", "string" );
    p.putObject( "ignore_columns" ).put( "type", "array" ).put( "description", "Columns not compared" )
      .putObject( "items" ).put( "type", "string" );
    it.putArray( "required" ).add( "name" );
    return o;
  }

  // ------------------------------------------------------------------ que se prueba

  /** La transformacion de una llamada: su .ktr, si esta abierta (id del documento) y la carpeta pedida. */
  static final class Objetivo {
    File ktr;
    String docId;
    String nombre;
    File carpeta;
  }

  /**
   * document, transformation_file o folder; sin nada, el documento activo. Lo abierto se busca en la UI (si Spoon no
   * esta, se sigue con el archivo).
   */
  static Objetivo objetivo( final JsonNode a ) throws Exception {
    Objetivo o = new Objetivo();
    String carpeta = Tools.optStr( a, "folder", null );
    if ( carpeta != null ) {
      o.carpeta = absoluta( carpeta, "folder" );
      if ( !o.carpeta.isDirectory() ) {
        throw new ToolError( "The folder " + o.carpeta + " does not exist" );
      }
    }
    String archivo = Tools.optStr( a, "transformation_file", null );
    String doc = Tools.optStr( a, "document", null );
    if ( archivo != null ) {
      o.ktr = absoluta( archivo, "transformation_file" );
      o.nombre = o.ktr.getName();
      final String canon = PruebasArchivos.canonica( o.ktr );
      try {
        o.docId = Docs.ui( () -> {
          for ( AbstractMeta m : Docs.abiertos() ) {
            if ( m instanceof TransMeta && m.getFilename() != null && canon.equalsIgnoreCase( PruebasArchivos.canonica(
              archivoDe( m ) ) ) ) {
              return Docs.id( m );
            }
          }
          return null;
        } );
      } catch ( Exception ignored ) {
        // sin Spoon: el archivo
      }
      return o;
    }
    if ( doc != null || o.carpeta == null ) {
      Object[] r = Docs.ui( () -> {
        AbstractMeta m = Docs.resolverSinActivar( a );
        if ( !( m instanceof TransMeta ) ) {
          throw new ToolError( "Tests are for transformations: '" + Docs.nombre( m ) + "' is a job" );
        }
        return new Object[] { Docs.id( m ), m.getFilename() == null ? null : archivoDe( m ), Docs.nombre( m ) };
      } );
      o.docId = (String) r[ 0 ];
      o.ktr = (File) r[ 1 ];
      o.nombre = (String) r[ 2 ];
    }
    return o;
  }

  /** El archivo de un documento ("file:///C:/..." o una ruta). */
  static File archivoDe( AbstractMeta m ) {
    String f = m.getFilename();
    if ( f == null || f.trim().isEmpty() ) {
      return null;
    }
    return new File( f.replaceFirst( "^(?i)file:/+(?=[A-Za-z]:)", "" ).replaceFirst( "^(?i)file:", "" ) )
      .getAbsoluteFile();
  }

  private static File absoluta( String ruta, String arg ) throws ToolError {
    File f = new File( ruta.trim() );
    if ( !f.isAbsolute() ) {
      throw new ToolError( "'" + arg + "' must be an absolute path: " + ruta );
    }
    return f.getAbsoluteFile();
  }

  /** Si hay que usar la copia del documento abierto. */
  static boolean usarDocumento( JsonNode a, Objetivo o ) throws ToolError {
    String s = Tools.optStr( a, "source", AUTO );
    if ( ResultadoPrueba.DEL_DOCUMENTO.equals( s ) ) {
      if ( o.docId == null ) {
        throw new ToolError( "source=open_document but the transformation is not open in Spoon" );
      }
      return true;
    }
    return !ResultadoPrueba.DEL_ARCHIVO.equals( s ) && o.docId != null;
  }

  /** La copia del documento abierto en una carpeta temporal nueva (quien la usa la borra al terminar). */
  static File copia( String docId, List<File> temporales ) throws Exception {
    File tmp = Files.createTempDirectory( "pentaho-live-tests-doc-" ).toFile();
    temporales.add( tmp );
    return CorrectorTools.copiaDeDocumento( Tools.obj().put( "document", docId ), tmp ).ktr;
  }

  /** Las tareas de correr: una prueba ({@code test}) o todas las de la transformacion o la carpeta. */
  static List<PruebasProcesos.Tarea> tareas( JsonNode a, boolean todas, List<File> temporales ) throws Exception {
    Objetivo o = objetivo( a );
    List<File> pruebas = new ArrayList<File>();
    if ( todas ) {
      if ( o.ktr != null ) {
        pruebas.addAll( PruebasArchivos.deTransformacion( o.ktr ) );
      }
      if ( o.carpeta != null ) {
        for ( File f : PruebasArchivos.deCarpeta( o.carpeta ) ) {
          if ( !pruebas.contains( f.getAbsoluteFile() ) ) {
            pruebas.add( f.getAbsoluteFile() );
          }
        }
      }
      if ( pruebas.isEmpty() ) {
        throw new ToolError( "No tests found" + ( o.ktr == null ? "" : " for " + o.ktr.getName() + " (looked in "
          + "tests/ and pruebas/ next to it and in its two parent folders)" ) + ". Create one with test_create" );
      }
    } else {
      String ref = Tools.str( a, "test" );
      File f = PruebasArchivos.buscar( ref, o.ktr, o.carpeta );
      if ( f == null ) {
        throw new ToolError( "Test '" + ref + "' not found" + ( o.ktr == null ? "" : " among the tests of "
          + o.ktr.getName() ) + ". test_list shows them" );
      }
      pruebas.add( f );
    }
    boolean doc = usarDocumento( a, o );
    File copia = null;
    String objetivo = o.ktr == null ? null : PruebasArchivos.canonica( o.ktr );
    List<PruebasProcesos.Tarea> l = new ArrayList<PruebasProcesos.Tarea>();
    for ( File f : pruebas ) {
      File ktr = null;
      // la copia del documento solo para las pruebas de esa transformacion
      if ( doc && objetivo != null && objetivo.equalsIgnoreCase( String.valueOf( PruebasArchivos.transformacionDe(
        f ) ) ) ) {
        if ( copia == null ) {
          copia = copia( o.docId, temporales );
        }
        ktr = copia;
      }
      l.add( PruebasProcesos.Tarea.correr( f, ktr ) );
    }
    return l;
  }

  // ------------------------------------------------------------------ test_list

  static Object listar( JsonNode a ) throws Exception {
    Objetivo o = objetivo( a );
    ObjectNode r = Tools.obj();
    List<File> pruebas = new ArrayList<File>();
    ArrayNode carpetas = r.putArray( "tests_folders" );
    if ( o.ktr != null ) {
      r.put( "transformation", o.ktr.getPath() );
      r.put( "default_folder", PruebasArchivos.carpetaPorDefecto( o.ktr ).getPath() );
      for ( File c : PruebasArchivos.carpetas( o.ktr ) ) {
        carpetas.add( c.getPath() );
      }
      pruebas.addAll( PruebasArchivos.deTransformacion( o.ktr ) );
    } else if ( o.nombre != null ) {
      r.put( "transformation", o.nombre );
      r.put( "note", "The transformation is not saved: tests need a .ktr file" );
    }
    if ( o.carpeta != null ) {
      carpetas.add( o.carpeta.getPath() );
      for ( File f : PruebasArchivos.deCarpeta( o.carpeta ) ) {
        if ( !pruebas.contains( f.getAbsoluteFile() ) ) {
          pruebas.add( f.getAbsoluteFile() );
        }
      }
    }
    ArrayNode l = r.putArray( "tests" );
    for ( File f : pruebas ) {
      l.add( describir( f ) );
    }
    r.put( "count", pruebas.size() );
    return r;
  }

  /** Una prueba para test_list y la ventana. */
  static ObjectNode describir( File f ) {
    ObjectNode x = Tools.obj();
    x.put( "name", PruebasMotor.nombreDeArchivo( f ) );
    x.put( "file", f.getPath() );
    try {
      DefPrueba d = PruebasMotor.cargar( f );
      x.put( "valid", true );
      if ( d.descripcion != null ) {
        x.put( "description", d.descripcion );
      }
      x.put( "transformation", PruebasMotor.resolver( f.getAbsoluteFile().getParentFile(), d.transformacion )
        .getPath() );
      ArrayNode ds = x.putArray( "datasets" );
      for ( DefPrueba.Dataset s : d.datasets ) {
        ds.add( s.step );
      }
      ArrayNode out = x.putArray( "outputs" );
      for ( DefPrueba.Salida s : d.salidas ) {
        out.add( s.step );
      }
      ArrayNode tb = x.putArray( "tables" );
      for ( TablaSalida t : d.tablas ) {
        tb.add( t.nombre );
      }
      ArrayNode aj = x.putArray( "tweaks" );
      for ( DefPrueba.Ajuste j : d.ajustes ) {
        aj.add( j.tipo + " " + ( j.deStep() ? j.step : j.nombre ) );
      }
      if ( !d.bases.isEmpty() ) {
        ArrayNode b = x.putArray( "test_databases" );
        for ( BaseDePrueba bd : d.bases ) {
          b.add( bd.conexion + " -> " + bd.base + " (" + bd.tipoNormal() + ")" );
        }
      }
    } catch ( ToolError e ) {
      x.put( "valid", false );
      x.put( "problem", e.getMessage() );
    }
    ResultadoPrueba u = PruebasArchivos.ultimo( f );
    if ( u != null ) {
      ObjectNode lr = x.putObject( "last_result" );
      lr.put( "status", u.estado );
      if ( u.mensaje != null ) {
        lr.put( "message", u.mensaje );
      }
      if ( u.terminadaEl != null ) {
        lr.put( "finished_at", u.terminadaEl );
      }
    }
    return x;
  }

  // ------------------------------------------------------------------ test_run / test_run_all

  static Object correrUna( JsonNode a ) throws Exception {
    List<File> temporales = new ArrayList<File>();
    List<PruebasProcesos.Tarea> t;
    try {
      t = tareas( a, false, temporales );
    } catch ( Exception e ) {
      borrar( temporales );
      throw e;
    }
    List<ResultadoPrueba> rs = PruebasProcesos.correrYEsperar( t, espera( a, 300 ), temporales );
    return compacto( rs.get( 0 ), true );
  }

  static Object correrTodas( JsonNode a ) throws Exception {
    List<File> temporales = new ArrayList<File>();
    List<PruebasProcesos.Tarea> t;
    try {
      t = tareas( a, true, temporales );
    } catch ( Exception e ) {
      borrar( temporales );
      throw e;
    }
    {
      List<ResultadoPrueba> rs = PruebasProcesos.correrYEsperar( t, espera( a, 900 ), temporales );
      ObjectNode o = Tools.obj();
      int[] n = new int[ 4 ];
      ArrayNode l = Tools.arr();
      for ( ResultadoPrueba r : rs ) {
        n[ ResultadoPrueba.PASO.equals( r.estado ) ? 0 : ResultadoPrueba.FALLO.equals( r.estado ) ? 1
          : ResultadoPrueba.ERROR.equals( r.estado ) ? 2 : 3 ]++;
        l.add( compacto( r, !r.paso() ) );
      }
      ObjectNode s = o.putObject( "summary" );
      s.put( "total", rs.size() ).put( "passed", n[ 0 ] ).put( "failed", n[ 1 ] ).put( "errors", n[ 2 ] );
      if ( n[ 3 ] > 0 ) {
        s.put( "cancelled", n[ 3 ] );
      }
      s.put( "indicator", indicador( n[ 0 ], rs.size() ) );
      o.set( "results", l );
      return o;
    }
  }

  /** "✓ 12/12" o "✗ 1" (lo que muestra el boton IDC). */
  static String indicador( int pasaron, int total ) {
    return pasaron == total ? "\u2713 " + pasaron + "/" + total : "\u2717 " + ( total - pasaron );
  }

  private static long espera( JsonNode a, int def ) throws ToolError {
    int s = Tools.optInt( a, "timeout_s", def );
    return Math.max( 10, Math.min( MAX_ESPERA_S, s ) ) * 1000L;
  }

  /**
   * El resultado para la IA: sin las metricas por step y, si paso o si no se pide, sin el log ni los ejemplos.
   */
  static ObjectNode compacto( ResultadoPrueba r, boolean detalle ) {
    ObjectNode o = CorrectorJson.mapper().valueToTree( r );
    JsonNode caso = o.get( "case" );
    if ( caso instanceof ObjectNode ) {
      ObjectNode run = (ObjectNode) caso.get( "run" );
      if ( run != null ) {
        run.remove( "metrics" );
        run.remove( "detail_key" );
        run.remove( "detail_args" );
        if ( !detalle || ResultadoPrueba.PASO.equals( r.estado ) ) {
          run.remove( "log" );
        }
      }
      ( (ObjectNode) caso ).remove( "comparison" ); // la suma de tables[]: repetida
      if ( !detalle || ResultadoPrueba.PASO.equals( r.estado ) ) {
        for ( JsonNode t : caso.path( "tables" ) ) {
          JsonNode c = t.get( "comparison" );
          if ( c instanceof ObjectNode ) {
            ( (ObjectNode) c ).remove( "examples" );
          }
        }
      }
      for ( Iterator<JsonNode> it = caso.path( "warnings" ).iterator(); it.hasNext(); ) {
        JsonNode w = it.next();
        if ( w instanceof ObjectNode ) {
          ( (ObjectNode) w ).remove( "detail_key" );
          ( (ObjectNode) w ).remove( "detail_args" );
        }
      }
    }
    return o;
  }

  // ------------------------------------------------------------------ test_create

  static Object crear( JsonNode a ) throws Exception {
    List<File> temporales = new ArrayList<File>();
    PruebasProcesos.Tarea t;
    try {
      t = crearTarea( a, temporales );
    } catch ( Exception e ) {
      borrar( temporales );
      throw e;
    }
    List<ResultadoPrueba> rs = PruebasProcesos.correrYEsperar( java.util.Collections.singletonList( t ), espera( a,
      600 ), temporales );
    ResultadoPrueba r = rs.get( 0 );
    if ( ResultadoPrueba.ERROR.equals( r.estado ) && r.creada == null ) {
      throw new ToolError( "The test was not created: " + r.mensaje );
    }
    ObjectNode o = compacto( r, true );
    o.put( "test_file", t.prueba.getPath() );
    return o;
  }

  /** La tarea de crear una prueba con los argumentos de test_create (la ventana arma los mismos). */
  static PruebasProcesos.Tarea crearTarea( JsonNode a, List<File> temporales ) throws Exception {
    ObjectNode sinCarpeta = a.isObject() ? ( (ObjectNode) a ).deepCopy() : Tools.obj();
    sinCarpeta.remove( "folder" ); // la carpeta de una prueba nueva puede no existir todavia
    Objetivo o = objetivo( sinCarpeta );
    if ( o.ktr == null ) {
      throw new ToolError( "Save the transformation to a .ktr first: the test points to its file" );
    }
    PruebasMain.Creacion c = new PruebasMain.Creacion();
    c.nombre = Tools.str( a, "name" ).trim();
    if ( !DefPrueba.nombreValido( c.nombre ) ) {
      throw new ToolError( "Invalid test name '" + c.nombre + "': letters, digits, - and _ (up to 80)" );
    }
    c.descripcion = Tools.optStr( a, "description", null );
    c.original = o.ktr.getAbsolutePath();
    File carpeta = o.carpeta != null ? o.carpeta : PruebasArchivos.carpetaPorDefecto( o.ktr );
    String f = Tools.optStr( a, "folder", null );
    if ( f != null ) {
      carpeta = absoluta( f, "folder" );
    }
    c.carpeta = carpeta.getAbsolutePath();
    c.salidas.addAll( lista( a, "output_steps" ) );
    if ( c.salidas.isEmpty() ) {
      throw new ToolError( "Missing argument 'output_steps': the steps whose rows are frozen as golden data" );
    }
    if ( a.has( "input_steps" ) && !a.get( "input_steps" ).isNull() ) {
      c.entradas = lista( a, "input_steps" );
    }
    c.claves.putAll( mapa( a, "key_columns" ) );
    c.ignorar.putAll( mapa( a, "ignore_columns" ) );
    if ( a.has( "tweaks" ) && a.get( "tweaks" ).isArray() ) {
      c.ajustes = new ArrayList<DefPrueba.Ajuste>();
      for ( JsonNode x : a.get( "tweaks" ) ) {
        DefPrueba.Ajuste j = CorrectorTools.pojo( x, DefPrueba.Ajuste.class, "tweaks" );
        if ( !DefPrueba.Ajuste.TIPOS.contains( j.tipo ) ) {
          throw new ToolError( "Unknown tweak type '" + j.tipo + "': " + DefPrueba.Ajuste.TIPOS );
        }
        c.ajustes.add( j );
      }
    }
    c.reemplazar = Tools.optBool( a, "replace", false );
    DefPrueba.Entorno e = new DefPrueba.Entorno();
    e.locale = Locale.getDefault().toString();
    e.zonaHoraria = TimeZone.getDefault().getID();
    if ( a.has( "environment" ) && a.get( "environment" ).isObject() ) {
      DefPrueba.Entorno x = CorrectorTools.pojo( a.get( "environment" ), DefPrueba.Entorno.class, "environment" );
      JsonNode en = a.get( "environment" );
      if ( en.has( "locale" ) ) {
        e.locale = x.locale;
      }
      if ( en.has( "timezone" ) ) {
        e.zonaHoraria = x.zonaHoraria;
      }
      if ( en.has( "encoding" ) ) {
        e.encoding = x.encoding;
      }
      if ( en.has( "timeout_s" ) ) {
        e.timeoutSeg = x.timeoutSeg;
      }
      if ( en.has( "max_rows" ) ) {
        e.maxFilas = x.maxFilas;
      }
    }
    c.entorno = e;
    if ( a.has( "test_databases" ) && a.get( "test_databases" ).isArray() ) {
      for ( JsonNode x : a.get( "test_databases" ) ) {
        BaseDePrueba b = CorrectorTools.pojo( x, BaseDePrueba.class, "test_databases" ).completar();
        List<String> rel = new ArrayList<String>();
        for ( String sc : b.scripts ) {
          rel.add( relativaACarpeta( carpeta, sc ) );
        }
        b.scripts = rel;
        c.bases.add( b );
      }
    }
    if ( a.has( "tables" ) && a.get( "tables" ).isArray() ) {
      for ( JsonNode x : a.get( "tables" ) ) {
        c.tablas.add( CorrectorTools.pojo( x, TablaSalida.class, "tables" ).completar() );
      }
    }
    for ( Map.Entry<String, List<String>> m : mapa( a, "db_scripts" ).entrySet() ) {
      List<String> rel = new ArrayList<String>();
      for ( String sc : m.getValue() ) {
        rel.add( relativaACarpeta( carpeta, sc ) );
      }
      c.scriptsBase.put( m.getKey(), rel );
    }

    File ktr = null;
    boolean doc = usarDocumento( a, o );
    if ( doc ) {
      final String id = o.docId;
      Object[] info = Docs.ui( () -> {
        TransMeta tm = (TransMeta) Docs.resolverSinActivar( Tools.obj().put( "document", id ) );
        return new Object[] { tm.hasChanged(), variables( tm ) };
      } );
      if ( Boolean.TRUE.equals( info[ 0 ] ) ) {
        c.avisos.add( "The transformation has unsaved changes: the frozen data comes from what is open in Spoon, "
          + "but the test runs the saved .ktr unless it is run with the open document. Save it." );
      }
      @SuppressWarnings( "unchecked" )
      Map<String, String> vars = (Map<String, String>) info[ 1 ];
      c.variables.putAll( vars );
      ktr = copia( id, temporales );
    } else {
      try {
        c.variables.putAll( variables( new TransMeta( o.ktr.getAbsolutePath() ) ) );
      } catch ( Exception ignored ) {
        // sin variables: la hija dira si no carga
      }
    }
    return PruebasProcesos.Tarea.crear( c, ktr );
  }

  /** Las variables que usa la transformacion y tienen valor en Spoon (sin las internas, parametros ni secretas). */
  static Map<String, String> variables( TransMeta tm ) {
    Map<String, String> m = new LinkedHashMap<String, String>();
    List<String> parametros = new ArrayList<String>();
    for ( String p : tm.listParameters() ) {
      parametros.add( p );
    }
    for ( String v : tm.getUsedVariables() ) {
      if ( v == null || v.startsWith( "Internal." ) || parametros.contains( v ) || SECRETA.matcher( v ).matches() ) {
        continue;
      }
      String valor = tm.getVariable( v );
      if ( valor != null ) {
        m.put( v, valor );
      }
    }
    return m;
  }

  /** Un script del pedido (absoluto) como ruta relativa a la carpeta de las pruebas (las pruebas son portables). */
  private static String relativaACarpeta( File carpeta, String ruta ) {
    File f = new File( ruta );
    return f.isAbsolute() ? PruebasMotor.relativa( carpeta, f ) : ruta;
  }

  private static List<String> lista( JsonNode a, String k ) throws ToolError {
    return CorrectorTools.lista( a, k );
  }

  private static Map<String, List<String>> mapa( JsonNode a, String k ) throws ToolError {
    Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
    JsonNode n = a.get( k );
    if ( n == null || n.isNull() ) {
      return m;
    }
    if ( !n.isObject() ) {
      throw new ToolError( "'" + k + "' must be an object: step -> list of columns" );
    }
    for ( Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      List<String> l = new ArrayList<String>();
      if ( e.getValue().isArray() ) {
        for ( JsonNode x : e.getValue() ) {
          l.add( x.asText() );
        }
      } else if ( !e.getValue().isNull() ) {
        l.add( e.getValue().asText() );
      }
      m.put( e.getKey(), l );
    }
    return m;
  }

  static void borrar( List<File> l ) {
    for ( File f : l ) {
      PruebasProcesos.borrar( f );
    }
  }
}
