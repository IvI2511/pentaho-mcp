package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.logging.LoggingObjectType;
import org.pentaho.di.core.logging.SimpleLoggingObject;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.insertupdate.InsertUpdateMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.metastore.api.IMetaStore;
import org.w3c.dom.Node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.LeccionLayout.Medidor;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.Plano.Destino;
import io.github.ivi2511.pentahomcp.Plano.Etapa;
import io.github.ivi2511.pentahomcp.Plano.Fuente;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasBoceto;

/**
 * plan_build del job de la orquestación (0.7.1): START, la comprobación de los ficheros de entrada, las carpetas de
 * salida, las tablas destino (CREATE TABLE sacado de las transformaciones ya construidas), una entrada TRANS por
 * transformación en orden de dependencias (las dimensiones antes que los hechos), SUCCESS al final y el camino de falla
 * que dice el plano (Write to log + Abort; con «continue», hops incondicionales), con una nota que lo explica.
 * <ul>
 * <li><b>sketch</b>: START y un marcador (DUMMY) por entrada con su nota PENDIENTE y los hops con su condición; cada
 * marcador guarda la entrada planeada y fill_placeholder sin 'type' la pone.</li>
 * <li><b>steps</b> (o full): las entradas configuradas; lo que no se puede configurar (el CREATE TABLE de una tabla cuya
 * transformación no está construida) queda como marcador.</li>
 * </ul>
 * Los jobs de Spoon no encadenan acciones de undo (ver UndoGrupo): el job se construye en un documento NUEVO (cerrarlo
 * sin guardar lo deshace). Si ya hay uno abierto enlazado al plano (o se pasa 'document'), se agregan solo las entradas
 * que faltan y todo queda en UN Ctrl+Z con {@link Lienzo}: un cambio de la entrada START cuyo objeto guardado, al
 * clonarse para deshacer o rehacer, deja el job como en su foto.
 */
final class PlanoJob {

  /** Claves del registro del plano en el job (grupo ReglasBoceto.GRUPO). */
  static final String K_JOB = "job";
  static final String K_TIPO = "planned_type";
  static final String K_CONFIG = "planned_config";

  static final String FILA = "main";
  static final String FALLA = "failure";

  /** Separación entre entradas (como el job de referencia de Grano de Oro). */
  static final int PASO_X = 160;
  static final int X0 = 96;

  private static final Set<String> FORMATOS_ARCHIVO = new LinkedHashSet<String>( java.util.Arrays.asList( "csv",
    "text", "excel", "json", "xml" ) );

  private PlanoJob() {
  }

  // ================================================================ qué construir

  /** Una entrada planeada. */
  static final class Entrada {
    /** start, check_files, folder:<ruta>, tables, trans:<nombre>, notify, success, log, abort. */
    String clave;
    String nombre;
    /** START, DUMMY o el id de la entrada (FILES_EXIST, CREATE_FOLDER, SQL, TRANS...). */
    String tipo;
    /** Las etiquetas de configuración (sin envoltorio). */
    String config = "";
    String descripcion = "";
    /** Las líneas de la nota PENDIENTE (en sketch, o si no se puede configurar). */
    final List<String> todo = new ArrayList<String>();
    /** FILA o FALLA. */
    String fila = FILA;
    /** No se puede configurar (queda marcador también en mode=steps). */
    boolean marcador;
    int x;
    int y;
  }

  /** Un hop planeado: desde, hacia y always | on_success | on_failure. */
  static final class Salto {
    final String desde;
    final String hacia;
    final String condicion;

    Salto( String desde, String hacia, String condicion ) {
      this.desde = desde;
      this.hacia = hacia;
      this.condicion = condicion;
    }
  }

  /** El job planeado. */
  static final class Pedido {
    String nombre;
    String modo;
    File carpeta;
    final List<Entrada> entradas = new ArrayList<Entrada>();
    final List<Salto> saltos = new ArrayList<Salto>();
    final List<String> transformaciones = new ArrayList<String>();
    final List<String> avisos = new ArrayList<String>();
    String nota;
    /** La conexión del SQL de las tablas (copia), o null. */
    DatabaseMeta conexion;
    final List<String> tablas = new ArrayList<String>();
    final List<String> tablasSinDdl = new ArrayList<String>();

    Entrada entrada( String clave ) {
      for ( Entrada e : entradas ) {
        if ( e.clave.equals( clave ) ) {
          return e;
        }
      }
      return null;
    }
  }

  /** true si el plano tiene un job (orchestration con nombre). */
  static boolean tieneJob( Plano p ) {
    return p.orquestacion != null && p.orquestacion.nombre != null && !p.orquestacion.nombre.trim().isEmpty();
  }

  /** true si 'pedida' nombra el job de la orquestación (sin mayúsculas ni .kjb). */
  static boolean esElJob( Plano p, String pedida ) {
    if ( pedida == null || !tieneJob( p ) ) {
      return false;
    }
    String x = pedida.trim().replace( '\\', '/' );
    x = x.substring( x.lastIndexOf( '/' ) + 1 ).replaceAll( "(?i)\\.kjb$", "" );
    return x.equalsIgnoreCase( p.orquestacion.nombre.trim() );
  }

  /** La carpeta del job y de los .ktr: orchestration.folder (relativa al plano) o la del plano. */
  static File carpeta( Plano p ) {
    File base = p.archivo == null ? new File( "." ).getAbsoluteFile() : p.archivo.getAbsoluteFile().getParentFile();
    if ( p.orquestacion != null && p.orquestacion.carpeta != null ) {
      File c = new File( p.orquestacion.carpeta );
      return c.isAbsolute() ? c : new File( base, p.orquestacion.carpeta );
    }
    return base;
  }

  /**
   * Las transformaciones del job en orden: el de orchestration.order (con un aviso si contradice una dependencia) o, sin
   * él, el de las dependencias (una transformación que lee o busca un destino va después de la que lo escribe) y, a
   * igualdad, primero las que cargan dimensiones, después las demás tablas y al final los hechos.
   */
  static List<String> orden( Plano p, List<String> avisos ) {
    List<String> todas = new ArrayList<String>();
    for ( String t : p.transformaciones() ) {
      if ( !t.isEmpty() ) {
        todas.add( t );
      }
    }
    Map<String, Set<String>> deps = new LinkedHashMap<String, Set<String>>();
    for ( String t : todas ) {
      Set<String> d = new LinkedHashSet<String>();
      for ( Etapa e : p.etapasDe( t ) ) {
        List<String> usa = new ArrayList<String>( e.lee );
        if ( e.busca != null ) {
          usa.add( e.busca );
        }
        for ( String id : usa ) {
          if ( p.destino( id ) == null ) {
            continue;
          }
          for ( String o : todas ) {
            if ( o.equals( t ) ) {
              continue;
            }
            for ( Etapa x : p.etapasDe( o ) ) {
              if ( x.escribe.contains( id ) ) {
                d.add( o );
              }
            }
          }
        }
      }
      deps.put( t, d );
    }
    List<String> out = new ArrayList<String>();
    if ( p.orquestacion != null && !p.orquestacion.orden.isEmpty() ) {
      for ( String o : p.orquestacion.orden ) {
        String t = p.buscarTransformacion( o );
        if ( t != null && !out.contains( t ) ) {
          out.add( t );
        }
      }
      for ( int i = 0; i < out.size(); i++ ) {
        for ( String d : deps.get( out.get( i ) ) ) {
          if ( out.indexOf( d ) > i ) {
            avisos.add( "orchestration.order runs '" + out.get( i ) + "' before '" + d + "', but it reads what '" + d
              + "' writes. The job keeps the plan's order: fix orchestration.order if it is a mistake." );
          }
        }
      }
      List<String> faltan = new ArrayList<String>();
      for ( String t : todas ) {
        if ( !out.contains( t ) ) {
          faltan.add( t );
        }
      }
      if ( !faltan.isEmpty() ) {
        avisos.add( "orchestration.order does not list " + faltan + ": they go at the end, in dependency order." );
        out.addAll( topologico( p, faltan, deps ) );
      }
      return out;
    }
    return topologico( p, todas, deps );
  }

