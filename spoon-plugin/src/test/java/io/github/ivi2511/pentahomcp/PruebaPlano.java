package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.diagramas.Diagramas;
import io.github.ivi2511.pentahomcp.informes.diagramas.Dibujo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Objetivo;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * El plano ETL sin Spoon (D1): leer YAML y JSON con sus errores de forma, escribir YAML determinista (ida y vuelta),
 * SnakeYAML solo con SafeConstructor, la revisión estática (plan_review) con sus textos en los tres idiomas, el
 * documento con las tres vistas (motor propio + Mermaid) en HTML y Markdown, el esquema JSON de docs/ y el plano del
 * ejemplo Grano de Oro (ejemplo/plano/).
 */
public class PruebaPlano {

  static final String MINI = "plan: 1\n"
    + "name: Ventas\n"
    + "language: es\n"
    + "goal: Cargar las ventas\n"
    + "grain: una fila por línea de ticket\n"
    + "load: full\n"
    + "sources:\n"
    + "  - id: ventas\n"
    + "    name: ventas.csv\n"
    + "    format: csv\n"
    + "    location: datos/ventas.csv\n"
    + "    volume: 3000 filas\n"
    + "    frequency: mensual\n"
    + "    key: [ticket, linea]\n"
    + "    rejects: salida/rechazos.csv\n"
    + "  - id: clientes\n"
    + "    name: clientes.csv\n"
    + "    format: csv\n"
    + "    location: datos/clientes.csv\n"
    + "    volume: 50 filas\n"
    + "    frequency: mensual\n"
    + "    rejects: salida/rechazos.csv\n"
    + "targets:\n"
    + "  - id: hechos\n"
    + "    name: hechos_ventas\n"
    + "    kind: fact\n"
    + "    key: [ticket, linea]\n"
    + "    load: truncate_insert\n"
    + "rules:\n"
    + "  - id: R1\n"
    + "    text: Precios con punto decimal\n"
    + "stages:\n"
    + "  - id: leer\n"
    + "    name: Leer ventas\n"
    + "    kind: extract\n"
    + "    reads: ventas\n"
    + "  - id: leer_clientes\n"
    + "    name: Leer clientes\n"
    + "    kind: extract\n"
    + "    reads: [clientes]\n"
    + "  - id: limpiar\n"
    + "    name: Limpiar\n"
    + "    kind: clean\n"
    + "    after: [leer]\n"
    + "    rules: [R1]\n"
    + "  - id: buscar\n"
    + "    name: Buscar cliente\n"
    + "    kind: lookup\n"
    + "    after: [limpiar]\n"
    + "    lookup: leer_clientes\n"
    + "    key: [cliente_id]\n"
    + "    on_missing: reject\n"
    + "  - id: cargar\n"
    + "    name: Cargar hechos\n"
    + "    kind: load\n"
    + "    after: [buscar]\n"
    + "    writes: [hechos]\n"
    + "  - id: rechazos\n"
    + "    name: Rechazos\n"
    + "    kind: reject\n"
    + "    after: [buscar]\n"
    + "risks:\n"
    + "  - text: Duplicados\n"
    + "    mitigation: Quitar duplicados\n"
    + "decisions:\n"
    + "  - text: Carga completa\n"
    + "    why: Es pequeña\n";

  static Plano mini() {
    Plano.Lectura l = Plano.leerTexto( MINI );
    igual( new ArrayList<String>(), l.errores, "el plano mínimo no tiene errores" );
    return l.plano;
  }

  /** La raíz del repositorio (pruebas.recursos = spoon-plugin\src\test\resources). */
  static File repo() {
    File r = new File( System.getProperty( "pruebas.recursos", "spoon-plugin/src/test/resources" ) ).getAbsoluteFile();
    return r.getParentFile().getParentFile().getParentFile().getParentFile();
  }

  static File ejemplo() {
    return new File( repo(), "ejemplo/plano/grano-de-oro.plan.yaml" );
  }

  // ================================================================ leer y escribir

