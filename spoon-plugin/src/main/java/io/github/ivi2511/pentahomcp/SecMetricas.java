package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.CorridaMedida.EntradaMedida;
import io.github.ivi2511.pentahomcp.CorridaMedida.PasoMedido;
import io.github.ivi2511.pentahomcp.CorridaMedida.TransHija;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;

/**
 * Seccion metrics (diseno §2.4.2): la ultima corrida de Spoon (last_run, solo lectura) o una copia
 * medida (run_copy: "Medir...", ejecuta y ESCRIBE las salidas). Para transformaciones: indicadores,
 * barras de filas y tiempo por step, Gantt (real con metricas; si no, aproximado y lo dice; con mas de 40
 * steps quedan los mas lentos), el ritmo de los steps (filas por segundo) si hay snapshots y la corrida dura
 * 2 s o mas (si no, una nota) y la tabla de StepStatus. Para jobs: indicadores, Gantt por
 * entrada con los sub-jobs y (en run_copy) los steps de cada sub-transformacion anidados, y tablas.
 *
 * @dueño WP4
 */
final class SecMetricas implements Recolector {

  /** Area de Rotulos de metricas y perfil (textos/ejecucion_*.properties). */
  static final String AREA = "ejecucion";
  /** Tope de barras del grafico de steps. */
  static final int MAX_BARRAS = 25;
  /** Tope de series del grafico de linea. */
  static final int MAX_SERIES = 5;
  /** El grafico de ritmo (filas por segundo) solo sale si la corrida duro al menos esto... */
  static final long MIN_MS_RITMO = 2000;
  /** ...y tiene al menos estos snapshots; si no, una nota de una linea. */
  static final int MIN_PUNTOS_RITMO = 4;
  /** Tope de filas de las tablas de steps de sub-transformaciones. */
  static final int MAX_FILAS_TABLA = 60;
  /** Ninguna seccion espera mas que esto a una medicion, aunque el pedido tenga mas tiempo. */
  static final long TOPE_MEDICION_MS = 10 * 60 * 1000L;

  @Override
  public String id() {
    return SeccionId.METRICAS;
  }

  /** Aplica a transformaciones y jobs: aunque no haya corrida previa, "Medir..." siempre puede correr. */
  @Override
  public String noAplica( Captura c, String idioma ) {
    if ( c == null || c.copia == null || !( c.esTransformacion() || c.esJob() ) ) {
      return Rotulos.de( AREA, idioma ).s( "metrics.no_document" );
    }
    return null;
  }

  /** Marcada por defecto solo si hubo corrida en Spoon (diseno §2.3). Barato: para la UI y disponibles. */
  static boolean hayCorrida( Captura c ) {
    if ( c == null ) {
      return false;
    }
    // sin tipo (capturas armadas a mano), lo dice la copia
    boolean job = c.esJob() || ( c.tipo == null && c.copia instanceof org.pentaho.di.job.JobMeta );
    return job ? c.ultimoJob != null : c.ultimaTrans != null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos r = ctx.rotulos( AREA );
    String titulo = SeccionId.titulo( id(), ctx.idioma() );
    Captura c = ctx.captura();
    String motivo = noAplica( c, ctx.idioma() );
    if ( motivo != null ) {
      return Seccion.omitida( id(), titulo, motivo );
    }
    String modo = ctx.pedido().metricas == null ? Pedido.METRICAS_ULTIMA : ctx.pedido().metricas;
    if ( Pedido.METRICAS_NINGUNA.equals( modo ) ) {
      return Seccion.omitida( id(), titulo, r.s( "metrics.off" ) );
    }
    CorridaMedida m;
    if ( Pedido.METRICAS_COPIA.equals( modo ) ) {
      ctx.progreso().paso( r.f( "metrics.measuring", c.nombre ), 0, 0 );
      long espera = Math.min( TOPE_MEDICION_MS, Math.max( 1000, ctx.msRestantes() - 3000 ) );
      if ( c.esTransformacion() ) {
        TransMeta copia = (TransMeta) ReportTools.clonar( c.copia );
        copia.setRepository( c.copia.getRepository() );
        copia.setMetaStore( c.copia.getMetaStore() );
        m = CorridaMedida.medirTrans( copia, espera, progreso( ctx ) );
      } else {
        JobMeta copia = (JobMeta) ReportTools.clonar( c.copia );
        copia.setRepository( c.copia.getRepository() );
        copia.setMetaStore( c.copia.getMetaStore() );
        m = CorridaMedida.medirJob( copia, espera, progreso( ctx ) );
      }
    } else {
      if ( !hayCorrida( c ) ) {
        return Seccion.omitida( id(), titulo, r.s( "metrics.no_run" ) );
      }
      ctx.progreso().paso( r.s( "metrics.reading" ), 0, 0 );
      m = c.esTransformacion() ? CorridaMedida.deTrans( c.ultimaTrans, (TransMeta) c.copia, CorridaMedida.ULTIMA )
        : CorridaMedida.deJob( c.ultimoJob, CorridaMedida.ULTIMA, null );
    }
    Seccion s = m.esJob ? seccionJob( m, titulo, r ) : seccionTrans( m, titulo, r );
    s.datos = m.datos();
    return s;
  }

