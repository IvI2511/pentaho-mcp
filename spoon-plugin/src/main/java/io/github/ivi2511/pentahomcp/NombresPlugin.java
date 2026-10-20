package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.PluginTypeInterface;
import org.pentaho.di.i18n.LanguageChoice;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Nombre y descripcion de un tipo de step o de entrada de job en el idioma del INFORME, no en el de
 * Spoon: el registro de plugins de Kettle los traduce una sola vez, al arrancar, con el idioma de la
 * JVM (un informe en ingles salia con «Búsqueda en flujo de datos» y uno en portugues con «Entrada
 * Tabla»).
 * <p>
 * En orden: (1) los textos propios del area {@code plugins} en ese idioma (los tipos comunes, sobre todo
 * para portugues, que Kettle casi no traduce); (2) la clave i18n del plugin (la de
 * {@code kettle-steps.xml} / {@code kettle-job-entries.xml} o la de la anotacion {@code @Step} /
 * {@code @JobEntry}) leida del {@code messages_<locale>.properties} de ese idioma (es: es_ES y si no
 * es_AR; pt: pt_BR y si no pt_PT; en: en_US); (3) en ingles, el nombre del registro si Spoon esta en
 * ingles; (4) el id del plugin, que no depende del idioma. Nunca tira excepciones. Con cache.
 */
final class NombresPlugin {

  /** Area de Rotulos con los nombres propios: textos/plugins_&lt;idioma&gt;.properties. */
  static final String AREA = "plugins";

  private static final Pattern I18N = Pattern.compile( "^i18n:([^:]+):(.+)$" );
  private static final Pattern XML_STEP = Pattern.compile( "<(step|job-entry) id=\"([^\"]+)\">(.*?)</\\1>",
    Pattern.DOTALL );
  private static final Pattern XML_DESC = Pattern.compile( "<description>([^<]*)</description>" );
  private static final Pattern XML_TIP = Pattern.compile( "<tooltip>([^<]*)</tooltip>" );

  /** "step|ID" o "job|ID" -> {nombre, descripcion} como "paquete:clave" o texto literal (prefijo "="). */
  private static volatile Map<String, String[]> xml;
  private static final Map<String, String> CACHE = new ConcurrentHashMap<String, String>();
  private static final Map<String, Properties> BUNDLES = new ConcurrentHashMap<String, Properties>();
  private static final Properties VACIO = new Properties();
  private static final String NULO = "\u0000";

  private NombresPlugin() {
  }

  /** "Table input" / "Entrada Tabla" / "TableInput"...: el nombre del tipo en ese idioma. Nunca null. */
  static String nombre( Class<? extends PluginTypeInterface> tipo, String id, String idioma ) {
    if ( id == null ) {
      return "?";
    }
    String i = Rotulos.normalizar( idioma );
    String clave = prefijo( tipo ) + "|" + id + "|n|" + i;
    String v = CACHE.get( clave );
    if ( v == null ) {
      v = resolver( tipo, id, i, true );
      CACHE.put( clave, v == null ? NULO : v );
    }
    return v == null || NULO.equals( v ) ? id : v;
  }

  /** La descripcion (tooltip) del tipo en ese idioma, o null si Kettle no la trae traducida. */
  static String descripcion( Class<? extends PluginTypeInterface> tipo, String id, String idioma ) {
    if ( id == null ) {
      return null;
    }
    String i = Rotulos.normalizar( idioma );
    String clave = prefijo( tipo ) + "|" + id + "|d|" + i;
    String v = CACHE.get( clave );
    if ( v == null ) {
      v = resolver( tipo, id, i, false );
      CACHE.put( clave, v == null ? NULO : v );
    }
    return v == null || NULO.equals( v ) ? null : v;
  }

  /** "Entrada Tabla (TableInput)": el nombre y el id si son distintos. */
  static String conId( Class<? extends PluginTypeInterface> tipo, String id, String idioma ) {
    if ( id == null ) {
      return "?";
    }
    String n = nombre( tipo, id, idioma );
    return n.equals( id ) ? id : n + " (" + id + ")";
  }

  private static String prefijo( Class<?> tipo ) {
    return tipo == JobEntryPluginType.class ? "job" : "step";
  }

