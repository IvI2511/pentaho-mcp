package io.github.ivi2511.pentahomcp.traductor;

import org.pentaho.di.trans.step.StepMeta;

/** Traductores de los steps que trabajan con filas y flujos (ordenar, agrupar, unir, buscar, repartir). */
final class TradFilas {

  private TradFilas() {
  }

  static void registrar() {
    Registro.step( TradFilas::dummy, "Dummy" );
  }

  /** Dummy (do nothing) → la misma variable. */
  static Bloque dummy( StepMeta s, CtxTrad ctx ) {
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String in = ctx.entradaUnida( s, b );
    b.l( ctx.df( s ) + " = " + ( in == null ? ( ctx.pandas() ? "pd.DataFrame()" : "spark.createDataFrame([], "
      + "T.StructType([]))" ) : in ) );
    b.vista = ctx.df( s );
    return b;
  }
}
