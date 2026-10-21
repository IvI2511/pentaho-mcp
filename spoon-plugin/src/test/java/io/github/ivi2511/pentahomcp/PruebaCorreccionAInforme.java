package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.navegador.Chromium;
import io.github.ivi2511.pentahomcp.informes.navegador.RenderPdf;
import io.github.ivi2511.pentahomcp.informes.render.RenderHtml;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * El puente con Informes (C9, docs/corrector-diseno.md §6 «Render») sobre los resultados fijos del prototipo
 * (resultados/, puntuados con C5) y resultados armados a mano: estructura del Informe, las dos versiones (el alumno no
 * ve valores, ejemplos, detalle, log ni metricas de los ocultos), el resultado de bruno en HTML, HTML liviano, PDF,
 * Markdown y PPTX con los renderizadores de Informes, escapes, estados que no son «graded», textos en/es/pt, avisos por
 * codigo, el informe de curso y el tema de Ajustes. No ejecuta Kettle.
 *
 * @dueño C9
 */
public class PruebaCorreccionAInforme {

  private static final String VENTAS = CorrectorRecursos.VENTAS;
  /** Los resultados del prototipo del pack ventas-por-producto. */
  private static final String[] PROTOTIPO = { "ana_orden_distinto", "bruno_agregado_mal", "carla_falta_columna",
    "diego_javascript", "elena_sin_ordenar", "fede_bucle", "gabi_renombra", "hugo_decimal" };
  /** Valores que solo estan en el caso oculto de bruno (Jugo no esta en el visible; los numeros tampoco). */
  private static final String[] OCULTOS_BRUNO = { "Jugo", "12.40", "7.23", "14.25", "12.50", "6.60" };
  /** Una clave de Rotulos que no se resolvio (se devuelve tal cual). */
  private static final Pattern CLAVE_CRUDA = Pattern.compile(
    "\\b(inf|md|aviso|estado|caso|motivo|planilla|check|detalle|regla|pista)\\.[a-z_]+(\\.[a-z_]+)*" );

  // ================================================================ estructura

  /** La version del docente de bruno: secciones, tarjetas, rubrica, ejemplos de los dos casos. */
  public void pruebaBrunoDocente() throws Exception {
    ResultadoCorreccion r = bruno();
    Informe inf = CorreccionAInforme.informe( r, false, new Tema() );
    igual( Arrays.asList( SeccionId.PORTADA, CorreccionAInforme.SEC_NOTA, CorreccionAInforme.SEC_CRITERIOS,
      CorreccionAInforme.SEC_PISTAS, "case_visible", "case_oculto", CorreccionAInforme.SEC_NOTAS ), ids( inf ),
      "secciones en orden" );
    igual( "es", inf.idioma, "el idioma del resultado" );
    igual( "Ejercicio 3 - Ventas por producto", inf.titulo, "titulo = el del pack" );
    igual( "Corrección de bruno_agregado_mal", inf.subtitulo, "subtitulo del docente" );
    igual( "bruno_agregado_mal.ktr", inf.nombreDoc, "el archivo de la entrega" );
    igual( Informe.TRANSFORMACION, inf.tipoDoc, "tipo" );
    noNulo( inf.rotulos.get( "page" ), "rotulos del renderizador" );
    String portada = json( inf.seccion( SeccionId.PORTADA ) );
    contiene( portada, "Alumno: bruno_agregado_mal", "portada: alumno" );
    contiene( portada, "Ejercicio: ventas-por-producto", "portada: id del pack" );
    contiene( portada, "Nota: 6,5 / 10 (corregida)", "portada: la nota (tambien en la del PPTX)" );

    Seccion nota = inf.seccion( CorreccionAInforme.SEC_NOTA );
    Indicadores tarjetas = (Indicadores) bloque( nota, Indicadores.class );
    igual( "6,5 / 10", tarjetas.items.get( 0 ).valor, "nota con coma" );
    igual( Indicador.OK, tarjetas.items.get( 0 ).nivel, "6,5 aprueba" );
    igual( "corregida", tarjetas.items.get( 1 ).valor, "estado traducido" );
    igual( "6 de 7", tarjetas.items.get( 2 ).valor, "criterios aprobados" );
    igual( "0 de 2", tarjetas.items.get( 3 ).valor, "casos sin diferencias" );
    igual( "No aprueba: Valores correctos.", nota.resumen, "lo que falla" );

    Tabla rubrica = (Tabla) bloque( inf.seccion( CorreccionAInforme.SEC_CRITERIOS ), Tabla.class );
    igual( 7, rubrica.filas.size(), "un renglon por check" );
    igual( Arrays.asList( "✘ Valores correctos", "0,5 / 4", "visible 25%, oculto 0%" ), rubrica.filas.get( 3 ),
      "renglon de values" );
    igual( "✔ Corre sin errores", rubrica.filas.get( 0 ).get( 0 ), "renglon aprobado" );
    igual( "Total: 6,5 de 10 puntos", rubrica.pie, "total" );

    contiene( json( inf.seccion( CorreccionAInforme.SEC_PISTAS ) ), "Revisa cómo calculas «importe_medio»",
      "la pista de C5" );

    Seccion visible = inf.seccion( "case_visible" );
    igual( "Caso «visible»", visible.titulo, "titulo del caso" );
    igual( "Filas: 4 esperadas, 4 tuyas, 1 iguales, 3 distintas, 0 faltan, 0 sobran, 0 claves repetidas",
      visible.resumen, "conteos" );
    Indicadores tv = (Indicadores) bloque( visible, Indicadores.class );
    igual( Arrays.asList( "terminó", "6 de 6", "4", "25%" ), valores( tv ), "tarjetas del caso" );
    Tabla dif = tabla( visible, "case_visible-different" );
    igual( Arrays.asList( "Clave", "Columna", "Esperado", "Entregado" ), dif.columnas, "columnas de la tabla" );
    igual( Arrays.asList( "Café", "importe_medio", "5.00", "7.50" ), dif.filas.get( 0 ), "el ejemplo del prototipo" );
    noNulo( tabla( visible, "case_visible-metrics" ), "filas por step del visible" );
    noNulo( tabla( visible, "case_visible-by-column" ), "diferencias por columna" );

    Seccion oculto = inf.seccion( "case_oculto" );
    igual( "Caso «oculto» (oculto)", oculto.titulo, "titulo del oculto" );
    String o = json( oculto );
    for ( String v : OCULTOS_BRUNO ) {
      contiene( o, v, "el docente ve los valores del oculto" );
    }
    noNulo( tabla( oculto, "case_oculto-metrics" ), "y sus metricas" );
    String todo = ModeloJson.escribir( inf, false );
    contiene( todo, "DETALLE-ENTREGA", "el detalle de la entrega va al docente" );
    contiene( todo, "DETALLE-OCULTO", "y el de un caso oculto" );
    contiene( todo, "REDIR-OCULTA", "y sus redirecciones" );
    noContiene( todo, "LOG-", "el log solo va si el caso fallo" );
    contiene( json( inf.seccion( CorreccionAInforme.SEC_NOTAS ) ), "Captura: file", "notas del docente" );
    sinClaves( todo, "bruno docente" );
    noNulo( r.caso( "oculto" ).comparacion.ejemplos, "informe() no cambia el resultado" );
  }

