package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * Las pruebas de transformaciones con datasets dorados (0.7.0, P1 y P2) con el motor en esta JVM
 * ({@link PruebasMotor}: el mismo que corre en la JVM de las pruebas), sobre los ejemplos de la skill y un patron:
 * <ul>
 * <li>snapshot de 03 (ventas por producto) que pasa, con el Text file output saltado (no escribe nada) y una clave;
 * despues un Group by cambiado (SUM → MAX) da valores distintos con su clave;</li>
 * <li>02 (filtrar clientes): un filtro cambiado deja pasar filas de mas;</li>
 * <li>06 (errores y parametros): ajustes set_parameter y remove_step; con otro valor del parametro la prueba falla;</li>
 * <li>el patron de deduplicacion (02a): datasets con fechas y textos que se reinyectan igual;</li>
 * <li>una prueba con bases de prueba H2 (servidor H2 de esta JVM): las tablas que escribe la carga se comparan, las
 * bases originales nunca se tocan;</li>
 * <li>P2: firmas de una transformacion y que pruebas toca un cambio.</li>
 * </ul>
 * Todo bajo {@code <prueba>/pruebas-etl}, sobre copias.
 */
public class PruebaPruebasEtl {

  private File base;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    base = new File( EntornoKettle.carpeta(), "pruebas-etl" );
    EntornoKettle.borrar( base.toPath() );
    cierto( base.mkdirs(), "carpeta de trabajo" );
  }

  // ================================================================ utilidades

  /** Un proyecto con esos ejemplos de la skill (y su carpeta datos/). */
  private File proyecto( String nombre, String... ktrs ) throws Exception {
    File dir = new File( base, nombre );
    File ej = new File( EntornoKettle.recursos(), "ej" );
    for ( String k : ktrs ) {
      copiar( new File( ej, k ), new File( dir, k ) );
    }
    File datos = new File( ej, "datos" );
    for ( File f : datos.listFiles() ) {
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

  private static PruebasMotor.Pedido pedido( File dir, String ktr, String nombre, String... salidas ) {
    PruebasMotor.Pedido p = new PruebasMotor.Pedido();
    p.carpeta = new File( dir, "tests" );
    p.nombre = nombre;
    p.original = new File( dir, ktr );
    p.salidas.addAll( Arrays.asList( salidas ) );
    return p;
  }

  private static void reemplazar( File f, String de, String a ) throws Exception {
    String x = EntornoKettle.leer( f );
    cierto( x.contains( de ), "el texto a cambiar esta en " + f.getName() + ": " + de );
    EntornoKettle.escribir( f, x.replaceFirst( java.util.regex.Pattern.quote( de ), java.util.regex.Matcher
      .quoteReplacement( a ) ) );
  }

  private static Comparacion comparacion( ResultadoPrueba r, String salida ) {
    for ( io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso.ComparacionTabla t : r.caso.tablas ) {
      if ( salida.equals( t.tabla ) ) {
        return t.comparacion;
      }
    }
    return null;
  }

  // ================================================================ P1

  /** Snapshot de 03: pasa, no escribe la salida, y un Group by cambiado se ve como valores distintos por clave. */
  public void pruebaA_SnapshotYGroupByCambiado() throws Exception {
    File dir = proyecto( "a", "03_ventas_por_producto.ktr" );
    PruebasMotor.Pedido p = pedido( dir, "03_ventas_por_producto.ktr", "ventas", "Totales por producto",
      "Escribir totales" );
    p.claves.put( "totales por producto", Collections.singletonList( "producto" ) );
    ResultadoPrueba r = PruebasMotor.crear( p, op() );
    igual( ResultadoPrueba.PASO, r.estado, "la prueba nueva pasa: " + r.mensaje );
    noNulo( r.creada, "lo creado" );
    File tests = new File( dir, "tests" );
    archivo( new File( tests, "ventas.test.json" ), 200, "la prueba" );
    archivo( new File( tests, "datasets/ventas/leer_ventas.csv" ), 50, "el dataset de Leer ventas" );
    archivo( new File( tests, "golden/ventas/totales_por_producto.csv" ), 50, "el dorado de Totales" );
    archivo( new File( tests, "golden/ventas/escribir_totales.csv" ), 50, "el dorado de Escribir totales" );
    falso( new File( dir, "salida" ).exists() && new File( dir, "salida" ).list().length > 0,
      "el Text file output saltado no escribio nada" );

    DefPrueba d = PruebasMotor.cargar( new File( tests, "ventas.test.json" ) );
    igual( DefPrueba.FORMATO, d.formato, "formato" );
    igual( "../03_ventas_por_producto.ktr", d.transformacion, "la .ktr relativa a la prueba" );
    igual( 1, d.datasets.size(), "un dataset" );
    igual( "Leer ventas", d.datasets.get( 0 ).step, "de la fuente" );
    cierto( d.datasets.get( 0 ).columnas.size() >= 6, "con sus columnas" );
    igual( "golden/ventas", d.caso.esperado, "el caso del pack: expected = carpeta de los dorados" );
    igual( Collections.singletonList( "producto" ), d.salida( "Totales por producto" ).comparar.columnasClave,
      "la clave" );
    boolean saltado = false;
    for ( DefPrueba.Ajuste a : d.ajustes ) {
      saltado |= DefPrueba.Ajuste.SALTAR.equals( a.tipo ) && "Escribir totales".equals( a.step );
    }
    cierto( saltado, "skip_step del Text file output capturado" );
    contiene( EntornoKettle.leer( new File( tests, "golden/ventas/totales_por_producto.csv" ) ), "producto,",
      "dorado con coma y encabezado" );

    // otra vez: pasa
    ResultadoPrueba r2 = PruebasMotor.correr( new File( tests, "ventas.test.json" ), op() );
    igual( ResultadoPrueba.PASO, r2.estado, "corre en verde: " + r2.mensaje );
    igual( 2, r2.salidas.size(), "dos salidas" );
    cierto( r2.salidas.get( 0 ).paso && r2.salidas.get( 1 ).paso, "las dos iguales" );
    cierto( r2.notas.toString().contains( "replaced by the dataset" ), "nota del dataset: " + r2.notas );

    // SUM -> MAX en importe_total
    reemplazar( new File( dir, "03_ventas_por_producto.ktr" ), "<subject>importe</subject>\n        <type>SUM</type>",
      "<subject>importe</subject>\n        <type>MAX</type>" );
    ResultadoPrueba r3 = PruebasMotor.correr( new File( tests, "ventas.test.json" ), op() );
    igual( ResultadoPrueba.FALLO, r3.estado, "el Group by cambiado se nota" );
    contiene( r3.mensaje, "changed rows", "dice que cambian filas" );
    Comparacion c = comparacion( r3, "Totales por producto" );
    noNulo( c, "la comparacion de Totales" );
    cierto( c.filas.distintas > 0, "filas distintas" );
    igual( 0, c.filas.faltan, "ninguna falta (misma clave)" );
    Comparacion.Ejemplo e = c.ejemplos.distintas.get( 0 );
    igual( "importe_total", e.columna, "la columna que cambia" );
    noNulo( e.clave, "con la clave del producto" );
    cierto( !e.esperado.equals( e.real ), "esperado y obtenido distintos" );
  }

  /** 02: el filtro cambiado (saldo >= 500 en vez de 1000) deja pasar filas de mas. */
  public void pruebaB_FiltroModificado() throws Exception {
    File dir = proyecto( "b", "02_filtrar_clientes.ktr" );
    ResultadoPrueba r = PruebasMotor.crear( pedido( dir, "02_filtrar_clientes.ktr", "filtro", "Elegir columnas" ),
      op() );
    igual( ResultadoPrueba.PASO, r.estado, "la prueba nueva pasa: " + r.mensaje );
    File prueba = new File( dir, "tests/filtro.test.json" );
    reemplazar( new File( dir, "02_filtrar_clientes.ktr" ), "<text>1000.0</text>", "<text>500.0</text>" );
    ResultadoPrueba r2 = PruebasMotor.correr( prueba, op() );
    igual( ResultadoPrueba.FALLO, r2.estado, "el filtro cambiado se nota" );
    contiene( r2.mensaje, "extra rows", "filas de mas" );
    ResultadoPrueba.Resumen s = r2.salida( "Elegir columnas" );
    cierto( s.sobran > 0, "sobran filas" );
    igual( 0, s.faltan.intValue(), "no falta ninguna" );
    cierto( !comparacion( r2, "Elegir columnas" ).ejemplos.sobran.isEmpty(), "ejemplos de las que sobran" );
    // el resultado se lee de vuelta del JSON (el contrato con Spoon)
    ResultadoPrueba x = CorrectorJson.leer( CorrectorJson.escribir( r2, true ), ResultadoPrueba.class );
    igual( ResultadoPrueba.FALLO, x.estado, "ida y vuelta por JSON" );
    igual( s.sobran, x.salida( "Elegir columnas" ).sobran, "con el resumen" );
  }

  /** 06: set_parameter y remove_step; con otro valor del parametro faltan filas. */
  public void pruebaC_Ajustes() throws Exception {
    File dir = proyecto( "c", "06_errores_y_parametros.ktr" );
    PruebasMotor.Pedido p = pedido( dir, "06_errores_y_parametros.ktr", "umbral", "Saldo sobre umbral" );
    p.ajustes = new ArrayList<DefPrueba.Ajuste>();
    DefPrueba.Ajuste par = new DefPrueba.Ajuste();
    par.tipo = DefPrueba.Ajuste.PARAMETRO;
    par.nombre = "SALDO_MINIMO";
    par.valor = "500";
    p.ajustes.add( par );
    DefPrueba.Ajuste quitar = new DefPrueba.Ajuste();
    quitar.tipo = DefPrueba.Ajuste.QUITAR;
    quitar.step = "Escribir errores";
    p.ajustes.add( quitar );
    DefPrueba.Ajuste saltar = DefPrueba.Ajuste.saltar( "Escribir validos", "no escribir" );
    p.ajustes.add( saltar );
    ResultadoPrueba r = PruebasMotor.crear( p, op() );
    igual( ResultadoPrueba.PASO, r.estado, "pasa: " + r.mensaje );
    cierto( r.notas.toString().contains( "'Escribir errores' removed" ), "remove_step aplicado: " + r.notas );
    falso( new File( dir, "salida" ).exists() && new File( dir, "salida" ).list().length > 0, "nada escrito" );
    int con500 = r.salida( "Saldo sobre umbral" ).filasReales;

    File prueba = new File( dir, "tests/umbral.test.json" );
    reemplazar( prueba, "\"value\" : \"500\"", "\"value\" : \"5000\"" );
    ResultadoPrueba r2 = PruebasMotor.correr( prueba, op() );
    igual( ResultadoPrueba.FALLO, r2.estado, "otro umbral, otra salida" );
    ResultadoPrueba.Resumen s = r2.salida( "Saldo sobre umbral" );
    cierto( s.filasReales < con500, "menos filas con 5000 (" + s.filasReales + " < " + con500 + ")" );
    cierto( s.faltan > 0, "faltan filas" );
  }

  /** El patron de deduplicacion de la skill: textos y fechas reinyectados igual. */
  public void pruebaD_PatronDeduplicacion() throws Exception {
    File repo = EntornoKettle.recursos().getAbsoluteFile().getParentFile().getParentFile().getParentFile()
      .getParentFile();
    File pat = new File( repo, "claude/skills/pentaho-etl/patrones/02_deduplicacion" );
    File dir = new File( base, "d" );
    copiar( new File( pat, "02a_ordenar_y_unique.ktr" ), new File( dir, "02a_ordenar_y_unique.ktr" ) );
    copiar( new File( pat, "datos/clientes_duplicados.csv" ), new File( dir, "datos/clientes_duplicados.csv" ) );
    ResultadoPrueba r = PruebasMotor.crear( pedido( dir, "02a_ordenar_y_unique.ktr", "dedup",
      "Keep first row per customer" ), op() );
    igual( ResultadoPrueba.PASO, r.estado, "pasa: " + r.mensaje );
    cierto( r.creada.avisos.isEmpty(), "los datasets reproducen la salida en vivo: " + r.creada.avisos );
    ResultadoPrueba.Resumen s = r.salida( "Keep first row per customer" );
    cierto( s.filasReales > 0 && s.filasReales.equals( s.filasEsperadas ), "filas: " + s.filasReales );
  }

  /** Bases de prueba H2: la carga escribe dim_cliente en la de prueba; las originales nunca se tocan. */
  public void pruebaE_BaseH2() throws Exception {
    BasesH2 h2 = new BasesH2( new File( base, "h2" ) );
    try {
      File dir = new File( base, "e" );
      h2.guardar( BasesH2.Variante.SOLUCION, new File( dir, "carga_dw.ktr" ) );
      File tests = new File( dir, "tests" );
      BasesH2.escribir( new File( tests, "bd/origen.sql" ), BasesH2.CREAR_ORIGEN );
      BasesH2.escribir( new File( tests, "bd/datos.sql" ), BasesH2.DATOS_VISIBLE );
      BasesH2.escribir( new File( tests, "bd/dw.sql" ), BasesH2.CREAR_DW );
      PruebasMotor.Pedido p = pedido( dir, "carga_dw.ktr", "carga", "Buscar ciudad" );
      BaseDePrueba o = h2.base( BasesH2.ORIGEN, "gym_oltp_test", "gym_oltp" );
      o.scripts.add( "bd/origen.sql" );
      BaseDePrueba w = h2.base( BasesH2.DW, "gym_dw_test", "gym_dw" );
      w.scripts.add( "bd/dw.sql" );
      p.bases.add( o );
      p.bases.add( w );
      p.scriptsBase.put( BasesH2.ORIGEN, Collections.singletonList( "bd/datos.sql" ) );
      TablaSalida t = new TablaSalida( BasesH2.DW, "DIM_CLIENTE" );
      t.columnasClave.add( "ID_CLIENTE" );
      p.tablas.add( t );
      PruebasMotor.Opciones op = op();
      op.claves.putAll( BasesH2.claves( BasesH2.CLAVE ) );
      ResultadoPrueba r = PruebasMotor.crear( p, op );
      igual( ResultadoPrueba.PASO, r.estado, "pasa: " + r.mensaje + " " + r.notas );
      igual( 0, r.creada.datasets.size(), "sin datasets: el origen sale de los scripts" );
      archivo( new File( tests, "golden/carga/DIM_CLIENTE.csv" ), 20, "el dorado de la tabla" );
      ResultadoPrueba.Resumen s = r.salida( "DIM_CLIENTE" );
      igual( 3, s.filasReales.intValue(), "3 clientes cargados" );
      cierto( r.notas.toString().contains( "test database" ), "conexiones redirigidas: " + r.notas );
      falso( CorrectorJson.escribir( r, false ).contains( BasesH2.CLAVE ), "sin la contraseña en el JSON" );
      falso( h2.existe( "gym_oltp" ), "la base original de origen nunca se toco" );
      falso( h2.existe( "gym_dw" ), "la del DW tampoco" );
      cierto( h2.existe( "gym_dw_test" ), "se uso la de prueba" );

      // otros datos de origen: la tabla cambia
      BasesH2.escribir( new File( tests, "bd/datos.sql" ), BasesH2.DATOS_VISIBLE.replace( "(10, 'Ana', 1)",
        "(10, 'Ana', 2)" ) );
      ResultadoPrueba r2 = PruebasMotor.correr( new File( tests, "carga.test.json" ), op );
      igual( ResultadoPrueba.FALLO, r2.estado, "Ana en otra ciudad" );
      Comparacion c = comparacion( r2, "DIM_CLIENTE" );
      igual( 1, c.filas.distintas, "una fila cambia" );
      igual( "CIUDAD", c.ejemplos.distintas.get( 0 ).columna.toUpperCase(), "la ciudad" );
    } finally {
      h2.parar();
    }
  }

  /** Una prueba invalida o con un step que ya no esta: error o fallo claros, sin excepciones. */
  public void pruebaF_ProblemasClaros() throws Exception {
    File dir = proyecto( "f", "03_ventas_por_producto.ktr" );
    ResultadoPrueba r = PruebasMotor.crear( pedido( dir, "03_ventas_por_producto.ktr", "v", "No existe" ), op() );
    igual( ResultadoPrueba.ERROR, r.estado, "un step que no esta" );
    contiene( r.mensaje, "There is no step 'No existe'", "lo dice" );
    falso( new File( dir, "tests/v.test.json" ).exists(), "no escribio la prueba" );

    ResultadoPrueba ok = PruebasMotor.crear( pedido( dir, "03_ventas_por_producto.ktr", "v", "Totales por producto" ),
      op() );
    igual( ResultadoPrueba.PASO, ok.estado, "esta si: " + ok.mensaje );
    ResultadoPrueba otra = PruebasMotor.crear( pedido( dir, "03_ventas_por_producto.ktr", "v", "Totales por "
      + "producto" ), op() );
    igual( ResultadoPrueba.ERROR, otra.estado, "ya existe" );
    contiene( otra.mensaje, "replace=true", "como pisarla" );

    reemplazar( new File( dir, "03_ventas_por_producto.ktr" ), "<name>Totales por producto</name>",
      "<name>Totales</name>" );
    reemplazar( new File( dir, "03_ventas_por_producto.ktr" ), "<to>Totales por producto</to>", "<to>Totales</to>" );
    reemplazar( new File( dir, "03_ventas_por_producto.ktr" ), "<from>Totales por producto</from>",
      "<from>Totales</from>" );
    ResultadoPrueba r2 = PruebasMotor.correr( new File( dir, "tests/v.test.json" ), op() );
    igual( ResultadoPrueba.FALLO, r2.estado, "el step renombrado" );
    contiene( r2.mensaje, "Totales por producto", "lo dice: " + r2.mensaje );
    igual( ResultadoPrueba.Resumen.SIN_STEP, r2.salidas.get( 0 ).problema, "missing_step" );

    File mala = new File( dir, "tests/mala.test.json" );
    EntornoKettle.escribir( mala, "{ \"format\": \"pentaho-live-test/1\", \"tweaks\": [ { \"type\": \"x\" } ] }" );
    ResultadoPrueba r3 = PruebasMotor.correr( mala, op() );
    igual( ResultadoPrueba.ERROR, r3.estado, "invalida" );
    contiene( r3.mensaje, "'transformation' is missing", "sin transformacion" );
    contiene( r3.mensaje, "unknown tweak type", "ajuste desconocido" );
  }

  // ================================================================ P2

  /** Firmas y pruebas afectadas: posiciones no cuentan; lo de abajo no toca lo de arriba; un dataset corta. */
  public void pruebaG_Afectadas() throws Exception {
    File dir = proyecto( "g", "03_ventas_por_producto.ktr" );
    TransMeta tm = new TransMeta( new File( dir, "03_ventas_por_producto.ktr" ).getAbsolutePath() );
    Map<String, String> f1 = PruebasArchivos.firma( tm );
    tm.findStep( "Calcular importe" ).setLocation( 999, 999 );
    igual( Collections.emptySet(), PruebasArchivos.cambiados( f1, PruebasArchivos.firma( tm ) ), "mover no cambia" );

    DefPrueba totales = new DefPrueba();
    totales.salidas.add( new DefPrueba.Salida( "Totales por producto" ) );
    totales.completar();
    DefPrueba conDataset = new DefPrueba();
    conDataset.salidas.add( new DefPrueba.Salida( "Totales por producto" ) );
    conDataset.datasets.add( new DefPrueba.Dataset( "Calcular importe", "x.csv" ) );
    conDataset.completar();
    DefPrueba escribir = new DefPrueba();
    escribir.salidas.add( new DefPrueba.Salida( "Escribir totales" ) );
    escribir.completar();

    // un cambio abajo (el archivo de salida) no toca la prueba de Totales
    StepMeta salida = tm.findStep( "Escribir totales" );
    ( (org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta) salida.getStepMetaInterface() ).setFileName(
      "otra_salida" );
    Set<String> c1 = PruebasArchivos.cambiados( f1, PruebasArchivos.firma( tm ) );
    igual( Collections.singleton( "Escribir totales" ), c1, "solo cambio la salida" );
    falso( PruebasArchivos.afectada( totales, tm, c1 ), "Totales no depende de la salida" );
    cierto( PruebasArchivos.afectada( escribir, tm, c1 ), "la de la salida si" );

    // un cambio arriba toca a las dos... salvo a la que tiene un dataset en el medio
    Map<String, String> f2 = PruebasArchivos.firma( tm );
    tm.findStep( "Leer ventas" ).setDescription( "otra cosa" );
    ( (org.pentaho.di.trans.steps.csvinput.CsvInputMeta) tm.findStep( "Leer ventas" ).getStepMetaInterface() )
      .setDelimiter( "," );
    Set<String> c2 = PruebasArchivos.cambiados( f2, PruebasArchivos.firma( tm ) );
    igual( Collections.singleton( "Leer ventas" ), c2, "cambio la entrada" );
    cierto( PruebasArchivos.afectada( totales, tm, c2 ), "Totales depende de Leer ventas" );
    falso( PruebasArchivos.afectada( conDataset, tm, c2 ), "con dataset en Calcular importe, Leer ventas no llega" );

    // un parametro nuevo: lo global, todas; sin firma anterior, todas
    Map<String, String> f3 = PruebasArchivos.firma( tm );
    tm.addParameterDefinition( "NUEVO", "1", "" );
    Set<String> c3 = PruebasArchivos.cambiados( f3, PruebasArchivos.firma( tm ) );
    cierto( c3.contains( PruebasArchivos.GLOBAL ), "cambio global" );
    cierto( PruebasArchivos.afectada( conDataset, tm, c3 ), "global: todas" );
    cierto( PruebasArchivos.afectada( totales, tm, null ), "sin firma anterior: todas" );
  }

  /** Donde se buscan las pruebas: tests/ o pruebas/ junto a la .ktr y en sus padres, por la .ktr que nombran. */
  public void pruebaH_Carpetas() throws Exception {
    File dir = proyecto( "h", "03_ventas_por_producto.ktr" );
    File sub = new File( dir, "trans" );
    copiar( new File( dir, "03_ventas_por_producto.ktr" ), new File( sub, "03_ventas_por_producto.ktr" ) );
    EntornoKettle.escribir( new File( dir, "pruebas/a.test.json" ), "{\"format\":\"pentaho-live-test/1\","
      + "\"transformation\":\"../trans/03_ventas_por_producto.ktr\",\"outputs\":[{\"step\":\"Leer ventas\"}]}" );
    EntornoKettle.escribir( new File( dir, "pruebas/b.test.json" ), "{\"format\":\"pentaho-live-test/1\","
      + "\"transformation\":\"../03_ventas_por_producto.ktr\",\"outputs\":[{\"step\":\"Leer ventas\"}]}" );
    List<File> l = PruebasArchivos.deTransformacion( new File( sub, "03_ventas_por_producto.ktr" ) );
    igual( 1, l.size(), "la del padre que la nombra: " + l );
    igual( "a.test.json", l.get( 0 ).getName(), "la a" );
    igual( "b.test.json", PruebasArchivos.deTransformacion( new File( dir, "03_ventas_por_producto.ktr" ) ).get( 0 )
      .getName(), "la b es de la otra" );
    igual( new File( sub, "tests" ).getAbsolutePath(), PruebasArchivos.carpetaPorDefecto( new File( sub,
      "03_ventas_por_producto.ktr" ) ).getAbsolutePath(), "las nuevas, junto a la .ktr" );
    noNulo( PruebasArchivos.buscar( "a", new File( sub, "03_ventas_por_producto.ktr" ), null ), "por nombre" );
  }
}
