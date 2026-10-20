package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.logging.LoggingObjectInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.core.DBCache;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.trans.steps.insertupdate.InsertUpdateMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Seccion target_model (WP5, diseno §2.4.4). Sobre el ejemplo 07 (conexion SQLite sqlite_demo, con
 * ${Internal.Entry.Current.Directory} en la ruta) con dos steps destino agregados en memoria:
 * "Cargar ventas" (Table output -> ventas) y "Upsert clientes" (Insert/Update -> clientes). Las
 * tablas son las de dw.sqlite: clientes(cliente_id PK, nombre, ciudad) y ventas(venta_id PK,
 * cliente_id FK -> clientes, producto, cantidad, precio, fecha).
 * <ul>
 * <li>sin conexion: erDiagram con tipos generados, UK de la clave de lookup y la relacion inferida;</li>
 * <li>con conexion, sobre una COPIA temporal de dw.sqlite: PK, FK y columnas reales;</li>
 * <li>si el archivo de la base no existe, no se crea (ni al consultar ni al calcular los campos).</li>
 * </ul>
 *
 * @dueño WP5
 */
public class PruebaDestino {

  /** Una linea valida de erDiagram compatible con Mermaid 9+ (lo que emite SecDestino). */
  private static final Pattern LINEA_ER = Pattern.compile( "erDiagram"
    + "|  [A-Za-z][A-Za-z0-9_]* \\{( \\})?"
    + "|  \\}"
    + "|    [A-Za-z][A-Za-z0-9_]* [A-Za-z][A-Za-z0-9_]*( (PK|FK|UK))?( \"[^\"#;\\n]*\")?"
    + "|  [A-Za-z][A-Za-z0-9_]* \\|\\|(--|\\.\\.)o\\{ [A-Za-z][A-Za-z0-9_]* : \"[^\"#;\\n]+\"" );

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  // ---------------------------------------------------------------- aplica / no aplica

  public void pruebaNoAplica() throws Exception {
    SecDestino sec = new SecDestino();
    noNulo( sec.noAplica( EntornoKettle.captura( "03" ), "es" ), "03 no tiene Table output ni Insert/Update" );
    noNulo( sec.noAplica( EntornoKettle.captura( "07" ), "en" ), "07 tal cual solo tiene Table input" );
    String job = sec.noAplica( EntornoKettle.captura( "10" ), "es" );
    igual( Rotulos.de( SecDestino.AREA, "es" ).s( "no_aplica.job" ), job, "un job no aplica" );
    nulo( sec.noAplica( captura( ej07( null, true ) ), "es" ), "07 con steps destino aplica" );

    Seccion s = sec.armar( new ContextoPrueba( EntornoKettle.captura( "03" ), pedido( "es", false ), new Tema() ) );
    igual( Seccion.OMITIDA, s.estado, "sin steps destino la seccion queda omitida" );
    noNulo( s.motivo, "con motivo" );
  }

  // ---------------------------------------------------------------- sin conexion

