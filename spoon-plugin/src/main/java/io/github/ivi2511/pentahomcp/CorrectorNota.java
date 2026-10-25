package io.github.ivi2511.pentahomcp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Criterio;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.PasoBloqueado;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.ReglaEvaluada;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.Catalogo;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.NoEvaluado;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Sev;
import io.github.ivi2511.pentahomcp.lint.Textos;

/**
 * La nota de una entrega (docs/corrector-diseno.md §5 y §6). Tres partes, sin ejecutar nada:
 * <ol>
 * <li>{@link #analizar}: el analisis estatico que la hija hace antes de correr los casos (va en el parcial): las
 * reglas del pack ({@code rules.<id>}: must_use, must_not_use, max_steps, error_handling, lint) y los renglones
 * {@code lint:<id>} con {@link LintMotor} (sin supresiones, sin modo profundo, sin reglas {@code env}: la nota no
 * depende de la maquina ni de lo que el alumno silencio). {@link #erroresSilenciosos} da D1 y D2 con el mismo motor.</li>
 * <li>{@link #puntuar}: la rubrica sobre el resultado ya corrido. Cada check de salida (runs, columns, row_count,
 * values) se promedia entre casos segun su peso; rule:/lint: valen 1 o 0; las compuertas ({@code requires}) dejan
 * en 0 el renglon si un check requerido no se aprobo; nota = ganado / puntos x escala, redondeada a 0,1. Escribe
 * {@code checks}, {@code grade} y las pistas ({@code hints}, §6), ya traducidas y sin repetir entre casos.</li>
 * <li>{@link #markdown}: la devolucion minima en .md para pruebas sin Informes (C9 dibuja la de verdad).</li>
 * </ol>
 * Todo texto de la entrega (nombres de steps, rutas, valores) es dato del alumno: las pistas citan nombres de
 * columnas y de steps, nunca valores, y el .md lo escapa.
 *
 * @dueño C5
 */
final class CorrectorNota {

