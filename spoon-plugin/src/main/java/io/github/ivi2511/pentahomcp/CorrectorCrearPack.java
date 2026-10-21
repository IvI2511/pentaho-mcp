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
 * Los errores que impiden armar el pack lanzan {@link Fallo} con un codigo estable y un texto en ingles.
 *
 * @dueño C6
 */
public final class CorrectorCrearPack {

  public static final String FORMATO_ENCARGO = "pentaho-live-pack-build/1";
  static final String SOLUCION = "solucion";
  static final String DATOS = "datos";
  static final String ESPERADO = "esperado";
  static final String PLANTILLA = "plantilla";
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

  /** Familias que sugieren una regla must_use (la primera que use la solucion), en orden de prioridad. */
  private static final String[][] FAMILIAS = { { "usa_group_by", "Group by / Memory group by", "GroupBy",
    "MemoryGroupBy" }, { "usa_denormaliser", "Row denormaliser", "Denormaliser" }, { "usa_normaliser",
    "Row normaliser", "Normaliser" }, { "usa_merge_join", "Merge join", "MergeJoin" }, { "usa_lookup",
    "Stream lookup / Database lookup", "StreamLookup", "DBLookup" }, { "usa_join_rows", "Join rows",
    "JoinRows" }, { "usa_unique", "Unique rows", "Unique", "UniqueRowsByHashSet" }, { "usa_switch_case",
    "Switch / case", "SwitchCase" }, { "usa_filtro", "Filter rows", "FilterRows" } };
  private static final List<String> CODIGO = Arrays.asList( "ScriptValueMod", "UserDefinedJavaClass", "Janino" );
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

  /** No se pudo armar el pack: {@link #codigo} estable (E_*) y el mensaje en ingles. */
  public static final class Fallo extends Exception {

    private static final long serialVersionUID = 1L;
    public final String codigo;

    Fallo( String codigo, String mensaje ) {
      super( mensaje );
      this.codigo = codigo;
    }
  }

  /** Un caso nuevo: sus entradas salen de una carpeta del docente. */
  static final class CasoNuevo {

