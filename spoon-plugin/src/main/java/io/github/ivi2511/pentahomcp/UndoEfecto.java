package io.github.ivi2511.pentahomcp;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

/**
 * Ctrl+Z para lo que el undo de Spoon no conoce: los parámetros del documento y su descripción. Spoon 9.4 solo deshace
 * steps, hops, notas, conexiones y entradas; pero al deshacer o rehacer un cambio de un step (o de una entrada de job)
 * llama a {@code clone()} sobre el objeto guardado en el historial (SpoonTransformationDelegate.undoTransformationAction
 * y redoTransformationAction: {@code getStep(i).replaceMeta((StepMeta) previous[i].clone())}; lo mismo
 * SpoonJobDelegate con JobEntryCopy, leído del bytecode de PDI 9.4). Así que se registra un cambio «de mentira» sobre
 * un step (el mismo antes y después) cuyos objetos guardados, al clonarse, dejan los parámetros y la descripción como
 * en su foto: el antes al deshacer, el después al rehacer. Va dentro del mismo {@link UndoGrupo}: el mismo Ctrl+Z que
 * el resto de la llamada.
 * <p>
 * El orden en el grupo es el del tiempo: deshacer va de atrás para adelante, así que cuando le toca a esta acción los
 * índices y el step portador están como cuando se registró. (UI, o el hilo de una copia en las pruebas)
 */
final class UndoEfecto {

  private UndoEfecto() {
  }

  /** Los parámetros (nombre → {default, descripción}, en orden) y la descripción de un documento. */
  static final class Foto {
    final Map<String, String[]> parametros = new LinkedHashMap<String, String[]>();
    String descripcion;

    static Foto de( AbstractMeta m ) {
      Foto f = new Foto();
      for ( String p : m.listParameters() ) {
        String d;
        String x;
        try {
          d = m.getParameterDefault( p );
          x = m.getParameterDescription( p );
        } catch ( Exception e ) {
          d = "";
          x = "";
        }
        f.parametros.put( p, new String[] { d == null ? "" : d, x == null ? "" : x } );
      }
      f.descripcion = m.getDescription();
      return f;
    }

    /** Deja el documento como en la foto (los parámetros que sobran se borran; los que faltan se declaran). */
    void poner( AbstractMeta m ) {
      if ( !igualParametros( de( m ) ) ) {
        m.eraseParameters();
        for ( Map.Entry<String, String[]> e : parametros.entrySet() ) {
          try {
            m.addParameterDefinition( e.getKey(), e.getValue()[ 0 ], e.getValue()[ 1 ] );
          } catch ( Exception x ) {
            // un nombre repetido no puede venir de una foto
          }
        }
        m.activateParameters();
      }
      if ( !Objects.equals( descripcion, m.getDescription() ) ) {
        m.setDescription( descripcion );
      }
      m.setChanged();
    }

    boolean igualParametros( Foto o ) {
      if ( !parametros.keySet().equals( o.parametros.keySet() ) ) {
        return false;
      }
      for ( Map.Entry<String, String[]> e : parametros.entrySet() ) {
        if ( !Arrays.equals( e.getValue(), o.parametros.get( e.getKey() ) ) ) {
          return false;
        }
      }
      return true;
    }

    boolean igual( Foto o ) {
      return igualParametros( o ) && Objects.equals( descripcion, o.descripcion );
    }
  }

  /** Un step del historial de undo que, cuando Spoon lo clona para deshacer o rehacer, pone su foto en el documento. */
  static final class PasoConFoto extends StepMeta {
    final transient AbstractMeta meta;
    final transient Foto foto;

    PasoConFoto( StepMeta base, AbstractMeta meta, Foto foto ) {
      super();
      replaceMeta( base );
      setStepErrorMeta( base.getStepErrorMeta() );
      this.meta = meta;
      this.foto = foto;
    }

    @Override
    public Object clone() {
      try {
        foto.poner( meta );
      } catch ( RuntimeException e ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not restore the parameters on undo: " + e );
      }
      return super.clone(); // un StepMeta común (StepMeta.clone hace new StepMeta())
    }
  }

  /** Lo mismo para una entrada de job. */
  static final class EntradaConFoto extends JobEntryCopy {
    final transient AbstractMeta meta;
    final transient Foto foto;

    EntradaConFoto( JobEntryCopy base, AbstractMeta meta, Foto foto ) {
      super();
      replaceMeta( base );
      this.meta = meta;
      this.foto = foto;
    }

    @Override
    public Object clone() {
      try {
        foto.poner( meta );
      } catch ( RuntimeException e ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not restore the parameters on undo: " + e );
      }
      return super.clone();
    }
  }

  /**
   * Registra en el grupo el paso de la foto antes a la actual del documento (que ya tiene el cambio hecho). Devuelve
   * false si no hay dónde colgarlo (un documento sin steps ni entradas): entonces no se deshace con Ctrl+Z.
   */
  static boolean registrar( UndoGrupo undo, AbstractMeta m, Foto antes ) {
    Foto despues = Foto.de( m );
    if ( antes.igual( despues ) ) {
      return true; // nada que deshacer
    }
    if ( m instanceof TransMeta ) {
      TransMeta tm = (TransMeta) m;
      if ( tm.nrSteps() == 0 ) {
        return false;
      }
      StepMeta s = tm.getStep( 0 );
      undo.cambio( new Object[] { new PasoConFoto( RobustecerAplicar.instantanea( s ), m, antes ) },
        new Object[] { new PasoConFoto( RobustecerAplicar.instantanea( s ), m, despues ) }, new int[] { 0 } );
      return true;
    }
    if ( m instanceof JobMeta ) {
      JobMeta jm = (JobMeta) m;
      if ( jm.nrJobEntries() == 0 ) {
        return false;
      }
      JobEntryCopy c = jm.getJobEntry( 0 );
      undo.cambio( new Object[] { new EntradaConFoto( (JobEntryCopy) c.clone(), m, antes ) },
        new Object[] { new EntradaConFoto( (JobEntryCopy) c.clone(), m, despues ) }, new int[] { 0 } );
      return true;
    }
    return false;
  }
}
