package io.github.ivi2511.pentahomcp.traductor;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.traductor.ProgramaJob.Funcion;

/**
 * Traduce un job a un script Python: una función por entrada (las transformaciones con su {@link Programa} dentro,
 * escrito con {@link EscritorPy#cuerpo}; los sub-jobs con su propio flujo) y un main() que sigue los hops de éxito,
 * fallo e incondicionales.
 *
 * <p>El flujo se escribe legible cuando el grafo no tiene bucles (una cadena con su camino de fallo queda como código
 * recto con {@code if not entrada(): ...}); con bucles, una pequeña máquina de estados que recorre los hops en el mismo
 * orden que PDI (en profundidad, los hops de cada entrada en orden). En los dos casos el resultado del job es el de la
 * última entrada ejecutada, como en Kitchen.
 */
public final class TraductorJob {

  private final Opciones op;
  private final CtxTrad c;
  private final ProgramaJob prog;
  /** Jobs en la pila de llamadas (rutas canónicas), para no entrar en un ciclo de sub-jobs. */
  private final Deque<String> pila = new ArrayDeque<>();
  /** Carpeta del job principal (la del script). */
  private final File base;

  private TraductorJob( JobMeta jm, Opciones op ) {
    this.op = op;
    this.c = new CtxTrad( new TransMeta(), op.destino, op.idioma );
    this.base = carpeta( jm );
    this.prog = new ProgramaJob( jm.getName(), jm.getFilename(), c );
  }

  public static ProgramaJob traducir( JobMeta jm, Opciones op ) {
    TraductorJob t = new TraductorJob( jm, op == null ? new Opciones() : op );
    return t.principal( jm );
  }

  private ProgramaJob principal( JobMeta jm ) {
    c.vars.remove( "Internal.Transformation.Filename.Directory" );
    c.vars.remove( "Internal.Transformation.Name" );
    c.vars.remove( "Internal.Entry.Current.Directory" );
    for ( String k : new String[] { "Internal.Job.Filename.Directory", "Internal.Entry.Current.Directory" } ) {
      c.vars.put( k, "os.environ.get(" + CtxTrad.lit( k ) + ", AQUI)" );
    }
    c.vars.put( "Internal.Job.Name", CtxTrad.lit( jm.getName() == null ? "" : jm.getName() ) );
    parametros( jm );
    c.importar( "import logging" );
    c.importar( "import sys" );
    c.importar( "import contextlib" );
    c.importar( "import functools" );
    ayudantesBase();
    pila.push( ruta( jm ) );
    prog.principal.addAll( flujo( jm ) );
    pila.pop();
    return prog;
  }

  /** Parámetros del job en VARS (con su valor por defecto; el entorno manda). */
  private void parametros( JobMeta jm ) {
    try {
      for ( String p : jm.listParameters() ) {
        String d = jm.getParameterDefault( p );
        c.vars.putIfAbsent( p, "os.environ.get(" + CtxTrad.lit( p ) + ", " + CtxTrad.lit( d == null ? "" : d ) + ")" );
      }
    } catch ( Exception e ) {
      // sin parámetros
    }
  }

  private void ayudantesBase() {
    c.ayudante( "entrada", String.join( "\n",
      "def entrada(f):",
      "    \"\"\"" + c.t( "Una entrada del job: devuelve True (éxito) o False (fallo); una excepción cuenta como fallo, "
        + "como en PDI.", "A job entry: returns True (success) or False (failure); an exception counts as a failure, "
          + "as in PDI.", "Uma entrada do job: devolve True (sucesso) ou False (falha); uma exceção conta como "
            + "falha, como no PDI." ) + "\"\"\"",
      "    @functools.wraps(f)",
      "    def envoltura():",
      "        log.info(\"%s\", f.__doc__.splitlines()[0] if f.__doc__ else f.__name__)",
      "        try:",
      "            return f() is not False",
      "        except Exception:",
      "            log.exception(\"" + c.t( "Falló", "Failed", "Falhou" ) + " %s\", f.__name__)",
      "            return False",
      "    return envoltura" ) );
    c.ayudante( "con_variables", String.join( "\n",
      "@contextlib.contextmanager",
      "def con_variables(valores):",
      "    \"\"\"" + c.t( "Da valor a variables (los parámetros de una entrada) mientras dura el bloque.",
        "Sets variables (the parameters of an entry) while the block lasts.",
        "Dá valor a variáveis (os parâmetros de uma entrada) enquanto dura o bloco." ) + "\"\"\"",
      "    antes = {k: VARS[k] for k in valores if k in VARS}",
      "    VARS.update(valores)",
      "    try:",
      "        yield",
      "    finally:",
      "        for k in valores:",
      "            if k in antes:",
      "                VARS[k] = antes[k]",
      "            else:",
      "                VARS.pop(k, None)" ) );
  }

