package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.Const;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.Textos;
import io.github.ivi2511.pentahomcp.robustez.Receta;

/**
 * «Revisar calidad…» del menú IDC ({@link IdcMenu}): el linter del documento activo en una ventana no modal. Puntuación y
 * nota; los hallazgos con una casilla (gravedad, regla, dónde, mensaje, qué arreglo tiene); debajo, el detalle del
 * elegido con formato (qué pasa, por qué importa, cómo arreglarlo, los datos). Clic = su step o entrada seleccionado en
 * el lienzo; doble clic o Intro = la ventana de detalle ({@link IdcDetalle.Ventana}) con los cambios exactos que haría
 * su arreglo (un ensayo: apply_lint_fixes dry_run, o harden_etl dry_run para los de robustez), el valor editable si el
 * arreglo lo pide, y sus acciones: aplicar este arreglo, silenciar esta regla aquí, pedírselo a Claude (copia un
 * pedido listo para pegar).
 * <p>
 * Casillas: los arreglos automáticos van marcados; los que cambian lo que hace el ETL (confirm) y los que llevan un
 * valor propuesto se pueden marcar; los hallazgos de robustez se marcan para aplicar su receta de harden_etl. «Aplicar
 * marcados» aplica primero las recetas (harden_etl) y después los arreglos (apply_lint_fixes keys + values),
 * encadenados en un solo Ctrl+Z en una transformación. «Revisión profunda (lee datos)» es lint_etl deep=true con su
 * misma regla de solo lectura e «Incluir robustez», robustness=true.
 * <p>
 * La lógica no está aquí: el {@link Servicio} de Spoon llama a {@link LintTools#lint}, {@link LintTools#arreglar},
 * {@link RobustezTools#robustecer} y {@link LintTools#silenciar} con los argumentos JSON de las herramientas MCP, en
 * un hilo de trabajo ({@link IdcMenu#enHilo}); lo largo (la revisión) se puede cancelar. Las pruebas usan un servicio
 * falso. (UI)
 */
final class IdcCalidad {

  /** Lo que el diálogo pide afuera. Todo corre fuera del hilo de UI salvo seleccionar. */
  interface Servicio {
    String nombre();

    /** lint_etl. alCancelar recibe con qué cortar el análisis (puede no llamarse). */
    ObjectNode revisar( boolean profunda, boolean robustez, Consumer<Runnable> alCancelar ) throws Exception;

    /**
     * apply_lint_fixes keys + values (en el idioma del diálogo); ensayo = dry_run. continuar = encadenar los cambios al
     * último grupo de undo del documento (cuando antes se aplicaron recetas en la misma tanda).
     */
    ObjectNode arreglar( Collection<String> keys, Map<String, String> valores, boolean ensayo, boolean continuar )
      throws Exception;

    /**
     * harden_etl con las recetas de los hallazgos de robustez, pares {receta, step}: solo las propuestas de esas
     * recetas en esos steps (las demás, excluidas; las confirm, confirmadas). ensayo = dry_run.
     */
    ObjectNode recetas( List<String[]> pares, boolean ensayo ) throws Exception;

    /** suppress_lint (step o entry o ninguno = todo el documento). */
    ObjectNode silenciar( String regla, String step, String entry, String motivo ) throws Exception;

    /** select_steps del step o la entrada en el lienzo (trae su pestaña al frente). Nunca lanza. (UI) */
    void seleccionar( String step, String entry );
  }

  /** Pide el motivo de un silencio: null = cancelado. Las pruebas lo cambian. */
  interface Preguntador {
    String pedir( Shell padre, String titulo, String texto );
  }

  static volatile Preguntador preguntador = ( padre, titulo, texto ) -> {
    InputDialog d = new InputDialog( padre, titulo, texto, "", null );
    return d.open() == Window.OK ? d.getValue() : null;
  };

  /** Presupuesto de la revisión profunda (el default de deep_timeout_s). */
  static final int PROFUNDA_S = 60;
  /** El prefijo de los how_to_fix de robustez («harden_etl recipes=[x] steps=[y]: …»), que en el diálogo sobra. */
  static final Pattern PREFIJO_RECETA = Pattern.compile( "^harden_etl recipes=\\[[^\\]]*\\](?: steps=\\[[^\\]]*\\])?:\\s*" );

  /** El diálogo abierto (uno a la vez). Solo en el hilo de UI. */
  static IdcCalidad abierto;

  final Shell shell;
  final Servicio servicio;
  final Rotulos r;
  final Textos lint;
  final String docId;

  final Label puntaje;
  final Label robustez;
  final Label conteos;
  final Button profunda;
  final Button conRobustez;
  final Button revisar;
  final Label estado;
  final ProgressBar barra;
  final Button cancelar;
  final Table tabla;
  final StyledText detalle;
  final Label pie;
  final Button aplicar;
  final Button verDetalle;
  final Button silenciar;
  final Button cerrar;

  private final Color rojo;
  private final Color ambar;
  private final Color gris;
  private final Color verde;

  /** El último resultado de lint_etl. */
  ObjectNode ultimo;
  /** Hallazgos de la tabla, en orden. */
  final List<JsonNode> hallazgos = new ArrayList<JsonNode>();
  /** Valores elegidos en la ventana de detalle (key → valor): nombres, descripciones, rutas, variables… */
  final Map<String, String> valores = new LinkedHashMap<String, String>();
  /**
   * Las recetas que harden_etl sabe aplicar a los hallazgos de robustez de la última revisión («receta|step», de un
   * ensayo en segundo plano); null mientras no se sabe (entonces vale el nivel de la receta).
   */
  volatile Set<String> recetasAplicables;
  private int genRecetas;
  /** La ventana de detalle abierta (una a la vez) y su hallazgo. */
  IdcDetalle.Ventana ventana;
  JsonNode enVentana;
  volatile boolean ocupado;
  private int generacion;
  private Runnable cancelador;
  /** Un aviso para mostrar en el estado cuando termine la próxima revisión (tras aplicar o silenciar). */
  private String pendiente;

  // ---------------------------------------------------------------- apertura desde el menú

  /** Abre (o trae al frente) el diálogo del documento. (UI) */
  static void abrir( Shell padre, AbstractMeta m, Rotulos r ) {
    String id = Docs.id( m );
    if ( abierto != null && !abierto.shell.isDisposed() ) {
      if ( id.equals( abierto.docId ) ) {
        abierto.shell.setActive();
        return;
      }
      abierto.shell.close();
    }
    abierto = new IdcCalidad( padre, new ServicioSpoon( id, Docs.nombre( m ), r.idioma() ), r, id );
    abierto.abrir();
  }

  IdcCalidad( Shell padre, Servicio s, Rotulos r, String docId ) {
    this.servicio = s;
    this.r = r;
    this.lint = Textos.de( r.idioma() );
    this.docId = docId;
    shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.MODELESS );
    shell.setText( r.f( "q_title", s.nombre() ) );
    final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
    if ( ico != null ) {
      shell.setImage( ico );
      shell.addListener( SWT.Dispose, e -> ico.dispose() );
    }
    rojo = new Color( shell.getDisplay(), 175, 25, 25 );
    ambar = new Color( shell.getDisplay(), 150, 90, 0 );
    gris = new Color( shell.getDisplay(), 100, 100, 100 );
    verde = new Color( shell.getDisplay(), 25, 115, 50 );
    shell.addListener( SWT.Dispose, e -> {
      rojo.dispose();
      ambar.dispose();
      gris.dispose();
      verde.dispose();
      cortar();
      if ( ventana != null && !ventana.shell.isDisposed() ) {
        ventana.shell.dispose();
      }
      if ( abierto == this ) {
        abierto = null;
      }
    } );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 12;
    gl.marginHeight = 10;
    shell.setLayout( gl );

