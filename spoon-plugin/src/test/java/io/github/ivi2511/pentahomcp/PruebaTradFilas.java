package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.steps.denormaliser.DenormaliserTargetField;
import org.pentaho.di.trans.steps.groupby.GroupByMeta;
import org.pentaho.di.trans.steps.memgroupby.MemoryGroupByMeta;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.CtxTrad;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;

/**
 * Traductores de filas y flujos (TradFilas): cada caso es una transformación mínima con Data grid de entrada; se
 * corre en Kettle (dentro de la JVM, capturando las filas del step) y su traducción en pandas y en PySpark sobre los
 * mismos datos, y se comparan columnas y valores (números con tolerancia, nulo = "").
 */
public class PruebaTradFilas {

  private static final String STR = "String";
  private static final String INT = "Integer";
  private static final String NUM = "Number";

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  // ================================================================ armado de transformaciones

  /** Una transformación de prueba escrita como XML. */
  static final class Caso {
    final String nombre;
    final StringBuilder pasos = new StringBuilder();
    final StringBuilder hops = new StringBuilder();
    final StringBuilder errores = new StringBuilder();
    final StringBuilder conexiones = new StringBuilder();
    /** grid → [campos, tipos, filas] */
    final Map<String, Object[]> grids = new LinkedHashMap<>();
    final List<String> objetivos = new ArrayList<>();
    boolean sinOrden;
    boolean sinSpark;
    int x = 50;
    TransMeta tm;
    final Map<String, List<List<String>>> pdi = new LinkedHashMap<>();
    final Map<String, List<String>> cabeceras = new LinkedHashMap<>();

    Caso( String nombre ) {
      this.nombre = nombre;
    }

    Caso grid( String n, String campos, String... filas ) {
      String[] cs = campos.split( "," );
      List<String> nombres = new ArrayList<>();
      List<String> tipos = new ArrayList<>();
      StringBuilder f = new StringBuilder( "<fields>" );
      for ( String c : cs ) {
        String[] p = c.split( ":" );
        nombres.add( p[0] );
        tipos.add( p[1] );
        f.append( "<field><name>" ).append( p[0] ).append( "</name><type>" ).append( p[1] ).append( "</type><format/>"
          + "<currency/><decimal>.</decimal><group/><length>-1</length><precision>-1</precision><set_empty_string>N"
          + "</set_empty_string></field>" );
      }
      f.append( "</fields><data>" );
      List<String[]> rs = new ArrayList<>();
      for ( String r : filas ) {
        String[] v = r.split( "\\|", -1 );
        rs.add( v );
        f.append( "<line>" );
        for ( String x2 : v ) {
          f.append( "<item>" ).append( esc( x2 ) ).append( "</item>" );
        }
        f.append( "</line>" );
      }
      f.append( "</data>" );
      grids.put( n, new Object[] { nombres, tipos, rs } );
      return paso( n, "DataGrid", f.toString() );
    }

    Caso paso( String n, String tipo, String cuerpo ) {
      pasos.append( "<step><name>" ).append( n ).append( "</name><type>" ).append( tipo ).append( "</type>"
        + "<distribute>Y</distribute><copies>1</copies>" ).append( cuerpo ).append( "<GUI><xloc>" ).append( x ).append(
          "</xloc><yloc>100</yloc></GUI></step>" );
      x += 100;
      return this;
    }

    Caso hop( String a, String b ) {
      hops.append( "<hop><from>" ).append( a ).append( "</from><to>" ).append( b ).append( "</to><enabled>Y</enabled>"
        + "</hop>" );
      return this;
    }

    Caso error( String a, String b ) {
      hop( a, b );
      errores.append( "<error><source_step>" ).append( a ).append( "</source_step><target_step>" ).append( b ).append(
        "</target_step><is_enabled>Y</is_enabled><nr_valuename/><descriptions_valuename/><fields_valuename/>"
          + "<codes_valuename/><max_errors/><max_pct_errors/><min_pct_rows/></error>" );
      return this;
    }

    Caso ver( String... pasosObjetivo ) {
      objetivos.addAll( Arrays.asList( pasosObjetivo ) );
      return this;
    }

    String xml() {
      return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><transformation><info><name>" + nombre + "</name></info>"
        + conexiones + "<order>" + hops + "</order>" + pasos + "<step_error_handling>" + errores
        + "</step_error_handling></transformation>";
    }
  }

  static String esc( String s ) {
    return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
  }

  static String grupo( String... campos ) {
    StringBuilder b = new StringBuilder( "<group>" );
    for ( String c : campos ) {
      b.append( "<field><name>" ).append( c ).append( "</name></field>" );
    }
    return b.append( "</group>" ).toString();
  }

  /** Campo de agregación de Group by / Memory group by / Analytic query. */
  static String agg( String nombre, String sujeto, String tipo, String valor ) {
    return "<field><aggregate>" + nombre + "</aggregate><subject>" + sujeto + "</subject><type>" + tipo
      + "</type><valuefield>" + ( valor == null ? "" : valor ) + "</valuefield></field>";
  }

  static String gb( int t ) {
    return GroupByMeta.typeGroupCode[t];
  }

  // ================================================================ ejecución en Kettle

