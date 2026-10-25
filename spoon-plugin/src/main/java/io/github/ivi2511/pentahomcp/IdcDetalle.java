package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.core.Props;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Lo que comparten los diálogos «Revisar calidad…» ({@link IdcCalidad}) y «Robustecer…» ({@link IdcRobustez}) del
 * menú IDC: el texto con formato del panel de detalle (títulos, párrafos que se ajustan, monoespaciada solo para
 * código, rutas y variables), la ventana de detalle de un hallazgo o una receta (doble clic o Intro en la tabla),
 * los tooltips con el texto entero de cada celda, y el tamaño de la ventana y el ancho de las columnas recordados por
 * usuario (las propiedades de Spoon, .spoonrc; nunca «siempre encima»). (UI)
 */
final class IdcDetalle {

  /** Prefijo de las preferencias en las propiedades de Spoon. */
  static final String PREFIJO = "PentahoLive.idc.";
  /** El manual de la skill en el repositorio (los enlaces «Más información»). */
  static final String MANUAL = "https://github.com/IvI2511/pentaho-mcp/blob/main/claude/skills/pentaho-etl/manual/";

  /** Cómo se abre una ventana de detalle: centrada sobre el diálogo. Las pruebas la abren fuera de la pantalla. */
  static volatile BiConsumer<Shell, Shell> abridor = IdcMenu::abrirShell;
  /** Lo que se copia al portapapeles (las pruebas lo leen aquí sin tocar el portapapeles del usuario). */
  static volatile java.util.function.BiConsumer<Display, String> portapapeles = IdcDetalle::copiarDeVerdad;
  /** Cómo se abre un enlace (las pruebas no abren el navegador). */
  static volatile java.util.function.Consumer<String> navegador = u -> Program.launch( u );

  private IdcDetalle() {
  }

  // ================================================================ preferencias por usuario

  /** Sin Spoon (pruebas): en memoria. */
  private static final Map<String, String> MEMORIA = new HashMap<String, String>();

  static String pref( String clave ) {
    try {
      if ( Props.isInitialized() ) {
        return Props.getInstance().getCustomParameter( PREFIJO + clave, null );
      }
    } catch ( Throwable ignorada ) {
      // sin propiedades: memoria
    }
    synchronized ( MEMORIA ) {
      return MEMORIA.get( clave );
    }
  }

  static void guardarPref( String clave, String valor ) {
    try {
      if ( Props.isInitialized() ) {
        Props.getInstance().setCustomParameter( PREFIJO + clave, valor );
        return;
      }
    } catch ( Throwable ignorada ) {
      // sin propiedades: memoria
    }
    synchronized ( MEMORIA ) {
      MEMORIA.put( clave, valor );
    }
  }

  /** Escribe .spoonrc (al cerrar un diálogo). */
  static void persistir() {
    try {
      if ( Props.isInitialized() ) {
        Props.getInstance().saveProps();
      }
    } catch ( Throwable ignorada ) {
      // no es grave: se guarda al salir de Spoon
    }
  }

  static int[] enteros( String s ) {
    if ( s == null || s.trim().isEmpty() ) {
      return null;
    }
    String[] p = s.split( "," );
    int[] r = new int[ p.length ];
    try {
      for ( int i = 0; i < p.length; i++ ) {
        r[ i ] = Integer.parseInt( p[ i ].trim() );
      }
    } catch ( NumberFormatException e ) {
      return null;
    }
    return r;
  }

  static String texto( int... v ) {
    StringBuilder b = new StringBuilder();
    for ( int x : v ) {
      b.append( b.length() == 0 ? "" : "," ).append( x );
    }
    return b.toString();
  }