  // ---------------------------------------------------------------- transformacion

  static Seccion seccionTrans( CorridaMedida m, String titulo, Rotulos r ) {
    Seccion s = new Seccion( SeccionId.METRICAS, titulo );
    estado( s, m, r );
    PasoMedido lento = m.masLento();
    long filas = m.filasLeidas();
    String clave = m.enCurso ? "summary.trans.running" : "summary.trans." + m.fuente;
    StringBuilder res = new StringBuilder( r.f( clave, filas, duracion( m.ms(), r ) ) );
    if ( lento != null && lento.ms > 0 && !m.enCurso ) {
      res.append( ' ' ).append( r.f( "summary.slowest_step", lento.step, duracion( lento.ms, r ) ) );
    }
    if ( m.errores > 0 ) {
      res.append( ' ' ).append( r.f( "summary.errors", m.errores ) );
    }
    s.resumen = res.toString();

    Indicadores ind = new Indicadores();
    ind.id = "metrics-indicators";
    ind.con( new Indicador( r.s( "ind.duration" ), duracion( m.ms(), r ), r.s( "source." + m.fuente ), null ) );
    ind.con( new Indicador( r.s( "ind.rows_read" ), entero( filas, r ), r.s( "ind.rows_read.detail" ), null ) );
    ind.con( new Indicador( r.s( "ind.rows_written" ), entero( m.filasEscritas(), r ), r.s( "ind.rows_written.detail" ),
      null ) );
    ind.con( new Indicador( r.s( "ind.errors" ), entero( m.errores, r ), null, m.errores > 0 ? Indicador.ERROR
      : Indicador.OK ) );
    if ( lento != null ) {
      ind.con( new Indicador( r.s( "ind.slowest" ), lento.step, duracion( lento.ms, r ), null ) );
    }
    s.con( ind );
    avisos( s, m, r );
    if ( m.pasos.isEmpty() ) {
      return s;
    }

    // barras: filas escritas y ms por step, de mas lento a mas rapido, como mucho 25
    List<PasoMedido> orden = new ArrayList<PasoMedido>( m.pasos );
    Collections.sort( orden, new Comparator<PasoMedido>() {
      @Override
      public int compare( PasoMedido a, PasoMedido b ) {
        return Long.compare( b.ms, a.ms );
      }
    } );
    Grafico barras = new Grafico();
    barras.id = "metrics-steps";
    barras.clase = Grafico.BARRAS_H;
    barras.titulo = r.s( "chart.steps.title" );
    Serie fFilas = new Serie();
    fFilas.nombre = r.s( "chart.series.rows" );
    Serie fMs = new Serie();
    fMs.nombre = r.s( "chart.series.ms" );
    for ( int i = 0; i < orden.size() && i < MAX_BARRAS; i++ ) {
      PasoMedido p = orden.get( i );
      barras.categorias.add( p.step );
      fFilas.valores.add( (double) p.escritas );
      fMs.valores.add( (double) p.ms );
    }
    barras.series.add( fFilas );
    barras.series.add( fMs );
    if ( orden.size() > MAX_BARRAS ) {
      barras.nota = r.f( "chart.more", orden.size() - MAX_BARRAS );
    }
    s.con( barras );

    // Gantt: todos los steps hasta MAX_FILAS_GANTT; si hay mas, quedan los mas lentos (en el orden de la
    // corrida) y la nota dice cuales no entraron. Antes se cortaba por orden y se perdia justo el ultimo, que
    // suele ser la carga final y el mas lento.
    List<PasoMedido> enGantt = new ArrayList<PasoMedido>( m.pasos );
    List<String> fuera = new ArrayList<String>();
    if ( enGantt.size() > CorridaMedida.MAX_FILAS_GANTT ) {
      Set<PasoMedido> lentos = new HashSet<PasoMedido>( orden.subList( 0, CorridaMedida.MAX_FILAS_GANTT ) );
      enGantt.clear();
      for ( PasoMedido p : m.pasos ) {
        if ( lentos.contains( p ) ) {
          enGantt.add( p );
        } else {
          fuera.add( p.step );
        }
      }
    }
    Grafico gantt = new Grafico();
    gantt.id = "metrics-gantt";
    gantt.clase = Grafico.GANTT;
    gantt.titulo = r.s( "chart.gantt.title" );
    gantt.unidad = "ms";
    for ( PasoMedido p : enGantt ) {
      gantt.categorias.add( p.step );
      if ( m.metricasReales ) {
        if ( p.initIni > 0 ) {
          gantt.tramos.add( new Tramo( p.step, Tramo.INIT, p.initIni - m.inicio, p.initFin - m.inicio ) );
        }
        if ( p.execIni > 0 ) {
          gantt.tramos.add( new Tramo( p.step, Tramo.EXEC, p.execIni - m.inicio, p.execFin - m.inicio ) );
        }
      } else {
        gantt.tramos.add( new Tramo( p.step, Tramo.EXEC, 0, p.ms ) );
      }
    }
    // con metricas reales la leyenda del Gantt (GraficosSvg) ya dice que el solapamiento es normal
    String nota = m.metricasReales ? null : r.s( "chart.gantt.approx" );
    if ( !fuera.isEmpty() ) {
      String mas = r.f( "chart.gantt.fastest_out", fuera.size(), lista( fuera, 8, r ) );
      nota = nota == null ? mas : nota + " " + mas;
    }
    gantt.nota = nota;
    s.con( gantt );

    // ritmo: filas por segundo de los 5 steps con mas volumen, solo si la corrida da para verlo
    Bloque ritmo = ritmo( m, r );
    if ( ritmo != null ) {
      s.con( ritmo );
    }

    s.con( tablaPasos( m.pasos, r, "metrics-steps-table", r.s( "table.steps.title" ), null ) );
    return s;
  }

