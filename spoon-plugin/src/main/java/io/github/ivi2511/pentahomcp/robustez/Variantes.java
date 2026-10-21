package io.github.ivi2511.pentahomcp.robustez;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Generador de datos sucios de {@code stress_test} (docs/robustez-diseno.md §5.1), sin Kettle: parte de las primeras
 * filas reales de una entrada ({@link Tabla}) y arma variantes con filas rotas <b>agregadas</b> (la base sigue de
 * control), cada una con su manifiesto (fila → tipo de rotura). Escribe la variante en el formato de la entrada: texto
 * con separador (CSV input, Text file input), JSON (un array de objetos planos) o Excel (xlsx/xls, con POI).
 *
 * @dueño R2c
 */
public final class Variantes {

  /** Tipos de rotura que se inyectan en filas (los demás son variantes de archivo entero). */
  public static final List<String> TIPOS_FILA = Arrays.asList( "missing_fields", "bad_dates", "bad_numbers",
    "duplicates", "truncated_lines", "extra_separators", "whitespace", "too_long_text", "out_of_range" );
  /** Variantes de archivo entero. */
  public static final List<String> TIPOS_ARCHIVO = Arrays.asList( "wrong_encoding", "empty_file", "header_only" );
  /** Las que corren por defecto (§5). rerun se agrega solo si hay una BD de archivo. */
  public static final List<String> POR_DEFECTO = Arrays.asList( "combined", "empty_file", "header_only" );
  /** Tipos que deben terminar en rechazo (o descarte declarado): los demás solo se informan. */
  public static final Set<String> DEBEN_RECHAZARSE = new LinkedHashSet<String>( Arrays.asList( "missing_fields",
    "bad_dates", "bad_numbers", "truncated_lines" ) );

  /** Marcador que lleva todo valor inyectado de texto: así se lo reconoce si llega a una salida. */
  public static final String MARCA = "zz_estres";

  private Variantes() {
  }

  /** Un campo de la entrada, como lo declara el step. */
  public static final class Campo {
    public String nombre;
    /** String, Integer, Number, BigNumber, Date, Timestamp, Boolean (los nombres de ValueMeta). */
    public String tipo = "String";
    public String formato;
    public int largo = -1;
    /** El tipo no está declarado: se dedujo de los datos (las roturas de ese tipo no se exigen como rechazo). */
    public boolean inferido;

    public Campo() {
    }

    public Campo( String nombre, String tipo, String formato ) {
      this.nombre = nombre;
      this.tipo = tipo == null || tipo.trim().isEmpty() ? "String" : tipo.trim();
      this.formato = formato;
    }

    boolean numerico() {
      return "Integer".equals( tipo ) || "Number".equals( tipo ) || "BigNumber".equals( tipo );
    }

    boolean fecha() {
      return "Date".equals( tipo ) || "Timestamp".equals( tipo );
    }
  }

  /** Las filas base: los campos declarados y hasta {@code rows} filas reales (valores como texto). */
  public static final class Tabla {
    public final List<Campo> campos = new ArrayList<Campo>();
    public final List<String[]> filas = new ArrayList<String[]>();
    /** Nombres de las columnas como están en el archivo (cabecera), si las hay. */
    public List<String> cabecera;

    public int indice( String campo ) {
      for ( int i = 0; i < campos.size(); i++ ) {
        if ( campos.get( i ).nombre.equalsIgnoreCase( campo ) ) {
          return i;
        }
      }
      return -1;
    }
  }

  /** Una fila de la variante: valores, o una línea cruda (truncada, separadores de más) en archivos de texto. */
  public static final class Fila {
    public String[] valores;
    /** Línea tal cual (solo texto): pisa a valores. */
    public String cruda;
    /** null = fila base; si no, el tipo de rotura. */
    public String tipo;
    /** Rota en una columna de tipo deducido: se inyecta pero no se exige que se rechace. */
    public boolean dudosa;