    String id;
    /** Carpeta del docente con los archivos de inputs para este caso; null = los que lee la solucion. */
    File dirEntradas;
    boolean oculto;
    double peso = 1;

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
      throw new Fallo( E_SOLUCION, "the solution transformation does not exist: " + ( op == null ? null
        : op.solucion ) );
    }
    if ( !op.solucion.getName().toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) ) {
      throw new Fallo( E_OPCIONES, "the solution must be a .ktr (jobs are not graded yet): " + op.solucion.getName() );
    }
    if ( op.destino == null ) {
      throw new Fallo( E_OPCIONES, "pack_path is required" );
    }
    String captura = op.captura == null ? Pack.Salida.CAPTURA_ARCHIVO : op.captura;
    if ( !Pack.Salida.CAPTURAS.contains( captura ) ) {
      throw new Fallo( E_OPCIONES, "capture must be one of " + Pack.Salida.CAPTURAS + ": " + captura );
    }
    if ( Pack.Salida.CAPTURA_QUERY.equals( captura ) && ( op.plantilla == null || !op.plantilla.isFile()
      || op.sql == null || op.sql.trim().isEmpty() ) ) {
      throw new Fallo( E_OPCIONES, "capture query needs the template database (an empty SQLite file with the target "
        + "tables) and the SQL that reads the result" );
    }
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
        throw new Fallo( E_OPCIONES, "bad case id (letters, digits, . _ -): " + c.id );
      }
      if ( !ids.add( c.id.toLowerCase( Locale.ROOT ) ) ) {
        throw new Fallo( E_OPCIONES, "duplicate case id: " + c.id );
      }
      if ( c.dirEntradas != null && !c.dirEntradas.isDirectory() ) {
        throw new Fallo( E_SIN_ENTRADA, "case " + c.id + ": the inputs folder does not exist: " + c.dirEntradas );
      }
      visible |= !c.oculto;
    }
    if ( !visible ) {
      throw new Fallo( E_OPCIONES, "at least one case must be visible" );
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
    for ( CasoNuevo c : casos ) {
      Caso k = new Caso( c.id, DATOS + "/" + c.id, ESPERADO + "/" + c.id + ".csv", c.oculto );
      k.peso = c.peso;
      p.casos.add( k );
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
      throw new IllegalStateException( "data-integration not found (" + pdi + "): set the PDI folder" );
    }
    File log = new File( trabajo, "crear-pack.log" );
    Process pr = CorrectorProcesos.lanzarHija( CorrectorProcesos.comandoBase( po, p, pdi ), dirHija,
      CorrectorCrearPack.class.getName(), Collections.singletonList( archivoEnc.getAbsolutePath() ), log,
      "pack-builder" );
    long limite = Math.max( 1, p.politica.timeoutSeg ) * 1000L * Math.max( 1, casos.size() ) + MARGEN_HIJA_MS;
    try {
      if ( !pr.waitFor( limite, TimeUnit.MILLISECONDS ) ) {
        CorrectorProcesos.matar( pr );
        throw new Fallo( E_HIJA, "running the solution took more than " + ( limite / 1000 ) + " s" + colaLog( log ) );
      }
    } catch ( InterruptedException e ) {
      CorrectorProcesos.matar( pr );
      Thread.currentThread().interrupt();
      throw e;
    }
    if ( !archivoHallado.isFile() ) {
      throw new Fallo( E_HIJA, "the pack builder stopped (exit code " + pr.exitValue() + ") without a result"
        + colaLog( log ) );
    }
    Hallado h = CorrectorJson.leer( archivoHallado, Hallado.class );
    if ( h.codigoError != null ) {
      throw new Fallo( h.codigoError, h.error );
    }

    // pack.json completo
    Creado cr = new Creado();
    cr.detectado = h.detectado;
    cr.casos.addAll( h.casos );
    cr.avisos.addAll( h.avisos );
    p.entradas = new ArrayList<String>( h.detectado.entradas );
    p.salida.step = h.detectado.stepSalida;
    p.salida.columnas = new ArrayList<Pack.Columna>( h.detectado.columnas );
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
        throw new Fallo( E_PACK, "the created pack is not valid:\n" + v.textoProblemas( "en" ) );
      }
      for ( CorrectorPack.Problema pr2 : v.avisos() ) {
        cr.avisos.add( new Aviso( AVISO_PACK, pr2.linea( "en" ) ) );
      }
    }

    // la solucion con el otro locale
    if ( op.verificarLocales ) {
      cr.verificacion = verificarLocale( dirPack, p, trabajo, op.procesos, cr.avisos );
    }

    // copia para alumnos (de la carpeta armada) y destino
    if ( op.copiaAlumnos != null ) {
      cr.copiaAlumnos = copiaParaAlumnos( dirPack, op.copiaAlumnos.getAbsoluteFile(), op.reemplazar )
        .getAbsolutePath();
    }
    cr.pack = moverPack( dirPack, destino, zip, op.reemplazar ).getAbsolutePath();
    return cr;
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
        avisos.add( new Aviso( AVISO_LOCALE, "with the locale " + otro + " the solution gets " + ( r == null
          || r.nota == null ? "no grade" : r.nota + "/" + numero( p.escala ) ) + ( r == null ? ""
          : " (" + r.estado + ( v.fallados.isEmpty() ? "" : ", failed: " + String.join( ", ", v.fallados ) ) + ")" )
          + ": its result depends on the locale (an empty decimal symbol when reading or writing?). The pack keeps "
          + p.politica.locale ) );
      }
    } catch ( Throwable e ) {
      avisos.add( new Aviso( AVISO_SIN_VERIFICAR, "the solution could not be graded with " + otro + ": "
        + RunMotor.primeraLinea( e ) ) );
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
          throw new Fallo( E_PACK, "the student copy is not valid:\n" + v.textoProblemas( "en" ) );
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
        throw new Fallo( E_DESTINO, que + " already exists: " + destino + " (pass replace to overwrite it)" );
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
    throw new Fallo( E_DESTINO, que + " already exists and is not empty: " + destino + ( destino.isDirectory()
      ? " (only an exercise pack can be replaced)" : "" ) );
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
        throw new Fallo( E_HIJA, "unknown job format " + enc.formato );
      }
      KettleClientEnvironment.getInstance().setClient( KettleClientEnvironment.ClientType.PAN );
      LanguageChoice.getInstance().setDefaultLocale( Locale.US );
      KettleEnvironment.init( false );
      construir( enc, h );
    } catch ( Fallo f ) {
      h.codigoError = f.codigo;
      h.error = f.getMessage();
    } catch ( Throwable t ) {
      t.printStackTrace( System.out );
      h.codigoError = E_HIJA;
      h.error = "the pack builder failed: " + RunMotor.primeraLinea( t );
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
      throw new Fallo( E_CARGA, "the solution could not be loaded: " + RunMotor.primeraLinea( t ) );
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
          throw new Fallo( E_SIN_ENTRADA, "case " + c.id + ": " + ( fuente == null ? "the solution does not read "
            + n : fuente + " has no " + n ) );
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
          h.avisos.add( new Aviso( AVISO_CODIGO, "the solution uses Modified JavaScript (" + Nodos.crudo( s, "name" )
            + "): it is allowed in this pack, and running JavaScript is running code" ) );
        }
      }
    }
    Revision rev = CorrectorPolitica.revisar( e, pack, false, Collections.<String>emptyList() );
    if ( rev.bloquea() ) {
      throw new Fallo( E_BLOQUEADA, "the grader would not run the solution (" + rev.motivo + "): " + rev.detalle );
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
    CorrectorMotor.Salida salida = CorrectorMotor.elegirSalida( pack, analisis, captura );
    if ( pack.salida.step != null && ( salida.step == null || !salida.step.equals( pack.salida.step ) ) && !query ) {
      throw new Fallo( E_SIN_SALIDA, "the output step " + CorrectorPolitica.q( pack.salida.step ) + " is not a "
        + ( Pack.Salida.CAPTURA_ARCHIVO.equals( captura ) ? "Text file output" : "step" ) + " of the solution" );
    }
    if ( !salida.hay() && !query ) {
      throw new Fallo( E_SIN_SALIDA, ( salida.motivo == null ? "the solution has no output step" : salida.motivo )
        + ( Pack.Salida.CAPTURA_ARCHIVO.equals( captura ) ? " (use capture step for other outputs)" : "" ) );
    }
    if ( !salida.empatados.isEmpty() ) {
      h.avisos.add( new Aviso( AVISO_SALIDA_AMBIGUA, "several output steps: " + CorrectorPolitica.q( salida.step )
        + " was chosen (also " + salida.empatados + "); pass output_step to choose" ) );
    }
    d.stepSalida = salida.step;
    d.tipoSalida = salida.tipo;
    StepMeta smSalida = salida.step == null ? null : analisis.findStep( salida.step );
    if ( !query && smSalida != null ) {
      d.columnas.addAll( columnas( analisis, smSalida ) );
    }

    // los casos: captura step (o el SQL del pack) y esperado/
    CorrectorMotor.Contexto cx = new CorrectorMotor.Contexto();
    cx.pack = pack;
    cx.dirPack = dirPack;
    cx.captura = query ? Pack.Salida.CAPTURA_QUERY : Pack.Salida.CAPTURA_STEP;
    cx.salida = salida;
    cx.dirTmpWorker = new File( System.getProperty( "java.io.tmpdir" ) ).getAbsoluteFile();
    CorrectorSandbox.Opciones so = new CorrectorSandbox.Opciones();
    so.dirTmpJvm = cx.dirTmpWorker;
    Map<String, CorrectorTabla> tablas = new LinkedHashMap<String, CorrectorTabla>();
    for ( Caso c : pack.casos ) {
      Preparacion p = CorrectorSandbox.preparar( pack, dirPack, c, e, new File( new File( trabajo, "casos" ), c.id ),
        so );
      if ( p.bloqueada() ) {
        throw new Fallo( E_BLOQUEADA, "case " + c.id + ": the grader would not run the solution (" + p.motivo + "): "
          + p.detalle );
      }
      Caso sinEsperado = new Caso( c.id, c.dirEntradas, null, c.oculto );
      ResultadoCaso rc = new ResultadoCaso();
      CorrectorMotor.Fin f = CorrectorMotor.correr( cx, sinEsperado, p, rc );
      if ( !rc.corrioBien() || f.tabla == null ) {
        throw new Fallo( E_CORRIDA, "case " + c.id + ": the solution did not finish cleanly (" + rc.corrida.estado
          + ( rc.corrida.detalle == null ? "" : ": " + rc.corrida.detalle ) + ")" );
      }
      tablas.put( c.id, f.tabla );
    }
    if ( query || d.columnas.isEmpty() ) {
      d.columnas.clear();
      d.columnas.addAll( tiposDeValores( tablas.values() ) );
    }

    // esperado/
    boolean conMascara = Pack.Salida.CAPTURA_ARCHIVO.equals( captura );
    for ( Caso c : pack.casos ) {
      CorrectorTabla t = tablas.get( c.id );
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
        h.avisos.add( new Aviso( AVISO_SALIDA_VACIA, "case " + c.id + ": the solution writes no rows" ) );
      }
    }

    // claves
    List<String> claves = enc.claves != null ? enc.claves : smSalida == null ? Collections.<String>emptyList()
      : clavesDetectadas( analisis, smSalida, d.columnas );
    d.claves.addAll( claves );
    if ( claves.isEmpty() ) {
      if ( enc.claves == null ) {
        h.avisos.add( new Aviso( AVISO_SIN_CLAVE, "no key columns found (no Group by or Sort rows before the "
          + "output): rows are compared as a multiset" ) );
      }
    } else {
      for ( Caso c : pack.casos ) {
        String rep = repetida( tablas.get( c.id ), claves, pack.comparar.distinguirMayusculas );
        if ( rep != null ) {
          h.clavesUnicas = false;
          h.avisos.add( new Aviso( AVISO_CLAVE_NO_UNICA, "case " + c.id + ": the key " + claves + " repeats ("
            + ( c.oculto ? "hidden case" : rep ) + ")" + ( enc.claves == null ? ": rows are compared without a key"
            : ": the comparison will report duplicate keys" ) ) );
        }
      }
    }

    // reglas sugeridas
    d.reglas.addAll( reglasSugeridas( e.tm, smSalida == null ? null : e.tm.findStep( smSalida.getName() ),
      pack.idioma ) );
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
        String k = f.getAbsolutePath().toLowerCase( Locale.ROOT );
        if ( f.isDirectory() || !vistas.add( k ) ) {
          continue; // una carpeta (Get file names, comodines): no es un archivo del pack
        }
        if ( !f.isFile() ) {
          avisos.add( new Aviso( AVISO_ENTRADA_FALTA, "step " + CorrectorPolitica.q( nombre ) + " reads " + v
            + ", which does not exist" ) );
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
          avisos.add( new Aviso( AVISO_ENTRADA_REPETIDA, "two inputs are called " + n + " (" + otra + " and " + f
            + "): the grader redirects both to datos/" + n ) );
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
