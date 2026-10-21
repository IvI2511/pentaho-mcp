package io.github.ivi2511.pentahomcp;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ivi2511.pentahomcp.informes.diagramas.Diagramas;
import io.github.ivi2511.pentahomcp.informes.diagramas.Dibujo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Objetivo;

/**
 * Herramienta de desarrollo (no es una prueba: el runner solo corre clases Prueba*): dibuja diagramas
 * propios de transformaciones, jobs y linajes y deja, para mirarlos, los SVG y PNG de cada objetivo y una
 * comparacion "antes / despues" con la pagina o la diapositiva simulada.
 *
 * <pre>
 * java -cp test-classes;classes;&lt;PDI&gt;\lib\* -DKETTLE_HOME=&lt;carpeta de prueba&gt; \
 *   io.github.ivi2511.pentahomcp.MuestrasDiagramas &lt;salida&gt; [--antes &lt;dir&gt;] [--idioma es] entradas...
 * </pre>
 *
 * Entradas: .ktr y .kjb (se leen con TransMeta/JobMeta y {@link GrafoFlujo}; no se ejecutan ni se conectan
 * a ninguna base), JSON de flujo ({"clase", "nodos", "aristas"}) o de linaje ({"grupos", "aristas"}, el
 * ultimo grupo es el destino), o carpetas con esos archivos. En {@code --antes} se busca
 * {@code <caso>_<objetivo>.png} (por ejemplo la pagina del PDF de antes) para ponerla a la izquierda.
 * Salida: {@code despues/}, {@code comparar/} y {@code medidas.txt}.
 *
 * @dueño WP6
 */
public final class MuestrasDiagramas {

  static final Objetivo[] OBJETIVOS = { Objetivo.A4, Objetivo.DIAPOSITIVA, Objetivo.PANTALLA, Objetivo.DOCX };
  /** Resolucion de los PNG respecto del tamano final. */
  static final double FACTOR = 1.5;

  private MuestrasDiagramas() {
  }

  public static void main( String[] args ) throws Exception {
    if ( args.length < 2 ) {
      System.out.println( "uso: MuestrasDiagramas <salida> [--antes <dir>] [--idioma es] entradas..." );
      System.exit( 2 );
    }
    File salida = new File( args[ 0 ] );
    File antes = null;
    String idioma = "es";
    List<File> entradas = new ArrayList<>();
    for ( int i = 1; i < args.length; i++ ) {
      if ( "--antes".equals( args[ i ] ) ) {
        antes = new File( args[ ++i ] );
      } else if ( "--idioma".equals( args[ i ] ) ) {
        idioma = args[ ++i ];
      } else {
        File f = new File( args[ i ] );
        if ( f.isDirectory() ) {
          File[] fs = f.listFiles( ( d, n ) -> n.endsWith( ".json" ) || n.endsWith( ".ktr" ) || n.endsWith( ".kjb" ) );
          Arrays.sort( fs );
          entradas.addAll( Arrays.asList( fs ) );
        } else {
          entradas.add( f );
        }
      }
    }
    File despues = new File( salida, "despues" );
    File comparar = new File( salida, "comparar" );
    despues.mkdirs();
    comparar.mkdirs();
    StringBuilder medidas = new StringBuilder( "caso;objetivo;partes;variantes;tamanos;letra_min;cruces;ms\n" );
    for ( File f : entradas ) {
      String caso = caso( f );
      Grafo g;
      try {
        g = leer( f, idioma );
      } catch ( Exception e ) {
        System.out.println( "== " + caso + ": no se pudo leer (" + e + ")" );
        continue;
      }
      System.out.println( "== " + caso + " (" + g.nodos().size() + " nodos, " + g.aristas().size() + " aristas)" );
      for ( Objetivo obj : OBJETIVOS ) {
        long t0 = System.nanoTime();
        List<Dibujo> ds = Diagramas.dibujar( g, obj, idioma );
        long ms = ( System.nanoTime() - t0 ) / 1000000;
        List<BufferedImage> imgs = new ArrayList<>();
        StringBuilder var = new StringBuilder();
        StringBuilder tam = new StringBuilder();
        double letra = Double.MAX_VALUE;
        int cruces = 0;
        for ( Dibujo d : ds ) {
          String nombre = caso + "_" + obj.nombre + ( ds.size() > 1 ? "_" + d.parte : "" );
          Files.write( new File( despues, nombre + ".svg" ).toPath(), d.svg.getBytes( StandardCharsets.UTF_8 ) );
          byte[] png = d.png( FACTOR );
          Files.write( new File( despues, nombre + ".png" ).toPath(), png );
          imgs.add( ImageIO.read( new ByteArrayInputStream( png ) ) );
          System.out.println( "   " + d );
          var.append( var.length() > 0 ? " | " : "" ).append( d.orientacion ).append( " r=" ).append( d.bandas );
          tam.append( tam.length() > 0 ? " | " : "" ).append( String.format( Locale.ROOT, "%.0fx%.0f", d.ancho,
            d.alto ) );
          letra = Math.min( letra, d.letra );
          cruces += d.cruces;
        }
        medidas.append( caso ).append( ';' ).append( obj.nombre ).append( ';' ).append( ds.size() ).append( ';' )
          .append( var ).append( ';' ).append( tam ).append( ';' ).append( String.format( Locale.ROOT, "%.1f",
            letra ) ).append( ';' ).append( cruces ).append( ';' ).append( ms ).append( '\n' );
        File previo = antes == null ? null : new File( antes, caso + "_" + obj.nombre + ".png" );
        BufferedImage img = componer( obj, ds, imgs, previo != null && previo.isFile() ? ImageIO.read( previo ) : null,
          g.titulo );
        ImageIO.write( img, "png", new File( comparar, caso + "_" + obj.nombre + ".png" ) );
      }
    }
    Files.write( new File( salida, "medidas.txt" ).toPath(), medidas.toString().getBytes( StandardCharsets.UTF_8 ) );
    System.out.println( medidas );
  }