  // ================================================================ entradas

  /** Las funciones de las entradas de un job y el cuerpo de su flujo. */
  private List<String> flujo( JobMeta jm ) {
    JobEntryCopy inicio = jm.findJobEntry( "START", 0, false );
    if ( inicio == null || !inicio.isStart() ) {
      inicio = null;
      for ( int i = 0; i < jm.nrJobEntries(); i++ ) {
        if ( jm.getJobEntry( i ).isStart() ) {
          inicio = jm.getJobEntry( i );
        }
      }
    }
    List<String> r = new ArrayList<>();
    if ( inicio == null ) {
      r.add( "# TODO: " + c.t( "el job no tiene START: no se ejecuta nada.", "the job has no START: nothing runs.",
        "o job não tem START: nada é executado." ) );
      r.add( "return False" );
      return r;
    }
    Grafo g = new Grafo( jm, inicio );
    notaInicio( jm, inicio, r );
    Map<JobEntryCopy, String> fn = new LinkedHashMap<>();
    for ( JobEntryCopy e : g.orden ) {
      if ( e == inicio && !g.esDestino( inicio ) ) {
        continue;
      }
      fn.put( e, funcionEntrada( jm, e ) );
    }
    int sueltas = 0;
    for ( int i = 0; i < jm.nrJobEntries(); i++ ) {
      if ( !g.orden.contains( jm.getJobEntry( i ) ) ) {
        sueltas++;
      }
    }
    if ( sueltas > 0 ) {
      r.add( "# " + c.t( sueltas + " entrada(s) sin camino desde START: PDI no las ejecuta y no se traducen.",
        sueltas + " entry(ies) with no path from START: PDI does not run them and they are not translated.",
        sueltas + " entrada(s) sem caminho desde START: o PDI não as executa e não são traduzidas." ) );
    }
    if ( g.ciclo ) {
      maquina( g, inicio, fn, r );
    } else {
      new Emisor( g, inicio, fn ).emitir( inicio, "", r );
    }
    return r;
  }

  private void notaInicio( JobMeta jm, JobEntryCopy inicio, List<String> r ) {
    try {
      Element x = XMLHandler.loadXMLString( inicio.getXML() ).getDocumentElement();
      boolean repetir = "Y".equalsIgnoreCase( CtxTrad.hijo( x, "repeat" ) );
      String tipo = CtxTrad.hijo( x, "schedulerType" );
      if ( repetir || ( !tipo.isEmpty() && !"0".equals( tipo ) ) ) {
        String n = c.t( "El START de «" + jm.getName() + "» está programado (tipo " + tipo + ", repetir=" + repetir
          + "): en Python, prográmalo con el sistema (cron, Programador de tareas) o un bucle con time.sleep.",
          "The START of '" + jm.getName() + "' is scheduled (type " + tipo + ", repeat=" + repetir + "): in Python, "
            + "schedule it with the system (cron, Task Scheduler) or a loop with time.sleep.",
          "O START de «" + jm.getName() + "» está agendado (tipo " + tipo + ", repetir=" + repetir + "): em Python, "
            + "agende-o com o sistema (cron, Agendador de tarefas) ou um laço com time.sleep." );
        prog.notas.add( n );
        r.add( "# " + c.t( "NOTA: ", "NOTE: ", "NOTA: " ) + n );
      }
    } catch ( Exception e ) {
      // sin XML
    }
  }

