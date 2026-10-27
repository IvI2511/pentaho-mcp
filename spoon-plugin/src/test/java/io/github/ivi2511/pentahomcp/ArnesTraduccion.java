package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.StepInterface;

import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;

/**
 * Arnés de equivalencia del traductor: corre una transformación en Kettle (en la JVM) capturando las filas de un step,
 * traduce la misma transformación a Python, le agrega el volcado de los DataFrames y compara. Las filas se normalizan
 * igual en los dos lados: enteros tal cual, números con 6 decimales, fechas «yyyy-MM-dd HH:mm:ss», booleanos
 * true/false, nulo y "" como &lt;null&gt;.
 */
public final class ArnesTraduccion {

  private ArnesTraduccion() {
  }

  /** Una transformación mínima con los steps (XML de cada &lt;step&gt;), los hops y las conexiones dadas. */
  public static String ktr( String nombre, String conexiones, String hops, String... steps ) {
    StringBuilder b = new StringBuilder( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<transformation>\n<info><name>" )
      .append( nombre ).append( "</name><trans_type>Normal</trans_type><directory>/</directory><parameters>"
        + "</parameters><size_rowset>10000</size_rowset></info>\n" );
    b.append( conexiones == null ? "" : conexiones ).append( "\n<order>" ).append( hops == null ? "" : hops )
      .append( "</order>\n" );
    int x = 50;
    for ( String s : steps ) {
      b.append( s.replace( "</step>", "<GUI><xloc>" + x + "</xloc><yloc>50</yloc><draw>Y</draw></GUI></step>" ) )
        .append( '\n' );
      x += 100;
    }
    return b.append( "</transformation>\n" ).toString();
  }

  /** Un hop. */
  public static String hop( String de, String a ) {
    return "<hop><from>" + de + "</from><to>" + a + "</to><enabled>Y</enabled></hop>";
  }

  /** Un step con su tipo y el XML interior. */
  public static String step( String nombre, String tipo, String interior ) {
    return "<step><name>" + nombre + "</name><type>" + tipo + "</type><distribute>Y</distribute><copies>1</copies>"
      + interior + "</step>";
  }

  /** Conexión SQLite con la ruta dada (puede tener variables). */
  public static String sqlite( String nombre, String ruta ) {
    return "<connection><name>" + nombre + "</name><server/><type>SQLITE</type><access>Native</access><database>"
      + ruta + "</database><port>-1</port><username/><password/></connection>";
  }

  public static File escribir( File dir, String nombre, String texto ) throws Exception {
    File f = new File( dir, nombre );
    Files.write( f.toPath(), texto.getBytes( StandardCharsets.UTF_8 ) );
    return f;
  }

  /** Corre la transformación en Kettle y devuelve las filas que escribe {@code paso} (cabecera + filas). */
  public static List<String> kettle( File ktr, String paso ) throws Exception {
    EntornoKettle.iniciar();
    TransMeta tm = new TransMeta( ktr.getAbsolutePath() );
    Trans t = new Trans( tm );
    t.setLogLevel( LogLevel.MINIMAL );
    t.prepareExecution( null );
    final List<String> filas = new ArrayList<>();
    final RowMetaInterface[] meta = new RowMetaInterface[ 1 ];
    if ( paso != null ) {
      StepInterface d = t.findRunThread( paso );
      Verificador.noNulo( d, "step " + paso + " en " + ktr.getName() );
      d.addRowListener( new RowAdapter() {
        @Override
        public void rowWrittenEvent( RowMetaInterface rm, Object[] row ) throws KettleStepException {
          meta[ 0 ] = rm;
          try {
            filas.add( fila( rm, row ) );
          } catch ( Exception e ) {
            throw new KettleStepException( e );
          }
        }
      } );
    }
    t.startThreads();
    t.waitUntilFinished();
    Verificador.igual( 0, t.getErrors(), ktr.getName() + " corre en Kettle sin errores" );
    List<String> r = new ArrayList<>();
    if ( meta[ 0 ] != null ) {
      r.add( String.join( "\t", meta[ 0 ].getFieldNames() ) );
    } else if ( paso != null ) {
      r.add( String.join( "\t", tm.getStepFields( paso ).getFieldNames() ) );
    }
    r.addAll( filas );
    return r;
  }

  private static String fila( RowMetaInterface rm, Object[] row ) throws Exception {
    List<String> v = new ArrayList<>();
    for ( int i = 0; i < rm.size(); i++ ) {
      ValueMetaInterface vm = rm.getValueMeta( i );
      Object o = i < row.length ? row[ i ] : null;
      if ( o != null && vm.isStorageBinaryString() ) {
        o = vm.convertBinaryStringToNativeType( (byte[]) o );
      }
      v.add( valor( o ) );
    }
    return String.join( "\t", v );
  }

  static String valor( Object o ) {
    if ( o == null ) {
      return "<null>";
    }
    if ( o instanceof Double || o instanceof Float || o instanceof BigDecimal ) {
      return String.format( Locale.ROOT, "%.6f", ( (Number) o ).doubleValue() );
    }
    if ( o instanceof Date ) {
      return new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" ).format( (Date) o );
    }
    if ( o instanceof byte[] ) {
      return new String( (byte[]) o, StandardCharsets.UTF_8 );
    }
    String s = o.toString();
    return s.isEmpty() ? "<null>" : s;
  }

  /** Corre la transformación en Kettle sin capturar filas (para las salidas). */
  public static void kettle( File ktr ) throws Exception {
    kettle( ktr, null );
  }

  // ================================================================ Python

  /** Volcado de un DataFrame (pandas o Spark) con la misma normalización que {@link #valor}. */
  static final String VOLCAR = String.join( "\n",
    "",
    "",
    "def _volcar(df, ruta):",
    "    import datetime, decimal",
    "    import pandas as _pd",
    "    if hasattr(df, \"collect\") and hasattr(df, \"schema\"):",
    "        cols = list(df.columns)",
    "        filas = [list(r) for r in df.collect()]",
    "    else:",
    "        cols = [str(c) for c in df.columns]",
    "        filas = df.astype(object).values.tolist()",
    "    def v(x):",
    "        if x is None:",
    "            return \"<null>\"",
    "        try:",
    "            if _pd.isna(x):",
    "                return \"<null>\"",
    "        except (TypeError, ValueError):",
    "            pass",
    "        if isinstance(x, bool) or type(x).__name__ == \"bool_\":",
    "            return \"true\" if x else \"false\"",
    "        if isinstance(x, datetime.datetime):",
    "            return x.strftime(\"%Y-%m-%d %H:%M:%S\")",
    "        if isinstance(x, datetime.date):",
    "            return x.strftime(\"%Y-%m-%d\") + \" 00:00:00\"",
    "        if isinstance(x, (float, decimal.Decimal)) or \"float\" in type(x).__name__:",
    "            return \"%.6f\" % float(x)",
    "        if isinstance(x, (bytes, bytearray)):",
    "            return bytes(x).decode(\"utf-8\", \"replace\")",
    "        s = str(x)",
    "        return s if s != \"\" else \"<null>\"",
    "    with open(ruta, \"w\", encoding=\"utf-8\", newline=\"\\n\") as f:",
    "        f.write(\"\\t\".join(cols) + \"\\n\")",
    "        for r in filas:",
    "            f.write(\"\\t\".join(v(x) for x in r) + \"\\n\")",
    "" );

  /**
   * Traduce el .ktr y escribe {@code <ktr>_<destino>.py} al lado, con el volcado de los DataFrames de los steps
   * dados en {@code <ktr>_<destino>.<step>.tsv}. Devuelve el programa (para mirar estados y notas).
   */
  public static Programa traducir( File ktr, Destino d, String... pasos ) throws Exception {
    EntornoKettle.iniciar();
    TransMeta tm = new TransMeta( ktr.getAbsolutePath() );
    Programa p = TraductorTrans.traducir( tm, d, "es" );
    StringBuilder py = new StringBuilder( EscritorPy.escribir( p ) ).append( VOLCAR );
    for ( String paso : pasos ) {
      py.append( "_volcar(" ).append( p.ctx.df( tm.findStep( paso ) ) ).append( ", os.path.join(AQUI, \"" ).append(
        volcado( ktr, d, paso ).getName() ).append( "\"))\n" );
    }
    Files.write( script( ktr, d ).toPath(), py.toString().getBytes( StandardCharsets.UTF_8 ) );
    return p;
  }

  public static File script( File ktr, Destino d ) {
    return new File( ktr.getParentFile(), ktr.getName().replace( ".ktr", "_" + d.id() + ".py" ) );
  }

  public static File volcado( File ktr, Destino d, String paso ) {
    return new File( ktr.getParentFile(), ktr.getName().replace( ".ktr", "_" + d.id() + "." + paso.replaceAll(
      "[^A-Za-z0-9]+", "_" ) + ".tsv" ) );
  }

  /** El volcado del step (cabecera + filas), o null si el script no llegó a escribirlo. */
  public static List<String> leer( File f ) throws Exception {
    if ( !f.isFile() ) {
      return null;
    }
    List<String> r = new ArrayList<>();
    for ( String l : new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ).split( "\n" ) ) {
      if ( !l.isEmpty() ) {
        r.add( l );
      }
    }
    return r;
  }