  public void pruebaLeerYaml() {
    Plano p = mini();
    igual( "Ventas", p.nombre, "nombre" );
    igual( "es", p.idioma, "idioma" );
    igual( 2, p.fuentes.size(), "fuentes" );
    igual( "[ticket, linea]", p.fuente( "ventas" ).clave.toString(), "clave en línea" );
    igual( "[ventas]", p.etapa( "leer" ).lee.toString(), "un texto suelto vale como lista" );
    igual( "reject", p.etapa( "buscar" ).siFalta, "on_missing" );
    igual( 1, p.transformaciones().size(), "una transformación sin nombre" );
    igual( "", p.transformaciones().get( 0 ), "la transformación sin nombre es \"\"" );
  }

  public void pruebaIdaYVueltaYaml() {
    Plano p = mini();
    String y = p.aYaml();
    contiene( y, "yaml-language-server: $schema=", "cabecera con el esquema" );
    Plano.Lectura l = Plano.leerTexto( y );
    igual( new ArrayList<String>(), l.errores, "lo escrito se vuelve a leer" );
    igual( p.aJson(), l.plano.aJson(), "ida y vuelta sin cambios" );
    igual( y, l.plano.aYaml(), "determinista: el mismo plano da el mismo YAML" );
    // textos raros: comillas, dos puntos, almohadilla, saltos, números y booleanos de YAML 1.1
    ObjectNode j = p.aJson();
    j.put( "goal", "Línea 1: «hola» # no es comentario\nLínea 2 con \"comillas\" y \\barra" );
    j.put( "grain", "yes" );
    j.put( "incremental_by", "02:00" );
    Plano q = Plano.desdeJson( j ).plano;
    Plano r = Plano.leerTexto( q.aYaml() ).plano;
    noNulo( r, "se relee" );
    igual( q.objetivo, r.objetivo, "texto con saltos y caracteres de YAML" );
    igual( "yes", r.grano, "yes queda texto, no booleano" );
    igual( "02:00", r.incrementalPor, "02:00 queda texto, no sexagesimal" );
  }

  public void pruebaJson() {
    Plano p = mini();
    Plano.Lectura l = Plano.leerTexto( p.aJson().toString() );
    igual( new ArrayList<String>(), l.errores, "JSON" );
    igual( p.aJson(), l.plano.aJson(), "JSON ida y vuelta" );
  }

  public void pruebaErroresDeForma() {
    Plano.Lectura l = Plano.leerTexto( "name: X\nsources:\n  - name: a\n    format: parquet\nstages:\n"
      + "  - name: S1\n    after: [nada]\n    writes: [t9]\n  - name: S1\n" );
    nulo( l.plano, "con errores no hay plano" );
    String e = l.errores.toString();
    contiene( e, "format: 'parquet' is not valid", "enum" );
    contiene( e, "there is no stage 'nada'", "after desconocido" );
    contiene( e, "there is no target 't9'", "writes desconocido" );
    contiene( e, "used twice", "ids repetidos (s1 dos veces)" );
    Plano.Lectura c = Plano.leerTexto( "name: X\nstages:\n  - {id: a, name: A, after: [b]}\n"
      + "  - {id: b, name: B, after: [a]}\n" );
    contiene( c.errores.toString(), "loop", "ciclo" );
    Plano.Lectura k = Plano.leerTexto( "name: X\ngoall: typo\nsources:\n  - name: a\n    formato: csv\n" );
    noNulo( k.plano, "las claves desconocidas no son errores" );
    contiene( k.avisos.toString(), "goall: unknown key", "aviso de clave desconocida" );
    contiene( k.avisos.toString(), "sources[0].formato", "aviso con la ruta" );
    nulo( Plano.leerTexto( "" ).plano, "vacío" );
    nulo( Plano.leerTexto( "- a\n- b\n" ).plano, "una lista no es un plano" );
    contiene( Plano.leerTexto( "name: [x\n" ).errores.toString(), "not valid YAML", "YAML roto" );
  }