    Fila( String[] v, String tipo ) {
      this.valores = v;
      this.tipo = tipo;
    }
  }

  /** Una variante lista para escribir. */
  public static final class Variante {
    public final String id;
    public final List<Fila> filas = new ArrayList<Fila>();
    /** 0 bytes. */
    public boolean vacio;
    /** Solo la cabecera. */
    public boolean soloCabecera;
    /** Se escribe en la otra codificación (UTF-8 ↔ ISO-8859-1). */
    public boolean otraCodificacion;
    /** Tipo → filas inyectadas. */
    public final Map<String, Integer> inyectadas = new LinkedHashMap<String, Integer>();

    Variante( String id ) {
      this.id = id;
    }

    /** Filas que deberían rechazarse (o descartarse declaradas). */
    public int debenRechazarse() {
      int n = 0;
      for ( Fila f : filas ) {
        if ( f.tipo != null && !f.dudosa && DEBEN_RECHAZARSE.contains( f.tipo ) ) {
          n++;
        }
      }
      return n;
    }

    public int datos() {
      return vacio || soloCabecera ? 0 : filas.size();
    }

    /** El manifiesto: número de fila de datos (desde 1) → tipo, solo las rotas. */
    public ObjectNode manifiesto( ObjectMapper om ) {
      ObjectNode o = om.createObjectNode();
      for ( int i = 0; i < filas.size(); i++ ) {
        if ( filas.get( i ).tipo != null ) {
          o.put( String.valueOf( i + 1 ), filas.get( i ).tipo );
        }
      }
      return o;
    }
  }

  // ------------------------------------------------------------------ generar

  /** Las ids válidas de variante. */
  public static Set<String> ids() {
    Set<String> s = new LinkedHashSet<String>();
    s.add( "combined" );
    s.addAll( TIPOS_FILA );
    s.addAll( TIPOS_ARCHIVO );
    s.add( "rerun" );
    return s;
  }

  /** {@code ["all"]} = cada tipo por separado + las de por defecto; null o vacío = las de por defecto. */
  public static List<String> expandir( Collection<String> pedidas, boolean hayBdArchivo ) {
    Set<String> s = new LinkedHashSet<String>();
    if ( pedidas == null || pedidas.isEmpty() ) {
      s.addAll( POR_DEFECTO );
      if ( hayBdArchivo ) {
        s.add( "rerun" );
      }
    } else {
      for ( String p : pedidas ) {
        String x = p.trim().toLowerCase( Locale.ROOT );
        if ( "all".equals( x ) ) {
          s.addAll( POR_DEFECTO );
          s.addAll( TIPOS_FILA );
          s.addAll( TIPOS_ARCHIVO );
          s.add( "rerun" );
        } else if ( ids().contains( x ) ) {
          s.add( x );
        } else {
          throw new IllegalArgumentException( "unknown variant '" + p + "'; valid: all, " + String.join( ", ", ids() ) );
        }
      }
    }
    return new ArrayList<String>( s );
  }

  /**
   * Arma la variante {@code id} a partir de la base. {@code obligatorios}: los campos que no pueden faltar (null =
   * todos). {@code texto}: la entrada es un archivo de texto (las líneas cortadas y los separadores de más solo
   * existen ahí).
   */
  public static Variante generar( String id, Tabla base, Collection<String> obligatorios, boolean texto,
                                  String separador ) {
    Variante v = new Variante( id );
    for ( String[] f : base.filas ) {
      v.filas.add( new Fila( f.clone(), null ) );
    }
    if ( "empty_file".equals( id ) ) {
      v.vacio = true;
      v.filas.clear();
      return v;
    }
    if ( "header_only".equals( id ) ) {
      v.soloCabecera = true;
      v.filas.clear();
      return v;
    }
    if ( "wrong_encoding".equals( id ) ) {
      v.otraCodificacion = true;
      int ix = primero( base, false, false );
      if ( ix >= 0 ) {
        String[] f = plantilla( base );
        f[ ix ] = "Ñandú camión ação " + MARCA;
        agregar( v, f, "wrong_encoding" );
      }
      return v;
    }
    if ( "rerun".equals( id ) ) {
      return v; // la base limpia; la corre dos veces la hija
    }
    List<String> tipos = "combined".equals( id ) ? TIPOS_FILA : Arrays.asList( id );
    for ( String t : tipos ) {
      inyectar( v, base, t, obligatorios, texto, separador == null || separador.isEmpty() ? ";" : separador );
    }
    return v;
  }