  /**
   * El tamaño de la Shell: el recordado (si cabe en la pantalla) o el de por defecto; se guarda al cerrarla. No la
   * mueve: la posición la pone quien la abre.
   */
  static void tamano( final Shell s, final String clave, int ancho, int alto ) {
    int[] t = enteros( pref( clave + ".size" ) );
    Rectangle pantalla = s.getDisplay().getClientArea();
    if ( t != null && t.length == 2 && t[ 0 ] >= 480 && t[ 1 ] >= 320 ) {
      ancho = Math.min( t[ 0 ], Math.max( 480, pantalla.width ) );
      alto = Math.min( t[ 1 ], Math.max( 320, pantalla.height ) );
    }
    s.setSize( ancho, alto );
    s.addListener( SWT.Dispose, e -> {
      if ( !s.getMaximized() ) {
        Point p = s.getSize();
        guardarPref( clave + ".size", texto( p.x, p.y ) );
      }
      persistir();
    } );
  }

  /**
   * Los anchos de las columnas: los recordados o los de por defecto; la columna elástica (-1 = ninguna) se queda con
   * el espacio que sobra cuando la tabla cambia de tamaño. Se guardan cuando la persona mueve una columna.
   */
  static void columnas( final Table t, final String clave, final int elastica ) {
    final TableColumn[] cs = t.getColumns();
    int[] w = enteros( pref( clave + ".cols" ) );
    if ( w != null && w.length == cs.length ) {
      for ( int i = 0; i < cs.length; i++ ) {
        if ( w[ i ] >= 16 ) {
          cs[ i ].setWidth( w[ i ] );
        }
      }
    }
    final boolean[] ajustando = new boolean[ 1 ];
    for ( TableColumn c : cs ) {
      c.addListener( SWT.Resize, e -> {
        if ( ajustando[ 0 ] ) {
          return;
        }
        int[] a = new int[ cs.length ];
        for ( int i = 0; i < cs.length; i++ ) {
          a[ i ] = cs[ i ].getWidth();
        }
        guardarPref( clave + ".cols", texto( a ) );
      } );
    }
    if ( elastica >= 0 && elastica < cs.length ) {
      t.addListener( SWT.Resize, e -> {
        int resto = t.getClientArea().width;
        for ( int i = 0; i < cs.length; i++ ) {
          if ( i != elastica ) {
            resto -= cs[ i ].getWidth();
          }
        }
        int minimo = 160;
        ajustando[ 0 ] = true;
        try {
          cs[ elastica ].setWidth( Math.max( minimo, resto - 2 ) );
        } finally {
          ajustando[ 0 ] = false;
        }
      } );
    }
  }

  /** Tooltip con el texto entero de la celda bajo el ratón (lo que la columna corta con «…»). */
  static void tooltips( final Table t ) {
    t.addListener( SWT.MouseHover, e -> {
      TableItem it = t.getItem( new Point( e.x, e.y ) );
      String tip = null;
      if ( it != null ) {
        for ( int i = 0; i < t.getColumnCount(); i++ ) {
          if ( it.getBounds( i ).contains( e.x, e.y ) ) {
            Object completo = it.getData( "tip" + i );
            tip = completo instanceof String ? (String) completo : it.getText( i );
            break;
          }
        }
      }
      String nuevo = tip == null || tip.trim().isEmpty() ? null : partir( tip, 90 );
      if ( nuevo == null ? t.getToolTipText() != null : !nuevo.equals( t.getToolTipText() ) ) {
        t.setToolTipText( nuevo );
      }
    } );
  }

  /** Corta un texto largo en líneas de unos n caracteres (por espacios) para un tooltip. */
  static String partir( String s, int n ) {
    StringBuilder b = new StringBuilder();
    for ( String linea : s.split( "\n", -1 ) ) {
      int col = 0;
      if ( b.length() > 0 ) {
        b.append( '\n' );
      }
      for ( String w : linea.split( " " ) ) {
        if ( col > 0 && col + w.length() + 1 > n ) {
          b.append( '\n' );
          col = 0;
        } else if ( col > 0 ) {
          b.append( ' ' );
          col++;
        }
        b.append( w );
        col += w.length();
      }
    }
    return b.toString();
  }

