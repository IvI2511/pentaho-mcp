package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.pentaho.di.core.Result;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.logging.LoggingObjectType;
import org.pentaho.di.core.logging.SimpleLoggingObject;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasBoceto;

/**
 * plan_build del job de la orquestación (0.7.1) sin Spoon, con el plano de Grano de Oro: el orden de las
 * transformaciones (dimensiones antes que hechos), la misma estructura que solucion-referencia/job_carga_dw.kjb (START,
 * comprobar ficheros, crear salida, crear el modelo, cinco TRANS, SUCCESS y Write to log + Abort con un hop rojo desde
 * cada entrada), el CREATE TABLE sacado de las transformaciones, el job que corre de verdad sobre una copia del ejemplo
 * (dos veces: es idempotente), el boceto con marcadores y fill_placeholder, el Ctrl+Z único en un job que ya existía,
 * plan_check del job y las variantes continue / aviso por correo.
 */
public class PruebaPlanoJob {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static Plano granoDeOro() throws Exception {
    return Plano.leer( PruebaPlano.ejemplo() ).plano;
  }

  private static List<String> tipos( JobMeta jm ) {
    List<String> l = new ArrayList<String>();
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      l.add( c.isStart() ? "START" : c.isDummy() ? "DUMMY" : c.getEntry().getPluginId() );
    }
    return l;
  }

  private static JobMeta construir( Plano p, String modo ) throws Exception {
    JobMeta jm = new JobMeta();
    PlanoJob.Pedido pd = PlanoJob.pedido( p, modo, "es", null );
    PlanoJob.construirAhora( null, jm, p, pd, "es", null, true );
    return jm;
  }

  public void pruebaOrdenYEstructuraComoLaReferencia() throws Exception {
    Plano p = granoDeOro();
    List<String> avisos = new ArrayList<String>();
    igual( "[01_dim_tienda, 02_dim_producto, 03_dim_fecha, 04_hechos_ventas, 05_kpi_ventas]", PlanoJob.orden( p, avisos )
      .toString(), "el orden del plano" );
    igual( 0, avisos.size(), "sin avisos: " + avisos );
    // sin orchestration.order: por dependencias, las dimensiones antes que los hechos
    p.orquestacion.orden.clear();
    java.util.Collections.reverse( p.etapas );
    List<String> sinOrden = PlanoJob.orden( p, avisos );
    cierto( sinOrden.indexOf( "04_hechos_ventas" ) > sinOrden.indexOf( "01_dim_tienda" ) && sinOrden.indexOf(
      "04_hechos_ventas" ) > sinOrden.indexOf( "03_dim_fecha" ), "hechos después de las dimensiones: " + sinOrden );
    igual( "05_kpi_ventas", sinOrden.get( 4 ), "los KPI leen los hechos: al final" );

    p = granoDeOro();
    PlanoJob.Pedido pd = PlanoJob.pedido( p, PlanoConstruir.STEPS, "es", null );
    igual( "[dim_tienda, dim_producto, dim_fecha, hechos_ventas, kpi_ventas_tienda_categoria, kpi_ticket_medio_tienda]",
      pd.tablas.toString(), "el CREATE TABLE de las seis tablas sale de las transformaciones construidas" );
    JobMeta jm = new JobMeta();
    ObjectNode o = PlanoJob.construirAhora( null, jm, p, pd, "es", null, true );
    System.out.println( "  job de Grano de Oro: " + o );
    JobMeta ref = (JobMeta) PlanoTools.abrirArchivo( new File( p.archivo.getParentFile(),
      "../solucion-referencia/job_carga_dw.kjb" ) );
    igual( tipos( ref ), tipos( jm ), "las mismas entradas que la referencia, en el mismo orden" );
    igual( ref.nrJobHops(), jm.nrJobHops(), "los mismos hops" );
    igual( PlanoDeriva.ordenJob( ref ), PlanoDeriva.ordenJob( jm ), "el mismo orden de transformaciones" );
    int rojos = 0;
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      rojos += !h.isUnconditional() && !h.getEvaluation() ? 1 : 0;
    }
    igual( 8, rojos, "un hop rojo desde cada entrada a «Registrar el error»" );
    String xml = jm.getXML();
    contiene( xml, "${Internal.Entry.Current.Directory}/../datos/ventas_2026_09.csv", "ruta relativa de la fuente" );
    contiene( xml, "<foldername>${Internal.Entry.Current.Directory}/../salida</foldername>", "la carpeta salida" );
    contiene( xml, "${Internal.Entry.Current.Directory}/04_hechos_ventas.ktr", "TRANS relativo" );
    contiene( xml, "DROP TABLE IF EXISTS", "overwrite: se borra" );
    contiene( xml, "CREATE TABLE", "y se crea" );
    noNulo( jm.findDatabase( "dw_grano_de_oro" ), "la conexión de las transformaciones" );
    igual( 0, PlanoJob.progreso( jm ).path( "placeholders" ).size(), "sin marcadores en mode=steps" );
    contiene( o.path( "save_as" ).asText(), "job_carga_dw.kjb", "dónde guardarlo" );
    contiene( o.path( "undo" ).asText(), "close it without saving", "documento nuevo" );
    // plan_check: el job construido cumple el plano
    PlanoDeriva.Resultado r = PlanoDeriva.revisar( p, new LinkedHashMap<String, Object[]>(), new Object[] { jm, "open",
      "J" }, "es" );
    for ( PlanoDeriva.Hallazgo h : r.hallazgos ) {
      falso( h.codigo.startsWith( "job_" ) || h.codigo.equals( "transformation_not_in_job" ), "sin deriva del job: "
        + h.codigo + " " + h.mensaje );
    }
  }

  /** Una copia del ejemplo sin el job de referencia; el job construido se guarda ahí y corre (dos veces). */
  public void pruebaElJobCorre() throws Exception {
    File raiz = new File( EntornoKettle.salida( "plano-job" ), "ejemplo" );
    if ( raiz.exists() ) {
      EntornoKettle.borrar( raiz.toPath() );
    }
    File ej = PruebaPlano.ejemplo().getParentFile().getParentFile();
    copiar( new File( ej, "datos" ), new File( raiz, "datos" ) );
    copiar( new File( ej, "plano" ), new File( raiz, "plano" ) );
    copiar( new File( ej, "solucion-referencia" ), new File( raiz, "solucion-referencia" ) );
    File kjb = new File( raiz, "solucion-referencia/job_carga_dw.kjb" );
    Files.delete( kjb.toPath() );
    falso( new File( raiz, "salida" ).exists(), "sin salida antes" );
    Plano p = Plano.leer( new File( raiz, "plano/grano-de-oro.plan.yaml" ) ).plano;
    JobMeta jm = construir( p, PlanoConstruir.STEPS );
    Files.write( kjb.toPath(), jm.getXML().getBytes( StandardCharsets.UTF_8 ) );
    for ( int vez = 1; vez <= 2; vez++ ) {
      JobMeta leido = new JobMeta( kjb.getAbsolutePath(), null );
      Job job = new Job( null, leido );
      job.setLogLevel( LogLevel.MINIMAL );
      job.start();
      job.waitUntilFinished();
      Result res = job.getResult();
      igual( 0L, res.getNrErrors(), "vuelta " + vez + ": sin errores" );
      cierto( res.getResult(), "vuelta " + vez + ": el job termina bien" );
      DatabaseMeta dm = new DatabaseMeta( "x", "SQLITE", "Native", null, new File( raiz, "salida/grano_de_oro_dw.sqlite" )
        .getAbsolutePath(), "-1", null, null );
      Database db = new Database( new SimpleLoggingObject( "prueba", LoggingObjectType.GENERAL, null ), dm );
      try {
        db.connect();
        igual( 2885L, db.getOneRow( "SELECT COUNT(*) FROM hechos_ventas" ).getInteger( 0 ), "vuelta " + vez
          + ": hechos" );
        igual( 4L, db.getOneRow( "SELECT COUNT(*) FROM dim_tienda" ).getInteger( 0 ), "dim_tienda" );
        igual( 20L, db.getOneRow( "SELECT COUNT(*) FROM kpi_ventas_tienda_categoria" ).getInteger( 0 ), "KPI" );
      } finally {
        db.disconnect();
      }
    }
    String rech = new String( Files.readAllBytes( new File( raiz, "salida/rechazos_ventas.csv" ).toPath() ),
      StandardCharsets.UTF_8 );
    cierto( rech.split( "\n" ).length >= 116, "115 rechazos y la cabecera" );
  }

  public void pruebaBocetoYRellenar() throws Exception {
    Plano p = granoDeOro();
    JobMeta jm = construir( p, PlanoConstruir.SKETCH );
    List<String> ts = tipos( jm );
    igual( "START", ts.get( 0 ), "START de verdad" );
    for ( int i = 1; i < ts.size(); i++ ) {
      igual( "DUMMY", ts.get( i ), "marcador " + jm.getJobEntry( i ).getName() );
    }
    igual( 11, PlanoJob.progreso( jm ).path( "placeholders" ).size(), "11 marcadores" );
    igual( 12, jm.nrNotes(), "una nota por marcador y la general" );
    JobEntryCopy t1 = PlanoJob.buscar( jm, "Ejecutar 01_dim_tienda" );
    igual( "TRANS", t1.getAttribute( ReglasBoceto.GRUPO, PlanoJob.K_TIPO ), "guarda la entrada planeada" );
    // los hops rojos ya están, aunque salgan de un DUMMY
    int rojos = 0;
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      rojos += !jm.getJobHop( i ).isUnconditional() && !jm.getJobHop( i ).getEvaluation() ? 1 : 0;
    }
    igual( 8, rojos, "el camino de falla planeado" );
    // plan_check: los marcadores
    PlanoDeriva.Resultado r = PlanoDeriva.revisar( p, new LinkedHashMap<String, Object[]>(), new Object[] { jm, "open",
      "J" }, "es" );
    boolean visto = false;
    for ( PlanoDeriva.Hallazgo h : r.hallazgos ) {
      visto |= "job_placeholder_left".equals( h.codigo );
    }
    cierto( visto, "plan_check cuenta los marcadores del job" );
    // fill_placeholder sin tipo: la entrada planeada
    int notas = jm.nrNotes();
    int hops = jm.nrJobHops();
    ObjectNode o = PlanoJob.rellenar( null, jm, "Ejecutar 01_dim_tienda", null, false, null );
    JobEntryCopy nueva = PlanoJob.buscar( jm, "Ejecutar 01_dim_tienda" );
    igual( "TRANS", nueva.getEntry().getPluginId(), "ahora es un TRANS" );
    contiene( nueva.getEntry().getXML(), "01_dim_tienda.ktr", "con su archivo" );
    igual( hops, jm.nrJobHops(), "los mismos hops" );
    igual( notas - 1, jm.nrNotes(), "sin su nota" );
    cierto( o.path( "planned" ).asBoolean(), "la planeada" );
    boolean rojo = false;
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      rojo |= h.getFromEntry() == nueva && !h.isUnconditional() && !h.getEvaluation();
    }
    cierto( rojo, "conserva su hop rojo" );
    // un Ctrl+Z devuelve el marcador
    deshacerJob( jm );
    JobEntryCopy otraVez = PlanoJob.buscar( jm, "Ejecutar 01_dim_tienda" );
    cierto( otraVez.isDummy(), "vuelve el marcador" );
    igual( notas, jm.nrNotes(), "y su nota" );
    igual( hops, jm.nrJobHops(), "y sus hops" );
    // con otro tipo
    PlanoJob.rellenar( null, jm, "Carga correcta", PlanoConstruir.piezas( McpHttpServer.JSON.readTree(
      "{\"type\":\"SUCCESS\"}" ) ), false, null );
    igual( "SUCCESS", PlanoJob.buscar( jm, "Carga correcta" ).getEntry().getPluginId(), "con type" );
    lanza( ToolError.class, () -> PlanoJob.rellenar( null, jm, "START", null, false, null ), "START no es marcador" );
  }

  /** Un job enlazado que ya tenía entradas: se agrega lo que falta y un Ctrl+Z lo quita todo. */
  public void pruebaJobExistenteUnCtrlZ() throws Exception {
    Plano p = granoDeOro();
    JobMeta jm = new JobMeta();
    JobEntryCopy ini = JobMeta.createStartEntry();
    ini.setLocation( 50, 50 );
    jm.addJobEntry( ini );
    JobEntryCopy dim = PlanoJob.desdeXml( jm, "Ejecutar 01_dim_tienda", "TRANS", "<specification_method>filename"
      + "</specification_method><filename>${Internal.Entry.Current.Directory}/01_dim_tienda.ktr</filename>", null );
    dim.setLocation( 200, 50 );
    jm.addJobEntry( dim );
    jm.addJobHop( new JobHopMeta( ini, dim ) );
    int antes = jm.nrJobEntries();
    PlanoJob.Pedido pd = PlanoJob.pedido( p, PlanoConstruir.STEPS, "es", null );
    ObjectNode o = PlanoJob.construirAhora( null, jm, p, pd, "es", null, false );
    contiene( o.path( "skipped_already_in_job" ).toString(), "Ejecutar 01_dim_tienda", "salta la que estaba" );
    igual( 12, jm.nrJobEntries(), "las que faltaban" );
    contiene( o.path( "undo" ).asText(), "One Ctrl+Z", "un Ctrl+Z" );
    deshacerJob( jm );
    igual( antes, jm.nrJobEntries(), "un Ctrl+Z deja el job como estaba" );
    igual( 1, jm.nrJobHops(), "con su hop" );
    igual( 0, jm.nrNotes(), "sin notas" );
  }

  public void pruebaContinueAvisoYSinOrquestacion() throws Exception {
    String base = new String( Files.readAllBytes( PruebaPlano.ejemplo().toPath() ), StandardCharsets.UTF_8 );
    Plano seguir = Plano.leerTexto( base.replace( "on_failure: abort", "on_failure: continue" ) ).plano;
    seguir.archivo = PruebaPlano.ejemplo().getAbsoluteFile();
    JobMeta jm = construir( seguir, PlanoConstruir.STEPS );
    nulo( PlanoJob.buscar( jm, "Registrar el error" ), "continue: sin camino de falla" );
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      cierto( jm.getJobHop( i ).isUnconditional(), "hops negros" );
    }
    Plano correo = Plano.leerTexto( base.replace( "notify: Registrar el error", "notify: datos@ejemplo.org" ) ).plano;
    correo.archivo = PruebaPlano.ejemplo().getAbsoluteFile();
    JobMeta jc = construir( correo, PlanoConstruir.STEPS );
    JobEntryCopy av = PlanoJob.buscar( jc, "Avisar a datos@ejemplo.org" );
    noNulo( av, "el aviso por correo" );
    cierto( PlanoJob.esMarcador( av ), "queda como marcador (SMTP)" );
    Plano sin = Plano.leerTexto( base.replaceAll( "(?s)orchestration:.*?environment:", "environment:" ) ).plano;
    lanza( ToolError.class, () -> PlanoJob.pedido( sin, PlanoConstruir.SKETCH, "es", null ), "sin orquestación" );
    cierto( PlanoJob.esElJob( granoDeOro(), "JOB_CARGA_DW.kjb" ), "transformation=<job> lo elige" );
  }

  /** Un Ctrl+Z en un job como Spoon (SpoonJobDelegate.undoJobAction, cambio de entrada). */
  static void deshacerJob( JobMeta jm ) {
    TransAction ta = jm.previousUndo();
    noNulo( ta, "hay algo que deshacer" );
    igual( TransAction.TYPE_ACTION_CHANGE_JOB_ENTRY, ta.getType(), "un cambio de entrada" );
    for ( int i = 0; i < ta.getCurrentIndex().length; i++ ) {
      JobEntryCopy prev = (JobEntryCopy) ( (JobEntryCopy) ta.getPrevious()[ i ] ).clone();
      jm.getJobEntry( ta.getCurrentIndex()[ i ] ).replaceMeta( prev );
    }
    nulo( jm.viewPreviousUndo(), "UNA acción" );
  }

  private static void copiar( File desde, File hacia ) throws Exception {
    hacia.mkdirs();
    for ( File f : desde.listFiles() ) {
      if ( f.isDirectory() ) {
        copiar( f, new File( hacia, f.getName() ) );
      } else {
        Files.copy( f.toPath(), new File( hacia, f.getName() ).toPath(), StandardCopyOption.REPLACE_EXISTING );
      }
    }
  }
}
