package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.encryption.Encr;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * 0.7.1: las pruebas de jobs ({@link PruebasJob}), los esquemas y el SQL calificado llevados a las bases de prueba en
 * las pruebas de ETL (H2), el KETTLE_HOME del usuario copiado a la JVM de las pruebas (uno falso, en la carpeta de la
 * prueba) y las pruebas sin el ruido del caso del pack. El motor corre en esta JVM salvo en {@link #pruebaF_JvmHija},
 * que usa la JVM de las pruebas de verdad. Nunca un MySQL ni un PostgreSQL: un servidor H2 propio
 * ({@link BasesH2}). Todo bajo {@code <prueba>/pruebas-job}.
 */
public class PruebaPruebasJob {

  private File base;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    base = new File( EntornoKettle.carpeta(), "pruebas-job" );
    EntornoKettle.borrar( base.toPath() );
    cierto( base.mkdirs(), "carpeta de trabajo" );
  }

  public void cerrar() {
    PruebasProcesos.procesos = null;
    PruebasKettleHome.origen = null;
  }

  // ================================================================ utilidades

  /** 10_job_secuencia.kjb con sus dos transformaciones y datos/. */
  private File proyecto( String nombre ) throws Exception {
    File dir = new File( base, nombre );
    File ej = new File( EntornoKettle.recursos(), "ej" );
    for ( String k : new String[] { "10_job_secuencia.kjb", "01_generar_constantes.ktr",
      "03_ventas_por_producto.ktr" } ) {
      copiar( new File( ej, k ), new File( dir, k ) );
    }
    for ( File f : new File( ej, "datos" ).listFiles() ) {
      copiar( f, new File( new File( dir, "datos" ), f.getName() ) );
    }
    return dir;
  }

  private static void copiar( File de, File a ) throws Exception {
    a.getParentFile().mkdirs();
    Files.copy( de.toPath(), a.toPath(), StandardCopyOption.REPLACE_EXISTING );
  }

  private static PruebasMotor.Opciones op() {
    PruebasMotor.Opciones o = new PruebasMotor.Opciones();
    o.versionPlugin = "test";
    return o;
  }

  private static PruebasMotor.Pedido pedido( File dir, String kjb, String nombre, String... salidas ) {
    PruebasMotor.Pedido p = new PruebasMotor.Pedido();
    p.carpeta = new File( dir, "tests" );
    p.nombre = nombre;
    p.original = new File( dir, kjb );
    p.salidas.addAll( Arrays.asList( salidas ) );
    return p;
  }

  private static void reemplazar( File f, String de, String a ) throws Exception {
    String x = EntornoKettle.leer( f );
    cierto( x.contains( de ), "el texto a cambiar esta en " + f.getName() + ": " + de );
    EntornoKettle.escribir( f, x.replace( de, a ) );
  }

  private static final String SUM = "<subject>importe</subject>\n        <type>SUM</type>";
  private static final String MAX = "<subject>importe</subject>\n        <type>MAX</type>";

  // ================================================================ jobs

  /** Snapshot de un job: pasa sin escribir nada; el Group by cambiado en la .ktr de una entrada se ve. */
  public void pruebaA_JobSnapshot() throws Exception {
    File dir = proyecto( "a" );
    PruebasMotor.Pedido p = pedido( dir, "10_job_secuencia.kjb", "secuencia", "Ventas por producto :: Totales por "
      + "producto", "Generar filas" );
    p.claves.put( "ventas por producto :: totales por producto", Collections.singletonList( "producto" ) );
    ResultadoPrueba r = PruebasMotor.crear( p, op() );
    igual( ResultadoPrueba.PASO, r.estado, "la prueba de job nueva pasa: " + r.mensaje + " " + r.notas );
    falso( new File( dir, "salida" ).exists() && new File( dir, "salida" ).list().length > 0,
      "los Text file output de las dos entradas se saltaron: nada escrito" );
    File prueba = new File( dir, "tests/secuencia.test.json" );
    String json = EntornoKettle.leer( prueba );
    contiene( json, "\"job\" : \"../10_job_secuencia.kjb\"", "la .kjb relativa a la prueba" );
    noContiene( json, "\"transformation\"", "no es de transformacion" );
    noContiene( json, "\"weight\"", "sin el ruido del caso (weight)" );
    noContiene( json, "\"hidden\"", "ni hidden" );
    DefPrueba d = PruebasMotor.cargar( prueba );
    igual( 2, d.salidas.size(), "dos salidas" );
    igual( "Ventas por producto", d.salidas.get( 0 ).entrada, "con su entrada" );
    igual( "Generar constantes", d.salidas.get( 1 ).entrada, "la entrada se deduce si el step es de una sola" );
    igual( 1, d.datasets.size(), "un dataset: Leer ventas" );
    igual( "Ventas por producto", d.datasets.get( 0 ).entrada, "de su entrada" );
    boolean saltado = false;
    for ( DefPrueba.Ajuste a : d.ajustes ) {
      saltado |= DefPrueba.Ajuste.SALTAR.equals( a.tipo ) && "Escribir totales".equals( a.step )
        && "Ventas por producto".equals( a.entrada );
    }
    cierto( saltado, "skip_step con job_entry: " + json );
    archivo( new File( dir, "tests/golden/secuencia/ventas_por_producto_totales_por_producto.csv" ), 40,
      "el dorado de la salida, con la entrada en el nombre" );

    ResultadoPrueba r2 = PruebasMotor.correr( prueba, op() );
    igual( ResultadoPrueba.PASO, r2.estado, "otra vez en verde: " + r2.mensaje );
    noNulo( r2.job, "el resultado nombra el job" );
    noNulo( r2.salida( "Ventas por producto :: Totales por producto" ), "la salida con su entrada" );
    contiene( r2.notas.toString(), "entry 'Ventas por producto': step 'Leer ventas' replaced by the dataset",
      "la nota del dataset dice la entrada" );

    reemplazar( new File( dir, "03_ventas_por_producto.ktr" ), SUM, MAX );
    ResultadoPrueba r3 = PruebasMotor.correr( prueba, op() );
    igual( ResultadoPrueba.FALLO, r3.estado, "el Group by cambiado en la .ktr de la entrada se nota" );
    contiene( r3.mensaje, "'Ventas por producto :: Totales por producto'", "dice que salida: " + r3.mensaje );
    contiene( r3.mensaje, "changed rows", "y que cambian filas" );
    cierto( r3.salida( "Ventas por producto :: Totales por producto" ).distintas > 0, "filas distintas" );
    cierto( r3.salida( "Generar constantes :: Generar filas" ).paso, "la otra entrada sigue igual" );
  }

  /** files[]: el CSV que deja el job (con su separador), un cambio, y un archivo que el job no escribio. */
  public void pruebaB_JobArchivos() throws Exception {
    File dir = proyecto( "b" );
    PruebasMotor.Pedido p = pedido( dir, "10_job_secuencia.kjb", "archivo" );
    p.archivos.add( "../salida/03_ventas_por_producto.csv" );
    ResultadoPrueba r = PruebasMotor.crear( p, op() );
    igual( ResultadoPrueba.PASO, r.estado, "pasa: " + r.mensaje + " " + r.notas );
    File prueba = new File( dir, "tests/archivo.test.json" );
    DefPrueba d = PruebasMotor.cargar( prueba );
    igual( 1, d.archivos.size(), "un archivo" );
    igual( ";", d.archivos.get( 0 ).csv.separador, "el separador del archivo, detectado" );
    archivo( new File( dir, "tests/golden/archivo/file_03_ventas_por_producto_csv.csv" ), 40, "su dorado" );
    igual( ResultadoPrueba.Resumen.ARCHIVO, r.salidas.get( 0 ).tipo, "kind file" );

    reemplazar( new File( dir, "03_ventas_por_producto.ktr" ), SUM, MAX );
    ResultadoPrueba r2 = PruebasMotor.correr( prueba, op() );
    igual( ResultadoPrueba.FALLO, r2.estado, "el archivo cambio: " + r2.mensaje );
    contiene( r2.mensaje, "03_ventas_por_producto.csv", "lo nombra" );

    // si el job no lo escribe (skip_step), falla: nunca se compara un archivo viejo
    reemplazar( new File( dir, "03_ventas_por_producto.ktr" ), MAX, SUM );
    DefPrueba.Ajuste a = DefPrueba.Ajuste.saltar( "Escribir totales", "prueba" );
    a.entrada = "03_ventas_por_producto.ktr"; // el nombre de la .ktr tambien vale como job_entry
    d.ajustes.add( a );
    PruebasMotor.guardar( d, prueba );
    ResultadoPrueba r3 = PruebasMotor.correr( prueba, op() );
    igual( ResultadoPrueba.FALLO, r3.estado, "no lo escribio" );
    igual( ResultadoPrueba.Resumen.SIN_ESCRIBIR, r3.salidas.get( 0 ).problema, "not_written: " + r3.mensaje );
    contiene( r3.mensaje, "was not written by the run", "lo dice" );
  }

  /** skip_entry por defecto (borra archivos) y los problemas de una prueba de job mal escrita. */
  public void pruebaC_EntradasYProblemas() throws Exception {
    File dir = proyecto( "c" );
    File kjb = new File( dir, "10_job_secuencia.kjb" );
    File testigo = new File( dir, "testigo.txt" );
    EntornoKettle.escribir( testigo, "no me borres" );
    String borrar = "    <entry>\n      <name>Borrar testigo</name>\n      <description/>\n      <type>DELETE_FILE</type>\n"
      + "      <attributes/>\n      <filename>${Internal.Entry.Current.Directory}/testigo.txt</filename>\n"
      + "      <fail_if_file_not_exists>N</fail_if_file_not_exists>\n      <parallel>N</parallel>\n      <draw>Y</draw>\n"
      + "      <nr>0</nr>\n      <xloc>500</xloc>\n      <yloc>300</yloc>\n      <attributes_kjc/>\n    </entry>\n";
    String x = EntornoKettle.leer( kjb ).replace( "  </entries>", borrar + "  </entries>" );
    // START -> Borrar testigo -> Generar constantes
    x = x.replaceFirst( "<from>START</from>\\s*<to>Generar constantes</to>", "<from>START</from>\n      <to>Borrar "
      + "testigo</to>" );
    x = x.replace( "  </hops>", "    <hop>\n      <from>Borrar testigo</from>\n      <to>Generar constantes</to>\n"
      + "      <from_nr>0</from_nr>\n      <to_nr>0</to_nr>\n      <enabled>Y</enabled>\n      <evaluation>Y</evaluation>\n"
      + "      <unconditional>N</unconditional>\n    </hop>\n  </hops>" );
    EntornoKettle.escribir( kjb, x );
    ResultadoPrueba r = PruebasMotor.crear( pedido( dir, "10_job_secuencia.kjb", "sin_borrar",
      "Ventas por producto :: Totales por producto" ), op() );
    igual( ResultadoPrueba.PASO, r.estado, "pasa: " + r.mensaje + " " + r.notas );
    cierto( testigo.isFile(), "la entrada que borra archivos se salto: el testigo sigue" );
    boolean salto = false;
    for ( DefPrueba.Ajuste a : r.creada.ajustes ) {
      salto |= DefPrueba.Ajuste.SALTAR_ENTRADA.equals( a.tipo ) && "Borrar testigo".equals( a.entrada );
    }
    cierto( salto, "skip_entry por defecto: " + r.creada.ajustes );
    contiene( r.notas.toString(), "entry 'Borrar testigo' skipped", "la nota" );

    // sin la entrada pedida
    ResultadoPrueba mal = PruebasMotor.crear( pedido( dir, "10_job_secuencia.kjb", "mal", "No existe :: Algo" ), op() );
    igual( ResultadoPrueba.ERROR, mal.estado, "entrada que no esta" );
    contiene( mal.mensaje, "There is no Transformation entry 'No existe'", "lo dice" );
    ResultadoPrueba ambiguo = PruebasMotor.crear( pedido( dir, "10_job_secuencia.kjb", "amb", "Totales" ), op() );
    igual( ResultadoPrueba.ERROR, ambiguo.estado, "un step que no esta" );
    contiene( ambiguo.mensaje, "'Entry :: Step'", "pide la entrada" );

    // una prueba de job escrita a mano sin job_entry
    File f = new File( dir, "tests/manual.test.json" );
    EntornoKettle.escribir( f, "{ \"format\": \"pentaho-live-test/1\", \"job\": \"../10_job_secuencia.kjb\", "
      + "\"outputs\": [ { \"step\": \"Totales por producto\" } ], \"tweaks\": [ { \"type\": \"skip_entry\" } ] }" );
    ResultadoPrueba r3 = PruebasMotor.correr( f, op() );
    igual( ResultadoPrueba.ERROR, r3.estado, "invalida" );
    contiene( r3.mensaje, "needs 'job_entry'", "falta la entrada de la salida" );
    contiene( r3.mensaje, "skip_entry without 'job_entry'", "y la del ajuste" );

    // una salida cuyo step ya no esta en la transformacion de la entrada
    File g = new File( dir, "tests/renombrado.test.json" );
    DefPrueba d = PruebasMotor.cargar( new File( dir, "tests/sin_borrar.test.json" ) );
    d.salidas.get( 0 ).step = "Totales viejos";
    PruebasMotor.guardar( d, g );
    copiar( new File( dir, "tests/golden/sin_borrar/ventas_por_producto_totales_por_producto.csv" ), new File( dir,
      "tests/golden/sin_borrar/ventas_por_producto_totales_viejos.csv" ) );
    ResultadoPrueba r4 = PruebasMotor.correr( g, op() );
    igual( ResultadoPrueba.FALLO, r4.estado, "falta el step" );
    igual( ResultadoPrueba.Resumen.SIN_STEP, r4.salidas.get( 0 ).problema, "missing_step" );
    contiene( r4.notas.toString(), "'Totales viejos' is not in entry 'Ventas por producto'", "dice donde" );
  }

  // ================================================================ esquemas en las bases de prueba (H2)

  /** Kettle escribe el esquema y la tabla del Table output entre comillas: en H2 van en minusculas y entre comillas. */
  private static final String SCRIPT = "CREATE SCHEMA \"gym_dw\";\n"
    + "CREATE TABLE \"gym_dw\".origen (id INT PRIMARY KEY, nombre VARCHAR(20));\n"
    + "INSERT INTO \"gym_dw\".origen VALUES (1, 'uno'), (2, 'dos');\n"
    + "CREATE TABLE \"gym_dw\".\"destino\" (id INT PRIMARY KEY, nombre VARCHAR(20));\n";

  /** Table input con gym_dw.origen y Table output con esquema gym_dw, en la base gym_dw (la original). */
  private static TransMeta conEsquemas( BasesH2 h2 ) {
    TransMeta tm = new TransMeta();
    tm.setName( "esquemas" );
    DatabaseMeta dw = new DatabaseMeta( BasesH2.DW, "H2", "Native", "localhost", "gym_dw", String.valueOf( h2.puerto ),
      BasesH2.USUARIO, BasesH2.CLAVE );
    tm.addDatabase( dw );
    TableInputMeta ti = new TableInputMeta();
    ti.setDefault();
    ti.setDatabaseMeta( dw );
    ti.setSQL( "SELECT o.id, o.nombre FROM \"gym_dw\".origen o ORDER BY o.id" );
    StepMeta leer = new StepMeta( "TableInput", "Leer origen", ti );
    tm.addStep( leer );
    TableOutputMeta to = new TableOutputMeta();
    to.setDefault();
    to.setDatabaseMeta( dw );
    to.setSchemaName( "gym_dw" );
    to.setTableName( "destino" );
    StepMeta escribir = new StepMeta( "TableOutput", "Cargar destino", to );
    tm.addStep( escribir );
    tm.addTransHop( new TransHopMeta( leer, escribir ) );
    return tm;
  }

  /** Una prueba de transformacion y una de job (con una entrada SQL) en H2 con esquemas: todo va a gym_dw_test. */
  public void pruebaD_EsquemasH2() throws Exception {
    BasesH2 h2 = new BasesH2( new File( base, "h2" ) );
    try {
      File dir = new File( base, "d" );
      BasesH2.escribir( new File( dir, "esquemas.ktr" ), conEsquemas( h2 ).getXML() );
      File tests = new File( dir, "tests" );
      BasesH2.escribir( new File( tests, "bd/dw.sql" ), SCRIPT );
      BaseDePrueba b = h2.base( BasesH2.DW, "gym_dw_test", "gym_dw" );
      b.scripts.add( "bd/dw.sql" );
      TablaSalida t = new TablaSalida( BasesH2.DW, "gym_dw.destino" );
      t.columnasClave.add( "ID" );

      PruebasMotor.Pedido p = new PruebasMotor.Pedido();
      p.carpeta = tests;
      p.nombre = "trans";
      p.original = new File( dir, "esquemas.ktr" );
      p.salidas.add( "Leer origen" );
      p.bases.add( b );
      p.tablas.add( t );
      PruebasMotor.Opciones op = op();
      op.claves.putAll( BasesH2.claves( BasesH2.CLAVE ) );
      ResultadoPrueba r = PruebasMotor.crear( p, op );
      igual( ResultadoPrueba.PASO, r.estado, "transformacion con esquemas: " + r.mensaje + " " + r.notas );
      igual( 2, r.salida( "gym_dw.destino" ).filasReales.intValue(), "las 2 filas en gym_dw_test.destino" );
      contiene( r.notas.toString(), "schema 'Cargar destino': gym_dw -> gym_dw_test", "el esquema del Table output" );
      contiene( r.notas.toString(), "sql 'Leer origen'", "el SQL calificado" );
      falso( h2.existe( "gym_dw" ), "la base original nunca se toco" );

      // un job con una entrada SQL que escribe en gym_dw.origen y despues la transformacion
      String sql = "    <entry>\n      <name>Agregar fila</name>\n      <description/>\n      <type>SQL</type>\n"
        + "      <attributes/>\n      <sql>INSERT INTO &quot;gym_dw&quot;.origen VALUES (3, 'tres')</sql>\n"
        + "      <useVariableSubstitution>F</useVariableSubstitution>\n      <sqlfromfile>F</sqlfromfile>\n"
        + "      <sqlfilename/>\n      <sendOneStatement>F</sendOneStatement>\n      <connection>" + BasesH2.DW
        + "</connection>\n      <parallel>N</parallel>\n      <draw>Y</draw>\n      <nr>0</nr>\n      <xloc>200</xloc>\n"
        + "      <yloc>100</yloc>\n    </entry>\n";
      File kjb = new File( dir, "carga.kjb" );
      BasesH2.escribir( kjb, job( conEsquemas( h2 ).getDatabase( 0 ).getXML(), sql + entradaTrans( "Cargar",
        "esquemas.ktr" ), "Agregar fila", "Cargar" ) );
      PruebasMotor.Pedido pj = new PruebasMotor.Pedido();
      pj.carpeta = tests;
      pj.nombre = "job";
      pj.original = kjb;
      pj.salidas.add( "Cargar :: Leer origen" );
      pj.entradas = Collections.emptyList(); // sin datasets: el origen sale de la base de prueba
      pj.bases.add( b );
      pj.tablas.add( t );
      ResultadoPrueba rj = PruebasMotor.crear( pj, op );
      igual( ResultadoPrueba.PASO, rj.estado, "job con esquemas: " + rj.mensaje + " " + rj.notas );
      igual( 3, rj.salida( "gym_dw.destino" ).filasReales.intValue(), "la entrada SQL agrego una fila" );
      contiene( rj.notas.toString(), "sql 'Agregar fila'", "el SQL de la entrada del job tambien" );
      contiene( rj.notas.toString(), "connection 'GYM_DW' (gym_dw) -> test database gym_dw_test", "la del job" );
      falso( CorrectorJson.escribir( rj, false ).contains( BasesH2.CLAVE ), "sin la contraseña" );
      falso( h2.existe( "gym_dw" ), "la base original nunca se toco" );
    } finally {
      h2.parar();
    }
  }

  /** Un job START -> entradas en orden -> Success, con esas conexiones. */
  static String job( String conexiones, String entradasXml, String... orden ) {
    StringBuilder hops = new StringBuilder();
    String antes = "START";
    for ( String o : orden ) {
      hops.append( hop( antes, o, antes.equals( "START" ) ) );
      antes = o;
    }
    hops.append( hop( antes, "Success", false ) );
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<job>\n  <name>prueba</name>\n  <parameters/>\n" + conexiones
      + "\n  <entries>\n    <entry>\n      <name>START</name>\n      <type>SPECIAL</type>\n      <start>Y</start>\n"
      + "      <dummy>N</dummy>\n      <repeat>N</repeat>\n      <schedulerType>0</schedulerType>\n"
      + "      <parallel>N</parallel>\n      <draw>Y</draw>\n      <nr>0</nr>\n      <xloc>50</xloc>\n"
      + "      <yloc>100</yloc>\n    </entry>\n" + entradasXml
      + "    <entry>\n      <name>Success</name>\n      <type>SUCCESS</type>\n      <parallel>N</parallel>\n"
      + "      <draw>Y</draw>\n      <nr>0</nr>\n      <xloc>600</xloc>\n      <yloc>100</yloc>\n    </entry>\n"
      + "  </entries>\n  <hops>\n" + hops + "  </hops>\n</job>\n";
  }

  private static String hop( String de, String a, boolean incondicional ) {
    return "    <hop>\n      <from>" + de + "</from>\n      <to>" + a + "</to>\n      <from_nr>0</from_nr>\n"
      + "      <to_nr>0</to_nr>\n      <enabled>Y</enabled>\n      <evaluation>Y</evaluation>\n      <unconditional>"
      + ( incondicional ? "Y" : "N" ) + "</unconditional>\n    </hop>\n";
  }

  static String entradaTrans( String nombre, String ktr ) {
    return "    <entry>\n      <name>" + nombre + "</name>\n      <type>TRANS</type>\n"
      + "      <specification_method>filename</specification_method>\n"
      + "      <filename>${Internal.Entry.Current.Directory}/" + ktr + "</filename>\n"
      + "      <wait_until_finished>Y</wait_until_finished>\n      <loglevel>Basic</loglevel>\n"
      + "      <parameters>\n        <pass_all_parameters>Y</pass_all_parameters>\n      </parameters>\n"
      + "      <parallel>N</parallel>\n      <draw>Y</draw>\n      <nr>0</nr>\n      <xloc>400</xloc>\n"
      + "      <yloc>100</yloc>\n    </entry>\n";
  }

  // ================================================================ ruido y esquema

  /** Una prueba de 0.7.0 con weight y hidden se sigue leyendo; al escribirla ya no los lleva. */
  public void pruebaE_SinRuido() throws Exception {
    File f = new File( base, "e/vieja.test.json" );
    EntornoKettle.escribir( f, "{ \"format\": \"pentaho-live-test/1\", \"transformation\": \"x.ktr\", "
      + "\"case\": { \"id\": \"vieja\", \"expected\": \"golden/vieja\", \"weight\": 1.0, \"hidden\": false }, "
      + "\"outputs\": [ { \"step\": \"A\" } ] }" );
    DefPrueba d = PruebasMotor.cargar( f );
    igual( "golden/vieja", d.caso.esperado, "se lee" );
    PruebasMotor.guardar( d, f );
    String x = EntornoKettle.leer( f );
    noContiene( x, "weight", "sin weight" );
    noContiene( x, "hidden", "sin hidden" );
    contiene( x, "\"expected\" : \"golden/vieja\"", "con lo demas" );
    noContiene( x, "user_kettle_home", "el valor por defecto no se escribe" );
    // el pack sigue escribiendo su formato tal cual
    io.github.ivi2511.pentahomcp.corrector.modelo.Caso c = new io.github.ivi2511.pentahomcp.corrector.modelo.Caso(
      "visible", null, "esperado/visible", false );
    contiene( CorrectorJson.escribir( c, false ), "\"weight\":1.0", "el pack: igual que antes" );
    // los ejemplos del repo ya no tienen el ruido
    File repo = EntornoKettle.recursos().getAbsoluteFile().getParentFile().getParentFile().getParentFile()
      .getParentFile();
    for ( File p : PruebasArchivos.deCarpeta( new File( repo, "ejemplo/pruebas" ) ) ) {
      noContiene( EntornoKettle.leer( p ), "\"weight\"", p.getName() + " sin weight" );
    }
  }

  // ================================================================ JVM de las pruebas y KETTLE_HOME del usuario

  /**
   * En la JVM de las pruebas de verdad: una prueba de job, y una transformacion que usa una conexion de shared.xml y
   * variables de kettle.properties de un KETTLE_HOME falso (en la carpeta de la prueba). Con user_kettle_home=false
   * no las encuentra. La variable con nombre de secreto no sale en el resultado.
   */
  public void pruebaF_JvmHija() throws Exception {
    BasesH2 h2 = new BasesH2( new File( base, "h2f" ) );
    try {
      CorrectorProcesos.Opciones po = new CorrectorProcesos.Opciones();
      po.xmxMb = 640;
      po.pdi = EntornoKettle.pdi();
      PruebasProcesos.procesos = po;
      File dir = new File( base, "f" );
      // el KETTLE_HOME falso
      File kettle = new File( base, "kettle-falso/.kettle" );
      DatabaseMeta compartida = new DatabaseMeta( "COMPARTIDA", "H2", "Native", "localhost", "compartida", String
        .valueOf( h2.puerto ), BasesH2.USUARIO, BasesH2.CLAVE );
      String conXml = compartida.getXML();
      cierto( conXml.contains( "Encrypted" ), "la contraseña cifrada como la guarda Spoon" );
      BasesH2.escribir( new File( kettle, "shared.xml" ), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<sharedobjects>\n" + conXml + "\n</sharedobjects>\n" );
      BasesH2.escribir( new File( kettle, "kettle.properties" ), "SALUDO=hola desde kettle.properties\n"
        + "MI_TOKEN_SECRETO=superSecreto123\n" );
      PruebasKettleHome.origen = kettle;
      // la transformacion: la conexion solo esta en shared.xml
      TransMeta tm = new TransMeta();
      tm.setName( "compartida" );
      tm.addDatabase( compartida );
      TableInputMeta ti = new TableInputMeta();
      ti.setDefault();
      ti.setDatabaseMeta( compartida );
      ti.setVariableReplacementActive( true );
      ti.setSQL( "SELECT '${SALUDO}' AS SALUDO, '${MI_TOKEN_SECRETO}' AS S" );
      tm.addStep( new StepMeta( "TableInput", "Leer", ti ) );
      String xml = tm.getXML().replaceAll( "(?s)<connection>\\s*<name>COMPARTIDA</name>.*?</connection>", "" );
      noContiene( xml, "<server>", "la .ktr no define la conexion" );
      BasesH2.escribir( new File( dir, "compartida.ktr" ), xml );
      DefPrueba d = new DefPrueba();
      d.nombre = "compartida";
      d.transformacion = "../compartida.ktr";
      d.salidas.add( new DefPrueba.Salida( "Leer" ) );
      d.completar();
      File prueba = new File( dir, "tests/compartida.test.json" );
      PruebasMotor.guardar( d, prueba );
      BasesH2.escribir( new File( dir, "tests/golden/compartida/leer.csv" ), "SALUDO,S\n"
        + "hola desde kettle.properties,otro valor\n" );
      // y la prueba de job de A, en la JVM hija
      File pa = proyecto( "f-job" );
      ResultadoPrueba creada = PruebasMotor.crear( pedido( pa, "10_job_secuencia.kjb", "hija", "Ventas por producto :: "
        + "Totales por producto" ), op() );
      igual( ResultadoPrueba.PASO, creada.estado, "creada en esta JVM: " + creada.mensaje );

      List<ResultadoPrueba> rs = PruebasProcesos.correrYEsperar( Arrays.asList( PruebasProcesos.Tarea.correr( prueba,
        null ), PruebasProcesos.Tarea.correr( new File( pa, "tests/hija.test.json" ), null ) ), 300000,
        new java.util.ArrayList<File>() );
      ResultadoPrueba r = rs.get( 0 );
      igual( ResultadoPrueba.FALLO, r.estado, "corre con la conexion compartida y falla solo por la columna S: "
        + r.mensaje + " " + ( r.caso == null ? "" : r.caso.corrida.detalle ) );
      ResultadoPrueba.Resumen s = r.salida( "Leer" );
      igual( 1, s.distintas.intValue(), "una fila distinta (S); SALUDO salio de kettle.properties" );
      String todo = CorrectorJson.escribir( r, false );
      noContiene( todo, "superSecreto123", "el valor de la variable secreta no sale en el resultado" );
      contiene( todo, CorrectorBases.TAPADA, "tapado" );
      igual( ResultadoPrueba.PASO, rs.get( 1 ).estado, "la prueba de job en la JVM hija: " + rs.get( 1 ).mensaje );
      falso( new File( dir, "tests/kettle.properties" ).exists() || new File( dir, "shared.xml" ).exists(),
        "nada del KETTLE_HOME en el proyecto" );

      // sin el KETTLE_HOME del usuario: la conexion no esta
      d.entorno.kettleDelUsuario = false;
      PruebasMotor.guardar( d, prueba );
      contiene( EntornoKettle.leer( prueba ), "\"user_kettle_home\" : false", "la opcion en la prueba" );
      List<ResultadoPrueba> rs2 = PruebasProcesos.correrYEsperar( Collections.singletonList( PruebasProcesos.Tarea
        .correr( prueba, null ) ), 300000, new java.util.ArrayList<File>() );
      cierto( !rs2.get( 0 ).paso(), "sin shared.xml no corre: " + rs2.get( 0 ).mensaje );
      igual( "superSecreto123", Encr.decryptPasswordOptionallyEncrypted( "superSecreto123" ), "(Encr en marcha)" );
    } finally {
      h2.parar();
    }
  }

  /** Donde se encuentran las pruebas de un job, que .ktr usa, su firma, test_list y los steps de la ventana. */
  public void pruebaH_ArchivosYVentana() throws Exception {
    File dir = proyecto( "h" );
    EntornoKettle.escribir( new File( dir, "tests/j.test.json" ), "{ \"format\": \"pentaho-live-test/1\", \"job\": "
      + "\"../10_job_secuencia.kjb\", \"outputs\": [ { \"job_entry\": \"Ventas por producto\", \"step\": \"Totales por "
      + "producto\" } ], \"files\": [ { \"path\": \"../salida/x.csv\" } ] }" );
    File kjb = new File( dir, "10_job_secuencia.kjb" );
    igual( 1, PruebasArchivos.deArchivo( kjb ).size(), "las pruebas del job" );
    igual( 1, PruebasArchivos.deJobsQueUsan( new File( dir, "03_ventas_por_producto.ktr" ) ).size(),
      "guardar la .ktr corre la prueba del job que la usa" );
    igual( 0, PruebasArchivos.deArchivo( new File( dir, "03_ventas_por_producto.ktr" ) ).size(),
      "pero no es una prueba de la .ktr" );
    org.pentaho.di.job.JobMeta jm = new org.pentaho.di.job.JobMeta( kjb.getAbsolutePath(), null );
    java.util.Map<String, String> f1 = PruebasArchivos.firma( jm );
    jm.getJobEntry( 1 ).setLocation( 999, 999 );
    igual( f1, PruebasArchivos.firma( jm ), "mover una entrada no cambia la firma" );
    jm.getJobEntry( 1 ).setDescription( "otra" );
    cierto( !f1.equals( PruebasArchivos.firma( jm ) ), "cambiar una entrada si" );
    java.util.Map<String, TransMeta> t = PruebasJob.transformaciones( jm );
    igual( Arrays.asList( "Generar constantes", "Ventas por producto" ), new java.util.ArrayList<String>( t.keySet() ),
      "las entradas Transformation" );
    List<String[]> steps = PruebasDialogo.ServicioSpoon.steps( t.get( "Ventas por producto" ) );
    cierto( steps.size() == 5, "los steps de la .ktr de la entrada" );
    com.fasterxml.jackson.databind.node.ObjectNode x = PruebasTools.describir( new File( dir, "tests/j.test.json" ) );
    cierto( x.path( "valid" ).asBoolean(), "valida: " + x );
    igual( "Ventas por producto :: Totales por producto", x.path( "outputs" ).get( 0 ).asText(), "con su entrada" );
    contiene( x.path( "job" ).asText(), "10_job_secuencia.kjb", "el job" );
    igual( "../salida/x.csv", x.path( "files" ).get( 0 ).asText(), "los archivos" );
  }

  /** PruebasKettleHome: solo las conexiones de shared.xml y kettle.properties; los secretos, descifrados. */
  public void pruebaG_CopiaKettleHome() throws Exception {
    File origen = new File( base, "g/origen/.kettle" );
    String con = new DatabaseMeta( "X", "H2", "Native", "", "x", "", "sa", "clave-larga-9" ).getXML();
    BasesH2.escribir( new File( origen, "shared.xml" ), "<sharedobjects>" + con + "<slaveserver><name>S</name>"
      + "</slaveserver></sharedobjects>" );
    BasesH2.escribir( new File( origen, "kettle.properties" ), "A=1\nDB_PASSWORD=otra-clave\n" );
    BasesH2.escribir( new File( origen, "repositories.xml" ), "<repositories/>" );
    File destino = new File( base, "g/destino/.kettle" );
    List<String> hecho = PruebasKettleHome.copiar( origen, destino );
    igual( Arrays.asList( "kettle.properties (2 variables)", "shared.xml (1 connections)" ), hecho, "lo copiado" );
    String sh = EntornoKettle.leer( new File( destino, "shared.xml" ) );
    contiene( sh, "<name>X</name>", "la conexion" );
    noContiene( sh, "slaveserver", "nada mas" );
    falso( new File( destino, "repositories.xml" ).exists(), "ni otros archivos" );
    List<String> sec = PruebasKettleHome.secretos( origen );
    cierto( sec.contains( "clave-larga-9" ), "la contraseña de la conexion, descifrada: " + sec );
    cierto( sec.contains( "otra-clave" ), "la variable con nombre de secreto" );
    falso( sec.contains( "1" ), "no las demas" );
    // el corrector no copia nada por defecto
    falso( new CorrectorProcesos.Opciones().kettleDelUsuario != null, "el corrector: KETTLE_HOME vacio" );
    noNulo( Pack.FORMATO, "-" );
  }
}