  /** SafeConstructor: una etiqueta de clase de Java no construye nada. */
  public void pruebaYamlSeguro() {
    Plano.Lectura l = Plano.leerTexto( "name: !!java.io.File [\"x\"]\n" );
    nulo( l.plano, "etiqueta de clase rechazada" );
    contiene( l.errores.toString(), "not valid YAML", "el error lo dice" );
    Plano.Lectura u = Plano.leerTexto( "name: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader "
      + "[[!!java.net.URL [\"http://127.0.0.1:1/\"]]]]\n" );
    nulo( u.plano, "la carga útil clásica de SnakeYAML tampoco" );
  }

  public void pruebaSlugYNombres() {
    igual( "leer_ventas_csv", Plano.slug( "Leer ventas (CSV)" ), "slug" );
    igual( "linea_valida", Plano.slug( "¿Línea válida?" ), "slug sin acentos" );
    cierto( Plano.esNombreDePlano( "x.plan.yaml" ) && Plano.esNombreDePlano( "X.PLAN.JSON" ), "nombres de plano" );
    falso( Plano.esNombreDePlano( "x.yaml" ), "un .yaml cualquiera no" );
    igual( "04_hechos", Plano.sinKtr( "C:\\a\\04_hechos.ktr" ), "sinKtr" );
  }

  // ================================================================ revisión

  public void pruebaRevisionLimpia() {
    List<PlanoRevision.Hallazgo> hs = PlanoRevision.revisar( mini(), "es" );
    List<String> codigos = codigos( hs );
    falso( codigos.contains( "no_grain" ), "tiene grano" );
    falso( codigos.contains( "source_without_rejects" ), "las dos fuentes tienen plan de rechazos" );
    falso( codigos.contains( "lookup_without_key" ), "la búsqueda tiene clave" );
    falso( codigos.contains( "non_idempotent_load" ), "truncate_insert es idempotente" );
    falso( codigos.contains( "orphans_rejected_nowhere" ), "hay etapa de rechazos después" );
    igual( 0, cuenta( hs, PlanoRevision.ERROR ), "sin errores: " + codigos );
  }

  public void pruebaRevisionEncuentra() {
    Plano.Lectura l = Plano.leerTexto( "name: X\nload: incremental\nsources:\n  - id: s\n    name: s.csv\n"
      + "targets:\n  - id: f\n    name: hechos\n    kind: fact\n  - id: d\n    name: dim\n    kind: dimension\n"
      + "    load: upsert\nrules:\n  - id: R1\n    text: nadie la usa\nstages:\n"
      + "  - {id: a, name: A, kind: extract, reads: [s], transformation: t1}\n"
      + "  - {id: b, name: B, kind: lookup, after: [a], lookup: d, transformation: t1}\n"
      + "  - {id: c, name: C, kind: dedupe, after: [b], transformation: t1}\n"
      + "  - {id: e, name: E, kind: load, after: [c], writes: [f], transformation: t2}\n"
      + "  - {id: x, name: X, kind: transform, transformation: t2}\n"
      + "risks:\n  - solo texto\ndecisions:\n  - text: sin porque\n" );
    noNulo( l.plano, "se lee: " + l.errores );
    List<PlanoRevision.Hallazgo> hs = PlanoRevision.revisar( l.plano, "es" );
    List<String> c = codigos( hs );
    for ( String x : new String[] { "no_goal", "no_grain", "incremental_without_watermark", "source_without_format",
      "source_without_volume", "source_without_rejects", "target_not_written", "non_idempotent_load",
      "upsert_without_key", "lookup_without_key", "lookup_without_on_missing", "dedupe_without_key",
      "stage_without_input", "stage_dead_end", "rule_unused", "no_orchestration", "risk_without_mitigation",
      "decision_without_why" } ) {
      cierto( c.contains( x ), "encuentra " + x + ": " + c );
    }
    PlanoRevision.Hallazgo grano = buscar( hs, "no_grain" );
    igual( PlanoRevision.ERROR, grano.severidad, "sin grano y con hechos = error" );
    noNulo( grano.pregunta, "el grano lo sabe el usuario: hay pregunta" );
    contiene( buscar( hs, "lookup_without_on_missing" ).pregunta, "no existe", "la pregunta de los huérfanos" );
    ObjectNode j = PlanoRevision.json( hs );
    cierto( j.path( "errors" ).asInt() >= 2, "json: errores" );
    contiene( j.toString(), "ask_the_user", "json con la pregunta" );
  }

