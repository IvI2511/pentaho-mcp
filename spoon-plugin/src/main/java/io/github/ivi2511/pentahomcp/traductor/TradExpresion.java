package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;

/**
 * Traduce expresiones de los steps con código (Java de Java filter / User Defined Java Expression, JavaScript simple
 * de Modified JavaScript value y OpenFormula de Formula) a una expresión vectorizada de pandas o de PySpark. Lo que
 * no reconoce lanza {@link NoTraducible}: el traductor del step deja entonces un bloque MANUAL con el código comentado.
 */
public final class TradExpresion {

  /** La expresión usa algo que no sabemos traducir. */
  public static final class NoTraducible extends Exception {
    private static final long serialVersionUID = 1L;

    public NoTraducible( String m ) {
      super( m );
    }
  }

  /** Dialecto del código de origen. */
  public enum Dialecto { JAVA, JAVASCRIPT, FORMULA }

  /** Un nodo del árbol: lit, campo, bin, un, si, fun, met. Tipo: S texto, I entero, D decimal, B lógico, T fecha. */
  static final class Nodo {
    final String k;
    final String v;
    char t;
    final List<Nodo> h = new ArrayList<>();

    Nodo( String k, String v, char t, Nodo... hijos ) {
      this.k = k;
      this.v = v;
      this.t = t;
      h.addAll( Arrays.asList( hijos ) );
    }
  }

  private final Dialecto dia;
  private final String df;
  private final RowMetaInterface campos;
  private final CtxTrad ctx;
  /** Variables locales (JavaScript): nombre → su expresión. */
  final Map<String, Nodo> locales = new HashMap<>();
  private List<String> tk;
  private int p;

  public TradExpresion( Dialecto dia, String df, RowMetaInterface campos, CtxTrad ctx ) {
    this.dia = dia;
    this.df = df;
    this.campos = campos;
    this.ctx = ctx;
  }

  /** Traduce la expresión completa a código Python (pandas o PySpark). */
  public String traducir( String fuente ) throws NoTraducible {
    return emitirCol( arbol( fuente ) );
  }

  /** El árbol de la expresión (sin emitir). */
  Nodo arbol( String fuente ) throws NoTraducible {
    tk = tokens( fuente );
    p = 0;
    Nodo n = dia == Dialecto.FORMULA ? fComparacion() : ternario();
    if ( p < tk.size() ) {
      throw new NoTraducible( "sobra: " + tk.get( p ) );
    }
    return n;
  }

  /** Tipo deducido de la expresión. */
  char tipo( String fuente ) throws NoTraducible {
    return arbol( fuente ).t;
  }

  // ================================================================ tokens

  List<String> tokens( String s ) throws NoTraducible {
    List<String> r = new ArrayList<>();
    int i = 0;
    while ( i < s.length() ) {
      char c = s.charAt( i );
      if ( Character.isWhitespace( c ) ) {
        i++;
        continue;
      }
      if ( c == '/' && i + 1 < s.length() && s.charAt( i + 1 ) == '/' ) {
        while ( i < s.length() && s.charAt( i ) != '\n' ) {
          i++;
        }
        continue;
      }
      if ( c == '/' && i + 1 < s.length() && s.charAt( i + 1 ) == '*' ) {
        int f = s.indexOf( "*/", i + 2 );
        i = f < 0 ? s.length() : f + 2;
        continue;
      }
      if ( Character.isDigit( c ) || ( c == '.' && i + 1 < s.length() && Character.isDigit( s.charAt( i + 1 ) ) ) ) {
        int j = i;
        while ( j < s.length() && ( Character.isLetterOrDigit( s.charAt( j ) ) || s.charAt( j ) == '.' ) ) {
          j++;
        }
        r.add( "#" + s.substring( i, j ) );
        i = j;
        continue;
      }
      if ( c == '"' || ( c == '\'' && dia != Dialecto.FORMULA ) ) {
        StringBuilder b = new StringBuilder();
        int j = i + 1;
        while ( true ) {
          if ( j >= s.length() ) {
            throw new NoTraducible( "texto sin cerrar" );
          }
          char d = s.charAt( j );
          if ( d == c ) {
            if ( dia == Dialecto.FORMULA && j + 1 < s.length() && s.charAt( j + 1 ) == c ) {
              b.append( c );
              j += 2;
              continue;
            }
            break;
          }
          if ( d == '\\' && dia != Dialecto.FORMULA && j + 1 < s.length() ) {
            char e = s.charAt( j + 1 );
            b.append( e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e );
            j += 2;
            continue;
          }
          b.append( d );
          j++;
        }
        r.add( "\"" + b );
        i = j + 1;
        continue;
      }
      if ( c == '[' && dia == Dialecto.FORMULA ) {
        int f = s.indexOf( ']', i );
        if ( f < 0 ) {
          throw new NoTraducible( "[ sin cerrar" );
        }
        r.add( "@" + s.substring( i + 1, f ) );
        i = f + 1;
        continue;
      }
      if ( Character.isJavaIdentifierStart( c ) ) {
        int j = i;
        while ( j < s.length() && ( Character.isJavaIdentifierPart( s.charAt( j ) ) || ( s.charAt( j ) == '.'
          && dia == Dialecto.FORMULA ) ) ) {
          j++;
        }
        r.add( s.substring( i, j ) );
        i = j;
        continue;
      }
      String[] ops = { "===", "!==", "==", "!=", "<=", ">=", "<>", "&&", "||", "+", "-", "*", "/", "%", "<", ">", "!",
        "?", ":", "(", ")", ",", ";", ".", "&", "^", "=" };
      boolean ok = false;
      for ( String o : ops ) {
        if ( s.startsWith( o, i ) ) {
          r.add( o.equals( "===" ) ? "==" : o.equals( "!==" ) ? "!=" : o );
          i += o.length();
          ok = true;
          break;
        }
      }
      if ( !ok ) {
        throw new NoTraducible( "carácter " + c );
      }
    }
    return r;
  }

