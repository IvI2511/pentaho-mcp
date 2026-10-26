package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Una tabla del resultado con la captura {@code tables} ({@code output.tables[]}): despues de correr la entrega, el
 * corrector hace un SELECT de la tabla en la base de prueba de {@link #conexion} y la compara, como un archivo, con
 * {@code <expected del caso>/<nombre>.csv}: por {@link #columnasClave} (o como multiconjunto), sin las
 * {@link #ignorar} (claves tecnicas, fechas de carga) y con los tipos de {@link #columnas}.
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class TablaSalida {

  /** La conexion de la solucion (una de test_databases) donde esta la tabla. */
  @JsonProperty( "connection" )
  public String conexion;
  /** Nombre de la tabla ("dim_cliente"); tambien el del CSV esperado. */
  @JsonProperty( "name" )
  public String nombre;
  /** Columnas que identifican una fila; vacio = comparar como multiconjunto. */
  @JsonProperty( "key_columns" )
  public List<String> columnasClave = new ArrayList<>();
  /** Columnas que no se comparan (claves sustitutas, fechas de carga). */
  @JsonProperty( "ignore_columns" )
  public List<String> ignorar = new ArrayList<>();
  /** Columnas de la tabla de la solucion con sus tipos (sin las ignoradas); las escribe create_exercise_pack. */
  @JsonProperty( "columns" )
  public List<Pack.Columna> columnas = new ArrayList<>();

  public TablaSalida() {
  }

  public TablaSalida( String conexion, String nombre ) {
    this.conexion = conexion;
    this.nombre = nombre;
  }

  /** "dim_cliente.csv": el archivo de la salida esperada dentro de la carpeta {@code expected} del caso. */
  public String archivo() {
    return ( nombre == null ? "" : nombre.trim() ) + ".csv";
  }

  public boolean ignora( String columna ) {
    if ( columna == null || ignorar == null ) {
      return false;
    }
    for ( String c : ignorar ) {
      if ( c != null && c.trim().equalsIgnoreCase( columna.trim() ) ) {
        return true;
      }
    }
    return false;
  }

  public TablaSalida completar() {
    if ( columnasClave == null ) {
      columnasClave = new ArrayList<>();
    }
    if ( ignorar == null ) {
      ignorar = new ArrayList<>();
    }
    if ( columnas == null ) {
      columnas = new ArrayList<>();
    }
    return this;
  }

  /** Clave de la tabla para encontrarla sin mayusculas: "club_dw/dim_cliente". */
  public String clave() {
    return ( conexion == null ? "" : conexion.trim().toLowerCase( Locale.ROOT ) ) + "/" + ( nombre == null ? ""
      : nombre.trim().toLowerCase( Locale.ROOT ) );
  }
}
