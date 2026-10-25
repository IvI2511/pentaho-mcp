package io.github.ivi2511.pentahomcp.robustez;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Una propuesta del plan de harden_etl: una receta sobre un step (el ancla), con su nivel, por qué, los cambios por
 * nombre y un diff de texto. Key: {@code harden:<receta>:<step>}.
 */
public final class Propuesta {

  private static final ObjectMapper JSON = new ObjectMapper();

  public final String receta;
  public final String step;
  public final String key;
  public String nivel;
  public boolean semantica;
  public String porQue;
  public final List<Cambio> cambios = new ArrayList<Cambio>();
  public final List<String> avisos = new ArrayList<String>();
  /** Para las manuales (y las que no se pueden aplicar solas): cómo hacerlo, en inglés para la IA. */
  public String comoHacerlo;
  /** Datos detectados que la persona puede corregir (campos obligatorios, claves...). */
  public ObjectNode detectado;

  public Propuesta( String receta, String step ) {
    this.receta = receta;
    this.step = step;
    this.key = "harden:" + receta + ":" + step;
    Receta r = Receta.de( receta );
    this.nivel = r == null ? Receta.MANUAL : r.nivel;
    this.semantica = r != null && r.semantica;
  }

  public boolean aplicable() {
    return !Receta.MANUAL.equals( nivel ) && !cambios.isEmpty();
  }

  /** El diff de texto: '+' lo que se agrega, '-' lo que se quita, '~' lo que cambia. */
  public String diff() {
    StringBuilder b = new StringBuilder();
    for ( Cambio c : cambios ) {
      if ( c.diff == null ) {
        continue;
      }
      if ( b.length() > 0 ) {
        b.append( '\n' );
      }
      b.append( c.diff );
    }
    return b.toString();
  }

  public ObjectNode json() {
    ObjectNode o = JSON.createObjectNode();
    o.put( "key", key ).put( "recipe", receta ).put( "level", nivel ).put( "semantics_change", semantica );
    o.put( "step", step ).put( "why", porQue );
    if ( !avisos.isEmpty() ) {
      ArrayNode a = o.putArray( "warnings" );
      for ( String s : avisos ) {
        a.add( s );
      }
    }
    if ( detectado != null ) {
      o.set( "detected", detectado );
    }
    if ( !cambios.isEmpty() ) {
      ArrayNode a = o.putArray( "changes" );
      for ( Cambio c : cambios ) {
        a.add( c.json() );
      }
      o.put( "diff", diff() );
    }
    boolean parametro = false;
    for ( Cambio c : cambios ) {
      parametro |= Cambio.ADD_PARAMETER.equals( c.op );
    }
    if ( parametro ) {
      o.put( "undoable", true ); // desde 0.7.0 el parámetro entra en el mismo Ctrl+Z (UndoEfecto)
    }
    if ( comoHacerlo != null ) {
      o.put( "how_to", comoHacerlo );
    }
    return o;
  }
}