  /** La version del alumno: nada de los valores, ejemplos, detalle, log, metricas ni redirecciones del oculto. */
  public void pruebaBrunoAlumno() throws Exception {
    ResultadoCorreccion r = bruno();
    Informe inf = CorreccionAInforme.informe( r, true, new Tema() );
    igual( "Devolución para bruno_agregado_mal", inf.subtitulo, "subtitulo del alumno" );
    String todo = ModeloJson.escribir( inf, false );
    for ( String v : OCULTOS_BRUNO ) {
      noContiene( todo, v, "el alumno no ve los valores del oculto" );
    }
    for ( String marca : Arrays.asList( "DETALLE-ENTREGA", "DETALLE-OCULTO", "DETALLE-VISIBLE", "REDIR-OCULTA", "LOG-",
      "Captura: file", "Pentaho Live 0.5.0", "Arranque de Kettle" ) ) {
      noContiene( todo, marca, "solo para el docente" );
    }
    Seccion visible = inf.seccion( "case_visible" );
    igual( Arrays.asList( "Clave", "Columna", "Esperado", "Tuyo" ), tabla( visible, "case_visible-different" ).columnas,
      "el alumno ve sus ejemplos del visible" );
    contiene( json( visible ), "salida/03_ventas_por_producto", "y sus redirecciones" );
    noNulo( tabla( visible, "case_visible-metrics" ), "y sus metricas" );

    Seccion oculto = inf.seccion( "case_oculto" );
    igual( "Filas: 4 esperadas, 4 tuyas, 0 iguales, 4 distintas, 0 faltan, 0 sobran, 0 claves repetidas",
      oculto.resumen, "los conteos del oculto si" );
    igual( Arrays.asList( "terminó", "6 de 6", "4", "0%" ), valores( (Indicadores) bloque( oculto,
      Indicadores.class ) ), "y sus tarjetas" );
    Aviso a = (Aviso) bloque( oculto, Aviso.class );
    igual( "Caso oculto: solo se muestran los conteos.", a.texto, "lo dice" );
    nulo( tabla( oculto, "case_oculto-different" ), "sin ejemplos" );
    nulo( tabla( oculto, "case_oculto-metrics" ), "sin metricas" );
    igual( Arrays.asList( "importe_medio", "4" ), tabla( oculto, "case_oculto-by-column" ).filas.get( 0 ),
      "diferencias por columna: solo conteos" );
    noContiene( json( oculto ), "salida/", "sin redirecciones ni salida capturada" );
    nulo( inf.seccion( CorreccionAInforme.SEC_NOTAS ), "bruno no tiene avisos para el alumno" );
    sinClaves( todo, "bruno alumno" );
  }

  // ================================================================ formatos

