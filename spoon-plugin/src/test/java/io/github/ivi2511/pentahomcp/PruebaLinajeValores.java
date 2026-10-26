package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.distinto;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * El linaje de los steps que cambian el valor de un campo (LinajeValores): String operations, Replace in string,
 * Strings cut, Concat fields, Split fields, Split field to rows, User defined Java expression, Value mapper, If field
 * value is null, Null if, Number range, Set field value, Set field value to a constant, Add a checksum, Regex
 * evaluation y Modified JavaScript value. Para cada uno: el camino del campo tiene un nodo «calc» con su etiqueta (en
 * el motor propio y en el de metaverse), la regla de la hoja de mapeo lo dice y la sección de linaje también.
 */
public class PruebaLinajeValores {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /** Generate rows (a, b, n, t) → cada step de la lista en cadena → Dummy «Destino». */
  static TransMeta cadena( String[][] steps ) throws Exception {
    TransMeta tm = new TransMeta();
    tm.setName( "valores" );
    String fuente = "<limit>3</limit><fields>"
      + "<field><name>a</name><type>String</type><nullif> hola, mundo </nullif><length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field>"
      + "<field><name>b</name><type>String</type><nullif>X-Y</nullif><length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field>"
      + "<field><name>n</name><type>Integer</type><nullif>7</nullif><length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field>"
      + "<field><name>t</name><type>String</type><nullif>A,B</nullif><length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field>"
      + "</fields>";
    TransOps.agregarStep( tm, "RowGenerator", "Fuente", fuente, 100, 100, null, null, null );
    String antes = "Fuente";
    int x = 250;
    for ( String[] s : steps ) {
      TransOps.agregarStep( tm, s[ 0 ], s[ 1 ], s[ 2 ], x, 100, antes, null, null );
      antes = s[ 1 ];
      x += 150;
    }
    TransOps.agregarStep( tm, "Dummy", "Destino", null, x, 100, antes, null, null );
    return tm;
  }

  /** Las firmas de los caminos de un campo del destino, con el motor propio. */
  private static String camino( TransMeta tm, String campo ) {
    LinajePropio lin = new LinajePropio( tm, Mapeo.contexto( tm, "es" ) );
    StepMeta d = tm.findStep( "Destino" );
    LinajePropio.Destino ld = lin.destino( d );
    igual( null, ld.motivo, "campos del destino" );
    LinajePropio.Campo c = ld.campo( campo );
    noNulo( c, "el destino tiene " + campo );
    List<String> fs = new ArrayList<String>();
    for ( List<LinajePropio.Nodo> cs : c.caminos ) {
      StringBuilder sb = new StringBuilder();
      for ( LinajePropio.Nodo n : cs ) {
        sb.append( n.firma() ).append( " | " );
      }
      fs.add( sb.toString() );
    }
    return fs.toString();
  }

  public void pruebaStringOperationsYReemplazos() throws Exception {
    TransMeta tm = cadena( new String[][] {
      { "StringOperations", "Estandarizar", "<fields><field><in_stream_name>a</in_stream_name><trim_type>both</trim_type>"
        + "<lower_upper>upper</lower_upper></field><field><in_stream_name>b</in_stream_name><out_stream_name>b_min"
        + "</out_stream_name><lower_upper>lower</lower_upper></field></fields>" },
      { "ReplaceString", "Coma por punto", "<fields><field><in_stream_name>a</in_stream_name><replace_string>,"
        + "</replace_string><replace_by_string>.</replace_by_string></field></fields>" },
      { "StringCut", "Primeros 3", "<fields><field><in_stream_name>b</in_stream_name><out_stream_name>b3"
        + "</out_stream_name><cut_from>0</cut_from><cut_to>3</cut_to></field></fields>" } } );
    String a = camino( tm, "a" );
    contiene( a, "Estandarizar:a:calc:quita los espacios, mayúsculas", "trim + upper en el sitio: " + a );
    contiene( a, "Coma por punto:a:calc:reemplaza «,» por «.»", "y el reemplazo después" );
    contiene( a, "Fuente:a:source", "el origen sigue siendo la fuente" );
    String bmin = camino( tm, "b_min" );
    contiene( bmin, "Estandarizar:b_min:calc:minúsculas", "un campo nuevo desde b: " + bmin );
    contiene( bmin, "Fuente:b:source", "que viene de b" );
    contiene( camino( tm, "b3" ), "Primeros 3:b3:calc:subcadena de 0 a 3", "Strings cut" );
    // la hoja de mapeo: la regla ya no es «sin cambios»
    List<Mapeo.Fila> fs = Mapeo.filas( tm, Mapeo.contexto( tm, "es" ), null );
    Mapeo.Fila fa = fila( fs, "a" );
    noNulo( fa, "fila de a" );
    distinto( Rotulos.de( SecLinaje.AREA, "es" ).s( "sin_cambios" ), fa.regla, "la regla de a" );
    contiene( fa.regla, "mayúsculas en «Estandarizar»", "dice la operación y el step: " + fa.regla );
    contiene( fa.regla, "reemplaza «,» por «.» en «Coma por punto»", "y el reemplazo" );
    contiene( fila( fs, "b_min" ).origen, ".b", "b_min sale de b: " + fila( fs, "b_min" ).origen );
  }

