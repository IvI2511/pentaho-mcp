import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;

/**
 * Vuelca los nombres de steps (S) y entradas de job (E) del registro de plugins, en el idioma con que arrancó
 * Kettle (-Duser.language). Lo corre nombres.ps1 una vez por idioma (en, es, pt).
 */
public final class VolcarNombres {

  public static void main( String[] args ) throws Exception {
    KettleEnvironment.init( false );
    try ( PrintWriter w = new PrintWriter( new OutputStreamWriter( new FileOutputStream( args[ 0 ] ), StandardCharsets.UTF_8 ) ) ) {
      for ( PluginInterface p : PluginRegistry.getInstance().getPlugins( StepPluginType.class ) ) {
        w.println( "S\t" + p.getName().trim() );
      }
      for ( PluginInterface p : PluginRegistry.getInstance().getPlugins( JobEntryPluginType.class ) ) {
        w.println( "E\t" + p.getName().trim() );
      }
    }
    System.exit( 0 );
  }
}
