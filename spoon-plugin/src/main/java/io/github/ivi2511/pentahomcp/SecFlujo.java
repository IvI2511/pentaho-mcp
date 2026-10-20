package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.job.JobEntryJob;
import org.pentaho.di.job.entries.success.JobEntrySuccess;
import org.pentaho.di.job.entries.writetolog.JobEntryWriteToLog;
import org.pentaho.di.job.entries.trans.JobEntryTrans;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Secciones flow (transformaciones) y job_flow (jobs), diseno §2.3: el lienzo real (captura.lienzo,
 * pintado en la UI) mas el Mermaid de ReportMotor (el mismo de generate_report), la tabla de hops como
 * alternativa, «que hace cada step / entrada» y, en jobs, «que ejecuta» con el archivo resuelto.
 *
 * @dueño WP2
 */
final class SecFlujo implements Recolector {

  private final boolean job;

  /** @param job false = flow (transformaciones), true = job_flow (jobs). */
  SecFlujo( boolean job ) {
    this.job = job;
  }

  @Override
  public String id() {
    return job ? SeccionId.FLUJO_JOB : SeccionId.FLUJO;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    boolean esJob = c != null && ( c.esJob() || c.copia instanceof JobMeta );
    if ( job == esJob ) {
      return null;
    }
    return Rotulos.de( InformeServicio.AREA, idioma ).s( job ? "no_aplica.solo_job" : "no_aplica.solo_trans" );
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    return job ? armarJob( ctx ) : armarTrans( ctx );
  }

  // ---------------------------------------------------------------- transformaciones