  public void pruebaSinConexion() throws Exception {
    File dw = EntornoKettle.copiaDw();
    long antes = dw.lastModified();
    long largo = dw.length();
    TransMeta tm = ej07( dw.getAbsolutePath(), true );
    ContextoPrueba ctx = new ContextoPrueba( captura( tm ), pedido( "es", false ), new Tema() );
    Seccion s = new SecDestino().armar( ctx );
    String json = ModeloJson.escribir( s, true );
    guardar( "sin-conexion", s );

    igual( Seccion.OK, s.estado, "estado sin conexion (" + s.motivo + ")" );
    igual( "target_model", s.id, "id" );
    noNulo( s.resumen, "resumen" );
    contiene( s.resumen, "2 tablas", "resumen con las tablas" );
    Diagrama d = diagrama( s );
    igual( Diagrama.ER, d.clase, "clase er" );
    String m = d.mermaid;
    validarMermaid( m );
    contiene( m, "  ventas {", "entidad ventas" );
    contiene( m, "  clientes {", "entidad clientes" );
    contiene( m, "INTEGER venta_id", "tipo generado por Kettle para un Integer en SQLite" );
    contiene( m, "NUMERIC precio", "tipo generado por Kettle para un Number en SQLite" );
    contiene( m, "INTEGER cliente_id UK \"clave de búsqueda", "clave de lookup de Insert/Update como UK" );
    contiene( m, "nombre \"se actualiza\"", "columna que se actualiza" );
    contiene( m, "clientes ||..o{ ventas : \"cliente_id\"", "relacion inferida por la clave de lookup" );
    noContiene( m, " PK", "sin conexion no hay PK" );
    noContiene( m, "||--o{", "sin conexion no hay FK de la base" );
    noNulo( d.alternativa, "tabla alternativa" );
    igual( 2, d.alternativa.filas.size(), "una fila por tabla en la alternativa" );
    noNulo( d.nota, "nota de las lineas punteadas" );

    List<Tabla> tablas = tablas( s );
    igual( 2, tablas.size(), "una tabla de columnas por tabla destino" );
    Tabla ventas = tablas.get( 0 );
    contiene( ventas.titulo, "sqlite_demo › ventas", "titulo con conexion y tabla" );
    contiene( ventas.titulo, "Cargar ventas (Salida a tabla, vacía la tabla)", "titulo con step, tipo y truncate" );
    igual( 4, ventas.filas.size(), "4 columnas mapeadas en ventas" );
    igual( "precio", ventas.filas.get( 3 ).get( 0 ), "columna precio" );
    igual( "valor", ventas.filas.get( 3 ).get( 1 ), "precio <- valor" );
    falso( aviso( s, Aviso.WARN ) != null, "sin avisos de advertencia: " + textos( s ) );
    noNulo( aviso( s, Aviso.INFO ), "aviso informativo de que no se consulto la base" );

    JsonNode datos = s.datos;
    igual( "target_model", datos.path( "section" ).asText(), "datos.section" );
    falso( datos.path( "database_queried" ).asBoolean(), "datos.database_queried" );
    igual( 2, datos.path( "tables" ).size(), "datos.tables" );
    igual( "not_queried", datos.path( "tables" ).get( 0 ).path( "database_check" ).path( "status" ).asText(),
      "estado de la base por tabla" );
    igual( "lookup_key", datos.path( "relationships" ).get( 0 ).path( "source" ).asText(), "origen de la relacion" );
    Seccion vuelta = ModeloJson.leer( json, Seccion.class );
    igual( json, ModeloJson.escribir( vuelta, true ), "ida y vuelta Jackson" );
    igual( antes, dw.lastModified(), "la base no se toco" );
    igual( largo, dw.length(), "la base no cambio de tamanio" );
  }

  // ---------------------------------------------------------------- con conexion

