package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.TypedListener;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.i18n.LanguageChoice;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPerspectiveManager;
import org.pentaho.ui.xul.XulComponent;
import org.pentaho.ui.xul.XulDomContainer;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.tema.SimboloIdc;

/**
 * El menú «IDC» de la barra principal de Spoon, justo a la derecha del selector de perspectivas: todo Pentaho Live a
 * mano (Revisar calidad…, Robustecer…, Ordenar lienzo, Lección), el cambio de perspectiva y «Acerca de».
 * <p>
 * <b>Dónde va y por qué así.</b> En PDI 9.4 el selector de perspectivas no es XUL: {@code
 * SpoonPerspectiveManager.initialize()} (dentro de {@code Spoon.init}, antes de {@code createContents}) agrega a la
 * ToolBar SWT del XUL {@code main-toolbar} (spoon.xul) un {@code ToolItem(SWT.DROP_DOWN)} con la imagen
 * perspective_changer.svg y un SelectionListener propio que arma un Menu con las perspectivas (leído del bytecode).
 * No hay overlay XUL que lo nombre, así que el menú se agrega igual que él: un ToolItem DROP_DOWN en esa misma
 * ToolBar, en el índice siguiente al del selector. Se instala desde {@link McpLifecycleListener#onStart} (hilo de UI,
 * con la ventana ya creada), como {@link OrdenarLienzo}. Si no encuentra el selector, va al final de la barra.
 * <p>
 * <b>Mismos caminos que las herramientas MCP:</b> los diálogos llaman a {@link LintTools#lint},
 * {@link LintTools#arreglar}, {@link LintTools#silenciar}, {@link RobustezTools#robustecer} y
 * {@link EstresTools#probar} con los mismos argumentos JSON que mandaría Claude; Ordenar lienzo es
 * {@link OrdenarLienzo#ejecutar}; el cambio de perspectiva es {@code Spoon.loadPerspective(id)}, lo mismo que hace
 * el selector de Spoon.
 * <p>
 * El modelo del menú ({@link #modelo}) no toca SWT ni Spoon: se prueba sin pantalla. Nada de esto puede tumbar Spoon:
 * todo lo que Spoon llama va en catch (Throwable). (UI)
 */
final class IdcMenu {

  /** Textos en informes/textos/idc_*.properties. */
  static final String AREA = "idc";
  /** Marca en la ToolBar principal ya decorada y en el ToolItem (getData). */
  static final String MARCA = "pentaholive.idc";
  static final String TEXTO_BOTON = "IDC";
  static final String REPO = "https://github.com/IvI2511/pentaho-mcp";
  /** Id del XUL de la barra principal (ui/spoon.xul). */
  static final String ID_BARRA = "main-toolbar";

  static final String CALIDAD = "quality";
  static final String ROBUSTECER = "harden";
  static final String ORDENAR = "arrange";
  static final String LECCION = "lesson";
  /** Plano ETL (D1): la perspectiva Diseño con el plano de la transformación activa. */
  static final String DISENO = "design";
  /** Hoja de mapeo origen-destino (D3). */
  static final String MAPEO = "mapping";
  /** Línea informativa con el progreso del boceto (D2). */
  static final String BOCETO = "sketch";
  static final String ACERCA = "about";
  /** Prefijo de los ids de las entradas de perspectiva: "perspective:" + id de la perspectiva. */
  static final String PERSPECTIVA = "perspective:";

  static final String TRANSFORMACION = "transformation";
  static final String JOB = "job";

  private static Image icono;
  private static Menu ultimo;

  private IdcMenu() {
  }

  // ================================================================ modelo (sin SWT)

  enum Tipo {
    ACCION, SEPARADOR, PERSPECTIVA
  }

  /** Una entrada del menú. */
  static final class Entrada {
    final Tipo tipo;
    final String id;
    final String texto;
    final boolean habilitada;
    final boolean marcada;
    final String ayuda;

    Entrada( Tipo tipo, String id, String texto, boolean habilitada, boolean marcada, String ayuda ) {
      this.tipo = tipo;
      this.id = id;
      this.texto = texto;
      this.habilitada = habilitada;
      this.marcada = marcada;
      this.ayuda = ayuda;
    }

    static Entrada separador() {
      return new Entrada( Tipo.SEPARADOR, null, null, true, false, null );
    }