  /** El resultado de bruno con los renderizadores de Informes: HTML, HTML liviano (IE11), PDF, Markdown y PPTX. */
  public void pruebaFormatos() throws Exception {
    ResultadoCorreccion r = bruno();
    File dir = limpia( "c9-formatos" );
    List<Formato> todos = Arrays.asList( Formato.HTML, Formato.HTML_ESTATICO, Formato.MARKDOWN, Formato.PPTX,
      Formato.PDF );
    Map<Formato, Resultado> al = CorreccionAInforme.exportar( r, true, todos, dir, "bruno-alumno", new Tema(),
      Progreso.NULO );
    igual( todos, new ArrayList<Formato>( al.keySet() ), "un resultado por formato, en orden" );

    File html = new File( dir, "bruno-alumno.html" );
    igual( html, al.get( Formato.HTML ).archivos.get( 0 ), "base.html" );
    String h = EntornoKettle.leer( html );
    contiene( h, "<!DOCTYPE html>", "es HTML" );
    contiene( h, "Valores correctos", "rubrica" );
    contiene( h, "Café", "ejemplo del visible" );
    contiene( h, "Revisa cómo calculas «importe_medio»", "pista" );
    falso( h.toLowerCase( Locale.ROOT ).contains( "<script" ), "sin diagramas: sin los 2,6 MB de Mermaid" );
    cierto( html.length() < 400 * 1024, "HTML chico: " + html.length() + " bytes" );
    sinOcultos( h, "HTML del alumno" );

    File liviano = new File( dir, "bruno-alumno.light.html" );
    archivo( liviano, 2000, "HTML liviano" );
    String l = EntornoKettle.leer( liviano );
    falso( l.toLowerCase( Locale.ROOT ).contains( "<script" ), "el liviano no lleva scripts (IE11)" );
    contiene( l, "X-UA-Compatible", "para el Browser de Spoon" );
    contiene( l, "Caso oculto: solo se muestran los conteos.", "el aviso del oculto" );
    sinOcultos( l, "HTML liviano del alumno" );

    String md = EntornoKettle.leer( new File( dir, "bruno-alumno.md" ) );
    contiene( md, "# Ejercicio 3 - Ventas por producto", "titulo del md" );
    contiene( md, "Valores correctos", "rubrica en el md" );
    contiene( md, "Café", "ejemplo en el md" );
    sinOcultos( md, "Markdown del alumno" );

    File pptx = new File( dir, "bruno-alumno.pptx" );
    archivo( pptx, 10000, "PPTX" );
    String p = textoPptx( pptx );
    contiene( p, "Valores correctos", "rubrica en el PPTX" );
    contiene( p, "Café", "ejemplo en el PPTX" );
    contiene( p, "Caso «oculto» (oculto)", "diapositiva del oculto" );
    contiene( p, "Nota: 6,5 / 10 (corregida)", "la nota en la portada del PPTX" );
    contiene( p, "No aprueba: Valores correctos.", "el resumen en la diapositiva (no solo en las notas)" );
    sinOcultos( p, "PPTX del alumno" );
    igual( 1, cuenta( h, "No aprueba: Valores correctos." ), "el resumen una sola vez en el HTML" );
    igual( 1, cuenta( md, "No aprueba: Valores correctos." ), "y en el Markdown" );

    Resultado pdf = al.get( Formato.PDF );
    File f = pdf.archivos.get( 0 );
    if ( Chromium.ubicar() == null ) {
      cierto( f.getName().endsWith( RenderPdf.SUFIJO_IMPRIMIR ), "sin navegador: el HTML para imprimir" );
      System.out.println( "  sin Edge ni Chrome: PDF como " + f.getName() );
    } else {
      igual( new File( dir, "bruno-alumno.pdf" ), f, "base.pdf" );
      archivo( f, 5000, "PDF" );
      byte[] b = Files.readAllBytes( f.toPath() );
      igual( "%PDF", new String( b, 0, 4, "US-ASCII" ), "es un PDF" );
      System.out.println( "  PDF del alumno: " + f.length() + " bytes" );
    }

    // el docente ve el oculto en todos los formatos (sin PDF: lo arma el mismo HTML)
    Map<Formato, Resultado> doc = CorreccionAInforme.exportar( r, false, Arrays.asList( Formato.HTML,
      Formato.HTML_ESTATICO, Formato.MARKDOWN, Formato.PPTX ), dir, "bruno-docente", new Tema(), Progreso.NULO );
    igual( 4, doc.size(), "cuatro formatos" );
    contiene( EntornoKettle.leer( new File( dir, "bruno-docente.html" ) ), "Jugo", "HTML del docente" );
    contiene( EntornoKettle.leer( new File( dir, "bruno-docente.light.html" ) ), "Jugo", "liviano del docente" );
    contiene( EntornoKettle.leer( new File( dir, "bruno-docente.md" ) ), "Jugo", "md del docente" );
    contiene( textoPptx( new File( dir, "bruno-docente.pptx" ) ), "Jugo", "PPTX del docente" );
  }

  /**
   * Word y Excel (Informes 0.5.1) por el mismo puente: el docente ve el valor del caso oculto, el alumno no. Es lo
   * que escribe grade_exercise con report_formats docx y xlsx.
   */
  public void pruebaWordYExcel() throws Exception {
    ResultadoCorreccion r = bruno();
    File dir = limpia( "c9-office" );
    Thread hilo = Thread.currentThread();
    ClassLoader antes = hilo.getContextClassLoader();
    hilo.setContextClassLoader( CorreccionAInforme.class.getClassLoader() ); // POI (JAXB), como CorrectorTools
    try {
      List<Formato> office = Arrays.asList( Formato.DOCX, Formato.XLSX );
      Map<Formato, Resultado> doc = CorreccionAInforme.exportar( r, false, office, dir, "bruno-docente", new Tema(),
        Progreso.NULO );
      Map<Formato, Resultado> al = CorreccionAInforme.exportar( r, true, office, dir, "bruno-alumno", new Tema(),
        Progreso.NULO );
      igual( 2, doc.size(), "docx y xlsx del docente" );
      igual( 2, al.size(), "docx y xlsx del alumno" );
      for ( String ext : Arrays.asList( "docx", "xlsx" ) ) {
        File d = new File( dir, "bruno-docente." + ext );
        File a = new File( dir, "bruno-alumno." + ext );
        archivo( d, 2000, ext + " del docente" );
        archivo( a, 2000, ext + " del alumno" );
        contiene( textoZip( d ), "Jugo", ext + ": el docente ve el oculto" );
        noContiene( textoZip( a ), "Jugo", ext + ": el alumno no ve el oculto" );
      }
    } finally {
      hilo.setContextClassLoader( antes );
    }
  }

