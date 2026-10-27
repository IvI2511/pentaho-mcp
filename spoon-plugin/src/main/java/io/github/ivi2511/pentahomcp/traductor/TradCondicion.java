package io.github.ivi2511.pentahomcp.traductor;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.pentaho.di.core.Condition;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaAndData;
import org.pentaho.di.core.row.ValueMetaInterface;

/**
 * Traduce un árbol de condiciones de PDI ({@link Condition}: Filter rows, Join rows...) a una expresión booleana de
 * pandas (una Series) o de PySpark (una Column), con la semántica de PDI: un nulo es menor que cualquier valor y dos
 * nulos son iguales; los operadores se aplican de izquierda a derecha sin precedencia (como los evalúa PDI); la
 * constante se convierte al tipo del campo de la izquierda. Cada condición atómica da siempre True o False (nunca
 * nulo), así la negación funciona como en PDI.
 */
public final class TradCondicion {

  private TradCondicion() {
  }

  /** Expresión de la condición sobre el DataFrame {@code df} (pandas) o sobre columnas (PySpark). */
  public static String expr( Condition c, String df, RowMetaInterface campos, CtxTrad ctx, Bloque b ) {
    if ( c == null || c.isEmpty() ) {
      return verdad( ctx, df );
    }
    String e;
    if ( c.isAtomic() ) {
      e = atomica( c, df, campos, ctx, b );
    } else {
      e = null;
      for ( int i = 0; i < c.nrConditions(); i++ ) {
        Condition h = c.getCondition( i );
        String x = expr( h, df, campos, ctx, b );
        if ( e == null ) {
          e = x;
          continue;
        }
        switch ( h.getOperator() ) {
          case Condition.OPERATOR_OR:
            e = "(" + e + " | " + x + ")";
            break;
          case Condition.OPERATOR_AND_NOT:
            e = "(" + e + " & ~" + x + ")";
            break;
          case Condition.OPERATOR_OR_NOT:
            e = "(" + e + " | ~" + x + ")";
            break;
          case Condition.OPERATOR_XOR: // en PySpark las Column no tienen ^
            e = ctx.pandas() ? "(" + e + " ^ " + x + ")" : "(" + e + " != " + x + ")";
            break;
          default:
            e = "(" + e + " & " + x + ")";
        }
      }
      if ( e == null ) {
        e = verdad( ctx, df );
      }
    }
    return c.isNegated() ? "~" + paren( e ) : e;
  }

  private static String verdad( CtxTrad ctx, String df ) {
    return ctx.pandas() ? "pd.Series(True, index=" + df + ".index)" : "F.lit(True)";
  }

  private static String paren( String e ) {
    return e.startsWith( "(" ) && e.endsWith( ")" ) && balanceado( e ) ? e : "(" + e + ")";
  }

  private static boolean balanceado( String e ) {
    int n = 0;
    for ( int i = 0; i < e.length(); i++ ) {
      char ch = e.charAt( i );
      if ( ch == '(' ) {
        n++;
      } else if ( ch == ')' ) {
        n--;
        if ( n == 0 && i < e.length() - 1 ) {
          return false;
        }
      }
    }
    return true;
  }

  /** Columna de un campo: df["x"] en pandas, F.col("x") en PySpark. */
  public static String columna( String campo, String df, CtxTrad ctx ) {
    return ctx.pandas() ? df + "[" + CtxTrad.lit( campo ) + "]" : "F.col(" + CtxTrad.lit( campo ) + ")";
  }

