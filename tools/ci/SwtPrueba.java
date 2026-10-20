import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Ventana SWT mínima con lo mismo que usa la ventana principal de Spoon (SashForm, Tree, CTabFolder,
 * Canvas pintado con GC), para separar «SWT no dibuja en esta Mac» de «Spoon no arma su ventana».
 * Se corre con el swt.jar de PDI:  java -XstartOnFirstThread -cp libswt/osx64/swt.jar SwtPrueba.java 30000
 * y se cierra sola a los N ms (por defecto 20000).
 */
public class SwtPrueba {
  public static void main( String[] args ) {
    final Display d = new Display();
    final Shell s = new Shell( d );
    s.setText( "SWT " + SWT.getPlatform() + " " + SWT.getVersion() + " / Java " + System.getProperty( "java.version" )
      + " " + System.getProperty( "os.arch" ) );
    s.setLayout( new FillLayout() );
    SashForm sash = new SashForm( s, SWT.HORIZONTAL );
    Tree t = new Tree( sash, SWT.BORDER );
    for ( int i = 1; i <= 12; i++ ) {
      new TreeItem( t, SWT.NONE ).setText( "Item " + i );
    }
    CTabFolder f = new CTabFolder( sash, SWT.BORDER );
    CTabItem ti = new CTabItem( f, SWT.NONE );
    ti.setText( "Canvas" );
    Canvas c = new Canvas( f, SWT.NONE );
    c.addPaintListener( e -> {
      e.gc.setBackground( d.getSystemColor( SWT.COLOR_BLUE ) );
      e.gc.fillRectangle( 20, 20, 240, 110 );
      e.gc.drawText( "dibujado con GC", 30, 150 );
    } );
    ti.setControl( c );
    f.setSelection( 0 );
    sash.setWeights( new int[] { 1, 3 } );
    s.setSize( 900, 600 );
    s.open();
    d.timerExec( Integer.parseInt( args.length > 0 ? args[ 0 ] : "20000" ), s::dispose );
    while ( !s.isDisposed() ) {
      if ( !d.readAndDispatch() ) {
        d.sleep();
      }
    }
    d.dispose();
  }
}