  static void correrKettle( Caso c ) throws Exception {
    c.tm = new TransMeta( new ByteArrayInputStream( c.xml().getBytes( StandardCharsets.UTF_8 ) ), null, true, null,
      null );
    Trans t = new Trans( c.tm );
    t.prepareExecution( null );
    for ( String o : c.objetivos ) {
      final List<List<String>> filas = new ArrayList<>();
      final List<String> cab = new ArrayList<>();
      c.pdi.put( o, filas );
      c.cabeceras.put( o, cab );
      StepInterface si = t.getStepInterface( o, 0 );
      cierto( si != null, c.nombre + ": existe el step " + o );
      si.addRowListener( new RowAdapter() {
        @Override
        public void rowWrittenEvent( RowMetaInterface rm, Object[] r ) {
          synchronized ( filas ) {
            if ( cab.isEmpty() ) {
              cab.addAll( Arrays.asList( rm.getFieldNames() ) );
            }
            filas.add( texto( rm, r ) );
          }
        }
      } );
    }
    t.startThreads();
    t.waitUntilFinished();
    igual( 0, t.getErrors(), c.nombre + ": Kettle sin errores" );
    // la cabecera de un objetivo sin filas: los campos que calcula Kettle
    for ( String o : c.objetivos ) {
      if ( c.cabeceras.get( o ).isEmpty() ) {
        c.cabeceras.get( o ).addAll( Arrays.asList( c.tm.getStepFields( o ).getFieldNames() ) );
      }
    }
  }

  static List<String> texto( RowMetaInterface rm, Object[] r ) {
    List<String> l = new ArrayList<>();
    for ( int i = 0; i < rm.size(); i++ ) {
      ValueMetaInterface v = rm.getValueMeta( i );
      try {
        if ( v.isNull( r[i] ) ) {
          l.add( "" );
        } else if ( v.isNumeric() ) {
          l.add( Double.toString( v.getNumber( r[i] ) ) );
        } else if ( v.isBoolean() ) {
          l.add( v.getBoolean( r[i] ) ? "true" : "false" );
        } else {
          l.add( v.getString( r[i] ) );
        }
      } catch ( Exception e ) {
        l.add( "?" );
      }
    }
    return l;
  }

  // ================================================================ ejecución en Python

  /** Código de pandas o PySpark que construye la entrada de un Data grid. */
  @SuppressWarnings( "unchecked" )
  static List<String> entradaPy( Object[] g, String df, Destino d ) {
    List<String> nombres = (List<String>) g[0];
    List<String> tipos = (List<String>) g[1];
    List<String[]> filas = (List<String[]>) g[2];
    List<String> r = new ArrayList<>();
    if ( d == Destino.PANDAS ) {
      StringBuilder b = new StringBuilder( df + " = pd.DataFrame({" );
      for ( int i = 0; i < nombres.size(); i++ ) {
        StringBuilder v = new StringBuilder();
        boolean todosNulos = true;
        for ( String[] f : filas ) {
          String x = f[i];
          todosNulos &= x.isEmpty();
          v.append( v.length() == 0 ? "" : ", " ).append( lit( x, tipos.get( i ) ) );
        }
        String t = tipos.get( i );
        b.append( CtxTrad.lit( nombres.get( i ) ) ).append( ": " ).append( INT.equals( t ) ? "pd.array([" + v
          + "], dtype=\"Int64\")" : NUM.equals( t ) ? "pd.Series([" + v + "], dtype=\"float64\")" : "pd.Series(["
            + v + "]" + ( todosNulos ? ", dtype=object" : "" ) + ")" ).append( ", " );
      }
      r.add( b.append( "})" ).toString() );
    } else {
      StringBuilder esq = new StringBuilder();
      for ( int i = 0; i < nombres.size(); i++ ) {
        String t = tipos.get( i );
        esq.append( i == 0 ? "" : ", " ).append( nombres.get( i ) ).append( INT.equals( t ) ? " long" : NUM.equals( t )
          ? " double" : " string" );
      }
      StringBuilder fs = new StringBuilder();
      for ( String[] f : filas ) {
        StringBuilder v = new StringBuilder( "(" );
        for ( int i = 0; i < nombres.size(); i++ ) {
          v.append( lit( f[i], tipos.get( i ) ) ).append( ", " );
        }
        fs.append( fs.length() == 0 ? "" : ", " ).append( v ).append( ")" );
      }
      r.add( df + " = spark.createDataFrame([" + fs + "], " + CtxTrad.lit( esq.toString() ) + ")" );
    }
    return r;
  }

  static String lit( String x, String tipo ) {
    if ( x.isEmpty() ) {
      return "None";
    }
    return INT.equals( tipo ) || NUM.equals( tipo ) ? ( NUM.equals( tipo ) ? Double.toString( Double.parseDouble( x ) )
      : x ) : CtxTrad.lit( x );
  }

  /** Traduce el caso, cambia los Data grid por los datos y agrega el volcado de cada objetivo a CSV. */
  static File script( Caso c, Destino d, File dir ) throws Exception {
    Programa p = TraductorTrans.traducir( c.tm, d, "es" );
    for ( int i = 0; i < p.bloques.size(); i++ ) {
      Bloque b = p.bloques.get( i );
      if ( "DataGrid".equals( b.tipo ) ) {
        Bloque n = new Bloque( b.paso, b.tipo );
        n.codigo.addAll( entradaPy( c.grids.get( b.paso ), p.ctx.df( c.tm.findStep( b.paso ) ), d ) );
        p.bloques.set( i, n );
      }
    }
    Bloque vuelca = new Bloque( "volcado", "Prueba" );
    for ( String o : c.objetivos ) {
      String v = p.ctx.df( c.tm.findStep( o ) );
      File csv = new File( dir, c.nombre + "_" + o + "_" + d.id() + ".csv" );
      csv.delete();
      vuelca.l( ( d == Destino.PANDAS ? v : v + ".toPandas()" ) + ".to_csv(" + CtxTrad.lit( csv.getAbsolutePath()
        .replace( '\\', '/' ) ) + ", index=False)" );
    }
    p.bloques.add( vuelca );
    String py = EscritorPy.escribir( p );
    File f = new File( dir, c.nombre + "_" + d.id() + ".py" );
    Files.write( f.toPath(), py.getBytes( StandardCharsets.UTF_8 ) );
    return f;
  }

