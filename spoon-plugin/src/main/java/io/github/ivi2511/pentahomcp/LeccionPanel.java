package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.trans.TransGraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.Leccion.Estado;
import io.github.ivi2511.pentahomcp.Leccion.Paso;
import io.github.ivi2511.pentahomcp.Leccion.Ritmo;
import io.github.ivi2511.pentahomcp.LeccionLayout.Pedida;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Modo clase, WP4: la pestana «Leccion» del panel de resultados de una transformacion (docs/modo-clase-diseno.md §6),
 * junto a Logging, Metricas y Preview data: la lista de pasos (hecho / ahora / pendiente), el texto del paso actual
 * (sus notas completas, tambien las que no entraron en el lienzo), las filas de su vista previa en una tabla propia
 * (la de Preview data la pisan las ejecuciones) y los botones «◀ Atras», «Pausa», «Siguiente ▶», «Reiniciar» y
 * «Terminar», mas A-/A+ para el proyector.
 * <p>
 * «Siguiente ▶» es {@link LeccionMotor#clic}: avanza el hilo de la leccion (los clics de mas se descartan alli). Los
 * demas botones son {@link LeccionMotor#controlar} en un hilo aparte (pueden esperar a la UI y a la vista previa).
 * La pestana se refresca con un timerExec que mira el contador de cambios de la leccion: sin hilos propios.
 * <p>
 * Si el usuario oculta el panel de resultados, Spoon desecha el composite con la pestana: el DisposeListener pausa la
 * leccion (motivo panel_hidden, solo guided/auto) y, al volver a abrir el panel, {@link #vigilar} repone la pestana
 * (el gancho loadTab de {@link LeccionTabExtender} no llega en PDI 9.4: Spoon no registra ese tipo de plugin).
 * Reponerla es idempotente y reanuda la pausa que puso el panel (no una pausa pedida).
 * <p>
 * A-/A+ cambia la letra de la pestana, no la de las notas: un restyle a mitad de clase reacomoda notas con steps
 * que todavia no existen y deja un Ctrl+Z encima del paso (que «Atras» ya no podria deshacer). Las notas se agrandan
 * con font_size en lesson_start o con lesson_notes restyle.
 * <p>
 * Textos: area «leccion» de {@link Rotulos} en el idioma de la leccion (la clase se proyecta en ese idioma).
 */
final class LeccionPanel {

  static final String AREA = "leccion";
  /** Clave del CTabItem con su LeccionPanel. */
  static final String CLAVE = "pentaho-live-leccion";
  static final int REFRESCO_MS = 250;
  static final int VIGILAR_MS = 500;
  static final int FUENTE_MIN = 7;
  static final int FUENTE_MAX = 28;

  static final String HECHO = "✓";
  static final String AHORA = "▶";
  static final String PENDIENTE = "○";

  /** Letra de la pestana (A-/A+), para todas las lecciones mientras Spoon este abierto; 0 = la del sistema. */
  static volatile int tamFuente;

  /** Confirma «Terminar». Las pruebas lo reemplazan (sin dialogo modal). */
  interface Confirmador {
    boolean confirmar( Shell padre, String titulo, String texto );
  }

  static volatile Confirmador confirmador = ( padre, titulo, texto ) -> {
    MessageBox mb = new MessageBox( padre, SWT.ICON_QUESTION | SWT.YES | SWT.NO );
    mb.setText( titulo );
    mb.setMessage( texto );
    return mb.open() == SWT.YES;
  };

  final Leccion l;
  final LeccionMotor.Entorno ent;
  /** El TransGraph de Spoon, o null (pruebas). */
  final TransGraph grafo;
  final Rotulos r;
  final CTabItem item;
  final Composite raiz;
  final Label titulo;
  final Label estado;
  final Button menos;
  final Button mas;
  final Table pasos;
  final Text ahora;
  final Group grupoFilas;
  final Table filas;
  final Button atras;
  final Button pausa;
  final Button siguiente;
  final Label aviso;
  final Button reiniciar;
  final Button terminar;

  private Font fuente;
  private long visto = -1;
  private Object filasVistas;
  /** Lo desecha el propio panel (reemplazo): no pausa la leccion. */
  private boolean propio;
  /** Una accion de los botones corriendo en su hilo. (UI) */
  private boolean ocupado;
  private String avisoAccion;

  // ---------------------------------------------------------------- abrir / reponer

  /** El panel de una pestana, o null. (UI) */
  static LeccionPanel de( CTabFolder f ) {
    if ( f == null || f.isDisposed() ) {
      return null;
    }
    for ( CTabItem i : f.getItems() ) {
      Object o = i.getData( CLAVE );
      if ( o instanceof LeccionPanel && !i.isDisposed() ) {
        return (LeccionPanel) o;
      }
    }
    return null;
  }

  /**
   * Agrega la pestana de la leccion (idempotente: si ya esta la de esa leccion, la devuelve; la de otra leccion la
   * reemplaza). Si la leccion estaba pausada porque se oculto el panel, la reanuda. (UI)
   */
  static LeccionPanel agregar( CTabFolder f, Leccion l, LeccionMotor.Entorno ent ) {
    return agregar( f, null, l, ent );
  }

  /** Igual, en el panel de resultados de un TransGraph de Spoon (para reponer la pestana si se oculta). (UI) */
  static LeccionPanel agregar( TransGraph tg, Leccion l, LeccionMotor.Entorno ent ) {
    return agregar( tg.extraViewTabFolder, tg, l, ent );
  }

  private static LeccionPanel agregar( CTabFolder f, TransGraph tg, Leccion l, LeccionMotor.Entorno ent ) {
    LeccionPanel p = de( f );
    if ( p != null ) {
      if ( p.l == l ) {
        LeccionMotor.panelVisible( l );
        return p;
      }
      p.propio = true;
      p.item.dispose();
    }
    p = new LeccionPanel( f, tg, l, ent );
    LeccionMotor.panelVisible( l );
    return p;
  }

  /**
   * Con el panel de resultados oculto, mira cada {@link #VIGILAR_MS} si el usuario lo volvio a abrir y repone la
   * pestana (y la elige). Hace falta porque el Spoon de PDI 9.4 no registra SpoonUiExtenderPluginType: los
   * extensores loadTab de la carpeta plugins nunca se escanean (probado en Spoon real), asi que
   * {@link LeccionTabExtender} queda solo para un Spoon que si lo registre. Termina con la leccion detenida o
   * reemplazada, o con la pestana del documento cerrada. (UI)
   */
  static void vigilar( final TransGraph tg, final Leccion l, final LeccionMotor.Entorno ent ) {
    final Display d = tg.getDisplay();
    d.timerExec( VIGILAR_MS, new Runnable() {
      @Override
      public void run() {
        try {
          if ( tg.isDisposed() || LeccionMotor.deDocumento( tg.getTransMeta() ) != l ) {
            return;
          }
          synchronized ( l ) {
            if ( l.estado == Estado.STOPPED ) {
              return;
            }
          }
          if ( tg.isExecutionResultsPaneVisible() && tg.extraViewTabFolder != null
            && !tg.extraViewTabFolder.isDisposed() ) {
            if ( de( tg.extraViewTabFolder ) == null ) {
              LeccionPanel p = agregar( tg, l, ent );
              tg.extraViewTabFolder.setSelection( p.item );
            }
            return;
          }
        } catch ( RuntimeException e ) {
          McpLifecycleListener.LOG.logDebug( "Pentaho Live: lesson tab watch failed: " + e );
          return;
        }
        d.timerExec( VIGILAR_MS, this );
      }
    } );
  }

  /**
   * lesson_start en Spoon: abre el panel de resultados de la transformacion (si estaba oculto) y elige la pestana.
   * null si quedo abierta; si no, el motivo (para warnings). (UI)
   */
  static String abrir( Leccion l ) {
    Spoon sp = LeccionMotor.spoonONulo();
    if ( sp == null ) {
      return "Spoon is not available";
    }
    TransGraph tg = sp.delegates.trans.findTransGraphOfTransformation( l.tm );
    if ( tg == null ) {
      return "the transformation has no canvas tab";
    }
    if ( !tg.isExecutionResultsPaneVisible() ) {
      tg.showExecutionResults(); // es un toggle: solo con el panel oculto (llama a addAllTabs y al extensor)
    }
    if ( tg.extraViewTabFolder == null || tg.extraViewTabFolder.isDisposed() ) {
      return "the results panel did not open";
    }
    LeccionPanel p = agregar( tg, l, LeccionMotor.spoon() );
    tg.extraViewTabFolder.setSelection( p.item );
    return null;
  }

  /** Gancho loadTab (TransGraph.addAllTabs): repone la pestana si el documento tiene una leccion viva. (UI) */
  static void alCargarPestanas( TransGraph tg ) {
    TransMeta tm = tg.getTransMeta();
    Leccion l = LeccionMotor.deDocumento( tm );
    if ( l == null || tg.extraViewTabFolder == null || tg.extraViewTabFolder.isDisposed() ) {
      return;
    }
    synchronized ( l ) {
      if ( l.estado == Estado.STOPPED ) {
        return;
      }
    }
    agregar( tg, l, LeccionMotor.spoon() );
  }

  // ---------------------------------------------------------------- armado

  private LeccionPanel( CTabFolder f, TransGraph tg, Leccion l, LeccionMotor.Entorno ent ) {
    this.l = l;
    this.ent = ent;
    this.grafo = tg;
    String idioma;
    synchronized ( l ) {
      idioma = l.idioma;
    }
    this.r = Rotulos.de( AREA, idioma );
    raiz = new Composite( f, SWT.NONE );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 6;
    gl.marginHeight = 4;
    raiz.setLayout( gl );

    Composite arriba = new Composite( raiz, SWT.NONE );
    arriba.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    GridLayout ga = new GridLayout( 4, false );
    ga.marginWidth = 0;
    ga.marginHeight = 0;
    arriba.setLayout( ga );
    titulo = new Label( arriba, SWT.NONE );
    titulo.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    estado = new Label( arriba, SWT.NONE );
    estado.setLayoutData( new GridData( SWT.END, SWT.CENTER, false, false ) );
    menos = boton( arriba, r.s( "font_smaller" ), r.s( "font_tooltip" ) );
    mas = boton( arriba, r.s( "font_larger" ), r.s( "font_tooltip" ) );

    SashForm sash = new SashForm( raiz, SWT.HORIZONTAL );
    sash.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    Group gp = grupo( sash, r.s( "steps" ) );
    pasos = new Table( gp, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER );
    new TableColumn( pasos, SWT.LEFT );
    Group gn = grupo( sash, r.s( "now" ) );
    ahora = new Text( gn, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER );
    grupoFilas = grupo( sash, r.s( "rows" ) );
    filas = new Table( grupoFilas, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER );
    filas.setHeaderVisible( true );
    filas.setLinesVisible( true );
    sash.setWeights( new int[] { 24, 44, 32 } );

    Composite abajo = new Composite( raiz, SWT.NONE );
    abajo.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    GridLayout gb = new GridLayout( 6, false );
    gb.marginWidth = 0;
    gb.marginHeight = 0;
    abajo.setLayout( gb );
    atras = boton( abajo, r.s( "back" ), r.s( "back_tooltip" ) );
    pausa = boton( abajo, r.s( "pause" ), null );
    siguiente = boton( abajo, r.s( "next" ), r.s( "next_tooltip" ) );
    aviso = new Label( abajo, SWT.NONE );
    aviso.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    reiniciar = boton( abajo, r.s( "restart" ), r.s( "restart_tooltip" ) );
    terminar = boton( abajo, r.s( "finish" ), r.s( "finish_tooltip" ) );

    pasos.addListener( SWT.Resize, e -> {
      if ( pasos.getColumnCount() > 0 ) {
        pasos.getColumn( 0 ).setWidth( Math.max( 50, pasos.getClientArea().width ) );
      }
    } );
    menos.addListener( SWT.Selection, e -> cambiarFuente( -1 ) );
    mas.addListener( SWT.Selection, e -> cambiarFuente( +1 ) );
    siguiente.addListener( SWT.Selection, e -> {
      LeccionMotor.clic( l );
      refrescar( true );
    } );
    atras.addListener( SWT.Selection, e -> accion( "back" ) );
    reiniciar.addListener( SWT.Selection, e -> accion( "restart" ) );
    pausa.addListener( SWT.Selection, e -> {
      Estado s;
      synchronized ( l ) {
        s = l.estado;
      }
      accion( s == Estado.PAUSED || s == Estado.OUT_OF_SYNC ? "resume" : "pause" );
    } );
    terminar.addListener( SWT.Selection, e -> {
      if ( confirmador.confirmar( raiz.getShell(), r.s( "finish_confirm_title" ), r.s( "finish_confirm" ) ) ) {
        accion( "stop" );
      }
    } );

    item = new CTabItem( f, SWT.NONE );
    item.setText( r.s( "tab" ) );
    item.setControl( raiz );
    item.setData( CLAVE, this );
    item.addDisposeListener( e -> {
      if ( !propio ) {
        LeccionMotor.panelOculto( l );
        if ( grafo != null && !grafo.isDisposed() ) {
          vigilar( grafo, l, ent );
        }
      }
      if ( !raiz.isDisposed() ) {
        raiz.dispose();
      }
    } );
    raiz.addDisposeListener( e -> {
      if ( fuente != null && !fuente.isDisposed() ) {
        fuente.dispose();
      }
    } );
    aplicarFuente();
    refrescar( true );
    final Display d = raiz.getDisplay();
    d.timerExec( REFRESCO_MS, new Runnable() {
      @Override
      public void run() {
        if ( raiz.isDisposed() ) {
          return;
        }
        try {
          refrescar( false );
        } catch ( RuntimeException e ) {
          McpLifecycleListener.LOG.logDebug( "Pentaho Live: lesson tab refresh failed: " + e );
        }
        d.timerExec( REFRESCO_MS, this );
      }
    } );
  }

  private static Button boton( Composite c, String texto, String ayuda ) {
    Button b = new Button( c, SWT.PUSH );
    b.setText( texto );
    if ( ayuda != null ) {
      b.setToolTipText( ayuda );
    }
    return b;
  }

  private static Group grupo( Composite c, String texto ) {
    Group g = new Group( c, SWT.NONE );
    g.setText( texto );
    g.setLayout( new FillLayout() );
    return g;
  }

  // ---------------------------------------------------------------- acciones

  /** lesson_control en un hilo aparte (back/restart pueden esperar a la UI; goto, a la vista previa). (UI) */
  private void accion( final String a ) {
    if ( ocupado ) {
      return;
    }
    ocupado = true;
    avisoAccion = null;
    refrescar( true );
    final Display d = raiz.getDisplay();
    Thread t = new Thread( () -> {
      String w = null;
      try {
        ObjectNode o = LeccionMotor.controlar( ent, l, a, null );
        JsonNode ws = o.path( "warnings" );
        if ( ws.size() > 0 ) {
          w = ws.get( ws.size() - 1 ).asText();
        }
      } catch ( Exception e ) {
        w = TransOps.mensaje( e );
      }
      final String fw = w;
      if ( !d.isDisposed() ) {
        d.asyncExec( () -> {
          ocupado = false;
          avisoAccion = fw;
          if ( !raiz.isDisposed() ) {
            refrescar( true );
          }
        } );
      }
    }, "pentaho-live-leccion-panel-" + l.id );
    t.setDaemon( true );
    t.start();
  }

  /** A-/A+: la letra de la pestana. (UI) */
  void cambiarFuente( int delta ) {
    int base = tamFuente > 0 ? tamFuente : raiz.getDisplay().getSystemFont().getFontData()[ 0 ].getHeight();
    tamFuente = Math.max( FUENTE_MIN, Math.min( FUENTE_MAX, base + delta ) );
    aplicarFuente();
    raiz.layout( true, true );
  }

  /** Tamaño de letra que muestra la pestaña ahora. (UI) */
  int alturaFuente() {
    return ahora.getFont().getFontData()[ 0 ].getHeight();
  }

  private void aplicarFuente() {
    if ( tamFuente <= 0 ) {
      return;
    }
    FontData[] fd = raiz.getDisplay().getSystemFont().getFontData();
    for ( FontData x : fd ) {
      x.setHeight( tamFuente );
    }
    Font nueva = new Font( raiz.getDisplay(), fd );
    fuenteA( raiz, nueva );
    if ( fuente != null && !fuente.isDisposed() ) {
      fuente.dispose();
    }
    fuente = nueva;
    visto = -1; // las columnas de la tabla se vuelven a medir
    filasVistas = null;
  }

  private static void fuenteA( Control c, Font f ) {
    c.setFont( f );
    if ( c instanceof Composite ) {
      for ( Control h : ( (Composite) c ).getChildren() ) {
        fuenteA( h, f );
      }
    }
  }

  // ---------------------------------------------------------------- refresco

  /** Lo que muestra la pestana, leido con el monitor de la leccion. */
  private static final class Vista {
    String titulo;
    String nivel;
    Estado estado;
    String motivo;
    Ritmo ritmo;
    int posicion;
    int total;
    final List<String> pasos = new ArrayList<String>();
    String ahora;
    String filasStep;
    ObjectNode filas;
    String aviso;
  }

  /** Vuelve a pintar si la leccion cambio (o siempre con forzar). (UI) */
  void refrescar( boolean forzar ) {
    Vista v;
    synchronized ( l ) {
      if ( !forzar && l.cambios == visto ) {
        return;
      }
      visto = l.cambios;
      v = vista();
    }
    titulo.setText( r.f( "header", v.titulo, v.nivel, v.posicion, v.total ) );
    String est = r.s( "state_" + v.estado.json );
    if ( v.motivo != null && r.tiene( "reason_" + v.motivo ) ) {
      est += " (" + r.s( "reason_" + v.motivo ) + ")";
    }
    estado.setText( est );

    if ( pasos.getItemCount() != v.pasos.size() ) {
      pasos.removeAll();
      for ( int i = 0; i < v.pasos.size(); i++ ) {
        new TableItem( pasos, SWT.NONE );
      }
    }
    for ( int i = 0; i < v.pasos.size(); i++ ) {
      TableItem ti = pasos.getItem( i );
      if ( !v.pasos.get( i ).equals( ti.getText() ) ) {
        ti.setText( v.pasos.get( i ) );
      }
    }
    if ( v.posicion > 0 && v.posicion <= pasos.getItemCount() ) {
      pasos.setSelection( v.posicion - 1 );
    } else {
      pasos.deselectAll();
    }
    if ( !v.ahora.equals( ahora.getText() ) ) {
      ahora.setText( v.ahora );
    }
    if ( v.filas != filasVistas ) {
      filasVistas = v.filas;
      llenarFilas( v.filasStep, v.filas );
    }

    boolean vivo = !ocupado && v.estado != Estado.STOPPED;
    boolean manual = v.ritmo == Ritmo.MANUAL;
    siguiente.setEnabled( !ocupado && v.estado == Estado.WAITING && !manual );
    siguiente.setToolTipText( manual ? r.s( "next_manual" ) : r.s( "next_tooltip" ) );
    atras.setEnabled( vivo && v.posicion > 0 && v.estado != Estado.PLAYING );
    reiniciar.setEnabled( vivo && v.posicion > 0 && v.estado != Estado.PLAYING );
    terminar.setEnabled( vivo );
    String tp;
    String ayuda = null;
    if ( v.estado == Estado.PAUSED ) {
      tp = r.s( "resume" );
    } else if ( v.estado == Estado.OUT_OF_SYNC ) {
      tp = r.s( "resync" );
      ayuda = r.s( "resync_tooltip" );
    } else {
      tp = r.s( "pause" );
    }
    pausa.setText( tp );
    pausa.setToolTipText( ayuda );
    pausa.setEnabled( vivo && ( v.estado == Estado.PAUSED || v.estado == Estado.OUT_OF_SYNC
      || v.estado == Estado.WAITING || v.estado == Estado.PLAYING ) );
    String a = avisoAccion != null ? avisoAccion : v.aviso;
    aviso.setText( a == null ? "" : a.replace( '\n', ' ' ) );
    aviso.setToolTipText( a );
    raiz.layout( true, true );
  }

  /** (con el monitor de la leccion) */
  private Vista vista() {
    Vista v = new Vista();
    v.titulo = l.titulo == null ? "" : l.titulo;
    v.nivel = r.tiene( "level_" + l.nivel ) ? r.s( "level_" + l.nivel ) : l.nivel;
    v.estado = l.estado;
    v.motivo = l.motivo;
    v.ritmo = l.ritmo;
    v.posicion = l.posicion;
    v.total = l.pasos.size();
    for ( Paso p : l.pasos ) {
      String marca = p.n <= l.posicion ? HECHO : p.n == l.posicion + 1 ? AHORA : PENDIENTE;
      v.pasos.add( marca + " " + p.n + " · " + p.step );
    }
    StringBuilder sb = new StringBuilder();
    if ( l.posicion == 0 ) {
      if ( l.textoTitulo != null ) {
        sb.append( l.textoTitulo );
      } else if ( l.intro != null ) {
        sb.append( r.s( "intro_label" ) ).append( ": " ).append( l.intro );
      }
    } else {
      Paso p = l.pasos.get( l.posicion - 1 );
      if ( p.pedidas.isEmpty() ) {
        sb.append( p.textoNota == null ? p.n + " · " + p.step : p.textoNota );
      }
      for ( Pedida pd : p.pedidas ) {
        if ( sb.length() > 0 ) {
          sb.append( "\n\n" );
        }
        sb.append( pd.texto );
      }
      for ( int i = p.vistas.size() - 1; i >= 0 && v.filas == null; i-- ) {
        synchronized ( l.filas ) {
          v.filas = l.filas.get( p.vistas.get( i ) );
        }
        v.filasStep = p.vistas.get( i );
      }
    }
    if ( l.estado == Estado.DONE && l.outro != null ) {
      sb.append( "\n\n" ).append( r.s( "outro_label" ) ).append( ": " ).append( l.outro );
    } else if ( l.posicion < l.pasos.size() ) {
      Paso q = l.pasos.get( l.posicion );
      sb.append( "\n\n" ).append( r.f( "upcoming", q.n, q.step ) );
    }
    v.ahora = sb.toString().replace( "\r\n", "\n" ).replace( "\n", Text.DELIMITER );
    v.aviso = l.avisos.isEmpty() ? null : l.avisos.get( l.avisos.size() - 1 );
    return v;
  }

  /** La tabla de filas: campos de la vista previa como columnas. (UI) */
  private void llenarFilas( String step, ObjectNode o ) {
    filas.setRedraw( false );
    try {
      filas.removeAll();
      for ( TableColumn c : filas.getColumns() ) {
        c.dispose();
      }
      JsonNode rows = o == null ? null : o.path( "rows" );
      if ( o == null || !rows.isArray() ) {
        grupoFilas.setText( r.s( "rows" ) );
        TableColumn c = new TableColumn( filas, SWT.LEFT );
        c.setText( "" );
        String t = o != null && o.path( "errors" ).isArray() && o.path( "errors" ).size() > 0
          ? o.path( "errors" ).get( 0 ).asText() : r.s( "no_rows" );
        new TableItem( filas, SWT.NONE ).setText( 0, t );
        c.pack();
        return;
      }
      grupoFilas.setText( r.f( "rows_of", step, rows.size() ) );
      JsonNode campos = o.path( "fields" );
      int n = Math.max( 1, campos.size() );
      for ( int i = 0; i < n; i++ ) {
        TableColumn c = new TableColumn( filas, SWT.LEFT );
        c.setText( i < campos.size() ? campos.get( i ).path( "name" ).asText() : "" );
      }
      for ( JsonNode fila : rows ) {
        TableItem ti = new TableItem( filas, SWT.NONE );
        for ( int i = 0; i < n && i < fila.size(); i++ ) {
          ti.setText( i, fila.get( i ).isNull() ? "<null>" : fila.get( i ).asText() );
        }
      }
      for ( TableColumn c : filas.getColumns() ) {
        c.pack();
      }
    } finally {
      filas.setRedraw( true );
    }
  }
}
