package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.Objects;

/**
 * Aserciones de las pruebas (sin JUnit: PDI no lo trae). Uso: {@code import static
 * io.github.ivi2511.pentahomcp.Verificador.*;}
 * <p>
 * Una asercion que no se cumple lanza {@link Falla}; {@link #saltear(String)} lanza {@link Salteada}
 * y el runner la cuenta como salteada, no como falla (por ejemplo, si no hay Edge ni Chrome).
 *
 * @dueño WP0
 */
public final class Verificador {

  /** Asercion fallida. */
  public static final class Falla extends AssertionError {
    private static final long serialVersionUID = 1L;

    public Falla( String msg ) {
      super( msg );
    }
  }

  /** La prueba no aplica en esta maquina (falta un navegador, etc.). */
  public static final class Salteada extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Salteada( String motivo ) {
      super( motivo );
    }
  }

  /** Codigo que puede lanzar cualquier cosa, para {@link #lanza}. */
  public interface Accion {
    void correr() throws Exception;
  }

  private Verificador() {
  }

  public static void cierto( boolean c, String msg ) {
    if ( !c ) {
      throw new Falla( msg );
    }
  }

  public static void falso( boolean c, String msg ) {
    if ( c ) {
      throw new Falla( msg );
    }
  }

  public static void igual( Object esperado, Object real, String msg ) {
    if ( !Objects.equals( esperado, real ) ) {
      throw new Falla( msg + ": se esperaba <" + esperado + "> y llego <" + real + ">" );
    }
  }

  public static void distinto( Object noEsperado, Object real, String msg ) {
    if ( Objects.equals( noEsperado, real ) ) {
      throw new Falla( msg + ": no se esperaba <" + real + ">" );
    }
  }

  public static void noNulo( Object o, String msg ) {
    if ( o == null ) {
      throw new Falla( msg + ": es null" );
    }
  }

  public static void nulo( Object o, String msg ) {
    if ( o != null ) {
      throw new Falla( msg + ": se esperaba null y llego <" + o + ">" );
    }
  }

  public static void contiene( String texto, String parte, String msg ) {
    if ( texto == null || parte == null || !texto.contains( parte ) ) {
      throw new Falla( msg + ": no contiene <" + parte + ">" + muestra( texto ) );
    }
  }

  public static void noContiene( String texto, String parte, String msg ) {
    if ( texto != null && parte != null && texto.contains( parte ) ) {
      int i = texto.indexOf( parte );
      throw new Falla( msg + ": contiene <" + parte + "> en la posicion " + i + muestra(
        texto.substring( Math.max( 0, i - 60 ), Math.min( texto.length(), i + parte.length() + 60 ) ) ) );
    }
  }

  /** El archivo existe y mide al menos minBytes. */
  public static void archivo( File f, long minBytes, String msg ) {
    if ( f == null || !f.isFile() ) {
      throw new Falla( msg + ": no existe el archivo " + f );
    }
    if ( f.length() < minBytes ) {
      throw new Falla( msg + ": " + f + " mide " + f.length() + " bytes (minimo " + minBytes + ")" );
    }
  }

  /** La accion tiene que lanzar una excepcion de ese tipo; la devuelve. */
  public static <T extends Throwable> T lanza( Class<T> tipo, Accion a, String msg ) {
    try {
      a.correr();
    } catch ( Throwable t ) {
      if ( tipo.isInstance( t ) ) {
        return tipo.cast( t );
      }
      throw new Falla( msg + ": se esperaba " + tipo.getSimpleName() + " y llego " + t );
    }
    throw new Falla( msg + ": se esperaba " + tipo.getSimpleName() + " y no lanzo nada" );
  }

  public static void falla( String msg ) {
    throw new Falla( msg );
  }

  public static void saltear( String motivo ) {
    throw new Salteada( motivo );
  }

  private static String muestra( String texto ) {
    if ( texto == null ) {
      return " (texto null)";
    }
    String t = texto.length() > 300 ? texto.substring( 0, 300 ) + "..." : texto;
    return "\n--- texto ---\n" + t;
  }
}