  /** Corre todos los scripts en un solo proceso de Python; devuelve la salida. */
  static String correrPython( List<File> scripts, File dir, String nombre, boolean spark ) throws Exception {
    StringBuilder m = new StringBuilder( "import os, runpy, sys, traceback\n" );
    if ( spark ) {
      m.append( "os.environ[\"PYSPARK_PYTHON\"] = sys.executable\n" );
      m.append( "os.environ.setdefault(\"JAVA_HOME\", " ).append( CtxTrad.lit( System.getProperty( "java.home" )
        .replace( '\\', '/' ) ) ).append( ")\n" );
    }
    m.append( "for f in [" );
    for ( File f : scripts ) {
      m.append( CtxTrad.lit( f.getAbsolutePath().replace( '\\', '/' ) ) ).append( ", " );
    }
    m.append( "]:\n    try:\n        runpy.run_path(f, run_name=\"__main__\")\n        print(\"OK\", f)\n"
      + "    except Exception:\n        print(\"FALLA\", f)\n        traceback.print_exc()\n" );
    File mf = new File( dir, nombre );
    Files.write( mf.toPath(), m.toString().getBytes( StandardCharsets.UTF_8 ) );
    PythonPrueba.Resultado r = PythonPrueba.correr( dir, 900, PythonPrueba.python(), mf.getAbsolutePath() );
    return r.salida;
  }

  // ================================================================ comparación

  static List<List<String>> leerCsv( File f ) throws Exception {
    List<List<String>> r = new ArrayList<>();
    String s = new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
    List<String> fila = new ArrayList<>();
    StringBuilder v = new StringBuilder();
    boolean comillas = false;
    for ( int i = 0; i < s.length(); i++ ) {
      char ch = s.charAt( i );
      if ( comillas ) {
        if ( ch == '"' && i + 1 < s.length() && s.charAt( i + 1 ) == '"' ) {
          v.append( '"' );
          i++;
        } else if ( ch == '"' ) {
          comillas = false;
        } else {
          v.append( ch );
        }
      } else if ( ch == '"' ) {
        comillas = true;
      } else if ( ch == ',' ) {
        fila.add( v.toString() );
        v.setLength( 0 );
      } else if ( ch == '\n' ) {
        fila.add( v.toString().replace( "\r", "" ) );
        v.setLength( 0 );
        r.add( fila );
        fila = new ArrayList<>();
      } else {
        v.append( ch );
      }
    }
    if ( v.length() > 0 || !fila.isEmpty() ) {
      fila.add( v.toString() );
      r.add( fila );
    }
    return r;
  }

  static String norm( String x ) {
    if ( x == null ) {
      return "";
    }
    String t = x.trim();
    if ( t.equalsIgnoreCase( "nan" ) || t.equalsIgnoreCase( "<NA>" ) || t.equalsIgnoreCase( "None" ) || t
      .equalsIgnoreCase( "NaT" ) ) {
      return "";
    }
    if ( t.equalsIgnoreCase( "true" ) || t.equalsIgnoreCase( "false" ) ) {
      return t.toLowerCase();
    }
    try {
      double d = Double.parseDouble( t );
      return String.format( java.util.Locale.ROOT, "%.6f", d );
    } catch ( NumberFormatException e ) {
      return t;
    }
  }

  static List<String> normFila( List<String> f ) {
    List<String> r = new ArrayList<>();
    for ( String x : f ) {
      r.add( norm( x ) );
    }
    return r;
  }

  /** Compara lo de Kettle con el CSV de Python: cabecera y filas (en orden, o como multiconjunto). */
  static String comparar( Caso c, String o, File csv, boolean ordenar ) throws Exception {
    if ( !csv.isFile() ) {
      return "no hay CSV " + csv.getName();
    }
    List<List<String>> py = leerCsv( csv );
    List<String> cab = py.isEmpty() ? new ArrayList<>() : py.remove( 0 );
    if ( cab.size() == 1 && cab.get( 0 ).isEmpty() ) {
      cab.clear();
    }
    if ( !cab.equals( c.cabeceras.get( o ) ) ) {
      return "cabecera " + cab + " != PDI " + c.cabeceras.get( o );
    }
    List<String> a = new ArrayList<>();
    List<String> b = new ArrayList<>();
    for ( List<String> f : c.pdi.get( o ) ) {
      a.add( String.join( "|", normFila( f ) ) );
    }
    for ( List<String> f : py ) {
      b.add( String.join( "|", normFila( f ) ) );
    }
    if ( ordenar ) {
      Collections.sort( a );
      Collections.sort( b );
    }
    return a.equals( b ) ? null : "filas\n  PDI    " + a + "\n  Python " + b;
  }

  // ================================================================ casos

  static final String GRID_GB = "g:String,x:Integer,y:Number,s:String";
  static final String[] FILAS_GB = { "a|1|1.5|uno", "a|2||dos", "b|3|2.5|", "a|4|4.0|cuatro", "b||3.5|cinco",
    "b|6|1.0|seis", "|7|2.0|siete", "|8|8.0|ocho" };

