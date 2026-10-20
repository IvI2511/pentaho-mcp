package io.github.ivi2511.pentahomcp.informes.render;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.ivi2511.pentahomcp.EntornoKettle;
import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;

/**
 * RenderMarkdown sobre ModeloMuestra (diseno §3.3 y §8.1): front matter, bloques mermaid con el mismo texto
 * que el modelo, carpeta {@code _archivos} con el lienzo, el logo y los SVG, tablas GFM bien formadas, avisos
 * de Obsidian/GitHub, escapes y nombres de archivo estables al regenerar.
 *
 * @dueño WP7
 */
public class PruebaMarkdown {

  private File carpeta;

  public void preparar() {
    carpeta = EntornoKettle.salida( "markdown" );
  }

  public void pruebaDocumento() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File md = new File( carpeta, "03 ventas.md" );
    EntornoKettle.borrar( RenderMarkdown.carpeta( md ).toPath() );
    Resultado r = new RenderMarkdown().escribir( inf, ModeloMuestra.tema(), md, Progreso.NULO );
    igual( md.getAbsolutePath(), r.archivos.get( 0 ).getAbsolutePath(), "el .md va primero" );
    String t = PruebaHtml.leer( md );
    cierto( t.startsWith( "---\ntitle: 'Ventas por producto'\ndate: 2026-10-20\ntags: [pentaho, etl, informe]\n"
      + "source: '03_ventas_por_producto.ktr'\ngenerator: 'Pentaho Live" ), "front matter:\n" + t
        .substring( 0, Math.min( 300, t.length() ) ) );
    noContiene( t, "C:\\ejemplos", "sin la ruta absoluta del documento (riesgo 20)" );
    // al lado del documento (<carpeta del .ktr>\informes\x.md), la ruta relativa
    File doc = new File( carpeta, "03_ventas_por_producto.ktr" );
    igual( "../03_ventas_por_producto.ktr", RenderMarkdown.fuente( doc.getAbsolutePath(), new File( carpeta,
      "informes" ) ), "source relativo a la carpeta del .md" );
    igual( "x.ktr", RenderMarkdown.fuente( "file:///C:/Users/alguien/x.ktr", carpeta ), "URI: solo el nombre" );
    contiene( t, "\n# Ventas por producto\n", "titulo" );
    contiene( t, "**Transformación · 03_ventas_por_producto**", "_ dentro de una palabra sin escapar" );
    igual( "a \\_b\\_ c_d", RenderMarkdown.esc( "a _b_ c_d" ), "_ en el borde de una palabra se escapa" );
    contiene( t, "\n## Linaje de campos\n", "secciones como ##" );
    noContiene( t, "## Flujo del job", "la omitida no tiene seccion" );
    contiene( t, "Secciones omitidas: Flujo del job (Solo para jobs).", "omitida con motivo" );
    // mermaid: tres bloques con el mismo texto del modelo
    igual( 3, PruebaHtml.cuenta( t, "```mermaid\n" ), "tres bloques mermaid" );
    for ( Diagrama d : ModeloMuestra.diagramas( inf ) ) {
      contiene( t, "```mermaid\n" + d.mermaid.trim() + "\n```\n", "mismo texto Mermaid que el HTML: " + d.id );
      noContiene( d.mermaid, "@{", "sintaxis Mermaid 9+" );
    }
    // archivos
    File dir = RenderMarkdown.carpeta( md );
    igual( "03 ventas_archivos", dir.getName(), "carpeta de archivos" );
    archivo( new File( dir, "lienzo.png" ), 1000, "lienzo" );
    archivo( new File( dir, "logo.png" ), 100, "logo" );
    contiene( t, "](03%20ventas_archivos/lienzo.png)", "enlace relativo con el espacio codificado" );
    for ( File f : r.archivos ) {
      cierto( f.isFile(), "existe " + f );
    }
    igual( r.archivos.size(), 1 + dir.listFiles().length, "Resultado lista el .md y cada archivo de la carpeta" );
    long total = 0;
    for ( File f : r.archivos ) {
      total += f.length();
    }
    igual( total, r.bytes, "Resultado.bytes" );
    // graficos: un .svg por grafico o, si GraficosSvg (WP6) falla, una tabla y un aviso
    int svgs = 0;
    for ( String id : new String[] { "profile-nulos", "metrics-barras", "metrics-gantt", "metrics-linea" } ) {
      if ( new File( dir, id + ".svg" ).isFile() ) {
        svgs++;
        contiene( t, "(03%20ventas_archivos/" + id + ".svg)", "enlace al svg " + id );
        contiene( PruebaHtml.leer( new File( dir, id + ".svg" ) ), "<svg", "svg valido " + id );
      }
    }
    igual( 4 - svgs, r.avisos.size(), "un aviso por grafico sin dibujar" );
    // avisos y pie
    contiene( t, "> [!WARNING]\n> **Parcial.** 1 step sin resolver", "parcial como WARNING" );
    contiene( t, "> [!CAUTION]\n> **Error.** Tiempo agotado", "error como CAUTION" );
    contiene( t, "> [!NOTE]\n> Sin consultar la base", "info como NOTE" );
    cierto( t.endsWith( "\n---\n\n*Generado con Pentaho Live · IDC Studios*\n" ), "linea final" );
    contiene( t, "*Texto redactado con asistencia de IA*", "rotulo de IA" );
    contiene( t, "**Conclusión.** El cuello de botella", "negrita de la narrativa" );
    contiene( t, "- Conviene activar *lazy conversion*.", "vineta con cursiva" );
    igual( Formato.MARKDOWN, new RenderMarkdown().formato(), "formato" );
  }

  public void pruebaTablasGfm() throws Exception {
    File md = new File( carpeta, "tablas.md" );
    new RenderMarkdown().escribir( ModeloMuestra.crear(), ModeloMuestra.tema(), md, Progreso.NULO );
    List<List<String>> tablas = tablas( PruebaHtml.leer( md ) );
    cierto( tablas.size() >= 10, "tablas encontradas: " + tablas.size() );
    for ( List<String> tabla : tablas ) {
      int columnas = barras( tabla.get( 0 ) );
      cierto( tabla.size() >= 2 && tabla.get( 1 ).matches( "\\|( :?---:? \\|)+" ), "separador GFM: " + tabla );
      for ( String fila : tabla ) {
        igual( columnas, barras( fila ), "misma cantidad de columnas en " + fila );
      }
    }
    String t = PruebaHtml.leer( md );
    contiene( t, "| nota\\|con\\|barras |", "| escapado en la celda" );
    contiene( t, "| --- | --- | ---: | ---: | --- | --- |", "alineacion de la tabla" );
    contiene( t, "| total = suma(importe)<br>filas = cuenta() |", "salto de linea en celda" );
    contiene( t, "| Step más lento | Leer ventas · 1,7 s (Atención) |", "indicador con detalle y nivel" );
  }

  public void pruebaEscapes() throws Exception {
    Informe inf = ModeloMuestra.crear();
    Parrafo p = new Parrafo( "<script>alert(1)</script> $a$ #etiqueta ==resaltado== %%oculto%% [x](y)\n"
      + "1. no es lista\n+ tampoco\n---" );
    p.id = "p-raro";
    inf.seccion( "summary" ).con( p );
    File md = new File( carpeta, "escapes.md" );
    new RenderMarkdown().escribir( inf, ModeloMuestra.tema(), md, Progreso.NULO );
    String t = PruebaHtml.leer( md );
    noContiene( t, ModeloMuestra.STEP_RARO, "el nombre raro nunca va crudo" );
    noContiene( t, "<b>", "sin HTML del usuario" );
    noContiene( t, "<script>", "sin script" );
    contiene( t, "\\<b\\>\\&\"x", "nombre raro escapado" );
    contiene( t, "\\<script\\>alert(1)\\</script\\> \\$a\\$ \\#etiqueta \\=\\=resaltado\\=\\= \\%\\%oculto\\%\\% "
      + "\\[x\\](y)  \n1\\. no es lista  \n\\+ tampoco  \n\\---\n", "escapes de Obsidian y GitHub" );
    contiene( t, "\\${Internal.Entry.Current.Directory}/db/dw.sqlite", "variable de Kettle con $ escapado" );
  }

  public void pruebaEstable() throws Exception {
    File md = new File( carpeta, "estable.md" );
    Resultado a = new RenderMarkdown().escribir( ModeloMuestra.crear(), ModeloMuestra.tema(), md, Progreso.NULO );
    String t1 = PruebaHtml.leer( md );
    Resultado b = new RenderMarkdown().escribir( ModeloMuestra.crear(), ModeloMuestra.tema(), md, Progreso.NULO );
    igual( t1, PruebaHtml.leer( md ), "regenerar da el mismo texto" );
    igual( nombres( a ), nombres( b ), "mismos nombres de archivo" );
    for ( File f : b.archivos ) {
      noContiene( f.getName(), "-2.", "sin sufijos al regenerar" );
    }
    noContiene( t1, "http://", "sin URLs" );
    noContiene( t1, "https://", "sin URLs" );
  }

  public void pruebaIdioma() throws Exception {
    Informe inf = ModeloMuestra.crear();
    inf.rotulos.clear();
    inf.idioma = "en";
    File md = new File( carpeta, "en.md" );
    new RenderMarkdown().escribir( inf, null, md, Progreso.NULO );
    String t = PruebaHtml.leer( md );
    cierto( t.endsWith( "*Generated with Pentaho Live · IDC Studios*\n" ), "pie en ingles" );
    contiene( t, "| Indicator | Value |", "indicadores en ingles" );
    contiene( t, "*Generated on October 20, 2026, 14:32*", "fecha en ingles" );
  }

  // ---------------------------------------------------------------- ayudas

  /** Bloques de lineas que empiezan con "|" (fuera de los bloques de codigo). */
  private static List<List<String>> tablas( String md ) {
    List<List<String>> l = new ArrayList<>();
    List<String> actual = null;
    boolean codigo = false;
    for ( String linea : md.split( "\n" ) ) {
      if ( linea.startsWith( "```" ) ) {
        codigo = !codigo;
      }
      if ( !codigo && linea.startsWith( "|" ) ) {
        if ( actual == null ) {
          actual = new ArrayList<>();
          l.add( actual );
        }
        actual.add( linea );
      } else {
        actual = null;
      }
    }
    return l;
  }

  /** Cantidad de | sin escapar. */
  private static int barras( String fila ) {
    int n = 0;
    for ( int i = 0; i < fila.length(); i++ ) {
      if ( fila.charAt( i ) == '|' && ( i == 0 || fila.charAt( i - 1 ) != '\\' ) ) {
        n++;
      }
    }
    return n;
  }

  private static List<String> nombres( Resultado r ) {
    List<String> l = new ArrayList<>();
    for ( File f : r.archivos ) {
      l.add( f.getName() );
    }
    return l;
  }
}
