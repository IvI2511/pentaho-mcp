package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lo que paso con un caso (diseño §6, {@code cases[]}): las redirecciones del sandbox, la corrida y la
 * comparacion. {@code run.state} es uno de {@link #ESTADOS}; si es {@link #BLOQUEADO}, {@code run.reason} dice por
 * que (uno de {@link #MOTIVOS}).
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class ResultadoCaso {

  // ---- estados del caso (run.state, §6)
  /** Termino sin errores: el unico que aprueba {@code runs}. */
  public static final String TERMINO = "finished";
  public static final String TERMINO_CON_ERRORES = "finished_with_errors";
  /** Vencio timeout_s: stopAll + killAll. */
  public static final String TIEMPO_AGOTADO = "timeout";
  /** Paso max_rows. */
  public static final String DEMASIADAS_FILAS = "too_many_rows";
  /** salida/ + tmp/ pasaron max_output_mb (P18). */
  public static final String SALIDA_DEMASIADO_GRANDE = "output_too_big";
  /** Fallo prepareExecution (un step no inicializo). */
  public static final String FALLO_INIT = "init_failed";
  /** Fallo startThreads. */
  public static final String FALLO_ARRANQUE = "start_failed";
  /** No hay step de donde capturar (captura file sin Text file output, step que no existe). */
  public static final String SIN_STEP_DE_SALIDA = "no_output_step";
  /** No se ejecuto: ver {@code reason}. */
  public static final String BLOQUEADO = "blocked";
  public static final List<String> ESTADOS = Collections.unmodifiableList( Arrays.asList( TERMINO,
    TERMINO_CON_ERRORES, TIEMPO_AGOTADO, DEMASIADAS_FILAS, SALIDA_DEMASIADO_GRANDE, FALLO_INIT, FALLO_ARRANQUE,
    SIN_STEP_DE_SALIDA, BLOQUEADO ) );

  // ---- motivos de bloqueo (run.reason del caso y reason de la entrega, §3 y §7)
  /** Step condicional que el pack no habilita (allow_steps). */
  public static final String STEP_NO_PERMITIDO = "step_not_allowed";
  /** Step peligroso sin modo confianza. */
  public static final String STEP_PELIGROSO = "dangerous_step";
  /** Step que no esta en ninguna lista o plugin faltante, con unknown_steps = block. */
  public static final String STEP_DESCONOCIDO = "unknown_step";
  /** Un step usa una conexion que no es la base del pack. */
  public static final String CONEXION_AJENA = "foreign_connection";
  /** Lectura no redirigida fuera del sandbox, esquema VFS que no es file:, o nombre de archivo desde un campo. */
  public static final String ENTRADA_INESPERADA = "unexpected_input";
  /** Salida a archivo que no se puede redirigir (fileNameInField). */
  public static final String SALIDA_INESPERADA = "unexpected_output";
  /** D1: dos steps con el mismo nombre (sin mayusculas) en el XML crudo. */
  public static final String NOMBRE_REPETIDO = "duplicate_step_name";
  /** D2: hop circular. */
  public static final String CICLO = "cycle";
  /** SQL contra la base del pack con ATTACH DATABASE, VACUUM INTO o load_extension. */
  public static final String SQL_PROHIBIDO = "forbidden_sql";
  /** La reescritura del sandbox cambio nombres de steps o extremos de hops (P14): no se corre. */
  public static final String REESCRITURA_FALLIDA = "rewrite_failed";
  /** Formato 2: un step nombra un esquema que no es una base de prueba (Table output a otro esquema). */
  public static final String ESQUEMA_AJENO = "foreign_schema";
  public static final List<String> MOTIVOS = Collections.unmodifiableList( Arrays.asList( STEP_NO_PERMITIDO,
    STEP_PELIGROSO, STEP_DESCONOCIDO, CONEXION_AJENA, ENTRADA_INESPERADA, SALIDA_INESPERADA, NOMBRE_REPETIDO, CICLO,
    SQL_PROHIBIDO, REESCRITURA_FALLIDA, ESQUEMA_AJENO ) );

  @JsonProperty( "id" )
  public String id;
  @JsonProperty( "hidden" )
  public boolean oculto;
  @JsonProperty( "weight" )
  public double peso = 1;
  /** "input 'Leer ventas': C:\...\ventas.csv -> datos/ventas.csv", "output 'Escribir totales': ... -> salida/...". */
  @JsonProperty( "redirections" )
  public List<String> redirecciones = new ArrayList<>();
  @JsonProperty( "warnings" )
  public List<ResultadoCorreccion.Aviso> avisos = new ArrayList<>();
  @JsonProperty( "run" )
  public Corrida corrida = new Corrida();
  /** Null si no hubo salida que comparar. Con la captura tables, la suma de las de {@link #tablas}. */
  @JsonProperty( "comparison" )
  public Comparacion comparacion;
  /** Captura tables: la comparacion de cada tabla ("dim_cliente" → ...), en el orden del pack. */
  @JsonProperty( "tables" )
  public List<ComparacionTabla> tablas;

  /** {@code tables[]}: una tabla capturada de la base de prueba y su comparacion. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class ComparacionTabla {

    @JsonProperty( "table" )
    public String tabla;
    @JsonProperty( "connection" )
    public String conexion;
    @JsonProperty( "comparison" )
    public Comparacion comparacion;

    public ComparacionTabla() {
    }

    public ComparacionTabla( String tabla, String conexion, Comparacion comparacion ) {
      this.tabla = tabla;
      this.conexion = conexion;
      this.comparacion = comparacion;
    }
  }

  /** {@code run}: como corrio el caso. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Corrida {

    /** Uno de {@link ResultadoCaso#ESTADOS}; null mientras corre. */
    @JsonProperty( "state" )
    public String estado;
    /** Con {@link ResultadoCaso#BLOQUEADO}: uno de {@link ResultadoCaso#MOTIVOS}. */
    @JsonProperty( "reason" )
    public String motivo;
    /** Texto para el docente, en ingles: el step que bloqueo, la primera linea del error... (puede traer datos). */
    @JsonProperty( "detail" )
    public String detalle;
    /** El mismo texto para traducirlo (clave del area «corrector»); null si no tiene traduccion. */
    @JsonProperty( "detail_key" )
    public String claveDetalle;
    @JsonProperty( "detail_args" )
    public List<String> argsDetalle;
    @JsonProperty( "seconds" )
    public Double segundos;
    /** Errores de Kettle (Trans.getErrors); null si no corrio. */
    @JsonProperty( "errors" )
    public Long errores;
    @JsonProperty( "output_step" )
    public String stepSalida;
    /** Id de plugin del step de salida (TextFileOutput, TableOutput...). */
    @JsonProperty( "output_step_type" )
    public String tipoStepSalida;
    /** "file salida/03_ventas.csv", "step Escribir totales", "query db/dw.sqlite". */
    @JsonProperty( "captured_from" )
    public String capturadoDe;
    @JsonProperty( "rows_captured" )
    public Long filasCapturadas;
    /** Lo que devuelve RunMotor.metricas: [{step, read, written, input, output, updated, rejected, errors, status}]. */
    @JsonProperty( "metrics" )
    public JsonNode metricas;
    /** Cola del log; nunca va en la devolucion de un caso oculto. */
    @JsonProperty( "log" )
    public String log;
  }

  /** El check {@code runs} del §5: termino y sin errores. */
  public boolean corrioBien() {
    return corrida != null && TERMINO.equals( corrida.estado ) && ( corrida.errores == null || corrida.errores == 0 );
  }

  public static boolean estadoValido( String estado ) {
    return ESTADOS.contains( estado );
  }

  public static boolean motivoValido( String motivo ) {
    return MOTIVOS.contains( motivo );
  }
}
