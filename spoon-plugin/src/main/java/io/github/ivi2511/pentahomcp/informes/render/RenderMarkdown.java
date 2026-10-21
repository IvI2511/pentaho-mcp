package io.github.ivi2511.pentahomcp.informes.render;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Renderizador;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.diagramas.Diagramas;
import io.github.ivi2511.pentahomcp.informes.diagramas.Dibujo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Objetivo;
import io.github.ivi2511.pentahomcp.informes.graficos.GraficosSvg;
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
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Markdown para Obsidian y GitHub (diseno §3.3): el .md mas la carpeta {@code <nombre>_archivos\} con el
 * PNG del lienzo, las imagenes y los SVG de los graficos.
 * <ul>
 * <li>Front matter YAML: title, date, tags, source, generator.</li>
 * <li>Diagramas de flujo, job y linaje: el dibujo propio como .svg (uno por parte si se partio), el lienzo de
 * Spoon y, plegado en una llamada de Obsidian, el codigo {@code ```mermaid} (GitHub y Obsidian lo dibujan).
 * El modelo entidad-relacion: la imagen (si la hay) y el bloque {@code ```mermaid}.</li>
 * <li>Tablas GFM con {@code |} escapado; indicadores como tabla de 2 columnas.</li>
 * <li>Avisos como {@code > [!NOTE]}, {@code > [!WARNING]} y {@code > [!CAUTION]} (los entienden los dos).</li>
 * <li>Una linea final en cursiva: "Generado con Pentaho Live · IDC Studios". Sin marca de agua.</li>
 * </ul>
 * Los nombres de archivo son estables: al regenerar se sobrescriben y los enlaces de Obsidian no se rompen.
 *
 * @dueño WP7
 */
public final class RenderMarkdown implements Renderizador {

  /** Sufijo de la carpeta de archivos: informe.md -> informe_archivos\. */
  public static final String SUFIJO_CARPETA = "_archivos";

  @Override
  public Formato formato() {
    return Formato.MARKDOWN;
  }

  @Override
  public Resultado escribir( Informe inf, Tema tema, File destino, Progreso p ) throws Exception {
    File dest = destino.getAbsoluteFile();
    File carpeta = new File( dest.getParentFile(), Html.base( dest ) + SUFIJO_CARPETA );
    Escritor e = new Escritor( inf == null ? new Informe() : inf, tema == null ? new Tema() : tema,
      p == null ? Progreso.NULO : p, carpeta );
    String md = e.documento();
    Html.escribir( dest, md );
    Resultado r = new Resultado();
    r.archivo( dest );
    for ( File f : e.archivos ) {
      r.archivo( f );
    }
    r.avisos.addAll( e.avisos );
    return r;
  }

  /**
   * La ruta del documento para el front matter: relativa a la carpeta del .md («../03_ventas.ktr»), o solo
   * el nombre si estan en discos distintos o no es un archivo local. Nunca la absoluta, que muestra
   * C:\Users\&lt;usuario&gt;\... (diseno §6, riesgo 20).
   */
  static String fuente( String archivo, File carpetaMd ) {
    String a = archivo.trim();
    String nombre = a.replace( '\\', '/' );
    nombre = nombre.substring( nombre.lastIndexOf( '/' ) + 1 );
    if ( carpetaMd == null || a.contains( "://" ) || a.regionMatches( true, 0, "file:", 0, 5 ) || a.contains(
      "${" ) ) {
      return nombre;
    }
    try {
      java.nio.file.Path doc = new File( a ).getAbsoluteFile().toPath().normalize();
      java.nio.file.Path dir = carpetaMd.getAbsoluteFile().toPath().normalize();
      if ( doc.getRoot() != null && doc.getRoot().equals( dir.getRoot() ) ) {
        String rel = dir.relativize( doc ).toString().replace( '\\', '/' );
        // cerca del .md (la carpeta informes\ al lado del .ktr, o una hermana): la ruta; si no, el nombre
        if ( !rel.startsWith( "../../../" ) ) {
          return rel;
        }
      }
    } catch ( RuntimeException e ) {
      // ruta rara: el nombre
    }
    return nombre;
  }