  /** El texto de todas las partes XML de un docx/xlsx (sin las etiquetas). */
  private static String textoZip( File f ) throws IOException {
    StringBuilder b = new StringBuilder();
    try ( java.util.zip.ZipFile z = new java.util.zip.ZipFile( f ) ) {
      java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
      while ( en.hasMoreElements() ) {
        java.util.zip.ZipEntry e = en.nextElement();
        if ( !e.getName().endsWith( ".xml" ) ) {
          continue;
        }
        try ( InputStream in = z.getInputStream( e ) ) {
          java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
          byte[] buf = new byte[8192];
          for ( int n; ( n = in.read( buf ) ) > 0; ) {
            o.write( buf, 0, n );
          }
          b.append( new String( o.toByteArray(), "UTF-8" ).replaceAll( "<[^>]+>", "" ) ).append( '
' );
        }
      }
    }
    return b.toString();
  }

  /** JSON = el contrato (no el modelo del informe); devolucionHtml para el lote. */
  public void pruebaJsonYDevolucion() throws Exception {
    ResultadoCorreccion r = bruno();
    File dir = limpia( "c9-json" );
    CorreccionAInforme.exportar( r, true, Arrays.asList( Formato.JSON ), dir, "alumno", new Tema(), null );
    CorreccionAInforme.exportar( r, false, Arrays.asList( Formato.JSON ), dir, "docente", new Tema(), null );
    ResultadoCorreccion al = CorrectorJson.leer( new File( dir, "alumno.json" ), ResultadoCorreccion.class );
    igual( ResultadoCorreccion.FORMATO, al.formato, "es un pentaho-live-grade-result/1" );
    // al leerlo, Comparacion arranca con ejemplos vacios: lo que importa es que no vinieron
    cierto( al.caso( "oculto" ).comparacion.ejemplos == null || al.caso( "oculto" ).comparacion.ejemplos.distintas
      .isEmpty(), "el JSON del alumno sin los ejemplos del oculto" );
    igual( 3, al.caso( "visible" ).comparacion.ejemplos.distintas.size(), "con los del visible" );
    sinOcultos( EntornoKettle.leer( new File( dir, "alumno.json" ) ), "JSON del alumno" );
    ResultadoCorreccion docente = CorrectorJson.leer( new File( dir, "docente.json" ), ResultadoCorreccion.class );
    igual( CorrectorJson.escribir( r, false ), CorrectorJson.escribir( docente, false ), "el del docente, tal cual" );

    File h = CorreccionAInforme.devolucionHtml( r, new File( dir, "bruno_agregado_mal.html" ), new Tema() );
    igual( new File( dir, "bruno_agregado_mal.html" ), h, "el archivo pedido" );
    String html = EntornoKettle.leer( h );
    falso( html.toLowerCase( Locale.ROOT ).contains( "<script" ), "liviano" );
    contiene( html, "Devolución para bruno_agregado_mal", "la del alumno" );
    sinOcultos( html, "devolucion HTML" );
  }

  // ================================================================ escapes, estados y avisos

  /** Lo que viene de la entrega se escapa en cada formato. */
  public void pruebaEscapes() throws Exception {
    ResultadoCorreccion d = puntuado( "diego_javascript" );
    d.alumno = "<script>alert(1)</script>";
    d.stepsBloqueados.get( 0 ).step = "Paso | raro\n# titulo <b>";
    File dir = limpia( "c9-escapes" );
    CorreccionAInforme.exportar( d, true, Arrays.asList( Formato.HTML_ESTATICO, Formato.MARKDOWN ), dir, "x",
      new Tema(), null );
    String h = EntornoKettle.leer( new File( dir, "x.light.html" ) );
    noContiene( h, "<script>alert", "el nombre no se ejecuta" );
    contiene( h, "&lt;script&gt;alert(1)&lt;/script&gt;", "va escapado" );
    noContiene( h, "titulo <b>", "el step tampoco" );
    contiene( h, "Steps bloqueados", "tabla de bloqueados" );
    String md = EntornoKettle.leer( new File( dir, "x.md" ) );
    noContiene( md, "<script>", "md escapado" );
    noContiene( md, "<b>", "md escapado (step)" );

    ResultadoCorreccion b = bruno();
    b.caso( "visible" ).comparacion.ejemplos.distintas.get( 0 ).real = "<img src=x onerror=alert(1)>";
    String hb = new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).html( CorreccionAInforme.informe( b, true,
      new Tema() ), new Tema() );
    noContiene( hb, "<img src=x", "un valor del alumno no es HTML" );
    contiene( hb, "&lt;img src=x onerror=alert(1)&gt;", "va escapado" );
  }

