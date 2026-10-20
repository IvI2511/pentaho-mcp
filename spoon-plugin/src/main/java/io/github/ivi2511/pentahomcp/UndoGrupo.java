package io.github.ivi2511.pentahomcp;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.ui.spoon.Spoon;

/**
 * Agrupa las acciones de undo de UNA llamada a una herramienta para que el usuario las deshaga con
 * un solo Ctrl+Z (y las rehaga con un solo Ctrl+Y).
 *
 * Como funciona el undo de Spoon en PDI 9.4 (leido del bytecode, no de la documentacion):
 * <ul>
 * <li>{@code AbstractMeta.addUndo(..., nextAlso)} IGNORA el ultimo parametro: arma el TransAction
 *   sin llamar a setNextAlso. Por eso {@code spoon.addUndoNew(..., true)} no encadena nada, y en
 *   todo Kettle nadie llama a {@code TransAction.setNextAlso}.</li>
 * <li>Transformaciones: {@code SpoonTransformationDelegate.undoTransformationAction} deshace la
 *   accion y, si la que acaba de deshacer tiene getNextAlso(), vuelve a llamar a
 *   {@code spoon.undoAction}, que deshace la ANTERIOR. redoTransformationAction rehace y, si la
 *   SIGUIENTE tiene getNextAlso(), la rehace tambien. O sea: la marca va en la accion que se
 *   encadena a la anterior (la segunda, la tercera...), nunca en la primera.</li>
 * <li>Ademas {@code Spoon.undoAction} tiene un caso fijo: al deshacer DELETE_STEP, si la anterior es
 *   DELETE_HOP la deshace tambien (y redoAction al reves: DELETE_HOP seguido de DELETE_STEP). Es como
 *   Spoon borra un step con sus hops. Ese chequeo corre DESPUES del encadenado, asi que si ademas
 *   marcaramos el DELETE_STEP, deshacer el grupo miraria la accion de antes del grupo y, si es un
 *   DELETE_HOP de otra operacion, se lo llevaria puesto. Por eso ese par se deja sin marca.</li>
 * <li>Jobs: {@code SpoonJobDelegate.undoJobAction/redoJobAction} no miran getNextAlso. Lo unico que
 *   encadena es el caso fijo DELETE_JOB_ENTRY + DELETE_JOB_HOP de Spoon.undoAction (borrar una
 *   entrada con sus hops). Igual se marca (no molesta y queda listo si Spoon lo respeta algun dia).
 *   Ver JobSoporte: que herramientas de jobs quedan en mas de un Ctrl+Z y por que.</li>
 * </ul>
 *
 * Uso: un grupo por llamada; registrar las acciones en el orden en que se hicieron. Sin Spoon
 * (pruebas) va directo a meta.addUndo. Si entre dos acciones del grupo alguien mas agrego una
 * accion al historial (el usuario, mientras build_steps anima), no se encadena a traves de ella. (UI)
 */
final class UndoGrupo {

  private final Spoon sp;
  private final AbstractMeta meta;
  private TransAction ultima;
  private int acciones;

  UndoGrupo( Spoon sp, AbstractMeta meta ) {
    this.sp = sp;
    this.meta = meta;
  }

  /** Grupo que registra en el meta sin Spoon (pruebas sin GUI). */
  static UndoGrupo sinSpoon( AbstractMeta meta ) {
    return new UndoGrupo( null, meta );
  }

  int acciones() {
    return acciones;
  }

  void nuevo( Object[] objs, int[] idx ) {
    if ( vacio( objs ) ) {
      return;
    }
    TransAction tope = meta.viewThisUndo();
    if ( sp != null ) {
      sp.addUndoNew( meta, objs, idx );
    } else {
      meta.addUndo( objs, null, idx, null, null, AbstractMeta.TYPE_UNDO_NEW, false );
    }
    encadenar( tope );
  }

  void borrado( Object[] objs, int[] idx ) {
    if ( vacio( objs ) ) {
      return;
    }
    TransAction tope = meta.viewThisUndo();
    if ( sp != null ) {
      sp.addUndoDelete( meta, objs, idx );
    } else {
      meta.addUndo( objs, null, idx, null, null, AbstractMeta.TYPE_UNDO_DELETE, false );
    }
    encadenar( tope );
  }

  void cambio( Object[] antes, Object[] despues, int[] idx ) {
    if ( vacio( antes ) ) {
      return;
    }
    TransAction tope = meta.viewThisUndo();
    if ( sp != null ) {
      sp.addUndoChange( meta, antes, despues, idx );
    } else {
      meta.addUndo( antes, despues, idx, null, null, AbstractMeta.TYPE_UNDO_CHANGE, false );
    }
    encadenar( tope );
  }

  void posicion( Object[] objs, int[] idx, Point[] antes, Point[] despues ) {
    if ( vacio( objs ) ) {
      return;
    }
    TransAction tope = meta.viewThisUndo();
    if ( sp != null ) {
      sp.addUndoPosition( meta, objs, idx, antes, despues );
    } else {
      meta.addUndo( objs, null, idx, antes, despues, AbstractMeta.TYPE_UNDO_POSITION, false );
    }
    encadenar( tope );
  }

  /**
   * Marca la accion recien agregada para que se deshaga junto con la anterior del grupo. 'tope' es
   * lo que habia arriba del historial antes de agregarla: si no es la ultima del grupo, alguien
   * metio otra accion en el medio y no se encadena.
   */
  private void encadenar( TransAction tope ) {
    TransAction ta = meta.viewThisUndo();
    if ( ta == null || ta == tope ) {
      return; // no se agrego nada (no deberia pasar)
    }
    if ( ultima != null && tope == ultima && !parFijoDeSpoon( ultima.getType(), ta.getType() ) ) {
      ta.setNextAlso( true );
    }
    ultima = ta;
    acciones++;
  }

  /** Los pares que Spoon.undoAction/redoAction ya encadenan solos: no hay que marcarlos. */
  static boolean parFijoDeSpoon( int anterior, int actual ) {
    return anterior == TransAction.TYPE_ACTION_DELETE_HOP && actual == TransAction.TYPE_ACTION_DELETE_STEP
      || anterior == TransAction.TYPE_ACTION_DELETE_JOB_HOP && actual == TransAction.TYPE_ACTION_DELETE_JOB_ENTRY;
  }

  private static boolean vacio( Object[] a ) {
    return a == null || a.length == 0;
  }
}
