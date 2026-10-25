package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
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
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.robustez.Receta;

/**
 * «Robustecer…» del menú IDC ({@link IdcMenu}): el plan de harden_etl (dry_run, todas las recetas) como una lista con
 * casillas, el detalle con formato de la receta elegida (por qué, avisos, los cambios exactos, cómo hacerlo), la ventana
 * de detalle con doble clic o Intro ({@link IdcDetalle.Ventana}: ir al step, aplicar esta receta, pedírselo a Claude),
 * «Aplicar» (un Ctrl+Z, parámetros nuevos incluidos) y «Probar con datos sucios…» (stress_test en segundo plano,
 * cancelable), que antes de correr dice qué va a hacer y si va a poder.
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

  /** Lo que el diálogo pide afuera; todo corre fuera del hilo de UI salvo seleccionar. */
  interface Servicio {
    String nombre();

    /** harden_etl recipes=[all] dry_run=true. */
    ObjectNode plan() throws Exception;

    /** harden_etl recipes=[all] dry_run=false confirm=confirmar, sin las auto de excluir. */
    ObjectNode aplicar( Set<String> confirmar, Set<String> excluir ) throws Exception;

    /** stress_test con un corte para cancelarla. */
    ObjectNode probar( EstresProcesos.Corte corte ) throws Exception;

    /** Lo que haría stress_test, sin correrlo ({@link EstresTools#previa}); null si no se sabe. */
    default ObjectNode previa() throws Exception {
      return null;
    }

    /** select_steps del step en el lienzo. Nunca lanza. (UI) */
    default void seleccionar( String step ) {
    }
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
  final StyledText detalle;
  final Label estado;
  final ProgressBar barra;
  final Label reloj;
  final Button cancelar;
  final Button aplicar;
  final Button verDetalle;
  final Button probar;
  final Button cerrar;
  final Group grupoPrueba;
  final Label veredicto;
  final Label entorno;
  /** Antes de la prueba: qué va a hacer (y si va a poder). Después, se oculta. */
  final StyledText queHara;
  final SashForm resultados;
  final Table comprobaciones;
  final StyledText mensajes;

  private final Color rojo;
  private final Color ambar;
  private final Color verde;
  private final Color gris;

  /** Las propuestas de la tabla, en orden. */
  final List<JsonNode> propuestas = new ArrayList<JsonNode>();
  ObjectNode plan;
  ObjectNode prueba;
  ObjectNode previa;
  IdcDetalle.Ventana ventana;
  JsonNode enVentana;
  volatile boolean ocupado;
  private int generacion;
  private EstresProcesos.Corte corte;
  private long inicio;
  /** El resumen de la última prueba (lo que muestra la caja de mensajes). */
  private IdcDetalle.Texto resumenPrueba = new IdcDetalle.Texto();

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
    rojo = new Color( shell.getDisplay(), 175, 25, 25 );
    ambar = new Color( shell.getDisplay(), 150, 90, 0 );
    verde = new Color( shell.getDisplay(), 25, 115, 50 );
    gris = new Color( shell.getDisplay(), 100, 100, 100 );
    shell.addListener( SWT.Dispose, e -> {
      rojo.dispose();
      ambar.dispose();
      verde.dispose();
      gris.dispose();
      cortarPrueba();
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

    // arriba: el plan y el detalle de la receta elegida, lado a lado en altura
    SashForm arriba = new SashForm( sash, SWT.VERTICAL );
    tabla = new Table( arriba, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL
      | SWT.H_SCROLL );
    tabla.setHeaderVisible( true );
    tabla.setLinesVisible( true );
    columna( tabla, "col_apply", 64 );
    columna( tabla, "col_recipe", 240 );
    columna( tabla, "col_level", 100 );
    columna( tabla, "col_step", 180 );
    columna( tabla, "col_why", 380 );
    columna( tabla, "col_semantics", 150 );
    IdcDetalle.columnas( tabla, "harden", 4 );
    IdcDetalle.tooltips( tabla );
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
    IdcDetalle.alAbrir( tabla, this::abrirDetalle );
    detalle = IdcDetalle.panel( arriba );
    detalle.setData( "plano", "" );
    arriba.setWeights( new int[] { 52, 48 } );

    // abajo: la prueba con datos sucios
    grupoPrueba = new Group( sash, SWT.NONE );
    grupoPrueba.setText( r.s( "r_stress" ).replace( "…", "" ) );
    grupoPrueba.setLayout( new GridLayout( 1, false ) );
    veredicto = new Label( grupoPrueba, SWT.WRAP );
    veredicto.setFont( IdcCalidad.negrita( veredicto, 1.15 ) );
    GridData gve = new GridData( SWT.FILL, SWT.TOP, true, false );
    gve.exclude = true;
    veredicto.setLayoutData( gve );
    veredicto.setVisible( false );
    entorno = new Label( grupoPrueba, SWT.WRAP );
    GridData ge = new GridData( SWT.FILL, SWT.TOP, true, false );
    ge.exclude = true;
    entorno.setLayoutData( ge );
    entorno.setVisible( false );
    queHara = IdcDetalle.panel( grupoPrueba );
    queHara.setData( "plano", "" );
    queHara.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    IdcDetalle.Texto qh = new IdcDetalle.Texto();
    qh.parrafo( r.s( "r_stress_what_loading" ), IdcDetalle.Texto.SUAVE );
    pintar( queHara, qh );
    resultados = new SashForm( grupoPrueba, SWT.VERTICAL );
    GridData gk = new GridData( SWT.FILL, SWT.FILL, true, true );
    gk.exclude = true;
    resultados.setLayoutData( gk );
    resultados.setVisible( false );
    comprobaciones = new Table( resultados, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL );
    comprobaciones.setHeaderVisible( true );
    comprobaciones.setLinesVisible( true );
    columna( comprobaciones, "col_variant", 150 );
    columna( comprobaciones, "col_check", 230 );
    columna( comprobaciones, "col_status", 90 );
    columna( comprobaciones, "col_msg", 520 );
    IdcDetalle.columnas( comprobaciones, "stress", 3 );
    IdcDetalle.tooltips( comprobaciones );
    mensajes = IdcDetalle.panel( resultados );
    mensajes.setData( "plano", "" );
    resultados.setWeights( new int[] { 55, 45 } );
    comprobaciones.addListener( SWT.Selection, e -> {
      // el mensaje entero de la comprobación elegida, encima del resumen
      Object d = e.item == null ? null : e.item.getData();
      if ( d instanceof String && !( (String) d ).isEmpty() ) {
        TableItem it = (TableItem) e.item;
        IdcDetalle.Texto t = new IdcDetalle.Texto();
        t.seccion( it.getText( 0 ) + " · " + it.getText( 1 ) + " · " + it.getText( 2 ) ).parrafo( (String) d );
        t.b.append( "\n" );
        pintar( mensajes, unir( t, resumenPrueba ) );
      }
    } );
    sash.setWeights( new int[] { 58, 42 } );

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
    GridLayout gbo = new GridLayout( 4, false );
    gbo.marginWidth = 0;
    bo.setLayout( gbo );
    aplicar = new Button( bo, SWT.PUSH );
    aplicar.setText( r.f( "r_apply", 0 ) );
    aplicar.setToolTipText( r.s( "r_apply_tip" ) );
    aplicar.setEnabled( false );
    aplicar.addListener( SWT.Selection, e -> aplicarPlan() );
    verDetalle = new Button( bo, SWT.PUSH );
    verDetalle.setText( r.s( "q_details" ) );
    verDetalle.setToolTipText( r.s( "r_details_tip" ) );
    verDetalle.setEnabled( false );
    verDetalle.addListener( SWT.Selection, e -> abrirDetalle() );
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

    IdcDetalle.tamano( shell, "harden", 1120, 820 );
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

  private static void pintar( StyledText st, IdcDetalle.Texto t ) {
    IdcDetalle.mostrar( st, t );
    st.setData( "plano", t.plano() );
  }

  /** Dos textos seguidos (el segundo sin perder sus estilos). */
  private static IdcDetalle.Texto unir( IdcDetalle.Texto a, IdcDetalle.Texto b ) {
    IdcDetalle.Texto t = new IdcDetalle.Texto();
    t.b.append( a.b );
    t.estilos.addAll( a.estilos );
    int base = t.b.length();
    t.b.append( b.b );
    for ( int[] x : b.estilos ) {
      t.estilos.add( new int[] { x[ 0 ] + base, x[ 1 ], x[ 2 ] } );
    }
    return t;
  }

  // ---------------------------------------------------------------- plan

  /** harden_etl dry_run con todas las recetas (y, a la vez, lo que haría la prueba con datos sucios). (UI) */
  void cargarPlan() {
    if ( ocupado ) {
      return;
    }
    ocupado( r.f( "r_planning", servicio.nombre() ), false );
    IdcMenu.enHilo( shell, "pentaholive-idc-plan", () -> servicio.plan(), o -> {
      libre( "" );
      mostrarPlan( o );
    }, e -> libre( r.f( "r_plan_error", IdcMenu.mensaje( e ) ) ) );
    if ( previa == null && prueba == null ) {
      IdcMenu.enHilo( shell, "pentaholive-idc-stress-plan", () -> servicio.previa(), o -> mostrarPrevia( o ), e -> {
        IdcDetalle.Texto t = new IdcDetalle.Texto();
        queHaraBase( t );
        t.parrafo( r.f( "r_stress_what_error", IdcMenu.mensaje( e ) ), IdcDetalle.Texto.AMBAR );
        if ( prueba == null ) {
          pintar( queHara, t );
        }
      } );
    }
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
    String antes = enVentana == null ? null : enVentana.path( "key" ).asText();
    tabla.removeAll();
    propuestas.clear();
    for ( JsonNode p : o.path( "proposals" ) ) {
      propuestas.add( p );
      TableItem it = new TableItem( tabla, SWT.NONE );
      it.setData( p );
      String nivel = p.path( "level" ).asText();
      boolean sem = p.path( "semantics_change" ).asBoolean();
      String titulo = receta( p.path( "recipe" ).asText() );
      it.setText( new String[] { "", titulo, r.s( "level_" + nivel ), p.path( "step" ).asText(), IdcCalidad.una( p
        .path( "why" ).asText() ), sem || "confirm".equals( nivel ) ? r.s( "r_semantics" ) : "" } );
      it.setData( "tip1", titulo + " (" + p.path( "recipe" ).asText() + ")" );
      it.setData( "tip4", p.path( "why" ).asText() );
      it.setData( "tip5", sem || "confirm".equals( nivel ) ? r.s( "r_semantics_tip" ) : "" );
      it.setChecked( marcadaAlEmpezar( p ) );
      if ( !marcable( p ) ) {
        it.setGrayed( true );
        it.setForeground( gris );
      } else if ( sem || "confirm".equals( nivel ) ) {
        it.setForeground( 5, ambar );
      }
    }
    if ( propuestas.isEmpty() ) {
      IdcDetalle.Texto t = new IdcDetalle.Texto();
      t.parrafo( r.s( "r_plan_none" ), IdcDetalle.Texto.SUAVE );
      pintar( detalle, t );
      verDetalle.setEnabled( false );
    } else {
      tabla.setSelection( 0 );
      elegida();
    }
    if ( ventana != null && !ventana.shell.isDisposed() && antes != null ) {
      JsonNode mismo = null;
      for ( JsonNode p : propuestas ) {
        if ( antes.equals( p.path( "key" ).asText() ) ) {
          mismo = p;
        }
      }
      if ( mismo != null ) {
        String est = ventana.estado.getText();
        llenarVentana( mismo );
        estadoVentana( est );
      } else {
        ventana.aplicar.setEnabled( false );
        estadoVentana( ventana.estado.getText() + ( ventana.estado.getText().isEmpty() ? "" : "  " ) + r.s(
          "r_gone" ) );
      }
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
    verDetalle.setEnabled( true );
    IdcDetalle.Texto t = contenido( propuestas.get( i ), true );
    t.parrafo( r.s( "r_detail_hint" ), IdcDetalle.Texto.SUAVE );
    pintar( detalle, t );
  }

  /** El texto con formato de una propuesta: por qué, nota manual, avisos, cambios exactos, cómo hacerlo, datos. */
  IdcDetalle.Texto contenido( JsonNode p, boolean conTitulo ) {
    IdcDetalle.Texto t = new IdcDetalle.Texto();
    String nivel = p.path( "level" ).asText();
    if ( conTitulo ) {
      t.titulo( receta( p.path( "recipe" ).asText() ) );
      t.parrafo( r.s( "level_" + nivel ) + " · " + p.path( "recipe" ).asText() + " · " + r.f( "q_where_step", p.path(
        "step" ).asText() ), "manual".equals( nivel ) ? IdcDetalle.Texto.SUAVE : "auto".equals( nivel )
          ? IdcDetalle.Texto.VERDE : IdcDetalle.Texto.AMBAR );
    }
    t.seccion( r.s( "d_why_recipe" ) ).parrafo( p.path( "why" ).asText() );
    if ( p.path( "semantics_change" ).asBoolean() || "confirm".equals( nivel ) ) {
      t.parrafo( "⚠ " + r.s( "r_semantics_tip" ), IdcDetalle.Texto.AMBAR );
    }
    if ( p.path( "warnings" ).size() > 0 ) {
      t.seccion( r.s( "r_detail_warnings" ).replace( ":", "" ) );
      List<String> l = new ArrayList<String>();
      for ( JsonNode w : p.path( "warnings" ) ) {
        l.add( w.asText() );
      }
      t.vinetas( l );
    }
    if ( IdcCalidad.texto( p, "diff" ) != null ) {
      t.seccion( r.s( "r_detail_changes" ).replace( ":", "" ) ).codigo( p.path( "diff" ).asText() );
      t.parrafo( r.s( "d_undo_one" ), IdcDetalle.Texto.SUAVE );
    }
    String como = IdcCalidad.texto( p, "how_to" );
    if ( "manual".equals( nivel ) ) {
      t.seccion( r.s( "d_manual" ) ).parrafo( como != null ? como : r.s( "r_manual_note" ) );
      t.parrafo( r.s( "d_manual_claude" ), IdcDetalle.Texto.SUAVE );
    } else if ( como != null ) {
      t.seccion( r.s( "r_detail_howto" ).replace( ":", "" ) ).parrafo( como );
    }
    JsonNode det = p.get( "detected" );
    if ( det != null && det.size() > 0 ) {
      t.seccion( r.s( "d_detected" ) );
      java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = det.fields();
      while ( it.hasNext() ) {
        java.util.Map.Entry<String, JsonNode> x = it.next();
        t.dato( x.getKey(), x.getValue().isTextual() ? x.getValue().asText() : x.getValue().toString() );
      }
    }
    return t;
  }

  /** El enlace al manual: 09-robustez.md. */
  static String manual() {
    return IdcDetalle.MANUAL + "09-robustez.md";
  }

  /** El pedido para Claude: la propuesta con todo lo que hace falta. */
  String pedido( JsonNode p ) {
    String como = IdcCalidad.texto( p, "how_to" );
    return r.f( "d_prompt_harden", servicio.nombre(), receta( p.path( "recipe" ).asText() ), p.path( "recipe" )
      .asText(), p.path( "step" ).asText(), p.path( "why" ).asText(), como == null ? "—" : como, p.path( "key" )
        .asText() );
  }

  // ---------------------------------------------------------------- la ventana de detalle

  void abrirDetalle() {
    int i = tabla.getSelectionIndex();
    if ( i < 0 || i >= propuestas.size() ) {
      return;
    }
    JsonNode p = propuestas.get( i );
    if ( ventana == null || ventana.shell.isDisposed() ) {
      ventana = new IdcDetalle.Ventana( shell, r, "harden.detail", r.f( "d_title_harden", servicio.nombre() ) );
      final IdcDetalle.Ventana v = ventana;
      v.irAlStep.addListener( SWT.Selection, e -> {
        if ( enVentana != null ) {
          servicio.seleccionar( enVentana.path( "step" ).asText() );
        }
      } );
      v.aplicar.setText( r.s( "d_apply_recipe" ) );
      v.aplicar.setToolTipText( r.s( "d_apply_recipe_tip" ) );
      v.aplicar.addListener( SWT.Selection, e -> aplicarDesdeVentana() );
      IdcDetalle.Ventana.ocultar( v.silenciar, true );
      v.claude.addListener( SWT.Selection, e -> {
        if ( enVentana != null ) {
          IdcDetalle.copiar( shell.getDisplay(), pedido( enVentana ) );
          estadoVentana( r.s( "d_claude_copied" ) );
        }
      } );
      llenarVentana( p );
      v.abrir( shell );
    } else {
      llenarVentana( p );
      ventana.shell.setVisible( true );
    }
  }

  void llenarVentana( JsonNode p ) {
    IdcDetalle.Ventana v = ventana;
    enVentana = p;
    String nivel = p.path( "level" ).asText();
    v.titulo.setText( receta( p.path( "recipe" ).asText() ) );
    v.insignia( r.s( "level_" + nivel ), "manual".equals( nivel ) ? IdcDetalle.Texto.SUAVE : "auto".equals( nivel )
      ? IdcDetalle.Texto.VERDE : IdcDetalle.Texto.AMBAR );
    v.donde.setText( r.f( "d_recipe", p.path( "recipe" ).asText() ) + " · " + r.f( "q_where_step", p.path( "step" )
      .asText() ) );
    IdcDetalle.Ventana.ocultar( v.aplicar, !marcable( p ) );
    v.aplicar.setEnabled( marcable( p ) && !ocupado );
    v.conValor( null, null );
    Receta rc = Receta.de( p.path( "recipe" ).asText() );
    v.enlace( r.f( "d_manual_link", "09-robustez.md", rc == null ? p.path( "recipe" ).asText() : rc.numero + " "
      + rc.id ), manual() );
    v.estado.setText( "" );
    v.mostrar( contenido( p, false ) );
    v.shell.layout( true, true );
  }

  /** «Aplicar esta receta»: solo esa propuesta (las demás auto, excluidas). (UI) */
  void aplicarDesdeVentana() {
    final JsonNode p = enVentana;
    if ( p == null || ocupado || !marcable( p ) ) {
      return;
    }
    Set<String> confirmar = new LinkedHashSet<String>();
    Set<String> excluir = new LinkedHashSet<String>();
    String k = p.path( "key" ).asText();
    for ( JsonNode x : propuestas ) {
      String kx = x.path( "key" ).asText();
      if ( kx.equals( k ) ) {
        if ( !"auto".equals( x.path( "level" ).asText() ) ) {
          confirmar.add( kx );
        }
      } else if ( "auto".equals( x.path( "level" ).asText() ) && marcable( x ) ) {
        excluir.add( kx );
      }
    }
    ventana.aplicar.setEnabled( false );
    estadoVentana( r.s( "r_applying" ) );
    aplicarCon( confirmar, excluir, txt -> estadoVentana( txt ) );
  }

  private void estadoVentana( String txt ) {
    if ( ventana != null && !ventana.shell.isDisposed() ) {
      ventana.estado.setText( txt == null ? "" : txt );
      ventana.shell.layout( true, true );
    }
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
    aplicarCon( e[ 0 ], e[ 1 ], null );
  }

  private void aplicarCon( final Set<String> confirmar, final Set<String> excluir,
                           final java.util.function.Consumer<String> alTerminar ) {
    ocupado( r.s( "r_applying" ), false );
    IdcMenu.enHilo( shell, "pentaholive-idc-harden", () -> servicio.aplicar( confirmar, excluir ), o -> {
      int n = o.path( "applied" ).size();
      String antes = puntos( o.get( "robustness_before" ) );
      String despues = puntos( o.get( "robustness_after" ) );
      StringBuilder t = new StringBuilder( n == 0 ? r.s( "r_applied_none" ) : r.f( "r_applied", n, antes,
        despues ) );
      List<String> no = new ArrayList<String>();
      for ( JsonNode x : o.path( "not_applied" ) ) {
        no.add( r.f( "q_skip_item", tituloDeKey( x.path( "key" ).asText() ), x.path( "reason" ).asText() ) );
      }
      if ( !no.isEmpty() ) {
        t.append( "  " ).append( r.f( "r_not_applied", String.join( "; ", no ) ) );
      }
      final String txt = t.toString();
      if ( alTerminar != null ) {
        alTerminar.accept( txt );
      }
      libre( txt );
      ocupado( txt, false );
      IdcMenu.enHilo( shell, "pentaholive-idc-plan", () -> servicio.plan(), p -> {
        libre( txt );
        mostrarPlan( p );
      }, err -> libre( txt ) );
    }, err -> {
      String txt = r.f( "r_apply_error", IdcMenu.mensaje( err ) );
      libre( txt );
      if ( alTerminar != null ) {
        alTerminar.accept( txt );
      }
    } );
  }

  /** «Receta · step» de una key harden:&lt;receta&gt;:&lt;step&gt; («*» = toda la tanda). */
  String tituloDeKey( String key ) {
    String[] p = key.split( ":", 3 );
    if ( p.length < 3 ) {
      return key;
    }
    return receta( p[ 1 ] ) + " · " + p[ 2 ];
  }

  private static String puntos( JsonNode x ) {
    return x == null || !x.has( "score" ) ? "—" : x.path( "score" ).asInt() + " " + x.path( "grade" ).asText();
  }

  // ---------------------------------------------------------------- prueba con datos sucios

  /** La cabecera de «qué hará»: los pasos de la prueba. */
  private void queHaraBase( IdcDetalle.Texto t ) {
    t.seccion( r.s( "r_stress_what" ) ).parrafo( r.s( "r_stress_what_text" ) );
  }

  /** Antes de correr: qué va a ensuciar, qué variantes, qué comprueba y si va a poder correr. (UI) */
  void mostrarPrevia( ObjectNode o ) {
    previa = o;
    if ( prueba != null ) {
      return; // ya hay un resultado: manda él
    }
    IdcDetalle.Texto t = new IdcDetalle.Texto();
    queHaraBase( t );
    if ( o == null ) {
      pintar( queHara, t );
      return;
    }
    List<String> ins = new ArrayList<String>();
    List<String> no = new ArrayList<String>();
    for ( JsonNode x : o.path( "inputs" ) ) {
      if ( IdcCalidad.texto( x, "not_evaluated" ) != null ) {
        no.add( r.f( "r_stress_input_no", x.path( "step" ).asText(), x.path( "not_evaluated" ).asText() ) );
      } else {
        ins.add( r.f( "r_stress_input", x.path( "step" ).asText(), x.path( "base_rows" ).asInt(), x.path( "fields" )
          .asInt() ) );
      }
    }
    if ( !ins.isEmpty() ) {
      t.seccion( r.s( "r_stress_inputs" ) ).vinetas( ins );
    }
    if ( !no.isEmpty() ) {
      t.vinetas( no );
    }
    List<String> vs = new ArrayList<String>();
    for ( JsonNode v : o.path( "variants" ) ) {
      String id = v.asText();
      vs.add( r.tiene( "var_" + id ) ? r.s( "var_" + id ) : id );
    }
    if ( !vs.isEmpty() ) {
      t.seccion( r.s( "r_stress_variants" ) ).vinetas( vs );
    }
    t.seccion( r.s( "r_stress_checks" ) ).vinetas( java.util.Arrays.asList( r.s( "chk_no_abort" ), r.s(
      "chk_no_silent_loss" ), r.s( "chk_rejects_have_reason" ) ) );
    if ( o.path( "will_run" ).asBoolean() ) {
      t.parrafo( r.s( "r_stress_will_run" ), IdcDetalle.Texto.VERDE );
    } else {
      t.seccion( r.s( "r_stress_wont_run" ) );
      t.parrafo( IdcCalidad.texto( o, "not_run_reason" ), IdcDetalle.Texto.AMBAR );
      if ( o.path( "databases_not_files" ).size() > 0 ) {
        t.parrafo( r.s( "r_stress_db_hint" ) );
      }
    }
    pintar( queHara, t );
  }

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

  private void ver( org.eclipse.swt.widgets.Control c, boolean ver ) {
    ( (GridData) c.getLayoutData() ).exclude = !ver;
    c.setVisible( ver );
  }

  /** Pinta un resultado de stress_test. (UI) */
  void mostrarPrueba( ObjectNode o ) {
    prueba = o;
    String v = o.path( "verdict" ).asText();
    veredicto.setText( r.f( "verdict", r.tiene( "verdict_" + v ) ? r.s( "verdict_" + v ) : v ) );
    veredicto.setForeground( "robust".equals( v ) ? verde : "fragile".equals( v ) ? rojo : ambar );
    ver( veredicto, true );
    String env = IdcCalidad.texto( o, "environment_problem" );
    if ( env != null ) {
      entorno.setText( r.s( "r_env_title" ) + ": " + env + "\n" + r.s( "r_env_hint" ) );
      entorno.setForeground( ambar );
    }
    ver( entorno, env != null );
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
    IdcDetalle.Texto m = new IdcDetalle.Texto();
    JsonNode s = o.get( "summary" );
    if ( s != null ) {
      m.parrafo( r.f( "r_summary", s.path( "passed" ).asInt(), s.path( "warned" ).asInt(), s.path( "failed" ).asInt() ),
        IdcDetalle.Texto.NEGRITA );
    }
    String nr = IdcCalidad.texto( o, "not_run_reason" );
    if ( nr != null && env == null ) {
      m.parrafo( r.f( "r_not_run_reason", nr ), IdcDetalle.Texto.AMBAR );
    }
    String sig = IdcCalidad.texto( o, "next" );
    if ( sig != null ) {
      m.parrafo( r.f( "r_next", sig ) );
    }
    if ( o.path( "notes" ).size() > 0 ) {
      m.seccion( r.s( "r_messages" ).replace( ":", "" ) );
      List<String> l = new ArrayList<String>();
      for ( JsonNode n : o.path( "notes" ) ) {
        l.add( n.asText() );
      }
      m.vinetas( l );
    }
    resumenPrueba = m;
    boolean hayFilas = comprobaciones.getItemCount() > 0;
    // sin filas (no corrió): nada de tabla vacía, solo el porqué y qué hacer
    ver( queHara, !hayFilas );
    ver( resultados, hayFilas );
    if ( hayFilas ) {
      pintar( mensajes, m );
    } else {
      IdcDetalle.Texto pie = new IdcDetalle.Texto();
      if ( previa != null && previa.path( "databases_not_files" ).size() > 0 || nr != null && nr.contains( "SQLite" ) ) {
        pie.parrafo( r.s( "r_stress_db_hint" ) );
      }
      pie.parrafo( r.s( "r_stress_db_hint_short" ), IdcDetalle.Texto.SUAVE );
      pintar( queHara, unir( m, pie ) );
    }
    grupoPrueba.layout( true, true );
  }

  private void fila( String variante, String chk, String st, String msg ) {
    TableItem it = new TableItem( comprobaciones, SWT.NONE );
    String nombreVar = r.tiene( "var_" + variante ) ? r.s( "var_" + variante ) : variante;
    String nombreChk = chk.isEmpty() ? "" : r.tiene( "chk_" + chk ) ? r.s( "chk_" + chk ) : chk;
    String nombreSt = r.tiene( "st_" + st ) ? r.s( "st_" + st ) : st;
    it.setText( new String[] { variante, nombreChk, nombreSt, IdcCalidad.una( msg ) } );
    it.setData( msg == null ? "" : msg );
    it.setData( "tip0", nombreVar );
    it.setData( "tip3", msg == null ? "" : msg );
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
    if ( ventana != null && !ventana.shell.isDisposed() ) {
      ventana.aplicar.setEnabled( false );
    }
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
    if ( ventana != null && !ventana.shell.isDisposed() && enVentana != null ) {
      ventana.aplicar.setEnabled( marcable( enVentana ) );
    }
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

    @Override
    public ObjectNode previa() throws Exception {
      return EstresTools.previa( Tools.obj().put( "document", doc ).put( "language", lang ) );
    }

    @Override
    public void seleccionar( String step ) {
      try {
        org.pentaho.di.base.AbstractMeta m = Docs.resolverSinActivar( Tools.obj().put( "document", doc ) );
        Docs.activar( m );
        TransTools.seleccionar( m, Collections.singletonList( step ) );
      } catch ( Throwable t ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not select '" + step + "' on the canvas: " + t );
      }
    }
  }
}
