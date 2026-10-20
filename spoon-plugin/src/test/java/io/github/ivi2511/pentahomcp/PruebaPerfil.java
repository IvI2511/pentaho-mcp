package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Perfil de datos (WP4, diseno §2.4.3 y §8.1): perfil de 04 (lookup con un cliente que no existe),
 * de la copia grande (20.000 filas en menos de 5 s), formato con el idioma del informe y no con el de
 * la JVM, privacidad, escritores aguas arriba y casos que no aplican.
 *
 * @dueño WP4
 */
public class PruebaPerfil {

  /** Filas del ventas.csv de la copia grande. */
  static final int FILAS_GRANDE = 60000;
  /** Filas de la copia enorme (corridas que tienen que durar mas de un segundo). */
  static final int FILAS_ENORME = 400000;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  // ---------------------------------------------------------------- ayudas (tambien las usa PruebaMetricas)

  /**
   * Copia grande de 03 y 04 en {@code <prueba>\ej_grande_<filas>}: los mismos .ktr con un ventas.csv
   * generado (con acentos, un producto vacio cada 997 filas y el cliente 9, que no existe, cada 9).
   */
  static synchronized File grande( int filas ) throws IOException {
    File dir = new File( EntornoKettle.carpeta(), "ej_grande_" + filas );
    File ventas = new File( dir, "datos/ventas.csv" );
    if ( ventas.isFile() && ventas.length() > filas * 20L ) {
      return dir;
    }
    File ej = EntornoKettle.ejemplos();
    new File( dir, "datos" ).mkdirs();
    for ( String f : new String[] { "03_ventas_por_producto.ktr", "04_ventas_con_cliente.ktr", "datos/clientes.csv" } ) {
      Files.copy( new File( ej, f ).toPath(), new File( dir, f ).toPath(), StandardCopyOption.REPLACE_EXISTING );
    }
    String[] productos = { "Café", "Té", "Medialuna", "Tostado", "Jugo de naranja" };
    String[] precios = { "2.50", "1.80", "1.20", "4.75", "3.10" };
    try ( BufferedWriter w = new BufferedWriter( new OutputStreamWriter( new FileOutputStream( ventas ),
      StandardCharsets.UTF_8 ) ) ) {
      w.write( "venta_id;cliente_id;producto;cantidad;precio_unitario;fecha_venta\n" );
      for ( int i = 1; i <= filas; i++ ) {
        int p = ( i * 7 ) % productos.length;
        int cliente = i % 9 == 0 ? 9 : 1 + ( i * 5 ) % 8;
        String producto = i % 997 == 0 ? "" : productos[ p ];
        int mes = 1 + ( i % 12 );
        int dia = 1 + ( i % 28 );
        w.write( i + ";" + cliente + ";" + producto + ";" + ( 1 + i % 12 ) + ";" + precios[ p ] + ";2024-" + ( mes < 10
          ? "0" : "" ) + mes + "-" + ( dia < 10 ? "0" : "" ) + dia + "\n" );
      }
    }
    return dir;
  }

  /** Captura de un .ktr cualquiera (como EntornoKettle.captura, pero por ruta). */
  static Captura capturaDe( File ktr ) throws Exception {
    EntornoKettle.iniciar();
    TransMeta tm = new TransMeta( ktr.getAbsolutePath() );
    Captura c = new Captura();
    c.copia = tm;
    c.docId = "prueba:" + ktr.getName();
    c.nombre = tm.getName();
    c.archivo = ktr.getAbsolutePath();
    c.tipo = Captura.TRANSFORMACION;
    c.capturado = new Date();
    return c;
  }

  static ContextoPrueba contexto( Captura c, String idioma, String step, int filas, Tema tema ) {
    Pedido p = new Pedido();
    p.idioma = idioma;
    p.perfilStep = step;
    p.perfilFilas = filas;
    return new ContextoPrueba( c, p, tema == null ? new Tema() : tema );
  }

  static JsonNode campo( Seccion s, String nombre ) {
    for ( JsonNode f : s.datos.get( "fields" ) ) {
      if ( nombre.equals( f.get( "name" ).asText() ) ) {
        return f;
      }
    }
    throw new AssertionError( "no hay campo " + nombre + " en " + s.datos );
  }

