package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
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
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;
import org.pentaho.di.i18n.LanguageChoice;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPlugin;

import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * La perspectiva Corrector (C8, docs/corrector-diseno.md §9, plan B) en el harness SWT de Informes: la perspectiva
 * crea su UI como en Spoon (re-cuelgue en una tarjeta de un deck) y el panel corrige un lote de verdad (JVMs hijas
 * con el PDI de prueba): la tabla se llena, el detalle muestra la correccion en el IE11 embebido (la del alumno sin
 * los valores del caso oculto), el ajuste del docente va a la planilla, «Abrir su .ktr» abre la copia y no el
 * original, «Volver a corregir con...» habilita un step condicional y queda registrado, el informe del curso, las
 * devoluciones, ordenar, cancelar, y el asistente «Crear pack desde esta transformacion» de punta a punta. Capturas
 * en {@code <prueba>\salida\panel-corrector\}.
 * <p>
 * El SWT corre en un hilo propio ({@code ui-prueba}); los metodos le mandan trabajo con syncExec, como los workers.
 *
 * @dueño C8
 */
public class PruebaPanelCorrector {

  private static final String VENTAS = CorrectorRecursos.VENTAS;
  private static final List<String> LOTE = Arrays.asList( "paula_correcta.ktr", "bruno_agregado_mal.ktr",
    "diego_javascript.ktr", "elena_sin_ordenar.ktr" );

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell spoonShell;
  private Shell host;
  private Composite tarjeta;
  private GraderPerspective perspectiva;
  private ServicioPrueba servicio;
  private File dir;
  private File capturas;
  private File copia;
  private Rotulos t;
  private Locale localeAntes;
  private File salida;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    dir = new File( EntornoKettle.carpeta(), "panel-corrector" );
    CorrectorSandbox.borrar( dir );
    dir.mkdirs();
    capturas = EntornoKettle.salida( "panel-corrector" );
    copia = CorrectorRecursos.copia();
    t = Rotulos.de( PanelCorrector.AREA, "es" );
    localeAntes = LanguageChoice.getInstance().getDefaultLocale();
    LanguageChoice.getInstance().setDefaultLocale( new Locale( "es", "ES" ) );
    servicio = new ServicioPrueba( new File( dir, "tmp" ) );

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
      spoonShell = new Shell( display );
      GraderPerspective.padreSinSpoon = spoonShell;
      GraderPerspective.servicioPrueba = servicio;
      // fuera de la pantalla y sin boton en la barra de tareas, como PruebaPanelSwt; ON_TOP solo en el modo visible
      host = new Shell( display, SWT.NO_TRIM | ( PruebaPanelSwt.VISIBLES ? SWT.ON_TOP : SWT.TOOL ) );
      host.setLayout( new FillLayout() );
      Composite deck = new Composite( host, SWT.NONE );
      StackLayout pila = new StackLayout();
      deck.setLayout( pila );
      tarjeta = new Composite( deck, SWT.NONE );
      tarjeta.setLayout( new GridLayout() );
      pila.topControl = tarjeta;
      host.setBounds( PruebaPanelSwt.X, PruebaPanelSwt.Y, 1280, 780 );
      return null;
    } );
  }

  public void cerrar() {
    try {
      ui( () -> {
        for ( Shell s : display.getShells() ) {
          s.dispose();
        }
        return null;
      } );
    } catch ( Throwable ignorada ) {
      // seguir cerrando
    }
    GraderPerspective.padreSinSpoon = null;
    GraderPerspective.servicioPrueba = null;
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

  /** getUI + setParent como el deck de Spoon; el pack muestra su resumen; la carpeta, cuantas entregas. */
  public void pruebaA_PerspectivaPackYCarpeta() throws Exception {
    perspectiva = new GraderPerspective();
    ui( () -> {
      Composite raiz = perspectiva.getUI();
      noNulo( raiz, "getUI" );
      cierto( raiz.getParent() == spoonShell, "getUI crea sobre la Shell de Spoon" );
      cierto( raiz.setParent( tarjeta ), "re-cuelgue (setParent) a la tarjeta del deck" );
      tarjeta.layout( true, true );
      PruebaPanelSwt.mostrar( host );
      host.layout( true, true );
      noNulo( perspectiva.panel(), "el panel se creo" );
      perspectiva.setActive( true );
      return null;
    } );
    igual( "Corrector", perspectiva.getDisplayName( new Locale( "es" ) ), "nombre es" );
    igual( "Grader", perspectiva.getDisplayName( Locale.ENGLISH ), "nombre en" );
    igual( "Corretor", perspectiva.getDisplayName( new Locale( "pt", "BR" ) ), "nombre pt" );
    nulo( perspectiva.getActiveMeta(), "sin documento propio" );
    String tipo = ui( () -> ( (Browser) buscar( "detalle" ) ).getBrowserType() );
    igual( "ie", tipo, "el detalle usa el IE11 del SWT de PDI" );
    cierto( ui( () -> buscar( "aviso_sandbox" ).getVisible() ), "el aviso de que no es un sandbox real" );

    // pack: resumen; uno que no existe: en rojo
    final File pack = new File( copia, "packs/" + VENTAS );
    texto( "pack", pack.getPath() );
    esperar( "resumen del pack", () -> etiqueta( "pack_info" ).contains( "2 casos" ), 20000 );
    contiene( etiqueta( "pack_info" ), "Ejercicio 3 - Ventas por producto", "titulo del pack" );
    contiene( etiqueta( "pack_info" ), "(1 oculto)", "casos ocultos" );
    texto( "pack", new File( dir, "no-existe" ).getPath() );
    esperar( "pack que no existe", () -> etiqueta( "pack_info" ).contains( "No existe" ), 5000 );
    igual( SWT.COLOR_RED, rojo( "pack_info" ), "en rojo" );
    texto( "pack", "relativa/pack" );
    esperar( "ruta relativa", () -> etiqueta( "pack_info" ).contains( "absoluta" ), 5000 );
    texto( "pack", pack.getPath() );
    esperar( "resumen otra vez", () -> etiqueta( "pack_info" ).contains( "2 casos" ), 20000 );

    // entregas
    File entregas = new File( dir, "Entregas TP3" );
    for ( String n : LOTE ) {
      copiar( new File( copia, "entregas/" + VENTAS + "/" + n ), new File( entregas, n ) );
    }
    new File( entregas, ".oculta" ).mkdirs();
    texto( "carpeta", entregas.getPath() );
    esperar( "cuenta las entregas", () -> etiqueta( "carpeta_info" ).equals( "4 entregas" ), 5000 );
    salida = new File( dir, "Entregas TP3 - corrección" );
    igual( salida.getPath(), ui( () -> ( (Text) buscar( "salida" ) ).getMessage() ), "resultados por defecto, al lado" );
    if ( panel().lote() == null ) { // en la suite completa puede mostrar el ultimo lote de grade_submissions
      falso( ui( () -> buscar( "notas" ).isEnabled() ), "sin lote: Notas deshabilitado" );
    }
  }

  /** «Corregir» con 2 workers de verdad: la tabla se llena, las marcas por check y el resumen del pie. */
  public void pruebaB_Corregir() throws Exception {
    memoria();
    ui( () -> {
      ( (org.eclipse.swt.widgets.Spinner) buscar( "workers" ) ).setSelection( 2 );
      return null;
    } );
    final CorrectorLote previo = panel().lote(); // en la suite, el ultimo de grade_submissions
    long t0 = System.currentTimeMillis();
    clic( "corregir" );
    esperar( "arranca el lote", () -> panel().lote() != null && panel().lote() != previo, 30000 );
    falso( ui( () -> buscar( "corregir" ).isEnabled() ), "Corregir deshabilitado mientras corre" );
    cierto( ui( () -> buscar( "cancelar" ).isEnabled() ), "Cancelar habilitado" );
    cierto( ui( () -> buscar( "progreso" ).getVisible() ), "barra de progreso visible" );
    igual( salida.getPath(), ui( () -> ( (Text) buscar( "salida" ) ).getText() ), "la carpeta por defecto quedo" );
    esperar( "termina el lote", () -> panel().lote().terminado() && panel().estado().contains( "terminó" ), 300000 );
    System.out.println( "  lote de 4 en el panel: " + ( System.currentTimeMillis() - t0 ) + " ms" );

    List<String[]> filas = ui( () -> panel().filasVisibles() );
    igual( 4, filas.size(), "4 filas" );
    String[] paula = fila( filas, "paula_correcta" );
    igual( "10,0", paula[ 1 ], "paula 10" );
    igual( "corregida", paula[ 2 ], "estado traducido" );
    igual( Arrays.asList( "●", "●", "●", "●" ), Arrays.asList( paula[ 3 ], paula[ 4 ], paula[ 5 ], paula[ 6 ] ),
      "paula: todos los checks" );
    igual( "3/3", paula[ 7 ], "paula: las 3 reglas" );
    String[] bruno = fila( filas, "bruno_agregado_mal" );
    igual( "6,5", bruno[ 1 ], "bruno 6,5" );
    igual( "◐", bruno[ 6 ], "bruno: valores a medias" );
    String[] diego = fila( filas, "diego_javascript" );
    igual( "0,5", diego[ 1 ], "diego 0,5 (bloqueada, compuerta)" );
    igual( "bloqueada", diego[ 2 ], "diego bloqueada" );
    igual( "○", diego[ 3 ], "diego no corre" );
    String[] elena = fila( filas, "elena_sin_ordenar" );
    igual( "5,5", elena[ 1 ], "elena 5,5" );
    igual( "○", elena[ 5 ], "elena: cantidad de filas" );
    String pie = ui( () -> panel().estado() );
    contiene( pie, "4/4", "el pie cuenta" );
    contiene( pie, "media 5,6", "la media" );
    contiene( pie, "bloqueadas: 1", "las bloqueadas" );
    falso( ui( () -> buscar( "progreso" ).getVisible() ), "sin barra al terminar" );
    cierto( ui( () -> buscar( "notas" ).isEnabled() && buscar( "exportar_devoluciones" ).isEnabled() ),
      "Notas y Exportar habilitados" );
    archivo( new File( salida, "notas.csv" ), 100, "planilla csv" );
    archivo( new File( salida, "notas.xlsx" ), 1000, "planilla xlsx" );
    archivo( new File( salida, "bruno_agregado_mal.html" ), 1000, "devolucion html del lote" );
    archivo( new File( salida, "bruno_agregado_mal.md" ), 100, "devolucion md del lote" );
  }

  /** El detalle: la correccion del docente (con el caso oculto) y la del alumno (sin sus valores). */
  public void pruebaC_Detalle() throws Exception {
    ui( () -> {
      panel().seleccionar( "bruno_agregado_mal" );
      return null;
    } );
    esperar( "carga el detalle", () -> panel().urlDetalle() != null && cargado(), 30000 );
    contiene( etiqueta( "detalle_titulo" ), "bruno_agregado_mal", "titulo del detalle" );
    contiene( etiqueta( "detalle_titulo" ), "6,5", "con la nota" );
    String docente = html( panel().urlDetalle() );
    contiene( docente, "importe_medio", "la columna que difiere" );
    contiene( docente, "Jugo", "el docente ve el caso oculto (Jugo solo esta ahi)" );
    String cuerpo = ui( () -> String.valueOf( ( (Browser) buscar( "detalle" ) ).evaluate(
      "return document.body.innerText;" ) ) );
    contiene( cuerpo, "bruno_agregado_mal", "IE muestra la correccion" );
    archivo( pantalla( "corrector-detalle.png" ), 5000, "captura del panel con el detalle" );

    final String antes = ui( () -> panel().urlDetalle() );
    ui( () -> {
      Button b = (Button) buscar( "vista_alumno" );
      b.setSelection( true );
      b.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    esperar( "la vista del alumno", () -> panel().urlDetalle() != null && !panel().urlDetalle().equals( antes )
      && cargado(), 30000 );
    String alumno = html( panel().urlDetalle() );
    contiene( alumno, "bruno_agregado_mal", "la del alumno" );
    noContiene( alumno, "Jugo", "sin los valores del caso oculto" );
    noContiene( alumno, "12,50", "ni sus importes" );
    noContiene( alumno, "12.50", "ni sus importes (con punto)" );
    falso( new File( new URIArchivo( antes ).f ).exists(), "el HTML anterior se borra" );
    ui( () -> {
      Button b = (Button) buscar( "vista_alumno" );
      b.setSelection( false );
      b.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  /** El ajuste del docente: +0,5 y comentario a la tabla y a la planilla; un ajuste invalido no se guarda. */
  public void pruebaD_Ajuste() throws Exception {
    ui( () -> {
      ( (Text) buscar( "ajuste" ) ).setText( "abc" );
      return null;
    } );
    clic( "guardar_ajuste" );
    esperar( "ajuste invalido", () -> panel().estado().contains( "tiene que ser un número" ), 5000 );
    ui( () -> {
      ( (Text) buscar( "ajuste" ) ).setText( "+0,5" );
      ( (Text) buscar( "comentario" ) ).setText( "Revisado en clase; el MAX era a propósito" );
      return null;
    } );
    clic( "guardar_ajuste" );
    esperar( "ajuste guardado", () -> panel().estado().contains( "nota final 7,0" ), 30000 );
    String[] bruno = fila( ui( () -> panel().filasVisibles() ), "bruno_agregado_mal" );
    igual( "7,0", bruno[ 1 ], "nota final en la tabla" );
    igual( "+0,5", bruno[ 8 ], "el ajuste en la tabla" );
    contiene( bruno[ 9 ], "Revisado en clase", "el comentario" );
    String csv = EntornoKettle.leer( new File( salida, "notas.csv" ) );
    contiene( csv, "Revisado en clase; el MAX era a propósito", "el comentario en la planilla" );
    CorrectorLote.Estado e = panel().lote().estado();
    for ( CorrectorLote.Renglon r : e.resultados ) {
      if ( "bruno_agregado_mal".equals( r.alumno ) ) {
        igual( 0.5, r.ajuste, "el ajuste en el estado del lote (get_grading_status)" );
        igual( 6.5, r.notaMotor, "nota del motor" );
      }
    }
  }

  /** «Abrir su .ktr» abre la copia del caso visible (no el original); «Abrir devolucion», el HTML del alumno. */
  public void pruebaE_AbrirKtrYDevolucion() throws Exception {
    clic( "abrir_ktr" );
    esperar( "abre en Spoon", () -> !servicio.enSpoon.isEmpty(), 5000 );
    File k = servicio.enSpoon.get( 0 );
    contiene( k.getPath(), CorrectorProcesos.ABIERTOS, "la copia de _abiertos" );
    contiene( k.getPath(), "bruno_agregado_mal", "la de bruno" );
    cierto( new File( k.getParentFile(), "datos" ).isDirectory(), "con sus datos" );
    clic( "abrir_devolucion" );
    esperar( "abre la devolucion", () -> !servicio.abiertos.isEmpty(), 5000 );
    igual( "bruno_agregado_mal.html", servicio.abiertos.get( servicio.abiertos.size() - 1 ).getName(), "el HTML" );
    // diego no corrio: no hay copia que abrir
    ui( () -> {
      panel().seleccionar( "diego_javascript" );
      return null;
    } );
    falso( ui( () -> buscar( "abrir_ktr" ).isEnabled() ), "diego (bloqueada) sin .ktr que abrir" );
  }

  /** «Volver a corregir con...»: ScriptValueMod marcado (estaba bloqueado), corre y queda registrado. */
  public void pruebaF_Recorregir() throws Exception {
    memoria();
    clic( "recorregir" );
    esperar( "abre el dialogo", () -> panel().dialogo() != null && !panel().dialogo().shell.isDisposed(), 5000 );
    cierto( ui( () -> ( (Button) panel().dialogo().buscar( "re:ScriptValueMod" ) ).getSelection() ),
      "el step bloqueado viene marcado" );
    falso( ui( () -> ( (Button) panel().dialogo().buscar( "re:TableOutput" ) ).getSelection() ), "los demas no" );
    falso( ui( () -> ( (Button) panel().dialogo().buscar( "re_confianza" ) ).getSelection() ),
      "el modo confianza nunca viene marcado" );
    contiene( ui( () -> ( (Label) panel().dialogo().buscar( "re_bloqueados" ) ).getText() ), "ScriptValueMod",
      "dice que estaba bloqueado" );
    archivo( pantallaShell( panel().dialogo().shell, "corrector-recorregir.png" ), 2000, "captura del dialogo" );
    ui( () -> {
      Control c = panel().dialogo().buscar( "re_aceptar" );
      c.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    esperar( "corrige otra vez", () -> !panel().ocupado() && panel().estado().contains( "(antes 0,5)" ), 180000 );
    String[] diego = fila( ui( () -> panel().filasVisibles() ), "diego_javascript" );
    igual( "corregida", diego[ 2 ], "ahora corre" );
    cierto( Double.parseDouble( diego[ 1 ].replace( ',', '.' ) ) > 0.5, "ahora suma: " + diego[ 1 ] );
    contiene( diego[ 7 ], "/3", "las reglas: " + diego[ 7 ] );
    ResultadoCorreccion r = CorrectorJson.leer( new File( salida, "diego_javascript.json" ), ResultadoCorreccion.class );
    igual( Collections.singletonList( "ScriptValueMod" ), r.stepsPermitidosExtra, "queda registrado" );
    falso( r.modoConfianza, "sin modo confianza" );
    contiene( EntornoKettle.leer( new File( salida, "notas.csv" ) ), "diego_javascript", "la planilla se reescribio" );
    cierto( new File( new File( salida, CorrectorProcesos.ABIERTOS ), "diego_javascript" ).isDirectory(),
      "ahora tiene copia para abrir" );
  }

  /** Ordenar por nota (de mayor a menor y al reves) y por alumno. */
  public void pruebaG_Ordenar() throws Exception {
    columna( 1 );
    List<String[]> f = ui( () -> panel().filasVisibles() );
    igual( "paula_correcta", f.get( 0 )[ 0 ], "la nota mas alta primero" );
    columna( 1 );
    f = ui( () -> panel().filasVisibles() );
    igual( "elena_sin_ordenar", f.get( 0 )[ 0 ], "y al reves" );
    columna( 0 );
    f = ui( () -> panel().filasVisibles() );
    igual( "bruno_agregado_mal", f.get( 0 )[ 0 ], "por alumno" );
    igual( "diego_javascript", ui( () -> {
      Table tb = (Table) buscar( "tabla" );
      int i = tb.getSelectionIndex();
      return i < 0 ? null : tb.getItem( i ).getData();
    } ), "la seleccion sigue en diego" );
  }

  /** Notas: abrir la planilla y el informe del curso; exportar las devoluciones (HTML y PDF). */
  public void pruebaH_NotasCursoYDevoluciones() throws Exception {
    int n = servicio.abiertos.size();
    menu( "abrir_xlsx" );
    esperar( "abre notas.xlsx", () -> servicio.abiertos.size() > n, 5000 );
    igual( "notas.xlsx", servicio.abiertos.get( servicio.abiertos.size() - 1 ).getName(), "la planilla" );
    int n2 = servicio.abiertos.size();
    menu( "curso_html" );
    esperar( "informe del curso", () -> servicio.abiertos.size() > n2, 60000 );
    File curso = servicio.abiertos.get( servicio.abiertos.size() - 1 );
    igual( "curso.html", curso.getName(), "curso.html" );
    String h = EntornoKettle.leer( curso );
    contiene( h, "Distribución de notas", "la distribucion" );
    contiene( h, "7,0", "con la nota ajustada de bruno" );
    clic( "exportar_devoluciones" );
    esperar( "exporta", () -> panel().estado().contains( "archivos en" ) && ui( () -> buscar(
      "exportar_devoluciones" ).isEnabled() ), 240000 );
    File dev = new File( salida, "devoluciones" );
    for ( String a : Arrays.asList( "paula_correcta", "bruno_agregado_mal", "diego_javascript", "elena_sin_ordenar" ) ) {
      archivo( new File( dev, a + ".html" ), 1000, "html de " + a );
      cierto( new File( dev, a + ".pdf" ).isFile() || new File( dev, a + ".imprimir.html" ).isFile(),
        "pdf (o su HTML para imprimir) de " + a );
    }
    noContiene( EntornoKettle.leer( new File( dev, "bruno_agregado_mal.html" ) ), "Jugo",
      "la devolucion exportada es la del alumno" );
  }

  /** Cancelar un lote que no termina: queda lo corregido y el pie lo dice. */
  public void pruebaI_Cancelar() throws Exception {
    memoria();
    File entregas = new File( dir, "lote-infinito" );
    copiar( new File( copia, "entregas/" + VENTAS + "/t6_infinito.ktr" ), new File( entregas, "t6_infinito.ktr" ) );
    copiar( new File( copia, "entregas/" + VENTAS + "/paula_correcta.ktr" ), new File( entregas, "zz_paula.ktr" ) );
    texto( "carpeta", entregas.getPath() );
    texto( "salida", new File( dir, "lote-infinito-notas" ).getPath() );
    ui( () -> {
      ( (org.eclipse.swt.widgets.Spinner) buscar( "workers" ) ).setSelection( 1 );
      return null;
    } );
    final CorrectorLote antes = panel().lote();
    clic( "corregir" );
    esperar( "arranca", () -> panel().lote() != antes && panel().lote() != null && !panel().lote().terminado(),
      30000 );
    Thread.sleep( 2000 );
    long t0 = System.currentTimeMillis();
    clic( "cancelar" );
    esperar( "cancelado", () -> panel().lote().terminado() && panel().estado().contains( "cancelada" ), 60000 );
    cierto( System.currentTimeMillis() - t0 < 45000, "cancelar no espera al timeout" );
    cierto( ui( () -> buscar( "corregir" ).isEnabled() ), "se puede corregir otra vez" );
  }

  /** Un lote que arranca grade_submissions (otro hilo, sin el panel): al volver a la perspectiva, se ve ese. */
  public void pruebaIb_LoteDeMcp() throws Exception {
    File entregas = new File( dir, "lote-mcp" );
    new File( entregas, "vacia" ).mkdirs(); // load_failed sin lanzar ningun worker
    CorrectorLote.Opciones op = new CorrectorLote.Opciones();
    op.pack = new File( copia, "packs/" + VENTAS );
    op.carpeta = entregas;
    op.salida = new File( dir, "lote-mcp-notas" );
    op.procesos = servicio.procesos();
    final CorrectorLote l = CorrectorLote.iniciar( op );
    cierto( l.esperar( 30000 ), "termina sin workers" );
    falso( panel().lote() == l, "todavia muestra el suyo" );
    ui( () -> {
      perspectiva.setActive( true );
      return null;
    } );
    esperar( "muestra el lote de grade_submissions", () -> panel().lote() == l, 5000 );
    String[] f = fila( ui( () -> panel().filasVisibles() ), "vacia" );
    igual( "no se pudo abrir", f[ 2 ], "la entrega vacia" );
  }

  /** El asistente de 4 pasos de punta a punta: analizar, ver la salida esperada, quitar una regla y guardar. */
  public void pruebaJ_Asistente() throws Exception {
    memoria();
    File profe = new File( dir, "profe" );
    File fixture = new File( copia, "packs/" + VENTAS );
    copiar( new File( fixture, "solucion/03_ventas_por_producto.ktr" ), new File( profe, "03_ventas_por_producto.ktr" ) );
    copiar( new File( fixture, "datos/visible/ventas.csv" ), new File( profe, "datos/ventas.csv" ) );
    copiar( new File( fixture, "datos/oculto/ventas.csv" ), new File( profe, "oculto/ventas.csv" ) );
    // sin transformacion activa: aviso, sin asistente
    clic( "crear_pack" );
    esperar( "sin pestana activa", () -> panel().estado().contains( "transformación solución" ), 5000 );
    nulo( panel().asistente(), "sin asistente" );

    servicio.solucion = new File( profe, "03_ventas_por_producto.ktr" );
    clic( "crear_pack" );
    esperar( "abre el asistente", () -> panel().asistente() != null && !panel().asistente().cerrado(), 5000 );
    final AsistentePack a = panel().asistente();
    igual( 0, a.pagina(), "paso 1" );
    contiene( ui( () -> ( (Label) a.buscar( "as_paso" ) ).getText() ), "Paso 1 de 4", "rotulo del paso" );
    igual( "03_ventas_por_producto", ui( () -> ( (Text) a.buscar( "as_titulo" ) ).getText() ), "titulo propuesto" );
    final File destino = new File( dir, "packs/creado-ui" );
    ui( () -> {
      ( (Text) a.buscar( "as_titulo" ) ).setText( "Ejercicio 3 - Ventas por producto" );
      ( (Text) a.buscar( "as_destino" ) ).setText( destino.getPath() );
      ( (Text) a.buscar( "as_oculto" ) ).setText( new File( profe, "oculto" ).getPath() );
      return null;
    } );
    long t0 = System.currentTimeMillis();
    clicEn( a, "as_siguiente" );
    esperar( "analiza", () -> a.pagina() == 1 && !a.trabajando(), 240000 );
    System.out.println( "  asistente, analizar: " + ( System.currentTimeMillis() - t0 ) + " ms" );
    falso( destino.exists(), "analizar no guarda nada en el destino" );
    contiene( ui( () -> ( (Label) a.buscar( "as_entradas" ) ).getText() ), "ventas.csv", "entradas detectadas" );
    igual( "Escribir totales", ui( () -> ( (Combo) a.buscar( "as_salida" ) ).getText() ), "step de salida" );
    igual( "producto", ui( () -> ( (Text) a.buscar( "as_claves" ) ).getText() ), "clave" );
    contiene( ui( () -> ( (Label) a.buscar( "as_columnas" ) ).getText() ), "importe_medio (Number", "columnas" );
    igual( 4, ui( () -> ( (Table) a.buscar( "as_esperada" ) ).getItemCount() ), "salida esperada: 4 filas" );
    igual( 6, ui( () -> ( (Table) a.buscar( "as_esperada" ) ).getColumnCount() ), "6 columnas" );
    archivo( pantallaShell( a.shell, "corrector-asistente-paso2.png" ), 2000, "captura del paso 2" );

    clicEn( a, "as_siguiente" );
    igual( 2, a.pagina(), "paso 3: reglas" );
    List<String> reglas = ui( () -> a.reglasMarcadas() );
    cierto( reglas.contains( "usa_group_by" ), "sugiere usa_group_by: " + reglas );
    String quitar = reglas.get( reglas.size() - 1 );
    ui( () -> {
      ( (Button) a.buscar( "as_regla:" + quitar ) ).setSelection( false );
      return null;
    } );
    clicEn( a, "as_siguiente" );
    igual( 3, a.pagina(), "paso 4: rubrica" );
    List<String> rub = ui( () -> a.rubrica() );
    falso( rub.toString().contains( "rule:" + quitar ), "sin la regla quitada: " + rub );
    cierto( rub.contains( "values=4" ), "values 4: " + rub );
    ui( () -> {
      ( (Text) a.buscar( "as_puntos:values" ) ).setText( "5" );
      return null;
    } );
    t0 = System.currentTimeMillis();
    clicEn( a, "as_guardar" );
    esperar( "guarda", () -> a.guardado() != null && !a.trabajando(), 300000 );
    System.out.println( "  asistente, guardar: " + ( System.currentTimeMillis() - t0 ) + " ms; " + ui( () -> a
      .estado() ) );
    contiene( ui( () -> a.estado() ), "Pack guardado", "estado del asistente" );
    contiene( ui( () -> a.estado() ), "creado-ui-alumnos.zip", "la copia para alumnos" );
    try ( CorrectorPack cp = CorrectorPack.abrir( destino ) ) {
      cierto( cp.valido(), "el pack guardado valida: " + cp.textoProblemas( "es" ) );
      Pack p = cp.pack();
      igual( "Ejercicio 3 - Ventas por producto", p.titulo, "titulo" );
      igual( 2, p.casos.size(), "visible y oculto" );
      cierto( p.casos.get( 1 ).oculto, "el oculto" );
      igual( Collections.singletonList( "producto" ), p.comparar.columnasClave, "clave" );
      igual( 5.0, p.itemRubrica( "values" ).puntos, "los puntos editados" );
      falso( p.regla( quitar ) != null, "la regla quitada no esta" );
      igual( "es", p.idioma, "idioma" );
    }
    File alumnos = new File( destino.getParentFile(), "creado-ui-alumnos.zip" );
    try ( ZipFile z = new ZipFile( alumnos ) ) {
      for ( java.util.Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
        String nn = en.nextElement().getName();
        falso( nn.startsWith( "solucion" ) || nn.contains( "oculto" ), "la copia para alumnos no tiene " + nn );
      }
    }
    clicEn( a, "as_usar" );
    esperar( "usa el pack", () -> a.cerrado(), 5000 );
    igual( destino.getPath(), ui( () -> ( (Text) buscar( "pack" ) ).getText() ), "el pack nuevo en el panel" );
    esperar( "su resumen", () -> etiqueta( "pack_info" ).contains( "2 casos" ), 20000 );
  }

  /** Textos: las claves ui.* del codigo estan en los tres idiomas, con los mismos marcadores; nada crudo. */
  public void pruebaK_Textos() throws Exception {
    List<Properties> ps = new ArrayList<Properties>();
    for ( String i : new String[] { "en", "es", "pt" } ) {
      Properties pr = new Properties();
      pr.load( new java.io.StringReader( new String( Files.readAllBytes( new File( EntornoKettle.recursos(),
        "../../main/resources/io/github/ivi2511/pentahomcp/informes/textos/corrector_" + i + ".properties" )
        .toPath() ), StandardCharsets.UTF_8 ) ) );
      ps.add( pr );
    }
    Set<String> en = new TreeSet<String>();
    for ( String k : ps.get( 0 ).stringPropertyNames() ) {
      if ( k.startsWith( "ui." ) ) {
        en.add( k );
      }
    }
    Pattern marca = Pattern.compile( "%(\\d\\$)?[,.0-9]*[sdf]" );
    for ( int i = 1; i < 3; i++ ) {
      for ( String k : en ) {
        noNulo( ps.get( i ).getProperty( k ), k + " en " + ( i == 1 ? "es" : "pt" ) );
        igual( marcas( marca, ps.get( 0 ).getProperty( k ) ), marcas( marca, ps.get( i ).getProperty( k ) ),
          "mismos marcadores en " + k );
      }
    }
    Set<String> usadas = new LinkedHashSet<String>();
    File main = new File( EntornoKettle.recursos(), "../../main/java/io/github/ivi2511/pentahomcp" );
    Pattern clave = Pattern.compile( "\"(ui\\.[a-z0-9_.]+)\"" );
    for ( String f : Arrays.asList( "PanelCorrector.java", "AsistentePack.java", "GraderPerspective.java" ) ) {
      Matcher m = clave.matcher( new String( Files.readAllBytes( new File( main, f ).toPath() ),
        StandardCharsets.UTF_8 ) );
      while ( m.find() ) {
        if ( !m.group( 1 ).endsWith( "." ) ) {
          usadas.add( m.group( 1 ) );
        }
      }
    }
    for ( String c : PanelCorrector.COLUMNAS ) {
      usadas.add( "ui.col." + c );
    }
    for ( int i = 1; i <= AsistentePack.PAGINAS; i++ ) {
      usadas.add( "ui.as_paso" + i );
    }
    cierto( usadas.size() > 100, "las claves del codigo (" + usadas.size() + ")" );
    for ( String k : usadas ) {
      cierto( en.contains( k ), "falta la clave " + k + " en corrector_en.properties" );
    }
    // ningun texto visible del panel es una clave cruda
    List<String> crudos = ui( () -> {
      List<String> out = new ArrayList<String>();
      for ( Control c : todos( panel().raiz() ) ) {
        for ( String s : textos( c ) ) {
          if ( s != null && s.trim().matches( "ui\\.[a-z0-9_.]+" ) ) {
            out.add( s );
          }
        }
      }
      return out;
    } );
    igual( Collections.emptyList(), crudos, "textos crudos en el panel" );
  }

  /** Un servicio roto no tira el panel ni la perspectiva; el error de una accion va al estado. */
  public void pruebaL_ServicioRoto() throws Exception {
    PanelCorrector.Servicio roto = (PanelCorrector.Servicio) Proxy.newProxyInstance(
      PanelCorrector.Servicio.class.getClassLoader(), new Class<?>[] { PanelCorrector.Servicio.class },
      ( px, m, a ) -> {
        throw new Error( "servicio roto" );
      } );
    GraderPerspective.servicioPrueba = roto;
    try {
      final GraderPerspective otra = new GraderPerspective();
      String estado = ui( () -> {
        Composite c = otra.getUI();
        noNulo( c, "getUI" );
        noNulo( otra.panel(), "el panel se arma igual" );
        otra.setActive( true );
        Control b = PanelCorrector.buscar( otra.panel().raiz(), "crear_pack" );
        b.notifyListeners( SWT.Selection, new Event() );
        String s = otra.panel().estado();
        c.dispose();
        return s;
      } );
      contiene( estado, "servicio roto", "el error va al estado" );
    } finally {
      GraderPerspective.servicioPrueba = servicio;
    }
  }

  /** El @SpoonPlugin sin classLoaderGroup, la misma perspectiva siempre y apagado con PENTAHO_LIVE_GRADER=off. */
  public void pruebaM_Plugin() throws Exception {
    SpoonPlugin sp = GraderSpoonPlugin.class.getAnnotation( SpoonPlugin.class );
    noNulo( sp, "@SpoonPlugin" );
    igual( "PentahoLiveGrader", sp.id(), "id" );
    igual( "", sp.classLoaderGroup(), "sin classLoaderGroup (§1.3 de Informes)" );
    GraderSpoonPlugin pl = new GraderSpoonPlugin();
    SpoonPerspective p1 = pl.getPerspective();
    noNulo( p1, "perspectiva" );
    cierto( p1 == pl.getPerspective(), "la misma instancia" );
    igual( GraderPerspective.ID, p1.getId(), "id de la perspectiva" );
    igual( Boolean.FALSE, p1.getClass().getMethod( "onFileClose" ).invoke( p1 ), "onFileClose" );
    nulo( pl.getLifecycleListener(), "sin lifecycle propio" );
    String antes = System.getProperty( Interruptor.PROPIEDAD_CORRECTOR );
    try {
      System.setProperty( Interruptor.PROPIEDAD_CORRECTOR, "off" );
      nulo( new GraderSpoonPlugin().getPerspective(), "apagada" );
      falso( Interruptor.informesApagados(), "Informes sigue" );
    } finally {
      if ( antes == null ) {
        System.clearProperty( Interruptor.PROPIEDAD_CORRECTOR );
      } else {
        System.setProperty( Interruptor.PROPIEDAD_CORRECTOR, antes );
      }
    }
  }

  /** La guia 07-corrector.md es un recurso MCP (pentaho://guide/grading) y nombra las 5 tools y la perspectiva. */
  public void pruebaN_GuiaCorrector() throws Exception {
    McpResources r = new McpResources();
    contiene( r.listar( null ).toString(), "pentaho://guide/grading", "resources/list trae la guia del corrector" );
    String guia = r.leer( "pentaho://guide/grading" ).path( "contents" ).path( 0 ).path( "text" ).asText();
    for ( String n : new String[] { "create_exercise_pack", "validate_exercise_pack", "grade_exercise",
      "grade_submissions", "get_grading_status", "Grader", "PENTAHO_LIVE_GRADER", "allow_steps", "trust mode" } ) {
      contiene( guia, n, "la guia nombra " + n );
    }
  }

  // ---------------------------------------------------------------- ayudas

  private PanelCorrector panel() {
    return perspectiva.panel();
  }

  private boolean cargado() {
    try {
      Browser b = (Browser) buscar( "detalle" );
      Object e = b.evaluate( "return document.readyState;" );
      return "complete".equals( String.valueOf( e ) ) && b.getUrl() != null && b.getUrl().startsWith( "file:" );
    } catch ( RuntimeException e ) {
      return false;
    }
  }

  private static String html( String url ) throws IOException {
    return EntornoKettle.leer( new File( new URIArchivo( url ).f ) );
  }

  /** file:/C:/... a ruta. */
  private static final class URIArchivo {
    final String f;

    URIArchivo( String u ) {
      String x;
      try {
        x = new File( new java.net.URI( u ) ).getPath();
      } catch ( Exception e ) {
        x = u;
      }
      f = x;
    }
  }

  private static String[] fila( List<String[]> filas, String alumno ) {
    for ( String[] f : filas ) {
      if ( alumno.equals( f[ 0 ] ) ) {
        return f;
      }
    }
    throw new AssertionError( "no hay fila de " + alumno );
  }

  private int rojo( String id ) throws Exception {
    return ui( () -> ( (Label) buscar( id ) ).getForeground().equals( display.getSystemColor( SWT.COLOR_RED ) )
      ? SWT.COLOR_RED : -1 );
  }

  private String etiqueta( String id ) throws Exception {
    return ui( () -> ( (Label) buscar( id ) ).getText() );
  }

  private void texto( String id, String valor ) throws Exception {
    ui( () -> {
      ( (Text) buscar( id ) ).setText( valor );
      return null;
    } );
  }

  private void columna( int i ) throws Exception {
    ui( () -> {
      ( (Table) buscar( "tabla" ) ).getColumn( i ).notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  private void menu( String id ) throws Exception {
    ui( () -> {
      panel().itemNotas( id ).notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  private void clic( final String id ) throws Exception {
    ui( () -> {
      Control c = buscar( id );
      cierto( c.isEnabled(), "'" + id + "' habilitado" );
      c.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  private void clicEn( final AsistentePack a, final String id ) throws Exception {
    ui( () -> {
      Control c = a.buscar( id );
      noNulo( c, "control " + id );
      cierto( c.isEnabled(), "'" + id + "' habilitado (estado: " + a.estado() + ")" );
      c.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  private Control buscar( String id ) {
    Control c = PanelCorrector.buscar( panel().raiz(), id );
    if ( c == null ) {
      throw new AssertionError( "no hay control '" + id + "'" );
    }
    return c;
  }

  @SuppressWarnings( "unchecked" )
  private <T> T ui( final Callable<T> c ) throws Exception {
    final Object[] res = new Object[ 1 ];
    final Throwable[] err = new Throwable[ 1 ];
    display.syncExec( () -> {
      try {
        res[ 0 ] = c.call();
      } catch ( Throwable x ) {
        err[ 0 ] = x;
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
      Thread.sleep( 100 );
    }
    falla( "no paso: " + que + " (estado: " + ui( () -> perspectiva.panel() == null ? "?" : perspectiva.panel()
      .estado() ) + "; log: " + servicio.logs + ")" );
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

  private static List<String> textos( Widget w ) {
    List<String> l = new ArrayList<String>();
    if ( w instanceof Button ) {
      l.add( ( (Button) w ).getText() );
    } else if ( w instanceof Label ) {
      l.add( ( (Label) w ).getText() );
    } else if ( w instanceof Table ) {
      for ( org.eclipse.swt.widgets.TableColumn c : ( (Table) w ).getColumns() ) {
        l.add( c.getText() );
        l.add( c.getToolTipText() );
      }
    }
    if ( w instanceof Control ) {
      l.add( ( (Control) w ).getToolTipText() );
    }
    return l;
  }

  private static List<String> marcas( Pattern p, String s ) {
    List<String> l = new ArrayList<String>();
    Matcher m = p.matcher( s == null ? "" : s );
    while ( m.find() ) {
      l.add( m.group() );
    }
    return l;
  }

  private File pantalla( final String nombre ) throws Exception {
    return pantallaShell( host, nombre );
  }

  /**
   * Captura de una ventana. Por defecto se dibuja cada hijo de la Shell sobre una imagen ({@code Control.print(GC)},
   * PrintWindow): funciona fuera de la pantalla y detras de otras ventanas, sin activar nada (los dialogos se abren
   * centrados en el panel, tambien fuera de la pantalla). Con {@code -Dpruebas.ventanas.visibles=true}, copyArea del
   * display (la ventana tiene que estar visible y arriba).
   */
  private File pantallaShell( final Shell s, final String nombre ) throws Exception {
    final boolean visibles = PruebaPanelSwt.VISIBLES;
    ui( () -> {
      if ( visibles ) {
        s.forceActive();
      }
      s.redraw();
      s.update();
      return null;
    } );
    Thread.sleep( 700 );
    return ui( () -> {
      Rectangle b = visibles ? s.getBounds() : s.getClientArea();
      Image img = new Image( display, Math.max( 1, b.width ), Math.max( 1, b.height ) );
      GC gc = visibles ? new GC( display ) : new GC( img );
      try {
        if ( visibles ) {
          gc.copyArea( img, b.x, b.y );
        } else {
          // Shell.print() de SWT en Windows siempre devuelve false: se dibuja cada hijo (PruebaPanelSwt.pantalla)
          boolean alguno = false;
          for ( Control hijo : s.getChildren() ) {
            Rectangle h = hijo.getBounds();
            Image parte = new Image( display, Math.max( 1, h.width ), Math.max( 1, h.height ) );
            GC gp = new GC( parte );
            try {
              if ( hijo.print( gp ) ) {
                gc.drawImage( parte, h.x, h.y );
                alguno = true;
              }
            } finally {
              gp.dispose();
              parte.dispose();
            }
          }
          if ( !alguno ) {
            System.out.println( "  (print(GC) no soportado: la captura queda vacia)" );
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
      return f;
    } );
  }

  private static void copiar( File de, File a ) throws IOException {
    File d = a.getParentFile();
    if ( d != null && !d.isDirectory() && !d.mkdirs() ) {
      throw new IOException( "no se pudo crear " + d );
    }
    Files.copy( de.toPath(), a.toPath(), StandardCopyOption.REPLACE_EXISTING );
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

  // ================================================================ servicio de prueba

  /** Sin Spoon: la «pestana activa» es un .ktr del disco; abrir en Spoon y con el sistema solo se registran. */
  static final class ServicioPrueba implements PanelCorrector.Servicio {
    final List<File> abiertos = new CopyOnWriteArrayList<File>();
    final List<File> enSpoon = new CopyOnWriteArrayList<File>();
    final List<String> logs = new CopyOnWriteArrayList<String>();
    final File temporales;
    volatile File solucion;

    ServicioPrueba( File temporales ) {
      this.temporales = temporales;
    }

    @Override
    public CorrectorProcesos.Opciones procesos() {
      CorrectorProcesos.Opciones op = new CorrectorProcesos.Opciones();
      op.xmxMb = 640;
      op.pdi = EntornoKettle.pdi();
      op.versionPlugin = "prueba";
      return op;
    }

    @Override
    public CorrectorTools.Copia solucionActiva( File tmp ) throws Exception {
      File s = solucion;
      if ( s == null ) {
        throw new McpHttpServer.ToolError( "There is no active transformation or job in Spoon" );
      }
      CorrectorTools.Copia c = new CorrectorTools.Copia();
      c.id = "T1";
      c.nombre = s.getName();
      c.ktr = new File( new File( tmp, "doc" ), s.getName() );
      copiar( s, c.ktr );
      c.dirOriginal = s.getAbsoluteFile().getParentFile();
      return c;
    }

    @Override
    public void abrirEnSpoon( File ktr ) {
      enSpoon.add( ktr );
    }

    @Override
    public void abrir( File f ) {
      abiertos.add( f );
    }

    @Override
    public File temporales() {
      return temporales;
    }

    @Override
    public void log( String texto, Throwable t ) {
      logs.add( texto + ( t == null ? "" : ": " + t ) );
    }
  }
}