  private String ver() {
    return p < tk.size() ? tk.get( p ) : "";
  }

  private boolean si( String t ) {
    if ( ver().equals( t ) ) {
      p++;
      return true;
    }
    return false;
  }

  private void pedir( String t ) throws NoTraducible {
    if ( !si( t ) ) {
      throw new NoTraducible( "esperaba " + t + " y hay " + ver() );
    }
  }

  // ================================================================ Java / JavaScript

  private Nodo ternario() throws NoTraducible {
    Nodo c = binario( 0 );
    if ( si( "?" ) ) {
      Nodo a = ternario();
      pedir( ":" );
      Nodo b = ternario();
      return new Nodo( "si", null, a.t == b.t ? a.t : a.t == 'S' || b.t == 'S' ? 'S' : 'D', c, a, b );
    }
    return c;
  }

  private static final String[][] NIVELES = { { "||" }, { "&&" }, { "==", "!=" }, { "<", ">", "<=", ">=" },
    { "+", "-" }, { "*", "/", "%" } };

  private Nodo binario( int nivel ) throws NoTraducible {
    if ( nivel >= NIVELES.length ) {
      return unario();
    }
    Nodo a = binario( nivel + 1 );
    while ( true ) {
      String o = ver();
      if ( !Arrays.asList( NIVELES[ nivel ] ).contains( o ) ) {
        return a;
      }
      p++;
      Nodo b = binario( nivel + 1 );
      a = bin( o, a, b );
    }
  }

  private Nodo bin( String o, Nodo a, Nodo b ) {
    char t;
    if ( "|| && == != < > <= >=".contains( o ) && o.length() <= 2 && !o.equals( "+" ) ) {
      t = 'B';
    } else if ( o.equals( "+" ) && ( a.t == 'S' || b.t == 'S' ) ) {
      t = 'S';
    } else if ( a.t == 'I' && b.t == 'I' ) {
      t = 'I';
    } else {
      t = 'D';
    }
    if ( o.equals( "-" ) || o.equals( "*" ) || o.equals( "/" ) || o.equals( "%" ) || o.equals( "^" ) ) {
      t = a.t == 'I' && b.t == 'I' && !o.equals( "^" ) ? 'I' : 'D';
    }
    return new Nodo( "bin", o, t, a, b );
  }

  private Nodo unario() throws NoTraducible {
    if ( si( "!" ) ) {
      return new Nodo( "un", "!", 'B', unario() );
    }
    if ( si( "-" ) ) {
      Nodo x = unario();
      return new Nodo( "un", "-", x.t, x );
    }
    if ( si( "+" ) ) {
      return unario();
    }
    // cast: (int) x, (double) x, (String) x
    if ( ver().equals( "(" ) && p + 2 < tk.size() && tk.get( p + 2 ).equals( ")" ) && tk.get( p + 1 ).matches(
      "int|long|double|float|String|Integer|Long|Double" ) ) {
      String ti = tk.get( p + 1 );
      p += 3;
      Nodo x = unario();
      char t = ti.matches( "int|long|Integer|Long" ) ? 'I' : ti.equals( "String" ) ? 'S' : 'D';
      return new Nodo( "fun", "cast", t, x );
    }
    return postfijo();
  }

  private Nodo postfijo() throws NoTraducible {
    Nodo x = primario();
    while ( ver().equals( "." ) ) {
      p++;
      String m = ver();
      p++;
      List<Nodo> args = argumentos();
      Nodo n = new Nodo( "met", m, tipoMetodo( m, x ), x );
      n.h.addAll( args );
      x = n;
    }
    return x;
  }