  /** "«a», «b», «c» y 3 más": como mucho {@code max} nombres entre comillas. */
  static String lista( List<String> nombres, int max, Rotulos r ) {
    StringBuilder sb = new StringBuilder();
    for ( int i = 0; i < nombres.size() && i < max; i++ ) {
      sb.append( i == 0 ? "" : ", " ).append( r.f( "quoted", nombres.get( i ) ) );
    }
    if ( nombres.size() > max ) {
      sb.append( ' ' ).append( r.f( "and_more", nombres.size() - max ) );
    }
    return sb.toString();
  }

  /**
   * El grafico de ritmo (filas por segundo de los 5 steps que mas filas escribieron, a lo largo de la
   * corrida), o una nota de una linea si la corrida fue tan corta que el grafico no diria nada (una de
   * medio segundo daba 3 puntos sueltos). null si no hubo snapshots (la corrida no tenia el monitoreo).
   */
  static Bloque ritmo( CorridaMedida m, Rotulos r ) {
    CorridaMedida.Snapshots sn = m.snapshots;
    if ( sn == null || sn.instantes.isEmpty() ) {
      return null;
    }
    if ( m.ms() < MIN_MS_RITMO || sn.instantes.size() < MIN_PUNTOS_RITMO ) {
      Parrafo p = new Parrafo( m.ms() < MIN_MS_RITMO ? r.f( "chart.line.short", duracion( m.ms(), r ) )
        : r.f( "chart.line.few", sn.instantes.size() ) );
      p.id = "metrics-throughput";
      return p;
    }
    return linea( m, r );
  }

