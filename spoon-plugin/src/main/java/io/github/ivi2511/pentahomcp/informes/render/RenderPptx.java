package io.github.ivi2511.pentahomcp.informes.render;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.poi.xslf.usermodel.XMLSlideShow;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Renderizador;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.graficos.GraficosSvg;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * PowerPoint 16:9 (960x540 pt) con Apache POI 4.1.1 XSLF (diseno §3.4). Las diapositivas las arma
 * {@link PptxDiapos}: portada, agenda y una o varias por seccion, con notas del orador, pie IDC y marca
 * de agua. Los diagramas Mermaid van como el PNG que llega por el constructor (PrerenderMermaid.png) o,
 * sin navegador, como su tabla alternativa; los graficos como SVG con PNG de respaldo (addSvgImage).
 * <p>
 * No conoce Kettle ni SWT. Corre en un worker. Escribe primero un ".tmp" al lado y despues lo mueve,
 * asi una exportacion cancelada o fallida no deja un .pptx a medias.
 *
 * @dueño WP9
 */
public final class RenderPptx implements Renderizador {

  /** Area de Rotulos de este renderizador ({@code textos/pptx_*.properties}). */
  public static final String AREA = "pptx";

  /** Lo que da el SVG de un grafico. Por defecto {@link GraficosSvg#svg} en el idioma del informe; las pruebas lo cambian. */
  public interface ProveedorSvg {
    String svg( Grafico g, Tema t, int anchoPx ) throws Exception;
  }

  private final Map<String, byte[]> pngMermaid;
  /** true = el detalle de consulta va en diapositivas de anexo al final (Tema.salida.pptxAnexo). */
  private boolean conAnexo;
  /** null = GraficosSvg con Informe.idioma (numeros y leyendas en el idioma del informe, no en el del tema). */
  private ProveedorSvg proveedorSvg;

  /**
   * @param pngMermaid id de Diagrama -> PNG (PrerenderMermaid.png); null o sin la clave = se usa la
   *                   tabla alternativa. render no ve navegador: los PNG llegan por aca.
   */
  public RenderPptx( Map<String, byte[]> pngMermaid ) {
    this.pngMermaid = pngMermaid == null ? Collections.<String, byte[]>emptyMap()
      : Collections.unmodifiableMap( new HashMap<>( pngMermaid ) );
  }

  /** Cambia de donde salen los SVG de los graficos (pruebas, o un SVG ya calculado). Devuelve this. */
  public RenderPptx conSvgGraficos( ProveedorSvg p ) {
    this.proveedorSvg = p;
    return this;
  }

  /** Con anexo: las tablas de detalle y lo que no entra van al final, tras una diapositiva «Anexo». */
  public RenderPptx conAnexo( boolean si ) {
    this.conAnexo = si;
    return this;
  }

  @Override
  public Formato formato() {
    return Formato.PPTX;
  }

  /**
   * Chequeo barato de que POI puede escribir PPTX en este proceso (diseno §3.4): XmlBeans tiene que
   * ser la 3.1 de lib\ de PDI. Sirve para que el panel deshabilite el formato con un motivo.
   *
   * @return null si se puede exportar; si no, el motivo traducido.
   */
  public static String problema( String idioma ) {
    Rotulos r = Rotulos.de( AREA, idioma );
    String version;
    try {
      Class.forName( "org.apache.poi.xslf.usermodel.XMLSlideShow", false, RenderPptx.class.getClassLoader() );
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

    // El loader del plugin durante toda la exportacion (diseno §3.4: evita el JAXBException de POI).
    Thread hilo = Thread.currentThread();
    ClassLoader antes = hilo.getContextClassLoader();
    hilo.setContextClassLoader( RenderPptx.class.getClassLoader() );
    try ( XMLSlideShow ppt = new XMLSlideShow() ) {
      final String idiomaGraficos = idioma;
      ProveedorSvg svgs = proveedorSvg != null ? proveedorSvg
        : ( g, te, ancho ) -> GraficosSvg.svg( g, te, ancho, idiomaGraficos );
      PptxDiapos diapos = new PptxDiapos( ppt, inf, t, pngMermaid, svgs, pr, conAnexo );
      diapos.armar();
      PptxDiapos.cancelar( pr );
      pr.paso( Rotulos.de( AREA, idioma ).s( "progreso_escribir" ), 0, 0 );
      try ( OutputStream os = new FileOutputStream( tmp ) ) {
        ppt.write( os );
      }
      mover( tmp, fin );
      Resultado r = new Resultado().archivo( fin );
      r.avisos.addAll( diapos.avisos() );
      return r;
    } finally {
      if ( tmp.exists() && !tmp.delete() ) {
        tmp.deleteOnExit();
      }
      hilo.setContextClassLoader( antes );
    }
  }

  private static void mover( File desde, File hacia ) throws IOException {
    try {
      Files.move( desde.toPath(), hacia.toPath(), StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE );
    } catch ( AtomicMoveNotSupportedException e ) {
      Files.move( desde.toPath(), hacia.toPath(), StandardCopyOption.REPLACE_EXISTING );
    }
  }
}
