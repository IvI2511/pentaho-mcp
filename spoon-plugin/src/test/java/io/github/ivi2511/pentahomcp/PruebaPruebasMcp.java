package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * Las herramientas MCP de las pruebas (0.7.0) de punta a punta, con la JVM de las pruebas de verdad
 * ({@link PruebasProcesos} + {@link PruebasMain}: el PDI de -Dpruebas.pdi y las clases de test.ps1), sin Spoon:
 * {@code test_create} (snapshot de 03), {@code test_list}, {@code test_run}, un Group by cambiado que
 * {@code test_run_all} pone en rojo con su diferencia, y cancelar antes de que arranque. Nada se escribe en
 * data-integration. Todo bajo {@code <prueba>/pruebas-mcp}.
 */
public class PruebaPruebasMcp {

  private File base;
  private File dir;
  private List<String> pdiAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    base = new File( EntornoKettle.carpeta(), "pruebas-mcp" );
    EntornoKettle.borrar( base.toPath() );
    dir = new File( base, "proyecto" );
    File ej = new File( EntornoKettle.recursos(), "ej" );
    copiar( new File( ej, "03_ventas_por_producto.ktr" ), new File( dir, "03_ventas_por_producto.ktr" ) );
    for ( File f : new File( ej, "datos" ).listFiles() ) {
      copiar( f, new File( new File( dir, "datos" ), f.getName() ) );
    }
    CorrectorProcesos.Opciones op = new CorrectorProcesos.Opciones();
    op.xmxMb = 640;
    op.pdi = EntornoKettle.pdi();
    PruebasProcesos.procesos = op;
    pdiAntes = Arrays.asList( EntornoKettle.pdi().list() );
  }

  public void cerrar() {
    PruebasProcesos.procesos = null;
  }

  private static void copiar( File de, File a ) throws Exception {
    a.getParentFile().mkdirs();
    Files.copy( de.toPath(), a.toPath(), StandardCopyOption.REPLACE_EXISTING );
  }

  private ObjectNode args() {
    return Tools.obj().put( "transformation_file", new File( dir, "03_ventas_por_producto.ktr" ).getAbsolutePath() );
  }

  /** test_create, test_list, test_run en verde; el Group by cambiado en rojo con test_run_all. */
  public void pruebaA_DePuntaAPunta() throws Exception {
    ObjectNode a = args().put( "name", "ventas" );
    a.putArray( "output_steps" ).add( "Totales por producto" );
    a.putObject( "key_columns" ).putArray( "Totales por producto" ).add( "producto" );
    a.putObject( "environment" ).put( "locale", "es_ES" ).put( "timezone", "Europe/Madrid" );
    JsonNode c = McpHttpServer.JSON.valueToTree( PruebasTools.crear( a ) );
    igual( ResultadoPrueba.PASO, c.path( "status" ).asText(), "la prueba nueva pasa: " + c );
    archivo( new File( dir, "tests/ventas.test.json" ), 200, "la prueba" );
    igual( 1, c.path( "created" ).path( "datasets" ).size(), "un dataset" );
    contiene( EntornoKettle.leer( new File( dir, "tests/ventas.test.json" ) ), "\"locale\" : \"es_ES\"",
      "con el entorno pedido" );

    JsonNode l = McpHttpServer.JSON.valueToTree( PruebasTools.listar( args() ) );
    igual( 1, l.path( "count" ).asInt(), "una prueba" );
    JsonNode t = l.path( "tests" ).get( 0 );
    igual( "ventas", t.path( "name" ).asText(), "su nombre" );
    igual( "[\"Leer ventas\"]", t.path( "datasets" ).toString(), "el dataset" );
    igual( ResultadoPrueba.PASO, t.path( "last_result" ).path( "status" ).asText(), "ultimo resultado" );

    JsonNode r = McpHttpServer.JSON.valueToTree( PruebasTools.correrUna( args().put( "test", "ventas" ) ) );
    igual( ResultadoPrueba.PASO, r.path( "status" ).asText(), "test_run en verde: " + r );
    igual( ResultadoPrueba.DEL_ARCHIVO, r.path( "source" ).asText(), "sin Spoon: el archivo" );
    cierto( r.path( "case" ).path( "run" ).path( "log" ).isMissingNode(), "sin log cuando pasa" );

    String ktr = EntornoKettle.leer( new File( dir, "03_ventas_por_producto.ktr" ) );
    EntornoKettle.escribir( new File( dir, "03_ventas_por_producto.ktr" ), ktr.replaceFirst(
      "<subject>importe</subject>\n        <type>SUM</type>", "<subject>importe</subject>\n        <type>MAX</type>" ) );
    JsonNode todas = McpHttpServer.JSON.valueToTree( PruebasTools.correrTodas( args() ) );
    igual( 1, todas.path( "summary" ).path( "failed" ).asInt(), "una en rojo: " + todas );
    igual( "✗ 1", todas.path( "summary" ).path( "indicator" ).asText(), "el indicador" );
    JsonNode res = todas.path( "results" ).get( 0 );
    contiene( res.path( "message" ).asText(), "changed rows", "por que" );
    JsonNode dif = res.path( "case" ).path( "tables" ).get( 0 ).path( "comparison" ).path( "examples" ).path(
      "different" );
    cierto( dif.size() > 0, "la diferencia fila a fila" );
    igual( "importe_total", dif.get( 0 ).path( "column" ).asText(), "en importe_total" );

    igual( new HashSet<String>( pdiAntes ), new HashSet<String>( Arrays.asList( EntornoKettle.pdi().list() ) ),
      "nada nuevo en data-integration" );
  }

  /** Argumentos mal: errores claros para la IA. */
  public void pruebaB_Errores() throws Exception {
    lanza( ToolError.class, () -> PruebasTools.correrUna( args().put( "test", "no_existe" ) ), "prueba que no esta" );
    ObjectNode a = args().put( "name", "mal nombre!" );
    a.putArray( "output_steps" ).add( "Totales por producto" );
    ToolError e = lanza( ToolError.class, () -> PruebasTools.crear( a ), "nombre invalido" );
    contiene( e.getMessage(), "Invalid test name", "lo dice" );
    ObjectNode b = Tools.obj().put( "transformation_file", "relativa.ktr" );
    lanza( ToolError.class, () -> PruebasTools.listar( b ), "ruta relativa" );
  }

  /**
   * {@code pentaho-live test} (P4, la CI de init): la prueba en rojo de pruebaA da código 1, la anotación de GitHub y el
   * resumen Markdown; arreglada, código 0 y el JSON de test_run; sin pruebas, 0; un error de uso, 2.
   */
  public void pruebaD_LineaDeComandos() throws Exception {
    File resumen = new File( base, "resumen-pruebas.md" );
    Object[] r = cli( dir, "test", "--all", "--format", "github", "--summary", resumen.getAbsolutePath() );
    igual( 1, r[ 0 ], "la del Group by cambiado falla: " + r[ 1 ] + r[ 2 ] );
    contiene( (String) r[ 1 ], "::error file=tests/ventas.test.json,title=pentaho-live test ventas::", "anotación" );
    contiene( (String) r[ 1 ], "FAIL  ventas  failed", "en texto" );
    contiene( (String) r[ 1 ], "output \"Totales por producto\":", "qué salida" );
    contiene( (String) r[ 1 ], "1 test(s): 0 passed, 1 failed", "el total" );
    String md = EntornoKettle.leer( resumen );
    contiene( md, "## Pentaho Live tests", "resumen" );
    contiene( md, "| ventas | `tests/ventas.test.json` | **failed** |", "una fila por prueba" );

    String ktr = EntornoKettle.leer( new File( dir, "03_ventas_por_producto.ktr" ) );
    EntornoKettle.escribir( new File( dir, "03_ventas_por_producto.ktr" ), ktr.replaceFirst(
      "<subject>importe</subject>\n        <type>MAX</type>", "<subject>importe</subject>\n        <type>SUM</type>" ) );
    r = cli( base, "test", "proyecto/03_ventas_por_producto.ktr", "--format", "json" );
    igual( 0, r[ 0 ], "arreglada: pasa: " + r[ 1 ] + r[ 2 ] );
    JsonNode j = McpHttpServer.JSON.readTree( (String) r[ 1 ] );
    igual( 1, j.path( "passed" ).asInt(), "json: una pasa" );
    igual( ResultadoPrueba.PASO, j.path( "tests" ).get( 0 ).path( "status" ).asText(), "su estado" );
    igual( "proyecto/tests/ventas.test.json", j.path( "tests" ).get( 0 ).path( "test_file" ).asText(), "relativa" );

    File vacia = new File( base, "sin-pruebas" );
    vacia.mkdirs();
    r = cli( vacia, "test" );
    igual( 0, r[ 0 ], "sin pruebas: 0" );
    contiene( (String) r[ 1 ], "nothing to run", "lo dice" );
    igual( 2, cli( dir, "test", "--format", "xml" )[ 0 ], "uso: --format" );
    igual( 2, cli( dir, "test", "no-existe.test.json" )[ 0 ], "uso: no existe" );
    igual( 2, cli( dir, "test", "03_ventas_por_producto.kjb_no" )[ 0 ], "uso: no existe" );
  }

  private static Object[] cli( File cwd, String... args ) throws Exception {
    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
    java.io.ByteArrayOutputStream e = new java.io.ByteArrayOutputStream();
    ProyectoMain m = new ProyectoMain( new java.io.PrintStream( o, true, "UTF-8" ), new java.io.PrintStream( e, true,
      "UTF-8" ) );
    m.cwd = cwd.getAbsoluteFile();
    m.kettleListo = true;
    int c = m.correr( args );
    return new Object[] { c, o.toString( "UTF-8" ), e.toString( "UTF-8" ) };
  }

  /** Cancelar antes de que arranque la JVM: todo queda cancelado. */
  public void pruebaC_Cancelar() throws Exception {
    List<PruebasProcesos.Tarea> t = new ArrayList<PruebasProcesos.Tarea>();
    t.add( PruebasProcesos.Tarea.correr( new File( dir, "tests/ventas.test.json" ), null ) );
    PruebasProcesos.Corrida c = PruebasProcesos.iniciar( t, null );
    c.cancelar();
    cierto( c.esperar( 120000 ), "termina" );
    igual( ResultadoPrueba.CANCELADA, c.resultados().get( 0 ).estado, "cancelada" );
  }
}