  /** Crea la función de una entrada y devuelve su nombre. */
  private String funcionEntrada( JobMeta jm, JobEntryCopy e ) {
    String id = e.getEntry() == null ? "" : e.getEntry().getPluginId();
    Element x;
    try {
      x = XMLHandler.loadXMLString( e.getXML() ).getDocumentElement();
    } catch ( Exception ex ) {
      x = null;
    }
    if ( "TRANS".equals( id ) ) {
      return transformacion( jm, e, x );
    }
    if ( "JOB".equals( id ) ) {
      return subjob( jm, e, x );
    }
    Bloque b = TradEntradasJob.traducir( e, x, c, jm );
    if ( e.isLaunchingInParallel() ) {
      paralelo( e, b );
    }
    String nombre = c.nuevo( "e_" + CtxTrad.ident( e.getName() ) );
    prog.funciones.add( new Funcion( nombre, b, true ) );
    return nombre;
  }

  private void paralelo( JobEntryCopy e, Bloque b ) {
    b.aproximado( c.t( "«" + e.getName() + "» lanza en paralelo las entradas que la siguen: aquí se ejecutan una "
      + "detrás de otra, en el orden de los hops.", "'" + e.getName() + "' launches the next entries in parallel: here "
        + "they run one after another, in hop order.", "«" + e.getName() + "» lança em paralelo as entradas "
          + "seguintes: aqui são executadas uma após a outra, na ordem dos hops." ) );
  }

  /** Entrada Transformation: def tr_x() con la transformación traducida dentro. */
  private String transformacion( JobMeta jm, JobEntryCopy e, Element x ) {
    Bloque b = new Bloque( e.getName(), "TRANS" );
    String nombre = c.nuevo( "tr_" + CtxTrad.ident( e.getName() ) );
    Funcion f = new Funcion( nombre, b, true );
    if ( e.isLaunchingInParallel() ) {
      paralelo( e, b );
    }
    String archivo = archivoEntrada( jm, e, x, b );
    TransMeta tm = null;
    String motivo = null;
    if ( archivo == null ) {
      motivo = c.t( "la transformación no se indica por nombre de archivo", "the transformation is not given by "
        + "file name", "a transformação não é indicada por nome de arquivo" );
    } else if ( !new File( archivo ).isFile() ) {
      motivo = c.t( "no se encuentra el archivo ", "file not found: ", "arquivo não encontrado: " )
        + EscritorPy.nombreArchivo( archivo );
    } else {
      try {
        tm = new TransMeta( archivo );
      } catch ( Exception ex ) {
        motivo = c.t( "no se pudo abrir ", "could not open ", "não foi possível abrir " ) + EscritorPy.nombreArchivo(
          archivo ) + ": " + ex.getMessage();
      }
    }
    if ( tm == null ) {
      b.manual( c.t( "Transformación sin traducir: ", "Untranslated transformation: ", "Transformação sem tradução: " )
        + motivo + "." );
      b.explicacion = c.t( "Ejecuta la transformación ", "Runs the transformation ", "Executa a transformação " )
        + CtxTrad.lit( CtxTrad.hijo( x, "filename" ) ) + ".";
      b.l( "# TODO: " + c.t( "traduce la transformación y pon aquí su código", "translate the transformation and put "
        + "its code here", "traduza a transformação e coloque aqui o seu código" ) );
      b.l( "raise FileNotFoundError(" + c.texto( CtxTrad.hijo( x, "filename" ) ) + ")" );
      prog.funciones.add( f );
      return nombre;
    }
    Programa p = TraductorTrans.traducir( tm, op.destino, op.idioma );
    f.programa = p;
    b.estado = p.estado();
    b.explicacion = c.t( "Transformación «", "Transformation '", "Transformação «" ) + p.nombre + c.t( "» (",
      "' (", "» (" ) + EscritorPy.nombreArchivo( archivo ) + "): " + p.bloques.size() + " steps.";
    Map<String, String> valores = valoresParametros( x, b );
    unir( p, valores, archivo );
    String carpeta = new File( archivo ).getAbsoluteFile().getParent();
    String rel = relativa( carpeta );
    if ( rel != null ) {
      String expr = "os.path.normpath(os.path.join(AQUI, " + CtxTrad.lit( rel ) + "))";
      valores.put( CtxTrad.lit( "Internal.Transformation.Filename.Directory" ), expr );
      valores.put( CtxTrad.lit( "Internal.Entry.Current.Directory" ), expr );
    }
    if ( valores.isEmpty() ) {
      f.posicion = 0;
      f.sangria = "    ";
    } else {
      b.l( "with con_variables(" + dict( valores ) + "):" );
      f.posicion = b.codigo.size();
      f.sangria = "        ";
      if ( p.bloques.isEmpty() ) {
        b.l( "    pass" );
      }
    }
    prog.funciones.add( f );
    return nombre;
  }

