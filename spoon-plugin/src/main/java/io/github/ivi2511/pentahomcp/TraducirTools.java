package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;
import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorIpynb;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Opciones;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.Registro;
import io.github.ivi2511.pentahomcp.traductor.TraductorStep;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;
import io.github.ivi2511.pentahomcp.traductor.Verificacion;

/**
 * Herramientas MCP del traductor a Python (0.8.0): {@code translate_to_python} (una transformación o un job a un .py
 * o un notebook, pandas o PySpark, con verificación opcional) y {@code translate_coverage} (qué ids de step y de
 * entrada de job tienen traducción). El diálogo IDC › Traducir a Python… usa {@link #traducir}.
 *
 * <p>Jobs: la parte de jobs ({@code TraductorJob.traducir(JobMeta, Opciones)} y
 * {@code EscritorJob.escribir(ProgramaJob, Opciones)}) se llama por reflexión, así esta clase compila sin ella.
 */
final class TraducirTools {

  /** Las pruebas corren PDI en esta JVM (EntornoKettle); en Spoon, la verificación lanza la JVM de las pruebas. */
  static volatile boolean pdiEnEstaJvm;

  private static final String PAQ = "io.github.ivi2511.pentahomcp.traductor.";

  private TraducirTools() {
  }

  /** Lo que deja una traducción (la herramienta y el diálogo). */
  static final class Hecho {
    File archivo;
    int exactos;
    int aproximados;
    int manuales;
    final List<Bloque> bloques = new ArrayList<>();
    /** null si no se pidió verificar. */
    Verificacion.Resultado verificacion;
    final List<String> avisos = new ArrayList<>();
  }

  // ================================================================ herramientas

  static void register( McpHttpServer s ) {
    s.add( Tools.tool( "translate_to_python",
      "Translates a transformation (.ktr) or a job (.kjb) to readable Python: pandas or PySpark, as a .py script or "
        + "a Jupyter notebook (.ipynb), one block per step with its explanation, notes on what differs from PDI and "
        + "a status per step (exact, approximate, manual = a TODO skeleton with the step settings). If the user did "
        + "not say it, ASK whether they want a .py script or a .ipynb notebook, and pandas or PySpark, before calling. "
        + "Passwords are never written: the code reads them from env PDI_PASSWORD_<CONNECTION>. The file goes next to "
        + "the .ktr by default (<name>.py / <name>.ipynb, <name>_pyspark.* for PySpark) and is never overwritten "
        + "without overwrite=true. verify=true (transformations only) runs the transformation in PDI with the steps "
        + "that write skipped (in a separate JVM, like test_create) and a variant of the Python that dumps what each "
        + "output would write to a temp folder, and compares them output by output (equal / different with the "
        + "first differing rows / not verified with why); nothing is written to the user's files or databases. "
        + "Python for verify: env PENTAHO_LIVE_PYTHON or python on PATH (with pandas; PySpark needs pyspark and Java "
        + "17+).",
      Tools.schema().document()
        .str( "file", "A .ktr or .kjb on disk to translate instead of an open document (absolute path).", false )
        .strEnum( "format", "py (script) or ipynb (notebook). Ask the user if they did not say.", false, "py",
          "ipynb" )
        .strEnum( "engine", "pandas (default) or pyspark. Ask the user if they did not say.", false, "pandas",
          "pyspark" )
        .strEnum( "language", "Language of comments and Markdown cells: es, en or pt (default es).", false, "es",
          "en", "pt" )
        .str( "title", "Title of the notebook / script (default: the transformation name).", false )
        .str( "author", "Author shown in the title.", false )
        .str( "intro", "Own introduction text (Markdown), after the title.", false )
        .bool( "explanations", "An explanation per step (default true).", false )
        .bool( "previews", "Notebook: preview of each DataFrame, head(10) / show(10) (default true).", false )
        .bool( "eda", "Exploratory analysis with matplotlib charts for inputs and outputs (default false).", false )
        .bool( "notes", "Notes on what differs from PDI (default true).", false )
        .str( "output", "Output file (absolute path). Default next to the .ktr / .kjb.", false )
        .bool( "overwrite", "Overwrite the output file if it exists (default false).", false )
        .bool( "verify", "Compare PDI and Python results with the data (default false; transformations only).",
          false )
        .build(),
      TraducirTools::traducirTool ) );

    s.add( Tools.tool( "translate_coverage",
      "Which step ids (transformations) and job-entry ids have a Python translation, with their family and the "
        + "status a step gets with its default settings for pandas and for PySpark (exact / approximate / manual). "
        + "Steps not listed become a MANUAL block (a TODO with the step settings) in translate_to_python.",
      Tools.schema().bool( "with_status", "Translate each step with its default settings to get its status (slower; "
        + "default true).", false ).build(),
      a -> cobertura( Tools.optBool( a, "with_status", true ) ) ) );
  }

