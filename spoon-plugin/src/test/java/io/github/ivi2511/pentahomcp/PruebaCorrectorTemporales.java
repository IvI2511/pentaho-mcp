package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.databaselookup.DatabaseLookupMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;
import org.pentaho.di.trans.steps.groupby.GroupByMeta;
import org.pentaho.di.trans.steps.insertupdate.InsertUpdateMeta;
import org.pentaho.di.trans.steps.joinrows.JoinRowsMeta;
import org.pentaho.di.trans.steps.rowgenerator.RowGeneratorMeta;
import org.pentaho.di.trans.steps.sort.SortRowsMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;

import io.github.ivi2511.pentahomcp.CorrectorMain.Pedido;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Los archivos temporales de los steps dentro del corrector (0.7.0): una solucion con Join Rows (producto cartesiano),
 * Sort rows y Group by con «todas las filas», que escriben temporales en {@code %%java.io.tmpdir%%}, tiene que poder
 * armar el pack y sacar 10 en la JVM hija. Antes el sandbox apuntaba esos temporales a
 * {@code ${Internal.Entry.Current.Directory}/tmp}, que en una corrida vale una URL {@code file:///...}: Sort rows
 * (VFS) la entendia, pero Join Rows y Group by usan {@code java.io.File} y fallaban con «Error creating temporary
 * file(s)». Ahora van a la ruta local de la carpeta {@code tmp/} del caso, que existe y se borra con el sandbox. Ademas
 * el error de un step que falla llega entero (el step y la causa), no solo su primera linea. Y lo mismo con una base
 * de prueba (H2 en TCP dentro de esta JVM).
 *
 * @dueño corrector 0.7.0
 */
public class PruebaCorrectorTemporales {

  private static final int FILAS = 20000;
  private File dir;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    dir = new File( EntornoKettle.carpeta(), "corrector-temporales" );
    CorrectorSandbox.borrar( dir );
    dir.mkdirs();
  }

  // ---------------------------------------------------------------- sin hija

  /** El sandbox reescribe el directorio temporal de Join Rows, Sort rows y Group by a la ruta local de tmp/. */
  public void pruebaA_Reescritura() throws Exception {
    File ktr = guardar( trans( "%%java.io.tmpdir%%", "C:/una/carpeta/del/alumno", "${java.io.tmpdir}" ),
      new File( dir, "a/temporales.ktr" ) );
    Pack pack = packDeStep( "Resultado" );
    CorrectorPolitica.Entrega e = CorrectorPolitica.cargar( ktr );
    CorrectorSandbox.Opciones so = new CorrectorSandbox.Opciones();
    so.dirTmpJvm = new File( dir, "a/tmp-jvm" );
    CorrectorSandbox.Preparacion p = CorrectorSandbox.preparar( pack, null, pack.casos.get( 0 ), e, new File( dir,
      "a/caso" ), so );
    falso( p.bloqueada(), "no se bloquea: " + p.motivo + " " + p.detalle );
    String xml = new String( Files.readAllBytes( p.ktr.toPath() ), StandardCharsets.UTF_8 );
    String tmp = p.tmp.getAbsolutePath().replace( '\\', '/' );
    cierto( p.tmp.isDirectory(), "tmp/ existe" );
    igual( 3, cuantas( xml, "<directory>" + tmp + "</directory>" ), "los tres temporales van a tmp/: " + xml );
    noContiene( xml, "Internal.Entry.Current.Directory}/tmp", "nunca la URL del directorio actual" );
    noContiene( xml, "C:/una/carpeta", "ni la carpeta del alumno" );
  }

  /** El error de un step: su nombre, el tipo y la causa, de varias lineas del log de Kettle. */
  public void pruebaB_ErrorCompleto() throws Exception {
    String log = "2026/10/25 10:00:01 - Añadir máximo cliente.0 - ERROR (version 9.4.0.0-343, build 0.0 from "
      + "2022-11-08 07.50.27 by buildguy) : Unexpected error\n"
      + "2026/10/25 10:00:01 - Añadir máximo cliente.0 - ERROR (version 9.4.0.0-343, build 0.0 from "
      + "2022-11-08 07.50.27 by buildguy) : org.pentaho.di.core.exception.KettleException: \n"
      + "2026/10/25 10:00:01 - Añadir máximo cliente.0 - Error creating temporary file(s) : \n"
      + "2026/10/25 10:00:01 - Añadir máximo cliente.0 - El sistema no puede encontrar la ruta especificada\n"
      + "2026/10/25 10:00:01 - Añadir máximo cliente.0 - \n"
      + "2026/10/25 10:00:01 - Añadir máximo cliente.0 - \tat org.pentaho.di.trans.steps.joinrows.JoinRows"
      + ".initialize(JoinRows.java:121)\n"
      + "2026/10/25 10:00:01 - Añadir máximo cliente.0 - \tat org.pentaho.di.trans.step.RunThread.run(RunThread"
      + ".java:62)\n"
      + "2026/10/25 10:00:01 - Ejercicio 2 - ERROR (version 9.4.0.0-343, build 0.0 from 2022-11-08 07.50.27 by "
      + "buildguy) : Errors detected!\n";
    String d = CorrectorMotor.errorDeStep( log );
    contiene( d, "«Añadir máximo cliente»", "nombra el step: " + d );
    contiene( d, "Error creating temporary file(s)", "el error" );
    contiene( d, "El sistema no puede encontrar la ruta especificada", "y la causa" );
    noContiene( d, "RunThread", "sin la pila" );
    noContiene( d, "Errors detected", "sin el resumen de la transformacion" );
  }

  // ---------------------------------------------------------------- con hija

  /** Crear el pack (la solucion corre en una hija) y corregir la solucion: 10. */
  public void pruebaC_CrearYCorregir() throws Exception {
    memoria();
    File sol = guardar( trans( "%%java.io.tmpdir%%", "%%java.io.tmpdir%%", "%%java.io.tmpdir%%" ), new File( dir,
      "c/profe/temporales.ktr" ) );
    File pack = new File( dir, "c/pack" );
    CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
    op.solucion = sol;
    op.destino = pack;
    op.titulo = "Temporales";
    op.idioma = "es";
    op.captura = Pack.Salida.CAPTURA_STEP;
    op.stepSalida = "Resultado";
    op.columnasClave = Collections.singletonList( "id" );
    op.verificarLocales = false;
    op.procesos = procesos();
    long t0 = System.currentTimeMillis();
    CorrectorCrearPack.Creado cr = CorrectorCrearPack.crear( op );
    System.out.println( "  crear el pack con temporales: " + ( System.currentTimeMillis() - t0 ) + " ms" );
    igual( FILAS, cr.casos.get( 0 ).filas, "todas las filas" );
    ResultadoCorreccion r = corregir( pack, sol, "c" );
    igual( ResultadoCorreccion.CORREGIDA, r.estado, "corregida (" + r.detalle + ")" );
    igual( 10.0, r.nota, "la solucion saca 10" );
  }

  /**
   * Un step que de verdad no puede crear sus temporales (la carpeta es un archivo): el detalle trae el step y la
   * causa, en español en el texto del asistente.
   */
  public void pruebaD_ErrorDelStepEnElDetalle() throws Exception {
    memoria();
    File sol = guardar( trans( "%%java.io.tmpdir%%", "%%java.io.tmpdir%%", "%%java.io.tmpdir%%" ), new File( dir,
      "d/profe/temporales.ktr" ) );
    // una entrega que rompe Join Rows con un prefijo imposible (createTempFile falla), igual que la carpeta inexistente
    String xml = new String( Files.readAllBytes( sol.toPath() ), StandardCharsets.UTF_8 ).replace(
      "<prefix>out</prefix>", "<prefix>x</prefix>" ); // prefijo de menos de 3 letras: IllegalArgumentException
    File rota = new File( dir, "d/entregas/rota.ktr" );
    rota.getParentFile().mkdirs();
    Files.write( rota.toPath(), xml.getBytes( StandardCharsets.UTF_8 ) );
    File pack = new File( dir, "d/pack" );
    CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
    op.solucion = rota;
    op.destino = pack;
    op.titulo = "Temporales";
    op.idioma = "es";
    op.captura = Pack.Salida.CAPTURA_STEP;
    op.stepSalida = "Resultado";
    op.verificarLocales = false;
    op.procesos = procesos();
    CorrectorCrearPack.Fallo f = Verificador.lanza( CorrectorCrearPack.Fallo.class, () -> CorrectorCrearPack.crear(
      op ), "la solucion rota no arma el pack" );
    igual( CorrectorCrearPack.E_CORRIDA, f.codigo, "solution_failed" );
    String es = f.texto( Rotulos.de( CorrectorTextos.AREA, "es" ) );
    System.out.println( "  el error: " + es );
    contiene( es, "«Añadir máximo»", "nombra el step" );
    contiene( es, "Prefix string", "con la causa de Java" );
  }

  /** Con una base de prueba (H2): Join Rows y Sort rows antes de un Insert/Update; crear, corregir, 10. */
  public void pruebaE_ConBaseDePrueba() throws Exception {
    memoria();
    BasesH2 h2 = new BasesH2( new File( dir, "e/h2" ) );
    try {
      File sol = guardar( transBase( h2 ), new File( dir, "e/profe/carga.ktr" ) );
      File scripts = new File( dir, "e/profe" );
      File pack = new File( dir, "e/pack" );
      CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
      op.solucion = sol;
      op.destino = pack;
      op.titulo = "Carga con temporales";
      op.idioma = "es";
      op.captura = Pack.Salida.CAPTURA_TABLAS;
      op.bases = new ArrayList<BaseDePrueba>( Arrays.asList( h2.base( BasesH2.ORIGEN, "club_oltp_correccion",
        "club_oltp" ), h2.base( BasesH2.DW, "club_dw_correccion", "club_dw" ) ) );
      op.scriptsBase = new LinkedHashMap<String, List<File>>();
      op.scriptsBase.put( BasesH2.ORIGEN, Arrays.asList( BasesH2.escribir( new File( scripts, "crear_origen.sql" ),
        BasesH2.CREAR_ORIGEN ), BasesH2.escribir( new File( scripts, "datos.sql" ), BasesH2.DATOS_VISIBLE ) ) );
      op.scriptsBase.put( BasesH2.DW, Collections.singletonList( BasesH2.escribir( new File( scripts,
        "crear_dw.sql" ), "CREATE TABLE dim_cliente (id_cliente INT, nombre VARCHAR(40), ciudad VARCHAR(40), "
        + "maximo INT);\n" ) ) );
      op.claves = BasesH2.claves( BasesH2.CLAVE );
      op.verificarLocales = false;
      op.procesos = procesos();
      op.procesos.claves.putAll( BasesH2.claves( BasesH2.CLAVE ) );
      CorrectorCrearPack.Creado cr = CorrectorCrearPack.crear( op );
      igual( 3, cr.casos.get( 0 ).filas, "3 clientes" );
      ResultadoCorreccion r = corregir( pack, sol, "e" );
      igual( ResultadoCorreccion.CORREGIDA, r.estado, "corregida (" + r.detalle + ")" );
      igual( 10.0, r.nota, "10" );
      falso( h2.existe( "club_oltp" ) || h2.existe( "club_dw" ), "las originales nunca" );
    } finally {
      h2.parar();
    }
  }

  // ---------------------------------------------------------------- transformaciones

  /**
   * 20000 filas numeradas, el maximo por producto cartesiano (Join Rows), ordenadas al reves con un buffer de 1000
   * (Sort rows a disco) y contadas con «todas las filas» (Group by a disco).
   */
  static TransMeta trans( String dirJoin, String dirSort, String dirGroup ) throws Exception {
    TransMeta tm = new TransMeta();
    tm.setName( "temporales" );
    RowGeneratorMeta g = generador( String.valueOf( FILAS ), "grupo", "String", "A" );
    StepMeta gen = paso( tm, "RowGenerator", "Generar filas", g, 50, 100 );
    StepMetaInterface seq = PluginRegistry.getInstance().loadClass( StepPluginType.class, "Sequence",
      StepMetaInterface.class );
    seq.setDefault();
    seq.getClass().getMethod( "setValuename", String.class ).invoke( seq, "id" );
    StepMeta num = paso( tm, "Sequence", "Numerar", seq, 200, 100 );
    StepMeta max = paso( tm, "RowGenerator", "Generar maximo", generador( "1", "maximo", "Integer", String.valueOf(
      FILAS ) ), 200, 250 );
    JoinRowsMeta jr = new JoinRowsMeta();
    jr.setDefault();
    jr.setDirectory( dirJoin );
    jr.setPrefix( "out" );
    jr.setCacheSize( 500 );
    StepMeta join = paso( tm, "JoinRows", "Añadir máximo", jr, 350, 100 );
    jr.setMainStep( num );
    SortRowsMeta sr = new SortRowsMeta();
    sr.setDefault();
    sr.allocate( 1 );
    sr.setFieldName( new String[] { "id" } );
    sr.setAscending( new boolean[] { false } );
    sr.setCaseSensitive( new boolean[] { true } );
    sr.setCollatorEnabled( new boolean[] { false } );
    sr.setCollatorStrength( new int[] { 0 } );
    sr.setPreSortedField( new boolean[] { false } );
    sr.setSortSize( "1000" );
    sr.setFreeMemoryLimit( "" );
    sr.setDirectory( dirSort );
    StepMeta sort = paso( tm, "SortRows", "Ordenar", sr, 500, 100 );
    GroupByMeta gb = new GroupByMeta();
    gb.setDefault();
    gb.allocate( 1, 1 );
    gb.setGroupField( new String[] { "grupo" } );
    gb.setAggregateField( new String[] { "filas_grupo" } );
    gb.setSubjectField( new String[] { "id" } );
    gb.setAggregateType( new int[] { GroupByMeta.TYPE_GROUP_COUNT_ALL } );
    gb.setValueField( new String[] { null } );
    gb.setPassAllRows( true );
    gb.setDirectory( dirGroup );
    StepMeta grp = paso( tm, "GroupBy", "Contar", gb, 650, 100 );
    StepMeta fin = paso( tm, "Dummy", "Resultado", new DummyTransMeta(), 800, 100 );
    tm.addTransHop( new TransHopMeta( gen, num ) );
    tm.addTransHop( new TransHopMeta( num, join ) );
    tm.addTransHop( new TransHopMeta( max, join ) );
    tm.addTransHop( new TransHopMeta( join, sort ) );
    tm.addTransHop( new TransHopMeta( sort, grp ) );
    tm.addTransHop( new TransHopMeta( grp, fin ) );
    return tm;
  }

  /** La carga de BasesH2 con el maximo por Join Rows y un Sort rows en el medio. */
  static TransMeta transBase( BasesH2 h2 ) {
    TransMeta tm = new TransMeta();
    tm.setName( "carga" );
    DatabaseMeta origen = new DatabaseMeta( BasesH2.ORIGEN, "H2", "Native", "localhost", "club_oltp", String.valueOf(
      h2.puerto ), BasesH2.USUARIO, BasesH2.CLAVE );
    DatabaseMeta dw = new DatabaseMeta( BasesH2.DW, "H2", "Native", "localhost", "club_dw", String.valueOf( h2.puerto ),
      BasesH2.USUARIO, BasesH2.CLAVE );
    tm.addDatabase( origen );
    tm.addDatabase( dw );
    TableInputMeta ti = new TableInputMeta();
    ti.setDefault();
    ti.setDatabaseMeta( origen );
    ti.setSQL( "SELECT ID, NOMBRE, ID_CIUDAD FROM CLIENTES" );
    StepMeta leer = paso( tm, "TableInput", "Leer clientes", ti, 50, 100 );
    TableInputMeta tmx = new TableInputMeta();
    tmx.setDefault();
    tmx.setDatabaseMeta( origen );
    tmx.setSQL( "SELECT MAX(ID) AS MAXIMO FROM CLIENTES" );
    StepMeta maximo = paso( tm, "TableInput", "Máximo cliente", tmx, 50, 250 );
    JoinRowsMeta jr = new JoinRowsMeta();
    jr.setDefault();
    jr.setDirectory( "%%java.io.tmpdir%%" );
    StepMeta join = paso( tm, "JoinRows", "Añadir máximo cliente", jr, 200, 100 );
    jr.setMainStep( leer );
    SortRowsMeta sr = new SortRowsMeta();
    sr.setDefault();
    sr.allocate( 1 );
    sr.setFieldName( new String[] { "ID" } );
    sr.setAscending( new boolean[] { true } );
    sr.setCaseSensitive( new boolean[] { true } );
    sr.setCollatorEnabled( new boolean[] { false } );
    sr.setCollatorStrength( new int[] { 0 } );
    sr.setPreSortedField( new boolean[] { false } );
    sr.setDirectory( "%%java.io.tmpdir%%" );
    StepMeta sort = paso( tm, "SortRows", "Ordenar", sr, 350, 100 );
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
    StepMeta buscar = paso( tm, "DBLookup", "Buscar ciudad", dl, 500, 100 );
    InsertUpdateMeta iu = new InsertUpdateMeta();
    iu.setDefault();
    iu.allocate( 1, 4 );
    iu.setDatabaseMeta( dw );
    iu.setTableName( "DIM_CLIENTE" );
    iu.setKeyLookup( new String[] { "ID_CLIENTE" } );
    iu.setKeyStream( new String[] { "ID" } );
    iu.setKeyStream2( new String[] { null } );
    iu.setKeyCondition( new String[] { "=" } );
    iu.setUpdateLookup( new String[] { "ID_CLIENTE", "NOMBRE", "CIUDAD", "MAXIMO" } );
    iu.setUpdateStream( new String[] { "ID", "NOMBRE", "CIUDAD", "MAXIMO" } );
    iu.setUpdate( new Boolean[] { Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE } );
    StepMeta cargar = paso( tm, "InsertUpdate", "Cargar dim_cliente", iu, 650, 100 );
    tm.addTransHop( new TransHopMeta( leer, join ) );
    tm.addTransHop( new TransHopMeta( maximo, join ) );
    tm.addTransHop( new TransHopMeta( join, sort ) );
    tm.addTransHop( new TransHopMeta( sort, buscar ) );
    tm.addTransHop( new TransHopMeta( buscar, cargar ) );
    return tm;
  }

  private static RowGeneratorMeta generador( String limite, String campo, String tipo, String valor ) {
    RowGeneratorMeta g = new RowGeneratorMeta();
    g.setDefault();
    g.allocate( 1 );
    g.setRowLimit( limite );
    g.setFieldName( new String[] { campo } );
    g.setFieldType( new String[] { tipo } );
    g.setValue( new String[] { valor } );
    g.setFieldFormat( new String[] { null } );
    g.setCurrency( new String[] { null } );
    g.setDecimal( new String[] { null } );
    g.setGroup( new String[] { null } );
    g.setFieldLength( new int[] { -1 } );
    g.setFieldPrecision( new int[] { -1 } );
    g.setSetEmptyString( new boolean[] { false } );
    return g;
  }

  private static StepMeta paso( TransMeta tm, String id, String nombre, StepMetaInterface m, int x, int y ) {
    StepMeta s = new StepMeta( id, nombre, m );
    s.setLocation( x, y );
    s.setDraw( true );
    tm.addStep( s );
    return s;
  }

  static File guardar( TransMeta tm, File f ) throws Exception {
    f.getAbsoluteFile().getParentFile().mkdirs();
    Files.write( f.toPath(), tm.getXML().getBytes( StandardCharsets.UTF_8 ) );
    return f;
  }

  // ---------------------------------------------------------------- ayudas

  private static Pack packDeStep( String step ) {
    Pack p = new Pack();
    p.formato = Pack.FORMATO;
    p.id = "temporales";
    p.titulo = "Temporales";
    p.completar();
    p.salida.captura = Pack.Salida.CAPTURA_STEP;
    p.salida.step = step;
    p.casos.add( new io.github.ivi2511.pentahomcp.corrector.modelo.Caso( "visible", "datos/visible",
      "esperado/visible.csv", false ) );
    return p;
  }

  private CorrectorProcesos.Opciones procesos() {
    CorrectorProcesos.Opciones op = new CorrectorProcesos.Opciones();
    op.workers = 1;
    op.xmxMb = 640;
    op.pdi = EntornoKettle.pdi();
    op.versionPlugin = "prueba";
    return op;
  }

  private ResultadoCorreccion corregir( File dirPack, File entrega, String sub ) throws Exception {
    Pack p;
    try ( CorrectorPack cp = CorrectorPack.cargar( dirPack ) ) {
      p = cp.pack();
    }
    File salida = new File( dir, sub + "/lote" );
    List<Pedido> pedidos = Collections.singletonList( new Pedido( "solucion", entrega, new File( salida,
      "solucion.json" ) ) );
    CorrectorProcesos.Opciones op = procesos();
    op.claves.putAll( BasesH2.claves( BasesH2.CLAVE ) );
    List<ResultadoCorreccion> rs = CorrectorProcesos.corregir( dirPack, p, pedidos, salida, op );
    noNulo( rs.get( 0 ), "un resultado" );
    return rs.get( 0 );
  }

  private static int cuantas( String s, String sub ) {
    int n = 0;
    for ( int i = s.indexOf( sub ); i >= 0; i = s.indexOf( sub, i + 1 ) ) {
      n++;
    }
    return n;
  }

  /** Espera (hasta 3 min) a que haya 1,5 GB libres antes de lanzar JVMs. */
  static void memoria() throws InterruptedException {
    for ( int i = 0; i < 36; i++ ) {
      long libre;
      try {
        Object os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        java.lang.reflect.Method m = Class.forName( "com.sun.management.OperatingSystemMXBean" ).getMethod(
          "getFreePhysicalMemorySize" );
        libre = ( (Number) m.invoke( os ) ).longValue();
      } catch ( Throwable x ) {
        return;
      }
      if ( libre >= 1536L * 1024 * 1024 ) {
        return;
      }
      System.out.println( "  esperando memoria libre (" + ( libre >> 20 ) + " MB)" );
      Thread.sleep( 5000 );
    }
  }
}
