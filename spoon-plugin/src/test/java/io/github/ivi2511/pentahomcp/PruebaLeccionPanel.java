package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.trans.TransGraph;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.Leccion.Estado;

/**
 * Modo clase, WP4: la pestana «Leccion» ({@link LeccionPanel}) sin Spoon, con el motor sin Spoon
 * ({@link LeccionMotor.SinSpoon}) y un CTabFolder en lugar de TransGraph.extraViewTabFolder. Siguiente ▶ avanza el
 * hilo de la leccion y la pestana muestra el paso hecho, el texto y las filas; Pausa/Reanudar, Atras, A-/A+, el
 * panel oculto (pausa panel_hidden y reanuda al reponer la pestana), Terminar, el ritmo manual, el reemplazo de la
 * pestana por otra leccion y el extensor loadTab.
 * <p>
 * Como PruebaPanelSwt: la ventana se abre fuera de la pantalla, sin activarse, y la captura sale con
 * {@code Control.print(GC)}; con {@code -Dpruebas.ventanas.visibles=true} se ve.
 *
 * @dueño clase
 */
public class PruebaLeccionPanel {

  private static final Pattern STEP = Pattern.compile( "<step>(.*?)</step>", Pattern.DOTALL );
  private static final long ESPERA_MS = 90000L;

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell host;
  private CTabFolder carpeta;
  private File capturas;
  private LeccionPanel.Confirmador confirmadorAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    capturas = EntornoKettle.salida( "leccion-panel" );
    confirmadorAntes = LeccionPanel.confirmador;
    final CountDownLatch listo = new CountDownLatch( 1 );
    hiloUi = new Thread( () -> {
      display = new Display();
      listo.countDown();
      while ( !parar ) {
        try {
          if ( !display.readAndDispatch() ) {
            display.sleep();
          }
        } catch ( Throwable e ) {
          e.printStackTrace( System.out );
        }
      }
      display.dispose();
    }, "ui-prueba-leccion" );
    hiloUi.setDaemon( true );
    hiloUi.start();
    cierto( listo.await( 30, TimeUnit.SECONDS ), "el hilo de UI arranco" );
    ui( () -> {
      host = new Shell( display, SWT.NO_TRIM | ( PruebaPanelSwt.VISIBLES ? SWT.ON_TOP : SWT.TOOL ) );
      host.setLayout( new FillLayout() );
      carpeta = new CTabFolder( host, SWT.BORDER );
      CTabItem log = new CTabItem( carpeta, SWT.NONE ); // como Logging, Metricas, Preview data
      log.setText( "Logging" );
      log.setControl( new Label( carpeta, SWT.NONE ) );
      host.setBounds( PruebaPanelSwt.X, PruebaPanelSwt.Y, 1100, 340 );
      PruebaPanelSwt.mostrar( host );
      return null;
    } );
  }

  public void cerrar() {
    LeccionPanel.confirmador = confirmadorAntes;
    LeccionPanel.tamFuente = 0;
    LeccionMotor.olvidarTodas();
    try {
      ui( () -> {
        if ( host != null && !host.isDisposed() ) {
          host.dispose();
        }
        return null;
      } );
    } catch ( Throwable ignorada ) {
      // seguir cerrando
    }
    parar = true;
    if ( display != null && !display.isDisposed() ) {
      display.wake();
    }
    try {
      hiloUi.join( 10000 );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------- pruebas

  /** Guided: la pestana, Siguiente ▶ con sus filas, A-/A+, Pausa/Reanudar, Atras, panel oculto y Terminar. */
  public void pruebaA_PestanaGuiada() throws Exception {
    LeccionMotor.olvidarTodas();
    LeccionMotor.SinSpoon ent = new LeccionMotor.SinSpoon( 0 );
    TransMeta tm = nueva( "panel_l1" );
    final Leccion l = LeccionMotor.empezar( ent, guionL1( "guided" ), tm );
    final LeccionPanel p = ui( () -> LeccionPanel.agregar( carpeta, l, ent ) );
    ui( () -> {
      igual( 2, carpeta.getItemCount(), "Logging + Leccion" );
      igual( "Lección", p.item.getText(), "pestana en el idioma de la leccion" );
      cierto( LeccionPanel.agregar( carpeta, l, ent ) == p, "agregar es idempotente" );
      igual( 2, carpeta.getItemCount(), "sigue una sola pestana Leccion" );
      carpeta.setSelection( p.item );
      igual( 5, p.pasos.getItemCount(), "5 pasos en la lista" );
      cierto( p.pasos.getItem( 0 ).getText().startsWith( LeccionPanel.AHORA ), "el paso 1 es el siguiente: "
        + p.pasos.getItem( 0 ).getText() );
      contiene( p.titulo.getText(), "Primer ETL: filtrar clientes · principiante · paso 0 de 5", "cabecera" );
      contiene( p.estado.getText(), "esperando «Siguiente»", "estado" );
      contiene( p.ahora.getText(), "Lección · Primer ETL", "en el paso 0 se ve la nota del titulo" );
      contiene( p.ahora.getText(), "Siguiente: 1 · Leer clientes", "y lo que viene" );
      cierto( p.siguiente.getEnabled(), "Siguiente habilitado" );
      falso( p.atras.getEnabled(), "Atras deshabilitado en el paso 0" );
      falso( p.reiniciar.getEnabled(), "Reiniciar deshabilitado en el paso 0" );
      igual( "Pausa", p.pausa.getText(), "boton Pausa" );
      return null;
    } );

    // Siguiente ▶: el hilo de la leccion avanza y la pestana lo muestra
    clic( p.siguiente );
    esperar( () -> estadoEs( l, Estado.WAITING ) && posicion( l ) == 1, "paso 1 jugado" );
    esperarUi( () -> p.pasos.getItem( 0 ).getText().startsWith( LeccionPanel.HECHO ), p, "paso 1 marcado hecho" );
    ui( () -> {
      cierto( p.pasos.getItem( 1 ).getText().startsWith( LeccionPanel.AHORA ), "el 2 es el siguiente" );
      contiene( p.titulo.getText(), "paso 1 de 5", "cabecera" );
      contiene( p.ahora.getText(), "1 · Leer clientes", "nota del paso" );
      contiene( p.ahora.getText(), "Qué:", "con sus etiquetas" );
      contiene( p.ahora.getText(), "D3", "y el ¡Ojo! automatico completo" );
      contiene( p.ahora.getText(), "Siguiente: 2 · Activos con saldo", "lo que viene" );
      igual( 5, p.filas.getItemCount(), "5 filas de la vista previa" );
      cierto( p.filas.getColumnCount() >= 3, "una columna por campo: " + p.filas.getColumnCount() );
      contiene( p.grupoFilas.getText(), "Filas de «Leer clientes» (5)", "titulo de las filas" );
      cierto( p.atras.getEnabled(), "Atras habilitado" );
      return null;
    } );
    igual( 1, l.clics, "un clic contado" );
    pantalla( "pestana_paso1.png" );

    // A-/A+ (letra de la pestana)
    int antes = ui( () -> p.alturaFuente() );
    ui( () -> {
      p.mas.notifyListeners( SWT.Selection, new Event() );
      p.mas.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    igual( antes + 2, ui( () -> p.alturaFuente() ), "A+ dos veces" );
    ui( () -> {
      p.menos.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    igual( antes + 1, ui( () -> p.alturaFuente() ), "A- una vez" );
    pantalla( "pestana_letra_grande.png" );

    // Pausa / Reanudar
    clic( p.pausa );
    esperar( () -> estadoEs( l, Estado.PAUSED ), "pausada" );
    esperarUi( () -> "Reanudar".equals( p.pausa.getText() ), p, "el boton dice Reanudar" );
    ui( () -> {
      falso( p.siguiente.getEnabled(), "Siguiente deshabilitado en pausa" );
      contiene( p.estado.getText(), "en pausa", "estado" );
      return null;
    } );
    clic( p.pausa );
    esperar( () -> estadoEs( l, Estado.WAITING ), "reanudada" );
    esperarUi( () -> "Pausa".equals( p.pausa.getText() ) && p.siguiente.getEnabled(), p, "vuelve Pausa" );

    // Atras: un Ctrl+Z del paso 1
    clic( p.atras );
    esperar( () -> posicion( l ) == 0, "atras al paso 0" );
    igual( 0, tm.nrSteps(), "el step se deshizo" );
    esperarUi( () -> p.pasos.getItem( 0 ).getText().startsWith( LeccionPanel.AHORA ), p, "paso 1 otra vez pendiente" );

    // el usuario oculta el panel de resultados: Spoon desecha la pestana -> pausa panel_hidden
    ui( () -> {
      p.item.dispose();
      return null;
    } );
    cierto( estadoEs( l, Estado.PAUSED ), "pausada al ocultar el panel" );
    igual( "panel_hidden", motivo( l ), "motivo panel_hidden" );
    contiene( LeccionMotor.estadoJson( l, false ).toString(), "\"reason\":\"panel_hidden\"", "lesson_status lo dice" );
    nulo( ui( () -> LeccionPanel.de( carpeta ) ), "sin pestana" );
    // loadTab la repone y reanuda la pausa que puso el panel
    final LeccionPanel p2 = ui( () -> LeccionPanel.agregar( carpeta, l, ent ) );
    cierto( p2 != p, "pestana nueva" );
    cierto( estadoEs( l, Estado.WAITING ), "reanudada al reponer la pestana" );
    nulo( motivo( l ), "sin motivo" );
    // una pausa pedida no se reanuda sola
    LeccionMotor.controlar( ent, l, "pause", null );
    ui( () -> LeccionPanel.agregar( carpeta, l, ent ) );
    cierto( estadoEs( l, Estado.PAUSED ), "la pausa pedida sigue" );
    LeccionMotor.controlar( ent, l, "resume", null );

    // Terminar (con confirmacion)
    LeccionPanel.confirmador = ( padre, t, x ) -> false;
    clic( p2.terminar );
    Thread.sleep( 300 );
    cierto( estadoEs( l, Estado.WAITING ), "sin confirmar no termina" );
    LeccionPanel.confirmador = ( padre, t, x ) -> true;
    clic( p2.terminar );
    esperar( () -> estadoEs( l, Estado.STOPPED ), "terminada" );
    esperarUi( () -> !p2.terminar.getEnabled(), p2, "Terminar deshabilitado" );
    ui( () -> {
      falso( p2.siguiente.getEnabled(), "sin Siguiente" );
      falso( p2.pausa.getEnabled(), "sin Pausa" );
      contiene( p2.estado.getText(), "detenida", "estado" );
      cierto( p2.mas.getEnabled(), "A+ sigue" );
      return null;
    } );
    pantalla( "pestana_terminada.png" );
    ui( () -> {
      p2.item.dispose();
      return null;
    } );
    LeccionPanel.tamFuente = 0;
  }

  /** Manual: Siguiente deshabilitado, lesson_next se ve en la pestana; otra leccion reemplaza la pestana sin pausar. */
  public void pruebaB_ManualYReemplazo() throws Exception {
    LeccionMotor.olvidarTodas();
    LeccionMotor.SinSpoon ent = new LeccionMotor.SinSpoon( 0 );
    final Leccion g = LeccionMotor.empezar( ent, guionL1( "guided" ).put( "language", "en" ), nueva( "panel_g" ) );
    final Leccion m = LeccionMotor.empezar( ent, guionL1( "manual" ), nueva( "panel_m" ) );
    final LeccionPanel pg = ui( () -> LeccionPanel.agregar( carpeta, g, ent ) );
    ui( () -> {
      igual( "Lesson", pg.item.getText(), "en ingles" );
      contiene( pg.titulo.getText(), "beginner · step 0 of 5", "cabecera en ingles" );
      return null;
    } );
    final LeccionPanel pm = ui( () -> LeccionPanel.agregar( carpeta, m, ent ) );
    ui( () -> {
      cierto( pg.item.isDisposed(), "la pestana de la otra leccion se reemplaza" );
      igual( 2, carpeta.getItemCount(), "una sola pestana Leccion" );
      cierto( LeccionPanel.de( carpeta ) == pm, "la nueva" );
      return null;
    } );
    cierto( estadoEs( g, Estado.WAITING ), "reemplazar no pausa la otra leccion" );
    ui( () -> {
      falso( pm.siguiente.getEnabled(), "manual: sin Siguiente" );
      contiene( pm.siguiente.getToolTipText(), "lesson_next", "dice como avanza" );
      return null;
    } );
    LeccionMotor.avanzar( ent, m, false );
    esperarUi( () -> pm.pasos.getItem( 0 ).getText().startsWith( LeccionPanel.HECHO ), pm, "lesson_next se ve" );
    ui( () -> {
      falso( pm.siguiente.getEnabled(), "sigue sin Siguiente" );
      igual( 5, pm.filas.getItemCount(), "las filas del paso 1" );
      return null;
    } );
    // el panel oculto no pausa una leccion manual
    ui( () -> {
      pm.item.dispose();
      return null;
    } );
    cierto( estadoEs( m, Estado.WAITING ), "manual: ocultar el panel no la pausa" );
  }

  /** El extensor loadTab: responde a TransGraph/loadTab y no rompe nada con otro sujeto. */
  public void pruebaC_Extensor() throws Exception {
    LeccionTabExtender x = new LeccionTabExtender();
    Map<Class<?>, Set<String>> r = x.respondsTo();
    igual( 1, r.size(), "una clase" );
    noNulo( r.get( TransGraph.class ), "TransGraph" );
    cierto( r.get( TransGraph.class ).contains( TransGraph.LOAD_TAB ), "evento loadTab" );
    igual( "loadTab", TransGraph.LOAD_TAB, "el evento de addAllTabs" );
    x.uiEvent( "otro", TransGraph.LOAD_TAB );
    x.uiEvent( null, null );
    noNulo( LeccionTabExtender.class.getAnnotation( org.pentaho.di.ui.spoon.SpoonUiExtenderPlugin.class ),
      "anotado para el escaneo de plugins" );
    igual( "", LeccionTabExtender.class.getAnnotation( org.pentaho.di.ui.spoon.SpoonUiExtenderPlugin.class )
      .classLoaderGroup(), "sin classLoaderGroup (mismo classloader que el LifecyclePlugin)" );
  }

  // ---------------------------------------------------------------- guion (el de PruebaLeccionMotor)

  private static String config( String ejemplo, String step ) throws Exception {
    String t = new String( Files.readAllBytes( EntornoKettle.ejemplo( ejemplo ).toPath() ), StandardCharsets.UTF_8 );
    Matcher mm = STEP.matcher( t );
    while ( mm.find() ) {
      if ( mm.group( 1 ).contains( "<name>" + step + "</name>" ) ) {
        return mm.group( 1 );
      }
    }
    throw new IllegalStateException( "no step " + step );
  }

  private static TransMeta nueva( String nombre ) throws Exception {
    EntornoKettle.iniciar();
    File dir = EntornoKettle.ejemplos();
    TransMeta tm = new TransMeta();
    tm.setName( nombre );
    tm.setFilename( new File( dir, nombre + ".ktr" ).getAbsolutePath() );
    tm.setInternalKettleVariables();
    return tm;
  }

  private static ObjectNode paso( String type, String name, String after, String what, String why ) throws Exception {
    ObjectNode p = Tools.obj().put( "type", type ).put( "name", name ).put( "config_xml", config( "02", name ) );
    if ( after != null ) {
      p.put( "after", after );
    }
    return p.put( "what", what ).put( "why", why );
  }

  private static ObjectNode guionL1( String ritmo ) throws Exception {
    ObjectNode a = Tools.obj().put( "title", "Primer ETL: filtrar clientes" ).put( "level", "beginner" )
      .put( "language", "es" ).put( "pace", ritmo ).put( "intro", "Leemos un CSV, filtramos y escribimos." )
      .put( "outro", "Ejercicio: cambia el saldo mínimo y predice cuántas filas salen." );
    ArrayNode s = a.putArray( "steps" );
    s.add( paso( "CsvInput", "Leer clientes", null, "lee datos/clientes.csv", "es la entrada del flujo" )
      .put( "watch_out", "auto" ).put( "preview_rows", 5 ) );
    s.add( paso( "FilterRows", "Activos con saldo", "Leer clientes", "deja pasar los activos con saldo",
      "separa dos ramas" ).put( "tip", "send_true_to necesita su hop" ) );
    s.add( paso( "SelectValues", "Elegir columnas", "Activos con saldo", "elige y renombra columnas",
      "la salida solo lleva lo útil" ) );
    s.add( paso( "TextFileOutput", "Escribir filtrados", "Elegir columnas", "escribe el CSV de salida",
      "es el resultado" ) );
    s.add( paso( "Dummy", "Descartados", "Activos con saldo", "recibe la rama falsa", "para verla en las métricas" ) );
    return a;
  }

  // ---------------------------------------------------------------- ayudas

  private static boolean estadoEs( Leccion l, Estado e ) {
    synchronized ( l ) {
      return l.estado == e;
    }
  }

  private static int posicion( Leccion l ) {
    synchronized ( l ) {
      return l.posicion;
    }
  }

  private static String motivo( Leccion l ) {
    synchronized ( l ) {
      return l.motivo;
    }
  }

  private void clic( final Button b ) throws Exception {
    ui( () -> {
      cierto( b.getEnabled(), "'" + b.getText() + "' habilitado para el clic" );
      b.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  private static void esperar( BooleanSupplier c, String que ) throws Exception {
    long fin = System.currentTimeMillis() + ESPERA_MS;
    while ( !c.getAsBoolean() ) {
      if ( System.currentTimeMillis() > fin ) {
        Verificador.falla( "no paso a tiempo: " + que );
      }
      Thread.sleep( 50 );
    }
  }

  /** Espera a que la pestana (refresco por timerExec) muestre algo. */
  private void esperarUi( final BooleanSupplier c, final LeccionPanel p, String que ) throws Exception {
    long fin = System.currentTimeMillis() + 15000L;
    while ( !ui( () -> !p.raiz.isDisposed() && c.getAsBoolean() ) ) {
      if ( System.currentTimeMillis() > fin ) {
        Verificador.falla( "la pestana no lo mostro: " + que );
      }
      Thread.sleep( 50 );
    }
  }

  private <T> T ui( final Callable<T> c ) throws Exception {
    final Object[] r = new Object[ 1 ];
    final Throwable[] e = new Throwable[ 1 ];
    display.syncExec( () -> {
      try {
        r[ 0 ] = c.call();
      } catch ( Throwable t ) {
        e[ 0 ] = t;
      }
    } );
    if ( e[ 0 ] instanceof Exception ) {
      throw (Exception) e[ 0 ];
    }
    if ( e[ 0 ] instanceof Error ) {
      throw (Error) e[ 0 ];
    }
    @SuppressWarnings( "unchecked" )
    T t = (T) r[ 0 ];
    return t;
  }

  /** Captura fuera de la pantalla: cada hijo de la Shell con print(GC). */
  private void pantalla( final String nombre ) throws Exception {
    Thread.sleep( 400 );
    ui( () -> {
      host.layout( true, true );
      Rectangle b = host.getBounds();
      Image img = new Image( display, b.width, b.height );
      GC gc = new GC( img );
      try {
        for ( Control hijo : host.getChildren() ) {
          Rectangle h = hijo.getBounds();
          Image parte = new Image( display, Math.max( 1, h.width ), Math.max( 1, h.height ) );
          GC gp = new GC( parte );
          try {
            if ( hijo.print( gp ) ) {
              gc.drawImage( parte, h.x, h.y );
            }
          } finally {
            gp.dispose();
            parte.dispose();
          }
        }
      } finally {
        gc.dispose();
      }
      ImageLoader il = new ImageLoader();
      il.data = new ImageData[] { img.getImageData() };
      File f = new File( capturas, nombre );
      il.save( f.getPath(), SWT.IMAGE_PNG );
      img.dispose();
      System.out.println( "  captura: " + f );
      return null;
    } );
  }
}