  static Object traducirTool( JsonNode a ) throws Exception {
    Opciones o = new Opciones();
    o.formato = "ipynb".equalsIgnoreCase( Tools.optStr( a, "format", "py" ) ) ? "ipynb" : "py";
    o.destino = Destino.de( Tools.optStr( a, "engine", "pandas" ) );
    o.idioma = idioma( Tools.optStr( a, "language", "es" ) );
    o.titulo = Tools.optStr( a, "title", null );
    o.autor = Tools.optStr( a, "author", null );
    o.introduccion = Tools.optStr( a, "intro", null );
    o.explicaciones = Tools.optBool( a, "explanations", true );
    o.vistas = Tools.optBool( a, "previews", true );
    o.eda = Tools.optBool( a, "eda", false );
    o.notas = Tools.optBool( a, "notes", true );
    boolean sobrescribir = Tools.optBool( a, "overwrite", false );
    boolean verificar = Tools.optBool( a, "verify", false );
    String archivo = Tools.optStr( a, "file", null );
    AbstractMeta meta;
    if ( archivo != null ) {
      File f = new File( archivo.trim() );
      if ( !f.isAbsolute() || !f.isFile() ) {
        throw new ToolError( "'file' must be an absolute path to an existing .ktr or .kjb: " + archivo );
      }
      meta = f.getName().toLowerCase( Locale.ROOT ).endsWith( ".kjb" ) ? new JobMeta( f.getAbsolutePath(), null )
        : new TransMeta( f.getAbsolutePath() );
    } else {
      final JsonNode args = a;
      meta = Docs.ui( () -> Docs.resolverSinActivar( args ) );
    }
    String out = Tools.optStr( a, "output", null );
    File salida = out == null ? null : new File( out.trim() );
    if ( salida != null && !salida.isAbsolute() ) {
      throw new ToolError( "'output' must be an absolute path: " + out );
    }
    Hecho h = traducir( meta, o, salida, sobrescribir, verificar );
    return json( h, o );
  }

  static ObjectNode json( Hecho h, Opciones o ) {
    ObjectNode r = Tools.obj();
    r.put( "path", h.archivo.getAbsolutePath() );
    r.put( "format", o.formato );
    r.put( "engine", o.destino.id() );
    ObjectNode c = r.putObject( "counts" );
    c.put( "exact", h.exactos ).put( "approximate", h.aproximados ).put( "manual", h.manuales );
    ArrayNode pasos = r.putArray( "steps" );
    for ( Bloque b : h.bloques ) {
      ObjectNode x = pasos.addObject();
      x.put( "step", b.paso ).put( "type", b.tipo ).put( "status", estadoId( b.estado ) );
      if ( !b.notas.isEmpty() ) {
        ArrayNode n = x.putArray( "notes" );
        for ( String s : b.notas ) {
          n.add( s );
        }
      }
    }
    if ( h.verificacion != null ) {
      r.set( "verification", json( h.verificacion ) );
    }
    if ( !h.avisos.isEmpty() ) {
      ArrayNode w = r.putArray( "warnings" );
      for ( String s : h.avisos ) {
        w.add( s );
      }
    }
    return r;
  }

  static ObjectNode json( Verificacion.Resultado v ) {
    ObjectNode r = Tools.obj();
    r.put( "status", v.estado );
    if ( v.motivo != null ) {
      r.put( "reason", v.motivo );
    }
    if ( v.python != null ) {
      r.put( "python", v.python );
    }
    ArrayNode a = r.putArray( "outputs" );
    for ( Verificacion.Salida s : v.salidas ) {
      ObjectNode x = a.addObject();
      x.put( "step", s.paso ).put( "status", s.estado );
      if ( s.filasPdi >= 0 ) {
        x.put( "rows_pdi", s.filasPdi );
      }
      if ( s.filasPython >= 0 ) {
        x.put( "rows_python", s.filasPython );
      }
      if ( s.motivo != null ) {
        x.put( "reason", s.motivo );
      }
      if ( !s.diferencias.isEmpty() ) {
        ArrayNode d = x.putArray( "differences" );
        for ( String t : s.diferencias ) {
          d.add( t );
        }
      }
    }
    if ( v.log != null ) {
      r.put( "python_log", v.log );
    }
    return r;
  }