  public void pruebaConConexion() throws Exception {
    // como en 07: la ruta usa ${Internal.Entry.Current.Directory} (un URI file:///...%20...)
    File carpeta = new File( EntornoKettle.ejemplos(), "salida_wp5_dw_" + System.nanoTime() );
    cierto( carpeta.mkdirs(), "carpeta " + carpeta );
    File dw = new File( carpeta, "dw.sqlite" );
    Files.copy( EntornoKettle.copiaDw().toPath(), dw.toPath() );
    long largo = dw.length();
    TransMeta tm = ej07( "${Internal.Entry.Current.Directory}/" + carpeta.getName() + "/dw.sqlite", true );
    ContextoPrueba ctx = new ContextoPrueba( captura( tm ), pedido( "es", true ), new Tema() );
    long t0 = System.currentTimeMillis();
    Seccion s = new SecDestino().armar( ctx );
    long ms = System.currentTimeMillis() - t0;
    guardar( "con-conexion", s );

    igual( Seccion.OK, s.estado, "estado con conexion (" + s.motivo + " / " + textos( s ) + ")" );
    cierto( ms < 15000, "con conexion tarda menos que el tope: " + ms + " ms" );
    String m = diagrama( s ).mermaid;
    validarMermaid( m );
    contiene( m, "venta_id PK", "PK de ventas leida de la base" );
    contiene( m, "cliente_id FK", "FK de ventas leida de la base" );
    contiene( m, "cliente_id PK \"clave de búsqueda", "PK de clientes (y clave de lookup)" );
    contiene( m, "clientes ||--o{ ventas : \"cliente_id\"", "relacion por la FK de la base" );
    noContiene( m, "||..o{", "con la FK no queda la relacion inferida" );
    contiene( m, "cantidad \"no se carga\"", "columna de la tabla que el step no carga" );
    contiene( m, "TEXT ciudad \"no se carga\"", "columna de clientes que no se carga, con su tipo real" );
    contiene( m, "REAL precio", "tipo real de la base" );
    contiene( s.resumen, "claves primarias en 2 tablas y 1 clave foránea", "resumen con lo leido" );

    List<Tabla> tablas = tablas( s );
    Tabla ventas = tablas.get( 0 );
    igual( "Tipo SQL (base)", ventas.columnas.get( 3 ), "con base, la columna de tipo es la real" );
    igual( 6, ventas.filas.size(), "las 6 columnas de ventas, en el orden de la tabla" );
    igual( "venta_id", ventas.filas.get( 0 ).get( 0 ), "primera columna" );
    contiene( ventas.filas.get( 1 ).get( 4 ), "FK → clientes.cliente_id", "uso de cliente_id en ventas" );
    falso( aviso( s, Aviso.WARN ) != null || aviso( s, Aviso.ERROR ) != null, "sin advertencias: " + textos( s ) );
    nulo( aviso( s, Aviso.INFO ), "sin el aviso de 'no se consulto la base'" );

    JsonNode t0j = s.datos.path( "tables" ).get( 0 );
    cierto( s.datos.path( "database_queried" ).asBoolean(), "datos.database_queried" );
    igual( "ok", t0j.path( "database_check" ).path( "status" ).asText(), "estado de la base" );
    igual( "venta_id", t0j.path( "database_check" ).path( "primary_key" ).get( 0 ).asText(), "PK en datos" );
    igual( "foreign_key", s.datos.path( "relationships" ).get( 0 ).path( "source" ).asText(), "origen FK" );
    igual( largo, dw.length(), "consultar no cambia la base" );
  }

  /** Una tabla que no existe en la base: aviso, y el resto sale igual. */
  public void pruebaTablaInexistente() throws Exception {
    File dw = EntornoKettle.copiaDw();
    TransMeta tm = ej07( dw.getAbsolutePath(), false );
    ( (TableOutputMeta) tm.findStep( "Cargar ventas" ).getStepMetaInterface() ).setTableName( "ventas_nueva" );
    Seccion s = new SecDestino().armar( new ContextoPrueba( captura( tm ), pedido( "en", true ), new Tema() ) );
    guardar( "tabla-inexistente", s );
    igual( Seccion.OK, s.estado, "una tabla que falta es un hallazgo, no un error de la seccion" );
    contiene( textos( s ), "Table ventas_nueva does not exist in connection sqlite_demo", "aviso de tabla inexistente" );
    igual( "table_missing", s.datos.path( "tables" ).get( 0 ).path( "database_check" ).path( "status" ).asText(),
      "estado de la tabla" );
    contiene( diagrama( s ).mermaid, "\"missing in table\"", "columnas marcadas" );
    igual( "The table does not exist in the database.", tablas( s ).get( 0 ).pie, "pie de la tabla" );
  }

  // ---------------------------------------------------------------- archivo inexistente

