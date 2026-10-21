package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.CorrectorPlanilla.Fila;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.PoliticaPack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Criterio;

/**
 * Lote, planilla y creacion de packs (C6, docs/corrector-diseno.md §2, §8 y §10). Las pruebas de lote y de creacion
 * lanzan JVMs hijas de verdad (como PruebaCorrectorMotor): classpath de test.ps1 + el PDI de -Dpruebas.pdi, todo bajo
 * {@code <prueba>/lote-c6}, nunca el KETTLE_HOME real.
 * <ul>
 * <li>Planilla sin hijas: CSV con BOM, {@code ;} y coma decimal, {@code =1+1;x.ktr} entre comillas y con {@code '}
 * (no corre columnas ni es formula); el .xlsx se vuelve a abrir con POI (formula de la nota con su valor, prefijo de
 * comilla, vinculo a la devolucion); los ajustes del docente se leen de los dos.</li>
 * <li>Lote: 17 entregas con 2 workers (.ktr sueltos, una carpeta con dos .ktr, un .zip, una carpeta de Moodle con
 * alumnos.csv, una carpeta vacia, {@code paula_correcta.kjb} junto a su .ktr y {@code =1+1;x.ktr}): estados y notas de
 * casos.json, identidades, la planilla con los ajustes de una anterior, devoluciones .md y el resumen.</li>
 * <li>Cancelar deja lo ya corregido y la planilla con eso.</li>
 * <li>Crear pack desde la solucion del ejercicio 3: las mismas columnas, claves y salida esperada que el pack de las
 * fixtures, valida, la solucion saca 10 con en_US y la copia para alumnos no tiene ni la solucion ni el caso oculto;
 * con la entrega de hugo como solucion (decimal vacio) avisa que depende del locale; captura query sobre SQLite.</li>
 * </ul>
 *
 * @dueño C6
 */
public class PruebaCorrectorLote {

  private static final String VENTAS = CorrectorRecursos.VENTAS;
  private static final String TABLA = CorrectorRecursos.TABLA;

  private File copia;