  private static String caso( File f ) {
    String n = f.getName();
    int p = n.lastIndexOf( '.' );
    String base = p > 0 ? n.substring( 0, p ) : n;
    String pref = n.endsWith( ".ktr" ) ? "f_" : n.endsWith( ".kjb" ) ? "j_" : "";
    return pref + base.replaceAll( "[^A-Za-z0-9_.-]+", "_" );
  }

  // ================================================================== entradas

  static Grafo leer( File f, String idioma ) throws Exception {
    String n = f.getName();
    if ( n.endsWith( ".ktr" ) || n.endsWith( ".kjb" ) ) {
      iniciarKettle();
      ReportMotor.T t = new ReportMotor.T( idioma );
      if ( n.endsWith( ".ktr" ) ) {
        TransMeta tm = new TransMeta( f.getAbsolutePath() );
        return GrafoFlujo.desde( tm, ReportMotor.ordenTrans( tm ), t );
      }
      JobMeta jm = new JobMeta( f.getAbsolutePath(), null );
      return GrafoFlujo.desde( jm, ReportMotor.ordenJob( jm ), t );
    }
    JsonNode j = new ObjectMapper().readTree( f );
    return j.has( "grupos" ) ? linaje( j, f ) : flujo( j );
  }

  private static boolean kettle;

  private static void iniciarKettle() throws Exception {
    if ( kettle ) {
      return;
    }
    if ( System.getProperty( "KETTLE_HOME" ) == null ) {
      throw new IllegalStateException( "falta -DKETTLE_HOME=<carpeta de prueba> (no se usa el ~/.kettle real)" );
    }
    KettleClientEnvironment.getInstance().setClient( KettleClientEnvironment.ClientType.SPOON );
    KettleEnvironment.init( false );
    kettle = true;
  }

