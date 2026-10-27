package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.traductor.CtxTrad;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.TradFormatosAcceso;

/**
 * Fechas con el mes por su nombre (MMMM, MMM): PDI las lee en el idioma de Spoon («Enero 10, 2008» en español) y
 * pandas / Spark sólo en inglés; el traductor cambia antes los nombres (español, portugués o inglés) por su número.
 * Sin esto, todas las filas de un ejercicio en español iban al hop de error.
 */
public class PruebaTradMeses {

  private static final String DATOS = "[\"Enero 10, 2008\", \"diciembre 5, 2009\", \"Mar. 3, 2010\", "
    + "\"janeiro 1, 2011\", \"May 7, 2012\", \"Septiembre 30, 2013\", None]";
  private static final String ESPERADO = "2008-01-10|2009-12-05|2010-03-03|2011-01-01|2012-05-07|2013-09-30|NaT";

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  public void pruebaPandas() throws Exception {
    if ( !PythonPrueba.tiene( "pandas" ) ) {
      saltear( "sin pandas" );
      return;
    }
    CtxTrad ctx = new CtxTrad( new TransMeta(), Destino.PANDAS, "es" );
    String e = TradFormatosAcceso.aFecha( "s", "MMMM d, yyyy", ctx );
    contiene( e, "_meses_pdi(s)", "cambia los nombres de mes" );
    contiene( e, "%m %d, %Y", "y lee el mes como número" );
    StringBuilder py = new StringBuilder( "import pandas as pd\n\n" );
    for ( Map.Entry<String, String> a : ctx.ayudantes.entrySet() ) {
      py.append( a.getValue() ).append( "\n\n" );
    }
    py.append( "s = pd.Series(" ).append( DATOS ).append( ", dtype=\"object\")\n" );
    py.append( "r = " ).append( e ).append( "\n" );
    py.append( "print(\"R=\" + \"|\".join(\"NaT\" if pd.isna(x) else x.strftime(\"%Y-%m-%d\") for x in r))\n" );
    PythonPrueba.Resultado r = correr( "meses_pandas.py", py.toString() );
    igual( 0, r.codigo, "corre: " + r.salida );
    contiene( r.salida, "R=" + ESPERADO, "las fechas, como en PDI" );
  }

  public void pruebaPyspark() throws Exception {
    if ( !PythonPrueba.tiene( "pyspark" ) ) {
      saltear( "sin pyspark" );
      return;
    }
    CtxTrad ctx = new CtxTrad( new TransMeta(), Destino.PYSPARK, "es" );
    String e = TradFormatosAcceso.aFecha( "F.col(\"f\")", "MMMM d, yyyy", ctx );
    contiene( e, "\"M d, yyyy\"", "lee el mes como número en Spark" );
    StringBuilder py = new StringBuilder( "import os, sys\nfrom pyspark.sql import SparkSession, functions as F\n" );
    py.append( "os.environ.setdefault(\"PYSPARK_PYTHON\", sys.executable)\n" );
    py.append( "b = SparkSession.builder.master(\"local[1]\").config(\"spark.ui.enabled\", \"false\")\n" );
    py.append( "if os.name == \"nt\":\n    b = b.config(\"spark.driver.host\", \"localhost\")"
      + ".config(\"spark.driver.bindAddress\", \"127.0.0.1\")\n" );
    py.append( "spark = b.getOrCreate()\n\n" );
    for ( Map.Entry<String, String> a : ctx.ayudantes.entrySet() ) {
      py.append( a.getValue() ).append( "\n\n" );
    }
    py.append( "df = spark.createDataFrame([(x,) for x in " ).append( DATOS ).append( "], \"f string\")\n" );
    py.append( "spark.conf.set(\"spark.sql.ansi.enabled\", \"false\")\n" );
    py.append( "r = [x[0] for x in df.select(" ).append( e ).append( ".alias(\"d\")).collect()]\n" );
    py.append( "print(\"R=\" + \"|\".join(\"NaT\" if x is None else x.strftime(\"%Y-%m-%d\") for x in r))\n" );
    py.append( "spark.stop()\n" );
    PythonPrueba.Resultado r = correr( "meses_spark.py", py.toString() );
    cierto( r.salida.contains( "R=" + ESPERADO ), "las fechas en Spark, como en PDI: " + r.salida.substring( Math
      .max( 0, r.salida.length() - 1500 ) ) );
  }

  private static PythonPrueba.Resultado correr( String nombre, String py ) throws Exception {
    File f = new File( EntornoKettle.salida( "trad-meses" ), nombre );
    Files.write( f.toPath(), py.getBytes( StandardCharsets.UTF_8 ) );
    return PythonPrueba.correr( f.getParentFile(), 300, PythonPrueba.python(), f.getAbsolutePath() );
  }
}