  private static List<String> topologico( Plano p, List<String> ts, Map<String, Set<String>> deps ) {
    List<String> out = new ArrayList<String>();
    List<String> resto = new ArrayList<String>( ts );
    while ( !resto.isEmpty() ) {
      String elegida = null;
      for ( String t : resto ) {
        boolean lista = true;
        for ( String d : deps.get( t ) ) {
          lista &= !resto.contains( d ) || !ts.contains( d );
        }
        if ( lista && ( elegida == null || rango( p, t ) < rango( p, elegida ) ) ) {
          elegida = t;
        }
      }
      if ( elegida == null ) {
        elegida = resto.get( 0 ); // un ciclo entre transformaciones: el orden del plano
      }
      out.add( elegida );
      resto.remove( elegida );
    }
    return out;
  }

  /** 0 dimensiones, 1 tablas y otros, 2 hechos, 3 solo archivos. */
  private static int rango( Plano p, String t ) {
    int r = 3;
    for ( Etapa e : p.etapasDe( t ) ) {
      for ( String id : e.escribe ) {
        Destino d = p.destino( id );
        String c = d == null || d.clase == null ? "other" : d.clase;
        r = Math.min( r, "dimension".equals( c ) ? 0 : "fact".equals( c ) ? 2 : "file".equals( c ) ? 3 : 1 );
      }
    }
    return r;
  }

  /**
   * Arma el job planeado. abiertas = transformaciones abiertas en Spoon por nombre (minúsculas) que tienen prioridad
   * sobre los .ktr de la carpeta para sacar el CREATE TABLE; puede ser null.
   */
  static Pedido pedido( Plano p, String modo, String idioma, Map<String, TransMeta> abiertas ) throws ToolError {
    if ( !tieneJob( p ) ) {
      throw new ToolError( "The plan has no orchestration job: add orchestration {name, order, on_failure, notify} "
        + "(plan_create overwrite=true) and build again." );
    }
    Rotulos r = Rotulos.de( PlanoInforme.AREA, idioma );
    Pedido pd = new Pedido();
    pd.nombre = p.orquestacion.nombre.trim();
    pd.modo = modo;
    pd.carpeta = carpeta( p ).getAbsoluteFile();
    pd.transformaciones.addAll( orden( p, pd.avisos ) );
    if ( pd.transformaciones.isEmpty() ) {
      throw new ToolError( "The plan has no named transformations (stages[].transformation): nothing for the job to "
        + "run." );
    }
    boolean bosquejo = PlanoConstruir.SKETCH.equals( modo );
    Entrada ini = entrada( pd, "start", "START", JobSoporte.START, "" );
    ini.descripcion = "";
    File base = baseDeDatos( p, pd.carpeta );
    // 1. comprobar los ficheros de entrada
    List<String> archivos = new ArrayList<String>();
    for ( Fuente f : p.fuentes ) {
      if ( f.formato != null && FORMATOS_ARCHIVO.contains( f.formato ) && f.ubicacion != null && !f.ubicacion
        .contains( "://" ) && !f.ubicacion.contains( "${" ) ) {
        archivos.add( ruta( pd.carpeta, base, f.ubicacion ) );
      }
    }
    if ( !archivos.isEmpty() ) {
      Entrada e = entrada( pd, "check_files", r.s( "job.comprobar" ), "FILES_EXIST", "" );
      StringBuilder c = new StringBuilder( "<filename/><fields>" );
      for ( String a : archivos ) {
        c.append( "<field><name>" ).append( x( a ) ).append( "</name></field>" );
      }
      e.config = c.append( "</fields>" ).toString();
      e.descripcion = r.s( "job.comprobar.desc" );
      e.todo.add( r.f( "job.todo.archivos", String.join( ", ", nombres( archivos ) ) ) );
    }
    // 2. las carpetas de las salidas (archivos destino, rechazos y bases de archivo)
    Set<String> carpetas = new LinkedHashSet<String>();
    for ( Destino d : p.destinos ) {
      if ( d.ubicacion != null && ( "file".equals( d.clase ) || esBaseDeArchivo( d.ubicacion ) ) ) {
        carpetas.add( padre( d.ubicacion ) );
      }
    }
    for ( Fuente f : p.fuentes ) {
      if ( f.rechazos != null && f.rechazos.matches( ".*[/\\\\].*\\.[A-Za-z0-9]{1,5}$" ) ) {
        carpetas.add( padre( f.rechazos ) );
      }
    }
    carpetas.remove( "" );
    for ( String c : carpetas ) {
      String rel = ruta( pd.carpeta, base, c );
      Entrada e = entrada( pd, "folder:" + c, r.f( "job.carpeta", c ), "CREATE_FOLDER", "<foldername>" + x( rel )
        + "</foldername><fail_of_folder_exists>N</fail_of_folder_exists>" );
      e.descripcion = r.s( "job.carpeta.desc" );
      e.todo.add( r.f( "job.todo.carpeta", rel ) );
    }
    // 3. las tablas destino
    List<Destino> tablas = new ArrayList<Destino>();
    for ( Destino d : p.destinos ) {
      if ( esTabla( d ) ) {
        tablas.add( d );
      }
    }
    if ( !tablas.isEmpty() ) {
      Ddl ddl = ddl( p, pd.transformaciones, pd.carpeta, abiertas, tablas );
      pd.conexion = ddl.conexion;
      pd.tablas.addAll( ddl.hechas );
      pd.tablasSinDdl.addAll( ddl.faltan );
      Entrada e = entrada( pd, "tables", r.s( "job.tablas" ), "SQL", "" );
      e.descripcion = r.s( "job.tablas.desc" );
      List<String> ns = new ArrayList<String>();
      for ( Destino d : tablas ) {
        ns.add( d.nombre );
      }
      e.todo.add( r.f( "job.todo.tablas", String.join( ", ", ns ) ) );
      if ( ddl.conexion == null || ddl.hechas.isEmpty() ) {
        e.marcador = true;
        e.todo.add( r.f( "job.todo.tablas.sin_ddl", String.join( ", ", ddl.faltan ) ) );
        pd.avisos.add( "The CREATE TABLE of " + ddl.faltan + " could not be derived (build their transformations "
          + "first: plan_build them and save the .ktr in " + pd.carpeta.getName() + "). The tables entry is left as a "
          + "placeholder." );
      } else {
        if ( !ddl.faltan.isEmpty() ) {
          e.todo.add( r.f( "job.todo.tablas.sin_ddl", String.join( ", ", ddl.faltan ) ) );
          pd.avisos.add( "No CREATE TABLE for " + ddl.faltan + ": their transformation is not built or does not write "
            + "them. Add them to the SQL of '" + e.nombre + "'." );
        }
        e.config = "<sql>" + x( ddl.sql ) + "</sql><useVariableSubstitution>F</useVariableSubstitution><sqlfromfile>F"
          + "</sqlfromfile><sqlfilename/><sendOneStatement>F</sendOneStatement><connection>" + x( ddl.conexion
            .getName() ) + "</connection>";
      }
    }
    // 4. las transformaciones
    for ( String t : pd.transformaciones ) {
      Entrada e = entrada( pd, "trans:" + t, r.f( "job.trans", t ), "TRANS", "<specification_method>filename"
        + "</specification_method><filename>${Internal.Entry.Current.Directory}/" + x( t ) + ".ktr</filename>"
        + "<wait_until_finished>Y</wait_until_finished>" );
      List<String> escribe = new ArrayList<String>();
      for ( Etapa et : p.etapasDe( t ) ) {
        for ( String id : et.escribe ) {
          String n = p.nombreDe( id );
          if ( !escribe.contains( n ) ) {
            escribe.add( n );
          }
        }
      }
      e.descripcion = escribe.isEmpty() ? t : r.f( "job.trans.desc", String.join( ", ", escribe ) );
      e.todo.add( r.f( "job.todo.trans", t + ".ktr" ) );
      if ( !new File( pd.carpeta, t + ".ktr" ).isFile() && ( abiertas == null || !abiertas.containsKey( t.toLowerCase(
        Locale.ROOT ) ) ) ) {
        pd.avisos.add( t + ".ktr is not in " + pd.carpeta.getPath() + " yet: build it (plan_build transformation="
          + t + ") and save it there before running the job." );
      }
    }
    // 5. final y camino de falla
    String siFalla = p.orquestacion.siFalla == null ? "abort" : p.orquestacion.siFalla;
    List<Entrada> principales = new ArrayList<Entrada>( pd.entradas );
    Entrada ok = entrada( pd, "success", r.s( "job.exito" ), "SUCCESS", "" );
    String aviso = p.orquestacion.avisar;
    boolean correo = aviso != null && aviso.matches( "(?s).*[\\w.+-]+@[\\w-]+\\.[\\w.]+.*" );
    Entrada log = null;
    if ( !"continue".equals( siFalla ) ) {
      String mensaje = r.f( "job.log.mensaje", p.nombre );
      if ( aviso != null && !correo ) {
        mensaje = aviso + ". " + mensaje;
      }
      log = entrada( pd, "log", r.s( "job.log" ), "WRITE_TO_LOG", "<logmessage>" + x( mensaje ) + "</logmessage>"
        + "<loglevel>Error</loglevel><logsubject>" + x( p.nombre ) + "</logsubject>" );
      log.fila = FALLA;
      log.descripcion = r.s( "job.log.desc" );
      log.todo.add( r.f( "job.todo.mensaje", mensaje ) );
      Entrada antes = log;
      if ( correo || "notify".equals( siFalla ) ) {
        Entrada n = entrada( pd, "notify", r.f( "job.avisar", aviso == null ? "?" : aviso ), JobSoporte.DUMMY, "" );
        n.fila = FALLA;
        n.marcador = true;
        n.todo.add( r.f( "job.todo.avisar", aviso == null ? "?" : aviso ) );
        pd.saltos.add( new Salto( antes.nombre, n.nombre, JobSoporte.SIEMPRE ) );
        antes = n;
      }
      Entrada ab = entrada( pd, "abort", r.s( "job.abortar" ), "ABORT", "<message>" + x( r.s( "job.abortar.mensaje" ) )
        + "</message>" );
      ab.fila = FALLA;
      ab.descripcion = r.s( "job.abortar.desc" );
      ab.todo.add( r.f( "job.todo.mensaje", r.s( "job.abortar.mensaje" ) ) );
      pd.saltos.add( new Salto( antes.nombre, ab.nombre, JobSoporte.SIEMPRE ) );
    }
    principales.add( ok );
    for ( int i = 0; i + 1 < principales.size(); i++ ) {
      Entrada a = principales.get( i );
      String cond = i == 0 || "continue".equals( siFalla ) ? JobSoporte.SIEMPRE : JobSoporte.SI_OK;
      pd.saltos.add( i, new Salto( a.nombre, principales.get( i + 1 ).nombre, cond ) );
      if ( i > 0 && log != null ) {
        pd.saltos.add( new Salto( a.nombre, log.nombre, JobSoporte.SI_FALLA ) );
      }
    }
    // orden de los hops: los de la fila primero (como el job de referencia), después los de falla
    List<Salto> fila = new ArrayList<Salto>();
    List<Salto> falla = new ArrayList<Salto>();
    for ( Salto s : pd.saltos ) {
      ( JobSoporte.SI_FALLA.equals( s.condicion ) || log != null && ( s.desde.equals( log.nombre ) || pd.entrada(
        "notify" ) != null && s.desde.equals( pd.entrada( "notify" ).nombre ) ) ? falla : fila ).add( s );
    }
    pd.saltos.clear();
    pd.saltos.addAll( fila );
    pd.saltos.addAll( falla );
    // los marcadores
    for ( Entrada e : pd.entradas ) {
      if ( bosquejo && !e.clave.equals( "start" ) ) {
        e.marcador = true;
      }
    }
    pd.nota = r.f( "job.nota", p.nombre, String.join( " → ", pd.transformaciones ) ) + "\n" + ( "continue".equals(
      siFalla ) ? r.s( "job.nota.seguir" ) : r.s( "job.nota.falla" ) );
    return pd;
  }

