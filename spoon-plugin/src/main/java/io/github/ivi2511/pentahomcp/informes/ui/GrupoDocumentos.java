package io.github.ivi2511.pentahomcp.informes.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Grupo «Documentos» (0.5.1): exportar el documento elegido arriba (lo de siempre) o varios a la vez. Con
 * «Varios documentos», una tabla con casillas de las pestanas abiertas y los archivos cargados (las
 * casillas sobreviven a los refrescos por id de documento), el boton que suma las transformaciones que
 * ejecuta un job (justo despues del job), y «Un archivo por documento» o «Todo en un archivo» (un
 * informe con portada, indice y un capitulo por documento) con su titulo. (UI)
 *
 * @dueño M
 */
final class GrupoDocumentos {

  /** Filas visibles de la tabla (despues, scroll). */
  static final int FILAS = 6;

  final Group grupo;
  final Button uno;
  final Button varios;
  final Table lista;
  final Button job;
  final Button cada;
  final Button combinar;
  final Text titulo;
  final Label resumen;

  private final PanelInformes panel;
  private final Rotulos r;
  private final Composite cuerpo;
  /** Ids marcados: sobreviven a los refrescos de la tabla. */
  private final Set<String> marcados = new LinkedHashSet<String>();
  /** Documento cargado desde un job -> id del job: se lista justo despues de el. */
  private final Map<String, String> padres = new LinkedHashMap<String, String>();
  private final List<PanelInformes.Documento> docs = new ArrayList<PanelInformes.Documento>();

  GrupoDocumentos( Composite padre, final PanelInformes panel ) {
    this.panel = panel;
    this.r = panel.r;
    grupo = new Group( padre, SWT.NONE );
    grupo.setText( PanelInformes.esc( r.s( "docs" ) ) );
    grupo.setLayout( new GridLayout( 1, false ) );
    grupo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

    uno = new Button( grupo, SWT.RADIO );
    uno.setText( PanelInformes.esc( r.s( "docs_uno" ) ) );
    uno.setData( PanelInformes.CLAVE, "docs_uno" );
    uno.setSelection( true );
    varios = new Button( grupo, SWT.RADIO );
    varios.setText( PanelInformes.esc( r.s( "docs_varios" ) ) );
    varios.setData( PanelInformes.CLAVE, "docs_varios" );

    cuerpo = new Composite( grupo, SWT.NONE );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 0;
    gl.marginHeight = 0;
    cuerpo.setLayout( gl );
    GridData gc = new GridData( SWT.FILL, SWT.TOP, true, false );
    gc.horizontalIndent = GrupoSecciones.SANGRIA;
    cuerpo.setLayoutData( gc );

    lista = new Table( cuerpo, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL );
    lista.setData( PanelInformes.CLAVE, "docs_lista" );
    lista.setToolTipText( r.s( "docs_lista_tip" ) );
    GridData gt = new GridData( SWT.FILL, SWT.TOP, true, false );
    gt.widthHint = 160;
    gt.heightHint = lista.getItemHeight() * FILAS + 4;
    lista.setLayoutData( gt );
    lista.addListener( SWT.Selection, e -> {
      if ( e.detail == SWT.CHECK && e.item instanceof TableItem ) {
        TableItem it = (TableItem) e.item;
        String id = (String) it.getData();
        if ( it.getChecked() ) {
          marcados.add( id );
        } else {
          marcados.remove( id );
        }
        actualizarResumen();
      }
    } );

    job = new Button( cuerpo, SWT.PUSH );
    job.setText( PanelInformes.esc( r.s( "docs_job" ) ) );
    job.setToolTipText( r.s( "docs_job_tip" ) );
    job.setData( PanelInformes.CLAVE, "docs_job" );
    job.addListener( SWT.Selection, e -> panel.agregarTransformacionesDeJob() );

    cada = new Button( cuerpo, SWT.RADIO );
    cada.setText( PanelInformes.esc( r.s( "docs_cada" ) ) );
    cada.setData( PanelInformes.CLAVE, "docs_cada" );
    cada.setSelection( true );
    combinar = new Button( cuerpo, SWT.RADIO );
    combinar.setText( PanelInformes.esc( r.s( "docs_combinar" ) ) );
    combinar.setData( PanelInformes.CLAVE, "docs_combinar" );

    titulo = new Text( cuerpo, SWT.BORDER );
    titulo.setMessage( r.s( "docs_titulo" ) );
    titulo.setToolTipText( r.s( "docs_titulo_tip" ) );
    titulo.setData( PanelInformes.CLAVE, "docs_titulo" );
    GridData gti = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gti.horizontalIndent = GrupoSecciones.SANGRIA;
    titulo.setLayoutData( gti );

    resumen = new Label( cuerpo, SWT.WRAP );
    resumen.setData( PanelInformes.CLAVE, "docs_resumen" );
    resumen.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

    uno.addListener( SWT.Selection, e -> modo() );
    varios.addListener( SWT.Selection, e -> modo() );
    cada.addListener( SWT.Selection, e -> modo() );
    combinar.addListener( SWT.Selection, e -> modo() );
    modo();
  }

