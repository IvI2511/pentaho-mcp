package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * La hoja de mapeo origen-destino (D3) sin Spoon: las filas de un ETL (origen por el linaje, regla, valor por
 * defecto del Stream lookup), la sección mapping, exportar a xlsx y volver a leerla (todo implementado), y una hoja
 * rellenada a mano en CSV con filas que faltan: copia, constante y marcador, aplicadas delante del step destino en un
 * solo Ctrl+Z.
 */
public class PruebaMapeo {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static Mapeo.Fila fila( List<Mapeo.Fila> fs, String campo ) {
    for ( Mapeo.Fila f : fs ) {
      if ( campo.equals( f.campo ) ) {
        return f;
      }
    }
    return null;
  }

  public void pruebaFilasDeUnEtl() throws Exception {
    TransMeta tm = EntornoKettle.trans( "04" );
    List<Mapeo.Fila> fs = Mapeo.filas( tm, Mapeo.contexto( tm, "es" ), null );
    cierto( fs.size() >= 5, "una fila por campo declarado del Text file output: " + fs.size() );
    Mapeo.Fila venta = fila( fs, "venta_id" );
    noNulo( venta, "venta_id" );
    igual( "04_ventas_con_cliente.csv.venta_id", venta.destino, "destino archivo.campo" );
    igual( "ventas.csv.venta_id", venta.origen, "origen archivo.campo" );
    contiene( venta.tipo, "Integer", "tipo" );
    igual( "Borrador", venta.estado, "estado por defecto" );
    Mapeo.Fila cliente = fila( fs, "cliente" );
    noNulo( cliente, "cliente (renombrado en el lookup)" );
    igual( "clientes.csv.nombre", cliente.origen, "viene de clientes.csv, no de la clave" );
    igual( "DESCONOCIDO", cliente.defecto, "el valor por defecto del Stream lookup" );
    cierto( !cliente.regla.isEmpty() && !cliente.regla.equals( Rotulos.de( SecLinaje.AREA, "es" ).s( "sin_cambios" ) ),
      "la regla dice la búsqueda: " + cliente.regla );
  }