  /** Une imports, ayudantes, conexiones y VARS de la transformación con los del job. */
  private void unir( Programa p, Map<String, String> valores, String archivo ) {
    CtxTrad pc = p.ctx;
    c.imports.addAll( pc.imports );
    for ( Map.Entry<String, String> a : pc.ayudantes.entrySet() ) {
      c.ayudante( a.getKey(), a.getValue() );
    }
    for ( Map.Entry<String, org.pentaho.di.core.database.DatabaseMeta> d : pc.conexiones.entrySet() ) {
      if ( !c.conexiones.containsKey( d.getKey() ) ) {
        c.conexiones.put( d.getKey(), neutra( d.getValue() ) );
      }
    }
    String cuerpo = EscritorPy.cuerpo( p, "" );
    for ( Map.Entry<String, String> v : pc.vars.entrySet() ) {
      String k = v.getKey();
      if ( k.equals( "Internal.Transformation.Filename.Directory" ) || k.equals( "Internal.Entry.Current.Directory" ) ) {
        c.vars.putIfAbsent( k, v.getValue() );
        continue;
      }
      if ( k.equals( "Internal.Transformation.Name" ) ) { // propia de cada transformación: sólo si la usa
        if ( cuerpo.contains( k ) ) {
          valores.put( CtxTrad.lit( k ), v.getValue() );
        }
        continue;
      }
      String actual = c.vars.get( k );
      if ( actual == null ) {
        c.vars.put( k, v.getValue() );
      } else if ( !actual.equals( v.getValue() ) && cuerpo.contains( k ) && !valores.containsKey( CtxTrad.lit( k ) )
        && !k.startsWith( "Internal.Job" ) ) {
        valores.put( CtxTrad.lit( k ), v.getValue() );
      }
    }
  }

  /**
   * Copia de la conexión sin las variables de esta máquina: su URL JDBC queda con ${Internal...} (que el script
   * resuelve con VARS) y nunca con la ruta local del .kjb.
   */
  static org.pentaho.di.core.database.DatabaseMeta neutra( org.pentaho.di.core.database.DatabaseMeta d ) {
    org.pentaho.di.core.database.DatabaseMeta r = (org.pentaho.di.core.database.DatabaseMeta) d.clone();
    r.shareVariablesWith( new Variables() );
    return r;
  }

  /** Parámetros que la entrada le pasa a la transformación o al sub-job (literal de clave → expresión). */
  private Map<String, String> valoresParametros( Element x, Bloque b ) {
    Map<String, String> r = new LinkedHashMap<>();
    Element ps = XMLHandler.getSubNode( x, "parameters" ) instanceof Element ? (Element) XMLHandler.getSubNode( x,
      "parameters" ) : null;
    if ( ps == null ) {
      return r;
    }
    for ( org.w3c.dom.Node n : XMLHandler.getNodes( ps, "parameter" ) ) {
      Element p = (Element) n;
      String nom = CtxTrad.hijo( p, "name" );
      if ( nom.isEmpty() ) {
        continue;
      }
      String campo = CtxTrad.hijo( p, "stream_name" );
      if ( !campo.isEmpty() ) {
        b.aproximado( c.t( "El parámetro " + nom + " sale del campo «" + campo + "» de las filas del resultado "
          + "anterior: aquí se toma de RESULT[\"rows\"] (primera fila).", "Parameter " + nom + " comes from field '"
            + campo + "' of the previous result rows: here it is taken from RESULT[\"rows\"] (first row).",
          "O parâmetro " + nom + " vem do campo «" + campo + "» das linhas do resultado anterior: aqui é lido de "
            + "RESULT[\"rows\"] (primeira linha)." ) );
        r.put( CtxTrad.lit( nom ), c.pandas() ? "str(RESULT[\"rows\"][" + CtxTrad.lit( campo ) + "].iloc[0])"
          : "str(RESULT[\"rows\"].first()[" + CtxTrad.lit( campo ) + "])" );
      } else {
        r.put( CtxTrad.lit( nom ), c.texto( CtxTrad.hijo( p, "value" ) ) );
      }
    }
    if ( "Y".equalsIgnoreCase( CtxTrad.hijo( x, "exec_per_row" ) ) ) {
      b.aproximado( c.t( "En PDI se ejecuta una vez por cada fila del resultado anterior: aquí, una sola vez.",
        "In PDI it runs once for each row of the previous result: here, only once.",
        "No PDI é executada uma vez por cada linha do resultado anterior: aqui, uma só vez." ) );
    }
    return r;
  }

