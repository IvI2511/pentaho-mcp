package io.github.ivi2511.pentahomcp;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.PoliticaPack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.Catalogo;

/**
 * Abre y valida un pack de ejercicio (docs/corrector-diseno.md §2 y §7): una carpeta con {@code pack.json}, un
 * .zip con lo mismo adentro (en la raiz o dentro de una sola carpeta) o un solo .json con los archivos en
 * {@code inline}. El .zip se descomprime y el .json inline se escribe (con un pack.json sin inline) en una carpeta
 * de trabajo: despues de {@link #abrir} todo pack es una carpeta, {@link #raiz()}, y el resto del corrector no
 * distingue de donde vino. La carpeta del docente no se toca.
 * <p>
 * Valida el esquema (formato, valores permitidos, rubrica contra reglas, columnas clave, locale, zona, charset,
 * mascaras), que cada ruta sea relativa y quede dentro del pack ({@code ..}, rutas absolutas y enlaces que salen:
 * pack invalido) y que los archivos existan. Cada {@link Problema} tiene un codigo estable, donde esta en pack.json
 * ({@code cases[1].expected}) y un texto legible en en/es/pt (Rotulos, area {@code corrector}, claves
 * {@code pack.*}). Los avisos no impiden corregir; los errores si.
 * <p>
 * Los .zip (del pack o de una entrega, {@link #descomprimir}) se revisan enteros antes de escribir nada: ninguna
 * entrada sale de la carpeta destino (zip slip), nombres validos en Windows, sin repetidos ni pisar archivos; y se
 * cuenta lo que realmente se descomprime contra un tope (bombas), no lo que el zip declara. No necesita Kettle.
 *
 * <pre>
 * try ( CorrectorPack p = CorrectorPack.cargar( new File( "ventas-por-producto.zip" ), null ) ) {
 *   for ( Caso c : p.pack().casos ) { File datos = p.dirEntradas( c ); File esperado = p.esperado( c ); ... }
 * } // borra la carpeta temporal del .zip
 * </pre>
 *
 * @dueño C1
 */
public final class CorrectorPack implements Closeable {

  public static final String PACK_JSON = "pack.json";
  /** {@link #forma()}: carpeta con pack.json (o el pack.json mismo, sin inline). */
  public static final String FORMA_CARPETA = "folder";
  public static final String FORMA_ZIP = "zip";
  /** Un solo .json con los archivos en {@code inline}. */
  public static final String FORMA_JSON = "json";

  /** Tope descomprimido de un .zip, contado en bytes realmente escritos. */
  public static final long TOPE_ZIP_BYTES = 200L * 1024 * 1024;
  public static final int TOPE_ZIP_ENTRADAS = 5000;
  /** Tope de pack.json o del .json inline (el inline es de 2 M caracteres; escapado ocupa mas). */
  public static final long TOPE_JSON_BYTES = 16L * 1024 * 1024;
  /** Area de Rotulos de los textos (claves pack.*). */
  public static final String AREA_TEXTOS = "corrector";

  // ---- codigos de problema (estables: los usan la UI, las tools MCP y las pruebas)
  public static final String NO_EXISTE = "not_found";
  public static final String NO_ES_PACK = "not_a_pack";
  public static final String SIN_PACK_JSON = "no_pack_json";
  public static final String JSON_GRANDE = "pack_too_big";
  public static final String JSON_MALO = "bad_json";
  public static final String TIPO_MALO = "wrong_type";
  public static final String ERROR_LECTURA = "io_error";
  public static final String FORMATO_DESCONOCIDO = "unknown_format";
  public static final String CLAVE_DESCONOCIDA = "unknown_key";
  public static final String FALTA_VALOR = "missing_value";
  public static final String VALOR_MALO = "bad_value";
  public static final String RUTA_ABSOLUTA = "absolute_path";
  public static final String RUTA_FUERA = "path_outside_pack";
  public static final String RUTA_MALA = "bad_path";
  public static final String FALTA_ARCHIVO = "missing_file";
  public static final String FALTA_CARPETA = "missing_folder";
  public static final String FALTA_ENTRADA = "missing_input";
  public static final String ENTRADA_NO_LISTADA = "unlisted_input";
  public static final String ENTRADA_REPETIDA = "duplicate_input";
  public static final String SIN_CASOS = "no_cases";
  public static final String CASO_REPETIDO = "duplicate_case";
  public static final String ID_CASO_MALO = "bad_case_id";
  public static final String SIN_CASO_VISIBLE = "no_visible_case";
  public static final String SIN_COLUMNAS = "no_columns";
  public static final String COLUMNA_REPETIDA = "duplicate_column";
  public static final String TIPO_COLUMNA = "unknown_column_type";
  public static final String CLAVE_SIN_COLUMNA = "unknown_key_column";
  public static final String FORMATO_FECHA = "bad_date_format";
  public static final String LOCALE_MALO = "bad_locale";
  public static final String ZONA_MALA = "bad_timezone";
  public static final String CHARSET_MALO = "bad_encoding";
  public static final String REGLA_REPETIDA = "duplicate_rule";
  public static final String LINT_DESCONOCIDA = "unknown_lint_rule";
  public static final String SIN_RUBRICA = "no_rubric";
  public static final String CHECK_DESCONOCIDO = "unknown_check";
  public static final String REGLA_DESCONOCIDA = "unknown_rule";
  public static final String CHECK_REPETIDO = "duplicate_check";
  public static final String REQUIERE_MALO = "bad_requires";
  public static final String SIN_PUNTOS = "zero_points";
  public static final String REGLA_SIN_USO = "unused_rule";
  public static final String EXTENSION_SOLUCION = "solution_extension";
  public static final String SIN_ENUNCIADO = "no_statement";
  public static final String SIN_TITULO = "no_title";
  public static final String IDIOMA_DESCONOCIDO = "unknown_language";
  public static final String CAMPO_IGNORADO = "ignored_field";
  public static final String ENCABEZADO_ESPERADO = "expected_header";
  public static final String INLINE_GRANDE = "inline_too_big";
  public static final String INLINE_RESERVADO = "inline_reserved";
  public static final String INLINE_REPETIDO = "duplicate_inline";
  public static final String ZIP_SLIP = "zip_slip";
  public static final String ZIP_GRANDE = "zip_too_big";
  public static final String ZIP_MUCHAS = "zip_too_many_entries";
  public static final String ZIP_NOMBRE = "zip_bad_name";
  public static final String ZIP_REPETIDO = "zip_duplicate";
  public static final String ZIP_ROTO = "zip_corrupt";

  /** Todos los codigos: cada uno tiene su texto pack.&lt;codigo&gt; en en/es/pt (lo controla la prueba). */
  public static final List<String> CODIGOS = Collections.unmodifiableList( Arrays.asList( NO_EXISTE, NO_ES_PACK,
    SIN_PACK_JSON, JSON_GRANDE, JSON_MALO, TIPO_MALO, ERROR_LECTURA, FORMATO_DESCONOCIDO, CLAVE_DESCONOCIDA,
    FALTA_VALOR, VALOR_MALO, RUTA_ABSOLUTA, RUTA_FUERA, RUTA_MALA, FALTA_ARCHIVO, FALTA_CARPETA, FALTA_ENTRADA,
    ENTRADA_NO_LISTADA, ENTRADA_REPETIDA, SIN_CASOS, CASO_REPETIDO, ID_CASO_MALO, SIN_CASO_VISIBLE, SIN_COLUMNAS,
    COLUMNA_REPETIDA, TIPO_COLUMNA, CLAVE_SIN_COLUMNA, FORMATO_FECHA, LOCALE_MALO, ZONA_MALA, CHARSET_MALO,
    REGLA_REPETIDA, LINT_DESCONOCIDA, SIN_RUBRICA, CHECK_DESCONOCIDO, REGLA_DESCONOCIDA, CHECK_REPETIDO,
    REQUIERE_MALO, SIN_PUNTOS, REGLA_SIN_USO, EXTENSION_SOLUCION, SIN_ENUNCIADO, SIN_TITULO, IDIOMA_DESCONOCIDO,
    CAMPO_IGNORADO, ENCABEZADO_ESPERADO, INLINE_GRANDE, INLINE_RESERVADO, INLINE_REPETIDO, ZIP_SLIP, ZIP_GRANDE,
    ZIP_MUCHAS, ZIP_NOMBRE, ZIP_REPETIDO, ZIP_ROTO ) );

  /** Tipos de Kettle (ValueMetaFactory) que puede tener una columna de output.columns. */
  public static final List<String> TIPOS_KETTLE = Collections.unmodifiableList( Arrays.asList( "String", "Number",
    "Integer", "BigNumber", "Date", "Timestamp", "Boolean", "Binary", "Serializable", "Internet Address" ) );

  /** Marca de un argumento que se traduce al armar el texto (pack.type.number...). */
  private static final char TRADUCIBLE = '\u0001';
  /** file:/, http://, zip:/ ... (un nombre con : sin / despues, como ventas.csv:flujo, es {@link #RUTA_MALA}). */
  private static final Pattern ESQUEMA = Pattern.compile( "^[A-Za-z][A-Za-z0-9+.\\-]+:/" );
  private static final Pattern ID_CASO = Pattern.compile( "[A-Za-z0-9](?:[A-Za-z0-9._\\-]{0,62}[A-Za-z0-9_\\-])?" );
  private static final Pattern RESERVADO = Pattern.compile( "(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?" );
  private static final Pattern LOCALE = Pattern.compile( "([A-Za-z]{2,3})(?:[_-]([A-Za-z]{2}|[0-9]{3}))?" );
  /** Basura de otros sistemas que no cuenta como archivo del pack ni de la entrega. */
  private static final Set<String> BASURA = new HashSet<>( Arrays.asList( ".ds_store", "thumbs.db", "desktop.ini" ) );
  private static final String MACOSX = "__MACOSX";
  private static final Map<Class<?>, Set<String>> CLAVES = new ConcurrentHashMap<>();