  public void pruebaConcatSplitYExpresiones() throws Exception {
    TransMeta tm = cadena( new String[][] {
      { "ConcatFields", "Unir", "<separator>-</separator><fields><field><name>a</name></field><field><name>b"
        + "</name></field></fields><ConcatFields><targetFieldName>ab</targetFieldName></ConcatFields>" },
      { "FieldSplitter", "Partir", "<splitfield>b</splitfield><delimiter>-</delimiter><fields><field><name>b1"
        + "</name><type>String</type></field><field><name>b2</name><type>String</type></field></fields>" },
      { "Janino", "Java", "<formula><field_name>j</field_name><formula_string>a + \"/\" + b1</formula_string>"
        + "<value_type>String</value_type><value_length>-1</value_length><value_precision>-1</value_precision>"
        + "<replace_field/></formula>" },
      { "ScriptValueMod", "JS", "<jsScripts><jsScript><jsScript_type>0</jsScript_type><jsScript_name>Script 1"
        + "</jsScript_name><jsScript_script>var z = b2 + n;</jsScript_script></jsScript></jsScripts><fields><field>"
        + "<name>z</name><rename>z</rename><type>String</type><length>-1</length><precision>-1</precision><replace>N"
        + "</replace></field></fields>" },
      { "SplitFieldToRows3", "Filas", "<splitfield>t</splitfield><delimiter>,</delimiter><newfield>pieza</newfield>" } } );
    String ab = camino( tm, "ab" );
    contiene( ab, "Unir:ab:calc:concatena a, b con «-»", "Concat fields: " + ab );
    contiene( ab, "Fuente:a:source", "de a" );
    contiene( ab, "Fuente:b:source", "y de b" );
    String b1 = camino( tm, "b1" );
    contiene( b1, "Partir:b1:calc:parte b por «-» (trozo 1)", "Split fields: " + b1 );
    String j = camino( tm, "j" );
    contiene( j, "Java:j:calc:expresión a + \"/\" + b1", "Java expression: " + j );
    contiene( j, "Fuente:a:source", "usa a" );
    contiene( j, "Partir:b1:calc", "y b1, que sale del split" );
    String z = camino( tm, "z" );
    contiene( z, "JS:z:calc:script JavaScript", "JavaScript: " + z );
    contiene( z, "Fuente:n:source", "nombra n" );
    contiene( camino( tm, "pieza" ), "Filas:pieza:calc:parte en filas por «,»", "Split field to rows" );
  }

  public void pruebaMapeosNulosYRangos() throws Exception {
    TransMeta tm = cadena( new String[][] {
      { "ValueMapper", "Mapear", "<field_to_use>b</field_to_use><target_field>b_txt</target_field>"
        + "<non_match_default>OTRO</non_match_default><fields><field><source_value>X-Y</source_value><target_value>"
        + "equis</target_value></field></fields>" },
      { "IfNull", "Si nulo", "<selectFields>Y</selectFields><fields><field><name>a</name><value>?</value></field>"
        + "</fields>" },
      { "NullIf", "Nulo si", "<fields><field><name>t</name><value>N/A</value></field></fields>" },
      { "NumberRange", "Rango", "<inputField>n</inputField><outputField>tramo</outputField><fallBackValue>alto"
        + "</fallBackValue><rules><rule><lower_bound>0</lower_bound><upper_bound>10</upper_bound><value>bajo"
        + "</value></rule></rules>" },
      { "SetValueField", "Copiar", "<fields><field><name>b</name><replaceby>a</replaceby></field></fields>" },
      { "SetValueConstant", "Fijar", "<fields><field><name>n</name><value>0</value></field></fields>" },
      { "CheckSum", "Hash", "<checksumtype>MD5</checksumtype><resultfieldName>h</resultfieldName><fields><field>"
        + "<name>a</name></field><field><name>t</name></field></fields>" },
      { "RegexEval", "Regex", "<script>^[A-Z]</script><matcher>t</matcher><resultfieldname>ok</resultfieldname>" } } );
    String bt = camino( tm, "b_txt" );
    contiene( bt, "Mapear:b_txt:calc:mapea valores: X-Y → equis; si no, «OTRO»", "Value mapper: " + bt );
    contiene( bt, "Fuente:b:source", "desde b" );
    contiene( camino( tm, "a" ), "Si nulo:a:calc:si es nulo, «?»", "If field value is null" );
    contiene( camino( tm, "t" ), "Nulo si:t:calc:nulo si es «N/A»", "Null if" );
    contiene( camino( tm, "tramo" ), "Rango:tramo:calc:rangos: [0", "Number range" );
    String b = camino( tm, "b" );
    contiene( b, "Copiar:b:calc:toma el valor de a", "Set field value: " + b );
    contiene( b, "Fuente:a:source", "ahora b sale de a" );
    contiene( camino( tm, "n" ), "Fijar:n:created:valor fijo «0»", "Set field value to a constant: nace ahí" );
    contiene( camino( tm, "h" ), "Hash:h:calc:checksum MD5 de a, t", "Add a checksum" );
    contiene( camino( tm, "ok" ), "Regex:ok:calc:¿cumple «^[A-Z]»? sobre t", "Regex evaluation" );
  }

