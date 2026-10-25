package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * La perspectiva Diseño y el diálogo «Hoja de mapeo…» con SWT, fuera de la pantalla (-20000, -20000), sin activar
 * ninguna ventana ni ponerla arriba de todo; las capturas salen con {@code Control.print(GC)} en
 * {@code <prueba>\salida\diseno\}.
 */
public class PruebaDiseno {

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell host;
  private File capturas;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    capturas = EntornoKettle.salida( "diseno" );
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
    }, "ui-prueba-diseno" );
    hiloUi.setDaemon( true );
    hiloUi.start();
    cierto( listo.await( 30, TimeUnit.SECONDS ), "el hilo de UI arrancó" );
    ui( () -> {
      host = new Shell( display, SWT.NO_TRIM | SWT.TOOL );
      host.setLayout( new FillLayout() );
      host.setBounds( PruebaPanelSwt.X, PruebaPanelSwt.Y, 1200, 820 );
      host.setVisible( true );
      DisenoPerspective.padreSinSpoon = host;
      return null;
    } );
  }

  public void cerrar() {
    DisenoPerspective.padreSinSpoon = null;
    try {
      ui( () -> {
        if ( host != null && !host.isDisposed() ) {
          host.dispose();
        }
        return null;
      } );
    } catch ( Throwable ignorada ) {
      // seguir cerrando
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

  public void pruebaA_NombreYRegistro() {
    DisenoPerspective p = new DisenoPerspective();
    igual( "pentaho-live-design", p.getId(), "id" );
    igual( "Diseño", p.getDisplayName( new Locale( "es", "ES" ) ), "es" );
    igual( "Design", p.getDisplayName( Locale.ENGLISH ), "en" );
    igual( null, p.getActiveMeta(), "sin documento propio (Ctrl+S deshabilitado)" );
    DisenoSpoonPlugin sp = new DisenoSpoonPlugin();
    noNulo( sp.getPerspective(), "la perspectiva" );
    cierto( sp.getPerspective() == sp.getPerspective(), "siempre la misma instancia" );
    System.setProperty( DisenoSpoonPlugin.PROPIEDAD, "off" );
    try {
      igual( null, new DisenoSpoonPlugin().getPerspective(), "PENTAHO_LIVE_DESIGN=off la apaga" );
    } finally {
      System.clearProperty( DisenoSpoonPlugin.PROPIEDAD );
    }
  }

  /** El plano del ejemplo en la perspectiva: estado, transformaciones y el documento con las vistas. */
  public void pruebaB_PerspectivaConElEjemplo() throws Exception {
    final DisenoPerspective p = new DisenoPerspective();
    final Composite ui = ui( () -> {
      Composite c = p.getUI();
      host.layout( true, true );
      return c;
    } );
    noNulo( ui, "UI" );
    final DisenoPerspective.Panel panel = p.panel();
    noNulo( panel, "el panel se armó" );
    falso( ui( () -> panel.construir.getEnabled() ), "sin plano no se construye" );
    ui( () -> {
      panel.cargar( PruebaPlano.ejemplo() );
      return null;
    } );
    contiene( panel.ultimoEstado, "Grano de Oro", "estado con el nombre" );
    contiene( panel.ultimoEstado, "29 etapas en 5 transformaciones", "estado con las cuentas" );
    igual( 5, (int) ui( () -> panel.transformacion.getItemCount() ), "las cinco transformaciones" );
    cierto( ui( () -> panel.construir.getEnabled() && panel.exportar.getEnabled() ), "acciones habilitadas" );
    noNulo( panel.html, "documento del plano" );
    String h = new String( Files.readAllBytes( panel.html.toPath() ), StandardCharsets.UTF_8 );
    contiene( h, "Flujo de datos", "vista 1" );
    contiene( h, "Actividad de la orquestación", "vista 2" );
    contiene( h, "Componentes y despliegue", "vista 3" );
    contiene( h, "<svg", "diagramas en SVG" );
    if ( panel.browser != null ) {
      Thread.sleep( 1500 );
      ui( () -> {
        display.readAndDispatch();
        return null;
      } );
    }
    pantalla( host, "perspectiva-diseno.png" );
    // un plano roto: el estado lo dice y no se puede construir
    File roto = new File( capturas, "roto.plan.yaml" );
    Files.write( roto.toPath(), "name: x\nstages:\n  - {name: A, after: [nada]}\n".getBytes( StandardCharsets.UTF_8 ) );
    ui( () -> {
      panel.cargar( roto );
      return null;
    } );
    contiene( panel.ultimoEstado, "errores", "errores de forma en el estado" );
    falso( ui( () -> panel.construir.getEnabled() ), "con errores no se construye" );
  }

  /** «Hoja de mapeo…»: exportar, comprobar (con propuestas) y aplicar, con un servicio de mentira. */
  public void pruebaC_DialogoMapeo() throws Exception {
    final Rotulos r = Rotulos.de( PlanoInforme.AREA, "es" );
    final int[] aplicado = new int[ 1 ];
    final IdcMapeo.Servicio s = new IdcMapeo.Servicio() {
      @Override
      public String nombre() {
        return "04_hechos_ventas";
      }

      @Override
      public ObjectNode exportar( File destino ) {
        ObjectNode o = Tools.obj().put( "row_count", 9 );
        o.putArray( "files" ).addObject().put( "path", destino.getPath() );
        return o;
      }

      @Override
      public ObjectNode importar( File hoja, boolean aplicar ) {
        if ( aplicar ) {
          aplicado[ 0 ]++;
        }
        ObjectNode o = Tools.obj().put( "implemented", 7 ).put( "differs", 1 ).put( "missing", 1 );
        o.putArray( "rows" ).addObject().put( "target", "hechos_ventas.descuento" ).put( "result", "missing" ).put(
          "proposal", "constant" ).put( "detail", "«descuento» = «0» (constante)." );
        return o;
      }
    };
    final IdcMapeo d = ui( () -> {
      IdcMapeo x = new IdcMapeo( host, s, r );
      x.shell.setLocation( PruebaPanelSwt.X, PruebaPanelSwt.Y );
      x.shell.setVisible( true );
      return x;
    } );
    ui( () -> {
      d.correrExportar( new File( capturas, "hoja.xlsx" ) );
      return null;
    } );
    esperar( () -> !d.ocupado );
    contiene( ui( () -> d.resultado.getText() ), "9 filas", "exportado" );
    ui( () -> {
      d.hoja = new File( capturas, "hoja.xlsx" );
      d.correrImportar( false );
      return null;
    } );
    esperar( () -> !d.ocupado );
    contiene( ui( () -> d.resultado.getText() ), "7 implementadas · 1 distintas · 1 sin implementar", "resumen" );
    contiene( ui( () -> d.resultado.getText() ), "Falta · hechos_ventas.descuento", "la fila que falta" );
    cierto( ui( () -> d.aplicar.getEnabled() ), "hay propuestas: se puede aplicar" );
    pantalla( d.shell, "dialogo-mapeo.png" );
    ui( () -> {
      d.correrImportar( true );
      return null;
    } );
    esperar( () -> !d.ocupado );
    igual( 1, aplicado[ 0 ], "aplicó una vez" );
    falso( ui( () -> d.aplicar.getEnabled() ), "después de aplicar, nada pendiente" );
    ui( () -> {
      d.shell.dispose();
      return null;
    } );
    igual( "md", IdcMapeo.formato( "x.MD" ), "formato por extensión" );
    igual( "xlsx", IdcMapeo.formato( "x" ), "xlsx por defecto" );
  }

  // ================================================================ apoyo

  private void esperar( Callable<Boolean> c ) throws Exception {
    long fin = System.currentTimeMillis() + 20000;
    while ( System.currentTimeMillis() < fin ) {
      if ( c.call() ) {
        Thread.sleep( 200 );
        return;
      }
      Thread.sleep( 100 );
    }
    Verificador.falla( "no terminó a tiempo" );
  }

  private <T> T ui( final Callable<T> c ) throws Exception {
    final Object[] r2 = new Object[ 1 ];
    final Throwable[] e = new Throwable[ 1 ];
    display.syncExec( () -> {
      try {
        r2[ 0 ] = c.call();
      } catch ( Throwable t ) {
        e[ 0 ] = t;
      }
    } );
    if ( e[ 0 ] instanceof Exception ) {
      throw (Exception) e[ 0 ];
    }
    if ( e[ 0 ] instanceof Error ) {
      throw (Error) e[ 0 ];
    }
    @SuppressWarnings( "unchecked" )
    T t = (T) r2[ 0 ];
    return t;
  }

  /** Captura fuera de la pantalla: cada hijo de la Shell con print(GC). */
  private void pantalla( final Shell s, final String nombre ) throws Exception {
    Thread.sleep( 300 );
    ui( () -> {
      s.layout( true, true );
      Rectangle b = s.getClientArea();
      Image img = new Image( display, Math.max( 1, b.width ), Math.max( 1, b.height ) );
      GC gc = new GC( img );
      try {
        gc.setBackground( s.getBackground() );
        gc.fillRectangle( 0, 0, b.width, b.height );
        pintar( s, gc, 0, 0 );
      } finally {
        gc.dispose();
      }
      ImageLoader il = new ImageLoader();
      il.data = new ImageData[] { img.getImageData() };
      File f = new File( capturas, nombre );
      il.save( f.getPath(), SWT.IMAGE_PNG );
      img.dispose();
      System.out.println( "  captura: " + f );
      return null;
    } );
    archivo( new File( capturas, nombre ), 3000, "captura " + nombre );
  }

  /** Imprime cada hijo (y los hijos de los Composite que no se imprimen solos). */
  private void pintar( Composite c, GC gc, int dx, int dy ) {
    for ( Control hijo : c.getChildren() ) {
      Rectangle h = hijo.getBounds();
      if ( h.width <= 0 || h.height <= 0 || !hijo.getVisible() ) {
        continue;
      }
      Image parte = new Image( display, h.width, h.height );
      GC gp = new GC( parte );
      try {
        if ( hijo.print( gp ) ) {
          gc.drawImage( parte, dx + h.x, dy + h.y );
        } else if ( hijo instanceof Composite ) {
          pintar( (Composite) hijo, gc, dx + h.x, dy + h.y );
        }
      } finally {
        gp.dispose();
        parte.dispose();
      }
    }
  }
}
