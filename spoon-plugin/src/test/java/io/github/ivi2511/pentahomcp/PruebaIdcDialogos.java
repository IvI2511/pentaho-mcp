package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ArrayNode;
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
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Los diálogos del menú IDC con servicios falsos: «Revisar calidad…» ({@link IdcCalidad}: puntuación, tabla, clic =
 * step en el lienzo, detalle con formato, casillas y «Aplicar marcados» con recetas de robustez encadenadas, la ventana
 * de detalle con su ensayo, su valor editable, aplicar solo ese y el pedido para Claude, silenciar, revisión profunda
 * (y la que no tiene nada que leer) y cancelar) y «Robustecer…» ({@link IdcRobustez}: casillas del plan, detalle con
 * los cambios, aplicar con confirm/excluidas, la ventana de detalle de una receta, «qué hará» la prueba con datos
 * sucios antes de correrla, su veredicto, el problema del entorno aparte, cancelar y la prueba que no puede correr). También el botón en una barra con un selector de perspectivas de mentira y
 * los MenuItem del modelo.
 * <p>
 * Como PruebaPanelSwt: las ventanas se abren fuera de la pantalla (-20000, -20000), sin activarse
 * (se arman con su constructor y se muestran con setVisible, no con open()), nunca arriba de todo, y las capturas salen con {@code Control.print(GC)} en
 * {@code <prueba>\salida\idc\}.
 */
public class PruebaIdcDialogos {

  private static final long ESPERA_MS = 20000L;

