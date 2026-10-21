package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.errorhandling.StreamInterface;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Supresiones;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasSeguridad;

/**
 * apply_lint_fixes y suppress_lint sin Spoon (docs/linter-diseno.md §6-§7, WP5): las keys se resuelven todas antes de
 * tocar nada (los renombres van al final), las keys viejas se rechazan, los renombres actualizan las referencias
 * (targets de Filter rows, &lt;from&gt; de Stream lookup, step1 de Merge join, destino del manejo de errores) y las
 * supresiones sobreviven a guardar y volver a abrir un .ktr y un .kjb. Además, cada clase de arreglo sobre los
 * ejemplos de la skill con el error de 03-errores-comunes.md que corrige.
 *
 * @dueño calidad
 */
public class PruebaArreglos {

  /** Lo que hace apply_lint_fixes sin la UI: analiza una copia y aplica sobre el documento. */
  private static final class Corrida {
    LintArreglos.Analisis an;
    LintArreglos.Hecho hecho;
    UndoGrupo undo;

    List<String> aplicadas() {
      List<String> l = new ArrayList<String>();
      for ( JsonNode x : hecho.aplicados ) {
        l.add( x.path( "key" ).asText() );
      }
      return l;
    }

    String saltada( String key ) {
      for ( String[] s : hecho.saltados ) {
        if ( s[ 0 ].equals( key ) ) {
          return s[ 1 ];
        }
      }
      return null;
    }

    String texto() {
      StringBuilder b = new StringBuilder( "applied " + hecho.aplicados );
      for ( String[] s : hecho.saltados ) {
        b.append( "; skipped " ).append( s[ 0 ] ).append( ": " ).append( s[ 1 ] );
      }
      return b.toString();
    }
  }

  private static Corrida arreglar( AbstractMeta doc, String args ) throws Exception {
    return arreglar( doc, doc, args );
  }

  /** Analiza 'analizado' (una copia) y aplica sobre 'doc': si no son el mismo documento, simula un cambio en medio. */
  private static Corrida arreglar( AbstractMeta analizado, AbstractMeta doc, String args ) throws Exception {
    LintArreglos.Pedido p = LintArreglos.pedido( McpHttpServer.JSON.readTree( args ) );
    AbstractMeta copia = ReportTools.clonar( analizado );
    Opciones op = new Opciones();
    op.lang = p.lang;
    op.sinGuardar = true; // los documentos de estas pruebas cambian en memoria: las reglas no leen el archivo
    op.entorno = new LintEntorno( copia );
    Corrida c = new Corrida();
    c.an = LintArreglos.analizar( copia, op, p );
    c.undo = UndoGrupo.sinSpoon( doc );
    c.hecho = LintArreglos.aplicar( doc, c.an, p.lang, c.undo, null, null );
    return c;
  }

  private static Resultado lint( AbstractMeta doc, String lang ) {
    AbstractMeta copia = ReportTools.clonar( doc );
    Opciones op = new Opciones();
    op.lang = lang;
    op.sinGuardar = true;
    op.entorno = new LintEntorno( copia );
    Resultado r = LintMotor.analizar( copia, op );
    LintPropuestas.completar( r, copia, lang );
    return r;
  }

  private static Hallazgo hallazgo( Resultado r, String key ) {
    for ( Hallazgo h : r.findings ) {
      if ( h.key.equals( key ) ) {
        return h;
      }
    }
    return null;
  }

  private static Hallazgo silenciado( Resultado r, String key ) {
    for ( Hallazgo h : r.suppressed ) {
      if ( h.key.equals( key ) ) {
        return h;
      }
    }
    return null;
  }

  private static String claves( Resultado r ) {
    List<String> l = new ArrayList<String>();
    for ( Hallazgo h : r.findings ) {
      l.add( h.key );
    }
    return l.toString();
  }

  private static int cuenta( Resultado r, String regla ) {
    int n = 0;
    for ( Hallazgo h : r.findings ) {
      n += h.rule.equals( regla ) ? 1 : 0;
    }
    return n;
  }

  /** Copia de un ejemplo de la skill con reemplazos de texto, junto a los demás (sus rutas relativas siguen andando). */
  private static File variante( String ejemplo, String nombre, String... reemplazos ) throws Exception {
    File f = EntornoKettle.ejemplo( ejemplo );
    String x = EntornoKettle.leer( f );
    for ( int i = 0; i + 1 < reemplazos.length; i += 2 ) {
      contiene( x, reemplazos[ i ], "el ejemplo " + ejemplo + " tiene el texto a reemplazar" );
      x = x.replace( reemplazos[ i ], reemplazos[ i + 1 ] );
    }
    File g = new File( f.getParentFile(), nombre );
    EntornoKettle.escribir( g, x );
    return g;
  }

  private static TransMeta trans( File f ) throws Exception {
    EntornoKettle.iniciar();
    return new TransMeta( f.getAbsolutePath() );
  }

  private static JobMeta job( File f ) throws Exception {
    EntornoKettle.iniciar();
    return new JobMeta( f.getAbsolutePath(), null );
  }

  /** Guardar y volver a abrir: el XML del documento a un archivo y new TransMeta/JobMeta de ese archivo. */
  private static TransMeta guardarYAbrir( TransMeta tm, String nombre ) throws Exception {
    File f = new File( EntornoKettle.salida( "arreglos" ), nombre );
    EntornoKettle.escribir( f, tm.getXML() );
    return new TransMeta( f.getAbsolutePath() );
  }

  private static JobMeta guardarYAbrir( JobMeta jm, String nombre ) throws Exception {
    File f = new File( EntornoKettle.salida( "arreglos" ), nombre );
    EntornoKettle.escribir( f, jm.getXML() );
    return new JobMeta( f.getAbsolutePath(), null );
  }

  private static String xml( StepMeta s ) throws Exception {
    return s.getXML();
  }

  private static void quitarStep( TransMeta tm, String nombre ) {
    StepMeta s = tm.findStep( nombre );
    for ( TransHopMeta h : TransOps.hopsDe( tm, s ) ) {
      tm.removeTransHop( tm.indexOfTransHop( h ) );
    }
    tm.removeStep( tm.indexOfStep( s ) );
  }

  // ---------------------------------------------------------------- keys

