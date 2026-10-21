package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Linaje de los steps con varias entradas y de las constantes de Calculator, sobre una transformacion
 * armada aca (sin bases ni archivos: DataGrid y Generate rows):
 * <ul>
 * <li>Append, Merge rows (diff) y Multiway merge join reciben sus filas por streams de info: el linaje
 * traza TODAS las entradas (antes cortaba en el step: «cod_cliente (creado)»);</li>
 * <li>un Dummy con dos hops normales tambien trae el campo de los dos;</li>
 * <li>un campo que solo cambia de mayuscula («Fecha» -&gt; «fecha») sigue hasta su origen: Kettle no
 * distingue mayusculas y el camino se cortaba con un origen desconocido;</li>
 * <li>las constantes de Calculator son valores, no campos (antes «1 (?)»), y la operacion es la cuenta
 * entera: ADD(DIVIDE(SUBTRACT(mes, 1), 4), 1);</li>
 * <li>si metaverse no puede con un campo (justo los de constantes de Calculator: NoSuchElementException),
 * el resto sigue con metaverse y el informe no muestra la excepcion: solo el motor y los campos.</li>
 * </ul>
 *
 * @dueño WP3
 */
public class PruebaLinajeEntradas {

  private TransMeta tm;
  private Captura captura;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    File dir = new File( EntornoKettle.carpeta(), "linaje-entradas" );
    dir.mkdirs();
    File ktr = new File( dir, "entradas.ktr" );
    EntornoKettle.escribir( ktr, ktr() );
    tm = new TransMeta( ktr.getAbsolutePath() );
    captura = new Captura();
    captura.copia = tm;
    captura.docId = "prueba:linaje-entradas";
    captura.nombre = tm.getName();
    captura.archivo = ktr.getAbsolutePath();
    captura.tipo = Captura.TRANSFORMACION;
    captura.capturado = new java.util.Date();
  }

  // ---------------------------------------------------------------- motor propio

  public void pruebaAppendTrazaLasDosEntradas() throws Exception {
    LinajePropio p = propio();
    LinajePropio.Destino d = p.destino( tm.findStep( "Salida ventas" ) );
    nulo( d.motivo, "sin motivo" );
    igual( "[Ventas A:cod:source, Ventas B:cod:source]", origenes( d.campo( "cod" ) ).toString(),
      "cod: las dos entradas del Append" );
    igual( "[Ventas A:importe:source, Ventas B:importe:source]", origenes( d.campo( "importe" ) ).toString(),
      "importe: las dos entradas" );
    for ( LinajePropio.Campo c : d.campos ) {
      for ( List<LinajePropio.Nodo> camino : c.caminos ) {
        for ( LinajePropio.Nodo n : camino ) {
          cierto( !( "Unir".equals( n.step ) && LinajePropio.CREA.equals( n.clase ) ), c.nombre
            + ": el Append no «crea» campos" );
        }
      }
    }
  }

  public void pruebaConstantesDeCalculator() throws Exception {
    LinajePropio.Destino d = propio().destino( tm.findStep( "Salida ventas" ) );
    LinajePropio.Campo c = d.campo( "cuatrimestre" );
    noNulo( c, "cuatrimestre" );
    igual( "[Ventas A:fecha:source, Ventas B:Fecha:source]", origenes( c ).toString(),
      "cuatrimestre sale de la fecha (las constantes no son campos), tambien la «Fecha» de Ventas B" );
    contiene( firmas( c ).toString(), "Minusculas:fecha:rename:Fecha → fecha", "el cambio de mayuscula es un renombre" );
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      for ( LinajePropio.Nodo n : camino ) {
        cierto( !LinajePropio.DESCONOCIDO.equals( n.clase ), "ningun nodo desconocido: " + n.firma() );
        cierto( !n.campo.matches( "\\d+" ), "ninguna constante como campo: " + n.firma() );
      }
      LinajePropio.Nodo calc = null;
      for ( LinajePropio.Nodo n : camino ) {
        calc = "Calcular".equals( n.step ) ? n : calc;
      }
      noNulo( calc, "el camino pasa por Calcular" );
      igual( LinajePropio.CALCULA, calc.clase, "calculado en Calcular" );
      igual( "ADD(DIVIDE(SUBTRACT(mes, 1), 4), 1)", calc.op, "la cuenta entera" );
    }
    // una constante que queda en la salida nace en el Calculator con su valor
    LinajePropio.Campo k = d.campo( "version" );
    igual( 1, k.caminos.size(), "version: un camino" );
    igual( "Calcular:version:created:constante «2»:", k.caminos.get( 0 ).get( 0 ).firma(), "version: constante" );
    Tabla t = tabla( d );
    for ( List<String> f : t.filas ) {
      noContiene( f.get( 2 ), "(?)", f.get( 0 ) + ": sin origen desconocido" );
    }
  }

  public void pruebaMergeRowsMultiwayYDosHops() throws Exception {
    LinajePropio p = propio();
    LinajePropio.Destino dif = p.destino( tm.findStep( "Salida diferencias" ) );
    nulo( dif.motivo, "Merge rows sin motivo" );
    igual( "[Nueva:importe:source, Referencia:importe:source]", origenes( dif.campo( "importe" ) ).toString(),
      "Merge rows: referencia y comparacion" );
    igual( "[Diferencias:flag:created]", origenes( dif.campo( "flag" ) ).toString(), "el flag lo crea Merge rows" );

    LinajePropio.Destino cli = p.destino( tm.findStep( "Salida clientes" ) );
    nulo( cli.motivo, "Multiway merge join sin motivo" );
    igual( "[Clientes 1:nombre:source]", origenes( cli.campo( "nombre" ) ).toString(), "nombre de la primera entrada" );
    igual( "[Clientes 2:ciudad:source]", origenes( cli.campo( "ciudad" ) ).toString(), "ciudad de la segunda" );
    String camino = firmas( cli.campo( "ciudad" ) ).toString();
    contiene( camino, "Juntar clientes:ciudad:join:unión INNER por cod = cod2", "la union con sus claves" );

    LinajePropio.Destino dos = p.destino( tm.findStep( "Dos hops" ) );
    igual( "[Ventas A:cod:source, Ventas B:cod:source]", origenes( dos.campo( "cod" ) ).toString(),
      "un step con dos hops normales trae el campo de los dos" );
  }

  /**
   * metaverse a veces pierde uno de los caminos que pasan por un Append (con una practica de un DW, de
   * una corrida a otra faltaba el del tail): los del motor propio que faltan se suman.
   */
  public void pruebaCombinarCompletaLoQueMetaversePierde() throws Exception {
    LinajePropio p = propio();
    org.pentaho.di.trans.step.StepMeta d = tm.findStep( "Salida ventas" );
    LinajeMetaverse.Respuesta mv = new LinajeMetaverse.Respuesta();
    for ( LinajePropio.Campo c : p.destino( d ).campos ) {
      mv.caminos.put( c.nombre, new ArrayList<>( c.caminos ) );
    }
    // a cod le falta la entrada del tail del Append
    List<List<LinajePropio.Nodo>> cod = mv.caminos.get( "cod" );
    igual( 2, cod.size(), "el propio da los dos caminos de cod" );
    cod.remove( "Ventas A".equals( cod.get( 0 ).get( 0 ).step ) ? 1 : 0 );
    LinajePropio.Destino r = SecLinaje.combinar( p, d, mv, ReportMotor.ordenTrans( tm ) );
    igual( "[Ventas A:cod:source, Ventas B:cod:source]", origenes( r.campo( "cod" ) ).toString(),
      "el camino que metaverse perdio vuelve" );
    igual( SecLinaje.MOTOR_MIXTO, r.campo( "cod" ).motor, "cod: de los dos motores" );
    igual( LinajeMetaverse.MOTOR, r.campo( "importe" ).motor, "importe: metaverse tenia todo" );
    igual( SecLinaje.MOTOR_MIXTO, r.motor, "el destino: mixto" );
    igual( 2, r.campo( "importe" ).caminos.size(), "sin caminos repetidos" );
  }

  // ---------------------------------------------------------------- la seccion (metaverse + respaldo)

  public void pruebaSeccionSinExcepciones() throws Exception {
    Pedido ped = new Pedido();
    ped.idioma = "es";
    Seccion s = new SecLinaje().armar( new ContextoPrueba( captura, ped, new Tema() ) );
    EntornoKettle.escribir( new File( EntornoKettle.salida( "linaje" ), "entradas-es.json" ), ModeloJson.escribir( s,
      true ) );
    igual( Seccion.OK, s.estado, "estado (" + s.motivo + ")" );
    // primero, para que sirve
    Bloque primero = s.bloques.get( 0 );
    cierto( primero instanceof Parrafo, "abre con un parrafo" );
    contiene( ( (Parrafo) primero ).texto, "Para qué sirve", "que explica para que sirve" );
    contiene( ( (Parrafo) primero ).texto, "análisis de impacto", "impacto" );
    // ningun texto del informe con la excepcion de metaverse
    String todo = textos( s );
    for ( String x : new String[] { "Exception", "NoSuchElement", "falló (", "java." } ) {
      noContiene( todo, x, "el informe no muestra detalles tecnicos" );
    }
    // Salida ventas: cuatrimestre lo traza el motor propio y la nota lo dice; las dos entradas del Append
    Tabla ventas = tablaDe( s, "Salida ventas" );
    noNulo( ventas, "tabla de Salida ventas" );
    String viene = fila( ventas, "cod" ).get( 2 );
    contiene( viene, "Ventas A", "cod viene de Ventas A" );
    contiene( viene, "Ventas B", "y de Ventas B" );
    noContiene( fila( ventas, "cuatrimestre" ).get( 2 ), "(?)", "cuatrimestre sin desconocidos" );
    contiene( fila( ventas, "cuatrimestre" ).get( 3 ), "ADD(DIVIDE(SUBTRACT(mes, 1), 4), 1)", "la cuenta" );
    JsonNode ts = s.datos.path( "targets" );
    for ( JsonNode t : ts ) {
      if ( "Salida ventas".equals( t.path( "step" ).asText() ) && "mixed".equals( t.path( "engine" ).asText() ) ) {
        contiene( ventas.nota, "metaverse no pudo trazar", "la nota dice que campos" );
        contiene( ventas.nota, "cuatrimestre", "y nombra el campo" );
      }
    }
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla && b.nota != null ) {
        contiene( b.nota, "Motor: ", "cada tabla dice el motor" );
      }
    }
    // en ingles tambien
    Pedido en = new Pedido();
    en.idioma = "en";
    Seccion se = new SecLinaje().armar( new ContextoPrueba( captura, en, new Tema() ) );
    contiene( ( (Parrafo) se.bloques.get( 0 ) ).texto, "What it is for", "explicacion en ingles" );
    noContiene( textos( se ), "Exception", "sin excepciones en ingles" );
  }

  // ---------------------------------------------------------------- utilidades

  private LinajePropio propio() throws Exception {
    Pedido ped = new Pedido();
    ped.idioma = "es";
    return new LinajePropio( tm, new ContextoPrueba( captura, ped, new Tema() ) );
  }

  /** Las filas de la tabla de la seccion (campo, tipo, viene de, transformaciones). */
  private static Tabla tabla( LinajePropio.Destino d ) {
    Rotulos r = Rotulos.de( SecLinaje.AREA, "es" );
    Tabla t = new Tabla( "campo", "tipo", "origen", "ops" );
    for ( LinajePropio.Campo c : d.campos ) {
      t.fila( c.nombre, c.tipo, SecLinaje.origenes( c, r ), SecLinaje.operaciones( c, r ) );
    }
    return t;
  }

  private static Tabla tablaDe( Seccion s, String step ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla && b.titulo != null && b.titulo.contains( "«" + step + "»" ) ) {
        return (Tabla) b;
      }
    }
    return null;
  }

  private static List<String> fila( Tabla t, String campo ) {
    for ( List<String> f : t.filas ) {
      if ( campo.equals( f.get( 0 ) ) ) {
        return f;
      }
    }
    throw new AssertionError( "no hay fila " + campo + " en " + t.titulo );
  }

  /** Todo el texto visible de la seccion (resumen, motivo, bloques, notas y celdas). */
  private static String textos( Seccion s ) throws Exception {
    StringBuilder sb = new StringBuilder( String.valueOf( s.resumen ) ).append( '\n' ).append( s.motivo );
    for ( Bloque b : s.bloques ) {
      String json = ModeloJson.escribir( b, false );
      sb.append( '\n' ).append( json );
    }
    return sb.toString();
  }

  /** "step:campo:clase" del primer nodo de cada camino, sin repetir, ordenados. */
  private static List<String> origenes( LinajePropio.Campo c ) {
    List<String> l = new ArrayList<>();
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      LinajePropio.Nodo n = camino.get( 0 );
      String o = n.step + ":" + n.campo + ":" + n.clase;
      if ( !l.contains( o ) ) {
        l.add( o );
      }
    }
    Collections.sort( l );
    return l;
  }

  private static List<String> firmas( LinajePropio.Campo c ) {
    List<String> l = new ArrayList<>();
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      StringBuilder sb = new StringBuilder();
      for ( LinajePropio.Nodo n : camino ) {
        sb.append( sb.length() == 0 ? "" : " > " ).append( n.firma() );
      }
      l.add( sb.toString() );
    }
    Collections.sort( l );
    return l;
  }

  // ---------------------------------------------------------------- la transformacion

  /**
   * Ventas A + Ventas B -&gt; Minusculas -&gt; Unir (Append) -&gt; Calcular (Calculator con constantes) -&gt; Salida ventas;
   * Referencia + Nueva -&gt; Diferencias (Merge rows) -&gt; Salida diferencias; Clientes 1 + Clientes 2 -&gt;
   * Juntar clientes (Multiway merge join) -&gt; Salida clientes; Ventas A y Ventas B -&gt; Dos hops (Dummy).
   */
  static String ktr() {
    StringBuilder s = new StringBuilder();
    s.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<transformation>\n<info><name>entradas</name>"
      + "<trans_type>Normal</trans_type><directory>/</directory></info>\n<order>\n" );
    String[][] hops = { { "Ventas A", "Unir" }, { "Ventas B", "Minusculas" }, { "Minusculas", "Unir" },
      { "Unir", "Calcular" },
      { "Calcular", "Salida ventas" }, { "Referencia", "Diferencias" }, { "Nueva", "Diferencias" },
      { "Diferencias", "Salida diferencias" }, { "Clientes 1", "Juntar clientes" }, { "Clientes 2", "Juntar clientes" },
      { "Juntar clientes", "Salida clientes" }, { "Ventas A", "Dos hops" }, { "Ventas B", "Dos hops" } };
    for ( String[] h : hops ) {
      s.append( "<hop><from>" ).append( h[ 0 ] ).append( "</from><to>" ).append( h[ 1 ] ).append(
        "</to><enabled>Y</enabled></hop>\n" );
    }
    s.append( "</order>\n" );
    String ventas = campo( "cod", "String" ) + campo( "fecha", "Date" ) + campo( "importe", "Number" );
    s.append( paso( "Ventas A", "DataGrid", "<fields>" + ventas + "</fields><data/>" ) );
    // Ventas B trae «Fecha» y un Select values solo le cambia la mayuscula (Kettle no lo cuenta como renombre)
    s.append( paso( "Ventas B", "DataGrid", "<fields>" + campo( "cod", "String" ) + campo( "Fecha", "Date" ) + campo(
      "importe", "Number" ) + "</fields><data/>" ) );
    s.append( paso( "Minusculas", "SelectValues", "<fields><field><name>cod</name></field><field><name>Fecha</name>"
      + "<rename>fecha</rename></field><field><name>importe</name></field><select_unspecified>N</select_unspecified>"
      + "</fields>" ) );
    s.append( paso( "Unir", "Append", "<head_name>Ventas A</head_name><tail_name>Minusculas</tail_name>" ) );
    s.append( paso( "Calcular", "Calculator", calculo( "mes", "MONTH_OF_DATE", "fecha", "", false )
      + calculo( "c1", "CONSTANT", "1", "", true ) + calculo( "c4", "CONSTANT", "4", "", true )
      + calculo( "mes_menos_1", "SUBTRACT", "mes", "c1", true ) + calculo( "cociente", "DIVIDE", "mes_menos_1", "c4",
        true ) + calculo( "cuatrimestre", "ADD", "cociente", "c1", false )
      + calculo( "version", "CONSTANT", "2", "", false ) ) );
    s.append( paso( "Salida ventas", "Dummy", "" ) );
    String ref = campo( "cod", "String" ) + campo( "importe", "Number" );
    s.append( paso( "Referencia", "DataGrid", "<fields>" + ref + "</fields><data/>" ) );
    s.append( paso( "Nueva", "DataGrid", "<fields>" + ref + "</fields><data/>" ) );
    s.append( paso( "Diferencias", "MergeRows", "<keys><key>cod</key></keys><values><value>importe</value></values>"
      + "<flag_field>flag</flag_field><reference>Referencia</reference><compare>Nueva</compare>" ) );
    s.append( paso( "Salida diferencias", "Dummy", "" ) );
    s.append( paso( "Clientes 1", "DataGrid", "<fields>" + campo( "cod", "String" ) + campo( "nombre", "String" )
      + "</fields><data/>" ) );
    s.append( paso( "Clientes 2", "DataGrid", "<fields>" + campo( "cod2", "String" ) + campo( "ciudad", "String" )
      + "</fields><data/>" ) );
    s.append( paso( "Juntar clientes", "MultiwayMergeJoin", "<join_type>INNER</join_type><step0>Clientes 1</step0>"
      + "<step1>Clientes 2</step1><number_input>2</number_input><keys><key>cod</key><key>cod2</key></keys>" ) );
    s.append( paso( "Salida clientes", "Dummy", "" ) );
    s.append( paso( "Dos hops", "Dummy", "" ) );
    s.append( "</transformation>\n" );
    return s.toString();
  }

  private static String paso( String nombre, String tipo, String cuerpo ) {
    return "<step><name>" + nombre + "</name><type>" + tipo + "</type><distribute>Y</distribute><copies>1</copies>"
      + cuerpo + "<GUI><xloc>10</xloc><yloc>10</yloc><draw>Y</draw></GUI></step>\n";
  }

  private static String campo( String nombre, String tipo ) {
    return "<field><name>" + nombre + "</name><type>" + tipo + "</type><length>-1</length><precision>-1</precision>"
      + "<set_empty_string>N</set_empty_string></field>";
  }

  private static String calculo( String nombre, String tipo, String a, String b, boolean quitar ) {
    return "<calculation><field_name>" + nombre + "</field_name><calc_type>" + tipo + "</calc_type><field_a>" + a
      + "</field_a><field_b>" + b + "</field_b><field_c/><value_type>Integer</value_type><value_length>-1"
      + "</value_length><value_precision>-1</value_precision><remove>" + ( quitar ? "Y" : "N" ) + "</remove>"
      + "</calculation>";
  }
}
