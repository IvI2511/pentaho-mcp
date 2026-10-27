package io.github.ivi2511.pentahomcp.traductor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;

/**
 * Manejo de errores como PDI (hop de error): las filas cuya conversión de texto a número o fecha falla se separan con
 * una máscara vectorizada y van al hop de error, con los campos de error que pide el StepErrorMeta.
 */
final class Errores {

  private static final Pattern A_FECHA = Pattern.compile( "^F\\.to_timestamp\\((.*), (\"[^\"]*\")\\)$" );
  private static final Pattern CAST = Pattern.compile( "^(.*)\\.cast\\(\"(long|double)\"\\)$" );

  private Errores() {
  }

  /**
   * Condición «la conversión {@code e} del texto {@code col} falla» (texto no vacío que no se convierte), o null si
   * la conversión no es de las que se saben comprobar.
   */
  static String fallo( String col, String e, CtxTrad ctx ) {
    if ( ctx.pandas() ) {
      String c = e.endsWith( ".astype(\"Int64\")" ) ? e.substring( 0, e.length() - 16 ) : e;
      if ( !( c.startsWith( "pd.to_numeric(" ) || c.startsWith( "pd.to_datetime(" ) ) || !c.endsWith( ")" ) ) {
        return null;
      }
      c = c.substring( 0, c.length() - 1 ) + ", errors=\"coerce\")";
      return "(" + col + ".notna() & (" + col + " != \"\") & " + c + ".isna())";
    }
    Matcher m = A_FECHA.matcher( e );
    if ( m.matches() ) {
      return "(" + col + ".isNotNull() & (" + col + " != \"\") & F.try_to_timestamp(" + m.group( 1 ) + ", F.lit("
        + m.group( 2 ) + ")).isNull())";
    }
    m = CAST.matcher( e );
    if ( m.matches() ) {
      String re = m.group( 2 ).equals( "long" ) ? "^[+-]?[0-9]+$"
        : "^[+-]?([0-9]+[.]?[0-9]*|[.][0-9]+)([eE][+-]?[0-9]+)?$";
      return "(" + col + ".isNotNull() & (" + col + " != \"\") & ~F.trim(" + m.group( 1 ) + ").rlike(r\"" + re
        + "\"))";
    }
    return null;
  }

  /**
   * Separa {@code df}: las filas que cumplen alguna condición de {@code fallos} van al DataFrame de error (con los
   * campos de error de PDI) y {@code df} se queda con las buenas. Registra la salida de error del step.
   */
  static void separar( StepMeta s, CtxTrad ctx, Bloque b, String df, List<String> fallos, String codigo ) {
    boolean pd = ctx.pandas();
    String err = ctx.dfError( s );
    String malo = ctx.nuevo( "malo_" + CtxTrad.ident( s.getName() ) );
    String cond = String.join( "\n    | ", fallos );
    if ( pd ) {
      b.l( malo + " = (" + cond + ").fillna(False).astype(bool)" );
      b.l( err + " = " + df + "[" + malo + "].reset_index(drop=True)" );
      b.l( df + " = " + df + "[~" + malo + "].reset_index(drop=True)" );
    } else {
      b.l( malo + " = F.coalesce(" + cond + ", F.lit(False))" );
      b.l( err + " = " + df + ".filter(" + malo + ")" );
      b.l( df + " = " + df + ".filter(~" + malo + ")" );
    }
    camposError( s, ctx, b, err, codigo );
    ctx.ponerSalidaError( s, err );
    b.nota( ctx.t( "Las filas cuyo texto no se puede convertir van al hop de error (como en PDI) y el resto sigue.",
      "Rows whose text cannot be converted go to the error hop (as in PDI) and the rest go on.",
      "As linhas cujo texto não pode ser convertido vão para o hop de erro (como no PDI) e o resto segue." ) );
  }

  /** Añade los campos de error que pide el StepErrorMeta (nº de errores, descripción, campos, código). */
  static void camposError( StepMeta s, CtxTrad ctx, Bloque b, String err, String codigo ) {
    StepErrorMeta em = s.getStepErrorMeta();
    String desc = ctx.t( "error de conversión", "conversion error", "erro de conversão" );
    poner( ctx, b, err, em.getNrErrorsValuename(), "1", "1" );
    poner( ctx, b, err, em.getErrorDescriptionsValuename(), CtxTrad.lit( desc ), CtxTrad.lit( desc ) );
    poner( ctx, b, err, em.getErrorFieldsValuename(), "None", "None" );
    poner( ctx, b, err, em.getErrorCodesValuename(), CtxTrad.lit( codigo ), CtxTrad.lit( codigo ) );
  }

  private static void poner( CtxTrad ctx, Bloque b, String err, String campo, String valorPd, String valorSpark ) {
    if ( campo == null || campo.trim().isEmpty() ) {
      return;
    }
    if ( ctx.pandas() ) {
      b.l( err + "[" + CtxTrad.lit( campo ) + "] = " + valorPd );
    } else {
      String v = valorSpark.equals( "None" ) ? "F.lit(None).cast(\"string\")" : "F.lit(" + valorSpark + ")";
      b.l( err + " = " + err + ".withColumn(" + CtxTrad.lit( campo ) + ", " + v + ")" );
    }
  }
}
