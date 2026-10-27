package io.github.ivi2511.pentahomcp.traductor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.trans.step.StepMeta;

/**
 * Verificación de una traducción (0.8.0): compara lo que produce la transformación en PDI con lo que produce el
 * Python generado, sin escribir en los archivos ni en las bases de la persona.
 *
 * <p>Python: corre una variante del script ({@link #variante}) en la que los bloques que escriben
 * ({@link Bloque#escribe}) no escriben: dejan lo que iban a escribir ({@link Bloque#escribeDf}) en un CSV de una carpeta temporal; los que leen
 * corren tal cual (sólo leen). Los steps finales que no escriben también se vuelcan. PDI: el {@link Motor} (las
 * pruebas doradas de la 0.7.0, en el paquete del plugin) corre la transformación con los steps que escriben saltados,
 * captura lo que habrían recibido y compara cada salida con su CSV de Python.
 */
public final class Verificacion {

  public static final String IGUAL = "equal";
  public static final String DISTINTO = "different";
  public static final String SIN_VERIFICAR = "not_verified";

  /** Variable de entorno (o propiedad del sistema pentaho.live.python) con el Python a usar. */
  public static final String VARIABLE_PYTHON = "PENTAHO_LIVE_PYTHON";

  private static volatile String pythonCache;
  private static volatile boolean buscado;

  private Verificacion() {
  }

  /** El resultado de una salida (un step que escribe o un step final). */
  public static final class Salida {
    public String paso;
    public String estado = SIN_VERIFICAR;
    public String motivo;
    public int filasPdi = -1;
    public int filasPython = -1;
    /** Las primeras diferencias, legibles (fila, columna, PDI vs Python). */
    public final List<String> diferencias = new ArrayList<>();
  }

  /** El resultado de toda la verificación. */
  public static final class Resultado {
    /** equal (todas iguales), different (alguna distinta) o not_verified (ninguna se pudo comparar). */
    public String estado = SIN_VERIFICAR;
    /** Por qué no se verificó (sin Python, sin pandas...), si estado es not_verified. */
    public String motivo;
    public final List<Salida> salidas = new ArrayList<>();
    /** Python usado. */
    public String python;
    /** Cola de la salida de Python (si falló). */
    public String log;

    /** Calcula {@link #estado} a partir de las salidas. */
    public void cerrar() {
      boolean alguna = false;
      boolean distinta = false;
      for ( Salida s : salidas ) {
        alguna |= !SIN_VERIFICAR.equals( s.estado );
        distinta |= DISTINTO.equals( s.estado );
      }
      estado = distinta ? DISTINTO : alguna ? IGUAL : SIN_VERIFICAR;
      if ( SIN_VERIFICAR.equals( estado ) && motivo == null && !salidas.isEmpty() ) {
        motivo = salidas.get( 0 ).motivo;
      }
    }
  }

  /**
   * El lado de PDI: corre la transformación sin escribir (los steps de {@code saltar} y los que escriben pasan a ser
   * Dummy), captura las filas de cada salida y las compara con el CSV que dejó Python (sin CSV: no verificada).
   */
  public interface Motor {
    void pdiYComparar( File ktr, Set<String> saltar, Map<String, File> csvPython, Resultado r, String idioma )
      throws Exception;
  }

  /** Una salida a comparar: el step y la variable de Python con sus filas. */
  public static final class Volcado {
    public final String paso;
    public final String variable;
    public final String archivo;

    Volcado( String paso, String variable, String archivo ) {
      this.paso = paso;
      this.variable = variable;
      this.archivo = archivo;
    }
  }

  // ================================================================ Python

  /** El Python: PENTAHO_LIVE_PYTHON (variable o propiedad), si no python / python3 / py del PATH; null si no hay. */
  public static String python() {
    if ( buscado ) {
      return pythonCache;
    }
    synchronized ( Verificacion.class ) {
      if ( !buscado ) {
        String env = System.getProperty( "pentaho.live.python" );
        if ( env == null || env.trim().isEmpty() ) {
          env = System.getenv( VARIABLE_PYTHON );
        }
        List<String> cand = env != null && !env.trim().isEmpty() ? Arrays.asList( env.trim() ) : Arrays.asList(
          "python", "python3", "py" );
        for ( String c : cand ) {
          try {
            if ( correr( null, 30, null, c, "--version" ).codigo == 0 ) {
              pythonCache = c;
              break;
            }
          } catch ( Exception e ) {
            // el siguiente
          }
        }
        buscado = true;
      }
    }
    return pythonCache;
  }

  /** Vuelve a buscar Python la próxima vez (cambió el ajuste). */
  public static void olvidarPython() {
    buscado = false;
    pythonCache = null;
  }

