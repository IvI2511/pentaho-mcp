package io.github.ivi2511.pentahomcp;

import org.pentaho.di.core.extension.ExtensionPoint;
import org.pentaho.di.core.extension.ExtensionPointInterface;
import org.pentaho.di.core.logging.LogChannelInterface;

/** Como {@link DiffPintorTrans}, para el lienzo de un job (JobPainterEnd, con el JobPainter). */
@ExtensionPoint( id = "PentahoLiveDiffJob", extensionPointId = "JobPainterEnd",
  description = "Pentaho Live: draws the semantic diff badges on the job canvas" )
public class DiffPintorJob implements ExtensionPointInterface {

  public DiffPintorJob() {
  }

  @Override
  public void callExtensionPoint( LogChannelInterface log, Object o ) {
    DiffLienzo.pintar( o );
  }
}