  private static String dict( Map<String, String> m ) {
    StringBuilder s = new StringBuilder( "{" );
    for ( Map.Entry<String, String> e : m.entrySet() ) {
      s.append( s.length() > 1 ? ", " : "" ).append( e.getKey() ).append( ": " ).append( e.getValue() );
    }
    return s.append( "}" ).toString();
  }

  /** Entrada Job: def job_x() con el flujo del sub-job dentro (sus entradas son funciones propias). */
  private String subjob( JobMeta jm, JobEntryCopy e, Element x ) {
    Bloque b = new Bloque( e.getName(), "JOB" );
    String nombre = c.nuevo( "job_" + CtxTrad.ident( e.getName() ) );
    Funcion f = new Funcion( nombre, b, true );
    if ( e.isLaunchingInParallel() ) {
      paralelo( e, b );
    }
    String archivo = archivoEntrada( jm, e, x, b );
    JobMeta sub = null;
    String motivo = null;
    String canon = null;
    if ( archivo == null ) {
      motivo = c.t( "el job no se indica por nombre de archivo", "the job is not given by file name",
        "o job não é indicado por nome de arquivo" );
    } else if ( !new File( archivo ).isFile() ) {
      motivo = c.t( "no se encuentra el archivo ", "file not found: ", "arquivo não encontrado: " )
        + EscritorPy.nombreArchivo( archivo );
    } else {
      canon = canonica( archivo );
      if ( pila.contains( canon ) ) {
        motivo = c.t( "el job se llama a sí mismo (directa o indirectamente)", "the job calls itself (directly or "
          + "indirectly)", "o job chama a si mesmo (direta ou indiretamente)" );
      } else {
        try {
          sub = new JobMeta( archivo, null );
        } catch ( Exception ex ) {
          motivo = c.t( "no se pudo abrir ", "could not open ", "não foi possível abrir " ) + EscritorPy
            .nombreArchivo( archivo ) + ": " + ex.getMessage();
        }
      }
    }
    if ( sub == null ) {
      b.manual( c.t( "Sub-job sin traducir: ", "Untranslated sub-job: ", "Sub-job sem tradução: " ) + motivo + "." );
      b.explicacion = c.t( "Ejecuta el job ", "Runs the job ", "Executa o job " ) + CtxTrad.lit( CtxTrad.hijo( x,
        "filename" ) ) + ".";
      b.l( "# TODO: " + c.t( "traduce el sub-job y pon aquí su flujo", "translate the sub-job and put its flow here",
        "traduza o sub-job e coloque aqui o seu fluxo" ) );
      b.l( "raise NotImplementedError(" + c.texto( CtxTrad.hijo( x, "filename" ) ) + ")" );
      prog.funciones.add( f );
      return nombre;
    }
    b.explicacion = c.t( "Job «", "Job '", "Job «" ) + sub.getName() + c.t( "» (", "' (", "» (" ) + EscritorPy
      .nombreArchivo( archivo ) + c.t( "): su flujo, con sus entradas definidas arriba.", "): its flow, with its "
        + "entries defined above.", "): o seu fluxo, com as suas entradas definidas acima." );
    Map<String, String> valores = valoresParametros( x, b );
    try {
      for ( String p : sub.listParameters() ) {
        String d = sub.getParameterDefault( p );
        String k = CtxTrad.lit( p );
        if ( !valores.containsKey( k ) && !c.vars.containsKey( p ) ) {
          c.vars.put( p, "os.environ.get(" + k + ", " + CtxTrad.lit( d == null ? "" : d ) + ")" );
        }
      }
    } catch ( Exception ex ) {
      // sin parámetros
    }
    String rel = relativa( new File( archivo ).getAbsoluteFile().getParent() );
    if ( rel != null ) {
      String expr = "os.path.normpath(os.path.join(AQUI, " + CtxTrad.lit( rel ) + "))";
      valores.put( CtxTrad.lit( "Internal.Job.Filename.Directory" ), expr );
      valores.put( CtxTrad.lit( "Internal.Entry.Current.Directory" ), expr );
    }
    pila.push( canon );
    List<String> cuerpo = flujo( sub );
    pila.pop();
    if ( valores.isEmpty() ) {
      b.codigo.addAll( cuerpo );
    } else {
      b.l( "with con_variables(" + dict( valores ) + "):" );
      for ( String l : cuerpo ) {
        b.l( l.isEmpty() ? "" : "    " + l );
      }
    }
    prog.funciones.add( f );
    return nombre;
  }

