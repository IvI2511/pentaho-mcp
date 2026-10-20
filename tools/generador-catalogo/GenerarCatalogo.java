import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.PluginTypeInterface;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.i18n.LanguageChoice;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

/**
 * Genera el catalogo de tipos de step y job entry de una instalacion de PDI (Kettle) 9.4.
 *
 * Uso:
 *   java -cp "<generador>/build;<pdi>/lib/*;<pdi>/classes" -DKETTLE_PLUGIN_BASE_FOLDERS=<pdi>/plugins \
 *        GenerarCatalogo <dirSalida> [--locale es_ES --solo-nombres]
 *
 * Modo normal (locale en_US): escribe steps.json, steps/<id>.xml, job-entries.json, job-entries/<id>.xml.
 *   Si existe <dirSalida>/generador/build/nombres_es.json (lo genera el modo --solo-nombres), agrega
 *   nombre_es / categoria_es / descripcion_es a cada entrada.
 * Modo --solo-nombres: solo vuelca id -> nombre/categoria/descripcion en el locale pedido.
 */
public class GenerarCatalogo {

  public static void main( String[] args ) throws Exception {
    if ( args.length < 1 ) {
      System.err.println( "Uso: GenerarCatalogo <dirSalida> [--locale xx_YY] [--solo-nombres]" );
      System.exit( 2 );
    }
    File out = new File( args[0] ).getAbsoluteFile();
    String locale = "en_US";
    boolean soloNombres = false;
    for ( int i = 1; i < args.length; i++ ) {
      if ( "--locale".equals( args[i] ) ) {
        locale = args[++i];
      } else if ( "--solo-nombres".equals( args[i] ) ) {
        soloNombres = true;
      }
    }
    String[] lp = locale.split( "_" );
    Locale loc = lp.length > 1 ? new Locale( lp[0], lp[1] ) : new Locale( lp[0] );
    Locale.setDefault( loc );
    LanguageChoice.getInstance().setDefaultLocale( loc );

    KettleEnvironment.init( false );
    PluginRegistry registry = PluginRegistry.getInstance();

    File nombresEs = new File( out, "generador/build/nombres_es.json" );
    if ( soloNombres ) {
      StringBuilder sb = new StringBuilder( "{\n" );
      volcarNombres( sb, "steps", registry.getPlugins( StepPluginType.class ) );
      sb.append( ",\n" );
      volcarNombres( sb, "jobentries", registry.getPlugins( JobEntryPluginType.class ) );
      sb.append( "\n}\n" );
      nombresEs.getParentFile().mkdirs();
      escribir( nombresEs, sb.toString() );
      System.out.println( "Nombres (" + locale + ") -> " + nombresEs );
      System.exit( 0 );
    }

    Map<String, Map<String, String>> es = leerNombres( nombresEs );

    int[] st = procesar( registry, StepPluginType.class, out, "steps", es.get( "steps" ) );
    int[] je = procesar( registry, JobEntryPluginType.class, out, "job-entries", es.get( "jobentries" ) );
    System.out.println( "Steps: " + st[0] + " (ok " + st[1] + ", error " + ( st[0] - st[1] ) + ")" );
    System.out.println( "Job entries: " + je[0] + " (ok " + je[1] + ", error " + ( je[0] - je[1] ) + ")" );
    System.exit( 0 );
  }

  private static void volcarNombres( StringBuilder sb, String clave, List<PluginInterface> plugins ) {
    sb.append( "  " ).append( q( clave ) ).append( ": {\n" );
    boolean primero = true;
    for ( PluginInterface p : plugins ) {
      if ( !primero ) {
        sb.append( ",\n" );
      }
      primero = false;
      sb.append( "    " ).append( q( p.getIds()[0] ) ).append( ": {\"nombre\": " ).append( q( p.getName() ) )
        .append( ", \"categoria\": " ).append( q( p.getCategory() ) ).append( ", \"descripcion\": " )
        .append( q( p.getDescription() ) ).append( "}" );
    }
    sb.append( "\n  }" );
  }

