package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.distinto;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Modo clase, WP2: notas de la leccion (LeccionNotas, lesson_notes) sin Spoon, sobre el ejemplo 04 de la skill.
 * Huellas, estilos, registro en los atributos, guardar y releer, ocultar/mostrar sin duplicar tras un Ctrl+Z
 * (simulado como lo hace Spoon con DELETE_NOTE), quitar sin tocar las notas del usuario ni las editadas, y
 * arrange_canvas con la regla de clase. El medidor es el aproximado (no hay Display en el hilo).
 */
public class PruebaLeccionNotas {

  private static TransMeta ejemplo() throws Exception {
    EntornoKettle.iniciar();
    return EntornoKettle.trans( "04" );
  }

  private static ObjectNode args( String accion ) {
    return Tools.obj().put( "action", accion );
  }

  private static ObjectNode correr( TransMeta tm, String accion, JsonNode a ) throws Exception {
    return LessonTools.ejecutar( null, tm, accion, a );
  }

  private static ObjectNode agregar( TransMeta tm, String step, String kind, String texto ) throws Exception {
    return correr( tm, "add", args( "add" ).put( "step", step ).put( "kind", kind ).put( "text", texto )
      .put( "language", "es" ) );
  }

  private static int lecciones( TransMeta tm ) {
    return LeccionNotas.reconocer( tm ).size();
  }