  /**
   * Una Calculator con nombre por defecto y A Integer: el intercambio de A y B se resolvió con el step antes del
   * renombre (que va al final), así que los dos se aplican en la misma llamada aunque la key del primero tenga el
   * nombre viejo. all_auto toma los dos y el renombre del Sort rows; un Ctrl+Z.
   */
  public void pruebaClavesYRenombresAlFinal() throws Exception {
    TransMeta tm = trans( variante( "03", "arr_calc.ktr",
      "Calcular importe", "Calculator", "Ordenar por producto", "Sort rows",
      "<field_a>precio_unitario</field_a>\n      <field_b>cantidad</field_b>",
      "<field_a>cantidad</field_a>\n      <field_b>precio_unitario</field_b>" ) );
    Resultado r = lint( tm, "en" );
    Hallazgo calc = hallazgo( r, "calculator_integer_first:step:Calculator:importe" );
    noNulo( calc, "calculator_integer_first con la key del §5: " + claves( r ) );
    igual( "swap_operands", calc.fix.kind, "arreglo del intercambio" );
    Hallazgo nombre = hallazgo( r, "default_step_name:step:Calculator:name" );
    noNulo( nombre, "default_step_name de la Calculator: " + claves( r ) );
    igual( "Calculate importe", nombre.fix.preview, "propuesta sin IA para la Calculator" );
    falso( nombre.fix.needsValue, "con propuesta ya no falta el valor" );
    igual( "Sort by producto", hallazgo( r, "default_step_name:step:Sort rows:name" ).fix.preview, "propuesta del Sort rows" );
    contiene( LintJson.hallazgo( nombre ).toString(), "\"preview\":\"Calculate importe\"", "la propuesta sale en el JSON" );

    Corrida c = arreglar( tm, "{\"keys\":[\"all_auto\"]}" );
    igual( new ArrayList<String>( Arrays.asList( "calculator_integer_first:step:Calculator:importe",
      "default_step_name:step:Calculator:name", "default_step_name:step:Sort rows:name" ) ), c.aplicadas(),
      "all_auto: el intercambio primero y los renombres al final: " + c.texto() );
    StepMeta s = tm.findStep( "Calculate importe" );
    noNulo( s, "Calculator renombrada" );
    contiene( xml( s ), "<field_a>precio_unitario</field_a>", "A y B intercambiados en el step ya renombrado" );
    noNulo( tm.findStep( "Sort by producto" ), "Sort rows renombrado" );
    cierto( tm.findTransHop( tm.findStep( "Sort by producto" ), tm.findStep( "Totales por producto" ) ) != null,
      "el hop sigue al step renombrado" );
    igual( 1, c.hecho.pasosDeshacer, "un Ctrl+Z en una transformación" );
    cierto( c.undo.acciones() >= 3, "tres cambios en el grupo: " + c.undo.acciones() );
    cierto( tm.viewThisUndo().getNextAlso(), "la última acción se deshace junto con las anteriores" );
    Resultado despues = lint( tm, "en" );
    igual( 0, cuenta( despues, "calculator_integer_first" ) + cuenta( despues, "default_step_name" ),
      "nada que arreglar después: " + claves( despues ) );
  }

  /** Keys que ya no salen, objetos que desaparecieron entre el análisis y la aplicación, y los argumentos. */
  public void pruebaClavesViejas() throws Exception {
    TransMeta tm = trans( variante( "03", "arr_viejas.ktr", "Calcular importe", "Calculator",
      "<field_a>precio_unitario</field_a>\n      <field_b>cantidad</field_b>",
      "<field_a>cantidad</field_a>\n      <field_b>precio_unitario</field_b>" ) );
    String swap = "calculator_integer_first:step:Calculator:importe";
    String renombre = "default_step_name:step:Calculator:name";
    String args = "{\"keys\":[\"" + swap + "\",\"" + renombre + "\",\"absolute_path:step:Otro:filename\"]}";

    // el step se borró después del análisis: no se encuentra al resolver (y no se toca nada)
    TransMeta vivo = trans( new File( tm.getFilename() ) );
    quitarStep( vivo, "Calculator" );
    int huella = LintArreglos.huella( vivo );
    Corrida c = arreglar( tm, vivo, args );
    igual( 0, c.hecho.cambios, "nada aplicado: " + c.texto() );
    contiene( c.saltada( swap ), "there is no step 'Calculator'", "step que ya no está" );
    contiene( c.saltada( "absolute_path:step:Otro:filename" ), "no longer found", "key que no sale" );
    igual( huella, LintArreglos.huella( vivo ), "el documento quedó igual" );

    // la calculación cambió a mano entre el análisis y la aplicación
    vivo = trans( new File( tm.getFilename() ) );
    TransOps.reconfigurar( vivo, vivo.findStep( "Calculator" ), "<calculation><field_name>importe</field_name>"
      + "<calc_type>MULTIPLY</calc_type><field_a>precio_unitario</field_a><field_b>cantidad</field_b>"
      + "<value_type>Number</value_type></calculation>", null );
    c = arreglar( tm, vivo, args );
    contiene( c.saltada( swap ), "changed since the analysis", "precondición vieja" );
    igual( Arrays.asList( renombre ), c.aplicadas(), "el renombre igual se aplica: " + c.texto() );

    // aplicado: la segunda vez nada sale
    c = arreglar( tm, "{\"keys\":[\"" + swap + "\",\"" + renombre + "\"]}" );
    igual( 2, c.hecho.cambios, "primera vez: " + c.texto() );
    c = arreglar( tm, "{\"keys\":[\"" + swap + "\",\"" + renombre + "\"]}" );
    igual( 0, c.hecho.cambios, "segunda vez: nada" );
    contiene( c.saltada( swap ), "no longer found", "key vieja (intercambio)" );
    contiene( c.saltada( renombre ), "no longer found", "key vieja (renombre)" );

    // la huella cambia con cualquier cambio
    int h1 = LintArreglos.huella( tm );
    igual( h1, LintArreglos.huella( tm ), "huella estable" );
    tm.findStep( "Calculate importe" ).setDistributes( false );
    cierto( h1 != LintArreglos.huella( tm ), "huella distinta tras un cambio" );

    // argumentos
    lanza( ToolError.class, () -> LintArreglos.pedido( McpHttpServer.JSON.readTree( "{}" ) ), "sin keys" );
    lanza( ToolError.class, () -> LintArreglos.pedido( McpHttpServer.JSON.readTree( "{\"keys\":[\"x\"],"
      + "\"language\":\"fr\"}" ) ), "idioma desconocido" );
    LintArreglos.Pedido p = LintArreglos.pedido( McpHttpServer.JSON.readTree( "{\"keys\":\"[\\\"all_auto\\\", "
      + "\\\"a:step:Filtrar por id, name:name\\\"]\",\"values\":\"{\\\"b:doc::description\\\":\\\"hola\\\"}\"}" ) );
    cierto( p.todosAuto, "all_auto en un array como texto" );
    igual( new ArrayList<String>( Arrays.asList( "a:step:Filtrar por id, name:name", "b:doc::description" ) ),
      new ArrayList<String>( p.keys ), "una key con coma no se parte; una key de values se pide" );
    igual( "hola", p.values.get( "b:doc::description" ), "values como texto JSON" );
  }

