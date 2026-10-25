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
