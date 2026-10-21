package io.github.ivi2511.pentahomcp.informes.render;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFHyperlink;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Renderizador;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Excel (.xlsx) con Apache POI 4.1.1 XSSF: una hoja «Indice» con un vinculo a cada hoja, una hoja por cada
 * tabla del informe (y por los datos de cada grafico) y una hoja «Indicadores» con todas las tarjetas. Cada
 * hoja: la cabecera en la fila 1 (inmovilizada y con autofiltro), anchos por contenido y el pie de la tabla
 * debajo.
 * <p>
 * Numeros: solo en columnas alineadas a la derecha y solo si la celda es un numero del idioma del informe
 * ("1.234", "12,5", "12,5 %"): pasa a numero con su formato; lo demas queda como texto tal cual
 * ("≥ 5.000", "2,7 s", "0042", fechas). Formulas: nunca. Un texto que empieza con = + - @ (o tab o retorno)
 * lleva el estilo quotePrefix: Excel lo muestra igual y no lo convierte en formula ni al editarlo.
 * <p>
 * No conoce Kettle ni SWT. Corre en un worker. Escribe primero un ".tmp" y despues lo mueve.
 *
 * @dueño WP10
 */
public final class RenderXlsx implements Renderizador {

  /** Area de Rotulos de este renderizador ({@code textos/xlsx_*.properties}). */
  public static final String AREA = "xlsx";
  /** Clave de {@link Formato} (argumento formats de build_report). */
  public static final String CLAVE = "xlsx";
  /** Excel: 31 caracteres por nombre de hoja y 32.767 por celda. */
  static final int MAX_HOJA = 31;
  static final int MAX_CELDA = 32767;
  static final int ANCHO_MAX_COLUMNA = 60;

  /** Formato.XLSX (se busca por clave). */
  @Override
  public Formato formato() {
    return Formato.XLSX;
  }

  /** null si POI puede escribir Excel en este proceso; si no, el motivo traducido. */
  public static String problema( String idioma ) {
    Rotulos r = Rotulos.de( AREA, idioma );
    String version;
    try {
      Class.forName( "org.apache.poi.xssf.usermodel.XSSFWorkbook", false, RenderXlsx.class.getClassLoader() );
      version = org.apache.xmlbeans.XmlBeans.getVersion();
    } catch ( Throwable t ) {
      return r.f( "xmlbeans_falta", t.getClass().getSimpleName() + ": " + t.getMessage() );
    }
    if ( version == null || !version.startsWith( "3.1" ) ) {
      return r.f( "xmlbeans", String.valueOf( version ) );
    }
    return null;
  }