  static List<Caso> casos() {
    List<Caso> l = new ArrayList<>();
    // Sort rows: mayúsculas, nulos, descendente, únicas
    l.add( new Caso( "ordenar" ).grid( "in", "g:String,x:Integer", "b|1", "A|2", "a|3", "|4", "B|5", "a|6" ).paso(
      "orden", "SortRows", "<directory>%%java.io.tmpdir%%</directory><prefix>out</prefix><sort_size>1000</sort_size>"
        + "<free_memory/><compress>N</compress><compress_variable/><unique_rows>N</unique_rows><fields>"
        + "<field><name>g</name><ascending>Y</ascending><case_sensitive>N</case_sensitive><collator_enabled>N"
        + "</collator_enabled><collator_strength>0</collator_strength><presorted>N</presorted></field>"
        + "<field><name>x</name><ascending>N</ascending><case_sensitive>Y</case_sensitive><collator_enabled>N"
        + "</collator_enabled><collator_strength>0</collator_strength><presorted>N</presorted></field></fields>" )
      .hop( "in", "orden" ).ver( "orden" ) );
    l.add( new Caso( "ordenar_unicas" ).grid( "in", "g:String,x:Integer", "b|1", "a|2", "a|3", "|4", "b|5", "|6" )
      .paso( "orden", "SortRows", "<directory>%%java.io.tmpdir%%</directory><prefix>out</prefix><sort_size>1000"
        + "</sort_size><unique_rows>Y</unique_rows><fields><field><name>g</name><ascending>Y</ascending>"
        + "<case_sensitive>Y</case_sensitive><presorted>N</presorted></field></fields>" ).hop( "in", "orden" ).ver(
          "orden" ) );
    // Unique rows: rachas, contador, sin mayúsculas, duplicados al hop de error
    l.add( new Caso( "unicas" ).grid( "in", "g:String,x:Integer", "a|1", "A|2", "b|3", "b|4", "a|5", "|6", "|7" )
      .paso( "uni", "Unique", "<count_rows>Y</count_rows><count_field>n</count_field><reject_duplicate_row>Y"
        + "</reject_duplicate_row><error_description>dup</error_description><fields><field><name>g</name>"
        + "<case_insensitive>Y</case_insensitive></field></fields>" ).paso( "dups", "Dummy", "" ).hop( "in", "uni" )
      .error( "uni", "dups" ).ver( "uni", "dups" ) );
    l.add( new Caso( "unicas_hash" ).grid( "in", "g:String,x:Integer", "a|1", "b|2", "a|3", "|4", "|5", "c|6" ).paso(
      "uni", "UniqueRowsByHashSet", "<store_values>N</store_values><reject_duplicate_row>N</reject_duplicate_row>"
        + "<error_description/><fields><field><name>g</name></field></fields>" ).hop( "in", "uni" ).ver( "uni" ) );
    // Group by: todas las agregaciones, entrada SIN ordenar (rachas)
    StringBuilder aggs = new StringBuilder();
    aggs.append( agg( "suma_x", "x", gb( GroupByMeta.TYPE_GROUP_SUM ), null ) );
    aggs.append( agg( "media_x", "x", gb( GroupByMeta.TYPE_GROUP_AVERAGE ), null ) );
    aggs.append( agg( "media_y", "y", gb( GroupByMeta.TYPE_GROUP_AVERAGE ), null ) );
    aggs.append( agg( "mediana_y", "y", gb( GroupByMeta.TYPE_GROUP_MEDIAN ), null ) );
    aggs.append( agg( "p25_y", "y", gb( GroupByMeta.TYPE_GROUP_PERCENTILE ), "25" ) );
    aggs.append( agg( "rango_y", "y", gb( GroupByMeta.TYPE_GROUP_PERCENTILE_NEAREST_RANK ), "50" ) );
    aggs.append( agg( "min_s", "s", gb( GroupByMeta.TYPE_GROUP_MIN ), null ) );
    aggs.append( agg( "max_y", "y", gb( GroupByMeta.TYPE_GROUP_MAX ), null ) );
    aggs.append( agg( "n", "s", gb( GroupByMeta.TYPE_GROUP_COUNT_ALL ), null ) );
    aggs.append( agg( "n_y", "y", gb( GroupByMeta.TYPE_GROUP_COUNT_ANY ), null ) );
    aggs.append( agg( "nd_y", "y", gb( GroupByMeta.TYPE_GROUP_COUNT_DISTINCT ), null ) );
    aggs.append( agg( "lista", "s", gb( GroupByMeta.TYPE_GROUP_CONCAT_COMMA ), null ) );
    aggs.append( agg( "cadena", "s", gb( GroupByMeta.TYPE_GROUP_CONCAT_STRING ), "|" ) );
    aggs.append( agg( "primero", "y", gb( GroupByMeta.TYPE_GROUP_FIRST ), null ) );
    aggs.append( agg( "ultimo", "s", gb( GroupByMeta.TYPE_GROUP_LAST ), null ) );
    aggs.append( agg( "primero_n", "y", gb( GroupByMeta.TYPE_GROUP_FIRST_INCL_NULL ), null ) );
    aggs.append( agg( "ultimo_n", "s", gb( GroupByMeta.TYPE_GROUP_LAST_INCL_NULL ), null ) );
    aggs.append( agg( "acum", "y", gb( GroupByMeta.TYPE_GROUP_CUMULATIVE_SUM ), null ) );
    aggs.append( agg( "acum_m", "y", gb( GroupByMeta.TYPE_GROUP_CUMULATIVE_AVERAGE ), null ) );
    aggs.append( agg( "desv", "y", gb( GroupByMeta.TYPE_GROUP_STANDARD_DEVIATION ), null ) );
    aggs.append( agg( "desv_m", "y", gb( GroupByMeta.TYPE_GROUP_STANDARD_DEVIATION_SAMPLE ), null ) );
    String gbBase = "<ignore_aggregate>N</ignore_aggregate><field_ignore/><directory>%%java.io.tmpdir%%</directory>"
      + "<prefix>grp</prefix>";
    l.add( new Caso( "agrupar" ).grid( "in", GRID_GB, FILAS_GB ).paso( "gb", "GroupBy", gbBase + "<all_rows>N"
      + "</all_rows><add_linenr>N</add_linenr><linenr_fieldname/><give_back_row>N</give_back_row>" + grupo( "g" )
      + "<fields>" + aggs + "</fields>" ).hop( "in", "gb" ).ver( "gb" ) );
    l.add( new Caso( "agrupar_todas" ).grid( "in", GRID_GB, FILAS_GB ).paso( "gb", "GroupBy", gbBase + "<all_rows>Y"
      + "</all_rows><add_linenr>Y</add_linenr><linenr_fieldname>linea</linenr_fieldname><give_back_row>N"
      + "</give_back_row>" + grupo( "g" ) + "<fields>" + aggs + "</fields>" ).hop( "in", "gb" ).ver( "gb" ) );
    l.add( new Caso( "agrupar_sin_claves" ).grid( "in", GRID_GB, FILAS_GB ).paso( "gb", "GroupBy", gbBase
      + "<all_rows>N</all_rows><add_linenr>N</add_linenr><give_back_row>N</give_back_row><group/><fields>" + aggs
      + "</fields>" ).hop( "in", "gb" ).ver( "gb" ) );
    l.add( new Caso( "agrupar_vacio" ).grid( "in", GRID_GB ).paso( "gb", "GroupBy", gbBase + "<all_rows>N</all_rows>"
      + "<give_back_row>Y</give_back_row><group/><fields>" + agg( "suma_x", "x", gb( GroupByMeta.TYPE_GROUP_SUM ),
        null ) + agg( "n", "x", gb( GroupByMeta.TYPE_GROUP_COUNT_ALL ), null ) + "</fields>" ).hop( "in", "gb" ).ver(
          "gb" ) );
    // Group by detrás de un Sort rows: groupby normal
    l.add( new Caso( "agrupar_ordenada" ).grid( "in", GRID_GB, FILAS_GB ).paso( "orden", "SortRows", "<directory>"
      + "%%java.io.tmpdir%%</directory><prefix>out</prefix><sort_size>1000</sort_size><unique_rows>N</unique_rows>"
      + "<fields><field><name>g</name><ascending>Y</ascending><case_sensitive>Y</case_sensitive><presorted>N"
      + "</presorted></field></fields>" ).paso( "gb", "GroupBy", gbBase + "<all_rows>N</all_rows><give_back_row>N"
        + "</give_back_row>" + grupo( "g" ) + "<fields>" + agg( "suma_x", "x", gb( GroupByMeta.TYPE_GROUP_SUM ), null )
        + agg( "lista", "s", gb( GroupByMeta.TYPE_GROUP_CONCAT_COMMA ), null ) + agg( "mediana", "x", gb(
          GroupByMeta.TYPE_GROUP_MEDIAN ), null ) + "</fields>" ).hop( "in", "orden" ).hop( "orden", "gb" ).ver(
            "gb" ) );
    // Memory group by
    Caso mg = new Caso( "agrupar_memoria" ).grid( "in", GRID_GB, FILAS_GB ).paso( "gb", "MemoryGroupBy",
      "<give_back_row>N</give_back_row>" + grupo( "g" ) + "<fields>" + agg( "suma_x", "x",
        MemoryGroupByMeta.typeGroupCode[MemoryGroupByMeta.TYPE_GROUP_SUM], null ) + agg( "media_y", "y",
          MemoryGroupByMeta.typeGroupCode[MemoryGroupByMeta.TYPE_GROUP_AVERAGE], null ) + agg( "n", "s",
            MemoryGroupByMeta.typeGroupCode[MemoryGroupByMeta.TYPE_GROUP_COUNT_ALL], null ) + agg( "nd", "y",
              MemoryGroupByMeta.typeGroupCode[MemoryGroupByMeta.TYPE_GROUP_COUNT_DISTINCT], null ) + agg( "maxs",
                "s", MemoryGroupByMeta.typeGroupCode[MemoryGroupByMeta.TYPE_GROUP_MAX], null ) + agg( "med", "y",
                  MemoryGroupByMeta.typeGroupCode[MemoryGroupByMeta.TYPE_GROUP_MEDIAN], null ) + "</fields>" ).hop(
                    "in", "gb" ).ver( "gb" );
    mg.sinOrden = true;
    l.add( mg );
    // Analytic query
    l.add( new Caso( "analitica" ).grid( "in", GRID_GB, FILAS_GB ).paso( "aq", "AnalyticQuery", grupo( "g" )
      + "<fields>" + agg( "x_ant", "x", "LAG", "1" ) + agg( "s_sig", "s", "LEAD", "2" ) + "</fields>" ).hop( "in",
        "aq" ).ver( "aq" ) );
    // Row denormaliser / normaliser / flattener
    StringBuilder tf = new StringBuilder();
    String[][] ts = { { "v", "ene", "t_ene", INT, DenormaliserTargetField.typeAggrDesc[0] },
      { "v", "feb", "t_feb", NUM, DenormaliserTargetField.typeAggrDesc[DenormaliserTargetField.TYPE_AGGR_SUM] },
      { "v", "mar", "t_mar", STR, DenormaliserTargetField.typeAggrDesc[DenormaliserTargetField.TYPE_AGGR_CONCAT_COMMA] },
      { "v", "ene", "n_ene", INT, DenormaliserTargetField.typeAggrDesc[DenormaliserTargetField.TYPE_AGGR_COUNT_ALL] } };
    for ( String[] t : ts ) {
      tf.append( "<field><field_name>" ).append( t[0] ).append( "</field_name><key_value>" ).append( t[1] ).append(
        "</key_value><target_name>" ).append( t[2] ).append( "</target_name><target_type>" ).append( t[3] ).append(
          "</target_type><target_format/><target_length>-1</target_length><target_precision>-1</target_precision>"
            + "<target_decimal_symbol/><target_grouping_symbol/><target_currency_symbol/><target_null_string/>"
            + "<target_aggregation_type>" ).append( t[4] ).append( "</target_aggregation_type></field>" );
    }
    l.add( new Caso( "desnormalizar" ).grid( "in", "id:Integer,k:String,v:String", "1|ene|10", "1|feb|2.5",
      "1|ene|12", "1|mar|x", "1|feb|1.5", "2|mar|y", "2|mar|z", "1|ene|3" ).paso( "dn", "Denormaliser",
        "<key_field>k</key_field>" + grupo( "id" ) + "<fields>" + tf + "</fields>" ).hop( "in", "dn" ).ver( "dn" ) );
    l.add( new Caso( "normalizar" ).grid( "in", "id:Integer,c1:Integer,c2:Integer,d1:String,otro:String",
      "1|10|20|a|o1", "2||40|b|o2" ).paso( "nm", "Normaliser", "<typefield>tipo</typefield><fields>"
        + "<field><name>c1</name><value>uno</value><norm>v</norm></field>"
        + "<field><name>c2</name><value>dos</value><norm>v</norm></field>"
        + "<field><name>d1</name><value>uno</value><norm>d</norm></field></fields>" ).hop( "in", "nm" ).ver( "nm" ) );
    l.add( new Caso( "aplanar" ).grid( "in", "id:Integer,v:String", "1|a", "2|b", "3|c", "4|d", "5|e" ).paso( "fl",
      "Flattener", "<field_name>v</field_name><fields><field><name>v1</name></field><field><name>v2</name></field>"
        + "</fields>" ).hop( "in", "fl" ).ver( "fl" ) );
    // Stream lookup: claves repetidas, no encontradas (default), clave nula
    l.add( new Caso( "buscar_flujo" ).grid( "in", "id:Integer,k:String", "1|a", "2|b", "3|z", "4|" ).grid( "lk",
      "lk_k:String,val:String,num:Integer", "a|primero|1", "b|bebe|2", "a|segundo|3", "|nulo|4" ).paso( "sl",
        "StreamLookup", "<from>lk</from><input_sorted>N</input_sorted><preserve_memory>Y</preserve_memory>"
          + "<sorted_list>N</sorted_list><integer_pair>N</integer_pair><lookup><key><name>k</name><field>lk_k</field>"
          + "</key><value><name>val</name><rename>valor</rename><default>ninguno</default><type>String</type></value>"
          + "<value><name>num</name><rename/><default>-1</default><type>Integer</type></value></lookup>" ).hop( "in",
            "sl" ).hop( "lk", "sl" ).ver( "sl" ) );
    // Merge join: los cuatro tipos
    for ( String tipo : new String[] { "INNER", "LEFT OUTER", "RIGHT OUTER", "FULL OUTER" } ) {
      Caso mj = new Caso( "merge_join_" + tipo.split( " " )[0].toLowerCase() ).grid( "a", "k:Integer,va:String",
        "1|a1", "2|a2", "2|a2b", "4|a4" ).grid( "b", "k:Integer,vb:String", "2|b2", "3|b3", "4|b4", "4|b4b" ).paso(
          "mj", "MergeJoin", "<join_type>" + tipo + "</join_type><step1>a</step1><step2>b</step2><keys_1><key>k</key>"
            + "</keys_1><keys_2><key>k</key></keys_2>" ).hop( "a", "mj" ).hop( "b", "mj" ).ver( "mj" );
      mj.sinOrden = true;
      l.add( mj );
    }
    // Merge rows (diff)
    l.add( new Caso( "merge_rows" ).grid( "ref", "k:Integer,v:String", "1|a", "2|b", "3|c", "5|" ).grid( "cmp",
      "k:Integer,v:String", "1|a", "2|B", "4|d", "5|" ).paso( "mr", "MergeRows", "<keys><key>k</key></keys><values>"
        + "<value>v</value></values><flag_field>flag</flag_field><reference>ref</reference><compare>cmp</compare>" )
      .hop( "ref", "mr" ).hop( "cmp", "mr" ).ver( "mr" ) );
    // Append, Sorted merge, Sample rows, último, bloqueo, vacío
    l.add( new Caso( "anexar" ).grid( "cola", "k:Integer,v:String", "9|z" ).grid( "cab", "k:Integer,v:String", "1|a",
      "2|b" ).paso( "ap", "Append", "<head_name>cab</head_name><tail_name>cola</tail_name>" ).hop( "cola", "ap" ).hop(
        "cab", "ap" ).ver( "ap" ) );
    Caso sm = new Caso( "mezcla_ordenada" ).grid( "a", "k:Integer,v:String", "1|a", "3|c", "5|e" ).grid( "b",
      "k:Integer,v:String", "2|b", "3|c2", "6|f" ).paso( "sm", "SortedMerge", "<fields><field><name>k</name>"
        + "<ascending>Y</ascending></field></fields>" ).hop( "a", "sm" ).hop( "b", "sm" ).ver( "sm" );
    sm.sinOrden = true;
    l.add( sm );
    l.add( new Caso( "muestra" ).grid( "in", "k:Integer", "10", "20", "30", "40", "50", "60" ).paso( "sr",
      "SampleRows", "<linesrange>1..2,5</linesrange><linenumfield>linea</linenumfield>" ).hop( "in", "sr" ).ver(
        "sr" ) );
    l.add( new Caso( "ultima" ).grid( "in", "k:Integer", "10", "20", "30" ).paso( "ul", "DetectLastRow",
      "<resultfieldname>es_ultima</resultfieldname>" ).hop( "in", "ul" ).ver( "ul" ) );
    l.add( new Caso( "bloqueo" ).grid( "in", "k:Integer", "10", "20", "30" ).paso( "bl", "BlockingStep",
      "<pass_all_rows>N</pass_all_rows><directory>%%java.io.tmpdir%%</directory><prefix>block</prefix><cache_size>"
        + "5000</cache_size><compress>N</compress>" ).hop( "in", "bl" ).ver( "bl" ) );
    l.add( new Caso( "vacio" ).grid( "in", "k:Integer,s:String" ).paso( "de", "DetectEmptyStream", "" ).hop( "in",
      "de" ).ver( "de" ) );
    l.add( new Caso( "abort_no" ).grid( "in", "k:Integer", "10", "20" ).paso( "ab", "Abort", "<row_threshold>5"
      + "</row_threshold><message>demasiadas</message><always_log_rows>N</always_log_rows><abort_option>ABORT"
      + "</abort_option>" ).hop( "in", "ab" ).ver( "ab" ) );
    return l;
  }