  /** Area de Rotulos: informes/textos/corrector_&lt;idioma&gt;.properties. */
  static final String AREA = "corrector";
  /** Presupuesto del linter por entrega (campos incluidos). */
  static final long MS_LINT = 60000;
  /** Reglas del linter que corren solo para las pistas (D4, D5, D6), si la entrega no usa bases de datos. */
  static final List<String> LINT_DE_PISTAS = Collections.unmodifiableList( Arrays.asList( "calculator_integer_first",
    "unsorted_input" ) );
  static final String D1_LINT = "duplicate_step_name";
  static final String D2_LINT = "cycle";
  /** Pistas de columnas y de valores: como mucho estas (una por columna). */
  static final int MAX_PISTAS_COLUMNA = 3;
  /** Steps que dejan el resultado en una base (captura query sin output.step). */
  static final Set<String> SALIDAS_A_BASE = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList(
    "TableOutput", "InsertUpdate", "Update", "SynchronizeAfterMerge", "DimensionLookup", "CombinationLookup" ) ) );
  static final Set<String> GROUP_BY = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList(
    "GroupBy", "MemoryGroupBy" ) ) );
  static final Set<String> MERGES = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList(
    "MergeJoin", "MergeRows", "SortedMerge", "MultiwayMergeJoin" ) ) );

  // ---- ids de las pistas (estables: los usan las pruebas y casos.json; el texto sale de Rotulos)
  static final String P_NO_CARGO = "load_failed";
  static final String P_STEP_BLOQUEADO = "blocked_step";
  static final String P_LEE_FUERA = "read_outside_pack";
  static final String P_SALIDA_EN_CAMPO = "unexpected_output";
  static final String P_CONEXION = "foreign_connection";
  static final String P_SQL = "forbidden_sql";
  static final String P_ESQUEMA = "foreign_schema";
  static final String P_REESCRITURA = "rewrite_failed";
  static final String P_D1 = "D1";
  static final String P_D2 = "D2";
  static final String P_TIEMPO = "timeout";
  static final String P_CAIDA = "crashed";
  static final String P_SALIDA_GRANDE = "output_too_big";
  static final String P_FILAS = "too_many_rows";
  static final String P_INIT = "init_failed";
  static final String P_CON_ERRORES = "finished_with_errors";
  static final String P_SIN_SALIDA = "no_output_step";
  static final String P_FALTA_COLUMNA = "missing_column";
  static final String P_SOBRA_COLUMNA = "extra_column";
  static final String P_RENOMBRE = "rename";
  static final String P_ORDEN_COLUMNAS = "column_order";
  static final String P_D5 = "D5";
  static final String P_CLAVES_REPETIDAS = "duplicate_keys";
  static final String P_FALTAN_FILAS = "missing_rows";
  static final String P_SOBRAN_FILAS = "extra_rows";
  static final String P_D3 = "D3";
  static final String P_D4 = "D4";
  static final String P_D6 = "D6";
  static final String P_VALORES = "values";
  static final String P_COMA = "comma_decimal";
  static final String P_ENCODING = "encoding";

  /** «CafÃ©», «TÃ©»: UTF-8 leido como Latin-1; y el caracter de reemplazo. */
  private static final Pattern MOJIBAKE = Pattern.compile( "�|[ÂÃ][\u0080-¿]" );
  private static final double EPS = 1e-9;

  private CorrectorNota() {
  }

  /** Lo que el analisis estatico sabe de la entrega y usan las pistas. No va al JSON. */
  static final class Estatico {

    /** Ids de plugin de todos los steps (tambien los sueltos, P20). */
    final Set<String> tipos = new LinkedHashSet<String>();
    /** De {@link #LINT_DE_PISTAS}, las que tienen algun hallazgo &gt;= warning. */
    final Set<String> lint = new LinkedHashSet<String>();
    /** false si las reglas de las pistas no corrieron (la entrega usa una conexion: no se calculan campos). */
    boolean lintDePistas;
  }

  /** Una pista: id estable (D5, rename...) y la frase para el alumno, ya traducida. */
  static final class Pista {

    final String id;
    final String texto;

    Pista( String id, String texto ) {
      this.id = id;
      this.texto = texto;
    }

    @Override
    public String toString() {
      return id + ": " + texto;
    }
  }

  /** D1 o D2: no se ejecuta (§3 paso 2). */
  static final class Bloqueo {

    /** {@link ResultadoCaso#NOMBRE_REPETIDO} o {@link ResultadoCaso#CICLO}. */
    final String motivo;
    final String step;
    /** El mensaje del linter, en el idioma pedido. */
    final String detalle;

    Bloqueo( String motivo, String step, String detalle ) {
      this.motivo = motivo;
      this.step = step;
      this.detalle = detalle;
    }
  }

  // ================================================================ analisis estatico

  /**
   * El analisis estatico de la entrega (§3 paso 2), sin ejecutar: llena {@code r.rules} con cada regla del pack y
   * {@code r.lint} con cada renglon {@code lint:<id>} de la rubrica. {@code tm} se analiza tal cual (el linter calcula
   * campos: pasar una copia que no se vaya a ejecutar, cargada desde un archivo para que D1 y las reglas de modo
   * archivo lean el XML crudo; con captura query conviene la copia del sandbox, donde la base existe).
   * {@code pasoSalida}: el step de salida si ya se sabe (null = output.step del pack o, si no esta, los Text file
   * output / Table output de la entrega). Devuelve lo que las pistas necesitan. tm null: nada se evalua.
   */
  static Estatico analizar( Pack pack, TransMeta tm, String pasoSalida, ResultadoCorreccion r ) {
    return analizar( pack, tm, pasoSalida, r, null );
  }

  /** Como el de arriba; dirPack: la carpeta del pack, donde va su perfil del linter (lint:profile, 0.7). */
  static Estatico analizar( Pack pack, TransMeta tm, String pasoSalida, ResultadoCorreccion r, java.io.File dirPack ) {
    pack.completar();
    r.completar();
    String idioma = idioma( pack, r );
    Rotulos t = Rotulos.de( AREA, idioma );
    Estatico e = new Estatico();
    if ( tm == null ) {
      for ( Regla rg : pack.reglas ) {
        if ( rg != null && rg.id != null ) {
          r.reglas.put( rg.id, noEvaluada( etiquetaRegla( rg, t, Textos.de( idioma ) ), t.s(
            "regla.sin_transformacion" ) ) );
        }
      }
      return e;
    }
    for ( StepMeta s : tm.getSteps() ) {
      if ( s.getStepID() != null ) {
        e.tipos.add( s.getStepID() );
      }
    }
    Set<String> pedidas = new LinkedHashSet<String>();
    for ( ItemRubrica i : pack.rubrica ) {
      if ( i != null && i.esLint() ) {
        pedidas.add( normLint( i.idLint() ) );
      }
    }
    for ( Regla rg : pack.reglas ) {
      if ( rg != null && Regla.LINT.equals( rg.tipo ) && rg.reglaLint != null ) {
        pedidas.add( normLint( rg.reglaLint ) );
      }
    }
    Set<String> correr = new LinkedHashSet<String>( pedidas );
    boolean conPerfil = correr.remove( CorrectorPerfil.ID ); // lint:profile, con el perfil del pack (CorrectorPerfil)
    e.lintDePistas = !usaBases( tm );
    if ( e.lintDePistas ) {
      correr.addAll( LINT_DE_PISTAS );
    }
    Map<String, ReglaEvaluada> lint = evaluarLint( correr, tm, idioma );
    if ( conPerfil ) {
      lint.put( CorrectorPerfil.ID, CorrectorPerfil.evaluar( dirPack, tm, idioma ) );
    }
    for ( ItemRubrica i : pack.rubrica ) {
      ReglaEvaluada x = i != null && i.esLint() ? lint.get( normLint( i.idLint() ) ) : null;
      if ( x != null ) {
        r.lint.put( i.idLint(), x );
      }
    }
    if ( e.lintDePistas ) {
      for ( String id : LINT_DE_PISTAS ) {
        ReglaEvaluada x = lint.get( id );
        if ( x != null && x.evaluada && !x.aprobada ) {
          e.lint.add( id );
        }
      }
    }
    r.reglas.putAll( evaluarReglas( pack, tm, pasoSalida, idioma, lint ) );
    return e;
  }

  /** Las reglas del pack sobre la transformacion: id -&gt; resultado. {@code lint}: lo de {@link #evaluarLint}. */
  static Map<String, ReglaEvaluada> evaluarReglas( Pack pack, TransMeta tm, String pasoSalida, String idioma,
                                                   Map<String, ReglaEvaluada> lint ) {
    Rotulos t = Rotulos.de( AREA, idioma );
    Textos tx = Textos.de( idioma );
    Map<String, ReglaEvaluada> out = new LinkedHashMap<String, ReglaEvaluada>();
    for ( Regla rg : pack.reglas ) {
      if ( rg == null || rg.id == null ) {
        continue;
      }
      ReglaEvaluada re;
      try {
        re = evaluarRegla( rg, pack, tm, pasoSalida, t, lint );
      } catch ( Throwable x ) { // una regla rota no tira las demas
        re = noEvaluada( null, t.f( "regla.fallo", RunMotor.primeraLinea( x ) ) );
      }
      re.etiqueta = etiquetaRegla( rg, t, tx );
      out.put( rg.id, re );
    }
    return out;
  }

  private static ReglaEvaluada evaluarRegla( Regla rg, Pack pack, TransMeta tm, String pasoSalida, Rotulos t,
                                             Map<String, ReglaEvaluada> lint ) {
    if ( tm == null ) {
      return noEvaluada( null, t.s( "regla.sin_transformacion" ) );
    }
    String tipo = rg.tipo == null ? "" : rg.tipo;
    if ( Regla.DEBE_USAR.equals( tipo ) ) {
      List<StepMeta> deTipo = pasosDeTipo( tm, rg.steps );
      if ( deTipo.isEmpty() ) {
        return new ReglaEvaluada( null, false, t.f( "regla.no_encontrado", lista( rg.steps ) ) );
      }
      List<StepMeta> salidas = pasosDeSalida( pack, tm, pasoSalida );
      List<StepMeta> ok = new ArrayList<StepMeta>();
      if ( !salidas.isEmpty() ) {
        Set<StepMeta> anc = ancestros( tm, salidas );
        for ( StepMeta s : deTipo ) {
          if ( anc.contains( s ) ) {
            ok.add( s );
          }
        }
        if ( ok.isEmpty() ) {
          return new ReglaEvaluada( null, false, t.f( "regla.no_llega", nombres( deTipo ), nombres( salidas ) ) );
        }
      } else {
        for ( StepMeta s : deTipo ) {
          if ( conectado( tm, s ) ) {
            ok.add( s );
          }
        }
        if ( ok.isEmpty() ) {
          return new ReglaEvaluada( null, false, t.f( "regla.suelto", nombres( deTipo ) ) );
        }
      }
      return new ReglaEvaluada( null, true, t.f( "regla.encontrado", nombres( ok ) ) );
    }
    if ( Regla.NO_DEBE_USAR.equals( tipo ) ) {
      List<StepMeta> deTipo = pasosDeTipo( tm, rg.steps );
      return deTipo.isEmpty() ? new ReglaEvaluada( null, true, t.s( "regla.no_usa" ) )
        : new ReglaEvaluada( null, false, t.f( "regla.usa", nombresConTipo( deTipo ) ) );
    }
    if ( Regla.MAX_STEPS.equals( tipo ) ) {
      if ( rg.valor == null ) {
        return noEvaluada( null, t.s( "regla.sin_valor" ) );
      }
      int n = tm.nrSteps();
      return new ReglaEvaluada( null, n <= rg.valor, t.f( "regla.cuenta", n, rg.valor ) );
    }
    if ( Regla.MANEJO_ERRORES.equals( tipo ) ) {
      List<StepMeta> deTipo = pasosDeTipo( tm, rg.steps );
      if ( deTipo.isEmpty() ) {
        return new ReglaEvaluada( null, false, t.f( "regla.no_encontrado", lista( rg.steps ) ) );
      }
      List<StepMeta> sin = new ArrayList<StepMeta>();
      for ( StepMeta s : deTipo ) {
        if ( !s.isDoingErrorHandling() ) {
          sin.add( s );
        }
      }
      return sin.isEmpty() ? new ReglaEvaluada( null, true, t.f( "regla.con_manejo", nombres( deTipo ) ) )
        : new ReglaEvaluada( null, false, t.f( "regla.sin_manejo", nombres( sin ) ) );
    }
    if ( Regla.LINT.equals( tipo ) ) {
      ReglaEvaluada x = lint == null || rg.reglaLint == null ? null : lint.get( normLint( rg.reglaLint ) );
      if ( x == null ) {
        return noEvaluada( null, t.s( "detalle.regla_sin_resultado" ) );
      }
      ReglaEvaluada c = new ReglaEvaluada( null, x.aprobada, x.detalle );
      c.evaluada = x.evaluada;
      return c;
    }
    return noEvaluada( null, t.f( "regla.tipo_desconocido", tipo ) );
  }

  /**
   * Las reglas del linter pedidas, con el contrato de docs/linter-diseno.md §9: aprobada si no hay hallazgos &gt;=
   * warning de esa regla. Corre {@link LintMotor} una vez con {@code honorSuppressions=false} (los atributos
   * pentaho-live-lint de la entrega no cuentan: si no, el alumno silencia la regla que se evalua), sin modo profundo
   * (lee archivos) ni kettle_check. Una regla {@code env} (depende de la maquina), una que no existe o una que no se
   * pudo evaluar quedan con {@code evaluated=false} y valen 0. Clave: el id del catalogo en minusculas.
   */
  static Map<String, ReglaEvaluada> evaluarLint( Collection<String> ids, TransMeta tm, String idioma ) {
    Rotulos t = Rotulos.de( AREA, idioma );
    Textos tx = Textos.de( idioma );
    Map<String, ReglaEvaluada> out = new LinkedHashMap<String, ReglaEvaluada>();
    Set<String> validas = new LinkedHashSet<String>();
    for ( String id : ids ) {
      String n = normLint( id );
      io.github.ivi2511.pentahomcp.lint.Regla rl = Catalogo.buscar( n );
      if ( rl == null ) {
        out.put( n, noEvaluada( n, t.f( "lint.desconocida", n ) ) );
      } else if ( rl.env() || Catalogo.KETTLE_CHECK.equals( rl.id() ) ) {
        out.put( rl.id(), noEvaluada( tx.t( rl.id() + ".title" ), t.s( "lint.env" ) ) );
      } else {
        validas.add( rl.id() );
      }
    }
    if ( validas.isEmpty() ) {
      return out;
    }
    if ( tm == null ) {
      for ( String id : validas ) {
        out.put( id, noEvaluada( tx.t( id + ".title" ), t.s( "regla.sin_transformacion" ) ) );
      }
      return out;
    }
    Resultado res;
    try {
      res = LintMotor.analizar( tm, opcionesLint( tm, validas, idioma ) );
    } catch ( Throwable x ) {
      for ( String id : validas ) {
        out.put( id, noEvaluada( tx.t( id + ".title" ), t.f( "lint.fallo", RunMotor.primeraLinea( x ) ) ) );
      }
      return out;
    }
    for ( String id : validas ) {
      List<Hallazgo> malos = new ArrayList<Hallazgo>();
      for ( Hallazgo h : res.findings ) {
        if ( id.equals( h.rule ) && h.severity != null && h.severity.almenos( Sev.WARNING ) && !h.env ) {
          malos.add( h );
        }
      }
      String titulo = tx.t( id + ".title" );
      if ( !malos.isEmpty() ) {
        String d = malos.get( 0 ).message;
        out.put( id, new ReglaEvaluada( titulo, false, malos.size() == 1 ? d : t.f( "lint.y_mas", d, malos.size()
          - 1 ) ) );
        continue;
      }
      NoEvaluado ne = null;
      for ( NoEvaluado n : res.notEvaluated ) {
        if ( id.equals( n.rule ) ) {
          ne = n;
          break;
        }
      }
      out.put( id, ne != null ? noEvaluada( titulo, ne.reason ) : new ReglaEvaluada( titulo, true, t.s(
        "lint.sin_hallazgos" ) ) );
    }
    return out;
  }

  /**
   * D1 (dos steps con el mismo nombre sin mayusculas, en el XML crudo: {@code duplicate_step_name}) y D2 (hop
   * circular: {@code cycle}) con el motor del linter: con cualquiera de los dos no se ejecuta (§3 paso 2). D1 primero
   * (los steps fundidos dejan un hop de un step a si mismo, que tambien es ciclo). D1 necesita que tm venga de un
   * archivo; si no, solo se mira D2. null si no hay ninguno o si el motor fallo.
   */
  static Bloqueo erroresSilenciosos( TransMeta tm, String idioma ) {
    if ( tm == null ) {
      return null;
    }
    Resultado res;
    try {
      res = LintMotor.analizar( tm, opcionesLint( tm, new LinkedHashSet<String>( Arrays.asList( D1_LINT, D2_LINT ) ),
        Rotulos.normalizar( idioma ) ) );
    } catch ( Throwable x ) {
      return null;
    }
    for ( String id : Arrays.asList( D1_LINT, D2_LINT ) ) {
      for ( Hallazgo h : res.findings ) {
        if ( id.equals( h.rule ) ) {
          return new Bloqueo( D1_LINT.equals( id ) ? ResultadoCaso.NOMBRE_REPETIDO : ResultadoCaso.CICLO, h.step,
            h.message );
        }
      }
    }
    return null;
  }

  private static Opciones opcionesLint( TransMeta tm, Set<String> ids, String idioma ) {
    Opciones op = new Opciones();
    op.rules = ids;
    op.honorSuppressions = false;
    op.deep = false;
    op.kettleChecks = false;
    op.minSeverity = Sev.INFO;
    op.lang = Rotulos.normalizar( idioma );
    op.entorno = new LintEntorno( tm, MS_LINT );
    return op;
  }

  /** true si algun step usa una conexion: entonces las reglas de las pistas no calculan campos (se conectarian). */
  static boolean usaBases( TransMeta tm ) {
    for ( StepMeta s : tm.getSteps() ) {
      try {
        DatabaseMeta[] dbs = s.getStepMetaInterface() == null ? null
          : s.getStepMetaInterface().getUsedDatabaseConnections();
        if ( dbs != null && dbs.length > 0 ) {
          return true;
        }
      } catch ( RuntimeException e ) {
        return true;
      }
    }
    return false;
  }

  /**
   * Donde termina el resultado: {@code pasoSalida}, si no output.step del pack (si estan en la entrega); si no, segun
   * la captura, los Text file output (file) o las salidas a base (query). Vacio = no se sabe.
   */
  static List<StepMeta> pasosDeSalida( Pack pack, TransMeta tm, String pasoSalida ) {
    for ( String n : Arrays.asList( pasoSalida, pack.salida == null ? null : pack.salida.step ) ) {
      StepMeta s = n == null ? null : tm.findStep( n );
      if ( s != null ) {
        return Collections.singletonList( s );
      }
    }
    String captura = pack.salida == null ? null : pack.salida.captura;
    Set<String> tipos = Pack.Salida.aBase( captura ) ? SALIDAS_A_BASE
      : Pack.Salida.CAPTURA_STEP.equals( captura ) ? Collections.<String>emptySet()
        : Collections.singleton( "TextFileOutput" );
    List<StepMeta> l = new ArrayList<StepMeta>();
    for ( StepMeta s : tm.getSteps() ) {
      if ( s.getStepID() != null && tipos.contains( s.getStepID() ) ) {
        l.add( s );
      }
    }
    return l;
  }

  /** Los steps de destino y todo lo que llega a ellos por hops habilitados. */
  static Set<StepMeta> ancestros( TransMeta tm, Collection<StepMeta> destinos ) {
    Set<StepMeta> l = new LinkedHashSet<StepMeta>( destinos );
    boolean cambio = true;
    while ( cambio ) {
      cambio = false;
      for ( TransHopMeta h : tm.getTransHops() ) {
        if ( h.isEnabled() && h.getFromStep() != null && h.getToStep() != null && l.contains( h.getToStep() )
          && l.add( h.getFromStep() ) ) {
          cambio = true;
        }
      }
    }
    return l;
  }

  private static boolean conectado( TransMeta tm, StepMeta s ) {
    for ( TransHopMeta h : tm.getTransHops() ) {
      if ( h.isEnabled() && h.getFromStep() != null && h.getToStep() != null
        && ( s.equals( h.getFromStep() ) || s.equals( h.getToStep() ) ) ) {
        return true;
      }
    }
    return false;
  }

  private static List<StepMeta> pasosDeTipo( TransMeta tm, List<String> tipos ) {
    Set<String> buscados = new HashSet<String>();
    if ( tipos != null ) {
      for ( String x : tipos ) {
        if ( x != null ) {
          buscados.add( x.trim().toLowerCase( Locale.ROOT ) );
        }
      }
    }
    List<StepMeta> l = new ArrayList<StepMeta>();
    for ( StepMeta s : tm.getSteps() ) {
      if ( s.getStepID() != null && buscados.contains( s.getStepID().toLowerCase( Locale.ROOT ) ) ) {
        l.add( s );
      }
    }
    return l;
  }

  // ================================================================ nota

  /**
   * Puntua el resultado con la rubrica del pack (§5) y le escribe {@code checks}, {@code grade}, {@code scale} y
   * {@code hints}; tambien {@code pack}, {@code pack_title} y {@code language} si faltaban. No cambia el estado ni los
   * casos. {@code est}: lo de {@link #analizar}, o null (por ejemplo el padre, con el parcial de un worker muerto):
   * las pistas que necesitan los tipos de steps se dan igual o se omiten. Devuelve r.
   */
  static ResultadoCorreccion puntuar( Pack pack, ResultadoCorreccion r, Estatico est ) {
    pack.completar();
    r.completar();
    r.idioma = idioma( pack, r );
    if ( r.pack == null ) {
      r.pack = pack.id;
    }
    if ( r.tituloPack == null ) {
      r.tituloPack = pack.titulo;
    }
    r.escala = pack.escala;
    Calculo c = new Calculo( pack, r );
    r.criterios = new ArrayList<Criterio>();
    double puntos = 0;
    double ganado = 0;
    for ( ItemRubrica i : pack.rubrica ) {
      if ( i == null || i.check == null ) {
        continue;
      }
      double[] exacto = new double[ 1 ];
      r.criterios.add( c.criterio( i, exacto ) );
      puntos += Math.max( 0, i.puntos );
      ganado += exacto[ 0 ];
    }
    r.nota = puntos > EPS ? redondear( ganado / puntos * pack.escala, 1 ) : 0.0;
    List<String> textos = new ArrayList<String>();
    for ( Pista p : pistas( pack, r, est ) ) {
      textos.add( p.texto );
    }
    r.pistas = textos;
    return r;
  }

  /** Los calculos de una puntuacion: puntajes crudos por check y compuertas, con memoria. */
  private static final class Calculo {

    final Pack pack;
    final ResultadoCorreccion r;
    final Rotulos t;
    final Textos tx;
    final List<Caso> casos;
    final Map<String, Double> crudos = new LinkedHashMap<String, Double>();
    final Map<String, Boolean> aprobados = new LinkedHashMap<String, Boolean>();

    Calculo( Pack pack, ResultadoCorreccion r ) {
      this.pack = pack;
      this.r = r;
      this.t = Rotulos.de( AREA, r.idioma );
      this.tx = Textos.de( r.idioma );
      this.casos = casos( pack, r );
    }

    /** El renglon con la compuerta aplicada; exacto[0] = lo ganado sin redondear. */
    Criterio criterio( ItemRubrica i, double[] exacto ) {
      double s = crudo( i.check );
      Criterio c = new Criterio( i.check, i.puntos, 0 );
      c.etiqueta = etiqueta( i );
      c.detalle = detalle( i.check );
      if ( i.esDeSalida() ) {
        c.porCaso = new LinkedHashMap<String, Double>();
        for ( Caso k : casos ) {
          c.porCaso.put( k.id, redondear( puntajeCaso( i.check, r.caso( k.id ) ), 4 ) );
        }
      }
      String falla = null;
      Set<String> pila = new HashSet<String>();
      pila.add( i.check );
      for ( String req : i.requiere == null ? Collections.<String>emptyList() : i.requiere ) {
        if ( req != null && !aprobado( req.trim(), pila ) ) {
          falla = req.trim();
          break;
        }
      }
      double g = Math.max( 0, i.puntos ) * s;
      if ( falla != null ) {
        g = 0;
        c.compuerta = falla;
        c.detalle = ( c.detalle == null || c.detalle.isEmpty() ? "" : c.detalle + " · " )
          + t.f( "detalle.compuerta", etiquetaDe( falla ) );
      }
      exacto[ 0 ] = g;
      c.ganado = redondear( g, 2 );
      c.aprobado = falla == null && s >= 1 - EPS;
      return c;
    }

    /** Aprobado con sus propias compuertas; un ciclo de requires se corta con el puntaje crudo. */
    boolean aprobado( String check, Set<String> pila ) {
      Boolean memo = aprobados.get( check );
      if ( memo != null ) {
        return memo;
      }
      boolean ok = crudo( check ) >= 1 - EPS;
      ItemRubrica i = pack.itemRubrica( check );
      if ( ok && i != null && i.requiere != null && pila.add( check ) ) {
        for ( String req : i.requiere ) {
          if ( req != null && !pila.contains( req.trim() ) && !aprobado( req.trim(), pila ) ) {
            ok = false;
            break;
          }
        }
        pila.remove( check );
      }
      aprobados.put( check, ok );
      return ok;
    }

    /** Puntaje 0..1 sin compuertas: promedio ponderado entre casos o la regla. Un check desconocido vale 0. */
    double crudo( String check ) {
      Double m = crudos.get( check );
      if ( m != null ) {
        return m;
      }
      double s = 0;
      if ( ItemRubrica.DE_SALIDA.contains( check ) ) {
        double suma = 0;
        double pesos = 0;
        for ( Caso k : casos ) {
          double w = Math.max( 0, k.peso );
          suma += w * puntajeCaso( check, r.caso( k.id ) );
          pesos += w;
        }
        if ( pesos <= EPS && !casos.isEmpty() ) { // todos con peso 0: promedio simple
          for ( Caso k : casos ) {
            suma += puntajeCaso( check, r.caso( k.id ) );
          }
          pesos = casos.size();
        }
        s = pesos > EPS ? suma / pesos : 0;
      } else {
        ReglaEvaluada re = reglaDe( check );
        s = re != null && re.evaluada && re.aprobada ? 1 : 0;
      }
      crudos.put( check, s );
      return s;
    }

    ReglaEvaluada reglaDe( String check ) {
      ItemRubrica i = new ItemRubrica( check, 0 );
      if ( i.esRegla() ) {
        return r.reglas.get( i.idRegla() );
      }
      if ( i.esLint() ) {
        ReglaEvaluada re = r.lint.get( i.idLint() );
        if ( re == null ) {
          for ( Map.Entry<String, ReglaEvaluada> e : r.lint.entrySet() ) {
            if ( normLint( e.getKey() ).equals( normLint( i.idLint() ) ) ) {
              return e.getValue();
            }
          }
        }
        return re;
      }
      return null;
    }

    /** Puntaje 0..1 de un check de salida en un caso; 0 si el caso no esta o no corrio. */
    double puntajeCaso( String check, ResultadoCaso rc ) {
      if ( rc == null ) {
        return 0;
      }
      if ( ItemRubrica.RUNS.equals( check ) ) {
        return rc.corrioBien() ? 1 : 0;
      }
      Comparacion cmp = rc.comparacion;
      if ( !corrio( rc ) || cmp == null ) {
        return 0;
      }
      if ( ItemRubrica.COLUMNS.equals( check ) ) {
        if ( OpcionesComparar.EXTRA_RESTAR.equals( pack.comparar.columnasDeMas ) && cmp.columnas != null ) {
          return puntajeColumnasRestando( cmp );
        }
        return acotar( cmp.puntajeColumnas );
      }
      if ( ItemRubrica.ROW_COUNT.equals( check ) ) {
        return cmp.cantidadFilasOk ? 1 : 0;
      }
      if ( ItemRubrica.VALUES.equals( check ) ) {
        return acotar( cmp.puntajeValores );
      }
      return 0;
    }

    /**
     * extra_columns = penalize: presentes / (esperadas + sobrantes que no son un renombre). Presentes son las que
     * estan y, sin ignore_column_order, en orden: como en CorrectorComparar, penalizar las de mas no apaga el orden.
     */
    double puntajeColumnasRestando( Comparacion cmp ) {
      Comparacion.Columnas col = cmp.columnas;
      int esperadas = col.esperadas == null ? 0 : col.esperadas.size();
      int faltan = col.faltan == null ? 0 : col.faltan.size();
      Set<String> renombradas = new HashSet<String>();
      for ( Comparacion.Renombre rn : col.renombres == null ? Collections.<Comparacion.Renombre>emptyList()
        : col.renombres ) {
        if ( rn != null && rn.real != null ) {
          renombradas.add( rn.real );
        }
      }
      int sobran = 0;
      for ( String s : col.sobran == null ? Collections.<String>emptyList() : col.sobran ) {
        if ( !renombradas.contains( s ) ) {
          sobran++;
        }
      }
      if ( esperadas <= 0 ) {
        return acotar( cmp.puntajeColumnas );
      }
      int fuera = pack.comparar.ignorarOrdenColumnas ? 0 : CorrectorComparar.fueraDeOrden( col.esperadas,
        col.reales ).size();
      return acotar( (double) ( esperadas - faltan - fuera ) / ( esperadas + sobran ) );
    }

    String etiqueta( ItemRubrica i ) {
      if ( i.etiqueta != null && !i.etiqueta.trim().isEmpty() ) {
        return i.etiqueta;
      }
      if ( i.esDeSalida() ) {
        return t.s( "check." + i.check );
      }
      if ( i.esRegla() ) {
        ReglaEvaluada re = r.reglas.get( i.idRegla() );
        if ( re != null && re.etiqueta != null ) {
          return re.etiqueta;
        }
        Regla rg = pack.regla( i.idRegla() );
        return rg != null ? etiquetaRegla( rg, t, tx ) : i.check;
      }
      if ( i.esLint() ) {
        return t.f( "check.lint", tx.t( normLint( i.idLint() ) + ".title" ) );
      }
      return i.check;
    }

    String etiquetaDe( String check ) {
      ItemRubrica i = pack.itemRubrica( check );
      return etiqueta( i != null ? i : new ItemRubrica( check, 0 ) );
    }

    String detalle( String check ) {
      if ( ItemRubrica.DE_SALIDA.contains( check ) ) {
        return detalleSalida( check );
      }
      ItemRubrica i = new ItemRubrica( check, 0 );
      if ( !i.esRegla() && !i.esLint() ) {
        return t.s( "detalle.check_desconocido" );
      }
      if ( i.esRegla() && pack.regla( i.idRegla() ) == null ) {
        return t.f( "detalle.regla_inexistente", i.idRegla() );
      }
      ReglaEvaluada re = reglaDe( check );
      if ( re == null ) {
        return t.s( "detalle.regla_sin_resultado" );
      }
      return re.evaluada ? re.detalle : t.f( "detalle.no_evaluada", re.detalle );
    }

    /** «visible 25%, oculto 0%»; un caso que no corrio dice su estado; si no corrio ninguno, por que. */
    String detalleSalida( String check ) {
      boolean alguno = false;
      for ( Caso k : casos ) {
        ResultadoCaso rc = r.caso( k.id );
        alguno |= rc != null && rc.corrida != null && rc.corrida.estado != null
          && !ResultadoCaso.BLOQUEADO.equals( rc.corrida.estado );
      }
      if ( !alguno ) {
        return t.f( "detalle.no_corrio", porQueNoCorrio() );
      }
      StringBuilder b = new StringBuilder();
      for ( Caso k : casos ) {
        ResultadoCaso rc = r.caso( k.id );
        b.append( b.length() == 0 ? "" : ", " );
        boolean bien = ItemRubrica.RUNS.equals( check ) ? rc != null && rc.corrioBien() : rc != null && corrio( rc );
        if ( bien ) {
          b.append( t.f( "detalle.caso", k.id, Math.round( puntajeCaso( check, rc ) * 100 ) ) );
        } else {
          b.append( t.f( "detalle.caso_no_corrio", k.id, estadoCaso( t, rc ) ) );
        }
      }
      return b.toString();
    }

    /** El motivo del bloqueo (de la entrega o de un caso); si no hay, el estado de la entrega o el de un caso. */
    String porQueNoCorrio() {
      String m = r.motivo;
      for ( ResultadoCaso rc : r.casos ) {
        if ( m == null && rc != null && rc.corrida != null ) {
          m = rc.corrida.motivo;
        }
      }
      if ( m != null ) {
        return texto( t, "motivo.", m );
      }
      if ( r.estado != null && !ResultadoCorreccion.CORREGIDA.equals( r.estado )
        && !ResultadoCorreccion.EN_CURSO.equals( r.estado ) ) {
        return texto( t, "estado.", r.estado );
      }
      for ( ResultadoCaso rc : r.casos ) {
        if ( rc != null && rc.corrida != null && rc.corrida.estado != null ) {
          return estadoCaso( t, rc );
        }
      }
      return t.s( "caso.not_run" );
    }
  }

  /** Los casos que cuentan: los del pack (un caso sin resultado vale 0); si el pack no trae, los del resultado. */
  static List<Caso> casos( Pack pack, ResultadoCorreccion r ) {
    List<Caso> l = new ArrayList<Caso>();
    if ( pack.casos != null ) {
      for ( Caso c : pack.casos ) {
        if ( c != null && c.id != null ) {
          l.add( c );
        }
      }
    }
    if ( l.isEmpty() && r.casos != null ) {
      for ( ResultadoCaso rc : r.casos ) {
        if ( rc != null && rc.id != null ) {
          Caso c = new Caso( rc.id, null, null, rc.oculto );
          c.peso = rc.peso;
          l.add( c );
        }
      }
    }
    return l;
  }

  /** Corrio hasta el final (con o sin errores): solo entonces columns, row_count y values miran la comparacion. */
  static boolean corrio( ResultadoCaso rc ) {
    String e = rc == null || rc.corrida == null ? null : rc.corrida.estado;
    return ResultadoCaso.TERMINO.equals( e ) || ResultadoCaso.TERMINO_CON_ERRORES.equals( e );
  }

  // ================================================================ pistas

  /**
   * Las pistas del §6 para el alumno, en el idioma del resultado y sin repetir entre casos: primero por que no corrio
   * (bloqueo, estado de los casos), despues columnas, filas, valores y formato. Miran tambien los casos ocultos (la
   * frase nunca cita sus valores: solo nombres de columnas y de steps). {@code est} null: sin los tipos de steps.
   */
  static List<Pista> pistas( Pack pack, ResultadoCorreccion r, Estatico est ) {
    pack.completar();
    r.completar();
    Rotulos t = Rotulos.de( AREA, idioma( pack, r ) );
    Map<String, Pista> out = new LinkedHashMap<String, Pista>();

    // ---- la entrega
    if ( ResultadoCorreccion.NO_CARGO.equals( r.estado ) ) {
      agregar( out, P_NO_CARGO, t.s( "pista.load_failed" ) );
    }
    Set<String> motivos = new LinkedHashSet<String>();
    if ( r.motivo != null ) {
      motivos.add( r.motivo );
    }
    for ( ResultadoCaso rc : r.casos ) {
      if ( rc != null && rc.corrida != null && ResultadoCaso.BLOQUEADO.equals( rc.corrida.estado )
        && rc.corrida.motivo != null ) {
        motivos.add( rc.corrida.motivo );
      }
    }
    for ( PasoBloqueado pb : r.stepsBloqueados ) {
      if ( pb != null && pb.motivo != null && ResultadoCorreccion.BLOQUEADA.equals( r.estado ) ) {
        motivos.add( pb.motivo );
      }
    }
    for ( String m : motivos ) {
      pistaBloqueo( out, t, pack, r, m );
    }
    if ( ResultadoCorreccion.MATADA.equals( r.estado ) ) {
      agregar( out, P_TIEMPO, t.f( "pista.timeout", pack.politica.timeoutSeg ) );
    }
    if ( ResultadoCorreccion.CAIDA.equals( r.estado ) ) {
      agregar( out, P_CAIDA, t.s( "pista.crashed" ) );
    }

    // ---- como corrio cada caso
    for ( ResultadoCaso rc : r.casos ) {
      String e = rc == null || rc.corrida == null ? null : rc.corrida.estado;
      if ( ResultadoCaso.TIEMPO_AGOTADO.equals( e ) ) {
        agregar( out, P_TIEMPO, t.f( "pista.timeout", pack.politica.timeoutSeg ) );
      } else if ( ResultadoCaso.SALIDA_DEMASIADO_GRANDE.equals( e ) ) {
        agregar( out, P_SALIDA_GRANDE, t.f( "pista.output_too_big", pack.politica.maxSalidaMb ) );
      } else if ( ResultadoCaso.DEMASIADAS_FILAS.equals( e ) ) {
        agregar( out, P_FILAS, t.f( "pista.too_many_rows", pack.politica.maxFilas ) );
      } else if ( ResultadoCaso.FALLO_INIT.equals( e ) || ResultadoCaso.FALLO_ARRANQUE.equals( e ) ) {
        agregar( out, P_INIT, t.s( "pista.init_failed" ) );
      } else if ( ResultadoCaso.TERMINO_CON_ERRORES.equals( e ) ) {
        agregar( out, P_CON_ERRORES, t.s( "pista.finished_with_errors" ) );
      } else if ( ResultadoCaso.SIN_STEP_DE_SALIDA.equals( e ) ) {
        String captura = pack.salida.captura;
        if ( Pack.Salida.CAPTURA_STEP.equals( captura ) && pack.salida.step != null ) {
          agregar( out, P_SIN_SALIDA, t.f( "pista.no_output_step.step", pack.salida.step ) );
        } else {
          agregar( out, P_SIN_SALIDA, t.s( Pack.Salida.CAPTURA_QUERY.equals( captura ) ? "pista.no_output_step.query"
            : Pack.Salida.CAPTURA_TABLAS.equals( captura ) ? "pista.no_output_step.tables"
            : "pista.no_output_step.file" ) );
        }
      }
    }

    // ---- lo que salio, comparado
    List<String> faltan = new ArrayList<String>();
    List<String> sobran = new ArrayList<String>();
    List<String[]> renombres = new ArrayList<String[]>();
    Set<String> difPorColumna = new LinkedHashSet<String>();
    boolean orden = false;
    boolean repetidas = false;
    boolean faltanFilas = false;
    boolean sobranFilas = false;
    boolean distintasNumericas = false;
    boolean x100 = false;
    boolean mojibake = false;
    boolean coma = tieneAviso( r.avisos );
    for ( ResultadoCaso rc : r.casos ) {
      if ( rc == null ) {
        continue;
      }
      coma |= tieneAviso( rc.avisos );
      Comparacion cmp = rc.comparacion;
      if ( cmp == null || !corrio( rc ) ) {
        continue;
      }
      Comparacion.Columnas col = cmp.columnas;
      Set<String> renEsperadas = new HashSet<String>();
      Set<String> renReales = new HashSet<String>();
      if ( col != null ) {
        for ( Comparacion.Renombre rn : col.renombres == null ? Collections.<Comparacion.Renombre>emptyList()
          : col.renombres ) {
          if ( rn != null && rn.esperada != null && rn.real != null ) {
            renombres.add( new String[] { rn.esperada, rn.real } );
            renEsperadas.add( rn.esperada );
            renReales.add( rn.real );
          }
        }
        for ( String c : col.faltan == null ? Collections.<String>emptyList() : col.faltan ) {
          if ( c != null && !renEsperadas.contains( c ) && !faltan.contains( c ) ) {
            faltan.add( c );
          }
        }
        for ( String c : col.sobran == null ? Collections.<String>emptyList() : col.sobran ) {
          if ( c != null && !renReales.contains( c ) && !sobran.contains( c ) ) {
            sobran.add( c );
          }
        }
        orden |= col.ordenDistinto && !pack.comparar.ignorarOrdenColumnas;
        for ( String c : col.reales == null ? Collections.<String>emptyList() : col.reales ) {
          mojibake |= c != null && MOJIBAKE.matcher( c ).find();
        }
      }
      Comparacion.Filas f = cmp.filas;
      if ( f != null ) {
        repetidas |= f.clavesRepetidas > 0;
        faltanFilas |= f.faltan > 0;
        sobranFilas |= f.sobran > 0;
      }
      if ( cmp.diferenciasPorColumna != null ) {
        for ( Map.Entry<String, Integer> d : cmp.diferenciasPorColumna.entrySet() ) {
          if ( d.getKey() != null && d.getValue() != null && d.getValue() > 0 ) {
            difPorColumna.add( d.getKey() );
            Pack.Columna pc = pack.salida.columna( d.getKey() );
            distintasNumericas |= pc != null && pc.esNumerica();
          }
        }
      }
      Comparacion.Ejemplos ej = cmp.ejemplos;
      if ( ej != null ) {
        for ( Comparacion.Ejemplo e : nn( ej.distintas ) ) {
          Pack.Columna pc = pack.salida.columna( e.columna );
          double a = numero( e.real );
          double b = numero( e.esperado );
          boolean numericos = !Double.isNaN( a ) && !Double.isNaN( b );
          distintasNumericas |= numericos && ( pc == null || pc.esNumerica() );
          if ( numericos && ( pc == null || pc.esNumerica() ) && Math.abs( b ) > EPS ) {
            double tol = Math.max( pack.comparar.toleranciaNumerica, 0.005 ) * 100;
            x100 |= Math.abs( a - b * 100 ) <= Math.max( tol, Math.abs( b * 100 ) * 1e-6 );
          }
          mojibake |= rota( e.real ) || rota( e.clave );
        }
        for ( Comparacion.Ejemplo e : nn( ej.sobran ) ) {
          mojibake |= rota( e.clave ) || ( e.fila != null && rota( e.fila.values() ) );
        }
      }
    }
    int n = 0;
    for ( String[] rn : renombres ) {
      if ( n++ < MAX_PISTAS_COLUMNA ) {
        agregar( out, P_RENOMBRE, t.f( "pista.rename", rn[ 0 ], rn[ 1 ] ) );
      }
    }
    for ( int i = 0; i < faltan.size() && i < MAX_PISTAS_COLUMNA; i++ ) {
      agregar( out, P_FALTA_COLUMNA, t.f( "pista.missing_column", faltan.get( i ) ) );
    }
    boolean restaExtras = OpcionesComparar.EXTRA_RESTAR.equals( pack.comparar.columnasDeMas );
    for ( int i = 0; i < sobran.size() && i < MAX_PISTAS_COLUMNA; i++ ) {
      agregar( out, P_SOBRA_COLUMNA, t.f( restaExtras ? "pista.extra_column.penalize" : "pista.extra_column",
        sobran.get( i ) ) );
    }
    if ( orden ) {
      agregar( out, P_ORDEN_COLUMNAS, t.s( "pista.column_order" ) );
    }
    if ( repetidas ) {
      boolean groupBy = est == null || interseca( est.tipos, GROUP_BY ) || est.lint.contains( "unsorted_input" );
      if ( groupBy ) {
        agregar( out, P_D5, t.s( "pista.D5" ) );
      } else {
        agregar( out, P_CLAVES_REPETIDAS, t.f( "pista.duplicate_keys", lista( pack.comparar.columnasClave ) ) );
      }
    }
    boolean conMerge = est != null && interseca( est.tipos, MERGES );
    if ( conMerge && ( faltanFilas || sobranFilas || !difPorColumna.isEmpty() ) ) {
      agregar( out, P_D6, t.s( "pista.D6" ) );
    }
    if ( faltanFilas ) {
      agregar( out, P_FALTAN_FILAS, t.s( "pista.missing_rows" ) );
    }
    if ( sobranFilas ) {
      agregar( out, P_SOBRAN_FILAS, t.s( "pista.extra_rows" ) );
    }
    boolean patron = false;
    if ( x100 ) {
      agregar( out, P_D3, t.s( "pista.D3" ) );
      patron = true;
    }
    if ( est != null && est.lint.contains( "calculator_integer_first" ) && distintasNumericas ) {
      agregar( out, P_D4, t.s( "pista.D4" ) );
      patron = true;
    }
    if ( !patron ) {
      int k = 0;
      for ( String c : difPorColumna ) {
        if ( k++ < MAX_PISTAS_COLUMNA ) {
          agregar( out, P_VALORES, t.f( "pista.values", c ) );
        }
      }
    }
    if ( coma ) {
      agregar( out, P_COMA, t.s( pack.comparar.formatoEstricto ? "pista.comma_decimal.strict"
        : "pista.comma_decimal" ) );
    }
    if ( mojibake ) {
      agregar( out, P_ENCODING, t.s( "pista.encoding" ) );
    }
    return new ArrayList<Pista>( out.values() );
  }

  private static void pistaBloqueo( Map<String, Pista> out, Rotulos t, Pack pack, ResultadoCorreccion r,
                                    String motivo ) {
    if ( ResultadoCaso.STEP_NO_PERMITIDO.equals( motivo ) || ResultadoCaso.STEP_PELIGROSO.equals( motivo )
      || ResultadoCaso.STEP_DESCONOCIDO.equals( motivo ) ) {
      List<String> steps = new ArrayList<String>();
      for ( PasoBloqueado pb : r.stepsBloqueados ) {
        if ( pb != null && pb.step != null && ( pb.motivo == null || motivo.equals( pb.motivo ) ) ) {
          steps.add( "«" + pb.step + "»" + ( pb.tipo == null ? "" : " (" + pb.tipo + ")" ) );
        }
      }
      String clave = ResultadoCaso.STEP_PELIGROSO.equals( motivo ) ? "pista.blocked_step.dangerous"
        : ResultadoCaso.STEP_DESCONOCIDO.equals( motivo ) ? "pista.blocked_step.unknown" : "pista.blocked_step";
      agregar( out, P_STEP_BLOQUEADO, steps.isEmpty() ? t.s( "pista.blocked_step.generico" )
        : t.f( clave, unir( steps ) ) );
    } else if ( ResultadoCaso.ENTRADA_INESPERADA.equals( motivo ) ) {
      agregar( out, P_LEE_FUERA, pack.entradas.isEmpty() ? t.s( "pista.read_outside_pack.generico" )
        : t.f( "pista.read_outside_pack", unir( pack.entradas ) ) );
    } else if ( ResultadoCaso.SALIDA_INESPERADA.equals( motivo ) ) {
      agregar( out, P_SALIDA_EN_CAMPO, t.s( "pista.unexpected_output" ) );
    } else if ( ResultadoCaso.CONEXION_AJENA.equals( motivo ) ) {
      if ( pack.bases.isEmpty() ) {
        agregar( out, P_CONEXION, t.s( "pista.foreign_connection" ) );
      } else {
        List<String> nombres = new ArrayList<String>();
        for ( io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba b : pack.bases ) {
          nombres.add( "«" + b.conexion + "»" );
        }
        agregar( out, P_CONEXION, t.f( "pista.foreign_connection.test", unir( nombres ) ) );
      }
    } else if ( ResultadoCaso.ESQUEMA_AJENO.equals( motivo ) ) {
      agregar( out, P_ESQUEMA, t.s( "pista.foreign_schema" ) );
    } else if ( ResultadoCaso.SQL_PROHIBIDO.equals( motivo ) ) {
      agregar( out, P_SQL, t.s( pack.bases.isEmpty() ? "pista.forbidden_sql" : "pista.forbidden_sql.server" ) );
    } else if ( ResultadoCaso.REESCRITURA_FALLIDA.equals( motivo ) ) {
      agregar( out, P_REESCRITURA, t.s( "pista.rewrite_failed" ) );
    } else if ( ResultadoCaso.NOMBRE_REPETIDO.equals( motivo ) ) {
      agregar( out, P_D1, t.s( "pista.D1" ) );
    } else if ( ResultadoCaso.CICLO.equals( motivo ) ) {
      agregar( out, P_D2, t.s( "pista.D2" ) );
    }
  }

  /** Sin repetir la misma frase (dos casos que fallan igual dan una sola pista). */
  private static void agregar( Map<String, Pista> out, String id, String texto ) {
    if ( texto != null && !out.containsKey( texto ) ) {
      out.put( texto, new Pista( id, texto ) );
    }
  }

  private static boolean tieneAviso( List<Aviso> avisos ) {
    if ( avisos != null ) {
      for ( Aviso a : avisos ) {
        if ( a != null && Aviso.COMA_DECIMAL.equals( a.codigo ) ) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean rota( String s ) {
    return s != null && MOJIBAKE.matcher( s ).find();
  }

  private static boolean rota( Collection<String> l ) {
    for ( String s : l ) {
      if ( rota( s ) ) {
        return true;
      }
    }
    return false;
  }

  /** Un numero escrito por la solucion o por el alumno («2000.00», «2000,00», « 7.5 »); NaN si no es. */
  static double numero( String s ) {
    if ( s == null ) {
      return Double.NaN;
    }
    String x = s.trim().replace( " ", "" );
    if ( x.isEmpty() ) {
      return Double.NaN;
    }
    if ( x.indexOf( ',' ) >= 0 && x.indexOf( '.' ) < 0 ) {
      x = x.replace( ',', '.' );
    }
    try {
      return Double.parseDouble( x );
    } catch ( NumberFormatException e ) {
      return Double.NaN;
    }
  }

  // ================================================================ .md minimo

  /**
   * La devolucion en Markdown, minima (la de verdad la dibuja CorreccionAInforme, C9): nota, criterios, pistas, steps
   * bloqueados, cada caso con sus conteos y ejemplos, y las notas del corrector. {@code alumno}: la version del alumno
   * ({@link ResultadoCorreccion#paraAlumno()}: los ocultos sin ejemplos, detalle ni log). Nunca lleva el log. Todo
   * texto que viene de la entrega se escapa.
   */
  static String markdown( ResultadoCorreccion original, boolean alumno ) {
    ResultadoCorreccion r = alumno ? original.paraAlumno() : CorrectorJson.copia( original,
      ResultadoCorreccion.class );
    r.completar();
    String idioma = Rotulos.normalizar( r.idioma );
    Rotulos t = Rotulos.de( AREA, idioma );
    Locale loc = Rotulos.locale( idioma );
    StringBuilder b = new StringBuilder();
    b.append( "# " ).append( md( t.s( "md.titulo" ) ) );
    if ( r.tituloPack != null || r.pack != null ) {
      b.append( ": " ).append( md( r.tituloPack != null ? r.tituloPack : r.pack ) );
    }
    b.append( "\n\n" );
    if ( r.alumno != null ) {
      b.append( "**" ).append( md( t.s( "md.alumno" ) ) ).append( ":** " ).append( md( r.alumno ) ).append( "  \n" );
    }
    if ( r.archivo != null ) {
      b.append( "**" ).append( md( t.s( "md.archivo" ) ) ).append( ":** " ).append( md( r.archivo ) ).append( "  \n" );
    }
    b.append( "**" ).append( md( t.s( "md.nota" ) ) ).append( ": " )
      .append( r.nota == null ? "-" : String.format( loc, "%.1f", r.nota ) ).append( " / " )
      .append( puntos( r.escala, loc ) ).append( "**  \n" );
    b.append( "**" ).append( md( t.s( "md.estado" ) ) ).append( ":** " )
      .append( md( texto( t, "estado.", r.estado ) ) );
    if ( r.motivo != null ) {
      b.append( " (" ).append( md( texto( t, "motivo.", r.motivo ) ) ).append( ")" );
    }
    b.append( "\n\n" );
    if ( r.modoConfianza ) {
      b.append( "> " ).append( md( t.s( "md.modo_confianza" ) ) ).append( "\n\n" );
    }

    if ( !r.criterios.isEmpty() ) {
      b.append( "## " ).append( md( t.s( "md.criterios" ) ) ).append( "\n\n" );
      fila( b, t.s( "md.criterio" ), t.s( "md.puntos" ), t.s( "md.detalle" ) );
      b.append( "|---|---:|---|\n" );
      for ( Criterio c : r.criterios ) {
        if ( c == null ) {
          continue;
        }
        b.append( "| " ).append( c.aprobado ? "✔ " : "✘ " )
          .append( md( c.etiqueta != null ? c.etiqueta : c.check ) ).append( " | " )
          .append( puntos( c.ganado, loc ) ).append( " / " ).append( puntos( c.puntos, loc ) ).append( " | " )
          .append( md( c.detalle ) ).append( " |\n" );
      }
      b.append( "\n" );
    }

    if ( !r.pistas.isEmpty() ) {
      b.append( "## " ).append( md( t.s( "md.pistas" ) ) ).append( "\n\n" );
      for ( String p : r.pistas ) {
        b.append( "- " ).append( md( p ) ).append( "\n" );
      }
      b.append( "\n" );
    }

    if ( !r.stepsBloqueados.isEmpty() ) {
      b.append( "## " ).append( md( t.s( "md.bloqueados" ) ) ).append( "\n\n" );
      fila( b, t.s( "md.col.step" ), t.s( "md.col.tipo" ), t.s( "md.col.nivel" ), t.s( "md.col.motivo" ) );
      b.append( "|---|---|---|---|\n" );
      for ( PasoBloqueado pb : r.stepsBloqueados ) {
        if ( pb != null ) {
          fila( b, pb.step, pb.tipo, pb.nivel, pb.motivo == null ? null : texto( t, "motivo.", pb.motivo ) );
        }
      }
      b.append( "\n" );
    }

    if ( !r.casos.isEmpty() ) {
      b.append( "## " ).append( md( t.s( "md.casos" ) ) ).append( "\n\n" );
      for ( ResultadoCaso rc : r.casos ) {
        if ( rc != null ) {
          caso( b, t, loc, rc, alumno );
        }
      }
    }

    if ( !r.avisos.isEmpty() ) {
      b.append( "## " ).append( md( t.s( "md.avisos" ) ) ).append( "\n\n" );
      for ( Aviso a : r.avisos ) {
        if ( a != null ) {
          b.append( "- " ).append( md( a.detalle != null ? CorrectorTextos.detalle( t, a ) : a.codigo ) ).append(
            "\n" );
        }
      }
      b.append( "\n" );
    }
    return b.toString();
  }

  private static void caso( StringBuilder b, Rotulos t, Locale loc, ResultadoCaso rc, boolean alumno ) {
    b.append( "### " ).append( md( t.f( "md.caso", rc.id ) ) );
    if ( rc.oculto ) {
      b.append( " (" ).append( md( t.s( "md.oculto" ) ) ).append( ")" );
    }
    b.append( "\n\n" );
    ResultadoCaso.Corrida run = rc.corrida;
    StringBuilder linea = new StringBuilder( estadoCaso( t, rc ) );
    if ( run != null && run.estado != null && run.motivo != null ) {
      linea.append( " (" ).append( texto( t, "motivo.", run.motivo ) ).append( ")" );
    }
    if ( run != null && run.segundos != null ) {
      linea.append( " · " ).append( t.f( "md.segundos", run.segundos ) );
    }
    if ( run != null && run.errores != null && run.errores > 0 ) {
      linea.append( " · " ).append( t.f( "md.errores", run.errores ) );
    }
    if ( run != null && run.capturadoDe != null ) {
      linea.append( " · " ).append( t.f( "md.capturado", run.capturadoDe ) );
    }
    b.append( "- **" ).append( md( t.s( "md.corrida" ) ) ).append( ":** " ).append( md( linea.toString() ) )
      .append( "\n" );
    if ( run != null && run.detalle != null && !alumno ) {
      b.append( "- " ).append( md( CorrectorTextos.detalle( t, run ) ) ).append( "\n" );
    }
    Comparacion cmp = rc.comparacion;
    if ( cmp != null ) {
      Comparacion.Filas f = cmp.filas == null ? new Comparacion.Filas() : cmp.filas;
      b.append( "- " ).append( md( t.f( "md.filas", f.esperadas, f.reales, f.iguales, f.distintas, f.faltan, f.sobran,
        f.clavesRepetidas ) ) ).append( "\n" );
      Comparacion.Columnas col = cmp.columnas;
      if ( col != null ) {
        listaMd( b, t.s( "md.faltan_columnas" ), col.faltan );
        listaMd( b, t.s( "md.sobran_columnas" ), col.sobran );
        List<String> ren = new ArrayList<String>();
        for ( Comparacion.Renombre rn : col.renombres == null ? Collections.<Comparacion.Renombre>emptyList()
          : col.renombres ) {
          if ( rn != null ) {
            ren.add( rn.esperada + " → " + rn.real );
          }
        }
        listaMd( b, t.s( "md.renombres" ), ren );
      }
    }
    if ( !rc.redirecciones.isEmpty() && !( alumno && rc.oculto ) ) {
      listaMd( b, t.s( "md.redirecciones" ), rc.redirecciones );
    }
    for ( Aviso a : rc.avisos ) {
      if ( a != null ) {
        b.append( "- " ).append( md( a.detalle != null ? CorrectorTextos.detalle( t, a ) : a.codigo ) ).append( "\n" );
      }
    }
    b.append( "\n" );
    Comparacion.Ejemplos ej = cmp == null ? null : cmp.ejemplos;
    if ( ej == null || ( alumno && rc.oculto ) ) {
      if ( rc.oculto && cmp != null ) {
        b.append( "_" ).append( md( t.s( "md.solo_conteos" ) ) ).append( "_\n\n" );
      }
      return;
    }
    if ( !nn( ej.distintas ).isEmpty() ) {
      b.append( "**" ).append( md( t.s( "md.ejemplos.different" ) ) ).append( "**\n\n" );
      fila( b, t.s( "md.col.clave" ), t.s( "md.col.columna" ), t.s( "md.col.esperado" ), t.s( "md.col.tuyo" ) );
      b.append( "|---|---|---|---|\n" );
      for ( Comparacion.Ejemplo e : nn( ej.distintas ) ) {
        fila( b, e.clave, e.columna, e.esperado, e.real );
      }
      b.append( "\n" );
    }
    ejemplosDeFila( b, t, t.s( "md.ejemplos.missing" ), nn( ej.faltan ) );
    ejemplosDeFila( b, t, t.s( "md.ejemplos.extra" ), nn( ej.sobran ) );
    if ( !nn( ej.clavesRepetidas ).isEmpty() ) {
      b.append( "**" ).append( md( t.s( "md.ejemplos.duplicate_keys" ) ) ).append( "**\n\n" );
      fila( b, t.s( "md.col.clave" ), t.s( "md.col.veces" ) );
      b.append( "|---|---:|\n" );
      for ( Comparacion.Ejemplo e : nn( ej.clavesRepetidas ) ) {
        fila( b, e.clave, e.veces == null ? "" : String.valueOf( e.veces ) );
      }
      b.append( "\n" );
    }
  }

  private static void ejemplosDeFila( StringBuilder b, Rotulos t, String titulo, List<Comparacion.Ejemplo> l ) {
    if ( l.isEmpty() ) {
      return;
    }
    b.append( "**" ).append( md( titulo ) ).append( "**\n\n" );
    for ( Comparacion.Ejemplo e : l ) {
      String v = e.clave;
      if ( v == null && e.fila != null ) {
        StringBuilder s = new StringBuilder();
        for ( Map.Entry<String, String> c : e.fila.entrySet() ) {
          s.append( s.length() == 0 ? "" : ", " ).append( c.getKey() ).append( "=" ).append( c.getValue() );
        }
        v = s.toString();
      }
      b.append( "- " ).append( md( v ) ).append( "\n" );
    }
    b.append( "\n" );
  }

  private static void listaMd( StringBuilder b, String titulo, List<String> l ) {
    if ( l == null || l.isEmpty() ) {
      return;
    }
    StringBuilder s = new StringBuilder();
    for ( String x : l ) {
      s.append( s.length() == 0 ? "" : ", " ).append( md( x ) );
    }
    b.append( "- **" ).append( md( titulo ) ).append( ":** " ).append( s ).append( "\n" );
  }

  private static void fila( StringBuilder b, String... celdas ) {
    b.append( "|" );
    for ( String c : celdas ) {
      b.append( " " ).append( md( c ) ).append( " |" );
    }
    b.append( "\n" );
  }

  /**
   * Texto para Markdown: entidades para &amp; &lt; &gt; (nada de HTML crudo), barra invertida delante de lo que arma
   * formato (* _ ` [ ] | # ! ~) y los saltos de linea como espacio (no rompen una fila de tabla).
   */
  static String md( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder b = new StringBuilder( s.length() + 8 );
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      switch ( c ) {
        case '&':
          b.append( "&amp;" );
          break;
        case '<':
          b.append( "&lt;" );
          break;
        case '>':
          b.append( "&gt;" );
          break;
        case '\r':
        case '\n':
        case '\t':
          b.append( ' ' );
          break;
        case '\\':
        case '`':
        case '*':
        case '_':
        case '[':
        case ']':
        case '|':
        case '#':
        case '!':
        case '~':
          b.append( '\\' ).append( c );
          break;
        default:
          b.append( c );
      }
    }
    return b.toString();
  }

  // ================================================================ utilidades

  static String idioma( Pack pack, ResultadoCorreccion r ) {
    return Rotulos.normalizar( r != null && r.idioma != null ? r.idioma : pack == null ? null : pack.idioma );
  }

  /** Redondeo comercial a n decimales, sin el ruido del double (9,6666 -&gt; 9,7; 7,25 -&gt; 7,3). */
  static double redondear( double x, int decimales ) {
    if ( Double.isNaN( x ) || Double.isInfinite( x ) ) {
      return 0;
    }
    return BigDecimal.valueOf( x ).setScale( 6, RoundingMode.HALF_UP ).setScale( decimales, RoundingMode.HALF_UP )
      .doubleValue();
  }

  private static double acotar( double x ) {
    return Double.isNaN( x ) ? 0 : Math.max( 0, Math.min( 1, x ) );
  }

  /** «terminó», «bloqueado»...; «no corrió» si no hay estado. */
  static String estadoCaso( Rotulos t, ResultadoCaso rc ) {
    String e = rc == null || rc.corrida == null ? null : rc.corrida.estado;
    return e == null ? t.s( "caso.not_run" ) : texto( t, "caso.", e );
  }

  /** El texto de prefijo + codigo, o el codigo si no hay texto para el. */
  private static String texto( Rotulos t, String prefijo, String codigo ) {
    if ( codigo == null ) {
      return "";
    }
    return t.tiene( prefijo + codigo ) ? t.s( prefijo + codigo ) : codigo;
  }

  private static String etiquetaRegla( Regla rg, Rotulos t, Textos tx ) {
    if ( rg.etiqueta != null && !rg.etiqueta.trim().isEmpty() ) {
      return rg.etiqueta;
    }
    String tipo = rg.tipo == null ? "" : rg.tipo;
    if ( Regla.DEBE_USAR.equals( tipo ) ) {
      return t.f( "regla.must_use", lista( rg.steps ) );
    }
    if ( Regla.NO_DEBE_USAR.equals( tipo ) ) {
      return t.f( "regla.must_not_use", lista( rg.steps ) );
    }
    if ( Regla.MAX_STEPS.equals( tipo ) ) {
      return t.f( "regla.max_steps", rg.valor == null ? 0 : rg.valor );
    }
    if ( Regla.MANEJO_ERRORES.equals( tipo ) ) {
      return t.f( "regla.error_handling", lista( rg.steps ) );
    }
    if ( Regla.LINT.equals( tipo ) ) {
      return t.f( "regla.lint", tx.t( normLint( rg.reglaLint ) + ".title" ) );
    }
    return rg.id;
  }

  private static ReglaEvaluada noEvaluada( String etiqueta, String detalle ) {
    ReglaEvaluada re = new ReglaEvaluada( etiqueta, false, detalle );
    re.evaluada = false;
    return re;
  }

  /** «lint:Unsorted_Input » -&gt; «unsorted_input». */
  static String normLint( String id ) {
    String x = id == null ? "" : id.trim().toLowerCase( Locale.ROOT );
    return x.startsWith( ItemRubrica.PREFIJO_LINT ) ? x.substring( ItemRubrica.PREFIJO_LINT.length() ).trim() : x;
  }

  private static String nombres( Collection<StepMeta> l ) {
    List<String> n = new ArrayList<String>();
    for ( StepMeta s : l ) {
      n.add( "«" + s.getName() + "»" );
    }
    return unir( n );
  }

  private static String nombresConTipo( Collection<StepMeta> l ) {
    List<String> n = new ArrayList<String>();
    for ( StepMeta s : l ) {
      n.add( "«" + s.getName() + "» (" + s.getStepID() + ")" );
    }
    return unir( n );
  }

  private static String lista( List<String> l ) {
    return unir( l == null ? Collections.<String>emptyList() : l );
  }

  private static String unir( Collection<String> l ) {
    StringBuilder b = new StringBuilder();
    for ( String s : l ) {
      if ( s != null ) {
        b.append( b.length() == 0 ? "" : ", " ).append( s );
      }
    }
    return b.toString();
  }

  private static String puntos( double x, Locale loc ) {
    DecimalFormat f = new DecimalFormat( "0.##", DecimalFormatSymbols.getInstance( loc ) );
    return f.format( x );
  }

  private static boolean interseca( Set<String> a, Set<String> b ) {
    for ( String x : a ) {
      if ( b.contains( x ) ) {
        return true;
      }
    }
    return false;
  }

  private static <T> List<T> nn( List<T> l ) {
    if ( l == null ) {
      return Collections.emptyList();
    }
    List<T> r = new ArrayList<T>();
    for ( T x : l ) {
      if ( x != null ) {
        r.add( x );
      }
    }
    return r;
  }
}