  private static void inyectar( Variante v, Tabla base, String tipo, Collection<String> obligatorios, boolean texto,
                                String sep ) {
    int n = base.campos.size();
    if ( n == 0 ) {
      return;
    }
    switch ( tipo ) {
      case "missing_fields": {
        for ( int i = 0; i < n; i++ ) {
          Campo c = base.campos.get( i );
          if ( obligatorios == null || contiene( obligatorios, c.nombre ) ) {
            String[] f = plantilla( base );
            f[ i ] = "";
            agregar( v, f, tipo );
          }
        }
        String[] vacia = new String[ n ];
        Arrays.fill( vacia, "" );
        agregar( v, vacia, tipo );
        break;
      }
      case "bad_dates": {
        for ( int i = 0; i < n; i++ ) {
          if ( base.campos.get( i ).fecha() ) {
            for ( String malo : new String[] { "31/02/2021", "2021-13-45", "no es fecha" } ) {
              String[] f = plantilla( base );
              f[ i ] = malo;
              agregar( v, f, tipo ).dudosa = base.campos.get( i ).inferido;
            }
          }
        }
        break;
      }
      case "bad_numbers": {
        for ( int i = 0; i < n; i++ ) {
          if ( base.campos.get( i ).numerico() ) {
            for ( String malo : new String[] { "mil", "12abc", "--5" } ) {
              String[] f = plantilla( base );
              f[ i ] = malo;
              agregar( v, f, tipo ).dudosa = base.campos.get( i ).inferido;
            }
          }
        }
        break;
      }
      case "duplicates": {
        for ( int k = 0; k < Math.min( 3, base.filas.size() ); k++ ) {
          agregar( v, base.filas.get( k ).clone(), tipo );
        }
        break;
      }
      case "truncated_lines": {
        if ( texto ) {
          String[] f = plantilla( base );
          StringBuilder sb = new StringBuilder();
          for ( int i = 0; i < Math.min( 2, n ); i++ ) {
            sb.append( f[ i ] == null ? "" : f[ i ] ).append( sep );
          }
          Fila fila = new Fila( f, tipo );
          fila.cruda = sb.toString();
          v.filas.add( fila );
          cuenta( v, tipo );
        }
        break;
      }
      case "extra_separators": {
        if ( texto ) {
          String[] f = plantilla( base );
          StringBuilder sb = new StringBuilder();
          for ( int i = 0; i < n; i++ ) {
            if ( i > 0 ) {
              sb.append( sep );
            }
            sb.append( f[ i ] == null ? "" : f[ i ] );
          }
          Fila fila = new Fila( f, tipo );
          fila.cruda = sb.append( sep ).append( MARCA ).toString();
          v.filas.add( fila );
          cuenta( v, tipo );
        }
        break;
      }
      case "whitespace": {
        String[] f = plantilla( base );
        for ( int i = 0; i < n; i++ ) {
          f[ i ] = "  " + ( f[ i ] == null ? "" : f[ i ] ) + "\t ";
        }
        agregar( v, f, tipo );
        break;
      }
      case "too_long_text": {
        int ix = primero( base, false, false );
        if ( ix >= 0 ) {
          String[] f = plantilla( base );
          StringBuilder sb = new StringBuilder( MARCA );
          while ( sb.length() < 5000 ) {
            sb.append( 'x' );
          }
          f[ ix ] = sb.toString();
          agregar( v, f, tipo );
        }
        break;
      }
      case "out_of_range": {
        for ( int i = 0; i < n; i++ ) {
          if ( base.campos.get( i ).numerico() ) {
            for ( String x : new String[] { "1e15", "-1" } ) {
              String[] f = plantilla( base );
              f[ i ] = "Integer".equals( base.campos.get( i ).tipo ) && x.contains( "e" ) ? "999999999999999" : x;
              agregar( v, f, tipo );
            }
            break;
          }
        }
        break;
      }
      default:
        break;
    }
  }