  private static Entrada entrada( Pedido pd, String clave, String nombre, String tipo, String config ) {
    Entrada e = new Entrada();
    e.clave = clave;
    e.nombre = nombreLibre( pd, nombre );
    e.tipo = tipo;
    e.config = config;
    pd.entradas.add( e );
    return e;
  }

  private static String nombreLibre( Pedido pd, String n ) {
    String x = n;
    int i = 2;
    while ( true ) {
      boolean usado = false;
      for ( Entrada e : pd.entradas ) {
        usado |= e.nombre.equalsIgnoreCase( x );
      }
      if ( !usado ) {
        return x;
      }
      x = n + " " + i++;
    }
  }

  static boolean esTabla( Destino d ) {
    String c = d.clase == null ? "table" : d.clase;
    if ( "file".equals( c ) ) {
      return false;
    }
    return ( "fact".equals( c ) || "dimension".equals( c ) || "table".equals( c ) ) && ( d.conexion != null
      || d.ubicacion != null && esBaseDeArchivo( d.ubicacion ) );
  }

  private static boolean esBaseDeArchivo( String u ) {
    return u.toLowerCase( Locale.ROOT ).matches( ".*\\.(sqlite|sqlite3|db|db3|mdb|accdb|h2\\.db|mv\\.db)$" );
  }

  private static String padre( String u ) {
    String x = u.replace( '\\', '/' );
    int i = x.lastIndexOf( '/' );
    return i <= 0 ? "" : x.substring( 0, i );
  }

  /**
   * La carpeta contra la que se resuelven las ubicaciones relativas del plano (datos/ventas.csv): la primera de la
   * carpeta del job, la del plano y sus padres (hasta 3 niveles) donde existe alguna fuente; si ninguna, la del plano.
   */
  static File baseDeDatos( Plano p, File carpetaJob ) {
    List<File> candidatas = new ArrayList<File>();
    File planoDir = p.archivo == null ? carpetaJob : p.archivo.getAbsoluteFile().getParentFile();
    for ( File d : new File[] { carpetaJob, planoDir } ) {
      File x = d;
      for ( int i = 0; i < 4 && x != null; i++ ) {
        if ( !candidatas.contains( x ) ) {
          candidatas.add( x );
        }
        x = x.getParentFile();
      }
    }
    for ( File c : candidatas ) {
      for ( Fuente f : p.fuentes ) {
        if ( f.ubicacion != null && !f.ubicacion.contains( "://" ) && !new File( f.ubicacion ).isAbsolute()
          && new File( c, f.ubicacion ).exists() ) {
          return c;
        }
      }
    }
    return planoDir;
  }

  /** La ubicación como ${Internal.Entry.Current.Directory}/relativa-a-la-carpeta-del-job (o absoluta si lo es). */
  static String ruta( File carpetaJob, File base, String ubicacion ) {
    File f = new File( ubicacion );
    if ( f.isAbsolute() ) {
      return ubicacion.replace( '\\', '/' );
    }
    File destino = new File( base, ubicacion ).getAbsoluteFile();
    String rel;
    try {
      rel = carpetaJob.getAbsoluteFile().toPath().normalize().relativize( destino.toPath().normalize() ).toString();
    } catch ( IllegalArgumentException otraUnidad ) {
      return destino.getPath().replace( '\\', '/' );
    }
    rel = rel.replace( '\\', '/' );
    return "${Internal.Entry.Current.Directory}" + ( rel.isEmpty() ? "" : "/" + rel );
  }

  private static List<String> nombres( List<String> rutas ) {
    List<String> l = new ArrayList<String>();
    for ( String s : rutas ) {
      l.add( s.substring( s.lastIndexOf( '/' ) + 1 ) );
    }
    return l;
  }

  // ================================================================ CREATE TABLE

  /** El SQL de las tablas destino y lo que no se pudo sacar. */
  static final class Ddl {
    String sql = "";
    DatabaseMeta conexion;
    final List<String> hechas = new ArrayList<String>();
    final List<String> faltan = new ArrayList<String>();
  }

