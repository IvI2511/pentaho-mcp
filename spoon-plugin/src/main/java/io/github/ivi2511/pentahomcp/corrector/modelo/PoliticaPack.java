package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Bloque {@code policy} del pack (diseño §2.2 y §7): que steps condicionales se permiten, topes y el entorno fijo
 * de la JVM hija. Los peligrosos no se habilitan desde el pack: solo con el modo confianza, que se activa en la UI
 * por corrida y queda en {@link ResultadoCorreccion#modoConfianza}.
 * <p>
 * Sin {@code locale}/{@code timezone}/{@code encoding} en el JSON valen en_US, UTC y UTF-8: el mismo resultado en
 * cualquier PC (P9, P13, P16 y P17 cambian la nota segun el entorno). create_exercise_pack escribe los del docente.
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class PoliticaPack {

  /** unknown_steps: un step que no esta en ninguna lista bloquea la entrega (por defecto). */
  public static final String BLOQUEAR = "block";
  /** unknown_steps: los desconocidos corren. */
  public static final String PERMITIR = "allow";

  /** Niveles de la politica (§7), como van en {@code blocked_steps[].level}. */
  public static final String SEGURO = "safe";
  public static final String CONDICIONAL = "conditional";
  public static final String PELIGROSO = "dangerous";
  public static final String DESCONOCIDO = "unknown";
  public static final List<String> NIVELES = Collections.unmodifiableList( Arrays.asList( SEGURO, CONDICIONAL,
    PELIGROSO, DESCONOCIDO ) );

  /** Ids de plugin de nivel condicional que corren en este ejercicio (TableOutput, ExecSQL...). */
  @JsonProperty( "allow_steps" )
  public List<String> permitirSteps = new ArrayList<>();
  /** {@link #BLOQUEAR} o {@link #PERMITIR}. */
  @JsonProperty( "unknown_steps" )
  public String stepsDesconocidos = BLOQUEAR;
  /** Tope por caso; el vigilante del padre mata al worker a los timeout_s x casos + 60 s. */
  @JsonProperty( "timeout_s" )
  public int timeoutSeg = 60;
  /** Filas capturadas como mucho (tambien al leer la salida a archivo). */
  @JsonProperty( "max_rows" )
  public long maxFilas = 100000;
  /** Tope de salida/ + tmp/ del caso, vigilado cada segundo (P18). */
  @JsonProperty( "max_output_mb" )
  public int maxSalidaMb = 50;
  /** -Duser.language/-Duser.country de la hija: "es_ES", "en_US" (tambien "es-ES"). */
  @JsonProperty( "locale" )
  public String locale = "en_US";
  /** -Duser.timezone de la hija ("Europe/Madrid", "UTC"). */
  @JsonProperty( "timezone" )
  public String zonaHoraria = "UTC";
  /** -Dfile.encoding de la hija; tambien el de un Text file output con encoding vacio (P17). */
  @JsonProperty( "encoding" )
  public String encoding = "UTF-8";

  /** El {@link #locale} como Locale de Java ("es_ES" o "es-ES" -> es_ES); en_US si esta vacio. */
  public Locale localeJava() {
    String l = locale == null ? "" : locale.trim().replace( '-', '_' );
    if ( l.isEmpty() ) {
      return Locale.US;
    }
    String[] p = l.split( "_", 3 );
    return p.length == 1 ? new Locale( p[ 0 ] ) : p.length == 2 ? new Locale( p[ 0 ], p[ 1 ] )
      : new Locale( p[ 0 ], p[ 1 ], p[ 2 ] );
  }

  /** true si el pack habilita ese step condicional (por id de plugin, sin mayusculas). */
  public boolean permite( String idPlugin ) {
    if ( idPlugin == null || permitirSteps == null ) {
      return false;
    }
    for ( String s : permitirSteps ) {
      if ( s != null && s.trim().equalsIgnoreCase( idPlugin ) ) {
        return true;
      }
    }
    return false;
  }

  public boolean bloqueaDesconocidos() {
    return !PERMITIR.equals( stepsDesconocidos );
  }
}
