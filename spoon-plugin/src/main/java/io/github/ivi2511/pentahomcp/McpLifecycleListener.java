package io.github.ivi2511.pentahomcp;

import org.pentaho.di.core.annotations.LifecyclePlugin;
import org.pentaho.di.core.lifecycle.LifeEventHandler;
import org.pentaho.di.core.lifecycle.LifecycleException;
import org.pentaho.di.core.lifecycle.LifecycleListener;
import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.core.logging.LogChannelInterface;

/**
 * Punto de entrada del plugin.
 *
 * Kettle descubre esta clase escaneando los .jar de plugins/ (anotacion @LifecyclePlugin,
 * via scannotation). Spoon la instancia en su constructor (new LifecycleSupport()) y llama a
 * onStart(spoon) desde Spoon.createContents(), en el hilo de UI, despues de openSpoon() y antes
 * de cargar los archivos recientes. onExit se llama al cerrar Spoon (Spoon.quitFile).
 *
 * Solo Spoon usa LifecycleSupport: Pan, Kitchen y Carte no disparan este listener.
 */
@LifecyclePlugin( id = "PentahoMCP", name = "Pentaho MCP server" )
public class McpLifecycleListener implements LifecycleListener {

  static final LogChannelInterface LOG = new LogChannel( "PentahoMCP" );

  static final String VERSION = "0.4.0";

  /** Llega a la IA en el initialize de MCP: como trabajar con estas herramientas. */
  static final String INSTRUCCIONES =
    "This server edits the transformation or job open in Spoon (Pentaho Data Integration 9.4) LIVE: the "
      + "user watches the canvas and sees each step appear. Recommended flow: (1) status to see what is open; "
      + "(2) new_transformation / open_file; (3) for each step: list_step_types to find its type id, "
      + "get_step_template to see its configuration XML, add_step with only the tags that change (everything "
      + "else keeps its default) and 'after' so it is placed and connected automatically; to lay out a whole "
      + "flow in one call use build_steps; (4) get_step_fields to confirm which columns come out of each step "
      + "before configuring the next one; (5) validate and preview_step to see real rows; (6) arrange_canvas "
      + "if there are branches or lookups, so no lines cross; (7) save with a .ktr/.kjb path. Place steps left "
      + "to right, ~150 px apart. If a step has several outputs, by default each one gets a copy of every row "
      + "(change it with set_output_mode). generate_report writes a Markdown report of the document and "
      + "canvas_screenshot returns an image of the canvas to check the drawing. Every change can be undone "
      + "with Ctrl+Z in Spoon. The step catalog and the guides are also available as MCP resources "
      + "(pentaho://...) for clients that do not have the pentaho-etl skill.";

  private McpHttpServer server;

  public McpLifecycleListener() {
    // constructor publico sin argumentos: lo exige PluginRegistry.loadClass
  }

  @Override
  public void onStart( LifeEventHandler handler ) throws LifecycleException {
    // Nunca dejar escapar una excepcion: una LifecycleException severa muestra un MessageBox en Spoon.
    try {
      int port = McpConfig.port();
      server = new McpHttpServer( port, VERSION, INSTRUCCIONES );
      DocumentTools.register( server, VERSION, port );
      TransTools.register( server );
      JobTools.register( server );
      RunTools.register( server );
      DbTools.register( server );
      ReportTools.register( server );
      McpResources.register( server );
      server.start();
      LOG.logBasic( "Pentaho MCP " + VERSION + " escuchando en http://127.0.0.1:" + port + "/mcp ("
        + server.toolCount() + " herramientas)" );
    } catch ( Throwable t ) {
      LOG.logError( "Pentaho MCP: no se pudo arrancar el servidor", t );
    }
  }

  @Override
  public void onExit( LifeEventHandler handler ) throws LifecycleException {
    try {
      if ( server != null ) {
        server.stop();
        LOG.logBasic( "Pentaho MCP detenido" );
      }
    } catch ( Throwable t ) {
      LOG.logError( "Pentaho MCP: error al detener el servidor", t );
    }
  }
}
