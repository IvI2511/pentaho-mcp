package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;

/**
 * Comparacion del corrector (C2, docs/corrector-diseno.md §4) sin Kettle: lectura de CSV (BOM, encierro,
 * separador, encoding), numeros con el formato que declaro el alumno, fechas, tolerancias, clave, multiconjunto,
 * posicion, claves repetidas, renombres y avisos. Los resultados del prototipo salen de las salidas fijas de
 * {@code corrector/salidas/} leidas con el Text file output de cada entrega, y las notas de casos.json se
 * reconstruyen sumando los puntos de reglas a los de salida.
 *
 * @dueño C2
 */
public class PruebaCorrectorComparar {

  private static final Locale ES = new Locale( "es", "ES" );
  private static final char REEMPLAZO = (char) 0xFFFD;

  private Pack ventas;
  private Pack tabla;

  public void preparar() throws Exception {
    ventas = pack( CorrectorRecursos.VENTAS );
    tabla = pack( CorrectorRecursos.TABLA );
  }

  // ------------------------------------------------------------------ lectura de CSV

  public void pruebaCsvBasico() {
    CorrectorTabla t = CorrectorTabla.leerTexto( " a ;b;c\n1;;x\r\n2;3;y\r\n\n4;5;z", new CorrectorTabla.Lectura() );
    igual( Arrays.asList( "a", "b", "c" ), t.columnas, "nombres con trim" );
    igual( 3, t.cantidadFilas(), "la linea vacia se saltea y la ultima sin salto cuenta" );
    nulo( t.celda( 0, 1 ), "vacio = null" );
    igual( "y", t.celda( 1, 2 ), "\\r\\n" );
    igual( "z", t.celda( 2, 2 ), "ultima linea sin salto" );
    igual( 1, t.indice( " B " ), "indice sin mayusculas y con trim" );
    igual( -1, t.indice( "d" ), "columna que no existe" );
    falso( t.cortada, "no cortada" );
    falso( t.conBom, "sin BOM" );

    CorrectorTabla mac = CorrectorTabla.leerTexto( "a;b\r1;2\r3;4\r", new CorrectorTabla.Lectura() );
    igual( 2, mac.cantidadFilas(), "fin de linea \\r" );
    igual( "4", mac.celda( 1, 1 ), "fin de linea \\r: celda" );

    CorrectorTabla vacia = CorrectorTabla.leerTexto( "", new CorrectorTabla.Lectura() );
    igual( 0, vacia.columnas.size(), "texto vacio: sin columnas" );
    igual( 0, vacia.cantidadFilas(), "texto vacio: sin filas" );
    CorrectorTabla vaciaConNombres = CorrectorTabla.leerTexto( "", new CorrectorTabla.Lectura().nombres( Arrays.asList(
      "x", "y" ) ) );
    igual( Arrays.asList( "x", "y" ), vaciaConNombres.columnas, "archivo vacio: las columnas del step" );
    CorrectorTabla soloEncabezado = CorrectorTabla.leerTexto( "a;b\n", new CorrectorTabla.Lectura() );
    igual( 2, soloEncabezado.columnas.size(), "solo encabezado" );
    igual( 0, soloEncabezado.cantidadFilas(), "solo encabezado: sin filas" );
  }

  public void pruebaCsvEncierro() {
    String csv = "\"producto\";\"nota\"\n" //
      + "\"Caf;e\";\"dijo \"\"hola\"\"\"\n" //
      + "\"dos\nlineas\";\"\"\n" //
      + "\"abc\"def;sin encierro\n" //
      + "a\"b;\"\"\n";
    CorrectorTabla t = CorrectorTabla.leerTexto( csv, new CorrectorTabla.Lectura() );
    igual( Arrays.asList( "producto", "nota" ), t.columnas, "encabezado con encierro" );
    igual( 4, t.cantidadFilas(), "filas" );
    igual( "Caf;e", t.celda( 0, 0 ), "separador dentro del encierro" );
    igual( "dijo \"hola\"", t.celda( 0, 1 ), "encierro doblado = literal" );
    igual( "dos\nlineas", t.celda( 1, 0 ), "salto de linea dentro del encierro" );
    nulo( t.celda( 1, 1 ), "\"\" = null" );
    igual( "abcdef", t.celda( 2, 0 ), "lo que sigue al cierre se agrega" );
    igual( "a\"b", t.celda( 3, 0 ), "comilla en el medio = literal" );

    CorrectorTabla sin = CorrectorTabla.leerTexto( "a;b\n\"x\";y\n", new CorrectorTabla.Lectura().encierro( "" ) );
    igual( "\"x\"", sin.celda( 0, 0 ), "sin encierro las comillas quedan" );

    CorrectorTabla abierto = CorrectorTabla.leerTexto( "a;b\n1;\"sin cierre", new CorrectorTabla.Lectura() );
    igual( "sin cierre", abierto.celda( 0, 1 ), "encierro sin cierre: hasta el final" );
  }

  public void pruebaCsvSeparadores() {
    igual( "\t", CorrectorTabla.traducir( "\\t" ), "\\t" );
    igual( "\t", CorrectorTabla.traducir( "$[09]" ), "$[09]" );
    igual( ";", CorrectorTabla.traducir( ";" ), ";" );
    igual( "$[zz]", CorrectorTabla.traducir( "$[zz]" ), "$[] que no es hex queda" );

    CorrectorTabla tab = CorrectorTabla.leerTexto( "a\tb\n1\t2\n", new CorrectorTabla.Lectura().separador( "\\t" ) );
    igual( "2", tab.celda( 0, 1 ), "tabulador" );
    CorrectorTabla doble = CorrectorTabla.leerTexto( "a||b\n1||2|3\n", new CorrectorTabla.Lectura().separador( "||" ) );
    igual( "2|3", doble.celda( 0, 1 ), "separador de dos caracteres" );
    CorrectorTabla coma = CorrectorTabla.leerTexto( "a,b\n\"1,5\",2\n", new CorrectorTabla.Lectura().separador( "," ) );
    igual( "1,5", coma.celda( 0, 0 ), "coma" );
    CorrectorTabla una = CorrectorTabla.leerTexto( "a;b\nx;y\n", new CorrectorTabla.Lectura().separador( "" ) );
    igual( Collections.singletonList( "a;b" ), una.columnas, "sin separador: una columna" );

    CorrectorTabla sinEnc = CorrectorTabla.leerTexto( "1;2\n3;4\n", new CorrectorTabla.Lectura().encabezado( false )
      .nombres( Arrays.asList( "x", "y" ) ) );
    igual( Arrays.asList( "x", "y" ), sinEnc.columnas, "sin encabezado: los nombres de la lectura" );
    igual( 2, sinEnc.cantidadFilas(), "sin encabezado: la primera linea es una fila" );
    CorrectorTabla anon = CorrectorTabla.leerTexto( "1;2\n", new CorrectorTabla.Lectura().encabezado( false ) );
    igual( Arrays.asList( "col1", "col2" ), anon.columnas, "sin encabezado ni nombres" );
  }

  public void pruebaCsvBomYEncoding() throws Exception {
    File dir = EntornoKettle.salida( "corrector-comparar" );
    byte[] utf8 = "producto;n\nCafé;1\n".getBytes( StandardCharsets.UTF_8 );

    File conBom = new File( dir, "bom.csv" );
    Files.write( conBom.toPath(), concat( new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }, utf8 ) );
    CorrectorTabla t = CorrectorTabla.leer( conBom, new CorrectorTabla.Lectura().encoding( "windows-1252", "UTF-8" ) );
    cierto( t.conBom, "BOM detectado" );
    igual( "UTF-8", t.encoding, "el BOM gana sobre el encoding declarado" );
    igual( "producto", t.columnas.get( 0 ), "el BOM no queda pegado al primer nombre" );
    igual( "Café", t.celda( 0, 0 ), "con BOM" );