  private static char tipoMetodo( String m, Nodo x ) {
    switch ( m ) {
      case "length":
      case "indexOf":
      case "intValue":
      case "longValue":
        return 'I';
      case "doubleValue":
        return 'D';
      case "equals":
      case "equalsIgnoreCase":
      case "contains":
      case "startsWith":
      case "endsWith":
      case "isEmpty":
        return 'B';
      default:
        return 'S';
    }
  }

  private List<Nodo> argumentos() throws NoTraducible {
    pedir( "(" );
    List<Nodo> r = new ArrayList<>();
    if ( si( ")" ) ) {
      return r;
    }
    do {
      r.add( dia == Dialecto.FORMULA ? fComparacion() : ternario() );
    } while ( si( "," ) || ( dia == Dialecto.FORMULA && si( ";" ) ) );
    pedir( ")" );
    return r;
  }

  private Nodo primario() throws NoTraducible {
    String t = ver();
    if ( t.isEmpty() ) {
      throw new NoTraducible( "fin inesperado" );
    }
    p++;
    if ( t.startsWith( "#" ) ) {
      return numero( t.substring( 1 ) );
    }
    if ( t.startsWith( "\"" ) ) {
      return new Nodo( "lit", CtxTrad.lit( t.substring( 1 ) ), 'S' );
    }
    if ( t.equals( "(" ) ) {
      Nodo x = ternario();
      pedir( ")" );
      return x;
    }
    if ( t.equals( "true" ) || t.equals( "false" ) ) {
      return new Nodo( "lit", t.equals( "true" ) ? "True" : "False", 'B' );
    }
    if ( t.equals( "null" ) ) {
      return new Nodo( "lit", "None", '?' );
    }
    if ( !Character.isJavaIdentifierStart( t.charAt( 0 ) ) ) {
      throw new NoTraducible( "inesperado: " + t );
    }
    // Math.abs(x), String.valueOf(x), Integer.parseInt(x)...
    if ( t.matches( "Math|String|Integer|Long|Double|Float" ) && ver().equals( "." ) ) {
      p++;
      String f = ver();
      p++;
      Nodo n = new Nodo( "fun", t + "." + f, t.equals( "String" ) ? 'S' : t.matches( "Integer|Long" ) ? 'I' : 'D' );
      n.h.addAll( argumentos() );
      if ( n.v.equals( "Math.round" ) ) {
        n.t = 'I';
      } else if ( n.v.matches( "Math[.](abs|max|min)" ) && !n.h.isEmpty() ) {
        boolean ent = true;
        for ( Nodo x : n.h ) {
          ent &= x.t == 'I';
        }
        n.t = ent ? 'I' : 'D';
      }
      return n;
    }
    if ( locales.containsKey( t ) ) {
      return locales.get( t );
    }
    if ( ver().equals( "(" ) ) {
      throw new NoTraducible( "función " + t );
    }
    return campo( t );
  }

  private Nodo numero( String s ) throws NoTraducible {
    String x = s.replaceAll( "[lLfFdD]$", "" );
    if ( x.matches( "\\d+" ) ) {
      return new Nodo( "lit", x, s.matches( ".*[fFdD]$" ) ? 'D' : 'I' );
    }
    if ( x.matches( "\\d*\\.\\d*([eE][-+]?\\d+)?|\\d+[eE][-+]?\\d+" ) ) {
      return new Nodo( "lit", x.startsWith( "." ) ? "0" + x : x.endsWith( "." ) ? x + "0" : x, 'D' );
    }
    throw new NoTraducible( "número " + s );
  }

  private Nodo campo( String nombre ) throws NoTraducible {
    ValueMetaInterface v = campos == null ? null : campos.searchValueMeta( nombre );
    if ( v == null && campos != null && campos.size() > 0 ) {
      throw new NoTraducible( "campo desconocido " + nombre );
    }
    return new Nodo( "campo", nombre, tipoPdi( v == null ? ValueMetaInterface.TYPE_NONE : v.getType() ) );
  }

  static char tipoPdi( int t ) {
    switch ( t ) {
      case ValueMetaInterface.TYPE_STRING:
        return 'S';
      case ValueMetaInterface.TYPE_INTEGER:
        return 'I';
      case ValueMetaInterface.TYPE_NUMBER:
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return 'D';
      case ValueMetaInterface.TYPE_BOOLEAN:
        return 'B';
      case ValueMetaInterface.TYPE_DATE:
      case ValueMetaInterface.TYPE_TIMESTAMP:
        return 'T';
      default:
        return '?';
    }
  }

  // ================================================================ OpenFormula