  private static boolean contiene( Collection<String> l, String x ) {
    for ( String s : l ) {
      if ( s.equalsIgnoreCase( x ) ) {
        return true;
      }
    }
    return false;
  }

  /** Una fila válida para romper: la primera de la base, o una inventada a partir de los tipos si no hay filas. */
  private static String[] plantilla( Tabla base ) {
    if ( !base.filas.isEmpty() ) {
      return base.filas.get( 0 ).clone();
    }
    String[] f = new String[ base.campos.size() ];
    for ( int i = 0; i < f.length; i++ ) {
      Campo c = base.campos.get( i );
      f[ i ] = c.numerico() ? "1" : c.fecha() ? "2021-01-01" : "x";
    }
    return f;
  }

  /** El primer campo de texto (o, si no hay, el primero). */
  private static int primero( Tabla base, boolean numerico, boolean fecha ) {
    for ( int i = 0; i < base.campos.size(); i++ ) {
      Campo c = base.campos.get( i );
      if ( !c.numerico() && !c.fecha() && !"Boolean".equals( c.tipo ) ) {
        return i;
      }
    }
    return base.campos.isEmpty() ? -1 : 0;
  }

  private static Fila agregar( Variante v, String[] f, String tipo ) {
    Fila fila = new Fila( f, tipo );
    v.filas.add( fila );
    cuenta( v, tipo );
    return fila;
  }

  /**
   * Los campos declarados como texto cuyo tipo real se conoce (lo convierte un step de más abajo:
   * {@code conversiones} campo → tipo) o se deduce de las filas base (todos enteros, números o fechas): así las
   * roturas de números y fechas caen donde importan aunque la entrada lea todo como String.
   */
  public static void completarTipos( Tabla t, Map<String, String> conversiones ) {
    for ( int i = 0; i < t.campos.size(); i++ ) {
      Campo c = t.campos.get( i );
      if ( !"String".equals( c.tipo ) ) {
        continue;
      }
      String conv = conversiones == null ? null : conversiones.get( c.nombre.toLowerCase( Locale.ROOT ) );
      if ( conv != null ) {
        c.tipo = conv; // lo que dice un step de más abajo manda (también si dice String)
        continue;
      }
      boolean alguno = false;
      boolean enteros = true;
      boolean numeros = true;
      boolean fechas = true;
      for ( String[] f : t.filas ) {
        String x = i < f.length && f[ i ] != null ? f[ i ].trim() : "";
        if ( x.isEmpty() ) {
          continue;
        }
        alguno = true;
        enteros &= x.matches( "-?\\d{1,18}" );
        numeros &= x.matches( "-?\\d+([.,]\\d+)?" );
        fechas &= x.matches( "\\d{4}-\\d{1,2}-\\d{1,2}([ T].*)?|\\d{1,2}/\\d{1,2}/\\d{2,4}" );
      }
      if ( alguno && ( enteros || numeros || fechas ) ) {
        c.tipo = enteros ? "Integer" : numeros ? "Number" : "Date";
        c.inferido = true;
      }
    }
  }

  private static void cuenta( Variante v, String tipo ) {
    Integer x = v.inyectadas.get( tipo );
    v.inyectadas.put( tipo, x == null ? 1 : x + 1 );
  }

  // ------------------------------------------------------------------ texto

  /** Formato de un archivo de texto con separador. */
  public static final class FormatoTexto {
    public String separador = ";";
    public String encierro = "\"";
    public boolean cabecera = true;
    public String encoding = "UTF-8";
    public int lineasCabecera = 1;

