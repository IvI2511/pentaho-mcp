package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.pentaho.di.core.CheckResultInterface;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.ArchivoCrudo;
import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * Estructura del lienzo (§2): cycle (corre primero), duplicate_step_name, disabled_hop, orphan_step,
 * distribute_multiple_outputs, missing_plugin y broken_hop (los que hoy da validate), la apagada
 * db_output_without_error_handling y kettle_check (checkSteps/checkJobEntries, no resta).
 */
public final class ReglasEstructura {

  static final Set<String> SALIDAS_BD = new HashSet<String>( Arrays.asList( "TableOutput", "InsertUpdate", "Update", "Delete" ) );

  private ReglasEstructura() {
  }

  /** cycle primero: si hay ciclo, las reglas que calculan campos no corren (§3.5). */
  public static Regla ciclo() {
    return new Ciclo();
  }

  public static List<Regla> todas() {
    List<Regla> l = new ArrayList<Regla>();
    l.add( new NombreDuplicado() );
    l.add( new HopDeshabilitado() );
    l.add( new StepHuerfano() );
    l.add( new Reparto() );
    l.add( new PluginFaltante() );
    l.add( new HopRoto() );
    l.add( new SalidaBdSinErrores() );
    return l;
  }

  /** Va al final: puede conectarse a la BD. */
  public static Regla kettleCheck() {
    return new ChequeoKettle();
  }

  static final class Ciclo extends ReglaBase {

    Ciclo() {
      super( "cycle", Sev.ERROR, Regla.STRUCTURE );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      TransMeta tm = ctx.trans();
      for ( StepMeta s : tm.getSteps() ) {
        if ( tm.hasLoop( s ) ) {
          Hallazgo h = ctx.enStep( Sev.ERROR, s, "hops" );
          h.message = ctx.t( "cycle.msg", "step", s.getName() );
          out.add( h );
        }
      }
    }
  }

  /**
   * Dos steps con el mismo nombre sin importar mayúsculas: findStep usa equalsIgnoreCase y al cargar uno desaparece.
   * Se mira el archivo del disco aunque el documento tenga cambios sin guardar (el step perdido al abrirlo sigue
   * perdido: guardar lo hace definitivo) y, además, el documento en memoria (un step agregado con el mismo nombre).
   */
  static final class NombreDuplicado extends ReglaBase {

    NombreDuplicado() {
      super( "duplicate_step_name", Sev.ERROR, Regla.STRUCTURE );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      Set<String> dichos = new HashSet<String>();
      ArchivoCrudo a = ctx.crudoDelDisco();
      if ( a != null && a.esTransformacion() ) {
        Set<String> vistos = new HashSet<String>();
        for ( Element s : Nodos.hijos( a.raiz, "step" ) ) {
          String nombre = Nodos.crudo( s, "name" ); // sin recortar: "X" y "X " son distintos para Kettle
          if ( !vistos.add( nombre.toLowerCase( Locale.ROOT ) ) && dichos.add( nombre.toLowerCase( Locale.ROOT ) ) ) {
            Hallazgo h = ctx.enStep( Sev.ERROR, nombre, "name" );
            h.message = ctx.t( "duplicate_step_name.msg", "step", nombre );
            h.evidencia( "type", Nodos.txt( s, "type" ) );
            h.evidencia( "where", "file" );
            out.add( h );
          }
        }
      }
      Set<String> vistos = new HashSet<String>();
      for ( StepMeta s : ctx.trans().getSteps() ) {
        String nombre = s.getName() == null ? "" : s.getName();
        if ( !vistos.add( nombre.toLowerCase( Locale.ROOT ) ) && dichos.add( nombre.toLowerCase( Locale.ROOT ) ) ) {
          Hallazgo h = ctx.enStep( Sev.ERROR, nombre, "name" );
          h.message = ctx.t( "duplicate_step_name.msg", "step", nombre );
          h.evidencia( "type", s.getStepID() );
          h.evidencia( "where", "memory" );
          out.add( h );
        }
      }
    }
  }

  static final class HopDeshabilitado extends ReglaBase {