  /** Doble clic o Intro en una fila de la tabla. */
  static void alAbrir( final Table t, final Runnable r ) {
    t.addListener( SWT.DefaultSelection, e -> r.run() );
  }

  // ================================================================ portapapeles y enlaces

  static void copiar( Display d, String texto ) {
    portapapeles.accept( d, texto );
  }

  private static void copiarDeVerdad( Display d, String texto ) {
    Clipboard c = new Clipboard( d );
    try {
      c.setContents( new Object[] { texto }, new Transfer[] { TextTransfer.getInstance() } );
    } finally {
      c.dispose();
    }
  }

  // ================================================================ texto con formato

  /**
   * Un texto con formato para un StyledText: título, secciones, párrafos (con ${VARIABLES} en monoespaciada), código
   * y viñetas. {@link #plano()} da el texto sin formato (para las pruebas y el portapapeles).
   */
  static final class Texto {
    static final int NORMAL = 0;
    static final int TITULO = 1;
    static final int SECCION = 2;
    static final int CODIGO = 3;
    static final int NEGRITA = 4;
    static final int SUAVE = 5;
    static final int ROJO = 6;
    static final int AMBAR = 7;
    static final int VERDE = 8;

    private static final Pattern VARIABLE = Pattern.compile( "\\$\\{[^}\\s]+\\}|%%[^%\\s]+%%" );

    final StringBuilder b = new StringBuilder();
    /** {inicio, largo, estilo}. */
    final List<int[]> estilos = new ArrayList<int[]>();

    private void con( String s, int estilo ) {
      if ( s == null || s.isEmpty() ) {
        return;
      }
      int i = b.length();
      b.append( s );
      if ( estilo != NORMAL ) {
        estilos.add( new int[] { i, s.length(), estilo } );
      }
    }

    private void separar() {
      if ( b.length() > 0 && !( b.length() >= 2 && b.charAt( b.length() - 1 ) == '\n' && b.charAt( b.length() - 2 )
        == '\n' ) ) {
        b.append( b.charAt( b.length() - 1 ) == '\n' ? "\n" : "\n\n" );
      }
    }

    Texto titulo( String s ) {
      separar();
      con( s, TITULO );
      b.append( '\n' );
      return this;
    }

    Texto seccion( String s ) {
      separar();
      con( s, SECCION );
      b.append( '\n' );
      return this;
    }

    /** Un párrafo: las ${VARIABLES} van en monoespaciada. */
    Texto parrafo( String s ) {
      if ( s == null || s.trim().isEmpty() ) {
        return this;
      }
      if ( b.length() > 0 && b.charAt( b.length() - 1 ) != '\n' ) {
        b.append( '\n' );
      }
      enLinea( s, NORMAL );
      b.append( '\n' );
      return this;
    }

    /** Un párrafo en un estilo (SUAVE, NEGRITA, ROJO, AMBAR, VERDE). */
    Texto parrafo( String s, int estilo ) {
      if ( s == null || s.trim().isEmpty() ) {
        return this;
      }
      if ( b.length() > 0 && b.charAt( b.length() - 1 ) != '\n' ) {
        b.append( '\n' );
      }
      enLinea( s, estilo );
      b.append( '\n' );
      return this;
    }

    private void enLinea( String s, int estilo ) {
      Matcher m = VARIABLE.matcher( s );
      int desde = 0;
      while ( m.find() ) {
        con( s.substring( desde, m.start() ), estilo );
        con( m.group(), CODIGO );
        desde = m.end();
      }
      con( s.substring( desde ), estilo );
    }