  private Nodo fComparacion() throws NoTraducible {
    Nodo a = fConcat();
    while ( true ) {
      String o = ver();
      if ( !Arrays.asList( "=", "<>", "<", ">", "<=", ">=" ).contains( o ) ) {
        return a;
      }
      p++;
      a = bin( o.equals( "=" ) ? "==" : o.equals( "<>" ) ? "!=" : o, a, fConcat() );
    }
  }

  private Nodo fConcat() throws NoTraducible {
    Nodo a = fSuma();
    while ( si( "&" ) ) {
      a = new Nodo( "bin", "&", 'S', a, fSuma() );
    }
    return a;
  }

  private Nodo fSuma() throws NoTraducible {
    Nodo a = fProd();
    while ( ver().equals( "+" ) || ver().equals( "-" ) ) {
      String o = tk.get( p++ );
      Nodo b = fProd();
      a = new Nodo( "bin", o, a.t == 'I' && b.t == 'I' ? 'I' : 'D', a, b );
    }
    return a;
  }

  private Nodo fProd() throws NoTraducible {
    Nodo a = fPot();
    while ( ver().equals( "*" ) || ver().equals( "/" ) ) {
      String o = tk.get( p++ );
      Nodo b = fPot();
      // en OpenFormula la división siempre es decimal
      a = new Nodo( "bin", o.equals( "/" ) ? "fdiv" : o, o.equals( "*" ) && a.t == 'I' && b.t == 'I' ? 'I' : 'D', a,
        b );
    }
    return a;
  }

  private Nodo fPot() throws NoTraducible {
    Nodo a = fUnario();
    while ( si( "^" ) ) {
      a = new Nodo( "bin", "^", 'D', a, fUnario() );
    }
    return a;
  }

  private Nodo fUnario() throws NoTraducible {
    if ( si( "-" ) ) {
      Nodo x = fUnario();
      return new Nodo( "un", "-", x.t, x );
    }
    if ( si( "+" ) ) {
      return fUnario();
    }
    String t = ver();
    if ( t.isEmpty() ) {
      throw new NoTraducible( "fin inesperado" );
    }
    p++;
    if ( t.startsWith( "#" ) ) {
      return numero( t.substring( 1 ) );
    }
    if ( t.startsWith( "\"" ) ) {
      return new Nodo( "lit", CtxTrad.lit( t.substring( 1 ) ), 'S' );
    }
    if ( t.startsWith( "@" ) ) {
      return campo( t.substring( 1 ) );
    }
    if ( t.equals( "(" ) ) {
      Nodo x = fComparacion();
      pedir( ")" );
      return x;
    }
    if ( Character.isLetter( t.charAt( 0 ) ) && ver().equals( "(" ) ) {
      String f = t.toUpperCase( Locale.ROOT );
      List<Nodo> args = argumentos();
      Nodo n = new Nodo( "fun", "F." + f, tipoFormula( f, args ) );
      n.h.addAll( args );
      return n;
    }
    if ( t.equalsIgnoreCase( "TRUE" ) || t.equalsIgnoreCase( "FALSE" ) ) {
      return new Nodo( "lit", t.equalsIgnoreCase( "TRUE" ) ? "True" : "False", 'B' );
    }
    throw new NoTraducible( "inesperado: " + t );
  }

  private static char tipoFormula( String f, List<Nodo> a ) {
    switch ( f ) {
      case "IF":
      case "CHOOSE":
        return a.size() > 1 ? a.get( 1 ).t : '?';
      case "AND":
      case "OR":
      case "NOT":
      case "ISBLANK":
      case "ISNA":
      case "ISERROR":
      case "EXACT":
      case "TRUE":
      case "FALSE":
        return 'B';
      case "LEN":
      case "YEAR":
      case "MONTH":
      case "DAY":
      case "HOUR":
      case "MINUTE":
      case "SECOND":
      case "INT":
      case "WEEKDAY":
        return 'I';
      case "TODAY":
      case "NOW":
      case "DATE":
        return 'T';
      case "UPPER":
      case "LOWER":
      case "TRIM":
      case "LEFT":
      case "RIGHT":
      case "MID":
      case "CONCATENATE":
      case "SUBSTITUTE":
      case "PROPER":
      case "TEXT":
        return 'S';
      default:
        return 'D';
    }
  }

  // ================================================================ emisión

  private boolean pd() {
    return ctx.pandas();
  }

  /** Emite y, en PySpark, envuelve un literal suelto en F.lit (para que siempre sea una Column). */
  String emitirCol( Nodo n ) throws NoTraducible {
    String e = emitir( n );
    if ( !pd() && n.k.equals( "lit" ) ) {
      return "F.lit(" + e + ")";
    }
    return e;
  }

