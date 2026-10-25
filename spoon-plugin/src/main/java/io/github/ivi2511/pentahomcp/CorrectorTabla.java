package io.github.ivi2511.pentahomcp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;

/**
 * Una tabla de texto para el corrector (docs/corrector-diseno.md §3 paso 5 y §4): la salida esperada de un caso
 * ({@code esperado/<caso>.csv}, leida con {@code output.expected_csv} del pack) o la del alumno: su Text file output
 * leido con <b>su</b> separador, encierro, encabezado y {@code <encoding>} (vacio = el {@code file.encoding} del
 * pack, P17), o las filas que capturo un RowListener o la consulta del pack ({@link #de}). No usa Kettle.
 * <p>
 * Lectura del CSV: BOM UTF-8/UTF-16 (gana sobre el encoding declarado), encierro con el encierro doblado como
 * escape, separadores y saltos de linea dentro del encierro, fin de linea {@code \n}, {@code \r\n} o {@code \r},
 * separador de varios caracteres, {@code \t} y {@code $[hex]} como en Kettle. Bytes que no son del charset: U+FFFD
 * (la pista de mojibake los busca). Filas cortas se completan con null; las largas se recortan y se avisa
 * ({@link #FILAS_IRREGULARES}). Un pie igual al encabezado (Text file output con «footer») se quita. Las lineas
 * vacias se saltean (con una sola columna, solo las del final). Celda vacia o igual al {@code nullif} que declaro
 * el alumno = null (§4). {@link Lectura#maxFilas} corta la lectura y marca {@link #cortada} (too_many_rows).
 * <p>
 * Los avisos de la lectura no llevan valores de celdas (la devolucion de un caso oculto los conserva).
 *
 * @dueño C2
 */
final class CorrectorTabla {

  /** Aviso: filas con mas celdas que columnas (se recortaron); detail = cuantas. */
  static final String FILAS_IRREGULARES = "irregular_rows";
  /** Aviso: la ultima fila repetia el encabezado (pie del Text file output) y se quito. */
  static final String PIE_QUITADO = "footer_removed";
  /** Aviso: el encoding declarado no existe en esta JVM; se leyo con el del pack. detail = el nombre. */
  static final String ENCODING_DESCONOCIDO = "unknown_encoding";

  /** Marca de orden de bytes ya decodificada. */
  private static final char BOM = (char) 0xFEFF;
  /** Tope del separador y del encierro (en Kettle son un caracter o unos pocos). */
  private static final int TOPE_MARCA = 64;

  /** Nombres de columna, con trim (del encabezado o de {@link Lectura#nombres}). */
  final List<String> columnas;
  /** Filas del largo de {@link #columnas}; null = celda vacia. Texto tal como se leyo (sin trim). */
  final List<String[]> filas;
  /** Formato que declaro el alumno para cada columna (misma posicion), o null: celdas sin formato. */
  final List<Formato> formatos;
  /** true si habia mas filas que {@link Lectura#maxFilas}: se leyeron solo esas (too_many_rows). */
  boolean cortada;
  /** true si el texto empezaba con BOM. */
  boolean conBom;
  /** Charset con el que se leyo el archivo; null si vino de texto o de filas. */
  String encoding;
  /** Lo que se noto al leer ({@link #FILAS_IRREGULARES}, {@link #PIE_QUITADO}, {@link #ENCODING_DESCONOCIDO}). */
  final List<ResultadoCorreccion.Aviso> avisos = new ArrayList<>();

  private CorrectorTabla( List<String> columnas, List<String[]> filas, List<Formato> formatos ) {
    this.columnas = columnas;
    this.filas = filas;
    this.formatos = formatos;
  }

  /**
   * Formato que el alumno declaro para un campo de su Text file output ({@code <field>}: type, format, currency,
   * decimal, group, nullif). Con el se leen sus numeros: {@code <decimal/>} vacio = el simbolo del locale de la hija
   * (P16: {@code 20,00} en es_ES vale 20).
   */
  static final class Formato {

