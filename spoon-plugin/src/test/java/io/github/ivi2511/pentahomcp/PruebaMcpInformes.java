package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.distinto;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.navegador.Chromium;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * La parte MCP de los informes (WP10, diseno §1.5): los esquemas de las 61 tools son JSON Schema
 * validos y las tres nuevas tienen los argumentos del diseno; generate_report no cambio; los
 * argumentos se validan antes de tocar Spoon; get_report_data devuelve el modelo en ingles y sin
 * binarios; y build_report sobre una captura de archivo (sin Spoon) escribe todos los formatos que
 * esta maquina puede dar.
 *
 * @dueño WP10
 */
public class PruebaMcpInformes {

  /**
   * 54 tools de 0.4.0 + build_report, get_report_data, show_report y set_error_handling (0.5.0) + lint_etl,
   * apply_lint_fixes y suppress_lint (linter).
   */
  private static final int TOOLS = 61;

  private McpHttpServer server;
  private int puerto;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    try ( ServerSocket ss = new ServerSocket( 0 ) ) {
      puerto = ss.getLocalPort();
    }
    // Las mismas herramientas que registra McpLifecycleListener.onStart (sin recursos: el catalogo
    // solo esta en el jar de build.ps1).
    server = new McpHttpServer( puerto, McpLifecycleListener.VERSION, McpLifecycleListener.INSTRUCCIONES );
    DocumentTools.register( server, McpLifecycleListener.VERSION, puerto );
    TransTools.register( server );
    JobTools.register( server );
    RunTools.register( server );
    DbTools.register( server );
    ReportTools.register( server );
    LintTools.register( server );
    server.start();
  }

  public void cerrar() {
    if ( server != null ) {
      server.stop();
    }
  }

  // ---------------------------------------------------------------- esquemas (por HTTP, como un cliente)

  public void pruebaEsquemas() throws Exception {
    JsonNode init = rpc( "initialize", McpHttpServer.JSON.createObjectNode().put( "protocolVersion",
      "2025-06-18" ) );
    String instr = init.path( "result" ).path( "instructions" ).asText();
    contiene( instr, "build_report", "las instrucciones nombran build_report" );
    contiene( instr, "get_report_data", "las instrucciones nombran get_report_data" );

    JsonNode tools = rpc( "tools/list", McpHttpServer.JSON.createObjectNode() ).path( "result" ).path( "tools" );
    cierto( tools.isArray(), "tools/list devuelve un array" );
    igual( TOOLS, tools.size(), "cantidad de tools" );
    Map<String, JsonNode> porNombre = new LinkedHashMap<>();
    for ( JsonNode t : tools ) {
      String n = t.path( "name" ).asText();
      cierto( n.matches( "[a-z][a-z0-9_]*" ), "nombre en snake_case: " + n );
      cierto( t.path( "description" ).asText().trim().length() > 20, "descripcion de " + n );
      List<String> errores = new ArrayList<>();
      validarEsquema( t.get( "inputSchema" ), n, true, errores );
      cierto( errores.isEmpty(), "esquema de " + n + ": " + errores );
      falso( porNombre.containsKey( n ), "tool repetida: " + n );
      porNombre.put( n, t );
    }

    // generate_report y canvas_screenshot no cambian en 0.5.0
    igual( set( "document", "path", "include_fields", "include_validation", "language" ), props( porNombre.get(
      "generate_report" ) ), "argumentos de generate_report" );
    igual( set( "document", "scale" ), props( porNombre.get( "canvas_screenshot" ) ), "argumentos de "
      + "canvas_screenshot" );

    JsonNode build = porNombre.get( "build_report" );
    noNulo( build, "build_report registrada" );
    igual( set( "document", "sections", "formats", "output_dir", "file_name", "language", "title", "narrative",
      "lineage_step", "profile_step", "profile_rows", "metrics", "query_database", "show_in_spoon" ), props( build ),
      "argumentos de build_report (diseno §1.5)" );
    igual( "[\"formats\"]", build.path( "inputSchema" ).path( "required" ).toString(), "formats es obligatorio" );
    List<String> formatos = new ArrayList<>();
    for ( Formato f : Formato.values() ) {
      formatos.add( f.clave );
    }
    igual( formatos, textos( prop( build, "formats" ).path( "items" ).path( "enum" ) ), "enum de formats" );
    igual( Arrays.asList( "html", "html_light", "pdf", "md", "pptx", "json" ), formatos, "claves de Formato" );
    igual( SeccionId.ORDEN, textos( prop( build, "sections" ).path( "items" ).path( "enum" ) ), "enum de sections" );
    igual( Arrays.asList( "last_run", "run_copy", "none" ), textos( prop( build, "metrics" ).path( "enum" ) ),
      "enum de metrics" );
    igual( set( "summary", "sections", "conclusions" ), nombres( prop( build, "narrative" ).path( "properties" ) ),
      "partes de narrative" );
    contiene( prop( build, "metrics" ).path( "description" ).asText(), "WRITE", "metrics avisa que run_copy escribe" );
    contiene( prop( build, "profile_step" ).path( "description" ).asText(), "RUNS", "profile_step avisa que ejecuta" );

    JsonNode datos = porNombre.get( "get_report_data" );
    noNulo( datos, "get_report_data registrada" );
    igual( set( "document", "sections", "language", "max_rows" ), props( datos ), "argumentos de get_report_data" );
    JsonNode mostrar = porNombre.get( "show_report" );
    noNulo( mostrar, "show_report registrada" );
    igual( set( "document", "sections", "narrative", "language" ), props( mostrar ), "argumentos de show_report" );
    JsonNode lint = porNombre.get( "lint_etl" );
    noNulo( lint, "lint_etl registrada" );
    igual( set( "document", "deep", "deep_timeout_s", "rules", "min_severity", "steps", "include_kettle_checks",
      "language" ), props( lint ), "argumentos de lint_etl (diseno del linter §6)" );
    igual( Arrays.asList( "info", "warning", "error" ), textos( prop( lint, "min_severity" ).path( "enum" ) ),
      "enum de min_severity" );
    JsonNode arreglos = porNombre.get( "apply_lint_fixes" );
    noNulo( arreglos, "apply_lint_fixes registrada" );
    igual( set( "document", "keys", "values", "dry_run", "language" ), props( arreglos ),
      "argumentos de apply_lint_fixes (diseno del linter §6)" );
    igual( "[\"keys\"]", arreglos.path( "inputSchema" ).path( "required" ).toString(), "keys es obligatorio" );
    igual( "string", prop( arreglos, "values" ).path( "additionalProperties" ).path( "type" ).asText(),
      "values: key -> texto" );
    contiene( arreglos.path( "description" ).asText(), "ASK THE PERSON", "los confirm se consultan" );
    JsonNode silenciar = porNombre.get( "suppress_lint" );
    noNulo( silenciar, "suppress_lint registrada" );
    igual( set( "document", "rule", "step", "entry", "reason", "remove" ), props( silenciar ),
      "argumentos de suppress_lint" );
    igual( "[\"rule\"]", silenciar.path( "inputSchema" ).path( "required" ).toString(), "rule es obligatorio" );
    for ( JsonNode t : new JsonNode[] { build, datos, mostrar, lint, arreglos, silenciar } ) {
      String json = t.toString();
      cierto( json.chars().allMatch( ch -> ch < 128 ), "la tool " + t.path( "name" ).asText() + " esta en ingles "
        + "(sin caracteres fuera de ASCII)" );
    }
  }

  /** Sin Spoon, las tools fallan con isError y un mensaje para la IA (y los argumentos se validan antes). */
  public void pruebaSinSpoon() throws Exception {
    JsonNode r = llamar( "build_report", "{\"formats\":[\"html\"]}" );
    cierto( r.path( "isError" ).asBoolean(), "build_report sin Spoon es isError" );
    contiene( texto( r ), "Spoon is not available", "build_report sin Spoon" );

    r = llamar( "build_report", "{\"formats\":[\"docx\"]}" );
    cierto( r.path( "isError" ).asBoolean(), "formato desconocido" );
    contiene( texto( r ), "Unknown format 'docx'", "formato desconocido, antes de buscar Spoon" );

    r = llamar( "get_report_data", "{\"sections\":[\"profile\"]}" );
    cierto( r.path( "isError" ).asBoolean(), "profile en get_report_data" );
    contiene( texto( r ), "profile_step", "profile en get_report_data explica la alternativa" );

    r = llamar( "show_report", "{}" );
    cierto( r.path( "isError" ).asBoolean(), "show_report sin Spoon es isError" );
    contiene( texto( r ), "Spoon is not available", "show_report sin Spoon" );

    r = llamar( "lint_etl", "{}" );
    cierto( r.path( "isError" ).asBoolean(), "lint_etl sin Spoon es isError" );
    contiene( texto( r ), "Spoon is not available", "lint_etl sin Spoon" );
    r = llamar( "lint_etl", "{\"rules\":[\"orphan_step\",\"no_such_rule\"]}" );
    cierto( r.path( "isError" ).asBoolean(), "regla desconocida" );
    contiene( texto( r ), "Unknown lint rule(s): [no_such_rule]", "regla desconocida, antes de buscar Spoon" );
    contiene( texto( r ), "absolute_path", "con la lista de los ids validos" );
    r = llamar( "lint_etl", "{\"min_severity\":\"fatal\"}" );
    cierto( r.path( "isError" ).asBoolean(), "min_severity desconocida" );
    contiene( texto( r ), "'min_severity'", "min_severity desconocida, antes de buscar Spoon" );

    r = llamar( "apply_lint_fixes", "{}" );
    cierto( r.path( "isError" ).asBoolean(), "apply_lint_fixes sin keys" );
    contiene( texto( r ), "Pass 'keys'", "sin keys, antes de buscar Spoon" );
    r = llamar( "apply_lint_fixes", "{\"keys\":[\"all_auto\"],\"language\":\"fr\"}" );
    contiene( texto( r ), "'language'", "idioma desconocido, antes de buscar Spoon" );
    r = llamar( "apply_lint_fixes", "{\"keys\":[\"all_auto\"]}" );
    cierto( r.path( "isError" ).asBoolean(), "apply_lint_fixes sin Spoon es isError" );
    contiene( texto( r ), "Spoon is not available", "apply_lint_fixes sin Spoon" );
    r = llamar( "suppress_lint", "{\"rule\":\"no_such_rule\"}" );
    cierto( r.path( "isError" ).asBoolean(), "suppress_lint con una regla desconocida" );
    contiene( texto( r ), "Unknown lint rule 'no_such_rule'", "regla desconocida, antes de buscar Spoon" );
    r = llamar( "suppress_lint", "{\"rule\":\"absolute_path\",\"step\":\"a\",\"entry\":\"b\"}" );
    contiene( texto( r ), "not both", "step y entry juntos" );
    r = llamar( "suppress_lint", "{\"rule\":\"absolute_path\",\"step\":\"Leer\"}" );
    cierto( r.path( "isError" ).asBoolean(), "suppress_lint sin Spoon es isError" );
    contiene( texto( r ), "Spoon is not available", "suppress_lint sin Spoon" );
  }

  // ---------------------------------------------------------------- argumentos

  public void pruebaArgumentos() throws Exception {
    Tema tema = new Tema();
    // formatos: array, texto separado por comas o texto con un array JSON; sin repetidos
    ReportTools.Peticion op = ReportTools.peticion( json( "{\"formats\":\"html, pdf,html\"}" ), tema,
      ReportTools.Modo.CONSTRUIR );
    igual( Arrays.asList( Formato.HTML, Formato.PDF ), op.formatos, "formats como texto" );
    op = ReportTools.peticion( json( "{\"formats\":\"[\\\"md\\\",\\\"json\\\"]\"}" ), tema,
      ReportTools.Modo.CONSTRUIR );
    igual( Arrays.asList( Formato.MARKDOWN, Formato.JSON ), op.formatos, "formats como array en texto" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{}" ), tema, ReportTools.Modo.CONSTRUIR ),
      "formats obligatorio en build_report" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[]}" ), tema,
      ReportTools.Modo.CONSTRUIR ), "formats vacio" );

    // secciones: validadas y en el orden del informe
    op = ReportTools.peticion( json( "{\"formats\":[\"html\"],\"sections\":[\"connections\",\"cover\",\"cover\"]}" ),
      tema, ReportTools.Modo.CONSTRUIR );
    igual( Arrays.asList( "cover", "connections" ), op.pedido.secciones, "secciones ordenadas y sin repetir" );
    ToolError e = lanza( ToolError.class, () -> ReportTools.peticion( json(
      "{\"formats\":[\"html\"],\"sections\":[\"lineaje\"]}" ), tema, ReportTools.Modo.CONSTRUIR ), "seccion mal" );
    contiene( e.getMessage(), "lineage", "el error lista los ids validos" );
    op = ReportTools.peticion( json( "{\"formats\":[\"html\"]}" ), tema, ReportTools.Modo.CONSTRUIR );
    nulo( op.pedido.secciones, "sin sections: se completan con la captura" );
    falso( op.seccionesExplicitas, "sin sections" );

    // perfil: necesita profile_step; profile_step lo agrega
    e = lanza( ToolError.class, () -> ReportTools.peticion( json(
      "{\"formats\":[\"html\"],\"sections\":[\"profile\"]}" ), tema, ReportTools.Modo.CONSTRUIR ), "profile sin step" );
    contiene( e.getMessage(), "profile_step", "profile sin step" );
    op = ReportTools.peticion( json( "{\"formats\":[\"html\"],\"sections\":[\"summary\"],\"profile_step\":\"Leer\","
      + "\"profile_rows\":200}" ), tema, ReportTools.Modo.CONSTRUIR );
    igual( Arrays.asList( "summary", "profile" ), op.pedido.secciones, "profile_step agrega profile" );
    igual( "Leer", op.pedido.perfilStep, "perfilStep" );
    igual( 200, op.pedido.perfilFilas, "perfilFilas" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[\"html\"],\"profile_rows\":0}" ), tema,
      ReportTools.Modo.CONSTRUIR ), "profile_rows fuera de rango" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[\"html\"],\"metrics\":\"todas\"}" ),
      tema, ReportTools.Modo.CONSTRUIR ), "metrics desconocido" );
    op = ReportTools.peticion( json( "{\"formats\":[\"pdf\"],\"metrics\":\"RUN_COPY\",\"query_database\":true,"
      + "\"show_in_spoon\":\"yes\",\"lineage_step\":\" Escribir \"}" ), tema, ReportTools.Modo.CONSTRUIR );
    igual( "run_copy", op.pedido.metricas, "metrics" );
    ReportTools.Peticion copia = ReportTools.peticion( json(
      "{\"formats\":[\"html\"],\"sections\":[\"cover\"],\"metrics\":\"run_copy\"}" ), tema, ReportTools.Modo.CONSTRUIR );
    igual( Arrays.asList( "cover", "metrics" ), copia.pedido.secciones, "run_copy agrega metrics" );
    cierto( op.pedido.destinoConsultarBase, "query_database" );
    cierto( op.mostrar, "show_in_spoon" );
    igual( "Escribir", op.pedido.linajeStep, "lineage_step sin blancos" );

    // idioma: el argumento, si no el de los ajustes
    Tema pt = new Tema();
    pt.idioma = "pt_BR";
    igual( "pt", ReportTools.peticion( json( "{\"formats\":[\"md\"]}" ), pt, ReportTools.Modo.CONSTRUIR ).pedido.idioma,
      "idioma de los ajustes" );
    igual( "es", ReportTools.peticion( json( "{\"formats\":[\"md\"],\"language\":\"ES\"}" ), pt,
      ReportTools.Modo.CONSTRUIR ).pedido.idioma, "idioma del argumento" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[\"md\"],\"language\":\"fr\"}" ), pt,
      ReportTools.Modo.CONSTRUIR ), "idioma desconocido" );

    // get_report_data: max_rows y sin perfil
    op = ReportTools.peticion( json( "{\"max_rows\":7}" ), tema, ReportTools.Modo.DATOS );
    igual( 7, op.maxFilas, "max_rows" );
    igual( 50, ReportTools.peticion( json( "{}" ), tema, ReportTools.Modo.DATOS ).maxFilas, "max_rows por defecto" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"max_rows\":5000}" ), tema, ReportTools.Modo.DATOS ),
      "max_rows fuera de rango" );
  }

  public void pruebaNarrativa() throws Exception {
    Tema tema = new Tema();
    Narrativa n = ReportTools.narrativa( json( "{\"summary\":\" Lee **ventas.csv** \",\"sections\":"
      + "{\"lineage\":\"El total sale de importe.\"},\"conclusions\":\"- Funciona\\n- Rapido\"}" ) );
    igual( "Lee **ventas.csv**", n.resumen, "summary sin blancos" );
    igual( "El total sale de importe.", n.porSeccion.get( "lineage" ), "texto por seccion" );
    igual( "- Funciona\n- Rapido", n.conclusiones, "conclusiones" );
    igual( Narrativa.IA, n.origen, "origen ia" );
    // como texto JSON (algunos clientes mandan los objetos asi) y como texto suelto
    n = ReportTools.narrativa( McpHttpServer.JSON.getNodeFactory().textNode( "{\"summary\":\"Hola\"}" ) );
    igual( "Hola", n.resumen, "objeto como texto JSON" );
    n = ReportTools.narrativa( McpHttpServer.JSON.getNodeFactory().textNode( "Conclusion suelta" ) );
    igual( "Conclusion suelta", n.conclusiones, "texto suelto va a las conclusiones" );
    nulo( ReportTools.narrativa( json( "{\"summary\":\"  \"}" ) ), "narrativa vacia" );

    StringBuilder largo = new StringBuilder();
    while ( largo.length() <= ReportTools.MAX_NARRATIVA ) {
      largo.append( "texto largo " );
    }
    ToolError e = lanza( ToolError.class, () -> ReportTools.narrativa( McpHttpServer.JSON.createObjectNode().put(
      "conclusions", largo.toString() ) ), "tope de 2000" );
    contiene( e.getMessage(), "2000", "el error dice el tope" );
    lanza( ToolError.class, () -> ReportTools.narrativa( json( "{\"sections\":{\"linaje\":\"x\"}}" ) ),
      "id de seccion mal en la narrativa" );
    lanza( ToolError.class, () -> ReportTools.narrativa( json( "{\"resumen\":\"x\"}" ) ), "clave desconocida" );
    lanza( ToolError.class, () -> ReportTools.narrativa( json( "[1]" ) ), "no es objeto" );

    // conclusiones con sections explicitas: se agrega narrative; texto de una seccion no pedida: aviso
    ReportTools.Peticion op = ReportTools.peticion( json( "{\"formats\":[\"html\"],\"sections\":[\"cover\"],"
      + "\"narrative\":{\"conclusions\":\"Fin\",\"sections\":{\"metrics\":\"M\"}}}" ), tema,
      ReportTools.Modo.CONSTRUIR );
    igual( Arrays.asList( "cover", "narrative" ), op.pedido.secciones, "conclusions agrega narrative" );
    cierto( op.avisos.toString().contains( "metrics" ), "aviso por la narrativa de una seccion no pedida: "
      + op.avisos );
  }

  public void pruebaNombresYCarpeta() throws Exception {
    Tema tema = new Tema();
    Captura c = EntornoKettle.captura( "03" );
    File ktr = new File( c.archivo );
    igual( new File( ktr.getParentFile(), "informes" ), ReportTools.carpetaSalida( null, tema, c ),
      "carpeta por defecto: <carpeta del .ktr>\\informes" );
    File propia = EntornoKettle.salida( "mcp-carpeta" );
    igual( propia.getAbsoluteFile(), ReportTools.carpetaSalida( propia.getPath(), tema, c ), "output_dir" );
    Tema conCarpeta = new Tema();
    conCarpeta.salida.carpeta = propia.getPath();
    igual( propia.getAbsoluteFile(), ReportTools.carpetaSalida( null, conCarpeta, c ), "carpeta de los ajustes" );

    Captura sinArchivo = EntornoKettle.captura( "03" );
    sinArchivo.archivo = null;
    ToolError e = lanza( ToolError.class, () -> ReportTools.carpetaSalida( null, tema, sinArchivo ),
      "documento sin guardar y sin output_dir" );
    contiene( e.getMessage(), "output_dir", "pide output_dir" );
    // relativa: se resolveria contra el directorio de trabajo de Spoon (data-integration)
    ToolError rel = lanza( ToolError.class, () -> ReportTools.carpetaSalida( "informes", tema, c ),
      "output_dir relativo" );
    contiene( rel.getMessage(), "must be an absolute folder", "explica por que: " + rel.getMessage() );
    Tema relativa = new Tema();
    relativa.salida.carpeta = "mis-informes";
    igual( new File( ktr.getParentFile(), "mis-informes" ).getAbsoluteFile(), ReportTools.carpetaSalida( null,
      relativa, c ), "carpeta relativa de los ajustes: desde la carpeta del .ktr" );

    Date fecha = new Date();
    igual( "b_c_", ReportTools.nombreBase( "a/b:c?.pdf", tema, fecha ), "file_name sin carpeta, extension ni "
      + "caracteres invalidos" );
    igual( "entrega", ReportTools.nombreBase( "entrega.html", tema, fecha ), "file_name sin extension" );
    igual( "ventas_ _borrador_", ReportTools.nombreBase( "ventas: <borrador>", tema, fecha ), "file_name limpio" );
    lanza( ToolError.class, () -> ReportTools.nombreBase( "...", tema, fecha ), "file_name invalido" );
    Tema conFecha = new Tema();
    conFecha.salida.agregarFecha = true;
    cierto( ReportTools.nombreBase( "x", conFecha, fecha ).matches( "x-\\d{8}-\\d{4}" ), "agregar_fecha" );

    // sin file_name, el mismo nombre y los mismos archivos que el panel (InformeServicio)
    Informe inf = new Informe();
    inf.nombreDoc = "03_ventas_por_producto";
    igual( "03_ventas_por_producto", InformeServicio.nombreBase( inf, tema ), "nombre por defecto" );
    File dir = new File( "C:\\salida" );
    Set<String> nombres = new HashSet<>();
    for ( Formato f : Formato.values() ) {
      nombres.add( InformeServicio.archivo( dir, "x", f ).getName() );
    }
    igual( Formato.values().length, nombres.size(), "un archivo distinto por formato (html_light no pisa al html): "
      + nombres );
    cierto( nombres.containsAll( set( "x.html", "x.pdf", "x.md", "x.pptx", "x.json" ) ), "extensiones: " + nombres );
  }

  // ---------------------------------------------------------------- get_report_data: modelo en ingles

  /** Sobre ModeloMuestra: no depende del motor (WP2). */
  public void pruebaDatosEnIngles() throws Exception {
    Informe inf = ModeloMuestra.crear();
    ArrayNode campos = (ArrayNode) inf.seccion( SeccionId.LINAJE ).datos.get( "fields" );
    for ( String extra : new String[] { "producto", "filas", "cliente" } ) { // 4 campos: se cortan a 2
      campos.addObject().put( "field", extra ).put( "kind", "pass" );
    }
    String original = ModeloJson.escribir( inf, false );
    contiene( original, "iVBOR", "la muestra trae PNG en base64" );

    ObjectNode d = ReportTools.datosIa( inf, 2 );
    String json = d.toString();
    noContiene( json, "iVBOR", "sin binarios" );
    noContiene( json, "\"rotulos\"", "sin rotulos internos" );
    cierto( d.path( "sections" ).isArray() && d.path( "sections" ).size() == inf.secciones.size(), "secciones" );
    Set<String> espanol = set( "secciones", "bloques", "titulo", "resumen", "estado", "motivo", "tipo", "texto",
      "columnas", "filas", "nivel", "clase", "datos", "etiqueta", "valor", "categorias", "valores", "tramos",
      "alternativa", "imagen", "nota", "ia" );
    List<String> encontradas = new ArrayList<>();
    buscarClaves( d, espanol, encontradas, false );
    cierto( encontradas.isEmpty(), "claves en castellano fuera de 'data': " + encontradas );

    Set<String> estados = new HashSet<>();
    Set<String> tipos = new HashSet<>();
    for ( JsonNode s : d.get( "sections" ) ) {
      estados.add( s.path( "status" ).asText() );
      for ( JsonNode b : s.path( "blocks" ) ) {
        tipos.add( b.path( "type" ).asText() );
      }
    }
    igual( set( "ok", "partial", "skipped", "error" ), estados, "los 4 estados en ingles" );
    igual( set( "paragraph", "list", "table", "indicators", "diagram", "chart", "image", "notice" ), tipos,
      "los 8 tipos de bloque en ingles" );
    contiene( json, "\"kind\":\"lineage\"", "clase de diagrama en ingles" );
    contiene( json, "\"kind\":\"bar\"", "clase de grafico en ingles" );
    contiene( json, "\"ai_written\":true", "marca de texto de IA" );
    noContiene( json, "\"ai_written\":false", "sin ai_written=false" );

    // tablas cortadas a max_rows con su total; datos crudos cortados y anotados en "truncated"
    JsonNode linaje = null;
    for ( JsonNode s : d.get( "sections" ) ) {
      if ( SeccionId.LINAJE.equals( s.path( "id" ).asText() ) ) {
        linaje = s;
      }
    }
    noNulo( linaje, "seccion lineage" );
    boolean cortada = false;
    for ( JsonNode b : linaje.path( "blocks" ) ) {
      if ( "table".equals( b.path( "type" ).asText() ) && b.has( "rows_total" ) ) {
        igual( 2, b.path( "rows" ).size(), "filas cortadas" );
        cierto( b.path( "rows_total" ).asInt() > 2, "rows_total" );
        cortada = true;
      }
    }
    cierto( cortada, "alguna tabla del linaje cortada a 2 filas" );
    cierto( linaje.path( "data" ).path( "fields" ).size() <= 2, "datos crudos cortados" );
    contiene( d.path( "truncated" ).toString(), "sections[lineage].data.fields", "ruta del recorte" );
    igual( "lineage", linaje.path( "data" ).path( "section" ).asText(), "los datos crudos no cambian de forma" );

    // con max_rows alto no se corta nada
    ObjectNode entero = ReportTools.datosIa( inf, 1000 );
    falso( entero.has( "truncated" ), "sin recortes" );
    noContiene( entero.toString(), "_total\"", "sin totales" );
  }

  // ---------------------------------------------------------------- de punta a punta sobre archivos

  /** build_report sobre la captura de un archivo: todos los formatos que esta maquina puede dar. */
  public void pruebaBuildReportArchivo() throws Exception {
    Captura c = EntornoKettle.captura( "03" );
    File dir = EntornoKettle.salida( "mcp-build-03" );
    limpiar( dir );
    ObjectNode args = McpHttpServer.JSON.createObjectNode();
    ArrayNode fs = args.putArray( "formats" );
    for ( Formato f : Formato.values() ) {
      fs.add( f.clave );
    }
    args.put( "output_dir", dir.getPath() );
    args.put( "language", "es" );
    args.put( "title", "Ventas por producto" );
    ObjectNode narr = args.putObject( "narrative" );
    narr.put( "summary", "Resumen redactado por la IA: lee **ventas.csv**." );
    narr.putObject( "sections" ).put( "dictionary", "Diccionario comentado por la IA." );
    narr.put( "conclusions", "- Conclusion de la IA" );

    long t0 = System.currentTimeMillis();
    ObjectNode r = ReportTools.construir( c, args );
    System.out.println( "  build_report 03: " + ( System.currentTimeMillis() - t0 ) + " ms; " + resumen( r ) );

    igual( c.docId, r.path( "document" ).asText(), "document" );
    igual( dir.getAbsolutePath(), r.path( "output_dir" ).asText(), "output_dir" );
    Map<String, File> archivos = new LinkedHashMap<>();
    for ( JsonNode x : r.path( "files" ) ) {
      File f = new File( x.path( "path" ).asText() );
      archivos.put( x.path( "format" ).asText(), f );
      igual( f.length(), x.path( "bytes" ).asLong(), "bytes de " + f.getName() );
    }
    String avisos = r.path( "warnings" ).toString();
    for ( String clave : new String[] { "html", "html_light", "md", "pptx", "json" } ) {
      File f = archivos.get( clave );
      if ( f == null ) {
        falla( "build_report no escribio " + clave + ". Avisos: " + avisos );
      }
      archivo( f, 200, clave );
    }
    igual( new File( dir, "03_ventas_por_producto.html" ), archivos.get( "html" ), "nombre del html" );
    distinto( archivos.get( "html" ), archivos.get( "html_light" ), "el liviano no pisa al interactivo" );
    File pdf = archivos.get( "pdf" );
    if ( Chromium.ubicar() != null ) {
      noNulo( pdf, "con Edge/Chrome hay PDF. Avisos: " + avisos );
      byte[] cab = Arrays.copyOf( Files.readAllBytes( pdf.toPath() ), 4 );
      igual( "%PDF", new String( cab, StandardCharsets.ISO_8859_1 ), "el PDF empieza con %PDF" );
    } else {
      contiene( avisos, "pdf", "sin navegador el PDF deja un aviso" );
    }

    // secciones con estado en ingles, y la narrativa adentro
    Set<String> ids = new HashSet<>();
    for ( JsonNode s : r.path( "sections" ) ) {
      ids.add( s.path( "id" ).asText() );
      cierto( set( "ok", "partial", "skipped", "error" ).contains( s.path( "status" ).asText() ), "estado en ingles: "
        + s );
    }
    for ( String id : new String[] { "cover", "summary", "flow", "dictionary", "narrative" } ) {
      cierto( ids.contains( id ), "seccion " + id + " en " + ids );
    }
    falso( ids.contains( "job_flow" ), "job_flow no aplica a una transformacion" );
    falso( ids.contains( "profile" ), "profile solo con profile_step" );
    String html = EntornoKettle.leer( archivos.get( "html" ) );
    contiene( html, "Resumen redactado por la IA", "la narrativa va al HTML" );
    contiene( html, "Conclusion de la IA", "las conclusiones van al HTML" );
    String md = EntornoKettle.leer( archivos.get( "md" ) );
    contiene( md, "Diccionario comentado por la IA", "la narrativa de una seccion va al Markdown" );
    Informe leido = ModeloJson.leer( EntornoKettle.leer( archivos.get( "json" ) ), Informe.class );
    igual( "es", leido.idioma, "idioma del JSON" );
    igual( "Ventas por producto", leido.titulo, "titulo" );
  }

  /** Un job: job_flow y ninguna seccion de transformacion. */
  public void pruebaJob() throws Exception {
    Captura job = EntornoKettle.captura( "10" );
    File dir = EntornoKettle.salida( "mcp-build-10" );
    limpiar( dir );
    ObjectNode args = McpHttpServer.JSON.createObjectNode();
    args.putArray( "formats" ).add( "md" ).add( "json" );
    args.put( "output_dir", dir.getPath() );
    args.put( "file_name", "job-10" );
    args.put( "lineage_step", "Escribir" );
    ObjectNode r = ReportTools.construir( job, args );
    contiene( r.path( "warnings" ).toString(), "lineage_step is ignored", "lineage_step en un job avisa" );
    System.out.println( "  build_report 10: " + resumen( r ) );
    igual( 2, r.path( "files" ).size(), "md y json del job. Avisos: " + r.path( "warnings" ) );
    archivo( new File( dir, "job-10.md" ), 200, "md del job" );
    Set<String> ids = new HashSet<>();
    for ( JsonNode s : r.path( "sections" ) ) {
      ids.add( s.path( "id" ).asText() );
    }
    cierto( ids.contains( "job_flow" ), "job_flow en un job: " + ids );
    falso( ids.contains( "flow" ) || ids.contains( "lineage" ) || ids.contains( "dictionary" ), "secciones de "
      + "transformacion fuera del job: " + ids );
  }

  /** get_report_data de punta a punta sobre la captura de un archivo. */
  public void pruebaDatosDeArchivo() throws Exception {
    Captura trans = EntornoKettle.captura( "03" );
    ObjectNode d = ReportTools.datos( trans, json( "{\"sections\":[\"summary\",\"dictionary\"],\"max_rows\":3,"
      + "\"language\":\"en\"}" ) );
    igual( trans.docId, d.path( "document" ).asText(), "document" );
    igual( "en", d.path( "language" ).asText(), "language" );
    List<String> secciones = new ArrayList<>();
    for ( JsonNode s : d.path( "sections" ) ) {
      secciones.add( s.path( "id" ).asText() );
    }
    igual( Arrays.asList( "summary", "dictionary" ), secciones, "solo las secciones pedidas" );
    for ( JsonNode s : d.path( "sections" ) ) {
      for ( JsonNode b : s.path( "blocks" ) ) {
        if ( "table".equals( b.path( "type" ).asText() ) ) {
          cierto( b.path( "rows" ).size() <= 3, "tablas cortadas a max_rows" );
        }
      }
    }
    noContiene( d.toString(), "\"bloques\"", "claves en ingles" );
  }

  // ---------------------------------------------------------------- ayudas

  /** Chequeo de forma de JSON Schema (el subconjunto que usa el servidor). */
  private static void validarEsquema( JsonNode s, String ruta, boolean raiz, List<String> errores ) {
    if ( s == null || !s.isObject() ) {
      errores.add( ruta + ": no es un objeto" );
      return;
    }
    Set<String> tipos = set( "object", "array", "string", "integer", "number", "boolean" );
    String tipo = s.path( "type" ).asText( "" );
    if ( !tipos.contains( tipo ) ) {
      errores.add( ruta + ": type invalido '" + tipo + "'" );
      return;
    }
    if ( raiz && !"object".equals( tipo ) ) {
      errores.add( ruta + ": la raiz tiene que ser object" );
    }
    if ( ruta.matches( "[^.]+\\.properties\\.[^.]+" ) && s.path( "description" ).asText( "" ).trim().isEmpty() ) {
      errores.add( ruta + ": argumento sin description" );
    }
    if ( s.has( "enum" ) ) {
      JsonNode e = s.get( "enum" );
      if ( !e.isArray() || e.size() == 0 ) {
        errores.add( ruta + ": enum vacio" );
      } else {
        for ( JsonNode v : e ) {
          if ( "string".equals( tipo ) && !v.isTextual() ) {
            errores.add( ruta + ": valor de enum que no es string: " + v );
          }
        }
      }
    }
    for ( String k : new String[] { "minimum", "maximum" } ) {
      if ( s.has( k ) && ( !s.get( k ).isNumber() || !( "integer".equals( tipo ) || "number".equals( tipo ) ) ) ) {
        errores.add( ruta + ": " + k + " solo va en numeros" );
      }
    }
    if ( s.has( "minimum" ) && s.has( "maximum" ) && s.get( "minimum" ).asDouble() > s.get( "maximum" ).asDouble() ) {
      errores.add( ruta + ": minimum > maximum" );
    }
    if ( "object".equals( tipo ) ) {
      JsonNode props = s.get( "properties" );
      if ( props != null && !props.isObject() ) {
        errores.add( ruta + ": properties no es un objeto" );
      }
      if ( props != null ) {
        for ( Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
          String k = it.next();
          if ( !k.matches( "[a-z][a-z0-9_]*" ) ) {
            errores.add( ruta + ": argumento que no es snake_case: " + k );
          }
          validarEsquema( props.get( k ), ruta + ".properties." + k, false, errores );
        }
      }
      if ( s.has( "required" ) ) {
        JsonNode req = s.get( "required" );
        if ( !req.isArray() || req.size() == 0 ) {
          errores.add( ruta + ": required vacio (hay que sacarlo)" );
        } else {
          for ( JsonNode r : req ) {
            if ( props == null || !props.has( r.asText() ) ) {
              errores.add( ruta + ": required '" + r.asText() + "' no esta en properties" );
            }
          }
        }
      }
      JsonNode ap = s.get( "additionalProperties" );
      if ( ap != null && !ap.isBoolean() ) {
        validarEsquema( ap, ruta + ".additionalProperties", false, errores );
      }
    } else if ( "array".equals( tipo ) ) {
      if ( !s.has( "items" ) ) {
        errores.add( ruta + ": array sin items" );
      } else {
        validarEsquema( s.get( "items" ), ruta + ".items", false, errores );
      }
      if ( s.has( "minItems" ) && s.get( "minItems" ).asInt() < 0 ) {
        errores.add( ruta + ": minItems negativo" );
      }
    } else if ( s.has( "properties" ) || s.has( "items" ) ) {
      errores.add( ruta + ": properties/items en un " + tipo );
    }
  }

  private static void buscarClaves( JsonNode n, Set<String> prohibidas, List<String> encontradas, boolean crudo ) {
    if ( n.isObject() ) {
      for ( Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        if ( !crudo && prohibidas.contains( e.getKey() ) ) {
          encontradas.add( e.getKey() );
        }
        buscarClaves( e.getValue(), prohibidas, encontradas, crudo || "data".equals( e.getKey() ) );
      }
    } else if ( n.isArray() ) {
      for ( JsonNode x : n ) {
        buscarClaves( x, prohibidas, encontradas, crudo );
      }
    }
  }

  private JsonNode rpc( String metodo, JsonNode params ) throws Exception {
    ObjectNode m = McpHttpServer.JSON.createObjectNode();
    m.put( "jsonrpc", "2.0" );
    m.put( "id", 1 );
    m.put( "method", metodo );
    m.set( "params", params );
    HttpURLConnection con = (HttpURLConnection) new URL( "http://127.0.0.1:" + puerto + "/mcp" ).openConnection();
    con.setRequestMethod( "POST" );
    con.setDoOutput( true );
    con.setConnectTimeout( 5000 );
    con.setReadTimeout( 120000 );
    con.setRequestProperty( "Content-Type", "application/json" );
    try ( OutputStream os = con.getOutputStream() ) {
      os.write( m.toString().getBytes( StandardCharsets.UTF_8 ) );
    }
    igual( 200, con.getResponseCode(), "HTTP de " + metodo );
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try ( InputStream in = con.getInputStream() ) {
      byte[] b = new byte[ 8192 ];
      int n;
      while ( ( n = in.read( b ) ) > 0 ) {
        buf.write( b, 0, n );
      }
    }
    JsonNode r = McpHttpServer.JSON.readTree( new String( buf.toByteArray(), StandardCharsets.UTF_8 ) );
    falso( r.has( "error" ), metodo + " sin error JSON-RPC: " + r );
    return r;
  }

  private JsonNode llamar( String tool, String argumentos ) throws Exception {
    ObjectNode p = McpHttpServer.JSON.createObjectNode();
    p.put( "name", tool );
    p.set( "arguments", json( argumentos ) );
    return rpc( "tools/call", p ).path( "result" );
  }

  private static String texto( JsonNode resultado ) {
    StringBuilder sb = new StringBuilder();
    for ( JsonNode c : resultado.path( "content" ) ) {
      sb.append( c.path( "text" ).asText() );
    }
    return sb.toString();
  }

  /** "html 2594 KB, pdf 158 KB...; cover ok, lineage skipped (WP3)...; 0 avisos" para la salida. */
  private static String resumen( JsonNode r ) {
    StringBuilder sb = new StringBuilder();
    for ( JsonNode f : r.path( "files" ) ) {
      sb.append( f.path( "format" ).asText() ).append( ' ' ).append( f.path( "bytes" ).asLong() / 1024 ).append(
        " KB, " );
    }
    sb.append( "| " );
    for ( JsonNode s : r.path( "sections" ) ) {
      sb.append( s.path( "id" ).asText() ).append( ' ' ).append( s.path( "status" ).asText() );
      if ( s.has( "reason" ) ) {
        sb.append( " (" ).append( s.path( "reason" ).asText() ).append( ')' );
      }
      sb.append( ", " );
    }
    sb.append( "| warnings: " ).append( r.path( "warnings" ) );
    return sb.toString();
  }

  private static JsonNode json( String s ) throws Exception {
    return McpHttpServer.JSON.readTree( s );
  }

  private static JsonNode prop( JsonNode tool, String nombre ) {
    return tool.path( "inputSchema" ).path( "properties" ).path( nombre );
  }

  private static Set<String> props( JsonNode tool ) {
    noNulo( tool, "tool" );
    return nombres( tool.path( "inputSchema" ).path( "properties" ) );
  }

  private static Set<String> nombres( JsonNode o ) {
    Set<String> s = new HashSet<>();
    for ( Iterator<String> it = o.fieldNames(); it.hasNext(); ) {
      s.add( it.next() );
    }
    return s;
  }

  private static List<String> textos( JsonNode arr ) {
    List<String> l = new ArrayList<>();
    for ( JsonNode x : arr ) {
      l.add( x.asText() );
    }
    return l;
  }

  private static Set<String> set( String... v ) {
    return new HashSet<>( Arrays.asList( v ) );
  }

  private static void limpiar( File dir ) throws Exception {
    EntornoKettle.borrar( dir.toPath() );
    cierto( dir.mkdirs(), "carpeta de salida " + dir );
  }
}
