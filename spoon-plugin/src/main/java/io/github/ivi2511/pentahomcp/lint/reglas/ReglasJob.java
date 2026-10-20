package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * Jobs (§2): failure_branch_ends_in_success (D10), start_not_named_start (C24), job_without_start,
 * orphan_entry, unreachable_entry y la apagada job_entry_without_failure_hop.
 */
public final class ReglasJob {

  /**
   * Entradas cuya rama roja es el «si no» (File exists → rojo → Success = exit 0 legítimo): la categoría
   * Conditions de 9.4 (por id: el nombre de la categoría depende del idioma de Spoon) y las que evalúan.
   */
  static final Set<String> CONDICIONES = new HashSet<String>( Arrays.asList( "CHECK_DB_CONNECTIONS",
    "CHECK_FILES_LOCKED", "FOLDER_IS_EMPTY", "WEBSERVICE_AVAILABLE", "FILES_EXIST", "COLUMNS_EXIST",
    "EVAL_FILES_METRICS", "EVAL_TABLE_CONTENT", "FILE_EXISTS", "SIMPLE_EVAL", "TABLE_EXISTS", "DELAY",
    "EVAL", "PING", "TELNET", "XSD_VALIDATOR", "DTD_VALIDATOR", "XML_WELL_FORMED", "WAIT_FOR_FILE",
    "WAIT_FOR_SQL", "CONNECTED_TO_REPOSITORY" ) );

  private ReglasJob() {
  }

  public static List<Regla> todas() {
    List<Regla> l = new ArrayList<Regla>();
    l.add( new RamaRojaASuccess() );
    l.add( new StartConOtroNombre() );
    l.add( new JobSinStart() );
    l.add( new EntradaHuerfana() );
    l.add( new EntradaInalcanzable() );
    l.add( new EntradaSinRamaDeError() );
    return l;
  }

  static String pid( JobEntryCopy c ) {
    return c.getEntry() == null ? "" : c.getEntry().getPluginId();
  }

  /** Por nombre+nr y no por identidad: los hops de un clon pueden apuntar a las entradas del original. */
  static String clave( JobEntryCopy c ) {
    return c.getName() + "#" + c.getNr();
  }

  static boolean roja( JobHopMeta h ) {
    return h.isEnabled() && !h.isUnconditional() && !h.getEvaluation();
  }

  /** La rama roja de un job termina en Success: Kitchen devuelve 0 aunque falló. */
  static final class RamaRojaASuccess extends ReglaBase {

    RamaRojaASuccess() {
      super( "failure_branch_ends_in_success", Sev.ERROR, Regla.CORRECTNESS );
      soloJobs();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      JobMeta jm = ctx.job();
      for ( int i = 0; i < jm.nrJobHops(); i++ ) {
        JobHopMeta h = jm.getJobHop( i );
        if ( h.getFromEntry() == null || h.getToEntry() == null || !roja( h ) || CONDICIONES.contains( pid( h.getFromEntry() ) ) ) {
          continue;
        }
        JobEntryCopy destino = h.getToEntry();
        if ( pid( destino ).equals( "SUCCESS" ) ) {
          Hallazgo x = ctx.enHop( Sev.ERROR, h.getFromEntry(), destino, "target" );
          x.message = ctx.t( "failure_branch_ends_in_success.msg", "from", h.getFromEntry().getName(), "to", destino.getName() );
          x.fix = Arreglo.conConfirmacion( Arreglo.RETARGET_HOP ).param( "to", "ABORT" ).vista( "Abort job" );
          out.add( x );
          continue;
        }
        String camino = porIncondicionales( jm, destino );
        if ( camino != null ) {
          Hallazgo x = ctx.enHop( Sev.WARNING, h.getFromEntry(), destino, "target" );
          x.message = ctx.t( "failure_branch_ends_in_success.msg.indirect", "from", h.getFromEntry().getName(),
            "to", destino.getName(), "path", camino );
          x.evidencia( "path", camino );
          out.add( x );
        }
      }
    }

    /** Si desde e se llega a un Success solo por hops incondicionales (sin pasar por Abort), el camino. */
    private static String porIncondicionales( JobMeta jm, JobEntryCopy e ) {
      Map<String, String> camino = new HashMap<String, String>();
      Deque<JobEntryCopy> cola = new ArrayDeque<JobEntryCopy>();
      cola.add( e );
      camino.put( clave( e ), e.getName() );
      while ( !cola.isEmpty() ) {
        JobEntryCopy x = cola.poll();
        for ( int i = 0; i < jm.nrJobHops(); i++ ) {
          JobHopMeta h = jm.getJobHop( i );
          if ( h.getFromEntry() == null || h.getToEntry() == null || !h.isEnabled() || !h.isUnconditional()
            || !clave( h.getFromEntry() ).equals( clave( x ) ) ) {
            continue;
          }
          JobEntryCopy y = h.getToEntry();
          if ( camino.containsKey( clave( y ) ) || pid( y ).equals( "ABORT" ) ) {
            continue;
          }
          String c = camino.get( clave( x ) ) + " -> " + y.getName();
          if ( pid( y ).equals( "SUCCESS" ) ) {
            return c;
          }
          camino.put( clave( y ), c );
          cola.add( y );
        }
      }
      return null;
    }
  }