  /** Ruta del .ktr / .kjb de la entrada con las variables resueltas; null si no es por archivo. */
  private String archivoEntrada( JobMeta jm, JobEntryCopy e, Element x, Bloque b ) {
    String metodo = CtxTrad.hijo( x, "specification_method" );
    String f = CtxTrad.hijo( x, "filename" );
    if ( ( !metodo.isEmpty() && !"filename".equalsIgnoreCase( metodo ) ) || f.isEmpty() ) {
      return null;
    }
    Variables v = new Variables();
    v.initializeVariablesFrom( null );
    File dir = carpeta( jm );
    String d = dir == null ? "" : dir.getAbsolutePath().replace( '\\', '/' );
    v.setVariable( "Internal.Entry.Current.Directory", d );
    v.setVariable( "Internal.Job.Filename.Directory", d );
    try {
      for ( String p : jm.listParameters() ) {
        String def = jm.getParameterDefault( p );
        if ( def != null ) {
          v.setVariable( p, def );
        }
      }
    } catch ( Exception ex ) {
      // sin parámetros
    }
    String r = v.environmentSubstitute( f ).replaceFirst( "^file:///?", "" );
    File a = new File( r );
    if ( !a.isAbsolute() && dir != null ) {
      a = new File( dir, r );
    }
    return a.getAbsolutePath();
  }

  private static File carpeta( JobMeta jm ) {
    String f = jm.getFilename();
    if ( f == null || f.isEmpty() ) {
      return null;
    }
    return new File( f.replaceFirst( "^file:///?", "" ) ).getAbsoluteFile().getParentFile();
  }

  private static String ruta( JobMeta jm ) {
    String f = jm.getFilename();
    return f == null ? "" : canonica( f.replaceFirst( "^file:///?", "" ) );
  }

  private static String canonica( String f ) {
    try {
      return new File( f ).getCanonicalPath();
    } catch ( Exception e ) {
      return new File( f ).getAbsolutePath();
    }
  }

  /** Carpeta relativa a la del job principal ("a/b", "../x"); null si es la misma o no se puede calcular. */
  private String relativa( String carpeta ) {
    if ( base == null || carpeta == null ) {
      return null;
    }
    try {
      java.nio.file.Path b = base.getCanonicalFile().toPath();
      java.nio.file.Path d = new File( carpeta ).getCanonicalFile().toPath();
      if ( b.equals( d ) ) {
        return null;
      }
      return b.relativize( d ).toString().replace( '\\', '/' );
    } catch ( Exception e ) {
      return null;
    }
  }

  // ================================================================ flujo

  /** Un hop que PDI puede seguir: destino y condición (null incondicional, true éxito, false fallo). */
  static final class Salto {
    final JobEntryCopy destino;
    final Boolean cuando;

    Salto( JobEntryCopy destino, Boolean cuando ) {
      this.destino = destino;
      this.cuando = cuando;
    }
  }

