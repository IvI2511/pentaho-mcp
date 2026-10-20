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

  static final String VERSION = "0.3.1";

  /** Llega a la IA en el initialize de MCP: como trabajar con estas herramientas. */
  static final String INSTRUCCIONES =
    "Este servidor edita EN VIVO la transformacion o el job abierto en Spoon (Pentaho Data Integration "
      + "9.4): el usuario mira el lienzo y ve aparecer cada step. Flujo recomendado: (1) estado para ver "
      + "que hay abierto; (2) nueva_transformacion / abrir_archivo; (3) para cada step: tipos_de_step para "
      + "encontrar el id, plantilla_step para ver su XML de configuracion, agregar_step con solo los tags que "
      + "cambian (lo demas queda por defecto) y 'despues_de' para que se ubique y conecte solo; (4) "
      + "campos_step para confirmar que columnas salen de cada step antes de configurar el siguiente; (5) "
      + "validar y preview_step para ver filas reales; (6) ordenar_lienzo si hay ramas o lookups, para que "
      + "no queden lineas cruzadas; (7) guardar con una ruta .ktr/.kjb. Ubica los steps de izquierda a "
      + "derecha, ~150 px entre uno y otro. Si un step tiene varias salidas, por defecto cada una recibe todas "
      + "las filas (modo_salida). Todo cambio se puede deshacer con Ctrl+Z en Spoon.";

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
