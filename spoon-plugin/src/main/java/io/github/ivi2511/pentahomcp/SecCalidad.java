package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.lint.Entorno;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.NoEvaluado;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Penalidad;
import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;
import io.github.ivi2511.pentahomcp.lint.Puntaje;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Sev;
import io.github.ivi2511.pentahomcp.lint.Sondas;
import io.github.ivi2511.pentahomcp.lint.Textos;
import io.github.ivi2511.pentahomcp.lint.TiempoAgotado;

/**
 * Seccion quality, «Calidad tecnica» (docs/linter-diseno.md §8, WP6): el motor del linter (lint/) sobre la copia de la
 * captura, lo mismo que lint_etl, con los textos en el idioma del informe. Los campos los pide al contexto del informe
 * (memo y presupuesto compartidos con linaje y diccionario, y sin tocar bases de archivo que no existen); con
 * {@code Pedido.calidadProfunda} corre ademas el modo profundo (LintSondas: previews de solo lectura).
 * <p>
 * Bloques: indicadores (puntaje y banda, errores, advertencias, sugerencias, silenciados), el resultado del modo
 * profundo, un aviso por error, la tabla de hallazgos (Severidad · Regla · Donde · Que pasa · Como se arregla), de
 * donde sale cada punto que falta (con la formula: es calidad tecnica, no una nota) y lo que no se pudo revisar. En
 * {@code datos}, el resultado de lint_etl (LintJson). Si validation va en el mismo informe, la tabla omite lo que la
 * validacion ya muestra ({@link RunMotor#REGLAS_DE_VALIDATE}, warning o error) y lo dice; el puntaje lo cuenta igual.
 * Costo: calculo de campos (puede conectarse a la base), asi que queda fuera de la vista previa liviana.
 *
 * @dueño calidad
 */
final class SecCalidad implements Recolector {

  /** Area de Rotulos de la seccion: textos/calidad_&lt;idioma&gt;.properties. */
  static final String AREA = "calidad";
  /** Presupuesto del modo profundo (el default de deep_timeout_s de lint_etl). */
  static final long MS_PROFUNDO = 60000;
  /** Lo que se reserva del plazo para el estatico y para cerrar la ultima preview del modo profundo. */
  static final long MS_RESERVA = LintTools.MS_CIERRE_PROFUNDO + 5000;
  /** Filas de las tablas de hallazgos y de lo no evaluado; el resto queda en datos (JSON). */
  static final int MAX_FILAS = 150;
  /** Avisos de error arriba de la tabla; el resto se cuenta en uno mas. */
  static final int MAX_AVISOS = 5;

  @Override
  public String id() {
    return SeccionId.CALIDAD;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    return null; // transformaciones y jobs
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    return armar( ctx, false );
  }