  private static Grafico linea( CorridaMedida m, Rotulos r ) {
    CorridaMedida.Snapshots sn = m.snapshots;
    if ( sn == null || sn.instantes.size() < MIN_PUNTOS_RITMO ) {
      return null;
    }
    // cada snapshot trae las filas escritas DESDE el anterior: se pasan a filas por segundo con el
    // intervalo real (el primero, desde el inicio de la corrida)
    long[] dt = intervalos( sn.instantes, m.inicio );
    List<Map.Entry<String, List<Long>>> series = new ArrayList<Map.Entry<String, List<Long>>>( sn.porStep.entrySet() );
    Collections.sort( series, new Comparator<Map.Entry<String, List<Long>>>() {
      @Override
      public int compare( Map.Entry<String, List<Long>> a, Map.Entry<String, List<Long>> b ) {
        return Long.compare( suma( b.getValue() ), suma( a.getValue() ) );
      }
    } );
    Grafico g = new Grafico();
    g.id = "metrics-throughput";
    g.clase = Grafico.LINEA;
    g.titulo = r.s( "chart.line.title" );
    g.unidad = r.s( "chart.line.unit" );
    g.nota = r.s( "chart.line.note" );
    for ( Long x : sn.instantes ) {
      g.categorias.add( x < 0 ? "" : segundos( x - m.inicio, r ) );
    }
    for ( int i = 0; i < series.size() && g.series.size() < MAX_SERIES; i++ ) {
      Map.Entry<String, List<Long>> e = series.get( i );
      if ( suma( e.getValue() ) == 0 ) {
        continue;
      }
      Serie se = new Serie();
      se.nombre = e.getKey();
      for ( int k = 0; k < sn.instantes.size(); k++ ) {
        Long filas = k < e.getValue().size() ? e.getValue().get( k ) : null;
        se.valores.add( filas == null ? null : (double) Math.round( filas * 1000.0 / dt[ k ] ) );
      }
      g.series.add( se );
    }
    return g.series.isEmpty() ? null : g;
  }

  /**
   * ms entre cada snapshot y el anterior (el primero, desde el inicio). Si un instante falta o no avanza,
   * la mediana de los intervalos buenos (o el de Medir...): nunca 0.
   */
  static long[] intervalos( List<Long> instantes, long inicio ) {
    int n = instantes.size();
    long[] dt = new long[ n ];
    List<Long> buenos = new ArrayList<Long>();
    for ( int k = 0; k < n; k++ ) {
      long x = instantes.get( k ) == null ? -1 : instantes.get( k );
      long antes = k == 0 ? inicio : instantes.get( k - 1 ) == null ? -1 : instantes.get( k - 1 );
      dt[ k ] = x > 0 && antes > 0 && x > antes ? x - antes : -1;
      if ( dt[ k ] > 0 && k > 0 ) {
        buenos.add( dt[ k ] );
      }
    }
    Collections.sort( buenos );
    long tipico = buenos.isEmpty() ? CorridaMedida.INTERVALO_SNAPSHOT_MS : buenos.get( buenos.size() / 2 );
    for ( int k = 0; k < n; k++ ) {
      if ( dt[ k ] <= 0 ) {
        dt[ k ] = tipico;
      }
    }
    return dt;
  }

