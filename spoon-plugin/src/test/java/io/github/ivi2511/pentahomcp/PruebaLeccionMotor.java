package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.gui.Point;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.filterrows.FilterRowsMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Modo clase, WP3: el motor de lecciones sin Spoon ({@link LeccionMotor.SinSpoon}). Una leccion de punta a punta
 * sobre los datos de los ejemplos (la L1 «Primer ETL: filtrar clientes», con la configuracion de
 * 02_filtrar_clientes.ktr), validacion sin tocar nada, el paso 0 como su propio Ctrl+Z, «Atras», «Reiniciar», el
 * Ctrl+Z del usuario (out_of_sync y resume), clics que no se acumulan en el ritmo guiado y el documento cerrado.
 *
 * @dueño clase
 */
public class PruebaLeccionMotor {

  private static final Pattern STEP = Pattern.compile( "<step>(.*?)</step>", Pattern.DOTALL );

  /** El cuerpo de un step de un ejemplo (lo que va en config_xml; name/type/GUI se ignoran). */
  private static String config( String ejemplo, String step ) throws Exception {
    String t = new String( Files.readAllBytes( EntornoKettle.ejemplo( ejemplo ).toPath() ), StandardCharsets.UTF_8 );
    Matcher m = STEP.matcher( t );
    while ( m.find() ) {
      if ( m.group( 1 ).contains( "<name>" + step + "</name>" ) ) {
        return m.group( 1 );
      }
    }
    throw new IllegalStateException( "no step " + step );
  }

  /** Transformacion vacia "guardada" junto a datos/ de la copia de los ejemplos. */
  private static TransMeta nueva( String nombre ) throws Exception {
    EntornoKettle.iniciar();
    File dir = EntornoKettle.ejemplos();
    TransMeta tm = new TransMeta();
    tm.setName( nombre );
    tm.setFilename( new File( dir, nombre + ".ktr" ).getAbsolutePath() );
    tm.setInternalKettleVariables();
    return tm;
  }

  private static ObjectNode paso( String type, String name, String after, String what, String why ) throws Exception {
    ObjectNode p = Tools.obj().put( "type", type ).put( "name", name ).put( "config_xml", config( "02", name ) );
    if ( after != null ) {
      p.put( "after", after );
    }
    return p.put( "what", what ).put( "why", why );
  }

  /** La L1 (filtrar clientes) en espanol. */
  private static ObjectNode guionL1( String ritmo ) throws Exception {
    ObjectNode a = Tools.obj().put( "title", "Primer ETL: filtrar clientes" ).put( "level", "beginner" )
      .put( "language", "es" ).put( "pace", ritmo ).put( "intro", "Leemos un CSV, filtramos y escribimos." )
      .put( "outro", "Ejercicio: cambia el saldo mínimo y predice cuántas filas salen." );
    ArrayNode s = a.putArray( "steps" );
    s.add( paso( "CsvInput", "Leer clientes", null, "lee datos/clientes.csv", "es la entrada del flujo" )
      .put( "watch_out", "auto" ).put( "preview_rows", 5 ) );
    s.add( paso( "FilterRows", "Activos con saldo", "Leer clientes", "deja pasar los activos con saldo",
      "separa dos ramas" ).put( "tip", "send_true_to necesita su hop" ).put( "preview_rows", 3 ) );
    s.add( paso( "SelectValues", "Elegir columnas", "Activos con saldo", "elige y renombra columnas",
      "la salida solo lleva lo útil" ) );
    s.add( paso( "TextFileOutput", "Escribir filtrados", "Elegir columnas", "escribe el CSV de salida",
      "es el resultado" ).put( "watch_out", "D8: los campos sin declarar no salen" ) );
    s.add( paso( "Dummy", "Descartados", "Activos con saldo", "recibe la rama falsa", "para verla en las métricas" ) );
    return a;
  }

  private static LeccionMotor.SinSpoon entorno( int pausa ) {
    LeccionMotor.olvidarTodas();
    return new LeccionMotor.SinSpoon( pausa );
  }

