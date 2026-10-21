package io.github.ivi2511.pentahomcp;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.ui.spoon.SpoonUiExtenderPlugin;
import org.pentaho.di.ui.spoon.SpoonUiExtenderPluginInterface;
import org.pentaho.di.ui.spoon.trans.TransGraph;

/**
 * Modo clase, WP4: repone la pestana «Leccion» cuando Spoon vuelve a armar el panel de resultados de una
 * transformacion (docs/modo-clase-diseno.md §6). TransGraph.addAllTabs (al abrir el panel, que ocultarlo desecha
 * entero) llama a los SpoonUiExtenderPluginInterface registrados para TransGraph con el evento loadTab.
 * <p>
 * Lo encuentra el escaneo de anotaciones de la carpeta plugins (SpoonUiExtenderPluginType), como el
 * {@code @SpoonPlugin} de Informes. <b>Sin {@code classLoaderGroup}</b>: tiene que cargar en el mismo classloader que
 * el {@code @LifecyclePlugin} para ver las lecciones de {@link LeccionMotor}. PluginRegistry.loadClass crea una
 * instancia por evento: sin estado propio. Nada de lo que pase aca puede romper el panel de Spoon (catch Throwable).
 */
@SpoonUiExtenderPlugin( id = "PentahoLiveLessonTab", name = "Pentaho Live lesson tab",
  description = "Lesson tab of the Pentaho Live class mode", image = "" )
public class LeccionTabExtender implements SpoonUiExtenderPluginInterface {

  @Override
  public Map<Class<?>, Set<String>> respondsTo() {
    Map<Class<?>, Set<String>> m = new HashMap<Class<?>, Set<String>>();
    m.put( TransGraph.class, Collections.singleton( TransGraph.LOAD_TAB ) );
    return m;
  }

  @Override
  public void uiEvent( Object subject, String event ) {
    try {
      if ( subject instanceof TransGraph && TransGraph.LOAD_TAB.equals( event ) ) {
        LeccionPanel.alCargarPestanas( (TransGraph) subject );
      }
    } catch ( Throwable t ) {
      try {
        McpLifecycleListener.LOG.logError( "Pentaho Live: the Lesson tab could not be added: " + t );
      } catch ( Throwable ignorada ) {
        // sin log: nada
      }
    }
  }
}
