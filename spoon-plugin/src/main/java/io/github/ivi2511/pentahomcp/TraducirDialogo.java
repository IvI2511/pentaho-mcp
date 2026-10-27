package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Opciones;
import io.github.ivi2511.pentahomcp.traductor.Verificacion;

/**
 * «Traducir a Python…» del menú IDC (0.8.0): pregunta el formato (.py o .ipynb), la biblioteca (pandas o PySpark), el
 * idioma, el título, el autor, la introducción, qué agregar (explicaciones, vistas previas, gráficos EDA, notas),
 * si se verifica con los datos y el archivo de salida; traduce en segundo plano ({@link TraducirTools#traducir}) y
 * muestra el resumen (exactos, aproximados, a completar, la verificación) con «Abrir carpeta» y «Abrir archivo». (UI)
 */
final class TraducirDialogo {

  /** Área de los textos (traducir_*.properties). */
  static final String AREA = "traducir";
  static final String[] IDIOMAS = { "es", "en", "pt" };
  /** Cómo se ve cada idioma en la lista (en su propio idioma). */
  static final String[] NOMBRES_IDIOMAS = { "Español", "English", "Português" };

  /** Quien traduce (las pruebas ponen uno propio). Corre fuera del hilo de la UI. */
  interface Traductor {
    TraducirTools.Hecho traducir( AbstractMeta meta, Opciones o, File salida, boolean sobrescribir, boolean verificar )
      throws Exception;
  }

  static volatile Traductor traductor = TraducirTools::traducir;
  /** Dónde está Python (las pruebas ponen uno propio); null = no hay. Corre fuera del hilo de la UI. */
  static volatile java.util.function.Supplier<String> buscadorPython = TraducirTools::python;
  /** Cómo se abre un archivo o una carpeta (las pruebas lo cambian). */
  static volatile java.util.function.Consumer<String> abridor = Program::launch;

  final Shell shell;
  final Rotulos r;
  final AbstractMeta meta;
  final Button py;
  final Button ipynb;
  final Button pandas;
  final Button pyspark;
  final Combo idioma;
  final Text titulo;
  final Text autor;
  final Text intro;
  final Button explicaciones;
  final Button vistas;
  final Button eda;
  final Button notas;
  final Button verificar;
  final Text salida;
  final Button examinar;
  final ProgressBar barra;
  final Label estado;
  final Text resumen;
  final Button generar;
  final Button abrirCarpeta;
  final Button abrirArchivo;
  final Button cerrar;
  /** La persona escribió la salida a mano: no se recalcula al cambiar formato o biblioteca. */
  boolean salidaEditada;
  private boolean poniendoSalida;
  volatile boolean ocupado;
  /** El último archivo generado (null = ninguno). */
  File generado;

  static Rotulos rotulos() {
    return Rotulos.de( AREA, ReportsPerspective.idiomaUi() );
  }

  /** Abre el diálogo para el documento activo (transformación o job). (UI) */
  static TraducirDialogo abrir( Shell padre, AbstractMeta m, Rotulos r ) {
    TraducirDialogo d = new TraducirDialogo( padre, m, r );
    d.shell.pack();
    org.eclipse.swt.graphics.Point t = d.shell.getSize();
    d.shell.setSize( Math.max( t.x, 620 ), t.y );
    IdcMenu.abrirShell( d.shell, padre );
    d.buscarPython();
    return d;
  }

  TraducirDialogo( Shell padre, AbstractMeta m, Rotulos r ) {
    this.r = r;
    this.meta = m;
    shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MODELESS );
    shell.setText( r.f( "t_title", Docs.nombre( m ) ) );
    final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
    if ( ico != null ) {
      shell.setImage( ico );
      shell.addListener( SWT.Dispose, e -> ico.dispose() );
    }
    GridLayout gl = new GridLayout( 2, true );
    gl.marginWidth = 14;
    gl.marginHeight = 12;
    gl.verticalSpacing = 10;
    shell.setLayout( gl );

