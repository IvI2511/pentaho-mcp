package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * mapping_import más allá de copia / constante / marcador (MapeoReglas): String operations, Replace in string,
 * Calculator (cuentas, redondeo y partes de fecha), Value mapper, Select values, If field value is null, Stream
 * lookup, Database lookup y Filter rows con rechazos, a partir de reglas en español, inglés y portugués; lo que no se
 * entiende queda como marcador con el porqué. Se aplica en un solo Ctrl+Z y la cadena se ejecuta de verdad.
 */
public class PruebaMapeoReglas {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /** Fuente (Generate rows) → Destino (Dummy), y un step suelto «segmentos» que lee una tabla de segmentos. */
  static TransMeta base( boolean conBase ) throws Exception {
    TransMeta tm = new TransMeta();
    tm.setName( "mapeo_reglas" );
    String f = "<limit>4</limit><fields>"
      + campo( "producto", "String", "", " café latte " ) + campo( "precio", "Number", "", "2.25" )
      + campo( "cantidad", "Integer", "", "3" ) + campo( "ciudad", "String", "", "Madrid" )
      + campo( "fecha", "Date", "yyyy-MM-dd", "2026-09-15" ) + campo( "cliente_id", "Integer", "", "7" )
      + "</fields>";
    TransOps.agregarStep( tm, "RowGenerator", "Fuente", f, 100, 100, null, null, null );
    TransOps.agregarStep( tm, "Dummy", "Destino", null, 600, 100, "Fuente", null, null );
    String seg = "<limit>1</limit><fields>" + campo( "cliente_id", "Integer", "", "7" ) + campo( "segmento", "String",
      "", "ORO" ) + "</fields>";
    TransOps.agregarStep( tm, "RowGenerator", "segmentos", seg, 100, 300, null, null, null );
    if ( conBase ) {
      DatabaseMeta db = new DatabaseMeta( "dw", "SQLITE", "Native", null, new File( EntornoKettle.salida( "mapeo-reglas" ), "no_existe.sqlite" ).getAbsolutePath(), "-1", null, null );
      tm.addDatabase( db );
    }
    return tm;
  }

  private static String campo( String n, String t, String fmt, String v ) {
    return "<field><name>" + n + "</name><type>" + t + "</type><format>" + fmt + "</format><decimal>.</decimal><group>,</group><nullif>" + v
      + "</nullif><length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field>";
  }

  private static List<MapeoImportar.FilaHoja> hoja( String csv ) throws Exception {
    File f = new File( EntornoKettle.salida( "mapeo-reglas" ), "hoja_" + Math.abs( csv.hashCode() ) + ".csv" );
    Files.write( f.toPath(), csv.getBytes( StandardCharsets.UTF_8 ) );
    return MapeoImportar.leer( f );
  }

  private static List<MapeoImportar.Veredicto> comparar( TransMeta tm, List<MapeoImportar.FilaHoja> h, String idioma ) {
    List<Mapeo.Fila> etl = Mapeo.filas( tm, Mapeo.contexto( tm, idioma ), null );
    return MapeoImportar.comparar( tm, Mapeo.contexto( tm, idioma ), h, etl, Rotulos.de( PlanoInforme.AREA, idioma ) );
  }

  private static MapeoImportar.Veredicto fila( List<MapeoImportar.Veredicto> vs, String campo ) {
    for ( MapeoImportar.Veredicto v : vs ) {
      if ( campo.equals( v.campo ) ) {
        return v;
      }
    }
    return null;
  }

  private static final String CABECERA = "Origen;Destino;Tipo;Regla;Por defecto;Validación;Estado\n";

