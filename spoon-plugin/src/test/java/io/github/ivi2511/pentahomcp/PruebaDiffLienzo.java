package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.core.extension.ExtensionPoint;
import org.pentaho.di.core.gui.GCInterface;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.diff.Diff;
import io.github.ivi2511.pentahomcp.diff.ResultadoDiff;
import io.github.ivi2511.pentahomcp.diff.Salidas;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * El modo visual del diff en Spoon (diseño E1): las marcas de cada lado ({@link DiffLienzo#marcas}), el dibujo de las
 * insignias con un GC de mentira que anota las llamadas, las extensiones del pintor, el diálogo «Comparar…» y la lista
 * de cambios (fuera de la pantalla, sin activarse; capturas en {@code <prueba>\salida\diff\}).
 */
public class PruebaDiffLienzo {

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell host;
  private File capturas;

  private String x;
  private String y;
  private ResultadoDiff res;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    capturas = EntornoKettle.salida( "diff" );
    // antes: el ejemplo 02; después: «Elegir columnas» renombrado, «Descartados» quitado, un Sort nuevo, filtro estricto
    x = PruebaDiff.ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    String z = PruebaDiff.cambiar( x, "Elegir columnas", "Columnas finales" );
    z = PruebaDiff.cambiar( z, "<function>&gt;=</function>", "<function>&gt;</function>" );
    z = PruebaDiff.cambiar( z, "<name>Descartados</name>\n    <type>Dummy</type>",
      "<name>Ordenar por saldo</name>\n    <type>SortRows</type>" );
    z = PruebaDiff.cambiar( z, "<send_false_to>Descartados</send_false_to>", "<send_false_to>Ordenar por saldo"
      + "</send_false_to>" );
    z = PruebaDiff.cambiar( z, "<to>Descartados</to>", "<to>Ordenar por saldo</to>" );
    y = z;
    res = Diff.comparar( Diff.Fuente.texto( x, "git HEAD: 02_filtrar_clientes.ktr" ), Diff.Fuente.texto( y,
      "abierto en Spoon" ), true );

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
    }, "ui-prueba-diff" );
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

  /** Las marcas de cada lado: después (añadido, renombrado, cambiado y el quitado como fantasma) y antes. */
  @SuppressWarnings( "unchecked" )
  public void pruebaA_Marcas() {
    Map<String, Object> d = DiffLienzo.marcas( res, false, true );
    Map<String, String> nodos = (Map<String, String>) d.get( DiffLienzo.NODOS );
    igual( "+", nodos.get( "Ordenar por saldo" ), "añadido: " + nodos );
    igual( "»", nodos.get( "Columnas finales" ), "renombrado" );
    igual( "~", nodos.get( "Activos con saldo" ), "cambiado" );
    falso( nodos.containsKey( "Descartados" ), "el quitado no está en el lienzo de después" );
    List<Object[]> fantasmas = (List<Object[]>) d.get( DiffLienzo.FANTASMAS );
    igual( 1, fantasmas.size(), "un fantasma" );
    igual( "Descartados", fantasmas.get( 0 )[ 0 ], "fantasma" );
    igual( 256, fantasmas.get( 0 )[ 1 ], "x de antes" );
    igual( 224, fantasmas.get( 0 )[ 2 ], "y de antes" );
    Map<String, String> saltos = (Map<String, String>) d.get( DiffLienzo.SALTOS );
    igual( "+", saltos.get( "Activos con saldo\u0001Ordenar por saldo" ), "salto nuevo: " + saltos );
    List<String[]> sf = (List<String[]>) d.get( DiffLienzo.SALTOS_FANTASMA );
    igual( 1, sf.size(), "salto quitado" );
    igual( Arrays.asList( "Activos con saldo", "Descartados" ), Arrays.asList( sf.get( 0 ) ), "extremos" );
    Map<String, Object> a = DiffLienzo.marcas( res, true, false );
    Map<String, String> na = (Map<String, String>) a.get( DiffLienzo.NODOS );
    igual( "\u2212", na.get( "Descartados" ), "quitado en el de antes" );
    igual( "»", na.get( "Elegir columnas" ), "renombrado con su nombre de antes" );
    falso( na.containsKey( "Ordenar por saldo" ), "el añadido no está en el de antes" );
    igual( "\u2212", ( (Map<String, String>) a.get( DiffLienzo.SALTOS ) ).get( "Activos con saldo\u0001Descartados" ),
      "salto quitado en el de antes" );
    cierto( ( (List<?>) a.get( DiffLienzo.FANTASMAS ) ).isEmpty(), "sin fantasmas en el de antes" );
  }

  /** El dibujo: insignias de color sobre cada step, el fantasma rayado y el salto quitado, con el offset del lienzo. */
  public void pruebaB_Dibujo() throws Exception {
    TransMeta despues = new TransMeta( new ByteArrayInputStream( y.getBytes( StandardCharsets.UTF_8 ) ), null, true,
      null, null );
    final List<String> llamadas = new ArrayList<String>();
    GCInterface gc = (GCInterface) Proxy.newProxyInstance( GCInterface.class.getClassLoader(),
      new Class<?>[] { GCInterface.class }, (InvocationHandler) ( p, m, args ) -> {
        StringBuilder sb = new StringBuilder( m.getName() );
        if ( args != null ) {
          sb.append( Arrays.deepToString( args ) );
        }
        llamadas.add( sb.toString() );
        if ( m.getName().equals( "textExtent" ) ) {
          return new Point( 8 * String.valueOf( args[ 0 ] ).length(), 12 );
        }
        return null;
      } );
    DiffLienzo.dibujar( gc, despues, DiffLienzo.marcas( res, false, true ), new Point( 10, 20 ), 32, 1 );
    String t = llamadas.toString();
    // «Ordenar por saldo» (añadido) está en 256,224: insignia verde centrada en (256+10+32, 224+20)
    contiene( t, "setBackground[34, 139, 58], fillRoundRectangle[289, 235, 18, 18, 18, 18]", "insignia verde: " + t );
    // «Activos con saldo» (cambiado) en 256,112: borde ámbar
    contiene( t, "setForeground[214, 132, 0], drawRoundRectangle[263, 129, 38, 38, 8, 8]", "borde ámbar" );
    // el fantasma «Descartados» (quitado) en 256,224 con su nombre
    contiene( t, "drawText[Descartados, ", "nombre del fantasma" );
    contiene( t, "setLineStyle[DASH]", "rayado" );
    // el salto quitado, de Activos con saldo (256,112) a Descartados (256,224): de borde a borde, como los hops de
    // Spoon (sale 18 px debajo del centro del icono real y llega 20 px antes del centro del fantasma), con su flecha
    contiene( t, "drawLine[282, 166, 282, 240]", "salto fantasma recortado: " + t );
    noContiene( t, "drawLine[282, 148, 282, 260]", "nunca de centro a centro por encima del icono" );
    contiene( t, "drawLine[282, 240, ", "la punta de la flecha" );
    // deja el GC como estaba
    String fin = llamadas.subList( llamadas.size() - 5, llamadas.size() ).toString();
    contiene( fin, "setLineStyle[SOLID]", "estilo restituido" );
    contiene( fin, "setFont[GRAPH]", "fuente restituida" );
    // sin marcas no dibuja nada
    llamadas.clear();
    DiffLienzo.pintar( new Object() );
    cierto( llamadas.isEmpty(), "pintar con otra cosa no hace nada" );
  }

  /** Las extensiones del pintor: ids y puntos de extensión de Kettle. */
  public void pruebaC_Extensiones() {
    ExtensionPoint t = DiffPintorTrans.class.getAnnotation( ExtensionPoint.class );
    igual( "TransPainterEnd", t.extensionPointId(), "transformaciones" );
    ExtensionPoint j = DiffPintorJob.class.getAnnotation( ExtensionPoint.class );
    igual( "JobPainterEnd", j.extensionPointId(), "jobs" );
    new DiffPintorTrans().callExtensionPoint( null, "no es un pintor" ); // nunca lanza
    new DiffPintorJob().callExtensionPoint( null, null );
  }

  /** El diálogo «Comparar…» y la lista de cambios, fuera de la pantalla; registro de marcas en el Display. */
  public void pruebaD_DialogoYLista() throws Exception {
    final File archivo = new File( capturas, "02_filtrar_clientes.ktr" );
    EntornoKettle.escribir( archivo, y );
    final TransMeta despues = new TransMeta( archivo.getAbsolutePath() );
    despues.clearChanged(); // como recién abierto en Spoon
    final Rotulos r = Salidas.rotulos( "es" );
    final IdcComparar d = ui( () -> {
      IdcComparar c = new IdcComparar( host, despues, r );
      c.shell.pack();
      mostrar( c.shell );
      return c;
    } );
    try {
      ui( () -> {
        igual( "Comparar · 02_filtrar_clientes", d.shell.getText(), "título" );
        cierto( d.guardado.getEnabled(), "con archivo: el guardado se puede elegir" );
        cierto( d.git.getSelection(), "sin cambios sin guardar: git por defecto" );
        igual( "git:HEAD", d.referencia(), "referencia por defecto" );
        cierto( d.ignorar.getSelection(), "ignorar posiciones marcado" );
        cierto( d.enLienzo.getSelection(), "insignias en este lienzo por defecto" );
        d.otro.setSelection( true );
        d.git.setSelection( false );
        igual( null, d.referencia(), "otro archivo sin elegir" );
        d.archivo.setText( "C:\\x\\antes.ktr" );
        igual( "C:\\x\\antes.ktr", d.referencia(), "otro archivo" );
        return null;
      } );
      pantalla( d.shell, "dialogo-comparar.png" );
    } finally {
      cerrarShell( d.shell );
    }

    final IdcComparar.Lista l = ui( () -> {
      DiffLienzo.poner( display, despues, DiffLienzo.marcas( res, false, true ) );
      IdcComparar.Lista x2 = new IdcComparar.Lista( host, despues, null, res, r );
      IdcDetalle.guardarPref( IdcComparar.Lista.PREF + ".size", "" );
      IdcDetalle.guardarPref( IdcComparar.Lista.PREF + ".cols", "" );
      x2.dimensionar( new Rectangle( 0, 0, 1920, 1040 ) );
      mostrar( x2.shell );
      return x2;
    } );
    try {
      ui( () -> {
        cierto( DiffLienzo.tiene( display, despues ), "marcas puestas" );
        igual( res.cambios.size(), l.tabla.getItemCount(), "una fila por cambio" );
        StringBuilder todo = new StringBuilder();
        for ( int i = 0; i < l.tabla.getItemCount(); i++ ) {
          todo.append( l.tabla.getItem( i ).getText( 0 ) ).append( " | " ).append( l.tabla.getItem( i ).getText( 1 ) )
            .append( " | " ).append( l.tabla.getItem( i ).getText( 2 ) ).append( '\n' );
        }
        String t = todo.toString();
        contiene( t, "+  añadido | Steps · Ordenar por saldo (SortRows) | ", "fila añadido: " + t );
        contiene( t, "Steps · Columnas finales (SelectValues), renombrado, antes «Elegir columnas»", "renombrado" );
        contiene( t, "condición: saldo >= 1000.0 AND activo = 'S' → saldo > 1000.0 AND activo = 'S'", "detalle" );
        noContiene( t, "\u0000", "sin secretos" );
        // a la medida del contenido: la columna de detalle muestra su texto entero (hasta 900 px) y la ventana, las
        // tres columnas (0.7.0 la abría de 702 px y cortaba el detalle)
        org.eclipse.swt.graphics.GC g = new org.eclipse.swt.graphics.GC( l.tabla );
        int largo = 0;
        try {
          g.setFont( l.tabla.getFont() );
          for ( int i = 0; i < l.tabla.getItemCount(); i++ ) {
            largo = Math.max( largo, g.textExtent( l.tabla.getItem( i ).getText( 2 ) ).x );
          }
        } finally {
          g.dispose();
        }
        int ancho = l.tabla.getColumn( 2 ).getWidth();
        cierto( ancho >= Math.min( 900, largo ), "el detalle entra: " + ancho + " >= " + largo );
        int suma = 0;
        for ( org.eclipse.swt.widgets.TableColumn c : l.tabla.getColumns() ) {
          suma += c.getWidth();
        }
        cierto( l.shell.getSize().x >= suma, "la ventana muestra las tres columnas: " + l.shell.getSize().x + " >= "
          + suma );
        cierto( l.shell.getSize().x <= 1920 * 92 / 100, "sin pasar del ancho de la pantalla" );
        contiene( String.valueOf( l.tabla.getItem( 0 ).getData( "tip2" ) ), "", "tooltip con el detalle entero" );
        l.seleccionar( 0 ); // sin Spoon: no lanza
        return null;
      } );
      pantalla( l.shell, "lista-cambios.png" );
    } finally {
      cerrarShell( l.shell );
    }
    ui( () -> {
      falso( DiffLienzo.tiene( display, despues ), "al cerrar la lista se van las marcas" );
      // el tamaño que dejó queda recordado y la próxima lista abre así
      String rec = IdcDetalle.pref( IdcComparar.Lista.PREF + ".size" );
      cierto( rec != null && rec.matches( "\\d+,\\d+" ), "al cerrarla se recuerda su tamaño: " + rec );
      IdcDetalle.guardarPref( IdcComparar.Lista.PREF + ".size", "1000,520" );
      IdcComparar.Lista otra = new IdcComparar.Lista( host, despues, null, res, r );
      try {
        otra.dimensionar( new Rectangle( 0, 0, 1920, 1040 ) );
        igual( "1000,520", otra.shell.getSize().x + "," + otra.shell.getSize().y, "abre con el tamaño recordado" );
      } finally {
        otra.shell.dispose();
      }
      return null;
    } );
  }

  // ================================================================ util

  private static void mostrar( Shell s ) {
    s.setLocation( PruebaPanelSwt.X, PruebaPanelSwt.Y );
    s.setVisible( true );
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
    archivo( new File( capturas, nombre ), 3000, "captura " + nombre );
  }
}