    // cabecera: puntuación, robustez y cuentas
    Composite cab = new Composite( shell, SWT.NONE );
    cab.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gc = new GridLayout( 3, false );
    gc.marginWidth = 0;
    gc.horizontalSpacing = 18;
    cab.setLayout( gc );
    puntaje = new Label( cab, SWT.NONE );
    puntaje.setFont( negrita( puntaje, 1.6 ) );
    puntaje.setText( "—/100" );
    robustez = new Label( cab, SWT.NONE );
    robustez.setFont( negrita( robustez, 1.1 ) );
    conteos = new Label( cab, SWT.NONE );
    conteos.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

    // opciones
    Composite op = new Composite( shell, SWT.NONE );
    op.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout go = new GridLayout( 6, false );
    go.marginWidth = 0;
    go.horizontalSpacing = 16;
    op.setLayout( go );
    profunda = new Button( op, SWT.CHECK );
    profunda.setText( r.s( "q_deep" ) );
    profunda.setToolTipText( r.s( "q_deep_tip" ) );
    conRobustez = new Button( op, SWT.CHECK );
    conRobustez.setText( r.s( "q_robust" ) );
    conRobustez.setToolTipText( r.s( "q_robust_tip" ) );
    revisar = new Button( op, SWT.PUSH );
    revisar.setText( r.s( "q_run" ) );
    revisar.addListener( SWT.Selection, e -> revisar() );
    new Label( op, SWT.NONE ).setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    barra = new ProgressBar( op, SWT.HORIZONTAL | SWT.INDETERMINATE );
    GridData gb = new GridData( SWT.RIGHT, SWT.CENTER, false, false );
    gb.widthHint = 160;
    barra.setLayoutData( gb );
    barra.setVisible( false );
    cancelar = new Button( op, SWT.PUSH );
    cancelar.setText( r.s( "cancel" ) );
    cancelar.setEnabled( false );
    cancelar.addListener( SWT.Selection, e -> cancelar() );
    estado = new Label( shell, SWT.WRAP );
    estado.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

    // hallazgos arriba, detalle abajo (la persona mueve el divisor)
    SashForm sash = new SashForm( shell, SWT.VERTICAL );
    sash.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    tabla = new Table( sash, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL );
    tabla.setHeaderVisible( true );
    tabla.setLinesVisible( true );
    columna( "col_apply", 64 );
    columna( "col_severity", 80 );
    columna( "col_rule", 230 );
    columna( "col_where", 170 );
    columna( "col_message", 360 );
    columna( "col_autofix", 210 );
    IdcDetalle.columnas( tabla, "quality", 4 );
    IdcDetalle.tooltips( tabla );
    tabla.addListener( SWT.Selection, e -> {
      if ( e.detail == SWT.CHECK ) {
        TableItem it = (TableItem) e.item;
        if ( !marcable( (JsonNode) it.getData() ) ) {
          it.setChecked( false );
        }
        contar();
      }
      elegida( true );
    } );
    IdcDetalle.alAbrir( tabla, this::abrirDetalle );

    detalle = IdcDetalle.panel( sash );
    detalle.setData( "plano", "" );
    sash.setWeights( new int[] { 58, 42 } );
    mostrarAyuda();

    pie = new Label( shell, SWT.WRAP );
    GridData gp = new GridData( SWT.FILL, SWT.TOP, true, false );
    gp.widthHint = 900;
    pie.setLayoutData( gp );
    pie.setForeground( gris );

    // botones
    Composite bo = new Composite( shell, SWT.NONE );
    bo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gbo = new GridLayout( 4, false );
    gbo.marginWidth = 0;
    bo.setLayout( gbo );
    aplicar = new Button( bo, SWT.PUSH );
    aplicar.setText( r.f( "q_apply", 0 ) );
    aplicar.setToolTipText( r.s( "q_apply_tip" ) );
    aplicar.setEnabled( false );
    aplicar.addListener( SWT.Selection, e -> aplicarMarcados() );
    verDetalle = new Button( bo, SWT.PUSH );
    verDetalle.setText( r.s( "q_details" ) );
    verDetalle.setToolTipText( r.s( "q_details_tip" ) );
    verDetalle.setEnabled( false );
    verDetalle.addListener( SWT.Selection, e -> abrirDetalle() );
    silenciar = new Button( bo, SWT.PUSH );
    silenciar.setText( r.s( "q_suppress" ) );
    silenciar.setToolTipText( r.s( "q_suppress_tip" ) );
    silenciar.setEnabled( false );
    silenciar.addListener( SWT.Selection, e -> silenciarElegido() );
    cerrar = new Button( bo, SWT.PUSH );
    cerrar.setText( r.s( "close" ) );
    GridData gce = new GridData( SWT.RIGHT, SWT.CENTER, true, false );
    gce.widthHint = 96;
    cerrar.setLayoutData( gce );
    cerrar.addListener( SWT.Selection, e -> shell.close() );

