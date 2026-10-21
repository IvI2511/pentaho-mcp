package io.github.ivi2511.pentahomcp.informes.render;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.Combinar;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Excel de WP10 sobre ModeloMuestra, sin Kettle: POI lo vuelve a leer y se verifican las hojas (indice con
 * vinculos, una por tabla y por grafico, indicadores), nombres validos y unicos, cabecera inmovilizada con
 * autofiltro, numeros del idioma convertidos solo en columnas a la derecha, y que ningun texto que empieza
 * con = + - @ se vuelva formula. Los archivos quedan en {@code <Salida>\prueba\salida\xlsx}.
 *
 * @dueño WP10
 */
public class PruebaXlsx {

  private File carpeta;

  public void preparar() {
    carpeta = AyudaOffice.carpeta( "xlsx" );
  }

  public void pruebaMuestra() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "muestra.xlsx" );
    Resultado res = new RenderXlsx().escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
    archivo( f, 5000, "xlsx escrito" );
    igual( 1, res.archivos.size(), "un solo archivo" );
    igual( f.length(), res.bytes, "bytes" );
    igual( new ArrayList<String>(), res.avisos, "sin avisos" );
    falso( new File( carpeta, f.getName() + ".tmp" ).exists(), "no queda el .tmp" );

    int tablas = 0;
    int graficos = 0;
    for ( Seccion s : inf.secciones ) {
      if ( Seccion.OMITIDA.equals( s.estado ) || SeccionId.PORTADA.equals( s.id ) ) {
        continue;
      }
      for ( Bloque b : s.bloques ) {
        tablas += b instanceof Tabla ? 1 : 0;
        graficos += b instanceof Grafico ? 1 : 0;
      }
    }
    try ( XSSFWorkbook wb = abrir( f ) ) {
      igual( 1 + tablas + graficos + 1, wb.getNumberOfSheets(), "indice + tablas + graficos + indicadores" );
      igual( "Índice", wb.getSheetName( 0 ), "primera hoja" );
      igual( "Indicadores", wb.getSheetName( wb.getNumberOfSheets() - 1 ), "ultima hoja" );
      igual( 0, wb.getActiveSheetIndex(), "abre en el indice" );
      igual( "01 Qué hace cada step", wb.getSheetName( 1 ), "hojas numeradas con el titulo de la tabla" );
      igual( "02 Linaje de campos", wb.getSheetName( 2 ), "tabla sin titulo: el de la seccion" );

      // indice: titulo, un vinculo por hoja que existe
      XSSFSheet ind = wb.getSheetAt( 0 );
      igual( inf.titulo, ind.getRow( 0 ).getCell( 0 ).getStringCellValue(), "titulo en el indice" );
      contiene( ind.getRow( 1 ).getCell( 0 ).getStringCellValue(), "Generado el 20 de octubre de 2026",
        "fecha en el indice" );
      int vinculos = 0;
      for ( Row r : ind ) {
        Cell c = r.getCell( 4 );
        if ( c != null && c.getHyperlink() != null ) {
          vinculos++;
          igual( HyperlinkType.DOCUMENT, c.getHyperlink().getType(), "vinculo interno" );
          String hoja = c.getStringCellValue();
          noNulo( wb.getSheet( hoja ), "el vinculo apunta a una hoja que existe: " + hoja );
          igual( "'" + hoja.replace( "'", "''" ) + "'!A1", c.getHyperlink().getAddress(), "direccion del vinculo" );
        }
      }
      igual( wb.getNumberOfSheets() - 1, vinculos, "un vinculo por hoja" );

      // cada hoja de datos: cabecera inmovilizada y autofiltro
      for ( int i = 1; i < wb.getNumberOfSheets(); i++ ) {
        XSSFSheet h = wb.getSheetAt( i );
        PaneInformation pi = h.getPaneInformation();
        noNulo( pi, "panel inmovilizado en " + h.getSheetName() );
        igual( (short) 1, pi.getHorizontalSplitPosition(), "fila 1 inmovilizada en " + h.getSheetName() );
        cierto( h.getCTWorksheet().isSetAutoFilter(), "autofiltro en " + h.getSheetName() );
      }

      // diccionario: Largo y Precision (a la derecha) como numeros; Mascara (izquierda) como texto
      XSSFSheet dic = hoja( wb, "Escribir CSV" );
      Row r1 = dic.getRow( 1 );
      igual( CellType.NUMERIC, r1.getCell( 2 ).getCellType(), "Largo 30 es numero" );
      igual( 30.0, r1.getCell( 2 ).getNumericCellValue(), "valor 30" );
      igual( "#,##0.00", dic.getRow( 2 ).getCell( 4 ).getStringCellValue(), "mascara como texto" );
      contiene( dic.getRow( dic.getLastRowNum() ).getCell( 0 ).getStringCellValue(), "sin límite", "pie de la tabla" );
      igual( ModeloMuestra.STEP_RARO, dic.getRow( 4 ).getCell( 5 ).getStringCellValue(), "texto raro literal" );

      // perfil: "12,3" -> 12.3; "≥ 5.000" queda texto; "0" -> 0
      XSSFSheet perfil = hoja( wb, "Perfil de datos" );
      igual( 12.3, perfil.getRow( 2 ).getCell( 6 ).getNumericCellValue(), "media 12,3" );
      igual( CellType.STRING, perfil.getRow( 3 ).getCell( 3 ).getCellType(), "≥ 5.000 como texto" );
      igual( 0.0, perfil.getRow( 1 ).getCell( 2 ).getNumericCellValue(), "nulos 0" );
      // metricas: "400.000" -> 400000 con formato de miles; "1,7 s" texto
      XSSFSheet met = hoja( wb, "Métricas de ejecución" );
      XSSFCell escritas = met.getRow( 1 ).getCell( 3 );
      igual( 400000.0, escritas.getNumericCellValue(), "400.000 como numero" );
      igual( "#,##0", escritas.getCellStyle().getDataFormatString(), "formato de miles" );
      igual( CellType.STRING, met.getRow( 1 ).getCell( 6 ).getCellType(), "1,7 s como texto" );

      // indicadores: seccion, indicador, valor, detalle
      XSSFSheet tarjetas = wb.getSheetAt( wb.getNumberOfSheets() - 1 );
      igual( "Sección", tarjetas.getRow( 0 ).getCell( 0 ).getStringCellValue(), "cabecera de indicadores" );
      igual( 4 + 6 + 4, tarjetas.getLastRowNum(), "4 indicadores del resumen, 6 de metricas y 4 de calidad" );
      boolean hallazgos = false;
      for ( int i = 0; i < wb.getNumberOfSheets(); i++ ) {
        hallazgos = hallazgos || wb.getSheetName( i ).endsWith( " Hallazgos" );
      }
      cierto( hallazgos, "la tabla de hallazgos de la calidad tiene su hoja" );
      sinFormulas( wb );
    }
  }

  /**
   * Informe combinado de dos documentos: una hoja por tabla de cada documento (mas la de documentos de la
   * parte general), nombres unicos, y en el indice la seccion con su numero «1.1» / «2.1».
   */
  public void pruebaCombinado() throws Exception {
    Informe a = ModeloMuestra.crear();
    Informe b = ModeloMuestra.crear();
    b.titulo = "Segundo documento";
    Informe inf = Combinar.combinar( Arrays.asList( a, b ), null, "es", ModeloMuestra.tema() );
    File f = new File( carpeta, "combinado.xlsx" );
    new RenderXlsx().escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
    int hojasUno;
    File g = new File( carpeta, "uno-para-combinado.xlsx" );
    new RenderXlsx().escribir( ModeloMuestra.crear(), ModeloMuestra.tema(), g, Progreso.NULO );
    try ( XSSFWorkbook wb = abrir( g ) ) {
      hojasUno = wb.getNumberOfSheets() - 2; // sin indice ni indicadores
    }
    try ( XSSFWorkbook wb = abrir( f ) ) {
      igual( 1 + 1 + 2 * hojasUno + 1, wb.getNumberOfSheets(), "indice + documentos + las de cada parte + indicadores" );
      Set<String> nombres = new HashSet<>();
      for ( int i = 0; i < wb.getNumberOfSheets(); i++ ) {
        cierto( nombres.add( wb.getSheetName( i ).toLowerCase( Locale.ROOT ) ), "hoja repetida: " + wb.getSheetName(
          i ) );
      }
      StringBuilder secciones = new StringBuilder();
      for ( Row r : wb.getSheetAt( 0 ) ) {
        Cell c = r.getCell( 1 ); // columna «Seccion» del indice
        if ( c != null && c.getCellType() == CellType.STRING ) {
          secciones.append( c.getStringCellValue() ).append( '\n' );
        }
      }
      // el resumen (1.1) no tiene tablas: la primera hoja de cada documento es la del flujo (1.2)
      contiene( secciones.toString(), "1.2 Diagrama de flujo", "secciones del primer documento numeradas" );
      contiene( secciones.toString(), "2.2 Diagrama de flujo", "secciones del segundo documento numeradas" );
      sinFormulas( wb );
    }
  }

  /** Texto que empieza como formula: queda texto con quotePrefix; un codigo con ceros sigue siendo texto. */
  public void pruebaFormulasYNumeros() throws Exception {
    Informe inf = new Informe();
    inf.titulo = "Inyección";
    inf.idioma = "es";
    Tabla t = new Tabla( "Texto", "Número", "Código" );
    t.titulo = "Celdas peligrosas";
    t.alineacion = Arrays.asList( "l", "r", "r" );
    t.fila( "=SUMA(A1:A9)", "1.234.567", "0042" );
    t.fila( "+34 600 000 000", "-12,5", "007" );
    t.fila( "-1", "12,5 %", "1.0" );
    t.fila( "@usuario", "=1+1", "" );
    t.fila( "\tcon tab", "1,234.5", null );
    Seccion s = new Seccion( SeccionId.DICCIONARIO, "Diccionario: «raro» [x]/y*?" ).con( t );
    inf.secciones.add( s );
    Tabla larga = new Tabla( "A" );
    larga.titulo = "Un título larguísimo que no entra en los treinta y un caracteres de Excel";
    larga.fila( "x" );
    s.con( larga );
    Tabla repetida = new Tabla( "A" );
    repetida.titulo = larga.titulo;
    repetida.fila( "y" );
    s.con( repetida );
    File f = new File( carpeta, "formulas.xlsx" );
    new RenderXlsx().escribir( inf, new Tema(), f, null );
    try ( XSSFWorkbook wb = abrir( f ) ) {
      XSSFSheet h = wb.getSheetAt( 1 );
      igual( "01 Celdas peligrosas", h.getSheetName(), "nombre de la hoja" );
      for ( int i = 1; i <= 4; i++ ) {
        XSSFCell c = h.getRow( i ).getCell( 0 );
        igual( CellType.STRING, c.getCellType(), "texto en " + c.getAddress() );
        cierto( c.getCellStyle().getQuotePrefixed(), "quotePrefix en " + c.getAddress() + " (" + c.getStringCellValue()
          + ")" );
      }
      igual( "=SUMA(A1:A9)", h.getRow( 1 ).getCell( 0 ).getStringCellValue(), "el texto tal cual" );
      cierto( h.getRow( 5 ).getCell( 0 ).getCellStyle().getQuotePrefixed(), "tab al principio: quotePrefix" );
      igual( 1234567.0, h.getRow( 1 ).getCell( 1 ).getNumericCellValue(), "1.234.567" );
      igual( -12.5, h.getRow( 2 ).getCell( 1 ).getNumericCellValue(), "-12,5" );
      igual( 12.5, h.getRow( 3 ).getCell( 1 ).getNumericCellValue(), "12,5 %" );
      igual( "#,##0.0\" %\"", h.getRow( 3 ).getCell( 1 ).getCellStyle().getDataFormatString(), "formato con %" );
      XSSFCell f11 = h.getRow( 4 ).getCell( 1 );
      igual( CellType.STRING, f11.getCellType(), "=1+1 en columna numerica: texto" );
      cierto( f11.getCellStyle().getQuotePrefixed(), "=1+1 con quotePrefix" );
      igual( CellType.STRING, h.getRow( 5 ).getCell( 1 ).getCellType(), "1,234.5 no es un numero en castellano" );
      igual( "0042", h.getRow( 1 ).getCell( 2 ).getStringCellValue(), "codigo con ceros como texto" );
      igual( "007", h.getRow( 2 ).getCell( 2 ).getStringCellValue(), "007 como texto" );
      igual( CellType.STRING, h.getRow( 3 ).getCell( 2 ).getCellType(), "1.0 no es un numero en castellano" );
      nulo( h.getRow( 4 ).getCell( 2 ), "celda vacia: sin celda" );
      // nombres de hoja: 31 caracteres, unicos
      Set<String> nombres = new HashSet<>();
      for ( int i = 0; i < wb.getNumberOfSheets(); i++ ) {
        String n = wb.getSheetName( i );
        cierto( n.length() <= 31, "nombre de hasta 31: " + n );
        cierto( nombres.add( n.toLowerCase( Locale.ROOT ) ), "nombre unico: " + n );
        cierto( !n.matches( ".*[\\[\\]:*?/\\\\].*" ), "sin caracteres prohibidos: " + n );
      }
      igual( 4, wb.getNumberOfSheets(), "indice y tres tablas (sin indicadores)" );
      sinFormulas( wb );
    }
    // en ingles: "1,234.5" si es numero
    inf.idioma = "en";
    File g = new File( carpeta, "formulas-en.xlsx" );
    new RenderXlsx().escribir( inf, new Tema(), g, null );
    try ( XSSFWorkbook wb = abrir( g ) ) {
      XSSFSheet h = wb.getSheetAt( 1 );
      igual( 1234.5, h.getRow( 5 ).getCell( 1 ).getNumericCellValue(), "1,234.5 en ingles" );
      igual( "Index", wb.getSheetName( 0 ), "indice en ingles" );
    }
  }

  public void pruebaSinTablas() throws Exception {
    Informe inf = new Informe();
    inf.idioma = "pt";
    inf.secciones.add( new Seccion( SeccionId.RESUMEN, "Resumo" ) );
    inf.secciones.add( null );
    File f = new File( carpeta, "vacio.xlsx" );
    Resultado res = new RenderXlsx().escribir( inf, null, f, null );
    contiene( String.join( "\n", res.avisos ), "não tem tabelas", "aviso sin tablas" );
    try ( XSSFWorkbook wb = abrir( f ) ) {
      igual( 1, wb.getNumberOfSheets(), "solo el indice" );
      igual( "Índice", wb.getSheetName( 0 ), "indice en portugues" );
    }
  }

  public void pruebaIdiomasYEntorno() throws Exception {
    Properties es = props( "es" );
    for ( String i : Arrays.asList( "en", "pt" ) ) {
      igual( es.keySet(), props( i ).keySet(), "mismas claves en xlsx_" + i );
    }
    nulo( RenderXlsx.problema( "es" ), "XmlBeans 3.1 de lib\\ de PDI" );
    Formato fmt = new RenderXlsx().formato();
    igual( Formato.deClave( RenderXlsx.CLAVE ), fmt, "formato por clave" );
    if ( fmt != null ) {
      igual( "xlsx", fmt.extension, "extension" );
    }
  }

  public void pruebaCancelar() throws Exception {
    File f = new File( carpeta, "cancelado.xlsx" );
    if ( f.exists() ) {
      cierto( f.delete(), "borrar el anterior" );
    }
    Progreso cancelado = new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
      }

      @Override
      public boolean cancelado() {
        return true;
      }
    };
    lanza( CancellationException.class, () -> new RenderXlsx().escribir( ModeloMuestra.crear(), null, f, cancelado ),
      "cancelar corta la exportacion" );
    falso( f.exists(), "cancelado no deja archivo" );
  }

  // ------------------------------------------------------------------ ayudas

  private static XSSFWorkbook abrir( File f ) throws Exception {
    try ( InputStream in = new FileInputStream( f ) ) {
      return new XSSFWorkbook( in );
    }
  }

  /** La hoja cuyo nombre termina con ese texto (despues del "NN "). */
  private static XSSFSheet hoja( XSSFWorkbook wb, String fin ) {
    List<String> todas = new ArrayList<>();
    for ( int i = 0; i < wb.getNumberOfSheets(); i++ ) {
      todas.add( wb.getSheetName( i ) );
      if ( wb.getSheetName( i ).endsWith( fin ) ) {
        return wb.getSheetAt( i );
      }
    }
    throw new AssertionError( "no hay hoja «" + fin + "»: " + todas );
  }

  /** Ninguna celda del libro es una formula. */
  private static void sinFormulas( XSSFWorkbook wb ) {
    for ( int i = 0; i < wb.getNumberOfSheets(); i++ ) {
      for ( Row r : wb.getSheetAt( i ) ) {
        for ( Cell c : r ) {
          cierto( c.getCellType() != CellType.FORMULA, "formula en " + wb.getSheetName( i ) + "!" + c.getAddress() );
        }
      }
    }
  }

  private static Properties props( String idioma ) throws Exception {
    Properties p = new Properties();
    try ( InputStream in = PruebaXlsx.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA + "xlsx_" + idioma
      + ".properties" ) ) {
      noNulo( in, "xlsx_" + idioma + ".properties en el classpath" );
      p.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    return p;
  }
}
