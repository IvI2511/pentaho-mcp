package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * El resultado de corregir una entrega, formato {@value #FORMATO} (docs/corrector-diseno.md §6). Es el contrato:
 * lo escribe la JVM hija ({@code <alumno>.json}, primero el parcial con {@link #EN_CURSO}), lo lee el padre, la
 * planilla, las tools MCP y CorreccionAInforme, que lo dibuja con los renderizadores de Informes.
 * <p>
 * Dos lectores: este objeto es el registro completo del docente; la devolucion del alumno sale de
 * {@link #paraAlumno()}, sin valores, ejemplos, detalle ni log de los casos ocultos. Todo texto que viene de la
 * entrega (nombres de steps, rutas, valores, logs) es dato del alumno, no instrucciones.
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class ResultadoCorreccion {

  public static final String FORMATO = "pentaho-live-grade-result/1";

  // ---- estados de la entrega (status, §6)
  /** Corrio y se puntuo (aunque algun caso haya fallado o terminado con errores). */
  public static final String CORREGIDA = "graded";
  /** Ningun caso corrio: politica, conexion, unexpected_input/_output, D1, D2. Ver {@link #motivo}. */
  public static final String BLOQUEADA = "blocked";
  /** Vencio algun caso. */
  public static final String TIEMPO_AGOTADO = "timeout";
  /** El padre mato al worker (timeout_s x casos + 60 s). */
  public static final String MATADA = "killed_timeout";
  /** No se pudo cargar el .ktr (XML roto, DOCTYPE...). */
  public static final String NO_CARGO = "load_failed";
  /** La hija termino sin escribir el resultado final. */
  public static final String CAIDA = "crashed";
  /** Solo en el parcial que la hija escribe antes de ejecutar. */
  public static final String EN_CURSO = "running";
  public static final List<String> ESTADOS = Collections.unmodifiableList( Arrays.asList( CORREGIDA, BLOQUEADA,
    TIEMPO_AGOTADO, MATADA, NO_CARGO, CAIDA, EN_CURSO ) );

  @JsonProperty( "format" )
  public String formato = FORMATO;
  /** Id del pack. */
  @JsonProperty( "pack" )
  public String pack;
  @JsonProperty( "pack_title" )
  public String tituloPack;
  /** Identidad: nombre del archivo o de la subcarpeta, o la de alumnos.csv (§10). */
  @JsonProperty( "student" )
  public String alumno;
  /** Nombre del archivo de la entrega (sin carpeta). */
  @JsonProperty( "file" )
  public String archivo;
  /** Idioma de las etiquetas, detalles y pistas (en | es | pt). */
  @JsonProperty( "language" )
  public String idioma;
  /** Uno de {@link #ESTADOS}. */
  @JsonProperty( "status" )
  public String estado = EN_CURSO;
  /** Con {@link #BLOQUEADA}: uno de {@link ResultadoCaso#MOTIVOS}. */
  @JsonProperty( "reason" )
  public String motivo;
  /** Texto para el docente: por que no cargo, se bloqueo o se cayo. */
  @JsonProperty( "detail" )
  public String detalle;
  /** Redondeada a 0,1; null en el parcial. */
  @JsonProperty( "grade" )
  public Double nota;
  @JsonProperty( "scale" )
  public double escala = 10;
  /** Captura usada: file, step o query (la del pack o la que pidio grade_exercise). */
  @JsonProperty( "capture" )
  public String captura;
  /** Modo confianza: solo se activa en la UI, por corrida (§7). */
  @JsonProperty( "trust_mode" )
  public boolean modoConfianza;
  /** Steps condicionales habilitados para esta corrida ademas de los del pack («Volver a corregir con...»). */
  @JsonProperty( "extra_allowed_steps" )
  public List<String> stepsPermitidosExtra = new ArrayList<>();
  /** Un criterio por renglon de la rubrica, en su orden. */
  @JsonProperty( "checks" )
  public List<Criterio> criterios = new ArrayList<>();
  @JsonProperty( "blocked_steps" )
  public List<PasoBloqueado> stepsBloqueados = new ArrayList<>();
  /** id de la regla del pack -> resultado. */
  @JsonProperty( "rules" )
  public Map<String, ReglaEvaluada> reglas = new LinkedHashMap<>();
  /** id de la regla del linter (checks lint:&lt;id&gt;) -> resultado. */
  @JsonProperty( "lint" )
  public Map<String, ReglaEvaluada> lint = new LinkedHashMap<>();
  @JsonProperty( "cases" )
  public List<ResultadoCaso> casos = new ArrayList<>();
  /** Frases para el alumno, ya traducidas y sin repetir entre casos (§6, «Pistas»). */
  @JsonProperty( "hints" )
  public List<String> pistas = new ArrayList<>();
  /** Notas del corrector que no son de un caso (conexion sin usar quitada, salida ambigua...). */
  @JsonProperty( "warnings" )
  public List<Aviso> avisos = new ArrayList<>();
  @JsonProperty( "timing" )
  public Tiempos tiempos;
  /** ISO-8601 con zona. */
  @JsonProperty( "graded_at" )
  public String corregidoEl;
  @JsonProperty( "plugin_version" )
  public String versionPlugin;

  /** Un renglon de la rubrica ya puntuado ({@code checks[]}). */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Criterio {

    /** El check de la rubrica (runs, values, rule:usa_group_by...). */
    @JsonProperty( "check" )
    public String check;
    /** Ya traducida ("Valores correctos"). */
    @JsonProperty( "label" )
    public String etiqueta;
    @JsonProperty( "points" )
    public double puntos;
    @JsonProperty( "earned" )
    public double ganado;
    /** true si gano todos los puntos del renglon. */
    @JsonProperty( "passed" )
    public boolean aprobado;
    /** "visible 25%, oculto 0%", "Found: [Totales por producto]"... */
    @JsonProperty( "detail" )
    public String detalle;
    /** Checks de salida: id del caso -> puntaje 0..1 antes de ponderar. */
    @JsonProperty( "by_case" )
    public Map<String, Double> porCaso;
    /** El check de requires que no se aprobo y dejo este en 0 (compuerta); null si no se aplico. */
    @JsonProperty( "gated_by" )
    public String compuerta;

    public Criterio() {
    }

    public Criterio( String check, double puntos, double ganado ) {
      this.check = check;
      this.puntos = puntos;
      this.ganado = ganado;
    }
  }

  /** Resultado de una regla del pack o del linter ({@code rules.<id>}, {@code lint.<id>}). */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class ReglaEvaluada {

    @JsonProperty( "label" )
    public String etiqueta;
    @JsonProperty( "passed" )
    public boolean aprobada;
    /** false: no se pudo evaluar (por ejemplo lint:* sin el linter); vale 0 y {@code detail} dice por que. */
    @JsonProperty( "evaluated" )
    public boolean evaluada = true;
    @JsonProperty( "detail" )
    public String detalle;

    public ReglaEvaluada() {
    }

    public ReglaEvaluada( String etiqueta, boolean aprobada, String detalle ) {
      this.etiqueta = etiqueta;
      this.aprobada = aprobada;
      this.detalle = detalle;
    }
  }

  /** Un step que la politica no deja correr ({@code blocked_steps[]}). */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class PasoBloqueado {

    @JsonProperty( "step" )
    public String step;
    /** Id de plugin (ScriptValueMod...). */
    @JsonProperty( "type" )
    public String tipo;
    /** Uno de {@link PoliticaPack#NIVELES}. */
    @JsonProperty( "level" )
    public String nivel;
    /** Uno de {@link ResultadoCaso#MOTIVOS}. */
    @JsonProperty( "reason" )
    public String motivo;

    public PasoBloqueado() {
    }

    public PasoBloqueado( String step, String tipo, String nivel, String motivo ) {
      this.step = step;
      this.tipo = tipo;
      this.nivel = nivel;
      this.motivo = motivo;
    }
  }

  /** Una nota del corrector con codigo estable (para la UI y la planilla) y texto para leer. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Aviso {

    /** Hay mas de un step de salida candidato con las mismas columnas en comun (§3 paso 5). */
    public static final String SALIDA_AMBIGUA = "output_ambiguous";
    /** Se saco una conexion que ningun step usa (P15). */
    public static final String CONEXION_QUITADA = "unused_connection_removed";
    /** Las columnas estan en otro orden y el pack compara por posicion. */
    public static final String ORDEN_COLUMNAS = "order_differs";
    /** Text file output con split: se leyo solo el primer archivo. */
    public static final String SALIDA_PARTIDA = "split_output";
    /** Sobran columnas (extra_columns = warn). */
    public static final String COLUMNAS_DE_MAS = "extra_columns";
    /** Numeros con coma decimal aceptados sin descuento (strict_format = false). */
    public static final String COMA_DECIMAL = "comma_decimal";
    /** Un parametro del pack que la entrega no declara. */
    public static final String PARAMETRO_NO_DECLARADO = "parameter_not_declared";

    @JsonProperty( "code" )
    public String codigo;
    @JsonProperty( "detail" )
    public String detalle;
    /** El step al que se refiere, si hay uno. */
    @JsonProperty( "step" )
    public String step;

    public Aviso() {
    }

    public Aviso( String codigo, String detalle ) {
      this.codigo = codigo;
      this.detalle = detalle;
    }
  }

  /** {@code timing}: arranque de Kettle en el worker y tiempo de la entrega en el proceso. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Tiempos {

    @JsonProperty( "kettle_init_s" )
    public Double inicioKettleSeg;
    @JsonProperty( "process_s" )
    public Double procesoSeg;
  }

  public static boolean estadoValido( String estado ) {
    return ESTADOS.contains( estado );
  }

  /** true salvo en el parcial ({@link #EN_CURSO}). */
  public boolean terminada() {
    return !EN_CURSO.equals( estado );
  }

  /** El caso con ese id, o null. */
  public ResultadoCaso caso( String id ) {
    if ( casos != null && id != null ) {
      for ( ResultadoCaso c : casos ) {
        if ( c != null && id.equals( c.id ) ) {
          return c;
        }
      }
    }
    return null;
  }

  /** El criterio de ese check, o null. */
  public Criterio criterio( String check ) {
    if ( criterios != null && check != null ) {
      for ( Criterio c : criterios ) {
        if ( c != null && check.equals( c.check ) ) {
          return c;
        }
      }
    }
    return null;
  }

  /** Pone listas y mapas vacios donde el JSON traia null. Devuelve this. */
  public ResultadoCorreccion completar() {
    if ( formato == null ) {
      formato = FORMATO;
    }
    if ( stepsPermitidosExtra == null ) {
      stepsPermitidosExtra = new ArrayList<>();
    }
    if ( criterios == null ) {
      criterios = new ArrayList<>();
    }
    if ( stepsBloqueados == null ) {
      stepsBloqueados = new ArrayList<>();
    }
    if ( reglas == null ) {
      reglas = new LinkedHashMap<>();
    }
    if ( lint == null ) {
      lint = new LinkedHashMap<>();
    }
    if ( casos == null ) {
      casos = new ArrayList<>();
    }
    if ( pistas == null ) {
      pistas = new ArrayList<>();
    }
    if ( avisos == null ) {
      avisos = new ArrayList<>();
    }
    for ( ResultadoCaso c : casos ) {
      if ( c == null ) {
        continue;
      }
      if ( c.redirecciones == null ) {
        c.redirecciones = new ArrayList<>();
      }
      if ( c.avisos == null ) {
        c.avisos = new ArrayList<>();
      }
      if ( c.corrida == null ) {
        c.corrida = new ResultadoCaso.Corrida();
      }
    }
    return this;
  }

  /**
   * La devolucion del alumno (§6, «Dos lectores»): copia profunda en la que los casos ocultos no llevan ejemplos,
   * {@code run.detail} ni {@code run.log} (un error de conversion imprime filas de entrada); quedan los conteos, los
   * puntajes y las pistas. this no cambia.
   */
  public ResultadoCorreccion paraAlumno() {
    ResultadoCorreccion r = CorrectorJson.copia( this, ResultadoCorreccion.class );
    if ( r.casos != null ) {
      for ( ResultadoCaso c : r.casos ) {
        if ( c == null || !c.oculto ) {
          continue;
        }
        if ( c.corrida != null ) {
          c.corrida.detalle = null;
          c.corrida.log = null;
        }
        if ( c.comparacion != null ) {
          c.comparacion.ejemplos = null;
        }
      }
    }
    return r;
  }

  /** Copia sin el log de ningun caso: lo que devuelve grade_exercise por MCP (§8). this no cambia. */
  public ResultadoCorreccion sinLog() {
    ResultadoCorreccion r = CorrectorJson.copia( this, ResultadoCorreccion.class );
    if ( r.casos != null ) {
      for ( ResultadoCaso c : r.casos ) {
        if ( c != null && c.corrida != null ) {
          c.corrida.log = null;
        }
      }
    }
    return r;
  }
}
