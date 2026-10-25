package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaFactory;

import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;

/**
 * Los CSV de las pruebas (0.7.0, P1): los <b>datasets</b> (las filas fijas que se inyectan en lugar de un step) y los
 * <b>dorados</b> (la salida esperada de un step o una tabla).
 * <ul>
 * <li><b>Dataset</b>: coma, comillas dobles, encabezado, UTF-8 sin BOM. Valores sin formato y sin perdida: numeros
 * planos con punto ({@code 1234.5}, nunca {@code 1.2345E3}), fechas {@code yyyy-MM-dd HH:mm:ss.SSS} y timestamps con
 * nanosegundos en la zona de la JVM de la prueba ({@code environment.timezone}), booleanos {@code Y}/{@code N},
 * binarios en base64; vacio = null. Las columnas guardan el tipo y los metadatos del campo original
 * ({@link DefPrueba.ColumnaDataset}): {@link #filaMeta} arma con ellos el RowMeta que se inyecta, asi los steps de
 * despues ven el mismo campo (mascara, simbolos, largo y precision) que con la fuente de verdad.</li>
 * <li><b>Dorado</b>: lo que escribe un {@link CorrectorTabla} capturado (numeros planos, fechas {@code yyyy-MM-dd}
 * con hora si no es medianoche: {@link CorrectorTabla#texto}), con el formato {@code golden_csv} de la prueba; se lee
 * con {@link CorrectorTabla#esperada(File, Pack.FormatoCsv, List)} y se compara con {@link CorrectorComparar}, como
 * la salida esperada de un pack.</li>
 * </ul>
 * Lectura con {@link CorrectorTabla} (encierros, saltos de linea dentro de una celda, BOM). Escritura con un stream
 * propio que siempre se cierra (P12 del corrector).
 *
 * @dueño P1 (0.7.0)
 */
final class PruebasDatasets {

  static final String MASCARA_FECHA = "yyyy-MM-dd HH:mm:ss.SSS";

  private PruebasDatasets() {
  }

  // ------------------------------------------------------------------ dataset: escribir

  /** Las columnas de un dataset con los metadatos de los campos de la salida original. */
  static List<DefPrueba.ColumnaDataset> columnas( RowMetaInterface rm ) {
    List<DefPrueba.ColumnaDataset> l = new ArrayList<DefPrueba.ColumnaDataset>();
    if ( rm == null ) {
      return l;
    }
    for ( ValueMetaInterface vm : rm.getValueMetaList() ) {
      DefPrueba.ColumnaDataset c = new DefPrueba.ColumnaDataset( vm.getName(), tipoDe( vm ) );
      c.largo = vm.getLength() >= 0 ? vm.getLength() : null;
      c.precision = vm.getPrecision() >= 0 ? vm.getPrecision() : null;
      c.formato = vacio( vm.getConversionMask() ) ? null : vm.getConversionMask();
      c.decimal = vacio( vm.getDecimalSymbol() ) ? null : vm.getDecimalSymbol();
      c.grupo = vacio( vm.getGroupingSymbol() ) ? null : vm.getGroupingSymbol();
      c.moneda = vacio( vm.getCurrencySymbol() ) ? null : vm.getCurrencySymbol();
      l.add( c );
    }
    return l;
  }

  /** El nombre de tipo de Kettle; los que no se pueden escribir sin perdida (Serializable, None) pasan a String. */
  static String tipoDe( ValueMetaInterface vm ) {
    switch ( vm.getType() ) {
      case ValueMetaInterface.TYPE_NUMBER:
      case ValueMetaInterface.TYPE_STRING:
      case ValueMetaInterface.TYPE_DATE:
      case ValueMetaInterface.TYPE_BOOLEAN:
      case ValueMetaInterface.TYPE_INTEGER:
      case ValueMetaInterface.TYPE_BIGNUMBER:
      case ValueMetaInterface.TYPE_BINARY:
      case ValueMetaInterface.TYPE_TIMESTAMP:
      case ValueMetaInterface.TYPE_INET:
        return ValueMetaFactory.getValueMetaName( vm.getType() );
      default:
        return "String";
    }
  }