  /** Nombres propuestos sin IA, por idioma, desde la configuración del step o de la entrada. */
  public void pruebaPropuestas() throws Exception {
    TransMeta tm = trans( variante( "01", "prop_01.ktr", "Generar filas", "Generate rows", "Agregar constantes",
      "Add constants", "Escribir CSV", "Text file output" ) );
    Resultado r = lint( tm, "en" );
    contiene( hallazgo( r, "default_step_name:step:Generate rows:name" ).fix.preview, "Generate ", "Generate rows" );
    contiene( hallazgo( r, "default_step_name:step:Generate rows:name" ).fix.preview, " rows", "con el límite" );
    contiene( hallazgo( r, "default_step_name:step:Add constants:name" ).fix.preview, "Add ", "Add constants" );
    // escribe en salida/${ARCHIVO_SALIDA}: una variable no es un nombre, queda para Claude o la persona
    cierto( hallazgo( r, "default_step_name:step:Text file output:name" ).fix.needsValue, "archivo con variable" );
    r = lint( tm, "pt" );
    igual( "Gerar 5 linhas", hallazgo( r, "default_step_name:step:Generate rows:name" ).fix.preview, "en portugués" );

    tm = trans( variante( "02", "prop_02.ktr", "Activos con saldo", "Filter rows" ) );
    String p = hallazgo( lint( tm, "es" ), "default_step_name:step:Filter rows:name" ).fix.preview;
    cierto( p.startsWith( "Filtrar por " ), "Filter rows por los campos de la condición: " + p );

    tm = trans( variante( "07", "prop_07.ktr", "Consultar SQLite", "Table input",
      "SELECT 1 AS id, 'Madrid' AS ciudad, 2.5 AS valor", "SELECT id, ciudad, valor FROM main.ciudades" ) );
    igual( "Leer ciudades", hallazgo( lint( tm, "es" ), "default_step_name:step:Table input:name" ).fix.preview,
      "Table input por la tabla del FROM" );

    JobMeta jm = job( variante( "10", "prop_10.kjb", "Generar constantes", "Transformation" ) );
    r = lint( jm, "es" );
    Hallazgo h = null;
    for ( Hallazgo x : r.findings ) {
      h = x.rule.equals( "default_entry_name" ) ? x : h;
    }
    noNulo( h, "entrada con nombre por defecto: " + claves( r ) );
    igual( "Ejecutar 01_generar_constantes", h.fix.preview, "la entrada TRANS por su archivo" );

    // únicos entre sí y con el documento; nada para lo que no dice qué hace
    tm = trans( variante( "02", "prop_unicos.ktr", "Descartados", "Dummy (do nothing)", "Elegir columnas", "Leer clientes 2" ) );
    TransOps.agregarStep( tm, "CsvInput", "CSV file input", "<filename>${Internal.Entry"
      + ".Current.Directory}/datos/clientes.csv</filename>", 600, 300, null, null, null );
    r = lint( tm, "es" );
    cierto( hallazgo( r, "default_step_name:step:Dummy (do nothing):name" ).fix.needsValue, "Dummy sin propuesta" );
    igual( "Leer clientes 3", hallazgo( r, "default_step_name:step:CSV file input:name" ).fix.preview,
      "no choca con los nombres que hay" );
    igual( "Ordenar por a, b, c +2", LintPropuestas.nombreOrden( Arrays.asList( "a", "b", "c", "d", "e" ), "es" ),
      "hasta tres campos" );
    igual( "ventas", LintPropuestas.nombreDeArchivo( "${Internal.Entry.Current.Directory}/datos/ventas.csv" ), "archivo" );
    nulo( LintPropuestas.nombreDeArchivo( "${ARCHIVO}" ), "una variable no es un nombre" );
    igual( "clientes", LintPropuestas.tablaDeSql( "select * from \"dw\".\"clientes\" c where 1=1" ), "tabla del FROM" );
  }

  // ---------------------------------------------------------------- referencias

  /** Renombrar actualiza quien nombra al step: targets de Filter rows, Stream lookup, Merge join y errores. */
  public void pruebaRenombresActualizanReferencias() throws Exception {
    // Filter rows: send_true_to / send_false_to
    TransMeta tm = trans( variante( "02", "arr_filtro.ktr", "Elegir columnas", "Select values",
      "Descartados", "Dummy (do nothing)" ) );
    Resultado r = lint( tm, "es" );
    String kSel = "default_step_name:step:Select values:name";
    String kDummy = "default_step_name:step:Dummy (do nothing):name";
    noNulo( hallazgo( r, kSel ), "Select values por defecto: " + claves( r ) );
    cierto( hallazgo( r, kDummy ).fix.needsValue, "Dummy: sin propuesta, falta el valor" );
    String elegir = hallazgo( r, kSel ).fix.preview;
    contiene( elegir, "Elegir ", "propuesta en castellano: " + elegir );
    Corrida c = arreglar( tm, "{\"keys\":[\"all_auto\"],\"language\":\"es\",\"values\":{\"" + kDummy
      + "\":\"Descartar inactivos\"}}" );
    igual( 2, c.hecho.cambios, c.texto() );
    String filtro = xml( tm.findStep( "Activos con saldo" ) );
    contiene( filtro, "<send_true_to>" + elegir + "</send_true_to>", "send_true_to actualizado" );
    contiene( filtro, "<send_false_to>Descartar inactivos</send_false_to>", "send_false_to actualizado" );
    TransMeta otra = guardarYAbrir( tm, "arr_filtro_guardado.ktr" );
    List<String> destinos = new ArrayList<String>();
    for ( StreamInterface t : otra.findStep( "Activos con saldo" ).getStepMetaInterface().getStepIOMeta()
      .getTargetStreams() ) {
      noNulo( t.getStepMeta(), "target resuelto al abrir" );
      destinos.add( t.getStepMeta().getName() );
    }
    igual( Arrays.asList( elegir, "Descartar inactivos" ), destinos, "targets del Filter rows tras guardar y abrir" );

    // Stream lookup: <from>
    tm = trans( variante( "04", "arr_lookup.ktr", "Leer clientes", "CSV file input" ) );
    r = lint( tm, "es" );
    Hallazgo csv = hallazgo( r, "default_step_name:step:CSV file input:name" );
    igual( "Leer clientes", csv.fix.preview, "propuesta desde el archivo que lee" );
    c = arreglar( tm, "{\"keys\":[\"" + csv.key + "\"],\"language\":\"es\"}" );
    igual( 1, c.hecho.cambios, c.texto() );
    contiene( c.hecho.aplicados.get( 0 ).path( "change" ).asText(), "Buscar cliente", "dice dónde actualizó" );
    contiene( xml( tm.findStep( "Buscar cliente" ) ), "<from>Leer clientes</from>", "<from> actualizado" );
    otra = guardarYAbrir( tm, "arr_lookup_guardado.ktr" );
    StreamInterface info = otra.findStep( "Buscar cliente" ).getStepMetaInterface().getStepIOMeta().getInfoStreams().get( 0 );
    igual( "Leer clientes", info.getStepMeta() == null ? null : info.getStepMeta().getName(), "lookup resuelto al abrir" );

    // Merge join: step1
    tm = trans( variante( "05", "arr_join.ktr", "Ordenar clientes", "Sort rows" ) );
    r = lint( tm, "en" );
    Hallazgo sort = hallazgo( r, "default_step_name:step:Sort rows:name" );
    noNulo( sort, "Sort rows por defecto: " + claves( r ) );
    String nuevo = sort.fix.preview;
    c = arreglar( tm, "{\"keys\":[\"" + sort.key + "\"]}" );
    igual( 1, c.hecho.cambios, c.texto() );
    contiene( xml( tm.findStep( "Unir clientes y ventas" ) ), "<step1>" + nuevo + "</step1>", "step1 actualizado" );
    otra = guardarYAbrir( tm, "arr_join_guardado.ktr" );
    info = otra.findStep( "Unir clientes y ventas" ).getStepMetaInterface().getStepIOMeta().getInfoStreams().get( 0 );
    igual( nuevo, info.getStepMeta() == null ? null : info.getStepMeta().getName(), "step1 resuelto al abrir" );
    igual( 0, cuenta( lint( otra, "en" ), "unsorted_input" ), "el Merge join sigue ordenado" );

    // manejo de errores: target_step
    tm = trans( variante( "06", "arr_errores.ktr", "Escribir errores", "Text file output" ) );
    r = lint( tm, "es" );
    Hallazgo tfo = hallazgo( r, "default_step_name:step:Text file output:name" );
    igual( "Escribir 06_errores", tfo.fix.preview, "propuesta desde el archivo que escribe" );
    c = arreglar( tm, "{\"keys\":[\"" + tfo.key + "\"],\"language\":\"es\"}" );
    igual( 1, c.hecho.cambios, c.texto() );
    igual( "Escribir 06_errores", tm.findStep( "Leer clientes con errores" ).getStepErrorMeta().getTargetStep().getName(),
      "destino de los errores" );
    contiene( tm.getXML(), "<target_step>Escribir 06_errores</target_step>", "target_step en el XML" );
    otra = guardarYAbrir( tm, "arr_errores_guardado.ktr" );
    StepMeta destino = otra.findStep( "Leer clientes con errores" ).getStepErrorMeta().getTargetStep();
    igual( "Escribir 06_errores", destino == null ? null : destino.getName(), "manejo de errores tras guardar y abrir" );
  }

