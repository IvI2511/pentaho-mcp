package io.github.ivi2511.pentahomcp.informes.ui;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Grupo «Identidad» (diseno §5.2): autor, institucion y curso, logo (PNG o JPG de hasta 2 MB) y los tres
 * colores con una muestra en cada boton. Edita el {@link Tema} del panel en memoria; «Guardar como
 * predeterminado» lo escribe con Ajustes. Los dialogos (archivo y color) se abren solo por clic. (UI)
 *
 * @dueño WP1
 */
final class GrupoIdentidad {

  /** Tope del logo (§4.1). */
  static final long LOGO_MAX_BYTES = 2L * 1024L * 1024L;

  final Group grupo;
  final Text autor;
  final Text institucion;
  final Text curso;
  final Text logo;
  final Button elegirLogo;
  final Button quitarLogo;
  final Button[] colores = new Button[ 3 ];
  final Button guardar;

  private final PanelInformes panel;
  private final Rotulos r;
  private final Image[] muestras = new Image[ 3 ];
  private boolean cargando;

  GrupoIdentidad( Composite padre, final PanelInformes panel ) {
    this.panel = panel;
    this.r = panel.r;
    grupo = new Group( padre, SWT.NONE );
    grupo.setText( PanelInformes.esc( r.s( "identity" ) ) );
    grupo.setLayout( new GridLayout( 2, false ) );
    grupo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

    autor = campo( "author", "autor" );
    institucion = campo( "institution", "institucion" );
    curso = campo( "course", "curso" );
    autor.addListener( SWT.Modify, e -> {
      if ( !cargando ) {
        panel.tema().identidad.autor = autor.getText().trim();
      }
    } );
    institucion.addListener( SWT.Modify, e -> {
      if ( !cargando ) {
        panel.tema().identidad.institucion = institucion.getText().trim();
      }
    } );
    curso.addListener( SWT.Modify, e -> {
      if ( !cargando ) {
        panel.tema().identidad.curso = curso.getText().trim();
      }
    } );

    new Label( grupo, SWT.NONE ).setText( PanelInformes.esc( r.s( "logo" ) ) );
    Composite fl = fila( 3 );
    logo = new Text( fl, SWT.BORDER | SWT.READ_ONLY );
    GridData gl = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gl.widthHint = 90;
    logo.setLayoutData( gl );
    logo.setData( PanelInformes.CLAVE, "logo" );
    elegirLogo = new Button( fl, SWT.PUSH );
    elegirLogo.setText( PanelInformes.esc( r.s( "choose" ) ) );
    elegirLogo.setToolTipText( r.s( "logo_choose_tip" ) );
    elegirLogo.setData( PanelInformes.CLAVE, "elegir_logo" );
    elegirLogo.addListener( SWT.Selection, e -> elegirLogo() );
    quitarLogo = new Button( fl, SWT.PUSH );
    quitarLogo.setText( PanelInformes.esc( r.s( "remove" ) ) );
    quitarLogo.setToolTipText( r.s( "logo_remove_tip" ) );
    quitarLogo.setData( PanelInformes.CLAVE, "quitar_logo" );
    quitarLogo.addListener( SWT.Selection, e -> {
      Tema t = panel.tema();
      t.logo = null;
      t.logoDatos = null;
      t.logoMime = null;
      logo.setText( "" );
    } );

    new Label( grupo, SWT.NONE ).setText( PanelInformes.esc( r.s( "colors" ) ) );
    Composite fc = fila( 3 );
    String[] tips = { "color_primary", "color_accent", "color_secondary" };
    for ( int i = 0; i < 3; i++ ) {
      final int k = i;
      colores[ i ] = new Button( fc, SWT.PUSH );
      colores[ i ].setToolTipText( r.s( tips[ i ] ) );
      colores[ i ].setData( PanelInformes.CLAVE, "color_" + i );
      colores[ i ].addListener( SWT.Selection, e -> elegirColor( k ) );
    }

    guardar = new Button( grupo, SWT.PUSH );
    guardar.setText( PanelInformes.esc( r.s( "save_default" ) ) );
    guardar.setToolTipText( r.s( "save_default_tip" ) );
    guardar.setData( PanelInformes.CLAVE, "guardar_predeterminado" );
    guardar.setLayoutData( new GridData( SWT.BEGINNING, SWT.CENTER, false, false, 2, 1 ) );
    guardar.addListener( SWT.Selection, e -> panel.guardarPredeterminado() );
  }

  /** Muestra el tema en los controles. */
  void cargar( Tema t ) {
    cargando = true;
    try {
      autor.setText( nn( t.identidad == null ? null : t.identidad.autor ) );
      institucion.setText( nn( t.identidad == null ? null : t.identidad.institucion ) );
      curso.setText( nn( t.identidad == null ? null : t.identidad.curso ) );
      logo.setText( t.logoDatos != null && t.logo != null ? t.logo : "" );
      for ( int i = 0; i < 3; i++ ) {
        pintarMuestra( i, color( t, i ) );
      }
    } finally {
      cargando = false;
    }
  }

  void liberar() {
    for ( int i = 0; i < muestras.length; i++ ) {
      if ( muestras[ i ] != null && !muestras[ i ].isDisposed() ) {
        muestras[ i ].dispose();
      }
      muestras[ i ] = null;
    }
  }

  // ---------------------------------------------------------------- logo y colores (por clic)

