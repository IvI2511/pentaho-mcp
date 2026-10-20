package io.github.ivi2511.pentahomcp.informes.render;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TabStop.TabStopType;
import org.apache.poi.sl.usermodel.TableCell.BorderEdge;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTRegularTextRun;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextField;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextLineBreak;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraph;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTransform2D;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;

import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
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
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;
import io.github.ivi2511.pentahomcp.informes.tema.SimboloIdc;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Arma las diapositivas de un {@link Informe} sobre un {@link XMLSlideShow} vacio (diseno §3.4):
 * <ol>
 * <li>Portada (plantilla TITLE): banda del color primario, titulo, subtitulo, documento, autor,
 * institucion, fecha y logo.</li>
 * <li>Agenda (TITLE_ONLY y un cuadro de texto): las secciones con el numero de su primera diapositiva.</li>
 * <li>Una o varias diapositivas por seccion (TITLE_ONLY). Los bloques se apilan de arriba abajo y,
 * cuando no entran, siguen en otra diapositiva con «(cont.)». Tablas de 10 filas como mucho por
 * diapositiva, con la cabecera repetida.</li>
 * </ol>
 * Titulos en placeholders reales (vista de esquema). Notas del orador con el resumen de la seccion,
 * el motivo si quedo parcial o con error, y la narrativa. Pie IDC con el numero de diapositiva (campo
 * {@code slidenum}) en todas menos la portada; marca de agua al 8 % en todas.
 * <p>
 * Las secciones «omitidas» no salen. Coordenadas en puntos (la diapositiva mide 960x540).
 *
 * @dueño WP9
 */
final class PptxDiapos {

  // ------------------------------------------------------------------ geometria (puntos)
  static final double ANCHO = 960;
  static final double ALTO = 540;
  static final double X0 = 40;
  static final double ANCHO_UTIL = ANCHO - 2 * X0;
  static final double Y_CONTENIDO = 80;
  static final double Y_LIMITE = 494;
  static final double Y_PIE = 508;
  static final double ESPACIO = 10;
  static final int FILAS_POR_DIAPO = 10;
  /** Ancho medio de un caracter en em (Segoe UI), con margen para no quedarse corto. */
  static final double ANCHO_CARACTER = 0.55;
  /** Alto de linea de Segoe UI (ascendente + descendente) en em; medido con LibreOffice. */
  static final double INTERLINEA = 1.32;
  static final double ALTO_SUBTITULO = 22;
  static final double ALTO_TARJETA = 76;
  static final double ANCHO_MARCA = 113; // unos 40 mm
  static final String FUENTE = "Segoe UI";
  /** Escala de los PNG de Mermaid (PrerenderMermaid.ESCALA: --force-device-scale-factor=2). */
  static final float ESCALA_MERMAID = 2f;
  /** Por debajo de esto (respecto del tamano natural) el texto del diagrama queda chico: se avisa. */
  static final double ESCALA_LEGIBLE = 0.55;

  // ------------------------------------------------------------------ colores fijos (niveles)
  static final Color OK = new Color( 0x2F, 0x85, 0x5A );
  static final Color WARN = new Color( 0x97, 0x5A, 0x16 );
  static final Color ERROR = new Color( 0xC5, 0x30, 0x30 );

  private static final Pattern MARCAS = Pattern.compile( "\\*\\*(.+?)\\*\\*|\\*(\\S(?:.*?\\S)?)\\*" );
  private static final Pattern SVG_ANCHO = Pattern.compile( "\\swidth\\s*=\\s*[\"']([0-9.]+)(px)?[\"']" );
  private static final Pattern SVG_ALTO = Pattern.compile( "\\sheight\\s*=\\s*[\"']([0-9.]+)(px)?[\"']" );
  private static final Pattern SVG_VIEWBOX = Pattern.compile(
    "viewBox\\s*=\\s*[\"']\\s*[-0-9.]+[ ,]+[-0-9.]+[ ,]+([0-9.]+)[ ,]+([0-9.]+)\\s*[\"']" );

  private final XMLSlideShow ppt;
  private final Informe inf;
  private final Tema tema;
  private final Map<String, byte[]> pngMermaid;
  private final RenderPptx.ProveedorSvg proveedorSvg;
  private final Progreso progreso;
  private final String idioma;
  private final Rotulos r;
  private final Locale locale;

  private final Color primario;
  private final Color acento;
  private final Color texto;
  private final Color tenue;
  private final Color borde;
  private final Color cebra;
  private final Color fondoTarjeta;

  private final XSLFSlideLayout layTitulo;
  private final XSLFSlideLayout laySoloTitulo;

  private XSLFPictureData marcaAgua;
  private XSLFPictureData simboloPie;

  /** Diapositiva actual y posicion vertical libre. */
  private XSLFSlide diapo;
  private double y;
  private String tituloSeccion;
  /** Titulo de bloque que todavia no se dibujo (va pegado al bloque, nunca solo al pie). */
  private String subtituloPendiente;

  private final List<Object[]> agenda = new ArrayList<>();
  private final List<String> avisos = new ArrayList<>();
  private int diagramasComoTabla;
  private int graficosComoTabla;

  PptxDiapos( XMLSlideShow ppt, Informe inf, Tema tema, Map<String, byte[]> pngMermaid,
              RenderPptx.ProveedorSvg proveedorSvg, Progreso progreso ) {
    this.ppt = ppt;
    this.inf = inf;
    this.tema = tema;
    this.pngMermaid = pngMermaid;
    this.proveedorSvg = proveedorSvg;
    this.progreso = progreso;
    this.idioma = Rotulos.normalizar( inf.idioma );
    this.r = Rotulos.de( RenderPptx.AREA, idioma );
    this.locale = Rotulos.locale( idioma );

    Tema.Colores c = tema.colores == null ? new Tema.Colores() : tema.colores;
    this.primario = color( c.primario, Tema.PRIMARIO );
    this.acento = color( c.acento, Tema.ACENTO );
    this.texto = color( Tema.TEXTO, Tema.TEXTO );
    this.tenue = color( Tema.TEXTO_TENUE, Tema.TEXTO_TENUE );
    this.borde = color( Tema.BORDE, Tema.BORDE );
    this.cebra = claro( primario, 0.07 );
    this.fondoTarjeta = claro( primario, 0.05 );

    double anchoPlantilla = ppt.getPageSize().getWidth();
    ppt.setPageSize( new Dimension( (int) ANCHO, (int) ALTO ) );
    XSLFSlideMaster master = ppt.getSlideMasters().get( 0 );
    if ( anchoPlantilla > 0 ) {
      plantillas16x9( master, ANCHO / anchoPlantilla );
    }
    this.layTitulo = master.getLayout( SlideLayout.TITLE );
    this.laySoloTitulo = master.getLayout( SlideLayout.TITLE_ONLY );
  }

  /** Avisos traducidos para el Resultado (diagramas o graficos que fueron como tabla). */
  List<String> avisos() {
    return avisos;
  }

  /** Lanza CancellationException si el usuario cancelo. */
  static void cancelar( Progreso p ) {
    if ( p != null && p.cancelado() ) {
      throw new CancellationException( "PowerPoint" );
    }
  }

  // ================================================================== armado

  void armar() {
    propiedades();
    simbolos();

    List<Seccion> secciones = new ArrayList<>();
    Seccion portada = null;
    for ( Seccion s : inf.secciones == null ? new ArrayList<Seccion>() : inf.secciones ) {
      if ( s == null || Seccion.OMITIDA.equals( s.estado ) ) {
        continue;
      }
      if ( SeccionId.PORTADA.equals( s.id ) ) {
        portada = s;
      } else {
        secciones.add( s );
      }
    }

    cancelar( progreso );
    portada( portada );
    XSLFSlide diapoAgenda = secciones.isEmpty() ? null : nuevaDiapo( tituloAgenda(), laySoloTitulo );

    int i = 0;
    for ( Seccion s : secciones ) {
      cancelar( progreso );
      String titulo = tituloDe( s );
      progreso.paso( r.f( "progreso", titulo ), i++, secciones.size() );
      seccion( s, titulo );
    }
    if ( diapoAgenda != null ) {
      llenarAgenda( diapoAgenda );
    }
    if ( diagramasComoTabla > 0 ) {
      avisos.add( r.f( "aviso_diagramas", diagramasComoTabla ) );
    }
    if ( graficosComoTabla > 0 ) {
      avisos.add( r.f( "aviso_graficos", graficosComoTabla ) );
    }
    fijarIdioma();
  }

  // ================================================================== idioma del texto

