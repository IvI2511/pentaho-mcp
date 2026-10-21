package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raiz de {@code pack.json}, formato {@value #FORMATO} (docs/corrector-diseno.md §2). Un pack es una carpeta o un
 * .zip con este JSON, el enunciado, los datos y la salida esperada de cada caso; o un solo .json con los archivos
 * en {@link #inline}. Todas las rutas son relativas a la carpeta del pack (las valida CorrectorPack, C1).
 * <p>
 * POJO con campos publicos para el Jackson 2.9 de lib\ (ver {@link CorrectorJson}): claves en ingles y snake_case,
 * campos en castellano. Al escribir se omiten null, listas y mapas vacios. {@link #completar()} pone los valores
 * por defecto donde el JSON traia null.
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Pack {

  public static final String FORMATO = "pentaho-live-exercise-pack/1";
  public static final String TRANSFORMACION = "transformation";
  public static final String JOB = "job";
  /** Tope del contenido de {@link #inline} (§2.2), en caracteres UTF-16 sumados. */
  public static final long TOPE_INLINE = 2L * 1024 * 1024;

  @JsonProperty( "format" )
  public String formato = FORMATO;
  @JsonProperty( "id" )
  public String id;
  @JsonProperty( "title" )
  public String titulo;
  /** en | es | pt: idioma de la devolucion y de las pistas. */
  @JsonProperty( "language" )
  public String idioma;
  /** Markdown que lee el alumno ("enunciado.md"). */
  @JsonProperty( "statement" )
  public String enunciado;
  /** {@link #TRANSFORMACION} o {@link #JOB} [NP]. */
  @JsonProperty( "kind" )
  public String tipo = TRANSFORMACION;
  /** La solucion del docente ("solucion/03_ventas.ktr"); no esta en la copia para alumnos. */
  @JsonProperty( "solution" )
  public String solucion;
  /** Entrega en carpeta con varios .ktr: cual se corrige (§10). */
  @JsonProperty( "submission_file" )
  public String archivoEntrega;
  /** Nombres de archivo que se redirigen a datos/ del caso ("ventas.csv"). */
  @JsonProperty( "inputs" )
  public List<String> entradas = new ArrayList<>();
  /** Se pasan con setParameterValue si la entrega los declara [NP]. */
  @JsonProperty( "parameters" )
  public Map<String, String> parametros = new LinkedHashMap<>();
  @JsonProperty( "cases" )
  public List<Caso> casos = new ArrayList<>();
  @JsonProperty( "output" )
  public Salida salida = new Salida();
  @JsonProperty( "compare" )
  public OpcionesComparar comparar = new OpcionesComparar();
  @JsonProperty( "rules" )
  public List<Regla> reglas = new ArrayList<>();
  @JsonProperty( "rubric" )
  public List<ItemRubrica> rubrica = new ArrayList<>();
  /** Nota maxima (10 en la UEM); la nota se redondea a 0,1. */
  @JsonProperty( "scale" )
  public double escala = 10;
  @JsonProperty( "policy" )
  public PoliticaPack politica = new PoliticaPack();
  /** Solo en la variante de un solo .json: ruta relativa -> contenido de texto ("datos/visible/ventas.csv"). */
  @JsonProperty( "inline" )
  public Map<String, String> inline;

  /** Bloque {@code output}: de donde y como se captura la salida, y sus columnas con los tipos de la solucion. */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Salida {

    /** Text file output, leido con su separador, encierro y encoding (§3 paso 5). */
    public static final String CAPTURA_ARCHIVO = "file";
    /** RowListener sobre el step de salida. */
    public static final String CAPTURA_STEP = "step";
    /** SQL del pack sobre la copia de la base SQLite del pack. */
    public static final String CAPTURA_QUERY = "query";
    public static final List<String> CAPTURAS = Collections.unmodifiableList( Arrays.asList( CAPTURA_ARCHIVO,
      CAPTURA_STEP, CAPTURA_QUERY ) );

    @JsonProperty( "capture" )
    public String captura = CAPTURA_ARCHIVO;
    /** Nombre del step de salida en la solucion; null = el de salida que comparta mas columnas (§3 paso 5). */
    @JsonProperty( "step" )
    public String step;
    /** Columnas de la salida de la solucion, con sus tipos; las escribe create_exercise_pack. */
    @JsonProperty( "columns" )
    public List<Columna> columnas = new ArrayList<>();
    /** Como estan escritos los CSV de esperado/. */
    @JsonProperty( "expected_csv" )
    public FormatoCsv csvEsperado = new FormatoCsv();
    /** Solo captura query. */
    @JsonProperty( "database" )
    public BaseDatos base;
    /** Solo captura query: el SELECT sobre la copia de la base. */
    @JsonProperty( "sql" )
    public String sql;

    /** La columna con ese nombre (sin mayusculas, con trim), o null. */
    public Columna columna( String nombre ) {
      if ( nombre == null || columnas == null ) {
        return null;
      }
      String n = nombre.trim();
      for ( Columna c : columnas ) {
        if ( c != null && c.nombre != null && c.nombre.trim().equalsIgnoreCase( n ) ) {
          return c;
        }
      }
      return null;
    }

    public List<String> nombresColumnas() {
      List<String> l = new ArrayList<>();
      if ( columnas != null ) {
        for ( Columna c : columnas ) {
          l.add( c == null ? null : c.nombre );
        }
      }
      return l;
    }
  }

  /** Una columna de la salida esperada con el tipo Kettle de la solucion (String, Number, Integer, Date...). */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Columna {

    @JsonProperty( "name" )
    public String nombre;
    /** Nombre de tipo de Kettle (ValueMetaFactory): String, Number, Integer, BigNumber, Date, Timestamp, Boolean. */
    @JsonProperty( "type" )
    public String tipo;
    /** Mascara de la solucion en el step de salida ("0.00", "yyyy-MM-dd"); opcional. */
    @JsonProperty( "format" )
    public String formato;

    public Columna() {
    }

    public Columna( String nombre, String tipo ) {
      this.nombre = nombre;
      this.tipo = tipo;
    }

    /** Number, Integer o BigNumber: se compara como numero (§4). */
    public boolean esNumerica() {
      String t = tipo == null ? "" : tipo.trim().toLowerCase( Locale.ROOT );
      return t.equals( "number" ) || t.equals( "integer" ) || t.equals( "bignumber" );
    }

    /** Date o Timestamp. */
    public boolean esFecha() {
      String t = tipo == null ? "" : tipo.trim().toLowerCase( Locale.ROOT );
      return t.equals( "date" ) || t.equals( "timestamp" );
    }
  }

  /** Formato de los CSV de esperado/ (los escribe create_exercise_pack; el docente los puede editar). */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class FormatoCsv {

    @JsonProperty( "separator" )
    public String separador = ";";
    @JsonProperty( "enclosure" )
    public String encierro = "\"";
    @JsonProperty( "header" )
    public boolean encabezado = true;
    @JsonProperty( "encoding" )
    public String encoding = "UTF-8";
  }

  /** Base del pack para la captura query: una plantilla SQLite que se copia a db/ de cada caso. */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class BaseDatos {

    public static final String SQLITE = "SQLITE";

    /** Ruta relativa al pack ("plantilla/dw.sqlite"). */
    @JsonProperty( "template" )
    public String plantilla;
    /** Tipo de conexion de Kettle; en 0.6 solo SQLITE. */
    @JsonProperty( "type" )
    public String tipo = SQLITE;
  }

  /** Pone los valores por defecto donde el JSON traia null (listas, mapas y bloques). Devuelve this. */
  public Pack completar() {
    if ( formato == null ) {
      formato = FORMATO;
    }
    if ( tipo == null ) {
      tipo = TRANSFORMACION;
    }
    if ( entradas == null ) {
      entradas = new ArrayList<>();
    }
    if ( parametros == null ) {
      parametros = new LinkedHashMap<>();
    }
    if ( casos == null ) {
      casos = new ArrayList<>();
    }
    if ( salida == null ) {
      salida = new Salida();
    }
    if ( salida.captura == null ) {
      salida.captura = Salida.CAPTURA_ARCHIVO;
    }
    if ( salida.columnas == null ) {
      salida.columnas = new ArrayList<>();
    }
    if ( salida.csvEsperado == null ) {
      salida.csvEsperado = new FormatoCsv();
    }
    if ( comparar == null ) {
      comparar = new OpcionesComparar();
    }
    if ( comparar.columnasClave == null ) {
      comparar.columnasClave = new ArrayList<>();
    }
    if ( comparar.formatosFecha == null ) {
      comparar.formatosFecha = new ArrayList<>();
    }
    if ( comparar.columnasDeMas == null ) {
      comparar.columnasDeMas = OpcionesComparar.EXTRA_AVISAR;
    }
    if ( reglas == null ) {
      reglas = new ArrayList<>();
    }
    for ( Regla r : reglas ) {
      if ( r != null && r.steps == null ) {
        r.steps = new ArrayList<>();
      }
    }
    if ( rubrica == null ) {
      rubrica = new ArrayList<>();
    }
    for ( ItemRubrica i : rubrica ) {
      if ( i != null && i.requiere == null ) {
        i.requiere = new ArrayList<>();
      }
    }
    if ( politica == null ) {
      politica = new PoliticaPack();
    }
    if ( politica.permitirSteps == null ) {
      politica.permitirSteps = new ArrayList<>();
    }
    if ( politica.stepsDesconocidos == null ) {
      politica.stepsDesconocidos = PoliticaPack.BLOQUEAR;
    }
    return this;
  }

  public boolean esJob() {
    return JOB.equals( tipo );
  }

  /** El caso con ese id, o null. */
  public Caso caso( String idCaso ) {
    if ( casos != null && idCaso != null ) {
      for ( Caso c : casos ) {
        if ( c != null && idCaso.equals( c.id ) ) {
          return c;
        }
      }
    }
    return null;
  }

  public List<Caso> casosVisibles() {
    List<Caso> l = new ArrayList<>();
    if ( casos != null ) {
      for ( Caso c : casos ) {
        if ( c != null && !c.oculto ) {
          l.add( c );
        }
      }
    }
    return l;
  }

  /** La regla con ese id, o null. */
  public Regla regla( String idRegla ) {
    if ( reglas != null && idRegla != null ) {
      for ( Regla r : reglas ) {
        if ( r != null && idRegla.equals( r.id ) ) {
          return r;
        }
      }
    }
    return null;
  }

  /** El renglon de la rubrica con ese check, o null. */
  public ItemRubrica itemRubrica( String check ) {
    if ( rubrica != null && check != null ) {
      for ( ItemRubrica i : rubrica ) {
        if ( i != null && check.equals( i.check ) ) {
          return i;
        }
      }
    }
    return null;
  }

  /** Suma de los puntos de la rubrica (la nota es ganado / total x escala). */
  public double puntosTotales() {
    double t = 0;
    if ( rubrica != null ) {
      for ( ItemRubrica i : rubrica ) {
        if ( i != null ) {
          t += i.puntos;
        }
      }
    }
    return t;
  }

  /**
   * El pack.json de la copia para alumnos (§2.1): sin los casos ocultos ni {@code solution}, y en la variante inline
   * sin los archivos de la solucion ni los de los casos ocultos (sus datos y su esperado). Copia profunda: this no
   * cambia. Los archivos de la carpeta los copia CorrectorCrearPack (C6).
   */
  public Pack paraAlumnos() {
    Pack p = CorrectorJson.copia( this, Pack.class );
    List<Caso> ocultos = new ArrayList<>();
    if ( p.casos != null ) {
      for ( Iterator<Caso> it = p.casos.iterator(); it.hasNext(); ) {
        Caso c = it.next();
        if ( c != null && c.oculto ) {
          ocultos.add( c );
          it.remove();
        }
      }
    }
    if ( p.inline != null ) {
      for ( Iterator<String> it = p.inline.keySet().iterator(); it.hasNext(); ) {
        String k = normalizar( it.next() );
        boolean sacar = solucion != null && k.equals( normalizar( solucion ) );
        for ( Caso c : ocultos ) {
          sacar |= c.esperado != null && k.equals( normalizar( c.esperado ) );
          sacar |= c.dirEntradas != null && k.startsWith( normalizar( c.dirEntradas ) + "/" );
        }
        if ( sacar ) {
          it.remove();
        }
      }
    }
    p.solucion = null;
    return p;
  }

  /** Ruta relativa con / y sin ./ ni / al principio o al final, para comparar claves de inline. */
  static String normalizar( String ruta ) {
    String r = ruta == null ? "" : ruta.trim().replace( '\\', '/' );
    while ( r.startsWith( "./" ) ) {
      r = r.substring( 2 );
    }
    while ( r.startsWith( "/" ) ) {
      r = r.substring( 1 );
    }
    while ( r.endsWith( "/" ) ) {
      r = r.substring( 0, r.length() - 1 );
    }
    return r;
  }
}
