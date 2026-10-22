package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * La salida de un caso contra la esperada (diseño §4 y §6): columnas, filas, ejemplos y los tres puntajes que
 * usa la rubrica ({@code columns_score}, {@code row_count_ok}, {@code values_score}). Las columnas que faltan no
 * restan en values (ya restan en columns). En los casos ocultos la devolucion del alumno lleva solo los conteos:
 * ver {@link ResultadoCorreccion#paraAlumno()}.
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Comparacion {

  /** Filas emparejadas por {@code key_columns}. */
  public static final String POR_CLAVE = "key";
  /** Sin clave y sin orden: emparejamiento voraz de filas iguales. */
  public static final String MULTICONJUNTO = "multiset";
  /** Sin clave y con orden: fila i contra fila i. */
  public static final String POR_POSICION = "position";

  /** {@link #POR_CLAVE}, {@link #MULTICONJUNTO} o {@link #POR_POSICION}. */
  @JsonProperty( "mode" )
  public String modo;
  @JsonProperty( "key_columns" )
  public List<String> columnasClave = new ArrayList<>();
  @JsonProperty( "columns" )
  public Columnas columnas = new Columnas();
  @JsonProperty( "rows" )
  public Filas filas = new Filas();
  /** Null en la devolucion de un caso oculto. */
  @JsonProperty( "examples" )
  public Ejemplos ejemplos = new Ejemplos();
  /** Columna -> celdas distintas (solo conteos: va tambien en los ocultos). */
  @JsonProperty( "differences_by_column" )
  public Map<String, Integer> diferenciasPorColumna = new LinkedHashMap<>();
  /** Presentes / esperadas. */
  @JsonProperty( "columns_score" )
  public double puntajeColumnas;
  @JsonProperty( "row_count_ok" )
  public boolean cantidadFilasOk;
  /** iguales / max(esperadas, iguales + sobrantes); las claves repetidas no cuentan como iguales. */
  @JsonProperty( "values_score" )
  public double puntajeValores;

  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Columnas {

    @JsonProperty( "expected" )
    public List<String> esperadas = new ArrayList<>();
    @JsonProperty( "actual" )
    public List<String> reales = new ArrayList<>();
    @JsonProperty( "missing" )
    public List<String> faltan = new ArrayList<>();
    @JsonProperty( "extra" )
    public List<String> sobran = new ArrayList<>();
    /** Columna que falta y otra que sobra con >= 80 % de los mismos valores por clave. */
    @JsonProperty( "renamed_suggestions" )
    public List<Renombre> renombres = new ArrayList<>();
    /** Sin ignore_column_order: las columnas estan pero en otro orden. */
    @JsonProperty( "order_differs" )
    public boolean ordenDistinto;
  }

  /** «La columna importe_medio no esta, pero promedio tiene los mismos valores: ¿la renombraste?». */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Renombre {

    /** La esperada que falta. */
    @JsonProperty( "expected" )
    public String esperada;
    /** La del alumno que sobra. */
    @JsonProperty( "actual" )
    public String real;
    /** Fraccion de valores iguales (0..1); opcional. */
    @JsonProperty( "ratio" )
    public Double coincidencia;

    public Renombre() {
    }

    public Renombre( String esperada, String real, Double coincidencia ) {
      this.esperada = esperada;
      this.real = real;
      this.coincidencia = coincidencia;
    }
  }

  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Filas {

    @JsonProperty( "expected" )
    public int esperadas;
    @JsonProperty( "actual" )
    public int reales;
    /** Filas con todas las columnas comunes iguales. */
    @JsonProperty( "matched" )
    public int iguales;
    @JsonProperty( "different" )
    public int distintas;
    @JsonProperty( "missing" )
    public int faltan;
    @JsonProperty( "extra" )
    public int sobran;
    /** Claves esperadas que el alumno repite (la firma del Group by sin Sort rows, D5). */
    @JsonProperty( "duplicate_keys" )
    public int clavesRepetidas;
  }

  /** Los primeros max_examples de cada tipo. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Ejemplos {

    @JsonProperty( "different" )
    public List<Ejemplo> distintas = new ArrayList<>();
    @JsonProperty( "missing" )
    public List<Ejemplo> faltan = new ArrayList<>();
    @JsonProperty( "extra" )
    public List<Ejemplo> sobran = new ArrayList<>();
    @JsonProperty( "duplicate_keys" )
    public List<Ejemplo> clavesRepetidas = new ArrayList<>();
  }

  /**
   * Un ejemplo. Diferencia: key + column + expected + actual. Fila que falta o sobra: key (o la fila en {@code row}
   * sin clave). Clave repetida: key + count. Los valores son texto tal como se leyeron.
   */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Ejemplo {

    /** Valores de las columnas clave unidos con " | " (uno solo: tal cual). */
    @JsonProperty( "key" )
    public String clave;
    @JsonProperty( "column" )
    public String columna;
    @JsonProperty( "expected" )
    public String esperado;
    @JsonProperty( "actual" )
    public String real;
    /** Clave repetida: cuantas veces aparece. */
    @JsonProperty( "count" )
    public Integer veces;
    /** Fila completa (columna -> valor) cuando no hay clave. */
    @JsonProperty( "row" )
    public Map<String, String> fila;

    public Ejemplo() {
    }

    /** Una celda distinta. */
    public static Ejemplo diferencia( String clave, String columna, String esperado, String real ) {
      Ejemplo e = new Ejemplo();
      e.clave = clave;
      e.columna = columna;
      e.esperado = esperado;
      e.real = real;
      return e;
    }

    /** Una clave repetida. */
    public static Ejemplo repetida( String clave, int veces ) {
      Ejemplo e = new Ejemplo();
      e.clave = clave;
      e.veces = veces;
      return e;
    }
  }
}