    @Override
    public String toString() {
      return tipo == Tipo.SEPARADOR ? "---" : ( marcada ? "(*) " : "" ) + texto + ( habilitada ? "" : " [off]" );
    }
  }

  /** Lo que hace falta saber de Spoon para armar el menú. */
  static final class Contexto {
    /** null (nada abierto), {@link #TRANSFORMACION} o {@link #JOB}. */
    String documento;
    /** La transformación activa tiene una lección viva (no terminada). */
    boolean leccion;
    /** «Boceto: 7 de 12 piezas construidas» si la transformación activa salió de un plano (plan_build); si no, null. */
    String boceto;
    /** {id, nombre visible}, en el orden del selector de Spoon. */
    final List<String[]> perspectivas = new ArrayList<String[]>();
    /** Id de la perspectiva activa. */
    String activa;
  }

  /** Las entradas del menú, en orden. Sin efectos. */
  static List<Entrada> modelo( Rotulos r, Contexto c ) {
    List<Entrada> l = new ArrayList<Entrada>();
    boolean hay = c.documento != null;
    boolean trans = TRANSFORMACION.equals( c.documento );
    l.add( new Entrada( Tipo.ACCION, CALIDAD, r.s( "menu_quality" ), hay, false, hay ? r.s( "menu_quality_tip" )
      : r.s( "no_document" ) ) );
    l.add( new Entrada( Tipo.ACCION, ROBUSTECER, JOB.equals( c.documento ) ? r.s( "menu_harden_only_trans" )
      : r.s( "menu_harden" ), trans, false, trans ? r.s( "menu_harden_tip" ) : hay ? r.s( "r_only_trans" )
      : r.s( "no_document" ) ) );
    l.add( new Entrada( Tipo.ACCION, ORDENAR, r.s( "menu_arrange" ), hay, false, hay ? r.s( "menu_arrange_tip" )
      : r.s( "no_document" ) ) );
    l.add( new Entrada( Tipo.ACCION, LECCION, c.leccion ? r.s( "menu_lesson_show" ) : r.s( "menu_lesson" ), true,
      false, null ) );
    l.add( new Entrada( Tipo.ACCION, DISENO, r.s( "menu_design" ), true, false, r.s( "menu_design_tip" ) ) );
    l.add( new Entrada( Tipo.ACCION, MAPEO, r.s( "menu_mapping" ), trans, false, trans ? r.s( "menu_mapping_tip" )
      : hay ? r.s( "r_only_trans" ) : r.s( "no_document" ) ) );
    if ( c.boceto != null ) {
      l.add( new Entrada( Tipo.ACCION, BOCETO, c.boceto, false, false, null ) );
    }
    if ( !c.perspectivas.isEmpty() ) {
      l.add( Entrada.separador() );
      for ( String[] p : c.perspectivas ) {
        l.add( new Entrada( Tipo.PERSPECTIVA, PERSPECTIVA + p[ 0 ], p[ 1 ], true, p[ 0 ].equals( c.activa ),
          null ) );
      }
    }
    l.add( Entrada.separador() );
    l.add( new Entrada( Tipo.ACCION, ACERCA, r.s( "menu_about" ), true, false, null ) );
    return l;
  }

  /**
   * Las perspectivas que muestra el selector de Spoon: las registradas, en su orden, sin las ocultas. ocultas = {id:
   * oculta} de los datos del propio selector (null si no se pudieron leer: entonces van todas).
   */
  static List<String[]> perspectivasVisibles( List<String[]> registradas, Map<String, Boolean> ocultas ) {
    List<String[]> l = new ArrayList<String[]>();
    for ( String[] p : registradas ) {
      if ( ocultas != null && !ocultas.isEmpty() && ( !ocultas.containsKey( p[ 0 ] ) || ocultas.get( p[ 0 ] ) ) ) {
        continue; // no está en el selector (no se instaló) o está oculta
      }
      l.add( p );
    }
    return l;
  }

  /** El idioma de Spoon (el de los demás textos de Pentaho Live en Spoon). */
  static Rotulos rotulos() {
    return Rotulos.de( AREA, ReportsPerspective.idiomaUi() );
  }

  // ================================================================ registro en Spoon

