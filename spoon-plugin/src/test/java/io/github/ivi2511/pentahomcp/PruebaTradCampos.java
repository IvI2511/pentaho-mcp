package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.Condition;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaAndData;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.calculator.CalculatorMeta;
import org.pentaho.di.trans.steps.calculator.CalculatorMetaFunction;
import org.pentaho.di.trans.steps.datagrid.DataGridMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;
import org.pentaho.di.trans.steps.filterrows.FilterRowsMeta;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.CtxTrad;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;

/**
 * Traductores de campos y condiciones (0.8.0, parte B): cada caso es una transformación pequeña (Data grid → steps)
 * que corre en Kettle dentro de la JVM; el mismo caso se traduce a pandas y a PySpark, se corre con los mismos datos
 * y se comparan las filas de cada step capturado (números con 6 decimales, fechas ISO, nulo = texto vacío).
 */
public class PruebaTradCampos {

  /** Un caso: la transformación, los steps cuya salida se compara y lo que dio Kettle. */
  private static final class Caso {
    final String nombre;
    final TransMeta tm;
    final List<String> capturas;
    final Map<String, List<String>> kettle = new LinkedHashMap<>();
    final Map<String, List<String>> cabeceras = new LinkedHashMap<>();
    /** Columnas que no se comparan en PySpark (aproximadas). */
    final List<String> sinSpark = new ArrayList<>();

    Caso( String nombre, TransMeta tm, String... capturas ) {
      this.nombre = nombre;
      this.tm = tm;
      this.capturas = Arrays.asList( capturas );
    }
  }

  private final List<Caso> casos = new ArrayList<>();
  private final List<String> erroresKettle = new ArrayList<>();
  private boolean armado;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  // ================================================================ armar transformaciones

  private static final String[] CAMPOS = { "id", "n", "s", "d", "b", "t" };
  private static final String[] TIPOS = { "Integer", "Number", "String", "Date", "Integer", "String" };
  private static final String[][] FILAS = {
    { "1", "2.5", "abc", "2024-01-05 10:30:00", "4", " Hola mundo " },
    { "2", "-2.5", "xyz", "2024-02-29 00:00:00", "-3", "a,b;c" },
    { "3", "", "", "2023-12-31 23:59:59", "0", "" },
    { "", "10.125", "axc", "2022-06-30 18:45:00", "7", "123-abc" },
    { "5", "0.5", "Abc", "2024-07-15 12:00:00", "2", "x&y<z>" },
    { "-7", "1.5", "bcd", "2020-02-29 08:15:30", "6", "tab\tfin" } };

  private static StepMeta paso( String nombre, StepMetaInterface m ) {
    String id = PluginRegistry.getInstance().getPluginId( StepPluginType.class, m );
    return new StepMeta( id, nombre, m );
  }

  private static TransMeta trans( String nombre ) {
    TransMeta tm = new TransMeta();
    tm.setName( nombre );
    DataGridMeta g = new DataGridMeta();
    g.allocate( CAMPOS.length );
    g.setFieldName( CAMPOS.clone() );
    g.setFieldType( TIPOS.clone() );
    String[] fmt = new String[ CAMPOS.length ];
    String[] dec = new String[ CAMPOS.length ];
    String[] grp = new String[ CAMPOS.length ];
    String[] cur = new String[ CAMPOS.length ];
    String[] nul = new String[ CAMPOS.length ];
    int[] len = new int[ CAMPOS.length ];
    int[] pre = new int[ CAMPOS.length ];
    for ( int i = 0; i < CAMPOS.length; i++ ) {
      fmt[ i ] = TIPOS[ i ].equals( "Date" ) ? "yyyy-MM-dd HH:mm:ss" : "";
      dec[ i ] = ".";
      grp[ i ] = "";
      cur[ i ] = "";
      nul[ i ] = "";
      len[ i ] = -1;
      pre[ i ] = -1;
    }
    g.setFieldFormat( fmt );
    g.setDecimal( dec );
    g.setGroup( grp );
    g.setCurrency( cur );
    g.setFieldNullIf( nul );
    g.setFieldLength( len );
    g.setFieldPrecision( pre );
    g.setEmptyString( new boolean[ CAMPOS.length ] );
    List<List<String>> filas = new ArrayList<>();
    for ( String[] f : FILAS ) {
      filas.add( new ArrayList<>( Arrays.asList( f ) ) );
    }
    g.setDataLines( filas );
    StepMeta sg = paso( "Datos", g );
    sg.setDistributes( false );
    tm.addStep( sg );
    return tm;
  }

  private static StepMeta agregar( TransMeta tm, String nombre, StepMetaInterface m, String desde ) {
    StepMeta s = paso( nombre, m );
    s.setLocation( 100 * tm.nrSteps(), 100 );
    tm.addStep( s );
    if ( desde != null ) {
      tm.addTransHop( new TransHopMeta( tm.findStep( desde ), s ) );
    }
    return s;
  }

  private static StepMeta dummy( TransMeta tm, String nombre, String desde ) {
    return agregar( tm, nombre, new DummyTransMeta(), desde );
  }

  /** Un step desde su XML (para los que son plugins o tienen muchos campos). */
  private static StepMetaInterface desdeXml( String id, String xml ) throws Exception {
    StepMetaInterface m = (StepMetaInterface) PluginRegistry.getInstance().loadClass( PluginRegistry.getInstance()
      .findPluginWithId( StepPluginType.class, id ) );
    m.loadXML( XMLHandler.loadXMLString( "<step>" + xml + "</step>" ).getDocumentElement(), new ArrayList<>(),
      (org.pentaho.metastore.api.IMetaStore) null );
    return m;
  }

  private static Condition cond( String izq, String f, Object constante ) throws Exception {
    return new Condition( izq, Condition.getFunction( f ), null, constante == null ? null : new ValueMetaAndData(
      "constant", constante ) );
  }

  private static Condition condCampo( String izq, String f, String der ) {
    return new Condition( izq, Condition.getFunction( f ), der, null );
  }

  private void filtro( TransMeta tm, String nombre, Condition c ) {
    FilterRowsMeta f = new FilterRowsMeta();
    f.setDefault();
    f.setCondition( c );
    StepMeta s = agregar( tm, nombre, f, "Datos" );
    StepMeta si = dummy( tm, nombre + " si", nombre );
    StepMeta no = dummy( tm, nombre + " no", nombre );
    f.getStepIOMeta().getTargetStreams().get( 0 ).setStepMeta( si );
    f.getStepIOMeta().getTargetStreams().get( 1 ).setStepMeta( no );
    s.setDistributes( true );
  }

  // ================================================================ casos