  private final File origen;
  private String forma;
  private File raiz;
  private Path raizReal;
  private Pack pack;
  private ObjectNode arbol;
  /** Carpeta temporal propia (zip o inline sin carpeta de trabajo): close() la borra. */
  private File temporal;
  private final List<Problema> problemas = new ArrayList<>();

  private CorrectorPack( File origen ) {
    this.origen = origen;
  }

  // ------------------------------------------------------------------ abrir

  /** {@link #abrir(File, File)} con una carpeta temporal que {@link #close()} borra. */
  public static CorrectorPack abrir( File origen ) {
    return abrir( origen, null );
  }

  /**
   * Abre y valida el pack. Nunca lanza por problemas del pack: quedan en {@link #problemas()} y, si son fatales
   * (no existe, JSON roto, zip inseguro, formato desconocido), {@link #pack()} es null.
   *
   * @param trabajo carpeta vacia o inexistente donde descomprimir el .zip o escribir el .json inline (queda al
   *                cerrar); null = una temporal que close() borra. Con una carpeta no se usa.
   */
  public static CorrectorPack abrir( File origen, File trabajo ) {
    return abrir( origen, trabajo, TOPE_ZIP_BYTES, TOPE_ZIP_ENTRADAS );
  }

  public static CorrectorPack abrir( File origen, File trabajo, long topeZipBytes, int topeZipEntradas ) {
    if ( trabajo != null && trabajo.exists() && !vacia( trabajo ) ) {
      throw new IllegalArgumentException( "La carpeta de trabajo no esta vacia: " + trabajo );
    }
    CorrectorPack p = new CorrectorPack( origen );
    try {
      p.abrirInterno( trabajo, topeZipBytes, topeZipEntradas );
    } catch ( RuntimeException | Error e ) {
      p.close();
      throw e;
    }
    return p;
  }

  /** Abre el pack y lanza {@link PackInvalido} si tiene algun error (y entonces ya borro su temporal). */
  public static CorrectorPack cargar( File origen, File trabajo ) throws PackInvalido {
    CorrectorPack p = abrir( origen, trabajo );
    if ( !p.valido() ) {
      p.close();
      throw new PackInvalido( origen, p.problemas );
    }
    return p;
  }

  public static CorrectorPack cargar( File origen ) throws PackInvalido {
    return cargar( origen, null );
  }

  /** Solo los problemas (errores y avisos), sin dejar nada en disco: para validate_exercise_pack. */
  public static List<Problema> validar( File origen ) {
    try ( CorrectorPack p = abrir( origen ) ) {
      return p.problemas();
    }
  }

  private void abrirInterno( File trabajo, long topeBytes, int topeEntradas ) {
    String nombre = origen == null ? "" : origen.getName();
    if ( origen == null || !origen.exists() ) {
      error( nombre, NO_EXISTE, origen == null ? "" : origen.getPath() );
      return;
    }
    File json;
    if ( origen.isDirectory() ) {
      forma = FORMA_CARPETA;
      raiz = buscarRaiz( origen );
      if ( raiz == null ) {
        error( nombre, SIN_PACK_JSON );
        return;
      }
      json = new File( raiz, PACK_JSON );
    } else if ( esZip( origen ) ) {
      forma = FORMA_ZIP;
      File destino = carpetaTrabajo( trabajo );
      if ( destino == null ) {
        return;
      }
      try {
        descomprimir( origen, destino, topeBytes, topeEntradas );
      } catch ( ZipRechazado z ) {
        agregar( Problema.ERROR, nombre, z.codigo, "pack." + z.codigo, (Object[]) z.args );
        return;
      } catch ( IOException e ) {
        error( nombre, ZIP_ROTO, mensaje( e ) );
        return;
      }
      raiz = buscarRaiz( destino );
      if ( raiz == null ) {
        error( nombre, SIN_PACK_JSON );
        return;
      }
      json = new File( raiz, PACK_JSON );
    } else if ( nombre.toLowerCase( Locale.ROOT ).endsWith( ".json" ) ) {
      forma = FORMA_JSON;
      json = origen;
    } else {
      error( nombre, NO_ES_PACK, nombre );
      return;
    }

    byte[] bytes = leerJson( json );
    if ( bytes == null || ( pack = leerPack( bytes, json.getName() ) ) == null ) {
      return;
    }
    JsonNode formato = arbol.get( "format" );
    if ( formato == null || formato.isNull() ) {
      error( "format", FALTA_VALOR );
      pack = null;
      return;
    }
    if ( !Pack.FORMATO.equals( pack.formato ) ) {
      error( "format", FORMATO_DESCONOCIDO, pack.formato, Pack.FORMATO );
      pack = null;
      return;
    }
    pack.completar();
    clavesDesconocidas( arbol, Pack.class, "" );

    if ( FORMA_JSON.equals( forma ) ) {
      if ( pack.inline != null ) {
        materializarInline( trabajo );
      } else {
        forma = FORMA_CARPETA; // el pack.json de una carpeta: las rutas son relativas a ella
        raiz = json.getAbsoluteFile().getParentFile();
      }
    } else if ( pack.inline != null ) {
      avisoV( "inline", CAMPO_IGNORADO, "inline" );
    }
    if ( raiz != null ) {
      try {
        raizReal = raiz.toPath().toRealPath();
      } catch ( IOException e ) {
        error( nombre, ERROR_LECTURA, mensaje( e ) );
        raiz = null;
      }
    }
    validarEsquema();
  }

  /** La carpeta con pack.json: dir, o su unica subcarpeta (sin contar __MACOSX) si dir no lo tiene. */
  static File buscarRaiz( File dir ) {
    if ( new File( dir, PACK_JSON ).isFile() ) {
      return dir;
    }
    File[] hijos = dir.listFiles();
    File unica = null;
    if ( hijos != null ) {
      for ( File h : hijos ) {
        if ( h.isDirectory() && !h.getName().equals( MACOSX ) ) {
          if ( unica != null ) {
            return null;
          }
          unica = h;
        }
      }
    }
    return unica != null && new File( unica, PACK_JSON ).isFile() ? unica : null;
  }

  /** .zip por extension o por la firma PK\3\4. */
  public static boolean esZip( File f ) {
    if ( f == null || !f.isFile() ) {
      return false;
    }
    if ( f.getName().toLowerCase( Locale.ROOT ).endsWith( ".zip" ) ) {
      return true;
    }
    try ( InputStream in = new FileInputStream( f ) ) {
      byte[] b = new byte[ 4 ];
      return in.read( b ) == 4 && b[ 0 ] == 'P' && b[ 1 ] == 'K' && b[ 2 ] == 3 && b[ 3 ] == 4;
    } catch ( IOException e ) {
      return false;
    }
  }

  private File carpetaTrabajo( File trabajo ) {
    try {
      if ( trabajo == null ) {
        temporal = Files.createTempDirectory( "pentaho-live-pack-" ).toFile();
        return temporal;
      }
      if ( !trabajo.isDirectory() && !trabajo.mkdirs() && !trabajo.isDirectory() ) {
        throw new IOException( "No se pudo crear la carpeta " + trabajo );
      }
      return trabajo;
    } catch ( IOException e ) {
      error( origen.getName(), ERROR_LECTURA, mensaje( e ) );
      return null;
    }
  }

  // ------------------------------------------------------------------ JSON

  /** Los bytes del JSON sin BOM, o null con el problema anotado. */
  private byte[] leerJson( File f ) {
    if ( f.length() > TOPE_JSON_BYTES ) {
      error( f.getName(), JSON_GRANDE, mb( f.length() ), mb( TOPE_JSON_BYTES ) );
      return null;
    }
    try {
      byte[] b = Files.readAllBytes( f.toPath() );
      if ( b.length >= 3 && ( b[ 0 ] & 0xFF ) == 0xEF && ( b[ 1 ] & 0xFF ) == 0xBB && ( b[ 2 ] & 0xFF ) == 0xBF ) {
        b = Arrays.copyOfRange( b, 3, b.length );
      }
      return b;
    } catch ( IOException e ) {
      error( f.getName(), ERROR_LECTURA, mensaje( e ) );
      return null;
    }
  }

  private Pack leerPack( byte[] b, String nombre ) {
    ObjectMapper m = CorrectorJson.mapper();
    JsonNode n;
    try {
      n = m.readTree( b );
    } catch ( JsonProcessingException e ) {
      JsonLocation l = e.getLocation();
      error( nombre, JSON_MALO, l == null ? "?" : l.getLineNr(), l == null ? "?" : l.getColumnNr(),
        primeraLinea( e.getOriginalMessage() ) );
      return null;
    } catch ( IOException e ) {
      error( nombre, ERROR_LECTURA, mensaje( e ) );
      return null;
    }
    if ( n == null || n.isMissingNode() || n.isNull() ) {
      errorV( nombre, JSON_MALO, "empty" );
      return null;
    }
    if ( !n.isObject() ) {
      errorV( nombre, JSON_MALO, "root" );
      return null;
    }
    arbol = (ObjectNode) n;
    try {
      return m.readValue( b, Pack.class );
    } catch ( JsonMappingException e ) {
      tipoMalo( nombre, e );
      return null;
    } catch ( IOException e ) {
      error( nombre, ERROR_LECTURA, mensaje( e ) );
      return null;
    }
  }