    /** Tipo de Kettle declarado (String, Number, Integer, Date...); informativo. */
    String tipo;
    /** Mascara de DecimalFormat o SimpleDateFormat ("0.00", "#,##0.00", "dd/MM/yyyy"). */
    String mascara;
    /** Simbolo decimal; vacio = el del locale. */
    String decimal;
    /** Simbolo de miles; vacio = el del locale. */
    String grupo;
    /** Simbolo de moneda que agrega la mascara. */
    String moneda;
    /** Texto que Kettle escribe en lugar de un null (nullif). */
    String nulo;

    Formato() {
    }

    Formato( String tipo, String mascara, String decimal, String grupo, String moneda, String nulo ) {
      this.tipo = tipo;
      this.mascara = mascara;
      this.decimal = decimal;
      this.grupo = grupo;
      this.moneda = moneda;
      this.nulo = nulo;
    }

    /** true si declara algo con que leer numeros (mascara o simbolos). */
    boolean declaraNumero() {
      return !vacio( mascara ) || !vacio( decimal ) || !vacio( grupo );
    }

    @Override
    public String toString() {
      return tipo + "[" + mascara + " dec=" + decimal + " grp=" + grupo + "]";
    }
  }

  /** Como leer un CSV: lo del Text file output del alumno (C4) o {@code expected_csv} del pack ({@link #de}). */
  static final class Lectura {

    /** Separador tal como esta en el .ktr; {@code \t} y {@code $[09]} se traducen. Vacio = una sola columna. */
    String separador = ";";
    /** Encierro; vacio = sin encierro. */
    String encierro = "\"";
    boolean encabezado = true;
    /** El declarado; vacio = {@link #encodingPorDefecto}. */
    String encoding;
    /** El file.encoding del pack (P17); vacio = UTF-8. */
    String encodingPorDefecto = "UTF-8";
    /** Nombres de las columnas si el archivo no tiene encabezado (los campos del step de salida). */
    List<String> nombres;
    /** Formato declarado por posicion de columna; null = sin formato. */
    List<Formato> formatos;
    /** Filas como mucho; 0 = sin tope. */
    long maxFilas;

    Lectura() {
    }

    /** La lectura de los CSV de esperado/ ({@code output.expected_csv}). */
    static Lectura de( Pack.FormatoCsv f ) {
      Lectura l = new Lectura();
      if ( f != null ) {
        l.separador = f.separador == null ? ";" : f.separador;
        l.encierro = f.encierro == null ? "" : f.encierro;
        l.encabezado = f.encabezado;
        l.encoding = f.encoding;
      }
      return l;
    }

    Lectura separador( String s ) {
      separador = s;
      return this;
    }

    Lectura encierro( String s ) {
      encierro = s;
      return this;
    }

    Lectura encabezado( boolean b ) {
      encabezado = b;
      return this;
    }

    Lectura encoding( String declarado, String porDefecto ) {
      encoding = declarado;
      encodingPorDefecto = porDefecto;
      return this;
    }

    Lectura nombres( List<String> l ) {
      nombres = l;
      return this;
    }

    Lectura formatos( List<Formato> l ) {
      formatos = l;
      return this;
    }

    Lectura maxFilas( long n ) {
      maxFilas = n;
      return this;
    }
  }

  // ------------------------------------------------------------------ fabricas

  /** Lee un CSV del disco. */
  static CorrectorTabla leer( File f, Lectura l ) throws IOException {
    try ( InputStream in = new FileInputStream( f ) ) {
      return leer( in, l );
    }
  }