  /**
   * El CREATE TABLE de cada tabla destino, con las columnas que le llegan al step que la escribe (Table output,
   * Insert / update...) en las transformaciones construidas, en el orden del job. Nunca abre la base del usuario: las
   * conexiones SQLite se redirigen a una base temporal donde se van creando las tablas (así un Table input o un
   * Database lookup de una transformación posterior encuentra las de las anteriores); con otra base solo se sacan las
   * columnas si nada de lo de arriba del step consulta una base. overwrite = DROP TABLE IF EXISTS + CREATE; el resto,
   * CREATE TABLE IF NOT EXISTS.
   */
  static Ddl ddl( Plano p, List<String> orden, File carpeta, Map<String, TransMeta> abiertas, List<Destino> tablas ) {
    Ddl out = new Ddl();
    Map<String, Destino> pendientes = new LinkedHashMap<String, Destino>();
    for ( Destino d : tablas ) {
      pendientes.put( d.nombre.toLowerCase( Locale.ROOT ), d );
    }
    Map<String, String> creates = new HashMap<String, String>();
    File temp = null;
    DatabaseMeta tempMeta = null;
    try {
      for ( String t : orden ) {
        TransMeta original = abiertas == null ? null : abiertas.get( t.toLowerCase( Locale.ROOT ) );
        File f = new File( carpeta, t + ".ktr" );
        String xml;
        try {
          if ( original != null ) {
            xml = original.getXML();
          } else if ( f.isFile() ) {
            original = new TransMeta( f.getAbsolutePath() );
            xml = original.getXML();
          } else {
            continue;
          }
        } catch ( Exception e ) {
          continue;
        }
        TransMeta copia;
        try {
          copia = new TransMeta( new ByteArrayInputStream( xml.getBytes( StandardCharsets.UTF_8 ) ), null, false, null,
            null );
        } catch ( Exception e ) {
          continue;
        }
        // las SQLite de la copia apuntan a la base temporal
        for ( DatabaseMeta db : copia.getDatabases() ) {
          if ( "SQLITE".equalsIgnoreCase( db.getPluginId() ) ) {
            if ( temp == null ) {
              temp = File.createTempFile( "pentaho-live-ddl-", ".sqlite" );
              temp.delete();
              tempMeta = (DatabaseMeta) db.clone();
              tempMeta.setDBName( temp.getAbsolutePath() );
            }
            db.setDBName( temp.getAbsolutePath() );
          }
        }
        for ( StepMeta s : copia.getSteps() ) {
          String tabla = tablaDe( copia, s );
          if ( tabla == null ) {
            continue;
          }
          Destino d = pendientes.get( tabla.toLowerCase( Locale.ROOT ) );
          if ( d == null || creates.containsKey( d.nombre.toLowerCase( Locale.ROOT ) ) ) {
            continue;
          }
          DatabaseMeta db = conexionDe( s );
          if ( db == null ) {
            continue;
          }
          if ( !"SQLITE".equalsIgnoreCase( db.getPluginId() ) && consultaBase( copia, s ) ) {
            continue; // con otra base, sacar las columnas abriría la del usuario
          }
          List<String[]> cols;
          try {
            cols = columnas( copia, s, db );
          } catch ( Throwable e ) {
            continue;
          }
          if ( cols.isEmpty() ) {
            continue;
          }
          StringBuilder c = new StringBuilder();
          boolean recarga = "overwrite".equals( d.carga );
          String nombre = db.quoteField( tabla );
          c.append( recarga ? "DROP TABLE IF EXISTS " + nombre + ";\n" : "" );
          c.append( "CREATE TABLE " ).append( recarga ? "" : "IF NOT EXISTS " ).append( nombre ).append( " (\n" );
          for ( int i = 0; i < cols.size(); i++ ) {
            c.append( "  " ).append( cols.get( i )[ 0 ] ).append( ' ' ).append( cols.get( i )[ 1 ] ).append( i + 1 < cols
              .size() ? ",\n" : "\n" );
          }
          c.append( ");\n" );
          creates.put( d.nombre.toLowerCase( Locale.ROOT ), c.toString() );
          if ( out.conexion == null ) {
            DatabaseMeta real = original.findDatabase( db.getName() );
            out.conexion = (DatabaseMeta) ( real == null ? db : real ).clone();
          }
          // la tabla existe en la base temporal para las transformaciones que siguen
          if ( temp != null && "SQLITE".equalsIgnoreCase( db.getPluginId() ) ) {
            ejecutar( tempMeta, c.toString().replace( "DROP TABLE IF EXISTS " + nombre + ";\n", "" ).replace(
              "CREATE TABLE " + nombre, "CREATE TABLE IF NOT EXISTS " + nombre ) );
          }
        }
      }
    } catch ( Throwable e ) {
      McpLifecycleListener.LOG.logDebug( "plan_build job: CREATE TABLE: " + e );
    } finally {
      if ( temp != null && temp.exists() && !temp.delete() ) {
        temp.deleteOnExit();
      }
    }
    StringBuilder sql = new StringBuilder();
    for ( Destino d : tablas ) {
      String c = creates.get( d.nombre.toLowerCase( Locale.ROOT ) );
      if ( c == null ) {
        out.faltan.add( d.nombre );
      } else {
        out.hechas.add( d.nombre );
        sql.append( c ).append( '\n' );
      }
    }
    out.sql = sql.toString().trim() + "\n";
    return out;
  }

  private static void ejecutar( DatabaseMeta meta, String sql ) {
    Database db = new Database( new SimpleLoggingObject( "plan_build", LoggingObjectType.GENERAL, null ), meta );
    try {
      db.connect();
      db.execStatements( sql );
    } catch ( Exception e ) {
      McpLifecycleListener.LOG.logDebug( "plan_build job: temporary DDL: " + e );
    } finally {
      db.disconnect();
    }
  }

  /** La tabla que escribe el step (Table output, Insert / update, Update, Dimension lookup...), o null. */
  static String tablaDe( TransMeta tm, StepMeta s ) {
    Object m = s.getStepMetaInterface();
    String t = null;
    if ( m instanceof TableOutputMeta ) {
      t = ( (TableOutputMeta) m ).getTableName();
    } else if ( m instanceof InsertUpdateMeta ) {
      t = ( (InsertUpdateMeta) m ).getTableName();
    } else if ( "Update".equals( s.getStepID() ) || "DimensionLookup".equals( s.getStepID() )
      || "SynchronizeAfterMerge".equals( s.getStepID() ) ) {
      try {
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile( "<table>([^<]+)</table>" ).matcher( s.getXML() );
        t = mt.find() ? XMLHandler.getTagValue( XMLHandler.loadXMLString( "<t>" + mt.group( 1 ) + "</t>" ), "t" ) : null;
      } catch ( Exception e ) {
        t = null;
      }
    }
    return t == null || t.trim().isEmpty() ? null : tm.environmentSubstitute( t ).trim();
  }

  private static DatabaseMeta conexionDe( StepMeta s ) {
    try {
      DatabaseMeta[] dbs = s.getStepMetaInterface().getUsedDatabaseConnections();
      return dbs == null || dbs.length == 0 ? null : dbs[ 0 ];
    } catch ( Throwable e ) {
      return null;
    }
  }

  /** Algún step de arriba de s (o s) consulta una base para dar sus campos (Table input, Database lookup...). */
  private static boolean consultaBase( TransMeta tm, StepMeta s ) {
    List<StepMeta> cola = new ArrayList<StepMeta>( tm.findPreviousSteps( s, true ) );
    Set<String> vistos = new LinkedHashSet<String>();
    while ( !cola.isEmpty() ) {
      StepMeta x = cola.remove( 0 );
      if ( !vistos.add( x.getName() ) ) {
        continue;
      }
      if ( conexionDe( x ) != null ) {
        return true;
      }
      cola.addAll( tm.findPreviousSteps( x, true ) );
    }
    return false;
  }

  /** {columna citada, definición} de la tabla que escribe s. */
  static List<String[]> columnas( TransMeta tm, StepMeta s, DatabaseMeta db ) throws Exception {
    RowMetaInterface llega = tm.getPrevStepFields( s );
    List<String[]> out = new ArrayList<String[]>();
    Object m = s.getStepMetaInterface();
    List<String[]> pares = new ArrayList<String[]>(); // {columna, campo del flujo}
    if ( m instanceof TableOutputMeta && ( (TableOutputMeta) m ).specifyFields() ) {
      String[] c = ( (TableOutputMeta) m ).getFieldDatabase();
      String[] f = ( (TableOutputMeta) m ).getFieldStream();
      for ( int i = 0; c != null && i < c.length; i++ ) {
        pares.add( new String[] { c[ i ], f != null && i < f.length && f[ i ] != null ? f[ i ] : c[ i ] } );
      }
    } else if ( m instanceof InsertUpdateMeta ) {
      InsertUpdateMeta iu = (InsertUpdateMeta) m;
      for ( int i = 0; iu.getKeyLookup() != null && i < iu.getKeyLookup().length; i++ ) {
        pares.add( new String[] { iu.getKeyLookup()[ i ], iu.getKeyStream()[ i ] } );
      }
      for ( int i = 0; iu.getUpdateLookup() != null && i < iu.getUpdateLookup().length; i++ ) {
        pares.add( new String[] { iu.getUpdateLookup()[ i ], iu.getUpdateStream()[ i ] } );
      }
    } else {
      for ( ValueMetaInterface v : llega.getValueMetaList() ) {
        pares.add( new String[] { v.getName(), v.getName() } );
      }
    }
    Set<String> vistas = new LinkedHashSet<String>();
    for ( String[] p : pares ) {
      ValueMetaInterface v = llega.searchValueMeta( p[ 1 ] );
      if ( v == null || !vistas.add( p[ 0 ].toLowerCase( Locale.ROOT ) ) ) {
        continue;
      }
      out.add( new String[] { db.quoteField( p[ 0 ] ), definicion( db, v, p[ 0 ] ) } );
    }
    return out;
  }