  private void elegirLogo() {
    try {
      FileDialog fd = new FileDialog( grupo.getShell(), SWT.OPEN );
      fd.setText( r.s( "logo" ) );
      fd.setFilterExtensions( new String[] { "*.png;*.jpg;*.jpeg" } );
      fd.setFilterNames( new String[] { r.s( "logo_filter" ) } );
      String ruta = fd.open();
      if ( ruta != null ) {
        usarLogo( new File( ruta ) );
      }
    } catch ( Throwable t ) {
      panel.fallo( t );
    }
  }

  /** Lee el logo, chequea PNG o JPG de hasta 2 MB y lo deja en el tema. false si no sirve. */
  boolean usarLogo( File f ) {
    try {
      if ( !f.isFile() || f.length() > LOGO_MAX_BYTES ) {
        panel.estado.error( r.s( "logo_invalid" ) );
        return false;
      }
      byte[] b = Files.readAllBytes( f.toPath() );
      String mime = mime( b );
      if ( mime == null ) {
        panel.estado.error( r.s( "logo_invalid" ) );
        return false;
      }
      Tema t = panel.tema();
      t.logoDatos = b;
      t.logoMime = mime;
      t.logo = "image/png".equals( mime ) ? "logo.png" : "logo.jpg";
      logo.setText( f.getName() );
      return true;
    } catch ( Throwable e ) {
      panel.fallo( e );
      return false;
    }
  }

  /** image/png o image/jpeg por los primeros bytes; null si es otra cosa. */
  static String mime( byte[] b ) {
    if ( b == null || b.length < 4 ) {
      return null;
    }
    if ( ( b[ 0 ] & 0xFF ) == 0x89 && b[ 1 ] == 'P' && b[ 2 ] == 'N' && b[ 3 ] == 'G' ) {
      return "image/png";
    }
    if ( ( b[ 0 ] & 0xFF ) == 0xFF && ( b[ 1 ] & 0xFF ) == 0xD8 && ( b[ 2 ] & 0xFF ) == 0xFF ) {
      return "image/jpeg";
    }
    return null;
  }

  private void elegirColor( int i ) {
    try {
      Tema t = panel.tema();
      ColorDialog cd = new ColorDialog( grupo.getShell() );
      cd.setText( r.s( i == 0 ? "color_primary" : i == 1 ? "color_accent" : "color_secondary" ) );
      cd.setRGB( rgb( color( t, i ) ) );
      RGB elegido = cd.open();
      if ( elegido != null ) {
        ponerColor( i, hex( elegido ) );
      }
    } catch ( Throwable e ) {
      panel.fallo( e );
    }
  }

  /** Cambia un color del tema ("#RRGGBB") y su muestra: 0 primario, 1 acento, 2 secundario. */
  void ponerColor( int i, String hex ) {
    Tema t = panel.tema();
    if ( t.colores == null ) {
      t.colores = new Tema.Colores();
    }
    if ( i == 0 ) {
      t.colores.primario = hex;
    } else if ( i == 1 ) {
      t.colores.acento = hex;
    } else {
      t.colores.secundario = hex;
    }
    pintarMuestra( i, hex );
  }

  private void pintarMuestra( int i, String hex ) {
    Image vieja = muestras[ i ];
    Image img = new Image( grupo.getDisplay(), 28, 14 );
    GC gc = new GC( img );
    Color c = new Color( grupo.getDisplay(), rgb( hex ) );
    try {
      gc.setBackground( c );
      gc.fillRectangle( 0, 0, 28, 14 );
      gc.setForeground( grupo.getDisplay().getSystemColor( SWT.COLOR_WIDGET_NORMAL_SHADOW ) );
      gc.drawRectangle( 0, 0, 27, 13 );
    } finally {
      gc.dispose();
      c.dispose();
    }
    muestras[ i ] = img;
    colores[ i ].setImage( img );
    colores[ i ].setData( PanelInformes.CLAVE + ".color", hex );
    if ( vieja != null && !vieja.isDisposed() ) {
      vieja.dispose();
    }
  }

  private static String color( Tema t, int i ) {
    Tema.Colores c = t.colores == null ? new Tema.Colores() : t.colores;
    String v = i == 0 ? c.primario : i == 1 ? c.acento : c.secundario;
    String def = i == 0 ? Tema.PRIMARIO : i == 1 ? Tema.ACENTO : Tema.SECUNDARIO;
    return v == null || !v.trim().matches( "#?[0-9A-Fa-f]{6}" ) ? def : v.trim();
  }

  static RGB rgb( String hex ) {
    String h = hex == null ? "" : hex.trim();
    if ( h.startsWith( "#" ) ) {
      h = h.substring( 1 );
    }
    try {
      int v = Integer.parseInt( h, 16 );
      return new RGB( ( v >> 16 ) & 0xFF, ( v >> 8 ) & 0xFF, v & 0xFF );
    } catch ( NumberFormatException e ) {
      return new RGB( 0x24, 0x3B, 0x53 );
    }
  }

  static String hex( RGB c ) {
    return String.format( Locale.ROOT, "#%02X%02X%02X", c.red, c.green, c.blue );
  }

  private Text campo( String clave, String id ) {
    new Label( grupo, SWT.NONE ).setText( PanelInformes.esc( r.s( clave ) ) );
    Text t = new Text( grupo, SWT.BORDER );
    GridData gd = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gd.widthHint = 140;
    t.setLayoutData( gd );
    t.setData( PanelInformes.CLAVE, id );
    return t;
  }

  private Composite fila( int columnas ) {
    Composite c = new Composite( grupo, SWT.NONE );
    GridLayout gl = new GridLayout( columnas, false );
    gl.marginWidth = 0;
    gl.marginHeight = 0;
    c.setLayout( gl );
    c.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    return c;
  }

  private static String nn( String s ) {
    return s == null ? "" : s;
  }
}
