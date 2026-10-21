package io.github.ivi2511.pentahomcp.informes.ui;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Disponibilidad;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Grupo «Secciones» (diseno §5.2): un check por seccion en el orden del informe, habilitado segun
 * {@code disponibles(...)} con el motivo en el tooltip, y los controles propios de algunas: step destino
 * del linaje, step y filas del perfil, ultima corrida y «Medir…», «consultar la base», la revision profunda
 * de la calidad y las conclusiones.
 * Flow y job_flow ocupan el mismo renglon: se ve el que corresponde al tipo de documento. (UI)
 *
 * @dueño WP1
 */
final class GrupoSecciones {

  /** Sangria de los controles propios de cada seccion (debajo de su check). */
  static final int SANGRIA = 20;

  final Group grupo;
  final Map<String, Button> checks = new LinkedHashMap<String, Button>();
  final Combo linaje;
  final Combo perfilStep;
  final Spinner perfilFilas;
  final Label ultimaCorrida;
  final Button medir;
  final Button consultarBase;
  /** quality: tambien el modo profundo del linter (lee las fuentes con previews de solo lectura). */
  final Button calidadProfunda;
  final Text conclusiones;

  private final PanelInformes panel;
  private final Rotulos r;
  private final Map<String, Control[]> filas = new HashMap<String, Control[]>();
  private String docActual;

  GrupoSecciones( Composite padre, final PanelInformes panel ) {
    this.panel = panel;
    this.r = panel.r;
    grupo = new Group( padre, SWT.NONE );
    grupo.setText( PanelInformes.esc( r.s( "sections" ) ) );
    GridLayout gl = new GridLayout( 1, false );
    gl.verticalSpacing = 2;
    grupo.setLayout( gl );
    grupo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

    // un renglon por seccion; los controles propios van debajo, con sangria (la columna es angosta)
    Combo cLinaje = null;
    Combo cPerfil = null;
    Spinner sFilas = null;
    Label lUltima = null;
    Button bMedir = null;
    Button bBase = null;
    Button bProfunda = null;
    for ( final String id : SeccionId.ORDEN ) {
      final Button b = new Button( grupo, SWT.CHECK );
      b.setText( PanelInformes.esc( SeccionId.titulo( id, panel.idiomaUi ) ) );
      b.setData( PanelInformes.CLAVE, "sec:" + id );
      b.setEnabled( false );
      b.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
      checks.put( id, b );
      Composite extra = null;
      if ( SeccionId.LINAJE.equals( id ) ) {
        extra = sangria( grupo, 1 );
        cLinaje = new Combo( extra, SWT.READ_ONLY | SWT.DROP_DOWN );
        cLinaje.setToolTipText( r.s( "lineage_target_tip" ) );
        cLinaje.setData( PanelInformes.CLAVE, "linaje_step" );
        cLinaje.setVisibleItemCount( 15 );
        cLinaje.setLayoutData( ancho( 120 ) );
      } else if ( SeccionId.DESTINO.equals( id ) ) {
        extra = sangria( grupo, 1 );
        bBase = new Button( extra, SWT.CHECK );
        bBase.setText( PanelInformes.esc( r.s( "query_db" ) ) );
        bBase.setToolTipText( r.s( "query_db_tip" ) );
        bBase.setData( PanelInformes.CLAVE, "consultar_base" );
        final Button base = bBase;
        base.addListener( SWT.Selection, e -> {
          if ( base.getSelection() && !panel.confirmarConsultaBase() ) {
            base.setSelection( false );
          }
        } );
      } else if ( SeccionId.CALIDAD.equals( id ) ) {
        extra = sangria( grupo, 1 );
        bProfunda = new Button( extra, SWT.CHECK );
        bProfunda.setText( PanelInformes.esc( r.s( "quality_deep" ) ) );
        bProfunda.setToolTipText( r.s( "quality_deep_tip" ) );
        bProfunda.setData( PanelInformes.CLAVE, "calidad_profunda" );
        final Button profunda = bProfunda;
        profunda.addListener( SWT.Selection, e -> {
          if ( profunda.getSelection() ) {
            marcar( SeccionId.CALIDAD, true ); // la revision profunda es de la seccion de calidad
          }
        } );
      } else if ( SeccionId.PERFIL.equals( id ) ) {
        extra = sangria( grupo, 2 );
        cPerfil = new Combo( extra, SWT.READ_ONLY | SWT.DROP_DOWN );
        cPerfil.setToolTipText( r.s( "profile_step_tip" ) );
        cPerfil.setData( PanelInformes.CLAVE, "perfil_step" );
        cPerfil.setVisibleItemCount( 15 );
        cPerfil.setLayoutData( ancho( 100 ) );
        sFilas = new Spinner( extra, SWT.BORDER );
        sFilas.setValues( 5000, 100, 1000000, 0, 1000, 10000 );
        sFilas.setToolTipText( r.s( "profile_rows_tip" ) );
        sFilas.setData( PanelInformes.CLAVE, "perfil_filas" );
        final Combo perfil = cPerfil;
        b.addListener( SWT.Selection, e -> {
          if ( b.getSelection() && perfil.getSelectionIndex() < 0 && perfil.getItemCount() > 0 ) {
            perfil.select( perfil.getItemCount() - 1 ); // el ultimo step suele ser la salida
          }
          if ( b.getSelection() ) {
            panel.perfilMarcado(); // §2.4.3: avisa que muestra valores reales
          }
        } );
      } else if ( SeccionId.METRICAS.equals( id ) ) {
        extra = sangria( grupo, 2 );
        lUltima = new Label( extra, SWT.NONE );
        lUltima.setText( PanelInformes.esc( r.s( "metrics_none" ) ) );
        lUltima.setData( PanelInformes.CLAVE, "ultima_corrida" );
        lUltima.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
        bMedir = new Button( extra, SWT.PUSH );
        bMedir.setText( PanelInformes.esc( r.s( "measure" ) ) );
        bMedir.setToolTipText( r.s( "measure_tip" ) );
        bMedir.setData( PanelInformes.CLAVE, "medir" );
        bMedir.setEnabled( false );
        bMedir.addListener( SWT.Selection, e -> panel.medir() );
      }
      filas.put( id, extra == null ? new Control[] { b } : new Control[] { b, extra } );
    }
    linaje = cLinaje;
    perfilStep = cPerfil;
    perfilFilas = sFilas;
    ultimaCorrida = lUltima;
    medir = bMedir;
    consultarBase = bBase;
    calidadProfunda = bProfunda;

    conclusiones = new Text( grupo, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL );
    GridData gc = new GridData( SWT.FILL, SWT.TOP, true, false );
    gc.heightHint = 48;
    gc.widthHint = 160;
    gc.horizontalIndent = SANGRIA;
    conclusiones.setLayoutData( gc );
    conclusiones.setToolTipText( r.s( "conclusions_tip" ) );
    conclusiones.setData( PanelInformes.CLAVE, "conclusiones" );
    conclusiones.setTextLimit( 2000 );
    conclusiones.addListener( SWT.Modify, e -> {
      Button n = checks.get( SeccionId.NARRATIVA );
      if ( n.isEnabled() ) {
        n.setSelection( !conclusiones.getText().trim().isEmpty() );
      }
    } );
    mostrarFila( SeccionId.FLUJO_JOB, false );
  }

