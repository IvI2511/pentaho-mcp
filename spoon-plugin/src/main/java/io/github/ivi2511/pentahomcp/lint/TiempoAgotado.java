package io.github.ivi2511.pentahomcp.lint;

import org.pentaho.di.core.exception.KettleStepException;

/**
 * La lanza el Entorno cuando calcular campos se pasa del timeout (suele ser una BD que no contesta). El hilo
 * que espera a la BD no se puede matar: después de la primera, Ctx no vuelve a pedir campos en ese análisis.
 */
public class TiempoAgotado extends KettleStepException {

  private static final long serialVersionUID = 1L;

  public TiempoAgotado( String mensaje ) {
    super( mensaje );
  }
}