  private static long suma( List<Long> l ) {
    long n = 0;
    for ( Long x : l ) {
      n += x == null ? 0 : x;
    }
    return n;
  }

  /** Tabla de StepStatus; con 'trans' agrega la columna de la sub-transformacion (jobs). */
  static Tabla tablaPasos( List<PasoMedido> pasos, Rotulos r, String id, String titulo, List<String> trans ) {
    boolean actualizadas = false;
    boolean rechazadas = false;
    boolean copias = false;
    for ( PasoMedido p : pasos ) {
      actualizadas |= p.actualizadas > 0;
      rechazadas |= p.rechazadas > 0;
      copias |= p.copias > 1;
    }
    List<String> cols = new ArrayList<String>();
    List<String> alin = new ArrayList<String>();
    if ( trans != null ) {
      cols.add( r.s( "col.trans" ) );
      alin.add( "l" );
    }
    cols.add( r.s( "col.step" ) );
    alin.add( "l" );
    if ( copias ) {
      cols.add( r.s( "col.copies" ) );
      alin.add( "r" );
    }
    for ( String k : new String[] { "col.read", "col.written", "col.input", "col.output" } ) {
      cols.add( r.s( k ) );
      alin.add( "r" );
    }
    if ( actualizadas ) {
      cols.add( r.s( "col.updated" ) );
      alin.add( "r" );
    }
    if ( rechazadas ) {
      cols.add( r.s( "col.rejected" ) );
      alin.add( "r" );
    }
    for ( String k : new String[] { "col.errors", "col.status", "col.time", "col.speed" } ) {
      cols.add( r.s( k ) );
      alin.add( "col.status".equals( k ) ? "l" : "r" );
    }
    Tabla t = new Tabla( cols.toArray( new String[ 0 ] ) );
    t.id = id;
    t.titulo = titulo;
    t.alineacion = alin;
    int n = 0;
    for ( int i = 0; i < pasos.size(); i++ ) {
      if ( n >= MAX_FILAS_TABLA ) {
        t.pie = r.f( "table.more_rows", pasos.size() - n );
        break;
      }
      PasoMedido p = pasos.get( i );
      List<String> f = new ArrayList<String>();
      if ( trans != null ) {
        f.add( trans.get( i ) );
      }
      f.add( p.step );
      if ( copias ) {
        f.add( entero( p.copias, r ) );
      }
      f.add( entero( p.leidas, r ) );
      f.add( entero( p.escritas, r ) );
      f.add( entero( p.entrada, r ) );
      f.add( entero( p.salida, r ) );
      if ( actualizadas ) {
        f.add( entero( p.actualizadas, r ) );
      }
      if ( rechazadas ) {
        f.add( entero( p.rechazadas, r ) );
      }
      f.add( entero( p.errores, r ) );
      f.add( p.estado == null ? "" : r.s( "status." + p.estado ) );
      f.add( duracion( p.ms, r ) );
      f.add( entero( Math.round( p.filasPorSegundo() ), r ) );
      t.filas.add( f );
      n++;
    }
    return t;
  }

  // ---------------------------------------------------------------- job

