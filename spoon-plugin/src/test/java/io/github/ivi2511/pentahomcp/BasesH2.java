package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.h2.tools.Server;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.databaselookup.DatabaseLookupMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;
import org.pentaho.di.trans.steps.insertupdate.InsertUpdateMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;

/**
 * Un servidor H2 en modo TCP dentro de la JVM de pruebas, que hace de «servidor de base de datos de la PC del
 * docente» para las pruebas de las bases de prueba del corrector (0.6.2): nunca se usa un MySQL ni un PostgreSQL de
 * la maquina. Con {@code -ifNotExists} cada base se crea al conectarse por primera vez, asi que un archivo
 * {@code <base>.mv.db} en {@link #carpeta} prueba que alguien se conecto: las bases originales del ejercicio
 * ({@code gym_oltp}, {@code gym_dw}) no tienen que aparecer nunca.
 * <p>
 * El ejercicio es el de la clase del usuario en chico: una base de origen (clientes y ciudades) y un data warehouse
 * con una dimension. La solucion lee los clientes, busca la ciudad con un Database lookup en el origen y carga la
 * dimension con un Insert/Update; las entregas de prueba la cambian (Table output, que duplica al repetir; una
 * conexion ajena).
 */
class BasesH2 {

  static final String USUARIO = "sa";
  static final String CLAVE = "clave-H2-de-prueba-7";
  static final String ORIGEN = "GYM_OLTP";
  static final String DW = "GYM_DW";

  final File carpeta;
  final int puerto;
  private final Server servidor;

  BasesH2( File carpeta ) throws Exception {
    this.carpeta = carpeta.getAbsoluteFile();
    if ( !this.carpeta.isDirectory() && !this.carpeta.mkdirs() ) {
      throw new IOException( "cannot create " + carpeta );
    }
    try ( ServerSocket s = new ServerSocket( 0 ) ) {
      puerto = s.getLocalPort();
    }
    servidor = Server.createTcpServer( "-tcpPort", String.valueOf( puerto ), "-ifNotExists", "-baseDir",
      this.carpeta.getAbsolutePath() ).start();
  }

  /** Sin servidor: solo arma las transformaciones y las bases (nadie se conecta a ese puerto). */
  BasesH2( int puerto ) {
    this.carpeta = null;
    this.puerto = puerto;
    this.servidor = null;
  }

  void parar() {
    if ( servidor != null ) {
      servidor.stop();
    }
  }

  /** Hay un archivo de esa base: alguien se conecto a ella. */
  boolean existe( String base ) {
    return new File( carpeta, base + ".mv.db" ).isFile();
  }

  /** Una consulta de un solo numero sobre una base que ya existe (si no existe, no se conecta). */
  long contar( String base, String sql ) throws Exception {
    if ( !existe( base ) ) {
      throw new IllegalStateException( "the database " + base + " does not exist" );
    }
    try ( Connection c = DriverManager.getConnection( "jdbc:h2:tcp://localhost:" + puerto + "/" + base, USUARIO,
      CLAVE ); Statement st = c.createStatement(); ResultSet rs = st.executeQuery( sql ) ) {
      rs.next();
      return rs.getLong( 1 );
    }
  }

  /** La base de prueba de una conexion del ejercicio, en este servidor. */
  BaseDePrueba base( String conexion, String base, String original ) {
    BaseDePrueba b = new BaseDePrueba( conexion, BaseDePrueba.H2, "localhost", String.valueOf( puerto ), base,
      USUARIO );
    b.baseOriginal = original;
    return b;
  }

  /** Las contraseñas de las dos conexiones (por nombre sin mayusculas). */
  static Map<String, String> claves( String clave ) {
    Map<String, String> m = new LinkedHashMap<String, String>();
    m.put( ORIGEN.toLowerCase(), clave );
    m.put( DW.toLowerCase(), clave );
    return m;
  }

  // ---------------------------------------------------------------- scripts