    // formato y biblioteca
    Group gf = grupo( r.s( "t_format" ), 1 );
    py = radio( gf, r.s( "t_py" ) );
    ipynb = radio( gf, r.s( "t_ipynb" ) );
    ipynb.setSelection( true );
    Group gb = grupo( r.s( "t_library" ), 1 );
    pandas = radio( gb, r.s( "t_pandas" ) );
    pyspark = radio( gb, r.s( "t_pyspark" ) );
    pandas.setSelection( true );

    // textos
    Group gt = grupo( r.s( "t_text" ), 2 );
    ( (GridData) gt.getLayoutData() ).horizontalSpan = 2;
    new Label( gt, SWT.NONE ).setText( r.s( "t_language" ) );
    idioma = new Combo( gt, SWT.DROP_DOWN | SWT.READ_ONLY );
    for ( String i : NOMBRES_IDIOMAS ) {
      idioma.add( i );
    }
    int ii = java.util.Arrays.asList( IDIOMAS ).indexOf( r.idioma() );
    idioma.select( ii < 0 ? 0 : ii );
    new Label( gt, SWT.NONE ).setText( r.s( "t_titulo" ) );
    titulo = new Text( gt, SWT.BORDER );
    titulo.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    titulo.setText( Docs.nombre( m ) );
    new Label( gt, SWT.NONE ).setText( r.s( "t_author" ) );
    autor = new Text( gt, SWT.BORDER );
    autor.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    Label li = new Label( gt, SWT.NONE );
    li.setText( r.s( "t_intro" ) );
    li.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );
    intro = new Text( gt, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL );
    GridData gi = new GridData( SWT.FILL, SWT.FILL, true, false, 2, 1 );
    gi.heightHint = 60;
    gi.widthHint = 520;
    intro.setLayoutData( gi );

    // qué agregar
    Group go = grupo( r.s( "t_options" ), 2 );
    ( (GridData) go.getLayoutData() ).horizontalSpan = 2;
    explicaciones = check( go, r.s( "t_explanations" ), true );
    vistas = check( go, r.s( "t_previews" ), true );
    eda = check( go, r.s( "t_eda" ), false );
    eda.setToolTipText( r.s( "t_eda_tip" ) );
    notas = check( go, r.s( "t_notes" ), true );
    verificar = check( go, r.s( "t_verify" ), false );
    ( (GridData) verificar.getLayoutData() ).horizontalSpan = 2;
    verificar.setEnabled( false ); // hasta saber si hay Python (buscarPython)
    verificar.setToolTipText( m instanceof TransMeta ? r.s( "t_verify_tip" ) : r.s( "t_verify_job" ) );

    // salida
    Composite cs = new Composite( shell, SWT.NONE );
    cs.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );
    GridLayout gs = new GridLayout( 3, false );
    gs.marginWidth = 0;
    cs.setLayout( gs );
    new Label( cs, SWT.NONE ).setText( r.s( "t_output" ) );
    salida = new Text( cs, SWT.BORDER );
    salida.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    salida.addListener( SWT.Modify, e -> {
      if ( !poniendoSalida ) {
        salidaEditada = !salida.getText().trim().isEmpty();
      }
    } );
    examinar = new Button( cs, SWT.PUSH );
    examinar.setText( r.s( "t_browse" ) );
    examinar.addListener( SWT.Selection, e -> examinar() );

    // estado, resumen y botones
    estado = new Label( shell, SWT.WRAP );
    estado.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );
    resumen = new Text( shell, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL );
    GridData gr = new GridData( SWT.FILL, SWT.FILL, true, true, 2, 1 );
    gr.heightHint = 150;
    resumen.setLayoutData( gr );

    Composite bo = new Composite( shell, SWT.NONE );
    bo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );
    GridLayout gbo = new GridLayout( 5, false );
    gbo.marginWidth = 0;
    bo.setLayout( gbo );
    barra = new ProgressBar( bo, SWT.HORIZONTAL | SWT.INDETERMINATE );
    GridData gpb = new GridData( SWT.LEFT, SWT.CENTER, true, false );
    gpb.widthHint = 150;
    barra.setLayoutData( gpb );
    barra.setVisible( false );
    abrirCarpeta = new Button( bo, SWT.PUSH );
    abrirCarpeta.setText( r.s( "t_open_folder" ) );
    abrirCarpeta.setEnabled( false );
    abrirCarpeta.addListener( SWT.Selection, e -> {
      if ( generado != null && generado.getAbsoluteFile().getParentFile() != null ) {
        abridor.accept( generado.getAbsoluteFile().getParentFile().getPath() );
      }
    } );
    abrirArchivo = new Button( bo, SWT.PUSH );
    abrirArchivo.setText( r.s( "t_open_file" ) );
    abrirArchivo.setEnabled( false );
    abrirArchivo.addListener( SWT.Selection, e -> {
      if ( generado != null ) {
        abridor.accept( generado.getAbsolutePath() );
      }
    } );
    generar = new Button( bo, SWT.PUSH );
    generar.setText( r.s( "t_generate" ) );
    generar.addListener( SWT.Selection, e -> generar() );
    cerrar = new Button( bo, SWT.PUSH );
    cerrar.setText( r.s( "t_close" ) );
    cerrar.addListener( SWT.Selection, e -> shell.close() );
    shell.setDefaultButton( generar );

    for ( Button b : new Button[] { py, ipynb, pandas, pyspark } ) {
      b.addListener( SWT.Selection, e -> salidaPorDefecto() );
    }
    salidaPorDefecto();
  }

  private Group grupo( String texto, int columnas ) {
    Group g = new Group( shell, SWT.NONE );
    g.setText( texto );
    g.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, false ) );
    g.setLayout( new GridLayout( columnas, false ) );
    return g;
  }

  private static Button radio( Composite c, String texto ) {
    Button b = new Button( c, SWT.RADIO );
    b.setText( texto );
    return b;
  }

  private static Button check( Composite c, String texto, boolean marcado ) {
    Button b = new Button( c, SWT.CHECK );
    b.setText( texto );
    b.setSelection( marcado );
    b.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    return b;
  }

  /** El archivo del documento (null si no está guardado). */
  File origen() {
    String f = meta.getFilename();
    return f == null || f.trim().isEmpty() ? null : new File( f.replaceFirst( "^file:///?", "" ) );
  }

  /** Las opciones elegidas. (UI) */
  Opciones opciones() {
    Opciones o = new Opciones();
    o.formato = py.getSelection() ? "py" : "ipynb";
    o.destino = pyspark.getSelection() ? Destino.PYSPARK : Destino.PANDAS;
    o.idioma = idioma.getSelectionIndex() < 0 ? r.idioma() : IDIOMAS[ idioma.getSelectionIndex() ];
    o.titulo = vacio( titulo.getText() ) ? null : titulo.getText().trim();
    o.autor = vacio( autor.getText() ) ? null : autor.getText().trim();
    o.introduccion = vacio( intro.getText() ) ? null : intro.getText().replace( "\r\n", "\n" );
    o.explicaciones = explicaciones.getSelection();
    o.vistas = vistas.getSelection();
    o.eda = eda.getSelection();
    o.notas = notas.getSelection();
    return o;
  }

  /** Recalcula la salida por defecto (si la persona no la escribió a mano). (UI) */
  void salidaPorDefecto() {
    if ( salidaEditada ) {
      return;
    }
    File f = null;
    try {
      f = TraducirTools.salidaPorDefecto( origen(), Docs.nombre( meta ), opciones() );
    } catch ( RuntimeException e ) {
      f = null;
    }
    poniendoSalida = true;
    try {
      salida.setText( f == null ? "" : f.getPath() );
    } finally {
      poniendoSalida = false;
    }
  }

  private void examinar() {
    FileDialog fd = new FileDialog( shell, SWT.SAVE );
    boolean nb = ipynb.getSelection();
    fd.setFilterExtensions( new String[] { nb ? "*.ipynb" : "*.py", "*.*" } );
    File actual = vacio( salida.getText() ) ? origen() : new File( salida.getText().trim() );
    if ( actual != null ) {
      if ( actual.getAbsoluteFile().getParentFile() != null ) {
        fd.setFilterPath( actual.getAbsoluteFile().getParentFile().getPath() );
      }
      if ( !vacio( salida.getText() ) ) {
        fd.setFileName( actual.getName() );
      }
    }
    String x = fd.open();
    if ( x != null ) {
      String ext = nb ? ".ipynb" : ".py";
      if ( !x.toLowerCase( java.util.Locale.ROOT ).endsWith( ext ) && new File( x ).getName().indexOf( '.' ) < 0 ) {
        x = x + ext;
      }
      salida.setText( x ); // queda como editada
    }
  }

  /** Busca Python en segundo plano: habilita «Verificar con los datos» si hay (y es una transformación). */
  void buscarPython() {
    final Display d = shell.getDisplay();
    Thread t = new Thread( () -> {
      String p;
      try {
        p = buscadorPython.get();
      } catch ( Throwable e ) {
        p = null;
      }
      final String python = p;
      d.asyncExec( () -> {
        if ( shell.isDisposed() ) {
          return;
        }
        boolean trans = meta instanceof TransMeta;
        verificar.setEnabled( python != null && trans && !ocupado );
        verificar.setToolTipText( !trans ? r.s( "t_verify_job" ) : python == null ? r.s( "t_verify_no_python" )
          : r.s( "t_verify_tip" ) + " (" + python + ")" );
        if ( python == null || !trans ) {
          verificar.setSelection( false );
        }
      } );
    }, "pentaho-live-traducir-python" );
    t.setDaemon( true );
    t.start();
  }

  /** Genera: pregunta si reemplaza, traduce en segundo plano y muestra el resumen. (UI) */
  void generar() {
    if ( ocupado ) {
      return;
    }
    if ( vacio( salida.getText() ) ) {
      estado.setText( r.s( "t_no_output" ) );
      shell.layout( true, true );
      return;
    }
    final File f = new File( salida.getText().trim() );
    boolean sobrescribir = false;
    if ( f.exists() ) {
      if ( !preguntarReemplazo( f ) ) {
        return;
      }
      sobrescribir = true;
    }
    final Opciones o = opciones();
    final boolean verif = verificar.isEnabled() && verificar.getSelection();
    final boolean sob = sobrescribir;
    ocupado( true, verif );
    final Display d = shell.getDisplay();
    Thread t = new Thread( () -> {
      TraducirTools.Hecho h = null;
      Throwable error = null;
      try {
        h = traductor.traducir( meta, o, f, sob, verif );
      } catch ( Throwable e ) {
        error = e;
      }
      final TraducirTools.Hecho hecho = h;
      final Throwable err = error;
      if ( d.isDisposed() ) {
        return;
      }
      d.asyncExec( () -> {
        if ( shell.isDisposed() ) {
          return;
        }
        ocupado( false, false );
        if ( err != null ) {
          McpLifecycleListener.LOG.logError( "Pentaho Live: translate to Python failed", err );
          String msg = err.getMessage() == null ? err.toString() : err.getMessage();
          estado.setText( r.f( "t_error", msg ) );
          resumen.setText( "" );
        } else {
          mostrar( hecho, f );
        }
        shell.layout( true, true );
      } );
    }, "pentaho-live-traducir" );
    t.setDaemon( true );
    t.start();
  }

  /** Pregunta si se reemplaza el archivo (las pruebas lo cambian). */
  static volatile java.util.function.BiFunction<Shell, String, Boolean> preguntador = ( s, texto ) -> {
    MessageBox mb = new MessageBox( s, SWT.ICON_QUESTION | SWT.YES | SWT.NO );
    mb.setText( rotulos().s( "t_exists_title" ) );
    mb.setMessage( texto );
    return mb.open() == SWT.YES;
  };

  private boolean preguntarReemplazo( File f ) {
    Boolean b = preguntador.apply( shell, r.f( "t_exists", f.getPath() ) );
    return b != null && b;
  }

  private void ocupado( boolean si, boolean conVerificacion ) {
    ocupado = si;
    barra.setVisible( si );
    generar.setEnabled( !si );
    for ( org.eclipse.swt.widgets.Control c : new org.eclipse.swt.widgets.Control[] { py, ipynb, pandas, pyspark,
      idioma, titulo, autor, intro, explicaciones, vistas, eda, notas, salida, examinar } ) {
      c.setEnabled( !si );
    }
    if ( si ) {
      verificar.setData( "habilitado", verificar.isEnabled() );
      verificar.setEnabled( false );
      abrirCarpeta.setEnabled( false );
      abrirArchivo.setEnabled( false );
      estado.setText( conVerificacion ? r.s( "t_running_verify" ) : r.s( "t_running" ) );
      resumen.setText( "" );
    } else {
      verificar.setEnabled( Boolean.TRUE.equals( verificar.getData( "habilitado" ) ) );
    }
    shell.layout( true, true );
  }

  /** El resumen de una traducción. (UI) */
  void mostrar( TraducirTools.Hecho h, File pedido ) {
    generado = h != null && h.archivo != null ? h.archivo : pedido;
    estado.setText( r.f( "t_done", generado.getPath() ) );
    resumen.setText( textoResumen( r, h ).replace( "\n", Text.DELIMITER ) );
    boolean existe = generado.isFile();
    abrirCarpeta.setEnabled( existe );
    abrirArchivo.setEnabled( existe );
  }

  /** El texto del resumen (sin SWT): cuántos de cada estado, la verificación y los avisos. */
  static String textoResumen( Rotulos r, TraducirTools.Hecho h ) {
    StringBuilder b = new StringBuilder();
    if ( h == null ) {
      return "";
    }
    int total = h.exactos + h.aproximados + h.manuales;
    b.append( r.f( "t_counts", total, h.exactos, h.aproximados, h.manuales ) );
    if ( !h.requisitos.isEmpty() ) {
      b.append( '\n' ).append( r.f( "t_requirements", String.join( ", ", h.requisitos ) ) );
    }
    List<String> manuales = new ArrayList<String>();
    for ( Bloque k : h.bloques ) {
      if ( k.estado == Estado.MANUAL ) {
        manuales.add( k.paso + " (" + k.tipo + ")" );
      }
    }
    if ( !manuales.isEmpty() ) {
      b.append( '\n' ).append( r.f( "t_manual_list", String.join( ", ", manuales ) ) );
    }
    Verificacion.Resultado v = h.verificacion;
    if ( v != null ) {
      b.append( "\n\n" ).append( r.s( "t_verif_title" ) ).append( ' ' );
      b.append( Verificacion.IGUAL.equals( v.estado ) ? r.s( "t_verif_equal" ) : Verificacion.DISTINTO.equals(
        v.estado ) ? r.s( "t_verif_different" ) : r.s( "t_verif_not" ) );
      if ( v.motivo != null && !v.motivo.isEmpty() ) {
        b.append( " (" ).append( v.motivo ).append( ')' );
      }
      for ( Verificacion.Salida s : v.salidas ) {
        b.append( '\n' );
        if ( Verificacion.IGUAL.equals( s.estado ) ) {
          b.append( r.f( "t_out_equal", s.paso, Math.max( 0, s.filasPdi ) ) );
        } else if ( Verificacion.DISTINTO.equals( s.estado ) ) {
          b.append( r.f( "t_out_different", s.paso, s.filasPdi, s.filasPython ) );
          int n = 0;
          for ( String x : s.diferencias ) {
            if ( ++n > 5 ) {
              break;
            }
            b.append( "\n      " ).append( x );
          }
        } else {
          b.append( r.f( "t_out_not", s.paso, s.motivo == null ? "?" : s.motivo ) );
        }
      }
    }
    if ( !h.avisos.isEmpty() ) {
      b.append( "\n\n" ).append( r.s( "t_warnings" ) );
      for ( String a : h.avisos ) {
        b.append( "\n  · " ).append( a );
      }
    }
    return b.toString();
  }

  private static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }
}
