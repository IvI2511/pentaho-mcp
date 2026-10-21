package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Textos de los informes: ningun texto visible deja ver un «%n» literal. El panel mostraba la
 * confirmacion de «consultar la base» con {@code Rotulos.s()}, que no formatea, y salia «%n%n».
 *
 * @dueño WP0
 */
public class PruebaTextos {

  /** Todo texto con %n, leido con s() o con f(), sale con saltos de linea, en los tres idiomas. */
  public void pruebaSinPorcientoNLiteral() throws Exception {
    int conSalto = 0;
    for ( String archivo : archivos() ) {
      String area = archivo.substring( 0, archivo.lastIndexOf( '_' ) );
      String idioma = archivo.substring( archivo.lastIndexOf( '_' ) + 1, archivo.indexOf( '.' ) );
      Properties p = cargar( archivo );
      Rotulos r = Rotulos.de( area, idioma );
      for ( String clave : p.stringPropertyNames() ) {
        String crudo = p.getProperty( clave );
        if ( !crudo.contains( "%n" ) ) {
          continue;
        }
        conSalto++;
        String s = r.s( clave );
        noContiene( s, "%n", archivo + " " + clave + " con s()" );
        contiene( s, System.lineSeparator(), archivo + " " + clave + ": el %n es un salto de linea" );
        String f = r.f( clave, "X", "Y", "Z" );
        noContiene( f, "%n", archivo + " " + clave + " con f()" );
      }
    }
    // query_db_confirm, measure_confirm y measure_confirm_nowrite en en/es/pt
    cierto( conSalto >= 9, "textos con %n revisados: " + conSalto );
    Rotulos es = Rotulos.de( "ui", "es" );
    String sl = System.lineSeparator();
    contiene( es.s( "query_db_confirm" ), "nada)." + sl + sl + "¿Seguir?", "la confirmacion de consultar la base" );
    contiene( es.f( "measure_confirm", "Doc", sl + "  - Escribir" ), "medirla." + sl + sl, "medir sigue igual" );
  }

  /** s() solo cambia %n: el resto del patron queda para f() (%% literal, %s, %,d). */
  public void pruebaSaltosNoRompenElFormato() {
    igual( "a" + System.lineSeparator() + "b %s %% %%n", Rotulos.saltos( "a%nb %s %% %%n" ), "solo el %n suelto" );
    igual( "sin_clave%n", Rotulos.de( "x", "es" ).s( "sin_clave%n" ), "una clave que no existe vuelve tal cual" );
    Rotulos e = Rotulos.de( "ejecucion", "es" );
    igual( "Nulos por campo (%)", e.s( "chart.nulls.title" ), "un % suelto queda igual" );
    igual( "El campo con más nulos es «x» (12 %).", e.f( "summary.profile.nulls", "x", "12" ), "y %% sigue andando" );
  }

  /**
   * Las llamadas del codigo: cada {@code s( "clave" )} o {@code t( "clave" )} con la clave escrita lee un texto
   * sin patron, porque s() no formatea y un {@code %s}, {@code %,d} o {@code %%} se veria tal cual (como el
   * «%n%n» de la confirmacion). Los patrones se leen con f(); una clave que en algun lado se lee con f() queda
   * fuera (puede haber la misma clave en dos areas).
   */
  public void pruebaLlamadasConSNoTienenPatron() throws Exception {
    String recursos = System.getProperty( "pruebas.recursos" );
    cierto( recursos != null, "test.ps1 pasa -Dpruebas.recursos" );
    File fuentes = new File( new File( recursos ).getParentFile().getParentFile(), "main" + File.separator + "java" );
    cierto( fuentes.isDirectory(), "fuentes en " + fuentes );
    Map<String, List<String>> textos = new HashMap<>(); // clave -> "archivo: texto" de todos los idiomas
    for ( String archivo : archivos() ) {
      Properties p = cargar( archivo );
      for ( String clave : p.stringPropertyNames() ) {
        List<String> l = textos.get( clave );
        if ( l == null ) {
          l = new ArrayList<>();
          textos.put( clave, l );
        }
        l.add( archivo + ": " + p.getProperty( clave ) );
      }
    }
    Pattern llamadaS = Pattern.compile( "(?<![\\w$])[st]\\(\\s*\"([A-Za-z0-9_.\\-]+)\"\\s*\\)" );
    Pattern llamadaF = Pattern.compile( "(?<![\\w$])f\\(\\s*\"([A-Za-z0-9_.\\-]+)\"" );
    // un especificador de String.format (sin el flag espacio: «% de nulos» no es un patron)
    Pattern patron = Pattern.compile( "%(\\d+\\$)?[-#+0,(]*\\d*(\\.\\d+)?[sdfxXcbeg%]" );
    Map<String, String> conS = new TreeMap<>(); // clave -> archivo .java
    Set<String> conF = new HashSet<>();
    List<File> javas = new ArrayList<>();
    juntar( fuentes, javas );
    cierto( javas.size() > 50, "archivos .java: " + javas.size() );
    for ( File j : javas ) {
      String src = new String( Files.readAllBytes( j.toPath() ), StandardCharsets.UTF_8 );
      Matcher m = llamadaS.matcher( src );
      while ( m.find() ) {
        conS.put( m.group( 1 ), j.getName() );
      }
      m = llamadaF.matcher( src );
      while ( m.find() ) {
        conF.add( m.group( 1 ) );
      }
    }
    int revisadas = 0;
    List<String> mal = new ArrayList<>();
    for ( Map.Entry<String, String> e : conS.entrySet() ) {
      List<String> l = textos.get( e.getKey() );
      if ( l == null || conF.contains( e.getKey() ) ) {
        continue;
      }
      revisadas++;
      for ( String x : l ) {
        String texto = x.substring( x.indexOf( ": " ) + 2 );
        if ( patron.matcher( texto ).find() ) {
          mal.add( e.getValue() + " lee con s() " + x );
        }
      }
    }
    cierto( revisadas > 200, "claves leidas con s() revisadas: " + revisadas );
    cierto( mal.isEmpty(), "textos con patron leidos con s() (usar f()): " + mal );
  }

  private static void juntar( File dir, List<File> out ) {
    File[] hijos = dir.listFiles();
    if ( hijos == null ) {
      return;
    }
    for ( File h : hijos ) {
      if ( h.isDirectory() ) {
        juntar( h, out );
      } else if ( h.getName().endsWith( ".java" ) ) {
        out.add( h );
      }
    }
  }

  // ---------------------------------------------------------------- utilidades

  /** Los .properties de textos/ (de la carpeta de clases: test.ps1 corre desde carpetas, no del jar). */
  private static List<String> archivos() throws Exception {
    URL u = Rotulos.class.getClassLoader().getResource( Rotulos.CARPETA + "ui_es.properties" );
    cierto( u != null && "file".equals( u.getProtocol() ), "textos en una carpeta: " + u );
    File dir = new File( u.toURI() ).getParentFile();
    List<String> l = new ArrayList<>();
    for ( String n : dir.list() ) {
      if ( n.endsWith( ".properties" ) ) {
        l.add( n );
      }
    }
    Collections.sort( l );
    cierto( l.size() >= 30, "archivos de textos: " + l.size() );
    return l;
  }

  private static Properties cargar( String archivo ) throws Exception {
    Properties p = new Properties();
    try ( InputStream in = Rotulos.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA + archivo ) ) {
      p.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    return p;
  }
}