  private static String resolver( Class<? extends PluginTypeInterface> tipo, String id, String idioma,
                                  boolean esNombre ) {
    try {
      // 1) los propios
      Rotulos r = Rotulos.de( AREA, idioma );
      String propio = r.propio( prefijo( tipo ) + "." + id + ( esNombre ? "" : ".desc" ) );
      if ( propio != null && !propio.trim().isEmpty() ) {
        return propio.trim();
      }
      // 2) la clave i18n de Kettle en el idioma del informe
      PluginInterface p = PluginRegistry.getInstance().findPluginWithId( tipo, id );
      String[] claves = claves( tipo, id, p );
      String cruda = claves == null ? null : claves[ esNombre ? 0 : 1 ];
      if ( cruda != null ) {
        if ( cruda.startsWith( "=" ) ) {
          String literal = limpiar( cruda.substring( 1 ) );
          if ( literal != null ) {
            return literal; // el plugin no esta traducido: su texto literal vale para todos los idiomas
          }
        } else {
          int dos = cruda.indexOf( ':' );
          ClassLoader cl = cargador( p );
          String t = traducir( cruda.substring( 0, dos ), cruda.substring( dos + 1 ), idioma, cl );
          if ( t != null ) {
            return t;
          }
        }
      }
      // 3) en ingles, el del registro si Spoon tambien esta en ingles
      if ( esNombre && "en".equals( idioma ) && p != null && spoonEnIngles() ) {
        String n = limpiar( p.getName() );
        if ( n != null ) {
          return n;
        }
      }
    } catch ( Throwable ignorada ) {
      // sin registro o plugin raro: el id
    }
    return null;
  }

  private static boolean spoonEnIngles() {
    try {
      Locale l = LanguageChoice.getInstance().getDefaultLocale();
      return l == null || "en".equals( l.getLanguage() );
    } catch ( Throwable e ) {
      return false;
    }
  }

  /** {nombre, descripcion} como "paquete:clave" (o "=texto" literal); de la anotacion o del XML de Kettle. */
  private static String[] claves( Class<? extends PluginTypeInterface> tipo, String id, PluginInterface p ) {
    String[] deXml = xml().get( prefijo( tipo ) + "|" + id );
    if ( deXml != null ) {
      return deXml;
    }
    if ( p == null ) {
      return null;
    }
    try {
      Class<?> principal = p.getMainType();
      String clase = principal == null ? null : p.getClassMap().get( principal );
      if ( clase == null && !p.getClassMap().isEmpty() ) {
        clase = p.getClassMap().values().iterator().next();
      }
      if ( clase == null ) {
        return null;
      }
      Class<?> c = Class.forName( clase, false, cargador( p ) );
      for ( Annotation a : c.getAnnotations() ) {
        String n = a.annotationType().getName();
        if ( "org.pentaho.di.core.annotations.Step".equals( n ) || "org.pentaho.di.core.annotations.JobEntry"
          .equals( n ) ) {
          String paquete = texto( a, "i18nPackageName" );
          return new String[] { clave( texto( a, "name" ), paquete ), clave( texto( a, "description" ), paquete ) };
        }
      }
    } catch ( Throwable ignorada ) {
      // sin anotacion legible
    }
    return null;
  }

  private static String clave( String valor, String paquete ) {
    if ( valor == null || valor.trim().isEmpty() ) {
      return null;
    }
    Matcher m = I18N.matcher( valor.trim() );
    if ( m.matches() ) {
      return m.group( 1 ) + ":" + m.group( 2 );
    }
    if ( paquete == null || paquete.trim().isEmpty() ) {
      return "=" + valor;
    }
    return paquete.trim() + ":" + valor.trim();
  }

  private static String texto( Annotation a, String metodo ) {
    try {
      Method m = a.annotationType().getMethod( metodo );
      Object v = m.invoke( a );
      return v == null ? null : v.toString();
    } catch ( Throwable e ) {
      return null;
    }
  }