  /**
   * Marca todo el texto (cuadros, tablas, notas, campos y finales de parrafo) con el idioma del informe
   * (es-ES, pt-BR, en-US). Sin esto PowerPoint revisaba la ortografia en ingles y subrayaba en rojo todo
   * el castellano y el portugues.
   */
  private void fijarIdioma() {
    String tag = locale.toLanguageTag();
    try {
      ppt.getProperties().getCoreProperties().getUnderlyingProperties().setLanguageProperty( tag );
    } catch ( RuntimeException e ) {
      // la propiedad es un extra
    }
    for ( XSLFSlide d : ppt.getSlides() ) {
      idioma( d.getShapes(), tag );
      XSLFNotes n = d.getNotes();
      if ( n != null ) {
        idioma( n.getShapes(), tag );
      }
    }
  }

  private static void idioma( List<XSLFShape> formas, String tag ) {
    for ( XSLFShape sh : formas ) {
      if ( sh instanceof XSLFGroupShape ) {
        idioma( ( (XSLFGroupShape) sh ).getShapes(), tag );
      } else if ( sh instanceof XSLFTable ) {
        for ( XSLFTableRow fila : ( (XSLFTable) sh ).getRows() ) {
          for ( XSLFTableCell c : fila.getCells() ) {
            idioma( c, tag );
          }
        }
      } else if ( sh instanceof XSLFTextShape ) {
        idioma( (XSLFTextShape) sh, tag );
      }
    }
  }

  private static void idioma( XSLFTextShape t, String tag ) {
    for ( XSLFTextParagraph p : t.getTextParagraphs() ) {
      CTTextParagraph x = p.getXmlObject();
      for ( CTRegularTextRun run : x.getRArray() ) {
        ( run.isSetRPr() ? run.getRPr() : run.addNewRPr() ).setLang( tag );
      }
      for ( CTTextField f : x.getFldArray() ) {
        ( f.isSetRPr() ? f.getRPr() : f.addNewRPr() ).setLang( tag );
      }
      for ( CTTextLineBreak br : x.getBrArray() ) {
        ( br.isSetRPr() ? br.getRPr() : br.addNewRPr() ).setLang( tag );
      }
      if ( x.isSetEndParaRPr() ) {
        x.getEndParaRPr().setLang( tag );
      }
    }
  }

  private void propiedades() {
    try {
      POIXMLProperties.CoreProperties cp = ppt.getProperties().getCoreProperties();
      cp.setTitle( limpio( inf.titulo ) );
      if ( inf.subtitulo != null ) {
        cp.setDescription( limpio( inf.subtitulo ) );
      }
      String autor = tema.identidad == null ? null : tema.identidad.autor;
      cp.setCreator( vacio( autor ) ? Tema.PIE : limpio( autor ) );
    } catch ( RuntimeException e ) {
      // las propiedades son un extra
    }
  }

  /** El simbolo IDC para el pie y la marca de agua (WP6). Si no esta, el pie va solo con texto. */
  private void simbolos() {
    try {
      byte[] pie = SimboloIdc.png( 64, SimboloIdc.CLARO, 1f );
      if ( pie != null && pie.length > 0 ) {
        simboloPie = ppt.addPicture( pie, PictureType.PNG );
      }
    } catch ( Throwable t ) {
      simboloPie = null;
    }
    if ( !tema.marcaDeAgua ) {
      return;
    }
    try {
      byte[] marca = SimboloIdc.png( (int) ( ANCHO_MARCA * 4 ), SimboloIdc.CLARO, 0.08f );
      if ( marca != null && marca.length > 0 ) {
        marcaAgua = ppt.addPicture( marca, PictureType.PNG );
      }
    } catch ( Throwable t ) {
      marcaAgua = null;
    }
  }

  // ================================================================== portada

