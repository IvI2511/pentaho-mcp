package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.extension.ExtensionPointInterface;
import org.pentaho.di.core.extension.ExtensionPointPluginType;
import org.pentaho.di.core.extension.KettleExtensionPoint;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.plugins.Plugin;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.trans.Trans;

/**
 * El gancho de las pruebas de ETL en la JVM de las pruebas (0.7.1): cada transformacion que arranca en esa JVM (las
 * que corre un job con sus entradas Transformation, y los mappings o executors de una transformacion) pasa por
 * {@link #callExtensionPoint} al empezar {@code Trans.prepareExecution}, cuando su TransMeta todavia se puede cambiar:
 * ahi {@link PruebasJob} cambia steps por datasets, aplica los ajustes, apunta las conexiones a las bases de prueba y
 * pone las escuchas de las salidas capturadas.
 * <p>
 * No va anotado con {@code @ExtensionPoint} (Spoon lo registraria al cargar el plugin): se registra a mano solo en la
 * JVM de las pruebas ({@link #registrar}) y no hace nada si no hay un {@link #activo}.
 *
 * @dueño P1 (0.7.1)
 */
public class PruebasGancho implements ExtensionPointInterface {

  /** Id del plugin de punto de extension. */
  static final String ID = "PentahoLiveTestsPrepare";

  /** Lo que se hace con cada Trans que se prepara. */
  interface Oyente {
    void preparar( Trans t ) throws KettleException;
  }

  /** La prueba en curso en esta JVM (una a la vez), o null. */
  static volatile Oyente activo;

  private static final Object LOCK = new Object();

  public PruebasGancho() {
  }

  @Override
  public void callExtensionPoint( LogChannelInterface log, Object o ) throws KettleException {
    Oyente x = activo;
    if ( x == null || !( o instanceof Trans ) ) {
      return;
    }
    x.preparar( (Trans) o );
  }

  /** Registra el gancho en TransformationPrepareExecution (una vez por JVM; Kettle ya iniciado). */
  static void registrar() throws KettleException {
    synchronized ( LOCK ) {
      PluginRegistry reg = PluginRegistry.getInstance();
      if ( reg.findPluginWithId( ExtensionPointPluginType.class, ID ) != null ) {
        return;
      }
      Map<Class<?>, String> clases = new HashMap<Class<?>, String>();
      clases.put( ExtensionPointInterface.class, PruebasGancho.class.getName() );
      List<String> libs = new ArrayList<String>();
      PluginInterface p = new Plugin( new String[] { ID }, ExtensionPointPluginType.class,
        ExtensionPointInterface.class, "", KettleExtensionPoint.TransformationPrepareExecution.id,
        "Pentaho Live: golden-dataset tests", null, false, true, clases, libs, null, null );
      reg.registerPlugin( ExtensionPointPluginType.class, p );
    }
  }

  /** Corre {@code r} con {@code o} activo y lo saca al terminar (aunque falle). */
  static void con( Oyente o, Corrida r ) throws Exception {
    registrar();
    synchronized ( LOCK ) {
      if ( activo != null ) {
        throw new IllegalStateException( "another test is running in this JVM" );
      }
      activo = o;
    }
    try {
      r.correr();
    } finally {
      activo = null;
    }
  }

  /** Lo que corre con el gancho activo. */
  interface Corrida {
    void correr() throws Exception;
  }

  /** Para las pruebas: los ids registrados. */
  static List<String> ids() {
    PluginInterface p = PluginRegistry.getInstance().findPluginWithId( ExtensionPointPluginType.class, ID );
    return p == null ? Collections.<String>emptyList() : java.util.Arrays.asList( p.getIds() );
  }
}