  /** Casos con base de datos SQLite (Database lookup / Database join); sólo pandas. */
  static List<Caso> casosBase( File db ) {
    String con = "<connection><name>bd</name><server/><type>SQLITE</type><access>Native</access><database>"
      + esc( db.getAbsolutePath().replace( '\\', '/' ) ) + "</database><port>-1</port><username/><password/>"
      + "</connection>";
    List<Caso> l = new ArrayList<>();
    Caso a = new Caso( "buscar_base" ).grid( "in", "id:Integer,k:Integer", "1|1", "2|2", "3|9", "4|" ).paso( "dl",
      "DBLookup", "<connection>bd</connection><cache>N</cache><cache_load_all>N</cache_load_all><cache_size>0"
        + "</cache_size><lookup><schema/><table>persona</table><orderby>nombre DESC</orderby><fail_on_multiple>N"
        + "</fail_on_multiple><eat_row_on_failure>N</eat_row_on_failure><key><name>k</name><field>grupo</field>"
        + "<condition>=</condition><name2/></key><value><name>nombre</name><rename>nom</rename><default>nadie"
        + "</default><type>String</type></value><value><name>edad</name><rename/><default/><type>Integer</type>"
        + "</value></lookup>" ).hop( "in", "dl" ).ver( "dl" );
    a.conexiones.append( con );
    a.sinSpark = true;
    l.add( a );
    Caso b = new Caso( "buscar_base_menor" ).grid( "in", "id:Integer,e:Integer", "1|20", "2|35", "3|99" ).paso( "dl",
      "DBLookup", "<connection>bd</connection><cache>N</cache><lookup><schema/><table>persona</table><orderby>edad"
        + "</orderby><fail_on_multiple>N</fail_on_multiple><eat_row_on_failure>Y</eat_row_on_failure><key><name>e"
        + "</name><field>edad</field><condition>&lt;</condition><name2/></key><value><name>nombre</name><rename/>"
        + "<default/><type>String</type></value></lookup>" ).hop( "in", "dl" ).ver( "dl" );
    b.conexiones.append( con );
    b.sinSpark = true;
    l.add( b );
    Caso c = new Caso( "unir_base" ).grid( "in", "id:Integer,k:Integer", "1|1", "2|9", "3|2" ).paso( "dj", "DBJoin",
      "<connection>bd</connection><rowlimit>0</rowlimit><sql>SELECT nombre, edad FROM persona WHERE grupo = ? ORDER "
        + "BY edad</sql><outer_join>Y</outer_join><replace_vars>N</replace_vars><parameter><field><name>k</name>"
        + "<type>Integer</type></field></parameter>" ).hop( "in", "dj" ).ver( "dj" );
    c.conexiones.append( con );
    c.sinSpark = true;
    l.add( c );
    return l;
  }

