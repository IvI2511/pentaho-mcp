package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.ReglaEvaluada;
import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Catalogo;
import io.github.ivi2511.pentahomcp.lint.CategoriasStep;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.NoEvaluado;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * El perfil del linter del proyecto o del curso (0.7, E5: {@link PerfilEquipo}, .pentaho-live/lint.yaml): lectura
 * (YAML, JSON, los on/off de YAML 1.1, avisos y errores con la clave), búsqueda subiendo carpetas, las reglas que apaga,
 * enciende y endurece, el mínimo, sus reglas propias (naming_convention con su arreglo de un Ctrl+Z,
 * step_without_description, required_variable de rutas y hosts, team_pattern con match/not_match y el corte por
 * tiempo de una regex catastrófica), los nombres propuestos con prefijo, el JSON de lint_etl y el renglón lint:profile
 * del corrector. Sin Spoon.
 */
public class PruebaPerfilEquipo {

  private File base;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    base = EntornoKettle.salida( "perfil-equipo" );
    EntornoKettle.borrar( base.toPath() );
    base.mkdirs();
  }

  static final String PERFIL = "version: 1\n"
    + "name: Curso BD\n"
    + "description: Convenciones del curso\n"
    + "min_score: 60\n"
    + "rules:\n"
    + "  select_star: off\n"
    + "  default_step_name: warning\n"
    + "  db_output_without_error_handling: on\n"
    + "naming:\n"
    + "  steps:\n"
    + "    input: IN_\n"
    + "    output: OUT_\n"
    + "    by_type: { SortRows: ORD_ }\n"
    + "  entries:\n"
    + "    transformation: TR_\n";

  // ================================================================ lectura

  public void pruebaA_Lectura() throws Exception {
    PerfilEquipo p = PerfilEquipo.desdeTexto( PERFIL + "colores: rojo\n", false );
    igual( "Curso BD", p.nombre, "name" );
    igual( Integer.valueOf( 60 ), p.minScore, "min_score" );
    // YAML 1.1: off/on llegan como booleanos
    igual( "off", p.ajuste( "select_star" ), "select_star: off" );
    igual( "on", p.ajuste( "db_output_without_error_handling" ), "on" );
    igual( Sev.WARNING, p.gravedad( "default_step_name" ), "gravedad fijada" );
    igual( "IN_", p.prefijosStep.get( "input" ), "prefijo input" );
    igual( "ORD_", p.prefijoStep( "SortRows" ), "by_type manda" );
    igual( "IN_", p.prefijoStep( "CsvInput" ), "CsvInput es input" );
    nulo( p.prefijoStep( "Calculator" ), "transform sin prefijo" );
    igual( "TR_", p.prefijoEntrada( "TRANS" ), "entrada de transformación" );
    contiene( p.avisos.toString(), "colores", "clave desconocida: aviso, no error" );
    cierto( p.seccionActiva( PerfilEquipo.NAMING ), "naming activa" );
    falso( p.seccionActiva( PerfilEquipo.TEAM_PATTERN ), "sin team_rules" );

    PerfilEquipo j = PerfilEquipo.desdeTexto( "{\"name\":\"J\",\"min_score\":80,\"rules\":{\"cycle\":\"error\","
      + "\"no_such_rule\":\"on\"},\"required_variables\":{\"paths\":\"${DIR_DATOS}\",\"hosts\":[\"DB_HOST\"]}}",
      true );
    igual( "J", j.nombre, "JSON" );
    igual( Arrays.asList( "DIR_DATOS" ), j.variablesRuta, "${X} y texto suelto" );
    contiene( j.avisos.toString(), "no_such_rule", "id desconocido: aviso" );

    contiene( lanza( IllegalArgumentException.class, () -> PerfilEquipo.desdeTexto( "rules:\n  cycle: fatal\n",
      false ), "gravedad inválida" ).getMessage(), "rules.cycle", "dice la clave" );
    contiene( lanza( IllegalArgumentException.class, () -> PerfilEquipo.desdeTexto( "min_score: 120\n", false ),
      "fuera de 0-100" ).getMessage(), "min_score", "min_score" );
    contiene( lanza( IllegalArgumentException.class, () -> PerfilEquipo.desdeTexto( "team_rules:\n  - id: x\n"
      + "    pattern: '(abc'\n", false ), "regex rota" ).getMessage(), "team_rules[0] (x).pattern", "regex" );
    contiene( lanza( IllegalArgumentException.class, () -> PerfilEquipo.desdeTexto( "team_rules:\n  - id: x\n",
      false ), "sin pattern" ).getMessage(), "pattern is required", "pattern obligatorio" );
    lanza( IllegalArgumentException.class, () -> PerfilEquipo.desdeTexto( "- a\n- b\n", false ), "no es un mapa" );
    lanza( IllegalArgumentException.class, () -> PerfilEquipo.desdeTexto( "name: [\n", false ), "YAML roto" );
    // SafeConstructor: un tag de clase de Java no se instancia
    lanza( IllegalArgumentException.class, () -> PerfilEquipo.desdeTexto( "name: !!java.io.File [ x ]\n", false ),
      "sin clases de Java" );
  }

  public void pruebaB_Busqueda() throws Exception {
    File proy = new File( base, "proy" );
    File sub = new File( proy, "etl/staging" );
    sub.mkdirs();
    File perfil = escribir( new File( proy, ".pentaho-live/lint.yaml" ), PERFIL );
    igual( perfil.getAbsolutePath(), PerfilEquipo.buscarArchivo( sub ).getAbsolutePath(), "sube carpetas" );
    PerfilEquipo.Busqueda b = PerfilEquipo.buscar( sub );
    noNulo( b.perfil, "lo carga" );
    igual( "Curso BD", b.perfil.nombre, "nombre" );

    PerfilEquipo.Busqueda none = PerfilEquipo.resolver( "none", sub, sub );
    cierto( none.desactivado, "none" );
    nulo( none.perfil, "none: sin perfil" );
    File vacia = new File( base, "vacia" );
    vacia.mkdirs();
    noNulo( PerfilEquipo.resolver( vacia.getAbsolutePath(), null, null ).error, "carpeta sin perfil: error explícito" );
    nulo( PerfilEquipo.resolver( null, vacia, vacia ).error, "sin perfil al buscar solo: no es un error" );

    File roto = escribir( new File( base, "roto/.pentaho-live/lint.yaml" ), "min_score: mucho\n" );
    PerfilEquipo.Busqueda r = PerfilEquipo.buscar( roto.getParentFile().getParentFile() );
    nulo( r.perfil, "roto: sin perfil" );
    contiene( r.error, "min_score", "roto: el error" );
    igual( roto.getAbsolutePath(), r.archivo.getAbsolutePath(), "y dónde" );

    // sin name: la carpeta del proyecto
    File sinNombre = escribir( new File( base, "Ventas 2026/.pentaho-live/lint.json" ), "{\"min_score\": 50}" );
    igual( "Ventas 2026", PerfilEquipo.cargar( sinNombre ).nombre, "nombre por defecto" );
    igual( sub.getAbsolutePath(), PerfilEquipo.carpetaDe( new File( sub, "x.ktr" ).toURI().toString() )
      .getAbsolutePath(), "carpetaDe una URL file:" );
    nulo( PerfilEquipo.carpetaDe( "sftp://host/x.ktr" ), "otra URL: nada" );

    ObjectNode j = PerfilEquipo.busquedaJson( b );
    cierto( j.path( "found" ).asBoolean(), "found" );
    igual( "naming_convention", j.path( "profile" ).path( "profile_rules_on" ).path( 0 ).asText(), "reglas del perfil" );
  }

  // ================================================================ con el motor

  public void pruebaC_NombresYGravedades() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    Resultado sin = lint( tm, null, null );
    PerfilEquipo p = PerfilEquipo.desdeTexto( PERFIL, false );
    Resultado con = lint( tm, p, null );
    List<Hallazgo> nombres = de( con, "naming_convention" );
    igual( 3, nombres.size(), "Leer ventas (IN_), Ordenar por producto (ORD_) y Escribir totales (OUT_): " + nombres );
    Hallazgo leer = enStep( nombres, "Leer ventas" );
    igual( "IN_Leer ventas", leer.fix.preview, "propone el prefijo" );
    igual( Arreglo.RENAME, leer.fix.kind, "renombrar" );
    cierto( leer.fix.auto && !leer.fix.confirm && !leer.fix.needsValue, "automático" );
    igual( "input", leer.evidence.get( "category" ), "categoría" );
    igual( "by_type", enStep( nombres, "Ordenar por producto" ).evidence.get( "category" ), "por tipo" );
    cierto( con.score < sin.score, "las del perfil restan: " + sin.score + " -> " + con.score );
    igual( "Curso BD", con.perfil.nombre, "resultado con perfil" );
    igual( Boolean.TRUE, con.pasaMinimo, "mínimo 60" );
    // el JSON de lint_etl
    JsonNode j = LintJson.resultado( con ).path( "profile" );
    igual( "Curso BD", j.path( "name" ).asText(), "profile.name" );
    igual( 60, j.path( "min_score" ).asInt(), "profile.min_score" );
    cierto( j.path( "passed" ).asBoolean(), "profile.passed" );
    falso( LintJson.resultado( sin ).has( "profile" ), "sin perfil no hay profile" );

    // gravedad y mínimo
    PerfilEquipo duro = PerfilEquipo.desdeTexto( PERFIL.replace( "min_score: 60", "min_score: 99" )
      + "  # nada\n", false );
    duro.reglas.put( "naming_convention", "warning" );
    Resultado r = lint( tm, duro, null );
    for ( Hallazgo h : de( r, "naming_convention" ) ) {
      igual( Sev.WARNING, h.severity, "gravedad del perfil" );
    }
    igual( Boolean.FALSE, r.pasaMinimo, "99 no se alcanza" );
    falso( LintJson.resultado( r ).path( "profile" ).path( "passed" ).asBoolean(), "passed false" );

    // reglas explícitas: el perfil no cambia qué corre
    Resultado sola = lint( tm, p, new LinkedHashSet<String>( Arrays.asList( "naming_convention" ) ) );
    for ( Hallazgo h : sola.findings ) {
      igual( "naming_convention", h.rule, "solo la pedida" );
    }
  }

  public void pruebaD_ArregloDeUnCtrlZ() throws Exception {
    TransMeta tm = (TransMeta) ReportTools.clonar( EntornoKettle.trans( "03" ) );
    Opciones op = opciones( tm, PerfilEquipo.desdeTexto( PERFIL, false ), "es" );
    LintArreglos.Pedido ped = LintArreglos.pedido( McpHttpServer.JSON.readTree( "{\"keys\":[\"all_auto\"],"
      + "\"language\":\"es\"}" ) );
    LintArreglos.Analisis an = LintArreglos.analizar( tm, op, ped );
    LintArreglos.Hecho h = LintArreglos.aplicar( tm, an, "es", UndoGrupo.sinSpoon( tm ), null, null );
    noNulo( tm.findStep( "IN_Leer ventas" ), "renombrado: " + h.aplicados );
    noNulo( tm.findStep( "OUT_Escribir totales" ), "salida renombrada" );
    noNulo( tm.findStep( "ORD_Ordenar por producto" ), "por tipo" );
    igual( 1, h.pasosDeshacer, "un solo Ctrl+Z" );
    // otra pasada: la convención ya se cumple
    igual( 0, de( lint( tm, PerfilEquipo.desdeTexto( PERFIL, false ), null ), "naming_convention" ).size(),
      "sin hallazgos de nombres" );
  }

  public void pruebaE_NombrePorDefectoConPrefijo() throws Exception {
    TransMeta tm = (TransMeta) ReportTools.clonar( EntornoKettle.trans( "03" ) );
    StepMeta s = tm.findStep( "Leer ventas" );
    s.setName( "CSV file input" );
    Opciones op = opciones( tm, PerfilEquipo.desdeTexto( PERFIL, false ), "es" );
    Resultado r = LintMotor.analizar( tm, op );
    LintPropuestas.completar( r, tm, "es" );
    Hallazgo d = enStep( de( r, "default_step_name" ), "CSV file input" );
    igual( Sev.WARNING, d.severity, "default_step_name: warning en el perfil" );
    noNulo( d.fix.preview, "hay propuesta" );
    cierto( d.fix.preview.startsWith( "IN_" ), "la propuesta lleva el prefijo: " + d.fix.preview );
    for ( Hallazgo h : de( r, "naming_convention" ) ) {
      falso( "CSV file input".equals( h.step ), "el nombre por defecto no se marca dos veces" );
    }
  }

  public void pruebaF_ApagarYEncender() throws Exception {
    TransMeta tm = EntornoKettle.trans( "07" ); // Table input con SELECT *?
    PerfilEquipo p = PerfilEquipo.desdeTexto( "rules:\n  orphan_step: off\n  cycle: off\n", false );
    Opciones op = opciones( tm, p, "en" );
    Resultado r = LintMotor.analizar( tm, op );
    igual( 0, de( r, "orphan_step" ).size(), "apagada" );
    igual( 0, de( r, "cycle" ).size(), "cycle apagada no se muestra" );
    // una apagada por defecto encendida por el perfil corre y resta
    Regla db = Catalogo.buscar( "db_output_without_error_handling" );
    cierto( db.apagada(), "apagada por defecto" );
    cierto( PerfilEquipo.desdeTexto( PERFIL, false ).enciende( db ), "el perfil la enciende" );
    cierto( PerfilEquipo.desdeTexto( PERFIL, false ).ajustar( Catalogo.seleccionar( null, false ) ).contains( db ),
      "y corre" );
    falso( PerfilEquipo.desdeTexto( PERFIL, false ).ajustar( Catalogo.seleccionar( null, false ) ).contains(
      Catalogo.buscar( "select_star" ) ), "select_star off" );
  }

  public void pruebaG_VariablesObligatorias() throws Exception {
    TransMeta tm = (TransMeta) ReportTools.clonar( EntornoKettle.trans( "03" ) );
    tm.addDatabase( new DatabaseMeta( "dw", "POSTGRESQL", "Native", "db.example.com", "dw", "5432", "u", "" ) );
    tm.addDatabase( new DatabaseMeta( "ok", "POSTGRESQL", "Native", "${DB_HOST}", "dw", "5432", "u", "" ) );
    PerfilEquipo p = PerfilEquipo.desdeTexto( "required_variables:\n  paths: [DIR_DATOS]\n  hosts: DB_HOST\n",
      false );
    Resultado r = lint( tm, p, null );
    List<Hallazgo> l = de( r, "required_variable" );
    List<String> objetos = new ArrayList<String>();
    for ( Hallazgo h : l ) {
      objetos.add( h.step != null ? h.step : h.object );
    }
    cierto( objetos.contains( "Leer ventas" ), "la ruta con ${Internal…} no es la del perfil: " + objetos );
    cierto( objetos.contains( "dw" ), "host a mano: " + objetos );
    falso( objetos.contains( "ok" ), "host con la variable: " + objetos );
    Hallazgo host = null;
    for ( Hallazgo h : l ) {
      host = "dw".equals( h.object ) ? h : host;
    }
    igual( Hallazgo.CONNECTION, host.scope, "en la conexión" );
    contiene( host.message, "${DB_HOST}", "dice la variable" );
    igual( Sev.WARNING, host.severity, "warning" );
    // con Internal.Entry.Current.Directory permitido, las rutas de los ejemplos pasan
    PerfilEquipo p2 = PerfilEquipo.desdeTexto( "required_variables:\n  paths: [DIR_DATOS, "
      + "Internal.Entry.Current.Directory]\n", false );
    igual( 0, de( lint( EntornoKettle.trans( "03" ), p2, null ), "required_variable" ).size(), "rutas con variable: "
      + de( lint( EntornoKettle.trans( "03" ), p2, null ), "required_variable" ) );
    // absolute_path propone la variable del perfil
    TransMeta abs = (TransMeta) ReportTools.clonar( EntornoKettle.trans( "03" ) );
    String xml = abs.getXML().replace( "${Internal.Entry.Current.Directory}/datos/ventas.csv", "C:/datos/ventas.csv" );
    TransMeta conAbs = new TransMeta( new java.io.ByteArrayInputStream( xml.getBytes( "UTF-8" ) ), null, true, null,
      null );
    Hallazgo a = enStep( de( lint( conAbs, p, null ), "absolute_path" ), "Leer ventas" );
    igual( "${DIR_DATOS}/ventas.csv", a.fix.preview, "absolute_path con la variable del perfil" );
    igual( "DIR_DATOS", a.fix.params.get( "parameter" ), "y su parámetro" );
  }

  public void pruebaH_ReglasDelEquipo() throws Exception {
    TransMeta tm = (TransMeta) ReportTools.clonar( EntornoKettle.trans( "03" ) );
    tm.findStep( "Calcular importe" ).setDescription( "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!" );
    PerfilEquipo p = PerfilEquipo.desdeTexto( "team_rules:\n"
      + "  - id: mayusculas\n    title: Nombres con prefijo\n    where: name\n    must: match\n"
      + "    pattern: '^[A-Z]{2,4}_'\n    severity: info\n"
      + "  - id: sin_csv\n    message: \"{where} lee un CSV ({match})\"\n    where: path\n    pattern: '\\.csv$'\n"
      + "    step_types: [CsvInput]\n"
      + "  - id: lenta\n    where: description\n    pattern: '^(.*a){20}$'\n", false );
    Resultado r = lint( tm, p, null );
    List<Hallazgo> l = de( r, "team_pattern" );
    int mayus = 0;
    Hallazgo csv = null;
    for ( Hallazgo h : l ) {
      mayus += "mayusculas".equals( h.attribute ) ? 1 : 0;
      csv = "sin_csv".equals( h.attribute ) ? h : csv;
    }
    igual( 5, mayus, "must: match: los 5 steps sin prefijo" );
    noNulo( csv, "not_match: el CSV" );
    igual( "Leer ventas lee un CSV (.csv)", csv.message, "el mensaje del perfil con {where} y {match}" );
    igual( Sev.WARNING, csv.severity, "warning por defecto" );
    igual( "team_pattern:step:Leer ventas:sin_csv", csv.key, "key con el id de la regla del equipo" );
    boolean cortada = false;
    for ( NoEvaluado n : r.notEvaluated ) {
      cortada |= "team_pattern".equals( n.rule ) && "timeout".equals( n.code );
    }
    cierto( cortada, "la regex catastrófica se corta por tiempo: " + r.notEvaluated );
  }

  public void pruebaI_DescripcionesYJob() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    PerfilEquipo p = PerfilEquipo.desdeTexto( "step_description:\n  required: true\n  except: [CsvInput]\n", false );
    igual( 4, de( lint( tm, p, null ), "step_without_description" ).size(), "5 steps sin descripción, uno exceptuado" );

    JobMeta jm = EntornoKettle.job( "10" );
    Resultado r = lint( jm, PerfilEquipo.desdeTexto( PERFIL, false ), null );
    List<Hallazgo> l = de( r, "naming_convention" );
    igual( 2, l.size(), "las dos entradas de transformación: " + l );
    for ( Hallazgo h : l ) {
      igual( Arreglo.RENAME_ENTRY, h.fix.kind, "renombrar la entrada" );
      cierto( h.fix.preview.startsWith( "TR_" ), "con TR_: " + h.fix.preview );
    }
  }

  public void pruebaJ_CategoriasYCatalogo() {
    igual( CategoriasStep.LOOKUP, CategoriasStep.deStep( "StreamLookup" ), "lookup" );
    igual( CategoriasStep.FILTER, CategoriasStep.deStep( "FilterRows" ), "filter" );
    igual( CategoriasStep.OUTPUT, CategoriasStep.deStep( "TableOutput" ), "output" );
    igual( CategoriasStep.E_JOB, CategoriasStep.deEntrada( "JOB" ), "job" );
    nulo( CategoriasStep.deStep( "NoExiste" ), "desconocido" );
    for ( String id : Arrays.asList( PerfilEquipo.NAMING, PerfilEquipo.STEP_DESCRIPTION, PerfilEquipo.REQUIRED_VARIABLE,
      PerfilEquipo.TEAM_PATTERN ) ) {
      Regla r = Catalogo.buscar( id );
      noNulo( r, "en el catálogo: " + id );
      cierto( r.apagada(), "apagada sin perfil: " + id );
      falso( Catalogo.seleccionar( null, false ).contains( r ), "sin perfil no corre: " + id );
    }
  }

  // ================================================================ corrector

  public void pruebaK_CorrectorLintProfile() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    File pack = new File( base, "pack" );
    escribir( new File( pack, ".pentaho-live/lint.yaml" ), PERFIL.replace( "min_score: 60", "min_score: 50" ) );
    ReglaEvaluada ok = CorrectorPerfil.evaluar( pack, tm, "es" );
    cierto( ok.evaluada, "evaluada: " + ok.detalle );
    cierto( ok.aprobada, "llega a 50: " + ok.detalle );
    contiene( ok.detalle, "Curso BD", "nombra el perfil" );
    escribir( new File( pack, ".pentaho-live/lint.yaml" ), PERFIL.replace( "min_score: 60", "min_score: 100" ) );
    falso( CorrectorPerfil.evaluar( pack, tm, "es" ).aprobada, "100 no" );
    ReglaEvaluada sin = CorrectorPerfil.evaluar( new File( base, "pack-sin-perfil" ), tm, "es" );
    falso( sin.evaluada, "sin perfil: no evaluada" );
    contiene( sin.detalle, ".pentaho-live/lint.yaml", "dice dónde va" );
  }

  // ================================================================ ayudas

  private static Opciones opciones( AbstractMeta m, PerfilEquipo p, String lang ) {
    Opciones op = new Opciones();
    op.lang = lang;
    op.perfil = p;
    op.entorno = new LintEntorno( m );
    return op;
  }

  private static Resultado lint( AbstractMeta m, PerfilEquipo p, java.util.Set<String> reglas ) {
    Opciones op = opciones( m, p, "es" );
    op.rules = reglas;
    return LintMotor.analizar( m, op );
  }

  private static List<Hallazgo> de( Resultado r, String regla ) {
    List<Hallazgo> l = new ArrayList<Hallazgo>();
    for ( Hallazgo h : r.findings ) {
      if ( regla.equals( h.rule ) ) {
        l.add( h );
      }
    }
    return l;
  }

  private static Hallazgo enStep( List<Hallazgo> l, String step ) {
    for ( Hallazgo h : l ) {
      if ( step.equals( h.step ) ) {
        return h;
      }
    }
    throw new AssertionError( "sin hallazgo en " + step + ": " + l );
  }

  static File escribir( File f, String texto ) throws Exception {
    f.getParentFile().mkdirs();
    EntornoKettle.escribir( f, texto );
    return f;
  }
}
