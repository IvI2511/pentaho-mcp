package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.lint.Nodos;

/**
 * Lo que el asistente «Crear pack» hace con las bases de datos sin ejecutar la solucion (0.7.0), contra un servidor
 * H2 en TCP dentro de esta JVM (ningun MySQL ni PostgreSQL de la maquina):
 * <ul>
 * <li>los scripts .sql junto a la solucion se reparten por conexion (por la base que nombran y por sus tablas), en
 * orden (los que crean tablas primero), los del caso oculto aparte, sin los de un pack ni los que no son del
 * ejercicio;</li>
 * <li>«Generar el script desde la base actual» lee la base original con un usuario que solo puede leer, no la cambia
 * (la misma foto antes y despues), escribe las tablas en orden de claves foraneas con sus tipos, la clave primaria y
 * los datos (comillas, nulos, fechas), deja vacias las tablas que carga la solucion, corta en maxFilas, y el script
 * corre en una base de prueba y deja los mismos datos; nada que no sea SELECT o SHOW pasa por su unica puerta;</li>
 * <li>«Probar conexion» crea y borra solo la base de prueba (nunca la original) y dice por que falla.</li>
 * </ul>
 *
 * @dueño corrector 0.7.0
 */
public class PruebaAsistenteBases {

  private File dir;
  private BasesH2 h2;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    dir = new File( EntornoKettle.carpeta(), "asistente-bases" );
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
      h2 = new BasesH2( new File( dir, "servidor" ) );
    }
    return h2;
  }

  // ---------------------------------------------------------------- scripts junto a la solucion

  public void pruebaA_ScriptsDetectados() throws Exception {
    BasesH2 s = new BasesH2( 9 ); // sin servidor: solo el XML
    File profe = new File( dir, "curso/ejercicio" );
    File sol = s.guardar( BasesH2.Variante.SOLUCION, new File( profe, "carga_dw.ktr" ) );
    BasesH2.escribir( new File( profe, "datos_visible.sql" ), BasesH2.DATOS_VISIBLE );
    BasesH2.escribir( new File( profe, "crear_origen.sql" ), BasesH2.CREAR_ORIGEN );
    BasesH2.escribir( new File( profe, "sql/crear_dw.sql" ), BasesH2.CREAR_DW );
    BasesH2.escribir( new File( profe, "sql/datos_oculto.sql" ), BasesH2.DATOS_OCULTO );
    BasesH2.escribir( new File( profe, "otra_cosa.sql" ), "CREATE TABLE nada (x INT);\n" );
    BasesH2.escribir( new File( profe, "pack-viejo/bd/crear_origen.sql" ), BasesH2.CREAR_ORIGEN );
    BasesH2.escribir( new File( profe, "pack-viejo/pack.json" ), "{}" );
    // en la carpeta de arriba, uno que nombra la base del DW
    BasesH2.escribir( new File( dir, "curso/gym_dw_completo.sql" ), "-- todo el dw\nUSE gym_dw;\n"
      + "CREATE TABLE fechas (id INT);\n" );
    Map<String, List<File>> m = CorrectorScripts.detectar( profe, Nodos.parse( new String( Files.readAllBytes(
      sol.toPath() ), StandardCharsets.UTF_8 ) ) );
    igual( Arrays.asList( "crear_origen.sql", "datos_oculto.sql", "datos_visible.sql" ), nombres( m.get(
      BasesH2.ORIGEN ) ), "origen: el que crea primero, despues los datos" );
    igual( Arrays.asList( "crear_dw.sql", "gym_dw_completo.sql" ), nombres( m.get( BasesH2.DW ) ),
      "dw: por la tabla y por USE gym_dw" );
    cierto( CorrectorScripts.esOculto( m.get( BasesH2.ORIGEN ).get( 1 ) ), "el del caso oculto se reconoce" );
    falso( CorrectorScripts.esOculto( m.get( BasesH2.ORIGEN ).get( 2 ) ), "el visible no" );
    igual( 2, m.size(), "otra_cosa.sql no es de ninguna conexion" );
  }

  // ---------------------------------------------------------------- volcado de solo lectura

  public void pruebaB_VolcadoSoloLectura() throws Exception {
    BasesH2 s = h2();
    String url = "jdbc:h2:tcp://localhost:" + s.puerto + "/gym_original";
    try ( Connection c = DriverManager.getConnection( url, BasesH2.USUARIO, BasesH2.CLAVE );
          Statement st = c.createStatement() ) {
      st.execute( "CREATE TABLE ciudades (id INT PRIMARY KEY, nombre VARCHAR(40) NOT NULL)" );
      st.execute( "CREATE TABLE clientes (id INT PRIMARY KEY, nombre VARCHAR(60), id_ciudad INT, alta DATE, "
        + "saldo DECIMAL(10,2), activo BOOLEAN, visto TIMESTAMP, FOREIGN KEY (id_ciudad) REFERENCES ciudades(id))" );
      st.execute( "CREATE TABLE dim_cliente (id_cliente INT, nombre VARCHAR(60))" );
      st.execute( "INSERT INTO ciudades VALUES (1, 'Madrid'), (2, 'A Coruña')" );
      st.execute( "INSERT INTO clientes VALUES (10, 'Ana O''Brien', 1, DATE '2026-01-31', 12.50, TRUE, "
        + "TIMESTAMP '2026-02-01 10:20:30'), (11, 'C:\\temp; y punto', 2, NULL, NULL, FALSE, NULL), "
        + "(12, NULL, 1, DATE '2025-12-01', -3.00, NULL, NULL)" );
      st.execute( "INSERT INTO dim_cliente VALUES (10, 'ya cargado')" );
      st.execute( "CREATE USER lector PASSWORD 'solo-leo-1'" );
      st.execute( "GRANT SELECT ON ciudades, clientes, dim_cliente TO lector" );
    }
    String antes = foto( url );
    BaseDePrueba b = new BaseDePrueba( "GYM_OLTP", BaseDePrueba.H2, "localhost", String.valueOf( s.puerto ),
      "no_se_usa_correccion", "lector" );
    File script = new File( dir, "volcado/gym_original_generado.sql" );
    script.getParentFile().mkdirs();
    CorrectorVolcado.Volcado v = CorrectorVolcado.volcar( b, "gym_original", "solo-leo-1", Collections.singletonList(
      "DIM_CLIENTE" ), script, 1000 );
    igual( antes, foto( url ), "la base original queda igual" );
    igual( 3, v.tablas.size(), "tres tablas" );
    igual( 5L, v.filas, "5 filas (sin las de dim_cliente)" );
    igual( Collections.singletonList( "DIM_CLIENTE" ), v.vacias, "dim_cliente va vacia" );
    cierto( CorrectorVolcado.sinParcial( script ), "sin el .parcial" );
    String sql = new String( Files.readAllBytes( script.toPath() ), StandardCharsets.UTF_8 );
    System.out.println( "  script generado:\n" + sql );
    cierto( sql.indexOf( "CREATE TABLE \"CIUDADES\"" ) < sql.indexOf( "CREATE TABLE \"CLIENTES\"" ),
      "el padre antes que el hijo" );
    contiene( sql, "(40) NOT NULL", "tipo, tamaño y NOT NULL" );
    contiene( sql, "PRIMARY KEY (\"ID\")", "la clave primaria" );
    contiene( sql, "DECIMAL(10,2)", "decimales" );
    contiene( sql, "'Ana O''Brien'", "comillas" );
    contiene( sql, "ALTER TABLE \"CLIENTES\" ADD FOREIGN KEY (\"ID_CIUDAD\") REFERENCES \"CIUDADES\" (\"ID\");",
      "la clave foranea al final" );
    noContiene( sql, "ya cargado", "sin los datos de la tabla que carga la solucion" );
    noContiene( sql, "solo-leo-1", "sin la contraseña" );
    noContiene( sql.toUpperCase(), "CREATE DATABASE", "sin CREATE DATABASE" );
    noContiene( sql.toUpperCase(), "USE ", "sin USE" );

    // el script corre en una base de prueba (como lo hace el corrector) y deja los mismos datos
    String copia = "jdbc:h2:tcp://localhost:" + s.puerto + "/copia_correccion";
    try ( Connection c = DriverManager.getConnection( copia, BasesH2.USUARIO, BasesH2.CLAVE );
          Statement st = c.createStatement() ) {
      for ( String x : CorrectorBases.sentencias( sql, false ) ) {
        st.execute( x );
      }
    }
    igual( tabla( url, "CIUDADES" ) + tabla( url, "CLIENTES" ), tabla( copia, "CIUDADES" ) + tabla( copia,
      "CLIENTES" ), "los mismos datos" );
    igual( "", tabla( copia, "DIM_CLIENTE" ), "dim_cliente vacia" );

    // la unica puerta: SELECT y SHOW
    try ( Connection c = DriverManager.getConnection( url, BasesH2.USUARIO, BasesH2.CLAVE );
          Statement st = c.createStatement() ) {
      for ( String malo : new String[] { "DELETE FROM ciudades", "DROP TABLE ciudades", "  update clientes set id=1",
        "INSERT INTO ciudades VALUES (9, 'x')", "CREATE TABLE x (y INT)" } ) {
        lanza( IllegalStateException.class, () -> CorrectorVolcado.consulta( st, malo ), "rechaza " + malo );
      }
    }
    igual( antes, foto( url ), "y sigue igual" );

    // corta en maxFilas
    File corto = new File( dir, "volcado/corto.sql" );
    CorrectorVolcado.Volcado v2 = CorrectorVolcado.volcar( b, "gym_original", "solo-leo-1", null, corto, 2 );
    igual( Collections.singletonList( "CLIENTES" ), v2.cortadas, "clientes tiene 3 > 2" );
    // contraseña mala: falla sin dejar archivo
    File mal = new File( dir, "volcado/mal.sql" );
    lanza( Exception.class, () -> CorrectorVolcado.volcar( b, "gym_original", "otra", null, mal, 10 ),
      "contraseña mala" );
    falso( mal.exists(), "sin archivo" );
    cierto( CorrectorVolcado.sinParcial( mal ), "ni .parcial" );
  }

  // ---------------------------------------------------------------- probar conexion

  public void pruebaC_ProbarConexion() throws Exception {
    BasesH2 s = h2();
    BaseDePrueba b = s.base( BasesH2.DW, "gym_dw_correccion", "gym_dw" );
    Pack p = new Pack();
    p.formato = Pack.FORMATO_2;
    p.completar();
    p.bases.add( b );
    new CorrectorBases.Sesion( p, null, "", BasesH2.claves( BasesH2.CLAVE ) ).probar();
    cierto( s.existe( "gym_dw_correccion" ), "se creo la de prueba" );
    falso( s.existe( "gym_dw" ), "nunca la original" );
    CorrectorBases.Falla f = lanza( CorrectorBases.Falla.class, () -> new CorrectorBases.Sesion( p, null, "",
      BasesH2.claves( "mala" ) ).probar(), "contraseña mala" );
    contiene( f.mensaje.texto( "es" ), "gym_dw_correccion", "nombra la base" );
    BaseDePrueba orig = s.base( BasesH2.DW, "gym_dw", "gym_dw" );
    p.bases.set( 0, orig );
    CorrectorBases.Falla f2 = lanza( CorrectorBases.Falla.class, () -> new CorrectorBases.Sesion( p, null, "",
      BasesH2.claves( BasesH2.CLAVE ) ).probar(), "la original: no" );
    contiene( f2.mensaje.texto( "es" ), "es una base de la solución", "en español" );
    falso( s.existe( "gym_dw" ), "la original no se toco" );
  }

  // ---------------------------------------------------------------- ayudas

  private static List<String> nombres( List<File> l ) {
    List<String> n = new java.util.ArrayList<String>();
    for ( File f : l == null ? Collections.<File>emptyList() : l ) {
      n.add( f.getName() );
    }
    return n;
  }

  /** Todas las tablas con todas sus filas, en orden: si algo cambia, cambia. */
  private static String foto( String url ) throws Exception {
    StringBuilder b = new StringBuilder();
    try ( Connection c = DriverManager.getConnection( url, BasesH2.USUARIO, BasesH2.CLAVE );
          ResultSet rs = c.getMetaData().getTables( null, "PUBLIC", "%", new String[] { "TABLE" } ) ) {
      while ( rs.next() ) {
        String t = rs.getString( "TABLE_NAME" );
        b.append( t ).append( ':' ).append( tabla( url, t ) ).append( '\n' );
      }
    }
    try ( Connection c = DriverManager.getConnection( url, BasesH2.USUARIO, BasesH2.CLAVE );
          Statement st = c.createStatement();
          ResultSet rs = st.executeQuery( "SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS" ) ) {
      rs.next();
      b.append( "usuarios:" ).append( rs.getInt( 1 ) );
    }
    return b.toString();
  }

  private static String tabla( String url, String t ) throws Exception {
    StringBuilder b = new StringBuilder();
    try ( Connection c = DriverManager.getConnection( url, BasesH2.USUARIO, BasesH2.CLAVE );
          Statement st = c.createStatement();
          ResultSet rs = st.executeQuery( "SELECT * FROM \"" + t + "\" ORDER BY 1" ) ) {
      ResultSetMetaData md = rs.getMetaData();
      while ( rs.next() ) {
        for ( int i = 1; i <= md.getColumnCount(); i++ ) {
          b.append( rs.getString( i ) ).append( '|' );
        }
        b.append( ';' );
      }
    }
    return b.toString();
  }
}