  /** ¿Este Python importa el módulo? */
  public static boolean tiene( String python, String modulo ) {
    try {
      return correr( null, 120, null, python, "-c", "import " + modulo ).codigo == 0;
    } catch ( Exception e ) {
      return false;
    }
  }

  /** null si se puede verificar con este destino; si no, por qué (sin Python, sin pandas, sin pyspark). */
  public static String falta( Destino d, String idioma ) {
    String py = python();
    if ( py == null ) {
      return t( idioma, "No hay Python: instálalo o indica cuál usar con la variable de entorno " + VARIABLE_PYTHON
        + ".", "No Python found: install it or set the environment variable " + VARIABLE_PYTHON + ".",
        "Não há Python: instale-o ou indique qual usar com a variável de ambiente " + VARIABLE_PYTHON + "." );
    }
    if ( !tiene( py, "pandas" ) ) {
      return t( idioma, "pandas no está instalado en " + py + " (pip install pandas).", "pandas is not installed in "
        + py + " (pip install pandas).", "O pandas não está instalado em " + py + " (pip install pandas)." );
    }
    if ( d == Destino.PYSPARK && !tiene( py, "pyspark" ) ) {
      return t( idioma, "PySpark no está instalado en " + py + " (pip install pyspark).", "PySpark is not installed in "
        + py + " (pip install pyspark).", "O PySpark não está instalado em " + py + " (pip install pyspark)." );
    }
    return null;
  }

  /** Las salidas que se comparan: lo que escriben los bloques que escriben y los steps finales. */
  public static List<Volcado> volcados( Programa p ) {
    List<Volcado> r = new ArrayList<>();
    Set<String> pasos = new LinkedHashSet<>();
    int n = 0;
    for ( Bloque k : p.bloques ) {
      String v = null;
      if ( k.escribe ) {
        v = k.escribeDf;
      } else if ( EscritorPy.esFinal( p, k ) ) {
        StepMeta s = p.ctx.trans.findStep( k.paso );
        v = k.vista != null ? k.vista : s == null ? null : p.ctx.df( s );
      }
      if ( v != null && pasos.add( k.paso ) ) {
        r.add( new Volcado( k.paso, v, "salida_" + ( ++n ) + ".csv" ) );
      }
    }
    return r;
  }

  /** Los steps que la verificación no deja escribir (los bloques que escriben). */
  public static Set<String> saltar( Programa p ) {
    Set<String> r = new LinkedHashSet<>();
    for ( Bloque k : p.bloques ) {
      if ( k.escribe ) {
        r.add( k.paso );
      }
    }
    return r;
  }

  /**
   * El script de la verificación: el preámbulo (AQUI = la carpeta del .ktr), los bloques (los que escriben sólo pasan
   * su DataFrame) y, detrás de cada salida, su volcado a {@code carpeta}.
   */
  public static String variante( Programa p, File carpeta, String aqui ) {
    List<Volcado> vs = volcados( p );
    Map<String, Volcado> porPaso = new LinkedHashMap<>();
    for ( Volcado v : vs ) {
      porPaso.put( v.paso, v );
    }
    StringBuilder b = new StringBuilder();
    b.append( "# Pentaho Live: verification variant (nothing is written outside " ).append( "_SALIDA)\n" );
    for ( String l : EscritorPy.preambulo( p ) ) {
      if ( l.startsWith( "AQUI = " ) ) {
        l = "AQUI = " + CtxTrad.lit( aqui );
      }
      b.append( l ).append( '\n' );
    }
    b.append( '\n' );
    b.append( "_SALIDA = " ).append( CtxTrad.lit( carpeta.getAbsolutePath() ) ).append( '\n' );
    b.append( VOLCAR ).append( '\n' );
    for ( Bloque k : p.bloques ) {
      b.append( "# --- " ).append( k.paso ).append( '\n' );
      b.append( "print(" ).append( CtxTrad.lit( "@@paso " + k.paso ) ).append( ", flush=True)\n" );
      if ( k.escribe ) {
        StepMeta s = p.ctx.trans == null ? null : p.ctx.trans.findStep( k.paso );
        String df = s == null ? null : p.ctx.df( s );
        if ( df != null && k.escribeDf != null && !df.equals( k.escribeDf ) ) {
          b.append( df ).append( " = " ).append( k.escribeDf ).append( '\n' );
        } else {
          b.append( "pass\n" );
        }
      } else {
        for ( String l : k.codigo ) {
          b.append( l ).append( '\n' );
        }
      }
      Volcado v = porPaso.get( k.paso );
      if ( v != null ) {
        b.append( "_volcar(" ).append( v.variable ).append( ", " ).append( CtxTrad.lit( v.archivo ) ).append( ")\n" );
      }
    }
    b.append( "print(\"@@fin\", flush=True)\n" );
    return b.toString();
  }

