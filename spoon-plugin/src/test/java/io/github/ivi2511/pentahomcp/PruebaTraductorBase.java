package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.traductor.CtxTrad;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorIpynb;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;

/**
 * El núcleo del traductor a Python (0.8.0): orden de los steps, nombres, variables, bloques MANUAL de lo que no tiene
 * traducción y los dos escritores. Cada ejemplo de la solución de referencia se traduce a pandas y a PySpark sin
 * excepciones y el .py compila con el Python del sistema (si lo hay).
 */
public class PruebaTraductorBase {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  public void pruebaNombres() {
    igual( "ventas_2026", CtxTrad.ident( "Ventas 2026" ), "ident sin espacios" );
    igual( "anadir_maximo", CtxTrad.ident( "Añadir máximo" ), "ident sin acentos" );
    igual( "_01_dim", CtxTrad.ident( "01 dim" ), "ident no empieza con dígito" );
    igual( "\"a\\\"b\"", CtxTrad.lit( "a\"b" ), "literal con comillas" );
  }

  public void pruebaEjemplos() throws Exception {
    File ej = new File( EntornoKettle.recursos(), "../../../../ejemplo/solucion-referencia" ).getCanonicalFile();
    File sal = EntornoKettle.salida( "traductor-base" );
    File[] ktrs = ej.listFiles( ( d, n ) -> n.endsWith( ".ktr" ) );
    cierto( ktrs != null && ktrs.length >= 5, "hay ejemplos" );
    for ( File k : ktrs ) {
      TransMeta t = new TransMeta( k.getAbsolutePath() );
      for ( Destino d : Destino.values() ) {
        Programa p = TraductorTrans.traducir( t, d, "es" );
        igual( t.nrSteps(), p.bloques.size(), k.getName() + ": un bloque por step" );
        String py = EscritorPy.escribir( p );
        contiene( py, d == Destino.PANDAS ? "import pandas as pd" : "SparkSession", k.getName() + ": imports" );
        File f = new File( sal, k.getName().replace( ".ktr", "_" + d.id() + ".py" ) );
        Files.write( f.toPath(), py.getBytes( StandardCharsets.UTF_8 ) );
        String nb = EscritorIpynb.escribir( p );
        contiene( nb, "\"nbformat\" : 4", k.getName() + ": notebook" );
        Files.write( new File( sal, k.getName().replace( ".ktr", "_" + d.id() + ".ipynb" ) ).toPath(), nb.getBytes(
          StandardCharsets.UTF_8 ) );
        PythonPrueba.compila( f );
      }
    }
  }
}
