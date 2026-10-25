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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasBoceto;

/**
 * plan_build y el modo boceto sin Spoon (D1, D2): el esqueleto de marcadores con sus notas PENDIENTE en un solo
 * Ctrl+Z, el registro del plano en el .ktr, el progreso, la regla placeholder_step del linter, fill_placeholder
 * (también deshacerlo), mode=steps y la deriva (plan_check) contra un boceto y contra la solución de referencia de
 * Grano de Oro.
 */
public class PruebaPlanoConstruir {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static Plano miniEnArchivo() throws Exception {
    Plano p = PruebaPlano.mini();
    File f = new File( EntornoKettle.salida( "plano" ), "mini.plan.yaml" );
    p.guardar( f );
    return Plano.leer( f ).plano;
  }

  private static TransMeta nueva( String nombre ) {
    TransMeta tm = new TransMeta();
    tm.setName( nombre );
    return tm;
  }

  // ================================================================ boceto

  public void pruebaBocetoUnCtrlZ() throws Exception {
    Plano p = miniEnArchivo();
    TransMeta tm = nueva( "ventas" );
    ObjectNode o = PlanoConstruir.construirAhora( null, tm, p, "", PlanoConstruir.SKETCH, "es", null );
    igual( 6, tm.nrSteps(), "un step por etapa" );
    for ( StepMeta s : tm.getSteps() ) {
      igual( "Dummy", s.getStepID(), "todos marcadores: " + s.getName() );
      noNulo( s.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_MARCADOR ), "marcado: " + s.getName() );
    }
    // hops: leer -> limpiar -> buscar -> cargar, buscar -> rechazos, leer_clientes -> buscar (lookup)
    igual( 5, tm.nrTransHops(), "hops de after y de lookup" );
    noNulo( tm.findTransHop( tm.findStep( "Leer clientes" ), tm.findStep( "Buscar cliente" ) ), "lookup como hop" );
    noNulo( tm.findTransHop( tm.findStep( "Buscar cliente" ), tm.findStep( "Rechazos" ) ), "rama de rechazos" );
    igual( 6, tm.nrNotes(), "una nota por etapa" );
    NotePadMeta n = notaDe( tm, "Buscar cliente" );
    noNulo( n, "nota de Buscar cliente" );
    contiene( n.getNote(), "PENDIENTE · Buscar cliente", "primera línea" );
    contiene( n.getNote(), "cliente_id", "la clave de la búsqueda" );
    contiene( n.getNote(), "rechazar la fila", "qué hacer si falta" );
    igual( 236, n.getBackGroundColorRed(), "color de lookup" );
    // las notas no se pisan ni pisan steps
    sinSolapes( tm );
    // registro
    igual( p.archivo.getPath(), PlanoConstruir.planoDe( tm ), "el .ktr apunta al plano" );
    igual( 6, ReglasBoceto.registro( tm ).size(), "registro de etapas" );
    PlanoConstruir.Progreso pr = PlanoConstruir.progreso( tm );
    igual( 0, pr.construidas, "nada construido" );
    igual( 6, pr.pendientes.size(), "seis marcadores" );
    igual( 6, o.path( "notes_added" ).asInt(), "respuesta: notas" );
    contiene( o.path( "undo" ).asText(), "One Ctrl+Z", "respuesta: un Ctrl+Z" );
    // un solo Ctrl+Z quita todo
    deshacerUno( tm );
    igual( 0, tm.nrSteps(), "Ctrl+Z quita los steps" );
    igual( 0, tm.nrTransHops(), "y los hops" );
    igual( 0, tm.nrNotes(), "y las notas" );
  }

  public void pruebaSaltaLasQueYaEstanYNoRepite() throws Exception {
    Plano p = miniEnArchivo();
    TransMeta tm = nueva( "ventas" );
    PlanoConstruir.construirAhora( null, tm, p, "", PlanoConstruir.SKETCH, "en", null );
    ToolError e = lanza( ToolError.class, () -> PlanoConstruir.construirAhora( null, tm, p, "", PlanoConstruir.SKETCH,
      "en", null ), "todo ya estaba" );
    contiene( e.getMessage(), "already on the canvas", "lo dice" );
    TransMeta otra = nueva( "otra" );
    TransOps.agregarStep( otra, "Dummy", "Leer ventas", null, 100, 100, null, null, null );
    ObjectNode o = PlanoConstruir.construirAhora( null, otra, p, "", PlanoConstruir.SKETCH, "en", null );
    igual( "[\"Leer ventas\"]", o.path( "skipped_already_on_canvas" ).toString(), "salta la que ya estaba" );
    noNulo( otra.findTransHop( otra.findStep( "Leer ventas" ), otra.findStep( "Limpiar" ) ), "y se conecta con ella" );
    contiene( notaDe( otra, "Limpiar" ).getNote(), "TO DO · Limpiar", "notas en inglés" );
  }

  public void pruebaLintMarcaLosMarcadores() throws Exception {
    Plano p = miniEnArchivo();
    TransMeta tm = nueva( "ventas" );
    PlanoConstruir.construirAhora( null, tm, p, "", PlanoConstruir.SKETCH, "es", null );
    TransMeta copia = (TransMeta) ReportTools.clonar( tm );
    Opciones op = LintTools.opciones( McpHttpServer.JSON.readTree( "{\"rules\":[\"placeholder_step\"]}" ) );
    op.entorno = new LintEntorno( copia );
    Resultado r = LintMotor.analizar( copia, op );
    int n = 0;
    for ( Hallazgo h : r.findings ) {
      if ( "placeholder_step".equals( h.rule ) ) {
        n++;
      }
    }
    igual( 6, n, "placeholder_step en cada marcador (la copia conserva los atributos)" );
  }

  public void pruebaRellenarMarcador() throws Exception {
    Plano p = miniEnArchivo();
    TransMeta tm = nueva( "ventas" );
    PlanoConstruir.construirAhora( null, tm, p, "", PlanoConstruir.SKETCH, "es", null );
    int notas = tm.nrNotes();
    Point donde = new Point( tm.findStep( "Limpiar" ).getLocation().x, tm.findStep( "Limpiar" ).getLocation().y );
    List<PlanoConstruir.Pieza> ps = PlanoConstruir.piezas( McpHttpServer.JSON.readTree(
      "{\"steps\":[{\"type\":\"StringOperations\"},{\"type\":\"ReplaceString\",\"name\":\"Coma decimal\"}]}" ) );
    ObjectNode o = PlanoConstruir.rellenar( tm, "Limpiar", ps, false, UndoGrupo.sinSpoon( tm ), null );
    StepMeta a = tm.findStep( "Limpiar" );
    StepMeta b = tm.findStep( "Coma decimal" );
    igual( "StringOperations", a.getStepID(), "el primero conserva el nombre del marcador" );
    igual( "ReplaceString", b.getStepID(), "cadena" );
    noNulo( tm.findTransHop( tm.findStep( "Leer ventas" ), a ), "recibe lo que entraba" );
    noNulo( tm.findTransHop( a, b ), "cadena conectada" );
    noNulo( tm.findTransHop( b, tm.findStep( "Buscar cliente" ) ), "da lo que salía" );
    igual( "limpiar", a.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_ETAPA ), "enlazado a la etapa" );
    igual( notas - 1, tm.nrNotes(), "se quita su nota PENDIENTE" );
    nulo( notaDe( tm, "Limpiar" ), "ya no está" );
    igual( 1, o.path( "sketch" ).path( "built" ).asInt(), "progreso 1" );
    PlanoConstruir.Progreso pr = PlanoConstruir.progreso( tm );
    igual( 1, pr.construidas, "1 de 6" );
    igual( 5, pr.pendientes.size(), "5 pendientes" );
    // un Ctrl+Z: vuelve el marcador con su nota y sus hops
    deshacerUno( tm );
    StepMeta d = tm.findStep( "Limpiar" );
    noNulo( d, "vuelve el marcador" );
    igual( "Dummy", d.getStepID(), "es el Dummy" );
    nulo( tm.findStep( "Coma decimal" ), "sin la cadena" );
    noNulo( tm.findTransHop( tm.findStep( "Leer ventas" ), d ), "hop de entrada" );
    noNulo( tm.findTransHop( d, tm.findStep( "Buscar cliente" ) ), "hop de salida" );
    igual( notas, tm.nrNotes(), "vuelve la nota" );
    igual( donde.x, d.getLocation().x, "en su sitio" );
    // no es un marcador: error
    TransOps.agregarStep( tm, "Dummy", "Otro", null, 10, 10, null, null, null );
    lanza( ToolError.class, () -> PlanoConstruir.rellenar( tm, "Otro", ps, false, UndoGrupo.sinSpoon( tm ), null ),
      "un Dummy cualquiera no se rellena" );
  }

  public void pruebaModoSteps() throws Exception {
    Plano p = Plano.leer( PruebaPlano.ejemplo() ).plano;
    TransMeta tm = nueva( "04_hechos_ventas" );
    PlanoConstruir.construirAhora( null, tm, p, "04_hechos_ventas", PlanoConstruir.STEPS, "es", null );
    igual( "CsvInput", tm.findStep( "Leer ventas (CSV)" ).getStepID(), "step_type" );
    igual( "DBLookup", tm.findStep( "Buscar tienda" ).getStepID(), "lookup en una tabla" );
    igual( "TextFileOutput", tm.findStep( "Escribir rechazos" ).getStepID(), "rechazos" );
    PlanoConstruir.Progreso pr = PlanoConstruir.progreso( tm );
    igual( pr.total, pr.construidas, "todo con su step: nada pendiente" );
    contiene( notaDe( tm, "Buscar tienda" ).getNote(), "Step sugerido: DBLookup", "la nota dice el step" );
    // tipo por clase cuando no hay step_type
    Plano.Lectura l = Plano.leerTexto( "name: x\nsources:\n  - {id: s, name: s.xlsx, format: excel}\n"
      + "targets:\n  - {id: t, name: t, kind: dimension, load: scd2, key: [k]}\nstages:\n"
      + "  - {id: a, name: A, kind: extract, reads: [s]}\n  - {id: b, name: B, kind: load, after: [a], writes: [t]}\n"
      + "  - {id: c, name: C, kind: clean, after: [a]}\n" );
    igual( "ExcelInput", PlanoConstruir.tipoSugerido( l.plano, l.plano.etapa( "a" ) ), "excel" );
    igual( "DimensionLookup", PlanoConstruir.tipoSugerido( l.plano, l.plano.etapa( "b" ) ), "scd2" );
    igual( "Dummy", PlanoConstruir.tipoSugerido( l.plano, l.plano.etapa( "c" ) ), "limpiar no tiene un step claro" );
  }

  public void pruebaGrupoObligatorio() throws Exception {
    Plano p = Plano.leer( PruebaPlano.ejemplo() ).plano;
    ToolError e = lanza( ToolError.class, () -> PlanoTools.grupo( p, null ), "varias transformaciones" );
    contiene( e.getMessage(), "04_hechos_ventas", "las nombra" );
    igual( "04_hechos_ventas", PlanoTools.grupo( p, "04_HECHOS_VENTAS.ktr" ), "sin mayúsculas ni .ktr" );
    lanza( ToolError.class, () -> PlanoTools.grupo( p, "no_existe" ), "no existe" );
  }

  // ================================================================ deriva

  public void pruebaDerivaDeUnBoceto() throws Exception {
    Plano p = miniEnArchivo();
    TransMeta tm = nueva( "ventas" );
    PlanoConstruir.construirAhora( null, tm, p, "", PlanoConstruir.SKETCH, "es", null );
    Map<String, Object[]> docs = new LinkedHashMap<String, Object[]>();
    docs.put( "", new Object[] { tm, "open", "T1" } );
    PlanoDeriva.Resultado r = PlanoDeriva.revisar( p, docs, null, "es" );
    igual( 6, contar( r, "placeholder_left" ), "seis marcadores" );
    igual( 0, avisos( r ), "un boceto recién hecho no tiene deriva: " + r.json() );
    // se construye leer con un CSV que no lee ventas.csv: avisa
    List<PlanoConstruir.Pieza> ps = PlanoConstruir.piezas( McpHttpServer.JSON.readTree( "{\"type\":\"CsvInput\"}" ) );
    PlanoConstruir.rellenar( tm, "Leer ventas", ps, false, UndoGrupo.sinSpoon( tm ), null );
    r = PlanoDeriva.revisar( p, docs, null, "es" );
    igual( 1, contar( r, "source_not_referenced" ), "la fuente no se nombra: " + r.json() );
    // se borra un marcador: la etapa queda sin step
    StepMeta c = tm.findStep( "Cargar hechos" );
    for ( TransHopMeta h : TransOps.hopsDe( tm, c ) ) {
      tm.removeTransHop( tm.indexOfTransHop( h ) );
    }
    tm.removeStep( tm.indexOfStep( c ) );
    r = PlanoDeriva.revisar( p, docs, null, "es" );
    igual( 1, contar( r, "stage_without_step" ), "sin step" );
    cierto( r.json().path( "drift" ).asBoolean(), "hay deriva" );
  }

  /** La solución de referencia contra el plano del ejemplo: se encuentran las cinco y el job, sin falsos avisos graves. */
  public void pruebaDerivaGranoDeOro() throws Exception {
    Plano p = Plano.leer( PruebaPlano.ejemplo() ).plano;
    ObjectNode o = PlanoTools.deriva( Tools.obj().put( "plan", p.archivo.getPath() ).put( "language", "es" ) );
    System.out.println( "  plan_check del ejemplo: " + o );
    int encontradas = 0;
    for ( JsonNode t : o.path( "transformations" ) ) {
      encontradas += "file".equals( t.path( "found" ).asText() ) ? 1 : 0;
    }
    igual( 5, encontradas, "las cinco transformaciones en ../solucion-referencia" );
    contiene( o.path( "job" ).asText(), "job_carga_dw.kjb", "el job" );
    for ( JsonNode h : o.path( "findings" ) ) {
      String c = h.path( "code" ).asText();
      falso( c.equals( "transformation_not_in_job" ) || c.equals( "job_without_failure_path" ) || c.equals(
        "job_order_differs" ), "el job cumple el plano: " + h );
      falso( c.equals( "stage_without_step" ), "cada etapa tiene su step: " + h );
      falso( c.equals( "rejects_missing" ) || c.equals( "orphans_not_rejected" ), "los rechazos existen: " + h );
    }
    JobMeta jm = (JobMeta) PlanoTools.abrirArchivo( new File( p.archivo.getParentFile(),
      "../solucion-referencia/job_carga_dw.kjb" ) );
    igual( "[01_dim_tienda, 02_dim_producto, 03_dim_fecha, 04_hechos_ventas, 05_kpi_ventas]", PlanoDeriva.ordenJob( jm )
      .toString(), "el orden del job" );
  }

  // ================================================================ apoyo

  static NotePadMeta notaDe( TransMeta tm, String etapa ) {
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      if ( etapa.equals( PlanoConstruir.etapaDeNota( tm.getNote( i ) ) ) ) {
        return tm.getNote( i );
      }
    }
    return null;
  }

  private static void sinSolapes( TransMeta tm ) {
    List<int[]> rs = new ArrayList<int[]>();
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      NotePadMeta n = tm.getNote( i );
      rs.add( new int[] { n.getLocation().x, n.getLocation().y, n.getWidth(), n.getHeight() } );
    }
    for ( int i = 0; i < rs.size(); i++ ) {
      for ( int j = i + 1; j < rs.size(); j++ ) {
        int[] a = rs.get( i );
        int[] b = rs.get( j );
        falso( a[ 0 ] < b[ 0 ] + b[ 2 ] && b[ 0 ] < a[ 0 ] + a[ 2 ] && a[ 1 ] < b[ 1 ] + b[ 3 ] && b[ 1 ] < a[ 1 ] + a[ 3 ],
          "notas " + i + " y " + j + " no se pisan" );
      }
      int[] a = rs.get( i );
      for ( StepMeta s : tm.getSteps() ) {
        Point p = s.getLocation();
        falso( p.x < a[ 0 ] + a[ 2 ] && a[ 0 ] < p.x + 32 && p.y < a[ 1 ] + a[ 3 ] && a[ 1 ] < p.y + 32, "la nota " + i
          + " no tapa a " + s.getName() );
      }
    }
  }

  private static int contar( PlanoDeriva.Resultado r, String codigo ) {
    int n = 0;
    for ( PlanoDeriva.Hallazgo h : r.hallazgos ) {
      n += codigo.equals( h.codigo ) ? 1 : 0;
    }
    return n;
  }

  private static int avisos( PlanoDeriva.Resultado r ) {
    int n = 0;
    for ( PlanoDeriva.Hallazgo h : r.hallazgos ) {
      n += PlanoDeriva.AVISO.equals( h.severidad ) ? 1 : 0;
    }
    return n;
  }

  /**
   * Un Ctrl+Z como Spoon (Spoon.undoAction + SpoonTransformationDelegate.undoTransformationAction): deshace la última
   * acción y sigue con la anterior mientras la deshecha tenga getNextAlso(); el par fijo DELETE_STEP + DELETE_HOP va
   * junto.
   */
  static void deshacerUno( TransMeta tm ) {
    TransAction ta = tm.previousUndo();
    noNulo( ta, "hay algo que deshacer" );
    Set<Integer> vistas = new HashSet<Integer>();
    while ( ta != null ) {
      vistas.add( System.identityHashCode( ta ) );
      deshacer( tm, ta );
      boolean seguir = ta.getNextAlso();
      if ( ta.getType() == TransAction.TYPE_ACTION_DELETE_STEP ) {
        TransAction prev = tm.viewPreviousUndo();
        if ( prev != null && prev.getType() == TransAction.TYPE_ACTION_DELETE_HOP ) {
          deshacer( tm, tm.previousUndo() );
          seguir = prev.getNextAlso();
        }
      }
      if ( !seguir ) {
        break;
      }
      ta = tm.previousUndo();
    }
    TransOps.resolverReferencias( tm );
  }

  private static void deshacer( TransMeta tm, TransAction ta ) {
    int[] idx = ta.getCurrentIndex();
    Object[] cur = ta.getCurrent();
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
      case TransAction.TYPE_ACTION_NEW_NOTE:
        for ( int i = idx.length - 1; i >= 0; i-- ) {
          tm.removeNote( idx[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_DELETE_STEP:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.addStep( idx[ i ], (StepMeta) cur[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_DELETE_HOP:
        for ( int i = 0; i < idx.length; i++ ) {
          TransHopMeta h = (TransHopMeta) cur[ i ];
          StepMeta a = tm.findStep( h.getFromStep().getName() );
          StepMeta b = tm.findStep( h.getToStep().getName() );
          TransHopMeta n = new TransHopMeta( a, b, h.isEnabled() );
          n.setErrorHop( h.isErrorHop() );
          tm.addTransHop( idx[ i ], n );
        }
        break;
      case TransAction.TYPE_ACTION_DELETE_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.addNote( idx[ i ], (NotePadMeta) cur[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_CHANGE_STEP:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.getStep( idx[ i ] ).replaceMeta( (StepMeta) ( (StepMeta) ta.getPrevious()[ i ] ).clone() );
        }
        break;
      case TransAction.TYPE_ACTION_POSITION_STEP:
        for ( int i = 0; i < idx.length; i++ ) {
          Point p = ta.getPreviousLocation()[ i ];
          tm.getStep( idx[ i ] ).setLocation( p.x, p.y );
        }
        break;
      case TransAction.TYPE_ACTION_POSITION_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          Point p = ta.getPreviousLocation()[ i ];
          tm.getNote( idx[ i ] ).setLocation( p.x, p.y );
        }
        break;
      default:
        Verificador.falla( "acción de undo no simulada: " + ta.getType() );
    }
  }
}
