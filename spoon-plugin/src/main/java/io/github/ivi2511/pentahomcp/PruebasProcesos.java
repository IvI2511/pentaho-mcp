package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * El lado de Spoon de las pruebas (0.7.0, P1 y P2): arma el trabajo, lanza la JVM de las pruebas ({@link PruebasMain})
 * con los mismos helpers que el corrector ({@link CorrectorProcesos#comandoBase}, {@link CorrectorProcesos#lanzarHija},
 * {@link CorrectorProcesos#matar}), avisa cada resultado apenas esta y deja cancelar. Nunca corre nada de una prueba en
 * esta JVM.
 * <ul>
 * <li><b>Una corrida a la vez</b>: las corridas se encolan en un solo hilo (la memoria de una PC de clase no da para
 * dos JVM de Kettle a la vez ademas de Spoon). Una corrida con pruebas de entornos distintos (locale, zona) lanza una
 * JVM por entorno, una detras de otra.</li>
 * <li><b>Cancelar</b>: crea {@code trabajo.json.cancel} (la hija corta la transformacion en curso y no corre las que
 * faltan) y si en {@value #MS_GRACIA} ms no salio, la mata.</li>
 * <li><b>Plazo</b>: el arranque de Kettle y la suma de los {@code timeout_s} de sus pruebas (por las veces que corre
 * cada una) mas un margen; si se pasa, se mata la JVM y las pruebas sin resultado quedan en {@code error}.</li>
 * <li>Todo queda en una carpeta temporal propia (KETTLE_HOME vacio, copias de los documentos abiertos, el log de la
 * hija) que se borra al terminar.</li>
 * </ul>
 *
 * @dueño P1 (0.7.0)
 */
final class PruebasProcesos {

  static final long MS_GRACIA = 5000;
  static final long MS_ARRANQUE = 180000;
  static final long MS_MARGEN = 60000;
  static final int XMX_MB = 768;

  /** Como lanzar la hija (pdi, java, classpath, -Xmx); null = lo de Spoon. Solo lo fijan las pruebas. */
  static volatile CorrectorProcesos.Opciones procesos;

  private static final AtomicInteger NUMERO = new AtomicInteger();
  private static final ExecutorService COLA = Executors.newSingleThreadExecutor( r -> {
    Thread t = new Thread( r, "pentaho-live-tests" );
    t.setDaemon( true );
    return t;
  } );

  private PruebasProcesos() {
  }

  /** Lo que se avisa mientras corre (en el hilo de la corrida: no bloquear). */
  interface Oyente {
    void empezada( Tarea t );

    void terminada( Tarea t, ResultadoPrueba r );
  }

  /** Una prueba que correr (o crear). */
  static final class Tarea {
    String tipo = PruebasMain.CORRER;
    /** El .test.json (correr). */
    File prueba;
    /** La copia del documento abierto; null = la .ktr del disco. */
    File ktr;
    /** Crear: el pedido. */
    PruebasMain.Creacion crear;
    /** Contraseñas de sus bases (por conexion, sin mayusculas), solo en memoria. */
    final Map<String, String> claves = new LinkedHashMap<String, String>();
    /** Para mostrar mientras corre. */
    String nombre;
    volatile ResultadoPrueba resultado;

    static Tarea correr( File prueba, File ktr ) {
      Tarea t = new Tarea();
      t.prueba = prueba.getAbsoluteFile();
      t.ktr = ktr;
      t.nombre = PruebasMotor.nombreDeArchivo( prueba );
      return t;
    }

    static Tarea crear( PruebasMain.Creacion c, File ktr ) {
      Tarea t = new Tarea();
      t.tipo = PruebasMain.CREAR;
      t.crear = c;
      t.ktr = ktr;
      t.nombre = c.nombre;
      t.prueba = new File( c.carpeta, c.nombre + DefPrueba.EXTENSION );
      return t;
    }
  }

  /** Una corrida encolada. */
  static final class Corrida {
    final List<Tarea> tareas;
    final Oyente oyente;
    final CountDownLatch fin = new CountDownLatch( 1 );
    /** Lo que hay que borrar al terminar (copias de documentos abiertos), ademas de la carpeta propia. */
    final List<File> temporales = new ArrayList<File>();
    volatile boolean cancelada;
    volatile boolean empezo;
    private volatile Process proceso;
    private volatile File trabajoActual;
    volatile String errorGeneral;

    Corrida( List<Tarea> tareas, Oyente oyente ) {
      this.tareas = tareas;
      this.oyente = oyente;
    }

    /** Corta: la prueba en curso se para, las que faltan quedan canceladas. No bloquea. */
    void cancelar() {
      cancelada = true;
      File t = trabajoActual;
      if ( t != null ) {
        try {
          new File( t.getPath() + ".cancel" ).createNewFile();
        } catch ( IOException ignored ) {
          // se mata igual
        }
      }
      final Process p = proceso;
      if ( p != null ) {
        Thread h = new Thread( () -> {
          try {
            if ( !p.waitFor( MS_GRACIA, TimeUnit.MILLISECONDS ) ) {
              CorrectorProcesos.matar( p );
            }
          } catch ( InterruptedException e ) {
            CorrectorProcesos.matar( p );
          }
        }, "pentaho-live-tests-cancel" );
        h.setDaemon( true );
        h.start();
      }
    }

    boolean esperar( long ms ) throws InterruptedException {
      return fin.await( ms, TimeUnit.MILLISECONDS );
    }

    boolean terminada() {
      return fin.getCount() == 0;
    }

    List<ResultadoPrueba> resultados() {
      List<ResultadoPrueba> l = new ArrayList<ResultadoPrueba>();
      for ( Tarea t : tareas ) {
        ResultadoPrueba r = t.resultado;
        l.add( r != null ? r : ResultadoPrueba.sin( t.nombre, t.prueba == null ? null : t.prueba.getPath(),
          cancelada ? ResultadoPrueba.CANCELADA : ResultadoPrueba.ERROR, cancelada ? "Cancelled" : "Not run yet" ) );
      }
      return l;
    }
  }

  /** Encola la corrida y vuelve enseguida. */
  static Corrida iniciar( List<Tarea> tareas, Oyente oyente ) {
    return iniciar( tareas, oyente, null );
  }

  /** Igual, y al terminar borra los temporales (las copias de los documentos abiertos). */
  static Corrida iniciar( List<Tarea> tareas, Oyente oyente, List<File> temporales ) {
    final Corrida c = new Corrida( new ArrayList<Tarea>( tareas ), oyente );
    if ( temporales != null ) {
      c.temporales.addAll( temporales );
    }
    COLA.submit( () -> {
      try {
        correr( c );
      } catch ( Throwable t ) {
        c.errorGeneral = RunMotor.primeraLinea( t );
        McpLifecycleListener.LOG.logError( "Pentaho Live: the tests could not run", t );
      } finally {
        for ( Tarea t : c.tareas ) {
          if ( t.resultado == null ) {
            t.resultado = ResultadoPrueba.sin( t.nombre, t.prueba == null ? null : t.prueba.getPath(), c.cancelada
              ? ResultadoPrueba.CANCELADA : ResultadoPrueba.ERROR, c.cancelada ? "Cancelled" : "The tests could not "
              + "run: " + c.errorGeneral );
            avisar( c, t );
          }
        }
        for ( File f : c.temporales ) {
          borrar( f );
        }
        c.fin.countDown();
      }
    } );
    return c;
  }

  /** Corre y espera (las herramientas MCP); al terminar borra los temporales. Si vence el plazo, cancela. */
  static List<ResultadoPrueba> correrYEsperar( List<Tarea> tareas, long ms, List<File> temporales )
    throws InterruptedException {
    Corrida c = iniciar( tareas, null, temporales );
    if ( !c.esperar( ms ) ) {
      c.cancelar();
      c.esperar( MS_GRACIA + 15000 );
    }
    return c.resultados();
  }

  // ------------------------------------------------------------------ la corrida

  private static void correr( Corrida c ) throws Exception {
    c.empezo = true;
    if ( c.cancelada ) {
      return;
    }
    // un grupo por entorno (locale, zona, encoding): una JVM por grupo
    Map<String, List<Tarea>> grupos = new LinkedHashMap<String, List<Tarea>>();
    Map<String, DefPrueba.Entorno> entornos = new LinkedHashMap<String, DefPrueba.Entorno>();
    for ( Tarea t : c.tareas ) {
      DefPrueba.Entorno e = entorno( t );
      String k = ( e.locale + "|" + e.zonaHoraria + "|" + e.encoding + "|" + e.usaKettleDelUsuario() ).toLowerCase(
        Locale.ROOT );
      grupos.computeIfAbsent( k, x -> new ArrayList<Tarea>() ).add( t );
      entornos.putIfAbsent( k, e );
    }
    for ( Map.Entry<String, List<Tarea>> g : grupos.entrySet() ) {
      if ( c.cancelada ) {
        return;
      }
      lanzar( c, g.getValue(), entornos.get( g.getKey() ) );
    }
  }

  /** El entorno de una tarea (el de la prueba, o el pedido al crear; el por defecto si no se puede leer). */
  private static DefPrueba.Entorno entorno( Tarea t ) {
    if ( t.crear != null ) {
      return t.crear.entorno != null ? t.crear.entorno : new DefPrueba.Entorno();
    }
    try {
      return PruebasMotor.cargar( t.prueba ).entorno;
    } catch ( Exception e ) {
      return new DefPrueba.Entorno();
    }
  }

  private static void lanzar( Corrida c, List<Tarea> tareas, DefPrueba.Entorno entorno ) throws Exception {
    File dir = Files.createTempDirectory( "pentaho-live-tests-" ).toFile();
    try {
      PruebasMain.Trabajo tr = new PruebasMain.Trabajo();
      tr.versionPlugin = CorrectorProcesos.versionDelPlugin();
      if ( tr.versionPlugin == null ) {
        tr.versionPlugin = McpLifecycleListener.VERSION;
      }
      Map<String, String> entorno2 = new LinkedHashMap<String, String>();
      long plazo = MS_ARRANQUE + MS_MARGEN;
      File resultados = new File( dir, "resultados" );
      for ( int i = 0; i < tareas.size(); i++ ) {
        Tarea t = tareas.get( i );
        PruebasMain.Tarea x = new PruebasMain.Tarea();
        x.tipo = t.tipo;
        x.prueba = t.prueba == null ? null : t.prueba.getAbsolutePath();
        x.ktr = t.ktr == null ? null : t.ktr.getAbsolutePath();
        x.crear = t.crear;
        x.resultado = new File( resultados, i + ".json" ).getAbsolutePath();
        tr.tareas.add( x );
        List<BaseDePrueba> bases = t.crear != null ? t.crear.bases : basesDe( t.prueba );
        for ( int b = 0; b < bases.size(); b++ ) {
          BaseDePrueba base = bases.get( b );
          String clave = CorrectorBases.clave( base, t.claves );
          if ( clave != null ) {
            entorno2.put( PruebasMain.VARIABLE_CLAVE + i + "_" + b, clave );
          }
        }
        int veces = 1;
        long porVez = entorno.timeoutSeg * 1000L;
        try {
          if ( t.crear == null ) {
            veces = PruebasMotor.cargar( t.prueba ).caso.vecesQueCorre();
          } else {
            veces = 4; // en vivo, con los datasets y la prueba
          }
        } catch ( Exception ignored ) {
          // una prueba invalida no corre: no suma plazo
        }
        plazo += porVez * veces + 15000;
      }
      File trabajo = new File( dir, "trabajo.json" );
      CorrectorJson.guardar( tr, trabajo );
      c.trabajoActual = trabajo;

      CorrectorProcesos.Opciones op = opciones();
      File pdi = op.pdi != null ? op.pdi : CorrectorProcesos.pdiDetectado();
      if ( pdi == null ) {
        throw new IOException( "Cannot find PDI's data-integration folder to start the test JVM" );
      }
      Pack pk = new Pack();
      pk.politica = entorno.politica();
      List<String> base = CorrectorProcesos.comandoBase( op, pk.completar(), pdi );
      File log = new File( dir, "pruebas.log" );
      File hija = new File( dir, "jvm" );
      if ( c.cancelada ) {
        return;
      }
      for ( Tarea t : tareas ) {
        if ( c.oyente != null ) {
          try {
            c.oyente.empezada( t );
          } catch ( Throwable ignored ) {
            // el oyente no frena la corrida
          }
        }
      }
      File kettle = entorno.usaKettleDelUsuario() ? ( op.kettleDelUsuario != null ? op.kettleDelUsuario
        : PruebasKettleHome.carpetaDelUsuario() ) : null;
      List<String> secretos = PruebasKettleHome.secretos( kettle );
      Process p = CorrectorProcesos.lanzarHija( base, hija, PruebasMain.class.getName(), Collections.singletonList(
        trabajo.getAbsolutePath() ), log, "tests-" + NUMERO.incrementAndGet(), entorno2, kettle );
      c.proceso = p;
      if ( c.cancelada ) {
        c.cancelar(); // se cancelo mientras arrancaba
      }
      long limite = System.currentTimeMillis() + plazo;
      boolean[] vistos = new boolean[ tareas.size() ];
      while ( true ) {
        boolean salio = p.waitFor( 200, TimeUnit.MILLISECONDS );
        recoger( c, tareas, tr, vistos );
        if ( salio ) {
          break;
        }
        if ( System.currentTimeMillis() > limite ) {
          CorrectorProcesos.matar( p );
          c.errorGeneral = "the test JVM did not finish in " + plazo / 1000 + " s and was stopped";
          break;
        }
      }
      recoger( c, tareas, tr, vistos );
      List<String> tapar = new ArrayList<String>( secretos );
      tapar.addAll( entorno2.values() );
      String cola = CorrectorBases.tapar( cola( log, 40 ), tapar );
      for ( int i = 0; i < tareas.size(); i++ ) {
        Tarea t = tareas.get( i );
        if ( t.resultado == null ) {
          t.resultado = ResultadoPrueba.sin( t.nombre, t.prueba == null ? null : t.prueba.getPath(), c.cancelada
            ? ResultadoPrueba.CANCELADA : ResultadoPrueba.ERROR, c.cancelada ? "Cancelled" : "The test JVM ended "
            + "without a result" + ( c.errorGeneral == null ? " (exit code " + exitCode( p ) + ")" : ": "
            + c.errorGeneral ) + ( cola.isEmpty() ? "" : "\n" + cola ) );
          avisar( c, t );
        }
      }
    } finally {
      c.proceso = null;
      c.trabajoActual = null;
      borrar( dir );
    }
  }

  private static String exitCode( Process p ) {
    try {
      return String.valueOf( p.exitValue() );
    } catch ( Throwable t ) {
      return "?";
    }
  }

  /** Lee los resultados que ya escribio la hija y los avisa (una sola vez cada uno). */
  private static void recoger( Corrida c, List<Tarea> tareas, PruebasMain.Trabajo tr, boolean[] vistos ) {
    for ( int i = 0; i < tareas.size(); i++ ) {
      if ( vistos[ i ] ) {
        continue;
      }
      File f = new File( tr.tareas.get( i ).resultado );
      if ( !f.isFile() ) {
        continue;
      }
      try {
        tareas.get( i ).resultado = CorrectorJson.leer( f, ResultadoPrueba.class );
        vistos[ i ] = true;
        avisar( c, tareas.get( i ) );
      } catch ( Exception ignored ) {
        // a medio escribir no puede ser (rename atomico); se reintenta
      }
    }
  }

  private static void avisar( Corrida c, Tarea t ) {
    PruebasArchivos.recordar( t.resultado );
    if ( c.oyente != null ) {
      try {
        c.oyente.terminada( t, t.resultado );
      } catch ( Throwable ignored ) {
        // el oyente no frena la corrida
      }
    }
  }

  private static List<BaseDePrueba> basesDe( File prueba ) {
    try {
      return PruebasMotor.cargar( prueba ).bases;
    } catch ( Exception e ) {
      return new ArrayList<BaseDePrueba>();
    }
  }

  /** Las opciones de la hija: las de las pruebas, las del corrector o las de Spoon. */
  static CorrectorProcesos.Opciones opciones() {
    CorrectorProcesos.Opciones b = procesos != null ? procesos : CorrectorTools.procesos;
    CorrectorProcesos.Opciones o = new CorrectorProcesos.Opciones();
    o.xmxMb = XMX_MB;
    if ( b != null ) {
      o.pdi = b.pdi;
      o.classpath = b.classpath;
      o.java = b.java;
      o.xmxMb = b.xmxMb;
      o.argsPadre = b.argsPadre;
      o.propiedades.putAll( b.propiedades );
      o.kettleDelUsuario = b.kettleDelUsuario;
    }
    return o;
  }

  /** Las ultimas n lineas del log de la hija (para el mensaje de una JVM que se cayo). */
  static String cola( File log, int n ) {
    if ( !log.isFile() ) {
      return "";
    }
    try ( RandomAccessFile f = new RandomAccessFile( log, "r" ) ) {
      long desde = Math.max( 0, f.length() - 16000 );
      byte[] b = new byte[ (int) ( f.length() - desde ) ];
      f.seek( desde );
      f.readFully( b );
      String[] ls = new String( b, StandardCharsets.UTF_8 ).split( "\\r?\\n" );
      List<String> l = new ArrayList<String>();
      for ( int i = Math.max( 0, ls.length - n ); i < ls.length; i++ ) {
        l.add( ls[ i ] );
      }
      return String.join( "\n", l ).trim();
    } catch ( IOException e ) {
      return "";
    }
  }

  static void borrar( File f ) {
    try {
      CorrectorSandbox.borrar( f );
    } catch ( Throwable ignored ) {
      // queda en el temporal del sistema
    }
  }
}
