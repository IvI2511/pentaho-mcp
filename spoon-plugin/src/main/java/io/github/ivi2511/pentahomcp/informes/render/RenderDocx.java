package io.github.ivi2511.pentahomcp.informes.render;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.namespace.QName;

import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblCellMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.NumberingDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STOnOff;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;

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
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Capitulo;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.SimboloIdc;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Word (.docx) con Apache POI 4.1.1 XWPF y los poi-ooxml-schemas "lite" de PDI 9.4.
 * <ul>
 * <li>Portada con la banda del color primario (tabla de una fila: Word y LibreOffice la respetan), logo,
 * datos de la seccion cover y avisos del informe.</li>
 * <li>Indice: un campo TOC real que Word rehace al abrir (settings updateFields: Word pregunta si actualiza
 * los campos). Hasta entonces muestra los titulos como hipervinculos, sin numeros de pagina.</li>
 * <li>Titulos con los estilos Heading 1-3 de Word (panel de navegacion e indice). Cada seccion abre pagina
 * con pageBreakBefore, salvo las cortas ({@link Html#corta}). Nunca hay saltos de pagina manuales: asi no
 * salen paginas en blanco.</li>
 * <li>Tablas a todo el ancho, con la cabecera repetida en cada pagina, filas que no se parten, anchos por
 * contenido y filas cebreadas.</li>
 * <li>Figuras PNG (lienzo, Mermaid a escala 2, graficos via Batik a ~2,7 px por punto) ajustadas al ancho
 * y al alto util: una figura nunca es mas alta que la pagina. Si la seccion acaba de empezar y la figura
 * entra achicandose poco, se achica para no dejar el titulo solo en una pagina.</li>
 * <li>Avisos como recuadros con borde izquierdo (estilos PliveAviso*), parrafos de IA rotulados, notas.</li>
 * <li>Encabezado con el titulo; pie con el simbolo IDC, «Pentaho Live · IDC Studios» y «Pagina X de Y»
 * (campos PAGE y NUMPAGES). La portada va sin encabezado ni pie.</li>
 * </ul>
 * Lo que falta en el jar lite (CTPageSz, CTPageMar, CTCompat) se escribe con XmlCursor o se agrega al
 * settings.xml del zip ya escrito. Los estilos y las vinetas van como XML con los colores del tema.
 * <p>
 * Los diagramas Mermaid llegan como PNG por el constructor (PrerenderMermaid.png); sin PNG va la tabla
 * alternativa. No conoce Kettle ni SWT. Corre en un worker. Escribe primero un ".tmp" y despues lo mueve.
 *
 * @dueño WP10
 */
public final class RenderDocx implements Renderizador {

  /** Area de Rotulos de este renderizador ({@code textos/docx_*.properties}). */
  public static final String AREA = "docx";
  /** Clave de {@link Formato} (argumento formats de build_report). */
  public static final String CLAVE = "docx";

  private final Map<String, byte[]> pngMermaid;
  private RenderPptx.ProveedorSvg proveedorSvg;

  /** @param pngMermaid id de Diagrama -> PNG (PrerenderMermaid.png, escala 2); null o sin la clave = tabla alternativa. */
  public RenderDocx( Map<String, byte[]> pngMermaid ) {
    this.pngMermaid = pngMermaid == null ? Collections.<String, byte[]>emptyMap()
      : Collections.unmodifiableMap( new HashMap<>( pngMermaid ) );
  }

  /** Cambia de donde salen los SVG de los graficos (pruebas). Devuelve this. */
  public RenderDocx conSvgGraficos( RenderPptx.ProveedorSvg p ) {
    this.proveedorSvg = p;
    return this;
  }

  /** Formato.DOCX (se busca por clave). */
  @Override
  public Formato formato() {
    return Formato.DOCX;
  }

  /** null si POI puede escribir Word en este proceso; si no, el motivo traducido (como RenderPptx.problema). */
  public static String problema( String idioma ) {
    Rotulos r = Rotulos.de( AREA, idioma );
    String version;
    try {
      Class.forName( "org.apache.poi.xwpf.usermodel.XWPFDocument", false, RenderDocx.class.getClassLoader() );
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
    // el loader del plugin durante toda la exportacion (como RenderPptx: evita el JAXBException de POI)
    Thread hilo = Thread.currentThread();
    ClassLoader antes = hilo.getContextClassLoader();
    hilo.setContextClassLoader( RenderDocx.class.getClassLoader() );
    try ( XWPFDocument doc = new XWPFDocument() ) {
      final String idiomaGraficos = idioma;
      RenderPptx.ProveedorSvg svgs = proveedorSvg != null ? proveedorSvg
        : ( g, te, ancho ) -> GraficosSvg.svg( g, te, ancho, idiomaGraficos );
      Escritor e = new Escritor( doc, inf, t, pngMermaid, svgs, pr );
      e.armar();
      cancelar( pr );
      pr.paso( Rotulos.de( AREA, idioma ).s( "progreso_escribir" ), 0, 0 );
      try ( OutputStream os = new FileOutputStream( tmp ) ) {
        doc.write( os );
      }
      modoActual( tmp );
      mover( tmp, fin );
      Resultado r = new Resultado().archivo( fin );
      r.avisos.addAll( e.avisos );
      return r;
    } finally {
      if ( tmp.exists() && !tmp.delete() ) {
        tmp.deleteOnExit();
      }
      hilo.setContextClassLoader( antes );
    }
  }

  static void cancelar( Progreso p ) {
    if ( p != null && p.cancelado() ) {
      throw new CancellationException( "Word" );
    }
  }

  /**
   * Word abre en «Modo de compatibilidad» (Word 2007) un .docx sin compatSetting. XWPFSettings no deja
   * tocar CTSettings (y CTCompat no esta en el jar lite): se agrega al settings.xml del zip ya escrito.
   */
  static void modoActual( File docx ) throws IOException {
    File otro = new File( docx.getPath() + ".2" );
    try {
      try ( ZipFile z = new ZipFile( docx ); ZipOutputStream out = new ZipOutputStream( new FileOutputStream( otro ) ) ) {
        Enumeration<? extends ZipEntry> es = z.entries();
        while ( es.hasMoreElements() ) {
          ZipEntry e = es.nextElement();
          byte[] datos;
          try ( InputStream in = z.getInputStream( e ) ) {
            datos = IOUtils.toByteArray( in );
          }
          if ( "word/settings.xml".equals( e.getName() ) ) {
            String x = new String( datos, StandardCharsets.UTF_8 );
            if ( !x.contains( "compatibilityMode" ) && x.contains( "</w:settings>" ) ) {
              x = x.replace( "</w:settings>", "<w:compat><w:compatSetting w:name=\"compatibilityMode\" "
                + "w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>" );
              datos = x.getBytes( StandardCharsets.UTF_8 );
            }
          }
          out.putNextEntry( new ZipEntry( e.getName() ) );
          out.write( datos );
          out.closeEntry();
        }
      }
      Files.move( otro.toPath(), docx.toPath(), StandardCopyOption.REPLACE_EXISTING );
    } finally {
      if ( otro.exists() && !otro.delete() ) {
        otro.deleteOnExit();
      }
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

  /** La seccion cover (la primera), o null. */
  static Seccion portada( Informe inf ) {
    for ( Seccion s : RenderHtml.ordenadas( inf ) ) {
      if ( SeccionId.PORTADA.equals( s.id ) ) {
        return s;
      }
    }
    return null;
  }

  /** Las secciones despues de la portada, en el orden del informe (omitidas incluidas). */
  static List<Seccion> cuerpo( Informe inf ) {
    List<Seccion> l = new ArrayList<>();
    Seccion portada = portada( inf );
    for ( Seccion s : RenderHtml.ordenadas( inf ) ) {
      if ( s != portada ) {
        l.add( s );
      }
    }
    return l;
  }

  /** Las que se dibujan: sin las omitidas. */
  static List<Seccion> vivas( List<Seccion> secciones ) {
    List<Seccion> l = new ArrayList<>();
    for ( Seccion s : secciones ) {
      if ( !Seccion.OMITIDA.equals( s.estado ) ) {
        l.add( s );
      }
    }
    return l;
  }

  // ==================================================================================== el escritor

  /** Estado de una escritura. */
  static final class Escritor {

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** Margenes en twips (1/20 pt): 2,2 cm arriba y abajo, 2 cm a los lados; encabezado y pie a 1 cm. */
    static final int MARGEN_V = 1247;
    static final int MARGEN_H = 1134;
    static final int MARGEN_HF = 567;
    /** Lo que se reserva bajo y sobre una figura sola en su pagina (titulo del bloque y nota), en puntos. */
    static final double RESERVA_FIGURA = 90;
    /** Lo que se reserva bajo una figura que sigue al titulo de la seccion (su nota), en puntos. */
    static final double RESERVA_NOTA = 36;
    /** Una figura se achica para quedar junto al titulo de la seccion solo si conserva esta fraccion. */
    static final double ACHICAR_MIN = 0.6;
    /** Un PNG de Mermaid o del lienzo (escala 2) puede agrandarse hasta su tamano en pixeles. */
    static final double AMPLIAR_MAX = 2.0;
    /** Pixeles del PNG de un diagrama propio por px CSS del tamano final: unos 200 ppp en la hoja. */
    static final double PPP_DIAGRAMA = 2.1;
    /** Ancho medio de un caracter de Segoe UI en fracciones del cuerpo (estimaciones). */
    static final double ANCHO_CARACTER = 0.55;
    /** Lo mismo con margen, para la palabra mas larga de una columna (no se tiene que partir). */
    static final double ANCHO_PALABRA = 0.64;
    static final String VINETAS = "1";

    final XWPFDocument doc;
    final Informe inf;
    final Tema tema;
    final Map<String, byte[]> pngs;
    final RenderPptx.ProveedorSvg proveedorSvg;
    final Progreso progreso;
    final String idioma;
    final Rotulos r;
    final Rotulos rr;
    final Rotulos rp;
    final Locale locale;
    final List<String> avisos = new ArrayList<>();
    final String primario;
    final String acento;
    final String tenue = hex( Tema.TEXTO_TENUE );
    final String borde = hex( Tema.BORDE );
    final String cebra;
    final String fondoTarjeta;
    final boolean letter;
    /** Pagina y area util en puntos. */
    final double anchoPagina;
    final double altoPagina;
    final double anchoUtil;
    final double altoUtil;
    final int anchoUtilTw;
    private final Set<String> marcadores = new HashSet<>();
    private int marcador;
    private int imagenes;
    private int diagramasComoTabla;
    private int graficosComoTabla;
    /**
     * Puntos (estimados) que ya ocupa la pagina desde el ultimo salto de seccion; NaN = no se sabe (despues
     * de una tabla, una figura o una seccion corta). Sirve para no dejar un titulo solo en una pagina.
     */
    private double ocupado = Double.NaN;
    /**
     * La ultima figura, que todavia sigue «pegada» a lo que venga (keepNext del estilo PliveFigura): solo
     * vale para su nota o un aviso corto. Si viene otra figura, un titulo o una tabla, se suelta; si no, una
     * cadena figura + figura + titulo + tabla que no entra en una hoja manda la figura a la pagina siguiente
     * y deja el titulo de la seccion solo.
     */
    private XWPFParagraph figuraPegada;
    /** La ultima tabla sin pie y su espacio: si la sigue un aviso, su ultima fila se pega a el. */
    private XWPFTable tablaSuelta;
    private XWPFParagraph espacioTabla;

    Escritor( XWPFDocument doc, Informe inf, Tema tema, Map<String, byte[]> pngMermaid, RenderPptx.ProveedorSvg svgs,
              Progreso p ) {
      this.doc = doc;
      this.pngs = pngMermaid == null ? Collections.<String, byte[]>emptyMap() : pngMermaid;
      this.inf = inf;
      this.tema = tema;
      this.proveedorSvg = svgs;
      this.progreso = p;
      this.idioma = Rotulos.normalizar( inf.idioma );
      this.r = Rotulos.de( AREA, idioma );
      this.rr = Rotulos.de( RenderHtml.AREA, idioma );
      this.rp = Rotulos.de( RenderPptx.AREA, idioma );
      this.locale = Rotulos.locale( idioma );
      Tema.Colores c = tema.colores == null ? new Tema.Colores() : tema.colores;
      this.primario = hex( Html.color( c.primario, Tema.PRIMARIO ) );
      this.acento = hex( Html.color( c.acento, Tema.ACENTO ) );
      this.cebra = hex( Html.claro( "#" + primario, 0.07f ) );
      this.fondoTarjeta = hex( Html.claro( "#" + primario, 0.05f ) );
      this.letter = Tema.LETTER.equalsIgnoreCase( tema.pagina );
      int anchoTw = letter ? 12240 : 11906;
      int altoTw = letter ? 15840 : 16838;
      this.anchoPagina = anchoTw / 20.0;
      this.altoPagina = altoTw / 20.0;
      this.anchoUtilTw = anchoTw - 2 * MARGEN_H;
      this.anchoUtil = anchoUtilTw / 20.0;
      this.altoUtil = ( altoTw - 2 * MARGEN_V ) / 20.0;
    }

    // ------------------------------------------------------------------ armado

    void armar() throws Exception {
      propiedades();
      estilos();
      vinetas();
      doc.enforceUpdateFields(); // Word rehace el indice y los numeros de pagina al abrir

      // un informe simple da un solo grupo (como siempre); uno combinado, la parte general y un capitulo por
      // documento: Heading 1 por documento y Heading 2 numerado («2.3») por seccion
      List<Capitulos.Grupo> grupos = Capitulos.grupos( inf );
      Capitulos.Grupo general = grupos.isEmpty() || grupos.get( 0 ).capitulo != null ? null : grupos.get( 0 );
      portada( general != null ? general.portada : RenderDocx.portada( inf ) );
      indice( grupos );

      int total = 0;
      for ( Capitulos.Grupo g : grupos ) {
        total += g.vivas().size();
      }
      int hecho = 0;
      for ( Capitulos.Grupo g : grupos ) {
        int nivel = 1;
        if ( g.capitulo != null ) {
          capitulo( g );
          nivel = 2;
        }
        List<Seccion> vivas = g.vivas();
        for ( int i = 0; i < vivas.size(); i++ ) {
          cancelar( progreso );
          Seccion s = vivas.get( i );
          progreso.paso( r.f( "progreso", titulo( s ) ), hecho++, total );
          boolean salto = i == 0 || !Html.corta( s );
          seccion( s, Capitulos.numero( g, i ), nivel, salto );
        }
      }
      if ( diagramasComoTabla > 0 ) {
        avisos.add( r.f( "aviso_diagramas", diagramasComoTabla ) );
      }
      if ( graficosComoTabla > 0 ) {
        avisos.add( r.f( "aviso_graficos", graficosComoTabla ) );
      }
      paginaYPies();
    }

    // ------------------------------------------------------------------ propiedades, estilos, vinetas

    void propiedades() {
      try {
        POIXMLProperties.CoreProperties cp = doc.getProperties().getCoreProperties();
        cp.setTitle( PptxDiapos.limpio( tituloInforme() ) );
        if ( inf.subtitulo != null ) {
          cp.setDescription( PptxDiapos.limpio( inf.subtitulo ) );
        }
        String autor = tema.identidad == null ? null : tema.identidad.autor;
        cp.setCreator( PptxDiapos.vacio( autor ) ? Tema.PIE : PptxDiapos.limpio( autor ) );
        cp.getUnderlyingProperties().setLanguageProperty( locale.toLanguageTag() );
        doc.getProperties().getExtendedProperties().getUnderlyingProperties().setApplication( "Pentaho Live" );
      } catch ( RuntimeException e ) {
        // las propiedades son un extra
      }
    }

    /** styles.xml entero: fuente, colores del tema, Heading 1-3 reales, TOC 1-3, recuadros y notas. */
    void estilos() throws Exception {
      String a10 = hex( Html.claro( "#" + acento, 0.10f ) );
      String a40 = hex( Html.claro( "#" + acento, 0.40f ) );
      String tabDer = String.valueOf( anchoUtilTw );
      String lang = locale.toLanguageTag();
      String x = "<w:styles xmlns:w=\"" + W + "\">"
        + "<w:docDefaults><w:rPrDefault><w:rPr>"
        + "<w:rFonts w:ascii=\"Segoe UI\" w:hAnsi=\"Segoe UI\" w:eastAsia=\"Segoe UI\" w:cs=\"Segoe UI\"/>"
        + "<w:color w:val=\"1F2933\"/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/>"
        + "<w:lang w:val=\"" + lang + "\" w:eastAsia=\"" + lang + "\"/>"
        + "</w:rPr></w:rPrDefault>"
        + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>"
        + "</w:docDefaults>"
        + estilo( "paragraph", "Normal", "Normal", null, true, "", "" )
        + estilo( "paragraph", "Title", "Title", "Normal", true,
          "<w:spacing w:before=\"60\" w:after=\"60\" w:line=\"240\" w:lineRule=\"auto\"/>",
          "<w:b/><w:color w:val=\"FFFFFF\"/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/>" )
        + estilo( "paragraph", "Subtitle", "Subtitle", "Normal", true, "<w:spacing w:after=\"60\"/>",
          "<w:color w:val=\"" + hex( Html.claro( "#" + primario, 0.18f ) ) + "\"/><w:sz w:val=\"28\"/>" )
        + estilo( "paragraph", "Heading1", "heading 1", "Normal", true,
          "<w:keepNext/><w:keepLines/><w:pBdr><w:bottom w:val=\"single\" w:sz=\"12\" w:space=\"4\" w:color=\""
            + primario + "\"/></w:pBdr><w:spacing w:before=\"0\" w:after=\"200\" w:line=\"240\" w:lineRule=\"auto\"/>"
            + "<w:outlineLvl w:val=\"0\"/>",
          "<w:b/><w:color w:val=\"" + primario + "\"/><w:sz w:val=\"34\"/><w:szCs w:val=\"34\"/>" )
        + estilo( "paragraph", "Heading2", "heading 2", "Normal", true,
          "<w:keepNext/><w:keepLines/><w:spacing w:before=\"240\" w:after=\"100\" w:line=\"240\" w:lineRule=\"auto\"/>"
            + "<w:outlineLvl w:val=\"1\"/>",
          "<w:b/><w:color w:val=\"1F2933\"/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/>" )
        + estilo( "paragraph", "Heading3", "heading 3", "Normal", true,
          "<w:keepNext/><w:keepLines/><w:spacing w:before=\"200\" w:after=\"80\"/><w:outlineLvl w:val=\"2\"/>",
          "<w:b/><w:color w:val=\"1F2933\"/><w:sz w:val=\"23\"/>" )
        + estilo( "paragraph", "PliveIndiceTitulo", "Plive Indice", "Normal", false,
          "<w:keepNext/><w:pageBreakBefore/><w:spacing w:after=\"160\"/>",
          "<w:b/><w:caps/><w:color w:val=\"" + tenue + "\"/><w:spacing w:val=\"20\"/><w:sz w:val=\"24\"/>" )
        + estilo( "paragraph", "TOC1", "toc 1", "Normal", false,
          "<w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"" + tabDer + "\"/></w:tabs>"
            + "<w:spacing w:before=\"60\" w:after=\"60\"/>",
          "<w:b/><w:color w:val=\"" + primario + "\"/>" )
        + estilo( "paragraph", "TOC2", "toc 2", "Normal", false,
          "<w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"" + tabDer + "\"/></w:tabs>"
            + "<w:spacing w:after=\"40\"/><w:ind w:left=\"240\"/>", "" )
        + estilo( "paragraph", "TOC3", "toc 3", "Normal", false,
          "<w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"" + tabDer + "\"/></w:tabs>"
            + "<w:spacing w:after=\"20\"/><w:ind w:left=\"480\"/>", "<w:sz w:val=\"18\"/>" )
        + estilo( "paragraph", "Header", "header", "Normal", false,
          "<w:tabs><w:tab w:val=\"right\" w:pos=\"" + tabDer + "\"/></w:tabs><w:spacing w:after=\"0\"/>",
          "<w:color w:val=\"" + tenue + "\"/><w:sz w:val=\"16\"/>" )
        + estilo( "paragraph", "Footer", "footer", "Normal", false,
          "<w:tabs><w:tab w:val=\"right\" w:pos=\"" + tabDer + "\"/></w:tabs><w:spacing w:after=\"0\"/>",
          "<w:color w:val=\"" + tenue + "\"/><w:sz w:val=\"15\"/>" )
        + estilo( "paragraph", "PliveResumen", "Plive Resumen", "Normal", false,
          "<w:pBdr><w:left w:val=\"single\" w:sz=\"24\" w:space=\"6\" w:color=\"" + primario + "\"/></w:pBdr>"
            + "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + fondoTarjeta + "\"/><w:spacing w:after=\"200\"/>"
            + "<w:ind w:left=\"160\" w:right=\"120\"/>",
          "<w:sz w:val=\"22\"/>" )
        + aviso( "PliveAvisoInfo", acento, a10 )
        + aviso( "PliveAvisoWarn", "D69E2E", "FFF8E6" )
        + aviso( "PliveAvisoError", "C53030", "FDEDED" )
        + estilo( "paragraph", "PliveIa", "Plive IA", "Normal", false,
          "<w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"8\" w:color=\"" + a40 + "\"/></w:pBdr>"
            + "<w:ind w:left=\"200\"/>", "" )
        + estilo( "paragraph", "PliveNota", "Plive Nota", "Normal", false, "<w:spacing w:before=\"40\" w:after=\"160\"/>",
          "<w:i/><w:color w:val=\"" + tenue + "\"/><w:sz w:val=\"17\"/>" )
        + estilo( "paragraph", "PliveFigura", "Plive Figura", "Normal", false,
          "<w:keepNext/><w:jc w:val=\"center\"/><w:spacing w:before=\"60\" w:after=\"60\"/>", "" )
        + estilo( "paragraph", "PliveCelda", "Plive Celda", "Normal", false,
          "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>",
          "<w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/>" )
        + estilo( "paragraph", "PliveDato", "Plive Dato", "Normal", false,
          "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"3\" w:color=\"" + fondoTarjeta + "\"/></w:pBdr>"
            + "<w:spacing w:after=\"80\"/>", "" )
        + estilo( "paragraph", "ListParagraph", "List Paragraph", "Normal", true,
          "<w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/><w:contextualSpacing/>", "" )
        + "<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\">"
        + "<w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/></w:style>"
        + "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>"
        + "<w:basedOn w:val=\"DefaultParagraphFont\"/><w:rPr><w:color w:val=\"" + acento + "\"/></w:rPr></w:style>"
        + "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/>"
        + "<w:semiHidden/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar>"
        + "<w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/>"
        + "<w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"
        + "</w:styles>";
      XWPFStyles st = doc.createStyles();
      // CTStyles.Factory.parse deja la raiz anidada (<w:styles><w:styles>): se parsea el documento entero
      st.setStyles( StylesDocument.Factory.parse( x ).getStyles() );
    }

    private static String estilo( String tipo, String id, String nombre, String base, boolean qFormat, String pPr,
                                  String rPr ) {
      StringBuilder sb = new StringBuilder( "<w:style w:type=\"" ).append( tipo ).append( '"' );
      if ( "Normal".equals( id ) ) {
        sb.append( " w:default=\"1\"" );
      }
      sb.append( " w:styleId=\"" ).append( id ).append( "\"><w:name w:val=\"" ).append( nombre ).append( "\"/>" );
      if ( base != null ) {
        sb.append( "<w:basedOn w:val=\"" ).append( base ).append( "\"/>" );
      }
      if ( id.startsWith( "Heading" ) || "Title".equals( id ) || "Subtitle".equals( id ) ) {
        sb.append( "<w:next w:val=\"Normal\"/>" );
      }
      if ( id.startsWith( "Heading" ) ) {
        sb.append( "<w:uiPriority w:val=\"9\"/>" );
      }
      if ( qFormat ) {
        sb.append( "<w:qFormat/>" );
      }
      if ( !pPr.isEmpty() ) {
        sb.append( "<w:pPr>" ).append( pPr ).append( "</w:pPr>" );
      }
      if ( !rPr.isEmpty() ) {
        sb.append( "<w:rPr>" ).append( rPr ).append( "</w:rPr>" );
      }
      return sb.append( "</w:style>" ).toString();
    }

    private static String aviso( String id, String color, String fondo ) {
      return estilo( "paragraph", id, id, "Normal", false,
        "<w:keepLines/><w:pBdr><w:left w:val=\"single\" w:sz=\"32\" w:space=\"8\" w:color=\"" + color + "\"/></w:pBdr>"
          + "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + fondo + "\"/>"
          + "<w:spacing w:before=\"60\" w:after=\"160\"/><w:ind w:left=\"200\" w:right=\"120\"/>", "" );
    }

    /** numbering.xml: una lista de vinetas con el punto del color de acento. */
    void vinetas() throws Exception {
      String x = "<w:numbering xmlns:w=\"" + W + "\">"
        + "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>"
        + "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/>"
        + "<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"360\" w:hanging=\"240\"/></w:pPr>"
        + "<w:rPr><w:rFonts w:ascii=\"Segoe UI\" w:hAnsi=\"Segoe UI\"/><w:color w:val=\"" + acento + "\"/></w:rPr></w:lvl>"
        + "</w:abstractNum><w:num w:numId=\"" + VINETAS + "\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>";
      XWPFNumbering n = doc.createNumbering();
      n.setNumbering( NumberingDocument.Factory.parse( x ).getNumbering() );
    }

    // ------------------------------------------------------------------ portada e indice

    String tituloInforme() {
      if ( !PptxDiapos.vacio( inf.titulo ) ) {
        return inf.titulo.trim();
      }
      if ( !PptxDiapos.vacio( inf.nombreDoc ) ) {
        return inf.nombreDoc.trim();
      }
      return rr.s( "report" );
    }

    void portada( Seccion cover ) throws Exception {
      Imagen logo = null;
      List<String> datos = new ArrayList<>();
      if ( cover != null ) {
        for ( Bloque b : Html.lista( cover.bloques ) ) {
          if ( b instanceof Imagen && logo == null && ( (Imagen) b ).datos != null ) {
            logo = (Imagen) b;
          } else if ( b instanceof Lista ) {
            for ( String it : Html.lista( ( (Lista) b ).items ) ) {
              if ( !PptxDiapos.vacio( it ) ) {
                datos.add( it );
              }
            }
          }
        }
      }
      if ( logo == null && tema.logoDatos != null ) {
        logo = new Imagen( tema.logoMime, tema.logoDatos, 0, 0, 1f );
      }
      // banda: tabla de una fila (texto | logo) con el color primario
      XWPFTable t = doc.createTable( 1, logo != null ? 2 : 1 );
      int anchoLogo = logo != null ? 2600 : 0;
      tablaBase( t, logo != null ? new int[] { anchoUtilTw - anchoLogo, anchoLogo } : new int[] { anchoUtilTw },
        false );
      CTTblPr tp = t.getCTTbl().getTblPr();
      sinBordes( tp );
      margenes( tp, 400, 360 );
      XWPFTableCell c = t.getRow( 0 ).getCell( 0 );
      c.setColor( primario );
      XWPFParagraph p = c.getParagraphs().get( 0 );
      String tipo = Informe.JOB.equals( inf.tipoDoc ) ? rr.s( "doc_job" )
        : Informe.TRANSFORMACION.equals( inf.tipoDoc ) ? rr.s( "doc_transformation" ) : null;
      boolean bandaOscura = RenderHtml.luminancia( "#" + primario ) < 0.40;
      String textoBanda = bandaOscura ? "FFFFFF" : "1F2933";
      String tenueBanda = bandaOscura ? hex( Html.claro( "#" + primario, 0.42f ) ) : tenue;
      if ( tipo != null || inf.nombreDoc != null ) {
        String linea = ( tipo == null ? "" : tipo.toUpperCase( locale ) )
          + ( tipo != null && inf.nombreDoc != null ? "  ·  " : "" ) + ( inf.nombreDoc != null ? inf.nombreDoc : "" );
        correr( p, linea, 8.5, tenueBanda, false, false );
        p.setSpacingAfter( 80 );
      } else {
        p.setSpacingAfter( 0 );
      }
      XWPFParagraph pt = c.addParagraph();
      pt.setStyle( "Title" );
      correr( pt, tituloInforme(), tituloInforme().length() > 50 ? 22 : 26, textoBanda, true, false );
      if ( !PptxDiapos.vacio( inf.subtitulo ) ) {
        XWPFParagraph ps = c.addParagraph();
        ps.setStyle( "Subtitle" );
        correr( ps, inf.subtitulo, 13, bandaOscura ? hex( Html.claro( "#" + primario, 0.18f ) ) : "1F2933", false,
          false );
      }
      String fecha = fecha( inf.generado );
      if ( fecha != null ) {
        XWPFParagraph pf = c.addParagraph();
        pf.setSpacingBefore( 160 );
        pf.setSpacingAfter( 0 );
        correr( pf, rr.f( "generated_on", fecha ), 9.5, tenueBanda, false, false );
      }
      c.setVerticalAlignment( XWPFTableCell.XWPFVertAlign.BOTTOM );
      if ( logo != null ) {
        XWPFTableCell cl = t.getRow( 0 ).getCell( 1 );
        cl.setColor( primario );
        cl.setVerticalAlignment( XWPFTableCell.XWPFVertAlign.TOP );
        XWPFParagraph pl = cl.getParagraphs().get( 0 );
        pl.setAlignment( ParagraphAlignment.RIGHT );
        pl.setSpacingAfter( 0 );
        try {
          int[] wh = logo.ancho > 0 && logo.alto > 0 ? new int[] { logo.ancho, logo.alto } : PptxDiapos.medir(
            logo.datos );
          double esc = Math.min( 1.0, Math.min( 100.0 / wh[ 0 ], 54.0 / wh[ 1 ] ) );
          imagen( pl.createRun(), logo.datos, logo.mime, wh[ 0 ] * esc, wh[ 1 ] * esc, logo.alt != null ? logo.alt
            : "logo" );
        } catch ( Exception e ) {
          // un logo roto no frena el informe
        }
      }
      // datos de la portada: los de la seccion cover; si no hay, los del tema
      if ( datos.isEmpty() ) {
        String d = !PptxDiapos.vacio( inf.archivo ) ? new File( inf.archivo ).getName() : inf.nombreDoc;
        if ( !PptxDiapos.vacio( d ) ) {
          datos.add( rp.f( "documento", d ) );
        }
        Tema.Identidad id = tema.identidad;
        if ( id != null ) {
          agregarSi( datos, "autor", id.autor );
          agregarSi( datos, "institucion", id.institucion );
          agregarSi( datos, "curso", id.curso );
          agregarSi( datos, "contacto", id.contacto );
        }
      }
      espacio( 240 );
      for ( String d : datos ) {
        XWPFParagraph pd = doc.createParagraph();
        pd.setStyle( "PliveDato" );
        String linea = PptxDiapos.limpio( d );
        int dos = linea.indexOf( ": " );
        if ( dos > 0 && dos < 30 ) {
          correr( pd, linea.substring( 0, dos + 1 ) + " ", 10.5, tenue, false, false );
          conMarcas( pd, linea.substring( dos + 2 ), 10.5, null );
        } else {
          conMarcas( pd, linea, 10.5, null );
        }
      }
      if ( cover != null && !PptxDiapos.vacio( cover.resumen ) ) {
        XWPFParagraph pr = doc.createParagraph();
        pr.setSpacingBefore( 160 );
        correr( pr, cover.resumen, 10.5, tenue, false, false );
      }
      if ( cover != null ) {
        for ( Bloque b : Html.lista( cover.bloques ) ) {
          if ( b instanceof Parrafo || b instanceof Tabla || b instanceof Aviso ) {
            bloque( b, 3 );
          }
        }
      }
      for ( String a : Html.lista( inf.avisos ) ) {
        if ( !PptxDiapos.vacio( a ) ) {
          aviso( Aviso.WARN, a );
        }
      }
    }

    private void agregarSi( List<String> l, String clave, String valor ) {
      if ( !PptxDiapos.vacio( valor ) ) {
        l.add( rp.f( clave, valor ) );
      }
    }

    /**
     * Pagina de indice: el titulo, un campo TOC (Word lo rehace al abrir; hasta entonces muestra los titulos
     * con hipervinculo y sin numero de pagina), una ayuda y las secciones omitidas.
     */
    void indice( List<Capitulos.Grupo> grupos ) {
      XWPFParagraph h = doc.createParagraph();
      h.setStyle( "PliveIndiceTitulo" );
      correr( h, texto( "contents" ), 0, null, false, false );
      List<String[]> entradas = new ArrayList<>(); // estilo, texto, marcador
      List<String> omitidas = new ArrayList<>();
      for ( Capitulos.Grupo g : grupos ) {
        if ( g.capitulo != null ) {
          entradas.add( new String[] { "TOC1", g.numero + "  " + tituloCapitulo( g.capitulo ), marcadorCapitulo(
            g.capitulo ) } );
        }
        int i = 0;
        for ( Seccion s : g.secciones ) {
          if ( Seccion.OMITIDA.equals( s.estado ) ) {
            String n = titulo( s ) + ( g.capitulo != null ? " (" + tituloCapitulo( g.capitulo ) + ")" : "" );
            omitidas.add( n + ( PptxDiapos.vacio( s.motivo ) ? "" : ": " + s.motivo.trim() ) );
          } else {
            String num = Capitulos.numero( g, i++ );
            entradas.add( new String[] { g.capitulo != null ? "TOC2" : "TOC1", ( num == null ? "" : num + "  " )
              + titulo( s ), marcadorDe( s ) } );
          }
        }
      }
      if ( entradas.isEmpty() ) {
        entradas.add( new String[] { "TOC1", " ", null } );
      }
      for ( int i = 0; i < entradas.size(); i++ ) {
        String[] e = entradas.get( i );
        XWPFParagraph p = doc.createParagraph();
        p.setStyle( e[ 0 ] );
        CTP x = p.getCTP();
        if ( i == 0 ) {
          campo( x, STFldCharType.BEGIN, true );
          CTR ri = x.addNewR();
          CTText it = ri.addNewInstrText();
          it.setSpace( SpaceAttribute.Space.PRESERVE );
          it.setStringValue( " TOC \\o \"" + ( inf.combinado() ? "1-2" : "1-1" ) + "\" \\h \\z \\u " );
          campo( x, STFldCharType.SEPARATE, false );
        }
        if ( e[ 2 ] != null ) {
          CTHyperlink hl = x.addNewHyperlink();
          hl.setAnchor( e[ 2 ] );
          hl.addNewR().addNewT().setStringValue( PptxDiapos.limpio( e[ 1 ] ) );
        } else {
          x.addNewR().addNewT().setStringValue( e[ 1 ] );
        }
        if ( i == entradas.size() - 1 ) {
          campo( x, STFldCharType.END, false );
        }
      }
      XWPFParagraph ayuda = doc.createParagraph();
      ayuda.setStyle( "PliveNota" );
      ayuda.setSpacingBefore( 200 );
      correr( ayuda, r.s( "indice_ayuda" ), 0, null, false, false );
      if ( !omitidas.isEmpty() ) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle( "PliveNota" );
        correr( p, rr.s( "omitted_sections" ) + ": " + String.join( "; ", omitidas ) + ".", 0, null, false, false );
      }
    }

    private static void campo( CTP p, STFldCharType.Enum tipo, boolean sucio ) {
      CTFldChar fc = p.addNewR().addNewFldChar();
      fc.setFldCharType( tipo );
      if ( sucio ) {
        fc.setDirty( STOnOff.TRUE );
      }
    }

    /** Marcador de la seccion: "sec_flow" (unico: si se repite el id, "sec_flow_2"). Estable por Seccion. */
    private final Map<Seccion, String> marcadorSeccion = new HashMap<>();

    String marcadorDe( Seccion s ) {
      String m = marcadorSeccion.get( s );
      if ( m == null ) {
        String base = marcador( Capitulos.ancla( s ) ); // "sec_flow"; en un capitulo, "sec_d2_flow"
        m = base;
        for ( int i = 2; !marcadores.add( m ); i++ ) {
          m = base + "_" + i;
        }
        marcadorSeccion.put( s, m );
      }
      return m;
    }

    /** Nombre de marcador valido en Word (letra inicial, [A-Za-z0-9_], hasta 40). */
    static String marcador( String ancla ) {
      String s = ancla.replaceAll( "[^A-Za-z0-9_]", "_" );
      if ( s.isEmpty() || !Character.isLetter( s.charAt( 0 ) ) ) {
        s = "s_" + s;
      }
      return s.length() > 36 ? s.substring( 0, 36 ) : s;
    }

    /** Marcador de la apertura de un capitulo: "cap_d2". */
    String marcadorCapitulo( Capitulo c ) {
      return marcador( "cap-" + c.id );
    }

    String tituloCapitulo( Capitulo c ) {
      return !PptxDiapos.vacio( c.titulo ) ? c.titulo.trim() : !PptxDiapos.vacio( c.nombreDoc ) ? c.nombreDoc
        .trim() : c.id;
    }

    // ------------------------------------------------------------------ capitulos y secciones

    /**
     * Apertura de un documento en un informe combinado: Heading 1 numerado en pagina nueva, tipo y nombre,
     * los datos de su portada, sus avisos y un mini indice con vinculos a sus secciones.
     */
    void capitulo( Capitulos.Grupo g ) throws Exception {
      Capitulo c = g.capitulo;
      XWPFParagraph h = titulo( 1, g.numero + "  " + tituloCapitulo( c ), marcadorCapitulo( c ), true );
      h.setSpacingAfter( 120 );
      String tipo = Informe.JOB.equals( c.tipoDoc ) ? rr.s( "doc_job" ) : rr.s( "doc_transformation" );
      XWPFParagraph pt = doc.createParagraph();
      correr( pt, tipo.toUpperCase( locale ) + ( PptxDiapos.vacio( c.nombreDoc ) ? "" : "  ·  " + c.nombreDoc
        .trim() ), 9, tenue, false, false );
      if ( !PptxDiapos.vacio( c.subtitulo ) ) {
        XWPFParagraph ps = doc.createParagraph();
        correr( ps, c.subtitulo, 12, null, false, false );
      }
      if ( g.portada != null ) {
        for ( Bloque b : Html.lista( g.portada.bloques ) ) {
          if ( b instanceof Lista ) {
            for ( String it : Html.lista( ( (Lista) b ).items ) ) {
              XWPFParagraph pd = doc.createParagraph();
              pd.setStyle( "PliveDato" );
              conMarcas( pd, PptxDiapos.limpio( it ), 10, null );
            }
          } else if ( b != null && !( b instanceof Imagen ) ) {
            bloque( b, 3 );
          }
        }
        if ( !PptxDiapos.vacio( g.portada.resumen ) ) {
          XWPFParagraph pr = doc.createParagraph();
          pr.setStyle( "PliveResumen" );
          conMarcas( pr, g.portada.resumen, 0, null );
        }
      }
      for ( String a : Html.lista( c.avisos ) ) {
        aviso( Aviso.WARN, a );
      }
      List<Seccion> vivas = g.vivas();
      for ( int i = 0; i < vivas.size(); i++ ) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle( "TOC2" );
        CTHyperlink hl = p.getCTP().addNewHyperlink();
        hl.setAnchor( marcadorDe( vivas.get( i ) ) );
        hl.addNewR().addNewT().setStringValue( PptxDiapos.limpio( Capitulos.numero( g, i ) + "  " + titulo( vivas
          .get( i ) ) ) );
      }
      ocupado = Double.NaN;
    }

    String titulo( Seccion s ) {
      if ( !PptxDiapos.vacio( s.titulo ) ) {
        return s.titulo.trim();
      }
      return SeccionId.titulo( s.id, idioma );
    }

    /**
     * Una seccion: titulo (Heading nivel, con su marcador para el indice), estado, resumen y bloques.
     *
     * @param numero      "2.3" en un capitulo de un informe combinado; null en un informe simple.
     * @param saltoPagina la seccion empieza en pagina nueva (pageBreakBefore, nunca un salto manual).
     */
    void seccion( Seccion s, String numero, int nivel, boolean saltoPagina ) throws Exception {
      String t = ( numero == null ? "" : numero + "  " ) + titulo( s );
      titulo( nivel, t, marcadorDe( s ), saltoPagina );
      if ( !saltoPagina ) {
        ocupado = Double.NaN; // sigue a otra seccion en la misma pagina: no se sabe cuanto queda
      }
      if ( Seccion.PARCIAL.equals( s.estado ) || Seccion.ERROR.equals( s.estado ) ) {
        boolean error = Seccion.ERROR.equals( s.estado );
        // el estado no va en el titulo: el campo TOC copiaria «Linaje de campos Parcial» al indice
        String estado = rr.s( error ? "status_error" : "status_partial" );
        aviso( error ? Aviso.ERROR : Aviso.WARN, PptxDiapos.vacio( s.motivo ) ? estado : estado + ": " + s.motivo
          .trim(), false );
      }
      if ( !PptxDiapos.vacio( s.resumen ) ) {
        for ( Html.Grupo g : Html.grupos( s.resumen ) ) {
          for ( String l : g.lineas ) {
            XWPFParagraph p = doc.createParagraph();
            p.setStyle( "PliveResumen" );
            conMarcas( p, l, 0, null );
            ocupar( altoTexto( l, 11, anchoUtil - 20 ) + 12 );
          }
        }
      }
      // en un combinado los titulos de bloque son Heading 3 tambien en la parte general: el TOC (1-2) no los lista
      int sub = inf.combinado() ? 3 : Math.min( 3, nivel + 1 );
      for ( Bloque b : Html.lista( s.bloques ) ) {
        if ( b == null ) {
          continue;
        }
        Bloque x = Html.sinRepetirResumen( b, s.resumen );
        if ( x != null ) {
          bloque( x, sub );
        }
      }
    }

    /** Un titulo Heading n con marcador (para el indice) y, si se pide, en pagina nueva. */
    XWPFParagraph titulo( int nivel, String texto, String nombreMarcador, boolean saltoPagina ) {
      soltarFigura();
      tablaSuelta = null;
      espacioTabla = null;
      XWPFParagraph h = doc.createParagraph();
      h.setStyle( "Heading" + nivel );
      if ( saltoPagina ) {
        h.setPageBreak( true );
        ocupado = 0;
      }
      CTP x = h.getCTP();
      BigInteger id = BigInteger.valueOf( ++marcador );
      if ( nombreMarcador != null ) {
        CTBookmark bs = x.addNewBookmarkStart();
        bs.setId( id );
        bs.setName( nombreMarcador );
      }
      XWPFRun run = h.createRun();
      run.setText( PptxDiapos.limpio( texto ) );
      if ( nombreMarcador != null ) {
        x.addNewBookmarkEnd().setId( id );
      }
      double cuerpo = nivel == 1 ? 17 : nivel == 2 ? 13 : 11.5;
      ocupar( altoTexto( texto, cuerpo, anchoUtil ) + ( nivel == 1 ? 18 : 16 ) );
      return h;
    }

    void bloque( Bloque b, int nivelTitulo ) throws Exception {
      if ( !( b instanceof Aviso ) ) {
        soltarFigura(); // la figura anterior solo sigue pegada a un aviso corto
      } else if ( tablaSuelta != null && PptxDiapos.vacio( b.titulo ) ) {
        pegarUltimaFila( tablaSuelta, espacioTabla ); // el aviso no queda solo despues de una tabla larga
      }
      tablaSuelta = null;
      espacioTabla = null;
      if ( !PptxDiapos.vacio( b.titulo ) ) {
        titulo( nivelTitulo, b.titulo.trim(), null, false );
      }
      if ( b instanceof Parrafo ) {
        texto( ( (Parrafo) b ).texto, b.ia ? "PliveIa" : null );
      } else if ( b instanceof Lista ) {
        for ( String it : Html.lista( ( (Lista) b ).items ) ) {
          XWPFParagraph p = doc.createParagraph();
          p.setStyle( b.ia ? "PliveIa" : "ListParagraph" );
          p.setNumID( new BigInteger( VINETAS ) );
          String l = PptxDiapos.limpio( it == null ? "" : it ).replace( '\n', ' ' );
          conMarcas( p, l, 0, null );
          ocupar( altoTexto( l, 10, anchoUtil - 36 ) + 3 );
        }
      } else if ( b instanceof Tabla ) {
        tabla( (Tabla) b );
      } else if ( b instanceof Indicadores ) {
        indicadores( (Indicadores) b );
      } else if ( b instanceof Aviso ) {
        Aviso a = (Aviso) b;
        aviso( a.nivel, a.texto );
      } else if ( b instanceof Diagrama ) {
        diagrama( (Diagrama) b );
      } else if ( b instanceof Grafico ) {
        grafico( (Grafico) b );
      } else if ( b instanceof Imagen ) {
        Imagen im = (Imagen) b;
        if ( !figuraSegura( im, im.alt ) ) {
          nota( rr.s( "image_unavailable" ) );
        }
      }
      if ( !PptxDiapos.vacio( b.nota ) ) {
        nota( b.nota.trim() );
      }
      if ( b.ia && tema.rotularIa ) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle( "PliveNota" );
        correr( p, rr.s( "ai_short" ) + "  ", 8, acento, true, false );
        correr( p, texto( "ai_label" ), 0, null, false, true );
        ocupar( 20 );
      }
    }

    // ------------------------------------------------------------------ texto

    /** Texto con poco Markdown: parrafos y vinetas ("- "). */
    void texto( String t, String estilo ) {
      if ( PptxDiapos.vacio( t ) ) {
        return;
      }
      for ( Html.Grupo g : Html.grupos( PptxDiapos.limpio( t ) ) ) {
        if ( g.lista ) {
          for ( String l : g.lineas ) {
            XWPFParagraph p = doc.createParagraph();
            p.setStyle( estilo != null ? estilo : "ListParagraph" );
            p.setNumID( new BigInteger( VINETAS ) );
            conMarcas( p, l, 0, null );
            ocupar( altoTexto( l, 10, anchoUtil - 36 ) + 3 );
          }
        } else {
          XWPFParagraph p = doc.createParagraph();
          if ( estilo != null ) {
            p.setStyle( estilo );
          }
          for ( int i = 0; i < g.lineas.size(); i++ ) {
            if ( i > 0 ) {
              p.createRun().addBreak();
            }
            conMarcas( p, g.lineas.get( i ), 0, null );
            ocupar( altoTexto( g.lineas.get( i ), 10, anchoUtil - 20 ) );
          }
          ocupar( 6 );
        }
      }
    }

    void aviso( String nivel, String texto ) {
      aviso( nivel, texto, true );
    }

    /** Recuadro de aviso; con {@code rotulo}, empieza con «Nota.», «Atención.» o «Error.» en negrita. */
    void aviso( String nivel, String texto, boolean rotulo ) {
      if ( PptxDiapos.vacio( texto ) ) {
        return;
      }
      String n = Aviso.ERROR.equals( nivel ) ? Aviso.ERROR : Aviso.WARN.equals( nivel ) ? Aviso.WARN : Aviso.INFO;
      String estilo = Aviso.ERROR.equals( n ) ? "PliveAvisoError" : Aviso.WARN.equals( n ) ? "PliveAvisoWarn"
        : "PliveAvisoInfo";
      XWPFParagraph p = doc.createParagraph();
      p.setStyle( estilo );
      String inicio = rotulo ? rr.s( "level_" + n ) + ". " : "";
      if ( rotulo ) {
        correr( p, inicio, 0, null, true, false );
      }
      boolean primero = true;
      for ( Html.Grupo g : Html.grupos( PptxDiapos.limpio( texto ) ) ) {
        for ( String l : g.lineas ) {
          if ( !primero ) {
            p.createRun().addBreak();
          }
          conMarcas( p, ( g.lista ? "• " : "" ) + l, 0, null );
          ocupar( altoTexto( ( primero ? inicio : "" ) + l, 10, anchoUtil - 30 ) );
          primero = false;
        }
      }
      ocupar( 12 );
    }

    void nota( String t ) {
      XWPFParagraph p = doc.createParagraph();
      p.setStyle( "PliveNota" );
      String[] lineas = PptxDiapos.limpio( t ).split( "\r?\n" );
      for ( int i = 0; i < lineas.length; i++ ) {
        if ( i > 0 ) {
          p.createRun().addBreak();
        }
        conMarcas( p, lineas[ i ], 0, null );
        ocupar( altoTexto( lineas[ i ], 8.5, anchoUtil ) );
      }
      ocupar( 10 );
    }

    XWPFParagraph espacio( int twips ) {
      XWPFParagraph p = doc.createParagraph();
      p.setSpacingAfter( 0 );
      p.setSpacingBefore( twips );
      return p;
    }

    /** Runs con **negrita** y *cursiva* (Html.trozos). size 0 / color null = los del estilo. */
    void conMarcas( XWPFParagraph p, String linea, double size, String color ) {
      for ( Html.Trozo t : Html.trozos( linea ) ) {
        correr( p, t.texto, size, color, t.estilo == Html.Estilo.NEGRITA, t.estilo == Html.Estilo.CURSIVA );
      }
    }

    /**
     * Uno o mas runs con el mismo formato. Un tabulador va en un run propio (w:tab): dentro del mismo run,
     * XmlBeans agrupa los w:t antes del w:tab y el texto quedaria desordenado. size 0 / color null = los del
     * estilo.
     */
    static XWPFRun correr( XWPFParagraph p, String t, double size, String color, boolean negrita, boolean cursiva ) {
      String[] partes = PptxDiapos.limpio( t ).split( "\t", -1 );
      XWPFRun ultimo = null;
      for ( int i = 0; i < partes.length; i++ ) {
        if ( i > 0 ) {
          formato( p.createRun(), size, color, negrita, cursiva ).addTab();
        }
        if ( !partes[ i ].isEmpty() || partes.length == 1 ) {
          ultimo = formato( p.createRun(), size, color, negrita, cursiva );
          ultimo.setText( partes[ i ] );
        }
      }
      return ultimo;
    }

    private static XWPFRun formato( XWPFRun run, double size, String color, boolean negrita, boolean cursiva ) {
      if ( negrita ) {
        run.setBold( true );
      }
      if ( cursiva ) {
        run.setItalic( true );
      }
      if ( color != null ) {
        run.setColor( color );
      }
      if ( size > 0 ) {
        tamano( run.getCTR(), size );
      }
      return run;
    }

    static void tamano( CTR r, double pt ) {
      CTRPr rp = r.isSetRPr() ? r.getRPr() : r.addNewRPr();
      BigInteger mitades = BigInteger.valueOf( Math.round( pt * 2 ) );
      ( rp.isSetSz() ? rp.getSz() : rp.addNewSz() ).setVal( mitades );
      ( rp.isSetSzCs() ? rp.getSzCs() : rp.addNewSzCs() ).setVal( mitades );
    }

    String texto( String clave ) {
      String v = inf.rotulos == null ? null : inf.rotulos.get( clave );
      return PptxDiapos.vacio( v ) ? rr.s( clave ) : v;
    }

    // ------------------------------------------------------------------ cuanto ocupa la pagina

    /** Suma a lo ocupado de la pagina (si se sabe). */
    private void ocupar( double puntos ) {
      if ( !Double.isNaN( ocupado ) ) {
        ocupado += puntos;
      }
    }

    /** Alto estimado (en puntos) de un texto de ese cuerpo en ese ancho, con el interlineado del estilo. */
    static double altoTexto( String t, double cuerpo, double ancho ) {
      int lineas = 0;
      for ( String l : PptxDiapos.limpio( t == null ? "" : t ).split( "\r?\n", -1 ) ) {
        double w = l.length() * cuerpo * ANCHO_CARACTER;
        lineas += Math.max( 1, (int) Math.ceil( w / Math.max( 1, ancho ) ) );
      }
      return lineas * cuerpo * 1.55;
    }

    // ------------------------------------------------------------------ tablas

    void tabla( Tabla t ) {
      List<String> cols = t.columnas == null ? new ArrayList<String>() : t.columnas;
      List<List<String>> filas = new ArrayList<>();
      for ( List<String> f : Html.lista( t.filas ) ) {
        filas.add( f == null ? new ArrayList<String>() : f );
      }
      int nCol = cols.size();
      for ( List<String> f : filas ) {
        nCol = Math.max( nCol, f.size() );
      }
      if ( nCol == 0 ) {
        return;
      }
      ocupado = Double.NaN; // el alto de las filas depende de como corte el texto
      double size = nCol > 6 ? 8 : 9;
      boolean cabecera = !cols.isEmpty();
      int[] anchos = anchos( cols, filas, nCol, size );
      XWPFTable tb = doc.createTable( filas.size() + ( cabecera ? 1 : 0 ), nCol );
      tablaBase( tb, anchos, true );
      int fila = 0;
      if ( cabecera ) {
        XWPFTableRow row = tb.getRow( 0 );
        row.setRepeatHeader( true );
        row.setCantSplitRow( true );
        for ( int j = 0; j < nCol; j++ ) {
          XWPFTableCell c = row.getCell( j );
          c.setColor( primario );
          celda( c, j < cols.size() ? cols.get( j ) : "", size, "FFFFFF", true, alinear( t.alineacion, j ) );
        }
        fila = 1;
      }
      int k = 0;
      for ( List<String> f : filas ) {
        XWPFTableRow row = tb.getRow( fila++ );
        row.setCantSplitRow( true );
        for ( int j = 0; j < nCol; j++ ) {
          XWPFTableCell c = row.getCell( j );
          if ( k % 2 == 1 ) {
            c.setColor( cebra );
          }
          celda( c, j < f.size() ? f.get( j ) : "", size, null, false, alinear( t.alineacion, j ) );
        }
        k++;
      }
      if ( !PptxDiapos.vacio( t.pie ) ) {
        pegarUltimaFila( tb, null ); // el pie no queda solo en la pagina siguiente
        nota( t.pie.trim() );
      } else {
        tablaSuelta = tb;
        espacioTabla = espacio( 60 ); // separa tablas seguidas (Word uniria dos tablas pegadas)
      }
    }

    /**
     * La ultima fila de la tabla (y el espacio que la sigue, si se da) con keepNext: va a la misma pagina
     * que lo que sigue. Asi una nota o un aviso corto despues de una tabla que llena la hoja no queda solo
     * en una pagina casi vacia: baja con la ultima fila (y la cabecera repetida).
     */
    void pegarUltimaFila( XWPFTable tb, XWPFParagraph espacio ) {
      if ( tb.getNumberOfRows() == 0 ) {
        return;
      }
      for ( XWPFTableCell c : tb.getRow( tb.getNumberOfRows() - 1 ).getTableCells() ) {
        for ( XWPFParagraph p : c.getParagraphs() ) {
          org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP()
            .getPPr() : p.getCTP().addNewPPr();
          ( ppr.isSetKeepNext() ? ppr.getKeepNext() : ppr.addNewKeepNext() ).setVal( STOnOff.TRUE );
        }
      }
      if ( espacio != null ) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = espacio.getCTP().isSetPPr() ? espacio
          .getCTP().getPPr() : espacio.getCTP().addNewPPr();
        ( ppr.isSetKeepNext() ? ppr.getKeepNext() : ppr.addNewKeepNext() ).setVal( STOnOff.TRUE );
      }
    }

    /** Ancho fijo (el util), grilla con los anchos dados y, si se pide, bordes horizontales finos. */
    void tablaBase( XWPFTable tb, int[] anchos, boolean bordes ) {
      CTTbl x = tb.getCTTbl();
      CTTblPr tp = x.getTblPr() != null ? x.getTblPr() : x.addNewTblPr();
      CTTblWidth tw = tp.isSetTblW() ? tp.getTblW() : tp.addNewTblW();
      int total = 0;
      for ( int a : anchos ) {
        total += a;
      }
      tw.setType( STTblWidth.DXA );
      tw.setW( BigInteger.valueOf( total ) );
      ( tp.isSetTblLayout() ? tp.getTblLayout() : tp.addNewTblLayout() ).setType( STTblLayoutType.FIXED );
      if ( bordes ) {
        CTTblBorders b = tp.isSetTblBorders() ? tp.getTblBorders() : tp.addNewTblBorders();
        linea( b.isSetTop() ? b.getTop() : b.addNewTop(), STBorder.SINGLE, 4, borde );
        linea( b.isSetBottom() ? b.getBottom() : b.addNewBottom(), STBorder.SINGLE, 4, borde );
        linea( b.isSetInsideH() ? b.getInsideH() : b.addNewInsideH(), STBorder.SINGLE, 4, borde );
        linea( b.isSetLeft() ? b.getLeft() : b.addNewLeft(), STBorder.NIL, 0, null );
        linea( b.isSetRight() ? b.getRight() : b.addNewRight(), STBorder.NIL, 0, null );
        linea( b.isSetInsideV() ? b.getInsideV() : b.addNewInsideV(), STBorder.NIL, 0, null );
        margenes( tp, 80, 30 );
      }
      CTTblGrid g = x.getTblGrid() != null ? x.getTblGrid() : x.addNewTblGrid();
      while ( g.sizeOfGridColArray() > 0 ) {
        g.removeGridCol( 0 );
      }
      for ( int a : anchos ) {
        g.addNewGridCol().setW( BigInteger.valueOf( a ) );
      }
      for ( XWPFTableRow row : tb.getRows() ) {
        for ( int j = 0; j < row.getTableCells().size() && j < anchos.length; j++ ) {
          CTTcPr pr = row.getCell( j ).getCTTc().isSetTcPr() ? row.getCell( j ).getCTTc().getTcPr()
            : row.getCell( j ).getCTTc().addNewTcPr();
          CTTblWidth cw = pr.isSetTcW() ? pr.getTcW() : pr.addNewTcW();
          cw.setType( STTblWidth.DXA );
          cw.setW( BigInteger.valueOf( anchos[ j ] ) );
        }
      }
    }

    private static void sinBordes( CTTblPr tp ) {
      CTTblBorders b = tp.isSetTblBorders() ? tp.getTblBorders() : tp.addNewTblBorders();
      linea( b.isSetTop() ? b.getTop() : b.addNewTop(), STBorder.NIL, 0, null );
      linea( b.isSetBottom() ? b.getBottom() : b.addNewBottom(), STBorder.NIL, 0, null );
      linea( b.isSetLeft() ? b.getLeft() : b.addNewLeft(), STBorder.NIL, 0, null );
      linea( b.isSetRight() ? b.getRight() : b.addNewRight(), STBorder.NIL, 0, null );
      linea( b.isSetInsideH() ? b.getInsideH() : b.addNewInsideH(), STBorder.NIL, 0, null );
      linea( b.isSetInsideV() ? b.getInsideV() : b.addNewInsideV(), STBorder.NIL, 0, null );
    }

    private static void margenes( CTTblPr tp, int lados, int arribaAbajo ) {
      CTTblCellMar m = tp.isSetTblCellMar() ? tp.getTblCellMar() : tp.addNewTblCellMar();
      ancho( m.isSetLeft() ? m.getLeft() : m.addNewLeft(), lados );
      ancho( m.isSetRight() ? m.getRight() : m.addNewRight(), lados );
      ancho( m.isSetTop() ? m.getTop() : m.addNewTop(), arribaAbajo );
      ancho( m.isSetBottom() ? m.getBottom() : m.addNewBottom(), arribaAbajo );
    }

    private static void ancho( CTTblWidth w, int dxa ) {
      w.setType( STTblWidth.DXA );
      w.setW( BigInteger.valueOf( dxa ) );
    }

    private static void linea( CTBorder b, STBorder.Enum tipo, int sz, String color ) {
      b.setVal( tipo );
      if ( sz > 0 ) {
        b.setSz( BigInteger.valueOf( sz ) );
        b.setSpace( BigInteger.ZERO );
      }
      if ( color != null ) {
        b.setColor( color );
      }
    }

    void celda( XWPFTableCell c, String valor, double size, String color, boolean negrita, ParagraphAlignment al ) {
      c.setVerticalAlignment( XWPFTableCell.XWPFVertAlign.CENTER );
      XWPFParagraph p = c.getParagraphs().get( 0 );
      p.setStyle( "PliveCelda" );
      p.setAlignment( al );
      String[] lineas = PptxDiapos.limpio( valor ).split( "\r?\n", -1 );
      for ( int i = 0; i < lineas.length; i++ ) {
        if ( i > 0 ) {
          p.createRun().addBreak();
        }
        if ( !lineas[ i ].isEmpty() ) {
          correr( p, lineas[ i ], size, color, negrita, false );
        }
      }
    }

    private static ParagraphAlignment alinear( List<String> al, int j ) {
      if ( al == null || j >= al.size() || al.get( j ) == null ) {
        return ParagraphAlignment.LEFT;
      }
      String a = al.get( j ).trim().toLowerCase( Locale.ROOT );
      return "r".equals( a ) ? ParagraphAlignment.RIGHT : "c".equals( a ) ? ParagraphAlignment.CENTER
        : ParagraphAlignment.LEFT;
    }

    /**
     * Anchos en twips que suman el ancho util. Cada columna pide un minimo (su palabra mas larga, hasta 24
     * caracteres, para que «campo_10» no se parta) y un natural (su celda mas larga, hasta 48 caracteres).
     * Si los naturales entran, se reparte lo que sobra en proporcion; si no, cada una recibe su minimo y lo
     * que queda se reparte segun lo que le falta para el natural.
     */
    int[] anchos( List<String> cols, List<List<String>> filas, int nCol, double size ) {
      double[] minimo = new double[ nCol ];
      double[] natural = new double[ nCol ];
      double sumaMin = 0;
      double sumaNat = 0;
      double margen = 10; // margenes de la celda (4 pt por lado) y un poco de aire
      for ( int j = 0; j < nCol; j++ ) {
        int palabra = j < cols.size() ? largoPalabra( cols.get( j ) ) : 0;
        int largo = j < cols.size() ? Math.min( 48, cols.get( j ) == null ? 0 : cols.get( j ).length() / 2 ) : 0;
        for ( List<String> f : filas ) {
          if ( j < f.size() && f.get( j ) != null ) {
            palabra = Math.max( palabra, largoPalabra( f.get( j ) ) );
            largo = Math.max( largo, Math.min( 48, largoLinea( f.get( j ) ) ) );
          }
        }
        palabra = Math.max( 3, Math.min( 24, palabra ) );
        largo = Math.max( palabra, Math.min( 48, largo ) );
        minimo[ j ] = palabra * size * ANCHO_PALABRA + margen;
        natural[ j ] = Math.max( minimo[ j ], largo * size * ANCHO_CARACTER + margen );
        sumaMin += minimo[ j ];
        sumaNat += natural[ j ];
      }
      double util = anchoUtilTw / 20.0;
      double[] pt = new double[ nCol ];
      for ( int j = 0; j < nCol; j++ ) {
        if ( sumaNat <= util ) {
          pt[ j ] = natural[ j ] * util / sumaNat;
        } else if ( sumaMin <= util ) {
          double extra = sumaNat - sumaMin;
          pt[ j ] = minimo[ j ] + ( extra <= 0 ? 0 : ( natural[ j ] - minimo[ j ] ) * ( util - sumaMin ) / extra );
        } else {
          pt[ j ] = minimo[ j ] * util / sumaMin;
        }
      }
      int[] out = new int[ nCol ];
      int resto = anchoUtilTw;
      for ( int j = 0; j < nCol; j++ ) {
        out[ j ] = j == nCol - 1 ? resto : (int) Math.floor( pt[ j ] * 20 );
        resto -= out[ j ];
      }
      return out;
    }

    private static int largoPalabra( String s ) {
      int m = 0;
      for ( String p : ( s == null ? "" : s ).split( "\\s+" ) ) {
        m = Math.max( m, p.length() );
      }
      return m;
    }

    private static int largoLinea( String s ) {
      int m = 0;
      for ( String l : s.split( "\r?\n" ) ) {
        m = Math.max( m, l.length() );
      }
      return m;
    }

    // ------------------------------------------------------------------ indicadores

    /** Tarjetas: tabla de hasta 4 columnas con espacio entre celdas y el borde izquierdo del nivel. */
    void indicadores( Indicadores ind ) {
      List<Indicador> items = new ArrayList<>();
      for ( Indicador i : Html.lista( ind.items ) ) {
        if ( i != null ) {
          items.add( i );
        }
      }
      if ( items.isEmpty() ) {
        return;
      }
      int porFila = Math.min( 4, items.size() );
      int filas = (int) Math.ceil( items.size() / (double) porFila );
      XWPFTable tb = doc.createTable( filas, porFila );
      int[] anchos = new int[ porFila ];
      for ( int j = 0; j < porFila; j++ ) {
        anchos[ j ] = anchoUtilTw / porFila;
      }
      tablaBase( tb, anchos, false );
      CTTblPr tp = tb.getCTTbl().getTblPr();
      sinBordes( tp );
      margenes( tp, 140, 80 );
      ancho( tp.isSetTblCellSpacing() ? tp.getTblCellSpacing() : tp.addNewTblCellSpacing(), 50 );
      for ( int k = 0; k < filas * porFila; k++ ) {
        XWPFTableCell c = tb.getRow( k / porFila ).getCell( k % porFila );
        XWPFParagraph p0 = c.getParagraphs().get( 0 );
        p0.setStyle( "PliveCelda" );
        if ( k >= items.size() ) {
          continue;
        }
        Indicador i = items.get( k );
        String nivel = Indicador.OK.equals( i.nivel ) ? "2F855A" : Indicador.WARN.equals( i.nivel ) ? "975A16"
          : Indicador.ERROR.equals( i.nivel ) ? "C53030" : null;
        c.setColor( fondoTarjeta );
        CTTcPr pr = c.getCTTc().isSetTcPr() ? c.getCTTc().getTcPr() : c.getCTTc().addNewTcPr();
        CTTcBorders bordes = pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders();
        linea( bordes.addNewLeft(), STBorder.SINGLE, 24, nivel != null ? nivel : acento );
        correr( p0, i.etiqueta, 8, tenue, false, false );
        XWPFParagraph p1 = c.addParagraph();
        p1.setStyle( "PliveCelda" );
        p1.setSpacingBefore( 40 );
        String valor = PptxDiapos.limpio( i.valor );
        double size = valor.length() > 14 ? 12 : 16;
        correr( p1, valor, size, nivel != null ? nivel : "1F2933", true, false );
        if ( !PptxDiapos.vacio( i.detalle ) ) {
          XWPFParagraph p2 = c.addParagraph();
          p2.setStyle( "PliveCelda" );
          correr( p2, i.detalle, 7.5, tenue, false, false );
        }
      }
      espacio( 80 );
      ocupar( filas * 62 + 6 );
    }

    // ------------------------------------------------------------------ figuras

    /**
     * Con grafo, el diagrama propio (una figura por parte, para la hoja de Word) y despues el lienzo si no es
     * una tira. Sin grafo: el lienzo (si hay) y el Mermaid como PNG; sin PNG, la tabla alternativa.
     */
    void diagrama( Diagrama d ) throws Exception {
      List<Dibujo> ds = Figuras.dibujos( d, Objetivo.DOCX, idioma );
      if ( ds != null ) {
        propio( d, ds );
        return;
      }
      boolean algo = d.imagen != null && figuraSegura( d.imagen, d.imagen.alt != null ? d.imagen.alt : d.alt );
      byte[] png = d.id == null || PptxDiapos.vacio( d.mermaid ) ? null : pngs.get( d.id );
      if ( png != null && png.length > 0 ) {
        try {
          figura( png, Imagen.PNG, 0, 0, 2f, AMPLIAR_MAX, d.alt );
          return;
        } catch ( RuntimeException e ) {
          // PNG roto: sigue con la tabla
        }
      }
      if ( algo ) {
        return;
      }
      if ( d.alternativa != null ) {
        if ( !PptxDiapos.vacio( d.mermaid ) ) {
          diagramasComoTabla++;
        }
        tabla( d.alternativa );
        nota( r.s( "diagrama_como_tabla" ) );
      } else {
        nota( r.s( "diagrama_no_disponible" ) + ( PptxDiapos.vacio( d.alt ) ? "" : " " + d.alt ) );
      }
    }

    /**
     * Las partes del diagrama propio como PNG a ~200 ppp del tamano final (Objetivo.DOCX ya es la hoja de
     * Word: no se agranda), cada una con «Parte i de n» encima si se partio, pegado a la figura.
     */
    void propio( Diagrama d, List<Dibujo> ds ) throws Exception {
      for ( Dibujo x : ds ) {
        if ( ds.size() > 1 ) {
          soltarFigura();
          XWPFParagraph p = doc.createParagraph();
          p.setStyle( "PliveNota" );
          org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP()
            .getPPr() : p.getCTP().addNewPPr();
          ppr.addNewKeepNext();
          correr( p, Figuras.parte( idioma, x.parte, x.partes ), 0, null, true, false );
          ocupar( 18 );
        }
        byte[] png = x.png( PPP_DIAGRAMA );
        int[] wh = PptxDiapos.medir( png );
        figura( png, Imagen.PNG, wh[ 0 ], wh[ 1 ], (float) ( wh[ 0 ] / x.anchoFinal() ), 1.0, d.alt );
      }
      Imagen lz = Figuras.lienzo( d );
      if ( lz != null && Figuras.proporcionada( lz ) ) {
        figuraSegura( lz, lz.alt != null ? lz.alt : d.alt );
      }
    }

    /** El SVG de GraficosSvg rasterizado con Batik al ancho util a ~200 ppp; si falla, sus datos como tabla. */
    void grafico( Grafico g ) {
      try {
        String svg = proveedorSvg.svg( g, tema, GraficosSvg.ANCHO );
        if ( svg != null && svg.contains( "<svg" ) ) {
          double[] tam = PptxDiapos.tamanoSvg( svg );
          int px = (int) Math.min( SvgAPng.MAX_ANCHO, Math.round( anchoUtil * 2.7 ) );
          byte[] png = SvgAPng.png( svg, px );
          double w = anchoUtil;
          double h = w * tam[ 1 ] / tam[ 0 ];
          double[] wh = encajar( w, h );
          soltarFigura();
          XWPFParagraph p = doc.createParagraph();
          p.setStyle( "PliveFigura" );
          imagen( p.createRun(), png, Imagen.PNG, wh[ 0 ], wh[ 1 ], g.titulo != null ? g.titulo : g.id != null
            ? g.id : "grafico" );
          figuraPegada = p;
          ocupado = Double.NaN;
          return;
        }
      } catch ( Throwable e ) {
        // abajo: la tabla
      }
      graficosComoTabla++;
      tabla( RenderHtml.tablaDeGrafico( g, rr, locale ) );
      nota( r.s( "grafico_como_tabla" ) );
    }

    /** Una Imagen del modelo como figura; false si no tiene bytes o no se puede leer (no frena el informe). */
    boolean figuraSegura( Imagen im, String alt ) throws Exception {
      if ( im.datos == null || im.datos.length == 0 ) {
        return false;
      }
      try {
        figura( im.datos, im.mime, im.ancho, im.alto, im.escala > 0 ? im.escala : 1f, 1.0, alt );
        return true;
      } catch ( RuntimeException e ) {
        return false;
      }
    }

    /**
     * Imagen centrada: tamano natural (pixeles / escala, 0,75 pt por pixel CSS) que se agranda hasta
     * {@code ampliar} veces si sobra lugar, y se achica para entrar en el ancho util y en el alto util
     * menos lo que piden el titulo y la nota. Una figura nunca pasa de una pagina.
     */
    void figura( byte[] datos, String mime, int anchoPx, int altoPx, float escala, double ampliar, String alt )
      throws Exception {
      int w0 = anchoPx;
      int h0 = altoPx;
      if ( w0 <= 0 || h0 <= 0 ) {
        int[] wh = PptxDiapos.medir( datos );
        w0 = wh[ 0 ];
        h0 = wh[ 1 ];
      }
      if ( w0 <= 0 || h0 <= 0 ) {
        throw new IllegalArgumentException( "imagen sin tamano" );
      }
      double natural = w0 / (double) ( escala > 0 ? escala : 1f ) * 0.75;
      double w = Math.min( anchoUtil, natural * ampliar );
      double[] wh = encajar( w, w * h0 / (double) w0 );
      soltarFigura();
      XWPFParagraph p = doc.createParagraph();
      p.setStyle( "PliveFigura" );
      imagen( p.createRun(), datos, mime, wh[ 0 ], wh[ 1 ], alt == null ? "figura" : alt );
      figuraPegada = p;
      ocupado = Double.NaN;
    }

    /**
     * La figura anterior deja de seguir pegada a lo que viene (keepNext en false sobre el del estilo): lo que
     * sigue es otra figura, un titulo o una tabla, que no tienen por que ir en su misma pagina.
     */
    void soltarFigura() {
      if ( figuraPegada == null ) {
        return;
      }
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = figuraPegada.getCTP().isSetPPr()
        ? figuraPegada.getCTP().getPPr() : figuraPegada.getCTP().addNewPPr();
      ( ppr.isSetKeepNext() ? ppr.getKeepNext() : ppr.addNewKeepNext() ).setVal( STOnOff.FALSE );
      figuraPegada = null;
    }

    /**
     * Ancho y alto finales de una figura de w x h puntos: entra en el ancho util y en el alto util menos
     * {@link #RESERVA_FIGURA}. Si la seccion acaba de empezar en esta pagina y lo que queda alcanza para
     * mostrarla al menos al {@link #ACHICAR_MIN} de ese tamano, se achica a lo que queda: asi el titulo de la
     * seccion no queda solo en una pagina y la figura en la siguiente.
     */
    double[] encajar( double w, double h ) {
      double proporcion = h / w;
      if ( w > anchoUtil ) {
        w = anchoUtil;
        h = w * proporcion;
      }
      double maxH = altoUtil - RESERVA_FIGURA;
      if ( h > maxH ) {
        h = maxH;
        w = h / proporcion;
      }
      if ( !Double.isNaN( ocupado ) ) {
        double libre = altoUtil - ocupado * 1.1 - RESERVA_NOTA;
        if ( h > libre && libre >= h * ACHICAR_MIN ) {
          h = libre;
          w = h / proporcion;
        }
      }
      return new double[] { w, h };
    }

    void imagen( XWPFRun run, byte[] datos, String mime, double wPt, double hPt, String nombre ) throws Exception {
      int tipo = mime != null && mime.toLowerCase( Locale.ROOT ).contains( "jp" ) ? Document.PICTURE_TYPE_JPEG
        : Document.PICTURE_TYPE_PNG;
      String archivo = "imagen" + ( ++imagenes ) + ( tipo == Document.PICTURE_TYPE_JPEG ? ".jpg" : ".png" );
      run.addPicture( new ByteArrayInputStream( datos ), tipo, archivo, Units.toEMU( wPt ), Units.toEMU( hPt ) );
      // descr = texto alternativo, para lectores de pantalla
      try {
        org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline in = run.getCTR().getDrawingArray(
          0 ).getInlineArray( 0 );
        in.getDocPr().setDescr( PptxDiapos.limpio( nombre ) );
      } catch ( RuntimeException e ) {
        // el texto alternativo es un extra
      }
    }

    // ------------------------------------------------------------------ pagina, encabezado y pie

    /**
     * Tamano y margenes de pagina (pgSz y pgMar no estan en el jar lite: van con XmlCursor), portada sin
     * encabezado ni pie (titlePg), encabezado con el titulo y pie con el simbolo IDC, «Pentaho Live · IDC
     * Studios» y «Pagina X de Y».
     */
    void paginaYPies() throws Exception {
      XWPFHeaderFooterPolicy pol = doc.createHeaderFooterPolicy();
      XWPFHeader h = pol.createHeader( XWPFHeaderFooterPolicy.DEFAULT );
      XWPFParagraph ph = h.getParagraphs().isEmpty() ? h.createParagraph() : h.getParagraphs().get( 0 );
      ph.setStyle( "Header" );
      ph.setAlignment( ParagraphAlignment.RIGHT );
      correr( ph, tituloInforme(), 0, null, false, false );
      pol.createHeader( XWPFHeaderFooterPolicy.FIRST );
      XWPFFooter f = pol.createFooter( XWPFHeaderFooterPolicy.DEFAULT );
      XWPFParagraph pf = f.getParagraphs().isEmpty() ? f.createParagraph() : f.getParagraphs().get( 0 );
      pf.setStyle( "Footer" );
      try {
        byte[] s = SimboloIdc.png( 64, SimboloIdc.CLARO, 1f );
        if ( s != null && s.length > 0 ) {
          XWPFRun rs = pf.createRun();
          rs.addPicture( new ByteArrayInputStream( s ), Document.PICTURE_TYPE_PNG, "idc.png", Units.toEMU( 12 ),
            Units.toEMU( 12 * SimboloIdc.ALTO / SimboloIdc.ANCHO ) );
          pf.createRun().setText( " " );
        }
      } catch ( Throwable e ) {
        // el pie va solo con texto
      }
      correr( pf, Tema.PIE, 0, null, false, false );
      pf.createRun().addTab();
      correr( pf, texto( "page" ) + " ", 0, null, false, false );
      campoSimple( pf, "PAGE", "1" );
      correr( pf, " " + texto( "of" ) + " ", 0, null, false, false );
      campoSimple( pf, "NUMPAGES", "1" );
      pol.createFooter( XWPFHeaderFooterPolicy.FIRST );

      CTSectPr sp = doc.getDocument().getBody().isSetSectPr() ? doc.getDocument().getBody().getSectPr()
        : doc.getDocument().getBody().addNewSectPr();
      // despues de headerReference/footerReference y antes de titlePg (Word rechaza el orden equivocado)
      XmlCursor c = sp.newCursor();
      try {
        c.toEndToken();
        elemento( c, "pgSz", "w", String.valueOf( Math.round( anchoPagina * 20 ) ), "h", String.valueOf( Math.round(
          altoPagina * 20 ) ) );
        elemento( c, "pgMar", "top", String.valueOf( MARGEN_V ), "right", String.valueOf( MARGEN_H ), "bottom", String
          .valueOf( MARGEN_V ), "left", String.valueOf( MARGEN_H ), "header", String.valueOf( MARGEN_HF ), "footer",
          String.valueOf( MARGEN_HF ), "gutter", "0" );
      } finally {
        c.dispose();
      }
      sp.addNewTitlePg();
    }

    private static void elemento( XmlCursor c, String nombre, String... atributos ) {
      c.beginElement( new QName( W, nombre, "w" ) );
      for ( int i = 0; i + 1 < atributos.length; i += 2 ) {
        c.insertAttributeWithValue( new QName( W, atributos[ i ], "w" ), atributos[ i + 1 ] );
      }
      c.toEndToken();
      c.toNextToken();
    }

    private static void campoSimple( XWPFParagraph p, String instr, String valor ) {
      CTSimpleField f = p.getCTP().addNewFldSimple();
      f.setInstr( " " + instr + " " );
      f.addNewR().addNewT().setStringValue( valor );
    }

    // ------------------------------------------------------------------ utilidades

    /** "#RRGGBB" -> "RRGGBB" (Word no lleva numeral). */
    static String hex( String color ) {
      String c = color == null ? "000000" : color.trim();
      return ( c.startsWith( "#" ) ? c.substring( 1 ) : c ).toUpperCase( Locale.ROOT );
    }

    /** Fecha del informe con el patron de la portada del PowerPoint ("20 de octubre de 2026, 14:32"). */
    String fecha( String iso ) {
      if ( PptxDiapos.vacio( iso ) ) {
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
        String f = DateTimeFormatter.ofPattern( rp.s( "fecha" ), locale ).format( t );
        return "es".equals( idioma ) || "pt".equals( idioma ) ? f.toLowerCase( locale ) : f;
      } catch ( RuntimeException e ) {
        return iso;
      }
    }
  }
}
