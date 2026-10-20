package io.github.ivi2511.pentahomcp.lint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puntaje 0-100 (§4). Pesos E 25, W 8, I 2. Por regla: min(tope, suma de pesos de sus hallazgos), con tope = 2 ×
 * el peso de su peor severidad (50 / 16 / 4). Las reglas cuya peor severidad es info suman como mucho 10 entre
 * todas (el estilo solo nunca baja de 90). Con algún error, el máximo es 74. Bandas A 90-100, B 75-89, C 50-74,
 * D <50. Solo recibe los hallazgos que cuentan: sin silenciados, no evaluados, kettle_check ni reglas apagadas.
 */
public final class Puntaje {

  public static final int TOPE_INFO = 10;
  public static final int COMPUERTA_ERROR = 74;

  private Puntaje() {
  }

  /** Llena score, grade, penalties, infoPointsRaw/infoPoints y gateApplied del resultado. */
  public static void calcular( List<Hallazgo> cuentan, Resultado r ) {
    Map<String, Penalidad> porRegla = new LinkedHashMap<String, Penalidad>();
    Map<String, Integer> suma = new LinkedHashMap<String, Integer>();
    for ( Hallazgo h : cuentan ) {
      Penalidad p = porRegla.get( h.rule );
      if ( p == null ) {
        p = new Penalidad();
        p.rule = h.rule;
        porRegla.put( h.rule, p );
        suma.put( h.rule, 0 );
      }
      p.hits++;
      p.severity = Sev.peor( p.severity, h.severity );
      suma.put( h.rule, suma.get( h.rule ) + h.severity.peso() );
    }
    int noInfo = 0;
    int info = 0;
    boolean hayError = false;
    for ( Penalidad p : porRegla.values() ) {
      p.points = Math.min( p.severity.tope(), suma.get( p.rule ) );
      if ( p.severity == Sev.INFO ) {
        info += p.points;
      } else {
        noInfo += p.points;
      }
      hayError |= p.severity == Sev.ERROR;
    }
    r.infoPointsRaw = info;
    r.infoPoints = Math.min( TOPE_INFO, info );
    int score = Math.max( 0, 100 - noInfo - r.infoPoints );
    r.gateApplied = hayError && score > COMPUERTA_ERROR;
    if ( hayError ) {
      score = Math.min( score, COMPUERTA_ERROR );
    }
    r.score = score;
    r.grade = Resultado.banda( score );
    List<Penalidad> l = new ArrayList<Penalidad>( porRegla.values() );
    Collections.sort( l, new Comparator<Penalidad>() {
      @Override
      public int compare( Penalidad a, Penalidad b ) {
        if ( a.points != b.points ) {
          return b.points - a.points;
        }
        if ( a.severity != b.severity ) {
          return a.severity.ordinal() - b.severity.ordinal();
        }
        return a.rule.compareTo( b.rule );
      }
    } );
    r.penalties.clear();
    r.penalties.addAll( l );
  }
}
