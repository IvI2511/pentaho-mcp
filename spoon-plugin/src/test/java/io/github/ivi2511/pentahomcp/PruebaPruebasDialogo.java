package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * La ventana «Pruebas…» del menu IDC (0.7.0) con un servicio falso (sin JVM ni Spoon): la lista en verde y rojo,
 * ejecutar todas en segundo plano, el clic en una que falla con su diferencia fila a fila y su salida marcada en el
 * lienzo, cancelar, «Crear prueba desde la vista previa…» con los steps elegidos marcados, «Ejecutar al guardar» y el
 * indicador del boton IDC.
 * <p>
 * Como PruebaIdcDialogos: las ventanas se abren fuera de la pantalla, sin activarse (constructor + setVisible, nunca
 * open() ni arriba de todo), y las capturas salen con {@code Control.print(GC)} en {@code <prueba>\salida\pruebas\}.
 */
public class PruebaPruebasDialogo {

  private static final long ESPERA_MS = 20000L;

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell host;
  private File capturas;
  private Rotulos r;
  private java.util.function.Consumer<PruebasCrear> abridorAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    capturas = EntornoKettle.salida( "pruebas" );
    r = Rotulos.de( PruebasDialogo.AREA, "es" );
    abridorAntes = PruebasDialogo.abridor;
    PruebasGuardado.activar( false );
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
    }, "ui-prueba-pruebas" );
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
    PruebasDialogo.abridor = abridorAntes;
    PruebasGuardado.activar( false );
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

  // ================================================================ servicio falso

  /** Dos pruebas: «kpi» pasa y «filtro» falla con una fila que cambia, una que falta y una que sobra. */
  static final class Falso implements PruebasDialogo.Servicio {
    final File dir = new File( EntornoKettle.carpeta(), "pruebas-dialogo" );
    final List<File> pruebas = new ArrayList<File>( Arrays.asList( new File( dir, "tests/filtro.test.json" ),
      new File( dir, "tests/kpi.test.json" ) ) );
    volatile CountDownLatch bloquear;
    volatile String seleccionado;
    volatile ObjectNode creada;

    @Override
    public String nombre() {
      return "ventas_por_producto";
    }

    @Override
    public File ktr() {
      return new File( dir, "ventas_por_producto.ktr" );
    }

    @Override
    public List<File> pruebas() {
      return new ArrayList<File>( pruebas );
    }

    @Override
    public ObjectNode describir( File f ) {
      ObjectNode o = Tools.obj().put( "name", PruebasMotor.nombreDeArchivo( f ) ).put( "valid", true );
      com.fasterxml.jackson.databind.node.ArrayNode out = o.putArray( "outputs" );
      out.add( "Totales por producto" );
      if ( f.getName().startsWith( "filtro" ) ) {
        o.put( "description", "Clientes activos con saldo" );
        out.add( "Elegir columnas" );
      }
      return o;
    }

    @Override
    public PruebasProcesos.Corrida correr( final List<File> fs, boolean documento, final PruebasProcesos.Oyente o ) {
      final List<PruebasProcesos.Tarea> tareas = new ArrayList<PruebasProcesos.Tarea>();
      for ( File f : fs ) {
        tareas.add( PruebasProcesos.Tarea.correr( f, null ) );
      }
      final PruebasProcesos.Corrida c = new PruebasProcesos.Corrida( tareas, o );
      Thread h = new Thread( () -> {
        for ( PruebasProcesos.Tarea t : tareas ) {
          o.empezada( t );
        }
        CountDownLatch b = bloquear;
        if ( b != null ) {
          try {
            while ( !c.cancelada && !b.await( 50, TimeUnit.MILLISECONDS ) ) {
              // esperando a que la prueba cancele
            }
          } catch ( InterruptedException e ) {
            return;
          }
        }
        for ( PruebasProcesos.Tarea t : tareas ) {
          ResultadoPrueba res = c.cancelada ? ResultadoPrueba.sin( t.nombre, t.prueba.getPath(),
            ResultadoPrueba.CANCELADA, "Cancelled" ) : resultado( t.prueba );
          t.resultado = res;
          o.terminada( t, res );
        }
        c.fin.countDown();
      } );
      h.setDaemon( true );
      h.start();
      return c;
    }

    @Override
    public PruebasProcesos.Corrida crear( ObjectNode args, final PruebasProcesos.Oyente o ) {
      creada = args;
      final File nueva = new File( dir, "tests/" + args.path( "name" ).asText() + ".test.json" );
      final PruebasProcesos.Tarea t = PruebasProcesos.Tarea.correr( nueva, null );
      final PruebasProcesos.Corrida c = new PruebasProcesos.Corrida( Arrays.asList( t ), o );
      Thread h = new Thread( () -> {
        ResultadoPrueba res = resultado( nueva );
        res.creada = new ResultadoPrueba.Creada();
        res.creada.datasets.add( new ResultadoPrueba.Archivo( "Leer ventas", "datasets/x/leer_ventas.csv", 12,
          null ) );
        res.creada.dorados.add( new ResultadoPrueba.Archivo( "Totales por producto", "golden/x/t.csv", 4, null ) );
        pruebas.add( nueva );
        t.resultado = res;
        o.terminada( t, res );
        c.fin.countDown();
      } );
      h.setDaemon( true );
      h.start();
      return c;
    }

    @Override
    public List<String[]> steps() {
      return Arrays.asList( new String[] { "Leer ventas", "CsvInput", "src" }, new String[] { "Calcular importe",
        "Calculator", "" }, new String[] { "Totales por producto", "GroupBy", "sel" }, new String[] {
          "Escribir totales", "TextFileOutput", "end" } );
    }

    @Override
    public void seleccionar( String step ) {
      seleccionado = step;
    }

    /** El resultado falso de una prueba: filtro falla, las demas pasan. */
    static ResultadoPrueba resultado( File f ) {
      ResultadoPrueba r = new ResultadoPrueba();
      r.prueba = PruebasMotor.nombreDeArchivo( f );
      r.archivoPrueba = f.getPath();
      r.segundos = 1.4;
      r.caso = new ResultadoCaso();
      r.caso.corrida.estado = ResultadoCaso.TERMINO;
      r.caso.corrida.segundos = 0.8;
      r.caso.corrida.errores = 0L;
      r.caso.tablas = new ArrayList<ResultadoCaso.ComparacionTabla>();
      ResultadoPrueba.Resumen s = new ResultadoPrueba.Resumen( "Totales por producto", ResultadoPrueba.Resumen.STEP );
      s.filasEsperadas = 4;
      s.filasReales = 4;
      s.iguales = 4;
      s.distintas = 0;
      s.faltan = 0;
      s.sobran = 0;
      s.paso = true;
      r.salidas.add( s );
      if ( !r.prueba.startsWith( "filtro" ) ) {
        r.estado = ResultadoPrueba.PASO;
        r.mensaje = "1 output matches the golden data (4 rows)";
        return r;
      }
      ResultadoPrueba.Resumen x = new ResultadoPrueba.Resumen( "Elegir columnas", ResultadoPrueba.Resumen.STEP );
      x.filasEsperadas = 5;
      x.filasReales = 5;
      x.iguales = 3;
      x.distintas = 1;
      x.faltan = 1;
      x.sobran = 1;
      x.paso = false;
      r.salidas.add( x );
      Comparacion c = new Comparacion();
      c.filas.esperadas = 5;
      c.filas.reales = 5;
      c.filas.iguales = 3;
      c.filas.distintas = 1;
      c.filas.faltan = 1;
      c.filas.sobran = 1;
      c.ejemplos.distintas.add( Comparacion.Ejemplo.diferencia( "7", "saldo", "1200.5", "980.0" ) );
      Comparacion.Ejemplo falta = new Comparacion.Ejemplo();
      falta.clave = "9";
      c.ejemplos.faltan.add( falta );
      Comparacion.Ejemplo sobra = new Comparacion.Ejemplo();
      sobra.clave = "12";
      c.ejemplos.sobran.add( sobra );
      r.caso.tablas.add( new ResultadoCaso.ComparacionTabla( "Elegir columnas", null, c ) );
      r.estado = ResultadoPrueba.FALLO;
      r.mensaje = "1 of 2 outputs differ: 'Elegir columnas' 1 changed rows, 1 missing rows, 1 extra rows";
      return r;
    }
  }

  // ================================================================ pruebas

  /** La lista, ejecutar todas, verde y rojo, y el clic en la que falla: su diferencia y su salida en el lienzo. */
  public void pruebaA_ListaYDiferencias() throws Exception {
    final Falso s = new Falso();
    final PruebasDialogo d = ui( () -> {
      PruebasDialogo x = new PruebasDialogo( host, s, r, "T1" );
      mostrar( x.shell );
      x.cargar();
      return x;
    } );
    try {
      ui( () -> {
        igual( "Pruebas · ventas_por_producto", d.shell.getText(), "título" );
        igual( 2, d.tabla.getItemCount(), "dos pruebas" );
        igual( "sin correr", d.tabla.getItem( 0 ).getText( 0 ), "sin resultado todavía" );
        igual( "filtro", d.tabla.getItem( 0 ).getText( 1 ), "por nombre" );
        igual( "Totales por producto, Elegir columnas", d.tabla.getItem( 0 ).getText( 2 ), "las salidas" );
        contiene( d.estado.getText(), "Las pruebas nuevas van a", "dónde van" );
        cierto( d.ejecutarTodas.getEnabled(), "se puede ejecutar" );
        falso( d.cancelar.getEnabled(), "nada que cancelar" );
        return null;
      } );
      pantalla( d.shell, "pruebas-sin-correr.png" );
      clic( d.ejecutarTodas );
      esperarUi( () -> !d.ocupado && d.filas.get( 0 ).resultado != null && d.filas.get( 1 ).resultado != null,
        "las dos corrieron" );
      ui( () -> {
        igual( "✗ falla", d.tabla.getItem( 0 ).getText( 0 ), "filtro en rojo" );
        igual( "✓ pasa", d.tabla.getItem( 1 ).getText( 0 ), "kpi en verde" );
        igual( "9/9", d.tabla.getItem( 0 ).getText( 3 ), "filas obtenidas / esperadas" );
        igual( "1 cambian · 1 faltan · 1 sobran", d.tabla.getItem( 0 ).getText( 4 ), "las diferencias" );
        igual( "todo igual", d.tabla.getItem( 1 ).getText( 4 ), "la que pasa" );
        igual( "2 pruebas · 1 en verde · 1 en rojo", d.resumen.getText(), "el resumen" );
        igual( "Listo: 1 en verde, 1 en rojo.", d.estado.getText(), "terminó" );
        d.tabla.setSelection( 0 );
        d.elegida( true );
        String t = d.detalle.getText();
        contiene( t, "filtro — Clientes activos con saldo", "nombre y descripción" );
        contiene( t, "✗ falla · 1 of 2 outputs differ", "estado y por qué" );
        contiene( t, "✗ Elegir columnas: 5 filas esperadas, 5 obtenidas · 3 iguales, 1 cambian, 1 faltan, 1 sobran",
          "el resumen de la salida" );
        contiene( t, "Cambia [7] · saldo: esperado «1200.5», obtenido «980.0»", "la celda que cambia" );
        contiene( t, "Falta la fila: 9", "la que falta" );
        contiene( t, "Sobra la fila: 12", "la que sobra" );
        contiene( t, "✓ Totales por producto: 4 filas esperadas", "la salida que pasa" );
        return null;
      } );
      igual( "Elegir columnas", s.seleccionado, "la salida que falla, marcada en el lienzo" );
      pantalla( d.shell, "pruebas-diferencias.png" );
    } finally {
      cerrarShell( d.shell );
    }
  }

  /** Ejecutar una (la elegida) y cancelar mientras corre. */
  public void pruebaB_Cancelar() throws Exception {
    final Falso s = new Falso();
    s.bloquear = new CountDownLatch( 1 );
    final PruebasDialogo d = ui( () -> {
      PruebasDialogo x = new PruebasDialogo( host, s, r, "T1" );
      mostrar( x.shell );
      x.cargar();
      x.tabla.setSelection( 1 );
      return x;
    } );
    try {
      clic( d.ejecutar );
      esperarUi( () -> d.ocupado && "… corriendo".equals( d.tabla.getItem( 1 ).getText( 0 ) ), "corriendo" );
      ui( () -> {
        igual( "sin correr", d.tabla.getItem( 0 ).getText( 0 ), "solo la elegida" );
        contiene( d.estado.getText(), "Corriendo 1 pruebas en una JVM aparte", "estado" );
        cierto( d.barra.getVisible(), "barra de progreso" );
        cierto( d.cancelar.getEnabled(), "se puede cancelar" );
        falso( d.ejecutarTodas.getEnabled(), "no se lanza otra a la vez" );
        return null;
      } );
      pantalla( d.shell, "pruebas-corriendo.png" );
      clic( d.cancelar );
      esperarUi( () -> !d.ocupado, "cancelada" );
      ui( () -> {
        igual( "Cancelado.", d.estado.getText(), "lo dice" );
        igual( "cancelada", d.tabla.getItem( 1 ).getText( 0 ), "la prueba quedó cancelada" );
        cierto( d.ejecutarTodas.getEnabled(), "libre otra vez" );
        return null;
      } );
    } finally {
      cerrarShell( d.shell );
    }
  }

  /** Crear desde la vista previa: el step elegido en el lienzo viene marcado; la nueva queda elegida. */
  public void pruebaC_Crear() throws Exception {
    final Falso s = new Falso();
    final AtomicReference<PruebasCrear> creador = new AtomicReference<PruebasCrear>();
    PruebasDialogo.abridor = c -> {
      mostrar( c.shell );
      creador.set( c );
    };
    final PruebasDialogo d = ui( () -> {
      PruebasDialogo x = new PruebasDialogo( host, s, r, "T1" );
      mostrar( x.shell );
      x.cargar();
      return x;
    } );
    try {
      clic( d.crear );
      final PruebasCrear c = creador.get();
      cierto( c != null, "se abrió la ventana de crear" );
      ui( () -> {
        igual( "Crear prueba · ventas_por_producto", c.shell.getText(), "título" );
        igual( "ventas_por_producto", c.nombre.getText(), "nombre propuesto" );
        igual( Arrays.asList( "Totales por producto" ), PruebasCrear.marcados( c.salidas ), "el elegido en el lienzo" );
        igual( Arrays.asList( "Leer ventas" ), PruebasCrear.marcados( c.entradas ), "la fuente como dataset" );
        cierto( c.saltar.getSelection(), "saltar lo que escribe, por defecto" );
        c.nombre.setText( "mal nombre" );
        return null;
      } );
      pantalla( c.shell, "pruebas-crear.png" );
      clic( c.crear );
      ui( () -> {
        igual( "El nombre solo puede tener letras, números, - y _ (hasta 80).", c.estado.getText(), "nombre malo" );
        c.nombre.setText( "kpi_nuevo" );
        return null;
      } );
      clic( c.crear );
      esperarUi( () -> c.shell.isDisposed(), "se cerró al crear" );
      igual( "kpi_nuevo", s.creada.path( "name" ).asText(), "los argumentos de test_create" );
      igual( "[\"Totales por producto\"]", s.creada.path( "output_steps" ).toString(), "salidas" );
      igual( "[\"Leer ventas\"]", s.creada.path( "input_steps" ).toString(), "entradas" );
      falso( s.creada.has( "tweaks" ), "con los ajustes por defecto (saltar lo que escribe)" );
      ui( () -> {
        igual( 3, d.tabla.getItemCount(), "la nueva en la lista" );
        igual( "kpi_nuevo", d.tabla.getItem( d.tabla.getSelectionIndex() ).getText( 1 ), "y elegida" );
        igual( "✓ pasa", d.tabla.getItem( d.tabla.getSelectionIndex() ).getText( 0 ), "en verde" );
        contiene( d.estado.getText(), "Prueba «kpi_nuevo» creada: 1 datasets y 1 salidas doradas.", "lo dice" );
        return null;
      } );
      pantalla( d.shell, "pruebas-creada.png" );
    } finally {
      cerrarShell( d.shell );
    }
  }

  /** «Ejecutar al guardar» guarda el ajuste (apagado por defecto) y el indicador del botón IDC. */
  public void pruebaD_AlGuardarEIndicador() throws Exception {
    falso( PruebasGuardado.activo(), "apagado por defecto" );
    final Falso s = new Falso();
    final PruebasDialogo d = ui( () -> {
      PruebasDialogo x = new PruebasDialogo( host, s, r, "T1" );
      mostrar( x.shell );
      x.cargar();
      falso( x.alGuardar.getSelection(), "desmarcado" );
      x.alGuardar.setSelection( true );
      x.alGuardar.notifyListeners( SWT.Selection, new Event() );
      return x;
    } );
    try {
      cierto( PruebasGuardado.activo(), "encendido y guardado" );
      ui( () -> {
        d.alGuardar.setSelection( false );
        d.alGuardar.notifyListeners( SWT.Selection, new Event() );
        return null;
      } );
      falso( PruebasGuardado.activo(), "apagado otra vez" );
    } finally {
      cerrarShell( d.shell );
    }
    igual( "✓ 12/12", PruebasTools.indicador( 12, 12 ), "todas en verde" );
    igual( "✗ 1", PruebasTools.indicador( 11, 12 ), "una en rojo" );
    ui( () -> {
      ToolBar tb = new ToolBar( host, SWT.FLAT );
      ToolItem it = new ToolItem( tb, SWT.DROP_DOWN );
      it.setData( IdcMenu.MARCA, Boolean.TRUE );
      it.setText( IdcMenu.TEXTO_BOTON );
      PruebasGuardado.poner( it, PruebasTools.indicador( 11, 12 ), "Pruebas de x: 1 en rojo" );
      igual( "IDC ✗ 1", it.getText(), "el botón con el indicador" );
      igual( "Pruebas de x: 1 en rojo", it.getToolTipText(), "y su detalle" );
      PruebasGuardado.poner( it, null, null );
      igual( "IDC", it.getText(), "sin indicador" );
      tb.dispose();
      return null;
    } );
  }

  // ================================================================ utilidades

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

  private void esperarUi( final BooleanSupplier c, String que ) throws Exception {
    long fin = System.currentTimeMillis() + ESPERA_MS;
    while ( !ui( () -> c.getAsBoolean() ) ) {
      if ( System.currentTimeMillis() > fin ) {
        Verificador.falla( "no pasó a tiempo: " + que );
      }
      Thread.sleep( 30 );
    }
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
  }
}