  /**
   * El tipo de la columna. SQLite con los tipos de su afinidad (Kettle daría NUMERIC a un Integer sin longitud, y un
   * Database lookup leería después otro tipo de valor); en las demás bases, la definición de Kettle con los enteros
   * sin longitud como enteros largos.
   */
  static String definicion( DatabaseMeta db, ValueMetaInterface v, String nombre ) {
    if ( "SQLITE".equalsIgnoreCase( db.getPluginId() ) ) {
      switch ( v.getType() ) {
        case ValueMetaInterface.TYPE_INTEGER:
        case ValueMetaInterface.TYPE_BOOLEAN:
          return "INTEGER";
        case ValueMetaInterface.TYPE_NUMBER:
          return "REAL";
        case ValueMetaInterface.TYPE_BIGNUMBER:
          return "NUMERIC";
        case ValueMetaInterface.TYPE_DATE:
        case ValueMetaInterface.TYPE_TIMESTAMP:
          return "TIMESTAMP";
        default:
          return "TEXT"; // String, y Binary de una consulta sin tipos (SQLite no los da antes de ejecutar)
      }
    }
    ValueMetaInterface c = v.clone();
    c.setName( nombre );
    if ( c.getType() == ValueMetaInterface.TYPE_INTEGER && c.getLength() <= 0 ) {
      c.setLength( 18 );
      c.setPrecision( 0 );
    }
    return db.getFieldDefinition( c, null, null, false, false, false ).trim();
  }

  // ================================================================ construir

  /** El resultado de construir: cuántas entradas se crearon, las que ya estaban, los marcadores. */
  static final class Hecho {
    final List<String> creadas = new ArrayList<String>();
    final List<String> yaEstaban = new ArrayList<String>();
    final List<String> marcadores = new ArrayList<String>();
    int hops;
    int notas;
    boolean deshacible;
  }