  private TransMeta casoCondiciones() throws Exception {
    TransMeta tm = trans( "condiciones" );
    filtro( tm, "F01", cond( "id", "<", 3L ) );
    filtro( tm, "F02", cond( "n", ">=", 1.5 ) );
    Condition c3 = cond( "s", "=", "abc" );
    c3.setNegated( true );
    filtro( tm, "F03", c3 );
    filtro( tm, "F04", cond( "s", "REGEXP", "[ab].*" ) );
    filtro( tm, "F05", cond( "s", "IS NULL", null ) );
    filtro( tm, "F06", cond( "s", "IN LIST", "abc;xyz;bcd" ) );
    filtro( tm, "F07", cond( "s", "CONTAINS", "b" ) );
    filtro( tm, "F08", cond( "s", "STARTS WITH", "a" ) );
    filtro( tm, "F09", cond( "s", "ENDS WITH", "c" ) );
    filtro( tm, "F10", cond( "s", "LIKE", "a%c" ) );
    Condition comp = new Condition();
    comp.addCondition( cond( "id", ">", 1L ) );
    Condition c = cond( "n", "<", 4.0 );
    c.setOperator( Condition.OPERATOR_AND );
    comp.addCondition( c );
    Condition c2 = cond( "s", "IS NOT NULL", null );
    c2.setOperator( Condition.OPERATOR_OR_NOT );
    comp.addCondition( c2 );
    filtro( tm, "F11", comp );
    Condition x = new Condition();
    x.addCondition( cond( "b", ">", 0L ) );
    Condition x2 = cond( "id", "<=", 2L );
    x2.setOperator( Condition.OPERATOR_XOR );
    x.addCondition( x2 );
    Condition x3 = cond( "s", "=", "xyz" );
    x3.setOperator( Condition.OPERATOR_AND_NOT );
    x.addCondition( x3 );
    filtro( tm, "F12", x );
    filtro( tm, "F13", condCampo( "id", "<=", "b" ) );
    filtro( tm, "F14", condCampo( "id", "<>", "b" ) );
    filtro( tm, "F15", condCampo( "id", ">", "b" ) );
    filtro( tm, "F16", cond( "d", ">", new SimpleDateFormat( "yyyy-MM-dd" ).parse( "2024-01-01" ) ) );
    filtro( tm, "F17", cond( "id", "=", "5" ) );
    filtro( tm, "F18", cond( "n", "<>", 2.5 ) );
    filtro( tm, "F19", condCampo( "id", "=", "b" ) );
    filtro( tm, "F20", cond( "b", "IN LIST", "4;0;2" ) );
    List<String> cap = new ArrayList<>();
    for ( int i = 1; i <= 20; i++ ) {
      cap.add( String.format( "F%02d si", i ) );
      cap.add( String.format( "F%02d no", i ) );
    }
    casos.add( new Caso( "condiciones", tm, cap.toArray( new String[ 0 ] ) ) );
    return tm;
  }

  private static CalculatorMetaFunction calc( String nombre, int tipo, String a, String b, String c, int tipoValor ) {
    CalculatorMetaFunction f = new CalculatorMetaFunction();
    f.setFieldName( nombre );
    f.setCalcType( tipo );
    f.setFieldA( a );
    f.setFieldB( b );
    f.setFieldC( c );
    f.setValueType( tipoValor );
    f.setValueLength( -1 );
    f.setValuePrecision( -1 );
    return f;
  }

