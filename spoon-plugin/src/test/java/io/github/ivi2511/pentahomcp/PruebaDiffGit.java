package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.diff.Diff;
import io.github.ivi2511.pentahomcp.diff.Git;
import io.github.ivi2511.pentahomcp.diff.LineaComandosPrueba;
import io.github.ivi2511.pentahomcp.diff.Normalizador;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * El diff contra git y la línea de comandos (diseño E1 y E2), en un repositorio de usar y tirar dentro de la carpeta
 * de la prueba (git del sistema; sin git, se saltean): {@code pentaho-live diff --rev}, dos archivos, JSON,
 * {@code textconv} conectado a {@code git diff} con un {@code .gitattributes}, {@code normalize --check}, el hook de
 * pre-commit, la herramienta MCP {@code diff_documents} con {@code path} y la sección «changes» de Informes. Nunca
 * toca la configuración global de git: la identidad del commit va con {@code -c}.
 */
public class PruebaDiffGit {

  private File repo;
  private File ktr;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    if ( !Git.disponible() ) {
      Verificador.saltear( "no hay git en esta máquina" );
    }
    repo = EntornoKettle.salida( "diff-git/repo" );
    // los objetos de git son de solo lectura: se habilitan para poder borrar la corrida anterior
    try ( java.util.stream.Stream<java.nio.file.Path> s = Files.walk( repo.toPath() ) ) {
      s.forEach( p -> p.toFile().setWritable( true ) );
    }
    EntornoKettle.borrar( repo.toPath() );
    repo.mkdirs();
    git( "init", "-q" );
    ktr = new File( repo, "etl/02_filtrar_clientes.ktr" );
    String x = PruebaDiff.ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    EntornoKettle.escribir( ktr, x );
    git( "add", "." );
    commit( "primera versión" );
    // commit 2: la condición del filtro
    EntornoKettle.escribir( ktr, PruebaDiff.cambiar( x, "<function>&gt;=</function>", "<function>&gt;</function>" ) );
    git( "add", "." );
    commit( "filtro estricto" );
    // sin commitear: un step renombrado
    EntornoKettle.escribir( ktr, PruebaDiff.cambiar( EntornoKettle.leer( ktr ), "Elegir columnas",
      "Columnas finales" ) );
  }

  private String git( String... args ) throws Exception {
    List<String> cmd = new ArrayList<String>();
    cmd.add( "git" );
    cmd.addAll( Arrays.asList( args ) );
    ProcessBuilder pb = new ProcessBuilder( cmd ).directory( repo ).redirectErrorStream( true );
    pb.environment().put( "GIT_CONFIG_NOSYSTEM", "1" );
    Process p = pb.start();
    p.getOutputStream().close();
    byte[] out = leerTodo( p );
    cierto( p.waitFor( 60, TimeUnit.SECONDS ), "git " + cmd + " termina" );
    String s = new String( out, StandardCharsets.UTF_8 );
    igual( 0, p.exitValue(), "git " + cmd + ": " + s );
    return s;
  }

  private static byte[] leerTodo( Process p ) throws Exception {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] b = new byte[ 8192 ];
    int n;
    while ( ( n = p.getInputStream().read( b ) ) > 0 ) {
      bo.write( b, 0, n );
    }
    return bo.toByteArray();
  }

  private void commit( String msg ) throws Exception {
    git( "-c", "user.name=Prueba", "-c", "user.email=prueba@example.invalid", "-c", "commit.gpgsign=false",
      "commit", "-q", "--no-verify", "-m", msg );
  }

  /** Corre un comando de pentaho-live en el proceso: {código, salida, error}. */
  private Object[] cli( String... args ) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    ByteArrayOutputStream e = new ByteArrayOutputStream();
    int c = LineaComandosPrueba.correr( repo, new PrintStream( o, true ), new PrintStream( e, true ), args );
    return new Object[] { c, new String( o.toByteArray(), StandardCharsets.UTF_8 ), new String( e.toByteArray(),
      StandardCharsets.UTF_8 ) };
  }

  /** diff contra HEAD, HEAD~1 y entre dos archivos; JSON; códigos de salida. */
  public void pruebaA_DiffConsola() throws Exception {
    Object[] r = cli( "diff", "--lang", "es", "etl/02_filtrar_clientes.ktr" );
    igual( 1, r[ 0 ], "con diferencias: " + r[ 2 ] );
    contiene( (String) r[ 1 ], "» Columnas finales (SelectValues), renombrado, antes «Elegir columnas»", "contra HEAD" );
    noContiene( (String) r[ 1 ], "condición", "la condición ya está en HEAD" );
    contiene( (String) r[ 1 ], "antes: git HEAD: 02_filtrar_clientes.ktr", "etiqueta de git" );
    r = cli( "diff", "--lang", "es", "--rev", "HEAD~1", "etl/02_filtrar_clientes.ktr" );
    contiene( (String) r[ 1 ], "condición: saldo >= 1000.0 AND activo = 'S' → saldo > 1000.0 AND activo = 'S'",
      "contra HEAD~1" );
    r = cli( "diff", "--format", "json", "git:HEAD~1", "etl/02_filtrar_clientes.ktr" );
    JsonNode j = McpHttpServer.JSON.readTree( (String) r[ 1 ] );
    igual( 2, j.path( "changes" ).size(), "json: dos cambios" );
    File copia = new File( repo, "otra.ktr" );
    Files.copy( ktr.toPath(), copia.toPath() );
    r = cli( "diff", "otra.ktr", "etl/02_filtrar_clientes.ktr" );
    igual( 0, r[ 0 ], "dos archivos iguales: código 0" );
    r = cli( "diff", "--format", "markdown", "--lang", "en", "--rev", "HEAD~1", "etl/02_filtrar_clientes.ktr" );
    contiene( (String) r[ 1 ], "### Changes in the transformation", "markdown" );
    r = cli( "diff", "--rev", "no-existe", "etl/02_filtrar_clientes.ktr" );
    igual( 2, r[ 0 ], "revisión que no existe: error" );
    r = cli( "diff", "--rev", "--output=x", "etl/02_filtrar_clientes.ktr" );
    igual( 2, r[ 0 ], "una revisión que parece una opción no llega a git" );
    Files.delete( copia.toPath() );
  }

  /** textconv conectado a git diff con un .gitattributes: git muestra la vista semántica. */
  public void pruebaB_TextconvEnGit() throws Exception {
    Object[] r = cli( "textconv", "--lang", "en", "etl/02_filtrar_clientes.ktr" );
    igual( 0, r[ 0 ], "textconv" );
    contiene( (String) r[ 1 ], "  Columnas finales [SelectValues]", "vista semántica" );
    File roto = new File( repo, "roto.ktr" );
    EntornoKettle.escribir( roto, "<<<<<<< HEAD\n<transformation>" );
    r = cli( "textconv", "roto.ktr" );
    igual( 0, r[ 0 ], "XML roto: no falla" );
    contiene( (String) r[ 1 ], "<<<<<<< HEAD", "y muestra el texto tal cual" );
    Files.delete( roto.toPath() );

    File clases = new File( Normalizador.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
    String java = new File( System.getProperty( "java.home" ), "bin/java" ).getPath().replace( '\\', '/' );
    // como «pentaho-live textconv»: un script que llama a la clase (las comillas no pasan bien por -c en Windows)
    File script = new File( repo, ".pl-textconv.sh" );
    EntornoKettle.escribir( script, "#!/bin/sh\nexec \"" + java + "\" -cp \"" + clases.getPath().replace( '\\', '/' )
      + "\" io.github.ivi2511.pentahomcp.diff.LineaComandos textconv --lang en \"$@\"\n" );
    EntornoKettle.escribir( new File( repo, ".gitattributes" ), "*.ktr diff=kettle\n*.kjb diff=kettle\n" );
    String d = git( "-c", "diff.kettle.textconv=sh .pl-textconv.sh", "diff", "HEAD~1", "HEAD", "--",
      "etl/02_filtrar_clientes.ktr" );
    contiene( d, "-    condition: saldo >= 1000.0 AND activo = 'S'", "línea quitada en git diff: " + d );
    contiene( d, "+    condition: saldo > 1000.0 AND activo = 'S'", "línea agregada en git diff" );
    noContiene( d, "&gt;", "sin XML" );
    Files.delete( new File( repo, ".gitattributes" ).toPath() );
    Files.delete( script.toPath() );
  }

  /**
   * textconv y contraseñas (0.7.1): una contraseña cambiada se ve en git diff como una marca distinta, nunca el valor
   * ni su hash; la misma contraseña da la misma marca; la clave de las marcas vive en el .git del clon.
   */
  public void pruebaD_TextconvMarcaLaContrasenaCambiada() throws Exception {
    File otro = EntornoKettle.salida( "diff-git/repo-pass" );
    if ( otro.exists() ) {
      try ( java.util.stream.Stream<java.nio.file.Path> s = Files.walk( otro.toPath() ) ) {
        s.forEach( p -> p.toFile().setWritable( true ) );
      }
      EntornoKettle.borrar( otro.toPath() );
    }
    otro.mkdirs();
    File guardado = repo;
    repo = otro;
    try {
      git( "init", "-q" );
      String x = PruebaDiff.ejemplo( "manual/ejemplos/07_conexion_sqlite.ktr" );
      cierto( x.contains( "<password" ), "el ejemplo tiene el tag password" );
      String uno = x.replaceFirst( "<password/>|<password>[^<]*</password>", "<password>Encrypted 2be98afc86aa7f2e4cb79ce10df90acde</password>" );
      String dos = x.replaceFirst( "<password/>|<password>[^<]*</password>", "<password>Encrypted 2be98afc86aa7f2e4bb18bd63c99dbdde</password>" );
      File k = new File( otro, "etl/con_clave.ktr" );
      EntornoKettle.escribir( k, uno );
      git( "add", "." );
      commit( "con clave" );
      Object[] r = cli( "textconv", "--lang", "en", "etl/con_clave.ktr" );
      String t1 = (String) r[ 1 ];
      java.util.regex.Matcher mt = java.util.regex.Pattern.compile( "password: \\(hidden · #([0-9a-f]{8})\\)" ).matcher(
        t1 );
      cierto( mt.find(), "la contraseña sale con su marca: " + t1 );
      String m1 = mt.group( 1 );
      noContiene( t1, "2be98afc86aa7f2e4cb79ce10df90acde", "nunca el valor cifrado" );
      noContiene( t1, io.github.ivi2511.pentahomcp.diff.Aplanar.secreto(
        "Encrypted 2be98afc86aa7f2e4cb79ce10df90acde" ).substring( 8, 20 ), "ni el hash" );
      cierto( new File( otro, ".git/pentaho-live/textconv.key" ).isFile(), "la clave quedó dentro de .git" );
      igual( (String) cli( "textconv", "--lang", "en", "etl/con_clave.ktr" )[ 1 ], t1, "misma contraseña, misma marca" );
      EntornoKettle.escribir( k, dos );
      String t2 = (String) cli( "textconv", "--lang", "en", "etl/con_clave.ktr" )[ 1 ];
      cierto( !t2.contains( "#" + m1 ), "otra contraseña, otra marca" );
      // en git diff: la línea de la contraseña cambia
      File clases = new File( Normalizador.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
      String java = new File( System.getProperty( "java.home" ), "bin/java" ).getPath().replace( '\\', '/' );
      File script = new File( otro, ".pl-textconv.sh" );
      EntornoKettle.escribir( script, "#!/bin/sh\nexec \"" + java + "\" -cp \"" + clases.getPath().replace( '\\', '/' )
        + "\" io.github.ivi2511.pentahomcp.diff.LineaComandos textconv --lang en \"$@\"\n" );
      EntornoKettle.escribir( new File( otro, ".gitattributes" ), "*.ktr diff=kettle\n" );
      String d = git( "-c", "diff.kettle.textconv=sh .pl-textconv.sh", "diff", "--", "etl/con_clave.ktr" );
      contiene( d, "-    password: (hidden · #" + m1 + ")", "la de antes en git diff: " + d );
      contiene( d, "+    password: (hidden · #", "la de ahora en git diff" );
      noContiene( d, "Encrypted", "sin el valor" );
    } finally {
      repo = guardado;
    }
  }

  /** normalize --check, normalize y el hook de pre-commit (solo si no hay cambios sin preparar). */
  public void pruebaC_NormalizarYPreCommit() throws Exception {
    File crudo = new File( repo, "etl/crudo.ktr" );
    String x = PruebaDiff.ejemplo( "manual/ejemplos/06_errores_y_parametros.ktr" );
    EntornoKettle.escribir( crudo, x );
    Object[] r = cli( "normalize", "--check", "etl" );
    igual( 1, r[ 0 ], "--check: hay archivos sin normalizar" );
    contiene( (String) r[ 1 ], "not normalized:", "lista los archivos" );
    igual( x, EntornoKettle.leer( crudo ), "--check no escribe" );
    // el hook: el preparado se normaliza y se vuelve a preparar
    git( "add", "etl/crudo.ktr" );
    r = cli( "pre-commit" );
    igual( 0, r[ 0 ], "pre-commit: " + r[ 2 ] );
    contiene( (String) r[ 1 ], "normalized etl/crudo.ktr", "lo dice" );
    String preparado = git( "show", ":etl/crudo.ktr" );
    igual( Normalizador.normalizar( x ), preparado, "lo preparado está normalizado" );
    igual( preparado, EntornoKettle.leer( crudo ), "y el archivo también" );
    // con cambios sin preparar no lo toca y corta
    File otro = new File( repo, "etl/otro.ktr" );
    EntornoKettle.escribir( otro, x );
    git( "add", "etl/otro.ktr" );
    EntornoKettle.escribir( otro, x.replace( "SALDO_MINIMO", "SALDO_MIN" ) );
    r = cli( "pre-commit" );
    igual( 1, r[ 0 ], "corta el commit" );
    contiene( (String) r[ 2 ], "has unstaged changes", "y explica" );
    igual( x.replace( "SALDO_MINIMO", "SALDO_MIN" ), EntornoKettle.leer( otro ), "no tocó el archivo" );
    git( "reset", "-q", "--", "etl/otro.ktr", "etl/crudo.ktr" );
    Files.delete( otro.toPath() );
    Files.delete( crudo.toPath() );
    // normalize escribe y después --check da 0
    File n = new File( repo, "norm/a.ktr" );
    EntornoKettle.escribir( n, x );
    igual( 0, cli( "normalize", "norm" )[ 0 ], "normalize" );
    igual( 0, cli( "normalize", "--check", "norm/a.ktr" )[ 0 ], "ya normalizado" );
    EntornoKettle.borrar( new File( repo, "norm" ).toPath() );
    contiene( (String) cli( "git-setup" )[ 1 ], "git config diff.kettle.textconv \"pentaho-live textconv\"",
      "git-setup" );
  }

  /** La herramienta MCP con path: contra git:HEAD por defecto; formatos; 'saved' solo con un documento abierto. */
  public void pruebaD_HerramientaMcpConPath() throws Exception {
    ObjectNode a = Tools.obj().put( "path", ktr.getPath() ).put( "language", "es" );
    JsonNode o = (JsonNode) DiffTools.diff( a );
    falso( o.path( "identical" ).asBoolean(), "hay cambios" );
    igual( "renamed", o.path( "changes" ).get( 0 ).path( "change" ).asText(), "el renombre" );
    contiene( o.path( "text" ).asText(), "Columnas finales", "con el texto" );
    String md = (String) DiffTools.diff( a.deepCopy().put( "format", "markdown" ).put( "compare_with", "git:HEAD~1" ) );
    contiene( md, "condición", "markdown contra HEAD~1" );
    try {
      DiffTools.diff( a.deepCopy().put( "compare_with", "saved" ) );
      throw new AssertionError( "saved con path" );
    } catch ( McpHttpServer.ToolError e ) {
      contiene( e.getMessage(), "open document", "saved es para un documento abierto" );
    }
    try {
      DiffTools.diff( a.deepCopy().put( "show_on_canvas", true ) );
      throw new AssertionError( "show_on_canvas con path" );
    } catch ( McpHttpServer.ToolError e ) {
      contiene( e.getMessage(), "open document", "show_on_canvas pide un documento abierto" );
    }
    // un archivo fuera de git
    File suelto = new File( EntornoKettle.salida( "diff-git" ), "suelto.ktr" );
    Files.copy( ktr.toPath(), suelto.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING );
    try {
      DiffTools.diff( Tools.obj().put( "path", suelto.getPath() ) );
      throw new AssertionError( "fuera de git" );
    } catch ( McpHttpServer.ToolError e ) {
      contiene( e.getMessage(), "git", "dice que no está en git: " + e.getMessage() );
    }
    JsonNode igualAs = (JsonNode) DiffTools.diff( Tools.obj().put( "path", suelto.getPath() ).put( "compare_with",
      ktr.getPath() ) );
    cierto( igualAs.path( "identical" ).asBoolean(), "contra otro archivo igual" );
  }

  /** La sección «changes» de Informes: contra git HEAD por defecto, o lo que diga compare_with. */
  public void pruebaE_SeccionCambios() throws Exception {
    Captura c = new Captura();
    c.copia = new TransMeta( ktr.getAbsolutePath() );
    c.archivo = ktr.getAbsolutePath();
    c.docId = "prueba:diff";
    c.nombre = c.copia.getName();
    c.tipo = Captura.TRANSFORMACION;
    c.capturado = new Date();
    Pedido p = new Pedido();
    p.idioma = "es";
    p.secciones = Arrays.asList( SeccionId.CAMBIOS );
    SecCambios sec = new SecCambios();
    igual( null, sec.noAplica( c, "es" ), "aplica: tiene archivo" );
    Seccion s = sec.armar( new ContextoPrueba( c, p, new Tema() ) );
    igual( Seccion.OK, s.estado, "estado: " + s.motivo );
    igual( "Cambios", s.titulo, "título" );
    contiene( s.resumen, "Comparado con git HEAD", "resumen: " + s.resumen );
    Tabla t = (Tabla) s.bloques.get( 0 );
    igual( 1, t.filas.size(), "una fila" );
    contiene( t.filas.get( 0 ).get( 1 ), "Columnas finales", "el renombre" );
    igual( "changes", s.datos.path( "section" ).asText(), "datos" );
    p.compararCon = "git:HEAD~1";
    Seccion s2 = sec.armar( new ContextoPrueba( c, p, new Tema() ) );
    igual( 2, ( (Tabla) s2.bloques.get( 0 ) ).filas.size(), "contra HEAD~1, dos cambios" );
    // sin archivo no aplica
    Captura sin = new Captura();
    contiene( sec.noAplica( sin, "es" ), "No hay con qué comparar", "sin archivo" );
    // contra el mismo archivo: sin cambios
    p.compararCon = ktr.getAbsolutePath();
    Seccion s3 = sec.armar( new ContextoPrueba( c, p, new Tema() ) );
    contiene( s3.resumen, "No hay diferencias de significado", "sin cambios" );
    // la referencia a git, también desde la fachada
    igual( "HEAD~2", Diff.revision( "git:HEAD~2" ), "git:REV" );
    igual( "HEAD", Diff.revision( "HEAD" ), "HEAD solo" );
    igual( null, Diff.revision( "C:/x.ktr" ), "una ruta" );
  }

  /**
   * {@code pentaho-live diff --rev REV} sin un archivo (P4, el paso de la CI de una pull request): cada .ktr/.kjb que
   * cambió desde la revisión, el nuevo y el borrado con una línea; markdown y JSON; códigos 0, 1 y 2.
   */
  public void pruebaF_DiffDeLosCambiados() throws Exception {
    String x = PruebaDiff.ejemplo( "manual/ejemplos/06_errores_y_parametros.ktr" );
    File borrar = new File( repo, "etl/borrar.ktr" );
    EntornoKettle.escribir( borrar, x );
    git( "add", "etl/borrar.ktr" );
    commit( "uno para borrar" );
    Files.delete( borrar.toPath() );
    EntornoKettle.escribir( new File( repo, "etl/nuevo.ktr" ), x );
    git( "add", "etl/nuevo.ktr" );
    EntornoKettle.escribir( new File( repo, "notas/leeme.txt" ), "sin ETL" );

    Object[] r = cli( "diff", "--rev", "HEAD", "--format", "markdown", "--lang", "en" );
    igual( 1, r[ 0 ], "con cambios: " + r[ 2 ] );
    String md = (String) r[ 1 ];
    contiene( md, "## Pentaho Live: semantic diff against HEAD", "título" );
    contiene( md, "3 .ktr/.kjb files changed.", "cuántos" );
    contiene( md, "`etl/02_filtrar_clientes.ktr`\n\n### Changes in the transformation", "el cambiado, por significado" );
    contiene( md, "Columnas finales", "con el renombre" );
    contiene( md, "### `etl/nuevo.ktr`\n\nNew transformation with", "el nuevo en una línea" );
    contiene( md, "### `etl/borrar.ktr`\n\nTransformation removed.", "el borrado" );
    noContiene( md, "&gt;", "sin XML" );

    r = cli( "diff", "--rev", "HEAD", "--format", "json", "etl" );
    igual( 1, r[ 0 ], "json: " + r[ 2 ] );
    JsonNode j = McpHttpServer.JSON.readTree( (String) r[ 1 ] );
    igual( 3, j.path( "count" ).asInt(), "tres archivos" );
    List<String> estados = new ArrayList<String>();
    for ( JsonNode f : j.path( "files" ) ) {
      estados.add( f.path( "file" ).asText() + "=" + f.path( "status" ).asText() );
    }
    igual( Arrays.asList( "etl/02_filtrar_clientes.ktr=modified", "etl/borrar.ktr=removed", "etl/nuevo.ktr=added" ),
      estados, "estados" );
    igual( "renamed", j.path( "files" ).get( 0 ).path( "diff" ).path( "changes" ).get( 0 ).path( "change" ).asText(),
      "el diff del cambiado, el de diff_documents" );

    r = cli( "diff", "--rev", "HEAD", "--lang", "es", "notas" );
    igual( 0, r[ 0 ], "carpeta sin .ktr cambiados: 0" );
    contiene( (String) r[ 1 ], "Ningún .ktr/.kjb cambió respecto de HEAD.", "lo dice" );
    igual( 1, cli( "diff" )[ 0 ], "sin argumentos: contra HEAD" );
    igual( 2, cli( "diff", "--rev", "no-existe", "etl" )[ 0 ], "revisión que no existe" );
    igual( 2, cli( "diff", "--rev", "HEAD", "no-existe" )[ 0 ], "ruta que no existe" );

    git( "rm", "-q", "--cached", "etl/nuevo.ktr" );
    Files.delete( new File( repo, "etl/nuevo.ktr" ).toPath() );
    EntornoKettle.borrar( new File( repo, "notas" ).toPath() );
  }
}