  /**
   * La ruta de 07 (${Internal.Entry.Current.Directory}/...) apunta a una base que no existe, en una
   * carpeta que SI existe (si no, SQLite fallaria por la carpeta y la prueba no probaria nada). Ni la
   * consulta ni el calculo de campos (el Table input se conectaria) pueden crear el archivo.
   */
  public void pruebaArchivoInexistente() throws Exception {
    File carpeta = new File( EntornoKettle.ejemplos(), "salida_wp5_" + System.nanoTime() );
    cierto( carpeta.mkdirs(), "carpeta nueva " + carpeta );
    File base = new File( carpeta, "demo.sqlite" );
    String ruta = "${Internal.Entry.Current.Directory}/" + carpeta.getName() + "/demo.sqlite";
    for ( boolean consultar : new boolean[] { false, true } ) {
      DBCache.getInstance().clear( "sqlite_demo" ); // sin cache, calcular los campos se conectaria
      TransMeta tm = ej07( ruta, true );
      ContextoPrueba ctx = new ContextoPrueba( captura( tm ), pedido( "es", consultar ), new Tema() );
      Seccion s = new SecDestino().armar( ctx );
      guardar( "archivo-inexistente-" + consultar, s );
      falso( base.exists(), "no se creo " + base + " (consultar=" + consultar + ")" );
      igual( 0, carpeta.list().length, "la carpeta sigue vacia (consultar=" + consultar + ")" );
      igual( Seccion.PARCIAL, s.estado, "parcial (consultar=" + consultar + ")" );
      contiene( textos( s ), "cuyo archivo de base no existe", "aviso de campos no calculados" );
      String m = diagrama( s ).mermaid;
      validarMermaid( m );
      contiene( m, "unknown venta_id", "sin campos, el tipo queda desconocido" );
      contiene( m, "cliente_id UK", "la clave de lookup sale igual de los metadatos" );
      if ( consultar ) {
        contiene( textos( s ), "El archivo de base de la conexión sqlite_demo no existe", "aviso del archivo" );
        contiene( s.motivo, "no se leyeron las claves", "motivo" );
        igual( "missing_file", s.datos.path( "tables" ).get( 0 ).path( "database_check" ).path( "status" ).asText(),
          "estado de la base" );
        cierto( ctx.avisos.size() >= 1, "el archivo faltante va tambien a los avisos del informe" );
      }
    }
  }

  /**
   * Control: calcular los campos sin el chequeo (lo que hace getPrevStepFields, y por lo tanto un
   * Contexto que no chequee) SI crea la base vacia, porque el Table input se conecta para saber sus
   * columnas. Por eso SecDestino chequea los steps anteriores antes de llamar a ctx.entrada.
   */
  public void pruebaControlCamposCreanArchivo() throws Exception {
    File carpeta = new File( EntornoKettle.ejemplos(), "salida_wp5_control_" + System.nanoTime() );
    cierto( carpeta.mkdirs(), "carpeta " + carpeta );
    File base = new File( carpeta, "demo.sqlite" );
    TransMeta tm = ej07( "${Internal.Entry.Current.Directory}/" + carpeta.getName() + "/demo.sqlite", false );
    StepMeta destino = tm.findStep( "Cargar ventas" );
    falso( SecDestino.basesFaltantesAntes( tm, destino ).isEmpty(), "el chequeo ve la base faltante" );
    // DBCache guarda los campos de un Table input por nombre de conexion + SQL: las pruebas anteriores
    // ya los dejaron ahi y getPrevStepFields no se conectaria
    DBCache.getInstance().clear( "sqlite_demo" );
    try {
      tm.getPrevStepFields( destino );
    } catch ( Exception e ) {
      // la consulta falla ("no such table": la base nueva esta vacia), pero el archivo ya se creo
    }
    cierto( base.isFile(), "sin el chequeo, calcular los campos crea " + base );
  }

