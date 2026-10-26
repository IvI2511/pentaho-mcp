package io.github.ivi2511.pentahomcp.diff;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import io.github.ivi2511.pentahomcp.diff.Documento.Nodo;
import io.github.ivi2511.pentahomcp.diff.Documento.Nota;
import io.github.ivi2511.pentahomcp.diff.Documento.Salto;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * La vista semántica de UN archivo, para el driver {@code textconv} de git ({@code pentaho-live textconv x.ktr}): una
 * propiedad por línea, con sangría, sin los valores por defecto del envoltorio de los steps ni los campos volátiles, y
 * sin contraseñas. git compara estos textos línea a línea, así que un cambio de configuración sale como una línea
 * «filter: saldo &gt; 1000» → «filter: saldo &gt;= 1000» y no como XML. Las posiciones salen solo si se piden.
 */
public final class Volcado {

  /** Valores del envoltorio de un step que son los de siempre y no se muestran. */
  static final Map<String, String> POR_DEFECTO = new HashMap<String, String>();

  static {
    POR_DEFECTO.put( "distribute", "Y" );
    POR_DEFECTO.put( "copies", "1" );
    POR_DEFECTO.put( "partitioning/method", "none" );
    POR_DEFECTO.put( "parallel", "N" );
  }

  private Volcado() {
  }

  public static String texto( Documento d, boolean posiciones, String idioma ) {
    return texto( d, posiciones, idioma, null );
  }

  /**
   * Con {@code clave} (la clave local del clon, {@link ClaveSecretos}), cada contraseña sale como «(oculta · #1a2b3c4d)»:
   * una marca que cambia cuando la contraseña cambia, así {@code git diff} muestra la línea cambiada, pero que no es la
   * contraseña ni su hash (un HMAC con una clave que nunca sale del clon). Sin clave, «(oculta)» a secas.
   */
  public static String texto( Documento d, boolean posiciones, String idioma, byte[] clave ) {
    Rotulos t = Salidas.rotulos( idioma );
    StringBuilder sb = new StringBuilder();
    sb.append( t.s( d.esJob() ? "dump.job" : "dump.transformation" ) ).append( ": " ).append( d.nombre )
      .append( '\n' );
    java.util.LinkedHashMap<String, String> props = new java.util.LinkedHashMap<String, String>( d.propiedades );
    props.remove( "name" ); // ya está en la cabecera
    propiedades( props, "  ", t, sb, false, clave );
    if ( !d.parametros.isEmpty() ) {
      sb.append( t.s( "dump.parameters" ) ).append( ":\n" );
      for ( Map.Entry<String, String[]> e : d.parametros.entrySet() ) {
        sb.append( "  " ).append( e.getKey() ).append( " = " ).append( e.getValue()[ 0 ] );
        if ( !e.getValue()[ 1 ].isEmpty() ) {
          sb.append( "  · " ).append( e.getValue()[ 1 ].replace( '\n', ' ' ) );
        }
        sb.append( '\n' );
      }
    }
    if ( !d.conexiones.isEmpty() ) {
      sb.append( t.s( "dump.connections" ) ).append( ":\n" );
      for ( Map.Entry<String, java.util.LinkedHashMap<String, String>> e : d.conexiones.entrySet() ) {
        sb.append( "  " ).append( e.getKey() ).append( '\n' );
        propiedades( e.getValue(), "    ", t, sb, false, clave );
      }
    }
    sb.append( t.s( d.esJob() ? "dump.entries" : "dump.steps" ) ).append( ":\n" );
    for ( Nodo n : d.nodos.values() ) {
      sb.append( "  " ).append( n.clave ).append( " [" ).append( n.tipo ).append( "]\n" );
      if ( posiciones ) {
        sb.append( "    @ " ).append( n.x ).append( ',' ).append( n.y ).append( '\n' );
      }
      propiedades( n.config, "    ", t, sb, true, clave );
    }
    if ( !d.saltos.isEmpty() ) {
      sb.append( t.s( "dump.hops" ) ).append( ":\n" );
      for ( Salto s : d.saltos ) {
        sb.append( "  " ).append( s.desde ).append( " -> " ).append( s.hasta );
        if ( s.condicion != null ) {
          sb.append( " [" ).append( t.s( "cond." + s.condicion ) ).append( ']' );
        }
        if ( s.error ) {
          sb.append( " [" ).append( t.s( "hop.error" ) ).append( ']' );
        }
        if ( !s.activo ) {
          sb.append( " [" ).append( t.s( "hop.disabled" ) ).append( ']' );
        }
        sb.append( '\n' );
      }
    }
    if ( !d.errores.isEmpty() ) {
      sb.append( t.s( "dump.error_handling" ) ).append( ":\n" );
      for ( Map.Entry<String, java.util.LinkedHashMap<String, String>> e : d.errores.entrySet() ) {
        sb.append( "  " ).append( e.getKey() ).append( '\n' );
        propiedades( e.getValue(), "    ", t, sb, false, clave );
      }
    }
    if ( !d.notas.isEmpty() ) {
      sb.append( t.s( "dump.notes" ) ).append( ":\n" );
      for ( Nota n : d.notas ) {
        String[] ls = n.texto.split( "\n", -1 );
        for ( int i = 0; i < ls.length; i++ ) {
          sb.append( i == 0 ? "  - " : "    " ).append( ls[ i ] ).append( '\n' );
        }
        if ( posiciones ) {
          sb.append( "    @ " ).append( n.x ).append( ',' ).append( n.y ).append( '\n' );
        }
      }
    }
    return sb.toString();
  }

  private static void propiedades( Map<String, String> m, String sangria, Rotulos t, StringBuilder sb,
                                   boolean envoltorio, byte[] clave ) {
    for ( Map.Entry<String, String> e : m.entrySet() ) {
      String k = e.getKey();
      String v = e.getValue();
      if ( v.isEmpty() || k.endsWith( Aplanar.ORDEN ) ) {
        continue;
      }
      if ( envoltorio && v.equals( POR_DEFECTO.get( k ) ) ) {
        continue;
      }
      if ( envoltorio && Arrays.asList( "remotesteps", "attributes" ).contains( k.split( "/" )[ 0 ] ) && v.trim()
        .isEmpty() ) {
        continue;
      }
      String et = Salidas.etiqueta( k, t );
      if ( Aplanar.esSecreto( v ) ) {
        String marca = clave == null ? null : ClaveSecretos.marca( clave, v );
        sb.append( sangria ).append( et ).append( ": " ).append( marca == null ? t.s( "secret.hidden" ) : t.f(
          "secret.tag", marca ) ).append( '\n' );
      } else if ( v.indexOf( '\n' ) >= 0 ) {
        sb.append( sangria ).append( et ).append( ":\n" );
        for ( String l : v.split( "\n", -1 ) ) {
          sb.append( sangria ).append( "  | " ).append( l ).append( '\n' );
        }
      } else {
        sb.append( sangria ).append( et ).append( ": " ).append( v ).append( '\n' );
      }
    }
  }
}