  private String texto( Nodo n ) throws NoTraducible {
    String e = emitir( n );
    if ( n.t == 'S' ) {
      return e;
    }
    if ( n.k.equals( "lit" ) ) {
      return CtxTrad.lit( n.v.replaceAll( "^\"|\"$", "" ) );
    }
    return pd() ? e + ".astype(\"string\")" : e + ".cast(\"string\")";
  }

  private int entero( Nodo n ) throws NoTraducible {
    if ( n.k.equals( "lit" ) && n.v.matches( "-?\\d+" ) ) {
      return Integer.parseInt( n.v );
    }
    if ( n.k.equals( "un" ) && n.v.equals( "-" ) && n.h.get( 0 ).k.equals( "lit" ) ) {
      return -entero( n.h.get( 0 ) );
    }
    throw new NoTraducible( "se necesita un número entero fijo" );
  }

  String emitir( Nodo n ) throws NoTraducible {
    switch ( n.k ) {
      case "lit":
        return n.v;
      case "campo":
        return TradCondicion.columna( n.v, df, ctx );
      case "un":
        if ( n.v.equals( "!" ) ) {
          return "~" + emitir( n.h.get( 0 ) );
        }
        return "(-" + emitir( n.h.get( 0 ) ) + ")";
      case "si": {
        String c = emitir( n.h.get( 0 ) );
        if ( pd() ) {
          return "np.where(" + c + ", " + emitir( n.h.get( 1 ) ) + ", " + emitir( n.h.get( 2 ) ) + ")";
        }
        return "F.when(" + c + ", " + emitir( n.h.get( 1 ) ) + ").otherwise(" + emitir( n.h.get( 2 ) ) + ")";
      }
      case "bin":
        return emitirBin( n );
      case "met":
        return emitirMetodo( n );
      default:
        return emitirFuncion( n );
    }
  }

  private String emitirBin( Nodo n ) throws NoTraducible {
    Nodo a = n.h.get( 0 );
    Nodo b = n.h.get( 1 );
    String o = n.v;
    if ( ( o.equals( "==" ) || o.equals( "!=" ) ) && ( esNulo( a ) || esNulo( b ) ) ) {
      String x = emitir( esNulo( a ) ? b : a );
      boolean igual = o.equals( "==" );
      return pd() ? x + ( igual ? ".isna()" : ".notna()" ) : x + ( igual ? ".isNull()" : ".isNotNull()" );
    }
    if ( o.equals( "&" ) || ( o.equals( "+" ) && n.t == 'S' ) ) {
      if ( pd() ) {
        return "(" + texto( a ) + " + " + texto( b ) + ")";
      }
      return "F.concat(" + litCol( texto( a ), a ) + ", " + litCol( texto( b ), b ) + ")";
    }
    String x = emitir( a );
    String y = emitir( b );
    switch ( o ) {
      case "&&":
        return "(" + x + " & " + y + ")";
      case "||":
        return "(" + x + " | " + y + ")";
      case "^":
        return pd() ? "(" + x + " ** " + y + ")" : "F.pow(" + litCol( x, a ) + ", " + y + ")";
      case "fdiv":
        return "(" + x + " / " + y + ")";
      case "/":
        if ( a.t == 'I' && b.t == 'I' ) { // división entera de Java: trunca hacia cero
          return pd() ? "np.trunc(" + x + " / " + y + ")" : "(" + x + " / " + y + ").cast(\"long\")";
        }
        return "(" + x + " / " + y + ")";
      case "%":
        return pd() ? "np.fmod(" + x + ", " + y + ")" : "(" + x + " % " + y + ")";
      default:
        return "(" + x + " " + o + " " + y + ")";
    }
  }

  private static List<String> litCols( List<String> l ) {
    List<String> r = new ArrayList<>();
    for ( String x : l ) {
      r.add( "F.lit(" + x + ")" );
    }
    return r;
  }

  private String litCol( String e, Nodo n ) {
    return !pd() && n.k.equals( "lit" ) ? "F.lit(" + e + ")" : e;
  }

  private static boolean esNulo( Nodo n ) {
    return n.k.equals( "lit" ) && n.v.equals( "None" );
  }

