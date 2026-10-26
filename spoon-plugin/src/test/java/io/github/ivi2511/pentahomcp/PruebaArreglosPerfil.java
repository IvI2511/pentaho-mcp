package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.util.ArrayList;
import java.util.List;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;
import io.github.ivi2511.pentahomcp.lint.Resultado;

/**
 * Los arreglos automáticos de 0.7.1 de las reglas del perfil: step_without_description propone una descripción a partir
 * del tipo y la configuración del step («Lee clientes.csv (6 campos)», «Filtra filas con …»), con confirmación, y se
 * deshace con un Ctrl+Z (también en un job); required_variable de hosts pasa host, puerto y base de la conexión a
 * ${DB_HOST_&lt;CONN&gt;}… con las líneas de kettle.properties y nunca toca la contraseña.
 */
public class PruebaArreglosPerfil {

  static final String PERFIL = "version: 1\nname: Pulido\nstep_description:\n  required: true\nrequired_variables:\n"
    + "  hosts: [DB_HOST]\n";

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static Opciones opciones( AbstractMeta copia, String lang ) {
    Opciones op = new Opciones();
    op.lang = lang;
    op.sinGuardar = true;
    op.entorno = new LintEntorno( copia );
    op.perfil = PerfilEquipo.desdeTexto( PERFIL, false );
    return op;
  }

  private static Resultado lint( AbstractMeta doc, String lang ) {
    AbstractMeta copia = ReportTools.clonar( doc );
    Resultado r = LintMotor.analizar( copia, opciones( copia, lang ) );
    LintPropuestas.completar( r, copia, lang );
    return r;
  }

  private static List<Hallazgo> deRegla( Resultado r, String regla ) {
    List<Hallazgo> l = new ArrayList<Hallazgo>();
    for ( Hallazgo h : r.findings ) {
      if ( h.rule.equals( regla ) ) {
        l.add( h );
      }
    }
    return l;
  }

  private static LintArreglos.Hecho arreglar( AbstractMeta doc, List<Hallazgo> hs, String lang ) throws Exception {
    StringBuilder keys = new StringBuilder();
    for ( Hallazgo h : hs ) {
      keys.append( keys.length() == 0 ? "" : "," ).append( '"' ).append( h.key ).append( '"' );
    }
    LintArreglos.Pedido p = LintArreglos.pedido( McpHttpServer.JSON.readTree( "{\"keys\":[" + keys
      + "],\"language\":\"" + lang + "\"}" ) );
    AbstractMeta copia = ReportTools.clonar( doc );
    LintArreglos.Analisis an = LintArreglos.analizar( copia, opciones( copia, lang ), p );
    return LintArreglos.aplicar( doc, an, p.lang, UndoGrupo.sinSpoon( doc ), null, null );
  }

  /** Lo que hace SpoonJobDelegate con Ctrl+Z / Ctrl+Y sobre un cambio de entrada (PDI 9.4). */
  static void cambioDeJob( JobMeta jm, TransAction ta, boolean deshacer ) {
    igual( TransAction.TYPE_ACTION_CHANGE_JOB_ENTRY, ta.getType(), "cambio de entrada" );
    Object[] o = deshacer ? ta.getPrevious() : ta.getCurrent();
    for ( int i = 0; i < o.length; i++ ) {
      jm.getJobEntry( ta.getCurrentIndex()[ i ] ).replaceMeta( (JobEntryCopy) ( (JobEntryCopy) o[ i ] ).clone() );
    }
  }

  // ---------------------------------------------------------------- step_without_description

