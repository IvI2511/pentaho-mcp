package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Los mensajes del corrector que se traducen (0.6.2): un error o un detalle es una clave del area «corrector» con
 * sus argumentos ({@link Mensaje}); el texto en ingles (el que va en {@code detail} y por MCP) sale de la misma clave
 * en corrector_en.properties, y la UI y los informes lo arman en el idioma del docente. Asi una UI en español nunca
 * muestra un texto en ingles del corrector (los mensajes del propio Kettle, como la primera linea de un error de un
 * step, quedan como los escribe Kettle).
 * <p>
 * Un argumento puede ser otra clave para traducir ({@link #clave(String)}: el codigo de un motivo) u otro mensaje
 * entero ({@link Mensaje#anidado()}: el detalle de un bloqueo dentro del error de crear un pack). Los nombres de
 * steps, conexiones y archivos van tal cual: son datos.
 *
 * @dueño corrector 0.6.2
 */
final class CorrectorTextos {

  static final String AREA = "corrector";
  /** Argumento que es una clave a traducir. */
  private static final char CLAVE = '\u0001';
  /** Argumento que es un mensaje entero (JSON: [clave, args...]). */
  private static final char ANIDADO = '\u0002';
  /** Argumento que es una lista de mensajes, uno por linea (JSON: [[clave, args...], ...]). */
  private static final char LISTA = '\u0003';

  private CorrectorTextos() {
  }

  /** Un texto traducible: una clave del area «corrector» y sus argumentos (ya como texto). */
  static final class Mensaje {

    final String clave;
    final List<String> args;

    Mensaje( String clave, Object... args ) {
      this.clave = clave;
      List<String> l = new ArrayList<String>();
      for ( Object o : args == null ? new Object[ 0 ] : args ) {
        l.add( o == null ? "" : String.valueOf( o ) );
      }
      this.args = Collections.unmodifiableList( l );
    }

    Mensaje( String clave, List<String> args ) {
      this.clave = clave;
      this.args = Collections.unmodifiableList( new ArrayList<String>( args == null ? Collections.<String>emptyList()
        : args ) );
    }

    /** En ingles: lo que va en {@code detail}, en los logs y por MCP. */
    String en() {
      return texto( Rotulos.de( AREA, "en" ) );
    }

    String texto( Rotulos t ) {
      return CorrectorTextos.texto( t, clave, args );
    }

    String texto( String idioma ) {
      return texto( Rotulos.de( AREA, idioma ) );
    }

    /** Para pasarlo como argumento de otro mensaje. */
    String anidado() {
      List<String> l = new ArrayList<String>();
      l.add( clave );
      l.addAll( args );
      try {
        return ANIDADO + CorrectorJson.escribir( l, false );
      } catch ( Exception e ) {
        return en();
      }
    }

    /** Pone el mensaje como detalle de la corrida de un caso. */
    void en( ResultadoCaso.Corrida c ) {
      c.detalle = en();
      c.claveDetalle = clave;
      c.argsDetalle = new ArrayList<String>( args );
    }

    /** Pone el mensaje como detalle de la entrega. */
    void en( ResultadoCorreccion r ) {
      r.detalle = en();
      r.claveDetalle = clave;
      r.argsDetalle = new ArrayList<String>( args );
    }

    /** Un aviso con este mensaje como detalle. */
    Aviso aviso( String codigo ) {
      Aviso a = new Aviso( codigo, en() );
      a.claveDetalle = clave;
      a.argsDetalle = new ArrayList<String>( args );
      return a;
    }

    @Override
    public String toString() {
      return en();
    }
  }

  /** Un argumento que se traduce: el texto de esa clave en el idioma del mensaje. */
  static String clave( String clave ) {
    return CLAVE + clave;
  }

  /** Varios mensajes como un solo argumento: uno por linea. */
  static String lista( List<Mensaje> mensajes ) {
    List<List<String>> l = new ArrayList<List<String>>();
    for ( Mensaje m : mensajes ) {
      List<String> x = new ArrayList<String>();
      x.add( m.clave );
      x.addAll( m.args );
      l.add( x );
    }
    try {
      return LISTA + CorrectorJson.escribir( l, false );
    } catch ( Exception e ) {
      return "";
    }
  }

  /** El texto de la clave con sus argumentos (los que son claves o mensajes, ya traducidos). */
  static String texto( Rotulos t, String clave, List<String> args ) {
    Object[] a = new Object[ args == null ? 0 : args.size() ];
    for ( int i = 0; i < a.length; i++ ) {
      a[ i ] = arg( t, args.get( i ) );
    }
    if ( clave == null ) {
      return "";
    }
    if ( !t.tiene( clave ) ) {
      StringBuilder b = new StringBuilder( clave );
      for ( Object x : a ) {
        b.append( ' ' ).append( x );
      }
      return b.toString();
    }
    return t.f( clave, a );
  }

  /** Un argumento ya en el idioma de t. */
  static String arg( Rotulos t, String a ) {
    if ( a == null || a.isEmpty() ) {
      return a == null ? "" : a;
    }
    char c = a.charAt( 0 );
    if ( c == CLAVE ) {
      return t.s( a.substring( 1 ) );
    }
    if ( c == ANIDADO || c == LISTA ) {
      try {
        JsonNode n = CorrectorJson.mapper().readTree( a.substring( 1 ) );
        if ( c == ANIDADO ) {
          return desdeJson( t, n );
        }
        StringBuilder b = new StringBuilder();
        for ( JsonNode m : n ) {
          b.append( b.length() == 0 ? "" : "\n" ).append( desdeJson( t, m ) );
        }
        return b.toString();
      } catch ( Exception e ) {
        return a.substring( 1 );
      }
    }
    return a;
  }

  private static String desdeJson( Rotulos t, JsonNode n ) {
    List<String> args = new ArrayList<String>();
    for ( int i = 1; i < n.size(); i++ ) {
      args.add( n.get( i ).asText() );
    }
    return texto( t, n.size() == 0 ? null : n.get( 0 ).asText(), args );
  }

  /**
   * El detalle en el idioma de t: el de la clave si la hay y existe, si no el texto en ingles que vino (un detalle
   * escrito por una version anterior o un mensaje de Kettle).
   */
  static String detalle( Rotulos t, String clave, List<String> args, String ingles ) {
    if ( clave != null && t.tiene( clave ) ) {
      return texto( t, clave, args );
    }
    return ingles;
  }

  static String detalle( Rotulos t, ResultadoCorreccion r ) {
    return detalle( t, r.claveDetalle, r.argsDetalle, r.detalle );
  }

  static String detalle( Rotulos t, ResultadoCaso.Corrida c ) {
    return detalle( t, c.claveDetalle, c.argsDetalle, c.detalle );
  }

  static String detalle( Rotulos t, Aviso a ) {
    return detalle( t, a.claveDetalle, a.argsDetalle, a.detalle );
  }
}
