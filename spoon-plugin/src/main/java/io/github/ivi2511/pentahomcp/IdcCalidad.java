package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
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
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.base.AbstractMeta;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * «Revisar calidad…» del menú IDC ({@link IdcMenu}): el linter del documento activo en una ventana no modal. Puntuación y
 * nota, hallazgos (gravedad, regla, dónde, mensaje, cómo arreglarlo, si tiene arreglo automático), clic en un hallazgo =
 * su step o entrada seleccionado en el lienzo, «Aplicar arreglos automáticos» (apply_lint_fixes keys=[all_auto]: un
 * Ctrl+Z en una transformación), «Silenciar esta regla aquí» (suppress_lint), «Revisión profunda (lee datos)» (lint_etl
 * deep=true, con su misma regla de solo lectura) e «Incluir robustez» (robustness=true).
 * <p>
 * La lógica no está aquí: el {@link Servicio} de Spoon llama a {@link LintTools#lint}, {@link LintTools#arreglar} y
 * {@link LintTools#silenciar} con los argumentos JSON de las herramientas MCP, en un hilo de trabajo
 * ({@link IdcMenu#enHilo}); lo largo (la revisión) se puede cancelar. Las pruebas usan un servicio falso. (UI)
 */
final class IdcCalidad {

  /** Lo que el diálogo pide afuera. revisar/arreglarAuto/silenciar corren fuera del hilo de UI; seleccionar, en él. */
  interface Servicio {
    String nombre();

    /** lint_etl. alCancelar recibe con qué cortar el análisis (puede no llamarse). */
    ObjectNode revisar( boolean profunda, boolean robustez, Consumer<Runnable> alCancelar ) throws Exception;

    /** apply_lint_fixes keys=["all_auto"]. */
    ObjectNode arreglarAuto() throws Exception;

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

  /** El diálogo abierto (uno a la vez). Solo en el hilo de UI. */
  static IdcCalidad abierto;

  final Shell shell;
  final Servicio servicio;
  final Rotulos r;
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
  final Text detalle;
  final Label pie;
  final Button aplicar;
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
        if ( IdcMenu.posicionPrueba == null ) {
          abierto.shell.setActive();
        }
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
    this.docId = docId;
    shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.MODELESS );
    shell.setText( r.f( "q_title", s.nombre() ) );
    final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
    if ( ico != null ) {
      shell.setImage( ico );
      shell.addListener( SWT.Dispose, e -> ico.dispose() );
    }
    rojo = new Color( shell.getDisplay(), 190, 30, 30 );
    ambar = new Color( shell.getDisplay(), 170, 105, 0 );
    gris = new Color( shell.getDisplay(), 100, 100, 100 );
    verde = new Color( shell.getDisplay(), 30, 130, 60 );
    shell.addListener( SWT.Dispose, e -> {
      rojo.dispose();
      ambar.dispose();
      gris.dispose();
      verde.dispose();
      cortar();
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

    // progreso a la derecha de las opciones; el estado, en su propia línea debajo
    Composite pr = op;
    new Label( pr, SWT.NONE ).setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    barra = new ProgressBar( pr, SWT.HORIZONTAL | SWT.INDETERMINATE );
    GridData gb = new GridData( SWT.RIGHT, SWT.CENTER, false, false );
    gb.widthHint = 160;
    barra.setLayoutData( gb );
    barra.setVisible( false );
    cancelar = new Button( pr, SWT.PUSH );
    cancelar.setText( r.s( "cancel" ) );
    cancelar.setEnabled( false );
    cancelar.addListener( SWT.Selection, e -> cancelar() );
    estado = new Label( shell, SWT.WRAP );
    estado.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

    // hallazgos
    tabla = new Table( shell, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL );
    tabla.setHeaderVisible( true );
    tabla.setLinesVisible( true );
    GridData gt = new GridData( SWT.FILL, SWT.FILL, true, true );
    gt.heightHint = 260;
    tabla.setLayoutData( gt );
    columna( "col_severity", 75 );
    columna( "col_rule", 150 );
    columna( "col_where", 150 );
    columna( "col_message", 320 );
    columna( "col_fix", 250 );
    columna( "col_autofix", 100 );
    tabla.addListener( SWT.Selection, e -> elegida( true ) );

    detalle = new Text( shell, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL );
    GridData gd = new GridData( SWT.FILL, SWT.TOP, true, false );
    gd.heightHint = 84;
    detalle.setLayoutData( gd );
    detalle.setText( r.s( "q_hint" ) );

    pie = new Label( shell, SWT.WRAP );
    pie.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    pie.setForeground( gris );

    // botones
    Composite bo = new Composite( shell, SWT.NONE );
    bo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gbo = new GridLayout( 3, false );
    gbo.marginWidth = 0;
    bo.setLayout( gbo );
    aplicar = new Button( bo, SWT.PUSH );
    aplicar.setText( r.f( "q_apply", 0 ) );
    aplicar.setToolTipText( r.s( "q_apply_tip" ) );
    aplicar.setEnabled( false );
    aplicar.addListener( SWT.Selection, e -> aplicarAuto() );
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

    shell.setSize( 1080, 640 );
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

  /** apply_lint_fixes keys=[all_auto] y otra revisión para ver la tabla nueva. (UI) */
  void aplicarAuto() {
    if ( ocupado ) {
      return;
    }
    ocupado( r.s( "q_applying" ), false );
    IdcMenu.enHilo( shell, "pentaholive-idc-fix", () -> servicio.arreglarAuto(), o -> {
      int n = o.path( "applied" ).size();
      int pasos = o.path( "undo_steps" ).asInt( 1 );
      String txt = n == 0 ? r.s( "q_applied_none" ) : r.f( "q_applied", n, pasos <= 1 ? r.s( "q_undo_one" )
        : r.f( "q_undo_n", pasos ), o.path( "score_before" ).asInt(), o.path( "score_after" ).asInt() );
      List<String> no = new ArrayList<String>();
      for ( JsonNode x : o.path( "skipped" ) ) {
        no.add( x.path( "key" ).asText() + ": " + x.path( "reason" ).asText() );
      }
      if ( !no.isEmpty() ) {
        txt += "  " + r.f( "q_skipped", String.join( "; ", no ) );
      }
      libre( txt );
      pendiente = txt;
      revisar();
    }, e -> libre( r.f( "q_apply_error", IdcMenu.mensaje( e ) ) ) );
  }

  /** suppress_lint de la regla del hallazgo elegido, en su step o entrada (o en el documento). (UI) */
  void silenciarElegido() {
    final JsonNode h = elegido();
    if ( h == null || ocupado ) {
      return;
    }
    final String regla = h.path( "rule" ).asText();
    final String step = texto( h, "step" );
    final String entry = step == null ? texto( h, "entry" ) : null;
    final String donde = step != null ? r.f( "q_where_step", step ) : entry != null ? r.f( "q_where_entry", entry )
      : r.s( "q_where_doc" );
    String motivo = preguntador.pedir( shell, r.f( "q_suppress_title", regla ), r.f( "error".equals( h.path(
      "severity" ).asText() ) ? "q_suppress_prompt_error" : "q_suppress_prompt", donde ) );
    if ( motivo == null ) {
      return;
    }
    final String m = motivo.trim().isEmpty() ? null : motivo.trim();
    ocupado( r.s( "q_suppressing" ), false );
    IdcMenu.enHilo( shell, "pentaholive-idc-suppress", () -> servicio.silenciar( regla, step, entry, m ), o -> {
      String txt = r.f( "q_suppressed", regla, donde );
      libre( txt );
      pendiente = txt;
      revisar();
    }, e -> libre( r.f( "q_suppress_error", IdcMenu.mensaje( e ) ) ) );
  }

  /** Clic en un hallazgo: el detalle y, si conLienzo, su step o entrada seleccionado en el lienzo. (UI) */
  void elegida( boolean conLienzo ) {
    JsonNode h = elegido();
    silenciar.setEnabled( h != null && !ocupado );
    if ( h == null ) {
      return;
    }
    StringBuilder b = new StringBuilder();
    b.append( h.path( "message" ).asText() );
    String fix = texto( h, "how_to_fix" );
    if ( fix != null ) {
      b.append( "\n\n" ).append( r.s( "q_detail_fix" ) ).append( ' ' ).append( fix );
    }
    JsonNode ev = h.get( "evidence" );
    if ( ev != null && ev.size() > 0 ) {
      b.append( "\n" );
      java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = ev.fields();
      while ( it.hasNext() ) {
        java.util.Map.Entry<String, JsonNode> x = it.next();
        b.append( "\n· " ).append( x.getKey() ).append( ": " ).append( x.getValue().asText() );
      }
    }
    detalle.setText( b.toString() );
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
    tabla.removeAll();
    hallazgos.clear();
    int auto = 0;
    int volver = -1;
    for ( JsonNode h : o.path( "findings" ) ) {
      if ( claveAntes != null && claveAntes.equals( h.path( "key" ).asText( null ) ) ) {
        volver = hallazgos.size();
      }
      hallazgos.add( h );
      TableItem it = new TableItem( tabla, SWT.NONE );
      String sev = h.path( "severity" ).asText();
      String donde = texto( h, "step" );
      if ( donde == null ) {
        donde = texto( h, "entry" );
      }
      if ( donde == null ) {
        donde = texto( h, "object" );
      }
      if ( donde != null && texto( h, "field" ) != null ) {
        donde += " · " + h.path( "field" ).asText();
      }
      it.setText( new String[] { r.s( "sev_" + sev ), h.path( "rule" ).asText(), donde == null ? "" : donde,
        una( h.path( "message" ).asText() ), una( h.path( "how_to_fix" ).asText() ), arreglo( h.get( "fix" ) ) } );
      it.setForeground( 0, "error".equals( sev ) ? rojo : "warning".equals( sev ) ? ambar : gris );
      if ( esAuto( h.get( "fix" ) ) ) {
        auto++;
      }
    }
    aplicar.setText( r.f( "q_apply", auto ) );
    aplicar.setEnabled( auto > 0 );
    silenciar.setEnabled( false );
    detalle.setText( hallazgos.isEmpty() ? r.s( "q_none" ) : r.s( "q_hint" ) );
    if ( volver >= 0 ) {
      // el mismo hallazgo sigue: queda elegido (sin volver a tocar el lienzo)
      tabla.setSelection( volver );
      elegida( false );
    }

    List<String> notas = new ArrayList<String>();
    JsonNode d = o.path( "deep" );
    if ( d.path( "ran" ).asBoolean() ) {
      notas.add( r.f( "q_deep_ran", d.path( "probes" ).asInt(), d.path( "ms" ).asLong() / 1000.0, d.path( "raised" )
        .asInt(), d.path( "lowered" ).asInt(), d.path( "not_evaluated" ).asInt() ) );
    } else if ( pedidaProfunda && texto( d, "reason" ) != null ) {
      notas.add( r.f( "q_deep_not_run", d.path( "reason" ).asText() ) );
    }
    JsonNode ne = o.path( "not_evaluated" );
    if ( ne.size() > 0 ) {
      Set<String> reglas = new LinkedHashSet<String>();
      for ( JsonNode x : ne ) {
        String q = x.path( "rule" ).asText();
        String w = texto( x, "step" ) != null ? x.path( "step" ).asText() : texto( x, "entry" );
        reglas.add( w == null ? q : q + " (" + w + ")" );
      }
      List<String> l = new ArrayList<String>( reglas );
      String lista = String.join( ", ", l.size() > 4 ? l.subList( 0, 4 ) : l ) + ( l.size() > 4 ? "…" : "" );
      notas.add( r.f( "q_not_evaluated", ne.size(), lista ) );
    }
    pie.setText( String.join( "\n", notas ) );
    shell.layout( true, true );
  }

  private String arreglo( JsonNode f ) {
    if ( f == null || f.isNull() ) {
      return "";
    }
    if ( esAuto( f ) ) {
      return r.s( "fix_auto" );
    }
    if ( f.path( "needs_value" ).asBoolean() ) {
      return r.s( "fix_value" );
    }
    return r.s( "fix_confirm" );
  }

  /** Lo que aplica apply_lint_fixes keys=[all_auto]: auto, sin confirm y sin valor que pedir (LintArreglos). */
  static boolean esAuto( JsonNode f ) {
    return f != null && f.path( "auto" ).asBoolean() && !f.path( "confirm" ).asBoolean() && !f.path( "needs_value" )
      .asBoolean();
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
    int auto = 0;
    for ( JsonNode h : hallazgos ) {
      if ( esAuto( h.get( "fix" ) ) ) {
        auto++;
      }
    }
    aplicar.setEnabled( auto > 0 );
    silenciar.setEnabled( elegido() != null );
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

  /** Una línea para la celda (el texto entero va en el detalle). */
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

  /** Un texto de lo que muestra (pruebas). (UI) */
  String resumen() {
    StringBuilder b = new StringBuilder();
    b.append( "title=" ).append( shell.getText() ).append( "\nscore=" ).append( puntaje.getText() )
      .append( "\nrobustness=" ).append( robustez.getText() ).append( "\ncounts=" ).append( conteos.getText() )
      .append( "\nstatus=" ).append( estado.getText() ).append( "\nbusy=" ).append( ocupado )
      .append( "\nrows=" ).append( tabla.getItemCount() ).append( "\napply=" ).append( aplicar.getText() )
      .append( aplicar.getEnabled() ? "" : " [off]" ).append( "\nfooter=" ).append( pie.getText() );
    for ( int i = 0; i < tabla.getItemCount() && i < 40; i++ ) {
      TableItem it = tabla.getItem( i );
      b.append( "\n  " ).append( it.getText( 0 ) ).append( " | " ).append( it.getText( 1 ) ).append( " | " )
        .append( it.getText( 2 ) ).append( " | " ).append( it.getText( 5 ) );
    }
    return b.toString();
  }

  // ---------------------------------------------------------------- gancho de la prueba en vivo

  /** Toca el diálogo abierto (ver {@link IdcMenu#prueba}): estado, profunda, robustez, fila:i, aplicar, silenciar:i, cerrar. (UI) */
  static String prueba( String que ) {
    IdcCalidad d = abierto;
    if ( d == null || d.shell.isDisposed() ) {
      return "no quality dialog";
    }
    if ( que.equals( "profunda" ) || que.equals( "robustez" ) ) {
      ( que.equals( "profunda" ) ? d.profunda : d.conRobustez ).setSelection( true );
      d.revisar();
    } else if ( que.equals( "revisar" ) ) {
      d.revisar();
    } else if ( que.equals( "cancelar" ) ) {
      d.cancelar();
    } else if ( que.startsWith( "fila:" ) ) {
      d.tabla.setSelection( Integer.parseInt( que.substring( 5 ) ) );
      d.elegida( true );
    } else if ( que.equals( "aplicar" ) ) {
      d.aplicarAuto();
    } else if ( que.startsWith( "silenciar:" ) ) {
      d.tabla.setSelection( Integer.parseInt( que.substring( 10 ) ) );
      d.elegida( false );
      Preguntador antes = preguntador;
      preguntador = ( p, t, x ) -> "IDC live test";
      try {
        d.silenciarElegido();
      } finally {
        preguntador = antes;
      }
    } else if ( que.equals( "cerrar" ) ) {
      d.shell.close();
      return "closed";
    }
    return d.resumen();
  }

  // ---------------------------------------------------------------- el servicio de Spoon

  /** Llama a las herramientas MCP del linter (los mismos métodos, los mismos argumentos). */
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
    public ObjectNode arreglarAuto() throws Exception {
      ObjectNode a = Tools.obj().put( "document", doc ).put( "language", lang );
      a.putArray( "keys" ).add( "all_auto" );
      return (ObjectNode) McpHttpServer.JSON.readTree( (String) LintTools.arreglar( a ) );
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
}
