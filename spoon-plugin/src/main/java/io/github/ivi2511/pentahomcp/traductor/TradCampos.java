package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.Condition;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaFactory;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.calculator.CalculatorMeta;
import org.pentaho.di.trans.steps.calculator.CalculatorMetaFunction;
import org.pentaho.di.trans.steps.filterrows.FilterRowsMeta;
import org.pentaho.di.trans.steps.javafilter.JavaFilterMeta;
import org.pentaho.di.trans.steps.janino.JaninoMeta;
import org.pentaho.di.trans.steps.janino.JaninoMetaFunction;
import org.pentaho.di.trans.steps.joinrows.JoinRowsMeta;
import org.pentaho.di.trans.steps.switchcase.SwitchCaseMeta;
import org.pentaho.di.trans.steps.switchcase.SwitchCaseTarget;
import org.pentaho.di.trans.steps.selectvalues.SelectMetadataChange;
import org.pentaho.di.trans.steps.selectvalues.SelectValuesMeta;
import org.pentaho.di.trans.steps.stringoperations.StringOperationsMeta;
import org.pentaho.di.trans.steps.replacestring.ReplaceStringMeta;
import org.pentaho.di.trans.steps.stringcut.StringCutMeta;
import org.pentaho.di.trans.steps.fieldsplitter.FieldSplitterMeta;
import org.pentaho.di.trans.steps.splitfieldtorows.SplitFieldToRowsMeta;
import org.pentaho.di.trans.steps.valuemapper.ValueMapperMeta;
import org.pentaho.di.trans.steps.ifnull.IfNullMeta;
import org.pentaho.di.trans.steps.nullif.NullIfMeta;
import org.pentaho.di.trans.steps.numberrange.NumberRangeMeta;
import org.pentaho.di.trans.steps.numberrange.NumberRangeRule;
import org.pentaho.di.trans.steps.setvaluefield.SetValueFieldMeta;
import org.pentaho.di.trans.steps.setvalueconstant.SetValueConstantMeta;
import org.pentaho.di.trans.steps.constant.ConstantMeta;
import org.pentaho.di.trans.steps.regexeval.RegexEvalMeta;
import org.pentaho.di.trans.steps.validator.Validation;
import org.pentaho.di.trans.steps.validator.ValidatorMeta;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Traductores de los steps que cambian campos fila a fila (cálculos, textos, tipos, condiciones, constantes). */
final class TradCampos {

  private TradCampos() {
  }

  static void registrar() {
    Registro.step( TradCampos::filtrar, "FilterRows" );
    Registro.step( TradCampos::switchCase, "SwitchCase" );
    Registro.step( TradCampos::javaFilter, "JavaFilter" );
    Registro.step( TradCampos::joinRows, "JoinRows" );
    Registro.step( TradCampos::calculadora, "Calculator" );
    Registro.step( TradCampos::janino, "Janino" );
    Registro.step( TradCampos::formula, "Formula" );
    Registro.step( TradCampos::javascript, "ScriptValueMod" );
    Registro.step( TradCampos::selectValues, "SelectValues" );
    Registro.step( TradCampos::stringOperations, "StringOperations" );
    Registro.step( TradCampos::replaceString, "ReplaceString" );
    Registro.step( TradCampos::stringCut, "StringCut" );
    Registro.step( TradCampos::concatFields, "ConcatFields" );
    Registro.step( TradCampos::fieldSplitter, "FieldSplitter" );
    Registro.step( TradCampos::splitToRows, "SplitFieldToRows3" );
    Registro.step( TradCampos::valueMapper, "ValueMapper" );
    Registro.step( TradCampos::ifNull, "IfNull" );
    Registro.step( TradCampos::nullIf, "NullIf" );
    Registro.step( TradCampos::numberRange, "NumberRange" );
    Registro.step( TradCampos::setValueField, "SetValueField" );
    Registro.step( TradCampos::setValueConstant, "SetValueConstant" );
    Registro.step( TradCampos::constantes, "Constant" );
    Registro.step( TradCampos::secuencia, "Sequence" );
    Registro.step( TradCampos::checksum, "CheckSum" );
    Registro.step( TradCampos::regexEval, "RegexEval" );
    Registro.step( TradCampos::validador, "Validator" );
    Registro.step( TradCampos::cloneRow, "CloneRow" );
  }

  // ================================================================ utilidades comunes

  static Bloque nuevo( StepMeta s ) {
    return new Bloque( s.getName(), s.getStepID() );
  }

  /** La entrada (unida) o un DataFrame vacío. */
  static String entrada( StepMeta s, CtxTrad ctx, Bloque b ) {
    String in = ctx.entradaUnida( s, b );
    if ( in != null ) {
      return in;
    }
    return ctx.pandas() ? "pd.DataFrame()" : "spark.createDataFrame([], T.StructType([]))";
  }

  /**
   * Agrega columnas calculadas: en pandas {@code out = in.copy()} y una asignación por columna; en PySpark una
   * cadena de withColumn. Las expresiones se escriben sobre {@code out} (pandas) o con F.col (PySpark).
   */
  static final class Columnas {
    final Map<String, String> exprs = new LinkedHashMap<>();
    final List<String> despues = new ArrayList<>();

    void poner( String col, String expr ) {
      exprs.put( col + "\u0000" + exprs.size(), expr );
    }

    void escribir( Bloque b, CtxTrad ctx, String out, String in ) {
      if ( ctx.pandas() ) {
        b.l( out + " = " + in + ".copy()" );
        for ( Map.Entry<String, String> e : exprs.entrySet() ) {
          String c = e.getKey().substring( 0, e.getKey().indexOf( '\u0000' ) );
          b.l( out + "[" + CtxTrad.lit( c ) + "] = " + e.getValue() );
        }
      } else if ( exprs.isEmpty() ) {
        b.l( out + " = " + in );
      } else {
        b.l( out + " = (" + in );
        int i = 0;
        for ( Map.Entry<String, String> e : exprs.entrySet() ) {
          String c = e.getKey().substring( 0, e.getKey().indexOf( '\u0000' ) );
          b.l( "    .withColumn(" + CtxTrad.lit( c ) + ", " + e.getValue() + ")" + ( ++i == exprs.size() ? ")" : "" ) );
        }
      }
      for ( String l : despues ) {
        b.l( l );
      }
    }
  }

  /** Columna (pandas: out["x"], PySpark: F.col("x")). */
  static String c( String campo, String df, CtxTrad ctx ) {
    return TradCondicion.columna( campo, df, ctx );
  }

  static boolean vacio( String s ) {
    return s == null || s.isEmpty();
  }

  /**
   * Metadatos de un texto escrito por la persona (constante, valor de reemplazo): PDI lo convierte con la máscara y
   * los símbolos de ESTE texto; sin símbolo decimal, el punto.
   */
  static ValueMetaInterface fuente( String mascara, String dec, String grp ) throws Exception {
    ValueMetaInterface v = ValueMetaFactory.createValueMeta( "s", ValueMetaInterface.TYPE_STRING );
    if ( !vacio( mascara ) ) {
      v.setConversionMask( mascara );
    }
    v.setDecimalSymbol( vacio( dec ) ? "." : dec );
    v.setGroupingSymbol( vacio( grp ) ? "" : grp );
    return v;
  }

  /** Nombre del tipo de PDI para Spark. */
  static String tipoSpark( int t ) {
    switch ( t ) {
      case ValueMetaInterface.TYPE_INTEGER:
        return "long";
      case ValueMetaInterface.TYPE_NUMBER:
        return "double";
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return "decimal(38,18)";
      case ValueMetaInterface.TYPE_BOOLEAN:
        return "boolean";
      case ValueMetaInterface.TYPE_DATE:
      case ValueMetaInterface.TYPE_TIMESTAMP:
        return "timestamp";
      case ValueMetaInterface.TYPE_BINARY:
        return "binary";
      default:
        return "string";
    }
  }

  /**
   * Conversión de una expresión ya calculada al tipo de PDI de destino (de: el tipo natural, ver
   * {@link TradExpresion#tipoPdi}). Number → Integer redondea como PDI (Math.round).
   */
  static String convertir( String x, char de, int a, CtxTrad ctx ) {
    boolean pd = ctx.pandas();
    switch ( a ) {
      case ValueMetaInterface.TYPE_INTEGER:
        if ( de == 'I' ) {
          return pd ? serie( x ) + ".astype(\"Int64\")" : x + ".cast(\"long\")";
        }
        if ( de == 'S' ) {
          return pd ? "pd.to_numeric(" + x + ").round().astype(\"Int64\")" : x + ".cast(\"double\").cast(\"long\")";
        }
        return pd ? "np.floor(" + serie( x ) + ".astype(float) + 0.5).astype(\"Int64\")" : "F.floor(" + x
          + " + 0.5).cast(\"long\")";
      case ValueMetaInterface.TYPE_NUMBER:
        return pd ? ( de == 'S' ? "pd.to_numeric(" + x + ")" : serie( x ) + ".astype(float)" ) : x
          + ".cast(\"double\")";
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return pd ? "pd.to_numeric(" + x + ")" : x + ".cast(\"decimal(38,18)\")";
      case ValueMetaInterface.TYPE_STRING:
        return pd ? serie( x ) + ".astype(\"string\")" : x + ".cast(\"string\")";
      case ValueMetaInterface.TYPE_DATE:
      case ValueMetaInterface.TYPE_TIMESTAMP:
        return pd ? "pd.to_datetime(" + x + ")" : x + ".cast(\"timestamp\")";
      case ValueMetaInterface.TYPE_BOOLEAN:
        return pd ? serie( x ) + ".astype(\"boolean\")" : x + ".cast(\"boolean\")";
      default:
        return x;
    }
  }

  /** Una expresión de pandas lista para .astype(): np.where / np.select se envuelven en una Series. */
  private static String serie( String x ) {
    if ( x.startsWith( "np." ) && !x.startsWith( "np.trunc(" ) && !x.startsWith( "np.floor(" ) ) {
      return "pd.Series(" + x + ")";
    }
    return x;
  }

  /** Código de la función _redondear (pandas): redondeo de Java BigDecimal con cualquier modo. */
  static String redondear( CtxTrad ctx ) {
    return "def _redondear(serie, decimales=0, modo=\"HALF_EVEN\"):\n"
      + "    \"\"\"" + ctx.t( "Redondea como PDI (BigDecimal de Java): modo UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, "
        + "HALF_EVEN o HALF_CEILING.", "Rounds like PDI (Java BigDecimal): mode UP, DOWN, CEILING, FLOOR, HALF_UP, "
          + "HALF_DOWN, HALF_EVEN or HALF_CEILING.", "Arredonda como o PDI (BigDecimal de Java): modo UP, DOWN, "
            + "CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN ou HALF_CEILING." ) + "\"\"\"\n"
      + "    import decimal\n"
      + "    serie = pd.Series(serie)\n"
      + "    decs = decimales if isinstance(decimales, pd.Series) else pd.Series(decimales, index=serie.index)\n"
      + "    modos = modo if isinstance(modo, pd.Series) else pd.Series(modo, index=serie.index)\n"
      + "    def uno(x, d, m):\n"
      + "        if pd.isna(x) or pd.isna(d):\n"
      + "            return np.nan\n"
      + "        if m == \"HALF_CEILING\":\n"
      + "            m = \"HALF_UP\" if x >= 0 else \"HALF_DOWN\"\n"
      + "        q = decimal.Decimal(1).scaleb(-int(d))\n"
      + "        return float(decimal.Decimal(repr(float(x))).quantize(q, rounding=getattr(decimal, \"ROUND_\" + m)))\n"
      + "    return pd.Series([uno(x, d, m) for x, d, m in zip(serie, decs, modos)], index=serie.index, dtype=float)\n";
  }

  /** Modo de redondeo de PDI (número de la Calculadora: 1 UP ... 8 HALF_CEILING) a su nombre. */
  static final String[] MODOS = { "UP", "DOWN", "CEILING", "FLOOR", "HALF_UP", "HALF_DOWN", "HALF_EVEN",
    "HALF_CEILING" };

  /** Redondeo en PySpark: HALF_UP (F.round) y HALF_EVEN (F.bround) son exactos; el resto se aproxima. */
  static String redondeoSpark( String x, String d, String modo, Bloque b, CtxTrad ctx ) {
    switch ( modo ) {
      case "HALF_UP":
        return "F.round(" + x + ", " + d + ")";
      case "HALF_EVEN":
        return "F.bround(" + x + ", " + d + ")";
      case "HALF_CEILING": { // como Math.round: mitades hacia arriba, en decimal exacto
        String p = d.matches( "-?\d+" ) ? "10 ** " + d : "F.pow(F.lit(10), " + d + ").cast(\"decimal(38,12)\")";
        return "(F.floor(" + x + ".cast(\"decimal(38,12)\") * " + p + " + 0.5) / " + p + ").cast(\"double\")";
      }
      case "CEILING":
        return "(F.ceil(" + x + " * 10 ** " + d + ") / 10 ** " + d + ")";
      case "FLOOR":
        return "(F.floor(" + x + " * 10 ** " + d + ") / 10 ** " + d + ")";
      default:
        b.aproximado( ctx.t( "Redondeo " + modo + " en PySpark: se usa HALF_UP (F.round).", "Rounding " + modo
          + " in PySpark: HALF_UP (F.round) is used.", "Arredondamento " + modo + " em PySpark: usa-se HALF_UP." ) );
        return "F.round(" + x + ", " + d + ")";
    }
  }

  // ================================================================ condiciones y flujos

