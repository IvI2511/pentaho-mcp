package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.distinto;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.util.List;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;

/**
 * La infraestructura de pruebas funciona (WP0): Kettle arranca headless y aislado, los ejemplos
 * 01-11 cargan desde la copia, ContextoPrueba calcula campos y dw.sqlite se copia.
 *
 * @dueño WP0
 */
public class PruebaEntorno {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  public void pruebaAislado() throws Exception {
    String base = EntornoKettle.carpeta().getAbsolutePath().toLowerCase();
    cierto( new File( Const.getKettleDirectory() ).getAbsolutePath().toLowerCase().startsWith( base ),
      "KETTLE_HOME bajo la carpeta de prueba: " + Const.getKettleDirectory() );
    cierto( new File( System.getProperty( "user.home" ) ).getAbsolutePath().toLowerCase().startsWith( base ),
      "user.home bajo la carpeta de prueba: " + System.getProperty( "user.home" ) );
    cierto( new File( "" ).getAbsolutePath().toLowerCase().startsWith( base ), "cwd = carpeta de prueba" );
    igual( KettleClientEnvironment.ClientType.SPOON, KettleClientEnvironment.getInstance().getClient(),
      "cliente SPOON (metaverse lo necesita)" );
    File ej = EntornoKettle.ejemplos();
    cierto( ej.getAbsolutePath().toLowerCase().startsWith( base ), "ejemplos copiados bajo la carpeta de prueba" );
  }

  public void pruebaEjemplosCargan() throws Exception {
    for ( String t : new String[] { "01", "02", "03", "04", "05", "06", "07" } ) {
      TransMeta tm = EntornoKettle.trans( t );
      cierto( tm.nrSteps() > 0, t + " tiene steps" );
    }
    for ( String j : new String[] { "10", "11" } ) {
      JobMeta jm = EntornoKettle.job( j );
      cierto( jm.nrJobEntries() > 0, j + " tiene entradas" );
    }
    archivo( new File( EntornoKettle.ejemplos(), "datos/ventas.csv" ), 100, "datos del ejemplo copiados" );
  }

  public void pruebaContextoPrueba() throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( "03", "es" );
    Captura c = ctx.captura();
    igual( Captura.TRANSFORMACION, c.tipo, "tipo" );
    cierto( c.esTransformacion(), "esTransformacion" );
    noNulo( c.archivo, "archivo" );
    List<StepMeta> orden = ctx.orden();
    cierto( orden.size() > 1, "orden topologico de 03: " + orden.size() );
    StepMeta ultimo = orden.get( orden.size() - 1 );
    RowMetaInterface rm = ctx.salida( ultimo );
    cierto( rm.size() > 0, "campos de salida de " + ultimo.getName() );
    cierto( ctx.salida( ultimo ) == rm, "memo de salida" );
    cierto( ctx.msRestantes() > 0, "plazo" );
    igual( "Linaje de campos", ctx.rotulos( "secciones" ).s( "lineage" ), "rotulos del idioma del pedido" );

    ContextoPrueba job = ContextoPrueba.de( "10", "en" );
    cierto( job.captura().esJob(), "10 es un job" );
    cierto( job.orden().isEmpty(), "orden vacio en un job" );
  }

  public void pruebaCopiaDw() throws Exception {
    File a = EntornoKettle.copiaDw();
    File b = EntornoKettle.copiaDw();
    archivo( a, 1024, "copia de dw.sqlite" );
    distinto( a.getAbsolutePath(), b.getAbsolutePath(), "cada copia es un archivo nuevo" );
    igual( a.length(), new File( EntornoKettle.recursos(), "db/dw.sqlite" ).length(), "mismo tamano" );
  }
}