  /** Bloqueada, tiempo agotado, no cargo, parcial, caida con log y modo confianza. */
  public void pruebaEstados() throws Exception {
    Tema tema = new Tema();
    // diego: bloqueada
    Informe d = CorreccionAInforme.informe( puntuado( "diego_javascript" ), true, tema );
    noNulo( d.seccion( CorreccionAInforme.SEC_BLOQUEADOS ), "steps bloqueados" );
    Tabla bl = (Tabla) bloque( d.seccion( CorreccionAInforme.SEC_BLOQUEADOS ), Tabla.class );
    igual( "condicional", bl.filas.get( 0 ).get( 2 ), "nivel traducido" );
    igual( "step no permitido", bl.filas.get( 0 ).get( 3 ), "motivo traducido" );
    Aviso ad = (Aviso) bloque( d.seccion( CorreccionAInforme.SEC_NOTA ), Aviso.class );
    igual( Aviso.ERROR, ad.nivel, "bloqueada: error" );
    contiene( ad.texto, "No se ejecutó", "por que" );
    igual( Indicador.ERROR, ( (Indicadores) bloque( d.seccion( CorreccionAInforme.SEC_NOTA ), Indicadores.class ) )
      .items.get( 1 ).nivel, "estado en rojo" );

    // fede: tiempo agotado
    Informe f = CorreccionAInforme.informe( puntuado( "fede_bucle" ), true, tema );
    Aviso af = (Aviso) bloque( f.seccion( CorreccionAInforme.SEC_NOTA ), Aviso.class );
    igual( Aviso.WARN, af.nivel, "timeout: aviso" );
    igual( "no terminó a tiempo", ( (Indicadores) bloque( f.seccion( "case_visible" ), Indicadores.class ) ).items.get(
      0 ).valor, "estado del caso" );

    // no cargo: el detalle solo para el docente
    ResultadoCorreccion nc = new ResultadoCorreccion();
    nc.pack = VENTAS;
    nc.alumno = "t5";
    nc.archivo = "t5_xxe.ktr";
    nc.idioma = "es";
    nc.estado = ResultadoCorreccion.NO_CARGO;
    nc.detalle = "DOCTYPE is disallowed";
    nc.nota = 0.0;
    nc.pistas.add( "No se pudo abrir tu archivo." );
    String ncDoc = ModeloJson.escribir( CorreccionAInforme.informe( nc, false, tema ), false );
    contiene( ncDoc, "DOCTYPE is disallowed", "el docente ve por que" );
    contiene( ncDoc, "No se pudo abrir el archivo de la entrega.", "y el estado" );
    Informe ncAl = CorreccionAInforme.informe( nc, true, tema );
    noContiene( ModeloJson.escribir( ncAl, false ), "DOCTYPE", "el alumno no" );
    igual( Arrays.asList( SeccionId.PORTADA, CorreccionAInforme.SEC_NOTA, CorreccionAInforme.SEC_PISTAS ), ids( ncAl ),
      "sin casos ni criterios" );
    igual( "ventas-por-producto", ncAl.titulo, "sin titulo del pack: su id" );

    // parcial: sin nota
    ResultadoCorreccion p = bruno();
    p.estado = ResultadoCorreccion.EN_CURSO;
    p.nota = null;
    Informe ip = CorreccionAInforme.informe( p, true, tema );
    Indicador np = ( (Indicadores) bloque( ip.seccion( CorreccionAInforme.SEC_NOTA ), Indicadores.class ) ).items.get(
      0 );
    igual( "– / 10", np.valor, "sin nota" );
    nulo( np.nivel, "neutra" );
    igual( Aviso.INFO, ( (Aviso) bloque( ip.seccion( CorreccionAInforme.SEC_NOTA ), Aviso.class ) ).nivel, "parcial" );
    contiene( json( ip.seccion( SeccionId.PORTADA ) ), "Nota: – / 10 (en curso)", "portada sin nota" );

    // caida con un caso que no inicializo: el log solo al docente, recortado
    ResultadoCorreccion c = bruno();
    c.estado = ResultadoCorreccion.CAIDA;
    c.modoConfianza = true;
    c.stepsPermitidosExtra.add( "ScriptValueMod" );
    ResultadoCaso v = c.caso( "visible" );
    v.corrida.estado = ResultadoCaso.FALLO_INIT;
    StringBuilder log = new StringBuilder();
    for ( int i = 1; i <= 50; i++ ) {
      log.append( "linea " ).append( i ).append( i == 50 ? " LOG-FALLA" : "" ).append( "\r\n" );
    }
    v.corrida.log = log.toString();
    Informe cd = CorreccionAInforme.informe( c, false, tema );
    String logDoc = json( cd.seccion( "case_visible" ) );
    contiene( logDoc, "LOG-FALLA", "el docente ve el final del log" );
    noContiene( logDoc, "linea 20\\n", "recortado a las ultimas lineas" );
    contiene( logDoc, "linea 21", "las ultimas " + CorreccionAInforme.MAX_LINEAS_LOG );
    contiene( json( cd.seccion( CorreccionAInforme.SEC_NOTAS ) ), "ScriptValueMod", "steps habilitados" );
    Informe ca = CorreccionAInforme.informe( c, true, tema );
    noContiene( ModeloJson.escribir( ca, false ), "LOG-FALLA", "el alumno no ve el log" );
    boolean confianza = false;
    for ( Bloque b : ca.seccion( CorreccionAInforme.SEC_NOTA ).bloques ) {
      confianza |= b instanceof Aviso && ( (Aviso) b ).texto.contains( "modo confianza" );
    }
    cierto( confianza, "el modo confianza se avisa" );
    igual( Indicador.ERROR, ( (Indicadores) bloque( ca.seccion( "case_visible" ), Indicadores.class ) ).items.get( 0 )
      .nivel, "caso que no inicializo en rojo" );

    // todos se dibujan
    File dir = limpia( "c9-estados" );
    int n = 0;
    for ( ResultadoCorreccion x : Arrays.asList( nc, p, c, puntuado( "diego_javascript" ), puntuado( "fede_bucle" ) ) ) {
      for ( boolean alumno : new boolean[] { true, false } ) {
        Map<Formato, Resultado> m = CorreccionAInforme.exportar( x, alumno, Arrays.asList( Formato.HTML_ESTATICO,
          Formato.MARKDOWN, Formato.PPTX ), dir, "e" + ( n++ ), tema, null );
        for ( Resultado res : m.values() ) {
          cierto( res.archivos.get( 0 ).isFile(), "escribio " + res.archivos.get( 0 ) );
        }
      }
    }
  }

