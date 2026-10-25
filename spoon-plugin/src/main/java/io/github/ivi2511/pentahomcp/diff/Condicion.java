package io.github.ivi2511.pentahomcp.diff;

import java.util.List;
import java.util.Locale;

import org.w3c.dom.Element;

/**
 * Una condición de Kettle ({@code <condition>} del Filter rows, Java filter...) como texto legible:
 * {@code saldo >= 1000 AND activo = 'S'}, {@code NOT (ciudad IS NULL)}. Lee el XML de {@code Condition.getXML()}:
 * {@code negated}, {@code operator}, {@code leftvalue}, {@code function}, {@code rightvalue}, {@code value} y
 * {@code conditions} anidadas.
 */
final class Condicion {

  private Condicion() {
  }

  static boolean esCondicion( Element e ) {
    return "condition".equals( e.getTagName() ) && ( Xmls.hijo( e, "leftvalue" ) != null
      || Xmls.hijo( e, "conditions" ) != null || Xmls.hijo( e, "function" ) != null );
  }

  /** El texto de la condición ("" si está vacía: deja pasar todo). */
  static String texto( Element c ) {
    String t = cuerpo( c );
    return t == null ? "" : t;
  }

  private static String cuerpo( Element c ) {
    boolean negada = si( Xmls.texto( c, "negated" ) );
    Element subs = Xmls.hijo( c, "conditions" );
    List<Element> hijas = subs == null ? null : Xmls.hijos( subs, "condition" );
    String t;
    if ( hijas != null && !hijas.isEmpty() ) {
      StringBuilder sb = new StringBuilder();
      for ( int i = 0; i < hijas.size(); i++ ) {
        Element h = hijas.get( i );
        String ht = cuerpo( h );
        if ( ht == null || ht.isEmpty() ) {
          continue;
        }
        boolean compuesta = Xmls.hijo( h, "conditions" ) != null && Xmls.hijos( Xmls.hijo( h, "conditions" ),
          "condition" ).size() > 1 && !si( Xmls.texto( h, "negated" ) );
        if ( compuesta ) {
          ht = "(" + ht + ")";
        }
        if ( sb.length() > 0 ) {
          String op = Xmls.texto( h, "operator" ).trim().toUpperCase( Locale.ROOT );
          sb.append( ' ' ).append( op.isEmpty() || op.equals( "-" ) ? "AND" : op ).append( ' ' );
        }
        sb.append( ht );
      }
      t = sb.toString();
    } else {
      t = simple( c );
    }
    if ( t == null || t.isEmpty() ) {
      return negada ? "NOT (TRUE)" : "";
    }
    return negada ? "NOT (" + t + ")" : t;
  }

  private static String simple( Element c ) {
    String izq = Xmls.texto( c, "leftvalue" ).trim();
    String fn = Xmls.texto( c, "function" ).trim();
    if ( izq.isEmpty() && ( fn.isEmpty() || fn.equals( "=" ) ) ) {
      return "";
    }
    String up = fn.toUpperCase( Locale.ROOT );
    if ( up.equals( "TRUE" ) ) {
      return izq.isEmpty() ? "TRUE" : izq + " IS TRUE";
    }
    if ( up.equals( "IS NULL" ) || up.equals( "IS NOT NULL" ) ) {
      return izq + " " + up;
    }
    String der = Xmls.texto( c, "rightvalue" ).trim();
    if ( der.isEmpty() ) {
      Element v = Xmls.hijo( c, "value" );
      if ( v != null ) {
        String tipo = Xmls.texto( v, "type" ).trim();
        boolean nula = si( Xmls.texto( v, "isnull" ) );
        String txt = Xmls.texto( v, "text" );
        if ( nula ) {
          der = "NULL";
        } else if ( "String".equalsIgnoreCase( tipo ) || "Date".equalsIgnoreCase( tipo )
          || "Timestamp".equalsIgnoreCase( tipo ) || txt.isEmpty() ) {
          der = "'" + txt.replace( "'", "''" ) + "'";
        } else {
          der = txt;
        }
      } else {
        der = "''";
      }
    }
    return izq + " " + ( fn.isEmpty() ? "=" : fn ) + " " + der;
  }

  private static boolean si( String s ) {
    String x = s == null ? "" : s.trim();
    return x.equalsIgnoreCase( "Y" ) || x.equalsIgnoreCase( "true" );
  }
}
