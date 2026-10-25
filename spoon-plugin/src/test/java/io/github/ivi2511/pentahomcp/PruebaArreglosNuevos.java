package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.NoEvaluado;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.robustez.Propuesta;

/**
 * Los arreglos de la 0.7.0 (lo que la persona encontró sin arreglo al revisar sus ejercicios): parámetros y
 * descripción que se deshacen con el mismo Ctrl+Z (UndoEfecto), en el linter y en harden_etl; contraseña a
 * ${DB_PASSWORD_&lt;CONEXIÓN&gt;} con confirmación y sin escribir nunca la clave; hop deshabilitado, step suelto, default
 * de un parámetro, descripción propuesta y SELECT * con sus columnas; duplicate_step_name evaluado aunque haya cambios
 * sin guardar; los textos de apply_lint_fixes en castellano; un grupo de undo que sigue al anterior; y lo que haría
 * stress_test antes de correrlo, sin conectarse a ninguna base.
 *
 * @dueño calidad
 */
public class PruebaArreglosNuevos {

  // ---------------------------------------------------------------- ayudas

  private static final class Corrida {
    LintArreglos.Analisis an;
    LintArreglos.Hecho hecho;

    String texto() {
      StringBuilder b = new StringBuilder( "applied " + hecho.aplicados );
      for ( String[] s : hecho.saltados ) {
        b.append( "; skipped " ).append( s[ 0 ] ).append( ": " ).append( s[ 1 ] );
      }
      return b.toString();
    }
  }

  private static Corrida arreglar( AbstractMeta doc, String args, boolean sinGuardar ) throws Exception {
    LintArreglos.Pedido p = LintArreglos.pedido( McpHttpServer.JSON.readTree( args ) );
    AbstractMeta copia = ReportTools.clonar( doc );
    Opciones op = new Opciones();
    op.lang = p.lang;
    op.sinGuardar = sinGuardar;
    op.entorno = new LintEntorno( copia );
    Corrida c = new Corrida();
    c.an = LintArreglos.analizar( copia, op, p );
    c.hecho = LintArreglos.aplicar( doc, c.an, p.lang, UndoGrupo.sinSpoon( doc ), null, null );
    return c;
  }

  private static Resultado lint( AbstractMeta doc, String lang, boolean sinGuardar ) {
    AbstractMeta copia = ReportTools.clonar( doc );
    Opciones op = new Opciones();
    op.lang = lang;
    op.sinGuardar = sinGuardar;
    op.entorno = new LintEntorno( copia );
    Resultado r = LintMotor.analizar( copia, op );
    LintPropuestas.completar( r, copia, lang );
    return r;
  }