  /** exact, approximate o manual (los ids de la API, en inglés). */
  static String estadoId( Estado e ) {
    return e == Estado.EXACTO ? "exact" : e == Estado.APROXIMADO ? "approximate" : "manual";
  }

  static String idioma( String s ) {
    String x = s == null ? "es" : s.trim().toLowerCase( Locale.ROOT );
    return x.startsWith( "en" ) ? "en" : x.startsWith( "pt" ) ? "pt" : "es";
  }

  // ================================================================ traducir

  /** El archivo por defecto: al lado del .ktr / .kjb, nombre.py / nombre.ipynb (nombre_pyspark.* con PySpark). */
  static File salidaPorDefecto( File origen, String nombre, Opciones o ) {
    String base;
    File dir;
    if ( origen != null ) {
      dir = origen.getAbsoluteFile().getParentFile();
      base = origen.getName().replaceFirst( "(?i)\\.(ktr|kjb)$", "" );
    } else {
      dir = new File( System.getProperty( "user.home" ) );
      base = nombre == null || nombre.trim().isEmpty() ? "etl" : nombre.trim().replaceAll( "[\\\\/:*?\"<>|]+", "_" );
    }
    String ext = o.notebook() ? ".ipynb" : ".py";
    return new File( dir, base + ( o.destino == Destino.PYSPARK ? "_pyspark" : "" ) + ext );
  }

  /** El Python para verificar (PENTAHO_LIVE_PYTHON, si no python del PATH); null si no hay. */
  static String python() {
    return Verificacion.python();
  }

  /**
   * Traduce (una copia de) la transformación o el job y escribe el archivo. Fuera del hilo de la UI: con verificar,
   * corre PDI y Python (puede tardar).
   */
  static Hecho traducir( AbstractMeta meta, Opciones o, File salida, boolean sobrescribir, boolean verificar )
    throws Exception {
    if ( meta == null ) {
      throw new ToolError( "There is no transformation or job to translate" );
    }
    File origen = archivo( meta );
    File destino = salida != null ? salida : salidaPorDefecto( origen, meta.getName(), o );
    if ( destino.exists() && !sobrescribir ) {
      throw new ToolError( "The file " + destino + " already exists (overwrite=true to replace it)" );
    }
    String xml = meta.getXML();
    Hecho h = new Hecho();
    h.archivo = destino;
    String texto;
    if ( meta instanceof JobMeta ) {
      texto = traducirJob( (JobMeta) meta, xml, o, h );
      if ( verificar ) {
        h.avisos.add( t( o, "La verificación con los datos es sólo para transformaciones.", "Verification with the "
          + "data is only for transformations.", "A verificação com os dados é só para transformações." ) );
      }
    } else {
      TransMeta copia = copiaTrans( (TransMeta) meta, xml );
      Programa p = TraductorTrans.traducir( copia, o.destino, o.idioma );
      texto = o.notebook() ? EscritorIpynb.escribir( p, o ) : EscritorPy.escribir( p, o );
      contar( h, p.bloques );
      if ( verificar ) {
        h.verificacion = verificar( p, xml, origen, o );
      }
    }
    if ( origen == null ) {
      h.avisos.add( t( o, "La transformación no está guardada: las rutas relativas parten de la carpeta del script.",
        "The transformation is not saved: relative paths start at the script's folder.",
        "A transformação não está salva: os caminhos relativos partem da pasta do script." ) );
    }
    File dir = destino.getAbsoluteFile().getParentFile();
    if ( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
      throw new ToolError( "Cannot create the folder " + dir );
    }
    Files.write( destino.toPath(), texto.getBytes( StandardCharsets.UTF_8 ) );
    return h;
  }

  static File archivo( AbstractMeta meta ) {
    String f = meta.getFilename();
    if ( f == null || f.trim().isEmpty() ) {
      return null;
    }
    return new File( f.replaceFirst( "^file:///?", "" ) ).getAbsoluteFile();
  }