  public void pruebaDescripcionesPropuestasYUnCtrlZ() throws Exception {
    TransMeta tm = new TransMeta( EntornoKettle.ejemplo( "02" ).getAbsolutePath() );
    List<Hallazgo> hs = deRegla( lint( tm, "es" ), PerfilEquipo.STEP_DESCRIPTION );
    cierto( hs.size() >= 3, "un hallazgo por step sin descripción: " + hs.size() );
    StringBuilder todas = new StringBuilder();
    for ( Hallazgo h : hs ) {
      igual( Arreglo.SET_STEP_DESCRIPTION, h.fix.kind, "con arreglo" );
      cierto( h.fix.confirm, "con confirmación" );
      cierto( h.fix.preview != null && !h.fix.preview.isEmpty(), "con descripción propuesta" );
      todas.append( h.step ).append( " = " ).append( h.fix.preview ).append( '\n' );
    }
    String t = todas.toString();
    System.out.println( "  propuestas:\n" + t );
    contiene( t, "Lee clientes.csv (", "un CSV input: el archivo y sus campos: " + t );
    contiene( t, "Filtra filas con saldo >= ", "un Filter rows: su condición" );
    noContiene( t, "/", "sin carpetas" );
    String antes = tm.getXML();
    LintArreglos.Hecho h = arreglar( tm, hs, "es" );
    igual( hs.size(), h.aplicados.size(), "aplicadas todas: " + h.saltados );
    igual( 1, h.pasosDeshacer, "un Ctrl+Z" );
    for ( Hallazgo x : hs ) {
      igual( x.fix.preview, tm.findStep( x.step ).getDescription(), "descripción puesta en " + x.step );
    }
    igual( 0, deRegla( lint( tm, "es" ), PerfilEquipo.STEP_DESCRIPTION ).size(), "la regla ya no se queja" );
    String despues = tm.getXML();
    PruebaArreglosNuevos.deshacer( tm );
    igual( antes, tm.getXML(), "un Ctrl+Z deja todo como estaba" );
    PruebaArreglosNuevos.rehacer( tm );
    igual( despues, tm.getXML(), "Ctrl+Y las vuelve a poner" );
  }

  public void pruebaDescripcionEnUnJob() throws Exception {
    JobMeta jm = new JobMeta( EntornoKettle.ejemplo( "10" ).getAbsolutePath(), null );
    List<Hallazgo> hs = deRegla( lint( jm, "en" ), PerfilEquipo.STEP_DESCRIPTION );
    cierto( !hs.isEmpty(), "entradas sin descripción" );
    Hallazgo trans = null;
    for ( Hallazgo h : hs ) {
      if ( h.fix.preview.startsWith( "Runs the transformation " ) ) {
        trans = h;
      }
    }
    cierto( trans != null, "una entrada TRANS: «Runs the transformation x.ktr»: " + hs.get( 0 ).fix.preview );
    contiene( trans.fix.preview, ".ktr", "con el nombre del archivo" );
    List<Hallazgo> una = new ArrayList<Hallazgo>();
    una.add( trans );
    LintArreglos.Hecho h = arreglar( jm, una, "en" );
    igual( 1, h.aplicados.size(), "aplicada: " + h.saltados );
    igual( 1, h.pasosDeshacer, "un Ctrl+Z" );
    JobEntryCopy c = jm.findJobEntry( trans.entry, trans.entryNr, true );
    igual( trans.fix.preview, c.getDescription(), "descrita" );
    TransAction ta = jm.previousUndo();
    cambioDeJob( jm, ta, true );
    igual( "", c.getDescription() == null ? "" : c.getDescription(), "Ctrl+Z la quita" );
    cambioDeJob( jm, jm.nextUndo(), false );
    igual( trans.fix.preview, c.getDescription(), "Ctrl+Y la vuelve a poner" );
  }

  // ---------------------------------------------------------------- required_variable: hosts

  private static TransMeta conConexion() {
    TransMeta tm = new TransMeta();
    tm.setName( "con_conexion" );
    DatabaseMeta db = new DatabaseMeta( "ventas dw", "POSTGRESQL", "Native", "192.0.2.5", "dw", "5432", "etl",
      "Encrypted 2be98afc86aa7f2e4cb79ce10df90acde" );
    tm.addDatabase( db );
    StepMeta s = new StepMeta( "Dummy", "Paso", new DummyTransMeta() );
    s.setDescription( "Un paso" );
    s.setLocation( 100, 100 );
    tm.addStep( s );
    return tm;
  }