  /** Empieza: deja la conexión del SQL y el registro del plano en el job. (UI o sin Spoon) */
  static void preparar( JobMeta jm, Plano p, Pedido pd ) {
    if ( pd.conexion != null && jm.findDatabase( pd.conexion.getName() ) == null ) {
      jm.addDatabase( (DatabaseMeta) pd.conexion.clone() );
    }
    if ( p.archivo != null ) {
      jm.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_PLANO, p.archivo.getPath() );
    }
    jm.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_NOMBRE, p.nombre );
    jm.setAttribute( ReglasBoceto.GRUPO, K_JOB, pd.nombre );
    if ( jm.getName() == null || jm.getName().trim().isEmpty() ) {
      jm.setName( pd.nombre );
    }
    if ( jm.getDescription() == null || jm.getDescription().isEmpty() ) {
      jm.setDescription( p.objetivo == null ? p.nombre : p.objetivo );
    }
  }

  /** Agrega una entrada (si no hay una con ese nombre) y los hops planeados entre ella y las que ya están. */
  static void agregar( JobMeta jm, Pedido pd, Entrada e, IMetaStore ms, Hecho h, String idioma ) throws Exception {
    if ( JobSoporte.START.equals( e.tipo ) && jm.findStart() != null ) {
      h.yaEstaban.add( jm.findStart().getName() );
      e.nombre = jm.findStart().getName();
      return;
    }
    if ( buscar( jm, e.nombre ) != null ) {
      h.yaEstaban.add( e.nombre );
      return;
    }
    JobEntryCopy c = crear( jm, e, ms );
    jm.addJobEntry( c );
    h.creadas.add( e.nombre );
    if ( e.marcador ) {
      h.marcadores.add( e.nombre );
    }
    for ( Salto s : pd.saltos ) {
      if ( s.desde.equals( e.nombre ) || s.hacia.equals( e.nombre ) ) {
        JobEntryCopy a = buscar( jm, s.desde );
        JobEntryCopy b = buscar( jm, s.hacia );
        if ( a != null && b != null && jm.findJobHop( a, b, true ) == null ) {
          JobHopMeta hop = new JobHopMeta( a, b );
          JobSoporte.aplicarCondicion( hop, a.isStart() ? JobSoporte.SIEMPRE : s.condicion );
          jm.addJobHop( hop );
          h.hops++;
        }
      }
    }
    jm.setChanged();
  }

  /** La entrada: la configurada o, si es marcador, un DUMMY con la planeada guardada en sus atributos. */
  static JobEntryCopy crear( JobMeta jm, Entrada e, IMetaStore ms ) throws Exception {
    JobEntryCopy c;
    if ( JobSoporte.START.equals( e.tipo ) ) {
      c = JobMeta.createStartEntry();
      c.setName( e.nombre );
    } else if ( e.marcador || JobSoporte.DUMMY.equals( e.tipo ) ) {
      c = JobMeta.createDummyEntry();
      c.setName( e.nombre );
      c.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_MARCADOR, e.clave );
      if ( !JobSoporte.DUMMY.equals( e.tipo ) ) {
        c.setAttribute( ReglasBoceto.GRUPO, K_TIPO, e.tipo );
        c.setAttribute( ReglasBoceto.GRUPO, K_CONFIG, e.config );
      }
    } else {
      c = desdeXml( jm, e.nombre, e.tipo, e.config, ms );
      c.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_ETAPA, e.clave );
    }
    c.setDescription( e.descripcion );
    c.setLocation( e.x, e.y );
    c.setDrawn( true );
    c.setParentJobMeta( jm );
    return c;
  }

  /** Una entrada configurada desde la plantilla de su tipo y las etiquetas que cambian. */
  static JobEntryCopy desdeXml( JobMeta jm, String nombre, String tipo, String config, IMetaStore ms )
    throws Exception {
    String t = JobSoporte.resolverTipo( tipo );
    String cuerpo = Xml.mezclar( JobSoporte.plantilla( t ), config, JobSoporte.IGNORAR );
    String xml = "<entry>" + XMLHandler.addTagValue( "name", nombre ) + XMLHandler.addTagValue( "description", "" )
      + XMLHandler.addTagValue( "type", t ) + cuerpo + XMLHandler.addTagValue( "parallel", "N" ) + XMLHandler
        .addTagValue( "draw", "Y" ) + XMLHandler.addTagValue( "nr", "0" ) + XMLHandler.addTagValue( "xloc", "0" )
      + XMLHandler.addTagValue( "yloc", "0" ) + "</entry>";
    Node nodo = XMLHandler.getSubNode( XMLHandler.loadXMLString( xml ), "entry" );
    JobEntryCopy c = new JobEntryCopy( nodo, jm.getDatabases(), jm.getSlaveServers(), null, ms );
    if ( c.isMissing() || c.getEntry() == null ) {
      throw new ToolError( "Spoon does not recognize the job entry type " + t );
    }
    return c;
  }

  static JobEntryCopy buscar( JobMeta jm, String nombre ) {
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      if ( c.getName() != null && c.getName().equalsIgnoreCase( nombre ) ) {
        return c;
      }
    }
    return null;
  }

  /**
   * Coloca las entradas: la fila principal en una línea; el camino de falla debajo, centrado bajo las
   * transformaciones; en sketch, las notas PENDIENTE arriba y abajo de la fila (alternadas) sin pisarse. Devuelve las
   * notas (todavía sin agregar). (Antes de crear las entradas.)
   */
  static List<NotePadMeta> ubicar( JobMeta jm, Pedido pd, String idioma, Medidor m ) {
    Rotulos r = Rotulos.de( PlanoInforme.AREA, idioma );
    String pref = PlanoConstruir.PREFIJO.get( Rotulos.normalizar( idioma ) );
    pref = pref == null ? PlanoConstruir.PREFIJO.get( "en" ) : pref;
    // desplazamiento: si el job ya tiene cosas, lo nuevo va debajo
    int y0 = 16;
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      y0 = Math.max( y0, c.getLocation().y + 100 );
    }
    for ( int i = 0; i < jm.nrNotes(); i++ ) {
      NotePadMeta n = jm.getNote( i );
      y0 = Math.max( y0, n.getLocation().y + n.getHeight() + 30 );
    }
    if ( jm.nrJobEntries() == 0 && jm.nrNotes() == 0 ) {
      y0 = 16;
    }
    List<NotePadMeta> notas = new ArrayList<NotePadMeta>();
    Point tg = m.texto( pd.nota, false );
    NotePadMeta general = new NotePadMeta( pd.nota, 80, y0, tg.x + 16, tg.y + 12 );
    PlanoConstruir.estilizar( general, "other" );
    notas.add( general );
    int yNotas = y0 + general.getHeight() + 24;
    List<Entrada> fila = new ArrayList<Entrada>();
    List<Entrada> falla = new ArrayList<Entrada>();
    for ( Entrada e : pd.entradas ) {
      ( FALLA.equals( e.fila ) ? falla : fila ).add( e );
    }
    // notas PENDIENTE: medir; la separación entre entradas sale de los nombres y de las notas (alternadas arriba y
    // abajo, dos notas vecinas de la misma banda están a dos pasos)
    Map<Entrada, String> textos = new LinkedHashMap<Entrada, String>();
    Map<Entrada, Point> tam = new LinkedHashMap<Entrada, Point>();
    int altoArriba = 0;
    int altoAbajo = 0;
    int paso = PASO_X;
    for ( int i = 0; i < fila.size(); i++ ) {
      Entrada e = fila.get( i );
      paso = Math.max( paso, m.texto( e.nombre, false ).x + 40 );
      if ( !e.marcador ) {
        continue;
      }
      String t = textoNota( pref, e, r );
      Point p = m.texto( t, false );
      Point s = new Point( p.x + 12, p.y + 10 );
      textos.put( e, t );
      tam.put( e, s );
      paso = Math.max( paso, ( s.x + 30 + 1 ) / 2 );
      if ( i % 2 == 1 ) {
        altoArriba = Math.max( altoArriba, s.y );
      } else {
        altoAbajo = Math.max( altoAbajo, s.y );
      }
    }
    int yFila = yNotas + ( altoArriba == 0 ? 0 : altoArriba + 20 ) + 16;
    for ( int i = 0; i < fila.size(); i++ ) {
      fila.get( i ).x = X0 + i * paso;
      fila.get( i ).y = yFila;
    }
    for ( Map.Entry<Entrada, String> x : textos.entrySet() ) {
      Entrada e = x.getKey();
      Point s = tam.get( e );
      int i = fila.indexOf( e );
      int nx = Math.max( 10, e.x + 16 - s.x / 2 );
      int ny = i % 2 == 1 ? yFila - 20 - s.y : yFila + 32 + 30;
      NotePadMeta n = new NotePadMeta( x.getValue(), nx, ny, s.x, s.y );
      PlanoConstruir.estilizar( n, "load" );
      notas.add( n );
    }
    int yFalla = yFila + 32 + 30 + ( altoAbajo == 0 ? 0 : altoAbajo + 30 ) + 120;
    // el camino de falla, centrado bajo las transformaciones, con sus notas debajo una al lado de la otra
    Map<Entrada, String> tf = new LinkedHashMap<Entrada, String>();
    Map<Entrada, Point> sf = new LinkedHashMap<Entrada, Point>();
    int pasoF = paso;
    for ( Entrada e : falla ) {
      pasoF = Math.max( pasoF, m.texto( e.nombre, false ).x + 40 );
      if ( e.marcador ) {
        String t = textoNota( pref, e, r );
        Point p = m.texto( t, false );
        tf.put( e, t );
        sf.put( e, new Point( p.x + 12, p.y + 10 ) );
        pasoF = Math.max( pasoF, p.x + 12 + 30 );
      }
    }
    int primera = Integer.MAX_VALUE;
    int ultima = 0;
    for ( Entrada e : fila ) {
      if ( e.clave.startsWith( "trans:" ) ) {
        primera = Math.min( primera, e.x );
        ultima = Math.max( ultima, e.x );
      }
    }
    if ( primera == Integer.MAX_VALUE ) {
      primera = X0;
      ultima = X0 + ( fila.size() - 1 ) * paso;
    }
    int centro = ( primera + ultima ) / 2;
    int x0 = centro - ( falla.size() - 1 ) * pasoF / 2;
    for ( int i = 0; i < falla.size(); i++ ) {
      Entrada e = falla.get( i );
      e.x = Math.max( X0, x0 + i * pasoF );
      e.y = yFalla;
      if ( tf.containsKey( e ) ) {
        Point s = sf.get( e );
        NotePadMeta n = new NotePadMeta( tf.get( e ), Math.max( 10, e.x + 16 - s.x / 2 ), yFalla + 32 + 30, s.x, s.y );
        PlanoConstruir.estilizar( n, "reject" );
        notas.add( n );
      }
    }
    return notas;
  }

  /** «PENDIENTE · nombre» y lo que tiene que hacer la entrada. */
  static String textoNota( String pref, Entrada e, Rotulos r ) {
    StringBuilder sb = new StringBuilder( pref ).append( e.nombre );
    List<String> cuerpo = new ArrayList<String>();
    if ( !JobSoporte.DUMMY.equals( e.tipo ) ) {
      cuerpo.add( r.f( "job.todo.tipo", nombreTipo( e.tipo ) ) );
    }
    cuerpo.addAll( e.todo );
    for ( String c : cuerpo ) {
      for ( String l : PlanoConstruir.envolver( c, PlanoConstruir.ANCHO_LINEA ) ) {
        sb.append( '\n' ).append( l );
      }
    }
    return sb.toString();
  }

  private static String nombreTipo( String id ) {
    try {
      org.pentaho.di.core.plugins.PluginInterface p = org.pentaho.di.core.plugins.PluginRegistry.getInstance()
        .findPluginWithId( org.pentaho.di.core.plugins.JobEntryPluginType.class, id );
      return p == null ? id : p.getName() + " (" + id + ")";
    } catch ( Throwable t ) {
      return id;
    }
  }

  /**
   * Construye el job planeado en jm de una vez (sin animar): Design perspective y pruebas. undo = null en un documento
   * nuevo (cerrarlo lo deshace); si no, una foto del lienzo en UN cambio (Ctrl+Z). (UI o sin Spoon)
   */
  static ObjectNode construirAhora( Spoon sp, JobMeta jm, Plano p, Pedido pd, String idioma, IMetaStore ms,
                                    boolean nuevo ) throws Exception {
    Lienzo antes = nuevo ? null : Lienzo.de( jm );
    preparar( jm, p, pd );
    LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( -1 );
    List<NotePadMeta> notas;
    try {
      notas = ubicar( jm, pd, idioma, m );
    } finally {
      m.cerrar();
    }
    Hecho h = new Hecho();
    for ( Entrada e : pd.entradas ) {
      agregar( jm, pd, e, ms, h, idioma );
    }
    return terminar( sp, jm, p, pd, notas, h, antes );
  }

  /** Agrega las notas (las de entradas que no se crearon, no), registra el undo y arma la respuesta. (UI) */
  static ObjectNode terminar( Spoon sp, JobMeta jm, Plano p, Pedido pd, List<NotePadMeta> notas, Hecho h,
                              Lienzo antes ) {
    for ( int i = 0; i < notas.size(); i++ ) {
      NotePadMeta n = notas.get( i );
      String de = PlanoConstruir.etapaDeNota( n );
      if ( i == 0 && !h.creadas.isEmpty() || de != null && h.creadas.contains( de ) ) {
        jm.addNote( n );
        h.notas++;
      }
    }
    jm.setChanged();
    if ( antes != null && !h.creadas.isEmpty() ) {
      h.deshacible = Lienzo.registrar( sp, jm, antes );
    }
    ObjectNode o = Tools.obj();
    o.put( "job", pd.nombre );
    o.put( "mode", pd.modo );
    ArrayNode ts = o.putArray( "transformations_in_order" );
    for ( String t : pd.transformaciones ) {
      ts.add( t );
    }
    ArrayNode en = o.putArray( "entries" );
    for ( Entrada e : pd.entradas ) {
      if ( h.creadas.contains( e.nombre ) ) {
        en.addObject().put( "name", e.nombre ).put( "type", h.marcadores.contains( e.nombre ) ? "DUMMY (placeholder: "
          + e.tipo + ")" : e.tipo );
      }
    }
    o.put( "hops", h.hops );
    o.put( "notes_added", h.notas );
    if ( !h.yaEstaban.isEmpty() ) {
      ArrayNode a = o.putArray( "skipped_already_in_job" );
      for ( String s : h.yaEstaban ) {
        a.add( s );
      }
    }
    if ( !h.marcadores.isEmpty() ) {
      ArrayNode a = o.putArray( "placeholders" );
      for ( String s : h.marcadores ) {
        a.add( s );
      }
      o.put( "fill", "fill_placeholder document=<job> step=<placeholder> without 'type' puts the planned entry; with "
        + "type/config_xml, another one." );
    }
    if ( !pd.tablas.isEmpty() ) {
      ArrayNode a = o.putArray( "tables_created_by_sql" );
      for ( String s : pd.tablas ) {
        a.add( s );
      }
    }
    if ( !pd.avisos.isEmpty() ) {
      ArrayNode a = o.putArray( "warnings" );
      for ( String s : pd.avisos ) {
        a.add( s );
      }
    }
    o.put( "save_as", new File( pd.carpeta, pd.nombre + ".kjb" ).getPath() );
    o.put( "undo", antes == null ? "It is a new job: close it without saving to undo it (Spoon does not chain undo "
      + "actions in jobs)." : h.deshacible ? "One Ctrl+Z in Spoon removes what was added." : "Could not register a "
        + "single Ctrl+Z: undo by hand or close without saving." );
    return o;
  }

  // ================================================================ un Ctrl+Z para jobs

  /**
   * La foto del lienzo de un job (entradas, hops y notas, por identidad) y la entrada «portadora»: un cambio de esa
   * entrada en el historial de Spoon cuyo objeto guardado, cuando Spoon lo clona para deshacer o rehacer
   * (SpoonJobDelegate.undoJobAction: clone() y después getJobEntry(i).replaceMeta), deja el job como en la foto. Va en
   * UNA acción: el Ctrl+Z de un job no encadena (ver UndoGrupo).
   */
  static final class Lienzo {
    final List<JobEntryCopy> entradas = new ArrayList<JobEntryCopy>();
    final List<JobHopMeta> hops = new ArrayList<JobHopMeta>();
    final List<NotePadMeta> notas = new ArrayList<NotePadMeta>();

    static Lienzo de( JobMeta jm ) {
      Lienzo l = new Lienzo();
      l.entradas.addAll( jm.getJobCopies() );
      for ( int i = 0; i < jm.nrJobHops(); i++ ) {
        l.hops.add( jm.getJobHop( i ) );
      }
      for ( int i = 0; i < jm.nrNotes(); i++ ) {
        l.notas.add( jm.getNote( i ) );
      }
      return l;
    }

    void poner( JobMeta jm ) {
      while ( jm.nrJobHops() > 0 ) {
        jm.removeJobHop( jm.nrJobHops() - 1 );
      }
      while ( jm.nrJobEntries() > 0 ) {
        jm.removeJobEntry( jm.nrJobEntries() - 1 );
      }
      while ( jm.nrNotes() > 0 ) {
        jm.removeNote( jm.nrNotes() - 1 );
      }
      for ( JobEntryCopy c : entradas ) {
        jm.addJobEntry( c );
      }
      for ( JobHopMeta h : hops ) {
        jm.addJobHop( h );
      }
      for ( NotePadMeta n : notas ) {
        jm.addNote( n );
      }
      jm.setChanged();
    }

    /**
     * Registra el paso de 'antes' al lienzo actual como UN cambio de una entrada que está en el mismo lugar en las dos
     * fotos. false si no hay ninguna (un job que estaba vacío).
     */
    static boolean registrar( Spoon sp, JobMeta jm, Lienzo antes ) {
      Lienzo despues = de( jm );
      int k = -1;
      for ( int i = 0; i < Math.min( antes.entradas.size(), despues.entradas.size() ); i++ ) {
        if ( antes.entradas.get( i ) == despues.entradas.get( i ) ) {
          k = i;
          break;
        }
      }
      if ( k < 0 ) {
        return false;
      }
      JobEntryCopy portadora = jm.getJobEntry( k );
      UndoGrupo u = sp == null ? UndoGrupo.sinSpoon( jm ) : new UndoGrupo( sp, jm );
      u.cambio( new Object[] { new ConFoto( (JobEntryCopy) portadora.clone(), jm, antes ) }, new Object[] {
        new ConFoto( (JobEntryCopy) portadora.clone(), jm, despues ) }, new int[] { k } );
      return true;
    }
  }

  /** La entrada del historial que, al clonarse, pone su foto en el job. */
  static final class ConFoto extends JobEntryCopy {
    final transient JobMeta jm;
    final transient Lienzo foto;

    ConFoto( JobEntryCopy base, JobMeta jm, Lienzo foto ) {
      super();
      replaceMeta( base );
      this.jm = jm;
      this.foto = foto;
    }

    @Override
    public Object clone() {
      try {
        foto.poner( jm );
      } catch ( RuntimeException e ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not restore the job canvas on undo: " + e );
      }
      return super.clone();
    }
  }

  // ================================================================ MCP

  /** plan_build del job: valida, crea el documento (o usa el enlazado), anima entrada por entrada y cierra. */
  static Object ejecutar( final JsonNode a, final Plano p, final String modo, final String idioma ) throws Exception {
    int delay = Math.max( 0, Math.min( TransLote.DELAY_MAX, Tools.optInt( a, "delay_ms", TransLote.DELAY_DEFECTO ) ) );
    final Map<String, TransMeta> abiertas = Docs.ui( () -> abiertasDelPlano( p ) );
    final Pedido pd = pedido( p, modo, idioma, abiertas );
    final Object[] estado = Docs.ui( () -> {
      Spoon sp = Docs.spoon();
      Object[] d = destino( sp, a, p, pd );
      JobMeta jm = (JobMeta) d[ 0 ];
      boolean nuevo = (Boolean) d[ 1 ];
      Lienzo antes = nuevo ? null : Lienzo.de( jm );
      preparar( jm, p, pd );
      LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( -1 );
      List<NotePadMeta> notas;
      try {
        notas = ubicar( jm, pd, idioma, m );
      } finally {
        m.cerrar();
      }
      return new Object[] { jm, antes, notas, new Hecho() };
    } );
    final JobMeta jm = (JobMeta) estado[ 0 ];
    final Lienzo antes = (Lienzo) estado[ 1 ];
    @SuppressWarnings( "unchecked" )
    final List<NotePadMeta> notas = (List<NotePadMeta>) estado[ 2 ];
    final Hecho h = (Hecho) estado[ 3 ];
    Exception fallo = null;
    for ( int i = 0; i < pd.entradas.size(); i++ ) {
      if ( i > 0 && delay > 0 ) {
        try {
          Thread.sleep( delay );
        } catch ( InterruptedException ie ) {
          Thread.currentThread().interrupt();
          fallo = new ToolError( "interrupted" );
          break;
        }
      }
      final Entrada e = pd.entradas.get( i );
      try {
        Docs.ui( () -> {
          Spoon sp = Docs.spoon();
          Docs.activar( jm );
          agregar( jm, pd, e, sp.getMetaStore(), h, idioma );
          Docs.touched( jm );
          return null;
        } );
      } catch ( Exception ex ) {
        fallo = ex;
        break;
      }
    }
    final boolean completo = fallo == null;
    ObjectNode o = Docs.ui( () -> {
      ObjectNode r = terminar( Docs.spoon(), jm, p, pd, completo ? notas : new ArrayList<NotePadMeta>(), h, antes );
      Docs.touched( jm );
      r.put( "document", Docs.id( jm ) );
      return r;
    } );
    if ( fallo != null ) {
      throw new ToolError( "plan_build stopped: " + TransOps.mensaje( fallo ) + ". " + o.path( "undo" ).asText() );
    }
    return o;
  }

  /** El job donde construir y si es nuevo: 'document', uno abierto enlazado al plano o uno nuevo. (UI) */
  static Object[] destino( Spoon sp, JsonNode a, Plano p, Pedido pd ) throws Exception {
    if ( Tools.optStr( a, "document", null ) != null ) {
      return new Object[] { Docs.job( a ), Boolean.FALSE };
    }
    for ( org.pentaho.di.base.AbstractMeta m : Docs.abiertos() ) {
      if ( m instanceof JobMeta && esDelPlano( (JobMeta) m, p ) ) {
        Docs.activar( m );
        return new Object[] { m, Boolean.FALSE };
      }
    }
    Docs.perspectivaPrincipal();
    sp.newJobFile();
    org.pentaho.di.base.AbstractMeta nuevo = Docs.activo();
    if ( !( nuevo instanceof JobMeta ) ) {
      throw new ToolError( "Spoon did not create the job" );
    }
    JobMeta jm = (JobMeta) nuevo;
    PlanoConstruir.nombrar( sp, jm, pd.nombre );
    return new Object[] { jm, Boolean.TRUE };
  }

  /** El job tiene el registro de este plano. */
  static boolean esDelPlano( JobMeta jm, Plano p ) {
    String ruta = jm.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_PLANO );
    return ruta != null && p.archivo != null && new File( ruta ).getAbsoluteFile().equals( p.archivo
      .getAbsoluteFile() ) && jm.getAttribute( ReglasBoceto.GRUPO, K_JOB ) != null;
  }

  /** Las transformaciones abiertas enlazadas al plano o con el nombre de una de sus transformaciones. (UI) */
  static Map<String, TransMeta> abiertasDelPlano( Plano p ) {
    Map<String, TransMeta> m = new HashMap<String, TransMeta>();
    try {
      for ( org.pentaho.di.base.AbstractMeta x : Docs.abiertos() ) {
        if ( !( x instanceof TransMeta ) ) {
          continue;
        }
        TransMeta tm = (TransMeta) x;
        String g = tm.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_TRANS );
        if ( g != null && PlanoConstruir.esDelPlano( tm, p, g ) && !g.isEmpty() ) {
          m.put( g.toLowerCase( Locale.ROOT ), tm );
        }
      }
    } catch ( Throwable t ) {
      // sin Spoon
    }
    return m;
  }

  // ================================================================ fill_placeholder en un job

  /**
   * Cambia un marcador del job por su entrada: la planeada (sin pieza) o la pedida. Los hops que entraban y salían se
   * vuelven a crear con su condición (incondicional si la entrada nueva no evalúa). La nota PENDIENTE se quita. Un
   * Ctrl+Z (foto del lienzo). (UI o sin Spoon)
   */
  static ObjectNode rellenar( Spoon sp, JobMeta jm, String paso, List<PlanoConstruir.Pieza> piezas, boolean conservarNota,
                              IMetaStore ms ) throws Exception {
    JobEntryCopy d = JobSoporte.entrada( jm, paso );
    String clave = d.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_MARCADOR );
    if ( !d.isDummy() || clave == null ) {
      throw new ToolError( "'" + d.getName() + "' is not a placeholder of the job sketch (a DUMMY left by plan_build)."
        + " Use configure_entry / add_entry instead." );
    }
    if ( piezas != null && piezas.size() > 1 ) {
      throw new ToolError( "In a job a placeholder is replaced by ONE entry: pass 'type' (and config_xml), or nothing to "
        + "put the planned one" );
    }
    String tipo;
    String config;
    String nombre = d.getName();
    if ( piezas == null || piezas.isEmpty() ) {
      tipo = d.getAttribute( ReglasBoceto.GRUPO, K_TIPO );
      config = d.getAttribute( ReglasBoceto.GRUPO, K_CONFIG );
      if ( tipo == null ) {
        throw new ToolError( "'" + d.getName() + "' has no planned entry (the plan cannot configure it, e.g. a "
          + "notification or a CREATE TABLE that could not be derived): pass 'type' and 'config_xml'." );
      }
      if ( config == null || config.trim().isEmpty() && "SQL".equals( tipo ) ) {
        throw new ToolError( "The planned SQL of '" + d.getName() + "' is empty: pass config_xml with <sql> and "
          + "<connection>." );
      }
    } else {
      tipo = piezas.get( 0 ).tipo;
      config = piezas.get( 0 ).config;
      if ( piezas.get( 0 ).nombre != null && !piezas.get( 0 ).nombre.trim().isEmpty() ) {
        nombre = piezas.get( 0 ).nombre.trim();
        JobEntryCopy otra = buscar( jm, nombre );
        if ( otra != null && otra != d ) {
          throw new ToolError( "There is already an entry named '" + otra.getName() + "'" );
        }
      }
    }
    JobEntryCopy nueva = desdeXml( jm, nombre, tipo, config == null ? "" : config, ms ); // valida antes de tocar
    Lienzo antes = Lienzo.de( jm );
    nueva.setLocation( d.getLocation().x, d.getLocation().y );
    nueva.setDescription( d.getDescription() );
    nueva.setDrawn( true );
    nueva.setParentJobMeta( jm );
    nueva.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_ETAPA, clave );
    List<JobHopMeta> viejos = new ArrayList<JobHopMeta>();
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      if ( h.getFromEntry() == d || h.getToEntry() == d ) {
        viejos.add( h );
      }
    }
    for ( JobHopMeta h : viejos ) {
      jm.removeJobHop( jm.indexOfJobHop( h ) );
    }
    int idx = jm.indexOfJobEntry( d );
    jm.removeJobEntry( idx );
    jm.addJobEntry( idx, nueva );
    for ( JobHopMeta h : viejos ) {
      JobEntryCopy a = h.getFromEntry() == d ? nueva : h.getFromEntry();
      JobEntryCopy b = h.getToEntry() == d ? nueva : h.getToEntry();
      JobHopMeta n = new JobHopMeta( a, b );
      // la condición planeada se conserva aunque el otro extremo siga siendo un marcador (DUMMY): cuando se rellene,
      // el hop ya es el que dice el plano; solo la entrada nueva que no evalúa (START, DUMMY) la fuerza a «always»
      String cond = JobSoporte.condicion( h );
      JobSoporte.aplicarCondicion( n, a == nueva && JobSoporte.soloIncondicional( a ) ? JobSoporte.SIEMPRE : cond );
      n.setEnabled( h.isEnabled() );
      jm.addJobHop( n );
    }
    boolean notaQuitada = false;
    if ( !conservarNota ) {
      for ( int i = jm.nrNotes() - 1; i >= 0; i-- ) {
        String e = PlanoConstruir.etapaDeNota( jm.getNote( i ) );
        if ( e != null && e.equalsIgnoreCase( d.getName() ) ) {
          jm.removeNote( i );
          notaQuitada = true;
          break;
        }
      }
    }
    jm.unselectAll();
    nueva.setSelected( true );
    jm.setChanged();
    boolean deshacible = Lienzo.registrar( sp, jm, antes );
    ObjectNode o = Tools.obj();
    o.put( "replaced", d.getName() );
    o.put( "entry", nueva.getName() );
    o.put( "type", nueva.getEntry().getPluginId() );
    o.put( "planned", piezas == null || piezas.isEmpty() );
    o.put( "hops_kept", viejos.size() );
    o.put( "note_removed", notaQuitada );
    o.set( "job_sketch", progreso( jm ) );
    o.put( "undo", deshacible ? "One Ctrl+Z in Spoon brings the placeholder back." : "Undo by hand." );
    return o;
  }

  /** Cuántos marcadores le quedan al job. */
  static ObjectNode progreso( JobMeta jm ) {
    ObjectNode o = Tools.obj();
    ArrayNode a = o.putArray( "placeholders" );
    int total = 0;
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      if ( c.isStart() ) {
        continue;
      }
      total++;
      if ( esMarcador( c ) ) {
        a.add( c.getName() );
      }
    }
    o.put( "entries", total );
    o.put( "built", total - a.size() );
    return o;
  }

  static boolean esMarcador( JobEntryCopy c ) {
    return c.isDummy() && c.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_MARCADOR ) != null;
  }

  private static String x( String s ) {
    return MapeoImportar.x( s );
  }
}