  private String emitirMetodo( Nodo n ) throws NoTraducible {
    Nodo o = n.h.get( 0 );
    List<Nodo> a = n.h.subList( 1, n.h.size() );
    String x = emitir( o );
    String s = pd() ? x + ".str" : x;
    boolean pd = pd();
    switch ( n.v + "/" + a.size() ) {
      case "equals/1":
        return "(" + x + " == " + emitir( a.get( 0 ) ) + ")";
      case "equalsIgnoreCase/1":
        return pd ? "(" + s + ".lower() == " + minus( a.get( 0 ) ) + ")" : "(F.lower(" + x + ") == "
          + minus( a.get( 0 ) ) + ")";
      case "length/0":
        return pd ? s + ".len()" : "F.length(" + x + ")";
      case "toUpperCase/0":
        return pd ? s + ".upper()" : "F.upper(" + x + ")";
      case "toLowerCase/0":
        return pd ? s + ".lower()" : "F.lower(" + x + ")";
      case "trim/0":
        return pd ? s + ".strip()" : "F.trim(" + x + ")";
      case "isEmpty/0":
        return pd ? "(" + s + ".len() == 0)" : "(F.length(" + x + ") == 0)";
      case "toString/0":
        return texto( o );
      case "intValue/0":
      case "longValue/0":
        return pd ? "np.trunc(" + x + ")" : x + ".cast(\"long\")";
      case "doubleValue/0":
        return pd ? x + ".astype(float)" : x + ".cast(\"double\")";
      case "substring/1":
      case "substring/2": {
        int d = entero( a.get( 0 ) );
        if ( a.size() == 1 ) {
          return pd ? s + ".slice(" + d + ")" : "F.substring(" + x + ", " + ( d + 1 ) + ", 1 << 30)";
        }
        int h = entero( a.get( 1 ) );
        return pd ? s + ".slice(" + d + ", " + h + ")" : "F.substring(" + x + ", " + ( d + 1 ) + ", " + ( h - d ) + ")";
      }
      case "contains/1":
        return pd ? s + ".contains(" + emitir( a.get( 0 ) ) + ", regex=False)" : x + ".contains(" + emitir( a.get( 0 ) )
          + ")";
      case "startsWith/1":
        return pd ? s + ".startswith(" + emitir( a.get( 0 ) ) + ")" : x + ".startswith(" + emitir( a.get( 0 ) ) + ")";
      case "endsWith/1":
        return pd ? s + ".endswith(" + emitir( a.get( 0 ) ) + ")" : x + ".endswith(" + emitir( a.get( 0 ) ) + ")";
      case "indexOf/1":
        return pd ? s + ".find(" + emitir( a.get( 0 ) ) + ")" : "(F.instr(" + x + ", " + emitir( a.get( 0 ) ) + ") - 1)";
      case "replace/2":
        return pd ? s + ".replace(" + emitir( a.get( 0 ) ) + ", " + emitir( a.get( 1 ) ) + ", regex=False)"
          : "F.replace(" + x + ", F.lit(" + emitir( a.get( 0 ) ) + "), F.lit(" + emitir( a.get( 1 ) ) + "))";
      default:
        throw new NoTraducible( "método " + n.v );
    }
  }

  private String minus( Nodo n ) throws NoTraducible {
    if ( n.k.equals( "lit" ) ) {
      return n.v.toLowerCase( Locale.ROOT );
    }
    return pd() ? emitir( n ) + ".str.lower()" : "F.lower(" + emitir( n ) + ")";
  }

