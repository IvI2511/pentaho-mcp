package io.github.ivi2511.pentahomcp;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.resource.ResourceEntry;
import org.pentaho.di.resource.ResourceReference;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.csvinput.CsvInputMeta;
import org.pentaho.di.trans.steps.fileinput.text.TextFileInputMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;
import org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;

/**
 * Seccion summary (diseno §2.3): cuentas (steps, hops, archivos leidos y escritos, tablas,
 * conexiones), una frase automatica por idioma («Lee 1 archivo (ventas.csv), calcula, ordena y
 * agrupa, y escribe 1 archivo (x.csv). 5 steps y 4 hops.») y los resumenes de las demas secciones.
 * InformeServicio la arma al final con {@link #armar(Contexto, List)}; no cuesta nada.
 *
 * @dueño WP2
 */
final class SecResumen implements Recolector {

  /** Verbo de la frase por id de step (el orden de la frase es el del flujo). */
  private static final Map<String, String> VERBOS = new HashMap<String, String>();
  /** Lo mismo para entradas de job. */
  private static final Map<String, String> VERBOS_JOB = new HashMap<String, String>();

  static {
    verbos( VERBOS, "v.genera", "RowGenerator", "DataGrid", "RandomValue", "SystemInfo" );
    verbos( VERBOS, "v.variables", "GetVariable", "GetVariables" );
    verbos( VERBOS, "v.constantes", "Constant" );
    verbos( VERBOS, "v.filtra", "FilterRows", "SwitchCase", "JavaFilter", "Validator" );
    verbos( VERBOS, "v.calcula", "Calculator", "Formula", "ScriptValueMod", "Janino", "UserDefinedJavaClass",
      "NumberRange", "ValueMapper", "StringOperations", "ReplaceString", "StringCut", "SetValueConstant",
      "SetValueField", "IfNull", "Sequence", "FieldsChangeSequence", "ConcatFields", "FieldSplitter",
      "SplitFieldToRows3", "Denormaliser", "Normaliser", "RegexEval" );
    verbos( VERBOS, "v.ordena", "SortRows" );
    verbos( VERBOS, "v.agrupa", "GroupBy", "MemoryGroupBy", "AnalyticQuery" );
    verbos( VERBOS, "v.unicos", "Unique", "UniqueRowsByHashSet" );
    verbos( VERBOS, "v.combina", "MergeJoin", "JoinRows", "MultiwayMergeJoin", "Append", "MergeRows",
      "SortedMerge" );
    verbos( VERBOS, "v.busca", "StreamLookup", "DBLookup", "DBJoin", "FuzzyMatch", "HTTP", "Rest" );
    verbos( VERBOS, "v.selecciona", "SelectValues" );
    verbos( VERBOS_JOB, "v.log", "WRITE_TO_LOG" );
    verbos( VERBOS_JOB, "v.sql", "SQL" );
    verbos( VERBOS_JOB, "v.espera", "DELAY", "WAIT_FOR_FILE", "WAIT_FOR_SQL" );
    verbos( VERBOS_JOB, "v.archivos", "COPY_FILES", "DELETE_FILE", "DELETE_FILES", "CREATE_FOLDER", "MOVE_FILES",
      "CREATE_FILE", "DELETE_FOLDERS", "ZIP_FILE", "UNZIP" );
  }

  private static void verbos( Map<String, String> m, String verbo, String... ids ) {
    for ( String id : ids ) {
      m.put( id, verbo );
    }
  }

  @Override
  public String id() {
    return SeccionId.RESUMEN;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    return armar( ctx, Collections.<Seccion>emptyList() );
  }

