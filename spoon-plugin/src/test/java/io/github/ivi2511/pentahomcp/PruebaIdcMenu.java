package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import io.github.ivi2511.pentahomcp.IdcMenu.Contexto;
import io.github.ivi2511.pentahomcp.IdcMenu.Entrada;
import io.github.ivi2511.pentahomcp.IdcMenu.Tipo;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * El modelo del menú IDC ({@link IdcMenu#modelo}) sin SWT ni Spoon: qué entradas hay, en qué orden, cuáles están
 * habilitadas según el documento activo, la lección y las perspectivas del selector de Spoon; y sus textos en los
 * tres idiomas (las mismas claves en en/es/pt, español de España con tú).
 */
public class PruebaIdcMenu {

  private final Rotulos es = Rotulos.de( IdcMenu.AREA, "es" );

  private static Contexto contexto( String doc, boolean leccion, String activa, String... perspectivas ) {
    Contexto c = new Contexto();
    c.documento = doc;
    c.leccion = leccion;
    c.activa = activa;
    for ( int i = 0; i + 1 < perspectivas.length; i += 2 ) {
      c.perspectivas.add( new String[] { perspectivas[ i ], perspectivas[ i + 1 ] } );
    }
    return c;
  }

  private static List<String> ids( List<Entrada> l ) {
    List<String> r = new ArrayList<String>();
    for ( Entrada e : l ) {
      r.add( e.tipo == Tipo.SEPARADOR ? "---" : e.id );
    }
    return r;
  }

  private static Entrada de( List<Entrada> l, String id ) {
    for ( Entrada e : l ) {
      if ( id.equals( e.id ) ) {
        return e;
      }
    }
    throw new AssertionError( "no hay " + id + " en " + l );
  }

  /** Transformación abierta en Data Integration: todo habilitado, perspectivas con la activa marcada, Acerca al final. */
  public void pruebaA_Transformacion() {
    List<Entrada> l = IdcMenu.modelo( es, contexto( IdcMenu.TRANSFORMACION, false, "001-spoon-jobs",
      "001-spoon-jobs", "Data Integration", "pentaho-live-reports", "Informes", "pentaho-live-grader", "Corrector" ) );
    igual( Arrays.asList( "quality", "harden", "arrange", "lesson", "project", "---", "perspective:001-spoon-jobs",
      "perspective:pentaho-live-reports", "perspective:pentaho-live-grader", "---", "about" ), ids( l ),
      "orden del menú" );
    for ( Entrada e : l ) {
      cierto( e.habilitada, "habilitada: " + e );
    }
    igual( "Revisar calidad…", de( l, "quality" ).texto, "texto calidad" );
    igual( "Robustecer…", de( l, "harden" ).texto, "texto robustecer" );
    igual( "Ordenar lienzo", de( l, "arrange" ).texto, "texto ordenar" );
    igual( "Lección…", de( l, "lesson" ).texto, "sin lección cargada" );
    igual( "Acerca de Pentaho Live", de( l, "about" ).texto, "texto acerca" );
    cierto( de( l, "perspective:001-spoon-jobs" ).marcada, "Data Integration marcada" );
    falso( de( l, "perspective:pentaho-live-reports" ).marcada, "Informes sin marcar" );
    igual( Tipo.PERSPECTIVA, de( l, "perspective:pentaho-live-grader" ).tipo, "tipo perspectiva" );
    igual( "Corrector", de( l, "perspective:pentaho-live-grader" ).texto, "nombre de la perspectiva" );
    contiene( de( l, "harden" ).ayuda, "datos sucios", "ayuda de robustecer" );
  }

  /** Un job: Robustecer deshabilitado y dicho; con Informes activa, esa queda marcada. */
  public void pruebaB_Job() {
    List<Entrada> l = IdcMenu.modelo( es, contexto( IdcMenu.JOB, false, "pentaho-live-reports", "001-spoon-jobs",
      "Data Integration", "pentaho-live-reports", "Informes" ) );
    cierto( de( l, "quality" ).habilitada, "calidad sirve para jobs" );
    falso( de( l, "harden" ).habilitada, "robustecer solo transformaciones" );
    igual( "Robustecer… (solo transformaciones)", de( l, "harden" ).texto, "lo dice" );
    contiene( de( l, "harden" ).ayuda, "job_guard", "y por qué" );
    cierto( de( l, "arrange" ).habilitada, "ordenar sirve para jobs" );
    cierto( de( l, "perspective:pentaho-live-reports" ).marcada, "Informes marcada" );
    falso( de( l, "perspective:001-spoon-jobs" ).marcada, "DI sin marcar" );
  }

  /** Nada abierto: solo Lección, perspectivas y Acerca habilitadas. Sin perspectivas, un solo separador. */
  public void pruebaC_SinDocumento() {
    List<Entrada> l = IdcMenu.modelo( es, contexto( null, false, null ) );
    igual( Arrays.asList( "quality", "harden", "arrange", "lesson", "project", "---", "about" ), ids( l ),
      "sin perspectivas no hay bloque ni separador de más" );
    falso( de( l, "quality" ).habilitada, "calidad" );
    falso( de( l, "harden" ).habilitada, "robustecer" );
    falso( de( l, "arrange" ).habilitada, "ordenar" );
    cierto( de( l, "lesson" ).habilitada, "lección explica cómo empezar una" );
    cierto( de( l, "about" ).habilitada, "acerca" );
    igual( es.s( "no_document" ), de( l, "quality" ).ayuda, "ayuda: abre un documento" );
  }

  /** Lección viva: la entrada dice que muestra la pestaña. */
  public void pruebaD_LeccionViva() {
    List<Entrada> l = IdcMenu.modelo( es, contexto( IdcMenu.TRANSFORMACION, true, null ) );
    igual( "Lección: mostrar la pestaña", de( l, "lesson" ).texto, "lección cargada" );
    List<Entrada> en = IdcMenu.modelo( Rotulos.de( IdcMenu.AREA, "en" ), contexto( IdcMenu.TRANSFORMACION, true,
      null ) );
    igual( "Lesson: show the tab", de( en, "lesson" ).texto, "en inglés" );
    igual( "Check quality…", de( en, "quality" ).texto, "en inglés" );
    List<Entrada> pt = IdcMenu.modelo( Rotulos.de( IdcMenu.AREA, "pt" ), contexto( IdcMenu.JOB, false, null ) );
    igual( "Robustecer… (só transformações)", de( pt, "harden" ).texto, "en portugués" );
    igual( "Sobre o Pentaho Live", de( pt, "about" ).texto, "en portugués" );
  }

  /** Las perspectivas del selector: sin las ocultas ni las que el selector no tiene; sin datos, todas. */
  public void pruebaE_PerspectivasVisibles() {
    List<String[]> reg = new ArrayList<String[]>();
    reg.add( new String[] { "001-spoon-jobs", "Data Integration" } );
    reg.add( new String[] { "schedulerPerspective", "Schedule" } );
    reg.add( new String[] { "pentaho-live-reports", "Informes" } );
    reg.add( new String[] { "rota", "Rota" } );
    Map<String, Boolean> ocultas = new LinkedHashMap<String, Boolean>();
    ocultas.put( "001-spoon-jobs", false );
    ocultas.put( "schedulerPerspective", true );
    ocultas.put( "pentaho-live-reports", false );
    List<String[]> v = IdcMenu.perspectivasVisibles( reg, ocultas );
    igual( 2, v.size(), "sin la oculta ni la que no se instaló" );
    igual( "Data Integration", v.get( 0 )[ 1 ], "orden de Spoon" );
    igual( "Informes", v.get( 1 )[ 1 ], "orden de Spoon" );
    igual( 4, IdcMenu.perspectivasVisibles( reg, null ).size(), "sin datos del selector: todas" );
    igual( 4, IdcMenu.perspectivasVisibles( reg, new LinkedHashMap<String, Boolean>() ).size(), "mapa vacío: todas" );
  }

  /** Mismas claves en en/es/pt y nada sin traducir; el castellano con tú, sin voseo. */
  public void pruebaF_Textos() throws Exception {
    Properties en = cargar( "en" );
    Properties esp = cargar( "es" );
    Properties pt = cargar( "pt" );
    igual( new TreeSet<Object>( en.keySet() ), new TreeSet<Object>( esp.keySet() ), "claves es = en" );
    igual( new TreeSet<Object>( en.keySet() ), new TreeSet<Object>( pt.keySet() ), "claves pt = en" );
    cierto( en.size() > 100, "textos: " + en.size() );
    for ( String k : esp.stringPropertyNames() ) {
      String v = " " + esp.getProperty( k ) + " ";
      for ( String voseo : new String[] { " tenés", " podés", " abrí ", " mirá ", " pedile", " pedíselo", " vos ",
        " revisalas", " guardá", " hacé ", " ejecutá" } ) {
        noContiene( v, voseo, "sin voseo en idc_es " + k );
      }
    }
    contiene( esp.getProperty( "lesson_none" ), "pídesela", "tú: pídesela" );
    contiene( esp.getProperty( "r_intro" ), "revísalas", "tú: revísalas" );
    // los textos con formato cierran con sus argumentos
    Rotulos r = Rotulos.de( IdcMenu.AREA, "es" );
    igual( "Calidad · ventas", r.f( "q_title", "ventas" ), "q_title" );
    igual( "82/100 · B", r.f( "q_score", 82, "B" ), "q_score" );
    contiene( r.f( "q_deep_ran", 3, 1.5, 1, 0, 2 ), "1,5 s", "decimales con coma" );
    contiene( r.s( "lesson_none" ), System.lineSeparator(), "%n es salto de línea" );
  }

  private static Properties cargar( String idioma ) throws Exception {
    Properties p = new Properties();
    try ( InputStream in = PruebaIdcMenu.class.getResourceAsStream( "/" + Rotulos.CARPETA + IdcMenu.AREA + "_"
      + idioma + ".properties" ) ) {
      cierto( in != null, "existe idc_" + idioma );
      p.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    return p;
  }
}