  /** Un valor del tipo equivocado ("weight": "uno", "cases": {}): donde, que se esperaba y en que linea. */
  private void tipoMalo( String nombre, JsonMappingException e ) {
    StringBuilder donde = new StringBuilder();
    for ( JsonMappingException.Reference r : e.getPath() ) {
      if ( r.getFieldName() != null ) {
        donde.append( donde.length() == 0 ? "" : "." ).append( r.getFieldName() );
      } else if ( r.getIndex() >= 0 ) {
        donde.append( '[' ).append( r.getIndex() ).append( ']' );
      }
    }
    String d = donde.length() == 0 ? nombre : donde.toString();
    JsonLocation l = e.getLocation();
    Object lin = l == null ? "?" : l.getLineNr();
    Object col = l == null ? "?" : l.getColumnNr();
    if ( e instanceof MismatchedInputException && ( (MismatchedInputException) e ).getTargetType() != null ) {
      String esperado = traducible( "type." + clase( ( (MismatchedInputException) e ).getTargetType() ) );
      if ( e instanceof InvalidFormatException && ( (InvalidFormatException) e ).getValue() != null ) {
        errorV( d, TIPO_MALO, "value", ( (InvalidFormatException) e ).getValue(), esperado, lin, col );
      } else {
        error( d, TIPO_MALO, esperado, lin, col );
      }
    } else {
      errorV( d, TIPO_MALO, "other", primeraLinea( e.getOriginalMessage() ), lin, col );
    }
  }

  private static String clase( Class<?> c ) {
    if ( c == boolean.class || c == Boolean.class ) {
      return "boolean";
    }
    if ( c.isPrimitive() || Number.class.isAssignableFrom( c ) ) {
      return "number";
    }
    if ( CharSequence.class.isAssignableFrom( c ) ) {
      return "text";
    }
    if ( Collection.class.isAssignableFrom( c ) || c.isArray() ) {
      return "list";
    }
    return "object";
  }

  /** Claves que no son del formato: avisos, con la parecida si la hay ("hiden" -> hidden). */
  private void clavesDesconocidas( JsonNode n, Class<?> tipo, String donde ) {
    if ( n == null || !n.isObject() ) {
      return;
    }
    Set<String> validas = claves( tipo );
    for ( Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String k = e.getKey();
      String aqui = donde.isEmpty() ? k : donde + "." + k;
      if ( !validas.contains( k ) ) {
        String s = parecida( k, validas );
        if ( s == null ) {
          aviso( aqui, CLAVE_DESCONOCIDA, k );
        } else {
          avisoV( aqui, CLAVE_DESCONOCIDA, "suggest", k, s );
        }
        continue;
      }
      Class<?> hijo = hijo( tipo, k );
      if ( hijo == null ) {
        continue;
      }
      if ( e.getValue().isArray() ) {
        for ( int i = 0; i < e.getValue().size(); i++ ) {
          clavesDesconocidas( e.getValue().get( i ), hijo, aqui + "[" + i + "]" );
        }
      } else {
        clavesDesconocidas( e.getValue(), hijo, aqui );
      }
    }
  }

  /** El POJO de un bloque anidado (parameters e inline son mapas libres). */
  private static Class<?> hijo( Class<?> tipo, String clave ) {
    if ( tipo == Pack.class ) {
      switch ( clave ) {
        case "cases":
          return Caso.class;
        case "output":
          return Pack.Salida.class;
        case "compare":
          return OpcionesComparar.class;
        case "rules":
          return Regla.class;
        case "rubric":
          return ItemRubrica.class;
        case "policy":
          return PoliticaPack.class;
        default:
          return null;
      }
    }
    if ( tipo == Pack.Salida.class ) {
      switch ( clave ) {
        case "columns":
          return Pack.Columna.class;
        case "expected_csv":
          return Pack.FormatoCsv.class;
        case "database":
          return Pack.BaseDatos.class;
        default:
          return null;
      }
    }
    return null;
  }

  /** Las claves JSON de un POJO del modelo, leidas de sus @JsonProperty. */
  private static Set<String> claves( Class<?> c ) {
    return CLAVES.computeIfAbsent( c, k -> {
      ObjectMapper m = CorrectorJson.mapper();
      BeanDescription d = m.getDeserializationConfig().introspect( m.constructType( k ) );
      Set<String> s = new LinkedHashSet<>();
      for ( BeanPropertyDefinition p : d.findProperties() ) {
        if ( p.hasField() || p.hasSetter() ) {
          s.add( p.getName() );
        }
      }
      return s;
    } );
  }

  /** La clave valida mas parecida (distancia de edicion chica, o igual sin mayusculas ni guiones), o null. */
  static String parecida( String clave, Collection<String> validas ) {
    String k = clave.toLowerCase( Locale.ROOT ).replace( '-', '_' );
    String mejor = null;
    int dm = Integer.MAX_VALUE;
    for ( String v : validas ) {
      String vv = v.toLowerCase( Locale.ROOT );
      int d = k.replace( "_", "" ).equals( vv.replace( "_", "" ) ) ? 0 : distancia( k, vv );
      if ( d < dm ) {
        dm = d;
        mejor = v;
      }
    }
    int tope = Math.max( 1, Math.min( 2, k.length() / 3 ) );
    return dm <= tope ? mejor : null;
  }