  @Override
  public Resultado escribir( Informe inf, Tema tema, File destino, Progreso p ) throws Exception {
    if ( inf == null || destino == null ) {
      throw new IllegalArgumentException( "Informe y destino son obligatorios" );
    }
    Tema t = tema == null ? new Tema() : tema;
    Progreso pr = p == null ? Progreso.NULO : p;
    String idioma = Rotulos.normalizar( inf.idioma );
    String prob = problema( idioma );
    if ( prob != null ) {
      throw new IllegalStateException( prob );
    }
    File fin = destino.getAbsoluteFile();
    File carpeta = fin.getParentFile();
    if ( carpeta != null && !carpeta.isDirectory() && !carpeta.mkdirs() && !carpeta.isDirectory() ) {
      throw new IOException( "No se pudo crear la carpeta " + carpeta );
    }
    File tmp = new File( carpeta, fin.getName() + ".tmp" );
    Thread hilo = Thread.currentThread();
    ClassLoader antes = hilo.getContextClassLoader();
    hilo.setContextClassLoader( RenderXlsx.class.getClassLoader() );
    try ( XSSFWorkbook wb = new XSSFWorkbook() ) {
      Libro l = new Libro( wb, inf, t, pr );
      l.armar();
      if ( pr.cancelado() ) {
        throw new CancellationException( "Excel" );
      }
      pr.paso( Rotulos.de( AREA, idioma ).s( "progreso_escribir" ), 0, 0 );
      try ( OutputStream os = new FileOutputStream( tmp ) ) {
        wb.write( os );
      }
      try {
        Files.move( tmp.toPath(), fin.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
      } catch ( AtomicMoveNotSupportedException e ) {
        Files.move( tmp.toPath(), fin.toPath(), StandardCopyOption.REPLACE_EXISTING );
      }
      Resultado r = new Resultado().archivo( fin );
      if ( l.indice.isEmpty() ) {
        r.avisos.add( Rotulos.de( AREA, idioma ).s( "aviso_sin_tablas" ) );
      }
      return r;
    } finally {
      if ( tmp.exists() && !tmp.delete() ) {
        tmp.deleteOnExit();
      }
      hilo.setContextClassLoader( antes );
    }
  }

  // ==================================================================================== el libro

  /** Estado de una escritura. */
  static final class Libro {
    final XSSFWorkbook wb;
    final Informe inf;
    final Tema tema;
    final Progreso progreso;
    final String idioma;
    final Rotulos r;
    final Rotulos rr;
    final Locale locale;
    final Pattern numero;
    final char miles;
    final char decimal;
    final Set<String> hojas = new HashSet<>();
    final XSSFCellStyle titulo;
    final XSSFCellStyle subtitulo;
    final XSSFCellStyle cabecera;
    final XSSFCellStyle texto;
    final XSSFCellStyle textoSeguro;
    final XSSFCellStyle vinculo;
    final XSSFCellStyle nota;
    /** Filas del indice: seccion, tabla, filas, hoja. */
    final List<Object[]> indice = new ArrayList<>();
    /** Tarjetas: seccion, indicador, valor, detalle. */
    final List<String[]> tarjetas = new ArrayList<>();
    private final Map<String, XSSFCellStyle> formatos = new HashMap<>();
    private XSSFCellStyle derechaTexto;
    private XSSFCellStyle derechaSeguro;

    Libro( XSSFWorkbook wb, Informe inf, Tema tema, Progreso p ) {
      this.wb = wb;
      this.inf = inf;
      this.tema = tema;
      this.progreso = p;
      this.idioma = Rotulos.normalizar( inf.idioma );
      this.r = Rotulos.de( AREA, idioma );
      this.rr = Rotulos.de( RenderHtml.AREA, idioma );
      this.locale = Rotulos.locale( idioma );
      this.miles = "en".equals( idioma ) ? ',' : '.';
      this.decimal = "en".equals( idioma ) ? '.' : ',';
      String m = Pattern.quote( String.valueOf( miles ) );
      String d = Pattern.quote( String.valueOf( decimal ) );
      // 1.234.567 | 1234567 | con decimales | con " %" al final
      this.numero = Pattern.compile( "^(-?(?:\\d{1,3}(?:" + m + "\\d{3})+|\\d+)(?:" + d + "(\\d+))?)(\\s?%)?$" );
      Tema.Colores c = tema.colores == null ? new Tema.Colores() : tema.colores;
      XSSFColor primario = color( Html.color( c.primario, Tema.PRIMARIO ) );
      XSSFColor acento = color( Html.color( c.acento, Tema.ACENTO ) );
      titulo = estilo( fuente( true, 14, primario, false ) );
      subtitulo = estilo( fuente( false, 10, color( Tema.TEXTO_TENUE ), false ) );
      cabecera = estilo( fuente( true, 10, color( "#FFFFFF" ), false ) );
      cabecera.setFillForegroundColor( primario );
      cabecera.setFillPattern( FillPatternType.SOLID_FOREGROUND );
      cabecera.setVerticalAlignment( VerticalAlignment.CENTER );
      cabecera.setWrapText( true );
      texto = estilo( fuente( false, 10, null, false ) );
      texto.setVerticalAlignment( VerticalAlignment.TOP );
      texto.setBorderBottom( BorderStyle.HAIR );
      texto.setBottomBorderColor( color( Tema.BORDE ) );
      textoSeguro = wb.createCellStyle();
      textoSeguro.cloneStyleFrom( texto );
      textoSeguro.setQuotePrefixed( true );
      vinculo = estilo( fuente( false, 10, acento, true ) );
      nota = estilo( fuente( false, 9, color( Tema.TEXTO_TENUE ), false ) );
      nota.getFont().setItalic( true );
    }

    void armar() {
      propiedades();
      XSSFSheet ind = hoja( r.s( "hoja_indice" ) );
      List<Seccion> vivas = RenderDocx.vivas( RenderDocx.cuerpo( inf ) );
      int n = 0;
      for ( int k = 0; k < vivas.size(); k++ ) {
        Seccion s = vivas.get( k );
        if ( progreso.cancelado() ) {
          return;
        }
        String sec = !PptxDiapos.vacio( s.titulo ) ? s.titulo.trim() : SeccionId.titulo( s.id, idioma );
        progreso.paso( r.f( "progreso", sec ), k, vivas.size() );
        for ( Bloque b : Html.lista( s.bloques ) ) {
          Tabla t = null;
          if ( b instanceof Tabla ) {
            t = (Tabla) b;
          } else if ( b instanceof Grafico ) {
            t = RenderHtml.tablaDeGrafico( (Grafico) b, rr, locale );
          } else if ( b instanceof Indicadores ) {
            for ( Indicador i : Html.lista( ( (Indicadores) b ).items ) ) {
              if ( i != null ) {
                tarjetas.add( new String[] { sec, i.etiqueta, i.valor, i.detalle } );
              }
            }
          }
          if ( t == null || ( Html.lista( t.filas ).isEmpty() && Html.lista( t.columnas ).isEmpty() ) ) {
            continue;
          }
          n++;
          String nombreTabla = !PptxDiapos.vacio( b.titulo ) ? b.titulo.trim() : sec;
          XSSFSheet h = hoja( String.format( Locale.ROOT, "%02d ", n ) + nombreTabla );
          tabla( h, t );
          indice.add( new Object[] { sec, nombreTabla, Html.lista( t.filas ).size(), h.getSheetName() } );
        }
      }
      if ( !tarjetas.isEmpty() ) {
        XSSFSheet h = hoja( r.s( "hoja_indicadores" ) );
        Tabla t = new Tabla( r.s( "col_seccion" ), rr.s( "indicator" ), rr.s( "value" ), r.s( "col_detalle" ) );
        for ( String[] x : tarjetas ) {
          t.fila( x );
        }
        tabla( h, t );
        indice.add( new Object[] { r.s( "hoja_indicadores" ), r.s( "hoja_indicadores" ), t.filas.size(), h
          .getSheetName() } );
      }
      llenarIndice( ind );
      wb.setActiveSheet( 0 );
    }

    void propiedades() {
      try {
        POIXMLProperties.CoreProperties cp = wb.getProperties().getCoreProperties();
        cp.setTitle( PptxDiapos.limpio( nombreInforme() ) );
        String autor = tema.identidad == null ? null : tema.identidad.autor;
        cp.setCreator( PptxDiapos.vacio( autor ) ? Tema.PIE : PptxDiapos.limpio( autor ) );
        wb.getProperties().getExtendedProperties().getUnderlyingProperties().setApplication( "Pentaho Live" );
      } catch ( RuntimeException e ) {
        // las propiedades son un extra
      }
    }

    String nombreInforme() {
      return !PptxDiapos.vacio( inf.titulo ) ? inf.titulo.trim() : !PptxDiapos.vacio( inf.nombreDoc ) ? inf.nombreDoc
        .trim() : rr.s( "report" );
    }

    /** Titulo, subtitulo y fecha, y la lista de hojas con un vinculo a cada una. */
    void llenarIndice( XSSFSheet h ) {
      int f = 0;
      celda( h.createRow( f++ ), 0, nombreInforme(), titulo );
      String fecha = RenderHtml.fechaLarga( inf.generado, locale );
      String sub = ( PptxDiapos.vacio( inf.subtitulo ) ? "" : inf.subtitulo.trim() )
        + ( fecha == null ? "" : ( PptxDiapos.vacio( inf.subtitulo ) ? "" : " · " ) + rr.f( "generated_on", fecha ) );
      if ( !sub.isEmpty() ) {
        celda( h.createRow( f++ ), 0, sub, subtitulo );
      }
      f++;
      String[] cols = { r.s( "col_n" ), r.s( "col_seccion" ), r.s( "col_tabla" ), r.s( "col_filas" ), r.s( "col_hoja" ) };
      int cab = f;
      Row rc = h.createRow( f++ );
      for ( int j = 0; j < cols.length; j++ ) {
        celda( rc, j, cols[ j ], cabecera );
      }
      int k = 0;
      for ( Object[] x : indice ) {
        Row row = h.createRow( f++ );
        Cell n = row.createCell( 0 );
        n.setCellValue( ++k );
        n.setCellStyle( texto );
        celda( row, 1, (String) x[ 0 ], texto );
        celda( row, 2, (String) x[ 1 ], texto );
        Cell filas = row.createCell( 3 );
        filas.setCellValue( ( (Integer) x[ 2 ] ).doubleValue() );
        filas.setCellStyle( texto );
        Cell v = row.createCell( 4 );
        v.setCellValue( (String) x[ 3 ] );
        XSSFHyperlink link = wb.getCreationHelper().createHyperlink( HyperlinkType.DOCUMENT );
        link.setAddress( "'" + ( (String) x[ 3 ] ).replace( "'", "''" ) + "'!A1" );
        v.setHyperlink( link );
        v.setCellStyle( vinculo );
      }
      if ( indice.isEmpty() ) {
        celda( h.createRow( f ), 0, r.s( "aviso_sin_tablas" ), nota );
      }
      int[] anchos = { 6, 26, 44, 8, 32 };
      for ( int j = 0; j < anchos.length; j++ ) {
        h.setColumnWidth( j, anchos[ j ] * 256 );
      }
      h.createFreezePane( 0, cab + 1 );
    }

    /** La tabla en la hoja: cabecera en la fila 1, inmovilizada y con autofiltro. */
    void tabla( XSSFSheet h, Tabla t ) {
      List<String> cols = Html.lista( t.columnas );
      List<List<String>> filas = Html.lista( t.filas );
      int nCol = cols.size();
      for ( List<String> f : filas ) {
        nCol = Math.max( nCol, f == null ? 0 : f.size() );
      }
      int[] largo = new int[ nCol ];
      int f = 0;
      if ( !cols.isEmpty() ) {
        Row rc = h.createRow( f++ );
        rc.setHeightInPoints( 18 );
        for ( int j = 0; j < nCol; j++ ) {
          String v = j < cols.size() ? cols.get( j ) : "";
          celda( rc, j, v == null ? "" : v, cabecera );
          largo[ j ] = Math.max( largo[ j ], v == null ? 0 : v.length() + 2 );
        }
      }
      for ( List<String> fila : filas ) {
        Row row = h.createRow( f++ );
        for ( int j = 0; j < nCol; j++ ) {
          String v = fila == null || j >= fila.size() ? null : fila.get( j );
          boolean derecha = derecha( t.alineacion, j );
          if ( v != null && derecha && numero( row, j, v.trim() ) ) {
            largo[ j ] = Math.max( largo[ j ], v.length() );
            continue;
          }
          Cell c = celda( row, j, v, null );
          if ( derecha && c != null ) {
            c.setCellStyle( derecha( (XSSFCellStyle) c.getCellStyle() ) );
          }
          largo[ j ] = Math.max( largo[ j ], maxLinea( v ) );
        }
      }
      for ( int j = 0; j < nCol; j++ ) {
        h.setColumnWidth( j, Math.max( 6, Math.min( ANCHO_MAX_COLUMNA, largo[ j ] + 2 ) ) * 256 );
      }
      if ( !cols.isEmpty() ) {
        h.createFreezePane( 0, 1 );
        if ( f > 1 && nCol > 0 ) {
          h.setAutoFilter( new CellRangeAddress( 0, f - 1, 0, nCol - 1 ) );
        }
      }
      if ( !PptxDiapos.vacio( t.pie ) ) {
        f++;
        celda( h.createRow( f ), 0, t.pie.trim(), nota );
      }
    }

    /** El estilo alineado a la derecha; getCellStyle() da un objeto nuevo cada vez: se compara el indice. */
    private XSSFCellStyle derecha( XSSFCellStyle s ) {
      if ( s.getIndex() == textoSeguro.getIndex() ) {
        if ( derechaSeguro == null ) {
          derechaSeguro = wb.createCellStyle();
          derechaSeguro.cloneStyleFrom( textoSeguro );
          derechaSeguro.setAlignment( HorizontalAlignment.RIGHT );
        }
        return derechaSeguro;
      }
      if ( derechaTexto == null ) {
        derechaTexto = wb.createCellStyle();
        derechaTexto.cloneStyleFrom( texto );
        derechaTexto.setAlignment( HorizontalAlignment.RIGHT );
      }
      return derechaTexto;
    }

    /** Si el texto es un numero del idioma ("1.234", "12,5", "12,5 %"), lo escribe como numero. */
    boolean numero( Row row, int j, String v ) {
      Matcher m = numero.matcher( v );
      if ( !m.matches() || m.group( 1 ).matches( "-?0\\d.*" ) ) {
        return false; // "0042" es un codigo, no un numero: queda como texto (con sus ceros)
      }
      String digitos = m.group( 1 ).replace( String.valueOf( miles ), "" ).replace( decimal, '.' );
      double d;
      try {
        d = Double.parseDouble( digitos );
      } catch ( NumberFormatException e ) {
        return false;
      }
      int decimales = m.group( 2 ) == null ? 0 : m.group( 2 ).length();
      boolean pct = m.group( 3 ) != null;
      String fmt = ( decimales == 0 ? "#,##0" : "#,##0." + repetir( '0', decimales ) ) + ( pct ? "\" %\"" : "" );
      XSSFCellStyle s = formatos.get( fmt );
      if ( s == null ) {
        s = wb.createCellStyle();
        s.cloneStyleFrom( texto );
        s.setAlignment( HorizontalAlignment.RIGHT );
        s.setDataFormat( wb.createDataFormat().getFormat( fmt ) );
        formatos.put( fmt, s );
      }
      Cell c = row.createCell( j );
      c.setCellValue( d );
      c.setCellStyle( s );
      return true;
    }

    /** Una celda de texto; si empieza como formula, con quotePrefix. null (o "" sin estilo) = sin celda. */
    Cell celda( Row row, int j, String v, CellStyle estilo ) {
      if ( v == null || ( v.isEmpty() && estilo == null ) ) {
        return null; // celda vacia: sin celda (los filtros y Ctrl+flecha andan mejor)
      }
      String s = PptxDiapos.limpio( v );
      if ( s.length() > MAX_CELDA ) {
        s = s.substring( 0, MAX_CELDA - 1 ) + "…";
      }
      Cell c = row.createCell( j );
      c.setCellValue( s );
      boolean formula = !s.isEmpty() && "=+-@\t\r".indexOf( s.charAt( 0 ) ) >= 0;
      if ( !formula ) {
        c.setCellStyle( estilo == null ? texto : estilo );
      } else if ( estilo == null || estilo == texto ) {
        c.setCellStyle( textoSeguro );
      } else {
        String clave = "q" + estilo.getIndex();
        XSSFCellStyle q = formatos.get( clave );
        if ( q == null ) {
          q = wb.createCellStyle();
          q.cloneStyleFrom( estilo );
          q.setQuotePrefixed( true );
          formatos.put( clave, q );
        }
        c.setCellStyle( q );
      }
      return c;
    }

    /** Hoja con nombre valido (31 caracteres, sin []:*?/\ ni ' al principio o al final) y unico. */
    XSSFSheet hoja( String deseado ) {
      String base = WorkbookUtil.createSafeSheetName( PptxDiapos.limpio( deseado ).replace( ": ", " - " )
        .replace( '«', '"' ).replace( '»', '"' ).replace( "\"", "" ), '_' ).trim();
      while ( base.startsWith( "'" ) ) {
        base = base.substring( 1 );
      }
      if ( base.length() > MAX_HOJA ) {
        base = base.substring( 0, MAX_HOJA ).trim();
      }
      while ( base.endsWith( "'" ) ) {
        base = base.substring( 0, base.length() - 1 );
      }
      if ( base.isEmpty() ) {
        base = "Hoja";
      }
      String n = base;
      int i = 2;
      while ( !hojas.add( n.toLowerCase( Locale.ROOT ) ) ) {
        String suf = " (" + i++ + ")";
        n = ( base.length() + suf.length() > MAX_HOJA ? base.substring( 0, MAX_HOJA - suf.length() ) : base ) + suf;
      }
      return wb.createSheet( n );
    }

    XSSFCellStyle estilo( Font f ) {
      XSSFCellStyle s = wb.createCellStyle();
      s.setFont( f );
      return s;
    }

    XSSFFont fuente( boolean negrita, int puntos, XSSFColor color, boolean subrayado ) {
      XSSFFont f = wb.createFont();
      f.setFontName( "Segoe UI" );
      f.setFontHeightInPoints( (short) puntos );
      f.setBold( negrita );
      if ( color != null ) {
        f.setColor( color );
      }
      if ( subrayado ) {
        f.setUnderline( Font.U_SINGLE );
      }
      return f;
    }

    static XSSFColor color( String hex ) {
      java.awt.Color c = PptxDiapos.color( hex, "#000000" );
      return new XSSFColor( new byte[] { (byte) c.getRed(), (byte) c.getGreen(), (byte) c.getBlue() }, null );
    }

    private static boolean derecha( List<String> al, int j ) {
      return al != null && j < al.size() && al.get( j ) != null && "r".equalsIgnoreCase( al.get( j ).trim() );
    }

    private static int maxLinea( String v ) {
      int m = 0;
      for ( String l : ( v == null ? "" : v ).split( "\r?\n" ) ) {
        m = Math.max( m, l.length() );
      }
      return m;
    }

    private static String repetir( char c, int n ) {
      StringBuilder sb = new StringBuilder();
      for ( int i = 0; i < n; i++ ) {
        sb.append( c );
      }
      return sb.toString();
    }
  }
}
