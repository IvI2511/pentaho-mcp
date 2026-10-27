package io.github.ivi2511.pentahomcp.traductor;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Qué traductor tiene cada tipo de step (por id de plugin). Cada familia registra los suyos en su propia clase, así
 * cada una se trabaja por separado: {@link TradEntradas}, {@link TradSalidas}, {@link TradCampos}, {@link TradFilas}.
 * Un tipo sin traductor queda como bloque MANUAL ({@link TraductorTrans}).
 */
public final class Registro {

  private static final Map<String, TraductorStep> STEPS = new TreeMap<>( String.CASE_INSENSITIVE_ORDER );

  static {
    TradEntradas.registrar();
    TradSalidas.registrar();
    TradCampos.registrar();
    TradFilas.registrar();
  }

  private Registro() {
  }

  /** Registra el traductor para uno o más ids de plugin (el último que registra un id gana). */
  public static void step( TraductorStep t, String... ids ) {
    for ( String id : ids ) {
      STEPS.put( id, t );
    }
  }

  public static TraductorStep buscar( String id ) {
    return id == null ? null : STEPS.get( id );
  }

  /** Ids de plugin con traductor (para la tabla de cobertura). */
  public static Map<String, TraductorStep> steps() {
    return Collections.unmodifiableMap( STEPS );
  }
}