  /** Job.execute busca findJobEntry("START", 0, false): nombre START sin importar mayúsculas, nr 0 y dibujada. */
  static final class StartConOtroNombre extends ReglaBase {

    StartConOtroNombre() {
      super( "start_not_named_start", Sev.ERROR, Regla.CORRECTNESS );
      soloJobs();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      JobMeta jm = ctx.job();
      JobEntryCopy start = jm.findStart();
      if ( start != null && jm.findJobEntry( "START", 0, false ) == null ) {
        Hallazgo h = ctx.enEntrada( Sev.ERROR, start, "name" );
        h.message = ctx.t( "start_not_named_start.msg", "entry", start.getName() );
        h.fix = Arreglo.auto( Arreglo.RENAME_ENTRY ).vista( "START" );
        out.add( h );
      }
    }
  }

  static final class JobSinStart extends ReglaBase {

    JobSinStart() {
      super( "job_without_start", Sev.ERROR, Regla.STRUCTURE );
      soloJobs();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      if ( ctx.job().findStart() == null ) {
        Hallazgo h = ctx.enDoc( Sev.ERROR, "start" );
        h.message = ctx.t( "job_without_start.msg" );
        out.add( h );
      }
    }
  }

  /** Entrada dibujada sin hops: nunca corre. Las ocultas (draw=N) de jobs viejos no cuentan. */
  static final class EntradaHuerfana extends ReglaBase {

    EntradaHuerfana() {
      super( "orphan_entry", Sev.WARNING, Regla.STRUCTURE );
      soloJobs();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      JobMeta jm = ctx.job();
      if ( jm.nrJobEntries() < 2 ) {
        return;
      }
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        if ( c.isDrawn() && !jm.isEntryUsedInHops( c ) ) {
          Hallazgo h = ctx.enEntrada( Sev.WARNING, c, "hops" );
          h.message = ctx.t( "orphan_entry.msg", "entry", c.getName() );
          out.add( h );
        }
      }
    }
  }

  /** Con hops, pero no se llega desde START por hops habilitados. */
  static final class EntradaInalcanzable extends ReglaBase {

    EntradaInalcanzable() {
      super( "unreachable_entry", Sev.WARNING, Regla.STRUCTURE );
      soloJobs();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      JobMeta jm = ctx.job();
      JobEntryCopy start = jm.findStart();
      if ( start == null ) {
        return;
      }
      Set<String> alcanzables = new HashSet<String>();
      Deque<String> cola = new ArrayDeque<String>();
      alcanzables.add( clave( start ) );
      cola.add( clave( start ) );
      while ( !cola.isEmpty() ) {
        String x = cola.poll();
        for ( int i = 0; i < jm.nrJobHops(); i++ ) {
          JobHopMeta h = jm.getJobHop( i );
          if ( h.isEnabled() && h.getFromEntry() != null && h.getToEntry() != null && x.equals( clave( h.getFromEntry() ) )
            && alcanzables.add( clave( h.getToEntry() ) ) ) {
            cola.add( clave( h.getToEntry() ) );
          }
        }
      }
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        if ( jm.isEntryUsedInHops( c ) && !alcanzables.contains( clave( c ) ) ) {
          Hallazgo h = ctx.enEntrada( Sev.WARNING, c, "hops" );
          h.message = ctx.t( "unreachable_entry.msg", "entry", c.getName() );
          out.add( h );
        }
      }
    }
  }

  /** Apagada (§2.1): sin hop rojo el job ya termina con error; sirve al modo clase y al corrector. */
  static final class EntradaSinRamaDeError extends ReglaBase {

    EntradaSinRamaDeError() {
      super( "job_entry_without_failure_hop", Sev.INFO, Regla.CORRECTNESS );
      soloJobs();
      apagadaPorDefecto();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      JobMeta jm = ctx.job();
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        String p = pid( c );
        if ( !p.equals( "TRANS" ) && !p.equals( "JOB" ) ) {
          continue;
        }
        boolean falla = false;
        for ( int i = 0; i < jm.nrJobHops(); i++ ) {
          JobHopMeta h = jm.getJobHop( i );
          if ( h.getFromEntry() != null && clave( h.getFromEntry() ).equals( clave( c ) ) && h.isEnabled()
            && ( !h.getEvaluation() || h.isUnconditional() ) ) {
            falla = true;
          }
        }
        if ( !falla ) {
          Hallazgo h = ctx.enEntrada( Sev.INFO, c, "hops" );
          h.message = ctx.t( "job_entry_without_failure_hop.msg", "entry", c.getName() );
          out.add( h );
        }
      }
    }
  }
}