  /** La carpeta de archivos que corresponde a un .md. */
  public static File carpeta( File md ) {
    File d = md.getAbsoluteFile();
    return new File( d.getParentFile(), Html.base( d ) + SUFIJO_CARPETA );
  }

  // ---------------------------------------------------------------- escapes de Markdown

  /**
   * Texto plano para Markdown (GitHub y Obsidian): escapa con barra lo que podria dar formato, HTML,
   * tablas, etiquetas (#), matematica ($), resaltado (==) y comentarios (%%) de Obsidian.
   */
  public static String esc( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder sb = new StringBuilder( s.length() + 8 );
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      char antes = i > 0 ? s.charAt( i - 1 ) : 0;
      char despues = i + 1 < s.length() ? s.charAt( i + 1 ) : 0;
      switch ( c ) {
        case '_':
          // dentro de una palabra (03_ventas_por_producto) no da formato ni en GFM ni en Obsidian
          if ( !( Character.isLetterOrDigit( antes ) && Character.isLetterOrDigit( despues ) ) ) {
            sb.append( '\\' );
          }
          sb.append( c );
          break;
        case '\\':
        case '`':
        case '*':
        case '[':
        case ']':
        case '<':
        case '>':
        case '|':
        case '#':
        case '$':
        case '~':
        case '&':
          sb.append( '\\' ).append( c );
          break;
        case '=':
        case '%':
          if ( antes == c || despues == c ) {
            sb.append( '\\' );
          }
          sb.append( c );
          break;
        case '\r':
          break;
        default:
          sb.append( c < 0x20 && c != '\n' && c != '\t' ? ' ' : c );
      }
    }
    return sb.toString();
  }

  /** Una linea con poco Markdown (**negrita**, *cursiva*): las marcas quedan, el resto se escapa. */
  public static String enLinea( String linea ) {
    StringBuilder sb = new StringBuilder();
    for ( Html.Trozo t : Html.trozos( linea ) ) {
      switch ( t.estilo ) {
        case NEGRITA:
          sb.append( "**" ).append( esc( t.texto ) ).append( "**" );
          break;
        case CURSIVA:
          sb.append( '*' ).append( esc( t.texto ) ).append( '*' );
          break;
        default:
          sb.append( esc( t.texto ) );
      }
    }
    return sb.toString();
  }

  /** Inicio de linea que Markdown tomaria como lista, titulo o regla: se escapa. */
  static String inicioSeguro( String linea ) {
    if ( linea.isEmpty() ) {
      return linea;
    }
    char c = linea.charAt( 0 );
    if ( c == '-' || c == '+' || c == '=' ) {
      return "\\" + linea;
    }
    int i = 0;
    while ( i < linea.length() && Character.isDigit( linea.charAt( i ) ) ) {
      i++;
    }
    if ( i > 0 && i < linea.length() && ( linea.charAt( i ) == '.' || linea.charAt( i ) == ')' ) ) {
      return linea.substring( 0, i ) + "\\" + linea.substring( i );
    }
    return linea;
  }

  /** Celda de tabla GFM: escapada y con los saltos de linea como &lt;br&gt;. */
  static String celda( String s ) {
    if ( s == null ) {
      return "";
    }
    return esc( s.replace( "\r\n", "\n" ).trim() ).replace( "\n", "<br>" );
  }

  /** Ruta relativa para un enlace: cada caracter fuera de [A-Za-z0-9._~-] va como %XX (UTF-8). */
  static String ruta( String carpeta, String archivo ) {
    return codificar( carpeta ) + "/" + codificar( archivo );
  }

  private static String codificar( String s ) {
    StringBuilder sb = new StringBuilder();
    for ( byte b : s.getBytes( StandardCharsets.UTF_8 ) ) {
      int c = b & 0xFF;
      if ( ( c >= 'A' && c <= 'Z' ) || ( c >= 'a' && c <= 'z' ) || ( c >= '0' && c <= '9' ) || c == '.' || c == '_'
        || c == '~' || c == '-' ) {
        sb.append( (char) c );
      } else {
        sb.append( '%' ).append( String.format( Locale.ROOT, "%02X", c ) );
      }
    }
    return sb.toString();
  }

  /** Cerca de bloque de codigo que no aparece dentro del texto (``` o mas larga). */
  static String cerca( String texto ) {
    int max = 0;
    int run = 0;
    for ( int i = 0; i < texto.length(); i++ ) {
      run = texto.charAt( i ) == '`' ? run + 1 : 0;
      max = Math.max( max, run );
    }
    StringBuilder sb = new StringBuilder();
    for ( int i = 0; i < Math.max( 3, max + 1 ); i++ ) {
      sb.append( '`' );
    }
    return sb.toString();
  }

  /** Valor YAML entre comillas simples ('' para la comilla) y sin saltos de linea. */
  static String yaml( String s ) {
    String t = s == null ? "" : s.replace( "\r", " " ).replace( "\n", " " ).trim();
    return "'" + t.replace( "'", "''" ) + "'";
  }

  // ---------------------------------------------------------------- el documento

  private static final class Escritor {
    final Informe inf;
    final Tema tema;
    final Progreso p;
    final File carpeta;
    final Rotulos r;
    final Locale loc;
    final StringBuilder sb = new StringBuilder( 32 * 1024 );
    final List<File> archivos = new ArrayList<>();
    final List<String> avisos = new ArrayList<>();
    final Set<String> nombres = new HashSet<>();
    boolean lienzoUsado;

    Escritor( Informe inf, Tema tema, Progreso p, File carpeta ) {
      this.inf = inf;
      this.tema = tema;
      this.p = p;
      this.carpeta = carpeta;
      String idioma = Rotulos.normalizar( inf.idioma != null ? inf.idioma : tema.idioma );
      this.r = Rotulos.de( RenderHtml.AREA, idioma );
      this.loc = Rotulos.locale( idioma );
    }

    String t( String clave ) {
      return RenderHtml.texto( inf, r, clave );
    }

    String documento() throws Exception {
      List<Seccion> secciones = RenderHtml.ordenadas( inf );
      frontMatter();
      portada( secciones );
      int n = secciones.size();
      int i = 0;
      for ( Seccion s : secciones ) {
        if ( p.cancelado() ) {
          throw new CancellationException();
        }
        p.paso( t( "writing_markdown" ), ++i, n );
        if ( SeccionId.PORTADA.equals( s.id ) || Seccion.OMITIDA.equals( s.estado ) ) {
          continue;
        }
        seccion( s );
      }
      sb.append( "---\n\n*" ).append( esc( t( "generated_with" ) + " " + Tema.PIE ) ).append( "*\n" );
      return sb.toString();
    }

    String titulo() {
      if ( inf.titulo != null && !inf.titulo.trim().isEmpty() ) {
        return inf.titulo.trim();
      }
      if ( inf.nombreDoc != null && !inf.nombreDoc.trim().isEmpty() ) {
        return inf.nombreDoc.trim();
      }
      return t( "report" );
    }

    void frontMatter() {
      String fecha = inf.generado != null && inf.generado.trim().matches( "\\d{4}-\\d{2}-\\d{2}.*" )
        ? inf.generado.trim().substring( 0, 10 ) : LocalDate.now().toString();
      String v = Html.version();
      sb.append( "---\n" );
      sb.append( "title: " ).append( yaml( titulo() ) ).append( '\n' );
      sb.append( "date: " ).append( fecha ).append( '\n' );
      sb.append( "tags: [pentaho, etl, informe]\n" );
      if ( inf.archivo != null && !inf.archivo.trim().isEmpty() ) {
        sb.append( "source: " ).append( yaml( fuente( inf.archivo, carpeta.getParentFile() ) ) ).append( '\n' );
      }
      sb.append( "generator: " ).append( yaml( "Pentaho Live" + ( v == null ? "" : " " + v ) ) ).append( '\n' );
      sb.append( "---\n\n" );
    }

    void portada( List<Seccion> secciones ) throws Exception {
      Seccion cover = null;
      for ( Seccion s : secciones ) {
        if ( SeccionId.PORTADA.equals( s.id ) ) {
          cover = s;
          break;
        }
      }
      sb.append( "# " ).append( esc( titulo() ) ).append( "\n\n" );
      Imagen logo = null;
      if ( cover != null ) {
        for ( Bloque b : Html.lista( cover.bloques ) ) {
          if ( b instanceof Imagen && ( (Imagen) b ).datos != null ) {
            logo = (Imagen) b;
            break;
          }
        }
        if ( logo == null && tema.logoDatos != null ) {
          logo = new Imagen( tema.logoMime, tema.logoDatos, 0, 0, 1f );
        }
      }
      if ( logo != null ) {
        imagen( logo, "logo", logo.alt );
      }
      List<String> lineas = new ArrayList<>();
      String tipo = Informe.JOB.equals( inf.tipoDoc ) ? t( "doc_job" )
        : Informe.TRANSFORMACION.equals( inf.tipoDoc ) ? t( "doc_transformation" ) : null;
      if ( tipo != null || inf.nombreDoc != null ) {
        String l = ( tipo == null ? "" : tipo ) + ( tipo != null && inf.nombreDoc != null ? " · " : "" )
          + ( inf.nombreDoc == null ? "" : inf.nombreDoc );
        lineas.add( "**" + esc( l ) + "**" );
      }
      if ( inf.subtitulo != null && !inf.subtitulo.trim().isEmpty() ) {
        lineas.add( inicioSeguro( esc( inf.subtitulo.trim() ) ) );
      }
      String fecha = RenderHtml.fechaLarga( inf.generado, loc );
      if ( fecha != null ) {
        lineas.add( "*" + esc( r.f( "generated_on", fecha ) ) + "*" );
      }
      if ( !lineas.isEmpty() ) {
        sb.append( String.join( "  \n", lineas ) ).append( "\n\n" );
      }
      if ( cover != null ) {
        if ( cover.resumen != null && !cover.resumen.trim().isEmpty() ) {
          parrafos( cover.resumen );
        }
        for ( Bloque b : Html.lista( cover.bloques ) ) {
          if ( b != null && b != logo ) {
            bloque( b );
          }
        }
      }
      if ( inf.avisos != null ) {
        for ( String a : inf.avisos ) {
          if ( a != null && !a.trim().isEmpty() ) {
            aviso( Aviso.WARN, null, a );
          }
        }
      }
      StringBuilder om = new StringBuilder();
      for ( Seccion s : secciones ) {
        if ( Seccion.OMITIDA.equals( s.estado ) && !SeccionId.PORTADA.equals( s.id ) ) {
          om.append( om.length() == 0 ? "" : "; " ).append( tituloSeccion( s ) );
          if ( s.motivo != null && !s.motivo.trim().isEmpty() ) {
            om.append( " (" ).append( s.motivo.trim() ).append( ')' );
          }
        }
      }
      if ( om.length() > 0 ) {
        aviso( Aviso.INFO, null, t( "omitted_sections" ) + ": " + om + "." );
      }
    }

    String tituloSeccion( Seccion s ) {
      if ( s.titulo != null && !s.titulo.trim().isEmpty() ) {
        return s.titulo.trim();
      }
      return SeccionId.titulo( s.id, r.idioma() );
    }

    void seccion( Seccion s ) throws Exception {
      sb.append( "## " ).append( esc( tituloSeccion( s ) ) ).append( "\n\n" );
      if ( Seccion.PARCIAL.equals( s.estado ) ) {
        aviso( Aviso.WARN, t( "status_partial" ), s.motivo );
      } else if ( Seccion.ERROR.equals( s.estado ) ) {
        aviso( Aviso.ERROR, t( "status_error" ), s.motivo );
      }
      if ( s.resumen != null && !s.resumen.trim().isEmpty() ) {
        for ( String linea : s.resumen.trim().split( "\\r?\\n" ) ) {
          if ( !linea.trim().isEmpty() ) {
            sb.append( '*' ).append( esc( linea.trim() ) ).append( "*  \n" );
          }
        }
        sb.append( '\n' );
      }
      for ( Bloque b : Html.lista( s.bloques ) ) {
        Bloque x = b == null ? null : Html.sinRepetirResumen( b, s.resumen );
        if ( x != null ) {
          bloque( x );
        }
      }
    }

    void bloque( Bloque b ) throws Exception {
      if ( b.titulo != null && !b.titulo.trim().isEmpty() ) {
        sb.append( "### " ).append( esc( b.titulo.trim() ) ).append( "\n\n" );
      }
      if ( b instanceof Parrafo ) {
        parrafos( ( (Parrafo) b ).texto );
      } else if ( b instanceof Lista ) {
        for ( String it : Html.lista( ( (Lista) b ).items ) ) {
          sb.append( "- " ).append( enLinea( it == null ? "" : it.replace( '\n', ' ' ).trim() ) ).append( '\n' );
        }
        sb.append( '\n' );
      } else if ( b instanceof Tabla ) {
        tabla( (Tabla) b );
      } else if ( b instanceof Indicadores ) {
        indicadores( (Indicadores) b );
      } else if ( b instanceof Aviso ) {
        Aviso a = (Aviso) b;
        aviso( a.nivel, null, a.texto );
      } else if ( b instanceof Diagrama ) {
        diagrama( (Diagrama) b );
      } else if ( b instanceof Grafico ) {
        grafico( (Grafico) b );
      } else if ( b instanceof Imagen ) {
        Imagen im = (Imagen) b;
        imagen( im, im.id != null ? im.id : "imagen", im.alt );
      }
      if ( b.nota != null && !b.nota.trim().isEmpty() ) {
        cursiva( b.nota );
      }
      if ( b.ia && tema.rotularIa ) {
        cursiva( t( "ai_label" ) );
      }
    }

    void cursiva( String texto ) {
      for ( String linea : texto.trim().split( "\\r?\\n" ) ) {
        if ( !linea.trim().isEmpty() ) {
          sb.append( '*' ).append( esc( linea.trim() ) ).append( "*  \n" );
        }
      }
      sb.append( '\n' );
    }

    void parrafos( String texto ) {
      for ( Html.Grupo g : Html.grupos( texto ) ) {
        if ( g.lista ) {
          for ( String li : g.lineas ) {
            sb.append( "- " ).append( enLinea( li ) ).append( '\n' );
          }
        } else {
          for ( int i = 0; i < g.lineas.size(); i++ ) {
            sb.append( inicioSeguro( enLinea( g.lineas.get( i ) ) ) );
            sb.append( i + 1 < g.lineas.size() ? "  \n" : "\n" );
          }
        }
        sb.append( '\n' );
      }
    }

    void tabla( Tabla t ) {
      int columnas = t.columnas == null ? 0 : t.columnas.size();
      if ( t.filas != null ) {
        for ( List<String> f : t.filas ) {
          columnas = Math.max( columnas, f == null ? 0 : f.size() );
        }
      }
      if ( columnas == 0 ) {
        return;
      }
      sb.append( '|' );
      for ( int i = 0; i < columnas; i++ ) {
        String c = t.columnas != null && i < t.columnas.size() ? t.columnas.get( i ) : "";
        sb.append( ' ' ).append( celda( c ) ).append( " |" );
      }
      sb.append( "\n|" );
      for ( int i = 0; i < columnas; i++ ) {
        String a = t.alineacion != null && i < t.alineacion.size() && t.alineacion.get( i ) != null
          ? t.alineacion.get( i ).trim().toLowerCase( Locale.ROOT ) : "l";
        sb.append( "r".equals( a ) ? " ---: |" : "c".equals( a ) ? " :---: |" : " --- |" );
      }
      sb.append( '\n' );
      if ( t.filas != null ) {
        for ( List<String> f : t.filas ) {
          sb.append( '|' );
          for ( int i = 0; i < columnas; i++ ) {
            String c = f == null || i >= f.size() ? null : f.get( i );
            sb.append( ' ' ).append( celda( c ) ).append( " |" );
          }
          sb.append( '\n' );
        }
      }
      sb.append( '\n' );
      if ( t.pie != null && !t.pie.trim().isEmpty() ) {
        cursiva( t.pie );
      }
    }

    void indicadores( Indicadores ind ) {
      Tabla t = new Tabla( t( "indicator" ), t( "value" ) );
      for ( Indicador i : Html.lista( ind.items ) ) {
        if ( i == null ) {
          continue;
        }
        StringBuilder v = new StringBuilder( i.valor == null ? "" : i.valor );
        if ( i.detalle != null && !i.detalle.trim().isEmpty() ) {
          v.append( " · " ).append( i.detalle.trim() );
        }
        if ( Indicador.WARN.equals( i.nivel ) || Indicador.ERROR.equals( i.nivel ) ) {
          v.append( " (" ).append( t( "level_" + i.nivel ) ).append( ')' );
        }
        t.fila( i.etiqueta, v.toString() );
      }
      tabla( t );
    }

    /** Llamada de Obsidian y GitHub: NOTE, WARNING o CAUTION; el titulo (opcional) va en negrita. */
    void aviso( String nivel, String titulo, String texto ) {
      String tipo = Aviso.ERROR.equals( nivel ) ? "CAUTION" : Aviso.WARN.equals( nivel ) ? "WARNING" : "NOTE";
      sb.append( "> [!" ).append( tipo ).append( "]\n" );
      List<Html.Grupo> grupos = Html.grupos( texto == null ? "" : texto );
      String prefijo = titulo == null ? "" : "**" + esc( titulo ) + ".** ";
      if ( !prefijo.isEmpty() && ( grupos.isEmpty() || grupos.get( 0 ).lista ) ) {
        sb.append( "> " ).append( prefijo.trim() ).append( '\n' );
        if ( !grupos.isEmpty() ) {
          sb.append( ">\n" );
        }
        prefijo = "";
      }
      boolean primerGrupo = true;
      for ( Html.Grupo g : grupos ) {
        if ( !primerGrupo ) {
          sb.append( ">\n" );
        }
        for ( int i = 0; i < g.lineas.size(); i++ ) {
          String l = enLinea( g.lineas.get( i ) );
          if ( g.lista ) {
            sb.append( "> - " ).append( l ).append( '\n' );
          } else {
            String inicio = primerGrupo && i == 0 && !prefijo.isEmpty() ? prefijo + l : inicioSeguro( l );
            sb.append( "> " ).append( inicio ).append( i + 1 < g.lineas.size() ? "  \n" : "\n" );
          }
        }
        primerGrupo = false;
      }
      sb.append( '\n' );
    }

    void diagrama( Diagrama d ) throws Exception {
      List<Dibujo> ds = Figuras.dibujos( d, Objetivo.PANTALLA, inf.idioma );
      if ( ds != null ) {
        propio( d, ds );
        return;
      }
      boolean hayImagen = d.imagen != null && d.imagen.datos != null && d.imagen.datos.length > 0;
      if ( hayImagen ) {
        boolean lienzo = !lienzoUsado && ( Diagrama.FLUJO.equals( d.clase ) || Diagrama.JOB.equals( d.clase ) );
        String nombre = lienzo ? "lienzo" : d.imagen.id != null ? d.imagen.id : d.id != null ? d.id : "diagrama";
        lienzoUsado |= lienzo;
        imagen( d.imagen, nombre, d.imagen.alt != null ? d.imagen.alt : d.alt );
      }
      if ( d.mermaid != null && !d.mermaid.trim().isEmpty() ) {
        String texto = d.mermaid.replace( "\r\n", "\n" ).trim();
        String c = cerca( texto );
        sb.append( c ).append( "mermaid\n" ).append( texto ).append( '\n' ).append( c ).append( "\n\n" );
      } else if ( !hayImagen && d.alternativa != null ) {
        tabla( d.alternativa );
      }
    }

    /**
     * Diagrama propio: cada parte como .svg en la carpeta de archivos, el lienzo (como imagen si no es una
     * tira; si lo es, un enlace al .png) y el codigo Mermaid plegado ({@code > [!note]-}: Obsidian lo
     * muestra cerrado y dibuja el diagrama al abrirlo; GitHub lo muestra como cita).
     */
    void propio( Diagrama d, List<Dibujo> ds ) throws Exception {
      String base = d.id != null ? d.id : "diagrama";
      String alt = d.alt != null ? d.alt : d.titulo != null ? d.titulo : "";
      for ( Dibujo x : ds ) {
        String nombre = archivo( ds.size() > 1 ? base + "-" + x.parte : base, "svg" );
        File f = new File( carpeta, nombre );
        Html.escribir( f, RenderImagenes.suelto( x.svgEscalado() ) );
        archivos.add( f );
        if ( ds.size() > 1 ) {
          cursiva( Figuras.parte( inf.idioma, x.parte, x.partes ) );
        }
        sb.append( "![" ).append( esc( alt ) ).append( "](" ).append( ruta( carpeta.getName(), nombre ) ).append(
          ")\n\n" );
      }
      Imagen lz = Figuras.lienzo( d );
      if ( lz != null ) {
        boolean lienzo = !lienzoUsado && ( Diagrama.FLUJO.equals( d.clase ) || Diagrama.JOB.equals( d.clase ) );
        lienzoUsado |= lienzo;
        String nombreLz = lienzo ? "lienzo" : lz.id != null ? lz.id : base + "-lienzo";
        String altLz = lz.alt != null ? lz.alt : alt;
        if ( Figuras.proporcionada( lz ) ) {
          imagen( lz, nombreLz, altLz );
        } else {
          String nombre = archivo( nombreLz, Imagen.JPG.equals( lz.mime ) ? "jpg" : "png" );
          File f = new File( carpeta, nombre );
          Html.escribir( f, lz.datos );
          archivos.add( f );
          String texto = Rotulos.de( Diagramas.AREA, inf.idioma ).s( "lienzo.enlace" );
          sb.append( '[' ).append( esc( texto ) ).append( "](" ).append( ruta( carpeta.getName(), nombre ) ).append(
            ")\n\n" );
        }
      }
      if ( d.mermaid != null && !d.mermaid.trim().isEmpty() ) {
        String texto = d.mermaid.replace( "\r\n", "\n" ).trim();
        String c = cerca( texto );
        sb.append( "> [!note]- " ).append( esc( t( "mermaid_code" ) ) ).append( '\n' );
        sb.append( "> " ).append( c ).append( "mermaid\n" );
        for ( String l : texto.split( "\n" ) ) {
          sb.append( l.isEmpty() ? ">" : "> " + l ).append( '\n' );
        }
        sb.append( "> " ).append( c ).append( "\n\n" );
      }
    }

    void grafico( Grafico g ) throws Exception {
      String svg = null;
      try {
        svg = GraficosSvg.svg( g, tema, RenderHtml.ANCHO_GRAFICO, inf.idioma );
      } catch ( Throwable e ) {
        svg = null;
      }
      if ( svg != null && svg.contains( "<svg" ) ) {
        String nombre = archivo( g.id != null ? g.id : "grafico", "svg" );
        File f = new File( carpeta, nombre );
        Html.escribir( f, svg );
        archivos.add( f );
        String alt = g.titulo != null ? g.titulo : g.clase;
        sb.append( "![" ).append( esc( alt ) ).append( "](" ).append( ruta( carpeta.getName(), nombre ) ).append(
          ")\n\n" );
      } else {
        String nombre = g.titulo != null ? g.titulo : g.id != null ? g.id : g.clase;
        avisos.add( r.f( "chart_unavailable", nombre ) );
        tabla( RenderHtml.tablaDeGrafico( g, r, loc ) );
      }
    }

    void imagen( Imagen im, String base, String alt ) throws Exception {
      if ( im == null || im.datos == null || im.datos.length == 0 ) {
        cursiva( t( "image_unavailable" ) );
        return;
      }
      String ext = Imagen.JPG.equals( im.mime ) ? "jpg" : "png";
      String nombre = archivo( base, ext );
      File f = new File( carpeta, nombre );
      Html.escribir( f, im.datos );
      archivos.add( f );
      sb.append( "![" ).append( esc( alt == null ? "" : alt ) ).append( "](" ).append( ruta( carpeta.getName(),
        nombre ) ).append( ")\n\n" );
    }

    /** Nombre de archivo unico dentro de la carpeta: id seguro mas extension (-2, -3... si se repite). */
    String archivo( String base, String ext ) {
      String b = Html.idSeguro( base );
      String n = b + "." + ext;
      int i = 2;
      while ( !nombres.add( n.toLowerCase( Locale.ROOT ) ) ) {
        n = b + "-" + i++ + "." + ext;
      }
      return n;
    }
  }
}