  /** Escribe las filas capturadas (con su RowMeta) como dataset. Devuelve cuantas filas escribio. */
  static int escribirDataset( File f, RowMetaInterface rm, List<Object[]> filas ) throws IOException {
    List<String> nombres = new ArrayList<String>();
    for ( ValueMetaInterface vm : rm.getValueMetaList() ) {
      nombres.add( vm.getName() );
    }
    List<String[]> texto = new ArrayList<String[]>();
    for ( Object[] f0 : filas ) {
      String[] r = new String[ nombres.size() ];
      for ( int i = 0; i < r.length; i++ ) {
        ValueMetaInterface vm = rm.getValueMeta( i );
        r[ i ] = escribirValor( vm, i < f0.length ? f0[ i ] : null );
      }
      texto.add( r );
    }
    escribirCsv( f, nombres, texto, ",", "\"", true, StandardCharsets.UTF_8 );
    return texto.size();
  }

  /** Un valor sin formato y sin perdida (ver la clase); null = celda vacia. */
  static String escribirValor( ValueMetaInterface vm, Object v ) {
    Object x = nativo( vm, v );
    if ( x == null ) {
      return null;
    }
    if ( x instanceof Timestamp ) {
      return x.toString(); // yyyy-mm-dd hh:mm:ss.fffffffff, zona de la JVM
    }
    if ( x instanceof Date ) {
      return fecha().format( (Date) x );
    }
    if ( x instanceof byte[] ) {
      return Base64.getEncoder().encodeToString( (byte[]) x );
    }
    if ( x instanceof InetAddress ) {
      return ( (InetAddress) x ).getHostAddress();
    }
    if ( vm != null && vm.getType() == ValueMetaInterface.TYPE_STRING ) {
      return x.toString();
    }
    return CorrectorTabla.texto( x );
  }

  /** El valor en su tipo de Java (las filas con conversion perezosa traen los bytes del archivo). */
  static Object nativo( ValueMetaInterface vm, Object v ) {
    if ( v == null || vm == null ) {
      return v;
    }
    try {
      return vm.getNativeDataType( v );
    } catch ( Exception e ) {
      return String.valueOf( v );
    }
  }

  // ------------------------------------------------------------------ dataset: leer

  /** Un dataset leido: el RowMeta que se inyecta y las filas ya en su tipo. */
  static final class Filas {
    RowMetaInterface meta;
    final List<Object[]> filas = new ArrayList<Object[]>();
    boolean cortada;
  }

  /**
   * Lee un dataset con sus columnas: las del CSV se emparejan por nombre (sin mayusculas); una columna declarada que
   * no esta en el CSV queda null. Lanza IOException con la fila y la columna de un valor que no se puede leer.
   */
  static Filas leerDataset( File f, List<DefPrueba.ColumnaDataset> cols, long maxFilas ) throws IOException {
    CorrectorTabla.Lectura l = new CorrectorTabla.Lectura().separador( "," ).encierro( "\"" ).encabezado( true )
      .encoding( "UTF-8", "UTF-8" ).maxFilas( maxFilas <= 0 ? 0 : maxFilas );
    CorrectorTabla t = CorrectorTabla.leer( f, l );
    Filas r = new Filas();
    r.cortada = t.cortada;
    List<DefPrueba.ColumnaDataset> columnas = cols;
    if ( columnas == null || columnas.isEmpty() ) { // sin columnas declaradas: todo texto, en el orden del CSV
      columnas = new ArrayList<DefPrueba.ColumnaDataset>();
      for ( String c : t.columnas ) {
        columnas.add( new DefPrueba.ColumnaDataset( c, "String" ) );
      }
    }
    r.meta = filaMeta( columnas );
    int[] indice = new int[ columnas.size() ];
    for ( int i = 0; i < indice.length; i++ ) {
      indice[ i ] = t.indice( columnas.get( i ).nombre );
    }
    for ( int fila = 0; fila < t.filas.size(); fila++ ) {
      Object[] o = new Object[ columnas.size() ];
      for ( int i = 0; i < o.length; i++ ) {
        String v = indice[ i ] < 0 ? null : t.celda( fila, indice[ i ] );
        try {
          o[ i ] = leerValor( r.meta.getValueMeta( i ), v );
        } catch ( Exception e ) {
          throw new IOException( "dataset " + f.getName() + ", row " + ( fila + 1 ) + ", column '" + columnas.get(
            i ).nombre + "': cannot read '" + v + "' as " + columnas.get( i ).tipo + " (" + e.getMessage() + ")" );
        }
      }
      r.filas.add( o );
    }
    return r;
  }