  /**
   * Los steps que guardan el nombre de otro como texto (Prioritize streams, Block this step until steps finish,
   * Multiway merge join, Join rows, XML join): el renombre de all_auto (y rename_step) los reescribe y siguen
   * resueltos tras guardar y abrir. Lo que queda nombrando el nombre viejo se avisa. El Injector lo nombra otro
   * archivo: pide confirmación.
   */
  public void pruebaRenombresConReferenciasDeTexto() throws Exception {
    EntornoKettle.iniciar();
    TransMeta tm = new TransMeta();
    tm.setName( "referencias de texto" );
    TransOps.agregarStep( tm, "RowGenerator", "Generate rows", "<limit>5</limit>", 100, 100, null, null, null );
    TransOps.agregarStep( tm, "RowGenerator", "Leer otro", "<limit>3</limit>", 100, 250, null, null, null );
    TransOps.agregarStep( tm, "PrioritizeStreams", "Priorizar", "<steps><step><name>Generate rows</name></step>"
      + "<step><name>Leer otro</name></step></steps>", 300, 150, "Generate rows", null, null );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Leer otro" ), tm.findStep( "Priorizar" ) ) );
    TransOps.agregarStep( tm, "BlockUntilStepsFinish", "Esperar", "<steps><step><name>Generate rows</name>"
      + "<CopyNr>0</CopyNr></step></steps>", 500, 150, "Priorizar", null, null );
    TransOps.agregarStep( tm, "Constant", "Etiquetar", "<fields><field><name>origen</name><type>String</type>"
      + "<nullif>Generate rows</nullif></field></fields>", 700, 150, "Esperar", null, null );
    TransOps.resolverReferencias( tm );

    Resultado r = lint( tm, "en" );
    String k = "default_step_name:step:Generate rows:name";
    Hallazgo h = hallazgo( r, k );
    noNulo( h, "Generate rows por defecto: " + claves( r ) );
    String nuevo = h.fix.preview;
    noNulo( nuevo, "con propuesta" );
    Corrida c = arreglar( tm, "{\"keys\":[\"all_auto\"]}" );
    cierto( c.aplicadas().contains( k ), "all_auto lo renombra: " + c.texto() );
    String cambio = c.hecho.aplicados.get( c.aplicadas().indexOf( k ) ).path( "change" ).asText();
    contiene( cambio, "'Priorizar'", "dice que actualizó el Prioritize streams: " + cambio );
    contiene( cambio, "'Esperar'", "y el Block until steps finish" );
    contiene( xml( tm.findStep( "Priorizar" ) ), "<name>" + nuevo + "</name>", "Prioritize streams reescrito" );
    noContiene( xml( tm.findStep( "Priorizar" ) ), "<name>Generate rows</name>", "sin el nombre viejo" );
    contiene( xml( tm.findStep( "Esperar" ) ), "<name>" + nuevo + "</name>", "Block until steps finish reescrito" );
    contiene( String.join( " ", c.hecho.notas ), "'Etiquetar' (nullif) still name 'Generate rows'",
      "avisa lo que sigue nombrando el nombre viejo: " + c.hecho.notas );
    TransMeta otra = guardarYAbrir( tm, "arr_texto_guardado.ktr" );
    igual( Arrays.asList( nuevo, "Leer otro" ), Arrays.asList( ( (org.pentaho.di.trans.steps.prioritizestreams
      .PrioritizeStreamsMeta) otra.findStep( "Priorizar" ).getStepMetaInterface() ).getStepName() ),
      "Prioritize streams tras guardar y abrir" );
    contiene( xml( otra.findStep( "Esperar" ) ), "<name>" + nuevo + "</name>", "Block until tras guardar y abrir" );

    // rename_step: Multiway merge join (step0, step1) y Join rows (main), releídos por Kettle
    tm = new TransMeta();
    TransOps.agregarStep( tm, "RowGenerator", "Uno", "<limit>1</limit>", 100, 100, null, null, null );
    TransOps.agregarStep( tm, "RowGenerator", "Dos", "<limit>1</limit>", 100, 250, null, null, null );
    TransOps.agregarStep( tm, "MultiwayMergeJoin", "Unir", "<join_type>INNER</join_type><step0>Uno</step0>"
      + "<step1>Dos</step1><number_input>2</number_input><keys><key>id</key><key>id</key></keys>", 300, 150, "Uno",
      null, null );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Dos" ), tm.findStep( "Unir" ) ) );
    TransOps.agregarStep( tm, "JoinRows", "Cruzar", "<main>Uno</main>", 300, 400, null, null, null );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Uno" ), tm.findStep( "Cruzar" ) ) );
    TransOps.resolverReferencias( tm );
    TransOps.renombrar( tm, tm.findStep( "Uno" ), "Leer uno", null );
    org.pentaho.di.trans.steps.multimerge.MultiMergeJoinMeta mm = (org.pentaho.di.trans.steps.multimerge
      .MultiMergeJoinMeta) tm.findStep( "Unir" ).getStepMetaInterface();
    igual( Arrays.asList( "Leer uno", "Dos" ), Arrays.asList( mm.getInputSteps() ), "Multiway merge join releído" );
    StepMeta info = mm.getStepIOMeta().getInfoStreams().get( 0 ).getStepMeta();
    igual( "Leer uno", info == null ? null : info.getName(), "y resuelto" );
    org.pentaho.di.trans.steps.joinrows.JoinRowsMeta jr = (org.pentaho.di.trans.steps.joinrows.JoinRowsMeta) tm
      .findStep( "Cruzar" ).getStepMetaInterface();
    igual( "Leer uno", jr.getMainStepname(), "Join rows: main" );
    igual( "Leer uno", jr.getMainStep() == null ? null : jr.getMainStep().getName(), "Join rows: main resuelto" );
    // XML join (plugin aparte): la reescritura sola, sin mayúsculas como Kettle
    String xj = TransOps.reescribirReferencias( "XMLJoin", "<step><name>Unir XML</name><targetXMLstep>table input"
      + "</targetXMLstep><sourceXMLstep>Otro</sourceXMLstep></step>", "Table input", "Leer ventas" );
    contiene( xj, "<targetXMLstep>Leer ventas</targetXMLstep>", "XML join: target" );
    contiene( xj, "<sourceXMLstep>Otro</sourceXMLstep>", "XML join: el otro queda" );
    nulo( TransOps.reescribirReferencias( "XMLJoin", "<step><targetXMLstep>A</targetXMLstep></step>", "Table input",
      "T" ), "no lo nombra: nada que reescribir" );
    nulo( TransOps.reescribirReferencias( "Dummy", "<step><name>Table input</name></step>", "Table input", "T" ),
      "un tipo que no guarda nombres" );

    // el Injector lo nombra otro archivo (Single threader, un programa): renombrarlo pide confirmación
    tm = new TransMeta();
    TransOps.agregarStep( tm, "Injector", "Injector", null, 100, 100, null, null, null );
    h = hallazgo( lint( tm, "en" ), "default_step_name:step:Injector:name" );
    noNulo( h, "Injector por defecto" );
    cierto( h.fix.confirm, "confirm: lo nombra otro archivo" );
  }

