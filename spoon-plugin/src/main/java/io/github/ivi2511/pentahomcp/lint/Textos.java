package io.github.ivi2511.pentahomcp.lint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Textos del linter en en/es/pt: lint/textos/lint_{idioma}.properties, en UTF-8. Claves <id>.title, <id>.why,
 * <id>.fix (catálogo, pentaho://lint/rules) y <id>.msg[.variante] (hallazgos). Los marcadores van como
 * {nombre}. Si una clave falta en es/pt se usa la de en; si falta en en, la clave misma.
 */
public final class Textos {

  public static final List<String> IDIOMAS = Collections.unmodifiableList( Arrays.asList( "en", "es", "pt" ) );
  private static final String CARPETA = "io/github/ivi2511/pentahomcp/lint/textos/lint_";
  private static final Map<String, Textos> CACHE = new HashMap<String, Textos>();

  private final String idioma;
  private final Properties props;
  private final Properties base;

  private Textos( String idioma, Properties props, Properties base ) {
    this.idioma = idioma;
    this.props = props;
    this.base = base;
  }

  /** en, es o pt; cualquier otra cosa (o pt_BR, es_AR…) se lleva al idioma base o a en. */
  public static String normalizar( String lang ) {
    if ( lang == null ) {
      return "en";
    }
    String l = lang.trim().toLowerCase( Locale.ROOT );
    if ( l.length() > 2 ) {
      l = l.substring( 0, 2 );
    }
    return IDIOMAS.contains( l ) ? l : "en";
  }

  public static synchronized Textos de( String lang ) {
    String l = normalizar( lang );
    Textos t = CACHE.get( l );
    if ( t == null ) {
      Properties en = cargar( "en" );
      t = new Textos( l, l.equals( "en" ) ? en : cargar( l ), en );
      CACHE.put( l, t );
    }
    return t;
  }

  public String idioma() {
    return idioma;
  }

  public boolean tiene( String clave ) {
    return props.getProperty( clave ) != null || base.getProperty( clave ) != null;
  }

  /** Solo las claves de este idioma (para controlar que los tres tengan las mismas). */
  public java.util.Set<String> claves() {
    return props.stringPropertyNames();
  }

  /** t("unsorted_input.msg.source", "step", "Group by", "keys", "a, b") */
  public String t( String clave, Object... pares ) {
    String s = props.getProperty( clave );
    if ( s == null ) {
      s = base.getProperty( clave );
    }
    if ( s == null ) {
      return clave;
    }
    for ( int i = 0; i + 1 < pares.length; i += 2 ) {
      s = s.replace( "{" + pares[ i ] + "}", pares[ i + 1 ] == null ? "" : String.valueOf( pares[ i + 1 ] ) );
    }
    return s;
  }

  private static Properties cargar( String idioma ) {
    Properties p = new Properties();
    ClassLoader cl = Textos.class.getClassLoader();
    try ( InputStream in = cl == null ? null : cl.getResourceAsStream( CARPETA + idioma + ".properties" ) ) {
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
      // sin textos: t() devuelve la clave
    }
    return p;
  }
}