  private void casoCalculadora() throws Exception {
    TransMeta tm = trans( "calculadora" );
    CalculatorMeta m = new CalculatorMeta();
    int I = ValueMetaInterface.TYPE_INTEGER;
    int N = ValueMetaInterface.TYPE_NUMBER;
    int S = ValueMetaInterface.TYPE_STRING;
    int D = ValueMetaInterface.TYPE_DATE;
    List<CalculatorMetaFunction> l = new ArrayList<>();
    l.add( calc( "c_const", CalculatorMetaFunction.CALC_CONSTANT, "7", null, null, I ) );
    l.add( calc( "c_dos", CalculatorMetaFunction.CALC_CONSTANT, "2", null, null, I ) );
    l.add( calc( "c_copia", CalculatorMetaFunction.CALC_COPY_OF_FIELD, "s", null, null, S ) );
    l.add( calc( "c_suma", CalculatorMetaFunction.CALC_ADD, "id", "b", null, I ) );
    l.add( calc( "c_sumastr", CalculatorMetaFunction.CALC_ADD, "s", "t", null, S ) );
    l.add( calc( "c_resta", CalculatorMetaFunction.CALC_SUBTRACT, "n", "b", null, N ) );
    l.add( calc( "c_mult", CalculatorMetaFunction.CALC_MULTIPLY, "n", "b", null, N ) );
    l.add( calc( "c_div", CalculatorMetaFunction.CALC_DIVIDE, "n", "c_dos", null, N ) );
    l.add( calc( "c_divint", CalculatorMetaFunction.CALC_DIVIDE, "b", "c_dos", null, I ) );
    l.add( calc( "c_cuad", CalculatorMetaFunction.CALC_SQUARE, "n", null, null, N ) );
    l.add( calc( "c_pct1", CalculatorMetaFunction.CALC_PERCENT_1, "n", "c_dos", null, N ) );
    l.add( calc( "c_pct2", CalculatorMetaFunction.CALC_PERCENT_2, "n", "c_dos", null, N ) );
    l.add( calc( "c_pct3", CalculatorMetaFunction.CALC_PERCENT_3, "n", "c_dos", null, N ) );
    l.add( calc( "c_comb1", CalculatorMetaFunction.CALC_COMBINATION_1, "n", "b", "c_dos", N ) );
    l.add( calc( "c_round1", CalculatorMetaFunction.CALC_ROUND_1, "n", null, null, N ) );
    l.add( calc( "c_round2", CalculatorMetaFunction.CALC_ROUND_2, "n", "c_dos", null, N ) );
    l.add( calc( "c_rstd1", CalculatorMetaFunction.CALC_ROUND_STD_1, "n", null, null, N ) );
    l.add( calc( "c_rstd2", CalculatorMetaFunction.CALC_ROUND_STD_2, "n", "c_dos", null, N ) );
    l.add( calc( "c_ceil", CalculatorMetaFunction.CALC_CEIL, "n", null, null, N ) );
    l.add( calc( "c_floor", CalculatorMetaFunction.CALC_FLOOR, "n", null, null, N ) );
    l.add( calc( "c_abs", CalculatorMetaFunction.CALC_ABS, "n", null, null, N ) );
    l.add( calc( "c_nvl", CalculatorMetaFunction.CALC_NVL, "n", "c_dos", null, N ) );
    l.add( calc( "c_nvls", CalculatorMetaFunction.CALC_NVL, "s", "t", null, S ) );
    l.add( calc( "c_resto", CalculatorMetaFunction.CALC_REMAINDER, "b", "c_dos", null, I ) );
    l.add( calc( "c_masdias", CalculatorMetaFunction.CALC_ADD_DAYS, "d", "c_dos", null, D ) );
    l.add( calc( "c_masmeses", CalculatorMetaFunction.CALC_ADD_MONTHS, "d", "c_dos", null, D ) );
    l.add( calc( "c_mashoras", CalculatorMetaFunction.CALC_ADD_HOURS, "d", "c_dos", null, D ) );
    l.add( calc( "c_anio", CalculatorMetaFunction.CALC_YEAR_OF_DATE, "d", null, null, I ) );
    l.add( calc( "c_mes", CalculatorMetaFunction.CALC_MONTH_OF_DATE, "d", null, null, I ) );
    l.add( calc( "c_diaanio", CalculatorMetaFunction.CALC_DAY_OF_YEAR, "d", null, null, I ) );
    l.add( calc( "c_diames", CalculatorMetaFunction.CALC_DAY_OF_MONTH, "d", null, null, I ) );
    l.add( calc( "c_diasem", CalculatorMetaFunction.CALC_DAY_OF_WEEK, "d", null, null, I ) );
    l.add( calc( "c_semiso", CalculatorMetaFunction.CALC_WEEK_OF_YEAR_ISO8601, "d", null, null, I ) );
    l.add( calc( "c_anioiso", CalculatorMetaFunction.CALC_YEAR_OF_DATE_ISO8601, "d", null, null, I ) );
    l.add( calc( "c_trim", CalculatorMetaFunction.CALC_QUARTER_OF_DATE, "d", null, null, I ) );
    l.add( calc( "c_hora", CalculatorMetaFunction.CALC_HOUR_OF_DAY, "d", null, null, I ) );
    l.add( calc( "c_min", CalculatorMetaFunction.CALC_MINUTE_OF_HOUR, "d", null, null, I ) );
    l.add( calc( "c_seg", CalculatorMetaFunction.CALC_SECOND_OF_MINUTE, "d", null, null, I ) );
    l.add( calc( "c_sinhora", CalculatorMetaFunction.CALC_REMOVE_TIME_FROM_DATE, "d", null, null, D ) );
    l.add( calc( "c_base", CalculatorMetaFunction.CALC_CONSTANT, "2024-03-01 00:00:00", null, null, D ) );
    l.get( l.size() - 1 ).setConversionMask( "yyyy-MM-dd HH:mm:ss" );
    l.add( calc( "c_difdias", CalculatorMetaFunction.CALC_DATE_DIFF, "d", "c_base", null, I ) );
    l.add( calc( "c_difhoras", CalculatorMetaFunction.CALC_DATE_DIFF_HR, "d", "c_base", null, I ) );
    l.add( calc( "c_mayus", CalculatorMetaFunction.CALC_UPPER_CASE, "t", null, null, S ) );
    l.add( calc( "c_minus", CalculatorMetaFunction.CALC_LOWER_CASE, "t", null, null, S ) );
    l.add( calc( "c_initcap", CalculatorMetaFunction.CALC_INITCAP, "t", null, null, S ) );
    l.add( calc( "c_largo", CalculatorMetaFunction.CALC_STRING_LEN, "t", null, null, I ) );
    l.add( calc( "c_digitos", CalculatorMetaFunction.CALC_GET_ONLY_DIGITS, "t", null, null, S ) );
    l.add( calc( "c_sindig", CalculatorMetaFunction.CALC_REMOVE_DIGITS, "t", null, null, S ) );
    l.add( calc( "c_sintab", CalculatorMetaFunction.CALC_REMOVE_TAB, "t", null, null, S ) );
    l.add( calc( "c_xml", CalculatorMetaFunction.CALC_MASK_XML, "t", null, null, S ) );
    l.add( calc( "c_sql", CalculatorMetaFunction.CALC_ESCAPE_SQL, "t", null, null, S ) );
    l.add( calc( "c_cdata", CalculatorMetaFunction.CALC_USE_CDATA, "s", null, null, S ) );
    l.add( calc( "c_leven", CalculatorMetaFunction.CALC_LEVENSHTEIN_DISTANCE, "s", "t", null, I ) );
    l.add( calc( "c_redint", CalculatorMetaFunction.CALC_ROUND_STD_1, "n", null, null, I ) );
    CalculatorMetaFunction tmp = calc( "c_tmp", CalculatorMetaFunction.CALC_COPY_OF_FIELD, "id", null, null, I );
    tmp.setRemovedFromResult( true );
    l.add( tmp );
    m.setCalculation( l.toArray( new CalculatorMetaFunction[ 0 ] ) );
    agregar( tm, "Calc", m, "Datos" );
    casos.add( new Caso( "calculadora", tm, "Calc" ) );
  }

