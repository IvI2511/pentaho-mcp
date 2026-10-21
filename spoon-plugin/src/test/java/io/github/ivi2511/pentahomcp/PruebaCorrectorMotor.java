package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.CorrectorMain.Pedido;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;

/**
 * Ejecucion del corrector en JVMs hijas (C4, docs/corrector-diseno.md §3, §7 y §10). Las pruebas de lote lanzan workers
 * de verdad ({@link CorrectorMain}) con {@link CorrectorProcesos}: classpath de las clases compiladas por test.ps1 + el
 * PDI de -Dpruebas.pdi, KETTLE_HOME, user.home y tmpdir propios bajo {@code <prueba>/motor-c4}, nunca los reales. Una
 * hija a la vez ({@code workers = 1}) y {@code -Xmx768m}: las pruebas comparten la PC con otras cosas.
 * <ul>
 * <li>Las 29 entregas de ventas-por-producto y las 5 de ventas-a-tabla dan el estado, el motivo, la nota y el estado
 * de cada caso de casos.json (las notas del §1 en una hija; walter bloqueado; t6 cortada por max_output_mb, P18).</li>
 * <li>P9: hugo da 6,0 con es_ES y 10,0 con el pack en en_US. P17: t4 da 10 con el pack en windows-1252 (el charset
 * nativo) aunque el padre diga -Dfile.encoding=COMPAT.</li>
 * <li>El vigilante: un caso que no termina queda {@code timeout} y recicla el worker; una entrega que se pasa del
 * limite del padre queda {@code killed_timeout} y una que tira la hija, {@code crashed}, las dos puntuadas con el
 * parcial; el lote sigue con otro worker.</li>
 * </ul>
 *
 * @dueño C4
 */
public class PruebaCorrectorMotor {

  private static final String VENTAS = CorrectorRecursos.VENTAS;
  private static final String TABLA = CorrectorRecursos.TABLA;
  private static final AtomicInteger LOTES = new AtomicInteger();

  private File copia;