    public Charset charset() {
      try {
        return encoding == null || encoding.trim().isEmpty() ? Charset.defaultCharset()
          : Charset.forName( encoding.trim() );
      } catch ( Exception e ) {
        return StandardCharsets.UTF_8;
      }
    }

    /** La otra codificación: UTF-8 ↔ ISO-8859-1. */
    public Charset otra() {
      return StandardCharsets.UTF_8.equals( charset() ) ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
    }
  }

  /** Lee hasta {@code max} filas de datos (sin la cabecera) de un archivo de texto con separador. */
  public static List<String[]> leerTexto( File f, FormatoTexto fmt, int max, List<String> cabeceraOut )
    throws IOException {
    byte[] b = leerTodo( f, 64L * 1024 * 1024 );
    String s = new String( b, fmt.charset() );
    if ( s.startsWith( "\uFEFF" ) ) {
      s = s.substring( 1 );
    }
    List<String[]> filas = new ArrayList<String[]>();
    int saltear = fmt.cabecera ? Math.max( 1, fmt.lineasCabecera ) : 0;
    for ( String linea : s.split( "\r\n|\n|\r" ) ) {
      if ( linea.isEmpty() ) {
        continue;
      }
      String[] v = partir( linea, fmt.separador, fmt.encierro );
      if ( saltear > 0 ) {
        saltear--;
        if ( cabeceraOut != null ) {
          cabeceraOut.addAll( Arrays.asList( v ) );
        }
        continue;
      }
      filas.add( v );
      if ( filas.size() >= max ) {
        break;
      }
    }
    return filas;
  }

  /** Parte una línea por el separador respetando el encierro (comillas dobladas dentro). */
  public static String[] partir( String linea, String sep, String enc ) {
    List<String> l = new ArrayList<String>();
    String e = enc == null ? "" : enc;
    String sp = sep == null || sep.isEmpty() ? ";" : sep;
    StringBuilder cur = new StringBuilder();
    boolean dentro = false;
    int i = 0;
    while ( i < linea.length() ) {
      if ( !e.isEmpty() && linea.startsWith( e, i ) ) {
        if ( dentro && linea.startsWith( e + e, i ) ) {
          cur.append( e );
          i += 2 * e.length();
          continue;
        }
        dentro = !dentro;
        i += e.length();
        continue;
      }
      if ( !dentro && linea.startsWith( sp, i ) ) {
        l.add( cur.toString() );
        cur.setLength( 0 );
        i += sp.length();
        continue;
      }
      cur.append( linea.charAt( i ) );
      i++;
    }
    l.add( cur.toString() );
    return l.toArray( new String[ 0 ] );
  }

  /** Escribe la variante como texto con separador (con cabecera si el formato la tiene). */
  public static void escribirTexto( Variante v, Tabla base, FormatoTexto fmt, File destino ) throws IOException {
    crearCarpeta( destino );
    Charset cs = v.otraCodificacion ? fmt.otra() : fmt.charset();
    if ( v.vacio ) {
      new FileOutputStream( destino ).close();
      return;
    }
    StringBuilder sb = new StringBuilder();
    String sep = fmt.separador == null || fmt.separador.isEmpty() ? ";" : fmt.separador;
    if ( fmt.cabecera ) {
      List<String> cab = base.cabecera != null && !base.cabecera.isEmpty() ? base.cabecera : nombres( base );
      sb.append( unir( cab.toArray( new String[ 0 ] ), sep, fmt.encierro ) ).append( "\n" );
    }
    if ( !v.soloCabecera ) {
      for ( Fila f : v.filas ) {
        sb.append( f.cruda != null ? f.cruda : unir( f.valores, sep, fmt.encierro ) ).append( "\n" );
      }
    }
    try ( OutputStream os = new FileOutputStream( destino ) ) {
      os.write( sb.toString().getBytes( cs ) );
    }
  }

