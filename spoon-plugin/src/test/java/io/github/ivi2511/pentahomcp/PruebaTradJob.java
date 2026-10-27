package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.pentaho.di.core.Result;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.special.JobEntrySpecial;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;

import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorJob;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Opciones;
import io.github.ivi2511.pentahomcp.traductor.ProgramaJob;
import io.github.ivi2511.pentahomcp.traductor.TraductorJob;

/**
 * Traductor de jobs (0.8.0): estructura del script (funciones por entrada, sub-jobs, main con el flujo), los ejemplos
 * de prueba y del curso, todas las entradas de PDI 9.4 sin excepciones, y equivalencia con Kitchen: los mismos jobs
 * (archivos en una carpeta temporal, camino de fallo, bucle, varios hops) corren en Kettle y en Python y dejan los
 * mismos archivos y el mismo resultado.
 */
public class PruebaTradJob {

  private File sal;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    sal = EntornoKettle.salida( "tradjob" );
  }

  private static Opciones op( Destino d, String formato ) {
    Opciones o = new Opciones();
    o.destino = d;
    o.formato = formato;
    return o;
  }

  private File escribir( ProgramaJob p, String nombre, Destino d ) throws Exception {
    File f = new File( sal, nombre + "_" + d.id() + ".py" );
    Files.write( f.toPath(), EscritorJob.escribir( p, op( d, "py" ) ).getBytes( StandardCharsets.UTF_8 ) );
    String nb = EscritorJob.escribir( p, op( d, "ipynb" ) );
    contiene( nb, "\"nbformat\" : 4", nombre + ": notebook" );
    contiene( nb, "ok = main()", nombre + ": celda final" );
    Files.write( new File( sal, nombre + "_" + d.id() + ".ipynb" ).toPath(), nb.getBytes( StandardCharsets.UTF_8 ) );
    PythonPrueba.compila( f );
    return f;
  }

  // ---------------------------------------------------------------- ejemplos

  public void pruebaSecuencia() throws Exception {
    JobMeta jm = EntornoKettle.job( "10" );
    for ( Destino d : Destino.values() ) {
      ProgramaJob p = TraductorJob.traducir( jm, op( d, "py" ) );
      String py = EscritorJob.escribir( p, op( d, "py" ) );
      contiene( py, "def tr_generar_constantes():", "una función por transformación" );
      contiene( py, "with con_variables({\"ARCHIVO_SALIDA\": \"01_desde_job\"}):", "parámetro de la entrada" );
      contiene( py, "        if not tr_generar_constantes():\n            return e_abortar()\n", "camino de fallo recto" );
      contiene( py, "    except Abortar as e:", "Abort corta el job" );
      contiene( py, "    return e_success()", "fin con éxito" );
      contiene( py, "RESULT = {}", "RESULT" );
      contiene( py, "sys.exit(0 if main() else 1)", "código de salida" );
      igual( 2, p.transformaciones().size(), "dos transformaciones" );
      if ( d == Destino.PYSPARK ) {
        igual( 1, py.split( "getOrCreate\\(\\)", -1 ).length - 1, "una sola SparkSession" );
      }
      escribir( p, "10_job_secuencia", d );
    }
  }

  public void pruebaPadre() throws Exception {
    JobMeta jm = EntornoKettle.job( "11" );
    for ( Destino d : Destino.values() ) {
      ProgramaJob p = TraductorJob.traducir( jm, op( d, "py" ) );
      String py = EscritorJob.escribir( p, op( d, "py" ) );
      contiene( py, "def job_correr_job_secuencia():", "sub-job como función" );
      contiene( py, "def tr_generar_constantes():", "las transformaciones del sub-job" );
      contiene( py, "log.info(f\"11_job_padre - Job padre arrancando. FECHA_PROCESO={VARS['FECHA_PROCESO']}",
        "write to log con variables" );
      contiene( py, "\"FECHA_PROCESO\": os.environ.get(\"FECHA_PROCESO\", \"2026-10-20\")", "parámetro del job" );
      escribir( p, "11_job_padre", d );
    }
  }

  public void pruebaCurso() throws Exception {
    File ej = new File( EntornoKettle.recursos(), "../../../../ejemplo/solucion-referencia/job_carga_dw.kjb" )
      .getCanonicalFile();
    JobMeta jm = new JobMeta( ej.getAbsolutePath(), null );
    for ( Destino d : Destino.values() ) {
      ProgramaJob p = TraductorJob.traducir( jm, op( d, "py" ) );
      String py = EscritorJob.escribir( p, op( d, "py" ) );
      igual( 5, p.transformaciones().size(), "cinco transformaciones" );
      contiene( py, "def e_comprobar_ficheros_de_entrada():", "FILES_EXIST" );
      contiene( py, "ejecutar_sql(" + ( d == Destino.PANDAS ? "engine_" : "jdbc_" ) + "dw_grano_de_oro, sql)",
        "SQL con la conexión del job" );
      contiene( py, "DROP TABLE IF EXISTS hechos_ventas;", "SQL legible" );
      contiene( py, "        if not tr_cargar_dim_tienda():\n            return rama_registrar_el_error()\n",
        "rama de fallo compartida como función" );
      noContiene( py, "\"user\": \"${", "sin variables sin resolver" );
      noContiene( py, "OneDrive", "sin rutas personales" );
      noContiene( py, "ivanc", "sin rutas personales" );
      escribir( p, "job_carga_dw", d );
    }
  }

  /** Todas las entradas de PDI 9.4 (con su configuración por defecto) se traducen sin excepciones y compilan. */
  public void pruebaTodasLasEntradas() throws Exception {
    PluginRegistry r = PluginRegistry.getInstance();
    JobMeta jm = new JobMeta();
    jm.setName( "todas" );
    JobEntrySpecial ini = new JobEntrySpecial( "START", true, false );
    JobEntryCopy anterior = new JobEntryCopy( ini );
    anterior.setLocation( 0, 0 );
    jm.addJobEntry( anterior );
    int n = 0;
    List<String> ids = new ArrayList<>();
    for ( PluginInterface pi : r.getPlugins( JobEntryPluginType.class ) ) {
      String id = pi.getIds()[0];
      if ( id.equals( "SPECIAL" ) || id.equals( "TRANS" ) || id.equals( "JOB" ) ) {
        continue;
      }
      JobEntryInterface e;
      try {
        e = (JobEntryInterface) r.loadClass( pi );
      } catch ( Throwable t ) {
        continue;
      }
      e.setName( "e " + id );
      e.setPluginId( id );
      JobEntryCopy c = new JobEntryCopy( e );
      c.setLocation( ++n * 10, 0 );
      jm.addJobEntry( c );
      jm.addJobHop( new JobHopMeta( anterior, c ) );
      anterior = c;
      ids.add( id );
    }
    cierto( ids.size() > 60, "hay entradas: " + ids.size() );
    for ( Destino d : Destino.values() ) {
      ProgramaJob p = TraductorJob.traducir( jm, op( d, "py" ) );
      igual( ids.size(), p.funciones.size(), "una función por entrada" );
      StringBuilder cobertura = new StringBuilder();
      for ( ProgramaJob.Funcion f : p.funciones ) {
        cobertura.append( f.bloque.tipo ).append( '\t' ).append( f.bloque.estado.id() ).append( '\t' ).append(
          f.bloque.notas.isEmpty() ? "" : f.bloque.notas.get( 0 ) ).append( '\n' );
      }
      Files.write( new File( sal, "cobertura_" + d.id() + ".tsv" ).toPath(), cobertura.toString().getBytes(
        StandardCharsets.UTF_8 ) );
      escribir( p, "todas", d );
    }
  }

  // ---------------------------------------------------------------- equivalencia con Kitchen

  /** Un job de prueba en XML: entradas (nombre, tipo, XML interior) y hops (desde, hasta, Y/N/U). */
  static final class JobXml {
    final StringBuilder entradas = new StringBuilder();
    final StringBuilder hops = new StringBuilder();
    int x = 50;
    final String nombre;
    /** Otros archivos del job (sub-jobs): ruta relativa → contenido; van a las dos carpetas. */
    final java.util.Map<String, String> otros = new java.util.LinkedHashMap<>();
    String conexion = "";

    JobXml( String nombre ) {
      this.nombre = nombre;
      entrada( "START", "SPECIAL", "<start>Y</start><dummy>N</dummy><repeat>N</repeat><schedulerType>0"
        + "</schedulerType><intervalSeconds>0</intervalSeconds><intervalMinutes>60</intervalMinutes><hour>12</hour>"
        + "<minutes>0</minutes><weekDay>1</weekDay><DayOfMonth>1</DayOfMonth>" );
    }

    JobXml entrada( String n, String tipo, String xml ) {
      entradas.append( "<entry><name>" ).append( n ).append( "</name><description/><type>" ).append( tipo ).append(
        "</type>" ).append( xml ).append( "<parallel>N</parallel><draw>Y</draw><nr>0</nr><xloc>" ).append( x += 80 )
        .append( "</xloc><yloc>100</yloc></entry>\n" );
      return this;
    }

    /** cond: "U" incondicional, "Y" si va bien, "N" si falla. */
    JobXml hop( String de, String a, String cond ) {
      hops.append( "<hop><from>" ).append( de ).append( "</from><to>" ).append( a ).append( "</to><from_nr>0"
        + "</from_nr><to_nr>0</to_nr><enabled>Y</enabled><evaluation>" ).append( "N".equals( cond ) ? "N" : "Y" )
        .append( "</evaluation><unconditional>" ).append( "U".equals( cond ) ? "Y" : "N" ).append(
          "</unconditional></hop>\n" );
      return this;
    }

    String xml() {
      return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<job><name>" + nombre + "</name><description/>"
        + "<parameters></parameters>" + conexion + "<entries>\n" + entradas + "</entries><hops>\n" + hops + "</hops><notepads>"
        + "</notepads></job>\n";
    }
  }

  private static final String D = "${Internal.Entry.Current.Directory}";

  private static String escribirArchivo( String ruta, String texto, boolean anadir ) {
    return "<filename>" + D + "/" + ruta + "</filename><createParentFolder>Y</createParentFolder><appendFile>" + ( anadir
      ? "Y" : "N" ) + "</appendFile><content>" + texto + "</content><encoding>UTF-8</encoding>";
  }

  /** Corre el job en Kettle y su traducción en Python (pandas) en carpetas gemelas y compara archivos y resultado. */
  private void comparar( JobXml j ) throws Exception {
    comparar( j, false );
  }

  /** Con spark: además corre la traducción PySpark (una sola SparkSession) y compara sus archivos. */
  private void comparar( JobXml j, boolean spark ) throws Exception {
    if ( PythonPrueba.python() == null ) {
      Verificador.saltear( "sin Python" );
    }
    File raiz = new File( sal, "kitchen_" + j.nombre );
    EntornoKettle.borrar( raiz.toPath() );
    File k = new File( raiz, "kettle" );
    File py = new File( raiz, "python" );
    k.mkdirs();
    py.mkdirs();
    File kjb = new File( k, j.nombre + ".kjb" );
    EntornoKettle.escribir( kjb, j.xml() );
    for ( java.util.Map.Entry<String, String> o : j.otros.entrySet() ) {
      EntornoKettle.escribir( new File( k, o.getKey() ), o.getValue() );
      EntornoKettle.escribir( new File( py, o.getKey() ), o.getValue() );
    }
    JobMeta jm = new JobMeta( kjb.getAbsolutePath(), null );
    Job job = new Job( null, jm );
    job.setLogLevel( LogLevel.MINIMAL );
    job.initializeVariablesFrom( null );
    jm.setInternalKettleVariables( job );
    job.copyParametersFrom( jm );
    job.activateParameters();
    job.start();
    job.waitUntilFinished();
    Result r = job.getResult();
    boolean okKettle = r != null && r.getResult() && r.getNrErrors() == 0;
    ProgramaJob p = TraductorJob.traducir( jm, op( Destino.PANDAS, "py" ) );
    File script = new File( py, j.nombre + ".py" );
    EntornoKettle.escribir( script, EscritorJob.escribir( p, op( Destino.PANDAS, "py" ) ) );
    kjb.renameTo( new File( raiz, j.nombre + ".kjb" ) );
    PythonPrueba.Resultado rp = PythonPrueba.correr( py, 120, PythonPrueba.python(), script.getAbsolutePath() );
    script.renameTo( new File( raiz, j.nombre + ".py" ) );
    igual( okKettle, rp.codigo == 0, j.nombre + ": mismo resultado (Kettle " + ( r == null ? "?" : r.getResult() + "/"
      + r.getNrErrors() ) + ", Python " + rp.codigo + "): " + rp.salida );
    igual( listado( k ), listado( py ), j.nombre + ": mismos archivos" );
    if ( spark && PythonPrueba.tiene( "pyspark" ) ) {
      File ps = new File( raiz, "pyspark" );
      File sp = new File( ps, j.nombre + ".py" );
      ProgramaJob q = TraductorJob.traducir( new JobMeta( new File( raiz, j.nombre + ".kjb" ).getAbsolutePath(), null ),
        op( Destino.PYSPARK, "py" ) );
      EntornoKettle.escribir( sp, EscritorJob.escribir( q, op( Destino.PYSPARK, "py" ) ) );
      String java = new File( System.getProperty( "java.home" ) ).getAbsolutePath().replace( "\\", "/" );
      PythonPrueba.Resultado rs = PythonPrueba.correr( ps, 300, PythonPrueba.python(), "-c", "import os, runpy; "
        + "os.environ.setdefault('JAVA_HOME', '" + java + "'); runpy.run_path('" + sp.getName() + "', run_name="
        + "'__main__')" );
      sp.renameTo( new File( raiz, j.nombre + "_pyspark.py" ) );
      igual( okKettle, rs.codigo == 0, j.nombre + ": mismo resultado en PySpark: " + rs.salida );
      igual( listado( k ), listado( ps ), j.nombre + ": mismos archivos en PySpark" );
    }
  }

  private static String listado( File dir ) throws Exception {
    TreeMap<String, String> m = new TreeMap<>();
    listar( dir, "", m );
    return m.toString();
  }

  private static void listar( File dir, String pre, TreeMap<String, String> m ) throws Exception {
    File[] hs = dir.listFiles();
    if ( hs == null ) {
      return;
    }
    for ( File h : hs ) {
      if ( h.isDirectory() ) {
        m.put( pre + h.getName() + "/", "" );
        listar( h, pre + h.getName() + "/", m );
      } else if ( h.getName().endsWith( ".zip" ) || h.getName().endsWith( ".sqlite" ) ) {
        m.put( pre + h.getName(), "binario" );
      } else {
        m.put( pre + h.getName(), new String( Files.readAllBytes( h.toPath() ), StandardCharsets.UTF_8 ) );
      }
    }
  }

  /** Carpetas y archivos: crear, escribir, copiar, comprimir, descomprimir, borrar, comprobar y variables. */
  public void pruebaKitchenArchivos() throws Exception {
    JobXml j = new JobXml( "archivos" )
      .entrada( "Crear carpeta", "CREATE_FOLDER", "<foldername>" + D + "/out</foldername><fail_of_folder_exists>N"
        + "</fail_of_folder_exists>" )
      .entrada( "Crear vacio", "CREATE_FILE", "<filename>" + D + "/out/vacio.txt</filename><fail_if_file_exists>Y"
        + "</fail_if_file_exists><add_filename_result>N</add_filename_result>" )
      .entrada( "Escribir", "WRITE_TO_FILE", escribirArchivo( "out/hola.txt", "hola", false ) )
      .entrada( "Escribir csv", "WRITE_TO_FILE", escribirArchivo( "out/datos.csv", "a;b", false ) )
      .entrada( "Copiar", "COPY_FILES", "<copy_empty_folders>Y</copy_empty_folders><arg_from_previous>N"
        + "</arg_from_previous><overwrite_files>N</overwrite_files><include_subfolders>N</include_subfolders>"
        + "<remove_source_files>N</remove_source_files><add_result_filesname>N</add_result_filesname>"
        + "<destination_is_a_file>N</destination_is_a_file><create_destination_folder>Y</create_destination_folder>"
        + "<fields><field><source_filefolder>" + D + "/out</source_filefolder><destination_filefolder>" + D
        + "/copia</destination_filefolder><wildcard>.*\\.txt</wildcard></field></fields>" )
      .entrada( "Comprimir", "ZIP_FILE", "<zipfilename>" + D + "/z.zip</zipfilename><compressionrate>1"
        + "</compressionrate><ifzipfileexists>2</ifzipfileexists><wildcard/><wildcardexclude/><sourcedirectory>" + D
        + "/copia</sourcedirectory><movetodirectory/><afterzip>0</afterzip><addfiletoresult>N</addfiletoresult>"
        + "<isfromprevious>N</isfromprevious><createparentfolder>N</createparentfolder><include_subfolders>N"
        + "</include_subfolders>" )
      .entrada( "Descomprimir", "UNZIP", "<zipfilename>" + D + "/z.zip</zipfilename><wildcard>.*hola.*</wildcard>"
        + "<wildcardexclude/><targetdirectory>" + D + "/unz</targetdirectory><afterunzip>0</afterunzip>"
        + "<createfolder>Y</createfolder><iffileexists>SKIP</iffileexists><success_condition>success_if_no_errors"
        + "</success_condition>" )
      .entrada( "Borrar", "DELETE_FILE", "<filename>" + D + "/out/vacio.txt</filename><fail_if_file_not_exists>Y"
        + "</fail_if_file_not_exists>" )
      .entrada( "Borrar csv", "DELETE_FILES", "<arg_from_previous>N</arg_from_previous><include_subfolders>N"
        + "</include_subfolders><fields><field><name>" + D + "/out</name><filemask>.*\\.csv</filemask></field>"
        + "</fields>" )
      .entrada( "Existen", "FILES_EXIST", "<filename/><fields><field><name>" + D + "/out/hola.txt</name></field>"
        + "<field><name>" + D + "/unz/hola.txt</name></field></fields>" )
      .entrada( "Vacia", "FOLDER_IS_EMPTY", "<foldername>" + D + "/unz</foldername><include_subfolders>N"
        + "</include_subfolders><specify_wildcard>N</specify_wildcard><wildcard/>" )
      .entrada( "No vacia", "WRITE_TO_FILE", escribirArchivo( "out/no_vacia.txt", "ok", false ) )
      .entrada( "Variables", "SET_VARIABLES", "<replacevars>Y</replacevars><filename/><file_variable_type>JVM"
        + "</file_variable_type><fields><field><variable_name>CUENTA</variable_name><variable_value>3"
        + "</variable_value><variable_type>CURRENT_JOB</variable_type></field></fields>" )
      .entrada( "Evaluar", "SIMPLE_EVAL", "<valuetype>variable</valuetype><fieldname/><variablename>${CUENTA}"
        + "</variablename><fieldtype>number</fieldtype><mask/><comparevalue>2</comparevalue><minvalue/><maxvalue/>"
        + "<successcondition>equal</successcondition><successnumbercondition>greater</successnumbercondition>"
        + "<successbooleancondition>false</successbooleancondition><successwhenvarset>N</successwhenvarset>" )
      .entrada( "Mayor", "WRITE_TO_FILE", escribirArchivo( "out/var.txt", "cuenta=${CUENTA}", false ) )
      .entrada( "Exito", "SUCCESS", "" )
      .entrada( "Fallo", "WRITE_TO_FILE", escribirArchivo( "fallo.txt", "fallo", true ) )
      .entrada( "Abortar", "ABORT", "<message>fallo</message>" );
    String[] cadena = { "START", "Crear carpeta", "Crear vacio", "Escribir", "Escribir csv", "Copiar", "Comprimir",
      "Descomprimir", "Borrar", "Borrar csv", "Existen", "Vacia" };
    for ( int i = 0; i + 1 < cadena.length; i++ ) {
      j.hop( cadena[i], cadena[i + 1], i == 0 ? "U" : "Y" );
      if ( i > 0 ) {
        j.hop( cadena[i], "Fallo", "N" );
      }
    }
    j.hop( "Vacia", "Fallo", "Y" ).hop( "Vacia", "No vacia", "N" ).hop( "No vacia", "Variables", "Y" )
      .hop( "Variables", "Evaluar", "Y" ).hop( "Evaluar", "Mayor", "Y" ).hop( "Evaluar", "Fallo", "N" )
      .hop( "Mayor", "Exito", "Y" ).hop( "Fallo", "Abortar", "U" );
    comparar( j, true );
  }

  /** Unzip: PDI compara la máscara con la URI completa (zip:file:///...!/nombre), así que "hola.*" no extrae nada. */
  public void pruebaKitchenUnzip() throws Exception {
    comparar( new JobXml( "unzip_mascara" )
      .entrada( "Escribir", "WRITE_TO_FILE", escribirArchivo( "src/hola.txt", "hola", false ) )
      .entrada( "Comprimir", "ZIP_FILE", "<zipfilename>" + D + "/z.zip</zipfilename><compressionrate>1"
        + "</compressionrate><ifzipfileexists>2</ifzipfileexists><wildcard/><wildcardexclude/><sourcedirectory>" + D
        + "/src</sourcedirectory><movetodirectory/><afterzip>0</afterzip><addfiletoresult>N</addfiletoresult>"
        + "<isfromprevious>N</isfromprevious><createparentfolder>N</createparentfolder><include_subfolders>N"
        + "</include_subfolders>" )
      .entrada( "Descomprimir", "UNZIP", "<zipfilename>" + D + "/z.zip</zipfilename><wildcard>hola.*</wildcard>"
        + "<wildcardexclude/><targetdirectory>" + D + "/unz</targetdirectory><afterunzip>0</afterunzip>"
        + "<createfolder>Y</createfolder><iffileexists>SKIP</iffileexists><success_condition>success_if_no_errors"
        + "</success_condition>" )
      .hop( "START", "Escribir", "U" ).hop( "Escribir", "Comprimir", "Y" ).hop( "Comprimir", "Descomprimir", "Y" ) );
  }

  /** Entrada que falla con sólo hop de éxito: el job termina mal; y con hop de fallo a Success: termina bien. */
  public void pruebaKitchenFallo() throws Exception {
    comparar( new JobXml( "fallo_sin_rama" )
      .entrada( "Existe", "FILE_EXISTS", "<filename>" + D + "/no_existe.txt</filename>" )
      .entrada( "Despues", "WRITE_TO_FILE", escribirArchivo( "despues.txt", "x", false ) )
      .hop( "START", "Existe", "U" ).hop( "Existe", "Despues", "Y" ) );
    comparar( new JobXml( "fallo_a_exito" )
      .entrada( "Existe", "FILE_EXISTS", "<filename>" + D + "/no_existe.txt</filename>" )
      .entrada( "Aviso", "WRITE_TO_FILE", escribirArchivo( "aviso.txt", "no estaba", false ) )
      .entrada( "Exito", "SUCCESS", "" )
      .hop( "START", "Existe", "U" ).hop( "Existe", "Aviso", "N" ).hop( "Aviso", "Exito", "Y" ) );
    comparar( new JobXml( "fallo_a_log" )
      .entrada( "Existe", "FILE_EXISTS", "<filename>" + D + "/no_existe.txt</filename>" )
      .entrada( "Log", "WRITE_TO_LOG", "<logmessage>no estaba</logmessage><loglevel>Basic</loglevel><logsubject/>" )
      .hop( "START", "Existe", "U" ).hop( "Existe", "Log", "N" ) );
  }

  /** Un bucle (vuelve a una entrada anterior hasta que el archivo crece) y varios hops desde una entrada. */
  public void pruebaKitchenBucleYRamas() throws Exception {
    comparar( new JobXml( "bucle" )
      .entrada( "Anadir", "WRITE_TO_FILE", escribirArchivo( "cont.txt", "x", true ) )
      .entrada( "Medir", "EVAL_FILES_METRICS", "<result_filenames_wildcard/><Result_field_file/>"
        + "<Result_field_wildcard/><Result_field_includesubfolders/><fields><field><source_filefolder>" + D
        + "/cont.txt</source_filefolder><wildcard/><include_subFolders>N</include_subFolders></field></fields>"
        + "<comparevalue>2</comparevalue><minvalue/><maxvalue/><successnumbercondition>greater"
        + "</successnumbercondition><source_files>files</source_files><evaluation_type>size</evaluation_type>"
        + "<scale>bytes</scale>" )
      .entrada( "Fin", "SUCCESS", "" )
      .hop( "START", "Anadir", "U" ).hop( "Anadir", "Medir", "Y" ).hop( "Medir", "Fin", "Y" )
      .hop( "Medir", "Anadir", "N" ) );
    ProgramaJob p = TraductorJob.traducir( new JobMeta( new File( sal, "kitchen_bucle/bucle.kjb" ).getAbsolutePath(),
      null ), op( Destino.PANDAS, "py" ) );
    contiene( EscritorJob.escribir( p, op( Destino.PANDAS, "py" ) ), "while pendientes:", "máquina de estados" );
    if ( PythonPrueba.tiene( "nbclient" ) ) { // el notebook también corre (celda final: main())
      File dir = new File( sal, "kitchen_bucle/notebook" );
      File nb = new File( dir, "bucle.ipynb" );
      EntornoKettle.escribir( nb, EscritorJob.escribir( p, op( Destino.PANDAS, "ipynb" ) ) );
      PythonPrueba.Resultado r = PythonPrueba.correr( dir, 300, PythonPrueba.python(), "-c", "import nbformat, "
        + "nbclient; nb = nbformat.read('bucle.ipynb', 4); nbclient.NotebookClient(nb, timeout=120, resources={"
        + "'metadata': {'path': '.'}}).execute()" );
      igual( 0, r.codigo, "notebook ejecutado: " + r.salida );
      igual( "xxx", EntornoKettle.leer( new File( dir, "cont.txt" ) ), "el notebook hace lo mismo" );
    }
    comparar( new JobXml( "ramas" )
      .entrada( "A", "WRITE_TO_FILE", escribirArchivo( "orden.txt", "a", true ) )
      .entrada( "B", "WRITE_TO_FILE", escribirArchivo( "orden.txt", "b", true ) )
      .entrada( "C", "WRITE_TO_FILE", escribirArchivo( "orden.txt", "c", true ) )
      .entrada( "D", "WRITE_TO_FILE", escribirArchivo( "orden.txt", "d", true ) )
      .entrada( "E", "FILE_EXISTS", "<filename>" + D + "/no_existe.txt</filename>" )
      .entrada( "F", "WRITE_TO_FILE", escribirArchivo( "orden.txt", "f", true ) )
      .hop( "START", "A", "U" ).hop( "A", "B", "U" ).hop( "A", "C", "Y" ).hop( "A", "E", "Y" )
      .hop( "B", "D", "Y" ).hop( "C", "D", "Y" ).hop( "E", "F", "N" ) );
  }

  /** Entradas de base de datos sobre SQLite: SQL, Table exists, Columns exist, contar filas, Truncate, Check. */
  public void pruebaKitchenBase() throws Exception {
    JobXml j = new JobXml( "base" );
    j.conexion = "<connection><name>prueba</name><server/><type>SQLITE</type><access>Native</access><database>" + D
      + "/prueba.sqlite</database><port>-1</port><username/><password/><servername/><data_tablespace/>"
      + "<index_tablespace/><attributes/></connection>";
    String con = "<connection>prueba</connection>";
    j.entrada( "Crear", "SQL", "<sql>DROP TABLE IF EXISTS t;&#xa;CREATE TABLE t (a INTEGER, b TEXT);&#xa;"
      + "INSERT INTO t VALUES (1, 'x;y');&#xa;INSERT INTO t VALUES (2, 'z');&#xa;INSERT INTO t VALUES (3, 'w');&#xa;"
      + "</sql>"
      + "<useVariableSubstitution>F</useVariableSubstitution><sqlfromfile>F</sqlfromfile><sqlfilename/>"
      + "<sendOneStatement>F</sendOneStatement>" + con )
      .entrada( "Existe", "TABLE_EXISTS", "<tablename>t</tablename><schemaname/>" + con )
      .entrada( "Columnas", "COLUMNS_EXIST", "<tablename>t</tablename><schemaname/>" + con + "<fields><field><name>a"
        + "</name></field><field><name>b</name></field></fields>" )
      .entrada( "Tres", "EVAL_TABLE_CONTENT", con + "<schemaname/><tablename>t</tablename><success_condition>"
        + "rows_count_equal</success_condition><limit>3</limit><is_custom_sql>N</is_custom_sql><is_usevars>N"
        + "</is_usevars><custom_sql/><add_rows_result>N</add_rows_result><clear_result_rows>Y</clear_result_rows>" )
      .entrada( "Vaciar", "TRUNCATE_TABLES", con + "<arg_from_previous>N</arg_from_previous><fields><field><name>t"
        + "</name><schemaname/></field></fields>" )
      .entrada( "Cero", "EVAL_TABLE_CONTENT", con + "<schemaname/><tablename>t</tablename><success_condition>"
        + "rows_count_greater</success_condition><limit>0</limit><is_custom_sql>N</is_custom_sql><is_usevars>N"
        + "</is_usevars><custom_sql/><add_rows_result>N</add_rows_result><clear_result_rows>Y</clear_result_rows>" )
      .entrada( "Comprobar", "CHECK_DB_CONNECTIONS", "<connections><connection><name>prueba</name><waitfor>0"
        + "</waitfor><waittime>millisecond</waittime></connection></connections>" )
      .entrada( "Ok", "WRITE_TO_FILE", escribirArchivo( "ok.txt", "ok", false ) )
      .entrada( "Fallo", "WRITE_TO_FILE", escribirArchivo( "fallo.txt", "fallo", true ) );
    String[] cadena = { "START", "Crear", "Existe", "Columnas", "Tres", "Vaciar" };
    for ( int i = 0; i + 1 < cadena.length; i++ ) {
      j.hop( cadena[i], cadena[i + 1], i == 0 ? "U" : "Y" );
      if ( i > 0 ) {
        j.hop( cadena[i], "Fallo", "N" );
      }
    }
    j.hop( "Vaciar", "Cero", "Y" ).hop( "Cero", "Fallo", "Y" ).hop( "Cero", "Comprobar", "N" )
      .hop( "Comprobar", "Ok", "Y" ).hop( "Comprobar", "Fallo", "N" );
    comparar( j );
  }

  /** Sub-job en una subcarpeta con un parámetro: la carpeta interna y el valor son los del sub-job. */
  public void pruebaKitchenSubjob() throws Exception {
    JobXml hijo = new JobXml( "hijo" )
      .entrada( "Escribir", "WRITE_TO_FILE", escribirArchivo( "salida_${P}.txt", "${P}/${Q}", false ) )
      .hop( "START", "Escribir", "U" );
    String h = hijo.xml().replace( "<parameters></parameters>", "<parameters><parameter><name>P</name>"
      + "<default_value>defecto</default_value><description/></parameter></parameters>" );
    JobXml padre = new JobXml( "padre" )
      .entrada( "Variables", "SET_VARIABLES", "<replacevars>Y</replacevars><filename/><file_variable_type>JVM"
        + "</file_variable_type><fields><field><variable_name>Q</variable_name><variable_value>q1</variable_value>"
        + "<variable_type>ROOT_JOB</variable_type></field></fields>" )
      .entrada( "Hijo", "JOB", "<specification_method>filename</specification_method><filename>" + D
        + "/sub/hijo.kjb</filename><arg_from_previous>N</arg_from_previous><params_from_previous>N"
        + "</params_from_previous><exec_per_row>N</exec_per_row><wait_until_finished>Y</wait_until_finished>"
        + "<parameters><pass_all_parameters>Y</pass_all_parameters><parameter><name>P</name><stream_name/>"
        + "<value>desde_padre</value></parameter></parameters>" )
      .entrada( "Hijo otra vez", "JOB", "<specification_method>filename</specification_method><filename>" + D
        + "/sub/hijo.kjb</filename><arg_from_previous>N</arg_from_previous><params_from_previous>N"
        + "</params_from_previous><exec_per_row>N</exec_per_row><wait_until_finished>Y</wait_until_finished>"
        + "<parameters><pass_all_parameters>Y</pass_all_parameters></parameters>" )
      .hop( "START", "Variables", "U" ).hop( "Variables", "Hijo", "Y" ).hop( "Hijo", "Hijo otra vez", "Y" );
    padre.otros.put( "sub/hijo.kjb", h );
    comparar( padre );
  }

  /** Abort job corta el job entero: el hop que venía después en la misma entrada no se sigue. */
  public void pruebaKitchenAbortar() throws Exception {
    comparar( new JobXml( "abortar" )
      .entrada( "A", "WRITE_TO_FILE", escribirArchivo( "orden.txt", "a", true ) )
      .entrada( "Abortar", "ABORT", "<message>fin</message>" )
      .entrada( "B", "WRITE_TO_FILE", escribirArchivo( "orden.txt", "b", true ) )
      .hop( "START", "A", "U" ).hop( "A", "Abortar", "U" ).hop( "A", "B", "U" ) );
  }

  public void pruebaEstado() throws Exception {
    ProgramaJob p = TraductorJob.traducir( EntornoKettle.job( "10" ), op( Destino.PANDAS, "py" ) );
    cierto( p.estado() != null, "estado" );
    cierto( p.cuantos( Estado.EXACTO ) >= 2, "Success y Abort exactos" );
  }
}