  /** Deshace la ultima accion como Spoon (solo los tipos que usan estas pruebas). */
  private static void deshacer( TransMeta tm ) {
    TransAction ta = tm.previousUndo();
    noNulo( ta, "hay algo para deshacer" );
    Object[] o = ta.getCurrent();
    int[] idx = ta.getCurrentIndex();
    switch ( ta.getType() ) {
      case TransAction.TYPE_ACTION_DELETE_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.addNote( idx[ i ], (NotePadMeta) o[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_NEW_NOTE:
        for ( int i = idx.length - 1; i >= 0; i-- ) {
          tm.removeNote( idx[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_CHANGE_NOTE: // como Spoon.undoTransformationAction
        for ( int i = 0; i < idx.length; i++ ) {
          tm.removeNote( idx[ i ] );
          tm.addNote( idx[ i ], (NotePadMeta) ( (NotePadMeta) ta.getPrevious()[ i ] ).clone() );
        }
        break;
      default:
        throw new IllegalStateException( "tipo de undo no simulado: " + ta.getType() );
    }
  }

  // ---------------------------------------------------------------- huellas, etiquetas y estilos

  public void pruebaHuellaSinEspacios() {
    igual( LeccionNotas.huella( "a b\nc" ), LeccionNotas.huella( "abc" ), "los espacios no cuentan" );
    igual( LeccionNotas.huella( "a b\r\nc\t" ), LeccionNotas.huella( "a\nbc" ), "ni los saltos de linea" );
    distinto( LeccionNotas.huella( "abc" ), LeccionNotas.huella( "abd" ), "otro texto, otra huella" );
    igual( 8, LeccionNotas.huella( "x" ).length(), "8 hex" );
  }

  public void pruebaEtiquetas() {
    igual( "¡Ojo!", LeccionNotas.etiqueta( "watch_out", "es" ), "es" );
    igual( "Watch out!", LeccionNotas.etiqueta( "watch_out", "en" ), "en" );
    igual( "Dica", LeccionNotas.etiqueta( "tip", "pt" ), "pt" );
    igual( "Lesson", LeccionNotas.etiqueta( "lesson", "fr" ), "otro idioma = en" );
    igual( "¡Ojo! (paso 2) la ruta", LeccionNotas.textoSuelta( LeccionLayout.Tipo.OJO, "es", "X", 2, "la ruta" ),
      "ojo con numero" );
    igual( "Tip (Leer): memoria", LeccionNotas.textoSuelta( LeccionLayout.Tipo.TIP, "en", "Leer", 0, "memoria" ),
      "tip sin numero" );
    igual( "3 · Buscar (Stream lookup)\nQué: une\nPor qué: sin BD",
      LeccionNotas.textoPaso( "es", 3, "Buscar", "Stream lookup", "une", "sin BD" ), "nota de paso" );
  }

  public void pruebaAgregarEstiloRegistroYUnUndo() throws Exception {
    TransMeta tm = ejemplo();
    String step = tm.getStep( 0 ).getName();
    int antes = tm.nrNotes();
    tm.clearUndo();
    ObjectNode r = agregar( tm, step, "watch_out", "la ruta es relativa al .ktr" );
    igual( antes + 1, tm.nrNotes(), "una nota mas" );
    igual( 1, lecciones( tm ), "reconocida por huella" );
    NotePadMeta n = LeccionNotas.reconocer( tm ).keySet().iterator().next();
    contiene( n.getNote().replace( "\n", " " ), "¡Ojo! (" + step + ")", "etiqueta fija en es" );
    igual( 253, n.getBackGroundColorRed(), "fondo del ojo" );
    igual( 190, n.getBorderColorRed(), "borde del ojo" );
    falso( n.isDrawShadow(), "sin sombra" );
    igual( LeccionLayout.ANCHO_NOTA, n.getWidth(), "220 de ancho" );
    igual( "1", tm.getAttribute( LeccionNotas.GRUPO, "notes" ), "registro: notes=1" );
    contiene( tm.getAttribute( LeccionNotas.GRUPO, "note.1" ), "|watch_out|" + step, "registro: note.1" );
    igual( "One Ctrl+Z in Spoon undoes this", r.get( "undo" ).asText(), "respuesta" );
    // una sola entrada en el historial a deshacer de una vez: la primera sin nextAlso, las demas encadenadas
    int acciones = 0;
    TransAction ta;
    boolean encadenada = true;
    while ( encadenada && ( ta = tm.previousUndo() ) != null ) {
      acciones++;
      encadenada = ta.getNextAlso();
    }
    cierto( acciones >= 1, "al menos una accion" );
    nulo( tm.previousUndo(), "un solo Ctrl+Z: no queda nada antes del grupo" );
  }

  public void pruebaAddErrores() throws Exception {
    TransMeta tm = ejemplo();
    lanza( ToolError.class, () -> agregar( tm, "No existe", "tip", "x" ), "step inexistente" );
    lanza( ToolError.class, () -> agregar( tm, tm.getStep( 0 ).getName(), "title", "x" ), "kind invalido" );
    lanza( ToolError.class, () -> correr( tm, "hide", args( "hide" ) ), "sin leccion no hay hide" );
    lanza( ToolError.class, () -> correr( tm, "bailar", args( "bailar" ) ), "accion desconocida" );
  }

  // ---------------------------------------------------------------- guardar y releer

  public void pruebaGuardarYReleerConservaNotasYRegistro() throws Exception {
    TransMeta tm = ejemplo();
    tm.addNote( new NotePadMeta( "Nota del usuario", 500, 500, 100, 30 ) );
    agregar( tm, tm.getStep( 0 ).getName(), "watch_out", "ruta relativa" );
    agregar( tm, tm.getStep( 1 ).getName(), "tip", "va entero a memoria" );
    agregar( tm, tm.getStep( 1 ).getName(), "question", "¿y si no hay coincidencia?" );
    igual( 3, lecciones( tm ), "tres de la leccion" );
    File f = new File( EntornoKettle.salida( "leccion-notas" ), "guardada.ktr" );
    EntornoKettle.escribir( f, tm.getXML() );
    TransMeta re = new TransMeta( f.getAbsolutePath() );
    igual( tm.nrNotes(), re.nrNotes(), "mismas notas" );
    igual( 3, lecciones( re ), "las tres reconocidas tras releer" );
    igual( "3", re.getAttribute( LeccionNotas.GRUPO, "notes" ), "registro tras releer" );
    List<String[]> l = LeccionNotas.listar( re );
    igual( 3, l.size(), "tres entradas" );
    for ( String[] e : l ) {
      igual( "present", e[ 3 ], "entrada " + e[ 0 ] + " presente" );
    }
    NotePadMeta tip = null;
    for ( Map.Entry<NotePadMeta, LeccionNotas.Entrada> x : LeccionNotas.reconocer( re ).entrySet() ) {
      if ( x.getValue().tipo == LeccionLayout.Tipo.TIP ) {
        tip = x.getKey();
      }
    }
    noNulo( tip, "el tip sigue" );
    igual( 228, tip.getBackGroundColorRed(), "el estilo viaja en el .ktr" );
  }

  // ---------------------------------------------------------------- ocultar y mostrar

  public void pruebaOcultarMostrarSinDuplicarTrasUndo() throws Exception {
    TransMeta tm = ejemplo();
    tm.addNote( new NotePadMeta( "Nota del usuario", 500, 500, 100, 30 ) );
    agregar( tm, tm.getStep( 0 ).getName(), "watch_out", "uno" );
    agregar( tm, tm.getStep( 1 ).getName(), "tip", "dos" );
    int total = tm.nrNotes();
    tm.clearUndo();
    ObjectNode h = correr( tm, "hide", args( "hide" ) );
    igual( 2, h.get( "notes" ).asInt(), "oculta 2" );
    igual( 1, h.get( "kept_user_notes" ).asInt(), "la del usuario queda" );
    igual( total - 2, tm.nrNotes(), "fuera del lienzo" );
    cierto( tm.getAttribute( LeccionNotas.GRUPO, "hidden" ).contains( "<notepad>" ), "XML en hidden" );
    for ( String[] e : LeccionNotas.listar( tm ) ) {
      igual( "hidden", e[ 3 ], "entrada oculta" );
    }
    // guardar y releer con las notas ocultas
    File f = new File( EntornoKettle.salida( "leccion-notas" ), "oculta.ktr" );
    EntornoKettle.escribir( f, tm.getXML() );
    TransMeta re = new TransMeta( f.getAbsolutePath() );
    igual( total - 2, re.nrNotes(), "ocultas tambien tras releer" );
    ObjectNode s = correr( re, "show", args( "show" ) );
    igual( 2, s.get( "notes" ).asInt(), "show repone 2" );
    igual( total, re.nrNotes(), "todas de vuelta" );
    igual( 2, lecciones( re ), "reconocidas" );
    igual( 0, LeccionNotas.ocultas( re ).size(), "ninguna oculta: todas en el lienzo" );
    // Ctrl+Z del show: vuelven a estar ocultas (hidden guarda la copia) y otro show las repone
    deshacer( re );
    igual( total - 2, re.nrNotes(), "el Ctrl+Z del show las saca" );
    for ( String[] e : LeccionNotas.listar( re ) ) {
      igual( "hidden", e[ 3 ], "otra vez oculta" );
    }
    igual( 2, correr( re, "show", args( "show" ) ).get( "notes" ).asInt(), "show otra vez" );
    // remove con todas en el lienzo vacia hidden: la copia para repartir no las lleva escondidas
    correr( re, "remove", args( "remove" ) );
    igual( 0, lecciones( re ), "fuera del lienzo" );
    igual( "", re.getAttribute( LeccionNotas.GRUPO, "hidden" ), "hidden vacio tras remove" );
    // Ctrl+Z del hide en el original: las notas vuelven, pero hidden sigue lleno (los atributos no entran en el undo)
    deshacer( tm );
    igual( total, tm.nrNotes(), "el Ctrl+Z repuso las notas" );
    ObjectNode s2 = correr( tm, "show", args( "show" ) );
    igual( 0, s2.get( "notes" ).asInt(), "show no duplica" );
    igual( total, tm.nrNotes(), "sin duplicados" );
    igual( 2, lecciones( tm ), "dos de la leccion" );
  }

  // ---------------------------------------------------------------- quitar

  public void pruebaQuitarRespetaUsuarioYEditadas() throws Exception {
    TransMeta tm = ejemplo();
    int previas = tm.nrNotes();
    tm.addNote( new NotePadMeta( "Nota del usuario", 500, 500, 100, 30 ) );
    agregar( tm, tm.getStep( 0 ).getName(), "watch_out", "uno" );
    agregar( tm, tm.getStep( 1 ).getName(), "tip", "dos" );
    agregar( tm, tm.getStep( 1 ).getName(), "question", "tres" );
    // el usuario edita la pregunta: pasa a ser suya
    NotePadMeta editada = null;
    for ( Map.Entry<NotePadMeta, LeccionNotas.Entrada> x : LeccionNotas.reconocer( tm ).entrySet() ) {
      if ( x.getValue().tipo == LeccionLayout.Tipo.PREGUNTA ) {
        editada = x.getKey();
      }
    }
    editada.setNote( editada.getNote() + " (respuesta: Dummy)" );
    igual( 2, lecciones( tm ), "la editada ya no es de la leccion" );
    tm.clearUndo();
    ObjectNode r = correr( tm, "remove", args( "remove" ) );
    igual( 2, r.get( "notes" ).asInt(), "borra 2" );
    igual( previas + 2, tm.nrNotes(), "quedan las previas, la del usuario y la editada" );
    cierto( tm.indexOfNote( editada ) >= 0, "la editada sigue" );
    igual( "3", tm.getAttribute( LeccionNotas.GRUPO, "notes" ), "el registro queda" );
    String[] tercera = LeccionNotas.listar( tm ).get( 2 );
    igual( "missing", tercera[ 3 ], "la editada figura como missing" );
    // Ctrl+Z: vuelven y se reconocen (el registro no se borro)
    deshacer( tm );
    igual( 2, lecciones( tm ), "el Ctrl+Z las devuelve reconocidas" );
    // purge
    ObjectNode p = correr( tm, "remove", args( "remove" ).put( "purge", true ) );
    igual( 2, p.get( "notes" ).asInt(), "borra 2 otra vez" );
    nulo( tm.getAttribute( LeccionNotas.GRUPO, "notes" ), "purge borra el registro" );
    falso( LeccionNotas.tieneLeccion( tm ), "sin leccion" );
  }

  // ---------------------------------------------------------------- relayout, restyle y arrange_canvas

  public void pruebaArrangeCanvasConLeccionUsaLaReglaDeClase() throws Exception {
    TransMeta tm = ejemplo();
    for ( int i = 0; i < tm.nrSteps(); i++ ) {
      agregar( tm, tm.getStep( i ).getName(), "tip", "nota " + i );
    }
    // el usuario desordena todo
    for ( int i = 0; i < tm.nrSteps(); i++ ) {
      tm.getStep( i ).setLocation( 900 + i * 7, 40 + i * 3 );
    }
    tm.clearUndo();
    TransLayout.Movimientos mov = OrdenarLienzo.ordenar( null, tm );
    cierto( mov.objetos.size() > 0, "movio steps" );
    for ( int i = 0; i < tm.nrSteps(); i++ ) {
      StepMeta s = tm.getStep( i );
      igual( 0, ( s.getLocation().x - LeccionLayout.x0( LeccionLayout.tamIcono() ) ) % LeccionLayout.DX,
        "columnas de 240: " + s.getName() + " x=" + s.getLocation().x );
    }
    // las notas siguen reconocidas y cerca de su step
    for ( Map.Entry<NotePadMeta, LeccionNotas.Entrada> x : LeccionNotas.reconocer( tm ).entrySet() ) {
      StepMeta s = tm.findStep( x.getValue().step );
      int dx = Math.abs( x.getKey().getLocation().x + 110 - s.getLocation().x );
      cierto( dx < 400, "nota " + x.getValue().k + " cerca de su step (dx=" + dx + ")" );
    }
    igual( tm.nrSteps(), lecciones( tm ), "todas reconocidas" );
    // relayout de nuevo: no cambia nada
    ObjectNode r = correr( tm, "relayout", args( "relayout" ) );
    igual( 0, r.get( "steps_moved" ).asInt(), "relayout estable" );
  }

  public void pruebaGetDocumentMarcaLasNotasDeLeccion() throws Exception {
    TransMeta tm = ejemplo();
    tm.addNote( new NotePadMeta( "Nota del usuario", 500, 500, 100, 30 ) );
    String step = tm.getStep( 0 ).getName();
    agregar( tm, step, "tip", "un tip" );
    ObjectNode doc = Tools.obj();
    DocumentTools.notas( tm, doc ); // la parte de get_document (resumen) que no necesita Spoon
    JsonNode notas = doc.path( "notes" );
    int leccion = 0;
    int usuario = 0;
    for ( JsonNode n : notas ) {
      if ( n.has( "lesson" ) ) {
        leccion++;
        igual( 1, n.get( "lesson" ).asInt(), "numero de la entrada" );
        igual( "tip", n.get( "kind" ).asText(), "tipo" );
        igual( step, n.get( "step" ).asText(), "step" );
        falso( n.has( "text" ), "sin el texto de la nota de leccion" );
      } else if ( "Nota del usuario".equals( n.path( "text" ).asText() ) ) {
        usuario++;
      }
    }
    igual( 1, leccion, "una nota de leccion marcada" );
    igual( 1, usuario, "la del usuario con su texto" );
  }

  public void pruebaRestyleCambiaFuenteYSigueReconocida() throws Exception {
    TransMeta tm = ejemplo();
    agregar( tm, tm.getStep( 0 ).getName(), "watch_out",
      "una explicacion bastante larga para que el corte de lineas cambie al agrandar la fuente del proyector" );
    NotePadMeta n = LeccionNotas.reconocer( tm ).keySet().iterator().next();
    String antes = n.getNote();
    n.setLocation( n.getLocation().x + 333, n.getLocation().y + 333 ); // fuera de su sitio: el restyle la mueve
    int x0 = n.getLocation().x;
    int y0 = n.getLocation().y;
    int fuente0 = n.getFontSize();
    int idx = tm.indexOfNote( n );
    tm.clearUndo();
    ObjectNode r = correr( tm, "restyle", args( "restyle" ).put( "font_size", 14 ) );
    igual( 1, lecciones( tm ), "sigue reconocida" );
    igual( 14, n.getFontSize(), "fuente 14" );
    distinto( antes, n.getNote(), "otro corte de lineas" );
    igual( "14", tm.getAttribute( LeccionNotas.GRUPO, "font_size" ), "font_size en el registro" );
    igual( 1, r.get( "notes" ).asInt(), "una nota" );
    cierto( n.getLocation().x != x0 || n.getLocation().y != y0, "el restyle la volvio a ubicar" );
    // el Ctrl+Z la devuelve tambien a su sitio (clone() de NotePadMeta comparte el Point de la ubicacion)
    deshacer( tm );
    NotePadMeta vuelta = tm.getNote( idx );
    igual( antes, vuelta.getNote(), "texto de antes" );
    igual( fuente0, vuelta.getFontSize(), "fuente de antes" );
    igual( x0 + "," + y0, vuelta.getLocation().x + "," + vuelta.getLocation().y, "en su sitio tras el Ctrl+Z" );
    lanza( ToolError.class, () -> correr( tm, "restyle", args( "restyle" ) ), "restyle sin font_size" );
    lanza( ToolError.class, () -> correr( tm, "restyle", args( "restyle" ).put( "font_size", 99 ) ), "fuera de rango" );
  }
}