  private static List<String> nombres( Tabla base ) {
    List<String> l = new ArrayList<String>();
    for ( Campo c : base.campos ) {
      l.add( c.nombre );
    }
    return l;
  }

  /** Une valores; encierra los que llevan el separador, el encierro o un salto de línea. */
  static String unir( String[] v, String sep, String enc ) {
    StringBuilder sb = new StringBuilder();
    String e = enc == null ? "" : enc;
    for ( int i = 0; i < v.length; i++ ) {
      if ( i > 0 ) {
        sb.append( sep );
      }
      String x = v[ i ] == null ? "" : v[ i ];
      if ( !e.isEmpty() && ( x.contains( sep ) || x.contains( e ) || x.contains( "\n" ) ) ) {
        sb.append( e ).append( x.replace( e, e + e ) ).append( e );
      } else {
        sb.append( x );
      }
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------ JSON

  /** Lee un array de objetos planos (o {"x":[...]}: el primer array) hasta {@code max}: claves = campos. */
  public static List<String[]> leerJson( File f, List<Campo> campos, int max, ObjectMapper om ) throws IOException {
    JsonNode raiz = om.readTree( leerTodo( f, 64L * 1024 * 1024 ) );
    JsonNode arr = primerArray( raiz );
    List<String[]> filas = new ArrayList<String[]>();
    if ( arr == null ) {
      return filas;
    }
    for ( JsonNode o : arr ) {
      if ( !o.isObject() ) {
        continue;
      }
      String[] v = new String[ campos.size() ];
      for ( int i = 0; i < campos.size(); i++ ) {
        JsonNode x = o.get( campos.get( i ).nombre );
        v[ i ] = x == null || x.isNull() ? null : x.isValueNode() ? x.asText() : x.toString();
      }
      filas.add( v );
      if ( filas.size() >= max ) {
        break;
      }
    }
    return filas;
  }

  private static JsonNode primerArray( JsonNode n ) {
    if ( n == null ) {
      return null;
    }
    if ( n.isArray() ) {
      return n;
    }
    if ( n.isObject() ) {
      for ( Iterator<JsonNode> it = n.elements(); it.hasNext(); ) {
        JsonNode a = primerArray( it.next() );
        if ( a != null ) {
          return a;
        }
      }
    }
    return null;
  }

  /** Escribe la variante como un array de objetos (claves = nombres de los campos). */
  public static void escribirJson( Variante v, Tabla base, File destino, ObjectMapper om ) throws IOException {
    crearCarpeta( destino );
    if ( v.vacio ) {
      new FileOutputStream( destino ).close();
      return;
    }
    ArrayNode a = om.createArrayNode();
    if ( !v.soloCabecera ) {
      for ( Fila f : v.filas ) {
        ObjectNode o = a.addObject();
        for ( int i = 0; i < base.campos.size(); i++ ) {
          String x = f.valores == null || i >= f.valores.length ? null : f.valores[ i ];
          if ( x == null || ( "missing_fields".equals( f.tipo ) && x.isEmpty() ) ) {
            continue; // en JSON, «falta» = la clave no está
          }
          o.put( base.campos.get( i ).nombre, x );
        }
      }
    }
    try ( OutputStream os = new FileOutputStream( destino ) ) {
      Charset cs = v.otraCodificacion ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
      os.write( om.writeValueAsString( a ).getBytes( cs ) );
    }
  }

  // ------------------------------------------------------------------ Excel

  /** Lee la hoja (por nombre o la primera) desde startRow, con cabecera opcional: valores como texto. */
  public static List<String[]> leerExcel( File f, String hoja, int filaInicio, boolean cabecera, int columnas,
                                          int max, List<String> cabeceraOut ) throws IOException {
    List<String[]> filas = new ArrayList<String[]>();
    try ( InputStream in = new FileInputStream( f ); Workbook wb = WorkbookFactory.create( in ) ) {
      Sheet s = hoja == null || hoja.isEmpty() ? wb.getSheetAt( 0 ) : wb.getSheet( hoja );
      if ( s == null ) {
        s = wb.getSheetAt( 0 );
      }
      DataFormatter df = new DataFormatter( Locale.ROOT );
      int r0 = Math.max( 0, filaInicio );
      for ( int r = r0; r <= s.getLastRowNum(); r++ ) {
        Row row = s.getRow( r );
        String[] v = new String[ columnas ];
        boolean alguno = false;
        for ( int c = 0; c < columnas; c++ ) {
          Cell cell = row == null ? null : row.getCell( c );
          v[ c ] = cell == null ? "" : df.formatCellValue( cell );
          alguno |= !v[ c ].isEmpty();
        }
        if ( cabecera && r == r0 ) {
          if ( cabeceraOut != null ) {
            cabeceraOut.addAll( Arrays.asList( v ) );
          }
          continue;
        }
        if ( !alguno ) {
          continue;
        }
        filas.add( v );
        if ( filas.size() >= max ) {
          break;
        }
      }
    } catch ( IOException e ) {
      throw e;
    } catch ( Exception e ) {
      throw new IOException( e.getMessage(), e );
    }
    return filas;
  }

  /** Escribe la variante como libro de Excel (xls si el nombre termina en .xls), en la hoja pedida. */
  public static void escribirExcel( Variante v, Tabla base, String hoja, boolean cabecera, File destino )
    throws IOException {
    crearCarpeta( destino );
    if ( v.vacio ) {
      new FileOutputStream( destino ).close();
      return;
    }
    boolean xls = destino.getName().toLowerCase( Locale.ROOT ).endsWith( ".xls" );
    try ( Workbook wb = xls ? new HSSFWorkbook() : new XSSFWorkbook();
          OutputStream os = new FileOutputStream( destino ) ) {
      Sheet s = wb.createSheet( hoja == null || hoja.isEmpty() ? "Sheet1" : hoja );
      int r = 0;
      if ( cabecera ) {
        Row row = s.createRow( r++ );
        List<String> cab = base.cabecera != null && !base.cabecera.isEmpty() ? base.cabecera : nombres( base );
        for ( int c = 0; c < cab.size(); c++ ) {
          row.createCell( c ).setCellValue( cab.get( c ) );
        }
      }
      if ( !v.soloCabecera ) {
        for ( Fila f : v.filas ) {
          Row row = s.createRow( r++ );
          for ( int c = 0; f.valores != null && c < f.valores.length; c++ ) {
            String x = f.valores[ c ];
            if ( x == null || x.isEmpty() ) {
              continue;
            }
            Campo campo = c < base.campos.size() ? base.campos.get( c ) : null;
            if ( f.tipo == null && campo != null && campo.numerico() && esNumero( x ) ) {
              row.createCell( c ).setCellValue( Double.parseDouble( x ) );
            } else {
              row.createCell( c ).setCellValue( x.length() > 32000 ? x.substring( 0, 32000 ) : x );
            }
          }
        }
      }
      wb.write( os );
    }
  }

  private static boolean esNumero( String x ) {
    try {
      Double.parseDouble( x );
      return true;
    } catch ( NumberFormatException e ) {
      return false;
    }
  }

  // ------------------------------------------------------------------ util

  static byte[] leerTodo( File f, long tope ) throws IOException {
    if ( f.length() > tope ) {
      throw new IOException( "file too large: " + f.getName() );
    }
    try ( InputStream in = new FileInputStream( f ) ) {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] buf = new byte[ 8192 ];
      for ( int n; ( n = in.read( buf ) ) > 0; ) {
        b.write( buf, 0, n );
      }
      return b.toByteArray();
    }
  }

  private static void crearCarpeta( File destino ) throws IOException {
    File d = destino.getAbsoluteFile().getParentFile();
    if ( d != null && !d.isDirectory() && !d.mkdirs() && !d.isDirectory() ) {
      throw new IOException( "cannot create " + d );
    }
  }
}
