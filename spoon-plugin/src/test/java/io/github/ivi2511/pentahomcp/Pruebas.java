package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Runner de las pruebas (lo lanza spoon-plugin\test.ps1). Sin JUnit y sin lista compartida:
 * <ul>
 * <li>Descubre toda clase {@code Prueba*} (no anidada, no abstracta) bajo la carpeta de clases de
 * prueba, en cualquier paquete.</li>
 * <li>Crea UNA instancia por clase (constructor publico sin argumentos) y corre, en orden alfabetico,
 * sus metodos publicos de instancia sin argumentos cuyo nombre empieza con {@code prueba}.</li>
 * <li>Si la clase tiene {@code public void preparar()} corre antes de todos sus metodos, y
 * {@code public void cerrar()} despues (aunque fallen).</li>
 * <li>Una {@link Verificador.Salteada} cuenta como salteada; cualquier otra excepcion, como falla.</li>
 * <li>Cada metodo tiene un tope de {@code -Dpruebas.timeoutSeg} segundos (600 por defecto).</li>
 * </ul>
 * Argumentos: filtros "Clase" o "Clase.metodo", separados por coma o espacio (vacio = todas).
 * Codigo de salida: 0 todo bien (salteadas incluidas), 1 alguna falla, 2 ningun metodo coincidio.
 *
 * @dueño WP0
 */
public final class Pruebas {

  private int ok;
  private int fallas;
  private int salteadas;
  private final List<String> resumenFallas = new ArrayList<>();

  public static void main( String[] args ) {
    int codigo;
    try {
      codigo = new Pruebas().correr( filtros( args ) );
    } catch ( Throwable t ) {
      t.printStackTrace( System.out );
      codigo = 1;
    }
    System.out.flush();
    System.exit( codigo );
  }

  private static List<String> filtros( String[] args ) {
    List<String> l = new ArrayList<>();
    for ( String a : args ) {
      for ( String p : a.split( "[,;\\s]+" ) ) {
        if ( !p.trim().isEmpty() ) {
          l.add( p.trim() );
        }
      }
    }
    return l;
  }

