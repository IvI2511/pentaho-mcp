package io.github.ivi2511.pentahomcp.informes.graficos;

import java.text.Normalizer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ancho estimado de un texto en Segoe UI / Arial, sin AWT (deterministico y sin fuentes instaladas):
 * metricas de Arial en milesimas de em, con 5 % de margen. Publica para los diagramas propios
 * ({@code informes.diagramas}); es la misma tabla que {@code GraficosSvg.Medida} (mas las flechas ← → y
 * el ×, que usan los diagramas).
 *
 * @dueño WP6
 */
public final class Medida {

  /** Anchos de Arial (1/1000 em) para ASCII 32..126. */
  private static final int[] ASCII = {
    278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // 32-47
    556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 48-63
    1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // 64-79
    667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // 80-95
    333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // 96-111
    556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584 }; // 112-126

  private static final Map<Character, Integer> CACHE = new ConcurrentHashMap<>();

  private Medida() {
  }

  /** Ancho en px de s escrito a tam px (regular; la negrita es ~8 % mas ancha). */
  public static double ancho( String s, double tam ) {
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
  public static String recortar( String s, double maxPx, double tam ) {
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
      case ' ':
        return 278;
      case '←':
      case '→':
        return 1000;
      case '×':
        return 584;
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