  /** Desde McpLifecycleListener.onStart (hilo de UI). Recibe Object para no nombrar kettle-ui-swt allí. Nunca lanza. */
  static void instalarDesde( Object handler ) {
    try {
      instalar( handler instanceof Spoon ? (Spoon) handler : UiThread.spoon() );
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logError( "Pentaho Live: could not add the IDC menu", t );
    }
  }

  static void instalar( final Spoon sp ) {
    if ( sp == null ) {
      return;
    }
    ToolBar tb = barraPrincipal( sp.getMainSpoonContainer() );
    if ( tb == null ) {
      McpLifecycleListener.LOG.logBasic( "Pentaho Live: Spoon's main toolbar was not found, no IDC menu" );
      return;
    }
    ToolItem it = agregarBoton( tb, rotulos(), imagen( tb.getDisplay() ), textoSelector() );
    if ( it != null ) {
      it.addListener( SWT.Selection, e -> {
        try {
          mostrarMenu( sp, (ToolItem) e.widget );
        } catch ( Throwable t ) {
          McpLifecycleListener.LOG.logError( "Pentaho Live: could not open the IDC menu", t );
        }
      } );
      McpLifecycleListener.LOG.logBasic( "Pentaho Live: IDC menu added to the main toolbar (position "
        + tb.indexOf( it ) + " of " + tb.getItemCount() + ")" );
    }
  }

  static ToolBar barraPrincipal( XulDomContainer c ) {
    if ( c == null || c.getDocumentRoot() == null ) {
      return null;
    }
    XulComponent x = c.getDocumentRoot().getElementById( ID_BARRA );
    Object o = x == null ? null : x.getManagedObject();
    return o instanceof ToolBar ? (ToolBar) o : null;
  }

  /**
   * Agrega el botón IDC (DROP_DOWN, símbolo + «IDC») justo después del selector de perspectivas, una sola vez:
   * devuelve el ToolItem nuevo, o null si la barra ya lo tenía. (UI)
   */
  static ToolItem agregarBoton( ToolBar tb, Rotulos r, Image img, String tooltipSelector ) {
    if ( tb == null || tb.isDisposed() || tb.getData( MARCA ) != null ) {
      return null;
    }
    tb.setData( MARCA, Boolean.TRUE );
    ToolItem it = new ToolItem( tb, SWT.DROP_DOWN, posicion( tb.getItems(), tooltipSelector ) );
    it.setData( MARCA, Boolean.TRUE );
    if ( img != null && !img.isDisposed() ) {
      it.setImage( img );
    }
    it.setText( TEXTO_BOTON );
    it.setToolTipText( r.s( "menu_tooltip" ) );
    try {
      tb.pack();
      if ( tb.getParent() != null ) {
        tb.getParent().layout( true, true );
      }
    } catch ( Throwable ignorada ) {
      // solo el acomodo de la barra
    }
    return it;
  }

  /** El tooltip que Spoon pone a su selector de perspectivas (BaseMessages de Spoon). */
  static String textoSelector() {
    try {
      return BaseMessages.getString( Spoon.class, "Spoon.Menu.View.Perspectives" );
    } catch ( Throwable t ) {
      return null;
    }
  }

  /**
   * Índice donde va el botón: el siguiente al selector de perspectivas, que se reconoce por su SelectionListener
   * (una clase de SpoonPerspectiveManager) o por su tooltip; si no está, al final.
   */
  static int posicion( ToolItem[] items, String tooltipSelector ) {
    for ( int i = 0; i < items.length; i++ ) {
      if ( esSelector( items[ i ], tooltipSelector ) ) {
        return i + 1;
      }
    }
    return items.length;
  }

  static boolean esSelector( ToolItem it, String tooltipSelector ) {
    if ( it == null || it.isDisposed() || ( it.getStyle() & SWT.DROP_DOWN ) == 0 || it.getData( MARCA ) != null ) {
      return false;
    }
    try {
      for ( Listener l : it.getListeners( SWT.Selection ) ) {
        Object o = l instanceof TypedListener ? ( (TypedListener) l ).getEventListener() : l;
        if ( o != null && o.getClass().getName().startsWith( SpoonPerspectiveManager.class.getName() ) ) {
          return true;
        }
      }
    } catch ( Throwable ignorada ) {
      // sigue con el tooltip
    }
    return tooltipSelector != null && tooltipSelector.equals( it.getToolTipText() );
  }

