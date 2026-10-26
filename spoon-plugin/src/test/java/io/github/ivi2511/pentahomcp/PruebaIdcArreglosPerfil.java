package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;
import io.github.ivi2511.pentahomcp.lint.Resultado;

/**
 * La ventana de detalle de «Revisar calidad…» con los arreglos nuevos de 0.7.1, sobre un documento de verdad (lint y
 * apply_lint_fixes reales, sin Spoon): la descripción propuesta de un step se edita como valor y el ensayo dice qué
 * pondrá; la conexión con host literal muestra las líneas de kettle.properties; aplicar desde la ventana cambia el
 * documento. Fuera de la pantalla, sin activar nada (como PruebaIdcDialogos); capturas en {@code <prueba>\salida\idc\}.
 */
public class PruebaIdcArreglosPerfil {

  private static final long ESPERA_MS = 30000L;

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell host;
  private File capturas;
  private java.util.function.BiConsumer<Shell, Shell> abridorAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    capturas = EntornoKettle.salida( "idc" );
    abridorAntes = IdcDetalle.abridor;
    IdcDetalle.abridor = ( v, p ) -> mostrar( v );
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
    }, "ui-prueba-idc-perfil" );
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
    IdcDetalle.abridor = abridorAntes;
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

  /** El servicio del diálogo sobre un documento en memoria: lint_etl y apply_lint_fixes de verdad, con el perfil. */
  static final class ServicioReal implements IdcCalidad.Servicio {
    final AbstractMeta doc;
    final String lang;

    ServicioReal( AbstractMeta doc, String lang ) {
      this.doc = doc;
      this.lang = lang;
    }

    private Opciones opciones( AbstractMeta copia ) {
      Opciones op = new Opciones();
      op.lang = lang;
      op.sinGuardar = true;
      op.entorno = new LintEntorno( copia );
      op.perfil = PerfilEquipo.desdeTexto( PruebaArreglosPerfil.PERFIL, false );
      return op;
    }

    Resultado lint() {
      AbstractMeta copia = ReportTools.clonar( doc );
      Resultado r = LintMotor.analizar( copia, opciones( copia ) );
      LintPropuestas.completar( r, copia, lang );
      return r;
    }

    @Override
    public String nombre() {
      return doc.getName();
    }

    @Override
    public ObjectNode revisar( boolean profunda, boolean robustez, Consumer<Runnable> alCancelar ) {
      return LintJson.resultado( lint() ).put( "document", "T1" );
    }

    @Override
    public ObjectNode arreglar( Collection<String> keys, Map<String, String> valores, boolean ensayo,
                                boolean continuar ) throws Exception {
      ObjectNode a = McpHttpServer.JSON.createObjectNode();
      com.fasterxml.jackson.databind.node.ArrayNode ks = a.putArray( "keys" );
      for ( String k : keys ) {
        ks.add( k );
      }
      a.set( "values", McpHttpServer.JSON.valueToTree( valores ) );
      a.put( "language", lang );
      LintArreglos.Pedido p = LintArreglos.pedido( a );
      AbstractMeta copia = ReportTools.clonar( doc );
      LintArreglos.Analisis an = LintArreglos.analizar( copia, opciones( copia ), p );
      AbstractMeta donde = ensayo ? ReportTools.clonar( doc ) : doc;
      synchronized ( doc ) {
        LintArreglos.Hecho h = LintArreglos.aplicar( donde, an, lang, UndoGrupo.sinSpoon( donde ), null, null );
        AbstractMeta copia2 = ReportTools.clonar( donde );
        Resultado despues = LintMotor.analizar( copia2, opciones( copia2 ) );
        return LintArreglos.respuesta( "T1", an, h, despues, ensayo, true, donde instanceof TransMeta, lang );
      }
    }

    @Override
    public ObjectNode recetas( List<String[]> pares, boolean ensayo ) {
      return McpHttpServer.JSON.createObjectNode();
    }

    @Override
    public ObjectNode silenciar( String regla, String step, String entry, String motivo ) {
      return McpHttpServer.JSON.createObjectNode();
    }

    @Override
    public void seleccionar( String step, String entry ) {
      // sin lienzo
    }
  }

  private static TransMeta documento() throws Exception {
    TransMeta tm = new TransMeta( EntornoKettle.ejemplo( "02" ).getAbsolutePath() );
    tm.addDatabase( new DatabaseMeta( "ventas dw", "POSTGRESQL", "Native", "192.0.2.5", "dw", "5432", "etl",
      "Encrypted 2be98afc86aa7f2e4cb79ce10df90acde" ) );
    return tm;
  }

  /** El índice en la tabla del hallazgo de esa regla (y ese step, si se da). */
  private int fila( IdcCalidad d, String regla, String step ) throws Exception {
    return ui( () -> {
      for ( int i = 0; i < d.hallazgos.size(); i++ ) {
        JsonNode h = d.hallazgos.get( i );
        if ( regla.equals( h.path( "rule" ).asText() ) && ( step == null || step.equals( h.path( "step" )
          .asText() ) ) ) {
          return i;
        }
      }
      return -1;
    } );
  }

  public void pruebaDetalleDeLosArreglosNuevos() throws Exception {
    final TransMeta tm = documento();
    final ServicioReal s = new ServicioReal( tm, "es" );
    final Rotulos r = Rotulos.de( IdcMenu.AREA, "es" );
    final IdcCalidad d = ui( () -> {
      IdcCalidad x = new IdcCalidad( host, s, r, "T1" );
      mostrar( x.shell );
      x.revisar();
      return x;
    } );
    try {
      esperarUi( () -> !d.ocupado && d.tabla.getItemCount() > 0, "la revisión" );
      // la descripción de un step: valor editable con la propuesta y el ensayo con lo que pondrá
      final int i = fila( d, PerfilEquipo.STEP_DESCRIPTION, "Leer clientes" );
      cierto( i >= 0, "el hallazgo step_without_description de «Leer clientes»" );
      ui( () -> {
        d.tabla.setSelection( i );
        d.abrirDetalle();
        return null;
      } );
      esperarUi( () -> d.ventana != null && d.ventana.cuerpo.getText().contains( "Descrito el step" ),
        "el ensayo del arreglo de descripción" );
      ui( () -> {
        cierto( d.ventana.filaValor.getVisible(), "el valor editable" );
        igual( r.s( "d_value_description" ), d.ventana.etiquetaValor.getText(), "etiqueta del valor" );
        igual( "Lee clientes.csv (6 campos)", d.ventana.valor.getText(), "la propuesta" );
        contiene( d.ventana.cuerpo.getText(), "Descrito el step 'Leer clientes': Lee clientes.csv (6 campos)",
          "los cambios exactos" );
        cierto( d.ventana.aplicar.getEnabled(), "aplicar habilitado" );
        return null;
      } );
      pantalla( d.ventana.shell, "calidad-detalle-descripcion.png" );
      // otra descripción, aplicada desde la ventana
      ui( () -> {
        d.ventana.valor.setText( "Lee la cartera de clientes" );
        d.ventana.verCambios.notifyListeners( SWT.Selection, new Event() );
        return null;
      } );
      esperarUi( () -> d.ventana.cuerpo.getText().contains( "Lee la cartera de clientes" ), "ensayo con otro valor" );
      ui( () -> {
        d.ventana.aplicar.notifyListeners( SWT.Selection, new Event() );
        return null;
      } );
      esperar( () -> "Lee la cartera de clientes".equals( tm.findStep( "Leer clientes" ).getDescription() ),
        "aplicada en el documento" );

      // la conexión: las líneas de kettle.properties, nunca la contraseña
      esperarUi( () -> !d.ocupado, "la revisión después de aplicar" );
      final int j = fila( d, PerfilEquipo.REQUIRED_VARIABLE, null );
      cierto( j >= 0, "el hallazgo required_variable del host" );
      ui( () -> {
        d.tabla.setSelection( j );
        d.abrirDetalle();
        return null;
      } );
      esperarUi( () -> d.ventana.cuerpo.getText().contains( "usa ahora" ), "el ensayo de la conexión" );
      ui( () -> {
        String t = d.ventana.cuerpo.getText();
        contiene( t, "${DB_HOST_VENTAS_DW}", "las variables" );
        contiene( t, "DB_HOST_VENTAS_DW=192.0.2.5", "la línea de kettle.properties" );
        cierto( !t.contains( "Encrypted" ), "sin la contraseña" );
        return null;
      } );
      pantalla( d.ventana.shell, "calidad-detalle-conexion.png" );
    } finally {
      ui( () -> {
        if ( !d.shell.isDisposed() ) {
          d.shell.dispose();
        }
        return null;
      } );
    }
  }

  // ================================================================ util

  private static void mostrar( Shell s ) {
    s.setLocation( PruebaPanelSwt.X, PruebaPanelSwt.Y );
    s.setVisible( true );
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

  private static void esperar( BooleanSupplier c, String que ) throws Exception {
    long fin = System.currentTimeMillis() + ESPERA_MS;
    while ( !c.getAsBoolean() ) {
      if ( System.currentTimeMillis() > fin ) {
        Verificador.falla( "no pasó a tiempo: " + que );
      }
      Thread.sleep( 30 );
    }
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
