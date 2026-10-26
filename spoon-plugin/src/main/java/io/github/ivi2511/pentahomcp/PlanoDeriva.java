package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.Plano.Destino;
import io.github.ivi2511.pentahomcp.Plano.Etapa;
import io.github.ivi2511.pentahomcp.Plano.Fuente;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasBoceto;

/**
 * plan_check (D1): la deriva entre lo planificado y lo construido. Solo avisa (warning / info), nunca bloquea ni
 * cambia nada: «el plano dice que los huérfanos se rechazan y no hay rama de error». Compara cada transformación del
 * plano con su .ktr (abierto en Spoon o en disco) y la orquestación con su .kjb. Sin Spoon: se prueba con TransMeta y
 * JobMeta sueltos.
 */
final class PlanoDeriva {

  static final String AVISO = "warning";
  static final String INFO = "info";

  private PlanoDeriva() {
  }

  /** Un hallazgo de la deriva. */
  static final class Hallazgo {
    final String codigo;
    final String severidad;
    final String transformacion;
    final String etapa;
    final String mensaje;

    Hallazgo( String codigo, String severidad, String transformacion, String etapa, String mensaje ) {
      this.codigo = codigo;
      this.severidad = severidad;
      this.transformacion = transformacion;
      this.etapa = etapa;
      this.mensaje = mensaje;
    }
  }

  /** Lo que se encontró de una transformación del plano: dónde está y cómo va. */
  static final class Estado {
    String transformacion;
    /** open | file | missing. */
    String donde = "missing";
    String ref;
    int etapas;
    int construidas;
    int marcadores;
    int sinStep;
  }

  /** El resultado completo. */
  static final class Resultado {
    final List<Hallazgo> hallazgos = new ArrayList<Hallazgo>();
    final List<Estado> estados = new ArrayList<Estado>();
    String job;

    ObjectNode json() {
      ObjectNode o = Tools.obj();
      int w = 0;
      int i = 0;
      ArrayNode a = Tools.arr();
      for ( Hallazgo h : hallazgos ) {
        if ( AVISO.equals( h.severidad ) ) {
          w++;
        } else {
          i++;
        }
        ObjectNode x = a.addObject();
        x.put( "code", h.codigo );
        x.put( "severity", h.severidad );
        if ( h.transformacion != null ) {
          x.put( "transformation", h.transformacion );
        }
        if ( h.etapa != null ) {
          x.put( "stage", h.etapa );
        }
        x.put( "message", h.mensaje );
      }
      o.put( "warnings", w );
      o.put( "infos", i );
      o.put( "drift", w > 0 );
      ArrayNode ts = o.putArray( "transformations" );
      for ( Estado e : estados ) {
        ObjectNode x = ts.addObject();
        x.put( "name", PlanoConstruir.nombreGrupo( e.transformacion ) );
        x.put( "found", e.donde );
        if ( e.ref != null ) {
          x.put( "document", e.ref );
        }
        x.put( "stages", e.etapas );
        x.put( "built", e.construidas );
        x.put( "placeholders", e.marcadores );
        x.put( "without_step", e.sinStep );
      }
      if ( job != null ) {
        o.put( "job", job );
      }
      o.set( "findings", a );
      return o;
    }
  }

  /**
   * Compara. docs = transformación del plano → {TransMeta, "open"|"file", ref} (sin entrada = no se encontró); job =
   * el .kjb de la orquestación o null.
   */
  static Resultado revisar( Plano p, Map<String, Object[]> docs, Object[] job, String idioma ) {
    Rotulos r = Rotulos.de( PlanoInforme.AREA, PlanoInforme.idioma( p, idioma ) );
    Resultado res = new Resultado();
    for ( String g : p.transformaciones() ) {
      Estado est = new Estado();
      est.transformacion = g;
      List<Etapa> etapas = p.etapasDe( g );
      est.etapas = etapas.size();
      res.estados.add( est );
      Object[] d = docs.get( g );
      String gn = PlanoConstruir.nombreGrupo( g );
      if ( d == null || d[ 0 ] == null ) {
        res.hallazgos.add( new Hallazgo( "transformation_not_built", INFO, gn, null, r.f( "der.no_construida", gn ) ) );
        continue;
      }
      TransMeta tm = (TransMeta) d[ 0 ];
      est.donde = (String) d[ 1 ];
      est.ref = (String) d[ 2 ];
      revisarTrans( p, g, tm, etapas, est, res, r );
    }
    revisarJob( p, job, res, r );
    return res;
  }