  private static String atomica( Condition c, String df, RowMetaInterface campos, CtxTrad ctx, Bloque b ) {
    int f = c.getFunction();
    if ( f == Condition.FUNC_TRUE ) {
      return verdad( ctx, df );
    }
    String izq = c.getLeftValuename();
    if ( izq == null || izq.isEmpty() ) {
      return verdad( ctx, df );
    }
    ValueMetaInterface mi = campos == null ? null : campos.searchValueMeta( izq );
    int tipo = mi == null ? ValueMetaInterface.TYPE_NONE : mi.getType();
    String l = columna( izq, df, ctx );
    boolean pd = ctx.pandas();
    if ( f == Condition.FUNC_NULL || f == Condition.FUNC_NOT_NULL ) {
      String n;
      if ( tipo == ValueMetaInterface.TYPE_STRING || tipo == ValueMetaInterface.TYPE_NONE ) {
        // en PDI una cadena vacía es nula
        n = pd ? l + ".fillna(\"\").eq(\"\")" : "(" + l + ".isNull() | (" + l + " == \"\"))";
      } else {
        n = pd ? l + ".isna()" : l + ".isNull()";
      }
      return f == Condition.FUNC_NULL ? n : "~" + paren( n );
    }
    // lado derecho: otro campo o una constante
    String der;
    boolean derCampo = c.getRightValuename() != null && !c.getRightValuename().isEmpty();
    String textoDer = null;
    boolean derNulo = false;
    if ( derCampo ) {
      der = columna( c.getRightValuename(), df, ctx );
    } else {
      ValueMetaAndData v = c.getRightExact();
      if ( v == null || v.getValueData() == null ) {
        derNulo = true;
        der = "None";
      } else {
        textoDer = cadena( v );
        der = literal( v, mi, ctx, b );
      }
    }
    switch ( f ) {
      case Condition.FUNC_EQUAL:
      case Condition.FUNC_NOT_EQUAL:
      case Condition.FUNC_SMALLER:
      case Condition.FUNC_SMALLER_EQUAL:
      case Condition.FUNC_LARGER:
      case Condition.FUNC_LARGER_EQUAL:
        if ( derNulo ) {
          String esNulo = pd ? l + ".isna()" : l + ".isNull()";
          String noNulo = pd ? l + ".notna()" : l + ".isNotNull()";
          switch ( f ) {
            case Condition.FUNC_EQUAL:
            case Condition.FUNC_SMALLER_EQUAL:
              return esNulo;
            case Condition.FUNC_NOT_EQUAL:
            case Condition.FUNC_LARGER:
              return noNulo;
            case Condition.FUNC_LARGER_EQUAL:
              return verdad( ctx, df );
            default:
              return pd ? "pd.Series(False, index=" + df + ".index)" : "F.lit(False)";
          }
        }
        return comparar( f, l, der, derCampo, ctx );
      case Condition.FUNC_REGEXP:
        return regex( l, textoDer, der, derCampo, tipo, ctx, b );
      case Condition.FUNC_LIKE: {
        if ( derCampo ) {
          b.aproximado( ctx.t( "LIKE contra otro campo: se compara como expresión regular de PDI sin convertir.",
            "LIKE against another field: compared as a PDI regular expression without conversion.",
            "LIKE contra outro campo: comparado como expressão regular do PDI sem conversão." ) );
          return regex( l, null, der, true, tipo, ctx, b );
        }
        String re = textoDer == null ? "" : textoDer.replace( "%", ".*" ).replace( "?", "." );
        return regex( l, re, CtxTrad.lit( re ), false, tipo, ctx, b );
      }
      case Condition.FUNC_IN_LIST:
        return enLista( l, textoDer, der, derCampo, tipo, ctx, b );
      case Condition.FUNC_CONTAINS:
      case Condition.FUNC_STARTS_WITH:
      case Condition.FUNC_ENDS_WITH: {
        String s = texto( l, tipo, ctx );
        if ( pd ) {
          if ( derCampo ) {
            String op = f == Condition.FUNC_CONTAINS ? "a in t" : f == Condition.FUNC_STARTS_WITH ? "t.startswith(a)"
              : "t.endswith(a)";
            return "pd.Series([isinstance(t, str) and isinstance(a, str) and " + op + " for t, a in zip(" + s + ", "
              + texto( der, ValueMetaInterface.TYPE_NONE, ctx ) + ")], index=" + df + ".index)";
          }
          String m = f == Condition.FUNC_CONTAINS ? "contains" : f == Condition.FUNC_STARTS_WITH ? "startswith"
            : "endswith";
          return s + ".str." + m + "(" + der + ( f == Condition.FUNC_CONTAINS ? ", regex=False" : "" )
            + ", na=False)";
        }
        String m = f == Condition.FUNC_CONTAINS ? "contains" : f == Condition.FUNC_STARTS_WITH ? "startswith"
          : "endswith";
        return "F.coalesce(" + s + "." + m + "(" + ( derCampo ? der : "F.lit(" + der + ")" ) + "), F.lit(False))";
      }
      default:
        b.manual( ctx.t( "Función de condición sin traducción: ", "Condition function not translated: ",
          "Função de condição sem tradução: " ) + c.getFunctionDesc() );
        return verdad( ctx, df );
    }
  }

