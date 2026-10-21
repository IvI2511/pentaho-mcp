package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.distinto;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Linaje (WP3): metaverse contra el motor propio en 01-06 (mismos caminos, origenes y operaciones),
 * 07 queda parcial con motivo y sin crear la base, el mapa de metaverse queda limpio, y la seccion
 * arma diagrama, tabla, resumen y datos en los tres idiomas. Deja los JSON en salida\linaje.
 *
 * @dueño WP3
 */
public class PruebaLinaje {

  private int mapaAntes;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    nulo( LinajeMetaverse.problema(), "metaverse disponible en el entorno de prueba" );
    mapaAntes = LinajeMetaverse.entradasEnMapa();
    cierto( mapaAntes >= 0, "se puede leer LineageGraphMap" );
  }

  // ---------------------------------------------------------------- los dos motores

  public void pruebaMotoresCoinciden() throws Exception {
    int comparados = 0;
    int conOps = 0;
    StringBuilder informe = new StringBuilder();
    for ( String ej : new String[] { "01", "02", "03", "04", "05", "06" } ) {
      ContextoPrueba ctx = ContextoPrueba.de( ej, "es" );
      TransMeta tm = (TransMeta) ctx.captura().copia;
      LinajePropio propio = new LinajePropio( tm, ctx );
      // todos los steps finales (tambien los que no escriben, como el Dummy de 02)
      Map<String, List<String>> campos = new LinkedHashMap<>();
      List<StepMeta> finales = new ArrayList<>();
      for ( StepMeta s : tm.getSteps() ) {
        if ( tm.isStepUsedInTransHops( s ) && tm.findNextSteps( s ).isEmpty() ) {
          finales.add( s );
          RowMetaInterface rm = propio.campos( s );
          noNulo( rm, ej + ": campos de " + s.getName() );
          campos.put( s.getName(), LinajeMetaverse.nombres( rm ) );
        }
      }
      int antes = LinajeMetaverse.entradasEnMapa();
      LinajeMetaverse.Corrida corrida = LinajeMetaverse.calcular( tm, campos, propio, 60000 );
      igual( antes, LinajeMetaverse.entradasEnMapa(), ej + ": metaverse no deja entradas en LineageGraphMap" );
      falso( LinajeMetaverse.enMapa( tm ), ej + ": el documento no quedo en el mapa" );
      for ( StepMeta d : finales ) {
        LinajeMetaverse.Respuesta mv = corrida.destinos.get( d.getName() );
        noNulo( mv, ej + ": respuesta de metaverse para " + d.getName() );
        if ( mv.error != null || !mv.fallidos.isEmpty() ) {
          // 06 "Escribir errores" (hop de error): metaverse falla (con todos o con algunos campos; los que
          // fallan van al motor propio) y el propio responde
          igual( "06", ej, "solo 06 tiene un destino donde metaverse falla: " + d.getName() + " " + mv.error + " "
            + mv.fallidos );
          igual( "Escribir errores", d.getName(), "destino donde metaverse falla" );
          if ( mv.error != null ) {
            continue;
          }
        }
        for ( ValueMetaInterface v : propio.campos( d ).getValueMetaList() ) {
          if ( mv.fallidos.contains( v.getName() ) ) {
            continue;
          }
          List<List<LinajePropio.Nodo>> deMv = mv.caminos.get( v.getName() );
          noNulo( deMv, ej + ": metaverse da caminos para " + d.getName() + "." + v.getName() );
          LinajePropio.Campo c = propio.campo( d, v );
          List<String> a = firmas( deMv );
          List<String> b = firmas( c.caminos );
          igual( a, b, ej + ": mismos caminos en " + d.getName() + "." + v.getName() );
          igual( origenes( deMv ), origenes( c.caminos ), ej + ": mismos origenes de " + v.getName() );
          // las operaciones de metaverse (crudas) dicen la misma funcion que el motor propio
          for ( List<LinajePropio.Nodo> camino : deMv ) {
            for ( LinajePropio.Nodo n : camino ) {
              if ( LinajePropio.CALCULA.equals( n.clase ) || LinajePropio.AGREGA.equals( n.clase ) ) {
                noNulo( n.metaverse, ej + ": metaverse describe " + n.step + "." + n.campo );
                String funcion = n.op.substring( 0, n.op.indexOf( '(' ) );
                contiene( n.metaverse, funcion, ej + ": metaverse y el motor propio dicen " + funcion );
                conOps++;
              }
            }
          }
          comparados++;
          informe.append( ej ).append( ' ' ).append( d.getName() ).append( '.' ).append( v.getName() ).append(
            " = " ).append( a ).append( '\n' );
        }
      }
    }
    EntornoKettle.escribir( new File( EntornoKettle.salida( "linaje" ), "caminos-01-06.txt" ), informe.toString() );
    cierto( comparados >= 50, "campos comparados en 01-06: " + comparados );
    cierto( conOps >= 10, "nodos de calculo o agregacion comparados con la descripcion de metaverse: " + conOps );
    igual( mapaAntes, LinajeMetaverse.entradasEnMapa(), "LineageGraphMap igual que al empezar" );
  }

  public void pruebaCasosConocidos() throws Exception {
    // 03: importe_total = SUM( MULTIPLY( precio_unitario, cantidad ) )
    LinajePropio.Destino d03 = destino( "03", "Escribir totales" );
    LinajePropio.Campo it = d03.campo( "importe_total" );
    igual( 2, it.caminos.size(), "03 importe_total: dos caminos" );
    igual( "[Leer ventas:cantidad:source, Leer ventas:precio_unitario:source]", origenes( it.caminos ).toString(),
      "03 importe_total: origenes" );
    String camino = firmas( it.caminos ).toString();
    contiene( camino, "Calcular importe:importe:calc:MULTIPLY(precio_unitario, cantidad)", "03 calculo" );
    contiene( camino, "Totales por producto:importe_total:aggregate:SUM(importe)", "03 agregado" );
    contiene( camino, "Leer ventas:precio_unitario:source:ventas.csv", "03 fuente con su archivo" );
    contiene( camino, "Leer ventas:cantidad:source:ventas.csv", "03 la otra fuente" );

    // 02: renombre y conversion de Select values
    LinajePropio.Destino d02 = destino( "02", "Escribir filtrados" );
    contiene( firmas( d02.campo( "id" ).caminos ).get( 0 ), "Elegir columnas:id:rename:cliente_id → id", "02 id" );
    contiene( firmas( d02.campo( "saldo" ).caminos ).get( 0 ), "Elegir columnas:saldo:convert:a String (#,##0.00)",
      "02 saldo" );

    // 04: la clave del lookup tambien es origen de cliente_id, con rol key
    LinajePropio.Destino d04 = destino( "04", "Escribir ventas con cliente" );
    List<String> cid = origenes( d04.campo( "cliente_id" ).caminos );
    igual( "[Leer clientes:cliente_id:source:key, Leer ventas:cliente_id:source]", cid.toString(), "04 cliente_id" );
    contiene( firmas( d04.campo( "cliente" ).caminos ).get( 0 ),
      "Buscar cliente:cliente:lookup:busca nombre como cliente por cliente_id = cliente_id", "04 cliente" );

    // 05: merge join
    contiene( firmas( destino( "05", "Escribir clientes con ventas" ).campo( "producto" ).caminos ).get( 0 ),
      "Unir clientes y ventas:producto:join:unión LEFT OUTER por cliente_id = cliente_id", "05 join" );

    // 06: filas de error (solo el motor propio) y variable
    LinajePropio.Destino err = destino( "06", "Escribir errores" );
    contiene( firmas( err.campo( "num_errores" ).caminos ).get( 0 ),
      "Leer clientes con errores:num_errores:created:filas de error de «Leer clientes con errores»", "06 errores" );
    contiene( firmas( destino( "06", "Escribir validos" ).campo( "umbral" ).caminos ).get( 0 ),
      "Traer umbral:umbral:created:variable ${SALDO_MINIMO}", "06 variable" );

    // 01: constantes creadas en la transformacion
    contiene( firmas( destino( "01", "Escribir CSV" ).campo( "pais" ).caminos ).get( 0 ),
      "Agregar constantes:pais:created:constante «", "01 constante" );
  }

  // ---------------------------------------------------------------- la seccion

  public void pruebaSeccion03() throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( "03", "es" );
    Seccion s = new SecLinaje().armar( ctx );
    guardar( s, "03-es" );
    igual( Seccion.OK, s.estado, "03: estado (" + s.motivo + ")" );
    igual( "Linaje de campos", s.titulo, "titulo" );
    igual( "metaverse", s.datos.path( "engine" ).asText(), "03: motor" );
    contiene( s.resumen, "Traza 6 campos de «Escribir totales» hasta «Leer ventas» (ventas.csv).", "resumen" );
    contiene( s.resumen, "Operaciones: 1 cálculo y 5 agregaciones.", "resumen de operaciones" );
    Diagrama g = bloque( s, Diagrama.class );
    noNulo( g, "03: diagrama" );
    igual( Diagrama.LINAJE, g.clase, "clase del diagrama" );
    contiene( g.mermaid, "flowchart LR\n", "mermaid" );
    contiene( g.mermaid, "subgraph g0 [\"Leer ventas\"]", "subgraph del origen" );
    contiene( g.mermaid, "|\"SUM(importe)\"|", "operacion sobre la flecha" );
    noContiene( g.mermaid, "Ordenar por producto", "los steps que solo pasan no se dibujan" );
    Tabla t = bloque( s, Tabla.class );
    igual( 6, t.filas.size(), "03: una fila por campo" );
    igual( "importe_total", t.filas.get( 2 ).get( 0 ), "fila de importe_total" );
    igual( "Leer ventas (ventas.csv): cantidad, precio_unitario", t.filas.get( 2 ).get( 2 ), "viene de" );
    igual( "MULTIPLY(precio_unitario, cantidad) en «Calcular importe» → SUM(importe) en «Totales por producto»",
      t.filas.get( 2 ).get( 3 ), "transformaciones" );
    igual( "sin cambios", t.filas.get( 0 ).get( 3 ), "producto pasa sin cambios" );
    contiene( t.nota, "metaverse", "nota con el motor" );
    igual( 0, s.datos.path( "unresolved_steps" ).size(), "sin pendientes" );
    JsonNode campo = s.datos.path( "targets" ).get( 0 ).path( "fields" ).get( 2 );
    igual( "importe_total", campo.path( "name" ).asText(), "datos: campo" );
    igual( 2, campo.path( "origins" ).size(), "datos: origenes" );
    igual( "aggregate", campo.path( "paths" ).get( 0 ).get( 3 ).path( "kind" ).asText(), "datos: kind" );
    contiene( campo.path( "paths" ).get( 0 ).get( 3 ).path( "metaverse" ).asText(), "SUM", "datos: crudo de metaverse" );
    // ida y vuelta con el modelo
    Informe inf = new Informe();
    inf.secciones.add( s );
    Informe vuelta = ModeloJson.leer( ModeloJson.escribir( inf, false ), Informe.class );
    igual( s.bloques.size(), vuelta.secciones.get( 0 ).bloques.size(), "Jackson: bloques" );
    igual( mapaAntes, LinajeMetaverse.entradasEnMapa(), "mapa limpio" );
  }

  public void pruebaSeccion07Parcial() throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( "07", "es" );
    File base = new File( EntornoKettle.ejemplos(), "salida/demo.sqlite" );
    falso( base.exists(), "07: la base no existe antes" );
    Seccion s = new SecLinaje().armar( ctx );
    guardar( s, "07-es" );
    igual( Seccion.PARCIAL, s.estado, "07: parcial" );
    noNulo( s.motivo, "07: motivo" );
    contiene( s.motivo, "Consultar SQLite", "07: el motivo nombra el step" );
    contiene( s.motivo, "salida/demo.sqlite", "07: el motivo nombra la base (relativa)" );
    contiene( s.motivo, "de 2 steps; el primero es «Consultar SQLite»", "07: cuenta los steps sin resolver" );
    JsonNode us = s.datos.path( "unresolved_steps" );
    igual( "Consultar SQLite", us.get( 0 ).path( "step" ).asText(), "07: step sin resolver" );
    igual( "Escribir consulta", us.get( 1 ).path( "step" ).asText(), "07: el destino depende de el" );
    falso( s.datos.path( "metaverse" ).path( "available" ).asBoolean(), "07: metaverse no se usa (crearia la base)" );
    cierto( bloque( s, Aviso.class ) != null, "07: aviso del destino" );
    falso( base.exists(), "07: no se creo la base" );
    falso( base.getParentFile().exists(), "07: ni la carpeta salida" );
    igual( mapaAntes, LinajeMetaverse.entradasEnMapa(), "mapa limpio" );
  }

  /**
   * La base SQLite existe y la carpeta del .ktr tiene espacios: ${Internal.Entry.Current.Directory} llega
   * como URI con %20 y el linaje la daba por faltante (pasaba con el ejemplo del repo, «Pentaho MCP - ...»).
   */
  public void pruebaBaseEnCarpetaConEspacios() throws Exception {
    File dir = new File( EntornoKettle.carpeta(), "linaje con espacios ñ" );
    java.nio.file.Path p = dir.toPath();
    if ( dir.exists() ) {
      try ( java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk( p ) ) {
        w.sorted( java.util.Comparator.reverseOrder() ).forEach( x -> x.toFile().delete() );
      }
    }
    File ktr = new File( dir, "07_conexion_sqlite.ktr" );
    java.nio.file.Files.createDirectories( new File( dir, "salida" ).toPath() );
    java.nio.file.Files.copy( EntornoKettle.ejemplo( "07" ).toPath(), ktr.toPath() );
    File base = new File( dir, "salida/demo.sqlite" );
    java.nio.file.Files.copy( EntornoKettle.copiaDw().toPath(), base.toPath() );
    TransMeta tm = new TransMeta( ktr.getAbsolutePath() );
    contiene( tm.environmentSubstitute( "${Internal.Entry.Current.Directory}" ), "%20", "la variable es una URI con %20" );
    Captura c = new Captura();
    c.copia = tm;
    c.docId = "prueba:espacios";
    c.nombre = tm.getName();
    c.archivo = ktr.getAbsolutePath();
    c.tipo = Captura.TRANSFORMACION;
    c.capturado = new java.util.Date();
    Pedido ped = new Pedido();
    ped.idioma = "es";
    Seccion s = new SecLinaje().armar( new ContextoPrueba( c, ped, new Tema() ) );
    String todo = String.valueOf( s.motivo ) + " " + s.datos.path( "unresolved_steps" );
    falso( todo.contains( "no existe" ), "la base existe: no se da por faltante (" + s.motivo + ")" );
    cierto( base.exists() && base.length() > 0, "la base sigue ahi" );
  }

  public void pruebaSeccion06DosDestinos() throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( "06", "en" );
    Seccion s = new SecLinaje().armar( ctx );
    guardar( s, "06-en" );
    igual( Seccion.OK, s.estado, "06: estado (" + s.motivo + ")" );
    JsonNode ts = s.datos.path( "targets" );
    igual( 2, ts.size(), "06: dos destinos" );
    igual( "mixed", s.datos.path( "engine" ).asText(), "06: metaverse y respaldo" );
    String motores = ts.get( 0 ).path( "engine" ).asText() + "," + ts.get( 1 ).path( "engine" ).asText();
    contiene( motores, "metaverse", "un destino con metaverse" );
    contiene( motores, "kettle-metadata", "y otro con el motor propio" );
    int tablas = 0;
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla ) {
        tablas++;
        if ( ( (Tabla) b ).titulo.contains( "Escribir errores" ) ) {
          // por que se uso el respaldo, sin la excepcion (esa va al log)
          contiene( b.nota, "metaverse could not", "nota: por que se uso el respaldo" );
          noContiene( b.nota, "Exception", "nota sin detalles tecnicos" );
        }
      }
    }
    igual( 2, tablas, "06: una tabla por destino" );
    contiene( s.resumen, "Traces 11 fields of 2 target steps back to", "resumen en ingles" );
  }

  public void pruebaRespaldoSinMetaverse() throws Exception {
    Seccion conMv = new SecLinaje().armar( ContextoPrueba.de( "04", "es" ) );
    KettleClientEnvironment.ClientType antes = KettleClientEnvironment.getInstance().getClient();
    Seccion sinMv;
    try {
      KettleClientEnvironment.getInstance().setClient( null );
      igual( LinajeMetaverse.SIN_TIPO_CLIENTE, LinajeMetaverse.problema(), "sin tipo de cliente no se usa metaverse" );
      sinMv = new SecLinaje().armar( ContextoPrueba.de( "04", "es" ) );
    } finally {
      KettleClientEnvironment.getInstance().setClient( antes );
    }
    guardar( sinMv, "04-sin-metaverse" );
    igual( "metaverse", conMv.datos.path( "engine" ).asText(), "con metaverse" );
    igual( "kettle-metadata", sinMv.datos.path( "engine" ).asText(), "sin metaverse, el motor propio" );
    igual( Seccion.OK, sinMv.estado, "el respaldo deja la seccion ok" );
    igual( bloque( conMv, Tabla.class ).filas, bloque( sinMv, Tabla.class ).filas, "la misma tabla con los dos motores" );
    // cliente_id pasa sin cambios desde ventas: haberlo usado como clave en clientes.csv no lo hace «venir» de ahi
    igual( "Leer ventas (ventas.csv): cliente_id", bloque( sinMv, Tabla.class ).filas.get( 1 ).get( 2 ),
      "cliente_id: solo su origen, sin la clave de busqueda" );
    igual( bloque( conMv, Diagrama.class ).mermaid, bloque( sinMv, Diagrama.class ).mermaid, "el mismo diagrama" );
    // la clave va punteada hacia los valores que busca (cliente, ciudad), no hacia un cliente_id que pasa
    String m = bloque( conMv, Diagrama.class ).mermaid;
    Map<String, String> etiqueta = new HashMap<String, String>();
    Matcher nodo = Pattern.compile( "(?m)^\\s+(c\\d+)\\[\"([^\"]*)\"\\]" ).matcher( m );
    while ( nodo.find() ) {
      etiqueta.put( nodo.group( 1 ), nodo.group( 2 ) );
    }
    List<String> destinosClave = new ArrayList<String>();
    Matcher punteada = Pattern.compile( "(?m)^\\s+(c\\d+) -\\.->(?:\\|\"[^\"]*\"\\|)? (c\\d+|g\\d+)$" ).matcher( m );
    while ( punteada.find() ) {
      destinosClave.add( etiqueta.get( punteada.group( 2 ) ) );
      igual( "cliente_id", etiqueta.get( punteada.group( 1 ) ), "la clave sale del cliente_id de clientes.csv" );
    }
    igual( Arrays.asList( "cliente", "ciudad" ), destinosClave, "flechas de la clave hacia los valores buscados:\n" + m );
    igual( 1, cuenta( m, "|\"key cliente_id = cliente_id\"|" ) + cuenta( m, "|\"clave cliente_id = cliente_id\"|" ),
      "el rotulo de la clave una sola vez:\n" + m );
    igual( conMv.resumen, sinMv.resumen, "el mismo resumen" );
    contiene( bloque( sinMv, Tabla.class ).nota, "Kettle no tiene definido el tipo de cliente", "nota con el motivo" );
  }

  public void pruebaPasoPedido() throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( "03", "pt" );
    ctx.pedido().linajeStep = "totales POR producto";
    Seccion s = new SecLinaje().armar( ctx );
    igual( 1, s.datos.path( "targets" ).size(), "un destino" );
    igual( "Totales por producto", s.datos.path( "targets" ).get( 0 ).path( "step" ).asText(), "el pedido" );
    contiene( s.resumen, "Rastreia 6 campos de “Totales por producto”", "resumen en portugues" );

    ContextoPrueba otro = ContextoPrueba.de( "03", "es" );
    otro.pedido().linajeStep = "No existe";
    Seccion s2 = new SecLinaje().armar( otro );
    Aviso a = bloque( s2, Aviso.class );
    noNulo( a, "aviso del step que no existe" );
    contiene( a.texto, "No hay un step «No existe»", "texto del aviso" );
    igual( "Escribir totales", s2.datos.path( "targets" ).get( 0 ).path( "step" ).asText(), "vuelve a los finales" );
  }

  public void pruebaPlazoYCancelacion() throws Exception {
    ContextoPrueba sinTiempo = ContextoPrueba.de( "03", "es" );
    sinTiempo.pedido().timeoutMs = 0;
    Seccion s = new SecLinaje().armar( new ContextoPrueba( sinTiempo.captura(), sinTiempo.pedido(), null ) );
    igual( Seccion.PARCIAL, s.estado, "sin plazo: parcial" );
    igual( "Se agotó el tiempo del linaje", s.motivo, "sin plazo: motivo" );

    ContextoPrueba cancelado = ContextoPrueba.de( "03", "es" );
    cancelado.cancelar = true;
    Seccion c = new SecLinaje().armar( cancelado );
    igual( Seccion.PARCIAL, c.estado, "cancelado: parcial" );
    igual( "Cancelado", c.motivo, "cancelado: motivo" );
    igual( mapaAntes, LinajeMetaverse.entradasEnMapa(), "mapa limpio" );
  }

  public void pruebaJobNoAplica() throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( "10", "es" );
    SecLinaje sec = new SecLinaje();
    igual( "Solo para transformaciones", sec.noAplica( ctx.captura(), "es" ), "job: no aplica" );
    nulo( sec.noAplica( ContextoPrueba.de( "03", "es" ).captura(), "es" ), "trans: aplica" );
    Seccion s = sec.armar( ctx );
    igual( Seccion.OMITIDA, s.estado, "job: omitida" );
    igual( SecLinaje.AREA, "linaje", "area de textos" );
  }

  public void pruebaClonNoTocaElOriginal() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    String nombre = tm.getName();
    String variable = tm.getVariable( "Internal.Transformation.Name" );
    final AtomicInteger avisos = new AtomicInteger();
    tm.addNameChangedListener( ( objeto, viejo, nuevo ) -> avisos.incrementAndGet() );
    TransMeta clon = LinajeMetaverse.clonar( tm );
    distinto( nombre, clon.getName(), "el clon tiene otro nombre" );
    falso( clon.equals( tm ), "el clon no es equals al original" );
    igual( nombre, tm.getName(), "el original conserva el nombre" );
    igual( variable, tm.getVariable( "Internal.Transformation.Name" ), "y su variable" );
    igual( 0, avisos.get(), "no se avisa a los listeners del original" );
    clon.setName( "otro" );
    igual( 0, avisos.get(), "ni cuando se renombra el clon (listeners propios)" );
  }

  public void pruebaEscapes() throws Exception {
    Captura c = EntornoKettle.captura( "03" );
    TransMeta tm = (TransMeta) c.copia;
    tm.findStep( "Calcular importe" ).setName( "<b>&\"x" );
    Pedido p = new Pedido();
    p.idioma = "es";
    Seccion s = new SecLinaje().armar( new ContextoPrueba( c, p, null ) );
    Diagrama g = bloque( s, Diagrama.class );
    noNulo( g, "diagrama" );
    contiene( g.mermaid, "#60;b#62;#38;#34;x", "nombre escapado en el mermaid" );
    noContiene( g.mermaid, "<b>", "sin el nombre crudo" );
    contiene( bloque( s, Tabla.class ).filas.get( 2 ).get( 3 ), "en «<b>&\"x»", "la tabla lleva el texto (escapa el render)" );
  }

  public void pruebaTextosCompletos() throws Exception {
    for ( String idioma : new String[] { "en", "es", "pt" } ) {
      for ( String ej : new String[] { "01", "02", "04", "05", "07" } ) {
        Seccion s = new SecLinaje().armar( ContextoPrueba.de( ej, idioma ) );
        guardar( s, ej + "-" + idioma );
        String json = ModeloJson.escribir( s, false );
        for ( String prefijo : new String[] { "\"resumen.", "\"motivo.", "\"op.", "\"col.", "\"titulo.", "\"nota.",
          "\"aviso.", "\"n.", "pendiente.", "\"clase." } ) {
          noContiene( json, prefijo, idioma + " " + ej + ": clave sin texto " + prefijo );
        }
      }
    }
  }

  // ---------------------------------------------------------------- utilidades

  private static LinajePropio.Destino destino( String ej, String step ) throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( ej, "es" );
    TransMeta tm = (TransMeta) ctx.captura().copia;
    LinajePropio.Destino d = new LinajePropio( tm, ctx ).destino( tm.findStep( step ) );
    nulo( d.motivo, ej + " " + step + ": sin motivo" );
    return d;
  }

  private static List<String> firmas( List<List<LinajePropio.Nodo>> cs ) {
    List<String> l = new ArrayList<>();
    for ( List<LinajePropio.Nodo> c : cs ) {
      StringBuilder sb = new StringBuilder();
      for ( LinajePropio.Nodo n : c ) {
        sb.append( sb.length() == 0 ? "" : " > " ).append( n.firma() );
      }
      l.add( sb.toString() );
    }
    Collections.sort( l );
    return l;
  }

  /** "step:campo:clase[:key]" del primer nodo de cada camino, sin repetir, ordenados. */
  private static List<String> origenes( List<List<LinajePropio.Nodo>> cs ) {
    List<String> l = new ArrayList<>();
    for ( List<LinajePropio.Nodo> c : cs ) {
      boolean clave = false;
      for ( LinajePropio.Nodo n : c ) {
        clave |= LinajePropio.ROL_CLAVE.equals( n.rol );
      }
      String o = c.get( 0 ).step + ":" + c.get( 0 ).campo + ":" + c.get( 0 ).clase + ( clave ? ":key" : "" );
      if ( !l.contains( o ) ) {
        l.add( o );
      }
    }
    Collections.sort( l );
    return l;
  }

  @SuppressWarnings( "unchecked" )
  private static <T extends Bloque> T bloque( Seccion s, Class<T> tipo ) {
    for ( Bloque b : s.bloques ) {
      if ( tipo.isInstance( b ) ) {
        return (T) b;
      }
    }
    return null;
  }

  private static void guardar( Seccion s, String nombre ) throws Exception {
    EntornoKettle.escribir( new File( EntornoKettle.salida( "linaje" ), nombre + ".json" ), ModeloJson.escribir( s,
      true ) );
  }

  /** Cuantas veces aparece t en s. */
  private static int cuenta( String s, String t ) {
    int n = 0;
    for ( int i = s.indexOf( t ); i >= 0; i = s.indexOf( t, i + t.length() ) ) {
      n++;
    }
    return n;
  }
}
