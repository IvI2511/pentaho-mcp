package io.github.ivi2511.pentahomcp.informes.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Barra superior (diseno §5.1 y §5.2): combo de documentos (pestanas de trans y job abiertas, mas los
 * archivos cargados), refrescar, «Abrir archivo…» e idioma del informe. (UI)
 *
 * @dueño WP1
 */
final class BarraDocumento {

  /** Idiomas del informe, en el orden del combo. */
  static final String[] IDIOMAS = { "en", "es", "pt" };

  final Composite barra;
  final Combo documentos;
  final Button refrescar;
  final Button abrirArchivo;
  final Combo idioma;

  private final Rotulos r;
  private final List<PanelInformes.Documento> lista = new ArrayList<PanelInformes.Documento>();

  BarraDocumento( Composite padre, final PanelInformes panel ) {
    this.r = panel.r;
    barra = new Composite( padre, SWT.NONE );
    GridLayout gl = new GridLayout( 6, false );
    gl.marginWidth = 8;
    gl.marginHeight = 5;
    barra.setLayout( gl );
    barra.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

    Label l = new Label( barra, SWT.NONE );
    l.setText( PanelInformes.esc( r.s( "document" ) ) );
    documentos = new Combo( barra, SWT.READ_ONLY | SWT.DROP_DOWN );
    GridData gd = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gd.widthHint = 260;
    gd.minimumWidth = 160;
    documentos.setLayoutData( gd );
    documentos.setVisibleItemCount( 15 );
    documentos.setData( PanelInformes.CLAVE, "documento" );
    documentos.addListener( SWT.Selection, e -> panel.documentoElegido() );

    refrescar = new Button( barra, SWT.PUSH );
    refrescar.setText( PanelInformes.esc( r.s( "refresh" ) ) );
    refrescar.setToolTipText( r.s( "refresh_tip" ) );
    refrescar.setData( PanelInformes.CLAVE, "refrescar" );
    refrescar.addListener( SWT.Selection, e -> panel.refrescar() );

    abrirArchivo = new Button( barra, SWT.PUSH );
    abrirArchivo.setText( PanelInformes.esc( r.s( "open_file" ) ) );
    abrirArchivo.setToolTipText( r.s( "open_file_tip" ) );
    abrirArchivo.setData( PanelInformes.CLAVE, "abrir_archivo" );
    abrirArchivo.addListener( SWT.Selection, e -> panel.abrirArchivo() );

    Label li = new Label( barra, SWT.NONE );
    li.setText( PanelInformes.esc( r.s( "language" ) ) );
    GridData gdi = new GridData( SWT.END, SWT.CENTER, false, false );
    gdi.horizontalIndent = 16;
    li.setLayoutData( gdi );
    idioma = new Combo( barra, SWT.READ_ONLY | SWT.DROP_DOWN );
    for ( String i : IDIOMAS ) {
      idioma.add( r.s( "lang_" + i ) );
    }
    idioma.select( 0 );
    idioma.setToolTipText( r.s( "language_tip" ) );
    idioma.setData( PanelInformes.CLAVE, "idioma" );
    idioma.addListener( SWT.Selection, e -> panel.idiomaCambiado() );
  }

  /**
   * Llena el combo. Mantiene la seleccion por id si sigue abierto; si no, elige la pestana activa de
   * Spoon o la primera.
   */
  void cargar( List<PanelInformes.Documento> docs ) {
    String antes = seleccionadoId();
    lista.clear();
    documentos.removeAll();
    for ( PanelInformes.Documento d : docs ) {
      lista.add( d );
      documentos.add( texto( d ) );
    }
    if ( lista.isEmpty() ) {
      return;
    }
    if ( antes != null && seleccionar( antes ) ) {
      return;
    }
    for ( int i = 0; i < lista.size(); i++ ) {
      if ( lista.get( i ).activo ) {
        documentos.select( i );
        return;
      }
    }
    documentos.select( 0 );
  }

  /** «03_ventas (transformación)», o «… · archivo» si se cargo sin pestana. */
  String texto( PanelInformes.Documento d ) {
    String tipo = r.s( d.esJob() ? "type_job" : "type_transformation" );
    String nombre = d.nombre == null ? d.id : d.nombre;
    return d.cargado ? r.f( "doc_file_item", nombre, tipo ) : r.f( "doc_item", nombre, tipo );
  }

  /** El documento elegido, o null si no hay ninguno. */
  PanelInformes.Documento seleccionado() {
    int i = documentos.getSelectionIndex();
    return i < 0 || i >= lista.size() ? null : lista.get( i );
  }

  String seleccionadoId() {
    PanelInformes.Documento d = seleccionado();
    return d == null ? null : d.id;
  }

  /** Selecciona por id (o por nombre, si el id no esta); false si no hay ninguno asi. */
  boolean seleccionar( String id ) {
    if ( id == null ) {
      return false;
    }
    for ( int i = 0; i < lista.size(); i++ ) {
      if ( id.equalsIgnoreCase( lista.get( i ).id ) ) {
        documentos.select( i );
        return true;
      }
    }
    for ( int i = 0; i < lista.size(); i++ ) {
      if ( id.equalsIgnoreCase( lista.get( i ).nombre ) ) {
        documentos.select( i );
        return true;
      }
    }
    return false;
  }

  /** en | es | pt. */
  String idioma() {
    int i = idioma.getSelectionIndex();
    return i < 0 ? IDIOMAS[ 0 ] : IDIOMAS[ i ];
  }

  void idioma( String i ) {
    String n = Rotulos.normalizar( i );
    for ( int k = 0; k < IDIOMAS.length; k++ ) {
      if ( IDIOMAS[ k ].equals( n ) ) {
        idioma.select( k );
      }
    }
  }
}
