package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * «Crear prueba desde la vista previa…» (0.7.0, P1): una ventana no modal sobre «Pruebas…» que arma los argumentos de
 * {@code test_create} (modo snapshot): el nombre, las salidas que se congelan como doradas (vienen marcados los steps
 * elegidos en el lienzo; si no hay ninguno, los finales), las entradas que pasan a datasets fijos (las fuentes) y si
 * se saltan los steps que escriben. Corre en segundo plano con la misma tarea que la herramienta MCP y, al terminar,
 * la lista de pruebas la muestra elegida. (UI)
 *
 * @dueño P1 (0.7.0)
 */
final class PruebasCrear {

  final PruebasDialogo padre;
  final Rotulos r;
  final Shell shell;
  final Text nombre;
  final Text descripcion;
  final Table salidas;
  final Table entradas;
  final Button saltar;
  final Label estado;
  final ProgressBar barra;
  final Button crear;
  final Button cancelar;
  volatile boolean ocupado;
  private PruebasProcesos.Corrida corrida;

  PruebasCrear( PruebasDialogo padre ) {
    this.padre = padre;
    this.r = padre.r;
    shell = new Shell( padre.shell, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MODELESS );
    shell.setText( r.f( "c_title", padre.servicio.nombre() ) );
    GridLayout gl = new GridLayout( 2, false );
    gl.marginWidth = 12;
    gl.marginHeight = 10;
    gl.verticalSpacing = 8;
    shell.setLayout( gl );

    new Label( shell, SWT.NONE ).setText( r.s( "c_name" ) );
    nombre = new Text( shell, SWT.BORDER );
    nombre.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    new Label( shell, SWT.NONE ).setText( r.s( "c_desc" ) );
    descripcion = new Text( shell, SWT.BORDER );
    descripcion.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

    Label ls = new Label( shell, SWT.WRAP );
    ls.setText( r.s( "c_outputs" ) );
    ls.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );
    salidas = tablaDeSteps();
    Label le = new Label( shell, SWT.WRAP );
    le.setText( r.s( "c_inputs" ) );
    le.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );
    entradas = tablaDeSteps();

    saltar = new Button( shell, SWT.CHECK );
    saltar.setText( r.s( "c_skip" ) );
    saltar.setToolTipText( r.s( "c_skip_tip" ) );
    saltar.setSelection( true );
    saltar.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );

    estado = new Label( shell, SWT.WRAP );
    GridData ge = new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 );
    ge.widthHint = 520;
    estado.setLayoutData( ge );

    Composite bo = new Composite( shell, SWT.NONE );
    bo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );
    GridLayout gb = new GridLayout( 3, false );
    gb.marginWidth = 0;
    bo.setLayout( gb );
    barra = new ProgressBar( bo, SWT.HORIZONTAL | SWT.INDETERMINATE );
    GridData gpb = new GridData( SWT.LEFT, SWT.CENTER, true, false );
    gpb.widthHint = 150;
    barra.setLayoutData( gpb );
    barra.setVisible( false );
    crear = new Button( bo, SWT.PUSH );
    crear.setText( r.s( "c_create" ) );
    crear.addListener( SWT.Selection, e -> crear() );
    cancelar = new Button( bo, SWT.PUSH );
    cancelar.setText( r.s( "c_cancel" ) );
    cancelar.addListener( SWT.Selection, e -> cancelar() );
    shell.setDefaultButton( crear );
    shell.addListener( SWT.Close, e -> {
      if ( corrida != null && !corrida.terminada() ) {
        corrida.cancelar();
      }
    } );

    llenar();
    shell.setSize( 600, 560 );
  }

  private Table tablaDeSteps() {
    Table t = new Table( shell, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.V_SCROLL );
    t.setHeaderVisible( false );
    GridData gd = new GridData( SWT.FILL, SWT.FILL, true, true, 2, 1 );
    gd.heightHint = 120;
    t.setLayoutData( gd );
    TableColumn c1 = new TableColumn( t, SWT.LEFT );
    c1.setWidth( 300 );
    TableColumn c2 = new TableColumn( t, SWT.LEFT );
    c2.setWidth( 200 );
    return t;
  }

  /** Los steps, con las marcas por defecto: salidas = elegidos en el lienzo (o los finales); entradas = fuentes. */
  void llenar() {
    List<String[]> steps = padre.servicio.steps();
    boolean hayElegidos = false;
    for ( String[] s : steps ) {
      hayElegidos |= Arrays.asList( s[ 2 ].split( "," ) ).contains( "sel" );
    }
    for ( String[] s : steps ) {
      List<String> m = Arrays.asList( s[ 2 ].split( "," ) );
      TableItem a = new TableItem( salidas, SWT.NONE );
      a.setText( new String[] { s[ 0 ], s[ 1 ] } );
      a.setChecked( hayElegidos ? m.contains( "sel" ) : m.contains( "end" ) );
      TableItem b = new TableItem( entradas, SWT.NONE );
      b.setText( new String[] { s[ 0 ], s[ 1 ] } );
      b.setChecked( m.contains( "src" ) && !( hayElegidos && m.contains( "sel" ) ) );
    }
    String base = DefPrueba.archivoDe( padre.servicio.nombre() );
    String n = base;
    for ( int i = 2; existe( n ); i++ ) {
      n = base + "_" + i;
    }
    nombre.setText( n );
    nombre.selectAll();
  }

  private boolean existe( String n ) {
    for ( PruebasDialogo.Fila f : padre.filas ) {
      if ( f.nombre.toLowerCase( Locale.ROOT ).equals( n.toLowerCase( Locale.ROOT ) ) ) {
        return true;
      }
    }
    return false;
  }

  void abrir() {
    IdcMenu.abrirShell( shell, padre.shell );
  }

  static List<String> marcados( Table t ) {
    List<String> l = new ArrayList<String>();
    for ( TableItem it : t.getItems() ) {
      if ( it.getChecked() ) {
        l.add( it.getText( 0 ) );
      }
    }
    return l;
  }

  /** Los argumentos de test_create (sin el documento: lo pone el servicio). */
  ObjectNode argumentos() {
    ObjectNode a = Tools.obj();
    a.put( "name", nombre.getText().trim() );
    if ( !descripcion.getText().trim().isEmpty() ) {
      a.put( "description", descripcion.getText().trim() );
    }
    ArrayNode s = a.putArray( "output_steps" );
    for ( String x : marcados( salidas ) ) {
      s.add( x );
    }
    ArrayNode e = a.putArray( "input_steps" );
    for ( String x : marcados( entradas ) ) {
      if ( !marcados( salidas ).contains( x ) ) {
        e.add( x );
      }
    }
    if ( !saltar.getSelection() ) {
      a.putArray( "tweaks" ); // sin saltar nada
    }
    return a;
  }

  /** Valida y crea en segundo plano. (UI) */
  void crear() {
    if ( ocupado ) {
      return;
    }
    if ( !DefPrueba.nombreValido( nombre.getText().trim() ) ) {
      estado.setText( r.s( "c_bad_name" ) );
      return;
    }
    if ( marcados( salidas ).isEmpty() ) {
      estado.setText( r.s( "c_need_output" ) );
      return;
    }
    if ( existe( nombre.getText().trim() ) ) {
      estado.setText( r.f( "c_exists", nombre.getText().trim() ) );
      return;
    }
    final ObjectNode a = argumentos();
    ocupado = true;
    estado.setText( r.s( "c_working" ) );
    barra.setVisible( true );
    crear.setEnabled( false );
    shell.layout( true, true );
    final Display d = shell.getDisplay();
    try {
      corrida = padre.servicio.crear( a, new PruebasProcesos.Oyente() {
        @Override
        public void empezada( PruebasProcesos.Tarea t ) {
          // el estado ya dice que corre
        }

        @Override
        public void terminada( PruebasProcesos.Tarea t, final ResultadoPrueba res ) {
          d.asyncExec( () -> PruebasCrear.this.terminada( res ) );
        }
      } );
    } catch ( Throwable e ) {
      libre( r.f( "c_failed", IdcMenu.mensaje( e ) ) );
    }
  }

  void terminada( ResultadoPrueba res ) {
    corrida = null;
    if ( shell.isDisposed() ) {
      return;
    }
    if ( res == null || ResultadoPrueba.ERROR.equals( res.estado ) && res.creada == null
      || ResultadoPrueba.CANCELADA.equals( res.estado ) ) {
      libre( res == null ? r.f( "c_failed", "?" ) : ResultadoPrueba.CANCELADA.equals( res.estado ) ? r.s(
        "c_cancelled" ) : r.f( "c_failed", res.mensaje ) );
      return;
    }
    if ( !padre.shell.isDisposed() ) {
      padre.creada( res );
      String txt = r.f( "c_done", res.prueba, res.creada == null ? 0 : res.creada.datasets.size(), res.creada == null
        ? 0 : res.creada.dorados.size() );
      if ( res.creada != null && !res.creada.avisos.isEmpty() ) {
        txt += "  " + r.f( "c_warnings", String.join( " · ", res.creada.avisos ) );
      }
      padre.estado.setText( txt );
      padre.shell.layout( true, true );
    }
    shell.close();
  }

  void cancelar() {
    if ( corrida != null && !corrida.terminada() ) {
      corrida.cancelar();
      estado.setText( r.s( "t_cancelling" ) );
      return;
    }
    shell.close();
  }

  private void libre( String texto ) {
    if ( shell.isDisposed() ) {
      return;
    }
    ocupado = false;
    estado.setText( texto );
    barra.setVisible( false );
    crear.setEnabled( true );
    shell.layout( true, true );
  }
}