  /** El RowMeta de un dataset: tipo, largo, precision, mascara y simbolos del campo original. */
  static RowMetaInterface filaMeta( List<DefPrueba.ColumnaDataset> cols ) throws IOException {
    RowMeta rm = new RowMeta();
    for ( DefPrueba.ColumnaDataset c : cols ) {
      int tipo = ValueMetaFactory.getIdForValueMeta( c.tipo == null ? "String" : c.tipo.trim() );
      if ( tipo <= 0 ) {
        tipo = ValueMetaInterface.TYPE_STRING;
      }
      ValueMetaInterface vm;
      try {
        vm = ValueMetaFactory.createValueMeta( c.nombre, tipo, c.largo == null ? -1 : c.largo, c.precision == null
          ? -1 : c.precision );
      } catch ( Exception e ) {
        throw new IOException( "cannot create the field '" + c.nombre + "' (" + c.tipo + "): " + e.getMessage() );
      }
      if ( !vacio( c.formato ) ) {
        vm.setConversionMask( c.formato );
      }
      if ( !vacio( c.decimal ) ) {
        vm.setDecimalSymbol( c.decimal );
      }
      if ( !vacio( c.grupo ) ) {
        vm.setGroupingSymbol( c.grupo );
      }
      if ( !vacio( c.moneda ) ) {
        vm.setCurrencySymbol( c.moneda );
      }
      rm.addValueMeta( vm );
    }
    return rm;
  }

  /** Lee un valor escrito por {@link #escribirValor}. */
  static Object leerValor( ValueMetaInterface vm, String v ) throws Exception {
    if ( v == null || v.isEmpty() ) {
      return null;
    }
    switch ( vm.getType() ) {
      case ValueMetaInterface.TYPE_NUMBER:
        return Double.valueOf( v.trim() );
      case ValueMetaInterface.TYPE_INTEGER:
        return Long.valueOf( new BigDecimal( v.trim() ).longValueExact() );
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return new BigDecimal( v.trim() );
      case ValueMetaInterface.TYPE_DATE:
        return fechaFlexible( v.trim() );
      case ValueMetaInterface.TYPE_TIMESTAMP:
        return v.trim().length() <= 10 ? new Timestamp( fechaFlexible( v.trim() ).getTime() ) : Timestamp.valueOf(
          v.trim() );
      case ValueMetaInterface.TYPE_BOOLEAN:
        Boolean b = CorrectorComparar.booleano( v );
        if ( b == null ) {
          throw new IllegalArgumentException( "not a boolean" );
        }
        return b;
      case ValueMetaInterface.TYPE_BINARY:
        return Base64.getDecoder().decode( v.trim() );
      case ValueMetaInterface.TYPE_INET:
        return InetAddress.getByName( v.trim() );
      default:
        return v;
    }
  }

  /** yyyy-MM-dd HH:mm:ss.SSS, o sin milisegundos, o solo la fecha (un dataset escrito a mano). */
  private static Date fechaFlexible( String s ) throws ParseException {
    String[] mascaras = { MASCARA_FECHA, "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm",
      "yyyy-MM-dd" };
    for ( String m : mascaras ) {
      SimpleDateFormat f = new SimpleDateFormat( m, Locale.ROOT );
      f.setLenient( false );
      java.text.ParsePosition p = new java.text.ParsePosition( 0 );
      Date d = f.parse( s, p );
      if ( d != null && p.getIndex() == s.length() ) {
        return d;
      }
    }
    throw new ParseException( "not a date (expected " + MASCARA_FECHA + ")", 0 );
  }

  private static SimpleDateFormat fecha() {
    return new SimpleDateFormat( MASCARA_FECHA, Locale.ROOT );
  }