  /** Copia desligada de la transformación (lo que se ve, cambios sin guardar incluidos), con su nombre de archivo. */
  static TransMeta copiaTrans( TransMeta t, String xml ) throws Exception {
    TransMeta c = new TransMeta( new ByteArrayInputStream( xml.getBytes( StandardCharsets.UTF_8 ) ), null, true, null,
      null );
    if ( t.getFilename() != null ) {
      c.setFilename( t.getFilename() );
      c.setInternalKettleVariables();
    }
    return c;
  }

  static void contar( Hecho h, List<Bloque> bloques ) {
    for ( Bloque b : bloques ) {
      h.bloques.add( b );
      if ( b.estado == Estado.EXACTO ) {
        h.exactos++;
      } else if ( b.estado == Estado.APROXIMADO ) {
        h.aproximados++;
      } else {
        h.manuales++;
      }
    }
  }

  /**
   * Un job: TraductorJob.traducir(JobMeta, Opciones) y EscritorJob.escribir(ProgramaJob, Opciones) por reflexión (la
   * parte de jobs). Los bloques: el campo «bloques» del ProgramaJob, si lo tiene.
   */
  @SuppressWarnings( "unchecked" )
  static String traducirJob( JobMeta jm, String xml, Opciones o, Hecho h ) throws Exception {
    Class<?> tj;
    Class<?> ej;
    try {
      tj = Class.forName( PAQ + "TraductorJob" );
      ej = Class.forName( PAQ + "EscritorJob" );
    } catch ( ClassNotFoundException e ) {
      throw new ToolError( "Translating jobs is not available in this build" );
    }
    Method tr = buscarMetodo( tj, "traducir", JobMeta.class, Opciones.class );
    if ( tr == null ) {
      throw new ToolError( "Translating jobs is not available in this build (TraductorJob.traducir)" );
    }
    JobMeta copia = new JobMeta( new ByteArrayInputStream( xml.getBytes( StandardCharsets.UTF_8 ) ), null, null );
    if ( jm.getFilename() != null ) {
      copia.setFilename( jm.getFilename() );
    }
    Object prog;
    try {
      prog = tr.invoke( null, copia, o );
    } catch ( java.lang.reflect.InvocationTargetException e ) {
      throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
    }
    Method es = buscarMetodo( ej, "escribir", prog.getClass(), Opciones.class );
    if ( es == null ) {
      throw new ToolError( "Translating jobs is not available in this build (EscritorJob.escribir)" );
    }
    try {
      java.lang.reflect.Field f = prog.getClass().getField( "bloques" );
      Object l = f.get( prog );
      if ( l instanceof Collection ) {
        List<Bloque> bs = new ArrayList<>();
        for ( Object x : (Collection<Object>) l ) {
          if ( x instanceof Bloque ) {
            bs.add( (Bloque) x );
          }
        }
        contar( h, bs );
      }
    } catch ( NoSuchFieldException e ) {
      // sin bloques visibles: sólo el archivo
    }
    try {
      return String.valueOf( es.invoke( null, prog, o ) );
    } catch ( java.lang.reflect.InvocationTargetException e ) {
      throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
    }
  }

  private static Method buscarMetodo( Class<?> c, String nombre, Class<?>... tipos ) {
    for ( Method m : c.getMethods() ) {
      if ( !m.getName().equals( nombre ) || m.getParameterCount() != tipos.length ) {
        continue;
      }
      boolean ok = true;
      for ( int i = 0; i < tipos.length; i++ ) {
        ok &= m.getParameterTypes()[ i ].isAssignableFrom( tipos[ i ] );
      }
      if ( ok ) {
        return m;
      }
    }
    return null;
  }

  // ================================================================ verificación