  // ---------------------------------------------------------------- suppress_lint

  /** En el step (con undo) y en el documento; viaja dentro del .ktr; se quita. */
  public void pruebaSilenciarKtr() throws Exception {
    TransMeta tm = trans( variante( "03", "sil_hop.ktr", "<from>Calcular importe</from>\n      <to>Ordenar por producto</to>\n"
      + "      <enabled>Y</enabled>", "<from>Calcular importe</from>\n      <to>Ordenar por producto</to>\n"
      + "      <enabled>N</enabled>" ) );
    String key = "disabled_hop:hop:Calcular importe -> Ordenar por producto:enabled";
    noNulo( hallazgo( lint( tm, "en" ), key ), "hop deshabilitado" );
    tm.setChanged( false ); // como recién guardado: setAttribute solo no lo marca

    UndoGrupo undo = UndoGrupo.sinSpoon( tm );
    ObjectNode o = LintArreglos.silenciar( tm, "disabled_hop", "Calcular importe", null, "a propósito", false, undo );
    cierto( o.path( "changed" ).asBoolean(), "cambió: " + o );
    igual( "step", o.path( "scope" ).asText(), "en el step" );
    cierto( o.path( "undoable" ).asBoolean(), "en un step se deshace" );
    cierto( tm.hasChanged(), "setChanged()" );
    TransAction ta = tm.viewThisUndo();
    igual( TransAction.TYPE_ACTION_CHANGE_STEP, ta.getType(), "un cambio de step en el undo" );
    falso( Supresiones.silencia( (StepMeta) ta.getPrevious()[ 0 ], "disabled_hop" ), "el antes del undo no lo silencia" );
    cierto( Supresiones.silencia( (StepMeta) ta.getCurrent()[ 0 ], "disabled_hop" ), "el después sí" );
    o = LintArreglos.silenciar( tm, "select_star", null, null, null, false, undo );
    igual( "doc", o.path( "scope" ).asText(), "en el documento" );
    falso( o.path( "undoable" ).asBoolean(), "en el documento no se deshace" );
    falso( LintArreglos.silenciar( tm, "disabled_hop", "Calcular importe", null, null, false, undo ).path( "changed" )
      .asBoolean(), "silenciar dos veces no cambia nada" );

    Resultado r = lint( tm, "en" );
    Hallazgo h = silenciado( r, key );
    noNulo( h, "silenciado: " + claves( r ) );
    igual( "a propósito", h.evidence.get( "suppressed_reason" ), "con el motivo" );
    nulo( hallazgo( r, key ), "ya no resta" );

    TransMeta otra = guardarYAbrir( tm, "sil_hop_guardado.ktr" );
    StepMeta s = otra.findStep( "Calcular importe" );
    cierto( Supresiones.silencia( s, "disabled_hop" ), "el step guardado lo silencia" );
    igual( "a propósito", Supresiones.motivo( s ), "con el motivo" );
    cierto( Supresiones.silencia( otra, "select_star" ), "el documento guardado lo silencia" );
    noNulo( silenciado( lint( otra, "en" ), key ), "tras guardar y abrir sigue silenciado" );

    o = LintArreglos.silenciar( otra, "disabled_hop", "Calcular importe", null, null, true, UndoGrupo.sinSpoon( otra ) );
    cierto( o.path( "changed" ).asBoolean(), "quitado" );
    nulo( s.getAttributes( Supresiones.GRUPO ), "sin reglas, el grupo se borra" );
    noNulo( hallazgo( lint( otra, "en" ), key ), "vuelve a contar" );
    falso( LintArreglos.silenciar( otra, "disabled_hop", "Calcular importe", null, null, true,
      UndoGrupo.sinSpoon( otra ) ).path( "changed" ).asBoolean(), "quitar lo que no está no cambia nada" );

    // errores
    lanza( ToolError.class, () -> LintArreglos.reglaParaSilenciar( "no_such_rule" ), "regla desconocida" );
    lanza( ToolError.class, () -> LintArreglos.reglaParaSilenciar( "kettle_check" ), "kettle_check no se silencia" );
    igual( "*", LintArreglos.reglaParaSilenciar( "*" ), "todas" );
    igual( "absolute_path", LintArreglos.reglaParaSilenciar( "lint:absolute_path" ), "id del corrector" );
    lanza( ToolError.class, () -> LintArreglos.silenciar( otra, "orphan_entry", null, null, null, false,
      UndoGrupo.sinSpoon( otra ) ), "regla de jobs en una transformación" );
    lanza( ToolError.class, () -> LintArreglos.silenciar( otra, "disabled_hop", null, "START", null, false,
      UndoGrupo.sinSpoon( otra ) ), "entry en una transformación" );
    ToolError e = lanza( ToolError.class, () -> LintArreglos.silenciar( otra, "disabled_hop", "No existe", null, null,
      false, UndoGrupo.sinSpoon( otra ) ), "step que no existe" );
    contiene( e.getMessage(), "Calcular importe", "con los nombres que hay" );
  }