    IdcDetalle.tamano( shell, "quality", 1120, 720 );
  }

  private void columna( String clave, int ancho ) {
    TableColumn c = new TableColumn( tabla, SWT.LEFT );
    c.setText( r.s( clave ) );
    c.setWidth( ancho );
  }

  /** Muestra la ventana y lanza la primera revisión. (UI) */
  void abrir() {
    IdcMenu.abrirShell( shell, (Shell) shell.getParent() );
    revisar();
  }

  // ---------------------------------------------------------------- acciones

  /** lint_etl con las opciones marcadas, en un hilo de trabajo y cancelable. (UI) */
  void revisar() {
    if ( ocupado || shell.isDisposed() ) {
      return;
    }
    final boolean prof = profunda.getSelection();
    final boolean rob = conRobustez.getSelection();
    final int gen = ++generacion;
    final AtomicReference<Runnable> corte = new AtomicReference<Runnable>();
    cancelador = () -> {
      Runnable c = corte.get();
      if ( c != null ) {
        c.run();
      }
    };
    ocupado( prof ? r.f( "q_running_deep", servicio.nombre(), PROFUNDA_S ) : r.f( "q_running", servicio.nombre() ),
      true );
    IdcMenu.enHilo( shell, "pentaholive-idc-lint", () -> servicio.revisar( prof, rob, corte::set ), o -> {
      if ( gen != generacion ) {
        return; // cancelada
      }
      cancelador = null;
      libre( pendiente != null ? pendiente : "" );
      pendiente = null;
      mostrar( o, prof );
    }, e -> {
      if ( gen != generacion ) {
        return;
      }
      cancelador = null;
      pendiente = null;
      libre( r.f( "q_error", IdcMenu.mensaje( e ) ) );
    } );
  }

  /** Corta la revisión en curso: su resultado ya no se muestra. (UI) */
  void cancelar() {
    if ( !ocupado || cancelador == null ) {
      return;
    }
    generacion++;
    cortar();
    pendiente = null;
    libre( r.s( "q_cancelled" ) );
  }

  private void cortar() {
    final Runnable c = cancelador;
    cancelador = null;
    if ( c != null ) {
      Thread h = new Thread( c, "pentaholive-idc-cancelar" );
      h.setDaemon( true );
      h.start();
    }
  }

  /** Aplica los marcados: recetas (harden_etl) y arreglos (apply_lint_fixes), un Ctrl+Z; luego otra revisión. (UI) */
  void aplicarMarcados() {
    List<JsonNode> l = new ArrayList<JsonNode>();
    for ( TableItem it : tabla.getItems() ) {
      if ( it.getChecked() && marcable( (JsonNode) it.getData() ) ) {
        l.add( (JsonNode) it.getData() );
      }
    }
    aplicar( l, null );
  }

  /**
   * Aplica estos hallazgos: primero sus recetas, después sus arreglos, encadenados. alTerminar (puede ser null)
   * recibe el texto del resultado. (UI)
   */
  void aplicar( final List<JsonNode> elegidos, final Consumer<String> alTerminar ) {
    if ( ocupado || elegidos.isEmpty() ) {
      return;
    }
    final List<String> keys = new ArrayList<String>();
    final Map<String, String> vals = new LinkedHashMap<String, String>();
    final List<String[]> pares = new ArrayList<String[]>();
    final Map<String, JsonNode> porClave = new LinkedHashMap<String, JsonNode>();
    for ( JsonNode h : elegidos ) {
      String k = h.path( "key" ).asText();
      porClave.put( k, h );
      String receta = receta( h );
      if ( receta != null && !tieneArreglo( h ) ) {
        pares.add( new String[] { receta, texto( h, "step" ) } );
        continue;
      }
      keys.add( k );
      String v = valores.get( k );
      if ( v == null && h.path( "fix" ).path( "needs_value" ).asBoolean() ) {
        v = texto( h.path( "fix" ), "preview" );
      }
      if ( v != null ) {
        vals.put( k, v );
      }
    }
    ocupado( r.s( "q_applying" ), false );
    IdcMenu.enHilo( shell, "pentaholive-idc-fix", () -> {
      ObjectNode rr = pares.isEmpty() ? null : servicio.recetas( pares, false );
      ObjectNode lr = keys.isEmpty() ? null : servicio.arreglar( keys, vals, false, rr != null && rr.path( "applied" )
        .size() > 0 );
      return new ObjectNode[] { rr, lr };
    }, o -> {
      String txt = resumen( o[ 0 ], o[ 1 ], porClave );
      for ( String k : porClave.keySet() ) {
        valores.remove( k );
      }
      libre( txt );
      pendiente = txt;
      if ( alTerminar != null ) {
        alTerminar.accept( txt );
      }
      revisar();
    }, e -> {
      String txt = r.f( "q_apply_error", IdcMenu.mensaje( e ) );
      libre( txt );
      if ( alTerminar != null ) {
        alTerminar.accept( txt );
      }
    } );
  }

  /** El texto del estado tras aplicar: cuántos, cómo se deshacen, la puntuación y los que no se aplicaron (y por qué). */
  String resumen( ObjectNode recetas, ObjectNode arreglos, Map<String, JsonNode> porClave ) {
    int n = 0;
    List<String> no = new ArrayList<String>();
    if ( recetas != null ) {
      n += recetas.path( "applied" ).size();
      for ( JsonNode x : recetas.path( "not_applied" ) ) {
        no.add( r.f( "q_skip_item", tituloReceta( x.path( "key" ).asText() ), x.path( "reason" ).asText() ) );
      }
    }
    int pasos = 1;
    if ( arreglos != null ) {
      n += arreglos.path( "applied" ).size();
      pasos = Math.max( 1, arreglos.path( "undo_steps" ).asInt( 1 ) );
      for ( JsonNode x : arreglos.path( "skipped" ) ) {
        JsonNode h = porClave.get( x.path( "key" ).asText() );
        String quien = h != null ? titulo( h ) + donde( h, " · " ) : x.path( "key" ).asText();
        no.add( r.f( "q_skip_item", quien, x.path( "reason" ).asText() ) );
      }
    }
    String txt;
    if ( n == 0 ) {
      txt = r.s( "q_applied_none" );
    } else if ( arreglos != null && arreglos.path( "applied" ).size() > 0 ) {
      txt = r.f( "q_applied", n, pasos <= 1 ? r.s( "q_undo_one" ) : r.f( "q_undo_n", pasos ), arreglos.path(
        "score_before" ).asInt(), arreglos.path( "score_after" ).asInt() );
    } else {
      txt = r.f( "q_applied_recipes", n, puntos( recetas.get( "robustness_before" ) ), puntos( recetas.get(
        "robustness_after" ) ) );
    }
    if ( !no.isEmpty() ) {
      txt += "  " + r.f( "q_skipped", String.join( "; ", no ) );
    }
    return txt;
  }

  private static String puntos( JsonNode x ) {
    return x == null || !x.has( "score" ) ? "—" : x.path( "score" ).asInt() + " " + x.path( "grade" ).asText();
  }

  /** suppress_lint de la regla del hallazgo elegido, en su step o entrada (o en el documento). (UI) */
  void silenciarElegido() {
    silenciar( elegido(), null );
  }

  void silenciar( final JsonNode h, final Consumer<String> alTerminar ) {
    if ( h == null || ocupado ) {
      return;
    }
    final String regla = h.path( "rule" ).asText();
    final String step = texto( h, "step" );
    final String entry = step == null ? texto( h, "entry" ) : null;
    final String donde = step != null ? r.f( "q_where_step", step ) : entry != null ? r.f( "q_where_entry", entry )
      : r.s( "q_where_doc" );
    Shell padre = ventana != null && !ventana.shell.isDisposed() && enVentana == h ? ventana.shell : shell;
    String motivo = preguntador.pedir( padre, r.f( "q_suppress_title", titulo( h ) ), r.f( "error".equals( h.path(
      "severity" ).asText() ) ? "q_suppress_prompt_error" : "q_suppress_prompt", donde ) );
    if ( motivo == null ) {
      return;
    }
    final String m = motivo.trim().isEmpty() ? null : motivo.trim();
    ocupado( r.s( "q_suppressing" ), false );
    IdcMenu.enHilo( shell, "pentaholive-idc-suppress", () -> servicio.silenciar( regla, step, entry, m ), o -> {
      String txt = r.f( "q_suppressed", titulo( h ), donde );
      libre( txt );
      pendiente = txt;
      if ( alTerminar != null ) {
        alTerminar.accept( txt );
      }
      revisar();
    }, e -> {
      String txt = r.f( "q_suppress_error", IdcMenu.mensaje( e ) );
      libre( txt );
      if ( alTerminar != null ) {
        alTerminar.accept( txt );
      }
    } );
  }

  /** Clic en un hallazgo: el detalle y, si conLienzo, su step o entrada seleccionado en el lienzo. (UI) */
  void elegida( boolean conLienzo ) {
    JsonNode h = elegido();
    silenciar.setEnabled( h != null && !ocupado );
    verDetalle.setEnabled( h != null );
    if ( h == null ) {
      return;
    }
    IdcDetalle.Texto t = contenido( h, true );
    t.parrafo( r.s( "q_detail_hint" ), IdcDetalle.Texto.SUAVE );
    IdcDetalle.mostrar( detalle, t );
    detalle.setData( "plano", t.plano() );
    if ( conLienzo ) {
      String step = texto( h, "step" );
      String entry = texto( h, "entry" );
      if ( step != null || entry != null ) {
        servicio.seleccionar( step, entry );
      }
    }
  }

  JsonNode elegido() {
    int i = tabla.getSelectionIndex();
    return i >= 0 && i < hallazgos.size() ? hallazgos.get( i ) : null;
  }

  private void mostrarAyuda() {
    IdcDetalle.Texto t = new IdcDetalle.Texto();
    t.parrafo( hallazgos.isEmpty() && ultimo != null ? r.s( "q_none" ) : r.s( "q_hint" ), IdcDetalle.Texto.SUAVE );
    IdcDetalle.mostrar( detalle, t );
    detalle.setData( "plano", t.plano() );
  }

  // ---------------------------------------------------------------- el contenido de un hallazgo

  /** Título de la regla en el idioma del diálogo (catálogo del linter), o su id. */
  String titulo( JsonNode h ) {
    String id = h.path( "rule" ).asText();
    return lint.tiene( id + ".title" ) ? lint.t( id + ".title" ) : id;
  }

  /** «step «X»», «entrada «X»», «conexión «X»»… o "" (con el separador delante). */
  String donde( JsonNode h, String sep ) {
    String step = texto( h, "step" );
    String entry = texto( h, "entry" );
    String obj = texto( h, "object" );
    String campo = texto( h, "field" );
    String[] k = h.path( "key" ).asText().split( ":", 3 );
    String s;
    if ( k.length > 1 && "hop".equals( k[ 1 ] ) && obj != null ) {
      s = r.f( "q_where_hop", obj );
    } else if ( step != null ) {
      s = r.f( "q_where_step", step );
    } else if ( entry != null ) {
      s = r.f( "q_where_entry", entry );
    } else if ( obj != null ) {
      String scope = h.path( "key" ).asText().split( ":", 3 ).length > 1 ? h.path( "key" ).asText().split( ":", 3 )[ 1 ]
        : "";
      s = r.tiene( "q_where_" + scope ) ? r.f( "q_where_" + scope, obj ) : obj;
    } else {
      s = r.s( "q_where_doc" );
    }
    if ( campo != null ) {
      s += " · " + campo;
    }
    return sep + s;
  }

  /** La receta de harden_etl que arregla un hallazgo de robustez (evidence.recipe), o null. */
  static String receta( JsonNode h ) {
    String x = texto( h.path( "evidence" ), "recipe" );
    if ( x == null && "db_output_without_error_handling".equals( h.path( "rule" ).asText() ) ) {
      x = Receta.REJECTS_ON_ERROR;
    }
    return x != null && Receta.de( x ) != null ? x : null;
  }

  /** Tiene un arreglo propio de apply_lint_fixes. */
  static boolean tieneArreglo( JsonNode h ) {
    JsonNode f = h.get( "fix" );
    return f != null && !f.isNull() && f.has( "kind" );
  }

  /** El valor con que se aplicaría: el elegido en el detalle, o la propuesta. */
  String valorDe( JsonNode h ) {
    String v = valores.get( h.path( "key" ).asText() );
    if ( v != null ) {
      return v.trim().isEmpty() ? null : v;
    }
    return texto( h.path( "fix" ), "preview" );
  }

  /** Se puede marcar: tiene arreglo (con su valor, si lo pide) o una receta que no es manual. */
  boolean marcable( JsonNode h ) {
    if ( h == null ) {
      return false;
    }
    if ( tieneArreglo( h ) ) {
      return !h.path( "fix" ).path( "needs_value" ).asBoolean() || valorDe( h ) != null;
    }
    String x = receta( h );
    if ( x == null || Receta.MANUAL.equals( Receta.de( x ).nivel ) ) {
      return false;
    }
    Set<String> ok = recetasAplicables;
    return ok == null || ok.contains( x + "|" + texto( h, "step" ) );
  }

  /** Lo que dice la columna «Arreglo». */
  String arreglo( JsonNode h ) {
    if ( tieneArreglo( h ) ) {
      JsonNode f = h.get( "fix" );
      if ( esAuto( f ) ) {
        return r.s( "fix_auto" );
      }
      if ( valores.containsKey( h.path( "key" ).asText() ) && valorDe( h ) != null ) {
        return r.s( "fix_value_set" );
      }
      if ( f.path( "needs_value" ).asBoolean() ) {
        return valorDe( h ) != null ? r.s( "fix_value_set" ) : r.s( "fix_value" );
      }
      return r.s( "fix_confirm" );
    }
    String x = receta( h );
    if ( x != null ) {
      return !marcable( h ) ? r.s( "fix_manual" ) : r.f( "fix_recipe", tituloReceta( "harden:" + x + ":" ) );
    }
    return r.s( "fix_manual" );
  }

  /** El título localizado de la receta de una key harden:&lt;receta&gt;:&lt;step&gt; (o de su id). */
  String tituloReceta( String key ) {
    String[] p = key.split( ":", 3 );
    String id = p.length > 1 ? p[ 1 ] : key;
    Rotulos h = RobustecerAnalisis.textos( r.idioma() );
    String t = h.tiene( "recipe_" + id ) ? h.s( "recipe_" + id ) : id;
    return p.length > 2 && !p[ 2 ].isEmpty() ? t + " · " + p[ 2 ] : t;
  }

  /** Cómo arreglarlo, sin el prefijo para la IA de los de robustez. */
  String comoArreglarlo( JsonNode h ) {
    String fix = texto( h, "how_to_fix" );
    if ( fix == null ) {
      return null;
    }
    String x = receta( h );
    if ( x != null && PREFIJO_RECETA.matcher( fix ).find() ) {
      fix = r.f( "d_recipe_fix", tituloReceta( "harden:" + x + ":" ), PREFIJO_RECETA.matcher( fix ).replaceFirst( "" ) );
    }
    return fix;
  }

  /** El texto con formato de un hallazgo (el panel de abajo y la ventana de detalle). */
  IdcDetalle.Texto contenido( JsonNode h, boolean conTitulo ) {
    IdcDetalle.Texto t = new IdcDetalle.Texto();
    String id = h.path( "rule" ).asText();
    if ( conTitulo ) {
      t.titulo( titulo( h ) );
      String sev = h.path( "severity" ).asText();
      t.parrafo( r.s( "sev_" + sev ) + " · " + id + donde( h, " · " ), "error".equals( sev ) ? IdcDetalle.Texto.ROJO
        : "warning".equals( sev ) ? IdcDetalle.Texto.AMBAR : IdcDetalle.Texto.SUAVE );
    }
    t.seccion( r.s( "d_what" ) ).parrafo( h.path( "message" ).asText() );
    if ( lint.tiene( id + ".why" ) ) {
      t.seccion( r.s( "d_why" ) ).parrafo( lint.t( id + ".why" ) );
    }
    String fix = comoArreglarlo( h );
    if ( fix != null ) {
      t.seccion( r.s( "d_fix" ) ).parrafo( fix );
    }
    if ( "password_in_file".equals( id ) ) {
      String var = valorDe( h );
      String nombre = var == null ? null : io.github.ivi2511.pentahomcp.lint.reglas.ReglasSeguridad.nombreVariable( var );
      if ( nombre != null ) {
        t.parrafo( r.f( "d_password_where", var, kettleProperties() ) );
        t.codigo( nombre + "=" + r.s( "d_password_placeholder" ) );
        t.parrafo( r.s( "d_password_never" ), IdcDetalle.Texto.SUAVE );
      }
    }
    if ( !tieneArreglo( h ) ) {
      String pasos = pasos( h );
      if ( pasos != null ) {
        t.seccion( r.s( marcable( h ) ? "d_manual_alt" : "d_manual" ) ).parrafo( pasos );
      }
    }
    if ( !marcable( h ) ) {
      t.parrafo( r.s( "d_manual_claude" ), IdcDetalle.Texto.SUAVE );
    }
    JsonNode ev = h.get( "evidence" );
    if ( ev != null && ev.size() > 0 ) {
      t.seccion( r.s( "d_data" ) );
      java.util.Iterator<Map.Entry<String, JsonNode>> it = ev.fields();
      while ( it.hasNext() ) {
        Map.Entry<String, JsonNode> x = it.next();
        t.dato( x.getKey(), x.getValue().asText() );
      }
    }
    return t;
  }

  /** Los pasos a mano de una regla sin arreglo automático (idc_*.properties howto_&lt;regla&gt;), o null. */
  String pasos( JsonNode h ) {
    String id = h.path( "rule" ).asText();
    if ( !r.tiene( "howto_" + id ) ) {
      return null;
    }
    String que = texto( h, "step" ) != null ? h.path( "step" ).asText() : texto( h, "entry" ) != null ? h.path(
      "entry" ).asText() : texto( h, "object" ) != null ? h.path( "object" ).asText() : "";
    return r.f( "howto_" + id, que );
  }

  /** La ruta de kettle.properties de este Spoon (KETTLE_HOME). */
  static String kettleProperties() {
    try {
      return Const.getKettleDirectory() + File.separator + "kettle.properties";
    } catch ( Throwable t ) {
      return "kettle.properties";
    }
  }

  /** El enlace al manual de la skill para la regla (06-calidad.md; los de robustez, 09-robustez.md). */
  static String manual( JsonNode h ) {
    boolean rob = "robustness".equals( h.path( "category" ).asText() ) || receta( h ) != null;
    return IdcDetalle.MANUAL + ( rob ? "09-robustez.md" : "06-calidad.md" );
  }

  /** El pedido para Claude: el hallazgo con todo lo que hace falta para arreglarlo con las herramientas. */
  String pedido( JsonNode h ) {
    String fix = texto( h, "how_to_fix" );
    return r.f( "d_prompt_lint", servicio.nombre(), titulo( h ), h.path( "rule" ).asText(), donde( h, "" ), h.path(
      "message" ).asText(), fix == null ? "—" : fix, h.path( "key" ).asText() );
  }

  // ---------------------------------------------------------------- la ventana de detalle

  /** Doble clic o Intro: la ventana de detalle del hallazgo elegido. (UI) */
  void abrirDetalle() {
    JsonNode h = elegido();
    if ( h == null ) {
      return;
    }
    if ( ventana == null || ventana.shell.isDisposed() ) {
      ventana = new IdcDetalle.Ventana( shell, r, "quality.detail", r.f( "d_title", servicio.nombre() ) );
      final IdcDetalle.Ventana v = ventana;
      v.irAlStep.addListener( SWT.Selection, e -> {
        if ( enVentana != null ) {
          servicio.seleccionar( texto( enVentana, "step" ), texto( enVentana, "entry" ) );
        }
      } );
      v.aplicar.addListener( SWT.Selection, e -> aplicarDesdeVentana() );
      v.silenciar.addListener( SWT.Selection, e -> {
        if ( enVentana != null ) {
          silenciar( enVentana, txt -> estadoVentana( txt ) );
        }
      } );
      v.claude.addListener( SWT.Selection, e -> {
        if ( enVentana != null ) {
          IdcDetalle.copiar( shell.getDisplay(), pedido( enVentana ) );
          estadoVentana( r.s( "d_claude_copied" ) );
        }
      } );
      v.verCambios.addListener( SWT.Selection, e -> {
        if ( enVentana != null ) {
          valores.put( enVentana.path( "key" ).asText(), v.valor.getText() );
          llenarVentana( enVentana );
          refrescarFila( enVentana );
        }
      } );
      v.valor.addListener( SWT.DefaultSelection, e -> v.verCambios.notifyListeners( SWT.Selection, new org.eclipse
        .swt.widgets.Event() ) );
      llenarVentana( h );
      v.abrir( shell );
    } else {
      llenarVentana( h );
      ventana.shell.setVisible( true );
    }
  }

  /** Llena la ventana con el hallazgo y lanza el ensayo de su arreglo (o de su receta). (UI) */
  void llenarVentana( final JsonNode h ) {
    final IdcDetalle.Ventana v = ventana;
    enVentana = h;
    v.titulo.setText( titulo( h ) );
    String sev = h.path( "severity" ).asText();
    v.insignia( r.s( "sev_" + sev ), "error".equals( sev ) ? IdcDetalle.Texto.ROJO : "warning".equals( sev )
      ? IdcDetalle.Texto.AMBAR : IdcDetalle.Texto.SUAVE );
    v.donde.setText( r.f( "d_rule", h.path( "rule" ).asText() ) + donde( h, " · " ) );
    IdcDetalle.Ventana.ocultar( v.irAlStep, texto( h, "step" ) == null && texto( h, "entry" ) == null );
    String etiqueta = etiquetaValor( h );
    v.conValor( etiqueta, etiqueta == null ? null : valores.containsKey( h.path( "key" ).asText() ) ? valores.get( h
      .path( "key" ).asText() ) : texto( h.path( "fix" ), "preview" ) );
    boolean conReceta = receta( h ) != null && !tieneArreglo( h );
    v.aplicar.setText( conReceta ? r.s( "d_apply_recipe" ) : r.s( "d_apply_fix" ) );
    v.aplicar.setToolTipText( r.s( conReceta ? "d_apply_recipe_tip" : "d_apply_fix_tip" ) );
    IdcDetalle.Ventana.ocultar( v.aplicar, !tieneArreglo( h ) && !marcable( h ) );
    v.aplicar.setEnabled( marcable( h ) && !ocupado );
    v.silenciar.setEnabled( !ocupado );
    v.enlace( r.f( "d_manual_link", manual( h ).substring( manual( h ).lastIndexOf( '/' ) + 1 ), h.path( "rule" )
      .asText() ), manual( h ) );
    v.estado.setText( "" );
    final IdcDetalle.Texto base = contenido( h, false );
    boolean ensayable = marcable( h );
    if ( ensayable ) {
      IdcDetalle.Texto t = contenido( h, false );
      t.seccion( r.s( "d_changes" ) ).parrafo( r.s( "d_changes_loading" ), IdcDetalle.Texto.SUAVE );
      v.mostrar( t );
      ensayar( h, base );
    } else {
      if ( tieneArreglo( h ) && h.path( "fix" ).path( "needs_value" ).asBoolean() ) {
        base.seccion( r.s( "d_changes" ) ).parrafo( r.s( "d_needs_value" ), IdcDetalle.Texto.AMBAR );
      }
      v.mostrar( base );
    }
    v.shell.layout( true, true );
  }

  /** La etiqueta del valor editable según el arreglo (null = no se edita nada). */
  String etiquetaValor( JsonNode h ) {
    if ( !tieneArreglo( h ) ) {
      return null;
    }
    String k = h.path( "fix" ).path( "kind" ).asText();
    String regla = h.path( "rule" ).asText();
    if ( "rename".equals( k ) || "rename_entry".equals( k ) ) {
      return r.s( "d_value_name" );
    }
    if ( "set_description".equals( k ) ) {
      return r.s( "d_value_description" );
    }
    if ( "add_parameter".equals( k ) || "set_parameter_default".equals( k ) ) {
      return r.s( "d_value_default" );
    }
    if ( "set_decimal".equals( k ) ) {
      return r.s( "d_value_decimal" );
    }
    if ( "replace_value".equals( k ) ) {
      return "password_in_file".equals( regla ) ? r.s( "d_value_variable" ) : r.s( "d_value_path" );
    }
    return null;
  }

  /** El ensayo del arreglo (o de la receta) del hallazgo: los cambios exactos, en la ventana. */
  private void ensayar( final JsonNode h, final IdcDetalle.Texto base ) {
    final String k = h.path( "key" ).asText();
    final boolean conReceta = receta( h ) != null && !tieneArreglo( h );
    final Map<String, String> vals = new LinkedHashMap<String, String>();
    String v = valorDe( h );
    if ( v != null ) {
      vals.put( k, v );
    }
    final IdcDetalle.Ventana vent = ventana;
    IdcMenu.enHilo( vent.shell, "pentaholive-idc-preview", () -> conReceta ? servicio.recetas( Collections
      .singletonList( new String[] { receta( h ), texto( h, "step" ) } ), true ) : servicio.arreglar( Collections
        .singletonList( k ), vals, true, false ), o -> {
          if ( enVentana != h || vent.shell.isDisposed() ) {
            return;
          }
          IdcDetalle.Texto t = contenido( h, false );
          t.seccion( r.s( "d_changes" ) );
          if ( conReceta ) {
            cambiosReceta( t, o );
          } else {
            cambiosArreglo( t, o );
          }
          vent.mostrar( t );
        }, e -> {
          if ( enVentana != h || vent.shell.isDisposed() ) {
            return;
          }
          IdcDetalle.Texto t = contenido( h, false );
          t.seccion( r.s( "d_changes" ) ).parrafo( r.f( "d_changes_error", IdcMenu.mensaje( e ) ),
            IdcDetalle.Texto.AMBAR );
          vent.mostrar( t );
        } );
  }

  /** Lo que haría apply_lint_fixes (dry_run): cada cambio, lo que no se podría y la puntuación. */
  void cambiosArreglo( IdcDetalle.Texto t, ObjectNode o ) {
    List<String> l = new ArrayList<String>();
    for ( JsonNode x : o.path( "applied" ) ) {
      l.add( x.path( "change" ).asText() );
    }
    if ( !l.isEmpty() ) {
      t.vinetas( l );
      boolean aproximada = o.path( "notes" ).toString().contains( LintArreglos.t( r.idioma(), "note.unsaved_score" )
        .substring( 0, 20 ) );
      t.parrafo( r.f( aproximada ? "d_score_after_unsaved" : "d_score_after", o.path( "score_before" ).asInt(), o
        .path( "score_after" ).asInt() ), IdcDetalle.Texto.VERDE );
      t.parrafo( r.s( "d_undo_one" ), IdcDetalle.Texto.SUAVE );
    }
    for ( JsonNode x : o.path( "skipped" ) ) {
      t.parrafo( r.f( "d_cannot", x.path( "reason" ).asText() ), IdcDetalle.Texto.AMBAR );
    }
    for ( JsonNode x : o.path( "notes" ) ) {
      String n = x.asText();
      if ( n.contains( "kettle.properties" ) ) {
        t.parrafo( n, IdcDetalle.Texto.SUAVE );
      }
    }
  }

  /** Lo que haría harden_etl (dry_run): los cambios de cada propuesta, sus avisos, o cómo hacerlo si es a mano. */
  void cambiosReceta( IdcDetalle.Texto t, ObjectNode o ) {
    boolean alguna = false;
    for ( JsonNode p : o.path( "proposals" ) ) {
      t.parrafo( tituloReceta( p.path( "key" ).asText() ), IdcDetalle.Texto.NEGRITA );
      if ( IdcRobustez.marcable( p ) ) {
        alguna = true;
        t.codigo( p.path( "diff" ).asText() );
      }
      for ( JsonNode w : p.path( "warnings" ) ) {
        t.parrafo( "⚠ " + w.asText(), IdcDetalle.Texto.AMBAR );
      }
      if ( texto( p, "how_to" ) != null ) {
        t.parrafo( p.path( "how_to" ).asText() );
      }
    }
    if ( !alguna ) {
      t.parrafo( r.s( "d_recipe_none" ), IdcDetalle.Texto.AMBAR );
    } else {
      t.parrafo( r.s( "d_undo_one" ), IdcDetalle.Texto.SUAVE );
    }
  }

  /** «Aplicar este arreglo / esta receta» de la ventana: solo ese hallazgo. (UI) */
  void aplicarDesdeVentana() {
    final IdcDetalle.Ventana v = ventana;
    final JsonNode h = enVentana;
    if ( h == null || ocupado ) {
      return;
    }
    if ( v.filaValor.getVisible() ) {
      valores.put( h.path( "key" ).asText(), v.valor.getText() );
    }
    if ( !marcable( h ) ) {
      estadoVentana( r.s( "d_needs_value" ) );
      return;
    }
    v.aplicar.setEnabled( false );
    estadoVentana( r.s( "q_applying" ) );
    aplicar( Collections.singletonList( h ), txt -> estadoVentana( txt ) );
  }

  private void estadoVentana( String txt ) {
    if ( ventana != null && !ventana.shell.isDisposed() ) {
      ventana.estado.setText( txt == null ? "" : txt );
      ventana.shell.layout( true, true );
    }
  }

  /** Tras cambiar el valor en la ventana: la fila de la tabla (columna Arreglo y casilla). (UI) */
  private void refrescarFila( JsonNode h ) {
    int i = hallazgos.indexOf( h );
    if ( i >= 0 && i < tabla.getItemCount() ) {
      TableItem it = tabla.getItem( i );
      it.setText( 5, arreglo( h ) );
      if ( !marcable( h ) ) {
        it.setChecked( false );
      }
      contar();
    }
  }

  // ---------------------------------------------------------------- resultado

  /** Pinta un resultado de lint_etl. (UI) */
  void mostrar( ObjectNode o, boolean pedidaProfunda ) {
    ultimo = o;
    int score = o.path( "score" ).asInt();
    String grade = o.path( "grade" ).asText();
    puntaje.setText( r.f( "q_score", score, grade ) );
    puntaje.setForeground( colorNota( grade ) );
    JsonNode rb = o.get( "robustness" );
    if ( rb != null && rb.has( "score" ) ) {
      robustez.setText( r.f( "q_robust_score", rb.path( "score" ).asInt(), rb.path( "grade" ).asText() ) );
      robustez.setForeground( colorNota( rb.path( "grade" ).asText() ) );
    } else {
      robustez.setText( "" );
    }
    JsonNode c = o.path( "counts" );
    String cuentas = r.f( "q_counts", c.path( "error" ).asInt(), c.path( "warning" ).asInt(), c.path( "info" )
      .asInt() );
    int sil = o.path( "suppressed" ).size();
    if ( sil > 0 ) {
      cuentas += " · " + r.f( "q_suppressed_count", sil );
    }
    conteos.setText( cuentas );

    JsonNode antes = elegido();
    String claveAntes = antes == null ? null : antes.path( "key" ).asText( null );
    Set<String> marcadasAntes = new LinkedHashSet<String>();
    Set<String> vistasAntes = new LinkedHashSet<String>();
    for ( TableItem it : tabla.getItems() ) {
      JsonNode x = (JsonNode) it.getData();
      vistasAntes.add( x.path( "key" ).asText() );
      if ( it.getChecked() ) {
        marcadasAntes.add( x.path( "key" ).asText() );
      }
    }
    tabla.removeAll();
    hallazgos.clear();
    int volver = -1;
    for ( JsonNode h : o.path( "findings" ) ) {
      String k = h.path( "key" ).asText( null );
      if ( claveAntes != null && claveAntes.equals( k ) ) {
        volver = hallazgos.size();
      }
      hallazgos.add( h );
      TableItem it = new TableItem( tabla, SWT.NONE );
      it.setData( h );
      String sev = h.path( "severity" ).asText();
      String regla = titulo( h );
      String donde = donde( h, "" );
      it.setText( new String[] { "", r.s( "sev_" + sev ), regla, donde, una( h.path( "message" ).asText() ), arreglo(
        h ) } );
      it.setData( "tip2", regla + " (" + h.path( "rule" ).asText() + ")" );
      it.setData( "tip4", h.path( "message" ).asText() );
      it.setData( "tip5", comoArreglarlo( h ) == null ? arreglo( h ) : arreglo( h ) + ": " + comoArreglarlo( h ) );
      it.setForeground( 1, "error".equals( sev ) ? rojo : "warning".equals( sev ) ? ambar : gris );
      boolean m = marcable( h );
      // conserva lo que la persona marcó o desmarcó; lo nuevo empieza marcado solo si es automático
      it.setChecked( m && ( vistasAntes.contains( k ) ? marcadasAntes.contains( k ) : esAuto( h.get( "fix" ) ) ) );
      if ( !m ) {
        it.setForeground( 5, gris );
      } else if ( !esAuto( h.get( "fix" ) ) ) {
        it.setForeground( 5, ambar );
      }
    }
    contar();
    silenciar.setEnabled( false );
    verDetalle.setEnabled( false );
    mostrarAyuda();
    recetasDeFondo();
    if ( volver >= 0 ) {
      // el mismo hallazgo sigue: queda elegido (sin volver a tocar el lienzo)
      tabla.setSelection( volver );
      elegida( false );
    }
    if ( ventana != null && !ventana.shell.isDisposed() && enVentana != null ) {
      JsonNode mismo = null;
      for ( JsonNode h : hallazgos ) {
        if ( h.path( "key" ).asText().equals( enVentana.path( "key" ).asText() ) ) {
          mismo = h;
        }
      }
      if ( mismo != null ) {
        String est = ventana.estado.getText();
        llenarVentana( mismo );
        estadoVentana( est );
      } else {
        ventana.aplicar.setEnabled( false );
        ventana.silenciar.setEnabled( false );
        estadoVentana( ventana.estado.getText() + ( ventana.estado.getText().isEmpty() ? "" : "  " ) + r.s(
          "d_gone" ) );
      }
    }

    List<String> notas = new ArrayList<String>();
    JsonNode d = o.path( "deep" );
    if ( d.path( "ran" ).asBoolean() && d.path( "probes" ).asInt() > 0 ) {
      notas.add( r.f( "q_deep_ran", d.path( "probes" ).asInt(), d.path( "ms" ).asLong() / 1000.0, d.path( "raised" )
        .asInt(), d.path( "lowered" ).asInt(), d.path( "not_evaluated" ).asInt() ) );
    } else if ( d.path( "ran" ).asBoolean() ) {
      notas.add( r.s( "q_deep_nothing" ) );
    } else if ( pedidaProfunda && texto( d, "reason" ) != null ) {
      notas.add( r.f( "q_deep_not_run", d.path( "reason" ).asText() ) );
    }
    JsonNode ne = o.path( "not_evaluated" );
    if ( ne.size() > 0 ) {
      Map<String, String> porRegla = new LinkedHashMap<String, String>();
      for ( JsonNode x : ne ) {
        String q = x.path( "rule" ).asText();
        String w = texto( x, "step" ) != null ? x.path( "step" ).asText() : texto( x, "entry" );
        String nombre = lint.tiene( q + ".title" ) ? lint.t( q + ".title" ) : q;
        String clave = w == null ? nombre : nombre + " («" + w + "»)";
        if ( !porRegla.containsKey( clave ) ) {
          porRegla.put( clave, x.path( "reason" ).asText() );
        }
      }
      List<String> l = new ArrayList<String>();
      for ( Map.Entry<String, String> e : porRegla.entrySet() ) {
        l.add( e.getValue().isEmpty() ? e.getKey() : e.getKey() + ": " + e.getValue() );
      }
      String lista = String.join( "; ", l.size() > 3 ? l.subList( 0, 3 ) : l ) + ( l.size() > 3 ? "…" : "" );
      notas.add( r.f( "q_not_evaluated", ne.size(), lista ) );
      pie.setToolTipText( IdcDetalle.partir( String.join( "\n", l ), 100 ) );
    } else {
      pie.setToolTipText( null );
    }
    pie.setText( String.join( "\n", notas ) );
    shell.layout( true, true );
  }

  /**
   * Un ensayo de harden_etl con las recetas de los hallazgos de robustez, en segundo plano: cuáles tienen de verdad un
   * cambio automático en su step. Las que no, pasan a «a mano» (con sus pasos y el pedido para Claude). (UI)
   */
  private void recetasDeFondo() {
    final List<String[]> pares = new ArrayList<String[]>();
    for ( JsonNode h : hallazgos ) {
      String x = receta( h );
      if ( x != null && !tieneArreglo( h ) && !Receta.MANUAL.equals( Receta.de( x ).nivel ) ) {
        pares.add( new String[] { x, texto( h, "step" ) } );
      }
    }
    recetasAplicables = null;
    if ( pares.isEmpty() ) {
      return;
    }
    final int gen = ++genRecetas;
    IdcMenu.enHilo( shell, "pentaholive-idc-recipes", () -> servicio.recetas( pares, true ), o -> {
      if ( gen != genRecetas || o == null ) {
        return;
      }
      Set<String> ok = new java.util.HashSet<String>();
      for ( JsonNode p : o.path( "proposals" ) ) {
        if ( IdcRobustez.marcable( p ) ) {
          ok.add( p.path( "recipe" ).asText() + "|" + p.path( "step" ).asText() );
        }
      }
      recetasAplicables = ok;
      for ( int i = 0; i < tabla.getItemCount() && i < hallazgos.size(); i++ ) {
        JsonNode h = hallazgos.get( i );
        if ( receta( h ) == null || tieneArreglo( h ) ) {
          continue;
        }
        TableItem it = tabla.getItem( i );
        it.setText( 5, arreglo( h ) );
        it.setData( "tip5", comoArreglarlo( h ) == null ? arreglo( h ) : arreglo( h ) + ": " + comoArreglarlo( h ) );
        if ( !marcable( h ) ) {
          it.setChecked( false );
          it.setForeground( 5, gris );
        }
      }
      contar();
    }, e -> McpLifecycleListener.LOG.logDebug( "Pentaho Live: harden_etl dry run for the quality dialog: " + e ) );
  }

  /** Lo que aplica apply_lint_fixes keys=[all_auto]: auto, sin confirm y sin valor que pedir (LintArreglos). */
  static boolean esAuto( JsonNode f ) {
    return f != null && f.path( "auto" ).asBoolean() && !f.path( "confirm" ).asBoolean() && !f.path( "needs_value" )
      .asBoolean();
  }

  private int marcadas() {
    int n = 0;
    for ( TableItem it : tabla.getItems() ) {
      if ( it.getChecked() && marcable( (JsonNode) it.getData() ) ) {
        n++;
      }
    }
    return n;
  }

  void contar() {
    int n = marcadas();
    aplicar.setText( r.f( "q_apply", n ) );
    aplicar.setEnabled( n > 0 && !ocupado );
    aplicar.getParent().layout( true );
  }

  private Color colorNota( String g ) {
    if ( "A".equals( g ) ) {
      return verde;
    }
    if ( "B".equals( g ) ) {
      return shell.getDisplay().getSystemColor( SWT.COLOR_WIDGET_FOREGROUND );
    }
    return "C".equals( g ) ? ambar : rojo;
  }

  private void ocupado( String texto, boolean cancelable ) {
    ocupado = true;
    estado.setText( texto );
    barra.setVisible( true );
    cancelar.setEnabled( cancelable );
    revisar.setEnabled( false );
    aplicar.setEnabled( false );
    silenciar.setEnabled( false );
    profunda.setEnabled( false );
    conRobustez.setEnabled( false );
    if ( ventana != null && !ventana.shell.isDisposed() ) {
      ventana.aplicar.setEnabled( false );
      ventana.silenciar.setEnabled( false );
    }
    shell.layout( true );
  }

  private void libre( String texto ) {
    if ( shell.isDisposed() ) {
      return;
    }
    ocupado = false;
    estado.setText( texto == null ? "" : texto );
    barra.setVisible( false );
    cancelar.setEnabled( false );
    revisar.setEnabled( true );
    profunda.setEnabled( true );
    conRobustez.setEnabled( true );
    contar();
    silenciar.setEnabled( elegido() != null );
    if ( ventana != null && !ventana.shell.isDisposed() && enVentana != null ) {
      ventana.aplicar.setEnabled( marcable( enVentana ) );
      ventana.silenciar.setEnabled( true );
    }
    shell.layout( true );
  }

  // ---------------------------------------------------------------- utilidades

  static String texto( JsonNode o, String k ) {
    JsonNode v = o == null ? null : o.get( k );
    if ( v == null || v.isNull() ) {
      return null;
    }
    String s = v.asText();
    return s.trim().isEmpty() ? null : s;
  }

  /** Una línea para la celda (el texto entero va en el detalle y en el tooltip). */
  static String una( String s ) {
    return s == null ? "" : s.replace( '\n', ' ' ).replace( '\r', ' ' );
  }

  /** La letra del control en negrita y escala veces más grande; se libera con el control. */
  static Font negrita( Control c, double escala ) {
    FontData[] fd = c.getFont().getFontData();
    for ( FontData f : fd ) {
      f.setHeight( (int) Math.round( f.getHeight() * escala ) );
      f.setStyle( SWT.BOLD );
    }
    final Font f = new Font( c.getDisplay(), fd );
    c.addListener( SWT.Dispose, e -> f.dispose() );
    return f;
  }

  // ---------------------------------------------------------------- el servicio de Spoon

  /** Llama a las herramientas MCP del linter y de robustez (los mismos métodos, los mismos argumentos). */
  static final class ServicioSpoon implements Servicio {
    final String doc;
    final String nombre;
    final String lang;

    ServicioSpoon( String doc, String nombre, String lang ) {
      this.doc = doc;
      this.nombre = nombre;
      this.lang = lang;
    }

    @Override
    public String nombre() {
      return nombre;
    }

    @Override
    public ObjectNode revisar( boolean prof, boolean rob, Consumer<Runnable> alCancelar ) throws Exception {
      ObjectNode a = Tools.obj().put( "document", doc ).put( "language", lang ).put( "deep", prof )
        .put( "robustness", rob );
      return LintTools.lint( a, alCancelar );
    }

    @Override
    public ObjectNode arreglar( Collection<String> keys, Map<String, String> valores, boolean ensayo,
                                boolean continuar ) throws Exception {
      ObjectNode a = Tools.obj().put( "document", doc ).put( "language", lang ).put( "dry_run", ensayo );
      ArrayNode k = a.putArray( "keys" );
      for ( String x : keys ) {
        k.add( x );
      }
      if ( valores != null && !valores.isEmpty() ) {
        ObjectNode v = a.putObject( "values" );
        for ( Map.Entry<String, String> e : valores.entrySet() ) {
          v.put( e.getKey(), e.getValue() );
        }
      }
      return (ObjectNode) McpHttpServer.JSON.readTree( (String) LintTools.arreglar( a, continuar ) );
    }

    @Override
    public ObjectNode recetas( List<String[]> pares, boolean ensayo ) throws Exception {
      return recetasDe( doc, lang, pares, ensayo );
    }

    @Override
    public ObjectNode silenciar( String regla, String step, String entry, String motivo ) throws Exception {
      ObjectNode a = Tools.obj().put( "document", doc ).put( "rule", regla );
      if ( step != null ) {
        a.put( "step", step );
      }
      if ( entry != null ) {
        a.put( "entry", entry );
      }
      if ( motivo != null ) {
        a.put( "reason", motivo );
      }
      return (ObjectNode) LintTools.silenciar( a );
    }

    @Override
    public void seleccionar( String step, String entry ) {
      try {
        AbstractMeta m = Docs.resolverSinActivar( Tools.obj().put( "document", doc ) );
        Docs.activar( m );
        TransTools.seleccionar( m, Collections.singletonList( step != null ? step : entry ) );
      } catch ( Throwable t ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not select '" + ( step != null ? step : entry )
          + "' on the canvas: " + t );
      }
    }
  }

  /**
   * harden_etl solo con estas recetas en estos steps: un ensayo con recipes y steps para saber las keys, y (si no es
   * ensayo) la aplicación con esas keys confirmadas y las demás auto excluidas. La respuesta del ensayo se filtra a las
   * propuestas pedidas.
   */
  static ObjectNode recetasDe( String doc, String lang, List<String[]> pares, boolean ensayo ) throws Exception {
    Set<String> recetas = new LinkedHashSet<String>();
    Set<String> steps = new LinkedHashSet<String>();
    for ( String[] p : pares ) {
      recetas.add( p[ 0 ] );
      if ( p[ 1 ] != null ) {
        steps.add( p[ 1 ] );
      }
    }
    ObjectNode a = Tools.obj().put( "document", doc ).put( "dry_run", true );
    ArrayNode rs = a.putArray( "recipes" );
    for ( String x : recetas ) {
      rs.add( x );
    }
    if ( !steps.isEmpty() ) {
      ArrayNode ss = a.putArray( "steps" );
      for ( String x : steps ) {
        ss.add( x );
      }
    }
    ObjectNode plan = (ObjectNode) McpHttpServer.JSON.readTree( (String) RobustezTools.robustecer( a, null, lang ) );
    Set<String> quiero = new LinkedHashSet<String>();
    Set<String> excluir = new LinkedHashSet<String>();
    ArrayNode filtradas = Tools.arr();
    for ( JsonNode p : plan.path( "proposals" ) ) {
      boolean pedida = false;
      for ( String[] x : pares ) {
        pedida |= x[ 0 ].equals( p.path( "recipe" ).asText() );
      }
      if ( pedida ) {
        filtradas.add( p );
        if ( IdcRobustez.marcable( p ) ) {
          quiero.add( p.path( "key" ).asText() );
        }
      } else if ( Receta.AUTO.equals( p.path( "level" ).asText() ) ) {
        excluir.add( p.path( "key" ).asText() );
      }
    }
    if ( ensayo ) {
      plan.set( "proposals", filtradas );
      return plan;
    }
    if ( quiero.isEmpty() ) {
      ObjectNode o = Tools.obj().put( "document", doc ).put( "dry_run", false );
      o.putArray( "applied" );
      o.putArray( "not_applied" );
      return o;
    }
    ObjectNode b = a.deepCopy().put( "dry_run", false );
    ArrayNode c = b.putArray( "confirm" );
    for ( String k : quiero ) {
      c.add( k );
    }
    return (ObjectNode) McpHttpServer.JSON.readTree( (String) RobustezTools.robustecer( b, excluir, lang, false ) );
  }
}