  private void portada( Seccion s ) {
    XSLFSlide d = ppt.createSlide( layTitulo );
    diapo = d;
    marcaDeAgua( d );
    // La banda del color primario son los propios placeholders de titulo y subtitulo con relleno: un
    // rectangulo aparte quedaria encima del titulo (va despues en el arbol de formas).
    XSLFTextShape[] ph = d.getPlaceholders();
    String titulo = !vacio( inf.titulo ) ? inf.titulo : !vacio( inf.nombreDoc ) ? inf.nombreDoc : "Pentaho Live";
    boolean conSub = ph.length > 1 && !vacio( inf.subtitulo );
    if ( ph.length > 1 && !conSub ) {
      d.removeShape( ph[ 1 ] );
    }
    if ( ph.length > 0 ) {
      XSLFTextShape t = ph[ 0 ];
      ancla( t, 0, 0, ANCHO, conSub ? 196 : 250 );
      estiloMarcador( t, titulo, titulo.length() > 50 ? 30 : 36, Color.WHITE, true, VerticalAlignment.BOTTOM );
      banda( t, conSub ? 6 : 24 );
    }
    if ( conSub ) {
      XSLFTextShape st = ph[ 1 ];
      ancla( st, 0, 195, ANCHO, 55 ); // 1 pt encima del titulo: sin costura entre los dos rellenos
      estiloMarcador( st, inf.subtitulo, 16, claro( primario, 0.18 ), false, VerticalAlignment.TOP );
      banda( st, 0 );
    }
    rect( d, 0, 250, ANCHO, 4, acento, null );

    // datos: los de la seccion cover si estan; si no, los del tema
    List<String> lineas = new ArrayList<>();
    Imagen logo = null;
    if ( s != null && s.bloques != null ) {
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Imagen && logo == null && ( (Imagen) b ).datos != null ) {
          logo = (Imagen) b;
        } else if ( b instanceof Lista && ( (Lista) b ).items != null ) {
          for ( String it : ( (Lista) b ).items ) {
            if ( !vacio( it ) ) {
              lineas.add( it );
            }
          }
        } else if ( b instanceof Parrafo && !vacio( ( (Parrafo) b ).texto ) ) {
          lineas.addAll( Arrays.asList( ( (Parrafo) b ).texto.split( "\r?\n" ) ) );
        }
      }
    }
    if ( lineas.isEmpty() ) {
      String doc = !vacio( inf.archivo ) ? inf.archivo : inf.nombreDoc;
      if ( !vacio( doc ) ) {
        lineas.add( r.f( "documento", doc ) );
      }
      Tema.Identidad id = tema.identidad;
      if ( id != null ) {
        agregarSi( lineas, "autor", id.autor );
        agregarSi( lineas, "institucion", id.institucion );
        agregarSi( lineas, "curso", id.curso );
        agregarSi( lineas, "contacto", id.contacto );
      }
    }
    String fecha = fecha( inf.generado );
    if ( fecha != null ) {
      lineas.add( r.f( "generado", fecha ) );
    }
    if ( !lineas.isEmpty() ) {
      double size = lineas.size() > 7 ? 12 : 14;
      XSLFTextBox tb = caja( d, 60, 278, 600, Math.min( 200, lineas.size() * size * 1.6 + 8 ) );
      for ( String l : lineas ) {
        XSLFTextParagraph p = parrafo( tb );
        p.setSpaceAfter( -4.0 );
        String linea = limpio( l );
        int dos = linea.indexOf( ": " );
        if ( dos > 0 && dos < 30 ) {
          run( p, linea.substring( 0, dos + 1 ) + " ", size, tenue, false, false );
          run( p, linea.substring( dos + 2 ), size, texto, false, false );
        } else {
          run( p, linea, size, texto, false, false );
        }
      }
    }

    byte[] datosLogo = logo != null ? logo.datos : tema.logoDatos;
    String mimeLogo = logo != null ? logo.mime : tema.logoMime;
    if ( datosLogo != null && datosLogo.length > 0 ) {
      try {
        int[] wh = logo != null && logo.ancho > 0 && logo.alto > 0 ? new int[] { logo.ancho, logo.alto }
          : medir( datosLogo );
        double maxW = 200;
        double maxH = 110;
        double esc = Math.min( maxW / wh[ 0 ], maxH / wh[ 1 ] );
        esc = Math.min( esc, 1.0 );
        double w = wh[ 0 ] * esc;
        double h = wh[ 1 ] * esc;
        XSLFPictureData pd = ppt.addPicture( datosLogo, tipoImagen( mimeLogo ) );
        XSLFPictureShape ps = d.createPicture( pd );
        ps.setAnchor( new Rectangle2D.Double( ANCHO - 60 - w, 282, w, h ) );
      } catch ( Exception e ) {
        // un logo roto no frena el informe
      }
    }

    if ( inf.avisos != null && !inf.avisos.isEmpty() ) {
      int n = Math.min( 3, inf.avisos.size() );
      XSLFTextBox tb = caja( d, 60, 470, 760, n * 14 + 6 );
      for ( int i = 0; i < n; i++ ) {
        XSLFTextParagraph p = parrafo( tb );
        run( p, limpio( inf.avisos.get( i ) ), 10, WARN, false, true );
      }
    }

    StringBuilder notas = new StringBuilder();
    if ( s != null && !vacio( s.resumen ) ) {
      notas.append( s.resumen );
    } else if ( !vacio( inf.subtitulo ) ) {
      notas.append( inf.subtitulo );
    }
    if ( inf.avisos != null && !inf.avisos.isEmpty() ) {
      if ( notas.length() > 0 ) {
        notas.append( "\n\n" );
      }
      notas.append( r.s( "avisos" ) ).append( ':' );
      for ( String a : inf.avisos ) {
        notas.append( "\n- " ).append( a );
      }
    }
    notas( d, notas.toString() );
  }

  private void agregarSi( List<String> l, String clave, String valor ) {
    if ( !vacio( valor ) ) {
      l.add( r.f( clave, valor ) );
    }
  }

  // ================================================================== agenda

  private String tituloAgenda() {
    String c = inf.rotulos == null ? null : inf.rotulos.get( "contents" );
    return vacio( c ) ? r.s( "agenda" ) : c;
  }

  private void llenarAgenda( XSLFSlide d ) {
    // Cuadro de texto y no el placeholder de contenido: LibreOffice ignora las tabulaciones propias
    // dentro de un placeholder (probado), y el numero de diapositiva va alineado con una.
    XSLFTextBox cuerpo = caja( d, X0 + 20, Y_CONTENIDO + 10, 620, Y_LIMITE - Y_CONTENIDO - 10 );
    double size = agenda.size() <= 9 ? 18 : agenda.size() <= 12 ? 15 : 12;
    int n = 1;
    for ( Object[] e : agenda ) {
      // sin marL ni indent: con ellos LibreOffice ignora la tabulacion derecha (probado)
      XSLFTextParagraph p = cuerpo.addNewTextParagraph();
      p.setSpaceAfter( -( size * 0.45 ) );
      p.setTextAlign( TextAlign.LEFT );
      p.addTabStops( 34, TabStopType.LEFT );
      p.addTabStops( 600, TabStopType.RIGHT );
      run( p, String.valueOf( n++ ), size, acento, true, false );
      run( p, "\t" + limpio( (String) e[ 0 ] ), size, texto, false, false );
      run( p, "\t" + e[ 1 ], size, tenue, false, false );
    }
  }

  // ================================================================== secciones

  private String tituloDe( Seccion s ) {
    if ( !vacio( s.titulo ) ) {
      return s.titulo;
    }
    return s.id == null ? "" : SeccionId.titulo( s.id, idioma );
  }

  private void seccion( Seccion s, String titulo ) {
    tituloSeccion = titulo;
    XSLFSlide primera = nuevaDiapo( titulo, laySoloTitulo );
    agenda.add( new Object[] { titulo, ppt.getSlides().indexOf( primera ) + 1 } );

    if ( Seccion.PARCIAL.equals( s.estado ) || Seccion.ERROR.equals( s.estado ) ) {
      boolean error = Seccion.ERROR.equals( s.estado );
      String linea = vacio( s.motivo ) ? r.s( error ? "estado_error_solo" : "estado_parcial_solo" )
        : r.f( error ? "estado_error" : "estado_parcial", s.motivo );
      double alto = altoTexto( Arrays.asList( linea ), 12, ANCHO_UTIL, 0 );
      XSLFTextBox tb = caja( diapo, X0, y - 4, ANCHO_UTIL, alto );
      run( parrafo( tb ), limpio( linea ), 12, error ? ERROR : WARN, false, true );
      y += alto + 4;
    }

    StringBuilder narrativa = new StringBuilder();
    List<Bloque> bs = new ArrayList<>();
    for ( Bloque b : s.bloques == null ? new ArrayList<Bloque>() : s.bloques ) {
      if ( b != null ) {
        bs.add( b );
      }
    }
    boolean algo = !bs.isEmpty();
    for ( int k = 0; k < bs.size(); k++ ) {
      Bloque b = bs.get( k );
      // un grafico que no entra en lo que queda pasaria a otra diapositiva y dejaria esta medio vacia: si
      // la tabla que le sigue entra entera, va primero (como una figura flotante)
      if ( k + 1 < bs.size() && b instanceof Grafico && bs.get( k + 1 ) instanceof Tabla && !vacia() ) {
        double necesita = altoMinimoGrafico( (Grafico) b );
        double tabla = altoTablaEntera( (Tabla) bs.get( k + 1 ) );
        if ( necesita > 0 && y + necesita > Y_LIMITE && tabla > 0 && y + tabla <= Y_LIMITE ) {
          bs.set( k, bs.get( k + 1 ) );
          bs.set( k + 1, b );
          b = bs.get( k );
        }
      }
      bloque( b );
      if ( b.ia && b instanceof Parrafo && !vacio( ( (Parrafo) b ).texto ) ) {
        narrativa.append( "\n\n" ).append( ( (Parrafo) b ).texto );
      }
    }
    if ( !algo ) {
      String t = !vacio( s.resumen ) ? s.resumen : r.s( "sin_contenido" );
      texto( Arrays.asList( t ), 16, false );
    }

    StringBuilder notas = new StringBuilder();
    if ( !vacio( s.resumen ) ) {
      notas.append( s.resumen );
    }
    if ( !Seccion.OK.equals( s.estado ) && !vacio( s.motivo ) ) {
      if ( notas.length() > 0 ) {
        notas.append( "\n\n" );
      }
      notas.append( r.f( Seccion.ERROR.equals( s.estado ) ? "estado_error" : "estado_parcial", s.motivo ) );
    }
    notas.append( narrativa );
    notas( primera, notas.toString().trim() );
  }

  private void bloque( Bloque b ) {
    subtituloPendiente = vacio( b.titulo ) ? null : b.titulo;
    if ( b instanceof Parrafo ) {
      parrafoBloque( (Parrafo) b );
    } else if ( b instanceof Lista ) {
      listaBloque( (Lista) b );
    } else if ( b instanceof Tabla ) {
      tabla( (Tabla) b );
    } else if ( b instanceof Indicadores ) {
      indicadores( (Indicadores) b );
    } else if ( b instanceof Diagrama ) {
      diagrama( (Diagrama) b );
    } else if ( b instanceof Grafico ) {
      grafico( (Grafico) b );
    } else if ( b instanceof Imagen ) {
      Imagen im = (Imagen) b;
      if ( im.datos != null && im.datos.length > 0 ) {
        figura( im.datos, im.mime, im.ancho, im.alto, 120, im.escala > 0 ? im.escala : 1f, null );
      }
    } else if ( b instanceof Aviso ) {
      aviso( (Aviso) b );
    }
    subtituloPendiente = null;
    if ( !vacio( b.nota ) ) {
      nota( b.nota );
    }
    if ( b.ia && tema.rotularIa ) {
      String et = inf.rotulos == null ? null : inf.rotulos.get( "ai_label" );
      nota( vacio( et ) ? r.s( "ia" ) : et );
    }
  }

  // ================================================================== diapositivas y espacio

  /** Diapositiva nueva con banda, titulo en su placeholder, marca de agua y pie. */
  private XSLFSlide nuevaDiapo( String titulo, XSLFSlideLayout layout ) {
    XSLFSlide d = ppt.createSlide( layout );
    diapo = d;
    marcaDeAgua( d );
    rect( d, 0, 0, ANCHO, 6, primario, null );
    XSLFTextShape[] ph = d.getPlaceholders();
    if ( ph.length > 0 ) {
      XSLFTextShape t = ph[ 0 ];
      ancla( t, X0, 16, ANCHO_UTIL, 50 );
      double size = titulo != null && titulo.length() > 60 ? 20 : 26;
      estiloMarcador( t, titulo, size, primario, true, VerticalAlignment.MIDDLE );
    }
    pie( d );
    y = Y_CONTENIDO;
    return d;
  }

  /** Sigue la seccion actual en otra diapositiva: «Titulo (cont.)». */
  private void continuar() {
    nuevaDiapo( tituloSeccion + " " + r.s( "cont" ), laySoloTitulo );
  }

  private boolean vacia() {
    return y <= Y_CONTENIDO + 1;
  }

  /**
   * Deja lugar para un bloque de ese alto (mas el subtitulo pendiente): si no entra y la diapositiva
   * ya tiene algo, pasa a otra. Despues dibuja el subtitulo pendiente.
   */
  private void reservar( double alto ) {
    double extra = subtituloPendiente != null ? ALTO_SUBTITULO : 0;
    if ( y + extra + alto > Y_LIMITE && !vacia() ) {
      continuar();
    }
    if ( subtituloPendiente != null ) {
      subtitulo( subtituloPendiente );
      subtituloPendiente = null;
    }
  }

  private void subtitulo( String t ) {
    XSLFTextBox tb = caja( diapo, X0, y, ANCHO_UTIL, ALTO_SUBTITULO - 2 );
    run( parrafo( tb ), limpio( t ), 13, primario, true, false );
    y += ALTO_SUBTITULO;
  }

  private void marcaDeAgua( XSLFSlide d ) {
    if ( marcaAgua == null ) {
      return;
    }
    double alto = ANCHO_MARCA * 21 / 37;
    XSLFPictureShape ps = d.createPicture( marcaAgua );
    ps.setAnchor( new Rectangle2D.Double( ANCHO - X0 - ANCHO_MARCA, Y_PIE - 10 - alto, ANCHO_MARCA, alto ) );
  }

  /** Pie de 7,5 pt: simbolo, «Pentaho Live · IDC Studios» a la izquierda y el numero a la derecha. */
  private void pie( XSLFSlide d ) {
    double x = X0;
    if ( simboloPie != null ) {
      XSLFPictureShape ps = d.createPicture( simboloPie );
      ps.setAnchor( new Rectangle2D.Double( X0, Y_PIE + 4.5, 14, 14 * 21.0 / 37 ) );
      x += 19;
    }
    XSLFTextBox tb = caja( d, x, Y_PIE, 400, 18 );
    tb.setVerticalAlignment( VerticalAlignment.MIDDLE );
    run( parrafo( tb ), Tema.PIE, 7.5, tenue, false, false );

    XSLFTextBox nb = caja( d, ANCHO - X0 - 120, Y_PIE, 120, 18 );
    nb.setVerticalAlignment( VerticalAlignment.MIDDLE );
    XSLFTextParagraph p = parrafo( nb );
    p.setTextAlign( TextAlign.RIGHT );
    // campo slidenum: el numero se actualiza solo si se agregan o borran diapositivas
    CTTextField f = p.getXmlObject().addNewFld();
    f.setId( "{" + UUID.randomUUID().toString().toUpperCase( Locale.ROOT ) + "}" );
    f.setType( "slidenum" );
    CTTextCharacterProperties rp = f.addNewRPr();
    rp.setLang( locale.toLanguageTag() );
    rp.setSz( 750 );
    rp.addNewSolidFill().addNewSrgbClr().setVal( rgb( tenue ) );
    rp.addNewLatin().setTypeface( FUENTE );
    f.setT( String.valueOf( ppt.getSlides().size() ) );
    finDeParrafo( p, 7.5 );
  }

  // ================================================================== bloques de texto

  private void parrafoBloque( Parrafo p ) {
    if ( vacio( p.texto ) ) {
      return;
    }
    texto( Arrays.asList( limpio( p.texto ).split( "\r?\n" ) ), 14, true );
  }

  private void listaBloque( Lista l ) {
    if ( l.items == null || l.items.isEmpty() ) {
      return;
    }
    List<String> lineas = new ArrayList<>();
    for ( String it : l.items ) {
      lineas.add( "- " + limpio( it == null ? "" : it ).replace( '\n', ' ' ) );
    }
    texto( lineas, 14, true );
  }

  /**
   * Cuadro de texto con las lineas dadas; las que empiezan con «- » van con vineta. Si no entra en
   * una diapositiva entera, achica la letra hasta 10 pt.
   */
  private void texto( List<String> crudas, double size, boolean marcas ) {
    List<String> lineas = new ArrayList<>();
    for ( String l : crudas ) {
      lineas.add( limpio( l ) );
    }
    double alto = altoLineas( lineas, size );
    double maximo = Y_LIMITE - Y_CONTENIDO - ( subtituloPendiente != null ? ALTO_SUBTITULO : 0 );
    while ( alto > maximo && size > 10 ) {
      size -= 1;
      alto = altoLineas( lineas, size );
    }
    reservar( alto );
    XSLFTextBox tb = caja( diapo, X0, y, ANCHO_UTIL, alto );
    for ( String l : lineas ) {
      XSLFTextParagraph p = parrafo( tb );
      p.setSpaceAfter( -( size * 0.35 ) );
      String linea = l;
      if ( esVineta( l ) ) {
        linea = l.trim().substring( 2 );
        p.setBullet( true );
        p.setBulletCharacter( "•" );
        p.setBulletFontColor( acento );
        p.setLeftMargin( 16.0 );
        p.setIndent( -12.0 );
      }
      if ( marcas ) {
        conMarcas( p, linea, size, texto );
      } else {
        run( p, linea, size, texto, false, false );
      }
    }
    y += alto + ESPACIO;
  }

  private static boolean esVineta( String l ) {
    String t = l.trim();
    return t.startsWith( "- " ) || t.startsWith( "* " ) || t.startsWith( "• " );
  }

  private double altoLineas( List<String> lineas, double size ) {
    double total = 0;
    for ( String l : lineas ) {
      double sangria = esVineta( l ) ? 16 : 0;
      total += altoTexto( Arrays.asList( l ), size, ANCHO_UTIL, sangria ) + size * 0.35;
    }
    return total;
  }

  private void aviso( Aviso a ) {
    if ( vacio( a.texto ) ) {
      return;
    }
    Color c = Aviso.ERROR.equals( a.nivel ) ? ERROR : Aviso.WARN.equals( a.nivel ) ? WARN : primario;
    String t = limpio( a.texto );
    double alto = altoTexto( Arrays.asList( t.split( "\r?\n" ) ), 13, ANCHO_UTIL - 14, 0 ) + 4;
    reservar( alto );
    rect( diapo, X0, y, 3, alto, c, null );
    XSLFTextBox tb = caja( diapo, X0 + 12, y, ANCHO_UTIL - 12, alto );
    tb.setVerticalAlignment( VerticalAlignment.MIDDLE );
    for ( String l : t.split( "\r?\n" ) ) {
      XSLFTextParagraph p = parrafo( tb );
      run( p, l, 13, c, false, true );
    }
    y += alto + ESPACIO;
  }

  /** Linea chica tenue en cursiva (nota del bloque, rotulo de IA, aclaraciones). */
  private void nota( String t ) {
    String n = limpio( t );
    double alto = altoTexto( Arrays.asList( n.split( "\r?\n" ) ), 9.5, ANCHO_UTIL, 0 );
    if ( y + alto > Y_LIMITE && !vacia() ) {
      continuar();
    }
    // pegada al bloque de arriba
    double yy = vacia() ? y : y - ESPACIO + 4;
    XSLFTextBox tb = caja( diapo, X0, yy, ANCHO_UTIL, alto );
    for ( String l : n.split( "\r?\n" ) ) {
      run( parrafo( tb ), l, 9.5, tenue, false, true );
    }
    y = yy + alto + ESPACIO;
  }

  // ================================================================== indicadores

  private void indicadores( Indicadores ind ) {
    List<Indicador> items = new ArrayList<>();
    if ( ind.items != null ) {
      for ( Indicador i : ind.items ) {
        if ( i != null ) {
          items.add( i );
        }
      }
    }
    if ( items.isEmpty() ) {
      return;
    }
    int filas = (int) Math.ceil( items.size() / 4.0 );
    int porFila = (int) Math.ceil( items.size() / (double) filas );
    double sep = 12;
    double ancho = Math.min( 260, ( ANCHO_UTIL - sep * ( porFila - 1 ) ) / porFila );
    for ( int f = 0; f < filas; f++ ) {
      reservar( ALTO_TARJETA );
      for ( int k = 0; k < porFila; k++ ) {
        int idx = f * porFila + k;
        if ( idx >= items.size() ) {
          break;
        }
        tarjeta( items.get( idx ), X0 + k * ( ancho + sep ), y, ancho );
      }
      y += ALTO_TARJETA + ESPACIO;
    }
  }

  private void tarjeta( Indicador i, double x, double yy, double ancho ) {
    Color nivel = Indicador.OK.equals( i.nivel ) ? OK : Indicador.WARN.equals( i.nivel ) ? WARN
      : Indicador.ERROR.equals( i.nivel ) ? ERROR : null;
    XSLFAutoShape fondo = diapo.createAutoShape();
    fondo.setShapeType( ShapeType.ROUND_RECT );
    fondo.setAnchor( new Rectangle2D.Double( x, yy, ancho, ALTO_TARJETA ) );
    fondo.setFillColor( fondoTarjeta );
    fondo.setLineColor( borde );
    fondo.setLineWidth( 0.75 );
    rect( diapo, x, yy + 8, 3, ALTO_TARJETA - 16, nivel != null ? nivel : acento, null );

    double interior = ancho - 24;
    XSLFTextBox tb = caja( diapo, x + 14, yy + 6, interior, ALTO_TARJETA - 12 );
    tb.setVerticalAlignment( VerticalAlignment.MIDDLE );
    XSLFTextParagraph p1 = parrafo( tb );
    run( p1, limpio( i.etiqueta ), 10, tenue, false, false );
    String valor = limpio( i.valor );
    double size = 22;
    while ( size > 12 && valor.length() * size * ANCHO_CARACTER > interior ) {
      size -= 1;
    }
    XSLFTextParagraph p2 = parrafo( tb );
    run( p2, valor, size, nivel != null ? nivel : texto, true, false );
    if ( !vacio( i.detalle ) ) {
      XSLFTextParagraph p3 = parrafo( tb );
      String det = limpio( i.detalle );
      double sd = 9;
      if ( det.length() * sd * ANCHO_CARACTER > interior * 2 ) {
        int max = (int) ( interior * 2 / ( sd * ANCHO_CARACTER ) ) - 1;
        det = det.substring( 0, Math.max( 1, max ) ) + "…";
      }
      run( p3, det, sd, tenue, false, false );
    }
  }

  // ================================================================== tablas

  private void tabla( Tabla t ) {
    String titulo = subtituloPendiente;
    subtituloPendiente = null;
    tablaPaginada( t, titulo );
  }

  /** Tabla XSLF de 10 filas como mucho por diapositiva, con la cabecera repetida y «(cont.)». */
  private void tablaPaginada( Tabla t, String titulo ) {
    List<String> cols = t.columnas == null ? new ArrayList<String>() : t.columnas;
    List<List<String>> filas = new ArrayList<>();
    if ( t.filas != null ) {
      for ( List<String> f : t.filas ) {
        filas.add( f == null ? new ArrayList<String>() : f );
      }
    }
    int nCol = cols.size();
    for ( List<String> f : filas ) {
      nCol = Math.max( nCol, f.size() );
    }
    if ( nCol == 0 ) {
      return;
    }
    double size = nCol > 6 ? 10 : 11;
    double[] anchos = anchosColumnas( cols, filas, nCol, size );
    boolean conCabecera = !cols.isEmpty();
    double altoCab = conCabecera ? altoFila( cols, anchos, size ) : 0;

    int i = 0;
    boolean primera = true;
    while ( primera || i < filas.size() ) {
      double altoTit = titulo != null ? ALTO_SUBTITULO : 0;
      double disponible = Y_LIMITE - y - altoTit - altoCab;
      int resto = filas.size() - i;
      // una tabla chica que no entra en lo que queda pero si en una diapositiva vacia no se parte (3+1,
      // 5+1 filas): va entera a la siguiente
      if ( !vacia() && resto > 0 && resto <= FILAS_POR_DIAPO + 2 ) {
        double todo = 0;
        for ( int k = i; k < filas.size(); k++ ) {
          todo += altoFila( filas.get( k ), anchos, size );
        }
        if ( todo > disponible && todo <= Y_LIMITE - Y_CONTENIDO - altoTit - altoCab ) {
          continuar();
          continue;
        }
      }
      // 11 o 12 filas: van todas juntas si entran (sin dejar 1 o 2 solas en otra diapositiva)
      int tope = resto > FILAS_POR_DIAPO && resto <= FILAS_POR_DIAPO + 2 ? resto : FILAS_POR_DIAPO;
      int n = 0;
      double alto = 0;
      while ( i + n < filas.size() && n < tope ) {
        double h = altoFila( filas.get( i + n ), anchos, size );
        if ( alto + h > disponible && n > 0 ) {
          break;
        }
        alto += h;
        n++;
      }
      // si igual quedarian 1 o 2 filas para la siguiente, se pasan algunas mas para que no queden solas
      int quedan = resto - n;
      if ( quedan > 0 && quedan < 3 && n - ( 3 - quedan ) >= 3 ) {
        for ( int k = 0; k < 3 - quedan; k++ ) {
          n--;
          alto -= altoFila( filas.get( i + n ), anchos, size );
        }
      }
      int minimo = Math.min( 3, filas.size() - i );
      if ( ( n < minimo || alto > disponible ) && !vacia() ) {
        continuar();
        continue;
      }
      if ( titulo != null ) {
        subtitulo( primera ? titulo : titulo + " " + r.s( "cont" ) );
      }
      dibujarTabla( cols, filas.subList( i, i + n ), anchos, size, t.alineacion, conCabecera, altoCab );
      i += n;
      primera = false;
      if ( i < filas.size() ) {
        continuar();
      }
    }
    if ( !vacio( t.pie ) ) {
      nota( t.pie );
    }
  }

  /**
   * Alto de la tabla entera (subtitulo, cabecera, filas y pie) si entra en una sola diapositiva; -1 si es
   * de las que se paginan.
   */
  private double altoTablaEntera( Tabla t ) {
    List<String> cols = t.columnas == null ? new ArrayList<String>() : t.columnas;
    List<List<String>> filas = new ArrayList<>();
    if ( t.filas != null ) {
      for ( List<String> f : t.filas ) {
        filas.add( f == null ? new ArrayList<String>() : f );
      }
    }
    if ( filas.size() > FILAS_POR_DIAPO + 2 ) {
      return -1;
    }
    int nCol = cols.size();
    for ( List<String> f : filas ) {
      nCol = Math.max( nCol, f.size() );
    }
    if ( nCol == 0 ) {
      return -1;
    }
    double size = nCol > 6 ? 10 : 11;
    double[] anchos = anchosColumnas( cols, filas, nCol, size );
    double alto = ( vacio( t.titulo ) ? 0 : ALTO_SUBTITULO ) + ( cols.isEmpty() ? 0 : altoFila( cols, anchos, size ) );
    for ( List<String> f : filas ) {
      alto += altoFila( f, anchos, size );
    }
    if ( !vacio( t.pie ) ) {
      alto += altoTexto( Arrays.asList( limpio( t.pie ).split( "\r?\n" ) ), 9.5, ANCHO_UTIL, 0 );
    }
    if ( !vacio( t.nota ) ) {
      alto += altoTexto( Arrays.asList( limpio( t.nota ).split( "\r?\n" ) ), 9.5, ANCHO_UTIL, 0 );
    }
    return alto + ESPACIO;
  }

  /** Lo minimo que tiene que quedar en la diapositiva para un grafico (lo que pide ubicarFigura); -1 si no hay SVG. */
  private double altoMinimoGrafico( Grafico g ) {
    try {
      String svg = proveedorSvg.svg( g, tema, (int) ANCHO_UTIL );
      if ( svg == null || !svg.contains( "<svg" ) ) {
        return -1;
      }
      double[] tam = tamanoSvg( svg );
      double h = ANCHO_UTIL * tam[ 1 ] / tam[ 0 ];
      return ( vacio( g.titulo ) ? 0 : ALTO_SUBTITULO ) + Math.min( h, Math.max( 150, h * 0.75 ) );
    } catch ( Throwable e ) {
      return -1;
    }
  }

  private void dibujarTabla( List<String> cols, List<List<String>> filas, double[] anchos, double size,
                             List<String> alineacion, boolean conCabecera, double altoCab ) {
    XSLFTable tbl = diapo.createTable();
    double total = 0;
    for ( double a : anchos ) {
      total += a;
    }
    double alto = 0;
    if ( conCabecera ) {
      XSLFTableRow fila = tbl.addRow();
      fila.setHeight( altoCab );
      alto += altoCab;
      for ( int j = 0; j < anchos.length; j++ ) {
        XSLFTableCell c = fila.addCell();
        celda( c, j < cols.size() ? cols.get( j ) : "", size, Color.WHITE, true, alinear( alineacion, j ) );
        c.setFillColor( primario );
        borde( c, primario );
      }
    }
    int k = 0;
    for ( List<String> f : filas ) {
      XSLFTableRow fila = tbl.addRow();
      double h = altoFila( f, anchos, size );
      fila.setHeight( h );
      alto += h;
      for ( int j = 0; j < anchos.length; j++ ) {
        XSLFTableCell c = fila.addCell();
        celda( c, j < f.size() ? f.get( j ) : "", size, texto, false, alinear( alineacion, j ) );
        c.setFillColor( k % 2 == 1 ? cebra : Color.WHITE );
        borde( c, borde );
      }
      k++;
    }
    for ( int j = 0; j < anchos.length; j++ ) {
      tbl.setColumnWidth( j, anchos[ j ] );
    }
    tbl.setAnchor( new Rectangle2D.Double( X0, y, total, alto ) );
    y += alto + ESPACIO;
  }

  private void celda( XSLFTableCell c, String valor, double size, Color color, boolean negrita, TextAlign al ) {
    c.clearText();
    c.setLeftInset( 6 );
    c.setRightInset( 6 );
    c.setTopInset( 3 );
    c.setBottomInset( 3 );
    c.setVerticalAlignment( VerticalAlignment.MIDDLE );
    String v = limpio( valor );
    for ( String linea : v.split( "\r?\n", -1 ) ) {
      XSLFTextParagraph p = c.addNewTextParagraph();
      p.setTextAlign( al );
      // una celda vacia toma la altura de la letra de la plantilla (18 pt) en LibreOffice: espacio duro
      run( p, linea.isEmpty() ? " " : linea, size, color, negrita, false );
    }
  }

  private static void borde( XSLFTableCell c, Color color ) {
    c.setBorderColor( BorderEdge.bottom, color );
    c.setBorderWidth( BorderEdge.bottom, 0.75 );
  }

  private static TextAlign alinear( List<String> al, int j ) {
    if ( al == null || j >= al.size() || al.get( j ) == null ) {
      return TextAlign.LEFT;
    }
    String a = al.get( j ).trim().toLowerCase( Locale.ROOT );
    return "r".equals( a ) ? TextAlign.RIGHT : "c".equals( a ) ? TextAlign.CENTER : TextAlign.LEFT;
  }

  /** Anchos por columna segun el largo del contenido; la tabla ocupa como mucho el ancho util. */
  private static double[] anchosColumnas( List<String> cols, List<List<String>> filas, int nCol, double size ) {
    double[] natural = new double[ nCol ];
    double suma = 0;
    for ( int j = 0; j < nCol; j++ ) {
      int largo = j < cols.size() ? largoMayor( cols.get( j ) ) + 1 : 0;
      for ( List<String> f : filas ) {
        if ( j < f.size() ) {
          largo = Math.max( largo, largoMayor( f.get( j ) ) );
        }
      }
      largo = Math.max( 3, Math.min( 48, largo ) );
      natural[ j ] = largo * size * ANCHO_CARACTER + 14;
      suma += natural[ j ];
    }
    double total = Math.min( ANCHO_UTIL, Math.max( suma, Math.min( ANCHO_UTIL, 300 ) ) );
    double[] anchos = new double[ nCol ];
    for ( int j = 0; j < nCol; j++ ) {
      anchos[ j ] = Math.floor( total * natural[ j ] / suma );
    }
    return anchos;
  }

  private static int largoMayor( String s ) {
    if ( s == null ) {
      return 0;
    }
    int m = 0;
    for ( String l : s.split( "\r?\n" ) ) {
      m = Math.max( m, l.length() );
    }
    return m;
  }

  private static double altoFila( List<String> celdas, double[] anchos, double size ) {
    int lineas = 1;
    for ( int j = 0; j < anchos.length && j < celdas.size(); j++ ) {
      String v = celdas.get( j );
      if ( v == null ) {
        continue;
      }
      double porLinea = Math.max( 1, ( anchos[ j ] - 12 ) / ( size * ANCHO_CARACTER ) );
      int n = 0;
      for ( String l : v.split( "\r?\n", -1 ) ) {
        n += Math.max( 1, (int) Math.ceil( l.length() / porLinea ) );
      }
      lineas = Math.max( lineas, n );
    }
    return Math.max( 20, lineas * size * INTERLINEA + 9 );
  }

  // ================================================================== diagramas, graficos e imagenes

  private void diagrama( Diagrama d ) {
    if ( d.imagen != null && d.imagen.datos != null && d.imagen.datos.length > 0 ) {
      // el lienzo real (flow / job_flow), ajustado a la diapositiva y nunca mas grande que su tamano natural
      figura( d.imagen.datos, d.imagen.mime, d.imagen.ancho, d.imagen.alto, 200, d.imagen.escala > 0
        ? d.imagen.escala : 1f, r.s( "diagrama_reducido" ) );
      return;
    }
    byte[] png = d.id == null ? null : pngMermaid.get( d.id );
    if ( png != null && png.length > 0 ) {
      try {
        // PrerenderMermaid los saca a escala 2: un diagrama de dos nodos no se agranda hasta el ancho entero
        figura( png, Imagen.PNG, 0, 0, 200, ESCALA_MERMAID, r.s( "diagrama_reducido" ) );
        return;
      } catch ( RuntimeException e ) {
        // PNG roto: sigue con la tabla
      }
    }
    if ( d.alternativa != null ) {
      if ( d.mermaid != null ) {
        diagramasComoTabla++;
      }
      String titulo = subtituloPendiente;
      subtituloPendiente = null;
      if ( titulo == null && !vacio( d.alternativa.titulo ) ) {
        titulo = d.alternativa.titulo;
      }
      tablaPaginada( d.alternativa, titulo );
      nota( r.s( "diagrama_como_tabla" ) );
      return;
    }
    reservar( 30 );
    String alt = vacio( d.alt ) ? "" : " " + d.alt;
    nota( r.s( "diagrama_no_disponible" ) + alt );
  }

  /**
   * Imagen ajustada al ancho util y al alto que queda, nunca mas grande que su tamano natural (1 punto
   * por pixel CSS: los pixeles divididos por la escala a la que se pinto). Si queda menos que altoMinimo,
   * pasa a otra diapositiva. Devuelve cuanto se achico respecto del tamano natural (1 = natural).
   */
  private double figura( byte[] datos, String mime, int anchoPx, int altoPx, double altoMinimo, float escala,
                         String notaSiChica ) {
    int w0 = anchoPx;
    int h0 = altoPx;
    if ( w0 <= 0 || h0 <= 0 ) {
      int[] wh = medir( datos );
      w0 = wh[ 0 ];
      h0 = wh[ 1 ];
    }
    double natural = w0 / (double) ( escala > 0 ? escala : 1f );
    double proporcion = h0 / (double) w0;
    double w = Math.min( ANCHO_UTIL, natural );
    double h = w * proporcion;
    ubicarFigura( w, h, altoMinimo );
    double disponible = Y_LIMITE - y;
    if ( h > disponible ) {
      h = disponible;
      w = h / proporcion;
    }
    // muy achicada: la aclaracion va en la misma diapositiva, debajo (se le hace lugar)
    boolean chica = notaSiChica != null && natural > 0 && w / natural < ESCALA_LEGIBLE;
    if ( chica ) {
      double hn = altoTexto( Arrays.asList( limpio( notaSiChica ) ), 9.5, ANCHO_UTIL, 0 ) + ESPACIO + 2;
      if ( y + h + hn > Y_LIMITE && h > 40 ) {
        h = Math.max( 40, Y_LIMITE - y - hn );
        w = h / proporcion;
      }
    }
    XSLFPictureData pd = ppt.addPicture( datos, tipoImagen( mime ) );
    XSLFPictureShape ps = diapo.createPicture( pd );
    ps.setAnchor( new Rectangle2D.Double( X0 + ( ANCHO_UTIL - w ) / 2, y, w, h ) );
    y += h + ESPACIO;
    if ( chica ) {
      nota( notaSiChica );
    }
    return natural <= 0 ? 1 : w / natural;
  }

  /**
   * Deja lugar para una figura que puede achicarse hasta el 75 % de su alto (y nunca por debajo de
   * altoMinimo); si en lo que queda de la diapositiva saldria mas chica, pasa a otra.
   */
  private void ubicarFigura( double w, double h, double altoMinimo ) {
    reservar( Math.min( h, Math.max( altoMinimo, h * 0.75 ) ) );
  }

  private void grafico( Grafico g ) {
    String svg = null;
    try {
      svg = proveedorSvg.svg( g, tema, (int) ANCHO_UTIL );
    } catch ( Throwable t ) {
      svg = null;
    }
    if ( svg != null && svg.contains( "<svg" ) ) {
      try {
        double[] tam = tamanoSvg( svg );
        double proporcion = tam[ 1 ] / tam[ 0 ];
        double w = ANCHO_UTIL; // vectorial: se puede agrandar sin perder nitidez
        double h = w * proporcion;
        ubicarFigura( w, h, 150 );
        double disponible = Y_LIMITE - y;
        if ( h > disponible ) {
          h = disponible;
          w = h / proporcion;
        }
        Rectangle2D ancla = new Rectangle2D.Double( X0 + ( ANCHO_UTIL - w ) / 2, y, w, h );
        XSLFPictureData svgPd = ppt.addPicture( svg.getBytes( StandardCharsets.UTF_8 ), PictureType.SVG );
        try {
          // POI dibuja el PNG de respaldo con Batik (lib\ de PDI)
          XSLFPictureShape.addSvgImage( diapo, svgPd, PictureType.PNG, ancla );
        } catch ( Throwable t ) {
          // respaldo propio: SvgAPng (WP6) y el SVG como extension del blip
          byte[] png = io.github.ivi2511.pentahomcp.informes.graficos.SvgAPng.png( svg, (int) ( w * 2 ) );
          XSLFPictureShape ps = diapo.createPicture( ppt.addPicture( png, PictureType.PNG ) );
          ps.setSvgImage( svgPd );
          ps.setAnchor( ancla );
        }
        y += h + ESPACIO;
        return;
      } catch ( Throwable t ) {
        // sigue con la tabla
      }
    }
    graficosComoTabla++;
    String titulo = subtituloPendiente;
    subtituloPendiente = null;
    tablaPaginada( tablaDeGrafico( g ), titulo );
    nota( r.s( "grafico_como_tabla" ) );
  }

  /** Los datos del grafico como tabla, para cuando no hay SVG. */
  private Tabla tablaDeGrafico( Grafico g ) {
    Tabla t = new Tabla();
    if ( Grafico.GANTT.equals( g.clase ) ) {
      t.columnas.addAll( Arrays.asList( r.s( "col_categoria" ), r.s( "col_tramo" ), r.s( "col_inicio" ),
        r.s( "col_fin" ), r.s( "col_duracion" ) ) );
      t.alineacion = Arrays.asList( "l", "l", "r", "r", "r" );
      if ( g.tramos != null ) {
        for ( Tramo tr : g.tramos ) {
          if ( tr == null ) {
            continue;
          }
          String tipo = Tramo.INIT.equals( tr.tipo ) ? r.s( "tramo_init" ) : Tramo.EXEC.equals( tr.tipo )
            ? r.s( "tramo_exec" ) : String.valueOf( tr.tipo );
          t.fila( tr.categoria, tipo, String.format( locale, "%,d", tr.inicioMs ),
            String.format( locale, "%,d", tr.finMs ), String.format( locale, "%,d", tr.finMs - tr.inicioMs ) );
        }
      }
      return t;
    }
    t.columnas.add( r.s( "col_categoria" ) );
    List<String> al = new ArrayList<>();
    al.add( "l" );
    List<Serie> series = g.series == null ? new ArrayList<Serie>() : g.series;
    for ( Serie s : series ) {
      String n = s == null || vacio( s.nombre ) ? "" : s.nombre;
      if ( !vacio( g.unidad ) && series.size() == 1 && !n.contains( g.unidad ) ) {
        n = n.isEmpty() ? g.unidad : n + " (" + g.unidad + ")";
      }
      t.columnas.add( n );
      al.add( "r" );
    }
    t.alineacion = al;
    List<String> cats = g.categorias == null ? new ArrayList<String>() : g.categorias;
    for ( int i = 0; i < cats.size(); i++ ) {
      List<String> fila = new ArrayList<>();
      fila.add( cats.get( i ) );
      for ( Serie s : series ) {
        Double v = s == null || s.valores == null || i >= s.valores.size() ? null : s.valores.get( i );
        fila.add( numero( v ) );
      }
      t.filas.add( fila );
    }
    return t;
  }

  private String numero( Double v ) {
    if ( v == null || v.isNaN() ) {
      return "";
    }
    if ( v == Math.rint( v ) && Math.abs( v ) < 1e15 ) {
      return String.format( locale, "%,d", v.longValue() );
    }
    return String.format( locale, "%,.2f", v );
  }

  // ================================================================== notas del orador

  /** Notas en el placeholder BODY; se borran la fecha y el numero que trae la plantilla de notas. */
  private void notas( XSLFSlide d, String texto ) {
    if ( vacio( texto ) ) {
      return;
    }
    XSLFNotes n = ppt.getNotesSlide( d );
    XSLFTextShape cuerpo = null;
    for ( XSLFTextShape sh : n.getPlaceholders() ) {
      Placeholder tipo = sh.getTextType();
      if ( tipo == Placeholder.DATETIME || tipo == Placeholder.SLIDE_NUMBER || tipo == Placeholder.HEADER
        || tipo == Placeholder.FOOTER ) {
        n.removeShape( sh );
      } else if ( tipo == Placeholder.BODY && cuerpo == null ) {
        cuerpo = sh;
      }
    }
    if ( cuerpo == null ) {
      return;
    }
    cuerpo.clearText();
    for ( String l : limpio( texto ).split( "\r?\n" ) ) {
      XSLFTextParagraph p = cuerpo.addNewTextParagraph();
      XSLFTextRun run = p.addNewTextRun();
      run.setText( l.replace( "**", "" ) );
      run.setFontSize( 12.0 );
    }
  }

  // ================================================================== ayudas de texto y forma

  private XSLFTextBox caja( XSLFSlide d, double x, double yy, double w, double h ) {
    XSLFTextBox tb = d.createTextBox();
    tb.setAnchor( new Rectangle2D.Double( x, yy, w, h ) );
    tb.setWordWrap( true );
    tb.setLeftInset( 0 );
    tb.setRightInset( 0 );
    tb.setTopInset( 2 );
    tb.setBottomInset( 2 );
    tb.setVerticalAlignment( VerticalAlignment.TOP );
    tb.clearText();
    return tb;
  }

  private static XSLFTextParagraph parrafo( XSLFTextShape sh ) {
    XSLFTextParagraph p = sh.addNewTextParagraph();
    p.setTextAlign( TextAlign.LEFT );
    return p;
  }

  private static XSLFTextRun run( XSLFTextParagraph p, String t, double size, Color color, boolean negrita,
                                  boolean cursiva ) {
    XSLFTextRun run = p.addNewTextRun();
    run.setText( t == null ? "" : t );
    run.setFontFamily( FUENTE );
    run.setFontSize( size );
    run.setFontColor( color );
    run.setBold( negrita );
    run.setItalic( cursiva );
    finDeParrafo( p, size );
    return run;
  }

  /**
   * Tamano del fin de parrafo (endParaRPr). Sin el, un parrafo vacio (una celda vacia) toma los 18 pt
   * de la plantilla y la fila sale mas alta que las demas. Va siempre ultimo: se saca y se vuelve a poner.
   */
  private static void finDeParrafo( XSLFTextParagraph p, double size ) {
    CTTextParagraph x = p.getXmlObject();
    if ( x.isSetEndParaRPr() ) {
      x.unsetEndParaRPr();
    }
    x.addNewEndParaRPr().setSz( (int) Math.round( size * 100 ) );
  }

  /** Texto con **negrita** y *cursiva* (el poco Markdown del modelo). */
  private static void conMarcas( XSLFTextParagraph p, String t, double size, Color color ) {
    Matcher m = MARCAS.matcher( t );
    int desde = 0;
    while ( m.find() ) {
      if ( m.start() > desde ) {
        run( p, t.substring( desde, m.start() ), size, color, false, false );
      }
      if ( m.group( 1 ) != null ) {
        run( p, m.group( 1 ), size, color, true, false );
      } else {
        run( p, m.group( 2 ), size, color, false, true );
      }
      desde = m.end();
    }
    if ( desde < t.length() || desde == 0 ) {
      run( p, t.substring( desde ), size, color, false, false );
    }
  }

  /**
   * Ancla de un placeholder EN la diapositiva. XSLFSimpleShape.setAnchor busca el xfrm heredado y, si
   * la forma no tiene uno propio, escribe en el de la plantilla (cambiaria todas las diapositivas que la
   * usan): por eso primero se le crea un xfrm vacio.
   */
  static void ancla( XSLFTextShape sh, double x, double yy, double w, double h ) {
    XmlObject xo = sh.getXmlObject();
    if ( xo instanceof CTShape ) {
      CTShapeProperties sp = ( (CTShape) xo ).getSpPr();
      if ( sp == null ) {
        sp = ( (CTShape) xo ).addNewSpPr();
      }
      if ( !sp.isSetXfrm() ) {
        sp.addNewXfrm();
      }
    }
    sh.setAnchor( new Rectangle2D.Double( x, yy, w, h ) );
  }

  /** Placeholder de la portada con el relleno del color primario y 60 pt de margen a los costados. */
  private void banda( XSLFTextShape t, double abajo ) {
    t.setFillColor( primario );
    t.setLeftInset( 60 );
    t.setRightInset( 60 );
    t.setTopInset( 6 );
    t.setBottomInset( abajo );
  }

  /**
   * La plantilla por defecto de POI es 4:3 (720 pt de ancho): estira en horizontal los placeholders del
   * patron y de los diseños, asi una diapositiva que el usuario agregue en PowerPoint sale bien.
   */
  private static void plantillas16x9( XSLFSlideMaster master, double fx ) {
    if ( Math.abs( fx - 1 ) < 0.01 ) {
      return;
    }
    escalar( master, fx );
    for ( XSLFSlideLayout l : master.getSlideLayouts() ) {
      escalar( l, fx );
    }
  }

  private static void escalar( XSLFSheet hoja, double fx ) {
    for ( XSLFShape sh : hoja.getShapes() ) {
      XmlObject xo = sh.getXmlObject();
      if ( !( xo instanceof CTShape ) ) {
        continue;
      }
      CTShapeProperties sp = ( (CTShape) xo ).getSpPr();
      if ( sp == null || !sp.isSetXfrm() ) {
        continue;
      }
      CTTransform2D t = sp.getXfrm();
      if ( t.isSetOff() ) {
        t.getOff().setX( Math.round( t.getOff().getX() * fx ) );
      }
      if ( t.isSetExt() ) {
        t.getExt().setCx( Math.round( t.getExt().getCx() * fx ) );
      }
    }
  }

  /** Reemplaza el texto de un placeholder y le pone letra, color y alineacion propios. */
  private void estiloMarcador( XSLFTextShape t, String valor, double size, Color color, boolean negrita,
                               VerticalAlignment va ) {
    t.clearText();
    t.setLeftInset( 0 );
    t.setRightInset( 0 );
    t.setWordWrap( true );
    t.setVerticalAlignment( va );
    XSLFTextParagraph p = t.addNewTextParagraph();
    p.setTextAlign( TextAlign.LEFT );
    p.setBullet( false );
    run( p, limpio( valor ), size, color, negrita, false );
  }

  private static XSLFAutoShape rect( XSLFSlide d, double x, double yy, double w, double h, Color fill,
                                     Color linea ) {
    XSLFAutoShape s = d.createAutoShape();
    s.setShapeType( ShapeType.RECT );
    s.setAnchor( new Rectangle2D.Double( x, yy, w, h ) );
    s.setFillColor( fill );
    s.setLineColor( linea );
    return s;
  }

  /** Alto estimado de un texto (con su interlineado y margenes) en un ancho dado. */
  private static double altoTexto( List<String> lineas, double size, double ancho, double sangria ) {
    double porLinea = Math.max( 1, ( ancho - sangria ) / ( size * ANCHO_CARACTER ) );
    int n = 0;
    for ( String l : lineas ) {
      n += Math.max( 1, (int) Math.ceil( sinMarcas( l ).length() / porLinea ) );
    }
    return n * size * INTERLINEA + 6;
  }

  private static String sinMarcas( String t ) {
    return t == null ? "" : t.replace( "**", "" );
  }

  // ================================================================== utilidades

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  /** Saca los caracteres que no valen en XML 1.0 (un .pptx con ellos no abre). null -> "". */
  static String limpio( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder b = null;
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      boolean ok;
      if ( Character.isHighSurrogate( c ) ) {
        ok = i + 1 < s.length() && Character.isLowSurrogate( s.charAt( i + 1 ) );
        if ( ok ) {
          if ( b != null ) {
            b.append( c ).append( s.charAt( i + 1 ) );
          }
          i++;
          continue;
        }
      } else if ( Character.isLowSurrogate( c ) ) {
        ok = false;
      } else {
        ok = c == '\t' || c == '\n' || c == '\r' || ( c >= 0x20 && c <= 0xD7FF ) || ( c >= 0xE000 && c <= 0xFFFD );
      }
      if ( !ok ) {
        if ( b == null ) {
          b = new StringBuilder( s.substring( 0, i ) );
        }
        continue;
      }
      if ( b != null ) {
        b.append( c );
      }
    }
    return b == null ? s : b.toString();
  }

  /** "#RRGGBB", "RRGGBB" o "#RGB"; si no se entiende, el por defecto. */
  static Color color( String hex, String porDefecto ) {
    Color c = parsear( hex );
    if ( c == null ) {
      c = parsear( porDefecto );
    }
    return c == null ? Color.BLACK : c;
  }

  private static Color parsear( String hex ) {
    if ( hex == null ) {
      return null;
    }
    String h = hex.trim();
    if ( h.startsWith( "#" ) ) {
      h = h.substring( 1 );
    }
    if ( h.length() == 3 ) {
      h = "" + h.charAt( 0 ) + h.charAt( 0 ) + h.charAt( 1 ) + h.charAt( 1 ) + h.charAt( 2 ) + h.charAt( 2 );
    }
    if ( h.length() != 6 ) {
      return null;
    }
    try {
      return new Color( Integer.parseInt( h, 16 ) );
    } catch ( NumberFormatException e ) {
      return null;
    }
  }

  /** El color mezclado con blanco: fraccion 0,1 = 10 % del color. */
  static Color claro( Color c, double fraccion ) {
    double f = Math.max( 0, Math.min( 1, fraccion ) );
    return new Color( (int) Math.round( c.getRed() * f + 255 * ( 1 - f ) ),
      (int) Math.round( c.getGreen() * f + 255 * ( 1 - f ) ), (int) Math.round( c.getBlue() * f + 255 * ( 1 - f ) ) );
  }

  private static byte[] rgb( Color c ) {
    return new byte[] { (byte) c.getRed(), (byte) c.getGreen(), (byte) c.getBlue() };
  }

  private static PictureType tipoImagen( String mime ) {
    return mime != null && mime.toLowerCase( Locale.ROOT ).contains( "jp" ) ? PictureType.JPEG : PictureType.PNG;
  }

  /** Ancho y alto en pixeles de un PNG o JPG. */
  static int[] medir( byte[] datos ) {
    try {
      BufferedImage im = ImageIO.read( new ByteArrayInputStream( datos ) );
      if ( im != null ) {
        return new int[] { im.getWidth(), im.getHeight() };
      }
    } catch ( Exception e ) {
      // abajo
    }
    throw new IllegalArgumentException( "Imagen ilegible" );
  }

  /** Ancho y alto de un SVG: atributos width/height del elemento raiz, o el viewBox. */
  static double[] tamanoSvg( String svg ) {
    int ini = svg.indexOf( "<svg" );
    int fin = ini < 0 ? -1 : svg.indexOf( '>', ini );
    String raiz = ini < 0 || fin < 0 ? svg : svg.substring( ini, fin + 1 );
    Matcher mw = SVG_ANCHO.matcher( raiz );
    Matcher mh = SVG_ALTO.matcher( raiz );
    if ( mw.find() && mh.find() ) {
      double w = Double.parseDouble( mw.group( 1 ) );
      double h = Double.parseDouble( mh.group( 1 ) );
      if ( w > 0 && h > 0 ) {
        return new double[] { w, h };
      }
    }
    Matcher mv = SVG_VIEWBOX.matcher( raiz );
    if ( mv.find() ) {
      double w = Double.parseDouble( mv.group( 1 ) );
      double h = Double.parseDouble( mv.group( 2 ) );
      if ( w > 0 && h > 0 ) {
        return new double[] { w, h };
      }
    }
    return new double[] { ANCHO_UTIL, ANCHO_UTIL * 0.4 };
  }

  /** La fecha ISO-8601 del informe con el patron del idioma; null si no hay. */
  private String fecha( String iso ) {
    if ( vacio( iso ) ) {
      return null;
    }
    TemporalAccessor t;
    try {
      t = OffsetDateTime.parse( iso.trim() );
    } catch ( RuntimeException e ) {
      try {
        t = LocalDateTime.parse( iso.trim() );
      } catch ( RuntimeException e2 ) {
        return iso;
      }
    }
    try {
      String f = DateTimeFormatter.ofPattern( r.s( "fecha" ), locale ).format( t );
      // los meses van en minuscula en castellano y portugues (los datos COMPAT dan "Outubro")
      return "es".equals( idioma ) || "pt".equals( idioma ) ? f.toLowerCase( locale ) : f;
    } catch ( RuntimeException e ) {
      return iso;
    }
  }
}