  static Seccion seccionJob( CorridaMedida m, String titulo, Rotulos r ) {
    Seccion s = new Seccion( SeccionId.METRICAS, titulo );
    estado( s, m, r );
    EntradaMedida lenta = m.entradaMasLenta();
    int ejecutadas = 0;
    for ( EntradaMedida e : m.entradas ) {
      if ( e.nivel == 0 && !esStart( e ) ) {
        ejecutadas++;
      }
    }
    String fin = m.enCurso ? r.s( "summary.job.running" )
      : r.s( Boolean.FALSE.equals( m.resultado ) || m.errores > 0 ? "summary.job.failed" : "summary.job.ok" );
    StringBuilder res = new StringBuilder( r.f( "summary.job." + m.fuente, duracion( m.ms(), r ), fin, ejecutadas ) );
    if ( lenta != null && !m.enCurso ) {
      res.append( ' ' ).append( r.f( "summary.slowest_entry", lenta.nombre, duracion( lenta.ms(), r ) ) );
    }
    if ( m.hayFilas() ) {
      res.append( ' ' ).append( r.f( "summary.job.rows", m.filasLeidas(), m.filasEscritas() ) );
    }
    if ( m.errores > 0 ) {
      res.append( ' ' ).append( r.f( "summary.errors", m.errores ) );
    }
    s.resumen = res.toString();

    Indicadores ind = new Indicadores();
    ind.id = "metrics-indicators";
    ind.con( new Indicador( r.s( "ind.duration" ), duracion( m.ms(), r ), r.s( "source." + m.fuente ), null ) );
    String resultado;
    String nivel;
    if ( m.enCurso || m.resultado == null ) {
      resultado = r.s( "ind.result.running" );
      nivel = Indicador.WARN;
    } else if ( m.resultado && m.errores == 0 ) {
      resultado = r.s( "ind.result.ok" );
      nivel = Indicador.OK;
    } else {
      resultado = r.s( "ind.result.failed" );
      nivel = Indicador.ERROR;
    }
    ind.con( new Indicador( r.s( "ind.result" ), resultado, null, nivel ) );
    ind.con( new Indicador( r.s( "ind.entries" ), entero( ejecutadas, r ), null, null ) );
    if ( m.hayFilas() ) {
      ind.con( new Indicador( r.s( "ind.rows_read" ), entero( m.filasLeidas(), r ), r.s( "ind.rows_read.detail" ),
        null ) );
      ind.con( new Indicador( r.s( "ind.rows_written" ), entero( m.filasEscritas(), r ), r.s(
        "ind.rows_written.detail" ), null ) );
    }
    ind.con( new Indicador( r.s( "ind.errors" ), entero( m.errores, r ), null, m.errores > 0 ? Indicador.ERROR
      : Indicador.OK ) );
    if ( lenta != null ) {
      ind.con( new Indicador( r.s( "ind.slowest_entry" ), lenta.nombre, duracion( lenta.ms(), r ), null ) );
    }
    s.con( ind );
    avisos( s, m, r );
    if ( !m.hayFilas() && CorridaMedida.ULTIMA.equals( m.fuente ) && !m.entradas.isEmpty() ) {
      s.con( aviso( Aviso.INFO, r.s( "metrics.job_rows_hint" ), "metrics-job-rows" ) );
    }
    if ( m.entradas.isEmpty() ) {
      return s;
    }

    // Gantt por entrada: los sub-jobs y los steps de cada sub-transformacion anidados
    Grafico gantt = new Grafico();
    gantt.id = "metrics-gantt";
    gantt.clase = Grafico.GANTT;
    gantt.titulo = r.s( "chart.gantt.job.title" );
    gantt.unidad = "ms";
    Set<String> usadas = new HashSet<String>();
    int filas = 0;
    int sinLugar = 0;
    boolean anidados = false;
    for ( EntradaMedida e : m.entradas ) {
      if ( esStart( e ) ) {
        continue;
      }
      if ( filas >= CorridaMedida.MAX_FILAS_GANTT ) {
        sinLugar++;
        continue;
      }
      String cat = unica( sangria( e.nivel ) + e.nombre, usadas );
      gantt.categorias.add( cat );
      filas++;
      long ini = e.ini < 0 ? 0 : e.ini - m.inicio;
      long fi = e.fin < 0 ? m.fin - m.inicio : e.fin - m.inicio;
      gantt.tramos.add( new Tramo( cat, Tramo.EXEC, ini, Math.max( ini, fi ) ) );
      if ( e.hija != null ) {
        for ( PasoMedido p : e.hija.pasos ) {
          if ( p.execIni <= 0 ) {
            continue;
          }
          if ( filas >= CorridaMedida.MAX_FILAS_GANTT ) {
            sinLugar++;
            continue;
          }
          String sc = unica( sangria( e.nivel + 1 ) + p.step, usadas );
          gantt.categorias.add( sc );
          anidados = true;
          filas++;
          if ( p.initIni > 0 ) {
            gantt.tramos.add( new Tramo( sc, Tramo.INIT, p.initIni - m.inicio, p.initFin - m.inicio ) );
          }
          gantt.tramos.add( new Tramo( sc, Tramo.EXEC, p.execIni - m.inicio, p.execFin - m.inicio ) );
        }
      }
    }
    gantt.nota = sinLugar > 0 ? r.f( "chart.gantt.job.more", sinLugar ) : r.s( "chart.gantt.job.note" );
    // las entradas del job corren una tras otra: la leyenda de siempre ("los steps corren en paralelo")
    // solo vale para los steps anidados de las sub-transformaciones
    gantt.leyenda = r.s( anidados ? "chart.gantt.job.legend_nested" : "chart.gantt.job.legend" );
    if ( !gantt.tramos.isEmpty() ) {
      s.con( gantt );
    }

    // tabla de entradas
    Tabla t = new Tabla( r.s( "col.entry" ), r.s( "col.job" ), r.s( "col.start" ), r.s( "col.duration" ), r.s(
      "col.result" ), r.s( "col.reason" ) );
    t.id = "metrics-entries";
    t.titulo = r.s( "table.entries.title" );
    t.alineacion = new ArrayList<String>( java.util.Arrays.asList( "l", "l", "r", "r", "l", "l" ) );
    int n = 0;
    for ( EntradaMedida e : m.entradas ) {
      if ( n >= MAX_FILAS_TABLA ) {
        t.pie = r.f( "table.more_rows", m.entradas.size() - n );
        break;
      }
      String res2 = e.ok == null ? r.s( "ind.result.running" ) : r.s( e.ok ? "ind.result.ok" : "ind.result.failed" );
      t.fila( sangria( e.nivel ) + e.nombre, e.job, e.ini < 0 ? "" : "+" + duracion( e.ini - m.inicio, r ), e.ms() < 0
        ? "" : duracion( e.ms(), r ), res2, razon( e, r ) );
      n++;
    }
    s.con( t );

    // steps de las sub-transformaciones (solo run_copy)
    if ( !m.hijas.isEmpty() ) {
      List<PasoMedido> todos = new ArrayList<PasoMedido>();
      List<String> nombres = new ArrayList<String>();
      for ( TransHija h : m.hijas ) {
        for ( PasoMedido p : h.pasos ) {
          todos.add( p );
          nombres.add( h.nombre );
        }
      }
      s.con( tablaPasos( todos, r, "metrics-subtrans", r.s( "table.subtrans.title" ), nombres ) );
    }
    return s;
  }