  /** Las tablas del origen (sin datos). */
  static final String CREAR_ORIGEN = "-- origen del ejercicio\n"
    + "CREATE TABLE ciudades (id INT PRIMARY KEY, nombre VARCHAR(40));\n"
    + "CREATE TABLE clientes (id INT PRIMARY KEY, nombre VARCHAR(40), id_ciudad INT);\n";
  /** Los datos del caso visible. */
  static final String DATOS_VISIBLE = "INSERT INTO ciudades VALUES (1, 'Madrid'), (2, 'Bilbao');\n"
    + "INSERT INTO clientes VALUES (10, 'Ana', 1), (11, 'Bruno', 2), (12, 'Carla', 1);\n";
  /** Los datos del caso oculto: otros clientes y otra ciudad. */
  static final String DATOS_OCULTO = "INSERT INTO ciudades VALUES (1, 'Sevilla'), (3, 'Vigo');\n"
    + "INSERT INTO clientes VALUES (20, 'Diego', 3), (21, 'Elena', 1), (22, 'Fede', 3), (23, 'Gabi', 1);\n";
  /** El data warehouse: la dimension sin clave primaria (un Table output repetido la duplica). */
  static final String CREAR_DW = "/* data warehouse; con un ; en el comentario */\n"
    + "CREATE TABLE dim_cliente (id_cliente INT, nombre VARCHAR(40), ciudad VARCHAR(40));\n";

  static File escribir( File f, String texto ) throws IOException {
    f.getAbsoluteFile().getParentFile().mkdirs();
    Files.write( f.toPath(), texto.getBytes( StandardCharsets.UTF_8 ) );
    return f;
  }

  // ---------------------------------------------------------------- transformaciones

  /** Que cambia una entrega respecto de la solucion. */
  enum Variante {
    SOLUCION, TABLE_OUTPUT, CONEXION_AJENA, SQL_A_ARCHIVO
  }

  /**
   * La solucion (o una entrega) con las conexiones a las bases ORIGINALES del ejercicio ({@code gym_oltp},
   * {@code gym_dw}) en este servidor: el corrector tiene que apuntarlas a las de prueba y no conectarse nunca a estas.
   */
  TransMeta trans( Variante v ) {
    TransMeta tm = new TransMeta();
    tm.setName( "carga_dw" );
    DatabaseMeta origen = new DatabaseMeta( ORIGEN, "H2", "Native", "localhost", "gym_oltp", String.valueOf( puerto ),
      USUARIO, CLAVE );
    DatabaseMeta dw = new DatabaseMeta( DW, "H2", "Native", "localhost", "gym_dw", String.valueOf( puerto ), USUARIO,
      CLAVE );
    tm.addDatabase( origen );
    tm.addDatabase( dw );

    TableInputMeta ti = new TableInputMeta();
    ti.setDefault();
    ti.setDatabaseMeta( origen );
    ti.setSQL( v == Variante.SQL_A_ARCHIVO ? "SELECT ID, NOMBRE, ID_CIUDAD FROM CLIENTES INTO OUTFILE 'x.csv'"
      : "SELECT ID, NOMBRE, ID_CIUDAD FROM CLIENTES ORDER BY ID" );
    StepMeta leer = paso( tm, "TableInput", "Leer clientes", ti, 50 );

    DatabaseLookupMeta dl = new DatabaseLookupMeta();
    dl.setDefault();
    dl.allocate( 1, 1 );
    dl.setDatabaseMeta( origen );
    dl.setTablename( "CIUDADES" );
    dl.setStreamKeyField1( new String[] { "ID_CIUDAD" } );
    dl.setStreamKeyField2( new String[] { null } );
    dl.setTableKeyField( new String[] { "ID" } );
    dl.setKeyCondition( new String[] { "=" } );
    dl.setReturnValueField( new String[] { "NOMBRE" } );
    dl.setReturnValueNewName( new String[] { "CIUDAD" } );
    dl.setReturnValueDefault( new String[] { null } );
    dl.setReturnValueDefaultType( new int[] { ValueMetaInterface.TYPE_STRING } );
    StepMeta buscar = paso( tm, "DBLookup", "Buscar ciudad", dl, 200 );

    StepMeta cargar;
    if ( v == Variante.TABLE_OUTPUT ) {
      TableOutputMeta to = new TableOutputMeta();
      to.setDefault();
      to.setDatabaseMeta( dw );
      to.setTableName( "DIM_CLIENTE" );
      to.setSpecifyFields( true );
      to.allocate( 3 );
      to.setFieldDatabase( new String[] { "ID_CLIENTE", "NOMBRE", "CIUDAD" } );
      to.setFieldStream( new String[] { "ID", "NOMBRE", "CIUDAD" } );
      cargar = paso( tm, "TableOutput", "Cargar dim_cliente", to, 350 );
    } else {
      InsertUpdateMeta iu = new InsertUpdateMeta();
      iu.setDefault();
      iu.allocate( 1, 3 );
      iu.setDatabaseMeta( dw );
      iu.setTableName( "DIM_CLIENTE" );
      iu.setKeyLookup( new String[] { "ID_CLIENTE" } );
      iu.setKeyStream( new String[] { "ID" } );
      iu.setKeyStream2( new String[] { null } );
      iu.setKeyCondition( new String[] { "=" } );
      iu.setUpdateLookup( new String[] { "ID_CLIENTE", "NOMBRE", "CIUDAD" } );
      iu.setUpdateStream( new String[] { "ID", "NOMBRE", "CIUDAD" } );
      iu.setUpdate( new Boolean[] { Boolean.FALSE, Boolean.TRUE, Boolean.TRUE } );
      cargar = paso( tm, "InsertUpdate", "Cargar dim_cliente", iu, 350 );
    }
    tm.addTransHop( new TransHopMeta( leer, buscar ) );
    tm.addTransHop( new TransHopMeta( buscar, cargar ) );

    if ( v == Variante.CONEXION_AJENA ) {
      DatabaseMeta otra = new DatabaseMeta( "OTRA", "H2", "Native", "localhost", "otra_bd", String.valueOf( puerto ),
        USUARIO, CLAVE );
      tm.addDatabase( otra );
      TableInputMeta t2 = new TableInputMeta();
      t2.setDefault();
      t2.setDatabaseMeta( otra );
      t2.setSQL( "SELECT 1 AS X" );
      StepMeta leer2 = paso( tm, "TableInput", "Leer otra base", t2, 500 );
      DummyTransMeta dm = new DummyTransMeta();
      StepMeta fin = paso( tm, "Dummy", "Nada", dm, 650 );
      tm.addTransHop( new TransHopMeta( leer2, fin ) );
    }
    return tm;
  }