  public void pruebaPropuestasEnEspanol() throws Exception {
    TransMeta tm = base( true );
    List<MapeoImportar.Veredicto> vs = comparar( tm, hoja( CABECERA
      + "Fuente.producto;Destino.producto_norm;String;sin espacios y en mayúsculas;;;\n"
      + "Fuente.producto;Destino.producto_slug;String;reemplazar ' ' por '-';;;\n"
      + ";Destino.importe;Number;importe = precio × cantidad, redondeado a 2 decimales;;> 0;\n"
      + ";Destino.anio;Integer;año de fecha;;;\n"
      + "Fuente.ciudad;Destino.ciudad_cod;String;\"Madrid → MAD, Barcelona → BCN; si no, OTRA\";;uno de MAD, BCN;\n"
      + "Fuente.cantidad;Destino.cantidad_txt;String;convertir a texto;;;\n"
      + "Fuente.ciudad;Destino.ciudad_def;String;;SIN CIUDAD;no nulo;\n"
      + "segmentos.segmento;Destino.segmento;String;buscar en segmentos por cliente_id;BRONCE;;\n"
      + "dim_canal.nombre_canal;Destino.canal;String;buscar en dim_canal por producto = codigo;;;\n"
      + ";Destino.estrategia;String;según la estrategia comercial del trimestre;;;\n"
      + "Fuente.producto;Destino.producto_raro;String;mayúsculas excepto los acrónimos;;;\n" ), "es" );
    igual( "string_operations", fila( vs, "producto_norm" ).accion, "trim + mayúsculas" );
    igual( "replace_string", fila( vs, "producto_slug" ).accion, "reemplazo" );
    igual( "calculator", fila( vs, "importe" ).accion, "cuenta con redondeo: " + tipos( fila( vs, "importe" ) ) );
    cierto( fila( vs, "importe" ).plan.acciones.size() == 2, "y la validación > 0: " + tipos( fila( vs, "importe" ) ) );
    igual( "calculator", fila( vs, "anio" ).accion, "año de una fecha" );
    igual( "value_mapper", fila( vs, "ciudad_cod" ).accion, "pares de valores" );
    igual( "rename", fila( vs, "cantidad_txt" ).accion, "copia + cambio de tipo" );
    contiene( tipos( fila( vs, "cantidad_txt" ) ), "select_values", "Select values: " + tipos( fila( vs,
      "cantidad_txt" ) ) );
    contiene( tipos( fila( vs, "ciudad_def" ) ), "if_null", "origen + por defecto: If field value is null" );
    contiene( tipos( fila( vs, "ciudad_def" ) ), "filter_rows", "y la validación no nulo" );
    igual( "stream_lookup", fila( vs, "segmento" ).accion, "el step suelto que lee la tabla" );
    igual( "database_lookup", fila( vs, "canal" ).accion, "otra tabla: con la conexión" );
    igual( "placeholder", fila( vs, "estrategia" ).accion, "regla en lenguaje natural" );
    igual( "placeholder", fila( vs, "producto_raro" ).accion, "a medias: no se adivina" );
    contiene( String.join( " ", fila( vs, "producto_raro" ).plan.dudas ), "excepto acronimos", "dice qué no entiende" );
    ObjectNode j = MapeoImportar.json( vs, Mapeo.filas( tm, Mapeo.contexto( tm, "es" ), null ), null );
    JsonNode raro = null;
    for ( JsonNode x : j.path( "rows" ) ) {
      if ( x.path( "target" ).asText().endsWith( "producto_raro" ) ) {
        raro = x;
      }
    }
    noNulo( raro, "fila en el JSON" );
    contiene( raro.path( "why" ).asText(), "no se entiende", "el porqué en el JSON: " + raro );
    // aplicar: un Ctrl+Z
    int steps = tm.nrSteps();
    int hops = tm.nrTransHops();
    int notas = tm.nrNotes();
    List<MapeoImportar.Insercion> hecho = MapeoImportar.aplicar( tm, vs, "es", UndoGrupo.sinSpoon( tm ), null );
    igual( 1, hecho.size(), "delante del destino" );
    List<String> ids = new ArrayList<String>();
    for ( String n : hecho.get( 0 ).steps ) {
      ids.add( tm.findStep( n ).getStepID() );
    }
    igual( "[StreamLookup, DBLookup, Calculator, StringOperations, ReplaceString, ValueMapper, SelectValues, IfNull, "
      + "Dummy, FilterRows, Dummy]", ids.toString(), "la cadena en orden: " + hecho.get( 0 ).steps );
    StepMeta destino = tm.findStep( "Destino" );
    RowMetaInterface llega = tm.getPrevStepFields( destino );
    for ( String c : new String[] { "producto_norm", "producto_slug", "importe", "anio", "ciudad_cod", "cantidad_txt",
      "ciudad_def", "segmento", "canal" } ) {
      noNulo( llega.searchValueMeta( c ), "llega " + c );
    }
    igual( "Number", llega.searchValueMeta( "importe" ).getTypeDesc(), "importe Number" );
    igual( "String", llega.searchValueMeta( "cantidad_txt" ).getTypeDesc(), "cantidad_txt String" );
    nulo( llega.searchValueMeta( "__importe_c0" ), "los temporales de Calculator se quitan" );
    noNulo( tm.findTransHop( tm.findStep( "segmentos" ), tm.findStep( "Buscar en segmentos para Destino" ) ),
      "el origen del Stream lookup le manda sus filas" );
    String filtro = tm.findStep( "Validar filas para Destino" ).getXML();
    contiene( filtro, "<send_false_to>Rechazos de Destino</send_false_to>", "las malas a rechazos" );
    contiene( filtro, "IN LIST", "uno de MAD, BCN" );
    contiene( filtro, "IS NOT NULL", "no nulo" );
    contiene( PruebaPlanoConstruir.notaDe( tm, "Completar mapeo de Destino" ).getNote(), "estrategia", "la nota lista lo "
      + "que hay que programar" );
    PruebaPlanoConstruir.deshacerUno( tm );
    igual( steps, tm.nrSteps(), "un Ctrl+Z quita los steps" );
    igual( hops, tm.nrTransHops(), "y los hops" );
    igual( notas, tm.nrNotes(), "y las notas" );
    noNulo( tm.findTransHop( tm.findStep( "Fuente" ), destino ), "el hop original vuelve" );
  }