  private static int distancia( String a, String b ) {
    int[] prev = new int[ b.length() + 1 ];
    int[] cur = new int[ b.length() + 1 ];
    for ( int j = 0; j <= b.length(); j++ ) {
      prev[ j ] = j;
    }
    for ( int i = 1; i <= a.length(); i++ ) {
      cur[ 0 ] = i;
      for ( int j = 1; j <= b.length(); j++ ) {
        int c = a.charAt( i - 1 ) == b.charAt( j - 1 ) ? 0 : 1;
        cur[ j ] = Math.min( Math.min( cur[ j - 1 ] + 1, prev[ j ] + 1 ), prev[ j - 1 ] + c );
      }
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return prev[ b.length() ];
  }

  // ------------------------------------------------------------------ inline

  /**
   * Escribe los archivos de {@code inline} y un pack.json sin inline en la carpeta de trabajo. Primero revisa
   * todas las claves: si alguna es invalida no escribe nada (ni dentro ni fuera) y la raiz queda null.
   */
  private void materializarInline( File trabajo ) {
    Map<String, String> archivos = new LinkedHashMap<>();
    Set<String> vistas = new HashSet<>();
    long total = 0;
    boolean mal = false;
    for ( Map.Entry<String, String> e : pack.inline.entrySet() ) {
      String k = e.getKey();
      String donde = "inline[\"" + k + "\"]";
      String motivo = motivoRuta( k );
      String n = normalizarRuta( k );
      if ( motivo == null && n.isEmpty() ) {
        motivo = RUTA_MALA;
      }
      if ( motivo != null ) {
        error( donde, motivo, k );
        mal = true;
      } else if ( n.equalsIgnoreCase( PACK_JSON ) ) {
        error( donde, INLINE_RESERVADO, k );
        mal = true;
      } else if ( !vistas.add( n.toLowerCase( Locale.ROOT ) ) ) {
        error( donde, INLINE_REPETIDO, k );
        mal = true;
      } else if ( e.getValue() == null ) {
        error( donde, FALTA_VALOR );
        mal = true;
      } else {
        total += e.getValue().length();
        archivos.put( n, e.getValue() );
      }
    }
    if ( total > Pack.TOPE_INLINE ) {
      error( "inline", INLINE_GRANDE, mb( total ), mb( Pack.TOPE_INLINE ) );
      mal = true;
    }
    if ( mal ) {
      return;
    }
    File destino = carpetaTrabajo( trabajo );
    if ( destino == null ) {
      return;
    }
    Set<String> esperados = new HashSet<>();
    for ( Caso c : pack.casos ) {
      if ( c != null && c.esperado != null ) {
        esperados.add( normalizarRuta( c.esperado ).toLowerCase( Locale.ROOT ) );
      }
    }
    Charset csv = charset( pack.salida.csvEsperado.encoding, StandardCharsets.UTF_8 );
    try {
      for ( Map.Entry<String, String> e : archivos.entrySet() ) {
        File f = new File( destino, e.getKey().replace( '/', File.separatorChar ) );
        File padre = f.getParentFile();
        if ( !padre.isDirectory() && !padre.mkdirs() && !padre.isDirectory() ) {
          throw new IOException( "No se pudo crear la carpeta " + padre );
        }
        Charset cs = esperados.contains( e.getKey().toLowerCase( Locale.ROOT ) ) ? csv : StandardCharsets.UTF_8;
        try ( OutputStream os = new FileOutputStream( f ) ) {
          os.write( e.getValue().getBytes( cs ) );
        }
      }
      ObjectNode sin = arbol.deepCopy();
      sin.remove( "inline" );
      CorrectorJson.guardar( sin, new File( destino, PACK_JSON ) );
      raiz = destino;
    } catch ( IOException e ) {
      error( "inline", ERROR_LECTURA, mensaje( e ) );
    }
  }

  // ------------------------------------------------------------------ esquema

  private void validarEsquema() {
    Pack p = pack;
    if ( vacio( p.id ) ) {
      error( "id", FALTA_VALOR );
    }
    if ( vacio( p.titulo ) ) {
      aviso( "title", SIN_TITULO );
    }
    if ( p.idioma != null && !idiomaConocido( p.idioma ) ) {
      aviso( "language", IDIOMA_DESCONOCIDO, p.idioma );
    }
    if ( !Pack.TRANSFORMACION.equals( p.tipo ) && !Pack.JOB.equals( p.tipo ) ) {
      errorV( "kind", VALOR_MALO, "one_of", p.tipo, Pack.TRANSFORMACION + ", " + Pack.JOB );
    }
    if ( vacio( p.enunciado ) ) {
      aviso( "statement", SIN_ENUNCIADO );
    } else {
      rutaDelPack( "statement", p.enunciado, false );
    }
    if ( !vacio( p.solucion ) ) {
      File s = rutaDelPack( "solution", p.solucion, false );
      String ext = p.esJob() ? ".kjb" : ".ktr";
      if ( s != null && !s.getName().toLowerCase( Locale.ROOT ).endsWith( ext ) ) {
        aviso( "solution", EXTENSION_SOLUCION, p.solucion, ext );
      }
    }
    if ( p.archivoEntrega != null ) {
      String motivo = vacio( p.archivoEntrega ) ? RUTA_MALA : motivoRuta( p.archivoEntrega );
      if ( motivo != null ) {
        error( "submission_file", motivo, p.archivoEntrega );
      }
    }
    List<String> entradas = validarEntradas();
    for ( Map.Entry<String, String> e : p.parametros.entrySet() ) {
      if ( vacio( e.getKey() ) ) {
        error( "parameters", FALTA_VALOR );
      }
    }
    validarCasos( entradas );
    validarSalida();
    validarComparar();
    validarReglas();
    validarRubrica();
    if ( !( p.escala > 0 ) || Double.isInfinite( p.escala ) ) {
      errorV( "scale", VALOR_MALO, "positive", num( p.escala ) );
    }
    validarPolitica();
  }

  /** inputs: nombres de archivo sin carpetas ni repetidos; devuelve los validos. */
  private List<String> validarEntradas() {
    List<String> validas = new ArrayList<>();
    Set<String> vistas = new HashSet<>();
    for ( int i = 0; i < pack.entradas.size(); i++ ) {
      String e = pack.entradas.get( i );
      String d = "inputs[" + i + "]";
      if ( vacio( e ) ) {
        error( d, FALTA_VALOR );
      } else if ( e.contains( "/" ) || e.contains( "\\" ) ) {
        errorV( d, VALOR_MALO, "file_name", e );
      } else if ( motivoRuta( e ) != null || normalizarRuta( e ).isEmpty() ) {
        error( d, RUTA_MALA, e );
      } else if ( !vistas.add( e.trim().toLowerCase( Locale.ROOT ) ) ) {
        error( d, ENTRADA_REPETIDA, e );
      } else {
        validas.add( e.trim() );
      }
    }
    return validas;
  }

  private void validarCasos( List<String> entradas ) {
    List<Caso> casos = pack.casos;
    if ( casos.isEmpty() ) {
      error( "cases", SIN_CASOS );
      return;
    }
    Set<String> ids = new HashSet<>();
    double pesos = 0;
    boolean pesoMalo = false;
    boolean visible = false;
    for ( int i = 0; i < casos.size(); i++ ) {
      Caso c = casos.get( i );
      String d = "cases[" + i + "]";
      if ( c == null ) {
        error( d, FALTA_VALOR );
        continue;
      }
      if ( vacio( c.id ) ) {
        error( d + ".id", FALTA_VALOR );
      } else if ( !ID_CASO.matcher( c.id ).matches() || RESERVADO.matcher( c.id ).matches() ) {
        error( d + ".id", ID_CASO_MALO, c.id );
      } else if ( !ids.add( c.id.toLowerCase( Locale.ROOT ) ) ) {
        error( d + ".id", CASO_REPETIDO, c.id );
      }
      visible |= !c.oculto;
      if ( Double.isNaN( c.peso ) || Double.isInfinite( c.peso ) || c.peso < 0 ) {
        errorV( d + ".weight", VALOR_MALO, "min", num( c.peso ), "0" );
        pesoMalo = true;
      } else {
        pesos += c.peso;
      }
      if ( vacio( c.dirEntradas ) ) {
        if ( !entradas.isEmpty() ) {
          error( d + ".inputs_dir", FALTA_VALOR );
        }
      } else {
        File dir = rutaDelPack( d + ".inputs_dir", c.dirEntradas, true );
        if ( dir != null ) {
          revisarEntradas( d + ".inputs_dir", c.dirEntradas, dir, entradas );
        }
      }
      if ( vacio( c.esperado ) ) {
        error( d + ".expected", FALTA_VALOR );
      } else {
        File e = rutaDelPack( d + ".expected", c.esperado, false );
        if ( e != null ) {
          revisarEncabezado( d + ".expected", c.esperado, e );
        }
      }
    }
    if ( !pesoMalo && pesos <= 0 ) {
      errorV( "cases", VALOR_MALO, "weights" );
    }
    if ( !visible ) {
      aviso( "cases", SIN_CASO_VISIBLE );
    }
  }

  /** Cada archivo de inputs esta en la carpeta del caso; los que estan y no se listan, aviso. */
  private void revisarEntradas( String donde, String ruta, File dir, List<String> entradas ) {
    Set<String> listadas = new HashSet<>();
    for ( String e : entradas ) {
      listadas.add( e.toLowerCase( Locale.ROOT ) );
      if ( !new File( dir, e ).isFile() ) {
        error( donde, FALTA_ENTRADA, e, ruta );
      }
    }
    File[] hay = dir.listFiles();
    if ( hay == null ) {
      return;
    }
    Arrays.sort( hay );
    for ( File f : hay ) {
      String n = f.getName().toLowerCase( Locale.ROOT );
      if ( f.isFile() && !listadas.contains( n ) && !BASURA.contains( n ) ) {
        aviso( donde, ENTRADA_NO_LISTADA, ruta, f.getName() );
      }
    }
  }

  /** El encabezado del CSV esperado contra output.columns (sin mayusculas ni orden). Solo aviso. */
  private void revisarEncabezado( String donde, String ruta, File f ) {
    Pack.Salida s = pack.salida;
    Pack.FormatoCsv csv = s.csvEsperado;
    if ( s.columnas.isEmpty() || !csv.encabezado || vacio( csv.separador ) ) {
      return;
    }
    String linea;
    try ( InputStream in = new FileInputStream( f ) ) {
      byte[] b = new byte[ 64 * 1024 ];
      int n = 0;
      int r;
      while ( n < b.length && ( r = in.read( b, n, b.length - n ) ) > 0 ) {
        n += r;
      }
      linea = new String( b, 0, n, charset( csv.encoding, StandardCharsets.UTF_8 ) );
    } catch ( IOException e ) {
      error( donde, ERROR_LECTURA, mensaje( e ) );
      return;
    }
    if ( linea.startsWith( "\uFEFF" ) ) {
      linea = linea.substring( 1 );
    }
    int fin = linea.indexOf( '\n' );
    linea = ( fin < 0 ? linea : linea.substring( 0, fin ) ).replace( "\r", "" );
    String enc = csv.encierro == null ? "" : csv.encierro;
    Set<String> hay = new LinkedHashSet<>();
    for ( String celda : linea.split( Pattern.quote( csv.separador ), -1 ) ) {
      String c = celda.trim();
      if ( !enc.isEmpty() && c.length() >= 2 * enc.length() && c.startsWith( enc ) && c.endsWith( enc ) ) {
        c = c.substring( enc.length(), c.length() - enc.length() ).trim();
      }
      hay.add( c.toLowerCase( Locale.ROOT ) );
    }
    List<String> faltan = new ArrayList<>();
    Set<String> esperadas = new HashSet<>();
    for ( Pack.Columna c : s.columnas ) {
      if ( c != null && c.nombre != null ) {
        String n = c.nombre.trim().toLowerCase( Locale.ROOT );
        esperadas.add( n );
        if ( !hay.contains( n ) ) {
          faltan.add( c.nombre.trim() );
        }
      }
    }
    List<String> sobran = new ArrayList<>();
    for ( String h : hay ) {
      if ( !esperadas.contains( h ) ) {
        sobran.add( h );
      }
    }
    if ( !faltan.isEmpty() || !sobran.isEmpty() ) {
      aviso( donde, ENCABEZADO_ESPERADO, ruta, lista( faltan ), lista( sobran ) );
    }
  }

  private void validarSalida() {
    Pack.Salida s = pack.salida;
    if ( !Pack.Salida.CAPTURAS.contains( s.captura ) ) {
      errorV( "output.capture", VALOR_MALO, "one_of", s.captura, String.join( ", ", Pack.Salida.CAPTURAS ) );
    }
    if ( s.columnas.isEmpty() ) {
      aviso( "output.columns", SIN_COLUMNAS );
    }
    Set<String> nombres = new HashSet<>();
    for ( int i = 0; i < s.columnas.size(); i++ ) {
      Pack.Columna c = s.columnas.get( i );
      String d = "output.columns[" + i + "]";
      if ( c == null ) {
        error( d, FALTA_VALOR );
        continue;
      }
      if ( vacio( c.nombre ) ) {
        error( d + ".name", FALTA_VALOR );
      } else if ( !nombres.add( c.nombre.trim().toLowerCase( Locale.ROOT ) ) ) {
        error( d + ".name", COLUMNA_REPETIDA, c.nombre );
      }
      if ( vacio( c.tipo ) ) {
        avisoV( d + ".type", TIPO_COLUMNA, "empty", String.join( ", ", TIPOS_KETTLE ) );
      } else if ( !tipoKettle( c.tipo ) ) {
        aviso( d + ".type", TIPO_COLUMNA, c.tipo, String.join( ", ", TIPOS_KETTLE ) );
      }
      if ( !vacio( c.formato ) ) {
        if ( c.esFecha() ) {
          String m = mascaraFechaMala( c.formato );
          if ( m != null ) {
            error( d + ".format", FORMATO_FECHA, c.formato, m );
          }
        } else if ( c.esNumerica() ) {
          try {
            new DecimalFormat( c.formato );
          } catch ( IllegalArgumentException e ) {
            errorV( d + ".format", VALOR_MALO, "number_format", c.formato, mensaje( e ) );
          }
        }
      }
    }
    Pack.FormatoCsv csv = s.csvEsperado;
    if ( vacio( csv.separador ) ) {
      error( "output.expected_csv.separator", FALTA_VALOR );
    }
    if ( !vacio( csv.encoding ) && charset( csv.encoding, null ) == null ) {
      error( "output.expected_csv.encoding", CHARSET_MALO, csv.encoding );
    }
    if ( Pack.Salida.CAPTURA_QUERY.equals( s.captura ) ) {
      if ( s.base == null ) {
        error( "output.database", FALTA_VALOR );
      } else {
        if ( vacio( s.base.plantilla ) ) {
          error( "output.database.template", FALTA_VALOR );
        } else {
          rutaDelPack( "output.database.template", s.base.plantilla, false );
        }
        if ( !Pack.BaseDatos.SQLITE.equalsIgnoreCase( s.base.tipo == null ? "" : s.base.tipo.trim() ) ) {
          errorV( "output.database.type", VALOR_MALO, "one_of", s.base.tipo, Pack.BaseDatos.SQLITE );
        }
      }
      if ( vacio( s.sql ) ) {
        error( "output.sql", FALTA_VALOR );
      }
    } else if ( s.base != null || !vacio( s.sql ) ) {
      avisoV( s.base != null ? "output.database" : "output.sql", CAMPO_IGNORADO, "database", s.captura );
    }
  }

  private void validarComparar() {
    OpcionesComparar c = pack.comparar;
    Pack.Salida s = pack.salida;
    Set<String> vistas = new HashSet<>();
    for ( int i = 0; i < c.columnasClave.size(); i++ ) {
      String k = c.columnasClave.get( i );
      String d = "compare.key_columns[" + i + "]";
      if ( vacio( k ) ) {
        error( d, FALTA_VALOR );
      } else if ( !vistas.add( k.trim().toLowerCase( Locale.ROOT ) ) ) {
        error( d, COLUMNA_REPETIDA, k );
      } else if ( !s.columnas.isEmpty() && s.columna( k ) == null ) {
        error( d, CLAVE_SIN_COLUMNA, k, lista( s.nombresColumnas() ) );
      }
    }
    if ( Double.isNaN( c.toleranciaNumerica ) || Double.isInfinite( c.toleranciaNumerica )
      || c.toleranciaNumerica < 0 ) {
      errorV( "compare.numeric_tolerance", VALOR_MALO, "min", num( c.toleranciaNumerica ), "0" );
    }
    if ( Double.isNaN( c.toleranciaRelativa ) || Double.isInfinite( c.toleranciaRelativa )
      || c.toleranciaRelativa < 0 ) {
      errorV( "compare.relative_tolerance", VALOR_MALO, "min", num( c.toleranciaRelativa ), "0" );
    }
    for ( int i = 0; i < c.formatosFecha.size(); i++ ) {
      String f = c.formatosFecha.get( i );
      String d = "compare.date_formats[" + i + "]";
      if ( vacio( f ) ) {
        error( d, FALTA_VALOR );
      } else {
        String m = mascaraFechaMala( f );
        if ( m != null ) {
          error( d, FORMATO_FECHA, f, m );
        }
      }
    }
    if ( !OpcionesComparar.EXTRAS.contains( c.columnasDeMas ) ) {
      errorV( "compare.extra_columns", VALOR_MALO, "one_of", c.columnasDeMas,
        String.join( ", ", OpcionesComparar.EXTRAS ) );
    }
    if ( c.maxEjemplos < 0 ) {
      errorV( "compare.max_examples", VALOR_MALO, "min", c.maxEjemplos, "0" );
    }
  }

  private void validarReglas() {
    Set<String> ids = new HashSet<>();
    for ( int i = 0; i < pack.reglas.size(); i++ ) {
      Regla r = pack.reglas.get( i );
      String d = "rules[" + i + "]";
      if ( r == null ) {
        error( d, FALTA_VALOR );
        continue;
      }
      if ( vacio( r.id ) ) {
        error( d + ".id", FALTA_VALOR );
      } else if ( !ids.add( r.id ) ) {
        error( d + ".id", REGLA_REPETIDA, r.id );
      }
      if ( vacio( r.tipo ) ) {
        error( d + ".type", FALTA_VALOR );
        continue;
      }
      if ( !Regla.tipoValido( r.tipo ) ) {
        errorV( d + ".type", VALOR_MALO, "one_of", r.tipo, String.join( ", ", Regla.TIPOS ) );
        continue;
      }
      switch ( r.tipo ) {
        case Regla.MAX_STEPS:
          if ( r.valor == null ) {
            error( d + ".value", FALTA_VALOR );
          } else if ( r.valor < 1 ) {
            errorV( d + ".value", VALOR_MALO, "min", r.valor, "1" );
          }
          break;
        case Regla.LINT:
          if ( vacio( r.reglaLint ) ) {
            error( d + ".rule", FALTA_VALOR );
          } else if ( !lintExiste( r.reglaLint ) ) {
            error( d + ".rule", LINT_DESCONOCIDA, r.reglaLint, idsLint() );
          }
          break;
        default: // must_use, must_not_use, error_handling: tipos de step
          if ( r.steps.isEmpty() ) {
            error( d + ".steps", FALTA_VALOR );
          }
          for ( int j = 0; j < r.steps.size(); j++ ) {
            if ( vacio( r.steps.get( j ) ) ) {
              error( d + ".steps[" + j + "]", FALTA_VALOR );
            }
          }
      }
    }
  }

  private void validarRubrica() {
    List<ItemRubrica> rubrica = pack.rubrica;
    if ( rubrica.isEmpty() ) {
      error( "rubric", SIN_RUBRICA );
    }
    Set<String> checks = new HashSet<>();
    Set<String> reglasUsadas = new HashSet<>();
    Map<String, List<String>> grafo = new LinkedHashMap<>();
    double total = 0;
    boolean puntosMalos = false;
    for ( int i = 0; i < rubrica.size(); i++ ) {
      ItemRubrica it = rubrica.get( i );
      String d = "rubric[" + i + "]";
      if ( it == null ) {
        error( d, FALTA_VALOR );
        continue;
      }
      boolean bueno = revisarCheck( d + ".check", it.check, reglasUsadas );
      if ( bueno && !checks.add( it.check ) ) {
        error( d + ".check", CHECK_REPETIDO, it.check );
      }
      if ( Double.isNaN( it.puntos ) || Double.isInfinite( it.puntos ) || it.puntos < 0 ) {
        errorV( d + ".points", VALOR_MALO, "min", num( it.puntos ), "0" );
        puntosMalos = true;
      } else {
        total += it.puntos;
        if ( it.puntos == 0 && bueno ) {
          aviso( d + ".points", SIN_PUNTOS, it.check );
        }
      }
      for ( int j = 0; j < it.requiere.size(); j++ ) {
        String rq = it.requiere.get( j );
        String dj = d + ".requires[" + j + "]";
        if ( !revisarCheck( dj, rq, reglasUsadas ) ) {
          continue;
        }
        if ( rq.equals( it.check ) ) {
          errorV( dj, REQUIERE_MALO, "self", rq );
        } else if ( bueno ) {
          List<String> l = grafo.get( it.check );
          if ( l == null ) {
            grafo.put( it.check, l = new ArrayList<>() );
          }
          l.add( rq );
        }
      }
    }
    if ( !rubrica.isEmpty() && !puntosMalos && total <= 0 ) {
      errorV( "rubric", VALOR_MALO, "total_points", num( total ) );
    }
    List<String> ciclo = ciclo( grafo );
    if ( ciclo != null ) {
      errorV( "rubric", REQUIERE_MALO, "cycle", String.join( " -> ", ciclo ) );
    }
    for ( int i = 0; i < pack.reglas.size(); i++ ) {
      Regla r = pack.reglas.get( i );
      if ( r != null && !vacio( r.id ) && !reglasUsadas.contains( r.id ) ) {
        aviso( "rules[" + i + "]", REGLA_SIN_USO, r.id );
      }
    }
  }

  /** Un check de la rubrica o de requires: valido, y si es rule:/lint:, que exista. */
  private boolean revisarCheck( String donde, String check, Set<String> reglasUsadas ) {
    if ( vacio( check ) ) {
      error( donde, FALTA_VALOR );
      return false;
    }
    if ( !ItemRubrica.checkValido( check ) ) {
      error( donde, CHECK_DESCONOCIDO, check );
      return false;
    }
    ItemRubrica i = new ItemRubrica( check, 0 );
    if ( i.esRegla() ) {
      reglasUsadas.add( i.idRegla() );
      if ( pack.regla( i.idRegla() ) == null ) {
        error( donde, REGLA_DESCONOCIDA, i.idRegla() );
        return false;
      }
    } else if ( i.esLint() && !lintExiste( i.idLint() ) ) {
      error( donde, LINT_DESCONOCIDA, i.idLint(), idsLint() );
      return false;
    }
    return true;
  }

  /** Un ciclo de requires (a -> b -> a), o null. */
  private static List<String> ciclo( Map<String, List<String>> grafo ) {
    Map<String, Integer> estado = new HashMap<>(); // 1 en curso, 2 visto
    for ( String n : grafo.keySet() ) {
      List<String> camino = new ArrayList<>();
      if ( buscarCiclo( n, grafo, estado, camino ) ) {
        return camino;
      }
    }
    return null;
  }

  private static boolean buscarCiclo( String n, Map<String, List<String>> grafo, Map<String, Integer> estado,
                                      List<String> camino ) {
    Integer e = estado.get( n );
    if ( e != null && e == 2 ) {
      return false;
    }
    if ( e != null && e == 1 ) {
      List<String> c = new ArrayList<>( camino.subList( camino.indexOf( n ), camino.size() ) );
      c.add( n );
      camino.clear();
      camino.addAll( c );
      return true;
    }
    estado.put( n, 1 );
    camino.add( n );
    List<String> sig = grafo.get( n );
    if ( sig != null ) {
      for ( String s : sig ) {
        if ( buscarCiclo( s, grafo, estado, camino ) ) {
          return true;
        }
      }
    }
    camino.remove( camino.size() - 1 );
    estado.put( n, 2 );
    return false;
  }

  private void validarPolitica() {
    PoliticaPack pol = pack.politica;
    if ( !PoliticaPack.BLOQUEAR.equals( pol.stepsDesconocidos ) && !PoliticaPack.PERMITIR.equals(
      pol.stepsDesconocidos ) ) {
      errorV( "policy.unknown_steps", VALOR_MALO, "one_of", pol.stepsDesconocidos, PoliticaPack.BLOQUEAR + ", "
        + PoliticaPack.PERMITIR );
    }
    for ( int i = 0; i < pol.permitirSteps.size(); i++ ) {
      if ( vacio( pol.permitirSteps.get( i ) ) ) {
        error( "policy.allow_steps[" + i + "]", FALTA_VALOR );
      }
    }
    if ( pol.timeoutSeg < 1 ) {
      errorV( "policy.timeout_s", VALOR_MALO, "min", pol.timeoutSeg, "1" );
    }
    if ( pol.maxFilas < 1 ) {
      errorV( "policy.max_rows", VALOR_MALO, "min", pol.maxFilas, "1" );
    }
    if ( pol.maxSalidaMb < 1 ) {
      errorV( "policy.max_output_mb", VALOR_MALO, "min", pol.maxSalidaMb, "1" );
    }
    if ( !vacio( pol.locale ) && !localeValido( pol.locale ) ) {
      error( "policy.locale", LOCALE_MALO, pol.locale );
    }
    if ( !vacio( pol.zonaHoraria ) && !zonaValida( pol.zonaHoraria ) ) {
      error( "policy.timezone", ZONA_MALA, pol.zonaHoraria );
    }
    if ( !vacio( pol.encoding ) && charset( pol.encoding, null ) == null ) {
      error( "policy.encoding", CHARSET_MALO, pol.encoding );
    }
  }

  // ------------------------------------------------------------------ rutas

  /**
   * Revisa una ruta de pack.json y devuelve el archivo (o la carpeta) si es valida, existe y queda dentro del pack
   * tambien despues de resolver enlaces y junctions; si no, anota el problema y devuelve null. Sin raiz (inline
   * invalido) solo revisa la forma.
   */
  private File rutaDelPack( String donde, String valor, boolean carpeta ) {
    String motivo = motivoRuta( valor );
    if ( motivo != null ) {
      error( donde, motivo, valor );
      return null;
    }
    if ( raiz == null ) {
      return null;
    }
    String n = normalizarRuta( valor );
    File f = n.isEmpty() ? raiz : new File( raiz, n.replace( '/', File.separatorChar ) );
    if ( carpeta ? !f.isDirectory() : !f.isFile() ) {
      String codigo = carpeta ? FALTA_CARPETA : FALTA_ARCHIVO;
      if ( f.exists() ) {
        errorV( donde, codigo, carpeta ? "file" : "folder", valor );
      } else {
        error( donde, codigo, valor );
      }
      return null;
    }
    if ( !dentro( f ) ) {
      errorV( donde, RUTA_FUERA, "link", valor );
      return null;
    }
    return f;
  }

  private boolean dentro( File f ) {
    try {
      return raizReal != null && f.toPath().toRealPath().startsWith( raizReal );
    } catch ( IOException e ) {
      return false;
    }
  }

  /**
   * Por que una ruta de pack.json (o una entrada de zip) no vale, o null si es relativa y queda dentro:
   * {@link #RUTA_ABSOLUTA} (/x, \\srv, C:\x, C:x, file:, http:), {@link #RUTA_FUERA} (algun segmento ..) o
   * {@link #RUTA_MALA} (vacia, caracteres que Windows no acepta, nombres reservados como CON o aux.csv, segmentos
   * que terminan en punto o espacio). Acepta \ y / como separadores.
   */
  public static String motivoRuta( String ruta ) {
    if ( ruta == null ) {
      return RUTA_MALA;
    }
    String r = ruta.trim().replace( '\\', '/' );
    if ( r.isEmpty() ) {
      return RUTA_MALA;
    }
    if ( r.startsWith( "/" ) || ( r.length() >= 2 && r.charAt( 1 ) == ':' && Character.isLetter( r.charAt( 0 ) ) )
      || ESQUEMA.matcher( r ).find() ) {
      return RUTA_ABSOLUTA;
    }
    String[] segmentos = r.split( "/" );
    for ( String s : segmentos ) {
      if ( s.trim().equals( ".." ) ) {
        return RUTA_FUERA;
      }
    }
    for ( String s : segmentos ) {
      if ( !s.isEmpty() && !s.equals( "." ) && !segmentoValido( s ) ) {
        return RUTA_MALA;
      }
    }
    return null;
  }

  private static boolean segmentoValido( String s ) {
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      if ( c < 0x20 || "<>:\"|?*".indexOf( c ) >= 0 ) {
        return false;
      }
    }
    char ultimo = s.charAt( s.length() - 1 );
    return ultimo != '.' && ultimo != ' ' && !RESERVADO.matcher( s ).matches();
  }