  /** Avisos por codigo: con datos, con frase (detalle solo al docente) y desconocidos. */
  public void pruebaAvisos() throws Exception {
    Rotulos es = Rotulos.de( CorreccionAInforme.AREA, "es" );
    igual( "Números con coma decimal en: importe_total, importe_medio", CorreccionAInforme.textoAviso(
      new ResultadoCorreccion.Aviso( "comma_decimal", "importe_total, importe_medio" ), es, false ), "con datos" );
    ResultadoCorreccion.Aviso amb = new ResultadoCorreccion.Aviso( "output_ambiguous",
      "several output steps share the same columns: 'A' was captured (also [B])" );
    amb.step = "A";
    igual( "Hay más de un step de salida con las mismas columnas: se corrigió uno solo (several output steps share "
      + "the same columns: 'A' was captured (also [B])).", CorreccionAInforme.textoAviso( amb, es, true ),
      "docente: con el detalle" );
    igual( "Hay más de un step de salida con las mismas columnas: se corrigió uno solo («A»).",
      CorreccionAInforme.textoAviso( amb, es, false ), "alumno: solo el step" );
    igual( "detalle raro", CorreccionAInforme.textoAviso( new ResultadoCorreccion.Aviso( "xyz", "detalle raro" ), es,
      false ), "codigo sin texto: el detalle" );
    igual( "xyz", CorreccionAInforme.textoAviso( new ResultadoCorreccion.Aviso( "xyz", null ), es, false ),
      "ni detalle: el codigo" );
    nulo( CorreccionAInforme.textoAviso( null, es, true ), "null" );
    Rotulos en = Rotulos.de( CorreccionAInforme.AREA, "en" );
    igual( "Columns the statement does not ask for: total", CorreccionAInforme.textoAviso(
      new ResultadoCorreccion.Aviso( "extra_columns", "total" ), en, false ), "en ingles" );

    // en un oculto, el alumno ve el aviso de la comparacion (solo nombres de columnas)
    ResultadoCorreccion b = bruno();
    b.caso( "oculto" ).avisos.add( new ResultadoCorreccion.Aviso( "comma_decimal", "importe_total" ) );
    b.avisos.add( new ResultadoCorreccion.Aviso( "unused_connection_removed", "connection 'mysql' (MYSQL) removed" ) );
    Informe inf = CorreccionAInforme.informe( b, true, new Tema() );
    contiene( json( inf.seccion( "case_oculto" ) ), "Números con coma decimal en: importe_total", "aviso del oculto" );
    String notas = json( inf.seccion( CorreccionAInforme.SEC_NOTAS ) );
    contiene( notas, "Se quitó una conexión que ningún step usa.", "aviso de la entrega traducido" );
    noContiene( notas, "mysql", "sin el detalle en ingles" );
  }

  /** en/es/pt: ninguna clave sin texto, con el separador decimal de cada idioma. */
  public void pruebaIdiomas() throws Exception {
    for ( String idioma : Arrays.asList( "es", "en", "pt" ) ) {
      for ( String a : PROTOTIPO ) {
        ResultadoCorreccion r = resultado( a );
        r.idioma = idioma;
        CorrectorNota.puntuar( pack( r.pack ), r, null );
        for ( boolean alumno : new boolean[] { true, false } ) {
          Informe inf = CorreccionAInforme.informe( r, alumno, new Tema() );
          igual( idioma, inf.idioma, a + ": idioma" );
          sinClaves( ModeloJson.escribir( inf, false ), a + " " + idioma + ( alumno ? " alumno" : " docente" ) );
        }
      }
    }
    ResultadoCorreccion b = resultado( "bruno_agregado_mal" );
    b.idioma = "en";
    CorrectorNota.puntuar( pack( VENTAS ), b, null );
    Informe en = CorreccionAInforme.informe( b, true, new Tema() );
    igual( "6.5 / 10", ( (Indicadores) bloque( en.seccion( CorreccionAInforme.SEC_NOTA ), Indicadores.class ) ).items
      .get( 0 ).valor, "punto decimal en ingles" );
    igual( "Feedback for bruno_agregado_mal", en.subtitulo, "subtitulo en ingles" );
    b.idioma = "pt";
    CorrectorNota.puntuar( pack( VENTAS ), b, null );
    Informe pt = CorreccionAInforme.informe( b, true, new Tema() );
    igual( "Devolutiva para bruno_agregado_mal", pt.subtitulo, "subtitulo en portugues" );
    igual( "6,5 / 10", ( (Indicadores) bloque( pt.seccion( CorreccionAInforme.SEC_NOTA ), Indicadores.class ) ).items
      .get( 0 ).valor, "coma en portugues" );
  }

