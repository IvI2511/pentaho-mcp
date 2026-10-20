package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Display;
import org.pentaho.di.core.Props;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.core.PropsUI;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Disponibilidad;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * El motor (InformeServicio) de punta a punta sobre los ejemplos 01-11: disponibilidad, seleccion
 * por defecto, informes completos en JSON (estados y tiempos), narrativa, cancelacion a mitad de
 * camino, plazo agotado, exportacion (JSON propio; HTML/MD/PPTX/PDF si sus paquetes ya estan), vista
 * previa, captura de archivo con y sin Display y la codificacion del lienzo.
 *
 * @dueño WP2
 */
public class PruebaServicio {

  private static final String[] EJEMPLOS = { "01", "02", "03", "04", "05", "06", "07", "10", "11" };
  private static final List<String> ESTADOS = Arrays.asList( Seccion.OK, Seccion.PARCIAL, Seccion.OMITIDA,
    Seccion.ERROR );
  /** Las secciones de WP2 (y quality, del linter): nunca deberian quedar en "error" sobre los ejemplos. */
  private static final List<String> PROPIAS = Arrays.asList( SeccionId.PORTADA, SeccionId.RESUMEN, SeccionId.FLUJO,
    SeccionId.FLUJO_JOB, SeccionId.DICCIONARIO, SeccionId.VALIDACION, SeccionId.CALIDAD, SeccionId.CONEXIONES,
    SeccionId.NARRATIVA );

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    InformeServicio.temporales = EntornoKettle.salida( "temp" );
  }

  private static Informe armar( Captura c, Pedido p, long seg ) throws Exception {
    return InformeServicio.armar( c, p, new Tema(), Progreso.NULO ).get( seg, TimeUnit.SECONDS );
  }

  private static Pedido pedido( String idioma ) {
    Pedido p = new Pedido();
    p.idioma = idioma;
    return p;
  }

  private static List<String> ids( Informe inf ) {
    List<String> l = new ArrayList<String>();
    for ( Seccion s : inf.secciones ) {
      l.add( s.id );
    }
    return l;
  }

  // ---------------------------------------------------------------- disponibilidad

  public void pruebaDisponibles() throws Exception {
    Map<String, Disponibilidad> t = mapa( InformeServicio.disponibles( EntornoKettle.captura( "03" ), "es" ) );
    igual( SeccionId.ORDEN.size(), t.size(), "una por seccion" );
    cierto( t.get( SeccionId.FLUJO ).aplica, "flow aplica" );
    falso( t.get( SeccionId.FLUJO_JOB ).aplica, "job_flow no aplica" );
    igual( "Solo para jobs", t.get( SeccionId.FLUJO_JOB ).motivo, "motivo traducido" );
    falso( t.get( SeccionId.FLUJO_JOB ).porDefecto, "lo que no aplica no va por defecto" );
    falso( t.get( SeccionId.PERFIL ).porDefecto, "profile no va por defecto" );
    falso( t.get( SeccionId.METRICAS ).porDefecto, "metrics sin corrida no va por defecto" );
    cierto( t.get( SeccionId.DICCIONARIO ).porDefecto, "dictionary por defecto" );
    igual( Disponibilidad.COSTO_UI, t.get( SeccionId.FLUJO ).costo, "costo flow" );
    igual( Disponibilidad.COSTO_CAMPOS, t.get( SeccionId.DICCIONARIO ).costo, "costo dictionary" );
    igual( Disponibilidad.COSTO_EJECUTA, t.get( SeccionId.PERFIL ).costo, "costo profile" );
    igual( Disponibilidad.COSTO_NADA, t.get( SeccionId.CONEXIONES ).costo, "costo connections" );
    igual( Disponibilidad.COSTO_CAMPOS, t.get( SeccionId.CALIDAD ).costo, "costo quality: calcula campos" );
    cierto( t.get( SeccionId.CALIDAD ).aplica && t.get( SeccionId.CALIDAD ).porDefecto, "quality por defecto" );
    igual( Disponibilidad.COSTO_BASE, mapa( InformeServicio.disponibles( EntornoKettle.captura( "07" ), "en" ) ).get(
      SeccionId.VALIDACION ).costo, "validacion con conexion" );

    Map<String, Disponibilidad> j = mapa( InformeServicio.disponibles( EntornoKettle.captura( "10" ), "en" ) );
    cierto( j.get( SeccionId.FLUJO_JOB ).aplica, "job_flow aplica a jobs" );
    igual( "Only for transformations", j.get( SeccionId.FLUJO ).motivo, "flow en un job" );
    falso( j.get( SeccionId.DICCIONARIO ).aplica, "dictionary no aplica a jobs" );
    cierto( j.get( SeccionId.CALIDAD ).aplica, "quality tambien para jobs" );

    List<String> orden = new ArrayList<String>();
    for ( Recolector r : InformeServicio.recolectores() ) {
      orden.add( r.id() );
    }
    igual( SeccionId.ORDEN, orden, "recolectores registrados en el orden del informe" );
  }

  private static Map<String, Disponibilidad> mapa( List<Disponibilidad> l ) {
    Map<String, Disponibilidad> m = new LinkedHashMap<String, Disponibilidad>();
    for ( Disponibilidad d : l ) {
      m.put( d.id, d );
    }
    return m;
  }

  public void pruebaSeleccionPorDefecto() throws Exception {
    Pedido p = pedido( "es" );
    Captura c03 = EntornoKettle.captura( "03" );
    List<String> t = InformeServicio.seleccion( c03, p, new Tema(), null );
    List<String> esperadas = new ArrayList<String>();
    for ( String id : Arrays.asList( SeccionId.PORTADA, SeccionId.RESUMEN, SeccionId.FLUJO, SeccionId.LINAJE,
      SeccionId.DICCIONARIO, SeccionId.DESTINO, SeccionId.VALIDACION, SeccionId.CALIDAD, SeccionId.CONEXIONES ) ) {
      if ( InformeServicio.recolector( id ).noAplica( c03, "es" ) == null ) {
        esperadas.add( id ); // target_model depende de su noAplica (WP5): 03 no tiene Table output
      }
    }
    igual( esperadas, t, "defaults de 03" );
    falso( t.contains( SeccionId.METRICAS ), "sin corrida no va metrics" );
    falso( t.contains( SeccionId.PERFIL ), "sin perfilStep no va profile" );
    falso( t.contains( SeccionId.FLUJO_JOB ), "job_flow no aplica" );
    p.perfilStep = "Leer ventas";
    p.narrativa = new Narrativa();
    p.narrativa.conclusiones = "Texto.";
    List<String> t2 = InformeServicio.seleccion( EntornoKettle.captura( "03" ), p, new Tema(), null );
    cierto( t2.contains( SeccionId.PERFIL ), "profile con perfilStep" );
    cierto( t2.contains( SeccionId.NARRATIVA ), "narrative con conclusiones" );
    p.metricas = Pedido.METRICAS_COPIA;
    cierto( InformeServicio.seleccion( EntornoKettle.captura( "03" ), p, new Tema(), null ).contains(
      SeccionId.METRICAS ), "metrics con run_copy" );

    Tema tema = new Tema();
    tema.secciones = Arrays.asList( SeccionId.PORTADA, SeccionId.CONEXIONES, SeccionId.FLUJO_JOB );
    igual( Arrays.asList( SeccionId.PORTADA, SeccionId.CONEXIONES ), InformeServicio.seleccion( EntornoKettle
      .captura( "03" ), pedido( "en" ), tema, null ), "tema.secciones, solo las que aplican" );

    List<String> avisos = new ArrayList<String>();
    Pedido x = pedido( "en" );
    x.secciones = Arrays.asList( "connections", "xyz", "cover", "cover" );
    igual( Arrays.asList( SeccionId.PORTADA, SeccionId.CONEXIONES ), InformeServicio.seleccion( EntornoKettle.captura(
      "03" ), x, new Tema(), avisos ), "explicitas, en orden y sin repetir" );
    igual( 1, avisos.size(), "aviso de la desconocida" );
    contiene( avisos.get( 0 ), "xyz", "aviso" );
  }

  // ---------------------------------------------------------------- informes completos

  public void pruebaArmarEjemplos() throws Exception {
    File dir = EntornoKettle.salida( "informes-wp2" );
    for ( String e : EJEMPLOS ) {
      for ( String idioma : "03".equals( e ) || "10".equals( e ) ? new String[] { "en", "es", "pt" }
        : new String[] { "es" } ) {
        Captura c = EntornoKettle.captura( e );
        long t0 = System.currentTimeMillis();
        Informe inf = armar( c, pedido( idioma ), 300 );
        long ms = System.currentTimeMillis() - t0;
        String nombre = e + "." + idioma;
        igual( idioma, inf.idioma, "idioma " + nombre );
        igual( c.nombre, inf.titulo, "titulo " + nombre );
        igual( c.archivo, inf.archivo, "archivo " + nombre );
        igual( c.esJob() ? Informe.JOB : Informe.TRANSFORMACION, inf.tipoDoc, "tipoDoc " + nombre );
        noNulo( inf.generado, "generado" );
        cierto( inf.generado.matches( "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-Z].*" ), "ISO-8601: " + inf.generado );
        for ( String k : InformeServicio.CLAVES_ROTULOS ) {
          noNulo( inf.rotulos.get( k ), "rotulo " + k );
          falso( inf.rotulos.get( k ).startsWith( "rotulo." ), "rotulo traducido " + k );
        }
        List<String> ids = ids( inf );
        cierto( ids.size() >= 5, "secciones de " + nombre + ": " + ids );
        int ultimo = -1;
        StringBuilder estados = new StringBuilder();
        for ( Seccion s : inf.secciones ) {
          int i = SeccionId.ORDEN.indexOf( s.id );
          cierto( i > ultimo, "orden de " + nombre + ": " + ids );
          ultimo = i;
          cierto( ESTADOS.contains( s.estado ), "estado valido " + s.id );
          noNulo( s.titulo, "titulo de " + s.id );
          if ( PROPIAS.contains( s.id ) ) {
            falso( Seccion.ERROR.equals( s.estado ), nombre + ": " + s.id + " en error: " + s.motivo );
            noNulo( s.resumen, nombre + ": resumen de " + s.id );
          }
          estados.append( ' ' ).append( s.id ).append( '=' ).append( s.estado );
        }
        cierto( ids.contains( c.esJob() ? SeccionId.FLUJO_JOB : SeccionId.FLUJO ), "flujo en " + nombre );
        falso( ids.contains( c.esJob() ? SeccionId.FLUJO : SeccionId.FLUJO_JOB ), "sin el flujo del otro tipo" );
        // ida y vuelta JSON
        String json = ModeloJson.escribir( inf, true );
        Informe vuelta = ModeloJson.leer( json, Informe.class );
        igual( inf.secciones.size(), vuelta.secciones.size(), "JSON ida y vuelta " + nombre );
        EntornoKettle.escribir( new File( dir, nombre + ".json" ), json );
        System.out.println( "  " + nombre + " " + ms + " ms:" + estados );
        System.out.println( "    " + inf.seccion( SeccionId.RESUMEN ).resumen );
        cierto( ms < 120000, "tiempo de " + nombre + ": " + ms + " ms" );
      }
    }
  }

  public void pruebaTodasLasSecciones() throws Exception {
    Pedido p = pedido( "es" );
    p.secciones = new ArrayList<String>( SeccionId.ORDEN );
    Informe inf = armar( EntornoKettle.captura( "03" ), p, 300 );
    igual( SeccionId.ORDEN, ids( inf ), "todas, en orden" );
    Seccion jf = inf.seccion( SeccionId.FLUJO_JOB );
    igual( Seccion.OMITIDA, jf.estado, "job_flow pedido en una trans" );
    igual( "Solo para jobs", jf.motivo, "motivo" );
    igual( Seccion.OMITIDA, inf.seccion( SeccionId.NARRATIVA ).estado, "narrative sin texto" );
    for ( Seccion s : inf.secciones ) {
      System.out.println( "  " + s.id + " = " + s.estado + ( s.motivo == null ? "" : " (" + s.motivo + ")" ) );
    }
  }

  public void pruebaNarrativa() throws Exception {
    Pedido p = pedido( "es" );
    p.narrativa = new Narrativa();
    p.narrativa.origen = Narrativa.IA;
    p.narrativa.resumen = "Resumen de la IA.";
    p.narrativa.conclusiones = "**Conclusión.** Todo bien.";
    p.narrativa.porSeccion.put( SeccionId.FLUJO, "Sobre el flujo." );
    p.narrativa.porSeccion.put( SeccionId.CONEXIONES, "Sobre las conexiones." );
    Informe inf = armar( EntornoKettle.captura( "06" ), p, 300 );
    Bloque r0 = inf.seccion( SeccionId.RESUMEN ).bloques.get( 0 );
    cierto( r0 instanceof Parrafo && "Resumen de la IA.".equals( ( (Parrafo) r0 ).texto ) && r0.ia,
      "resumen de la IA al principio de summary" );
    Bloque f0 = inf.seccion( SeccionId.FLUJO ).bloques.get( 0 );
    cierto( f0 instanceof Parrafo && "Sobre el flujo.".equals( ( (Parrafo) f0 ).texto ) && f0.ia,
      "narrativa de la seccion al principio" );
    Bloque c0 = inf.seccion( SeccionId.CONEXIONES ).bloques.get( 0 );
    igual( "Sobre las conexiones.", ( (Parrafo) c0 ).texto, "conexiones" );
    Seccion n = inf.seccion( SeccionId.NARRATIVA );
    noNulo( n, "narrative por defecto si hay conclusiones" );
    igual( Seccion.OK, n.estado, "narrative ok" );
    cierto( n.bloques.get( 0 ).ia, "conclusiones con ia" );
    // ids unicos en todo el informe
    List<String> vistos = new ArrayList<String>();
    for ( Seccion s : inf.secciones ) {
      for ( Bloque b : s.bloques ) {
        if ( b.id != null ) {
          falso( vistos.contains( b.id ), "id de bloque repetido: " + b.id );
          vistos.add( b.id );
        }
      }
    }
  }

  // ---------------------------------------------------------------- cancelacion y plazos

  public void pruebaCancelarPorProgreso() throws Exception {
    final AtomicInteger pasos = new AtomicInteger();
    final List<String> textos = Collections.synchronizedList( new ArrayList<String>() );
    Progreso pr = new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
        textos.add( texto );
        pasos.incrementAndGet();
      }

      @Override
      public boolean cancelado() {
        return pasos.get() >= 3; // despues de la segunda seccion
      }
    };
    Pedido p = pedido( "es" );
    p.secciones = new ArrayList<String>( SeccionId.ORDEN );
    long t0 = System.currentTimeMillis();
    Informe inf = InformeServicio.armar( EntornoKettle.captura( "05" ), p, new Tema(), pr ).get( 120, TimeUnit.SECONDS );
    long ms = System.currentTimeMillis() - t0;
    int canceladas = 0;
    for ( Seccion s : inf.secciones ) {
      if ( Seccion.OMITIDA.equals( s.estado ) && "Cancelado".equals( s.motivo ) ) {
        canceladas++;
      }
    }
    cierto( canceladas >= 5, "las secciones que faltaban quedan canceladas: " + canceladas );
    cierto( inf.avisos.contains( "El informe se canceló antes de terminar." ), "aviso: " + inf.avisos );
    igual( Seccion.OK, inf.seccion( SeccionId.PORTADA ).estado, "la portada llego a armarse" );
    System.out.println( "  cancelado en " + ms + " ms, " + canceladas + " secciones canceladas; pasos " + textos );
  }

  public void pruebaCancelarFuture() throws Exception {
    final CountDownLatch adentro = new CountDownLatch( 1 );
    final CountDownLatch nunca = new CountDownLatch( 1 );
    Progreso trabado = new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
        adentro.countDown();
        try {
          nunca.await( 60, TimeUnit.SECONDS ); // se traba hasta que lo interrumpan
        } catch ( InterruptedException e ) {
          Thread.currentThread().interrupt();
        }
      }

      @Override
      public boolean cancelado() {
        return false;
      }
    };
    Future<Informe> f = InformeServicio.armar( EntornoKettle.captura( "03" ), pedido( "en" ), new Tema(), trabado );
    cierto( adentro.await( 30, TimeUnit.SECONDS ), "el informe arranco" );
    long t0 = System.currentTimeMillis();
    cierto( f.cancel( true ), "cancel(true)" );
    cierto( f.isCancelled(), "cancelado" );
    // el pool sigue sirviendo: otro informe sale enseguida (los dos hilos no quedaron tomados)
    Informe otro = armar( EntornoKettle.captura( "01" ), pedido( "en" ), 120 );
    cierto( otro.secciones.size() > 3, "el pool sigue andando" );
    System.out.println( "  otro informe " + ( System.currentTimeMillis() - t0 ) + " ms despues del cancel" );
  }

  public void pruebaPlazoAgotado() throws Exception {
    Pedido p = pedido( "es" );
    p.timeoutMs = 0;
    Informe inf = armar( EntornoKettle.captura( "03" ), p, 120 );
    igual( Seccion.OK, inf.seccion( SeccionId.PORTADA ).estado, "portada (barata) sale igual" );
    igual( Seccion.OK, inf.seccion( SeccionId.FLUJO ).estado, "flujo (barato) sale igual" );
    igual( Seccion.OK, inf.seccion( SeccionId.CONEXIONES ).estado, "conexiones (barata) sale igual" );
    igual( Seccion.OK, inf.seccion( SeccionId.RESUMEN ).estado, "resumen sale igual" );
    Seccion d = inf.seccion( SeccionId.DICCIONARIO );
    igual( Seccion.OMITIDA, d.estado, "diccionario (caro) omitido" );
    contiene( d.motivo, "Se agotó el tiempo", "motivo" );
    igual( Seccion.OMITIDA, inf.seccion( SeccionId.VALIDACION ).estado, "validacion omitida" );
  }

  public void pruebaSeccionQueFallaOSeCuelga() throws Exception {
    // una seccion que falla o no respeta el plazo no traba el informe: queda en "error" con su motivo
    Pedido p = pedido( "es" );
    p.timeoutMs = 1500;
    ContextoKettle ctx = new ContextoKettle( EntornoKettle.captura( "01" ), p, new Tema(), null );
    Rotulos r = Rotulos.de( InformeServicio.AREA, "es" );
    Recolector rec = InformeServicio.recolector( SeccionId.DICCIONARIO );
    Seccion mala = InformeServicio.correr( SeccionId.DICCIONARIO, rec, new Callable<Seccion>() {
      @Override
      public Seccion call() {
        throw new IllegalStateException( "falla adrede" );
      }
    }, ctx, r );
    igual( Seccion.ERROR, mala.estado, "excepcion: error" );
    contiene( mala.motivo, "falla adrede", "motivo con el mensaje" );
    igual( "Diccionario de datos", mala.titulo, "titulo puesto por el motor" );
    Seccion nula = InformeServicio.correr( SeccionId.DICCIONARIO, rec, new Callable<Seccion>() {
      @Override
      public Seccion call() {
        return null;
      }
    }, ctx, r );
    igual( Seccion.ERROR, nula.estado, "null: error" );
    long t0 = System.currentTimeMillis();
    Seccion colgada = InformeServicio.correr( SeccionId.DICCIONARIO, rec, new Callable<Seccion>() {
      @Override
      public Seccion call() throws Exception {
        Thread.sleep( 120000 );
        return null;
      }
    }, ctx, r );
    long ms = System.currentTimeMillis() - t0;
    igual( Seccion.ERROR, colgada.estado, "colgada: error" );
    contiene( colgada.motivo, "No terminó en", "motivo" );
    cierto( ms < 15000, "no espera de mas: " + ms + " ms" );
  }

  // ---------------------------------------------------------------- exportar

  public void pruebaExportarJson() throws Exception {
    Informe inf = armar( EntornoKettle.captura( "04" ), pedido( "pt" ), 300 );
    File dir = EntornoKettle.salida( "exportar-wp2" );
    String base = InformeServicio.nombreBase( inf, new Tema() );
    igual( "04_ventas_con_cliente", base, "nombre base" );
    File f = InformeServicio.archivo( dir, base, Formato.JSON );
    Resultado r = InformeServicio.exportar( inf, new Tema(), Formato.JSON, f, Progreso.NULO );
    archivo( f, 1000, "JSON exportado" );
    igual( f.getAbsoluteFile(), r.archivos.get( 0 ), "el principal primero" );
    igual( f.length(), r.bytes, "bytes" );
    Informe vuelta = ModeloJson.leer( new String( Files.readAllBytes( f.toPath() ), "UTF-8" ), Informe.class );
    igual( inf.secciones.size(), vuelta.secciones.size(), "secciones" );
    igual( "pt", vuelta.idioma, "idioma" );
    Informe sb = InformeServicio.sinBinarios( inf );
    falso( ModeloJson.escribir( sb, false ).contains( "\"datos\":\"" ), "sin binarios" );
  }

  /**
   * HTML liviano sin Edge/Chrome: los diagramas van como recuadros y tablas y el Resultado lo avisa (en el
   * idioma del informe); con el navegador del tema la busqueda lo encuentra; una vista previa cancelada no
   * espera al navegador.
   */
  public void pruebaLivianoSinNavegador() throws Exception {
    Informe inf = armar( EntornoKettle.captura( "03" ), pedido( "es" ), 300 );
    cierto( !InformeServicio.diagramas( inf ).isEmpty(), "03 tiene diagramas Mermaid" );
    File dir = EntornoKettle.salida( "liviano-sin-navegador" );
    String antes = System.getProperty( io.github.ivi2511.pentahomcp.informes.navegador.Chromium.PROPIEDAD );
    System.setProperty( io.github.ivi2511.pentahomcp.informes.navegador.Chromium.PROPIEDAD, "none" );
    try {
      falso( InformeServicio.hayNavegador( new Tema() ), "con la propiedad en none no hay navegador" );
      Resultado r = InformeServicio.exportar( inf, new Tema(), Formato.HTML_ESTATICO, new File( dir, "x.light.html" ),
        Progreso.NULO );
      contiene( String.join( "\n", r.avisos ), "No hay Edge ni Chrome: los diagramas del HTML liviano",
        "el liviano avisa que va sin diagramas: " + r.avisos );
    } finally {
      if ( antes == null ) {
        System.clearProperty( io.github.ivi2511.pentahomcp.informes.navegador.Chromium.PROPIEDAD );
      } else {
        System.setProperty( io.github.ivi2511.pentahomcp.informes.navegador.Chromium.PROPIEDAD, antes );
      }
    }
    java.io.File edge = io.github.ivi2511.pentahomcp.informes.navegador.Chromium.ubicar();
    if ( edge != null ) {
      Tema conRuta = new Tema();
      conRuta.navegador = edge.getPath();
      cierto( InformeServicio.hayNavegador( conRuta ), "con la ruta del tema" );
    }
    // vista previa con pre-render y un Progreso cancelado: corta enseguida, sin esperar 60 s a Chromium
    Progreso cancelado = new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
      }

      @Override
      public boolean cancelado() {
        return true;
      }
    };
    long t0 = System.currentTimeMillis();
    try {
      InformeServicio.vistaPrevia( inf, new Tema(), true, cancelado );
      falla( "una vista previa cancelada no termina" );
    } catch ( java.util.concurrent.CancellationException e ) {
      cierto( System.currentTimeMillis() - t0 < 5000, "corta rapido: " + ( System.currentTimeMillis() - t0 ) + " ms" );
    }
    // carpeta relativa en los ajustes: desde la carpeta del documento, nunca desde data-integration
    Captura c = EntornoKettle.captura( "03" );
    Tema rel = new Tema();
    rel.salida.carpeta = "informes-rel";
    igual( new File( new File( c.archivo ).getAbsoluteFile().getParentFile(), "informes-rel" ),
      InformeServicio.carpetaPorDefecto( c, rel ), "relativa a la carpeta del .ktr" );
  }

  public void pruebaExportarFormatos() throws Exception {
    Informe inf = armar( EntornoKettle.captura( "03" ), pedido( "es" ), 300 );
    File dir = EntornoKettle.salida( "exportar-wp2" );
    String base = InformeServicio.nombreBase( inf, new Tema() );
    List<String> pendientes = new ArrayList<String>();
    List<String> hechos = new ArrayList<String>();
    for ( Formato f : new Formato[] { Formato.HTML, Formato.HTML_ESTATICO, Formato.MARKDOWN, Formato.PPTX,
      Formato.PDF } ) {
      File destino = InformeServicio.archivo( dir, base, f );
      try {
        Resultado r = InformeServicio.exportar( inf, ModeloMuestra.tema(), f, destino, Progreso.NULO );
        noNulo( r, "resultado " + f );
        cierto( !r.archivos.isEmpty(), "archivos de " + f );
        cierto( r.archivos.get( 0 ).isFile(), "principal de " + f + ": " + r.archivos.get( 0 ) );
        hechos.add( f.clave + " (" + r.bytes + " bytes" + ( r.avisos.isEmpty() ? "" : ", " + r.avisos ) + ")" );
      } catch ( UnsupportedOperationException e ) {
        pendientes.add( f.clave + " (" + e.getMessage() + ")" );
      }
    }
    System.out.println( "  exportados: " + hechos + "; pendientes de su paquete: " + pendientes );
    if ( hechos.isEmpty() ) {
      saltear( "todos los renderizadores siguen en stub: " + pendientes );
    }
  }

  public void pruebaVistaPrevia() throws Exception {
    Informe inf = armar( EntornoKettle.captura( "10" ), pedido( "es" ), 300 );
    File f;
    try {
      f = InformeServicio.vistaPrevia( inf, new Tema(), false, Progreso.NULO );
    } catch ( UnsupportedOperationException e ) {
      saltear( "RenderHtml sigue en stub (" + e.getMessage() + ")" );
      return;
    }
    archivo( f, 200, "vista.html" );
    igual( "vista.html", f.getName(), "nombre" );
    cierto( f.getAbsolutePath().startsWith( InformeServicio.carpetaVistas().getAbsolutePath() ), "bajo plive\\vista" );
    String html = EntornoKettle.leer( f );
    falso( html.toLowerCase().contains( "<script" ), "la vista previa no lleva scripts" );
  }

  public void pruebaNombres() {
    igual( "T1", InformeServicio.carpetaSegura( "T1" ), "id corto tal cual" );
    String c = InformeServicio.carpetaSegura( "file:C:\\x\\03_ventas.ktr" );
    cierto( c.startsWith( "03_ventas.ktr-" ), "archivo con hash: " + c );
    falso( c.contains( ":" ) || c.contains( "\\" ), "sin caracteres invalidos: " + c );
    File d = new File( "C:\\salida" );
    igual( new File( d, "x.light.html" ), InformeServicio.archivo( d, "x", Formato.HTML_ESTATICO ), "liviano" );
    igual( new File( d, "x.md" ), InformeServicio.archivo( d, "x", Formato.MARKDOWN ), "md" );
    Tema t = new Tema();
    t.salida.agregarFecha = true;
    Informe inf = new Informe();
    inf.nombreDoc = "a:b";
    cierto( InformeServicio.nombreBase( inf, t ).matches( "a_b-\\d{8}-\\d{4}" ), "con fecha: " + InformeServicio
      .nombreBase( inf, t ) );
    Captura cap = new Captura();
    cap.archivo = "C:\\ej\\03.ktr";
    igual( new File( "C:\\ej\\informes" ), InformeServicio.carpetaPorDefecto( cap, new Tema() ), "carpeta del .ktr" );
    t.salida.carpeta = "D:\\informes";
    igual( new File( "D:\\informes" ), InformeServicio.carpetaPorDefecto( cap, t ), "carpeta del tema" );
    nulo( InformeServicio.carpetaPorDefecto( new Captura(), new Tema() ), "sin guardar ni tema: null" );
  }

  // ---------------------------------------------------------------- captura y lienzo

  public void pruebaCapturarArchivoSinDisplay() throws Exception {
    File ktr = EntornoKettle.ejemplo( "03" );
    Captura c = InformeServicio.capturarArchivo( ktr.getPath(), null );
    cierto( c.copia instanceof TransMeta, "TransMeta" );
    igual( Captura.TRANSFORMACION, c.tipo, "tipo" );
    igual( ktr.getAbsolutePath(), c.archivo, "archivo" );
    igual( "03_ventas_por_producto", c.nombre, "nombre" );
    cierto( c.docId.startsWith( "file:" ), "docId" );
    nulo( c.lienzoCrudo, "sin Display no hay lienzo" );
    falso( c.sinGuardar, "no tiene cambios" );
    Captura j = InformeServicio.capturarArchivo( EntornoKettle.ejemplo( "11" ).getPath(), null );
    cierto( j.copia instanceof JobMeta && j.esJob(), "JobMeta" );
    try {
      InformeServicio.capturarArchivo( new File( EntornoKettle.ejemplos(), "no-existe.ktr" ).getPath(), null );
      falla( "un archivo que no existe tiene que fallar" );
    } catch ( java.io.FileNotFoundException e ) {
      contiene( e.getMessage(), "no-existe.ktr", "mensaje" );
    }
  }

  public void pruebaCodificarLienzo() throws Exception {
    // ImageData son datos puros: se arma sin Display, como la deja ReportImagen.pintar
    ImageData img = new ImageData( 400, 300, 24, new PaletteData( 0xFF0000, 0xFF00, 0xFF ) );
    int blanco = 0xFFFFFF;
    for ( int y = 0; y < 300; y++ ) {
      for ( int x = 0; x < 400; x++ ) {
        img.setPixel( x, y, x >= 100 && x < 200 && y >= 50 && y < 150 ? 0x243B53 : blanco );
      }
    }
    Captura c = EntornoKettle.captura( "02" );
    c.lienzoCrudo = img;
    c.lienzo = new Imagen();
    c.lienzo.escala = 2f;
    InformeServicio.codificarLienzo( c );
    nulo( c.lienzoCrudo, "lienzoCrudo vaciado" );
    noNulo( c.lienzo.datos, "PNG" );
    igual( 100 + 2 * ReportImagen.MARGEN, c.lienzo.ancho, "recortado al dibujo mas el margen (ancho)" );
    igual( 100 + 2 * ReportImagen.MARGEN, c.lienzo.alto, "alto" );
    igual( 2f, c.lienzo.escala, "escala" );
    igual( Imagen.PNG, c.lienzo.mime, "mime" );
    // y el flujo la usa
    Informe inf = armar( c, pedido( "es" ), 300 );
    Diagrama d = (Diagrama) inf.seccion( SeccionId.FLUJO ).bloques.get( 0 );
    noNulo( d.imagen, "imagen del lienzo en el diagrama" );
    cierto( Arrays.equals( c.lienzo.datos, d.imagen.datos ), "mismos bytes" );
    igual( "flow-lienzo", d.imagen.id, "id" );
  }

  public void pruebaLienzoConDisplay() throws Exception {
    final Display[] d = new Display[ 1 ];
    final Throwable[] error = new Throwable[ 1 ];
    final boolean[] propsPropios = { false };
    final CountDownLatch listo = new CountDownLatch( 1 );
    final AtomicBoolean fin = new AtomicBoolean();
    Thread ui = new Thread( new Runnable() {
      @Override
      public void run() {
        try {
          d[ 0 ] = new Display();
          if ( !PropsUI.isInitialized() ) {
            PropsUI.init( d[ 0 ], Props.TYPE_PROPERTIES_SPOON );
            propsPropios[ 0 ] = true;
          }
        } catch ( Throwable t ) {
          error[ 0 ] = t;
        }
        listo.countDown();
        if ( d[ 0 ] == null ) {
          return;
        }
        try {
          while ( !fin.get() ) {
            if ( !d[ 0 ].readAndDispatch() ) {
              d[ 0 ].sleep();
            }
          }
        } finally {
          d[ 0 ].dispose();
        }
      }
    }, "prueba-wp2-ui" );
    ui.setDaemon( true );
    ui.start();
    cierto( listo.await( 30, TimeUnit.SECONDS ), "el hilo de UI arranco" );
    if ( d[ 0 ] == null || error[ 0 ] != null ) {
      saltear( "no se pudo crear un Display SWT: " + error[ 0 ] );
    }
    try {
      if ( !propsPropios[ 0 ] ) {
        saltear( "PropsUI ya lo inicio otra prueba con su propio Display: no se prueba el pintado aca" );
      }
      Captura c = InformeServicio.capturarArchivo( EntornoKettle.ejemplo( "05" ).getPath(), d[ 0 ] );
      noNulo( c.lienzoCrudo, "lienzo pintado en la UI" );
      igual( 2f, c.lienzo.escala, "escala 2" );
      Informe inf = armar( c, pedido( "es" ), 300 );
      Diagrama dg = (Diagrama) inf.seccion( SeccionId.FLUJO ).bloques.get( 0 );
      noNulo( dg.imagen, "imagen en el diagrama" );
      cierto( dg.imagen.ancho > 200 && dg.imagen.alto > 100, "tamano: " + dg.imagen.ancho + "x" + dg.imagen.alto );
      File png = new File( EntornoKettle.salida( "informes-wp2" ), "05_lienzo.png" );
      Files.write( png.toPath(), dg.imagen.datos );
      System.out.println( "  lienzo de 05: " + dg.imagen.ancho + "x" + dg.imagen.alto + " -> " + png );

      Captura j = InformeServicio.capturarArchivo( EntornoKettle.ejemplo( "10" ).getPath(), d[ 0 ] );
      noNulo( j.lienzoCrudo, "lienzo del job" );
    } finally {
      fin.set( true );
      if ( !d[ 0 ].isDisposed() ) {
        d[ 0 ].wake();
      }
      ui.join( 10000 );
    }
  }
}