  /** Steps de campos en cadena desde XML (como los guarda Spoon). */
  private void casoCadena() throws Exception {
    TransMeta tm = trans( "cadena" );
    agregar( tm, "Textos", desdeXml( "StringOperations", "<fields>"
      + "<field><in_stream_name>t</in_stream_name><out_stream_name>t_trim</out_stream_name><trim_type>both</trim_type>"
      + "<lower_upper>upper</lower_upper><padding_type>none</padding_type><pad_char/><pad_len/><init_cap>no</init_cap>"
      + "<mask_xml>none</mask_xml><digits>none</digits><remove_special_characters>none</remove_special_characters>"
      + "</field>"
      + "<field><in_stream_name>s</in_stream_name><out_stream_name>s_pad</out_stream_name><trim_type>none</trim_type>"
      + "<lower_upper>none</lower_upper><padding_type>left</padding_type><pad_char>*</pad_char><pad_len>5</pad_len>"
      + "<init_cap>no</init_cap><mask_xml>none</mask_xml><digits>none</digits>"
      + "<remove_special_characters>none</remove_special_characters></field>"
      + "<field><in_stream_name>t</in_stream_name><out_stream_name>t_dig</out_stream_name><trim_type>none</trim_type>"
      + "<lower_upper>none</lower_upper><padding_type>none</padding_type><pad_char/><pad_len/><init_cap>no</init_cap>"
      + "<mask_xml>escapexml</mask_xml><digits>remove_digits</digits>"
      + "<remove_special_characters>none</remove_special_characters></field></fields>" ), "Datos" );
    agregar( tm, "Reemplazar", desdeXml( "ReplaceString", "<fields>"
      + "<field><in_stream_name>s</in_stream_name><out_stream_name>s_rep</out_stream_name><use_regex>no</use_regex>"
      + "<replace_string>b</replace_string><replace_by_string>BB</replace_by_string><set_empty_string>N</set_empty_string>"
      + "<replace_field_by_string/><whole_word>no</whole_word><case_sensitive>no</case_sensitive><is_unicode>no</is_unicode>"
      + "</field>"
      + "<field><in_stream_name>t</in_stream_name><out_stream_name>t_rep</out_stream_name><use_regex>yes</use_regex>"
      + "<replace_string>([a-z])([0-9]*)</replace_string><replace_by_string>[$1]</replace_by_string>"
      + "<set_empty_string>N</set_empty_string><replace_field_by_string/><whole_word>no</whole_word>"
      + "<case_sensitive>yes</case_sensitive><is_unicode>no</is_unicode></field></fields>" ), "Textos" );
    agregar( tm, "Cortar", desdeXml( "StringCut", "<fields><field><in_stream_name>s</in_stream_name>"
      + "<out_stream_name>s_cut</out_stream_name><cut_from>1</cut_from><cut_to>3</cut_to></field></fields>" ),
      "Reemplazar" );
    agregar( tm, "Mapear", desdeXml( "ValueMapper", "<field_to_use>s</field_to_use><target_field>s_map</target_field>"
      + "<non_match_default>OTRO</non_match_default><fields><field><source_value>abc</source_value>"
      + "<target_value>A</target_value></field><field><source_value>xyz</source_value><target_value>X</target_value>"
      + "</field><field><source_value/><target_value>VACIO</target_value></field></fields>" ), "Cortar" );
    agregar( tm, "SiNulo", desdeXml( "IfNull", "<replaceAllByValue/><replaceAllMask/><selectFields>Y</selectFields>"
      + "<selectValuesType>N</selectValuesType><setEmptyStringAll>N</setEmptyStringAll><valuetypes/><fields>"
      + "<field><name>n</name><value>-1</value><mask/><set_empty_string>N</set_empty_string></field>"
      + "<field><name>t</name><value>nada</value><mask/><set_empty_string>N</set_empty_string></field>"
      + "<field><name>id</name><value>0</value><mask/><set_empty_string>N</set_empty_string></field></fields>" ),
      "Mapear" );
    agregar( tm, "Anular", desdeXml( "NullIf", "<fields><field><name>s</name><value>abc</value></field>"
      + "<field><name>t</name><value>a,b;c</value></field></fields>" ), "SiNulo" );
    agregar( tm, "Rango", desdeXml( "NumberRange", "<inputField>n</inputField><outputField>rango</outputField>"
      + "<fallBackValue>fuera</fallBackValue><rules><rule><lower_bound>-10</lower_bound><upper_bound>0</upper_bound>"
      + "<value>neg</value></rule><rule><lower_bound>0</lower_bound><upper_bound>2</upper_bound><value>bajo</value>"
      + "</rule><rule><lower_bound>2</lower_bound><upper_bound>5</upper_bound><value>medio</value></rule></rules>" ),
      "Anular" );
    agregar( tm, "Constantes", desdeXml( "Constant", "<fields><field><name>k_txt</name><type>String</type>"
      + "<format/><currency/><decimal/><group/><nullif>hola</nullif><length>-1</length><precision>-1</precision>"
      + "<set_empty_string>N</set_empty_string></field><field><name>k_num</name><type>Number</type><format/><currency/>"
      + "<decimal>.</decimal><group/><nullif>3.25</nullif><length>-1</length><precision>-1</precision>"
      + "<set_empty_string>N</set_empty_string></field><field><name>k_int</name><type>Integer</type><format/>"
      + "<currency/><decimal/><group/><nullif>42</nullif><length>-1</length><precision>-1</precision>"
      + "<set_empty_string>N</set_empty_string></field></fields>" ), "Rango" );
    agregar( tm, "Fijar", desdeXml( "SetValueConstant", "<usevar>N</usevar><fields><field><name>t</name>"
      + "<value>fijo</value><mask/><set_empty_string>N</set_empty_string></field></fields>" ), "Constantes" );
    agregar( tm, "Copiar", desdeXml( "SetValueField", "<fields><field><name>s_cut</name><replaceby>s</replaceby>"
      + "</field></fields>" ), "Fijar" );
    agregar( tm, "Expresion", desdeXml( "Janino", "<formula><field_name>j1</field_name>"
      + "<formula_string>b * 2 + 1</formula_string><value_type>Integer</value_type><value_length>-1</value_length>"
      + "<value_precision>-1</value_precision><replace_field/></formula><formula><field_name>j2</field_name>"
      + "<formula_string>b &gt; 1 ? \"grande\" : \"chico\"</formula_string><value_type>String</value_type>"
      + "<value_length>-1</value_length><value_precision>-1</value_precision><replace_field/></formula>"
      + "<formula><field_name>j3</field_name><formula_string>k_txt.toUpperCase() + \"-\" + k_int</formula_string>"
      + "<value_type>String</value_type><value_length>-1</value_length><value_precision>-1</value_precision>"
      + "<replace_field/></formula><formula><field_name>j4</field_name><formula_string>Math.abs(b) / 2"
      + "</formula_string><value_type>Integer</value_type><value_length>-1</value_length>"
      + "<value_precision>-1</value_precision><replace_field/></formula>" ), "Copiar" );
    agregar( tm, "Partir", desdeXml( "FieldSplitter", "<splitfield>k_txt</splitfield><delimiter>o</delimiter>"
      + "<enclosure/><fields><field><name>p1</name><id/><idrem>N</idrem><type>String</type><format/><group/>"
      + "<decimal/><currency/><length>-1</length><precision>-1</precision><nullif/><ifnull/><trimtype>none</trimtype>"
      + "</field><field><name>p2</name><id/><idrem>N</idrem><type>String</type><format/><group/><decimal/><currency/>"
      + "<length>-1</length><precision>-1</precision><nullif/><ifnull/><trimtype>none</trimtype></field></fields>" ),
      "Expresion" );
    agregar( tm, "Regex", desdeXml( "RegexEval", "<script>([a-z]+)([0-9]*)</script><matcher>s</matcher>"
      + "<resultfieldname>es_ok</resultfieldname><usevar>N</usevar><allowcapturegroups>Y</allowcapturegroups>"
      + "<replacefields>N</replacefields><canoneq>N</canoneq><caseinsensitive>N</caseinsensitive><comment>N</comment>"
      + "<dotall>N</dotall><multiline>N</multiline><unicode>N</unicode><unix>N</unix><fields><field><name>g1</name>"
      + "<type>String</type><format/><group/><decimal/><currency/><nullif/><ifnull/><trimtype>none</trimtype>"
      + "<length>-1</length><precision>-1</precision></field><field><name>g2</name><type>String</type><format/><group/>"
      + "<decimal/><currency/><nullif/><ifnull/><trimtype>none</trimtype><length>-1</length><precision>-1</precision>"
      + "</field></fields>" ), "Partir" );
    casos.add( new Caso( "cadena", tm, "Textos", "Reemplazar", "Cortar", "Mapear", "SiNulo", "Anular", "Rango",
      "Constantes", "Fijar", "Copiar", "Expresion", "Partir", "Regex" ) );
  }

