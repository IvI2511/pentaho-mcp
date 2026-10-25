package io.github.ivi2511.pentahomcp.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Una transformación (.ktr) o un job (.kjb) leído del XML, listo para comparar: propiedades del documento, steps o
 * entradas con su configuración aplanada ({@link Aplanar}), saltos, manejo de errores, conexiones (sin contraseñas),
 * parámetros y notas. Sin Kettle: solo el XML.
 */
public final class Documento {

  public static final String TRANSFORMACION = "transformation";
  public static final String JOB = "job";

  /** Lo que cambia en cada guardado sin cambiar el significado (también lo quita el normalizador). */
  public static final Set<String> VOLATILES = new HashSet<String>( Arrays.asList( "modified_user",
    "modified_date" ) );

  /** Tags del envoltorio de un step que no son su configuración (la posición va aparte). */
  static final Set<String> FUERA_STEP = new HashSet<String>( Arrays.asList( "name", "type", "GUI" ) );
  /** Idem para una entrada de job. */
  static final Set<String> FUERA_ENTRADA = new HashSet<String>( Arrays.asList( "name", "type", "xloc", "yloc",
    "draw", "nr" ) );
  /**
   * Partes del envoltorio de un step que casi nunca cambian y no dicen qué step es: no cuentan para reconocer un step
   * renombrado.
   */
  static final Set<String> ENVOLTORIO = new HashSet<String>( Arrays.asList( "description", "distribute",
    "custom_distribution", "copies", "partitioning", "attributes", "cluster_schema", "remotesteps", "parallel",
    "attributes_kjc" ) );

  /** Un step (transformación) o una entrada (job). */
  public static final class Nodo {
    /** Clave única: el nombre; en un job, nombre#n para las copias de una entrada (n &gt; 0). */
    public String clave;
    public String nombre;
    public String tipo;
    public int x;
    public int y;
    public int copia;
    /** ruta -> valor, en el orden del XML. */
    public LinkedHashMap<String, String> config = new LinkedHashMap<String, String>();

    /** Las propiedades que identifican al nodo (sin el envoltorio ni las vacías). */
    public LinkedHashMap<String, String> propias() {
      LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
      for ( java.util.Map.Entry<String, String> e : config.entrySet() ) {
        String raiz = e.getKey().split( "/", 2 )[ 0 ];
        if ( !ENVOLTORIO.contains( Aplanar.tagDe( raiz ) ) && !e.getValue().isEmpty() ) {
          m.put( e.getKey(), e.getValue() );
        }
      }
      return m;
    }
  }

  /** Un salto. En una transformación: activo y si es de error; en un job: activo y condición. */
  public static final class Salto {
    public String desde;
    public String hasta;
    public boolean activo = true;
    /** Transformación: el salto lleva las filas con error (manejo de errores del step de origen). */
    public boolean error;
    /** Job: always, success o failure. */
    public String condicion;
  }

  /** Una nota del lienzo. */
  public static final class Nota {
    public String texto;
    public int x;
    public int y;
    /** El resto (tamaño, fuente, colores), para saber si cambió el formato. */
    public String formato;
  }

  public String tipo;
  public String nombre;
  /** Propiedades del documento (info de la transformación o cabecera del job), sin parámetros ni volátiles. */
  public final LinkedHashMap<String, String> propiedades = new LinkedHashMap<String, String>();
  public final LinkedHashMap<String, Nodo> nodos = new LinkedHashMap<String, Nodo>();
  public final List<Salto> saltos = new ArrayList<Salto>();
  /** Manejo de errores: step de origen -> propiedades (target_step, is_enabled, campos...). */
  public final LinkedHashMap<String, LinkedHashMap<String, String>> errores =
    new LinkedHashMap<String, LinkedHashMap<String, String>>();
  /** Conexión -> propiedades (las contraseñas, como secreto). */
  public final LinkedHashMap<String, LinkedHashMap<String, String>> conexiones =
    new LinkedHashMap<String, LinkedHashMap<String, String>>();
  /** Parámetro -> {valor por defecto, descripción}. */
  public final LinkedHashMap<String, String[]> parametros = new LinkedHashMap<String, String[]>();
  public final List<Nota> notas = new ArrayList<Nota>();

