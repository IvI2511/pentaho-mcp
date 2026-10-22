package io.github.ivi2511.pentahomcp.robustez;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Un cambio de una propuesta de harden_etl, siempre POR NOMBRE (nada de objetos del clon analizado): se resuelve
 * contra el documento vivo al aplicar. 'xml' (el cuerpo del step nuevo o el fragmento a mezclar) no va al JSON.
 */
public final class Cambio {

  public static final String ADD_STEP = "add_step";
  public static final String REMOVE_HOP = "remove_hop";
  public static final String ADD_HOP = "add_hop";
  public static final String RECONFIGURE = "reconfigure";
  public static final String SET_ERROR_HANDLING = "set_error_handling";
  public static final String STRICT_CONVERSION = "strict_conversion";
  public static final String ADD_PARAMETER = "add_parameter";

  private static final ObjectMapper JSON = new ObjectMapper();

  public final String op;
  /** Parámetros del cambio, en inglés (name, type, step, from, to, target, description_field...). */
  public final Map<String, String> p = new LinkedHashMap<String, String>();
  /** Atributos de Kettle del step nuevo (grupo pentaho-live-robust). */
  public final Map<String, String> atributos = new LinkedHashMap<String, String>();
  /** Cuerpo del step nuevo (fragmento sobre la plantilla) o fragmento a mezclar en uno existente. */
  public String xml;
  public int x;
  public int y;
  /** Línea del diff, en inglés. */
  public String diff;

  public Cambio( String op ) {
    this.op = op;
  }

  public Cambio con( String k, String v ) {
    p.put( k, v );
    return this;
  }

  public String get( String k ) {
    return p.get( k );
  }

  /** Lo que identifica el cambio: dos propuestas que comparten una cola de rechazo comparten sus cambios. */
  public String firma() {
    if ( ADD_STEP.equals( op ) ) {
      return op + "|" + p.get( "name" ).toLowerCase( java.util.Locale.ROOT );
    }
    if ( ADD_HOP.equals( op ) || REMOVE_HOP.equals( op ) ) {
      return op + "|" + p.get( "from" ).toLowerCase( java.util.Locale.ROOT ) + "|"
        + p.get( "to" ).toLowerCase( java.util.Locale.ROOT );
    }
    if ( ADD_PARAMETER.equals( op ) ) {
      return op + "|" + p.get( "name" );
    }
    return op + "|" + p.get( "step" ) + "|" + ( xml == null ? "" : Integer.toHexString( xml.hashCode() ) )
      + "|" + p.toString();
  }

  public ObjectNode json() {
    ObjectNode o = JSON.createObjectNode();
    o.put( "op", op );
    for ( Map.Entry<String, String> e : p.entrySet() ) {
      o.put( e.getKey(), e.getValue() );
    }
    if ( !atributos.isEmpty() ) {
      ObjectNode a = o.putObject( "attributes" );
      for ( Map.Entry<String, String> e : atributos.entrySet() ) {
        a.put( e.getKey(), e.getValue() );
      }
    }
    return o;
  }
}