  private String emitirFuncion( Nodo n ) throws NoTraducible {
    List<Nodo> a = n.h;
    boolean pd = pd();
    String f = n.v;
    switch ( f + "/" + a.size() ) {
      case "cast/1":
        if ( n.t == 'I' ) {
          return pd ? "np.trunc(" + emitir( a.get( 0 ) ) + ")" : emitirCol( a.get( 0 ) ) + ".cast(\"long\")";
        }
        if ( n.t == 'S' ) {
          return texto( a.get( 0 ) );
        }
        return pd ? "(" + emitir( a.get( 0 ) ) + " * 1.0)" : emitirCol( a.get( 0 ) ) + ".cast(\"double\")";
      case "Math.abs/1":
      case "F.ABS/1":
        return pd ? "abs(" + emitir( a.get( 0 ) ) + ")" : "F.abs(" + emitirCol( a.get( 0 ) ) + ")";
      case "Math.round/1": // Java: floor(x + 0.5)
        return pd ? "np.floor(" + emitir( a.get( 0 ) ) + " + 0.5)" : "F.floor(" + emitir( a.get( 0 ) ) + " + 0.5)";
      case "Math.floor/1":
      case "F.INT/1":
        return pd ? "np.floor(" + emitir( a.get( 0 ) ) + ")" : "F.floor(" + emitirCol( a.get( 0 ) ) + ")";
      case "Math.ceil/1":
        return pd ? "np.ceil(" + emitir( a.get( 0 ) ) + ")" : "F.ceil(" + emitirCol( a.get( 0 ) ) + ")";
      case "Math.sqrt/1":
      case "F.SQRT/1":
        return pd ? "np.sqrt(" + emitir( a.get( 0 ) ) + ")" : "F.sqrt(" + emitirCol( a.get( 0 ) ) + ")";
      case "Math.log/1":
      case "F.LN/1":
        return pd ? "np.log(" + emitir( a.get( 0 ) ) + ")" : "F.log(" + emitirCol( a.get( 0 ) ) + ")";
      case "Math.exp/1":
      case "F.EXP/1":
        return pd ? "np.exp(" + emitir( a.get( 0 ) ) + ")" : "F.exp(" + emitirCol( a.get( 0 ) ) + ")";
      case "Math.pow/2":
      case "F.POWER/2":
        return pd ? "(" + emitir( a.get( 0 ) ) + " ** " + emitir( a.get( 1 ) ) + ")" : "F.pow(" + emitirCol( a.get( 0 ) )
          + ", " + emitir( a.get( 1 ) ) + ")";
      case "Math.max/2":
      case "F.MAX/2":
        return pd ? "np.maximum(" + emitir( a.get( 0 ) ) + ", " + emitir( a.get( 1 ) ) + ")" : "F.greatest("
          + emitirCol( a.get( 0 ) ) + ", " + emitirCol( a.get( 1 ) ) + ")";
      case "Math.min/2":
      case "F.MIN/2":
        return pd ? "np.minimum(" + emitir( a.get( 0 ) ) + ", " + emitir( a.get( 1 ) ) + ")" : "F.least("
          + emitirCol( a.get( 0 ) ) + ", " + emitirCol( a.get( 1 ) ) + ")";
      case "String.valueOf/1":
        return texto( a.get( 0 ) );
      case "Integer.parseInt/1":
      case "Integer.valueOf/1":
      case "Long.parseLong/1":
      case "Long.valueOf/1":
        return pd ? "pd.to_numeric(" + emitir( a.get( 0 ) ) + ").astype(\"Int64\")" : emitirCol( a.get( 0 ) )
          + ".cast(\"long\")";
      case "Double.parseDouble/1":
      case "Double.valueOf/1":
      case "F.VALUE/1":
        return pd ? "pd.to_numeric(" + emitir( a.get( 0 ) ) + ")" : emitirCol( a.get( 0 ) ) + ".cast(\"double\")";
      default:
        break;
    }
    if ( !f.startsWith( "F." ) ) {
      throw new NoTraducible( "función " + f );
    }
    return formula( f.substring( 2 ), a );
  }

