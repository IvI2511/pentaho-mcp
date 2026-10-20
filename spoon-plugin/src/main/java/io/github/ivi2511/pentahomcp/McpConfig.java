package io.github.ivi2511.pentahomcp;

import org.pentaho.di.core.util.EnvUtil;

/** Configuracion del plugin. */
final class McpConfig {

  static final String PORT_VAR = "PENTAHO_MCP_PORT";
  static final int DEFAULT_PORT = 8765;

  private McpConfig() {
  }

  /**
   * Puerto: system property (-DPENTAHO_MCP_PORT=... o una linea en ~/.kettle/kettle.properties,
   * que EnvUtil.environmentInit() copia a System properties en KettleEnvironment.init()), luego
   * variable de entorno del SO, luego 8765.
   */
  static int port() {
    String v = null;
    try {
      v = EnvUtil.getSystemProperty( PORT_VAR );
    } catch ( Throwable ignored ) {
      // EnvUtil siempre esta en kettle-core; por las dudas
    }
    if ( v == null || v.trim().isEmpty() ) {
      v = System.getenv( PORT_VAR );
    }
    if ( v != null ) {
      try {
        int p = Integer.parseInt( v.trim() );
        if ( p > 0 && p < 65536 ) {
          return p;
        }
      } catch ( NumberFormatException ignored ) {
        // cae al default
      }
    }
    return DEFAULT_PORT;
  }
}