  /** "./datos\\visible/" -> "datos/visible": separadores /, sin . ni vacios ni / al principio o al final. */
  public static String normalizarRuta( String ruta ) {
    if ( ruta == null ) {
      return "";
    }
    StringBuilder b = new StringBuilder();
    for ( String s : ruta.trim().replace( '\\', '/' ).split( "/" ) ) {
      if ( !s.isEmpty() && !s.equals( "." ) ) {
        b.append( b.length() == 0 ? "" : "/" ).append( s );
      }
    }
    return b.toString();
  }

  // ------------------------------------------------------------------ zip

  /**
   * Un .zip rechazado: {@link #codigo} es uno de {@link #ZIP_SLIP}, {@link #ZIP_GRANDE}, {@link #ZIP_MUCHAS},
   * {@link #ZIP_NOMBRE}, {@link #ZIP_REPETIDO} o {@link #ZIP_ROTO}; {@link #entrada} la entrada culpable (o null).
   */
  public static final class ZipRechazado extends IOException {

    private static final long serialVersionUID = 1L;

    public final String codigo;
    public final String entrada;
    final String[] args;

    ZipRechazado( String codigo, String entrada, Object... args ) {
      super( codigo + ( entrada == null ? "" : ": " + entrada ) + " " + Arrays.toString( args ) );
      this.codigo = codigo;
      this.entrada = entrada;
      this.args = new String[ args.length ];
      for ( int i = 0; i < args.length; i++ ) {
        this.args[ i ] = String.valueOf( args[ i ] );
      }
    }

