package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import io.github.ivi2511.pentahomcp.CorridaMedida.PasoMedido;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.graficos.GraficosSvg;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Metricas sin ejecutar nada (corridas armadas a mano):
 * <ul>
 * <li>el Gantt nunca esconde los steps mas lentos: con 26 steps van todos (antes 25, y el que faltaba era
 * la carga final, el mas lento); con mas de 40 quedan los 40 mas lentos y la nota nombra los demas;</li>
 * <li>el grafico de ritmo (antes «Filas escritas por intervalo») va en filas por segundo, con titulo y
 * explicacion, y solo si la corrida dura 2 s o mas con 4 mediciones o mas; si no, una nota de una linea.</li>
 * </ul>
 *
 * @dueño WP4
 */
public class PruebaRitmoYGantt {

  private final Rotulos es = Rotulos.de( SecMetricas.AREA, "es" );

  // ---------------------------------------------------------------- Gantt

  /** El caso real: 26 steps en orden de pipeline y el ultimo (la carga) es el mas lento. */
  public void pruebaGantt26ConElMasLentoAlFinal() throws Exception {
    CorridaMedida m = corrida( 26, 25, 600 );
    Seccion s = SecMetricas.seccionTrans( m, "Métricas", es );
    Grafico g = grafico( s, Grafico.GANTT );
    igual( 26, g.categorias.size(), "los 26 steps en el Gantt" );
    igual( "Step 25", g.categorias.get( 25 ), "el mas lento, al final, esta" );
    nulo( g.nota, "sin nota de steps que no entran" );
    String svg = GraficosSvg.svg( g, new Tema(), 720, "es" );
    contiene( svg, ">Step 25</text>", "el SVG dibuja el mas lento" );
    noContiene( svg, "más</text>", "sin «+N más»" );
  }

  /** 45 steps: quedan los 40 mas lentos, en su orden, y la nota dice cuales no entraron. */
  public void pruebaGantt45SeVanLosMasRapidos() throws Exception {
    CorridaMedida m = corrida( 45, 44, 600 );
    // los 5 mas rapidos: 10, 20, 30, 40 y 0 (ms 5)
    for ( int i : new int[] { 0, 10, 20, 30, 40 } ) {
      m.pasos.get( i ).ms = 5;
      m.pasos.get( i ).execFin = m.pasos.get( i ).execIni + 5;
    }
    Seccion s = SecMetricas.seccionTrans( m, "Métricas", es );
    Grafico g = grafico( s, Grafico.GANTT );
    igual( 40, g.categorias.size(), "40 filas" );
    for ( int i : new int[] { 0, 10, 20, 30, 40 } ) {
      falso( g.categorias.contains( "Step " + i ), "fuera el rapido Step " + i );
    }
    igual( "Step 44", g.categorias.get( 39 ), "el mas lento sigue, al final (orden de la corrida)" );
    igual( "Step 1", g.categorias.get( 0 ), "el orden de la corrida se mantiene" );
    noNulo( g.nota, "nota" );
    contiene( g.nota, "No entran en el gráfico los 5 steps más rápidos", "la nota explica" );
    contiene( g.nota, "«Step 0»", "y los nombra" );
    contiene( g.nota, "«Step 40»", "todos" );
  }