  /** La sección de linaje (motor propio o metaverse) y la de mapeo muestran la transformación. */
  public void pruebaSeccionesYMetaverse() throws Exception {
    TransMeta tm = cadena( new String[][] {
      { "StringOperations", "Estandarizar", "<fields><field><in_stream_name>a</in_stream_name><trim_type>both"
        + "</trim_type><lower_upper>upper</lower_upper></field></fields>" },
      { "ValueMapper", "Mapear", "<field_to_use>b</field_to_use><target_field/><non_match_default/><fields><field>"
        + "<source_value>X-Y</source_value><target_value>equis</target_value></field></fields>" } } );
    // metaverse: los nodos de String operations y Value mapper son «calc» con la etiqueta propia
    LinajePropio propio = new LinajePropio( tm, Mapeo.contexto( tm, "es" ) );
    StepMeta d = tm.findStep( "Destino" );
    Map<String, List<String>> campos = new LinkedHashMap<String, List<String>>();
    campos.put( "Destino", LinajeMetaverse.nombres( propio.campos( d ) ) );
    if ( LinajeMetaverse.problema() == null ) {
      LinajeMetaverse.Corrida corrida = LinajeMetaverse.calcular( tm, campos, propio, 60000 );
      LinajeMetaverse.Respuesta mv = corrida.destinos.get( "Destino" );
      noNulo( mv, "respuesta de metaverse" );
      if ( mv.error == null ) {
        int trazados = 0;
        for ( String campo : new String[] { "a", "b" } ) {
          List<List<LinajePropio.Nodo>> cs = mv.caminos.get( campo );
          if ( cs == null ) {
            continue; // metaverse no lo trazó: queda el motor propio
          }
          String paso = "a".equals( campo ) ? "Estandarizar" : "Mapear";
          boolean visto = false;
          for ( List<LinajePropio.Nodo> c : cs ) {
            for ( LinajePropio.Nodo n : c ) {
              if ( paso.equals( n.step ) ) {
                igual( LinajePropio.CALCULA, n.clase, "metaverse: " + paso + " cambia el valor de " + campo );
                visto = true;
              }
            }
          }
          cierto( visto, "metaverse pasa por " + paso + ": " + cs.size() );
          trazados++;
        }
        cierto( trazados > 0, "metaverse trazó a o b: " + mv.caminos.keySet() );
        System.out.println( "  metaverse trazó " + trazados + " de 2 campos con steps que cambian valores" );
      }
    }
    // sección de linaje
    Seccion s = new SecLinaje().armar( Mapeo.contexto( tm, "es" ) );
    Tabla t = null;
    for ( Object b : s.bloques ) {
      if ( b instanceof Tabla ) {
        t = (Tabla) b;
      }
    }
    noNulo( t, "tabla del linaje" );
    String todo = t.filas.toString();
    contiene( todo, "quita los espacios, mayúsculas en «Estandarizar»", "la sección dice String operations: " + todo );
    contiene( todo, "mapea valores: X-Y → equis en «Mapear»", "y el Value mapper" );
    // sección de mapeo
    Seccion m = new SecMapeo().armar( Mapeo.contexto( tm, "en" ) );
    contiene( m.datos.toString(), "upper case in", "la hoja de mapeo en inglés: " + m.datos );
  }

  private static Mapeo.Fila fila( List<Mapeo.Fila> fs, String campo ) {
    for ( Mapeo.Fila f : fs ) {
      if ( campo.equals( f.campo ) ) {
        return f;
      }
    }
    return null;
  }
}