  /** archivoFaltante reconoce SQLite, H2 embebido, HSQLDB en archivo y no toca las bases de servidor. */
  public void pruebaArchivoDeBase() throws Exception {
    TransMeta tm = EntornoKettle.trans( "07" );
    File dir = EntornoKettle.salida( "destino" );
    File existe = new File( dir, "existe.sqlite" );
    EntornoKettle.escribir( existe, "" );
    noNulo( SecDestino.archivoFaltante( db( "SQLITE", "", new File( dir, "no.sqlite" ).getPath() ), tm ), "sqlite falta" );
    nulo( SecDestino.archivoFaltante( db( "SQLITE", "", existe.getPath() ), tm ), "sqlite existe" );
    nulo( SecDestino.archivoFaltante( db( "SQLITE", "", ":memory:" ), tm ), "sqlite en memoria" );
    File h2 = SecDestino.archivoFaltante( db( "H2", "", new File( dir, "baseh2" ).getPath() ), tm );
    noNulo( h2, "h2 embebido falta" );
    cierto( h2.getName().endsWith( ".mv.db" ), "h2 usa .mv.db" );
    nulo( SecDestino.archivoFaltante( db( "H2", "localhost", "baseh2" ), tm ), "h2 servidor" );
    nulo( SecDestino.archivoFaltante( db( "H2", "", "mem:x" ), tm ), "h2 en memoria" );
    noNulo( SecDestino.archivoFaltante( db( "HYPERSONIC", "", "file:" + new File( dir, "hs" ).getPath() ), tm ),
      "hsqldb en archivo" );
    nulo( SecDestino.archivoFaltante( db( "POSTGRESQL", "localhost", "dw" ), tm ), "postgres no es de archivo" );
    File conVariable = SecDestino.archivoFaltante( db( "SQLITE", "", "${Internal.Entry.Current.Directory}/x.sqlite" ),
      tm );
    noNulo( conVariable, "con variable" );
    igual( EntornoKettle.ejemplos().getCanonicalPath(), conVariable.getParentFile().getCanonicalPath(),
      "la variable se resuelve con la transformacion" );

    // otros motores que crean la base al conectarse: DuckDB (archivo) y Derby embebido con create=true
    File duck = new File( dir, "no.duckdb" );
    igual( duck.getAbsolutePath(), SecDestino.archivoFaltante( generica( "jdbc:duckdb:" + duck.getPath() ), tm )
      .getAbsolutePath(), "duckdb falta" );
    nulo( SecDestino.archivoFaltante( generica( "jdbc:duckdb:" ), tm ), "duckdb en memoria (sin ruta)" );
    nulo( SecDestino.archivoFaltante( generica( "jdbc:duckdb::memory:" ), tm ), "duckdb :memory:" );
    File duckExiste = new File( dir, "existe.duckdb" );
    EntornoKettle.escribir( duckExiste, "" );
    nulo( SecDestino.archivoFaltante( generica( "jdbc:duckdb:" + duckExiste.getPath() + "?access_mode=read_only" ),
      tm ), "duckdb existe (con opciones)" );
    File derby = new File( dir, "basederby" );
    noNulo( SecDestino.archivoFaltante( generica( "jdbc:derby:" + derby.getPath() + ";create=true" ), tm ),
      "derby con create=true falta" );
    nulo( SecDestino.archivoFaltante( generica( "jdbc:derby:" + derby.getPath() ), tm ),
      "derby sin create=true no crea nada" );
    nulo( SecDestino.archivoFaltante( generica( "jdbc:derby://localhost:1527/x;create=true" ), tm ), "derby en red" );
    nulo( SecDestino.archivoFaltante( generica( "jdbc:derby:memory:x;create=true" ), tm ), "derby en memoria" );
  }

  private static DatabaseMeta generica( String url ) {
    DatabaseMeta dm = db( "GENERIC", "", "" );
    dm.getAttributes().setProperty( "CUSTOM_URL", url );
    dm.getAttributes().setProperty( "CUSTOM_DRIVER_CLASS", "x.Driver" );
    return dm;
  }

  /**
   * ${Internal.Entry.Current.Directory} es un URI (file:///...%20...). archivoFaltante lo decodifica
   * como SQLite: se prueba conectando de verdad (en una carpeta propia) y viendo donde aparece el archivo.
   */
  public void pruebaRutaUriComoSqlite() throws Exception {
    TransMeta tm = EntornoKettle.trans( "07" );
    String dirUri = tm.environmentSubstitute( "${Internal.Entry.Current.Directory}" );
    cierto( dirUri.startsWith( "file:" ), "la variable es un URI: " + dirUri );
    File carpeta = new File( EntornoKettle.ejemplos(), "salida_wp5_uri_" + System.nanoTime() );
    cierto( carpeta.mkdirs(), "carpeta " + carpeta );
    File esperado = new File( carpeta, "creada.sqlite" );
    DatabaseMeta dm = db( "SQLITE", "", "${Internal.Entry.Current.Directory}/" + carpeta.getName() + "/creada.sqlite" );
    File visto = SecDestino.archivoFaltante( dm, tm );
    noNulo( visto, "antes de conectar falta" );
    igual( esperado.getCanonicalPath(), visto.getCanonicalPath(), "la ruta decodificada" );
    dm.shareVariablesWith( tm );
    Database d = new Database( (LoggingObjectInterface) null, dm );
    d.shareVariablesWith( tm );
    try {
      d.connect();
    } finally {
      d.disconnect();
    }
    cierto( esperado.isFile(), "SQLite creo el archivo en la ruta decodificada (la que chequea SecDestino)" );
    nulo( SecDestino.archivoFaltante( dm, tm ), "ahora existe" );
  }