  /** En una entrada (todas sus copias, sin undo) y en el documento; viaja dentro del .kjb. */
  public void pruebaSilenciarKjb() throws Exception {
    File f = variante( "10", "sil_job.kjb", "<from>START</from>", "<from>START</from>" );
    JobMeta jm = job( f );
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      if ( "START".equals( h.getFromEntry().getName() ) ) {
        h.setEnabled( false );
      }
    }
    Resultado r = lint( jm, "en" );
    String hop = null;
    for ( Hallazgo h : r.findings ) {
      hop = h.rule.equals( "disabled_hop" ) ? h.key : hop;
    }
    noNulo( hop, "hop deshabilitado desde START: " + claves( r ) );
    cierto( cuenta( r, "unreachable_entry" ) > 0, "entradas inalcanzables" );

    ObjectNode o = LintArreglos.silenciar( jm, "disabled_hop", null, "start", "pruebas", false, UndoGrupo.sinSpoon( jm ) );
    igual( "entry", o.path( "scope" ).asText(), "en la entrada" );
    igual( "START", o.path( "entry" ).asText(), "con el nombre de la entrada" );
    falso( o.path( "undoable" ).asBoolean(), "en una entrada no se deshace" );
    LintArreglos.silenciar( jm, "unreachable_entry", null, null, "se arregla después", false, UndoGrupo.sinSpoon( jm ) );
    cierto( jm.hasChanged(), "setChanged()" );
    r = lint( jm, "en" );
    noNulo( silenciado( r, hop ), "hop silenciado en la entrada de origen" );
    igual( 0, cuenta( r, "unreachable_entry" ), "inalcanzables silenciadas en el documento" );
    lanza( ToolError.class, () -> LintArreglos.silenciar( jm, "orphan_step", null, null, null, false,
      UndoGrupo.sinSpoon( jm ) ), "regla de transformaciones en un job" );
    lanza( ToolError.class, () -> LintArreglos.silenciar( jm, "disabled_hop", "START", null, null, false,
      UndoGrupo.sinSpoon( jm ) ), "step en un job" );

