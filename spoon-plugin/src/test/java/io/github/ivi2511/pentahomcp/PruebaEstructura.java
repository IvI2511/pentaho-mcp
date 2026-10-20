package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Secciones estructurales de WP2 sobre los ejemplos 01-11, una por una y con el ContextoKettle real:
 * portada, resumen (frases por idioma), flujo y flujo del job (mismo Mermaid que generate_report),
 * diccionario, validacion, conexiones y conclusiones; mas el contexto (memo, presupuesto,
 * cancelacion) y los textos (mismas claves en en/es/pt).
 *
 * @dueño WP2
 */
public class PruebaEstructura {

  private static final String[] TRANS = { "01", "02", "03", "04", "05", "06", "07" };
  private static final String[] JOBS = { "10", "11" };

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static ContextoKettle ctx( String ejemplo, String idioma ) throws Exception {
    return ctx( ejemplo, idioma, new Tema(), null );
  }

  private static ContextoKettle ctx( String ejemplo, String idioma, Tema tema, Narrativa n ) throws Exception {
    Pedido p = new Pedido();
    p.idioma = idioma;
    p.narrativa = n;
    return new ContextoKettle( EntornoKettle.captura( ejemplo ), p, tema, Progreso.NULO );
  }

  @SuppressWarnings( "unchecked" )
  private static <T extends Bloque> List<T> bloques( Seccion s, Class<T> tipo ) {
    List<T> l = new ArrayList<T>();
    for ( Bloque b : s.bloques ) {
      if ( tipo.isInstance( b ) ) {
        l.add( (T) b );
      }
    }
    return l;
  }