  /** Cada código tiene .msg en los tres idiomas, con las mismas claves, y los que preguntan tienen .ask. */
  public void pruebaTextosDeLaRevision() {
    for ( String idioma : new String[] { "en", "es", "pt" } ) {
      Rotulos r = Rotulos.de( PlanoInforme.AREA, idioma );
      for ( String c : PlanoRevision.CODIGOS ) {
        cierto( r.propio( "rev." + c + ".msg" ) != null, idioma + ": rev." + c + ".msg" );
      }
    }
    for ( String c : new String[] { "no_grain", "lookup_without_on_missing", "load_strategy_missing" } ) {
      cierto( Rotulos.de( PlanoInforme.AREA, "es" ).tiene( "rev." + c + ".ask" ), "pregunta de " + c );
    }
  }

  // ================================================================ documento y diagramas

  public void pruebaDocumento() throws Exception {
    Plano p = mini();
    Informe inf = PlanoInforme.armar( p, null, null );
    igual( "es", inf.idioma, "el idioma del plano" );
    igual( SeccionId.PORTADA, inf.secciones.get( 0 ).id, "portada primero" );
    List<String> ids = new ArrayList<String>();
    for ( Seccion s : inf.secciones ) {
      ids.add( s.id );
    }
    for ( String id : new String[] { PlanoInforme.S_RESUMEN, PlanoInforme.S_FLUJO, PlanoInforme.S_ACTIVIDAD,
      PlanoInforme.S_COMPONENTES, PlanoInforme.S_FUENTES, PlanoInforme.S_DESTINOS, PlanoInforme.S_REGLAS,
      PlanoInforme.S_ETAPAS, PlanoInforme.S_RIESGOS, PlanoInforme.S_REVISION } ) {
      cierto( ids.contains( id ), "sección " + id );
    }
    int diagramas = 0;
    for ( Seccion s : inf.secciones ) {
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Diagrama ) {
          Diagrama d = (Diagrama) b;
          diagramas++;
          noNulo( d.grafo, d.id + ": grafo del motor propio" );
          noNulo( d.mermaid, d.id + ": Mermaid" );
          cierto( d.mermaid.startsWith( "flowchart" ), d.id + ": flowchart" );
          List<Dibujo> ds = Diagramas.dibujar( d.grafo, Objetivo.A4, "es" );
          cierto( !ds.isEmpty(), d.id + ": se dibuja" );
        }
      }
    }
    igual( 3, diagramas, "tres vistas" );
    Diagrama flujo = (Diagrama) inf.seccion( PlanoInforme.S_FLUJO ).bloques.get( 0 );
    contiene( flujo.mermaid, "n_s_ventas", "fuente en el flujo" );
    contiene( flujo.mermaid, "-.->", "búsqueda punteada" );
    Diagrama act = (Diagrama) inf.seccion( PlanoInforme.S_ACTIVIDAD ).bloques.get( 0 );
    contiene( act.mermaid, "falla", "rama de falla" );
    contiene( act.mermaid, "abortar", "abortar por defecto" );
    Diagrama comp = (Diagrama) inf.seccion( PlanoInforme.S_COMPONENTES ).bloques.get( 0 );
    contiene( comp.mermaid, "subgraph", "componentes agrupados por sitio" );
    contiene( comp.mermaid, "Carpeta datos", "la carpeta de las fuentes" );

    File dir = EntornoKettle.salida( "plano" );
    File html = new File( dir, "mini.html" );
    InformeServicio.exportar( inf, new Tema(), Formato.HTML, html, null );
    String h = new String( Files.readAllBytes( html.toPath() ), StandardCharsets.UTF_8 );
    contiene( h, "<svg", "HTML con los diagramas en SVG" );
    contiene( h, "Flujo de datos", "títulos en español" );
    File md = new File( dir, "mini.md" );
    InformeServicio.exportar( inf, new Tema(), Formato.MARKDOWN, md, null );
    String m = new String( Files.readAllBytes( md.toPath() ), StandardCharsets.UTF_8 );
    contiene( m, "```mermaid", "Markdown con el Mermaid plegado" );
    contiene( m, "Revisión del plano", "la revisión" );
  }

  public void pruebaDocumentoEnIngles() {
    Informe inf = PlanoInforme.armar( mini(), "en", null );
    igual( "Data flow", inf.seccion( PlanoInforme.S_FLUJO ).titulo, "en inglés" );
    Informe pt = PlanoInforme.armar( mini(), "pt", null );
    igual( "Fluxo de dados", pt.seccion( PlanoInforme.S_FLUJO ).titulo, "en portugués" );
  }

  // ================================================================ esquema y ejemplo

  /** Las propiedades del esquema de docs/ son las que lee y escribe Plano (en cada nivel). */
  public void pruebaEsquema() throws Exception {
    File f = new File( repo(), "docs/plano.schema.json" );
    cierto( f.isFile(), "existe " + f );
    JsonNode s = McpHttpServer.JSON.readTree( f );
    JsonNode props = s.path( "properties" );
    Plano p = Plano.leer( ejemplo() ).plano;
    noNulo( p, "el ejemplo se lee" );
    ObjectNode j = p.aJson();
    j.put( "incremental_by", "x" );
    ( (ObjectNode) j.path( "sources" ).get( 0 ) ).put( "connection", "dw" );
    igual( claves( props ), claves( j ), "claves del plano" );
    igual( claves( props.path( "sources" ).path( "items" ).path( "properties" ) ), unionDe( j.path( "sources" ) ),
      "claves de sources" );
    igual( claves( props.path( "targets" ).path( "items" ).path( "properties" ) ), unionDe( j.path( "targets" ) ),
      "claves de targets" );
    igual( claves( props.path( "stages" ).path( "items" ).path( "properties" ) ), unionDe( j.path( "stages" ) ),
      "claves de stages" );
    igual( claves( props.path( "orchestration" ).path( "properties" ) ), claves( j.path( "orchestration" ) ),
      "claves de orchestration" );
    // los enum del esquema son los de Plano
    igual( Plano.FORMATOS.toString(), textos( props.path( "sources" ).path( "items" ).path( "properties" ).path(
      "format" ).path( "enum" ) ), "formatos" );
    igual( Plano.CLASES_ETAPA.toString(), textos( props.path( "stages" ).path( "items" ).path( "properties" ).path(
      "kind" ).path( "enum" ) ), "clases de etapa" );
    igual( Plano.CARGAS_DESTINO.toString(), textos( props.path( "targets" ).path( "items" ).path( "properties" )
      .path( "load" ).path( "enum" ) ), "cargas" );
  }

  /** El plano de Grano de Oro: sin errores ni claves desconocidas, escrito tal como lo escribe plan_create. */
  public void pruebaEjemploGranoDeOro() throws Exception {
    Plano.Lectura l = Plano.leer( ejemplo() );
    igual( new ArrayList<String>(), l.errores, "sin errores" );
    igual( new ArrayList<String>(), l.avisos, "sin claves desconocidas" );
    Plano p = l.plano;
    igual( "[01_dim_tienda, 02_dim_producto, 03_dim_fecha, 04_hechos_ventas, 05_kpi_ventas]", p.transformaciones()
      .toString(), "en el orden del job" );
    String y = new String( Files.readAllBytes( ejemplo().toPath() ), StandardCharsets.UTF_8 );
    File canon = new File( EntornoKettle.salida( "plano" ), "grano-de-oro.canonico.plan.yaml" );
    Files.write( canon.toPath(), p.aYaml().getBytes( StandardCharsets.UTF_8 ) );
    igual( p.aYaml(), y.replace( "\r\n", "\n" ), "el ejemplo está en la forma de plan_create (ver " + canon + ")" );
    List<PlanoRevision.Hallazgo> hs = PlanoRevision.revisar( p, "es" );
    igual( 0, cuenta( hs, PlanoRevision.ERROR ), "el ejemplo no tiene errores: " + codigos( hs ) );
  }

  /**
   * El documento del ejemplo como lo escribe plan_get formats=["html","md","images"]: queda en
   * {@code <prueba>\salida\ejemplo-plano\} (de ahí se copian los de ejemplo/plano/).
   */
  public void pruebaDocumentoDelEjemplo() throws Exception {
    Plano p = Plano.leer( ejemplo() ).plano;
    Informe inf = PlanoInforme.armar( p, null, null );
    File dir = EntornoKettle.salida( "ejemplo-plano" );
    EntornoKettle.borrar( dir.toPath() ); // las figuras se numeran: nada de una corrida anterior
    dir.mkdirs();
    for ( Formato f : new Formato[] { Formato.HTML, Formato.MARKDOWN, Formato.IMAGENES } ) {
      File destino = InformeServicio.archivo( dir, "grano-de-oro", f );
      ClassLoader antes = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader( PruebaPlano.class.getClassLoader() );
      try {
        InformeServicio.exportar( inf, new Tema(), f, destino, null );
      } finally {
        Thread.currentThread().setContextClassLoader( antes );
      }
      cierto( destino.exists(), "escrito " + destino );
    }
    String md = new String( Files.readAllBytes( new File( dir, "grano-de-oro.md" ).toPath() ), StandardCharsets.UTF_8 );
    contiene( md, "Componentes y despliegue", "las tres vistas" );
    falso( md.contains( ":\\" ) || md.contains( "Users" ), "sin rutas absolutas en el Markdown" );
  }

  /** La guía manual/10-plano.md es el recurso pentaho://guide/plan y nombra las herramientas y cada código. */
  public void pruebaGuia() throws Exception {
    McpResources r = new McpResources();
    contiene( r.listar( null ).toString(), "pentaho://guide/plan", "resources/list trae la guía del plano" );
    String guia = r.leer( "pentaho://guide/plan" ).path( "contents" ).path( 0 ).path( "text" ).asText();
    for ( String t : new String[] { "plan_create", "plan_review", "plan_get", "plan_build", "fill_placeholder",
      "plan_check", "mapping_export", "mapping_import", "placeholder_step", "docs/plano.schema.json" } ) {
      contiene( guia, t, "la guía nombra " + t );
    }
    for ( String c : PlanoRevision.CODIGOS ) {
      contiene( guia, "`" + c + "`", "la guía explica " + c );
    }
    for ( String c : new String[] { "transformation_not_built", "stage_without_step", "placeholder_left",
      "step_type_differs", "source_not_referenced", "load_differs", "orphans_not_rejected", "rejects_missing",
      "steps_not_in_plan", "transformation_not_in_job", "job_order_differs", "job_without_failure_path" } ) {
      contiene( guia, "`" + c + "`", "la guía explica la deriva " + c );
    }
  }

  // ================================================================ apoyo

  static List<String> codigos( List<PlanoRevision.Hallazgo> hs ) {
    List<String> l = new ArrayList<String>();
    for ( PlanoRevision.Hallazgo h : hs ) {
      l.add( h.codigo );
    }
    return l;
  }

  static int cuenta( List<PlanoRevision.Hallazgo> hs, String sev ) {
    int n = 0;
    for ( PlanoRevision.Hallazgo h : hs ) {
      n += sev.equals( h.severidad ) ? 1 : 0;
    }
    return n;
  }

  static PlanoRevision.Hallazgo buscar( List<PlanoRevision.Hallazgo> hs, String codigo ) {
    for ( PlanoRevision.Hallazgo h : hs ) {
      if ( codigo.equals( h.codigo ) ) {
        return h;
      }
    }
    return null;
  }

  private static Set<String> claves( JsonNode o ) {
    Set<String> s = new TreeSet<String>();
    Iterator<String> it = o.fieldNames();
    while ( it.hasNext() ) {
      s.add( it.next() );
    }
    return s;
  }

  private static Set<String> unionDe( JsonNode arr ) {
    Set<String> s = new TreeSet<String>();
    for ( JsonNode x : arr ) {
      s.addAll( claves( x ) );
    }
    return s;
  }

  private static String textos( JsonNode arr ) {
    List<String> l = new ArrayList<String>();
    for ( JsonNode x : arr ) {
      l.add( x.asText() );
    }
    return l.toString();
  }
}
