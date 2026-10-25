package io.github.ivi2511.pentahomcp.diff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

/**
 * Convierte la configuración XML de un step, una entrada, una conexión o el documento en un mapa ordenado
 * {@code ruta -> valor} que se puede comparar propiedad por propiedad:
 * <ul>
 * <li>las hojas son {@code a/b/c};</li>
 * <li>las listas (un contenedor con hijos del mismo tag, o un tag repetido) llevan clave: {@code fields/field[saldo]/type}
 * si cada elemento tiene un nombre ({@code name}, {@code field_name}, {@code column_name}, {@code code}...) único, o la
 * posición {@code [1]}, {@code [2]} si no; las listas con nombre guardan además su orden en
 * {@code fields/field#order} (el orden de las columnas importa);</li>
 * <li>una condición de Kettle ({@code <condition>} del Filter rows y otros) se resume en una sola propiedad legible
 * ({@code saldo >= 1000 AND activo = 'S'}, ver {@link Condicion});</li>
 * <li>las contraseñas nunca salen: su valor se cambia por {@link #SECRETO} + un hash que solo sirve para saber si
 * cambió (ver {@link #secreto}).</li>
 * </ul>
 */
public final class Aplanar {

  /** Prefijo de un valor oculto (contraseña): lo que sigue es un hash para comparar, nunca se muestra. */
  public static final String SECRETO = "\u0000secret:";
  /** Sufijo de la entrada con el orden de una lista con nombres. */
  public static final String ORDEN = "#order";

  /** Hijos que identifican a un elemento de una lista, en orden de preferencia. */
  static final List<String> CLAVES = Collections.unmodifiableList( Arrays.asList( "name", "field_name",
    "column_name", "fieldname", "code", "aggregate", "stream_name", "key", "column", "field", "rename",
    "parameter", "variable_name", "source_step" ) );

  /** Listas donde el orden no significa nada (no se informa su orden). */
  static final Set<String> SIN_ORDEN = new HashSet<String>( Arrays.asList( "attribute", "group", "connection",
    "parameter", "slaveserver", "partitionschema", "clusterschema" ) );

  /** Tags o claves que son secretos. */
  static final Pattern SECRETOS = Pattern.compile(
    "(?i).*(password|passwd|passphrase|pass_word|secret|private_?key|api_?key|access_?key|token|credential|username|user_name|auth_user).*" );

  /** Un valor que solo es una variable ({@code ${DB_PASS}}): no es una contraseña, se muestra. */
  static final Pattern SOLO_VARIABLE = Pattern.compile( "\\$\\{[^}]+\\}|%%[^%]+%%" );

  private Aplanar() {
  }

  /** Todos los hijos de {@code e} menos los tags de {@code ignorar}. */
  public static LinkedHashMap<String, String> aplanar( Element e, Set<String> ignorar ) {
    LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
    if ( e != null ) {
      hijos( e, "", out, ignorar == null ? Collections.<String>emptySet() : ignorar, false );
    }
    return out;
  }