  private static Tabla tabla( Seccion s, String id ) {
    for ( Tabla t : bloques( s, Tabla.class ) ) {
      if ( id.equals( t.id ) ) {
        return t;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- portada

  public void pruebaPortada() throws Exception {
    Seccion s = new SecPortada().armar( ctx( "03", "es", ModeloMuestra.tema(), null ) );
    igual( SeccionId.PORTADA, s.id, "id" );
    igual( Seccion.OK, s.estado, "estado" );
    Imagen logo = bloques( s, Imagen.class ).get( 0 );
    igual( 240, logo.ancho, "ancho del logo" );
    igual( 80, logo.alto, "alto del logo" );
    Lista l = bloques( s, Lista.class ).get( 0 );
    cierto( l.items.contains( "Documento: 03_ventas_por_producto.ktr" ), "documento: " + l.items );
    cierto( l.items.contains( "Autor: Nombre Apellido" ), "autor: " + l.items );
    cierto( l.items.contains( "Tipo: Transformación" ), "tipo: " + l.items );
    contiene( s.resumen, "Transformación 03_ventas_por_producto", "resumen" );
    falso( bloques( s, Parrafo.class ).isEmpty(), "descripcion del .ktr" );
    igual( "transformation", s.datos.path( "type" ).asText(), "datos.type" );

    Seccion j = new SecPortada().armar( ctx( "10", "en" ) );
    contiene( j.resumen, "Job 10_job_secuencia", "resumen del job" );
    cierto( bloques( j, Imagen.class ).isEmpty(), "sin logo en el tema neutro" );
  }

  // ---------------------------------------------------------------- resumen

  public void pruebaResumenTrans() throws Exception {
    Seccion es = new SecResumen().armar( ctx( "03", "es" ) );
    System.out.println( "  03 es: " + es.resumen );
    contiene( es.resumen, "Lee 1 archivo (ventas.csv)", "lee" );
    contiene( es.resumen, "calcula, ordena y agrupa, y escribe 1 archivo", "verbos y escritura" );
    contiene( es.resumen, "5 steps y 4 hops.", "cuentas" );
    Indicadores ind = bloques( es, Indicadores.class ).get( 0 );
    igual( "5", ind.items.get( 0 ).valor, "indicador steps" );

    Seccion en = new SecResumen().armar( ctx( "03", "en" ) );
    System.out.println( "  03 en: " + en.resumen );
    contiene( en.resumen, "Reads 1 file (ventas.csv), calculates, sorts and groups, and writes 1 file", "en" );
    Seccion pt = new SecResumen().armar( ctx( "03", "pt" ) );
    System.out.println( "  03 pt: " + pt.resumen );
    contiene( pt.resumen, "Lê 1 arquivo (ventas.csv)", "pt" );

    for ( String e : TRANS ) {
      Seccion s = new SecResumen().armar( ctx( e, "es" ) );
      System.out.println( "  " + e + " es: " + s.resumen );
      noNulo( s.resumen, "resumen de " + e );
      falso( s.resumen.contains( "%" ), "sin patrones sin resolver en " + e );
    }
    String r01 = new SecResumen().armar( ctx( "01", "es" ) ).resumen;
    contiene( r01, "Genera filas y agrega constantes, y escribe 1 archivo (01_constantes.csv)", "01: coma y parametro" );
    contiene( new SecResumen().armar( ctx( "04", "es" ) ).resumen, "busca valores", "04 busca" );
    contiene( new SecResumen().armar( ctx( "05", "es" ) ).resumen, "une", "05 une" );
    contiene( new SecResumen().armar( ctx( "06", "es" ) ).resumen, "lee variables", "06 variables" );
    contiene( new SecResumen().armar( ctx( "07", "es" ) ).resumen, "Consulta 1 base (sqlite_demo)", "07 consulta" );
  }

  public void pruebaResumenJob() throws Exception {
    Seccion s10 = new SecResumen().armar( ctx( "10", "es" ) );
    System.out.println( "  10 es: " + s10.resumen );
    contiene( s10.resumen, "Ejecuta 2 transformaciones (01_generar_constantes.ktr, 03_ventas_por_producto.ktr)",
      "10" );
    Seccion s11 = new SecResumen().armar( ctx( "11", "en" ) );
    System.out.println( "  11 en: " + s11.resumen );
    contiene( s11.resumen, "Runs 1 job (10_job_secuencia.kjb) and writes to the log", "11" );
  }

  public void pruebaResumenJuntaLasDemas() throws Exception {
    ContextoKettle c = ctx( "03", "es" );
    List<Seccion> otras = new ArrayList<Seccion>();
    otras.add( new SecFlujo( false ).armar( c ) );
    otras.add( new SecConexiones().armar( c ) );
    Seccion m = new Seccion( SeccionId.METRICAS, "Métricas de ejecución" );
    m.resumen = "La última ejecución procesó 400.000 filas en 2,7 s.";
    otras.add( m );
    Seccion s = new SecResumen().armar( c, otras );
    Lista d = null;
    for ( Lista l : bloques( s, Lista.class ) ) {
      d = l;
    }
    noNulo( d, "lista de destacados" );
    cierto( d.items.get( 0 ).startsWith( "**Diagrama de flujo:** " ), "destacado del flujo: " + d.items );
    Parrafo p = null;
    for ( Parrafo x : bloques( s, Parrafo.class ) ) {
      if ( "summary-texto".equals( x.id ) ) {
        p = x;
      }
    }
    noNulo( p, "parrafo del resumen" );
    contiene( p.texto, "\nLa última ejecución procesó 400.000 filas", "las metricas van en el parrafo" );
  }

  // ---------------------------------------------------------------- flujo

  public void pruebaFlujoTrans() throws Exception {
    for ( String e : TRANS ) {
      ContextoKettle c = ctx( e, "es" );
      TransMeta tm = (TransMeta) c.captura().copia;
      Seccion s = new SecFlujo( false ).armar( c );
      igual( Seccion.OK, s.estado, "estado de " + e );
      Diagrama d = bloques( s, Diagrama.class ).get( 0 );
      igual( Diagrama.FLUJO, d.clase, "clase" );
      cierto( d.mermaid.startsWith( "flowchart LR\n" ), "mermaid de " + e );
      nulo( d.imagen, "sin lienzo en una captura sin Display" );
      noNulo( d.alternativa, "tabla alternativa" );
      igual( tm.nrTransHops(), d.alternativa.filas.size(), "hops en la alternativa de " + e );
      Tabla t = tabla( s, "flow-steps" );
      igual( tm.nrSteps(), t.filas.size(), "una fila por step en " + e );
      for ( List<String> f : t.filas ) {
        falso( f.get( 3 ).contains( "`" ), "texto plano sin backticks: " + f );
        falso( f.get( 3 ).contains( "&lt;" ), "texto plano sin entidades: " + f );
      }
      // el mismo Mermaid que generate_report
      ReportMotor.Opciones op = new ReportMotor.Opciones();
      op.idioma = "es";
      op.campos = false;
      String md = ReportMotor.informe( c.captura().copia, op ).markdown;
      contiene( md, "```mermaid\n" + d.mermaid + "```", "mismo Mermaid que generate_report en " + e );
      System.out.println( "  " + e + ": " + s.resumen );
    }
    contiene( new SecFlujo( false ).armar( ctx( "06", "es" ) ).resumen, "hop de error", "06 tiene hop de error" );
    contiene( new SecFlujo( false ).armar( ctx( "03", "en" ) ).resumen, "No error hops.", "03 en" );
  }

  public void pruebaFlujoJob() throws Exception {
    Seccion s = new SecFlujo( true ).armar( ctx( "10", "es" ) );
    igual( SeccionId.FLUJO_JOB, s.id, "id" );
    Diagrama d = bloques( s, Diagrama.class ).get( 0 );
    igual( Diagrama.JOB, d.clase, "clase" );
    cierto( d.mermaid.startsWith( "flowchart LR\n" ), "mermaid" );
    Tabla ej = tabla( s, "job-flow-ejecuta" );
    noNulo( ej, "tabla que ejecuta" );
    igual( 2, ej.filas.size(), "10 ejecuta dos transformaciones" );
    for ( List<String> f : ej.filas ) {
      igual( "sí", f.get( 3 ), "existe " + f );
      falso( f.get( 2 ).contains( "${" ), "ruta resuelta: " + f );
      falso( f.get( 2 ).contains( ":\\" ) || f.get( 2 ).startsWith( "file:" ), "ruta relativa: " + f );
    }
    cierto( bloques( s, Aviso.class ).isEmpty(), "sin avisos de archivos faltantes" );
    System.out.println( "  10: " + s.resumen + " | " + ej.filas );
    // tipos y «que hace» de las entradas especiales en castellano, no los de Kettle en ingles
    Tabla en = tabla( s, "job-flow-entradas" );
    String filas = en.filas.toString() + d.mermaid;
    System.out.println( "  10 entradas: " + en.filas );
    noContiene( filas, "Special entries", "START no es «Special entries»" );
    noContiene( filas, "Abort job", "Abort en castellano" );
    noContiene( filas, "Abort the job", "que hace de Abort" );
    noContiene( filas, "Transformation", "Transformation en castellano" );
    for ( List<String> f : en.filas ) {
      if ( "START".equals( f.get( 1 ) ) ) {
        igual( "Inicio (SPECIAL)", f.get( 2 ), "tipo de START" );
      } else if ( "Success".equals( f.get( 1 ) ) ) {
        igual( "Éxito (SUCCESS)", f.get( 2 ), "tipo de Success" );
        igual( "Termina el job con éxito.", f.get( 3 ), "que hace Success" );
      } else if ( "Abortar".equals( f.get( 1 ) ) ) {
        igual( "Abortar el job (ABORT)", f.get( 2 ), "tipo de Abort" );
        contiene( f.get( 3 ), "Aborta el job", "que hace Abort" );
      } else if ( "Generar constantes".equals( f.get( 1 ) ) ) {
        igual( "Transformación (TRANS)", f.get( 2 ), "tipo de una transformacion" );
      }
    }

    Seccion s11 = new SecFlujo( true ).armar( ctx( "11", "en" ) );
    Tabla ej11 = tabla( s11, "job-flow-ejecuta" );
    igual( 1, ej11.filas.size(), "11 ejecuta un job" );
    igual( "Job", ej11.filas.get( 0 ).get( 1 ), "tipo" );
    igual( "10_job_secuencia.kjb", ej11.filas.get( 0 ).get( 2 ), "archivo relativo" );
    contiene( s11.resumen, "Runs 1 job (10_job_secuencia.kjb).", "resumen 11" );
  }

  public void pruebaNoAplica() throws Exception {
    Captura t = EntornoKettle.captura( "03" );
    Captura j = EntornoKettle.captura( "10" );
    nulo( new SecFlujo( false ).noAplica( t, "es" ), "flow aplica a trans" );
    igual( "Solo para transformaciones", new SecFlujo( false ).noAplica( j, "es" ), "flow en un job" );
    igual( "Only for jobs", new SecFlujo( true ).noAplica( t, "en" ), "job_flow en una trans" );
    nulo( new SecFlujo( true ).noAplica( j, "pt" ), "job_flow aplica a jobs" );
    igual( "Somente para transformações", new SecDiccionario().noAplica( j, "pt" ), "dictionary en un job" );
    nulo( new SecValidacion().noAplica( j, "es" ), "validation en jobs" );
  }

  // ---------------------------------------------------------------- diccionario

  public void pruebaDiccionario() throws Exception {
    Seccion s = new SecDiccionario().armar( ctx( "03", "es" ) );
    igual( Seccion.OK, s.estado, "estado 03: " + s.motivo );
    Tabla t = tabla( s, "dictionary-salida-1" );
    noNulo( t, "tabla de la salida final" );
    igual( "Salida final: Escribir totales", t.titulo, "titulo" );
    igual( 7, t.columnas.size(), "columnas" );
    cierto( t.filas.size() >= 2, "campos de la salida: " + t.filas );
    contiene( s.resumen, "1 salida final («Escribir totales») con", "resumen 03" );
    cierto( tabla( s, "dictionary-anexo-1" ) != null, "anexo con los demas steps" );
    System.out.println( "  03: " + s.resumen );
    // la salida final es lo que el step escribe: el orden y los formatos que declara (no la mascara del flujo)
    igual( "producto", t.filas.get( 0 ).get( 0 ), "orden del archivo: " + t.filas );
    for ( List<String> f : t.filas ) {
      if ( "importe_total".equals( f.get( 0 ) ) || "importe_medio".equals( f.get( 0 ) ) ) {
        igual( "0.00", f.get( 4 ), "formato declarado de " + f.get( 0 ) );
      } else if ( "unidades".equals( f.get( 0 ) ) || "num_ventas".equals( f.get( 0 ) ) ) {
        igual( "0", f.get( 4 ), "formato declarado de " + f.get( 0 ) );
      }
    }
    contiene( t.pie, "como los escribe «Escribir totales»", "el pie dice de donde salen orden y formato" );
    igual( 6, s.datos.path( "outputs" ).get( 0 ).path( "written_fields" ).size(), "datos: los campos escritos" );
    Tabla t4 = tabla( new SecDiccionario().armar( ctx( "04", "en" ) ), "dictionary-salida-1" );
    List<String> nombres4 = new ArrayList<String>();
    for ( List<String> f : t4.filas ) {
      nombres4.add( f.get( 0 ) );
    }
    igual( java.util.Arrays.asList( "venta_id", "fecha_venta", "cliente_id", "cliente", "ciudad", "producto",
      "cantidad", "precio_unitario" ), nombres4, "04: las columnas en el orden del archivo" );

    Seccion s2 = new SecDiccionario().armar( ctx( "02", "en" ) );
    igual( 2, s2.datos.path( "final_outputs" ).asInt(), "02 tiene dos salidas finales (filtro true/false)" );
    contiene( s2.resumen, "2 final outputs", "resumen 02" );

    Seccion s7 = new SecDiccionario().armar( ctx( "07", "es" ) );
    igual( Seccion.PARCIAL, s7.estado, "07 sin base: parcial" );
    contiene( s7.motivo, "Escribir consulta", "motivo de 07" );
    cierto( !bloques( s7, Aviso.class ).isEmpty(), "aviso del error de 07" );
    for ( Aviso a : bloques( s7, Aviso.class ) ) {
      contiene( a.texto, "(salida/demo.sqlite)", "ruta relativa a la carpeta del documento: " + a.texto );
      noContiene( a.texto, ":\\", "sin ruta absoluta (riesgo 20): " + a.texto );
    }
    Seccion s7pt = new SecDiccionario().armar( ctx( "07", "pt" ) );
    contiene( s7pt.motivo, "“Escribir consulta”", "comillas del portugues" );
    noContiene( s7pt.motivo, "«", "sin comillas del castellano en portugues" );
    System.out.println( "  07: " + s7.estado + " / " + s7.motivo );
  }

  // ---------------------------------------------------------------- validacion

  public void pruebaValidacion() throws Exception {
    for ( String e : new String[] { "03", "06", "10" } ) {
      Seccion s = new SecValidacion().armar( ctx( e, "es" ) );
      igual( Seccion.OK, s.estado, "estado de " + e );
      Indicadores ind = bloques( s, Indicadores.class ).get( 0 );
      igual( 2, ind.items.size(), "errores y advertencias" );
      cierto( s.datos.has( "errors" ) && s.datos.has( "warnings" ), "datos de validate" );
      System.out.println( "  " + e + ": " + s.resumen );
    }
    Seccion s7 = new SecValidacion().armar( ctx( "07", "en" ) );
    System.out.println( "  07: " + s7.resumen );
    noNulo( s7.resumen, "resumen de 07" );
    // 07: Kettle no valido ningun step (la base no existe): no puede decir «sin errores»
    igual( Seccion.PARCIAL, s7.estado, "07 parcial" );
    noContiene( s7.resumen, "No errors or warnings", "no dice que la validacion esta limpia" );
    contiene( s7.resumen, "2 of 2 steps were not validated", "dice cuantos quedaron sin validar" );
    Indicadores ind7 = bloques( s7, Indicadores.class ).get( 0 );
    igual( 3, ind7.items.size(), "errores, advertencias y sin validar" );
    nulo( ind7.items.get( 0 ).nivel, "0 errores no es ok si no se valido" );
    nulo( ind7.items.get( 1 ).nivel, "0 advertencias no es ok si no se valido" );
    igual( "warn", ind7.items.get( 2 ).nivel, "sin validar en amarillo" );
    igual( "2", ind7.items.get( 2 ).valor, "dos sin validar" );
    cierto( s7.datos.path( "valid" ).isNull(), "datos.valid null: no se sabe" );
    falso( s7.datos.path( "complete" ).asBoolean( true ), "datos.complete false" );
    contiene( s7.motivo, "(salida/demo.sqlite)", "ruta relativa en el motivo: " + s7.motivo );
    noContiene( s7.motivo, ":\\", "sin ruta absoluta en el motivo" );

    // los chequeos propios de Pentaho Live salen traducidos y sin nombres de tools del MCP
    ContextoKettle c3 = ctx( "03", "es" );
    TransMeta tm = (TransMeta) c3.captura().copia;
    StepMeta leer = tm.findStep( "Leer ventas" );
    StepMeta extra = new StepMeta( "Dummy", "Otra salida", new org.pentaho.di.trans.steps.dummytrans.DummyTransMeta() );
    tm.addStep( extra );
    tm.addTransHop( new org.pentaho.di.trans.TransHopMeta( leer, extra ) );
    leer.setDistributes( true );
    Seccion s3 = new SecValidacion().armar( c3 );
    Tabla msj = tabla( s3, "validation-mensajes" );
    noNulo( msj, "tabla de mensajes" );
    String todo = msj.filas.toString();
    contiene( todo, "Tiene 2 salidas en modo «distribuir»", "el chequeo propio en castellano: " + todo );
    noContiene( todo, "set_output_mode", "sin nombres de tools del MCP" );
    noContiene( todo, "DISTRIBUTE mode", "sin el texto en ingles del MCP" );
    // la tool validate (MCP) sigue con su texto
    contiene( RunMotor.validar( tm, false, null, null ).toString(), "set_output_mode", "validate sin cambios" );
  }

  // ---------------------------------------------------------------- nombres de los tipos

  public void pruebaNombresDePlugins() throws Exception {
    EntornoKettle.iniciar();
    Class<org.pentaho.di.core.plugins.StepPluginType> st = org.pentaho.di.core.plugins.StepPluginType.class;
    Class<org.pentaho.di.core.plugins.JobEntryPluginType> je = org.pentaho.di.core.plugins.JobEntryPluginType.class;
    igual( "Stream lookup", NombresPlugin.nombre( st, "StreamLookup", "en" ), "en, sin importar el idioma de la JVM" );
    igual( "Búsqueda en flujo de datos", NombresPlugin.nombre( st, "StreamLookup", "es" ), "es de Kettle" );
    igual( "Busca em fluxo", NombresPlugin.nombre( st, "StreamLookup", "pt" ), "pt propio" );
    igual( "Text file output", NombresPlugin.nombre( st, "TextFileOutput", "en" ), "en" );
    igual( "Entrada de tabela", NombresPlugin.nombre( st, "TableInput", "pt" ), "pt" );
    igual( "Transformation", NombresPlugin.nombre( je, "TRANS", "en" ), "entrada en ingles" );
    igual( "Transformación", NombresPlugin.nombre( je, "TRANS", "es" ), "entrada en castellano" );
    igual( "Abort job", NombresPlugin.nombre( je, "ABORT", "en" ), "anotada (@JobEntry), en ingles" );
    igual( "Abortar o job", NombresPlugin.nombre( je, "ABORT", "pt" ), "anotada, en portugues" );
    igual( "NoExiste", NombresPlugin.nombre( st, "NoExiste", "pt" ), "sin plugin: el id" );
    igual( "Stream lookup (StreamLookup)", NombresPlugin.conId( st, "StreamLookup", "en" ), "con id" );

    // 04 en ingles: ningun tipo en castellano en la tabla ni en el Mermaid
    Seccion f4 = new SecFlujo( false ).armar( ctx( "04", "en" ) );
    String tipos = tabla( f4, "flow-steps" ).filas.toString() + bloques( f4, Diagrama.class ).get( 0 ).mermaid;
    contiene( tipos, "Stream lookup (StreamLookup)", "tipo en ingles" );
    noContiene( tipos, "Búsqueda en flujo", "sin el nombre en castellano" );
    noContiene( tipos, "Fichero", "sin el nombre en castellano" );
    // 07 en portugues
    Seccion f7 = new SecFlujo( false ).armar( ctx( "07", "pt" ) );
    String tipos7 = tabla( f7, "flow-steps" ).filas.toString() + bloques( f7, Diagrama.class ).get( 0 ).mermaid;
    contiene( tipos7, "Entrada de tabela (TableInput)", "tipo en portugues" );
    noContiene( tipos7, "Entrada Tabla", "sin el nombre en castellano" );
  }

  // ---------------------------------------------------------------- conexiones

  public void pruebaConexiones() throws Exception {
    Seccion s7 = new SecConexiones().armar( ctx( "07", "es" ) );
    Tabla c = tabla( s7, "connections-conexiones" );
    noNulo( c, "tabla de conexiones" );
    igual( 1, c.filas.size(), "una conexion" );
    igual( "sqlite_demo", c.filas.get( 0 ).get( 0 ), "nombre" );
    igual( "Consultar SQLite", c.filas.get( 0 ).get( 5 ), "usada por" );
    String json = ModeloJson.escribir( s7, false ).toLowerCase();
    noContiene( json, "password", "nunca contrasenas" );
    noContiene( json, "username", "nunca usuarios" );
    contiene( s7.resumen, "1 conexión", "resumen 07" );

    Seccion s6 = new SecConexiones().armar( ctx( "06", "es" ) );
    Tabla p = tabla( s6, "connections-parametros" );
    igual( "SALDO_MINIMO", p.filas.get( 0 ).get( 0 ), "parametro" );
    igual( "1000", p.filas.get( 0 ).get( 1 ), "valor por defecto" );
    igual( "Sin conexiones a bases de datos ni parámetros.", new SecConexiones().armar( ctx( "03", "es" ) ).resumen,
      "03 sin nada" );
    contiene( new SecConexiones().armar( ctx( "11", "en" ) ).resumen, "1 parameter", "11 tiene un parametro" );
  }

  // ---------------------------------------------------------------- conclusiones

  public void pruebaNarrativa() throws Exception {
    Seccion vacia = new SecNarrativa().armar( ctx( "03", "es" ) );
    igual( Seccion.OMITIDA, vacia.estado, "sin texto: omitida" );
    igual( "No hay texto de conclusiones.", vacia.motivo, "motivo" );

    Narrativa n = new Narrativa();
    n.origen = Narrativa.IA;
    StringBuilder largo = new StringBuilder( "**Conclusión.** " );
    while ( largo.length() < 3000 ) {
      largo.append( "El cuello de botella es la lectura <b>&\"x. " );
    }
    n.conclusiones = largo.toString();
    Seccion s = new SecNarrativa().armar( ctx( "03", "es", new Tema(), n ) );
    igual( Seccion.OK, s.estado, "con texto" );
    Parrafo p = bloques( s, Parrafo.class ).get( 0 );
    cierto( p.ia, "ia = true" );
    cierto( p.texto.length() <= InformeServicio.MAX_NARRATIVA, "recortado a 2000: " + p.texto.length() );
    contiene( p.texto, "<b>&\"x", "el texto va tal cual (lo escapa el renderizador)" );
  }

  // ---------------------------------------------------------------- contexto

  public void pruebaContexto() throws Exception {
    ContextoKettle c = ctx( "03", "es" );
    List<StepMeta> orden = c.orden();
    igual( 5, orden.size(), "orden de 03" );
    cierto( c.orden() == orden, "orden memorizado" );
    StepMeta ultimo = orden.get( orden.size() - 1 );
    RowMetaInterface rm = c.salida( ultimo );
    cierto( rm.size() > 0, "campos de salida" );
    cierto( c.salida( ultimo ) == rm, "memo de salida" );
    RowMetaInterface en = c.entrada( ultimo );
    cierto( en.size() > 0, "campos de entrada" );
    cierto( c.entrada( ultimo ) == en, "memo de entrada" );
    cierto( c.msCamposGastados() < ContextoKettle.MS_CAMPOS, "presupuesto de campos" );
    long r1 = c.msRestantes();
    cierto( r1 > 0 && r1 <= new Pedido().timeoutMs, "plazo: " + r1 );
    c.aviso( "uno" );
    c.aviso( "uno" );
    c.aviso( " " );
    igual( 1, c.avisos().size(), "avisos sin repetir ni vacios" );
    igual( "Linaje de campos", c.rotulos( SeccionId.AREA ).s( SeccionId.LINAJE ), "rotulos del idioma" );

    // un error queda memorizado y se relanza
    ContextoKettle c7 = ctx( "07", "es" );
    final StepMeta tabla = ( (TransMeta) c7.captura().copia ).findStep( "Consultar SQLite" );
    KettleStepException e1 = lanza( KettleStepException.class, () -> c7.salida( tabla ), "07 sin base" );
    KettleStepException e2 = lanza( KettleStepException.class, () -> c7.salida( tabla ), "07 otra vez" );
    cierto( e1 == e2, "el error tambien va al memo" );

    // jobs: sin orden ni campos
    final ContextoKettle j = ctx( "10", "en" );
    cierto( j.orden().isEmpty(), "orden vacio en un job" );
    lanza( KettleStepException.class, () -> j.salida( null ), "salida en un job" );

    // cancelacion por el Progreso del pedido
    final boolean[] cancelar = { false };
    Pedido p = new Pedido();
    ContextoKettle cc = new ContextoKettle( EntornoKettle.captura( "03" ), p, new Tema(), new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
      }

      @Override
      public boolean cancelado() {
        return cancelar[ 0 ];
      }
    } );
    falso( cc.cancelado(), "no cancelado" );
    cancelar[ 0 ] = true;
    cierto( cc.cancelado(), "cancelado por el Progreso" );
    cierto( cc.progreso().cancelado(), "progreso() ve la cancelacion" );
    lanza( KettleStepException.class, () -> cc.salida( cc.orden().get( 0 ) ), "sin campos despues de cancelar" );

    // plazo agotado: sin campos
    Pedido rapido = new Pedido();
    rapido.timeoutMs = 0;
    final ContextoKettle cero = new ContextoKettle( EntornoKettle.captura( "03" ), rapido, new Tema(), null );
    igual( 0L, cero.msRestantes(), "plazo cero" );
    lanza( KettleStepException.class, () -> cero.salida( cero.orden().get( 0 ) ), "sin plazo no calcula" );
  }

  // ---------------------------------------------------------------- textos

  public void pruebaTextos() throws Exception {
    Properties en = props( "en" );
    Properties es = props( "es" );
    Properties pt = props( "pt" );
    cierto( en.size() > 100, "estructura_en tiene textos: " + en.size() );
    igual( new TreeSet<Object>( en.keySet() ), new TreeSet<Object>( es.keySet() ), "mismas claves en en y es" );
    igual( new TreeSet<Object>( en.keySet() ), new TreeSet<Object>( pt.keySet() ), "mismas claves en en y pt" );
    for ( String i : new String[] { "en", "es", "pt" } ) {
      Rotulos r = Rotulos.de( InformeServicio.AREA, i );
      for ( Object k : en.keySet() ) {
        String v = r.s( (String) k );
        falso( v.trim().isEmpty(), "texto vacio: " + i + " " + k );
      }
    }
    igual( "1 step", Txt.n( Rotulos.de( InformeServicio.AREA, "en" ), "n.step", 1 ), "singular" );
    igual( "1.200 campos", Txt.n( Rotulos.de( InformeServicio.AREA, "es" ), "n.campo", 1200 ), "miles en es" );
    igual( "a y b", Txt.lista( Rotulos.de( InformeServicio.AREA, "es" ), java.util.Arrays.asList( "a", "b" ) ),
      "lista de 2" );
    igual( "a, b and c", Txt.lista( Rotulos.de( InformeServicio.AREA, "en" ), java.util.Arrays.asList( "a", "b",
      "c" ) ), "lista de 3" );
    igual( "a, b, e c", Txt.clausulas( Rotulos.de( InformeServicio.AREA, "pt" ), java.util.Arrays.asList( "a", "b",
      "c" ) ), "clausulas de 3" );
  }

  public void pruebaPlano() {
    igual( "Lee: C:/x/a|b.csv", Txt.plano( "Lee: `C:/x/a\\|b.csv`" ), "codigo con barra" );
    igual( "a`b", Txt.plano( "`` a`b ``" ), "codigo con backtick" );
    igual( "x | y <b>", Txt.plano( "x \\| y &lt;b&gt;" ), "escapes de celda" );
    igual( "Une LEFT OUTER: cliente_id = cliente_id", Txt.plano( "Une LEFT OUTER: `cliente_id` = `cliente_id`" ),
      "dos tramos de codigo" );
    igual( "sin cierre `abc", Txt.plano( "sin cierre `abc" ), "backtick suelto" );
    igual( "ventas.csv", Txt.baseNombre( "file:///C:/Users/x%20y/datos/ventas.csv" ), "nombre de una URI" );
    igual( "C:\\Users\\x y\\datos\\ventas.csv", Txt.local( "file:///C:/Users/x%20y/datos/ventas.csv" ),
      "ruta local de una URI" );
    igual( "datos/ventas.csv", Txt.relativa( "file:///C:/ej/datos/ventas.csv", "C:\\ej\\03.ktr" ), "relativa" );
    igual( "a_b_c", Txt.nombreArchivo( "a/b:c", 80 ), "nombre de archivo" );
  }

  private static Properties props( String idioma ) throws Exception {
    Properties p = new Properties();
    try ( InputStream in = PruebaEstructura.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA
      + InformeServicio.AREA + "_" + idioma + ".properties" ) ) {
      noNulo( in, "estructura_" + idioma + ".properties en el classpath" );
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[ 8192 ];
      int n;
      while ( ( n = in.read( b ) ) > 0 ) {
        bo.write( b, 0, n );
      }
      p.load( new StringReader( new String( bo.toByteArray(), StandardCharsets.UTF_8 ) ) );
    }
    return p;
  }
}