  // ================================================================ pruebas

  public void pruebaEquivalencia() throws Exception {
    File dir = EntornoKettle.salida( "trad-filas" );
    File db = new File( dir, "filas.sqlite" );
    db.delete();
    try ( Connection cn = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
      Statement st = cn.createStatement() ) {
      st.execute( "CREATE TABLE persona (grupo INTEGER, nombre TEXT, edad INTEGER)" );
      st.execute( "INSERT INTO persona VALUES (1, 'Ana', 30), (1, 'Berta', 25), (2, 'Carlos', 40), (3, 'Dora', 50)" );
    }
    List<Caso> todos = casos();
    todos.addAll( casosBase( db ) );
    for ( Caso c : todos ) {
      correrKettle( c );
    }
    if ( PythonPrueba.python() == null ) {
      String env = System.getenv( "PENTAHO_LIVE_PYTHON" );
      String porque;
      try {
        porque = PythonPrueba.correr( null, 60, env == null ? "python" : env, "--version" ).salida;
      } catch ( Exception e ) {
        porque = e.toString();
      }
      Verificador.saltear( "sin Python: " + env + " " + porque );
    }
    StringBuilder fallas = new StringBuilder();
    for ( Destino d : Destino.values() ) {
      if ( d == Destino.PYSPARK && ( !PythonPrueba.tiene( "pyspark" ) || System.getenv( "PRUEBA_SIN_SPARK" )
        != null ) ) {
        continue;
      }
      List<File> scripts = new ArrayList<>();
      List<Caso> usados = new ArrayList<>();
      for ( Caso c : todos ) {
        if ( d == Destino.PYSPARK && c.sinSpark ) {
          continue;
        }
        scripts.add( script( c, d, dir ) );
        usados.add( c );
      }
      String salida = correrPython( scripts, dir, "todo_" + d.id() + ".py", d == Destino.PYSPARK );
      for ( Caso c : usados ) {
        for ( String o : c.objetivos ) {
          File csv = new File( dir, c.nombre + "_" + o + "_" + d.id() + ".csv" );
          String r = comparar( c, o, csv, c.sinOrden || d == Destino.PYSPARK );
          if ( r != null ) {
            fallas.append( "\n[" ).append( d.id() ).append( "] " ).append( c.nombre ).append( "/" ).append( o )
              .append( ": " ).append( r );
          }
        }
      }
      if ( salida.contains( "FALLA" ) ) {
        fallas.append( "\n[" ).append( d.id() ).append( "] salida de Python:\n" ).append( salida.length() > 6000
          ? salida.substring( salida.length() - 6000 ) : salida );
      }
    }
    Files.write( new File( dir, "fallas.txt" ).toPath(), fallas.toString().getBytes( StandardCharsets.UTF_8 ) );
    cierto( fallas.length() == 0, "equivalencia con Kettle:" + fallas );
  }

