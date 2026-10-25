package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Criterio;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.PasoBloqueado;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.render.RenderHtml;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Puente entre el corrector y Informes (docs/corrector-diseno.md §6, «Render»; paquete C9). Pasa un
 * {@link ResultadoCorreccion} a un {@link Informe} (Informe → Secciones → Bloques) que dibujan los renderizadores de
 * Informes 0.5.0 con {@link InformeServicio#exportar}: HTML, HTML liviano (el {@code Browser} IE11 de Spoon), PDF,
 * Markdown y PPTX, con el {@link Tema} de {@link Ajustes#cargar()}. No hay renderizador propio.
 * <p>
 * Secciones de una entrega, en este orden (sus ids no son de {@link SeccionId}: los renderizadores los dejan despues
 * de la portada en el orden en que se agregan):
 * <ol>
 * <li>{@code cover}: ejercicio, alumno, archivo, nota, estado y la identidad del tema (docente, institucion,
 * curso).</li>
 * <li>{@link #SEC_NOTA}: tarjetas (nota, estado, criterios aprobados, casos sin diferencias), el modo confianza y por
 * que no se corrigio, si es el caso.</li>
 * <li>{@link #SEC_CRITERIOS}: la rubrica puntuada; {@link #SEC_BLOQUEADOS}: los steps que la politica no dejo
 * correr; {@link #SEC_PISTAS}: las pistas del JSON.</li>
 * <li>Una por caso ({@link #SEC_CASO}{@code <id>}): tarjetas de corrida, columnas, filas y valores; detalles
 * (columnas que faltan o sobran, renombres, avisos); tablas de valores distintos, filas que faltan o sobran, claves
 * repetidas, diferencias por columna y filas por step; la cola del log si el caso fallo.</li>
 * <li>{@link #SEC_NOTAS}: las notas del corrector (avisos de la entrega, captura, tiempos, version).</li>
 * </ol>
 * <b>Dos lectores</b> (§6): con {@code alumno = true} se parte de {@link #paraAlumno}: de los casos ocultos no van
 * ejemplos, valores, redirecciones, metricas, salida capturada, detalle ni log (solo conteos, puntajes y avisos por
 * codigo), y de ningun caso ni de la entrega van el {@code detail} ni el log, que pueden traer rutas de la PC del
 * docente; tampoco la captura, los tiempos ni la version. La version del docente lo lleva todo, los ocultos
 * incluidos, y el final del log de los casos que fallaron.
 * <p>
 * {@link #curso} arma el informe de un lote (indicadores, la distribucion de notas, los criterios mas fallados en
 * {@link Grafico#BARRAS_H} y la tabla de entregas).
 * <p>
 * Todo texto que viene de la entrega (nombres de steps y archivos, valores, logs) es dato del alumno: va siempre en
 * campos que cada renderizador escapa. Los avisos ({@code warnings}) se traducen por {@code code}.
 *
 * @dueño C9
 */
public final class CorreccionAInforme {

  /** Area de Rotulos (la del corrector: corrector_{en,es,pt}.properties). */
  static final String AREA = CorrectorNota.AREA;

  /** Ids de seccion de una entrega. */
  public static final String SEC_NOTA = "grade";
  public static final String SEC_CRITERIOS = "criteria";
  public static final String SEC_BLOQUEADOS = "blocked_steps";
  public static final String SEC_PISTAS = "hints";
  /** Prefijo de la seccion de cada caso: {@code case_<id>}. */
  public static final String SEC_CASO = "case_";
  public static final String SEC_NOTAS = "grader_notes";

  /** Ids de seccion del informe de curso. */
  public static final String SEC_CURSO = "course_summary";
  public static final String SEC_DISTRIBUCION = "grade_distribution";
  public static final String SEC_CHECKS = "checks";
  public static final String SEC_ENTREGAS = "submissions";

  /** Lineas y caracteres de la cola del log que van al informe del docente. */
  static final int MAX_LINEAS_LOG = 30;
  static final int MAX_LOG = 4000;
  /** Filas de la tabla de metricas de un caso. */
  static final int MAX_METRICAS = 40;
  /** Barras de la distribucion de notas (escala / 10 cada una). */
  static final int TRAMOS = 10;

  static final String BIEN = "✔";
  static final String MAL = "✘";
  private static final String SIN_NOTA = "–";

  private CorreccionAInforme() {
  }

  // ================================================================ una entrega

  /**
   * El informe de una entrega. {@code alumno}: la devolucion del alumno (sin lo de los casos ocultos); si no, el
   * registro completo del docente. El idioma es el del resultado (sus etiquetas y pistas ya vienen traducidas);
   * {@code tema} (puede ser null) da el logo y la identidad de la portada. {@code r} no cambia.
   */
  public static Informe informe( ResultadoCorreccion r, boolean alumno, Tema tema ) {
    if ( r == null ) {
      throw new IllegalArgumentException( "No grade result" );
    }
    Armado a = new Armado( alumno ? paraAlumno( r ) : CorrectorJson.copia( r, ResultadoCorreccion.class ), alumno,
      tema );
    ResultadoCorreccion x = a.r;
    String titulo = Txt.vacio( x.tituloPack ) != null ? x.tituloPack.trim() : Txt.vacio( x.pack ) != null
      ? x.pack.trim() : a.t.s( "md.titulo" );
    String quien = Txt.vacio( x.alumno ) != null ? x.alumno.trim() : Txt.vacio( x.archivo ) != null ? x.archivo
      : "?";
    Informe inf = cabecera( titulo, a.t.f( alumno ? "inf.subtitulo.alumno" : "inf.subtitulo", quien ), a.idioma );
    inf.nombreDoc = Txt.vacio( x.archivo );
    inf.tipoDoc = x.archivo == null ? null : x.archivo.toLowerCase( Locale.ROOT ).endsWith( ".kjb" ) ? Informe.JOB
      : Informe.TRANSFORMACION;

    inf.secciones.add( portada( a ) );
    inf.secciones.add( nota( a ) );
    agregar( inf, criterios( a ) );
    agregar( inf, bloqueados( a ) );
    agregar( inf, pistas( a ) );
    for ( ResultadoCaso rc : x.casos ) {
      if ( rc != null ) {
        inf.secciones.add( caso( a, rc ) );
      }
    }
    agregar( inf, notas( a ) );
    return inf;
  }

  /**
   * Lo que del resultado le llega al alumno (informe y JSON): {@link ResultadoCorreccion#paraAlumno()} y ademas sin
   * el {@code detail} de la entrega ni el {@code run.detail}/{@code run.log} de ningun caso (pueden traer rutas de la
   * PC del docente) y, de los ocultos, sin redirecciones, metricas ni salida capturada. {@code r} no cambia.
   */
  public static ResultadoCorreccion paraAlumno( ResultadoCorreccion r ) {
    ResultadoCorreccion x = r.paraAlumno().completar();
    x.detalle = null;
    x.claveDetalle = null;
    x.argsDetalle = null;
    for ( ResultadoCaso rc : x.casos ) {
      if ( rc == null ) {
        continue;
      }
      rc.corrida.detalle = null;
      rc.corrida.claveDetalle = null;
      rc.corrida.argsDetalle = null;
      rc.corrida.log = null;
      if ( rc.oculto ) {
        rc.redirecciones.clear();
        rc.corrida.metricas = null;
        rc.corrida.capturadoDe = null;
      }
    }
    return x;
  }

  /** Lo que comparten las secciones de una entrega. */
  private static final class Armado {
    final ResultadoCorreccion r;
    final boolean alumno;
    final Tema tema;
    final String idioma;
    /** Textos del corrector y de las secciones estructurales (portada). */
    final Rotulos t;
    final Rotulos e;
    final Locale loc;

    Armado( ResultadoCorreccion r, boolean alumno, Tema tema ) {
      this.r = r.completar();
      this.alumno = alumno;
      this.tema = tema;
      this.idioma = Rotulos.normalizar( r.idioma != null ? r.idioma : tema != null ? tema.idioma : null );
      this.t = Rotulos.de( AREA, idioma );
      this.e = Rotulos.de( InformeServicio.AREA, idioma );
      this.loc = Rotulos.locale( idioma );
    }

    /** De este caso solo se muestran conteos: el alumno mirando un caso oculto. */
    boolean reservado( ResultadoCaso rc ) {
      return alumno && rc.oculto;
    }
  }

  private static Seccion portada( Armado a ) {
    ResultadoCorreccion r = a.r;
    Seccion s = new Seccion( SeccionId.PORTADA, SeccionId.titulo( SeccionId.PORTADA, a.idioma ) );
    logo( s, a.tema, a.e );
    Lista l = new Lista();
    l.id = "cover-datos";
    poner( l, a.t, "inf.portada.alumno", r.alumno );
    poner( l, a.t, "inf.portada.archivo", r.archivo );
    if ( Txt.vacio( r.tituloPack ) != null ) {
      poner( l, a.t, "inf.portada.ejercicio", r.pack );
    }
    // la nota va en la lista y no en el resumen: la portada del PPTX no muestra el resumen
    l.items.add( a.t.f( "inf.portada.nota", notaSobre( r.nota, r.escala, a.loc ), texto( a.t, "estado.",
      r.estado ) ) );
    identidad( l, a.tema, a.t, a.e );
    poner( l, a.t, "inf.portada.corregida", fecha( r.corregidoEl ) );
    s.con( l );
    return s;
  }

  private static Seccion nota( Armado a ) {
    ResultadoCorreccion r = a.r;
    Rotulos t = a.t;
    Seccion s = new Seccion( SEC_NOTA, t.s( "inf.sec.nota" ) );
    Indicadores ind = new Indicadores();
    ind.id = "grade-cards";
    ind.con( new Indicador( t.s( "md.nota" ), notaSobre( r.nota, r.escala, a.loc ), r.nota == null ? null
      : porciento( r.nota / ( r.escala <= 0 ? 10 : r.escala ) ), nivelNota( r.nota, r.escala ) ) );
    ind.con( new Indicador( t.s( "md.estado" ), texto( t, "estado.", r.estado ), r.motivo == null ? null : texto( t,
      "motivo.", r.motivo ), nivelEstado( r.estado ) ) );
    int aprobados = 0;
    int criterios = 0;
    List<String> fallan = new ArrayList<String>();
    for ( Criterio c : r.criterios ) {
      if ( c == null ) {
        continue;
      }
      criterios++;
      if ( c.aprobado ) {
        aprobados++;
      } else {
        fallan.add( etiqueta( c ) );
      }
    }
    if ( criterios > 0 ) {
      ind.con( new Indicador( t.s( "inf.ind.criterios" ), t.f( "inf.de", aprobados, criterios ), null,
        aprobados == criterios ? Indicador.OK : Indicador.WARN ) );
    }
    int casos = 0;
    int casosBien = 0;
    for ( ResultadoCaso rc : r.casos ) {
      if ( rc != null ) {
        casos++;
        casosBien += sinDiferencias( rc ) ? 1 : 0;
      }
    }
    if ( casos > 0 ) {
      ind.con( new Indicador( t.s( "inf.ind.casos" ), t.f( "inf.de", casosBien, casos ), null,
        casosBien == casos ? Indicador.OK : casosBien > 0 ? Indicador.WARN : Indicador.ERROR ) );
    }
    s.con( ind );
    if ( criterios > 0 ) {
      s.resumen = fallan.isEmpty() ? t.s( "inf.resumen.todo" ) : t.f( "inf.resumen.fallan", unir( fallan, "; " ) );
      s.con( resumenEnDiapositiva( SEC_NOTA, s.resumen ) );
    }
    if ( r.modoConfianza ) {
      s.con( aviso( "grade-trust", Aviso.ERROR, t.s( "md.modo_confianza" ) ) );
    }
    if ( ResultadoCorreccion.EN_CURSO.equals( r.estado ) ) {
      s.con( aviso( "grade-partial", Aviso.INFO, t.s( "inf.en_curso" ) ) );
    } else if ( r.estado != null && !ResultadoCorreccion.CORREGIDA.equals( r.estado ) ) {
      String k = "inf.estado." + r.estado;
      String txt = t.tiene( k ) ? t.s( k ) : texto( t, "estado.", r.estado );
      if ( r.motivo != null ) {
        txt += " (" + texto( t, "motivo.", r.motivo ) + ")";
      }
      if ( !a.alumno && Txt.vacio( r.detalle ) != null ) {
        txt += "\n" + t.f( "inf.detalle", CorrectorTextos.detalle( t, r ).trim() );
      }
      s.con( aviso( "grade-status", nivelAviso( r.estado ), txt ) );
    } else if ( !a.alumno && Txt.vacio( r.detalle ) != null ) {
      s.con( parrafo( "grade-detail", t.f( "inf.detalle", CorrectorTextos.detalle( t, r ).trim() ) ) );
    }
    return s;
  }

  private static Seccion criterios( Armado a ) {
    ResultadoCorreccion r = a.r;
    if ( r.criterios.isEmpty() ) {
      return null;
    }
    Rotulos t = a.t;
    Seccion s = new Seccion( SEC_CRITERIOS, t.s( "md.criterios" ) );
    Tabla tb = new Tabla( t.s( "md.criterio" ), t.s( "md.puntos" ), t.s( "md.detalle" ) );
    tb.id = "criteria-table";
    tb.alineacion = new ArrayList<String>( Arrays.asList( "l", "r", "l" ) );
    double ganado = 0;
    double puntos = 0;
    for ( Criterio c : r.criterios ) {
      if ( c == null ) {
        continue;
      }
      ganado += c.ganado;
      puntos += c.puntos;
      tb.fila( ( c.aprobado ? BIEN : MAL ) + " " + etiqueta( c ), puntos( c.ganado, a.loc ) + " / " + puntos(
        c.puntos, a.loc ), c.detalle );
    }
    tb.pie = t.f( "inf.criterios.total", puntos( ganado, a.loc ), puntos( puntos, a.loc ) );
    s.con( tb );
    return s;
  }

  private static Seccion bloqueados( Armado a ) {
    if ( a.r.stepsBloqueados.isEmpty() ) {
      return null;
    }
    Rotulos t = a.t;
    Seccion s = new Seccion( SEC_BLOQUEADOS, t.s( "md.bloqueados" ) );
    Tabla tb = new Tabla( t.s( "md.col.step" ), t.s( "md.col.tipo" ), t.s( "md.col.nivel" ), t.s( "md.col.motivo" ) );
    tb.id = "blocked-table";
    for ( PasoBloqueado pb : a.r.stepsBloqueados ) {
      if ( pb != null ) {
        tb.fila( pb.step, pb.tipo, pb.nivel == null ? null : texto( t, "inf.nivel.", pb.nivel ), pb.motivo == null
          ? null : texto( t, "motivo.", pb.motivo ) );
      }
    }
    s.con( tb );
    return s;
  }

  private static Seccion pistas( Armado a ) {
    List<String> l = new ArrayList<String>();
    for ( String p : a.r.pistas ) {
      if ( Txt.vacio( p ) != null ) {
        l.add( p.trim() );
      }
    }
    if ( l.isEmpty() ) {
      return null;
    }
    Seccion s = new Seccion( SEC_PISTAS, a.t.s( "md.pistas" ) );
    Lista li = new Lista();
    li.id = "hints-list";
    li.items.addAll( l );
    s.con( li );
    return s;
  }

  private static Seccion caso( Armado a, ResultadoCaso rc ) {
    Rotulos t = a.t;
    boolean reservado = a.reservado( rc );
    String id = SEC_CASO + ( rc.id == null ? "" : rc.id );
    String titulo = t.f( "md.caso", rc.id == null ? "?" : rc.id ) + ( rc.oculto ? " (" + t.s( "md.oculto" ) + ")"
      : "" );
    Seccion s = new Seccion( id, titulo );
    ResultadoCaso.Corrida run = rc.corrida == null ? new ResultadoCaso.Corrida() : rc.corrida;
    Comparacion cmp = rc.comparacion;

    // ---- tarjetas
    Indicadores ind = new Indicadores();
    ind.id = id + "-cards";
    String estado = CorrectorNota.estadoCaso( t, rc );
    if ( run.estado != null && run.motivo != null ) {
      estado += " (" + texto( t, "motivo.", run.motivo ) + ")";
    }
    List<String> partes = new ArrayList<String>();
    if ( run.segundos != null ) {
      partes.add( t.f( "md.segundos", run.segundos ) );
    }
    if ( run.errores != null && run.errores > 0 ) {
      partes.add( t.f( "md.errores", run.errores ) );
    }
    ind.con( new Indicador( t.s( "md.corrida" ), estado, partes.isEmpty() ? null : unir( partes, " · " ),
      rc.corrioBien() ? Indicador.OK : ResultadoCaso.TERMINO_CON_ERRORES.equals( run.estado ) ? Indicador.WARN
        : run.estado == null ? null : Indicador.ERROR ) );
    if ( cmp != null ) {
      Comparacion.Columnas col = cmp.columnas == null ? new Comparacion.Columnas() : cmp.columnas;
      Comparacion.Filas f = cmp.filas == null ? new Comparacion.Filas() : cmp.filas;
      int esperadas = nn( col.esperadas ).size();
      int presentes = Math.max( 0, esperadas - nn( col.faltan ).size() );
      int sobran = nn( col.sobran ).size();
      ind.con( new Indicador( t.s( "inf.ind.columnas" ), t.f( "inf.de", presentes, esperadas ), sobran == 0 ? null
        : t.f( "inf.ind.columnas.sobran", sobran ), cmp.puntajeColumnas >= 1 - 1e-9 ? Indicador.OK
          : Indicador.WARN ) );
      ind.con( new Indicador( t.s( "inf.ind.filas" ), String.valueOf( f.reales ), t.f( "inf.ind.filas.esperadas",
        f.esperadas ), cmp.cantidadFilasOk ? Indicador.OK : Indicador.WARN ) );
      ind.con( new Indicador( t.s( "inf.ind.valores" ), porciento( cmp.puntajeValores ), t.f(
        "inf.ind.valores.iguales", f.iguales, f.esperadas ), cmp.puntajeValores >= 1 - 1e-9 ? Indicador.OK
          : cmp.puntajeValores > 0 ? Indicador.WARN : Indicador.ERROR ) );
      s.resumen = t.f( "md.filas", f.esperadas, f.reales, f.iguales, f.distintas, f.faltan, f.sobran,
        f.clavesRepetidas );
    }
    s.con( ind );
    if ( s.resumen != null ) {
      s.con( resumenEnDiapositiva( id, s.resumen ) );
    }

    // ---- detalles
    Lista det = new Lista();
    det.id = id + "-details";
    if ( cmp != null && cmp.columnas != null ) {
      lista( det, t.s( "md.faltan_columnas" ), cmp.columnas.faltan );
      lista( det, t.s( "md.sobran_columnas" ), cmp.columnas.sobran );
      List<String> ren = new ArrayList<String>();
      for ( Comparacion.Renombre rn : nn( cmp.columnas.renombres ) ) {
        if ( rn != null ) {
          ren.add( rn.esperada + " → " + rn.real );
        }
      }
      lista( det, t.s( "md.renombres" ), ren );
    }
    if ( !reservado ) {
      if ( Txt.vacio( run.stepSalida ) != null ) {
        det.items.add( t.f( "inf.step_salida", run.stepSalida ) );
      }
      if ( Txt.vacio( run.capturadoDe ) != null ) {
        det.items.add( t.f( "inf.capturado", run.capturadoDe ) );
      }
      lista( det, t.s( "md.redirecciones" ), rc.redirecciones );
    }
    if ( !a.alumno && Txt.vacio( run.detalle ) != null ) {
      det.items.add( t.f( "inf.detalle", CorrectorTextos.detalle( t, run ).trim() ) );
    }
    for ( ResultadoCorreccion.Aviso av : nn( rc.avisos ) ) {
      String x = textoAviso( av, t, !a.alumno );
      if ( x != null ) {
        det.items.add( x );
      }
    }
    if ( !det.items.isEmpty() ) {
      s.con( det );
    }
    if ( reservado ) {
      s.con( aviso( id + "-hidden", Aviso.INFO, t.s( "md.solo_conteos" ) ) );
    }

    // ---- ejemplos (el alumno no los ve en un oculto; paraAlumno ya los saco)
    Comparacion.Ejemplos ej = cmp == null ? null : cmp.ejemplos;
    if ( ej != null && !reservado ) {
      Comparacion.Filas f = cmp.filas == null ? new Comparacion.Filas() : cmp.filas;
      List<Comparacion.Ejemplo> dist = nn( ej.distintas );
      if ( !dist.isEmpty() ) {
        Tabla tb = new Tabla( t.s( "md.col.clave" ), t.s( "md.col.columna" ), t.s( "md.col.esperado" ), t.s(
          a.alumno ? "md.col.tuyo" : "inf.col.entregado" ) );
        tb.id = id + "-different";
        tb.titulo = t.s( "md.ejemplos.different" );
        for ( Comparacion.Ejemplo x : dist ) {
          tb.fila( x.clave, x.columna, x.esperado, x.real );
        }
        s.con( tb );
      }
      tablaFilas( s, t, id + "-missing", t.s( "md.ejemplos.missing" ), nn( ej.faltan ), f.faltan );
      tablaFilas( s, t, id + "-extra", t.s( "md.ejemplos.extra" ), nn( ej.sobran ), f.sobran );
      List<Comparacion.Ejemplo> rep = nn( ej.clavesRepetidas );
      if ( !rep.isEmpty() ) {
        Tabla tb = new Tabla( t.s( "md.col.clave" ), t.s( "md.col.veces" ) );
        tb.id = id + "-duplicates";
        tb.titulo = t.s( "md.ejemplos.duplicate_keys" );
        tb.alineacion = new ArrayList<String>( Arrays.asList( "l", "r" ) );
        for ( Comparacion.Ejemplo x : rep ) {
          tb.fila( x.clave, x.veces == null ? "" : String.valueOf( x.veces ) );
        }
        pie( tb, t, f.clavesRepetidas, rep.size() );
        s.con( tb );
      }
    }

    // ---- diferencias por columna (solo conteos: tambien en los ocultos)
    if ( cmp != null && cmp.diferenciasPorColumna != null && !cmp.diferenciasPorColumna.isEmpty() ) {
      Tabla tb = new Tabla( t.s( "md.col.columna" ), t.s( "inf.col.celdas" ) );
      tb.id = id + "-by-column";
      tb.titulo = t.s( "inf.dif_columna" );
      tb.alineacion = new ArrayList<String>( Arrays.asList( "l", "r" ) );
      for ( Map.Entry<String, Integer> x : cmp.diferenciasPorColumna.entrySet() ) {
        tb.fila( x.getKey(), x.getValue() == null ? "" : String.valueOf( x.getValue() ) );
      }
      s.con( tb );
    }

    // ---- filas por step
    if ( !reservado && run.metricas != null && run.metricas.isArray() && run.metricas.size() > 0 ) {
      Tabla tb = new Tabla( t.s( "md.col.step" ), t.s( "inf.col.leidas" ), t.s( "inf.col.escritas" ), t.s(
        "inf.col.errores" ) );
      tb.id = id + "-metrics";
      tb.titulo = t.s( "inf.metricas" );
      tb.alineacion = new ArrayList<String>( Arrays.asList( "l", "r", "r", "r" ) );
      int n = 0;
      for ( JsonNode m : run.metricas ) {
        if ( n++ >= MAX_METRICAS ) {
          break;
        }
        tb.fila( m.path( "step" ).asText( "" ), numero( m, "read" ), numero( m, "written" ), numero( m, "errors" ) );
      }
      pie( tb, t, run.metricas.size(), Math.min( run.metricas.size(), MAX_METRICAS ) );
      s.con( tb );
    }

    // ---- la cola del log, solo para el docente y si el caso no salio bien
    if ( !a.alumno && !rc.corrioBien() && Txt.vacio( run.log ) != null ) {
      Parrafo p = parrafo( id + "-log", cola( run.log ) );
      p.titulo = t.s( "inf.log" );
      s.con( p );
    }
    return s;
  }

  private static Seccion notas( Armado a ) {
    ResultadoCorreccion r = a.r;
    Rotulos t = a.t;
    Lista l = new Lista();
    l.id = "grader-notes";
    for ( ResultadoCorreccion.Aviso av : r.avisos ) {
      String x = textoAviso( av, t, !a.alumno );
      if ( x != null && !l.items.contains( x ) ) {
        l.items.add( x );
      }
    }
    if ( !a.alumno ) {
      if ( Txt.vacio( r.captura ) != null ) {
        l.items.add( t.f( "inf.nota.captura", r.captura ) );
      }
      if ( r.stepsPermitidosExtra != null && !r.stepsPermitidosExtra.isEmpty() ) {
        l.items.add( t.f( "inf.nota.permitidos", unir( r.stepsPermitidosExtra, ", " ) ) );
      }
      if ( r.tiempos != null && ( r.tiempos.inicioKettleSeg != null || r.tiempos.procesoSeg != null ) ) {
        l.items.add( t.f( "inf.nota.tiempos", segundos( r.tiempos.inicioKettleSeg, a.loc ), segundos(
          r.tiempos.procesoSeg, a.loc ) ) );
      }
      if ( Txt.vacio( r.versionPlugin ) != null ) {
        l.items.add( t.f( "inf.nota.version", r.versionPlugin ) );
      }
    }
    if ( l.items.isEmpty() ) {
      return null;
    }
    Seccion s = new Seccion( SEC_NOTAS, t.s( "md.avisos" ) );
    s.con( l );
    return s;
  }

  // ================================================================ curso

  /**
   * El informe de un lote para el docente: indicadores (con {@link CorrectorPlanilla#resumir}, igual que la
   * planilla), la distribucion de notas ({@link #distribucion}), los criterios mas fallados ({@link Grafico#BARRAS_H})
   * con su tabla, y la tabla de entregas. {@code notasFinales} (alumno → nota con el ajuste del docente; puede ser null) reemplaza la nota del
   * motor en las cuentas. {@code idioma} null = el del primer resultado.
   */
  public static Informe curso( List<ResultadoCorreccion> resultados, Map<String, Double> notasFinales, String idioma,
    Tema tema ) {
    List<ResultadoCorreccion> rs = new ArrayList<ResultadoCorreccion>();
    if ( resultados != null ) {
      for ( ResultadoCorreccion r : resultados ) {
        if ( r != null ) {
          rs.add( CorrectorJson.copia( r, ResultadoCorreccion.class ).completar() );
        }
      }
    }
    ResultadoCorreccion primero = rs.isEmpty() ? null : rs.get( 0 );
    String i = Rotulos.normalizar( idioma != null ? idioma : primero != null && primero.idioma != null
      ? primero.idioma : tema != null ? tema.idioma : null );
    Rotulos t = Rotulos.de( AREA, i );
    Rotulos e = Rotulos.de( InformeServicio.AREA, i );
    Locale loc = Rotulos.locale( i );
    double escala = primero == null || primero.escala <= 0 ? 10 : primero.escala;
    String packId = null;
    String titulo = null;
    for ( ResultadoCorreccion r : rs ) {
      packId = packId != null ? packId : Txt.vacio( r.pack );
      titulo = titulo != null ? titulo : Txt.vacio( r.tituloPack );
    }
    Informe inf = cabecera( titulo != null ? titulo.trim() : packId != null ? packId.trim() : t.s( "md.titulo" ),
      t.f( "inf.curso.subtitulo", rs.size() ), i );
    inf.nombreDoc = packId;

    // ---- filas como las de la planilla (con el ajuste del docente)
    List<CorrectorPlanilla.Fila> filas = new ArrayList<CorrectorPlanilla.Fila>();
    for ( ResultadoCorreccion r : rs ) {
      CorrectorPlanilla.Fila f = new CorrectorPlanilla.Fila( r.alumno, r.archivo, r );
      Double fin = notasFinales == null || r.alumno == null ? null : notasFinales.get( r.alumno );
      if ( fin != null && r.nota != null ) {
        f.ajuste = fin - r.nota;
      }
      filas.add( f );
    }
    Collections.sort( filas, new Comparator<CorrectorPlanilla.Fila>() {
      @Override
      public int compare( CorrectorPlanilla.Fila x, CorrectorPlanilla.Fila y ) {
        return String.valueOf( x.alumno ).compareToIgnoreCase( String.valueOf( y.alumno ) );
      }
    } );
    CorrectorPlanilla.Resumen res = CorrectorPlanilla.resumir( filas, escala );

    // ---- portada
    Seccion cover = new Seccion( SeccionId.PORTADA, SeccionId.titulo( SeccionId.PORTADA, i ) );
    logo( cover, tema, e );
    Lista l = new Lista();
    l.id = "cover-datos";
    if ( titulo != null ) {
      poner( l, t, "inf.portada.ejercicio", packId );
    }
    identidad( l, tema, t, e );
    l.items.add( t.f( "inf.curso.entregas", rs.size() ) );
    cover.con( l );
    inf.secciones.add( cover );

    // ---- resumen
    Seccion s = new Seccion( SEC_CURSO, t.s( "inf.curso.sec.resumen" ) );
    Indicadores ind = new Indicadores();
    ind.id = "course-cards";
    ind.con( new Indicador( t.s( "planilla.resumen.entregas" ), String.valueOf( rs.size() ), t.f(
      "inf.curso.con_resultado", res.corregidas ), null ) );
    ind.con( new Indicador( t.s( "planilla.resumen.media" ), res.media == null ? SIN_NOTA : uno( res.media, loc ),
      null, nivelNota( res.media, escala ) ) );
    ind.con( new Indicador( t.s( "planilla.resumen.mediana" ), res.mediana == null ? SIN_NOTA : uno( res.mediana,
      loc ), null, nivelNota( res.mediana, escala ) ) );
    ind.con( new Indicador( t.f( "planilla.resumen.aprobadas", puntos( escala / 2, loc ) ), t.f( "inf.de",
      res.aprobadas, res.corregidas ), null, res.corregidas == 0 ? null : res.aprobadas * 2 >= res.corregidas
        ? Indicador.OK : Indicador.WARN ) );
    ind.con( new Indicador( t.s( "planilla.resumen.bloqueadas" ), String.valueOf( res.bloqueadas ), null,
      res.bloqueadas == 0 ? Indicador.OK : Indicador.WARN ) );
    ind.con( new Indicador( t.s( "planilla.resumen.tiempo" ), String.valueOf( res.tiempoAgotado ), null,
      res.tiempoAgotado == 0 ? Indicador.OK : Indicador.WARN ) );
    ind.con( new Indicador( t.s( "planilla.resumen.fallidas" ), String.valueOf( res.fallidas ), null,
      res.fallidas == 0 ? Indicador.OK : Indicador.ERROR ) );
    s.con( ind );
    if ( res.media != null ) {
      s.resumen = t.f( "inf.curso.resumen", res.corregidas, uno( res.media, loc ), res.aprobadas );
      s.con( resumenEnDiapositiva( SEC_CURSO, s.resumen ) );
    }
    inf.secciones.add( s );

    // ---- distribucion de notas
    List<Double> notas = new ArrayList<Double>();
    for ( CorrectorPlanilla.Fila f : filas ) {
      Double n = f.resultado != null && f.resultado.terminada() ? f.notaFinal( escala ) : null;
      if ( n != null ) {
        notas.add( n );
      }
    }
    if ( !notas.isEmpty() ) {
      Seccion d = new Seccion( SEC_DISTRIBUCION, t.s( "inf.curso.sec.distribucion" ) );
      d.con( distribucion( notas, escala, t, loc ) );
      inf.secciones.add( d );
    }

    // ---- criterios mas fallados
    Seccion c = criteriosDelCurso( rs, t, loc );
    if ( c != null ) {
      inf.secciones.add( c );
    }

    // ---- entregas
    if ( !filas.isEmpty() ) {
      Seccion se = new Seccion( SEC_ENTREGAS, t.s( "inf.curso.sec.entregas" ) );
      Tabla tb = new Tabla( t.s( "md.alumno" ), t.s( "md.archivo" ), t.s( "md.estado" ), t.s( "md.nota" ), t.s(
        "inf.curso.col.criterios" ) );
      tb.id = "submissions-table";
      tb.alineacion = new ArrayList<String>( Arrays.asList( "l", "l", "l", "r", "r" ) );
      for ( CorrectorPlanilla.Fila f : filas ) {
        ResultadoCorreccion r = f.resultado;
        Double n = r.terminada() ? f.notaFinal( escala ) : null;
        int ok = 0;
        int total = 0;
        for ( Criterio k : r.criterios ) {
          if ( k != null ) {
            total++;
            ok += k.aprobado ? 1 : 0;
          }
        }
        tb.fila( f.alumno, f.archivo, texto( t, "estado.", r.estado ), n == null ? SIN_NOTA : uno( n, loc ),
          total == 0 ? "" : t.f( "inf.de", ok, total ) );
      }
      se.con( tb );
      inf.secciones.add( se );
    }
    return inf;
  }

  /**
   * Cuantas notas caen en cada uno de los TRAMOS de escala / TRAMOS: [0, 1), [1, 2) ... [9, 10]. Es un
   * {@link Grafico#LINEA} (poligono de frecuencias) y no un barras_h: GraficosSvg ordena las barras de mayor a menor y
   * la distribucion perderia el orden de las notas.
   */
  static Grafico distribucion( List<Double> notas, double escala, Rotulos t, Locale loc ) {
    Grafico g = new Grafico();
    g.id = "grade-distribution";
    g.clase = Grafico.LINEA;
    g.titulo = t.s( "inf.curso.distribucion" );
    g.unidad = t.s( "inf.curso.unidad" );
    double ancho = escala / TRAMOS;
    double[] cuenta = new double[ TRAMOS ];
    for ( double n : notas ) {
      int k = (int) Math.floor( n / ancho + 1e-9 );
      cuenta[ Math.max( 0, Math.min( TRAMOS - 1, k ) ) ]++;
    }
    Serie s = new Serie();
    s.nombre = t.s( "inf.curso.serie.entregas" );
    for ( int k = 0; k < TRAMOS; k++ ) {
      g.categorias.add( puntos( k * ancho, loc ) + "–" + puntos( ( k + 1 ) * ancho, loc ) );
      s.valores.add( cuenta[ k ] );
    }
    g.series.add( s );
    return g;
  }

  private static Seccion criteriosDelCurso( List<ResultadoCorreccion> rs, Rotulos t, Locale loc ) {
    final Map<String, double[]> cuentas = new LinkedHashMap<String, double[]>(); // check -> {con, aprueban, ganado}
    Map<String, String> etiquetas = new LinkedHashMap<String, String>();
    Map<String, Double> puntos = new LinkedHashMap<String, Double>();
    for ( ResultadoCorreccion r : rs ) {
      if ( !r.terminada() ) {
        continue;
      }
      for ( Criterio c : r.criterios ) {
        if ( c == null || c.check == null ) {
          continue;
        }
        double[] x = cuentas.get( c.check );
        if ( x == null ) {
          x = new double[ 3 ];
          cuentas.put( c.check, x );
          etiquetas.put( c.check, etiqueta( c ) );
          puntos.put( c.check, c.puntos );
        }
        x[ 0 ]++;
        x[ 1 ] += c.aprobado ? 1 : 0;
        x[ 2 ] += c.ganado;
      }
    }
    if ( cuentas.isEmpty() ) {
      return null;
    }
    List<String> orden = new ArrayList<String>( cuentas.keySet() );
    Collections.sort( orden, new Comparator<String>() {
      @Override
      public int compare( String a, String b ) {
        double fa = cuentas.get( a )[ 0 ] - cuentas.get( a )[ 1 ];
        double fb = cuentas.get( b )[ 0 ] - cuentas.get( b )[ 1 ];
        return Double.compare( fb, fa ); // estable: a igual cantidad, el orden de la rubrica
      }
    } );
    Seccion s = new Seccion( SEC_CHECKS, t.s( "inf.curso.sec.criterios" ) );
    Grafico g = new Grafico();
    g.id = "failed-checks";
    g.clase = Grafico.BARRAS_H; // ordenado de mas a menos fallado, que es lo que se quiere ver
    g.titulo = t.s( "inf.curso.fallados" );
    Serie no = new Serie();
    no.nombre = t.s( "inf.curso.serie.fallan" );
    for ( String k : orden ) {
      double[] x = cuentas.get( k );
      g.categorias.add( etiquetas.get( k ) );
      no.valores.add( x[ 0 ] - x[ 1 ] );
    }
    g.series.add( no );
    s.con( g );
    Tabla tb = new Tabla( t.s( "planilla.resumen.check" ), t.s( "planilla.resumen.puntos" ), t.s(
      "planilla.resumen.check_aprobado" ), t.s( "planilla.resumen.check_media" ) );
    tb.id = "checks-table";
    tb.alineacion = new ArrayList<String>( Arrays.asList( "l", "r", "r", "r" ) );
    for ( String k : cuentas.keySet() ) {
      double[] x = cuentas.get( k );
      tb.fila( etiquetas.get( k ), puntos( puntos.get( k ), loc ), t.f( "inf.de", (int) x[ 1 ], (int) x[ 0 ] ),
        puntos( x[ 0 ] == 0 ? 0 : x[ 2 ] / x[ 0 ], loc ) );
    }
    s.con( tb );
    return s;
  }

  // ================================================================ exportar

  /**
   * Exporta un informe de este puente con {@link InformeServicio#exportar} (corre en el hilo que llama: un worker,
   * nunca la UI). {@code tema} null = {@link #tema()}. {@link Formato#HTML} sin diagramas Mermaid (los de una
   * correccion no tienen) se escribe con el modo estatico en el mismo {@code destino}: el interactivo solo le suma
   * los 2,6 MB de Mermaid, y una devolucion por alumno los repetiria en cada archivo.
   */
  public static Resultado exportar( Informe inf, Tema tema, Formato f, File destino, Progreso p ) throws Exception {
    Formato real = f == Formato.HTML && InformeServicio.diagramas( inf ).isEmpty() ? Formato.HTML_ESTATICO : f;
    return InformeServicio.exportar( inf, tema == null ? tema() : tema, real, destino, p );
  }

  /**
   * La correccion en varios formatos, en {@code carpeta} con el nombre {@code base} (los nombres de
   * {@link InformeServicio#archivo}: base.html, base.light.html, base.pdf, base.md, base.pptx, base.docx, base.xlsx, base.json).
   * {@link Formato#JSON} es el contrato, no el modelo del informe: el resultado tal cual (docente) o
   * {@link #paraAlumno} (alumno). Ojo con {@code base}: en la carpeta de un lote,
   * {@code <alumno>.json} ya es el resultado completo.
   *
   * @return formato → lo que escribio, en el orden pedido.
   */
  public static Map<Formato, Resultado> exportar( ResultadoCorreccion r, boolean alumno, Collection<Formato> formatos,
    File carpeta, String base, Tema tema, Progreso p ) throws Exception {
    if ( r == null || carpeta == null ) {
      throw new IllegalArgumentException( "Missing result or folder" );
    }
    Tema t = tema == null ? tema() : tema;
    String b = Txt.nombreArchivo( Txt.vacio( base ) != null ? base : Txt.vacio( r.alumno ) != null ? r.alumno
      : "correccion", 80 );
    Informe inf = informe( r, alumno, t );
    Map<Formato, Resultado> out = new LinkedHashMap<Formato, Resultado>();
    for ( Formato f : formatos == null ? Collections.<Formato>emptyList() : formatos ) {
      if ( f == null || out.containsKey( f ) ) {
        continue;
      }
      if ( p != null && p.cancelado() ) {
        throw new java.util.concurrent.CancellationException();
      }
      File destino = InformeServicio.archivo( carpeta, b, f );
      if ( f == Formato.JSON ) {
        if ( !carpeta.isDirectory() && !carpeta.mkdirs() ) {
          throw new IOException( "Could not create the folder " + carpeta );
        }
        ResultadoCorreccion x = alumno ? paraAlumno( r ) : r;
        CorrectorPlanilla.escribirAtomico( CorrectorJson.escribir( x, true ).getBytes( StandardCharsets.UTF_8 ),
          destino );
        out.put( f, new Resultado().archivo( destino ) );
      } else {
        out.put( f, exportar( inf, t, f, destino, p ) );
      }
    }
    return out;
  }

  /**
   * La devolucion del alumno en HTML liviano (sin scripts: abre tambien en el {@code Browser} IE11 de Spoon), por
   * ejemplo {@code <alumno>.html} junto al {@code .md} de un lote. Devuelve el archivo escrito.
   */
  public static File devolucionHtml( ResultadoCorreccion r, File destino, Tema tema ) throws Exception {
    Tema t = tema == null ? tema() : tema;
    Resultado res = InformeServicio.exportar( informe( r, true, t ), t, Formato.HTML_ESTATICO, destino,
      Progreso.NULO );
    return res.archivos.isEmpty() ? destino : res.archivos.get( 0 );
  }

  /** El tema guardado (Ajustes.cargar), o la plantilla neutra si no se puede leer. Nunca null. */
  public static Tema tema() {
    try {
      Tema t = Ajustes.cargar();
      return t == null ? new Tema() : t;
    } catch ( Throwable e ) {
      return new Tema();
    }
  }

  // ================================================================ piezas

  /** Un Informe vacio con titulo, idioma, fecha y los rotulos del renderizador (como InformeServicio.cabecera). */
  private static Informe cabecera( String titulo, String subtitulo, String idioma ) {
    Informe inf = new Informe();
    inf.titulo = titulo;
    inf.subtitulo = subtitulo;
    inf.idioma = idioma;
    inf.generado = ZonedDateTime.now().truncatedTo( ChronoUnit.SECONDS ).format(
      DateTimeFormatter.ISO_OFFSET_DATE_TIME );
    Rotulos render = Rotulos.de( RenderHtml.AREA, idioma );
    Rotulos est = Rotulos.de( InformeServicio.AREA, idioma );
    for ( String k : InformeServicio.CLAVES_ROTULOS ) {
      inf.rotulos.put( k, render.tiene( k ) ? render.s( k ) : est.s( "rotulo." + k ) );
    }
    return inf;
  }

  private static void agregar( Informe inf, Seccion s ) {
    if ( s != null ) {
      inf.secciones.add( s );
    }
  }

  /** El logo del tema como primer bloque de la portada (como SecPortada). */
  private static void logo( Seccion s, Tema tema, Rotulos e ) {
    if ( tema == null || tema.logoDatos == null || tema.logoDatos.length == 0 ) {
      return;
    }
    int[] wh = InformeServicio.medidas( tema.logoDatos );
    String mime = tema.logoMime != null ? tema.logoMime : ( tema.logoDatos[ 0 ] & 0xFF ) == 0xFF ? Imagen.JPG
      : Imagen.PNG;
    Imagen im = new Imagen( mime, tema.logoDatos, wh[ 0 ], wh[ 1 ], 1f );
    im.id = "cover-logo";
    im.alt = tema.identidad != null && Txt.vacio( tema.identidad.institucion ) != null ? tema.identidad.institucion
      : e.s( "portada.logo_alt" );
    s.con( im );
  }

  /** Docente, institucion, curso y contacto del tema. */
  private static void identidad( Lista l, Tema tema, Rotulos t, Rotulos e ) {
    if ( tema == null || tema.identidad == null ) {
      return;
    }
    poner( l, t, "inf.portada.docente", tema.identidad.autor );
    poner( l, e, "portada.institucion", tema.identidad.institucion );
    poner( l, e, "portada.curso", tema.identidad.curso );
    poner( l, e, "portada.contacto", tema.identidad.contacto );
  }

  private static void poner( Lista l, Rotulos r, String clave, String valor ) {
    if ( Txt.vacio( valor ) != null ) {
      l.items.add( r.f( clave, valor.trim() ) );
    }
  }

  /** «Columnas que faltan: a, b». */
  private static void lista( Lista l, String titulo, List<String> valores ) {
    List<String> v = new ArrayList<String>();
    for ( String x : nn( valores ) ) {
      if ( x != null ) {
        v.add( x );
      }
    }
    if ( !v.isEmpty() ) {
      l.items.add( titulo + ": " + unir( v, ", " ) );
    }
  }

  /** Filas que faltan o sobran: una columna «Clave», o las columnas de la fila completa cuando no hay clave. */
  private static void tablaFilas( Seccion s, Rotulos t, String id, String titulo, List<Comparacion.Ejemplo> l,
    int total ) {
    if ( l.isEmpty() ) {
      return;
    }
    Set<String> cols = new LinkedHashSet<String>();
    boolean conClave = false;
    for ( Comparacion.Ejemplo x : l ) {
      if ( x.fila != null && !x.fila.isEmpty() ) {
        cols.addAll( x.fila.keySet() );
      }
      conClave |= x.clave != null;
    }
    Tabla tb = new Tabla();
    if ( conClave || cols.isEmpty() ) {
      tb.columnas.add( t.s( "md.col.clave" ) );
    }
    tb.columnas.addAll( cols );
    for ( Comparacion.Ejemplo x : l ) {
      List<String> fila = new ArrayList<String>();
      if ( conClave || cols.isEmpty() ) {
        fila.add( x.clave );
      }
      for ( String c : cols ) {
        fila.add( x.fila == null ? null : x.fila.get( c ) );
      }
      tb.filas.add( fila );
    }
    tb.id = id;
    tb.titulo = titulo;
    pie( tb, t, total, l.size() );
    s.con( tb );
  }

  /** «y 3 más» bajo una tabla de ejemplos recortada. */
  private static void pie( Tabla tb, Rotulos t, int total, int mostradas ) {
    if ( total > mostradas ) {
      tb.pie = t.f( "inf.y_mas", total - mostradas );
    }
  }

  /**
   * El texto de un aviso, traducido por su codigo ({@code aviso.<code>}). Si la traduccion tiene {@code %s}, el
   * {@code detail} va adentro: son los avisos de la comparacion, cuyo detalle es solo datos (nombres de columnas,
   * conteos), tambien en los ocultos. Si no, el detalle (una frase en ingles para el docente) va entre parentesis solo
   * con {@code conDetalle}; al alumno le llega a lo sumo el step. Sin traduccion: el detalle, o el codigo.
   */
  static String textoAviso( ResultadoCorreccion.Aviso a, Rotulos t, boolean conDetalle ) {
    if ( a == null || ( a.codigo == null && a.detalle == null ) ) {
      return null;
    }
    String k = "aviso." + a.codigo;
    String det = Txt.vacio( a.detalle ) == null ? null : a.detalle.trim();
    if ( det != null && a.claveDetalle != null && t.tiene( a.claveDetalle ) ) {
      det = CorrectorTextos.detalle( t, a ).trim(); // el detalle en el idioma del informe
    }
    if ( a.codigo == null || !t.tiene( k ) ) {
      return det != null ? det : a.codigo;
    }
    String base = t.s( k );
    if ( base.contains( "%s" ) ) {
      return t.f( k, det == null ? "-" : det );
    }
    if ( conDetalle && det != null ) {
      return entreParentesis( base, det );
    }
    if ( Txt.vacio( a.step ) != null ) {
      return entreParentesis( base, "«" + a.step.trim() + "»" );
    }
    return base;
  }

  /** «Frase.» + x → «Frase (x).». */
  private static String entreParentesis( String frase, String x ) {
    String f = frase.trim();
    boolean punto = f.endsWith( "." );
    return ( punto ? f.substring( 0, f.length() - 1 ) : f ) + " (" + x + ")" + ( punto ? "." : "" );
  }

  /** La cola del log: las ultimas MAX_LINEAS_LOG lineas, hasta MAX_LOG caracteres. */
  static String cola( String log ) {
    String[] l = log.replace( "\r", "" ).split( "\n" );
    int desde = Math.max( 0, l.length - MAX_LINEAS_LOG );
    StringBuilder b = new StringBuilder();
    for ( int i = desde; i < l.length; i++ ) {
      b.append( b.length() == 0 ? "" : "\n" ).append( l[ i ] );
    }
    String s = b.toString().trim();
    return s.length() <= MAX_LOG ? s : "…" + s.substring( s.length() - MAX_LOG + 1 );
  }

  /** Un caso sin diferencias: corrio bien y columnas, filas y valores completos. */
  static boolean sinDiferencias( ResultadoCaso rc ) {
    Comparacion c = rc.comparacion;
    return rc.corrioBien() && c != null && c.puntajeColumnas >= 1 - 1e-9 && c.cantidadFilasOk
      && c.puntajeValores >= 1 - 1e-9;
  }

  /**
   * El resumen de la seccion repetido como parrafo: el HTML y el Markdown lo saltean (Html.sinRepetirResumen) y el
   * PPTX, que pone el resumen en las notas del orador, lo muestra en la diapositiva.
   */
  private static Parrafo resumenEnDiapositiva( String idSeccion, String resumen ) {
    return parrafo( idSeccion + "-summary", resumen );
  }

  private static String etiqueta( Criterio c ) {
    return Txt.vacio( c.etiqueta ) != null ? c.etiqueta.trim() : String.valueOf( c.check );
  }

  private static Aviso aviso( String id, String nivel, String texto ) {
    Aviso a = new Aviso( nivel, texto );
    a.id = id;
    return a;
  }

  private static Parrafo parrafo( String id, String texto ) {
    Parrafo p = new Parrafo( texto );
    p.id = id;
    return p;
  }

  /** ok si llega a la mitad de la escala, error si no; null sin nota. */
  static String nivelNota( Double nota, double escala ) {
    if ( nota == null ) {
      return null;
    }
    return nota >= ( escala <= 0 ? 10 : escala ) / 2 - 1e-9 ? Indicador.OK : Indicador.ERROR;
  }

  private static String nivelEstado( String estado ) {
    if ( ResultadoCorreccion.CORREGIDA.equals( estado ) ) {
      return Indicador.OK;
    }
    if ( ResultadoCorreccion.TIEMPO_AGOTADO.equals( estado ) || ResultadoCorreccion.MATADA.equals( estado ) ) {
      return Indicador.WARN;
    }
    return ResultadoCorreccion.EN_CURSO.equals( estado ) || estado == null ? null : Indicador.ERROR;
  }

  private static String nivelAviso( String estado ) {
    return ResultadoCorreccion.TIEMPO_AGOTADO.equals( estado ) || ResultadoCorreccion.MATADA.equals( estado )
      ? Aviso.WARN : Aviso.ERROR;
  }

  /** El texto de prefijo + codigo, o el codigo si no hay texto para el. */
  private static String texto( Rotulos t, String prefijo, String codigo ) {
    if ( codigo == null ) {
      return "";
    }
    return t.tiene( prefijo + codigo ) ? t.s( prefijo + codigo ) : codigo;
  }

  /** «6,5 / 10» (con la coma o el punto del idioma); «– / 10» sin nota. */
  private static String notaSobre( Double nota, double escala, Locale loc ) {
    return ( nota == null ? SIN_NOTA : uno( nota, loc ) ) + " / " + puntos( escala, loc );
  }

  /** Un decimal: 6,5. */
  private static String uno( double x, Locale loc ) {
    return String.format( loc, "%.1f", x );
  }

  /** Hasta dos decimales, sin ceros de mas: 0,5 / 4 / 9,67. */
  static String puntos( double x, Locale loc ) {
    return new DecimalFormat( "0.##", DecimalFormatSymbols.getInstance( loc ) ).format( x );
  }

  /** 0,25 → «25%». */
  private static String porciento( double x ) {
    return Math.round( Math.max( 0, Math.min( 1, x ) ) * 100 ) + "%";
  }

  private static String segundos( Double s, Locale loc ) {
    return s == null ? SIN_NOTA : String.format( loc, "%.1f", s );
  }

  private static String numero( JsonNode m, String campo ) {
    JsonNode v = m.get( campo );
    return v == null || v.isNull() ? "" : v.asText();
  }

  /** ISO-8601 → «2026-10-20 14:32»; el texto tal cual si no se puede leer. */
  static String fecha( String iso ) {
    if ( Txt.vacio( iso ) == null ) {
      return null;
    }
    try {
      TemporalAccessor ta = DateTimeFormatter.ISO_DATE_TIME.parse( iso.trim() );
      return DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm" ).format( ta );
    } catch ( RuntimeException e ) {
      return iso.trim();
    }
  }

  private static String unir( List<String> l, String sep ) {
    StringBuilder b = new StringBuilder();
    for ( String s : l ) {
      if ( s != null ) {
        b.append( b.length() == 0 ? "" : sep ).append( s );
      }
    }
    return b.toString();
  }

  private static <T> List<T> nn( List<T> l ) {
    return l == null ? Collections.<T>emptyList() : l;
  }
}
