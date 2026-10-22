package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.core.EngineMetaInterface;
import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPerspectiveListener;
import org.pentaho.ui.xul.XulOverlay;
import org.pentaho.ui.xul.impl.XulEventHandler;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * La perspectiva Corrector / Grader / Corretor (docs/corrector-diseno.md §9, plan B). La registra
 * {@link GraderSpoonPlugin}; adentro vive {@link PanelCorrector}.
 * <p>
 * Igual que {@link ReportsPerspective}: Spoon llama {@link #getUI()} en el hilo de UI la primera vez que se activa y
 * re-cuelga el Composite en una tarjeta del deck; ningun metodo lanza; {@link #getActiveMeta()} devuelve null (Ctrl+S
 * no guarda una pestana oculta). El servicio real ({@link ServicioSpoon}) copia la pestana activa para «Crear pack
 * desde esta transformacion» y abre en Data Integration la copia que corrio un alumno.
 *
 * @dueño C8
 */
public class GraderPerspective implements SpoonPerspective {

  /** Id de la perspectiva; la tarjeta del deck es "perspective-pentaho-live-grader". */
  public static final String ID = "pentaho-live-grader";

  static final String CANAL = "PentahoLive-Corrector";

  /** Solo pruebas: padre de la UI cuando no hay Spoon. */
  static volatile Shell padreSinSpoon;

  /** Solo pruebas: el servicio del panel (en Spoon, un {@link ServicioSpoon}). */
  static volatile PanelCorrector.Servicio servicioPrueba;

  private static volatile LogChannelInterface log;

  private final List<SpoonPerspectiveListener> listeners = new CopyOnWriteArrayList<SpoonPerspectiveListener>();
  private Composite ui;
  private PanelCorrector panel;

  public GraderPerspective() {
    // trivial: corre dentro de SpoonPluginManager.<clinit>; ningun widget hasta getUI()
  }

  @Override
  public String getId() {
    return ID;
  }

  /** "Grader", "Corrector" o "Corretor". */
  @Override
  public String getDisplayName( Locale locale ) {
    try {
      String s = Rotulos.de( PanelCorrector.AREA, locale == null ? null : locale.toString() ).s( "ui.perspectiva" );
      return s == null || s.isEmpty() || "ui.perspectiva".equals( s ) ? "Grader" : s;
    } catch ( Throwable t ) {
      return "Grader";
    }
  }

  @Override
  public InputStream getPerspectiveIcon() {
    return null;
  }

  /** Perezoso, como Informes: un Composite sobre la Shell de Spoon y adentro el panel; si falla, un Label. */
  @Override
  public Composite getUI() {
    try {
      if ( ui == null || ui.isDisposed() ) {
        panel = null;
        ui = new Composite( padre(), SWT.NONE );
        ui.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
        ui.setLayout( new FillLayout() );
        try {
          panel = new PanelCorrector( ui, servicio(), ReportsPerspective.idiomaUi() );
        } catch ( Throwable t ) {
          panel = null;
          error( "Pentaho Live: the Grader panel could not be built", t );
          for ( Control c : ui.getChildren() ) {
            c.dispose();
          }
          Label l = new Label( ui, SWT.WRAP );
          l.setText( PanelInformes.esc( Rotulos.de( PanelCorrector.AREA, ReportsPerspective.idiomaUi() ).f(
            "ui.error_panel", String.valueOf( t ) ) ) );
        }
      }
    } catch ( Throwable t ) {
      error( "Pentaho Live: the Grader perspective could not create its UI", t );
    }
    return ui;
  }

  @Override
  public void setActive( boolean active ) {
    for ( SpoonPerspectiveListener l : listeners ) {
      try {
        if ( active ) {
          l.onActivation();
        } else {
          l.onDeactication(); // sic: asi se llama en la API de 9.4
        }
      } catch ( Throwable t ) {
        error( "Pentaho Live: a perspective listener failed", t );
      }
    }
    try {
      if ( panel != null && active ) {
        panel.alActivar();
      }
    } catch ( Throwable t ) {
      error( "Pentaho Live: the Grader panel failed when switching perspective", t );
    }
  }

  @Override
  public List<XulOverlay> getOverlays() {
    return Collections.emptyList();
  }

  @Override
  public List<XulEventHandler> getEventHandlers() {
    return Collections.emptyList();
  }

  @Override
  public void addPerspectiveListener( SpoonPerspectiveListener listener ) {
    if ( listener != null && !listeners.contains( listener ) ) {
      listeners.add( listener );
    }
  }

  @Override
  public EngineMetaInterface getActiveMeta() {
    return null;
  }

  /** No esta en la interfaz: Spoon.tabCloseSelected la busca por reflexion con otra perspectiva activa. */
  public boolean onFileClose() {
    return false;
  }

  /** El panel, o null si todavia no se creo la UI (o si fallo). */
  PanelCorrector panel() {
    return panel;
  }

  // ---------------------------------------------------------------- apoyo

  private static Shell padre() {
    Spoon sp = Spoon.getInstance();
    if ( sp != null && sp.getShell() != null && !sp.getShell().isDisposed() ) {
      return sp.getShell();
    }
    Shell s = padreSinSpoon;
    if ( s != null && !s.isDisposed() ) {
      return s;
    }
    Display d = Display.getCurrent();
    if ( d != null ) {
      if ( d.getActiveShell() != null ) {
        return d.getActiveShell();
      }
      Shell[] todas = d.getShells();
      if ( todas.length > 0 ) {
        return todas[ 0 ];
      }
    }
    throw new IllegalStateException( "No Spoon shell to create the Grader panel on" );
  }

  private static PanelCorrector.Servicio servicio() {
    PanelCorrector.Servicio s = servicioPrueba;
    return s != null ? s : new ServicioSpoon();
  }

  static LogChannelInterface log() {
    LogChannelInterface l = log;
    if ( l == null ) {
      l = new LogChannel( CANAL );
      log = l;
    }
    return l;
  }

  static void error( String texto, Throwable t ) {
    try {
      log().logError( texto, t );
    } catch ( Throwable ignorada ) {
      // sin log: lo importante es no romper Spoon
    }
  }

  // ---------------------------------------------------------------- servicio real

  /** El servicio del panel dentro de Spoon. */
  static final class ServicioSpoon implements PanelCorrector.Servicio {

    /** La pestana activa (una transformacion) copiada con getXML() del clon, como create_exercise_pack. (UI) */
    @Override
    public CorrectorTools.Copia solucionActiva( File tmp ) throws Exception {
      return CorrectorTools.copiaDeDocumento( Tools.obj(), tmp );
    }

    /** Una pestana nueva en Data Integration con el .ktr (como open_file). (UI) */
    @Override
    public void abrirEnSpoon( File ktr ) throws Exception {
      Spoon sp = Docs.spoon();
      Docs.perspectivaPrincipal();
      sp.openFile( ktr.getAbsolutePath(), false );
    }

    @Override
    public void log( String texto, Throwable t ) {
      try {
        if ( t == null ) {
          GraderPerspective.log().logBasic( texto );
        } else {
          GraderPerspective.log().logError( texto, t );
        }
      } catch ( Throwable ignorada ) {
        // sin log
      }
    }
  }
}