  /**
   * Habilita los checks segun la disponibilidad. Con un documento nuevo pone los defaults (los del tema o
   * los de cada seccion) y llena los combos de steps; con el mismo, respeta lo que eligio el usuario.
   */
  void actualizar( String docId, Captura c, List<Disponibilidad> disp, Tema tema ) {
    boolean nuevo = docId == null ? docActual != null : !docId.equals( docActual );
    docActual = docId;
    boolean esJob = c != null && c.esJob();
    mostrarFila( SeccionId.FLUJO, !esJob );
    mostrarFila( SeccionId.FLUJO_JOB, esJob );

    Map<String, Disponibilidad> porId = new HashMap<String, Disponibilidad>();
    if ( disp != null ) {
      for ( Disponibilidad d : disp ) {
        if ( d != null && d.id != null ) {
          porId.put( d.id, d );
        }
      }
    }
    for ( Map.Entry<String, Button> e : checks.entrySet() ) {
      String id = e.getKey();
      Button b = e.getValue();
      Disponibilidad d = porId.get( id );
      boolean aplica = d == null ? c != null && aplicaPorTipo( id, esJob ) : d.aplica;
      b.setEnabled( aplica );
      if ( !aplica ) {
        b.setSelection( false );
        b.setToolTipText( d != null && d.motivo != null ? d.motivo : r.s( "not_applicable" ) );
      } else {
        b.setToolTipText( d == null ? null : costo( d.costo ) );
        if ( nuevo ) {
          b.setSelection( porDefecto( id, d, tema ) );
        }
      }
    }

    if ( nuevo ) {
      llenarSteps( c );
      if ( tema != null && tema.perfil != null && tema.perfil.filas > 0 ) {
        perfilFilas.setSelection( tema.perfil.filas );
      }
      consultarBase.setSelection( false );
      calidadProfunda.setSelection( false ); // lee datos: siempre a pedido
    }
    // los controles propios de una seccion que no aplica no ocupan lugar (Medir queda siempre)
    for ( String id : new String[] { SeccionId.LINAJE, SeccionId.DESTINO, SeccionId.PERFIL, SeccionId.CALIDAD } ) {
      mostrarExtra( id, checks.get( id ).isEnabled() );
    }
    linaje.setEnabled( checks.get( SeccionId.LINAJE ).isEnabled() && linaje.getItemCount() > 1 );
    boolean perfil = checks.get( SeccionId.PERFIL ).isEnabled();
    perfilStep.setEnabled( perfil && perfilStep.getItemCount() > 0 );
    perfilFilas.setEnabled( perfil );
    consultarBase.setEnabled( checks.get( SeccionId.DESTINO ).isEnabled() );
    if ( !consultarBase.isEnabled() ) {
      consultarBase.setSelection( false );
    }
    // el modo profundo solo revisa transformaciones (orden de las filas y simbolo decimal)
    calidadProfunda.setEnabled( checks.get( SeccionId.CALIDAD ).isEnabled() && !esJob );
    if ( !calidadProfunda.isEnabled() ) {
      calidadProfunda.setSelection( false );
    }
    medir.setEnabled( c != null && c.copia != null );
    ultimaCorrida.setText( PanelInformes.esc( ultima( c ) ) );
    ultimaCorrida.getParent().layout( true );
  }