  public void pruebaInglesYPortugues() throws Exception {
    TransMeta tm = base( false );
    List<MapeoImportar.Veredicto> vs = comparar( tm, hoja( "Source,Target,Type,Rule,Default,Validation\n"
      + "Fuente.producto,Destino.p_up,String,trim and upper case,,\n"
      + "Fuente.ciudad,Destino.c_low,String,minúsculas sem espaços,,\n"
      + ",Destino.total,Number,precio * cantidad + 1,,between 1 and 100\n"
      + ",Destino.mes,Integer,month of fecha,,\n"
      + ",Destino.trimestre,Integer,trimestre da fecha,,\n"
      + "Fuente.producto,Destino.p_cap,String,initcap,,\n"
      + "x.y,Destino.canal,String,look up in dim_canal by producto,,\n"
      + "Fuente.ciudad,Destino.ciudad2,String,copy as is,,must be one of Madrid / Bilbao\n" ), "en" );
    igual( "string_operations", fila( vs, "p_up" ).accion, "en: trim and upper" );
    igual( "string_operations", fila( vs, "c_low" ).accion, "pt: minúsculas sem espaços" );
    igual( "calculator", fila( vs, "total" ).accion, "a * b + c: " + tipos( fila( vs, "total" ) ) );
    contiene( tipos( fila( vs, "total" ) ), "filter_rows", "between" );
    igual( "calculator", fila( vs, "mes" ).accion, "month of" );
    igual( "calculator", fila( vs, "trimestre" ).accion, "trimestre da" );
    igual( "string_operations", fila( vs, "p_cap" ).accion, "initcap" );
    igual( "placeholder", fila( vs, "canal" ).accion, "sin conexión: marcador" );
    contiene( String.join( " ", fila( vs, "canal" ).plan.dudas ), "no connection", "lo dice" );
    igual( "rename", fila( vs, "ciudad2" ).accion, "copy" );
    contiene( tipos( fila( vs, "ciudad2" ) ), "filter_rows", "one of" );
  }

