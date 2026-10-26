package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.pentaho.di.core.Result;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.logging.ChannelLogTable;
import org.pentaho.di.core.logging.JobEntryLogTable;
import org.pentaho.di.core.logging.JobLogTable;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.logging.LoggingObjectInterface;
import org.pentaho.di.core.logging.LoggingObjectType;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobEntryResult;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.success.JobEntrySuccess;
import org.pentaho.di.job.entries.trans.JobEntryTrans;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.trans.RowProducer;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransAdapter;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * Las pruebas de jobs (0.7.1), en la JVM de las pruebas como las de transformaciones ({@link PruebasMotor}):
 * <ul>
 * <li><b>Correr</b> ({@link #correr}): carga la .kjb (o la copia del documento abierto, con el nombre de archivo del
 * original), aplica los {@code skip_entry} (la entrada pasa a ser un Success con su nombre), apunta las conexiones del
 * job a las bases de prueba (y el SQL y los esquemas de sus entradas), y lo corre como Kitchen ({@link Job}, con plazo
 * y cancelacion). Cada transformacion que arranca pasa por {@link PruebasGancho}: si es de una entrada que la prueba
 * nombra ({@code job_entry}: el nombre de la entrada Transformation o el de su .ktr), sus steps con dataset pasan a ser
 * Injectors, se aplican sus skip_step/remove_step, sus conexiones van a las bases de prueba y las salidas capturadas
 * tienen su RowListener (de la ultima vez que corrio esa entrada). Al final se leen las tablas de las bases de prueba
 * y los archivos de {@code files[]}, y todo se compara con los dorados como en una prueba de transformacion.</li>
 * <li><b>Crear</b> ({@link #crear}, snapshot): una corrida en vivo captura las entradas y salidas elegidas
 * ({@code "Entrada :: Step"}), las entradas pasan a datasets, otra corrida con los datasets da los dorados (salidas,
 * tablas y archivos) y la prueba nueva se corre una vez. Por defecto se saltan las entradas que hablan con el
 * exterior (mail, FTP, SSH, HTTP...), las de SQL sin base de prueba y, si la prueba no compara archivos ni tablas, lo
 * que escribe (steps de salida y entradas que crean, mueven o borran archivos): asi crear una prueba no escribe nada.</li>
 * </ul>
 * La variable {@value #VARIABLE_DIR} es una carpeta temporal propia de cada corrida: un {@code set_variable} puede
 * mandar ahi las salidas del job ({@code ${PENTAHO_LIVE_TEST_DIR}}) para no tocar las de verdad.
 *
 * @dueño P1 (0.7.1)
 */
final class PruebasJob {

  /** Carpeta temporal de la corrida (para set_variable y files[].path). */
  static final String VARIABLE_DIR = "PENTAHO_LIVE_TEST_DIR";
  /** Entradas que hablan con el exterior: se saltan al crear una prueba (ids de plugin). */
  static final Set<String> EXTERIORES = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList( "MAIL",
    "FTP", "FTP_PUT", "FTP_DELETE", "FTPS_GET", "FTPS_PUT", "SFTP", "SFTPPUT", "SSH2_GET", "SSH2_PUT", "HTTP",
    "SEND_NAGIOS_PASSIVE_CHECK", "SNMP_TRAP", "SYSLOG", "MSGBOX_INFO", "TALEND_JOB_EXEC", "GET_POP",
    "MAIL_VALIDATOR", "WEBSERVICE_AVAILABLE", "TELNET", "PING" ) ) );
  /** Entradas que crean, mueven o borran archivos: se saltan al crear si la prueba no compara archivos ni tablas. */
  static final Set<String> DE_ARCHIVOS = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList(
    "DELETE_FILE", "DELETE_FILES", "DELETE_FOLDERS", "MOVE_FILES", "COPY_FILES", "CREATE_FILE", "CREATE_FOLDER",
    "WRITE_TO_FILE", "ZIP_FILE", "UNZIP", "DELETE_RESULT_FILENAMES", "COPY_MOVE_RESULT_FILENAMES", "PGP_ENCRYPT_FILES",
    "PGP_DECRYPT_FILES", "DOS_UNIX_CONVERTER", "MYSQL_BULK_FILE", "XSLT", "XML_WELL_FORMED", "DTD_VALIDATOR" ) ) );
  /** Entradas que escriben en una base: sin base de prueba para su conexion, se saltan al crear. */
  static final Set<String> ESCRIBEN_EN_BASE = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList(
    "SQL", "TRUNCATE_TABLES", "MYSQL_BULK_LOAD", "MSSQL_BULK_LOAD" ) ) );

  private PruebasJob() {
  }

  // ================================================================ el plan de una corrida

  /** Un step capturado de una entrada del job. */
  static final class Captura {
    final String entrada;
    final String step;
    volatile CorrectorMotor.Escucha escucha;
    volatile StepMeta sm;
    volatile TransMeta tm;
    /** La entrada corrio y el step estaba (en la ultima corrida). */
    volatile boolean vista;
    volatile String nombreReal;
    /** Por que no se capturo (el step no esta en la transformacion de la entrada). */
    volatile String falta;

    Captura( String entrada, String step ) {
      this.entrada = entrada;
      this.step = step;
    }

    CorrectorTabla tabla() {
      CorrectorMotor.Escucha e = escucha;
      if ( e == null || sm == null ) {
        return CorrectorTabla.vacia( new ArrayList<String>() );
      }
      return e.tabla( sm, tm );
    }
  }

  /** Un dataset que se inyecta en un step de una entrada. */
  static final class Inyeccion {
    final String entrada;
    final String step;
    final String archivo;
    final PruebasDatasets.Filas filas;
    volatile boolean usada;

    Inyeccion( String entrada, String step, String archivo, PruebasDatasets.Filas filas ) {
      this.entrada = entrada;
      this.step = step;
      this.archivo = archivo;
      this.filas = filas;
    }
  }

  /** Lo que hace el gancho con cada transformacion del job (una corrida de una prueba). */
  static final class Plan implements PruebasGancho.Oyente {
    DefPrueba d;
    File dir;
    PruebasMotor.Opciones op;
    Pack pk;
    CorrectorBases.Sesion bases;
    final Map<String, String> variables = new LinkedHashMap<String, String>();
    final Map<String, String> parametros = new LinkedHashMap<String, String>();
    final List<Captura> capturas = new ArrayList<Captura>();
    final List<Inyeccion> inyecciones = new ArrayList<Inyeccion>();
    final List<DefPrueba.Ajuste> ajustesDeStep = new ArrayList<DefPrueba.Ajuste>();
    final List<String> notas = Collections.synchronizedList( new ArrayList<String>() );
    final Set<String> entradasVistas = Collections.synchronizedSet( new LinkedHashSet<String>() );
    final Map<TransMeta, Boolean> preparadas = Collections.synchronizedMap( new WeakHashMap<TransMeta, Boolean>() );
    final Map<String, CorrectorBases.Captura> tablas = new LinkedHashMap<String, CorrectorBases.Captura>();
    final Map<DefPrueba.ArchivoSalida, PruebasMotor.ArchivoLeido> archivos = new LinkedHashMap<DefPrueba
      .ArchivoSalida, PruebasMotor.ArchivoLeido>();
    volatile String error;
    long maxFilas = Long.MAX_VALUE;
    long timeoutMs = 120000;
    int veces = 1;
    File dirTemporal;
    /** Leer las tablas y los archivos de la prueba al terminar (no en la corrida en vivo de crear). */
    boolean leerResultados = true;

    Captura captura( String entrada, String step ) {
      for ( Captura c : capturas ) {
        if ( mismo( c.entrada, entrada ) && mismo( c.step, step ) ) {
          return c;
        }
      }
      return null;
    }

    boolean excedida() {
      for ( Captura c : capturas ) {
        CorrectorMotor.Escucha e = c.escucha;
        if ( e != null && e.excedida ) {
          return true;
        }
      }
      return false;
    }

    @Override
    public void preparar( final Trans t ) throws KettleException {
      final TransMeta tm = t.getTransMeta();
      if ( tm == null || preparadas.containsKey( tm ) ) {
        return;
      }
      preparadas.put( tm, Boolean.TRUE );
      String entrada = entradaDe( t );
      String etiqueta = entrada != null ? "entry '" + entrada + "'" : "transformation '" + tm.getName() + "'";
      List<String> nt = new ArrayList<String>();
      try {
        if ( entrada != null ) {
          entradasVistas.add( PruebasMotor.clave( entrada ) );
        }
        final List<Captura> mias = new ArrayList<Captura>();
        for ( Captura c : capturas ) {
          if ( coincide( c.entrada, entrada, tm ) ) {
            StepMeta sm = PruebasMotor.buscar( tm, c.step );
            if ( sm == null ) {
              c.falta = "the step '" + c.step + "' is not in " + etiqueta;
              c.vista = false;
            } else {
              c.falta = null;
              c.nombreReal = sm.getName();
              mias.add( c );
            }
          }
        }
        final List<Inyeccion> inys = new ArrayList<Inyeccion>();
        Set<String> inyectados = new HashSet<String>();
        for ( Inyeccion i : inyecciones ) {
          if ( !coincide( i.entrada, entrada, tm ) ) {
            continue;
          }
          StepMeta sm = PruebasMotor.buscar( tm, i.step );
          if ( sm == null ) {
            throw new ToolError( "The step '" + i.step + "' of the dataset " + i.archivo + " is not in " + etiqueta );
          }
          PruebasMotor.inyector( tm, sm, i.filas.meta, nt );
          inyectados.add( sm.getName() );
          i.usada = true;
          inys.add( i );
          nt.add( "step '" + sm.getName() + "' replaced by the dataset " + i.archivo + " (" + i.filas.filas.size()
            + " rows)" );
        }
        List<StepMeta> objetivos = new ArrayList<StepMeta>();
        for ( Captura c : mias ) {
          objetivos.add( tm.findStep( c.nombreReal ) );
        }
        for ( DefPrueba.Ajuste a : ajustesDeStep ) {
          if ( coincide( a.entrada, entrada, tm ) ) {
            PruebasMotor.ajustarStep( tm, a, objetivos, inyectados, nt );
          }
        }
        for ( StepMeta s : tm.getSteps() ) {
          s.getStepMetaInterface().searchInfoAndTargetSteps( tm.getSteps() );
        }
        tm.clearCaches();
        if ( bases != null ) {
          PruebasMotor.Redireccion red = PruebasMotor.redirigir( tm.getDatabases(), PruebasMotor.usadas( tm ), pk,
            bases, nt );
          PruebasMotor.esquemas( tm, bases, red, PruebasMotor.variablesDe( t, variables ), nt );
        } else {
          PruebasMotor.avisarSinBases( tm, nt );
        }
        RunMotor.sinTablasDeLog( tm );
        RunMotor.sinTablasDeLog( tm, t );
        if ( mias.isEmpty() && inys.isEmpty() ) {
          return;
        }
        t.addTransListener( new TransAdapter() {
          @Override
          public void transStarted( Trans x ) throws KettleException {
            for ( Captura c : mias ) {
              CorrectorMotor.Escucha e = new CorrectorMotor.Escucha( maxFilas );
              for ( StepInterface si : x.findStepInterfaces( c.nombreReal ) ) {
                si.addRowListener( e );
              }
              c.tm = tm;
              c.sm = tm.findStep( c.nombreReal );
              c.escucha = e;
              c.vista = true;
            }
            for ( Inyeccion i : inys ) {
              String n = PruebasMotor.buscar( tm, i.step ).getName();
              RowProducer rp = x.addRowProducer( n, 0 );
              PruebasMotor.alimentador( x, rp, i.filas, n ).start();
            }
          }
        } );
      } catch ( ToolError e ) {
        error = e.getMessage();
        throw new KettleException( e.getMessage() );
      } finally {
        for ( String n : nt ) {
          notas.add( etiqueta + ": " + n );
        }
      }
    }
  }

  /** El nombre de la entrada del job que corre esta Trans (su padre de log), o null (un mapping, una executor). */
  static String entradaDe( Trans t ) {
    try {
      LoggingObjectInterface p = t.getParent();
      if ( p != null && p.getObjectType() == LoggingObjectType.JOBENTRY ) {
        return p.getObjectName();
      }
    } catch ( Throwable ignored ) {
      // sin padre
    }
    return null;
  }

  /** Si {@code pedida} (de la prueba) nombra esta transformacion: su entrada, su .ktr (con o sin extension) o su nombre. */
  static boolean coincide( String pedida, String entrada, TransMeta tm ) {
    if ( pedida == null || pedida.trim().isEmpty() ) {
      return false;
    }
    String p = pedida.trim();
    if ( entrada != null && p.equalsIgnoreCase( entrada.trim() ) ) {
      return true;
    }
    String f = tm.getFilename();
    if ( f != null && !f.trim().isEmpty() ) {
      String n = new File( f.replace( '\\', '/' ).replaceFirst( "^(?i)file:/+", "/" ) ).getName();
      if ( p.equalsIgnoreCase( n ) || p.equalsIgnoreCase( n.replaceFirst( "(?i)\\.ktr$", "" ) ) ) {
        return true;
      }
    }
    return entrada == null && tm.getName() != null && p.equalsIgnoreCase( tm.getName().trim() );
  }

  static boolean mismo( String a, String b ) {
    return PruebasMotor.clave( a ).equals( PruebasMotor.clave( b ) );
  }

  // ================================================================ correr

  /** Corre una prueba de job (Kettle ya iniciado). Lo que falla queda en {@code r}; ToolError si no se puede. */
  static void correr( DefPrueba d, File dir, PruebasMotor.Opciones op, ResultadoPrueba r ) throws Exception {
    File original = PruebasMotor.resolver( dir, d.job );
    r.job = original.getPath();
    File kjb = op.ktr != null ? op.ktr : original;
    r.origen = op.ktr != null ? ResultadoPrueba.DEL_DOCUMENTO : ResultadoPrueba.DEL_ARCHIVO;
    if ( !kjb.isFile() ) {
      throw new ToolError( "The job " + original + " does not exist" );
    }
    ResultadoCaso rc = new ResultadoCaso();
    rc.id = d.caso.id;
    rc.tablas = new ArrayList<ResultadoCaso.ComparacionTabla>();
    r.caso = rc;
    final Plan plan = plan( d, dir, op, true );
    for ( DefPrueba.Salida s : d.salidas ) {
      plan.capturas.add( new Captura( s.entrada, s.step ) );
    }
    try {
      JobMeta jm = cargar( kjb, original );
      prepararJob( plan, jm );
      ejecutar( plan, jm, rc.corrida );
    } finally {
      r.notas.addAll( plan.notas );
      PruebasProcesos.borrar( plan.dirTemporal );
    }
    if ( op.cancelada.getAsBoolean() ) {
      r.estado = ResultadoPrueba.CANCELADA;
      r.mensaje = "Cancelled";
      return;
    }
    final boolean corrio = rc.corrioBien();
    final Set<DefPrueba.Salida> faltan = new HashSet<DefPrueba.Salida>();
    for ( DefPrueba.Salida s : d.salidas ) {
      Captura c = plan.captura( s.entrada, s.step );
      if ( corrio && ( c == null || !c.vista ) ) {
        faltan.add( s );
        ResultadoPrueba.Resumen x = new ResultadoPrueba.Resumen( DefPrueba.nombreCompleto( s.entrada, s.step ),
          ResultadoPrueba.Resumen.STEP );
        x.problema = ResultadoPrueba.Resumen.SIN_STEP;
        r.salidas.add( x );
        r.notas.add( c != null && c.falta != null ? c.falta : "the job did not run the entry '" + s.entrada + "'" );
      }
    }
    for ( Inyeccion i : plan.inyecciones ) {
      if ( corrio && !i.usada ) {
        r.notas.add( "dataset of '" + DefPrueba.nombreCompleto( i.entrada, i.step ) + "' not used: the job did not "
          + "run that entry" );
      }
    }
    PruebasMotor.comparar( d, dir, r, rc, corrio, "job", new PruebasMotor.Capturas() {
      @Override
      public String nombre( DefPrueba.Salida s ) {
        if ( faltan.contains( s ) ) {
          return null;
        }
        Captura c = plan.captura( s.entrada, s.step );
        return DefPrueba.nombreCompleto( s.entrada, c != null && c.nombreReal != null ? c.nombreReal : s.step );
      }

      @Override
      public CorrectorTabla tabla( DefPrueba.Salida s ) {
        Captura c = plan.captura( s.entrada, s.step );
        return c == null ? CorrectorTabla.vacia( new ArrayList<String>() ) : c.tabla();
      }

      @Override
      public CorrectorBases.Captura tabla( TablaSalida t ) {
        return plan.tablas.get( t.clave() );
      }

      @Override
      public PruebasMotor.ArchivoLeido archivo( DefPrueba.ArchivoSalida a ) {
        return plan.archivos.get( a );
      }
    } );
  }

  /** El plan de una corrida: bases, variables, ajustes y (si conDatasets) los datasets leidos. */
  static Plan plan( DefPrueba d, File dir, PruebasMotor.Opciones op, boolean conDatasets ) throws Exception {
    Plan p = new Plan();
    p.d = d;
    p.dir = dir;
    p.op = op;
    p.timeoutMs = Math.max( 1, d.entorno.timeoutSeg ) * 1000L;
    p.maxFilas = d.entorno.maxFilas <= 0 ? Long.MAX_VALUE : d.entorno.maxFilas;
    p.veces = d.caso == null ? 1 : d.caso.vecesQueCorre();
    p.dirTemporal = Files.createTempDirectory( "pentaho-live-test-" ).toFile();
    for ( DefPrueba.Ajuste a : d.ajustes ) {
      if ( DefPrueba.Ajuste.VARIABLE.equals( a.tipo ) ) {
        p.variables.put( a.nombre.trim(), a.valor == null ? "" : a.valor );
      } else if ( DefPrueba.Ajuste.PARAMETRO.equals( a.tipo ) ) {
        p.parametros.put( a.nombre.trim(), a.valor == null ? "" : a.valor );
      } else if ( a.deStep() ) {
        p.ajustesDeStep.add( a );
      }
    }
    if ( conDatasets ) {
      for ( DefPrueba.Dataset ds : d.datasets ) {
        File f = PruebasMotor.resolver( dir, ds.archivo );
        if ( !f.isFile() ) {
          throw new ToolError( "The dataset file " + ds.archivo + " (step '" + DefPrueba.nombreCompleto( ds.entrada,
            ds.step ) + "') does not exist" );
        }
        p.inyecciones.add( new Inyeccion( ds.entrada, ds.step, ds.archivo, PruebasDatasets.leerDataset( f,
          ds.columnas, d.entorno.maxFilas ) ) );
      }
    }
    if ( !d.bases.isEmpty() ) {
      p.pk = d.comoPack();
      try {
        p.bases = new CorrectorBases.Sesion( p.pk, dir, op.sufijoBase, op.claves );
      } catch ( CorrectorBases.Falla f ) {
        throw new ToolError( f.getMessage() );
      }
    }
    return p;
  }

  /** La .kjb (o la copia del documento) con el nombre de archivo del original (las rutas relativas son las suyas). */
  static JobMeta cargar( File kjb, File original ) throws ToolError {
    JobMeta jm;
    try {
      jm = new JobMeta( kjb.getAbsolutePath(), null );
    } catch ( Exception e ) {
      throw new ToolError( "Cannot load " + original.getName() + ": " + RunMotor.primeraLinea( e ) );
    }
    if ( !kjb.equals( original ) ) {
      jm.setFilename( original.getAbsolutePath() );
    }
    jm.setInternalKettleVariables();
    return jm;
  }

  /**
   * El job antes de correr: skip_entry (un Success con el nombre de la entrada), sus conexiones a las bases de prueba
   * (y los esquemas y el SQL de sus entradas) y sin tablas de log.
   */
  static void prepararJob( Plan plan, JobMeta jm ) throws Exception {
    for ( DefPrueba.Ajuste a : plan.d.ajustes ) {
      if ( !a.deEntrada() ) {
        continue;
      }
      boolean alguna = false;
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        if ( c.getName() == null || !c.getName().trim().equalsIgnoreCase( a.entrada.trim() ) ) {
          continue;
        }
        if ( c.isStart() ) {
          throw new ToolError( "skip_entry '" + a.entrada + "': the START entry cannot be skipped" );
        }
        JobEntrySuccess s = new JobEntrySuccess( c.getName(), c.getDescription() );
        s.setParentJobMeta( jm );
        c.setEntry( s );
        alguna = true;
      }
      plan.notas.add( alguna ? "entry '" + a.entrada + "' skipped (it does nothing and the job goes on)"
        : "skip_entry '" + a.entrada + "' ignored: there is no such entry in the job" );
    }
    Set<String> usadas = new HashSet<String>();
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      for ( DatabaseMeta x : usadasDe( c.getEntry() ) ) {
        if ( x.getName() != null ) {
          usadas.add( x.getName().toLowerCase( Locale.ROOT ) );
        }
      }
    }
    if ( plan.bases != null ) {
      PruebasMotor.Redireccion red = PruebasMotor.redirigir( jm.getDatabases(), usadas, plan.pk, plan.bases,
        plan.notas );
      if ( !red.conexiones.isEmpty() ) {
        Set<String> pruebas = plan.bases.nombres();
        Set<String> prohibidas = plan.bases.prohibidas();
        Map<String, String> ren = plan.bases.renombres();
        for ( Map.Entry<String, String> o : red.originales.entrySet() ) {
          ren.putIfAbsent( o.getKey(), o.getValue() );
        }
        for ( JobEntryCopy c : jm.getJobCopies() ) {
          JobEntryInterface e = c.getEntry();
          CorrectorBases.Destino dest = null;
          for ( DatabaseMeta x : usadasDe( e ) ) {
            if ( dest == null && x.getName() != null ) {
              dest = red.conexiones.get( x.getName().toLowerCase( Locale.ROOT ) );
            }
          }
          if ( dest == null ) {
            continue;
          }
          Element x = Nodos.parse( "<entry>" + e.getXML() + "</entry>" );
          List<String> redir = new ArrayList<String>();
          List<CorrectorPolitica.Hallazgo> mal = new ArrayList<CorrectorPolitica.Hallazgo>();
          CorrectorSandbox.esquemasDeStep( x, c.getName(), e.getPluginId(), dest, PruebasMotor.variablesDe( jm,
            plan.variables ), pruebas, prohibidas, ren, redir, mal );
          if ( !mal.isEmpty() ) {
            throw new ToolError( "The test does not run: " + mal.get( 0 ).detalle() + " (a test only reads and writes "
              + "its test databases)" );
          }
          if ( !redir.isEmpty() ) {
            e.loadXML( x, jm.getDatabases(), jm.getSlaveServers(), null, null );
            plan.notas.addAll( redir );
          }
        }
      }
    } else {
      for ( DatabaseMeta dm : jm.getDatabases() ) {
        if ( dm.getName() != null && usadas.contains( dm.getName().toLowerCase( Locale.ROOT ) ) ) {
          plan.notas.add( "connection '" + dm.getName() + "' of the job is the real one (the test has no "
            + "test_databases)" );
        }
      }
    }
    jm.setJobLogTable( JobLogTable.getDefault( jm, jm ) );
    jm.setJobEntryLogTable( JobEntryLogTable.getDefault( jm, jm ) );
    jm.setChannelLogTable( ChannelLogTable.getDefault( jm, jm ) );
  }

  private static DatabaseMeta[] usadasDe( JobEntryInterface e ) {
    try {
      DatabaseMeta[] d = e == null ? null : e.getUsedDatabaseConnections();
      return d == null ? new DatabaseMeta[ 0 ] : d;
    } catch ( Throwable t ) {
      return new DatabaseMeta[ 0 ];
    }
  }

  /**
   * Corre el job (las veces del caso) con el gancho activo; deja el estado en {@code run} y, si termino bien, las
   * tablas de las bases de prueba y los archivos leidos en el plan. Las bases se recrean antes de la primera vez.
   */
  static void ejecutar( final Plan plan, final JobMeta jm, final ResultadoCaso.Corrida run ) throws Exception {
    if ( plan.bases != null ) {
      try {
        plan.bases.recrear( plan.d.caso );
      } catch ( CorrectorBases.Falla f ) {
        throw new ToolError( "Test database: " + f.getMessage() );
      }
    }
    final long inicio = System.currentTimeMillis();
    PruebasGancho.con( plan, () -> {
      Map<DefPrueba.ArchivoSalida, Object[]> antes = null;
      for ( int vez = 1; vez <= plan.veces; vez++ ) {
        boolean ultima = vez == plan.veces;
        for ( Captura c : plan.capturas ) {
          c.escucha = null;
          c.vista = false;
          c.falta = null;
        }
        plan.preparadas.clear();
        plan.entradasVistas.clear();
        plan.error = null;
        Job j = new Job( null, jm, RunMotor.padreDeLog() );
        j.setLogLevel( LogLevel.BASIC );
        j.initializeVariablesFrom( null );
        jm.setInternalKettleVariables( j );
        j.copyParametersFrom( jm );
        List<String> declarados = Arrays.asList( jm.listParameters() );
        for ( Map.Entry<String, String> e : plan.parametros.entrySet() ) {
          if ( declarados.contains( e.getKey() ) ) {
            j.setParameterValue( e.getKey(), e.getValue() );
          }
        }
        j.activateParameters();
        j.setVariable( VARIABLE_DIR, plan.dirTemporal.getAbsolutePath() );
        for ( Map.Entry<String, String> e : plan.parametros.entrySet() ) {
          if ( !declarados.contains( e.getKey() ) ) {
            j.setVariable( e.getKey(), j.environmentSubstitute( e.getValue() ) );
            if ( vez == 1 ) {
              plan.notas.add( "parameter '" + e.getKey() + "' is not declared by the job: set as a variable" );
            }
          }
        }
        for ( Map.Entry<String, String> e : plan.variables.entrySet() ) {
          j.setVariable( e.getKey(), j.environmentSubstitute( e.getValue() ) );
        }
        if ( ultima && plan.leerResultados && !plan.d.archivos.isEmpty() ) {
          antes = PruebasMotor.estadoArchivos( plan.d, plan.dir, j );
        }
        String canal = null;
        try {
          long limite = System.currentTimeMillis() + plan.timeoutMs;
          j.start();
          canal = j.getLogChannelId();
          RunMotor.dormir( 50 );
          String corte = null;
          // un Abort deja el job parado (stopped) sin marcarlo finished: tambien termino
          while ( !j.isFinished() && !( j.isStopped() && !j.isActive() ) ) {
            if ( plan.op.cancelada.getAsBoolean() ) {
              corte = "cancelled";
              break;
            }
            if ( System.currentTimeMillis() > limite ) {
              corte = ResultadoCaso.TIEMPO_AGOTADO;
              break;
            }
            if ( plan.excedida() ) {
              corte = ResultadoCaso.DEMASIADAS_FILAS;
              break;
            }
            RunMotor.dormir( PruebasMotor.MS_PASO );
          }
          if ( corte != null ) {
            j.stopAll();
            j.waitUntilFinished( 10000 );
            run.estado = "cancelled".equals( corte ) ? ResultadoCaso.TIEMPO_AGOTADO : corte;
            run.detalle = "cancelled".equals( corte ) ? "cancelled" : ResultadoCaso.TIEMPO_AGOTADO.equals( corte )
              ? "the job did not finish in " + ( plan.timeoutMs / 1000 ) + " s (environment.timeout_s)"
              : "a captured step produced more than " + plan.maxFilas + " rows (environment.max_rows)";
          }
          Result res = j.getResult();
          long errores = res == null ? j.getErrors() : Math.max( res.getNrErrors(), j.getErrors() );
          boolean ok = res != null && res.getResult() && errores == 0;
          run.segundos = Math.round( ( System.currentTimeMillis() - inicio ) / 10.0 ) / 100.0;
          run.errores = errores;
          if ( run.estado == null ) {
            run.estado = ok ? ResultadoCaso.TERMINO : ResultadoCaso.TERMINO_CON_ERRORES;
            if ( !ok ) {
              run.detalle = detalle( j, plan );
            }
          }
          run.log = RunMotor.colaLog( canal, !ok );
          if ( !ultima && !ResultadoCaso.TERMINO.equals( run.estado ) ) {
            return;
          }
          if ( !ultima ) {
            run.estado = null;
            run.errores = null;
            run.detalle = null;
          }
        } finally {
          RunMotor.liberarLog( canal );
        }
      }
      if ( !ResultadoCaso.TERMINO.equals( run.estado ) || !plan.leerResultados ) {
        return;
      }
      long filas = 0;
      List<String> desde = new ArrayList<String>();
      for ( Captura c : plan.capturas ) {
        CorrectorMotor.Escucha e = c.escucha;
        if ( e != null ) {
          filas += e.filas.size();
          desde.add( DefPrueba.nombreCompleto( c.entrada, c.nombreReal ) );
        }
      }
      for ( TablaSalida ts : plan.d.tablas ) {
        try {
          CorrectorBases.Captura c = plan.bases.capturar( ts, plan.maxFilas );
          plan.tablas.put( ts.clave(), c );
          filas += c.tabla.cantidadFilas();
          desde.add( "table " + ts.nombre );
        } catch ( CorrectorBases.Falla f ) {
          throw new ToolError( "Test database: " + f.getMessage() );
        }
      }
      if ( antes != null ) {
        plan.archivos.putAll( PruebasMotor.leerArchivos( plan.d, antes ) );
        for ( PruebasMotor.ArchivoLeido l : plan.archivos.values() ) {
          if ( l.tabla != null ) {
            filas += l.tabla.cantidadFilas();
            desde.add( "file " + l.archivo.getName() );
          }
        }
      }
      run.filasCapturadas = filas;
      run.capturadoDe = String.join( ", ", desde );
    } );
  }

  /** Por que fallo el job: el error del gancho y las entradas que fallaron (con su comentario). */
  private static String detalle( Job j, Plan plan ) {
    List<String> l = new ArrayList<String>();
    if ( plan.error != null ) {
      l.add( plan.error );
    }
    try {
      for ( JobEntryResult er : new ArrayList<JobEntryResult>( j.getJobEntryResults() ) ) {
        Result res = er.getResult();
        if ( res != null && ( !res.getResult() || res.getNrErrors() > 0 ) ) {
          l.add( "entry '" + er.getJobEntryName() + "' failed" + ( er.getComment() == null || er.getComment().isEmpty()
            ? "" : " (" + er.getComment() + ")" ) );
        }
      }
    } catch ( Throwable ignored ) {
      // sin resultados por entrada
    }
    return l.isEmpty() ? "the job ended with result false" : String.join( "; ", l );
  }

  // ================================================================ crear (snapshot)

  /** Una salida o entrada pedida ("Entrada :: Step") ya resuelta contra el job. */
  private static final class Pedida {
    String entrada;
    String step;
  }

  /**
   * Crea una prueba de job (ver la clase). {@code pc.salidas} y {@code pc.entradas}: "Entrada :: Step" (sin entrada, la
   * unica entrada Transformation que tiene ese step). Llama {@link PruebasMotor#crearInterno}.
   */
  static ResultadoPrueba crear( PruebasMotor.Pedido pc, PruebasMotor.Opciones op, File archivo, List<File> escritos )
    throws Exception {
    File dir = pc.carpeta.getAbsoluteFile();
    File kjb = op.ktr != null ? op.ktr : pc.original;
    if ( !kjb.isFile() ) {
      throw new ToolError( "The job " + pc.original + " does not exist" );
    }
    DefPrueba d = new DefPrueba();
    d.esquema = DefPrueba.ESQUEMA;
    d.nombre = pc.nombre;
    d.descripcion = pc.descripcion;
    d.job = PruebasMotor.relativa( dir, pc.original );
    d.entorno = pc.entorno == null ? new DefPrueba.Entorno() : pc.entorno;
    d.caso = new Caso();
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
    Pack pk = d.comoPack();

    // las transformaciones de las entradas Transformation del job
    JobMeta jm = cargar( kjb, pc.original );
    Map<String, TransMeta> trans = new LinkedHashMap<String, TransMeta>();
    List<String> sinCargar = new ArrayList<String>();
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      if ( c.getEntry() instanceof JobEntryTrans && !trans.containsKey( c.getName() ) ) {
        try {
          trans.put( c.getName(), ( (JobEntryTrans) c.getEntry() ).getTransMeta( null, null, jm ) );
        } catch ( Exception e ) {
          sinCargar.add( c.getName() + " (" + RunMotor.primeraLinea( e ) + ")" );
        }
      }
    }
    List<Pedida> salidas = resolver( pc.salidas, trans, sinCargar, "output" );
    if ( salidas.isEmpty() && pc.tablas.isEmpty() && pc.archivos.isEmpty() ) {
      throw new ToolError( "Choose at least one output: 'Entry :: Step', a table or a file" );
    }
    List<Pedida> entradas = new ArrayList<Pedida>();
    if ( pc.entradas != null ) {
      entradas.addAll( resolver( pc.entradas, trans, sinCargar, "input" ) );
      for ( Pedida e : entradas ) {
        for ( Pedida s : salidas ) {
          if ( mismo( e.entrada, s.entrada ) && mismo( e.step, s.step ) ) {
            throw new ToolError( "'" + DefPrueba.nombreCompleto( e.entrada, e.step ) + "' cannot be both an input "
              + "(dataset) and an output" );
          }
        }
      }
    } else { // las fuentes que llegan a las salidas de cada entrada
      for ( Map.Entry<String, TransMeta> t : trans.entrySet() ) {
        List<StepMeta> obj = new ArrayList<StepMeta>();
        for ( Pedida s : salidas ) {
          if ( mismo( s.entrada, t.getKey() ) ) {
            obj.add( t.getValue().findStep( s.step ) );
          }
        }
        if ( obj.isEmpty() ) {
          continue;
        }
        for ( StepMeta sm : PruebasMotor.ancestros( t.getValue(), obj ) ) {
          if ( !obj.contains( sm ) && PruebasMotor.entra( t.getValue(), sm ) == 0 && !PruebasMotor.FUENTES_FIJAS
            .contains( sm.getStepID() ) && !PruebasMotor.conBaseDePrueba( sm, pk ) ) {
            Pedida p = new Pedida();
            p.entrada = t.getKey();
            p.step = sm.getName();
            entradas.add( p );
          }
        }
      }
    }

    // ajustes: los pedidos, o los que hacen que crear no escriba ni hable con el exterior
    ResultadoPrueba.Creada creada = new ResultadoPrueba.Creada();
    creada.avisos.addAll( pc.avisos );
    if ( pc.ajustes != null ) {
      d.ajustes.addAll( pc.ajustes );
    } else {
      boolean escribe = !d.archivos.isEmpty() || !d.tablas.isEmpty();
      Set<String> saltadas = new HashSet<String>();
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        JobEntryInterface e = c.getEntry();
        String id = e == null ? "" : String.valueOf( e.getPluginId() ).toUpperCase( Locale.ROOT );
        String por = null;
        if ( EXTERIORES.contains( id ) ) {
          por = "talks to the outside (" + id + ")";
        } else if ( !escribe && DE_ARCHIVOS.contains( id ) ) {
          por = "changes files (" + id + ")";
        } else if ( ESCRIBEN_EN_BASE.contains( id ) && !conBasesDePrueba( usadasDe( e ), pk ) ) {
          por = "writes to a database without a test database (" + id + ")";
        } else if ( "SHELL".equals( id ) ) {
          creada.avisos.add( "The entry '" + c.getName() + "' runs a shell script: it also runs in the test (add a "
            + "skip_entry tweak if it must not)" );
        }
        if ( por != null && saltadas.add( PruebasMotor.clave( c.getName() ) ) ) {
          d.ajustes.add( DefPrueba.Ajuste.saltarEntrada( c.getName(), por ) );
        }
      }
      if ( !escribe ) {
        for ( Map.Entry<String, TransMeta> t : trans.entrySet() ) {
          for ( StepMeta sm : t.getValue().getSteps() ) {
            boolean esEntrada = false;
            for ( Pedida p : entradas ) {
              esEntrada |= mismo( p.entrada, t.getKey() ) && mismo( p.step, sm.getName() );
            }
            if ( !esEntrada && RunMotor.escribe( sm ) && !PruebasMotor.conBaseDePrueba( sm, pk ) ) {
              DefPrueba.Ajuste a = DefPrueba.Ajuste.saltar( sm.getName(), "writes outside the transformation ("
                + sm.getStepID() + ")" );
              a.entrada = t.getKey();
              d.ajustes.add( a );
            }
          }
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
    for ( Pedida s : salidas ) {
      DefPrueba.Salida x = new DefPrueba.Salida( s.step );
      x.entrada = s.entrada;
      List<String> k = pc.claves.get( PruebasMotor.clave( DefPrueba.nombreCompleto( s.entrada, s.step ) ) );
      if ( k == null ) {
        k = pc.claves.get( PruebasMotor.clave( s.step ) );
      }
      if ( k != null ) {
        x.comparar.columnasClave.addAll( k );
      }
      List<String> ig = pc.ignorar.get( PruebasMotor.clave( DefPrueba.nombreCompleto( s.entrada, s.step ) ) );
      if ( ig == null ) {
        ig = pc.ignorar.get( PruebasMotor.clave( s.step ) );
      }
      if ( ig != null ) {
        x.ignorar.addAll( ig );
      }
      d.salidas.add( x );
    }
    if ( !sinCargar.isEmpty() ) {
      creada.avisos.add( "Some Transformation entries could not be loaded: " + sinCargar );
    }

    // 1) en vivo: las entradas y las salidas tal como salen hoy
    DefPrueba enVivo = CorrectorJson.copia( d, DefPrueba.class ).completar();
    Plan vivo = plan( enVivo, dir, op, false );
    vivo.leerResultados = false;
    for ( Pedida p : entradas ) {
      vivo.capturas.add( new Captura( p.entrada, p.step ) );
    }
    for ( Pedida p : salidas ) {
      vivo.capturas.add( new Captura( p.entrada, p.step ) );
    }
    ResultadoCaso.Corrida run = new ResultadoCaso.Corrida();
    try {
      JobMeta j1 = cargar( kjb, pc.original );
      prepararJob( vivo, j1 );
      ejecutar( vivo, j1, run );
    } finally {
      PruebasProcesos.borrar( vivo.dirTemporal );
    }
    if ( op.cancelada.getAsBoolean() ) {
      throw new ToolError( "Cancelled" );
    }
    if ( !ResultadoCaso.TERMINO.equals( run.estado ) ) {
      throw new ToolError( "The job does not run cleanly, so nothing was frozen (" + run.estado + "): "
        + ( run.detalle == null ? "" : run.detalle ) + ( pc.ajustes == null ? ". The default tweaks skip what writes: "
        + "if later entries read what earlier ones write, pass the tweaks yourself (tweaks: [] runs everything) or use "
        + "test_databases" : "" ) + ( run.log == null ? "" : "\n" + run.log ) );
    }

    // 2) los datasets
    File dirDatos = new File( new File( dir, "datasets" ), pc.nombre );
    for ( Pedida p : entradas ) {
      Captura c = vivo.captura( p.entrada, p.step );
      if ( c == null || !c.vista ) {
        creada.avisos.add( "'" + DefPrueba.nombreCompleto( p.entrada, p.step ) + "' did not run: no dataset" );
        continue;
      }
      RowMetaInterface rm = c.escucha == null ? null : c.escucha.meta;
      if ( rm == null ) {
        rm = c.tm.getStepFields( c.nombreReal );
      }
      File f = new File( dirDatos, DefPrueba.archivoDe( p.entrada + "_" + p.step ) + ".csv" );
      escritos.add( f );
      int n = PruebasDatasets.escribirDataset( f, rm, c.escucha == null ? new ArrayList<Object[]>() : c.escucha.filas );
      if ( c.escucha != null && c.escucha.excedida ) {
        creada.avisos.add( "'" + DefPrueba.nombreCompleto( p.entrada, p.step ) + "' produced more than "
          + d.entorno.maxFilas + " rows: the dataset keeps the first ones (raise environment.max_rows)" );
      }
      DefPrueba.Dataset ds = new DefPrueba.Dataset( p.step, PruebasMotor.relativa( dir, f ) );
      ds.entrada = p.entrada;
      ds.columnas.addAll( PruebasDatasets.columnas( rm ) );
      d.datasets.add( ds );
      List<String> cols = new ArrayList<String>();
      for ( DefPrueba.ColumnaDataset cd : ds.columnas ) {
        cols.add( cd.nombre );
      }
      creada.datasets.add( new ResultadoPrueba.Archivo( DefPrueba.nombreCompleto( p.entrada, p.step ), ds.archivo, n,
        cols ) );
    }

    // 3) con los datasets: los dorados
    Plan rep = plan( d, dir, op, true );
    for ( DefPrueba.Salida s : d.salidas ) {
      rep.capturas.add( new Captura( s.entrada, s.step ) );
    }
    ResultadoCaso.Corrida run2 = new ResultadoCaso.Corrida();
    try {
      JobMeta j2 = cargar( kjb, pc.original );
      prepararJob( rep, j2 );
      ejecutar( rep, j2, run2 );
    } finally {
      PruebasProcesos.borrar( rep.dirTemporal );
    }
    if ( op.cancelada.getAsBoolean() ) {
      throw new ToolError( "Cancelled" );
    }
    if ( !ResultadoCaso.TERMINO.equals( run2.estado ) ) {
      throw new ToolError( "With the frozen datasets the job does not run cleanly (" + run2.estado + "): "
        + ( run2.detalle == null ? "" : run2.detalle ) );
    }
    File dirDorado = PruebasMotor.resolver( dir, d.caso.esperado );
    for ( DefPrueba.Salida s : d.salidas ) {
      Captura c = rep.captura( s.entrada, s.step );
      if ( c == null || !c.vista ) {
        throw new ToolError( "The job did not run the entry '" + s.entrada + "' (or its step '" + s.step + "'): "
          + "nothing to freeze" );
      }
      CorrectorTabla t = c.tabla();
      s.step = c.nombreReal;
      s.columnas.clear();
      s.columnas.addAll( PruebasDatasets.tipos( t.columnas, c.escucha == null ? null : c.escucha.meta ) );
      File f = PruebasMotor.dorado( dir, d, s );
      escritos.add( f );
      PruebasDatasets.escribirDorado( f, t, d.csvDorado );
      creada.dorados.add( new ResultadoPrueba.Archivo( DefPrueba.nombreCompleto( s.entrada, s.step ),
        PruebasMotor.relativa( dir, f ), t.cantidadFilas(), t.columnas ) );
      Captura v = vivo.captura( s.entrada, s.step );
      PruebasMotor.estabilidad( s, t, v == null ? CorrectorTabla.vacia( new ArrayList<String>() ) : v.tabla(), d,
        pc.ignorarInestables, creada );
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
      creada.dorados.add( new ResultadoPrueba.Archivo( "table " + ts.nombre, PruebasMotor.relativa( dir, f ), c.tabla
        .cantidadFilas(), c.tabla.columnas ) );
    }
    for ( DefPrueba.ArchivoSalida a : d.archivos ) {
      PruebasMotor.ArchivoLeido l = rep.archivos.get( a );
      if ( l == null || l.tabla == null ) {
        throw new ToolError( "The file " + a.ruta + " cannot be frozen: " + ( l == null ? "it was not read"
          : l.problema ) );
      }
      File f = PruebasMotor.dorado( dir, d, a );
      escritos.add( f );
      PruebasDatasets.escribirDorado( f, l.tabla, d.csvDorado );
      creada.dorados.add( new ResultadoPrueba.Archivo( "file " + a.nombre(), PruebasMotor.relativa( dir, f ), l.tabla
        .cantidadFilas(), l.tabla.columnas ) );
    }
    creada.ajustes.addAll( d.ajustes );
    escritos.add( archivo );
    PruebasMotor.guardar( d, archivo );

    // 4) la prueba recien creada, corrida como cualquier otra
    ResultadoPrueba r = PruebasMotor.correr( archivo, op );
    r.creada = creada;
    if ( !r.paso() && ResultadoPrueba.FALLO.equals( r.estado ) ) {
      creada.avisos.add( "The new test does not pass on its own frozen data: " + r.mensaje );
    }
    return r;
  }

  /** "Entrada :: Step" contra las transformaciones del job; sin entrada, la unica que tiene ese step. */
  private static List<Pedida> resolver( List<String> pedidas, Map<String, TransMeta> trans, List<String> sinCargar,
                                        String que ) throws ToolError {
    List<Pedida> l = new ArrayList<Pedida>();
    for ( String x : pedidas ) {
      String[] es = DefPrueba.partir( x );
      Pedida p = new Pedida();
      if ( es[ 0 ] == null || es[ 0 ].isEmpty() ) {
        List<String> con = new ArrayList<String>();
        for ( Map.Entry<String, TransMeta> t : trans.entrySet() ) {
          if ( PruebasMotor.buscar( t.getValue(), es[ 1 ] ) != null ) {
            con.add( t.getKey() );
          }
        }
        if ( con.size() != 1 ) {
          throw new ToolError( "The " + que + " '" + x + "' must say its Transformation entry: 'Entry :: Step'"
            + ( con.isEmpty() ? " (no entry has that step; entries: " + trans.keySet() + ")" : " (it is in "
            + con + ")" ) );
        }
        es[ 0 ] = con.get( 0 );
      }
      TransMeta tm = null;
      for ( Map.Entry<String, TransMeta> t : trans.entrySet() ) {
        if ( t.getKey().trim().equalsIgnoreCase( es[ 0 ] ) ) {
          p.entrada = t.getKey();
          tm = t.getValue();
        }
      }
      if ( tm == null ) {
        throw new ToolError( "There is no Transformation entry '" + es[ 0 ] + "' in the job. Entries: "
          + trans.keySet() + ( sinCargar.isEmpty() ? "" : "; not loaded: " + sinCargar ) );
      }
      StepMeta sm = PruebasMotor.buscar( tm, es[ 1 ] );
      if ( sm == null ) {
        throw new ToolError( "There is no step '" + es[ 1 ] + "' in the transformation of the entry '" + p.entrada
          + "'. Steps: " + PruebasMotor.limitar( PruebasMotor.nombresSteps( tm ), 40 ) );
      }
      p.step = sm.getName();
      l.add( p );
    }
    return l;
  }

  private static boolean conBasesDePrueba( DatabaseMeta[] usadas, Pack pk ) {
    if ( pk.bases == null || pk.bases.isEmpty() || usadas.length == 0 ) {
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

  /**
   * Las transformaciones de las entradas Transformation de un job abierto o en disco, para la ventana y test_list:
   * entrada → TransMeta (las que no cargan, fuera).
   */
  static Map<String, TransMeta> transformaciones( JobMeta jm ) {
    Map<String, TransMeta> m = new LinkedHashMap<String, TransMeta>();
    try {
      JobMeta copia = (JobMeta) jm.realClone( false );
      copia.setInternalKettleVariables();
      for ( JobEntryCopy c : copia.getJobCopies() ) {
        if ( c.getEntry() instanceof JobEntryTrans && !m.containsKey( c.getName() ) ) {
          try {
            m.put( c.getName(), ( (JobEntryTrans) c.getEntry() ).getTransMeta( null, null, copia ) );
          } catch ( Exception ignored ) {
            // una entrada que no carga no se ofrece
          }
        }
      }
    } catch ( Throwable ignored ) {
      // sin entradas
    }
    return m;
  }
}