  /**
   * Por que hop llego la entrada, en el idioma del informe (el texto de Kettle viene en el de Spoon y
   * queda solo en los datos). Si Kettle trae un motivo desconocido, ese texto.
   */
  static String razon( EntradaMedida e, Rotulos r ) {
    if ( e.razonCodigo != null ) {
      return r.s( "reason." + e.razonCodigo );
    }
    return e.razon == null ? "" : e.razon;
  }

  /** La entrada START (la de cada sub-job tambien): no hace nada y no va al Gantt ni a la cuenta. */
  private static boolean esStart( EntradaMedida e ) {
    return "START".equalsIgnoreCase( e.nombre ) && e.ms() <= 5;
  }

  /** Sangria visible para las filas anidadas (espacios duros: el SVG colapsa los comunes). */
  static String sangria( int nivel ) {
    if ( nivel <= 0 ) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for ( int i = 1; i < nivel; i++ ) {
      sb.append( "  " );
    }
    return sb.append( "↳ " ).toString();
  }

  /** Categoria unica del Gantt (dos entradas con el mismo nombre irian a la misma fila). */
  private static String unica( String cat, Set<String> usadas ) {
    String c = cat;
    int i = 2;
    while ( !usadas.add( c ) ) {
      c = cat + " (" + i++ + ")";
    }
    return c;
  }

