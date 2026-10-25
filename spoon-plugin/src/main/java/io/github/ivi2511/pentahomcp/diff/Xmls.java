package io.github.ivi2511.pentahomcp.diff;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Lectura de XML para el diff y el normalizador: solo el JDK (corre sin Kettle, desde la línea de comandos). Sin DTD ni
 * entidades externas.
 */
public final class Xmls {

  private Xmls() {
  }

  /** Error de lectura de un .ktr/.kjb (XML roto, raíz desconocida). */
  public static final class XmlInvalido extends Exception {
    private static final long serialVersionUID = 1L;

    public XmlInvalido( String msg ) {
      super( msg );
    }
  }

  /** Parsea bytes (con o sin BOM; la declaración manda la codificación). Conserva los espacios. */
  public static Document parsear( byte[] bytes ) throws XmlInvalido {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware( false );
      f.setExpandEntityReferences( false );
      f.setCoalescing( false );
      f.setIgnoringComments( false );
      try {
        f.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
      } catch ( Exception ignorada ) {
        // parser sin esa feature
      }
      try {
        f.setFeature( "http://xml.org/sax/features/external-general-entities", false );
        f.setFeature( "http://xml.org/sax/features/external-parameter-entities", false );
      } catch ( Exception ignorada ) {
        // idem
      }
      DocumentBuilder b = f.newDocumentBuilder();
      b.setErrorHandler( new ErrorHandler() {
        @Override
        public void warning( SAXParseException e ) {
        }

        @Override
        public void error( SAXParseException e ) throws SAXParseException {
          throw e;
        }

        @Override
        public void fatalError( SAXParseException e ) throws SAXParseException {
          throw e;
        }
      } );
      return b.parse( new ByteArrayInputStream( bytes ) );
    } catch ( SAXParseException e ) {
      throw new XmlInvalido( "Invalid XML (line " + e.getLineNumber() + "): " + e.getMessage() );
    } catch ( Exception e ) {
      throw new XmlInvalido( "Invalid XML: " + e.getMessage() );
    }
  }

  public static Document parsear( String xml ) throws XmlInvalido {
    return parsear( xml.getBytes( StandardCharsets.UTF_8 ) );
  }

  public static byte[] leer( Path p ) throws IOException {
    return Files.readAllBytes( p );
  }

  /** Hijos elemento, en orden. */
  public static List<Element> hijos( Node padre ) {
    List<Element> out = new ArrayList<Element>();
    if ( padre == null ) {
      return out;
    }
    for ( Node n = padre.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.ELEMENT_NODE ) {
        out.add( (Element) n );
      }
    }
    return out;
  }

  /** Hijos elemento con ese tag. */
  public static List<Element> hijos( Node padre, String tag ) {
    List<Element> out = new ArrayList<Element>();
    for ( Element e : hijos( padre ) ) {
      if ( e.getTagName().equals( tag ) ) {
        out.add( e );
      }
    }
    return out;
  }

  /** El primer hijo con ese tag, o null. */
  public static Element hijo( Node padre, String tag ) {
    if ( padre == null ) {
      return null;
    }
    for ( Node n = padre.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.ELEMENT_NODE && ( (Element) n ).getTagName().equals( tag ) ) {
        return (Element) n;
      }
    }
    return null;
  }

  /** true si el elemento tiene hijos elemento. */
  public static boolean tieneHijos( Node e ) {
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.ELEMENT_NODE ) {
        return true;
      }
    }
    return false;
  }

  /** El texto directo (texto y CDATA) de un elemento hoja, con los fines de línea en \n; "" si no hay. */
  public static String texto( Node e ) {
    if ( e == null ) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE ) {
        sb.append( n.getNodeValue() );
      }
    }
    return sb.toString().replace( "\r\n", "\n" ).replace( '\r', '\n' );
  }

  /** El texto del hijo con ese tag ("" si no está). */
  public static String texto( Node padre, String tag ) {
    return texto( hijo( padre, tag ) );
  }

  public static int entero( String s, int def ) {
    try {
      return Integer.parseInt( s.trim() );
    } catch ( RuntimeException e ) {
      return def;
    }
  }
}
