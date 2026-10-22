package io.github.ivi2511.pentahomcp;

import java.util.Locale;

import org.pentaho.di.core.util.EnvUtil;

/**
 * Interruptor de la perspectiva Informes y chequeo de classloaders (diseno §1.2 y §1.3).
 * <p>
 * {@code PENTAHO_LIVE_REPORTS=off} (system property, linea en {@code kettle.properties} o variable de
 * entorno) apaga la perspectiva sin desinstalar el MCP: {@link ReportsSpoonPlugin#getPerspective()}
 * devuelve null. {@code kettle.properties} ya esta cargado cuando corre {@code SpoonPluginManager.<clinit>},
 * porque {@code KettleEnvironment.init()} va antes en {@code Spoon.main}.
 * <p>
 * Loaders: cada punto de entrada deja la identidad de su classloader en una system property
 * ({@link #PROP_LOADER_SPOON}, {@link #PROP_LOADER_LIFECYCLE}). Las system properties cruzan loaders; una
 * clase compartida no serviria, porque estaria duplicada justo en el caso que se quiere detectar.
 * <p>
 * Nada de esta clase lanza excepciones: se llama desde el constructor del plugin, donde una
 * RuntimeException deja a Spoon sin arrancar.
 *
 * @dueño WP1
 */
final class Interruptor {

  /** Nombre de la propiedad: {@code PENTAHO_LIVE_REPORTS=off}. */
  static final String PROPIEDAD = "PENTAHO_LIVE_REPORTS";

  /** Identidad del loader del {@code @SpoonPlugin} (la escribe {@link ReportsSpoonPlugin}). */
  static final String PROP_LOADER_SPOON = "pentaho.live.loader.spoon";

  /** Identidad del loader del {@code @LifecyclePlugin} (la escribe McpLifecycleListener, WP10). */
  static final String PROP_LOADER_LIFECYCLE = "pentaho.live.loader.lifecycle";

  private Interruptor() {
  }

  /** true si la perspectiva Informes esta apagada. Nunca lanza. */
  static boolean informesApagados() {
    return apagadoPor( PROPIEDAD );
  }

  /**
   * true si la perspectiva Corrector esta apagada ({@code PENTAHO_LIVE_GRADER=off}, igual que
   * {@link #PROPIEDAD}; docs/corrector-diseno.md §9, plan B). Las tools MCP del corrector siguen. Nunca lanza.
   */
  static boolean correctorApagado() {
    return apagadoPor( PROPIEDAD_CORRECTOR );
  }

  /** Nombre de la propiedad que apaga la perspectiva Corrector. */
  static final String PROPIEDAD_CORRECTOR = "PENTAHO_LIVE_GRADER";

  private static boolean apagadoPor( String propiedad ) {
    try {
      String v = null;
      try {
        v = EnvUtil.getSystemProperty( propiedad );
      } catch ( Throwable ignorada ) {
        // EnvUtil siempre esta en kettle-core; por las dudas, la system property directa
        v = System.getProperty( propiedad );
      }
      if ( v == null || v.trim().isEmpty() ) {
        v = System.getenv( propiedad );
      }
      return apagado( v );
    } catch ( Throwable t ) {
      return false;
    }
  }

  /** "off", "false", "0", "no" o "disabled" (sin distinguir mayusculas ni espacios) apagan. */
  static boolean apagado( String valor ) {
    if ( valor == null ) {
      return false;
    }
    String v = valor.trim().toLowerCase( Locale.ROOT );
    return v.equals( "off" ) || v.equals( "false" ) || v.equals( "0" ) || v.equals( "no" ) || v.equals( "disabled" );
  }

  /** "clase@hash" del loader; "bootstrap" si es null. Igual en todos los loaders (es solo texto). */
  static String identidadLoader( ClassLoader cl ) {
    try {
      if ( cl == null ) {
        return "bootstrap";
      }
      return cl.getClass().getName() + "@" + Integer.toHexString( System.identityHashCode( cl ) );
    } catch ( Throwable t ) {
      return "?";
    }
  }

  /** Deja en la system property la identidad del loader de la clase. Nunca lanza. */
  static void marcarLoader( String propiedad, Class<?> clase ) {
    try {
      System.setProperty( propiedad, identidadLoader( clase.getClassLoader() ) );
    } catch ( Throwable ignorada ) {
      // un SecurityManager raro: el chequeo es solo diagnostico
    }
  }

  /**
   * null si los dos puntos de entrada cargaron con el mismo loader (o si falta alguno, por ejemplo con
   * la perspectiva apagada); si no, un mensaje para el log. Lo usa McpLifecycleListener.onStart (WP10).
   */
  static String loadersDistintos() {
    try {
      String s = System.getProperty( PROP_LOADER_SPOON );
      String l = System.getProperty( PROP_LOADER_LIFECYCLE );
      if ( s == null || l == null || s.equals( l ) ) {
        return null;
      }
      return "Pentaho Live: the Reports perspective and the MCP server were loaded by different classloaders ("
        + s + " vs " + l + "). Check that the plugin is installed only once (data-integration\\plugins\\pentaho-mcp) "
        + "and that neither annotation declares a classLoaderGroup.";
    } catch ( Throwable t ) {
      return null;
    }
  }
}
