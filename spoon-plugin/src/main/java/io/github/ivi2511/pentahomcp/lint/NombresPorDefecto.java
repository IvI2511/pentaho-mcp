package io.github.ivi2511.pentahomcp.lint;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;

/**
 * Nombres por defecto de steps y entradas (default_step_name / default_entry_name). PluginRegistry solo tiene
 * los del idioma con que arrancó Spoon: se suman los de en/es/pt de lint/nombres_por_defecto.txt (lo genera
 * tools/lint-corpus/nombres.ps1, un arranque de Kettle por idioma) y los del registro (plugins de terceros).
 */
public final class NombresPorDefecto {

  private static final String RECURSO = "io/github/ivi2511/pentahomcp/lint/nombres_por_defecto.txt";
  private static final Pattern NUMERO_FINAL = Pattern.compile( "\\s+\\d+$" );
  private static Set<String> steps;
  private static Set<String> entradas;
  private static int deArchivo;

  private NombresPorDefecto() {
  }

  /** «Text file output 2» → «text file output». */
  public static String base( String nombre ) {
    if ( nombre == null ) {
      return "";
    }
    return NUMERO_FINAL.matcher( nombre.trim().toLowerCase( Locale.ROOT ) ).replaceAll( "" ).trim();
  }

  public static boolean esDeStep( String nombre ) {
    cargar();
    return steps.contains( base( nombre ) );
  }

  public static boolean esDeEntrada( String nombre ) {
    cargar();
    return entradas.contains( base( nombre ) );
  }

  /** Cuántos nombres vinieron del archivo generado (el resto, del registro). */
  public static int cuantosDelArchivo() {
    cargar();
    return deArchivo;
  }

  private static synchronized void cargar() {
    if ( steps != null ) {
      return;
    }
    Set<String> s = new HashSet<String>();
    Set<String> e = new HashSet<String>();
    int n = 0;
    ClassLoader cl = NombresPorDefecto.class.getClassLoader();
    try ( InputStream in = cl == null ? null : cl.getResourceAsStream( RECURSO ) ) {
      if ( in != null ) {
        BufferedReader r = new BufferedReader( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
        for ( String l; ( l = r.readLine() ) != null; ) {
          if ( l.startsWith( "S\t" ) ) {
            s.add( base( l.substring( 2 ) ) );
            n++;
          } else if ( l.startsWith( "E\t" ) ) {
            e.add( base( l.substring( 2 ) ) );
            n++;
          }
        }
      }
    } catch ( Exception ignored ) {
      // sin archivo: solo los del registro
    }
    try {
      for ( PluginInterface p : PluginRegistry.getInstance().getPlugins( StepPluginType.class ) ) {
        s.add( base( p.getName() ) );
      }
      for ( PluginInterface p : PluginRegistry.getInstance().getPlugins( JobEntryPluginType.class ) ) {
        e.add( base( p.getName() ) );
      }
    } catch ( Exception ignored ) {
      // sin registro (no debería pasar dentro de Spoon)
    }
    s.remove( "" );
    e.remove( "" );
    deArchivo = n;
    steps = s;
    entradas = e;
  }
}