    /** El texto legible en en | es | pt. */
    public String texto( String idioma ) {
      return new Problema( Problema.ERROR, "", codigo, "pack." + codigo, (Object[]) args ).texto( idioma );
    }
  }

  /**
   * Descomprime un .zip (pack o entrega, §7) en destino sin salir de el. Primero revisa todas las entradas del
   * directorio central: cantidad, nombres (sin .., absolutas, caracteres o nombres reservados de Windows),
   * repetidas sin mayusculas, que no pisen archivos de destino y que el tamaño declarado no pase el tope. Recien
   * entonces escribe, contando los bytes reales (un zip puede mentir el tamaño). Si algo falla borra lo que escribio
   * y lanza {@link ZipRechazado}. Ignora __MACOSX/ y .DS_Store, Thumbs.db y desktop.ini. Nombres en UTF-8 o, si no
   * son UTF-8 valido, en CP437 (el «Enviar a carpeta comprimida» de Windows viejo).
   */
  public static void descomprimir( File zip, File destino, long topeBytes, int topeEntradas ) throws IOException {
    List<ZipEntry> entradas = new ArrayList<>();
    ZipFile zf = abrirZip( zip, entradas, topeEntradas );
    File dest = destino.getAbsoluteFile();
    boolean existia = dest.isDirectory();
    Set<String> previos = new HashSet<>();
    String[] hay = existia ? dest.list() : null;
    if ( hay != null ) {
      previos.addAll( Arrays.asList( hay ) );
    }
    boolean ok = false;
    try {
      Path base = dest.toPath().normalize();
      Map<ZipEntry, File> plan = new LinkedHashMap<>();
      Set<String> archivos = new HashSet<>();
      Set<String> carpetas = new HashSet<>();
      long declarado = 0;
      for ( ZipEntry e : entradas ) {
        String crudo = e.getName();
        String n = crudo.replace( '\\', '/' );
        if ( ignorarEnZip( n ) ) {
          continue;
        }
        String motivo = motivoRuta( n );
        if ( RUTA_ABSOLUTA.equals( motivo ) || RUTA_FUERA.equals( motivo ) ) {
          throw new ZipRechazado( ZIP_SLIP, crudo, crudo );
        }
        String norm = normalizarRuta( n );
        if ( norm.isEmpty() ) {
          continue;
        }
        if ( motivo != null ) {
          throw new ZipRechazado( ZIP_NOMBRE, crudo, crudo );
        }
        File f = new File( dest, norm.replace( '/', File.separatorChar ) );
        if ( !f.toPath().normalize().startsWith( base ) || !f.getCanonicalFile().toPath().startsWith(
          dest.getCanonicalFile().toPath() ) ) {
          throw new ZipRechazado( ZIP_SLIP, crudo, crudo );
        }
        String clave = norm.toLowerCase( Locale.ROOT );
        boolean dir = e.isDirectory() || n.endsWith( "/" );
        if ( dir ) {
          if ( archivos.contains( clave ) ) {
            throw new ZipRechazado( ZIP_REPETIDO, crudo, crudo );
          }
          carpetas.add( clave );
        } else {
          if ( !archivos.add( clave ) || carpetas.contains( clave ) || f.exists() ) {
            throw new ZipRechazado( ZIP_REPETIDO, crudo, crudo );
          }
          if ( e.getSize() > 0 ) {
            declarado += e.getSize();
          }
        }
        // las carpetas de los padres tampoco pueden ser archivos del zip
        for ( int i = clave.indexOf( '/' ); i > 0; i = clave.indexOf( '/', i + 1 ) ) {
          carpetas.add( clave.substring( 0, i ) );
        }
        plan.put( e, f );
      }
      for ( String c : carpetas ) {
        if ( archivos.contains( c ) ) {
          throw new ZipRechazado( ZIP_REPETIDO, c, c );
        }
      }
      if ( declarado > topeBytes ) {
        throw new ZipRechazado( ZIP_GRANDE, null, mb( topeBytes ) );
      }
      if ( !dest.isDirectory() && !dest.mkdirs() && !dest.isDirectory() ) {
        throw new IOException( "No se pudo crear la carpeta " + dest );
      }
      long total = 0;
      byte[] buf = new byte[ 64 * 1024 ];
      for ( Map.Entry<ZipEntry, File> p : plan.entrySet() ) {
        File f = p.getValue();
        if ( p.getKey().isDirectory() || p.getKey().getName().replace( '\\', '/' ).endsWith( "/" ) ) {
          if ( !f.isDirectory() && !f.mkdirs() && !f.isDirectory() ) {
            throw new IOException( "No se pudo crear la carpeta " + f );
          }
          continue;
        }
        File padre = f.getParentFile();
        if ( !padre.isDirectory() && !padre.mkdirs() && !padre.isDirectory() ) {
          throw new IOException( "No se pudo crear la carpeta " + padre );
        }
        try ( InputStream in = zf.getInputStream( p.getKey() ); OutputStream out = new FileOutputStream( f ) ) {
          int r;
          while ( ( r = in.read( buf ) ) > 0 ) {
            total += r;
            if ( total > topeBytes ) {
              throw new ZipRechazado( ZIP_GRANDE, p.getKey().getName(), mb( topeBytes ) );
            }
            out.write( buf, 0, r );
          }
        }
      }
      ok = true;
    } catch ( ZipRechazado e ) {
      throw e;
    } catch ( IOException | RuntimeException e ) {
      throw new ZipRechazado( ZIP_ROTO, null, mensaje( e ) );
    } finally {
      cerrar( zf );
      if ( !ok ) {
        deshacer( dest, existia, previos );
      }
    }
  }

