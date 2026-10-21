package io.github.ivi2511.pentahomcp.informes.render;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Ayudas de las pruebas de Word, Excel e imagenes (PruebaDocx, PruebaXlsx, PruebaImagenes):
 * <ul>
 * <li>{@link #grande()}: un informe del tamano del «Ejercicio 2» de un usuario (26 steps, lienzo muy ancho,
 * linaje muy alto, 20 tablas en el diccionario, 29 filas de metricas), con sus PNG de Mermaid
 * ({@link #pngGrande}).</li>
 * <li>{@link #aPdf}: convierte con LibreOffice headless (perfil temporal propio) si esta instalado.</li>
 * <li>{@link #paginas}: lee un PDF con el iText 2.1.7 de PDI (por reflexion, no esta en el classpath de las
 * pruebas): texto de cada pagina y cuantas imagenes dibuja (operador Do).</li>
 * </ul>
 * No es una prueba (el runner solo toma las clases Prueba*).
 *
 * @dueño WP10
 */
final class AyudaOffice {

  static final String LINAJE_FIGURA = "lineage-hacia-cargar";

  private AyudaOffice() {
  }

  /** {@code <Salida>\prueba\salida\<sub>} (lo mismo que EntornoKettle.salida, sin cargar Kettle). */
  static File carpeta( String sub ) {
    String p = System.getProperty( "pruebas.dir" );
    File base = p == null ? new File( "" ).getAbsoluteFile() : new File( p ).getAbsoluteFile();
    File f = new File( new File( base, "salida" ), sub );
    f.mkdirs();
    return f;
  }

  // ------------------------------------------------------------------ el informe grande

  /** ModeloMuestra estirado al tamano de un ejercicio real de 26 steps. */
  static Informe grande() {
    Informe inf = ModeloMuestra.crear();
    inf.titulo = "Ejercicio de prueba: carga del data warehouse del gimnasio";
    // flujo: lienzo muy ancho (una tira) y 26 steps
    Seccion flujo = inf.seccion( SeccionId.FLUJO );
    Diagrama d = (Diagrama) flujo.bloques.get( 0 );
    d.imagen = new Imagen( Imagen.PNG, ModeloMuestra.png( 3200, 440, "Lienzo ancho (escala 2)" ), 3200, 440, 2f );
    Tabla pasos = (Tabla) flujo.bloques.get( 1 );
    pasos.filas.clear();
    for ( int i = 1; i <= 26; i++ ) {
      pasos.fila( "Paso " + i, i % 3 == 0 ? "Table output" : "Select values", "Hace la parte " + i
        + " de la carga, con un texto algo largo para que la celda ocupe dos lineas en la pagina." );
    }
    // linaje: titulo largo, explicacion, aviso y un diagrama muy alto
    Seccion linaje = inf.seccion( SeccionId.LINAJE );
    linaje.resumen = "26 campos llegan a «Cargar ventas»; 4 se calculan, 2 se buscan en otra tabla y 1 se agrega.";
    Diagrama dl = (Diagrama) linaje.bloques.get( 0 );
    dl.id = LINAJE_FIGURA;
    dl.titulo = "Linaje hacia «Cargar ventas»";
    linaje.bloques.add( 0, new Parrafo( "Para qué sirve: muestra de dónde sale cada campo que llega al step final, "
      + "por qué steps pasa y qué operación lo cambia. Sirve para encontrar el origen de un dato raro y para saber "
      + "qué se rompe si cambias una columna de la entrada." ) );
    Tabla campos = (Tabla) bloque( linaje, "lineage-tabla" );
    for ( int i = 1; i <= 26; i++ ) {
      campos.fila( "campo_" + i, "Leer entrada › col_" + i, i % 4 == 0 ? "calcula" : "pasa", "pass" );
    }
    // diccionario: 20 tablas
    Seccion dic = inf.seccion( SeccionId.DICCIONARIO );
    for ( int k = 1; k <= 20; k++ ) {
      Tabla t = new Tabla( "Campo", "Tipo", "Largo", "Formato" );
      t.id = "dictionary-anexo-" + k;
      t.titulo = "Step " + k;
      t.alineacion = new ArrayList<>( Arrays.asList( "l", "l", "r", "l" ) );
      for ( int i = 1; i <= 8; i++ ) {
        t.fila( "campo_" + i, i % 2 == 0 ? "Integer" : "String", String.valueOf( i * 5 ), "" );
      }
      dic.bloques.add( t );
    }
    // metricas: 29 filas
    Seccion met = inf.seccion( SeccionId.METRICAS );
    Tabla mt = (Tabla) bloque( met, "metrics-tabla" );
    mt.filas.clear();
    for ( int i = 1; i <= 29; i++ ) {
      mt.fila( "Paso " + i, "0", String.valueOf( i * 1000 ), String.valueOf( i * 1000 ), "0", "Finished", i + ",0 s" );
    }
    Aviso a = new Aviso( Aviso.INFO, "Ejecución corta: la línea de tiempo es aproximada." );
    a.id = "metrics-corta";
    met.bloques.add( a );
    return inf;
  }

  /** PNG de Mermaid del informe grande: el flujo ancho y el linaje muy alto (escala 2). */
  static Map<String, byte[]> pngGrande( Informe inf ) {
    Map<String, byte[]> m = new LinkedHashMap<>();
    for ( Diagrama d : ModeloMuestra.diagramas( inf ) ) {
      if ( d.mermaid == null ) {
        continue;
      }
      if ( LINAJE_FIGURA.equals( d.id ) ) {
        m.put( d.id, ModeloMuestra.png( 900, 3200, "Linaje alto" ) );
      } else if ( Diagrama.FLUJO.equals( d.clase ) ) {
        m.put( d.id, ModeloMuestra.png( 2600, 700, "Flujo" ) );
      } else {
        m.put( d.id, ModeloMuestra.png( 1200, 800, "Diagrama " + d.clase ) );
      }
    }
    return m;
  }

  static Bloque bloque( Seccion s, String id ) {
    for ( Bloque b : s.bloques ) {
      if ( id.equals( b.id ) ) {
        return b;
      }
    }
    throw new IllegalStateException( "falta el bloque " + id );
  }

  // ------------------------------------------------------------------ LibreOffice

  /** soffice.com (Windows) o soffice; null si LibreOffice no esta instalado. */
  static File soffice() {
    List<File> c = new ArrayList<>();
    for ( String v : new String[] { "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432" } ) {
      String pf = System.getenv( v );
      if ( pf != null ) {
        c.add( new File( pf, "LibreOffice\\program\\soffice.com" ) );
      }
    }
    c.add( new File( "/usr/bin/soffice" ) );
    c.add( new File( "/Applications/LibreOffice.app/Contents/MacOS/soffice" ) );
    for ( File f : c ) {
      if ( f.isFile() ) {
        return f;
      }
    }
    return null;
  }

  /**
   * Convierte a PDF con LibreOffice headless y un perfil propio en la carpeta de pruebas (no toca el del
   * usuario ni choca con un LibreOffice abierto). Devuelve el PDF, o null si no hay LibreOffice; falla si
   * LibreOffice esta pero no lo convierte.
   */
  static File aPdf( File archivo, File carpetaPdf ) throws Exception {
    File exe = soffice();
    if ( exe == null ) {
      return null;
    }
    carpetaPdf.mkdirs();
    String nombre = archivo.getName();
    File pdf = new File( carpetaPdf, nombre.substring( 0, nombre.lastIndexOf( '.' ) ) + ".pdf" );
    if ( pdf.exists() && !pdf.delete() ) {
      throw new IllegalStateException( "no se pudo borrar " + pdf );
    }
    File perfil = new File( carpeta( "lo-perfil" ), "u" );
    String url = perfil.toURI().toASCIIString().replaceFirst( "^file:/+", "file:///" );
    ProcessBuilder pb = new ProcessBuilder( exe.getAbsolutePath(), "--headless", "--norestore", "-env:UserInstallation="
      + url, "--convert-to", "pdf", "--outdir", carpetaPdf.getAbsolutePath(), archivo.getAbsolutePath() );
    pb.redirectErrorStream( true );
    Process pr = pb.start();
    StringBuilder salida = new StringBuilder();
    try ( BufferedReader br = new BufferedReader( new InputStreamReader( pr.getInputStream(),
      StandardCharsets.UTF_8 ) ) ) {
      String l;
      while ( ( l = br.readLine() ) != null ) {
        salida.append( l ).append( '\n' );
      }
    }
    if ( !pr.waitFor( 180, TimeUnit.SECONDS ) ) {
      pr.destroyForcibly();
      throw new IllegalStateException( "LibreOffice no termino en 180 s: " + salida );
    }
    if ( !pdf.isFile() ) {
      throw new IllegalStateException( "LibreOffice no escribio " + pdf + " (codigo " + pr.exitValue() + "): " + salida );
    }
    return pdf;
  }

  // ------------------------------------------------------------------ paginas de un PDF

  /** Una pagina: su numero (desde 1), el texto y cuantas imagenes o formularios dibuja. */
  static final class Pagina {
    final int numero;
    final String texto;
    final int imagenes;

    Pagina( int numero, String texto, int imagenes ) {
      this.numero = numero;
      this.texto = texto;
      this.imagenes = imagenes;
    }

    @Override
    public String toString() {
      String t = texto.replace( '\n', '|' );
      return "p" + numero + " (" + imagenes + " img) " + ( t.length() > 120 ? t.substring( 0, 120 ) + "..." : t );
    }
  }

  private static final Pattern DO = Pattern.compile( "/[^\\s/\\[\\]()<>]+\\s+Do\\b" );

  /** Las paginas del PDF leidas con el iText 2.1.7 de PDI; null si no esta el jar. */
  static List<Pagina> paginas( File pdf ) throws Exception {
    String pdi = System.getProperty( "pruebas.pdi" );
    File jar = pdi == null ? null : new File( pdi, "plugins/pdi-core-plugins/lib/itext-2.1.7.jar" );
    if ( jar == null || !jar.isFile() ) {
      return null;
    }
    try ( URLClassLoader cl = new URLClassLoader( new URL[] { jar.toURI().toURL() }, AyudaOffice.class
      .getClassLoader() ) ) {
      Class<?> cR = cl.loadClass( "com.lowagie.text.pdf.PdfReader" );
      Object r = cR.getConstructor( String.class ).newInstance( pdf.getAbsolutePath() );
      try {
        int n = (Integer) cR.getMethod( "getNumberOfPages" ).invoke( r );
        Class<?> cX = cl.loadClass( "com.lowagie.text.pdf.parser.PdfTextExtractor" );
        Object x = cX.getConstructor( cR ).newInstance( r );
        Method texto = cX.getMethod( "getTextFromPage", int.class );
        Method contenido = cR.getMethod( "getPageContent", int.class );
        List<Pagina> l = new ArrayList<>();
        for ( int i = 1; i <= n; i++ ) {
          String t = (String) texto.invoke( x, i );
          String c = new String( (byte[]) contenido.invoke( r, i ), StandardCharsets.ISO_8859_1 );
          Matcher m = DO.matcher( c );
          int dos = 0;
          while ( m.find() ) {
            dos++;
          }
          l.add( new Pagina( i, t == null ? "" : t, dos ) );
        }
        return l;
      } finally {
        cR.getMethod( "close" ).invoke( r );
      }
    }
  }

  /**
   * El texto de la pagina sin el encabezado (el titulo del informe) ni el pie («Pentaho Live · IDC Studios»,
   * «Pagina X de Y»): lo que queda es el contenido.
   */
  static String contenido( Pagina p, String tituloInforme ) {
    StringBuilder b = new StringBuilder();
    for ( String l : p.texto.split( "\r?\n" ) ) {
      String x = l.trim();
      if ( x.isEmpty() || x.equals( tituloInforme ) || x.contains( "IDC Studios" ) ) {
        continue;
      }
      b.append( x ).append( '\n' );
    }
    return b.toString().trim();
  }
}