  static Tabla tabla( Seccion s, String id ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla && id.equals( b.id ) ) {
        return (Tabla) b;
      }
    }
    return null;
  }

  static boolean hayAviso( Seccion s, String id ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Aviso && id.equals( b.id ) ) {
        return true;
      }
    }
    return false;
  }

  /** La fila de la tabla cuyo primer valor es 'clave'. */
  static java.util.List<String> fila( Tabla t, String clave ) {
    for ( java.util.List<String> f : t.filas ) {
      if ( clave.equals( f.get( 0 ) ) ) {
        return f;
      }
    }
    throw new AssertionError( "no hay fila " + clave + " en la tabla " + t.id );
  }

  // ---------------------------------------------------------------- pruebas

  /** 04 «Buscar cliente»: 10 filas, el cliente 9 no existe (1 nulo en nombre y ciudad), sin escritores. */
  public void pruebaPerfil04() throws Exception {
    File salida = new File( EntornoKettle.ejemplos(), "salida" );
    long antes = ultimaModificacion( salida, "04_ventas_con_cliente" );
    ContextoPrueba ctx = contexto( EntornoKettle.captura( "04" ), "es", "Buscar cliente", 0, null );
    Seccion s = new SecPerfil().armar( ctx );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    JsonNode d = s.datos;
    igual( "data_profile", d.get( "shape" ).asText(), "forma de datos" );
    igual( 10L, d.get( "sample_rows" ).asLong(), "filas perfiladas" );
    igual( "finished", d.get( "state" ).asText(), "llego al final (menos de 5.000 filas)" );
    igual( 5000, d.get( "row_limit" ).asInt(), "tope por defecto (perfilFilas 0 = el del tema)" );
    igual( 0, d.get( "writers_ran" ).size(), "Buscar cliente no tiene escritores aguas arriba" );
    cierto( d.get( "steps_excluded" ).asInt() >= 1, "el escritor de abajo quedo afuera o como Dummy" );

    JsonNode cliente = campo( s, "cliente" );
    igual( 0L, cliente.get( "nulls" ).asLong(), "el lookup pone un valor por defecto: sin nulos" );
    boolean desconocido = false;
    for ( JsonNode x : cliente.get( "top" ) ) {
      desconocido |= "DESCONOCIDO".equals( x.get( "value" ).asText() ) && x.get( "count" ).asLong() == 1;
    }
    cierto( desconocido, "el cliente 9 no existe: 1 DESCONOCIDO" );
    igual( "Ana Pérez", cliente.get( "min" ).asText(), "minimo de texto (con acento)" );
    JsonNode venta = campo( s, "venta_id" );
    cierto( venta.get( "unique" ).asBoolean(), "venta_id es unico" );
    igual( 101L, venta.get( "min" ).asLong(), "min venta_id" );
    igual( 110L, venta.get( "max" ).asLong(), "max venta_id" );
    JsonNode fecha = campo( s, "fecha_venta" );
    igual( "2024-05-01", fecha.get( "min" ).asText(), "fecha minima en ISO" );
    igual( "2024-05-05", fecha.get( "max" ).asText(), "fecha maxima en ISO" );
    JsonNode producto = campo( s, "producto" );
    igual( 4, producto.get( "distinct" ).asInt(), "4 productos" );
    igual( "Café", producto.get( "top" ).get( 0 ).get( "value" ).asText(), "el mas vendido (con acento)" );
    igual( 4L, producto.get( "top" ).get( 0 ).get( "count" ).asLong(), "Café 4 veces" );
    JsonNode cantidad = campo( s, "cantidad" );
    igual( 36.0, cantidad.get( "sum" ).asDouble(), "suma de cantidad" );
    igual( 3.6, cantidad.get( "mean" ).asDouble(), "media de cantidad" );
    JsonNode precio = campo( s, "precio_unitario" );
    igual( 4.75, precio.get( "max" ).asDouble(), "max precio (lazy conversion: bytes -> Number)" );

    // bloques
    Tabla t = tabla( s, "profile-fields" );
    noNulo( t, "tabla de campos" );
    contiene( fila( t, "cliente" ).get( 2 ), "0 (0,0 %)", "nulos con coma decimal (es)" );
    igual( "2024-05-01", fila( t, "fecha_venta" ).get( 6 ), "fecha minima en la tabla" );
    igual( "4,75", fila( t, "precio_unitario" ).get( 7 ), "max precio con coma (es)" );
    noNulo( tabla( s, "profile-numeric" ), "tabla de numericos" );
    noNulo( tabla( s, "profile-top" ), "tabla de frecuentes" );
    cierto( hayAviso( s, "profile-sample" ), "aviso de la muestra" );
    falso( hayAviso( s, "profile-writers" ), "sin aviso de escritores" );
    for ( Bloque b : s.bloques ) {
      falso( b instanceof Grafico, "sin nulos no hay grafico de nulos" );
    }
    contiene( s.resumen, "Ningún campo tiene nulos", "resumen sin nulos" );
    contiene( s.resumen, "«Buscar cliente»", "resumen con el step" );
    contiene( s.resumen, "10 filas", "resumen con las filas" );

    EntornoKettle.escribir( new File( EntornoKettle.salida( "metricas" ), "profile_04.json" ), ModeloJson.escribir( s,
      true ) );
    // ida y vuelta JSON, y no escribio la salida de 04
    Seccion vuelta = ModeloJson.leer( ModeloJson.escribir( s, false ), Seccion.class );
    igual( s.bloques.size(), vuelta.bloques.size(), "ida y vuelta JSON" );
    igual( antes, ultimaModificacion( salida, "04_ventas_con_cliente" ), "el perfil no escribio la salida de 04" );
  }

  /** La copia grande: 20.000 filas en menos de 5 s, con tope de distintos y vacios convertidos en nulos. */
  public void pruebaCopiaGrande() throws Exception {
    File dir = grande( FILAS_GRANDE );
    Captura c = capturaDe( new File( dir, "04_ventas_con_cliente.ktr" ) );
    ContextoPrueba ctx = contexto( c, "en", "Buscar cliente", 20000, null );
    long t0 = System.currentTimeMillis();
    Seccion s = new SecPerfil().armar( ctx );
    long ms = System.currentTimeMillis() - t0;
    System.out.println( "  perfil de 20.000 filas en " + ms + " ms (" + s.datos.get( "ms" ).asLong() + " ms de corrida)" );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    igual( 20000L, s.datos.get( "sample_rows" ).asLong(), "exactamente el tope de filas" );
    igual( "row_limit", s.datos.get( "state" ).asText(), "corto por el tope" );
    cierto( ms < 5000, "20.000 filas en menos de 5 s: " + ms + " ms" );
    JsonNode venta = campo( s, "venta_id" );
    cierto( venta.get( "distinct_is_lower_bound" ).asBoolean(), "venta_id pasa el tope de 5.000 distintos" );
    igual( SecPerfil.MAX_DISTINTOS, venta.get( "distinct" ).asInt(), "distintos hasta el tope" );
    falso( venta.get( "unique" ).asBoolean(), "con tope no se puede decir unico" );
    JsonNode producto = campo( s, "producto" );
    igual( 20L, producto.get( "nulls" ).asLong(), "un producto vacio cada 997 filas = 20 nulos" );
    long desconocidos = 0;
    for ( JsonNode x : campo( s, "cliente" ).get( "top" ) ) {
      desconocidos += "DESCONOCIDO".equals( x.get( "value" ).asText() ) ? x.get( "count" ).asLong() : 0;
    }
    igual( 2222L, desconocidos, "el cliente 9 no existe (1 de cada 9 filas)" );
    Grafico g = null;
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Grafico ) {
        g = (Grafico) b;
      }
    }
    noNulo( g, "con nulos hay grafico" );
    igual( "producto", g.categorias.get( 0 ), "el campo con mas nulos primero" );
    igual( 0.1, g.series.get( 0 ).valores.get( 0 ), "0,1 % de nulos" );
    contiene( s.resumen, "“producto” (0.1 %)", "resumen: el campo con mas nulos (en)" );
    Tabla t = tabla( s, "profile-fields" );
    contiene( t.pie, "5,000", "pie con el tope de distintos (en)" );
    contiene( fila( t, "venta_id" ).get( 4 ), "≥ 5,000", "distintos como cota inferior" );
    contiene( s.resumen, "first 20,000 rows", "resumen: muestra de 20.000 (en)" );
  }

  /** Formatos con el idioma del informe aunque la JVM tenga otro locale; fechas siempre ISO. */
  public void pruebaLocale() throws Exception {
    Locale antes = Locale.getDefault();
    try {
      Locale.setDefault( new Locale( "es", "ES" ) );
      Seccion en = new SecPerfil().armar( contexto( EntornoKettle.captura( "04" ), "en", "Leer ventas", 0, null ) );
      igual( Seccion.OK, en.estado, "estado en" );
      Tabla t = tabla( en, "profile-fields" );
      igual( "4.75", fila( t, "precio_unitario" ).get( 7 ), "punto decimal en ingles con la JVM en es_ES" );
      igual( "2024-05-05", fila( t, "fecha_venta" ).get( 7 ), "fecha ISO" );
      contiene( fila( tabla( en, "profile-numeric" ), "precio_unitario" ).get( 1 ), ".", "media con punto (en)" );

      Locale.setDefault( Locale.US );
      Seccion es = new SecPerfil().armar( contexto( EntornoKettle.captura( "04" ), "es", "Leer ventas", 0, null ) );
      Tabla te = tabla( es, "profile-fields" );
      igual( "4,75", fila( te, "precio_unitario" ).get( 7 ), "coma decimal en castellano con la JVM en en_US" );
      igual( "2024-05-05", fila( te, "fecha_venta" ).get( 7 ), "fecha ISO tambien en es" );
      contiene( fila( tabla( es, "profile-numeric" ), "precio_unitario" ).get( 1 ), ",", "media con coma (es)" );
      igual( 4.75, campo( es, "precio_unitario" ).get( "max" ).asDouble(), "en datos va el numero crudo" );

      Seccion pt = new SecPerfil().armar( contexto( EntornoKettle.captura( "04" ), "pt", "Leer ventas", 0, null ) );
      igual( "4,75", fila( tabla( pt, "profile-fields" ), "precio_unitario" ).get( 7 ), "coma decimal en portugues" );
      contiene( pt.resumen, "“", "comillas del portugues en el resumen" );
    } finally {
      Locale.setDefault( antes );
    }
  }

  /** Perfilar un step que escribe: corre y lo avisa; la confirmacion lista los escritores. */
  public void pruebaEscritores() throws Exception {
    TransMeta t03 = EntornoKettle.trans( "03" );
    nulo( CorridaMedida.textoConfirmacionPerfil( t03, "Totales por producto", "es" ), "nada escribe antes de Totales" );
    String conf = CorridaMedida.textoConfirmacionPerfil( t03, "Escribir totales", "es" );
    noNulo( conf, "Escribir totales escribe" );
    contiene( conf, "«Escribir totales»", "la confirmacion nombra el step" );
    igual( java.util.Arrays.asList( "Escribir totales" ), CorridaMedida.escritoresAguasArriba( t03, "Escribir totales" ),
      "escritores aguas arriba" );

    Seccion s = new SecPerfil().armar( contexto( EntornoKettle.captura( "03" ), "es", "Escribir totales", 0, null ) );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    cierto( hayAviso( s, "profile-writers" ), "avisa que escribio" );
    igual( "Escribir totales", s.datos.get( "writers_ran" ).get( 0 ).asText(), "datos: writers_ran" );
    igual( 4L, s.datos.get( "sample_rows" ).asLong(), "4 productos" );
  }

  /** perfil.mostrarValores = false: sin valores frecuentes ni min/max de textos, en bloques y en datos. */
  public void pruebaPrivacidad() throws Exception {
    Tema tema = new Tema();
    tema.perfil.mostrarValores = false;
    Seccion s = new SecPerfil().armar( contexto( EntornoKettle.captura( "04" ), "es", "Buscar cliente", 0, tema ) );
    igual( Seccion.OK, s.estado, "estado" );
    nulo( tabla( s, "profile-top" ), "sin tabla de frecuentes" );
    cierto( hayAviso( s, "profile-hidden" ), "avisa que oculta" );
    igual( "—", fila( tabla( s, "profile-fields" ), "cliente" ).get( 6 ), "min de texto oculto" );
    igual( "101", fila( tabla( s, "profile-fields" ), "venta_id" ).get( 6 ), "min numerico visible" );
    String json = ModeloJson.escribir( s, false );
    noContiene( json, "Pérez", "ningun nombre de cliente en el JSON" );
    noContiene( json, "Madrid", "ninguna ciudad en el JSON" );
    noContiene( json, "\"top\"", "sin valores frecuentes en datos" );
    cierto( s.datos.get( "values_hidden" ).asBoolean(), "datos: values_hidden" );
  }

  /** Jobs, sin step, step inexistente: omitida o error con motivo, nunca excepcion. */
  /**
   * Textos largos (CLOB, descripciones): los distintos se cuentan con una clave corta (principio y hash),
   * asi 5.000 valores no son 5.000 textos enteros en memoria, y los datos crudos van recortados.
   */
  public void pruebaTextosLargos() throws Exception {
    SecPerfil.Campo c = new SecPerfil.Campo( new org.pentaho.di.core.row.value.ValueMetaString( "texto" ) );
    StringBuilder sb = new StringBuilder();
    for ( int i = 0; i < 5000; i++ ) {
      sb.append( 'a' );
    }
    String a = sb + "1";
    String b = sb + "2";
    c.agregar( a );
    c.agregar( b );
    c.agregar( a );
    igual( 2, c.cuenta.size(), "dos distintos aunque empiecen igual" );
    for ( Object k : c.cuenta.keySet() ) {
      cierto( ( (String) k ).length() <= SecPerfil.MAX_CLAVE + 12, "clave corta: " + ( (String) k ).length() );
    }
    igual( 2L, c.top().get( 0 ).getValue()[ 0 ], "el repetido cuenta 2" );
    igual( "corto", SecPerfil.Campo.clave( "corto" ), "un texto corto es su propia clave" );
    igual( SecPerfil.MAX_DATOS + 1, SecPerfil.recortarDato( a ).length(), "datos crudos recortados (mas «…»)" );
    igual( "corto", SecPerfil.recortarDato( "corto" ), "un texto corto queda igual" );
  }

  public void pruebaNoAplica() throws Exception {
    SecPerfil p = new SecPerfil();
    noNulo( p.noAplica( EntornoKettle.captura( "10" ), "es" ), "un job no tiene perfil" );
    nulo( p.noAplica( EntornoKettle.captura( "03" ), "es" ), "una transformacion si" );
    Seccion job = p.armar( ContextoPrueba.de( "10", "es" ) );
    igual( Seccion.OMITIDA, job.estado, "job: omitida" );
    igual( "Solo para transformaciones", job.motivo, "motivo traducido" );
    Seccion sinStep = p.armar( contexto( EntornoKettle.captura( "03" ), "en", null, 0, null ) );
    igual( Seccion.OMITIDA, sinStep.estado, "sin step: omitida (no ejecuta nada)" );
    Seccion malo = p.armar( contexto( EntornoKettle.captura( "03" ), "pt", "No existe", 0, null ) );
    igual( Seccion.ERROR, malo.estado, "step inexistente: error" );
    contiene( malo.motivo, "No existe", "el motivo nombra el step" );
  }

  /** Cancelado a mitad de camino: parcial, con las filas que llego a ver. */
  public void pruebaCancelada() throws Exception {
    File dir = grande( FILAS_ENORME );
    final ContextoPrueba ctx = contexto( capturaDe( new File( dir, "03_ventas_por_producto.ktr" ) ), "es",
      "Calcular importe", 1000000, null );
    Thread cancelador = new Thread( new Runnable() {
      @Override
      public void run() {
        RunMotor.dormir( 150 );
        ctx.cancelar = true;
      }
    } );
    cancelador.start();
    Seccion s = new SecPerfil().armar( ctx );
    cancelador.join();
    if ( "finished".equals( s.datos.get( "state" ).asText() ) ) {
      Verificador.saltear( "la corrida termino antes de cancelar (" + s.datos.get( "ms" ).asLong() + " ms)" );
    }
    igual( Seccion.PARCIAL, s.estado, "cancelado: parcial" );
    igual( "cancelled", s.datos.get( "state" ).asText(), "estado de la corrida" );
    cierto( s.datos.get( "sample_rows" ).asLong() < FILAS_ENORME, "no llego al final" );
  }

  private static long ultimaModificacion( File dir, String prefijo ) {
    long m = -1;
    File[] fs = dir.listFiles();
    if ( fs != null ) {
      for ( File f : fs ) {
        if ( f.getName().startsWith( prefijo ) ) {
          m = Math.max( m, f.lastModified() );
        }
      }
    }
    return m;
  }
}