  private static Hallazgo deRegla( Resultado r, String regla ) {
    for ( Hallazgo h : r.findings ) {
      if ( h.rule.equals( regla ) ) {
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

  /** Copia de un ejemplo con reemplazos, junto a los demás. */
  private static TransMeta variante( String ejemplo, String nombre, String... reemplazos ) throws Exception {
    EntornoKettle.iniciar();
    File f = EntornoKettle.ejemplo( ejemplo );
    String x = EntornoKettle.leer( f );
    for ( int i = 0; i + 1 < reemplazos.length; i += 2 ) {
      contiene( x, reemplazos[ i ], "el ejemplo " + ejemplo + " tiene el texto a reemplazar" );
      x = x.replace( reemplazos[ i ], reemplazos[ i + 1 ] );
    }
    File g = new File( f.getParentFile(), nombre );
    EntornoKettle.escribir( g, x );
    return new TransMeta( g.getAbsolutePath() );
  }

  /**
   * Lo que hace Spoon con Ctrl+Z (SpoonTransformationDelegate.undoTransformationAction), con los tipos que usan los
   * arreglos: deshace la acción de arriba y, mientras la deshecha tenga getNextAlso(), la anterior. Un cambio de step
   * se deshace con replaceMeta(previo.clone()): ahí actúa UndoEfecto.
   */
  static void deshacer( TransMeta tm ) {
    TransAction ta = tm.previousUndo();
    while ( ta != null ) {
      int[] idx = ta.getCurrentIndex();
      switch ( ta.getType() ) {
        case TransAction.TYPE_ACTION_NEW_STEP:
          for ( int i = idx.length - 1; i >= 0; i-- ) {
            tm.removeStep( idx[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_NEW_HOP:
          for ( int i = idx.length - 1; i >= 0; i-- ) {
            tm.removeTransHop( idx[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_DELETE_HOP:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.addTransHop( idx[ i ], (TransHopMeta) ta.getCurrent()[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_DELETE_STEP:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.addStep( idx[ i ], (StepMeta) ta.getCurrent()[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_CHANGE_STEP:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.getStep( idx[ i ] ).replaceMeta( (StepMeta) ( (StepMeta) ta.getPrevious()[ i ] ).clone() );
          }
          break;
        case TransAction.TYPE_ACTION_CHANGE_HOP:
          for ( int i = 0; i < idx.length; i++ ) {
            TransHopMeta prev = (TransHopMeta) ( (TransHopMeta) ta.getPrevious()[ i ] ).clone();
            tm.removeTransHop( idx[ i ] );
            tm.addTransHop( idx[ i ], prev );
          }
          break;
        default:
          Verificador.falla( "acción de undo inesperada: " + ta.getType() );
      }
      if ( !ta.getNextAlso() ) {
        break;
      }
      ta = tm.previousUndo();
    }
    TransOps.resolverReferencias( tm );
  }

  /** Y Ctrl+Y: rehace desde la acción siguiente mientras la siguiente tenga getNextAlso(). */
  static void rehacer( TransMeta tm ) {
    TransAction ta = tm.nextUndo();
    while ( ta != null ) {
      int[] idx = ta.getCurrentIndex();
      switch ( ta.getType() ) {
        case TransAction.TYPE_ACTION_CHANGE_STEP:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.getStep( idx[ i ] ).replaceMeta( (StepMeta) ( (StepMeta) ta.getCurrent()[ i ] ).clone() );
          }
          break;
        case TransAction.TYPE_ACTION_NEW_STEP:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.addStep( idx[ i ], (StepMeta) ta.getCurrent()[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_NEW_HOP:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.addTransHop( idx[ i ], (TransHopMeta) ta.getCurrent()[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_DELETE_HOP:
          for ( int i = idx.length - 1; i >= 0; i-- ) {
            tm.removeTransHop( idx[ i ] );
          }
          break;
        default:
          Verificador.falla( "acción de redo inesperada: " + ta.getType() );
      }
      TransAction sig = tm.viewNextUndo();
      if ( sig == null || !sig.getNextAlso() ) {
        break;
      }
      ta = tm.nextUndo();
    }
    TransOps.resolverReferencias( tm );
  }

  // ---------------------------------------------------------------- deshacer parámetros

  /**
   * harden_etl input_file_parameter: el parámetro nuevo entra en el mismo Ctrl+Z que la ruta (antes quedaba fuera, con
   * «[not undoable with Ctrl+Z]» en el plan), y Ctrl+Y lo vuelve a poner. El diff ya no lo dice y va en castellano.
   */
  public void pruebaParametroDeHardenSeDeshace() throws Exception {
    TransMeta tm = variante( "03", "nuevo_param.ktr" );
    String antes = tm.getXML();
    RobustecerAnalisis.Pedido p = RobustezTools.pedido( new ObjectMapper().readTree(
      "{\"recipes\":[\"input_file_parameter\"],\"confirm\":[\"all\"],\"dry_run\":false,\"language\":\"es\"}" ) );
    RobustecerAnalisis.Plan mcp = RobustecerAnalisis.analizar( tm, p );
    String enMcp = RobustecerAnalisis.elegir( mcp, p ).get( 0 ).diff();
    contiene( enMcp, "+ parameter ", "desde MCP el diff sigue en inglés" );
    noContiene( enMcp, "not undoable", "ya no dice que no se deshace" );
    p.diffEnIdioma = true; // como el menú IDC
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    List<Propuesta> el = RobustecerAnalisis.elegir( plan, p );
    igual( 1, el.size(), "una propuesta de parámetro" );
    String diff = el.get( 0 ).diff();
    contiene( diff, "+ parámetro ", "el diff en castellano" );
    contiene( diff, ": archivo ", "la ruta en castellano" );
    igual( true, el.get( 0 ).json().path( "undoable" ).asBoolean(), "undoable=true" );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, el, UndoGrupo.sinSpoon( tm ), null, "es" );
    igual( 1, h.aplicados.size(), "aplicada" );
    igual( 1, h.pasosDeshacer, "un Ctrl+Z" );
    igual( 1, tm.listParameters().length, "el parámetro declarado" );
    String nombre = tm.listParameters()[ 0 ];
    cierto( h.notas.isEmpty(), "sin la nota de que no se deshace: " + h.notas );
    String despues = tm.getXML();
    deshacer( tm );
    igual( 0, tm.listParameters().length, "Ctrl+Z quita el parámetro" );
    igual( antes, tm.getXML(), "y deja el XML como antes (parámetros incluidos)" );
    rehacer( tm );
    igual( Arrays.asList( nombre ), Arrays.asList( tm.listParameters() ), "Ctrl+Y lo vuelve a poner" );
    igual( despues, tm.getXML(), "y deja el XML como después" );
  }

  /**
   * silent_filter_drop (lo que salió en el ejercicio): R02 manda la rama falsa del filtro a un motivo y a la cola de
   * rechazos; se confirma, el linter deja de señalarlo y un Ctrl+Z lo deshace.
   */
  public void pruebaFiltroSinRamaFalsa() throws Exception {
    TransMeta tm = variante( "02", "nuevo_filtro.ktr", "<send_false_to>Descartados</send_false_to>",
      "<send_false_to/>" );
    StepMeta d = tm.findStep( "Descartados" );
    tm.removeTransHop( tm.indexOfTransHop( tm.findTransHop( tm.findStep( "Activos con saldo" ), d ) ) );
    tm.removeStep( tm.indexOfStep( d ) );
    Opciones op = new Opciones();
    op.lang = "es";
    op.sinGuardar = true;
    op.robustez = true;
    op.entorno = new LintEntorno( tm );
    Resultado r = LintMotor.analizar( ReportTools.clonar( tm ), op );
    Hallazgo h = deRegla( r, "silent_filter_drop" );
    noNulo( h, "filtro silencioso: " + claves( r ) );
    String antes = tm.getXML();
    RobustecerAnalisis.Pedido p = RobustezTools.pedido( new ObjectMapper().readTree(
      "{\"recipes\":[\"reject_reason\"],\"steps\":[\"Activos con saldo\"],\"confirm\":[\"all\"],\"dry_run\":false}" ) );
    List<Propuesta> el = RobustecerAnalisis.elegir( RobustecerAnalisis.analizar( tm, p ), p );
    igual( 1, el.size(), "una propuesta para el filtro" );
    igual( "confirm", el.get( 0 ).nivel, "se confirma" );
    contiene( el.get( 0 ).diff(), "Activos con saldo: send_false_to -> ", "la rama falsa al motivo" );
    String motivo = el.get( 0 ).diff().replaceAll( "(?s).*send_false_to -> ([^\\n]+).*", "$1" );
    RobustecerAplicar.Hecho hecho = RobustecerAplicar.aplicar( tm, el, UndoGrupo.sinSpoon( tm ), null );
    igual( 1, hecho.aplicados.size(), "aplicada: " + hecho.saltados );
    contiene( tm.findStep( "Activos con saldo" ).getXML(), "<send_false_to>" + motivo + "</send_false_to>",
      "el filtro manda la rama falsa" );
    noNulo( tm.findTransHop( tm.findStep( "Activos con saldo" ), tm.findStep( motivo ) ), "con su hop" );
    nulo( deRegla( LintMotor.analizar( ReportTools.clonar( tm ), op ), "silent_filter_drop" ), "arreglado" );
    deshacer( tm );
    igual( antes, tm.getXML(), "Ctrl+Z lo deshace" );
  }

  /** undeclared_variable, parameter_without_default y no_description: con valor, deshacibles con el mismo Ctrl+Z. */
  public void pruebaParametrosYDescripcionDelLinter() throws Exception {
    TransMeta tm = variante( "02", "nuevo_desc.ktr",
      "${Internal.Entry.Current.Directory}/datos/clientes.csv", "${DIR_ENTRADA}/clientes.csv" );
    tm.setDescription( "" );
    tm.setExtendedDescription( "" );
    tm.addParameterDefinition( "SIN_DEFAULT", "", "" );
    Resultado r = lint( tm, "es", true );
    Hallazgo desc = deRegla( r, "no_description" );
    noNulo( desc, "sin descripción: " + claves( r ) );
    cierto( desc.fix.confirm && !desc.fix.needsValue, "con propuesta: se confirma, ya no pide valor" );
    contiene( desc.fix.preview, "Lee «", "la propuesta dice qué lee" );
    contiene( desc.fix.preview, "escribe «", "y qué escribe" );
    Hallazgo var = deRegla( r, "undeclared_variable" );
    noNulo( var, "variable sin declarar: " + claves( r ) );
    cierto( var.fix.undoable, "ahora se deshace" );
    Hallazgo pd = deRegla( r, "parameter_without_default" );
    noNulo( pd, "parámetro sin default: " + claves( r ) );
    igual( "set_parameter_default", pd.fix.kind, "con arreglo" );
    String antes = tm.getXML();
    Corrida c = arreglar( tm, "{\"language\":\"es\",\"keys\":[\"" + desc.key + "\"],\"values\":{\"" + var.key
      + "\":\"C:/datos\",\"" + pd.key + "\":\"valor\"}}", true );
    igual( 3, c.hecho.cambios, c.texto() );
    igual( 1, c.hecho.pasosDeshacer, "un Ctrl+Z" );
    for ( JsonNode x : c.hecho.aplicados ) {
      cierto( x.path( "undoable" ).asBoolean(), "deshacible: " + x );
    }
    contiene( c.hecho.aplicados.toString(), "Declarado el parámetro DIR_ENTRADA", "el cambio en castellano" );
    igual( "valor", tm.getParameterDefault( "SIN_DEFAULT" ), "default puesto" );
    igual( "C:/datos", tm.getParameterDefault( "DIR_ENTRADA" ), "parámetro declarado" );
    contiene( tm.getDescription(), "Lee «", "descripción puesta" );
    deshacer( tm );
    igual( antes, tm.getXML(), "Ctrl+Z deja el documento como antes" );
  }

  /** disabled_hop se habilita y orphan_step se borra (con confirmación); Ctrl+Z devuelve los dos. */
  public void pruebaHopYStepSuelto() throws Exception {
    TransMeta tm = variante( "02", "nuevo_hop.ktr" );
    TransHopMeta h = tm.getTransHop( 0 );
    h.setEnabled( false );
    TransOps.agregarStep( tm, "Dummy", "Suelto", null, 500, 500, null, null, null );
    Resultado r = lint( tm, "en", true );
    Hallazgo dh = deRegla( r, "disabled_hop" );
    Hallazgo os = deRegla( r, "orphan_step" );
    noNulo( dh, "hop deshabilitado: " + claves( r ) );
    noNulo( os, "step suelto: " + claves( r ) );
    cierto( dh.fix.confirm && os.fix.confirm, "los dos con confirmación" );
    String antes = tm.getXML();
    Corrida c = arreglar( tm, "{\"keys\":[\"" + dh.key + "\",\"" + os.key + "\"]}", true );
    igual( 2, c.hecho.cambios, c.texto() );
    cierto( tm.getTransHop( 0 ).isEnabled(), "habilitado" );
    nulo( tm.findStep( "Suelto" ), "borrado" );
    contiene( c.hecho.aplicados.toString(), "Deleted the loose step 'Suelto'", "lo dice" );
    Resultado d = lint( tm, "en", true );
    nulo( deRegla( d, "disabled_hop" ), "arreglado" );
    nulo( deRegla( d, "orphan_step" ), "arreglado" );
    deshacer( tm );
    igual( antes, tm.getXML(), "Ctrl+Z devuelve el hop deshabilitado y el step" );
  }

  /** password_in_file: ${DB_PASSWORD_<CONEXIÓN>} con confirmación (ya no pide valor) y nunca la clave en una nota. */
  public void pruebaClaveAVariable() throws Exception {
    TransMeta tm = variante( "07", "nuevo_clave.ktr", "<username/>\n    <password/>",
      "<username>admin</username>\n    <password>secreto123</password>" );
    Resultado r = lint( tm, "es", true );
    Hallazgo h = deRegla( r, "password_in_file" );
    noNulo( h, "contraseña: " + claves( r ) );
    igual( "${DB_PASSWORD_SQLITE_DEMO}", h.fix.preview, "la variable propuesta" );
    cierto( h.fix.confirm && !h.fix.needsValue, "con confirmación, sin pedir valor" );
    Corrida c = arreglar( tm, "{\"language\":\"es\",\"keys\":[\"" + h.key + "\"]}", true );
    igual( 1, c.hecho.cambios, c.texto() );
    igual( "${DB_PASSWORD_SQLITE_DEMO}", tm.findDatabase( "sqlite_demo" ).getPassword(), "la variable" );
    String todo = c.hecho.aplicados.toString() + c.hecho.notas;
    contiene( todo, "DB_PASSWORD_SQLITE_DEMO=", "dice qué línea poner en kettle.properties" );
    contiene( todo, "kettle.properties", "y dónde" );
    noContiene( todo, "secreto123", "nunca la clave" );
    noContiene( tm.getXML(), "secreto123", "ni en el XML" );
    nulo( deRegla( lint( tm, "es", true ), "password_in_file" ), "arreglado" );
  }

  /** duplicate_step_name se evalúa aunque el documento tenga cambios sin guardar (lee el archivo del disco). */
  public void pruebaNombreDuplicadoSinGuardar() throws Exception {
    EntornoKettle.iniciar();
    File f = EntornoKettle.ejemplo( "02" );
    String x = EntornoKettle.leer( f );
    // un segundo step con el mismo nombre que el primero, como queda un .ktr editado a mano
    int i = x.indexOf( "<step>" );
    int j = x.indexOf( "</step>", i ) + "</step>".length();
    String paso = x.substring( i, j );
    String doble = paso.replaceFirst( "<xloc>\\d+</xloc>", "<xloc>900</xloc>" );
    File g = new File( f.getParentFile(), "nuevo_dup.ktr" );
    EntornoKettle.escribir( g, x.substring( 0, j ) + "\n  " + doble + x.substring( j ) );
    TransMeta tm = new TransMeta( g.getAbsolutePath() );
    tm.setChanged(); // cambios sin guardar
    Resultado r = lint( tm, "es", true );
    noNulo( deRegla( r, "duplicate_step_name" ), "evaluada con cambios sin guardar: " + claves( r ) );
    for ( NoEvaluado n : r.notEvaluated ) {
      falso( "duplicate_step_name".equals( n.rule ), "no queda sin evaluar: " + n );
    }
    // sin archivo: solo lo que hay en memoria, sin «sin evaluar»
    TransMeta nuevo = new TransMeta();
    nuevo.setName( "sin_archivo" );
    TransOps.agregarStep( nuevo, "Dummy", "A", null, 100, 100, null, null, null );
    Resultado r2 = lint( nuevo, "es", true );
    for ( NoEvaluado n : r2.notEvaluated ) {
      falso( "duplicate_step_name".equals( n.rule ), "sin archivo no queda sin evaluar: " + n );
    }
  }

  /** select_star: SELECT * FROM una tabla → sus columnas (calculadas contra la base SQLite de la prueba). */
  public void pruebaSelectEstrella() throws Exception {
    EntornoKettle.iniciar();
    File dir = EntornoKettle.salida( "nuevo-select" );
    dir.mkdirs();
    File db = new File( dir, "ciudades.sqlite" );
    if ( db.exists() ) {
      db.delete();
    }
    Class.forName( "org.sqlite.JDBC" );
    try ( Connection cn = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          Statement st = cn.createStatement() ) {
      st.execute( "CREATE TABLE ciudades (id INTEGER, ciudad TEXT, valor REAL)" );
      st.execute( "INSERT INTO ciudades VALUES (1, 'Madrid', 2.5)" );
    }
    TransMeta tm = variante( "07", "nuevo_select.ktr",
      "${Internal.Entry.Current.Directory}/salida/demo.sqlite", db.getAbsolutePath().replace( '\\', '/' ) );
    StepMeta s = tm.findStep( "Consultar SQLite" );
    String xml = s.getXML().replaceAll( "(?s)<sql>.*?</sql>", "<sql>SELECT * FROM ciudades WHERE valor &gt; 0</sql>" );
    TransOps.reconfigurar( tm, s, xml.substring( xml.indexOf( "<sql>" ), xml.indexOf( "</sql>" ) + 6 ), null );
    Resultado r = lint( tm, "en", true );
    Hallazgo h = deRegla( r, "select_star" );
    noNulo( h, "SELECT *: " + claves( r ) );
    igual( "expand_select_star", h.fix.kind, "con arreglo" );
    Corrida c = arreglar( tm, "{\"keys\":[\"" + h.key + "\"]}", true );
    igual( 1, c.hecho.cambios, c.texto() );
    contiene( tm.findStep( "Consultar SQLite" ).getXML(), "SELECT id, ciudad, valor FROM ciudades WHERE valor",
      "las columnas, y el resto de la consulta igual" );
    nulo( deRegla( lint( tm, "en", true ), "select_star" ), "arreglado" );
  }

  /** language=es: change, reason y notes de apply_lint_fixes en castellano (en por defecto, como antes). */
  public void pruebaTextosEnCastellano() throws Exception {
    TransMeta tm = variante( "03", "nuevo_es.ktr", "Ordenar por producto", "Sort rows" );
    Corrida c = arreglar( tm, "{\"keys\":[\"all_auto\",\"no:existe\"],\"language\":\"es\"}", true );
    contiene( c.hecho.aplicados.toString(), "Step «Sort rows» renombrado a «Ordenar por producto»", "change" );
    contiene( c.texto(), "ya no está: las reglas ya no lo señalan", "reason" );
    ObjectNode o = LintArreglos.respuesta( "T", c.an, c.hecho, lint( tm, "es", true ), false, true, true, "es" );
    contiene( o.path( "notes" ).toString(), "Un Ctrl+Z en Spoon deshace todos los cambios", "notes" );
    TransMeta en = variante( "03", "nuevo_en.ktr", "Ordenar por producto", "Sort rows" );
    Corrida ce = arreglar( en, "{\"keys\":[\"all_auto\"]}", true );
    contiene( ce.hecho.aplicados.toString(), "Renamed step 'Sort rows' to", "en inglés por defecto" );
  }

  /** UndoGrupo.continuando: la primera acción del grupo nuevo se encadena a la última del anterior. */
  public void pruebaUndoQueSigue() throws Exception {
    TransMeta tm = variante( "02", "nuevo_sigue.ktr" );
    UndoGrupo a = UndoGrupo.sinSpoon( tm );
    StepMeta s = tm.getStep( 0 );
    a.cambio( new Object[] { s.clone() }, new Object[] { s.clone() }, new int[] { 0 } );
    TransAction primera = tm.viewThisUndo();
    UndoGrupo b = UndoGrupo.continuando( null, tm );
    b.cambio( new Object[] { s.clone() }, new Object[] { s.clone() }, new int[] { 0 } );
    cierto( tm.viewThisUndo() != primera && tm.viewThisUndo().getNextAlso(), "encadenada a la del grupo anterior" );
    // si alguien hizo algo en medio, no
    tm.addUndo( new Object[] { s.clone() }, new Object[] { s.clone() }, new int[] { 0 }, null, null,
      AbstractMeta.TYPE_UNDO_CHANGE, false );
    UndoGrupo c = UndoGrupo.continuando( null, tm );
    c.cambio( new Object[] { s.clone() }, new Object[] { s.clone() }, new int[] { 0 } );
    falso( tm.viewThisUndo().getNextAlso(), "con otra acción en medio, grupo nuevo" );
  }

  /** Lo que haría stress_test: entradas, variantes y por qué no corre con una base que no es archivo (sin conectarse). */
  public void pruebaPreviaDeLaPrueba() throws Exception {
    TransMeta tm = variante( "07", "nuevo_previa.ktr", "<type>SQLITE</type>", "<type>MYSQL</type>",
      "<server/>", "<server>db.invalid</server>" );
    EstresTools.Pedido p = EstresTools.pedido( McpHttpServer.JSON.readTree( "{\"language\":\"es\"}" ) );
    ObjectNode o = EstresTools.previa( tm, p );
    falso( o.path( "will_run" ).asBoolean(), "no va a correr: " + o );
    contiene( o.path( "not_run_reason" ).asText(), "no son archivos SQLite/H2", "el motivo en castellano" );
    contiene( o.path( "not_run_reason" ).asText(), "MYSQL", "y qué base" );
    cierto( o.path( "variants" ).size() >= 3, "las variantes" );
    TransMeta csv = variante( "02", "nuevo_previa_csv.ktr" );
    ObjectNode o2 = EstresTools.previa( csv, p );
    cierto( o2.path( "will_run" ).asBoolean(), "con un CSV sí: " + o2 );
    igual( 1, o2.path( "inputs" ).size(), "una entrada" );
    cierto( o2.path( "inputs" ).get( 0 ).path( "base_rows" ).asInt() > 0, "con filas de base" );
  }
}
