package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorIpynb;
import io.github.ivi2511.pentahomcp.traductor.EscritorJob;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Opciones;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TraductorJob;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;

/**
 * El ejemplo del curso de punta a punta (0.8.0): el job de Grano de Oro traducido a pandas corre sobre una copia de
 * ejemplo/ y deja el mismo almacén que Kitchen: comprobar_resultados.py (las 29 comprobaciones con las que se validó
 * la solución de referencia) cuadra. Deja además los notebooks y scripts de ejemplo en la salida de la prueba
 * (ejemplo-python/), que son los de ejemplo/python/.
 */
public class PruebaTraductorGranoDeOro {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  public void pruebaJobCompletoCuadra() throws Exception {
    File ej = new File( EntornoKettle.recursos(), "../../../../ejemplo" ).getCanonicalFile();
    File copia = EntornoKettle.salida( "grano-de-oro-python" );
    copiar( new File( ej, "solucion-referencia" ).toPath(), new File( copia, "solucion-referencia" ).toPath() );
    copiar( new File( ej, "datos" ).toPath(), new File( copia, "datos" ).toPath() );
    Files.copy( new File( ej, "comprobar_resultados.py" ).toPath(), new File( copia, "comprobar_resultados.py" )
      .toPath(), StandardCopyOption.REPLACE_EXISTING );
    File sr = new File( copia, "solucion-referencia" );
    File muestra = EntornoKettle.salida( "ejemplo-python" );

    // el job en pandas (.py) y en notebook, y en PySpark (notebook)
    JobMeta jm = new JobMeta( new File( sr, "job_carga_dw.kjb" ).getAbsolutePath(), null );
    Opciones o = new Opciones();
    o.formato = "py";
    o.titulo = "Carga del almacén de Grano de Oro";
    String py = EscritorJob.escribir( TraductorJob.traducir( jm, o ), o );
    File job = new File( sr, "job_carga_dw.py" );
    escribir( job, py );
    escribir( new File( muestra, "job_carga_dw.py" ), py );
    Opciones nb = new Opciones();
    nb.titulo = o.titulo;
    escribir( new File( muestra, "job_carga_dw.ipynb" ), EscritorJob.escribir( TraductorJob.traducir( jm, nb ), nb ) );
    Opciones sp = new Opciones();
    sp.destino = Destino.PYSPARK;
    sp.titulo = o.titulo;
    escribir( new File( muestra, "job_carga_dw_pyspark.ipynb" ), EscritorJob.escribir( TraductorJob.traducir( jm,
      sp ), sp ) );

    // cada transformación como notebook con explicaciones y EDA
    File[] ktrs = sr.listFiles( ( d, n ) -> n.endsWith( ".ktr" ) );
    StringBuilder estados = new StringBuilder();
    for ( File k : ktrs ) {
      TransMeta t = new TransMeta( k.getAbsolutePath() );
      Opciones e = new Opciones();
      e.eda = true;
      Programa p = TraductorTrans.traducir( t, Destino.PANDAS, "es" );
      String base = k.getName().replace( ".ktr", "" );
      escribir( new File( muestra, base + ".ipynb" ), EscritorIpynb.escribir( p, e ) );
      Opciones ep = new Opciones();
      ep.formato = "py";
      escribir( new File( muestra, base + ".py" ), EscritorPy.escribir( p, ep ) );
      if ( base.startsWith( "06" ) ) { // el KPI desde ficheros también en PySpark (lee y escribe archivos)
        Opciones es = new Opciones();
        es.eda = true;
        es.destino = Destino.PYSPARK;
        escribir( new File( muestra, base + "_pyspark.ipynb" ), EscritorIpynb.escribir( TraductorTrans.traducir( t,
          Destino.PYSPARK, "es" ), es ) );
      }
      estados.append( base ).append( ": " ).append( p.cuantos( Estado.EXACTO ) ).append( " exactos, " ).append(
        p.cuantos( Estado.APROXIMADO ) ).append( " aproximados, " ).append( p.cuantos( Estado.MANUAL ) ).append(
          " manuales\n" );
    }
    escribir( new File( muestra, "estados.txt" ), estados.toString() );
    System.out.println( estados );

    if ( !PythonPrueba.tiene( "pandas" ) || !PythonPrueba.tiene( "sqlalchemy" ) ) {
      saltear( "sin Python con pandas y sqlalchemy" );
      return;
    }
    PythonPrueba.Resultado r = PythonPrueba.correr( sr, 600, PythonPrueba.python(), job.getAbsolutePath() );
    System.out.println( r.salida.length() > 4000 ? r.salida.substring( r.salida.length() - 4000 ) : r.salida );
    cierto( r.codigo == 0, "el job traducido termina bien: " + cola( r.salida ) );
    PythonPrueba.Resultado c = PythonPrueba.correr( copia, 300, PythonPrueba.python(), new File( copia,
      "comprobar_resultados.py" ).getAbsolutePath() );
    System.out.println( c.salida );
    cierto( c.codigo == 0, "comprobar_resultados.py cuadra con el almacén que deja Python: " + cola( c.salida ) );
  }

  private static String cola( String s ) {
    return s.length() > 1500 ? s.substring( s.length() - 1500 ) : s;
  }

  private static void escribir( File f, String s ) throws IOException {
    Files.write( f.toPath(), s.getBytes( StandardCharsets.UTF_8 ) );
  }

  private static void copiar( Path de, Path a ) throws IOException {
    try ( Stream<Path> s = Files.walk( de ) ) {
      for ( Path p : (Iterable<Path>) s::iterator ) {
        Path d = a.resolve( de.relativize( p ).toString() );
        if ( Files.isDirectory( p ) ) {
          Files.createDirectories( d );
        } else {
          Files.copy( p, d, StandardCopyOption.REPLACE_EXISTING );
        }
      }
    }
  }
}
