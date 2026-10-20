package io.github.ivi2511.pentahomcp.lint;

import java.util.Locale;

/**
 * Severidad de un hallazgo (docs/linter-diseno.md §0 y §4): error = el resultado sale mal, no corre o deja
 * una contraseña en claro; warning = frágil o depende de la máquina; info = estilo o documentación.
 */
public enum Sev {
  ERROR( "error", 25 ), WARNING( "warning", 8 ), INFO( "info", 2 );

  private final String codigo;
  private final int peso;

  Sev( String codigo, int peso ) {
    this.codigo = codigo;
    this.peso = peso;
  }

  /** Como va en el JSON y en los textos: error, warning, info. */
  public String codigo() {
    return codigo;
  }

  /** Puntos que resta cada hallazgo (§4). */
  public int peso() {
    return peso;
  }

  /** Tope de puntos por regla: el doble del peso de su peor severidad (50 / 16 / 4). */
  public int tope() {
    return 2 * peso;
  }

  /** true si this es al menos tan grave como otra. */
  public boolean almenos( Sev otra ) {
    return ordinal() <= otra.ordinal();
  }

  public static Sev peor( Sev a, Sev b ) {
    if ( a == null ) {
      return b;
    }
    if ( b == null ) {
      return a;
    }
    return a.ordinal() <= b.ordinal() ? a : b;
  }

  /** Acepta error/warning/info (y e/w/i); null si no es ninguna. */
  public static Sev desde( String s ) {
    if ( s == null ) {
      return null;
    }
    String x = s.trim().toLowerCase( Locale.ROOT );
    for ( Sev v : values() ) {
      if ( v.codigo.equals( x ) || v.codigo.substring( 0, 1 ).equals( x ) ) {
        return v;
      }
    }
    return null;
  }
}