  /** Abre el zip probando UTF-8 y despues CP437, y lee la lista de entradas (sin pasar el tope). */
  private static ZipFile abrirZip( File zip, List<ZipEntry> entradas, int topeEntradas ) throws ZipRechazado {
    List<Charset> charsets = new ArrayList<>();
    charsets.add( StandardCharsets.UTF_8 );
    Charset cp437 = charset( "IBM437", null );
    if ( cp437 != null ) {
      charsets.add( cp437 );
    }
    Exception primera = null;
    for ( Charset cs : charsets ) {
      ZipFile zf = null;
      try {
        zf = new ZipFile( zip, cs );
        if ( zf.size() > topeEntradas ) {
          int n = zf.size();
          cerrar( zf );
          throw new ZipRechazado( ZIP_MUCHAS, null, n, topeEntradas );
        }
        entradas.clear();
        for ( Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
          entradas.add( en.nextElement() );
        }
        return zf;
      } catch ( ZipRechazado e ) {
        throw e;
      } catch ( IOException | IllegalArgumentException e ) {
        cerrar( zf );
        if ( primera == null ) {
          primera = e;
        }
      }
    }
    throw new ZipRechazado( ZIP_ROTO, null, mensaje( primera ) );
  }

  private static boolean ignorarEnZip( String nombre ) {
    if ( nombre.startsWith( MACOSX + "/" ) || nombre.equals( MACOSX ) ) {
      return true;
    }
    int i = nombre.lastIndexOf( '/', nombre.length() - 2 );
    String ultimo = nombre.substring( i + 1 ).toLowerCase( Locale.ROOT );
    return BASURA.contains( ultimo );
  }

  /** Borra lo que se escribio en destino (todo si no existia; si existia, lo que no estaba antes). */
  private static void deshacer( File dest, boolean existia, Set<String> previos ) {
    if ( !existia ) {
      borrar( dest );
      return;
    }
    String[] hay = dest.list();
    if ( hay != null ) {
      for ( String h : hay ) {
        if ( !previos.contains( h ) ) {
          borrar( new File( dest, h ) );
        }
      }
    }
  }

  /**
   * Comprime una carpeta en un .zip (nombres UTF-8 con /), escribiendo primero un temporal al lado y despues
   * renombrandolo: para la copia para alumnos en .zip (C6) y las pruebas.
   */
  public static void comprimir( File carpeta, File zip ) throws IOException {
    final Path base = carpeta.getAbsoluteFile().toPath();
    File destino = zip.getAbsoluteFile();
    File dir = destino.getParentFile();
    if ( dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
      throw new IOException( "No se pudo crear la carpeta " + dir );
    }
    File tmp = new File( dir, "." + destino.getName() + "." + System.nanoTime() + ".tmp" );
    List<Path> todo;
    try ( Stream<Path> s = Files.walk( base ) ) {
      todo = s.filter( p -> !p.equals( base ) ).sorted().collect( Collectors.toList() );
    }
    try {
      try ( ZipOutputStream z = new ZipOutputStream( new FileOutputStream( tmp ), StandardCharsets.UTF_8 ) ) {
        for ( Path p : todo ) {
          File f = p.toFile();
          if ( f.equals( tmp ) || f.equals( destino ) ) {
            continue;
          }
          String rel = base.relativize( p ).toString().replace( '\\', '/' );
          if ( f.isDirectory() ) {
            String[] hijos = f.list();
            if ( hijos != null && hijos.length == 0 ) {
              z.putNextEntry( new ZipEntry( rel + "/" ) );
              z.closeEntry();
            }
            continue;
          }
          z.putNextEntry( new ZipEntry( rel ) );
          Files.copy( p, z );
          z.closeEntry();
        }
      }
      Files.move( tmp.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING );
    } finally {
      if ( tmp.exists() && !tmp.delete() ) {
        tmp.deleteOnExit();
      }
    }
  }

  // ------------------------------------------------------------------ acceso

  /** El pack con los valores por defecto puestos; null si no se pudo leer (ver {@link #problemas()}). */
  public Pack pack() {
    return pack;
  }

  /** La carpeta del pack (la del docente, o la de trabajo con el .zip o el inline); null si no se pudo abrir. */
  public File raiz() {
    return raiz;
  }

  public File origen() {
    return origen;
  }

  /** {@link #FORMA_CARPETA}, {@link #FORMA_ZIP} o {@link #FORMA_JSON}; null si el origen no existe. */
  public String forma() {
    return forma;
  }

  public List<Problema> problemas() {
    return Collections.unmodifiableList( new ArrayList<>( problemas ) );
  }

  public List<Problema> errores() {
    return filtrar( true );
  }

  public List<Problema> avisos() {
    return filtrar( false );
  }

  private List<Problema> filtrar( boolean errores ) {
    List<Problema> l = new ArrayList<>();
    for ( Problema p : problemas ) {
      if ( p.esError() == errores ) {
        l.add( p );
      }
    }
    return l;
  }

  /** Se leyo el pack y no tiene errores (puede tener avisos). */
  public boolean valido() {
    return pack != null && raiz != null && errores().isEmpty();
  }

  /** Lanza {@link PackInvalido} si el pack no es valido. */
  public CorrectorPack requerirValido() throws PackInvalido {
    if ( !valido() ) {
      throw new PackInvalido( origen, problemas );
    }
    return this;
  }

  /** en | es | pt: el idioma del pack (language), en si no lo dice. */
  public String idioma() {
    return Rotulos.normalizar( pack == null ? null : pack.idioma );
  }

  /** Un archivo del pack por su ruta relativa (no mira si existe). IllegalArgumentException si la ruta sale. */
  public File archivo( String ruta ) {
    String motivo = motivoRuta( ruta );
    if ( motivo != null || raiz == null ) {
      throw new IllegalArgumentException( "Ruta invalida en el pack (" + ( motivo == null ? "sin raiz" : motivo )
        + "): " + ruta );
    }
    String n = normalizarRuta( ruta );
    return n.isEmpty() ? raiz : new File( raiz, n.replace( '/', File.separatorChar ) );
  }

  /** La solucion del docente, o null (copia para alumnos). */
  public File solucion() {
    return pack == null || vacio( pack.solucion ) ? null : archivo( pack.solucion );
  }

  /** enunciado.md, o null. */
  public File enunciado() {
    return pack == null || vacio( pack.enunciado ) ? null : archivo( pack.enunciado );
  }

  /** El Markdown del enunciado en UTF-8, o null si no hay o no se puede leer. */
  public String textoEnunciado() {
    File f = enunciado();
    if ( f == null || !f.isFile() ) {
      return null;
    }
    try {
      String t = new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
      return t.startsWith( "\uFEFF" ) ? t.substring( 1 ) : t;
    } catch ( IOException e ) {
      return null;
    }
  }

  /** La carpeta de entradas del caso, o null si no tiene. */
  public File dirEntradas( Caso c ) {
    return c == null || vacio( c.dirEntradas ) ? null : archivo( c.dirEntradas );
  }

  /** El CSV esperado del caso, o null. */
  public File esperado( Caso c ) {
    return c == null || vacio( c.esperado ) ? null : archivo( c.esperado );
  }

  /** Cada nombre de {@code inputs} con su archivo en la carpeta del caso (en el orden de inputs). */
  public Map<String, File> entradas( Caso c ) {
    Map<String, File> m = new LinkedHashMap<>();
    File dir = dirEntradas( c );
    if ( dir != null && pack != null ) {
      for ( String e : pack.entradas ) {
        if ( !vacio( e ) ) {
          m.put( e.trim(), new File( dir, e.trim() ) );
        }
      }
    }
    return m;
  }

  /** La base plantilla de la captura query, o null. */
  public File plantilla() {
    return pack == null || pack.salida.base == null || vacio( pack.salida.base.plantilla ) ? null : archivo(
      pack.salida.base.plantilla );
  }

  /** "Ejercicio 3 - Ventas · 2 casos (1 oculto) · 10 pts" (§9). */
  public String resumen( String idioma ) {
    if ( pack == null ) {
      return origen == null ? "" : origen.getName();
    }
    Rotulos r = Rotulos.de( AREA_TEXTOS, idioma );
    int n = pack.casos.size();
    int ocultos = n - pack.casosVisibles().size();
    String casos = r.f( n == 1 ? "pack.summary.cases.1" : "pack.summary.cases.n", n );
    if ( ocultos > 0 ) {
      casos += " " + r.f( ocultos == 1 ? "pack.summary.hidden.1" : "pack.summary.hidden.n", ocultos );
    }
    String titulo = vacio( pack.titulo ) ? pack.id : pack.titulo;
    return r.f( "pack.summary", titulo, casos, num( pack.puntosTotales() ) );
  }

