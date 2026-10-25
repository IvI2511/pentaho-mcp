package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Los diálogos de 0.7 «equipo» fuera de la pantalla: «Preparar proyecto…» ({@link IdcProyecto}: el ensayo al escribir
 * la carpeta, «Elegir…» con un elector falso, «Crear lo que falta», la segunda vez sin nada que crear) y la línea
 * «Perfil: …» de «Revisar calidad…» ({@link IdcCalidad#textoPerfil}: con perfil y mínimo, sin perfil, perfil roto).
 * Como PruebaIdcDialogos: ventanas en (-20000, -20000), mostradas con setVisible (nunca open(), forceActive ni
 * ON_TOP) y capturas con Control.print(GC) en {@code <prueba>\salida\idc\}.
 */
public class PruebaIdcProyecto {

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell host;
  private File capturas;
  private File base;
  private IdcProyecto.Elector electorAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    capturas = EntornoKettle.salida( "idc" );
    base = EntornoKettle.salida( "idc-proyecto" );
    EntornoKettle.borrar( base.toPath() );
    base.mkdirs();
    electorAntes = IdcProyecto.elector;
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
    }, "ui-prueba-idc-proyecto" );
    hiloUi.setDaemon( true );
    hiloUi.start();
    cierto( listo.await( 30, TimeUnit.SECONDS ), "el hilo de UI arrancó" );
    ui( () -> {
      host = new Shell( display, SWT.NO_TRIM | SWT.TOOL );
      host.setLayout( new FillLayout() );
      host.setBounds( PruebaPanelSwt.X, PruebaPanelSwt.Y, 400, 200 );
      host.setVisible( true );
      return null;
    } );
  }

  public void cerrar() {
    IdcProyecto.elector = electorAntes;
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

  public void pruebaA_PrepararProyecto() throws Exception {
    final Rotulos r = Rotulos.de( IdcMenu.AREA, "es" );
    final File proy = new File( base, "ventas" );
    igual( "", IdcProyecto.carpetaInicial( new File( new File( base, "no-existe" ), "x.ktr" ).getAbsolutePath() ),
      "carpeta que no existe: vacía" );
    new File( base, "etl" ).mkdirs();
    igual( base.getAbsolutePath(), IdcProyecto.carpetaInicial( new File( new File( base, "etl" ), "x.ktr" )
      .getAbsolutePath() ), "etl/ existe: su padre" );
    igual( "", IdcProyecto.carpetaInicial( null ), "sin archivo: vacía" );

    final IdcProyecto d = ui( () -> {
      IdcProyecto x = new IdcProyecto( host, r, "" );
      mostrar( x.shell );
      return x;
    } );
    falso( ui( () -> d.crear.getEnabled() ), "sin carpeta no se puede crear" );
    contiene( ui( () -> d.estado.getText() ), "ruta completa", "pide la carpeta" );
    IdcProyecto.elector = ( padre, titulo, actual ) -> proy.getAbsolutePath();
    clic( ui( () -> d.elegir ) );
    igual( proy.getAbsolutePath(), ui( () -> d.carpeta.getText() ), "Elegir… pone la carpeta" );
    cierto( ui( () -> d.crear.getEnabled() ), "hay cosas que crear" );
    int filas = ui( () -> d.tabla.getItemCount() );
    cierto( filas > 10, "la lista de lo que se crearía: " + filas );
    igual( "se creará", ui( () -> d.tabla.getItem( 0 ).getText( 1 ) ), "estado" );
    falso( proy.exists(), "el ensayo no escribe" );
    ui( () -> {
      d.nombre.setText( "Curso BD" );
      return null;
    } );
    pantalla( d.shell, "proyecto-1-antes.png" );

    clic( ui( () -> d.crear ) );
    cierto( new File( proy, ".pentaho-live/lint.yaml" ).isFile(), "creado el perfil" );
    contiene( EntornoKettle.leer( new File( proy, "README.md" ) ), "# Curso BD", "con el nombre elegido" );
    contiene( ui( () -> d.estado.getText() ), "Hecho:", "dice qué hizo" );
    igual( "creado", ui( () -> d.tabla.getItem( 0 ).getText( 1 ) ), "estado creado" );
    falso( ui( () -> d.crear.getEnabled() ), "después de crear, nada que hacer" );
    pantalla( d.shell, "proyecto-2-creado.png" );

    // otra vez sobre la misma carpeta: todo ya existe
    ui( () -> {
      d.carpeta.setText( proy.getAbsolutePath() + File.separator );
      return null;
    } );
    contiene( ui( () -> d.estado.getText() ), "no falta nada", "nada que crear" );
    igual( "ya existe (no se toca)", ui( () -> d.tabla.getItem( 0 ).getText( 1 ) ), "ya existe" );
    cerrarShell( d.shell );
  }

  public void pruebaB_PerfilEnCalidad() throws Exception {
    final Rotulos r = Rotulos.de( IdcMenu.AREA, "es" );
    ObjectNode pf = Tools.obj().put( "name", "Curso BD" ).put( "path", "C:\\curso\\.pentaho-live\\lint.yaml" ).put(
      "min_score", 75 ).put( "passed", false );
    igual( "Perfil: Curso BD (C:\\curso\\.pentaho-live\\lint.yaml) · mínimo 75: NO lo cumple", IdcCalidad.textoPerfil( r,
      pf ), "con perfil" );
    contiene( IdcCalidad.textoPerfil( r, null ), "Preparar proyecto", "sin perfil: cómo crear uno" );
    contiene( IdcCalidad.textoPerfil( r, Tools.obj().put( "path", "x.yaml" ).put( "error", "min_score must be" ) ),
      "no se pudo leer", "perfil roto" );
    contiene( IdcCalidad.textoPerfil( Rotulos.de( IdcMenu.AREA, "en" ), pf ), "Profile: Curso BD", "en inglés" );
    contiene( IdcCalidad.textoPerfil( Rotulos.de( IdcMenu.AREA, "pt" ), pf.deepCopy().put( "passed", true ) ),
      "mínimo 75: atingido", "en portugués" );

    final ObjectNode res = (ObjectNode) McpHttpServer.JSON.readTree( "{\"score\":72,\"grade\":\"C\",\"counts\":"
      + "{\"error\":0,\"warning\":2,\"info\":3},\"findings\":[{\"key\":\"naming_convention:step:Leer ventas:name\","
      + "\"rule\":\"naming_convention\",\"severity\":\"info\",\"category\":\"style\",\"scope\":\"step\",\"step\":"
      + "\"Leer ventas\",\"message\":\"El step «Leer ventas» (input) no empieza por IN_, como pide el perfil del "
      + "proyecto.\",\"how_to_fix\":\"Renómbralo con el prefijo del perfil.\",\"fix\":{\"kind\":\"rename\","
      + "\"auto\":true,\"confirm\":false,\"needs_value\":false,\"preview\":\"IN_Leer ventas\"}}],"
      + "\"suppressed\":[],\"not_evaluated\":[]}" );
    res.set( "profile", pf );
    final IdcCalidad d = ui( () -> {
      IdcCalidad x = new IdcCalidad( host, new Falso(), r, "T1" );
      mostrar( x.shell );
      x.mostrar( res, false );
      return x;
    } );
    contiene( ui( () -> d.perfil.getText() ), "Perfil: Curso BD", "la línea del perfil" );
    igual( 1, ui( () -> d.tabla.getItemCount() ), "el hallazgo de la convención" );
    pantalla( d.shell, "calidad-perfil.png" );
    cerrarShell( d.shell );
  }

  /** Un servicio que no se llama (la prueba muestra un resultado fijo). */
  static final class Falso implements IdcCalidad.Servicio {
    @Override
    public String nombre() {
      return "03_ventas_por_producto";
    }

    @Override
    public ObjectNode revisar( boolean profunda, boolean robustez, Consumer<Runnable> alCancelar ) {
      return Tools.obj();
    }

    @Override
    public ObjectNode arreglar( Collection<String> keys, Map<String, String> valores, boolean ensayo,
                                boolean continuar ) {
      return Tools.obj();
    }

    @Override
    public ObjectNode recetas( List<String[]> pares, boolean ensayo ) {
      return Tools.obj();
    }

    @Override
    public ObjectNode silenciar( String regla, String step, String entry, String motivo ) {
      return Tools.obj();
    }

    @Override
    public void seleccionar( String step, String entry ) {
      // nada
    }
  }

  // ---------------------------------------------------------------- utilidades (como PruebaIdcDialogos)

  private static void mostrar( Shell s ) {
    s.setLocation( PruebaPanelSwt.X, PruebaPanelSwt.Y );
    s.setVisible( true );
  }

  private void clic( final Button b ) throws Exception {
    ui( () -> {
      cierto( b.getEnabled(), "'" + b.getText() + "' habilitado para el clic" );
      b.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
  }

  private void cerrarShell( final Shell s ) throws Exception {
    ui( () -> {
      if ( !s.isDisposed() ) {
        s.dispose();
      }
      return null;
    } );
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
        for ( Control hijo : s.getChildren() ) {
          Rectangle h = hijo.getBounds();
          if ( h.width <= 0 || h.height <= 0 || !hijo.getVisible() ) {
            continue;
          }
          Image parte = new Image( display, h.width, h.height );
          GC gp = new GC( parte );
          try {
            if ( hijo.print( gp ) ) {
              gc.drawImage( parte, h.x, h.y );
            }
          } finally {
            gp.dispose();
            parte.dispose();
          }
        }
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
  }
}
