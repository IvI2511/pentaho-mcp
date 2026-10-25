package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * «Robustecer…» del menú IDC ({@link IdcMenu}): el plan de harden_etl (dry_run, todas las recetas) como una lista con
 * casillas, «Aplicar» (un Ctrl+Z) y «Probar con datos sucios…» (stress_test en segundo plano, cancelable).
 * <p>
 * Casillas: las propuestas auto van marcadas; las confirm y las que cambian la semántica de la carga
 * (semantics_change) van desmarcadas y con ⚠; las manuales no se pueden marcar (muestran cómo hacerlo). Al aplicar,
 * las confirm marcadas van en {@code confirm} y las auto desmarcadas en la lista de excluidas de
 * {@link RobustezTools#robustecer(com.fasterxml.jackson.databind.JsonNode, Set, String)}: el mismo camino que harden_etl
 * dry_run=false. La prueba con datos sucios es {@link EstresTools#probar(com.fasterxml.jackson.databind.JsonNode,
 * EstresProcesos.Corte)}: el veredicto, los problemas del entorno aparte (no son fallos de robustez), cada comprobación
 * por variante y los mensajes. (UI)
 */
final class IdcRobustez {

  /** Lo que el diálogo pide afuera; todo corre fuera del hilo de UI. */
  interface Servicio {
    String nombre();

    /** harden_etl recipes=[all] dry_run=true. */
    ObjectNode plan() throws Exception;

    /** harden_etl recipes=[all] dry_run=false confirm=confirmar, sin las auto de excluir. */
    ObjectNode aplicar( Set<String> confirmar, Set<String> excluir ) throws Exception;

    /** stress_test con un corte para cancelarla. */
    ObjectNode probar( EstresProcesos.Corte corte ) throws Exception;
  }

  /** El diálogo abierto (uno a la vez). Solo en el hilo de UI. */
  static IdcRobustez abierto;

  final Shell shell;
  final Servicio servicio;
  final Rotulos r;
  final String docId;

  final Label puntaje;
  final Label intro;
  final Table tabla;
  final Text detalle;
  final Label estado;
  final ProgressBar barra;
  final Label reloj;
  final Button cancelar;
  final Button aplicar;
  final Button probar;
  final Button cerrar;
  final Group grupoPrueba;
  final Label veredicto;
  final Label entorno;
  final Table comprobaciones;
  final Text mensajes;

  private final Color rojo;
  private final Color ambar;
  private final Color verde;
  private final Color gris;

  /** Las propuestas de la tabla, en orden. */
  final List<JsonNode> propuestas = new ArrayList<JsonNode>();
  ObjectNode plan;
  ObjectNode prueba;
  volatile boolean ocupado;
  private int generacion;
  private EstresProcesos.Corte corte;
  private long inicio;
  /** El resumen de la última prueba (lo que muestra la caja de mensajes). */
  private String resumenPrueba = "";

  // ---------------------------------------------------------------- apertura

  static void abrir( Shell padre, TransMeta m, Rotulos r ) {
    String id = Docs.id( m );
    if ( abierto != null && !abierto.shell.isDisposed() ) {
      if ( id.equals( abierto.docId ) ) {
        abierto.shell.setActive();
        return;
      }
      abierto.shell.close();
    }
    abierto = new IdcRobustez( padre, new ServicioSpoon( id, Docs.nombre( m ), r.idioma() ), r, id );
    abierto.abrir();
  }

  IdcRobustez( Shell padre, Servicio s, Rotulos r, String docId ) {
    this.servicio = s;
    this.r = r;
    this.docId = docId;
    shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.MODELESS );
    shell.setText( r.f( "r_title", s.nombre() ) );
    final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
    if ( ico != null ) {
      shell.setImage( ico );
      shell.addListener( SWT.Dispose, e -> ico.dispose() );
    }
    rojo = new Color( shell.getDisplay(), 190, 30, 30 );
    ambar = new Color( shell.getDisplay(), 170, 105, 0 );
    verde = new Color( shell.getDisplay(), 30, 130, 60 );
    gris = new Color( shell.getDisplay(), 100, 100, 100 );
    shell.addListener( SWT.Dispose, e -> {
      rojo.dispose();
      ambar.dispose();
      verde.dispose();
      gris.dispose();
      cortarPrueba();
      if ( abierto == this ) {
        abierto = null;
      }
    } );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 12;
    gl.marginHeight = 10;
    shell.setLayout( gl );