  /** Vuelca un DataFrame (pandas o PySpark) como lo hace PDI: fechas ISO, booleanos Y/N, nulos vacíos. */
  static final String VOLCAR = String.join( "\n",
    "def _volcar(df, archivo):",
    "    import pandas as _pd",
    "    if not isinstance(df, _pd.DataFrame):",
    "        df = df.toPandas()",
    "    df = df.copy()",
    "    for c in df.columns:",
    "        s = df[c]",
    "        if _pd.api.types.is_datetime64_any_dtype(s):",
    "            v = s.dropna()",
    "            dia = bool((v == v.dt.normalize()).all())",
    "            df[c] = s.dt.strftime(\"%Y-%m-%d\" if dia else \"%Y-%m-%d %H:%M:%S\")",
    "        elif _pd.api.types.is_bool_dtype(s):",
    "            df[c] = s.map({True: \"Y\", False: \"N\"})",
    "    df.to_csv(os.path.join(_SALIDA, archivo), index=False, encoding=\"utf-8\")" );

  /**
   * Verifica: corre la variante en Python (en una carpeta temporal que se borra) y le pide al motor que corra PDI y
   * compare. Nunca lanza: lo que falla queda como not_verified con su motivo.
   *
   * @param ktr      la .ktr que corre PDI (la copia del documento abierto o el archivo)
   * @param carpeta  la carpeta de la transformación (AQUI: de ahí se leen los archivos relativos)
   */
  public static Resultado verificar( Programa p, File ktr, File carpeta, Motor motor, String idioma ) {
    Resultado r = new Resultado();
    List<Volcado> vs = volcados( p );
    for ( Volcado v : vs ) {
      Salida s = new Salida();
      s.paso = v.paso;
      r.salidas.add( s );
    }
    if ( vs.isEmpty() ) {
      r.motivo = t( idioma, "La transformación no tiene salidas que comparar.", "The transformation has no outputs "
        + "to compare.", "A transformação não tem saídas para comparar." );
      return r;
    }
    String falta = falta( p.destino(), idioma );
    if ( falta != null ) {
      todas( r, falta );
      return r;
    }
    r.python = python();
    File tmp = null;
    try {
      tmp = Files.createTempDirectory( "pentaho-live-verif-" ).toFile();
      File script = new File( tmp, "verificar.py" );
      Files.write( script.toPath(), variante( p, tmp, carpeta.getAbsolutePath() ).getBytes( StandardCharsets.UTF_8 ) );
      Map<String, String> env = new LinkedHashMap<>();
      for ( DatabaseMeta db : new ArrayList<>( p.ctx.conexiones.values() ) ) {
        String clave = db.getPassword();
        if ( clave != null && !clave.isEmpty() && System.getenv( Conexiones.variableClave( db ) ) == null ) {
          env.put( Conexiones.variableClave( db ), p.ctx.trans.environmentSubstitute( clave ) ); // sólo en memoria
        }
      }
      if ( p.destino() == Destino.PYSPARK && System.getenv( "JAVA_HOME" ) == null && javaModerno() ) {
        env.put( "JAVA_HOME", System.getProperty( "java.home" ) );
      }
      Py py = correr( carpeta, p.destino() == Destino.PYSPARK ? 900 : 300, env, r.python, "-X", "utf8",
        script.getAbsolutePath() );
      String ultimo = null;
      for ( String l : py.salida.split( "\\r?\\n" ) ) {
        if ( l.startsWith( "@@paso " ) ) {
          ultimo = l.substring( 7 );
        }
      }
      Map<String, File> csv = new LinkedHashMap<>();
      for ( Volcado v : vs ) {
        File f = new File( tmp, v.archivo );
        if ( f.isFile() ) {
          csv.put( v.paso, f );
        }
      }
      if ( py.codigo != 0 ) {
        r.log = cola( py.salida, 15 );
        String mot = motivoPython( py.salida, ultimo, p.destino(), idioma );
        for ( Salida s : r.salidas ) {
          if ( !csv.containsKey( s.paso ) ) {
            s.motivo = mot;
          }
        }
        if ( csv.isEmpty() ) {
          r.motivo = mot;
          return r;
        }
      }
      motor.pdiYComparar( ktr, saltar( p ), csv, r, idioma );
    } catch ( Exception e ) {
      String m = e.getMessage() == null ? e.toString() : e.getMessage();
      for ( Salida s : r.salidas ) {
        if ( SIN_VERIFICAR.equals( s.estado ) && s.motivo == null ) {
          s.motivo = m;
        }
      }
      r.motivo = m;
    } finally {
      borrar( tmp );
      r.cerrar();
    }
    return r;
  }

