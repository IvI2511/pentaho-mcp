package io.github.ivi2511.pentahomcp.diff;

import java.io.File;
import java.io.PrintStream;

/** Para las pruebas: corre un comando de {@link LineaComandos} en el proceso, con su carpeta de trabajo y salidas. */
public final class LineaComandosPrueba {

  private LineaComandosPrueba() {
  }

  public static int correr( File cwd, PrintStream out, PrintStream err, String... args ) {
    LineaComandos l = new LineaComandos( out, err );
    l.cwd = cwd;
    return l.correr( args );
  }
}