  private static ClassLoader cargador( PluginInterface p ) {
    try {
      if ( p != null ) {
        ClassLoader cl = PluginRegistry.getInstance().getClassLoader( p );
        if ( cl != null ) {
          return cl;
        }
      }
    } catch ( Throwable ignorada ) {
      // el de Kettle
    }
    return StepMeta.class.getClassLoader();
  }

  /** Los kettle-steps.xml y kettle-job-entries.xml del motor, leidos una vez. */
  private static Map<String, String[]> xml() {
    Map<String, String[]> m = xml;
    if ( m != null ) {
      return m;
    }
    Map<String, String[]> nuevo = new HashMap<String, String[]>();
    for ( String recurso : Arrays.asList( "kettle-steps.xml", "kettle-job-entries.xml" ) ) {
      String t = leer( StepMeta.class.getClassLoader(), recurso );
      if ( t == null ) {
        continue;
      }
      Matcher e = XML_STEP.matcher( t );
      while ( e.find() ) {
        String tipo = "step".equals( e.group( 1 ) ) ? "step" : "job";
        Matcher d = XML_DESC.matcher( e.group( 3 ) );
        Matcher tt = XML_TIP.matcher( e.group( 3 ) );
        String desc = d.find() ? d.group( 1 ) : null;
        String tip = tt.find() ? tt.group( 1 ) : null;
        nuevo.put( tipo + "|" + e.group( 2 ), new String[] { clave( desc, null ), clave( tip, null ) } );
      }
    }
    xml = Collections.unmodifiableMap( nuevo );
    return xml;
  }

  /** Locales a probar por idioma: el de Spoon de cada pais y despues otra variante del mismo idioma. */
  private static List<String> sufijos( String idioma ) {
    if ( "es".equals( idioma ) ) {
      return Arrays.asList( "es_ES", "es_AR", "es" );
    }
    if ( "pt".equals( idioma ) ) {
      return Arrays.asList( "pt_BR", "pt_PT", "pt" );
    }
    return Arrays.asList( "en_US", "en", "" );
  }

  private static String traducir( String paquete, String clave, String idioma, ClassLoader cl ) {
    String base = paquete.replace( '.', '/' ) + "/messages/messages";
    for ( String s : sufijos( idioma ) ) {
      Properties p = bundle( cl, base + ( s.isEmpty() ? "" : "_" + s ) + ".properties" );
      String v = limpiar( p.getProperty( clave ) );
      if ( v != null ) {
        return v;
      }
    }
    return null;
  }

  private static Properties bundle( ClassLoader cl, String recurso ) {
    String k = System.identityHashCode( cl ) + "|" + recurso;
    Properties p = BUNDLES.get( k );
    if ( p == null ) {
      String t = leer( cl, recurso );
      p = new Properties();
      if ( t != null ) {
        try {
          p.load( new StringReader( t ) );
        } catch ( IOException | RuntimeException e ) {
          p = VACIO;
        }
      }
      BUNDLES.put( k, p );
    }
    return p;
  }

  /** El recurso como texto: UTF-8 si es valido, si no ISO-8859-1 (los .properties viejos de Kettle). */
  private static String leer( ClassLoader cl, String recurso ) {
    if ( cl == null ) {
      return null;
    }
    try ( InputStream in = cl.getResourceAsStream( recurso ) ) {
      if ( in == null ) {
        return null;
      }
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] buf = new byte[ 8192 ];
      int n;
      while ( ( n = in.read( buf ) ) > 0 ) {
        bo.write( buf, 0, n );
      }
      byte[] b = bo.toByteArray();
      try {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput( CodingErrorAction.REPORT )
          .onUnmappableCharacter( CodingErrorAction.REPORT ).decode( ByteBuffer.wrap( b ) ).toString();
      } catch ( CharacterCodingException e ) {
        return new String( b, StandardCharsets.ISO_8859_1 );
      }
    } catch ( IOException | RuntimeException e ) {
      return null;
    }
  }

  /** Sin los {0} de los mensajes de Kettle, en una linea; null si queda vacio. */
  private static String limpiar( String s ) {
    if ( s == null ) {
      return null;
    }
    String t = s.replaceAll( "\\{\\d+\\}", " " ).replaceAll( "\\s+", " " ).trim();
    return t.isEmpty() ? null : t;
  }
}