  /**
   * Borra los lotes de la corrida anterior (quedan para mirarlos: resultados, _logs/ y _abiertos/, que trae datos/ de
   * solo lectura y por eso se borra con CorrectorSandbox.borrar y no con lo de CorrectorRecursos).
   */
  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    CorrectorSandbox.borrar( base() );
  }

  /** Donde van las salidas de los lotes (fuera de las copias de CorrectorRecursos). */
  private static File base() {
    return new File( EntornoKettle.carpeta(), "motor-c4" );
  }

  private static File lote( String nombre ) {
    return new File( base(), nombre + "-" + LOTES.incrementAndGet() );
  }

  private File copia() throws IOException {
    if ( copia == null ) {
      copia = CorrectorRecursos.copia();
    }
    return copia;
  }

  // ------------------------------------------------------------------ sin hijas

  /** Que flags del padre pasan a la hija y que pone el pack. */
  public void pruebaFlags() throws Exception {
    List<String> padre = Arrays.asList( "-Xmx2048m", "--add-opens", "java.base/java.net=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED", "-Djava.locale.providers=COMPAT,SPI", "-Dfile.encoding=COMPAT",
      "-DKETTLE_HOME=C:/profe", "-Duser.home=C:/profe", "-Duser.language=fr", "-Djava.io.tmpdir=C:/tmp",
      "-agentlib:jdwp=transport=dt_socket,server=y,address=5005", "-javaagent:x.jar", "-XX:MaxPermSize=256m",
      "-Djava.endorsed.dirs=C:/pdi/system/karaf/lib/endorsed", "--illegal-access=permit" );
    List<String> f = CorrectorProcesos.flagsDe( padre );
    igual( Arrays.asList( "--add-opens", "java.base/java.net=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED", "-Djava.locale.providers=COMPAT,SPI", "-Djava.endorsed.dirs=C:/pdi/system/karaf/lib/endorsed",
      "--illegal-access=permit" ), f, "solo los de Spoon.bat" );
    igual( Collections.emptyList(), CorrectorProcesos.flagsDe( Arrays.asList( "--add-opens" ) ), "sin valor: nada" );

    Pack p = pack( VENTAS );
    List<String> d = CorrectorProcesos.flagsDelPack( p );
    cierto( d.contains( "-Dfile.encoding=UTF-8" ), "encoding del pack: " + d );
    cierto( d.contains( "-Duser.language=es" ) && d.contains( "-Duser.country=ES" ), "locale del pack: " + d );
    cierto( d.contains( "-Duser.timezone=Europe/Madrid" ), "zona del pack: " + d );
    p.politica.locale = "pt-BR";
    p.politica.encoding = "windows-1252";
    p.politica.zonaHoraria = null;
    d = CorrectorProcesos.flagsDelPack( p );
    cierto( d.contains( "-Duser.language=pt" ) && d.contains( "-Duser.country=BR" ), "pt-BR: " + d );
    cierto( d.contains( "-Dfile.encoding=windows-1252" ) && d.contains( "-Duser.timezone=UTC" ), "defaults: " + d );

    CorrectorProcesos.Opciones op = new CorrectorProcesos.Opciones();
    op.argsPadre = padre;
    op.xmxMb = 512;
    List<String> cmd = CorrectorProcesos.comandoBase( op, pack( VENTAS ), EntornoKettle.pdi() );
    cierto( cmd.get( 0 ).toLowerCase().contains( "java" ), "java: " + cmd.get( 0 ) );
    igual( "-Xmx512m", cmd.get( 1 ), "Xmx propio" );
    int i = cmd.indexOf( "-Dfile.encoding=UTF-8" );
    cierto( i > 0 && !cmd.contains( "-Dfile.encoding=COMPAT" ), "el encoding del padre no pasa: " + cmd );
    for ( String c : cmd ) {
      falso( c.startsWith( "-agentlib" ) || c.startsWith( "-javaagent" ) || c.startsWith( "-XX" ) || c.startsWith(
        "-DKETTLE_HOME" ) || c.startsWith( "-Duser.home" ) || c.equals( "-Duser.language=fr" ), "no pasa " + c );
    }
    String cp = cmd.get( cmd.indexOf( "-cp" ) + 1 );
    contiene( cp, new File( EntornoKettle.pdi(), "lib" ).getAbsolutePath() + File.separator + "*", "lib/* de PDI" );
    contiene( cp, new File( EntornoKettle.pdi(), "classes" ).getAbsolutePath(), "classes de PDI" );
    contiene( cmd.toString(), "-DKETTLE_PLUGIN_BASE_FOLDERS=" + new File( EntornoKettle.pdi(), "plugins" )
      .getAbsolutePath(), "plugins de PDI" );
  }

  /** El classpath del plugin y la carpeta de PDI salen del jar instalado (plugins/pentaho-mcp/../..). */
  public void pruebaClasspath() throws Exception {
    File pdi = new File( lote( "pdi-falso" ), "data-integration" );
    File lib = new File( pdi, "lib" );
    lib.mkdirs();
    Files.write( new File( lib, "kettle-engine-9.4.0.0-343.jar" ).toPath(), new byte[ 0 ] );
    cierto( CorrectorProcesos.esPdi( pdi ), "lib/kettle-engine-*.jar" );
    falso( CorrectorProcesos.esPdi( lib ), "lib no es un PDI" );
    List<String> cp = CorrectorProcesos.classpath( pdi, Collections.singletonList( "plugin.jar" ) );
    igual( "plugin.jar", cp.get( 0 ), "el plugin primero" );
    cierto( cp.contains( lib.getAbsolutePath() + File.separator + "*" ), "lib/*: " + cp );
    // en las pruebas el plugin son las clases de test.ps1 (una carpeta, sin lib/)
    List<String> plugin = CorrectorProcesos.classpathDelPlugin();
    igual( 1, plugin.size(), "una carpeta de clases: " + plugin );
    cierto( new File( plugin.get( 0 ), "io/github/ivi2511/pentahomcp/CorrectorMain.class" ).isFile(), "CorrectorMain "
      + "en " + plugin );
  }

  /** La cola: dos workers no toman el mismo ticket; los nombres de tomadas/ dicen de quien. */
  public void pruebaCola() throws Exception {
    File cola = new File( lote( "cola" ), "_cola" );
    File pend = new File( cola, CorrectorMain.PENDIENTES );
    pend.mkdirs();
    for ( int i = 1; i <= 40; i++ ) {
      CorrectorJson.guardar( new Pedido( "a" + i, new File( "a" + i + ".ktr" ), null ), new File( pend, String.format(
        "%05d.json", i ) ) );
    }
    final List<String> tomados = Collections.synchronizedList( new ArrayList<String>() );
    List<Thread> hilos = new ArrayList<Thread>();
    for ( final String w : Arrays.asList( "w1_1", "w2_1", "w3_1" ) ) {
      Thread t = new Thread( () -> {
        try {
          File tk;
          while ( ( tk = CorrectorMain.tomar( cola, w ) ) != null ) {
            tomados.add( CorrectorMain.ticket( tk.getName() ) );
            cierto( tk.getName().startsWith( w + CorrectorMain.SEP_TOMADA ), "prefijo del worker" );
          }
        } catch ( IOException e ) {
          throw new RuntimeException( e );
        }
      } );
      hilos.add( t );
      t.start();
    }
    for ( Thread t : hilos ) {
      t.join( 30000 );
    }
    igual( 40, tomados.size(), "cada ticket una vez" );
    igual( 40, new java.util.HashSet<String>( tomados ).size(), "sin repetidos" );
    nulo( CorrectorMain.tomar( cola, "w9_1" ), "cola vacia" );
    igual( "e_cola", CorrectorMain.carpeta( "_cola" ), "carpeta de alumno que no choca con _cola" );
    igual( "ana", CorrectorMain.carpeta( "ana" ), "carpeta normal" );
  }

  // ------------------------------------------------------------------ lotes en hijas

  /**
   * Las 29 entregas de ventas-por-producto en un worker: estado, motivo, nota y estado de cada caso de casos.json.
   * Lo que no tiene que correr nunca (ulises, lara, nico, xime) no deja nada fuera del sandbox.
   */
  public void pruebaLoteVentas() throws Exception {
    File antesPublic = new File( "C:/Users/Public/robado.csv" );
    boolean existia = antesPublic.exists();
    Lote l = corregirTodas( VENTAS, pack( VENTAS ), dirPack( VENTAS ), opciones() );
    verificarContraCasos( VENTAS, l );
    falso( !existia && antesPublic.exists(), "ulises no escribio fuera" );

    // un worker sirve varias entregas (P3) y se recicla despues de cortar t6 (P18)
    cierto( l.lanzados.size() >= 2 && l.lanzados.size() < l.pedidos.size(), "workers lanzados: " + l.lanzados );
    ResultadoCorreccion t6 = l.de( "t6_infinito" );
    for ( ResultadoCaso rc : t6.casos ) {
      igual( ResultadoCaso.SALIDA_DEMASIADO_GRANDE, rc.corrida.estado, "t6/" + rc.id );
      cierto( rc.corrida.segundos < 15, "t6/" + rc.id + ": cortada por tamaño y no por timeout (" + rc.corrida.segundos
        + " s)" );
    }
    // lo que dice el resultado de una entrega corrida
    ResultadoCorreccion ana = l.de( "ana_orden_distinto" );
    igual( ResultadoCorreccion.FORMATO, ana.formato, "formato" );
    noNulo( ana.tiempos.inicioKettleSeg, "kettle_init_s" );
    noNulo( ana.tiempos.procesoSeg, "process_s" );
    noNulo( ana.corregidoEl, "graded_at" );
    igual( "file", ana.captura, "captura" );
    ResultadoCaso vis = ana.caso( "visible" );
    igual( "Escribir totales", vis.corrida.stepSalida, "output_step" );
    igual( "TextFileOutput", vis.corrida.tipoStepSalida, "output_step_type" );
    contiene( vis.corrida.capturadoDe, "file salida/", "captured_from" );
    igual( 4L, vis.corrida.filasCapturadas, "4 filas" );
    igual( 0L, vis.corrida.errores, "sin errores" );
    cierto( vis.corrida.metricas != null && vis.corrida.metricas.size() > 0, "metricas" );
    cierto( !vis.redirecciones.isEmpty(), "ana: redirecciones de su PC" );
    igual( 4, vis.comparacion.filas.iguales, "4/4 iguales" );
    // bruno: los ejemplos de la diferencia, y el oculto sin valores en la copia del alumno
    ResultadoCorreccion bruno = l.de( "bruno_agregado_mal" );
    cierto( !bruno.caso( "visible" ).comparacion.ejemplos.distintas.isEmpty(), "bruno: ejemplos del visible" );
    ResultadoCorreccion alumno = bruno.paraAlumno();
    nulo( alumno.caso( "oculto" ).comparacion.ejemplos, "oculto: sin ejemplos para el alumno" );
    nulo( alumno.caso( "oculto" ).corrida.log, "oculto: sin log para el alumno" );
    // walter: la salida de paula del mismo lote esta fuera de su sandbox
    ResultadoCorreccion walter = l.de( "walter_copia_companero" );
    contiene( walter.detalle, "outside the sandbox", "walter: por que" );
    // diego: bloqueado sin correr, con las reglas en el resultado
    ResultadoCorreccion diego = l.de( "diego_javascript" );
    igual( 1, diego.stepsBloqueados.size(), "diego: el step bloqueado" );
    cierto( diego.reglas.containsKey( "sin_codigo" ) && !diego.reglas.get( "sin_codigo" ).aprobada,
      "diego: sin_codigo" );
    // el sandbox se borra al terminar; _abiertos tiene el caso visible de una que corrio y no el oculto
    falso( new File( l.dir, CorrectorProcesos.SANDBOX ).exists(), "_sandbox borrado" );
    File abierto = new File( new File( l.dir, CorrectorProcesos.ABIERTOS ), "paula_correcta" );
    cierto( new File( abierto, "paula_correcta.ktr" ).isFile(), "paula: la copia reescrita en _abiertos" );
    cierto( new File( abierto, "datos/ventas.csv" ).isFile(), "paula: con sus datos (del caso visible)" );
    String[] salida = new File( abierto, "salida" ).list();
    igual( 0, salida == null ? 0 : salida.length, "salida/ vaciada despues de capturar" );
    falso( new File( new File( l.dir, CorrectorProcesos.ABIERTOS ), "diego_javascript" ).exists(), "diego no corrio" );
    // los logs de los workers
    String log = EntornoKettle.leer( new File( new File( l.dir, CorrectorProcesos.LOGS ), "worker-1.log" ) );
    contiene( log, "Kettle ready", "el worker inicio Kettle" );
    contiene( log, "recycling", "el worker se recicla" );
  }

  /** Captura query sobre la copia de la plantilla: ines 10, juan 5,5; kevin y lara bloqueadas; la plantilla igual. */
  public void pruebaLoteTabla() throws Exception {
    File plantilla = new File( dirPack( TABLA ), "plantilla/dw.sqlite" );
    byte[] antes = Files.readAllBytes( plantilla.toPath() );
    boolean existia = new File( "C:/Users/Public/copia.sqlite" ).exists();
    Lote l = corregirTodas( TABLA, pack( TABLA ), dirPack( TABLA ), opciones() );
    verificarContraCasos( TABLA, l );
    cierto( Arrays.equals( antes, Files.readAllBytes( plantilla.toPath() ) ), "P5: la plantilla no cambia" );
    falso( !existia && new File( "C:/Users/Public/copia.sqlite" ).exists(), "lara no creo nada fuera" );
    ResultadoCaso ines = l.de( "ines_tabla_ok" ).caso( "visible" );
    igual( "query db/dw.sqlite", ines.corrida.capturadoDe, "captured_from" );
    igual( 4L, ines.corrida.filasCapturadas, "4 filas en totales" );
    // mora corrio un Execute SQL (condicional): el worker se recicla despues
    contiene( EntornoKettle.leer( new File( new File( l.dir, CorrectorProcesos.LOGS ), "worker-1.log" ) ),
      "conditional steps", "mora: reciclado por steps condicionales" );
  }

  /** P9: el locale del pack llega a la hija; hugo (decimal vacio al leer) da 6,0 en es_ES y 10,0 en en_US. */
  public void pruebaLocaleDelPack() throws Exception {
    File dir = copiaDelPack( VENTAS, "en", ( pj ) -> ( (ObjectNode) pj.path( "policy" ) ).put( "locale", "en_US" ) );
    Pack p = CorrectorJson.leer( new File( dir, "pack.json" ), Pack.class ).completar();
    Lote l = corregir( p, dir, opciones(), entrega( VENTAS, "hugo_decimal.ktr" ), entrega( VENTAS,
      "paula_correcta.ktr" ) );
    igual( 10.0, l.de( "hugo_decimal" ).nota, "hugo con en_US (con es_ES da 6,0: pruebaLoteVentas)" );
    igual( 10.0, l.de( "paula_correcta" ).nota, "paula con en_US" );
    contiene( EntornoKettle.leer( new File( new File( l.dir, CorrectorProcesos.LOGS ), "worker-1.log" ) ),
      "-Duser.language=en -Duser.country=US", "el comando lleva el locale del pack" );
  }

  /**
   * P17: t4 (encoding vacio en la salida) da 10 con el pack en el charset nativo de Windows: Kettle escribe y el
   * corrector lee con el file.encoding del pack, aunque el padre traiga -Dfile.encoding=COMPAT (Java 8/11).
   */
  public void pruebaEncodingNativo() throws Exception {
    File dir = copiaDelPack( VENTAS, "cp1252", ( pj ) -> ( (ObjectNode) pj.path( "policy" ) ).put( "encoding",
      "windows-1252" ) );
    Pack p = CorrectorJson.leer( new File( dir, "pack.json" ), Pack.class ).completar();
    CorrectorProcesos.Opciones op = opciones();
    op.argsPadre = new ArrayList<String>( ManagementFactory.getRuntimeMXBean().getInputArguments() );
    op.argsPadre.add( "-Dfile.encoding=COMPAT" );
    Lote l = corregir( p, dir, op, entrega( VENTAS, "t4_encoding_vacio.ktr" ) );
    igual( 10.0, l.de( "t4_encoding_vacio" ).nota, "t4 con windows-1252" );
    String log = EntornoKettle.leer( new File( new File( l.dir, CorrectorProcesos.LOGS ), "worker-1.log" ) );
    contiene( log, "-Dfile.encoding=windows-1252", "la hija con el encoding del pack" );
    noContiene( log, "-Dfile.encoding=COMPAT", "sin el del padre" );
  }

  /**
   * Un caso que no termina: {@code timeout} en los dos casos (status timeout, solo las reglas sin compuerta) y el
   * worker se recicla; la siguiente corre en otro.
   */
  public void pruebaTimeoutDelCaso() throws Exception {
    File dir = copiaDelPack( VENTAS, "t3s", ( pj ) -> ( (ObjectNode) pj.path( "policy" ) ).put( "timeout_s", 3 ) );
    Pack p = CorrectorJson.leer( new File( dir, "pack.json" ), Pack.class ).completar();
    Lote l = corregir( p, dir, opciones(), lento(), entrega( VENTAS, "paula_correcta.ktr" ) );
    ResultadoCorreccion r = l.de( "zeta_lento" );
    igual( ResultadoCorreccion.TIEMPO_AGOTADO, r.estado, "status timeout" );
    for ( ResultadoCaso rc : r.casos ) {
      igual( ResultadoCaso.TIEMPO_AGOTADO, rc.corrida.estado, "caso " + rc.id );
      cierto( rc.corrida.segundos >= 3 && rc.corrida.segundos < 15, rc.id + ": cortado a los 3 s ("
        + rc.corrida.segundos + ")" );
    }
    igual( 1.0, r.nota, "solo sin_codigo y max_pasos (usa_group_by con compuerta en runs)" );
    igual( 10.0, l.de( "paula_correcta" ).nota, "paula despues" );
    igual( 2, l.lanzados.size(), "reciclado despues del timeout: " + l.lanzados );
  }

  /**
   * El padre: una entrega que se pasa del limite queda {@code killed_timeout} (se mata el worker), una que tira la
   * hija queda {@code crashed}; las dos con la nota de sus reglas (el parcial), y el lote sigue.
   */
  public void pruebaVigilanteDelPadre() throws Exception {
    File dir = copiaDelPack( VENTAS, "t30s", ( pj ) -> ( (ObjectNode) pj.path( "policy" ) ).put( "timeout_s", 30 ) );
    Pack p = CorrectorJson.leer( new File( dir, "pack.json" ), Pack.class ).completar();
    CorrectorProcesos.Opciones op = opciones();
    op.limiteEntregaMs = 6000;
    op.propiedades.put( CorrectorMain.PROP_CAER, "bruno_agregado_mal" );
    long t0 = System.currentTimeMillis();
    Lote l = corregir( p, dir, op, lento(), entrega( VENTAS, "bruno_agregado_mal.ktr" ), entrega( VENTAS,
      "paula_correcta.ktr" ) );
    ResultadoCorreccion lento = l.de( "zeta_lento" );
    igual( ResultadoCorreccion.MATADA, lento.estado, "killed_timeout" );
    contiene( lento.detalle, "killed", "detalle" );
    igual( 1.0, lento.nota, "las reglas del parcial" );
    cierto( lento.reglas.containsKey( "usa_group_by" ), "el parcial tenia las reglas" );
    ResultadoCorreccion bruno = l.de( "bruno_agregado_mal" );
    igual( ResultadoCorreccion.CAIDA, bruno.estado, "crashed" );
    contiene( bruno.detalle, "exit code 7", "el codigo de salida" );
    igual( 1.0, bruno.nota, "bruno: sus reglas sin compuerta" );
    igual( 10.0, l.de( "paula_correcta" ).nota, "el lote sigue" );
    igual( 3, l.lanzados.size(), "un worker por cada muerte: " + l.lanzados );
    cierto( System.currentTimeMillis() - t0 < 120000, "el limite del padre corta antes del timeout del caso" );
  }

  /**
   * Captura {@code step} (P4): el RowListener sobre el Text file output, proyectado a sus campos, da las mismas notas
   * que la captura {@code file} (numeros sin formato: {@code 7.5} contra {@code 5.00} se comparan como numeros).
   */
  public void pruebaCapturaStep() throws Exception {
    CorrectorProcesos.Opciones op = opciones();
    op.captura = Pack.Salida.CAPTURA_STEP;
    Lote l = corregir( pack( VENTAS ), dirPack( VENTAS ), op, entrega( VENTAS, "paula_correcta.ktr" ), entrega( VENTAS,
      "bruno_agregado_mal.ktr" ), entrega( VENTAS, "carla_falta_columna.ktr" ) );
    igual( 10.0, l.de( "paula_correcta" ).nota, "paula" );
    igual( 6.5, l.de( "bruno_agregado_mal" ).nota, "bruno (P4)" );
    igual( 9.7, l.de( "carla_falta_columna" ).nota, "carla (P4)" );
    ResultadoCorreccion paula = l.de( "paula_correcta" );
    igual( "step", paula.captura, "la captura pedida" );
    igual( "step Escribir totales", paula.caso( "visible" ).corrida.capturadoDe, "captured_from" );
    igual( 4L, paula.caso( "oculto" ).corrida.filasCapturadas, "filas del oculto" );
    cierto( l.de( "carla_falta_columna" ).caso( "visible" ).comparacion.columnas.faltan.contains( "ultima_venta" ),
      "carla: proyectado a los campos del Text file output" );
  }

  /**
   * El pack de un solo .json (inline, copia para alumnos): CorrectorPack lo escribe en una carpeta y el worker lo usa
   * como cualquier otro. Captura step, un parametro que la entrega no declara (aviso), reglas error_handling y lint, y
   * lint:absolute_path, evaluados con LintMotor en la hija.
   */
  public void pruebaPackInline() throws Exception {
    File trabajo = lote( "inline-pack" );
    try ( CorrectorPack cp = CorrectorPack.cargar( new File( new File( copia(), "packs" ), "ventas-inline.json" ),
      trabajo ) ) {
      Lote l = corregir( cp.pack(), cp.raiz(), opciones(), entrega( VENTAS, "paula_correcta.ktr" ), entrega( VENTAS,
        "ana_orden_distinto.ktr" ) );
      ResultadoCorreccion paula = l.de( "paula_correcta" );
      igual( ResultadoCorreccion.CORREGIDA, paula.estado, "paula corregida" );
      cierto( paula.criterio( "runs" ).aprobado && paula.criterio( "columns" ).aprobado && paula.criterio( "values" )
        .aprobado, "paula: salida perfecta por step" );
      cierto( paula.criterio( "rule:orden" ).aprobado, "lint unsorted_input en la hija: " + paula.reglas.get( "orden" )
        .detalle );
      cierto( paula.criterio( "lint:absolute_path" ).aprobado, "paula sin rutas absolutas" );
      cierto( paula.lint.containsKey( "absolute_path" ) && paula.lint.get( "absolute_path" ).evaluada,
        "lint evaluado" );
      falso( l.de( "ana_orden_distinto" ).criterio( "lint:absolute_path" ).aprobado, "ana: rutas de su PC" );
      boolean aviso = false;
      for ( ResultadoCorreccion.Aviso a : paula.avisos ) {
        aviso |= ResultadoCorreccion.Aviso.PARAMETRO_NO_DECLARADO.equals( a.codigo );
      }
      cierto( aviso, "FECHA_CORTE no esta declarado en paula: aviso" );
    }
  }

  /** Cancelar mata el worker, deja lo ya corregido y no deja el parcial de la que estaba a medias. */
  public void pruebaCancelar() throws Exception {
    File dir = copiaDelPack( VENTAS, "t60s", ( pj ) -> ( (ObjectNode) pj.path( "policy" ) ).put( "timeout_s", 60 ) );
    Pack p = CorrectorJson.leer( new File( dir, "pack.json" ), Pack.class ).completar();
    File out = lote( "cancelar" );
    final List<String> tomadas = Collections.synchronizedList( new ArrayList<String>() );
    final List<String> terminadas = Collections.synchronizedList( new ArrayList<String>() );
    List<Pedido> pedidos = Arrays.asList( new Pedido( "paula_correcta", entrega( VENTAS, "paula_correcta.ktr" ),
      null ), new Pedido( "zeta_lento", lento(), null ), new Pedido( "bruno", entrega( VENTAS,
      "bruno_agregado_mal.ktr" ), null ) );
    try ( CorrectorProcesos cp = new CorrectorProcesos( dir, p, out, opciones(), new CorrectorProcesos.Oyente() {
      @Override
      public void tomada( Pedido pd, String worker ) {
        tomadas.add( pd.alumno );
      }

      @Override
      public void terminada( Pedido pd, ResultadoCorreccion r ) {
        terminadas.add( pd.alumno );
      }
    } ) ) {
      cp.iniciar( pedidos );
      long fin = System.currentTimeMillis() + 120000;
      while ( !tomadas.contains( "zeta_lento" ) && System.currentTimeMillis() < fin ) {
        Thread.sleep( 100 );
      }
      cierto( tomadas.contains( "zeta_lento" ), "el worker tomo la lenta: " + tomadas );
      Thread.sleep( 1500 ); // ya esta corriendo su primer caso
      long t0 = System.currentTimeMillis();
      cp.cancelar();
      cierto( cp.esperar( 1000 ), "el lote termino al cancelar" );
      cierto( System.currentTimeMillis() - t0 < 20000, "cancelar no espera al timeout del caso" );
      cierto( cp.cancelado(), "cancelado" );
      igual( Collections.singletonList( "paula_correcta" ), terminadas, "solo lo ya corregido" );
      igual( 1, cp.hechas(), "una hecha" );
      cierto( new File( out, "paula_correcta.json" ).isFile(), "paula queda" );
      falso( new File( out, "zeta_lento.json" ).exists(), "sin el parcial de la que estaba a medias" );
      falso( new File( out, "bruno.json" ).exists(), "la que no se tomo, sin resultado" );
      List<ResultadoCorreccion> rs = cp.resultados( pedidos );
      noNulo( rs.get( 0 ), "paula" );
      nulo( rs.get( 1 ), "lenta sin resultado" );
    }
  }

  /** {@code CorrectorMain --pack ... --out ... entregas}: hace de padre y escribe una linea por entrega. */
  public void pruebaLineaDeComandos() throws Exception {
    File out = lote( "cli" );
    List<String> cmd = new ArrayList<String>();
    cmd.add( CorrectorProcesos.java().getAbsolutePath().replace( "javaw.exe", "java.exe" ) );
    cmd.add( "-Xmx384m" );
    cmd.addAll( CorrectorProcesos.flagsDe( ManagementFactory.getRuntimeMXBean().getInputArguments() ) );
    cmd.add( "-Dfile.encoding=UTF-8" );
    cmd.add( "-cp" );
    cmd.add( String.join( File.pathSeparator, CorrectorProcesos.classpath( EntornoKettle.pdi(),
      CorrectorProcesos.classpathDelPlugin() ) ) );
    cmd.add( CorrectorMain.class.getName() );
    cmd.addAll( Arrays.asList( "--pack", dirPack( VENTAS ).getAbsolutePath(), "--out", out.getAbsolutePath(),
      "--workers", "1", "--pdi", EntornoKettle.pdi().getAbsolutePath(), "--lang", "en" ) );
    cmd.add( entrega( VENTAS, "paula_correcta.ktr" ).getAbsolutePath() );
    cmd.add( entrega( VENTAS, "carla_falta_columna.ktr" ).getAbsolutePath() );
    out.mkdirs();
    File salida = new File( out, "cli.txt" );
    ProcessBuilder pb = new ProcessBuilder( cmd ).directory( out ).redirectErrorStream( true ).redirectOutput( salida );
    pb.environment().put( "KETTLE_HOME", new File( out, "kh" ).getAbsolutePath() );
    Process pr = pb.start();
    pr.getOutputStream().close();
    if ( !pr.waitFor( 300, TimeUnit.SECONDS ) ) {
      CorrectorProcesos.matar( pr );
      Verificador.falla( "la linea de comandos no termino" );
    }
    String texto = EntornoKettle.leer( salida );
    igual( 0, pr.exitValue(), "codigo de salida: " + texto );
    contiene( texto, "paula_correcta\tgraded\t10.0", "paula" );
    contiene( texto, "carla_falta_columna\tgraded\t9.7", "carla" );
    ResultadoCorreccion r = CorrectorJson.leer( new File( out, "carla_falta_columna.json" ),
      ResultadoCorreccion.class );
    igual( "en", r.idioma, "--lang" );
  }

  // ------------------------------------------------------------------ ayudas

  /** Un lote corrido: sus pedidos, los resultados y los workers lanzados. */
  private static final class Lote {

    File dir;
    final List<Pedido> pedidos = new ArrayList<Pedido>();
    final Map<String, ResultadoCorreccion> porAlumno = new LinkedHashMap<String, ResultadoCorreccion>();
    List<String> lanzados;

    ResultadoCorreccion de( String alumno ) {
      ResultadoCorreccion r = porAlumno.get( alumno );
      noNulo( r, alumno + ": tiene resultado" );
      return r;
    }
  }

  private static CorrectorProcesos.Opciones opciones() {
    CorrectorProcesos.Opciones op = new CorrectorProcesos.Opciones();
    op.workers = 1;
    op.xmxMb = 768;
    op.pdi = EntornoKettle.pdi();
    op.versionPlugin = "prueba";
    return op;
  }

  private Lote corregirTodas( String packId, Pack p, File dirPack, CorrectorProcesos.Opciones op ) throws Exception {
    List<File> archivos = new ArrayList<File>();
    for ( Iterator<String> it = expectativas( packId ).fieldNames(); it.hasNext(); ) {
      archivos.add( entrega( packId, it.next() ) );
    }
    return corregir( p, dirPack, op, archivos.toArray( new File[ 0 ] ) );
  }

  private static Lote corregir( Pack p, File dirPack, CorrectorProcesos.Opciones op, File... entregas )
    throws Exception {
    Lote l = new Lote();
    l.dir = lote( p.id );
    for ( File f : entregas ) {
      String alumno = f.getName().replace( ".ktr", "" );
      l.pedidos.add( new Pedido( alumno, f, new File( l.dir, alumno + ".json" ) ) );
    }
    try ( CorrectorProcesos cp = new CorrectorProcesos( dirPack, p, l.dir, op, null ) ) {
      cp.iniciar( l.pedidos );
      long fin = System.currentTimeMillis() + 15 * 60000L;
      while ( !cp.esperar( 1000 ) ) {
        if ( System.currentTimeMillis() > fin ) {
          cp.cancelar();
          Verificador.falla( "el lote no termino en 15 min" );
        }
      }
      nulo( cp.error(), "sin errores del supervisor" );
      List<ResultadoCorreccion> rs = cp.resultados( l.pedidos );
      for ( int i = 0; i < l.pedidos.size(); i++ ) {
        l.porAlumno.put( l.pedidos.get( i ).alumno, rs.get( i ) );
        // lo que el padre devolvio es lo que quedo escrito
        noNulo( rs.get( i ), l.pedidos.get( i ).alumno + ": resultado" );
        ResultadoCorreccion enDisco = CorrectorJson.leer( new File( l.pedidos.get( i ).resultado ),
          ResultadoCorreccion.class );
        igual( rs.get( i ).estado, enDisco.estado, l.pedidos.get( i ).alumno + ": el JSON del disco" );
      }
      l.lanzados = cp.workersLanzados();
    }
    return l;
  }

  /** Cada entrega contra casos.json: status, reason, grade y el estado de cada caso. */
  private void verificarContraCasos( String packId, Lote l ) throws Exception {
    List<String> malas = new ArrayList<String>();
    for ( Iterator<Map.Entry<String, JsonNode>> it = expectativas( packId ).fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String alumno = e.getKey().replace( ".ktr", "" );
      JsonNode esp = e.getValue();
      ResultadoCorreccion r = l.de( alumno );
      cierto( ResultadoCorreccion.estadoValido( r.estado ), alumno + ": estado del §6" );
      cierto( r.terminada(), alumno + ": no quedo en running" );
      StringBuilder d = new StringBuilder();
      if ( !esp.path( "status" ).asText().equals( r.estado ) ) {
        d.append( " status " ).append( r.estado ).append( "!=" ).append( esp.path( "status" ).asText() );
      }
      if ( esp.hasNonNull( "reason" ) && !esp.path( "reason" ).asText().equals( r.motivo ) ) {
        d.append( " reason " ).append( r.motivo ).append( "!=" ).append( esp.path( "reason" ).asText() );
      }
      if ( r.nota == null || Math.abs( esp.path( "grade" ).asDouble() - r.nota ) > 1e-9 ) {
        d.append( " grade " ).append( r.nota ).append( "!=" ).append( esp.path( "grade" ).asDouble() );
      }
      for ( Iterator<Map.Entry<String, JsonNode>> c = esp.path( "cases" ).fields(); c.hasNext(); ) {
        Map.Entry<String, JsonNode> ce = c.next();
        ResultadoCaso rc = r.caso( ce.getKey() );
        String est = rc == null ? null : rc.corrida.estado;
        if ( !ce.getValue().asText().equals( est ) ) {
          d.append( " case " ).append( ce.getKey() ).append( " " ).append( est ).append( "!=" ).append( ce.getValue()
            .asText() ).append( rc == null || rc.corrida.detalle == null ? "" : " (" + rc.corrida.detalle + ")" );
        }
      }
      if ( d.length() > 0 ) {
        malas.add( alumno + ":" + d + ( r.detalle == null ? "" : " [" + r.detalle + "]" ) );
      }
    }
    igual( Collections.emptyList(), malas, packId + ": entregas que no dan lo de casos.json" );
  }

  private static JsonNode expectativas( String packId ) throws IOException {
    for ( JsonNode p : CorrectorRecursos.casos().path( "packs" ) ) {
      if ( packId.equals( p.path( "id" ).asText() ) ) {
        return p.path( "expected" );
      }
    }
    throw new IllegalStateException( "no hay " + packId + " en casos.json" );
  }

  private File dirPack( String packId ) throws IOException {
    return new File( new File( copia(), "packs" ), packId );
  }

  private File entrega( String packId, String archivo ) throws IOException {
    return new File( new File( new File( copia(), "entregas" ), packId ), archivo );
  }

  private Pack pack( String packId ) throws IOException {
    return CorrectorJson.leer( new File( dirPack( packId ), "pack.json" ), Pack.class ).completar();
  }

  private interface Cambio {
    void aplicar( JsonNode packJson );
  }

  /** Una copia del pack (en la copia de las fixtures) con un cambio en pack.json. */
  private File copiaDelPack( String packId, String sufijo, Cambio cambio ) throws IOException {
    File de = dirPack( packId );
    File a = new File( de.getParentFile(), packId + "-" + sufijo );
    copiarArbol( de, a );
    File pj = new File( a, "pack.json" );
    JsonNode n = CorrectorJson.mapper().readTree( pj );
    cambio.aplicar( n );
    Files.write( pj.toPath(), CorrectorJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes( n ) );
    return a;
  }

  /** t6 con una fila por segundo: no termina, pero tampoco escribe mucho (corta el timeout, no max_output_mb). */
  private File lento() throws IOException {
    File t6 = entrega( VENTAS, "t6_infinito.ktr" );
    String x = new String( Files.readAllBytes( t6.toPath() ), StandardCharsets.UTF_8 );
    String y = x.replace( "<interval_in_ms>0</interval_in_ms>", "<interval_in_ms>1000</interval_in_ms>" );
    cierto( !x.equals( y ), "t6 tiene interval_in_ms" );
    File f = new File( t6.getParentFile(), "zeta_lento.ktr" );
    Files.write( f.toPath(), y.getBytes( StandardCharsets.UTF_8 ) );
    return f;
  }

  private static void copiarArbol( File de, File a ) throws IOException {
    a.mkdirs();
    File[] hijos = de.listFiles();
    for ( File h : hijos == null ? new File[ 0 ] : hijos ) {
      File d = new File( a, h.getName() );
      if ( h.isDirectory() ) {
        copiarArbol( h, d );
      } else {
        Files.copy( h.toPath(), d.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING );
      }
    }
  }
}
