package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.EngineMetaInterface;
import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPerspectiveListener;
import org.pentaho.di.ui.spoon.SpoonPerspectiveManager;
import org.pentaho.ui.xul.XulOverlay;
import org.pentaho.ui.xul.impl.XulEventHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * La perspectiva Diseño / Design / Design (D1): el plano ETL con sus tres vistas (flujo de datos, actividad de la
 * orquestación, componentes y despliegue), sus tablas y la revisión, dibujado con el motor de los Informes (HTML
 * liviano en un Browser, diagramas propios en SVG sin navegador). Arriba: el plano (abrir, recargar), la
 * transformación del plano y los botones Construir boceto (plan_build mode=sketch en la transformación enlazada, o en
 * una nueva), Comprobar deriva (plan_check) y Exportar (HTML, Markdown, PDF o Word).
 * <p>
 * Igual que {@link ReportsPerspective}: getActiveMeta() = null (Ctrl+S deshabilitado), getUI() perezoso, nada lanza
 * hacia Spoon. {@link #mostrar} la trae al frente con un plano (plan_get show=true y el menú IDC «Diseño…»).
 */
public class DisenoPerspective implements SpoonPerspective {

  public static final String ID = "pentaho-live-design";
  static final String CANAL = "PentahoLive-Diseno";
  /** SWT.EDGE en SWT 3.116+ (en el 3.115 de PDI no existe: queda IE11). */
  static final int ESTILO_EDGE = 1 << 18;

  /** Solo pruebas: padre de la UI cuando no hay Spoon. */
  static volatile Shell padreSinSpoon;

  private final List<SpoonPerspectiveListener> listeners = new CopyOnWriteArrayList<SpoonPerspectiveListener>();
  private Composite ui;
  private Panel panel;
  /** El plano a mostrar en cuanto haya UI. */
  private File pendiente;

  public DisenoPerspective() {
    // trivial: corre dentro de SpoonPluginManager.<clinit>
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getDisplayName( Locale locale ) {
    try {
      String s = Rotulos.de( PlanoInforme.AREA, locale == null ? null : locale.toString() ).s( "ui.perspectiva" );
      return s == null || s.isEmpty() || "ui.perspectiva".equals( s ) ? "Design" : s;
    } catch ( Throwable t ) {
      return "Design";
    }
  }

  @Override
  public InputStream getPerspectiveIcon() {
    return null;
  }

  @Override
  public Composite getUI() {
    try {
      if ( ui == null || ui.isDisposed() ) {
        panel = null;
        ui = new Composite( padre(), SWT.NONE );
        ui.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
        ui.setLayout( new FillLayout() );
        try {
          panel = new Panel( ui, ReportsPerspective.idiomaUi() );
          if ( pendiente != null ) {
            panel.cargar( pendiente );
            pendiente = null;
          }
        } catch ( Throwable t ) {
          panel = null;
          error( "Pentaho Live: the Design panel could not be built", t );
          for ( Control c : ui.getChildren() ) {
            c.dispose();
          }
          Label l = new Label( ui, SWT.WRAP );
          l.setText( String.valueOf( t ) );
        }
      }
    } catch ( Throwable t ) {
      error( "Pentaho Live: the Design perspective could not create its UI", t );
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
          l.onDeactication();
        }
      } catch ( Throwable t ) {
        error( "Pentaho Live: a perspective listener failed", t );
      }
    }
    try {
      if ( active && panel != null ) {
        panel.alActivar();
      }
    } catch ( Throwable t ) {
      error( "Pentaho Live: the Design panel failed when switching perspective", t );
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

  public boolean onFileClose() {
    return false;
  }

  Panel panel() {
    return panel;
  }

  /** Muestra el plano (o lo deja para cuando se cree la UI). (UI) */
  void abrirPlano( File plano ) {
    if ( panel != null ) {
      panel.cargar( plano );
    } else {
      pendiente = plano;
    }
  }

  /**
   * Trae la perspectiva Diseño al frente con ese plano (null = el que ya tenía, o el de la transformación activa).
   * (UI)
   */
  static void mostrar( Spoon sp, File plano ) throws Exception {
    DisenoPerspective d = instancia();
    if ( d == null ) {
      throw new McpHttpServer.ToolError( "The Design perspective is not available in this Spoon (PENTAHO_LIVE_DESIGN="
        + "off, or the plugin is not in data-integration\\plugins\\pentaho-mcp)" );
    }
    File f = plano;
    if ( f == null ) {
      AbstractMeta m = IdcMenu.activo();
      String ruta = m instanceof TransMeta ? PlanoConstruir.planoDe( (TransMeta) m ) : null;
      f = ruta == null ? null : new File( ruta );
    }
    if ( f != null ) {
      d.abrirPlano( f );
    }
    sp.loadPerspective( ID );
  }

  /** La instancia registrada en Spoon, o null. */
  static DisenoPerspective instancia() {
    try {
      for ( SpoonPerspective p : SpoonPerspectiveManager.getInstance().getPerspectives() ) {
        if ( p instanceof DisenoPerspective ) {
          return (DisenoPerspective) p;
        }
      }
    } catch ( Throwable t ) {
      // sin Spoon
    }
    return null;
  }

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
    if ( d != null && d.getShells().length > 0 ) {
      return d.getShells()[ 0 ];
    }
    throw new IllegalStateException( "No Spoon shell to create the Design panel on" );
  }

  static void error( String texto, Throwable t ) {
    try {
      new LogChannel( CANAL ).logError( texto, t );
    } catch ( Throwable ignorada ) {
      // sin log
    }
  }

  // ================================================================ el panel

  /** La UI: barra con el plano y las acciones, una línea de estado y el documento del plano. (UI) */
  static final class Panel {
    final Rotulos r;
    final Composite raiz;
    final Combo plano;
    final Combo transformacion;
    final Button abrir;
    final Button recargar;
    final Button construir;
    final Button deriva;
    final Button exportar;
    final Button navegador;
    final Label estado;
    final Browser browser;
    final Label sinBrowser;
    /** El plano cargado y su vista HTML. */
    Plano actual;
    File html;
    /** Último texto de estado (pruebas). */
    String ultimoEstado = "";

    Panel( Composite padre, String idioma ) {
      r = Rotulos.de( PlanoInforme.AREA, idioma );
      raiz = new Composite( padre, SWT.NONE );
      GridLayout gl = new GridLayout( 1, false );
      gl.marginWidth = 8;
      gl.marginHeight = 6;
      raiz.setLayout( gl );

      Composite fila1 = new Composite( raiz, SWT.NONE );
      fila1.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
      GridLayout g1 = new GridLayout( 4, false );
      g1.marginWidth = 0;
      g1.marginHeight = 0;
      fila1.setLayout( g1 );
      etiqueta( fila1, r.s( "ui.plano" ) );
      plano = new Combo( fila1, SWT.DROP_DOWN );
      GridData gp = new GridData( SWT.FILL, SWT.CENTER, true, false );
      gp.widthHint = 420;
      plano.setLayoutData( gp );
      plano.setToolTipText( r.s( "ui.plano.tip" ) );
      abrir = boton( fila1, r.s( "ui.abrir" ), r.s( "ui.abrir.tip" ) );
      recargar = boton( fila1, r.s( "ui.recargar" ), r.s( "ui.recargar.tip" ) );

      Composite fila2 = new Composite( raiz, SWT.NONE );
      fila2.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
      GridLayout g2 = new GridLayout( 6, false );
      g2.marginWidth = 0;
      g2.marginHeight = 0;
      fila2.setLayout( g2 );
      etiqueta( fila2, r.s( "ui.transformacion" ) );
      transformacion = new Combo( fila2, SWT.READ_ONLY );
      GridData gt = new GridData( SWT.LEFT, SWT.CENTER, false, false );
      gt.widthHint = 200;
      transformacion.setLayoutData( gt );
      construir = boton( fila2, r.s( "ui.construir" ), r.s( "ui.construir.tip" ) );
      deriva = boton( fila2, r.s( "ui.deriva" ), r.s( "ui.deriva.tip" ) );
      exportar = boton( fila2, r.s( "ui.exportar" ), r.s( "ui.exportar.tip" ) );
      navegador = boton( fila2, r.s( "ui.navegador" ), r.s( "ui.navegador.tip" ) );

      estado = new Label( raiz, SWT.WRAP );
      estado.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

      Browser b = null;
      Label sin = null;
      try {
        b = new Browser( raiz, ESTILO_EDGE );
        b.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
      } catch ( Throwable t ) {
        if ( b != null && !b.isDisposed() ) {
          b.dispose();
        }
        b = null;
        sin = new Label( raiz, SWT.WRAP );
        sin.setText( r.f( "ui.sin_navegador", String.valueOf( t.getMessage() ) ) );
        sin.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
      }
      browser = b;
      sinBrowser = sin;

      abrir.addListener( SWT.Selection, e -> elegir() );
      recargar.addListener( SWT.Selection, e -> recargar() );
      plano.addListener( SWT.DefaultSelection, e -> recargar() );
      plano.addListener( SWT.Selection, e -> recargar() );
      construir.addListener( SWT.Selection, e -> construir() );
      deriva.addListener( SWT.Selection, e -> deriva() );
      exportar.addListener( SWT.Selection, e -> exportar() );
      navegador.addListener( SWT.Selection, e -> {
        if ( html != null ) {
          Program.launch( html.getAbsolutePath() );
        }
      } );
      habilitar();
      ponerEstado( r.s( "ui.vacio" ) );
      vacio();
    }

    private static Label etiqueta( Composite c, String t ) {
      Label l = new Label( c, SWT.NONE );
      l.setText( t );
      return l;
    }

    private static Button boton( Composite c, String t, String tip ) {
      Button b = new Button( c, SWT.PUSH );
      b.setText( t );
      b.setToolTipText( tip );
      return b;
    }

    void habilitar() {
      boolean hay = actual != null;
      construir.setEnabled( hay && transformacion.getItemCount() > 0 );
      deriva.setEnabled( hay );
      exportar.setEnabled( hay );
      navegador.setEnabled( hay && html != null );
      recargar.setEnabled( !plano.getText().trim().isEmpty() );
    }

    void ponerEstado( String t ) {
      ultimoEstado = t == null ? "" : t;
      if ( !estado.isDisposed() ) {
        estado.setText( ultimoEstado.replace( "&", "&&" ) );
        raiz.layout( true, true );
      }
    }

    /** Al activar la perspectiva: si no hay plano, el de la transformación activa; si hay, se refresca. */
    void alActivar() {
      if ( actual == null ) {
        AbstractMeta m = IdcMenu.activo();
        String ruta = m instanceof TransMeta ? PlanoConstruir.planoDe( (TransMeta) m ) : null;
        if ( ruta != null && new File( ruta ).isFile() ) {
          cargar( new File( ruta ) );
        }
      } else {
        recargar();
      }
    }

    void elegir() {
      FileDialog fd = new FileDialog( raiz.getShell(), SWT.OPEN );
      fd.setText( r.s( "ui.abrir" ) );
      fd.setFilterExtensions( new String[] { "*.plan.yaml;*.plan.yml;*.plan.json", "*.*" } );
      fd.setFilterNames( new String[] { r.s( "ui.filtro" ), "*.*" } );
      String f = fd.open();
      if ( f != null ) {
        cargar( new File( f ) );
      }
    }

    void recargar() {
      String t = plano.getText().trim();
      if ( !t.isEmpty() ) {
        cargar( new File( t ) );
      }
    }

    /** Lee el plano, arma su documento y lo muestra. Los errores de forma van al estado. (UI) */
    void cargar( File f ) {
      String ruta = f.getAbsolutePath();
      if ( plano.indexOf( ruta ) < 0 ) {
        plano.add( ruta, 0 );
      }
      plano.setText( ruta );
      Plano.Lectura l;
      try {
        l = Plano.leer( f );
      } catch ( Throwable t ) {
        actual = null;
        ponerEstado( r.f( "ui.error", IdcMenu.mensaje( t ) ) );
        vacio();
        habilitar();
        return;
      }
      if ( l.plano == null ) {
        actual = null;
        ponerEstado( r.f( "ui.errores_forma", l.errores.size(), String.join( " · ", l.errores.subList( 0, Math.min( 3,
          l.errores.size() ) ) ) ) );
        vacio();
        habilitar();
        return;
      }
      actual = l.plano;
      String elegida = transformacion.getText();
      transformacion.removeAll();
      for ( String g : actual.transformaciones() ) {
        transformacion.add( PlanoConstruir.nombreGrupo( g ) );
      }
      int i = transformacion.indexOf( elegida );
      transformacion.select( i >= 0 ? i : 0 );
      List<String[]> progreso = progreso( actual );
      List<PlanoRevision.Hallazgo> rev = PlanoRevision.revisar( actual, r.idioma() );
      int err = 0;
      int av = 0;
      for ( PlanoRevision.Hallazgo h : rev ) {
        err += PlanoRevision.ERROR.equals( h.severidad ) ? 1 : 0;
        av += PlanoRevision.AVISO.equals( h.severidad ) ? 1 : 0;
      }
      StringBuilder st = new StringBuilder( r.f( "ui.estado", actual.nombre, actual.etapas.size(),
        actual.transformaciones().size(), err, av ) );
      for ( String[] p : progreso ) {
        st.append( "  ·  " ).append( r.f( "ui.estado.boceto", p[ 0 ], Integer.parseInt( p[ 1 ] ), Integer.parseInt(
          p[ 2 ] ) ) );
      }
      if ( !l.avisos.isEmpty() ) {
        st.append( "  ·  " ).append( r.f( "ui.estado.avisos", l.avisos.size() ) );
      }
      ponerEstado( st.toString() );
      try {
        Informe inf = PlanoInforme.armar( actual, r.idioma(), progreso );
        html = InformeServicio.vistaPrevia( inf, tema(), false, null );
        if ( browser != null && !browser.isDisposed() ) {
          browser.setUrl( html.toURI().toString() );
        }
      } catch ( Throwable t ) {
        html = null;
        ponerEstado( ultimoEstado + "  ·  " + r.f( "ui.error", IdcMenu.mensaje( t ) ) );
      }
      habilitar();
    }

    private void vacio() {
      if ( browser != null && !browser.isDisposed() ) {
        browser.setText( "<html><body style=\"font-family:Segoe UI,Arial;color:#6B7785;padding:24px\">"
          + Html( r.s( "ui.vacio.html" ) ) + "</body></html>" );
      }
    }

    private static String Html( String s ) {
      return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" ).replace( "\n", "<br>" );
    }

    private static Tema tema() {
      try {
        Tema t = Ajustes.cargar();
        return t == null ? new Tema() : t;
      } catch ( Throwable e ) {
        return new Tema();
      }
    }

    /** El grupo elegido en el combo ("" = la transformación sin nombre). */
    String grupoElegido() {
      if ( actual == null ) {
        return null;
      }
      int i = transformacion.getSelectionIndex();
      List<String> ts = actual.transformaciones();
      return i >= 0 && i < ts.size() ? ts.get( i ) : ts.isEmpty() ? null : ts.get( 0 );
    }

    /**
     * Construir boceto: en la transformación abierta enlazada a esa parte del plano, o en una nueva con su nombre; sin
     * animar, un Ctrl+Z. Después vuelve a Data Integration para ver el lienzo. (UI)
     */
    void construir() {
      if ( actual == null ) {
        return;
      }
      String g = grupoElegido();
      try {
        Spoon sp = Docs.spoon();
        TransMeta tm = null;
        for ( AbstractMeta m : Docs.abiertos() ) {
          if ( m instanceof TransMeta && PlanoConstruir.esDelPlano( (TransMeta) m, actual, g ) ) {
            tm = (TransMeta) m;
          }
        }
        if ( tm == null ) {
          Docs.perspectivaPrincipal();
          sp.newTransFile();
          AbstractMeta n = Docs.activo();
          if ( !( n instanceof TransMeta ) ) {
            throw new IllegalStateException( "Spoon did not create the transformation" );
          }
          tm = (TransMeta) n;
          tm.setName( g == null || g.isEmpty() ? actual.nombre : g );
        }
        ObjectNode o = PlanoConstruir.construirAhora( sp, tm, actual, g, PlanoConstruir.SKETCH, r.idioma(), sp
          .getMetaStore() );
        Docs.perspectivaPrincipal();
        Docs.activar( tm );
        Docs.touched( tm );
        ponerEstado( r.f( "ui.construido", o.path( "steps" ).size(), PlanoConstruir.nombreGrupo( g ) ) );
      } catch ( Throwable t ) {
        aviso( r.f( "ui.error", IdcMenu.mensaje( t ) ) );
      }
    }

    /** Comprobar deriva (plan_check) en otro hilo; el resultado en un cuadro. (UI) */
    void deriva() {
      if ( actual == null || actual.archivo == null ) {
        return;
      }
      final String ruta = actual.archivo.getAbsolutePath();
      final String idioma = r.idioma();
      ponerEstado( r.s( "ui.comprobando" ) );
      final Display d = raiz.getDisplay();
      Thread h = new Thread( () -> {
        String texto;
        try {
          ObjectNode a = Tools.obj().put( "plan", ruta ).put( "language", idioma );
          ObjectNode o = PlanoTools.deriva( a );
          StringBuilder sb = new StringBuilder( r.f( "ui.deriva.resumen", o.path( "warnings" ).asInt(), o.path(
            "infos" ).asInt() ) );
          int n = 0;
          for ( JsonNode x : o.path( "findings" ) ) {
            if ( n++ >= 15 ) {
              sb.append( "\n…" );
              break;
            }
            sb.append( "\n• " ).append( x.path( "message" ).asText() );
          }
          texto = sb.toString();
        } catch ( Throwable t ) {
          texto = r.f( "ui.error", IdcMenu.mensaje( t ) );
        }
        final String fin = texto;
        if ( !d.isDisposed() ) {
          d.asyncExec( () -> {
            if ( !raiz.isDisposed() ) {
              ponerEstado( fin.split( "\n", 2 )[ 0 ] );
              aviso( fin );
            }
          } );
        }
      }, "pentaho-live-plan-check" );
      h.setDaemon( true );
      h.start();
    }

    /** Exportar el documento del plano (HTML, Markdown, PDF o Word) junto al plano o donde se elija. (UI) */
    void exportar() {
      if ( actual == null ) {
        return;
      }
      FileDialog fd = new FileDialog( raiz.getShell(), SWT.SAVE );
      fd.setText( r.s( "ui.exportar" ) );
      String base = actual.archivo == null ? Plano.slug( actual.nombre ) : actual.archivo.getName().replaceAll(
        "(?i)\\.plan\\.(yaml|yml|json)$", "" );
      fd.setFileName( base + ".html" );
      if ( actual.archivo != null ) {
        fd.setFilterPath( actual.archivo.getParent() );
      }
      fd.setFilterExtensions( new String[] { "*.html", "*.md", "*.pdf", "*.docx" } );
      fd.setFilterNames( new String[] { "HTML", "Markdown", "PDF", "Word" } );
      fd.setOverwrite( true );
      String f = fd.open();
      if ( f == null ) {
        return;
      }
      String n = f.toLowerCase( Locale.ROOT );
      Formato fmt = n.endsWith( ".md" ) ? Formato.MARKDOWN : n.endsWith( ".pdf" ) ? Formato.PDF : n.endsWith( ".docx" )
        ? Formato.DOCX : Formato.HTML;
      try {
        Informe inf = PlanoInforme.armar( actual, r.idioma(), progreso( actual ) );
        ClassLoader antes = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader( DisenoPerspective.class.getClassLoader() );
        try {
          InformeServicio.exportar( inf, tema(), fmt, new File( f ), null );
        } finally {
          Thread.currentThread().setContextClassLoader( antes );
        }
        ponerEstado( r.f( "ui.exportado", f ) );
      } catch ( Throwable t ) {
        aviso( r.f( "ui.error", IdcMenu.mensaje( t ) ) );
      }
    }

    void aviso( String texto ) {
      MessageBox mb = new MessageBox( raiz.getShell(), SWT.ICON_INFORMATION | SWT.OK );
      mb.setText( r.s( "ui.perspectiva" ) );
      mb.setMessage( texto );
      mb.open();
    }

    /** El progreso de los bocetos abiertos de este plano. (UI) */
    static List<String[]> progreso( Plano p ) {
      List<String[]> l = new ArrayList<String[]>();
      if ( p == null || p.archivo == null || UiThread.spoon() == null ) {
        return l;
      }
      try {
        for ( AbstractMeta m : Docs.abiertos() ) {
          if ( !( m instanceof TransMeta ) ) {
            continue;
          }
          String ruta = PlanoConstruir.planoDe( (TransMeta) m );
          if ( ruta == null || !new File( ruta ).getAbsoluteFile().equals( p.archivo.getAbsoluteFile() ) ) {
            continue;
          }
          PlanoConstruir.Progreso pr = PlanoConstruir.progreso( (TransMeta) m );
          if ( pr != null ) {
            l.add( new String[] { PlanoConstruir.nombreGrupo( pr.transformacion ), Integer.toString( pr.construidas ),
              Integer.toString( pr.total ), String.join( ", ", pr.pendientes ) } );
          }
        }
      } catch ( Throwable t ) {
        // sin documentos
      }
      return l;
    }
  }
}