  private void casoFlujos() throws Exception {
    TransMeta tm = trans( "flujos" );
    StepMeta sw = agregar( tm, "Switch", desdeXml( "SwitchCase", "<fieldname>s</fieldname><use_contains>N</use_contains>"
      + "<case_value_type>String</case_value_type><case_value_format/><case_value_decimal/><case_value_group/>"
      + "<default_target_step>Otros</default_target_step><cases><case><value>abc</value><target_step>Caso A</target_step>"
      + "</case><case><value>xyz</value><target_step>Caso X</target_step></case><case><value>bcd</value>"
      + "<target_step>Caso A</target_step></case></cases>" ), "Datos" );
    dummy( tm, "Caso A", "Switch" );
    dummy( tm, "Caso X", "Switch" );
    dummy( tm, "Otros", "Switch" );
    sw.getStepMetaInterface().searchInfoAndTargetSteps( tm.getSteps() );
    agregar( tm, "JavaF", desdeXml( "JavaFilter", "<send_true_to>Java si</send_true_to><send_false_to>Java no"
      + "</send_false_to><condition>b &gt; 0 &amp;&amp; s.length() == 3</condition>" ), "Datos" );
    dummy( tm, "Java si", "JavaF" );
    dummy( tm, "Java no", "JavaF" );
    tm.findStep( "JavaF" ).getStepMetaInterface().searchInfoAndTargetSteps( tm.getSteps() );
    agregar( tm, "Validar", desdeXml( "Validator", "<validate_all>Y</validate_all><concat_errors>N</concat_errors>"
      + "<concat_separator/><validator_field><name>id</name><validation_name>v_id</validation_name>"
      + "<max_length/><min_length/><null_allowed>Y</null_allowed><only_null_allowed>N</only_null_allowed>"
      + "<only_numeric_allowed>N</only_numeric_allowed><data_type>Integer</data_type><data_type_verified>N"
      + "</data_type_verified><conversion_mask/><decimal_symbol/><grouping_symbol/><max_value>4</max_value>"
      + "<min_value>0</min_value><start_string/><end_string/><start_string_not_allowed/><end_string_not_allowed/>"
      + "<regular_expression/><regular_expression_not_allowed/><error_code>E1</error_code><error_description/>"
      + "<is_sourcing_values>N</is_sourcing_values><sourcing_step/><sourcing_field/><allowed_value/>"
      + "</validator_field><validator_field><name>s</name><validation_name>v_s</validation_name><max_length>3"
      + "</max_length><min_length/><null_allowed>Y</null_allowed><only_null_allowed>N</only_null_allowed>"
      + "<only_numeric_allowed>N</only_numeric_allowed><data_type>String</data_type><data_type_verified>N"
      + "</data_type_verified><conversion_mask/><decimal_symbol/><grouping_symbol/><max_value/><min_value/>"
      + "<start_string/><end_string/><start_string_not_allowed>A</start_string_not_allowed><end_string_not_allowed/>"
      + "<regular_expression/><regular_expression_not_allowed/><error_code>E2</error_code><error_description/>"
      + "<is_sourcing_values>N</is_sourcing_values><sourcing_step/><sourcing_field/><allowed_value/>"
      + "</validator_field>" ), "Datos" );
    dummy( tm, "Validos", "Validar" );
    StepMeta err = dummy( tm, "Invalidos", "Validar" );
    org.pentaho.di.trans.step.StepErrorMeta em = new org.pentaho.di.trans.step.StepErrorMeta( tm, tm.findStep(
      "Validar" ), err );
    em.setEnabled( true );
    tm.findStep( "Validar" ).setStepErrorMeta( em );
    // Join rows: Datos × Constantes chicas, con condición
    agregar( tm, "Dos", desdeXml( "DataGrid", "<fields><field><name>k</name><type>Integer</type><format/><currency/>"
      + "<decimal/><group/><length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string>"
      + "</field></fields><data><line><item>1</item></line><line><item>3</item></line></data>" ), null );
    StepMeta jr = agregar( tm, "Cruce", desdeXml( "JoinRows", "<directory>%%java.io.tmpdir%%</directory><prefix>out"
      + "</prefix><cache_size>500</cache_size><main>Datos</main><compare><condition><negated>N</negated>"
      + "<leftvalue>b</leftvalue><function>&gt;</function><rightvalue>k</rightvalue></condition></compare>" ), "Datos" );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Dos" ), jr ) );
    jr.getStepMetaInterface().searchInfoAndTargetSteps( tm.getSteps() );
    casos.add( new Caso( "flujos", tm, "Caso A", "Caso X", "Otros", "Java si", "Java no", "Validos", "Invalidos",
      "Cruce" ) );
  }

  private void casoSelect() throws Exception {
    TransMeta tm = trans( "select" );
    agregar( tm, "Elegir", desdeXml( "SelectValues", "<fields><field><name>s</name><rename>texto</rename>"
      + "<length>-2</length><precision>-2</precision></field><field><name>id</name><rename/><length>-2</length>"
      + "<precision>-2</precision></field><field><name>d</name><rename/><length>-2</length><precision>-2</precision>"
      + "</field><field><name>n</name><rename/><length>-2</length><precision>-2</precision></field>"
      + "<select_unspecified>N</select_unspecified></fields>" ), "Datos" );
    agregar( tm, "Tipos", desdeXml( "SelectValues", "<fields><select_unspecified>N</select_unspecified>"
      + "<meta><name>d</name><rename>d_txt</rename><type>String</type><length>-2</length><precision>-2</precision>"
      + "<conversion_mask>dd/MM/yyyy HH:mm</conversion_mask><date_format_lenient>false</date_format_lenient>"
      + "<date_format_locale/><date_format_timezone/><lenient_string_to_number>false</lenient_string_to_number>"
      + "<encoding/><decimal_symbol/><grouping_symbol/><currency_symbol/><storage_type/></meta>"
      + "<meta><name>n</name><rename/><type>String</type><length>-2</length><precision>-2</precision>"
      + "<conversion_mask>0.00</conversion_mask><date_format_lenient>false</date_format_lenient><date_format_locale/>"
      + "<date_format_timezone/><lenient_string_to_number>false</lenient_string_to_number><encoding/>"
      + "<decimal_symbol>,</decimal_symbol><grouping_symbol/><currency_symbol/><storage_type/></meta>"
      + "<meta><name>id</name><rename/><type>Number</type><length>-2</length><precision>-2</precision>"
      + "<conversion_mask/><date_format_lenient>false</date_format_lenient><date_format_locale/>"
      + "<date_format_timezone/><lenient_string_to_number>false</lenient_string_to_number><encoding/>"
      + "<decimal_symbol/><grouping_symbol/><currency_symbol/><storage_type/></meta></fields>" ), "Elegir" );
    agregar( tm, "Volver", desdeXml( "SelectValues", "<fields><select_unspecified>N</select_unspecified>"
      + "<meta><name>d_txt</name><rename>d2</rename><type>Date</type><length>-2</length><precision>-2</precision>"
      + "<conversion_mask>dd/MM/yyyy HH:mm</conversion_mask><date_format_lenient>false</date_format_lenient>"
      + "<date_format_locale/><date_format_timezone/><lenient_string_to_number>false</lenient_string_to_number>"
      + "<encoding/><decimal_symbol/><grouping_symbol/><currency_symbol/><storage_type/></meta>"
      + "<meta><name>n</name><rename>n2</rename><type>Number</type><length>-2</length><precision>-2</precision>"
      + "<conversion_mask>0.00</conversion_mask><date_format_lenient>false</date_format_lenient><date_format_locale/>"
      + "<date_format_timezone/><lenient_string_to_number>false</lenient_string_to_number><encoding/>"
      + "<decimal_symbol>,</decimal_symbol><grouping_symbol/><currency_symbol/><storage_type/></meta></fields>" ),
      "Tipos" );
    agregar( tm, "Quitar", desdeXml( "SelectValues", "<fields><select_unspecified>N</select_unspecified>"
      + "<remove><name>texto</name></remove></fields>" ), "Volver" );
    casos.add( new Caso( "select", tm, "Elegir", "Tipos", "Volver", "Quitar" ) );
  }

