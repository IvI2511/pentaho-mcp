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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

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
