package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.csvinput.CsvInputMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;
import org.pentaho.di.trans.steps.textfileinput.TextFileInputField;

import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorIpynb;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Opciones;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;

/**
 * Los escritores del traductor con opciones (0.8.0): título, autor, introducción, idioma, explicaciones (las del bloque
 * o DescribirStep), notas, vistas, EDA y resumen; el notebook es nbformat 4.5 válido y corre de punta a punta con
 * nbclient (pandas), con sus gráficos.
 */
public class PruebaTraductorEscritores {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /** Una transformación chica: ventas.csv → CSV file input → Dummy «Resultado», guardada en {@code dir}. */
  static File ejemplo( File dir ) throws Exception {
    dir.mkdirs();
    Files.write( new File( dir, "ventas.csv" ).toPath(), ( "producto,tienda,cantidad\nPan,Centro,3\nLeche,Norte,5\n"
      + "Pan,Norte,2\nQueso,Centro,7\nPan,Sur,1\nLeche,Centro,4\n" ).getBytes( StandardCharsets.UTF_8 ) );
    TransMeta t = new TransMeta();
    t.setName( "ventas" );
    CsvInputMeta csv = new CsvInputMeta();
    csv.setDefault();
    csv.setFilename( "${Internal.Entry.Current.Directory}/ventas.csv" );
    csv.setDelimiter( "," );
    csv.setEnclosure( "\"" );
    csv.setHeaderPresent( true );
    csv.setEncoding( "UTF-8" );
    String[] n = { "producto", "tienda", "cantidad" };
    TextFileInputField[] fs = new TextFileInputField[ n.length ];
    for ( int i = 0; i < n.length; i++ ) {
      fs[ i ] = new TextFileInputField( n[ i ], -1, -1 );
      fs[ i ].setType( ValueMetaInterface.TYPE_STRING );
    }
    csv.setInputFields( fs );
    StepMeta s1 = new StepMeta( "CsvInput", "Leer ventas", csv );
    s1.setLocation( 100, 100 );
    StepMeta s2 = new StepMeta( "Dummy", "Resultado", new DummyTransMeta() );
    s2.setLocation( 300, 100 );
    t.addStep( s1 );
    t.addStep( s2 );
    t.addTransHop( new TransHopMeta( s1, s2 ) );
    File ktr = new File( dir, "ventas.ktr" );
    Files.write( ktr.toPath(), t.getXML().getBytes( StandardCharsets.UTF_8 ) );
    return ktr;
  }

  static Opciones opciones( String formato, Destino d, String idioma ) {
    Opciones o = new Opciones();
    o.formato = formato;
    o.destino = d;
    o.idioma = idioma;
    o.titulo = "Ventas por tienda";
    o.autor = "Equipo de datos";
    o.introduccion = "Este cuaderno **reproduce** la transformación de ventas.";
    o.eda = true;
    return o;
  }

  public void pruebaPyConOpciones() throws Exception {
    File dir = EntornoKettle.salida( "traductor-escritores/py" );
    File ktr = ejemplo( dir );
    TransMeta t = new TransMeta( ktr.getAbsolutePath() );
    Programa p = TraductorTrans.traducir( t, Destino.PANDAS, "es" );
    String py = EscritorPy.escribir( p, opciones( "py", Destino.PANDAS, "es" ) );
    contiene( py, "Ventas por tienda", "título" );
    contiene( py, "Autor: Equipo de datos", "autor" );
    contiene( py, "**reproduce**", "introducción" );
    contiene( py, "Origen: ventas.ktr", "origen" );
    contiene( py, "pip install pandas", "cómo correrlo" );
    contiene( py, "matplotlib", "EDA pide matplotlib" );
    contiene( py, "# analisis_exploratorio()", "EDA apagado por defecto" );
    contiene( py, "def eda(", "función EDA" );
    contiene( py, "# Resumen: Steps: 2", "resumen" );
    contiene( py, "# NOTA: ", "notas" );
    // explicación de DescribirStep (el bloque no trae una)
    contiene( py, "ventas.csv", "explicación del CsvInput" );
    File f = new File( dir, "ventas.py" );
    Files.write( f.toPath(), py.getBytes( StandardCharsets.UTF_8 ) );
    PythonPrueba.compila( f );
    // inglés y sin notas
    Opciones en = opciones( "py", Destino.PANDAS, "en" );
    en.notas = false;
    en.titulo = null;
    String pyEn = EscritorPy.escribir( TraductorTrans.traducir( t, Destino.PANDAS, "en" ), en );
    contiene( pyEn, "Python translation of the transformation 'ventas'", "título por defecto en inglés" );
    contiene( pyEn, "How to run it", "en: cómo correrlo" );
    noContiene( pyEn, "# NOTE: ", "sin notas" );
    // portugués
    String pyPt = EscritorPy.escribir( TraductorTrans.traducir( t, Destino.PYSPARK, "pt" ), opciones( "py",
      Destino.PYSPARK, "pt" ) );
    contiene( pyPt, "Como executá-lo", "pt" );
    contiene( pyPt, "pip install pyspark", "pip de PySpark" );
    contiene( pyPt, "Java 17", "aviso de Java" );
    contiene( pyPt, "limit(max_filas).toPandas()", "EDA en PySpark con muestra" );
    File fp = new File( dir, "ventas_pyspark.py" );
    Files.write( fp.toPath(), pyPt.getBytes( StandardCharsets.UTF_8 ) );
    PythonPrueba.compila( fp );
    // las firmas viejas siguen
    String viejo = EscritorPy.escribir( p );
    contiene( viejo, "import pandas as pd", "escribir(Programa)" );
    cierto( EscritorPy.cuerpo( p, "    " ).contains( "    df_leer_ventas = pd.read_csv(" ), "cuerpo con sangría" );
  }