  private String formula( String f, List<Nodo> a ) throws NoTraducible {
    boolean pd = pd();
    int n = a.size();
    switch ( f ) {
      case "IF":
        if ( n < 2 || n > 3 ) {
          break;
        }
        Nodo no = n == 3 ? a.get( 2 ) : new Nodo( "lit", "False", 'B' );
        return emitir( new Nodo( "si", null, '?', a.get( 0 ), a.get( 1 ), no ) );
      case "AND":
      case "OR": {
        List<String> l = new ArrayList<>();
        for ( Nodo x : a ) {
          l.add( emitir( x ) );
        }
        return "(" + String.join( f.equals( "AND" ) ? " & " : " | ", l ) + ")";
      }
      case "NOT":
        return "~" + emitir( a.get( 0 ) );
      case "ISBLANK":
      case "ISNA":
        return pd ? emitir( a.get( 0 ) ) + ".isna()" : emitir( a.get( 0 ) ) + ".isNull()";
      case "EXACT":
        return "(" + emitir( a.get( 0 ) ) + " == " + emitir( a.get( 1 ) ) + ")";
      case "LEN":
        return pd ? texto( a.get( 0 ) ) + ".str.len()" : "F.length(" + texto( a.get( 0 ) ) + ")";
      case "UPPER":
        return pd ? texto( a.get( 0 ) ) + ".str.upper()" : "F.upper(" + texto( a.get( 0 ) ) + ")";
      case "LOWER":
        return pd ? texto( a.get( 0 ) ) + ".str.lower()" : "F.lower(" + texto( a.get( 0 ) ) + ")";
      case "TRIM":
        return pd ? texto( a.get( 0 ) ) + ".str.strip()" : "F.trim(" + texto( a.get( 0 ) ) + ")";
      case "LEFT": {
        int k = n > 1 ? entero( a.get( 1 ) ) : 1;
        return pd ? texto( a.get( 0 ) ) + ".str.slice(0, " + k + ")" : "F.substring(" + texto( a.get( 0 ) ) + ", 1, " + k
          + ")";
      }
      case "RIGHT": {
        int k = n > 1 ? entero( a.get( 1 ) ) : 1;
        return pd ? texto( a.get( 0 ) ) + ".str.slice(-" + k + ")" : "F.substring(" + texto( a.get( 0 ) ) + ", -" + k
          + ", " + k + ")";
      }
      case "MID": {
        int d = entero( a.get( 1 ) );
        int k = entero( a.get( 2 ) );
        return pd ? texto( a.get( 0 ) ) + ".str.slice(" + ( d - 1 ) + ", " + ( d - 1 + k ) + ")" : "F.substring("
          + texto( a.get( 0 ) ) + ", " + d + ", " + k + ")";
      }
      case "CONCATENATE": {
        List<String> l = new ArrayList<>();
        for ( Nodo x : a ) {
          l.add( pd ? texto( x ) : litCol( texto( x ), x ) );
        }
        return pd ? "(" + String.join( " + ", l ) + ")" : "F.concat(" + String.join( ", ", l ) + ")";
      }
      case "SUBSTITUTE":
        if ( n != 3 ) {
          break;
        }
        return pd ? texto( a.get( 0 ) ) + ".str.replace(" + emitir( a.get( 1 ) ) + ", " + emitir( a.get( 2 ) )
          + ", regex=False)" : "F.replace(" + texto( a.get( 0 ) ) + ", F.lit(" + emitir( a.get( 1 ) ) + "), F.lit("
            + emitir( a.get( 2 ) ) + "))";
      case "ROUND": {
        int k = n > 1 ? entero( a.get( 1 ) ) : 0;
        ctx.ayudante( "_redondear", TradCampos.redondear( ctx ) );
        return pd ? "_redondear(" + emitir( a.get( 0 ) ) + ", " + k + ", \"HALF_UP\")" : "F.round(" + emitirCol( a.get(
          0 ) ) + ", " + k + ")";
      }
      case "MOD":
        return pd ? "(" + emitir( a.get( 0 ) ) + " % " + emitir( a.get( 1 ) ) + ")" : "F.pmod(" + emitirCol( a.get( 0 ) )
          + ", " + emitir( a.get( 1 ) ) + ")";
      case "TODAY":
        return pd ? "pd.Timestamp.today().normalize()" : "F.current_date().cast(\"timestamp\")";
      case "NOW":
        return pd ? "pd.Timestamp.now()" : "F.current_timestamp()";
      case "YEAR":
      case "MONTH":
      case "DAY":
      case "HOUR":
      case "MINUTE":
      case "SECOND": {
        String x = emitir( a.get( 0 ) );
        String sp = f.equals( "DAY" ) ? "dayofmonth" : f.toLowerCase( Locale.ROOT );
        return pd ? x + ".dt." + f.toLowerCase( Locale.ROOT ) : "F." + sp + "(" + x + ")";
      }
      case "DATE":
        return pd ? "pd.to_datetime(pd.DataFrame({\"year\": " + emitir( a.get( 0 ) ) + ", \"month\": " + emitir( a.get(
          1 ) ) + ", \"day\": " + emitir( a.get( 2 ) ) + "}))" : "F.make_date(" + emitirCol( a.get( 0 ) ) + ", "
            + emitirCol( a.get( 1 ) ) + ", " + emitirCol( a.get( 2 ) ) + ").cast(\"timestamp\")";
      case "WEEKDAY": {
        // tipo 1 (por defecto): domingo = 1 ... sábado = 7; tipo 2: lunes = 1 ... domingo = 7; tipo 3: lunes = 0
        int t = n > 1 ? entero( a.get( 1 ) ) : 1;
        String x = emitir( a.get( 0 ) );
        if ( pd ) {
          return t == 2 ? "(" + x + ".dt.dayofweek + 1)" : t == 3 ? x + ".dt.dayofweek" : "((" + x
            + ".dt.dayofweek + 1) % 7 + 1)";
        }
        return t == 2 ? "((F.dayofweek(" + x + ") + 5) % 7 + 1)" : t == 3 ? "((F.dayofweek(" + x + ") + 5) % 7)"
          : "F.dayofweek(" + x + ")";
      }
      case "CHOOSE": {
        List<String> ops = new ArrayList<>();
        for ( Nodo x : a.subList( 1, n ) ) {
          if ( !x.k.equals( "lit" ) ) {
            throw new NoTraducible( "CHOOSE con valores calculados" );
          }
          ops.add( x.v );
        }
        String i = emitir( a.get( 0 ) );
        if ( pd ) {
          StringBuilder m = new StringBuilder();
          for ( int k = 0; k < ops.size(); k++ ) {
            m.append( k == 0 ? "" : ", " ).append( k + 1 ).append( ": " ).append( ops.get( k ) );
          }
          return i + ".map({" + m + "})";
        }
        return "F.element_at(F.array(" + String.join( ", ", litCols( ops ) ) + "), " + i + ".cast(\"int\"))";
      }
      case "TRUE":
        return "True";
      case "FALSE":
        return "False";
      default:
        break;
    }
    throw new NoTraducible( "función " + f );
  }
}