  private static void hijos( Element padre, String prefijo, Map<String, String> out, Set<String> ignorar,
                             boolean secretoHeredado ) {
    List<Element> hs = new ArrayList<Element>();
    for ( Element h : Xmls.hijos( padre ) ) {
      if ( prefijo.isEmpty() && ignorar.contains( h.getTagName() ) ) {
        continue;
      }
      hs.add( h );
    }
    // cuántas veces aparece cada tag y si el padre es un contenedor (todos los hijos con el mismo tag)
    Map<String, Integer> cuenta = new LinkedHashMap<String, Integer>();
    for ( Element h : hs ) {
      Integer n = cuenta.get( h.getTagName() );
      cuenta.put( h.getTagName(), n == null ? 1 : n + 1 );
    }
    boolean contenedor = cuenta.size() == 1 && !prefijo.isEmpty() && Xmls.tieneHijos( hs.get( 0 ) );
    Map<String, List<String>> etiquetas = new HashMap<String, List<String>>();
    for ( String tag : cuenta.keySet() ) {
      List<Element> grupo = new ArrayList<Element>();
      for ( Element h : hs ) {
        if ( h.getTagName().equals( tag ) ) {
          grupo.add( h );
        }
      }
      if ( cuenta.get( tag ) > 1 ) {
        etiquetas.put( tag, claves( grupo ) );
      } else if ( contenedor ) {
        // un solo elemento en un contenedor: con clave si tiene nombre (para que coincida con la versión de dos)
        List<String> ks = claves( grupo );
        if ( !ks.get( 0 ).matches( "\\d+" ) ) {
          etiquetas.put( tag, ks );
        }
      }
    }
    Map<String, Integer> visto = new HashMap<String, Integer>();
    for ( Element h : hs ) {
      String tag = h.getTagName();
      int i = visto.containsKey( tag ) ? visto.get( tag ) : 0;
      visto.put( tag, i + 1 );
      List<String> ks = etiquetas.get( tag );
      String seg = tag;
      boolean secreto = secretoHeredado || SECRETOS.matcher( tag ).matches();
      if ( ks != null ) {
        String k = ks.get( i );
        seg = tag + "[" + k + "]";
        if ( SECRETOS.matcher( k ).matches() ) {
          secreto = true;
        }
      }
      String ruta = prefijo.isEmpty() ? seg : prefijo + "/" + seg;
      if ( Condicion.esCondicion( h ) ) {
        out.put( ruta, Condicion.texto( h ) );
        continue;
      }
      if ( Xmls.tieneHijos( h ) ) {
        hijos( h, ruta, out, ignorar, secreto );
      } else {
        String v = Xmls.texto( h );
        if ( v.indexOf( '\n' ) >= 0 && v.trim().isEmpty() ) {
          v = ""; // un contenedor vacío (<parameters> con su sangría), no un valor
        }
        out.put( ruta, secreto || v.startsWith( "Encrypted " ) ? secreto( v ) : v );
      }
    }
    // el orden de las listas con nombre
    for ( Map.Entry<String, List<String>> e : etiquetas.entrySet() ) {
      List<String> ks = e.getValue();
      if ( SIN_ORDEN.contains( e.getKey() ) || ks.isEmpty() || ks.get( 0 ).matches( "\\d+" ) ) {
        continue;
      }
      String ruta = ( prefijo.isEmpty() ? "" : prefijo + "/" ) + e.getKey() + ORDEN;
      out.put( ruta, unir( ks ) );
    }
  }

  /** Las claves de los elementos de una lista: un nombre único de CLAVES, o la posición 1, 2, 3... */
  static List<String> claves( List<Element> grupo ) {
    for ( String c : CLAVES ) {
      List<String> ks = new ArrayList<String>();
      Set<String> unicas = new LinkedHashSet<String>();
      boolean ok = true;
      for ( Element g : grupo ) {
        Element k = Xmls.hijo( g, c );
        String v = k == null || Xmls.tieneHijos( k ) ? "" : Xmls.texto( k ).trim();
        if ( v.isEmpty() || v.contains( "[" ) || v.contains( "]" ) || v.contains( "/" ) || !unicas.add( v ) ) {
          ok = false;
          break;
        }
        ks.add( v );
      }
      if ( ok && !ks.isEmpty() ) {
        return ks;
      }
    }
    List<String> ks = new ArrayList<String>();
    for ( int i = 1; i <= grupo.size(); i++ ) {
      ks.add( String.valueOf( i ) );
    }
    return ks;
  }

  static String unir( List<String> l ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      sb.append( sb.length() == 0 ? "" : ", " ).append( s );
    }
    return sb.toString();
  }

  /**
   * Un valor secreto: {@link #SECRETO} + un hash (SHA-256 con sal fija, para comparar dos versiones sin guardar el
   * valor). Vacío o solo una variable quedan tal cual. El hash no se muestra nunca.
   */
  public static String secreto( String v ) {
    if ( v == null || v.isEmpty() || SOLO_VARIABLE.matcher( v.trim() ).matches() ) {
      return v == null ? "" : v;
    }
    try {
      MessageDigest md = MessageDigest.getInstance( "SHA-256" );
      byte[] h = md.digest( ( "pentaho-live-diff:" + v ).getBytes( StandardCharsets.UTF_8 ) );
      StringBuilder sb = new StringBuilder( SECRETO );
      for ( int i = 0; i < 12; i++ ) {
        sb.append( String.format( Locale.ROOT, "%02x", h[ i ] ) );
      }
      return sb.toString();
    } catch ( Exception e ) {
      return SECRETO + v.length();
    }
  }

  public static boolean esSecreto( String v ) {
    return v != null && v.startsWith( SECRETO );
  }

  /** El segmento sin clave: {@code field[saldo]} -> {@code field}. */
  static String tagDe( String segmento ) {
    int i = segmento.indexOf( '[' );
    return i < 0 ? segmento : segmento.substring( 0, i );
  }
}