  /** Llena la tabla con los documentos del combo, en orden y con lo cargado de un job despues del job. */
  void cargar( List<PanelInformes.Documento> todos ) {
    docs.clear();
    List<PanelInformes.Documento> sueltos = new ArrayList<PanelInformes.Documento>();
    Map<String, List<PanelInformes.Documento>> hijos = new LinkedHashMap<String, List<PanelInformes.Documento>>();
    Set<String> ids = new LinkedHashSet<String>();
    for ( PanelInformes.Documento d : todos ) {
      ids.add( d.id );
    }
    for ( PanelInformes.Documento d : todos ) {
      String p = padres.get( d.id );
      if ( p != null && ids.contains( p ) && !p.equals( d.id ) ) {
        List<PanelInformes.Documento> l = hijos.get( p );
        if ( l == null ) {
          l = new ArrayList<PanelInformes.Documento>();
          hijos.put( p, l );
        }
        l.add( d );
      } else {
        sueltos.add( d );
      }
    }
    for ( PanelInformes.Documento d : sueltos ) {
      docs.add( d );
      List<PanelInformes.Documento> l = hijos.get( d.id );
      if ( l != null ) {
        docs.addAll( l );
      }
    }
    lista.removeAll();
    for ( PanelInformes.Documento d : docs ) {
      TableItem it = new TableItem( lista, SWT.NONE );
      it.setText( ( padres.containsKey( d.id ) ? "   " : "" ) + d.nombre + " (" + r.s( d.esJob() ? "doc_job"
        : "doc_trans" ) + ")" );
      it.setData( d.id );
      it.setChecked( marcados.contains( d.id ) );
    }
    marcados.retainAll( ids );
    actualizarResumen();
  }

  /** Marca esos documentos (los que suma el boton del job) y los anota como hijos del job. */
  void agregados( String jobId, List<String> ids ) {
    marcados.add( jobId );
    for ( String id : ids ) {
      if ( !id.equals( jobId ) && !padres.containsKey( id ) ) {
        padres.put( id, jobId );
      }
      marcados.add( id );
    }
  }

  /** true si se eligio «Varios documentos». */
  boolean esVarios() {
    return varios.getSelection();
  }

  /**
   * Los documentos a exportar, en el orden de la tabla: con «Este documento», solo el elegido arriba;
   * con «Varios», los marcados (vacia si ninguno).
   */
  List<PanelInformes.Documento> elegidos( PanelInformes.Documento seleccionado ) {
    List<PanelInformes.Documento> out = new ArrayList<PanelInformes.Documento>();
    if ( !esVarios() ) {
      if ( seleccionado != null ) {
        out.add( seleccionado );
      }
      return out;
    }
    for ( PanelInformes.Documento d : docs ) {
      if ( marcados.contains( d.id ) ) {
        out.add( d );
      }
    }
    return out;
  }

  /** true = «Todo en un archivo». */
  boolean combinar() {
    return esVarios() && combinar.getSelection();
  }

  /** El titulo del informe combinado, o null (los nombres de los documentos). */
  String titulo() {
    String t = titulo.getText().trim();
    return t.isEmpty() ? null : t;
  }

  /** Activa los controles segun el modo; los de «Varios» no ocupan lugar con «Este documento». */
  void modo() {
    boolean v = varios.getSelection();
    GridData gd = (GridData) cuerpo.getLayoutData();
    gd.exclude = !v;
    cuerpo.setVisible( v );
    titulo.setEnabled( v && combinar.getSelection() );
    actualizarResumen();
    panel.relayout();
  }

  private void actualizarResumen() {
    int n = 0;
    for ( PanelInformes.Documento d : docs ) {
      if ( marcados.contains( d.id ) ) {
        n++;
      }
    }
    resumen.setText( PanelInformes.esc( n < 2 ? r.s( "docs_pocos" ) : r.f( combinar.getSelection()
      ? "docs_resumen_uno" : "docs_resumen_cada", n ) ) );
  }
}