  /** Lee un CSV de un stream (no lo cierra). */
  static CorrectorTabla leer( InputStream in, Lectura l ) throws IOException {
    if ( l == null ) {
      l = new Lectura();
    }
    BufferedInputStream b = new BufferedInputStream( in, 1 << 16 );
    b.mark( 8 );
    byte[] cab = new byte[ 3 ];
    int n = 0;
    while ( n < 3 ) {
      int k = b.read( cab, n, 3 - n );
      if ( k < 0 ) {
        break;
      }
      n += k;
    }
    b.reset();
    List<ResultadoCorreccion.Aviso> avisos = new ArrayList<>();
    Charset declarado = null;
    String nombre = l.encoding == null ? "" : l.encoding.trim();
    if ( !nombre.isEmpty() ) {
      declarado = charset( nombre );
      if ( declarado == null ) {
        avisos.add( new ResultadoCorreccion.Aviso( ENCODING_DESCONOCIDO, nombre ) );
      }
    }
    if ( declarado == null ) {
      String pd = l.encodingPorDefecto == null ? "" : l.encodingPorDefecto.trim();
      declarado = pd.isEmpty() ? null : charset( pd );
      if ( declarado == null ) {
        declarado = StandardCharsets.UTF_8;
      }
    }
    Charset cs = declarado;
    int saltar = 0;
    boolean utf32 = declarado.name().toUpperCase( Locale.ROOT ).startsWith( "UTF-32" );
    if ( n >= 3 && ( cab[ 0 ] & 0xFF ) == 0xEF && ( cab[ 1 ] & 0xFF ) == 0xBB && ( cab[ 2 ] & 0xFF ) == 0xBF ) {
      cs = StandardCharsets.UTF_8;
      saltar = 3;
    } else if ( !utf32 && n >= 2 && ( cab[ 0 ] & 0xFF ) == 0xFE && ( cab[ 1 ] & 0xFF ) == 0xFF ) {
      cs = StandardCharsets.UTF_16BE;
      saltar = 2;
    } else if ( !utf32 && n >= 2 && ( cab[ 0 ] & 0xFF ) == 0xFF && ( cab[ 1 ] & 0xFF ) == 0xFE ) {
      cs = StandardCharsets.UTF_16LE;
      saltar = 2;
    }
    for ( long s = saltar; s > 0; ) {
      long k = b.skip( s );
      if ( k <= 0 ) {
        break;
      }
      s -= k;
    }
    CharsetDecoder dec = cs.newDecoder().onMalformedInput( CodingErrorAction.REPLACE )
      .onUnmappableCharacter( CodingErrorAction.REPLACE );
    CorrectorTabla t = leer( new InputStreamReader( b, dec ), l );
    t.conBom = t.conBom || saltar > 0;
    t.encoding = cs.name();
    t.avisos.addAll( 0, avisos );
    return t;
  }

  /** Lee un CSV que ya es texto (la variante inline del pack, pruebas). Un U+FEFF inicial se saltea. */
  static CorrectorTabla leerTexto( String texto, Lectura l ) {
    try {
      return leer( new StringReader( texto == null ? "" : texto ), l == null ? new Lectura() : l );
    } catch ( IOException e ) {
      throw new IllegalStateException( e ); // StringReader no lanza
    }
  }

  /** La salida esperada de un caso: {@code expected_csv} del pack y, sin encabezado, sus {@code output.columns}. */
  static CorrectorTabla esperada( File csv, Pack pack ) throws IOException {
    return leer( csv, lecturaEsperada( pack ) );
  }

  /** La salida esperada de una tabla (captura tables): {@code expected_csv} y, sin encabezado, esos nombres. */
  static CorrectorTabla esperada( File csv, Pack.FormatoCsv formato, List<String> nombres ) throws IOException {
    Lectura l = Lectura.de( formato );
    l.nombres = nombres;
    return leer( csv, l );
  }

  /** Igual que {@link #esperada(File, Pack)} con el texto del CSV (pack inline). */
  static CorrectorTabla esperadaTexto( String csv, Pack pack ) {
    return leerTexto( csv, lecturaEsperada( pack ) );
  }

  private static Lectura lecturaEsperada( Pack pack ) {
    Lectura l = Lectura.de( pack == null || pack.salida == null ? null : pack.salida.csvEsperado );
    if ( pack != null && pack.salida != null ) {
      l.nombres = pack.salida.nombresColumnas();
    }
    return l;
  }

  /**
   * Una tabla armada con filas ya capturadas (RowListener, JDBC). Cada celda pasa por {@link #texto(Object)}:
   * numeros planos, fechas yyyy-MM-dd; "" = null. Las filas cortas se completan con null.
   */
  static CorrectorTabla de( List<String> columnas, List<? extends List<?>> filas ) {
    List<String> cols = new ArrayList<>();
    if ( columnas != null ) {
      for ( String c : columnas ) {
        cols.add( c == null ? "" : c.trim() );
      }
    }
    List<String[]> fs = new ArrayList<>();
    if ( filas != null ) {
      for ( List<?> f : filas ) {
        String[] r = new String[ cols.size() ];
        for ( int i = 0; f != null && i < r.length && i < f.size(); i++ ) {
          String v = texto( f.get( i ) );
          r[ i ] = v == null || v.isEmpty() ? null : v;
        }
        fs.add( r );
      }
    }
    return new CorrectorTabla( cols, fs, null );
  }