  public void pruebaCalculadoYAgrupado() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    List<Mapeo.Fila> fs = Mapeo.filas( tm, Mapeo.contexto( tm, "en" ), null );
    boolean suma = false;
    for ( Mapeo.Fila f : fs ) {
      suma |= f.regla.contains( "SUM" ) || f.regla.toLowerCase().contains( "sum" );
    }
    cierto( suma, "una regla con la agregación: " + Mapeo.json( fs ) );
  }

  public void pruebaInsertUpdate() throws Exception {
    File dir = new File( EntornoKettle.carpeta(), "mapeo-estres" );
    dir.mkdirs();
    File ktr = new File( dir, "upsert_load_sqlite.ktr" );
    Files.copy( new File( EntornoKettle.recursos(), "estres/upsert_load_sqlite.ktr" ).toPath(), ktr.toPath(),
      java.nio.file.StandardCopyOption.REPLACE_EXISTING );
    TransMeta tm = new TransMeta( ktr.getPath() );
    StepMeta iu = null;
    for ( StepMeta s : tm.getSteps() ) {
      if ( "InsertUpdate".equals( s.getStepID() ) ) {
        iu = s;
      }
    }
    noNulo( iu, "hay un Insert/Update" );
    String tabla = Mapeo.recursoDestino( tm, iu );
    cierto( !tabla.equals( iu.getName() ), "el recurso es la tabla: " + tabla );
    List<Mapeo.Fila> fs = Mapeo.filas( tm, Mapeo.contexto( tm, "en" ), java.util.Arrays.asList( iu ) );
    cierto( fs.size() >= 2, "clave y columnas a actualizar: " + Mapeo.json( fs ) );
  }

  public void pruebaSeccion() throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( "04", "es" );
    Seccion s = new SecMapeo().armar( ctx );
    igual( SeccionId.MAPEO, s.id, "id" );
    igual( "Hoja de mapeo origen-destino", s.titulo, "título" );
    Tabla t = (Tabla) s.bloques.get( 0 );
    igual( 9, t.columnas.size(), "nueve columnas" );
    igual( "Origen", t.columnas.get( 0 ), "en español" );
    cierto( s.datos.path( "rows" ).size() == t.filas.size(), "las filas también en los datos" );
    noNulo( new SecMapeo().noAplica( EntornoKettle.captura( "10" ), "es" ), "no aplica a jobs" );
    nulo( new SecMapeo().noAplica( EntornoKettle.captura( "04" ), "es" ), "aplica a transformaciones" );
  }

  /** Exportar a xlsx con el renderizador de los Informes y volver a leer: todas las filas implementadas. */
  public void pruebaExportarYReleer() throws Exception {
    ContextoPrueba ctx = ContextoPrueba.de( "04", "es" );
    Informe inf = new Informe();
    inf.idioma = "es";
    inf.titulo = "Hoja de mapeo";
    inf.secciones.add( new SecMapeo().armar( ctx ) );
    File dir = EntornoKettle.salida( "mapeo" );
    File xlsx = new File( dir, "04_mapeo.xlsx" );
    InformeServicio.exportar( inf, new Tema(), Formato.XLSX, xlsx, null );
    cierto( xlsx.length() > 1000, "xlsx escrito" );
    File md = new File( dir, "04_mapeo.md" );
    InformeServicio.exportar( inf, new Tema(), Formato.MARKDOWN, md, null );
    contiene( new String( Files.readAllBytes( md.toPath() ), StandardCharsets.UTF_8 ), "| Origen | Destino |",
      "Markdown con la tabla" );
    List<MapeoImportar.FilaHoja> hoja = MapeoImportar.leer( xlsx );
    TransMeta tm = (TransMeta) ctx.captura().copia;
    List<Mapeo.Fila> etl = Mapeo.filas( tm, Mapeo.contexto( tm, "es" ), null );
    igual( etl.size(), hoja.size(), "las filas de la hoja" );
    List<MapeoImportar.Veredicto> vs = MapeoImportar.comparar( tm, Mapeo.contexto( tm, "es" ), hoja, etl, Rotulos.de(
      PlanoInforme.AREA, "es" ) );
    ObjectNode j = MapeoImportar.json( vs, etl, null );
    igual( etl.size(), j.path( "implemented" ).asInt(), "todo implementado: " + j );
    igual( 0, j.path( "missing" ).asInt(), "nada falta" );
  }

  /** Una hoja rellenada a mano (CSV, encabezados en español): copia, constante, marcador y una distinta. */
  public void pruebaImportarYAplicar() throws Exception {
    File csv = new File( EntornoKettle.salida( "mapeo" ), "rellenada.csv" );
    String t = "Hoja de mapeo de ventas;;;\n"
      + "Origen;Destino;Tipo;Regla;Por defecto;Validación;Estado;Responsable;Notas\n"
      + "ventas.csv.venta_id;04_ventas_con_cliente.csv.venta_id;Integer;;;;Aprobado;Profe;\n"
      + "clientes.csv.ciudad;04_ventas_con_cliente.csv.ciudad_cliente;String;copiar la ciudad;;;Aprobado;;\n"
      + ";04_ventas_con_cliente.csv.pais;String;;España;;Revisado;;\n"
      + "crm.segmento;04_ventas_con_cliente.csv.segmento;String;\"Buscar el segmento en el CRM; si no, \"\"B\"\"\";;;"
      + "Borrador;;\n"
      + "clientes.csv.nombre;04_ventas_con_cliente.csv.cliente;Integer;;;;Aprobado;;\n";
    Files.write( csv.toPath(), t.getBytes( StandardCharsets.UTF_8 ) );
    List<MapeoImportar.FilaHoja> hoja = MapeoImportar.leer( csv );
    igual( 5, hoja.size(), "encuentra el encabezado en la segunda fila" );
    igual( "Buscar el segmento en el CRM; si no, \"B\"", hoja.get( 3 ).g( "rule" ), "comillas del CSV" );
    TransMeta tm = EntornoKettle.trans( "04" );
    List<Mapeo.Fila> etl = Mapeo.filas( tm, Mapeo.contexto( tm, "es" ), null );
    Rotulos r = Rotulos.de( PlanoInforme.AREA, "es" );
    List<MapeoImportar.Veredicto> vs = MapeoImportar.comparar( tm, Mapeo.contexto( tm, "es" ), hoja, etl, r );
    igual( MapeoImportar.IMPLEMENTADA, vs.get( 0 ).estado, "venta_id implementada" );
    igual( "rename", vs.get( 1 ).accion, "ciudad llega: se copia" );
    igual( "constant", vs.get( 2 ).accion, "pais: constante" );
    igual( "placeholder", vs.get( 3 ).accion, "segmento: marcador" );
    igual( MapeoImportar.DIFIERE, vs.get( 4 ).estado, "cliente: distinto tipo" );
    contiene( vs.get( 4 ).detalle, "Integer", "dice el tipo" );
    ObjectNode j = MapeoImportar.json( vs, etl, null );
    igual( 1, j.path( "implemented" ).asInt(), "1 implementada" );
    igual( 1, j.path( "differs" ).asInt(), "1 distinta" );
    igual( 3, j.path( "missing" ).asInt(), "3 faltan" );
    cierto( j.path( "in_etl_not_in_sheet" ).size() > 0, "campos del ETL que la hoja no tiene" );

    int steps = tm.nrSteps();
    int hops = tm.nrTransHops();
    List<MapeoImportar.Insercion> hecho = MapeoImportar.aplicar( tm, vs, "es", UndoGrupo.sinSpoon( tm ), null );
    igual( 1, hecho.size(), "delante de un step" );
    igual( 3, hecho.get( 0 ).steps.size(), "Calculator, Add constants y marcador: " + hecho.get( 0 ).steps );
    StepMeta destino = tm.findStep( "Escribir ventas con cliente" );
    StepMeta marcador = tm.findStep( r.f( "map.imp.paso.marcador", "Escribir ventas con cliente" ) );
    noNulo( marcador, "el marcador" );
    noNulo( tm.findTransHop( marcador, destino ), "el último va al destino" );
    RowMetaInterface llega = tm.getStepFields( marcador );
    noNulo( llega.searchValueMeta( "ciudad_cliente" ), "la copia crea ciudad_cliente" );
    noNulo( llega.searchValueMeta( "ciudad" ), "y no quita ciudad" );
    noNulo( llega.searchValueMeta( "pais" ), "la constante crea pais" );
    igual( "mapping", marcador.getAttribute( "pentaho_live_plan", "placeholder" ), "el marcador es del boceto" );
    contiene( PruebaPlanoConstruir.notaDe( tm, r.f( "map.imp.paso.marcador", "Escribir ventas con cliente" ) )
      .getNote(), "segmento: Buscar el segmento", "la nota lista la regla" );
    PruebaPlanoConstruir.deshacerUno( tm );
    igual( steps, tm.nrSteps(), "un Ctrl+Z quita los steps" );
    igual( hops, tm.nrTransHops(), "y deja los hops como estaban" );
    noNulo( tm.findTransHop( tm.findStep( "Buscar cliente" ), destino ), "el hop original vuelve" );
  }

  public void pruebaHojasMalas() throws Exception {
    File csv = new File( EntornoKettle.salida( "mapeo" ), "sin_encabezado.csv" );
    Files.write( csv.toPath(), "a;b;c\n1;2;3\n".getBytes( StandardCharsets.UTF_8 ) );
    ToolError e = lanza( ToolError.class, () -> MapeoImportar.leer( csv ), "sin encabezado" );
    contiene( e.getMessage(), "Target", "lo explica" );
    File txt = new File( EntornoKettle.salida( "mapeo" ), "x.pdf" );
    Files.write( txt.toPath(), new byte[] { 1 } );
    lanza( ToolError.class, () -> MapeoImportar.leer( txt ), "formato no soportado" );
    igual( "[dw.hechos, importe]", java.util.Arrays.toString( MapeoImportar.partir( "dw.hechos.importe" ) ),
      "último punto" );
    igual( "[, importe]", java.util.Arrays.toString( MapeoImportar.partir( "importe" ) ), "sin tabla" );
    cierto( MapeoImportar.mismoTipo( "varchar(20)", "String(20)" ), "varchar = String" );
    cierto( MapeoImportar.mismoTipo( "decimal", "BigNumber(10,2)" ), "decimal = BigNumber" );
    JsonNode encab = McpHttpServer.JSON.valueToTree( MapeoImportar.sinonimos() );
    contiene( encab.toString(), "\"origem\":\"source\"", "encabezados en portugués" );
  }
}
