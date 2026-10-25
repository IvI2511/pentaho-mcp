package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.i18n.LanguageChoice;
import org.pentaho.di.resource.ResourceEntry;
import org.pentaho.di.resource.ResourceReference;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.groupby.GroupByMeta;
import org.pentaho.di.trans.steps.memgroupby.MemoryGroupByMeta;
import org.pentaho.di.trans.steps.sort.SortRowsMeta;
import org.pentaho.di.trans.steps.textfileoutput.TextFileField;
import org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.CorrectorMain.Pedido;
import io.github.ivi2511.pentahomcp.CorrectorPolitica.Entrega;
import io.github.ivi2511.pentahomcp.CorrectorPolitica.Revision;
import io.github.ivi2511.pentahomcp.CorrectorSandbox.Preparacion;
import io.github.ivi2511.pentahomcp.CorrectorTextos.Mensaje;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.PoliticaPack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Criterio;
import io.github.ivi2511.pentahomcp.corrector.modelo.SeguridadBases;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.Nodos;

/**
 * Arma un pack de ejercicio a partir de la solucion del docente (docs/corrector-diseno.md §2, §8
 * {@code create_exercise_pack} y §9 «Crear pack desde esta transformacion»). Dos lados:
 * <ul>
 * <li><b>El padre</b> ({@link #crear}, en Spoon o en las pruebas): copia la solucion a {@code solucion/}, escribe un
 * pack.json parcial y lanza una JVM hija con el entorno del pack ({@link CorrectorProcesos#lanzarHija}: nada de la
 * solucion corre ni se analiza en Spoon). Con lo que devuelve la hija completa pack.json (entradas, step de salida,
 * columnas con tipos, claves, allow_steps, reglas y rubrica sugeridas si no vinieron), escribe un enunciado esqueleto
 * si falta, valida el pack ({@link CorrectorPack}), lo corrige con la solucion en el <b>otro</b> locale (es_ES o
 * en_US: si la nota no es la maxima, aviso {@value #AVISO_LOCALE}) y arma la copia para alumnos
 * ({@link #copiaParaAlumnos}: sin la solucion ni los casos ocultos). El pack se arma en una carpeta temporal y recien
 * al final pasa a {@code pack_path} (carpeta o .zip).</li>
 * <li><b>La hija</b> ({@code java ... CorrectorCrearPack encargo.json}): carga la solucion con
 * {@link CorrectorPolitica#cargar}, detecta las entradas (archivos que leen sus steps, resueltos contra la carpeta
 * original de la solucion) y las copia a {@code datos/<caso>/}, revisa la politica (los condicionales que usa pasan
 * a allow_steps; peligrosos, desconocidos, D1, D2: no se puede armar), elige el step de salida
 * ({@link CorrectorMotor#elegirSalida}), corre cada caso en su sandbox ({@link CorrectorSandbox},
 * {@link CorrectorMotor#correr}) capturando por {@code step} (o el SQL del pack con {@code query}) y escribe
 * {@code esperado/<caso>.csv}: numeros con la mascara de la solucion si la captura es {@code file}, fechas
 * yyyy-MM-dd. Las claves salen del Group by (o Sort rows) mas cercano a la salida y se verifica que sean unicas en
 * cada caso; si no, se compara sin clave y se avisa.</li>
 * </ul>
 * <b>Bases de prueba</b> (formato 2): si la solucion lee o escribe un servidor (MySQL, PostgreSQL, H2), el pack lleva
 * {@code test_databases} con una base descartable por conexion ({@link CorrectorBases}): sus scripts pasan a
 * {@code bd/} (los de un caso, a {@code bd/<caso>/}), la contraseña nunca va al pack (llega a la hija por el entorno)
 * y, con la captura {@code tables}, la salida esperada de cada tabla queda en {@code esperado/<caso>/<tabla>.csv}.
 * <p>
 * Los errores que impiden armar el pack lanzan {@link Fallo} con un codigo estable y un mensaje traducible (en
 * ingles en {@code getMessage()}, en el idioma de la UI con {@link Fallo#texto}).
 *
 * @dueño C6
 */
public final class CorrectorCrearPack {

  public static final String FORMATO_ENCARGO = "pentaho-live-pack-build/1";
  static final String SOLUCION = "solucion";
  static final String DATOS = "datos";
  static final String ESPERADO = "esperado";
  static final String PLANTILLA = "plantilla";
  /** Los scripts de las bases de prueba (bd/ y bd/&lt;caso&gt;/). */
  static final String BD = "bd";
  static final String ENUNCIADO = "enunciado.md";
  static final String VISIBLE = "visible";

  // ---- avisos (code de cada warnings[])
  /** Varios steps de salida posibles: se eligio uno (output_step lo fija). */
  static final String AVISO_SALIDA_AMBIGUA = Aviso.SALIDA_AMBIGUA;
  /** Las claves (pedidas o detectadas) se repiten en la salida de algun caso. */
  static final String AVISO_CLAVE_NO_UNICA = "key_not_unique";
  /** No se encontro una clave: se compara como multiconjunto. */
  static final String AVISO_SIN_CLAVE = "no_key_columns";
  /** Con el otro locale la solucion no saca la nota maxima. */
  static final String AVISO_LOCALE = "locale_dependent";
  /** No se pudo verificar el otro locale (la hija fallo). */
  static final String AVISO_SIN_VERIFICAR = "locale_not_checked";
  /** La solucion usa Modified JavaScript: queda en allow_steps y es ejecutar codigo (§7). */
  static final String AVISO_CODIGO = "code_step_allowed";
  /** Un step lee un archivo que no existe. */
  static final String AVISO_ENTRADA_FALTA = "input_not_found";
  /** Dos entradas con el mismo nombre en carpetas distintas: el sandbox las redirige al mismo archivo. */
  static final String AVISO_ENTRADA_REPETIDA = "input_name_clash";
  /** Un caso sin filas en la salida. */
  static final String AVISO_SALIDA_VACIA = "empty_output";
  /** Una advertencia de la validacion del pack (CorrectorPack). */
  static final String AVISO_PACK = "pack_warning";

  // ---- errores (Fallo.codigo)
  static final String E_SOLUCION = "solution_not_found";
  static final String E_DESTINO = "pack_exists";
  static final String E_OPCIONES = "bad_options";
  static final String E_CARGA = "solution_load_failed";
  static final String E_BLOQUEADA = "solution_blocked";
  static final String E_CORRIDA = "solution_failed";
  static final String E_SIN_SALIDA = "no_output_step";
  static final String E_SIN_ENTRADA = "missing_input";
  static final String E_HIJA = "build_failed";
  static final String E_PACK = "invalid_pack";
  /** Una base de prueba no es segura (es la original, del sistema, sin sufijo de prueba...). */
  static final String E_BASE_INSEGURA = "unsafe_test_database";
  /** La solucion usa una conexion de servidor sin base de prueba. */
  static final String E_FALTA_BASE = "needs_test_database";
  /** No se pudo preparar o leer una base de prueba (servidor, contraseña, script). */
  static final String E_BASE = "test_database_failed";
  /** Todos los codigos de {@link Fallo}: cada uno tiene su texto error.&lt;codigo&gt; en en/es/pt (lo controla la prueba). */
  static final List<String> ERRORES = Collections.unmodifiableList( Arrays.asList( E_SOLUCION, E_DESTINO,
    E_OPCIONES, E_CARGA, "solution_blocked", "solution_failed", "no_output_step", "missing_input", E_HIJA, E_PACK,
    E_BASE_INSEGURA, E_FALTA_BASE, E_BASE ) );
  /** Todos los avisos de crear un pack (warnings[].code). */
  static final List<String> AVISOS = Collections.unmodifiableList( Arrays.asList( Aviso.SALIDA_AMBIGUA,
    "key_not_unique", "no_key_columns", "locale_dependent", "locale_not_checked", "code_step_allowed",
    "input_not_found", "input_name_clash", "empty_output", "pack_warning" ) );

  /** Familias que sugieren una regla must_use (la primera que use la solucion), en orden de prioridad. */
  private static final String[][] FAMILIAS = { { "usa_group_by", "Group by / Memory group by", "GroupBy",
    "MemoryGroupBy" }, { "usa_denormaliser", "Row denormaliser", "Denormaliser" }, { "usa_normaliser",
    "Row normaliser", "Normaliser" }, { "usa_merge_join", "Merge join", "MergeJoin" }, { "usa_lookup",
    "Stream lookup / Database lookup", "StreamLookup", "DBLookup" }, { "usa_join_rows", "Join rows",
    "JoinRows" }, { "usa_unique", "Unique rows", "Unique", "UniqueRowsByHashSet" }, { "usa_switch_case",
    "Switch / case", "SwitchCase" }, { "usa_filtro", "Filter rows", "FilterRows" } };
  private static final List<String> CODIGO = Arrays.asList( "ScriptValueMod", "UserDefinedJavaClass", "Janino" );
  /** Steps de base que un pack con bases de prueba deja correr siempre (sobre las bases de prueba). */
  static final List<String> PASOS_DE_BASE = Collections.unmodifiableList( Arrays.asList( "TableInput", "TableOutput",
    "InsertUpdate", "Update", "Delete", "DBLookup", "DBJoin", "DimensionLookup", "CombinationLookup",
    "SynchronizeAfterMerge", "TableExists", "ColumnExists" ) );
  private static final Pattern MASCARA_SIMPLE = Pattern.compile( "[#0,.]+" );
  private static final Pattern FECHA_ISO = Pattern.compile( "\\d{4}-\\d{2}-\\d{2}" );
  private static final Pattern ENTERO = Pattern.compile( "-?(0|[1-9]\\d{0,17})" );
  private static final Pattern NUMERO = Pattern.compile( "-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?" );
  private static final Pattern NO_ID = Pattern.compile( "[^A-Za-z0-9._-]+" );
  /** Margen del padre sobre timeout_s × casos para la hija (arranque de Kettle incluido). */
  static final long MARGEN_HIJA_MS = 180000;

  private CorrectorCrearPack() {
  }

  // ------------------------------------------------------------------ opciones y resultado

  /** No se pudo armar el pack: {@link #codigo} estable (E_*), el mensaje en ingles y, para traducirlo, su clave. */
  public static final class Fallo extends Exception {

    private static final long serialVersionUID = 1L;
    public final String codigo;
    /** El mensaje traducible; null si vino solo en ingles (una hija de una version anterior). */
    final transient Mensaje mensaje;

    Fallo( String codigo, String mensaje ) {
      super( mensaje );
      this.codigo = codigo;
      this.mensaje = null;
    }

    Fallo( String codigo, Mensaje mensaje ) {
      super( mensaje.en() );
      this.codigo = codigo;
      this.mensaje = mensaje;
    }

    /** El mensaje en el idioma de t (en ingles si no tiene clave). */
    public String texto( Rotulos t ) {
      return mensaje == null ? getMessage() : mensaje.texto( t );
    }

    /** El nombre del error en el idioma de t ("la solución está bloqueada"). */
    public String nombre( Rotulos t ) {
      return t.tiene( "error." + codigo ) ? t.s( "error." + codigo ) : codigo;
    }
  }

  /** Un caso nuevo: sus entradas salen de una carpeta del docente. */
  static final class CasoNuevo {

    String id;
    /** Carpeta del docente con los archivos de inputs para este caso; null = los que lee la solucion. */
    File dirEntradas;
    boolean oculto;
    double peso = 1;
    /** Scripts SQL de este caso por conexion (los datos de origen del caso), o null. */
    Map<String, List<File>> scripts;
    /** Veces que corre la entrega antes de capturar (2: ver si duplica filas); null = 1. */
    Integer ejecuciones;

    CasoNuevo() {
    }

