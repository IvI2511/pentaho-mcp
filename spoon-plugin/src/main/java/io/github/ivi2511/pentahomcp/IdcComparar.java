package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import io.github.ivi2511.pentahomcp.diff.Diferencia;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Como;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Propiedad;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Que;
import io.github.ivi2511.pentahomcp.diff.Diff;
import io.github.ivi2511.pentahomcp.diff.Git;
import io.github.ivi2511.pentahomcp.diff.ResultadoDiff;
import io.github.ivi2511.pentahomcp.diff.Salidas;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * «Comparar…» del menú IDC (diseño E1): elegir con qué comparar el documento activo (su archivo guardado, una revisión
 * de git u otro archivo), cómo verlo (insignias en este lienzo, o dos pestañas, antes y después, las dos con
 * insignias) y la lista de cambios, donde un clic selecciona el step. Las insignias son solo dibujo
 * ({@link DiffLienzo}) y se van al cerrar la lista; la pestaña de antes, si no se tocó, también. (UI)
 */
final class IdcComparar {

  /** La lista abierta (una a la vez). */
  static Lista abierta;

  final Shell shell;
  final Rotulos r;
  final AbstractMeta meta;
  final Button guardado;
  final Button git;
  final Combo revisiones;
  final Button otro;
  final Text archivo;
  final Button ignorar;
  final Button enLienzo;
  final Button pestanas;
  final Button comparar;
  final Label estado;
  volatile boolean ocupado;

  // ================================================================ el diálogo

  /** Abre el diálogo para el documento activo. (UI) */
  static IdcComparar abrir( Shell padre, AbstractMeta m, String idioma ) {
    IdcComparar d = new IdcComparar( padre, m, Salidas.rotulos( idioma ) );
    d.shell.pack();
    IdcMenu.abrirShell( d.shell, padre );
    return d;
  }

  IdcComparar( Shell padre, AbstractMeta m, Rotulos r ) {
    this.r = r;
    this.meta = m;
    shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL );
    shell.setText( r.f( "cmp.title", Docs.nombre( m ) ) );
    final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
    if ( ico != null ) {
      shell.setImage( ico );
      shell.addListener( SWT.Dispose, e -> ico.dispose() );
    }
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 14;
    gl.marginHeight = 12;
    gl.verticalSpacing = 10;
    shell.setLayout( gl );