  // ---------------------------------------------------------------- comunes

  private static void estado( Seccion s, CorridaMedida m, Rotulos r ) {
    if ( m.enCurso ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.s( "metrics.running" );
    } else if ( m.cancelada ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.s( "metrics.cancelled" );
    } else if ( m.cortada ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.f( "metrics.timeout", duracion( m.ms(), r ) );
    } else if ( m.falloInicio != null ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.f( "metrics.init_failed", m.falloInicio );
    }
  }

  private static void avisos( Seccion s, CorridaMedida m, Rotulos r ) {
    if ( CorridaMedida.COPIA.equals( m.fuente ) ) {
      s.con( aviso( Aviso.INFO, r.s( "metrics.copy_wrote" ), "metrics-copy" ) );
    }
    if ( Seccion.PARCIAL.equals( s.estado ) && s.motivo != null ) {
      s.con( aviso( Aviso.WARN, s.motivo, "metrics-partial" ) );
    }
    String error = primerError( m.log );
    if ( error != null ) {
      s.con( aviso( Aviso.ERROR, r.f( "metrics.first_error", error ), "metrics-error" ) );
    }
    if ( !m.esJob && !m.metricasReales && !m.pasos.isEmpty() ) {
      s.con( aviso( Aviso.INFO, r.s( "metrics.approx" ), "metrics-approx" ) );
    }
  }

  /** El progreso del contexto, cancelado tambien si el contexto se cancela (el pedido se reemplazo). */
  static Progreso progreso( final Contexto ctx ) {
    return new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
        ctx.progreso().paso( texto, hecho, total );
      }

      @Override
      public boolean cancelado() {
        return ctx.cancelado() || ctx.progreso().cancelado();
      }
    };
  }

  /** La primera linea con ERROR de la cola del log (ya limpia por RunMotor.colaLog), o null. */
  static String primerError( String log ) {
    if ( log == null ) {
      return null;
    }
    for ( String l : log.split( "\\r?\\n" ) ) {
      int i = l.indexOf( "ERROR:" );
      if ( i >= 0 ) {
        String x = l.substring( i + 6 ).trim();
        if ( !x.isEmpty() ) {
          return x.length() > 300 ? x.substring( 0, 300 ) + "..." : x;
        }
      }
    }
    return null;
  }

  static Aviso aviso( String nivel, String texto, String id ) {
    Aviso a = new Aviso( nivel, texto );
    a.id = id;
    return a;
  }

  /** "227 ms", "2,7 s", "3 min 05 s", "1 h 02 min" con el separador del idioma. */
  static String duracion( long ms, Rotulos r ) {
    if ( ms < 0 ) {
      return "";
    }
    if ( ms < 1000 ) {
      return r.f( "dur.ms", ms );
    }
    if ( ms < 60000 ) {
      return r.f( "dur.s", ms / 1000.0 );
    }
    long s = ms / 1000;
    if ( s < 3600 ) {
      return r.f( "dur.min", s / 60, s % 60 );
    }
    return r.f( "dur.h", s / 3600, ( s % 3600 ) / 60 );
  }

  /** Segundos con un decimal ("0,5 s"), para el eje X del grafico de linea. */
  static String segundos( long ms, Rotulos r ) {
    return r.f( "dur.s", Math.max( 0, ms ) / 1000.0 );
  }

  /** Entero con el separador de miles del idioma ("400.000" en es, "400,000" en en). */
  static String entero( long n, Rotulos r ) {
    return String.format( r.locale(), "%,d", n );
  }
}