  /**
   * Job con 3 entradas y una sub-transformacion de 44 steps (47 filas): quedan las 40 mas lentas en su orden,
   * la ultima entrada (la mas lenta, la carga) sigue y la nota nombra las que no entraron.
   */
  public void pruebaGanttJobSeVanLasMasRapidas() throws Exception {
    Constructor<CorridaMedida> k = CorridaMedida.class.getDeclaredConstructor();
    k.setAccessible( true );
    CorridaMedida m = k.newInstance();
    m.esJob = true;
    m.fuente = CorridaMedida.ULTIMA;
    m.estado = "finished";
    m.nombre = "job";
    m.inicio = 1_000_000L;
    m.fin = m.inicio + 5000;
    String[] nombres = { "Preparar", "Transformar", "Cargar todo" };
    long[] dur = { 1, 1500, 3000 };
    long t = m.inicio;
    for ( int i = 0; i < 3; i++ ) {
      CorridaMedida.EntradaMedida e = new CorridaMedida.EntradaMedida();
      e.nombre = nombres[ i ];
      e.nr = 0;
      e.job = "job";
      e.ini = t;
      e.fin = t + dur[ i ];
      e.ok = Boolean.TRUE;
      if ( i == 1 ) {
        e.hija = new CorridaMedida.TransHija();
        for ( int j = 0; j < 44; j++ ) {
          PasoMedido p = new PasoMedido();
          p.step = "Paso " + j;
          p.ms = j < 6 ? 1 : 100 + j; // los 6 primeros, rapidisimos (como «Preparar»)
          p.execIni = t + j;
          p.execFin = p.execIni + p.ms;
          p.estado = "STATUS_FINISHED";
          e.hija.pasos.add( p );
        }
      }
      m.entradas.add( e );
      t = e.fin;
    }
    Seccion s = SecMetricas.seccionJob( m, "Métricas", es );
    Grafico g = grafico( s, Grafico.GANTT );
    igual( CorridaMedida.MAX_FILAS_GANTT, g.categorias.size(), "40 filas" );
    igual( "Cargar todo", g.categorias.get( g.categorias.size() - 1 ), "la carga final, la mas lenta, esta" );
    igual( "Transformar", g.categorias.get( 0 ), "orden de la corrida (Preparar, 1 ms, queda fuera)" );
    falso( g.categorias.contains( "↳ Paso 0" ), "fuera un step rapido" );
    noNulo( g.nota, "nota" );
    contiene( g.nota, "No entran en el gráfico las 7 filas más rápidas", "la nota explica" );
    contiene( g.nota, "«Preparar»", "y las nombra" );
  }

  /** El SVG del Gantt (para quien le pase mas filas): quedan las mas largas, el eje cubre toda la corrida. */
  public void pruebaGanttSvgTope() {
    Grafico g = new Grafico();
    g.clase = Grafico.GANTT;
    for ( int i = 0; i < 45; i++ ) {
      long dur = i < 5 ? 3 : 100 + i;
      g.tramos.add( new Tramo( "Fila " + i, Tramo.EXEC, i * 10, i * 10 + dur ) );
    }
    // la fila 2 (rapida) termina la ultima: el eje igual la cubre
    g.tramos.add( new Tramo( "Fila 2", Tramo.INIT, 2000, 2001 ) );
    String svg = GraficosSvg.svg( g, new Tema(), 720, "es" );
    contiene( svg, "+5 más", "45 filas: 40 y «+5 más»" );
    contiene( svg, ">Fila 44</text>", "la mas larga (y la ultima) esta" );
    noContiene( svg, ">Fila 3</text>", "una de las rapidas no" );
    contiene( svg, " s</text>", "el eje llega a 2 s (la fila que no se dibuja tambien cuenta)" );
  }

  // ---------------------------------------------------------------- ritmo

  /** Una corrida real: 555 ms y 3 snapshots. No hay grafico, hay una nota de una linea. */
  public void pruebaRitmoCorridaCorta() throws Exception {
    CorridaMedida m = corrida( 5, 4, 555 );
    m.snapshots = snapshots( m, 3, 200, 10 );
    Seccion s = SecMetricas.seccionTrans( m, "Métricas", es );
    nulo( grafico( s, Grafico.LINEA ), "sin grafico de ritmo" );
    Parrafo p = parrafo( s, "metrics-throughput" );
    noNulo( p, "nota en su lugar" );
    contiene( p.texto, "No se dibuja el ritmo de cada step (filas por segundo)", "dice que no se dibuja" );
    contiene( p.texto, "555 ms", "con la duracion" );
    cierto( p.texto.indexOf( '\n' ) < 0, "una linea" );
  }

  /** 3 s pero solo 3 mediciones (la ultima corrida de Spoon toma una por segundo): tampoco. */
  public void pruebaRitmoPocasMediciones() throws Exception {
    CorridaMedida m = corrida( 5, 4, 3000 );
    m.snapshots = snapshots( m, 3, 1000, 10 );
    Seccion s = SecMetricas.seccionTrans( m, "Métricas", es );
    nulo( grafico( s, Grafico.LINEA ), "sin grafico" );
    contiene( parrafo( s, "metrics-throughput" ).texto, "solo hay 3 mediciones", "la nota dice por que" );
  }

