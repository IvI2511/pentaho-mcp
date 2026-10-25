package io.github.ivi2511.pentahomcp.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * El XML canónico de un .ktr o .kjb (diseño E2): el mismo archivo escrito siempre igual, para que dos guardados sin
 * cambios reales den cero diff en git.
 * <ul>
 * <li>Sin los campos volátiles que Spoon reescribe en cada guardado: {@code modified_user} y {@code modified_date}
 * (Kettle los lee si están y no los necesita).</li>
 * <li>Sangría de 2 espacios por nivel, fines de línea LF, UTF-8 sin BOM, la declaración XML de Kettle.</li>
 * <li>Los textos escapados como los escribe Kettle ({@code XMLHandler.addTagValue}: {@code &amp;amp; &amp;lt; &amp;gt;
 * &amp;#34; &amp;#39;}); un CR suelto como {@code &amp;#xd;} (si no, el parser lo convertiría en LF). Valor vacío:
 * {@code <tag/>}; contenedor vacío: apertura y cierre en dos líneas, como Kettle.</li>
 * <li>Orden estable donde el orden no significa nada y Kettle no lo conserva: los atributos de cada step o entrada
 * (grupos por nombre y atributos por clave: Kettle los guarda en un HashMap) y los parámetros (Kettle los lista
 * ordenados). Steps, saltos, conexiones, notas y campos quedan en su orden: Kettle lo conserva y en algunos casos
 * importa (el orden de los saltos decide el reparto de filas en round robin).</li>
 * </ul>
 * Nada más cambia: el valor de cada tag queda igual, carácter por carácter (lo prueba PruebaNormalizador con Kettle).
 */
public final class Normalizador {

  public static final String DECLARACION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

  private Normalizador() {
  }

  /** El texto canónico de un .ktr/.kjb. */
  public static String normalizar( byte[] xml ) throws Xmls.XmlInvalido {
    Document d = Xmls.parsear( xml );
    Element raiz = d.getDocumentElement();
    String tag = raiz.getTagName();
    if ( "transformation".equals( tag ) ) {
      quitarVolatiles( Xmls.hijo( raiz, "info" ) );
      ordenarParametros( Xmls.hijo( Xmls.hijo( raiz, "info" ), "parameters" ) );
    } else if ( "job".equals( tag ) ) {
      quitarVolatiles( raiz );
      ordenarParametros( Xmls.hijo( raiz, "parameters" ) );
    } else {
      throw new Xmls.XmlInvalido( "Not a transformation or a job: the root element is <" + tag + ">" );
    }
    ordenarAtributos( raiz );
    StringBuilder sb = new StringBuilder( xml.length + 256 );
    sb.append( DECLARACION ).append( '\n' );
    for ( Node n = d.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.COMMENT_NODE ) {
        sb.append( "<!--" ).append( n.getNodeValue() ).append( "-->\n" );
      }
    }
    escribir( raiz, 0, sb );
    return sb.toString();
  }

  public static String normalizar( String xml ) throws Xmls.XmlInvalido {
    return normalizar( xml.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
  }

  private static void quitarVolatiles( Element padre ) {
    if ( padre == null ) {
      return;
    }
    for ( Element e : Xmls.hijos( padre ) ) {
      if ( Documento.VOLATILES.contains( e.getTagName() ) ) {
        Node antes = e.getPreviousSibling();
        if ( antes != null && antes.getNodeType() == Node.TEXT_NODE && antes.getNodeValue().trim().isEmpty() ) {
          padre.removeChild( antes );
        }
        padre.removeChild( e );
      }
    }
  }

  private static void ordenarParametros( Element ps ) {
    if ( ps == null ) {
      return;
    }
    reordenar( ps, "parameter", ( a, b ) -> Xmls.texto( a, "name" ).compareTo( Xmls.texto( b, "name" ) ) );
  }

  /** Los &lt;attributes&gt; de steps, entradas y del documento: grupos por nombre, atributos por clave. */
  private static void ordenarAtributos( Element e ) {
    for ( Element h : Xmls.hijos( e ) ) {
      if ( "attributes".equals( h.getTagName() ) && !Xmls.hijos( h, "group" ).isEmpty() ) {
        reordenar( h, "group", ( a, b ) -> Xmls.texto( a, "name" ).compareTo( Xmls.texto( b, "name" ) ) );
        for ( Element g : Xmls.hijos( h, "group" ) ) {
          reordenar( g, "attribute", ( a, b ) -> Xmls.texto( a, "key" ).compareTo( Xmls.texto( b, "key" ) ) );
        }
      } else {
        ordenarAtributos( h );
      }
    }
  }

  /** Ordena los hijos con ese tag (estable), dejándolos donde estaba el primero. Solo si todos los hijos son de ese tag. */
  private static void reordenar( Element padre, String tag, Comparator<Element> orden ) {
    List<Element> todos = Xmls.hijos( padre );
    List<Element> estos = Xmls.hijos( padre, tag );
    if ( estos.size() < 2 ) {
      return;
    }
    List<Element> ordenados = new ArrayList<Element>( estos );
    Collections.sort( ordenados, orden );
    if ( ordenados.equals( estos ) ) {
      return;
    }
    // solo reordena cuando los elementos de ese tag van seguidos (lo habitual); si no, lo deja como está
    int primero = todos.indexOf( estos.get( 0 ) );
    for ( int i = 0; i < estos.size(); i++ ) {
      if ( todos.get( primero + i ) != estos.get( i ) ) {
        return;
      }
    }
    Node despues = estos.get( estos.size() - 1 ).getNextSibling();
    for ( Element e : estos ) {
      padre.removeChild( e );
    }
    for ( Element e : ordenados ) {
      padre.insertBefore( e, despues );
    }
  }

  // ================================================================ escritura

  private static void sangria( int nivel, StringBuilder sb ) {
    for ( int i = 0; i < nivel; i++ ) {
      sb.append( "  " );
    }
  }

  private static void escribir( Element e, int nivel, StringBuilder sb ) {
    sangria( nivel, sb );
    sb.append( '<' ).append( e.getTagName() );
    atributos( e, sb );
    boolean hijos = Xmls.tieneHijos( e );
    if ( !hijos ) {
      String texto = textoDirecto( e );
      boolean cdata = tieneCdata( e );
      if ( texto.isEmpty() ) {
        sb.append( "/>\n" );
      } else if ( texto.trim().isEmpty() && ( texto.indexOf( '\n' ) >= 0 || texto.indexOf( '\r' ) >= 0 ) ) {
        // contenedor vacío (los <parameters> de Kettle): apertura y cierre en dos líneas
        sb.append( ">\n" );
        sangria( nivel, sb );
        sb.append( "</" ).append( e.getTagName() ).append( ">\n" );
      } else if ( cdata ) {
        sb.append( '>' );
        nodosInline( e, sb );
        sb.append( "</" ).append( e.getTagName() ).append( ">\n" );
      } else {
        sb.append( '>' ).append( escapar( texto ) ).append( "</" ).append( e.getTagName() ).append( ">\n" );
      }
      return;
    }
    if ( mixto( e ) ) {
      // texto y elementos mezclados (no pasa en Kettle): tal cual, sin sangría adentro
      sb.append( '>' );
      nodosInline( e, sb );
      sb.append( "</" ).append( e.getTagName() ).append( ">\n" );
      return;
    }
    sb.append( ">\n" );
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.ELEMENT_NODE ) {
        escribir( (Element) n, nivel + 1, sb );
      } else if ( n.getNodeType() == Node.COMMENT_NODE ) {
        sangria( nivel + 1, sb );
        sb.append( "<!--" ).append( n.getNodeValue() ).append( "-->\n" );
      }
    }
    sangria( nivel, sb );
    sb.append( "</" ).append( e.getTagName() ).append( ">\n" );
  }

  private static void atributos( Element e, StringBuilder sb ) {
    NamedNodeMap as = e.getAttributes();
    if ( as == null || as.getLength() == 0 ) {
      return;
    }
    List<Attr> l = new ArrayList<Attr>();
    for ( int i = 0; i < as.getLength(); i++ ) {
      l.add( (Attr) as.item( i ) );
    }
    Collections.sort( l, ( a, b ) -> a.getName().compareTo( b.getName() ) );
    for ( Attr a : l ) {
      sb.append( ' ' ).append( a.getName() ).append( "=\"" ).append( escapar( a.getValue() ) ).append( '"' );
    }
  }

  private static String textoDirecto( Element e ) {
    StringBuilder sb = new StringBuilder();
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE ) {
        sb.append( n.getNodeValue() );
      }
    }
    return sb.toString();
  }

  private static boolean tieneCdata( Element e ) {
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.CDATA_SECTION_NODE ) {
        return true;
      }
    }
    return false;
  }

  private static boolean mixto( Element e ) {
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( ( n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE )
        && !n.getNodeValue().trim().isEmpty() ) {
        return true;
      }
    }
    return false;
  }

  /** Los hijos tal cual (texto escapado, CDATA como CDATA, elementos en una línea). */
  private static void nodosInline( Node e, StringBuilder sb ) {
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      switch ( n.getNodeType() ) {
        case Node.TEXT_NODE:
          sb.append( escapar( n.getNodeValue() ) );
          break;
        case Node.CDATA_SECTION_NODE:
          sb.append( "<![CDATA[" ).append( n.getNodeValue().replace( "]]>", "]]]]><![CDATA[>" ) ).append( "]]>" );
          break;
        case Node.COMMENT_NODE:
          sb.append( "<!--" ).append( n.getNodeValue() ).append( "-->" );
          break;
        case Node.ELEMENT_NODE:
          Element h = (Element) n;
          sb.append( '<' ).append( h.getTagName() );
          atributos( h, sb );
          if ( h.getFirstChild() == null ) {
            sb.append( "/>" );
          } else {
            sb.append( '>' );
            nodosInline( h, sb );
            sb.append( "</" ).append( h.getTagName() ).append( '>' );
          }
          break;
        default:
          break;
      }
    }
  }

  /** Como XMLHandler.addTagValue de Kettle, y el CR como referencia (si no, el parser lo convierte en LF). */
  static String escapar( String s ) {
    StringBuilder sb = new StringBuilder( s.length() + 16 );
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      switch ( c ) {
        case '&':
          sb.append( "&amp;" );
          break;
        case '<':
          sb.append( "&lt;" );
          break;
        case '>':
          sb.append( "&gt;" );
          break;
        case '"':
          sb.append( "&#34;" );
          break;
        case '\'':
          sb.append( "&#39;" );
          break;
        case '\r':
          sb.append( "&#xd;" );
          break;
        default:
          if ( c < 0x20 && c != '\n' && c != '\t' ) {
            sb.append( String.format( Locale.ROOT, "&#x%x;", (int) c ) );
          } else {
            sb.append( c );
          }
      }
    }
    return sb.toString();
  }
}