  /** Verifica (ver {@link Verificacion}): PDI con las pruebas doradas, Python con la variante. */
  static Verificacion.Resultado verificar( Programa p, String xml, File origen, Opciones o ) throws Exception {
    if ( origen == null ) {
      Verificacion.Resultado r = new Verificacion.Resultado();
      r.motivo = t( o, "Guarda la transformación para verificarla (los archivos relativos parten de su carpeta).",
        "Save the transformation to verify it (relative files start at its folder).",
        "Salve a transformação para verificá-la (os arquivos relativos partem da sua pasta)." );
      for ( Verificacion.Volcado v : Verificacion.volcados( p ) ) {
        Verificacion.Salida s = new Verificacion.Salida();
        s.paso = v.paso;
        s.motivo = r.motivo;
        r.salidas.add( s );
      }
      return r;
    }
    File tmp = Files.createTempDirectory( "pentaho-live-verif-ktr-" ).toFile();
    try {
      File ktr = new File( tmp, origen.getName() );
      Files.write( ktr.toPath(), xml.getBytes( StandardCharsets.UTF_8 ) );
      return Verificacion.verificar( p, ktr, origen.getParentFile(), new MotorPruebas( origen ), o.idioma );
    } finally {
      PruebasProcesos.borrar( tmp );
    }
  }

  /** El lado de PDI de la verificación con la maquinaria de test_create (modo snapshot, sin datasets). */
  static final class MotorPruebas implements Verificacion.Motor {
    final File original;

    MotorPruebas( File original ) {
      this.original = original;
    }

    @Override
    public void pdiYComparar( File ktr, Set<String> saltar, Map<String, File> csvPython, Verificacion.Resultado r,
                              String idioma ) throws Exception {
      File carpeta = java.nio.file.Files.createTempDirectory( "pentaho-live-verif-pdi-" ).toFile();
      List<File> temporales = new ArrayList<>();
      temporales.add( carpeta );
      try {
        TransMeta tm = new TransMeta( ktr.getAbsolutePath() );
        PruebasMain.Creacion c = new PruebasMain.Creacion();
        c.nombre = "verificacion";
        c.carpeta = carpeta.getAbsolutePath();
        c.original = original.getAbsolutePath();
        c.entradas = new ArrayList<>(); // sin datasets: lee las fuentes de verdad (sólo lee)
        for ( Verificacion.Salida s : r.salidas ) {
          if ( PruebasMotor.buscar( tm, s.paso ) != null ) {
            c.salidas.add( s.paso );
          }
        }
        c.ajustes = new ArrayList<>();
        for ( StepMeta sm : tm.getSteps() ) {
          if ( saltar.contains( sm.getName() ) || RunMotor.escribe( sm ) ) {
            c.ajustes.add( DefPrueba.Ajuste.saltar( sm.getName(), "writes outside the transformation ("
              + sm.getStepID() + ")" ) );
          }
        }
        c.variables.putAll( PruebasTools.variables( tm ) );
        DefPrueba.Entorno e = new DefPrueba.Entorno();
        e.locale = Locale.getDefault().toString();
        e.zonaHoraria = TimeZone.getDefault().getID();
        e.timeoutSeg = 600;
        c.entorno = e;
        c.reemplazar = true;
        c.ignorarInestables = false;
        ResultadoPrueba rp;
        if ( pdiEnEstaJvm ) {
          PruebasMotor.Opciones op = new PruebasMotor.Opciones();
          op.ktr = ktr;
          rp = PruebasMotor.crear( c.pedido(), op );
        } else {
          List<ResultadoPrueba> rs = PruebasProcesos.correrYEsperar( java.util.Collections.singletonList(
            PruebasProcesos.Tarea.crear( c, ktr ) ), 15 * 60 * 1000L, temporales );
          rp = rs.isEmpty() ? null : rs.get( 0 );
        }
        if ( rp == null || rp.creada == null ) {
          String m = "PDI: " + ( rp == null ? "no result" : rp.mensaje );
          for ( Verificacion.Salida s : r.salidas ) {
            if ( Verificacion.SIN_VERIFICAR.equals( s.estado ) ) {
              s.motivo = s.motivo == null ? m : s.motivo;
            }
          }
          r.motivo = m;
          return;
        }
        DefPrueba d = PruebasMotor.cargar( new File( carpeta, c.nombre + DefPrueba.EXTENSION ) );
        for ( Verificacion.Salida s : r.salidas ) {
          DefPrueba.Salida ds = null;
          for ( DefPrueba.Salida x : d.salidas ) {
            if ( x.step.equalsIgnoreCase( s.paso ) ) {
              ds = x;
            }
          }
          File dorado = ds == null ? null : PruebasMotor.dorado( carpeta, d, ds );
          if ( dorado == null || !dorado.isFile() ) {
            s.motivo = t( idioma, "PDI no dejó filas para este step.", "PDI left no rows for this step.",
              "O PDI não deixou linhas para este step." );
            continue;
          }
          CorrectorTabla esp = CorrectorTabla.esperada( dorado, d.csvDorado, PruebasMotor.nombresColumnas(
            ds.columnas ) );
          s.filasPdi = esp.cantidadFilas();
          File py = csvPython.get( s.paso );
          if ( py == null ) {
            if ( s.motivo == null ) {
              s.motivo = t( idioma, "Python no llegó a este step.", "Python did not reach this step.",
                "O Python não chegou a este step." );
            }
            continue;
          }
          CorrectorTabla real = CorrectorTabla.leer( py, new CorrectorTabla.Lectura().separador( "," )
            .encoding( "UTF-8", "UTF-8" ) );
          s.filasPython = real.cantidadFilas();
          Comparacion cmp = CorrectorComparar.comparar( esp, real, ds.columnas, ds.comparar, d.entorno.politica()
            .localeJava(), null );
          ResultadoPrueba.Resumen x = new ResultadoPrueba.Resumen( s.paso, ResultadoPrueba.Resumen.STEP );
          PruebasMotor.resumir( x, cmp, ds.comparar );
          s.motivo = null;
          s.estado = x.paso ? Verificacion.IGUAL : Verificacion.DISTINTO;
          if ( !x.paso ) {
            diferencias( s, cmp, idioma );
          }
        }
      } finally {
        PruebasProcesos.borrar( carpeta );
      }
    }
  }