  /** Las entradas alcanzables desde START, sus saltos (en el orden de los hops) y si hay un bucle. */
  static final class Grafo {
    final JobMeta jm;
    final List<JobEntryCopy> orden = new ArrayList<>();
    final Map<JobEntryCopy, List<Salto>> saltos = new HashMap<>();
    final Map<JobEntryCopy, Integer> entrantes = new HashMap<>();
    boolean ciclo;

    Grafo( JobMeta jm, JobEntryCopy inicio ) {
      this.jm = jm;
      recorrer( inicio, new ArrayList<>() );
    }

    List<Salto> de( JobEntryCopy e ) {
      List<Salto> r = saltos.get( e );
      if ( r != null ) {
        return r;
      }
      r = new ArrayList<>();
      for ( int i = 0; i < jm.nrJobHops(); i++ ) {
        JobHopMeta h = jm.getJobHop( i );
        if ( !h.isEnabled() || h.getFromEntry() == null || h.getToEntry() == null || !e.equals( h.getFromEntry() ) ) {
          continue;
        }
        if ( h.isUnconditional() ) {
          r.add( new Salto( h.getToEntry(), null ) );
        } else if ( e.evaluates() ) {
          r.add( new Salto( h.getToEntry(), h.getEvaluation() ) );
        }
      }
      saltos.put( e, r );
      return r;
    }

    private void recorrer( JobEntryCopy e, List<JobEntryCopy> camino ) {
      if ( camino.contains( e ) ) {
        ciclo = true;
        return;
      }
      if ( orden.contains( e ) ) {
        return;
      }
      orden.add( e );
      camino.add( e );
      for ( Salto s : de( e ) ) {
        entrantes.merge( s.destino, 1, Integer::sum );
        recorrer( s.destino, camino );
      }
      camino.remove( camino.size() - 1 );
    }

    boolean esDestino( JobEntryCopy e ) {
      return entrantes.containsKey( e );
    }
  }

  /** Bucles: una máquina de estados con una pila de entradas pendientes (el mismo orden en profundidad que PDI). */
  private void maquina( Grafo g, JobEntryCopy inicio, Map<JobEntryCopy, String> fn, List<String> r ) {
    String n = c.t( "El flujo tiene un bucle: se recorre con una pila de entradas pendientes, en el mismo orden que "
      + "PDI (cada entrada, y después lo que sale de ella por sus hops, en orden).", "The flow has a loop: it is "
        + "walked with a stack of pending entries, in the same order as PDI (each entry, then what leaves it through "
          + "its hops, in order).", "O fluxo tem um laço: é percorrido com uma pilha de entradas pendentes, na mesma "
            + "ordem que o PDI (cada entrada, e depois o que sai dela pelos seus hops, em ordem)." );
    prog.notas.add( n );
    r.add( "# " + n );
    r.add( "# " + c.t( "flujo: entrada → [(siguiente, None = siempre / True = si va bien / False = si falla)]",
      "flow: entry → [(next, None = always / True = on success / False = on failure)]",
      "fluxo: entrada → [(seguinte, None = sempre / True = se correr bem / False = se falhar)]" ) );
    r.add( "flujo = {" );
    for ( JobEntryCopy e : g.orden ) {
      String f = fn.get( e );
      if ( f == null || g.de( e ).isEmpty() ) {
        continue;
      }
      StringBuilder s = new StringBuilder( "    " + f + ": [" );
      boolean primero = true;
      for ( Salto x : g.de( e ) ) {
        s.append( primero ? "" : ", " ).append( "(" ).append( fn.get( x.destino ) ).append( ", " ).append( x.cuando
          == null ? "None" : x.cuando ? "True" : "False" ).append( ")" );
        primero = false;
      }
      r.add( s.append( "]," ).toString() );
    }
    r.add( "}" );
    StringBuilder p = new StringBuilder();
    List<Salto> ini = g.de( inicio );
    for ( int i = ini.size() - 1; i >= 0; i-- ) {
      p.append( p.length() == 0 ? "" : ", " ).append( fn.get( ini.get( i ).destino ) );
    }
    r.add( "pendientes = [" + p + "]" );
    r.add( "ok = True" );
    r.add( "while pendientes:" );
    r.add( "    actual = pendientes.pop()" );
    r.add( "    ok = actual()" );
    r.add( "    pendientes += reversed([sig for sig, cuando in flujo.get(actual, []) if cuando is None or cuando == ok])" );
    r.add( "return ok" );
  }