  // ---------------------------------------------------------------- base que no responde

  /** Una base de servidor que rechaza la conexion: error con motivo, sin contrasenia, y el resto sale. */
  public void pruebaBaseInalcanzable() throws Exception {
    TransMeta tm = ej07( null, true );
    DatabaseMeta pg = new DatabaseMeta( "pg_caida", "POSTGRESQL", "Native", "127.0.0.1", "dw", "1", "usuario",
      "secreto123" );
    tm.addDatabase( pg );
    ( (TableOutputMeta) tm.findStep( "Cargar ventas" ).getStepMetaInterface() ).setDatabaseMeta( pg );
    ( (InsertUpdateMeta) tm.findStep( "Upsert clientes" ).getStepMetaInterface() ).setDatabaseMeta( pg );
    // los campos llegan del Table input de sqlite_demo: que apunte a una base que existe
    tm.findDatabase( "sqlite_demo" ).setDBName( EntornoKettle.copiaDw().getAbsolutePath() );
    long t0 = System.currentTimeMillis();
    Seccion s = new SecDestino().armar( new ContextoPrueba( captura( tm ), pedido( "en", true ), new Tema() ) );
    long ms = System.currentTimeMillis() - t0;
    guardar( "base-inalcanzable", s );
    igual( Seccion.PARCIAL, s.estado, "parcial" );
    cierto( ms < 20000, "respeta el tope de 15 s: " + ms + " ms" );
    String t = textos( s );
    contiene( t, "Connection pg_caida could not be queried", "aviso de error" );
    noContiene( t + ModeloJson.escribir( s, false ), "secreto123", "nunca la contrasenia" );
    contiene( diagrama( s ).mermaid, "  ventas {", "el diagrama sale igual" );
    contiene( diagrama( s ).mermaid, "DOUBLE_PRECISION precio", "tipo generado para PostgreSQL (DOUBLE PRECISION)" );
    contiene( diagrama( s ).mermaid, "TEXT producto", "String sin largo en PostgreSQL" );
  }

  // ---------------------------------------------------------------- otros

  /** Pedido cancelado: vuelve enseguida, parcial y con motivo. */
  public void pruebaCancelado() throws Exception {
    ContextoPrueba ctx = new ContextoPrueba( captura( ej07( EntornoKettle.copiaDw().getAbsolutePath(), true ) ),
      pedido( "pt", true ), new Tema() );
    ctx.cancelar = true;
    Seccion s = new SecDestino().armar( ctx );
    igual( Seccion.PARCIAL, s.estado, "cancelado = parcial" );
    contiene( s.motivo, "Cancelado", "motivo en portugues" );
  }

  /** Nombres raros: acentos, espacios, comillas y palabras clave no rompen el erDiagram. */
  public void pruebaNombresRaros() throws Exception {
    TransMeta tm = ej07( EntornoKettle.copiaDw().getAbsolutePath(), false );
    TableOutputMeta to = (TableOutputMeta) tm.findStep( "Cargar ventas" ).getStepMetaInterface();
    to.setTableName( "ventas del año" );
    to.setFieldDatabase( new String[] { "año", "PK", "precio \"neto\"", "2026" } );
    to.setFieldStream( new String[] { "id", "ciudad", "valor", "id" } );
    Seccion s = new SecDestino().armar( new ContextoPrueba( captura( tm ), pedido( "es", false ), new Tema() ) );
    guardar( "nombres-raros", s );
    String m = diagrama( s ).mermaid;
    validarMermaid( m );
    contiene( m, "  ventas_del_ano {", "entidad sin espacios ni acentos" );
    contiene( m, "ano \"año\"", "atributo ASCII con el nombre original en el comentario" );
    contiene( m, "PK_ \"PK\"", "una columna llamada PK no se confunde con la clave" );
    contiene( m, "precio_neto \"precio 'neto'\"", "comillas dobles cambiadas" );
    contiene( m, "c_2026", "identificador que empieza con letra" );
    igual( "ventas_del_ano", SecDestino.identificador( "ventas del año", "t" ), "identificador" );
    igual( "VARCHAR_100", SecDestino.identificador( "VARCHAR(100)", "unknown" ), "tipo con largo" );
    igual( "unknown", SecDestino.identificador( "?", "unknown" ), "tipo desconocido" );
  }

