package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Criterio;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * La planilla de notas de un lote (docs/corrector-diseno.md §5 «Ajuste manual» y §10 «Planilla»): una fila por
 * entrega con las columnas {@code alumno;[email;]archivo;estado;nota;<un check de la rubrica por columna>;segundos;
 * devolucion;nota_motor;ajuste;comentario} (encabezados en en/es/pt). {@code nota} es la final: {@code nota_motor +
 * ajuste} acotada a [0, escala]; el docente edita {@code ajuste} (un numero con signo: +0,5, -1) y {@code comentario},
 * y al volver a escribir la planilla en la misma carpeta se conservan ({@link #leerAjustes}).
 * <ul>
 * <li><b>CSV</b> para Excel: UTF-8 con BOM, {@code ;}, fin de linea CRLF, coma decimal en es/pt (punto en en). Todo
 * texto va entre comillas (las comillas internas, dobles) y con {@code '} delante si empieza con {@code = + - @}, tab
 * o retorno: el nombre del archivo y la identidad los elige el alumno ({@code =1+1;x.ktr} no corre columnas ni es una
 * formula). Los numeros van sin comillas.</li>
 * <li><b>XLSX</b> con el POI XSSF que el plugin trae en su lib/ (el de Informes), con el context classloader del
 * plugin mientras escribe: hoja de notas (encabezado fijo, autofiltro, {@code nota} como formula sobre
 * {@code nota_motor} y {@code ajuste} con el valor ya calculado, la devolucion como vinculo relativo, el texto que
 * parece formula con «prefijo de comilla») y hoja de resumen. Sin POI ({@link #hayPoi()} false), solo CSV.</li>
 * </ul>
 * Las escrituras van a un temporal al lado y despues se renombran: una planilla abierta en Excel (Windows la traba)
 * hace fallar la escritura sin dejar un archivo a medias. Todo texto de la entrega es dato del alumno.
 *
 * @dueño C6
 */
final class CorrectorPlanilla {

  /** Area de Rotulos (claves planilla.*). */
  static final String AREA = CorrectorNota.AREA;
  static final String CSV = "csv";
  static final String XLSX = "xlsx";
  static final String AMBAS = "both";
  public static final List<String> FORMATOS = Collections.unmodifiableList( Arrays.asList( CSV, XLSX, AMBAS ) );
  /** Nombre de la planilla en la carpeta de salida (notas.csv / notas.xlsx). */
  static final String NOMBRE = "notas";
  /** Empieza una formula en Excel, LibreOffice o Sheets (OWASP «CSV injection»). */
  private static final String INICIO_FORMULA = "=+-@\t\r";
  /** Columnas fijas (claves planilla.col.*), en orden; los checks van entre {@code nota} y {@code segundos}. */
  static final String C_ALUMNO = "alumno";
  static final String C_EMAIL = "email";
  static final String C_ARCHIVO = "archivo";
  static final String C_ESTADO = "estado";
  static final String C_NOTA = "nota";
  static final String C_SEGUNDOS = "segundos";
  static final String C_DEVOLUCION = "devolucion";
  static final String C_NOTA_MOTOR = "nota_motor";
  static final String C_AJUSTE = "ajuste";
  static final String C_COMENTARIO = "comentario";
  private static final List<String> IDIOMAS = Arrays.asList( "en", "es", "pt" );

  private CorrectorPlanilla() {
  }

  // ------------------------------------------------------------------ datos

  /** Una entrega del lote. */
  static final class Fila {

    String alumno;
    /** De alumnos.csv, o null. */
    String email;
    /** Lo que se muestra como archivo (la ruta dentro de la carpeta de entregas). */
    String archivo;
    /** El resultado final, o null si todavia no termino. */
    ResultadoCorreccion resultado;
    /** Estado mientras no hay resultado: {@code pending} o {@code running} (§6). */
    String estadoSinResultado;
    /** La devolucion del alumno, relativa a la carpeta de salida ("ana.md"), o null. */
    String devolucion;
    /** Ajuste manual del docente (con signo), o null. */
    Double ajuste;
    String comentario;

    Fila() {
    }

    Fila( String alumno, String archivo, ResultadoCorreccion r ) {
      this.alumno = alumno;
      this.archivo = archivo;
      this.resultado = r;
    }

    /** La nota del motor, o null (sin resultado). */
    Double notaMotor() {
      return resultado == null ? null : resultado.nota;
    }

    /** {@code nota_motor + ajuste} acotada a [0, escala]; null si no hay nota del motor. */
    Double notaFinal( double escala ) {
      Double m = notaMotor();
      if ( m == null ) {
        return null;
      }
      if ( ajuste == null ) {
        return m;
      }
      double n = Math.max( 0, Math.min( escala, m + ajuste ) );
      return BigDecimal.valueOf( n ).setScale( 2, RoundingMode.HALF_UP ).doubleValue();
    }

    /** status del resultado, o el de mientras corre. */
    String estado() {
      return resultado != null ? resultado.estado : estadoSinResultado;
    }
  }

  /** Lo que se dibuja: el pack (rubrica y escala), el idioma y las filas en orden. */
  static final class Hoja {

    Pack pack;
    String idioma;
    final List<Fila> filas = new ArrayList<Fila>();
  }

  /** {@code summary} del lote (§8): sobre las entregas con nota final. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Resumen {

    /** Entregas con resultado final. */
    @JsonProperty( "graded" )
    public int corregidas;
    @JsonProperty( "mean" )
    public Double media;
    @JsonProperty( "median" )
    public Double mediana;
    /** Nota final mayor o igual a la mitad de la escala. */
    @JsonProperty( "passed" )
    public int aprobadas;
    @JsonProperty( "blocked" )
    public int bloqueadas;
    /** timeout + killed_timeout. */
    @JsonProperty( "timeouts" )
    public int tiempoAgotado;
    /** load_failed + crashed. */
    @JsonProperty( "failed" )
    public int fallidas;
  }

  /** Ajuste y comentario del docente guardados en una planilla anterior. */
  static final class Ajuste {

    Double ajuste;
    String comentario;

    Ajuste( Double ajuste, String comentario ) {
      this.ajuste = ajuste;
      this.comentario = comentario;
    }
  }

  /** El resumen de las filas con resultado (notas finales, con ajuste). */
  static Resumen resumir( List<Fila> filas, double escala ) {
    Resumen r = new Resumen();
    List<Double> notas = new ArrayList<Double>();
    for ( Fila f : filas ) {
      if ( f.resultado == null || !f.resultado.terminada() ) {
        continue;
      }
      r.corregidas++;
      String e = f.resultado.estado;
      if ( ResultadoCorreccion.BLOQUEADA.equals( e ) ) {
        r.bloqueadas++;
      } else if ( ResultadoCorreccion.TIEMPO_AGOTADO.equals( e ) || ResultadoCorreccion.MATADA.equals( e ) ) {
        r.tiempoAgotado++;
      } else if ( ResultadoCorreccion.NO_CARGO.equals( e ) || ResultadoCorreccion.CAIDA.equals( e ) ) {
        r.fallidas++;
      }
      Double n = f.notaFinal( escala );
      if ( n != null ) {
        notas.add( n );
        if ( n >= escala / 2 - 1e-9 ) {
          r.aprobadas++;
        }
      }
    }
    if ( !notas.isEmpty() ) {
      double s = 0;
      for ( double n : notas ) {
        s += n;
      }
      r.media = redondear( s / notas.size() );
      Collections.sort( notas );
      int m = notas.size() / 2;
      r.mediana = redondear( notas.size() % 2 == 1 ? notas.get( m ) : ( notas.get( m - 1 ) + notas.get( m ) ) / 2 );
    }
    return r;
  }

  private static double redondear( double x ) {
    return BigDecimal.valueOf( x ).setScale( 2, RoundingMode.HALF_UP ).doubleValue();
  }

  // ------------------------------------------------------------------ columnas

  /** Una columna de la planilla: su clave (fija o el check) y su encabezado ya traducido. */
  static final class Columna {

    final String clave;
    final String titulo;
    /** El check de la rubrica, o null si es fija. */
    final String check;

    Columna( String clave, String titulo, String check ) {
      this.clave = clave;
      this.titulo = titulo;
      this.check = check;
    }
  }

  /** Las columnas en orden: fijas, un check de la rubrica por columna (su etiqueta) y las del ajuste. */
  static List<Columna> columnas( Hoja h ) {
    Rotulos t = Rotulos.de( AREA, h.idioma );
    List<Columna> l = new ArrayList<Columna>();
    l.add( fija( t, C_ALUMNO ) );
    boolean emails = false;
    for ( Fila f : h.filas ) {
      emails |= f.email != null && !f.email.trim().isEmpty();
    }
    if ( emails ) {
      l.add( fija( t, C_EMAIL ) );
    }
    l.add( fija( t, C_ARCHIVO ) );
    l.add( fija( t, C_ESTADO ) );
    l.add( fija( t, C_NOTA ) );
    Set<String> titulos = new LinkedHashSet<String>();
    for ( ItemRubrica i : h.pack == null || h.pack.rubrica == null ? Collections.<ItemRubrica>emptyList()
      : h.pack.rubrica ) {
      if ( i == null || i.check == null ) {
        continue;
      }
      String titulo = etiqueta( h, i, t );
      if ( !titulos.add( titulo ) ) {
        titulo = titulo + " (" + i.check + ")"; // dos etiquetas iguales: el check las distingue
        titulos.add( titulo );
      }
      l.add( new Columna( i.check, titulo, i.check ) );
    }
    l.add( fija( t, C_SEGUNDOS ) );
    l.add( fija( t, C_DEVOLUCION ) );
    l.add( fija( t, C_NOTA_MOTOR ) );
    l.add( fija( t, C_AJUSTE ) );
    l.add( fija( t, C_COMENTARIO ) );
    return l;
  }

  private static Columna fija( Rotulos t, String clave ) {
    return new Columna( clave, t.s( "planilla.col." + clave ), null );
  }

  /** La etiqueta del check: la del renglon, la de la regla, la de los resultados o la de Rotulos (check.*). */
  static String etiqueta( Hoja h, ItemRubrica i, Rotulos t ) {
    if ( i.etiqueta != null && !i.etiqueta.trim().isEmpty() ) {
      return i.etiqueta.trim();
    }
    if ( i.esRegla() && h.pack != null ) {
      Regla rg = h.pack.regla( i.idRegla() );
      if ( rg != null && rg.etiqueta != null && !rg.etiqueta.trim().isEmpty() ) {
        return rg.etiqueta.trim();
      }
    }
    for ( Fila f : h.filas ) {
      Criterio c = f.resultado == null ? null : f.resultado.criterio( i.check );
      if ( c != null && c.etiqueta != null && !c.etiqueta.trim().isEmpty() ) {
        return c.etiqueta.trim();
      }
    }
    if ( i.esLint() ) {
      return t.f( "check.lint", i.idLint() );
    }
    return t.tiene( "check." + i.check ) ? t.s( "check." + i.check ) : i.check;
  }

  /** "corregida", "bloqueada · step no permitido", "pendiente"... */
  static String textoEstado( Fila f, Rotulos t ) {
    String e = f.estado();
    if ( e == null ) {
      return "";
    }
    String s = t.tiene( "estado." + e ) ? t.s( "estado." + e ) : t.tiene( "lote.estado." + e ) ? t.s( "lote.estado."
      + e ) : e;
    String motivo = f.resultado == null ? null : f.resultado.motivo;
    if ( motivo != null && !motivo.isEmpty() ) {
      s += " · " + ( t.tiene( "motivo." + motivo ) ? t.s( "motivo." + motivo ) : motivo );
    }
    return s;
  }

  /** El valor de una celda: String, Double o null. */
  static Object valor( Hoja h, Fila f, Columna c, Rotulos t ) {
    double escala = h.pack == null ? 10 : h.pack.escala;
    if ( c.check != null ) {
      Criterio cr = f.resultado == null ? null : f.resultado.criterio( c.check );
      return cr == null ? null : (Object) redondear( cr.ganado );
    }
    switch ( c.clave ) {
      case C_ALUMNO:
        return f.alumno;
      case C_EMAIL:
        return f.email;
      case C_ARCHIVO:
        return f.archivo;
      case C_ESTADO:
        return textoEstado( f, t );
      case C_NOTA:
        return f.notaFinal( escala );
      case C_SEGUNDOS:
        return f.resultado == null || f.resultado.tiempos == null ? null : f.resultado.tiempos.procesoSeg;
      case C_DEVOLUCION:
        return f.devolucion;
      case C_NOTA_MOTOR:
        return f.notaMotor();
      case C_AJUSTE:
        return f.ajuste;
      case C_COMENTARIO:
        return f.comentario;
      default:
        return null;
    }
  }

  // ------------------------------------------------------------------ CSV

  /** Escribe la planilla CSV (ver la clase) en destino. */
  static File escribirCsv( Hoja h, File destino ) throws IOException {
    Rotulos t = Rotulos.de( AREA, h.idioma );
    char decimal = "en".equals( Rotulos.normalizar( h.idioma ) ) ? '.' : ',';
    List<Columna> cols = columnas( h );
    StringBuilder b = new StringBuilder( "\uFEFF" );
    for ( int i = 0; i < cols.size(); i++ ) {
      b.append( i == 0 ? "" : ";" ).append( celdaCsv( cols.get( i ).titulo ) );
    }
    b.append( "\r\n" );
    for ( Fila f : h.filas ) {
      for ( int i = 0; i < cols.size(); i++ ) {
        Object v = valor( h, f, cols.get( i ), t );
        b.append( i == 0 ? "" : ";" );
        if ( v instanceof Number ) {
          b.append( numero( ( (Number) v ).doubleValue(), decimal ) );
        } else if ( v != null ) {
          b.append( celdaCsv( v.toString() ) );
        }
      }
      b.append( "\r\n" );
    }
    escribirAtomico( b.toString().getBytes( StandardCharsets.UTF_8 ), destino );
    return destino;
  }

  /**
   * Una celda de texto para el CSV: entre comillas, las comillas internas dobles y, si empieza con algo que Excel
   * toma como formula ({@code = + - @}, tab o retorno), con {@code '} delante.
   */
  static String celdaCsv( String texto ) {
    String s = sinFormula( texto == null ? "" : texto );
    return "\"" + s.replace( "\"", "\"\"" ) + "\"";
  }

  /** El texto con {@code '} delante si empieza como una formula. */
  static String sinFormula( String texto ) {
    return parece( texto ) ? "'" + texto : texto;
  }

  /** Empieza con = + - @, tab o retorno. */
  static boolean parece( String texto ) {
    return texto != null && !texto.isEmpty() && INICIO_FORMULA.indexOf( texto.charAt( 0 ) ) >= 0;
  }

  /** Hasta 2 decimales, sin ceros de mas ni miles, con el separador decimal pedido. */
  static String numero( double d, char decimal ) {
    if ( Double.isNaN( d ) || Double.isInfinite( d ) ) {
      return "";
    }
    String s = BigDecimal.valueOf( d ).setScale( 2, RoundingMode.HALF_UP ).stripTrailingZeros().toPlainString();
    return decimal == '.' ? s : s.replace( '.', decimal );
  }

  // ------------------------------------------------------------------ XLSX

  /** true si POI XSSF esta en el classpath del plugin (en Spoon, su lib/; en las hijas no hace falta). */
  static boolean hayPoi() {
    try {
      Class.forName( "org.apache.poi.xssf.usermodel.XSSFWorkbook", false, CorrectorPlanilla.class.getClassLoader() );
      return true;
    } catch ( Throwable t ) {
      return false;
    }
  }

  /** Escribe la planilla XLSX (ver la clase); IllegalStateException si no hay POI. */
  static File escribirXlsx( Hoja h, File destino ) throws Exception {
    if ( !hayPoi() ) {
      throw new IllegalStateException( "Apache POI is not available: only the CSV gradebook can be written" );
    }
    Thread hilo = Thread.currentThread();
    ClassLoader antes = hilo.getContextClassLoader();
    hilo.setContextClassLoader( CorrectorPlanilla.class.getClassLoader() ); // POI (JAXB, XmlBeans) lo necesita
    try {
      escribirAtomico( Xlsx.escribir( h ), destino );
      return destino;
    } finally {
      hilo.setContextClassLoader( antes );
    }
  }

  /** Todo lo que toca POI: solo se carga si {@link #hayPoi()}. */
  private static final class Xlsx {

    private Xlsx() {
    }

    static byte[] escribir( Hoja h ) throws IOException {
      Rotulos t = Rotulos.de( AREA, h.idioma );
      double escala = h.pack == null ? 10 : h.pack.escala;
      List<Columna> cols = columnas( h );
      try ( org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook() ) {
        org.apache.poi.ss.usermodel.CreationHelper ch = wb.getCreationHelper();
        org.apache.poi.ss.usermodel.Font negrita = wb.createFont();
        negrita.setBold( true );
        org.apache.poi.ss.usermodel.CellStyle encabezado = wb.createCellStyle();
        encabezado.setFont( negrita );
        encabezado.setWrapText( true );
        encabezado.setVerticalAlignment( org.apache.poi.ss.usermodel.VerticalAlignment.TOP );
        org.apache.poi.ss.usermodel.CellStyle texto = wb.createCellStyle();
        org.apache.poi.ss.usermodel.CellStyle comilla = wb.createCellStyle();
        comilla.setQuotePrefixed( true );
        org.apache.poi.ss.usermodel.CellStyle num = wb.createCellStyle();
        num.setDataFormat( ch.createDataFormat().getFormat( "0.0#" ) );
        org.apache.poi.ss.usermodel.CellStyle nota = wb.createCellStyle();
        nota.setDataFormat( ch.createDataFormat().getFormat( "0.0#" ) );
        nota.setFont( negrita );
        org.apache.poi.ss.usermodel.Font azul = wb.createFont();
        azul.setUnderline( org.apache.poi.ss.usermodel.Font.U_SINGLE );
        azul.setColor( org.apache.poi.ss.usermodel.IndexedColors.BLUE.getIndex() );
        org.apache.poi.ss.usermodel.CellStyle vinculo = wb.createCellStyle();
        vinculo.setFont( azul );

        org.apache.poi.ss.usermodel.Sheet s = wb.createSheet( nombreHoja( t.s( "planilla.hoja.notas" ) ) );
        org.apache.poi.ss.usermodel.Row r0 = s.createRow( 0 );
        int iNota = -1;
        int iMotor = -1;
        int iAjuste = -1;
        for ( int i = 0; i < cols.size(); i++ ) {
          org.apache.poi.ss.usermodel.Cell c = r0.createCell( i );
          c.setCellValue( cols.get( i ).titulo );
          c.setCellStyle( parece( cols.get( i ).titulo ) ? comilla : encabezado );
          String k = cols.get( i ).check == null ? cols.get( i ).clave : "";
          iNota = C_NOTA.equals( k ) ? i : iNota;
          iMotor = C_NOTA_MOTOR.equals( k ) ? i : iMotor;
          iAjuste = C_AJUSTE.equals( k ) ? i : iAjuste;
        }
        int fila = 1;
        for ( Fila f : h.filas ) {
          org.apache.poi.ss.usermodel.Row r = s.createRow( fila );
          for ( int i = 0; i < cols.size(); i++ ) {
            Columna col = cols.get( i );
            Object v = valor( h, f, col, t );
            org.apache.poi.ss.usermodel.Cell c = r.createCell( i );
            if ( i == iNota && iMotor >= 0 && iAjuste >= 0 ) {
              String m = ref( fila, iMotor );
              String a = ref( fila, iAjuste );
              c.setCellFormula( "IF(" + m + "=\"\",\"\",IF(ISNUMBER(" + a + "),MIN(" + numero( escala, '.' )
                + ",MAX(0," + m + "+" + a + "))," + m + "))" );
              if ( v instanceof Number ) {
                c.setCellValue( ( (Number) v ).doubleValue() ); // el valor ya calculado (el de la formula)
              } else {
                c.setCellValue( "" );
              }
              c.setCellStyle( nota );
            } else if ( v instanceof Number ) {
              c.setCellValue( ( (Number) v ).doubleValue() );
              c.setCellStyle( num );
            } else if ( v != null ) {
              String x = v.toString();
              c.setCellValue( x );
              if ( C_DEVOLUCION.equals( col.clave ) && col.check == null && !x.isEmpty() ) {
                try {
                  org.apache.poi.ss.usermodel.Hyperlink hl = ch.createHyperlink(
                    org.apache.poi.common.usermodel.HyperlinkType.FILE );
                  hl.setAddress( uriRelativa( x ) ); // "Matías Pérez.md" -> "Mat%C3%ADas%20P%C3%A9rez.md"
                  c.setHyperlink( hl );
                  c.setCellStyle( vinculo );
                } catch ( RuntimeException e ) {
                  c.setCellStyle( parece( x ) ? comilla : texto ); // sin vinculo: queda el nombre
                }
              } else {
                c.setCellStyle( parece( x ) ? comilla : texto );
              }
            }
          }
          fila++;
        }
        s.createFreezePane( 1, 1 );
        if ( !cols.isEmpty() ) {
          s.setAutoFilter( new org.apache.poi.ss.util.CellRangeAddress( 0, Math.max( 0, fila - 1 ), 0,
            cols.size() - 1 ) );
        }
        for ( int i = 0; i < cols.size(); i++ ) {
          s.setColumnWidth( i, ancho( h, cols.get( i ), t ) * 256 );
        }
        resumen( wb, h, t, escala, encabezado, num );
        wb.setForceFormulaRecalculation( true );
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        wb.write( out );
        return out.toByteArray();
      }
    }

    /** Hoja de resumen: los numeros del lote y, por check, cuantos lo aprueban y el promedio de puntos. */
    private static void resumen( org.apache.poi.xssf.usermodel.XSSFWorkbook wb, Hoja h, Rotulos t, double escala,
                                 org.apache.poi.ss.usermodel.CellStyle encabezado,
                                 org.apache.poi.ss.usermodel.CellStyle num ) {
      org.apache.poi.ss.usermodel.Sheet s = wb.createSheet( nombreHoja( t.s( "planilla.hoja.resumen" ) ) );
      Resumen r = resumir( h.filas, escala );
      int fila = 0;
      if ( h.pack != null ) {
        fila = par( s, fila, t.s( "planilla.resumen.ejercicio" ), h.pack.titulo == null ? h.pack.id : h.pack.titulo,
          encabezado, num );
      }
      fila = par( s, fila, t.s( "planilla.resumen.entregas" ), (double) h.filas.size(), encabezado, num );
      fila = par( s, fila, t.s( "planilla.resumen.corregidas" ), (double) r.corregidas, encabezado, num );
      fila = par( s, fila, t.s( "planilla.resumen.media" ), r.media, encabezado, num );
      fila = par( s, fila, t.s( "planilla.resumen.mediana" ), r.mediana, encabezado, num );
      fila = par( s, fila, t.f( "planilla.resumen.aprobadas", numero( escala / 2, '.' ) ), (double) r.aprobadas,
        encabezado, num );
      fila = par( s, fila, t.s( "planilla.resumen.bloqueadas" ), (double) r.bloqueadas, encabezado, num );
      fila = par( s, fila, t.s( "planilla.resumen.tiempo" ), (double) r.tiempoAgotado, encabezado, num );
      fila = par( s, fila, t.s( "planilla.resumen.fallidas" ), (double) r.fallidas, encabezado, num );
      fila++;
      org.apache.poi.ss.usermodel.Row cab = s.createRow( fila++ );
      String[] titulos = { t.s( "planilla.resumen.check" ), t.s( "planilla.resumen.puntos" ), t.s(
        "planilla.resumen.check_aprobado" ), t.s( "planilla.resumen.check_media" ) };
      for ( int i = 0; i < titulos.length; i++ ) {
        org.apache.poi.ss.usermodel.Cell c = cab.createCell( i );
        c.setCellValue( titulos[ i ] );
        c.setCellStyle( encabezado );
      }
      for ( ItemRubrica i : h.pack == null ? Collections.<ItemRubrica>emptyList() : h.pack.rubrica ) {
        if ( i == null || i.check == null ) {
          continue;
        }
        int aprobaron = 0;
        int con = 0;
        double suma = 0;
        for ( Fila f : h.filas ) {
          Criterio c = f.resultado == null ? null : f.resultado.criterio( i.check );
          if ( c != null ) {
            con++;
            suma += c.ganado;
            aprobaron += c.aprobado ? 1 : 0;
          }
        }
        org.apache.poi.ss.usermodel.Row r1 = s.createRow( fila++ );
        org.apache.poi.ss.usermodel.Cell c0 = r1.createCell( 0 );
        String et = etiqueta( h, i, t );
        c0.setCellValue( et );
        if ( parece( et ) ) {
          org.apache.poi.ss.usermodel.CellStyle q = wb.createCellStyle();
          q.setQuotePrefixed( true );
          c0.setCellStyle( q );
        }
        celdaNum( r1, 1, i.puntos, num );
        celdaNum( r1, 2, (double) aprobaron, num );
        celdaNum( r1, 3, con == 0 ? null : redondear( suma / con ), num );
      }
      s.setColumnWidth( 0, 40 * 256 );
      for ( int i = 1; i < 4; i++ ) {
        s.setColumnWidth( i, 16 * 256 );
      }
    }

    private static int par( org.apache.poi.ss.usermodel.Sheet s, int fila, String titulo, Object valor,
                            org.apache.poi.ss.usermodel.CellStyle encabezado,
                            org.apache.poi.ss.usermodel.CellStyle num ) {
      org.apache.poi.ss.usermodel.Row r = s.createRow( fila );
      org.apache.poi.ss.usermodel.Cell c = r.createCell( 0 );
      c.setCellValue( titulo );
      c.setCellStyle( encabezado );
      if ( valor instanceof Number ) {
        celdaNum( r, 1, ( (Number) valor ).doubleValue(), num );
      } else if ( valor != null ) {
        r.createCell( 1 ).setCellValue( valor.toString() );
      }
      return fila + 1;
    }

    private static void celdaNum( org.apache.poi.ss.usermodel.Row r, int col, Double v,
                                  org.apache.poi.ss.usermodel.CellStyle num ) {
      org.apache.poi.ss.usermodel.Cell c = r.createCell( col );
      if ( v != null ) {
        c.setCellValue( v );
        c.setCellStyle( num );
      }
    }

    /** "C5" para fila (0-based) y columna (0-based). */
    private static String ref( int fila, int col ) {
      return new org.apache.poi.ss.util.CellReference( fila, col, false, false ).formatAsString();
    }

    /** Lee alumno → ajuste y comentario de la primera hoja de un .xlsx (encabezado en cualquier idioma). */
    static Map<String, Ajuste> leer( File xlsx ) throws Exception {
      Map<String, Ajuste> m = new LinkedHashMap<String, Ajuste>();
      try ( InputStream in = new FileInputStream( xlsx );
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook( in ) ) {
        if ( wb.getNumberOfSheets() == 0 ) {
          return m;
        }
        org.apache.poi.ss.usermodel.Sheet s = wb.getSheetAt( 0 );
        org.apache.poi.ss.usermodel.Row r0 = s.getRow( 0 );
        if ( r0 == null ) {
          return m;
        }
        List<String> encabezado = new ArrayList<String>();
        for ( int i = 0; i < r0.getLastCellNum(); i++ ) {
          encabezado.add( texto( r0.getCell( i ) ) );
        }
        int[] ix = indices( encabezado );
        if ( ix == null ) {
          return m;
        }
        for ( int f = 1; f <= s.getLastRowNum(); f++ ) {
          org.apache.poi.ss.usermodel.Row r = s.getRow( f );
          if ( r == null ) {
            continue;
          }
          String alumno = texto( r.getCell( ix[ 0 ] ) );
          if ( alumno == null || alumno.trim().isEmpty() ) {
            continue;
          }
          Double aj = null;
          org.apache.poi.ss.usermodel.Cell ca = ix[ 1 ] < 0 ? null : r.getCell( ix[ 1 ] );
          if ( ca != null && ca.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC ) {
            aj = ca.getNumericCellValue();
          } else if ( ca != null ) {
            aj = numeroDe( texto( ca ) );
          }
          String com = ix[ 2 ] < 0 ? null : texto( r.getCell( ix[ 2 ] ) );
          agregarAjuste( m, alumno, aj, com );
        }
      }
      return m;
    }

    private static String texto( org.apache.poi.ss.usermodel.Cell c ) {
      if ( c == null ) {
        return null;
      }
      switch ( c.getCellType() ) {
        case STRING:
          return c.getStringCellValue();
        case NUMERIC:
          return numero( c.getNumericCellValue(), '.' );
        case BOOLEAN:
          return String.valueOf( c.getBooleanCellValue() );
        case FORMULA:
          try {
            return c.getCachedFormulaResultType() == org.apache.poi.ss.usermodel.CellType.NUMERIC ? numero(
              c.getNumericCellValue(), '.' ) : c.getStringCellValue();
          } catch ( RuntimeException e ) {
            return null;
          }
        default:
          return null;
      }
    }
  }

  /** Una ruta relativa como URI (espacios y acentos codificados, / como separador) para un vinculo de Excel. */
  static String uriRelativa( String ruta ) {
    try {
      return new java.net.URI( null, null, ruta.replace( '\\', '/' ), null ).toASCIIString();
    } catch ( java.net.URISyntaxException e ) {
      throw new IllegalArgumentException( e );
    }
  }

  /** Nombre de hoja valido para Excel (31 caracteres, sin : \ / ? * [ ]). */
  static String nombreHoja( String s ) {
    String n = ( s == null ? "" : s ).replaceAll( "[:\\\\/?*\\[\\]]", "_" ).trim();
    if ( n.isEmpty() ) {
      n = "Hoja";
    }
    return n.length() > 31 ? n.substring( 0, 31 ) : n;
  }

  /** Ancho de columna en caracteres: el del encabezado o el del texto mas largo, entre 8 y 50. */
  private static int ancho( Hoja h, Columna c, Rotulos t ) {
    int w = Math.min( 30, c.titulo.length() + 2 );
    for ( Fila f : h.filas ) {
      Object v = valor( h, f, c, t );
      if ( v != null && !( v instanceof Number ) ) {
        w = Math.max( w, v.toString().length() + 2 );
      }
    }
    return Math.max( 8, Math.min( 50, w ) );
  }

  // ------------------------------------------------------------------ ajustes guardados

  /**
   * Ajustes y comentarios del docente de la planilla que ya estaba en la carpeta ({@code notas.csv} o
   * {@code notas.xlsx}, la mas nueva de las dos), por alumno. Encabezados en cualquiera de los tres idiomas. Vacio si
   * no hay planilla o no se puede leer.
   */
  static Map<String, Ajuste> leerAjustes( File dirSalida ) {
    File csv = new File( dirSalida, NOMBRE + "." + CSV );
    File xlsx = new File( dirSalida, NOMBRE + "." + XLSX );
    File f = csv.isFile() && ( !xlsx.isFile() || csv.lastModified() >= xlsx.lastModified() ) ? csv
      : xlsx.isFile() ? xlsx : null;
    if ( f == null ) {
      return new LinkedHashMap<String, Ajuste>();
    }
    try {
      if ( f == xlsx ) {
        if ( !hayPoi() ) {
          return new LinkedHashMap<String, Ajuste>();
        }
        Thread hilo = Thread.currentThread();
        ClassLoader antes = hilo.getContextClassLoader();
        hilo.setContextClassLoader( CorrectorPlanilla.class.getClassLoader() );
        try {
          return Xlsx.leer( f );
        } finally {
          hilo.setContextClassLoader( antes );
        }
      }
      return leerAjustesCsv( f );
    } catch ( Throwable t ) {
      return new LinkedHashMap<String, Ajuste>();
    }
  }

  /**
   * Un CSV que pudo pasar por Excel: UTF-8 (con o sin BOM) si los bytes son UTF-8 valido; si no, windows-1252 (el
   * «CSV delimitado por comas» de Excel en Windows guarda en el codigo de pagina del sistema). Sin el BOM.
   */
  static String leerTexto( File f ) throws IOException {
    byte[] b = Files.readAllBytes( f.toPath() );
    String t;
    try {
      t = StandardCharsets.UTF_8.newDecoder().onMalformedInput( java.nio.charset.CodingErrorAction.REPORT )
        .onUnmappableCharacter( java.nio.charset.CodingErrorAction.REPORT ).decode( java.nio.ByteBuffer.wrap( b ) )
        .toString();
    } catch ( java.nio.charset.CharacterCodingException e ) {
      t = new String( b, java.nio.charset.Charset.forName( "windows-1252" ) );
    }
    return t.startsWith( "\uFEFF" ) ? t.substring( 1 ) : t;
  }

  /** Los ajustes de un CSV de esta planilla (o uno que el docente guardo desde Excel con ; o ,). */
  static Map<String, Ajuste> leerAjustesCsv( File csv ) throws IOException {
    Map<String, Ajuste> m = new LinkedHashMap<String, Ajuste>();
    String texto = leerTexto( csv );
    String primera = texto.split( "\r?\n|\r", 2 )[ 0 ];
    String sep = primera.indexOf( ';' ) >= 0 || primera.indexOf( ',' ) < 0 ? ";" : ",";
    CorrectorTabla tb = CorrectorTabla.leerTexto( texto, new CorrectorTabla.Lectura().separador( sep ).encierro(
      "\"" ).encabezado( true ) );
    int[] ix = indices( tb.columnas );
    if ( ix == null ) {
      return m;
    }
    for ( int f = 0; f < tb.cantidadFilas(); f++ ) {
      String alumno = tb.celda( f, ix[ 0 ] );
      if ( alumno == null || alumno.trim().isEmpty() ) {
        continue;
      }
      agregarAjuste( m, alumno, ix[ 1 ] < 0 ? null : numeroDe( tb.celda( f, ix[ 1 ] ) ), ix[ 2 ] < 0 ? null
        : tb.celda( f, ix[ 2 ] ) );
    }
    return m;
  }

  private static void agregarAjuste( Map<String, Ajuste> m, String alumno, Double ajuste, String comentario ) {
    String c = comentario == null ? null : sinComilla( comentario );
    if ( ajuste == null && ( c == null || c.trim().isEmpty() ) ) {
      return;
    }
    m.put( sinComilla( alumno ), new Ajuste( ajuste, c == null || c.trim().isEmpty() ? null : c ) );
  }

  /** Quita la {@code '} que agrego {@link #sinFormula}. */
  static String sinComilla( String s ) {
    return s != null && s.length() > 1 && s.charAt( 0 ) == '\'' && parece( s.substring( 1 ) ) ? s.substring( 1 ) : s;
  }

  /** alumno, ajuste y comentario en el encabezado (en/es/pt, sin mayusculas); null si no esta la del alumno. */
  private static int[] indices( List<String> encabezado ) {
    int[] ix = { -1, -1, -1 };
    String[] claves = { C_ALUMNO, C_AJUSTE, C_COMENTARIO };
    for ( int k = 0; k < claves.length; k++ ) {
      Set<String> nombres = new LinkedHashSet<String>();
      nombres.add( claves[ k ] );
      for ( String i : IDIOMAS ) {
        nombres.add( Rotulos.de( AREA, i ).s( "planilla.col." + claves[ k ] ).trim().toLowerCase( Locale.ROOT ) );
      }
      for ( int i = 0; i < encabezado.size(); i++ ) {
        String e = encabezado.get( i ) == null ? "" : encabezado.get( i ).replace( "\uFEFF", "" ).trim().toLowerCase(
          Locale.ROOT );
        if ( nombres.contains( e ) && ix[ k ] < 0 ) {
          ix[ k ] = i;
        }
      }
    }
    return ix[ 0 ] < 0 ? null : ix;
  }

  /** "+0,5", "-1", "0.25" → numero; null si esta vacio o no es un numero. */
  static Double numeroDe( String s ) {
    if ( s == null ) {
      return null;
    }
    String t = sinComilla( s.trim() ).replace( ',', '.' ).replace( " ", "" );
    if ( t.startsWith( "+" ) ) {
      t = t.substring( 1 );
    }
    if ( t.isEmpty() ) {
      return null;
    }
    try {
      double d = Double.parseDouble( t );
      return Double.isNaN( d ) || Double.isInfinite( d ) ? null : d;
    } catch ( NumberFormatException e ) {
      return null;
    }
  }

  // ------------------------------------------------------------------ archivos

  /** Escribe primero un temporal al lado y despues lo renombra (una planilla abierta en Excel no queda a medias). */
  static void escribirAtomico( byte[] datos, File destino ) throws IOException {
    File fin = destino.getAbsoluteFile();
    File dir = fin.getParentFile();
    if ( dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
      throw new IOException( "cannot create " + dir );
    }
    File tmp = new File( dir, "." + fin.getName() + "." + System.nanoTime() + ".tmp" );
    try {
      try ( OutputStream os = new FileOutputStream( tmp ) ) {
        os.write( datos );
      }
      try {
        Files.move( tmp.toPath(), fin.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING );
      } catch ( AtomicMoveNotSupportedException e ) {
        Files.move( tmp.toPath(), fin.toPath(), StandardCopyOption.REPLACE_EXISTING );
      }
    } finally {
      if ( tmp.exists() && !tmp.delete() ) {
        tmp.deleteOnExit();
      }
    }
  }
}