  private static void revisarTrans( Plano p, String g, TransMeta tm, List<Etapa> etapas, Estado est, Resultado res,
                                    Rotulos r ) {
    String gn = PlanoConstruir.nombreGrupo( g );
    Map<String, String> reg = ReglasBoceto.registro( tm );
    Map<String, StepMeta> deEtapa = new LinkedHashMap<String, StepMeta>();
    Map<String, Boolean> esMarcador = new LinkedHashMap<String, Boolean>();
    for ( Etapa e : etapas ) {
      StepMeta s = buscar( tm, e, reg );
      if ( s == null ) {
        est.sinStep++;
        res.hallazgos.add( new Hallazgo( "stage_without_step", AVISO, gn, e.id, r.f( "der.sin_step", e.nombre ) ) );
        continue;
      }
      deEtapa.put( e.id, s );
      boolean m = ReglasBoceto.etapaDeMarcador( tm, s, reg ) != null;
      esMarcador.put( e.id, m );
      if ( m ) {
        est.marcadores++;
        res.hallazgos.add( new Hallazgo( "placeholder_left", INFO, gn, e.id, r.f( "der.marcador", e.nombre ) ) );
        continue;
      }
      est.construidas++;
      if ( e.tipoStep != null && !e.tipoStep.equalsIgnoreCase( s.getStepID() ) ) {
        res.hallazgos.add( new Hallazgo( "step_type_differs", INFO, gn, e.id, r.f( "der.tipo", e.nombre, e.tipoStep,
          s.getStepID() ) ) );
      }
    }
    String todo = textoDe( tm );
    boolean hayHopError = false;
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      hayHopError |= h.isErrorHop() && h.isEnabled();
    }
    // fuentes y destinos de esta transformacion: si su etapa ya esta construida, algun step los tiene que nombrar
    for ( Etapa e : etapas ) {
      if ( !Boolean.FALSE.equals( esMarcador.get( e.id ) ) ) {
        continue; // sin step o todavia marcador: ya se aviso
      }
      for ( String id : e.lee ) {
        Fuente f = p.fuente( id );
        String clave = f == null ? null : claveRecurso( f.ubicacion, f.nombre );
        if ( clave != null && !todo.contains( clave ) ) {
          res.hallazgos.add( new Hallazgo( "source_not_referenced", AVISO, gn, e.id, r.f( "der.fuente", f.nombre,
            e.nombre ) ) );
        }
      }
      for ( String id : e.escribe ) {
        Destino d = p.destino( id );
        String clave = d == null ? null : claveRecurso( null, d.nombre );
        if ( clave != null && !todo.contains( clave ) ) {
          res.hallazgos.add( new Hallazgo( "target_not_written", AVISO, gn, e.id, r.f( "der.destino", d.nombre,
            e.nombre ) ) );
        } else if ( d != null ) {
          String carga = cargaQueFalta( tm, d );
          if ( carga != null ) {
            res.hallazgos.add( new Hallazgo( "load_differs", AVISO, gn, e.id, r.f( "der.carga", d.nombre, r.s(
              "carga_destino." + d.carga ), carga ) ) );
          }
        }
      }
      if ( "reject".equals( e.siFalta ) && !hayHopError && !rechazoConstruido( p, e, deEtapa, esMarcador )
        && !rechazoPendiente( p, e, esMarcador ) ) {
        res.hallazgos.add( new Hallazgo( "orphans_not_rejected", AVISO, gn, e.id, r.f( "der.huerfanos", e.nombre ) ) );
      }
    }
    // el plan de rechazos de las fuentes que lee esta transformacion (si su etapa de rechazos sigue pendiente, ya
    // se avisó como marcador)
    boolean rechazoPendiente = false;
    for ( Etapa e : etapas ) {
      rechazoPendiente |= "reject".equals( e.clase ) && !Boolean.FALSE.equals( esMarcador.get( e.id ) );
    }
    for ( Fuente f : p.fuentes ) {
      if ( rechazoPendiente || PlanoRevision.vacio( f.rechazos ) || !leeLaFuente( etapas, f, esMarcador ) ) {
        continue;
      }
      String clave = claveRecurso( f.rechazos, null );
      if ( !hayHopError && ( clave == null || !todo.contains( clave ) ) ) {
        res.hallazgos.add( new Hallazgo( "rejects_missing", AVISO, gn, null, r.f( "der.rechazos", f.nombre,
          f.rechazos ) ) );
      }
    }
    // steps que el plano no conoce (los de una cadena que reemplazó a un marcador sí los conoce)
    List<StepMeta> conocidos = new ArrayList<StepMeta>( deEtapa.values() );
    for ( Etapa e : etapas ) {
      conocidos.addAll( enlazados( tm, e ) );
    }
    List<String> extra = new ArrayList<String>();
    for ( StepMeta s : tm.getSteps() ) {
      if ( !conocidos.contains( s ) ) {
        extra.add( s.getName() );
      }
    }
    if ( !extra.isEmpty() && !etapas.isEmpty() ) {
      res.hallazgos.add( new Hallazgo( "steps_not_in_plan", INFO, gn, null, r.f( "der.extra", extra.size(),
        String.join( ", ", extra.subList( 0, Math.min( 8, extra.size() ) ) ) + ( extra.size() > 8 ? ", …" : "" ) ) ) );
    }
  }

  private static void revisarJob( Plano p, Object[] job, Resultado res, Rotulos r ) {
    if ( p.orquestacion == null || p.orquestacion.nombre == null ) {
      return;
    }
    String nj = p.orquestacion.nombre;
    if ( job == null || job[ 0 ] == null ) {
      res.hallazgos.add( new Hallazgo( "job_not_built", INFO, null, null, r.f( "der.job_falta", nj ) ) );
      return;
    }
    JobMeta jm = (JobMeta) job[ 0 ];
    res.job = (String) job[ 2 ];
    List<String> enOrden = ordenJob( jm );
    List<String> ts = new ArrayList<String>();
    for ( String g : p.transformaciones() ) {
      if ( !g.isEmpty() ) {
        ts.add( g );
      }
    }
    List<Integer> posiciones = new ArrayList<Integer>();
    for ( String g : ts ) {
      int k = indice( enOrden, g );
      if ( k < 0 ) {
        res.hallazgos.add( new Hallazgo( "transformation_not_in_job", AVISO, g, null, r.f( "der.no_en_job", g, nj ) ) );
      } else {
        posiciones.add( k );
      }
    }
    for ( int i = 1; i < posiciones.size(); i++ ) {
      if ( posiciones.get( i ) < posiciones.get( i - 1 ) ) {
        res.hallazgos.add( new Hallazgo( "job_order_differs", INFO, null, null, r.f( "der.orden", nj ) ) );
        break;
      }
    }
    String siFalla = p.orquestacion.siFalla == null ? "abort" : p.orquestacion.siFalla;
    if ( !"continue".equals( siFalla ) ) {
      boolean falla = false;
      for ( int i = 0; i < jm.nrJobHops(); i++ ) {
        JobHopMeta h = jm.getJobHop( i );
        falla |= h.isEnabled() && !h.isUnconditional() && !h.getEvaluation();
      }
      if ( !falla ) {
        res.hallazgos.add( new Hallazgo( "job_without_failure_path", AVISO, null, null, r.f( "der.sin_falla", nj ) ) );
      }
    }
    // marcadores del boceto del job (plan_build job=true mode=sketch)
    List<String> marcadores = new ArrayList<String>();
    boolean compruebaArchivos = false;
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      if ( PlanoJob.esMarcador( c ) ) {
        marcadores.add( c.getName() );
        compruebaArchivos |= "FILES_EXIST".equals( c.getAttribute( ReglasBoceto.GRUPO, PlanoJob.K_TIPO ) );
      } else if ( c.getEntry() != null ) {
        String id = c.getEntry().getPluginId();
        compruebaArchivos |= "FILES_EXIST".equals( id ) || "FILE_EXISTS".equals( id );
      }
    }
    if ( !marcadores.isEmpty() ) {
      res.hallazgos.add( new Hallazgo( "job_placeholder_left", INFO, null, null, r.f( "der.job_marcadores", nj,
        marcadores.size(), String.join( ", ", marcadores ) ) ) );
    }
    List<String> archivos = new ArrayList<String>();
    for ( Fuente f : p.fuentes ) {
      if ( f.ubicacion != null && f.formato != null && f.formato.matches( "csv|text|excel|json|xml" ) ) {
        archivos.add( f.nombre );
      }
    }
    if ( !archivos.isEmpty() && !compruebaArchivos ) {
      res.hallazgos.add( new Hallazgo( "job_without_input_check", INFO, null, null, r.f( "der.job_sin_comprobar",
        String.join( ", ", archivos ), nj ) ) );
    }
  }

  /** Las transformaciones que corre el job (nombre de archivo sin .ktr, en minúsculas), en el orden de los hops. */
  static List<String> ordenJob( JobMeta jm ) {
    List<String> out = new ArrayList<String>();
    JobEntryCopy ini = null;
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      if ( c.isStart() ) {
        ini = c;
      }
    }
    List<JobEntryCopy> cola = new ArrayList<JobEntryCopy>();
    List<JobEntryCopy> vistas = new ArrayList<JobEntryCopy>();
    if ( ini != null ) {
      cola.add( ini );
    } else {
      cola.addAll( jm.getJobCopies() );
    }
    while ( !cola.isEmpty() ) {
      JobEntryCopy c = cola.remove( 0 );
      if ( vistas.contains( c ) ) {
        continue;
      }
      vistas.add( c );
      String t = transDe( c );
      if ( t != null ) {
        out.add( t );
      }
      for ( int i = 0; i < jm.nrJobHops(); i++ ) {
        JobHopMeta h = jm.getJobHop( i );
        if ( h.getFromEntry() == c && h.isEnabled() && ( h.isUnconditional() || h.getEvaluation() ) ) {
          cola.add( h.getToEntry() );
        }
      }
    }
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      String t = transDe( c );
      if ( t != null && !out.contains( t ) ) {
        out.add( t );
      }
    }
    return out;
  }

  private static String transDe( JobEntryCopy c ) {
    if ( PlanoJob.esMarcador( c ) && "TRANS".equals( c.getAttribute( ReglasBoceto.GRUPO, PlanoJob.K_TIPO ) ) ) {
      // un marcador del boceto del job que va a ser un TRANS: cuenta para el orden (ya se avisa que es marcador)
      String f = valor( String.valueOf( c.getAttribute( ReglasBoceto.GRUPO, PlanoJob.K_CONFIG ) ), "filename" );
      return f == null || f.isEmpty() ? null : Plano.sinKtr( f ).toLowerCase( Locale.ROOT );
    }
    if ( c.getEntry() == null || !"TRANS".equals( c.getEntry().getPluginId() ) ) {
      return null;
    }
    try {
      String xml = c.getEntry().getXML();
      String f = valor( xml, "filename" );
      if ( f == null || f.isEmpty() ) {
        f = valor( xml, "transname" );
      }
      if ( f == null || f.isEmpty() ) {
        f = c.getName();
      }
      return Plano.sinKtr( f ).toLowerCase( Locale.ROOT );
    } catch ( Exception e ) {
      return c.getName().toLowerCase( Locale.ROOT );
    }
  }

  private static String valor( String xml, String tag ) {
    int a = xml.indexOf( "<" + tag + ">" );
    int b = a < 0 ? -1 : xml.indexOf( "</" + tag + ">", a );
    return a < 0 || b < 0 ? null : xml.substring( a + tag.length() + 2, b ).trim();
  }

  private static int indice( List<String> l, String g ) {
    String x = g.toLowerCase( Locale.ROOT );
    for ( int i = 0; i < l.size(); i++ ) {
      String t = l.get( i );
      // "${Internal.Entry.Current.Directory}/04_hechos_ventas" -> termina en el nombre
      if ( t.equals( x ) || t.endsWith( "/" + x ) || t.endsWith( "}" + x ) ) {
        return i;
      }
    }
    return -1;
  }

  /**
   * El step de la etapa: entre los enlazados a ella (la cadena que reemplazó al marcador, o el marcador), el que se
   * llama como la etapa, si no el del tipo pedido, si no el primero; sin enlace, uno con su nombre.
   */
  static StepMeta buscar( TransMeta tm, Etapa e, Map<String, String> reg ) {
    List<StepMeta> l = enlazados( tm, e );
    for ( StepMeta s : l ) {
      if ( s.getName().equalsIgnoreCase( e.nombre ) ) {
        return s;
      }
    }
    for ( StepMeta s : l ) {
      if ( e.tipoStep != null && e.tipoStep.equalsIgnoreCase( s.getStepID() ) ) {
        return s;
      }
    }
    return l.isEmpty() ? tm.findStep( e.nombre ) : l.get( 0 );
  }

  /** Los steps enlazados a la etapa por los atributos del boceto. */
  static List<StepMeta> enlazados( TransMeta tm, Etapa e ) {
    List<StepMeta> l = new ArrayList<StepMeta>();
    for ( StepMeta s : tm.getSteps() ) {
      if ( e.id.equals( s.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_ETAPA ) ) || e.id.equals( s.getAttribute(
        ReglasBoceto.GRUPO, ReglasBoceto.K_MARCADOR ) ) ) {
        l.add( s );
      }
    }
    return l;
  }

  /** Todo el XML de los steps en minúsculas (para buscar si alguno nombra un archivo o una tabla). */
  static String textoDe( TransMeta tm ) {
    StringBuilder sb = new StringBuilder();
    for ( StepMeta s : tm.getSteps() ) {
      try {
        sb.append( s.getXML() ).append( '\n' );
      } catch ( Exception ignored ) {
        sb.append( s.getName() ).append( '\n' );
      }
    }
    return sb.toString().toLowerCase( Locale.ROOT );
  }

  /** "datos/ventas.csv" → "ventas.csv"; sin ubicación, el nombre (si parece un archivo o una tabla). */
  static String claveRecurso( String ubicacion, String nombre ) {
    String u = ubicacion != null && !ubicacion.trim().isEmpty() ? ubicacion : nombre;
    if ( u == null || u.trim().isEmpty() ) {
      return null;
    }
    String x = u.trim().replace( '\\', '/' );
    x = x.substring( x.lastIndexOf( '/' ) + 1 );
    int punto = x.lastIndexOf( " · " );
    if ( punto >= 0 ) {
      x = x.substring( punto + 3 );
    }
    if ( x.contains( " " ) ) {
      return null; // un nombre con espacios no es un archivo ni una tabla: no se puede buscar
    }
    return x.toLowerCase( Locale.ROOT );
  }

  /** Lo que hace la carga del destino si no es lo que dice el plano ("TableOutput sin truncar"), o null. */
  static String cargaQueFalta( TransMeta tm, Destino d ) {
    if ( d.carga == null || "insert".equals( d.carga ) || "append".equals( d.carga ) || "file".equals( d.clase ) ) {
      return null;
    }
    String tabla = d.nombre.toLowerCase( Locale.ROOT );
    List<String> tipos = new ArrayList<String>();
    boolean trunca = false;
    for ( StepMeta s : tm.getSteps() ) {
      String x;
      try {
        x = s.getXML().toLowerCase( Locale.ROOT );
      } catch ( Exception e ) {
        continue;
      }
      if ( !x.contains( ">" + tabla + "<" ) ) {
        continue;
      }
      tipos.add( s.getStepID() );
      if ( "TableOutput".equals( s.getStepID() ) && x.contains( "<truncate>y</truncate>" ) ) {
        trunca = true;
      }
    }
    if ( tipos.isEmpty() ) {
      return null;
    }
    switch ( d.carga ) {
      case "truncate_insert":
        return trunca || tipos.contains( "InsertUpdate" ) || tipos.contains( "Update" ) ? null : String.join( ", ",
          tipos ) + " (no truncate)";
      case "upsert":
        return tipos.contains( "InsertUpdate" ) || tipos.contains( "Update" ) || tipos.contains( "SynchronizeAfterMerge" )
          ? null : String.join( ", ", tipos );
      case "scd1":
      case "scd2":
        return tipos.contains( "DimensionLookup" ) || tipos.contains( "CombinationLookup" ) || tipos.contains(
          "InsertUpdate" ) && "scd1".equals( d.carga ) || trunca && "scd1".equals( d.carga ) ? null : String.join(
            ", ", tipos );
      default:
        return null;
    }
  }

  /** Hay una etapa de rechazos construida después de e. */
  private static boolean rechazoConstruido( Plano p, Etapa e, Map<String, StepMeta> deEtapa,
                                            Map<String, Boolean> esMarcador ) {
    List<Etapa> cola = new ArrayList<Etapa>( PlanoRevision.siguientes( p, e ) );
    List<String> vistos = new ArrayList<String>();
    while ( !cola.isEmpty() ) {
      Etapa x = cola.remove( 0 );
      if ( vistos.contains( x.id ) ) {
        continue;
      }
      vistos.add( x.id );
      if ( "reject".equals( x.clase ) && deEtapa.containsKey( x.id ) && Boolean.FALSE.equals( esMarcador.get(
        x.id ) ) ) {
        return true;
      }
      cola.addAll( PlanoRevision.siguientes( p, x ) );
    }
    return false;
  }

  /** Hay una etapa de rechazos después de e que todavía no está construida (ya se avisa como marcador). */
  private static boolean rechazoPendiente( Plano p, Etapa e, Map<String, Boolean> esMarcador ) {
    List<Etapa> cola = new ArrayList<Etapa>( PlanoRevision.siguientes( p, e ) );
    List<String> vistos = new ArrayList<String>();
    while ( !cola.isEmpty() ) {
      Etapa x = cola.remove( 0 );
      if ( vistos.contains( x.id ) ) {
        continue;
      }
      vistos.add( x.id );
      if ( "reject".equals( x.clase ) && !Boolean.FALSE.equals( esMarcador.get( x.id ) ) ) {
        return true;
      }
      cola.addAll( PlanoRevision.siguientes( p, x ) );
    }
    return false;
  }

  private static boolean leeLaFuente( List<Etapa> etapas, Fuente f, Map<String, Boolean> esMarcador ) {
    for ( Etapa e : etapas ) {
      if ( e.lee.contains( f.id ) && Boolean.FALSE.equals( esMarcador.get( e.id ) ) ) {
        return true;
      }
    }
    return false;
  }
}