    final File f = m.getFilename() == null ? null : new File( m.getFilename() );
    Group con = new Group( shell, SWT.NONE );
    con.setText( r.s( "cmp.with" ) );
    con.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    con.setLayout( new GridLayout( 3, false ) );
    guardado = new Button( con, SWT.RADIO );
    guardado.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false, 3, 1 ) );
    boolean hayArchivo = f != null && f.isFile();
    guardado.setText( hayArchivo ? r.s( "cmp.saved" ) : r.s( "cmp.saved_none" ) );
    guardado.setToolTipText( r.s( "cmp.saved_tip" ) );
    guardado.setEnabled( hayArchivo );
    git = new Button( con, SWT.RADIO );
    git.setText( r.s( "cmp.git" ) );
    revisiones = new Combo( con, SWT.DROP_DOWN );
    GridData gr = new GridData( SWT.FILL, SWT.CENTER, true, false, 2, 1 );
    gr.widthHint = 380;
    revisiones.setLayoutData( gr );
    revisiones.setText( "HEAD" );
    git.setEnabled( hayArchivo );
    revisiones.setEnabled( hayArchivo );
    otro = new Button( con, SWT.RADIO );
    otro.setText( r.s( "cmp.file" ) );
    archivo = new Text( con, SWT.BORDER );
    archivo.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    Button examinar = new Button( con, SWT.PUSH );
    examinar.setText( r.s( "cmp.browse" ) );
    examinar.addListener( SWT.Selection, e -> {
      FileDialog fd = new FileDialog( shell, SWT.OPEN );
      fd.setFilterExtensions( new String[] { m instanceof JobMeta ? "*.kjb" : "*.ktr", "*.*" } );
      if ( f != null && f.getParentFile() != null ) {
        fd.setFilterPath( f.getParentFile().getPath() );
      }
      String x = fd.open();
      if ( x != null ) {
        archivo.setText( x );
        elegir( otro );
      }
    } );
    revisiones.addListener( SWT.Modify, e -> elegir( git ) );
    archivo.addListener( SWT.Modify, e -> {
      if ( !archivo.getText().trim().isEmpty() ) {
        elegir( otro );
      }
    } );
    elegir( hayArchivo ? ( m.hasChanged() ? guardado : git ) : otro );

    ignorar = new Button( shell, SWT.CHECK );
    ignorar.setText( r.s( "cmp.ignore_positions" ) );
    ignorar.setSelection( true );

    Group ver = new Group( shell, SWT.NONE );
    ver.setText( r.s( "cmp.view" ) );
    ver.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    ver.setLayout( new GridLayout( 1, false ) );
    enLienzo = new Button( ver, SWT.RADIO );
    enLienzo.setText( r.s( "cmp.view_badges" ) );
    enLienzo.setSelection( true );
    pestanas = new Button( ver, SWT.RADIO );
    pestanas.setText( r.s( "cmp.view_tabs" ) );

    Composite pie = new Composite( shell, SWT.NONE );
    pie.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gp = new GridLayout( 3, false );
    gp.marginWidth = 0;
    pie.setLayout( gp );
    estado = new Label( pie, SWT.WRAP );
    GridData ge = new GridData( SWT.FILL, SWT.CENTER, true, false );
    ge.widthHint = 300;
    estado.setLayoutData( ge );
    comparar = new Button( pie, SWT.PUSH );
    comparar.setText( r.s( "cmp.run" ) );
    comparar.setLayoutData( new GridData( 110, SWT.DEFAULT ) );
    comparar.addListener( SWT.Selection, e -> comparar() );
    Button cancelar = new Button( pie, SWT.PUSH );
    cancelar.setText( r.s( "cmp.cancel" ) );
    cancelar.setLayoutData( new GridData( 110, SWT.DEFAULT ) );
    cancelar.addListener( SWT.Selection, e -> shell.close() );
    shell.setDefaultButton( comparar );

    if ( hayArchivo ) {
      cargarHistorial( f );
    }
  }

  private void elegir( Button b ) {
    guardado.setSelection( b == guardado );
    git.setSelection( b == git );
    otro.setSelection( b == otro );
  }

  /** Los últimos commits del archivo en el combo, desde un hilo aparte (git puede tardar). */
  private void cargarHistorial( final File f ) {
    IdcMenu.enHilo( shell, "pentaho-live-git-log", () -> Git.historial( f, 15 ), commits -> {
      if ( revisiones.isDisposed() ) {
        return;
      }
      String actual = revisiones.getText();
      if ( commits.isEmpty() && !Git.disponible() ) {
        git.setText( r.s( "cmp.git_none" ) );
        git.setEnabled( false );
        revisiones.setEnabled( false );
        if ( git.getSelection() ) {
          elegir( guardado.isEnabled() ? guardado : otro );
        }
        git.getParent().layout( true );
        return;
      }
      revisiones.removeAll();
      revisiones.add( "HEAD" );
      for ( Git.Commit c : commits ) {
        revisiones.add( c.hash + "  " + c.fecha + "  " + c.asunto );
      }
      revisiones.setText( actual );
    }, err -> {
      // sin historial: queda HEAD escrito a mano
    } );
  }

  /** La referencia elegida: "saved", "git:REV" o una ruta; null si falta el archivo. */
  String referencia() {
    if ( guardado.getSelection() ) {
      return DiffTools.GUARDADO;
    }
    if ( git.getSelection() ) {
      String t = revisiones.getText().trim();
      int sp = t.indexOf( ' ' );
      String rev = sp > 0 ? t.substring( 0, sp ) : t;
      return "git:" + ( rev.isEmpty() ? "HEAD" : rev );
    }
    String a = archivo.getText().trim();
    return a.isEmpty() ? null : a;
  }

  /** Compara en un hilo aparte y, si sale bien, cierra el diálogo y muestra los cambios. (UI) */
  void comparar() {
    if ( ocupado ) {
      return;
    }
    final String ref = referencia();
    if ( ref == null ) {
      estado.setText( r.s( "cmp.no_file" ) );
      return;
    }
    final boolean ign = ignorar.getSelection();
    final boolean dos = pestanas.getSelection();
    final String idioma = r.idioma();
    final Diff.Fuente despues = Diff.Fuente.texto( XMLHandler.getXMLHeader() + xml( meta ), DiffTools.etiquetaAbierto(
      meta, idioma ) );
    final File f = meta.getFilename() == null ? null : new File( meta.getFilename() );
    ocupado = true;
    comparar.setEnabled( false );
    estado.setText( r.s( "cmp.working" ) );
    IdcMenu.enHilo( shell, "pentaho-live-diff", () -> DiffTools.comparar( despues, f, ref, ign ), c -> {
      ocupado = false;
      Spoon sp = UiThread.spoon();
      shell.close();
      if ( sp != null ) {
        mostrar( sp, meta, c, dos, idioma );
      }
    }, e -> {
      ocupado = false;
      if ( !comparar.isDisposed() ) {
        comparar.setEnabled( true );
        estado.setText( r.f( "cmp.error", IdcMenu.mensaje( e ) ) );
        shell.layout( true, true );
      }
    } );
  }

  private static String xml( AbstractMeta m ) {
    try {
      return m.getXML();
    } catch ( Exception e ) {
      throw new IllegalStateException( e );
    }
  }

  // ================================================================ mostrar: insignias y lista

  /**
   * Muestra una comparación hecha: las insignias en el lienzo de {@code despues} (con los quitados como fantasmas) o,
   * con {@code dosPestanas}, la versión de antes en una pestaña nueva y las dos con insignias; y la lista de cambios.
   * (UI)
   */
  static Lista mostrar( Spoon sp, AbstractMeta despues, DiffTools.Comparacion c, boolean dosPestanas,
                        String idioma ) {
    if ( abierta != null && !abierta.shell.isDisposed() ) {
      abierta.shell.close();
    }
    AbstractMeta antes = null;
    if ( dosPestanas && !c.resultado.iguales() ) {
      antes = abrirAntes( sp, despues, c, idioma );
    }
    DiffLienzo.poner( sp.getDisplay(), despues, DiffLienzo.marcas( c.resultado, false, antes == null ) );
    if ( antes != null ) {
      DiffLienzo.poner( sp.getDisplay(), antes, DiffLienzo.marcas( c.resultado, true, false ) );
    }
    redibujar( despues );
    redibujar( antes );
    abierta = new Lista( sp.getShell(), despues, antes, c.resultado, Salidas.rotulos( idioma ) );
    abierta.abrir();
    return abierta;
  }

  /** Carga la versión de antes desde su XML y la abre en una pestaña nueva, sin archivo y sin cambios. (UI) */
  static AbstractMeta abrirAntes( Spoon sp, AbstractMeta despues, DiffTools.Comparacion c, String idioma ) {
    try {
      String etiqueta = c.antes.etiqueta;
      if ( etiqueta != null && etiqueta.length() > 40 ) {
        etiqueta = "…" + etiqueta.substring( etiqueta.length() - 39 );
      }
      AbstractMeta m;
      if ( despues instanceof JobMeta ) {
        JobMeta jm = new JobMeta( new ByteArrayInputStream( c.antes.xml ), null, null );
        jm.setName( Salidas.rotulos( idioma ).f( "cmp.before_tab", jm.getName(), etiqueta ) );
        jm.setFilename( null );
        sp.delegates.jobs.addJobGraph( jm );
        m = jm;
      } else {
        TransMeta tm = new TransMeta( new ByteArrayInputStream( c.antes.xml ), null, true, null, null );
        tm.setName( Salidas.rotulos( idioma ).f( "cmp.before_tab", tm.getName(), etiqueta ) );
        tm.setFilename( null );
        sp.delegates.trans.addTransGraph( tm );
        m = tm;
      }
      m.clearChanged();
      sp.refreshTree();
      Docs.activar( despues );
      return m;
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logError( "Pentaho Live: could not open the other version in a tab", t );
      return null;
    }
  }

  static void redibujar( AbstractMeta m ) {
    if ( m == null ) {
      return;
    }
    try {
      Docs.redibujar( m );
    } catch ( Throwable ignorada ) {
      // Spoon cerrándose
    }
  }

  /** La lista de cambios: tabla, clic = seleccionar el step, copiar como Markdown, cerrar = quitar las insignias. */
  static final class Lista {
    final Shell shell;
    final Table tabla;
    final AbstractMeta despues;
    final AbstractMeta antes;
    final ResultadoDiff resultado;
    final Rotulos r;
    /** El cambio de cada fila. */
    final List<Diferencia> filas = new ArrayList<Diferencia>();

    Lista( Shell padre, AbstractMeta despues, AbstractMeta antes, ResultadoDiff res, Rotulos r ) {
      this.despues = despues;
      this.antes = antes;
      this.resultado = res;
      this.r = r;
      shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MODELESS );
      shell.setText( r.f( "cmp.list_title", Docs.nombre( despues ) ) );
      final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
      if ( ico != null ) {
        shell.setImage( ico );
        shell.addListener( SWT.Dispose, e -> ico.dispose() );
      }
      GridLayout gl = new GridLayout( 1, false );
      gl.marginWidth = 12;
      gl.marginHeight = 10;
      shell.setLayout( gl );
      Label titulo = new Label( shell, SWT.WRAP );
      titulo.setText( Salidas.titulo( res, r ) );
      titulo.setFont( IdcCalidad.negrita( titulo, 1.15 ) );
      Label sub = new Label( shell, SWT.WRAP );
      GridData gs = new GridData( SWT.FILL, SWT.TOP, true, false );
      gs.widthHint = 480; // envuelve al ancho de la ventana (el de la tabla)
      sub.setLayoutData( gs );
      sub.setText( r.f( "before_after", n( res.etiquetaAntes ), n( res.etiquetaDespues ) ) + "\n"
        + ( res.iguales() ? r.s( "cmp.identical" ) : Salidas.resumen( res, r ) ) );
      tabla = new Table( shell, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE );
      tabla.setHeaderVisible( true );
      tabla.setLinesVisible( true );
      GridData gt = new GridData( SWT.FILL, SWT.FILL, true, true );
      gt.heightHint = 300;
      tabla.setLayoutData( gt );
      int[] anchos = { 110, 300, 390 };
      String[] cols = { r.s( "cmp.col_change" ), r.s( "cmp.col_what" ), r.s( "cmp.col_detail" ) };
      for ( int i = 0; i < cols.length; i++ ) {
        TableColumn c = new TableColumn( tabla, SWT.LEFT );
        c.setText( cols[ i ] );
        c.setWidth( anchos[ i ] );
      }
      llenar();
      IdcDetalle.tooltips( tabla );
      tabla.addListener( SWT.Selection, e -> seleccionar( tabla.getSelectionIndex() ) );
      Label ayuda = new Label( shell, SWT.WRAP );
      ayuda.setText( r.s( "cmp.list_hint" ) + " " + r.s( "cmp.close_tip" ) );
      ayuda.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
      Composite pie = new Composite( shell, SWT.NONE );
      pie.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
      GridLayout gp = new GridLayout( 3, false );
      gp.marginWidth = 0;
      pie.setLayout( gp );
      final Label aviso = new Label( pie, SWT.NONE );
      aviso.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
      Button copiar = new Button( pie, SWT.PUSH );
      copiar.setText( r.s( "cmp.copy_md" ) );
      copiar.addListener( SWT.Selection, e -> {
        Clipboard cb = new Clipboard( shell.getDisplay() );
        try {
          cb.setContents( new Object[] { Salidas.markdown( res, r.idioma() ) }, new Transfer[] { TextTransfer
            .getInstance() } );
          aviso.setText( r.s( "cmp.copied" ) );
        } finally {
          cb.dispose();
        }
      } );
      Button cerrar = new Button( pie, SWT.PUSH );
      cerrar.setText( r.s( "cmp.close" ) );
      cerrar.setLayoutData( new GridData( 100, SWT.DEFAULT ) );
      cerrar.addListener( SWT.Selection, e -> shell.close() );
      shell.addListener( SWT.Dispose, e -> limpiar() );
    }

    /** Clave de las preferencias: el tamaño de la ventana y el ancho de las columnas que dejó la persona. */
    static final String PREF = "cmp.list";

    /**
     * Las columnas a lo que miden su título y sus filas (la de «qué» hasta 560 px, la de detalle hasta 900) y la
     * ventana a la tabla entera, sin pasar del 92 % del ancho ni del 80 % del alto de la pantalla donde está Spoon. Si
     * la persona ya la agrandó o achicó, vale lo que dejó (IdcDetalle.tamano / columnas). (UI)
     */
    org.eclipse.swt.graphics.Point medir( org.eclipse.swt.graphics.Rectangle pantalla ) {
      TableColumn[] cs = tabla.getColumns();
      int[] tope = { 220, 560, 900 };
      int suma = 0;
      for ( int i = 0; i < cs.length; i++ ) {
        cs[ i ].pack();
        cs[ i ].setWidth( Math.max( i == 2 ? 240 : 90, Math.min( tope[ i ], cs[ i ].getWidth() + 12 ) ) );
        suma += cs[ i ].getWidth();
      }
      int barra = tabla.getVerticalBar() == null ? 18 : tabla.getVerticalBar().getSize().x;
      int ancho = suma + barra + 2 * tabla.getBorderWidth() + 2 * 12 + 8;
      ancho = Math.max( 560, Math.min( ancho, pantalla.width * 92 / 100 ) );
      int filas = Math.max( 6, Math.min( 22, tabla.getItemCount() + 1 ) );
      ( (GridData) tabla.getLayoutData() ).heightHint = tabla.getHeaderHeight() + filas * tabla.getItemHeight();
      int alto = shell.computeSize( ancho, SWT.DEFAULT, true ).y;
      alto = Math.min( alto, pantalla.height * 80 / 100 );
      return new org.eclipse.swt.graphics.Point( ancho, alto );
    }

    /** El tamaño: el que dejó la persona la última vez o el de {@link #medir}; los anchos de columna, igual. (UI) */
    void dimensionar( org.eclipse.swt.graphics.Rectangle pantalla ) {
      org.eclipse.swt.graphics.Point t0 = medir( pantalla );
      IdcDetalle.tamano( shell, PREF, t0.x, t0.y );
      IdcDetalle.columnas( tabla, PREF, 2 );
      shell.layout( true, true );
    }

    void abrir() {
      Shell padre = (Shell) shell.getParent();
      org.eclipse.swt.graphics.Rectangle pantalla = padre != null && !padre.isDisposed() && padre.getMonitor() != null
        ? padre.getMonitor().getClientArea() : shell.getDisplay().getClientArea();
      dimensionar( pantalla );
      org.eclipse.swt.graphics.Point t = shell.getSize();
      int x = pantalla.x + ( pantalla.width - t.x ) / 2;
      int y = pantalla.y + ( pantalla.height - t.y ) / 2;
      if ( padre != null && !padre.isDisposed() ) {
        org.eclipse.swt.graphics.Rectangle p = padre.getBounds();
        x = p.x + Math.max( 0, p.width - t.x - 40 );
        y = p.y + Math.max( 0, p.height - t.y - 60 );
      }
      // siempre entera dentro de la pantalla
      x = Math.max( pantalla.x, Math.min( x, pantalla.x + pantalla.width - t.x ) );
      y = Math.max( pantalla.y, Math.min( y, pantalla.y + pantalla.height - t.y ) );
      shell.setLocation( x, y );
      if ( shell.getDisplay().getActiveShell() != null ) {
        shell.open();
      } else {
        // pedida desde Claude con Spoon en segundo plano: se muestra sin quitarle el foco a la persona
        shell.setVisible( true );
      }
    }

    private void llenar() {
      Display d = shell.getDisplay();
      Color[] colores = { new Color( d, 34, 139, 58 ), new Color( d, 200, 40, 40 ), new Color( d, 170, 105, 0 ),
        new Color( d, 40, 98, 196 ), new Color( d, 110, 110, 110 ) };
      shell.addListener( SWT.Dispose, e -> {
        for ( Color c : colores ) {
          c.dispose();
        }
      } );
      for ( Que q : new Que[] { Que.DOCUMENTO, Que.STEP, Que.ENTRADA, Que.SALTO, Que.ERRORES, Que.CONEXION,
        Que.PARAMETRO, Que.NOTA } ) {
        for ( Diferencia df : resultado.de( q ) ) {
          TableItem it = new TableItem( tabla, SWT.NONE );
          it.setText( 0, df.como.simbolo + "  " + r.s( "w." + df.como.id ) );
          it.setForeground( 0, colores[ df.como.ordinal() ] );
          it.setText( 1, r.s( "sec." + q.id ) + " · " + Salidas.cabeceraCorta( df, r ) );
          String det = detalle( df );
          it.setText( 2, det );
          it.setData( "tip2", detalleCompleto( df ) );
          filas.add( df );
        }
      }
    }

    /** Todas las propiedades, una por línea (el tooltip de la celda de detalle). */
    private String detalleCompleto( Diferencia df ) {
      StringBuilder sb = new StringBuilder();
      for ( Propiedad p : df.propiedades ) {
        Salidas.lineasPropiedad( df, p, r, "", sb );
      }
      String t = sb.toString().trim();
      return t.length() > 2000 ? t.substring( 0, 1999 ) + "…" : t;
    }

    /** La primera propiedad (o las primeras) en una línea. */
    private String detalle( Diferencia df ) {
      StringBuilder sb = new StringBuilder();
      for ( Propiedad p : df.propiedades ) {
        StringBuilder una = new StringBuilder();
        Salidas.lineasPropiedad( df, p, r, "", una );
        String l = una.toString().trim().replace( '\n', ' ' );
        if ( l.isEmpty() ) {
          continue;
        }
        if ( sb.length() > 0 ) {
          sb.append( "; " );
        }
        sb.append( l );
        if ( sb.length() > 200 ) {
          break;
        }
      }
      return sb.length() > 220 ? sb.substring( 0, 219 ) + "…" : sb.toString();
    }

    /** Selecciona en el lienzo el step o la entrada (o los dos extremos de un salto) del cambio. (UI) */
    void seleccionar( int i ) {
      if ( i < 0 || i >= filas.size() ) {
        return;
      }
      Diferencia df = filas.get( i );
      List<String> nombres = new ArrayList<String>();
      AbstractMeta donde = despues;
      if ( df.que == Que.STEP || df.que == Que.ENTRADA || df.que == Que.ERRORES ) {
        if ( df.como == Como.QUITADO ) {
          if ( antes == null ) {
            return; // es un fantasma en este lienzo
          }
          donde = antes;
        }
        nombres.add( nombre( df.nombre ) );
      } else if ( df.que == Que.SALTO ) {
        if ( df.como == Como.QUITADO ) {
          if ( antes == null ) {
            return;
          }
          donde = antes;
        }
        nombres.add( nombre( df.desde ) );
        nombres.add( nombre( df.hasta ) );
      } else {
        return;
      }
      try {
        Docs.activar( donde );
        TransTools.seleccionar( donde, nombres );
      } catch ( Throwable t ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not select " + nombres + ": " + t );
      }
    }

    /** La clave de un nodo de job (nombre#n) sin la copia. */
    private static String nombre( String clave ) {
      int i = clave.lastIndexOf( '#' );
      return i > 0 && clave.substring( i + 1 ).matches( "\\d+" ) ? clave.substring( 0, i ) : clave;
    }

    /** Al cerrar: fuera las insignias y, si nadie la tocó, la pestaña de antes. */
    void limpiar() {
      try {
        Display d = Display.getCurrent();
        if ( d != null ) {
          DiffLienzo.quitar( d, despues );
          redibujar( despues );
          if ( antes != null ) {
            DiffLienzo.quitar( d, antes );
            cerrarAntes();
          }
        }
      } catch ( Throwable t ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: diff cleanup: " + t );
      }
      if ( abierta == this ) {
        abierta = null;
      }
    }

    private void cerrarAntes() {
      Spoon sp = UiThread.spoon();
      if ( sp == null || antes.hasChanged() ) {
        redibujar( antes );
        return;
      }
      try {
        org.pentaho.di.ui.spoon.TabMapEntry e = sp.delegates.tabs.findTabMapEntry( antes );
        if ( e != null ) {
          org.pentaho.xul.swt.tab.TabItem pestania = e.getTabItem();
          if ( sp.tabClose( pestania, true ) && !pestania.isDisposed() ) {
            if ( pestania.getTabset() != null ) {
              pestania.getTabset().remove( pestania );
            }
            if ( !pestania.isDisposed() ) {
              pestania.dispose();
            }
          }
        }
      } catch ( Throwable t ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not close the other version's tab: " + t );
      }
    }

    private static String n( String s ) {
      return s == null ? "" : s;
    }
  }

}