  private static ObjectNode siguiente( LeccionMotor.SinSpoon ent, Leccion l ) throws Exception {
    return LeccionMotor.avanzar( ent, l, false );
  }

  private static String hechos( JsonNode r ) {
    return r.path( "done" ).toString();
  }

  // ---------------------------------------------------------------- de punta a punta

  public void pruebaLeccionDePuntaAPunta() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 0 );
    TransMeta tm = nueva( "leccion_l1" );
    Leccion l = LeccionMotor.empezar( ent, guionL1( "manual" ), tm );
    ObjectNode ini = LeccionMotor.inicioJson( l );
    igual( "waiting_next", ini.path( "state" ).asText(), "espera el primer paso" );
    igual( 0, ini.path( "position" ).asInt(), "posicion 0" );
    igual( 5, ini.path( "beats" ).asInt(), "5 pasos" );
    igual( 5, ini.path( "plan" ).size(), "plan de 5" );
    igual( "build", ini.path( "plan" ).get( 0 ).path( "action" ).asText(), "crea" );
    igual( "Activos con saldo", ini.path( "plan" ).get( 4 ).path( "previews" ).path( 0 ).asText(),
      "la vista previa del Filter rows se pospone al paso del ultimo destino" );
    igual( 0, tm.nrSteps(), "el paso 0 no crea steps" );
    igual( 1, tm.nrNotes(), "solo el titulo" );
    contiene( tm.getNote( 0 ).getNote(), "Lección · Primer ETL", "titulo con la etiqueta en espanol" );
    contiene( tm.getNote( 0 ).getNote(), "principiante · 5 pasos", "nivel y pasos" );
    igual( l.id, tm.getAttribute( LeccionNotas.GRUPO, "id" ), "id en el registro" );
    igual( "beginner", tm.getAttribute( LeccionNotas.GRUPO, "level" ), "nivel en el registro" );
    noNulo( tm.viewThisUndo(), "el paso 0 es su propio Ctrl+Z" );

    ObjectNode r1 = siguiente( ent, l );
    igual( 1, r1.path( "position" ).asInt(), "paso 1" );
    contiene( hechos( r1 ), "step 'Leer clientes' added", "crea el step" );
    contiene( hechos( r1 ), "watch-out note added", "ojo automatico" );
    igual( 5, r1.path( "preview" ).path( "rows" ).size(), "5 filas de la vista previa: " + r1.path( "preview" ) );
    igual( 3, tm.nrNotes(), "titulo + paso + ojo" );
    boolean d3 = false;
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      d3 |= tm.getNote( i ).getNote().replaceAll( "\\s+", " " ).contains( "D3" );
    }
    cierto( d3, "principiante: el ojo automatico de CsvInput es D3 (solo los silenciosos)" );

    for ( int k = 2; k <= 5; k++ ) {
      ObjectNode r = siguiente( ent, l );
      igual( k, r.path( "position" ).asInt(), "paso " + k );
      if ( k == 5 ) {
        igual( "done", r.path( "state" ).asText(), "termina" );
        contiene( r.path( "outro" ).asText(), "Ejercicio", "el cierre" );
        noNulo( r.get( "preview" ), "la vista previa pospuesta del Filter rows corre en el paso 5" );
        contiene( hechos( r ), "preview of 'Activos con saldo'", "dice que corrio" );
      }
    }
    igual( 5, tm.nrSteps(), "5 steps" );
    igual( 4, tm.nrTransHops(), "4 hops" );
    for ( StepMeta s : tm.getSteps() ) {
      Point p = l.plan.posicion( s.getName() );
      igual( p.x, s.getLocation().x, s.getName() + ": x del plan" );
      igual( p.y, s.getLocation().y, s.getName() + ": y del plan" );
    }
    FilterRowsMeta fm = (FilterRowsMeta) tm.findStep( "Activos con saldo" ).getStepMetaInterface();
    noNulo( fm.getStepIOMeta().getTargetStreams().get( 0 ).getStepMeta(), "send_true_to resuelto" );
    noNulo( fm.getStepIOMeta().getTargetStreams().get( 1 ).getStepMeta(), "send_false_to resuelto (paso 5)" );
    igual( tm.nrNotes(), LeccionNotas.reconocer( tm ).size(), "todas las notas son de la leccion" );
    lanza( ToolError.class, () -> siguiente( ent, l ), "terminada: no avanza" );

    // guardar y releer: notas y registro viajan en el .ktr
    File f = new File( tm.getFilename() );
    Files.write( f.toPath(), tm.getXML().getBytes( StandardCharsets.UTF_8 ) );
    TransMeta otra = new TransMeta( f.getAbsolutePath() );
    igual( tm.nrNotes(), LeccionNotas.reconocer( otra ).size(), "releido: reconocidas" );
    igual( "Primer ETL: filtrar clientes", otra.getAttribute( LeccionNotas.GRUPO, "title" ), "titulo releido" );
    LeccionMotor.olvidarTodas();
  }

  // ---------------------------------------------------------------- undo

  public void pruebaAtrasReiniciarYCtrlZDelUsuario() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 0 );
    TransMeta tm = nueva( "leccion_undo" );
    ObjectNode g = guionL1( "manual" );
    for ( JsonNode p : g.path( "steps" ) ) {
      ( (ObjectNode) p ).remove( "preview_rows" );
    }
    Leccion l = LeccionMotor.empezar( ent, g, tm );
    siguiente( ent, l );
    siguiente( ent, l );
    int notas2 = tm.nrNotes();
    siguiente( ent, l );
    igual( 3, tm.nrSteps(), "3 steps" );

    ObjectNode b = LeccionMotor.controlar( ent, l, "back", null );
    igual( 2, b.path( "position" ).asInt(), "atras: posicion 2" );
    nulo( tm.findStep( "Elegir columnas" ), "atras quita el step del paso 3" );
    igual( notas2, tm.nrNotes(), "y sus notas, en el mismo Ctrl+Z" );
    igual( 1, tm.nrTransHops(), "y su hop" );

    // Ctrl+Z del usuario: deshace el paso 2 entero (step, hop, notas)
    LeccionMotor.deshacerSinSpoon( tm );
    igual( 1, tm.nrSteps(), "Ctrl+Z del usuario: queda el paso 1" );
    ObjectNode r = siguiente( ent, l );
    igual( "out_of_sync", r.path( "state" ).asText(), "lo detecta antes del paso" );
    igual( "user_undo", r.path( "reason" ).asText(), "motivo" );
    igual( 1, tm.nrSteps(), "y no toca nada" );
    lanza( ToolError.class, () -> siguiente( ent, l ), "fuera de sincronia no avanza" );
    ObjectNode re = LeccionMotor.controlar( ent, l, "resume", null );
    igual( "waiting_next", re.path( "state" ).asText(), "resume re-sincroniza" );
    igual( 1, re.path( "position" ).asInt(), "desde lo que hay" );
    igual( 2, siguiente( ent, l ).path( "position" ).asInt(), "vuelve a crear el paso 2" );
    noNulo( tm.findStep( "Activos con saldo" ), "Filter rows de nuevo" );

    ObjectNode rs = LeccionMotor.controlar( ent, l, "restart", null );
    igual( 0, rs.path( "position" ).asInt(), "reiniciar: posicion 0" );
    igual( 0, tm.nrSteps(), "sin steps" );
    igual( 1, tm.nrNotes(), "queda el titulo (paso 0)" );
    ObjectNode go = LeccionMotor.controlar( ent, l, "goto", 3 );
    igual( 3, go.path( "position" ).asInt(), "goto 3" );
    igual( 3, tm.nrSteps(), "3 steps" );
    igual( 1, LeccionMotor.controlar( ent, l, "goto", 1 ).path( "position" ).asInt(), "goto 1 hacia atras" );
    igual( 1, tm.nrSteps(), "1 step" );

    // algo del usuario arriba del historial: «Atras» no deshace lo ajeno
    tm.addUndo( new Object[] { tm.getStep( 0 ) }, null, new int[] { 0 }, new Point[] { new Point( 1, 1 ) },
      new Point[] { new Point( 2, 2 ) }, org.pentaho.di.base.AbstractMeta.TYPE_UNDO_POSITION, false );
    ObjectNode nb = LeccionMotor.controlar( ent, l, "back", null );
    igual( 1, nb.path( "position" ).asInt(), "no retrocede" );
    contiene( nb.path( "warnings" ).toString(), "not lesson step 1", "y lo avisa" );
    LeccionMotor.controlar( ent, l, "stop", null );
    lanza( ToolError.class, () -> siguiente( ent, l ), "parada: no avanza" );
    LeccionMotor.olvidarTodas();
  }

  public void pruebaPasoCeroAcomodaYEsSuCtrlZ() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 0 );
    EntornoKettle.iniciar();
    TransMeta tm = EntornoKettle.trans( "04" );
    Point antes = new Point( tm.findStep( "Leer ventas" ).getLocation().x, tm.findStep( "Leer ventas" ).getLocation().y );
    ObjectNode a = Tools.obj().put( "title", "Cada venta con su cliente" ).put( "pace", "manual" );
    ArrayNode s = a.putArray( "steps" );
    s.addObject().put( "step", "Leer ventas" ).put( "what", "reads the sales" ).put( "why", "main flow" );
    s.addObject().put( "step", "Buscar cliente" ).put( "what", "looks the customer up" ).put( "why", "joins" )
      .put( "watch_out", "auto" );
    Leccion l = LeccionMotor.empezar( ent, a, tm );
    Point plan = l.plan.posicion( "Leer ventas" );
    igual( plan.x, tm.findStep( "Leer ventas" ).getLocation().x, "paso 0: los steps que ya estaban van al plan" );
    igual( "annotate", LeccionMotor.inicioJson( l ).path( "plan" ).get( 0 ).path( "action" ).asText(), "anotar" );
    LeccionMotor.deshacerSinSpoon( tm );
    igual( antes.x, tm.findStep( "Leer ventas" ).getLocation().x, "un Ctrl+Z deshace el paso 0 (posiciones)" );
    igual( 0, tm.nrNotes(), "y el titulo" );
    ObjectNode r = siguiente( ent, l );
    igual( "out_of_sync", r.path( "state" ).asText(), "sin el paso 0: fuera de sincronia" );
    LeccionMotor.olvidarTodas();

    TransMeta tm2 = EntornoKettle.trans( "04" );
    Leccion l2 = LeccionMotor.empezar( ent, a, tm2 );
    ObjectNode r1 = siguiente( ent, l2 );
    igual( 1, r1.path( "position" ).asInt(), "anota el paso 1" );
    contiene( hechos( r1 ), "note 1 added", "su nota" );
    ObjectNode r2 = siguiente( ent, l2 );
    igual( "done", r2.path( "state" ).asText(), "fin" );
    igual( 4, tm2.nrSteps(), "no crea steps" );
    LeccionMotor.olvidarTodas();
  }

  // ---------------------------------------------------------------- validacion

  public void pruebaValidaSinTocarNada() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 0 );
    TransMeta tm = nueva( "leccion_mal" );
    ObjectNode a = guionL1( "manual" );
    ( (ObjectNode) a.path( "steps" ).get( 0 ) ).put( "x", 10 );
    ( (ObjectNode) a.path( "steps" ).get( 2 ) ).put( "type", "NoExisteEsteTipo" );
    ( (ObjectNode) a.path( "steps" ).get( 1 ) ).put( "after", "Escribir filtrados" );
    ToolError e = lanza( ToolError.class, () -> LeccionMotor.empezar( ent, a, tm ), "guion con errores" );
    contiene( e.getMessage(), "nothing was changed", "no toca nada" );
    contiene( e.getMessage(), "x/y", "x/y" );
    contiene( e.getMessage(), "NoExisteEsteTipo", "tipo" );
    contiene( e.getMessage(), "comes later", "after hacia adelante" );
    igual( 0, tm.nrSteps(), "sin steps" );
    igual( 0, tm.nrNotes(), "sin notas" );
    falso( LeccionNotas.tieneLeccion( tm ), "sin registro" );
    nulo( tm.viewThisUndo(), "sin undo" );

    ObjectNode w = guionL1( "manual" );
    ( (ObjectNode) w.path( "steps" ).get( 3 ) ).put( "preview_rows", 2 );
    ToolError ew = lanza( ToolError.class, () -> LeccionMotor.empezar( ent, w, tm ), "vista previa de una salida" );
    contiene( ew.getMessage(), "allow_write", "pide allow_write" );

    TransMeta sinArchivo = new TransMeta();
    sinArchivo.setName( "sin_archivo" );
    ToolError ef = lanza( ToolError.class, () -> LeccionMotor.empezar( ent, guionL1( "manual" ), sinArchivo ),
      "vista previa sin guardar" );
    contiene( ef.getMessage(), "has no file", "hay que guardar" );

    ObjectNode x = Tools.obj().put( "title", "t" );
    x.putArray( "steps" ).addObject().put( "step", "No existe" );
    contiene( lanza( ToolError.class, () -> LeccionMotor.empezar( ent, x, tm ), "step inexistente" ).getMessage(),
      "does not exist", "lo dice" );
    igual( 0, tm.nrNotes(), "sigue sin notas" );
    LeccionMotor.olvidarTodas();
  }

  // ---------------------------------------------------------------- ritmo guiado, clics y documento cerrado

  public void pruebaClicsNoSeAcumulan() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 600 );
    TransMeta tm = nueva( "leccion_clics" );
    ObjectNode g = guionL1( "guided" );
    for ( JsonNode p : g.path( "steps" ) ) {
      ( (ObjectNode) p ).remove( "preview_rows" );
    }
    Leccion l = LeccionMotor.empezar( ent, g, tm );
    cierto( LeccionMotor.clic( l ), "el primer clic avanza" );
    falso( LeccionMotor.clic( l ), "el segundo se descarta" );
    falso( LeccionMotor.clic( l ), "el tercero tambien" );
    long fin = System.currentTimeMillis() + 15000;
    ObjectNode st = null;
    while ( System.currentTimeMillis() < fin ) {
      st = LeccionMotor.estado( ent, l, 2, null );
      if ( st.path( "position" ).asInt() >= 1 && "waiting_next".equals( st.path( "state" ).asText() ) ) {
        break;
      }
    }
    igual( 1, st.path( "position" ).asInt(), "un solo paso" );
    Thread.sleep( 1200 );
    ObjectNode st2 = LeccionMotor.estado( ent, l, 0, null );
    igual( 1, st2.path( "position" ).asInt(), "los clics de mas no se acumularon" );
    igual( 3, st2.path( "clicks" ).path( "count" ).asInt(), "pero se cuentan" );
    ObjectNode es = LeccionMotor.estado( ent, l, 1, 0 );
    cierto( es.path( "changed" ).asBoolean(), "since distinto de la posicion: vuelve enseguida" );

    ent.cerrada = true;
    ObjectNode c = LeccionMotor.estado( ent, l, 0, null );
    igual( "stopped", c.path( "state" ).asText(), "documento cerrado" );
    igual( "document_closed", c.path( "reason" ).asText(), "motivo" );
    falso( LeccionMotor.clic( l ), "parada: el clic no hace nada" );
    LeccionMotor.olvidarTodas();
  }

  public void pruebaRitmoAutoConPausa() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 0 );
    TransMeta tm = nueva( "leccion_auto" );
    ObjectNode g = guionL1( "auto" ).put( "delay_ms", 1500 );
    ArrayNode pasos = (ArrayNode) g.path( "steps" );
    while ( pasos.size() > 2 ) {
      pasos.remove( pasos.size() - 1 );
    }
    for ( JsonNode p : pasos ) {
      ( (ObjectNode) p ).remove( "preview_rows" );
    }
    Leccion l = LeccionMotor.empezar( ent, g, tm );
    igual( "paused", LeccionMotor.controlar( ent, l, "pause", null ).path( "state" ).asText(), "pausada" );
    Thread.sleep( 2200 );
    igual( 0, LeccionMotor.estado( ent, l, 0, null ).path( "position" ).asInt(), "en pausa no avanza" );
    igual( "waiting_next", LeccionMotor.controlar( ent, l, "resume", null ).path( "state" ).asText(), "sigue" );
    long fin = System.currentTimeMillis() + 15000;
    ObjectNode st = null;
    while ( System.currentTimeMillis() < fin ) {
      st = LeccionMotor.estado( ent, l, 3, null );
      if ( "done".equals( st.path( "state" ).asText() ) ) {
        break;
      }
    }
    igual( "done", st.path( "state" ).asText(), "auto: llega solo al final" );
    igual( 2, tm.nrSteps(), "2 steps" );
    LeccionMotor.olvidarTodas();
  }

  public void pruebaSegundaLeccionReemplazaElRegistro() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 0 );
    TransMeta tm = nueva( "leccion_dos" );
    ObjectNode g = guionL1( "manual" );
    for ( JsonNode p : g.path( "steps" ) ) {
      ( (ObjectNode) p ).remove( "preview_rows" );
    }
    Leccion l = LeccionMotor.empezar( ent, g, tm );
    siguiente( ent, l );
    siguiente( ent, l );
    lanza( ToolError.class, () -> LeccionMotor.empezar( ent, guionL1( "manual" ), tm ), "sigue activa" );
    LeccionMotor.controlar( ent, l, "stop", null );
    contiene( lanza( ToolError.class, () -> LeccionMotor.empezar( ent, guionL1( "manual" ), tm ),
      "con notas de leccion" ).getMessage(), "already has lesson notes", "pide quitarlas" );
    LessonTools.ejecutar( null, tm, "remove", Tools.obj().put( "action", "remove" ) );
    ObjectNode g2 = Tools.obj().put( "title", "Otra" ).put( "pace", "manual" );
    g2.putArray( "steps" ).addObject().put( "step", "Leer clientes" ).put( "what", "again" );
    Leccion l2 = LeccionMotor.empezar( ent, g2, tm );
    igual( "1", tm.getAttribute( LeccionNotas.GRUPO, "notes" ), "registro nuevo: solo el titulo" );
    contiene( LeccionMotor.inicioJson( l2 ).path( "warnings" ).toString(), "earlier lesson", "lo avisa" );
    siguiente( ent, l2 );
    igual( 1, LeccionNotas.numeroDePaso( LeccionNotas.registro( tm ), "Leer clientes" ), "numeracion desde 1" );
    LeccionMotor.olvidarTodas();
  }

  // ---------------------------------------------------------------- stop, Ctrl+Z y restyle en medio de un paso

  private static ObjectNode sinVistas( ObjectNode g ) {
    for ( JsonNode p : g.path( "steps" ) ) {
      ( (ObjectNode) p ).remove( "preview_rows" );
    }
    return g;
  }

  private static void esperarStep( TransMeta tm ) throws Exception {
    long fin = System.currentTimeMillis() + 5000;
    while ( tm.nrSteps() == 0 && System.currentTimeMillis() < fin ) {
      Thread.sleep( 20 );
    }
    igual( 1, tm.nrSteps(), "el step ya esta; las notas esperan la pausa" );
  }

  public void pruebaStopMientrasCorreUnPaso() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 1500 );
    TransMeta tm = nueva( "leccion_stop" );
    Leccion l = LeccionMotor.empezar( ent, sinVistas( guionL1( "guided" ) ), tm );
    cierto( LeccionMotor.clic( l ), "el clic arranca el paso 1" );
    esperarStep( tm );
    igual( "stopped", LeccionMotor.controlar( ent, l, "stop", null ).path( "state" ).asText(), "parada" );
    Thread.sleep( 2500 );
    ObjectNode st = LeccionMotor.estado( ent, l, 0, null );
    igual( "stopped", st.path( "state" ).asText(), "el final del paso no la revive" );
    falso( LeccionMotor.clic( l ), "parada: el clic no hace nada" );
    igual( 1, tm.nrSteps(), "no jugo otro paso" );
    LeccionMotor.olvidarTodas();
  }

  public void pruebaCtrlZEnLaPausaNoDejaNotasSueltas() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 1500 );
    TransMeta tm = nueva( "leccion_pausa" );
    Leccion l = LeccionMotor.empezar( ent, sinVistas( guionL1( "guided" ) ), tm );
    int notas0 = tm.nrNotes();
    cierto( LeccionMotor.clic( l ), "el clic arranca el paso 1" );
    esperarStep( tm );
    LeccionMotor.deshacerSinSpoon( tm ); // el profesor aprieta Ctrl+Z antes de que salgan las notas
    long fin = System.currentTimeMillis() + 8000;
    ObjectNode st = LeccionMotor.estado( ent, l, 0, null );
    while ( "playing".equals( st.path( "state" ).asText() ) && System.currentTimeMillis() < fin ) {
      st = LeccionMotor.estado( ent, l, 1, null );
    }
    igual( "out_of_sync", st.path( "state" ).asText(), "desincronizada" );
    igual( "user_undo", st.path( "reason" ).asText(), "por el Ctrl+Z" );
    igual( 0, st.path( "position" ).asInt(), "el paso no cuenta como jugado" );
    igual( 0, tm.nrSteps(), "sin el step" );
    igual( notas0, tm.nrNotes(), "sin notas sueltas del paso deshecho" );
    ObjectNode re = LeccionMotor.controlar( ent, l, "resume", null );
    igual( "waiting_next", re.path( "state" ).asText(), "resume vuelve a esperar" );
    igual( 0, re.path( "position" ).asInt(), "en el paso 0" );
    LeccionMotor.controlar( ent, l, "stop", null );
    LeccionMotor.olvidarTodas();
  }

  public void pruebaRestyleEnMedioNoDesincroniza() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 0 );
    TransMeta tm = nueva( "leccion_restyle" );
    Leccion l = LeccionMotor.empezar( ent, sinVistas( guionL1( "manual" ) ), tm );
    siguiente( ent, l );
    siguiente( ent, l );
    String huellas0 = PruebaLeccionNotas.huellasCrudas( tm );
    ObjectNode rs = LessonTools.ejecutar( null, tm, "restyle", Tools.obj().put( "action", "restyle" ).put( "font_size",
      16 ) );
    cierto( rs.path( "notes" ).asInt() > 0, "el restyle toco las notas" );
    cierto( !huellas0.equals( PruebaLeccionNotas.huellasCrudas( tm ) ), "el restyle cambio algun texto cortado" );
    ObjectNode r = siguiente( ent, l );
    igual( "waiting_next", r.path( "state" ).asText(), "el restyle no cuenta como un Ctrl+Z: " + r );
    igual( 3, r.path( "position" ).asInt(), "paso 3" );
    nulo( LeccionMotor.escritorAntes( tm, "Elegir columnas" ), "nada escribe antes de Elegir columnas" );
    siguiente( ent, l );
    igual( "Escribir filtrados", LeccionMotor.escritorAntes( tm, "Escribir filtrados" ), "el output escribe" );
    LeccionMotor.controlar( ent, l, "stop", null );
    LeccionMotor.olvidarTodas();
  }

  public void pruebaDocumentoCerradoAlAvanzar() throws Exception {
    LeccionMotor.SinSpoon ent = entorno( 0 );
    TransMeta tm = nueva( "leccion_cerrada" );
    ObjectNode g = guionL1( "manual" );
    for ( JsonNode p : g.path( "steps" ) ) {
      ( (ObjectNode) p ).remove( "preview_rows" );
    }
    Leccion l = LeccionMotor.empezar( ent, g, tm );
    contiene( lanza( ToolError.class, () -> LeccionMotor.empezar( ent, g, tm ), "otra leccion" ).getMessage(),
      "still", "una leccion activa por documento" );
    ent.cerrada = true;
    ObjectNode r = siguiente( ent, l );
    igual( "stopped", r.path( "state" ).asText(), "parada" );
    igual( "document_closed", r.path( "reason" ).asText(), "documento cerrado" );
    igual( 0, tm.nrSteps(), "sin cambios" );
    LeccionMotor.olvidarTodas();
  }
}
