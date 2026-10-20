package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import org.pentaho.di.core.extension.ExtensionPoint;
import org.pentaho.di.core.extension.ExtensionPointPluginType;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Metricas de ejecucion (WP4, diseno §2.4.2 y §8.1): run_copy de 03 y de los jobs 10 y 11 (con Gantt),
 * la ultima corrida (con y sin metricas, y en curso), las trampas de fechas y de idioma, la
 * confirmacion de "Medir..." y el MetricasHook (apagado por defecto).
 *
 * @dueño WP4
 */
public class PruebaMetricas {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  public void cerrar() {
    System.clearProperty( MetricasHook.PROPIEDAD );
  }

  // ---------------------------------------------------------------- ayudas

  static ContextoPrueba contexto( Captura c, String idioma, String metricas ) {
    Pedido p = new Pedido();
    p.idioma = idioma;
    p.metricas = metricas;
    return new ContextoPrueba( c, p, new Tema() );
  }

  static Grafico grafico( Seccion s, String clase ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Grafico && clase.equals( ( (Grafico) b ).clase ) ) {
        return (Grafico) b;
      }
    }
    return null;
  }

  static Indicadores indicadores( Seccion s ) {
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Indicadores ) {
        return (Indicadores) b;
      }
    }
    return null;
  }

  static Indicador indicador( Seccion s, String etiqueta ) {
    for ( Indicador i : indicadores( s ).items ) {
      if ( etiqueta.equals( i.etiqueta ) ) {
        return i;
      }
    }
    throw new AssertionError( "no hay indicador " + etiqueta );
  }

  static int tramos( Grafico g, String tipo ) {
    int n = 0;
    for ( Tramo t : g.tramos ) {
      if ( tipo.equals( t.tipo ) ) {
        n++;
      }
    }
    return n;
  }

  static JsonNode paso( JsonNode pasos, String nombre ) {
    for ( JsonNode p : pasos ) {
      if ( nombre.equals( p.get( "step" ).asText() ) ) {
        return p;
      }
    }
    throw new AssertionError( "no hay step " + nombre + " en " + pasos );
  }

  static JsonNode entrada( JsonNode d, String nombre, int nivel ) {
    for ( JsonNode e : d.get( "entries" ) ) {
      if ( nombre.equals( e.get( "entry" ).asText() ) && e.get( "level" ).asInt() == nivel ) {
        return e;
      }
    }
    throw new AssertionError( "no hay entrada " + nombre + " de nivel " + nivel + " en " + d.get( "entries" ) );
  }

  /** El anio de una fecha ISO del JSON (la trampa de getStartDate() = 1900). */
  static int anio( JsonNode d, String campo ) {
    return Integer.parseInt( d.get( campo ).asText().substring( 0, 4 ) );
  }

  /** Corre una transformacion como lo haria Spoon (la "ultima corrida"), sin las ayudas de CorridaMedida. */
  static Trans correr( TransMeta tm, boolean metricas, boolean esperar ) throws Exception {
    Trans t = new Trans( tm );
    t.setLogLevel( LogLevel.MINIMAL );
    t.setGatheringMetrics( metricas );
    tm.setInternalKettleVariables( t );
    t.copyParametersFrom( tm );
    t.activateParameters();
    t.prepareExecution( null );
    t.startThreads();
    if ( esperar ) {
      t.waitUntilFinished();
    }
    return t;
  }

  // ---------------------------------------------------------------- transformacion 03

  /** "Medir..." de 03: Gantt real (init y exec), filas, tabla, datos sin 1900, y escribio su salida. */
  public void pruebaCopia03() throws Exception {
    Captura c = EntornoKettle.captura( "03" );
    File salida = new File( EntornoKettle.ejemplos(), "salida" );
    long antes = System.currentTimeMillis() - 1000;
    Seccion s = new SecMetricas().armar( contexto( c, "es", Pedido.METRICAS_COPIA ) );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    JsonNode d = s.datos;
    igual( "run_metrics", d.get( "shape" ).asText(), "forma" );
    igual( "transformation", d.get( "kind" ).asText(), "tipo" );
    igual( "run_copy", d.get( "source" ).asText(), "fuente" );
    igual( "finished", d.get( "state" ).asText(), "estado de la corrida" );
    igual( "real", d.get( "gantt" ).asText(), "con metricas: Gantt real" );
    cierto( anio( d, "start" ) >= 2020, "inicio real y no 1900-01-01: " + d.get( "start" ) );
    cierto( anio( d, "end" ) >= 2020, "fin real: " + d.get( "end" ) );
    cierto( d.get( "duration_ms" ).asLong() > 0 && d.get( "duration_ms" ).asLong() < 60000, "duracion: " + d.get(
      "duration_ms" ) );
    igual( 10L, d.get( "rows_read" ).asLong(), "filas leidas = las que escribio Leer ventas" );
    igual( 4L, d.get( "rows_written" ).asLong(), "filas escritas = las de Escribir totales" );
    igual( 0L, d.get( "errors" ).asLong(), "sin errores" );
    igual( 5, d.get( "steps" ).size(), "5 steps" );
    JsonNode leer = paso( d.get( "steps" ), "Leer ventas" );
    igual( "STATUS_FINISHED", leer.get( "status" ).asText(), "estado por enum, no traducido" );
    cierto( leer.get( "source" ).asBoolean(), "Leer ventas es fuente" );
    cierto( paso( d.get( "steps" ), "Escribir totales" ).get( "writes" ).asBoolean(), "Escribir totales escribe" );
    cierto( leer.has( "exec_start_ms" ) && leer.get( "exec_start_ms" ).asLong() >= 0, "tramo exec relativo" );

    // bloques
    igual( "10", indicador( s, "Filas leídas" ).valor, "indicador filas leidas" );
    igual( "4", indicador( s, "Filas escritas" ).valor, "indicador filas escritas" );
    igual( Indicador.OK, indicador( s, "Errores" ).nivel, "errores en verde" );
    noNulo( indicador( s, "Step más lento" ).valor, "step mas lento" );
    Grafico gantt = grafico( s, Grafico.GANTT );
    noNulo( gantt, "Gantt" );
    igual( 5, tramos( gantt, Tramo.INIT ), "5 tramos init" );
    igual( 5, tramos( gantt, Tramo.EXEC ), "5 tramos exec" );
    for ( Tramo t : gantt.tramos ) {
      cierto( t.inicioMs >= 0 && t.finMs >= t.inicioMs && t.finMs <= d.get( "duration_ms" ).asLong() + 50,
        "tramo dentro de la corrida: " + t.categoria + " " + t.inicioMs + "-" + t.finMs );
    }
    Grafico barras = grafico( s, Grafico.BARRAS_H );
    noNulo( barras, "barras" );
    igual( 2, barras.series.size(), "filas y ms" );
    igual( 5, barras.categorias.size(), "un step por barra" );
    Tabla t = null;
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla ) {
        t = (Tabla) b;
      }
    }
    noNulo( t, "tabla de steps" );
    igual( 5, t.filas.size(), "una fila por step" );
    cierto( t.columnas.contains( "Estado" ), "columna estado" );
    boolean terminado = false;
    for ( List<String> f : t.filas ) {
      terminado |= f.contains( "Terminado" );
    }
    cierto( terminado, "estado traducido por nosotros (es): Terminado" );
    contiene( s.resumen, "procesó 10 filas", "resumen" );
    contiene( s.resumen, "más lento", "resumen con el step mas lento" );
    cierto( PruebaPerfil.hayAviso( s, "metrics-copy" ), "avisa que la copia escribio" );

    // escribio de verdad y el JSON no tiene 1900
    boolean escribio = false;
    File[] fs = salida.listFiles();
    if ( fs != null ) {
      for ( File f : fs ) {
        escribio |= f.getName().startsWith( "03_ventas_por_producto" ) && f.lastModified() >= antes;
      }
    }
    cierto( escribio, "Medir escribio la salida de 03 en la copia de ej" );
    String json = ModeloJson.escribir( s, false );
    EntornoKettle.escribir( new File( EntornoKettle.salida( "metricas" ), "metrics_03_run_copy.json" ), ModeloJson
      .escribir( s, true ) );
    noContiene( json, "1900", "ninguna fecha de 1900 en el JSON" );
    igual( s.bloques.size(), ModeloJson.leer( json, Seccion.class ).bloques.size(), "ida y vuelta JSON" );
  }

  /** "Medir..." de la copia enorme de 03: hay snapshots, asi que sale la linea de filas por intervalo. */
  public void pruebaCopiaGrandeLinea() throws Exception {
    File dir = PruebaPerfil.grande( PruebaPerfil.FILAS_ENORME );
    Captura c = PruebaPerfil.capturaDe( new File( dir, "03_ventas_por_producto.ktr" ) );
    Seccion s = new SecMetricas().armar( contexto( c, "es", Pedido.METRICAS_COPIA ) );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    igual( (long) PruebaPerfil.FILAS_ENORME, s.datos.get( "rows_read" ).asLong(), "todas las filas" );
    Grafico linea = grafico( s, Grafico.LINEA );
    if ( linea == null ) {
      Verificador.saltear( "la corrida duro " + s.datos.get( "duration_ms" ) + " ms: menos de 3 snapshots" );
    }
    cierto( linea.categorias.size() >= 3, "al menos 3 instantes: " + linea.categorias );
    cierto( linea.series.size() >= 1 && linea.series.size() <= SecMetricas.MAX_SERIES, "1 a 5 series" );
    contiene( linea.categorias.get( linea.categorias.size() - 1 ), " s", "eje X en segundos" );
    double total = 0;
    for ( Double v : linea.series.get( 0 ).valores ) {
      total += v == null ? 0 : v;
    }
    cierto( total > 0, "la serie con mas filas tiene filas" );
    cierto( s.datos.get( "snapshots" ).get( "at_ms" ).size() >= 3, "datos: snapshots" );
    contiene( s.resumen, "400.000 filas", "miles con punto en el resumen (es)" );
  }

  /** Numeros y duraciones con el idioma del informe, aunque la JVM tenga otro locale. */
  public void pruebaFormatoIdioma() throws Exception {
    Locale antes = Locale.getDefault();
    try {
      Locale.setDefault( new Locale( "es", "ES" ) );
      Rotulos en = Rotulos.de( SecMetricas.AREA, "en" );
      Rotulos es = Rotulos.de( SecMetricas.AREA, "es" );
      Rotulos pt = Rotulos.de( SecMetricas.AREA, "pt" );
      igual( "2.7 s", SecMetricas.duracion( 2700, en ), "en con la JVM en es_ES" );
      igual( "400,000", SecMetricas.entero( 400000, en ), "miles en ingles" );
      Locale.setDefault( Locale.US );
      igual( "2,7 s", SecMetricas.duracion( 2700, es ), "es con la JVM en en_US" );
      igual( "400.000", SecMetricas.entero( 400000, es ), "miles en castellano" );
      igual( "2,7 s", SecMetricas.duracion( 2700, pt ), "pt" );
      igual( "227 ms", SecMetricas.duracion( 227, es ), "menos de un segundo" );
      igual( "3 min 05 s", SecMetricas.duracion( 185000, es ), "minutos" );
      igual( "1 h 02 min", SecMetricas.duracion( 3720000, en ), "horas" );

      Locale.setDefault( new Locale( "es", "ES" ) );
      Seccion s = new SecMetricas().armar( contexto( EntornoKettle.captura( "03" ), "en", Pedido.METRICAS_COPIA ) );
      igual( Seccion.OK, s.estado, "estado en" );
      contiene( s.resumen, "processed 10 rows", "resumen en ingles" );
      contiene( s.resumen, "“", "comillas del ingles" );
      noContiene( s.resumen, "«", "sin comillas del castellano" );
      cierto( s.datos.get( "start" ).asText().matches( "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-Z].*" ),
        "ISO-8601 con zona y digitos ASCII: " + s.datos.get( "start" ) );
      boolean terminado = false;
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Tabla ) {
          for ( List<String> f : ( (Tabla) b ).filas ) {
            terminado |= f.contains( "Finished" );
          }
        }
      }
      cierto( terminado, "estado en ingles aunque Kettle y la JVM esten en castellano" );
    } finally {
      Locale.setDefault( antes );
    }
  }

  /** Ultima corrida sin "Gather performance metrics": Gantt aproximado y lo dice. */
  public void pruebaUltimaSinMetricas() throws Exception {
    Captura c = EntornoKettle.captura( "03" );
    c.ultimaTrans = correr( EntornoKettle.trans( "03" ), false, true );
    cierto( SecMetricas.hayCorrida( c ), "hay corrida: marcada por defecto" );
    Seccion s = new SecMetricas().armar( contexto( c, "es", Pedido.METRICAS_ULTIMA ) );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    igual( "last_run", s.datos.get( "source" ).asText(), "fuente" );
    igual( "approximate", s.datos.get( "gantt" ).asText(), "sin metricas: aproximado" );
    cierto( anio( s.datos, "start" ) >= 2020, "inicio con getCurrentDate y no 1900: " + s.datos.get( "start" ) );
    Grafico g = grafico( s, Grafico.GANTT );
    igual( 0, tramos( g, Tramo.INIT ), "sin init" );
    igual( 5, tramos( g, Tramo.EXEC ), "un tramo por step" );
    for ( Tramo t : g.tramos ) {
      igual( 0L, t.inicioMs, "aproximado: de 0 a su runtime (" + t.categoria + ")" );
    }
    cierto( PruebaPerfil.hayAviso( s, "metrics-approx" ), "avisa que es aproximado" );
    falso( PruebaPerfil.hayAviso( s, "metrics-copy" ), "no dice que escribio una copia" );
    contiene( s.resumen, "La última ejecución procesó 10 filas", "resumen" );
    igual( 10L, s.datos.get( "rows_read" ).asLong(), "filas leidas" );
  }

  /** Ultima corrida con metricas: Gantt real. */
  public void pruebaUltimaConMetricas() throws Exception {
    Captura c = EntornoKettle.captura( "03" );
    c.ultimaTrans = correr( EntornoKettle.trans( "03" ), true, true );
    Seccion s = new SecMetricas().armar( contexto( c, "pt", Pedido.METRICAS_ULTIMA ) );
    igual( Seccion.OK, s.estado, "estado" );
    igual( "real", s.datos.get( "gantt" ).asText(), "con metricas: real" );
    igual( 5, tramos( grafico( s, Grafico.GANTT ), Tramo.INIT ), "init de cada step" );
    contiene( s.resumen, "A última execução processou 10 linhas", "resumen en portugues" );
  }

  /** La corrida sigue en curso: seccion parcial con el motivo. */
  public void pruebaEnCurso() throws Exception {
    File dir = PruebaPerfil.grande( PruebaPerfil.FILAS_ENORME );
    File ktr = new File( dir, "03_ventas_por_producto.ktr" );
    Captura c = PruebaPerfil.capturaDe( ktr );
    Trans t = correr( new TransMeta( ktr.getAbsolutePath() ), true, false );
    try {
      RunMotor.dormir( 300 );
      if ( t.isFinished() ) {
        Verificador.saltear( "la corrida de " + PruebaPerfil.FILAS_ENORME + " filas termino en menos de 300 ms" );
      }
      c.ultimaTrans = t;
      Seccion s = new SecMetricas().armar( contexto( c, "es", Pedido.METRICAS_ULTIMA ) );
      igual( Seccion.PARCIAL, s.estado, "en curso: parcial" );
      contiene( s.motivo, "en curso", "motivo" );
      igual( "running", s.datos.get( "state" ).asText(), "estado de la corrida" );
      contiene( s.resumen, "lleva procesadas", "resumen de una corrida en curso" );
    } finally {
      t.stopAll();
      t.waitUntilFinished();
    }
  }

  // ---------------------------------------------------------------- jobs

  /** "Medir..." del job 10: entradas, sub-transformaciones con sus steps y Gantt anidado. */
  public void pruebaJob10() throws Exception {
    Seccion s = new SecMetricas().armar( contexto( EntornoKettle.captura( "10" ), "es", Pedido.METRICAS_COPIA ) );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    JsonNode d = s.datos;
    igual( "job", d.get( "kind" ).asText(), "tipo" );
    igual( "ok", d.get( "result" ).asText(), "resultado" );
    cierto( anio( d, "start" ) >= 2020, "inicio real: " + d.get( "start" ) );
    JsonNode gen = entrada( d, "Generar constantes", 0 );
    JsonNode ven = entrada( d, "Ventas por producto", 0 );
    entrada( d, "Success", 0 );
    cierto( gen.get( "end_ms" ).asLong() <= ven.get( "start_ms" ).asLong() + 5, "en secuencia" );
    igual( "ok", ven.get( "result" ).asText(), "resultado de la entrada" );
    cierto( ven.has( "reason" ), "el hop que se siguio (getReason)" );
    igual( 2, d.get( "transformations" ).size(), "las dos sub-transformaciones" );
    JsonNode t03 = d.get( "transformations" ).get( 1 );
    igual( "Ventas por producto", t03.get( "entry" ).asText(), "entrada de la sub-transformacion" );
    igual( 4L, paso( t03.get( "steps" ), "Escribir totales" ).get( "written" ).asLong(), "filas de 03 dentro del job" );
    cierto( d.get( "rows_written" ).asLong() >= 4, "filas escritas del job" );

    Grafico g = grafico( s, Grafico.GANTT );
    noNulo( g, "Gantt del job" );
    cierto( g.categorias.contains( "Ventas por producto" ), "fila de la entrada" );
    cierto( g.categorias.contains( "↳ Escribir totales" ), "steps anidados bajo su entrada: " + g.categorias );
    falso( g.categorias.contains( "START" ), "sin START" );
    int iEntrada = g.categorias.indexOf( "Ventas por producto" );
    int iStep = g.categorias.indexOf( "↳ Leer ventas" );
    cierto( iStep == iEntrada + 1, "el primer step justo debajo de su entrada" );
    cierto( tramos( g, Tramo.INIT ) >= 5, "init de los steps de las sub-transformaciones" );
    igual( "Bien", indicador( s, "Resultado" ).valor, "resultado" );
    contiene( s.resumen, "terminó bien", "resumen" );
    contiene( s.resumen, "Sus transformaciones leyeron", "filas en el resumen" );

    // «Llegó por» en el idioma del informe; el texto de Kettle solo en los datos
    igual( "unconditional", gen.get( "reason_code" ).asText(), "START -> Generar constantes: " + gen );
    igual( "success", ven.get( "reason_code" ).asText(), "hop «si termina bien» hacia Ventas: " + ven );
    Tabla te = null;
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla && "metrics-entries".equals( b.id ) ) {
        te = (Tabla) b;
      }
    }
    noNulo( te, "tabla de entradas" );
    String razones = te.filas.toString();
    noContiene( razones, "Followed", "sin el texto de Kettle en ingles: " + razones );
    contiene( razones, "un hop incondicional", "motivo traducido: " + razones );
    contiene( razones, "un hop «si termina bien»", "motivo traducido: " + razones );
    igual( "the start of the job", SecMetricas.razon( entradaMedida( "start" ), Rotulos.de( SecMetricas.AREA,
      "en" ) ), "start en ingles" );
    igual( "success", CorridaMedida.codigoRazon( "Followed link after success" ), "codigo desde el ingles" );
    nulo( CorridaMedida.codigoRazon( "otra cosa" ), "desconocido" );
    // la leyenda del Gantt del job no dice que las entradas corren en paralelo
    noNulo( g.leyenda, "leyenda propia del Gantt del job" );
    contiene( g.leyenda, "una tras otra", "las entradas corren en secuencia: " + g.leyenda );
    // con metricas reales, el tiempo de cada step es su barra de ejecucion del Gantt
    for ( JsonNode st : t03.get( "steps" ) ) {
      if ( st.has( "exec_start_ms" ) ) {
        igual( st.get( "exec_end_ms" ).asLong() - st.get( "exec_start_ms" ).asLong(), st.get( "time_ms" ).asLong(),
          "tiempo = barra de ejecucion de " + st.get( "step" ).asText() );
      }
    }
  }

  private static CorridaMedida.EntradaMedida entradaMedida( String codigo ) {
    CorridaMedida.EntradaMedida e = new CorridaMedida.EntradaMedida();
    e.razonCodigo = codigo;
    return e;
  }

  /** "Medir..." del job 11 (llama al 10): entradas del sub-job anidadas y sus sub-transformaciones. */
  public void pruebaJob11() throws Exception {
    Seccion s = new SecMetricas().armar( contexto( EntornoKettle.captura( "11" ), "en", Pedido.METRICAS_COPIA ) );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    JsonNode d = s.datos;
    JsonNode padre = entrada( d, "Correr job secuencia", 0 );
    JsonNode hija = entrada( d, "Generar constantes", 1 );
    igual( "10_job_secuencia", hija.get( "job" ).asText(), "la entrada es del sub-job" );
    cierto( hija.get( "start_ms" ).asLong() >= padre.get( "start_ms" ).asLong() && hija.get( "end_ms" ).asLong() <= padre
      .get( "end_ms" ).asLong() + 5, "la entrada del sub-job cae dentro de la del padre" );
    igual( 2, d.get( "transformations" ).size(), "las sub-transformaciones del sub-job (DelegationListener)" );
    Grafico g = grafico( s, Grafico.GANTT );
    cierto( g.categorias.contains( "↳ Generar constantes" ), "entrada de nivel 1: " + g.categorias );
    cierto( g.categorias.contains( "  ↳ Generar filas" ), "step de nivel 2: " + g.categorias );
    contiene( s.resumen, "finished OK", "resumen en ingles" );
    EntornoKettle.escribir( new File( EntornoKettle.salida( "metricas" ), "metrics_11_run_copy.json" ), ModeloJson
      .escribir( s, true ) );
  }

  /** Ultima corrida de un job (Spoon): el arbol del JobTracker da el Gantt; las filas no se conocen. */
  public void pruebaJobUltima() throws Exception {
    Captura c = EntornoKettle.captura( "10" );
    JobMeta jm = EntornoKettle.job( "10" );
    Job j = new Job( null, jm );
    j.setLogLevel( LogLevel.MINIMAL );
    j.initializeVariablesFrom( null );
    jm.setInternalKettleVariables( j );
    j.copyParametersFrom( jm );
    j.activateParameters();
    j.start();
    j.waitUntilFinished();
    c.ultimoJob = j;
    Seccion s = new SecMetricas().armar( contexto( c, "es", Pedido.METRICAS_ULTIMA ) );
    igual( Seccion.OK, s.estado, "estado (motivo " + s.motivo + ")" );
    igual( "last_run", s.datos.get( "source" ).asText(), "fuente" );
    entrada( s.datos, "Ventas por producto", 0 );
    falso( s.datos.has( "transformations" ), "sin sub-transformaciones (solo al medir)" );
    falso( s.datos.has( "rows_read" ), "sin filas" );
    cierto( PruebaPerfil.hayAviso( s, "metrics-job-rows" ), "explica que las filas salen al medir" );
    Grafico g = grafico( s, Grafico.GANTT );
    cierto( g.categorias.contains( "Generar constantes" ) && g.categorias.contains( "Ventas por producto" ),
      "Gantt por entrada" );
  }

  // ---------------------------------------------------------------- omitida, confirmacion, hook

  public void pruebaOmitida() throws Exception {
    SecMetricas m = new SecMetricas();
    nulo( m.noAplica( EntornoKettle.captura( "03" ), "es" ), "aplica a transformaciones" );
    nulo( m.noAplica( EntornoKettle.captura( "10" ), "es" ), "aplica a jobs" );
    Captura c = EntornoKettle.captura( "03" );
    falso( SecMetricas.hayCorrida( c ), "sin corrida no va marcada por defecto" );
    Seccion sin = m.armar( contexto( c, "es", Pedido.METRICAS_ULTIMA ) );
    igual( Seccion.OMITIDA, sin.estado, "sin corrida previa: omitida" );
    contiene( sin.motivo, "Medir", "el motivo sugiere Medir" );
    Seccion off = m.armar( contexto( c, "en", Pedido.METRICAS_NINGUNA ) );
    igual( Seccion.OMITIDA, off.estado, "metricas none: omitida" );
    Captura job = EntornoKettle.captura( "11" );
    igual( Seccion.OMITIDA, m.armar( contexto( job, "pt", null ) ).estado, "metricas null = last_run; sin corrida" );
  }

  /** Lo que la UI muestra antes de "Medir...": los steps o entradas que escriben. */
  public void pruebaConfirmacion() throws Exception {
    igual( java.util.Arrays.asList( "Escribir totales" ), CorridaMedida.escritores( EntornoKettle.trans( "03" ) ),
      "03 escribe en Escribir totales" );
    String t = CorridaMedida.textoConfirmacion( EntornoKettle.trans( "03" ), "es" );
    contiene( t, "«Escribir totales»", "la confirmacion nombra el step" );
    contiene( t, "¿Seguir?", "pregunta" );
    List<String> j = CorridaMedida.escritores( EntornoKettle.job( "10" ) );
    cierto( j.contains( "Generar constantes" ) && j.contains( "Ventas por producto" ), "entradas TRANS del job: " + j );
    falso( j.contains( "START" ) || j.contains( "Success" ), "START y Success no escriben" );
    contiene( CorridaMedida.textoConfirmacion( EntornoKettle.job( "11" ), "en" ), "“Correr job secuencia”",
      "job 11: la entrada JOB" );
    // una transformacion sin salidas no pide confirmacion
    TransMeta solo = EntornoKettle.trans( "03" );
    solo.removeStep( solo.indexOfStep( solo.findStep( "Escribir totales" ) ) );
    nulo( CorridaMedida.textoConfirmacion( solo, "es" ), "sin escritores no hace falta preguntar" );
  }

  /**
   * MetricasHook: apagado no toca nada; encendido (registrado a mano en el ExtensionPointPluginType,
   * como lo haria el escaneo de plugins) activa metricas y snapshots y devuelve el flag al terminar.
   */
  public void pruebaHook() throws Exception {
    System.clearProperty( MetricasHook.PROPIEDAD );
    falso( MetricasHook.activo(), "apagado por defecto" );
    TransMeta tm = EntornoKettle.trans( "03" );
    Trans t = new Trans( tm );
    new MetricasHook().callExtensionPoint( null, t );
    falso( t.isGatheringMetrics(), "apagado: no activa metricas" );
    falso( tm.isCapturingStepPerformanceSnapShots(), "apagado: no toca la TransMeta" );

    PluginRegistry reg = PluginRegistry.getInstance();
    PluginInterface previo = reg.findPluginWithId( ExtensionPointPluginType.class, "PentahoLiveMetricas" );
    if ( previo == null ) {
      ExtensionPointPluginType.getInstance().handlePluginAnnotation( MetricasHook.class, MetricasHook.class
        .getAnnotation( ExtensionPoint.class ), new ArrayList<String>(), true, null );
    }
    PluginInterface plugin = reg.findPluginWithId( ExtensionPointPluginType.class, "PentahoLiveMetricas" );
    noNulo( plugin, "la anotacion @ExtensionPoint registra el plugin" );
    try {
      System.setProperty( MetricasHook.PROPIEDAD, "on" );
      cierto( MetricasHook.activo(), "encendido" );
      final TransMeta viva = EntornoKettle.trans( "03" ); // como la del documento en Spoon
      falso( viva.isCapturingStepPerformanceSnapShots(), "03 no tiene el monitoreo activado" );
      final boolean[] durante = { false };
      Trans x = new Trans( viva );
      x.setLogLevel( LogLevel.MINIMAL );
      viva.setInternalKettleVariables( x );
      x.prepareExecution( null ); // aca corre el hook (TransformationPrepareExecution)
      cierto( x.isGatheringMetrics(), "el hook activo las metricas de una corrida comun" );
      durante[ 0 ] = viva.isCapturingStepPerformanceSnapShots();
      x.startThreads();
      x.waitUntilFinished();
      cierto( durante[ 0 ], "snapshots activos durante la corrida" );
      falso( viva.isCapturingStepPerformanceSnapShots(), "el flag vuelve a como estaba (no se guarda en el .ktr)" );
      falso( MetricasHook.PENDIENTES.containsKey( viva ), "sin pendientes" );
      CorridaMedida m = CorridaMedida.deTrans( x, null, CorridaMedida.ULTIMA );
      cierto( m.metricasReales, "la ultima corrida tiene Gantt real gracias al hook" );

      // si el usuario ya lo tenia activado, no se lo saca
      TransMeta conFlag = EntornoKettle.trans( "03" );
      conFlag.setCapturingStepPerformanceSnapShots( true );
      Trans y = new Trans( conFlag );
      y.setLogLevel( LogLevel.MINIMAL );
      conFlag.setInternalKettleVariables( y );
      y.prepareExecution( null );
      y.startThreads();
      y.waitUntilFinished();
      cierto( conFlag.isCapturingStepPerformanceSnapShots(), "respeta el flag del usuario" );
    } finally {
      System.clearProperty( MetricasHook.PROPIEDAD );
      if ( previo == null && plugin != null ) {
        reg.removePlugin( ExtensionPointPluginType.class, plugin );
      }
    }
  }
}