  private int correr( List<String> filtros ) throws Exception {
    long t0 = System.currentTimeMillis();
    File raiz = new File( Pruebas.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
    List<String> clases = new ArrayList<>();
    buscar( raiz, "", clases );
    Collections.sort( clases, ( a, b ) -> simple( a ).compareTo( simple( b ) ) );
    long timeoutMs = Long.getLong( "pruebas.timeoutSeg", 600L ) * 1000L;

    int metodosCorridos = 0;
    for ( String nombre : clases ) {
      if ( !coincideClase( filtros, simple( nombre ) ) ) {
        continue; // ni se carga: una clase de otro paquete a medio hacer no molesta
      }
      Class<?> c;
      try {
        c = Class.forName( nombre, false, Pruebas.class.getClassLoader() );
      } catch ( Throwable e ) {
        registrar( simple( nombre ), "FALLA", 0, "no carga: " + e, e );
        continue;
      }
      if ( Modifier.isAbstract( c.getModifiers() ) || c.isInterface() || c.getEnclosingClass() != null ) {
        continue;
      }
      List<Method> metodos = new ArrayList<>();
      try {
        for ( Method m : c.getMethods() ) {
          if ( m.getDeclaringClass() == c && m.getName().startsWith( "prueba" ) && m.getParameterCount() == 0
            && !Modifier.isStatic( m.getModifiers() ) && coincide( filtros, c.getSimpleName(), m.getName() ) ) {
            metodos.add( m );
          }
        }
      } catch ( Throwable e ) {
        registrar( c.getSimpleName(), "FALLA", 0, "no se pueden leer sus metodos: " + e, e );
        continue;
      }
      if ( metodos.isEmpty() ) {
        continue;
      }
      Collections.sort( metodos, ( a, b ) -> a.getName().compareTo( b.getName() ) );
      metodosCorridos += metodos.size();
      correrClase( c, metodos, timeoutMs );
    }

    double seg = ( System.currentTimeMillis() - t0 ) / 1000.0;
    System.out.println();
    if ( !resumenFallas.isEmpty() ) {
      System.out.println( "FALLAS:" );
      for ( String f : resumenFallas ) {
        System.out.println( "  " + f );
      }
    }
    System.out.println( String.format( Locale.ROOT, "RESULTADO: %d ok, %d fallas, %d salteadas (%.1f s)", ok, fallas,
      salteadas, seg ) );
    if ( metodosCorridos == 0 ) {
      System.out.println( "Ninguna prueba coincide con " + ( filtros.isEmpty() ? "(todas)" : filtros ) );
      return 2;
    }
    return fallas > 0 ? 1 : 0;
  }

  private void correrClase( Class<?> c, List<Method> metodos, long timeoutMs ) {
    String cn = c.getSimpleName();
    Object inst;
    try {
      inst = c.getConstructor().newInstance();
    } catch ( Throwable e ) {
      for ( Method m : metodos ) {
        registrar( cn + "." + m.getName(), "FALLA", 0, "no se pudo instanciar: " + causa( e ), causa( e ) );
      }
      return;
    }
    Method preparar = publico( c, "preparar" );
    Method cerrar = publico( c, "cerrar" );
    if ( preparar != null ) {
      Throwable e = invocar( inst, preparar, timeoutMs );
      if ( e != null ) {
        boolean salteada = e instanceof Verificador.Salteada;
        for ( Method m : metodos ) {
          registrar( cn + "." + m.getName(), salteada ? "SALTEADA" : "FALLA", 0,
            ( salteada ? "" : "preparar() fallo: " ) + e, salteada ? null : e );
        }
        invocarCerrar( inst, cerrar, timeoutMs, cn );
        return;
      }
    }
    for ( Method m : metodos ) {
      long t = System.currentTimeMillis();
      Throwable e = invocar( inst, m, timeoutMs );
      long ms = System.currentTimeMillis() - t;
      if ( e == null ) {
        registrar( cn + "." + m.getName(), "OK", ms, null, null );
      } else if ( e instanceof Verificador.Salteada ) {
        registrar( cn + "." + m.getName(), "SALTEADA", ms, e.getMessage(), null );
      } else {
        registrar( cn + "." + m.getName(), "FALLA", ms, String.valueOf( e ), e );
      }
    }
    invocarCerrar( inst, cerrar, timeoutMs, cn );
  }

  private void invocarCerrar( Object inst, Method cerrar, long timeoutMs, String cn ) {
    if ( cerrar == null ) {
      return;
    }
    Throwable e = invocar( inst, cerrar, timeoutMs );
    if ( e != null ) {
      registrar( cn + ".cerrar", "FALLA", 0, String.valueOf( e ), e );
    }
  }

  /** Corre el metodo en un hilo aparte con tope de tiempo; devuelve la excepcion o null. */
  private static Throwable invocar( final Object inst, final Method m, long timeoutMs ) {
    final Throwable[] error = new Throwable[ 1 ];
    Thread h = new Thread( () -> {
      try {
        m.invoke( inst );
      } catch ( Throwable t ) {
        error[ 0 ] = causa( t );
      }
    }, "prueba-" + m.getName() );
    h.setDaemon( true );
    h.start();
    try {
      h.join( timeoutMs );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
    }
    if ( h.isAlive() ) {
      h.interrupt();
      return new Verificador.Falla( "tiempo agotado (" + timeoutMs / 1000 + " s)" );
    }
    return error[ 0 ];
  }

  private void registrar( String quien, String estado, long ms, String detalle, Throwable e ) {
    if ( "OK".equals( estado ) ) {
      ok++;
    } else if ( "SALTEADA".equals( estado ) ) {
      salteadas++;
    } else {
      fallas++;
      resumenFallas.add( quien + ": " + detalle );
    }
    System.out.println( String.format( Locale.ROOT, "%-8s %s (%d ms)%s", estado, quien, ms,
      detalle == null ? "" : " - " + detalle ) );
    if ( e != null && !( e instanceof Verificador.Falla ) ) {
      e.printStackTrace( System.out );
    }
    System.out.flush();
  }

  private static Throwable causa( Throwable t ) {
    Throwable c = t;
    while ( ( c instanceof InvocationTargetException || c instanceof ExceptionInInitializerError )
      && c.getCause() != null ) {
      c = c.getCause();
    }
    return c;
  }

  private static Method publico( Class<?> c, String nombre ) {
    try {
      Method m = c.getMethod( nombre );
      return Modifier.isStatic( m.getModifiers() ) ? null : m;
    } catch ( NoSuchMethodException e ) {
      return null;
    }
  }

  private static void buscar( File dir, String paquete, List<String> salida ) {
    File[] hijos = dir.listFiles();
    if ( hijos == null ) {
      return;
    }
    Arrays.sort( hijos );
    for ( File f : hijos ) {
      String n = f.getName();
      if ( f.isDirectory() ) {
        buscar( f, paquete + n + ".", salida );
      } else if ( n.startsWith( "Prueba" ) && n.endsWith( ".class" ) && !n.contains( "$" )
        && !n.equals( "Pruebas.class" ) ) {
        salida.add( paquete + n.substring( 0, n.length() - ".class".length() ) );
      }
    }
  }

  private static String simple( String fqcn ) {
    int i = fqcn.lastIndexOf( '.' );
    return i < 0 ? fqcn : fqcn.substring( i + 1 );
  }

  private static boolean coincideClase( List<String> filtros, String clase ) {
    if ( filtros.isEmpty() ) {
      return true;
    }
    for ( String f : filtros ) {
      String fc = f.contains( "." ) ? f.substring( 0, f.indexOf( '.' ) ) : f;
      if ( fc.equalsIgnoreCase( clase ) ) {
        return true;
      }
    }
    return false;
  }

  private static boolean coincide( List<String> filtros, String clase, String metodo ) {
    if ( filtros.isEmpty() ) {
      return true;
    }
    for ( String f : filtros ) {
      if ( f.equalsIgnoreCase( clase ) || f.equalsIgnoreCase( clase + "." + metodo ) ) {
        return true;
      }
    }
    return false;
  }
}
