package io.github.ivi2511.pentahomcp.pruebas.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.PoliticaPack;
import io.github.ivi2511.pentahomcp.corrector.modelo.SeguridadBases;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;

/**
 * Una prueba de transformacion con datasets dorados (0.7.0, P1): {@code tests/<nombre>.test.json} junto al proyecto,
 * formato {@value #FORMATO} (JSON Schema en {@code docs/schemas/pentaho-live-test-1.schema.json}).
 * <p>
 * Comparte el formato del <b>caso</b> con los packs del corrector: {@link #caso} es un {@link Caso} del pack (su
 * {@code expected} es la carpeta de los CSV dorados, como en la captura {@code tables}; {@code db_scripts} y
 * {@code executions} valen igual), las bases de prueba son {@link BaseDePrueba} y {@link SeguridadBases}, las tablas
 * capturadas {@link TablaSalida}, las columnas {@link Pack.Columna}, el CSV dorado {@link Pack.FormatoCsv} y la
 * comparacion de cada salida un {@link OpcionesComparar}: las mismas claves JSON y el mismo motor
 * (CorrectorComparar, CorrectorTabla, CorrectorBases). Lo propio de una prueba: {@link #datasets} (steps cuya salida
 * se reemplaza por filas fijas), {@link #salidas} (steps cuya salida se compara con un CSV dorado) y
 * {@link #ajustes} (saltar o quitar un step, fijar variables y parametros).
 * <p>
 * Rutas relativas a la carpeta del archivo de la prueba. Claves JSON en ingles y snake_case, campos en castellano,
 * como el resto de los formatos del plugin.
 *
 * @dueño P1 (0.7.0)
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
@JsonPropertyOrder( { "$schema", "format", "name", "description", "transformation", "environment", "datasets", "case",
  "test_databases", "database_safety", "outputs", "tables", "golden_csv", "tweaks", "run_whole_transformation",
  "created_with" } )
public final class DefPrueba {

  public static final String FORMATO = "pentaho-live-test/1";
  public static final String ESQUEMA = "https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/docs/schemas/"
    + "pentaho-live-test-1.schema.json";
  /** Extension de los archivos de prueba. */
  public static final String EXTENSION = ".test.json";

  @JsonProperty( "$schema" )
  public String esquema;
  @JsonProperty( "format" )
  public String formato = FORMATO;
  /** Nombre corto y unico en su carpeta (el del archivo sin {@value #EXTENSION}). */
  @JsonProperty( "name" )
  public String nombre;
  @JsonProperty( "description" )
  public String descripcion;
  /** La .ktr que se prueba, relativa a la carpeta de la prueba ("../06_kpi.ktr"). */
  @JsonProperty( "transformation" )
  public String transformacion;
  @JsonProperty( "environment" )
  @JsonMerge
  public Entorno entorno = new Entorno();
  /** Steps cuya salida se reemplaza por un dataset fijo (el step no corre: sus filas salen del CSV). */
  @JsonProperty( "datasets" )
  public List<Dataset> datasets = new ArrayList<>();
  /**
   * El caso, con el formato del pack: {@code expected} = carpeta de los CSV dorados ({@code <step>.csv},
   * {@code <tabla>.csv}); {@code db_scripts} = scripts que corren en las bases de prueba despues de los suyos;
   * {@code executions} = cuantas veces corre antes de capturar. {@code inputs_dir}, {@code hidden} y {@code weight} no
   * se usan.
   */
  @JsonProperty( "case" )
  public Caso caso;
  /** Bases descartables (H2, MySQL, MariaDB, PostgreSQL) donde se recrean las conexiones de la transformacion. */
  @JsonProperty( "test_databases" )
  public List<BaseDePrueba> bases = new ArrayList<>();
  @JsonProperty( "database_safety" )
  public SeguridadBases seguridad;
  /** Steps cuya salida se captura y se compara con su CSV dorado. */
  @JsonProperty( "outputs" )
  public List<Salida> salidas = new ArrayList<>();
  /** Tablas de las bases de prueba que se leen despues de correr y se comparan con {@code <expected>/<tabla>.csv}. */
  @JsonProperty( "tables" )
  public List<TablaSalida> tablas = new ArrayList<>();
  /** Como estan escritos los CSV dorados (por defecto: coma, comillas, encabezado, UTF-8). */
  @JsonProperty( "golden_csv" )
  @JsonMerge
  public Pack.FormatoCsv csvDorado = csvPorDefecto();
  @JsonProperty( "tweaks" )
  public List<Ajuste> ajustes = new ArrayList<>();
  /**
   * false (por defecto): corre solo lo necesario para las salidas capturadas (como una vista previa: los steps que no
   * llegan a ninguna no corren). true: la transformacion entera. Con {@link #tablas}, siempre entera.
   */
  @JsonProperty( "run_whole_transformation" )
  public boolean todaLaTransformacion;
  /** "Pentaho Live 0.7.0": quien la escribio (informativo). */
  @JsonProperty( "created_with" )
  public String creadaCon;

  /** El CSV dorado por defecto: coma, comillas dobles, encabezado y UTF-8 (se lee bien en git y en Excel). */
  public static Pack.FormatoCsv csvPorDefecto() {
    Pack.FormatoCsv f = new Pack.FormatoCsv();
    f.separador = ",";
    f.encierro = "\"";
    f.encabezado = true;
    f.encoding = "UTF-8";
    return f;
  }

  /** La comparacion por defecto de una salida: exacta (tolerancia 1e-6), columnas de mas fallan, 20 ejemplos. */
  public static OpcionesComparar compararPorDefecto() {
    OpcionesComparar o = new OpcionesComparar();
    o.toleranciaNumerica = 0.000001;
    o.columnasDeMas = OpcionesComparar.EXTRA_RESTAR;
    o.maxEjemplos = 20;
    return o;
  }

  // ------------------------------------------------------------------ partes

  /** {@code environment}: con que locale, zona y encoding corre la JVM de la prueba, y sus topes. */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Entorno {

    /** "es_ES", "en_US": numeros y fechas de los steps que no declaran su formato. */
    @JsonProperty( "locale" )
    public String locale = "en_US";
    @JsonProperty( "timezone" )
    public String zonaHoraria = "UTC";
    @JsonProperty( "encoding" )
    public String encoding = "UTF-8";
    /** Tope de la corrida (cada ejecucion). */
    @JsonProperty( "timeout_s" )
    public int timeoutSeg = 120;
    /** Filas capturadas como mucho por salida (y leidas de cada dataset). */
    @JsonProperty( "max_rows" )
    public long maxFilas = 1000000;

    /** La politica del corrector con este entorno (para lanzar la JVM con los mismos -D que una correccion). */
    public PoliticaPack politica() {
      PoliticaPack p = new PoliticaPack();
      p.locale = locale;
      p.zonaHoraria = zonaHoraria;
      p.encoding = encoding;
      p.timeoutSeg = Math.max( 1, timeoutSeg );
      p.maxFilas = maxFilas <= 0 ? 1000000 : maxFilas;
      return p;
    }
  }

  /** {@code datasets[]}: un step cuya salida es fija. */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Dataset {

    @JsonProperty( "step" )
    public String step;
    /** CSV del dataset, relativo a la prueba ("datasets/kpi/leer_ventas.csv"): coma, comillas, encabezado, UTF-8. */
    @JsonProperty( "file" )
    public String archivo;
    /** Las columnas con el tipo y el formato que tenian en la salida del step (las filas se inyectan con ellos). */
    @JsonProperty( "columns" )
    public List<ColumnaDataset> columnas = new ArrayList<>();

    public Dataset() {
    }

    public Dataset( String step, String archivo ) {
      this.step = step;
      this.archivo = archivo;
    }
  }

  /**
   * Una columna de un dataset: el tipo de Kettle y los metadatos del campo en la salida original (mascara, simbolos,
   * largo y precision), para que los steps de despues vean el mismo campo. Los valores del CSV estan escritos sin
   * formato (numeros planos con punto, fechas {@code yyyy-MM-dd HH:mm:ss.SSS}, booleanos Y/N, binarios en base64).
   */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class ColumnaDataset {

    @JsonProperty( "name" )
    public String nombre;
    /** String, Number, Integer, BigNumber, Date, Timestamp, Boolean, Binary, Internet Address. */
    @JsonProperty( "type" )
    public String tipo;
    @JsonProperty( "length" )
    public Integer largo;
    @JsonProperty( "precision" )
    public Integer precision;
    /** Mascara de conversion del campo ("#,##0.00", "dd/MM/yyyy"): se conserva, no se usa para leer el CSV. */
    @JsonProperty( "format" )
    public String formato;
    @JsonProperty( "decimal" )
    public String decimal;
    @JsonProperty( "group" )
    public String grupo;
    @JsonProperty( "currency" )
    public String moneda;

    public ColumnaDataset() {
    }

    public ColumnaDataset( String nombre, String tipo ) {
      this.nombre = nombre;
      this.tipo = tipo;
    }
  }

  /** {@code outputs[]}: un step cuya salida se compara con un CSV dorado. */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Salida {

    @JsonProperty( "step" )
    public String step;
    /** CSV dorado relativo a la carpeta {@code expected} del caso; null = {@code <step limpio>.csv}. */
    @JsonProperty( "file" )
    public String archivo;
    /** Columnas de la salida dorada con su tipo (asi se comparan: numeros como numeros, fechas como fechas). */
    @JsonProperty( "columns" )
    public List<Pack.Columna> columnas = new ArrayList<>();
    /** Claves, orden, tolerancias... (las del corrector); lo que falta toma {@link #compararPorDefecto()}. */
    @JsonProperty( "compare" )
    @JsonMerge
    public OpcionesComparar comparar = compararPorDefecto();
    /** Columnas que no se comparan (fechas de carga, UUID, la hora del sistema). */
    @JsonProperty( "ignore_columns" )
    public List<String> ignorar = new ArrayList<>();

    public Salida() {
    }

    public Salida( String step ) {
      this.step = step;
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
  }

  /** {@code tweaks[]}: lo que se cambia en la copia que corre (nunca en el archivo). */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Ajuste {

    /** El step no hace nada y sus filas siguen tal cual (un Dummy con su nombre): un Table output que no escribe. */
    public static final String SALTAR = "skip_step";
    /** El step y sus saltos se quitan: no corre ni le llega nada a lo que venia despues. */
    public static final String QUITAR = "remove_step";
    public static final String VARIABLE = "set_variable";
    public static final String PARAMETRO = "set_parameter";
    public static final List<String> TIPOS = Collections.unmodifiableList( Arrays.asList( SALTAR, QUITAR, VARIABLE,
      PARAMETRO ) );

    @JsonProperty( "type" )
    public String tipo;
    @JsonProperty( "step" )
    public String step;
    @JsonProperty( "name" )
    public String nombre;
    @JsonProperty( "value" )
    public String valor;
    /** Por que (informativo). */
    @JsonProperty( "note" )
    public String nota;

    public Ajuste() {
    }

    public static Ajuste saltar( String step, String nota ) {
      Ajuste a = new Ajuste();
      a.tipo = SALTAR;
      a.step = step;
      a.nota = nota;
      return a;
    }

    public static Ajuste variable( String nombre, String valor ) {
      Ajuste a = new Ajuste();
      a.tipo = VARIABLE;
      a.nombre = nombre;
      a.valor = valor;
      return a;
    }

    @JsonIgnore
    public boolean deStep() {
      return SALTAR.equals( tipo ) || QUITAR.equals( tipo );
    }
  }

  // ------------------------------------------------------------------ utilidades

  /** Pone los valores por defecto donde el JSON traia null. Devuelve this. */
  public DefPrueba completar() {
    if ( formato == null ) {
      formato = FORMATO;
    }
    if ( entorno == null ) {
      entorno = new Entorno();
    }
    if ( datasets == null ) {
      datasets = new ArrayList<>();
    }
    for ( Dataset d : datasets ) {
      if ( d != null && d.columnas == null ) {
        d.columnas = new ArrayList<>();
      }
    }
    if ( caso == null ) {
      caso = new Caso();
    }
    if ( caso.id == null || caso.id.trim().isEmpty() ) {
      caso.id = nombre == null ? "test" : nombre;
    }
    if ( caso.esperado == null || caso.esperado.trim().isEmpty() ) {
      caso.esperado = "golden/" + ( nombre == null ? "test" : nombre );
    }
    if ( caso.scriptsBase == null ) {
      caso.scriptsBase = new java.util.LinkedHashMap<>();
    }
    if ( bases == null ) {
      bases = new ArrayList<>();
    }
    for ( BaseDePrueba b : bases ) {
      if ( b != null ) {
        b.completar();
      }
    }
    if ( seguridad != null ) {
      seguridad.completar();
    }
    if ( salidas == null ) {
      salidas = new ArrayList<>();
    }
    for ( Salida s : salidas ) {
      if ( s == null ) {
        continue;
      }
      if ( s.columnas == null ) {
        s.columnas = new ArrayList<>();
      }
      if ( s.ignorar == null ) {
        s.ignorar = new ArrayList<>();
      }
      if ( s.comparar == null ) {
        s.comparar = compararPorDefecto();
      }
      if ( s.comparar.columnasClave == null ) {
        s.comparar.columnasClave = new ArrayList<>();
      }
      if ( s.comparar.formatosFecha == null ) {
        s.comparar.formatosFecha = new ArrayList<>();
      }
      if ( s.comparar.columnasDeMas == null ) {
        s.comparar.columnasDeMas = OpcionesComparar.EXTRA_RESTAR;
      }
    }
    if ( tablas == null ) {
      tablas = new ArrayList<>();
    }
    for ( TablaSalida t : tablas ) {
      if ( t != null ) {
        t.completar();
      }
    }
    if ( csvDorado == null ) {
      csvDorado = csvPorDefecto();
    }
    if ( ajustes == null ) {
      ajustes = new ArrayList<>();
    }
    return this;
  }

  /**
   * Un Pack del corrector con lo que comparten: las bases de prueba, sus reglas de nombre, el caso y la politica del
   * entorno. Lo usan CorrectorBases.Sesion (recrear las bases y leer tablas) y CorrectorProcesos (los -D de la JVM).
   */
  public Pack comoPack() {
    Pack p = new Pack();
    p.id = nombre;
    p.titulo = nombre;
    p.formato = bases == null || bases.isEmpty() ? Pack.FORMATO : Pack.FORMATO_2;
    p.bases = bases == null ? new ArrayList<BaseDePrueba>() : bases;
    p.seguridad = seguridad;
    p.politica = ( entorno == null ? new Entorno() : entorno ).politica();
    if ( caso != null ) {
      p.casos.add( caso );
    }
    p.salida.captura = tablas == null || tablas.isEmpty() ? Pack.Salida.CAPTURA_STEP : Pack.Salida.CAPTURA_TABLAS;
    if ( tablas != null ) {
      p.salida.tablas = tablas;
    }
    p.salida.csvEsperado = csvDorado == null ? csvPorDefecto() : csvDorado;
    return p.completar();
  }

  /** La salida de ese step (sin mayusculas), o null. */
  public Salida salida( String step ) {
    if ( step == null || salidas == null ) {
      return null;
    }
    for ( Salida s : salidas ) {
      if ( s != null && s.step != null && s.step.trim().equalsIgnoreCase( step.trim() ) ) {
        return s;
      }
    }
    return null;
  }

  /** El dataset de ese step (sin mayusculas), o null. */
  public Dataset dataset( String step ) {
    if ( step == null || datasets == null ) {
      return null;
    }
    for ( Dataset d : datasets ) {
      if ( d != null && d.step != null && d.step.trim().equalsIgnoreCase( step.trim() ) ) {
        return d;
      }
    }
    return null;
  }

  /** Un nombre de prueba valido: letras, numeros, guion y guion bajo (1 a 80). */
  public static boolean nombreValido( String n ) {
    return n != null && n.matches( "[A-Za-z0-9][A-Za-z0-9_-]{0,79}" );
  }

  /** "Leer ventas (CSV)" → "leer_ventas_csv": para nombres de archivo de datasets y dorados. */
  public static String archivoDe( String step ) {
    String s = step == null ? "" : java.text.Normalizer.normalize( step, java.text.Normalizer.Form.NFD )
      .replaceAll( "\\p{M}", "" ).toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9]+", "_" ).replaceAll(
        "^_+|_+$", "" );
    return s.isEmpty() ? "step" : s.length() > 60 ? s.substring( 0, 60 ) : s;
  }
}