  private static StepMeta paso( TransMeta tm, String id, String nombre,
                                org.pentaho.di.trans.step.StepMetaInterface m, int x ) {
    StepMeta s = new StepMeta( id, nombre, m );
    s.setLocation( x, 100 );
    s.setDraw( true );
    tm.addStep( s );
    return s;
  }

  File guardar( Variante v, File destino ) throws Exception {
    return escribir( destino, trans( v ).getXML() );
  }

  /** Las opciones de crear el pack de la clase: dos bases de prueba, scripts, un caso oculto y cada caso dos veces. */
  CorrectorCrearPack.Opciones opciones( File solucion, File destino, File scripts, String claveDocente ) throws Exception {
    CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
    op.solucion = solucion;
    op.destino = destino;
    op.titulo = "Ejercicio 2 - Carga del DW";
    op.idioma = "es";
    op.captura = io.github.ivi2511.pentahomcp.corrector.modelo.Pack.Salida.CAPTURA_TABLAS;
    op.bases = new ArrayList<BaseDePrueba>();
    op.bases.add( base( ORIGEN, "gym_oltp_correccion", "gym_oltp" ) );
    op.bases.add( base( DW, "gym_dw_correccion", "gym_dw" ) );
    op.scriptsBase = new LinkedHashMap<String, List<File>>();
    op.scriptsBase.put( ORIGEN, new ArrayList<File>( java.util.Arrays.asList( escribir( new File( scripts,
      "crear_origen.sql" ), CREAR_ORIGEN ) ) ) );
    op.scriptsBase.put( DW, Collections.singletonList( escribir( new File( scripts, "crear_dw.sql" ), CREAR_DW ) ) );
    CorrectorCrearPack.CasoNuevo visible = new CorrectorCrearPack.CasoNuevo( CorrectorCrearPack.VISIBLE, null, false );
    visible.scripts = new LinkedHashMap<String, List<File>>();
    visible.scripts.put( ORIGEN, Collections.singletonList( escribir( new File( scripts, "datos_visible.sql" ),
      DATOS_VISIBLE ) ) );
    visible.ejecuciones = 2;
    CorrectorCrearPack.CasoNuevo oculto = new CorrectorCrearPack.CasoNuevo( "oculto", null, true );
    oculto.scripts = new LinkedHashMap<String, List<File>>();
    oculto.scripts.put( ORIGEN, Collections.singletonList( escribir( new File( scripts, "datos_oculto.sql" ),
      DATOS_OCULTO ) ) );
    oculto.ejecuciones = 2;
    op.casos = new ArrayList<CorrectorCrearPack.CasoNuevo>( java.util.Arrays.asList( visible, oculto ) );
    op.claves = claves( claveDocente );
    op.verificarLocales = false;
    return op;
  }
}