  // ================================================================ correr

  /** Corre la transformación en Kettle y guarda las filas (normalizadas) de cada step capturado. */
  private static void correrKettle( Caso c ) throws Exception {
    File ktr = new File( EntornoKettle.salida( "trad-campos" ), c.nombre + ".ktr" );
    Files.write( ktr.toPath(), c.tm.getXML().getBytes( StandardCharsets.UTF_8 ) );
    TransMeta leida = new TransMeta( ktr.getAbsolutePath() );
    Trans t = new Trans( leida );
    t.setLogLevel( LogLevel.MINIMAL );
    t.prepareExecution( null );
    for ( String paso : c.capturas ) {
      final List<String> filas = new ArrayList<>();
      c.kettle.put( paso, filas );
      t.findRunThread( paso ).addRowListener( new RowAdapter() {
        @Override
        public void rowWrittenEvent( RowMetaInterface rm, Object[] row ) throws KettleStepException {
          synchronized ( filas ) {
            if ( !c.cabeceras.containsKey( paso ) ) {
              c.cabeceras.put( paso, Arrays.asList( rm.getFieldNames() ) );
            }
            StringBuilder b = new StringBuilder();
            for ( int i = 0; i < rm.size(); i++ ) {
              b.append( i == 0 ? "" : "\u0001" ).append( norm( rm.getValueMeta( i ), row[ i ] ) );
            }
            filas.add( b.toString() );
          }
        }
      } );
    }
    t.startThreads();
    t.waitUntilFinished();
    igual( 0, t.getErrors(), c.nombre + ": corre en Kettle sin errores" );
    for ( String paso : c.capturas ) {
      if ( !c.cabeceras.containsKey( paso ) ) {
        c.cabeceras.put( paso, Arrays.asList( leida.getStepFields( paso ).getFieldNames() ) );
      }
    }
  }

  private static String norm( ValueMetaInterface vm, Object o ) throws KettleStepException {
    try {
      Object v = o;
      if ( v == null ) {
        return "~";
      }
      switch ( vm.getType() ) {
        case ValueMetaInterface.TYPE_INTEGER:
        case ValueMetaInterface.TYPE_NUMBER:
        case ValueMetaInterface.TYPE_BIGNUMBER: {
          BigDecimal d = vm.getBigNumber( v );
          String s = d.setScale( 6, RoundingMode.HALF_EVEN ).stripTrailingZeros().toPlainString();
          return s.equals( "-0" ) ? "0" : s;
        }
        case ValueMetaInterface.TYPE_DATE:
        case ValueMetaInterface.TYPE_TIMESTAMP:
          return new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" ).format( vm.getDate( v ) );
        case ValueMetaInterface.TYPE_BOOLEAN:
          return vm.getBoolean( v ) ? "true" : "false";
        default:
          String s = vm.getString( v );
          return s == null || s.isEmpty() ? "~" : s;
      }
    } catch ( Exception e ) {
      throw new KettleStepException( e );
    }
  }

  private static final String RUNNER = String.join( "\n",
    "import sys, os, traceback, datetime, decimal",
    "import pandas as pd",
    "import numpy as np",
    "SAL = sys.argv[1]",
    "CASOS = [l.rstrip('\\n').split('\\t') for l in open(sys.argv[2], encoding='utf-8') if l.strip()]",
    "def norm(v):",
    "    if v is None:",
    "        return '~'",
    "    try:",
    "        if pd.isna(v):",
    "            return '~'",
    "    except (TypeError, ValueError):",
    "        pass",
    "    if isinstance(v, (bool, np.bool_)):",
    "        return 'true' if v else 'false'",
    "    if isinstance(v, (int, float, np.integer, np.floating, decimal.Decimal)):",
    "        s = f'{float(v):.6f}'.rstrip('0').rstrip('.')",
    "        return '0' if s == '-0' else s",
    "    if isinstance(v, (pd.Timestamp, datetime.datetime)):",
    "        return v.strftime('%Y-%m-%d %H:%M:%S')",
    "    if isinstance(v, datetime.date):",
    "        return v.strftime('%Y-%m-%d 00:00:00')",
    "    s = str(v)",
    "    return '~' if s == '' else s",
    "for caso, archivo, pares in CASOS:",
    "    g = {'__file__': archivo, '__name__': '__main__'}",
    "    try:",
    "        exec(compile(open(archivo, encoding='utf-8').read(), archivo, 'exec'), g)",
    "        for par in pares.split('|'):",
    "            paso, var = par.split('=')",
    "            df = g[var]",
    "            if not isinstance(df, pd.DataFrame):",
    "                df = df.toPandas()",
    "            with open(os.path.join(SAL, caso + '__' + paso + '.txt'), 'w', encoding='utf-8') as f:",
    "                f.write('\\x01'.join(map(str, df.columns)) + '\\n')",
    "                for fila in df.itertuples(index=False, name=None):",
    "                    f.write('\\x01'.join(norm(v) for v in fila) + '\\n')",
    "    except Exception:",
    "        with open(os.path.join(SAL, caso + '__ERROR.txt'), 'w', encoding='utf-8') as f:",
    "            f.write(traceback.format_exc())",
    "" );