  /** Las primeras diferencias, legibles. */
  static void diferencias( Verificacion.Salida s, Comparacion c, String idioma ) {
    if ( !c.columnas.faltan.isEmpty() ) {
      s.diferencias.add( t( idioma, "Columnas que faltan en "
        + "Python: ", "Columns missing in Python: ", "Colunas que faltam no Python: " ) + c.columnas.faltan );
    }
    if ( !c.columnas.sobran.isEmpty() ) {
      s.diferencias.add( t( idioma, "Columnas de más en Python: ", "Extra columns in Python: ",
        "Colunas a mais no Python: " ) + c.columnas.sobran );
    }
    if ( c.filas.reales != c.filas.esperadas ) {
      s.diferencias.add( t( idioma, "Filas: PDI ", "Rows: PDI ", "Linhas: PDI " ) + c.filas.esperadas + ", Python "
        + c.filas.reales );
    }
    int n = 0;
    for ( Comparacion.Ejemplo e : c.ejemplos.distintas ) {
      if ( n++ >= 5 ) {
        break;
      }
      s.diferencias.add( ( e.fila != null ? t( idioma, "fila ", "row ", "linha " ) + e.fila + " " : "" ) + ( e.clave
        != null ? "[" + e.clave + "] " : "" ) + ( e.columna == null ? "" : e.columna + ": " ) + "PDI=" + e.esperado
        + " Python=" + e.real );
    }
    for ( Comparacion.Ejemplo e : c.ejemplos.faltan ) {
      if ( n++ >= 5 ) {
        break;
      }
      s.diferencias.add( t( idioma, "falta en Python: ", "missing in Python: ", "falta no Python: " ) + ( e.clave
        != null ? e.clave : String.valueOf( e.fila ) ) );
    }
    for ( Comparacion.Ejemplo e : c.ejemplos.sobran ) {
      if ( n++ >= 5 ) {
        break;
      }
      s.diferencias.add( t( idioma, "sobra en Python: ", "extra in Python: ", "sobra no Python: " ) + ( e.clave
        != null ? e.clave : String.valueOf( e.fila ) ) );
    }
  }

  // ================================================================ cobertura

  /** Una fila de la cobertura. */
  static final class Cubierto {
    String id;
    String tipo; // step / job_entry
    String familia;
    String nombre;
    String pandas;
    String pyspark;
  }

