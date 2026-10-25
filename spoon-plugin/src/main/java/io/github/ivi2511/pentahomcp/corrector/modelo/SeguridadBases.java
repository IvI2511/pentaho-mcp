package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Bloque {@code database_safety} del pack: las reglas que tiene que cumplir el nombre de cada base de prueba antes de
 * que el corrector la borre y la vuelva a crear. Sin el bloque valen los valores por defecto: sufijo obligatorio
 * ({@link #SUFIJOS}), las bases del sistema prohibidas y un worker por base salvo que cada uno lleve su sufijo.
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class SeguridadBases {

  /** Sufijos de prueba por defecto: la base tiene que terminar en uno (sin mayusculas). */
  public static final List<String> SUFIJOS = Collections.unmodifiableList( Arrays.asList( "_correccion", "_test",
    "_prueba", "_grading" ) );
  /** Bases del sistema que nunca se tocan, digan lo que digan las demas reglas. */
  public static final List<String> DEL_SISTEMA = Collections.unmodifiableList( Arrays.asList( "mysql",
    "information_schema", "performance_schema", "sys", "postgres", "template0", "template1", "master", "model",
    "msdb", "tempdb", "public" ) );

  /** null o vacio = {@link #SUFIJOS}. */
  @JsonProperty( "test_suffixes" )
  public List<String> sufijos = new ArrayList<>();
  /** true = el nombre no necesita un sufijo de prueba (decision explicita del docente). */
  @JsonProperty( "allow_any_name" )
  public boolean cualquierNombre;
  /** Nombres (o patrones con * y ?) que nunca pueden ser una base de prueba, ademas de {@link #DEL_SISTEMA}. */
  @JsonProperty( "deny" )
  public List<String> prohibidas = new ArrayList<>();
  /** true = un solo worker aunque se pidan mas (sin bases "_w1", "_w2" por worker). */
  @JsonProperty( "single_worker" )
  public boolean unWorker;

  /** Los sufijos que valen (los del pack o los de por defecto). */
  public List<String> sufijosEfectivos() {
    List<String> l = new ArrayList<>();
    if ( sufijos != null ) {
      for ( String s : sufijos ) {
        if ( s != null && !s.trim().isEmpty() ) {
          l.add( s.trim() );
        }
      }
    }
    return l.isEmpty() ? SUFIJOS : l;
  }

  public SeguridadBases completar() {
    if ( sufijos == null ) {
      sufijos = new ArrayList<>();
    }
    if ( prohibidas == null ) {
      prohibidas = new ArrayList<>();
    }
    return this;
  }
}