  /** La cadena aplicada se ejecuta: los valores salen como dice la hoja y las filas malas van a rechazos. */
  public void pruebaSeEjecuta() throws Exception {
    Object[] r100 = correr( "> 100" );
    igual( 0, ( (List<?>) r100[ 0 ] ).size(), "2.25 × 3 = 6.75 no pasa «> 100»" );
    igual( 4, r100[ 2 ], "las 4 filas van a rechazos" );
    Object[] r1 = correr( "> 1" );
    @SuppressWarnings( "unchecked" )
    List<Object[]> buenas = (List<Object[]>) r1[ 0 ];
    igual( 4, buenas.size(), "con «> 1» pasan las 4" );
    igual( 0, r1[ 2 ], "ninguna a rechazos" );
    RowMetaInterface rm = (RowMetaInterface) r1[ 1 ];
    Object[] r = buenas.get( 0 );
    igual( "CAFÉ LATTE", rm.getString( r, "producto_norm", null ), "trim + mayúsculas" );
    igual( "cafélatte", rm.getString( r, "producto_slug", null ), "sin espacios" );
    igual( 6.75, rm.getNumber( r, rm.indexOfValue( "importe" ) ), "cuenta redondeada a 2 decimales" );
    igual( 2026L, rm.getInteger( r, rm.indexOfValue( "anio" ) ), "año de la fecha" );
    igual( "MAD", rm.getString( r, "ciudad_cod", null ), "Value mapper" );
    igual( "ORO", rm.getString( r, "segmento", null ), "Stream lookup" );
  }

  /** Aplica la hoja con esa validación del importe, guarda, relee y ejecuta: {filas buenas, su RowMeta, malas}. */
  private static Object[] correr( String validacion ) throws Exception {
    TransMeta tm = base( false );
    List<MapeoImportar.Veredicto> vs = comparar( tm, hoja( CABECERA
      + "Fuente.producto;Destino.producto_norm;String;sin espacios y en mayúsculas;;;\n"
      + "Fuente.producto;Destino.producto_slug;String;quitar ' ';;;\n"
      + ";Destino.importe;Number;importe = precio × cantidad, redondeado a 2 decimales;;" + validacion + ";\n"
      + ";Destino.anio;Integer;año de fecha;;;\n"
      + "Fuente.ciudad;Destino.ciudad_cod;String;\"Madrid → MAD, Barcelona → BCN; si no, OTRA\";;;\n"
      + "segmentos.segmento;Destino.segmento;String;buscar en segmentos por cliente_id;BRONCE;;\n" ), "es" );
    MapeoImportar.aplicar( tm, vs, "es", UndoGrupo.sinSpoon( tm ), null );
    File ktr = new File( EntornoKettle.salida( "mapeo-reglas" ), "aplicada_" + Math.abs( validacion.hashCode() )
      + ".ktr" );
    Files.write( ktr.toPath(), tm.getXML().getBytes( StandardCharsets.UTF_8 ) );
    TransMeta leida = new TransMeta( ktr.getAbsolutePath() );
    Trans t = new Trans( leida );
    t.setLogLevel( LogLevel.MINIMAL );
    t.prepareExecution( null );
    final List<Object[]> buenas = new ArrayList<Object[]>();
    final RowMetaInterface[] meta = new RowMetaInterface[ 1 ];
    final int[] malas = new int[ 1 ];
    StepInterface d = t.findRunThread( "Destino" );
    d.addRowListener( new RowAdapter() {
      @Override
      public void rowWrittenEvent( RowMetaInterface rm, Object[] row ) throws KettleStepException {
        meta[ 0 ] = rm;
        buenas.add( row );
      }
    } );
    t.findRunThread( "Rechazos de Destino" ).addRowListener( new RowAdapter() {
      @Override
      public void rowWrittenEvent( RowMetaInterface rm, Object[] row ) {
        malas[ 0 ]++;
      }
    } );
    t.startThreads();
    t.waitUntilFinished();
    igual( 0, t.getErrors(), "la cadena corre sin errores (" + validacion + ")" );
    return new Object[] { buenas, meta[ 0 ], malas[ 0 ] };
  }

  private static String tipos( MapeoImportar.Veredicto v ) {
    List<String> l = new ArrayList<String>();
    if ( v.plan != null ) {
      for ( MapeoReglas.Accion a : v.plan.acciones ) {
        l.add( a.tipo );
      }
    }
    return l.toString() + ( v.plan == null ? "" : " dudas=" + v.plan.dudas );
  }
}
