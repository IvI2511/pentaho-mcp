package io.github.ivi2511.pentahomcp.lint;

import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Lectura del XML de un step, una entrada o un archivo. Sin DTD ni entidades externas: el archivo es de otro. */
public final class Nodos {

  private static final ThreadLocal<DocumentBuilder> DB = new ThreadLocal<DocumentBuilder>() {
    @Override
    protected DocumentBuilder initialValue() {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware( false );
      f.setExpandEntityReferences( false );
      // cada feature por separado: el parser que trae PDI en lib/ puede no conocer alguna
      for ( String feature : new String[] { XMLConstants.FEATURE_SECURE_PROCESSING,
        "http://apache.org/xml/features/disallow-doctype-decl",
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities" } ) {
        try {
          f.setFeature( feature, !feature.contains( "external" ) );
        } catch ( Exception ignored ) {
          // sigue con las que sí conoce
        }
      }
      try {
        return f.newDocumentBuilder();
      } catch ( Exception e ) {
        throw new IllegalStateException( e );
      }
    }
  };

  private Nodos() {
  }

  public static Element parse( String xml ) throws Exception {
    DocumentBuilder b = DB.get();
    b.reset();
    return b.parse( new InputSource( new StringReader( xml ) ) ).getDocumentElement();
  }

  public static Element parse( InputStream in ) throws Exception {
    DocumentBuilder b = DB.get();
    b.reset();
    return b.parse( new InputSource( in ) ).getDocumentElement();
  }

  /** Hijos elemento con ese tag (null = todos). */
  public static List<Element> hijos( Element e, String tag ) {
    List<Element> l = new ArrayList<Element>();
    if ( e == null ) {
      return l;
    }
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && ( tag == null || ( (Element) n ).getTagName().equals( tag ) ) ) {
        l.add( (Element) n );
      }
    }
    return l;
  }

  public static Element hijo( Element e, String tag ) {
    if ( e == null ) {
      return null;
    }
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && ( (Element) n ).getTagName().equals( tag ) ) {
        return (Element) n;
      }
    }
    return null;
  }

  /** Texto del primer hijo con ese tag, recortado; "" si no está. */
  public static String txt( Element e, String tag ) {
    Element k = hijo( e, tag );
    return k == null ? "" : k.getTextContent().trim();
  }

  /** Texto sin recortar (los nombres de step distinguen "X" de "X "). */
  public static String crudo( Element e, String tag ) {
    Element k = hijo( e, tag );
    return k == null ? "" : k.getTextContent();
  }

  /** Los textos de contenedor/item[/nombre]: p. ej. ("fields", "field", "name") o ("keys_1", "key", null). */
  public static List<String> nombres( Element e, String contenedor, String item, String tagNombre ) {
    List<String> r = new ArrayList<String>();
    for ( Element f : hijos( hijo( e, contenedor ), item ) ) {
      String n = tagNombre == null ? f.getTextContent().trim() : txt( f, tagNombre );
      if ( !n.isEmpty() ) {
        r.add( n );
      }
    }
    return r;
  }

  public static boolean esHoja( Element e ) {
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element ) {
        return false;
      }
    }
    return true;
  }

  /** Hojas del árbol con su padre: {hoja, padre}. */
  public static List<Element[]> hojas( Element raiz ) {
    List<Element[]> out = new ArrayList<Element[]>();
    hojas( raiz, null, out );
    return out;
  }

  private static void hojas( Element e, Element padre, List<Element[]> out ) {
    if ( esHoja( e ) ) {
      out.add( new Element[] { e, padre } );
      return;
    }
    for ( Element k : hijos( e, null ) ) {
      hojas( k, e, out );
    }
  }

  /** El ancestro más cercano con uno de esos tags, o null. */
  public static Element ancestro( Element e, String... tags ) {
    for ( Node n = e.getParentNode(); n instanceof Element; n = n.getParentNode() ) {
      String t = ( (Element) n ).getTagName();
      for ( String x : tags ) {
        if ( x.equals( t ) ) {
          return (Element) n;
        }
      }
    }
    return null;
  }
}
