package io.github.ivi2511.pentahomcp.lint;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

/**
 * Lo que el motor le pide al plugin. lint/ no ve las clases package-private del paquete base (RunMotor,
 * TransOps…): la implementa LintEntorno, y los Informes pueden pasar la suya (ctx.salida/entrada). Ctx memoiza
 * las respuestas: cada campo se pide una vez por análisis.
 */
public interface Entorno {

  /** Campos que salen del step (getStepFields), con timeout. */
  RowMetaInterface salida( StepMeta s ) throws KettleStepException;

  /** Campos que llegan al step (getPrevStepFields), con timeout. */
  RowMetaInterface entrada( StepMeta s ) throws KettleStepException;

  /** Salidas normales del step (sin hops de error ni salidas con nombre): TransOps.salidasNormales. */
  int salidasNormales( TransMeta tm, StepMeta s );

  /** Si el step está en la lista blanca de solo lectura del modo profundo (§3.3). */
  boolean soloLectura( StepMeta s );

  boolean cancelado();

  long msRestantes();
}
