package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.trans.TransMeta;

/**
 * stress_test de punta a punta, headless (docs/robustez-diseno.md §5): una JVM hija de verdad corre las copias de los
 * .ktr sintéticos de src/test/resources/estres. La transformación frágil falla con una explicación clara (aborta por
 * una conversión sin hop de error; un filtro sin rama falsa pierde filas en silencio, con su nombre); su versión
 * robustecida pasa; nada se escribe fuera del sandbox temporal; una transformación con MySQL no corre y no se conecta,
 * pero export_variants deja los archivos rotos.
 *
 * @dueño R2c
 */
public class PruebaEstres {

  private File dir;
  private File sandbox;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    dir = new File( EntornoKettle.carpeta(), "estres" );
    EstresTools.borrar( dir );
    copiar( new File( EntornoKettle.recursos(), "estres" ).toPath(), dir.toPath() );
    sandbox = new File( EntornoKettle.carpeta(), "estres-sandbox" );
    EstresTools.borrar( sandbox );
  }

  private static void copiar( Path de, Path a ) throws IOException {
    try ( Stream<Path> s = Files.walk( de ) ) {
      for ( Path p : (Iterable<Path>) s::iterator ) {
        Path d = a.resolve( de.relativize( p ).toString() );
        if ( Files.isDirectory( p ) ) {
          Files.createDirectories( d );
        } else {
          Files.copy( p, d, StandardCopyOption.REPLACE_EXISTING );
        }
      }
    }
  }

  /** Ruta relativa → tamaño y fecha: para comprobar que nada cambió junto a los originales. */
  private static Map<String, String> foto( File raiz ) throws IOException {
    Map<String, String> m = new TreeMap<String, String>();
    try ( Stream<Path> s = Files.walk( raiz.toPath() ) ) {
      for ( Path p : (Iterable<Path>) s::iterator ) {
        File f = p.toFile();
        m.put( raiz.toPath().relativize( p ).toString(), f.isDirectory() ? "dir" : f.length() + "@" + f.lastModified() );
      }
    }
    return m;
  }

  private ObjectNode probar( String ktr, String... variantes ) throws Exception {
    TransMeta tm = new TransMeta( new File( dir, ktr ).getAbsolutePath() );
    EstresTools.Pedido p = new EstresTools.Pedido();
    p.variantes = variantes.length == 0 ? null : Arrays.asList( variantes );
    p.timeoutS = 60;
    p.base = sandbox;
    ObjectNode o = EstresTools.ejecutar( tm, ktr, p );
    System.out.println( "    stress_test " + ktr + ": " + RunMotor.json( o ).replaceAll( "\\s+", " " ) );
    return o;
  }

  private static JsonNode variante( JsonNode o, String id ) {
    for ( JsonNode v : o.path( "variants" ) ) {
      if ( id.equals( v.path( "id" ).asText() ) ) {
        return v;
      }
    }
    throw new AssertionError( "no variant " + id + " in " + o );
  }

  private static JsonNode check( JsonNode v, String id ) {
    for ( JsonNode c : v.path( "checks" ) ) {
      if ( id.equals( c.path( "id" ).asText() ) ) {
        return c;
      }
    }
    throw new AssertionError( "no check " + id + " in " + v );
  }

  public void pruebaFragilFallaConExplicacion() throws Exception {
    Map<String, String> antes = foto( dir );
    ObjectNode o = probar( "fragile_load.ktr", "combined", "missing_fields" );
    cierto( o.path( "ran" ).asBoolean(), "corrió: " + o );
    igual( "fragile", o.path( "verdict" ).asText(), "veredicto" );

    JsonNode comb = variante( o, "combined" );
    JsonNode abort = check( comb, "no_abort" );
    igual( "fail", abort.path( "status" ).asText(), "combined aborta: " + comb );
    contiene( abort.path( "message" ).asText(), "aborted", "dice que abortó" );
    contiene( abort.toString(), "Convert types", "y nombra el step que falló" );

    JsonNode faltan = variante( o, "missing_fields" );
    igual( "pass", check( faltan, "no_abort" ).path( "status" ).asText(), "missing_fields no aborta: " + faltan );
    JsonNode perdida = check( faltan, "no_silent_loss" );
    igual( "fail", perdida.path( "status" ).asText(), "pero pierde filas en silencio: " + faltan );
    contiene( perdida.toString(), "'Check required' (FilterRows) lost 5 row(s)", "nombra el filtro y cuántas" );
    contiene( faltan.path( "summary" ).asText(), "5 lost", "resumen con las perdidas" );
    contiene( o.path( "next" ).asText(), "harden_etl", "sugiere robustecer" );
    igual( antes, foto( dir ), "nada cambió junto al original" );
    falso( new File( dir, "salida" ).exists(), "las salidas no se escriben junto al .ktr original" );
    sinRestos();
  }

  public void pruebaRobustaPasa() throws Exception {
    Map<String, String> antes = foto( dir );
    ObjectNode o = probar( "hardened_load.ktr" );
    cierto( o.path( "ran" ).asBoolean(), "corrió: " + o );
    igual( "robust", o.path( "verdict" ).asText(), "veredicto: " + o );
    JsonNode comb = variante( o, "combined" );
    for ( JsonNode c : comb.path( "checks" ) ) {
      cierto( !"fail".equals( c.path( "status" ).asText() ) && !"warn".equals( c.path( "status" ).asText() ),
        "combined: " + c );
    }
    JsonNode b = comb.path( "balance" );
    igual( 0, b.path( "lost" ).size(), "ninguna perdida" );
    // 5 con campos vacíos + 1 línea cortada (rama falsa) + 3 fechas + 6 números malos + 1 con espacios (hop de error)
    igual( 16L, b.path( "rejected" ).asLong(), "las rotas fueron a rechazos: " + b );
    igual( 15L, b.path( "loaded" ).asLong(), "las buenas (8 + duplicadas, texto largo, fuera de rango...) se "
      + "cargaron: " + b );
    igual( 31L, b.path( "read" ).asLong(), "31 leídas = 15 + 16 + 0 perdidas" );
    boolean motivo = false;
    for ( JsonNode r : comb.path( "rejects" ) ) {
      igual( 0, r.path( "without_reason" ).asInt(), "todo rechazo con motivo: " + r );
      motivo |= r.path( "sample_reasons" ).size() > 0;
    }
    cierto( motivo, "motivos de muestra" );
    igual( "pass", check( variante( o, "empty_file" ), "no_abort" ).path( "status" ).asText(), "archivo vacío" );
    igual( "pass", check( variante( o, "header_only" ), "no_abort" ).path( "status" ).asText(), "solo cabecera" );
    igual( antes, foto( dir ), "nada cambió junto al original" );
    sinRestos();
  }

  public void pruebaBaseDeDatosDeServidorNoCorre() throws Exception {
    File export = new File( EntornoKettle.carpeta(), "estres-export" );
    EstresTools.borrar( export );
    TransMeta tm = new TransMeta( new File( dir, "server_database_load.ktr" ).getAbsolutePath() );
    EstresTools.Pedido p = new EstresTools.Pedido();
    p.base = sandbox;
    p.exportar = export.getAbsolutePath();
    ObjectNode o = EstresTools.ejecutar( tm, "T9", p );
    falso( o.path( "ran" ).asBoolean(), "no corre" );
    igual( "not_run", o.path( "verdict" ).asText(), "not_run" );
    contiene( o.path( "not_run_reason" ).asText(), "dw (MYSQL)", "nombra la conexión y su tipo" );
    contiene( o.path( "not_run_reason" ).asText(), "never connects", "y que no se conecta" );
    cierto( new File( export, "combined/Read_sales.csv" ).isFile(), "export_variants escribió la variante" );
    cierto( new File( export, "combined/manifest.json" ).isFile(), "con su manifiesto" );
    igual( 0L, new File( export, "empty_file/Read_sales.csv" ).length(), "y el archivo vacío" );
    // otra vez: no pisa nada
    ObjectNode o2 = EstresTools.ejecutar( tm, "T9", p );
    cierto( o2.path( "exported" ).path( "skipped" ).size() >= 3, "no pisa lo que existe: " + o2.path( "exported" ) );
    sinRestos();
  }

  public void pruebaCopiaReescribeRutas() throws Exception {
    baseSqlite();
    TransMeta tm = new TransMeta( new File( dir, "append_load_sqlite.ktr" ).getAbsolutePath() );
    EstresSandbox.Analisis a = EstresSandbox.analizar( tm, 50, null, null );
    cierto( a.corre(), "SQLite de archivo: corre (" + a.bdNoArchivo + a.bloqueos + ")" );
    igual( 1, a.tablas.size(), "la tabla de la carga, para rerun" );
    File sb = new File( sandbox, "copia" );
    File variante = new File( sb, "entrada/x.csv" );
    File ktr = EstresSandbox.escribirCopia( a, java.util.Collections.singletonMap( "Read sales", variante ), new File(
      sb, "salida" ), new File( sb, "db" ), new File( sb, "copia.ktr" ) );
    String xml = new String( Files.readAllBytes( ktr.toPath() ), java.nio.charset.StandardCharsets.UTF_8 );
    contiene( xml, variante.getAbsolutePath().replace( '\\', '/' ), "la entrada lee la variante" );
    contiene( xml, new File( sb, "db" ).getAbsolutePath().replace( '\\', '/' ), "la conexión apunta a la copia de la BD" );
    cierto( new File( sb, "db/dw.db" ).isFile(), "la BD se copió al sandbox" );
    cierto( !xml.contains( dir.getAbsolutePath().replace( '\\', '/' ) + "/db" ), "nada apunta a la BD original" );
    EstresTools.borrar( sb );
  }

  public void pruebaTextFileInputTipado() throws Exception {
    ObjectNode o = probar( "typed_text_input.ktr", "bad_numbers", "header_only" );
    cierto( o.path( "ran" ).asBoolean(), "corrió: " + o );
    JsonNode in = o.path( "static" ).path( "inputs" ).get( 0 );
    igual( 8, in.path( "base_rows" ).asInt(), "leyó la base de un Text file input: " + in );
    JsonNode malos = variante( o, "bad_numbers" );
    igual( 6, malos.path( "inputs" ).path( "Read text" ).path( "injected" ).path( "bad_numbers" ).asInt(),
      "números malos en los dos campos numéricos declarados" );
    igual( "fail", check( malos, "no_abort" ).path( "status" ).asText(), "un número malo aborta: " + malos );
    contiene( check( malos, "no_abort" ).toString(), "Read text", "nombra el Text file input" );
    igual( "pass", check( variante( o, "header_only" ), "no_abort" ).path( "status" ).asText(), "solo cabecera" );
    sinRestos();
  }

  /** db/dw.sqlite junto a la copia de los .ktr, con la tabla vacía (sin clave primaria: duplicar es posible). */
  private File baseSqlite() throws Exception {
    File db = new File( dir, "db/dw.sqlite" );
    if ( !db.isFile() ) {
      db.getParentFile().mkdirs();
      try ( java.sql.Connection c = java.sql.DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
            java.sql.Statement st = c.createStatement() ) {
        st.execute( "CREATE TABLE sales (id INTEGER, fecha TEXT, cliente TEXT, importe REAL)" );
      }
    }
    return db;
  }

  public void pruebaRerunDuplicaConTableOutput() throws Exception {
    File db = baseSqlite();
    Map<String, String> antes = foto( dir );
    ObjectNode o = probar( "append_load_sqlite.ktr", "rerun" );
    cierto( o.path( "ran" ).asBoolean(), "corrió sobre la copia de la BD: " + o );
    igual( "fragile", o.path( "verdict" ).asText(), "veredicto" );
    JsonNode idem = check( variante( o, "rerun" ), "idempotent" );
    igual( "fail", idem.path( "status" ).asText(), "volver a correr duplica: " + idem );
    contiene( idem.toString(), "8 rows after the 1st run, 16 after the 2nd", "cuántas" );
    contiene( o.path( "next" ).asText(), "idempotent_load", "sugiere Insert/Update" );
    igual( antes, foto( dir ), "la BD original no se tocó" );
    igual( 0L, filas( db ), "la BD original sigue vacía" );
    sinRestos();
  }

  public void pruebaRerunIdempotenteConInsertUpdate() throws Exception {
    File db = baseSqlite();
    ObjectNode o = probar( "upsert_load_sqlite.ktr", "rerun" );
    cierto( o.path( "ran" ).asBoolean(), "corrió: " + o );
    JsonNode idem = check( variante( o, "rerun" ), "idempotent" );
    igual( "pass", idem.path( "status" ).asText(), "Insert/Update no duplica: " + idem );
    igual( "robust", o.path( "verdict" ).asText(), "veredicto" );
    igual( 0L, filas( db ), "la BD original sigue vacía" );
    sinRestos();
  }

  private static long filas( File db ) throws Exception {
    try ( java.sql.Connection c = java.sql.DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          java.sql.Statement st = c.createStatement();
          java.sql.ResultSet rs = st.executeQuery( "SELECT COUNT(*) FROM sales" ) ) {
      return rs.next() ? rs.getLong( 1 ) : -1;
    }
  }

  private void sinRestos() {
    String[] l = sandbox.list();
    cierto( l == null || l.length == 0, "el sandbox se borró: " + ( l == null ? "" : Arrays.toString( l ) ) );
  }
}