  /**
   * Arma la seccion. {@code conValidacion}: la seccion validation esta en el mismo informe (y salio), asi que la tabla
   * no repite lo que ella ya muestra.
   */
  Seccion armar( Contexto ctx, boolean conValidacion ) throws Exception {
    Rotulos r = ctx.rotulos( AREA );
    final Captura c = ctx.captura();
    final AbstractMeta copia = c.copia;
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), ctx.idioma() ) );
    long restante = ctx.msRestantes();
    long tope = Math.max( 1000L, Math.min( InformeServicio.TOPE_SECCION_MS, restante ) );

    final Opciones op = new Opciones();
    op.lang = ctx.idioma();
    op.sinGuardar = c.sinGuardar;
    op.robustez = true; // la familia robustness va en la seccion, con su sub-puntaje (en un job no corre ninguna)
    final EntornoInforme env = new EntornoInforme( ctx, copia, tope );
    op.entorno = env;
    // el perfil del proyecto o del curso (.pentaho-live/lint.yaml), como lint_etl (E5)
    java.io.File carpeta = PerfilEquipo.carpetaDe( copia.getFilename() );
    final PerfilEquipo.Busqueda perfil = carpeta == null ? null : PerfilEquipo.buscar( carpeta );
    op.perfil = perfil == null ? null : perfil.perfil;
    boolean profundo = ctx.pedido().calidadProfunda;
    if ( profundo ) {
      op.deep = true;
      op.sondas = new LintSondas();
      op.deepBudgetMs = Math.max( 1000L, Math.min( MS_PROFUNDO, tope - MS_RESERVA ) );
      // "mostrar_valores": false (como el perfil): el modo profundo no cita los valores que lee
      op.ocultarValores = ctx.tema() != null && ctx.tema().perfil != null && !ctx.tema().perfil.mostrarValores;
    }
    Resultado res;
    try {
      res = RunMotor.enSegundoPlano( new Callable<Resultado>() {
        @Override
        public Resultado call() {
          Resultado x = LintMotor.analizar( copia, op );
          LintPropuestas.completar( x, copia, op.lang ); // nombres propuestos para los nombres por defecto
          return x;
        }
      }, tope, new Runnable() {
        @Override
        public void run() {
          env.cancelar();
        }
      }, "The quality check" );
    } catch ( ToolError e ) {
      s.estado = Seccion.ERROR;
      s.motivo = r.f( "motivo.timeout", tope / 1000 );
      return s;
    }

    Textos tx = Textos.de( op.lang );
    // los contadores de la calidad tecnica sin la familia robustness, que tiene su propio sub-puntaje
    Resultado rb = res.robustez;
    int errores = cuenta( res, Sev.ERROR ) - cuenta( rb, Sev.ERROR );
    int advertencias = cuenta( res, Sev.WARNING ) - cuenta( rb, Sev.WARNING );
    int sugerencias = cuenta( res, Sev.INFO ) - cuenta( rb, Sev.INFO );

    // lo que la validacion ya muestra: sus reglas, con warning o error (las info no las da validate)
    List<Hallazgo> enTabla = new ArrayList<Hallazgo>();
    List<Hallazgo> yaEnValidacion = new ArrayList<Hallazgo>();
    List<Hallazgo> deRobustez = new ArrayList<Hallazgo>();
    for ( Hallazgo h : res.findings ) {
      if ( esRobustez( h ) ) {
        deRobustez.add( h );
      } else if ( conValidacion && enValidacion( h ) ) {
        yaEnValidacion.add( h );
      } else {
        enTabla.add( h );
      }
    }

    s.con( indicadores( r, res, errores, advertencias, sugerencias ) );
    LintTools.anotarPerfil( res, perfil );
    Parrafo pp = parrafoPerfil( r, res );
    if ( pp != null ) {
      s.con( pp );
    }
    if ( profundo ) {
      s.con( parrafoProfundo( r, res ) );
    }
    avisos( s, r, tx, enTabla );
    tablaHallazgos( s, r, tx, enTabla, yaEnValidacion, profundo, !deRobustez.isEmpty() );
    tablaPuntos( s, r, tx, res );
    tablaRobustez( s, r, tx, rb, deRobustez );
    tablaNoEvaluado( s, r, tx, res );

    s.resumen = resumen( r, tx, res, errores, advertencias, sugerencias );
    if ( cortado( res ) || ctx.cancelado() ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.s( "motivo.parcial" );
    }

    ObjectNode datos = LintJson.resultado( res );
    datos.put( "section", id() );
    datos.put( "deep_requested", profundo );
    if ( !yaEnValidacion.isEmpty() ) {
      ArrayNode a = datos.putArray( "keys_shown_in_validation" );
      for ( Hallazgo h : yaEnValidacion ) {
        a.add( h.key );
      }
    }
    s.datos = datos;
    return s;
  }

  // ---------------------------------------------------------------- bloques

  private static Indicadores indicadores( Rotulos r, Resultado res, int errores, int advertencias, int sugerencias ) {
    Indicadores ind = new Indicadores();
    ind.id = "quality-indicadores";
    ind.con( new Indicador( r.s( "ind.puntaje" ), r.f( "ind.puntaje.valor", res.score ), r.f( "ind.puntaje.detalle",
      res.grade ), nivelBanda( res.grade ) ) );
    ind.con( new Indicador( r.s( "ind.errores" ), entero( r, errores ), null, errores > 0 ? Indicador.ERROR
      : Indicador.OK ) );
    ind.con( new Indicador( r.s( "ind.advertencias" ), entero( r, advertencias ), null, advertencias > 0
      ? Indicador.WARN : Indicador.OK ) );
    ind.con( new Indicador( r.s( "ind.sugerencias" ), entero( r, sugerencias ) ) );
    if ( res.robustez != null ) {
      ind.con( new Indicador( r.s( "ind.robustez" ), r.f( "ind.puntaje.valor", res.robustez.score ), r.f(
        "ind.puntaje.detalle", res.robustez.grade ), nivelBanda( res.robustez.grade ) ) );
    }
    if ( !res.suppressed.isEmpty() ) {
      ind.con( new Indicador( r.s( "ind.silenciados" ), entero( r, res.suppressed.size() ), r.s(
        "ind.silenciados.detalle" ), null ) );
    }
    return ind;
  }

  /** A en verde, D en rojo; C en amarillo (con un error el puntaje queda en C como mucho); B neutro. */
  static String nivelBanda( String banda ) {
    if ( "A".equals( banda ) ) {
      return Indicador.OK;
    }
    if ( "C".equals( banda ) ) {
      return Indicador.WARN;
    }
    if ( "D".equals( banda ) ) {
      return Indicador.ERROR;
    }
    return null;
  }

  /** «Perfil del proyecto: Curso (.pentaho-live/lint.yaml) · mínimo 75: lo cumple», o null sin perfil. */
  static Parrafo parrafoPerfil( Rotulos r, Resultado res ) {
    String texto;
    if ( res.perfil != null ) {
      String archivo = res.perfil.archivo == null ? PerfilEquipo.CARPETA : PerfilEquipo.CARPETA + "/"
        + res.perfil.archivo.getName();
      texto = r.f( "perfil.con", res.perfil.nombre, archivo );
      if ( res.minScore != null ) {
        texto += " " + r.f( Boolean.TRUE.equals( res.pasaMinimo ) ? "perfil.minimo.ok" : "perfil.minimo.ko",
          res.minScore );
      }
    } else if ( res.perfilError != null ) {
      texto = r.f( "perfil.error", res.perfilError );
    } else {
      return null;
    }
    Parrafo p = new Parrafo( texto );
    p.id = "quality-perfil";
    return p;
  }

  private static Parrafo parrafoProfundo( Rotulos r, Resultado res ) {
    String texto;
    if ( res.deep.ran && res.deep.reason == null ) {
      texto = r.f( "profundo.hecho", res.deep.probes, res.deep.ms / 1000.0, res.deep.raised, res.deep.lowered,
        res.deep.unchanged, res.deep.notEvaluated );
    } else {
      texto = r.f( "profundo.no", Txt.vacio( res.deep.reason ) != null ? res.deep.reason : r.s( "profundo.sin_motivo" ) );
    }
    Parrafo p = new Parrafo( texto );
    p.id = "quality-profundo";
    return p;
  }

  /** Un aviso por cada error (diseno §8), como mucho MAX_AVISOS; el resto, contado en uno mas. */
  private static void avisos( Seccion s, Rotulos r, Textos tx, List<Hallazgo> enTabla ) {
    int n = 0;
    int resto = 0;
    for ( Hallazgo h : enTabla ) {
      if ( h.severity != Sev.ERROR ) {
        continue;
      }
      if ( n >= MAX_AVISOS ) {
        resto++;
        continue;
      }
      Aviso a = new Aviso( Aviso.ERROR, r.f( "aviso.error", tx.t( h.rule + ".title" ), h.message ) );
      a.id = "quality-error-" + ( ++n );
      s.con( a );
    }
    if ( resto > 0 ) {
      Aviso a = new Aviso( Aviso.ERROR, Txt.n( r, "aviso.mas", resto ) );
      a.id = "quality-error-mas";
      s.con( a );
    }
  }

  private static void tablaHallazgos( Seccion s, Rotulos r, Textos tx, List<Hallazgo> enTabla,
                                      List<Hallazgo> yaEnValidacion, boolean profundo, boolean hayRobustez ) {
    List<String> notas = new ArrayList<String>();
    if ( !yaEnValidacion.isEmpty() ) {
      notas.add( Txt.n( r, "nota.validacion", yaEnValidacion.size() ) );
    }
    if ( !profundo && tieneSonda( enTabla ) ) {
      notas.add( r.s( "nota.sin_profundo" ) );
    }
    if ( enTabla.isEmpty() ) {
      Parrafo p = new Parrafo( !yaEnValidacion.isEmpty() ? r.s( "todos_en_validacion" ) : hayRobustez ? r.s(
        "sin_hallazgos.calidad" ) : r.s( "sin_hallazgos" ) );
      p.id = "quality-sin-hallazgos";
      s.con( p );
      return;
    }
    Tabla t = new Tabla( r.s( "col.severidad" ), r.s( "col.regla" ), r.s( "col.donde" ), r.s( "col.que" ), r.s(
      "col.arreglo" ) );
    t.id = "quality-hallazgos";
    t.titulo = r.s( "tabla.hallazgos" );
    for ( int i = 0; i < enTabla.size() && i < MAX_FILAS; i++ ) {
      Hallazgo h = enTabla.get( i );
      t.fila( severidad( r, h.severity ), tx.t( h.rule + ".title" ), donde( r, h ), que( r, h ), arreglo( r, h ) );
    }
    if ( enTabla.size() > MAX_FILAS ) {
      t.pie = Txt.n( r, "pie.mas", enTabla.size() - MAX_FILAS );
    }
    if ( !notas.isEmpty() ) {
      t.nota = String.join( "\n", notas );
    }
    s.con( t );
  }

  /** De donde sale cada punto que falta, con la formula al pie. */
  private static void tablaPuntos( Seccion s, Rotulos r, Textos tx, Resultado res ) {
    if ( res.penalties.isEmpty() ) {
      return;
    }
    Tabla t = new Tabla( r.s( "col.regla" ), r.s( "col.severidad" ), r.s( "col.veces" ), r.s( "col.puntos" ) );
    t.id = "quality-puntos";
    t.titulo = r.s( "tabla.puntos" );
    t.alineacion = new ArrayList<String>( Arrays.asList( "l", "l", "r", "r" ) );
    for ( Penalidad p : res.penalties ) {
      t.fila( tx.t( p.rule + ".title" ), severidad( r, p.severity ), entero( r, p.hits ), r.f( "puntos", p.points ) );
    }
    List<String> notas = new ArrayList<String>();
    notas.add( r.f( "nota.formula", Sev.ERROR.peso(), Sev.WARNING.peso(), Sev.INFO.peso(), Sev.ERROR.tope(),
      Sev.WARNING.tope(), Sev.INFO.tope(), Puntaje.TOPE_INFO, Puntaje.COMPUERTA_ERROR ) );
    if ( res.infoPointsRaw > res.infoPoints ) {
      notas.add( r.f( "nota.tope_info", res.infoPointsRaw, res.infoPoints ) );
    }
    if ( res.gateApplied ) {
      notas.add( r.f( "nota.compuerta", Puntaje.COMPUERTA_ERROR ) );
    }
    t.nota = String.join( "\n", notas );
    s.con( t );
  }

  /**
   * La familia robustness: sus hallazgos (las filas malas que pararian la carga o se perderian) en una tabla aparte,
   * con de donde salen los puntos que le faltan a su sub-puntaje. No restan de la calidad tecnica.
   */
  private static void tablaRobustez( Seccion s, Rotulos r, Textos tx, Resultado rb, List<Hallazgo> deRobustez ) {
    if ( rb == null || deRobustez.isEmpty() ) {
      return;
    }
    Tabla t = new Tabla( r.s( "col.severidad" ), r.s( "col.regla" ), r.s( "col.donde" ), r.s( "col.que" ), r.s(
      "col.arreglo" ) );
    t.id = "quality-robustez";
    t.titulo = r.s( "tabla.robustez" );
    for ( int i = 0; i < deRobustez.size() && i < MAX_FILAS; i++ ) {
      Hallazgo h = deRobustez.get( i );
      t.fila( severidad( r, h.severity ), tx.t( h.rule + ".title" ), donde( r, h ), que( r, h ), arreglo( r, h ) );
    }
    if ( deRobustez.size() > MAX_FILAS ) {
      t.pie = Txt.n( r, "pie.mas", deRobustez.size() - MAX_FILAS );
    }
    t.nota = r.s( "nota.robustez" );
    s.con( t );
    if ( rb.penalties.isEmpty() ) {
      return;
    }
    Tabla p = new Tabla( r.s( "col.regla" ), r.s( "col.severidad" ), r.s( "col.veces" ), r.s( "col.puntos" ) );
    p.id = "quality-puntos-robustez";
    p.titulo = r.s( "tabla.puntos_robustez" );
    p.alineacion = new ArrayList<String>( Arrays.asList( "l", "l", "r", "r" ) );
    for ( Penalidad x : rb.penalties ) {
      p.fila( tx.t( x.rule + ".title" ), severidad( r, x.severity ), entero( r, x.hits ), r.f( "puntos", x.points ) );
    }
    p.nota = r.s( "nota.formula_robustez" );
    s.con( p );
  }

  private static void tablaNoEvaluado( Seccion s, Rotulos r, Textos tx, Resultado res ) {
    if ( res.notEvaluated.isEmpty() ) {
      return;
    }
    Tabla t = new Tabla( r.s( "col.regla" ), r.s( "col.donde" ), r.s( "col.motivo" ) );
    t.id = "quality-no-evaluado";
    t.titulo = r.s( "tabla.no_evaluado" );
    for ( int i = 0; i < res.notEvaluated.size() && i < MAX_FILAS; i++ ) {
      NoEvaluado n = res.notEvaluated.get( i );
      String regla = n.rule == null ? "" : tx.t( n.rule + ".title" );
      String donde = n.step != null ? n.step : n.entry != null ? n.entry : "";
      t.fila( regla, donde, motivo( r, res, n ) );
    }
    if ( res.notEvaluated.size() > MAX_FILAS ) {
      t.pie = Txt.n( r, "pie.mas_no_evaluado", res.notEvaluated.size() - MAX_FILAS );
    }
    t.nota = r.s( "nota.no_evaluado" );
    s.con( t );
  }

  /** El motivo del linter, salvo el del presupuesto del modo profundo, que le habla a la IA (deep_timeout_s). */
  private static String motivo( Rotulos r, Resultado res, NoEvaluado n ) {
    if ( "deep_budget".equals( n.code ) ) {
      return r.f( "ne.deep_budget", Math.max( 1L, res.deep.budgetMs / 1000 ) );
    }
    return n.reason == null ? "" : n.reason;
  }

  private static String resumen( Rotulos r, Textos tx, Resultado res, int errores, int advertencias,
                                 int sugerencias ) {
    String robustez = resumenRobustez( r, res.robustez );
    if ( errores + advertencias + sugerencias == 0 ) {
      if ( robustez == null ) {
        return r.f( "resumen.limpio", res.score, res.grade );
      }
      return r.f( "resumen.limpio.calidad", res.score, res.grade ) + " " + robustez;
    }
    List<String> partes = new ArrayList<String>();
    if ( errores > 0 ) {
      partes.add( Txt.n( r, "n.error", errores ) );
    }
    if ( advertencias > 0 ) {
      partes.add( Txt.n( r, "n.adv", advertencias ) );
    }
    if ( sugerencias > 0 ) {
      partes.add( Txt.n( r, "n.sug", sugerencias ) );
    }
    StringBuilder sb = new StringBuilder( r.f( "resumen", res.score, res.grade, Txt.lista( r, partes ) ) );
    if ( errores == 0 ) {
      sb.append( ' ' ).append( r.s( "resumen.sin_errores" ) );
    }
    if ( !res.penalties.isEmpty() ) {
      Penalidad p = res.penalties.get( 0 );
      sb.append( ' ' ).append( r.f( "resumen.peor", tx.t( p.rule + ".title" ), Txt.n( r, "n.punto", p.points ) ) );
    }
    if ( robustez != null ) {
      sb.append( ' ' ).append( robustez );
    }
    return sb.toString();
  }

  /** El sub-puntaje de robustez en una frase; null si la familia no corrio (un job). */
  private static String resumenRobustez( Rotulos r, Resultado rb ) {
    if ( rb == null ) {
      return null;
    }
    List<String> partes = new ArrayList<String>();
    int e = cuenta( rb, Sev.ERROR );
    int w = cuenta( rb, Sev.WARNING );
    int i = cuenta( rb, Sev.INFO );
    if ( e > 0 ) {
      partes.add( Txt.n( r, "n.error", e ) );
    }
    if ( w > 0 ) {
      partes.add( Txt.n( r, "n.adv", w ) );
    }
    if ( i > 0 ) {
      partes.add( Txt.n( r, "n.sug", i ) );
    }
    if ( partes.isEmpty() ) {
      return r.f( "resumen.robustez.limpio", rb.score, rb.grade );
    }
    return r.f( "resumen.robustez", rb.score, rb.grade, Txt.lista( r, partes ) );
  }

  // ---------------------------------------------------------------- celdas

  static String severidad( Rotulos r, Sev s ) {
    return r.s( "sev." + ( s == null ? Sev.INFO : s ).codigo() );
  }

  /** El step o la entrada; un hop, una conexion, un slave server o el documento con su rotulo; y el campo, si hay. */
  static String donde( Rotulos r, Hallazgo h ) {
    String d;
    if ( Hallazgo.HOP.equals( h.scope ) && h.object != null ) {
      d = r.f( "donde.hop", h.object.replace( " -> ", " → " ) );
    } else if ( Hallazgo.CONNECTION.equals( h.scope ) ) {
      d = r.f( "donde.conexion", h.object == null ? "" : h.object );
    } else if ( Hallazgo.SLAVE.equals( h.scope ) ) {
      d = r.f( "donde.slave", h.object == null ? "" : h.object );
    } else if ( Hallazgo.DOC.equals( h.scope ) ) {
      d = h.object == null ? r.s( "donde.doc" ) : r.f( "donde.doc.objeto", h.object );
    } else if ( h.step != null ) {
      d = h.step;
    } else if ( h.entry != null ) {
      d = h.entry;
    } else {
      d = h.object == null ? r.s( "donde.doc" ) : h.object;
    }
    if ( Txt.vacio( h.field ) != null ) {
      d += " · " + h.field;
    }
    return d;
  }

  /** El mensaje, y si depende de la maquina (rutas, archivos, el modo profundo) lo dice. */
  static String que( Rotulos r, Hallazgo h ) {
    String m = h.message == null ? "" : h.message.trim();
    return h.env ? m + " " + r.s( "env" ) : m;
  }

  /** Como se arregla, con la propuesta concreta si el linter la tiene (un nombre, una ruta, el simbolo decimal). */
  static String arreglo( Rotulos r, Hallazgo h ) {
    String a = h.howToFix == null ? "" : h.howToFix.trim();
    if ( h.fix != null && Txt.vacio( h.fix.preview ) != null ) {
      a += ( a.isEmpty() ? "" : " " ) + r.f( "propuesta", h.fix.preview.trim() );
    }
    return a;
  }

  // ---------------------------------------------------------------- ayudas

  /** Las reglas que tambien da validate (y, con warning o error, la seccion validation ya las muestra). */
  static boolean enValidacion( Hallazgo h ) {
    return RunMotor.REGLAS_DE_VALIDATE.contains( h.rule ) && h.severity != null && h.severity.almenos( Sev.WARNING );
  }

  private static boolean tieneSonda( List<Hallazgo> l ) {
    for ( Hallazgo h : l ) {
      if ( Sondas.REGLAS.contains( h.rule ) ) {
        return true;
      }
    }
    return false;
  }

  /** Algunas reglas no corrieron porque el analisis se cancelo o se quedo sin tiempo: el puntaje cuenta menos. */
  private static boolean cortado( Resultado res ) {
    for ( NoEvaluado n : res.notEvaluated ) {
      if ( "cancelled".equals( n.code ) ) {
        return true;
      }
    }
    return false;
  }

  /** De la familia robustness (va en su propia tabla y en su sub-puntaje, no en la calidad tecnica). */
  static boolean esRobustez( Hallazgo h ) {
    return Regla.ROBUSTNESS.equals( h.category );
  }

  private static int cuenta( Resultado res, Sev s ) {
    if ( res == null ) {
      return 0;
    }
    Integer n = res.counts.get( s.codigo() );
    return n == null ? 0 : n;
  }

  private static String entero( Rotulos r, long n ) {
    return String.format( r.locale(), "%,d", n );
  }

  // ---------------------------------------------------------------- entorno

  /**
   * El Entorno del linter dentro de un informe: los campos por el Contexto (memo y presupuesto compartidos con las
   * demas secciones; nunca crea una base de archivo que falta), la lista blanca y las salidas normales de LintEntorno,
   * y la cancelacion del pedido. Cuando el contexto corta el calculo de campos por tiempo, lo pasa como TiempoAgotado:
   * asi el linter deja de pedirlos.
   */
  static final class EntornoInforme implements Entorno {

    private final Contexto ctx;
    private final LintEntorno base;
    private final long fin;
    private final String vencido;
    private volatile boolean cancelado;

    EntornoInforme( Contexto ctx, AbstractMeta copia, long presupuestoMs ) {
      this.ctx = ctx;
      this.base = new LintEntorno( copia, presupuestoMs );
      this.fin = System.currentTimeMillis() + presupuestoMs;
      this.vencido = Rotulos.de( InformeServicio.AREA, ctx.idioma() ).s( "dic.timeout" );
    }

    void cancelar() {
      cancelado = true;
      base.cancelar();
    }

    @Override
    public RowMetaInterface salida( StepMeta s ) throws KettleStepException {
      try {
        return ctx.salida( s );
      } catch ( KettleStepException e ) {
        throw comoTiempo( e );
      }
    }

    @Override
    public RowMetaInterface entrada( StepMeta s ) throws KettleStepException {
      try {
        return ctx.entrada( s );
      } catch ( KettleStepException e ) {
        throw comoTiempo( e );
      }
    }

    private KettleStepException comoTiempo( KettleStepException e ) {
      String m = e.getMessage();
      if ( !( e instanceof TiempoAgotado ) && m != null && m.contains( vencido ) ) {
        return new TiempoAgotado( vencido );
      }
      return e;
    }

    @Override
    public int salidasNormales( TransMeta tm, StepMeta s ) {
      return base.salidasNormales( tm, s );
    }

    @Override
    public boolean soloLectura( StepMeta s ) {
      return base.soloLectura( s );
    }

    @Override
    public String[] baseFaltante( StepMeta s ) {
      return base.baseFaltante( s );
    }

    @Override
    public boolean cancelado() {
      return cancelado || ctx.cancelado() || Thread.currentThread().isInterrupted();
    }

    @Override
    public long msRestantes() {
      return Math.min( fin - System.currentTimeMillis(), ctx.msRestantes() );
    }
  }
}