  // ================================================================ el menú

  /** Arma el menú con el estado de ahora y lo despliega debajo del botón. (UI) */
  static void mostrarMenu( final Spoon sp, ToolItem it ) {
    final Display d = it.getDisplay();
    if ( ultimo != null && !ultimo.isDisposed() ) {
      ultimo.dispose();
    }
    Menu m = new Menu( it.getParent().getShell(), SWT.POP_UP );
    ultimo = m;
    llenar( m, modelo( rotulos(), contexto( sp ) ), id -> d.asyncExec( () -> ejecutar( sp, id ) ) );
    Rectangle b = it.getBounds();
    Point p = it.getParent().toDisplay( b.x, b.y + b.height );
    m.setLocation( p );
    m.setVisible( true );
  }

  /** Crea los MenuItem del modelo; al elegir uno llama a accion con su id. (UI) */
  static void llenar( Menu m, List<Entrada> entradas, final Consumer<String> accion ) {
    for ( final Entrada e : entradas ) {
      if ( e.tipo == Tipo.SEPARADOR ) {
        new MenuItem( m, SWT.SEPARATOR );
        continue;
      }
      MenuItem mi = new MenuItem( m, e.tipo == Tipo.PERSPECTIVA ? SWT.RADIO : SWT.PUSH );
      mi.setText( e.texto );
      mi.setEnabled( e.habilitada );
      mi.setData( e.id );
      if ( e.tipo == Tipo.PERSPECTIVA ) {
        mi.setSelection( e.marcada );
      }
      if ( e.ayuda != null ) {
        try {
          mi.setToolTipText( e.ayuda );
        } catch ( Throwable ignorada ) {
          // SWT sin tooltips de menú
        }
      }
      mi.addListener( SWT.Selection, ev -> {
        if ( e.tipo == Tipo.PERSPECTIVA && !( (MenuItem) ev.widget ).getSelection() ) {
          return; // el RADIO que se desmarca también avisa
        }
        accion.accept( e.id );
      } );
    }
  }

  /** El estado de Spoon para el modelo. Nunca lanza. (UI) */
  static Contexto contexto( Spoon sp ) {
    Contexto c = new Contexto();
    try {
      AbstractMeta m = activo();
      if ( m instanceof TransMeta ) {
        c.documento = TRANSFORMACION;
        PlanoConstruir.Progreso pr = PlanoConstruir.progreso( (TransMeta) m );
        if ( pr != null ) {
          c.boceto = rotulos().f( "menu_sketch", pr.construidas, pr.total );
        }
        Leccion l = LeccionMotor.deDocumento( (TransMeta) m );
        if ( l != null ) {
          synchronized ( l ) {
            c.leccion = l.estado != Leccion.Estado.STOPPED;
          }
        }
      } else if ( m instanceof JobMeta ) {
        c.documento = JOB;
      }
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logDebug( "Pentaho Live: IDC menu without the active document: " + t );
    }
    try {
      SpoonPerspectiveManager pm = SpoonPerspectiveManager.getInstance();
      SpoonPerspective act = pm.getActivePerspective();
      c.activa = act == null ? null : act.getId();
      Locale loc = LanguageChoice.getInstance().getDefaultLocale();
      List<String[]> reg = new ArrayList<String[]>();
      for ( SpoonPerspective p : pm.getPerspectives() ) {
        String n = null;
        try {
          n = p.getDisplayName( loc );
        } catch ( Throwable ignorada ) {
          // sin nombre: el id
        }
        reg.add( new String[] { p.getId(), n == null || n.trim().isEmpty() ? p.getId() : n } );
      }
      c.perspectivas.addAll( perspectivasVisibles( reg, ocultas( pm ) ) );
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logDebug( "Pentaho Live: IDC menu without perspectives: " + t );
    }
    return c;
  }