  /** JSON de flujo: nodos {id, nombre, tipo, copias, distribuye, salidas, inicio} y aristas. */
  static Grafo flujo( JsonNode j ) {
    boolean job = "job".equals( j.path( "clase" ).asText() );
    Grafo g = new Grafo( job ? Grafo.JOB : Grafo.FLUJO, j.path( "titulo" ).asText() );
    for ( JsonNode x : j.path( "nodos" ) ) {
      Grafo.Nodo n = g.nodo( x.path( "id" ).asText(), x.path( "nombre" ).asText() );
      n.copias = x.path( "copias" ).asInt( 1 );
      n.inicio = x.path( "inicio" ).asBoolean( false );
      String tipo = x.path( "tipo" ).asText( "" );
      if ( !n.inicio && !tipo.isEmpty() ) {
        n.lineas.add( tipo );
      }
      int salidas = x.path( "salidas" ).asInt( 0 );
      if ( salidas >= 2 ) {
        n.multi = true;
        n.lineas.add( ( x.path( "distribuye" ).asBoolean( true ) ? "distribuye las filas a " : "copia las filas a " )
          + salidas + " salidas" );
      }
    }
    for ( JsonNode x : j.path( "aristas" ) ) {
      Grafo.Nodo a = g.buscar( x.path( "desde" ).asText() );
      Grafo.Nodo b = g.buscar( x.path( "hacia" ).asText() );
      if ( a == null || b == null ) {
        continue;
      }
      Grafo.Arista e = g.arista( a, b, x.path( "clase" ).asText( Grafo.NORMAL ) );
      e.activa = x.path( "activa" ).asBoolean( true );
      String rot = x.path( "rotulo" ).isNull() ? null : x.path( "rotulo" ).asText( null );
      e.rotulo = job ? ( e.activa ? null : "deshabilitado" ) : rot;
    }
    return g;
  }

  /** JSON de linaje: grupos {nombre, campos {id, nombre}} y aristas {desde, hacia, clave, rotulo}. */
  static Grafo linaje( JsonNode j, File f ) {
    String titulo = j.path( "titulo" ).asText( f.getName() );
    Grafo g = new Grafo( Grafo.LINAJE, new File( titulo ).getName() );
    Map<String, Grafo.Grupo> grupos = new java.util.HashMap<>();
    Grafo.Grupo ultimo = null;
    for ( JsonNode x : j.path( "grupos" ) ) {
      Grafo.Grupo gr = g.grupo( x.path( "nombre" ).asText() );
      grupos.put( x.path( "id" ).asText(), gr );
      for ( JsonNode y : x.path( "campos" ) ) {
        g.campo( gr, y.path( "id" ).asText(), y.path( "nombre" ).asText() );
      }
      ultimo = gr;
    }
    if ( ultimo != null ) {
      ultimo.destino = true;
    }
    Map<Grafo.Nodo, Set<String>> ops = new IdentityHashMap<>();
    for ( JsonNode x : j.path( "aristas" ) ) {
      boolean clave = x.path( "clave" ).asBoolean( false );
      Grafo.Nodo a = g.buscar( x.path( "desde" ).asText() );
      Grafo.Nodo b = g.buscar( x.path( "hacia" ).asText() );
      if ( b == null && grupos.containsKey( x.path( "hacia" ).asText() ) ) {
        List<Grafo.Nodo> cs = grupos.get( x.path( "hacia" ).asText() ).campos();
        b = cs.isEmpty() ? null : cs.get( 0 );
      }
      if ( a == null || b == null ) {
        continue;
      }
      g.arista( a, b, clave ? Grafo.CLAVE : Grafo.NORMAL );
      String r = x.path( "rotulo" ).isNull() ? null : x.path( "rotulo" ).asText( null );
      if ( !clave && r != null ) {
        Set<String> s = ops.get( b );
        if ( s == null ) {
          s = new LinkedHashSet<>();
          ops.put( b, s );
        }
        s.add( r );
      }
    }
    for ( Map.Entry<Grafo.Nodo, Set<String>> e : ops.entrySet() ) {
      e.getKey().op = String.join( "; ", e.getValue() );
    }
    return g;
  }

  // ================================================================== comparacion

