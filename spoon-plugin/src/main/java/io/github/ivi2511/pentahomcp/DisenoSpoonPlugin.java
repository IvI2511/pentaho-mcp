package io.github.ivi2511.pentahomcp;

import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.ui.spoon.SpoonLifecycleListener;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPlugin;
import org.pentaho.di.ui.spoon.SpoonPluginCategories;
import org.pentaho.di.ui.spoon.SpoonPluginInterface;
import org.pentaho.ui.xul.XulDomContainer;

/**
 * Cuarto punto de entrada del mismo jar: registra la perspectiva Diseño (D1), donde se ve el plano ETL con sus tres
 * vistas. Mismas reglas que {@link ReportsSpoonPlugin} y {@link GraderSpoonPlugin}: <b>sin classLoaderGroup</b>,
 * constructor y {@link #getPerspective()} triviales y todo en {@code catch (Throwable)} (una excepción aquí deja a
 * Spoon sin arrancar). {@code PENTAHO_LIVE_DESIGN=off} (variable de entorno o propiedad) la apaga; las tools MCP del
 * plano siguen.
 */
@SpoonPlugin( id = "PentahoLiveDesign", image = "", name = "Pentaho Live Design",
  description = "ETL plan (design) perspective of Pentaho Live" )
@SpoonPluginCategories( { "spoon" } )
public class DisenoSpoonPlugin implements SpoonPluginInterface {

  /** Propiedad o variable de entorno que apaga la perspectiva. */
  static final String PROPIEDAD = "PENTAHO_LIVE_DESIGN";

  private DisenoPerspective perspectiva;
  private boolean decidido;

  public DisenoSpoonPlugin() {
    // constructor publico sin argumentos (PluginRegistry.loadClass); nada de widgets ni de E/S
  }

  @Override
  public void applyToContainer( String category, XulDomContainer container ) {
    // sin XUL: la perspectiva aparece sola en el selector de Spoon
  }

  @Override
  public SpoonLifecycleListener getLifecycleListener() {
    return null;
  }

  @Override
  public synchronized SpoonPerspective getPerspective() {
    if ( !decidido ) {
      decidido = true;
      try {
        if ( !apagada() ) {
          perspectiva = new DisenoPerspective();
        }
      } catch ( Throwable t ) {
        perspectiva = null;
        try {
          new LogChannel( DisenoPerspective.CANAL ).logError( "Pentaho Live: the Design perspective could not be "
            + "created; Spoon continues without it", t );
        } catch ( Throwable ignorada ) {
          // sin log: igual Spoon sigue
        }
      }
    }
    return perspectiva;
  }

  static boolean apagada() {
    try {
      String v = System.getProperty( PROPIEDAD );
      if ( v == null || v.trim().isEmpty() ) {
        v = System.getenv( PROPIEDAD );
      }
      return Interruptor.apagado( v );
    } catch ( Throwable t ) {
      return false;
    }
  }
}
