package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.i18n.LanguageChoice;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * La pestana Informes con «Varios documentos» (0.5.1), con el servicio falso de PruebaPanelSwt: exportar
 * cada documento a sus archivos, todo en un archivo con titulo, y «Añadir transformaciones del job».
 * La ventana va fuera de la pantalla y sin activarse (el PC puede estar en uso).
 *
 * @dueño M
 */
public class PruebaPanelVarios {

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell spoonShell;
  private Shell host;
  private Composite tarjeta;
  private ReportsPerspective perspectiva;
  private PruebaPanelSwt.ServicioFalso servicio;
  private File dir;
  private Rotulos r;
  private Locale localeAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    dir = new File( EntornoKettle.carpeta(), "panel_varios" );
    EntornoKettle.borrar( dir.toPath() );
    dir.mkdirs();
    r = Rotulos.de( PanelInformes.AREA, "es" );
    localeAntes = LanguageChoice.getInstance().getDefaultLocale();
    LanguageChoice.getInstance().setDefaultLocale( new Locale( "es", "ES" ) );

    servicio = new PruebaPanelSwt.ServicioFalso( dir );
    Captura t = EntornoKettle.captura( "03" );
    t.docId = "T1";
    Captura j = EntornoKettle.captura( "10" );
    j.docId = "J1";
    servicio.capturas.put( "T1", t );
    servicio.capturas.put( "J1", j );
    servicio.docs.add( new PanelInformes.Documento( "T1", t.nombre, Captura.TRANSFORMACION, t.archivo, true,
      false ) );
    servicio.docs.add( new PanelInformes.Documento( "J1", j.nombre, Captura.JOB, j.archivo, false, false ) );

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
    }, "ui-prueba-varios" );
    hiloUi.setDaemon( true );
    hiloUi.start();
    cierto( listo.await( 30, TimeUnit.SECONDS ), "el hilo de UI arranco" );
    ui( () -> {
      spoonShell = new Shell( display );
      ReportsPerspective.padreSinSpoon = spoonShell;
      ReportsPerspective.servicioPrueba = servicio;
      host = new Shell( display, SWT.NO_TRIM | ( PruebaPanelSwt.VISIBLES ? SWT.ON_TOP : SWT.TOOL ) );
      host.setLayout( new FillLayout() );
      Composite deck = new Composite( host, SWT.NONE );
      StackLayout pila = new StackLayout();
      deck.setLayout( pila );
      tarjeta = new Composite( deck, SWT.NONE );
      tarjeta.setLayout( new GridLayout() );
      pila.topControl = tarjeta;
      host.setBounds( PruebaPanelSwt.X, PruebaPanelSwt.Y, 1200, 760 );
      return null;
    } );
    perspectiva = new ReportsPerspective();
    ui( () -> {
      Composite raiz = perspectiva.getUI();
      raiz.setParent( tarjeta );
      tarjeta.layout( true, true );
      PruebaPanelSwt.mostrar( host );
      host.layout( true, true );
      perspectiva.setActive( true );
      return null;
    } );
    esperar( "vista liviana", () -> !perspectiva.panel().ocupado() && servicio.vistas.size() >= 2, 30000 );
  }

  public void cerrar() {
    try {
      ui( () -> {
        if ( host != null && !host.isDisposed() ) {
          host.dispose();
        }
        if ( spoonShell != null && !spoonShell.isDisposed() ) {
          spoonShell.dispose();
        }
        return null;
      } );
    } catch ( Throwable ignorada ) {
      // seguir cerrando
    }
    ReportsPerspective.padreSinSpoon = null;
    ReportsPerspective.servicioPrueba = null;
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

  /** «Varios documentos» + «Un archivo por documento»: un .md por documento, con su nombre. */
  public void pruebaA_CadaUno() throws Exception {
    final File destino = new File( dir, "cada" );
    soloMd( destino );
    ui( () -> {
      cierto( !buscar( "docs_lista" ).isVisible(), "con «Este documento» la lista no se ve" );
      radio( "docs_varios", "docs_uno" );
      cierto( buscar( "docs_lista" ).isVisible(), "con «Varios» la lista se ve" );
      Table t = (Table) buscar( "docs_lista" );
      igual( 2, t.getItemCount(), "las dos pestanas" );
      for ( TableItem it : t.getItems() ) {
        marcar( t, it, true );
      }
      contiene( ( (org.eclipse.swt.widgets.Label) buscar( "docs_resumen" ) ).getText(), "2", "resumen con 2" );
      return null;
    } );
    int antes = servicio.exportadosCopia().size();
    clic( "exportar" );
    esperar( "exporta los dos", () -> !perspectiva.panel().ocupado() && servicio.exportadosCopia().size() == antes
      + 2, 30000 );
    List<String> exp = servicio.exportadosCopia().subList( antes, antes + 2 );
    igual( "MARKDOWN|" + servicio.capturas.get( "T1" ).nombre + ".md", exp.get( 0 ), "el de la transformacion" );
    igual( "MARKDOWN|" + servicio.capturas.get( "J1" ).nombre + ".md", exp.get( 1 ), "el del job" );
    String estado = ui( () -> perspectiva.panel().estado() );
    contiene( estado, r.f( "status_exported_n", 2, 2, destino.getPath() ), "estado de varios" );
  }

  /** «Todo en un archivo» con titulo: un solo .md con ese nombre. */
  public void pruebaB_Combinado() throws Exception {
    final File destino = new File( dir, "combinado" );
    soloMd( destino );
    ui( () -> {
      radio( "docs_combinar", "docs_cada" );
      cierto( buscar( "docs_titulo" ).isEnabled(), "titulo habilitado al combinar" );
      ( (Text) buscar( "docs_titulo" ) ).setText( "Entrega final" );
      return null;
    } );
    int antes = servicio.exportadosCopia().size();
    clic( "exportar" );
    esperar( "exporta uno", () -> !perspectiva.panel().ocupado() && servicio.exportadosCopia().size() == antes + 1,
      30000 );
    igual( "MARKDOWN|Entrega final.md", servicio.exportadosCopia().get( antes ), "un archivo con el titulo" );
    contiene( EntornoKettle.leer( new File( destino, "Entrega final.md" ) ), "de Entrega final",
      "el informe exportado es el combinado" );
  }

  /** «Añadir transformaciones del job» con el job elegido: suma 01 (cargado) y marca 03 (ya abierto). */
  public void pruebaC_TransformacionesDelJob() throws Exception {
    ui( () -> {
      Combo cb = (Combo) buscar( "documento" );
      cb.select( 1 ); // J1
      cb.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
    esperar( "elige el job", () -> !perspectiva.panel().ocupado(), 30000 );
    clic( "docs_job" );
    esperar( "suma lo del job", () -> !perspectiva.panel().ocupado() && ( (Table) buscar( "docs_lista" ) )
      .getItemCount() == 3, 30000 );
    List<String> marcados = ui( () -> {
      List<String> l = new ArrayList<String>();
      for ( TableItem it : ( (Table) buscar( "docs_lista" ) ).getItems() ) {
        l.add( ( it.getChecked() ? "x " : "  " ) + it.getText().trim() );
      }
      return l;
    } );
    igual( 3, marcados.size(), "03, 10 y 01: " + marcados );
    contiene( marcados.get( 0 ), "x", "el job marcado" );
    contiene( marcados.get( 0 ), servicio.capturas.get( "J1" ).nombre, "el job primero (eligio el job): "
      + marcados );
    for ( String m : marcados ) {
      cierto( m.startsWith( "x" ), "todo marcado: " + marcados );
    }
    cierto( servicio.archivosPedidos.size() == 1 && servicio.archivosPedidos.get( 0 ).endsWith(
      "01_generar_constantes.ktr" ), "solo carga 01 (03 ya estaba abierto): " + servicio.archivosPedidos );
    contiene( ui( () -> perspectiva.panel().estado() ), r.f( "docs_job_added", 2 ), "estado del boton" );
  }

  // ---------------------------------------------------------------- ayudas

  /** Solo Markdown, en esa carpeta. (desde fuera de la UI) */
  private void soloMd( final File destino ) throws Exception {
    ui( () -> {
      ( (Text) buscar( "carpeta" ) ).setText( destino.getPath() );
      for ( Control c : todos( perspectiva.panel().raiz() ) ) {
        Object k = c.getData( PanelInformes.CLAVE );
        if ( k instanceof String && ( (String) k ).startsWith( "fmt:" ) ) {
          ( (Button) c ).setSelection( "fmt:md".equals( k ) );
        }
      }
      return null;
    } );
  }

  /** Elige un radio y suelta el otro, como un clic. (UI) */
  private void radio( String si, String no ) {
    ( (Button) buscar( no ) ).setSelection( false );
    Button b = (Button) buscar( si );
    b.setSelection( true );
    b.notifyListeners( SWT.Selection, new Event() );
  }

  /** Marca una fila de una tabla con casillas, como un clic en la casilla. (UI) */
  private static void marcar( Table t, TableItem it, boolean si ) {
    it.setChecked( si );
    Event e = new Event();
    e.detail = SWT.CHECK;
    e.item = it;
    t.notifyListeners( SWT.Selection, e );
  }

  @SuppressWarnings( "unchecked" )
  private <T> T ui( final Callable<T> c ) throws Exception {
    final Object[] res = new Object[ 1 ];
    final Throwable[] err = new Throwable[ 1 ];
    display.syncExec( () -> {
      try {
        res[ 0 ] = c.call();
      } catch ( Throwable t ) {
        err[ 0 ] = t;
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
      Thread.sleep( 50 );
    }
    falla( "no paso: " + que + " (estado: " + ui( () -> perspectiva.panel().estado() ) + "; exportados: "
      + servicio.exportadosCopia() + "; log: " + servicio.logs + ")" );
  }

  private void clic( final String id ) throws Exception {
    ui( () -> {
      Control c = buscar( id );
      noNulo( c, id );
      cierto( c.isEnabled(), "'" + id + "' habilitado" );
      c.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  private Control buscar( String id ) {
    for ( Control c : todos( perspectiva.panel().raiz() ) ) {
      if ( id.equals( c.getData( PanelInformes.CLAVE ) ) ) {
        return c;
      }
    }
    throw new AssertionError( "no hay control '" + id + "'" );
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
}