  /** Arma el resumen con las secciones ya hechas (sus resumenes van en «Lo mas importante»). */
  Seccion armar( Contexto ctx, List<Seccion> otras ) {
    Rotulos r = ctx.rotulos( InformeServicio.AREA );
    Captura c = ctx.captura();
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), ctx.idioma() ) );
    ObjectNode datos = Txt.obj();
    datos.put( "section", id() );

    Narrativa n = ctx.pedido().narrativa;
    String narr = n == null ? null : Txt.recortar( n.resumen, InformeServicio.MAX_NARRATIVA );
    if ( narr != null ) {
      Parrafo p = new Parrafo( narr );
      p.id = "summary-narrativa";
      p.ia = Narrativa.IA.equals( n.origen );
      s.con( p );
    }

    String frase;
    String cuenta;
    Indicadores ind = new Indicadores();
    ind.id = "summary-cuentas";
    if ( c.copia instanceof JobMeta ) {
      JobMeta jm = (JobMeta) c.copia;
      List<SecFlujo.Llamada> llamadas = SecFlujo.llamadas( jm, c.archivo );
      frase = fraseJob( r, jm, llamadas, datos );
      int nEntradas = jm.nrJobEntries();
      cuenta = r.f( "cuenta", Txt.n( r, "n.entrada", nEntradas ), Txt.n( r, "n.hop", jm.nrJobHops() ) );
      ind.con( new Indicador( r.s( "resumen.ind.entradas" ), entero( r, nEntradas ) ) );
      ind.con( new Indicador( r.s( "resumen.ind.hops" ), entero( r, jm.nrJobHops() ) ) );
      List<String> trans = new ArrayList<String>();
      List<String> jobs = new ArrayList<String>();
      for ( SecFlujo.Llamada l : llamadas ) {
        String b = l.base == null ? l.entrada : l.base;
        List<String> d = l.job ? jobs : trans;
        if ( !d.contains( b ) ) {
          d.add( b );
        }
      }
      ind.con( new Indicador( r.s( "resumen.ind.trans" ), entero( r, trans.size() ), detalle( trans ), null ) );
      ind.con( new Indicador( r.s( "resumen.ind.jobs" ), entero( r, jobs.size() ), detalle( jobs ), null ) );
      datos.put( "entries", nEntradas );
      datos.put( "hops", jm.nrJobHops() );
    } else {
      TransMeta tm = (TransMeta) c.copia;
      Analisis a = analizar( tm, ctx.orden(), c.archivo, ctx.idioma() );
      frase = fraseTrans( r, a );
      int nSteps = tm.nrSteps();
      cuenta = r.f( "cuenta", Txt.n( r, "n.step", nSteps ), Txt.n( r, "n.hop", tm.nrTransHops() ) );
      ind.con( new Indicador( r.s( "resumen.ind.steps" ), entero( r, nSteps ) ) );
      ind.con( new Indicador( r.s( "resumen.ind.hops" ), entero( r, tm.nrTransHops() ) ) );
      ind.con( new Indicador( r.s( "resumen.ind.leidos" ), entero( r, a.leidos.size() ), detalle( bases(
        a.leidos ) ), null ) );
      ind.con( new Indicador( r.s( "resumen.ind.escritos" ), entero( r, a.escritos.size() ), detalle( bases(
        a.escritos ) ), null ) );
      if ( !a.tablas.isEmpty() ) {
        ind.con( new Indicador( r.s( "resumen.ind.tablas" ), entero( r, a.tablas.size() ), detalle(
          new ArrayList<String>( a.tablas ) ), null ) );
      }
      datos.put( "steps", nSteps );
      datos.put( "hops", tm.nrTransHops() );
      lista( datos, "reads", a.leidos );
      lista( datos, "writes", a.escritos );
      lista( datos, "tables", a.tablas );
      lista( datos, "queries", a.consultas );
      lista( datos, "operations", a.verbos );
    }
    int conexiones = SecConexiones.conexiones( c.copia ).size();
    if ( conexiones > 0 ) {
      ind.con( new Indicador( r.s( "resumen.ind.conexiones" ), entero( r, conexiones ) ) );
    }
    datos.put( "connections", conexiones );
    s.con( ind );

    boolean vacio = c.copia instanceof JobMeta ? ( (JobMeta) c.copia ).nrJobEntries() == 0
      : ( (TransMeta) c.copia ).nrSteps() == 0;
    s.resumen = vacio ? r.s( "resumen.vacio" ) : frase + " " + cuenta;
    StringBuilder texto = new StringBuilder( s.resumen );
    Seccion metricas = buscar( otras, SeccionId.METRICAS );
    if ( metricas != null && Txt.vacio( metricas.resumen ) != null && !Seccion.OMITIDA.equals( metricas.estado ) ) {
      texto.append( '\n' ).append( metricas.resumen.trim() );
    }
    Parrafo p = new Parrafo( texto.toString() );
    p.id = "summary-texto";
    s.con( p );

    Lista destacados = new Lista();
    destacados.id = "summary-destacados";
    destacados.titulo = r.s( "resumen.lista" );
    ArrayNode dr = datos.putArray( "sections" );
    for ( Seccion o : otras ) {
      if ( o == null || SeccionId.PORTADA.equals( o.id ) || SeccionId.RESUMEN.equals( o.id )
        || SeccionId.NARRATIVA.equals( o.id ) ) {
        continue;
      }
      ObjectNode x = dr.addObject();
      x.put( "id", o.id );
      Txt.poner( x, "status", o.estado );
      Txt.poner( x, "summary", o.resumen );
      Txt.poner( x, "reason", o.motivo );
      if ( Seccion.OMITIDA.equals( o.estado ) || SeccionId.METRICAS.equals( o.id ) ) {
        continue;
      }
      String t = Txt.vacio( o.resumen ) != null ? o.resumen.trim() : Txt.vacio( o.motivo );
      if ( t != null ) {
        destacados.items.add( r.f( "resumen.item", o.titulo, t ) );
      }
    }
    if ( !destacados.items.isEmpty() ) {
      s.con( destacados );
    }
    s.datos = datos;
    return s;
  }

  // ---------------------------------------------------------------- transformaciones

  /** Lo que la frase cuenta de una transformacion. */
  static final class Analisis {
    /** Rutas resueltas (relativas a la carpeta del documento si se puede), sin repetir. */
    final Set<String> leidos = new LinkedHashSet<String>();
    final Set<String> escritos = new LinkedHashSet<String>();
    final Set<String> tablas = new LinkedHashSet<String>();
    /** Nombres de las conexiones que consulta (Table input...). */
    final Set<String> consultas = new LinkedHashSet<String>();
    /** Claves de verbos (v.*) en el orden del flujo. */
    final Set<String> verbos = new LinkedHashSet<String>();
    /** Tipos de steps que escriben sin archivo ni tabla (Mail, Set variables...). */
    final Set<String> otros = new LinkedHashSet<String>();
  }

  static Analisis analizar( TransMeta tm, List<StepMeta> orden, String archivoDoc, String idioma ) {
    Analisis a = new Analisis();
    boolean conErrores = false;
    for ( StepMeta sm : orden ) {
      StepMetaInterface m = sm.getStepMetaInterface();
      String id = sm.getStepID();
      boolean escribe = RunMotor.escribe( sm );
      List<String> archivos = archivos( tm, sm );
      String verbo = VERBOS.get( id );
      if ( verbo != null ) {
        a.verbos.add( verbo );
      }
      StepErrorMeta em = sm.getStepErrorMeta();
      if ( em != null && em.isEnabled() && em.getTargetStep() != null ) {
        conErrores = true;
      }
      if ( m instanceof TableInputMeta ) {
        DatabaseMeta db = ( (TableInputMeta) m ).getDatabaseMeta();
        a.consultas.add( db == null ? "?" : db.getName() );
        continue;
      }
      if ( escribe ) {
        String tabla = tabla( m );
        if ( !archivos.isEmpty() ) {
          for ( String f : archivos ) {
            a.escritos.add( Txt.relativa( f, archivoDoc ) );
          }
        } else if ( tabla != null ) {
          a.tablas.add( tabla );
        } else {
          a.otros.add( NombresPlugin.nombre( StepPluginType.class, id, idioma ) );
        }
      } else {
        for ( String f : archivos ) {
          a.leidos.add( Txt.relativa( f, archivoDoc ) );
        }
      }
    }
    if ( conErrores ) {
      a.verbos.add( "v.errores" );
    }
    return a;
  }

  static String fraseTrans( Rotulos r, Analisis a ) {
    List<String> clausulas = new ArrayList<String>();
    if ( !a.leidos.isEmpty() ) {
      clausulas.add( r.f( a.leidos.size() == 1 ? "lee.archivo.1" : "lee.archivo.n", a.leidos.size(), unir( bases(
        a.leidos ) ) ) );
    }
    if ( !a.consultas.isEmpty() ) {
      clausulas.add( r.f( a.consultas.size() == 1 ? "consulta.1" : "consulta.n", a.consultas.size(), unir(
        new ArrayList<String>( a.consultas ) ) ) );
    }
    List<String> verbos = new ArrayList<String>();
    for ( String v : a.verbos ) {
      verbos.add( r.s( v ) );
    }
    if ( !verbos.isEmpty() ) {
      clausulas.add( Txt.lista( r, verbos ) );
    }
    if ( !a.escritos.isEmpty() ) {
      clausulas.add( r.f( a.escritos.size() == 1 ? "escribe.archivo.1" : "escribe.archivo.n", a.escritos.size(),
        unir( bases( a.escritos ) ) ) );
    }
    if ( !a.tablas.isEmpty() ) {
      clausulas.add( r.f( a.tablas.size() == 1 ? "escribe.tabla.1" : "escribe.tabla.n", a.tablas.size(), unir(
        new ArrayList<String>( a.tablas ) ) ) );
    }
    if ( !a.otros.isEmpty() ) {
      clausulas.add( r.f( "escribe.otro", unir( new ArrayList<String>( a.otros ) ) ) );
    }
    if ( a.leidos.isEmpty() && a.consultas.isEmpty() && a.escritos.isEmpty() && a.tablas.isEmpty()
      && a.otros.isEmpty() ) {
      // nada entra ni sale: la frase dice eso y, si hay, que hace por dentro
      String nada = r.s( "resumen.nada" );
      return verbos.isEmpty() ? nada : Txt.mayuscula( Txt.lista( r, verbos ) ) + ". " + nada;
    }
    return Txt.mayuscula( Txt.clausulas( r, clausulas ) ) + ".";
  }

  /**
   * Archivos que lee o escribe el step, resueltos con las variables de la copia: los tipos comunes
   * por su configuracion y el resto por getResourceDependencies (como ReportMotor.archivosDe).
   */
  static List<String> archivos( TransMeta tm, StepMeta sm ) {
    List<String> crudos = new ArrayList<String>();
    try {
      StepMetaInterface m = sm.getStepMetaInterface();
      if ( m instanceof CsvInputMeta ) {
        crudos.add( ( (CsvInputMeta) m ).getFilename() );
      } else if ( m instanceof TextFileInputMeta ) {
        String[] f = ( (TextFileInputMeta) m ).inputFiles.fileName;
        if ( f != null ) {
          Collections.addAll( crudos, f );
        }
      } else if ( m instanceof TextFileOutputMeta ) {
        TextFileOutputMeta o = (TextFileOutputMeta) m;
        String ext = Txt.vacio( o.getExtension() );
        crudos.add( o.getFileName() + ( ext == null ? "" : "." + ext ) );
      } else {
        List<ResourceReference> refs = m.getResourceDependencies( tm, sm );
        if ( refs != null ) {
          for ( ResourceReference r : refs ) {
            for ( ResourceEntry e : r.getEntries() ) {
              if ( e.getResourcetype() == ResourceEntry.ResourceType.FILE ) {
                crudos.add( e.getResource() );
              }
            }
          }
        }
      }
    } catch ( Throwable e ) {
      // configuracion rara: sin archivos
    }
    List<String> out = new ArrayList<String>();
    VariableSpace vs = crudos.isEmpty() ? null : Txt.variables( tm );
    for ( String c : crudos ) {
      if ( Txt.vacio( c ) != null ) {
        String v = vs.environmentSubstitute( c.trim() );
        if ( !out.contains( v ) ) {
          out.add( v );
        }
      }
    }
    return out;
  }

  /** Tabla destino de un step de salida a base (getSchemaName/getTableName), o null. */
  private static String tabla( StepMetaInterface m ) {
    try {
      if ( m instanceof TableOutputMeta ) {
        TableOutputMeta to = (TableOutputMeta) m;
        return Txt.vacio( to.getSchemaName() ) == null ? to.getTableName() : to.getSchemaName() + "."
          + to.getTableName();
      }
      if ( m.getUsedDatabaseConnections() == null || m.getUsedDatabaseConnections().length == 0 ) {
        return null;
      }
      Method g = m.getClass().getMethod( "getTableName" );
      Object t = g.invoke( m );
      String esquema = null;
      try {
        Object e = m.getClass().getMethod( "getSchemaName" ).invoke( m );
        esquema = e == null ? null : Txt.vacio( e.toString() );
      } catch ( NoSuchMethodException ignored ) {
        // sin esquema
      }
      String tabla = t == null ? null : Txt.vacio( t.toString() );
      return tabla == null ? null : esquema == null ? tabla : esquema + "." + tabla;
    } catch ( Throwable e ) {
      return null;
    }
  }

  // ---------------------------------------------------------------- jobs

  private static String fraseJob( Rotulos r, JobMeta jm, List<SecFlujo.Llamada> llamadas, ObjectNode datos ) {
    List<String> clausulas = new ArrayList<String>();
    String ejecuta = SecFlujo.frasejecuta( r, llamadas );
    if ( ejecuta != null ) {
      clausulas.add( ejecuta );
    }
    Set<String> verbos = new LinkedHashSet<String>();
    for ( JobEntryCopy c : ReportMotor.ordenJob( jm ) ) {
      String id = c.getEntry() == null ? null : c.getEntry().getPluginId();
      String v = id == null ? null : VERBOS_JOB.get( id );
      if ( v != null ) {
        verbos.add( v );
      }
    }
    List<String> vs = new ArrayList<String>();
    for ( String v : verbos ) {
      vs.add( r.s( v ) );
    }
    clausulas.addAll( vs );
    ArrayNode runs = datos.putArray( "runs" );
    for ( SecFlujo.Llamada l : llamadas ) {
      ObjectNode o = runs.addObject();
      o.put( "kind", l.job ? "job" : "transformation" );
      Txt.poner( o, "file", l.mostrar );
    }
    lista( datos, "operations", verbos );
    if ( clausulas.isEmpty() ) {
      return r.s( "resumen.job_nada" );
    }
    if ( ejecuta == null ) {
      return Txt.mayuscula( Txt.lista( r, vs ) ) + ". " + r.s( "resumen.job_nada" );
    }
    return Txt.mayuscula( Txt.lista( r, clausulas ) ) + ".";
  }

  // ---------------------------------------------------------------- comunes

  private static Seccion buscar( List<Seccion> l, String id ) {
    for ( Seccion s : l ) {
      if ( s != null && id.equals( s.id ) ) {
        return s;
      }
    }
    return null;
  }

  private static String entero( Rotulos r, long n ) {
    return String.format( r.locale(), "%,d", n );
  }

  private static String detalle( List<String> l ) {
    if ( l.isEmpty() ) {
      return null;
    }
    String s = unir( l );
    return s.length() > 120 ? s.substring( 0, 119 ) + "…" : s;
  }

  private static List<String> bases( Set<String> rutas ) {
    List<String> out = new ArrayList<String>();
    for ( String r : rutas ) {
      String b = Txt.baseNombre( r );
      if ( b != null && !out.contains( b ) ) {
        out.add( b );
      }
    }
    return out;
  }

  private static String unir( List<String> l ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      sb.append( sb.length() == 0 ? "" : ", " ).append( s );
    }
    return sb.toString();
  }

  private static void lista( ObjectNode o, String clave, Set<String> valores ) {
    ArrayNode a = o.putArray( clave );
    for ( String v : valores ) {
      a.add( v );
    }
  }
}
