package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;

/**
 * {@code pentaho-live init} (0.7, X1: {@link ProyectoInit}), project_init y {@code pentaho-live lint/profile}
 * ({@link ProyectoMain}), sin Spoon: crea solo lo que falta y nunca pisa un archivo; el ensayo no escribe nada; el perfil
 * «curso» que deja se lee y los ejemplos de la skill lo cumplen (la misma comprobación que hace el workflow de CI que
 * crea init: {@code ProyectoMain lint etl --format github}); los códigos de salida (0, 1 por debajo del mínimo, 2 de
 * uso); el JSON, las anotaciones de GitHub y el resumen en Markdown.
 */
public class PruebaProyectoInit {

  private File base;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    base = EntornoKettle.salida( "proyecto-init" );
    EntornoKettle.borrar( base.toPath() );
    base.mkdirs();
  }

  public void pruebaA_CreaSoloLoQueFalta() throws Exception {
    File p = new File( base, "ventas" );
    ProyectoInit.Hecho ensayo = ProyectoInit.crear( p, null, "es", true );
    falso( p.exists(), "el ensayo no escribe nada" );
    cierto( ensayo.cuantos( ProyectoInit.SE_CREARIA ) > 10, "dice qué crearía: " + ProyectoInit.texto( ensayo ) );

    ProyectoInit.Hecho h = ProyectoInit.crear( p, "Curso BD 2026", "es", false );
    igual( 0, h.cuantos( ProyectoInit.EXISTE ), "carpeta nueva: todo creado" );
    for ( String f : new String[] { "etl/.gitkeep", "datasets/.gitkeep", "tests/.gitkeep", "contracts/.gitkeep",
      "env/dev.properties", ".gitignore", ".gitattributes", ".pentaho-live/lint.yaml", ".pentaho-live/lint.schema.json",
      "README.md", ".github/workflows/pentaho-live.yml" } ) {
      cierto( new File( p, f ).isFile(), "creado: " + f );
    }
    PerfilEquipo perfil = PerfilEquipo.cargar( new File( p, ".pentaho-live/lint.yaml" ) );
    igual( "Curso BD 2026", perfil.nombre, "el nombre va al perfil" );
    igual( Integer.valueOf( 75 ), perfil.minScore, "mínimo del perfil curso" );
    cierto( perfil.avisos.isEmpty(), "el perfil por defecto no tiene avisos: " + perfil.avisos );
    cierto( perfil.seccionActiva( PerfilEquipo.NAMING ) && perfil.seccionActiva( PerfilEquipo.REQUIRED_VARIABLE )
      && perfil.seccionActiva( PerfilEquipo.TEAM_PATTERN ), "con convenciones" );
    // el nombre con caracteres de YAML no rompe el archivo
    File q = new File( base, "raro" );
    ProyectoInit.crear( q, "Curso: \"BD\" # 1", "en", false );
    igual( "Curso: \"BD\" # 1", PerfilEquipo.cargar( new File( q, ".pentaho-live/lint.yaml" ) ).nombre, "nombre escapado" );

    String readme = EntornoKettle.leer( new File( p, "README.md" ) );
    contiene( readme, "# Curso BD 2026", "título" );
    contiene( readme, "pentaho-live lint etl", "cómo comprobarlo" );
    String gi = EntornoKettle.leer( new File( p, ".gitignore" ) );
    for ( String x : new String[] { "env/local.properties", "salida/", "*.sqlite", "!datasets/**/*.sqlite",
      "runs.sqlite" } ) {
      contiene( gi, x, ".gitignore: " + x );
    }
    String ga = EntornoKettle.leer( new File( p, ".gitattributes" ) );
    cierto( ProyectoInit.hayTextconv(), "el plugin trae pentaho-live textconv (" + ProyectoInit.CLASE_TEXTCONV + ")" );
    contiene( ga, "\n*.ktr diff=kettle\n*.kjb diff=kettle", "driver de diff activo" );
    noContiene( ga, "# *.ktr diff=kettle", "sin comentar" );
    String wf = EntornoKettle.leer( new File( p, ".github/workflows/pentaho-live.yml" ) );
    contiene( wf, "io.github.ivi2511.pentahomcp.ProyectoMain \"$@\"", "pentaho-live con ProyectoMain" );
    contiene( wf, "io.github.ivi2511.pentahomcp.diff.LineaComandos \"$@\"", "y con los comandos de git" );
    contiene( wf, "run: pentaho-live lint etl --format github --summary \"$GITHUB_STEP_SUMMARY\"", "el linter" );
    contiene( wf, "actions/cache", "PDI en caché" );
    contiene( wf, "v" + ProyectoInit.version() + "/pdi-ce-9.4-con-pentaho-mcp.zip", "PDI de esta versión" );
    contiene( wf, "run: pentaho-live test --all --format github --summary \"$GITHUB_STEP_SUMMARY\"", "las pruebas" );
    contiene( wf, "pentaho-live diff --rev \"origin/$BASE_REF\" --format markdown", "el diff contra la rama base" );
    contiene( wf, "git fetch --no-tags --depth=1 origin", "trae la rama base" );
    contiene( wf, "PENTAHO_LIVE_PR_COMMENT: \"false\"", "comentario apagado por defecto" );
    contiene( wf, "# pull-requests: write", "permiso comentado" );
    noContiene( wf, "TODO", "sin pasos pendientes" );
    noContiene( wf, "{{VERSION}}", "sin marcadores sin llenar" );
    String dev = EntornoKettle.leer( new File( p, "env/dev.properties" ) );
    noContiene( dev.replaceAll( "(?m)^#.*$", "" ), "DB_PASSWORD", "sin contraseñas" );

    // segunda vez: nada nuevo, y lo cambiado por la persona sigue igual
    EntornoKettle.escribir( new File( p, "README.md" ), "mío" );
    new File( p, "tests/.gitkeep" ).delete();
    ProyectoInit.Hecho otra = ProyectoInit.crear( p, null, "en", false );
    igual( 0, otra.cuantos( ProyectoInit.CREADO ), "nada que crear (tests/ ya existe; .gitkeep solo en carpetas nuevas)" );
    igual( "mío", EntornoKettle.leer( new File( p, "README.md" ) ), "nunca pisa" );

    // project_init (MCP) da lo mismo en JSON
    ObjectNode j = LintTools.iniciarProyecto( McpHttpServer.JSON.readTree( "{\"folder\":" + McpHttpServer.JSON
      .writeValueAsString( new File( base, "mcp" ).getAbsolutePath() ) + ",\"dry_run\":true}" ) );
    cierto( j.path( "dry_run" ).asBoolean(), "dry_run" );
    contiene( j.path( "would_create" ).toString(), ".pentaho-live/lint.yaml", "lista lo que crearía" );
    falso( new File( base, "mcp" ).exists(), "sin escribir" );
    Verificador.lanza( McpHttpServer.ToolError.class, () -> LintTools.iniciarProyecto( McpHttpServer.JSON.readTree(
      "{\"folder\":\"relativa\"}" ) ), "folder relativa" );
    // lint_profile con path, sin Spoon
    ObjectNode lp = LintTools.perfilDe( McpHttpServer.JSON.readTree( "{\"path\":" + McpHttpServer.JSON
      .writeValueAsString( new File( p, "etl" ).getAbsolutePath() ) + "}" ) );
    cierto( lp.path( "found" ).asBoolean(), "lint_profile lo encuentra subiendo: " + lp );
    igual( "Curso BD 2026", lp.path( "profile" ).path( "name" ).asText(), "su nombre" );
  }

  /** La CLI: init, profile y lint sobre los ejemplos de la skill con el perfil curso, como el workflow de CI. */
  public void pruebaB_LineaDeComandos() throws Exception {
    File p = new File( base, "cli" );
    Salida s = correr( new File( "." ), "init", p.getAbsolutePath(), "--name", "Curso", "--lang", "en" );
    igual( 0, s.codigo, "init: " + s.err );
    contiene( s.out, "+ .pentaho-live/lint.yaml", "imprime lo creado" );
    s = correr( p, "init" );
    contiene( s.out, "0 created", "segunda vez: nada: " + s.out );

    s = correr( p, "profile" );
    igual( 0, s.codigo, "profile" );
    JsonNode pj = McpHttpServer.JSON.readTree( s.out );
    igual( "Curso", pj.path( "profile" ).path( "name" ).asText(), "profile en JSON" );

    // los ejemplos de la skill en etl/ (con sus datos al lado, como en la skill)
    File skill = new File( EntornoKettle.recursos(), "../../../../claude/skills/pentaho-etl/manual/ejemplos" )
      .getCanonicalFile();
    cierto( skill.isDirectory(), "ejemplos de la skill en " + skill );
    final File etl = new File( p, "etl" );
    final java.nio.file.Path desde = skill.toPath();
    try ( java.util.stream.Stream<java.nio.file.Path> w = Files.walk( desde ) ) {
      for ( java.nio.file.Path x : (Iterable<java.nio.file.Path>) w::iterator ) {
        java.nio.file.Path a = etl.toPath().resolve( desde.relativize( x ).toString() );
        if ( Files.isDirectory( x ) ) {
          Files.createDirectories( a );
        } else if ( !x.toString().contains( "salida" ) ) {
          Files.copy( x, a, StandardCopyOption.REPLACE_EXISTING );
        }
      }
    }
    int n = 0;
    for ( File f : etl.listFiles() ) {
      n += ProyectoMain.esEtl( f ) ? 1 : 0;
    }
    cierto( n >= 9, "ejemplos copiados: " + n );
    File resumen = new File( base, "resumen.md" );
    s = correr( p, "lint", "etl", "--format", "github", "--summary", resumen.getAbsolutePath(), "--lang", "en" );
    System.out.println( s.out );
    igual( 0, s.codigo, "los ejemplos de la skill cumplen el perfil curso (como en la CI): " + s.out + s.err );
    contiene( s.out, "Profile: Curso", "dice el perfil" );
    contiene( s.out, n + " file(s): " + n + " passed", "todos pasan" );
    String md = EntornoKettle.leer( resumen );
    contiene( md, "## Pentaho Live lint", "resumen Markdown" );
    contiene( md, "| `etl/03_ventas_por_producto.ktr` |", "una fila por archivo" );

    s = correr( p, "lint", "etl/03_ventas_por_producto.ktr", "--format", "json", "--lang", "es" );
    igual( 0, s.codigo, "json" );
    JsonNode j = McpHttpServer.JSON.readTree( s.out );
    igual( 1, j.path( "files" ).size(), "un archivo" );
    JsonNode f = j.path( "files" ).path( 0 );
    cierto( f.path( "passed" ).asBoolean(), "pasa" );
    igual( "Curso", f.path( "lint" ).path( "profile" ).path( "name" ).asText(), "profile en el JSON de lint_etl" );
    contiene( f.path( "lint" ).path( "findings" ).toString(), "naming_convention", "las convenciones del curso" );

    s = correr( p, "lint", "etl", "--min-score", "100" );
    igual( 1, s.codigo, "--min-score 100: alguno no llega" );
    contiene( s.out, "FAIL", "lo dice" );
    s = correr( p, "lint", "etl/03_ventas_por_producto.ktr", "--min-score", "100", "--format", "github" );
    contiene( s.out, "::error file=etl/03_ventas_por_producto.ktr,title=lint score::", "anotación de GitHub" );
    s = correr( p, "lint", "etl/03_ventas_por_producto.ktr", "--profile", "none", "--format", "json" );
    igual( 0, s.codigo, "sin perfil ni mínimo: pasa" );
    falso( McpHttpServer.JSON.readTree( s.out ).path( "files" ).path( 0 ).path( "lint" ).has( "profile" ), "--profile none" );

    igual( 2, correr( p, "lint", "etl", "--min-score", "mucho" ).codigo, "uso: --min-score" );
    igual( 2, correr( p, "lint" ).codigo, "uso: sin archivos" );
    igual( 2, correr( p, "lint", "no-existe" ).codigo, "uso: no existe" );
    igual( 2, correr( p, "lint", "etl", "--profile", new File( base, "nada.yaml" ).getAbsolutePath() ).codigo,
      "perfil explícito que no existe" );
    igual( 2, correr( p, "que" ).codigo, "comando desconocido" );
    s = correr( new File( base, "vacio-" + System.nanoTime() ), "lint", "." );
    igual( 2, s.codigo, "carpeta que no existe" );
    File vacia = new File( base, "sin-etl" );
    vacia.mkdirs();
    s = correr( vacia, "lint", "." );
    igual( 0, s.codigo, "nada que revisar: 0" );
    contiene( s.out, "nothing to check", "lo dice" );

    // un perfil roto en el proyecto no deja pasar en silencio
    EntornoKettle.escribir( new File( p, ".pentaho-live/lint.yaml" ), "min_score: [\n" );
    s = correr( p, "lint", "etl/03_ventas_por_producto.ktr" );
    igual( 1, s.codigo, "perfil roto: falla" );
    contiene( s.out, "lint profile", "y dice por qué" );
  }

  /**
   * 0.7.1: lint sin SWT no llena stderr. Kettle escribe por cada plugin con diálogo «Unexpected error registering jar
   * plugin file» y un ClassNotFoundException del diálogo: el filtro tira el par y deja pasar lo demás.
   */
  public void pruebaC_SinRuidoDeKettle() throws Exception {
    String aviso = "2026/10/26 07:31:02 - General - ERROR (version 9.4.0.0-343) : Unexpected error registering jar "
      + "plugin file: file:/pdi/plugins/excel-plugins/excel-plugins-core-9.4.0.0-343.jar";
    String dialogo = "2026/10/26 07:31:02 - General - ERROR (version 9.4.0.0-343) : java.lang.ClassNotFoundException: "
      + "org.pentaho.di.trans.steps.excelinput.ExcelInputDialog\n2026/10/26 07:31:02 - General - \tat java.base/"
      + "jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:581)";
    String ui = "ERROR : java.lang.ClassNotFoundException: org.pentaho.di.ui.trans.steps.delay.DelayDialog";
    String swt = "ERROR : java.lang.NoClassDefFoundError: org/eclipse/swt/widgets/Shell";
    String real = "2026/10/26 07:31:03 - General - ERROR (version 9.4.0.0-343) : java.lang.ClassNotFoundException: "
      + "com.acme.MiStep";
    for ( String r : new String[] { dialogo, ui, swt } ) {
      cierto( ProyectoMain.ruido( r ), "ruido: " + r );
    }
    falso( ProyectoMain.ruido( real ), "una clase que no es de interfaz no es ruido" );
    falso( ProyectoMain.ruido( "Loading transformation from file" ), "un log normal" );
    falso( ProyectoMain.ruido( null ), "null" );

    ByteArrayOutputStream b = new ByteArrayOutputStream();
    PrintStream f = ProyectoMain.SinRuido.sobre( new PrintStream( b, true, "UTF-8" ) );
    f.println( aviso );
    f.println( dialogo );
    f.println( aviso );
    f.println( ui );
    f.flush();
    igual( "", b.toString( "UTF-8" ), "los pares de un PDI sin Spoon no salen" );
    f.println( aviso );
    f.println( real );
    f.println( "fin" );
    f.flush();
    String s = b.toString( "UTF-8" );
    contiene( s, "registering jar plugin file", "un plugin roto por otra cosa: sale el aviso" );
    contiene( s, "com.acme.MiStep", "y su causa" );
    contiene( s, "fin", "y lo que sigue" );
    cierto( s.indexOf( "registering" ) < s.indexOf( "com.acme" ), "en orden" );
    f.println( aviso );
    f.flush();
    cierto( b.toString( "UTF-8" ).endsWith( aviso + System.lineSeparator() ), "un aviso guardado sale con flush" );

    // silenciarRuido pone el filtro en los dos canales de Kettle, una sola vez
    java.io.PrintStream eAntes = org.pentaho.di.core.logging.KettleLogStore.OriginalSystemErr;
    java.io.PrintStream oAntes = org.pentaho.di.core.logging.KettleLogStore.OriginalSystemOut;
    try {
      ProyectoMain m = new ProyectoMain( System.out, new PrintStream( new ByteArrayOutputStream(), true, "UTF-8" ) );
      m.silenciarRuido();
      PrintStream e1 = org.pentaho.di.core.logging.KettleLogStore.OriginalSystemErr;
      cierto( e1 instanceof ProyectoMain.SinRuido, "stderr de Kettle filtrado" );
      cierto( org.pentaho.di.core.logging.KettleLogStore.OriginalSystemOut instanceof ProyectoMain.SinRuido, "y stdout" );
      m.silenciarRuido();
      cierto( e1 == org.pentaho.di.core.logging.KettleLogStore.OriginalSystemErr, "una vez" );
    } finally {
      org.pentaho.di.core.logging.KettleLogStore.OriginalSystemErr = eAntes;
      org.pentaho.di.core.logging.KettleLogStore.OriginalSystemOut = oAntes;
    }

    // los hijos de pentaho-live test copian el flag con el que Java 8 salta los --add-opens del lanzador
    java.util.List<String> fl = CorrectorProcesos.flagsDe( java.util.Arrays.asList( "-XX:+IgnoreUnrecognizedVMOptions",
      "--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xmx1g", "-XX:+UseG1GC" ) );
    igual( java.util.Arrays.asList( "-XX:+IgnoreUnrecognizedVMOptions", "--add-opens=java.base/java.lang=ALL-UNNAMED" ),
      fl, "flags para la JVM hija" );
    // y los dos lanzadores pasan los --add-opens así, sin JDK_JAVA_OPTIONS (Java 9+ avisaba en stderr cada vez)
    File repo = new File( EntornoKettle.recursos(), "../../../.." ).getCanonicalFile();
    for ( String l : new String[] { "launcher/pentaho-live", "launcher/pentaho-live.cmd" } ) {
      String t = EntornoKettle.leer( new File( repo, l ) );
      contiene( t, "-XX:+IgnoreUnrecognizedVMOptions --add-opens=java.base/java.net=ALL-UNNAMED", l );
      noContiene( t.replaceAll( "(?m)^\\s*(#|rem ).*$", "" ), "JDK_JAVA_OPTIONS", l + " sin JDK_JAVA_OPTIONS" );
    }
  }

  /** 0.7.1: la salida de pentaho-live test en en/es/pt (--lang o el idioma del sistema), sin correr JVMs. */
  public void pruebaD_PruebasEnTresIdiomas() throws Exception {
    File p = new File( base, "idiomas" );
    p.mkdirs();
    java.util.List<io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba> rs = resultados( p );
    String[][] esperado = {
      { "en", "  ok    dim_producto  passed in 0.41 s  tests/dim_producto.test.json",
        "  FAIL  kpi  failed in 0.45 s  tests/kpi.test.json",
        "output \"Redondear importe\": 20 changed (rows expected 20, got 20); first: [T01 | Cafés] importe expected "
          + "\"12.50\", got \"10.00\"",
        "table \"dim_tienda\": missing golden", "2 test(s): 1 passed, 1 failed, with errors or not finished.",
        "title=pentaho-live test kpi::Test kpi failed: 1 of 1 outputs differ", "## Pentaho Live tests",
        "1 of 2 did not pass.", "| Test | File | Result | Seconds | Detail |",
        "| kpi | `tests/kpi.test.json` | **failed** | 0.45 |" },
      { "es", "  ok    dim_producto  pasa en 0,41 s  tests/dim_producto.test.json",
        "  FALLA  kpi  falla en 0,45 s  tests/kpi.test.json",
        "salida «Redondear importe»: 20 cambian (filas esperadas 20, obtenidas 20); la primera: [T01 | Cafés] importe "
          + "esperado «12.50», obtenido «10.00»",
        "tabla «dim_tienda»: falta el archivo dorado", "2 prueba(s): 1 pasan, 1 fallan, dan error o no terminaron.",
        "title=pentaho-live test kpi::La prueba kpi falla: 1 of 1 outputs differ", "## Pruebas de Pentaho Live",
        "1 de 2 no pasan.", "| Prueba | Archivo | Resultado | Segundos | Detalle |",
        "| kpi | `tests/kpi.test.json` | **falla** | 0,45 |" },
      { "pt", "  ok    dim_producto  passa em 0,41 s  tests/dim_producto.test.json",
        "  FALHA  kpi  falha em 0,45 s  tests/kpi.test.json",
        "saída \"Redondear importe\": 20 mudam (linhas esperadas 20, obtidas 20); a primeira: [T01 | Cafés] importe "
          + "esperado \"12.50\", obtido \"10.00\"",
        "tabela \"dim_tienda\": falta o arquivo dourado", "2 teste(s): 1 passam, 1 falham, dão erro ou não terminaram.",
        "title=pentaho-live test kpi::O teste kpi falha: 1 of 1 outputs differ", "## Testes do Pentaho Live",
        "1 de 2 não passam.", "| Teste | Arquivo | Resultado | Segundos | Detalhe |",
        "| kpi | `tests/kpi.test.json` | **falha** | 0,45 |" } };
    for ( String[] e : esperado ) {
      io.github.ivi2511.pentahomcp.informes.Rotulos t = io.github.ivi2511.pentahomcp.informes.Rotulos.de(
        ProyectoMain.AREA, e[ 0 ] );
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      ProyectoMain m = new ProyectoMain( new PrintStream( o, true, "UTF-8" ), System.err );
      m.cwd = p.getAbsoluteFile();
      File resumen = new File( base, "resumen-" + e[ 0 ] + ".md" );
      resumen.delete();
      m.githubPruebas( rs, t );
      m.textoPruebas( rs, t );
      m.escribirResumenPruebas( resumen.getAbsolutePath(), rs, t );
      String s = o.toString( "UTF-8" ).replace( "\r\n", "\n" ) + EntornoKettle.leer( resumen );
      for ( int i = 1; i < e.length; i++ ) {
        contiene( s, e[ i ], e[ 0 ] + ": " + e[ i ] + "\n" + s );
      }
      noContiene( s, "::error file=tests/dim_producto", e[ 0 ] + ": sin anotación para la que pasa" );
    }
    // --lang en el comando (sin pruebas no arranca ninguna JVM); sin --lang, el idioma del sistema
    Salida s = correr( p, "test", "--lang", "es" );
    igual( 0, s.codigo, "sin pruebas: 0" );
    contiene( s.out, "no hay pruebas (*.test.json)", "en español" );
    s = correr( p, "test", "--lang", "pt", "--summary", new File( base, "vacio-pt.md" ).getAbsolutePath() );
    contiene( s.out, "nenhum teste", "em português" );
    contiene( EntornoKettle.leer( new File( base, "vacio-pt.md" ) ), "Nenhum teste com datasets dourados", "o resumo" );
    s = correr( p, "test", "--lang", "en" );
    contiene( s.out, "nothing to run", "in English" );
    java.util.Locale antes = java.util.Locale.getDefault();
    try {
      java.util.Locale.setDefault( new java.util.Locale( "es", "ES" ) );
      contiene( correr( p, "test" ).out, "nada que ejecutar", "el idioma del sistema" );
    } finally {
      java.util.Locale.setDefault( antes );
    }
    igual( 2, correr( p, "test", "--lang" ).codigo, "--lang sin valor" );
  }

  /** Una que pasa y una que falla con una celda distinta y una tabla sin dorado. */
  private static java.util.List<io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba> resultados( File p ) {
    io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba ok =
      new io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba();
    ok.prueba = "dim_producto";
    ok.estado = io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.PASO;
    ok.segundos = 0.41;
    ok.archivoPrueba = new File( p, "tests/dim_producto.test.json" ).getAbsolutePath();
    io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba mal =
      new io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba();
    mal.prueba = "kpi";
    mal.estado = io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.FALLO;
    mal.mensaje = "1 of 1 outputs differ";
    mal.segundos = 0.45;
    mal.archivoPrueba = new File( p, "tests/kpi.test.json" ).getAbsolutePath();
    io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.Resumen sa =
      new io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.Resumen( "Redondear importe",
        io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.Resumen.STEP );
    sa.distintas = 20;
    sa.filasEsperadas = 20;
    sa.filasReales = 20;
    io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.Resumen ta =
      new io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.Resumen( "dim_tienda",
        io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.Resumen.TABLA );
    ta.problema = io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba.Resumen.SIN_DORADO;
    mal.salidas.add( sa );
    mal.salidas.add( ta );
    io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion c = new io.github.ivi2511.pentahomcp.corrector.modelo
      .Comparacion();
    c.ejemplos.distintas.add( io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion.Ejemplo.diferencia(
      "T01 | Cafés", "importe", "12.50", "10.00" ) );
    mal.caso = new io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso();
    mal.caso.tablas = new java.util.ArrayList<io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso
      .ComparacionTabla>();
    mal.caso.tablas.add( new io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso.ComparacionTabla(
      "Redondear importe", null, c ) );
    return java.util.Arrays.asList( ok, mal );
  }

  /**
   * 0.7.1, lo que mostró la CI de verdad: el JSON input de Grano de Oro tiene defaultPathLeafToNull=Y, un tag con «path»
   * que no es una ruta; required_variable avisaba «la ruta de 'Leer tiendas (JSON)' no empieza con ${DIR_DATOS}: Y».
   */
  public void pruebaE_UnInterruptorNoEsUnaRuta() throws Exception {
    File p = new File( base, "grano" );
    ProyectoInit.crear( p, "Grano", "en", false );
    File ktr = new File( EntornoKettle.recursos(), "../../../../ejemplo/solucion-referencia/01_dim_tienda.ktr" )
      .getCanonicalFile();
    cierto( ktr.isFile(), "la solución de Grano de Oro: " + ktr );
    contiene( EntornoKettle.leer( ktr ), "<defaultPathLeafToNull>Y</defaultPathLeafToNull>", "el caso de la CI" );
    Files.copy( ktr.toPath(), new File( p, "etl/01_dim_tienda.ktr" ).toPath(), StandardCopyOption.REPLACE_EXISTING );
    Salida s = correr( p, "lint", "etl/01_dim_tienda.ktr", "--format", "json", "--lang", "en" );
    igual( 0, s.codigo, "pasa: " + s.out + s.err );
    JsonNode fs = McpHttpServer.JSON.readTree( s.out ).path( "files" ).path( 0 ).path( "lint" ).path( "findings" );
    for ( JsonNode h : fs ) {
      if ( "required_variable".equals( h.path( "rule" ).asText() ) ) {
        noContiene( h.path( "message" ).asText(), ": Y.", "un Y/N no es una ruta: " + h );
      }
    }
    noContiene( fs.toString(), "defaultPathLeafToNull", "ningún hallazgo sobre el interruptor" );
  }

  /**
   * 0.7.1: la guía 13 es el recurso pentaho://guide/team-projects (dentro del jar: build.ps1 la copia) y cuenta cómo leer
   * la CI de verdad; build.ps1 y McpResources nombran las mismas guías; el texto del diálogo que ya no se ve no está.
   */
  public void pruebaF_GuiaYPlantillas() throws Exception {
    McpResources r = new McpResources();
    contiene( r.listar( null ).toString(), "pentaho://guide/team-projects", "resources/list trae la guía 13" );
    String g = r.leer( "pentaho://guide/team-projects" ).path( "contents" ).path( 0 ).path( "text" ).asText();
    for ( String n : new String[] { "pentaho-live init", "pentaho-live test", "Summary", "::error",
      "PENTAHO_LIVE_PR_COMMENT" } ) {
      contiene( g, n, "la guía 13 nombra " + n );
    }
    // las guías que lee McpResources = las que build.ps1 mete en el jar
    java.util.Set<String> enRecursos = new java.util.TreeSet<String>();
    java.lang.reflect.Field docs = McpResources.class.getDeclaredField( "DOCS" );
    docs.setAccessible( true );
    for ( Object d : (Object[]) docs.get( null ) ) {
      java.lang.reflect.Field ruta = d.getClass().getDeclaredField( "ruta" );
      ruta.setAccessible( true );
      String x = (String) ruta.get( d );
      if ( x != null && x.startsWith( "manual/" ) ) {
        enRecursos.add( x.substring( "manual/".length() ) );
      }
    }
    File repo = new File( EntornoKettle.recursos(), "../../../.." ).getCanonicalFile();
    String build = EntornoKettle.leer( new File( repo, "spoon-plugin/build.ps1" ) );
    java.util.Set<String> enBuild = new java.util.TreeSet<String>();
    java.util.regex.Matcher mt = java.util.regex.Pattern.compile( "\"manual\\\\(\\d\\d-[\\w-]+\\.md)\"" ).matcher( build );
    while ( mt.find() ) {
      enBuild.add( mt.group( 1 ) );
    }
    cierto( enRecursos.contains( "13-proyecto.md" ), "13-proyecto.md es recurso: " + enRecursos );
    igual( enRecursos, enBuild, "build.ps1 copia al jar las guías que McpResources lee" );
    for ( String m : enBuild ) {
      cierto( new File( repo, "claude/skills/pentaho-etl/manual/" + m ).isFile(), "existe " + m );
    }

    // el README que deja init cuenta cómo leer la CI (en los tres idiomas)
    for ( String l : new String[] { "en", "es", "pt" } ) {
      File q = new File( base, "readme-" + l );
      ProyectoInit.crear( q, "X", l, false );
      String readme = EntornoKettle.leer( new File( q, "README.md" ) );
      contiene( readme, "manual/13-proyecto.md", l + ": enlaza la guía 13" );
      contiene( readme, "::error", l + ": las anotaciones" );
      contiene( readme, "pentaho-live test --all --lang", l + ": las pruebas en su idioma" );
    }
    // «Preparar proyecto…»: ya no hay plugin sin textconv, así que su aviso se fue con sus claves
    for ( String l : new String[] { "en", "es", "pt" } ) {
      falso( io.github.ivi2511.pentahomcp.informes.Rotulos.de( IdcMenu.AREA, l ).tiene( "p_textconv_off" ),
        l + ": sin p_textconv_off" );
    }
  }

  // ---------------------------------------------------------------- ayudas

  static final class Salida {
    int codigo;
    String out;
    String err;
  }

  private static Salida correr( File cwd, String... args ) throws Exception {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    ByteArrayOutputStream e = new ByteArrayOutputStream();
    ProyectoMain m = new ProyectoMain( new PrintStream( o, true, "UTF-8" ), new PrintStream( e, true, "UTF-8" ) );
    m.cwd = cwd.getAbsoluteFile();
    m.kettleListo = true;
    Salida s = new Salida();
    s.codigo = m.correr( args );
    s.out = o.toString( "UTF-8" );
    s.err = e.toString( "UTF-8" );
    noNulo( s.out, "salida" );
    return s;
  }
}