  /** Lector minimo del JSON que escribe volcarNombres (formato fijo, una entrada por linea). */
  private static Map<String, Map<String, String>> leerNombres( File f ) throws IOException {
    Map<String, Map<String, String>> res = new TreeMap<>();
    res.put( "steps", new TreeMap<String, String>() );
    res.put( "jobentries", new TreeMap<String, String>() );
    if ( !f.exists() ) {
      return res;
    }
    String actual = null;
    Pattern sec = Pattern.compile( "^  \"(\\w+)\": \\{$" );
    Pattern ent = Pattern.compile( "^    (\"(?:[^\"\\\\]|\\\\.)*\"): \\{\"nombre\": (\"(?:[^\"\\\\]|\\\\.)*\"|null), "
      + "\"categoria\": (\"(?:[^\"\\\\]|\\\\.)*\"|null), \"descripcion\": (\"(?:[^\"\\\\]|\\\\.)*\"|null)\\},?$" );
    for ( String linea : Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 ) ) {
      Matcher m = sec.matcher( linea );
      if ( m.matches() ) {
        actual = m.group( 1 );
        continue;
      }
      m = ent.matcher( linea );
      if ( m.matches() && actual != null ) {
        // guardamos los literales JSON ya escapados: id -> fragmento JSON
        res.get( actual ).put( m.group( 1 ), "\"nombre_es\": " + m.group( 2 ) + ", \"categoria_es\": " + m.group( 3 )
          + ", \"descripcion_es\": " + m.group( 4 ) );
      }
    }
    return res;
  }

  private static int[] procesar( PluginRegistry registry, Class<? extends PluginTypeInterface> tipo, File out,
                                 String nombre, Map<String, String> es ) throws IOException {
    boolean esStep = tipo == StepPluginType.class;
    List<PluginInterface> plugins = new ArrayList<>( registry.getPlugins( tipo ) );
    Collections.sort( plugins, new Comparator<PluginInterface>() {
      @Override public int compare( PluginInterface a, PluginInterface b ) {
        int c = nz( a.getCategory() ).compareToIgnoreCase( nz( b.getCategory() ) );
        return c != 0 ? c : a.getIds()[0].compareToIgnoreCase( b.getIds()[0] );
      }
    } );
    File dirXml = new File( out, nombre );
    dirXml.mkdirs();
    StringBuilder json = new StringBuilder( "[\n" );
    int ok = 0;
    for ( int i = 0; i < plugins.size(); i++ ) {
      PluginInterface p = plugins.get( i );
      String id = p.getIds()[0];
      String error = null;
      String xml = null;
      String clase = null;
      try {
        Object o = registry.loadClass( p );
        clase = o.getClass().getName();
        if ( esStep ) {
          StepMetaInterface smi = (StepMetaInterface) o;
          smi.setDefault();
          // Algunos steps (SortRows, BlockingStep, ZipFile, Avro, Kafka...) leen
          // parentStepMeta.getParentTransMeta() dentro de getXML(): hay que colgarlos de una TransMeta.
          TransMeta tm = new TransMeta();
          StepMeta sm = new StepMeta( id, p.getName(), smi );
          smi.setParentStepMeta( sm );
          sm.setParentTransMeta( tm );
          sm.setLocation( 100, 100 );
          sm.setDraw( true );
          tm.addStep( sm );
          xml = sm.getXML();
        } else {
          JobEntryInterface jei = (JobEntryInterface) o;
          jei.setPluginId( id );
          jei.setName( p.getName() );
          JobMeta jm = new JobMeta();
          jei.setParentJobMeta( jm );
          JobEntryCopy jec = new JobEntryCopy( jei );
          jec.setLocation( 100, 100 ); // sin location, JobEntryCopy.getXML() da NPE
          jec.setDrawn( true );
          jm.addJobEntry( jec );
          xml = jec.getXML();
        }
        escribir( new File( dirXml, archivo( id ) + ".xml" ), xml );
        ok++;
      } catch ( Throwable t ) {
        error = t.getClass().getName() + ": " + t.getMessage();
        Throwable c = t.getCause();
        while ( c != null && c != c.getCause() ) {
          error += " | causa: " + c.getClass().getName() + ": " + c.getMessage();
          c = c.getCause();
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace( new PrintWriter( sw ) );
        System.err.println( "[" + nombre + "] ERROR en " + id + ":\n" + sw );
      }
      json.append( "  {" );
      campo( json, "id", id ).append( ", " );
      if ( p.getIds().length > 1 ) {
        json.append( "\"ids_alternativos\": [" );
        for ( int k = 1; k < p.getIds().length; k++ ) {
          json.append( k > 1 ? ", " : "" ).append( q( p.getIds()[k] ) );
        }
        json.append( "], " );
      }
      campo( json, "nombre", p.getName() ).append( ", " );
      campo( json, "categoria", p.getCategory() ).append( ", " );
      campo( json, "descripcion", p.getDescription() ).append( ", " );
      String extraEs = es == null ? null : es.get( q( id ) );
      if ( extraEs != null ) {
        json.append( extraEs ).append( ", " );
      }
      campo( json, "origen", origen( p ) ).append( ", " );
      campo( json, "clase_meta", clase != null ? clase : p.getClassMap().get( p.getMainType() ) ).append( ", " );
      campo( json, "documentacion", p.getDocumentationUrl() ).append( ", " );
      json.append( "\"ok\": " ).append( error == null );
      if ( error != null ) {
        json.append( ", " );
        campo( json, "error", error );
      } else {
        json.append( ", " );
        campo( json, "xml", nombre + "/" + archivo( id ) + ".xml" );
      }
      json.append( "}" ).append( i < plugins.size() - 1 ? ",\n" : "\n" );
    }
    json.append( "]\n" );
    escribir( new File( out, nombre + ".json" ), json.toString() );
    return new int[] { plugins.size(), ok };
  }

  private static String origen( PluginInterface p ) {
    if ( p.isNativePlugin() ) {
      return "nativo (kettle-engine)";
    }
    if ( p.getPluginDirectory() != null ) {
      String d = p.getPluginDirectory().getPath();
      d = d.replace( '\\', '/' );
      int k = d.indexOf( "/plugins/" );
      if ( k >= 0 ) {
        String r = d.substring( k + 1 );
        if ( r.endsWith( "/" ) ) {
          r = r.substring( 0, r.length() - 1 );
        }
        return r;
      }
      return d;
    }
    return "plugin";
  }

  static String archivo( String id ) {
    return id.replaceAll( "[^A-Za-z0-9._-]", "_" );
  }

  static String nz( String s ) {
    return s == null ? "" : s;
  }

  static StringBuilder campo( StringBuilder sb, String k, String v ) {
    return sb.append( q( k ) ).append( ": " ).append( q( v ) );
  }

  static String q( String s ) {
    if ( s == null ) {
      return "null";
    }
    StringBuilder sb = new StringBuilder( "\"" );
    for ( char c : s.toCharArray() ) {
      switch ( c ) {
        case '"': sb.append( "\\\"" ); break;
        case '\\': sb.append( "\\\\" ); break;
        case '\n': sb.append( "\\n" ); break;
        case '\r': sb.append( "\\r" ); break;
        case '\t': sb.append( "\\t" ); break;
        default:
          if ( c < 0x20 ) {
            sb.append( String.format( "\\u%04x", (int) c ) );
          } else {
            sb.append( c );
          }
      }
    }
    return sb.append( '"' ).toString();
  }

  static void escribir( File f, String s ) throws IOException {
    f.getParentFile().mkdirs();
    try ( Writer w = new OutputStreamWriter( new FileOutputStream( f ), StandardCharsets.UTF_8 ) ) {
      w.write( s );
    }
  }
}