  /** show_report: marca exactamente las secciones del pedido que aplican (null = defaults ya puestos). */
  void aplicar( Pedido p, Tema tema ) {
    if ( p == null ) {
      return;
    }
    if ( p.secciones != null ) {
      for ( Map.Entry<String, Button> e : checks.entrySet() ) {
        Button b = e.getValue();
        b.setSelection( b.isEnabled() && p.secciones.contains( e.getKey() ) );
      }
    }
    if ( p.linajeStep != null ) {
      int i = linaje.indexOf( p.linajeStep );
      if ( i > 0 ) {
        linaje.select( i );
      }
    }
    if ( p.perfilStep != null ) {
      int i = perfilStep.indexOf( p.perfilStep );
      if ( i >= 0 ) {
        perfilStep.select( i );
      }
    }
    if ( p.perfilFilas > 0 ) {
      perfilFilas.setSelection( p.perfilFilas );
    }
    if ( p.destinoConsultarBase && consultarBase.isEnabled() ) {
      consultarBase.setSelection( true ); // lo pidio el usuario por MCP: no se vuelve a preguntar
    }
    if ( calidadProfunda.isEnabled() ) {
      calidadProfunda.setSelection( p.calidadProfunda );
      if ( p.calidadProfunda ) {
        marcar( SeccionId.CALIDAD, true ); // setSelection no dispara el listener que la marca
      }
    }
  }

  /** Ids marcados, habilitados y visibles, en el orden del informe. */
  List<String> seleccionadas() {
    List<String> out = new ArrayList<String>();
    for ( Map.Entry<String, Button> e : checks.entrySet() ) {
      Button b = e.getValue();
      if ( b.getSelection() && b.isEnabled() && b.getVisible() ) {
        out.add( e.getKey() );
      }
    }
    return out;
  }

  void marcar( String id, boolean on ) {
    Button b = checks.get( id );
    if ( b != null && b.isEnabled() ) {
      b.setSelection( on );
    }
  }

  /** null = los steps finales. */
  String linajeStep() {
    int i = linaje.getSelectionIndex();
    return i <= 0 ? null : linaje.getItem( i );
  }

  /** null si no se eligio step. */
  String perfilStep() {
    int i = perfilStep.getSelectionIndex();
    return i < 0 ? null : perfilStep.getItem( i );
  }

  int perfilFilas() {
    return perfilFilas.getSelection();
  }

  boolean consultarBase() {
    return consultarBase.getSelection() && consultarBase.isEnabled();
  }

  /** La revision profunda, solo si quality va marcada. */
  boolean calidadProfunda() {
    Button q = checks.get( SeccionId.CALIDAD );
    return calidadProfunda.getSelection() && calidadProfunda.isEnabled() && q.getSelection() && q.isEnabled();
  }

  String conclusiones() {
    return conclusiones.getText().trim();
  }