  private Seccion armarTrans( Contexto ctx ) {
    Rotulos r = ctx.rotulos( InformeServicio.AREA );
    Captura c = ctx.captura();
    TransMeta tm = (TransMeta) c.copia;
    ReportMotor.T t = new ReportMotor.T( ctx.idioma() );
    String nombre = c.nombre != null ? c.nombre : Docs.nombre( tm );
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), ctx.idioma() ) );
    List<StepMeta> orden = ctx.orden();
    ObjectNode datos = Txt.obj();
    datos.put( "section", id() );

    int errores = 0;
    int apagados = 0;
    Tabla hops = new Tabla( r.s( "flujo.col.desde" ), r.s( "flujo.col.hacia" ), r.s( "flujo.col.hop" ) );
    ArrayNode dh = datos.putArray( "hops" );
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() == null || h.getToStep() == null ) {
        continue;
      }
      List<String> clase = new ArrayList<String>();
      clase.add( r.s( h.isErrorHop() ? "flujo.hop.error" : "flujo.hop.normal" ) );
      if ( h.isErrorHop() ) {
        errores++;
      }
      if ( !h.isEnabled() ) {
        apagados++;
        clase.add( r.s( "flujo.hop.off" ) );
      }
      hops.fila( h.getFromStep().getName(), h.getToStep().getName(), unir( clase ) );
      ObjectNode o = dh.addObject();
      o.put( "from", h.getFromStep().getName() );
      o.put( "to", h.getToStep().getName() );
      o.put( "enabled", h.isEnabled() );
      o.put( "error", h.isErrorHop() );
    }

    if ( orden.isEmpty() ) {
      s.resumen = r.s( "resumen.vacio" );
      s.datos = datos;
      return s;
    }

    Diagrama d = new Diagrama();
    d.id = "flow-diagrama";
    d.clase = Diagrama.FLUJO;
    d.mermaid = ReportMotor.mermaidTrans( tm, orden, t );
    d.imagen = lienzo( c, "flow-lienzo", r.f( "flujo.alt", nombre ) );
    d.alt = d.imagen != null ? r.f( "flujo.alt", nombre ) : r.f( "flujo.mermaid_alt", nombre );
    d.nota = r.s( "flujo.leyenda.trans" );
    hops.titulo = r.s( "flujo.tabla.hops" );
    d.alternativa = hops;
    s.con( d );

    Tabla pasos = new Tabla( r.s( "flujo.col.n" ), r.s( "flujo.col.step" ), r.s( "flujo.col.tipo" ),
      r.s( "flujo.col.hace" ) );
    pasos.id = "flow-steps";
    pasos.titulo = r.s( "flujo.tabla.steps" );
    pasos.alineacion = new ArrayList<String>( Arrays.asList( "r", "l", "l", "l" ) );
    ArrayNode ds = datos.putArray( "steps" );
    int i = 1;
    for ( StepMeta sm : orden ) {
      String hace = Txt.plano( ReportMotor.queHaceStep( tm, sm, t ) );
      String tipo = NombresPlugin.conId( StepPluginType.class, sm.getStepID(), ctx.idioma() );
      pasos.fila( String.valueOf( i++ ), sm.getName(), tipo, hace );
      ObjectNode o = ds.addObject();
      o.put( "name", sm.getName() );
      o.put( "type", sm.getStepID() );
      o.put( "type_name", NombresPlugin.nombre( StepPluginType.class, sm.getStepID(), ctx.idioma() ) );
      Txt.poner( o, "does", hace );
      if ( sm.getCopies() > 1 ) {
        o.put( "copies", sm.getCopies() );
      }
      if ( RunMotor.escribe( sm ) ) {
        o.put( "writes", true );
      }
      if ( sm.isMissing() ) {
        o.put( "missing_plugin", true );
      }
    }
    s.con( pasos );

    List<String> extra = new ArrayList<String>();
    if ( errores > 0 ) {
      extra.add( Txt.n( r, "n.hop_error", errores ) );
    }
    if ( apagados > 0 ) {
      extra.add( Txt.n( r, "n.hop_off", apagados ) );
    }
    String cuenta = r.f( "cuenta", Txt.n( r, "n.step", orden.size() ), Txt.n( r, "n.hop", tm.nrTransHops() ) );
    s.resumen = cuenta + " " + ( extra.isEmpty() ? r.s( "flujo.sin_error" ) : r.f( "flujo.extra", Txt.lista( r,
      extra ) ) );
    datos.put( "step_count", orden.size() );
    datos.put( "hop_count", tm.nrTransHops() );
    datos.put( "error_hops", errores );
    datos.put( "disabled_hops", apagados );
    datos.put( "canvas_image", d.imagen != null );
    s.datos = datos;
    return s;
  }

  // ---------------------------------------------------------------- jobs

  private Seccion armarJob( Contexto ctx ) {
    Rotulos r = ctx.rotulos( InformeServicio.AREA );
    Captura c = ctx.captura();
    JobMeta jm = (JobMeta) c.copia;
    ReportMotor.T t = new ReportMotor.T( ctx.idioma() );
    String nombre = c.nombre != null ? c.nombre : Docs.nombre( jm );
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), ctx.idioma() ) );
    List<JobEntryCopy> orden = ReportMotor.ordenJob( jm );
    ObjectNode datos = Txt.obj();
    datos.put( "section", id() );

    int apagados = 0;
    Tabla hops = new Tabla( r.s( "flujo.col.desde" ), r.s( "flujo.col.hacia" ), r.s( "flujo.col.hop" ) );
    ArrayNode dh = datos.putArray( "hops" );
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      if ( h.getFromEntry() == null || h.getToEntry() == null ) {
        continue;
      }
      List<String> clase = new ArrayList<String>();
      String cond = h.isUnconditional() ? "always" : h.getEvaluation() ? "success" : "failure";
      clase.add( r.s( h.isUnconditional() ? "flujo.hop.siempre" : h.getEvaluation() ? "flujo.hop.ok"
        : "flujo.hop.falla" ) );
      if ( !h.isEnabled() ) {
        apagados++;
        clase.add( r.s( "flujo.hop.off" ) );
      }
      hops.fila( h.getFromEntry().getName(), h.getToEntry().getName(), unir( clase ) );
      ObjectNode o = dh.addObject();
      o.put( "from", h.getFromEntry().getName() );
      o.put( "to", h.getToEntry().getName() );
      o.put( "condition", cond );
      o.put( "enabled", h.isEnabled() );
    }

    if ( orden.isEmpty() ) {
      s.resumen = r.s( "resumen.vacio" );
      s.datos = datos;
      return s;
    }

    Diagrama d = new Diagrama();
    d.id = "job-flow-diagrama";
    d.clase = Diagrama.JOB;
    d.mermaid = ReportMotor.mermaidJob( jm, orden, t );
    d.imagen = lienzo( c, "job-flow-lienzo", r.f( "flujo.alt", nombre ) );
    d.alt = d.imagen != null ? r.f( "flujo.alt", nombre ) : r.f( "flujo.mermaid_alt", nombre );
    d.nota = r.s( "flujo.leyenda.job" );
    hops.titulo = r.s( "flujo.tabla.hops" );
    d.alternativa = hops;
    s.con( d );

    Tabla entradas = new Tabla( r.s( "flujo.col.n" ), r.s( "flujo.col.entrada" ), r.s( "flujo.col.tipo" ),
      r.s( "flujo.col.hace" ) );
    entradas.id = "job-flow-entradas";
    entradas.titulo = r.s( "flujo.tabla.entradas" );
    entradas.alineacion = new ArrayList<String>( Arrays.asList( "r", "l", "l", "l" ) );
    ArrayNode de = datos.putArray( "entries" );
    int i = 1;
    for ( JobEntryCopy e : orden ) {
      String plugin = e.getEntry() == null ? null : e.getEntry().getPluginId();
      String propio = queHacePropio( e, r );
      String hace = propio != null ? propio : Txt.plano( ReportMotor.queHaceEntrada( e, t ) );
      entradas.fila( String.valueOf( i++ ), e.getName(), tipoEntrada( e, plugin, r, ctx.idioma() ), hace );
      ObjectNode o = de.addObject();
      o.put( "name", e.getName() );
      Txt.poner( o, "type", plugin );
      Txt.poner( o, "does", hace );
      if ( e.isStart() ) {
        o.put( "start", true );
      }
    }
    s.con( entradas );

    List<Llamada> llamadas = llamadas( jm, c.archivo );
    if ( !llamadas.isEmpty() ) {
      Tabla ej = new Tabla( r.s( "flujo.col.entrada" ), r.s( "flujo.col.tipo" ), r.s( "flujo.col.archivo" ),
        r.s( "flujo.col.existe" ) );
      ej.id = "job-flow-ejecuta";
      ej.titulo = r.s( "flujo.tabla.ejecuta" );
      ArrayNode dl = datos.putArray( "runs" );
      for ( Llamada l : llamadas ) {
        ej.fila( l.entrada, r.s( l.job ? "flujo.ejecuta.job" : "flujo.ejecuta.trans" ), l.mostrar,
          l.existe == null ? "?" : r.s( l.existe ? "si" : "no" ) );
        ObjectNode o = dl.addObject();
        o.put( "entry", l.entrada );
        o.put( "kind", l.job ? "job" : "transformation" );
        Txt.poner( o, "file", l.mostrar );
        Txt.poner( o, "configured", l.crudo );
        if ( l.existe != null ) {
          o.put( "found", l.existe );
        }
      }
      s.con( ej );
      for ( Llamada l : llamadas ) {
        if ( Boolean.FALSE.equals( l.existe ) ) {
          Aviso a = new Aviso( Aviso.WARN, r.f( "flujo.aviso.falta", l.entrada, l.mostrar ) );
          a.id = "job-flow-falta-" + ( llamadas.indexOf( l ) + 1 );
          s.con( a );
        }
      }
    }

    String cuenta = r.f( "cuenta", Txt.n( r, "n.entrada", orden.size() ), Txt.n( r, "n.hop", jm.nrJobHops() ) );
    String ejecuta = frasejecuta( r, llamadas );
    s.resumen = cuenta + ( ejecuta == null ? "" : " " + Txt.mayuscula( ejecuta ) + "." )
      + ( apagados > 0 ? " " + r.f( "flujo.extra", Txt.n( r, "n.hop_off", apagados ) ) : "" );
    datos.put( "entry_count", orden.size() );
    datos.put( "hop_count", jm.nrJobHops() );
    datos.put( "disabled_hops", apagados );
    datos.put( "canvas_image", d.imagen != null );
    s.datos = datos;
    return s;
  }

  /** "ejecuta 2 transformaciones (a.ktr, b.ktr) y 1 job (c.kjb)", o null si no llama a nada. */
  static String frasejecuta( Rotulos r, List<Llamada> llamadas ) {
    List<String> trans = new ArrayList<String>();
    List<String> jobs = new ArrayList<String>();
    for ( Llamada l : llamadas ) {
      String n = l.base == null ? l.entrada : l.base;
      List<String> destino = l.job ? jobs : trans;
      if ( !destino.contains( n ) ) {
        destino.add( n );
      }
    }
    List<String> partes = new ArrayList<String>();
    if ( !trans.isEmpty() ) {
      partes.add( r.f( trans.size() == 1 ? "ejecuta.trans.1" : "ejecuta.trans.n", trans.size(), unir( trans ) ) );
    }
    if ( !jobs.isEmpty() ) {
      partes.add( r.f( jobs.size() == 1 ? "ejecuta.job.1" : "ejecuta.job.n", jobs.size(), unir( jobs ) ) );
    }
    return partes.isEmpty() ? null : Txt.lista( r, partes );
  }

  // ---------------------------------------------------------------- comunes

  /** Copia de la imagen del lienzo de la captura (sin duplicar los bytes), o null si no hay. */
  private static Imagen lienzo( Captura c, String id, String alt ) {
    if ( c.lienzoCrudo != null ) {
      InformeServicio.codificarLienzo( c ); // por si el recolector corre fuera de armar (pruebas)
    }
    Imagen l = c.lienzo;
    if ( l == null || l.datos == null ) {
      return null;
    }
    Imagen i = new Imagen( l.mime, l.datos, l.ancho, l.alto, l.escala );
    i.id = id;
    i.alt = alt;
    return i;
  }

  /**
   * Tipo de la entrada en el idioma del informe: «Inicio (SPECIAL)» y «Dummy (SPECIAL)» para las
   * especiales (Kettle las llama «Special entries»); el resto, el nombre del plugin y su id.
   */
  static String tipoEntrada( JobEntryCopy e, String plugin, Rotulos r, String idioma ) {
    if ( e.isStart() ) {
      return r.s( "entrada.tipo.start" ) + ( plugin == null ? "" : " (" + plugin + ")" );
    }
    if ( e.isDummy() ) {
      return r.s( "entrada.tipo.dummy" ) + ( plugin == null ? "" : " (" + plugin + ")" );
    }
    return NombresPlugin.conId( JobEntryPluginType.class, plugin, idioma );
  }

  /**
   * «Que hace» propio de las entradas que no tienen configuracion para describir (Dummy, Success, Abort,
   * Write to log): Kettle solo da su descripcion, en el idioma de Spoon («Success», «Abort the job»).
   * null = la de ReportMotor (START, transformaciones, jobs, SQL) o la descripcion del tipo.
   */
  static String queHacePropio( JobEntryCopy c, Rotulos r ) {
    JobEntryInterface e = c.getEntry();
    if ( e == null || c.isStart() ) {
      return null;
    }
    if ( c.isDummy() ) {
      return r.s( "entrada.hace.dummy" );
    }
    try {
      if ( e instanceof JobEntrySuccess ) {
        return r.s( "entrada.hace.success" );
      }
      if ( e instanceof JobEntryWriteToLog ) {
        String m = Txt.vacio( ( (JobEntryWriteToLog) e ).getLogMessage() );
        return m == null ? null : r.f( "entrada.hace.log", Txt.recortar( m.replaceAll( "\\s+", " " ), 200 ) );
      }
      if ( "ABORT".equalsIgnoreCase( e.getPluginId() ) ) {
        // JobEntryAbort vive en pdi-core-plugins (otro loader): el mensaje se lee por reflexion
        String m = null;
        try {
          Object v = e.getClass().getMethod( "getMessageabort" ).invoke( e );
          m = v == null ? null : Txt.vacio( v.toString() );
        } catch ( ReflectiveOperationException | RuntimeException ignorada ) {
          m = null;
        }
        return m == null ? r.s( "entrada.hace.abort" ) : r.f( "entrada.hace.abort_msg", Txt.recortar( m, 200 ) );
      }
    } catch ( Throwable ignorada ) {
      // configuracion rara: la descripcion del tipo
    }
    return null;
  }

  private static String unir( List<String> l ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      sb.append( sb.length() == 0 ? "" : ", " ).append( s );
    }
    return sb.toString();
  }

  /** Una transformacion o job que ejecuta una entrada del job. */
  static final class Llamada {
    String entrada;
    boolean job;
    /** Como esta configurado (con variables). */
    String crudo;
    /** Ruta para mostrar: resuelta y relativa a la carpeta del job si se puede. */
    String mostrar;
    /** Nombre del archivo ("01_x.ktr"). */
    String base;
    /** null = no se pudo saber (variables sin resolver, repositorio). */
    Boolean existe;
  }

  /** Las entradas Transformation y Job del job, con el archivo resuelto con las variables internas. */
  static List<Llamada> llamadas( JobMeta jm, String archivoDoc ) {
    List<Llamada> out = new ArrayList<Llamada>();
    VariableSpace vs = Txt.variables( jm );
    for ( JobEntryCopy c : ReportMotor.ordenJob( jm ) ) {
      JobEntryInterface e = c.getEntry();
      String crudo;
      boolean esJob;
      if ( e instanceof JobEntryTrans ) {
        JobEntryTrans t = (JobEntryTrans) e;
        crudo = Txt.vacio( t.getFilename() ) != null ? t.getFilename() : unirRepo( t.getDirectory(),
          t.getTransname() );
        esJob = false;
      } else if ( e instanceof JobEntryJob ) {
        JobEntryJob j = (JobEntryJob) e;
        crudo = Txt.vacio( j.getFilename() ) != null ? j.getFilename() : unirRepo( j.getDirectory(), j.getJobName() );
        esJob = true;
      } else {
        continue;
      }
      Llamada l = new Llamada();
      l.entrada = c.getName();
      l.job = esJob;
      l.crudo = crudo;
      String resuelto = crudo == null ? null : vs.environmentSubstitute( crudo );
      l.mostrar = resuelto == null ? null : Txt.relativa( resuelto, archivoDoc );
      l.base = Txt.baseNombre( resuelto );
      if ( resuelto != null && !resuelto.contains( "${" ) && !resuelto.contains( "%%" ) ) {
        String local = Txt.local( resuelto );
        if ( local != null && !local.contains( "://" ) ) {
          l.existe = new File( local ).isFile();
        }
      }
      out.add( l );
    }
    return out;
  }

  private static String unirRepo( String dir, String nombre ) {
    if ( Txt.vacio( nombre ) == null ) {
      return null;
    }
    return ( dir == null ? "" : dir.endsWith( "/" ) ? dir : dir + "/" ) + nombre;
  }
}
