package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.List;

/**
 * Verificación de una traducción (0.8.0): compara lo que produce la transformación en PDI con lo que produce el
 * Python generado, sin escribir en los archivos ni en las bases de la persona. ESQUELETO: se completa en esta rama.
 */
public final class Verificacion {

  public static final String IGUAL = "equal";
  public static final String DISTINTO = "different";
  public static final String SIN_VERIFICAR = "not_verified";

  private Verificacion() {
  }

  /** El resultado de una salida (un step que escribe o un step final). */
  public static final class Salida {
    public String paso;
    public String estado = SIN_VERIFICAR;
    public String motivo;
    public int filasPdi = -1;
    public int filasPython = -1;
    /** Las primeras diferencias, legibles (fila, columna, PDI vs Python). */
    public final List<String> diferencias = new ArrayList<>();
  }

  /** El resultado de toda la verificación. */
  public static final class Resultado {
    /** equal (todas iguales), different (alguna distinta) o not_verified (ninguna se pudo comparar). */
    public String estado = SIN_VERIFICAR;
    /** Por qué no se verificó (sin Python, sin pandas...), si estado es not_verified. */
    public String motivo;
    public final List<Salida> salidas = new ArrayList<>();
  }
}