  /** Borra lo de la corrida anterior (los .ktr de _abiertos traen datos/ de solo lectura: CorrectorSandbox.borrar). */
  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    CorrectorSandbox.borrar( base() );
  }

  private static File base() {
    return new File( EntornoKettle.carpeta(), "lote-c6" );
  }

  private File copia() throws IOException {
    if ( copia == null ) {
      copia = CorrectorRecursos.copia();
    }
    return copia;
  }

  private File entrega( String packId, String archivo ) throws IOException {
    return new File( new File( new File( copia(), "entregas" ), packId ), archivo );
  }

  private File dirPack( String packId ) throws IOException {
    return new File( new File( copia(), "packs" ), packId );
  }

  private static CorrectorProcesos.Opciones procesos() {
    CorrectorProcesos.Opciones op = new CorrectorProcesos.Opciones();
    op.xmxMb = 640;
    op.pdi = EntornoKettle.pdi();
    op.versionPlugin = "prueba";
    return op;
  }

  // ------------------------------------------------------------------ planilla (sin hijas)

  /** El CSV: BOM, ;, CRLF, coma decimal, texto entre comillas y sin formulas; los ajustes vuelven a leerse. */
  public void pruebaPlanillaCsv() throws Exception {
    CorrectorPlanilla.Hoja h = hoja( "es" );
    File f = new File( base(), "planilla/notas.csv" );
    CorrectorPlanilla.escribirCsv( h, f );
    byte[] b = Files.readAllBytes( f.toPath() );
    cierto( b.length > 3 && ( b[ 0 ] & 0xFF ) == 0xEF && ( b[ 1 ] & 0xFF ) == 0xBB && ( b[ 2 ] & 0xFF ) == 0xBF,
      "UTF-8 con BOM" );
    String texto = new String( b, StandardCharsets.UTF_8 ).substring( 1 );
    cierto( texto.contains( "\r\n" ), "CRLF" );
    // cada fila tiene las mismas columnas que el encabezado: el ; y las comillas del alumno no las corren
    CorrectorTabla t = CorrectorTabla.leerTexto( texto, new CorrectorTabla.Lectura().separador( ";" ).encierro( "\"" )
      .encabezado( true ) );
    igual( Arrays.asList( "alumno", "archivo", "estado", "nota", "Corre sin errores", "Valores correctos",
      "Usa un Group by", "segundos", "devolucion", "nota_motor", "ajuste", "comentario" ), t.columnas,
      "encabezado en es, un check por columna" );
    igual( 3, t.cantidadFilas(), "3 filas" );
    int x = fila( t, "'=1+1;x" );
    cierto( x >= 0, "la identidad del alumno con ' delante: " + t );
    igual( "'=1+1;x.ktr", t.celda( x, t.indice( "archivo" ) ), "archivo sin formula y sin correr columnas" );
    igual( "'@SUM(A1)", t.celda( x, t.indice( "comentario" ) ), "comentario que empieza con @" );
    igual( "corregida", t.celda( x, t.indice( "estado" ) ), "estado traducido" );
    igual( "9,2", t.celda( x, t.indice( "nota" ) ), "nota final con coma decimal (9,7 - 0,5)" );
    igual( "9,7", t.celda( x, t.indice( "nota_motor" ) ), "nota del motor" );
    igual( "-0,5", t.celda( x, t.indice( "ajuste" ) ), "el ajuste es un numero, sin '" );
    int d = fila( t, "diego" );
    igual( "bloqueada · step no permitido", t.celda( d, t.indice( "estado" ) ), "estado con el motivo" );
    igual( "0,5", t.celda( d, t.indice( "Usa un Group by" ) ), "puntos del check" );
    int a = fila( t, "ana \"la\" grande" );
    cierto( a >= 0, "comillas dobladas" );
    igual( "linea 1\nlinea 2; con punto y coma", t.celda( a, t.indice( "comentario" ) ), "salto de linea y ; en el "
      + "comentario" );
    contiene( texto, "\"'=1+1;x\";\"'=1+1;x.ktr\";", "las dos celdas entre comillas" );
    // en ingles: punto decimal y encabezados en ingles
    File en = new File( base(), "planilla/en.csv" );
    CorrectorPlanilla.escribirCsv( hoja( "en" ), en );
    String te = new String( Files.readAllBytes( en.toPath() ), StandardCharsets.UTF_8 );
    contiene( te, "\"student\";\"file\";\"status\";\"grade\"", "encabezado en" );
    contiene( te, ";9.2;", "punto decimal en en" );
    // los ajustes vuelven a leerse (sin la ' que agrego la planilla), tambien del CSV en ingles
    Map<String, CorrectorPlanilla.Ajuste> aj = CorrectorPlanilla.leerAjustesCsv( f );
    noNulo( aj.get( "=1+1;x" ), "ajuste de =1+1;x: " + aj.keySet() );
    igual( -0.5, aj.get( "=1+1;x" ).ajuste, "ajuste" );
    igual( "@SUM(A1)", aj.get( "=1+1;x" ).comentario, "comentario sin la '" );
    igual( "linea 1\nlinea 2; con punto y coma", aj.get( "ana \"la\" grande" ).comentario, "comentario multilinea" );
    nulo( aj.get( "diego" ), "sin ajuste ni comentario: no esta" );
    igual( -0.5, CorrectorPlanilla.leerAjustesCsv( en ).get( "=1+1;x" ).ajuste, "del CSV en ingles" );
    // guardada desde Excel como «CSV delimitado por comas»: windows-1252, sin BOM ni comillas
    File excel = new File( base(), "planilla/excel.csv" );
    Files.write( excel.toPath(), "alumno;nota;ajuste;comentario\r\nMatías;7,5;+0,25;revisé el código\r\n".getBytes(
      "windows-1252" ) );
    Map<String, CorrectorPlanilla.Ajuste> ex = CorrectorPlanilla.leerAjustesCsv( excel );
    noNulo( ex.get( "Matías" ), "el nombre con acento en windows-1252: " + ex.keySet() );
    igual( 0.25, ex.get( "Matías" ).ajuste, "ajuste con signo" );
    igual( "revisé el código", ex.get( "Matías" ).comentario, "comentario con acentos" );
    // escapes sueltos
    igual( "\"'-2+3\"", CorrectorPlanilla.celdaCsv( "-2+3" ), "-" );
    igual( "\"'+1\"", CorrectorPlanilla.celdaCsv( "+1" ), "+" );
    igual( "\"'\tx\"", CorrectorPlanilla.celdaCsv( "\tx" ), "tab" );
    igual( "\"a\"\"b\"", CorrectorPlanilla.celdaCsv( "a\"b" ), "comillas" );
    igual( "\"ana\"", CorrectorPlanilla.celdaCsv( "ana" ), "texto normal" );
    igual( 0.5, CorrectorPlanilla.numeroDe( "+0,5" ), "+0,5" );
    igual( -1.25, CorrectorPlanilla.numeroDe( "'-1.25" ), "-1.25 con '" );
    nulo( CorrectorPlanilla.numeroDe( "=1+1" ), "formula: no es numero" );
    // el resumen
    CorrectorPlanilla.Resumen r = CorrectorPlanilla.resumir( h.filas, 10 );
    igual( 3, r.corregidas, "corregidas" );
    igual( 1, r.bloqueadas, "bloqueadas" );
    igual( 2, r.aprobadas, "aprobadas (>= 5)" );
    igual( 6.57, r.media, "media de 10, 9,2 y 0,5" );
    igual( 9.2, r.mediana, "mediana" );
  }

  /** El .xlsx vuelve a abrir con POI: hojas, encabezado, formula de la nota con su valor, comilla y vinculo. */
  public void pruebaPlanillaXlsx() throws Exception {
    cierto( CorrectorPlanilla.hayPoi(), "POI en el classpath de las pruebas" );
    CorrectorPlanilla.Hoja h = hoja( "es" );
    File f = new File( base(), "planilla-xlsx/notas.xlsx" );
    CorrectorPlanilla.escribirXlsx( h, f );
    try ( InputStream in = new FileInputStream( f ); XSSFWorkbook wb = new XSSFWorkbook( in ) ) {
      igual( 2, wb.getNumberOfSheets(), "notas y resumen" );
      igual( "Notas", wb.getSheetName( 0 ), "hoja de notas" );
      igual( "Resumen", wb.getSheetName( 1 ), "hoja de resumen" );
      Sheet s = wb.getSheetAt( 0 );
      Row r0 = s.getRow( 0 );
      List<String> cab = new ArrayList<String>();
      for ( int i = 0; i < r0.getLastCellNum(); i++ ) {
        cab.add( r0.getCell( i ).getStringCellValue() );
      }
      igual( "alumno", cab.get( 0 ), "primera columna" );
      int iNota = cab.indexOf( "nota" );
      int iAj = cab.indexOf( "ajuste" );
      int iDev = cab.indexOf( "devolucion" );
      igual( 3, s.getLastRowNum(), "encabezado + 3 filas (la ultima es la 3)" );
      Row x = null;
      for ( int i = 1; i <= s.getLastRowNum(); i++ ) {
        if ( "=1+1;x".equals( s.getRow( i ).getCell( 0 ).getStringCellValue() ) ) {
          x = s.getRow( i );
        }
      }
      noNulo( x, "la fila de =1+1;x (texto, no formula)" );
      Cell alumno = x.getCell( 0 );
      igual( CellType.STRING, alumno.getCellType(), "el alumno es texto" );
      cierto( alumno.getCellStyle().getQuotePrefixed(), "con prefijo de comilla" );
      Cell nota = x.getCell( iNota );
      igual( CellType.FORMULA, nota.getCellType(), "la nota es formula" );
      contiene( nota.getCellFormula(), "ISNUMBER", "nota_motor + ajuste" );
      igual( 9.2, nota.getNumericCellValue(), "con el valor ya calculado" );
      igual( -0.5, x.getCell( iAj ).getNumericCellValue(), "ajuste numerico" );
      noNulo( x.getCell( iDev ).getHyperlink(), "vinculo a la devolucion" );
      igual( "x.md", x.getCell( iDev ).getHyperlink().getAddress(), "vinculo relativo" );
      Sheet res = wb.getSheetAt( 1 );
      boolean media = false;
      for ( Row r : res ) {
        Cell c = r.getCell( 0 );
        if ( c != null && c.getCellType() == CellType.STRING && "Media".equals( c.getStringCellValue() ) ) {
          media = true;
          igual( 6.57, r.getCell( 1 ).getNumericCellValue(), "media en el resumen" );
        }
      }
      cierto( media, "el resumen tiene la media" );
    }
    // los ajustes de la planilla .xlsx (la mas nueva de la carpeta)
    Map<String, CorrectorPlanilla.Ajuste> aj = CorrectorPlanilla.leerAjustes( f.getParentFile() );
    igual( -0.5, aj.get( "=1+1;x" ).ajuste, "ajuste leido del .xlsx" );
    igual( "@SUM(A1)", aj.get( "=1+1;x" ).comentario, "comentario leido del .xlsx" );
    igual( "Grades", CorrectorPlanilla.nombreHoja( "Grades" ), "nombre de hoja" );
    igual( "a_b_c", CorrectorPlanilla.nombreHoja( "a:b/c" ), "sin caracteres prohibidos" );
  }

  /** Tres filas: ana (10, con comillas y un comentario de dos lineas), =1+1;x (9,7 - 0,5) y diego (bloqueada). */
  private static CorrectorPlanilla.Hoja hoja( String idioma ) {
    Pack p = new Pack();
    p.id = "p";
    p.titulo = "Ejercicio de prueba";
    p.escala = 10;
    Regla rg = new Regla( "usa_group_by", Regla.DEBE_USAR, "GroupBy" );
    rg.etiqueta = "Usa un Group by";
    p.reglas.add( rg );
    p.rubrica.add( new ItemRubrica( "runs", 1 ) );
    p.rubrica.add( new ItemRubrica( "values", 4 ) );
    p.rubrica.add( new ItemRubrica( "rule:usa_group_by", 1 ) );
    p.completar();
    CorrectorPlanilla.Hoja h = new CorrectorPlanilla.Hoja();
    h.pack = p;
    h.idioma = idioma;
    Fila a = new Fila( "ana \"la\" grande", "ana.ktr", resultado( "graded", null, 10.0, 1, 4, 1 ) );
    a.comentario = "linea 1\nlinea 2; con punto y coma";
    a.devolucion = "ana.md";
    Fila x = new Fila( "=1+1;x", "=1+1;x.ktr", resultado( "graded", null, 9.7, 1, 3.7, 1 ) );
    x.ajuste = -0.5;
    x.comentario = "@SUM(A1)";
    x.devolucion = "x.md";
    Fila d = new Fila( "diego", "diego.ktr", resultado( "blocked", "step_not_allowed", 0.5, 0, 0, 0.5 ) );
    h.filas.addAll( Arrays.asList( a, x, d ) );
    return h;
  }

  private static ResultadoCorreccion resultado( String estado, String motivo, double nota, double runs,
                                                double values, double regla ) {
    ResultadoCorreccion r = new ResultadoCorreccion();
    r.estado = estado;
    r.motivo = motivo;
    r.nota = nota;
    r.tiempos = new ResultadoCorreccion.Tiempos();
    r.tiempos.procesoSeg = 0.35;
    r.criterios.add( criterio( "runs", "Corre sin errores", 1, runs ) );
    r.criterios.add( criterio( "values", "Valores correctos", 4, values ) );
    r.criterios.add( criterio( "rule:usa_group_by", "Usa un Group by", 1, regla ) );
    return r;
  }

  private static Criterio criterio( String check, String etiqueta, double puntos, double ganado ) {
    Criterio c = new Criterio( check, puntos, ganado );
    c.etiqueta = etiqueta;
    c.aprobado = ganado >= puntos;
    return c;
  }

  private static int fila( CorrectorTabla t, String alumno ) {
    for ( int i = 0; i < t.cantidadFilas(); i++ ) {
      if ( alumno.equals( t.celda( i, 0 ) ) ) {
        return i;
      }
    }
    return -1;
  }

  // ------------------------------------------------------------------ lote (hijas)

  /**
   * 17 entregas con 2 workers: estados y notas de casos.json, identidades (carpeta, .zip, alumnos.csv, .kjb repetido,
   * carpeta vacia, =1+1;x), devoluciones, planillas con el ajuste de una anterior y el resumen.
   */
  public void pruebaLote() throws Exception {
    File carpeta = new File( base(), "entregas-tp3" );
    File salida = new File( base(), "notas-tp3" );
    carpeta.mkdirs();
    salida.mkdirs();
    for ( String n : Arrays.asList( "ana_orden_distinto", "bruno_agregado_mal", "carla_falta_columna",
      "diego_javascript", "elena_sin_ordenar", "fede_bucle", "hugo_decimal", "paula_correcta",
      "quique_dibuja_salida", "sofi_fecha_dmy", "walter_copia_companero" ) ) {
      copiar( entrega( VENTAS, n + ".ktr" ), new File( carpeta, n + ".ktr" ) );
    }
    EntornoKettle.escribir( new File( carpeta, "paula_correcta.kjb" ), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<job><name>paula</name></job>\n" );
    copiar( entrega( VENTAS, "carla_falta_columna.ktr" ), new File( carpeta, "=1+1;x.ktr" ) );
    File gabi = new File( carpeta, "gabi" );
    copiar( entrega( VENTAS, "gabi_renombra.ktr" ), new File( gabi, "gabi_renombra.ktr" ) );
    copiar( entrega( VENTAS, "tomas_sin_salida.ktr" ), new File( gabi, "borrador.ktr" ) );
    File zipDir = new File( base(), "rodri-zip" );
    copiar( entrega( VENTAS, "rodri_filtra_de_mas.ktr" ), new File( new File( zipDir, "tp" ),
      "rodri_filtra_de_mas.ktr" ) );
    CorrectorPack.comprimir( zipDir, new File( carpeta, "rodri.zip" ) );
    String moodle = "Mati Pérez_12345_assignsubmission_file_";
    copiar( entrega( VENTAS, "mati_rutas_mac.ktr" ), new File( new File( carpeta, moodle ), "mati_rutas_mac.ktr" ) );
    File nadie = new File( carpeta, "nadie" );
    nadie.mkdirs();
    EntornoKettle.escribir( new File( nadie, "leeme.txt" ), "no entregue nada" );
    EntornoKettle.escribir( new File( carpeta, "consigna.pdf" ), "%PDF-1.4" );
    EntornoKettle.escribir( new File( carpeta, "alumnos.csv" ), "\uFEFFarchivo;alumno;email\r\n" + moodle
      + ";Matías Pérez;mati@example.com\r\nquien_no_esta.ktr;Fantasma;f@example.com\r\n" );
    // una planilla anterior con ajustes del docente: se conservan
    EntornoKettle.escribir( new File( salida, "notas.csv" ), "\uFEFF\"alumno\";\"ajuste\";\"comentario\"\r\n"
      + "\"bruno_agregado_mal\";0,5;\"revisado a mano\"\r\n\"'=1+1;x\";-1;\"probando\"\r\n" );

    CorrectorLote.Opciones op = new CorrectorLote.Opciones();
    op.pack = dirPack( VENTAS );
    op.carpeta = carpeta;
    op.salida = salida;
    op.workers = 2;
    op.planilla = CorrectorPlanilla.AMBAS;
    op.procesos = procesos();
    CorrectorLote l = CorrectorLote.iniciar( op );
    // otro lote a la misma carpeta mientras corre: no
    lanza( IllegalStateException.class, () -> CorrectorLote.iniciar( op ), "dos lotes en la misma carpeta" );
    igual( l, CorrectorLote.buscar( l.id() ), "job_id" );
    CorrectorLote.Estado s0 = l.estado();
    igual( 17, s0.total, "17 entregas" );
    cierto( l.esperar( 15 * 60000L ), "el lote termino" );
    CorrectorLote.Estado s = l.estado();
    nulo( s.error, "sin error del lote" );
    igual( CorrectorLote.TERMINADO, s.estado, "finished" );
    igual( 17, s.hechas, "17 hechas" );
    Map<String, CorrectorLote.Renglon> por = new LinkedHashMap<String, CorrectorLote.Renglon>();
    for ( CorrectorLote.Renglon r : s.resultados ) {
      por.put( r.alumno, r );
    }

    // estados y notas: los de casos.json (las identidades nuevas, con la entrega de origen)
    Map<String, String> origen = new LinkedHashMap<String, String>();
    for ( String n : Arrays.asList( "ana_orden_distinto", "carla_falta_columna", "diego_javascript",
      "elena_sin_ordenar", "fede_bucle", "hugo_decimal", "paula_correcta", "quique_dibuja_salida", "sofi_fecha_dmy",
      "walter_copia_companero" ) ) {
      origen.put( n, n );
    }
    origen.put( "=1+1;x", "carla_falta_columna" );
    origen.put( "gabi", "gabi_renombra" );
    origen.put( "rodri", "rodri_filtra_de_mas" );
    origen.put( "Matías Pérez", "mati_rutas_mac" );
    List<String> malas = new ArrayList<String>();
    for ( Map.Entry<String, String> e : origen.entrySet() ) {
      JsonNode esp = CorrectorRecursos.esperado( VENTAS, e.getValue() + ".ktr" );
      CorrectorLote.Renglon r = por.get( e.getKey() );
      if ( r == null ) {
        malas.add( e.getKey() + ": sin resultado" );
        continue;
      }
      double nota = esp.path( "grade" ).asDouble() + ( "=1+1;x".equals( e.getKey() ) ? -1 : 0 );
      if ( !esp.path( "status" ).asText().equals( r.estado ) || r.nota == null || Math.abs( r.nota - nota ) > 1e-9 ) {
        malas.add( e.getKey() + ": " + r.estado + " " + r.nota + " (esperado " + esp.path( "status" ).asText() + " "
          + nota + ")" );
      }
    }
    igual( Collections.emptyList(), malas, "entregas que no dan lo de casos.json" );
    // bruno con el ajuste de la planilla anterior
    CorrectorLote.Renglon bruno = por.get( "bruno_agregado_mal" );
    igual( "graded", bruno.estado, "bruno corregido" );
    igual( 6.5, bruno.notaMotor, "nota del motor de bruno" );
    igual( 7.0, bruno.nota, "nota final de bruno (6,5 + 0,5 de la planilla anterior)" );

    // identidades
    CorrectorLote.Renglon p2 = por.get( "paula_correcta_2" );
    noNulo( p2, "el .kjb de paula queda como paula_correcta_2: " + por.keySet() );
    igual( "load_failed", p2.estado, "un job no se corrige todavia" );
    igual( "paula_correcta.kjb", p2.archivo, "archivo del .kjb" );
    igual( "graded", por.get( "paula_correcta" ).estado, "el .ktr se queda con la identidad" );
    ResultadoCorreccion rp2 = CorrectorJson.leer( new File( salida, "paula_correcta_2.json" ),
      ResultadoCorreccion.class );
    cierto( tieneAviso( rp2, CorrectorLote.AVISO_IDENTIDAD_REPETIDA ), "aviso de identidad repetida" );
    igual( "rodri.zip/tp/rodri_filtra_de_mas.ktr", por.get( "rodri" ).archivo, "la entrega .zip" );
    igual( "gabi/gabi_renombra.ktr", por.get( "gabi" ).archivo, "de la carpeta, el unico con Text file output" );
    cierto( tieneAviso( CorrectorJson.leer( new File( salida, "gabi.json" ), ResultadoCorreccion.class ),
      CorrectorLote.AVISO_ENTREGA_ELEGIDA ), "gabi: aviso de cual se corrigio" );
    igual( moodle + "/mati_rutas_mac.ktr", por.get( "Matías Pérez" ).archivo, "identidad de alumnos.csv" );
    CorrectorLote.Renglon nad = por.get( "nadie" );
    igual( "load_failed", nad.estado, "carpeta sin .ktr" );
    igual( 0.0, nad.nota, "sin nada que corregir" );
    contiene( CorrectorJson.leer( new File( salida, "nadie.json" ), ResultadoCorreccion.class ).detalle, "no .ktr",
      "por que" );
    boolean ignorado = false;
    boolean fantasma = false;
    for ( Aviso a : s.avisos == null ? Collections.<Aviso>emptyList() : s.avisos ) {
      ignorado |= CorrectorLote.AVISO_IGNORADO.equals( a.codigo ) && a.detalle.contains( "consigna.pdf" );
      fantasma |= CorrectorLote.AVISO_ALUMNOS.equals( a.codigo ) && a.detalle.contains( "quien_no_esta" );
    }
    cierto( ignorado, "consigna.pdf no es una entrega: " + s.avisos );
    cierto( fantasma, "alumnos.csv nombra una que no esta: " + s.avisos );
    for ( CorrectorLote.Entrega e : l.entregas() ) {
      if ( "Matías Pérez".equals( e.alumno ) ) {
        igual( "mati@example.com", e.email, "email de alumnos.csv" );
      }
    }

    // resumen
    igual( 17, s.resumen.corregidas, "con resultado" );
    igual( 3, s.resumen.bloqueadas, "diego, fede y walter" );
    igual( 2, s.resumen.fallidas, "paula_correcta_2 y nadie" );
    igual( 0, s.resumen.tiempoAgotado, "sin timeouts" );
    noNulo( s.resumen.media, "media" );
    noNulo( s.resumen.mediana, "mediana" );

    // dos workers de verdad
    cierto( new File( new File( salida, CorrectorProcesos.LOGS ), "worker-2.log" ).isFile(), "el segundo worker" );
    falso( new File( salida, CorrectorProcesos.SANDBOX ).exists(), "_sandbox borrado (con los .zip descomprimidos)" );

    // devoluciones
    String md = EntornoKettle.leer( new File( salida, "bruno_agregado_mal.md" ) );
    contiene( md, "bruno\\_agregado\\_mal", "la devolucion de bruno (Markdown escapado)" );
    cierto( new File( salida, "=1+1;x.md" ).isFile(), "devolucion de =1+1;x" );

    // la planilla CSV: columnas firmes, sin formulas, con los ajustes
    noNulo( s.planillas, "planillas escritas" );
    igual( 2, s.planillas.size(), "csv y xlsx: " + s.planillas + " " + avisos( s.avisos ) );
    contiene( s.planilla, "notas.xlsx", "la principal es la .xlsx" );
    String csv = new String( Files.readAllBytes( new File( salida, "notas.csv" ).toPath() ), StandardCharsets.UTF_8 );
    CorrectorTabla t = CorrectorTabla.leerTexto( csv, new CorrectorTabla.Lectura().separador( ";" ).encierro( "\"" )
      .encabezado( true ) );
    igual( 17, t.cantidadFilas(), "17 filas" );
    int columnas = t.columnas.size();
    igual( 5 + pack( VENTAS ).rubrica.size() + 5, columnas, "alumno, email, archivo, estado, nota, los checks y 5 mas" );
    for ( String linea : csv.substring( 1 ).split( "\r\n(?=\")" ) ) {
      CorrectorTabla una = CorrectorTabla.leerTexto( linea, new CorrectorTabla.Lectura().separador( ";" ).encierro(
        "\"" ).encabezado( false ) );
      igual( columnas, una.columnas.size(), "cada linea con las mismas columnas: " + linea );
    }
    int x = fila( t, "'=1+1;x" );
    cierto( x >= 0, "=1+1;x con ' delante" );
    igual( "'=1+1;x.ktr", t.celda( x, t.indice( "archivo" ) ), "su archivo" );
    igual( "8,7", t.celda( x, t.indice( "nota" ) ), "9,7 - 1 de la planilla anterior" );
    igual( "probando", t.celda( x, t.indice( "comentario" ) ), "comentario anterior" );
    int b = fila( t, "bruno_agregado_mal" );
    igual( "7", t.celda( b, t.indice( "nota" ) ), "bruno 7" );
    igual( "6,5", t.celda( b, t.indice( "nota_motor" ) ), "bruno motor" );
    igual( "revisado a mano", t.celda( b, t.indice( "comentario" ) ), "bruno comentario" );
    igual( "bruno_agregado_mal.md", t.celda( b, t.indice( "devolucion" ) ), "devolucion" );
    igual( "mati@example.com", t.celda( fila( t, "Matías Pérez" ), t.indice( "email" ) ), "email" );
    cierto( fila( t, "ana_orden_distinto" ) < fila( t, "bruno_agregado_mal" ) && fila( t, "bruno_agregado_mal" )
      < fila( t, "walter_copia_companero" ), "ordenada por alumno" );

    // la planilla XLSX abre y tiene las mismas filas
    try ( InputStream in = new FileInputStream( new File( salida, "notas.xlsx" ) ); XSSFWorkbook wb = new XSSFWorkbook(
      in ) ) {
      igual( 17, wb.getSheetAt( 0 ).getLastRowNum(), "17 filas en el .xlsx" );
    }

    // ajustar despues: reescribe las planillas
    cierto( l.ajustar( "diego_javascript", 1.0, "lo charlamos" ), "ajuste de diego" );
    Map<String, CorrectorPlanilla.Ajuste> aj = CorrectorPlanilla.leerAjustesCsv( new File( salida, "notas.csv" ) );
    igual( 1.0, aj.get( "diego_javascript" ).ajuste, "el ajuste quedo en la planilla" );
    igual( 1.5, l.estado().resultados.get( indice( l.estado(), "diego_javascript" ) ).nota, "nota 0,5 + 1" );
    falso( l.ajustar( "nadie_que_exista", 1.0, null ), "alumno que no esta" );
  }

  /**
   * Sin nada que lanzar: un .zip con zip slip y una carpeta sin .ktr quedan load_failed sin abrir ningun worker; el zip
   * no escribe fuera. Un lote sin entregas termina enseguida con la planilla vacia.
   */
  public void pruebaLoteSinCorrer() throws Exception {
    File carpeta = new File( base(), "entregas-malas" );
    File salida = new File( base(), "notas-malas" );
    carpeta.mkdirs();
    File fuera = new File( base(), "fuera.ktr" );
    try ( java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream( new java.io.FileOutputStream( new File(
      carpeta, "malo.zip" ) ) ) ) {
      z.putNextEntry( new ZipEntry( "../../fuera.ktr" ) );
      z.write( "<transformation/>".getBytes( StandardCharsets.UTF_8 ) );
      z.closeEntry();
    }
    new File( carpeta, "vacia" ).mkdirs();
    CorrectorLote.Opciones op = new CorrectorLote.Opciones();
    op.pack = dirPack( VENTAS );
    op.carpeta = carpeta;
    op.salida = salida;
    op.planilla = CorrectorPlanilla.CSV;
    CorrectorLote l = CorrectorLote.iniciar( op );
    cierto( l.esperar( 30000 ), "termina sin workers" );
    CorrectorLote.Estado s = l.estado();
    igual( CorrectorLote.TERMINADO, s.estado, "finished" );
    igual( 2, s.total, "dos entregas" );
    igual( 2, s.hechas, "las dos con resultado" );
    for ( CorrectorLote.Renglon r : s.resultados ) {
      igual( ResultadoCorreccion.NO_CARGO, r.estado, r.alumno + ": load_failed" );
      igual( 0.0, r.nota, r.alumno + ": 0" );
    }
    contiene( CorrectorJson.leer( new File( salida, "malo.json" ), ResultadoCorreccion.class ).detalle, "zip slip",
      "por que se rechazo el zip" );
    falso( fuera.exists() || new File( carpeta, "fuera.ktr" ).exists(), "el zip no escribio fuera" );
    falso( new File( salida, CorrectorProcesos.LOGS ).exists(), "ningun worker" );

    File vacia = new File( base(), "entregas-ninguna" );
    vacia.mkdirs();
    op.carpeta = vacia;
    op.salida = new File( base(), "notas-ninguna" );
    CorrectorLote l2 = CorrectorLote.iniciar( op );
    cierto( l2.esperar( 30000 ), "termina enseguida" );
    igual( 0, l2.estado().total, "sin entregas" );
    cierto( new File( op.salida, "notas.csv" ).isFile(), "planilla con el encabezado" );
    lanza( IllegalArgumentException.class, () -> {
      CorrectorLote.Opciones o = new CorrectorLote.Opciones();
      o.pack = dirPack( VENTAS );
      o.carpeta = new File( base(), "no-existe" );
      o.salida = salida;
      CorrectorLote.iniciar( o );
    }, "carpeta de entregas que no existe" );
    lanza( CorrectorPack.PackInvalido.class, () -> {
      CorrectorLote.Opciones o = new CorrectorLote.Opciones();
      o.pack = new File( new File( copia(), "packs/invalidos" ), "sin_casos.json" );
      o.carpeta = carpeta;
      o.salida = new File( base(), "notas-pack-malo" );
      CorrectorLote.iniciar( o );
    }, "pack invalido" );
  }

  /** Cancelar: queda lo ya corregido, la planilla con eso y la entrega a medias como pendiente. */
  public void pruebaCancelar() throws Exception {
    File carpeta = new File( base(), "entregas-cancelar" );
    File salida = new File( base(), "notas-cancelar" );
    carpeta.mkdirs();
    copiar( entrega( VENTAS, "paula_correcta.ktr" ), new File( carpeta, "a_paula.ktr" ) );
    File t6 = entrega( VENTAS, "t6_infinito.ktr" );
    String x = new String( Files.readAllBytes( t6.toPath() ), StandardCharsets.UTF_8 ).replace(
      "<interval_in_ms>0</interval_in_ms>", "<interval_in_ms>1000</interval_in_ms>" );
    EntornoKettle.escribir( new File( carpeta, "b_lento.ktr" ), x );
    CorrectorLote.Opciones op = new CorrectorLote.Opciones();
    op.pack = dirPack( VENTAS );
    op.carpeta = carpeta;
    op.salida = salida;
    op.workers = 1;
    op.planilla = CorrectorPlanilla.CSV;
    op.procesos = procesos();
    CorrectorLote l = CorrectorLote.iniciar( op );
    long fin = System.currentTimeMillis() + 180000;
    while ( System.currentTimeMillis() < fin ) {
      CorrectorLote.Estado s = l.estado();
      if ( s.hechas >= 1 && "running".equals( s.resultados.get( indice( s, "b_lento" ) ).estado ) ) {
        break;
      }
      Thread.sleep( 200 );
    }
    Thread.sleep( 1000 );
    l.cancelar();
    cierto( l.terminado(), "el lote cerro" );
    CorrectorLote.Estado s = l.estado();
    igual( CorrectorLote.CANCELADO, s.estado, "cancelled" );
    igual( 1, s.hechas, "solo paula" );
    igual( 10.0, s.resultados.get( indice( s, "a_paula" ) ).nota, "paula 10" );
    nulo( s.resultados.get( indice( s, "b_lento" ) ).nota, "la lenta sin nota" );
    String csv = EntornoKettle.leer( new File( salida, "notas.csv" ) );
    contiene( csv, "\"a_paula\"", "la planilla tiene a paula" );
    contiene( csv, "\"b_lento\"", "y la que no termino" );
    falso( new File( salida, "b_lento.json" ).exists(), "sin el parcial de la cancelada" );
  }

  private static int indice( CorrectorLote.Estado s, String alumno ) {
    for ( int i = 0; i < s.resultados.size(); i++ ) {
      if ( alumno.equals( s.resultados.get( i ).alumno ) ) {
        return i;
      }
    }
    throw new AssertionError( "no esta " + alumno );
  }

  // ------------------------------------------------------------------ crear pack (hijas)

  /**
   * El pack creado desde la solucion del ejercicio 3 con un caso visible (los datos que lee la solucion) y uno oculto:
   * las mismas columnas, tipos, claves, reglas y salida esperada que el pack de las fixtures; valida; la solucion saca
   * 10 con en_US; la copia para alumnos (.zip) no tiene la solucion ni el caso oculto y tambien valida.
   */
  public void pruebaCrearPack() throws Exception {
    File profe = new File( base(), "profe" );
    File fixture = dirPack( VENTAS );
    copiar( new File( fixture, "solucion/03_ventas_por_producto.ktr" ), new File( profe,
      "03_ventas_por_producto.ktr" ) );
    copiar( new File( fixture, "datos/visible/ventas.csv" ), new File( profe, "datos/ventas.csv" ) );
    copiar( new File( fixture, "datos/oculto/ventas.csv" ), new File( profe, "oculto/ventas.csv" ) );
    Pack fx = pack( VENTAS );

    CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
    op.solucion = new File( profe, "03_ventas_por_producto.ktr" );
    op.destino = new File( base(), "packs/ventas-creado" );
    op.titulo = "Ejercicio 3 - Ventas por producto";
    op.idioma = "es";
    op.casos = Arrays.asList( new CorrectorCrearPack.CasoNuevo( "visible", null, false ),
      new CorrectorCrearPack.CasoNuevo( "oculto", new File( profe, "oculto" ), true ) );
    op.politica = CorrectorJson.copia( fx.politica, PoliticaPack.class );
    op.copiaAlumnos = new File( base(), "packs/ventas-alumnos.zip" );
    op.procesos = procesos();
    op.dirTrabajo = new File( base(), "crear-trabajo" );
    CorrectorCrearPack.Creado c = CorrectorCrearPack.crear( op );

    igual( op.destino.getAbsolutePath(), c.pack, "pack_path" );
    igual( Collections.singletonList( "ventas.csv" ), c.detectado.entradas, "entradas detectadas" );
    igual( "Escribir totales", c.detectado.stepSalida, "step de salida" );
    igual( "TextFileOutput", c.detectado.tipoSalida, "tipo" );
    igual( Collections.singletonList( "producto" ), c.detectado.claves, "clave del Group by" );
    igual( CorrectorJson.escribir( fx.salida.columnas, false ), CorrectorJson.escribir( c.detectado.columnas, false ),
      "columnas, tipos y mascaras como las del pack de las fixtures" );
    List<String> reglas = new ArrayList<String>();
    for ( Regla r : c.detectado.reglas ) {
      reglas.add( r.id + ":" + r.tipo + ( r.valor == null ? "" : ":" + r.valor ) );
    }
    igual( Arrays.asList( "usa_group_by:must_use", "sin_codigo:must_not_use", "max_pasos:max_steps:8" ), reglas,
      "reglas sugeridas" );
    igual( 2, c.casos.size(), "dos casos" );
    igual( 4, c.casos.get( 0 ).filas, "4 filas en el visible" );
    igual( 6, c.casos.get( 0 ).columnas, "6 columnas" );
    for ( String caso : Arrays.asList( "visible", "oculto" ) ) {
      igual( EntornoKettle.leer( new File( fixture, "esperado/" + caso + ".csv" ) ).replace( "\r\n", "\n" ),
        EntornoKettle.leer( new File( op.destino, "esperado/" + caso + ".csv" ) ), "esperado/" + caso
          + ".csv igual al de las fixtures" );
    }
    List<String> codigos = new ArrayList<String>();
    for ( Aviso a : c.avisos ) {
      codigos.add( a.codigo + ": " + a.detalle );
    }
    igual( Collections.emptyList(), codigos, "sin avisos" );
    noNulo( c.verificacion, "verificacion del otro locale" );
    igual( "en_US", c.verificacion.locale, "el otro locale" );
    igual( 10.0, c.verificacion.nota, "la solucion saca 10 con en_US" );

    // el pack: valida, misma rubrica y reglas que las fixtures, con el enunciado esqueleto
    List<CorrectorPack.Problema> problemas = CorrectorPack.validar( op.destino );
    for ( CorrectorPack.Problema pr : problemas ) {
      falso( pr.esError(), "el pack creado valida: " + pr );
    }
    Pack p = CorrectorJson.leer( new File( op.destino, "pack.json" ), Pack.class ).completar();
    igual( CorrectorJson.escribir( fx.rubrica, false ), CorrectorJson.escribir( p.rubrica, false ), "rubrica" );
    igual( "solucion/03_ventas_por_producto.ktr", p.solucion, "solution" );
    igual( Arrays.asList( "visible", "oculto" ), Arrays.asList( p.casos.get( 0 ).id, p.casos.get( 1 ).id ), "casos" );
    cierto( p.casos.get( 1 ).oculto, "el oculto" );
    igual( Arrays.asList( "yyyy-MM-dd", "dd/MM/yyyy" ), p.comparar.formatosFecha, "date_formats por la fecha" );
    igual( "es_ES", p.politica.locale, "la politica pedida" );
    cierto( new File( op.destino, "datos/oculto/ventas.csv" ).isFile(), "datos del oculto" );
    String md = EntornoKettle.leer( new File( op.destino, "enunciado.md" ) );
    contiene( md, "# Ejercicio 3 - Ventas por producto", "titulo" );
    contiene( md, "| `importe_medio` | Number | `0.00` |", "columnas" );
    contiene( md, "`ventas.csv`", "entradas" );

    // la copia para alumnos: sin solucion ni caso oculto, valida
    File za = op.copiaAlumnos;
    cierto( za.isFile(), "copia para alumnos .zip" );
    List<String> nombres = new ArrayList<String>();
    try ( ZipFile z = new ZipFile( za ) ) {
      for ( java.util.Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
        nombres.add( en.nextElement().getName() );
      }
    }
    cierto( nombres.contains( "pack.json" ) && nombres.contains( "esperado/visible.csv" ) && nombres.contains(
      "datos/visible/ventas.csv" ) && nombres.contains( "enunciado.md" ), "lo del caso visible: " + nombres );
    for ( String n : nombres ) {
      falso( n.startsWith( "solucion" ) || n.contains( "oculto" ), "no tiene " + n );
    }
    try ( CorrectorPack ca = CorrectorPack.abrir( za ) ) {
      cierto( ca.valido(), "la copia para alumnos valida: " + ca.problemas() );
      igual( 1, ca.pack().casos.size(), "solo el visible" );
      nulo( ca.pack().solucion, "sin solution" );
    }

    // el destino ya existe: no se pisa
    CorrectorCrearPack.Fallo f = lanza( CorrectorCrearPack.Fallo.class, () -> CorrectorCrearPack.crear( op ),
      "destino existente" );
    igual( CorrectorCrearPack.E_DESTINO, f.codigo, "pack_exists" );
  }

  /**
   * hugo (decimal vacio al leer el CSV, D3) como solucion: con es_ES lee 2.50 como 250 y con en_US como 2,5. El pack se
   * arma con es_ES y la verificacion con en_US no da 10: aviso locale_dependent. Sin claves pedidas ni detectables por
   * un Sort, y con la copia para alumnos en carpeta.
   */
  public void pruebaCrearPackDependeDelLocale() throws Exception {
    File profe = new File( base(), "profe-hugo" );
    copiar( entrega( VENTAS, "hugo_decimal.ktr" ), new File( profe, "hugo_decimal.ktr" ) );
    copiar( new File( dirPack( VENTAS ), "datos/visible/ventas.csv" ), new File( profe, "datos/ventas.csv" ) );
    String ktr = EntornoKettle.leer( new File( profe, "hugo_decimal.ktr" ) );
    CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
    op.solucion = new File( profe, "hugo_decimal.ktr" );
    op.destino = new File( base(), "packs/hugo" );
    op.idioma = "en";
    op.politica = CorrectorJson.copia( pack( VENTAS ).politica, PoliticaPack.class );
    op.copiaAlumnos = new File( base(), "packs/hugo-alumnos" );
    op.procesos = procesos();
    CorrectorCrearPack.Creado c = CorrectorCrearPack.crear( op );
    boolean aviso = false;
    for ( Aviso a : c.avisos ) {
      aviso |= CorrectorCrearPack.AVISO_LOCALE.equals( a.codigo );
    }
    cierto( aviso, "depende del locale: " + c.avisos );
    cierto( c.verificacion.nota != null && c.verificacion.nota < 10, "con en_US no saca 10: " + c.verificacion.nota );
    cierto( c.verificacion.fallados.contains( "values" ), "fallan los valores: " + c.verificacion.fallados );
    contiene( ktr, "<decimal/>", "hugo tiene el decimal vacio" );
    // el esperado se escribio con es_ES: los importes x100
    contiene( EntornoKettle.leer( new File( op.destino, "esperado/visible.csv" ) ), "2000.00", "Café 20 x 100" );
    cierto( new File( op.copiaAlumnos, "pack.json" ).isFile(), "copia para alumnos en carpeta" );
    falso( new File( op.copiaAlumnos, "solucion" ).exists(), "sin la solucion" );
    contiene( EntornoKettle.leer( new File( op.destino, "enunciado.md" ) ), "Build a transformation", "enunciado en en" );
  }

  /** Captura query: la plantilla y el SQL del docente; el esperado sale de la consulta y los tipos de los valores. */
  public void pruebaCrearPackQuery() throws Exception {
    File fixture = dirPack( TABLA );
    Pack fx = pack( TABLA );
    File profe = new File( base(), "profe-tabla" );
    copiar( new File( fixture, fx.solucion ), new File( profe, "03_a_tabla.ktr" ) );
    copiar( new File( fixture, "datos/visible/ventas.csv" ), new File( profe, "datos/ventas.csv" ) );
    CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
    op.solucion = new File( profe, "03_a_tabla.ktr" );
    op.destino = new File( base(), "packs/tabla.zip" );
    op.idioma = "es";
    op.captura = Pack.Salida.CAPTURA_QUERY;
    op.plantilla = new File( fixture, fx.salida.base.plantilla );
    op.sql = fx.salida.sql;
    op.politica = CorrectorJson.copia( fx.politica, PoliticaPack.class );
    op.politica.permitirSteps.clear(); // los condicionales que usa la solucion se agregan solos
    op.verificarLocales = false;
    op.procesos = procesos();
    CorrectorCrearPack.Creado c = CorrectorCrearPack.crear( op );
    cierto( op.destino.isFile(), "el pack .zip" );
    nulo( c.verificacion, "sin verificacion de locales" );
    try ( CorrectorPack cp = CorrectorPack.abrir( op.destino ) ) {
      cierto( cp.valido(), "valida: " + cp.problemas() );
      Pack p = cp.pack();
      igual( "query", p.salida.captura, "captura" );
      cierto( p.politica.permite( "TableOutput" ), "TableOutput permitido: " + p.politica.permitirSteps );
      igual( Collections.singletonList( "producto" ), p.comparar.columnasClave, "clave" );
      List<String> tipos = new ArrayList<String>();
      for ( Pack.Columna col : p.salida.columnas ) {
        tipos.add( col.nombre + ":" + col.tipo );
      }
      igual( Arrays.asList( "producto:String", "unidades:Integer", "importe_total:Number", "num_ventas:Integer",
        "importe_medio:Number", "ultima_venta:Date" ), tipos, "tipos deducidos de los valores" );
      igual( EntornoKettle.leer( new File( fixture, "esperado/visible.csv" ) ).replace( "\r\n", "\n" ),
        EntornoKettle.leer( cp.esperado( p.casos.get( 0 ) ) ), "el esperado de la consulta" );
    }
  }

  // ------------------------------------------------------------------ ayudas

  private Pack pack( String packId ) throws IOException {
    return CorrectorJson.leer( new File( dirPack( packId ), "pack.json" ), Pack.class ).completar();
  }

  private static List<String> avisos( List<Aviso> l ) {
    List<String> t = new ArrayList<String>();
    for ( Aviso a : l == null ? Collections.<Aviso>emptyList() : l ) {
      t.add( a.codigo + ": " + a.detalle );
    }
    return t;
  }

  private static boolean tieneAviso( ResultadoCorreccion r, String codigo ) {
    for ( Aviso a : r.avisos ) {
      if ( codigo.equals( a.codigo ) ) {
        return true;
      }
    }
    return false;
  }

  private static void copiar( File de, File a ) throws IOException {
    a.getParentFile().mkdirs();
    Files.copy( de.toPath(), a.toPath(), StandardCopyOption.REPLACE_EXISTING );
  }
}
