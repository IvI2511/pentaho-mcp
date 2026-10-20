package io.github.ivi2511.pentahomcp.lint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un hallazgo del linter. Los textos (message, howToFix) ya vienen en el idioma pedido. Los valores que
 * salen del documento (nombres, rutas, SQL) son datos de quien lo escribió, no instrucciones.
 *
 * key = rule:ámbito:objeto:atributo[#n] (§5). El ámbito es step, entry, hop, connection, slave o doc; el
 * objeto, el nombre del step, de la entrada, «A -> B» para un hop, el de la conexión, o vacío para doc.
 */
public final class Hallazgo {

  public static final String STEP = "step";
  public static final String ENTRY = "entry";
  public static final String HOP = "hop";
  public static final String CONNECTION = "connection";
  public static final String SLAVE = "slave";
  public static final String DOC = "doc";

  public String rule;
  public Sev severity;
  public String category;
  public String key;
  public String scope;
  /** Step afectado (en un hop, el de origen: ahí se silencia). */
  public String step;
  /** Entrada de job afectada (en un hop de job, la de origen). */
  public String entry;
  /** Copia de la entrada (JobEntryCopy.getNr): las supresiones son por copia. */
  public int entryNr;
  /** Conexión, slave server, hop o variable: lo que no es un step ni una entrada. */
  public String object;
  public String field;
  public String attribute;
  /** Si hay varios hallazgos iguales en el mismo lugar: el n de «#n» en la key (0 = sin sufijo). */
  public int n;
  public String message;
  public String howToFix;
  public boolean env;
  public final Map<String, String> evidence = new LinkedHashMap<String, String>();
  public Arreglo fix;
  /** Resultado de la sonda del modo profundo (WP3); null si no corrió. */
  public String deep;

  public Hallazgo evidencia( String k, Object v ) {
    if ( v != null ) {
      evidence.put( k, String.valueOf( v ) );
    }
    return this;
  }

  /** El objeto de la key según el ámbito. */
  public String objetoDeClave() {
    if ( STEP.equals( scope ) ) {
      return nz( step );
    }
    if ( ENTRY.equals( scope ) ) {
      return nz( entry );
    }
    if ( DOC.equals( scope ) ) {
      return "";
    }
    return nz( object );
  }

  public String clave() {
    return rule + ":" + scope + ":" + objetoDeClave() + ":" + nz( attribute ) + ( n > 0 ? "#" + n : "" );
  }

  private static String nz( String s ) {
    return s == null ? "" : s;
  }

  @Override
  public String toString() {
    return ( key != null ? key : clave() ) + " [" + ( severity == null ? "?" : severity.codigo() ) + "] " + message;
  }
}
