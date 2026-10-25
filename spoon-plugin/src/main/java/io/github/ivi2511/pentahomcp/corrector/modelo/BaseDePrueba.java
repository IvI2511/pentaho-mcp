package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Una base de prueba del pack ({@code test_databases[]}, formato {@value Pack#FORMATO_2}): un servidor de base de
 * datos de la PC del docente (MySQL, MariaDB, PostgreSQL o H2) donde el corrector crea una base descartable antes de
 * cada caso de cada entrega, la llena con los scripts del pack y apunta ahi las conexiones de la entrega.
 * <p>
 * La contraseña <b>nunca</b> va en el pack: al corregir sale de la variable de Kettle {@link #variableClave} (el
 * kettle.properties del docente) o de lo que el docente escribe en Spoon, y llega a la JVM hija por el entorno del
 * proceso, nunca por un archivo. {@link #base} tiene que terminar en un sufijo de prueba ({@code _correccion},
 * {@code _test}...) y no puede ser {@link #baseOriginal} (la que usa la solucion): el corrector borra y crea solo
 * esa base.
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class BaseDePrueba {

  public static final String MYSQL = "MYSQL";
  public static final String MARIADB = "MARIADB";
  public static final String POSTGRESQL = "POSTGRESQL";
  public static final String H2 = "H2";
  /** Tipos de conexion de Kettle que el corrector sabe recrear. */
  public static final List<String> TIPOS = Collections.unmodifiableList( Arrays.asList( MYSQL, MARIADB, POSTGRESQL,
    H2 ) );

  /** Nombre de la conexion en la solucion ("GYM_DW"): las de la entrega con ese nombre pasan a esta base. */
  @JsonProperty( "connection" )
  public String conexion;
  /** Uno de {@link #TIPOS}. */
  @JsonProperty( "type" )
  public String tipo;
  @JsonProperty( "host" )
  public String servidor;
  @JsonProperty( "port" )
  public String puerto;
  /** La base (en MySQL, el esquema) que el corrector recrea: "gym_dw_correccion". */
  @JsonProperty( "database" )
  public String base;
  /** La base de la solucion ("gym_dw"): nunca se toca y {@link #base} no puede ser esta. */
  @JsonProperty( "original_database" )
  public String baseOriginal;
  @JsonProperty( "user" )
  public String usuario;
  /** Variable de Kettle (kettle.properties) o de entorno con la contraseña en la PC que corrige; null = preguntar. */
  @JsonProperty( "password_variable" )
  public String variableClave;
  /** Scripts SQL del pack (rutas relativas) que corren, en orden, en la base recien creada antes de cada caso. */
  @JsonProperty( "scripts" )
  public List<String> scripts = new ArrayList<>();
  /** Opciones JDBC (las extra de la conexion de Kettle): "serverTimezone" → "Europe/Madrid". */
  @JsonProperty( "options" )
  public Map<String, String> opciones = new LinkedHashMap<>();
  /** PostgreSQL: la base a la que se conecta para borrar y crear la de prueba (por defecto "postgres"). */
  @JsonProperty( "admin_database" )
  public String baseAdmin;

  public BaseDePrueba() {
  }

  public BaseDePrueba( String conexion, String tipo, String servidor, String puerto, String base, String usuario ) {
    this.conexion = conexion;
    this.tipo = tipo;
    this.servidor = servidor;
    this.puerto = puerto;
    this.base = base;
    this.usuario = usuario;
  }

  /** El tipo en mayusculas y sin espacios ("mysql" → MYSQL); "" si no hay. */
  public String tipoNormal() {
    return tipo == null ? "" : tipo.trim().toUpperCase( Locale.ROOT );
  }

  /** MySQL o MariaDB: la base es un esquema del servidor. */
  public boolean esMysql() {
    return MYSQL.equals( tipoNormal() ) || MARIADB.equals( tipoNormal() );
  }

  /** El nombre de la base sin la carpeta (H2 sin servidor: la base es una ruta de archivo). */
  public static String nombreSolo( String base ) {
    String b = base == null ? "" : base.trim();
    while ( b.endsWith( "/" ) || b.endsWith( "\\" ) ) {
      b = b.substring( 0, b.length() - 1 );
    }
    int i = Math.max( b.lastIndexOf( '/' ), b.lastIndexOf( '\\' ) );
    return i < 0 ? b : b.substring( i + 1 );
  }

  /** La base con el sufijo del worker: "gym_dw_correccion" + "_w2" (H2 de archivo: al final de la ruta). */
  public static String conSufijo( String base, String sufijo ) {
    return sufijo == null || sufijo.isEmpty() ? base : base + sufijo;
  }

  /** Pone listas y mapas vacios donde el JSON traia null. Devuelve this. */
  public BaseDePrueba completar() {
    if ( scripts == null ) {
      scripts = new ArrayList<>();
    }
    if ( opciones == null ) {
      opciones = new LinkedHashMap<>();
    }
    return this;
  }
}
