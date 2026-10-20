package io.github.ivi2511.pentahomcp;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Informe de muestra COMPLETO para probar renderizadores sin recolectores (diseno §7.2): todas las
 * secciones de una transformacion, los ocho tipos de bloque, las tres clases de grafico, diagramas
 * flujo/linaje/er, los cuatro estados de seccion (job_flow "omitida", lineage y profile "parcial",
 * validation "error"), los niveles de aviso e indicador, un bloque con ia = true y textos con
 * caracteres que hay que escapar ({@code <b>&"x}, {@code |}, saltos de linea, acentos).
 * <p>
 * Tambien da PNG y SVG de ejemplo para simular lo que aun no existe: {@link #svgPrerender} (lo que
 * daria PrerenderMermaid.svg), {@link #pngPrerender} (PrerenderMermaid.png) y {@link #svgMuestra}
 * (con rect de fondo, como GraficosSvg).
 *
 * @dueño WP0
 */
public final class ModeloMuestra {

  /** Nombre de step con todo lo que hay que escapar en HTML, Markdown y Mermaid. */
  public static final String STEP_RARO = "<b>&\"x";

  private ModeloMuestra() {
  }

  /** Informe completo en espanol. Cada llamada devuelve un objeto nuevo. */
  public static Informe crear() {
    Informe inf = new Informe();
    inf.titulo = "Ventas por producto";
    inf.subtitulo = "Informe de la transformación 03_ventas_por_producto";
    inf.tipoDoc = Informe.TRANSFORMACION;
    inf.archivo = "C:\\ejemplos\\03_ventas_por_producto.ktr";
    inf.nombreDoc = "03_ventas_por_producto";
    inf.idioma = "es";
    inf.generado = "2026-10-20T14:32:05-03:00";
    inf.rotulos.put( "page", "Página" );
    inf.rotulos.put( "of", "de" );
    inf.rotulos.put( "contents", "Contenido" );
    inf.rotulos.put( "generated_with", "Generado con" );
    inf.rotulos.put( "ai_label", "Texto redactado con asistencia de IA" );
    inf.avisos.add( "El documento tenía cambios sin guardar: el informe usa la versión de Spoon." );

    inf.secciones.add( portada() );
    inf.secciones.add( resumen() );
    inf.secciones.add( flujo() );
    inf.secciones.add( Seccion.omitida( SeccionId.FLUJO_JOB, "Flujo del job", "Solo para jobs" ) );
    inf.secciones.add( linaje() );
    inf.secciones.add( diccionario() );
    inf.secciones.add( destino() );
    inf.secciones.add( perfil() );
    inf.secciones.add( metricas() );
    inf.secciones.add( validacion() );
    inf.secciones.add( conexiones() );
    inf.secciones.add( narrativa() );
    return inf;
  }

  /** Tema con identidad y logo de muestra (colores por defecto). */
  public static Tema tema() {
    Tema t = new Tema();
    t.identidad.autor = "Nombre Apellido";
    t.identidad.institucion = "Universidad de Ejemplo";
    t.identidad.curso = "Big Data I";
    t.logo = "logo.png";
    t.logoDatos = png( 240, 80, "LOGO" );
    t.logoMime = Imagen.PNG;
    return t;
  }

  /** Todos los Diagrama del informe (en el orden en que aparecen). */
  public static List<Diagrama> diagramas( Informe inf ) {
    List<Diagrama> l = new ArrayList<>();
    for ( Seccion s : inf.secciones ) {
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Diagrama ) {
          l.add( (Diagrama) b );
        }
      }
    }
    return l;
  }

  /** id de Diagrama con mermaid -> SVG de muestra (simula PrerenderMermaid.svg). */
  public static Map<String, String> svgPrerender( Informe inf ) {
    Map<String, String> m = new LinkedHashMap<>();
    for ( Diagrama d : diagramas( inf ) ) {
      if ( d.mermaid != null ) {
        m.put( d.id, svgMuestra( "Diagrama " + d.clase + " (pre-render de muestra)", 640, 240 ) );
      }
    }
    return m;
  }

  /** id de Diagrama con mermaid -> PNG de muestra (simula PrerenderMermaid.png). */
  public static Map<String, byte[]> pngPrerender( Informe inf ) {
    Map<String, byte[]> m = new LinkedHashMap<>();
    for ( Diagrama d : diagramas( inf ) ) {
      if ( d.mermaid != null ) {
        m.put( d.id, png( 1280, 480, "Diagrama " + d.clase ) );
      }
    }
    return m;
  }

  /**
   * SVG 1.1 autocontenido, compatible con IE11 y con POI: rect de fondo que cubre el viewBox,
   * atributos inline, texto con text-anchor (sin dominant-baseline).
   */
  public static String svgMuestra( String texto, int ancho, int alto ) {
    String t = texto.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" ).replace( "\"", "&quot;" );
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" width=\"" + ancho + "\" height=\"" + alto
      + "\" viewBox=\"0 0 " + ancho + " " + alto + "\">"
      + "<rect x=\"0\" y=\"0\" width=\"" + ancho + "\" height=\"" + alto + "\" fill=\"#FFFFFF\"/>"
      + "<rect x=\"10\" y=\"10\" width=\"" + ( ancho - 20 ) + "\" height=\"" + ( alto - 20 )
      + "\" fill=\"#E9EBEE\" stroke=\"#243B53\" stroke-width=\"2\" rx=\"6\"/>"
      + "<text x=\"" + ( ancho / 2 ) + "\" y=\"" + ( alto / 2 ) + "\" dy=\"0.35em\" text-anchor=\"middle\""
      + " font-family=\"Segoe UI, Arial, sans-serif\" font-size=\"16\" fill=\"#1F2933\">" + t + "</text></svg>";
  }

  /** PNG de muestra (bytes de archivo) con un marco y un texto. */
  public static byte[] png( int ancho, int alto, String texto ) {
    BufferedImage img = new BufferedImage( ancho, alto, BufferedImage.TYPE_INT_ARGB );
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
      g.setColor( Color.WHITE );
      g.fillRect( 0, 0, ancho, alto );
      g.setColor( new Color( 0x24, 0x3B, 0x53 ) );
      g.setStroke( new BasicStroke( 4f ) );
      g.drawRect( 4, 4, ancho - 9, alto - 9 );
      g.setColor( new Color( 0x3F, 0x74, 0xD8 ) );
      g.fillRect( 20, 20, Math.max( 10, ancho / 6 ), Math.max( 10, alto / 4 ) );
      g.setColor( new Color( 0x1C, 0x9A, 0x80 ) );
      g.fillRect( ancho - 20 - Math.max( 10, ancho / 6 ), alto - 20 - Math.max( 10, alto / 4 ), Math.max( 10, ancho
        / 6 ), Math.max( 10, alto / 4 ) );
      g.setColor( new Color( 0x1F, 0x29, 0x33 ) );
      g.setFont( new Font( Font.SANS_SERIF, Font.BOLD, Math.max( 12, alto / 8 ) ) );
      int w = g.getFontMetrics().stringWidth( texto );
      g.drawString( texto, Math.max( 8, ( ancho - w ) / 2 ), alto / 2 + g.getFontMetrics().getAscent() / 3 );
    } finally {
      g.dispose();
    }
    try {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      ImageIO.write( img, "png", bo );
      return bo.toByteArray();
    } catch ( IOException e ) {
      throw new IllegalStateException( e );
    }
  }

  // ---------------------------------------------------------------- secciones

  private static Seccion portada() {
    Seccion s = new Seccion( SeccionId.PORTADA, "Portada" );
    s.resumen = "Transformación 03_ventas_por_producto, versión 1.0.";
    Imagen logo = new Imagen( Imagen.PNG, png( 240, 80, "LOGO" ), 240, 80, 1f );
    logo.id = "cover-logo";
    logo.alt = "Logo de la institución";
    s.con( logo );
    Lista l = new Lista( "Documento: 03_ventas_por_producto.ktr", "Autor: Nombre Apellido",
      "Institución: Universidad de Ejemplo", "Versión: 1.0", "Modificado: 2026-10-20 14:32 por admin" );
    l.id = "cover-datos";
    s.con( l );
    return s;
  }

  private static Seccion resumen() {
    Seccion s = new Seccion( SeccionId.RESUMEN, "Resumen" );
    s.resumen = "Lee 1 archivo CSV (ventas.csv), calcula, ordena y agrupa, y escribe 1 archivo. 8 steps y 7 hops.";
    Parrafo ia = new Parrafo( "**Resumen del autor.** La transformación consolida las ventas diarias "
      + "por producto para el tablero semanal." );
    ia.id = "summary-narrativa";
    ia.ia = true;
    s.con( ia );
    Indicadores ind = new Indicadores()
      .con( new Indicador( "Steps", "8" ) )
      .con( new Indicador( "Hops", "7" ) )
      .con( new Indicador( "Archivos leídos", "1", "ventas.csv", null ) )
      .con( new Indicador( "Archivos escritos", "1", "salida/03_ventas_por_producto.csv", Indicador.OK ) );
    ind.id = "summary-cuentas";
    s.con( ind );
    Parrafo p = new Parrafo( "Lee 1 archivo CSV (**ventas.csv**), calcula, ordena y agrupa, y escribe 1 archivo.\n"
      + "La última ejecución procesó 400.000 filas en 2,7 s; el step más lento fue «Leer ventas» (1,7 s)." );
    p.id = "summary-texto";
    s.con( p );
    return s;
  }

  private static Seccion flujo() {
    Seccion s = new Seccion( SeccionId.FLUJO, "Diagrama de flujo" );
    s.resumen = "8 steps en 1 camino principal, sin hops de error.";
    Diagrama d = new Diagrama();
    d.id = "flow-diagrama";
    d.clase = Diagrama.FLUJO;
    d.alt = "Lienzo de la transformación";
    d.imagen = new Imagen( Imagen.PNG, png( 1600, 600, "Lienzo (escala 2)" ), 1600, 600, 2f );
    d.imagen.id = "flow-lienzo";
    d.imagen.alt = "Lienzo de Spoon";
    d.mermaid = "flowchart LR\n"
      + "  s1[\"Leer ventas\"] --> s2[\"Calcular importe\"]\n"
      + "  s2 --> s3[\"Ordenar por producto\"]\n"
      + "  s3 --> s4[\"Agrupar por producto\"]\n"
      + "  s4 --> s5[\"" + ReportMotor.escaparMermaid( STEP_RARO ) + "\"]\n"
      + "  s5 --> s6[\"Escribir CSV\"]\n";
    d.alternativa = new Tabla( "Desde", "Hacia" ).fila( "Leer ventas", "Calcular importe" )
      .fila( "Calcular importe", "Ordenar por producto" ).fila( "Agrupar por producto", STEP_RARO );
    s.con( d );
    Tabla t = new Tabla( "Step", "Tipo", "Qué hace" )
      .fila( "Leer ventas", "CSV file input", "Lee ventas.csv (separador «;», encabezado)." )
      .fila( "Calcular importe", "Calculator", "importe = cantidad × precio" )
      .fila( "Ordenar por producto", "Sort rows", "Ordena por producto ascendente." )
      .fila( "Agrupar por producto", "Group by", "total = suma(importe)\nfilas = cuenta()" )
      .fila( STEP_RARO, "Dummy", "Nombre con caracteres a escapar: a|b, <i>, &amp;" )
      .fila( "Escribir CSV", "Text file output", null );
    t.id = "flow-steps";
    t.titulo = "Qué hace cada step";
    s.con( t );
    return s;
  }

  private static Seccion linaje() {
    Seccion s = new Seccion( SeccionId.LINAJE, "Linaje de campos" );
    s.estado = Seccion.PARCIAL;
    s.motivo = "1 step sin resolver: «Leer clientes» necesita la base.";
    s.resumen = "4 campos llegan a «Escribir CSV»; 1 se calcula y 1 se agrega.";
    Diagrama d = new Diagrama();
    d.id = "lineage-diagrama";
    d.clase = Diagrama.LINAJE;
    d.alt = "Linaje de los campos de Escribir CSV";
    d.mermaid = "flowchart LR\n"
      + "  subgraph S1[\"Leer ventas\"]\n"
      + "    f1[\"producto\"]\n"
      + "    f2[\"cantidad\"]\n"
      + "    f3[\"precio\"]\n"
      + "  end\n"
      + "  subgraph S2[\"Calcular importe\"]\n"
      + "    f4[\"importe\"]\n"
      + "  end\n"
      + "  subgraph S3[\"Agrupar por producto\"]\n"
      + "    f5[\"producto\"]\n"
      + "    f6[\"total\"]\n"
      + "  end\n"
      + "  f2 -->|cantidad x precio| f4\n"
      + "  f3 --> f4\n"
      + "  f1 -->|pasa| f5\n"
      + "  f4 -->|suma| f6\n";
    d.alternativa = new Tabla( "Campo", "Origen", "Operación" ).fila( "producto", "Leer ventas.producto", "pasa" )
      .fila( "total", "Calcular importe.importe", "suma" );
    s.con( d );
    Tabla t = new Tabla( "Campo destino", "Viene de", "Operación", "Tipo" )
      .fila( "producto", "Leer ventas › producto", "pasa", "pass" )
      .fila( "total", "Calcular importe › importe (cantidad × precio)", "suma", "aggregate" )
      .fila( "filas", "–", "cuenta", "aggregate" )
      .fila( "cliente", "Leer clientes › nombre", "búsqueda (clave: id_cliente)", "lookup" );
    t.id = "lineage-tabla";
    s.con( t );
    Aviso a = new Aviso( Aviso.WARN, "«Leer clientes» (Table input) necesita conectarse a la base: quedó sin resolver." );
    a.id = "lineage-aviso";
    s.con( a );
    ObjectNode datos = ModeloJson.mapper().createObjectNode();
    datos.put( "section", "lineage" );
    datos.put( "engine", "kettle-metadata" );
    datos.put( "target_step", "Escribir CSV" );
    ArrayNode campos = datos.putArray( "fields" );
    ObjectNode c1 = campos.addObject();
    c1.put( "field", "total" );
    c1.put( "kind", "aggregate" );
    ArrayNode origenes = c1.putArray( "origins" );
    ObjectNode o1 = origenes.addObject();
    o1.put( "step", "Calcular importe" );
    o1.put( "field", "importe" );
    datos.putArray( "unresolved_steps" ).addObject().put( "step", "Leer clientes" ).put( "reason", "needs database" );
    s.datos = datos;
    return s;
  }

  private static Seccion diccionario() {
    Seccion s = new Seccion( SeccionId.DICCIONARIO, "Diccionario de datos" );
    s.resumen = "1 salida final con 4 campos.";
    Tabla t = new Tabla( "Campo", "Tipo", "Largo", "Precisión", "Máscara", "Origen" )
      .fila( "producto", "String", "30", "", "", "Leer ventas" )
      .fila( "total", "Number", "12", "2", "#,##0.00", "Agrupar por producto" )
      .fila( "filas", "Integer", "9", "0", "", "Agrupar por producto" )
      .fila( "nota|con|barras", "String", "", "", "", STEP_RARO );
    t.id = "dictionary-escribir-csv";
    t.titulo = "Escribir CSV";
    t.alineacion = new ArrayList<>( Arrays.asList( "l", "l", "r", "r", "l", "l" ) );
    t.pie = "Los largos vacíos significan «sin límite».";
    s.con( t );
    return s;
  }

  private static Seccion destino() {
    Seccion s = new Seccion( SeccionId.DESTINO, "Modelo destino" );
    s.resumen = "1 tabla destino (ventas_producto) en la conexión dw_sqlite, sin consultar la base.";
    Diagrama d = new Diagrama();
    d.id = "target-model-er";
    d.clase = Diagrama.ER;
    d.alt = "Modelo entidad-relación de las tablas destino";
    d.mermaid = "erDiagram\n"
      + "  ventas_producto {\n"
      + "    string producto PK\n"
      + "    double total\n"
      + "    integer filas\n"
      + "  }\n"
      + "  productos {\n"
      + "    string producto PK\n"
      + "    string nombre\n"
      + "  }\n"
      + "  productos ||--o{ ventas_producto : \"producto\"\n";
    d.alternativa = new Tabla( "Tabla", "Columna", "Tipo SQL", "Clave" ).fila( "ventas_producto", "producto",
      "VARCHAR(30)", "PK" ).fila( "ventas_producto", "total", "DOUBLE", "" );
    s.con( d );
    Tabla t = new Tabla( "Columna", "Campo del stream", "Tipo SQL", "Clave" )
      .fila( "producto", "producto", "VARCHAR(30)", "PK" )
      .fila( "total", "total", "DOUBLE", "" )
      .fila( "filas", "filas", "INTEGER", "" );
    t.id = "target-model-columnas";
    t.titulo = "dw_sqlite › main.ventas_producto (Table output, truncate)";
    s.con( t );
    Aviso a = new Aviso( Aviso.INFO, "Sin consultar la base: las claves salen del step, no de la tabla real." );
    a.id = "target-model-aviso";
    s.con( a );
    return s;
  }

  private static Seccion perfil() {
    Seccion s = new Seccion( SeccionId.PERFIL, "Perfil de datos" );
    s.estado = Seccion.PARCIAL;
    s.motivo = "Solo las primeras 5.000 filas.";
    s.resumen = "5.000 filas de «Calcular importe»; «precio» tiene 2,4 % de nulos.";
    Tabla t = new Tabla( "Campo", "Tipo", "Nulos", "Distintos", "Mín", "Máx", "Media" )
      .fila( "producto", "String", "0", "12", "Alfajor", "Yerba", "" )
      .fila( "cantidad", "Integer", "0", "40", "1", "40", "12,3" )
      .fila( "precio", "Number", "120", "≥ 5.000", "0,50", "999,90", "143,27" );
    t.id = "profile-tabla";
    t.alineacion = Arrays.asList( "l", "l", "r", "r", "l", "l", "r" );
    s.con( t );
    Grafico g = new Grafico();
    g.id = "profile-nulos";
    g.titulo = "Porcentaje de nulos por campo";
    g.clase = Grafico.BARRAS_H;
    g.unidad = "%";
    g.categorias.addAll( Arrays.asList( "producto", "cantidad", "precio" ) );
    g.series.add( new Serie( "% nulos", 0.0, 0.0, 2.4 ) );
    s.con( g );
    Aviso a = new Aviso( Aviso.INFO, "Los valores mínimos y máximos son datos reales." );
    a.id = "profile-aviso";
    s.con( a );
    return s;
  }

  private static Seccion metricas() {
    Seccion s = new Seccion( SeccionId.METRICAS, "Métricas de ejecución" );
    s.resumen = "La última ejecución procesó 400.000 filas en 2,7 s; el step más lento fue «Leer ventas» (1,7 s).";
    Indicadores ind = new Indicadores()
      .con( new Indicador( "Duración", "2,7 s" ) )
      .con( new Indicador( "Filas leídas", "400.000" ) )
      .con( new Indicador( "Filas escritas", "12", null, Indicador.OK ) )
      .con( new Indicador( "Errores", "0", null, Indicador.OK ) )
      .con( new Indicador( "Step más lento", "Leer ventas", "1,7 s", Indicador.WARN ) )
      .con( new Indicador( "Rechazadas", "3", "por formato", Indicador.ERROR ) );
    ind.id = "metrics-indicadores";
    s.con( ind );

    Grafico b = new Grafico();
    b.id = "metrics-barras";
    b.titulo = "Filas escritas y tiempo por step";
    b.clase = Grafico.BARRAS_H;
    b.categorias.addAll( Arrays.asList( "Leer ventas", "Calcular importe", "Ordenar por producto",
      "Agrupar por producto", "Escribir CSV" ) );
    b.series.add( new Serie( "Filas", 400000.0, 400000.0, 400000.0, 12.0, 12.0 ) );
    b.series.add( new Serie( "ms", 1700.0, 900.0, 1200.0, 300.0, 15.0 ) );
    s.con( b );

    Grafico gantt = new Grafico();
    gantt.id = "metrics-gantt";
    gantt.titulo = "Línea de tiempo de los steps";
    gantt.clase = Grafico.GANTT;
    gantt.unidad = "ms";
    gantt.nota = "Los steps corren en paralelo: que las barras se solapen es normal.";
    gantt.categorias.addAll( b.categorias );
    gantt.tramos.add( new Tramo( "Leer ventas", Tramo.INIT, 0, 40 ) );
    gantt.tramos.add( new Tramo( "Leer ventas", Tramo.EXEC, 40, 1740 ) );
    gantt.tramos.add( new Tramo( "Calcular importe", Tramo.INIT, 0, 35 ) );
    gantt.tramos.add( new Tramo( "Calcular importe", Tramo.EXEC, 60, 1760 ) );
    gantt.tramos.add( new Tramo( "Ordenar por producto", Tramo.INIT, 0, 30 ) );
    gantt.tramos.add( new Tramo( "Ordenar por producto", Tramo.EXEC, 80, 2500 ) );
    gantt.tramos.add( new Tramo( "Agrupar por producto", Tramo.EXEC, 2400, 2650 ) );
    gantt.tramos.add( new Tramo( "Escribir CSV", Tramo.EXEC, 2600, 2700 ) );
    s.con( gantt );

    Grafico linea = new Grafico();
    linea.id = "metrics-linea";
    linea.titulo = "Filas por intervalo";
    linea.clase = Grafico.LINEA;
    linea.unidad = "filas";
    linea.categorias.addAll( Arrays.asList( "0,5 s", "1,0 s", "1,5 s", "2,0 s", "2,5 s" ) );
    linea.series.add( new Serie( "Leer ventas", 90000.0, 120000.0, 130000.0, 60000.0, 0.0 ) );
    linea.series.add( new Serie( "Escribir CSV", 0.0, 0.0, null, 0.0, 12.0 ) );
    s.con( linea );

    Tabla t = new Tabla( "Step", "Copia", "Leídas", "Escritas", "Errores", "Estado", "Tiempo" )
      .fila( "Leer ventas", "0", "0", "400.000", "0", "Finished", "1,7 s" )
      .fila( "Escribir CSV", "0", "12", "12", "0", "Finished", "0,1 s" );
    t.id = "metrics-tabla";
    t.alineacion = Arrays.asList( "l", "r", "r", "r", "r", "l", "r" );
    s.con( t );

    ObjectNode datos = ModeloJson.mapper().createObjectNode();
    datos.put( "section", "metrics" );
    datos.put( "source", "last_run" );
    datos.put( "duration_ms", 2700 );
    datos.put( "gantt", "approximate" );
    s.datos = datos;
    return s;
  }

  private static Seccion validacion() {
    Seccion s = new Seccion( SeccionId.VALIDACION, "Validación" );
    s.estado = Seccion.ERROR;
    s.motivo = "Tiempo agotado (60 s) al validar.";
    Aviso a = new Aviso( Aviso.ERROR, "La validación no terminó: «Leer clientes» no responde." );
    a.id = "validation-error";
    s.con( a );
    return s;
  }

  private static Seccion conexiones() {
    Seccion s = new Seccion( SeccionId.CONEXIONES, "Conexiones y parámetros" );
    s.resumen = "1 conexión y 2 parámetros.";
    Tabla c = new Tabla( "Nombre", "Tipo", "Host", "Base" ).fila( "dw_sqlite", "SQLite", "", "${Internal.Entry"
      + ".Current.Directory}/db/dw.sqlite" );
    c.id = "connections-conexiones";
    c.titulo = "Conexiones";
    s.con( c );
    Tabla p = new Tabla( "Parámetro", "Por defecto", "Descripción" )
      .fila( "ARCHIVO", "ventas.csv", "Archivo de entrada" )
      .fila( "SEPARADOR", ";", "Separador | del CSV" );
    p.id = "connections-parametros";
    p.titulo = "Parámetros";
    s.con( p );
    return s;
  }

  private static Seccion narrativa() {
    Seccion s = new Seccion( SeccionId.NARRATIVA, "Conclusiones" );
    Parrafo p = new Parrafo( "**Conclusión.** El cuello de botella es la lectura del CSV.\n"
      + "- Conviene activar *lazy conversion*.\n- Revisar los 3 registros rechazados." );
    p.id = "narrative-texto";
    p.ia = true;
    s.con( p );
    return s;
  }
}
