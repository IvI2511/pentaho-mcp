package io.github.ivi2511.pentahomcp.pruebas.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;

/**
 * Lo que dejo una prueba ({@value #FORMATO}): el estado, el caso con el formato del corrector ({@link ResultadoCaso}:
 * {@code run} con el estado de la corrida, su error y la cola del log; {@code tables[]} con la comparacion de cada
 * salida y cada tabla, con sus ejemplos de filas que faltan, sobran o cambian) y un resumen por salida. Con
 * {@code test_create}, ademas, lo que se escribio ({@link #creada}).
 *
 * @dueño P1 (0.7.0)
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
@JsonPropertyOrder( { "format", "test", "status", "message", "test_file", "transformation", "source", "seconds",
  "finished_at", "outputs", "case", "notes", "created", "plugin_version" } )
public final class ResultadoPrueba {

  public static final String FORMATO = "pentaho-live-test-result/1";

  /** Todo igual: la corrida termino sin errores y cada salida coincide con su dorado. */
  public static final String PASO = "passed";
  /** Corrio pero algo no coincide, fallo un step o falta un step capturado. */
  public static final String FALLO = "failed";
  /** No se pudo correr la prueba (JSON invalido, falta la .ktr o un dataset, base de prueba insegura...). */
  public static final String ERROR = "error";
  /** Se cancelo antes de terminar. */
  public static final String CANCELADA = "cancelled";
  public static final List<String> ESTADOS = Collections.unmodifiableList( Arrays.asList( PASO, FALLO, ERROR,
    CANCELADA ) );

  /** La .ktr del disco. */
  public static final String DEL_ARCHIVO = "file";
  /** La transformacion abierta en Spoon, con los cambios sin guardar. */
  public static final String DEL_DOCUMENTO = "open_document";

  @JsonProperty( "format" )
  public String formato = FORMATO;
  @JsonProperty( "test" )
  public String prueba;
  @JsonProperty( "status" )
  public String estado;
  /** Por que no paso (en ingles), para la IA y la ventana. */
  @JsonProperty( "message" )
  public String mensaje;
  @JsonProperty( "test_file" )
  public String archivoPrueba;
  @JsonProperty( "transformation" )
  public String transformacion;
  /** {@link #DEL_ARCHIVO} o {@link #DEL_DOCUMENTO}. */
  @JsonProperty( "source" )
  public String origen;
  @JsonProperty( "seconds" )
  public Double segundos;
  @JsonProperty( "finished_at" )
  public String terminadaEl;
  @JsonProperty( "outputs" )
  public List<Resumen> salidas = new ArrayList<>();
  /** El caso con el formato del resultado de una correccion: run, warnings y tables[] (una por salida y tabla). */
  @JsonProperty( "case" )
  public ResultadoCaso caso;
  /** Datasets inyectados, ajustes aplicados, conexiones redirigidas, steps que no corrieron. */
  @JsonProperty( "notes" )
  public List<String> notas = new ArrayList<>();
  @JsonProperty( "created" )
  public Creada creada;
  @JsonProperty( "plugin_version" )
  public String versionPlugin;

  /** Una salida (step o tabla) comparada con su dorado. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  @JsonPropertyOrder( { "name", "kind", "passed", "problem", "rows_expected", "rows_actual", "matched", "different",
    "missing", "extra", "duplicate_keys", "missing_columns", "extra_columns", "golden" } )
  public static final class Resumen {

    public static final String STEP = "step";
    public static final String TABLA = "table";
    /** El step ya no esta en la transformacion. */
    public static final String SIN_STEP = "missing_step";
    /** Falta el CSV dorado. */
    public static final String SIN_DORADO = "missing_golden";
    /** La corrida fallo antes: no hay nada que comparar. */
    public static final String SIN_CORRIDA = "not_run";

    @JsonProperty( "name" )
    public String nombre;
    /** {@link #STEP} o {@link #TABLA}. */
    @JsonProperty( "kind" )
    public String tipo;
    @JsonProperty( "passed" )
    public boolean paso;
    /** {@link #SIN_STEP}, {@link #SIN_DORADO}, {@link #SIN_CORRIDA} o null (se comparo). */
    @JsonProperty( "problem" )
    public String problema;
    @JsonProperty( "rows_expected" )
    public Integer filasEsperadas;
    @JsonProperty( "rows_actual" )
    public Integer filasReales;
    @JsonProperty( "matched" )
    public Integer iguales;
    @JsonProperty( "different" )
    public Integer distintas;
    @JsonProperty( "missing" )
    public Integer faltan;
    @JsonProperty( "extra" )
    public Integer sobran;
    @JsonProperty( "duplicate_keys" )
    public Integer clavesRepetidas;
    @JsonProperty( "missing_columns" )
    public List<String> columnasFaltan;
    @JsonProperty( "extra_columns" )
    public List<String> columnasSobran;
    /** El CSV dorado, relativo a la prueba. */
    @JsonProperty( "golden" )
    public String dorado;

    public Resumen() {
    }

    public Resumen( String nombre, String tipo ) {
      this.nombre = nombre;
      this.tipo = tipo;
    }
  }

  /** {@code created}: lo que escribio test_create. */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Creada {

    @JsonProperty( "datasets" )
    public List<Archivo> datasets = new ArrayList<>();
    @JsonProperty( "goldens" )
    public List<Archivo> dorados = new ArrayList<>();
    @JsonProperty( "tweaks" )
    public List<DefPrueba.Ajuste> ajustes = new ArrayList<>();
    /** Lo que conviene revisar (salidas que no se reproducen con los datasets, variables, cambios sin guardar). */
    @JsonProperty( "warnings" )
    public List<String> avisos = new ArrayList<>();
  }

  /** Un archivo escrito por test_create. */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Archivo {

    @JsonProperty( "step" )
    public String step;
    @JsonProperty( "file" )
    public String archivo;
    @JsonProperty( "rows" )
    public int filas;
    @JsonProperty( "columns" )
    public List<String> columnas = new ArrayList<>();

    public Archivo() {
    }

    public Archivo( String step, String archivo, int filas, List<String> columnas ) {
      this.step = step;
      this.archivo = archivo;
      this.filas = filas;
      if ( columnas != null ) {
        this.columnas.addAll( columnas );
      }
    }
  }

  @JsonIgnore
  public boolean paso() {
    return PASO.equals( estado );
  }

  /** La salida con ese nombre, o null. */
  public Resumen salida( String nombre ) {
    for ( Resumen r : salidas ) {
      if ( r.nombre != null && r.nombre.equals( nombre ) ) {
        return r;
      }
    }
    return null;
  }

  /** Un resultado sin corrida: error o cancelada, con su mensaje. */
  public static ResultadoPrueba sin( String prueba, String archivo, String estado, String mensaje ) {
    ResultadoPrueba r = new ResultadoPrueba();
    r.prueba = prueba;
    r.archivoPrueba = archivo;
    r.estado = estado;
    r.mensaje = mensaje;
    return r;
  }
}