    /** Líneas de código, rutas o SQL: monoespaciada, cada una en su línea. */
    Texto codigo( String s ) {
      if ( s == null || s.isEmpty() ) {
        return this;
      }
      if ( b.length() > 0 && b.charAt( b.length() - 1 ) != '\n' ) {
        b.append( '\n' );
      }
      for ( String l : s.split( "\n" ) ) {
        con( l, CODIGO );
        b.append( '\n' );
      }
      return this;
    }

    Texto vinetas( List<String> l ) {
      for ( String x : l ) {
        if ( b.length() > 0 && b.charAt( b.length() - 1 ) != '\n' ) {
          b.append( '\n' );
        }
        b.append( "•  " );
        enLinea( x, NORMAL );
        b.append( '\n' );
      }
      return this;
    }

    /** «clave: valor», la clave en negrita y el valor en monoespaciada. */
    Texto dato( String k, String v ) {
      if ( b.length() > 0 && b.charAt( b.length() - 1 ) != '\n' ) {
        b.append( '\n' );
      }
      con( k + ": ", NEGRITA );
      con( v, CODIGO );
      b.append( '\n' );
      return this;
    }

    String plano() {
      return b.toString().replaceAll( "\\s+$", "" );
    }

    /** Pinta el texto en el StyledText (fuentes y colores de {@link Estilos}). */
    void pintar( StyledText st, Estilos e ) {
      String t = plano();
      st.setText( t );
      List<StyleRange> rs = new ArrayList<StyleRange>();
      for ( int[] x : estilos ) {
        if ( x[ 0 ] >= t.length() ) {
          continue;
        }
        StyleRange r = new StyleRange();
        r.start = x[ 0 ];
        r.length = Math.min( x[ 1 ], t.length() - x[ 0 ] );
        switch ( x[ 2 ] ) {
          case TITULO:
            r.font = e.titulo;
            break;
          case SECCION:
            r.font = e.seccion;
            r.foreground = e.azul;
            break;
          case CODIGO:
            r.font = e.mono;
            r.background = e.fondoCodigo;
            break;
          case NEGRITA:
            r.font = e.negrita;
            break;
          case SUAVE:
            r.foreground = e.gris;
            break;
          case ROJO:
            r.font = e.negrita;
            r.foreground = e.rojo;
            break;
          case AMBAR:
            r.font = e.negrita;
            r.foreground = e.ambar;
            break;
          case VERDE:
            r.font = e.negrita;
            r.foreground = e.verde;
            break;
          default:
            continue;
        }
        rs.add( r );
      }
      st.setStyleRanges( rs.toArray( new StyleRange[ 0 ] ) );
    }
  }

  /** Fuentes y colores de un StyledText de detalle; se liberan con él. */
  static final class Estilos {
    final Font titulo;
    final Font seccion;
    final Font negrita;
    final Font mono;
    final Color azul;
    final Color gris;
    final Color rojo;
    final Color ambar;
    final Color verde;
    final Color fondoCodigo;

    Estilos( Control c ) {
      Display d = c.getDisplay();
      titulo = escalada( c, 1.35, SWT.BOLD );
      seccion = escalada( c, 1.05, SWT.BOLD );
      negrita = escalada( c, 1.0, SWT.BOLD );
      Font m;
      try {
        FontData[] fd = JFaceResources.getTextFont().getFontData();
        FontData[] base = c.getFont().getFontData();
        for ( FontData f : fd ) {
          f.setHeight( base.length > 0 ? base[ 0 ].getHeight() : f.getHeight() );
        }
        m = new Font( d, fd );
      } catch ( Throwable t ) {
        m = new Font( d, "Consolas", c.getFont().getFontData()[ 0 ].getHeight(), SWT.NORMAL );
      }
      mono = m;
      azul = new Color( d, 25, 75, 140 );
      gris = new Color( d, 95, 95, 95 );
      rojo = new Color( d, 175, 25, 25 );
      ambar = new Color( d, 150, 90, 0 );
      verde = new Color( d, 25, 115, 50 );
      fondoCodigo = new Color( d, 238, 240, 243 );
      c.addListener( SWT.Dispose, e -> {
        for ( Font f : new Font[] { titulo, seccion, negrita, mono } ) {
          f.dispose();
        }
        for ( Color x : new Color[] { azul, gris, rojo, ambar, verde, fondoCodigo } ) {
          x.dispose();
        }
      } );
    }