  /**
   * Corre varios scripts en UN proceso de Python (Spark arranca una sola vez): cada uno con runpy, cwd en su carpeta.
   * Devuelve la salida (con «=== script», «OK» o el traceback y «FALLO» por script).
   */
  public static String correr( List<File> scripts, int segundos ) throws Exception {
    String py = PythonPrueba.python();
    if ( py == null || scripts.isEmpty() ) {
      return "";
    }
    File dir = scripts.get( 0 ).getParentFile();
    File corredor = new File( dir, "_corredor.py" );
    Files.write( corredor.toPath(), String.join( "\n",
      "import os, runpy, sys, traceback",
      "for f in sys.argv[1:]:",
      "    print(\"=== \" + os.path.basename(f), flush=True)",
      "    os.chdir(os.path.dirname(f))",
      "    try:",
      "        runpy.run_path(f, run_name=\"__main__\")",
      "        print(\"OK\", flush=True)",
      "    except BaseException:",
      "        traceback.print_exc()",
      "        print(\"FALLO\", flush=True)",
      "" ).getBytes( StandardCharsets.UTF_8 ) );
    List<String> cmd = new ArrayList<>( Arrays.asList( py, corredor.getAbsolutePath() ) );
    for ( File s : scripts ) {
      cmd.add( s.getAbsolutePath() );
    }
    ProcessBuilder pb = new ProcessBuilder( cmd ).redirectErrorStream( true ).directory( dir );
    pb.environment().put( "PYTHONIOENCODING", "utf-8" );
    pb.environment().put( "PYSPARK_PYTHON", py );
    pb.environment().put( "PYSPARK_DRIVER_PYTHON", py );
    File jdk = new File( "C:\\Program Files\\Java\\jdk-21" );
    if ( jdk.isDirectory() ) {
      pb.environment().put( "JAVA_HOME", jdk.getAbsolutePath() );
    }
    File log = new File( dir, "_corredor.log" );
    pb.redirectOutput( log );
    Process p = pb.start();
    if ( !p.waitFor( segundos, TimeUnit.SECONDS ) ) {
      p.destroyForcibly();
      throw new IllegalStateException( "Python no terminó en " + segundos + " s" );
    }
    return new String( Files.readAllBytes( log.toPath() ), StandardCharsets.UTF_8 );
  }

