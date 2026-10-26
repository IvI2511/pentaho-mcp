package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.CorrectorMain.Pedido;
import io.github.ivi2511.pentahomcp.CorrectorPolitica.Entrega;
import io.github.ivi2511.pentahomcp.CorrectorSandbox.Preparacion;
import io.github.ivi2511.pentahomcp.CorrectorTextos.Mensaje;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.SeguridadBases;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Las bases de prueba del corrector (0.6.2): ejercicios que leen y escriben un servidor de base de datos. Sin ningun
 * MySQL ni PostgreSQL de la maquina: un servidor H2 en TCP dentro de esta JVM ({@link BasesH2}) hace de servidor del
 * docente. De punta a punta: el pack se crea desde la solucion (scripts, caso oculto, dos ejecuciones por caso), la
 * base de prueba se recrea antes de cada caso, la entrega correcta saca 10, la que carga con Table output duplica
 * filas al repetirla, la que usa otra conexion se bloquea, una base que es la original se rechaza, dos workers usan
 * cada uno su base (_w1, _w2), la contraseña no queda en ningun archivo y las bases originales no se tocan nunca.
 * Ademas, sin Kettle corriendo nada: el separador de sentencias, el cambio de nombres en los scripts, las reglas de
 * seguridad, el SQL que sale de las bases de prueba, la validacion del pack 2 y que todo codigo de error tenga su
 * texto en en, es y pt.
 *
 * @dueño corrector 0.6.2
 */
public class PruebaCorrectorBases {