    File cp = new File( dir, "nativo.csv" );
    Files.write( cp.toPath(), "producto;n\nCafé;1\n".getBytes( Charset.forName( "windows-1252" ) ) );
    CorrectorTabla nativo = CorrectorTabla.leer( cp, new CorrectorTabla.Lectura().encoding( "", "windows-1252" ) );
    igual( "Café", nativo.celda( 0, 0 ), "encoding vacio = el del pack (P17)" );
    igual( "windows-1252", nativo.encoding, "charset usado" );
    CorrectorTabla roto = CorrectorTabla.leer( cp, new CorrectorTabla.Lectura().encoding( null, "UTF-8" ) );
    igual( "Caf" + REEMPLAZO, roto.celda( 0, 0 ), "bytes que no son UTF-8: U+FFFD" );

    CorrectorTabla raro = CorrectorTabla.leer( cp, new CorrectorTabla.Lectura().encoding( "NO-EXISTE-9",
      "windows-1252" ) );
    igual( "Café", raro.celda( 0, 0 ), "encoding desconocido: el del pack" );
    igual( CorrectorTabla.ENCODING_DESCONOCIDO, raro.avisos.get( 0 ).codigo, "aviso de encoding" );
    igual( "NO-EXISTE-9", raro.avisos.get( 0 ).detalle, "detalle del aviso" );

    File u16 = new File( dir, "utf16.csv" );
    Files.write( u16.toPath(), concat( new byte[] { (byte) 0xFF, (byte) 0xFE },
      "producto;n\nTé;2\n".getBytes( StandardCharsets.UTF_16LE ) ) );
    CorrectorTabla t16 = CorrectorTabla.leer( u16, new CorrectorTabla.Lectura() );
    igual( "Té", t16.celda( 0, 0 ), "UTF-16LE con BOM" );
    cierto( t16.conBom, "BOM UTF-16" );