  public void pruebaHostAVariablesSinTocarLaClave() throws Exception {
    TransMeta tm = conConexion();
    DatabaseMeta db = tm.findDatabase( "ventas dw" );
    String clave = db.getPassword();
    List<Hallazgo> hs = deRegla( lint( tm, "en" ), PerfilEquipo.REQUIRED_VARIABLE );
    igual( 1, hs.size(), "el host literal" );
    Hallazgo h = hs.get( 0 );
    igual( Arreglo.USE_CONNECTION_VARIABLES, h.fix.kind, "con arreglo" );
    cierto( h.fix.confirm, "con confirmación" );
    igual( "${DB_HOST_VENTAS_DW} · ${DB_PORT_VENTAS_DW} · ${DB_NAME_VENTAS_DW}", h.fix.preview, "propuesta" );
    igual( "DB_HOST_VENTAS_DW=192.0.2.5\nDB_PORT_VENTAS_DW=5432\nDB_NAME_VENTAS_DW=dw", h.fix.params.get(
      "properties" ), "las líneas de kettle.properties" );
    noContiene( h.fix.params.toString(), "Encrypted", "nunca la contraseña" );
    String antes = tm.getXML();
    LintArreglos.Hecho r = arreglar( tm, hs, "en" );
    igual( 1, r.aplicados.size(), "aplicado: " + r.saltados );
    igual( "${DB_HOST_VENTAS_DW}", db.getHostname(), "host" );
    igual( "${DB_PORT_VENTAS_DW}", db.getDatabasePortNumberString(), "puerto" );
    igual( "${DB_NAME_VENTAS_DW}", db.getDatabaseName(), "base" );
    igual( clave, db.getPassword(), "la contraseña, intacta" );
    String notas = r.notas.toString();
    contiene( notas, "kettle.properties", "la nota dice dónde" );
    contiene( notas, "DB_HOST_VENTAS_DW=192.0.2.5", "y las líneas" );
    noContiene( notas, "Encrypted", "sin la contraseña" );
    igual( 0, deRegla( lint( tm, "en" ), PerfilEquipo.REQUIRED_VARIABLE ).size(),
      "${DB_HOST_VENTAS_DW} cumple con DB_HOST del perfil" );
    igual( 1, r.pasosDeshacer, "un Ctrl+Z" );
    PruebaArreglosNuevos.deshacer( tm );
    igual( antes, tm.getXML(), "Ctrl+Z deja la conexión como estaba" );
  }

  public void pruebaHostEnUnJobSeDeshace() throws Exception {
    JobMeta jm = new JobMeta( EntornoKettle.ejemplo( "10" ).getAbsolutePath(), null );
    DatabaseMeta db = new DatabaseMeta( "Almacén", "MYSQL", "Native", "db.example.invalid", "almacen", "3306", "etl",
      "" );
    jm.addDatabase( db );
    List<Hallazgo> hs = deRegla( lint( jm, "es" ), PerfilEquipo.REQUIRED_VARIABLE );
    igual( 1, hs.size(), "el host literal" );
    igual( "${DB_HOST_ALMACEN} · ${DB_PORT_ALMACEN} · ${DB_NAME_ALMACEN}", hs.get( 0 ).fix.preview,
      "sin acentos en el nombre de la variable" );
    LintArreglos.Hecho r = arreglar( jm, hs, "es" );
    igual( 1, r.aplicados.size(), "aplicado: " + r.saltados );
    cierto( r.aplicados.get( 0 ).path( "undoable" ).asBoolean(), "deshacible también en un job" );
    igual( "${DB_HOST_ALMACEN}", db.getHostname(), "host" );
    cambioDeJob( jm, jm.previousUndo(), true );
    igual( "db.example.invalid", db.getHostname(), "Ctrl+Z devuelve el host" );
    igual( "3306", db.getDatabasePortNumberString(), "el puerto" );
    igual( "almacen", db.getDatabaseName(), "y la base" );
  }

  public void pruebaVariablesDeConexion() {
    igual( "[DB_HOST_VENTAS, DB_PORT_VENTAS, DB_NAME_VENTAS]", java.util.Arrays.toString(
      io.github.ivi2511.pentahomcp.lint.reglas.ReglasPerfil.variablesConexion( "DB_HOST", "ventas" ) ), "DB_HOST" );
    igual( "[PG_SERVIDOR_DW, PG_SERVIDOR_PORT_DW, PG_SERVIDOR_NAME_DW]", java.util.Arrays.toString(
      io.github.ivi2511.pentahomcp.lint.reglas.ReglasPerfil.variablesConexion( "PG_SERVIDOR", "dw" ) ), "sin HOST" );
  }
}