    puntaje = new Label( shell, SWT.NONE );
    puntaje.setFont( IdcCalidad.negrita( puntaje, 1.3 ) );
    puntaje.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    intro = new Label( shell, SWT.WRAP );
    GridData gi = new GridData( SWT.FILL, SWT.TOP, true, false );
    gi.widthHint = 900;
    intro.setLayoutData( gi );
    intro.setText( r.s( "r_intro" ) );

    SashForm sash = new SashForm( shell, SWT.VERTICAL );
    sash.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );

    Composite arriba = new Composite( sash, SWT.NONE );
    GridLayout ga = new GridLayout( 1, false );
    ga.marginWidth = 0;
    ga.marginHeight = 0;
    arriba.setLayout( ga );
    tabla = new Table( arriba, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL
      | SWT.H_SCROLL );
    tabla.setHeaderVisible( true );
    tabla.setLinesVisible( true );
    tabla.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    columna( tabla, "col_apply", 70 );
    columna( tabla, "col_recipe", 170 );
    columna( tabla, "col_level", 100 );
    columna( tabla, "col_step", 170 );
    columna( tabla, "col_why", 400 );
    columna( tabla, "col_semantics", 150 );
    tabla.addListener( SWT.Selection, e -> {
      if ( e.detail == SWT.CHECK ) {
        TableItem it = (TableItem) e.item;
        JsonNode p = (JsonNode) it.getData();
        if ( !marcable( p ) ) {
          it.setChecked( false ); // las manuales no se aplican nunca
        }
        contar();
      }
      elegida( e.item instanceof TableItem ? tabla.indexOf( (TableItem) e.item ) : tabla.getSelectionIndex() );
    } );
    detalle = new Text( arriba, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL );
    GridData gd = new GridData( SWT.FILL, SWT.FILL, true, false );
    gd.heightHint = 90;
    detalle.setLayoutData( gd );

    grupoPrueba = new Group( sash, SWT.NONE );
    grupoPrueba.setText( r.s( "r_stress" ).replace( "…", "" ) );
    grupoPrueba.setLayout( new GridLayout( 1, false ) );
    veredicto = new Label( grupoPrueba, SWT.WRAP );
    veredicto.setFont( IdcCalidad.negrita( veredicto, 1.15 ) );
    veredicto.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    entorno = new Label( grupoPrueba, SWT.WRAP );
    GridData ge = new GridData( SWT.FILL, SWT.TOP, true, false );
    ge.exclude = true;
    entorno.setLayoutData( ge );
    entorno.setVisible( false );
    comprobaciones = new Table( grupoPrueba, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL );
    comprobaciones.setHeaderVisible( true );
    comprobaciones.setLinesVisible( true );
    GridData gk = new GridData( SWT.FILL, SWT.FILL, true, true );
    gk.minimumHeight = 90;
    gk.heightHint = 120;
    comprobaciones.setLayoutData( gk );
    columna( comprobaciones, "col_variant", 140 );
    columna( comprobaciones, "col_check", 230 );
    columna( comprobaciones, "col_status", 90 );
    columna( comprobaciones, "col_msg", 560 );
    mensajes = new Text( grupoPrueba, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL );
    GridData gm = new GridData( SWT.FILL, SWT.FILL, true, false );
    gm.heightHint = 70;
    mensajes.setLayoutData( gm );
    comprobaciones.addListener( SWT.Selection, e -> {
      // el mensaje entero de la comprobación elegida, encima del resumen
      Object d = e.item == null ? null : e.item.getData();
      if ( d instanceof String && !( (String) d ).isEmpty() ) {
        TableItem it = (TableItem) e.item;
        mensajes.setText( it.getText( 0 ) + " · " + it.getText( 1 ) + ": " + d + "\n\n" + resumenPrueba );
      }
    } );
    sash.setWeights( new int[] { 45, 55 } );

    Composite pr = new Composite( shell, SWT.NONE );
    pr.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gp = new GridLayout( 4, false );
    gp.marginWidth = 0;
    pr.setLayout( gp );
    estado = new Label( pr, SWT.WRAP );
    estado.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    reloj = new Label( pr, SWT.NONE );
    GridData gr = new GridData( SWT.RIGHT, SWT.CENTER, false, false );
    gr.widthHint = 60;
    reloj.setLayoutData( gr );
    barra = new ProgressBar( pr, SWT.HORIZONTAL | SWT.INDETERMINATE );
    GridData gb = new GridData( SWT.RIGHT, SWT.CENTER, false, false );
    gb.widthHint = 160;
    barra.setLayoutData( gb );
    barra.setVisible( false );
    cancelar = new Button( pr, SWT.PUSH );
    cancelar.setText( r.s( "cancel" ) );
    cancelar.setEnabled( false );
    cancelar.addListener( SWT.Selection, e -> cancelarPrueba() );

    Composite bo = new Composite( shell, SWT.NONE );
    bo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gbo = new GridLayout( 3, false );
    gbo.marginWidth = 0;
    bo.setLayout( gbo );
    aplicar = new Button( bo, SWT.PUSH );
    aplicar.setText( r.f( "r_apply", 0 ) );
    aplicar.setToolTipText( r.s( "r_apply_tip" ) );
    aplicar.setEnabled( false );
    aplicar.addListener( SWT.Selection, e -> aplicarPlan() );
    probar = new Button( bo, SWT.PUSH );
    probar.setText( r.s( "r_stress" ) );
    probar.setToolTipText( r.s( "r_stress_tip" ) );
    probar.addListener( SWT.Selection, e -> probar() );
    cerrar = new Button( bo, SWT.PUSH );
    cerrar.setText( r.s( "close" ) );
    GridData gce = new GridData( SWT.RIGHT, SWT.CENTER, true, false );
    gce.widthHint = 96;
    cerrar.setLayoutData( gce );
    cerrar.addListener( SWT.Selection, e -> shell.close() );

    shell.setSize( 1100, 800 );
  }

  private void columna( Table t, String clave, int ancho ) {
    TableColumn c = new TableColumn( t, SWT.LEFT );
    c.setText( r.s( clave ) );
    c.setWidth( ancho );
  }

  void abrir() {
    IdcMenu.abrirShell( shell, (Shell) shell.getParent() );
    cargarPlan();
  }

  // ---------------------------------------------------------------- plan

  /** harden_etl dry_run con todas las recetas. (UI) */
  void cargarPlan() {
    if ( ocupado ) {
      return;
    }
    ocupado( r.f( "r_planning", servicio.nombre() ), false );
    IdcMenu.enHilo( shell, "pentaholive-idc-plan", () -> servicio.plan(), o -> {
      libre( "" );
      mostrarPlan( o );
    }, e -> libre( r.f( "r_plan_error", IdcMenu.mensaje( e ) ) ) );
  }

  /** El título de la receta en el idioma del diálogo (informes/textos/harden_*.properties), o su id. */
  String receta( String id ) {
    Rotulos h = RobustecerAnalisis.textos( r.idioma() );
    return h.tiene( "recipe_" + id ) ? h.s( "recipe_" + id ) : id;
  }

  /** Marcable: no manual y con cambios (Propuesta.aplicable). */
  static boolean marcable( JsonNode p ) {
    return p != null && !"manual".equals( p.path( "level" ).asText() ) && p.path( "changes" ).size() > 0;
  }

  /** Marcada al empezar: solo las auto que no cambian la semántica de la carga. */
  static boolean marcadaAlEmpezar( JsonNode p ) {
    return marcable( p ) && "auto".equals( p.path( "level" ).asText() ) && !p.path( "semantics_change" )
      .asBoolean();
  }

  void mostrarPlan( ObjectNode o ) {
    plan = o;
    JsonNode rb = o.get( "robustness_before" );
    if ( rb != null && rb.has( "score" ) ) {
      puntaje.setText( r.f( "r_score", rb.path( "score" ).asInt(), rb.path( "grade" ).asText() ) );
    } else {
      puntaje.setText( "" );
    }
    tabla.removeAll();
    propuestas.clear();
    for ( JsonNode p : o.path( "proposals" ) ) {
      propuestas.add( p );
      TableItem it = new TableItem( tabla, SWT.NONE );
      it.setData( p );
      String nivel = p.path( "level" ).asText();
      boolean sem = p.path( "semantics_change" ).asBoolean();
      it.setText( new String[] { "", receta( p.path( "recipe" ).asText() ), r.s( "level_" + nivel ),
        p.path( "step" ).asText(),
        IdcCalidad.una( p.path( "why" ).asText() ), sem || "confirm".equals( nivel ) ? r.s( "r_semantics" )
          : "" } );
      it.setChecked( marcadaAlEmpezar( p ) );
      if ( !marcable( p ) ) {
        it.setGrayed( true );
        it.setForeground( gris );
      } else if ( sem || "confirm".equals( nivel ) ) {
        it.setForeground( 5, ambar );
      }
    }
    if ( propuestas.isEmpty() ) {
      detalle.setText( r.s( "r_plan_none" ) );
    } else {
      tabla.setSelection( 0 );
      elegida();
    }
    contar();
    shell.layout( true, true );
  }

  /** Detalle de la propuesta elegida: por qué, avisos, cambios (diff) o cómo hacerlo. (UI) */
  void elegida() {
    elegida( tabla.getSelectionIndex() );
  }

  void elegida( int i ) {
    if ( i < 0 || i >= propuestas.size() ) {
      return;
    }
    JsonNode p = propuestas.get( i );
    StringBuilder b = new StringBuilder();
    b.append( receta( p.path( "recipe" ).asText() ) ).append( " (" ).append( p.path( "recipe" ).asText() )
      .append( ") · " ).append( p.path( "step" ).asText() ).append( "\n" )
      .append( p.path( "why" ).asText() );
    if ( "manual".equals( p.path( "level" ).asText() ) ) {
      b.append( "\n\n" ).append( r.s( "r_manual_note" ) );
    }
    if ( p.path( "warnings" ).size() > 0 ) {
      b.append( "\n\n" ).append( r.s( "r_detail_warnings" ) );
      for ( JsonNode w : p.path( "warnings" ) ) {
        b.append( "\n· " ).append( w.asText() );
      }
    }
    if ( IdcCalidad.texto( p, "diff" ) != null ) {
      b.append( "\n\n" ).append( r.s( "r_detail_changes" ) ).append( "\n" ).append( p.path( "diff" ).asText() );
    }
    if ( IdcCalidad.texto( p, "how_to" ) != null ) {
      b.append( "\n\n" ).append( r.s( "r_detail_howto" ) ).append( "\n" ).append( p.path( "how_to" ).asText() );
    }
    detalle.setText( b.toString() );
  }

  /** Las keys marcadas (confirmar) y las auto desmarcadas (excluir). */
  Set<String>[] eleccion() {
    Set<String> confirmar = new LinkedHashSet<String>();
    Set<String> excluir = new LinkedHashSet<String>();
    for ( TableItem it : tabla.getItems() ) {
      JsonNode p = (JsonNode) it.getData();
      if ( !marcable( p ) ) {
        continue;
      }
      String k = p.path( "key" ).asText();
      boolean auto = "auto".equals( p.path( "level" ).asText() );
      if ( it.getChecked() && !auto ) {
        confirmar.add( k );
      } else if ( !it.getChecked() && auto ) {
        excluir.add( k );
      }
    }
    @SuppressWarnings( "unchecked" )
    Set<String>[] r2 = new Set[] { confirmar, excluir };
    return r2;
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
    aplicar.setText( r.f( "r_apply", n ) );
    aplicar.setEnabled( n > 0 && !ocupado );
    aplicar.getParent().layout( true );
  }

  /** harden_etl dry_run=false con lo marcado; después, el plan otra vez. (UI) */
  void aplicarPlan() {
    if ( ocupado || marcadas() == 0 ) {
      return;
    }
    final Set<String>[] e = eleccion();
    ocupado( r.s( "r_applying" ), false );
    IdcMenu.enHilo( shell, "pentaholive-idc-harden", () -> servicio.aplicar( e[ 0 ], e[ 1 ] ), o -> {
      int n = o.path( "applied" ).size();
      String antes = puntos( o.get( "robustness_before" ) );
      String despues = puntos( o.get( "robustness_after" ) );
      StringBuilder t = new StringBuilder( n == 0 ? r.s( "r_applied_none" ) : r.f( "r_applied", n, antes,
        despues ) );
      List<String> no = new ArrayList<String>();
      for ( JsonNode x : o.path( "not_applied" ) ) {
        no.add( x.path( "key" ).asText() + ": " + x.path( "reason" ).asText() );
      }
      if ( !no.isEmpty() ) {
        t.append( "  " ).append( r.f( "r_not_applied", String.join( "; ", no ) ) );
      }
      final String txt = t.toString();
      libre( txt );
      ocupado( txt, false );
      IdcMenu.enHilo( shell, "pentaholive-idc-plan", () -> servicio.plan(), p -> {
        libre( txt );
        mostrarPlan( p );
      }, err -> libre( txt ) );
    }, err -> libre( r.f( "r_apply_error", IdcMenu.mensaje( err ) ) ) );
  }

  private static String puntos( JsonNode x ) {
    return x == null || !x.has( "score" ) ? "—" : x.path( "score" ).asInt() + " " + x.path( "grade" ).asText();
  }

  // ---------------------------------------------------------------- prueba con datos sucios

  /** stress_test en segundo plano con barra, reloj y Cancelar. (UI) */
  void probar() {
    if ( ocupado ) {
      return;
    }
    final int gen = ++generacion;
    final EstresProcesos.Corte c = new EstresProcesos.Corte();
    corte = c;
    inicio = System.currentTimeMillis();
    ocupado( r.f( "r_stress_running", servicio.nombre() ), true );
    tic( gen );
    IdcMenu.enHilo( shell, "pentaholive-idc-stress", () -> servicio.probar( c ), o -> {
      if ( gen != generacion ) {
        return;
      }
      corte = null;
      libre( "" );
      mostrarPrueba( o );
    }, e -> {
      if ( gen != generacion ) {
        return;
      }
      corte = null;
      libre( r.f( "r_stress_error", IdcMenu.mensaje( e ) ) );
    } );
  }

  /** El reloj de la prueba, una vez por segundo mientras corre. */
  private void tic( final int gen ) {
    if ( shell.isDisposed() || gen != generacion || !ocupado ) {
      return;
    }
    reloj.setText( r.f( "elapsed", ( System.currentTimeMillis() - inicio ) / 1000 ) );
    shell.getDisplay().timerExec( 1000, () -> tic( gen ) );
  }

  /** Cancela la prueba: mata la JVM hija (fuera del hilo de UI) y descarta su resultado. (UI) */
  void cancelarPrueba() {
    if ( corte == null ) {
      return;
    }
    generacion++;
    cortarPrueba();
    libre( r.s( "r_stress_cancelled" ) );
  }

  private void cortarPrueba() {
    final EstresProcesos.Corte c = corte;
    corte = null;
    if ( c != null ) {
      Thread h = new Thread( c::cancelar, "pentaholive-idc-stress-cancel" );
      h.setDaemon( true );
      h.start();
    }
  }

  /** Pinta un resultado de stress_test. (UI) */
  void mostrarPrueba( ObjectNode o ) {
    prueba = o;
    String v = o.path( "verdict" ).asText();
    veredicto.setText( r.f( "verdict", r.tiene( "verdict_" + v ) ? r.s( "verdict_" + v ) : v ) );
    veredicto.setForeground( "robust".equals( v ) ? verde : "fragile".equals( v ) ? rojo : ambar );
    String env = IdcCalidad.texto( o, "environment_problem" );
    GridData ge = (GridData) entorno.getLayoutData();
    if ( env != null ) {
      entorno.setText( r.s( "r_env_title" ) + ": " + env + "\n" + r.s( "r_env_hint" ) );
      entorno.setForeground( ambar );
      ge.exclude = false;
      entorno.setVisible( true );
    } else {
      ge.exclude = true;
      entorno.setVisible( false );
    }
    comprobaciones.removeAll();
    for ( JsonNode vj : o.path( "variants" ) ) {
      String id = vj.path( "id" ).asText();
      String envV = IdcCalidad.texto( vj, "environment_problem" );
      if ( vj.path( "checks" ).size() == 0 ) {
        fila( id, "", vj.path( "status" ).asText(), envV != null ? envV : vj.path( "message" ).asText() );
        continue;
      }
      for ( JsonNode k : vj.path( "checks" ) ) {
        fila( id, k.path( "id" ).asText(), k.path( "status" ).asText(), k.path( "message" ).asText() );
      }
    }
    List<String> m = new ArrayList<String>();
    JsonNode s = o.get( "summary" );
    if ( s != null ) {
      m.add( r.f( "r_summary", s.path( "passed" ).asInt(), s.path( "warned" ).asInt(), s.path( "failed" ).asInt() ) );
    }
    String nr = IdcCalidad.texto( o, "not_run_reason" );
    if ( nr != null && env == null ) {
      m.add( r.f( "r_not_run_reason", nr ) );
    }
    String sig = IdcCalidad.texto( o, "next" );
    if ( sig != null ) {
      m.add( r.f( "r_next", sig ) );
    }
    if ( o.path( "notes" ).size() > 0 ) {
      m.add( r.s( "r_messages" ) );
      for ( JsonNode n : o.path( "notes" ) ) {
        m.add( "· " + n.asText() );
      }
    }
    resumenPrueba = String.join( "\n", m );
    mensajes.setText( resumenPrueba );
    grupoPrueba.layout( true, true );
  }

  private void fila( String variante, String chk, String st, String msg ) {
    TableItem it = new TableItem( comprobaciones, SWT.NONE );
    String nombreChk = chk.isEmpty() ? "" : r.tiene( "chk_" + chk ) ? r.s( "chk_" + chk ) : chk;
    String nombreSt = r.tiene( "st_" + st ) ? r.s( "st_" + st ) : st;
    it.setText( new String[] { variante, nombreChk, nombreSt, IdcCalidad.una( msg ) } );
    it.setData( msg == null ? "" : msg );
    it.setForeground( 2, "pass".equals( st ) ? verde : "fail".equals( st ) ? rojo : "warn".equals( st ) ? ambar
      : gris );
  }

  // ---------------------------------------------------------------- estado

  private void ocupado( String texto, boolean cancelable ) {
    ocupado = true;
    estado.setText( texto );
    barra.setVisible( true );
    reloj.setText( "" );
    cancelar.setEnabled( cancelable );
    aplicar.setEnabled( false );
    probar.setEnabled( false );
    estado.getParent().layout( true );
  }

  private void libre( String texto ) {
    if ( shell.isDisposed() ) {
      return;
    }
    ocupado = false;
    estado.setText( texto == null ? "" : texto );
    barra.setVisible( false );
    reloj.setText( "" );
    cancelar.setEnabled( false );
    probar.setEnabled( true );
    contar();
    estado.getParent().layout( true );
  }

  // ---------------------------------------------------------------- el servicio de Spoon

  /** Llama a harden_etl y stress_test (los mismos métodos, los mismos argumentos). */
  static final class ServicioSpoon implements Servicio {
    final String doc;
    final String nombre;
    /** El idioma de Spoon: el de los textos del plan y de la prueba (los nombres de steps siguen al documento). */
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
    public ObjectNode plan() throws Exception {
      ObjectNode a = Tools.obj().put( "document", doc ).put( "dry_run", true );
      a.putArray( "recipes" ).add( "all" );
      return (ObjectNode) McpHttpServer.JSON.readTree( (String) RobustezTools.robustecer( a, null, lang ) );
    }

    @Override
    public ObjectNode aplicar( Set<String> confirmar, Set<String> excluir ) throws Exception {
      ObjectNode a = Tools.obj().put( "document", doc ).put( "dry_run", false );
      a.putArray( "recipes" ).add( "all" );
      if ( !confirmar.isEmpty() ) {
        com.fasterxml.jackson.databind.node.ArrayNode c = a.putArray( "confirm" );
        for ( String k : confirmar ) {
          c.add( k );
        }
      }
      return (ObjectNode) McpHttpServer.JSON.readTree( (String) RobustezTools.robustecer( a, excluir, lang ) );
    }

    @Override
    public ObjectNode probar( EstresProcesos.Corte corte ) throws Exception {
      ObjectNode a = Tools.obj().put( "document", doc ).put( "language", lang );
      return (ObjectNode) McpHttpServer.JSON.readTree( (String) EstresTools.probar( a, corte ) );
    }
  }
}