  /**
   * {id: oculta} de los datos del selector de Spoon (SpoonPerspectiveManager.PerspectiveManager.perspectiveList, por
   * reflexión: no son públicos); null si no se pueden leer.
   */
  private static Map<String, Boolean> ocultas( SpoonPerspectiveManager pm ) {
    try {
      Method mm = SpoonPerspectiveManager.class.getDeclaredMethod( "getPerspectiveManagerMap" );
      mm.setAccessible( true );
      Map<?, ?> mapa = (Map<?, ?>) mm.invoke( pm );
      Collection<?> valores = mapa == null ? Collections.emptyList() : mapa.values();
      for ( Object man : valores ) {
        Field f = man.getClass().getDeclaredField( "perspectiveList" );
        f.setAccessible( true );
        List<?> datos = (List<?>) f.get( man );
        Map<String, Boolean> r = new LinkedHashMap<String, Boolean>();
        for ( Object x : datos ) {
          Method gid = x.getClass().getDeclaredMethod( "getId" );
          Method hid = x.getClass().getDeclaredMethod( "isHidden" );
          gid.setAccessible( true );
          hid.setAccessible( true );
          r.put( (String) gid.invoke( x ), (Boolean) hid.invoke( x ) );
        }
        return r; // todos los PerspectiveManager comparten la misma lista
      }
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logDebug( "Pentaho Live: perspective switcher data not readable: " + t );
    }
    return null;
  }

  /** El documento activo (la pestaña de lienzo elegida, en cualquier perspectiva), o null. (UI) */
  static AbstractMeta activo() {
    try {
      return Docs.activo();
    } catch ( Throwable t ) {
      return null;
    }
  }

  /** Lo que hace cada entrada. Nunca lanza. (UI) */
  static void ejecutar( Spoon sp, String id ) {
    Rotulos r = rotulos();
    try {
      if ( id == null ) {
        return;
      }
      if ( id.startsWith( PERSPECTIVA ) ) {
        sp.loadPerspective( id.substring( PERSPECTIVA.length() ) ); // lo mismo que el selector de Spoon
        return;
      }
      if ( ACERCA.equals( id ) ) {
        acerca( sp.getShell(), r );
        return;
      }
      if ( LECCION.equals( id ) ) {
        leccion( sp.getShell(), r );
        return;
      }
      if ( DISENO.equals( id ) ) {
        DisenoPerspective.mostrar( sp, null );
        return;
      }
      AbstractMeta m = activo();
      if ( m == null ) {
        aviso( sp.getShell(), r, r.s( "no_document" ) );
        return;
      }
      if ( ORDENAR.equals( id ) ) {
        Docs.activar( m );
        OrdenarLienzo.ejecutar( sp, m );
      } else if ( CALIDAD.equals( id ) ) {
        IdcCalidad.abrir( sp.getShell(), m, r );
      } else if ( MAPEO.equals( id ) ) {
        if ( m instanceof TransMeta ) {
          IdcMapeo.abrir( sp.getShell(), (TransMeta) m, Rotulos.de( PlanoInforme.AREA, r.idioma() ) );
        } else {
          aviso( sp.getShell(), r, r.s( "r_only_trans" ) );
        }
      } else if ( ROBUSTECER.equals( id ) ) {
        if ( m instanceof TransMeta ) {
          IdcRobustez.abrir( sp.getShell(), (TransMeta) m, r );
        } else {
          aviso( sp.getShell(), r, r.s( "r_only_trans" ) );
        }
      }
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logError( "Pentaho Live: IDC menu action '" + id + "' failed", t );
    }
  }

  /** Lección: muestra la pestaña de la lección viva de la transformación activa, o explica cómo empezar una. (UI) */
  static void leccion( Shell padre, Rotulos r ) {
    AbstractMeta m = activo();
    if ( !( m instanceof TransMeta ) ) {
      aviso( padre, r, r.s( "lesson_no_trans" ) + "\n\n" + r.s( "lesson_none" ) );
      return;
    }
    Leccion l = LeccionMotor.deDocumento( (TransMeta) m );
    boolean viva = false;
    if ( l != null ) {
      synchronized ( l ) {
        viva = l.estado != Leccion.Estado.STOPPED;
      }
    }
    if ( !viva ) {
      aviso( padre, r, r.s( "lesson_none" ) );
      return;
    }
    try {
      Docs.activar( m );
    } catch ( Throwable ignorada ) {
      // la pestaña se abre igual
    }
    String error = LeccionPanel.abrir( l );
    if ( error != null ) {
      aviso( padre, r, r.f( "lesson_not_shown", error ) );
    }
  }