  /**
   * Un valor capturado como texto sin formato (§3 paso 5, captura step/query): BigDecimal plano
   * ({@code 7.5}, nunca {@code 7.5E+1}), fecha {@code yyyy-MM-dd} (con hora si no es medianoche, en la zona de la
   * JVM, que en la hija es la del pack), booleano Y/N, bytes como UTF-8.
   */
  static String texto( Object v ) {
    if ( v == null ) {
      return null;
    }
    if ( v instanceof BigDecimal ) {
      return ( (BigDecimal) v ).toPlainString();
    }
    if ( v instanceof Double || v instanceof Float ) {
      double d = ( (Number) v ).doubleValue();
      return Double.isNaN( d ) || Double.isInfinite( d ) ? String.valueOf( d )
        : new BigDecimal( Double.toString( d ) ).toPlainString();
    }
    if ( v instanceof Number ) {
      return new BigDecimal( v.toString() ).toPlainString();
    }
    if ( v instanceof Date ) {
      Calendar c = Calendar.getInstance();
      c.setTime( (Date) v );
      boolean medianoche = c.get( Calendar.HOUR_OF_DAY ) == 0 && c.get( Calendar.MINUTE ) == 0
        && c.get( Calendar.SECOND ) == 0 && c.get( Calendar.MILLISECOND ) == 0;
      String m = medianoche ? "yyyy-MM-dd" : c.get( Calendar.MILLISECOND ) == 0 ? "yyyy-MM-dd HH:mm:ss"
        : "yyyy-MM-dd HH:mm:ss.SSS";
      return new SimpleDateFormat( m, Locale.ROOT ).format( (Date) v );
    }
    if ( v instanceof Boolean ) {
      return ( (Boolean) v ) ? "Y" : "N";
    }
    if ( v instanceof byte[] ) {
      return new String( (byte[]) v, StandardCharsets.UTF_8 );
    }
    return v.toString();
  }

  // ------------------------------------------------------------------ consultas

  int cantidadFilas() {
    return filas.size();
  }

  /** La celda (null = vacia o fuera de rango). */
  String celda( int fila, int col ) {
    if ( fila < 0 || fila >= filas.size() ) {
      return null;
    }
    String[] f = filas.get( fila );
    return col < 0 || col >= f.length ? null : f[ col ];
  }

  /** El formato declarado de esa columna, o null. */
  Formato formato( int col ) {
    return formatos == null || col < 0 || col >= formatos.size() ? null : formatos.get( col );
  }