  public boolean esJob() {
    return JOB.equals( tipo );
  }

  /** Lee un .ktr o .kjb. */
  public static Documento leer( byte[] xml ) throws Xmls.XmlInvalido {
    return leer( Xmls.parsear( xml ) );
  }

  public static Documento leer( String xml ) throws Xmls.XmlInvalido {
    return leer( Xmls.parsear( xml ) );
  }

  public static Documento leer( Document d ) throws Xmls.XmlInvalido {
    Element raiz = d.getDocumentElement();
    Documento doc = new Documento();
    if ( "transformation".equals( raiz.getTagName() ) ) {
      doc.tipo = TRANSFORMACION;
      leerTrans( raiz, doc );
    } else if ( "job".equals( raiz.getTagName() ) ) {
      doc.tipo = JOB;
      leerJob( raiz, doc );
    } else {
      throw new Xmls.XmlInvalido( "Not a transformation or a job: the root element is <" + raiz.getTagName() + ">" );
    }
    return doc;
  }

  private static void leerTrans( Element raiz, Documento doc ) {
    Element info = Xmls.hijo( raiz, "info" );
    doc.nombre = Xmls.texto( info, "name" );
    Set<String> fueraInfo = new HashSet<String>( VOLATILES );
    fueraInfo.add( "parameters" );
    fueraInfo.add( "created_user" );
    fueraInfo.add( "created_date" );
    doc.propiedades.putAll( Aplanar.aplanar( info, fueraInfo ) );
    parametros( Xmls.hijo( info, "parameters" ), doc );
    Set<String> conocidos = new HashSet<String>( Arrays.asList( "info", "notepads", "order", "step",
      "step_error_handling", "connection" ) );
    for ( Element c : Xmls.hijos( raiz, "connection" ) ) {
      conexion( c, doc );
    }
    notas( Xmls.hijo( raiz, "notepads" ), doc );
    for ( Element s : Xmls.hijos( raiz, "step" ) ) {
      Nodo n = new Nodo();
      n.nombre = Xmls.texto( s, "name" );
      n.clave = n.nombre;
      n.tipo = Xmls.texto( s, "type" ).trim();
      Element gui = Xmls.hijo( s, "GUI" );
      n.x = Xmls.entero( Xmls.texto( gui, "xloc" ), 0 );
      n.y = Xmls.entero( Xmls.texto( gui, "yloc" ), 0 );
      n.config = Aplanar.aplanar( s, FUERA_STEP );
      if ( !doc.nodos.containsKey( n.clave ) ) {
        doc.nodos.put( n.clave, n );
      }
    }
    Element eh = Xmls.hijo( raiz, "step_error_handling" );
    Set<String> conError = new HashSet<String>();
    for ( Element e : Xmls.hijos( eh, "error" ) ) {
      String origen = Xmls.texto( e, "source_step" );
      LinkedHashMap<String, String> p = Aplanar.aplanar( e, new HashSet<String>( Arrays.asList( "source_step" ) ) );
      doc.errores.put( origen, p );
      String activo = Xmls.texto( e, "is_enabled" ).trim();
      if ( activo.equalsIgnoreCase( "Y" ) ) {
        conError.add( origen + "\u0001" + Xmls.texto( e, "target_step" ) );
      }
    }
    for ( Element h : Xmls.hijos( Xmls.hijo( raiz, "order" ), "hop" ) ) {
      Salto s = new Salto();
      s.desde = Xmls.texto( h, "from" );
      s.hasta = Xmls.texto( h, "to" );
      s.activo = !"N".equalsIgnoreCase( Xmls.texto( h, "enabled" ).trim() );
      s.error = conError.contains( s.desde + "\u0001" + s.hasta );
      doc.saltos.add( s );
    }
    otros( raiz, conocidos, doc );
  }