  static void aviso( Shell padre, Rotulos r, String texto ) {
    MessageBox mb = new MessageBox( padre, SWT.ICON_INFORMATION | SWT.OK );
    mb.setText( r.s( "info_title" ) );
    mb.setMessage( texto );
    mb.open();
  }

  // ================================================================ Acerca de

  /** «Acerca de Pentaho Live»: símbolo, versión, autor y enlace al repositorio. Modal sobre Spoon. (UI) */
  static Shell acerca( Shell padre, Rotulos r ) {
    Shell s = crearAcerca( padre, r );
    abrirShell( s, padre );
    return s;
  }

  /** La ventana «Acerca de», armada y sin abrir (las pruebas la muestran fuera de la pantalla). (UI) */
  static Shell crearAcerca( Shell padre, Rotulos r ) {
    final Shell s = new Shell( padre, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL );
    s.setText( r.s( "about_title" ) );
    GridLayout gl = new GridLayout( 2, false );
    gl.marginWidth = 18;
    gl.marginHeight = 16;
    gl.horizontalSpacing = 16;
    s.setLayout( gl );
    final Image grande = imagenPng( s.getDisplay(), 74 );
    Label simbolo = new Label( s, SWT.NONE );
    if ( grande != null ) {
      simbolo.setImage( grande );
      s.addListener( SWT.Dispose, e -> grande.dispose() );
    }
    simbolo.setLayoutData( new GridData( SWT.LEFT, SWT.TOP, false, false, 1, 4 ) );
    Label v = new Label( s, SWT.NONE );
    v.setText( r.f( "about_version", McpLifecycleListener.VERSION ) );
    v.setFont( IdcCalidad.negrita( v, 1.3 ) );
    Label t = new Label( s, SWT.WRAP );
    t.setText( r.s( "about_text" ) );
    GridData gd = new GridData( SWT.FILL, SWT.TOP, true, false );
    gd.widthHint = 380;
    t.setLayoutData( gd );
    Label a = new Label( s, SWT.NONE );
    a.setText( r.s( "about_author" ) );
    Link enlace = new Link( s, SWT.NONE );
    enlace.setText( r.f( "about_link", REPO, REPO.replace( "https://", "" ) ) );
    enlace.addListener( SWT.Selection, e -> {
      try {
        Program.launch( REPO );
      } catch ( Throwable ignorada ) {
        // sin navegador
      }
    } );
    Button cerrar = new Button( s, SWT.PUSH );
    cerrar.setText( r.s( "close" ) );
    GridData gb = new GridData( SWT.RIGHT, SWT.BOTTOM, true, false, 2, 1 );
    gb.widthHint = 96;
    cerrar.setLayoutData( gb );
    cerrar.addListener( SWT.Selection, e -> s.close() );
    s.setDefaultButton( cerrar );
    s.pack();
    return s;
  }

  /** Centra la Shell sobre su padre y la abre. (UI) */
  static void abrirShell( Shell s, Shell padre ) {
    if ( padre != null && !padre.isDisposed() ) {
      Rectangle p = padre.getBounds();
      Point t = s.getSize();
      s.setLocation( p.x + Math.max( 0, ( p.width - t.x ) / 2 ), p.y + Math.max( 0, ( p.height - t.y ) / 3 ) );
    }
    s.open();
  }

  // ================================================================ icono

  /** El símbolo IDC para la barra (16 px de alto), una sola Image por Display; null si no se pudo crear. */
  static synchronized Image imagen( Display d ) {
    if ( icono != null && !icono.isDisposed() && icono.getDevice() == d ) {
      return icono;
    }
    try {
      icono = new Image( d, new ImageData( new ByteArrayInputStream( pngBarra() ) ) );
    } catch ( Throwable t ) {
      icono = null;
    }
    return icono;
  }

  /**
   * El icono de la barra ocupa el hueco de 16 x 16 de los demás (la ToolBar de Windows usa un solo tamaño de imagen
   * para todos sus botones y estiraría uno más ancho). Celdas de 3 x 3 px con 1 de separación: 15 x 11, centrado.
   */
  static final int LADO = 16;
  static final int CELDA = 3;
  static final int HUECO = 1;
  /**
   * Las celdas apagadas en la barra: un gris medio opaco con contraste de 3:1 o más sobre fondo claro (#f0f0f0) y
   * sobre fondo oscuro (#2b2b2b), como las encendidas (las del manual, #d6deea y #202b3c, desaparecen a 16 px sobre
   * el fondo contrario).
   */
  static final int[] GRIS_BARRA = { 0x7a, 0x84, 0x96 };