  /** Código Python que crea el DataFrame de «Datos» (los mismos valores que el Data grid). */
  private static List<String> datosPy( Destino d ) {
    List<String> r = new ArrayList<>();
    if ( d == Destino.PANDAS ) {
      r.add( "df_datos = pd.DataFrame({" );
      for ( int i = 0; i < CAMPOS.length; i++ ) {
        List<String> v = new ArrayList<>();
        for ( String[] f : FILAS ) {
          v.add( valorPy( TIPOS[ i ], f[ i ], d ) );
        }
        String tipo = TIPOS[ i ].equals( "Integer" ) ? "\"Int64\"" : TIPOS[ i ].equals( "Number" ) ? "\"float64\""
          : TIPOS[ i ].equals( "Date" ) ? "\"datetime64[ns]\"" : "object";
        r.add( "    " + CtxTrad.lit( CAMPOS[ i ] ) + ": pd.Series([" + String.join( ", ", v ) + "], dtype=" + tipo
          + ")," );
      }
      r.add( "})" );
    } else {
      r.add( "import datetime" );
      List<String> filas = new ArrayList<>();
      for ( String[] f : FILAS ) {
        List<String> v = new ArrayList<>();
        for ( int i = 0; i < CAMPOS.length; i++ ) {
          v.add( valorPy( TIPOS[ i ], f[ i ], d ) );
        }
        filas.add( "(" + String.join( ", ", v ) + ")" );
      }
      List<String> esquema = new ArrayList<>();
      for ( int i = 0; i < CAMPOS.length; i++ ) {
        esquema.add( CAMPOS[ i ] + " " + ( TIPOS[ i ].equals( "Integer" ) ? "long" : TIPOS[ i ].equals( "Number" )
          ? "double" : TIPOS[ i ].equals( "Date" ) ? "timestamp" : "string" ) );
      }
      r.add( "df_datos = spark.createDataFrame([" + String.join( ", ", filas ) + "], " + CtxTrad.lit( String.join( ", ",
        esquema ) ) + ")" );
    }
    return r;
  }

  private static String valorPy( String tipo, String v, Destino d ) {
    if ( v.isEmpty() ) {
      return "None";
    }
    switch ( tipo ) {
      case "Integer":
        return v;
      case "Number":
        return v.contains( "." ) ? v : v + ".0";
      case "Date":
        return d == Destino.PANDAS ? "pd.Timestamp(" + CtxTrad.lit( v ) + ")" : "datetime.datetime.fromisoformat("
          + CtxTrad.lit( v ) + ")";
      default:
        return CtxTrad.lit( v );
    }
  }

  private void armarTodo() throws Exception {
    if ( armado ) {
      return;
    }
    armado = true;
    casoCondiciones();
    casoCalculadora();
    casoCadena();
    casoFlujos();
    casoSelect();
    for ( Caso c : new ArrayList<>( casos ) ) {
      try {
        correrKettle( c );
      } catch ( Throwable e ) {
        erroresKettle.add( c.nombre + ": " + e );
        casos.remove( c );
      }
    }
  }

  /** Traduce cada caso, corre todos en un solo proceso de Python y compara. */
  private void comparar( Destino d ) throws Exception {
    armarTodo();
    if ( PythonPrueba.python() == null ) {
      String env = System.getenv( "PENTAHO_LIVE_PYTHON" );
      String motivo;
      try {
        motivo = env + " -> " + PythonPrueba.correr( null, 60, env, "--version" ).salida;
      } catch ( Exception e ) {
        motivo = env + " -> " + e;
      }
      Verificador.saltear( "sin Python: " + motivo );
    }
    PythonPrueba.Resultado imp = PythonPrueba.correr( null, 300, PythonPrueba.python(), "-c", "import " + ( d
      == Destino.PANDAS ? "pandas" : "pyspark" ) );
    if ( imp.codigo != 0 ) {
      Verificador.saltear( "sin " + d.id() + " en " + PythonPrueba.python() + ": " + imp.salida );
    }
    File dir = EntornoKettle.salida( "trad-campos-" + d.id() );
    File[] viejos = dir.listFiles();
    for ( File v : viejos == null ? new File[ 0 ] : viejos ) {
      if ( v.isFile() ) {
        v.delete();
      }
    }
    StringBuilder lista = new StringBuilder();
    Map<String, Programa> programas = new LinkedHashMap<>();
    for ( Caso c : casos ) {
      TransMeta leida = new TransMeta( new File( EntornoKettle.salida( "trad-campos" ), c.nombre + ".ktr" )
        .getAbsolutePath() );
      Programa p = TraductorTrans.traducir( leida, d, "es" );
      programas.put( c.nombre, p );
      List<String> pares = new ArrayList<>();
      for ( Bloque b : p.bloques ) {
        if ( b.paso.equals( "Datos" ) ) {
          b.codigo.clear();
          b.codigo.addAll( datosPy( d ) );
        } else if ( b.paso.equals( "Dos" ) ) {
          b.codigo.clear();
          b.codigo.add( d == Destino.PANDAS ? "df_dos = pd.DataFrame({\"k\": pd.Series([1, 3], dtype=\"Int64\")})"
            : "df_dos = spark.createDataFrame([(1,), (3,)], \"k long\")" );
        }
      }
      for ( String paso : c.capturas ) {
        pares.add( paso + "=" + p.ctx.df( leida.findStep( paso ) ) );
      }
      File py = new File( dir, "caso_" + c.nombre + ".py" );
      Files.write( py.toPath(), EscritorPy.escribir( p ).getBytes( StandardCharsets.UTF_8 ) );
      lista.append( c.nombre ).append( '\t' ).append( py.getAbsolutePath() ).append( '\t' ).append( String.join( "|",
        pares ) ).append( '\n' );
    }
    File casosTxt = new File( dir, "casos.txt" );
    Files.write( casosTxt.toPath(), lista.toString().getBytes( StandardCharsets.UTF_8 ) );
    File runner = new File( dir, "correr.py" );
    Files.write( runner.toPath(), RUNNER.getBytes( StandardCharsets.UTF_8 ) );
    PythonPrueba.Resultado r = PythonPrueba.correr( dir, d == Destino.PANDAS ? 300 : 900, PythonPrueba.python(), runner
      .getAbsolutePath(), dir.getAbsolutePath(), casosTxt.getAbsolutePath() );
    cierto( r.codigo == 0, "el runner de Python termina: " + r.salida );
    List<String> fallas = new ArrayList<>( erroresKettle );
    for ( Caso c : casos ) {
      File err = new File( dir, c.nombre + "__ERROR.txt" );
      if ( err.exists() ) {
        fallas.add( c.nombre + ": " + new String( Files.readAllBytes( err.toPath() ), StandardCharsets.UTF_8 ) );
        continue;
      }
      for ( String paso : c.capturas ) {
        String dif = diferencias( c, paso, new File( dir, c.nombre + "__" + paso + ".txt" ), d );
        if ( dif != null ) {
          fallas.add( c.nombre + "/" + paso + ": " + dif );
        }
      }
    }
    // estados: nada MANUAL en los steps de esta parte
    for ( Map.Entry<String, Programa> e : programas.entrySet() ) {
      for ( Bloque b : e.getValue().bloques ) {
        if ( !b.paso.equals( "Datos" ) && !b.paso.equals( "Dos" ) && b.estado == Estado.MANUAL ) {
          fallas.add( e.getKey() + "/" + b.paso + ": MANUAL " + b.notas );
        }
      }
    }
    if ( !fallas.isEmpty() ) {
      falla( d.id() + ": " + fallas.size() + " diferencias:\n" + String.join( "\n", fallas ) );
    }
  }