  void conclusiones( String texto ) {
    conclusiones.setText( texto == null ? "" : texto );
  }

  // ---------------------------------------------------------------- ayudas

  private boolean porDefecto( String id, Disponibilidad d, Tema tema ) {
    if ( SeccionId.NARRATIVA.equals( id ) ) {
      return !conclusiones.getText().trim().isEmpty();
    }
    if ( SeccionId.PERFIL.equals( id ) ) {
      return false; // ejecuta la transformacion: siempre a pedido
    }
    if ( tema != null && tema.secciones != null ) {
      return tema.secciones.contains( id );
    }
    return d != null && d.porDefecto;
  }

  /** Sin disponibilidad del servicio: lo minimo por tipo de documento. */
  private static boolean aplicaPorTipo( String id, boolean esJob ) {
    if ( SeccionId.FLUJO_JOB.equals( id ) ) {
      return esJob;
    }
    if ( SeccionId.FLUJO.equals( id ) || SeccionId.LINAJE.equals( id ) || SeccionId.DICCIONARIO.equals( id )
      || SeccionId.PERFIL.equals( id ) || SeccionId.DESTINO.equals( id ) ) {
      return !esJob;
    }
    return true;
  }

  private String costo( String c ) {
    if ( Disponibilidad.COSTO_CAMPOS.equals( c ) ) {
      return r.s( "cost_fields" );
    }
    if ( Disponibilidad.COSTO_BASE.equals( c ) ) {
      return r.s( "cost_database" );
    }
    if ( Disponibilidad.COSTO_EJECUTA.equals( c ) ) {
      return r.s( "cost_runs" );
    }
    if ( Disponibilidad.COSTO_UI.equals( c ) ) {
      return r.s( "cost_ui" );
    }
    return null;
  }

  private void llenarSteps( Captura c ) {
    String antesLinaje = linajeStep();
    linaje.removeAll();
    perfilStep.removeAll();
    linaje.add( r.s( "lineage_final" ) );
    if ( c != null && c.copia instanceof TransMeta ) {
      for ( String s : ( (TransMeta) c.copia ).getStepNames() ) {
        linaje.add( s );
        perfilStep.add( s );
      }
    }
    int i = antesLinaje == null ? -1 : linaje.indexOf( antesLinaje );
    linaje.select( i > 0 ? i : 0 );
  }

  private String ultima( Captura c ) {
    Date fin = null;
    try {
      if ( c != null && c.ultimaTrans != null ) {
        fin = c.ultimaTrans.getEndDate() != null ? c.ultimaTrans.getEndDate() : c.ultimaTrans.getStartDate();
      } else if ( c != null && c.ultimoJob != null ) {
        fin = c.ultimoJob.getEndDate() != null ? c.ultimoJob.getEndDate() : c.ultimoJob.getStartDate();
      }
    } catch ( Throwable ignorada ) {
      // corrida a medio armar
    }
    if ( fin == null || fin.getTime() <= 0 ) {
      return r.s( "metrics_none" );
    }
    return r.f( "metrics_last", DateFormat.getTimeInstance( DateFormat.SHORT, r.locale() ).format( fin ) );
  }

  private void mostrarFila( String id, boolean ver ) {
    Control[] fila = filas.get( id );
    if ( fila == null ) {
      return;
    }
    mostrar( fila, 0, ver );
  }

  private void mostrarExtra( String id, boolean ver ) {
    Control[] fila = filas.get( id );
    if ( fila != null && fila.length > 1 ) {
      mostrar( fila, 1, ver );
    }
  }

  private static void mostrar( Control[] fila, int desde, boolean ver ) {
    for ( int i = desde; i < fila.length; i++ ) {
      Control k = fila[ i ];
      Object ld = k.getLayoutData();
      GridData gd = ld instanceof GridData ? (GridData) ld : new GridData();
      gd.exclude = !ver;
      k.setLayoutData( gd );
      k.setVisible( ver );
    }
  }

  /** Renglon con sangria, debajo del check, para los controles propios de la seccion. */
  private static Composite sangria( Composite padre, int columnas ) {
    Composite c = new Composite( padre, SWT.NONE );
    GridLayout gl = new GridLayout( columnas, false );
    gl.marginWidth = 0;
    gl.marginHeight = 0;
    c.setLayout( gl );
    GridData gd = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gd.horizontalIndent = SANGRIA;
    c.setLayoutData( gd );
    return c;
  }

  private static GridData ancho( int px ) {
    GridData gd = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gd.widthHint = px;
    gd.minimumWidth = 60;
    return gd;
  }
}