  // ------------------------------------------------------------------ dorado

  /** Escribe una tabla capturada como CSV dorado con el formato de la prueba. */
  static void escribirDorado( File f, CorrectorTabla t, Pack.FormatoCsv formato ) throws IOException {
    Pack.FormatoCsv fm = formato == null ? DefPrueba.csvPorDefecto() : formato;
    Charset cs;
    try {
      cs = Charset.forName( vacio( fm.encoding ) ? "UTF-8" : fm.encoding.trim() );
    } catch ( Exception e ) {
      cs = StandardCharsets.UTF_8;
    }
    escribirCsv( f, t.columnas, t.filas, CorrectorTabla.traducir( fm.separador == null ? "," : fm.separador ),
      fm.encierro == null ? "" : fm.encierro, fm.encabezado, cs );
  }

  /** Una tabla sin las columnas ignoradas (las dos de la comparacion pasan por aca). */
  static CorrectorTabla sin( CorrectorTabla t, DefPrueba.Salida s ) {
    if ( t == null || s == null || s.ignorar == null || s.ignorar.isEmpty() ) {
      return t;
    }
    List<Integer> quedan = new ArrayList<Integer>();
    List<String> nombres = new ArrayList<String>();
    for ( int i = 0; i < t.columnas.size(); i++ ) {
      if ( !s.ignora( t.columnas.get( i ) ) ) {
        quedan.add( i );
        nombres.add( t.columnas.get( i ) );
      }
    }
    List<List<Object>> filas = new ArrayList<List<Object>>();
    for ( String[] f : t.filas ) {
      List<Object> r = new ArrayList<Object>();
      for ( int i : quedan ) {
        r.add( i < f.length ? f[ i ] : null );
      }
      filas.add( r );
    }
    CorrectorTabla x = CorrectorTabla.de( nombres, filas );
    x.cortada = t.cortada;
    x.avisos.addAll( t.avisos );
    return x;
  }

  /** Los tipos de una salida capturada (para {@code outputs[].columns}): el de cada campo del RowMeta. */
  static List<Pack.Columna> tipos( List<String> columnas, RowMetaInterface rm ) {
    List<Pack.Columna> l = new ArrayList<Pack.Columna>();
    for ( String c : columnas ) {
      ValueMetaInterface vm = rm == null ? null : rm.searchValueMeta( c );
      l.add( new Pack.Columna( c, vm == null ? "String" : tipoDe( vm ) ) );
    }
    return l;
  }

  // ------------------------------------------------------------------ CSV

  /** CSV con encierro solo donde hace falta (separador, encierro, salto de linea, espacios en los bordes). */
  static void escribirCsv( File f, List<String> columnas, List<String[]> filas, String sep, String enc,
                           boolean encabezado, Charset cs ) throws IOException {
    File dir = f.getAbsoluteFile().getParentFile();
    if ( dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
      throw new IOException( "cannot create " + dir );
    }
    StringBuilder b = new StringBuilder();
    if ( encabezado ) {
      linea( b, columnas.toArray( new String[ 0 ] ), sep, enc );
    }
    for ( String[] r : filas ) {
      linea( b, r, sep, enc );
    }
    try ( OutputStream os = new FileOutputStream( f ) ) {
      os.write( b.toString().getBytes( cs ) );
    }
  }

  private static void linea( StringBuilder b, String[] celdas, String sep, String enc ) {
    for ( int i = 0; i < celdas.length; i++ ) {
      if ( i > 0 ) {
        b.append( sep );
      }
      String v = celdas[ i ];
      if ( v == null ) {
        continue;
      }
      boolean encerrar = !enc.isEmpty() && ( v.isEmpty() || v.contains( sep ) || v.contains( enc ) || v.indexOf( '\n' )
        >= 0 || v.indexOf( '\r' ) >= 0 || !v.equals( v.trim() ) );
      if ( encerrar ) {
        b.append( enc ).append( v.replace( enc, enc + enc ) ).append( enc );
      } else {
        b.append( v );
      }
    }
    b.append( '\n' );
  }

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }
}