    CasoNuevo( String id, File dirEntradas, boolean oculto ) {
      this.id = id;
      this.dirEntradas = dirEntradas;
      this.oculto = oculto;
    }
  }

  /** Los argumentos de create_exercise_pack (§8) y del asistente (§9). */
  static final class Opciones {

    /** La solucion (.ktr). */
    File solucion;
    /** Donde apunta {@code ${Internal.Entry.Current.Directory}} al buscar las entradas; null = la de la solucion. */
    File dirOriginal;
    /** Nombre en solucion/ (null = el del archivo): la pestaña activa se guarda primero en un temporal. */
    String nombreSolucion;
    /** pack_path: una carpeta (inexistente o vacia) o un .zip. */
    File destino;
    /** Reemplaza un pack que ya esta en destino (un .zip, o una carpeta con pack.json). */
    boolean reemplazar;
    String id;
    String titulo;
    /** en | es | pt; null = el de esta JVM. */
    String idioma;
    /** Markdown del enunciado; null = un esqueleto con las columnas de salida. */
    String enunciado;
    /** Nombres de archivo que se redirigen; null = los que lee la solucion. */
    List<String> entradas;
    /** null = un caso {@value #VISIBLE} con los archivos que lee la solucion. */
    List<CasoNuevo> casos;
    String stepSalida;
    /** file | step | query; null = file. */
    String captura;
    /** null = las del Group by o Sort rows mas cercano a la salida. */
    List<String> columnasClave;
    OpcionesComparar comparar;
    /** null = las sugeridas. */
    List<Regla> reglas;
    /** null = runs 1, columns 2, row_count 1, values 4 y 2 puntos de reglas. */
    List<ItemRubrica> rubrica;
    /** null = locale y zona de esta JVM, UTF-8, 60 s. */
    PoliticaPack politica;
    Double escala;
    /** Captura query: la base SQLite vacia con las tablas destino y el SELECT. */
    File plantilla;
    String sql;
    /**
     * Bases de prueba (formato 2), una por conexion de servidor que use la solucion: servidor, base de prueba,
     * usuario, original_database, password_variable. Sus {@code scripts} se ignoran: van en {@link #scriptsBase}.
     */
    List<BaseDePrueba> bases;
    /** Conexion → scripts del docente (archivos) que corren en su base antes de cada caso; se copian a bd/. */
    Map<String, List<File>> scriptsBase;
    /** Contraseñas por conexion (sin mayusculas), solo en memoria: van a las hijas por el entorno. */
    Map<String, String> claves;
    /** Reglas de nombre de las bases de prueba; null = las de por defecto. */
    SeguridadBases seguridad;
    /** Captura tables: las tablas a comparar (con clave e ignoradas); null = las que escribe la solucion. */
    List<TablaSalida> tablas;
    /** Corregir la solucion con el otro locale (es_ES / en_US) y avisar si cambia. */
    boolean verificarLocales = true;
    /** Copia para alumnos (carpeta o .zip), o null. */
    File copiaAlumnos;
    /** Lanzamiento de las hijas (pdi, java, -Xmx, propiedades); null = por defecto. */
    CorrectorProcesos.Opciones procesos;
    /** Carpeta de trabajo (queda al terminar: pruebas); null = una temporal que se borra. */
    File dirTrabajo;
  }