  /**
   * Antes (si hay) a la izquierda; a la derecha la simulacion: hojas A4 / Word con las figuras apiladas al
   * tamano final, una diapositiva 16:9 por parte, o la columna de la pagina HTML.
   */
  static BufferedImage componer( Objetivo obj, List<Dibujo> ds, List<BufferedImage> imgs, BufferedImage previo,
                                 String titulo ) {
    double f = FACTOR;
    List<BufferedImage> hojas = new ArrayList<>();
    boolean diapo = obj == Objetivo.DIAPOSITIVA;
    boolean hoja = obj != Objetivo.PANTALLA && !diapo;
    // tamano de la hoja (px a 96 ppp) y origen de la caja util
    double hw = diapo ? 960 : hoja ? ( obj == Objetivo.DOCX ? 794 : 794 ) : 920;
    double hh = diapo ? 540 : hoja ? 1123 : Math.max( 700, totalAlto( ds ) + 140 );
    double x0 = diapo ? 40 : hoja ? ( obj == Objetivo.DOCX ? 94 : 60 ) : 40;
    double y0 = diapo ? 80 : hoja ? 110 : 60;
    BufferedImage actual = null;
    Graphics2D gr = null;
    double y = y0;
    for ( int i = 0; i < ds.size(); i++ ) {
      Dibujo d = ds.get( i );
      double h = d.altoFinal();
      if ( actual == null || diapo || y + h > hh - 60 ) {
        actual = new BufferedImage( (int) ( hw * f ), (int) ( hh * f ), BufferedImage.TYPE_INT_RGB );
        gr = actual.createGraphics();
        gr.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC );
        gr.setRenderingHint( RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON );
        gr.setColor( Color.WHITE );
        gr.fillRect( 0, 0, actual.getWidth(), actual.getHeight() );
        gr.setColor( new Color( 0x24, 0x3B, 0x53 ) );
        gr.setFont( new Font( "SansSerif", Font.BOLD, (int) ( 20 * f ) ) );
        gr.drawString( d.titulo == null ? titulo : d.titulo, (int) ( x0 * f ), (int) ( ( y0 - 30 ) * f ) );
        hojas.add( actual );
        y = y0;
      }
      double caja = obj.ancho;
      double x = x0 + ( caja - d.anchoFinal() ) / 2;
      double yy = diapo ? y0 + ( obj.alto - h ) / 2 : y;
      gr.drawImage( imgs.get( i ), (int) ( x * f ), (int) ( yy * f ), (int) ( d.anchoFinal() * f ), (int) ( h * f ),
        null );
      gr.setColor( new Color( 0xD9, 0xDE, 0xE5 ) );
      gr.drawRect( (int) ( x0 * f ), (int) ( ( diapo ? y0 : yy ) * f ), (int) ( caja * f ), (int) ( ( diapo ? obj.alto
        : h ) * f ) );
      y += h + 30;
    }
    // todo en una tira: antes | hoja 1 | hoja 2 ...
    int alto = 0;
    int ancho = 0;
    BufferedImage antes = previo;
    if ( antes != null ) {
      int hRef = hojas.get( 0 ).getHeight();
      double s = (double) hRef / antes.getHeight();
      BufferedImage a2 = new BufferedImage( Math.max( 1, (int) ( antes.getWidth() * s ) ), hRef,
        BufferedImage.TYPE_INT_RGB );
      Graphics2D g2 = a2.createGraphics();
      g2.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC );
      g2.drawImage( antes, 0, 0, a2.getWidth(), a2.getHeight(), null );
      g2.dispose();
      antes = a2;
      ancho += antes.getWidth() + 30;
      alto = Math.max( alto, antes.getHeight() );
    }
    for ( BufferedImage h : hojas ) {
      ancho += h.getWidth() + 30;
      alto = Math.max( alto, h.getHeight() );
    }
    BufferedImage out = new BufferedImage( Math.max( 1, ancho ), Math.max( 1, alto ), BufferedImage.TYPE_INT_RGB );
    Graphics2D g = out.createGraphics();
    g.setColor( new Color( 0x55, 0x55, 0x55 ) );
    g.fillRect( 0, 0, out.getWidth(), out.getHeight() );
    int x = 0;
    if ( antes != null ) {
      g.drawImage( antes, x, 0, null );
      x += antes.getWidth() + 30;
    }
    for ( BufferedImage h : hojas ) {
      g.drawImage( h, x, 0, null );
      x += h.getWidth() + 30;
    }
    g.dispose();
    return out;
  }

  private static double totalAlto( List<Dibujo> ds ) {
    double s = 0;
    for ( Dibujo d : ds ) {
      s += d.altoFinal() + 30;
    }
    return s;
  }
}