  /** Comparación con la semántica de PDI (nulo menor que todo, nulo igual a nulo), sin resultados nulos. */
  static String comparar( int f, String l, String r, boolean rCampo, CtxTrad ctx ) {
    boolean pd = ctx.pandas();
    String lNul = pd ? l + ".isna()" : l + ".isNull()";
    String lNo = pd ? l + ".notna()" : l + ".isNotNull()";
    String rNul = pd ? r + ".isna()" : r + ".isNull()";
    String rNo = pd ? r + ".notna()" : r + ".isNotNull()";
    String igual = pd ? ( rCampo ? "(" + l + ".eq(" + r + ").fillna(False) | (" + lNul + " & " + rNul + "))"
      : "(" + lNo + " & " + l + ".eq(" + r + "))" ) : l + ".eqNullSafe(" + r + ")";
    String op;
    switch ( f ) {
      case Condition.FUNC_EQUAL:
        return igual;
      case Condition.FUNC_NOT_EQUAL:
        return "~" + paren( igual );
      case Condition.FUNC_SMALLER:
        op = pd ? l + ".lt(" + r + ")" : "(" + l + " < " + r + ")";
        return rCampo ? "(" + rNo + " & (" + lNul + " | " + op + "))" : "(" + lNul + " | " + op + ")";
      case Condition.FUNC_SMALLER_EQUAL:
        op = pd ? l + ".le(" + r + ")" : "(" + l + " <= " + r + ")";
        return rCampo ? "(" + lNul + " | (" + rNo + " & " + op + "))" : "(" + lNul + " | " + op + ")";
      case Condition.FUNC_LARGER:
        op = pd ? l + ".gt(" + r + ")" : "(" + l + " > " + r + ")";
        return rCampo ? "(" + lNo + " & (" + rNul + " | " + op + "))" : "(" + lNo + " & " + op + ")";
      default: // >=
        op = pd ? l + ".ge(" + r + ")" : "(" + l + " >= " + r + ")";
        return rCampo ? "(" + rNul + " | (" + lNo + " & " + op + "))" : "(" + lNo + " & " + op + ")";
    }
  }

  /** El campo como texto (para REGEXP, CONTAINS...): si no es String, se convierte. */
  private static String texto( String l, int tipo, CtxTrad ctx ) {
    if ( tipo == ValueMetaInterface.TYPE_STRING ) {
      return l;
    }
    return ctx.pandas() ? l + ".astype(\"string\")" : l + ".cast(\"string\")";
  }

  private static String regex( String l, String re, String der, boolean derCampo, int tipo, CtxTrad ctx, Bloque b ) {
    String s = texto( l, tipo, ctx );
    if ( ctx.pandas() ) {
      if ( derCampo ) {
        ctx.importar( "import re" );
        return "pd.Series([isinstance(t, str) and isinstance(p, str) and re.fullmatch(p, t) is not None for t, p in "
          + "zip(" + s + ", " + der + ")], index=" + l.substring( 0, l.indexOf( '[' ) ) + ".index)";
      }
      return s + ".str.fullmatch(" + CtxTrad.lit( re ) + ", na=False)";
    }
    if ( derCampo ) {
      return "F.coalesce(" + s + ".rlike(F.concat(F.lit(\"^(?:\"), " + der + ", F.lit(\")$\"))), F.lit(False))";
    }
    return "F.coalesce(" + s + ".rlike(" + CtxTrad.lit( "^(?:" + re + ")$" ) + "), F.lit(False))";
  }

