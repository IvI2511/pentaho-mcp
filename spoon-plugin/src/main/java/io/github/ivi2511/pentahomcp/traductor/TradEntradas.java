package io.github.ivi2511.pentahomcp.traductor;

import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.csvinput.CsvInputMeta;
import org.pentaho.di.trans.steps.textfileinput.TextFileInputField;

/** Traductores de los steps que leen datos (archivos, bases, filas generadas). */
final class TradEntradas {

  private TradEntradas() {
  }

  static void registrar() {
    Registro.step( TradEntradas::csv, "CsvInput" );
  }

  /** CSV file input → pd.read_csv / spark.read.csv con separador, encabezado, codificación y columnas. */
  static Bloque csv( StepMeta s, CtxTrad ctx ) {
    CsvInputMeta m = (CsvInputMeta) s.getStepMetaInterface();
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String df = ctx.df( s );
    StringBuilder cols = new StringBuilder();
    for ( TextFileInputField f : m.getInputFields() ) {
      cols.append( cols.length() == 0 ? "" : ", " ).append( CtxTrad.lit( f.getName() ) );
    }
    String sep = m.getDelimiter() == null || m.getDelimiter().isEmpty() ? "," : m.getDelimiter();
    String enc = m.getEncoding() == null || m.getEncoding().isEmpty() ? "utf-8" : m.getEncoding();
    if ( ctx.pandas() ) {
      b.l( df + " = pd.read_csv(" + ctx.texto( m.getFilename() ) + ", sep=" + ctx.texto( sep ) + ", encoding="
        + CtxTrad.lit( enc ) + ( m.isHeaderPresent() ? "" : ", header=None" ) + ", dtype=str, keep_default_na=False)" );
      if ( cols.length() > 0 ) {
        b.l( df + ".columns = [" + cols + "]" );
      }
    } else {
      b.l( df + " = spark.read.csv(" + ctx.texto( m.getFilename() ) + ", sep=" + ctx.texto( sep ) + ", encoding="
        + CtxTrad.lit( enc ) + ", header=" + ( m.isHeaderPresent() ? "True" : "False" ) + ")" );
      if ( cols.length() > 0 ) {
        b.l( df + " = " + df + ".toDF(" + cols + ")" );
      }
    }
    b.aproximado( ctx.t( "Esqueleto inicial: falta convertir tipos y formatos.", "Initial skeleton: types and formats "
      + "still to convert.", "Esqueleto inicial: falta converter tipos e formatos." ) );
    b.lee = true;
    b.vista = df;
    return b;
  }
}