  /** Lo que no se puede comparar fila a fila: estados, notas y que compile. */
  public void pruebaTraducciones() throws Exception {
    File dir = EntornoKettle.salida( "trad-filas" );
    Caso c = new Caso( "varios" ).grid( "in", "k:Integer,v:String", "1|a" ).paso( "res", "RowsToResult", "" ).paso(
      "sub", "Mapping", "<specification_method>filename</specification_method><filename>${Internal.Entry.Current"
        + ".Directory}/sub.ktr</filename>" ).paso( "rs", "ReservoirSampling", "<sample_size>2</sample_size><seed>7"
          + "</seed>" ).paso( "dl", "Delay", "<timeout>2</timeout><scaletime>seconds</scaletime>" ).paso( "es",
            "BlockUntilStepsFinish", "<steps><step><name>in</name><CopyNr>0</CopyNr></step></steps>" ).hop( "in",
              "res" ).hop( "res", "sub" ).hop( "sub", "rs" ).hop( "rs", "dl" ).hop( "dl", "es" );
    c.tm = new TransMeta( new ByteArrayInputStream( c.xml().getBytes( StandardCharsets.UTF_8 ) ), null, true, null,
      null );
    Caso c2 = new Caso( "resultado" ).paso( "rr", "RowsFromResult", "<fields><field><name>k</name><type>Integer"
      + "</type><length>-1</length><precision>-1</precision></field></fields>" ).paso( "d", "Dummy", "" ).hop( "rr",
        "d" );
    c2.tm = new TransMeta( new ByteArrayInputStream( c2.xml().getBytes( StandardCharsets.UTF_8 ) ), null, true, null,
      null );
    for ( Destino d : Destino.values() ) {
      Programa p = TraductorTrans.traducir( c.tm, d, "es" );
      String py = EscritorPy.escribir( p );
      contiene( py, "RESULT[\"rows\"] = ", d.id() + ": RowsToResult" );
      contiene( py, "RESULT = globals().setdefault(\"RESULT\", {})", d.id() + ": RESULT" );
      contiene( py, "trans_sub(", d.id() + ": Mapping sugiere la función" );
      for ( Bloque b : p.bloques ) {
        if ( "Mapping".equals( b.tipo ) ) {
          igual( Estado.MANUAL, b.estado, "Mapping manual" );
        }
        if ( "ReservoirSampling".equals( b.tipo ) ) {
          igual( Estado.APROXIMADO, b.estado, "Reservoir aproximado" );
        }
        if ( "Delay".equals( b.tipo ) || "BlockUntilStepsFinish".equals( b.tipo ) || "RowsToResult".equals( b.tipo ) ) {
          igual( Estado.EXACTO, b.estado, b.tipo + " exacto" );
        }
      }
      File f = new File( dir, "varios_" + d.id() + ".py" );
      Files.write( f.toPath(), py.getBytes( StandardCharsets.UTF_8 ) );
      PythonPrueba.compila( f );
      String py2 = EscritorPy.escribir( TraductorTrans.traducir( c2.tm, d, "en" ) );
      contiene( py2, "RESULT.get(\"rows\"", d.id() + ": RowsFromResult" );
      File f2 = new File( dir, "resultado_" + d.id() + ".py" );
      Files.write( f2.toPath(), py2.getBytes( StandardCharsets.UTF_8 ) );
      PythonPrueba.compila( f2 );
    }
  }
}