  /** Las claves inf.* y aviso.* (las de C9) estan en los tres idiomas con los mismos %s y %d. */
  public void pruebaTextos() throws Exception {
    Map<String, java.util.Properties> p = new LinkedHashMap<String, java.util.Properties>();
    for ( String i : Arrays.asList( "en", "es", "pt" ) ) {
      java.util.Properties x = new java.util.Properties();
      try ( InputStream in = PruebaCorreccionAInforme.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA
        + "corrector_" + i + ".properties" ) ) {
        noNulo( in, "corrector_" + i + ".properties" );
        x.load( new java.io.InputStreamReader( in, java.nio.charset.StandardCharsets.UTF_8 ) );
      }
      p.put( i, x );
    }
    Pattern marca = Pattern.compile( "%[,.0-9]*[sdf]" );
    int n = 0;
    for ( String k : p.get( "en" ).stringPropertyNames() ) {
      if ( !k.startsWith( "inf." ) && !k.startsWith( "aviso." ) ) {
        continue;
      }
      n++;
      List<String> en = marcas( marca, p.get( "en" ).getProperty( k ) );
      for ( String i : Arrays.asList( "es", "pt" ) ) {
        String v = p.get( i ).getProperty( k );
        noNulo( v, k + " en " + i );
        igual( en, marcas( marca, v ), k + ": los mismos marcadores en " + i );
      }
    }
    cierto( n > 80, "las claves de C9: " + n );
  }

  private static List<String> marcas( Pattern p, String s ) {
    List<String> l = new ArrayList<String>();
    Matcher m = p.matcher( s );
    while ( m.find() ) {
      l.add( m.group() );
    }
    return l;
  }

  // ================================================================ curso

  /** El informe de un lote: indicadores, distribucion, criterios mas fallados y entregas; con ajustes. */
  public void pruebaCurso() throws Exception {
    List<ResultadoCorreccion> rs = new ArrayList<ResultadoCorreccion>();
    for ( String a : PROTOTIPO ) {
      rs.add( puntuado( a ) );
    }
    Informe inf = CorreccionAInforme.curso( rs, null, null, new Tema() );
    igual( Arrays.asList( SeccionId.PORTADA, CorreccionAInforme.SEC_CURSO, CorreccionAInforme.SEC_DISTRIBUCION,
      CorreccionAInforme.SEC_CHECKS, CorreccionAInforme.SEC_ENTREGAS ), ids( inf ), "secciones del curso" );
    igual( "Ejercicio 3 - Ventas por producto", inf.titulo, "titulo" );
    igual( "Resultados del curso", inf.subtitulo, "subtitulo" );
    Indicadores tarjetas = (Indicadores) bloque( inf.seccion( CorreccionAInforme.SEC_CURSO ), Indicadores.class );
    // 10 + 6,5 + 9,7 + 0,5 + 5,5 + 1 + 9,7 + 6 = 48,9 / 8 = 6,11; mediana (6 + 6,5) / 2 = 6,25
    igual( Arrays.asList( "8", "6,1", "6,3", "6 de 8", "1", "1", "0" ), valores( tarjetas ), "indicadores" );

    Grafico g = (Grafico) bloque( inf.seccion( CorreccionAInforme.SEC_DISTRIBUCION ), Grafico.class );
    igual( Grafico.LINEA, g.clase, "linea: barras_h ordenaria los tramos por cantidad" );
    igual( 10, g.categorias.size(), "diez tramos" );
    igual( "0–1", g.categorias.get( 0 ), "primer tramo" );
    igual( "9–10", g.categorias.get( 9 ), "ultimo tramo" );
    igual( Arrays.asList( 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 2.0, 0.0, 0.0, 3.0 ), g.series.get( 0 ).valores,
      "diego | fede | elena | hugo, bruno | ana, carla, gabi" );

    Grafico ch = (Grafico) bloque( inf.seccion( CorreccionAInforme.SEC_CHECKS ), Grafico.class );
    igual( Grafico.BARRAS_H, ch.clase, "barras horizontales" );
    igual( "Valores correctos", ch.categorias.get( 0 ), "el mas fallado primero" );
    double antes = Double.MAX_VALUE;
    for ( Double x : ch.series.get( 0 ).valores ) {
      cierto( x <= antes, "ordenado de mas a menos fallado: " + ch.series.get( 0 ).valores );
      antes = x;
    }
    Tabla tc = (Tabla) bloque( inf.seccion( CorreccionAInforme.SEC_CHECKS ), Tabla.class );
    igual( 7, tc.filas.size(), "un renglon por check, en el orden de la rubrica" );
    igual( "Corre sin errores", tc.filas.get( 0 ).get( 0 ), "primero runs" );

    Tabla te = (Tabla) bloque( inf.seccion( CorreccionAInforme.SEC_ENTREGAS ), Tabla.class );
    igual( 8, te.filas.size(), "ocho entregas" );
    igual( Arrays.asList( "ana_orden_distinto", "ana_orden_distinto.ktr", "corregida", "10,0", "7 de 7" ), te.filas
      .get( 0 ), "ordenadas por alumno" );
    sinClaves( ModeloJson.escribir( inf, false ), "curso" );

    // con el ajuste del docente: bruno 6,5 -> 7,0
    Map<String, Double> ajustes = new LinkedHashMap<String, Double>();
    ajustes.put( "bruno_agregado_mal", 7.0 );
    Informe aj = CorreccionAInforme.curso( rs, ajustes, null, new Tema() );
    Grafico ga = (Grafico) bloque( aj.seccion( CorreccionAInforme.SEC_DISTRIBUCION ), Grafico.class );
    igual( 1.0, ga.series.get( 0 ).valores.get( 7 ), "bruno pasa al tramo 7-8" );
    igual( 1.0, ga.series.get( 0 ).valores.get( 6 ), "queda hugo en 6-7" );
    igual( "7,0", ( (Tabla) bloque( aj.seccion( CorreccionAInforme.SEC_ENTREGAS ), Tabla.class ) ).filas.get( 1 ).get(
      3 ), "la nota final en la tabla" );

    // vacio y en ingles
    Informe vacio = CorreccionAInforme.curso( new ArrayList<ResultadoCorreccion>(), null, "en", new Tema() );
    igual( Arrays.asList( SeccionId.PORTADA, CorreccionAInforme.SEC_CURSO ), ids( vacio ), "sin entregas" );
    igual( "Course results", vacio.subtitulo, "en ingles" );

    File dir = limpia( "c9-curso" );
    for ( Formato f : Arrays.asList( Formato.HTML, Formato.MARKDOWN, Formato.PPTX ) ) {
      File destino = InformeServicio.archivo( dir, "curso", f );
      Resultado res = CorreccionAInforme.exportar( inf, new Tema(), f, destino, Progreso.NULO );
      cierto( res.archivos.get( 0 ).isFile(), "curso en " + f );
    }
    String h = EntornoKettle.leer( new File( dir, "curso.html" ) );
    contiene( h, "<svg", "el grafico dibujado" );
    contiene( h, "Distribución de notas", "su seccion" );
    contiene( textoPptx( new File( dir, "curso.pptx" ) ), "Criterios más fallados", "PPTX del curso" );
  }

  /** Sin tema, el de Ajustes.cargar(): colores e identidad del docente en la devolucion. */
  public void pruebaTemaDeAjustes() throws Exception {
    File f = Ajustes.archivo();
    byte[] antes = f.isFile() ? Files.readAllBytes( f.toPath() ) : null;
    try {
      EntornoKettle.escribir( f, "{ \"identidad\": { \"autor\": \"Prof. Prueba\", \"institucion\": \"Universidad "
        + "de Prueba\", \"curso\": \"ETL 3\" }, \"colores\": { \"primario\": \"#7A1F5C\" } }" );
      Tema t = CorreccionAInforme.tema();
      igual( "Universidad de Prueba", t.identidad.institucion, "lee informes.json" );
      File dir = limpia( "c9-tema" );
      CorreccionAInforme.exportar( bruno(), true, Arrays.asList( Formato.HTML ), dir, "con-tema", null, null );
      String h = EntornoKettle.leer( new File( dir, "con-tema.html" ) );
      contiene( h.toUpperCase( Locale.ROOT ), "#7A1F5C", "el color del tema" );
      contiene( h, "Docente: Prof. Prueba", "el docente" );
      contiene( h, "Institución: Universidad de Prueba", "la institucion" );
      contiene( h, "Curso: ETL 3", "el curso" );
    } finally {
      if ( antes != null ) {
        Files.write( f.toPath(), antes );
      } else if ( f.isFile() && !f.delete() ) {
        falla( "no se pudo borrar " + f );
      }
    }
  }

  // ================================================================ ayudas

  /** bruno puntuado con el pack (criterios y pistas de C5), con marcas de lo que es solo del docente. */
  private static ResultadoCorreccion bruno() throws IOException {
    ResultadoCorreccion r = puntuado( "bruno_agregado_mal" );
    r.detalle = "DETALLE-ENTREGA";
    r.captura = "file";
    r.versionPlugin = "0.5.0";
    r.tiempos.procesoSeg = 8.0;
    r.caso( "visible" ).corrida.detalle = "DETALLE-VISIBLE";
    r.caso( "visible" ).corrida.log = "LOG-VISIBLE";
    r.caso( "oculto" ).corrida.detalle = "DETALLE-OCULTO";
    r.caso( "oculto" ).corrida.log = "LOG-OCULTO";
    r.caso( "oculto" ).redirecciones.add( "input 'Leer ventas': REDIR-OCULTA -> datos/ventas.csv" );
    return r;
  }

  private static ResultadoCorreccion resultado( String alumno ) throws IOException {
    return CorrectorJson.leer( CorrectorRecursos.resultado( alumno ), ResultadoCorreccion.class ).completar();
  }

  private static ResultadoCorreccion puntuado( String alumno ) throws IOException {
    ResultadoCorreccion r = resultado( alumno );
    return CorrectorNota.puntuar( pack( r.pack ), r, null );
  }

  private static Pack pack( String id ) throws IOException {
    File f = CorrectorRecursos.pack( id );
    return CorrectorJson.leer( f.isDirectory() ? new File( f, "pack.json" ) : f, Pack.class ).completar();
  }

  private static File limpia( String sub ) throws IOException {
    File d = EntornoKettle.salida( sub );
    EntornoKettle.borrar( d.toPath() );
    d.mkdirs();
    return d;
  }

  private static List<String> ids( Informe inf ) {
    List<String> l = new ArrayList<String>();
    for ( Seccion s : inf.secciones ) {
      l.add( s.id );
    }
    return l;
  }

  private static String json( Object o ) throws IOException {
    noNulo( o, "seccion" );
    return ModeloJson.escribir( o, false );
  }

  /** El primer bloque de esa clase en la seccion. */
  private static Bloque bloque( Seccion s, Class<? extends Bloque> tipo ) {
    noNulo( s, "seccion" );
    for ( Bloque b : s.bloques ) {
      if ( tipo.isInstance( b ) ) {
        return b;
      }
    }
    falla( "la seccion " + s.id + " no tiene un " + tipo.getSimpleName() );
    return null;
  }

  /** La tabla con ese id, o null. */
  private static Tabla tabla( Seccion s, String id ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla && id.equals( b.id ) ) {
        return (Tabla) b;
      }
    }
    return null;
  }

  private static List<String> valores( Indicadores ind ) {
    List<String> l = new ArrayList<String>();
    for ( Indicador i : ind.items ) {
      l.add( i.valor );
    }
    return l;
  }

  private static void sinOcultos( String texto, String donde ) {
    for ( String v : OCULTOS_BRUNO ) {
      noContiene( texto, v, donde + ": un valor del caso oculto" );
    }
    for ( String marca : Arrays.asList( "DETALLE-", "LOG-", "REDIR-OCULTA" ) ) {
      noContiene( texto, marca, donde + ": solo del docente" );
    }
  }

  private static int cuenta( String texto, String parte ) {
    int n = 0;
    for ( int i = texto.indexOf( parte ); i >= 0; i = texto.indexOf( parte, i + parte.length() ) ) {
      n++;
    }
    return n;
  }

  private static void sinClaves( String texto, String donde ) {
    Matcher m = CLAVE_CRUDA.matcher( texto );
    if ( m.find() ) {
      falla( donde + ": queda una clave de Rotulos sin texto: «" + m.group() + "»" );
    }
  }

  /** Todo el texto del PPTX: cuadros, grupos (las tarjetas) y tablas. */
  private static String textoPptx( File f ) throws Exception {
    StringBuilder b = new StringBuilder();
    try ( InputStream in = new FileInputStream( f ); XMLSlideShow ppt = new XMLSlideShow( in ) ) {
      cierto( ppt.getSlides().size() >= 5, "diapositivas: " + ppt.getSlides().size() );
      for ( XSLFSlide d : ppt.getSlides() ) {
        texto( d.getShapes(), b );
      }
    }
    return b.toString();
  }

  private static void texto( List<XSLFShape> formas, StringBuilder b ) {
    for ( XSLFShape sh : formas ) {
      if ( sh instanceof XSLFGroupShape ) {
        texto( ( (XSLFGroupShape) sh ).getShapes(), b );
      } else if ( sh instanceof XSLFTable ) {
        for ( XSLFTableRow r : (XSLFTable) sh ) {
          for ( XSLFTableCell c : r.getCells() ) {
            b.append( c.getText() ).append( " | " );
          }
          b.append( '\n' );
        }
      } else if ( sh instanceof XSLFTextShape ) {
        b.append( ( (XSLFTextShape) sh ).getText() ).append( '\n' );
      }
    }
  }
}