  /** Lo que devuelve create_exercise_pack (§8). Claves en ingles. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Creado {

    @JsonProperty( "pack_path" )
    public String pack;
    @JsonProperty( "cases" )
    public List<CasoCreado> casos = new ArrayList<CasoCreado>();
    @JsonProperty( "detected" )
    public Detectado detectado = new Detectado();
    @JsonProperty( "warnings" )
    public List<Aviso> avisos = new ArrayList<Aviso>();
    @JsonProperty( "student_copy" )
    public String copiaAlumnos;
    /** La corrida de la solucion con el otro locale, o null si no se pidio. */
    @JsonProperty( "locale_check" )
    public Verificacion verificacion;
  }

  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class CasoCreado {

    @JsonProperty( "id" )
    public String id;
    @JsonProperty( "hidden" )
    public boolean oculto;
    @JsonProperty( "rows" )
    public int filas;
    @JsonProperty( "columns" )
    public int columnas;
    /** Relativo al pack ("esperado/visible.csv"). */
    @JsonProperty( "expected_file" )
    public String esperado;
  }

  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Detectado {

    @JsonProperty( "inputs" )
    public List<String> entradas = new ArrayList<String>();
    @JsonProperty( "output_step" )
    public String stepSalida;
    @JsonProperty( "output_step_type" )
    public String tipoSalida;
    @JsonProperty( "key_columns" )
    public List<String> claves = new ArrayList<String>();
    @JsonProperty( "columns" )
    public List<Pack.Columna> columnas = new ArrayList<Pack.Columna>();
    @JsonProperty( "suggested_rules" )
    public List<Regla> reglas = new ArrayList<Regla>();
    /** Los condicionales que usa la solucion (pasan a policy.allow_steps). */
    @JsonProperty( "allow_steps" )
    public List<String> permitidos = new ArrayList<String>();
    @JsonProperty( "steps" )
    public Integer steps;
    /** Captura tables: las tablas comparadas, con su clave y sus columnas. */
    @JsonProperty( "tables" )
    public List<TablaSalida> tablas = new ArrayList<TablaSalida>();
  }

  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Verificacion {

    @JsonProperty( "locale" )
    public String locale;
    @JsonProperty( "status" )
    public String estado;
    @JsonProperty( "grade" )
    public Double nota;
    @JsonProperty( "scale" )
    public double escala;
    /** Los checks que no aprobo. */
    @JsonProperty( "failed_checks" )
    public List<String> fallados = new ArrayList<String>();
  }

  /** El encargo de la hija ({@code encargo.json}). */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Encargo {

    @JsonProperty( "format" )
    public String formato = FORMATO_ENCARGO;
    /** El pack en construccion: pack.json parcial, solucion/ y plantilla/. */
    @JsonProperty( "pack_dir" )
    public String dirPack;
    /** Donde la hija arma los sandboxes y la copia de analisis. */
    @JsonProperty( "work_dir" )
    public String dirTrabajo;
    /** La carpeta contra la que se resuelven las rutas de la solucion. */
    @JsonProperty( "original_dir" )
    public String dirOriginal;
    /** Caso → carpeta del docente con sus entradas (null = los archivos que lee la solucion). */
    @JsonProperty( "input_sources" )
    public Map<String, String> fuentes = new LinkedHashMap<String, String>();
    /** null = detectar. */
    @JsonProperty( "key_columns" )
    public List<String> claves;
    @JsonProperty( "result" )
    public String resultado;
  }

  /** Lo que la hija encontro ({@code hallado.json}). */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Hallado {

    @JsonProperty( "error_code" )
    public String codigoError;
    @JsonProperty( "error" )
    public String error;
    /** El error para traducirlo (clave del area «corrector» y sus argumentos). */
    @JsonProperty( "error_key" )
    public String claveError;
    @JsonProperty( "error_args" )
    public List<String> argsError;
    @JsonProperty( "detected" )
    public Detectado detectado = new Detectado();
    @JsonProperty( "key_unique" )
    public boolean clavesUnicas = true;
    @JsonProperty( "cases" )
    public List<CasoCreado> casos = new ArrayList<CasoCreado>();
    @JsonProperty( "warnings" )
    public List<Aviso> avisos = new ArrayList<Aviso>();
  }

  // ------------------------------------------------------------------ padre

  /**
   * Arma el pack (ver la clase). No corre nada de la solucion en esta JVM.
   *
   * @throws Fallo si no se puede (solucion que no carga, que bloquea, sin salida, entradas que faltan, pack invalido)
   */
  static Creado crear( Opciones op ) throws Exception {
    if ( op == null || op.solucion == null || !op.solucion.isFile() ) {
      throw new Fallo( E_SOLUCION, new Mensaje( "crear.err.solution_not_found", op == null ? null : op.solucion ) );
    }
    if ( !op.solucion.getName().toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) ) {
      throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.not_ktr", op.solucion.getName() ) );
    }
    if ( op.destino == null ) {
      throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.no_pack_path" ) );
    }
    String captura = op.captura == null ? Pack.Salida.CAPTURA_ARCHIVO : op.captura;
    if ( !Pack.Salida.CAPTURAS.contains( captura ) ) {
      throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.bad_capture", captura, String.join( ", ",
        Pack.Salida.CAPTURAS ) ) );
    }
    if ( Pack.Salida.CAPTURA_QUERY.equals( captura ) && ( op.plantilla == null || !op.plantilla.isFile()
      || op.sql == null || op.sql.trim().isEmpty() ) ) {
      throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.query_needs_template" ) );
    }
    revisarBases( op, captura );
    File destino = op.destino.getAbsoluteFile();
    boolean zip = destino.getName().toLowerCase( Locale.ROOT ).endsWith( ".zip" );
    revisarDestino( destino, zip, op.reemplazar, "pack_path" );
    if ( op.copiaAlumnos != null ) {
      revisarDestino( op.copiaAlumnos.getAbsoluteFile(), op.copiaAlumnos.getName().toLowerCase( Locale.ROOT )
        .endsWith( ".zip" ), op.reemplazar, "student_copy" );
    }
    List<CasoNuevo> casos = op.casos == null || op.casos.isEmpty() ? Collections.singletonList( new CasoNuevo(
      VISIBLE, null, false ) ) : op.casos;
    Set<String> ids = new HashSet<String>();
    boolean visible = false;
    for ( CasoNuevo c : casos ) {
      if ( c.id == null || !c.id.matches( "[A-Za-z0-9](?:[A-Za-z0-9._\\-]{0,62}[A-Za-z0-9_\\-])?" ) ) {
        throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.bad_case_id", c.id ) );
      }
      if ( !ids.add( c.id.toLowerCase( Locale.ROOT ) ) ) {
        throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.duplicate_case", c.id ) );
      }
      if ( c.dirEntradas != null && !c.dirEntradas.isDirectory() ) {
        throw new Fallo( E_SIN_ENTRADA, new Mensaje( "crear.err.no_inputs_folder", c.id, c.dirEntradas ) );
      }
      for ( List<File> l : c.scripts == null ? Collections.<List<File>>emptyList() : c.scripts.values() ) {
        for ( File f : l == null ? Collections.<File>emptyList() : l ) {
          if ( f == null || !f.isFile() ) {
            throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.script_not_found", f ) );
          }
        }
      }
      visible |= !c.oculto;
    }
    if ( !visible ) {
      throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.no_visible" ) );
    }

    boolean temporal = op.dirTrabajo == null;
    File trabajo = temporal ? Files.createTempDirectory( "pentaho-live-pack" ).toFile() : op.dirTrabajo
      .getAbsoluteFile();
    try {
      return crearEn( op, casos, captura, destino, zip, trabajo );
    } finally {
      if ( temporal ) {
        try {
          CorrectorSandbox.borrar( trabajo );
        } catch ( IOException ignored ) {
          // algo trabado: queda en el temporal del sistema
        }
      }
    }
  }

  /**
   * Las bases de prueba antes de lanzar nada: cada conexion de servidor que usa la solucion tiene la suya (o la
   * entrega no podria correr), los scripts existen y ninguna base es la original, del sistema o sin sufijo de prueba.
   */
  private static void revisarBases( Opciones op, String captura ) throws Fallo {
    List<BaseDePrueba> bases = op.bases == null ? Collections.<BaseDePrueba>emptyList() : op.bases;
    if ( Pack.Salida.CAPTURA_TABLAS.equals( captura ) && bases.isEmpty() ) {
      throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.tables_need_db" ) );
    }
    CorrectorBases.Inventario inv;
    try {
      inv = CorrectorBases.inventario( op.solucion );
    } catch ( Exception e ) {
      throw new Fallo( E_CARGA, new Mensaje( "crear.err.load", RunMotor.primeraLinea( e ) ) );
    }
    Pack p = new Pack();
    p.seguridad = op.seguridad;
    p.bases = new ArrayList<BaseDePrueba>();
    for ( BaseDePrueba b : bases ) {
      BaseDePrueba x = CorrectorJson.copia( b, BaseDePrueba.class ).completar();
      CorrectorBases.Conexion c = inv.conexion( x.conexion );
      if ( c == null ) {
        throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.unknown_connection", x.conexion ) );
      }
      if ( vacio( x.baseOriginal ) ) {
        x.baseOriginal = c.base;
      }
      p.bases.add( x );
    }
    for ( CorrectorBases.Conexion c : inv.usadas() ) {
      boolean sqlite = "SQLITE".equalsIgnoreCase( c.tipo );
      if ( p.base( c.nombre ) == null && !( sqlite && Pack.Salida.CAPTURA_QUERY.equals( captura ) ) ) {
        throw new Fallo( E_FALTA_BASE, new Mensaje( "crear.err.needs_test_db", c.nombre, c.tipo ) );
      }
    }
    List<String> originales = new ArrayList<String>();
    for ( CorrectorBases.Conexion c : inv.conexiones ) {
      if ( !vacio( c.base ) ) {
        originales.add( c.base );
      }
    }
    List<Mensaje> mal = CorrectorBases.inseguras( p, null, originales );
    if ( !mal.isEmpty() ) {
      throw new Fallo( E_BASE_INSEGURA, new Mensaje( "crear.err.unsafe", CorrectorTextos.lista( mal ) ) );
    }
    for ( List<File> l : op.scriptsBase == null ? Collections.<List<File>>emptyList() : op.scriptsBase.values() ) {
      for ( File f : l == null ? Collections.<File>emptyList() : l ) {
        if ( f == null || !f.isFile() ) {
          throw new Fallo( E_OPCIONES, new Mensaje( "crear.err.script_not_found", f ) );
        }
      }
    }
  }

  /** Copia un script del docente al pack (bd/ o bd/&lt;caso&gt;/) y devuelve su ruta relativa. */
  private static String copiarScript( File f, File dirPack, String carpeta ) throws IOException {
    File dir = new File( dirPack, carpeta.replace( '/', File.separatorChar ) );
    mkdirs( dir );
    String n = CorrectorSandbox.limpio( f.getName() );
    File d = new File( dir, n );
    for ( int i = 2; d.exists(); i++ ) {
      d = new File( dir, n.replaceFirst( "(\\.[^.]*)?$", "_" + i + "$1" ) );
    }
    Files.copy( f.toPath(), d.toPath() );
    return carpeta + "/" + d.getName();
  }

  private static Creado crearEn( Opciones op, List<CasoNuevo> casos, String captura, File destino, boolean zip,
                                 File trabajo ) throws Exception {
    File dirPack = new File( trabajo, "pack" );
    CorrectorSandbox.borrar( dirPack );
    mkdirs( new File( dirPack, SOLUCION ) );
    String idioma = Rotulos.normalizar( op.idioma != null ? op.idioma : Locale.getDefault().getLanguage() );
    Rotulos t = Rotulos.de( CorrectorNota.AREA, idioma );

    // pack.json parcial: lo que la hija necesita para correr los casos
    Pack p = new Pack();
    String nombreSol = op.nombreSolucion != null && !op.nombreSolucion.trim().isEmpty() ? CorrectorSandbox.limpio(
      op.nombreSolucion.trim() ) : op.solucion.getName();
    if ( !nombreSol.toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) ) {
      nombreSol += ".ktr";
    }
    String base = nombreSol.substring( 0, nombreSol.length() - 4 );
    p.id = op.id != null && !op.id.trim().isEmpty() ? op.id.trim() : idDe( destino.getName().replaceFirst(
      "(?i)\\.zip$", "" ), base );
    p.titulo = op.titulo != null && !op.titulo.trim().isEmpty() ? op.titulo.trim() : base;
    p.idioma = idioma;
    p.enunciado = ENUNCIADO;
    p.solucion = SOLUCION + "/" + nombreSol;
    p.entradas = op.entradas == null ? new ArrayList<String>() : new ArrayList<String>( op.entradas );
    boolean tablas = Pack.Salida.CAPTURA_TABLAS.equals( captura );
    for ( CasoNuevo c : casos ) {
      Caso k = new Caso( c.id, DATOS + "/" + c.id, ESPERADO + "/" + c.id + ( tablas ? "" : ".csv" ), c.oculto );
      k.peso = c.peso;
      k.ejecuciones = c.ejecuciones == null || c.ejecuciones <= 1 ? null : c.ejecuciones;
      if ( c.scripts != null ) {
        for ( Map.Entry<String, List<File>> e : c.scripts.entrySet() ) {
          List<String> rutas = new ArrayList<String>();
          for ( File f : e.getValue() == null ? Collections.<File>emptyList() : e.getValue() ) {
            rutas.add( copiarScript( f, dirPack, BD + "/" + c.id ) );
          }
          if ( !rutas.isEmpty() ) {
            k.scriptsBase.put( e.getKey(), rutas );
          }
        }
      }
      p.casos.add( k );
    }
    if ( op.bases != null && !op.bases.isEmpty() ) {
      CorrectorBases.Inventario inv = CorrectorBases.inventario( op.solucion );
      for ( BaseDePrueba b : op.bases ) {
        BaseDePrueba x = CorrectorJson.copia( b, BaseDePrueba.class ).completar();
        x.tipo = x.tipoNormal();
        x.scripts = new ArrayList<String>();
        CorrectorBases.Conexion c = inv.conexion( x.conexion );
        if ( vacio( x.baseOriginal ) && c != null ) {
          x.baseOriginal = c.base;
        }
        List<File> sc = scriptsDe( op.scriptsBase, x.conexion );
        for ( File f : sc ) {
          x.scripts.add( copiarScript( f, dirPack, BD ) );
        }
        p.bases.add( x );
      }
      p.seguridad = op.seguridad == null ? null : CorrectorJson.copia( op.seguridad, SeguridadBases.class );
    }
    if ( tablas && op.tablas != null ) {
      for ( TablaSalida ts : op.tablas ) {
        p.salida.tablas.add( CorrectorJson.copia( ts, TablaSalida.class ).completar() );
      }
    }
    p.salida.captura = captura;
    p.salida.step = op.stepSalida == null || op.stepSalida.trim().isEmpty() ? null : op.stepSalida.trim();
    if ( Pack.Salida.CAPTURA_QUERY.equals( captura ) ) {
      mkdirs( new File( dirPack, PLANTILLA ) );
      String n = CorrectorSandbox.limpio( op.plantilla.getName() );
      Files.copy( op.plantilla.toPath(), new File( new File( dirPack, PLANTILLA ), n ).toPath() );
      p.salida.base = new Pack.BaseDatos();
      p.salida.base.plantilla = PLANTILLA + "/" + n;
      p.salida.sql = op.sql.trim();
    }
    p.comparar = op.comparar != null ? CorrectorJson.copia( op.comparar, OpcionesComparar.class )
      : new OpcionesComparar();
    p.politica = op.politica != null ? CorrectorJson.copia( op.politica, PoliticaPack.class ) : politicaLocal();
    p.escala = op.escala != null && op.escala > 0 ? op.escala : 10;
    p.completar();
    p.formato = p.formatoNecesario();
    Files.copy( op.solucion.toPath(), new File( new File( dirPack, SOLUCION ), nombreSol ).toPath(),
      StandardCopyOption.REPLACE_EXISTING );
    CorrectorJson.guardar( p, new File( dirPack, CorrectorPack.PACK_JSON ) );

    // la hija: detecta, copia entradas, corre los casos y escribe esperado/
    Encargo enc = new Encargo();
    enc.dirPack = dirPack.getAbsolutePath();
    enc.dirTrabajo = new File( trabajo, "hija-trabajo" ).getAbsolutePath();
    File dirOriginal = op.dirOriginal != null ? op.dirOriginal : op.solucion.getAbsoluteFile().getParentFile();
    enc.dirOriginal = dirOriginal.getAbsolutePath();
    for ( CasoNuevo c : casos ) {
      enc.fuentes.put( c.id, c.dirEntradas == null ? null : c.dirEntradas.getAbsolutePath() );
    }
    enc.claves = op.columnasClave == null ? null : new ArrayList<String>( op.columnasClave );
    File dirHija = new File( trabajo, "hija" );
    CorrectorSandbox.borrar( dirHija );
    mkdirs( dirHija );
    File archivoEnc = new File( dirHija, "encargo.json" );
    File archivoHallado = new File( dirHija, "hallado.json" );
    enc.resultado = archivoHallado.getAbsolutePath();
    CorrectorJson.guardar( enc, archivoEnc );
    CorrectorProcesos.Opciones po = opcionesHija( op.procesos );
    File pdi = po.pdi != null ? po.pdi.getAbsoluteFile() : CorrectorProcesos.pdiDetectado();
    if ( pdi == null || !CorrectorProcesos.esPdi( pdi ) ) {
      throw new Fallo( E_HIJA, new Mensaje( "crear.err.no_pdi", pdi ) );
    }
    File log = new File( trabajo, "crear-pack.log" );
    List<String> enUso = CorrectorBases.clavesDeUso( p, null );
    try {
      CorrectorBases.reservar( enUso );
    } catch ( CorrectorBases.Falla f ) {
      throw new Fallo( E_BASE, f.mensaje );
    }
    Hallado h;
    try {
      Map<String, String> claves = claves( op );
      Process pr = CorrectorProcesos.lanzarHija( CorrectorProcesos.comandoBase( po, p, pdi ), dirHija,
        CorrectorCrearPack.class.getName(), Collections.singletonList( archivoEnc.getAbsolutePath() ), log,
        "pack-builder", CorrectorBases.entorno( p, claves ) );
      int veces = 0;
      for ( Caso c : p.casos ) {
        veces += c.vecesQueCorre();
      }
      long limite = Math.max( 1, p.politica.timeoutSeg ) * 1000L * Math.max( 1, veces ) + MARGEN_HIJA_MS;
      try {
        if ( !pr.waitFor( limite, TimeUnit.MILLISECONDS ) ) {
          CorrectorProcesos.matar( pr );
          throw new Fallo( E_HIJA, new Mensaje( "crear.err.child_timeout", limite / 1000, colaLog( log ) ) );
        }
      } catch ( InterruptedException e ) {
        CorrectorProcesos.matar( pr );
        Thread.currentThread().interrupt();
        throw e;
      }
      if ( !archivoHallado.isFile() ) {
        throw new Fallo( E_HIJA, new Mensaje( "crear.err.child_stopped", pr.exitValue(), colaLog( log ) ) );
      }
      h = CorrectorJson.leer( archivoHallado, Hallado.class );
    } finally {
      CorrectorBases.liberar( enUso );
    }
    if ( h.codigoError != null ) {
      throw h.claveError != null ? new Fallo( h.codigoError, new Mensaje( h.claveError, h.argsError ) )
        : new Fallo( h.codigoError, h.error );
    }

    // pack.json completo
    Creado cr = new Creado();
    cr.detectado = h.detectado;
    cr.casos.addAll( h.casos );
    cr.avisos.addAll( h.avisos );
    p.entradas = new ArrayList<String>( h.detectado.entradas );
    if ( p.entradas.isEmpty() ) { // sin archivos de entrada (lee solo de una base): los casos no tienen carpeta
      for ( Caso c : p.casos ) {
        File d = new File( dirPack, c.dirEntradas.replace( '/', File.separatorChar ) );
        String[] hay = d.list();
        if ( hay != null && hay.length == 0 ) {
          Files.delete( d.toPath() );
        }
        c.dirEntradas = null;
      }
      File datos = new File( dirPack, DATOS );
      String[] hay = datos.list();
      if ( hay != null && hay.length == 0 ) {
        Files.delete( datos.toPath() );
      }
    }
    p.salida.step = h.detectado.stepSalida;
    p.salida.columnas = new ArrayList<Pack.Columna>( h.detectado.columnas );
    if ( tablas ) {
      p.salida.tablas = new ArrayList<TablaSalida>( h.detectado.tablas );
    }
    if ( op.columnasClave == null ) {
      p.comparar.columnasClave = h.clavesUnicas ? new ArrayList<String>( h.detectado.claves )
        : new ArrayList<String>();
    } else {
      p.comparar.columnasClave = new ArrayList<String>( op.columnasClave );
    }
    if ( op.comparar == null ) {
      for ( Pack.Columna c : p.salida.columnas ) {
        if ( c.esFecha() && p.comparar.formatosFecha.isEmpty() ) {
          p.comparar.formatosFecha.addAll( Arrays.asList( "yyyy-MM-dd", "dd/MM/yyyy" ) );
        }
      }
    }
    for ( String s : h.detectado.permitidos ) {
      if ( !p.politica.permite( s ) ) {
        p.politica.permitirSteps.add( s );
      }
    }
    p.reglas = op.reglas != null ? new ArrayList<Regla>( op.reglas ) : new ArrayList<Regla>( h.detectado.reglas );
    p.rubrica = op.rubrica != null ? new ArrayList<ItemRubrica>( op.rubrica ) : rubricaPorDefecto( p.reglas );
    p.completar();
    CorrectorJson.guardar( p, new File( dirPack, CorrectorPack.PACK_JSON ) );
    String md = op.enunciado != null && !op.enunciado.trim().isEmpty() ? op.enunciado : enunciado( p, t );
    Files.write( new File( dirPack, ENUNCIADO ).toPath(), md.getBytes( StandardCharsets.UTF_8 ) );

    // validar
    try ( CorrectorPack v = CorrectorPack.abrir( dirPack ) ) {
      if ( !v.valido() ) {
        throw new Fallo( E_PACK, new Mensaje( "crear.err.invalid_pack", problemas( v.errores() ) ) );
      }
      for ( CorrectorPack.Problema pr2 : v.avisos() ) {
        cr.avisos.add( mensaje( pr2 ).aviso( AVISO_PACK ) );
      }
    }

    // la solucion con el otro locale
    if ( op.verificarLocales ) {
      cr.verificacion = verificarLocale( dirPack, p, trabajo, opcionesConClaves( op ), cr.avisos );
    }

    // copia para alumnos (de la carpeta armada) y destino
    if ( op.copiaAlumnos != null ) {
      cr.copiaAlumnos = copiaParaAlumnos( dirPack, op.copiaAlumnos.getAbsoluteFile(), op.reemplazar )
        .getAbsolutePath();
    }
    cr.pack = moverPack( dirPack, destino, zip, op.reemplazar ).getAbsolutePath();
    return cr;
  }

  /** Las contraseñas de las bases de prueba (por conexion, sin mayusculas) que se pasaron. */
  private static Map<String, String> claves( Opciones op ) {
    Map<String, String> m = new LinkedHashMap<String, String>();
    if ( op.claves != null ) {
      for ( Map.Entry<String, String> e : op.claves.entrySet() ) {
        if ( e.getKey() != null ) {
          m.put( e.getKey().trim().toLowerCase( Locale.ROOT ), e.getValue() );
        }
      }
    }
    return m;
  }

  /** Las opciones de las hijas con las contraseñas de las bases de prueba. */
  private static CorrectorProcesos.Opciones opcionesConClaves( Opciones op ) {
    CorrectorProcesos.Opciones po = opcionesHija( op.procesos );
    po.claves.putAll( claves( op ) );
    return po;
  }

  /** Los scripts de una conexion (sin mayusculas). */
  private static List<File> scriptsDe( Map<String, List<File>> m, String conexion ) {
    List<File> l = new ArrayList<File>();
    if ( m != null && conexion != null ) {
      for ( Map.Entry<String, List<File>> e : m.entrySet() ) {
        if ( e.getKey() != null && e.getKey().trim().equalsIgnoreCase( conexion.trim() ) && e.getValue() != null ) {
          l.addAll( e.getValue() );
        }
      }
    }
    return l;
  }

  /** Un problema del pack como mensaje traducible ("donde: texto"). */
  static Mensaje mensaje( CorrectorPack.Problema pr ) {
    List<String> args = new ArrayList<String>();
    for ( String x : pr.args ) {
      args.add( !x.isEmpty() && x.charAt( 0 ) == '\u0001' ? CorrectorTextos.clave( "pack." + x.substring( 1 ) ) : x );
    }
    Mensaje m = new Mensaje( pr.clave, args );
    return pr.donde.isEmpty() ? m : new Mensaje( "crear.problem_at", pr.donde, m.anidado() );
  }

  private static String problemas( List<CorrectorPack.Problema> l ) {
    List<Mensaje> m = new ArrayList<Mensaje>();
    for ( CorrectorPack.Problema p : l ) {
      m.add( mensaje( p ) );
    }
    return CorrectorTextos.lista( m );
  }

  /** La politica por defecto: el locale y la zona de esta JVM (la del docente), UTF-8, 60 s. */
  static PoliticaPack politicaLocal() {
    PoliticaPack pol = new PoliticaPack();
    Locale l = Locale.getDefault();
    pol.locale = l.getCountry().isEmpty() ? l.getLanguage() : l.getLanguage() + "_" + l.getCountry();
    pol.zonaHoraria = TimeZone.getDefault().getID();
    pol.encoding = "UTF-8";
    return pol;
  }

  /** runs 1, columns 2, row_count 1, values 4 y dos puntos de reglas (must_use con compuerta en runs). */
  static List<ItemRubrica> rubricaPorDefecto( List<Regla> reglas ) {
    List<ItemRubrica> l = new ArrayList<ItemRubrica>();
    l.add( new ItemRubrica( ItemRubrica.RUNS, 1 ) );
    l.add( new ItemRubrica( ItemRubrica.COLUMNS, 2 ) );
    l.add( new ItemRubrica( ItemRubrica.ROW_COUNT, 1 ) );
    l.add( new ItemRubrica( ItemRubrica.VALUES, 4 ) );
    List<Regla> usa = new ArrayList<Regla>();
    List<Regla> otras = new ArrayList<Regla>();
    for ( Regla r : reglas ) {
      if ( r == null || r.id == null ) {
        continue;
      }
      ( Regla.DEBE_USAR.equals( r.tipo ) ? usa : otras ).add( r );
    }
    if ( usa.isEmpty() && otras.isEmpty() ) {
      l.get( 3 ).puntos = 6;
      return l;
    }
    double puntosUsa = usa.isEmpty() ? 0 : otras.isEmpty() ? 2 : 1;
    double puntosOtras = otras.isEmpty() ? 0 : 2 - puntosUsa;
    for ( Regla r : usa ) {
      l.add( new ItemRubrica( ItemRubrica.PREFIJO_REGLA + r.id, puntosUsa / usa.size(), ItemRubrica.RUNS ) );
    }
    for ( Regla r : otras ) {
      l.add( new ItemRubrica( ItemRubrica.PREFIJO_REGLA + r.id, puntosOtras / otras.size() ) );
    }
    return l;
  }

  /** Un esqueleto de enunciado: titulo, entradas, columnas de salida y claves. */
  static String enunciado( Pack p, Rotulos t ) {
    StringBuilder b = new StringBuilder();
    b.append( "# " ).append( CorrectorNota.md( p.titulo ) ).append( "\n\n" );
    List<String> ent = new ArrayList<String>();
    for ( String e : p.entradas ) {
      ent.add( codigo( e ) );
    }
    String donde = Pack.Salida.CAPTURA_QUERY.equals( p.salida.captura ) ? t.s( "crear.enunciado.donde.query" )
      : Pack.Salida.CAPTURA_STEP.equals( p.salida.captura ) ? t.f( "crear.enunciado.donde.step", CorrectorNota.md(
        p.salida.step ) ) : t.s( "crear.enunciado.donde.file" );
    b.append( t.f( "crear.enunciado.intro", ent.isEmpty() ? "-" : String.join( ", ", ent ), donde ) ).append(
      "\n\n" );
    b.append( "| " ).append( t.s( "crear.enunciado.col.columna" ) ).append( " | " ).append( t.s(
      "crear.enunciado.col.tipo" ) ).append( " | " ).append( t.s( "crear.enunciado.col.formato" ) ).append(
      " |\n|---|---|---|\n" );
    for ( Pack.Columna c : p.salida.columnas ) {
      b.append( "| " ).append( codigo( c.nombre ) ).append( " | " ).append( CorrectorNota.md( c.tipo ) ).append( " | " )
        .append( c.formato == null ? "" : codigo( c.formato ) ).append( " |\n" );
    }
    b.append( "\n" );
    if ( p.comparar.conClave() ) {
      b.append( t.f( "crear.enunciado.clave", String.join( ", ", p.comparar.columnasClave ) ) ).append( "\n\n" );
    }
    b.append( t.s( "crear.enunciado.pie" ) ).append( "\n" );
    return b.toString();
  }

  /**
   * Corrige la solucion contra el pack con el otro locale (es_ES si el pack no es es, si no en_US), en una hija: si no
   * saca la nota maxima, aviso. La copia del pack con el otro locale va a la carpeta de trabajo.
   */
  private static Verificacion verificarLocale( File dirPack, Pack p, File trabajo, CorrectorProcesos.Opciones base,
                                               List<Aviso> avisos ) {
    String otro = p.politica.localeJava().getLanguage().equals( "es" ) ? "en_US" : "es_ES";
    Verificacion v = new Verificacion();
    v.locale = otro;
    v.escala = p.escala;
    try {
      ResultadoCorreccion r = corregirSolucion( dirPack, p, otro, new File( trabajo, "verificacion" ), base );
      v.estado = r == null ? null : r.estado;
      v.nota = r == null ? null : r.nota;
      if ( r != null ) {
        for ( Criterio c : r.criterios ) {
          if ( !c.aprobado ) {
            v.fallados.add( c.check );
          }
        }
      }
      if ( r == null || r.nota == null || r.nota < p.escala - 1e-9 ) {
        String nota = r == null || r.nota == null ? "-" : numero( r.nota ) + "/" + numero( p.escala );
        String estado = r == null ? "-" : CorrectorTextos.clave( "estado." + r.estado );
        if ( r != null && ResultadoCorreccion.CAIDA.equals( r.estado ) && r.claveDetalle != null ) {
          avisos.add( new Mensaje( "crear.aviso.locale_not_checked", otro, new Mensaje( r.claveDetalle,
            r.argsDetalle ).anidado() ).aviso( AVISO_SIN_VERIFICAR ) );
        } else {
          avisos.add( new Mensaje( "crear.aviso.locale_dependent", otro, nota, estado, v.fallados.isEmpty() ? "-"
            : String.join( ", ", v.fallados ), p.politica.locale ).aviso( AVISO_LOCALE ) );
        }
      }
    } catch ( Throwable e ) {
      Mensaje m = e instanceof CorrectorBases.Falla ? ( (CorrectorBases.Falla) e ).mensaje : null;
      avisos.add( new Mensaje( "crear.aviso.locale_not_checked", otro, m != null ? m.anidado()
        : RunMotor.primeraLinea( e ) ).aviso( AVISO_SIN_VERIFICAR ) );
    }
    return v;
  }

  /**
   * Corrige la solucion del pack contra el propio pack en una hija ({@code validate_exercise_pack}: solution_grade;
   * la verificacion de locales). {@code locale} null = el del pack; si no, se corrige una copia del pack con ese
   * locale. Los resultados y logs quedan en {@code salida}.
   */
  static ResultadoCorreccion corregirSolucion( File dirPack, Pack pack, String locale, File salida,
                                               CorrectorProcesos.Opciones base ) throws Exception {
    if ( pack.solucion == null ) {
      throw new IllegalArgumentException( "the pack has no solution (student copy?)" );
    }
    Pack p = CorrectorJson.copia( pack, Pack.class ).completar();
    File dir = dirPack;
    if ( locale != null && !locale.equalsIgnoreCase( p.politica.locale ) ) {
      dir = new File( salida, "pack-" + locale );
      CorrectorSandbox.borrar( dir );
      CorrectorMain.copiarArbol( dirPack, dir );
      p.politica.locale = locale;
      CorrectorJson.guardar( p, new File( dir, CorrectorPack.PACK_JSON ) );
    }
    File ktr = new File( dir, p.solucion.replace( '/', File.separatorChar ) );
    File out = new File( salida, "resultados" );
    CorrectorSandbox.borrar( out );
    CorrectorProcesos.Opciones po = opcionesHija( base );
    po.workers = 1;
    po.guardarAbiertos = false;
    po.versionPlugin = base != null && base.versionPlugin != null ? base.versionPlugin : null;
    Pedido pd = new Pedido( "solution", ktr, new File( out, "solution.json" ) );
    List<ResultadoCorreccion> rs = CorrectorProcesos.corregir( dir, p, Collections.singletonList( pd ), out, po );
    return rs.isEmpty() ? null : rs.get( 0 );
  }

  private static CorrectorProcesos.Opciones opcionesHija( CorrectorProcesos.Opciones b ) {
    CorrectorProcesos.Opciones po = new CorrectorProcesos.Opciones();
    if ( b != null ) {
      po.pdi = b.pdi;
      po.classpath = b.classpath;
      po.java = b.java;
      po.xmxMb = b.xmxMb;
      po.argsPadre = b.argsPadre;
      po.propiedades.putAll( b.propiedades );
      po.arranqueMs = b.arranqueMs;
      po.margenMs = b.margenMs;
      po.versionPlugin = b.versionPlugin;
      po.claves.putAll( b.claves );
    }
    return po;
  }

  // ------------------------------------------------------------------ copia para alumnos

  /**
   * La copia para alumnos (§2.1): el pack sin {@code solucion/} (ni el archivo de {@code solution}), sin los datos ni
   * el esperado de los casos ocultos y con el pack.json de {@link Pack#paraAlumnos()}. {@code destino}: una carpeta
   * (inexistente o vacia) o un .zip. Se valida antes de devolverla.
   *
   * @param dirPack la carpeta del pack del docente
   */
  static File copiaParaAlumnos( File dirPack, File destino, boolean reemplazar ) throws Exception {
    Pack p = CorrectorJson.leer( new File( dirPack, CorrectorPack.PACK_JSON ), Pack.class ).completar();
    Pack a = p.paraAlumnos();
    Set<String> fuera = new HashSet<String>();
    fuera.add( SOLUCION );
    if ( p.solucion != null ) {
      fuera.add( CorrectorPack.normalizarRuta( p.solucion ).toLowerCase( Locale.ROOT ) );
    }
    for ( Caso c : p.casos ) {
      if ( c != null && c.oculto ) {
        if ( c.dirEntradas != null ) {
          fuera.add( CorrectorPack.normalizarRuta( c.dirEntradas ).toLowerCase( Locale.ROOT ) );
        }
        if ( c.esperado != null ) {
          fuera.add( CorrectorPack.normalizarRuta( c.esperado ).toLowerCase( Locale.ROOT ) );
        }
        for ( List<String> l : c.scriptsBase.values() ) {
          for ( String x : l == null ? Collections.<String>emptyList() : l ) {
            fuera.add( CorrectorPack.normalizarRuta( x ).toLowerCase( Locale.ROOT ) );
          }
        }
        fuera.add( BD + "/" + c.id.toLowerCase( Locale.ROOT ) ); // la carpeta de sus scripts
      }
    }
    fuera.add( CorrectorPack.PACK_JSON );
    boolean zip = destino.getName().toLowerCase( Locale.ROOT ).endsWith( ".zip" );
    revisarDestino( destino, zip, reemplazar, "student_copy" );
    File dir = zip ? Files.createTempDirectory( "pentaho-live-alumnos" ).toFile() : new File(
      destino.getParentFile(), "." + destino.getName() + ".tmp-" + System.nanoTime() );
    try {
      mkdirs( dir );
      copiarSin( dirPack, dir, "", fuera );
      CorrectorJson.guardar( a, new File( dir, CorrectorPack.PACK_JSON ) );
      try ( CorrectorPack v = CorrectorPack.abrir( dir ) ) {
        if ( !v.valido() ) {
          throw new Fallo( E_PACK, new Mensaje( "crear.err.invalid_student_copy", problemas( v.errores() ) ) );
        }
      }
      if ( zip ) {
        CorrectorPack.comprimir( dir, destino );
      } else {
        if ( destino.exists() ) {
          CorrectorSandbox.borrar( destino ); // reemplazar: revisarDestino ya vio que es un pack
        }
        Files.move( dir.toPath(), destino.toPath() );
      }
      return destino;
    } finally {
      if ( dir.exists() ) {
        CorrectorSandbox.borrar( dir );
      }
    }
  }

  /** Copia el arbol salvo las rutas relativas (sin mayusculas, con /) de {@code fuera} y lo que cuelga de ellas. */
  private static void copiarSin( File de, File a, String rel, Set<String> fuera ) throws IOException {
    File[] hijos = de.listFiles();
    if ( hijos == null ) {
      return;
    }
    for ( File h : hijos ) {
      if ( Files.isSymbolicLink( h.toPath() ) ) {
        continue;
      }
      String r = rel.isEmpty() ? h.getName() : rel + "/" + h.getName();
      if ( fuera.contains( r.toLowerCase( Locale.ROOT ) ) ) {
        continue;
      }
      File d = new File( a, h.getName() );
      if ( h.isDirectory() ) {
        mkdirs( d );
        copiarSin( h, d, r, fuera );
        String[] quedo = d.list();
        if ( quedo != null && quedo.length == 0 ) {
          Files.delete( d.toPath() ); // carpeta que solo tenia lo oculto
        }
      } else if ( h.isFile() ) {
        Files.copy( h.toPath(), d.toPath(), StandardCopyOption.REPLACE_EXISTING );
      }
    }
  }

  // ------------------------------------------------------------------ destino

  /** El destino tiene que no existir o estar vacio; con reemplazar, puede ser un .zip o una carpeta con pack.json. */
  private static void revisarDestino( File destino, boolean zip, boolean reemplazar, String que ) throws Fallo {
    if ( !destino.exists() ) {
      return;
    }
    if ( zip ) {
      if ( !destino.isFile() || !reemplazar ) {
        throw new Fallo( E_DESTINO, new Mensaje( "crear.err.exists", que, destino ) );
      }
      return;
    }
    String[] hijos = destino.isDirectory() ? destino.list() : null;
    if ( hijos != null && hijos.length == 0 ) {
      return;
    }
    if ( destino.isDirectory() && reemplazar && new File( destino, CorrectorPack.PACK_JSON ).isFile() ) {
      return;
    }
    throw new Fallo( E_DESTINO, new Mensaje( destino.isDirectory() ? "crear.err.exists_folder"
      : "crear.err.exists_file", que, destino ) );
  }

  /** Pasa el pack armado a su destino: un .zip, o la carpeta (renombrada si se puede, si no copiada). */
  private static File moverPack( File dirPack, File destino, boolean zip, boolean reemplazar ) throws IOException {
    if ( zip ) {
      CorrectorPack.comprimir( dirPack, destino );
      return destino;
    }
    File padre = destino.getParentFile();
    if ( padre != null ) {
      mkdirs( padre );
    }
    if ( destino.exists() ) {
      String[] hijos = destino.list();
      if ( hijos != null && hijos.length > 0 && reemplazar ) {
        CorrectorSandbox.borrar( destino );
      } else if ( hijos != null && hijos.length == 0 ) {
        Files.delete( destino.toPath() );
      }
    }
    try {
      Files.move( dirPack.toPath(), destino.toPath() );
    } catch ( IOException e ) {
      CorrectorMain.copiarArbol( dirPack, destino ); // otro disco: copia
      CorrectorSandbox.borrar( dirPack );
    }
    return destino;
  }

  // ------------------------------------------------------------------ hija

  /** {@code java ... CorrectorCrearPack encargo.json}: siempre escribe hallado.json y sale con 0 (1 si ni eso). */
  public static void main( String[] args ) {
    int codigo = 0;
    Hallado h = new Hallado();
    Encargo enc = null;
    try {
      enc = CorrectorJson.leer( new File( args[ 0 ] ), Encargo.class );
      if ( !FORMATO_ENCARGO.equals( enc.formato ) ) {
        throw new Fallo( E_HIJA, new Mensaje( "crear.err.bad_job", enc.formato ) );
      }
      KettleClientEnvironment.getInstance().setClient( KettleClientEnvironment.ClientType.PAN );
      LanguageChoice.getInstance().setDefaultLocale( Locale.US );
      KettleEnvironment.init( false );
      construir( enc, h );
    } catch ( Fallo f ) {
      h.codigoError = f.codigo;
      h.error = f.getMessage();
      if ( f.mensaje != null ) {
        h.claveError = f.mensaje.clave;
        h.argsError = new ArrayList<String>( f.mensaje.args );
      }
    } catch ( Throwable t ) {
      t.printStackTrace( System.out );
      Mensaje m = new Mensaje( "crear.err.builder", RunMotor.primeraLinea( t ) );
      h.codigoError = E_HIJA;
      h.error = m.en();
      h.claveError = m.clave;
      h.argsError = new ArrayList<String>( m.args );
    } finally {
      try {
        if ( enc != null && enc.resultado != null ) {
          CorrectorJson.guardar( h, new File( enc.resultado ) );
        } else {
          codigo = 1;
        }
      } catch ( Throwable t ) {
        t.printStackTrace( System.out );
        codigo = 1;
      }
    }
    System.out.println( "[crear-pack] " + ( h.codigoError == null ? "ok" : h.codigoError + ": " + h.error ) );
    System.out.flush();
    System.exit( codigo );
  }

  /** Lo de la hija (ver la clase). */
  static void construir( Encargo enc, Hallado h ) throws Exception {
    File dirPack = new File( enc.dirPack );
    File trabajo = new File( enc.dirTrabajo );
    CorrectorSandbox.borrar( trabajo );
    mkdirs( trabajo );
    File archivoPack = new File( dirPack, CorrectorPack.PACK_JSON );
    Pack pack = CorrectorJson.leer( archivoPack, Pack.class ).completar();
    File sol = new File( dirPack, pack.solucion.replace( '/', File.separatorChar ) );
    Entrega e;
    try {
      e = CorrectorPolitica.cargar( sol );
    } catch ( Throwable t ) {
      throw new Fallo( E_CARGA, new Mensaje( "crear.err.load", RunMotor.primeraLinea( t ) ) );
    }
    CorrectorBases.Sesion bases = null;
    if ( !pack.bases.isEmpty() ) {
      try {
        bases = new CorrectorBases.Sesion( pack, dirPack, "", CorrectorBases.clavesDelEntorno( pack ) );
      } catch ( CorrectorBases.Falla f ) {
        throw new Fallo( E_BASE_INSEGURA, f.mensaje );
      }
    }
    Detectado d = h.detectado;
    d.steps = e.tm.nrSteps();

    // entradas
    Map<String, File> leidas = entradasDe( e, new File( enc.dirOriginal ), h.avisos );
    if ( pack.entradas.isEmpty() ) {
      pack.entradas.addAll( leidas.keySet() );
    }
    d.entradas.addAll( pack.entradas );
    for ( Caso c : pack.casos ) {
      File destino = new File( dirPack, c.dirEntradas.replace( '/', File.separatorChar ) );
      mkdirs( destino );
      String fuente = enc.fuentes.get( c.id );
      for ( String n : pack.entradas ) {
        File de = fuente == null ? leidas.get( n ) : new File( fuente, n );
        if ( de == null || !de.isFile() ) {
          throw new Fallo( E_SIN_ENTRADA, fuente == null ? new Mensaje( "crear.err.input_not_read", c.id, n )
            : new Mensaje( "crear.err.input_missing", c.id, fuente, n ) );
        }
        Files.copy( de.toPath(), new File( destino, n ).toPath(), StandardCopyOption.REPLACE_EXISTING );
      }
    }

    // politica: los condicionales que usa la solucion quedan permitidos
    for ( Element s : e.steps() ) {
      String tipo = Nodos.txt( s, "type" );
      String canon = CorrectorPolitica.canonico( tipo );
      if ( PoliticaPack.CONDICIONAL.equals( CorrectorPolitica.nivel( tipo ) ) && !pack.politica.permite( canon ) ) {
        pack.politica.permitirSteps.add( canon );
        d.permitidos.add( canon );
        if ( "ScriptValueMod".equalsIgnoreCase( canon ) ) {
          h.avisos.add( new Mensaje( "crear.aviso.code_step_allowed", Nodos.crudo( s, "name" ) ).aviso(
            AVISO_CODIGO ) );
        }
      }
    }
    if ( !pack.bases.isEmpty() ) {
      // con bases de prueba, los steps de base de siempre quedan permitidos aunque la solucion no los use (un alumno
      // puede cargar con Table output lo que la solucion carga con Insert/Update); el SQL libre (Execute SQL) solo si
      // la solucion lo usa
      for ( String s : PASOS_DE_BASE ) {
        if ( !pack.politica.permite( s ) ) {
          pack.politica.permitirSteps.add( s );
          d.permitidos.add( s );
        }
      }
    }
    Revision rev = CorrectorPolitica.revisar( e, pack, false, Collections.<String>emptyList() );
    if ( rev.bloquea() ) {
      throw new Fallo( E_BLOQUEADA, new Mensaje( "crear.err.blocked", CorrectorTextos.clave( "motivo." + rev.motivo ),
        rev.mensaje == null ? rev.detalle : rev.mensaje.anidado() ) );
    }
    CorrectorJson.guardar( pack, archivoPack ); // entradas y allow_steps: el sandbox de los casos los lee

    // step de salida y columnas, sobre la copia de analisis (conexiones neutralizadas)
    TransMeta analisis = CorrectorMain.copiaDeAnalisis( e, new File( trabajo, CorrectorMain.ANALISIS ), pack,
      dirPack );
    if ( analisis == null ) {
      analisis = e.tm;
    }
    String captura = pack.salida.captura;
    boolean query = Pack.Salida.CAPTURA_QUERY.equals( captura );
    boolean tablas = Pack.Salida.CAPTURA_TABLAS.equals( captura );
    boolean aBase = query || tablas;
    CorrectorMotor.Salida salida = CorrectorMotor.elegirSalida( pack, analisis, captura );
    if ( pack.salida.step != null && ( salida.step == null || !salida.step.equals( pack.salida.step ) ) && !aBase ) {
      throw new Fallo( E_SIN_SALIDA, new Mensaje( Pack.Salida.CAPTURA_ARCHIVO.equals( captura )
        ? "crear.err.output_not_tfo" : "crear.err.output_not_step", pack.salida.step ) );
    }
    if ( !salida.hay() && !aBase ) {
      Mensaje m = salida.mensaje != null ? salida.mensaje : new Mensaje( "crear.err.no_output_step" );
      throw new Fallo( E_SIN_SALIDA, Pack.Salida.CAPTURA_ARCHIVO.equals( captura ) ? new Mensaje(
        "crear.err.no_output.file", m.anidado() ) : m );
    }
    if ( !salida.empatados.isEmpty() ) {
      h.avisos.add( new Mensaje( "crear.aviso.output_ambiguous", salida.step, String.join( ", ", salida.empatados ) )
        .aviso( AVISO_SALIDA_AMBIGUA ) );
    }
    if ( tablas && pack.salida.tablas.isEmpty() ) {
      pack.salida.tablas.addAll( tablasEscritas( sol, pack ) );
      if ( pack.salida.tablas.isEmpty() ) {
        throw new Fallo( E_SIN_SALIDA, new Mensaje( "crear.err.no_tables" ) );
      }
      CorrectorJson.guardar( pack, archivoPack );
    }
    d.stepSalida = salida.step;
    d.tipoSalida = salida.tipo;
    StepMeta smSalida = salida.step == null ? null : analisis.findStep( salida.step );
    if ( !aBase && smSalida != null ) {
      d.columnas.addAll( columnas( analisis, smSalida ) );
    }

    // los casos: captura step (o el SQL del pack) y esperado/
    CorrectorMotor.Contexto cx = new CorrectorMotor.Contexto();
    cx.pack = pack;
    cx.dirPack = dirPack;
    cx.captura = tablas ? Pack.Salida.CAPTURA_TABLAS : query ? Pack.Salida.CAPTURA_QUERY : Pack.Salida.CAPTURA_STEP;
    cx.salida = salida;
    cx.dirTmpWorker = new File( System.getProperty( "java.io.tmpdir" ) ).getAbsoluteFile();
    cx.bases = bases;
    CorrectorSandbox.Opciones so = new CorrectorSandbox.Opciones();
    so.dirTmpJvm = cx.dirTmpWorker;
    so.bases = bases;
    Map<String, CorrectorTabla> salidas = new LinkedHashMap<String, CorrectorTabla>();
    Map<String, Map<String, CorrectorBases.Captura>> capturas = new LinkedHashMap<String,
      Map<String, CorrectorBases.Captura>>();
    for ( Caso c : pack.casos ) {
      Preparacion p = CorrectorSandbox.preparar( pack, dirPack, c, e, new File( new File( trabajo, "casos" ), c.id ),
        so );
      if ( p.bloqueada() ) {
        throw new Fallo( E_BLOQUEADA, new Mensaje( "crear.err.blocked_case", c.id, CorrectorTextos.clave( "motivo."
          + p.motivo ), p.mensaje == null ? p.detalle : p.mensaje.anidado() ) );
      }
      Caso sinEsperado = new Caso( c.id, c.dirEntradas, null, c.oculto );
      sinEsperado.scriptsBase = c.scriptsBase;
      sinEsperado.ejecuciones = c.ejecuciones;
      ResultadoCaso rc = new ResultadoCaso();
      CorrectorMotor.Fin f = CorrectorMotor.correr( cx, sinEsperado, p, rc );
      if ( f.fallaBase != null ) {
        throw new Fallo( E_BASE, new Mensaje( "db.err.case", c.id, f.fallaBase.anidado() ) );
      }
      if ( !rc.corrioBien() || ( tablas ? f.tablas.isEmpty() : f.tabla == null ) ) {
        throw new Fallo( E_CORRIDA, new Mensaje( "crear.err.run_failed", c.id, CorrectorTextos.clave( "caso."
          + ( rc.corrida.estado == null ? "not_run" : rc.corrida.estado ) ), rc.corrida.claveDetalle != null
          ? new Mensaje( rc.corrida.claveDetalle, rc.corrida.argsDetalle ).anidado() : rc.corrida.detalle == null
          ? "-" : rc.corrida.detalle ) );
      }
      if ( tablas ) {
        for ( Map.Entry<String, CorrectorBases.Captura> t : f.tablas.entrySet() ) {
          if ( t.getValue().falta != null ) {
            throw new Fallo( E_SIN_SALIDA, new Mensaje( "crear.err.table_not_read", c.id, t.getKey(),
              t.getValue().falta ) );
          }
        }
        capturas.put( c.id, new LinkedHashMap<String, CorrectorBases.Captura>( f.tablas ) );
      } else {
        salidas.put( c.id, f.tabla );
      }
    }
    if ( tablas ) {
      esperadoDeTablas( pack, dirPack, capturas, sol, enc, h );
      d.reglas.addAll( reglasSugeridas( e.tm, null, pack.idioma ) );
      return;
    }
    Map<String, CorrectorTabla> tablas0 = salidas;
    if ( query || d.columnas.isEmpty() ) {
      d.columnas.clear();
      d.columnas.addAll( tiposDeValores( tablas0.values() ) );
    }

    // esperado/
    boolean conMascara = Pack.Salida.CAPTURA_ARCHIVO.equals( captura );
    for ( Caso c : pack.casos ) {
      CorrectorTabla t = tablas0.get( c.id );
      File f = new File( dirPack, c.esperado.replace( '/', File.separatorChar ) );
      escribirEsperado( t, d.columnas, pack.salida.csvEsperado, conMascara, f );
      CasoCreado cc = new CasoCreado();
      cc.id = c.id;
      cc.oculto = c.oculto;
      cc.filas = t.cantidadFilas();
      cc.columnas = t.columnas.size();
      cc.esperado = c.esperado;
      h.casos.add( cc );
      if ( cc.filas == 0 ) {
        h.avisos.add( new Mensaje( "crear.aviso.empty_output", c.id ).aviso( AVISO_SALIDA_VACIA ) );
      }
    }

    // claves
    List<String> claves = enc.claves != null ? enc.claves : smSalida == null ? Collections.<String>emptyList()
      : clavesDetectadas( analisis, smSalida, d.columnas );
    d.claves.addAll( claves );
    if ( claves.isEmpty() ) {
      if ( enc.claves == null ) {
        h.avisos.add( new Mensaje( "crear.aviso.no_key_columns" ).aviso( AVISO_SIN_CLAVE ) );
      }
    } else {
      for ( Caso c : pack.casos ) {
        String rep = repetida( tablas0.get( c.id ), claves, pack.comparar.distinguirMayusculas );
        if ( rep != null ) {
          h.clavesUnicas = false;
          h.avisos.add( new Mensaje( enc.claves == null ? "crear.aviso.key_not_unique"
            : "crear.aviso.key_not_unique.forced", c.id, String.join( ", ", claves ), c.oculto ? "-" : rep ).aviso(
            AVISO_CLAVE_NO_UNICA ) );
        }
      }
    }

    // reglas sugeridas
    d.reglas.addAll( reglasSugeridas( e.tm, smSalida == null ? null : e.tm.findStep( smSalida.getName() ),
      pack.idioma ) );
  }

  /**
   * Captura tables sin tablas pedidas: las que escriben los steps de salida a base de la solucion (Table output,
   * Insert/Update, Update, Dimension lookup, Combination lookup...) en una conexion con base de prueba, con la clave
   * que dice el step (las claves de Insert/Update, la natural de una dimension).
   */
  static List<TablaSalida> tablasEscritas( File solucion, Pack pack ) throws Exception {
    List<TablaSalida> l = new ArrayList<TablaSalida>();
    for ( CorrectorBases.TablaEscrita t : CorrectorBases.inventario( solucion ).tablasUnicas() ) {
      BaseDePrueba b = pack.base( t.conexion );
      if ( b == null ) {
        continue;
      }
      TablaSalida ts = new TablaSalida( b.conexion, t.tabla );
      ts.columnasClave.addAll( t.claves );
      l.add( ts );
    }
    return l;
  }

  /**
   * Captura tables: los tipos y la clave de cada tabla (la pedida; si no, la del step si es unica en todos los casos;
   * si no, la clave primaria si es unica; si no, sin clave) y {@code esperado/<caso>/<tabla>.csv}.
   */
  private static void esperadoDeTablas( Pack pack, File dirPack, Map<String, Map<String, CorrectorBases.Captura>> capturas,
                                        File solucion, Encargo enc, Hallado h ) throws Exception {
    Detectado d = h.detectado;
    for ( TablaSalida ts : pack.salida.tablas ) {
      CorrectorBases.Captura primera = null;
      List<CorrectorTabla> porCaso = new ArrayList<CorrectorTabla>();
      for ( Caso c : pack.casos ) {
        CorrectorBases.Captura x = capturas.get( c.id ).get( ts.nombre );
        if ( primera == null ) {
          primera = x;
        }
        porCaso.add( x.tabla );
      }
      ts.columnas = new ArrayList<Pack.Columna>( primera.tipos );
      List<List<String>> candidatas = new ArrayList<List<String>>();
      boolean pedida = !ts.columnasClave.isEmpty() && enc.claves != null;
      if ( !ts.columnasClave.isEmpty() ) {
        candidatas.add( new ArrayList<String>( ts.columnasClave ) );
      }
      if ( !pedida && !primera.clavePrimaria.isEmpty() ) {
        candidatas.add( new ArrayList<String>( primera.clavePrimaria ) );
      }
      List<String> clave = new ArrayList<String>();
      for ( List<String> k : candidatas ) {
        boolean sirve = true;
        String rep = null;
        for ( CorrectorTabla t : porCaso ) {
          rep = repetida( t, k, pack.comparar.distinguirMayusculas );
          sirve &= rep == null;
        }
        if ( sirve ) {
          clave = enMayusculas( k, primera.tabla );
          break;
        }
        h.avisos.add( new Mensaje( "crear.aviso.table_key_not_unique", ts.nombre, String.join( ", ", k ), rep )
          .aviso( AVISO_CLAVE_NO_UNICA ) );
      }
      if ( clave.isEmpty() ) {
        h.avisos.add( new Mensaje( "crear.aviso.table_no_key", ts.nombre ).aviso( AVISO_SIN_CLAVE ) );
      }
      ts.columnasClave = clave;
      int i = 0;
      for ( Caso c : pack.casos ) {
        CorrectorTabla t = porCaso.get( i++ );
        CorrectorBases.ordenar( t, clave );
        File f = new File( new File( dirPack, c.esperado.replace( '/', File.separatorChar ) ), ts.archivo() );
        escribirEsperado( t, ts.columnas, pack.salida.csvEsperado, false, f );
        CasoCreado cc = null;
        for ( CasoCreado x : h.casos ) {
          if ( x.id.equals( c.id ) ) {
            cc = x;
          }
        }
        if ( cc == null ) {
          cc = new CasoCreado();
          cc.id = c.id;
          cc.oculto = c.oculto;
          cc.esperado = c.esperado;
          h.casos.add( cc );
        }
        cc.filas += t.cantidadFilas();
        cc.columnas += t.columnas.size();
        if ( t.cantidadFilas() == 0 ) {
          h.avisos.add( new Mensaje( "crear.aviso.table_empty", ts.nombre, c.id ).aviso( AVISO_SALIDA_VACIA ) );
        }
      }
      d.tablas.add( ts );
    }
    d.claves.clear();
  }

  /** Los nombres de la clave como estan en la tabla (H2 los guarda en mayusculas). */
  private static List<String> enMayusculas( List<String> clave, CorrectorTabla t ) {
    List<String> l = new ArrayList<String>();
    for ( String k : clave ) {
      int i = t.indice( k );
      l.add( i < 0 ? k : t.columnas.get( i ) );
    }
    return l;
  }

  /**
   * Los archivos que leen los steps de entrada de la solucion (no las salidas ni las bases), por nombre: las
   * dependencias de cada step ({@code getResourceDependencies}) y las hojas filename/file_name y file/name del XML,
   * con las variables resueltas contra la carpeta original de la solucion. Solo los que existen.
   */
  static Map<String, File> entradasDe( Entrega e, File dirOriginal, List<Aviso> avisos ) {
    Map<String, File> m = new LinkedHashMap<String, File>();
    TransMeta tm = e.tm;
    String dir = dirOriginal.getAbsolutePath().replace( '\\', '/' );
    tm.setVariable( "Internal.Entry.Current.Directory", dir );
    tm.setVariable( "Internal.Transformation.Filename.Directory", dir );
    Set<String> vistas = new HashSet<String>();
    for ( Element s : e.steps() ) {
      String nombre = Nodos.crudo( s, "name" );
      String tipo = CorrectorPolitica.canonico( Nodos.txt( s, "type" ) );
      if ( CorrectorSandbox.SALIDAS.contains( tipo.toLowerCase( Locale.ROOT ) ) || CorrectorNota.SALIDAS_A_BASE
        .contains( tipo ) ) {
        continue;
      }
      List<String> rutas = new ArrayList<String>();
      StepMeta sm = tm.findStep( nombre );
      if ( sm != null ) {
        try {
          List<ResourceReference> refs = sm.getStepMetaInterface().getResourceDependencies( tm, sm );
          for ( ResourceReference r : refs == null ? Collections.<ResourceReference>emptyList() : refs ) {
            for ( ResourceEntry en : r.getEntries() ) {
              if ( en.getResourcetype() == ResourceEntry.ResourceType.FILE && en.getResource() != null ) {
                rutas.add( en.getResource() );
              }
            }
          }
        } catch ( Throwable ignored ) {
          // las hojas del XML alcanzan
        }
      }
      for ( Element[] hp : Nodos.hojas( s ) ) {
        String tag = hp[ 0 ].getTagName().toLowerCase( Locale.ROOT );
        String padre = hp[ 1 ] == null ? "" : hp[ 1 ].getTagName().toLowerCase( Locale.ROOT );
        if ( tag.equals( "filename" ) || tag.equals( "file_name" ) || tag.equals( "name" ) && padre.equals( "file" ) ) {
          String v = hp[ 0 ].getTextContent().trim();
          if ( !v.isEmpty() ) {
            rutas.add( v );
          }
        }
      }
      for ( String r : rutas ) {
        String v = tm.environmentSubstitute( r ).trim();
        if ( v.isEmpty() || CorrectorSandbox.red( v ) || CorrectorSandbox.esquema( v ) != null && !v.toLowerCase(
          Locale.ROOT ).startsWith( "file:" ) ) {
          continue;
        }
        File f;
        try {
          f = v.toLowerCase( Locale.ROOT ).startsWith( "file:" ) ? CorrectorSandbox.aArchivo( v ) : new File( v );
        } catch ( Throwable t ) {
          continue;
        }
        if ( !f.isAbsolute() ) {
          f = new File( dirOriginal, v );
        }
        // sin «..» ni «.»: ${Internal.Entry.Current.Directory}/../datos/x y datos/x son el mismo archivo
        f = f.getAbsoluteFile().toPath().normalize().toFile();
        String k = f.getAbsolutePath().toLowerCase( Locale.ROOT );
        if ( f.isDirectory() || !vistas.add( k ) ) {
          continue; // una carpeta (Get file names, comodines): no es un archivo del pack
        }
        if ( !f.isFile() ) {
          avisos.add( new Mensaje( "crear.aviso.input_not_found", nombre, v ).aviso( AVISO_ENTRADA_FALTA ) );
          continue;
        }
        String n = f.getName();
        File otra = null;
        for ( Map.Entry<String, File> x : m.entrySet() ) {
          if ( x.getKey().equalsIgnoreCase( n ) ) {
            otra = x.getValue();
          }
        }
        if ( otra != null ) {
          avisos.add( new Mensaje( "crear.aviso.input_name_clash", n, otra, f ).aviso( AVISO_ENTRADA_REPETIDA ) );
          continue;
        }
        m.put( n, f );
      }
    }
    return m;
  }

  /**
   * Las columnas de la salida con sus tipos: los campos declarados del Text file output (tipo del flujo si el campo
   * no lo dice, mascara del campo o del flujo) o los campos del step.
   */
  static List<Pack.Columna> columnas( final TransMeta tm, final StepMeta sm ) throws Exception {
    List<Pack.Columna> l = new ArrayList<Pack.Columna>();
    final boolean tfo = sm.getStepMetaInterface() instanceof TextFileOutputMeta;
    RowMetaInterface flujo = null;
    try {
      flujo = RunMotor.enSegundoPlano( () -> tfo ? tm.getPrevStepFields( sm ) : tm.getStepFields( sm ),
        CorrectorMotor.MS_CAMPOS, null, "Fields of " + sm.getName() );
    } catch ( Throwable ignored ) {
      flujo = null;
    }
    TextFileField[] campos = tfo ? ( (TextFileOutputMeta) sm.getStepMetaInterface() ).getOutputFields() : null;
    if ( campos != null && campos.length > 0 ) {
      for ( TextFileField f : campos ) {
        ValueMetaInterface vm = flujo == null ? null : flujo.searchValueMeta( f.getName() );
        String tipo = f.getTypeDesc();
        if ( tipo == null || !CorrectorPack.TIPOS_KETTLE.contains( tipo ) ) {
          tipo = vm == null ? "String" : vm.getTypeDesc();
        }
        String mascara = vacio( f.getFormat() ) ? vm == null ? null : vm.getConversionMask() : f.getFormat();
        l.add( columna( f.getName(), tipo, mascara ) );
      }
      return l;
    }
    for ( int i = 0; flujo != null && i < flujo.size(); i++ ) {
      ValueMetaInterface vm = flujo.getValueMeta( i );
      l.add( columna( vm.getName(), vm.getTypeDesc(), vm.getConversionMask() ) );
    }
    return l;
  }

  private static Pack.Columna columna( String nombre, String tipo, String mascara ) {
    Pack.Columna c = new Pack.Columna( nombre, CorrectorPack.TIPOS_KETTLE.contains( tipo ) ? tipo : "String" );
    if ( !vacio( mascara ) && ( c.esNumerica() || c.esFecha() ) ) {
      c.formato = mascara.trim();
    }
    return c;
  }

  /** Captura query (o sin columnas): los tipos se deducen de los valores (enteros, numeros, fechas ISO, texto). */
  static List<Pack.Columna> tiposDeValores( java.util.Collection<CorrectorTabla> tablas ) {
    List<Pack.Columna> l = new ArrayList<Pack.Columna>();
    CorrectorTabla primera = tablas.isEmpty() ? null : tablas.iterator().next();
    if ( primera == null ) {
      return l;
    }
    for ( int j = 0; j < primera.columnas.size(); j++ ) {
      boolean hay = false;
      boolean entero = true;
      boolean numero = true;
      boolean fecha = true;
      for ( CorrectorTabla t : tablas ) {
        for ( int i = 0; i < t.cantidadFilas() && j < t.columnas.size(); i++ ) {
          String v = t.celda( i, j );
          if ( v == null || v.isEmpty() ) {
            continue;
          }
          hay = true;
          boolean ceroIzq = v.length() > 1 && v.startsWith( "0" ) && !v.startsWith( "0." );
          entero &= ENTERO.matcher( v ).matches() && !ceroIzq;
          numero &= NUMERO.matcher( v ).matches() && !ceroIzq;
          fecha &= FECHA_ISO.matcher( v ).matches();
        }
      }
      String n = primera.columnas.get( j );
      Pack.Columna c = !hay ? new Pack.Columna( n, "String" ) : fecha ? new Pack.Columna( n, "Date" ) : entero
        ? new Pack.Columna( n, "Integer" ) : numero ? new Pack.Columna( n, "Number" ) : new Pack.Columna( n,
        "String" );
      if ( hay && fecha ) {
        c.formato = "yyyy-MM-dd";
      }
      l.add( c );
    }
    return l;
  }

  /**
   * Escribe la salida esperada de un caso con el formato de {@code expected_csv}: encabezado, separador, encierro
   * solo donde hace falta y, con {@code conMascara}, los numeros con la mascara de la solucion (punto decimal, sin
   * miles: lo que escribe su Text file output).
   */
  static void escribirEsperado( CorrectorTabla t, List<Pack.Columna> cols, Pack.FormatoCsv f, boolean conMascara,
                                File destino ) throws IOException {
    String sep = f == null || f.separador == null ? ";" : f.separador;
    String enc = f == null || f.encierro == null ? "\"" : f.encierro;
    Charset cs;
    try {
      cs = Charset.forName( f == null || vacio( f.encoding ) ? "UTF-8" : f.encoding );
    } catch ( Exception e ) {
      cs = StandardCharsets.UTF_8;
    }
    StringBuilder b = new StringBuilder();
    if ( f == null || f.encabezado ) {
      for ( int j = 0; j < t.columnas.size(); j++ ) {
        b.append( j == 0 ? "" : sep ).append( celda( t.columnas.get( j ), sep, enc ) );
      }
      b.append( "\n" );
    }
    for ( int i = 0; i < t.cantidadFilas(); i++ ) {
      for ( int j = 0; j < t.columnas.size(); j++ ) {
        String v = t.celda( i, j );
        Pack.Columna c = j < cols.size() && cols.get( j ).nombre != null && cols.get( j ).nombre.equalsIgnoreCase(
          t.columnas.get( j ) ) ? cols.get( j ) : columnaDe( cols, t.columnas.get( j ) );
        if ( v != null && conMascara && c != null && c.esNumerica() ) {
          v = conMascara( v, c.formato );
        }
        b.append( j == 0 ? "" : sep ).append( v == null ? "" : celda( v, sep, enc ) );
      }
      b.append( "\n" );
    }
    mkdirs( destino.getAbsoluteFile().getParentFile() );
    CorrectorPlanilla.escribirAtomico( b.toString().getBytes( cs ), destino );
  }

  private static Pack.Columna columnaDe( List<Pack.Columna> cols, String nombre ) {
    for ( Pack.Columna c : cols ) {
      if ( c.nombre != null && c.nombre.equalsIgnoreCase( nombre ) ) {
        return c;
      }
    }
    return null;
  }

  /** Entre encierro solo si hace falta (separador, encierro, salto de linea o espacios en los bordes). */
  private static String celda( String v, String sep, String enc ) {
    boolean hace = !sep.isEmpty() && v.contains( sep ) || !enc.isEmpty() && v.contains( enc ) || v.indexOf( '\n' )
      >= 0 || v.indexOf( '\r' ) >= 0 || !v.equals( v.trim() );
    if ( !hace || enc.isEmpty() ) {
      return v;
    }
    return enc + v.replace( enc, enc + enc ) + enc;
  }

  /** Un numero plano con la mascara de la solucion ("0.00" → 20.00), si es simple (digitos, # . ,); si no, igual. */
  static String conMascara( String v, String mascara ) {
    if ( vacio( mascara ) || !MASCARA_SIMPLE.matcher( mascara.trim() ).matches() ) {
      return v;
    }
    BigDecimal b;
    try {
      b = new BigDecimal( v.trim() );
    } catch ( NumberFormatException e ) {
      return v;
    }
    try {
      DecimalFormat df = new DecimalFormat( mascara.trim().replace( ",", "" ), DecimalFormatSymbols.getInstance(
        Locale.ROOT ) );
      df.setGroupingUsed( false );
      df.setRoundingMode( RoundingMode.HALF_EVEN );
      return df.format( b );
    } catch ( IllegalArgumentException e ) {
      return v;
    }
  }

  /**
   * Las claves de la salida: los campos de agrupacion del Group by / Memory group by mas cercano antes del step de
   * salida (por hops habilitados) o, si no hay, los del Sort rows mas cercano; solo si todos estan en las columnas.
   */
  static List<String> clavesDetectadas( TransMeta tm, StepMeta salida, List<Pack.Columna> columnas ) {
    Set<String> cols = new HashSet<String>();
    for ( Pack.Columna c : columnas ) {
      if ( c.nombre != null ) {
        cols.add( c.nombre.trim().toLowerCase( Locale.ROOT ) );
      }
    }
    List<String> orden = null;
    Deque<StepMeta> cola = new ArrayDeque<StepMeta>();
    Set<StepMeta> vistos = new HashSet<StepMeta>();
    cola.add( salida );
    vistos.add( salida );
    while ( !cola.isEmpty() ) {
      StepMeta s = cola.poll();
      List<String> campos = null;
      boolean agrupa = false;
      if ( s.getStepMetaInterface() instanceof GroupByMeta ) {
        campos = lista( ( (GroupByMeta) s.getStepMetaInterface() ).getGroupField() );
        agrupa = true;
      } else if ( s.getStepMetaInterface() instanceof MemoryGroupByMeta ) {
        campos = lista( ( (MemoryGroupByMeta) s.getStepMetaInterface() ).getGroupField() );
        agrupa = true;
      } else if ( s.getStepMetaInterface() instanceof SortRowsMeta ) {
        campos = lista( ( (SortRowsMeta) s.getStepMetaInterface() ).getFieldName() );
      }
      if ( campos != null && !campos.isEmpty() && todas( campos, cols ) ) {
        if ( agrupa ) {
          return campos;
        }
        if ( orden == null ) {
          orden = campos;
        }
      }
      for ( TransHopMeta h : tm.getTransHops() ) {
        if ( h.isEnabled() && s.equals( h.getToStep() ) && h.getFromStep() != null && vistos.add( h.getFromStep() ) ) {
          cola.add( h.getFromStep() );
        }
      }
    }
    return orden == null ? new ArrayList<String>() : orden;
  }

  private static List<String> lista( String[] a ) {
    List<String> l = new ArrayList<String>();
    for ( String s : a == null ? new String[ 0 ] : a ) {
      if ( s != null && !s.trim().isEmpty() ) {
        l.add( s.trim() );
      }
    }
    return l;
  }

  private static boolean todas( List<String> campos, Set<String> cols ) {
    for ( String c : campos ) {
      if ( !cols.contains( c.toLowerCase( Locale.ROOT ) ) ) {
        return false;
      }
    }
    return true;
  }

  /** La primera clave repetida de la tabla ("Café ×2"), o null si son unicas. */
  static String repetida( CorrectorTabla t, List<String> claves, boolean mayusculas ) {
    int[] ix = new int[ claves.size() ];
    for ( int k = 0; k < ix.length; k++ ) {
      ix[ k ] = t.indice( claves.get( k ) );
      if ( ix[ k ] < 0 ) {
        return "column " + claves.get( k ) + " is not in the output";
      }
    }
    Map<String, Integer> vistas = new LinkedHashMap<String, Integer>();
    for ( int i = 0; i < t.cantidadFilas(); i++ ) {
      StringBuilder b = new StringBuilder();
      for ( int k = 0; k < ix.length; k++ ) {
        String v = t.celda( i, ix[ k ] );
        v = v == null ? "" : v.trim();
        b.append( k == 0 ? "" : " | " ).append( mayusculas ? v : v.toLowerCase( Locale.ROOT ) );
      }
      String k = b.toString();
      Integer n = vistas.get( k );
      vistas.put( k, n == null ? 1 : n + 1 );
    }
    for ( Map.Entry<String, Integer> e : vistas.entrySet() ) {
      if ( e.getValue() > 1 ) {
        return e.getKey() + " x" + e.getValue();
      }
    }
    return null;
  }

  /**
   * Las reglas que se sugieren (§8 suggested_rules): must_use de la primera familia que usa la solucion conectada por
   * hops habilitados (Group by, Denormaliser, Merge join...), must_not_use de codigo si la solucion no lo usa y
   * max_steps con el numero de steps + 3.
   */
  static List<Regla> reglasSugeridas( TransMeta tm, StepMeta salida, String idioma ) {
    Rotulos t = Rotulos.de( CorrectorNota.AREA, idioma );
    List<Regla> l = new ArrayList<Regla>();
    Set<String> tipos = new LinkedHashSet<String>();
    Set<String> conectados = new LinkedHashSet<String>();
    Set<StepMeta> ancestros = salida == null ? null : CorrectorNota.ancestros( tm, Collections.singletonList(
      salida ) );
    for ( StepMeta s : tm.getSteps() ) {
      String id = s.getStepID() == null ? "" : s.getStepID();
      tipos.add( id.toLowerCase( Locale.ROOT ) );
      if ( ancestros == null || ancestros.contains( s ) ) {
        conectados.add( id.toLowerCase( Locale.ROOT ) );
      }
    }
    for ( String[] f : FAMILIAS ) {
      boolean usa = false;
      for ( int i = 2; i < f.length; i++ ) {
        usa |= conectados.contains( f[ i ].toLowerCase( Locale.ROOT ) );
      }
      if ( usa ) {
        Regla r = new Regla( f[ 0 ], Regla.DEBE_USAR, Arrays.copyOfRange( f, 2, f.length ) );
        r.etiqueta = t.f( "crear.regla.usa", f[ 1 ] );
        l.add( r );
        break;
      }
    }
    boolean codigo = false;
    for ( String c : CODIGO ) {
      codigo |= tipos.contains( c.toLowerCase( Locale.ROOT ) );
    }
    if ( !codigo ) {
      Regla r = new Regla( "sin_codigo", Regla.NO_DEBE_USAR, CODIGO.toArray( new String[ 0 ] ) );
      r.etiqueta = t.s( "crear.regla.sin_codigo" );
      l.add( r );
    }
    Regla max = new Regla( "max_pasos", Regla.MAX_STEPS );
    max.valor = tm.nrSteps() + 3;
    max.etiqueta = t.f( "crear.regla.max_pasos", max.valor );
    l.add( max );
    return l;
  }

  // ------------------------------------------------------------------ ayudas

  /** Un id de pack: el nombre de la carpeta o del .zip si sirve, si no el de la solucion. */
  private static String idDe( String destino, String solucion ) {
    String a = NO_ID.matcher( destino == null ? "" : destino.trim() ).replaceAll( "-" ).replaceAll( "^[-.]+|[-.]+$",
      "" );
    if ( !a.isEmpty() ) {
      return a.toLowerCase( Locale.ROOT );
    }
    String b = NO_ID.matcher( solucion ).replaceAll( "-" ).replaceAll( "^[-.]+|[-.]+$", "" );
    return b.isEmpty() ? "ejercicio" : b.toLowerCase( Locale.ROOT );
  }

  /** Texto en un span de codigo de Markdown (sin acentos graves, barras verticales ni saltos que lo rompan). */
  private static String codigo( String s ) {
    return "`" + ( s == null ? "" : s.replace( '`', '\'' ).replace( '|', '/' ).replace( '\n', ' ' ).replace( '\r',
      ' ' ) ) + "`";
  }

  private static String colaLog( File log ) {
    try {
      String x = new String( Files.readAllBytes( log.toPath() ), StandardCharsets.UTF_8 );
      int desde = Math.max( 0, x.length() - 1500 );
      return "\n--- " + log.getName() + " (end) ---\n" + x.substring( desde );
    } catch ( IOException e ) {
      return "";
    }
  }

  private static String numero( double d ) {
    return BigDecimal.valueOf( d ).stripTrailingZeros().toPlainString();
  }

  private static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  private static void mkdirs( File d ) throws IOException {
    if ( d != null && !d.isDirectory() && !d.mkdirs() && !d.isDirectory() ) {
      throw new IOException( "cannot create " + d );
    }
  }
}
