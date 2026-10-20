package io.github.ivi2511.pentahomcp;

import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.ui.spoon.SpoonLifecycleListener;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPlugin;
import org.pentaho.di.ui.spoon.SpoonPluginCategories;
import org.pentaho.di.ui.spoon.SpoonPluginInterface;
import org.pentaho.ui.xul.XulDomContainer;

/**
 * Segundo punto de entrada del MISMO jar que {@link McpLifecycleListener}: registra la perspectiva
 * Informes (diseno §1.2).
 * <p>
 * Lo descubre {@code SpoonPluginType}, que solo mira {@code plugins} relativo al directorio de trabajo
 * (data-integration). {@code SpoonPluginManager.<clinit>}, dentro de {@code Spoon.init}, lo instancia y llama
 * dos veces a {@link #getPerspective()} (chequeo de null y {@code addPerspective}): tiene que ser la misma
 * instancia.
 * <p>
 * <b>Sin {@code classLoaderGroup}</b>, igual que el {@code @LifecyclePlugin}: asi los dos cargan en el mismo
 * {@code KettleURLClassLoader} de la carpeta y comparten los estaticos (Docs, RunMotor, InformeServicio). Un
 * grupo en una sola de las dos anotaciones parte el estado en dos (§1.3): no agregarlo.
 * <p>
 * <b>Reglas duras.</b> Una RuntimeException en el constructor o en getPerspective() se vuelve
 * ExceptionInInitializerError y Spoon no arranca; una en applyToContainer lo deja sin lienzo. Por eso todo es
 * trivial y esta envuelto en {@code catch (Throwable)}, y la perspectiva se crea recien en getPerspective().
 *
 * @dueño WP1
 */
@SpoonPlugin( id = "PentahoLiveReports", image = "", name = "Pentaho Live Reports",
  description = "Reports perspective of Pentaho Live" )
@SpoonPluginCategories( { "spoon" } )
public class ReportsSpoonPlugin implements SpoonPluginInterface {

  private ReportsPerspective perspectiva;
  private boolean decidido;

  public ReportsSpoonPlugin() {
    // constructor publico sin argumentos (PluginRegistry.loadClass); nada de widgets ni de E/S
    try {
      Interruptor.marcarLoader( Interruptor.PROP_LOADER_SPOON, ReportsSpoonPlugin.class );
    } catch ( Throwable ignorada ) {
      // solo diagnostico: nunca puede impedir que Spoon arranque
    }
  }

  /** v1: sin XUL propio. La perspectiva aparece sola en el desplegable de la barra. */
  @Override
  public void applyToContainer( String category, XulDomContainer container ) {
    // nada
  }

  @Override
  public SpoonLifecycleListener getLifecycleListener() {
    return null; // McpLifecycleListener ya cubre el arranque y el cierre
  }

  /**
   * La perspectiva, siempre la misma instancia; null si {@code PENTAHO_LIVE_REPORTS=off} o si no se pudo
   * crear. La decision se toma una sola vez. Nunca lanza.
   */
  @Override
  public synchronized SpoonPerspective getPerspective() {
    if ( !decidido ) {
      decidido = true;
      try {
        if ( !Interruptor.informesApagados() ) {
          perspectiva = new ReportsPerspective();
        }
      } catch ( Throwable t ) {
        perspectiva = null;
        try {
          new LogChannel( "PentahoLive-Informes" ).logError( "Pentaho Live: the Reports perspective could not be "
            + "created; Spoon continues without it", t );
        } catch ( Throwable ignorada ) {
          // sin log: igual Spoon sigue
        }
      }
    }
    return perspectiva;
  }
}
