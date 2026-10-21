package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;
import org.pentaho.di.i18n.LanguageChoice;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Disponibilidad;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * El panel de Informes en una Shell, con un servicio falso (diseno §8.2): la perspectiva crea su UI como
 * en Spoon (re-cuelgue en una tarjeta de un deck), arma sola la vista liviana, la vista completa, exporta,
 * cambia a un job, cancela, mide, aplica show_report, y se guardan capturas de pantalla en
 * {@code <prueba>\salida\panel\}. Abre una ventana real durante unos segundos (IE11 embebido incluido).
 * <p>
 * El SWT corre en un hilo propio ({@code ui-prueba}) con su bucle de eventos; los metodos de prueba le
 * mandan trabajo con syncExec y esperan, como hacen los workers del panel.
 *
 * @dueño WP1
 */
public class PruebaPanelSwt {

  static final List<String> DEFAULTS = Arrays.asList( SeccionId.PORTADA, SeccionId.RESUMEN, SeccionId.FLUJO,
    SeccionId.FLUJO_JOB, SeccionId.LINAJE, SeccionId.DICCIONARIO, SeccionId.DESTINO, SeccionId.VALIDACION,
    SeccionId.CONEXIONES );

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell spoonShell;
  private Shell host;
  private Composite tarjeta;
  private ReportsPerspective perspectiva;
  private ServicioFalso servicio;
  private File dir;
  private File capturas;
  private Rotulos r;
  private Locale localeAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    dir = new File( EntornoKettle.carpeta(), "panel" );
    EntornoKettle.borrar( dir.toPath() );
    dir.mkdirs();
    capturas = EntornoKettle.salida( "panel" );
    r = Rotulos.de( PanelInformes.AREA, "es" );
    localeAntes = LanguageChoice.getInstance().getDefaultLocale();
    LanguageChoice.getInstance().setDefaultLocale( new Locale( "es", "ES" ) ); // la UI de "Spoon" en castellano