  /** La parte de la salida de {@link #correr} de un script. */
  public static String salidaDe( String salida, File script ) {
    int i = salida.indexOf( "=== " + script.getName() );
    if ( i < 0 ) {
      return "";
    }
    int j = salida.indexOf( "\n=== ", i + 4 );
    return j < 0 ? salida.substring( i ) : salida.substring( i, j );
  }

  /** Compara filas: mismas columnas y mismos valores (números con tolerancia relativa 1e-6). */
  public static void comparar( List<String> esperado, List<String> real, String msg ) {
    Verificador.noNulo( real, msg + ": el script volcó el DataFrame" );
    Verificador.igual( esperado.size(), real.size(), msg + ": cantidad de filas (+cabecera)\nKettle " + esperado
      + "\nPython " + real );
    for ( int i = 0; i < esperado.size(); i++ ) {
      String[] a = esperado.get( i ).split( "\t", -1 );
      String[] b = real.get( i ).split( "\t", -1 );
      boolean ok = a.length == b.length;
      for ( int k = 0; ok && k < a.length; k++ ) {
        ok = mismo( a[ k ], b[ k ] );
      }
      Verificador.cierto( ok, msg + ": fila " + i + "\nKettle " + esperado.get( i ) + "\nPython " + real.get( i ) );
    }
  }

  static boolean mismo( String a, String b ) {
    if ( a.equals( b ) ) {
      return true;
    }
    try {
      double x = Double.parseDouble( a );
      double y = Double.parseDouble( b );
      return Math.abs( x - y ) <= 1e-6 * Math.max( 1, Math.abs( x ) );
    } catch ( NumberFormatException e ) {
      return false;
    }
  }
}