  /** 5 s y 20 mediciones de 250 ms con 100 filas cada una: 400 filas/s, con titulo, unidad y explicacion. */
  public void pruebaRitmoFilasPorSegundo() throws Exception {
    CorridaMedida m = corrida( 5, 4, 5000 );
    m.snapshots = snapshots( m, 20, 250, 100 );
    Seccion s = SecMetricas.seccionTrans( m, "Métricas", es );
    nulo( parrafo( s, "metrics-throughput" ), "sin la nota de corrida corta" );
    Grafico g = grafico( s, Grafico.LINEA );
    noNulo( g, "grafico de ritmo" );
    igual( "metrics-throughput", g.id, "id" );
    igual( "Ritmo de cada step durante la ejecución", g.titulo, "titulo claro" );
    igual( "filas/s", g.unidad, "unidad" );
    contiene( g.nota, "cuántas filas por segundo", "explicacion debajo" );
    cierto( g.series.size() >= 1 && g.series.size() <= SecMetricas.MAX_SERIES, "1 a 5 series" );
    for ( Serie se : g.series ) {
      for ( Double v : se.valores ) {
        igual( 400.0, v, se.nombre + ": 100 filas cada 250 ms = 400 filas/s" );
      }
    }
    Rotulos en = Rotulos.de( SecMetricas.AREA, "en" );
    Grafico ge = grafico( SecMetricas.seccionTrans( m, "Metrics", en ), Grafico.LINEA );
    igual( "Pace of each step during the run", ge.titulo, "en" );
    igual( "rows/s", ge.unidad, "unidad en" );
  }

  /** Intervalos con un instante que falta: la mediana, nunca 0 (no divide por cero). */
  public void pruebaIntervalos() {
    List<Long> xs = new ArrayList<>();
    xs.add( 1250L );
    xs.add( -1L );
    xs.add( 1750L );
    xs.add( 2000L );
    long[] dt = SecMetricas.intervalos( xs, 1000L );
    igual( 250L, dt[ 0 ], "el primero desde el inicio" );
    igual( 250L, dt[ 1 ], "sin instante: la mediana" );
    igual( 250L, dt[ 2 ], "despues de uno que falta: la mediana" );
    igual( 250L, dt[ 3 ], "normal" );
  }

  // ---------------------------------------------------------------- utilidades

  /**
   * Una corrida de n steps en fila: cada uno ejecuta 20 ms seguidos del anterior, salvo el mas lento (que
   * dura 400 ms); la corrida dura total ms.
   */
  private static CorridaMedida corrida( int n, int lento, long total ) throws Exception {
    Constructor<CorridaMedida> k = CorridaMedida.class.getDeclaredConstructor();
    k.setAccessible( true );
    CorridaMedida m = k.newInstance();
    m.esJob = false;
    m.fuente = CorridaMedida.ULTIMA;
    m.estado = "finished";
    m.nombre = "prueba";
    m.inicio = 1_000_000L;
    m.fin = m.inicio + total;
    m.metricasReales = true;
    for ( int i = 0; i < n; i++ ) {
      PasoMedido p = new PasoMedido();
      p.step = "Step " + i;
      p.ms = i == lento ? 400 : 20 + i;
      p.escritas = 10;
      p.estado = "STATUS_FINISHED";
      p.initIni = m.inicio;
      p.initFin = m.inicio + 5;
      p.execIni = m.inicio + 5 + i;
      p.execFin = p.execIni + p.ms;
      p.fuente = i == 0;
      p.escribe = i == n - 1;
      m.pasos.add( p );
    }
    return m;
  }

  /** k snapshots cada dt ms desde el inicio, con filas escritas por intervalo en cada step. */
  private static CorridaMedida.Snapshots snapshots( CorridaMedida m, int k, long dt, long filas ) {
    CorridaMedida.Snapshots sn = new CorridaMedida.Snapshots();
    for ( int i = 1; i <= k; i++ ) {
      sn.instantes.add( m.inicio + i * dt );
    }
    for ( PasoMedido p : m.pasos ) {
      List<Long> l = new ArrayList<>();
      for ( int i = 0; i < k; i++ ) {
        l.add( filas );
      }
      sn.porStep.put( p.step, l );
    }
    return sn;
  }

  private static Grafico grafico( Seccion s, String clase ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Grafico && clase.equals( ( (Grafico) b ).clase ) ) {
        return (Grafico) b;
      }
    }
    return null;
  }

  private static Parrafo parrafo( Seccion s, String id ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Parrafo && id.equals( b.id ) ) {
        return (Parrafo) b;
      }
    }
    return null;
  }
}