  /** Origen de la rejilla dentro de los 16 x 16 (x, y). */
  static int[] origenBarra() {
    int w = SimboloIdc.COLUMNAS * CELDA + ( SimboloIdc.COLUMNAS - 1 ) * HUECO;
    int h = SimboloIdc.FILAS * CELDA + ( SimboloIdc.FILAS - 1 ) * HUECO;
    return new int[] { ( LADO - w ) / 2, ( LADO - h ) / 2 };
  }

  /**
   * El símbolo IDC para la barra de Spoon: 16 x 16 px, todas las celdas opacas y alineadas al píxel (sin
   * antialiasing, que a este tamaño las desdibuja), las encendidas con los colores de la marca y las apagadas en
   * {@link #GRIS_BARRA}; el resto transparente. PNG con alfa.
   */
  static byte[] pngBarra() throws java.io.IOException {
    int[] o = origenBarra();
    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage( LADO, LADO,
      java.awt.image.BufferedImage.TYPE_INT_ARGB );
    java.awt.Graphics2D g = img.createGraphics();
    try {
      for ( int f = 0; f < SimboloIdc.FILAS; f++ ) {
        for ( int c = 0; c < SimboloIdc.COLUMNAS; c++ ) {
          String t = SimboloIdc.tipo( f, c );
          int[] rgb = SimboloIdc.APAGADA.equals( t ) ? GRIS_BARRA
            : io.github.ivi2511.pentahomcp.informes.tema.Paleta.rgb( SimboloIdc.color( SimboloIdc.CLARO, t ) );
          g.setColor( new java.awt.Color( rgb[ 0 ], rgb[ 1 ], rgb[ 2 ] ) );
          g.fillRect( o[ 0 ] + c * ( CELDA + HUECO ), o[ 1 ] + f * ( CELDA + HUECO ), CELDA, CELDA );
        }
      }
    } finally {
      g.dispose();
    }
    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write( img, "png", bo );
    return bo.toByteArray();
  }

  /** El símbolo IDC (variante clara, la de fondos claros) de anchoPx de ancho como Image SWT; null si falla. */
  static Image imagenPng( Display d, int anchoPx ) {
    try {
      byte[] png = SimboloIdc.png( anchoPx, SimboloIdc.CLARO, 1f );
      ImageData id = new ImageData( new ByteArrayInputStream( png ) );
      return new Image( d, id );
    } catch ( Throwable t ) {
      return null;
    }
  }

  // ================================================================ trabajo en segundo plano

  /**
   * Corre trabajo en un hilo propio (nunca en el de UI) y entrega el resultado o el error en el hilo de UI, solo si
   * la Shell sigue abierta.
   */
  static <T> Thread enHilo( final Shell ventana, String nombre, final Callable<T> trabajo, final Consumer<T> bien,
                            final Consumer<Throwable> mal ) {
    final Display d = ventana.getDisplay();
    Thread h = new Thread( () -> {
      T res = null;
      Throwable err = null;
      try {
        res = trabajo.call();
      } catch ( Throwable t ) {
        err = t;
      }
      final T r = res;
      final Throwable e = err;
      try {
        if ( !d.isDisposed() ) {
          d.asyncExec( () -> {
            if ( ventana.isDisposed() ) {
              return;
            }
            if ( e == null ) {
              bien.accept( r );
            } else {
              mal.accept( e );
            }
          } );
        }
      } catch ( Throwable ignorada ) {
        // Spoon se cerró
      }
    }, nombre );
    h.setDaemon( true );
    h.start();
    return h;
  }

  /** El mensaje de un error para la UI (el de la ToolError tal cual). */
  static String mensaje( Throwable t ) {
    Throwable c = t;
    while ( c.getCause() != null && c.getCause() != c && ( c.getMessage() == null || c.getMessage().isEmpty() ) ) {
      c = c.getCause();
    }
    String m = c.getMessage();
    return m == null || m.isEmpty() ? c.getClass().getSimpleName() : m;
  }
}
