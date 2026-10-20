package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.TiempoAgotado;

/**
 * La seccion «Calidad tecnica» (quality, SecCalidad: docs/linter-diseno.md §8, WP6), sin Spoon: el mismo puntaje que
 * lint_etl, los bloques en los tres idiomas, que no repite lo que ya muestra validation, el modo profundo con
 * Pedido.calidadProfunda, transformaciones y jobs, los cuatro formatos y que no crea la base SQLite que falta.
 *
 * @dueño calidad
 */
public class PruebaCalidad {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    InformeServicio.temporales = EntornoKettle.salida( "temp-calidad" );
  }

  // ---------------------------------------------------------------- la seccion sola

  /** 03 en es: indicadores, el mismo puntaje que lint_etl y datos con su forma JSON. */
  public void pruebaSeccionTrans() throws Exception {
    Captura c = EntornoKettle.captura( "03" );
    Seccion s = new SecCalidad().armar( ctx( c, "es", false ) );
    mostrar( "03.es", s );
    igual( SeccionId.CALIDAD, s.id, "id" );
    igual( "Calidad técnica", s.titulo, "titulo traducido" );
    igual( Seccion.OK, s.estado, "estado: " + s.motivo );
    io.github.ivi2511.pentahomcp.lint.Resultado lint = PruebaProfundo.estatico( (TransMeta) c.copia );
    Indicadores ind = bloques( s, Indicadores.class ).get( 0 );
    igual( "quality-indicadores", ind.id, "id de los indicadores" );
    igual( "Calidad técnica", ind.items.get( 0 ).etiqueta, "rotulo: calidad tecnica, no una nota" );
    igual( lint.score + "/100", ind.items.get( 0 ).valor, "el mismo puntaje que lint_etl" );
    igual( "Banda " + lint.grade, ind.items.get( 0 ).detalle, "banda" );
    igual( SecCalidad.nivelBanda( lint.grade ), ind.items.get( 0 ).nivel, "color de la banda" );
    igual( Arrays.asList( "Errores", "Advertencias", "Sugerencias" ), Arrays.asList( ind.items.get( 1 ).etiqueta,
      ind.items.get( 2 ).etiqueta, ind.items.get( 3 ).etiqueta ), "errores, advertencias y sugerencias" );
    igual( lint.score, s.datos.path( "score" ).asInt(), "datos.score" );
    igual( lint.grade, s.datos.path( "grade" ).asText(), "datos.grade" );
    igual( "quality", s.datos.path( "section" ).asText(), "datos.section" );
    falso( s.datos.path( "deep_requested" ).asBoolean(), "sin revision profunda" );
    cierto( s.datos.has( "findings" ) && s.datos.has( "penalties" ) && s.datos.has( "not_evaluated" ),
      "la forma de lint_etl: " + s.datos );
    igual( lint.findings.size(), s.datos.path( "findings" ).size(), "los mismos hallazgos" );
    contiene( s.resumen, "Puntaje " + lint.score + "/100 (" + lint.grade + ")", "resumen con el puntaje" );
    if ( !lint.findings.isEmpty() ) {
      Tabla t = tabla( s, "quality-hallazgos" );
      noNulo( t, "tabla de hallazgos" );
      igual( Arrays.asList( "Severidad", "Regla", "Dónde", "Qué pasa", "Cómo se arregla" ), t.columnas, "columnas" );
      igual( lint.findings.size(), t.filas.size(), "una fila por hallazgo (sin validation en el informe)" );
      Tabla p = tabla( s, "quality-puntos" );
      noNulo( p, "de donde salen los puntos" );
      contiene( p.nota, "no es una nota", "la formula avisa que no es una nota" );
      contiene( p.nota, "100 − puntos", "la formula" );
    }
  }

  /** Una transformacion con problemas: avisos de error, celdas, propuestas de nombre y los tres idiomas. */
  public void pruebaHallazgos() throws Exception {
    TransMeta tm = conProblemas( "q_problemas.ktr" );
    for ( String idioma : new String[] { "en", "es", "pt" } ) {
      Seccion s = new SecCalidad().armar( ctx( captura( tm, "q_problemas.ktr" ), idioma, false ) );
      mostrar( "problemas." + idioma, s );
      igual( Seccion.OK, s.estado, "estado " + idioma + ": " + s.motivo );
      Tabla t = tabla( s, "quality-hallazgos" );
      noNulo( t, "hallazgos " + idioma );
      String todo = t.filas.toString();
      contiene( todo, "Suelto", "el step sin hops: " + todo );
      contiene( todo, "Text file output", "el nombre por defecto" );
      for ( List<String> f : t.filas ) {
        cierto( f.size() == 5 && !f.get( 0 ).isEmpty() && !f.get( 1 ).isEmpty() && !f.get( 3 ).isEmpty(),
          "fila completa: " + f );
        falso( f.get( 1 ).endsWith( ".title" ), "titulo de la regla traducido: " + f );
      }
      noContiene( todo, "apply_lint_fixes", "sin nombres de tools del MCP" );
      noContiene( todo, "deep_timeout_s", "sin argumentos del MCP" );
      Tabla p = tabla( s, "quality-puntos" );
      noNulo( p, "puntos " + idioma );
      igual( Arrays.asList( "l", "l", "r", "r" ), p.alineacion, "numeros a la derecha" );
      cierto( p.filas.get( 0 ).get( 3 ).startsWith( "−" ), "puntos en negativo: " + p.filas.get( 0 ) );
    }
    Seccion es = new SecCalidad().armar( ctx( captura( tm, "q_problemas.ktr" ), "es", false ) );
    Tabla t = tabla( es, "quality-hallazgos" );
    boolean propuesta = false;
    for ( List<String> f : t.filas ) {
      if ( f.get( 2 ).equals( "Text file output" ) && f.get( 4 ).contains( "Propuesta: «" ) ) {
        propuesta = true;
      }
    }
    cierto( propuesta, "el nombre propuesto (LintPropuestas) en «Cómo se arregla»: " + t.filas );
    contiene( t.filas.toString(), "Advertencia", "severidad en castellano" );
    Seccion pt = new SecCalidad().armar( ctx( captura( tm, "q_problemas.ktr" ), "pt", false ) );
    contiene( pt.resumen, "Pontuação", "resumen en portugues: " + pt.resumen );
    contiene( tabla( pt, "quality-hallazgos" ).columnas.toString(), "Como corrigir", "columnas en portugues" );
    Seccion en = new SecCalidad().armar( ctx( captura( tm, "q_problemas.ktr" ), "en", false ) );
    contiene( en.resumen, "What costs the most", "lo que mas resta, en ingles: " + en.resumen );
  }

  /**
   * Sin validation en el informe la tabla lista todo; con validation, omite sus reglas (warning o error) y lo dice
   * al pie, y el puntaje es el mismo.
   */
  public void pruebaSinRepetirValidacion() throws Exception {
    TransMeta tm = conProblemas( "q_validacion.ktr" );
    Pedido solo = pedido( "es", SeccionId.CALIDAD );
    Informe a = armar( captura( tm, "q_validacion.ktr" ), solo );
    Seccion qa = a.seccion( SeccionId.CALIDAD );
    noNulo( qa, "quality sola" );
    contiene( tabla( qa, "quality-hallazgos" ).filas.toString(), "Step sin hops", "sin validation: el huerfano" );
    cierto( qa.datos.path( "keys_shown_in_validation" ).isMissingNode(), "nada omitido" );

    Pedido ambas = pedido( "es", SeccionId.VALIDACION, SeccionId.CALIDAD );
    Informe b = armar( captura( conProblemas( "q_validacion2.ktr" ), "q_validacion2.ktr" ), ambas );
    Seccion val = b.seccion( SeccionId.VALIDACION );
    Seccion qb = b.seccion( SeccionId.CALIDAD );
    mostrar( "con validation", qb );
    cierto( InformeServicio.salio( val ), "validation salio: " + val.estado + " " + val.motivo );
    contiene( val.datos.toString(), "Suelto", "validation muestra el huerfano" );
    Tabla t = tabla( qb, "quality-hallazgos" );
    noNulo( t, "quedan otros hallazgos" );
    for ( List<String> f : t.filas ) {
      falso( f.get( 1 ).equals( "Step sin hops" ), "sin el huerfano, que ya esta en validation: " + f );
    }
    contiene( t.nota, "ya está en «Validación»", "lo dice al pie: " + t.nota );
    JsonNode omitidas = qb.datos.path( "keys_shown_in_validation" );
    cierto( omitidas.size() >= 1 && omitidas.toString().contains( "orphan_step:step:Suelto" ), "keys omitidas: "
      + omitidas );
    igual( qa.datos.path( "score" ).asInt(), qb.datos.path( "score" ).asInt(), "el puntaje los cuenta igual" );
    contiene( tabla( qb, "quality-puntos" ).filas.toString(), "Step sin hops", "y resta sus puntos" );
    // para mirarlo: el informe con las dos secciones en HTML liviano
    File html = InformeServicio.archivo( EntornoKettle.salida( "calidad-formatos" ), "problemas", Formato.HTML_ESTATICO );
    InformeServicio.exportar( b, new Tema(), Formato.HTML_ESTATICO, html, Progreso.NULO );
    contiene( EntornoKettle.leer( html ), "De dónde salen los puntos que faltan", "la tabla de puntos en el HTML" );
    // los info de esas reglas no los da validate: siguen en la tabla
    for ( String k : textos( omitidas ) ) {
      for ( JsonNode h : qb.datos.path( "findings" ) ) {
        if ( h.path( "key" ).asText().equals( k ) ) {
          falso( "info".equals( h.path( "severity" ).asText() ), "solo se omiten warning y error: " + k );
        }
      }
    }
  }

  /** Con validation en error (no salio), quality no omite nada: nadie mas lo mostraria. */
  public void pruebaValidacionQueNoSalio() throws Exception {
    falso( InformeServicio.salio( null ), "sin validation" );
    Seccion err = new Seccion( SeccionId.VALIDACION, "Validación" );
    err.estado = Seccion.ERROR;
    falso( InformeServicio.salio( err ), "validation en error" );
    err.estado = Seccion.PARCIAL;
    cierto( InformeServicio.salio( err ), "validation parcial: sus chequeos propios salieron" );
    Hallazgo h = new Hallazgo();
    h.rule = "orphan_step";
    h.severity = io.github.ivi2511.pentahomcp.lint.Sev.WARNING;
    cierto( SecCalidad.enValidacion( h ), "orphan_step warning lo da validate" );
    h.severity = io.github.ivi2511.pentahomcp.lint.Sev.INFO;
    falso( SecCalidad.enValidacion( h ), "un info no lo da validate" );
    h.rule = "absolute_path";
    h.severity = io.github.ivi2511.pentahomcp.lint.Sev.WARNING;
    falso( SecCalidad.enValidacion( h ), "absolute_path no es de validate" );
  }

  // ---------------------------------------------------------------- modo profundo

  /** m03 (Group by sin Sort rows) con la revision profunda: error con la prueba, aviso y parrafo del modo profundo. */
  public void pruebaProfunda() throws Exception {
    TransMeta tm = PruebaProfundo.mutacion( "03", "q_m03.ktr", x -> {
      x = PruebaProfundo.sinStep( x, "Ordenar por producto" );
      x = PruebaProfundo.sinHop( x, "Calcular importe", "Ordenar por producto" );
      x = PruebaProfundo.sinHop( x, "Ordenar por producto", "Totales por producto" );
      return PruebaProfundo.conHop( x, "Calcular importe", "Totales por producto" );
    } );
    Seccion est = new SecCalidad().armar( ctx( captura( tm, "q_m03.ktr" ), "es", false ) );
    mostrar( "m03 estatico", est );
    contiene( tabla( est, "quality-hallazgos" ).nota, "sin leer datos", "sin la revision profunda lo avisa" );
    cierto( bloques( est, Aviso.class ).isEmpty(), "estatico: sin errores" );

    long t0 = System.currentTimeMillis();
    Seccion s = new SecCalidad().armar( ctx( captura( tm, "q_m03.ktr" ), "es", true ) );
    mostrar( "m03 profundo (" + ( System.currentTimeMillis() - t0 ) + " ms)", s );
    igual( Seccion.OK, s.estado, "estado: " + s.motivo );
    cierto( s.datos.path( "deep_requested" ).asBoolean(), "datos.deep_requested" );
    cierto( s.datos.path( "deep" ).path( "ran" ).asBoolean(), "corrio: " + s.datos.path( "deep" ) );
    igual( 1, s.datos.path( "deep" ).path( "raised" ).asInt(), "subio uno a error" );
    Parrafo p = parrafo( s, "quality-profundo" );
    noNulo( p, "parrafo del modo profundo" );
    contiene( p.texto, "Revisión profunda:", "parrafo: " + p.texto );
    contiene( p.texto, "Subieron a error: 1", "cuenta lo que subio" );
    List<Aviso> avisos = bloques( s, Aviso.class );
    igual( 1, avisos.size(), "un aviso por error" );
    igual( Aviso.ERROR, avisos.get( 0 ).nivel, "aviso de error" );
    contiene( avisos.get( 0 ).texto, "Entrada sin ordenar por la clave", "con el titulo de la regla" );
    contiene( avisos.get( 0 ).texto, "Revisión profunda:", "y la prueba del modo profundo" );
    Indicador puntaje = bloques( s, Indicadores.class ).get( 0 ).items.get( 0 );
    cierto( s.datos.path( "score" ).asInt() <= 74, "con un error, 74 como mucho: " + puntaje.valor );
    igual( "1", bloques( s, Indicadores.class ).get( 0 ).items.get( 1 ).valor, "un error" );
    igual( Indicador.ERROR, bloques( s, Indicadores.class ).get( 0 ).items.get( 1 ).nivel, "en rojo" );
    contiene( tabla( s, "quality-puntos" ).nota, "el puntaje queda en 74 como mucho", "la compuerta del error" );
    contiene( tabla( s, "quality-hallazgos" ).filas.get( 0 ).toString(), "(Depende de esta máquina.)",
      "lo que dijo el modo profundo depende de los datos" );
    String nota = tabla( s, "quality-hallazgos" ).nota;
    cierto( nota == null || !nota.contains( "sin leer datos" ), "con la revision profunda no lo avisa: " + nota );
  }

  /** Un job: la seccion sale y la revision profunda dice que solo revisa transformaciones. */
  public void pruebaJob() throws Exception {
    Captura c = EntornoKettle.captura( "10" );
    Seccion s = new SecCalidad().armar( ctx( c, "en", true ) );
    mostrar( "10.en", s );
    igual( Seccion.OK, s.estado, "estado: " + s.motivo );
    igual( "job", s.datos.path( "kind" ).asText(), "kind" );
    Parrafo p = parrafo( s, "quality-profundo" );
    noNulo( p, "parrafo del modo profundo" );
    contiene( p.texto, "Deep check: ", "parrafo: " + p.texto );
    contiene( p.texto, "transformations", "solo transformaciones" );
    noNulo( s.resumen, "resumen" );
  }

  // ---------------------------------------------------------------- el informe entero

  /** Por defecto quality va en el informe (despues de validation), sale en los 4 formatos y en el resumen. */
  public void pruebaInformeYFormatos() throws Exception {
    Informe inf = armar( EntornoKettle.captura( "03" ), pedido( "es" ) );
    List<String> ids = new ArrayList<String>();
    for ( Seccion s : inf.secciones ) {
      ids.add( s.id );
    }
    cierto( ids.contains( SeccionId.CALIDAD ), "quality por defecto: " + ids );
    cierto( ids.indexOf( SeccionId.CALIDAD ) == ids.indexOf( SeccionId.VALIDACION ) + 1, "despues de validation: "
      + ids );
    Seccion q = inf.seccion( SeccionId.CALIDAD );
    igual( Seccion.OK, q.estado, "estado: " + q.motivo );
    contiene( inf.seccion( SeccionId.RESUMEN ).datos.toString(), "\"quality\"", "el resumen la junta" );
    File dir = EntornoKettle.salida( "calidad-formatos" );
    for ( Formato f : new Formato[] { Formato.HTML_ESTATICO, Formato.MARKDOWN, Formato.PPTX, Formato.JSON } ) {
      File destino = InformeServicio.archivo( dir, "03-calidad", f );
      Resultado r = InformeServicio.exportar( inf, new Tema(), f, destino, Progreso.NULO );
      noNulo( r, "resultado " + f );
      cierto( destino.isFile() && destino.length() > 1000, "escribio " + destino );
      if ( f == Formato.HTML_ESTATICO || f == Formato.MARKDOWN || f == Formato.JSON ) {
        String txt = EntornoKettle.leer( destino );
        contiene( txt, "Calidad técnica", "titulo en " + f );
        contiene( txt, "Puntaje", "resumen en " + f );
      }
    }
    String md = EntornoKettle.leer( InformeServicio.archivo( dir, "03-calidad", Formato.MARKDOWN ) );
    contiene( md, "## ", "secciones en Markdown" );
    // get_report_data: quality con sus datos, sin binarios
    JsonNode datos = ReportTools.datosIa( inf, 50 );
    contiene( datos.toString(), "\"quality\"", "get_report_data lleva quality" );
  }

  /** 07: la base SQLite no existe y ni el estatico ni la revision profunda la crean (Kettle la crearia vacia). */
  public void pruebaBaseQueFalta() throws Exception {
    Captura c = EntornoKettle.captura( "07" );
    File base = new File( new File( c.archivo ).getParentFile(), "salida/demo.sqlite" );
    falso( base.exists(), "la base no existe al empezar: " + base );
    Seccion s = new SecCalidad().armar( ctx( c, "es", true ) );
    mostrar( "07.es profundo", s );
    falso( Seccion.ERROR.equals( s.estado ), "no queda en error: " + s.motivo );
    falso( base.exists(), "no se creo la base vacia" );
    TransMeta tm = (TransMeta) c.copia;
    StepMeta consulta = tm.findStep( "Consultar SQLite" );
    noNulo( consulta, "el Table input de 07" );
    LintEntorno env = new LintEntorno( ReportTools.clonar( tm ) );
    cierto( env.creaBase( consulta ), "conectarse crearia la base" );
    falso( env.soloLectura( consulta ), "entonces no es de solo lectura para el modo profundo" );
    StepMeta escribir = tm.findStep( "Escribir consulta" );
    falso( env.creaBase( escribir ), "un step sin conexion no crea bases" );
  }

  /** El contexto corta el calculo de campos por tiempo: el entorno lo pasa como TiempoAgotado (el linter deja de pedir). */
  public void pruebaEntorno() throws Exception {
    Captura c = EntornoKettle.captura( "03" );
    Pedido p = pedido( "es" );
    p.timeoutMs = 0;
    ContextoKettle cero = new ContextoKettle( c, p, new Tema(), Progreso.NULO );
    final SecCalidad.EntornoInforme env = new SecCalidad.EntornoInforme( cero, c.copia, 60000 );
    final StepMeta s = ( (TransMeta) c.copia ).getStep( 0 );
    lanza( TiempoAgotado.class, () -> env.salida( s ), "sin plazo: TiempoAgotado" );
    lanza( TiempoAgotado.class, () -> env.entrada( s ), "entrada igual" );
    igual( 0L, Math.max( 0L, env.msRestantes() ), "sin plazo" );
    ContextoKettle normal = new ContextoKettle( c, pedido( "es" ), new Tema(), Progreso.NULO );
    SecCalidad.EntornoInforme env2 = new SecCalidad.EntornoInforme( normal, c.copia, 60000 );
    noNulo( env2.salida( s ), "con plazo calcula" );
    falso( env2.cancelado(), "sin cancelar" );
    normal.cancelar();
    cierto( env2.cancelado(), "el pedido cancelado cancela el linter" );
    // un error de campos que no es por tiempo pasa tal cual (el linter lo anota como no evaluado)
    Captura c7 = EntornoKettle.captura( "07" );
    ContextoKettle ctx7 = new ContextoKettle( c7, pedido( "es" ), new Tema(), Progreso.NULO );
    final SecCalidad.EntornoInforme env7 = new SecCalidad.EntornoInforme( ctx7, c7.copia, 60000 );
    final StepMeta consulta = ( (TransMeta) c7.copia ).findStep( "Consultar SQLite" );
    KettleStepException e7 = lanza( KettleStepException.class, () -> env7.salida( consulta ), "la base falta" );
    falso( e7 instanceof TiempoAgotado, "no es por tiempo: " + e7.getMessage() );
    // los textos en los tres idiomas: mismas claves, ninguno vacio
    Properties en = props( "en" );
    igual( new TreeSet<Object>( en.keySet() ), new TreeSet<Object>( props( "es" ).keySet() ), "mismas claves en y es" );
    igual( new TreeSet<Object>( en.keySet() ), new TreeSet<Object>( props( "pt" ).keySet() ), "mismas claves en y pt" );
    for ( String i : new String[] { "en", "es", "pt" } ) {
      Rotulos r = Rotulos.de( SecCalidad.AREA, i );
      for ( Object k : en.keySet() ) {
        falso( r.s( (String) k ).trim().isEmpty(), "texto vacio: " + i + " " + k );
      }
      igual( i.equals( "en" ) ? "Technical quality" : i.equals( "es" ) ? "Calidad técnica" : "Qualidade técnica",
        SeccionId.titulo( SeccionId.CALIDAD, i ), "titulo " + i );
      noContiene( r.f( "nota.formula", 25, 8, 2, 50, 16, 4, 10, 74 ), "%", "formula con sus 8 numeros en " + i );
      noContiene( r.f( "profundo.hecho", 3, 1.5, 1, 0, 2, 0 ), "%", "profundo con sus 6 numeros en " + i );
    }
    igual( "1.234 hallazgos más en los datos del informe (JSON).", InformeServicio.Txt.n( Rotulos.de( SecCalidad.AREA,
      "es" ), "pie.mas", 1234 ), "plural con miles" );
  }

  // ---------------------------------------------------------------- ayudas

  /**
   * 03 con un step suelto (orphan_step, de validate), la salida con el nombre por defecto y la entrada con una ruta
   * absoluta (que existe: absolute_path, que validate no da).
   */
  private static TransMeta conProblemas( String nombre ) throws Exception {
    final String csv = new File( EntornoKettle.ejemplos(), "datos/ventas.csv" ).getAbsolutePath().replace( '\\', '/' );
    return PruebaProfundo.mutacion( "03", nombre, x -> {
      x = PruebaProfundo.reemplazar( x, "${Internal.Entry.Current.Directory}/datos/ventas.csv", csv );
      x = x.replace( "Escribir totales", "Text file output" );
      String suelto = "<step><name>Suelto</name><type>Dummy</type><description/><distribute>Y</distribute>"
        + "<custom_distribution/><copies>1</copies><partitioning><method>none</method><schema_name/></partitioning>"
        + "<attributes/><cluster_schema/><remotesteps><input></input><output></output></remotesteps>"
        + "<GUI><xloc>100</xloc><yloc>400</yloc><draw>Y</draw></GUI></step>";
      return x.replaceFirst( "<step>", java.util.regex.Matcher.quoteReplacement( suelto ) + "<step>" );
    } );
  }

  private static Captura captura( TransMeta tm, String archivo ) throws Exception {
    Captura c = new Captura();
    c.copia = ReportTools.clonar( tm );
    c.docId = "prueba:" + archivo;
    c.nombre = tm.getName();
    c.archivo = tm.getFilename();
    c.tipo = Captura.TRANSFORMACION;
    c.capturado = new Date();
    return c;
  }

  private static ContextoKettle ctx( Captura c, String idioma, boolean profunda ) {
    Pedido p = pedido( idioma );
    p.calidadProfunda = profunda;
    return new ContextoKettle( c, p, new Tema(), Progreso.NULO );
  }

  private static Pedido pedido( String idioma, String... secciones ) {
    Pedido p = new Pedido();
    p.idioma = idioma;
    if ( secciones.length > 0 ) {
      p.secciones = new ArrayList<String>( Arrays.asList( secciones ) );
    }
    return p;
  }

  private static Informe armar( Captura c, Pedido p ) throws Exception {
    return InformeServicio.armar( c, p, new Tema(), Progreso.NULO ).get( 300, TimeUnit.SECONDS );
  }

  @SuppressWarnings( "unchecked" )
  private static <T extends Bloque> List<T> bloques( Seccion s, Class<T> clase ) {
    List<T> l = new ArrayList<T>();
    for ( Bloque b : s.bloques ) {
      if ( clase.isInstance( b ) ) {
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

  private static Parrafo parrafo( Seccion s, String id ) {
    for ( Parrafo p : bloques( s, Parrafo.class ) ) {
      if ( id.equals( p.id ) ) {
        return p;
      }
    }
    return null;
  }

  private static List<String> textos( JsonNode a ) {
    List<String> l = new ArrayList<String>();
    for ( JsonNode x : a ) {
      l.add( x.asText() );
    }
    return l;
  }

  private static void mostrar( String que, Seccion s ) {
    System.out.println( "  " + que + ": [" + s.estado + "] " + s.resumen + ( s.motivo == null ? "" : " (" + s.motivo
      + ")" ) );
    for ( Bloque b : s.bloques ) {
      if ( b instanceof Tabla ) {
        Tabla t = (Tabla) b;
        System.out.println( "    tabla " + t.id + ": " + t.filas.size() + " filas" + ( t.nota == null ? ""
          : " · nota: " + t.nota.replace( '\n', ' ' ) ) );
        for ( int i = 0; i < t.filas.size() && i < 4; i++ ) {
          System.out.println( "      " + t.filas.get( i ) );
        }
      } else if ( b instanceof Aviso ) {
        System.out.println( "    aviso: " + ( (Aviso) b ).texto );
      } else if ( b instanceof Parrafo ) {
        System.out.println( "    parrafo: " + ( (Parrafo) b ).texto );
      } else if ( b instanceof Indicadores ) {
        StringBuilder sb = new StringBuilder();
        for ( Indicador i : ( (Indicadores) b ).items ) {
          sb.append( i.etiqueta ).append( '=' ).append( i.valor ).append( i.detalle == null ? "" : " (" + i.detalle
            + ")" ).append( "; " );
        }
        System.out.println( "    indicadores: " + sb );
      }
    }
  }

  private static Properties props( String idioma ) throws Exception {
    String recurso = Rotulos.CARPETA + SecCalidad.AREA + "_" + idioma + ".properties";
    try ( InputStream in = PruebaCalidad.class.getClassLoader().getResourceAsStream( recurso ) ) {
      noNulo( in, "recurso " + recurso );
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] buf = new byte[ 8192 ];
      int n;
      while ( ( n = in.read( buf ) ) > 0 ) {
        bo.write( buf, 0, n );
      }
      Properties p = new Properties();
      p.load( new StringReader( new String( bo.toByteArray(), StandardCharsets.UTF_8 ) ) );
      return p;
    }
  }
}