    servicio = new ServicioFalso( dir );
    Captura t = EntornoKettle.captura( "03" );
    t.docId = "T1";
    Captura j = EntornoKettle.captura( "10" );
    j.docId = "J1";
    servicio.capturas.put( "T1", t );
    servicio.capturas.put( "J1", j );
    servicio.docs.add( new PanelInformes.Documento( "T1", t.nombre, Captura.TRANSFORMACION, t.archivo, true,
      false ) );
    servicio.docs.add( new PanelInformes.Documento( "J1", j.nombre, Captura.JOB, j.archivo, false, false ) );

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
    }, "ui-prueba" );
    hiloUi.setDaemon( true );
    hiloUi.start();
    cierto( listo.await( 30, TimeUnit.SECONDS ), "el hilo de UI arranco" );

    ui( () -> {
      // como Spoon: la Shell de Spoon (nunca abierta) es el padre de getUI(), y el deck re-cuelga el Composite
      spoonShell = new Shell( display );
      ReportsPerspective.padreSinSpoon = spoonShell;
      ReportsPerspective.servicioPrueba = servicio;
      host = new Shell( display, SWT.NO_TRIM | SWT.ON_TOP );
      host.setLayout( new FillLayout() );
      Composite deck = new Composite( host, SWT.NONE );
      StackLayout pila = new StackLayout();
      deck.setLayout( pila );
      tarjeta = new Composite( deck, SWT.NONE );
      tarjeta.setLayout( new GridLayout() );
      pila.topControl = tarjeta;
      host.setBounds( 40, 40, 1200, 760 );
      return null;
    } );
  }

  public void cerrar() {
    try {
      ui( () -> {
        if ( host != null && !host.isDisposed() ) {
          host.dispose();
        }
        if ( spoonShell != null && !spoonShell.isDisposed() ) {
          spoonShell.dispose();
        }
        return null;
      } );
    } catch ( Throwable ignorada ) {
      // seguir cerrando
    }
    ReportsPerspective.padreSinSpoon = null;
    ReportsPerspective.servicioPrueba = null;
    if ( localeAntes != null ) {
      LanguageChoice.getInstance().setDefaultLocale( localeAntes );
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

  // ---------------------------------------------------------------- pruebas (en orden alfabetico)

  /** getUI + setParent como el deck de Spoon, setActive(true) y la vista liviana armada sola. */
  public void pruebaA_PerspectivaYVistaLiviana() throws Exception {
    perspectiva = new ReportsPerspective();
    ui( () -> {
      Composite raiz = perspectiva.getUI();
      noNulo( raiz, "getUI" );
      cierto( raiz.getParent() == spoonShell, "getUI crea sobre la Shell de Spoon" );
      cierto( raiz.setParent( tarjeta ), "re-cuelgue (setParent) a la tarjeta del deck" );
      tarjeta.layout( true, true );
      host.open();
      host.layout( true, true );
      noNulo( perspectiva.panel(), "el panel se creo" );
      perspectiva.setActive( true );
      return null;
    } );
    esperar( "vista liviana con pre-render", () -> servicio.vistas.size() >= 2 && !perspectiva.panel().ocupado()
      && r.s( "status_light_done" ).equals( perspectiva.panel().estado() ), 30000 );

    Pedido p = servicio.pedidos.get( 0 );
    igual( Arrays.asList( SeccionId.PORTADA, SeccionId.RESUMEN, SeccionId.FLUJO, SeccionId.CONEXIONES ),
      p.secciones, "secciones de la vista liviana" );
    igual( Pedido.METRICAS_NINGUNA, p.metricas, "la liviana no mide" );
    falso( p.destinoConsultarBase, "la liviana no consulta la base" );
    igual( "es", p.idioma, "idioma del informe = el de Spoon" );
    igual( Arrays.asList( "false", "true" ), servicio.vistasCopia(), "primero sin pre-render y despues con" );

    String tipo = ui( () -> ( (Browser) buscar( "navegador" ) ).getBrowserType() );
    igual( "ie", tipo, "el SWT de PDI trae IE11" );
    esperarHtml( "prerender" );
    String modo = ui( () -> String.valueOf( ( (Browser) buscar( "navegador" ) ).evaluate(
      "return String(document.documentMode);" ) ) );
    igual( "11", modo, "documentMode 11 con X-UA-Compatible" );
    falso( ui( () -> buscar( "banda" ).getVisible() ), "con navegador no hay banda" );
    igual( "T1", ui( () -> ( (Combo) buscar( "documento" ) ).getSelectionIndex() == 0 ? "T1" : "?" ),
      "eligio la pestana activa de Data Integration" );
    archivo( pantalla( "panel-liviana.png" ), 5000, "captura de la vista liviana" );
  }

  /** Vista previa (boton): todas las marcadas, botones deshabilitados mientras corre. */
  public void pruebaB_VistaCompleta() throws Exception {
    servicio.demoraMs = 800;
    int antes = servicio.pedidos.size();
    clic( "vista_previa" );
    esperar( "arranca la tarea", () -> perspectiva.panel().ocupado(), 5000 );
    falso( ui( () -> buscar( "vista_previa" ).isEnabled() || buscar( "exportar" ).isEnabled() ),
      "Vista previa y Exportar deshabilitados mientras corre" );
    cierto( ui( () -> buscar( "progreso" ).getVisible() && buscar( "cancelar" ).getVisible() ),
      "barra de progreso y Cancelar visibles" );
    esperar( "termina", () -> !perspectiva.panel().ocupado() && servicio.pedidos.size() > antes && perspectiva
      .panel().estado().equals( r.f( "status_preview_done", 8 ) ), 30000 );
    servicio.demoraMs = 150;
    Pedido p = servicio.pedidos.get( servicio.pedidos.size() - 1 );
    igual( Arrays.asList( SeccionId.PORTADA, SeccionId.RESUMEN, SeccionId.FLUJO, SeccionId.LINAJE,
      SeccionId.DICCIONARIO, SeccionId.DESTINO, SeccionId.VALIDACION, SeccionId.CONEXIONES ), p.secciones,
      "las marcadas por defecto que aplican a una transformacion" );
    igual( Pedido.METRICAS_ULTIMA, p.metricas, "metricas de la ultima corrida" );
    cierto( servicio.pasosRecibidos > 0, "el worker aviso pasos" );
    cierto( ui( () -> buscar( "vista_previa" ).isEnabled() && buscar( "exportar" ).isEnabled() ),
      "botones habilitados al terminar" );
    esperarHtml( "prerender" );
    Thread.sleep( 400 );
    igual( r.f( "status_preview_done", 8 ), ui( () -> perspectiva.panel().estado() ),
      "un aviso de progreso atrasado no pisa el estado final" );
    falso( ui( () -> barraScroll( false ) ), "a 1200 px la columna de opciones no necesita scroll horizontal" );
    archivo( pantalla( "panel-completa.png" ), 5000, "captura de la vista completa" );
  }

  /** Exportar reusa la vista completa (mismo documento y pedido) y habilita «Abrir carpeta». */
  public void pruebaC_Exportar() throws Exception {
    final File destino = new File( dir, "export" );
    int armadosAntes = servicio.pedidos.size();
    ui( () -> {
      ( (Text) buscar( "carpeta" ) ).setText( destino.getPath() );
      ( (Button) buscar( "fmt:md" ) ).setSelection( true );
      return null;
    } );
    clic( "exportar" );
    esperar( "exporta", () -> !perspectiva.panel().ocupado() && servicio.exportadosCopia().size() == 3, 30000 );
    igual( armadosAntes, servicio.pedidos.size(), "no rearma: reusa la vista completa" );
    List<String> exp = servicio.exportadosCopia();
    igual( "HTML|03_ventas_por_producto.html", exp.get( 0 ), "html" );
    igual( "PDF|03_ventas_por_producto.pdf", exp.get( 1 ), "pdf" );
    igual( "MARKDOWN|03_ventas_por_producto.md", exp.get( 2 ), "md" );
    archivo( new File( destino, "03_ventas_por_producto.md" ), 10, "el md quedo en la carpeta" );
    String estado = ui( () -> perspectiva.panel().estado() );
    contiene( estado, destino.getPath(), "el estado dice la carpeta" );
    contiene( estado, "3 archivos", "y cuantos archivos" );
    cierto( ui( () -> buscar( "abrir_carpeta" ).isEnabled() ), "Abrir carpeta habilitado" );
    clic( "abrir_carpeta" );
    esperar( "abre la carpeta", () -> servicio.abiertos.contains( destino ), 5000 );

    // «Abrir en el navegador»: HTML interactivo en temporales y el programa del sistema (falso)
    clic( "abrir_navegador" );
    esperar( "abre el navegador", () -> !perspectiva.panel().ocupado() && servicio.abiertos.size() >= 2, 15000 );
    File html = servicio.abiertos.get( servicio.abiertos.size() - 1 );
    igual( "informe.html", html.getName(), "html interactivo" );
    contiene( html.getPath(), new File( dir, "tmp" ).getPath(), "en temporales" );
    cierto( servicio.exportadosCopia().contains( "HTML|informe.html" ), "exporto HTML interactivo" );

    // «Guardar como predeterminado»: identidad, colores, formatos, carpeta e idioma
    ui( () -> {
      ( (Text) buscar( "autor" ) ).setText( "Iván Castaño" );
      return null;
    } );
    clic( "guardar_predeterminado" );
    noNulo( servicio.guardado, "guardo el tema" );
    igual( "Iván Castaño", servicio.guardado.identidad.autor, "autor editado" );
    igual( "Universidad Europea", servicio.guardado.identidad.institucion, "institucion" );
    igual( Arrays.asList( "html", "pdf", "md" ), servicio.guardado.salida.formatos, "formatos marcados" );
    igual( destino.getPath(), servicio.guardado.salida.carpeta, "carpeta" );
    igual( "es", servicio.guardado.idioma, "idioma del informe" );
    igual( r.s( "saved_default" ), ui( () -> perspectiva.panel().estado() ), "estado" );
  }

  /** Cambio a un job: job_flow en lugar de flow y sin linaje; despues Cancelar a mitad de camino. */
  public void pruebaD_JobYCancelar() throws Exception {
    int antes = servicio.pedidos.size();
    ui( () -> {
      Combo c = (Combo) buscar( "documento" );
      c.select( 1 );
      c.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    esperar( "vista liviana del job", () -> servicio.pedidos.size() > antes && !perspectiva.panel().ocupado(),
      30000 );
    Pedido p = servicio.pedidos.get( servicio.pedidos.size() - 1 );
    cierto( p.secciones.contains( SeccionId.FLUJO_JOB ) && !p.secciones.contains( SeccionId.FLUJO ),
      "job_flow y no flow: " + p.secciones );
    falso( ui( () -> buscar( "sec:" + SeccionId.FLUJO ).getVisible() ), "flow oculto en un job" );
    cierto( ui( () -> buscar( "sec:" + SeccionId.FLUJO_JOB ).getVisible() ), "job_flow visible" );
    falso( ui( () -> buscar( "sec:" + SeccionId.LINAJE ).isEnabled() ), "linaje deshabilitado en un job" );
    contiene( ui( () -> ( (Button) buscar( "sec:" + SeccionId.LINAJE ) ).getToolTipText() ), "transformaciones",
      "el tooltip explica por que" );

    servicio.demoraMs = 20000;
    servicio.vioCancelacion = false;
    clic( "vista_previa" );
    esperar( "corre", () -> perspectiva.panel().ocupado(), 5000 );
    Thread.sleep( 300 );
    clic( "cancelar" );
    igual( r.s( "status_cancelled" ), ui( () -> perspectiva.panel().estado() ), "estado Cancelado" );
    falso( ui( () -> perspectiva.panel().ocupado() ), "ya no esta ocupado" );
    cierto( ui( () -> buscar( "vista_previa" ).isEnabled() ), "Vista previa habilitado otra vez" );
    esperar( "el worker ve Progreso.cancelado()", () -> servicio.vioCancelacion, 5000 );
    servicio.demoraMs = 150;
    Thread.sleep( 300 );
    igual( r.s( "status_cancelled" ), ui( () -> perspectiva.panel().estado() ),
      "el resultado de la tarea cancelada se ignora" );
  }

  /** Sin Edge/Chrome: una sola pasada y la banda que avisa que los diagramas se ven al exportar. */
  public void pruebaE0_SinNavegador() throws Exception {
    servicio.navegador = false;
    try {
      int vistas = servicio.vistasCopia().size();
      ui( () -> {
        Combo c = (Combo) buscar( "documento" );
        c.select( 0 );
        c.notifyListeners( SWT.Selection, new Event() );
        return null;
      } );
      esperar( "liviana sin navegador", () -> servicio.vistasCopia().size() > vistas && !perspectiva.panel()
        .ocupado() && r.s( "status_light_done" ).equals( perspectiva.panel().estado() ), 30000 );
      Thread.sleep( 300 );
      List<String> nuevas = servicio.vistasCopia().subList( vistas, servicio.vistasCopia().size() );
      igual( Collections.singletonList( "false" ), nuevas, "sin navegador no hay segunda pasada" );
      cierto( ui( () -> buscar( "banda" ).getVisible() ), "la banda se ve" );
      igual( r.s( "band_diagrams" ), ui( () -> ( (Label) buscar( "banda" ) ).getText() ), "texto de la banda" );
      esperarHtml( "base" );
      archivo( pantalla( "panel-sin-navegador.png" ), 5000, "captura con la banda" );
    } finally {
      servicio.navegador = true;
    }
    // un job no muestra los controles de secciones que no aplican
    ui( () -> {
      Combo c = (Combo) buscar( "documento" );
      c.select( 1 );
      c.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    esperar( "liviana del job", () -> !buscar( "linaje_step" ).getParent().getVisible(), 10000 );
    falso( ui( () -> buscar( "perfil_step" ).getParent().getVisible() ), "sin step de perfil en un job" );
    cierto( ui( () -> buscar( "medir" ).getParent().getVisible() ), "Medir sigue a mano en un job" );
  }

  /** «Medir…»: confirma con los steps que escriben y arma con run_copy y metricas. */
  public void pruebaE_Medir() throws Exception {
    ui( () -> {
      Combo c = (Combo) buscar( "documento" );
      c.select( 0 );
      c.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    esperar( "liviana de la trans", () -> !perspectiva.panel().ocupado() && r.s( "status_light_done" ).equals(
      perspectiva.panel().estado() ), 30000 );
    int antes = servicio.pedidos.size();
    clic( "medir" );
    esperar( "midio", () -> servicio.pedidos.size() > antes && !perspectiva.panel().ocupado(), 30000 );
    igual( 1, servicio.confirmaciones.size(), "pidio confirmacion" );
    contiene( servicio.confirmaciones.get( 0 ), "Escribir resumen", "con los steps que escriben" );
    Pedido p = servicio.pedidos.get( servicio.pedidos.size() - 1 );
    igual( Pedido.METRICAS_COPIA, p.metricas, "run_copy" );
    cierto( p.secciones.contains( SeccionId.METRICAS ), "con la seccion metrics" );

    // sin confirmar no corre nada
    servicio.confirmar = false;
    int n = servicio.pedidos.size();
    clic( "medir" );
    Thread.sleep( 500 );
    igual( n, servicio.pedidos.size(), "cancelada la confirmacion, no mide" );

    // «consultar la base» avisa que se va a conectar; si se cancela, queda sin marcar
    int conf = servicio.confirmaciones.size();
    ui( () -> {
      Button b = (Button) buscar( "consultar_base" );
      b.setSelection( true );
      b.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    igual( conf + 1, servicio.confirmaciones.size(), "pregunto antes de conectarse" );
    noContiene( servicio.confirmaciones.get( conf ), "%n", "la confirmacion con saltos de linea, sin «%n» literal" );
    falso( ui( () -> ( (Button) buscar( "consultar_base" ) ).getSelection() ), "sin confirmar queda desmarcado" );
    servicio.confirmar = true;
    ui( () -> {
      Button b = (Button) buscar( "consultar_base" );
      b.setSelection( true );
      b.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    cierto( ui( () -> ( (Button) buscar( "consultar_base" ) ).getSelection() ), "confirmado queda marcado" );
    int antesBase = servicio.pedidos.size();
    clic( "vista_previa" );
    esperar( "vista con la base", () -> servicio.pedidos.size() > antesBase && !perspectiva.panel().ocupado(),
      30000 );
    cierto( servicio.pedidos.get( servicio.pedidos.size() - 1 ).destinoConsultarBase, "el pedido consulta la base" );
    igual( "Iván Castaño", servicio.ultimoTema.identidad.autor, "armar recibe el tema editado en el panel" );
    ui( () -> {
      ( (Button) buscar( "consultar_base" ) ).setSelection( false );
      return null;
    } );
  }

  /** «Abrir archivo…» sin el dialogo: carga en el worker, entra al combo y arma la vista liviana. */
  public void pruebaE1_AbrirArchivo() throws Exception {
    final File ktr = EntornoKettle.ejemplo( "02" );
    final java.lang.reflect.Method cargar = PanelInformes.class.getDeclaredMethod( "cargarArchivo", String.class );
    cargar.setAccessible( true );
    int antes = servicio.pedidos.size();
    ui( () -> cargar.invoke( perspectiva.panel(), ktr.getPath() ) );
    esperar( "cargo el archivo", () -> servicio.pedidos.size() > antes && !perspectiva.panel().ocupado() && r.s(
      "status_light_done" ).equals( perspectiva.panel().estado() ), 30000 );
    igual( 1, servicio.archivosPedidos.size(), "capturarArchivo una vez" );
    falso( servicio.archivoEnUi, "capturarArchivo corre fuera de la UI" );
    String item = ui( () -> ( (Combo) buscar( "documento" ) ).getText() );
    contiene( item, "02_filtrar_clientes", "el combo muestra el archivo" );
    contiene( item, "archivo", "marcado como archivo" );
    igual( 3, ui( () -> ( (Combo) buscar( "documento" ) ).getItemCount() ), "dos pestanas y un archivo" );
    igual( Captura.TRANSFORMACION, servicio.ultimoInformeTipo, "armo la transformacion cargada" );
  }

  /** show_report: PanelInformes.mostrar desde otro hilo con secciones, idioma y narrativa. */
  public void pruebaF_Mostrar() throws Exception {
    int antes = servicio.pedidos.size();
    Pedido p = new Pedido();
    p.secciones = new ArrayList<String>( Arrays.asList( SeccionId.PORTADA, SeccionId.METRICAS,
      SeccionId.NARRATIVA ) );
    p.idioma = "pt";
    p.narrativa = new Narrativa();
    p.narrativa.conclusiones = "Conclusão & teste";
    p.narrativa.resumen = "Resumo da IA";
    p.narrativa.origen = Narrativa.IA;
    PanelInformes.mostrar( "J1", p ); // no es el hilo de UI: pasa por asyncExec
    esperar( "aplica el pedido", () -> servicio.pedidos.size() > antes && !perspectiva.panel().ocupado(), 30000 );
    Pedido hecho = servicio.pedidos.get( servicio.pedidos.size() - 1 );
    igual( Arrays.asList( SeccionId.PORTADA, SeccionId.METRICAS, SeccionId.NARRATIVA ), hecho.secciones,
      "las secciones pedidas" );
    igual( "pt", hecho.idioma, "idioma pedido" );
    noNulo( hecho.narrativa, "narrativa" );
    igual( "Conclusão & teste", hecho.narrativa.conclusiones, "conclusiones" );
    igual( "Resumo da IA", hecho.narrativa.resumen, "resumen" );
    igual( Narrativa.IA, hecho.narrativa.origen, "origen ia" );
    igual( "J1", servicio.ultimaCaptura, "el documento pedido" );
    igual( "Português", ui( () -> ( (Combo) buscar( "idioma" ) ).getText() ), "combo de idioma" );
    igual( "Conclusão & teste", ui( () -> ( (Text) buscar( "conclusiones" ) ).getText() ), "texto visible" );

    // el MCP nunca ejecuta nada: show_report con el perfil (que corre una copia que escribe) no lo arma
    int n1 = servicio.pedidos.size();
    Pedido conPerfil = new Pedido();
    conPerfil.secciones = new ArrayList<String>( Arrays.asList( SeccionId.PORTADA, SeccionId.PERFIL ) );
    conPerfil.perfilStep = "Calcular importe";
    conPerfil.idioma = "es";
    PanelInformes.mostrar( "T1", conPerfil );
    esperar( "aplica el pedido con perfil", () -> servicio.pedidos.size() > n1 && !perspectiva.panel().ocupado(),
      30000 );
    Pedido armado = servicio.pedidos.get( servicio.pedidos.size() - 1 );
    cierto( armado.secciones.contains( SeccionId.PORTADA ), "la portada pedida: " + armado.secciones );
    falso( armado.secciones.contains( SeccionId.PERFIL ), "sin el perfil por el MCP: " + armado.secciones );

    // build_report con show_in_spoon: el informe ya armado se muestra sin armarlo (ni ejecutarlo) otra vez
    int n2 = servicio.pedidos.size();
    int v2 = servicio.vistasCopia().size();
    Informe yaArmado = ModeloMuestra.crear();
    yaArmado.titulo = "Ya armado por build_report";
    PanelInformes.mostrar( "T1", conPerfil, yaArmado );
    esperar( "muestra el informe armado", () -> servicio.vistasCopia().size() > v2 && !perspectiva.panel().ocupado(),
      30000 );
    Thread.sleep( 300 );
    igual( n2, servicio.pedidos.size(), "no vuelve a armar: el perfil no corre dos veces" );

    // una tarea del usuario en curso no se corta: el show_report espera a que termine
    servicio.demoraMs = 2500;
    servicio.vioCancelacion = false;
    int n3 = servicio.pedidos.size();
    clic( "vista_previa" );
    esperar( "corre la del usuario", () -> perspectiva.panel().ocupado() && servicio.pedidos.size() > n3, 5000 );
    Pedido otro = new Pedido();
    otro.secciones = new ArrayList<String>( Arrays.asList( SeccionId.PORTADA, SeccionId.RESUMEN ) );
    otro.idioma = "es";
    PanelInformes.mostrar( "T1", otro );
    Thread.sleep( 400 );
    cierto( ui( () -> perspectiva.panel().ocupado() ), "la tarea del usuario sigue" );
    falso( servicio.vioCancelacion, "show_report no la cancelo" );
    servicio.demoraMs = 150;
    esperar( "termina la del usuario y despues se aplica el pedido", () -> servicio.pedidos.size() > n3 + 1
      && !perspectiva.panel().ocupado(), 30000 );
    falso( servicio.vioCancelacion, "la del usuario termino sin cancelarse" );
    igual( Arrays.asList( SeccionId.PORTADA, SeccionId.RESUMEN ), servicio.pedidos.get( servicio.pedidos.size() - 1 )
      .secciones, "el pedido que espero" );
  }

  /** Textos: mismas claves y mismos formatos en en/es/pt, claves del codigo presentes, ningun rotulo crudo. */
  public void pruebaG_Textos() throws Exception {
    Map<String, Properties> idiomas = new HashMap<String, Properties>();
    for ( String i : new String[] { "en", "es", "pt" } ) {
      Properties pr = new Properties();
      String txt = new String( Files.readAllBytes( new File( EntornoKettle.recursos(),
        "../../main/resources/io/github/ivi2511/pentahomcp/informes/textos/ui_" + i + ".properties" ).toPath() ),
        StandardCharsets.UTF_8 );
      pr.load( new java.io.StringReader( txt ) );
      idiomas.put( i, pr );
    }
    Set<String> en = new TreeSet<String>( idiomas.get( "en" ).stringPropertyNames() );
    for ( String i : new String[] { "es", "pt" } ) {
      igual( en, new TreeSet<String>( idiomas.get( i ).stringPropertyNames() ), "mismas claves en " + i );
      for ( String k : en ) {
        igual( formatos( idiomas.get( "en" ).getProperty( k ) ), formatos( idiomas.get( i ).getProperty( k ) ),
          "mismos %s/%d en " + i + "." + k );
      }
    }

    // claves que usa el codigo
    Set<String> usadas = new LinkedHashSet<String>();
    File main = new File( EntornoKettle.recursos(), "../../main/java/io/github/ivi2511/pentahomcp" );
    List<File> fuentes = new ArrayList<File>( Arrays.asList( new File( main, "informes/ui" ).listFiles() ) );
    fuentes.add( new File( main, "ReportsPerspective.java" ) );
    Pattern llamada = Pattern.compile( "\\.(?:s|f)\\(\\s*(?:[a-z]+\\s*\\?\\s*)?\"([a-z][a-z0-9_]*)\"(?:\\s*:\\s*\"([a-z][a-z0-9_]*)\")?" );
    for ( File f : fuentes ) {
      Matcher m = llamada.matcher( new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ) );
      while ( m.find() ) {
        for ( int g = 1; g <= 2; g++ ) {
          if ( m.group( g ) != null && !m.group( g ).endsWith( "_" ) ) { // "lang_" + i: prefijo, se listan abajo
            usadas.add( m.group( g ) );
          }
        }
      }
    }
    usadas.addAll( Arrays.asList( "lang_en", "lang_es", "lang_pt", "fmt_html", "fmt_pdf", "fmt_md", "fmt_pptx",
      "color_primary", "color_accent", "color_secondary", "status_light", "status_building" ) );
    cierto( usadas.size() > 60, "se encontraron las claves del codigo (" + usadas.size() + ")" );
    for ( String k : usadas ) {
      cierto( en.contains( k ), "falta la clave '" + k + "' en ui_en.properties" );
    }

    // ningun texto visible es una clave sin traducir
    List<String> crudos = ui( () -> {
      List<String> out = new ArrayList<String>();
      Pattern clave = Pattern.compile( "[a-z]+(_[a-z0-9]+)+" );
      recorrer( perspectiva.panel().raiz(), w -> {
        for ( String t : textos( w ) ) {
          if ( t != null && clave.matcher( t.trim() ).matches() ) {
            out.add( t );
          }
        }
      } );
      return out;
    } );
    igual( Collections.emptyList(), crudos, "textos crudos en el panel" );
    igual( "Informes", perspectiva.getDisplayName( new Locale( "es" ) ), "nombre de la perspectiva" );
  }

  /** Ventana chica: el lado izquierdo pasa a tener scroll; captura para revisar la disposicion. */
  public void pruebaH_Estrecho() throws Exception {
    ui( () -> {
      host.setBounds( 40, 40, 860, 520 );
      host.layout( true, true );
      return null;
    } );
    Thread.sleep( 400 );
    cierto( ui( () -> barraScroll( true ) ), "scroll vertical en la columna de opciones" );
    archivo( pantalla( "panel-estrecho.png" ), 5000, "captura con ventana chica" );
  }

  /** Un servicio roto: el panel no se arma, la perspectiva muestra el error y nada se propaga a Spoon. */
  public void pruebaI_ServicioRoto() throws Exception {
    PanelInformes.Servicio roto = (PanelInformes.Servicio) Proxy.newProxyInstance(
      PanelInformes.Servicio.class.getClassLoader(), new Class<?>[] { PanelInformes.Servicio.class },
      ( px, m, a ) -> {
        throw new Error( "servicio roto" );
      } );
    PanelInformes.Servicio antes = ReportsPerspective.servicioPrueba;
    String edge = System.getProperty( "org.eclipse.swt.browser.EdgeDataDir" );
    ReportsPerspective.servicioPrueba = roto;
    try {
      // 1) todo lo que el panel pide al armarse esta protegido: se arma igual y setActive no propaga
      final ReportsPerspective otra = new ReportsPerspective();
      cierto( ui( () -> {
        Composite c = otra.getUI();
        noNulo( c, "getUI" );
        boolean hayPanel = otra.panel() != null;
        otra.setActive( true );
        otra.setActive( false );
        c.dispose();
        return hayPanel;
      } ), "con el servicio roto el panel se arma igual" );

      // 2) si el constructor del panel falla, la perspectiva muestra el error en un Label
      System.clearProperty( "org.eclipse.swt.browser.EdgeDataDir" ); // asi VistaPrevia pide temporales()
      final ReportsPerspective tercera = new ReportsPerspective();
      String texto = ui( () -> {
        Composite c = tercera.getUI();
        noNulo( c, "getUI devuelve algo igual" );
        tercera.setActive( true );
        StringBuilder sb = new StringBuilder();
        for ( Control k : c.getChildren() ) {
          if ( k instanceof Label ) {
            sb.append( ( (Label) k ).getText() );
          }
        }
        c.dispose();
        return sb.toString();
      } );
      contiene( texto, "servicio roto", "el Label muestra el error" );
      contiene( texto, "No se pudo crear el panel", "con el texto traducido" );
    } finally {
      ReportsPerspective.servicioPrueba = antes;
      if ( edge != null ) {
        System.setProperty( "org.eclipse.swt.browser.EdgeDataDir", edge );
      }
    }

    // y con el servicio bueno pero una captura que falla: error en rojo, el panel sigue
    servicio.fallarCaptura = true;
    try {
      clic( "vista_previa" );
      esperar( "error en el estado", () -> perspectiva.panel().estado().contains( "captura rota" ), 5000 );
      falso( ui( () -> perspectiva.panel().ocupado() ), "no queda ocupado" );
    } finally {
      servicio.fallarCaptura = false;
    }
  }

  /** informes.json roto: el panel arranca con los defaults y lo avisa una vez (diseno §4.1). */
  public void pruebaJ_AvisoAjustes() throws Exception {
    servicio.avisoAjustes = "informes.json roto: se usan los valores por defecto";
    try {
      final ReportsPerspective otra = new ReportsPerspective();
      String[] estados = ui( () -> {
        Composite c = otra.getUI();
        String alArrancar = otra.panel().estado();
        c.dispose();
        return new String[] { alArrancar };
      } );
      igual( "informes.json roto: se usan los valores por defecto", estados[ 0 ], "aviso al arrancar" );
    } finally {
      servicio.avisoAjustes = null;
    }
  }

  // ---------------------------------------------------------------- ayudas SWT

  interface Visita {
    void ver( Widget w );
  }

  @SuppressWarnings( "unchecked" )
  private <T> T ui( final Callable<T> c ) throws Exception {
    final Object[] res = new Object[ 1 ];
    final Throwable[] err = new Throwable[ 1 ];
    display.syncExec( () -> {
      try {
        res[ 0 ] = c.call();
      } catch ( Throwable t ) {
        err[ 0 ] = t;
      }
    } );
    if ( err[ 0 ] instanceof Exception ) {
      throw (Exception) err[ 0 ];
    }
    if ( err[ 0 ] instanceof Error ) {
      throw (Error) err[ 0 ];
    }
    return (T) res[ 0 ];
  }

  private void esperar( String que, Callable<Boolean> cond, long ms ) throws Exception {
    long fin = System.currentTimeMillis() + ms;
    while ( System.currentTimeMillis() < fin ) {
      if ( Boolean.TRUE.equals( ui( cond ) ) ) {
        return;
      }
      Thread.sleep( 50 );
    }
    falla( "no paso: " + que + " (estado: " + ui( () -> perspectiva.panel().estado() ) + "; pedidos: "
      + servicio.pedidos.size() + "; vistas: " + servicio.vistasCopia() + "; log: " + servicio.logs + ")" );
  }

  /** Espera que IE termine de cargar y que el titulo tenga el texto. */
  private void esperarHtml( final String enTitulo ) throws Exception {
    esperar( "IE cargo la pagina (" + enTitulo + ")", () -> {
      try {
        Browser b = (Browser) buscar( "navegador" );
        Object estado = b.evaluate( "return document.readyState + '|' + document.title;" );
        return estado != null && String.valueOf( estado ).startsWith( "complete" ) && String.valueOf( estado )
          .contains( enTitulo );
      } catch ( RuntimeException e ) {
        return false; // IE todavia navegando
      }
    }, 20000 );
  }

  private void clic( final String id ) throws Exception {
    ui( () -> {
      Control c = buscar( id );
      cierto( c.isEnabled(), "'" + id + "' habilitado" );
      c.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  /** Si la barra (vertical u horizontal) del ScrolledComposite de opciones esta visible. (UI) */
  private boolean barraScroll( boolean vertical ) {
    for ( Control c : todos( perspectiva.panel().raiz() ) ) {
      if ( c instanceof ScrolledComposite ) {
        ScrolledComposite sc = (ScrolledComposite) c;
        org.eclipse.swt.widgets.ScrollBar b = vertical ? sc.getVerticalBar() : sc.getHorizontalBar();
        return b != null && b.isVisible();
      }
    }
    throw new AssertionError( "no hay ScrolledComposite" );
  }

  private Control buscar( String id ) {
    for ( Control c : todos( perspectiva.panel().raiz() ) ) {
      if ( id.equals( c.getData( PanelInformes.CLAVE ) ) ) {
        return c;
      }
    }
    throw new AssertionError( "no hay control '" + id + "'" );
  }

  private static List<Control> todos( Control raiz ) {
    List<Control> out = new ArrayList<Control>();
    out.add( raiz );
    if ( raiz instanceof Composite ) {
      for ( Control h : ( (Composite) raiz ).getChildren() ) {
        out.addAll( todos( h ) );
      }
    }
    return out;
  }

  private static void recorrer( Control raiz, Visita v ) {
    for ( Control c : todos( raiz ) ) {
      v.ver( c );
    }
  }

  private static List<String> textos( Widget w ) {
    List<String> t = new ArrayList<String>();
    if ( w instanceof Button ) {
      t.add( ( (Button) w ).getText() );
    } else if ( w instanceof Label ) {
      t.add( ( (Label) w ).getText() );
    } else if ( w instanceof Group ) {
      t.add( ( (Group) w ).getText() );
    } else if ( w instanceof Combo ) {
      t.addAll( Arrays.asList( ( (Combo) w ).getItems() ) );
    }
    if ( w instanceof Control ) {
      t.add( ( (Control) w ).getToolTipText() );
    }
    return t;
  }

  /** Secuencia de conversiones de formato (%s, %d, %,d...) sin %n ni %%. */
  private static List<String> formatos( String s ) {
    List<String> out = new ArrayList<String>();
    Matcher m = Pattern.compile( "%[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])" ).matcher( s == null ? "" : s );
    while ( m.find() ) {
      if ( !m.group( 1 ).equals( "n" ) && !m.group( 1 ).equals( "%" ) ) {
        out.add( m.group() );
      }
    }
    return out;
  }

  /** Captura de pantalla de la ventana (copyArea del display: tiene que estar visible y arriba). */
  private File pantalla( final String nombre ) throws Exception {
    ui( () -> {
      host.forceActive();
      host.redraw();
      host.update();
      return null;
    } );
    Thread.sleep( 700 );
    return ui( () -> {
      Rectangle b = host.getBounds();
      Image img = new Image( display, b.width, b.height );
      GC gc = new GC( display );
      try {
        gc.copyArea( img, b.x, b.y );
      } finally {
        gc.dispose();
      }
      ImageLoader il = new ImageLoader();
      il.data = new ImageData[] { img.getImageData() };
      File f = new File( capturas, nombre );
      il.save( f.getPath(), SWT.IMAGE_PNG );
      img.dispose();
      System.out.println( "  captura: " + f );
      return f;
    } );
  }

  // ================================================================ servicio falso

  /** Servicio del panel sin Spoon ni motor: registra lo que le piden y escribe HTML chico. */
  static final class ServicioFalso implements PanelInformes.Servicio {
    final List<PanelInformes.Documento> docs = new ArrayList<PanelInformes.Documento>();
    final Map<String, Captura> capturas = new HashMap<String, Captura>();
    final List<Pedido> pedidos = Collections.synchronizedList( new ArrayList<Pedido>() );
    final List<String> vistas = Collections.synchronizedList( new ArrayList<String>() );
    final List<String> exportados = Collections.synchronizedList( new ArrayList<String>() );
    final List<File> abiertos = Collections.synchronizedList( new ArrayList<File>() );
    final List<String> confirmaciones = Collections.synchronizedList( new ArrayList<String>() );
    final List<String> logs = Collections.synchronizedList( new ArrayList<String>() );
    final List<String> archivosPedidos = Collections.synchronizedList( new ArrayList<String>() );
    volatile boolean archivoEnUi;
    volatile String ultimoInformeTipo;
    volatile Tema ultimoTema;
    volatile String avisoAjustes;
    final File dir;
    volatile long demoraMs = 150;
    volatile boolean navegador = true;
    volatile boolean confirmar = true;
    volatile boolean fallarCaptura;
    volatile boolean vioCancelacion;
    volatile int pasosRecibidos;
    volatile String ultimaCaptura;
    Tema guardado;

    ServicioFalso( File dir ) {
      this.dir = dir;
    }

    List<String> vistasCopia() {
      synchronized ( vistas ) {
        return new ArrayList<String>( vistas );
      }
    }

    List<String> exportadosCopia() {
      synchronized ( exportados ) {
        return new ArrayList<String>( exportados );
      }
    }

    @Override
    public List<PanelInformes.Documento> documentos() {
      return new ArrayList<PanelInformes.Documento>( docs );
    }

    @Override
    public Captura capturar( String docId ) {
      if ( fallarCaptura ) {
        throw new IllegalStateException( "captura rota" );
      }
      Captura c = capturas.get( docId );
      if ( c == null ) {
        throw new IllegalStateException( "no hay " + docId );
      }
      ultimaCaptura = docId;
      return c;
    }

    @Override
    public Captura capturarArchivo( String ruta, Display d ) throws Exception {
      archivosPedidos.add( ruta );
      archivoEnUi = Display.getCurrent() != null;
      org.pentaho.di.trans.TransMeta tm = new org.pentaho.di.trans.TransMeta( ruta );
      Captura c = new Captura();
      c.copia = tm;
      c.docId = "archivo:" + ruta;
      c.nombre = tm.getName();
      c.archivo = ruta;
      c.tipo = Captura.TRANSFORMACION;
      c.capturado = new java.util.Date();
      return c;
    }

    @Override
    public List<Disponibilidad> disponibles( Captura c, String idioma ) {
      List<Disponibilidad> out = new ArrayList<Disponibilidad>();
      boolean job = c.esJob();
      for ( String id : SeccionId.ORDEN ) {
        boolean soloTrans = id.equals( SeccionId.FLUJO ) || id.equals( SeccionId.LINAJE ) || id.equals(
          SeccionId.DICCIONARIO ) || id.equals( SeccionId.PERFIL ) || id.equals( SeccionId.DESTINO );
        boolean aplica = job ? !soloTrans : !id.equals( SeccionId.FLUJO_JOB );
        String motivo = aplica ? null : ( job ? "Solo para transformaciones" : "Solo para jobs" );
        String costo = id.equals( SeccionId.PERFIL ) ? Disponibilidad.COSTO_EJECUTA : id.equals( SeccionId.LINAJE )
          || id.equals( SeccionId.DICCIONARIO ) ? Disponibilidad.COSTO_CAMPOS : Disponibilidad.COSTO_NADA;
        out.add( new Disponibilidad( id, aplica, motivo, DEFAULTS.contains( id ), costo ) );
      }
      return out;
    }

    @Override
    public Future<Informe> armar( final Captura c, final Pedido p, final Tema t, final Progreso pr ) {
      pedidos.add( p );
      ultimoInformeTipo = c.tipo;
      ultimoTema = t;
      final CompletableFuture<Informe> f = new CompletableFuture<Informe>();
      Thread h = new Thread( () -> {
        try {
          long fin = System.currentTimeMillis() + demoraMs;
          int i = 0;
          while ( System.currentTimeMillis() < fin ) {
            if ( pr.cancelado() ) {
              vioCancelacion = true;
              f.complete( null );
              return;
            }
            pr.paso( "Paso " + i, i++, 0 );
            pasosRecibidos++;
            Thread.sleep( 40 );
          }
          Informe inf = ModeloMuestra.crear();
          inf.titulo = c.nombre;
          inf.nombreDoc = c.nombre;
          inf.idioma = p.idioma;
          List<Seccion> pedidas = new ArrayList<Seccion>();
          for ( Seccion s : inf.secciones ) {
            if ( p.secciones.contains( s.id ) ) {
              pedidas.add( s );
            }
          }
          inf.secciones = pedidas;
          f.complete( inf );
        } catch ( Throwable e ) {
          f.completeExceptionally( e );
        }
      }, "armar-falso" );
      h.setDaemon( true );
      h.start();
      return f;
    }

    @Override
    public File vistaPrevia( Informe inf, Tema t, boolean prerender, Progreso pr ) throws Exception {
      vistas.add( String.valueOf( prerender ) );
      File f = new File( dir, "vista/vista.html" );
      f.getParentFile().mkdirs();
      StringBuilder sb = new StringBuilder();
      sb.append( "<!DOCTYPE html><html><head><meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">" )
        .append( "<meta charset=\"utf-8\"><title>vista " ).append( prerender ? "prerender" : "base" )
        .append( "</title><style>body{font-family:\"Segoe UI\",Arial,sans-serif;margin:24px;color:#1F2933}" )
        .append( "h1{color:" ).append( t.colores.primario ).append( "}li{margin:4px 0}.r{border:1px dashed #D9DEE5;" )
        .append( "padding:12px;color:#6B7785}</style></head><body><h1>" ).append( esc( inf.titulo ) )
        .append( "</h1><p>" ).append( esc( t.identidad.autor ) ).append( " &middot; " ).append( inf.idioma )
        .append( "</p><ul>" );
      for ( Seccion s : inf.secciones ) {
        sb.append( "<li>" ).append( esc( s.titulo ) ).append( " (" ).append( s.estado ).append( ")</li>" );
      }
      sb.append( "</ul>" );
      if ( prerender ) {
        sb.append( "<svg width=\"360\" height=\"60\"><rect x=\"5\" y=\"10\" width=\"140\" height=\"40\" rx=\"6\" " )
          .append( "fill=\"#E9EDF2\" stroke=\"#243B53\"/><text x=\"18\" y=\"35\">Leer ventas</text><line x1=\"145\" " )
          .append( "y1=\"30\" x2=\"210\" y2=\"30\" stroke=\"#6B7785\"/><rect x=\"210\" y=\"10\" width=\"140\" " )
          .append( "height=\"40\" rx=\"6\" fill=\"#E9EDF2\" stroke=\"#243B53\"/><text x=\"222\" y=\"35\">Agrupar</text></svg>" );
      } else {
        sb.append( "<div class=\"r\">Diagrama disponible al exportar</div>" );
      }
      sb.append( "<p style=\"color:#6B7785;font-size:11px\">Pentaho Live &middot; IDC Studios</p></body></html>" );
      Files.write( f.toPath(), sb.toString().getBytes( StandardCharsets.UTF_8 ) );
      return f;
    }

    @Override
    public Resultado exportar( Informe inf, Tema t, Formato f, File destino, Progreso pr ) throws Exception {
      exportados.add( f.name() + "|" + destino.getName() );
      destino.getParentFile().mkdirs();
      Files.write( destino.toPath(), ( "informe falso " + f.clave + " de " + inf.titulo ).getBytes(
        StandardCharsets.UTF_8 ) );
      return new Resultado().archivo( destino );
    }

    @Override
    public boolean hayNavegador() {
      return navegador;
    }

    @Override
    public List<String> pasosQueEscriben( Captura c ) {
      return Collections.singletonList( "Escribir resumen" );
    }

    @Override
    public Tema temaGuardado() {
      Tema t = new Tema();
      t.identidad.autor = "Iván Prueba";
      t.identidad.institucion = "Universidad Europea";
      t.identidad.curso = "Big Data I";
      return t;
    }

    @Override
    public Tema temaGuardado( String idioma, List<String> avisos ) {
      if ( avisoAjustes != null ) {
        avisos.add( avisoAjustes );
      }
      return temaGuardado();
    }

    @Override
    public void guardarTema( Tema t ) {
      guardado = t.copia();
    }

    @Override
    public void abrir( File f ) {
      abiertos.add( f );
    }

    @Override
    public boolean confirmar( Shell padre, String titulo, String mensaje ) {
      confirmaciones.add( titulo + ": " + mensaje );
      return confirmar;
    }

    @Override
    public File temporales() {
      return new File( dir, "tmp" );
    }

    @Override
    public void log( String texto, Throwable t ) {
      logs.add( texto + ( t == null ? "" : ": " + t ) );
    }

    private static String esc( String s ) {
      return s == null ? "" : s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" ).replace( "\"",
        "&quot;" );
    }
  }
}
