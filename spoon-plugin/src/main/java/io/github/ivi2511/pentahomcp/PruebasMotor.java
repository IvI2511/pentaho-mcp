package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.RowProducer;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepPartitioningMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;
import org.pentaho.di.trans.steps.injector.InjectorMeta;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * El motor de las pruebas de transformaciones (0.7.0, P1), en la JVM de la prueba ({@link PruebasMain}; nunca en la
 * de Spoon). Reusa el corrector: el bucle de corrida de {@link CorrectorMotor} (con sus helpers de RunMotor), su
 * RowListener ({@link CorrectorMotor.Escucha}), la comparacion fila a fila ({@link CorrectorComparar}), las tablas
 * ({@link CorrectorTabla}) y las bases de prueba ({@link CorrectorBases.Sesion}: recrear con scripts, leer tablas).
 * <p>
 * <b>Una prueba</b> ({@link #correr}): carga la .ktr (o la copia del documento abierto, con el nombre de archivo del
 * original: {@code Internal.Entry.Current.Directory} es la carpeta de verdad), deja solo lo que llega a las salidas
 * capturadas ({@link RunMotor#recortar}, como una vista previa), cambia cada step con dataset por un Injector con su
 * nombre (sin sus saltos de entrada: sus filas salen del CSV, con el RowMeta del campo original), aplica los ajustes
 * (skip_step = Dummy, remove_step, variables y parametros), apunta las conexiones a las bases de prueba, corre con
 * plazo, captura cada salida y la compara con su CSV dorado. El archivo de la transformacion nunca se toca.
 * <p>
 * <b>Crear</b> ({@link #crear}, modo snapshot): una corrida «en vivo» (con los steps que escriben saltados) captura las
 * entradas elegidas y las salidas; las entradas se guardan como datasets, otra corrida con los datasets inyectados
 * da los dorados, se avisan las columnas que no se reproducen (fechas del sistema, aleatorios: pasan a
 * {@code ignore_columns}) y la prueba se corre una vez para confirmar que pasa.
 *
 * @dueño P1 (0.7.0)
 */
final class PruebasMotor {

  /** Tipos de step que se dejan correr al crear aunque no tengan entrada (son fijos: no hace falta un dataset). */
  static final Set<String> FUENTES_FIJAS = new HashSet<String>( java.util.Arrays.asList( "DataGrid", "RowGenerator",
    "Injector", "Dummy" ) );
  /** Cada cuanto se mira si la corrida termino, vencio o se cancelo. */
  static final long MS_PASO = 20;

  private PruebasMotor() {
  }

  /** Como correr (lo que no esta en la prueba). */
  static final class Opciones {
    /** La .ktr que corre en lugar de la del disco (la copia del documento abierto); null = la del disco. */
    File ktr;
    /** Contraseñas de las bases de prueba por conexion (sin mayusculas), solo en memoria. */
    final Map<String, String> claves = new LinkedHashMap<String, String>();
    /** Sufijo de las bases de prueba (vacio = sin sufijo). */
    String sufijoBase;
    BooleanSupplier cancelada = () -> false;
    String versionPlugin;
  }

  // ================================================================ cargar

  /** Lee y completa una prueba; ToolError si no es una prueba valida. */
  static DefPrueba cargar( File f ) throws ToolError {
    DefPrueba d;
    try {
      d = CorrectorJson.leer( f, DefPrueba.class );
    } catch ( Exception e ) {
      throw new ToolError( "Invalid test file " + f.getName() + ": " + RunMotor.primeraLinea( e ) );
    }
    if ( d == null ) {
      throw new ToolError( "Empty test file " + f.getName() );
    }
    if ( d.nombre == null || d.nombre.trim().isEmpty() ) {
      d.nombre = nombreDeArchivo( f );
    }
    d.completar();
    List<String> mal = problemas( d );
    if ( !mal.isEmpty() ) {
      throw new ToolError( "Invalid test " + f.getName() + ": " + String.join( "; ", mal ) );
    }
    return d;
  }

  /** "kpi.test.json" → "kpi". */
  static String nombreDeArchivo( File f ) {
    String n = f.getName();
    return n.toLowerCase( Locale.ROOT ).endsWith( DefPrueba.EXTENSION ) ? n.substring( 0, n.length()
      - DefPrueba.EXTENSION.length() ) : n;
  }

  /** Lo que hace invalida una prueba (vacio = valida). */
  static List<String> problemas( DefPrueba d ) {
    List<String> l = new ArrayList<String>();
    if ( !DefPrueba.FORMATO.equals( d.formato ) ) {
      l.add( "format must be " + DefPrueba.FORMATO + " (is " + d.formato + ")" );
    }
    if ( vacio( d.transformacion ) && !d.esJob() ) {
      l.add( "'transformation' is missing" );
    }
    if ( !vacio( d.transformacion ) && d.esJob() ) {
      l.add( "a test has 'transformation' or 'job', not both" );
    }
    if ( d.salidas.isEmpty() && d.tablas.isEmpty() && d.archivos.isEmpty() ) {
      l.add( "nothing to compare: 'outputs', 'tables' and 'files' are empty" );
    }
    for ( DefPrueba.Salida s : d.salidas ) {
      if ( s == null || vacio( s.step ) ) {
        l.add( "an output without 'step'" );
      } else if ( d.esJob() && vacio( s.entrada ) ) {
        l.add( "the output '" + s.step + "' needs 'job_entry' (the Transformation entry of the job that runs it)" );
      }
    }
    for ( DefPrueba.Dataset ds : d.datasets ) {
      if ( ds == null || vacio( ds.step ) || vacio( ds.archivo ) ) {
        l.add( "a dataset without 'step' or 'file'" );
      } else if ( d.esJob() && vacio( ds.entrada ) ) {
        l.add( "the dataset of '" + ds.step + "' needs 'job_entry'" );
      }
    }
    for ( DefPrueba.ArchivoSalida a : d.archivos ) {
      if ( a == null || vacio( a.ruta ) ) {
        l.add( "a file without 'path'" );
      }
    }
    for ( DefPrueba.Ajuste a : d.ajustes ) {
      if ( a == null || !DefPrueba.Ajuste.TIPOS.contains( a.tipo ) ) {
        l.add( "unknown tweak type " + ( a == null ? null : a.tipo ) + " (use " + DefPrueba.Ajuste.TIPOS + ")" );
      } else if ( a.deStep() && vacio( a.step ) ) {
        l.add( a.tipo + " without 'step'" );
      } else if ( a.deStep() && d.esJob() && vacio( a.entrada ) ) {
        l.add( a.tipo + " '" + a.step + "' needs 'job_entry'" );
      } else if ( a.deEntrada() && ( vacio( a.entrada ) || !d.esJob() ) ) {
        l.add( a.tipo + ( d.esJob() ? " without 'job_entry'" : " is only for job tests" ) );
      } else if ( !a.deStep() && !a.deEntrada() && vacio( a.nombre ) ) {
        l.add( a.tipo + " without 'name'" );
      }
    }
    if ( !d.tablas.isEmpty() && d.bases.isEmpty() ) {
      l.add( "'tables' needs 'test_databases'" );
    }
    for ( TablaSalida t : d.tablas ) {
      if ( t == null || vacio( t.nombre ) ) {
        l.add( "a table without 'name'" );
      }
    }
    return l;
  }

  /** Una ruta de la prueba (relativa a su carpeta, o absoluta). */
  static File resolver( File dir, String ruta ) {
    File f = new File( ruta.trim().replace( '\\', '/' ).replace( '/', File.separatorChar ) );
    if ( !f.isAbsolute() ) {
      f = new File( dir, f.getPath() );
    }
    try {
      return f.getCanonicalFile();
    } catch ( IOException e ) {
      return f.getAbsoluteFile();
    }
  }

  /**
   * Escribe una prueba sin el ruido de los valores por defecto del caso del pack (0.7.1): {@code weight: 1.0} y
   * {@code hidden: false} no dicen nada en una prueba (el formato del pack los sigue leyendo y escribiendo igual).
   */
  static void guardar( DefPrueba d, File f ) throws IOException {
    com.fasterxml.jackson.databind.node.ObjectNode n = CorrectorJson.mapper().valueToTree( d );
    com.fasterxml.jackson.databind.JsonNode caso = n.get( "case" );
    if ( caso instanceof com.fasterxml.jackson.databind.node.ObjectNode ) {
      com.fasterxml.jackson.databind.node.ObjectNode c = (com.fasterxml.jackson.databind.node.ObjectNode) caso;
      if ( c.has( "weight" ) && c.get( "weight" ).asDouble() == 1.0 ) {
        c.remove( "weight" );
      }
      if ( c.has( "hidden" ) && !c.get( "hidden" ).asBoolean() ) {
        c.remove( "hidden" );
      }
    }
    CorrectorJson.guardar( n, f );
  }

  /** El CSV dorado de un archivo: {@code <expected>/<file>} o {@code <expected>/file_<nombre limpio>.csv}. */
  static File dorado( File dir, DefPrueba d, DefPrueba.ArchivoSalida a ) {
    File esperado = resolver( dir, d.caso.esperado );
    return new File( esperado, vacio( a.archivo ) ? "file_" + DefPrueba.archivoDe( a.nombre() ) + ".csv" : a.archivo );
  }

  /** El CSV dorado de una salida: {@code <expected>/<file>} o {@code <expected>/<step limpio>.csv}. */
  static File dorado( File dir, DefPrueba d, DefPrueba.Salida s ) {
    File esperado = resolver( dir, d.caso.esperado );
    return new File( esperado, vacio( s.archivo ) ? DefPrueba.archivoDe( vacio( s.entrada ) ? s.step : s.entrada + "_"
      + s.step ) + ".csv" : s.archivo );
  }

  // ================================================================ correr

  /** Corre una prueba en esta JVM (Kettle ya iniciado). Nunca lanza: un problema queda como {@code error}. */
  static ResultadoPrueba correr( File archivo, Opciones op ) {
    long t0 = System.currentTimeMillis();
    ResultadoPrueba r = new ResultadoPrueba();
    r.archivoPrueba = archivo.getAbsolutePath();
    r.prueba = nombreDeArchivo( archivo );
    r.versionPlugin = op.versionPlugin;
    try {
      DefPrueba d = cargar( archivo );
      r.prueba = d.nombre;
      correr( d, archivo.getAbsoluteFile().getParentFile(), op, r );
    } catch ( ToolError e ) {
      r.estado = ResultadoPrueba.ERROR;
      r.mensaje = e.getMessage();
    } catch ( Throwable t ) {
      t.printStackTrace( System.out );
      r.estado = ResultadoPrueba.ERROR;
      r.mensaje = "The test runner failed: " + RunMotor.primeraLinea( t );
    }
    r.segundos = Math.round( ( System.currentTimeMillis() - t0 ) / 10.0 ) / 100.0;
    r.terminadaEl = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT ).format( new Date() );
    if ( !op.claves.isEmpty() ) {
      tapar( r, op.claves.values() );
    }
    return r;
  }

  private static void correr( DefPrueba d, File dir, Opciones op, ResultadoPrueba r ) throws Exception {
    if ( d.esJob() ) {
      PruebasJob.correr( d, dir, op, r );
      return;
    }
    File original = resolver( dir, d.transformacion );
    r.transformacion = original.getPath();
    File ktr = op.ktr != null ? op.ktr : original;
    r.origen = op.ktr != null ? ResultadoPrueba.DEL_DOCUMENTO : ResultadoPrueba.DEL_ARCHIVO;
    if ( !ktr.isFile() ) {
      throw new ToolError( "The transformation " + original + " does not exist" );
    }
    ResultadoCaso rc = new ResultadoCaso();
    rc.id = d.caso.id;
    rc.tablas = new ArrayList<ResultadoCaso.ComparacionTabla>();
    r.caso = rc;

    Preparada p = preparar( d, dir, ktr, original, nombres( d.salidas ), true, op, r.notas );
    for ( String falta : p.faltan ) {
      ResultadoPrueba.Resumen s = new ResultadoPrueba.Resumen( falta, ResultadoPrueba.Resumen.STEP );
      s.problema = ResultadoPrueba.Resumen.SIN_STEP;
      r.salidas.add( s );
    }
    if ( p.reales.isEmpty() && d.tablas.isEmpty() && d.archivos.isEmpty() ) {
      r.estado = ResultadoPrueba.FALLO;
      r.mensaje = "None of the captured steps is in the transformation any more: " + p.faltan;
      rc.corrida.estado = ResultadoCaso.SIN_STEP_DE_SALIDA;
      return;
    }
    try {
      ejecutar( p, d, op, rc.corrida, rc );
    } finally {
      p.cerrar();
    }
    if ( op.cancelada.getAsBoolean() ) {
      r.estado = ResultadoPrueba.CANCELADA;
      r.mensaje = "Cancelled";
      return;
    }
    final Preparada pp = p;
    comparar( d, dir, r, rc, rc.corrioBien(), "transformation", new Capturas() {
      @Override
      public String nombre( DefPrueba.Salida s ) {
        return pp.faltan.contains( s.step ) ? null : pp.real( s.step );
      }

      @Override
      public CorrectorTabla tabla( DefPrueba.Salida s ) {
        return pp.capturada( s.step );
      }

      @Override
      public CorrectorBases.Captura tabla( TablaSalida t ) {
        return pp.tablas.get( t.clave() );
      }

      @Override
      public ArchivoLeido archivo( DefPrueba.ArchivoSalida a ) {
        return pp.archivos.get( a );
      }
    } );
  }

  /** Lo que la corrida dejo para comparar (una transformacion o un job). */
  interface Capturas {
    /** El nombre real de la salida (para el resultado), o null si el step no esta (ya se informo). */
    String nombre( DefPrueba.Salida s );

    CorrectorTabla tabla( DefPrueba.Salida s );

    CorrectorBases.Captura tabla( TablaSalida t );

    /** El archivo leido despues de correr, o null si no se llego a leer. */
    ArchivoLeido archivo( DefPrueba.ArchivoSalida a );
  }

  /** Un archivo de {@code files[]} despues de correr: sus filas, o por que no se puede comparar. */
  static final class ArchivoLeido {
    /** La ruta resuelta. */
    File archivo;
    CorrectorTabla tabla;
    /** Por que no (no existe, no lo escribio la corrida, no se pudo leer), en ingles; null = se leyo. */
    String problema;
  }

  /** Antes de correr: la ruta resuelta de cada archivo y su estado (fecha y tamaño; null = no existe). */
  static Map<DefPrueba.ArchivoSalida, Object[]> estadoArchivos( DefPrueba d, File dir,
                                                                 org.pentaho.di.core.variables.VariableSpace vars ) {
    Map<DefPrueba.ArchivoSalida, Object[]> m = new LinkedHashMap<DefPrueba.ArchivoSalida, Object[]>();
    for ( DefPrueba.ArchivoSalida a : d.archivos ) {
      File f = rutaArchivo( dir, a, vars );
      m.put( a, new Object[] { f, f.isFile() ? new long[] { f.lastModified(), f.length() } : null } );
    }
    return m;
  }

  /** La ruta de un archivo de files[] con las variables de la corrida; relativa, a la carpeta de la prueba. */
  static File rutaArchivo( File dir, DefPrueba.ArchivoSalida a, org.pentaho.di.core.variables.VariableSpace vars ) {
    String r = a.ruta == null ? "" : a.ruta.trim();
    if ( vars != null ) {
      r = vars.environmentSubstitute( r );
    }
    if ( r.regionMatches( true, 0, "file:", 0, 5 ) ) { // ${Internal.Entry.Current.Directory} es una URL (%20...)
      File f = CorrectorSandbox.aArchivo( r );
      return f.isAbsolute() ? f.getAbsoluteFile() : resolver( dir, f.getPath() );
    }
    return resolver( dir, r );
  }

  /** Despues de correr: cada archivo leido como CSV, si la corrida lo escribio (existe y cambio). */
  static Map<DefPrueba.ArchivoSalida, ArchivoLeido> leerArchivos( DefPrueba d, Map<DefPrueba.ArchivoSalida,
    Object[]> antes ) {
    Map<DefPrueba.ArchivoSalida, ArchivoLeido> m = new LinkedHashMap<DefPrueba.ArchivoSalida, ArchivoLeido>();
    for ( DefPrueba.ArchivoSalida a : d.archivos ) {
      Object[] x = antes.get( a );
      if ( x == null ) {
        continue;
      }
      ArchivoLeido l = new ArchivoLeido();
      l.archivo = (File) x[ 0 ];
      long[] previo = (long[]) x[ 1 ];
      if ( !l.archivo.isFile() ) {
        l.problema = "the run did not write " + l.archivo;
      } else if ( previo != null && previo[ 0 ] == l.archivo.lastModified() && previo[ 1 ] == l.archivo.length() ) {
        l.problema = "the run did not change " + l.archivo + " (an old file is never compared)";
      } else {
        try {
          if ( a.csv == null ) {
            a.csv = csvDe( l.archivo ); // al crear: el separador del archivo queda en la prueba
          }
          l.tabla = CorrectorTabla.esperada( l.archivo, a.csv, nombresColumnas( a.columnas ) );
        } catch ( Exception e ) {
          l.problema = "cannot read " + l.archivo + ": " + RunMotor.primeraLinea( e );
        }
      }
      m.put( a, l );
    }
    return m;
  }

  /**
   * Como esta escrito un archivo de files[] sin {@code csv}: el separador mas frecuente de su primera linea (fuera de
   * comillas) entre {@code ; , | tab}; comillas dobles, encabezado y UTF-8.
   */
  static Pack.FormatoCsv csvDe( File f ) {
    Pack.FormatoCsv c = DefPrueba.csvPorDefecto();
    String linea = "";
    try ( java.io.BufferedReader r = new java.io.BufferedReader( new java.io.InputStreamReader(
      new java.io.FileInputStream( f ), java.nio.charset.StandardCharsets.UTF_8 ) ) ) {
      String x = r.readLine();
      linea = x == null ? "" : x;
    } catch ( IOException ignored ) {
      return c;
    }
    char[] candidatos = { ';', ',', '|', '\t' };
    int mejor = 0;
    boolean dentro = false;
    int[] n = new int[ candidatos.length ];
    for ( char ch : linea.toCharArray() ) {
      if ( ch == '"' ) {
        dentro = !dentro;
      }
      for ( int i = 0; i < candidatos.length && !dentro; i++ ) {
        if ( ch == candidatos[ i ] ) {
          n[ i ]++;
        }
      }
    }
    for ( int i = 0; i < candidatos.length; i++ ) {
      if ( n[ i ] > n[ mejor ] ) {
        mejor = i;
      }
    }
    if ( n[ mejor ] > 0 ) {
      c.separador = String.valueOf( candidatos[ mejor ] );
    }
    return c;
  }

  /**
   * Compara lo capturado con los dorados (salidas de steps, tablas y archivos), arma el resumen y da el veredicto.
   * {@code que}: "transformation" o "job" (para el mensaje de una corrida que fallo).
   */
  static void comparar( DefPrueba d, File dir, ResultadoPrueba r, ResultadoCaso rc, boolean corrio, String que,
                        Capturas cap ) throws Exception {
    for ( DefPrueba.Salida s : d.salidas ) {
      String nombre = cap.nombre( s );
      if ( nombre == null ) {
        continue;
      }
      ResultadoPrueba.Resumen x = new ResultadoPrueba.Resumen( nombre, ResultadoPrueba.Resumen.STEP );
      File dorado = dorado( dir, d, s );
      x.dorado = relativa( dir, dorado );
      r.salidas.add( x );
      if ( !corrio ) {
        x.problema = ResultadoPrueba.Resumen.SIN_CORRIDA;
        continue;
      }
      if ( !dorado.isFile() ) {
        x.problema = ResultadoPrueba.Resumen.SIN_DORADO;
        continue;
      }
      CorrectorTabla real = PruebasDatasets.sin( cap.tabla( s ), s );
      CorrectorTabla esp = PruebasDatasets.sin( CorrectorTabla.esperada( dorado, d.csvDorado, nombresColumnas(
        s.columnas ) ), s );
      List<Aviso> avisos = new ArrayList<Aviso>();
      Comparacion c = CorrectorComparar.comparar( esp, real, s.columnas, s.comparar, d.entorno.politica()
        .localeJava(), avisos );
      for ( Aviso a : avisos ) {
        a.step = x.nombre;
        rc.avisos.add( a );
      }
      rc.tablas.add( new ResultadoCaso.ComparacionTabla( x.nombre, null, c ) );
      resumir( x, c, s.comparar );
    }
    for ( TablaSalida t : d.tablas ) {
      ResultadoPrueba.Resumen x = new ResultadoPrueba.Resumen( t.nombre, ResultadoPrueba.Resumen.TABLA );
      File dorado = new File( resolver( dir, d.caso.esperado ), t.archivo() );
      x.dorado = relativa( dir, dorado );
      r.salidas.add( x );
      CorrectorBases.Captura ct = cap.tabla( t );
      if ( !corrio || ct == null ) {
        x.problema = ResultadoPrueba.Resumen.SIN_CORRIDA;
        continue;
      }
      if ( !dorado.isFile() ) {
        x.problema = ResultadoPrueba.Resumen.SIN_DORADO;
        continue;
      }
      if ( ct.falta != null ) {
        rc.avisos.add( new Aviso( CorrectorMotor.AVISO_SIN_TABLA, t.nombre + ": " + ct.falta ) );
      }
      CorrectorTabla esp = CorrectorTabla.esperada( dorado, d.csvDorado, nombresColumnas( t.columnas ) );
      OpcionesComparar oc = DefPrueba.compararPorDefecto();
      oc.columnasClave = new ArrayList<String>( t.columnasClave );
      List<Aviso> avisos = new ArrayList<Aviso>();
      Comparacion c = CorrectorComparar.comparar( esp, ct.tabla, t.columnas, oc, d.entorno.politica().localeJava(),
        avisos );
      for ( Aviso a : avisos ) {
        a.detalle = t.nombre + ": " + ( a.detalle == null ? "" : a.detalle );
        rc.avisos.add( a );
      }
      rc.tablas.add( new ResultadoCaso.ComparacionTabla( t.nombre, t.conexion, c ) );
      resumir( x, c, oc );
    }
    for ( DefPrueba.ArchivoSalida a : d.archivos ) {
      ResultadoPrueba.Resumen x = new ResultadoPrueba.Resumen( a.nombre(), ResultadoPrueba.Resumen.ARCHIVO );
      File dorado = dorado( dir, d, a );
      x.dorado = relativa( dir, dorado );
      r.salidas.add( x );
      ArchivoLeido l = cap.archivo( a );
      if ( !corrio || l == null ) {
        x.problema = ResultadoPrueba.Resumen.SIN_CORRIDA;
        continue;
      }
      if ( !dorado.isFile() ) {
        x.problema = ResultadoPrueba.Resumen.SIN_DORADO;
        continue;
      }
      if ( l.problema != null ) {
        x.problema = ResultadoPrueba.Resumen.SIN_ESCRIBIR;
        rc.avisos.add( new Aviso( CorrectorMotor.AVISO_SIN_TABLA, a.nombre() + ": " + l.problema ) );
        continue;
      }
      DefPrueba.Salida comoSalida = a.comoSalida();
      CorrectorTabla real = PruebasDatasets.sin( l.tabla, comoSalida );
      CorrectorTabla esp = PruebasDatasets.sin( CorrectorTabla.esperada( dorado, d.csvDorado, nombresColumnas(
        a.columnas ) ), comoSalida );
      List<Aviso> avisos = new ArrayList<Aviso>();
      Comparacion c = CorrectorComparar.comparar( esp, real, a.columnas, a.comparar, d.entorno.politica()
        .localeJava(), avisos );
      for ( Aviso av : avisos ) {
        av.detalle = a.nombre() + ": " + ( av.detalle == null ? "" : av.detalle );
        rc.avisos.add( av );
      }
      rc.tablas.add( new ResultadoCaso.ComparacionTabla( a.nombre(), null, c ) );
      resumir( x, c, a.comparar );
    }
    if ( !rc.tablas.isEmpty() ) {
      rc.comparacion = CorrectorComparar.juntar( rc.tablas );
    }
    veredicto( r, rc, corrio, que );
  }

  /** passed / failed y el mensaje de por que. */
  private static void veredicto( ResultadoPrueba r, ResultadoCaso rc, boolean corrio, String que ) {
    if ( !corrio ) {
      r.estado = ResultadoPrueba.FALLO;
      String det = rc.corrida.detalle == null ? "" : ": " + rc.corrida.detalle;
      r.mensaje = "The " + que + " did not finish cleanly (" + rc.corrida.estado + ( rc.corrida.errores != null
        && rc.corrida.errores > 0 ? ", " + rc.corrida.errores + " errors" : "" ) + ")" + det;
      return;
    }
    List<String> mal = new ArrayList<String>();
    for ( ResultadoPrueba.Resumen s : r.salidas ) {
      if ( s.paso ) {
        continue;
      }
      if ( ResultadoPrueba.Resumen.SIN_STEP.equals( s.problema ) ) {
        mal.add( "'" + s.nombre + "' is no longer in the transformation" );
      } else if ( ResultadoPrueba.Resumen.SIN_DORADO.equals( s.problema ) ) {
        mal.add( "'" + s.nombre + "' has no golden file (" + s.dorado + ")" );
      } else if ( ResultadoPrueba.Resumen.SIN_ESCRIBIR.equals( s.problema ) ) {
        mal.add( "the file '" + s.nombre + "' was not written by the run" );
      } else {
        List<String> q = new ArrayList<String>();
        if ( s.columnasFaltan != null && !s.columnasFaltan.isEmpty() ) {
          q.add( "missing columns " + s.columnasFaltan );
        }
        if ( s.columnasSobran != null && !s.columnasSobran.isEmpty() ) {
          q.add( "extra columns " + s.columnasSobran );
        }
        if ( s.distintas != null && s.distintas > 0 ) {
          q.add( s.distintas + " changed rows" );
        }
        if ( s.faltan != null && s.faltan > 0 ) {
          q.add( s.faltan + " missing rows" );
        }
        if ( s.sobran != null && s.sobran > 0 ) {
          q.add( s.sobran + " extra rows" );
        }
        if ( s.clavesRepetidas != null && s.clavesRepetidas > 0 ) {
          q.add( s.clavesRepetidas + " duplicate keys" );
        }
        mal.add( "'" + s.nombre + "' " + ( q.isEmpty() ? "differs" : String.join( ", ", q ) ) );
      }
    }
    if ( mal.isEmpty() ) {
      r.estado = ResultadoPrueba.PASO;
      int filas = 0;
      for ( ResultadoPrueba.Resumen s : r.salidas ) {
        filas += s.filasReales == null ? 0 : s.filasReales;
      }
      r.mensaje = r.salidas.size() + ( r.salidas.size() == 1 ? " output matches" : " outputs match" )
        + " the golden data (" + filas + " rows)";
    } else {
      r.estado = ResultadoPrueba.FALLO;
      r.mensaje = mal.size() + " of " + r.salidas.size() + " outputs differ: " + String.join( "; ", mal );
    }
  }

  /** El resumen de una comparacion y si paso: columnas, filas y valores identicos. */
  static void resumir( ResultadoPrueba.Resumen x, Comparacion c, OpcionesComparar oc ) {
    x.filasEsperadas = c.filas.esperadas;
    x.filasReales = c.filas.reales;
    x.iguales = c.filas.iguales;
    x.distintas = c.filas.distintas;
    x.faltan = c.filas.faltan;
    x.sobran = c.filas.sobran;
    x.clavesRepetidas = c.filas.clavesRepetidas;
    x.columnasFaltan = c.columnas.faltan.isEmpty() ? null : new ArrayList<String>( c.columnas.faltan );
    boolean extrasFallan = oc == null || !OpcionesComparar.EXTRA_AVISAR.equals( oc.columnasDeMas );
    x.columnasSobran = c.columnas.sobran.isEmpty() ? null : new ArrayList<String>( c.columnas.sobran );
    x.paso = c.columnas.faltan.isEmpty() && ( !extrasFallan || c.columnas.sobran.isEmpty() ) && c.filas.distintas == 0
      && c.filas.faltan == 0 && c.filas.sobran == 0 && c.filas.clavesRepetidas == 0 && c.filas.iguales
      == c.filas.esperadas && c.filas.reales == c.filas.esperadas;
  }

  // ================================================================ preparar la copia

  /** La copia lista para correr y lo que se capturo. */
  static final class Preparada {
    TransMeta tm;
    /** Nombre pedido (sin mayusculas) → nombre real del step capturado. */
    final Map<String, String> reales = new LinkedHashMap<String, String>();
    /** Steps capturados (nombre real) → su escucha (de la ultima corrida). */
    final Map<String, CorrectorMotor.Escucha> escuchas = new LinkedHashMap<String, CorrectorMotor.Escucha>();
    /** Steps con dataset (nombre real) → sus filas. */
    final Map<String, PruebasDatasets.Filas> inyectar = new LinkedHashMap<String, PruebasDatasets.Filas>();
    final Map<String, String> variables = new LinkedHashMap<String, String>();
    /** Los pedidos que no estan en la transformacion. */
    final List<String> faltan = new ArrayList<String>();
    CorrectorBases.Sesion bases;
    /** Tablas leidas despues de correr (clave de TablaSalida). */
    final Map<String, CorrectorBases.Captura> tablas = new LinkedHashMap<String, CorrectorBases.Captura>();
    List<TablaSalida> tablasPedidas = new ArrayList<TablaSalida>();
    /** La carpeta de la prueba (rutas de files[]). */
    File dir;
    DefPrueba def;
    /** Los archivos de files[] leidos despues de la ultima corrida. */
    final Map<DefPrueba.ArchivoSalida, ArchivoLeido> archivos = new LinkedHashMap<DefPrueba.ArchivoSalida,
      ArchivoLeido>();
    long timeoutMs;
    long maxFilas;
    int veces = 1;

    String real( String pedido ) {
      String x = reales.get( clave( pedido ) );
      return x == null ? pedido : x;
    }

    CorrectorTabla capturada( String pedido ) {
      String n = real( pedido );
      CorrectorMotor.Escucha e = escuchas.get( n );
      StepMeta sm = tm.findStep( n );
      if ( e == null || sm == null ) {
        return CorrectorTabla.vacia( new ArrayList<String>() );
      }
      return e.tabla( sm, tm );
    }

    void cerrar() {
      // nada abierto entre corridas: Sesion conecta y desconecta en cada operacion
    }
  }

  /**
   * Arma la copia que corre: carga, recorta a lo que llega a las capturas, inyecta los datasets (si conDatasets),
   * aplica los ajustes y apunta las conexiones a las bases de prueba. Las notas van en ingles.
   *
   * @param capturar los steps cuya salida se captura (los que no estan quedan en {@link Preparada#faltan})
   */
  static Preparada preparar( DefPrueba d, File dir, File ktr, File original, List<String> capturar,
                             boolean conDatasets, Opciones op, List<String> notas ) throws Exception {
    Preparada p = new Preparada();
    TransMeta tm;
    try {
      tm = new TransMeta( ktr.getAbsolutePath() );
    } catch ( Exception e ) {
      throw new ToolError( "Cannot load " + original.getName() + ": " + RunMotor.primeraLinea( e ) );
    }
    if ( !ktr.equals( original ) ) {
      tm.setFilename( original.getAbsolutePath() ); // Internal.Entry.Current.Directory = la carpeta de verdad
    }
    tm.setInternalKettleVariables();
    p.tm = tm;
    p.dir = dir;
    p.def = d;
    p.timeoutMs = Math.max( 1, d.entorno.timeoutSeg ) * 1000L;
    p.maxFilas = d.entorno.maxFilas <= 0 ? Long.MAX_VALUE : d.entorno.maxFilas;
    p.veces = d.caso == null ? 1 : d.caso.vecesQueCorre();
    p.tablasPedidas = d.tablas;

    // lo que se captura
    List<StepMeta> objetivos = new ArrayList<StepMeta>();
    for ( String n : capturar ) {
      StepMeta sm = buscar( tm, n );
      if ( sm == null ) {
        p.faltan.add( n );
      } else {
        p.reales.put( clave( n ), sm.getName() );
        if ( !objetivos.contains( sm ) ) {
          objetivos.add( sm );
        }
      }
    }
    // pasos de datasets y ajustes que no estan
    List<DefPrueba.Dataset> datasets = conDatasets ? d.datasets : new ArrayList<DefPrueba.Dataset>();
    for ( DefPrueba.Dataset ds : datasets ) {
      if ( buscar( tm, ds.step ) == null ) {
        throw new ToolError( "The step '" + ds.step + "' of the dataset " + ds.archivo + " is not in "
          + original.getName() );
      }
    }
    // solo lo que llega a las capturas (como una vista previa)
    if ( !d.todaLaTransformacion && d.tablas.isEmpty() && d.archivos.isEmpty() && !objetivos.isEmpty() ) {
      int antes = tm.nrSteps();
      Set<String> nombresAntes = nombresSteps( tm );
      RunMotor.recortar( tm, objetivos );
      Set<String> quitados = new LinkedHashSet<String>( nombresAntes );
      quitados.removeAll( nombresSteps( tm ) );
      if ( !quitados.isEmpty() || antes != tm.nrSteps() ) {
        notas.add( quitados.size() + " steps that do not lead to the captured outputs do not run"
          + ( quitados.isEmpty() ? "" : ": " + limitar( quitados, 8 ) ) );
      }
    }
    // datasets: el step pasa a ser un Injector con su nombre
    for ( DefPrueba.Dataset ds : datasets ) {
      StepMeta sm = buscar( tm, ds.step );
      if ( sm == null ) {
        notas.add( "dataset of '" + ds.step + "' not used: the step does not lead to the captured outputs" );
        continue;
      }
      File f = resolver( dir, ds.archivo );
      if ( !f.isFile() ) {
        throw new ToolError( "The dataset file " + ds.archivo + " (step '" + ds.step + "') does not exist" );
      }
      PruebasDatasets.Filas filas = PruebasDatasets.leerDataset( f, ds.columnas, d.entorno.maxFilas );
      inyector( tm, sm, filas.meta, notas );
      p.inyectar.put( sm.getName(), filas );
      notas.add( "step '" + sm.getName() + "' replaced by the dataset " + ds.archivo + " (" + filas.filas.size()
        + " rows)" );
    }
    // ajustes
    for ( DefPrueba.Ajuste a : d.ajustes ) {
      if ( DefPrueba.Ajuste.VARIABLE.equals( a.tipo ) ) {
        p.variables.put( a.nombre.trim(), a.valor == null ? "" : a.valor );
        continue;
      }
      if ( DefPrueba.Ajuste.PARAMETRO.equals( a.tipo ) ) {
        boolean declarado = false;
        for ( String x : tm.listParameters() ) {
          declarado |= x.equals( a.nombre.trim() );
        }
        if ( declarado ) {
          tm.setParameterValue( a.nombre.trim(), a.valor == null ? "" : a.valor );
        } else {
          p.variables.put( a.nombre.trim(), a.valor == null ? "" : a.valor );
          notas.add( "parameter '" + a.nombre + "' is not declared by the transformation: set as a variable" );
        }
        continue;
      }
      if ( a.deStep() ) {
        ajustarStep( tm, a, objetivos, p.inyectar.keySet(), notas );
      }
    }
    for ( StepMeta s : tm.getSteps() ) {
      s.getStepMetaInterface().searchInfoAndTargetSteps( tm.getSteps() );
    }
    tm.clearCaches();
    // bases de prueba
    if ( !d.bases.isEmpty() ) {
      Pack pk = d.comoPack();
      try {
        p.bases = new CorrectorBases.Sesion( pk, dir, op.sufijoBase, op.claves );
      } catch ( CorrectorBases.Falla f ) {
        throw new ToolError( f.getMessage() );
      }
      Redireccion red = redirigir( tm.getDatabases(), usadas( tm ), pk, p.bases, notas );
      esquemas( tm, p.bases, red, variablesDe( tm, p.variables ), notas );
    } else {
      avisarSinBases( tm, notas );
    }
    RunMotor.sinTablasDeLog( tm ); // una prueba no escribe en tablas de log
    return p;
  }

  /**
   * Aplica un skip_step o remove_step a la copia que corre. {@code objetivos}: las salidas capturadas (no se pueden
   * quitar); {@code inyectados}: los steps cambiados por su dataset (el ajuste no se aplica).
   */
  static void ajustarStep( TransMeta tm, DefPrueba.Ajuste a, java.util.Collection<StepMeta> objetivos,
                           Set<String> inyectados, List<String> notas ) throws ToolError {
    StepMeta sm = buscar( tm, a.step );
    if ( sm == null ) {
      notas.add( a.tipo + " '" + a.step + "' ignored: the step is not in the copy that runs" );
      return;
    }
    if ( inyectados.contains( sm.getName() ) ) {
      notas.add( a.tipo + " '" + a.step + "' ignored: the step is replaced by its dataset" );
      return;
    }
    if ( DefPrueba.Ajuste.SALTAR.equals( a.tipo ) ) {
      dummy( tm, sm );
      notas.add( "step '" + sm.getName() + "' skipped (rows pass through, nothing is written)" );
      return;
    }
    if ( objetivos.contains( sm ) ) {
      throw new ToolError( "remove_step '" + a.step + "': it is a captured output" );
    }
    boolean destinoDeErrores = false;
    for ( StepMeta x : tm.getSteps() ) {
      destinoDeErrores |= x.getStepErrorMeta() != null && x.getStepErrorMeta().isEnabled() && x.getStepErrorMeta()
        .getTargetStep() == sm;
    }
    if ( destinoDeErrores ) { // el manejo de errores sigue: las filas con error terminan aca y no siguen
      dummy( tm, sm );
      for ( int i = tm.nrTransHops() - 1; i >= 0; i-- ) {
        if ( tm.getTransHop( i ).getFromStep() == sm ) {
          tm.removeTransHop( i );
        }
      }
      notas.add( "step '" + sm.getName() + "' removed: it receives error rows, so they end there" );
      return;
    }
    for ( int i = tm.nrTransHops() - 1; i >= 0; i-- ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() == sm || h.getToStep() == sm ) {
        tm.removeTransHop( i );
      }
    }
    tm.removeStep( tm.indexOfStep( sm ) );
    notas.add( "step '" + sm.getName() + "' removed with its hops" );
  }

  /** Cambia el step por un Injector con su nombre y los campos del dataset; sin sus saltos de entrada. */
  static void inyector( TransMeta tm, StepMeta sm, RowMetaInterface rm, List<String> notas ) {
    InjectorMeta im = new InjectorMeta();
    int n = rm.size();
    im.allocate( n );
    for ( int i = 0; i < n; i++ ) {
      ValueMetaInterface vm = rm.getValueMeta( i );
      im.getFieldname()[ i ] = vm.getName();
      im.getType()[ i ] = vm.getType();
      im.getLength()[ i ] = vm.getLength();
      im.getPrecision()[ i ] = vm.getPrecision();
    }
    im.setParentStepMeta( sm );
    sinSaltosDeError( tm, sm );
    sm.setStepID( "Injector" );
    sm.setStepMetaInterface( im );
    sm.setCopies( 1 );
    sm.setStepErrorMeta( null );
    sm.setStepPartitioningMeta( new StepPartitioningMeta() );
    sm.setClusterSchema( null );
    int quitados = 0;
    for ( int i = tm.nrTransHops() - 1; i >= 0; i-- ) {
      if ( tm.getTransHop( i ).getToStep() == sm ) {
        tm.removeTransHop( i );
        quitados++;
      }
    }
    if ( quitados > 0 && notas != null ) {
      notas.add( "the " + quitados + " hops into '" + sm.getName() + "' were removed (its rows come from the "
        + "dataset)" );
    }
  }

  /**
   * Quita el salto al destino de errores del step (si lo maneja): un Injector o un Dummy no saben de errores y
   * repartirian las filas buenas tambien por ese salto.
   */
  static void sinSaltosDeError( TransMeta tm, StepMeta sm ) {
    org.pentaho.di.trans.step.StepErrorMeta em = sm.getStepErrorMeta();
    if ( em == null || em.getTargetStep() == null ) {
      return;
    }
    for ( int i = tm.nrTransHops() - 1; i >= 0; i-- ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() == sm && h.getToStep() == em.getTargetStep() ) {
        tm.removeTransHop( i );
      }
    }
  }

  /** El step pasa a ser un Dummy con su nombre: las filas siguen y no se escribe nada. */
  static void dummy( TransMeta tm, StepMeta sm ) {
    sinSaltosDeError( tm, sm );
    DummyTransMeta dm = new DummyTransMeta();
    dm.setDefault();
    dm.setParentStepMeta( sm );
    sm.setStepID( "Dummy" );
    sm.setStepMetaInterface( dm );
    sm.setStepErrorMeta( null );
    sm.setStepPartitioningMeta( new StepPartitioningMeta() );
    sm.setClusterSchema( null );
  }

  /**
   * Apunta cada conexion que tiene base de prueba a ella (por nombre, por base o por tipo y servidor, como el
   * corrector): tipo, servidor, puerto, la base de prueba, usuario, opciones del pack y la contraseña en memoria.
   */
  static void redirigir( TransMeta tm, Pack pk, CorrectorBases.Sesion s, List<String> notas ) {
    redirigir( tm.getDatabases(), usadas( tm ), pk, s, notas );
  }

  /** Las variables de una copia con las de la prueba (para sustituir en esquemas y SQL). */
  static org.pentaho.di.core.variables.VariableSpace variablesDe( org.pentaho.di.core.variables.VariableSpace base,
                                                                  Map<String, String> extra ) {
    org.pentaho.di.core.variables.Variables v = new org.pentaho.di.core.variables.Variables();
    v.copyVariablesFrom( base );
    for ( Map.Entry<String, String> e : extra.entrySet() ) {
      v.setVariable( e.getKey(), e.getValue() );
    }
    return v;
  }

  /** Las conexiones que usan los steps (sin mayusculas). */
  static Set<String> usadas( TransMeta tm ) {
    Set<String> usadas = new HashSet<String>();
    for ( StepMeta sm : tm.getSteps() ) {
      try {
        for ( DatabaseMeta x : sm.getStepMetaInterface().getUsedDatabaseConnections() ) {
          if ( x != null && x.getName() != null ) {
            usadas.add( x.getName().toLowerCase( Locale.ROOT ) );
          }
        }
      } catch ( Throwable ignored ) {
        // un step sin conexiones
      }
    }
    return usadas;
  }

  /** Sin bases de prueba: anota las conexiones que usan los steps (la prueba usa las de verdad). */
  static void avisarSinBases( TransMeta tm, List<String> notas ) {
    Set<String> u = usadas( tm );
    for ( DatabaseMeta dm : tm.getDatabases() ) {
      if ( dm.getName() != null && u.contains( dm.getName().toLowerCase( Locale.ROOT ) ) ) {
        notas.add( "connection '" + dm.getName() + "' is the real one (the test has no test_databases)" );
      }
    }
  }

  /** Lo que dejo {@link #redirigir}: conexion (sin mayusculas) → su base de prueba, y base original → de prueba. */
  static final class Redireccion {
    final Map<String, CorrectorBases.Destino> conexiones = new LinkedHashMap<String, CorrectorBases.Destino>();
    final Map<String, String> originales = new LinkedHashMap<String, String>();
  }

  /**
   * Apunta cada conexion con base de prueba a ella (las de una transformacion o las de un job). Las usadas sin base de
   * prueba quedan con un WARNING en las notas.
   */
  static Redireccion redirigir( List<DatabaseMeta> conexiones, Set<String> usadas, Pack pk, CorrectorBases.Sesion s,
                                List<String> notas ) {
    Redireccion red = new Redireccion();
    Set<BaseDePrueba> emparejadas = new HashSet<BaseDePrueba>();
    for ( DatabaseMeta dm : conexiones ) {
      BaseDePrueba b = CorrectorBases.emparejar( pk, dm.getName(), dm.getPluginId(), dm.getHostname(), dm
        .getDatabaseName(), emparejadas, false );
      boolean usada = dm.getName() != null && usadas.contains( dm.getName().toLowerCase( Locale.ROOT ) );
      if ( b == null ) {
        if ( usada ) {
          notas.add( "WARNING: connection '" + dm.getName() + "' has no test database: the test uses the real "
            + "one" );
        }
        continue;
      }
      emparejadas.add( b );
      CorrectorBases.Destino dest = s.destino( b );
      Properties conservar = new Properties();
      for ( Map.Entry<Object, Object> e : dm.getAttributes().entrySet() ) {
        if ( CorrectorBases.ATRIBUTOS.contains( String.valueOf( e.getKey() ).toLowerCase( Locale.ROOT ) ) ) {
          conservar.put( e.getKey(), e.getValue() );
        }
      }
      String nombre = dm.getName();
      String antes = dm.getDatabaseName();
      DatabaseMeta nueva = s.meta( dest, dest.nombre );
      nueva.setName( nombre );
      nueva.getAttributes().putAll( conservar );
      dm.replaceMeta( nueva );
      dm.setName( nombre );
      red.conexiones.put( nombre == null ? "" : nombre.toLowerCase( Locale.ROOT ), dest );
      CorrectorBases.anotarOriginal( antes, dest, red.originales );
      notas.add( "connection '" + nombre + "' (" + antes + ") -> test database " + dest.describir() );
    }
    return red;
  }

  /**
   * (0.7.1) Los esquemas y el SQL de los steps que usan una conexion redirigida, como en el corrector
   * ({@link CorrectorSandbox#esquemasDeStep}): {@code schema} y {@code gym_dw.ventas} de la base original pasan a la
   * de prueba. Lo que saldria de las bases de prueba (otro esquema de MySQL, SQL que nombra otra base o lee archivos
   * del servidor) es un error: la prueba no corre.
   */
  static void esquemas( TransMeta tm, CorrectorBases.Sesion s, Redireccion red,
                        org.pentaho.di.core.variables.VariableSpace vars, List<String> notas ) throws ToolError {
    if ( red.conexiones.isEmpty() ) {
      return;
    }
    Set<String> pruebas = s.nombres();
    Set<String> prohibidas = s.prohibidas();
    Map<String, String> ren = s.renombres();
    for ( Map.Entry<String, String> o : red.originales.entrySet() ) {
      ren.putIfAbsent( o.getKey(), o.getValue() );
    }
    for ( StepMeta sm : tm.getSteps() ) {
      CorrectorBases.Destino d = null;
      try {
        for ( DatabaseMeta x : sm.getStepMetaInterface().getUsedDatabaseConnections() ) {
          if ( d == null && x != null && x.getName() != null ) {
            d = red.conexiones.get( x.getName().toLowerCase( Locale.ROOT ) );
          }
        }
      } catch ( Throwable ignored ) {
        // sin conexiones
      }
      if ( d == null ) {
        continue;
      }
      org.w3c.dom.Element e;
      try {
        e = io.github.ivi2511.pentahomcp.lint.Nodos.parse( sm.getXML() );
      } catch ( Exception ex ) {
        continue;
      }
      List<String> redir = new ArrayList<String>();
      List<CorrectorPolitica.Hallazgo> mal = new ArrayList<CorrectorPolitica.Hallazgo>();
      CorrectorSandbox.esquemasDeStep( e, sm.getName(), sm.getStepID(), d, vars, pruebas, prohibidas, ren, redir,
        mal );
      if ( !mal.isEmpty() ) {
        throw new ToolError( "The test does not run: " + mal.get( 0 ).detalle() + " (a test only reads and writes "
          + "its test databases)" );
      }
      if ( redir.isEmpty() ) {
        continue;
      }
      try {
        StepMeta nuevo = new StepMeta( e, tm.getDatabases(), (org.pentaho.metastore.api.IMetaStore) null );
        org.pentaho.di.trans.step.StepMetaInterface smi = nuevo.getStepMetaInterface();
        if ( smi instanceof org.pentaho.di.trans.step.BaseStepMeta ) {
          ( (org.pentaho.di.trans.step.BaseStepMeta) smi ).setParentStepMeta( sm );
        }
        sm.setStepMetaInterface( smi );
      } catch ( Exception ex ) {
        throw new ToolError( "Could not point the step '" + sm.getName() + "' to the test databases: "
          + RunMotor.primeraLinea( ex ) );
      }
      notas.addAll( redir );
    }
    for ( StepMeta x : tm.getSteps() ) {
      x.getStepMetaInterface().searchInfoAndTargetSteps( tm.getSteps() );
    }
  }

  // ================================================================ ejecutar

  /**
   * Corre la copia (las veces del caso), con los datasets inyectados y un RowListener en cada step capturado, y lee
   * las tablas pedidas al final. Deja la corrida en {@code run} y los avisos en {@code rc} (si no es null). Las bases
   * de prueba se recrean antes de la primera vez.
   */
  static void ejecutar( Preparada p, DefPrueba d, Opciones op, ResultadoCaso.Corrida run, ResultadoCaso rc )
    throws Exception {
    long inicio = System.currentTimeMillis();
    if ( p.bases != null ) {
      try {
        p.bases.recrear( d.caso );
      } catch ( CorrectorBases.Falla f ) {
        throw new ToolError( "Test database: " + f.getMessage() );
      }
      inicio = System.currentTimeMillis();
    }
    Trans t = null;
    String canal = null;
    Map<DefPrueba.ArchivoSalida, Object[]> antesArchivos = null;
    try {
      for ( int vez = 1; vez <= p.veces; vez++ ) {
        boolean ultima = vez == p.veces;
        if ( canal != null ) {
          RunMotor.liberarLog( canal );
        }
        long limite = System.currentTimeMillis() + p.timeoutMs;
        t = new Trans( p.tm, RunMotor.padreDeLog() );
        t.setLogLevel( LogLevel.BASIC );
        for ( Map.Entry<String, String> v : p.variables.entrySet() ) {
          t.setVariable( v.getKey(), v.getValue() );
        }
        RunMotor.prepararVariables( t, p.tm );
        RunMotor.sinTablasDeLog( p.tm, t );
        canal = t.getLogChannelId();
        if ( ultima && p.def != null && !p.def.archivos.isEmpty() ) {
          antesArchivos = estadoArchivos( p.def, p.dir, t );
        }
        try {
          RunMotor.prepararConPlazo( t, Math.max( 1, limite - System.currentTimeMillis() ), "Preparing the "
            + "transformation" );
        } catch ( ToolError e ) {
          run.estado = ResultadoCaso.TIEMPO_AGOTADO;
          run.detalle = e.getMessage();
          return;
        } catch ( Exception e ) {
          run.estado = ResultadoCaso.FALLO_INIT;
          run.errores = (long) Math.max( 1, t.getErrors() );
          run.detalle = RunMotor.errorDeLog( canal, e );
          run.metricas = RunMotor.metricas( t );
          run.log = RunMotor.colaLog( canal, true );
          return;
        }
        if ( ultima ) {
          p.escuchas.clear();
          for ( String n : new LinkedHashSet<String>( p.reales.values() ) ) {
            CorrectorMotor.Escucha e = new CorrectorMotor.Escucha( p.maxFilas );
            for ( StepInterface si : t.findStepInterfaces( n ) ) {
              si.addRowListener( e );
            }
            p.escuchas.put( n, e );
          }
        }
        List<Thread> alimentadores = new ArrayList<Thread>();
        for ( Map.Entry<String, PruebasDatasets.Filas> e : p.inyectar.entrySet() ) {
          RowProducer rp = t.addRowProducer( e.getKey(), 0 );
          alimentadores.add( alimentador( t, rp, e.getValue(), e.getKey() ) );
        }
        try {
          t.startThreads();
        } catch ( Exception e ) {
          run.estado = ResultadoCaso.FALLO_ARRANQUE;
          run.errores = (long) Math.max( 1, t.getErrors() );
          run.detalle = RunMotor.errorDeLog( canal, e );
          parar( t );
          run.metricas = RunMotor.metricas( t );
          run.log = RunMotor.colaLog( canal, true );
          return;
        }
        for ( Thread h : alimentadores ) {
          h.start();
        }
        String corte = null;
        while ( !t.isFinished() ) {
          if ( op.cancelada.getAsBoolean() ) {
            corte = "cancelled";
            break;
          }
          if ( System.currentTimeMillis() > limite ) {
            corte = ResultadoCaso.TIEMPO_AGOTADO;
            break;
          }
          if ( excedida( p ) ) {
            corte = ResultadoCaso.DEMASIADAS_FILAS;
            break;
          }
          RunMotor.dormir( MS_PASO );
        }
        if ( corte == null && excedida( p ) ) {
          corte = ResultadoCaso.DEMASIADAS_FILAS;
        }
        if ( corte != null ) {
          parar( t );
          run.estado = "cancelled".equals( corte ) ? ResultadoCaso.TIEMPO_AGOTADO : corte;
          run.detalle = "cancelled".equals( corte ) ? "cancelled" : ResultadoCaso.TIEMPO_AGOTADO.equals( corte )
            ? "the run did not finish in " + ( p.timeoutMs / 1000 ) + " s (environment.timeout_s)"
            : "a captured step produced more than " + p.maxFilas + " rows (environment.max_rows)";
        }
        run.segundos = Math.round( ( System.currentTimeMillis() - inicio ) / 10.0 ) / 100.0;
        int errores = t.getErrors();
        run.errores = (long) errores;
        run.metricas = RunMotor.metricas( t );
        if ( run.estado == null ) {
          run.estado = errores > 0 ? ResultadoCaso.TERMINO_CON_ERRORES : ResultadoCaso.TERMINO;
          if ( errores > 0 ) {
            run.detalle = RunMotor.errorDeLog( canal, null );
          }
        }
        if ( !ultima && !ResultadoCaso.TERMINO.equals( run.estado ) ) {
          run.log = RunMotor.colaLog( canal, errores > 0 );
          return;
        }
        if ( !ultima ) {
          run.estado = null;
          run.errores = null;
          continue;
        }
        run.log = RunMotor.colaLog( canal, errores > 0 );
      }
      if ( !ResultadoCaso.TERMINO.equals( run.estado ) ) {
        return;
      }
      long filas = 0;
      List<String> desde = new ArrayList<String>();
      for ( Map.Entry<String, CorrectorMotor.Escucha> e : p.escuchas.entrySet() ) {
        filas += e.getValue().filas.size();
        desde.add( e.getKey() );
      }
      for ( TablaSalida ts : p.tablasPedidas ) {
        try {
          CorrectorBases.Captura c = p.bases.capturar( ts, p.maxFilas );
          p.tablas.put( ts.clave(), c );
          filas += c.tabla.cantidadFilas();
          desde.add( "table " + ts.nombre );
        } catch ( CorrectorBases.Falla f ) {
          throw new ToolError( "Test database: " + f.getMessage() );
        }
      }
      if ( antesArchivos != null ) {
        p.archivos.putAll( leerArchivos( p.def, antesArchivos ) );
        for ( ArchivoLeido l : p.archivos.values() ) {
          if ( l.tabla != null ) {
            filas += l.tabla.cantidadFilas();
            desde.add( "file " + l.archivo.getName() );
          }
        }
      }
      run.filasCapturadas = filas;
      run.capturadoDe = String.join( ", ", desde );
    } finally {
      if ( canal != null ) {
        RunMotor.liberarLog( canal );
      }
    }
  }

  private static boolean excedida( Preparada p ) {
    for ( CorrectorMotor.Escucha e : p.escuchas.values() ) {
      if ( e.excedida ) {
        return true;
      }
    }
    return false;
  }

  /** El hilo que pone las filas del dataset en el Injector (una copia de cada fila: los steps reusan arrays). */
  static Thread alimentador( final Trans t, final RowProducer rp, final PruebasDatasets.Filas filas,
                                     String step ) {
    Thread h = new Thread( () -> {
      try {
        for ( Object[] f : filas.filas ) {
          Object[] copia = filas.meta.cloneRow( f );
          while ( !rp.putRowWait( filas.meta, copia, 200, TimeUnit.MILLISECONDS ) ) {
            if ( t.isStopped() || t.isFinished() ) {
              return;
            }
          }
        }
      } catch ( Throwable e ) {
        System.out.println( "[tests] dataset of '" + step + "': " + e );
      } finally {
        rp.finished();
      }
    }, "pentaho-live-test-dataset" );
    h.setDaemon( true );
    return h;
  }

  private static void parar( Trans t ) {
    try {
      t.stopAll();
    } catch ( Throwable ignored ) {
      // sigue con killAll
    }
    RunMotor.esperarFin( t, CorrectorMotor.MS_PARADA );
  }

  // ================================================================ crear (snapshot)

  /** Lo que pide test_create. */
  static final class Pedido {
    /** Carpeta de las pruebas ({@code <ktr>/tests}). */
    File carpeta;
    String nombre;
    String descripcion;
    /** La .ktr del disco (la que la prueba nombra). */
    File original;
    /** Steps cuya salida se congela como dorada. */
    final List<String> salidas = new ArrayList<String>();
    /** Steps cuya salida se congela como dataset; null = las fuentes que llegan a las salidas. */
    List<String> entradas;
    /** step (sin mayusculas) → columnas clave. */
    final Map<String, List<String>> claves = new LinkedHashMap<String, List<String>>();
    /** step (sin mayusculas) → columnas ignoradas. */
    final Map<String, List<String>> ignorar = new LinkedHashMap<String, List<String>>();
    /** Ajustes pedidos; null = saltar los steps que escriben (mas las variables de {@link #variables}). */
    List<DefPrueba.Ajuste> ajustes;
    /** Variables de Spoon que usa la transformacion: se fijan en la prueba (set_variable). */
    final Map<String, String> variables = new LinkedHashMap<String, String>();
    DefPrueba.Entorno entorno = new DefPrueba.Entorno();
    /** Bases de prueba y tablas (opcional: pruebas con base de datos). */
    final List<BaseDePrueba> bases = new ArrayList<BaseDePrueba>();
    final List<TablaSalida> tablas = new ArrayList<TablaSalida>();
    /** Archivos que deja la corrida (0.7.1): rutas, con variables o relativas a la carpeta de las pruebas. */
    final List<String> archivos = new ArrayList<String>();
    final Map<String, List<String>> scriptsBase = new LinkedHashMap<String, List<String>>();
    boolean reemplazar;
    /** Pasar a ignore_columns las columnas que no se reproducen con los datasets. */
    boolean ignorarInestables = true;
    /** Avisos del padre (cambios sin guardar...). */
    final List<String> avisos = new ArrayList<String>();
  }

  /**
   * Crea la prueba (ver la clase) y la corre una vez. Nunca lanza: un problema queda como {@code error} y no se
   * escribe nada (o se borra lo escrito).
   */
  static ResultadoPrueba crear( Pedido pc, Opciones op ) {
    long t0 = System.currentTimeMillis();
    File archivo = new File( pc.carpeta, pc.nombre + DefPrueba.EXTENSION );
    ResultadoPrueba r;
    List<File> escritos = new ArrayList<File>();
    try {
      r = crearInterno( pc, op, archivo, escritos );
    } catch ( ToolError e ) {
      r = ResultadoPrueba.sin( pc.nombre, archivo.getAbsolutePath(), ResultadoPrueba.ERROR, e.getMessage() );
      borrar( escritos );
    } catch ( Throwable t ) {
      t.printStackTrace( System.out );
      r = ResultadoPrueba.sin( pc.nombre, archivo.getAbsolutePath(), ResultadoPrueba.ERROR, "Could not create the "
        + "test: " + RunMotor.primeraLinea( t ) );
      borrar( escritos );
    }
    r.segundos = Math.round( ( System.currentTimeMillis() - t0 ) / 10.0 ) / 100.0;
    if ( !op.claves.isEmpty() ) {
      tapar( r, op.claves.values() );
    }
    return r;
  }

  private static ResultadoPrueba crearInterno( Pedido pc, Opciones op, File archivo, List<File> escritos )
    throws Exception {
    if ( !DefPrueba.nombreValido( pc.nombre ) ) {
      throw new ToolError( "Invalid test name '" + pc.nombre + "': letters, digits, - and _ (up to 80)" );
    }
    if ( archivo.exists() && !pc.reemplazar ) {
      throw new ToolError( "The test " + archivo + " already exists (replace=true to overwrite it)" );
    }
    if ( pc.salidas.isEmpty() && pc.tablas.isEmpty() && pc.archivos.isEmpty() ) {
      throw new ToolError( "Choose at least one output step to freeze (output_steps)" );
    }
    if ( pc.original != null && pc.original.getName().toLowerCase( Locale.ROOT ).endsWith( ".kjb" ) ) {
      return PruebasJob.crear( pc, op, archivo, escritos );
    }
    File dir = pc.carpeta.getAbsoluteFile();
    File ktr = op.ktr != null ? op.ktr : pc.original;
    if ( !ktr.isFile() ) {
      throw new ToolError( "The transformation " + pc.original + " does not exist" );
    }
    DefPrueba d = new DefPrueba();
    d.esquema = DefPrueba.ESQUEMA;
    d.nombre = pc.nombre;
    d.descripcion = pc.descripcion;
    d.transformacion = relativa( dir, pc.original );
    d.entorno = pc.entorno == null ? new DefPrueba.Entorno() : pc.entorno;
    d.caso = new io.github.ivi2511.pentahomcp.corrector.modelo.Caso();
    d.caso.id = pc.nombre;
    d.caso.esperado = "golden/" + pc.nombre;
    d.caso.scriptsBase.putAll( pc.scriptsBase );
    d.bases.addAll( pc.bases );
    d.tablas.addAll( pc.tablas );
    for ( String a : pc.archivos ) {
      d.archivos.add( new DefPrueba.ArchivoSalida( a ) );
    }
    d.creadaCon = "Pentaho Live " + ( op.versionPlugin == null ? "" : op.versionPlugin );
    d.completar();

    // la transformacion tal cual, para elegir entradas y ajustes
    TransMeta tm;
    try {
      tm = new TransMeta( ktr.getAbsolutePath() );
    } catch ( Exception e ) {
      throw new ToolError( "Cannot load " + pc.original.getName() + ": " + RunMotor.primeraLinea( e ) );
    }
    List<StepMeta> salidas = new ArrayList<StepMeta>();
    for ( String n : pc.salidas ) {
      StepMeta sm = buscar( tm, n );
      if ( sm == null ) {
        throw new ToolError( "There is no step '" + n + "' in " + pc.original.getName() + ". Steps: "
          + limitar( nombresSteps( tm ), 40 ) );
      }
      if ( !salidas.contains( sm ) ) {
        salidas.add( sm );
      }
    }
    Set<StepMeta> alcance = d.tablas.isEmpty() && d.archivos.isEmpty() ? ancestros( tm, salidas )
      : new LinkedHashSet<StepMeta>( tm.getSteps() );
    Pack pk = d.comoPack();
    List<StepMeta> entradas = new ArrayList<StepMeta>();
    if ( pc.entradas == null ) {
      for ( StepMeta sm : alcance ) {
        if ( !salidas.contains( sm ) && entra( tm, sm ) == 0 && !FUENTES_FIJAS.contains( sm.getStepID() )
          && !conBaseDePrueba( sm, pk ) ) {
          entradas.add( sm );
        }
      }
    } else {
      for ( String n : pc.entradas ) {
        StepMeta sm = buscar( tm, n );
        if ( sm == null ) {
          throw new ToolError( "There is no input step '" + n + "' in " + pc.original.getName() );
        }
        if ( salidas.contains( sm ) ) {
          throw new ToolError( "'" + n + "' cannot be both an input (dataset) and an output" );
        }
        if ( !alcance.contains( sm ) ) {
          throw new ToolError( "The input '" + n + "' does not lead to any of the output steps" );
        }
        if ( !entradas.contains( sm ) ) {
          entradas.add( sm );
        }
      }
    }
    // ajustes: los pedidos, o saltar todo lo que escribe (salvo las entradas)
    if ( pc.ajustes != null ) {
      d.ajustes.addAll( pc.ajustes );
    } else if ( d.archivos.isEmpty() ) { // con files[] lo que escribe tiene que escribir
      for ( StepMeta sm : alcance ) {
        if ( !entradas.contains( sm ) && RunMotor.escribe( sm ) && !conBaseDePrueba( sm, pk ) ) {
          d.ajustes.add( DefPrueba.Ajuste.saltar( sm.getName(), "writes outside the transformation ("
            + sm.getStepID() + ")" ) );
        }
      }
    }
    for ( Map.Entry<String, String> v : pc.variables.entrySet() ) {
      boolean ya = false;
      for ( DefPrueba.Ajuste a : d.ajustes ) {
        ya |= DefPrueba.Ajuste.VARIABLE.equals( a.tipo ) && v.getKey().equals( a.nombre );
      }
      if ( !ya ) {
        d.ajustes.add( DefPrueba.Ajuste.variable( v.getKey(), v.getValue() ) );
      }
    }
    for ( StepMeta sm : salidas ) {
      DefPrueba.Salida s = new DefPrueba.Salida( sm.getName() );
      List<String> k = pc.claves.get( clave( sm.getName() ) );
      if ( k != null ) {
        s.comparar.columnasClave.addAll( k );
      }
      List<String> ig = pc.ignorar.get( clave( sm.getName() ) );
      if ( ig != null ) {
        s.ignorar.addAll( ig );
      }
      d.salidas.add( s );
    }

    // 1) en vivo: las entradas y las salidas tal como salen hoy
    ResultadoPrueba.Creada creada = new ResultadoPrueba.Creada();
    creada.avisos.addAll( pc.avisos );
    List<String> capturar = new ArrayList<String>();
    for ( StepMeta sm : entradas ) {
      capturar.add( sm.getName() );
    }
    for ( StepMeta sm : salidas ) {
      capturar.add( sm.getName() );
    }
    List<String> notas = new ArrayList<String>();
    DefPrueba enVivo = CorrectorJson.copia( d, DefPrueba.class ).completar();
    enVivo.todaLaTransformacion = false;
    Preparada vivo = preparar( enVivo, dir, ktr, pc.original, capturar, false, op, notas );
    ResultadoCaso.Corrida run = new ResultadoCaso.Corrida();
    ejecutar( vivo, enVivo, op, run, null );
    if ( op.cancelada.getAsBoolean() ) {
      throw new ToolError( "Cancelled" );
    }
    if ( !ResultadoCaso.TERMINO.equals( run.estado ) ) {
      throw new ToolError( "The transformation does not run cleanly, so nothing was frozen (" + run.estado + "): "
        + ( run.detalle == null ? "" : run.detalle ) + ( run.log == null ? "" : "\n" + run.log ) );
    }

    // 2) los datasets
    File dirDatos = new File( new File( dir, "datasets" ), pc.nombre );
    for ( StepMeta sm : entradas ) {
      CorrectorMotor.Escucha e = vivo.escuchas.get( sm.getName() );
      RowMetaInterface rm = e == null ? null : e.meta;
      if ( rm == null ) {
        rm = vivo.tm.getStepFields( sm.getName() ); // no salio ninguna fila: los campos del step
      }
      File f = new File( dirDatos, DefPrueba.archivoDe( sm.getName() ) + ".csv" );
      escritos.add( f );
      int n = PruebasDatasets.escribirDataset( f, rm, e == null ? new ArrayList<Object[]>() : e.filas );
      if ( e != null && e.excedida ) {
        creada.avisos.add( "'" + sm.getName() + "' produced more than " + d.entorno.maxFilas + " rows: the dataset "
          + "keeps the first ones (raise environment.max_rows)" );
      }
      DefPrueba.Dataset ds = new DefPrueba.Dataset( sm.getName(), relativa( dir, f ) );
      ds.columnas.addAll( PruebasDatasets.columnas( rm ) );
      d.datasets.add( ds );
      creada.datasets.add( new ResultadoPrueba.Archivo( sm.getName(), ds.archivo, n, nombresDe( ds.columnas ) ) );
    }

    // 3) con los datasets: los dorados
    List<String> notas2 = new ArrayList<String>();
    Preparada rep = preparar( d, dir, ktr, pc.original, nombres( d.salidas ), true, op, notas2 );
    ResultadoCaso.Corrida run2 = new ResultadoCaso.Corrida();
    ejecutar( rep, d, op, run2, null );
    if ( op.cancelada.getAsBoolean() ) {
      throw new ToolError( "Cancelled" );
    }
    if ( !ResultadoCaso.TERMINO.equals( run2.estado ) ) {
      throw new ToolError( "With the frozen datasets the transformation does not run cleanly (" + run2.estado + "): "
        + ( run2.detalle == null ? "" : run2.detalle ) );
    }
    File dirDorado = resolver( dir, d.caso.esperado );
    for ( DefPrueba.Salida s : d.salidas ) {
      CorrectorTabla t = rep.capturada( s.step );
      CorrectorMotor.Escucha e = rep.escuchas.get( rep.real( s.step ) );
      s.columnas.clear();
      s.columnas.addAll( PruebasDatasets.tipos( t.columnas, e == null ? null : e.meta ) );
      File f = dorado( dir, d, s );
      escritos.add( f );
      PruebasDatasets.escribirDorado( f, t, d.csvDorado );
      creada.dorados.add( new ResultadoPrueba.Archivo( s.step, relativa( dir, f ), t.cantidadFilas(), t.columnas ) );
      // ¿los datasets reproducen la salida en vivo?
      estabilidad( s, t, vivo.capturada( s.step ), d, pc.ignorarInestables, creada );
    }
    for ( TablaSalida ts : d.tablas ) {
      CorrectorBases.Captura c = rep.tablas.get( ts.clave() );
      if ( c == null ) {
        continue;
      }
      ts.columnas.clear();
      ts.columnas.addAll( c.tipos );
      if ( ts.columnasClave.isEmpty() && !c.clavePrimaria.isEmpty() ) {
        ts.columnasClave.addAll( c.clavePrimaria );
      }
      File f = new File( dirDorado, ts.archivo() );
      escritos.add( f );
      PruebasDatasets.escribirDorado( f, c.tabla, d.csvDorado );
      creada.dorados.add( new ResultadoPrueba.Archivo( "table " + ts.nombre, relativa( dir, f ), c.tabla
        .cantidadFilas(), c.tabla.columnas ) );
    }
    for ( DefPrueba.ArchivoSalida a : d.archivos ) {
      ArchivoLeido l = rep.archivos.get( a );
      if ( l == null || l.tabla == null ) {
        throw new ToolError( "The file " + a.ruta + " cannot be frozen: " + ( l == null ? "it was not read"
          : l.problema ) );
      }
      File f = dorado( dir, d, a );
      escritos.add( f );
      PruebasDatasets.escribirDorado( f, l.tabla, d.csvDorado );
      creada.dorados.add( new ResultadoPrueba.Archivo( "file " + a.nombre(), relativa( dir, f ), l.tabla
        .cantidadFilas(), l.tabla.columnas ) );
    }
    creada.ajustes.addAll( d.ajustes );
    escritos.add( archivo );
    guardar( d, archivo );

    // 4) la prueba recien creada, corrida como cualquier otra
    ResultadoPrueba r = correr( archivo, op );
    r.creada = creada;
    if ( !r.paso() && ResultadoPrueba.FALLO.equals( r.estado ) ) {
      creada.avisos.add( "The new test does not pass on its own frozen data: " + r.mensaje );
    }
    return r;
  }

  /**
   * Si los datasets reproducen la salida en vivo: si solo cambian valores de algunas columnas (fechas del sistema,
   * aleatorios) y {@code ignorar}, pasan a {@code ignore_columns}; si no, un aviso.
   */
  static void estabilidad( DefPrueba.Salida s, CorrectorTabla repetida, CorrectorTabla viva, DefPrueba d,
                           boolean ignorar, ResultadoPrueba.Creada creada ) {
    String nombre = DefPrueba.nombreCompleto( s.entrada, s.step );
    Comparacion c = CorrectorComparar.comparar( repetida, viva, s.columnas, s.comparar, d.entorno.politica()
      .localeJava(), null );
    ResultadoPrueba.Resumen x = new ResultadoPrueba.Resumen( nombre, ResultadoPrueba.Resumen.STEP );
    resumir( x, c, s.comparar );
    if ( x.paso ) {
      return;
    }
    List<String> cols = new ArrayList<String>( c.diferenciasPorColumna.keySet() );
    boolean soloValores = c.filas.faltan == 0 && c.filas.sobran == 0 && c.columnas.faltan.isEmpty()
      && c.columnas.sobran.isEmpty() && !cols.isEmpty();
    if ( soloValores && ignorar ) {
      for ( String col : cols ) {
        if ( !s.ignora( col ) ) {
          s.ignorar.add( col );
        }
      }
      creada.avisos.add( "'" + nombre + "': the columns " + cols + " change between runs (system dates, "
        + "random values...): added to ignore_columns" );
    } else {
      creada.avisos.add( "'" + nombre + "': replaying the datasets does not give the same rows as the live "
        + "sources (" + ( c.filas.distintas + c.filas.faltan + c.filas.sobran ) + " rows differ" + ( cols
        .isEmpty() ? "" : ", columns " + cols ) + "). The golden file is the replayed output; check the "
        + "inputs that were not frozen and non-deterministic steps" );
    }
  }

  /** El step usa conexiones y todas tienen base de prueba: lee o escribe solo en bases descartables. */
  static boolean conBaseDePrueba( StepMeta sm, Pack pk ) {
    if ( pk.bases == null || pk.bases.isEmpty() ) {
      return false;
    }
    DatabaseMeta[] usadas;
    try {
      usadas = sm.getStepMetaInterface().getUsedDatabaseConnections();
    } catch ( Throwable t ) {
      return false;
    }
    if ( usadas == null || usadas.length == 0 ) {
      return false;
    }
    for ( DatabaseMeta dm : usadas ) {
      if ( dm == null || CorrectorBases.emparejar( pk, dm.getName(), dm.getPluginId(), dm.getHostname(), dm
        .getDatabaseName(), new HashSet<BaseDePrueba>(), false ) == null ) {
        return false;
      }
    }
    return true;
  }

  /** Steps de los que llega algo a los objetivos (ellos incluidos), por saltos habilitados. */
  static Set<StepMeta> ancestros( TransMeta tm, Collection<StepMeta> objetivos ) {
    Set<StepMeta> s = new LinkedHashSet<StepMeta>( objetivos );
    boolean cambio = true;
    while ( cambio ) {
      cambio = false;
      for ( TransHopMeta h : tm.getTransHops() ) {
        if ( h.isEnabled() && h.getFromStep() != null && s.contains( h.getToStep() ) && s.add( h.getFromStep() ) ) {
          cambio = true;
        }
      }
    }
    return s;
  }

  /** Saltos habilitados que entran al step. */
  static int entra( TransMeta tm, StepMeta sm ) {
    int n = 0;
    for ( TransHopMeta h : tm.getTransHops() ) {
      if ( h.isEnabled() && h.getToStep() == sm ) {
        n++;
      }
    }
    return n;
  }

  // ================================================================ utilidades

  static StepMeta buscar( TransMeta tm, String nombre ) {
    if ( nombre == null ) {
      return null;
    }
    StepMeta sm = tm.findStep( nombre );
    if ( sm != null ) {
      return sm;
    }
    for ( StepMeta s : tm.getSteps() ) {
      if ( s.getName() != null && s.getName().trim().equalsIgnoreCase( nombre.trim() ) ) {
        return s;
      }
    }
    return null;
  }

  static Set<String> nombresSteps( TransMeta tm ) {
    Set<String> s = new LinkedHashSet<String>();
    for ( StepMeta sm : tm.getSteps() ) {
      s.add( sm.getName() );
    }
    return s;
  }

  static List<String> nombres( List<DefPrueba.Salida> salidas ) {
    List<String> l = new ArrayList<String>();
    for ( DefPrueba.Salida s : salidas ) {
      l.add( s.step );
    }
    return l;
  }

  static List<String> nombresColumnas( List<Pack.Columna> cols ) {
    List<String> l = new ArrayList<String>();
    for ( Pack.Columna c : cols ) {
      l.add( c.nombre );
    }
    return l;
  }

  private static List<String> nombresDe( List<DefPrueba.ColumnaDataset> cols ) {
    List<String> l = new ArrayList<String>();
    for ( DefPrueba.ColumnaDataset c : cols ) {
      l.add( c.nombre );
    }
    return l;
  }

  static String clave( String n ) {
    return n == null ? "" : n.trim().toLowerCase( Locale.ROOT );
  }

  static String limitar( Collection<String> l, int max ) {
    List<String> x = new ArrayList<String>( l );
    return x.size() <= max ? String.join( ", ", x ) : String.join( ", ", x.subList( 0, max ) ) + "... (" + x.size()
      + ")";
  }

  /** Ruta de f relativa a dir con /, o la absoluta si no hay una relativa razonable. */
  static String relativa( File dir, File f ) {
    try {
      java.nio.file.Path a = dir.getCanonicalFile().toPath();
      java.nio.file.Path b = f.getCanonicalFile().toPath();
      if ( a.getRoot() != null && a.getRoot().equals( b.getRoot() ) ) {
        return a.relativize( b ).toString().replace( '\\', '/' );
      }
    } catch ( Exception ignored ) {
      // la absoluta
    }
    return f.getAbsolutePath();
  }

  private static void borrar( List<File> l ) {
    for ( File f : l ) {
      if ( f.isFile() && !f.delete() ) {
        f.deleteOnExit();
      }
    }
  }

  /** Las contraseñas de las bases de prueba fuera de todo texto del resultado. */
  static void tapar( ResultadoPrueba r, Collection<String> claves ) {
    try {
      com.fasterxml.jackson.databind.JsonNode n = CorrectorJson.mapper().valueToTree( r );
      CorrectorMain.tapar( n, new ArrayList<String>( claves ) );
      ResultadoPrueba x = CorrectorJson.mapper().treeToValue( n, ResultadoPrueba.class );
      r.mensaje = x.mensaje;
      r.notas = x.notas;
      r.caso = x.caso;
      r.creada = x.creada;
    } catch ( Exception ignored ) {
      // queda como estaba
    }
  }

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }
}
