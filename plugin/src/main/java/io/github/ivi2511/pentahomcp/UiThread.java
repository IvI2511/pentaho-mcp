package io.github.ivi2511.pentahomcp;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.swt.widgets.Display;
import org.pentaho.di.ui.spoon.Spoon;

/**
 * Ejecuta codigo en el hilo de UI de SWT desde los hilos del servidor HTTP.
 *
 * Usa Display.asyncExec + CompletableFuture con timeout en vez de syncExec: si el usuario tiene un
 * dialogo modal abierto el runnable igual corre (los modales hacen readAndDispatch), pero si la UI
 * esta trabada el hilo HTTP no queda colgado para siempre.
 */
final class UiThread {

  private UiThread() {
  }

  static Spoon spoon() {
    try {
      return Spoon.getInstance();
    } catch ( Throwable t ) {
      return null; // kettle-ui-swt no disponible (no deberia pasar dentro de Spoon)
    }
  }

  static Display display() {
    Spoon s = spoon();
    if ( s == null ) {
      return null;
    }
    Display d = s.getDisplay();
    return ( d == null || d.isDisposed() ) ? null : d;
  }

  static <T> T call( final Callable<T> task, long timeoutMs ) throws Exception {
    Display d = display();
    if ( d == null ) {
      throw new IllegalStateException( "Spoon/Display no disponible" );
    }
    if ( d.getThread() == Thread.currentThread() ) {
      return task.call();
    }
    final CompletableFuture<T> f = new CompletableFuture<T>();
    d.asyncExec( new Runnable() {
      @Override
      public void run() {
        try {
          f.complete( task.call() );
        } catch ( Throwable t ) {
          f.completeExceptionally( t );
        }
      }
    } );
    try {
      return f.get( timeoutMs, TimeUnit.MILLISECONDS );
    } catch ( ExecutionException e ) {
      Throwable c = e.getCause();
      if ( c instanceof Exception ) {
        throw (Exception) c;
      }
      throw new RuntimeException( c );
    } catch ( TimeoutException e ) {
      throw new TimeoutException( "La UI de Spoon no respondio en " + timeoutMs + " ms" );
    }
  }
}
