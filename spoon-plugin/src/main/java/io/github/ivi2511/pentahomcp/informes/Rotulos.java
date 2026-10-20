package io.github.ivi2511.pentahomcp.informes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Textos visibles por area e idioma (diseno §5.4). Lee
 * {@code io/github/ivi2511/pentahomcp/informes/textos/<area>_<en|es|pt>.properties} del classpath
 * <b>en UTF-8</b> (el ResourceBundle de Java 8 lee ISO-8859-1).
 * <p>
 * Si falta la clave en el idioma pedido se usa la de "en"; si tampoco esta, se devuelve la clave tal
 * cual (nunca null, nunca excepcion). Cada paquete de trabajo es dueno de su area.
 *
 * @dueño WP0
 */
public final class Rotulos {

  /** Carpeta de los .properties dentro del classpath. */
  public static final String CARPETA = "io/github/ivi2511/pentahomcp/informes/textos/";

  private static final ConcurrentHashMap<String, Rotulos> CACHE = new ConcurrentHashMap<>();

  private final String area;
  private final String idioma;
  private final Locale locale;
  private final Properties propio;
  private final Properties ingles;

  private Rotulos( String area, String idioma ) {
    this.area = area;
    this.idioma = idioma;
    this.locale = locale( idioma );
    this.propio = cargar( area, idioma );
    this.ingles = "en".equals( idioma ) ? propio : cargar( area, "en" );
  }

  /** Los textos de un area ("ui", "estructura", "linaje"...) en un idioma (en | es | pt; otro = en). */
  public static Rotulos de( String area, String idioma ) {
    final String a = area == null ? "" : area;
    final String i = normalizar( idioma );
    return CACHE.computeIfAbsent( a + "_" + i, k -> new Rotulos( a, i ) );
  }

  /** "es", "es_AR", "ES" -> "es"; "pt_BR" -> "pt"; null u otro -> "en". */
  public static String normalizar( String idioma ) {
    if ( idioma == null ) {
      return "en";
    }
    String i = idioma.trim().toLowerCase( Locale.ROOT );
    if ( i.startsWith( "es" ) ) {
      return "es";
    }
    if ( i.startsWith( "pt" ) ) {
      return "pt";
    }
    return "en";
  }

  /** Locale para formatear numeros y fechas: en -> en_US, es -> es_ES, pt -> pt_BR. */
  public static Locale locale( String idioma ) {
    String i = normalizar( idioma );
    if ( "es".equals( i ) ) {
      return new Locale( "es", "ES" );
    }
    if ( "pt".equals( i ) ) {
      return new Locale( "pt", "BR" );
    }
    return Locale.US;
  }

  /** El texto de la clave; si no existe en ningun idioma, la clave misma. */
  public String s( String clave ) {
    String v = propio.getProperty( clave );
    if ( v == null ) {
      v = ingles.getProperty( clave );
    }
    return v == null ? clave : v;
  }

  /**
   * El texto de la clave con {@code String.format( locale(), texto, args )}: %s, %d, %,d (miles con el
   * separador del idioma), %.1f (decimales con la coma o el punto del idioma). Un "%" literal va como
   * "%%". Si el formato no cierra, devuelve el texto y los argumentos tal cual (nunca excepcion).
   */
  public String f( String clave, Object... args ) {
    String patron = s( clave );
    try {
      return String.format( locale, patron, args );
    } catch ( IllegalFormatException e ) {
      StringBuilder sb = new StringBuilder( patron );
      for ( Object a : args ) {
        sb.append( ' ' ).append( a );
      }
      return sb.toString();
    }
  }

  /** El texto solo en el idioma pedido, sin caer al ingles; null si ese idioma no lo tiene. */
  public String propio( String clave ) {
    return propio.getProperty( clave );
  }

  /** true si la clave existe en el idioma pedido o en ingles. */
  public boolean tiene( String clave ) {
    return propio.getProperty( clave ) != null || ingles.getProperty( clave ) != null;
  }

  public String area() {
    return area;
  }

  public String idioma() {
    return idioma;
  }

  public Locale locale() {
    return locale;
  }

  private static Properties cargar( String area, String idioma ) {
    Properties p = new Properties();
    ClassLoader cl = Rotulos.class.getClassLoader();
    String recurso = CARPETA + area + "_" + idioma + ".properties";
    try ( InputStream in = cl == null ? null : cl.getResourceAsStream( recurso ) ) {
      if ( in == null ) {
        return p;
      }
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] buf = new byte[ 8192 ];
      int n;
      while ( ( n = in.read( buf ) ) > 0 ) {
        bo.write( buf, 0, n );
      }
      String texto = new String( bo.toByteArray(), StandardCharsets.UTF_8 );
      if ( texto.startsWith( "﻿" ) ) {
        texto = texto.substring( 1 );
      }
      p.load( new StringReader( texto ) );
    } catch ( IOException | RuntimeException e ) {
      // sin textos: s() devuelve la clave
    }
    return p;
  }
}