    CorrectorTabla texto = CorrectorTabla.leerTexto( (char) 0xFEFF + "a;b\n1;2\n", null );
    cierto( texto.conBom, "U+FEFF al principio del texto" );
    igual( "a", texto.columnas.get( 0 ), "sin el U+FEFF" );
  }

  public void pruebaCsvIrregularesPieTopeYNulos() {
    CorrectorTabla t = CorrectorTabla.leerTexto( "a;b;\n1;2;\n3\n4;5;6;7\n", new CorrectorTabla.Lectura() );
    igual( Arrays.asList( "a", "b" ), t.columnas, "separador al final del encabezado" );
    igual( "2", t.celda( 0, 1 ), "separador al final de la fila" );
    nulo( t.celda( 1, 1 ), "fila corta: null" );
    igual( "5", t.celda( 2, 1 ), "fila larga: se recorta" );
    igual( 1, t.avisos.size(), "un aviso" );
    igual( CorrectorTabla.FILAS_IRREGULARES, t.avisos.get( 0 ).codigo, "filas irregulares" );
    igual( "1", t.avisos.get( 0 ).detalle, "una fila irregular" );

    CorrectorTabla pie = CorrectorTabla.leerTexto( "a;b\n1;2\na;b\n", new CorrectorTabla.Lectura() );
    igual( 1, pie.cantidadFilas(), "el pie igual al encabezado se quita" );
    igual( CorrectorTabla.PIE_QUITADO, pie.avisos.get( 0 ).codigo, "aviso del pie" );

    CorrectorTabla tope = CorrectorTabla.leerTexto( "a\n1\n2\n3\n", new CorrectorTabla.Lectura().maxFilas( 2 ) );
    igual( 2, tope.cantidadFilas(), "max_rows" );
    cierto( tope.cortada, "cortada" );
    CorrectorTabla justo = CorrectorTabla.leerTexto( "a\n1\n2\n", new CorrectorTabla.Lectura().maxFilas( 2 ) );
    falso( justo.cortada, "exactamente max_rows no corta" );

    CorrectorTabla.Formato nulo = new CorrectorTabla.Formato( "String", null, null, null, null, "N/A" );
    CorrectorTabla conNulo = CorrectorTabla.leerTexto( "a;b\nN/A;N/A\n", new CorrectorTabla.Lectura()
      .formatos( Arrays.asList( nulo, null ) ) );
    nulo( conNulo.celda( 0, 0 ), "nullif declarado = null" );
    igual( "N/A", conNulo.celda( 0, 1 ), "sin nullif queda el texto" );
    igual( nulo, conNulo.formato( 0 ), "formato por posicion" );
    nulo( conNulo.formato( 5 ), "formato fuera de rango" );

    CorrectorTabla unaCol = CorrectorTabla.leerTexto( "a\n1\n\n2\n\n\n", new CorrectorTabla.Lectura() );
    igual( 3, unaCol.cantidadFilas(), "una columna: la vacia del medio es un null; las del final no cuentan" );
    nulo( unaCol.celda( 1, 0 ), "null del medio" );
  }

  public void pruebaTablaDeFilasCapturadas() {
    Calendar c = Calendar.getInstance();
    c.clear();
    c.set( 2024, Calendar.MAY, 4 );
    Calendar h = Calendar.getInstance();
    h.clear();
    h.set( 2024, Calendar.MAY, 4, 13, 5, 7 );
    List<List<Object>> filas = new ArrayList<>();
    filas.add( Arrays.<Object>asList( new BigDecimal( "7.2333" ), 7.5d, 12L, c.getTime(), h.getTime(), true, "" ) );
    filas.add( Arrays.<Object>asList( new BigDecimal( "1E+2" ), null, 3, null, null, false ) );
    CorrectorTabla t = CorrectorTabla.de( Arrays.asList( " a ", "b", "c", "d", "e", "f", "g" ), filas );
    igual( "a", t.columnas.get( 0 ), "nombres con trim" );
    igual( "7.2333", t.celda( 0, 0 ), "BigDecimal plano" );
    igual( "100", t.celda( 1, 0 ), "BigDecimal sin exponente" );
    igual( "7.5", t.celda( 0, 1 ), "Double" );
    igual( "12", t.celda( 0, 2 ), "Long" );
    igual( "2024-05-04", t.celda( 0, 3 ), "fecha a medianoche" );
    igual( "2024-05-04 13:05:07", t.celda( 0, 4 ), "fecha con hora" );
    igual( "Y", t.celda( 0, 5 ), "booleano" );
    nulo( t.celda( 0, 6 ), "\"\" = null" );
    nulo( t.celda( 1, 1 ), "null" );
    nulo( t.celda( 1, 6 ), "fila corta" );
    nulo( t.formato( 0 ), "sin formatos" );
    igual( 0, CorrectorTabla.vacia( Arrays.asList( "x" ) ).cantidadFilas(), "tabla vacia" );
  }

  public void pruebaEsperadasDeLosPacks() throws Exception {
    CorrectorTabla v = esperada( ventas, CorrectorRecursos.VENTAS, "visible" );
    igual( ventas.salida.nombresColumnas(), v.columnas, "columnas del esperado" );
    igual( 4, v.cantidadFilas(), "filas" );
    igual( "Café", v.celda( 0, 0 ), "UTF-8" );
    Pack inline = CorrectorJson.leer( CorrectorRecursos.pack( CorrectorRecursos.INLINE ), Pack.class ).completar();
    CorrectorTabla in = CorrectorTabla.esperadaTexto( inline.inline.get( inline.caso( "visible" ).esperado ), inline );
    igual( v.columnas, in.columnas, "inline: columnas" );
    igual( v.cantidadFilas(), in.cantidadFilas(), "inline: filas" );
    Comparacion c = CorrectorComparar.comparar( v, in, inline, null );
    igual( 1.0, c.puntajeValores, "inline = carpeta" );

    // sin encabezado: los nombres de output.columns
    Pack sin = CorrectorJson.copia( ventas, Pack.class );
    sin.salida.csvEsperado.encabezado = false;
    CorrectorTabla s = CorrectorTabla.esperadaTexto( "Café;8;20.00;4;5.00;2024-05-04\n", sin );
    igual( ventas.salida.nombresColumnas(), s.columnas, "sin encabezado" );
    igual( 1, s.cantidadFilas(), "sin encabezado: filas" );
  }

  // ------------------------------------------------------------------ numeros

  public void pruebaNumerosConElFormatoDelAlumno() {
    CorrectorTabla.Formato decVacio = fmt( "0.00", "", "" );
    numero( "20,00", decVacio, ES, "20.00", true, false, "P16: decimal vacio en es_ES" );
    numero( "20.00", decVacio, ES, "20.00", false, true, "punto en es_ES sin miles en la mascara: plano" );
    numero( "20.00", fmt( "0.00", ".", "" ), ES, "20.00", false, true, "decimal . declarado en es_ES" );
    numero( "1.234,50", fmt( "#,##0.00", "", "" ), ES, "1234.50", true, false, "miles y coma en es_ES" );
    numero( "1.234", fmt( "#,##0", "", "" ), ES, "1234", false, false, "miles con punto: no es 1,234" );
    numero( "1,234.50", fmt( "#,##0.00", "", "" ), Locale.US, "1234.50", false, false, "miles en en_US" );
    numero( "1 234,5", fmt( "#,##0.0", ",", " " ), ES, "1234.5", true, false, "miles con espacio" );
    numero( "€ 20,00", new CorrectorTabla.Formato( "Number", "€ 0.00", "", "", "€", null ), ES, "20.00", true,
      false, "moneda" );
    numero( "12,5%", fmt( "0.0%", "", "" ), ES, "0.125", true, false, "porcentaje" );
    numero( "(5.00)", fmt( "0.00;(0.00)", ".", "" ), Locale.US, "-5.00", false, false, "negativo entre parentesis" );
    numero( "7.5", null, ES, "7.5", false, true, "sin formato (step/query)" );
    numero( "-3", null, ES, "-3", false, true, "entero negativo" );
    numero( "1e5", null, ES, "1E+5", false, true, "exponente" );
    numero( "2,5", null, Locale.US, "2.5", true, false, "heuristica: una coma es el decimal" );
    numero( "1.234.567", null, Locale.US, "1234567", false, false, "heuristica: puntos repetidos son miles" );
    numero( "1,234,567", null, Locale.US, "1234567", false, false, "heuristica: comas repetidas son miles" );
    numero( "1.234,5", null, Locale.US, "1234.5", true, false, "heuristica: el ultimo es el decimal" );
    numero( "1'234.5", null, Locale.US, "1234.5", false, false, "apostrofe de miles" );
    nulo( CorrectorComparar.numero( "abc", null, ES ), "texto" );
    nulo( CorrectorComparar.numero( "1e99999", null, ES ), "exponente absurdo" );
    nulo( CorrectorComparar.numero( "", null, ES ), "vacio" );
  }

  // ------------------------------------------------------------------ el prototipo con tablas fijas

  /** Las comparaciones de resultados/ (prototipo) salen iguales desde las salidas fijas. */
  public void pruebaResultadosDelPrototipo() throws Exception {
    String[][] lista = { { CorrectorRecursos.VENTAS, "ana_orden_distinto" }, { CorrectorRecursos.VENTAS,
      "bruno_agregado_mal" }, { CorrectorRecursos.VENTAS, "carla_falta_columna" }, { CorrectorRecursos.VENTAS,
      "elena_sin_ordenar" }, { CorrectorRecursos.VENTAS, "gabi_renombra" }, { CorrectorRecursos.VENTAS,
      "hugo_decimal" }, { CorrectorRecursos.TABLA, "ines_tabla_ok" }, { CorrectorRecursos.TABLA, "juan_tabla_max" } };
    for ( String[] x : lista ) {
      ResultadoCorreccion proto = CorrectorJson.leer( CorrectorRecursos.resultado( x[ 1 ] ),
        ResultadoCorreccion.class );
      igual( x[ 0 ].equals( CorrectorRecursos.VENTAS ) ? 2 : 1, proto.casos.size(), x[ 1 ] + ": casos" );
      for ( ResultadoCaso rc : proto.casos ) {
        noNulo( rc.comparacion, x[ 1 ] + "/" + rc.id + ": el prototipo comparo" );
        Comparacion c = comparar( x[ 0 ], x[ 1 ], rc.id, null );
        mismaComparacion( rc.comparacion, c, x[ 1 ] + "/" + rc.id );
      }
    }
  }

  /** Las notas de casos.json salen de la comparacion mas los puntos de las reglas (estas entregas corren). */
  public void pruebaNotasDeCasosJson() throws Exception {
    Object[][] lista = { { "paula_correcta", 2.0 }, { "ana_orden_distinto", 2.0 }, { "bruno_agregado_mal", 2.0 }, {
      "carla_falta_columna", 2.0 }, { "elena_sin_ordenar", 2.0 }, { "gabi_renombra", 2.0 }, { "hugo_decimal", 2.0 },
      { "rodri_filtra_de_mas", 2.0 }, { "quique_dibuja_salida", 1.0 }, { "sofi_fecha_dmy", 2.0 }, {
        "t3_decimal_coma", 2.0 } };
    for ( Object[] x : lista ) {
      String alumno = (String) x[ 0 ];
      double esperada = CorrectorRecursos.esperado( CorrectorRecursos.VENTAS, alumno + ".ktr" ).path( "grade" )
        .asDouble();
      igual( esperada, nota( ventas, casos( CorrectorRecursos.VENTAS, alumno, ventas ), (Double) x[ 1 ] ),
        alumno + ": nota" );
    }
    for ( String alumno : Arrays.asList( "ines_tabla_ok", "juan_tabla_max" ) ) {
      double esperada = CorrectorRecursos.esperado( CorrectorRecursos.TABLA, alumno + ".ktr" ).path( "grade" )
        .asDouble();
      igual( esperada, nota( tabla, casos( CorrectorRecursos.TABLA, alumno, tabla ), 0 ), alumno + ": nota" );
    }
  }

  // ------------------------------------------------------------------ formato del alumno: P16, P17, fechas

  public void pruebaP16ComaDecimalDa10() throws Exception {
    List<Aviso> avisos = new ArrayList<>();
    Map<String, Comparacion> c = casos( CorrectorRecursos.VENTAS, "t3_decimal_coma", ventas, avisos );
    for ( Comparacion x : c.values() ) {
      igual( 1.0, x.puntajeValores, "t3: valores" );
      igual( 0, x.filas.distintas, "t3: sin diferencias" );
    }
    igual( 10.0, nota( ventas, c, 2.0 ), "P16 da 10" );
    Aviso coma = aviso( avisos, Aviso.COMA_DECIMAL );
    noNulo( coma, "aviso de coma decimal" );
    igual( "importe_total, importe_medio", coma.detalle, "columnas con coma" );

    // con strict_format la coma resta
    Pack estricto = conOpciones( ventas, o -> o.formatoEstricto = true );
    List<Aviso> av2 = new ArrayList<>();
    Map<String, Comparacion> c2 = casos( CorrectorRecursos.VENTAS, "t3_decimal_coma", estricto, av2 );
    igual( 0.0, c2.get( "visible" ).puntajeValores, "strict_format: la coma resta" );
    igual( Integer.valueOf( 4 ), c2.get( "visible" ).diferenciasPorColumna.get( "importe_total" ), "por columna" );
    noNulo( aviso( av2, Aviso.COMA_DECIMAL ), "con strict_format tambien se avisa (la pista dice que resta)" );
    igual( 6.0, nota( estricto, c2, 2.0 ), "strict_format: como el prototipo" );

    // con en_US la misma salida tambien se lee (la heuristica toma la coma sola como decimal)
    Pack us = CorrectorJson.copia( ventas, Pack.class );
    us.politica.locale = "en_US";
    igual( 10.0, nota( us, casos( CorrectorRecursos.VENTAS, "t3_decimal_coma", us ), 2.0 ), "en_US" );
  }

  public void pruebaP17EncodingVacio() throws Exception {
    Pack nativo = CorrectorJson.copia( ventas, Pack.class );
    nativo.politica.encoding = "windows-1252";
    Map<String, Comparacion> bien = casos( CorrectorRecursos.VENTAS, "t4_encoding_vacio", nativo );
    igual( 10.0, nota( nativo, bien, 2.0 ), "leido con el charset con que se escribio" );

    List<Aviso> avisos = new ArrayList<>();
    Map<String, Comparacion> mal = casos( CorrectorRecursos.VENTAS, "t4_encoding_vacio", ventas, avisos );
    Comparacion v = mal.get( "visible" );
    igual( 2, v.filas.iguales, "visible: Medialuna y Tostado" );
    igual( 2, v.filas.faltan, "visible: Café y Té faltan" );
    igual( 2, v.filas.sobran, "visible: Caf? y T? sobran" );
    igual( 0.5, v.puntajeValores, "visible" );
    igual( 0.75, mal.get( "oculto" ).puntajeValores, "oculto" );
    igual( 8.5, nota( ventas, mal, 2.0 ), "P17 con el charset equivocado" );
    Aviso m = aviso( avisos, CorrectorComparar.MOJIBAKE );
    noNulo( m, "aviso de mojibake" );
    igual( "producto", m.detalle, "columna con mojibake" );
    contiene( v.ejemplos.sobran.get( 0 ).clave, String.valueOf( REEMPLAZO ), "la clave rota se muestra" );
  }

  public void pruebaFechasConOtraMascara() throws Exception {
    List<Aviso> avisos = new ArrayList<>();
    Map<String, Comparacion> c = casos( CorrectorRecursos.VENTAS, "sofi_fecha_dmy", ventas, avisos );
    igual( 10.0, nota( ventas, c, 2.0 ), "date_formats acepta dd/MM/yyyy" );
    Aviso f = aviso( avisos, CorrectorComparar.FORMATO_FECHA );
    noNulo( f, "aviso de fecha con otra mascara" );
    igual( "ultima_venta", f.detalle, "columna" );

    Pack estricto = conOpciones( ventas, o -> o.formatoEstricto = true );
    List<Aviso> av2 = new ArrayList<>();
    Map<String, Comparacion> c2 = casos( CorrectorRecursos.VENTAS, "sofi_fecha_dmy", estricto, av2 );
    igual( 0.0, c2.get( "visible" ).puntajeValores, "strict_format: la otra mascara resta" );
    noNulo( aviso( av2, CorrectorComparar.FORMATO_FECHA ), "strict_format: se avisa igual" );
    igual( 6.0, nota( estricto, c2, 2.0 ), "strict_format: como el prototipo" );

    Pack sinFormatos = conOpciones( ventas, o -> o.formatosFecha = new ArrayList<>() );
    igual( 0.0, casos( CorrectorRecursos.VENTAS, "sofi_fecha_dmy", sinFormatos ).get( "visible" ).puntajeValores,
      "sin date_formats no se acepta" );

    // ambigua: 04/05 es 4 de mayo con la mascara que declaro, aunque MM/dd/yyyy este antes en date_formats
    Pack ambiguo = conOpciones( ventas, o -> o.formatosFecha = Arrays.asList( "MM/dd/yyyy", "dd/MM/yyyy" ) );
    igual( 1.0, casos( CorrectorRecursos.VENTAS, "sofi_fecha_dmy", ambiguo ).get( "visible" ).puntajeValores,
      "la mascara declarada desempata" );

    // captura step: fechas yyyy-MM-dd y numeros sin formato contra un esperado con mascara 0.00
    CorrectorTabla esp = esperada( ventas, CorrectorRecursos.VENTAS, "oculto" );
    CorrectorTabla step = CorrectorTabla.de( esp.columnas, Arrays.asList( //
      Arrays.<Object>asList( "Café", 12L, new BigDecimal( "30" ), 4L, new BigDecimal( "7.5" ), "2024-06-07" ),
      Arrays.<Object>asList( "Jugo", 7L, new BigDecimal( "21.7" ), 3L, new BigDecimal( "7.2333333" ), "2024-06-06" ),
      Arrays.<Object>asList( "Medialuna", 11L, 13.2d, 2L, 6.6d, "2024-06-05 00:00:00.000" ),
      Arrays.<Object>asList( "Tostado", 6L, new BigDecimal( "28.5" ), 3L, new BigDecimal( "9.5" ), "2024/06/07" ) ) );
    List<Aviso> av = new ArrayList<>();
    Comparacion s = CorrectorComparar.comparar( esp, step, ventas, av );
    igual( 1.0, s.puntajeValores, "captura step: numeros planos con tolerancia, fechas ISO y de Kettle" );
  }

  // ------------------------------------------------------------------ filas

  public void pruebaFilasQueFaltanYSalidaDibujada() throws Exception {
    Map<String, Comparacion> r = casos( CorrectorRecursos.VENTAS, "rodri_filtra_de_mas", ventas );
    Comparacion v = r.get( "visible" );
    igual( 2, v.filas.faltan, "rodri visible: faltan 2" );
    igual( Arrays.asList( "Medialuna", "Té" ), claves( v.ejemplos.faltan ), "las que faltan" );
    falso( v.cantidadFilasOk, "row_count" );
    igual( 0.5, v.puntajeValores, "visible 50 %" );
    igual( 0.75, r.get( "oculto" ).puntajeValores, "oculto 75 %" );

    Map<String, Comparacion> q = casos( CorrectorRecursos.VENTAS, "quique_dibuja_salida", ventas );
    igual( 1.0, q.get( "visible" ).puntajeValores, "quique pasa el visible" );
    Comparacion o = q.get( "oculto" );
    igual( 0.0, o.puntajeValores, "y no el oculto" );
    cierto( o.cantidadFilasOk, "4 filas contra 4" );
    igual( 3, o.filas.distintas, "Café, Medialuna y Tostado distintas" );
    igual( Collections.singletonList( "Jugo" ), claves( o.ejemplos.faltan ), "falta Jugo" );
    igual( Collections.singletonList( "Té" ), claves( o.ejemplos.sobran ), "sobra Té" );
  }

  public void pruebaClavesRepetidas() throws Exception {
    Comparacion c = comparar( CorrectorRecursos.VENTAS, "elena_sin_ordenar", "oculto", null );
    igual( 4, c.filas.clavesRepetidas, "D5: todas repetidas" );
    igual( 0, c.filas.sobran, "las repetidas no sobran" );
    igual( 0, c.filas.iguales, "ni cuentan como iguales" );
    igual( 0.0, c.puntajeValores, "values" );
    igual( Integer.valueOf( 3 ), c.ejemplos.clavesRepetidas.get( 1 ).veces, "Jugo x3" );

    // clave compuesta y numerica: 1 y 1.0 son la misma clave
    OpcionesComparar o = new OpcionesComparar();
    o.columnasClave = Arrays.asList( "a", "b" );
    CorrectorTabla esp = csv( "a;b;v\n1;x;10\n2;x;20\n1;y;30\n" );
    CorrectorTabla alu = csv( "a;b;v\n1.0;y;30\n2;x;21\n1;x;10\n9;z;1\n" );
    List<Pack.Columna> tipos = Arrays.asList( new Pack.Columna( "a", "Integer" ), new Pack.Columna( "v", "Number" ) );
    Comparacion k = CorrectorComparar.comparar( esp, alu, tipos, o, Locale.US, null );
    igual( Comparacion.POR_CLAVE, k.modo, "por clave" );
    igual( 2, k.filas.iguales, "iguales" );
    igual( 1, k.filas.distintas, "2|x distinta" );
    igual( 1, k.filas.sobran, "9|z sobra" );
    igual( "2 | x", k.ejemplos.distintas.get( 0 ).clave, "clave compuesta unida con |" );
    igual( "9 | z", k.ejemplos.sobran.get( 0 ).clave, "sobrante" );
    igual( 2.0 / 3, k.puntajeValores, "2 / max(3, 2 + 1)" );
  }

  public void pruebaMulticonjunto() throws Exception {
    Pack sinClave = conOpciones( ventas, o -> o.columnasClave = new ArrayList<>() );
    Comparacion ana = comparar( sinClave, CorrectorRecursos.VENTAS, "ana_orden_distinto", "visible", null );
    igual( Comparacion.MULTICONJUNTO, ana.modo, "multiconjunto" );
    igual( 1.0, ana.puntajeValores, "el orden no importa" );
    Comparacion bruno = comparar( sinClave, CorrectorRecursos.VENTAS, "bruno_agregado_mal", "visible", null );
    igual( 1, bruno.filas.iguales, "bruno sin clave: Tostado" );
    igual( 3, bruno.filas.distintas, "las parecidas se emparejan como distintas" );
    igual( 0, bruno.filas.faltan + bruno.filas.sobran, "ninguna falta ni sobra" );
    igual( Integer.valueOf( 3 ), bruno.diferenciasPorColumna.get( "importe_medio" ), "solo importe_medio" );
    igual( "#1", bruno.ejemplos.distintas.get( 0 ).clave, "sin clave: numero de fila" );
    igual( 0.25, bruno.puntajeValores, "igual que con clave" );

    OpcionesComparar o = new OpcionesComparar();
    CorrectorTabla esp = csv( "x\na\na\nb\n" );
    CorrectorTabla alu = csv( "x\na\nb\nb\n" );
    Comparacion m = CorrectorComparar.comparar( esp, alu, null, o, Locale.US, null );
    igual( 2, m.filas.iguales, "a y b" );
    igual( 1, m.filas.faltan, "falta una a" );
    igual( 1, m.filas.sobran, "sobra una b" );
    igual( "a", m.ejemplos.faltan.get( 0 ).fila.get( "x" ), "la fila que falta" );
    igual( "#3", m.ejemplos.sobran.get( 0 ).clave, "fila 3 del alumno" );
    igual( 2.0 / 3, m.puntajeValores, "2 / max(3, 2 + 1)" );
  }

  public void pruebaPorPosicionYOrdenEstricto() throws Exception {
    Pack posicion = conOpciones( ventas, o -> {
      o.columnasClave = new ArrayList<>();
      o.ignorarOrdenFilas = false;
    } );
    Comparacion ana = comparar( posicion, CorrectorRecursos.VENTAS, "ana_orden_distinto", "visible", null );
    igual( Comparacion.POR_POSICION, ana.modo, "por posicion" );
    igual( 0, ana.filas.iguales, "en otro orden nada coincide" );
    igual( 4, ana.filas.distintas, "todas distintas" );
    igual( 1.0, comparar( posicion, CorrectorRecursos.VENTAS, "paula_correcta", "visible", null ).puntajeValores,
      "la correcta" );
    CorrectorTabla esp = csv( "x;y\n1;a\n2;b\n" );
    Comparacion mas = CorrectorComparar.comparar( esp, csv( "x;y\n1;a\n2;b\n3;c\n" ), null, posicion.comparar,
      Locale.US, null );
    igual( 1, mas.filas.sobran, "fila de mas" );
    igual( "#3", mas.ejemplos.sobran.get( 0 ).clave, "numero de fila" );
    igual( "c", mas.ejemplos.sobran.get( 0 ).fila.get( "y" ), "la fila que sobra" );
    igual( 2.0 / 3, mas.puntajeValores, "2 / max(2, 2 + 1)" );

    // con clave y orden estricto: las filas fuera de orden pasan a distintas
    Pack ordenado = conOpciones( ventas, o -> o.ignorarOrdenFilas = false );
    List<Aviso> avisos = new ArrayList<>();
    Comparacion desc = comparar( ordenado, CorrectorRecursos.VENTAS, "ana_orden_distinto", "visible", avisos );
    igual( Comparacion.POR_CLAVE, desc.modo, "sigue por clave" );
    igual( 1, desc.filas.iguales, "solo una en orden" );
    igual( 3, desc.filas.distintas, "tres fuera de orden" );
    igual( 0.25, desc.puntajeValores, "values" );
    Comparacion.Ejemplo e = desc.ejemplos.distintas.get( 0 );
    igual( CorrectorComparar.COLUMNA_POSICION, e.columna, "ejemplo de orden" );
    igual( "Café", e.clave, "Café" );
    igual( "1", e.esperado, "fila esperada" );
    igual( "4", e.real, "fila del alumno" );
    igual( "3", aviso( avisos, CorrectorComparar.ORDEN_FILAS ).detalle, "aviso de orden" );
    List<Aviso> av2 = new ArrayList<>();
    igual( 1.0, comparar( ordenado, CorrectorRecursos.VENTAS, "paula_correcta", "visible", av2 ).puntajeValores,
      "la correcta en orden" );
    nulo( aviso( av2, CorrectorComparar.ORDEN_FILAS ), "sin aviso de orden" );
  }

  // ------------------------------------------------------------------ celdas

  public void pruebaToleranciasYTexto() {
    List<Pack.Columna> tipos = Arrays.asList( new Pack.Columna( "k", "String" ), new Pack.Columna( "n", "Number" ),
      new Pack.Columna( "t", "String" ) );
    OpcionesComparar o = new OpcionesComparar();
    o.columnasClave = Collections.singletonList( "k" );
    CorrectorTabla esp = csv( "k;n;t\na;7.23;007\nb;20.00;x\nc;12;Hola\nd;100;y\n" );
    CorrectorTabla alu = csv( "k;n;t\na;7.2333;7\nb;20.01;x\nc;12.0; Hola \nd;104;y\n" );
    Comparacion c = CorrectorComparar.comparar( esp, alu, tipos, o, Locale.US, null );
    igual( Integer.valueOf( 1 ), c.diferenciasPorColumna.get( "t" ), "007 no es 7 en una columna String" );
    igual( Integer.valueOf( 1 ), c.diferenciasPorColumna.get( "n" ), "solo 100 contra 104" );
    igual( "104", c.ejemplos.distintas.get( 1 ).real, "valor tal como se leyo" );

    o.toleranciaRelativa = 0.05;
    Comparacion rel = CorrectorComparar.comparar( esp, alu, tipos, o, Locale.US, null );
    nulo( rel.diferenciasPorColumna.get( "n" ), "tolerancia relativa 5 %" );

    o.toleranciaRelativa = 0;
    o.toleranciaNumerica = 0;
    Comparacion exacta = CorrectorComparar.comparar( esp, alu, tipos, o, Locale.US, null );
    igual( Integer.valueOf( 3 ), exacta.diferenciasPorColumna.get( "n" ), "tolerancia 0: 12 = 12.0 igual" );

    // numerica en el pack: 007 = 7
    List<Pack.Columna> num = Arrays.asList( new Pack.Columna( "k", "String" ), new Pack.Columna( "n", "Number" ),
      new Pack.Columna( "t", "Integer" ) );
    o.toleranciaNumerica = 0.01;
    nulo( CorrectorComparar.comparar( esp, alu, num, o, Locale.US, null ).diferenciasPorColumna.get( "t" ),
      "Integer: 007 = 7, y Hola es texto en los dos" );

    // trim y mayusculas
    o.recortar = false;
    igual( Integer.valueOf( 2 ), CorrectorComparar.comparar( esp, alu, tipos, o, Locale.US, null )
      .diferenciasPorColumna.get( "t" ), "sin trim ' Hola ' es distinto" );
    o.recortar = true;
    o.distinguirMayusculas = false;
    CorrectorTabla mayus = csv( "k;n;t\nA;7.23;007\nB;20.00;X\nC;12;HOLA\nD;100;Y\n" );
    Comparacion sinMayus = CorrectorComparar.comparar( esp, mayus, tipos, o, Locale.US, null );
    igual( 4, sinMayus.filas.iguales, "case_sensitive false: clave y texto" );
    o.distinguirMayusculas = true;
    igual( 4, CorrectorComparar.comparar( esp, mayus, tipos, o, Locale.US, null ).filas.faltan,
      "case_sensitive true: las claves no casan" );

    // sin tipos en el pack: numerica si la esperada es toda numerica
    OpcionesComparar sin = new OpcionesComparar();
    Comparacion deducida = CorrectorComparar.comparar( csv( "n;t\n1.50;a\n" ), csv( "n;t\n1.5;a\n" ), null, sin,
      Locale.US, null );
    igual( 1.0, deducida.puntajeValores, "deducida numerica" );
    Comparacion texto = CorrectorComparar.comparar( csv( "n;t\n1.50;a\nx;b\n" ), csv( "n;t\n1.5;a\nx;b\n" ), null,
      sin, Locale.US, null );
    igual( 1, texto.filas.iguales, "deducida texto: 1.50 no es 1.5" );

    // booleanos, nulos y vacios
    List<Pack.Columna> bool = Arrays.asList( new Pack.Columna( "b", "Boolean" ), new Pack.Columna( "s", "String" ) );
    Comparacion b = CorrectorComparar.comparar( csv( "b;s\nY;\nN;x\n" ), csv( "b;s\ntrue;  \nfalse;x\n" ), bool, sin,
      Locale.US, null );
    igual( 2, b.filas.iguales, "Y = true, vacio = espacios con trim" );
  }

  public void pruebaFormatoEstrictoNumeros() {
    List<Pack.Columna> tipos = Collections.singletonList( new Pack.Columna( "n", "Number" ) );
    OpcionesComparar o = new OpcionesComparar();
    CorrectorTabla esp = csv( "n\n1234.50\n" );
    CorrectorTabla.Lectura l = new CorrectorTabla.Lectura().formatos( Collections.singletonList( fmt( "#,##0.00", "",
      "" ) ) );
    CorrectorTabla alu = CorrectorTabla.leerTexto( "n\n\"1,234.50\"\n", l );
    igual( 1.0, CorrectorComparar.comparar( esp, alu, tipos, o, Locale.US, null ).puntajeValores, "miles aceptados" );
    o.formatoEstricto = true;
    igual( 0.0, CorrectorComparar.comparar( esp, alu, tipos, o, Locale.US, null ).puntajeValores,
      "strict_format: los miles restan" );
    igual( 1.0, CorrectorComparar.comparar( esp, csv( "n\n1234.5\n" ), tipos, o, Locale.US, null ).puntajeValores,
      "strict_format: plano con otros decimales vale" );
  }

  // ------------------------------------------------------------------ columnas

  public void pruebaColumnas() throws Exception {
    Comparacion carla = comparar( CorrectorRecursos.VENTAS, "carla_falta_columna", "visible", null );
    igual( Collections.singletonList( "ultima_venta" ), carla.columnas.faltan, "falta" );
    igual( 1.0, carla.puntajeValores, "lo que falta no resta en values" );

    CorrectorTabla esp = csv( "a;b;c\n1;2;3\n" );
    CorrectorTabla otroOrden = csv( "B;a;c;d\n2;1;3;9\n" );
    OpcionesComparar o = new OpcionesComparar();
    List<Aviso> avisos = new ArrayList<>();
    Comparacion c = CorrectorComparar.comparar( esp, otroOrden, null, o, Locale.US, avisos );
    igual( 1.0, c.puntajeColumnas, "ignore_column_order: por nombre sin mayusculas" );
    falso( c.columnas.ordenDistinto, "sin aviso de orden" );
    igual( Collections.singletonList( "d" ), c.columnas.sobran, "sobra d" );
    igual( "d", aviso( avisos, Aviso.COLUMNAS_DE_MAS ).detalle, "aviso de columnas de mas" );
    igual( 1.0, c.puntajeValores, "valores por nombre" );

    o.ignorarOrdenColumnas = false;
    avisos.clear();
    Comparacion orden = CorrectorComparar.comparar( esp, otroOrden, null, o, Locale.US, avisos );
    cierto( orden.columnas.ordenDistinto, "order_differs" );
    igual( 2.0 / 3, orden.puntajeColumnas, "presentes en orden: a y c" );
    String fuera = aviso( avisos, Aviso.ORDEN_COLUMNAS ).detalle;
    cierto( "a".equals( fuera ) || "b".equals( fuera ), "una fuera de orden: " + fuera );
    igual( 1.0, orden.puntajeValores, "los valores no se cuentan dos veces" );

    o.ignorarOrdenColumnas = true;
    o.columnasDeMas = OpcionesComparar.EXTRA_RESTAR;
    avisos.clear();
    Comparacion pen = CorrectorComparar.comparar( esp, otroOrden, null, o, Locale.US, avisos );
    igual( 0.75, pen.puntajeColumnas, "penalize: 3 / (3 + 1)" );
    nulo( aviso( avisos, Aviso.COLUMNAS_DE_MAS ), "penalize no avisa" );
  }

  public void pruebaClaveQueFaltaONoEsUnica() throws Exception {
    List<Aviso> avisos = new ArrayList<>();
    OpcionesComparar o = new OpcionesComparar();
    o.columnasClave = Collections.singletonList( "k" );
    Comparacion sinK = CorrectorComparar.comparar( csv( "k;v\na;1\nb;2\n" ), csv( "clave;v\na;1\nb;2\n" ), null, o,
      Locale.US, avisos );
    igual( Comparacion.MULTICONJUNTO, sinK.modo, "sin la columna clave: multiconjunto" );
    igual( "k", aviso( avisos, CorrectorComparar.SIN_CLAVE ).detalle, "aviso de clave" );
    igual( 2, sinK.filas.iguales, "empareja por v" );
    igual( 1, sinK.columnas.renombres.size(), "y sugiere el renombre de la clave" );

    avisos.clear();
    Comparacion repetida = CorrectorComparar.comparar( csv( "k;v\na;1\na;2\n" ), csv( "k;v\na;2\na;1\n" ), null, o,
      Locale.US, avisos );
    igual( Comparacion.MULTICONJUNTO, repetida.modo, "clave repetida en la esperada: multiconjunto" );
    noNulo( aviso( avisos, CorrectorComparar.CLAVE_NO_UNICA ), "aviso de clave no unica" );
    igual( 1.0, repetida.puntajeValores, "multiconjunto" );
    igual( 0, repetida.columnasClave.size(), "sin clave en el resultado" );
  }

  public void pruebaRenombres() throws Exception {
    Comparacion gabi = comparar( CorrectorRecursos.VENTAS, "gabi_renombra", "oculto", null );
    igual( 1, gabi.columnas.renombres.size(), "un renombre" );
    Comparacion.Renombre r = gabi.columnas.renombres.get( 0 );
    igual( "importe_medio", r.esperada, "esperada" );
    igual( "promedio", r.real, "real" );
    igual( 1.0, r.coincidencia, "todos iguales" );

    CorrectorTabla esp = csv( "k;v\na;1\nb;2\nc;3\nd;4\ne;5\n" );
    OpcionesComparar o = new OpcionesComparar();
    o.columnasClave = Collections.singletonList( "k" );
    igual( 1, CorrectorComparar.comparar( esp, csv( "k;w\na;1\nb;2\nc;3\nd;4\ne;9\n" ), null, o, Locale.US, null )
      .columnas.renombres.size(), "80 %: se sugiere" );
    igual( 0, CorrectorComparar.comparar( esp, csv( "k;w\na;1\nb;2\nc;3\nd;8\ne;9\n" ), null, o, Locale.US, null )
      .columnas.renombres.size(), "60 %: no" );
  }

  // ------------------------------------------------------------------ avisos, ejemplos y limites

  public void pruebaEscalaX100() throws Exception {
    List<Aviso> avisos = new ArrayList<>();
    Comparacion c = comparar( CorrectorRecursos.VENTAS, "hugo_decimal", "oculto", avisos );
    igual( "importe_total x100, importe_medio x100", aviso( avisos, CorrectorComparar.ESCALA ).detalle,
      "D3: los importes x100 (723.33 contra 7.23 tambien)" );
    igual( 0.0, c.puntajeValores, "values" );
    List<Aviso> av2 = new ArrayList<>();
    comparar( CorrectorRecursos.VENTAS, "bruno_agregado_mal", "visible", av2 );
    nulo( aviso( av2, CorrectorComparar.ESCALA ), "MAX en vez de AVERAGE no es una escala" );
    for ( Aviso a : avisos ) {
      cierto( a.detalle == null || !a.detalle.contains( "2000" ), "los avisos no llevan valores" );
    }
  }

  public void pruebaEjemplosConTope() throws Exception {
    Pack dos = conOpciones( ventas, o -> o.maxEjemplos = 2 );
    Comparacion c = comparar( dos, CorrectorRecursos.VENTAS, "hugo_decimal", "visible", null );
    igual( 2, c.ejemplos.distintas.size(), "max_examples" );
    igual( Integer.valueOf( 4 ), c.diferenciasPorColumna.get( "importe_total" ), "los conteos no se cortan" );
    Pack cero = conOpciones( ventas, o -> o.maxEjemplos = 0 );
    igual( 0, comparar( cero, CorrectorRecursos.VENTAS, "hugo_decimal", "visible", null ).ejemplos.distintas.size(),
      "max_examples 0" );
  }

  public void pruebaCasosLimite() {
    OpcionesComparar o = new OpcionesComparar();
    Comparacion vacias = CorrectorComparar.comparar( csv( "a\n" ), csv( "a\n" ), null, o, Locale.US, null );
    igual( 1.0, vacias.puntajeValores, "las dos sin filas" );
    cierto( vacias.cantidadFilasOk, "0 = 0" );
    Comparacion sobran = CorrectorComparar.comparar( csv( "a\n" ), csv( "a\n1\n" ), null, o, Locale.US, null );
    igual( 0.0, sobran.puntajeValores, "esperada vacia y el alumno con filas" );
    Comparacion nada = CorrectorComparar.comparar( csv( "a;b\n1;2\n" ), CorrectorTabla.vacia( Arrays.asList( "a",
      "b" ) ), null, o, Locale.US, null );
    igual( 0.0, nada.puntajeValores, "el alumno sin filas" );
    igual( 1, nada.filas.faltan, "falta la fila" );
    Comparacion otras = CorrectorComparar.comparar( csv( "a;b\n1;2\n" ), csv( "x;y\n1;2\n" ), null, o, Locale.US,
      null );
    igual( 0.0, otras.puntajeColumnas, "ninguna columna" );
    igual( 0.0, otras.puntajeValores, "sin columnas en comun no hay valores" );
    igual( 1, otras.filas.faltan, "falta" );
    igual( 1, otras.filas.sobran, "sobra" );
    igual( 0, otras.columnas.renombres.size(), "sin filas emparejadas no hay renombres" );

    o.columnasClave = Collections.singletonList( "a" );
    Comparacion soloClave = CorrectorComparar.comparar( csv( "a;b\n1;2\n" ), csv( "a\n1\n" ), null, o, Locale.US,
      null );
    igual( 1, soloClave.filas.distintas, "solo la clave no alcanza para que la fila sea igual" );
    igual( 0.0, soloClave.puntajeValores, "values 0" );
    Comparacion lista = CorrectorComparar.comparar( csv( "a\n1\n2\n" ), csv( "a\n2\n1\n" ), null, o, Locale.US, null );
    igual( 1.0, lista.puntajeValores, "la salida es solo la clave" );

    lanzaNull();
  }

  public void pruebaTablaGrande() {
    int n = CorrectorComparar.LIMITE_VORAZ + 1000;
    StringBuilder e = new StringBuilder( "id;v\n" );
    List<String> filas = new ArrayList<>();
    for ( int i = 0; i < n; i++ ) {
      e.append( i ).append( ';' ).append( i ).append( ".00\n" );
      filas.add( i + ";" + i + ".001" ); // dentro de la tolerancia: ninguna firma exacta coincide
    }
    Collections.shuffle( filas, new Random( 7 ) );
    filas.set( 0, "-1;-1" );
    StringBuilder a = new StringBuilder( "id;v\n" );
    for ( String f : filas ) {
      a.append( f ).append( '\n' );
    }
    long t0 = System.currentTimeMillis();
    List<Pack.Columna> tipos = Arrays.asList( new Pack.Columna( "id", "Integer" ), new Pack.Columna( "v", "Number" ) );
    Comparacion c = CorrectorComparar.comparar( csv( e.toString() ), csv( a.toString() ), tipos,
      new OpcionesComparar(), Locale.US, null );
    long ms = System.currentTimeMillis() - t0;
    igual( Comparacion.MULTICONJUNTO, c.modo, "sin clave" );
    igual( n - 1, c.filas.iguales, "todas menos la reemplazada" );
    igual( 1, c.filas.faltan, "una falta" );
    igual( 1, c.filas.sobran, "una sobra" );
    cierto( ms < 20000, "ordenar y recorrer: " + ms + " ms" );
  }

  public void pruebaSubsecuenciaCreciente() {
    igual( "[true, false, true, true]", Arrays.toString( CorrectorComparar.subsecuenciaCreciente( new int[] { 0, 3, 1,
      2 } ) ), "0 1 2" );
    igual( 1, cuenta( CorrectorComparar.subsecuenciaCreciente( new int[] { 3, 2, 1, 0 } ) ), "descendente: 1" );
    igual( 0, CorrectorComparar.subsecuenciaCreciente( new int[ 0 ] ).length, "vacia" );
  }

  // ------------------------------------------------------------------ ayudas

  private static void lanzaNull() {
    Verificador.lanza( IllegalArgumentException.class, () -> CorrectorComparar.comparar( null, csv( "a\n" ), null,
      null, null, null ), "sin esperada" );
  }

  private static int cuenta( boolean[] b ) {
    int n = 0;
    for ( boolean x : b ) {
      n += x ? 1 : 0;
    }
    return n;
  }

  private static CorrectorTabla csv( String texto ) {
    return CorrectorTabla.leerTexto( texto, new CorrectorTabla.Lectura() );
  }

  private static CorrectorTabla.Formato fmt( String mascara, String decimal, String grupo ) {
    return new CorrectorTabla.Formato( "Number", mascara, decimal, grupo, null, null );
  }

  private static void numero( String texto, CorrectorTabla.Formato f, Locale loc, String valor, boolean coma,
    boolean plano, String msg ) {
    CorrectorComparar.Numero n = CorrectorComparar.numero( texto, f, loc );
    noNulo( n, msg + ": " + texto );
    cierto( new BigDecimal( valor ).compareTo( n.valor ) == 0, msg + ": " + texto + " -> " + n.valor + " (esperaba "
      + valor + ")" );
    igual( coma, n.coma, msg + ": coma" );
    igual( plano, n.plano, msg + ": plano" );
  }

  private static Pack pack( String id ) throws Exception {
    return CorrectorJson.leer( new File( CorrectorRecursos.pack( id ), "pack.json" ), Pack.class ).completar();
  }

  private static Pack conOpciones( Pack p, Consumer<OpcionesComparar> cambio ) {
    Pack c = CorrectorJson.copia( p, Pack.class ).completar();
    cambio.accept( c.comparar );
    return c;
  }

  private static CorrectorTabla esperada( Pack p, String packId, String caso ) throws Exception {
    return CorrectorTabla.esperada( new File( CorrectorRecursos.pack( packId ), p.caso( caso ).esperado ), p );
  }

  /** La salida fija del alumno, leida como la leeria C4: con el Text file output de su .ktr (o plana si es query). */
  private static CorrectorTabla salida( String packId, String alumno, String caso, Pack p ) throws Exception {
    File f = new File( CorrectorRecursos.raiz(), "salidas/" + packId + "/" + alumno + "/" + caso + ".csv" );
    if ( Pack.Salida.CAPTURA_QUERY.equals( p.salida.captura ) ) {
      return CorrectorTabla.leer( f, new CorrectorTabla.Lectura() );
    }
    return CorrectorTabla.leer( f, lecturaDelTextFileOutput( CorrectorRecursos.entrega( packId, alumno + ".ktr" ),
      p ) );
  }

  private Comparacion comparar( String packId, String alumno, String caso, List<Aviso> avisos ) throws Exception {
    Pack p = CorrectorRecursos.TABLA.equals( packId ) ? tabla : ventas;
    return comparar( p, packId, alumno, caso, avisos );
  }

  private static Comparacion comparar( Pack p, String packId, String alumno, String caso, List<Aviso> avisos )
    throws Exception {
    return CorrectorComparar.comparar( esperada( p, packId, caso ), salida( packId, alumno, caso, p ), p, avisos );
  }

  private static Map<String, Comparacion> casos( String packId, String alumno, Pack p ) throws Exception {
    return casos( packId, alumno, p, null );
  }

  private static Map<String, Comparacion> casos( String packId, String alumno, Pack p, List<Aviso> avisos )
    throws Exception {
    Map<String, Comparacion> m = new LinkedHashMap<>();
    for ( Caso c : p.casos ) {
      m.put( c.id, comparar( p, packId, alumno, c.id, avisos ) );
    }
    return m;
  }

  /** La nota con la rubrica del pack: runs completo, columns/row_count/values por caso y los puntos de reglas. */
  private static double nota( Pack p, Map<String, Comparacion> cmp, double puntosReglas ) {
    double ganado = puntosReglas;
    double pesos = 0;
    for ( Caso c : p.casos ) {
      pesos += c.peso;
    }
    for ( ItemRubrica i : p.rubrica ) {
      if ( ItemRubrica.RUNS.equals( i.check ) ) {
        ganado += i.puntos;
        continue;
      }
      if ( !ItemRubrica.COLUMNS.equals( i.check ) && !ItemRubrica.ROW_COUNT.equals( i.check )
        && !ItemRubrica.VALUES.equals( i.check ) ) {
        continue;
      }
      double s = 0;
      for ( Caso c : p.casos ) {
        Comparacion x = cmp.get( c.id );
        double v = ItemRubrica.COLUMNS.equals( i.check ) ? x.puntajeColumnas
          : ItemRubrica.ROW_COUNT.equals( i.check ) ? ( x.cantidadFilasOk ? 1 : 0 ) : x.puntajeValores;
        s += v * c.peso;
      }
      ganado += i.puntos * s / pesos;
    }
    return Math.round( ganado / p.puntosTotales() * p.escala * 10 ) / 10.0;
  }

  private static Aviso aviso( List<Aviso> l, String codigo ) {
    for ( Aviso a : l ) {
      if ( codigo.equals( a.codigo ) ) {
        return a;
      }
    }
    return null;
  }

  private static List<String> claves( List<Comparacion.Ejemplo> l ) {
    List<String> r = new ArrayList<>();
    for ( Comparacion.Ejemplo e : l ) {
      r.add( e.clave );
    }
    return r;
  }

  private static void mismaComparacion( Comparacion a, Comparacion b, String q ) {
    igual( a.modo, b.modo, q + ": modo" );
    igual( a.columnasClave, b.columnasClave, q + ": key_columns" );
    igual( a.columnas.esperadas, b.columnas.esperadas, q + ": columnas esperadas" );
    igual( a.columnas.reales, b.columnas.reales, q + ": columnas reales" );
    igual( a.columnas.faltan, b.columnas.faltan, q + ": missing" );
    igual( a.columnas.sobran, b.columnas.sobran, q + ": extra" );
    igual( a.columnas.ordenDistinto, b.columnas.ordenDistinto, q + ": order_differs" );
    igual( renombres( a ), renombres( b ), q + ": renamed_suggestions" );
    Comparacion.Filas x = a.filas;
    Comparacion.Filas y = b.filas;
    igual( Arrays.asList( x.esperadas, x.reales, x.iguales, x.distintas, x.faltan, x.sobran, x.clavesRepetidas ),
      Arrays.asList( y.esperadas, y.reales, y.iguales, y.distintas, y.faltan, y.sobran, y.clavesRepetidas ), q
        + ": rows (expected, actual, matched, different, missing, extra, duplicate_keys)" );
    igual( a.puntajeColumnas, b.puntajeColumnas, q + ": columns_score" );
    igual( a.cantidadFilasOk, b.cantidadFilasOk, q + ": row_count_ok" );
    igual( a.puntajeValores, b.puntajeValores, q + ": values_score" );
    igual( a.diferenciasPorColumna, b.diferenciasPorColumna, q + ": differences_by_column" );
    igual( ejemplos( a ), ejemplos( b ), q + ": examples" );
  }

  private static List<String> renombres( Comparacion c ) {
    List<String> l = new ArrayList<>();
    for ( Comparacion.Renombre r : c.columnas.renombres ) {
      l.add( r.esperada + "->" + r.real );
    }
    return l;
  }

  private static List<String> ejemplos( Comparacion c ) {
    List<String> l = new ArrayList<>();
    for ( Comparacion.Ejemplo e : c.ejemplos.distintas ) {
      l.add( "diferente " + e.clave + " | " + e.columna + " | " + e.esperado + " | " + e.real );
    }
    for ( Comparacion.Ejemplo e : c.ejemplos.faltan ) {
      l.add( "falta " + e.clave );
    }
    for ( Comparacion.Ejemplo e : c.ejemplos.sobran ) {
      l.add( "sobra " + e.clave );
    }
    for ( Comparacion.Ejemplo e : c.ejemplos.clavesRepetidas ) {
      l.add( "repetida " + e.clave + " x" + e.veces );
    }
    return l;
  }

  /**
   * Lo que C4 saca del TextFileOutputMeta, leido del XML de la entrega sin Kettle: separador, encierro, encabezado,
   * encoding (vacio = el del pack) y por campo tipo, mascara, decimal, miles, moneda y nullif.
   */
  static CorrectorTabla.Lectura lecturaDelTextFileOutput( File ktr, Pack p ) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
    Document d = f.newDocumentBuilder().parse( ktr );
    for ( Element step : hijos( d.getDocumentElement(), "step" ) ) {
      if ( !"TextFileOutput".equals( texto( step, "type" ) ) ) {
        continue;
      }
      List<String> nombres = new ArrayList<>();
      List<CorrectorTabla.Formato> formatos = new ArrayList<>();
      for ( Element campos : hijos( step, "fields" ) ) {
        for ( Element c : hijos( campos, "field" ) ) {
          nombres.add( texto( c, "name" ) );
          formatos.add( new CorrectorTabla.Formato( texto( c, "type" ), texto( c, "format" ), texto( c, "decimal" ),
            texto( c, "group" ), texto( c, "currency" ), texto( c, "nullif" ) ) );
        }
      }
      return new CorrectorTabla.Lectura().separador( texto( step, "separator" ) ).encierro( texto( step, "enclosure" ) )
        .encabezado( "Y".equalsIgnoreCase( texto( step, "header" ) ) ).encoding( texto( step, "encoding" ),
          p.politica.encoding ).nombres( nombres ).formatos( formatos ).maxFilas( p.politica.maxFilas );
    }
    throw new IllegalStateException( ktr + " no tiene Text file output" );
  }

  private static List<Element> hijos( Element e, String tag ) {
    List<Element> l = new ArrayList<>();
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && tag.equals( ( (Element) n ).getTagName() ) ) {
        l.add( (Element) n );
      }
    }
    return l;
  }

  private static String texto( Element e, String tag ) {
    List<Element> l = hijos( e, tag );
    return l.isEmpty() ? "" : l.get( 0 ).getTextContent();
  }

  private static byte[] concat( byte[] a, byte[] b ) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    o.write( a, 0, a.length );
    o.write( b, 0, b.length );
    return o.toByteArray();
  }
}