  private static String enLista( String l, String lista, String der, boolean derCampo, int tipo, CtxTrad ctx,
    Bloque b ) {
    if ( derCampo ) {
      b.manual( ctx.t( "IN LIST contra otro campo: complétalo a mano.", "IN LIST against another field: complete it "
        + "by hand.", "IN LIST contra outro campo: complete-o à mão." ) );
      return ctx.pandas() ? l + ".notna()" : l + ".isNotNull()";
    }
    String[] partes = Const.splitString( lista == null ? "" : lista, ';', true );
    List<String> vals = new ArrayList<>();
    boolean numeros = tipo == ValueMetaInterface.TYPE_INTEGER;
    for ( String p : partes ) {
      String v = p == null ? "" : p.replace( "\\", "" );
      if ( numeros ) {
        try {
          vals.add( String.valueOf( Long.parseLong( v.trim() ) ) );
          continue;
        } catch ( NumberFormatException e ) {
          numeros = false;
        }
      }
      vals.add( CtxTrad.lit( v ) );
    }
    if ( !numeros && tipo == ValueMetaInterface.TYPE_INTEGER ) {
      vals.clear();
      for ( String p : partes ) {
        vals.add( CtxTrad.lit( p == null ? "" : p.replace( "\\", "" ) ) );
      }
    }
    String s = numeros || tipo == ValueMetaInterface.TYPE_STRING ? l : texto( l, tipo, ctx );
    if ( !numeros && tipo != ValueMetaInterface.TYPE_STRING && tipo != ValueMetaInterface.TYPE_NONE ) {
      b.aproximado( ctx.t( "IN LIST sobre un campo no texto: PDI compara el valor formateado con su máscara.",
        "IN LIST on a non-text field: PDI compares the value formatted with its mask.",
        "IN LIST sobre um campo não texto: o PDI compara o valor formatado com a sua máscara." ) );
    }
    String lst = "[" + String.join( ", ", vals ) + "]";
    return ctx.pandas() ? s + ".isin(" + lst + ")" : "F.coalesce(" + s + ".isin(" + lst + "), F.lit(False))";
  }

  /** El texto de la constante tal como la escribió la persona. */
  private static String cadena( ValueMetaAndData v ) {
    try {
      Object d = v.getValueData();
      if ( d instanceof String ) {
        return (String) d;
      }
      return v.getValueMeta().getString( d );
    } catch ( Exception e ) {
      return String.valueOf( v.getValueData() );
    }
  }

  /** La constante como literal Python, convertida al tipo del campo de la izquierda (como hace PDI al comparar). */
  static String literal( ValueMetaAndData v, ValueMetaInterface izq, CtxTrad ctx, Bloque b ) {
    Object d = v.getValueData();
    ValueMetaInterface vm = v.getValueMeta();
    if ( izq != null && izq.getType() != vm.getType() ) {
      try {
        d = izq.convertData( vm, d );
        vm = izq;
      } catch ( Exception e ) {
        // queda con su tipo
      }
    }
    return valorPy( d, vm == null ? ValueMetaInterface.TYPE_STRING : vm.getType(), ctx );
  }

  /** Un valor Java de PDI como literal Python (Timestamp/fecha según el destino). */
  public static String valorPy( Object d, int tipo, CtxTrad ctx ) {
    if ( d == null ) {
      return "None";
    }
    if ( d instanceof Long || d instanceof Integer ) {
      return String.valueOf( d );
    }
    if ( d instanceof Double ) {
      return num( (Double) d );
    }
    if ( d instanceof BigDecimal ) {
      return ( (BigDecimal) d ).toPlainString().contains( "." ) ? ( (BigDecimal) d ).toPlainString()
        : ( (BigDecimal) d ).toPlainString() + ".0";
    }
    if ( d instanceof Boolean ) {
      return ( (Boolean) d ) ? "True" : "False";
    }
    if ( d instanceof Date ) {
      String s = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss.SSS" ).format( (Date) d ).replaceAll( "\\.000$", "" )
        .replaceAll( " 00:00:00$", "" );
      if ( ctx.pandas() ) {
        return "pd.Timestamp(" + CtxTrad.lit( s ) + ")";
      }
      ctx.importar( "import datetime" );
      return "F.to_timestamp(F.lit(" + CtxTrad.lit( s ) + "))";
    }
    if ( d instanceof byte[] ) {
      return "b" + CtxTrad.lit( new String( (byte[]) d ) );
    }
    return CtxTrad.lit( String.valueOf( d ) );
  }

  static String num( double x ) {
    if ( Double.isNaN( x ) ) {
      return "float(\"nan\")";
    }
    if ( x == Math.rint( x ) && Math.abs( x ) < 1e15 ) {
      return String.valueOf( (long) x ) + ".0";
    }
    return String.valueOf( x );
  }

  /** Lista de campos usados (para comentarios). */
  static List<String> usados( Condition c ) {
    return c == null ? new ArrayList<>() : new ArrayList<>( Arrays.asList( c.getUsedFields() ) );
  }
}