    private static Font escalada( Control c, double escala, int estilo ) {
      FontData[] fd = c.getFont().getFontData();
      for ( FontData f : fd ) {
        f.setHeight( (int) Math.round( f.getHeight() * escala ) );
        f.setStyle( estilo );
      }
      return new Font( c.getDisplay(), fd );
    }
  }

  /** Un StyledText de solo lectura que ajusta las líneas, con márgenes, para mostrar un {@link Texto}. */
  static StyledText panel( Composite padre ) {
    StyledText st = new StyledText( padre, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL );
    st.setMargins( 10, 8, 10, 8 );
    st.setLineSpacing( 2 );
    st.setData( "estilos", new Estilos( st ) );
    st.setBackground( st.getDisplay().getSystemColor( SWT.COLOR_LIST_BACKGROUND ) );
    return st;
  }

  static void mostrar( StyledText st, Texto t ) {
    t.pintar( st, (Estilos) st.getData( "estilos" ) );
    st.setTopIndex( 0 );
    st.setTopPixel( 0 );
  }

  // ================================================================ la ventana de detalle

  /**
   * La ventana de detalle de un hallazgo o de una receta: título, insignia (gravedad o nivel) y dónde, con «Ir al
   * step»; el texto con formato; un valor editable si el arreglo lo pide; un enlace al manual; y las acciones de ese
   * elemento (aplicar, silenciar, pedírselo a Claude). La llena quien la abre. No modal y nunca «siempre encima».
   */
  static final class Ventana {
    final Shell shell;
    final Label titulo;
    final Label insignia;
    final Label donde;
    final Button irAlStep;
    final StyledText cuerpo;
    final Composite filaValor;
    final Label etiquetaValor;
    final Text valor;
    final Button verCambios;
    final Link enlace;
    final Label estado;
    final Button aplicar;
    final Button silenciar;
    final Button claude;
    final Button cerrar;
    final Estilos estilos;
    private String url;

    Ventana( Shell padre, Rotulos r, String claveTamano, String tituloVentana ) {
      shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MODELESS );
      shell.setText( tituloVentana );
      final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
      if ( ico != null ) {
        shell.setImage( ico );
        shell.addListener( SWT.Dispose, e -> ico.dispose() );
      }
      GridLayout gl = new GridLayout( 1, false );
      gl.marginWidth = 14;
      gl.marginHeight = 12;
      gl.verticalSpacing = 8;
      shell.setLayout( gl );

      titulo = new Label( shell, SWT.WRAP );
      titulo.setFont( IdcCalidad.negrita( titulo, 1.45 ) );
      GridData gt = new GridData( SWT.FILL, SWT.TOP, true, false );
      gt.widthHint = 600;
      titulo.setLayoutData( gt );

      Composite fila = new Composite( shell, SWT.NONE );
      fila.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
      GridLayout gf = new GridLayout( 3, false );
      gf.marginWidth = 0;
      gf.marginHeight = 0;
      gf.horizontalSpacing = 12;
      fila.setLayout( gf );
      insignia = new Label( fila, SWT.NONE );
      insignia.setFont( IdcCalidad.negrita( insignia, 1.0 ) );
      donde = new Label( fila, SWT.NONE );
      donde.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
      irAlStep = new Button( fila, SWT.PUSH );
      irAlStep.setText( r.s( "d_goto" ) );
      irAlStep.setToolTipText( r.s( "d_goto_tip" ) );

      cuerpo = panel( shell );
      estilos = (Estilos) cuerpo.getData( "estilos" );
      GridData gc = new GridData( SWT.FILL, SWT.FILL, true, true );
      gc.heightHint = 340;
      cuerpo.setLayoutData( gc );