  /** Todos los problemas, uno por linea ("Error · cases[0].expected: ..."), en en | es | pt. */
  public String textoProblemas( String idioma ) {
    StringBuilder b = new StringBuilder();
    Rotulos r = Rotulos.de( AREA_TEXTOS, idioma );
    for ( Problema p : problemas ) {
      b.append( b.length() == 0 ? "" : "\n" ).append( r.s( "pack.severity." + p.severidad ) ).append( " · " ).append(
        p.linea( idioma ) );
    }
    return b.toString();
  }

  /** Borra la carpeta temporal si la creo este objeto (no la de trabajo que paso quien llama). */
  @Override
  public void close() {
    if ( temporal != null ) {
      borrar( temporal );
      temporal = null;
    }
  }

  // ------------------------------------------------------------------ problemas

  /**
   * Un problema del pack. {@code code} es estable; {@code where} dice donde esta en pack.json
   * ({@code cases[1].expected}, {@code rubric[7].check}) o el archivo; {@code message} es el texto en ingles (el de
   * las tools MCP). {@link #texto(String)} lo da en es o pt.
   */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  public static final class Problema {

    public static final String ERROR = "error";
    public static final String AVISO = "warning";

    @JsonProperty( "severity" )
    public final String severidad;
    @JsonProperty( "code" )
    public final String codigo;
    @JsonProperty( "where" )
    public final String donde;
    @JsonProperty( "message" )
    public final String mensaje;
    /** Clave de Rotulos (pack.&lt;codigo&gt;[.variante]). */
    @JsonIgnore
    public final String clave;
    @JsonIgnore
    public final List<String> args;

    Problema( String severidad, String donde, String codigo, String clave, Object... args ) {
      this.severidad = severidad;
      this.donde = donde == null ? "" : donde;
      this.codigo = codigo;
      this.clave = clave;
      List<String> a = new ArrayList<>();
      for ( Object o : args ) {
        a.add( o == null ? "" : String.valueOf( o ) );
      }
      this.args = Collections.unmodifiableList( a );
      this.mensaje = texto( "en" );
    }

    public boolean esError() {
      return ERROR.equals( severidad );
    }

    /** El texto del problema (sin el donde) en en | es | pt. */
    public String texto( String idioma ) {
      Rotulos r = Rotulos.de( AREA_TEXTOS, idioma );
      Object[] a = new Object[ args.size() ];
      for ( int i = 0; i < a.length; i++ ) {
        String s = args.get( i );
        a[ i ] = !s.isEmpty() && s.charAt( 0 ) == TRADUCIBLE ? r.s( "pack." + s.substring( 1 ) ) : s;
      }
      return r.tiene( clave ) ? r.f( clave, a ) : codigo + ( a.length == 0 ? "" : " " + Arrays.toString( a ) );
    }

    /** "donde: texto". */
    public String linea( String idioma ) {
      return donde.isEmpty() ? texto( idioma ) : donde + ": " + texto( idioma );
    }

    @Override
    public String toString() {
      return severidad + " " + codigo + " " + linea( "en" );
    }
  }

  /** El pack tiene errores; {@link #getMessage()} los lista en ingles y {@link #texto(String)} en es o pt. */
  public static final class PackInvalido extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient List<Problema> problemas;
    private final String origen;

    PackInvalido( File origen, List<Problema> problemas ) {
      super( texto( origen == null ? "" : origen.getPath(), problemas, "en" ) );
      this.origen = origen == null ? "" : origen.getPath();
      this.problemas = Collections.unmodifiableList( new ArrayList<>( problemas ) );
    }

    /** Errores y avisos. */
    public List<Problema> problemas() {
      return problemas;
    }

    public String texto( String idioma ) {
      return texto( origen, problemas, idioma );
    }

    private static String texto( String origen, List<Problema> problemas, String idioma ) {
      StringBuilder b = new StringBuilder( Rotulos.de( AREA_TEXTOS, idioma ).f( "pack.invalid", origen ) );
      for ( Problema p : problemas ) {
        if ( p.esError() ) {
          b.append( "\n- " ).append( p.linea( idioma ) );
        }
      }
      return b.toString();
    }
  }

  private void agregar( String severidad, String donde, String codigo, String clave, Object... args ) {
    problemas.add( new Problema( severidad, donde, codigo, clave, args ) );
  }

  private void error( String donde, String codigo, Object... args ) {
    agregar( Problema.ERROR, donde, codigo, "pack." + codigo, args );
  }

  private void errorV( String donde, String codigo, String variante, Object... args ) {
    agregar( Problema.ERROR, donde, codigo, "pack." + codigo + "." + variante, args );
  }

  private void aviso( String donde, String codigo, Object... args ) {
    agregar( Problema.AVISO, donde, codigo, "pack." + codigo, args );
  }

  private void avisoV( String donde, String codigo, String variante, Object... args ) {
    agregar( Problema.AVISO, donde, codigo, "pack." + codigo + "." + variante, args );
  }

  // ------------------------------------------------------------------ utilidades

  private static String traducible( String clave ) {
    return TRADUCIBLE + clave;
  }

  private static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  private static boolean vacia( File dir ) {
    String[] l = dir.list();
    return dir.isDirectory() && l != null && l.length == 0;
  }

  private static boolean idiomaConocido( String idioma ) {
    String i = idioma.trim().toLowerCase( Locale.ROOT );
    return i.equals( "en" ) || i.equals( "es" ) || i.equals( "pt" ) || i.startsWith( "en_" ) || i.startsWith( "es_" )
      || i.startsWith( "pt_" ) || i.startsWith( "en-" ) || i.startsWith( "es-" ) || i.startsWith( "pt-" );
  }

  private static boolean tipoKettle( String tipo ) {
    for ( String t : TIPOS_KETTLE ) {
      if ( t.equalsIgnoreCase( tipo.trim() ) ) {
        return true;
      }
    }
    return false;
  }

  /** null si la mascara de SimpleDateFormat es valida; si no, el motivo. */
  private static String mascaraFechaMala( String mascara ) {
    try {
      new SimpleDateFormat( mascara, Locale.ROOT );
      return null;
    } catch ( IllegalArgumentException e ) {
      return mensaje( e );
    }
  }

  /** es_ES, es-ES, en, pt_BR, es_419: idioma ISO de 2 letras (o 3) y pais ISO de 2 letras (o area de 3 cifras). */
  static boolean localeValido( String locale ) {
    java.util.regex.Matcher m = LOCALE.matcher( locale.trim() );
    if ( !m.matches() ) {
      return false;
    }
    String idioma = m.group( 1 ).toLowerCase( Locale.ROOT );
    if ( idioma.length() == 2 && !Arrays.asList( Locale.getISOLanguages() ).contains( idioma ) ) {
      return false;
    }
    String pais = m.group( 2 );
    return pais == null || Character.isDigit( pais.charAt( 0 ) ) || Arrays.asList( Locale.getISOCountries() ).contains(
      pais.toUpperCase( Locale.ROOT ) );
  }

  /** Europe/Madrid, UTC, GMT-03:00, y los ids cortos que acepta -Duser.timezone (EST...). */
  static boolean zonaValida( String zona ) {
    String z = zona.trim();
    try {
      ZoneId.of( z );
      return true;
    } catch ( DateTimeException e ) {
      return ZoneId.SHORT_IDS.containsKey( z );
    }
  }

  private static Charset charset( String nombre, Charset siNo ) {
    if ( vacio( nombre ) ) {
      return siNo;
    }
    try {
      return Charset.isSupported( nombre.trim() ) ? Charset.forName( nombre.trim() ) : siNo;
    } catch ( IllegalCharsetNameException e ) {
      return siNo;
    }
  }

  private boolean lintExiste( String id ) {
    try {
      return Catalogo.buscar( id ) != null;
    } catch ( Throwable t ) {
      return true; // sin el catalogo del linter no se puede revisar: lo dira C5 como "not evaluated"
    }
  }

  private static String idsLint() {
    try {
      return Catalogo.idsValidos();
    } catch ( Throwable t ) {
      return "";
    }
  }

  private static String lista( List<String> l ) {
    return l.isEmpty() ? "-" : String.join( ", ", l );
  }

  private static String num( double d ) {
    if ( Double.isNaN( d ) || Double.isInfinite( d ) ) {
      return String.valueOf( d );
    }
    return BigDecimal.valueOf( d ).stripTrailingZeros().toPlainString();
  }

  private static String mb( long bytes ) {
    return num( Math.round( bytes / 1024.0 / 1024.0 * 10 ) / 10.0 );
  }

  private static String primeraLinea( String s ) {
    if ( s == null ) {
      return "";
    }
    int i = s.indexOf( '\n' );
    return ( i < 0 ? s : s.substring( 0, i ) ).trim();
  }

  private static String mensaje( Throwable e ) {
    if ( e == null ) {
      return "";
    }
    String m = e.getMessage();
    return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : primeraLinea( m );
  }

  private static void cerrar( ZipFile zf ) {
    if ( zf != null ) {
      try {
        zf.close();
      } catch ( IOException e ) {
        // nada
      }
    }
  }

  /**
   * Borra un archivo o una carpeta entera sin seguir enlaces: un symlink o una junction de Windows (reparse point,
   * {@code isOther}) se borra a si mismo, no lo que tiene adentro.
   */
  static void borrar( File f ) {
    if ( f == null ) {
      return;
    }
    BasicFileAttributes a;
    try {
      a = Files.readAttributes( f.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS );
    } catch ( IOException e ) {
      return; // no existe
    }
    if ( a.isDirectory() && !a.isSymbolicLink() && !a.isOther() ) {
      File[] hijos = f.listFiles();
      if ( hijos != null ) {
        for ( File h : hijos ) {
          borrar( h );
        }
      }
    }
    if ( !f.delete() ) {
      f.deleteOnExit();
    }
  }
}