  private static String diferencias( Caso c, String paso, File f, Destino d ) throws Exception {
    if ( !f.exists() ) {
      return "sin salida de Python";
    }
    List<String> lineas = Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 );
    List<String> cabPy = Arrays.asList( lineas.get( 0 ).split( "\u0001", -1 ) );
    List<String> cabK = c.cabeceras.get( paso );
    List<Integer> idx = new ArrayList<>();
    for ( String col : cabK ) {
      int i = cabPy.indexOf( col );
      if ( i < 0 ) {
        return "falta la columna " + col + " (Python: " + cabPy + ")";
      }
      idx.add( i );
    }
    if ( cabPy.size() != cabK.size() ) {
      return "columnas distintas: Kettle " + cabK + " / Python " + cabPy;
    }
    List<String> py = new ArrayList<>();
    for ( String l : lineas.subList( 1, lineas.size() ) ) {
      String[] v = l.split( "\u0001", -1 );
      StringBuilder b = new StringBuilder();
      for ( int k = 0; k < idx.size(); k++ ) {
        b.append( k == 0 ? "" : "\u0001" ).append( v[ idx.get( k ) ] );
      }
      py.add( b.toString() );
    }
    List<String> k = new ArrayList<>( c.kettle.get( paso ) );
    if ( d == Destino.PYSPARK ) {
      Collections.sort( k );
      Collections.sort( py );
    }
    if ( k.equals( py ) ) {
      return null;
    }
    StringBuilder r = new StringBuilder( "cols " + cabK + "\n" );
    for ( int i = 0; i < Math.max( k.size(), py.size() ); i++ ) {
      String a = i < k.size() ? k.get( i ) : "(nada)";
      String b = i < py.size() ? py.get( i ) : "(nada)";
      if ( !a.equals( b ) ) {
        r.append( "   K: " ).append( a.replace( '\u0001', '|' ) ).append( "\n   P: " ).append( b.replace( '\u0001',
          '|' ) ).append( '\n' );
      }
    }
    return r.toString();
  }

  public void pruebaPandas() throws Exception {
    comparar( Destino.PANDAS );
  }

  public void pruebaPyspark() throws Exception {
    comparar( Destino.PYSPARK );
  }

  /** Expresiones sueltas: Java, OpenFormula y lo que no se traduce queda MANUAL. */
  public void pruebaExpresiones() throws Exception {
    armarTodo();
    TransMeta tm = trans( "manual" );
    agregar( tm, "Script", desdeXml( "ScriptValueMod", "<compatible>N</compatible><optimizationLevel>9"
      + "</optimizationLevel><jsScripts><jsScript><jsScript_type>0</jsScript_type><jsScript_name>Script 1"
      + "</jsScript_name><jsScript_script>for (var i = 0; i &lt; 3; i++) { x = i; }</jsScript_script></jsScript>"
      + "</jsScripts><fields><field><name>x</name><rename>x</rename><type>Integer</type><length>-1</length>"
      + "<precision>-1</precision><replace>N</replace></field></fields>" ), "Datos" );
    agregar( tm, "Script2", desdeXml( "ScriptValueMod", "<compatible>N</compatible><optimizationLevel>9"
      + "</optimizationLevel><jsScripts><jsScript><jsScript_type>0</jsScript_type><jsScript_name>Script 1"
      + "</jsScript_name><jsScript_script>var y = b * 2;\nvar z = s.toUpperCase();</jsScript_script></jsScript>"
      + "</jsScripts><fields><field><name>y</name><rename>y</rename><type>Number</type><length>-1</length>"
      + "<precision>-1</precision><replace>N</replace></field><field><name>z</name><rename>z</rename><type>String"
      + "</type><length>-1</length><precision>-1</precision><replace>N</replace></field></fields>" ), "Script" );
    for ( Destino d : Destino.values() ) {
      Programa p = TraductorTrans.traducir( tm, d, "es" );
      String py = EscritorPy.escribir( p );
      igual( Estado.MANUAL, p.bloques.get( 1 ).estado, "JS con bucle: MANUAL" );
      contiene( py, "#   for (var i = 0; i < 3; i++) { x = i; }", "script comentado" );
      igual( Estado.EXACTO, p.bloques.get( 2 ).estado, "JS simple: traducido " + p.bloques.get( 2 ).notas );
      contiene( py, d == Destino.PANDAS ? "[\"s\"].str.upper()" : "F.upper(F.col(\"s\"))", "JS toUpperCase" );
      File f = new File( EntornoKettle.salida( "trad-campos" ), "manual_" + d.id() + ".py" );
      Files.write( f.toPath(), py.getBytes( StandardCharsets.UTF_8 ) );
      PythonPrueba.compila( f );
    }
  }

  /** Los ejemplos del curso con steps de campos se traducen sin bloques MANUAL de esta parte. */
  public void pruebaEjemplosCurso() throws Exception {
    File ej = new File( EntornoKettle.recursos(), "../../../../ejemplo/solucion-referencia" ).getCanonicalFile();
    for ( File k : ej.listFiles( ( x, n ) -> n.endsWith( ".ktr" ) ) ) {
      TransMeta t = new TransMeta( k.getAbsolutePath() );
      for ( Destino d : Destino.values() ) {
        Programa p = TraductorTrans.traducir( t, d, "es" );
        for ( Bloque b : p.bloques ) {
          if ( b.tipo.matches( "Calculator|FilterRows|SelectValues|Formula|Sequence|ValueMapper|StringOperations" ) ) {
            cierto( b.estado != Estado.MANUAL, k.getName() + "/" + b.paso + " (" + d.id() + ") no es MANUAL: "
              + b.notas );
          }
        }
        File f = new File( EntornoKettle.salida( "trad-campos" ), k.getName() + "_" + d.id() + ".py" );
        Files.write( f.toPath(), EscritorPy.escribir( p ).getBytes( StandardCharsets.UTF_8 ) );
        PythonPrueba.compila( f );
      }
    }
  }
}
