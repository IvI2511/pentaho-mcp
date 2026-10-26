package io.github.ivi2511.pentahomcp;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.database.DatabaseMeta;
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

  /**
   * Los parámetros (nombre → {default, descripción}, en orden) y la descripción de un documento; desde 0.7.1 también la
   * descripción de cada step o entrada y el host, el puerto y la base de cada conexión (los arreglos de
   * step_without_description y required_variable), que Spoon no deshace en un job.
   */
  static final class Foto {
    final Map<String, String[]> parametros = new LinkedHashMap<String, String[]>();
    String descripcion;
    /** StepMeta o JobEntryCopy (por identidad) → su descripción. */
    final Map<Object, String> descripciones = new IdentityHashMap<Object, String>();
    /** DatabaseMeta (por identidad) → {host, puerto, base}. */
    final Map<DatabaseMeta, String[]> conexiones = new IdentityHashMap<DatabaseMeta, String[]>();

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
      if ( m instanceof TransMeta ) {
        for ( StepMeta s : ( (TransMeta) m ).getSteps() ) {
          f.descripciones.put( s, s.getDescription() );
        }
      } else if ( m instanceof JobMeta ) {
        for ( JobEntryCopy c : ( (JobMeta) m ).getJobCopies() ) {
          f.descripciones.put( c, c.getDescription() );
        }
      }
      for ( DatabaseMeta db : m.getDatabases() ) {
        f.conexiones.put( db, new String[] { db.getHostname(), db.getDatabasePortNumberString(), db
          .getDatabaseName() } );
      }
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
      for ( Map.Entry<Object, String> e : descripciones.entrySet() ) {
        Object o = e.getKey();
        if ( o instanceof StepMeta && m instanceof TransMeta && ( (TransMeta) m ).indexOfStep( (StepMeta) o ) >= 0
          && !Objects.equals( e.getValue(), ( (StepMeta) o ).getDescription() ) ) {
          ( (StepMeta) o ).setDescription( e.getValue() );
        } else if ( o instanceof JobEntryCopy && m instanceof JobMeta && ( (JobMeta) m ).indexOfJobEntry(
          (JobEntryCopy) o ) >= 0 && !Objects.equals( e.getValue(), ( (JobEntryCopy) o ).getDescription() ) ) {
          ( (JobEntryCopy) o ).setDescription( e.getValue() );
        }
      }
      for ( Map.Entry<DatabaseMeta, String[]> e : conexiones.entrySet() ) {
        DatabaseMeta db = e.getKey();
        if ( m.indexOfDatabase( db ) < 0 ) {
          continue;
        }
        String[] v = e.getValue();
        if ( !Objects.equals( v[ 0 ], db.getHostname() ) || !Objects.equals( v[ 1 ], db.getDatabasePortNumberString() )
          || !Objects.equals( v[ 2 ], db.getDatabaseName() ) ) {
          db.setHostname( v[ 0 ] );
          db.setDBPort( v[ 1 ] );
          db.setDBName( v[ 2 ] );
          db.setChanged();
        }
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
      if ( !igualParametros( o ) || !Objects.equals( descripcion, o.descripcion ) ) {
        return false;
      }
      for ( Map.Entry<Object, String> e : descripciones.entrySet() ) {
        if ( o.descripciones.containsKey( e.getKey() ) && !Objects.equals( e.getValue(), o.descripciones.get( e
          .getKey() ) ) ) {
          return false;
        }
      }
      for ( Map.Entry<DatabaseMeta, String[]> e : conexiones.entrySet() ) {
        if ( o.conexiones.containsKey( e.getKey() ) && !Arrays.equals( e.getValue(), o.conexiones.get( e
          .getKey() ) ) ) {
          return false;
        }
      }
      return true;
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

    /** El step que carga la acción con la descripción de la foto (si no, el clon la pisaría al deshacer). */
    PasoConFoto con( StepMeta real ) {
      if ( foto.descripciones.containsKey( real ) ) {
        setDescription( foto.descripciones.get( real ) );
      }
      return this;
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

    EntradaConFoto con( JobEntryCopy real ) {
      if ( foto.descripciones.containsKey( real ) ) {
        setDescription( foto.descripciones.get( real ) );
      }
      return this;
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
   * Una entrada de job del historial que, cuando Spoon la clona para deshacer o rehacer, pone en su lugar las entradas y
   * las notas de su foto de posiciones. Es como un job deshace «Ordenar lienzo» de un solo Ctrl+Z: SpoonJobDelegate no
   * encadena acciones (ver UndoGrupo), así que las entradas y las notas que se movieron van en UNA acción de cambio
   * sobre la primera entrada, cuya propia posición es la de la foto.
   */
  static final class PosicionesConFoto extends JobEntryCopy {
    final transient JobMeta meta;
    final transient Object[] objetos;
    final transient org.pentaho.di.core.gui.Point[] lugares;

    PosicionesConFoto( JobEntryCopy base, JobMeta meta, Object[] objetos, org.pentaho.di.core.gui.Point[] lugares,
                       org.pentaho.di.core.gui.Point propio ) {
      super();
      replaceMeta( base );
      setLocation( propio.x, propio.y );
      this.meta = meta;
      this.objetos = objetos;
      this.lugares = lugares;
    }

    @Override
    public Object clone() {
      try {
        for ( int i = 0; i < objetos.length; i++ ) {
          org.pentaho.di.core.gui.Point p = lugares[ i ];
          if ( objetos[ i ] instanceof JobEntryCopy && meta.indexOfJobEntry( (JobEntryCopy) objetos[ i ] ) >= 0 ) {
            ( (JobEntryCopy) objetos[ i ] ).setLocation( p.x, p.y );
          } else if ( objetos[ i ] instanceof org.pentaho.di.core.NotePadMeta
            && meta.indexOfNote( (org.pentaho.di.core.NotePadMeta) objetos[ i ] ) >= 0 ) {
            ( (org.pentaho.di.core.NotePadMeta) objetos[ i ] ).setLocation( p.x, p.y );
          }
        }
      } catch ( RuntimeException e ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not restore the positions on undo: " + e );
      }
      return super.clone();
    }
  }

  /**
   * Registra como UNA acción (un Ctrl+Z en un job) el movimiento de entradas y notas: 'objetos' (entradas y notas, ya
   * movidas a 'despues') estaban en 'antes'. Cuelga de la primera entrada del job; false si el job no tiene entradas.
   */
  static boolean registrarPosiciones( UndoGrupo undo, JobMeta jm, Object[] objetos,
                                      org.pentaho.di.core.gui.Point[] antes, org.pentaho.di.core.gui.Point[] despues ) {
    if ( jm.nrJobEntries() == 0 || objetos.length == 0 ) {
      return false;
    }
    JobEntryCopy c = jm.getJobEntry( 0 );
    org.pentaho.di.core.gui.Point ahora = new org.pentaho.di.core.gui.Point( c.getLocation().x, c.getLocation().y );
    org.pentaho.di.core.gui.Point antesPropio = ahora;
    for ( int i = 0; i < objetos.length; i++ ) {
      if ( objetos[ i ] == c ) {
        antesPropio = antes[ i ];
      }
    }
    undo.cambio( new Object[] { new PosicionesConFoto( c, jm, objetos, antes, antesPropio ) },
      new Object[] { new PosicionesConFoto( c, jm, objetos, despues, ahora ) }, new int[] { 0 } );
    return true;
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
      undo.cambio( new Object[] { new PasoConFoto( RobustecerAplicar.instantanea( s ), m, antes ).con( s ) },
        new Object[] { new PasoConFoto( RobustecerAplicar.instantanea( s ), m, despues ).con( s ) }, new int[] { 0 } );
      return true;
    }
    if ( m instanceof JobMeta ) {
      JobMeta jm = (JobMeta) m;
      if ( jm.nrJobEntries() == 0 ) {
        return false;
      }
      JobEntryCopy c = jm.getJobEntry( 0 );
      undo.cambio( new Object[] { new EntradaConFoto( (JobEntryCopy) c.clone(), m, antes ).con( c ) },
        new Object[] { new EntradaConFoto( (JobEntryCopy) c.clone(), m, despues ).con( c ) }, new int[] { 0 } );
      return true;
    }
    return false;
  }
}
