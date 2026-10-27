package io.github.ivi2511.pentahomcp.traductor;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.errorhandling.StreamInterface;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * El contexto de la traducción de UNA transformación: destino (pandas o PySpark), idioma de los comentarios, nombres
 * de los DataFrames, quién alimenta a quién, imports, funciones auxiliares, variables y conexiones. Lo comparten todos
 * los {@link TraductorStep}.
 *
 * <p>Convenciones del código generado:
 * <ul>
 * <li>Cada step deja su salida principal en {@code df_<step>} ({@link #df(StepMeta)}). Un step que reparte filas a
 * destinos distintos (Filter rows true/false, Switch / Case, el manejo de errores) registra cada salida con
 * {@link #ponerSalida} y {@link #ponerSalidaError}.</li>
 * <li>Las variables de Kettle viven en el dict {@code VARS} (parámetros de la transformación con su valor por defecto,
 * más {@code Internal.*}); {@link #texto(String)} convierte {@code "${DIR}/x.csv"} en {@code f"{VARS['DIR']}/x.csv"}.</li>
 * <li>Cada conexión es un engine de SQLAlchemy ({@code engine_<conexión>}) en pandas, o un dict de opciones JDBC
 * ({@code jdbc_<conexión>}) en PySpark; la contraseña sale de una variable de entorno, nunca del .ktr.</li>
 * <li>PySpark: hay una {@code spark} (SparkSession) creada en la cabecera, y {@code F} es pyspark.sql.functions.</li>
 * </ul>
 */
public final class CtxTrad {

  public final TransMeta trans;
  public final Destino destino;
  /** "es", "en" o "pt": idioma de comentarios, explicaciones y notas. */
  public final String idioma;

  /** Líneas de import (sin repetir, en orden de aparición). */
  public final Set<String> imports = new LinkedHashSet<>();
  /** Funciones auxiliares: nombre → código (def completo). Van una sola vez, antes del primer bloque. */
  public final Map<String, String> ayudantes = new LinkedHashMap<>();
  /** VARS: nombre → expresión Python del valor por defecto (un literal). */
  public final Map<String, String> vars = new LinkedHashMap<>();
  /** Carpeta del .ktr ("" si no está guardado); en el código generado es AQUI. */
  public String carpeta = "";
  /** Conexiones usadas: nombre de la conexión → su DatabaseMeta. */
  public final Map<String, DatabaseMeta> conexiones = new LinkedHashMap<>();

  private final Map<String, String> nombres = new HashMap<>();
  private final Set<String> usados = new HashSet<>();
  /** (origen, destino) → variable que el origen le da a ese destino. */
  private final Map<String, String> salidas = new HashMap<>();
  private final Map<String, String> salidasError = new HashMap<>();

  public CtxTrad( TransMeta trans, Destino destino, String idioma ) {
    this.trans = trans;
    this.destino = destino;
    this.idioma = idioma == null ? "es" : idioma;
    usados.add( "spark" );
    usados.add( "pd" );
    usados.add( "np" );
    usados.add( "F" );
    usados.add( "VARS" );
    usados.add( "AQUI" );
    if ( destino == Destino.PYSPARK ) {
      importar( "from pyspark.sql import SparkSession, functions as F, types as T, Window" );
    } else {
      importar( "import pandas as pd" );
      importar( "import numpy as np" );
    }
    importar( "import os" );
    variablesInternas();
  }

  public boolean pandas() {
    return destino == Destino.PANDAS;
  }

  public boolean spark() {
    return destino == Destino.PYSPARK;
  }

  /** Texto en el idioma del contexto. */
  public String t( String es, String en, String pt ) {
    return "en".equals( idioma ) ? en : "pt".equals( idioma ) ? pt : es;
  }

  public void importar( String linea ) {
    imports.add( linea );
  }

  /** Registra una función auxiliar (una sola vez por nombre). */
  public void ayudante( String nombre, String codigo ) {
    ayudantes.putIfAbsent( nombre, codigo );
  }

  // ================================================================ nombres

  /** Identificador Python seguro a partir de un texto: minúsculas, sin acentos, con _ en vez de símbolos. */
  public static String ident( String s ) {
    String n = Normalizer.normalize( s == null ? "" : s, Normalizer.Form.NFD ).replaceAll( "\\p{M}", "" );
    n = n.toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9]+", "_" ).replaceAll( "^_+|_+$", "" );
    if ( n.isEmpty() ) {
      n = "x";
    }
    if ( Character.isDigit( n.charAt( 0 ) ) ) {
      n = "_" + n;
    }
    return n;
  }

  /** Variable del DataFrame de salida principal del step: df_&lt;step&gt;, único en el programa. */
  public String df( StepMeta s ) {
    return nombre( s.getName() );
  }

  private String nombre( String paso ) {
    String n = nombres.get( paso );
    if ( n == null ) {
      String base = "df_" + ident( paso );
      n = base;
      for ( int i = 2; usados.contains( n ); i++ ) {
        n = base + "_" + i;
      }
      usados.add( n );
      nombres.put( paso, n );
    }
    return n;
  }

  /** Un nombre nuevo, único, para una variable auxiliar (con el prefijo dado). */
  public String nuevo( String prefijo ) {
    String base = ident( prefijo );
    String n = base;
    for ( int i = 2; usados.contains( n ); i++ ) {
      n = base + "_" + i;
    }
    usados.add( n );
    return n;
  }

  // ================================================================ flujo

  /** Registra la variable que el step {@code origen} le da a {@code destino} (Filter rows, Switch / Case...). */
  public void ponerSalida( StepMeta origen, StepMeta destino, String variable ) {
    salidas.put( origen.getName() + "\u0000" + destino.getName(), variable );
  }

  /** Registra la variable con las filas que el step manda por su hop de error. */
  public void ponerSalidaError( StepMeta origen, String variable ) {
    salidasError.put( origen.getName(), variable );
  }

  public String salidaError( StepMeta origen ) {
    return salidasError.get( origen.getName() );
  }

  /** Variable de las filas de error del step (df_&lt;step&gt;_error), la registre o no el traductor. */
  public String dfError( StepMeta origen ) {
    String v = salidaError( origen );
    return v != null ? v : nombreError( origen );
  }

  private final Map<String, String> nombresError = new HashMap<>();

  private String nombreError( StepMeta origen ) {
    return nombresError.computeIfAbsent( origen.getName(), k -> nuevo( df( origen ) + "_error" ) );
  }

  /** ¿El hop de error de {@code origen} va a {@code destino}? */
  public static boolean esHopDeError( StepMeta origen, StepMeta destino ) {
    return origen.getStepErrorMeta() != null && origen.getStepErrorMeta().isEnabled()
      && destino.equals( origen.getStepErrorMeta().getTargetStep() );
  }

  /** ¿El step tiene manejo de errores activo con destino? */
  public static boolean tieneError( StepMeta s ) {
    return s.getStepErrorMeta() != null && s.getStepErrorMeta().isEnabled()
      && s.getStepErrorMeta().getTargetStep() != null;
  }

  /** Lo que el origen le da al destino: su salida registrada, la de error o su df principal. */
  public String salidaHacia( StepMeta origen, StepMeta destino ) {
    if ( esHopDeError( origen, destino ) ) {
      return dfError( origen );
    }
    String v = salidas.get( origen.getName() + "\u0000" + destino.getName() );
    return v != null ? v : df( origen );
  }

  /** Steps anteriores con hop habilitado (en el orden del lienzo de los hops). */
  public List<StepMeta> anteriores( StepMeta s ) {
    List<StepMeta> r = new ArrayList<>();
    for ( int i = 0; i < trans.nrTransHops(); i++ ) {
      TransHopMeta h = trans.getTransHop( i );
      if ( h.isEnabled() && s.equals( h.getToStep() ) && h.getFromStep() != null && !r.contains( h.getFromStep() ) ) {
        r.add( h.getFromStep() );
      }
    }
    return r;
  }

  /** Steps siguientes con hop habilitado. */
  public List<StepMeta> siguientes( StepMeta s ) {
    List<StepMeta> r = new ArrayList<>();
    for ( int i = 0; i < trans.nrTransHops(); i++ ) {
      TransHopMeta h = trans.getTransHop( i );
      if ( h.isEnabled() && s.equals( h.getFromStep() ) && h.getToStep() != null && !r.contains( h.getToStep() ) ) {
        r.add( h.getToStep() );
      }
    }
    return r;
  }

  /** Siguientes por el flujo principal (sin el destino del hop de error). */
  public List<StepMeta> siguientesPrincipales( StepMeta s ) {
    List<StepMeta> r = new ArrayList<>();
    for ( StepMeta d : siguientes( s ) ) {
      if ( !esHopDeError( s, d ) ) {
        r.add( d );
      }
    }
    return r;
  }

  /** Nombres de los steps que este step lee como info (lookup, merge join, stream lookup...). */
  public List<String> nombresInfo( StepMeta s ) {
    List<String> r = new ArrayList<>();
    try {
      for ( StreamInterface st : s.getStepMetaInterface().getStepIOMeta().getInfoStreams() ) {
        String n = st.getStepMeta() != null ? st.getStepMeta().getName() : st.getStepname();
        if ( n != null && !n.isEmpty() ) {
          r.add( n );
        }
      }
    } catch ( RuntimeException e ) {
      // sin IO meta: sin info
    }
    return r;
  }

  /** Variables de las entradas principales (todas las anteriores salvo las de info), en orden. */
  public List<String> entradas( StepMeta s ) {
    List<String> info = nombresInfo( s );
    List<String> r = new ArrayList<>();
    for ( StepMeta p : anteriores( s ) ) {
      if ( !info.contains( p.getName() ) ) {
        r.add( salidaHacia( p, s ) );
      }
    }
    return r;
  }

  /** La única (o la primera) entrada principal; null si no tiene. */
  public String entrada( StepMeta s ) {
    List<String> e = entradas( s );
    return e.isEmpty() ? null : e.get( 0 );
  }

  /**
   * Una sola variable con todas las entradas principales: la única tal cual, o varias unidas (pd.concat /
   * unionByName), como PDI mezcla las filas de varios hops que llegan al mismo step. null si no tiene entradas.
   */
  public String entradaUnida( StepMeta s, Bloque b ) {
    List<String> e = entradas( s );
    if ( e.isEmpty() ) {
      return null;
    }
    if ( e.size() == 1 ) {
      return e.get( 0 );
    }
    String v = nuevo( df( s ) + "_in" );
    if ( pandas() ) {
      b.l( v + " = pd.concat([" + String.join( ", ", e ) + "], ignore_index=True)" );
    } else {
      StringBuilder sb = new StringBuilder( e.get( 0 ) );
      for ( int i = 1; i < e.size(); i++ ) {
        sb.append( ".unionByName(" ).append( e.get( i ) ).append( ", allowMissingColumns=True)" );
      }
      b.l( v + " = " + sb );
    }
    return v;
  }

  /** La variable del step de info con ese nombre (lo que ese step le da a éste). */
  public String info( StepMeta s, String nombreInfo ) {
    StepMeta i = trans.findStep( nombreInfo );
    return i == null ? null : salidaHacia( i, s );
  }

  // ================================================================ campos

  /** Campos que llegan al step (vacío si Kettle no puede calcularlos). */
  public RowMetaInterface camposEntrada( StepMeta s ) {
    try {
      RowMetaInterface r = trans.getPrevStepFields( s );
      return r == null ? new RowMeta() : r;
    } catch ( Exception | StackOverflowError e ) { // un ciclo de saltos: Kettle no termina
      return new RowMeta();
    }
  }

  /** Campos que salen del step. */
  public RowMetaInterface campos( StepMeta s ) {
    try {
      RowMetaInterface r = trans.getStepFields( s );
      return r == null ? new RowMeta() : r;
    } catch ( Exception | StackOverflowError e ) { // un ciclo de saltos: Kettle no termina
      return new RowMeta();
    }
  }

  /** El XML del step (el elemento &lt;step&gt;), para leer la configuración sin depender de la clase Meta. */
  public Element xml( StepMeta s ) throws Exception {
    Document d = XMLHandler.loadXMLString( s.getXML() );
    return d.getDocumentElement();
  }

  /** Texto de un hijo directo del XML (o "" si no está). */
  public static String hijo( Element e, String tag ) {
    if ( e == null ) {
      return "";
    }
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && tag.equals( n.getNodeName() ) ) {
        return n.getTextContent() == null ? "" : n.getTextContent();
      }
    }
    return "";
  }

  // ================================================================ literales y variables

  /** Literal de cadena Python (comillas dobles, con escapes). */
  public static String lit( String s ) {
    if ( s == null ) {
      return "None";
    }
    StringBuilder b = new StringBuilder( "\"" );
    for ( char c : s.toCharArray() ) {
      switch ( c ) {
        case '\\': b.append( "\\\\" ); break;
        case '"': b.append( "\\\"" ); break;
        case '\n': b.append( "\\n" ); break;
        case '\r': b.append( "\\r" ); break;
        case '\t': b.append( "\\t" ); break;
        default:
          if ( c < 0x20 ) {
            b.append( String.format( "\\x%02x", (int) c ) );
          } else {
            b.append( c );
          }
      }
    }
    return b.append( '"' ).toString();
  }

  /** Nombre de columna como literal Python (alias corto de {@link #lit}). */
  public static String col( String campo ) {
    return lit( campo );
  }

  private static final Pattern VAR = Pattern.compile( "\\$\\{([^}]+)\\}|%%([^%]+)%%" );

  /**
   * Un valor de PDI que puede tener variables ({@code ${X}} o {@code %%X%%}) como expresión Python: un literal si
   * no tiene, un f-string sobre VARS si tiene (y la variable queda registrada en VARS).
   */
  public String texto( String valor ) {
    if ( valor == null ) {
      return "None";
    }
    Matcher m = VAR.matcher( valor );
    if ( !m.find() ) {
      return lit( valor );
    }
    m.reset();
    StringBuilder b = new StringBuilder( "f\"" );
    int desde = 0;
    while ( m.find() ) {
      b.append( escaparF( valor.substring( desde, m.start() ) ) );
      String n = m.group( 1 ) != null ? m.group( 1 ) : m.group( 2 );
      usarVariable( n );
      b.append( "{VARS[" ).append( lit( n ).replace( '"', '\'' ) ).append( "]}" );
      desde = m.end();
    }
    b.append( escaparF( valor.substring( desde ) ) );
    return b.append( '"' ).toString();
  }

  private static String escaparF( String s ) {
    String l = lit( s );
    return l.substring( 1, l.length() - 1 ).replace( "{", "{{" ).replace( "}", "}}" );
  }

  /** Registra una variable en VARS (con su valor por defecto si es un parámetro; si no, os.environ o ""). */
  public void usarVariable( String n ) {
    if ( vars.containsKey( n ) ) {
      return;
    }
    String def = null;
    try {
      if ( java.util.Arrays.asList( trans.listParameters() ).contains( n ) ) {
        def = trans.getParameterDefault( n );
      }
    } catch ( Exception e ) {
      // sin parámetros
    }
    vars.put( n, "os.environ.get(" + lit( n ) + ", " + lit( def == null ? "" : def ) + ")" );
  }

  private void variablesInternas() {
    String f = trans.getFilename();
    String dir = "";
    if ( f != null && !f.isEmpty() ) {
      File a = new File( f.replaceFirst( "^file:///?", "" ) );
      dir = a.getAbsoluteFile().getParent() == null ? "" : a.getAbsoluteFile().getParent().replace( '\\', '/' );
    }
    String nombre = trans.getName() == null ? "" : trans.getName();
    // la carpeta del .ktr = la del script o notebook (se guarda al lado): AQUI, nunca la ruta de esta máquina
    for ( String k : new String[] { "Internal.Transformation.Filename.Directory", "Internal.Entry.Current.Directory" } ) {
      vars.put( k, "os.environ.get(" + lit( k ) + ", AQUI)" );
    }
    this.carpeta = dir;
    vars.put( "Internal.Transformation.Name", lit( nombre ) );
  }

  // ================================================================ conexiones

  /** Variable de la conexión (engine_x en pandas, jdbc_x en PySpark) y la registra para la cabecera. */
  public String conexion( DatabaseMeta db ) {
    if ( db == null ) {
      return "None";
    }
    conexiones.putIfAbsent( db.getName(), db );
    return ( pandas() ? "engine_" : "jdbc_" ) + ident( db.getName() );
  }
}
