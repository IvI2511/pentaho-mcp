package io.github.ivi2511.pentahomcp.informes.graficos;

import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;
import io.github.ivi2511.pentahomcp.informes.tema.Paleta;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * SVG 1.1 propio para barras_h, gantt y linea (diseno §3.6). El mismo SVG va inline en el HTML (y en la
 * vista previa IE11), al PDF (vectorial), como archivo .svg al Markdown y al PPTX (con el PNG de
 * {@link SvgAPng} de respaldo).
 * <ul>
 * <li>Atributos {@code fill}, {@code stroke} y {@code font-family} inline en cada elemento: nada de CSS,
 * {@code <style>}, {@code var()} ni clases.</li>
 * <li>Sin {@code dominant-baseline}: la linea de base de cada texto se calcula en Java (y + 0,35 em).</li>
 * <li>El primer elemento visible es un {@code <rect>} blanco que cubre todo el {@code viewBox} (POI calcula
 * mal el tamano sin el).</li>
 * <li>{@code width}/{@code height} en px iguales al {@code viewBox}; el alto sale de los datos (22 px por
 * fila en barras y Gantt).</li>
 * <li>Textos del usuario escapados para XML (y sin caracteres de control); rotulos largos recortados con
 * «…» y el nombre completo en un {@code <title>}.</li>
 * </ul>
 * Los textos propios del grafico (leyenda del Gantt, «+N mas», «Sin datos») salen del area
 * {@code graficos} de {@link Rotulos}; los numeros usan los separadores del idioma. Nunca lanza
 * excepciones: datos vacios o una clase desconocida dan un recuadro «Sin datos».
 * <p>
 * Lo que NO dibuja: el {@code titulo} y la {@code nota} del bloque (los pone el renderizador; el titulo
 * va ademas como {@code <title>} y {@code aria-label}). La leyenda del Gantt ya explica que los steps se
 * solapan, asi que el recolector no necesita repetirlo en la nota.
 * <p>
 * Reglas de cada clase:
 * <ul>
 * <li>barras_h: 1 o 2 series (si son 2, cada una con su escala, porque suelen ser unidades distintas:
 * filas y ms); categorias ordenadas de mayor a menor por la primera serie; el valor escrito al final de
 * cada barra con {@code unidad}; si la unidad es "%", la escala va de 0 a 100. Con 2 series de unidades
 * distintas, dejar {@code unidad} en null y poner la unidad en el nombre de cada serie.</li>
 * <li>gantt: filas en el orden de {@code categorias} (y despues las que solo estan en los tramos), hasta
 * {@link #MAX_FILAS_GANTT}: si hay mas, quedan las mas largas y las mas rapidas van como «+N mas»; eje en
 * ms, o en s si dura 2 s o mas, de toda la corrida; init en gris y exec en el primario; la duracion de exec
 * al final.</li>
 * <li>linea: hasta 5 series (las demas se cuentan en la leyenda); un null corta la linea; eje Y desde 0
 * y la unidad arriba del eje.</li>
 * </ul>
 * <p>
 * IE11 (probado con el Browser de SWT 3.115, documentMode 11): dibuja todo, con los rotulos. Si el CSS
 * achica el SVG ({@code max-width:100%}) IE11 no conserva la proporcion: con {@code height:auto} queda de
 * 150 px de alto y si no, del alto original, con el dibujo centrado y espacio en blanco (no se deforma).
 * Para la vista previa conviene pedir el SVG ya con el ancho disponible.
 *
 * @dueño WP6
 */
public final class GraficosSvg {

  /** Ancho recomendado (px): el de la columna de texto del HTML. */
  public static final int ANCHO = 720;
  /** Alto de cada fila de barras_h (una serie) y de gantt. */
  public static final int FILA = 22;
  /** Tope de barras; el resto va como «+N mas». */
  public static final int MAX_BARRAS = 25;
  /**
   * Tope de filas del Gantt (las de CorridaMedida.MAX_FILAS_GANTT entran enteras). Si hay mas, quedan las
   * mas largas en su orden y el resto (las mas rapidas) va como «+N mas».
   */
  public static final int MAX_FILAS_GANTT = 40;
  /** barras_h dibuja 1 o 2 series. */
  public static final int MAX_SERIES_BARRAS = 2;
  /** linea dibuja hasta 5 series. */
  public static final int MAX_SERIES_LINEA = 5;
  /** Area de textos de este paquete. */
  public static final String AREA = "graficos";

  /** Fuente con comillas simples: va dentro de un atributo con comillas dobles. */
  static final String FUENTE = "'Segoe UI', Arial, sans-serif";

  private static final int MIN_ANCHO = 240;
  private static final int MAX_ANCHO = 4000;
  private static final double MARGEN = 10;
  private static final double TAM = 12;
  private static final double TAM_VALOR = 11;
  private static final double TAM_EJE = 10.5;
  private static final double BASE = 0.35;
  private static final String BLANCO = "#FFFFFF";

  private static final Pattern TAMANO = Pattern.compile(
    "<svg\\b[^>]*?\\swidth=\"([0-9.]+)\"[^>]*?\\sheight=\"([0-9.]+)\"" );

  private GraficosSvg() {
  }

  /** El SVG en el idioma del tema ({@code t.idioma}; si es null, el del sistema). */
  public static String svg( Grafico g, Tema t, int anchoPx ) {
    return svg( g, t, anchoPx, t == null ? null : t.idioma );
  }

  /**
   * El SVG del grafico con anchoPx de ancho, con los textos y numeros en ese idioma (en | es | pt; null =
   * el del tema o el del sistema). Los renderizadores pasan {@code Informe.idioma}.
   */
  public static String svg( Grafico g, Tema t, int anchoPx, String idioma ) {
    Tema tema = t == null ? new Tema() : t;
    String i = idioma != null ? idioma : tema.idioma != null ? tema.idioma : Locale.getDefault().getLanguage();
    Rotulos r = Rotulos.de( AREA, i );
    int w = Math.max( MIN_ANCHO, Math.min( MAX_ANCHO, anchoPx <= 0 ? ANCHO : anchoPx ) );
    Contexto c = new Contexto( tema, r, w );
    try {
      if ( g != null && Grafico.BARRAS_H.equals( g.clase ) ) {
        return barras( g, c );
      }
      if ( g != null && Grafico.GANTT.equals( g.clase ) ) {
        return gantt( g, c );
      }
      if ( g != null && Grafico.LINEA.equals( g.clase ) ) {
        return linea( g, c );
      }
    } catch ( RuntimeException e ) {
      // datos incoherentes: mejor un recuadro que romper el informe entero
    }
    return vacio( g, c );
  }

  /** {ancho, alto} en px leidos de la raiz de un SVG de esta clase (o {0, 0}). Le sirve a PPTX. */
  public static int[] tamano( String svg ) {
    if ( svg != null ) {
      Matcher m = TAMANO.matcher( svg );
      if ( m.find() ) {
        try {
          return new int[] { (int) Math.round( Double.parseDouble( m.group( 1 ) ) ), (int) Math.round( Double
            .parseDouble( m.group( 2 ) ) ) };
        } catch ( NumberFormatException e ) {
          // cae al {0, 0}
        }
      }
    }
    return new int[] { 0, 0 };
  }

  // ------------------------------------------------------------------ barras_h

  /** Una categoria con sus 1 o 2 valores. */
  private static final class Fila {
    final String rotulo;
    final Double[] v;

    Fila( String rotulo, Double[] v ) {
      this.rotulo = rotulo;
      this.v = v;
    }
  }

  private static String barras( Grafico g, Contexto c ) {
    List<Serie> series = series( g, MAX_SERIES_BARRAS );
    int k = g.categorias == null ? 0 : g.categorias.size();
    if ( k == 0 || series.isEmpty() ) {
      return vacio( g, c );
    }
    int s = series.size();
    List<Fila> filas = new ArrayList<>();
    boolean alguno = false;
    for ( int i = 0; i < k; i++ ) {
      Double[] v = new Double[ s ];
      for ( int j = 0; j < s; j++ ) {
        v[ j ] = valor( series.get( j ), i );
        alguno |= v[ j ] != null;
      }
      filas.add( new Fila( texto( g.categorias.get( i ) ), v ) );
    }
    if ( !alguno ) {
      return vacio( g, c );
    }
    // categorias ordenadas de mayor a menor por la primera serie (orden estable; sin dato al final)
    Collections.sort( filas, new Comparator<Fila>() {
      @Override
      public int compare( Fila a, Fila b ) {
        Double x = a.v[ 0 ];
        Double y = b.v[ 0 ];
        if ( x == null || y == null ) {
          return x == null ? ( y == null ? 0 : 1 ) : -1;
        }
        return Double.compare( y, x );
      }
    } );
    int visibles = Math.min( k, MAX_BARRAS );
    int resto = k - visibles;
    double[] max = new double[ s ];
    for ( int j = 0; j < s; j++ ) {
      for ( int i = 0; i < visibles; i++ ) {
        Double v = filas.get( i ).v[ j ];
        if ( v != null && v > max[ j ] ) {
          max[ j ] = v;
        }
      }
      if ( max[ j ] <= 0 ) {
        max[ j ] = 1;
      }
      // un porcentaje se mide contra el 100 %: 2,4 % de nulos no puede llenar la barra
      if ( "%".equals( texto( g.unidad ) ) ) {
        max[ j ] = Math.max( max[ j ], 100 );
      }
    }
    // medidas horizontales: rotulos | barras | valores
    double w = c.ancho;
    double rotW = 0;
    double valW = 0;
    for ( int i = 0; i < visibles; i++ ) {
      Fila f = filas.get( i );
      rotW = Math.max( rotW, Medida.ancho( f.rotulo, TAM ) );
      for ( int j = 0; j < s; j++ ) {
        valW = Math.max( valW, Medida.ancho( c.valor( f.v[ j ], g.unidad ), TAM_VALOR ) );
      }
    }
    valW = Math.min( valW + 8, w * 0.3 );
    rotW = Math.max( 60, Math.min( rotW, w * 0.38 ) );
    double hueco = w - 2 * MARGEN - 8 - valW - rotW;
    if ( hueco < w * 0.3 ) {
      rotW = Math.max( 40, rotW - ( w * 0.3 - hueco ) );
    }
    double x0 = MARGEN + rotW + 8;
    double largoMax = Math.max( 40, w - MARGEN - valW - x0 );
    double filaH = s == 1 ? FILA : 30;
    double barraH = s == 1 ? 14 : 11;

    Lienzo l = new Lienzo( c );
    double y = MARGEN;
    List<Item> items = new ArrayList<>();
    for ( int j = 0; j < s; j++ ) {
      String n = texto( series.get( j ).nombre );
      if ( !n.isEmpty() ) {
        items.add( Item.cuadro( Paleta.serie( c.tema, j ), n ) );
      }
    }
    if ( !items.isEmpty() ) {
      y += leyenda( l, items, MARGEN, y, w - MARGEN ) + 6;
    }
    double arriba = y;
    for ( int i = 0; i < visibles; i++ ) {
      Fila f = filas.get( i );
      double yf = arriba + i * filaH;
      l.texto( x0 - 8, yf + filaH / 2 + TAM * BASE, f.rotulo, rotW, "end", TAM, Tema.TEXTO, false );
      double yb = yf + ( filaH - ( s * barraH + ( s - 1 ) * 2 ) ) / 2;
      for ( int j = 0; j < s; j++ ) {
        Double v = f.v[ j ];
        double largo = v == null || v <= 0 ? 0 : v / max[ j ] * largoMax;
        if ( v != null && v > 0 && largo < 1.5 ) {
          largo = 1.5;
        }
        String txt = c.valor( v, g.unidad );
        if ( largo > 0 ) {
          l.rect( x0, yb, largo, barraH, Paleta.serie( c.tema, j ), " rx=\"2\" ry=\"2\"", f.rotulo + ": " + txt );
        }
        l.texto( x0 + largo + 4, yb + barraH / 2 + TAM_VALOR * BASE, txt, 0, "start", TAM_VALOR, Tema.TEXTO,
          false );
        yb += barraH + 2;
      }
    }
    double abajo = arriba + visibles * filaH;
    l.linea( x0, arriba - 2, x0, abajo + 2, Paleta.GRIS_MEDIO, 1 );
    if ( resto > 0 ) {
      l.textoTenue( x0, abajo + filaH / 2 + TAM_VALOR * BASE, c.r.f( "mas", resto ), "start" );
      abajo += filaH;
    }
    return l.cerrar( g, abajo + MARGEN );
  }

  // ------------------------------------------------------------------ gantt

  private static String gantt( Grafico g, Contexto c ) {
    // filas: el orden de categorias y despues las que solo aparecen en los tramos
    LinkedHashMap<String, List<Tramo>> filas = new LinkedHashMap<>();
    if ( g.categorias != null ) {
      for ( String cat : g.categorias ) {
        if ( cat != null && !filas.containsKey( cat ) ) {
          filas.put( cat, new ArrayList<Tramo>() );
        }
      }
    }
    if ( g.tramos != null ) {
      for ( Tramo t : g.tramos ) {
        if ( t == null ) {
          continue;
        }
        String cat = t.categoria == null ? "" : t.categoria;
        List<Tramo> l = filas.get( cat );
        if ( l == null ) {
          l = new ArrayList<>();
          filas.put( cat, l );
        }
        l.add( t );
      }
    }
    final List<String> todas = new ArrayList<>( filas.keySet() );
    int n = todas.size();
    int visibles = Math.min( n, MAX_FILAS_GANTT );
    int resto = n - visibles;
    // el eje cubre toda la corrida, tambien las filas que no se dibujan
    long tmin = 0;
    long tmax = Long.MIN_VALUE;
    for ( List<Tramo> l : filas.values() ) {
      for ( Tramo t : l ) {
        tmin = Math.min( tmin, Math.min( t.inicioMs, t.finMs ) );
        tmax = Math.max( tmax, Math.max( t.inicioMs, t.finMs ) );
      }
    }
    List<String> nombres = todas;
    if ( resto > 0 ) {
      // si no entran todas, quedan las mas largas (en su orden): las que se cortan son las mas rapidas
      List<String> porDuracion = new ArrayList<>( todas );
      final LinkedHashMap<String, List<Tramo>> f = filas;
      Collections.sort( porDuracion, new Comparator<String>() {
        @Override
        public int compare( String a, String b ) {
          return Long.compare( duracion( f.get( b ) ), duracion( f.get( a ) ) );
        }
      } );
      Set<String> quedan = new HashSet<>( porDuracion.subList( 0, visibles ) );
      nombres = new ArrayList<>();
      for ( String x : todas ) {
        if ( quedan.contains( x ) ) {
          nombres.add( x );
        }
      }
    }
    if ( tmax == Long.MIN_VALUE ) {
      return vacio( g, c );
    }
    if ( tmax <= tmin ) {
      tmax = tmin + 1;
    }
    boolean seg = tmax - tmin >= 2000;
    double factor = seg ? 1000.0 : 1.0;
    double paso = Escala.paso( ( tmax - tmin ) / factor / 6.0 );
    double dmin = Math.floor( tmin / factor / paso ) * paso;
    double dmax = Math.ceil( tmax / factor / paso ) * paso;
    if ( dmax <= dmin ) {
      dmax = dmin + paso;
    }
    int decimales = Escala.decimales( paso );
    String unidad = seg ? "s" : "ms";

    // medidas horizontales
    double w = c.ancho;
    double rotW = 0;
    double valW = 0;
    for ( int i = 0; i < visibles; i++ ) {
      rotW = Math.max( rotW, Medida.ancho( texto( nombres.get( i ) ), TAM ) );
      valW = Math.max( valW, Medida.ancho( c.duracion( duracion( filas.get( nombres.get( i ) ) ) ), TAM_VALOR ) );
    }
    String ultimoTick = c.numero( dmax, decimales ) + " " + unidad;
    valW = Math.max( Math.min( valW + 8, w * 0.25 ), Medida.ancho( ultimoTick, TAM_EJE ) / 2 + 2 );
    rotW = Math.max( 60, Math.min( rotW, w * 0.36 ) );
    double x0 = MARGEN + rotW + 8;
    double ancho = Math.max( 60, w - MARGEN - valW - x0 );

    Lienzo l = new Lienzo( c );
    double y = MARGEN;
    List<Item> items = new ArrayList<>();
    boolean hayInit = false;
    boolean hayExec = false;
    for ( int i = 0; i < visibles; i++ ) {
      for ( Tramo t : filas.get( nombres.get( i ) ) ) {
        hayInit |= Tramo.INIT.equals( t.tipo );
        hayExec |= !Tramo.INIT.equals( t.tipo );
      }
    }
    if ( hayInit ) {
      items.add( Item.cuadro( Paleta.GRIS_CLARO, c.r.s( "init" ) ) );
    }
    if ( hayExec ) {
      items.add( Item.cuadro( Paleta.primario( c.tema ), c.r.s( "exec" ) ) );
    }
    if ( g.leyenda != null ) {
      if ( !g.leyenda.trim().isEmpty() ) {
        items.add( Item.nota( g.leyenda.trim() ) );
      }
    } else if ( visibles > 1 ) {
      items.add( Item.nota( c.r.s( "paralelo" ) ) );
    }
    y += leyenda( l, items, MARGEN, y, w - MARGEN ) + 4;

    // eje de tiempo arriba: marcas, rotulos y la unidad en la ultima
    double ejeY = y + TAM_EJE;
    double arriba = ejeY + 6;
    double abajo = arriba + visibles * FILA;
    int marcas = (int) Math.round( ( dmax - dmin ) / paso );
    for ( int m = 0; m <= marcas; m++ ) {
      double d = dmin + m * paso;
      double x = x0 + ( d - dmin ) / ( dmax - dmin ) * ancho;
      l.linea( x, arriba - 2, x, abajo, m == 0 ? Paleta.GRIS_MEDIO : Tema.BORDE, 1 );
      String txt = ( m == 0 && d == 0 ? "0" : c.numero( d, decimales ) ) + ( m == marcas ? " " + unidad : "" );
      l.textoEje( x, ejeY, txt, m == marcas ? "end" : m == 0 ? "start" : "middle" );
    }
    double escala = ancho / ( ( dmax - dmin ) * factor );
    for ( int i = 0; i < visibles; i++ ) {
      String nombre = nombres.get( i );
      String rot = texto( nombre );
      double yf = arriba + i * FILA;
      l.texto( x0 - 8, yf + FILA / 2.0 + TAM * BASE, rot, rotW, "end", TAM, Tema.TEXTO, false );
      double fin = x0;
      // init primero: exec se dibuja encima si se solapan
      for ( int pasada = 0; pasada < 2; pasada++ ) {
        for ( Tramo t : filas.get( nombre ) ) {
          boolean exec = !Tramo.INIT.equals( t.tipo );
          if ( exec != ( pasada == 1 ) ) {
            continue;
          }
          long a = Math.min( t.inicioMs, t.finMs );
          long b = Math.max( t.inicioMs, t.finMs );
          double xa = x0 + ( a - dmin * factor ) * escala;
          double largo = Math.max( 1.5, ( b - a ) * escala );
          String color = exec ? Paleta.primario( c.tema ) : Paleta.GRIS_CLARO;
          String tip = rot + " · " + c.r.s( exec ? "exec" : "init" ) + ": " + c.numero( a, 0 ) + " – " + c
            .numero( b, 0 ) + " ms";
          l.rect( xa, yf + 5, largo, FILA - 10, color, " rx=\"2\" ry=\"2\"", tip );
          fin = Math.max( fin, xa + largo );
        }
      }
      if ( !filas.get( nombre ).isEmpty() ) {
        l.texto( fin + 4, yf + FILA / 2.0 + TAM_VALOR * BASE, c.duracion( duracion( filas.get( nombre ) ) ), 0,
          "start", TAM_VALOR, Tema.TEXTO_TENUE, false );
      }
    }
    if ( resto > 0 ) {
      l.textoTenue( x0, abajo + FILA / 2.0 + TAM_VALOR * BASE, c.r.f( "mas", resto ), "start" );
      abajo += FILA;
    }
    return l.cerrar( g, abajo + MARGEN );
  }

  /** ms de ejecucion de la fila (suma de los exec; si no hay, de todos los tramos). */
  private static long duracion( List<Tramo> tramos ) {
    long exec = 0;
    long todo = 0;
    for ( Tramo t : tramos ) {
      long d = Math.abs( t.finMs - t.inicioMs );
      todo += d;
      if ( !Tramo.INIT.equals( t.tipo ) ) {
        exec += d;
      }
    }
    return exec > 0 ? exec : todo;
  }

  // ------------------------------------------------------------------ linea

  private static String linea( Grafico g, Contexto c ) {
    List<Serie> series = series( g, Integer.MAX_VALUE );
    int extra = Math.max( 0, series.size() - MAX_SERIES_LINEA );
    if ( extra > 0 ) {
      series = new ArrayList<>( series.subList( 0, MAX_SERIES_LINEA ) );
    }
    int cats = g.categorias == null ? 0 : g.categorias.size();
    int n = cats;
    double lo = Double.POSITIVE_INFINITY;
    double hi = Double.NEGATIVE_INFINITY;
    for ( Serie s : series ) {
      n = Math.max( n, s.valores == null ? 0 : s.valores.size() );
    }
    for ( Serie s : series ) {
      for ( int i = 0; i < n; i++ ) {
        Double v = valor( s, i );
        if ( v != null ) {
          lo = Math.min( lo, v );
          hi = Math.max( hi, v );
        }
      }
    }
    if ( n == 0 || series.isEmpty() || lo > hi ) {
      return vacio( g, c );
    }
    lo = Math.min( 0, lo );
    hi = Math.max( 0, hi );
    if ( hi <= lo ) {
      hi = lo + 1;
    }
    double paso = Escala.paso( ( hi - lo ) / 5.0 );
    double ymin = Math.floor( lo / paso ) * paso;
    double ymax = Math.ceil( hi / paso ) * paso;
    if ( ymax <= ymin ) {
      ymax = ymin + paso;
    }
    int decimales = Escala.decimales( paso );
    int marcas = (int) Math.round( ( ymax - ymin ) / paso );

    double w = c.ancho;
    Lienzo l = new Lienzo( c );
    double y = MARGEN;
    List<Item> items = new ArrayList<>();
    for ( int j = 0; j < series.size(); j++ ) {
      String nombre = texto( series.get( j ).nombre );
      items.add( Item.linea( Paleta.serie( c.tema, j ), nombre.isEmpty() ? String.valueOf( j + 1 ) : nombre,
        guion( j ) ) );
    }
    if ( extra > 0 ) {
      items.add( Item.nota( c.r.f( "series_mas", extra ) ) );
    }
    y += leyenda( l, items, MARGEN, y, w - MARGEN ) + 6;
    String unidad = texto( g.unidad );
    if ( !unidad.isEmpty() ) {
      l.textoEje( MARGEN, y + TAM_EJE, unidad, "start" );
      y += TAM_EJE + 6;
    }
    double yLabW = 0;
    for ( int m = 0; m <= marcas; m++ ) {
      yLabW = Math.max( yLabW, Medida.ancho( c.numero( ymin + m * paso, decimales ), TAM_EJE ) );
    }
    double izq = MARGEN + yLabW + 6;
    double der = w - MARGEN;
    double anchoP = Math.max( 40, der - izq );
    double arriba = y + 6;
    double alto = Math.max( 150, Math.min( 300, Math.round( w * 0.34 ) ) );
    double abajo = arriba + alto;

    for ( int m = 0; m <= marcas; m++ ) {
      double v = ymin + m * paso;
      double yy = abajo - ( v - ymin ) / ( ymax - ymin ) * alto;
      boolean cero = Math.abs( v ) < paso / 1e6;
      l.linea( izq, yy, der, yy, cero ? Paleta.GRIS_MEDIO : Tema.BORDE, 1 );
      l.textoEje( izq - 6, yy + TAM_EJE * BASE, c.numero( v, decimales ), "end" );
    }
    // rotulos del eje X: uno cada k para que no se pisen
    double banda = anchoP / n;
    double rotMax = 0;
    for ( int i = 0; i < n; i++ ) {
      rotMax = Math.max( rotMax, Medida.ancho( categoria( g, i ), TAM_EJE ) );
    }
    int k = Math.max( 1, (int) Math.ceil( ( Math.min( rotMax, 160 ) + 8 ) / banda ) );
    for ( int i = 0; i < n; i += k ) {
      double x = izq + ( i + 0.5 ) * banda;
      String rot = Medida.recortar( categoria( g, i ), Math.max( 20, k * banda - 6 ), TAM_EJE );
      l.textoEje( x, abajo + 6 + TAM_EJE, rot, "middle" );
    }
    // series: un polyline por tramo continuo (null corta la linea) y puntos si son pocos
    boolean puntos = n <= 40;
    for ( int j = 0; j < series.size(); j++ ) {
      Serie s = series.get( j );
      String color = Paleta.serie( c.tema, j );
      String nombre = items.get( j ).texto;
      List<double[]> tramo = new ArrayList<>();
      for ( int i = 0; i <= n; i++ ) {
        Double v = i < n ? valor( s, i ) : null;
        if ( v != null ) {
          tramo.add( new double[] { izq + ( i + 0.5 ) * banda, abajo - ( v - ymin ) / ( ymax - ymin ) * alto, i,
            v } );
          continue;
        }
        if ( tramo.size() > 1 ) {
          l.polilinea( tramo, color, guion( j ) );
        }
        if ( puntos || tramo.size() == 1 ) {
          for ( double[] p : tramo ) {
            l.punto( p[ 0 ], p[ 1 ], tramo.size() == 1 ? 3 : 2.5, color, nombre + " · " + categoria( g,
              (int) p[ 2 ] ) + ": " + c.valor( p[ 3 ], g.unidad ) );
          }
        }
        tramo.clear();
      }
    }
    return l.cerrar( g, abajo + 6 + TAM_EJE + 4 + MARGEN );
  }

  /** Patron de trazo de la serie j: las primeras 3 lisas, despues rayas y puntos. */
  private static String guion( int j ) {
    switch ( j % MAX_SERIES_LINEA ) {
      case 3:
        return "6 3";
      case 4:
        return "2 3";
      default:
        return null;
    }
  }

  private static String categoria( Grafico g, int i ) {
    if ( g.categorias != null && i < g.categorias.size() ) {
      return texto( g.categorias.get( i ) );
    }
    return String.valueOf( i + 1 );
  }

  // ------------------------------------------------------------------ comunes

  /** Recuadro «Sin datos» (tambien para clases desconocidas). */
  private static String vacio( Grafico g, Contexto c ) {
    Lienzo l = new Lienzo( c );
    double h = 80;
    l.rect( 0.5, 0.5, c.ancho - 1, h - 1, BLANCO, " stroke=\"" + Tema.BORDE + "\" stroke-width=\"1\""
      + " stroke-dasharray=\"4 3\"", null );
    l.textoTenue( c.ancho / 2.0, h / 2 + TAM * BASE, c.r.s( "sin_datos" ), "middle" );
    return l.cerrar( g, h );
  }

  /** Las series no nulas, hasta max. */
  private static List<Serie> series( Grafico g, int max ) {
    List<Serie> l = new ArrayList<>();
    if ( g.series != null ) {
      for ( Serie s : g.series ) {
        if ( s != null && l.size() < max ) {
          l.add( s );
        }
      }
    }
    return l;
  }

  /** El valor i de la serie, o null si falta, es null, NaN o infinito. */
  private static Double valor( Serie s, int i ) {
    if ( s == null || s.valores == null || i < 0 || i >= s.valores.size() ) {
      return null;
    }
    Double v = s.valores.get( i );
    if ( v == null || v.isNaN() || v.isInfinite() ) {
      return null;
    }
    return v == 0 ? 0.0 : v;
  }

  private static String texto( String s ) {
    return s == null ? "" : s.trim();
  }

  // ------------------------------------------------------------------ leyenda

  /** Un elemento de la leyenda: cuadro de color, trazo de linea o nota suelta. */
  private static final class Item {
    final String color;
    final String texto;
    final int forma; // 0 cuadro, 1 linea, 2 nota
    final String guion;

    private Item( String color, String texto, int forma, String guion ) {
      this.color = color;
      this.texto = texto;
      this.forma = forma;
      this.guion = guion;
    }

    static Item cuadro( String color, String texto ) {
      return new Item( color, texto, 0, null );
    }

    static Item linea( String color, String texto, String guion ) {
      return new Item( color, texto, 1, guion );
    }

    static Item nota( String texto ) {
      return new Item( null, texto, 2, null );
    }
  }

  /** Dibuja la leyenda desde (x, y), envolviendo en maxX. Devuelve el alto usado. */
  private static double leyenda( Lienzo l, List<Item> items, double x, double y, double maxX ) {
    double alto = 14;
    double cx = x;
    double cy = y;
    for ( Item it : items ) {
      double muestra = it.forma == 0 ? 14 : it.forma == 1 ? 22 : 0;
      double maxTexto = maxX - x - muestra;
      String txt = Medida.recortar( it.texto, maxTexto, TAM_VALOR );
      double largo = muestra + Medida.ancho( txt, TAM_VALOR );
      if ( cx > x && cx + largo > maxX ) {
        cx = x;
        cy += 16;
        alto += 16;
      }
      if ( it.forma == 0 ) {
        l.rect( cx, cy + 2, 10, 10, it.color, " rx=\"2\" ry=\"2\"", null );
      } else if ( it.forma == 1 ) {
        l.linea2( cx, cy + 7, cx + 16, cy + 7, it.color, 2.5, it.guion );
        l.punto( cx + 8, cy + 7, 2.5, it.color, null, it.color );
      }
      l.texto( cx + muestra, cy + 7 + TAM_VALOR * BASE, txt, 0, "start", TAM_VALOR, it.forma == 2 ? Tema.TEXTO_TENUE
        : Tema.TEXTO, false );
      cx += largo + 14;
    }
    return alto;
  }

  // ------------------------------------------------------------------ contexto y formato

  /** Tema, textos, locale y ancho de un dibujo. */
  private static final class Contexto {
    final Tema tema;
    final Rotulos r;
    final Locale locale;
    final char decimal;
    final int ancho;

    Contexto( Tema tema, Rotulos r, int ancho ) {
      this.tema = tema;
      this.r = r;
      this.locale = r.locale();
      this.decimal = DecimalFormatSymbols.getInstance( locale ).getDecimalSeparator();
      this.ancho = ancho;
    }

    /** Valor con miles y decimales del idioma (0 a 3 decimales segun el tamano) y la unidad. */
    String valor( Double v, String unidad ) {
      if ( v == null ) {
        return "–";
      }
      double a = Math.abs( v );
      int dec = a == Math.rint( a ) || a >= 100 ? 0 : a >= 10 ? 1 : a >= 1 ? 2 : 3;
      String s = sinCeros( numero( v, dec ) );
      String u = texto( unidad );
      return u.isEmpty() ? s : s + " " + u;
    }

    /** Numero con miles y exactamente esos decimales. */
    String numero( double v, int decimales ) {
      double x = v == 0 ? 0.0 : v;
      String s = String.format( locale, "%,." + Math.max( 0, decimales ) + "f", x );
      // -0 por redondeo
      return s.matches( "-0([.,]0+)?" ) ? s.substring( 1 ) : s;
    }

    /** "850 ms" o "1,7 s". */
    String duracion( long ms ) {
      if ( ms < 1000 ) {
        return numero( ms, 0 ) + " ms";
      }
      return numero( ms / 1000.0, ms >= 100000 ? 0 : 1 ) + " s";
    }

    private String sinCeros( String s ) {
      int p = s.lastIndexOf( decimal );
      if ( p < 0 ) {
        return s;
      }
      int fin = s.length();
      while ( fin > p + 1 && s.charAt( fin - 1 ) == '0' ) {
        fin--;
      }
      if ( fin == p + 1 ) {
        fin = p;
      }
      return s.substring( 0, fin );
    }
  }

  /** Pasos "lindos" de los ejes: 1, 2, 2,5 o 5 por potencia de 10. */
  static final class Escala {

    private Escala() {
    }

    static double paso( double crudo ) {
      if ( !( crudo > 0 ) || Double.isInfinite( crudo ) ) {
        return 1;
      }
      double e = Math.pow( 10, Math.floor( Math.log10( crudo ) ) );
      double f = crudo / e;
      double n = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
      return n * e;
    }

    /** Decimales para escribir multiplos de ese paso sin perder precision. */
    static int decimales( double paso ) {
      for ( int d = 0; d <= 6; d++ ) {
        double m = paso * Math.pow( 10, d );
        if ( Math.abs( m - Math.rint( m ) ) < 1e-6 * Math.max( 1, m ) ) {
          return d;
        }
      }
      return 6;
    }
  }

  // ------------------------------------------------------------------ SVG

  /** Arma el texto del SVG. Coordenadas con punto y hasta 2 decimales. */
  private static final class Lienzo {
    private final Contexto c;
    private final StringBuilder sb = new StringBuilder( 8192 );

    Lienzo( Contexto c ) {
      this.c = c;
    }

    void rect( double x, double y, double w, double h, String fill, String extra, String titulo ) {
      sb.append( "<rect x=\"" ).append( n( x ) ).append( "\" y=\"" ).append( n( y ) ).append( "\" width=\"" )
        .append( n( w ) ).append( "\" height=\"" ).append( n( h ) ).append( "\" fill=\"" ).append( fill ).append(
          '"' );
      if ( extra != null ) {
        sb.append( extra );
      }
      if ( titulo != null ) {
        sb.append( "><title>" ).append( Xml.esc( titulo ) ).append( "</title></rect>" );
      } else {
        sb.append( "/>" );
      }
    }

    void linea( double x1, double y1, double x2, double y2, String stroke, double ancho ) {
      sb.append( "<line x1=\"" ).append( n( x1 ) ).append( "\" y1=\"" ).append( n( y1 ) ).append( "\" x2=\"" )
        .append( n( x2 ) ).append( "\" y2=\"" ).append( n( y2 ) ).append( "\" stroke=\"" ).append( stroke ).append(
          "\" stroke-width=\"" ).append( n( ancho ) ).append( "\" shape-rendering=\"crispEdges\"/>" );
    }

    /** Linea con extremos redondeados y patron opcional (leyenda de linea). */
    void linea2( double x1, double y1, double x2, double y2, String stroke, double ancho, String guion ) {
      sb.append( "<line x1=\"" ).append( n( x1 ) ).append( "\" y1=\"" ).append( n( y1 ) ).append( "\" x2=\"" )
        .append( n( x2 ) ).append( "\" y2=\"" ).append( n( y2 ) ).append( "\" stroke=\"" ).append( stroke ).append(
          "\" stroke-width=\"" ).append( n( ancho ) ).append( '"' );
      if ( guion != null ) {
        sb.append( " stroke-dasharray=\"" ).append( guion ).append( '"' );
      }
      sb.append( "/>" );
    }

    void polilinea( List<double[]> puntos, String stroke, String guion ) {
      sb.append( "<polyline points=\"" );
      for ( int i = 0; i < puntos.size(); i++ ) {
        if ( i > 0 ) {
          sb.append( ' ' );
        }
        sb.append( n( puntos.get( i )[ 0 ] ) ).append( ',' ).append( n( puntos.get( i )[ 1 ] ) );
      }
      sb.append( "\" fill=\"none\" stroke=\"" ).append( stroke ).append(
        "\" stroke-width=\"2\" stroke-linejoin=\"round\" stroke-linecap=\"round\"" );
      if ( guion != null ) {
        sb.append( " stroke-dasharray=\"" ).append( guion ).append( '"' );
      }
      sb.append( "/>" );
    }

    void punto( double x, double y, double radio, String fill, String titulo ) {
      punto( x, y, radio, fill, titulo, BLANCO );
    }

    void punto( double x, double y, double radio, String fill, String titulo, String borde ) {
      sb.append( "<circle cx=\"" ).append( n( x ) ).append( "\" cy=\"" ).append( n( y ) ).append( "\" r=\"" )
        .append( n( radio ) ).append( "\" fill=\"" ).append( fill ).append( "\" stroke=\"" ).append( borde )
        .append( "\" stroke-width=\"1\"" );
      if ( titulo != null ) {
        sb.append( "><title>" ).append( Xml.esc( titulo ) ).append( "</title></circle>" );
      } else {
        sb.append( "/>" );
      }
    }

    /**
     * Texto con la linea de base en y. Si maxAncho &gt; 0 y no entra, se recorta con «…» y el original
     * va en un title.
     */
    void texto( double x, double y, String t, double maxAncho, String anchor, double tam, String fill,
      boolean negrita ) {
      String s = t == null ? "" : t;
      String visible = maxAncho > 0 ? Medida.recortar( s, maxAncho, tam ) : s;
      sb.append( "<text x=\"" ).append( n( x ) ).append( "\" y=\"" ).append( n( y ) ).append( '"' );
      if ( !"start".equals( anchor ) ) {
        sb.append( " text-anchor=\"" ).append( anchor ).append( '"' );
      }
      sb.append( " font-family=\"" ).append( FUENTE ).append( "\" font-size=\"" ).append( n( tam ) ).append(
        "\" fill=\"" ).append( fill ).append( '"' );
      if ( negrita ) {
        sb.append( " font-weight=\"bold\"" );
      }
      sb.append( '>' );
      if ( !visible.equals( s ) ) {
        sb.append( "<title>" ).append( Xml.esc( s ) ).append( "</title>" );
      }
      sb.append( Xml.esc( visible ) ).append( "</text>" );
    }

    void textoTenue( double x, double y, String t, String anchor ) {
      texto( x, y, t, c.ancho - 2 * MARGEN, anchor, TAM_VALOR, Tema.TEXTO_TENUE, false );
    }

    void textoEje( double x, double y, String t, String anchor ) {
      texto( x, y, t, 0, anchor, TAM_EJE, Tema.TEXTO_TENUE, false );
    }

    /** Envuelve todo con la raiz, el title y el rect de fondo. */
    String cerrar( Grafico g, double alto ) {
      int w = c.ancho;
      int h = (int) Math.ceil( alto );
      String titulo = g == null ? "" : GraficosSvg.texto( g.titulo );
      StringBuilder r = new StringBuilder( sb.length() + 400 );
      r.append( "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" width=\"" ).append( w ).append(
        "\" height=\"" ).append( h ).append( "\" viewBox=\"0 0 " ).append( w ).append( ' ' ).append( h ).append(
          "\" role=\"img\"" );
      if ( !titulo.isEmpty() ) {
        r.append( " aria-label=\"" ).append( Xml.esc( titulo ) ).append( '"' );
      }
      r.append( '>' );
      if ( !titulo.isEmpty() ) {
        r.append( "<title>" ).append( Xml.esc( titulo ) ).append( "</title>" );
      }
      r.append( "<rect x=\"0\" y=\"0\" width=\"" ).append( w ).append( "\" height=\"" ).append( h ).append(
        "\" fill=\"" ).append( BLANCO ).append( "\"/>" );
      r.append( sb ).append( "</svg>" );
      return r.toString();
    }

    private static String n( double d ) {
      if ( Double.isNaN( d ) || Double.isInfinite( d ) ) {
        return "0";
      }
      long l = Math.round( d * 100 );
      if ( l % 100 == 0 ) {
        return Long.toString( l / 100 );
      }
      String s = String.format( Locale.ROOT, "%.2f", l / 100.0 );
      return s.endsWith( "0" ) ? s.substring( 0, s.length() - 1 ) : s;
    }
  }

  /** Escape para texto y atributos XML; quita los caracteres que XML 1.0 no admite. */
  static final class Xml {

    private Xml() {
    }

    static String esc( String s ) {
      if ( s == null ) {
        return "";
      }
      StringBuilder b = new StringBuilder( s.length() + 16 );
      for ( int i = 0; i < s.length(); i++ ) {
        char ch = s.charAt( i );
        switch ( ch ) {
          case '&':
            b.append( "&amp;" );
            break;
          case '<':
            b.append( "&lt;" );
            break;
          case '>':
            b.append( "&gt;" );
            break;
          case '"':
            b.append( "&quot;" );
            break;
          case '\'':
            b.append( "&#39;" );
            break;
          case '\t':
          case '\n':
          case '\r':
            b.append( ' ' );
            break;
          default:
            if ( Character.isHighSurrogate( ch ) && i + 1 < s.length() && Character.isLowSurrogate( s.charAt( i
              + 1 ) ) ) {
              b.append( ch ).append( s.charAt( ++i ) );
            } else if ( ch >= 0x20 && !Character.isSurrogate( ch ) && ch != 0xFFFE && ch != 0xFFFF ) {
              b.append( ch );
            }
        }
      }
      return b.toString();
    }
  }

  /**
   * Ancho estimado de un texto en Segoe UI / Arial, sin AWT (deterministico y sin fuentes instaladas):
   * metricas de Arial en milesimas de em, con 5 % de margen.
   */
  static final class Medida {

    /** Anchos de Arial (1/1000 em) para ASCII 32..126. */
    private static final int[] ASCII = {
      278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // 32-47
      556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 48-63
      1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // 64-79
      667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // 80-95
      333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // 96-111
      556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584 }; // 112-126

    private static final Map<Character, Integer> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private Medida() {
    }

    static double ancho( String s, double tam ) {
      if ( s == null || s.isEmpty() ) {
        return 0;
      }
      double milesimas = 0;
      for ( int i = 0; i < s.length(); i++ ) {
        char ch = s.charAt( i );
        if ( Character.isLowSurrogate( ch ) ) {
          continue;
        }
        milesimas += unidades( ch );
      }
      return milesimas / 1000.0 * tam * 1.05;
    }

    /** El texto recortado con «…» para que entre en maxPx (por puntos de codigo, sin partir pares). */
    static String recortar( String s, double maxPx, double tam ) {
      if ( s == null ) {
        return "";
      }
      if ( ancho( s, tam ) <= maxPx ) {
        return s;
      }
      int fin = s.length();
      while ( fin > 0 ) {
        fin = s.offsetByCodePoints( fin, -1 );
        String t = s.substring( 0, fin ).trim() + "…";
        if ( ancho( t, tam ) <= maxPx ) {
          return t;
        }
      }
      return "…";
    }

    private static int unidades( char ch ) {
      if ( ch >= 32 && ch <= 126 ) {
        return ASCII[ ch - 32 ];
      }
      Integer u = CACHE.get( ch );
      if ( u == null ) {
        u = calcular( ch );
        CACHE.put( ch, u );
      }
      return u;
    }

    private static int calcular( char ch ) {
      switch ( ch ) {
        case '«':
        case '»':
        case '–':
          return 556;
        case '…':
        case '—':
          return 1000;
        case '·':
        case ' ':
          return 278;
        default:
          break;
      }
      if ( Character.isHighSurrogate( ch ) || ch >= 0x2E80 ) {
        return 1000; // CJK, emoji y otros anchos
      }
      String base = Normalizer.normalize( String.valueOf( ch ), Normalizer.Form.NFD );
      if ( !base.isEmpty() && base.charAt( 0 ) >= 32 && base.charAt( 0 ) <= 126 ) {
        return ASCII[ base.charAt( 0 ) - 32 ];
      }
      return 600;
    }
  }
}