      filaValor = new Composite( shell, SWT.NONE );
      GridData gv = new GridData( SWT.FILL, SWT.TOP, true, false );
      gv.exclude = true;
      filaValor.setLayoutData( gv );
      filaValor.setVisible( false );
      GridLayout gvl = new GridLayout( 3, false );
      gvl.marginWidth = 0;
      gvl.marginHeight = 0;
      filaValor.setLayout( gvl );
      etiquetaValor = new Label( filaValor, SWT.NONE );
      valor = new Text( filaValor, SWT.BORDER | SWT.SINGLE );
      valor.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
      verCambios = new Button( filaValor, SWT.PUSH );
      verCambios.setText( r.s( "d_preview" ) );

      enlace = new Link( shell, SWT.NONE );
      enlace.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
      enlace.addListener( SWT.Selection, e -> {
        if ( url != null ) {
          navegador.accept( url );
        }
      } );

      estado = new Label( shell, SWT.WRAP );
      estado.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

      Composite bo = new Composite( shell, SWT.NONE );
      bo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
      GridLayout gb = new GridLayout( 4, false );
      gb.marginWidth = 0;
      bo.setLayout( gb );
      aplicar = new Button( bo, SWT.PUSH );
      silenciar = new Button( bo, SWT.PUSH );
      silenciar.setText( r.s( "q_suppress" ) );
      silenciar.setToolTipText( r.s( "q_suppress_tip" ) );
      claude = new Button( bo, SWT.PUSH );
      claude.setText( r.s( "d_claude" ) );
      claude.setToolTipText( r.s( "d_claude_tip" ) );
      cerrar = new Button( bo, SWT.PUSH );
      cerrar.setText( r.s( "close" ) );
      GridData gce = new GridData( SWT.RIGHT, SWT.CENTER, true, false );
      gce.widthHint = 96;
      cerrar.setLayoutData( gce );
      cerrar.addListener( SWT.Selection, e -> shell.close() );
      shell.addListener( SWT.Traverse, e -> {
        if ( e.detail == SWT.TRAVERSE_ESCAPE ) {
          shell.close();
        }
      } );
      tamano( shell, claveTamano, 760, 620 );
    }

    /** La insignia: texto y color (Texto.ROJO, AMBAR, VERDE o SUAVE). */
    void insignia( String texto, int color ) {
      insignia.setText( " " + texto + " " );
      insignia.setForeground( color == Texto.ROJO ? estilos.rojo : color == Texto.AMBAR ? estilos.ambar
        : color == Texto.VERDE ? estilos.verde : estilos.gris );
    }

    /** Muestra u oculta la fila del valor editable. */
    void conValor( String etiqueta, String v ) {
      GridData g = (GridData) filaValor.getLayoutData();
      boolean ver = etiqueta != null;
      g.exclude = !ver;
      filaValor.setVisible( ver );
      if ( ver ) {
        etiquetaValor.setText( etiqueta );
        valor.setText( v == null ? "" : v );
      }
      shell.layout( true, true );
    }

    void enlace( String texto, String u ) {
      url = u;
      enlace.setText( u == null ? "" : "<a>" + texto.replace( "&", "&&" ).replace( "<", "" ) + "</a>" );
    }

    void mostrar( Texto t ) {
      IdcDetalle.mostrar( cuerpo, t );
    }

    /** Oculta un botón (sin dejar el hueco). */
    static void ocultar( Button b, boolean ocultar ) {
      GridData g = b.getLayoutData() instanceof GridData ? (GridData) b.getLayoutData() : new GridData();
      g.exclude = ocultar;
      b.setLayoutData( g );
      b.setVisible( !ocultar );
    }

    void abrir( Shell padre ) {
      shell.layout( true, true );
      abridor.accept( shell, padre );
    }
  }
}