    JobMeta otro = guardarYAbrir( jm, "sil_job_guardado.kjb" );
    JobEntryCopy start = otro.findJobEntry( "START", 0, true );
    cierto( Supresiones.silencia( start, "disabled_hop" ), "la entrada guardada lo silencia" );
    igual( "pruebas", Supresiones.motivo( start ), "con el motivo" );
    cierto( Supresiones.silencia( otro, "unreachable_entry" ), "el documento guardado lo silencia" );
    r = lint( otro, "en" );
    noNulo( silenciado( r, hop ), "tras guardar y abrir sigue silenciado" );
    igual( 0, cuenta( r, "unreachable_entry" ), "y las inalcanzables también" );
    LintArreglos.silenciar( otro, "disabled_hop", null, "START", null, true, UndoGrupo.sinSpoon( otro ) );
    noNulo( hallazgo( lint( otro, "en" ), hop ), "quitado: vuelve a contar" );
  }

  // ---------------------------------------------------------------- cada arreglo

  /** unsorted_input: Sort rows insertado (auto+c, fuera de all_auto) en Group by y en Merge join (step1/step2). */
  public void pruebaInsertarOrden() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    quitarStep( tm, "Ordenar por producto" );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Calcular importe" ), tm.findStep( "Totales por producto" ) ) );
    String key = "unsorted_input:step:Totales por producto:input";
    Hallazgo h = hallazgo( lint( tm, "en" ), key );
    noNulo( h, "Group by sin ordenar" );
    cierto( h.fix.confirm, "insertar un Sort rows pide confirmación" );
    Corrida c = arreglar( tm, "{\"keys\":[\"all_auto\"]}" );
    falso( c.aplicadas().contains( key ), "all_auto no inserta steps: " + c.texto() );
    c = arreglar( tm, "{\"keys\":[\"" + key + "\"]}" );
    igual( Arrays.asList( key ), c.aplicadas(), c.texto() );
    StepMeta orden = tm.findStep( "Sort by producto" );
    noNulo( orden, "Sort rows nuevo" );
    igual( "SortRows", orden.getStepID(), "tipo" );
    cierto( tm.findTransHop( tm.findStep( "Calcular importe" ), orden ) != null
      && tm.findTransHop( orden, tm.findStep( "Totales por producto" ) ) != null, "entre la fuente y el Group by" );
    nulo( tm.findTransHop( tm.findStep( "Calcular importe" ), tm.findStep( "Totales por producto" ) ), "sin el hop viejo" );
    igual( 3, c.undo.acciones(), "step + hop borrado + hops nuevos" );
    igual( 1, c.hecho.pasosDeshacer, "un Ctrl+Z" );
    igual( 0, cuenta( lint( tm, "en" ), "unsorted_input" ), "ordenado" );

    // Merge join: un Sort rows por entrada y step1/step2 apuntando a cada uno
    tm = EntornoKettle.trans( "05" );
    quitarStep( tm, "Ordenar clientes" );
    quitarStep( tm, "Ordenar ventas" );
    StepMeta mj = tm.findStep( "Unir clientes y ventas" );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Leer clientes" ), mj ) );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Leer ventas" ), mj ) );
    TransOps.reconfigurar( tm, mj, "<step1>Leer clientes</step1><step2>Leer ventas</step2>", null );
    Resultado r = lint( tm, "es" );
    String k1 = "unsorted_input:step:Unir clientes y ventas:step1";
    String k2 = "unsorted_input:step:Unir clientes y ventas:step2";
    cierto( hallazgo( r, k1 ) != null && hallazgo( r, k2 ) != null, "las dos entradas: " + claves( r ) );
    c = arreglar( tm, "{\"keys\":[\"" + k1 + "\",\"" + k2 + "\"],\"language\":\"es\"}" );
    igual( 2, c.hecho.cambios, c.texto() );
    String x = xml( mj );
    cierto( x.contains( "<step1>Ordenar por " ) && x.contains( "<step2>Ordenar por " ), "step1/step2 repuntados: " + x );
    igual( 0, cuenta( lint( tm, "es" ), "unsorted_input" ), "ordenado" );
    TransMeta otra = guardarYAbrir( tm, "orden_join.ktr" );
    for ( StreamInterface s : otra.findStep( "Unir clientes y ventas" ).getStepMetaInterface().getStepIOMeta()
      .getInfoStreams() ) {
      cierto( s.getStepMeta() != null && "SortRows".equals( s.getStepMeta().getStepID() ), "entrada resuelta al abrir" );
    }

    // Table input justo antes de un Group by: ORDER BY en la consulta en lugar de un Sort rows
    tm = new TransMeta();
    tm.setName( "orden en la consulta" );
    tm.addDatabase( new DatabaseMeta( "dw", "SQLITE", "Native", "", "dw.sqlite", "-1", "", "" ) );
    TransOps.agregarStep( tm, "TableInput", "Leer tabla", "<connection>dw</connection>"
      + "<sql>SELECT producto, importe FROM ventas;</sql>", 100, 100, null, null, null );
    TransOps.agregarStep( tm, "GroupBy", "Totales", "<group><field><name>producto</name></field></group>"
      + "<fields><field><aggregate>total</aggregate><subject>importe</subject><type>SUM</type></field></fields>", null,
      null, "Leer tabla", null, null );
    h = hallazgo( lint( tm, "en" ), "unsorted_input:step:Totales:input" );
    noNulo( h, "Group by detrás de un Table input sin ORDER BY" );
    igual( "ORDER BY producto", h.fix.preview, "propone el ORDER BY" );
    c = arreglar( tm, "{\"keys\":[\"" + h.key + "\"]}" );
    igual( 1, c.hecho.cambios, c.texto() );
    igual( "SELECT producto, importe FROM ventas\nORDER BY producto",
      ( (TableInputMeta) tm.findStep( "Leer tabla" ).getStepMetaInterface() ).getSQL(),
      "ORDER BY al final, sin el punto y coma" );
    igual( 2, tm.nrSteps(), "sin steps nuevos" );
    igual( 0, cuenta( lint( tm, "en" ), "unsorted_input" ), "ordenado por la consulta" );

    // un Table input (copiando) con dos Group by: un solo ORDER BY; el segundo lleva un Sort rows
    tm = new TransMeta();
    tm.setName( "dos agrupaciones" );
    tm.addDatabase( new DatabaseMeta( "dw", "SQLITE", "Native", "", "dw.sqlite", "-1", "", "" ) );
    TransOps.agregarStep( tm, "TableInput", "Leer ventas", "<connection>dw</connection>"
      + "<sql>SELECT producto, cliente, importe FROM ventas</sql>", 100, 100, null, null, null );
    TransOps.agregarStep( tm, "GroupBy", "Total por producto", "<group><field><name>producto</name></field></group>"
      + "<fields><field><aggregate>total</aggregate><subject>importe</subject><type>SUM</type></field></fields>", 300,
      50, "Leer ventas", null, null );
    TransOps.agregarStep( tm, "GroupBy", "Total por cliente", "<group><field><name>cliente</name></field></group>"
      + "<fields><field><aggregate>total</aggregate><subject>importe</subject><type>SUM</type></field></fields>", 300,
      200, "Leer ventas", null, null );
    tm.findStep( "Leer ventas" ).setDistributes( false );
    r = lint( tm, "en" );
    String kp = "unsorted_input:step:Total por producto:input";
    String kc = "unsorted_input:step:Total por cliente:input";
    cierto( hallazgo( r, kp ) != null && hallazgo( r, kc ) != null, "los dos Group by: " + claves( r ) );
    c = arreglar( tm, "{\"keys\":[\"" + kp + "\",\"" + kc + "\"]}" );
    igual( 2, c.hecho.cambios, c.texto() );
    String sql = ( (TableInputMeta) tm.findStep( "Leer ventas" ).getStepMetaInterface() ).getSQL();
    igual( "SELECT producto, cliente, importe FROM ventas\nORDER BY producto", sql, "un solo ORDER BY" );
    StepMeta orden2 = tm.findStep( "Sort by cliente" );
    noNulo( orden2, "Sort rows para el segundo: " + c.texto() );
    cierto( tm.findTransHop( tm.findStep( "Leer ventas" ), orden2 ) != null
      && tm.findTransHop( orden2, tm.findStep( "Total por cliente" ) ) != null, "entre el Table input y el segundo" );
    igual( 0, cuenta( lint( tm, "en" ), "unsorted_input" ), "los dos ordenados" );
  }

  /** Los demás arreglos de transformaciones: copiar, ruta + DIR_DATOS, decimal, campos de salida y contraseña. */
  public void pruebaArreglosDeTransformacion() throws Exception {
    // reparto con dos salidas → copiar (auto+c)
    TransMeta tm = trans( variante( "02", "arr_reparto.ktr", "</order>",
      "<hop><from>Leer clientes</from><to>Descartados</to><enabled>Y</enabled></hop></order>" ) );
    String k = "distribute_multiple_outputs:step:Leer clientes:distribute";
    Hallazgo h = hallazgo( lint( tm, "en" ), k );
    noNulo( h, "reparto: " + claves( lint( tm, "en" ) ) );
    Corrida c = arreglar( tm, "{\"keys\":[\"" + k + "\"]}" );
    igual( 1, c.hecho.cambios, c.texto() );
    falso( tm.findStep( "Leer clientes" ).isDistributes(), "copia" );

    // ruta absoluta → ${DIR_DATOS}/... + parámetro; la segunda carpeta no puede reusar DIR_DATOS
    tm = trans( variante( "03", "arr_ruta.ktr", "${Internal.Entry.Current.Directory}", "C:/Users/alumno/etl" ) );
    Resultado r = lint( tm, "en" );
    String kLeer = "absolute_path:step:Leer ventas:filename";
    String kEscribir = "absolute_path:step:Escribir totales:file/name";
    igual( "${DIR_DATOS}/ventas.csv", hallazgo( r, kLeer ).fix.preview, "propuesta con parámetro" );
    c = arreglar( tm, "{\"keys\":[\"" + kLeer + "\",\"" + kEscribir + "\"]}" );
    igual( Arrays.asList( kLeer ), c.aplicadas(), c.texto() );
    contiene( c.saltada( kEscribir ), "DIR_DATOS is already the folder", "otra carpeta con el mismo parámetro" );
    contiene( xml( tm.findStep( "Leer ventas" ) ), "<filename>${DIR_DATOS}/ventas.csv</filename>", "ruta nueva" );
    igual( "C:/Users/alumno/etl/datos", tm.getParameterDefault( "DIR_DATOS" ), "parámetro con la carpeta" );
    c = arreglar( tm, "{\"values\":{\"" + kEscribir + "\":\"${Internal.Entry.Current.Directory}/salida/totales\"}}" );
    igual( 1, c.hecho.cambios, "con un valor elegido: " + c.texto() );
    igual( 0, cuenta( lint( tm, "en" ), "absolute_path" ), "sin rutas absolutas" );

    // decimal vacío: asistido (falta el valor)
    tm = trans( variante( "03", "arr_decimal.ktr", "<decimal>.</decimal>", "<decimal/>" ) );
    r = lint( tm, "en" );
    String kDec = null;
    for ( Hallazgo x : r.findings ) {
      kDec = x.rule.equals( "empty_decimal_symbol" ) && x.step.equals( "Leer ventas" ) ? x.key : kDec;
    }
    noNulo( kDec, "decimal vacío: " + claves( r ) );
    c = arreglar( tm, "{\"keys\":[\"" + kDec + "\"]}" );
    contiene( c.saltada( kDec ), "needs a value", "sin valor no se aplica" );
    c = arreglar( tm, "{\"values\":{\"" + kDec + "\":\";\"}}" );
    contiene( c.saltada( kDec ), "must be '.' or ','", "solo . o ," );
    c = arreglar( tm, "{\"values\":{\"" + kDec + "\":\".\"}}" );
    igual( 1, c.hecho.cambios, c.texto() );
    noNulo( lint( tm, "en" ), "lint" );
    for ( Hallazgo x : lint( tm, "en" ).findings ) {
      falso( x.key.equals( kDec ), "arreglado: " + x.key );
    }

    // salida sin campos → los que llegan, Strings con largo -1
    tm = EntornoKettle.trans( "03" );
    TransOps.reconfigurar( tm, tm.findStep( "Escribir totales" ), "<fields/>", null );
    k = "text_output_without_fields:step:Escribir totales:fields";
    h = hallazgo( lint( tm, "en" ), k );
    noNulo( h, "salida sin campos: " + claves( lint( tm, "en" ) ) );
    c = arreglar( tm, "{\"keys\":[\"" + k + "\"]}" );
    igual( 1, c.hecho.cambios, c.texto() );
    String x = xml( tm.findStep( "Escribir totales" ) );
    contiene( x, "<name>producto</name>", "campos declarados" );
    contiene( x, "<length>-1</length>", "Strings sin largo" );
    nulo( hallazgo( lint( tm, "en" ), k ), "arreglado" );

    // contraseña en una conexión → solo una variable
    tm = trans( variante( "07", "arr_clave.ktr", "<username/>\n    <password/>",
      "<username>admin</username>\n    <password>secreto123</password>" ) );
    k = "password_in_file:connection:sqlite_demo:password";
    noNulo( hallazgo( lint( tm, "en" ), k ), "contraseña: " + claves( lint( tm, "en" ) ) );
    c = arreglar( tm, "{\"values\":{\"" + k + "\":\"otraClave\"}}" );
    contiene( c.saltada( k ), "must be only a variable", "nunca escribe una clave" );
    c = arreglar( tm, "{\"values\":{\"" + k + "\":\"S3cret!${ENV}\"}}" );
    contiene( c.saltada( k ), "must be only a variable", "una clave con una variable pegada sigue siendo una clave" );
    igual( 0, c.hecho.cambios, "nada aplicado" );
    cierto( ReglasSeguridad.esVariable( "${A}${B}" ) && ReglasSeguridad.esVariable( " %%PASS%% " ),
      "solo variables" );
    falso( ReglasSeguridad.esVariable( "S3cret!${ENV}" ) || ReglasSeguridad.esVariable( "${A} x" ), "texto fijo" );
    c = arreglar( tm, "{\"values\":{\"" + k + "\":\"${SQLITE_PASSWORD}\"}}" );
    igual( 1, c.hecho.cambios, c.texto() );
    cierto( c.hecho.aplicados.get( 0 ).path( "undoable" ).asBoolean(), "en una transformación se deshace" );
    igual( "${SQLITE_PASSWORD}", tm.findDatabase( "sqlite_demo" ).getPassword(), "variable" );
    noContiene( tm.getXML(), "secreto123", "la clave ya no está en el XML" );
    nulo( hallazgo( lint( tm, "en" ), k ), "arreglado" );
    tm.findDatabase( "sqlite_demo" ).setPassword( "clave${SUFIJO}" );
    Hallazgo mezcla = hallazgo( lint( tm, "en" ), k );
    noNulo( mezcla, "texto con una variable: sigue contando como contraseña guardada" );
    contiene( mezcla.message, "connection 'sqlite_demo'", "en inglés el nombre va entre comillas simples" );
    contiene( hallazgo( lint( tm, "es" ), k ).message, "conexión «sqlite_demo»", "en castellano, con «»" );

    // dry_run: sobre una copia, el documento no cambia
    tm = trans( variante( "03", "arr_ensayo.ktr", "Ordenar por producto", "Sort rows" ) );
    int huella = LintArreglos.huella( tm );
    AbstractMeta copia = ReportTools.clonar( tm );
    LintArreglos.Pedido p = LintArreglos.pedido( McpHttpServer.JSON.readTree( "{\"keys\":[\"all_auto\"],\"dry_run\":true}" ) );
    Opciones op = new Opciones();
    op.sinGuardar = true;
    op.entorno = new LintEntorno( copia );
    LintArreglos.Analisis an = LintArreglos.analizar( copia, op, p );
    LintArreglos.Hecho hecho = LintArreglos.aplicar( copia, an, p.lang, UndoGrupo.sinSpoon( copia ), null, null );
    igual( 1, hecho.cambios, "en la copia" );
    igual( huella, LintArreglos.huella( tm ), "el documento no cambió" );
    ObjectNode resp = LintArreglos.respuesta( "T1", an, hecho, lint( copia, "en" ), true, false, true );
    cierto( resp.path( "dry_run" ).asBoolean() && resp.path( "score_after" ).asInt() >= resp.path( "score_before" ).asInt(),
      "respuesta del ensayo: " + resp );
    contiene( resp.path( "notes" ).toString(), "Dry run", "lo dice" );
  }

  /** Jobs: START renombrado (auto) y rama roja a Abort (auto+c); un Ctrl+Z por acción. */
  public void pruebaArreglosDeJob() throws Exception {
    JobMeta jm = job( variante( "10", "arr_start.kjb", "<name>START</name>", "<name>Inicio</name>", "<from>START</from>",
      "<from>Inicio</from>" ) );
    String k = "start_not_named_start:entry:Inicio:name";
    noNulo( hallazgo( lint( jm, "en" ), k ), "START con otro nombre: " + claves( lint( jm, "en" ) ) );
    Corrida c = arreglar( jm, "{\"keys\":[\"all_auto\"]}" );
    cierto( c.aplicadas().contains( k ), "all_auto lo renombra: " + c.texto() );
    noNulo( jm.findJobEntry( "START", 0, false ), "Kettle encuentra el START" );
    igual( c.undo.acciones(), c.hecho.pasosDeshacer, "en un job, un Ctrl+Z por acción" );

    jm = job( variante( "10", "arr_rama.kjb", "<from>Ventas por producto</from>\n      <to>Abortar</to>",
      "<from>Ventas por producto</from>\n      <to>Success</to>" ) );
    k = "failure_branch_ends_in_success:hop:Ventas por producto -> Success:target";
    Hallazgo h = hallazgo( lint( jm, "en" ), k );
    noNulo( h, "rama roja a Success: " + claves( lint( jm, "en" ) ) );
    cierto( h.fix.confirm, "cambiar el destino pide confirmación" );
    c = arreglar( jm, "{\"keys\":[\"" + k + "\"]}" );
    igual( 1, c.hecho.cambios, c.texto() );
    contiene( c.hecho.aplicados.get( 0 ).path( "change" ).asText(), "'Abortar'", "usa el Abort que ya había" );
    igual( 1, c.hecho.pasosDeshacer, "un cambio de hop" );
    nulo( hallazgo( lint( jm, "en" ), k ), "arreglado" );
    JobMeta otro = guardarYAbrir( jm, "arr_rama_guardado.kjb" );
    igual( 0, cuenta( lint( otro, "en" ), "failure_branch_ends_in_success" ), "tras guardar y abrir" );
  }
}