  private File dir;
  private BasesH2 h2;
  /** El pack creado por la prueba de punta a punta (lo usan las siguientes). */
  private File pack;
  private File entregas;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    dir = new File( EntornoKettle.carpeta(), "corrector-bases" );
    CorrectorSandbox.borrar( dir );
    dir.mkdirs();
  }

  public void cerrar() {
    if ( h2 != null ) {
      h2.parar();
    }
  }

  private BasesH2 h2() throws Exception {
    if ( h2 == null ) {
      h2 = new BasesH2( new File( dir, "servidor-h2" ) );
    }
    return h2;
  }

  // ================================================================ SQL (sin Kettle)

  public void pruebaA_Sentencias() throws Exception {
    List<String> l = CorrectorBases.sentencias( "CREATE TABLE a (x INT); -- un; comentario\n"
      + "INSERT INTO a VALUES (1); INSERT INTO b VALUES ('x;y', 'it''s');\n/* bloque; */\n"
      + "DELIMITER $$\nCREATE TRIGGER t BEFORE INSERT ON a FOR EACH ROW BEGIN SET NEW.x = 1; END$$\nDELIMITER ;\n"
      + "SELECT 1;\n# fin; de mysql\n", true );
    igual( 5, l.size(), "sentencias: " + l );
    igual( "CREATE TABLE a (x INT)", l.get( 0 ), "la primera, sin el ;" );
    contiene( l.get( 2 ), "'x;y'", "un ; dentro de una cadena no corta" );
    cierto( l.get( 3 ).startsWith( "CREATE TRIGGER" ) && l.get( 3 ).contains( "SET NEW.x = 1; END" ),
      "DELIMITER $$: el trigger entero: " + l.get( 3 ) );
    igual( "SELECT 1", l.get( 4 ), "despues de volver al ;" );

    List<String> pg = CorrectorBases.sentencias( "CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END; $$ "
      + "LANGUAGE plpgsql;\nSELECT 2;", false );
    igual( 2, pg.size(), "PostgreSQL: el bloque $$ es una sola sentencia: " + pg );

    List<String> dump = CorrectorBases.sentencias( "/*!40101 SET NAMES utf8 */;\nCREATE DATABASE IF NOT EXISTS gym_dw;\n"
      + "USE gym_dw;\n", true );
    igual( 3, dump.size(), "mysqldump: " + dump );
    cierto( dump.get( 0 ).startsWith( "/*!40101" ), "el comentario ejecutable queda" );
  }

  public void pruebaB_Renombrar() throws Exception {
    Map<String, String> m = Collections.singletonMap( "gym_dw", "gym_dw_correccion" );
    igual( "USE gym_dw_correccion; INSERT INTO gym_dw_correccion.dim (n, gym_dw_x) VALUES ('gym_dw'); "
      + "SELECT * FROM `gym_dw_correccion`.x -- gym_dw\n", CorrectorBases.renombrar( "USE gym_dw; INSERT INTO "
      + "gym_dw.dim (n, gym_dw_x) VALUES ('gym_dw'); SELECT * FROM `GYM_DW`.x -- gym_dw\n", m ),
      "solo los identificadores: ni cadenas, ni comentarios, ni otros nombres" );
    Set<String> pruebas = Collections.singleton( "gym_dw_correccion" );
    igual( "USE otra", CorrectorBases.scriptAjeno( "USE otra", pruebas ), "un script que nombra otra base" );
    nulo( CorrectorBases.scriptAjeno( "USE gym_dw_correccion", pruebas ), "la de prueba vale" );
    cierto( CorrectorBases.crearOBorrarBase( "CREATE DATABASE IF NOT EXISTS gym_dw_correccion", true ),
      "CREATE DATABASE de la de prueba: se salta" );
    falso( CorrectorBases.crearOBorrarBase( "CREATE SCHEMA ventas", false ), "en PostgreSQL un esquema es otra cosa" );
  }

  public void pruebaC_SqlAjeno() throws Exception {
    Set<String> pruebas = new HashSet<String>( Arrays.asList( "gym_dw_correccion" ) );
    Set<String> prohibidas = new HashSet<String>( Arrays.asList( "gym_dw", "mysql" ) );
    String[][] casos = { { "SELECT * FROM t INTO OUTFILE '/tmp/x'", "INTO OUTFILE" },
      { "LOAD DATA INFILE 'x' INTO TABLE t", "LOAD DATA" }, { "USE gym_dw", "USE gym_dw" },
      { "SELECT * FROM gym_dw.dim", "gym_dw." }, { "SELECT * FROM mysql.user", "mysql." },
      { "CALL CSVWRITE('x.csv', 'SELECT 1')", "CSVWRITE / FILE_READ" }, { "DROP DATABASE otra", "DROP DATABASE otra" },
      { "GRANT ALL ON *.* TO x", "GRANT" }, { "COPY t TO '/tmp/x'", "COPY TO/FROM" },
      { "SELECT 'INTO OUTFILE' AS x FROM t", null }, { "SELECT a.x FROM t a", null },
      { "USE gym_dw_correccion", null }, { "INSERT INTO gym_dw_correccion.dim VALUES (1)", null },
      { "SELECT grant_id FROM t", null } };
    for ( String[] c : casos ) {
      igual( c[ 1 ], CorrectorBases.sqlAjeno( c[ 0 ], pruebas, prohibidas ), c[ 0 ] );
    }
  }

  // ================================================================ seguridad y pack (sin Kettle corriendo)

  private Pack packConBase( String base ) {
    Pack p = new Pack();
    p.id = "dw";
    BaseDePrueba b = new BaseDePrueba( "GYM_DW", "MYSQL", "localhost", "3306", base, "profe" );
    b.baseOriginal = "gym_dw";
    p.bases.add( b );
    return p.completar();
  }

  private static String claves( List<Mensaje> l ) {
    List<String> k = new ArrayList<String>();
    for ( Mensaje m : l ) {
      k.add( m.clave );
    }
    return k.toString();
  }

  public void pruebaD_Seguridad() throws Exception {
    igual( "[]", claves( CorrectorBases.inseguras( packConBase( "gym_dw_correccion" ), null, null ) ), "la buena" );
    igual( "[db.unsafe.original, db.unsafe.suffix]", claves( CorrectorBases.inseguras( packConBase( "gym_dw" ), null,
      null ) ), "la original: nunca" );
    igual( "[db.unsafe.suffix]", claves( CorrectorBases.inseguras( packConBase( "gym_dw_copia" ), null, null ) ),
      "sin sufijo de prueba" );
    Pack libre = packConBase( "gym_dw_copia" );
    libre.seguridad = new SeguridadBases();
    libre.seguridad.cualquierNombre = true;
    igual( "[]", claves( CorrectorBases.inseguras( libre, null, null ) ), "allow_any_name: decision del docente" );
    libre.bases.get( 0 ).base = "mysql";
    igual( "[db.unsafe.system]", claves( CorrectorBases.inseguras( libre, null, null ) ),
      "una del sistema, ni con allow_any_name" );
    Pack deny = packConBase( "gym_dw_correccion" );
    deny.seguridad = new SeguridadBases();
    deny.seguridad.prohibidas.add( "gym_*" );
    igual( "[db.unsafe.denied]", claves( CorrectorBases.inseguras( deny, null, null ) ), "deny con comodin" );
    igual( "[db.unsafe.original]", claves( CorrectorBases.inseguras( packConBase( "gym_dw_correccion" ), null,
      Collections.singletonList( "gym_dw_correccion" ) ) ), "una base de la solucion leida del .ktr" );
    igual( "[db.unsafe.chars]", claves( CorrectorBases.inseguras( packConBase( "x`; DROP DATABASE y" ), null,
      null ) ), "caracteres raros" );
    Pack tipo = packConBase( "gym_dw_correccion" );
    tipo.bases.get( 0 ).tipo = "ORACLE";
    igual( "[db.unsafe.type]", claves( CorrectorBases.inseguras( tipo, null, null ) ), "tipo que no sabe recrear" );
    igual( Arrays.asList( "_w1", "_w2" ), CorrectorBases.sufijos( packConBase( "gym_dw_correccion" ), 2 ),
      "un sufijo por worker" );
    igual( Collections.singletonList( "" ), CorrectorBases.sufijos( packConBase( "gym_dw_correccion" ), 1 ),
      "con uno, sin sufijo" );
    Pack uno = packConBase( "gym_dw_correccion" );
    uno.seguridad = new SeguridadBases();
    uno.seguridad.unWorker = true;
    igual( 1, CorrectorBases.workersPermitidos( uno, 3 ), "single_worker" );
    igual( "gym_dw_correccion", CorrectorBases.propuesta( "gym_dw" ), "la propuesta del asistente" );
    igual( "ya_test", CorrectorBases.propuesta( "ya_test" ), "una que ya es de prueba queda igual" );
    String es = CorrectorBases.inseguras( packConBase( "gym_dw" ), null, null ).get( 0 ).texto( "es" );
    contiene( es, "nunca puede ser una de las tuyas", "en español: " + es );
  }

  /** Un pack 2 en disco: pack.json, bd/, esperado/visible/DIM_CLIENTE.csv. */
  private File packEnDisco( String nombre, Pack p, String extraJson ) throws Exception {
    File d = new File( dir, "packs/" + nombre );
    CorrectorSandbox.borrar( d );
    BasesH2.escribir( new File( d, "bd/crear_dw.sql" ), BasesH2.CREAR_DW );
    BasesH2.escribir( new File( d, "esperado/visible/DIM_CLIENTE.csv" ), "ID_CLIENTE;NOMBRE;CIUDAD\n10;Ana;Madrid\n" );
    BasesH2.escribir( new File( d, "enunciado.md" ), "# DW\n" );
    String json = CorrectorJson.escribir( p, true );
    if ( extraJson != null ) {
      json = json.replaceFirst( "\"original_database\" : \"gym_dw\"", "\"original_database\" : \"gym_dw\", "
        + extraJson );
    }
    BasesH2.escribir( new File( d, "pack.json" ), json );
    return d;
  }

  private Pack pack2() {
    Pack p = packConBase( "gym_dw_correccion" );
    p.formato = Pack.FORMATO_2;
    p.titulo = "DW";
    p.enunciado = "enunciado.md";
    p.bases.get( 0 ).scripts.add( "bd/crear_dw.sql" );
    p.casos.add( new Caso( "visible", null, "esperado/visible", false ) );
    p.salida.captura = Pack.Salida.CAPTURA_TABLAS;
    TablaSalida t = new TablaSalida( "GYM_DW", "DIM_CLIENTE" );
    t.columnasClave.add( "ID_CLIENTE" );
    t.columnas.add( new Pack.Columna( "ID_CLIENTE", "Integer" ) );
    t.columnas.add( new Pack.Columna( "NOMBRE", "String" ) );
    t.columnas.add( new Pack.Columna( "CIUDAD", "String" ) );
    p.salida.tablas.add( t );
    p.rubrica.add( new io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica( "values", 1 ) );
    return p.completar();
  }

  private static String codigos( CorrectorPack cp ) {
    List<String> l = new ArrayList<String>();
    for ( CorrectorPack.Problema p : cp.errores() ) {
      l.add( p.codigo );
    }
    return l.toString();
  }

  public void pruebaE_Pack2() throws Exception {
    try ( CorrectorPack cp = CorrectorPack.abrir( packEnDisco( "bueno", pack2(), null ) ) ) {
      cierto( cp.valido(), "el pack 2 valida: " + cp.textoProblemas( "en" ) );
      igual( "gym_dw_correccion", cp.pack().bases.get( 0 ).base, "la base de prueba" );
      igual( 1, cp.pack().salida.tablas.size(), "la tabla" );
    }
    String json = new String( Files.readAllBytes( new File( dir, "packs/bueno/pack.json" ).toPath() ),
      StandardCharsets.UTF_8 );
    contiene( json, "\"test_databases\"", "se escribe el bloque" );
    noContiene( json, "\"password\"", "nunca una contraseña" );
    igual( Pack.FORMATO_2, pack2().formatoNecesario(), "formato 2" );
    igual( Pack.FORMATO, new Pack().completar().formatoNecesario(), "un pack sin bases sigue en el formato 1" );

    Pack viejo = pack2();
    viejo.formato = Pack.FORMATO;
    try ( CorrectorPack cp = CorrectorPack.abrir( packEnDisco( "viejo", viejo, null ) ) ) {
      contiene( codigos( cp ), CorrectorPack.FORMATO_VIEJO, "bases con el formato 1" );
    }
    try ( CorrectorPack cp = CorrectorPack.abrir( packEnDisco( "clave", pack2(), "\"password\" : \"x\"" ) ) ) {
      contiene( codigos( cp ), CorrectorPack.CLAVE_EN_PACK, "la contraseña en el pack" );
    }
    Pack original = pack2();
    original.bases.get( 0 ).base = "gym_dw";
    try ( CorrectorPack cp = CorrectorPack.abrir( packEnDisco( "original", original, null ) ) ) {
      falso( cp.valido(), "una base de prueba que es la original no valida" );
      contiene( codigos( cp ), CorrectorPack.BASE_INSEGURA, "el codigo" );
      contiene( cp.textoProblemas( "es" ), "nunca puede ser una de las tuyas", "en español" );
      contiene( cp.textoProblemas( "pt" ), "nunca pode ser uma das suas", "en portugues" );
    }
    Pack sinCsv = pack2();
    sinCsv.salida.tablas.get( 0 ).nombre = "FACT";
    sinCsv.salida.tablas.get( 0 ).columnas.clear();
    try ( CorrectorPack cp = CorrectorPack.abrir( packEnDisco( "sincsv", sinCsv, null ) ) ) {
      contiene( codigos( cp ), CorrectorPack.FALTA_TABLA, "falta el CSV de la tabla" );
    }
    Pack sinTablas = pack2();
    sinTablas.salida.tablas.clear();
    try ( CorrectorPack cp = CorrectorPack.abrir( packEnDisco( "sintablas", sinTablas, null ) ) ) {
      contiene( codigos( cp ), CorrectorPack.SIN_TABLAS, "tables sin tablas" );
    }
    // compatibilidad: los packs 0.6.0 siguen validando igual
    try ( CorrectorPack cp = CorrectorPack.abrir( CorrectorRecursos.pack( CorrectorRecursos.VENTAS ) ) ) {
      cierto( cp.valido(), "el pack 1 de siempre: " + cp.textoProblemas( "en" ) );
      igual( Pack.FORMATO, cp.pack().formato, "formato 1" );
    }
  }

  // ================================================================ textos

  private static Properties bundle( String idioma ) throws Exception {
    Properties p = new Properties();
    File f = new File( EntornoKettle.recursos(), "../../main/resources/" + Rotulos.CARPETA + "corrector_" + idioma
      + ".properties" );
    try ( InputStreamReader r = new InputStreamReader( new FileInputStream( f ), StandardCharsets.UTF_8 ) ) {
      p.load( r );
    }
    return p;
  }

  private static int marcas( String patron ) {
    Matcher m = Pattern.compile( "%(\\d+\\$)?s" ).matcher( patron );
    Set<String> l = new LinkedHashSet<String>();
    int n = 0;
    while ( m.find() ) {
      n++;
      if ( m.group( 1 ) != null ) {
        l.add( m.group( 1 ) );
      }
    }
    return l.isEmpty() ? n : l.size();
  }

  /** Todo codigo de error del corrector tiene su texto en en, es y pt, con los mismos argumentos y traducido. */
  public void pruebaF_Textos() throws Exception {
    Properties en = bundle( "en" );
    Properties es = bundle( "es" );
    Properties pt = bundle( "pt" );
    Set<String> claves = new LinkedHashSet<String>();
    for ( String c : CorrectorCrearPack.ERRORES ) {
      claves.add( "error." + c );
    }
    for ( String c : ResultadoCaso.MOTIVOS ) {
      claves.add( "motivo." + c );
    }
    for ( String c : ResultadoCaso.ESTADOS ) {
      claves.add( "caso." + c );
    }
    for ( String c : ResultadoCorreccion.ESTADOS ) {
      claves.add( "estado." + c );
    }
    for ( String c : CorrectorPack.CODIGOS ) {
      claves.add( "pack." + c );
    }
    // y cada clave de un mensaje del corrector en el codigo (new Mensaje( "clave", ...))
    File src = new File( EntornoKettle.recursos(), "../../main/java/io/github/ivi2511/pentahomcp" );
    Pattern p = Pattern.compile( "Mensaje\\(\\s*\"([a-z_.]+)\"" );
    int archivos = 0;
    for ( File f : src.listFiles() ) {
      if ( f.getName().endsWith( ".java" ) ) {
        Matcher m = p.matcher( new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ) );
        while ( m.find() ) {
          claves.add( m.group( 1 ) );
          archivos++;
        }
      }
    }
    cierto( archivos > 100, "hay mensajes en el codigo: " + archivos );
    List<String> mal = new ArrayList<String>();
    for ( String k : claves ) {
      for ( Properties b : Arrays.asList( en, es, pt ) ) {
        if ( b.getProperty( k ) == null ) {
          mal.add( ( b == en ? "en" : b == es ? "es" : "pt" ) + " no tiene " + k );
        }
      }
      if ( en.getProperty( k ) != null && es.getProperty( k ) != null && pt.getProperty( k ) != null ) {
        int n = marcas( en.getProperty( k ) );
        if ( marcas( es.getProperty( k ) ) != n || marcas( pt.getProperty( k ) ) != n ) {
          mal.add( k + ": distinta cantidad de argumentos" );
        }
        boolean letras = en.getProperty( k ).replaceAll( "%(\\d+\\$)?s", "" ).matches( ".*[A-Za-z]{3,}.*" );
        if ( letras && k.matches( "(det|db|crear|error)\\..*" ) && ( en.getProperty( k ).equals( es.getProperty( k ) )
          || en.getProperty( k ).equals( pt.getProperty( k ) ) ) ) {
          mal.add( k + ": sin traducir" );
        }
      }
    }
    igual( "[]", mal.toString(), "textos de " + claves.size() + " claves" );

    // el mensaje del usuario, en una UI en español: nada en ingles
    Mensaje det = new Mensaje( "det.connection.not_pack.step", "GYM_DW", "MYSQL", "Buscar clave cliente" );
    CorrectorCrearPack.Fallo f = new CorrectorCrearPack.Fallo( "solution_blocked", new Mensaje(
      "crear.err.blocked_case", "visible", CorrectorTextos.clave( "motivo.foreign_connection" ), det.anidado() ) );
    Rotulos t = Rotulos.de( CorrectorTextos.AREA, "es" );
    String ui = t.f( "ui.as_error", f.nombre( t ), f.texto( t ) );
    igual( "No se pudo armar el pack (el corrector no ejecutaría la solución): Caso visible: el corrector no "
      + "ejecutaría la solución (conexión ajena al ejercicio): la conexión «GYM_DW» (MYSQL), que usa el step «Buscar "
      + "clave cliente», no es la base del pack", ui, "el error del asistente en español" );
    for ( String ingles : Arrays.asList( "grader", "would not", "connection", "used by", "is not" ) ) {
      noContiene( ui, ingles, "sin ingles" );
    }
    contiene( f.getMessage(), "connection 'GYM_DW' (MYSQL) used by step 'Buscar clave cliente' is not the pack "
      + "database", "por MCP sigue en ingles" );
    contiene( new Mensaje( "crear.err.blocked_case", "visible", CorrectorTextos.clave( "motivo.foreign_connection" ),
      det.anidado() ).texto( "pt" ), "não é a base do pack", "en portugues" );
  }

  // ================================================================ sandbox (Kettle, sin ejecutar nada)

  /** Un pack 2 en memoria con las dos bases H2 (no hace falta el servidor: preparar no se conecta). */
  private Pack packH2( String tipo ) {
    Pack p = new Pack();
    p.id = "dw";
    p.formato = Pack.FORMATO_2;
    for ( String[] b : new String[][] { { BasesH2.ORIGEN, "gym_oltp" }, { BasesH2.DW, "gym_dw" } } ) {
      BaseDePrueba x = new BaseDePrueba( b[ 0 ], tipo, "localhost", "9", b[ 1 ] + "_correccion", "sa" );
      x.baseOriginal = b[ 1 ];
      p.bases.add( x );
    }
    p.casos.add( new Caso( "visible", null, "esperado/visible", false ) );
    p.salida.captura = Pack.Salida.CAPTURA_TABLAS;
    p.politica.permitirSteps.addAll( CorrectorCrearPack.PASOS_DE_BASE );
    return p.completar();
  }

  private Preparacion preparar( Pack p, File ktr, String sufijo ) throws Exception {
    Entrega e = CorrectorPolitica.cargar( ktr );
    CorrectorSandbox.Opciones op = new CorrectorSandbox.Opciones();
    op.bases = new CorrectorBases.Sesion( p, dir, sufijo, Collections.singletonMap( "gym_dw", "secreta" ) );
    return CorrectorSandbox.preparar( p, dir, p.casos.get( 0 ), e, new File( dir, "sandbox/" + ktr.getName()
      .replace( ".ktr", "" ) ), op );
  }

  public void pruebaG_Sandbox() throws Exception {
    BasesH2 sinServidor = new BasesH2( 9 );
    File sol = sinServidor.guardar( BasesH2.Variante.SOLUCION, new File( dir, "ktr/solucion.ktr" ) );
    Preparacion p = preparar( packH2( "H2" ), sol, "_w2" );
    falso( p.bloqueada(), "la solucion corre: " + p.detalle );
    igual( new HashSet<String>( Arrays.asList( BasesH2.ORIGEN, BasesH2.DW ) ), p.conexionesPrueba.keySet(),
      "las dos conexiones van a su base de prueba" );
    igual( "gym_dw_correccion_w2", p.conexionesPrueba.get( BasesH2.DW ).nombre, "con el sufijo del worker" );
    String xml = new String( Files.readAllBytes( p.ktr.toPath() ), StandardCharsets.UTF_8 );
    contiene( xml, "<database>gym_dw_correccion_w2</database>", "la copia apunta a la de prueba" );
    contiene( xml, "<database>gym_oltp_correccion_w2</database>", "el origen tambien" );
    noContiene( xml, "<database>gym_dw</database>", "nada apunta a la original" );
    noContiene( xml, BasesH2.CLAVE, "sin la contraseña de la solucion" );
    noContiene( xml, "Encrypted", "ninguna contraseña en la copia" );
    contiene( p.redirecciones.toString(), "test database gym_dw_correccion_w2", "la nota del docente" );

    // una conexion que no es de ninguna base de prueba: bloquea, con el texto en el idioma de la UI
    Preparacion ajena = preparar( packH2( "H2" ), sinServidor.guardar( BasesH2.Variante.CONEXION_AJENA, new File( dir,
      "ktr/ajena.ktr" ) ), "" );
    igual( ResultadoCaso.CONEXION_AJENA, ajena.motivo, "conexion ajena" );
    igual( "det.connection.not_test.step", ajena.mensaje.clave, "la clave del detalle" );
    contiene( ajena.detalle, "is none of the exercise's test databases", "en ingles en el JSON" );
    contiene( ajena.mensaje.texto( "es" ), "no es ninguna de las bases de prueba", "en español en la UI" );

    // SQL que escribe archivos en el servidor
    Preparacion archivo = preparar( packH2( "H2" ), sinServidor.guardar( BasesH2.Variante.SQL_A_ARCHIVO, new File(
      dir, "ktr/archivo.ktr" ) ), "" );
    igual( ResultadoCaso.SQL_PROHIBIDO, archivo.motivo, "INTO OUTFILE: " + archivo.detalle );

    // MySQL: los esquemas de los steps y el SQL con el nombre original pasan a la de prueba; otro esquema bloquea
    org.pentaho.di.trans.TransMeta tm = sinServidor.trans( BasesH2.Variante.TABLE_OUTPUT );
    ( (org.pentaho.di.trans.steps.tableoutput.TableOutputMeta) tm.findStep( "Cargar dim_cliente" )
      .getStepMetaInterface() ).setSchemaName( "gym_dw" );
    ( (org.pentaho.di.trans.steps.tableinput.TableInputMeta) tm.findStep( "Leer clientes" ).getStepMetaInterface() )
      .setSQL( "SELECT id, nombre, id_ciudad FROM gym_oltp.clientes" );
    for ( org.pentaho.di.core.database.DatabaseMeta d : tm.getDatabases() ) {
      d.setDatabaseType( "MYSQL" );
    }
    File my = BasesH2.escribir( new File( dir, "ktr/mysql.ktr" ), tm.getXML() );
    Preparacion pm = preparar( packH2( "MYSQL" ), my, "" );
    falso( pm.bloqueada(), "MySQL con los nombres originales: corre: " + pm.detalle );
    String xm = new String( Files.readAllBytes( pm.ktr.toPath() ), StandardCharsets.UTF_8 );
    contiene( xm, "<schema>gym_dw_correccion</schema>", "el esquema del Table output" );
    contiene( xm, "gym_oltp_correccion.clientes", "el SQL del Table input" );
    ( (org.pentaho.di.trans.steps.tableoutput.TableOutputMeta) tm.findStep( "Cargar dim_cliente" )
      .getStepMetaInterface() ).setSchemaName( "contabilidad" );
    Preparacion otro = preparar( packH2( "MYSQL" ), BasesH2.escribir( new File( dir, "ktr/mysql2.ktr" ),
      tm.getXML() ), "" );
    igual( ResultadoCaso.ESQUEMA_AJENO, otro.motivo, "un esquema que no es de prueba" );
    contiene( otro.mensaje.texto( "es" ), "usa el esquema «contabilidad»", "en español" );

    // 0.7.1, H2 (y PostgreSQL): el esquema y el SQL calificado con la base original tambien van a la de prueba;
    // otro esquema vive dentro de la base de prueba y no bloquea
    org.pentaho.di.trans.TransMeta th = sinServidor.trans( BasesH2.Variante.TABLE_OUTPUT );
    ( (org.pentaho.di.trans.steps.tableoutput.TableOutputMeta) th.findStep( "Cargar dim_cliente" )
      .getStepMetaInterface() ).setSchemaName( "gym_dw" );
    ( (org.pentaho.di.trans.steps.tableinput.TableInputMeta) th.findStep( "Leer clientes" ).getStepMetaInterface() )
      .setSQL( "SELECT c.id, c.nombre, c.id_ciudad FROM gym_oltp.clientes c" );
    Preparacion ph = preparar( packH2( "H2" ), BasesH2.escribir( new File( dir, "ktr/h2esquema.ktr" ),
      th.getXML() ), "" );
    falso( ph.bloqueada(), "H2 con los nombres originales: corre: " + ph.detalle );
    String xh = new String( Files.readAllBytes( ph.ktr.toPath() ), StandardCharsets.UTF_8 );
    contiene( xh, "<schema>gym_dw_correccion</schema>", "el esquema del Table output en H2" );
    contiene( xh, "gym_oltp_correccion.clientes c", "el SQL calificado en H2" );
    contiene( xh, "c.id, c.nombre", "el alias no se toca" );
    ( (org.pentaho.di.trans.steps.tableoutput.TableOutputMeta) th.findStep( "Cargar dim_cliente" )
      .getStepMetaInterface() ).setSchemaName( "staging" );
    Preparacion ph2 = preparar( packH2( "H2" ), BasesH2.escribir( new File( dir, "ktr/h2esquema2.ktr" ),
      th.getXML() ), "" );
    falso( ph2.bloqueada(), "en H2 otro esquema queda dentro de la base de prueba: " + ph2.detalle );
    contiene( new String( Files.readAllBytes( ph2.ktr.toPath() ), StandardCharsets.UTF_8 ),
      "<schema>staging</schema>", "y no se toca" );
  }

  /** 0.7.1: solo los nombres que califican a otro (o van tras USE) cambian; un nombre que tambien es tabla, no. */
  public void pruebaB2_RenombrarCalificados() throws Exception {
    java.util.Map<String, String> m = Collections.singletonMap( "gym_dw", "gym_dw_test" );
    java.util.Set<String> amb = new HashSet<String>();
    igual( "SELECT * FROM gym_dw_test.ventas v JOIN `gym_dw_test`.`dim` d ON v.id = d.id WHERE x = 'gym_dw.a' -- gym_dw.b",
      CorrectorBases.renombrarCalificados( "SELECT * FROM gym_dw.ventas v JOIN `gym_dw`.`dim` d ON v.id = d.id "
        + "WHERE x = 'gym_dw.a' -- gym_dw.b", m, amb ), "calificados, sin cadenas ni comentarios" );
    cierto( amb.isEmpty(), "sin ambiguos" );
    igual( "USE gym_dw_test", CorrectorBases.renombrarCalificados( "USE gym_dw", m, amb ), "USE" );
    igual( "SELECT gym_dw FROM t", CorrectorBases.renombrarCalificados( "SELECT gym_dw FROM t", m, amb ),
      "una columna con ese nombre no" );
    String ventas = "SELECT ventas.importe FROM ventas";
    igual( ventas, CorrectorBases.renombrarCalificados( ventas, Collections.singletonMap( "ventas", "ventas_test" ),
      amb ), "la tabla ventas de la base ventas: ambiguo, no se toca" );
    cierto( amb.contains( "ventas" ), "y queda anotado" );
  }


  // ================================================================ de punta a punta con H2 (JVMs hijas)

  private CorrectorProcesos.Opciones procesos( int workers ) {
    CorrectorProcesos.Opciones op = new CorrectorProcesos.Opciones();
    op.workers = workers;
    op.xmxMb = 640;
    op.pdi = EntornoKettle.pdi();
    op.versionPlugin = "prueba";
    op.claves.putAll( BasesH2.claves( BasesH2.CLAVE ) );
    return op;
  }

  private Map<String, ResultadoCorreccion> corregir( File dirPack, int workers, boolean conservar, String clave,
                                                     String... alumnos ) throws Exception {
    Pack p;
    try ( CorrectorPack cp = CorrectorPack.cargar( dirPack ) ) {
      p = cp.pack();
    }
    File salida = new File( dir, "lote-" + workers + "-" + System.nanoTime() );
    List<Pedido> pedidos = new ArrayList<Pedido>();
    for ( String a : alumnos ) {
      pedidos.add( new Pedido( a, new File( entregas, a + ".ktr" ), new File( salida, a + ".json" ) ) );
    }
    CorrectorProcesos.Opciones op = procesos( workers );
    op.conservarSandbox = conservar;
    op.claves.clear();
    op.claves.putAll( BasesH2.claves( clave ) );
    List<ResultadoCorreccion> rs = CorrectorProcesos.corregir( dirPack, p, pedidos, salida, op );
    Map<String, ResultadoCorreccion> m = new LinkedHashMap<String, ResultadoCorreccion>();
    for ( int i = 0; i < alumnos.length; i++ ) {
      m.put( alumnos[ i ], rs.get( i ) );
    }
    ultimaSalida = salida;
    return m;
  }

  private File ultimaSalida;

  /** El pack se crea desde la solucion: corre en una hija contra las bases de prueba recien creadas. */
  public void pruebaH_CrearPack() throws Exception {
    BasesH2 s = h2();
    entregas = new File( dir, "entregas" );
    File sol = s.guardar( BasesH2.Variante.SOLUCION, new File( dir, "profe/carga_dw.ktr" ) );
    s.guardar( BasesH2.Variante.SOLUCION, new File( entregas, "correcta.ktr" ) );
    s.guardar( BasesH2.Variante.TABLE_OUTPUT, new File( entregas, "duplica.ktr" ) );
    s.guardar( BasesH2.Variante.CONEXION_AJENA, new File( entregas, "ajena.ktr" ) );
    pack = new File( dir, "packs/ejercicio2" );
    CorrectorCrearPack.Opciones op = s.opciones( sol, pack, new File( dir, "profe/scripts" ), BasesH2.CLAVE );
    op.procesos = procesos( 1 );
    op.copiaAlumnos = new File( dir, "packs/ejercicio2-alumnos.zip" );
    long t0 = System.currentTimeMillis();
    CorrectorCrearPack.Creado cr = CorrectorCrearPack.crear( op );
    System.out.println( "  crear el pack con bases de prueba: " + ( System.currentTimeMillis() - t0 ) + " ms" );
    igual( 2, cr.casos.size(), "visible y oculto" );
    igual( 3, cr.casos.get( 0 ).filas, "visible: 3 clientes" );
    igual( 4, cr.casos.get( 1 ).filas, "oculto: 4 clientes (la base se recreo antes del caso)" );
    igual( 1, cr.detectado.tablas.size(), "una tabla detectada" );
    igual( "DIM_CLIENTE", cr.detectado.tablas.get( 0 ).nombre, "la que escribe el Insert/Update" );
    igual( Collections.singletonList( "ID_CLIENTE" ), cr.detectado.tablas.get( 0 ).columnasClave,
      "la clave del Insert/Update" );
    try ( CorrectorPack cp = CorrectorPack.abrir( pack ) ) {
      cierto( cp.valido(), "el pack creado valida: " + cp.textoProblemas( "en" ) );
      Pack p = cp.pack();
      igual( Pack.FORMATO_2, p.formato, "formato 2" );
      igual( Pack.Salida.CAPTURA_TABLAS, p.salida.captura, "captura tables" );
      igual( 2, p.bases.size(), "dos bases de prueba" );
      igual( "gym_dw", p.base( BasesH2.DW ).baseOriginal, "la original queda anotada" );
      igual( Integer.valueOf( 2 ), p.casos.get( 0 ).ejecuciones, "dos ejecuciones" );
      cierto( p.politica.permite( "TableOutput" ), "los steps de base quedan permitidos" );
      String esp = new String( Files.readAllBytes( cp.esperado( p.casos.get( 1 ), p.salida.tablas.get( 0 ) )
        .toPath() ), StandardCharsets.UTF_8 );
      contiene( esp, "Diego", "el esperado del oculto" );
      noContiene( esp, "Ana", "sin los datos del visible: se recreo" );
      contiene( esp, "Vigo", "con la ciudad del lookup" );
    }
    sinClave( pack, "el pack" );
    falso( s.existe( "gym_oltp" ) || s.existe( "gym_dw" ), "las bases originales no se tocaron" );
    cierto( s.existe( "gym_dw_correccion" ), "la de prueba se creo" );
    // la copia para alumnos: sin la solucion, sin el caso oculto ni sus scripts
    File alumnos = new File( dir, "packs/alumnos" );
    CorrectorPack.descomprimir( op.copiaAlumnos, alumnos, 1 << 26, 1000 );
    falso( new File( alumnos, "bd/oculto" ).exists(), "sin los scripts del oculto" );
    cierto( new File( alumnos, "bd/visible" ).isDirectory(), "con los del visible" );
    falso( new File( alumnos, "solucion" ).exists(), "sin la solucion" );
  }

  /** Correcta 10; Table output, con dos ejecuciones, duplica; otra conexion, bloqueada. Un worker. */
  public void pruebaI_Corregir() throws Exception {
    BasesH2 s = h2();
    Map<String, ResultadoCorreccion> r = corregir( pack, 1, true, BasesH2.CLAVE, "correcta", "duplica", "ajena" );
    ResultadoCorreccion ok = r.get( "correcta" );
    igual( ResultadoCorreccion.CORREGIDA, ok.estado, "correcta: corregida (" + ok.detalle + ")" );
    igual( 10.0, ok.nota, "correcta: 10" );
    noNulo( ok.casos.get( 0 ).tablas, "la comparacion de cada tabla" );
    igual( "DIM_CLIENTE", ok.casos.get( 0 ).tablas.get( 0 ).tabla, "la tabla" );

    ResultadoCorreccion dup = r.get( "duplica" );
    igual( ResultadoCorreccion.CORREGIDA, dup.estado, "duplica: corrio (" + dup.detalle + ")" );
    cierto( dup.nota < 10, "duplica: menos de 10 (" + dup.nota + ")" );
    cierto( dup.casos.get( 0 ).comparacion.filas.clavesRepetidas > 0, "claves repetidas por la segunda ejecucion" );
    igual( 6, dup.casos.get( 0 ).comparacion.filas.reales, "3 filas x 2 ejecuciones" );
    falso( dup.criterio( "row_count" ).aprobado, "row_count no aprueba" );

    ResultadoCorreccion aj = r.get( "ajena" );
    igual( ResultadoCorreccion.BLOQUEADA, aj.estado, "ajena: bloqueada" );
    igual( ResultadoCaso.CONEXION_AJENA, aj.motivo, "por la conexion" );
    contiene( CorrectorTextos.detalle( Rotulos.de( CorrectorTextos.AREA, "es" ), aj ), "«OTRA»", "detalle en español" );
    contiene( aj.pistas.toString(), "«GYM_OLTP»", "la pista nombra las conexiones del ejercicio" );
    falso( s.existe( "otra_bd" ), "la base ajena no se toco" );
    falso( s.existe( "gym_oltp" ) || s.existe( "gym_dw" ), "las originales tampoco" );
    // el ultimo caso que corrio fue el oculto: la base de prueba tiene solo sus 4 clientes
    // (duplica: 4 clientes x 2 ejecuciones; sin recrear habria tambien las 6 del visible)
    igual( 8L, s.contar( "gym_dw_correccion", "SELECT COUNT(*) FROM DIM_CLIENTE" ), "recreada antes del oculto" );
    sinClave( ultimaSalida, "los resultados y los sandboxes" );
  }

  /** Dos workers: cada uno con su base (_w1, _w2), las mismas notas. */
  public void pruebaJ_DosWorkers() throws Exception {
    BasesH2 s = h2();
    Map<String, ResultadoCorreccion> r = corregir( pack, 2, false, BasesH2.CLAVE, "correcta", "duplica", "ajena" );
    igual( 10.0, r.get( "correcta" ).nota, "correcta: 10" );
    cierto( r.get( "duplica" ).nota < 10, "duplica" );
    igual( ResultadoCorreccion.BLOQUEADA, r.get( "ajena" ).estado, "ajena" );
    cierto( s.existe( "gym_dw_correccion_w1" ) && s.existe( "gym_dw_correccion_w2" ),
      "cada worker con su base de prueba" );
    cierto( s.existe( "gym_oltp_correccion_w1" ) && s.existe( "gym_oltp_correccion_w2" ), "y su origen" );
    falso( s.existe( "gym_dw" ), "la original nunca" );
  }

  /** Una base de prueba que es la original o sin sufijo: no se crea el pack, no se corrige; contraseña mala. */
  public void pruebaK_Rechazos() throws Exception {
    BasesH2 s = h2();
    File sol = s.guardar( BasesH2.Variante.SOLUCION, new File( dir, "profe2/carga_dw.ktr" ) );
    CorrectorCrearPack.Opciones op = s.opciones( sol, new File( dir, "packs/no" ), new File( dir, "profe2/scripts" ),
      BasesH2.CLAVE );
    op.bases.get( 1 ).base = "gym_dw";
    CorrectorCrearPack.Fallo f = lanza( CorrectorCrearPack.Fallo.class, () -> CorrectorCrearPack.crear( op ),
      "la base original" );
    igual( CorrectorCrearPack.E_BASE_INSEGURA, f.codigo, "el codigo" );
    contiene( f.texto( Rotulos.de( CorrectorTextos.AREA, "es" ) ), "es una base de la solución", "en español" );
    falso( new File( dir, "packs/no" ).exists(), "no se creo nada" );
    op.bases.get( 1 ).base = "gym_dw_copia";
    f = lanza( CorrectorCrearPack.Fallo.class, () -> CorrectorCrearPack.crear( op ), "sin sufijo" );
    contiene( f.getMessage(), "does not end in a test suffix", "sin sufijo de prueba" );
    falso( s.existe( "gym_dw_copia" ), "y no se creo la base" );

    // un pack cuya base de prueba se cambio a mano por la original: el lote no arranca
    Pack p;
    try ( CorrectorPack cp = CorrectorPack.abrir( pack ) ) {
      p = CorrectorJson.copia( cp.pack(), Pack.class ).completar();
    }
    p.base( BasesH2.DW ).base = "gym_dw";
    final Pack pf = p;
    CorrectorBases.Falla fa = lanza( CorrectorBases.Falla.class, () -> new CorrectorProcesos( pack, pf, new File( dir,
      "lote-no" ), procesos( 1 ), null ), "el lote se niega" );
    contiene( fa.mensaje.texto( "es" ), "No se pueden usar las bases de prueba", "en español" );
    // dos workers y el nombre con sufijo coincide con una base de la solucion: tambien
    p.base( BasesH2.DW ).base = "gym_dw_correccion";
    p.base( BasesH2.DW ).baseOriginal = "gym_dw_correccion_w2";
    lanza( CorrectorBases.Falla.class, () -> new CorrectorProcesos( pack, pf, new File( dir, "lote-no2" ), procesos(
      2 ), null ), "el sufijo del worker tambien se revisa" );

    // contraseña mala: crashed, con el error del servidor traducido
    ResultadoCorreccion mal = corregir( pack, 1, false, "otra-clave", "correcta" ).get( "correcta" );
    igual( ResultadoCorreccion.CAIDA, mal.estado, "sin poder conectarse: crashed (" + mal.detalle + ")" );
    igual( "db.err.case", mal.claveDetalle, "la clave del detalle" );
    contiene( CorrectorTextos.detalle( Rotulos.de( CorrectorTextos.AREA, "es" ), mal ), "No se pudo conectar con la "
      + "base de prueba", "en español" );
  }

  /** create_exercise_pack por MCP nunca acepta una contraseña. */
  public void pruebaL_McpSinClave() throws Exception {
    BasesH2 s = new BasesH2( 9 );
    File sol = s.guardar( BasesH2.Variante.SOLUCION, new File( dir, "mcp/carga_dw.ktr" ) );
    ObjectNode a = CorrectorJson.mapper().createObjectNode();
    a.put( "solution_file", sol.getAbsolutePath() );
    a.put( "pack_path", new File( dir, "mcp/pack" ).getAbsolutePath() );
    a.put( "title", "DW" );
    a.put( "capture", "tables" );
    ObjectNode b = a.putArray( "test_databases" ).addObject();
    b.put( "connection", BasesH2.DW );
    b.put( "password", "x" );
    ToolError e = lanza( ToolError.class, () -> CorrectorTools.crearPack( a ), "una contraseña" );
    contiene( e.getMessage(), "Never pass a password", "lo dice" );
  }

  /** Ningun archivo bajo dir tiene la contraseña en claro. */
  private static void sinClave( File dir, String que ) throws Exception {
    List<String> con = new ArrayList<String>();
    buscar( dir, BasesH2.CLAVE, con );
    igual( "[]", con.toString(), que + ": sin la contraseña en ningun archivo" );
  }

  private static void buscar( File f, String clave, List<String> con ) throws Exception {
    if ( f.isDirectory() ) {
      File[] h = f.listFiles();
      for ( File x : h == null ? new File[ 0 ] : h ) {
        buscar( x, clave, con );
      }
    } else if ( f.isFile() && f.length() < 20_000_000 ) {
      if ( new String( Files.readAllBytes( f.toPath() ), StandardCharsets.ISO_8859_1 ).contains( clave ) ) {
        con.add( f.getPath() );
      }
    }
  }
}
