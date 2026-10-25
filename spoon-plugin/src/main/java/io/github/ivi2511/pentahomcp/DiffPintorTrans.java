package io.github.ivi2511.pentahomcp;

import org.pentaho.di.core.extension.ExtensionPoint;
import org.pentaho.di.core.extension.ExtensionPointInterface;
import org.pentaho.di.core.logging.LogChannelInterface;

/**
 * Al final de cada pintado del lienzo de una transformación (TransPainterEnd, con el TransPainter): las insignias del
 * diff, si ese documento tiene ({@link DiffLienzo}). Sin marcas no hace nada. Nunca lanza.
 */
@ExtensionPoint( id = "PentahoLiveDiffTrans", extensionPointId = "TransPainterEnd",
  description = "Pentaho Live: draws the semantic diff badges on the transformation canvas" )
public class DiffPintorTrans implements ExtensionPointInterface {

  public DiffPintorTrans() {
  }

  @Override
  public void callExtensionPoint( LogChannelInterface log, Object o ) {
    DiffLienzo.pintar( o );
  }
}