  public void pruebaNotebookValidoYCorre() throws Exception {
    File dir = EntornoKettle.salida( "traductor-escritores/ipynb" );
    File ktr = ejemplo( dir );
    TransMeta t = new TransMeta( ktr.getAbsolutePath() );
    Programa p = TraductorTrans.traducir( t, Destino.PANDAS, "es" );
    String nb = EscritorIpynb.escribir( p, opciones( "ipynb", Destino.PANDAS, "es" ) );
    contiene( nb, "\"nbformat_minor\" : 5", "nbformat 4.5" );
    contiene( nb, "# Ventas por tienda", "celda de título" );
    contiene( nb, "**Autor:** Equipo de datos", "autor" );
    contiene( nb, "## Cómo correrlo", "cómo correrlo" );
    contiene( nb, "## Análisis exploratorio", "EDA" );
    contiene( nb, "eda(df_leer_ventas", "EDA de la entrada" );
    contiene( nb, "eda(df_resultado", "EDA de la salida" );
    contiene( nb, "| 1 | Leer ventas | `CsvInput` | aproximado |", "tabla resumen" );
    contiene( nb, "df_resultado.head(10)", "vista previa" );
    File f = new File( dir, "ventas.ipynb" );
    Files.write( f.toPath(), nb.getBytes( StandardCharsets.UTF_8 ) );
    if ( PythonPrueba.python() == null ) {
      Verificador.saltear( "sin Python" );
    }
    PythonPrueba.Resultado imp = PythonPrueba.correr( dir, 120, PythonPrueba.python(), "-c", "import nbformat" );
    if ( imp.codigo != 0 ) {
      Verificador.saltear( "sin nbformat en " + PythonPrueba.python() + ": " + imp.salida );
    }
    PythonPrueba.Resultado v = PythonPrueba.correr( dir, 120, PythonPrueba.python(), "-c", "import nbformat, sys; "
      + "nb = nbformat.read(sys.argv[1], as_version=4); nbformat.validate(nb); print('valido', nb.nbformat_minor)",
      f.getAbsolutePath() );
    cierto( v.codigo == 0 && v.salida.contains( "valido 5" ), "nbformat valida el notebook: " + v.salida );
    if ( !PythonPrueba.tiene( "nbclient" ) || !PythonPrueba.tiene( "matplotlib" ) ) {
      Verificador.saltear( "sin nbclient o matplotlib" );
    }
    PythonPrueba.Resultado r = PythonPrueba.correr( dir, 300, PythonPrueba.python(), "-c", "import nbformat, sys; "
      + "from nbclient import NotebookClient; nb = nbformat.read(sys.argv[1], as_version=4); "
      + "NotebookClient(nb, timeout=240, kernel_name='python3', resources={'metadata': {'path': sys.argv[2]}})"
      + ".execute(); imgs = sum(1 for c in nb.cells if c.cell_type == 'code' for o in c.get('outputs', []) "
      + "if 'image/png' in o.get('data', {})); print('corrio', imgs)", f.getAbsolutePath(), dir.getAbsolutePath() );
    cierto( r.codigo == 0 && r.salida.contains( "corrio" ), "el notebook corre con nbclient: " + r.salida );
    int imgs = Integer.parseInt( r.salida.replaceAll( "(?s).*corrio (\\d+).*", "$1" ) );
    cierto( imgs >= 2, "el EDA dibuja gráficos (" + imgs + ")" );
  }

  public void pruebaSinExplicacionesNiVistas() throws Exception {
    File dir = EntornoKettle.salida( "traductor-escritores/min" );
    TransMeta t = new TransMeta( ejemplo( dir ).getAbsolutePath() );
    Opciones o = new Opciones();
    o.explicaciones = false;
    o.vistas = false;
    o.notas = false;
    Programa p = TraductorTrans.traducir( t, Destino.PANDAS, "es" );
    String nb = EscritorIpynb.escribir( p, o );
    noContiene( nb, ".head(10)", "sin vistas" );
    noContiene( nb, "> Nota:", "sin notas" );
    noContiene( nb, "Análisis exploratorio", "sin EDA" );
    contiene( nb, "## Resumen", "el resumen va siempre" );
  }
}