  private static void leerJob( Element raiz, Documento doc ) {
    doc.nombre = Xmls.texto( raiz, "name" );
    Set<String> fuera = new HashSet<String>( VOLATILES );
    fuera.addAll( Arrays.asList( "name", "parameters", "connection", "entries", "hops", "notepads", "created_user",
      "created_date" ) );
    doc.propiedades.putAll( Aplanar.aplanar( raiz, fuera ) );
    parametros( Xmls.hijo( raiz, "parameters" ), doc );
    for ( Element c : Xmls.hijos( raiz, "connection" ) ) {
      conexion( c, doc );
    }
    notas( Xmls.hijo( raiz, "notepads" ), doc );
    for ( Element e : Xmls.hijos( Xmls.hijo( raiz, "entries" ), "entry" ) ) {
      Nodo n = new Nodo();
      n.nombre = Xmls.texto( e, "name" );
      n.copia = Xmls.entero( Xmls.texto( e, "nr" ), 0 );
      n.clave = clave( n.nombre, n.copia );
      n.tipo = Xmls.texto( e, "type" ).trim();
      n.x = Xmls.entero( Xmls.texto( e, "xloc" ), 0 );
      n.y = Xmls.entero( Xmls.texto( e, "yloc" ), 0 );
      n.config = Aplanar.aplanar( e, FUERA_ENTRADA );
      if ( !doc.nodos.containsKey( n.clave ) ) {
        doc.nodos.put( n.clave, n );
      }
    }
    for ( Element h : Xmls.hijos( Xmls.hijo( raiz, "hops" ), "hop" ) ) {
      Salto s = new Salto();
      s.desde = clave( Xmls.texto( h, "from" ), Xmls.entero( Xmls.texto( h, "from_nr" ), 0 ) );
      s.hasta = clave( Xmls.texto( h, "to" ), Xmls.entero( Xmls.texto( h, "to_nr" ), 0 ) );
      s.activo = !"N".equalsIgnoreCase( Xmls.texto( h, "enabled" ).trim() );
      boolean incondicional = "Y".equalsIgnoreCase( Xmls.texto( h, "unconditional" ).trim() );
      boolean evaluacion = !"N".equalsIgnoreCase( Xmls.texto( h, "evaluation" ).trim() );
      s.condicion = incondicional ? "always" : evaluacion ? "success" : "failure";
      doc.saltos.add( s );
    }
  }

  /** nombre, o nombre#n para la copia n &gt; 0 de una entrada de job. */
  static String clave( String nombre, int copia ) {
    return copia > 0 ? nombre + "#" + copia : nombre;
  }

  private static void parametros( Element ps, Documento doc ) {
    for ( Element p : Xmls.hijos( ps, "parameter" ) ) {
      doc.parametros.put( Xmls.texto( p, "name" ), new String[] { Xmls.texto( p, "default_value" ),
        Xmls.texto( p, "description" ) } );
    }
  }

  private static void conexion( Element c, Documento doc ) {
    String nombre = Xmls.texto( c, "name" );
    doc.conexiones.put( nombre, Aplanar.aplanar( c, new HashSet<String>( Arrays.asList( "name" ) ) ) );
  }

  private static void notas( Element np, Documento doc ) {
    for ( Element n : Xmls.hijos( np, "notepad" ) ) {
      Nota x = new Nota();
      x.texto = Xmls.texto( n, "note" );
      x.x = Xmls.entero( Xmls.texto( n, "xloc" ), 0 );
      x.y = Xmls.entero( Xmls.texto( n, "yloc" ), 0 );
      LinkedHashMap<String, String> resto = Aplanar.aplanar( n, new HashSet<String>( Arrays.asList( "note", "xloc",
        "yloc" ) ) );
      x.formato = resto.toString();
      doc.notas.add( x );
    }
  }

  /** Elementos raíz de una transformación que no se leyeron aparte: van a las propiedades del documento. */
  private static void otros( Element raiz, Set<String> conocidos, Documento doc ) {
    for ( Element e : Xmls.hijos( raiz ) ) {
      if ( conocidos.contains( e.getTagName() ) ) {
        continue;
      }
      if ( Xmls.tieneHijos( e ) ) {
        for ( java.util.Map.Entry<String, String> x : Aplanar.aplanar( e, null ).entrySet() ) {
          doc.propiedades.put( e.getTagName() + "/" + x.getKey(), x.getValue() );
        }
      } else {
        doc.propiedades.put( e.getTagName(), Xmls.texto( e ) );
      }
    }
  }

  /** Nombre visible de un nodo de job (sin el #n de la clave). */
  public static String visible( String clave ) {
    return clave;
  }
}