    HopDeshabilitado() {
      super( "disabled_hop", Sev.WARNING, Regla.STRUCTURE );
      ambos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      if ( ctx.esTrans() ) {
        for ( TransHopMeta h : ctx.trans().getTransHops() ) {
          if ( h.getFromStep() != null && h.getToStep() != null && !h.isEnabled() ) {
            Hallazgo x = ctx.enHop( Sev.WARNING, h.getFromStep(), h.getToStep(), "enabled" );
            x.message = ctx.t( "disabled_hop.msg", "from", h.getFromStep().getName(), "to", h.getToStep().getName() );
            x.fix = Arreglo.conConfirmacion( Arreglo.ENABLE_HOP );
            out.add( x );
          }
        }
      } else {
        JobMeta jm = ctx.job();
        for ( int i = 0; i < jm.nrJobHops(); i++ ) {
          JobHopMeta h = jm.getJobHop( i );
          if ( h.getFromEntry() != null && h.getToEntry() != null && !h.isEnabled() ) {
            Hallazgo x = ctx.enHop( Sev.WARNING, h.getFromEntry(), h.getToEntry(), "enabled" );
            // en un job no pasan filas: la entrada de destino no corre por ese camino
            x.message = ctx.t( "disabled_hop.msg.job", "from", h.getFromEntry().getName(), "to", h.getToEntry().getName() );
            x.fix = Arreglo.conConfirmacion( Arreglo.ENABLE_HOP );
            out.add( x );
          }
        }
      }
    }
  }

  static final class StepHuerfano extends ReglaBase {

    StepHuerfano() {
      super( "orphan_step", Sev.WARNING, Regla.STRUCTURE );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      TransMeta tm = ctx.trans();
      if ( tm.nrSteps() < 2 ) {
        return;
      }
      for ( StepMeta s : tm.getSteps() ) {
        if ( !tm.isStepUsedInTransHops( s ) ) {
          Hallazgo h = ctx.enStep( Sev.WARNING, s, "hops" );
          h.message = ctx.t( "orphan_step.msg", "step", s.getName() );
          h.fix = Arreglo.conConfirmacion( Arreglo.DELETE_STEP );
          out.add( h );
        }
      }
    }
  }

  /** 2+ salidas en modo repartir: cada una recibe una parte. I si todas son el mismo tipo (reparto de carga). */
  static final class Reparto extends ReglaBase {

    Reparto() {
      super( "distribute_multiple_outputs", Sev.WARNING, Regla.STRUCTURE );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      TransMeta tm = ctx.trans();
      for ( StepMeta s : tm.getSteps() ) {
        int salidas = ctx.entorno().salidasNormales( tm, s );
        if ( salidas < 2 || !s.isDistributes() ) {
          continue;
        }
        List<String> destinos = new ArrayList<String>();
        Set<String> tipos = new HashSet<String>();
        for ( TransHopMeta h : tm.getTransHops() ) {
          if ( h.getFromStep() == s && h.getToStep() != null && !h.isErrorHop() && h.isEnabled() ) {
            destinos.add( h.getToStep().getName() );
            tipos.add( h.getToStep().getStepID() );
          }
        }
        boolean iguales = tipos.size() == 1 && destinos.size() >= 2;
        Hallazgo h = ctx.enStep( iguales ? Sev.INFO : Sev.WARNING, s, "distribute" );
        h.message = ctx.t( iguales ? "distribute_multiple_outputs.msg.same_type" : "distribute_multiple_outputs.msg",
          "step", s.getName(), "n", salidas, "targets", ReglasOrden.unir( destinos ) );
        h.evidencia( "outputs", salidas ).evidencia( "targets", ReglasOrden.unir( destinos ) );
        h.fix = Arreglo.conConfirmacion( Arreglo.SET_OUTPUT_MODE ).vista( "copy" );
        out.add( h );
      }
    }
  }

  static final class PluginFaltante extends ReglaBase {

    PluginFaltante() {
      super( "missing_plugin", Sev.ERROR, Regla.STRUCTURE );
      ambos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      if ( ctx.esTrans() ) {
        for ( StepMeta s : ctx.trans().getSteps() ) {
          if ( s.isMissing() ) {
            Hallazgo h = ctx.enStep( Sev.ERROR, s, "type" );
            h.message = ctx.t( "missing_plugin.msg", "name", s.getName(), "type", s.getStepID() );
            out.add( h );
          }
        }
      } else {
        for ( JobEntryCopy c : ctx.job().getJobCopies() ) {
          if ( c.isMissing() ) {
            Hallazgo h = ctx.enEntrada( Sev.ERROR, c, "type" );
            h.message = ctx.t( "missing_plugin.msg", "name", c.getName(),
              "type", c.getEntry() == null ? "?" : c.getEntry().getPluginId() );
            out.add( h );
          }
        }
      }
    }
  }

  static final class HopRoto extends ReglaBase {

    HopRoto() {
      super( "broken_hop", Sev.ERROR, Regla.STRUCTURE );
      ambos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      if ( ctx.esTrans() ) {
        for ( TransHopMeta h : ctx.trans().getTransHops() ) {
          if ( h.getFromStep() == null || h.getToStep() == null ) {
            out.add( roto( ctx, h.getFromStep() == null ? "?" : h.getFromStep().getName(),
              h.getToStep() == null ? "?" : h.getToStep().getName() ) );
          }
        }
      } else {
        JobMeta jm = ctx.job();
        for ( int i = 0; i < jm.nrJobHops(); i++ ) {
          JobHopMeta h = jm.getJobHop( i );
          if ( h.getFromEntry() == null || h.getToEntry() == null ) {
            out.add( roto( ctx, h.getFromEntry() == null ? "?" : h.getFromEntry().getName(),
              h.getToEntry() == null ? "?" : h.getToEntry().getName() ) );
          }
        }
      }
    }

