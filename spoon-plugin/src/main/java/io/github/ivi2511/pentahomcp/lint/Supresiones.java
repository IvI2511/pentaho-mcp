package io.github.ivi2511.pentahomcp.lint;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.core.AttributesInterface;

/**
 * Silenciar reglas con atributos de Kettle (§0, §6 suppress_lint): grupo pentaho-live-lint, clave ignore = ids
 * separados por coma («*» = todas) y reason = por qué. Van en el step, la entrada o el documento y viajan
 * dentro del .ktr/.kjb. setAttribute no marca el documento como cambiado: quien escribe llama setChanged().
 */
public final class Supresiones {

  public static final String GRUPO = "pentaho-live-lint";
  public static final String IGNORAR = "ignore";
  public static final String MOTIVO = "reason";
  public static final String TODAS = "*";

  private Supresiones() {
  }

  public static Set<String> ids( AttributesInterface a ) {
    Set<String> r = new LinkedHashSet<String>();
    if ( a == null ) {
      return r;
    }
    String v;
    try {
      v = a.getAttribute( GRUPO, IGNORAR );
    } catch ( RuntimeException e ) {
      return r;
    }
    if ( v == null ) {
      return r;
    }
    for ( String x : v.split( "[,;\\s]+" ) ) {
      String id = x.trim().toLowerCase( Locale.ROOT );
      if ( !id.isEmpty() ) {
        r.add( id );
      }
    }
    return r;
  }

  public static boolean silencia( AttributesInterface a, String regla ) {
    Set<String> s = ids( a );
    return s.contains( TODAS ) || s.contains( regla );
  }

  public static String motivo( AttributesInterface a ) {
    try {
      return a == null ? null : a.getAttribute( GRUPO, MOTIVO );
    } catch ( RuntimeException e ) {
      return null;
    }
  }

  /** Agrega la regla (y el motivo, si viene). No llama setChanged(). */
  public static void agregar( AttributesInterface a, String regla, String motivo ) {
    Set<String> s = ids( a );
    s.add( regla.trim().toLowerCase( Locale.ROOT ) );
    a.setAttribute( GRUPO, IGNORAR, unir( s ) );
    if ( motivo != null && !motivo.trim().isEmpty() ) {
      a.setAttribute( GRUPO, MOTIVO, motivo.trim() );
    }
  }

  /**
   * Quita la regla; si no queda ninguna, borra el grupo entero. No llama setChanged(). Se borra del mapa y no
   * con setAttribute(…, null): Kettle guardaría la clave con valor nulo.
   */
  public static void quitar( AttributesInterface a, String regla ) {
    Set<String> s = ids( a );
    s.remove( regla.trim().toLowerCase( Locale.ROOT ) );
    if ( !s.isEmpty() ) {
      a.setAttribute( GRUPO, IGNORAR, unir( s ) );
      return;
    }
    Map<String, Map<String, String>> todo = a.getAttributesMap();
    if ( todo != null ) {
      todo.remove( GRUPO );
    }
  }

  private static String unir( Set<String> s ) {
    StringBuilder b = new StringBuilder();
    for ( String x : s ) {
      b.append( b.length() == 0 ? "" : "," ).append( x );
    }
    return b.toString();
  }
}