  private static void todas( Resultado r, String motivo ) {
    r.motivo = motivo;
    for ( Salida s : r.salidas ) {
      s.motivo = motivo;
    }
  }

  /** Por qué falló Python, en una línea: la excepción final y en qué step. */
  static String motivoPython( String salida, String paso, Destino d, String idioma ) {
    String err = "";
    String[] ls = salida.split( "\\r?\\n" );
    for ( int i = ls.length - 1; i >= 0; i-- ) {
      String l = ls[ i ].trim();
      if ( !l.isEmpty() && !l.startsWith( "@@" ) && l.matches( "^[\\w.]+(Error|Exception|Exit|Interrupt)\\b.*|^[\\w.]+:"
        + " .*" ) ) {
        err = l;
        break;
      }
    }
    if ( err.isEmpty() && ls.length > 0 ) {
      err = ls[ ls.length - 1 ].trim();
    }
    String bajo = salida.toLowerCase( java.util.Locale.ROOT );
    String pista = "";
    if ( d == Destino.PYSPARK && ( bajo.contains( "java_home" ) || bajo.contains( "java gateway process exited" )
      || bajo.contains( "unsupportedclassversion" ) ) ) {
      pista = t( idioma, " PySpark necesita Java 17 o posterior (JAVA_HOME).", " PySpark needs Java 17 or later "
        + "(JAVA_HOME).", " O PySpark precisa do Java 17 ou posterior (JAVA_HOME)." );
    } else if ( bajo.contains( "password" ) || bajo.contains( "authentication" ) || bajo.contains( "access denied" ) ) {
      pista = t( idioma, " ¿Falta la contraseña de la base? (variable PDI_PASSWORD_<CONEXIÓN>)", " Missing the "
        + "database password? (variable PDI_PASSWORD_<CONNECTION>)", " Falta a senha da base? (variável "
          + "PDI_PASSWORD_<CONEXÃO>)" );
    } else if ( bajo.contains( "modulenotfounderror" ) ) {
      pista = t( idioma, " Instala el módulo que falta con pip.", " Install the missing module with pip.",
        " Instale o módulo que falta com pip." );
    }
    return t( idioma, "Python falló", "Python failed", "O Python falhou" ) + ( paso == null ? "" : t( idioma,
      " en «" + paso + "»", " at '" + paso + "'", " em «" + paso + "»" ) ) + ": " + err + pista;
  }

  static boolean javaModerno() {
    String v = System.getProperty( "java.specification.version", "1.8" );
    try {
      return !v.startsWith( "1." ) && Integer.parseInt( v.split( "\\." )[ 0 ] ) >= 17;
    } catch ( NumberFormatException e ) {
      return false;
    }
  }

  static String cola( String s, int n ) {
    List<String> l = new ArrayList<>();
    for ( String x : s.split( "\\r?\\n" ) ) {
      if ( !x.startsWith( "@@" ) ) {
        l.add( x );
      }
    }
    return String.join( "\n", l.subList( Math.max( 0, l.size() - n ), l.size() ) );
  }

  // ================================================================ procesos

  /** Lo que devolvió un proceso. */
  public static final class Py {
    public int codigo;
    public String salida;
  }

  /** Corre un comando (stdout + stderr juntos, UTF-8) con plazo; env se suma al del proceso. */
  public static Py correr( File dir, int segundos, Map<String, String> env, String... cmd ) throws Exception {
    ProcessBuilder pb = new ProcessBuilder( Arrays.asList( cmd ) ).redirectErrorStream( true );
    if ( dir != null && dir.isDirectory() ) {
      pb.directory( dir );
    }
    pb.environment().put( "PYTHONIOENCODING", "utf-8" );
    pb.environment().put( "PYTHONUTF8", "1" );
    pb.environment().put( "MPLBACKEND", "Agg" );
    if ( env != null ) {
      pb.environment().putAll( env );
    }
    File log = File.createTempFile( "pentaho-live-py", ".log" );
    try {
      pb.redirectOutput( log );
      Process p = pb.start();
      if ( !p.waitFor( segundos, TimeUnit.SECONDS ) ) {
        p.destroyForcibly();
        throw new IOException( "Python did not finish in " + segundos + " s" );
      }
      Py r = new Py();
      r.codigo = p.exitValue();
      r.salida = new String( Files.readAllBytes( log.toPath() ), StandardCharsets.UTF_8 );
      return r;
    } finally {
      log.delete();
    }
  }

  static void borrar( File f ) {
    if ( f == null || !f.exists() ) {
      return;
    }
    File[] hijos = f.listFiles();
    if ( hijos != null ) {
      for ( File h : hijos ) {
        borrar( h );
      }
    }
    f.delete();
  }

  static String t( String idioma, String es, String en, String pt ) {
    return EscritorPy.t( idioma, es, en, pt );
  }
}
