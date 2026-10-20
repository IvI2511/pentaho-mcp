package io.github.ivi2511.pentahomcp;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/** XML de steps y entradas de job: mezclar la configuracion de la IA con los valores por defecto. */
final class Xml {

  /** Tags del envoltorio &lt;step&gt; que arma el plugin, no la configuracion del step. */
  static final Set<String> TAGS_ENVOLTORIO_STEP = new HashSet<String>( Arrays.asList(
    "name", "type", "description", "distribute", "custom_distribution", "copies", "partitioning",
    "attributes", "cluster_schema", "remotesteps", "GUI" ) );

  /** Idem para &lt;entry&gt; de un job. */
  static final Set<String> TAGS_ENVOLTORIO_ENTRY = new HashSet<String>( Arrays.asList(
    "name", "type", "description", "attributes", "parallel", "draw", "nr", "xloc", "yloc" ) );

  private Xml() {
  }

  /** Parsea un documento; si no es XML valido, ToolError con el motivo. */
  static Document parse( String xml ) throws ToolError {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware( false );
      f.setExpandEntityReferences( false );
      try {
        f.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
      } catch ( Exception ignored ) {
        // parser sin esa feature
      }
      return f.newDocumentBuilder().parse( new InputSource( new StringReader( xml ) ) );
    } catch ( Exception e ) {
      throw new ToolError( "XML invalido: " + e.getMessage() );
    }
  }

  /**
   * Hijos elemento de primer nivel de un fragmento de configuracion. Acepta el cuerpo suelto
   * ("&lt;separator&gt;;&lt;/separator&gt;&lt;fields&gt;...") o envuelto en &lt;step&gt;/&lt;entry&gt;.
   */
  static List<Element> hijos( String fragmento ) throws ToolError {
    String f = fragmento == null ? "" : fragmento.trim();
    if ( f.startsWith( "<?xml" ) ) {
      f = f.substring( f.indexOf( "?>" ) + 2 ).trim();
    }
    Document d = parse( "<raiz>" + f + "</raiz>" );
    Element raiz = d.getDocumentElement();
    List<Element> primeros = elementos( raiz );
    if ( primeros.size() == 1 && ( "step".equals( primeros.get( 0 ).getTagName() )
      || "entry".equals( primeros.get( 0 ).getTagName() ) ) ) {
      return elementos( primeros.get( 0 ) );
    }
    return primeros;
  }

  /**
   * Mezcla por tag de primer nivel: cada tag que trae la IA reemplaza TODAS las apariciones de ese
   * tag en los valores por defecto (en la posicion de la primera). Lo que la IA no manda queda con
   * el valor por defecto. Los tags de 'ignorar' (el envoltorio) se descartan del fragmento.
   *
   * @return el cuerpo mezclado, sin envoltorio, listo para meter dentro de &lt;step&gt;/&lt;entry&gt;.
   */
  static String mezclar( String porDefecto, String fragmento, Set<String> ignorar ) throws ToolError {
    Document d = parse( "<raiz>" + ( porDefecto == null ? "" : porDefecto ) + "</raiz>" );
    Element raiz = d.getDocumentElement();
    List<Element> nuevos = new ArrayList<Element>();
    Set<String> tags = new LinkedHashSet<String>();
    for ( Element e : hijos( fragmento ) ) {
      if ( ignorar != null && ignorar.contains( e.getTagName() ) ) {
        continue;
      }
      nuevos.add( (Element) d.importNode( e, true ) );
      tags.add( e.getTagName() );
    }
    for ( String tag : tags ) {
      List<Element> viejos = new ArrayList<Element>();
      for ( Element e : elementos( raiz ) ) {
        if ( e.getTagName().equals( tag ) ) {
          viejos.add( e );
        }
      }
      Node ancla = viejos.isEmpty() ? null : viejos.get( 0 ); // los nuevos van donde estaba el primero
      for ( Element n : nuevos ) {
        if ( n.getTagName().equals( tag ) ) {
          raiz.insertBefore( n, ancla ); // ancla null = al final
        }
      }
      for ( Element v : viejos ) {
        raiz.removeChild( v );
      }
    }
    StringBuilder sb = new StringBuilder();
    for ( Element e : elementos( raiz ) ) {
      sb.append( texto( e ) ).append( '\n' );
    }
    return sb.toString();
  }

  /** Quita del cuerpo los tags del envoltorio (por ejemplo del getXML() de un JobEntry). */
  static String sinEnvoltorio( String cuerpo, Set<String> ignorar ) throws ToolError {
    StringBuilder sb = new StringBuilder();
    for ( Element e : hijos( cuerpo ) ) {
      if ( !ignorar.contains( e.getTagName() ) ) {
        sb.append( texto( e ) ).append( '\n' );
      }
    }
    return sb.toString();
  }

  static List<Element> elementos( Node padre ) {
    List<Element> out = new ArrayList<Element>();
    for ( Node n = padre.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.ELEMENT_NODE ) {
        out.add( (Element) n );
      }
    }
    return out;
  }

  /** Serializa un nodo sin declaracion XML. */
  static String texto( Node n ) throws ToolError {
    try {
      Transformer t = TransformerFactory.newInstance().newTransformer();
      t.setOutputProperty( OutputKeys.OMIT_XML_DECLARATION, "yes" );
      t.setOutputProperty( OutputKeys.ENCODING, "UTF-8" );
      StringWriter w = new StringWriter();
      t.transform( new DOMSource( n ), new StreamResult( w ) );
      return w.toString();
    } catch ( Exception e ) {
      throw new ToolError( "No se pudo serializar XML: " + e.getMessage() );
    }
  }

  /** Enmascara contrasenias antes de devolver XML a la IA. */
  static String sinPasswords( String xml ) {
    return xml == null ? null
      : xml.replaceAll( "(?s)<password>.*?</password>", "<password>****</password>" );
  }

  static String escapar( String s ) {
    if ( s == null ) {
      return "";
    }
    return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" ).replace( "\"", "&quot;" );
  }
}