  /** Flujo sin bucles: código recto con if / return; las ramas compartidas grandes van a funciones rama_x(). */
  private final class Emisor {
    final Grafo g;
    final JobEntryCopy inicio;
    final Map<JobEntryCopy, String> fn;
    final Map<JobEntryCopy, String> ramas = new HashMap<>();
    final Map<JobEntryCopy, Integer> tamanos = new HashMap<>();

    Emisor( Grafo g, JobEntryCopy inicio, Map<JobEntryCopy, String> fn ) {
      this.g = g;
      this.inicio = inicio;
      this.fn = fn;
    }

    /** Entradas que escribiría el código recto desde e (con las repeticiones). */
    int tamano( JobEntryCopy e ) {
      Integer t = tamanos.get( e );
      if ( t != null ) {
        return t;
      }
      int n = 1;
      for ( Salto s : g.de( e ) ) {
        n += tamano( s.destino );
      }
      tamanos.put( e, n );
      return n;
    }

    void emitir( JobEntryCopy e, String ind, List<String> r ) {
      List<Salto> ss = g.de( e );
      String llamada = e == inicio ? null : fn.get( e ) + "()";
      if ( ss.isEmpty() ) {
        r.add( ind + "return " + ( llamada == null ? "True" : llamada ) );
        return;
      }
      if ( ss.size() == 1 ) {
        Salto s = ss.get( 0 );
        if ( llamada != null ) {
          if ( s.cuando == null ) {
            r.add( ind + llamada );
          } else {
            r.add( ind + ( s.cuando ? "if not " : "if " ) + llamada + ":" );
            r.add( ind + "    return " + ( s.cuando ? "False" : "True" ) );
          }
        }
        seguir( s.destino, ind, r );
        return;
      }
      if ( ss.size() == 2 && llamada != null && ss.get( 0 ).cuando != null && ss.get( 1 ).cuando != null && !ss.get(
        0 ).cuando.equals( ss.get( 1 ).cuando ) ) {
        Salto bien = ss.get( 0 ).cuando ? ss.get( 0 ) : ss.get( 1 );
        Salto mal = ss.get( 0 ).cuando ? ss.get( 1 ) : ss.get( 0 );
        r.add( ind + "if not " + llamada + ":" );
        seguir( mal.destino, ind + "    ", r );
        seguir( bien.destino, ind, r );
        return;
      }
      // varios hops: PDI los sigue en orden, uno detrás de otro; el resultado es el del último camino
      r.add( ind + "# " + c.t( "varios hops: PDI los sigue en orden y el resultado es el del último camino",
        "several hops: PDI follows them in order and the result is the last path's",
        "vários hops: o PDI segue-os em ordem e o resultado é o do último caminho" ) );
      r.add( ind + "ok = " + ( llamada == null ? "True" : llamada ) );
      r.add( ind + "res = ok" );
      for ( Salto s : ss ) {
        String si = s.cuando == null ? "" : s.cuando ? "if ok: " : "if not ok: ";
        r.add( ind + si + "res = " + rama( s.destino ) + "()" );
      }
      r.add( ind + "return res" );
    }

    void seguir( JobEntryCopy d, String ind, List<String> r ) {
      Integer n = g.entrantes.get( d );
      if ( n != null && n > 1 && tamano( d ) > 3 ) {
        r.add( ind + "return " + rama( d ) + "()" );
      } else {
        emitir( d, ind, r );
      }
    }

    String rama( JobEntryCopy d ) {
      String n = ramas.get( d );
      if ( n != null ) {
        return n;
      }
      n = c.nuevo( "rama_" + CtxTrad.ident( d.getName() ) );
      ramas.put( d, n );
      Bloque b = new Bloque( d.getName(), "rama" );
      b.explicacion = c.t( "Sigue el flujo desde «", "Follows the flow from '", "Segue o fluxo a partir de «" ) + d
        .getName() + c.t( "» y devuelve su resultado.", "' and returns its result.", "» e devolve o seu resultado." );
      emitir( d, "", b.codigo );
      prog.funciones.add( new Funcion( n, b, false ) );
      return n;
    }
  }
}