  /** Primera columna con ese nombre (sin mayusculas, con trim), o -1. */
  int indice( String nombre ) {
    if ( nombre == null ) {
      return -1;
    }
    String n = nombre.trim();
    for ( int i = 0; i < columnas.size(); i++ ) {
      if ( columnas.get( i ).equalsIgnoreCase( n ) ) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public String toString() {
    return "CorrectorTabla" + columnas + " x " + filas.size() + ( cortada ? " (cortada)" : "" );
  }

  // ------------------------------------------------------------------ lectura

  private static CorrectorTabla leer( Reader r, Lectura l ) throws IOException {
    String sep = traducir( l.separador );
    String enc = l.encierro == null ? "" : l.encierro;
    if ( sep.length() > TOPE_MARCA || enc.length() > TOPE_MARCA ) {
      throw new IOException( "Separador o encierro demasiado largo" );
    }
    if ( !sep.isEmpty() && sep.equals( enc ) ) {
      enc = ""; // no se pueden distinguir: Kettle tampoco
    }
    Cursor c = new Cursor( r );
    boolean bom = false;
    if ( c.ver() == BOM ) {
      c.saltar( 1 );
      bom = true;
    }

    List<String> columnas = null;
    List<String[]> filas = new ArrayList<>();
    int vaciasPendientes = 0;
    int irregulares = 0;
    boolean cortada = false;
    List<Formato> formatos = l.formatos;
    List<String> reg;
    while ( ( reg = registro( c, sep, enc ) ) != null ) {
      boolean vacia = reg.size() == 1 && reg.get( 0 ).isEmpty();
      if ( columnas == null ) {
        if ( vacia ) {
          continue;
        }
        if ( l.encabezado ) {
          columnas = new ArrayList<>();
          for ( String s : reg ) {
            columnas.add( s.replace( String.valueOf( BOM ), "" ).trim() );
          }
          while ( !columnas.isEmpty() && columnas.get( columnas.size() - 1 ).isEmpty() ) {
            columnas.remove( columnas.size() - 1 ); // separador al final de la linea
          }
          continue;
        }
        columnas = nombresSinEncabezado( l.nombres, reg.size() );
      }
      if ( vacia ) {
        vaciasPendientes++;
        continue;
      }
      if ( columnas.size() == 1 ) {
        for ( ; vaciasPendientes > 0; vaciasPendientes-- ) {
          if ( !agregar( filas, new String[ 1 ], l ) ) {
            cortada = true;
            break;
          }
        }
      }
      vaciasPendientes = 0;
      if ( cortada ) {
        break;
      }
      String[] f = new String[ columnas.size() ];
      for ( int i = 0; i < reg.size(); i++ ) {
        String v = reg.get( i );
        if ( i < f.length ) {
          f[ i ] = nulo( v, formatos == null || i >= formatos.size() ? null : formatos.get( i ) );
        } else if ( !v.trim().isEmpty() ) {
          irregulares++;
          break;
        }
      }
      if ( !agregar( filas, f, l ) ) {
        cortada = true;
        break;
      }
    }
    if ( columnas == null ) {
      columnas = nombresSinEncabezado( l.nombres, 0 ); // archivo vacio: las columnas que declaro el step
    }
    CorrectorTabla t = new CorrectorTabla( columnas, filas, formatos );
    t.cortada = cortada;
    t.conBom = bom;
    if ( irregulares > 0 ) {
      t.avisos.add( new ResultadoCorreccion.Aviso( FILAS_IRREGULARES, String.valueOf( irregulares ) ) );
    }
    if ( l.encabezado && !cortada && !filas.isEmpty() && esEncabezado( filas.get( filas.size() - 1 ), columnas ) ) {
      filas.remove( filas.size() - 1 );
      t.avisos.add( new ResultadoCorreccion.Aviso( PIE_QUITADO, null ) );
    }
    return t;
  }

  /** false si la fila no entra por maxFilas. */
  private static boolean agregar( List<String[]> filas, String[] f, Lectura l ) {
    if ( l.maxFilas > 0 && filas.size() >= l.maxFilas ) {
      return false;
    }
    filas.add( f );
    return true;
  }

  private static List<String> nombresSinEncabezado( List<String> nombres, int ancho ) {
    List<String> cols = new ArrayList<>();
    if ( nombres != null && !nombres.isEmpty() ) {
      for ( String n : nombres ) {
        cols.add( n == null ? "" : n.trim() );
      }
      return cols;
    }
    for ( int i = 1; i <= ancho; i++ ) {
      cols.add( "col" + i );
    }
    return cols;
  }

  private static String nulo( String v, Formato f ) {
    if ( v == null || v.isEmpty() ) {
      return null;
    }
    if ( f != null && !vacio( f.nulo ) && v.equals( f.nulo ) ) {
      return null;
    }
    return v;
  }

  private static boolean esEncabezado( String[] fila, List<String> columnas ) {
    if ( columnas.isEmpty() ) {
      return false;
    }
    for ( int i = 0; i < columnas.size(); i++ ) {
      String v = i < fila.length ? fila[ i ] : null;
      if ( !columnas.get( i ).equalsIgnoreCase( v == null ? "" : v.trim() ) ) {
        return false;
      }
    }
    return true;
  }

  /**
   * Un registro: lista de campos sin el encierro. null al final del texto. Campo que empieza con el encierro: hasta
   * el encierro de cierre (doblado = literal); lo que siga hasta el separador se agrega tal cual.
   */
  private static List<String> registro( Cursor c, String sep, String enc ) throws IOException {
    if ( c.ver() < 0 ) {
      return null;
    }
    List<String> campos = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    while ( true ) {
      sb.setLength( 0 );
      if ( !enc.isEmpty() && c.empieza( enc ) ) {
        c.saltar( enc.length() );
        while ( c.ver() >= 0 ) {
          if ( c.empieza( enc ) ) {
            c.saltar( enc.length() );
            if ( c.empieza( enc ) ) {
              sb.append( enc );
              c.saltar( enc.length() );
              continue;
            }
            break;
          }
          sb.append( c.siguiente() );
        }
      }
      int fin; // 0 separador, 1 fin de linea, 2 fin del texto
      while ( true ) {
        int ch = c.ver();
        if ( ch < 0 ) {
          fin = 2;
          break;
        }
        if ( c.empieza( sep ) ) {
          c.saltar( sep.length() );
          fin = 0;
          break;
        }
        if ( ch == '\n' ) {
          c.saltar( 1 );
          fin = 1;
          break;
        }
        if ( ch == '\r' ) {
          c.saltar( 1 );
          if ( c.ver() == '\n' ) {
            c.saltar( 1 );
          }
          fin = 1;
          break;
        }
        sb.append( c.siguiente() );
      }
      campos.add( sb.toString() );
      if ( fin != 0 ) {
        return campos;
      }
    }
  }

  /** {@code \t} -> tab y {@code $[hex]} -> los bytes como caracteres, como el separador de Kettle. */
  static String traducir( String s ) {
    if ( s == null ) {
      return "";
    }
    String t = s.replace( "\\t", "\t" );
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while ( i < t.length() ) {
      int a = t.indexOf( "$[", i );
      int b = a < 0 ? -1 : t.indexOf( ']', a );
      if ( a < 0 || b < 0 ) {
        sb.append( t, i, t.length() );
        break;
      }
      String hex = t.substring( a + 2, b ).replaceAll( "[\\s,]", "" );
      if ( !hex.matches( "([0-9a-fA-F]{2})+" ) ) {
        sb.append( t, i, b + 1 );
        i = b + 1;
        continue;
      }
      sb.append( t, i, a );
      for ( int k = 0; k < hex.length(); k += 2 ) {
        sb.append( (char) Integer.parseInt( hex.substring( k, k + 2 ), 16 ) );
      }
      i = b + 1;
    }
    return sb.toString();
  }

  private static Charset charset( String nombre ) {
    try {
      return Charset.forName( nombre );
    } catch ( RuntimeException e ) {
      return null;
    }
  }

  static boolean vacio( String s ) {
    return s == null || s.isEmpty();
  }

  /** Lector con mirada adelante de unos caracteres (separadores y encierros de mas de un caracter). */
  private static final class Cursor {

    private final Reader r;
    private final char[] buf = new char[ 1 << 14 ];
    private int pos;
    private int lim;
    private boolean fin;

    Cursor( Reader r ) {
      this.r = r;
    }

    private boolean hay( int n ) throws IOException {
      if ( lim - pos >= n ) {
        return true;
      }
      if ( fin ) {
        return false;
      }
      if ( pos > 0 ) {
        System.arraycopy( buf, pos, buf, 0, lim - pos );
        lim -= pos;
        pos = 0;
      }
      while ( lim - pos < n && !fin ) {
        int k = r.read( buf, lim, buf.length - lim );
        if ( k < 0 ) {
          fin = true;
        } else {
          lim += k;
        }
      }
      return lim - pos >= n;
    }

    /** El proximo caracter sin consumirlo, o -1. */
    int ver() throws IOException {
      return hay( 1 ) ? buf[ pos ] : -1;
    }

    boolean empieza( String s ) throws IOException {
      if ( s.isEmpty() || !hay( s.length() ) ) {
        return false;
      }
      for ( int i = 0; i < s.length(); i++ ) {
        if ( buf[ pos + i ] != s.charAt( i ) ) {
          return false;
        }
      }
      return true;
    }

    char siguiente() {
      return buf[ pos++ ];
    }

    void saltar( int n ) {
      pos += n;
    }
  }

  /** Una tabla sin filas con esas columnas (la salida que no escribio nada). */
  static CorrectorTabla vacia( List<String> columnas ) {
    return de( columnas == null ? Collections.<String>emptyList() : columnas, Collections.<List<String>>emptyList() );
  }
}
