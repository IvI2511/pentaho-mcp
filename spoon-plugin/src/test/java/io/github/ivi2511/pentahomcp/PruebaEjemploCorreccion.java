package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;

/**
 * El pack de ejemplo de Grano de Oro ({@code ejemplo/pack-correccion}, C10): valida, su salida esperada es la de
 * {@code ejemplo/datos/resultado_esperado.json}, la copia para alumnos no trae la solucion ni el caso oculto, y las
 * cuatro entregas de {@code ejemplo/entregas-ejemplo} sacan las notas que dice su README (con JVMs de verdad), igual
 * que la solucion de referencia (10).
 *
 * @dueño C10
 */
public class PruebaEjemploCorreccion {

  private File ejemplo;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    ejemplo = new File( EntornoKettle.recursos(), "../../../../ejemplo" ).getCanonicalFile();
    cierto( new File( ejemplo, "pack-correccion/pack.json" ).isFile(), "el pack de ejemplo en " + ejemplo );
  }

  /** El pack valida y su salida esperada es la tabla de resultado_esperado.json (visible); la copia, sin secretos. */
  public void pruebaPack() throws Exception {
    File pack = new File( ejemplo, "pack-correccion" );
    try ( CorrectorPack cp = CorrectorPack.abrir( pack ) ) {
      cierto( cp.valido(), "el pack valida: " + cp.textoProblemas( "es" ) );
      Pack p = cp.pack();
      igual( 2, p.casos.size(), "visible y oculto" );
      cierto( p.casos.get( 1 ).oculto, "el segundo es oculto" );
      igual( Arrays.asList( "tienda", "categoria" ), p.comparar.columnasClave, "clave" );
      igual( 10.0, p.puntosTotales(), "la rubrica suma 10" );
      igual( "es_ES", p.politica.locale, "locale del pack" );
      CorrectorTabla esp = CorrectorTabla.esperada( cp.esperado( p.casos.get( 0 ) ), p );
      JsonNode kpi = CorrectorJson.mapper().readTree( new File( ejemplo, "datos/resultado_esperado.json" ) ).path(
        "esperado" ).path( "kpi_ventas_tienda_categoria" );
      igual( kpi.size(), esp.filas.size(), "20 filas como en resultado_esperado.json" );
      Map<String, String> m = new LinkedHashMap<String, String>();
      for ( String[] f : esp.filas ) {
        m.put( f[ esp.columnas.indexOf( "tienda" ) ] + "|" + f[ esp.columnas.indexOf( "categoria" ) ], f[ esp.columnas
          .indexOf( "unidades" ) ] + ";" + f[ esp.columnas.indexOf( "importe" ) ] );
      }
      for ( JsonNode k : kpi ) {
        igual( k.path( "unidades" ).asText() + ";" + k.path( "importe" ).asText(), m.get( k.path( "tienda" ).asText()
          + "|" + k.path( "categoria" ).asText() ), "fila " + k );
      }
      // los datos del caso visible son los de la practica
      for ( String n : Arrays.asList( "ventas_2026_09.csv", "productos.xlsx", "tiendas.json" ) ) {
        cierto( Arrays.equals( Files.readAllBytes( new File( ejemplo, "datos/" + n ).toPath() ), Files.readAllBytes(
          new File( pack, "datos/visible/" + n ).toPath() ) ), n + " igual que en ejemplo/datos" );
      }
      // la solucion del pack es la de solucion-referencia
      cierto( Arrays.equals( Files.readAllBytes( new File( ejemplo, "solucion-referencia/06_kpi_desde_ficheros.ktr" )
        .toPath() ), Files.readAllBytes( cp.solucion().toPath() ) ), "la solucion del pack = solucion-referencia/06" );
    }
    File alumnos = new File( ejemplo, "pack-correccion-alumnos.zip" );
    try ( ZipFile z = new ZipFile( alumnos ) ) {
      for ( java.util.Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
        String n = en.nextElement().getName();
        falso( n.startsWith( "solucion" ) || n.contains( "oculto" ), "la copia para alumnos no tiene " + n );
      }
    }
    try ( CorrectorPack cp = CorrectorPack.abrir( alumnos ) ) {
      cierto( cp.valido(), "la copia para alumnos valida" );
    }
  }

  /** Las entregas de ejemplo sacan las notas del README; la solucion de referencia, 10. */
  public void pruebaEntregas() throws Exception {
    memoria();
    File base = new File( EntornoKettle.carpeta(), "ejemplo-correccion" );
    CorrectorSandbox.borrar( base );
    File carpeta = new File( base, "entregas" );
    carpeta.mkdirs();
    for ( File f : new File( ejemplo, "entregas-ejemplo" ).listFiles( ( d, n ) -> n.endsWith( ".ktr" ) ) ) {
      Files.copy( f.toPath(), new File( carpeta, f.getName() ).toPath(), StandardCopyOption.REPLACE_EXISTING );
    }
    Files.copy( new File( ejemplo, "solucion-referencia/06_kpi_desde_ficheros.ktr" ).toPath(), new File( carpeta,
      "referencia.ktr" ).toPath(), StandardCopyOption.REPLACE_EXISTING );
    CorrectorLote.Opciones op = new CorrectorLote.Opciones();
    op.pack = new File( ejemplo, "pack-correccion" );
    op.carpeta = carpeta;
    op.salida = new File( base, "notas" );
    op.workers = 2;
    op.planilla = CorrectorPlanilla.CSV;
    op.procesos = new CorrectorProcesos.Opciones();
    op.procesos.pdi = EntornoKettle.pdi();
    op.procesos.xmxMb = 640;
    op.procesos.versionPlugin = "prueba";
    long t0 = System.currentTimeMillis();
    CorrectorLote l = CorrectorLote.iniciar( op );
    cierto( l.esperar( 300000 ), "el lote termina" );
    System.out.println( "  lote de ejemplo: " + ( System.currentTimeMillis() - t0 ) + " ms" );
    Map<String, Double> notas = new LinkedHashMap<String, Double>();
    for ( CorrectorLote.Renglon r : l.estado().resultados ) {
      igual( "graded", r.estado, r.alumno + " corregida" );
      notas.put( r.alumno, r.nota );
    }
    igual( 10.0, notas.get( "referencia" ), "la solucion de referencia" );
    igual( 9.5, notas.get( "lucia_rutas_absolutas" ), "lucia" );
    igual( 5.3, notas.get( "marcos_sin_quitar_duplicados" ), "marcos" );
    igual( 6.0, notas.get( "nerea_decimal_vacio" ), "nerea" );
    igual( 5.0, notas.get( "oscar_group_by_sin_ordenar" ), "oscar" );

    ResultadoCorreccion lucia = l.resultado( "lucia_rutas_absolutas" );
    falso( lucia.criterio( "lint:absolute_path" ).aprobado, "lucia pierde las rutas relativas" );
    cierto( lucia.caso( "visible" ).redirecciones.toString().contains( "C:\\Users\\lucia" ), "y se anotan las rutas" );
    falso( l.resultado( "marcos_sin_quitar_duplicados" ).criterio( "rule:quita_duplicados" ).aprobado,
      "marcos sin Unique rows" );
    pista( l.resultado( "nerea_decimal_vacio" ), "100", "nerea: la pista del decimal" );
    pista( l.resultado( "oscar_group_by_sin_ordenar" ), "Sort rows", "oscar: la pista del Sort rows" );
    // la devolucion del alumno no trae los valores del caso oculto
    String html = EntornoKettle.leer( new File( op.salida, "nerea_decimal_vacio.html" ) );
    List<String> filas = Files.readAllLines( new File( ejemplo, "pack-correccion/esperado/oculto.csv" ).toPath() );
    for ( String f : filas.subList( 1, filas.size() ) ) {
      String importe = f.substring( f.lastIndexOf( ';' ) + 1 );
      if ( !EntornoKettle.leer( new File( ejemplo, "pack-correccion/esperado/visible.csv" ) ).contains( importe ) ) {
        falso( html.contains( importe ) || html.contains( importe.replace( '.', ',' ) ), "sin el importe oculto "
          + importe );
      }
    }
  }

  private static void pista( ResultadoCorreccion r, String parte, String msg ) {
    noNulo( r, msg );
    contiene( String.join( " | ", r.pistas ), parte, msg );
  }

  /** Espera (hasta 2 min) a que haya 2 GB libres antes de lanzar JVMs. */
  private static void memoria() throws InterruptedException {
    for ( int i = 0; i < 24; i++ ) {
      long libre;
      try {
        Object os = ManagementFactory.getOperatingSystemMXBean();
        java.lang.reflect.Method m = Class.forName( "com.sun.management.OperatingSystemMXBean" ).getMethod(
          "getFreePhysicalMemorySize" );
        libre = ( (Number) m.invoke( os ) ).longValue();
      } catch ( Throwable x ) {
        return;
      }
      if ( libre >= 2L * 1024 * 1024 * 1024 ) {
        return;
      }
      System.out.println( "  esperando memoria libre (" + ( libre >> 20 ) + " MB)" );
      Thread.sleep( 5000 );
    }
  }
}
