package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.encryption.Encr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.lint.Nodos;

/**
 * El KETTLE_HOME de la JVM de las pruebas (0.7.1): por defecto ({@code environment.user_kettle_home}, true) se copian
 * a su {@code kettle-home/.kettle} temporal las conexiones del {@code shared.xml} del usuario y su
 * {@code kettle.properties}, asi una transformacion que usa una conexion compartida o una variable de
 * kettle.properties corre igual que en Spoon. Nada mas del KETTLE_HOME (ni repositorios, ni metastore, ni plugins).
 * <ul>
 * <li>La copia vive solo en la carpeta temporal de la corrida y se borra con ella: nunca va a una prueba, a un dorado
 * ni al proyecto.</li>
 * <li>Las contraseñas (las de las conexiones, descifradas, y los valores de las variables con nombre de secreto) se
 * tapan en el resultado y en el log que se muestra ({@link #secretos}).</li>
 * <li>El corrector no lo usa para las entregas de los alumnos (su KETTLE_HOME sigue vacio).</li>
 * </ul>
 *
 * @dueño P1 (0.7.1)
 */
final class PruebasKettleHome {

  static final String KETTLE_PROPERTIES = "kettle.properties";
  static final String SHARED = "shared.xml";
  /** Variables cuyo valor se trata como secreto. */
  static final Pattern SECRETA = Pattern.compile( "(?i).*(pass|pwd|clave|secret|token|key).*" );

  /** De donde se copia: null = el KETTLE_HOME efectivo de esta JVM (Spoon). Solo lo fijan las pruebas. */
  static volatile File origen;

  private PruebasKettleHome() {
  }

  /** La carpeta {@code .kettle} del usuario: {@link #origen} o la de Kettle en esta JVM ($KETTLE_HOME/.kettle). */
  static File carpetaDelUsuario() {
    File o = origen;
    if ( o != null ) {
      return o;
    }
    try {
      return new File( Const.getKettleDirectory() );
    } catch ( Throwable t ) {
      return null;
    }
  }

  /** El shared.xml efectivo: KETTLE_SHARED_OBJECTS (si esta) o {@code <.kettle>/shared.xml}. */
  static File sharedDe( File kettleDir ) {
    if ( origen == null ) {
      String v = System.getProperty( "KETTLE_SHARED_OBJECTS" );
      if ( v != null && !v.trim().isEmpty() && new File( v.trim() ).isFile() ) {
        return new File( v.trim() );
      }
    }
    return new File( kettleDir, SHARED );
  }

  /**
   * Copia a {@code destino} (la {@code .kettle} vacia de la hija) el kettle.properties y las conexiones del shared.xml
   * de {@code desde}. Devuelve lo que copio ("kettle.properties (12 variables)", "shared.xml (3 connections)").
   * Nunca lanza por un archivo que falta.
   */
  static List<String> copiar( File desde, File destino ) throws IOException {
    List<String> hecho = new ArrayList<String>();
    if ( desde == null || !desde.isDirectory() ) {
      return hecho;
    }
    if ( !destino.isDirectory() && !destino.mkdirs() && !destino.isDirectory() ) {
      throw new IOException( "cannot create " + destino );
    }
    File kp = new File( desde, KETTLE_PROPERTIES );
    if ( kp.isFile() ) {
      byte[] b = java.nio.file.Files.readAllBytes( kp.toPath() );
      try ( OutputStream os = new FileOutputStream( new File( destino, KETTLE_PROPERTIES ) ) ) {
        os.write( b );
      }
      hecho.add( KETTLE_PROPERTIES + " (" + leerPropiedades( kp ).size() + " variables)" );
    }
    File sh = sharedDe( desde );
    if ( sh.isFile() ) {
      try {
        int n = copiarConexiones( sh, new File( destino, SHARED ) );
        hecho.add( SHARED + " (" + n + " connections)" );
      } catch ( Exception e ) {
        hecho.add( SHARED + " not copied: " + RunMotor.primeraLinea( e ) );
      }
    }
    return hecho;
  }

  /** Solo los {@code <connection>} del shared.xml (ni slaves, ni particiones, ni clusters). */
  private static int copiarConexiones( File shared, File destino ) throws Exception {
    Element raiz;
    try ( InputStream in = new FileInputStream( shared ) ) {
      raiz = Nodos.parse( in );
    }
    Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    Element nueva = doc.createElement( "sharedobjects" );
    doc.appendChild( nueva );
    int n = 0;
    for ( Element c : Nodos.hijos( raiz, "connection" ) ) {
      Node x = doc.importNode( c, true );
      nueva.appendChild( x );
      n++;
    }
    TransformerFactory tf = TransformerFactory.newInstance();
    Transformer t = tf.newTransformer();
    t.setOutputProperty( OutputKeys.ENCODING, "UTF-8" );
    t.setOutputProperty( OutputKeys.INDENT, "yes" );
    try ( OutputStream os = new FileOutputStream( destino ) ) {
      t.transform( new DOMSource( doc ), new StreamResult( os ) );
    }
    return n;
  }

  private static Properties leerPropiedades( File f ) {
    Properties p = new Properties();
    try ( InputStream in = new FileInputStream( f ) ) {
      p.load( new InputStreamReader( in, StandardCharsets.ISO_8859_1 ) );
    } catch ( IOException ignored ) {
      // vacio
    }
    return p;
  }

  /**
   * Lo que no puede aparecer en un resultado: las contraseñas de las conexiones del shared.xml (descifradas y como
   * estan escritas) y los valores de las variables de kettle.properties con nombre de secreto (de 4 caracteres o
   * mas).
   */
  static List<String> secretos( File kettleDir ) {
    Set<String> s = new LinkedHashSet<String>();
    if ( kettleDir == null ) {
      return new ArrayList<String>();
    }
    File kp = new File( kettleDir, KETTLE_PROPERTIES );
    if ( kp.isFile() ) {
      for ( Map.Entry<Object, Object> e : leerPropiedades( kp ).entrySet() ) {
        String v = String.valueOf( e.getValue() ).trim();
        if ( SECRETA.matcher( String.valueOf( e.getKey() ) ).matches() && v.length() >= 4 ) {
          s.add( v );
          agregarDescifrada( s, v );
        }
      }
    }
    File sh = new File( kettleDir, SHARED );
    if ( sh.isFile() ) {
      try ( InputStream in = new FileInputStream( sh ) ) {
        Element raiz = Nodos.parse( in );
        for ( Element c : Nodos.hijos( raiz, "connection" ) ) {
          String pw = Nodos.txt( c, "password" );
          if ( pw.length() >= 4 ) {
            s.add( pw );
            agregarDescifrada( s, pw );
          }
        }
      } catch ( Exception ignored ) {
        // sin conexiones
      }
    }
    return new ArrayList<String>( s );
  }

  private static void agregarDescifrada( Set<String> s, String v ) {
    try {
      String d = Encr.decryptPasswordOptionallyEncrypted( v );
      if ( d != null && d.length() >= 4 ) {
        s.add( d );
      }
    } catch ( Throwable ignored ) {
      // no cifrada
    }
  }
}