  private Display display;
  private Thread hiloUi;
  private volatile boolean parar;
  private Shell host;
  private File capturas;
  private Rotulos r;
  private IdcCalidad.Preguntador preguntadorAntes;
  private java.util.function.BiConsumer<Shell, Shell> abridorAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    capturas = EntornoKettle.salida( "idc" );
    r = Rotulos.de( IdcMenu.AREA, "es" );
    preguntadorAntes = IdcCalidad.preguntador;
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
    }, "ui-prueba-idc" );
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
    IdcCalidad.preguntador = preguntadorAntes;
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

  // ================================================================ calidad

  /** Revisión, tabla, clic = step, arreglos marcados (y otra revisión), silenciar. */
  public void pruebaA_Calidad() throws Exception {
    final CalidadFalsa s = new CalidadFalsa();
    final IdcCalidad d = ui( () -> {
      IdcCalidad x = new IdcCalidad( host, s, r, "T1" );
      mostrar( x.shell );
      x.revisar();
      return x;
    } );
    try {
      esperarUi( () -> !d.ocupado && d.tabla.getItemCount() == 4, "primera revisión" );
      ui( () -> {
        igual( "Calidad · ventas_por_producto", d.shell.getText(), "título" );
        igual( "62/100 · C", d.puntaje.getText(), "puntuación y nota" );
        igual( "", d.robustez.getText(), "sin robustez" );
        contiene( d.conteos.getText(), "1 errores · 1 avisos · 1 info", "cuentas" );
        contiene( d.conteos.getText(), "1 silenciados", "silenciados" );
        igual( "Error", d.tabla.getItem( 0 ).getText( 1 ), "gravedad en castellano" );
        igual( "Ruta absoluta", d.tabla.getItem( 0 ).getText( 2 ), "la regla con su título en castellano" );
        igual( "el step «Leer ventas»", d.tabla.getItem( 0 ).getText( 3 ), "dónde: el step" );
        igual( "automático", d.tabla.getItem( 1 ).getText( 5 ), "arreglo automático" );
        igual( "con confirmación", d.tabla.getItem( 0 ).getText( 5 ), "arreglo que cambia una ruta" );
        igual( "pide un valor", d.tabla.getItem( 2 ).getText( 5 ), "auto que pide un valor: no es automático" );
        igual( "receta: Filas malas a un archivo de rechazos", d.tabla.getItem( 3 ).getText( 5 ),
          "robustez: su receta" );
        cierto( d.tabla.getItem( 1 ).getChecked(), "la automática empieza marcada" );
        falso( d.tabla.getItem( 0 ).getChecked() || d.tabla.getItem( 3 ).getChecked(), "las demás no" );
        igual( "Aplicar marcados (1)", d.aplicar.getText(), "un arreglo marcado" );
        cierto( d.aplicar.getEnabled(), "aplicar habilitado" );
        falso( d.silenciar.getEnabled(), "silenciar sin hallazgo elegido" );
        contiene( d.pie.getText(), "1 comprobaciones sin evaluar: Entrada sin ordenar por la clave («Agrupar»): "
          + "deep=false", "no evaluados con su motivo" );
        falso( s.profunda.get( 0 ), "primera revisión sin profunda" );
        return null;
      } );
      pantalla( d.shell, "calidad.png" );

      // clic en un hallazgo: el detalle con formato y el step en el lienzo
      ui( () -> {
        d.tabla.setSelection( 1 );
        d.tabla.notifyListeners( SWT.Selection, new Event() );
        String t = (String) d.detalle.getData( "plano" );
        contiene( t, "Entrada sin ordenar por la clave", "detalle: el título" );
        contiene( t, "Qué pasa\nGroup by sin ordenar", "detalle: el mensaje bajo su encabezado" );
        contiene( t, "Por qué importa", "detalle: por qué importa (catálogo)" );
        contiene( t, "Cómo arreglarlo\nPon un Sort rows", "detalle: cómo arreglarlo" );
        contiene( t, "order: desconocido", "detalle: la evidencia" );
        contiene( t, "Doble clic (o Intro)", "detalle: cómo abrir la ventana" );
        cierto( d.silenciar.getEnabled() && d.verDetalle.getEnabled(), "silenciar y ver detalle habilitados" );
        return null;
      } );
      igual( Arrays.asList( "Agrupar" ), s.seleccionados, "el step elegido en el lienzo" );

      // un hallazgo de robustez marcado: su receta va primero y los arreglos se encadenan a ella
      ui( () -> {
        d.tabla.getItem( 3 ).setChecked( true );
        Event e = new Event();
        e.detail = SWT.CHECK;
        e.item = d.tabla.getItem( 3 );
        d.tabla.notifyListeners( SWT.Selection, e );
        igual( "Aplicar marcados (2)", d.aplicar.getText(), "dos marcados" );
        // una que pide un valor sin valor no se puede marcar
        d.tabla.getItem( 2 ).setChecked( true );
        e = new Event();
        e.detail = SWT.CHECK;
        e.item = d.tabla.getItem( 2 );
        d.tabla.notifyListeners( SWT.Selection, e );
        falso( d.tabla.getItem( 2 ).getChecked(), "sin valor no se marca" );
        return null;
      } );
      clic( d.aplicar );
      esperarUi( () -> !d.ocupado && s.revisiones.get() == 2, "aplicar y revisar otra vez" );
      igual( 1, s.arreglos.get(), "apply_lint_fixes una vez" );
      igual( Collections.singletonList( "unsorted_input:Agrupar" ), s.keys, "las keys marcadas" );
      igual( "rejects_on_error|Cargar fecha", s.pares, "la receta del hallazgo de robustez, en su step" );
      cierto( s.continuar, "los arreglos se encadenan al undo de la receta" );
      falso( s.ensayo, "no es un ensayo" );
      ui( () -> {
        contiene( d.estado.getText(), "2 arreglos aplicados (se deshacen con un Ctrl+Z). Puntuación 62 → 70",
          "aviso de lo aplicado" );
        return null;
      } );

      // silenciar la regla del hallazgo elegido en su step, con motivo
      IdcCalidad.preguntador = ( p, t, x ) -> {
        contiene( t, "Silenciar «Ruta absoluta»", "título de la pregunta" );
        contiene( x, "Es un error", "avisa que es un error" );
        contiene( x, "el step «Leer ventas»", "dónde" );
        return "  ruta del servidor de la uni  ";
      };
      ui( () -> {
        d.tabla.setSelection( 0 );
        d.elegida( false );
        return null;
      } );
      clic( d.silenciar );
      esperarUi( () -> !d.ocupado && s.revisiones.get() == 3, "silenciar y revisar otra vez" );
      igual( Arrays.asList( "absolute_path", "Leer ventas", null, "ruta del servidor de la uni" ), s.silenciado,
        "suppress_lint con regla, step y motivo" );
      ui( () -> {
        contiene( d.estado.getText(), "«Ruta absoluta» silenciada en el step «Leer ventas»", "aviso" );
        return null;
      } );

      // preguntar y cancelar: no se silencia nada
      IdcCalidad.preguntador = ( p, t, x ) -> null;
      clic( d.silenciar );
      Thread.sleep( 300 );
      igual( 3, s.revisiones.get(), "sin revisión nueva" );
    } finally {
      cerrarShell( d.shell );
    }
  }

  /** Revisión profunda con robustez, que se cancela; después una que termina con su pie; y una sin nada que leer. */
  public void pruebaB_CalidadProfundaYCancelar() throws Exception {
    final CalidadFalsa s = new CalidadFalsa();
    final IdcCalidad d = ui( () -> {
      IdcCalidad x = new IdcCalidad( host, s, r, "T1" );
      mostrar( x.shell );
      x.revisar();
      return x;
    } );
    try {
      esperarUi( () -> !d.ocupado && d.tabla.getItemCount() == 4, "primera revisión" );
      s.bloquear = new CountDownLatch( 1 );
      ui( () -> {
        d.profunda.setSelection( true );
        d.conRobustez.setSelection( true );
        return null;
      } );
      clic( d.revisar );
      esperarUi( () -> d.ocupado && d.cancelar.getEnabled(), "revisión profunda en curso" );
      esperar( () -> s.enEspera, "el lint falso esperando" );
      ui( () -> {
        contiene( d.estado.getText(), "Revisando «ventas_por_producto» y leyendo datos (hasta 60 s)", "estado" );
        cierto( d.barra.getVisible(), "barra de progreso" );
        falso( d.revisar.getEnabled(), "revisar deshabilitado mientras corre" );
        return null;
      } );
      pantalla( d.shell, "calidad-revisando.png" );
      clic( d.cancelar );
      esperar( () -> s.cortada.get() == 1, "el corte del lint llamado" );
      ui( () -> {
        falso( d.ocupado, "libre" );
        igual( "Revisión cancelada.", d.estado.getText(), "cancelada" );
        falso( d.barra.getVisible(), "sin barra" );
        igual( 4, d.tabla.getItemCount(), "la tabla de antes sigue" );
        return null;
      } );
      cierto( s.profunda.get( 1 ), "pidió profunda" );
      cierto( s.robustez.get( 1 ), "pidió robustez" );

      // otra: termina, con robustez y el pie de la profunda
      s.bloquear = null;
      s.conRobustez = true;
      clic( d.revisar );
      esperarUi( () -> !d.ocupado && d.robustez.getText().length() > 0, "revisión profunda terminada" );
      ui( () -> {
        igual( "Robustez 40/100 · D", d.robustez.getText(), "sub-puntuación de robustez" );
        contiene( d.pie.getText(), "Revisión profunda: 2 sondas en 1,5 s · 1 confirmados · 0 descartados · 1 sin "
          + "evaluar", "pie de la profunda" );
        return null;
      } );
      pantalla( d.shell, "calidad-profunda.png" );

      // una profunda sin nada que leer: lo dice en vez de «0 sondas»
      s.sondas = 0;
      clic( d.revisar );
      esperarUi( () -> !d.ocupado && s.revisiones.get() == 4, "otra revisión profunda" );
      ui( () -> {
        noContiene( d.pie.getText(), "0 sondas", "nada de «0 sondas»" );
        contiene( d.pie.getText(), "Revisión profunda: no había nada que leer", "explica por qué" );
        return null;
      } );
    } finally {
      cerrarShell( d.shell );
    }
  }

  // ================================================================ robustez

  /** Plan con casillas, aplicar con confirm y excluidas, prueba con datos sucios (cancelada y terminada). */
  public void pruebaC_Robustecer() throws Exception {
    final RobustezFalsa s = new RobustezFalsa();
    final IdcRobustez d = ui( () -> {
      IdcRobustez x = new IdcRobustez( host, s, r, "T1" );
      mostrar( x.shell );
      x.cargarPlan();
      return x;
    } );
    try {
      esperarUi( () -> !d.ocupado && d.tabla.getItemCount() == 3 && d.previa != null, "el plan y la previa" );
      ui( () -> {
        igual( "Robustecer · carga_ventas", d.shell.getText(), "título" );
        igual( "Robustez ahora: 35/100 · D", d.puntaje.getText(), "robustez antes" );
        cierto( d.tabla.getItem( 0 ).getChecked(), "auto marcada" );
        falso( d.tabla.getItem( 1 ).getChecked(), "confirm que cambia la carga desmarcada" );
        igual( "⚠ cambia la carga", d.tabla.getItem( 1 ).getText( 5 ), "y marcada con ⚠" );
        igual( "confirmar", d.tabla.getItem( 1 ).getText( 2 ), "nivel" );
        igual( "Filas malas a un archivo de rechazos", d.tabla.getItem( 0 ).getText( 1 ), "receta con su título" );
        falso( d.tabla.getItem( 2 ).getChecked(), "manual sin marcar" );
        cierto( d.tabla.getItem( 2 ).getGrayed(), "manual en gris" );
        igual( "Aplicar (1)", d.aplicar.getText(), "una marcada" );
        String t = (String) d.detalle.getData( "plano" );
        contiene( t, "Cambios\n+ step Escribir rechazos", "detalle de la primera: los cambios" );
        contiene( t, "Un solo Ctrl+Z deshace todo esto", "y cómo se deshacen" );
        // antes de probar: qué hará, sin una tabla vacía
        falso( d.resultados.getVisible(), "sin tabla de comprobaciones antes de probar" );
        cierto( d.queHara.getVisible(), "qué hará, a la vista" );
        String q = (String) d.queHara.getData( "plano" );
        contiene( q, "Qué hará la prueba", "encabezado" );
        contiene( q, "«Leer ventas»: 50 filas de base, 4 campos", "las entradas que ensuciará" );
        contiene( q, "Todo junto: cada tipo de fila rota en un mismo archivo", "las variantes en castellano" );
        contiene( q, "Todo listo", "va a poder correr" );
        return null;
      } );
      pantalla( d.shell, "robustecer-plan.png" );

      // una manual no se puede marcar
      ui( () -> {
        d.tabla.getItem( 2 ).setChecked( true );
        Event e = new Event();
        e.detail = SWT.CHECK;
        e.item = d.tabla.getItem( 2 );
        d.tabla.notifyListeners( SWT.Selection, e );
        falso( d.tabla.getItem( 2 ).getChecked(), "la manual vuelve a desmarcarse" );
        String t = (String) d.detalle.getData( "plano" );
        contiene( t, "Cómo hacerlo a mano\nUsa un Combination lookup/update", "detalle de la manual: cómo hacerlo" );
        contiene( t, "Pentaho Live no puede arreglarlo solo", "y que se lo puede pedir a Claude" );
        // marcar la confirm, desmarcar la auto
        d.tabla.getItem( 1 ).setChecked( true );
        d.tabla.getItem( 0 ).setChecked( false );
        e = new Event();
        e.detail = SWT.CHECK;
        e.item = d.tabla.getItem( 0 );
        d.tabla.notifyListeners( SWT.Selection, e );
        igual( "Aplicar (1)", d.aplicar.getText(), "una marcada" );
        return null;
      } );
      clic( d.aplicar );
      esperarUi( () -> !d.ocupado && s.planes.get() == 2, "aplicar y plan nuevo" );
      igual( new LinkedHashSet<String>( Collections.singletonList( "required_fields:Leer ventas" ) ), s.confirmar,
        "confirm = la confirm marcada" );
      igual( new LinkedHashSet<String>( Collections.singletonList( "rejects_on_error:Cargar ventas" ) ), s.excluir,
        "excluidas = la auto desmarcada" );
      ui( () -> {
        contiene( d.estado.getText(), "1 cambios aplicados: un Ctrl+Z los deshace. Robustez 35 D → 70 B", "aviso" );
        cierto( d.tabla.getItem( 0 ).getChecked(), "plan nuevo: la auto vuelve marcada" );
        return null;
      } );

      // prueba con datos sucios: cancelar
      s.bloquear = true;
      clic( d.probar );
      esperarUi( () -> d.ocupado && d.cancelar.getEnabled(), "prueba en curso" );
      esperar( () -> s.corte != null, "la prueba falsa empezó" );
      ui( () -> {
        contiene( d.estado.getText(), "Probando «carga_ventas» con datos sucios", "estado" );
        cierto( d.barra.getVisible(), "barra" );
        falso( d.aplicar.getEnabled(), "aplicar deshabilitado mientras corre" );
        return null;
      } );
      Thread.sleep( 1200 );
      ui( () -> {
        contiene( d.reloj.getText(), " s", "el reloj corre: " + d.reloj.getText() );
        return null;
      } );
      pantalla( d.shell, "robustecer-probando.png" );
      clic( d.cancelar );
      esperar( () -> s.corte != null && s.corte.pedido(), "el corte pedido" );
      ui( () -> {
        igual( "Prueba cancelada.", d.estado.getText(), "cancelada" );
        falso( d.ocupado, "libre" );
        return null;
      } );

      // y una que termina: frágil, con un problema del entorno aparte
      s.bloquear = false;
      clic( d.probar );
      esperarUi( () -> !d.ocupado && d.comprobaciones.getItemCount() > 0, "veredicto" );
      ui( () -> {
        igual( "Veredicto: FRÁGIL: alguna variante abortó, perdió filas o rechazó sin motivo",
          d.veredicto.getText(), "veredicto" );
        cierto( d.entorno.getVisible(), "el entorno aparte" );
        contiene( d.entorno.getText(), "Problema del entorno (no es un fallo de robustez): the database file "
          + "dw.sqlite does not exist", "problema del entorno" );
        cierto( d.resultados.getVisible(), "ahora sí la tabla" );
        falso( d.queHara.getVisible(), "y ya no el «qué hará»" );
        igual( 4, d.comprobaciones.getItemCount(), "3 comprobaciones de combined + empty_file sin evaluar" );
        igual( "No aborta", d.comprobaciones.getItem( 0 ).getText( 1 ), "comprobación en castellano" );
        igual( "falla", d.comprobaciones.getItem( 1 ).getText( 2 ), "estado en castellano" );
        igual( "sin evaluar", d.comprobaciones.getItem( 3 ).getText( 2 ), "variante sin evaluar" );
        String m = (String) d.mensajes.getData( "plano" );
        contiene( m, "2 pasan · 0 avisos · 1 fallan", "resumen" );
        contiene( m, "Siguiente paso: harden_etl recipes=[required_fields]", "siguiente" );
        contiene( m, "•  Not a security sandbox", "notas" );
        // una comprobación elegida: su mensaje entero arriba del resumen
        Event e = new Event();
        e.item = d.comprobaciones.getItem( 1 );
        d.comprobaciones.notifyListeners( SWT.Selection, e );
        m = (String) d.mensajes.getData( "plano" );
        cierto( m.startsWith( "combined · No pierde filas en silencio · falla\n3 rows lost in Filtrar" ),
          "mensaje de la comprobación: " + m );
        contiene( m, "2 pasan · 0 avisos · 1 fallan", "y el resumen debajo" );
        return null;
      } );
      pantalla( d.shell, "robustecer-veredicto.png" );
    } finally {
      cerrarShell( d.shell );
    }
  }

  /** «Probar con datos sucios» cuando no va a poder correr: lo dice antes, y después no deja una tabla vacía. */
  public void pruebaG_RobustecerSinPoderProbar() throws Exception {
    final RobustezFalsa s = new RobustezFalsa();
    s.conBaseMysql = true;
    final IdcRobustez d = ui( () -> {
      IdcRobustez x = new IdcRobustez( host, s, r, "T1" );
      mostrar( x.shell );
      x.cargarPlan();
      return x;
    } );
    try {
      esperarUi( () -> !d.ocupado && d.previa != null, "el plan y la previa" );
      ui( () -> {
        String q = (String) d.queHara.getData( "plano" );
        contiene( q, "No se va a poder ejecutar", "lo avisa antes" );
        contiene( q, "bases de datos que no son archivos SQLite/H2 (GYM_DW (MYSQL)", "con el porqué" );
        contiene( q, "haz una copia de la transformación que cargue en una base SQLite", "y qué hacer" );
        return null;
      } );
      clic( d.probar );
      esperarUi( () -> !d.ocupado && d.prueba != null, "la prueba que no corre" );
      ui( () -> {
        falso( d.resultados.getVisible(), "sin tabla vacía" );
        cierto( d.queHara.getVisible(), "el texto con el porqué" );
        contiene( (String) d.queHara.getData( "plano" ), "Motivo: la transformación usa bases de datos",
          "el motivo" );
        contiene( d.veredicto.getText(), "NO SE EJECUTÓ", "veredicto" );
        return null;
      } );
      pantalla( d.shell, "robustecer-sin-probar.png" );

      // la ventana de detalle de una receta: doble clic, sus acciones y el pedido para Claude
      final String[] copiado = new String[ 1 ];
      IdcDetalle.portapapeles = ( dis, t ) -> copiado[ 0 ] = t;
      ui( () -> {
        d.tabla.setSelection( 2 );
        d.tabla.notifyListeners( SWT.DefaultSelection, new Event() );
        return null;
      } );
      esperarUi( () -> d.ventana != null && d.ventana.shell.isVisible(), "la ventana de detalle" );
      ui( () -> {
        igual( "Claves subrogadas estables", d.ventana.titulo.getText(), "título: la receta" );
        contiene( d.ventana.insignia.getText(), "a mano", "insignia: el nivel" );
        falso( d.ventana.aplicar.getVisible(), "una manual no se aplica" );
        d.ventana.claude.notifyListeners( SWT.Selection, new Event() );
        contiene( copiado[ 0 ], "Robustecer (harden_etl) propone esta receta", "el pedido" );
        contiene( copiado[ 0 ], "Usa un Combination lookup/update", "con el cómo" );
        contiene( d.ventana.estado.getText(), "Pedido copiado", "lo dice" );
        return null;
      } );
      pantalla( d.ventana.shell, "robustecer-detalle.png" );
      ui( () -> {
        d.tabla.setSelection( 1 );
        d.abrirDetalle();
        cierto( d.ventana.aplicar.getVisible() && d.ventana.aplicar.getEnabled(), "una confirm sí se aplica" );
        d.ventana.irAlStep.notifyListeners( SWT.Selection, new Event() );
        return null;
      } );
      igual( Collections.singletonList( "Leer ventas" ), s.seleccionados, "ir al step" );
      clic( d.ventana.aplicar );
      esperarUi( () -> !d.ocupado && s.planes.get() == 2, "aplicar esta receta y plan nuevo" );
      igual( new LinkedHashSet<String>( Collections.singletonList( "required_fields:Leer ventas" ) ), s.confirmar,
        "solo esa" );
      igual( new LinkedHashSet<String>( Collections.singletonList( "rejects_on_error:Cargar ventas" ) ), s.excluir,
        "las demás auto, excluidas" );
    } finally {
      IdcDetalle.portapapeles = PORTAPAPELES;
      cerrarShell( d.shell );
    }
  }

  /** La ventana de detalle de un hallazgo: contenido, valor editable, ensayo, aplicar solo ese y pedírselo a Claude. */
  public void pruebaF_CalidadDetalle() throws Exception {
    final CalidadFalsa s = new CalidadFalsa();
    final String[] copiado = new String[ 1 ];
    IdcDetalle.portapapeles = ( dis, t ) -> copiado[ 0 ] = t;
    final IdcCalidad d = ui( () -> {
      IdcCalidad x = new IdcCalidad( host, s, r, "T1" );
      mostrar( x.shell );
      x.revisar();
      return x;
    } );
    try {
      esperarUi( () -> !d.ocupado && d.tabla.getItemCount() == 4, "primera revisión" );
      // la contraseña: confirm con la variable propuesta, y dónde ponerla
      ui( () -> {
        d.tabla.setSelection( 0 );
        d.tabla.notifyListeners( SWT.Selection, new Event() );
        d.tabla.notifyListeners( SWT.DefaultSelection, new Event() );
        return null;
      } );
      esperarUi( () -> d.ventana != null && d.ventana.shell.isVisible(), "la ventana de detalle" );
      esperarUi( () -> d.ventana.cuerpo.getText().contains( "Qué cambiará" ) && !d.ventana.cuerpo.getText()
        .contains( "Calculando" ), "el ensayo del arreglo" );
      ui( () -> {
        igual( "Ruta absoluta", d.ventana.titulo.getText(), "título" );
        contiene( d.ventana.insignia.getText(), "Error", "insignia" );
        contiene( d.ventana.donde.getText(), "Regla absolute_path · el step «Leer ventas»", "dónde" );
        cierto( d.ventana.irAlStep.getVisible(), "ir al step" );
        cierto( d.ventana.filaValor.getVisible(), "el valor editable" );
        igual( "Ruta nueva:", d.ventana.etiquetaValor.getText(), "etiqueta del valor" );
        igual( "${Internal.Entry.Current.Directory}/datos/ventas.csv", d.ventana.valor.getText(), "la propuesta" );
        String t = d.ventana.cuerpo.getText();
        contiene( t, "Qué pasa", "qué pasa" );
        contiene( t, "Por qué importa", "por qué" );
        contiene( t, "•  In 'Leer ventas', filename: C:\\datos -> ${Internal.Entry.Current.Directory}/datos/ventas.csv",
          "los cambios exactos del ensayo" );
        contiene( t, "Puntuación: 62 → 70", "la puntuación que tendría" );
        contiene( d.ventana.enlace.getText(), "06-calidad.md · absolute_path", "el enlace al manual" );
        cierto( d.ventana.aplicar.getEnabled(), "aplicar habilitado (con la propuesta)" );
        return null;
      } );
      cierto( s.ensayo, "fue un ensayo" );
      igual( "${Internal.Entry.Current.Directory}/datos/ventas.csv", s.valores.get( "absolute_path:Leer ventas" ),
        "con el valor propuesto" );
      pantalla( d.ventana.shell, "calidad-detalle.png" );

      // otro valor: «Ver cambios» hace otro ensayo con él
      ui( () -> {
        d.ventana.valor.setText( "${DIR}/ventas.csv" );
        d.ventana.verCambios.notifyListeners( SWT.Selection, new Event() );
        return null;
      } );
      esperar( () -> "${DIR}/ventas.csv".equals( s.valores.get( "absolute_path:Leer ventas" ) ), "ensayo con el valor" );
      ui( () -> {
        cierto( d.tabla.getItem( 0 ).getText( 5 ).equals( "con valor elegido" ), "la fila lo refleja" );
        return null;
      } );

      // pedírselo a Claude: el pedido con todo
      ui( () -> {
        d.ventana.claude.notifyListeners( SWT.Selection, new Event() );
        return null;
      } );
      contiene( copiado[ 0 ], "Tengo abierta en Spoon la transformación «ventas_por_producto»", "el pedido" );
      contiene( copiado[ 0 ], "- Regla: Ruta absoluta (absolute_path)", "la regla" );
      contiene( copiado[ 0 ], "- Key: absolute_path:Leer ventas", "la key" );
      contiene( copiado[ 0 ], "espera a que te lo confirme", "pide confirmación" );

      // aplicar este arreglo: solo esa key, con el valor elegido
      clic( d.ventana.aplicar );
      esperarUi( () -> !d.ocupado && s.revisiones.get() == 2, "aplicar y revisar otra vez" );
      igual( 1, s.arreglos.get(), "esta vez de verdad" );
      igual( Collections.singletonList( "absolute_path:Leer ventas" ), s.keysAplicadas, "solo esa key" );
      igual( "${DIR}/ventas.csv", s.valoresAplicados.get( "absolute_path:Leer ventas" ), "con el valor elegido" );
      ui( () -> {
        contiene( d.ventana.estado.getText(), "arreglos aplicados", "el resultado en la ventana" );
        return null;
      } );

      // un hallazgo sin arreglo: los pasos a mano y Claude
      ui( () -> {
        d.tabla.setSelection( 2 );
        d.abrirDetalle();
        return null;
      } );
      ui( () -> {
        String t = d.ventana.cuerpo.getText();
        contiene( t, "Este arreglo necesita un valor", "pide el valor" );
        falso( d.ventana.aplicar.getEnabled(), "sin valor no se aplica" );
        return null;
      } );
    } finally {
      IdcDetalle.portapapeles = PORTAPAPELES;
      cerrarShell( d.shell );
    }
  }

  /** Lo que copia al portapapeles de verdad (las pruebas lo cambian y lo devuelven). */
  private static final java.util.function.BiConsumer<Display, String> PORTAPAPELES = IdcDetalle.portapapeles;

  // ================================================================ barra y menú

  /** El botón va justo después del selector de perspectivas, una sola vez; los MenuItem salen del modelo. */
  public void pruebaD_BotonYMenu() throws Exception {
    ui( () -> {
      Shell s = new Shell( host, SWT.NO_TRIM | SWT.TOOL );
      try {
        s.setLayout( new FillLayout() );
        ToolBar tb = new ToolBar( s, SWT.FLAT | SWT.RIGHT );
        new ToolItem( tb, SWT.DROP_DOWN ).setToolTipText( "Nuevo" ); // el «nuevo archivo», también DROP_DOWN
        new ToolItem( tb, SWT.PUSH ).setToolTipText( "Abrir" );
        new ToolItem( tb, SWT.SEPARATOR );
        ToolItem sel = new ToolItem( tb, SWT.DROP_DOWN );
        sel.setToolTipText( "Perspectivas de mentira" );
        new ToolItem( tb, SWT.PUSH ).setToolTipText( "Otro" );
        igual( 4, IdcMenu.posicion( tb.getItems(), "Perspectivas de mentira" ), "después del selector" );
        igual( 5, IdcMenu.posicion( tb.getItems(), "no existe" ), "sin selector: al final" );
        Image img = new Image( display, new ImageData( new java.io.ByteArrayInputStream( IdcMenu.pngBarra() ) ) );
        igual( 16, img.getBounds().height, "16 px de alto, como los demás iconos de la barra" );
        igual( 16, img.getBounds().width, "16 px de ancho (la barra usa un solo tamaño de imagen)" );
        int[] o = IdcMenu.origenBarra();
        // todas las celdas opacas y con contraste sobre fondo claro (#f0f0f0) y oscuro (#2b2b2b)
        ImageData id = img.getImageData();
        int celdas = 0;
        for ( int f = 0; f < 3; f++ ) {
          for ( int c = 0; c < 4; c++ ) {
            int x = o[ 0 ] + c * 4 + 1;
            int y = o[ 1 ] + f * 4 + 1;
            igual( 255, id.getAlpha( x, y ), "celda " + f + "," + c + " opaca" );
            org.eclipse.swt.graphics.RGB rgb = id.palette.getRGB( id.getPixel( x, y ) );
            double l = luminancia( rgb.red, rgb.green, rgb.blue );
            double claro = ( luminancia( 0xf0, 0xf0, 0xf0 ) + 0.05 ) / ( l + 0.05 );
            double oscuro = ( l + 0.05 ) / ( luminancia( 0x2b, 0x2b, 0x2b ) + 0.05 );
            cierto( claro >= 3 && oscuro >= 3, "celda " + f + "," + c + " con contraste 3:1 en fondo claro ("
              + claro + ") y oscuro (" + oscuro + "): " + rgb );
            celdas++;
          }
        }
        igual( 12, celdas, "12 celdas" );
        igual( 0, id.getAlpha( o[ 0 ] + 3, o[ 1 ] + 1 ), "el hueco entre celdas es transparente" );
        guardar( img, "icono-barra.png" );
        ToolItem it = IdcMenu.agregarBoton( tb, r, img, "Perspectivas de mentira" );
        igual( 4, tb.indexOf( it ), "IDC justo a la derecha del selector" );
        igual( "IDC", it.getText(), "texto" );
        cierto( ( it.getStyle() & SWT.DROP_DOWN ) != 0, "desplegable" );
        contiene( it.getToolTipText(), "Pentaho Live", "tooltip" );
        igual( null, IdcMenu.agregarBoton( tb, r, img, "Perspectivas de mentira" ), "una sola vez" );
        falso( IdcMenu.esSelector( it, it.getToolTipText() ), "el IDC no es el selector" );

        // los MenuItem del modelo
        IdcMenu.Contexto c = new IdcMenu.Contexto();
        c.documento = IdcMenu.JOB;
        c.activa = "b";
        c.perspectivas.add( new String[] { "a", "Data Integration" } );
        c.perspectivas.add( new String[] { "b", "Informes" } );
        Menu m = new Menu( s, SWT.POP_UP );
        final List<String> elegidos = new ArrayList<String>();
        IdcMenu.llenar( m, IdcMenu.modelo( r, c ), elegidos::add );
        MenuItem[] items = m.getItems();
        igual( 14, items.length, "9 acciones (0.7.0: Comparar…, Pruebas…, Diseño…, Hoja de mapeo… y Preparar "
          + "proyecto…), 2 separadores, 2 perspectivas y acerca" );
        igual( "Revisar calidad…", items[ 0 ].getText(), "primera" );
        falso( items[ 1 ].getEnabled(), "robustecer con un job" );
        igual( "Comparar…", items[ 3 ].getText(), "comparar" );
        falso( items[ 4 ].getEnabled(), "pruebas con un job" );
        falso( items[ 7 ].getEnabled(), "hoja de mapeo con un job" );
        igual( "Preparar proyecto…", items[ 8 ].getText(), "preparar proyecto" );
        cierto( ( items[ 9 ].getStyle() & SWT.SEPARATOR ) != 0, "separador" );
        cierto( ( items[ 11 ].getStyle() & SWT.RADIO ) != 0, "perspectiva como radio" );
        cierto( items[ 11 ].getSelection(), "Informes marcada" );
        items[ 0 ].notifyListeners( SWT.Selection, new Event() );
        items[ 11 ].notifyListeners( SWT.Selection, new Event() );
        items[ 13 ].notifyListeners( SWT.Selection, new Event() );
        igual( Arrays.asList( "quality", "perspective:b", "about" ), elegidos, "cada entrada su id" );
        m.dispose();
        img.dispose();
      } finally {
        s.dispose();
      }
      return null;
    } );
  }

  /** «Acerca de»: versión y enlace; se cierra con su botón. */
  public void pruebaE_Acerca() throws Exception {
    final Shell s = ui( () -> {
      Shell a = IdcMenu.crearAcerca( host, r );
      mostrar( a );
      return a;
    } );
    try {
      ui( () -> {
        igual( "Acerca de Pentaho Live", s.getText(), "título" );
        StringBuilder t = new StringBuilder();
        for ( Control c : s.getChildren() ) {
          if ( c instanceof org.eclipse.swt.widgets.Label ) {
            t.append( ( (org.eclipse.swt.widgets.Label) c ).getText() ).append( '\n' );
          } else if ( c instanceof org.eclipse.swt.widgets.Link ) {
            t.append( ( (org.eclipse.swt.widgets.Link) c ).getText() ).append( '\n' );
          }
        }
        contiene( t.toString(), "Pentaho Live " + McpLifecycleListener.VERSION, "versión" );
        contiene( t.toString(), "Iván Castaño · IDC Studios · Licencia MIT", "autor" );
        contiene( t.toString(), "https://github.com/IvI2511/pentaho-mcp", "enlace al repositorio" );
        return null;
      } );
      pantalla( s, "acerca.png" );
    } finally {
      cerrarShell( s );
    }
  }

  // ================================================================ servicios falsos

  static ObjectNode hallazgo( String sev, String regla, String step, String msg, String fix, ObjectNode arreglo ) {
    ObjectNode h = McpHttpServer.JSON.createObjectNode();
    h.put( "key", regla + ":" + step ).put( "rule", regla ).put( "severity", sev ).put( "category", "x" )
      .put( "step", step ).put( "message", msg ).put( "how_to_fix", fix );
    if ( arreglo != null ) {
      h.set( "fix", arreglo );
    }
    return h;
  }

  static final class CalidadFalsa implements IdcCalidad.Servicio {
    final AtomicInteger revisiones = new AtomicInteger();
    final AtomicInteger arreglos = new AtomicInteger();
    final AtomicInteger cortada = new AtomicInteger();
    final List<Boolean> profunda = Collections.synchronizedList( new ArrayList<Boolean>() );
    final List<Boolean> robustez = Collections.synchronizedList( new ArrayList<Boolean>() );
    final List<String> seleccionados = new ArrayList<String>();
    volatile List<String> silenciado;
    volatile CountDownLatch bloquear;
    volatile boolean conRobustez;
    volatile boolean enEspera;
    volatile int score = 62;
    volatile int sondas = 2;
    volatile List<String> keys;
    volatile Map<String, String> valores = new LinkedHashMap<String, String>();
    volatile boolean ensayo;
    volatile boolean continuar;
    volatile List<String> keysAplicadas;
    volatile Map<String, String> valoresAplicados;
    volatile String pares;

    @Override
    public String nombre() {
      return "ventas_por_producto";
    }

    @Override
    public ObjectNode revisar( boolean prof, boolean rob, Consumer<Runnable> alCancelar ) throws Exception {
      profunda.add( prof );
      robustez.add( rob );
      final CountDownLatch b = bloquear;
      if ( b != null ) {
        alCancelar.accept( () -> {
          cortada.incrementAndGet();
          b.countDown();
        } );
        enEspera = true;
        b.await( 30, TimeUnit.SECONDS );
      }
      revisiones.incrementAndGet();
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      o.put( "document", "T1" ).put( "score", score ).put( "grade", score >= 75 ? "B" : "C" );
      o.putObject( "counts" ).put( "error", 1 ).put( "warning", 1 ).put( "info", 1 );
      ArrayNode f = o.putArray( "findings" );
      // cambia una ruta: confirm, con la ruta propuesta (se puede editar en el detalle)
      ObjectNode ruta = McpHttpServer.JSON.createObjectNode().put( "kind", "replace_value" ).put( "auto", true )
        .put( "confirm", true ).put( "preview", "${Internal.Entry.Current.Directory}/datos/ventas.csv" );
      f.add( hallazgo( "error", "absolute_path", "Leer ventas", "Ruta absoluta C:\\datos\\ventas.csv",
        "Usa ${Internal.Entry.Current.Directory}", ruta ) );
      ObjectNode auto = McpHttpServer.JSON.createObjectNode().put( "kind", "rename" ).put( "auto", true )
        .put( "confirm", false );
      ObjectNode g = hallazgo( "warning", "unsorted_input", "Agrupar", "Group by sin ordenar antes",
        "Pon un Sort rows delante", auto );
      g.putObject( "evidence" ).put( "order", "desconocido" );
      f.add( g );
      // auto pero pide un valor: all_auto no lo aplica (LintArreglos), así que no cuenta
      ObjectNode autoConValor = McpHttpServer.JSON.createObjectNode().put( "kind", "rename" ).put( "auto", true )
        .put( "confirm", false ).put( "needs_value", true );
      f.add( hallazgo( "info", "default_step_name", "Dummy (do nothing)", "Nombre por defecto",
        "Dale un nombre que diga qué hace", autoConValor ) );
      // robustez: se arregla con su receta de harden_etl
      ObjectNode rb = hallazgo( "warning", "no_rejects_on_load", "Cargar fecha", "La carga para con una fila mala",
        "harden_etl recipes=[rejects_on_error] steps=[Cargar fecha]: manejo de errores hacia un archivo de rechazos",
        null );
      rb.put( "category", "robustness" );
      rb.putObject( "evidence" ).put( "recipe", "rejects_on_error" ).put( "anchor", "Cargar fecha" );
      f.add( rb );
      o.putArray( "suppressed" ).addObject().put( "rule", "x" );
      o.putArray( "not_evaluated" ).addObject().put( "rule", "unsorted_input" ).put( "step", "Agrupar" )
        .put( "reason", "deep=false" );
      if ( conRobustez ) {
        o.putObject( "robustness" ).put( "score", 40 ).put( "grade", "D" );
      }
      ObjectNode d = o.putObject( "deep" ).put( "ran", prof );
      if ( prof ) {
        d.put( "ms", 1500 ).put( "probes", sondas ).put( "raised", 1 ).put( "lowered", 0 ).put( "not_evaluated", 1 );
        if ( sondas == 0 ) {
          d.put( "reason", "nothing to check" );
        }
      } else {
        d.put( "reason", "deep=false" );
      }
      return o;
    }

    @Override
    public ObjectNode arreglar( Collection<String> k, Map<String, String> v, boolean ens, boolean cont ) {
      keys = new ArrayList<String>( k );
      valores = new LinkedHashMap<String, String>( v );
      ensayo = ens;
      continuar = cont;
      if ( !ens ) {
        arreglos.incrementAndGet();
        keysAplicadas = new ArrayList<String>( k );
        valoresAplicados = new LinkedHashMap<String, String>( v );
      }
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      ArrayNode a = o.putArray( "applied" );
      for ( String x : k ) {
        a.addObject().put( "key", x ).put( "change", x.startsWith( "absolute_path" ) ? "In 'Leer ventas', filename: "
          + "C:\\datos -> " + v.get( x ) : "x" );
      }
      o.putArray( "skipped" );
      o.put( "undo_steps", 1 ).put( "score_before", 62 ).put( "score_after", 70 );
      if ( !ens ) {
        score = 70;
      }
      return o;
    }

    @Override
    public ObjectNode recetas( List<String[]> p, boolean ens ) {
      StringBuilder b = new StringBuilder();
      for ( String[] x : p ) {
        b.append( b.length() == 0 ? "" : ";" ).append( x[ 0 ] ).append( '|' ).append( x[ 1 ] );
      }
      pares = b.toString();
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      if ( ens ) {
        ObjectNode pr = o.putArray( "proposals" ).addObject().put( "key", "harden:rejects_on_error:Cargar fecha" )
          .put( "recipe", "rejects_on_error" ).put( "level", "auto" ).put( "step", "Cargar fecha" );
        pr.putArray( "changes" ).addObject().put( "op", "add_step" );
        pr.put( "diff", "+ Escribir rechazos (Text file output)" );
        return o;
      }
      o.putArray( "applied" ).addObject().put( "key", "harden:rejects_on_error:Cargar fecha" );
      o.putArray( "not_applied" );
      return o;
    }

    @Override
    public ObjectNode silenciar( String regla, String step, String entry, String motivo ) {
      silenciado = Arrays.asList( regla, step, entry, motivo );
      return McpHttpServer.JSON.createObjectNode().put( "changed", true );
    }

    @Override
    public void seleccionar( String step, String entry ) {
      seleccionados.add( step != null ? step : entry );
    }
  }

  static final class RobustezFalsa implements IdcRobustez.Servicio {
    final AtomicInteger planes = new AtomicInteger();
    final List<String> seleccionados = new ArrayList<String>();
    volatile Set<String> confirmar;
    volatile Set<String> excluir;
    volatile boolean bloquear;
    volatile boolean conBaseMysql;
    volatile EstresProcesos.Corte corte;

    static final String NO_CORRE = "la transformación usa bases de datos que no son archivos SQLite/H2 (GYM_DW (MYSQL) "
      + "used by Cargar fecha): stress_test nunca se conecta a ellas";

    @Override
    public String nombre() {
      return "carga_ventas";
    }

    @Override
    public ObjectNode plan() {
      planes.incrementAndGet();
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      o.put( "document", "T1" ).put( "dry_run", true );
      o.putObject( "robustness_before" ).put( "score", 35 ).put( "grade", "D" );
      ArrayNode p = o.putArray( "proposals" );
      ObjectNode a = p.addObject().put( "key", "rejects_on_error:Cargar ventas" ).put( "recipe", "rejects_on_error" )
        .put( "level", "auto" ).put( "semantics_change", false ).put( "step", "Cargar ventas" )
        .put( "why", "Una fila mala aborta la carga" );
      a.putArray( "changes" ).addObject().put( "op", "add_step" );
      a.put( "diff", "+ step Escribir rechazos Cargar ventas" );
      ObjectNode b = p.addObject().put( "key", "required_fields:Leer ventas" ).put( "recipe", "required_fields" )
        .put( "level", "confirm" ).put( "semantics_change", true ).put( "step", "Leer ventas" )
        .put( "why", "Las filas sin producto se cuelan" );
      b.putArray( "changes" ).addObject().put( "op", "add_step" );
      b.put( "diff", "+ step Validar obligatorios" );
      p.addObject().put( "key", "stable_surrogate_keys:Claves" ).put( "recipe", "stable_surrogate_keys" )
        .put( "level", "manual" ).put( "semantics_change", true ).put( "step", "Claves" )
        .put( "why", "Las claves cambian al repetir" ).put( "how_to", "Usa un Combination lookup/update" );
      return o;
    }

    @Override
    public ObjectNode aplicar( Set<String> c, Set<String> e ) {
      confirmar = c;
      excluir = e;
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      o.putArray( "applied" ).addObject().put( "key", "required_fields:Leer ventas" );
      o.putArray( "not_applied" );
      o.putObject( "robustness_before" ).put( "score", 35 ).put( "grade", "D" );
      o.putObject( "robustness_after" ).put( "score", 70 ).put( "grade", "B" );
      return o;
    }

    @Override
    public ObjectNode previa() {
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      o.putArray( "inputs" ).addObject().put( "step", "Leer ventas" ).put( "type", "CsvInput" ).put( "base_rows", 50 )
        .put( "fields", 4 );
      ArrayNode v = o.putArray( "variants" );
      v.add( "combined" ).add( "empty_file" ).add( "header_only" );
      o.putArray( "file_outputs" );
      ArrayNode db = o.putArray( "databases_not_files" );
      if ( conBaseMysql ) {
        db.add( "GYM_DW (MYSQL) used by Cargar fecha" );
        o.put( "will_run", false ).put( "not_run_reason", NO_CORRE );
      } else {
        o.put( "will_run", true );
      }
      return o;
    }

    @Override
    public void seleccionar( String step ) {
      seleccionados.add( step );
    }

    @Override
    public ObjectNode probar( EstresProcesos.Corte c ) throws Exception {
      corte = c;
      if ( conBaseMysql ) {
        ObjectNode o = McpHttpServer.JSON.createObjectNode();
        o.put( "document", "T1" ).put( "ran", false ).put( "verdict", "not_run" ).put( "not_run_reason", NO_CORRE );
        return o;
      }
      if ( bloquear ) {
        long fin = System.currentTimeMillis() + 30000;
        while ( !c.pedido() && System.currentTimeMillis() < fin ) {
          Thread.sleep( 50 );
        }
        throw new McpHttpServer.ToolError( "The stress test was cancelled" );
      }
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      o.put( "document", "T1" ).put( "ran", true ).put( "verdict", "fragile" );
      o.put( "environment_problem", "the database file dw.sqlite does not exist" );
      ArrayNode vs = o.putArray( "variants" );
      ObjectNode v = vs.addObject().put( "id", "combined" );
      ArrayNode k = v.putArray( "checks" );
      k.addObject().put( "id", "no_abort" ).put( "status", "pass" ).put( "message", "finished" );
      k.addObject().put( "id", "no_silent_loss" ).put( "status", "fail" ).put( "message", "3 rows lost in Filtrar" );
      k.addObject().put( "id", "rejects_have_reason" ).put( "status", "pass" );
      vs.addObject().put( "id", "empty_file" ).put( "status", "not_evaluated" ).put( "message", "no rows" );
      o.putObject( "summary" ).put( "passed", 2 ).put( "warned", 0 ).put( "failed", 1 );
      o.put( "next", "harden_etl recipes=[required_fields] (see the plan with dry_run=true), then run stress_test "
        + "again" );
      o.putArray( "notes" ).add( "Not a security sandbox: the transformation's own JavaScript/Java code runs in the "
        + "child JVM." );
      return o;
    }
  }

  // ================================================================ utilidades

  /** Fuera de la pantalla y sin activarla (nunca open(), forceActive ni ON_TOP). (UI) */
  private static void mostrar( Shell s ) {
    s.setLocation( PruebaPanelSwt.X, PruebaPanelSwt.Y );
    s.setVisible( true );
  }

  /** Luminancia relativa (WCAG 2) de un color sRGB. */
  private static double luminancia( int r, int g, int b ) {
    double[] c = { r / 255.0, g / 255.0, b / 255.0 };
    for ( int i = 0; i < 3; i++ ) {
      c[ i ] = c[ i ] <= 0.03928 ? c[ i ] / 12.92 : Math.pow( ( c[ i ] + 0.055 ) / 1.055, 2.4 );
    }
    return 0.2126 * c[ 0 ] + 0.7152 * c[ 1 ] + 0.0722 * c[ 2 ];
  }

  /** Guarda una imagen como PNG en las capturas. */
  private void guardar( Image img, String nombre ) {
    ImageLoader il = new ImageLoader();
    il.data = new ImageData[] { img.getImageData() };
    File f = new File( capturas, nombre );
    il.save( f.getPath(), SWT.IMAGE_PNG );
    System.out.println( "  captura: " + f );
  }

  private void clic( final Button b ) throws Exception {
    ui( () -> {
      cierto( b.getEnabled(), "'" + b.getText() + "' habilitado para el clic" );
      b.notifyListeners( SWT.Selection, new Event() );
      return null;
    } );
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
