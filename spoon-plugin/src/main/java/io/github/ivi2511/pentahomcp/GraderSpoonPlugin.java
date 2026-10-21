package io.github.ivi2511.pentahomcp;

import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.ui.spoon.SpoonLifecycleListener;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPlugin;
import org.pentaho.di.ui.spoon.SpoonPluginCategories;
import org.pentaho.di.ui.spoon.SpoonPluginInterface;
import org.pentaho.ui.xul.XulDomContainer;

/**
 * Tercer punto de entrada del mismo jar: registra la perspectiva Corrector (docs/corrector-diseno.md §9, plan B).
 * La perspectiva Informes 0.5.0 no tiene pestanas donde colgar el corrector, asi que va en una propia.
 * <p>
 * Mismas reglas que {@link ReportsSpoonPlugin}: <b>sin {@code classLoaderGroup}</b> (asi carga en el mismo
 * {@code KettleURLClassLoader} que el {@code @LifecyclePlugin} y comparte sus estaticos: los lotes de
 * {@link CorrectorLote} que tambien ve {@code get_grading_status}), constructor y {@link #getPerspective()} triviales,
 * todo en {@code catch (Throwable)}: una excepcion aca deja a Spoon sin arrancar. {@code PENTAHO_LIVE_GRADER=off}
 * apaga la perspectiva ({@link Interruptor#correctorApagado()}); las tools MCP del corrector siguen.
 *
 * @dueño C8
 */
@SpoonPlugin( id = "PentahoLiveGrader", image = "", name = "Pentaho Live Grader",
  description = "Exercise grader perspective of Pentaho Live" )
@SpoonPluginCategories( { "spoon" } )
public class GraderSpoonPlugin implements SpoonPluginInterface {

  private GraderPerspective perspectiva;
  private boolean decidido;

  public GraderSpoonPlugin() {
    // constructor publico sin argumentos (PluginRegistry.loadClass); nada de widgets ni de E/S
  }

  /** Sin XUL propio: la perspectiva aparece sola en el desplegable de la barra de Spoon. */
  @Override
  public void applyToContainer( String category, XulDomContainer container ) {
    // nada
  }

  @Override
  public SpoonLifecycleListener getLifecycleListener() {
    return null; // McpLifecycleListener ya cubre el arranque y el cierre
  }

  /** La perspectiva, siempre la misma instancia; null si esta apagada o no se pudo crear. Nunca lanza. */
  @Override
  public synchronized SpoonPerspective getPerspective() {
    if ( !decidido ) {
      decidido = true;
      try {
        if ( !Interruptor.correctorApagado() ) {
          perspectiva = new GraderPerspective();
        }
      } catch ( Throwable t ) {
        perspectiva = null;
        try {
          new LogChannel( GraderPerspective.CANAL ).logError( "Pentaho Live: the Grader perspective could not be "
            + "created; Spoon continues without it", t );
        } catch ( Throwable ignorada ) {
          // sin log: igual Spoon sigue
        }
      }
    }
    return perspectiva;
  }
}