  /** Las tres traducciones tienen las mismas claves y el resumen sale en cada idioma sin claves crudas. */
  public void pruebaIdiomas() throws Exception {
    Properties en = props( "en" );
    for ( String idioma : new String[] { "es", "pt" } ) {
      Properties p = props( idioma );
      igual( en.keySet(), p.keySet(), "mismas claves en destino_" + idioma );
      for ( String k : en.stringPropertyNames() ) {
        igual( cuenta( en.getProperty( k ) ), cuenta( p.getProperty( k ) ), "mismos %s en " + idioma + ":" + k );
      }
    }
    File dw = EntornoKettle.copiaDw();
    for ( String idioma : new String[] { "en", "es", "pt" } ) {
      for ( boolean consultar : new boolean[] { false, true } ) {
        Seccion s = new SecDestino().armar( new ContextoPrueba( captura( ej07( dw.getAbsolutePath(), true ) ),
          pedido( idioma, consultar ), new Tema() ) );
        String todo = ModeloJson.escribir( s, false );
        for ( String prefijo : new String[] { "resumen.", "aviso.", "rol.", "col.", "tabla.", "tipo.", "motivo.",
          "diagrama.", "n.columnas", "n.tablas", "n.fks" } ) {
          noContiene( todo, "\"" + prefijo, "sin claves crudas (" + idioma + ")" );
          noContiene( todo, " " + prefijo, "sin claves crudas (" + idioma + ")" );
        }
        cierto( s.resumen != null && s.resumen.length() > 20, "resumen en " + idioma + ": " + s.resumen );
      }
    }
  }

  // ---------------------------------------------------------------- ayudas

  /**
   * El ejemplo 07 con dos steps destino despues del Table input, en la conexion sqlite_demo. ruta =
   * null deja la ruta del .ktr (salida/demo.sqlite). Con lookup, el Insert/Update de clientes.
   */
  private static TransMeta ej07( String ruta, boolean conUpsert ) throws Exception {
    TransMeta tm = EntornoKettle.trans( "07" );
    DatabaseMeta db = tm.findDatabase( "sqlite_demo" );
    if ( ruta != null ) {
      db.setDBName( ruta );
    }
    StepMeta origen = tm.findStep( "Consultar SQLite" );
    // la consulta original (SELECT 1 AS id...) no tiene tipos declarados: sqlite-jdbc los da todos como
    // Number. Sobre las tablas de dw salen Integer, String y Number.
    ( (TableInputMeta) origen.getStepMetaInterface() ).setSQL( "SELECT v.venta_id AS id, c.ciudad AS ciudad, "
      + "v.precio AS valor FROM ventas v JOIN clientes c ON c.cliente_id = v.cliente_id" );
    TableOutputMeta to = new TableOutputMeta();
    to.setDefault();
    to.setDatabaseMeta( db );
    to.setTableName( "ventas" );
    to.setTruncateTable( true );
    to.setSpecifyFields( true );
    to.setFieldDatabase( new String[] { "venta_id", "cliente_id", "producto", "precio" } );
    to.setFieldStream( new String[] { "id", "id", "ciudad", "valor" } );
    StepMeta sto = new StepMeta( "TableOutput", "Cargar ventas", to );
    sto.setLocation( 400, 112 );
    tm.addStep( sto );
    tm.addTransHop( new TransHopMeta( origen, sto ) );
    if ( conUpsert ) {
      InsertUpdateMeta iu = new InsertUpdateMeta();
      iu.setDefault();
      iu.setDatabaseMeta( db );
      iu.setTableName( "clientes" );
      iu.allocate( 1, 2 );
      iu.setKeyStream( new String[] { "id" } );
      iu.setKeyLookup( new String[] { "cliente_id" } );
      iu.setKeyCondition( new String[] { "=" } );
      iu.setKeyStream2( new String[] { null } );
      iu.setUpdateLookup( new String[] { "cliente_id", "nombre" } );
      iu.setUpdateStream( new String[] { "id", "ciudad" } );
      iu.setUpdate( new Boolean[] { Boolean.FALSE, Boolean.TRUE } );
      StepMeta siu = new StepMeta( "InsertUpdate", "Upsert clientes", iu );
      siu.setLocation( 400, 240 );
      tm.addStep( siu );
      tm.addTransHop( new TransHopMeta( origen, siu ) );
    }
    tm.clearCaches();
    return tm;
  }