    private Hallazgo roto( Ctx ctx, String desde, String hasta ) {
      Hallazgo h = ctx.enObjeto( Sev.ERROR, Hallazgo.HOP, desde + " -> " + hasta, "end" );
      h.message = ctx.t( "broken_hop.msg", "from", desde, "to", hasta );
      return h;
    }
  }

  /** Apagada (§2.1): es lo que el corrector llama error_handling. */
  static final class SalidaBdSinErrores extends ReglaBase {

    SalidaBdSinErrores() {
      super( "db_output_without_error_handling", Sev.INFO, Regla.CORRECTNESS );
      apagadaPorDefecto();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( SALIDAS_BD.contains( s.getStepID() ) && s.supportsErrorHandling() && !s.isDoingErrorHandling() ) {
          Hallazgo h = ctx.enStep( Sev.INFO, s, "error_handling" );
          h.message = ctx.t( "db_output_without_error_handling.msg", "step", s.getName() );
          out.add( h );
        }
      }
    }
  }

  /** checkSteps / checkJobEntries (el «Verificar» de Spoon): no resta ni activa la compuerta (§4). */
  static final class ChequeoKettle extends ReglaBase {

    ChequeoKettle() {
      super( "kettle_check", Sev.ERROR, Regla.STRUCTURE );
      ambos();
      conCampos();
      dependeDelEntorno();
      sinPuntaje();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      List<CheckResultInterface> r = new ArrayList<CheckResultInterface>();
      if ( ctx.esTrans() ) {
        TransMeta tm = ctx.trans();
        // check() de Table input/output se conecta: los steps que tocarían una base de archivo que no existe no se
        // revisan (Kettle la crearía vacía), como en RunMotor.validar con saltear
        List<StepMeta> pasos = tm.getSteps();
        boolean[] van = new boolean[ pasos.size() ];
        int quedan = 0;
        for ( int i = 0; i < pasos.size(); i++ ) {
          String[] falta = ctx.entorno().baseFaltante( pasos.get( i ) );
          van[ i ] = falta == null;
          quedan += van[ i ] ? 1 : 0;
          if ( falta != null ) {
            ctx.noEvaluado( "fields", pasos.get( i ).getName(), null, ctx.t( "ne.check_db_missing", "connection",
              falta[ 0 ], "file", falta[ 1 ] ) );
          }
        }
        if ( quedan == pasos.size() ) {
          tm.checkSteps( r, false, null, tm, null, tm.getMetaStore() );
        } else if ( quedan > 0 ) { // con onlySelected y ninguno seleccionado, checkSteps revisa todos
          boolean[] antes = new boolean[ pasos.size() ];
          try {
            for ( int i = 0; i < pasos.size(); i++ ) {
              antes[ i ] = pasos.get( i ).isSelected();
              pasos.get( i ).setSelected( van[ i ] );
            }
            tm.checkSteps( r, true, null, tm, null, tm.getMetaStore() );
          } finally {
            for ( int i = 0; i < pasos.size(); i++ ) {
              pasos.get( i ).setSelected( antes[ i ] );
            }
          }
        }
      } else {
        JobMeta jm = ctx.job();
        jm.checkJobEntries( r, false, null, jm, null, jm.getMetaStore() );
      }
      Set<String> vistos = new HashSet<String>();
      for ( CheckResultInterface c : r ) {
        Sev sev = c.getType() == CheckResultInterface.TYPE_RESULT_ERROR ? Sev.ERROR
          : c.getType() == CheckResultInterface.TYPE_RESULT_WARNING ? Sev.WARNING : null;
        if ( sev == null ) {
          continue;
        }
        String fuente = c.getSourceInfo() == null ? null : c.getSourceInfo().getName();
        String texto = c.getText() == null ? "" : c.getText().trim().replaceAll( "\\s+", " " );
        if ( !vistos.add( fuente + "\n" + texto ) ) {
          continue; // Kettle repite el aviso por cada step que recorre el campo
        }
        Hallazgo h;
        if ( fuente == null ) {
          h = ctx.enDoc( sev, "check" );
        } else if ( ctx.esTrans() ) {
          h = ctx.enStep( sev, fuente, "check" );
        } else {
          h = ctx.enObjeto( sev, Hallazgo.ENTRY, fuente, "check" );
          h.entry = fuente;
        }
        h.message = texto;
        h.howToFix = ctx.t( "kettle_check.fix" );
        out.add( h );
      }
    }
  }
}
