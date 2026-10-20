package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.EngineMetaInterface;
import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.i18n.LanguageChoice;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPerspectiveListener;
import org.pentaho.di.ui.spoon.job.JobGraph;
import org.pentaho.di.ui.spoon.trans.TransGraph;
import org.pentaho.ui.xul.XulOverlay;
import org.pentaho.ui.xul.impl.XulEventHandler;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Disponibilidad;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.navegador.Chromium;
import io.github.ivi2511.pentahomcp.informes.render.RenderPptx;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * La perspectiva Informes / Reports / Relatorios (diseno §1.2 y §5). La registra {@link ReportsSpoonPlugin}.
 * <p>
 * Spoon llama {@link #getUI()} en el hilo de UI la primera vez que se activa (o al arrancar, si es la
 * perspectiva de inicio) y re-cuelga el Composite en una tarjeta del deck con {@code setParent}. El panel
 * ({@link PanelInformes}) vive adentro; esta clase le da el {@link ServicioSpoon}, el puente a las clases
 * package-private (Docs, RunMotor) y al motor ({@link InformeServicio}).
 * <p>
 * Ningun metodo lanza: {@code loadPerspective(int)} solo atrapa KettleException y una excepcion en
 * {@code getId}/{@code getDisplayName} durante {@code Spoon.init} deja a Spoon sin lienzo.
 * {@link #getActiveMeta()} devuelve null (opcion 1 de la investigacion): con Informes al frente Ctrl+S queda
 * deshabilitado y no guarda una pestana oculta.
 *
 * @dueño WP1
 */
public class ReportsPerspective implements SpoonPerspective {

  /** Id de la perspectiva; la tarjeta del deck es "perspective-pentaho-live-reports". */
  public static final String ID = "pentaho-live-reports";

  /** Canal de log de los informes. */
  static final String CANAL = "PentahoLive-Informes";

  /** Solo pruebas: padre de la UI cuando no hay Spoon (en Spoon es su Shell). */
  static volatile Shell padreSinSpoon;

  /** Solo pruebas: el servicio del panel (en Spoon, un {@link ServicioSpoon}). */
  static volatile PanelInformes.Servicio servicioPrueba;

  private static volatile LogChannelInterface log;

  private final List<SpoonPerspectiveListener> listeners = new CopyOnWriteArrayList<SpoonPerspectiveListener>();
  private Composite ui;
  private PanelInformes panel;

  public ReportsPerspective() {
    // trivial: corre dentro de SpoonPluginManager.<clinit>; ningun widget hasta getUI()
  }

  @Override
  public String getId() {
    return ID;
  }

  /** "Reports", "Informes" o "Relatorios". Spoon la llama una vez con el locale de LanguageChoice. */
  @Override
  public String getDisplayName( Locale locale ) {
    try {
      String s = Rotulos.de( PanelInformes.AREA, locale == null ? null : locale.toString() ).s( "perspective" );
      return s == null || s.isEmpty() || "perspective".equals( s ) ? "Reports" : s;
    } catch ( Throwable t ) {
      return "Reports";
    }
  }

  /** Spoon 9.4 nunca la llama. */
  @Override
  public InputStream getPerspectiveIcon() {
    return null;
  }

  /**
   * Perezoso: un Composite sobre la Shell de Spoon con {@code GridData(FILL, FILL, true, true)} (el deck lo
   * re-cuelga) y, adentro, el panel. Si el panel falla, un Label con el error en lugar de propagarlo.
   */
  @Override
  public Composite getUI() {
    try {
      if ( ui == null || ui.isDisposed() ) {
        panel = null;
        ui = new Composite( padre(), SWT.NONE );
        ui.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
        ui.setLayout( new FillLayout() );
        try {
          panel = new PanelInformes( ui, servicio(), idiomaUi() );
        } catch ( Throwable t ) {
          panel = null;
          error( "Pentaho Live: the Reports panel could not be built", t );
          for ( Control c : ui.getChildren() ) {
            c.dispose();
          }
          Label l = new Label( ui, SWT.WRAP );
          l.setText( PanelInformes.esc( Rotulos.de( PanelInformes.AREA, idiomaUi() ).f( "panel_error",
            String.valueOf( t ) ) ) );
        }
      }
    } catch ( Throwable t ) {
      error( "Pentaho Live: the Reports perspective could not create its UI", t );
    }
    return ui;
  }

  /** Avisa a los listeners y al panel (refresca documentos y arma la vista previa liviana). */
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
      if ( panel != null ) {
        if ( active ) {
          panel.alActivar();
        } else {
          panel.alDesactivar();
        }
      }
    } catch ( Throwable t ) {
      error( "Pentaho Live: the Reports panel failed when switching perspective", t );
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

  /** Null: Informes no tiene documento propio (Ctrl+S deshabilitado). Las tools MCP leen el tab folder (WP10). */
  @Override
  public EngineMetaInterface getActiveMeta() {
    return null;
  }

  /** No esta en la interfaz: Spoon.tabCloseSelected la busca por reflexion con otra perspectiva activa. */
  public boolean onFileClose() {
    return false;
  }

  /** El panel, o null si todavia no se creo la UI (o si fallo). */
  PanelInformes panel() {
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
    throw new IllegalStateException( "No Spoon shell to create the Reports panel on" );
  }

  private static PanelInformes.Servicio servicio() {
    PanelInformes.Servicio s = servicioPrueba;
    return s != null ? s : new ServicioSpoon();
  }

  /** Idioma de la UI de Spoon: es, pt o en (cualquier otro). */
  static String idiomaUi() {
    try {
      Locale l = LanguageChoice.getInstance().getDefaultLocale();
      return Rotulos.normalizar( l == null ? null : l.toString() );
    } catch ( Throwable t ) {
      return Rotulos.normalizar( Locale.getDefault().getLanguage() );
    }
  }

  static LogChannelInterface log() {
    LogChannelInterface l = log;
    if ( l == null ) {
      l = new LogChannel( CANAL );
      log = l;
    }
    return l;
  }

  /** Al log de errores sin lanzar nunca (se llama desde los catch de los metodos que Spoon invoca). */
  static void error( String texto, Throwable t ) {
    try {
      log().logError( texto, t );
    } catch ( Throwable ignorada ) {
      // sin log: lo importante es no romper Spoon
    }
  }

  // ---------------------------------------------------------------- servicio real

  /**
   * El servicio del panel dentro de Spoon: documentos abiertos con los ids de {@link Docs} (los mismos que
   * usa el MCP), el motor {@link InformeServicio}, los ajustes ({@link Ajustes}) y {@link Chromium}.
   */
  static final class ServicioSpoon implements PanelInformes.Servicio {

    // enSegundoPlano queda el del panel (su propio pool) y no InformeServicio.enPool: la tarea del panel
    // espera a armar(), que corre en el pool de 2 hilos de informes; si las dos cosas compartieran ese
    // pool, dos esperas a la vez (panel y build_report) lo dejarian sin hilos.

    @Override
    public List<PanelInformes.Documento> documentos() {
      List<PanelInformes.Documento> out = new ArrayList<PanelInformes.Documento>();
      Spoon sp = Spoon.getInstance();
      if ( sp == null ) {
        return out;
      }
      AbstractMeta activo = null;
      try {
        // el tab folder directo: no depende de la perspectiva activa (getActiveMeta seria null aca)
        TransGraph tg = sp.getActiveTransGraph();
        if ( tg != null ) {
          activo = tg.getTransMeta();
        } else {
          JobGraph jg = sp.getActiveJobGraph();
          activo = jg == null ? null : jg.getJobMeta();
        }
      } catch ( Throwable ignorada ) {
        // sin pestana activa
      }
      try {
        for ( AbstractMeta m : Docs.abiertos() ) {
          out.add( new PanelInformes.Documento( Docs.id( m ), Docs.nombre( m ), Docs.tipo( m ), m.getFilename(),
            m == activo, false ) );
        }
      } catch ( Exception e ) {
        ReportsPerspective.log().logDebug( "Pentaho Live: could not list the open documents: " + e );
      }
      return out;
    }

    @Override
    public Captura capturar( String docId ) throws Exception {
      for ( AbstractMeta m : Docs.abiertos() ) {
        if ( Docs.id( m ).equals( docId ) ) {
          return InformeServicio.capturar( Docs.spoon(), m );
        }
      }
      throw new IllegalStateException( Rotulos.de( PanelInformes.AREA, idiomaUi() ).f( "doc_closed", docId ) );
    }

    @Override
    public Captura capturarArchivo( String ruta, Display d ) throws Exception {
      return InformeServicio.capturarArchivo( ruta, d );
    }

    @Override
    public List<Disponibilidad> disponibles( Captura c, String idioma ) {
      return InformeServicio.disponibles( c, idioma );
    }

    @Override
    public Future<Informe> armar( Captura c, Pedido p, Tema t, Progreso pr ) {
      return InformeServicio.armar( c, p, t, pr );
    }

    @Override
    public String nombreBase( Informe inf, Tema t ) {
      return InformeServicio.nombreBase( inf, t );
    }

    @Override
    public File archivo( File carpeta, String base, Formato f ) {
      return InformeServicio.archivo( carpeta, base, f );
    }

    @Override
    public File vistaPrevia( Informe inf, Tema t, boolean prerender, Progreso pr ) throws Exception {
      return InformeServicio.vistaPrevia( inf, t, prerender, pr );
    }

    @Override
    public Resultado exportar( Informe inf, Tema t, Formato f, File destino, Progreso pr ) throws Exception {
      return InformeServicio.exportar( inf, t, f, destino, pr );
    }

    @Override
    public boolean hayNavegador() {
      try {
        return Chromium.ubicar() != null;
      } catch ( Throwable t ) {
        return false;
      }
    }

    /** Con la ruta del navegador del tema primero: la misma busqueda que el PDF y el pre-render. */
    @Override
    public boolean hayNavegador( Tema t ) {
      return InformeServicio.hayNavegador( t );
    }

    /** Steps (o entradas de job) que escriben fuera del documento: los muestra la confirmacion de Medir. */
    @Override
    public List<String> pasosQueEscriben( Captura c ) {
      return c == null ? new ArrayList<String>() : CorridaMedida.escritores( c.copia );
    }

    /** Pregunta antes de perfilar un step con escritores aguas arriba (diseno §2.4.3), o null. */
    @Override
    public String confirmacionPerfil( Captura c, String step, String idioma ) {
      if ( c == null || !( c.copia instanceof TransMeta ) || step == null ) {
        return null;
      }
      return CorridaMedida.textoConfirmacionPerfil( (TransMeta) c.copia, step, idioma );
    }

    /** Motivo por el que no se puede exportar PowerPoint en este Spoon (XmlBeans), o null. */
    @Override
    public String problemaPptx( String idioma ) {
      try {
        return RenderPptx.problema( idioma );
      } catch ( Throwable t ) {
        return String.valueOf( t );
      }
    }

    @Override
    public Tema temaGuardado() {
      try {
        Tema t = Ajustes.cargar();
        return t == null ? new Tema() : t;
      } catch ( Throwable e ) {
        error( "Pentaho Live: could not read the report settings; using the defaults", e );
        return new Tema();
      }
    }

    @Override
    public Tema temaGuardado( String idioma, List<String> avisos ) {
      try {
        Tema t = Ajustes.cargar( idioma, avisos );
        return t == null ? new Tema() : t;
      } catch ( Throwable e ) {
        error( "Pentaho Live: could not read the report settings; using the defaults", e );
        return new Tema();
      }
    }

    @Override
    public void guardarTema( Tema t ) throws Exception {
      Ajustes.guardar( t );
    }

    @Override
    public void log( String texto, Throwable t ) {
      try {
        if ( t == null ) {
          ReportsPerspective.log().logBasic( texto );
        } else {
          ReportsPerspective.log().logError( texto, t );
        }
      } catch ( Throwable ignorada ) {
        // sin log
      }
    }
  }
}