  /** Los ids con traducción: los steps del Registro y, si está la parte de jobs, las entradas de su registro. */
  static List<Cubierto> cubiertos( boolean conEstado ) {
    List<Cubierto> r = new ArrayList<>();
    for ( Map.Entry<String, TraductorStep> e : new TreeMap<>( Registro.steps() ).entrySet() ) {
      Cubierto c = new Cubierto();
      c.id = e.getKey();
      c.tipo = "step";
      c.familia = familia( e.getValue() );
      PluginInterface pi = PluginRegistry.getInstance().findPluginWithId( StepPluginType.class, e.getKey() );
      c.nombre = pi == null ? "" : pi.getName();
      if ( conEstado ) {
        c.pandas = estadoPorDefecto( pi, Destino.PANDAS );
        c.pyspark = estadoPorDefecto( pi, Destino.PYSPARK );
      }
      r.add( c );
    }
    for ( Map.Entry<String, Object> e : entradasJob().entrySet() ) {
      Cubierto c = new Cubierto();
      c.id = e.getKey();
      c.tipo = "job_entry";
      c.familia = familia( e.getValue() );
      c.nombre = "";
      r.add( c );
    }
    return r;
  }

  /** La familia por la clase que registra el traductor (TradEntradas → inputs...). */
  static String familia( Object traductor ) {
    if ( traductor == null ) {
      return "";
    }
    String n = traductor.getClass().getName();
    n = n.substring( n.lastIndexOf( '.' ) + 1 );
    int k = n.indexOf( '$' );
    if ( k > 0 ) {
      n = n.substring( 0, k );
    }
    return n.startsWith( "Trad" ) && n.length() > 4 ? n.substring( 4 ) : n;
  }

  /** Las entradas de job con traducción, por reflexión (RegistroJob.entradas() o TraductorJob.entradas()). */
  @SuppressWarnings( "unchecked" )
  static Map<String, Object> entradasJob() {
    for ( String[] cm : new String[][] { { "RegistroJob", "entradas" }, { "TraductorJob", "entradas" },
      { "RegistroJob", "steps" } } ) {
      try {
        Method m = Class.forName( PAQ + cm[ 0 ] ).getMethod( cm[ 1 ] );
        Object x = m.invoke( null );
        if ( x instanceof Map ) {
          return new TreeMap<>( (Map<String, Object>) x );
        }
      } catch ( Exception | LinkageError e ) {
        // sin la parte de jobs
      }
    }
    return new LinkedHashMap<>();
  }

  /** El estado que da un step con su configuración por defecto (en una transformación de un solo step). */
  static String estadoPorDefecto( PluginInterface pi, Destino d ) {
    if ( pi == null ) {
      return "";
    }
    try {
      StepMetaInterface smi = (StepMetaInterface) PluginRegistry.getInstance().loadClass( pi );
      smi.setDefault();
      TransMeta t = new TransMeta();
      t.setName( "cobertura" );
      StepMeta sm = new StepMeta( pi.getIds()[ 0 ], "Paso", smi );
      t.addStep( sm );
      Programa p = TraductorTrans.traducir( t, d, "en" );
      return p.bloques.isEmpty() ? "" : estadoId( p.bloques.get( 0 ).estado );
    } catch ( Exception | LinkageError e ) {
      return "?";
    }
  }

  static Object cobertura( boolean conEstado ) {
    ObjectNode r = Tools.obj();
    List<Cubierto> l = cubiertos( conEstado );
    int pasos = 0;
    int entradas = 0;
    ArrayNode s = r.putArray( "steps" );
    ArrayNode j = r.putArray( "job_entries" );
    for ( Cubierto c : l ) {
      ObjectNode x = ( "step".equals( c.tipo ) ? s : j ).addObject();
      x.put( "id", c.id ).put( "family", c.familia );
      if ( c.nombre != null && !c.nombre.isEmpty() ) {
        x.put( "name", c.nombre );
      }
      if ( c.pandas != null ) {
        x.put( "pandas", c.pandas );
      }
      if ( c.pyspark != null ) {
        x.put( "pyspark", c.pyspark );
      }
      if ( "step".equals( c.tipo ) ) {
        pasos++;
      } else {
        entradas++;
      }
    }
    r.put( "step_count", pasos );
    r.put( "job_entry_count", entradas );
    r.put( "note", "Steps and job entries not listed become a MANUAL block (a TODO with their settings). Statuses are "
      + "for the default settings of each step: some options can make a step approximate or manual." );
    return r;
  }

  static String t( Opciones o, String es, String en, String pt ) {
    return t( o.idioma, es, en, pt );
  }

  static String t( String idioma, String es, String en, String pt ) {
    return "en".equals( idioma ) ? en : "pt".equals( idioma ) ? pt : es;
  }
}