  private static Captura captura( TransMeta tm ) {
    Captura c = new Captura();
    c.copia = tm;
    c.docId = "prueba:destino";
    c.nombre = tm.getName();
    c.archivo = tm.getFilename();
    c.tipo = Captura.TRANSFORMACION;
    c.capturado = new Date();
    return c;
  }

  private static Pedido pedido( String idioma, boolean consultar ) {
    Pedido p = new Pedido();
    p.idioma = idioma;
    p.destinoConsultarBase = consultar;
    return p;
  }

  private static DatabaseMeta db( String tipo, String host, String base ) {
    return new DatabaseMeta( "x", tipo, "Native", host, base, "-1", "", "" );
  }

  private static Diagrama diagrama( Seccion s ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Diagrama ) {
        return (Diagrama) b;
      }
    }
    throw new Verificador.Falla( "la seccion no tiene diagrama: " + s.estado + " " + s.motivo );
  }

  private static List<Tabla> tablas( Seccion s ) {
    List<Tabla> l = new ArrayList<>();
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla ) {
        l.add( (Tabla) b );
      }
    }
    return l;
  }

  private static Aviso aviso( Seccion s, String nivel ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Aviso && nivel.equals( ( (Aviso) b ).nivel ) ) {
        return (Aviso) b;
      }
    }
    return null;
  }

  private static String textos( Seccion s ) {
    StringBuilder sb = new StringBuilder();
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Aviso ) {
        sb.append( '[' ).append( ( (Aviso) b ).nivel ).append( "] " ).append( ( (Aviso) b ).texto ).append( '\n' );
      }
    }
    return sb.toString();
  }

  /** Cada linea del erDiagram tiene la forma que entiende Mermaid 9+. */
  private static void validarMermaid( String m ) {
    noNulo( m, "mermaid" );
    String[] lineas = m.split( "\n" );
    igual( "erDiagram", lineas[ 0 ], "primera linea" );
    int abiertas = 0;
    for ( String l : lineas ) {
      cierto( LINEA_ER.matcher( l ).matches(), "linea de erDiagram valida: <" + l + ">\n" + m );
      if ( l.endsWith( " {" ) ) {
        abiertas++;
      } else if ( l.equals( "  }" ) ) {
        abiertas--;
      }
      cierto( abiertas == 0 || abiertas == 1, "llaves balanceadas" );
    }
    igual( 0, abiertas, "llaves cerradas" );
  }

  private static void guardar( String nombre, Seccion s ) throws Exception {
    File dir = EntornoKettle.salida( "destino" );
    EntornoKettle.escribir( new File( dir, nombre + ".json" ), ModeloJson.escribir( s, true ) );
    Diagrama d = null;
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Diagrama ) {
        d = (Diagrama) b;
      }
    }
    if ( d != null && d.mermaid != null ) {
      EntornoKettle.escribir( new File( dir, nombre + ".mmd" ), d.mermaid );
    }
  }

  private static Properties props( String idioma ) throws Exception {
    Properties p = new Properties();
    String recurso = Rotulos.CARPETA + SecDestino.AREA + "_" + idioma + ".properties";
    try ( InputStream in = PruebaDestino.class.getClassLoader().getResourceAsStream( recurso ) ) {
      noNulo( in, "recurso " + recurso );
      p.load( new java.io.InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    return p;
  }

  /** Cantidad de especificadores de formato (sin contar %%). */
  private static int cuenta( String patron ) {
    return patron.replace( "%%", "" ).split( "%", -1 ).length - 1;
  }
}