  /** Filter rows → máscara booleana; con destinos true/false registra las dos salidas. */
  static Bloque filtrar( StepMeta s, CtxTrad ctx ) {
    FilterRowsMeta m = (FilterRowsMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    RowMetaInterface campos = ctx.camposEntrada( s );
    String cond = TradCondicion.expr( m.getCondition(), in, campos, ctx, b );
    String mascara = ctx.nuevo( "cond_" + CtxTrad.ident( s.getName() ) );
    b.l( mascara + " = " + cond );
    StepMeta si = s.getParentTransMeta() == null ? null : ctx.trans.findStep( m.getTrueStepname() );
    StepMeta no = ctx.trans.findStep( m.getFalseStepname() );
    if ( vacio( m.getTrueStepname() ) ) {
      si = null;
    }
    if ( vacio( m.getFalseStepname() ) ) {
      no = null;
    }
    String vSi = out;
    if ( ctx.pandas() ) {
      b.l( out + " = " + in + "[" + mascara + "].reset_index(drop=True)" );
    } else {
      b.l( out + " = " + in + ".filter(" + mascara + ")" );
    }
    if ( si != null ) {
      ctx.ponerSalida( s, si, vSi );
    }
    if ( no != null ) {
      String vNo = ctx.nuevo( out + "_false" );
      b.l( vNo + " = " + in + ( ctx.pandas() ? "[~" + mascara + "].reset_index(drop=True)" : ".filter(~" + mascara
        + ")" ) );
      ctx.ponerSalida( s, no, vNo );
    }
    if ( si == null && no != null ) {
      // sólo destino «false»: lo demás no sale; el resto de hops recibe las filas que cumplen
      b.nota( ctx.t( "Sólo hay destino para las filas que NO cumplen.", "Only the rows that do NOT match have a "
        + "target.", "Só há destino para as linhas que NÃO cumprem." ) );
    }
    b.vista = out;
    return b;
  }

  /** Switch / Case → una máscara por caso (igualdad o «contiene») y el resto al destino por defecto. */
  static Bloque switchCase( StepMeta s, CtxTrad ctx ) {
    SwitchCaseMeta m = (SwitchCaseMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    ValueMetaInterface campo = ctx.camposEntrada( s ).searchValueMeta( m.getFieldname() );
    String col = TradCondicion.columna( m.getFieldname(), in, ctx );
    // casos agrupados por destino (varios valores pueden ir al mismo step)
    Map<String, List<String>> porDestino = new LinkedHashMap<>();
    List<String> todas = new ArrayList<>();
    int tipoCaso = m.getCaseValueType();
    for ( SwitchCaseTarget t : m.getCaseTargets() ) {
      String dest = t.caseTargetStep != null ? t.caseTargetStep.getName() : t.caseTargetStepname;
      String e;
      if ( m.isContains() ) {
        String txt = campo != null && campo.getType() == ValueMetaInterface.TYPE_STRING ? col : pd ? col
          + ".astype(\"string\")" : col + ".cast(\"string\")";
        e = pd ? txt + ".str.contains(" + CtxTrad.lit( t.caseValue == null ? "" : t.caseValue ) + ", regex=False, "
          + "na=False)" : "F.coalesce(" + txt + ".contains(" + CtxTrad.lit( t.caseValue == null ? "" : t.caseValue )
            + "), F.lit(False))";
      } else if ( vacio( t.caseValue ) ) {
        e = pd ? col + ".isna()" : col + ".isNull()";
      } else {
        String v = valorCaso( t.caseValue, tipoCaso, campo, ctx );
        e = pd ? "(" + col + ".notna() & " + col + ".eq(" + v + "))" : col + ".eqNullSafe(" + v + ")";
      }
      porDestino.computeIfAbsent( dest == null ? "" : dest, k -> new ArrayList<>() ).add( e );
      todas.add( e );
    }
    List<String> mascaras = new ArrayList<>();
    for ( Map.Entry<String, List<String>> e : porDestino.entrySet() ) {
      String mk = ctx.nuevo( "caso_" + CtxTrad.ident( e.getKey() ) );
      b.l( mk + " = " + String.join( " | ", e.getValue() ) );
      mascaras.add( mk );
      StepMeta d = ctx.trans.findStep( e.getKey() );
      if ( d != null ) {
        String v = ctx.nuevo( out + "_" + CtxTrad.ident( e.getKey() ) );
        b.l( v + " = " + in + ( pd ? "[" + mk + "].reset_index(drop=True)" : ".filter(" + mk + ")" ) );
        ctx.ponerSalida( s, d, v );
      }
    }
    String resto = mascaras.isEmpty() ? null : String.join( " | ", mascaras );
    b.l( out + " = " + in + ( resto == null ? "" : pd ? "[~(" + resto + ")].reset_index(drop=True)" : ".filter(~("
      + resto + "))" ) );
    StepMeta def = m.getDefaultTargetStep() != null ? m.getDefaultTargetStep() : ctx.trans.findStep( m
      .getDefaultTargetStepname() );
    if ( def != null ) {
      ctx.ponerSalida( s, def, out );
    }
    if ( m.isContains() && porDestino.size() > 1 ) {
      b.nota( ctx.t( "Con «contiene», una fila que cumple varios casos va a todos sus destinos (como en PDI).",
        "With 'contains', a row matching several cases goes to all their targets (as in PDI).",
        "Com «contém», uma linha que cumpre vários casos vai para todos os seus destinos (como no PDI)." ) );
    }
    b.vista = out;
    return b;
  }

  private static String valorCaso( String v, int tipoCaso, ValueMetaInterface campo, CtxTrad ctx ) {
    int t = campo != null ? campo.getType() : tipoCaso;
    try {
      ValueMetaInterface vm = fuente( null, null, null );
      ValueMetaInterface dest = campo != null ? campo.clone() : ValueMetaFactory.createValueMeta( "v", t );
      Object o = dest.convertData( vm, v );
      return TradCondicion.valorPy( o, t, ctx );
    } catch ( Exception e ) {
      return CtxTrad.lit( v );
    }
  }

  /** Java filter → la condición Java traducida a una máscara (o MANUAL con el código comentado). */
  static Bloque javaFilter( StepMeta s, CtxTrad ctx ) {
    JavaFilterMeta m = (JavaFilterMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String mascara = ctx.nuevo( "cond_" + CtxTrad.ident( s.getName() ) );
    try {
      String e = new TradExpresion( TradExpresion.Dialecto.JAVA, in, ctx.camposEntrada( s ), ctx ).traducir( m
        .getCondition() );
      b.l( "# Java: " + m.getCondition().replaceAll( "\\s+", " " ) );
      b.l( mascara + " = " + e );
    } catch ( TradExpresion.NoTraducible e ) {
      b.manual( ctx.t( "La condición Java no se pudo traducir (", "The Java condition could not be translated (",
        "A condição Java não pôde ser traduzida (" ) + e.getMessage() + ")." );
      b.l( "# TODO Java: " + m.getCondition().replaceAll( "\\s+", " " ) );
      b.l( mascara + " = " + ( ctx.pandas() ? "pd.Series(True, index=" + in + ".index)" : "F.lit(True)" ) );
    }
    b.nota( ctx.t( "Con nulos, PDI falla al evaluar Java; aquí la fila no cumple.", "With nulls PDI fails to evaluate "
      + "the Java code; here the row does not match.", "Com nulos o PDI falha ao avaliar o Java; aqui a linha não "
        + "cumpre." ) );
    salidasFiltro( s, ctx, b, in, out, mascara, null, null );
    b.vista = out;
    return b;
  }

  /** Reparte las filas de un filtro (true/false por los nombres de destino del XML). */
  private static void salidasFiltro( StepMeta s, CtxTrad ctx, Bloque b, String in, String out, String mascara,
    String siN, String noN ) {
    boolean pd = ctx.pandas();
    if ( pd ) {
      b.l( out + " = " + in + "[" + mascara + "].reset_index(drop=True)" );
    } else {
      b.l( out + " = " + in + ".filter(" + mascara + ")" );
    }
    String si = siN;
    String no = noN;
    try {
      Element x = ctx.xml( s );
      si = si == null ? CtxTrad.hijo( x, "send_true_to" ) : si;
      no = no == null ? CtxTrad.hijo( x, "send_false_to" ) : no;
    } catch ( Exception e ) {
      // sin XML
    }
    StepMeta dSi = vacio( si ) ? null : ctx.trans.findStep( si );
    StepMeta dNo = vacio( no ) ? null : ctx.trans.findStep( no );
    if ( dSi != null ) {
      ctx.ponerSalida( s, dSi, out );
    }
    if ( dNo != null ) {
      String v = ctx.nuevo( out + "_false" );
      b.l( v + " = " + in + ( pd ? "[~" + mascara + "].reset_index(drop=True)" : ".filter(~" + mascara + ")" ) );
      ctx.ponerSalida( s, dNo, v );
    }
  }

  /** Join rows (producto cartesiano) → merge how="cross" / crossJoin, y la condición como filtro. */
  static Bloque joinRows( StepMeta s, CtxTrad ctx ) {
    JoinRowsMeta m = (JoinRowsMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String out = ctx.df( s );
    List<StepMeta> ant = ctx.anteriores( s );
    if ( ant.isEmpty() ) {
      return TraductorTrans.sinTraduccion( s, ctx, ctx.t( "Join rows sin entradas.", "Join rows without inputs.",
        "Join rows sem entradas." ) );
    }
    // el step principal va primero (PDI pone sus campos delante)
    String principal = m.getMainStepname();
    List<String> vars = new ArrayList<>();
    for ( StepMeta a : ant ) {
      String v = ctx.salidaHacia( a, s );
      if ( a.getName().equals( principal ) ) {
        vars.add( 0, v );
      } else {
        vars.add( v );
      }
    }
    String e = vars.get( 0 );
    for ( int i = 1; i < vars.size(); i++ ) {
      e = ctx.pandas() ? e + ".merge(" + vars.get( i ) + ", how=\"cross\")" : e + ".crossJoin(" + vars.get( i ) + ")";
    }
    b.l( out + " = " + e );
    Condition c = m.getCondition();
    if ( c != null && !c.isEmpty() ) {
      String cond = TradCondicion.expr( c, out, ctx.campos( s ), ctx, b );
      b.l( out + " = " + out + ( ctx.pandas() ? "[" + cond + "].reset_index(drop=True)" : ".filter(" + cond + ")" ) );
    }
    if ( vars.size() > 1 && vacio( principal ) ) {
      b.nota( ctx.t( "Sin step principal, el orden de las filas puede diferir del de PDI.", "Without a main step, the "
        + "row order may differ from PDI.", "Sem step principal, a ordem das linhas pode diferir da do PDI." ) );
    }
    b.vista = out;
    return b;
  }

  // ================================================================ Calculator

  static Bloque calculadora( StepMeta s, CtxTrad ctx ) {
    CalculatorMeta m = (CalculatorMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String df = ctx.pandas() ? out : null;
    RowMetaInterface entrada = ctx.camposEntrada( s );
    Map<String, Character> tipos = new LinkedHashMap<>();
    for ( ValueMetaInterface v : entrada.getValueMetaList() ) {
      tipos.put( v.getName(), TradExpresion.tipoPdi( v.getType() ) );
    }
    Columnas cols = new Columnas();
    List<String> quitar = new ArrayList<>();
    for ( CalculatorMetaFunction f : m.getCalculation() ) {
      String[] r = funcionCalc( f, df, tipos, ctx, b );
      String expr = r[ 0 ];
      char nat = r[ 1 ].charAt( 0 );
      int tipo = f.getValueType();
      if ( tipo != ValueMetaInterface.TYPE_NONE && TradExpresion.tipoPdi( tipo ) != nat ) {
        expr = convertir( expr, nat, tipo, ctx );
        nat = TradExpresion.tipoPdi( tipo );
      } else if ( ctx.pandas() && nat == 'I' && !r[ 0 ].endsWith( "astype(\"Int64\")" ) && f.getCalcType()
        != CalculatorMetaFunction.CALC_CONSTANT ) {
        expr = convertir( expr, 'I', ValueMetaInterface.TYPE_INTEGER, ctx );
      }
      cols.poner( f.getFieldName(), expr );
      tipos.put( f.getFieldName(), nat );
      if ( f.isRemovedFromResult() ) {
        quitar.add( f.getFieldName() );
      }
    }
    cols.escribir( b, ctx, out, in );
    if ( !quitar.isEmpty() ) {
      List<String> q = new ArrayList<>();
      for ( String x : quitar ) {
        q.add( CtxTrad.lit( x ) );
      }
      b.l( out + " = " + out + ( ctx.pandas() ? ".drop(columns=[" + String.join( ", ", q ) + "])" : ".drop("
        + String.join( ", ", q ) + ")" ) + "  # " + ctx.t( "campos temporales", "temporary fields",
          "campos temporários" ) );
    }
    b.vista = out;
    return b;
  }

  /** Una función de la Calculadora: {expresión, tipo natural}. */
  private static String[] funcionCalc( CalculatorMetaFunction f, String df, Map<String, Character> tipos,
    CtxTrad ctx, Bloque b ) {
    boolean pd = ctx.pandas();
    String a = vacio( f.getFieldA() ) ? null : c( f.getFieldA(), df, ctx );
    String bb = vacio( f.getFieldB() ) ? null : c( f.getFieldB(), df, ctx );
    String cc = vacio( f.getFieldC() ) ? null : c( f.getFieldC(), df, ctx );
    char ta = a == null ? '?' : tipos.getOrDefault( f.getFieldA(), '?' );
    char tb = bb == null ? '?' : tipos.getOrDefault( f.getFieldB(), '?' );
    char num = ta == 'I' && ( bb == null || tb == 'I' ) ? 'I' : 'D';
    String sa = a == null ? "" : pd ? a + ".str" : a;
    int calc = f.getCalcType();
    switch ( calc ) {
      case CalculatorMetaFunction.CALC_CONSTANT: {
        int t = f.getValueType() == ValueMetaInterface.TYPE_NONE ? ValueMetaInterface.TYPE_STRING : f.getValueType();
        String v = f.getFieldA();
        String lit;
        try {
          ValueMetaInterface vs = fuente( f.getConversionMask(), f.getDecimalSymbol(), f.getGroupingSymbol() );
          ValueMetaInterface vd = ValueMetaFactory.createValueMeta( "v", t );
          if ( !vacio( f.getConversionMask() ) ) {
            vd.setConversionMask( f.getConversionMask() );
          }
          lit = TradCondicion.valorPy( vd.convertData( vs, v ), t, ctx );
        } catch ( Exception e ) {
          lit = CtxTrad.lit( v );
        }
        if ( !pd ) {
          lit = lit.startsWith( "F." ) ? lit : "F.lit(" + lit + ")" + ( lit.equals( "None" ) ? ".cast(\"" + tipoSpark(
            t ) + "\")" : "" );
        } else if ( t == ValueMetaInterface.TYPE_INTEGER && !lit.equals( "None" ) ) {
          lit = "pd.Series(" + lit + ", index=" + df + ".index, dtype=\"Int64\")";
        }
        return r( lit, TradExpresion.tipoPdi( t ) );
      }
      case CalculatorMetaFunction.CALC_COPY_OF_FIELD:
        return r( a, ta );
      case CalculatorMetaFunction.CALC_ADD:
        if ( ta == 'S' ) {
          return r( pd ? "(" + a + " + " + texto( bb, tb, ctx ) + ")" : "F.concat(" + a + ", " + texto( bb, tb, ctx )
            + ")", 'S' );
        }
        if ( ta == 'T' ) {
          break;
        }
        return r( "(" + a + " + " + bb + ")", num );
      case CalculatorMetaFunction.CALC_SUBTRACT:
        return r( "(" + a + " - " + bb + ")", num );
      case CalculatorMetaFunction.CALC_MULTIPLY:
        return r( "(" + a + " * " + bb + ")", num );
      case CalculatorMetaFunction.CALC_DIVIDE:
        if ( num == 'I' ) {
          // PDI divide dos Integer con división entera de Java (trunca hacia cero)
          return r( pd ? "np.trunc(" + a + " / " + bb + ")" : "(" + a + " / " + bb + ").cast(\"long\")", 'I' );
        }
        return r( "(" + a + " / " + bb + ")", 'D' );
      case CalculatorMetaFunction.CALC_SQUARE:
        return r( "(" + a + " * " + a + ")", ta == 'I' ? 'I' : 'D' );
      case CalculatorMetaFunction.CALC_SQUARE_ROOT:
        return r( pd ? "np.sqrt(" + a + ")" : "F.sqrt(" + a + ")", 'D' );
      case CalculatorMetaFunction.CALC_PERCENT_1:
        return r( "(100.0 * " + a + " / " + bb + ")", num == 'I' ? 'I' : 'D' );
      case CalculatorMetaFunction.CALC_PERCENT_2:
        return r( "(" + a + " - " + a + " * " + bb + " / 100.0)", 'D' );
      case CalculatorMetaFunction.CALC_PERCENT_3:
        return r( "(" + a + " + " + a + " * " + bb + " / 100.0)", 'D' );
      case CalculatorMetaFunction.CALC_COMBINATION_1:
        return r( "(" + a + " + " + bb + " * " + cc + ")", num );
      case CalculatorMetaFunction.CALC_COMBINATION_2:
        return r( pd ? "np.sqrt(" + a + " ** 2 + " + bb + " ** 2)" : "F.sqrt(" + a + " * " + a + " + " + bb + " * " + bb
          + ")", 'D' );
      case CalculatorMetaFunction.CALC_ADD3:
        return r( "(" + a + " + " + bb + " + " + cc + ")", num == 'I' && tipos.getOrDefault( f.getFieldC(), '?' ) == 'I'
          ? 'I' : 'D' );
      case CalculatorMetaFunction.CALC_ROUND_1:
        return redondeo( a, "0", "HALF_CEILING", ta, ctx, b );
      case CalculatorMetaFunction.CALC_ROUND_2:
        return redondeo( a, bb, "HALF_CEILING", ta, ctx, b );
      case CalculatorMetaFunction.CALC_ROUND_STD_1:
        return redondeo( a, "0", "HALF_UP", ta, ctx, b );
      case CalculatorMetaFunction.CALC_ROUND_STD_2:
        return redondeo( a, bb, "HALF_UP", ta, ctx, b );
      case CalculatorMetaFunction.CALC_ROUND_CUSTOM_1:
      case CalculatorMetaFunction.CALC_ROUND_CUSTOM_2: {
        String d = calc == CalculatorMetaFunction.CALC_ROUND_CUSTOM_1 ? "0" : bb;
        String modo = calc == CalculatorMetaFunction.CALC_ROUND_CUSTOM_1 ? bb : cc;
        if ( pd ) {
          ctx.ayudante( "_redondear", redondear( ctx ) );
          String nombres = "{" + "1: \"UP\", 2: \"DOWN\", 3: \"CEILING\", 4: \"FLOOR\", 5: \"HALF_UP\", 6: \"HALF_DOWN\", "
            + "7: \"HALF_EVEN\", 8: \"HALF_CEILING\"}";
          return r( "_redondear(" + a + ", " + d + ", " + modo + ".map(" + nombres + "))", 'D' );
        }
        b.aproximado( ctx.t( "Redondeo con modo variable en PySpark: se usa HALF_UP.", "Rounding with a variable mode "
          + "in PySpark: HALF_UP is used.", "Arredondamento com modo variável em PySpark: usa-se HALF_UP." ) );
        return r( "F.round(" + a + ", " + d + ")", 'D' );
      }
      case CalculatorMetaFunction.CALC_CEIL:
        return r( ta == 'I' ? a : pd ? "np.ceil(" + a + ")" : "F.ceil(" + a + ").cast(\"double\")", ta == 'I' ? 'I'
          : 'D' );
      case CalculatorMetaFunction.CALC_FLOOR:
        return r( ta == 'I' ? a : pd ? "np.floor(" + a + ")" : "F.floor(" + a + ").cast(\"double\")", ta == 'I' ? 'I'
          : 'D' );
      case CalculatorMetaFunction.CALC_ABS:
        return r( pd ? a + ".abs()" : "F.abs(" + a + ")", ta );
      case CalculatorMetaFunction.CALC_REMAINDER:
        return r( pd ? "np.fmod(" + a + ", " + bb + ")" : "(" + a + " % " + bb + ")", num );
      case CalculatorMetaFunction.CALC_NVL:
        if ( pd && ta == 'S' ) {
          return r( a + ".mask(" + a + ".fillna(\"\").eq(\"\"), " + bb + ")", ta );
        }
        return r( pd ? a + ".fillna(" + bb + ")" : "F.coalesce(" + a + ", " + bb + ")", ta );
      // ---- fechas
      case CalculatorMetaFunction.CALC_ADD_DAYS:
        return r( pd ? "(" + a + " + pd.to_timedelta(" + bb + ", unit=\"D\"))" : "(" + a + " + F.make_dt_interval(days="
          + bb + "))", 'T' );
      case CalculatorMetaFunction.CALC_ADD_HOURS:
        return r( pd ? "(" + a + " + pd.to_timedelta(" + bb + ", unit=\"h\"))" : "(" + a + " + F.make_dt_interval("
          + "hours=" + bb + "))", 'T' );
      case CalculatorMetaFunction.CALC_ADD_MINUTES:
        return r( pd ? "(" + a + " + pd.to_timedelta(" + bb + ", unit=\"min\"))" : "(" + a + " + F.make_dt_interval("
          + "mins=" + bb + "))", 'T' );
      case CalculatorMetaFunction.CALC_ADD_SECONDS:
        return r( pd ? "(" + a + " + pd.to_timedelta(" + bb + ", unit=\"s\"))" : "(" + a + " + F.make_dt_interval("
          + "secs=" + bb + "))", 'T' );
      case CalculatorMetaFunction.CALC_ADD_MONTHS:
        if ( pd ) {
          ctx.ayudante( "_sumar_meses", "def _sumar_meses(fechas, meses):\n    \"\"\"" + ctx.t(
            "Suma meses como PDI (si el día no existe, el último del mes).", "Adds months like PDI (if the day does "
              + "not exist, the last of the month).", "Soma meses como o PDI (se o dia não existe, o último do mês)." )
            + "\"\"\"\n    meses = meses if isinstance(meses, pd.Series) else pd.Series(meses, index=fechas.index)\n"
            + "    return pd.Series([f + pd.DateOffset(months=int(m)) if pd.notna(f) and pd.notna(m) else pd.NaT\n"
            + "                      for f, m in zip(fechas, meses)], index=fechas.index, dtype=\"datetime64[ns]\")\n" );
          return r( "_sumar_meses(" + a + ", " + bb + ")", 'T' );
        }
        return r( "(" + a + " + F.make_ym_interval(months=" + bb + "))", 'T' );
      case CalculatorMetaFunction.CALC_YEAR_OF_DATE:
        return r( pd ? a + ".dt.year" : "F.year(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_MONTH_OF_DATE:
        return r( pd ? a + ".dt.month" : "F.month(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_DAY_OF_YEAR:
        return r( pd ? a + ".dt.dayofyear" : "F.dayofyear(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_DAY_OF_MONTH:
        return r( pd ? a + ".dt.day" : "F.dayofmonth(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_DAY_OF_WEEK: // PDI: domingo = 1 ... sábado = 7
        return r( pd ? "((" + a + ".dt.dayofweek + 1) % 7 + 1)" : "F.dayofweek(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_WEEK_OF_YEAR:
        b.aproximado( ctx.t( "La semana del año de PDI depende del idioma de Java; se usa la ISO 8601 (la de España).",
          "PDI's week of year depends on the Java locale; ISO 8601 is used.", "A semana do ano do PDI depende do "
            + "idioma do Java; usa-se a ISO 8601." ) );
        return r( pd ? a + ".dt.isocalendar().week" : "F.weekofyear(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_WEEK_OF_YEAR_ISO8601:
        return r( pd ? a + ".dt.isocalendar().week" : "F.weekofyear(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_YEAR_OF_DATE_ISO8601:
        return r( pd ? a + ".dt.isocalendar().year" : "F.extract(F.lit(\"YEAROFWEEK\"), " + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_QUARTER_OF_DATE:
        return r( pd ? a + ".dt.quarter" : "F.quarter(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_HOUR_OF_DAY:
        return r( pd ? a + ".dt.hour" : "F.hour(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_MINUTE_OF_HOUR:
        return r( pd ? a + ".dt.minute" : "F.minute(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_SECOND_OF_MINUTE:
        return r( pd ? a + ".dt.second" : "F.second(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_REMOVE_TIME_FROM_DATE:
        return r( pd ? a + ".dt.normalize()" : "F.date_trunc(\"day\", " + a + ")", 'T' );
      case CalculatorMetaFunction.CALC_DATE_DIFF:
        return difFechas( a, bb, 86400, ctx );
      case CalculatorMetaFunction.CALC_DATE_DIFF_MSEC:
        return difFechas( a, bb, 0, ctx );
      case CalculatorMetaFunction.CALC_DATE_DIFF_SEC:
        return difFechas( a, bb, 1, ctx );
      case CalculatorMetaFunction.CALC_DATE_DIFF_MN:
        return difFechas( a, bb, 60, ctx );
      case CalculatorMetaFunction.CALC_DATE_DIFF_HR:
        return difFechas( a, bb, 3600, ctx );
      case CalculatorMetaFunction.CALC_DATE_WORKING_DIFF:
        b.aproximado( ctx.t( "Días laborables: lunes a viernes, sin festivos.", "Working days: Monday to Friday, no "
          + "holidays.", "Dias úteis: segunda a sexta, sem feriados." ) );
        if ( pd ) {
          return r( "pd.Series(np.busday_count(" + bb + ".values.astype(\"datetime64[D]\"), " + a
            + ".values.astype(\"datetime64[D]\")), index=" + df + ".index)", 'I' );
        }
        return r( "F.size(F.filter(F.sequence(F.to_date(" + bb + "), F.to_date(" + a + ")), lambda d: F.dayofweek(d)"
          + ".between(2, 6))) - F.when(F.dayofweek(" + a + ").between(2, 6), 1).otherwise(0)", 'I' );
      // ---- textos
      case CalculatorMetaFunction.CALC_UPPER_CASE:
        return r( pd ? sa + ".upper()" : "F.upper(" + a + ")", 'S' );
      case CalculatorMetaFunction.CALC_LOWER_CASE:
        return r( pd ? sa + ".lower()" : "F.lower(" + a + ")", 'S' );
      case CalculatorMetaFunction.CALC_INITCAP:
        return r( pd ? sa + ".title()" : "F.initcap(" + a + ")", 'S' );
      case CalculatorMetaFunction.CALC_STRING_LEN:
        return r( pd ? sa + ".len()" : "F.length(" + a + ")", 'I' );
      case CalculatorMetaFunction.CALC_REMOVE_CR:
        return r( quitar( a, "\\r", ctx ), 'S' );
      case CalculatorMetaFunction.CALC_REMOVE_LF:
        return r( quitar( a, "\\n", ctx ), 'S' );
      case CalculatorMetaFunction.CALC_REMOVE_CRLF:
        return r( quitar( a, "[\\r\\n]", ctx ), 'S' );
      case CalculatorMetaFunction.CALC_REMOVE_TAB:
        return r( quitar( a, "\\t", ctx ), 'S' );
      case CalculatorMetaFunction.CALC_GET_ONLY_DIGITS:
        return r( quitar( a, "[^0-9]", ctx ), 'S' );
      case CalculatorMetaFunction.CALC_REMOVE_DIGITS:
        return r( quitar( a, "[0-9]", ctx ), 'S' );
      case CalculatorMetaFunction.CALC_USE_CDATA:
        return r( pd ? "(\"<![CDATA[\" + " + a + " + \"]]>\")" : "F.concat(F.lit(\"<![CDATA[\"), " + a
          + ", F.lit(\"]]>\"))", 'S' );
      case CalculatorMetaFunction.CALC_ESCAPE_SQL:
        return r( pd ? sa + ".replace(\"'\", \"''\", regex=False)" : "F.replace(" + a + ", F.lit(\"'\"), F.lit(\"''\"))",
          'S' );
      case CalculatorMetaFunction.CALC_MASK_XML:
      case CalculatorMetaFunction.CALC_ESCAPE_HTML: {
        if ( calc == CalculatorMetaFunction.CALC_ESCAPE_HTML ) {
          b.aproximado( ctx.t( "Escape HTML: PDI también escapa letras no ASCII (é → &eacute;).", "HTML escape: PDI "
            + "also escapes non-ASCII letters.", "Escape HTML: o PDI também escapa letras não ASCII." ) );
        }
        String[][] rr = { { "&", "&amp;" }, { "<", "&lt;" }, { ">", "&gt;" }, { "\"", "&quot;" }, { "'",
          calc == CalculatorMetaFunction.CALC_MASK_XML ? "&apos;" : "&#39;" } };
        String e = a;
        for ( String[] x : rr ) {
          e = pd ? e + ".str.replace(" + CtxTrad.lit( x[ 0 ] ) + ", " + CtxTrad.lit( x[ 1 ] ) + ", regex=False)"
            : "F.replace(" + e + ", F.lit(" + CtxTrad.lit( x[ 0 ] ) + "), F.lit(" + CtxTrad.lit( x[ 1 ] ) + "))";
        }
        return r( e, 'S' );
      }
      case CalculatorMetaFunction.CALC_UNESCAPE_XML:
      case CalculatorMetaFunction.CALC_UNESCAPE_HTML:
        ctx.importar( "import html" );
        if ( pd ) {
          return r( a + ".map(html.unescape, na_action=\"ignore\")", 'S' );
        }
        b.aproximado( ctx.t( "Desescapar con una UDF de Python.", "Unescape with a Python UDF.",
          "Desescapar com uma UDF de Python." ) );
        return r( "F.udf(html.unescape, \"string\")(" + a + ")", 'S' );
      case CalculatorMetaFunction.CALC_LEVENSHTEIN_DISTANCE:
        if ( pd ) {
          ctx.ayudante( "_levenshtein", "def _levenshtein(a, b):\n    \"\"\"" + ctx.t( "Distancia de Levenshtein.",
            "Levenshtein distance.", "Distância de Levenshtein." ) + "\"\"\"\n"
            + "    if not isinstance(a, str) or not isinstance(b, str):\n        return pd.NA\n"
            + "    fila = list(range(len(b) + 1))\n"
            + "    for i, ca in enumerate(a, 1):\n"
            + "        ant, fila[0] = fila[0], i\n"
            + "        for j, cb in enumerate(b, 1):\n"
            + "            ant, fila[j] = fila[j], min(fila[j] + 1, fila[j - 1] + 1, ant + (ca != cb))\n"
            + "    return fila[-1]\n" );
          return r( "pd.Series([_levenshtein(x, y) for x, y in zip(" + a + ", " + bb + ")], index=" + df
            + ".index, dtype=\"Int64\")", 'I' );
        }
        return r( "F.levenshtein(" + a + ", " + bb + ")", 'I' );
      case CalculatorMetaFunction.CALC_SOUNDEX:
        if ( !pd ) {
          return r( "F.soundex(" + a + ")", 'S' );
        }
        break;
      case CalculatorMetaFunction.CALC_SUBSTITUTE_VARIABLE:
        if ( pd ) {
          ctx.importar( "import re" );
          return r( sa + ".replace(r\"\\$\\{([^}]+)\\}\", lambda m: VARS.get(m.group(1), m.group(0)), regex=True)",
            'S' );
        }
        b.aproximado( ctx.t( "Sustituir variables con una UDF.", "Variable substitution with a UDF.",
          "Substituir variáveis com uma UDF." ) );
        ctx.importar( "import re" );
        return r( "F.udf(lambda s: None if s is None else re.sub(r\"\\$\\{([^}]+)\\}\", lambda m: VARS.get(m.group(1), "
          + "m.group(0)), s), \"string\")(" + a + ")", 'S' );
      case CalculatorMetaFunction.CALC_CRC32:
      case CalculatorMetaFunction.CALC_ADLER32:
      case CalculatorMetaFunction.CALC_MD5:
      case CalculatorMetaFunction.CALC_SHA1: {
        String alg = calc == CalculatorMetaFunction.CALC_CRC32 ? "crc32" : calc == CalculatorMetaFunction.CALC_ADLER32
          ? "adler32" : calc == CalculatorMetaFunction.CALC_MD5 ? "md5" : "sha1";
        ctx.importar( "import hashlib" );
        ctx.importar( "import zlib" );
        ctx.ayudante( "_suma_archivo", "def _suma_archivo(ruta, algoritmo):\n    \"\"\"" + ctx.t(
          "Suma de comprobación de un ARCHIVO (como la Calculadora de PDI).", "Checksum of a FILE (like PDI's "
            + "Calculator).", "Soma de verificação de um ARQUIVO (como a Calculadora do PDI)." ) + "\"\"\"\n"
          + "    if not isinstance(ruta, str) or not os.path.isfile(ruta):\n        return None\n"
          + "    with open(ruta, \"rb\") as f:\n        datos = f.read()\n"
          + "    if algoritmo in (\"crc32\", \"adler32\"):\n        return getattr(zlib, algoritmo)(datos)\n"
          + "    return hashlib.new(algoritmo, datos).hexdigest()\n" );
        char t = alg.endsWith( "32" ) ? 'I' : 'S';
        b.nota( ctx.t( "La Calculadora calcula la suma de comprobación del archivo cuya ruta está en el campo.",
          "The Calculator computes the checksum of the file whose path is in the field.", "A Calculadora calcula a "
            + "soma de verificação do arquivo cujo caminho está no campo." ) );
        if ( pd ) {
          return r( a + ".map(lambda r: _suma_archivo(r, " + CtxTrad.lit( alg ) + "))", t );
        }
        return r( "F.udf(lambda r: _suma_archivo(r, " + CtxTrad.lit( alg ) + "), \"" + ( t == 'I' ? "long" : "string" )
          + "\")(" + a + ")", t );
      }
      default:
        break;
    }
    b.manual( ctx.t( "Función de la Calculadora sin traducción: ", "Calculator function not translated: ",
      "Função da Calculadora sem tradução: " ) + CalculatorMetaFunction.getCalcFunctionLongDesc( calc ) + " ("
      + f.getFieldName() + ")" );
    return r( pd ? "None  # TODO" : "F.lit(None)  # TODO", '?' );
  }

  private static String[] r( String e, char t ) {
    return new String[] { e, String.valueOf( t ) };
  }

  private static String texto( String x, char t, CtxTrad ctx ) {
    if ( t == 'S' ) {
      return x;
    }
    return ctx.pandas() ? x + ".astype(\"string\")" : x + ".cast(\"string\")";
  }

  private static String quitar( String a, String re, CtxTrad ctx ) {
    return ctx.pandas() ? a + ".str.replace(r" + CtxTrad.lit( re ).replace( "\\\\", "\\" ) + ", \"\", regex=True)"
      : "F.regexp_replace(" + a + ", r" + CtxTrad.lit( re ).replace( "\\\\", "\\" ) + ", \"\")";
  }

  private static String[] redondeo( String a, String d, String modo, char ta, CtxTrad ctx, Bloque b ) {
    if ( ctx.pandas() ) {
      ctx.ayudante( "_redondear", redondear( ctx ) );
      return r( "_redondear(" + a + ", " + d + ", " + CtxTrad.lit( modo ) + ")", ta == 'I' && d.equals( "0" ) ? 'I'
        : 'D' );
    }
    return r( redondeoSpark( a, d, modo, b, ctx ), ta == 'I' && d.equals( "0" ) ? 'I' : 'D' );
  }

  /** A - B en la unidad pedida (segundos por unidad; 0 = milisegundos), truncando hacia cero como Java. */
  private static String[] difFechas( String a, String b, int seg, CtxTrad ctx ) {
    if ( ctx.pandas() ) {
      String u = seg == 0 ? "pd.Timedelta(milliseconds=1)" : seg == 1 ? "pd.Timedelta(seconds=1)" : seg == 60
        ? "pd.Timedelta(minutes=1)" : seg == 3600 ? "pd.Timedelta(hours=1)" : "pd.Timedelta(days=1)";
      return r( "np.trunc((" + a + " - " + b + ") / " + u + ")", 'I' );
    }
    String dif = "(" + a + ".cast(\"double\") - " + b + ".cast(\"double\"))";
    return r( seg == 0 ? "(" + dif + " * 1000).cast(\"long\")" : seg == 1 ? dif + ".cast(\"long\")" : "(" + dif
      + " / " + seg + ").cast(\"long\")", 'I' );
  }

  // ================================================================ código: Janino, Formula, JavaScript

  /** User Defined Java Expression → expresiones vectorizadas (o MANUAL con el código comentado). */
  static Bloque janino( StepMeta s, CtxTrad ctx ) {
    JaninoMeta m = (JaninoMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    RowMetaInterface campos = ctx.camposEntrada( s ).clone();
    Columnas cols = new Columnas();
    for ( JaninoMetaFunction f : m.getFormula() ) {
      String destino = vacio( f.getReplaceField() ) ? f.getFieldName() : f.getReplaceField();
      cols.poner( destino, expresion( TradExpresion.Dialecto.JAVA, f.getFormula(), f.getValueType(), ctx.pandas() ? out
        : in, campos, ctx, b ) );
      try {
        if ( campos.searchValueMeta( destino ) == null ) {
          campos.addValueMeta( ValueMetaFactory.createValueMeta( destino, f.getValueType() ) );
        }
      } catch ( Exception e ) {
        // sin tipo
      }
    }
    cols.escribir( b, ctx, out, in );
    b.nota( ctx.t( "Con nulos, el código Java de PDI suele fallar; aquí da nulo.", "With nulls PDI's Java code usually "
      + "fails; here it gives null.", "Com nulos o código Java do PDI costuma falhar; aqui dá nulo." ) );
    b.vista = out;
    return b;
  }

  private static String expresion( TradExpresion.Dialecto d, String codigo, int tipo, String df,
    RowMetaInterface campos, CtxTrad ctx, Bloque b ) {
    TradExpresion t = new TradExpresion( d, df, campos, ctx );
    try {
      TradExpresion.Nodo n = t.arbol( codigo );
      String e = t.emitirCol( n );
      if ( tipo != ValueMetaInterface.TYPE_NONE && TradExpresion.tipoPdi( tipo ) != n.t && n.t != '?' ) {
        e = convertir( e, n.t, tipo, ctx );
      } else if ( ctx.pandas() && e.startsWith( "np.where(" ) ) {
        e = "pd.Series(" + e + ", index=" + df + ".index)";
      }
      return e;
    } catch ( TradExpresion.NoTraducible ex ) {
      b.manual( ctx.t( "Expresión sin traducción (", "Expression not translated (", "Expressão sem tradução (" )
        + ex.getMessage() + "): " + codigo.replaceAll( "\\s+", " " ) );
      return ( ctx.pandas() ? "None" : "F.lit(None).cast(\"" + tipoSpark( tipo ) + "\")" ) + "  # TODO: " + codigo
        .replaceAll( "\\s+", " " );
    }
  }

  /** Formula (OpenFormula) → expresiones (las funciones comunes; el resto MANUAL). */
  static Bloque formula( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    RowMetaInterface campos = ctx.camposEntrada( s ).clone();
    Columnas cols = new Columnas();
    for ( Node n : XMLHandler.getNodes( x, "formula" ) ) {
      String nombre = XMLHandler.getTagValue( n, "field_name" );
      String fuente = XMLHandler.getTagValue( n, "formula_string" );
      int tipo = ValueMetaFactory.getIdForValueMeta( XMLHandler.getTagValue( n, "value_type" ) );
      String rep = XMLHandler.getTagValue( n, "replace_field" );
      String destino = vacio( rep ) ? nombre : rep;
      cols.poner( destino, expresion( TradExpresion.Dialecto.FORMULA, fuente == null ? "" : fuente, tipo, ctx.pandas()
        ? out : in, campos, ctx, b ) );
      if ( campos.searchValueMeta( destino ) == null ) {
        campos.addValueMeta( ValueMetaFactory.createValueMeta( destino, tipo == 0 ? ValueMetaInterface.TYPE_NUMBER
          : tipo ) );
      }
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /**
   * Modified JavaScript value: un script simple de asignaciones ({@code var x = expr;}) se traduce como expresiones;
   * si no, MANUAL con el script comentado y los campos de salida creados vacíos.
   */
  static Bloque javascript( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    StringBuilder script = new StringBuilder();
    Node scripts = XMLHandler.getSubNode( x, "jsScripts" );
    for ( Node n : XMLHandler.getNodes( scripts, "jsScript" ) ) {
      String tipo = XMLHandler.getTagValue( n, "jsScript_type" );
      if ( "0".equals( tipo ) || tipo == null ) { // 0 = script de transformación (1 inicio, 2 fin)
        script.append( XMLHandler.getTagValue( n, "jsScript_script" ) ).append( '\n' );
      }
    }
    List<String[]> salidas = new ArrayList<>();
    Node fields = XMLHandler.getSubNode( x, "fields" );
    for ( Node n : XMLHandler.getNodes( fields, "field" ) ) {
      salidas.add( new String[] { XMLHandler.getTagValue( n, "name" ), XMLHandler.getTagValue( n, "rename" ),
        XMLHandler.getTagValue( n, "type" ), XMLHandler.getTagValue( n, "replace" ) } );
    }
    RowMetaInterface campos = ctx.camposEntrada( s );
    TradExpresion t = new TradExpresion( TradExpresion.Dialecto.JAVASCRIPT, ctx.pandas() ? out : in, campos, ctx );
    Columnas cols = new Columnas();
    try {
      for ( String sentencia : script.toString().replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" )
        .split( "[;\\n]" ) ) {
        String st = sentencia.trim();
        if ( st.isEmpty() ) {
          continue;
        }
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile( "^(?:var\\s+)?([A-Za-z_$][\\w$]*)\\s*=(?!=)"
          + "\\s*(.+)$", java.util.regex.Pattern.DOTALL ).matcher( st );
        if ( !mm.find() ) {
          throw new TradExpresion.NoTraducible( st );
        }
        t.locales.put( mm.group( 1 ), t.arbol( mm.group( 2 ) ) );
      }
      for ( String[] o : salidas ) {
        TradExpresion.Nodo n = t.locales.get( o[ 0 ] );
        if ( n == null ) {
          if ( campos.searchValueMeta( o[ 0 ] ) == null ) {
            throw new TradExpresion.NoTraducible( o[ 0 ] );
          }
          n = new TradExpresion.Nodo( "campo", o[ 0 ], '?' );
        }
        String destino = vacio( o[ 1 ] ) ? o[ 0 ] : o[ 1 ];
        int tipo = ValueMetaFactory.getIdForValueMeta( o[ 2 ] );
        String e = t.emitirCol( n );
        if ( tipo != ValueMetaInterface.TYPE_NONE && n.t != '?' && TradExpresion.tipoPdi( tipo ) != n.t ) {
          e = convertir( e, n.t, tipo, ctx );
        } else if ( ctx.pandas() && e.startsWith( "np.where(" ) ) {
          e = "pd.Series(" + e + ", index=" + out + ".index)";
        }
        cols.poner( destino, e );
      }
      for ( String l : script.toString().split( "\\r?\\n" ) ) {
        if ( !l.trim().isEmpty() ) {
          b.l( "# JS: " + l.trim() );
        }
      }
      cols.escribir( b, ctx, out, in );
      b.nota( ctx.t( "Script traducido como expresiones vectorizadas.", "Script translated as vectorized expressions.",
        "Script traduzido como expressões vetorizadas." ) );
    } catch ( TradExpresion.NoTraducible e ) {
      b.codigo.clear();
      b.manual( ctx.t( "El script JavaScript no se pudo traducir (", "The JavaScript could not be translated (",
        "O script JavaScript não pôde ser traduzido (" ) + e.getMessage() + ")." );
      b.l( "# TODO: " + ctx.t( "script de PDI:", "PDI script:", "script do PDI:" ) );
      for ( String l : script.toString().split( "\\r?\\n" ) ) {
        b.l( "#   " + l );
      }
      Columnas vacias = new Columnas();
      for ( String[] o : salidas ) {
        int tipo = ValueMetaFactory.getIdForValueMeta( o[ 2 ] );
        vacias.poner( vacio( o[ 1 ] ) ? o[ 0 ] : o[ 1 ], ctx.pandas() ? "None" : "F.lit(None).cast(\"" + tipoSpark(
          tipo ) + "\")" );
      }
      vacias.escribir( b, ctx, out, in );
    }
    b.vista = out;
    return b;
  }

  // ================================================================ formatos

  /** Máscara de fecha de Java (SimpleDateFormat) → formato de strftime. */
  static String strftime( String java ) {
    if ( vacio( java ) ) {
      return "%Y/%m/%d %H:%M:%S.%f";
    }
    StringBuilder r = new StringBuilder();
    int i = 0;
    while ( i < java.length() ) {
      char ch = java.charAt( i );
      int j = i;
      while ( j < java.length() && java.charAt( j ) == ch ) {
        j++;
      }
      int n = j - i;
      if ( ch == '\'' ) {
        int f = java.indexOf( '\'', i + 1 );
        if ( f < 0 ) {
          f = java.length();
        }
        r.append( java.substring( i + 1, f ).replace( "%", "%%" ) );
        i = f + 1;
        continue;
      }
      switch ( ch ) {
        case 'y':
          r.append( n == 2 ? "%y" : "%Y" );
          break;
        case 'M':
          r.append( n >= 4 ? "%B" : n == 3 ? "%b" : "%m" );
          break;
        case 'd':
          r.append( "%d" );
          break;
        case 'H':
          r.append( "%H" );
          break;
        case 'h':
          r.append( "%I" );
          break;
        case 'm':
          r.append( "%M" );
          break;
        case 's':
          r.append( "%S" );
          break;
        case 'S':
          r.append( "%f" );
          break;
        case 'a':
          r.append( "%p" );
          break;
        case 'E':
          r.append( n >= 4 ? "%A" : "%a" );
          break;
        case 'D':
          r.append( "%j" );
          break;
        case 'Z':
        case 'X':
          r.append( "%z" );
          break;
        default:
          for ( int k = 0; k < n; k++ ) {
            r.append( ch == '%' ? "%%" : String.valueOf( ch ) );
          }
      }
      i = j;
    }
    return r.toString();
  }

  /** ¿La máscara de Java tiene milisegundos (S)? strftime usa microsegundos. */
  private static boolean conMilis( String java ) {
    return java != null && java.replaceAll( "'[^']*'", "" ).contains( "S" );
  }

  /** Texto → fecha con máscara (pandas / PySpark). */
  static String aFecha( String x, String mascara, CtxTrad ctx ) {
    String m = vacio( mascara ) ? "yyyy/MM/dd HH:mm:ss.SSS" : mascara;
    if ( ctx.pandas() ) {
      return "pd.to_datetime(" + x + ", format=" + CtxTrad.lit( strftime( m ) ) + ")";
    }
    return "F.to_timestamp(" + x + ", " + CtxTrad.lit( m ) + ")";
  }

  /** Fecha → texto con máscara. */
  static String deFecha( String x, String mascara, CtxTrad ctx ) {
    String m = vacio( mascara ) ? "yyyy/MM/dd HH:mm:ss.SSS" : mascara;
    if ( ctx.pandas() ) {
      String e = x + ".dt.strftime(" + CtxTrad.lit( strftime( m ) ) + ")";
      return conMilis( m ) ? e + ".str.replace(r\"(\\.\\d{3})\\d{3}\", r\"\\1\", regex=True)" : e;
    }
    return "F.date_format(" + x + ", " + CtxTrad.lit( m ) + ")";
  }

  /** Texto → número con símbolos decimal y de agrupación (los de la máscara de PDI). */
  static String aNumero( String x, String dec, String grp, boolean entero, CtxTrad ctx ) {
    String d = vacio( dec ) ? "." : dec;
    String e = x;
    if ( ctx.pandas() ) {
      if ( !vacio( grp ) ) {
        e = e + ".str.replace(" + CtxTrad.lit( grp ) + ", \"\", regex=False)";
      }
      if ( !d.equals( "." ) ) {
        e = e + ".str.replace(" + CtxTrad.lit( d ) + ", \".\", regex=False)";
      }
      e = "pd.to_numeric(" + e + ( e.equals( x ) ? "" : "" ) + ")";
      return entero ? e + ".astype(\"Int64\")" : e;
    }
    if ( !vacio( grp ) ) {
      e = "F.replace(" + e + ", F.lit(" + CtxTrad.lit( grp ) + "), F.lit(\"\"))";
    }
    if ( !d.equals( "." ) ) {
      e = "F.replace(" + e + ", F.lit(" + CtxTrad.lit( d ) + "), F.lit(\".\"))";
    }
    return e + ".cast(\"" + ( entero ? "long" : "double" ) + "\")";
  }

  /** Número → texto con máscara de Java (DecimalFormat: decimales y agrupación). */
  static String deNumero( String x, String mascara, String dec, String grp, CtxTrad ctx, Bloque b ) {
    if ( vacio( mascara ) ) {
      return ctx.pandas() ? x + ".astype(\"string\")" : x + ".cast(\"string\")";
    }
    String m = mascara.split( ";" )[ 0 ];
    int p = m.indexOf( '.' );
    int decs = p < 0 ? 0 : m.substring( p + 1 ).replaceAll( "[^0#]", "" ).length();
    int min = p < 0 ? 0 : m.substring( p + 1 ).replaceAll( "[^0]", "" ).length();
    boolean agrupa = m.contains( "," );
    if ( min != decs ) {
      b.aproximado( ctx.t( "Máscara «" + mascara + "»: se escriben siempre " + decs + " decimales.", "Mask '" + mascara
        + "': always " + decs + " decimals are written.", "Máscara «" + mascara + "»: escrevem-se sempre " + decs
          + " decimais." ) );
    }
    String d = vacio( dec ) ? "." : dec;
    String g = vacio( grp ) ? "," : grp;
    if ( ctx.pandas() ) {
      String fmt = "{:" + ( agrupa ? "," : "" ) + "." + decs + "f}";
      String e = x + ".map(" + CtxTrad.lit( fmt ) + ".format, na_action=\"ignore\")";
      if ( !d.equals( "." ) || ( agrupa && !g.equals( "," ) ) ) {
        e = e + ".map(lambda s: s.translate(str.maketrans({\",\": " + CtxTrad.lit( g ) + ", \".\": " + CtxTrad.lit( d )
          + "})), na_action=\"ignore\")";
      }
      return e + ".astype(\"string\")";
    }
    String e = agrupa ? "F.format_number(" + x + ", " + decs + ")" : "F.format_string(" + CtxTrad.lit( "%." + decs
      + "f" ) + ", " + x + ")";
    if ( !d.equals( "." ) || ( agrupa && !g.equals( "," ) ) ) {
      e = "F.translate(" + e + ", \",.\", " + CtxTrad.lit( ( agrupa ? g : "," ) + d ) + ")";
    }
    return e;
  }

  /** Conversión de tipo de Select values / Metadata (de → a), con máscaras. */
  static String cambiarTipo( String x, ValueMetaInterface de, int a, String mascara, String dec, String grp,
    CtxTrad ctx, Bloque b ) {
    int t = de == null ? ValueMetaInterface.TYPE_NONE : de.getType();
    String mk = vacio( mascara ) && de != null ? de.getConversionMask() : mascara;
    if ( t == a ) {
      return x;
    }
    boolean pd = ctx.pandas();
    if ( t == ValueMetaInterface.TYPE_STRING ) {
      switch ( a ) {
        case ValueMetaInterface.TYPE_DATE:
        case ValueMetaInterface.TYPE_TIMESTAMP:
          return aFecha( x, mk, ctx );
        case ValueMetaInterface.TYPE_INTEGER:
          return aNumero( x, dec, grp, true, ctx );
        case ValueMetaInterface.TYPE_NUMBER:
        case ValueMetaInterface.TYPE_BIGNUMBER:
          return aNumero( x, dec, grp, false, ctx );
        case ValueMetaInterface.TYPE_BOOLEAN:
          return pd ? x + ".str.upper().map({\"Y\": True, \"YES\": True, \"TRUE\": True, \"1\": True}).fillna(False)"
            + ".where(" + x + ".notna())" : "F.upper(" + x + ").isin(\"Y\", \"YES\", \"TRUE\", \"1\")";
        default:
          break;
      }
    }
    if ( a == ValueMetaInterface.TYPE_STRING ) {
      if ( t == ValueMetaInterface.TYPE_DATE || t == ValueMetaInterface.TYPE_TIMESTAMP ) {
        return deFecha( x, mk, ctx );
      }
      if ( t == ValueMetaInterface.TYPE_NUMBER || t == ValueMetaInterface.TYPE_BIGNUMBER
        || t == ValueMetaInterface.TYPE_INTEGER ) {
        return deNumero( x, mk, dec, grp, ctx, b );
      }
      if ( t == ValueMetaInterface.TYPE_BOOLEAN ) {
        return pd ? x + ".map({True: \"Y\", False: \"N\"}).astype(\"string\")" : "F.when(" + x + ", \"Y\").when(~" + x
          + ", \"N\")";
      }
    }
    return convertir( x, TradExpresion.tipoPdi( t ), a, ctx );
  }

  // ================================================================ Select values

  static Bloque selectValues( StepMeta s, CtxTrad ctx ) {
    SelectValuesMeta m = (SelectValuesMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    RowMetaInterface campos = ctx.camposEntrada( s );
    String act = in;
    // 1) seleccionar / renombrar / ordenar
    SelectValuesMeta.SelectField[] sel = m.getSelectFields();
    if ( sel != null && sel.length > 0 ) {
      List<String> nombres = new ArrayList<>();
      Map<String, String> ren = new LinkedHashMap<>();
      List<String> exprs = new ArrayList<>();
      for ( SelectValuesMeta.SelectField f : sel ) {
        nombres.add( CtxTrad.lit( f.getName() ) );
        String nuevo = vacio( f.getRename() ) ? f.getName() : f.getRename();
        if ( !nuevo.equals( f.getName() ) ) {
          ren.put( f.getName(), nuevo );
        }
        exprs.add( ren.containsKey( f.getName() ) ? "F.col(" + CtxTrad.lit( f.getName() ) + ").alias(" + CtxTrad.lit(
          nuevo ) + ")" : CtxTrad.lit( f.getName() ) );
      }
      String resto = "";
      if ( m.isSelectingAndSortingUnspecifiedFields() ) {
        String v = ctx.nuevo( "otros" );
        b.l( v + " = sorted(c for c in " + in + ".columns if c not in [" + String.join( ", ", nombres ) + "])" );
        resto = v;
      }
      if ( pd ) {
        String cols = "[" + String.join( ", ", nombres ) + "]" + ( resto.isEmpty() ? "" : " + " + resto );
        StringBuilder r = new StringBuilder();
        for ( Map.Entry<String, String> e : ren.entrySet() ) {
          r.append( r.length() == 0 ? "" : ", " ).append( CtxTrad.lit( e.getKey() ) ).append( ": " ).append( CtxTrad.lit(
            e.getValue() ) );
        }
        b.l( out + " = " + in + "[" + cols + "]" + ( ren.isEmpty() ? ".copy()" : ".rename(columns={" + r + "})" ) );
      } else {
        b.l( out + " = " + in + ".select(" + String.join( ", ", exprs ) + ( resto.isEmpty() ? "" : ", *" + resto )
          + ")" );
      }
      act = out;
    }
    // 2) quitar
    String[] del = m.getDeleteName();
    if ( del != null && del.length > 0 ) {
      List<String> q = new ArrayList<>();
      for ( String d : del ) {
        q.add( CtxTrad.lit( d ) );
      }
      b.l( out + " = " + act + ( pd ? ".drop(columns=[" + String.join( ", ", q ) + "])" : ".drop(" + String.join( ", ",
        q ) + ")" ) );
      act = out;
    }
    // 3) metadatos: renombrar y cambiar tipo
    SelectMetadataChange[] meta = m.getMeta();
    if ( meta != null && meta.length > 0 ) {
      if ( !act.equals( out ) ) {
        b.l( out + " = " + act + ( pd ? ".copy()" : "" ) );
      }
      RowMetaInterface antes = ctx.camposEntrada( s );
      Map<String, String> ren = new LinkedHashMap<>();
      for ( SelectMetadataChange mc : meta ) {
        ValueMetaInterface de = antes.searchValueMeta( mc.getName() );
        if ( de == null ) {
          de = campos.searchValueMeta( mc.getName() );
        }
        String col = pd ? out + "[" + CtxTrad.lit( mc.getName() ) + "]" : "F.col(" + CtxTrad.lit( mc.getName() ) + ")";
        int a = mc.getType();
        if ( a != ValueMetaInterface.TYPE_NONE && ( de == null || de.getType() != a ) ) {
          String e = cambiarTipo( col, de, a, mc.getConversionMask(), mc.getDecimalSymbol(), mc.getGroupingSymbol(),
            ctx, b );
          b.l( pd ? col + " = " + e : out + " = " + out + ".withColumn(" + CtxTrad.lit( mc.getName() ) + ", " + e + ")" );
        }
        if ( !vacio( mc.getRename() ) && !mc.getRename().equals( mc.getName() ) ) {
          ren.put( mc.getName(), mc.getRename() );
        }
      }
      for ( Map.Entry<String, String> e : ren.entrySet() ) {
        b.l( pd ? out + " = " + out + ".rename(columns={" + CtxTrad.lit( e.getKey() ) + ": " + CtxTrad.lit( e
          .getValue() ) + "})" : out + " = " + out + ".withColumnRenamed(" + CtxTrad.lit( e.getKey() ) + ", "
            + CtxTrad.lit( e.getValue() ) + ")" );
      }
      act = out;
    }
    if ( !act.equals( out ) ) {
      b.l( out + " = " + act + ( pd ? ".copy()" : "" ) );
    }
    b.vista = out;
    return b;
  }

  // ================================================================ textos

  private static int indice( String[] codigos, String v ) {
    if ( v == null ) {
      return 0;
    }
    for ( int i = 0; i < codigos.length; i++ ) {
      if ( codigos[ i ].equalsIgnoreCase( v ) ) {
        return i;
      }
    }
    try {
      return Integer.parseInt( v );
    } catch ( NumberFormatException e ) {
      return 0;
    }
  }

  private static String de( String[] a, int i ) {
    return a == null || i >= a.length ? null : a[ i ];
  }

  /** String operations: recortar, mayúsculas, relleno, capitalizar, escapar, dígitos y caracteres especiales. */
  static Bloque stringOperations( StepMeta s, CtxTrad ctx ) {
    StringOperationsMeta m = (StringOperationsMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    Columnas cols = new Columnas();
    String[] ent = m.getFieldInStream();
    for ( int i = 0; i < ent.length; i++ ) {
      String x = c( ent[ i ], pd ? out : null, ctx );
      String e = x;
      String sx = pd ? ".str" : "";
      int trim = indice( StringOperationsMeta.trimTypeCode, de( m.getTrimType(), i ) );
      if ( trim > 0 ) {
        String[] f = { "", "lstrip", "rstrip", "strip" };
        String[] g = { "", "ltrim", "rtrim", "trim" };
        e = pd ? e + ".str." + f[ trim ] + "(\" \")" : "F." + g[ trim ] + "(" + e + ")";
      }
      int lu = indice( StringOperationsMeta.lowerUpperCode, de( m.getLowerUpper(), i ) );
      if ( lu > 0 ) {
        e = pd ? e + ".str." + ( lu == 1 ? "lower" : "upper" ) + "()" : "F." + ( lu == 1 ? "lower" : "upper" ) + "(" + e
          + ")";
      }
      int pad = indice( StringOperationsMeta.paddingCode, de( m.getPaddingType(), i ) );
      if ( pad > 0 && !vacio( de( m.getPadLen(), i ) ) ) {
        String len = de( m.getPadLen(), i );
        String ch = vacio( de( m.getPadChar(), i ) ) ? " " : de( m.getPadChar(), i );
        e = pd ? e + ".str." + ( pad == 1 ? "rjust" : "ljust" ) + "(" + len + ", " + CtxTrad.lit( ch.substring( 0, 1 ) )
          + ")" : "F." + ( pad == 1 ? "lpad" : "rpad" ) + "(" + e + ", " + len + ", " + CtxTrad.lit( ch ) + ")";
        b.nota( ctx.t( "Relleno: PDI también corta los textos más largos que " + len + ".", "Padding: PDI also cuts "
          + "texts longer than " + len + ".", "Preenchimento: o PDI também corta textos mais longos que " + len + "." ) );
        e = pd ? e + ".str.slice(0, " + len + ")" : "F.substring(" + e + ", 1, " + len + ")";
      }
      int ic = indice( StringOperationsMeta.initCapCode, de( m.getInitCap(), i ) );
      if ( ic > 0 ) {
        e = pd ? e + ".str.title()" : "F.initcap(" + e + ")";
      }
      int mx = indice( StringOperationsMeta.maskXMLCode, de( m.getMaskXML(), i ) );
      if ( mx > 0 ) {
        switch ( mx ) {
          case StringOperationsMeta.MASK_CDATA:
            e = pd ? "(\"<![CDATA[\" + " + e + " + \"]]>\")" : "F.concat(F.lit(\"<![CDATA[\"), " + e + ", F.lit(\"]]>\"))";
            break;
          case StringOperationsMeta.MASK_ESCAPE_SQL:
            e = pd ? e + ".str.replace(\"'\", \"''\", regex=False)" : "F.replace(" + e + ", F.lit(\"'\"), F.lit(\"''\"))";
            break;
          case StringOperationsMeta.MASK_UNESCAPE_XML:
          case StringOperationsMeta.MASK_UNESCAPE_HTML:
            ctx.importar( "import html" );
            e = pd ? e + ".map(html.unescape, na_action=\"ignore\")" : "F.udf(html.unescape, \"string\")(" + e + ")";
            break;
          default:
            for ( String[] r : new String[][] { { "&", "&amp;" }, { "<", "&lt;" }, { ">", "&gt;" }, { "\"", "&quot;" },
              { "'", mx == StringOperationsMeta.MASK_ESCAPE_XML ? "&apos;" : "&#39;" } } ) {
              e = pd ? e + ".str.replace(" + CtxTrad.lit( r[ 0 ] ) + ", " + CtxTrad.lit( r[ 1 ] ) + ", regex=False)"
                : "F.replace(" + e + ", F.lit(" + CtxTrad.lit( r[ 0 ] ) + "), F.lit(" + CtxTrad.lit( r[ 1 ] ) + "))";
            }
        }
      }
      int dg = indice( StringOperationsMeta.digitsCode, de( m.getDigits(), i ) );
      if ( dg > 0 ) {
        e = quitar( e, dg == 1 ? "[^0-9]" : "[0-9]", ctx );
      }
      int sp = indice( StringOperationsMeta.removeSpecialCharactersCode, de( m.getRemoveSpecialCharacters(), i ) );
      if ( sp > 0 ) {
        String[] re = { "", "\\r", "\\n", "[\\r\\n]", "\\t", " " };
        e = quitar( e, re[ Math.min( sp, 5 ) ], ctx );
      }
      String dest = vacio( de( m.getFieldOutStream(), i ) ) ? ent[ i ] : m.getFieldOutStream()[ i ];
      cols.poner( dest, e.equals( x ) ? x : e );
      if ( sx.isEmpty() && pd ) {
        sx = "";
      }
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /** Replace in string: texto o expresión regular, mayúsculas, palabra entera, reemplazo por otro campo. */
  static Bloque replaceString( StepMeta s, CtxTrad ctx ) {
    ReplaceStringMeta m = (ReplaceStringMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    Columnas cols = new Columnas();
    String[] ent = m.getFieldInStream();
    for ( int i = 0; i < ent.length; i++ ) {
      String x = c( ent[ i ], pd ? out : null, ctx );
      boolean regex = m.getUseRegEx()[ i ];
      String busca = m.getReplaceString()[ i ] == null ? "" : m.getReplaceString()[ i ];
      String re = regex ? busca : java.util.regex.Pattern.quote( busca );
      if ( !regex ) {
        re = busca.replaceAll( "([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1" );
      }
      if ( m.getWholeWord()[ i ] ) {
        re = "\\b" + re + "\\b";
      }
      boolean mayus = m.getCaseSensitive()[ i ];
      String por = m.getReplaceByString()[ i ] == null ? "" : m.getReplaceByString()[ i ];
      String campoPor = de( m.getFieldReplaceByString(), i );
      if ( m.isSetEmptyString()[ i ] ) {
        por = "";
      }
      String e;
      if ( !vacio( campoPor ) ) {
        if ( pd ) {
          ctx.importar( "import re" );
          e = "pd.Series([re.sub(" + CtxTrad.lit( ( mayus ? "" : "(?i)" ) + re ) + ", p.replace(\"\\\\\", \"\\\\\\\\\"), t) "
            + "if isinstance(t, str) and isinstance(p, str) else t for t, p in zip(" + x + ", " + c( campoPor, out, ctx )
            + ")], index=" + out + ".index, dtype=\"string\")";
        } else {
          e = "F.expr(" + CtxTrad.lit( "regexp_replace(`" + ent[ i ] + "`, '" + ( mayus ? "" : "(?i)" ) + re.replace( "\\",
            "\\\\" ).replace( "'", "\\'" ) + "', `" + campoPor + "`)" ) + ")";
        }
      } else if ( pd ) {
        // Java usa $1 en el reemplazo; Python \1
        String porPy = regex ? por.replaceAll( "\\$(\\d)", "\\\\$1" ) : por.replace( "\\", "\\\\" );
        e = x + ".str.replace(" + CtxTrad.lit( re ) + ", " + CtxTrad.lit( porPy ) + ", regex=True" + ( mayus ? ""
          : ", case=False" ) + ")";
      } else {
        e = "F.regexp_replace(" + x + ", " + CtxTrad.lit( ( mayus ? "" : "(?i)" ) + re ) + ", " + CtxTrad.lit( regex ? por
          : por.replace( "\\", "\\\\" ).replace( "$", "\\$" ) ) + ")";
      }
      String dest = vacio( de( m.getFieldOutStream(), i ) ) ? ent[ i ] : m.getFieldOutStream()[ i ];
      cols.poner( dest, e );
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /** Strings cut: subcadena [desde, hasta) como PDI (posiciones desde 0). */
  static Bloque stringCut( StepMeta s, CtxTrad ctx ) {
    StringCutMeta m = (StringCutMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    Columnas cols = new Columnas();
    String[] ent = m.getFieldInStream();
    for ( int i = 0; i < ent.length; i++ ) {
      int d = entero( de( m.getCutFrom(), i ), 0 );
      int h = entero( de( m.getCutTo(), i ), 0 );
      String x = c( ent[ i ], pd ? out : null, ctx );
      String e;
      if ( d < 0 || h < 0 ) {
        // posiciones negativas: PDI corta desde el final
        b.aproximado( ctx.t( "Posiciones negativas en Strings cut: revisa el resultado.", "Negative positions in "
          + "Strings cut: check the result.", "Posições negativas em Strings cut: verifique o resultado." ) );
      }
      int a = Math.min( d, h );
      int z = Math.max( d, h );
      e = pd ? x + ".str.slice(" + a + ", " + z + ")" : "F.substring(" + x + ", " + ( a + 1 ) + ", " + ( z - a ) + ")";
      String dest = vacio( de( m.getFieldOutStream(), i ) ) ? ent[ i ] : m.getFieldOutStream()[ i ];
      cols.poner( dest, e );
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  static int entero( String s, int def ) {
    try {
      return Integer.parseInt( s.trim() );
    } catch ( Exception e ) {
      return def;
    }
  }

  /** Concat fields: une campos con separador (y comillas) en un campo nuevo. */
  static Bloque concatFields( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    String sep = CtxTrad.hijo( x, "separator" );
    String enc = CtxTrad.hijo( x, "enclosure" );
    boolean forzar = "Y".equalsIgnoreCase( CtxTrad.hijo( x, "enclosure_forced" ) );
    Node cf = XMLHandler.getSubNode( x, "ConcatFields" );
    String destino = cf != null ? XMLHandler.getTagValue( cf, "targetFieldName" ) : "";
    boolean quitarCampos = cf != null && "Y".equalsIgnoreCase( XMLHandler.getTagValue( cf, "removeSelectedFields" ) );
    RowMetaInterface campos = ctx.camposEntrada( s );
    List<String> partes = new ArrayList<>();
    List<String> nombres = new ArrayList<>();
    for ( Node n : XMLHandler.getNodes( XMLHandler.getSubNode( x, "fields" ), "field" ) ) {
      String nombre = XMLHandler.getTagValue( n, "name" );
      nombres.add( nombre );
      ValueMetaInterface v = campos.searchValueMeta( nombre );
      String fmt = XMLHandler.getTagValue( n, "format" );
      String col = c( nombre, pd ? out : null, ctx );
      String t = v == null ? col : cambiarTipo( col, v, ValueMetaInterface.TYPE_STRING, fmt, XMLHandler.getTagValue( n,
        "decimal" ), XMLHandler.getTagValue( n, "group" ), ctx, b );
      if ( forzar && !vacio( enc ) ) {
        t = pd ? "(" + CtxTrad.lit( enc ) + " + " + t + " + " + CtxTrad.lit( enc ) + ")" : "F.concat(F.lit(" + CtxTrad
          .lit( enc ) + "), " + t + ", F.lit(" + CtxTrad.lit( enc ) + "))";
      }
      partes.add( pd ? t + ".fillna(\"\")" : "F.coalesce(" + t + ", F.lit(\"\"))" );
    }
    String e;
    if ( pd ) {
      e = partes.isEmpty() ? "\"\"" : partes.get( 0 );
      for ( int i = 1; i < partes.size(); i++ ) {
        e = e + " + " + CtxTrad.lit( sep ) + " + " + partes.get( i );
      }
    } else {
      e = "F.concat_ws(" + CtxTrad.lit( sep ) + ", " + String.join( ", ", partes ) + ")";
    }
    Columnas cols = new Columnas();
    cols.poner( destino, e );
    cols.escribir( b, ctx, out, in );
    if ( quitarCampos && !nombres.isEmpty() ) {
      List<String> q = new ArrayList<>();
      for ( String n : nombres ) {
        q.add( CtxTrad.lit( n ) );
      }
      b.l( out + " = " + out + ( pd ? ".drop(columns=[" + String.join( ", ", q ) + "])" : ".drop(" + String.join( ", ", q )
        + ")" ) );
    }
    if ( !forzar && !vacio( enc ) ) {
      b.nota( ctx.t( "Las comillas sólo se ponen si el valor contiene el separador: aquí no se ponen.", "Quotes are "
        + "only added if the value contains the separator: not added here.", "As aspas só se põem se o valor contém o "
          + "separador: aqui não se põem." ) );
    }
    b.vista = out;
    return b;
  }

  /** Split fields: parte un campo en varios por un delimitador, con su tipo. */
  static Bloque fieldSplitter( StepMeta s, CtxTrad ctx ) {
    FieldSplitterMeta m = (FieldSplitterMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    String[] nombres = m.getFieldName();
    int n = nombres.length;
    String delim = m.getDelimiter() == null ? "" : m.getDelimiter();
    String partes = ctx.nuevo( "partes" );
    if ( pd ) {
      b.l( out + " = " + in + ".copy()" );
      b.l( partes + " = " + out + "[" + CtxTrad.lit( m.getSplitField() ) + "].str.split(" + CtxTrad.lit( delim )
        + ", n=" + ( n - 1 ) + ", expand=True, regex=False).reindex(columns=range(" + n + "))" );
    } else {
      b.l( partes + " = F.split(F.col(" + CtxTrad.lit( m.getSplitField() ) + "), " + CtxTrad.lit( java.util.regex.Pattern
        .quote( delim ).replace( "\\Q", "" ).replace( "\\E", "" ).replaceAll( "([.|*+?^$()\\[\\]{}\\\\])", "\\\\$1" ) )
        + ", " + n + ")" );
      b.l( out + " = " + in );
    }
    // en PDI el campo partido se reemplaza por los nuevos, en su posición
    for ( int i = 0; i < n; i++ ) {
      String x = pd ? partes + "[" + i + "]" : partes + ".getItem(" + i + ")";
      int tipo = m.getFieldType()[ i ];
      String tr = trimPorTipo( x, m.getFieldTrimType()[ i ], ctx );
      ValueMetaInterface vs = null;
      try {
        vs = ValueMetaFactory.createValueMeta( "s", ValueMetaInterface.TYPE_STRING );
      } catch ( Exception e ) {
        // no pasa
      }
      String e = tipo == ValueMetaInterface.TYPE_STRING || tipo == ValueMetaInterface.TYPE_NONE ? tr : cambiarTipo( tr,
        vs, tipo, de( m.getFieldFormat(), i ), de( m.getFieldDecimal(), i ), de( m.getFieldGroup(), i ), ctx, b );
      if ( !vacio( de( m.getFieldIfNull(), i ) ) ) {
        e = pd ? e + ".fillna(" + CtxTrad.lit( m.getFieldIfNull()[ i ] ) + ")" : "F.coalesce(" + e + ", F.lit("
          + CtxTrad.lit( m.getFieldIfNull()[ i ] ) + "))";
      }
      b.l( pd ? out + "[" + CtxTrad.lit( nombres[ i ] ) + "] = " + e : out + " = " + out + ".withColumn(" + CtxTrad.lit(
        nombres[ i ] ) + ", " + e + ")" );
    }
    List<String> orden = new ArrayList<>();
    for ( ValueMetaInterface v : ctx.campos( s ).getValueMetaList() ) {
      orden.add( CtxTrad.lit( v.getName() ) );
    }
    if ( !orden.isEmpty() ) {
      b.l( out + " = " + out + ( pd ? "[[" + String.join( ", ", orden ) + "]]" : ".select(" + String.join( ", ", orden )
        + ")" ) );
    } else {
      b.l( out + " = " + out + ( pd ? ".drop(columns=[" + CtxTrad.lit( m.getSplitField() ) + "])" : ".drop(" + CtxTrad
        .lit( m.getSplitField() ) + ")" ) );
    }
    if ( pd ) {
      b.nota( ctx.t( "Las partes vacías quedan como texto vacío o nulo según el tipo.", "Empty parts stay as empty "
        + "text or null depending on the type.", "As partes vazias ficam como texto vazio ou nulo segundo o tipo." ) );
    }
    b.vista = out;
    return b;
  }

  private static String trimPorTipo( String x, int trim, CtxTrad ctx ) {
    if ( trim <= 0 ) {
      return x;
    }
    String[] f = { "", "lstrip", "rstrip", "strip" };
    String[] g = { "", "ltrim", "rtrim", "trim" };
    return ctx.pandas() ? x + ".str." + f[ Math.min( trim, 3 ) ] + "()" : "F." + g[ Math.min( trim, 3 ) ] + "(" + x
      + ")";
  }

  /** Split field to rows: una fila por cada parte (explode), con número de parte opcional. */
  static Bloque splitToRows( StepMeta s, CtxTrad ctx ) {
    SplitFieldToRowsMeta m = (SplitFieldToRowsMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    String campo = m.getSplitField();
    String nuevo = vacio( m.getNewFieldname() ) ? campo : m.getNewFieldname();
    String delim = m.getDelimiter() == null ? ";" : m.getDelimiter();
    String reJava = m.isDelimiterRegex() ? delim : delim.replaceAll( "([.|*+?^$()\\[\\]{}\\\\])", "\\\\$1" );
    if ( pd ) {
      b.l( out + " = " + in + ".assign(**{" + CtxTrad.lit( nuevo ) + ": " + in + "[" + CtxTrad.lit( campo )
        + "].str.split(" + CtxTrad.lit( m.isDelimiterRegex() ? delim : delim ) + ", regex=" + ( m.isDelimiterRegex()
          ? "True" : "False" ) + ")})" );
      if ( !vacio( m.getRowNumberField() ) ) {
        b.l( out + " = " + out + ".explode(" + CtxTrad.lit( nuevo ) + ")" );
        b.l( out + "[" + CtxTrad.lit( m.getRowNumberField() ) + "] = " + out + ".groupby(level=0).cumcount() + 1" );
        b.l( out + " = " + out + ".reset_index(drop=True)" );
      } else {
        b.l( out + " = " + out + ".explode(" + CtxTrad.lit( nuevo ) + ", ignore_index=True)" );
      }
    } else {
      String f = !vacio( m.getRowNumberField() ) ? "F.posexplode_outer" : "F.explode_outer";
      String sp = "F.split(F.col(" + CtxTrad.lit( campo ) + "), " + CtxTrad.lit( reJava ) + ")";
      if ( !vacio( m.getRowNumberField() ) ) {
        b.l( out + " = " + in + ".select(\"*\", " + f + "(" + sp + ").alias(\"_pos\", " + CtxTrad.lit( "_parte" ) + "))" );
        b.l( out + " = " + out + ".withColumn(" + CtxTrad.lit( nuevo ) + ", F.col(\"_parte\")).withColumn(" + CtxTrad
          .lit( m.getRowNumberField() ) + ", F.col(\"_pos\") + 1).drop(\"_pos\", \"_parte\")" );
      } else {
        b.l( out + " = " + in + ".withColumn(" + CtxTrad.lit( nuevo ) + ", " + f + "(" + sp + "))" );
      }
    }
    b.vista = out;
    return b;
  }

  /** Value mapper: valor → valor (con valor por defecto para los que no están). */
  static Bloque valueMapper( StepMeta s, CtxTrad ctx ) {
    ValueMapperMeta m = (ValueMapperMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    String dest = vacio( m.getTargetField() ) ? m.getFieldToUse() : m.getTargetField();
    String nulo = null;
    StringBuilder mapa = new StringBuilder();
    for ( int i = 0; i < m.getSourceValue().length; i++ ) {
      String o = m.getSourceValue()[ i ];
      String d = m.getTargetValue()[ i ];
      if ( vacio( o ) ) {
        nulo = d;
        continue;
      }
      mapa.append( mapa.length() == 0 ? "" : ", " ).append( CtxTrad.lit( o ) ).append( ": " ).append( CtxTrad.lit( d ) );
    }
    String mv = ctx.nuevo( "mapa_" + CtxTrad.ident( dest ) );
    b.l( mv + " = {" + mapa + "}" );
    String col = c( m.getFieldToUse(), pd ? out : null, ctx );
    String def = m.getNonMatchDefault();
    String e;
    if ( pd ) {
      String base = col + ".map(" + mv + ")";
      // sin coincidencia: el valor por defecto o, si no hay, el original (si se escribe en el mismo campo) o nulo
      String resto = !vacio( def ) ? CtxTrad.lit( def ) : vacio( m.getTargetField() ) ? col : null;
      e = resto == null ? base : base + ".where(" + col + ".isin(" + mv + ".keys()), " + resto + ")";
      if ( nulo != null ) {
        e = e + ".mask(" + col + ".fillna(\"\").eq(\"\"), " + CtxTrad.lit( nulo ) + ")";
      } else if ( !vacio( def ) ) {
        e = e + ".mask(" + col + ".isna(), None)";
      }
    } else {
      StringBuilder w = new StringBuilder();
      if ( nulo != null ) {
        w.append( "F.when(F.coalesce(" ).append( col ).append( ", F.lit(\"\")) == \"\", " ).append( CtxTrad.lit( nulo ) )
          .append( ")" );
      }
      for ( int i = 0; i < m.getSourceValue().length; i++ ) {
        String o = m.getSourceValue()[ i ];
        if ( vacio( o ) ) {
          continue;
        }
        w.append( w.length() == 0 ? "F" : "" ).append( ".when(" ).append( col ).append( " == " ).append( CtxTrad.lit( o ) )
          .append( ", " ).append( CtxTrad.lit( m.getTargetValue()[ i ] ) ).append( ")" );
      }
      String resto = !vacio( def ) ? "F.when(" + col + ".isNotNull(), F.lit(" + CtxTrad.lit( def ) + "))" : vacio( m
        .getTargetField() ) ? col : "F.lit(None)";
      e = w.length() == 0 ? resto : w + ".otherwise(" + resto + ")";
    }
    Columnas cols = new Columnas();
    cols.poner( dest, e );
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /** If field value is null: valor fijo por campo, por tipo o para todos. */
  static Bloque ifNull( StepMeta s, CtxTrad ctx ) {
    IfNullMeta m = (IfNullMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    RowMetaInterface campos = ctx.camposEntrada( s );
    Columnas cols = new Columnas();
    if ( m.isSelectFields() ) {
      for ( IfNullMeta.Fields f : m.getFields() ) {
        ValueMetaInterface v = campos.searchValueMeta( f.getFieldName() );
        cols.poner( f.getFieldName(), relleno( f.getFieldName(), v, f.isSetEmptyString() ? "" : f.getReplaceValue(), f
          .getReplaceMask(), pd ? out : null, ctx ) );
      }
    } else if ( m.isSelectValuesType() ) {
      for ( IfNullMeta.ValueTypes t : m.getValueTypes() ) {
        int id = ValueMetaFactory.getIdForValueMeta( t.getTypeName() );
        for ( ValueMetaInterface v : campos.getValueMetaList() ) {
          if ( v.getType() == id ) {
            cols.poner( v.getName(), relleno( v.getName(), v, t.isSetTypeEmptyString() ? "" : t.getTypereplaceValue(), t
              .getTypereplaceMask(), pd ? out : null, ctx ) );
          }
        }
      }
    } else {
      for ( ValueMetaInterface v : campos.getValueMetaList() ) {
        cols.poner( v.getName(), relleno( v.getName(), v, m.isSetEmptyStringAll() ? "" : m.getReplaceAllByValue(), m
          .getReplaceAllMask(), pd ? out : null, ctx ) );
      }
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  private static String relleno( String campo, ValueMetaInterface v, String valor, String mascara, String df,
    CtxTrad ctx ) {
    String col = c( campo, df, ctx );
    String lit = CtxTrad.lit( valor == null ? "" : valor );
    if ( v != null && v.getType() != ValueMetaInterface.TYPE_STRING ) {
      try {
        ValueMetaInterface vs = fuente( mascara, null, null );
        ValueMetaInterface vd = v.clone();
        if ( !vacio( mascara ) ) {
          vd.setConversionMask( mascara );
        }
        lit = TradCondicion.valorPy( vd.convertData( vs, valor ), v.getType(), ctx );
      } catch ( Exception e ) {
        // queda como texto
      }
    }
    if ( ctx.pandas() ) {
      if ( v == null || v.getType() == ValueMetaInterface.TYPE_STRING ) {
        return col + ".mask(" + col + ".fillna(\"\").eq(\"\"), " + lit + ")";
      }
      return col + ".fillna(" + lit + ")";
    }
    if ( v == null || v.getType() == ValueMetaInterface.TYPE_STRING ) {
      return "F.when(F.coalesce(" + col + ", F.lit(\"\")) == \"\", F.lit(" + lit + ")).otherwise(" + col + ")";
    }
    return "F.coalesce(" + col + ", " + ( lit.startsWith( "F." ) ? lit : "F.lit(" + lit + ")" ) + ")";
  }

  /** Null if: el valor dado pasa a nulo. */
  static Bloque nullIf( StepMeta s, CtxTrad ctx ) {
    NullIfMeta m = (NullIfMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    RowMetaInterface campos = ctx.camposEntrada( s );
    Columnas cols = new Columnas();
    for ( NullIfMeta.Field f : m.getFields() ) {
      ValueMetaInterface v = campos.searchValueMeta( f.getFieldName() );
      String col = c( f.getFieldName(), pd ? out : null, ctx );
      String lit = CtxTrad.lit( f.getFieldValue() );
      if ( v != null && v.getType() != ValueMetaInterface.TYPE_STRING ) {
        try {
          lit = TradCondicion.valorPy( v.convertData( fuente( null, null, null ), f.getFieldValue() ), v.getType(),
            ctx );
        } catch ( Exception e ) {
          // queda como texto
        }
      }
      cols.poner( f.getFieldName(), pd ? col + ".mask(" + col + ".eq(" + lit + "))" : "F.when(" + col + " == " + lit
        + ", F.lit(None)).otherwise(" + col + ")" );
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /** Number range: rangos [desde, hasta) → etiqueta; fuera de rango, el valor por defecto. */
  static Bloque numberRange( StepMeta s, CtxTrad ctx ) {
    NumberRangeMeta m = (NumberRangeMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    String col = c( m.getInputField(), pd ? out : null, ctx );
    List<String> conds = new ArrayList<>();
    List<String> vals = new ArrayList<>();
    for ( NumberRangeRule r : m.getRules() ) {
      List<String> p = new ArrayList<>();
      if ( r.getLowerBound() != -Double.MAX_VALUE ) {
        p.add( "(" + col + " >= " + TradCondicion.num( r.getLowerBound() ) + ")" );
      }
      if ( r.getUpperBound() != Double.MAX_VALUE ) {
        p.add( "(" + col + " < " + TradCondicion.num( r.getUpperBound() ) + ")" );
      }
      conds.add( p.isEmpty() ? ( pd ? col + ".notna()" : col + ".isNotNull()" ) : String.join( " & ", p ) );
      vals.add( CtxTrad.lit( r.getValue() ) );
    }
    String def = CtxTrad.lit( m.getFallBackValue() == null ? "" : m.getFallBackValue() );
    String e;
    if ( pd ) {
      e = "pd.Series(np.select([" + String.join( ", ", conds ) + "], [" + String.join( ", ", vals ) + "], default=" + def
        + "), index=" + out + ".index, dtype=\"string\")";
    } else {
      StringBuilder w = new StringBuilder( "F" );
      for ( int i = 0; i < conds.size(); i++ ) {
        w.append( ".when(" ).append( conds.get( i ) ).append( ", " ).append( vals.get( i ) ).append( ")" );
      }
      e = conds.isEmpty() ? "F.lit(" + def + ")" : w + ".otherwise(" + def + ")";
    }
    Columnas cols = new Columnas();
    cols.poner( m.getOutputField(), e );
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /** Set field value: copia el valor de otro campo. */
  static Bloque setValueField( StepMeta s, CtxTrad ctx ) {
    SetValueFieldMeta m = (SetValueFieldMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    Columnas cols = new Columnas();
    for ( int i = 0; i < m.getFieldName().length; i++ ) {
      cols.poner( m.getFieldName()[ i ], c( m.getReplaceByFieldValue()[ i ], ctx.pandas() ? in : null, ctx ) );
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /** Set field value to a constant. */
  static Bloque setValueConstant( StepMeta s, CtxTrad ctx ) {
    SetValueConstantMeta m = (SetValueConstantMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    RowMetaInterface campos = ctx.camposEntrada( s );
    Columnas cols = new Columnas();
    for ( SetValueConstantMeta.Field f : m.getFields() ) {
      ValueMetaInterface v = campos.searchValueMeta( f.getFieldName() );
      String valor = f.isEmptyString() ? "" : f.getReplaceValue();
      String lit = m.isUseVars() ? ctx.texto( valor ) : CtxTrad.lit( valor );
      if ( v != null && v.getType() != ValueMetaInterface.TYPE_STRING && !f.isEmptyString() ) {
        try {
          ValueMetaInterface vd = v.clone();
          if ( !vacio( f.getReplaceMask() ) ) {
            vd.setConversionMask( f.getReplaceMask() );
          }
          lit = TradCondicion.valorPy( vd.convertData( fuente( f.getReplaceMask(), null, null ), valor ), v.getType(),
            ctx );
        } catch ( Exception e ) {
          // texto
        }
      }
      if ( vacio( valor ) && !f.isEmptyString() ) {
        lit = "None";
      }
      cols.poner( f.getFieldName(), ctx.pandas() ? lit : lit.startsWith( "F." ) ? lit : "F.lit(" + lit + ")"
        + ( v != null && "None".equals( lit ) ? ".cast(\"" + tipoSpark( v.getType() ) + "\")" : "" ) );
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /** Add constants: columnas nuevas con valor fijo (convertido a su tipo con su máscara). */
  static Bloque constantes( StepMeta s, CtxTrad ctx ) {
    ConstantMeta m = (ConstantMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    Columnas cols = new Columnas();
    for ( int i = 0; i < m.getFieldName().length; i++ ) {
      int tipo = ValueMetaFactory.getIdForValueMeta( m.getFieldType()[ i ] );
      String valor = m.getValue()[ i ];
      boolean vacia = m.isSetEmptyString() != null && m.isSetEmptyString()[ i ];
      String lit;
      if ( vacia ) {
        lit = "\"\"";
      } else if ( vacio( valor ) ) {
        lit = "None";
      } else {
        try {
          ValueMetaInterface vd = ValueMetaFactory.createValueMeta( "v", tipo );
          if ( !vacio( de( m.getFieldFormat(), i ) ) ) {
            vd.setConversionMask( m.getFieldFormat()[ i ] );
          }
          if ( !vacio( de( m.getDecimal(), i ) ) ) {
            vd.setDecimalSymbol( m.getDecimal()[ i ] );
          }
          if ( !vacio( de( m.getGroup(), i ) ) ) {
            vd.setGroupingSymbol( m.getGroup()[ i ] );
          }
          lit = TradCondicion.valorPy( vd.convertData( fuente( de( m.getFieldFormat(), i ), de( m.getDecimal(), i ), de(
            m.getGroup(), i ) ), valor ), tipo, ctx );
        } catch ( Exception e ) {
          lit = CtxTrad.lit( valor );
          b.aproximado( ctx.t( "No se pudo convertir la constante «" + valor + "».", "Could not convert constant '"
            + valor + "'.", "Não foi possível converter a constante «" + valor + "»." ) );
        }
      }
      String e;
      if ( pd ) {
        e = tipo == ValueMetaInterface.TYPE_INTEGER ? "pd.Series(" + lit + ", index=" + out + ".index, dtype=\"Int64\")"
          : lit;
      } else {
        e = lit.startsWith( "F." ) ? lit : "F.lit(" + lit + ")" + ( "None".equals( lit ) ? ".cast(\"" + tipoSpark( tipo )
          + "\")" : "" );
      }
      cols.poner( m.getFieldName()[ i ], e );
    }
    cols.escribir( b, ctx, out, in );
    b.vista = out;
    return b;
  }

  /** Add sequence: contador desde un valor con incremento (y máximo: vuelve a empezar). */
  static Bloque secuencia( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String campo = CtxTrad.hijo( x, "valuename" );
    if ( "Y".equalsIgnoreCase( CtxTrad.hijo( x, "use_database" ) ) ) {
      b.aproximado( ctx.t( "Secuencia de base de datos («" + CtxTrad.hijo( x, "seqname" ) + "»): se numera en Python "
        + "desde 1; usa la secuencia de la base si la necesitas.", "Database sequence: numbered in Python from 1; use "
          + "the database sequence if you need it.", "Sequência de base de dados: numera-se em Python a partir de 1." ) );
    }
    String desde = vacio( CtxTrad.hijo( x, "start_at" ) ) ? "1" : ctx.texto( CtxTrad.hijo( x, "start_at" ) );
    String paso = vacio( CtxTrad.hijo( x, "increment_by" ) ) ? "1" : ctx.texto( CtxTrad.hijo( x, "increment_by" ) );
    String max = CtxTrad.hijo( x, "max_value" );
    desde = desde.startsWith( "\"" ) ? desde.replace( "\"", "" ) : "int(" + desde + ")";
    paso = paso.startsWith( "\"" ) ? paso.replace( "\"", "" ) : "int(" + paso + ")";
    boolean conMax = !vacio( max ) && !max.trim().equals( "999999999" ) && max.trim().matches( "\\d+" );
    if ( ctx.pandas() ) {
      String e = "np.arange(len(" + out + "))";
      e = conMax ? "pd.Series(" + desde + " + (" + e + " * " + paso + ") % (" + max.trim() + " - " + desde + " + 1), index="
        + out + ".index, dtype=\"Int64\")" : "pd.Series(" + desde + " + " + e + " * " + paso + ", index=" + out
          + ".index, dtype=\"Int64\")";
      b.l( out + " = " + in + ".copy()" );
      b.l( out + "[" + CtxTrad.lit( campo ) + "] = " + e );
    } else {
      b.l( out + " = " + in + ".withColumn(" + CtxTrad.lit( campo ) + ", " + desde + " + (F.row_number().over(Window"
        + ".orderBy(F.monotonically_increasing_id())) - 1) * " + paso + ")" );
      if ( conMax ) {
        b.aproximado( ctx.t( "El máximo de la secuencia no se aplica en PySpark.", "The sequence maximum is not "
          + "applied in PySpark.", "O máximo da sequência não se aplica em PySpark." ) );
      }
    }
    b.vista = out;
    return b;
  }

  /** Add a checksum: CRC32 / ADLER32 / MD5 / SHA-1 / SHA-256 de los campos concatenados. */
  static Bloque checksum( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    String tipo = CtxTrad.hijo( x, "checksumtype" ).toUpperCase( java.util.Locale.ROOT );
    String res = CtxTrad.hijo( x, "resultfieldName" );
    List<String> campos = new ArrayList<>();
    for ( Node n : XMLHandler.getNodes( XMLHandler.getSubNode( x, "fields" ), "field" ) ) {
      campos.add( XMLHandler.getTagValue( n, "name" ) );
    }
    if ( campos.isEmpty() ) {
      for ( ValueMetaInterface v : ctx.camposEntrada( s ).getValueMetaList() ) {
        campos.add( v.getName() );
      }
    }
    List<String> lits = new ArrayList<>();
    for ( String c : campos ) {
      lits.add( CtxTrad.lit( c ) );
    }
    b.aproximado( ctx.t( "PDI concatena los valores con su formato de texto; números y fechas pueden diferir.",
      "PDI concatenates values with their text format; numbers and dates may differ.", "O PDI concatena os valores "
        + "com o seu formato de texto; números e datas podem diferir." ) );
    String alg = tipo.contains( "ADLER" ) ? "adler32" : tipo.contains( "MD5" ) ? "md5" : tipo.contains( "256" )
      ? "sha256" : tipo.contains( "SHA" ) ? "sha1" : "crc32";
    if ( pd ) {
      String txt = in + "[[" + String.join( ", ", lits ) + "]].astype(\"string\").fillna(\"\").agg(\"\".join, axis=1)";
      String e;
      if ( alg.endsWith( "32" ) ) {
        ctx.importar( "import zlib" );
        e = txt + ".map(lambda t: zlib." + alg + "(t.encode(\"utf-8\"))).astype(\"Int64\")";
      } else {
        ctx.importar( "import hashlib" );
        e = txt + ".map(lambda t: hashlib." + alg + "(t.encode(\"utf-8\")).hexdigest())";
      }
      b.l( out + " = " + in + ".copy()" );
      b.l( out + "[" + CtxTrad.lit( res ) + "] = " + e );
    } else {
      List<String> cs = new ArrayList<>();
      for ( String l : lits ) {
        cs.add( "F.coalesce(F.col(" + l + ").cast(\"string\"), F.lit(\"\"))" );
      }
      String txt = "F.concat(" + String.join( ", ", cs ) + ")";
      String e = alg.equals( "crc32" ) ? "F.crc32(" + txt + ")" : alg.equals( "md5" ) ? "F.md5(" + txt + ")" : alg
        .equals( "sha1" ) ? "F.sha1(" + txt + ")" : alg.equals( "sha256" ) ? "F.sha2(" + txt + ", 256)" : null;
      if ( e == null ) {
        ctx.importar( "import zlib" );
        e = "F.udf(lambda t: zlib.adler32(t.encode(\"utf-8\")), \"long\")(" + txt + ")";
      }
      b.l( out + " = " + in + ".withColumn(" + CtxTrad.lit( res ) + ", " + e + ")" );
    }
    b.vista = out;
    return b;
  }

  /** Regex evaluation: ¿cumple? (campo lógico) y grupos de captura como campos nuevos. */
  static Bloque regexEval( StepMeta s, CtxTrad ctx ) {
    RegexEvalMeta m = (RegexEvalMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    StringBuilder flags = new StringBuilder();
    if ( m.isCaseInsensitiveFlagSet() ) {
      flags.append( 'i' );
    }
    if ( m.isDotAllFlagSet() ) {
      flags.append( 's' );
    }
    if ( m.isMultilineFlagSet() ) {
      flags.append( 'm' );
    }
    if ( m.isCommentFlagSet() ) {
      flags.append( 'x' );
    }
    String re = ( flags.length() > 0 ? "(?" + flags + ")" : "" ) + ( m.getScript() == null ? "" : m.getScript() );
    String col = c( m.getMatcher(), pd ? out : null, ctx );
    Columnas cols = new Columnas();
    String[] grupos = m.isAllowCaptureGroupsFlagSet() && m.getFieldName() != null ? m.getFieldName() : new String[ 0 ];
    if ( !vacio( m.getResultFieldName() ) ) {
      cols.poner( m.getResultFieldName(), pd ? col + ".str.fullmatch(" + CtxTrad.lit( re ) + ", na=False)"
        : "F.coalesce(" + col + ".rlike(" + CtxTrad.lit( "^(?:" + re + ")$" ) + "), F.lit(False))" );
    }
    if ( grupos.length > 0 ) {
      if ( pd ) {
        String g = ctx.nuevo( "grupos" );
        b.l( "# " + ctx.t( "grupos de captura (sólo si toda la cadena cumple)", "capture groups (only if the whole "
          + "string matches)", "grupos de captura (só se toda a cadeia cumpre)" ) );
        cols.despues.add( g + " = " + out + "[" + CtxTrad.lit( m.getMatcher() ) + "].str.extract(" + CtxTrad.lit( "^(?:"
          + re + ")$" ) + ")" );
        for ( int i = 0; i < grupos.length; i++ ) {
          int tipo = m.getFieldType()[ i ];
          String e = g + "[" + i + "]";
          if ( tipo != ValueMetaInterface.TYPE_STRING && tipo != ValueMetaInterface.TYPE_NONE ) {
            try {
              e = cambiarTipo( e, ValueMetaFactory.createValueMeta( "s", ValueMetaInterface.TYPE_STRING ), tipo, de( m
                .getFieldFormat(), i ), de( m.getFieldDecimal(), i ), de( m.getFieldGroup(), i ), ctx, b );
            } catch ( Exception ex ) {
              // texto
            }
          }
          cols.despues.add( out + "[" + CtxTrad.lit( grupos[ i ] ) + "] = " + e );
        }
      } else {
        for ( int i = 0; i < grupos.length; i++ ) {
          String e = "F.when(" + col + ".rlike(" + CtxTrad.lit( "^(?:" + re + ")$" ) + "), F.regexp_extract(" + col + ", "
            + CtxTrad.lit( "^(?:" + re + ")$" ) + ", " + ( i + 1 ) + "))";
          int tipo = m.getFieldType()[ i ];
          if ( tipo != ValueMetaInterface.TYPE_STRING && tipo != ValueMetaInterface.TYPE_NONE ) {
            try {
              e = cambiarTipo( e, ValueMetaFactory.createValueMeta( "s", ValueMetaInterface.TYPE_STRING ), tipo, de( m
                .getFieldFormat(), i ), de( m.getFieldDecimal(), i ), de( m.getFieldGroup(), i ), ctx, b );
            } catch ( Exception ex ) {
              // texto
            }
          }
          cols.poner( grupos[ i ], e );
        }
      }
    }
    cols.escribir( b, ctx, out, in );
    if ( m.isReplacefields() ) {
      b.nota( ctx.t( "«Reemplazar campos»: los grupos con el nombre de un campo existente lo sustituyen.",
        "'Replace fields': groups named like an existing field replace it.", "«Substituir campos»: os grupos com o "
          + "nome de um campo existente substituem-no." ) );
    }
    b.vista = out;
    return b;
  }

  /** Data validator: las filas que no cumplen van al hop de error (como PDI con manejo de errores). */
  static Bloque validador( StepMeta s, CtxTrad ctx ) {
    ValidatorMeta m = (ValidatorMeta) s.getStepMetaInterface();
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    RowMetaInterface campos = ctx.camposEntrada( s );
    List<String> reglas = new ArrayList<>();
    List<String> codigos = new ArrayList<>();
    for ( Validation v : m.getValidations() ) {
      String col = c( v.getFieldName(), in, ctx );
      ValueMetaInterface vm = campos.searchValueMeta( v.getFieldName() );
      boolean txt = vm == null || vm.getType() == ValueMetaInterface.TYPE_STRING;
      String nulo = txt ? ( pd ? col + ".fillna(\"\").eq(\"\")" : "F.coalesce(" + col + ", F.lit(\"\")) == \"\"" ) : pd
        ? col + ".isna()" : col + ".isNull()";
      List<String> ok = new ArrayList<>();
      if ( !v.isNullAllowed() ) {
        ok.add( "~(" + nulo + ")" );
      }
      if ( v.isOnlyNullAllowed() ) {
        ok.add( "(" + nulo + ")" );
      }
      String s2 = txt ? col : pd ? col + ".astype(\"string\")" : col + ".cast(\"string\")";
      String ss = pd ? s2 + ".str" : s2;
      if ( !vacio( v.getMaximumLength() ) ) {
        ok.add( pd ? "(" + ss + ".len() <= " + v.getMaximumLength() + ")" : "(F.length(" + s2 + ") <= " + v
          .getMaximumLength() + ")" );
      }
      if ( !vacio( v.getMinimumLength() ) ) {
        ok.add( pd ? "(" + ss + ".len() >= " + v.getMinimumLength() + ")" : "(F.length(" + s2 + ") >= " + v
          .getMinimumLength() + ")" );
      }
      if ( v.isOnlyNumericAllowed() ) {
        ok.add( pd ? ss + ".fullmatch(r\"[0-9]*\", na=False)" : s2 + ".rlike(\"^[0-9]*$\")" );
      }
      if ( !vacio( v.getStartString() ) ) {
        ok.add( pd ? ss + ".startswith(" + CtxTrad.lit( v.getStartString() ) + ", na=False)" : s2 + ".startswith(" + CtxTrad.lit( v
          .getStartString() ) + ")" );
      }
      if ( !vacio( v.getEndString() ) ) {
        ok.add( pd ? ss + ".endswith(" + CtxTrad.lit( v.getEndString() ) + ", na=False)" : s2 + ".endswith(" + CtxTrad.lit( v
          .getEndString() ) + ")" );
      }
      if ( !vacio( v.getStartStringNotAllowed() ) ) {
        ok.add( "~" + ( pd ? ss + ".startswith(" + CtxTrad.lit( v.getStartStringNotAllowed() ) + ", na=False)" : s2
          + ".startswith(" + CtxTrad.lit( v.getStartStringNotAllowed() ) + ")" ) );
      }
      if ( !vacio( v.getEndStringNotAllowed() ) ) {
        ok.add( "~" + ( pd ? ss + ".endswith(" + CtxTrad.lit( v.getEndStringNotAllowed() ) + ", na=False)" : s2 + ".endswith("
          + CtxTrad.lit( v.getEndStringNotAllowed() ) + ")" ) );
      }
      if ( !vacio( v.getRegularExpression() ) ) {
        ok.add( pd ? ss + ".fullmatch(" + CtxTrad.lit( v.getRegularExpression() ) + ", na=False)" : s2 + ".rlike(" + CtxTrad.lit(
          "^(?:" + v.getRegularExpression() + ")$" ) + ")" );
      }
      if ( !vacio( v.getRegularExpressionNotAllowed() ) ) {
        ok.add( "~" + ( pd ? ss + ".fullmatch(" + CtxTrad.lit( v.getRegularExpressionNotAllowed() ) + ", na=False)" : s2
          + ".rlike(" + CtxTrad.lit( "^(?:" + v.getRegularExpressionNotAllowed() + ")$" ) + ")" ) );
      }
      String[] permitidos = v.getAllowedValues();
      if ( permitidos != null && permitidos.length > 0 ) {
        List<String> l = new ArrayList<>();
        for ( String p : permitidos ) {
          l.add( CtxTrad.lit( p ) );
        }
        ok.add( pd ? s2 + ".isin([" + String.join( ", ", l ) + "])" : s2 + ".isin(" + String.join( ", ", l ) + ")" );
      }
      if ( !vacio( v.getMinimumValue() ) || !vacio( v.getMaximumValue() ) ) {
        String num = txt ? ( pd ? "pd.to_numeric(" + col + ", errors=\"coerce\")" : col + ".cast(\"double\")" ) : col;
        if ( !vacio( v.getMinimumValue() ) ) {
          ok.add( "(" + num + " >= " + v.getMinimumValue() + ")" );
        }
        if ( !vacio( v.getMaximumValue() ) ) {
          ok.add( "(" + num + " <= " + v.getMaximumValue() + ")" );
        }
      }
      if ( v.isSourcingValues() ) {
        b.aproximado( ctx.t( "Valores permitidos leídos de otro step: no se traducen.", "Allowed values read from "
          + "another step: not translated.", "Valores permitidos lidos de outro step: não traduzidos." ) );
      }
      if ( ok.isEmpty() ) {
        continue;
      }
      // una regla que no es de nulos no se evalúa sobre nulos (PDI deja pasar el nulo si se permite)
      String r = String.join( " & ", ok );
      if ( v.isNullAllowed() && !v.isOnlyNullAllowed() ) {
        r = "(" + nulo + ") | (" + r + ")";
      }
      String vr = ctx.nuevo( "ok_" + CtxTrad.ident( vacio( v.getName() ) ? v.getFieldName() : v.getName() ) );
      b.l( vr + " = " + ( pd ? "(" + r + ").fillna(False)" : "F.coalesce(" + r + ", F.lit(False))" ) );
      reglas.add( vr );
      codigos.add( CtxTrad.lit( vacio( v.getErrorCode() ) ? "KVD000" : v.getErrorCode() ) );
    }
    String todo = reglas.isEmpty() ? ( pd ? "pd.Series(True, index=" + in + ".index)" : "F.lit(True)" ) : String.join(
      " & ", reglas );
    String valido = ctx.nuevo( "valido_" + CtxTrad.ident( s.getName() ) );
    b.l( valido + " = " + todo );
    b.l( out + " = " + in + ( pd ? "[" + valido + "].reset_index(drop=True)" : ".filter(" + valido + ")" ) );
    if ( CtxTrad.tieneError( s ) ) {
      String err = ctx.dfError( s );
      String campoCod = s.getStepErrorMeta().getErrorCodesValuename();
      String campoN = s.getStepErrorMeta().getNrErrorsValuename();
      if ( pd ) {
        b.l( err + " = " + in + "[~" + valido + "].copy()" );
        if ( !vacio( campoN ) ) {
          b.l( err + "[" + CtxTrad.lit( campoN ) + "] = (~pd.concat([" + String.join( ", ", reglas ) + "], axis=1))"
            + ".sum(axis=1)[~" + valido + "].astype(\"Int64\")" );
        }
        if ( !vacio( campoCod ) && !reglas.isEmpty() ) {
          b.l( err + "[" + CtxTrad.lit( campoCod ) + "] = np.select([" + negar( reglas ) + "], [" + String.join( ", ",
            codigos ) + "], default=None)[~" + valido + ".to_numpy()]" );
        }
        b.l( err + " = " + err + ".reset_index(drop=True)" );
      } else {
        StringBuilder w = new StringBuilder();
        for ( int i = 0; i < reglas.size(); i++ ) {
          w.append( i == 0 ? "F" : "" ).append( ".when(~" ).append( reglas.get( i ) ).append( ", " ).append( codigos
            .get( i ) ).append( ")" );
        }
        b.l( err + " = " + in + ".filter(~" + valido + ")" + ( !vacio( campoCod ) && w.length() > 0 ? ".withColumn("
          + CtxTrad.lit( campoCod ) + ", " + w + ")" : "" ) );
      }
      ctx.ponerSalidaError( s, err );
      b.nota( ctx.t( "Las filas que no cumplen van al hop de error con el código de la primera regla que fallan.",
        "Failing rows go to the error hop with the code of the first rule they fail.", "As linhas que não cumprem vão "
          + "para o hop de erro com o código da primeira regra que falham." ) );
    } else {
      b.nota( ctx.t( "Sin manejo de errores, PDI se detiene en la primera fila no válida; aquí se descartan.",
        "Without error handling PDI stops at the first invalid row; here they are dropped.", "Sem tratamento de "
          + "erros o PDI para na primeira linha inválida; aqui são descartadas." ) );
    }
    if ( m.isConcatenatingErrors() ) {
      b.aproximado( ctx.t( "«Concatenar errores»: se pone sólo el código de la primera regla.", "'Concatenate "
        + "errors': only the first rule's code is set.", "«Concatenar erros»: põe-se só o código da primeira regra." ) );
    }
    b.vista = out;
    return b;
  }

  private static String negar( List<String> l ) {
    List<String> r = new ArrayList<>();
    for ( String x : l ) {
      r.add( "~" + x );
    }
    return String.join( ", ", r );
  }

  /** Clone row: cada fila se repite N veces (más la original), con marca y número de copia opcionales. */
  static Bloque cloneRow( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean pd = ctx.pandas();
    boolean nEnCampo = "Y".equalsIgnoreCase( CtxTrad.hijo( x, "nrcloneinfield" ) );
    String n = nEnCampo ? c( CtxTrad.hijo( x, "nrclonefield" ), in, ctx ) : CtxTrad.hijo( x, "nrclones" ).trim();
    if ( !nEnCampo && !n.matches( "\\d+" ) ) {
      n = "int(" + ctx.texto( n ) + ")";
    }
    String marca = "Y".equalsIgnoreCase( CtxTrad.hijo( x, "addcloneflag" ) ) ? CtxTrad.hijo( x, "cloneflagfield" ) : null;
    String num = "Y".equalsIgnoreCase( CtxTrad.hijo( x, "addclonenum" ) ) ? CtxTrad.hijo( x, "clonenumfield" ) : null;
    if ( pd ) {
      String rep = ctx.nuevo( "veces" );
      b.l( rep + " = " + ( nEnCampo ? n + ".fillna(0).astype(int) + 1" : n + " + 1" ) );
      b.l( out + " = " + in + ".loc[" + in + ".index.repeat(" + rep + ")]" );
      b.l( out + " = " + out + ".assign(_copia=" + out + ".groupby(level=0).cumcount()).reset_index(drop=True)" );
      if ( marca != null ) {
        b.l( out + "[" + CtxTrad.lit( marca ) + "] = " + out + "[\"_copia\"] > 0" );
      }
      if ( num != null ) {
        b.l( out + "[" + CtxTrad.lit( num ) + "] = " + out + "[\"_copia\"].astype(\"Int64\")" );
      }
      b.l( out + " = " + out + ".drop(columns=[\"_copia\"])" );
    } else {
      String sec = nEnCampo ? "F.sequence(F.lit(0), F.coalesce(" + n + ".cast(\"int\"), F.lit(0)))" : "F.sequence(F.lit(0), "
        + "F.lit(" + n + "))";
      b.l( out + " = " + in + ".withColumn(\"_copia\", F.explode(" + sec + "))" );
      if ( marca != null ) {
        b.l( out + " = " + out + ".withColumn(" + CtxTrad.lit( marca ) + ", F.col(\"_copia\") > 0)" );
      }
      if ( num != null ) {
        b.l( out + " = " + out + ".withColumn(" + CtxTrad.lit( num ) + ", F.col(\"_copia\").cast(\"long\"))" );
      }
      b.l( out + " = " + out + ".drop(\"_copia\")" );
    }
    b.vista = out;
    return b;
  }
}
