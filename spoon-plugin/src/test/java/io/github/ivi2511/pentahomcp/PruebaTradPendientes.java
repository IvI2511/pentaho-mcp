package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.pentaho.di.core.Const;
import org.pentaho.di.trans.steps.calculator.CalculatorMetaFunction;

import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Programa;

/** Pendientes del traductor: initcap exacto (pandas y PySpark) y DescribirStep sin reflexión. */
public class PruebaTradPendientes {

  private static final String TEXTO = "hOLA mundo-ana o'neil x1y a_b 3d ángel.ÉL";

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /** Lo que hace PDI (Const.initCap): mayúscula tras lo que no sea letra, dígito ni «_». */
  public void pruebaInitCapDePdi() {
    igual( "Hola Mundo-Ana O'Neil X1y A_b 3d Ángel.Él", Const.initCap( TEXTO ), "Const.initCap" );
  }

  /** Calculator «InitCap» y String operations «init cap» dan lo mismo que Kettle en pandas y en PySpark. */
  public void pruebaInitCapEquivalente() throws Exception {
    File dir = EntornoKettle.salida( "trad-pendientes" );
    String campo = "<field><name>t</name><type>String</type><format/><currency/><decimal/><group/><length>-1</length>"
      + "<precision>-1</precision><set_empty_string>N</set_empty_string></field>";
    String datos = ArnesTraduccion.step( "Datos", "DataGrid", "<fields>" + campo + "</fields><data>"
      + "<line><item>" + TEXTO + "</item></line><line><item>plain TEXT</item></line><line><item/></line></data>" );
    String calc = ArnesTraduccion.step( "Calc", "Calculator", "<calculation><field_name>c_ic</field_name><calc_type>"
      + CalculatorMetaFunction.calc_desc[ CalculatorMetaFunction.CALC_INITCAP ] + "</calc_type><field_a>t</field_a>"
      + "<field_b/><field_c/><value_type>String</value_type><value_length>-1</value_length><value_precision>-1"
      + "</value_precision><remove>N</remove><conversion_mask/><decimal_symbol/><grouping_symbol/>"
      + "<currency_symbol/></calculation>" );
    String textos = ArnesTraduccion.step( "Textos", "StringOperations", "<fields><field><in_stream_name>t"
      + "</in_stream_name><out_stream_name>t_ic</out_stream_name><trim_type>none</trim_type><lower_upper>none"
      + "</lower_upper><padding_type>none</padding_type><pad_char/><pad_len/><init_cap>yes</init_cap>"
      + "<mask_xml>none</mask_xml><digits>none</digits><remove_special_characters>none</remove_special_characters>"
      + "</field></fields>" );
    File ktr = ArnesTraduccion.escribir( dir, "initcap.ktr", ArnesTraduccion.ktr( "initcap", null,
      ArnesTraduccion.hop( "Datos", "Calc" ) + ArnesTraduccion.hop( "Calc", "Textos" ), datos, calc, textos ) );
    List<String> k = ArnesTraduccion.kettle( ktr, "Textos" );
    contiene( String.join( "\n", k ), "Hola Mundo-Ana O'Neil X1y A_b 3d Ángel.Él", "Kettle hace initcap" );
    List<File> scripts = new ArrayList<>();
    for ( Destino d : new Destino[] { Destino.PANDAS, Destino.PYSPARK } ) {
      Programa p = ArnesTraduccion.traducir( ktr, d, "Textos" );
      igual( 0, p.cuantos( Estado.APROXIMADO ) + p.cuantos( Estado.MANUAL ), d + ": todo exacto" );
      String py = EscritorPy.escribir( p );
      contiene( py, "def _initcap_pdi(", d + ": ayudante" );
      cierto( !py.contains( ".title()" ) && !py.contains( "F.initcap(" ), d + ": sin title()/F.initcap" );
      scripts.add( ArnesTraduccion.script( ktr, d ) );
    }
    if ( PythonPrueba.python() == null ) {
      Verificador.saltear( "sin Python del traductor" );
      return;
    }
    String salida = ArnesTraduccion.correr( scripts, 600 );
    for ( Destino d : new Destino[] { Destino.PANDAS, Destino.PYSPARK } ) {
      String s = ArnesTraduccion.salidaDe( salida, ArnesTraduccion.script( ktr, d ) );
      cierto( s.contains( "OK" ) && !s.contains( "FALLO" ), d + " corre:\n" + s );
      ArnesTraduccion.comparar( k, ArnesTraduccion.leer( ArnesTraduccion.volcado( ktr, d, "Textos" ) ), d + "" );
    }
    Files.write( new File( dir, "salida.log" ).toPath(), salida.getBytes( StandardCharsets.UTF_8 ) );
  }

  /** DescribirStep del lint se llama directo (Ctx.para), sin reflexión. */
  public void pruebaDescribirSinReflexion() throws Exception {
    String rel = "spoon-plugin/src/main/java/io/github/ivi2511/pentahomcp/traductor/EscritorPy.java";
    File f = new File( rel ).getAbsoluteFile();
    for ( File d = new File( "." ).getAbsoluteFile(); d != null && !f.isFile(); d = d.getParentFile() ) {
      f = new File( d, rel );
    }
    if ( !f.isFile() ) {
      Verificador.saltear( "sin fuentes junto a " + new File( "." ).getAbsolutePath() );
      return;
    }
    String fuente = new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
    cierto( !fuente.contains( "setAccessible" ), "EscritorPy sin reflexión" );
    contiene( fuente, "Ctx.para(", "usa la fábrica pública del lint" );
  }
}
