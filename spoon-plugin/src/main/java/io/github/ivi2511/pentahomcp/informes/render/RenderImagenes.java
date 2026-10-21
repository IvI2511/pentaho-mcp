package io.github.ivi2511.pentahomcp.informes.render;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Renderizador;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.diagramas.Dibujo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Objetivo;
import io.github.ivi2511.pentahomcp.informes.graficos.GraficosSvg;
import io.github.ivi2511.pentahomcp.informes.graficos.SvgAPng;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * «Imagenes»: una carpeta {@code <base>_imagenes\} con cada figura del informe lista para pegar en una
 * presentacion o un documento:
 * <ul>
 * <li>el lienzo de Spoon (el PNG del modelo, a escala 2);</li>
 * <li>cada diagrama de flujo, job o linaje como lo dibuja el motor propio para una diapositiva 16:9 (uno por
 * parte si se partio): PNG al doble y SVG;</li>
 * <li>cada diagrama Mermaid (el modelo entidad-relacion) como PNG (PrerenderMermaid.png, escala 2) y como SVG
 * estatico (PrerenderMermaid.svg: sin foreignObject, se pega bien en Office);</li>
 * <li>cada grafico como SVG (GraficosSvg) y como PNG de {@value #ANCHO_PNG_GRAFICO} px (Batik);</li>
 * <li>las imagenes sueltas de las secciones (el logo de la portada no).</li>
 * </ul>
 * Nombres estables, ordenados y en el idioma del informe:
 * {@code 03_linaje-de-campos_linaje-hacia-cargar-ventas.png}. Al regenerar se sobrescriben; nunca se borra
 * nada de la carpeta.
 * <p>
 * El destino es la carpeta. {@link Resultado#archivos}: la carpeta primero y despues cada archivo (ver
 * {@link #archivos(Resultado)}). No conoce Kettle ni SWT. Corre en un worker.
 *
 * @dueño WP10
 */
public final class RenderImagenes implements Renderizador {

  /** Area de Rotulos ({@code textos/imagenes_*.properties}). */
  public static final String AREA = "imagenes";
  /** Clave de {@link Formato} (argumento formats de build_report). */
  public static final String CLAVE = "images";
  /** La carpeta por defecto es el nombre base del informe con este sufijo. */
  public static final String SUFIJO = "_imagenes";
  /** Ancho del PNG de un grafico: el doble del SVG de GraficosSvg. */
  public static final int ANCHO_PNG_GRAFICO = 2 * GraficosSvg.ANCHO;
  /** Largo maximo de un nombre de archivo (sin extension). */
  static final int MAX_NOMBRE = 80;

  private final Map<String, byte[]> pngMermaid;
  private final Map<String, String> svgMermaid;
  private RenderPptx.ProveedorSvg proveedorSvg;

  /**
   * @param pngMermaid id de Diagrama -> PNG (PrerenderMermaid.png); null = sin PNG de Mermaid.
   * @param svgMermaid id de Diagrama -> SVG (PrerenderMermaid.svg); null = sin SVG de Mermaid.
   */
  public RenderImagenes( Map<String, byte[]> pngMermaid, Map<String, String> svgMermaid ) {
    this.pngMermaid = pngMermaid == null ? Collections.<String, byte[]>emptyMap() : new HashMap<>( pngMermaid );
    this.svgMermaid = svgMermaid == null ? Collections.<String, String>emptyMap() : new HashMap<>( svgMermaid );
  }

  /** Cambia de donde salen los SVG de los graficos (pruebas). Devuelve this. */
  public RenderImagenes conSvgGraficos( RenderPptx.ProveedorSvg p ) {
    this.proveedorSvg = p;
    return this;
  }

  /** Formato.IMAGENES (se busca por clave). */
  @Override
  public Formato formato() {
    return Formato.IMAGENES;
  }

  /** "C:\x\informe" -> "C:\x\informe_imagenes" (la carpeta por defecto para un nombre base). */
  public static File carpeta( File base ) {
    return new File( base.getAbsoluteFile().getParentFile(), base.getName() + SUFIJO );
  }

  /** Los archivos escritos, sin la carpeta (que va primera en Resultado.archivos). */
  public static List<File> archivos( Resultado r ) {
    return r == null || r.archivos.size() <= 1 ? Collections.<File>emptyList() : r.archivos.subList( 1, r.archivos
      .size() );
  }

  @Override
  public Resultado escribir( Informe inf, Tema tema, File destino, Progreso p ) throws Exception {
    if ( inf == null || destino == null ) {
      throw new IllegalArgumentException( "Informe y destino son obligatorios" );
    }
    Progreso pr = p == null ? Progreso.NULO : p;
    Tema t = tema == null ? new Tema() : tema;
    final String idioma = Rotulos.normalizar( inf.idioma );
    Rotulos r = Rotulos.de( AREA, idioma );
    RenderPptx.ProveedorSvg svgs = proveedorSvg != null ? proveedorSvg
      : ( g, te, ancho ) -> GraficosSvg.svg( g, te, ancho, idioma );
    File dir = destino.getAbsoluteFile();
    if ( !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
      throw new IOException( "No se pudo crear la carpeta " + dir );
    }
    Resultado res = new Resultado().archivo( dir );
    Set<String> usados = new HashSet<>();
    int n = 0;
    int sinDibujo = 0;
    int soloSvg = 0;
    List<Seccion> vivas = RenderDocx.vivas( RenderDocx.cuerpo( inf ) );
    for ( int k = 0; k < vivas.size(); k++ ) {
      Seccion s = vivas.get( k );
      String titulo = !PptxDiapos.vacio( s.titulo ) ? s.titulo.trim() : SeccionId.titulo( s.id, idioma );
      pr.paso( r.f( "progreso", titulo ), k, vivas.size() );
      String sec = Html.idSeguro( titulo );
      for ( Bloque b : Html.lista( s.bloques ) ) {
        if ( pr.cancelado() ) {
          throw new CancellationException( "Imagenes" );
        }
        if ( b instanceof Diagrama ) {
          Diagrama d = (Diagrama) b;
          if ( d.imagen != null && d.imagen.datos != null && d.imagen.datos.length > 0 ) {
            String base = nombre( usados, ++n, sec, r.s( "lienzo" ) );
            escribir( res, new File( dir, base + extension( d.imagen.mime ) ), d.imagen.datos );
          }
          List<Dibujo> ds = Figuras.dibujos( d, Objetivo.DIAPOSITIVA, idioma );
          if ( ds != null ) {
            String base = nombre( usados, ++n, sec, que( b, r.s( "diagrama" ) ) );
            for ( Dibujo x : ds ) {
              String nb = ds.size() > 1 ? base + "-" + x.parte : base;
              byte[] png;
              try {
                png = x.png( 2 );
              } catch ( Throwable e ) {
                png = null;
              }
              if ( png != null && png.length > 0 ) {
                escribir( res, new File( dir, nb + ".png" ), png );
              } else {
                soloSvg++;
              }
              escribir( res, new File( dir, nb + ".svg" ), suelto( x.svgEscalado() ).getBytes(
                StandardCharsets.UTF_8 ) );
            }
            continue;
          }
          if ( PptxDiapos.vacio( d.mermaid ) ) {
            continue;
          }
          byte[] png = d.id == null ? null : pngMermaid.get( d.id );
          String svg = d.id == null ? null : svgMermaid.get( d.id );
          boolean hayPng = png != null && png.length > 0;
          boolean haySvg = svg != null && svg.contains( "<svg" );
          if ( !hayPng && !haySvg ) {
            sinDibujo++;
            continue;
          }
          String base = nombre( usados, ++n, sec, que( b, r.s( "diagrama" ) ) );
          if ( hayPng ) {
            escribir( res, new File( dir, base + ".png" ), png );
          }
          if ( haySvg ) {
            escribir( res, new File( dir, base + ".svg" ), suelto( svg ).getBytes( StandardCharsets.UTF_8 ) );
          }
        } else if ( b instanceof Grafico ) {
          String svg;
          try {
            svg = svgs.svg( (Grafico) b, t, GraficosSvg.ANCHO );
          } catch ( Exception e ) {
            svg = null;
          }
          if ( svg == null || !svg.contains( "<svg" ) ) {
            sinDibujo++;
            continue;
          }
          String base = nombre( usados, ++n, sec, que( b, r.s( "grafico" ) ) );
          escribir( res, new File( dir, base + ".svg" ), suelto( svg ).getBytes( StandardCharsets.UTF_8 ) );
          byte[] png;
          try {
            png = SvgAPng.png( svg, ANCHO_PNG_GRAFICO );
          } catch ( Throwable e ) {
            png = null;
          }
          if ( png != null && png.length > 0 ) {
            escribir( res, new File( dir, base + ".png" ), png );
          } else {
            soloSvg++; // queda el SVG
          }
        } else if ( b instanceof Imagen ) {
          Imagen im = (Imagen) b;
          if ( im.datos != null && im.datos.length > 0 ) {
            String base = nombre( usados, ++n, sec, que( b, r.s( "imagen" ) ) );
            escribir( res, new File( dir, base + extension( im.mime ) ), im.datos );
          }
        }
      }
    }
    if ( sinDibujo > 0 ) {
      res.avisos.add( r.f( "aviso_sin_dibujo", sinDibujo ) );
    }
    if ( soloSvg > 0 ) {
      res.avisos.add( r.f( "aviso_solo_svg", soloSvg ) );
    }
    if ( res.archivos.size() <= 1 ) {
      res.avisos.add( r.s( "aviso_vacia" ) );
    }
    return res;
  }

  /** El titulo del bloque como parte del nombre; si no tiene, el texto dado ("diagrama", "grafico"...). */
  private static String que( Bloque b, String porDefecto ) {
    return Html.idSeguro( !PptxDiapos.vacio( b.titulo ) ? b.titulo.trim() : porDefecto );
  }

  /** "03_linaje-de-campos_linaje-hacia-cargar-ventas": hasta {@value #MAX_NOMBRE} caracteres y sin repetir. */
  static String nombre( Set<String> usados, int n, String seccion, String que ) {
    String b = String.format( Locale.ROOT, "%02d_%s_%s", n, seccion, que );
    if ( b.length() > MAX_NOMBRE ) {
      b = b.substring( 0, MAX_NOMBRE ).replaceAll( "[-_]+$", "" );
    }
    String x = b;
    for ( int i = 2; !usados.add( x.toLowerCase( Locale.ROOT ) ); i++ ) {
      x = b + "-" + i;
    }
    return x;
  }

  /**
   * SVG como archivo suelto: con declaracion XML y el xmlns de SVG (sin ellos Word, PowerPoint y los
   * visores no lo abren).
   */
  static String suelto( String svg ) {
    String s = svg.trim();
    int i = s.indexOf( "<svg" );
    if ( i > 0 && !s.startsWith( "<?xml" ) ) {
      s = s.substring( i ); // un comentario o espacio antes de la raiz
    }
    if ( !s.startsWith( "<?xml" ) ) {
      int fin = s.indexOf( '>' );
      if ( s.startsWith( "<svg" ) && fin > 0 && !XMLNS_SVG.matcher( s.substring( 0, fin ) ).find() ) {
        s = "<svg xmlns=\"http://www.w3.org/2000/svg\"" + s.substring( 4 );
      }
      s = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + s;
    }
    return s;
  }

  private static final java.util.regex.Pattern XMLNS_SVG = java.util.regex.Pattern.compile(
    "\\sxmlns\\s*=\\s*[\"']http://www\\.w3\\.org/2000/svg[\"']" );

  private static String extension( String mime ) {
    return mime != null && mime.toLowerCase( Locale.ROOT ).contains( "jp" ) ? ".jpg" : ".png";
  }

  private static void escribir( Resultado r, File f, byte[] datos ) throws IOException {
    Html.escribir( f, datos );
    r.archivo( f );
  }
}
