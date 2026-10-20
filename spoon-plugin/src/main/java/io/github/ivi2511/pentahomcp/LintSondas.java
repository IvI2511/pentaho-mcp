package io.github.ivi2511.pentahomcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.NoEvaluado;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Profundo;
import io.github.ivi2511.pentahomcp.lint.Sev;
import io.github.ivi2511.pentahomcp.lint.Sondas;
import io.github.ivi2511.pentahomcp.lint.Textos;

/**
 * El modo profundo del linter (docs/linter-diseno.md §3.3, WP3): sondas que LEEN las fuentes, sobre copias recortadas
 * de la copia del análisis, para confirmar o descartar lo que el estático no puede saber.
 * <ul>
 * <li><b>Orden</b> (unsorted_input): las filas que llegan al consumidor desde esa entrada (hasta 5000), comparando las
 * claves de filas consecutivas con ValueMeta.compare: que cada grupo venga junto (Group by, Unique rows, Denormaliser,
 * Analytic query) y, si el consumidor compara (Merge join, Merge rows, Sorted merge), en orden ascendente (o el de
 * Sorted merge). Ordenadas, leídas enteras y con un orden que no cambia entre corridas → info. Desordenadas → la
 * prueba diferencial.</li>
 * <li><b>Diferencial</b>: el consumidor tal cual y en otra copia con un Sort rows delante de cada entrada marcada (el
 * mismo insert_sort de apply_lint_fixes); compara las filas que salen como multiconjunto. Distintas → error; iguales →
 * queda warning («anda por casualidad»). Con varias entradas (o un step con copias) no hay una entrada que mirar: va
 * directo a la diferencial.</li>
 * <li><b>Decimal</b> (empty_decimal_symbol): el step con ese campo como String, o sea la columna como la parte el
 * parser del propio step (ancho fijo, comillas, varios archivos), hasta 2000 filas; cuenta los valores con '.' o ','.
 * Con separadores → error, aunque en esta JVM se lean bien (depende del idioma de la máquina que lo corra; además se
 * dice cómo lo lee esta). Todos enteros y la columna leída entera → info. Ningún valor → no evaluado.</li>
 * </ul>
 * Seguridad: cada copia se recorta al step que se mira (RunMotor.recortar) y corre solo si TODOS los steps que quedan
 * están en la lista blanca de solo lectura (Entorno.soloLectura). Presupuesto: 30 s por sonda (la diferencial, 30 s
 * entre sus dos corridas) y op.deepBudgetMs en total; lo que no entra va a not_evaluated y el hallazgo queda como lo
 * dejó el estático. Todo en el hilo de trabajo del lint, nunca en el de UI.
 */
final class LintSondas implements Sondas {

  static final int MAX_ORDEN = 5000;
  static final int MAX_DECIMAL = 2000;
  static final long MS_SONDA = 30000;
  /** Salida distinta en cada corrida: con uno de estos en el camino, la diferencial no prueba nada. */
  static final Set<String> NO_DETERMINISTAS = new HashSet<String>( Arrays.asList( "RandomValue", "SystemInfo",
    "ReservoirSampling" ) );
  /** Causas de ReglasOrden en las que el orden de las filas puede cambiar de una corrida a otra. */
  static final Set<String> SIN_ORDEN_FIJO = new HashSet<String>( Arrays.asList( "copies", "merges", "multi" ) );
  private static final Pattern DIGITO = Pattern.compile( "\\d" );
  private static final int MAX_VALOR = 40;

  @Override
  public void sondear( TransMeta copia, List<Hallazgo> hallazgos, Opciones op, Textos tx, Profundo prof,
                       List<NoEvaluado> noEvaluados ) {
    new Corrida( copia, op, tx, prof, noEvaluados ).correr( hallazgos );
  }

  /** Una pasada del modo profundo: el presupuesto, los textos y lo que se va anotando. */
  static final class Corrida {
    final TransMeta base;
    final Opciones op;
    final Textos tx;
    final Profundo prof;
    final List<NoEvaluado> ne;
    final long inicio;
    final long fin;

    Corrida( TransMeta base, Opciones op, Textos tx, Profundo prof, List<NoEvaluado> ne ) {
      this.base = base;
      this.op = op;
      this.tx = tx;
      this.prof = prof;
      this.ne = ne;
      this.inicio = System.currentTimeMillis();
      this.fin = inicio + Math.max( 0, op.deepBudgetMs );
    }

    void correr( List<Hallazgo> hs ) {
      prof.ran = true;
      prof.budgetMs = op.deepBudgetMs;
      Map<String, List<Hallazgo>> porConsumidor = new LinkedHashMap<String, List<Hallazgo>>();
      List<Hallazgo> decimales = new ArrayList<Hallazgo>();
      for ( Hallazgo h : hs ) {
        if ( h.step == null ) {
          continue;
        }
        if ( "unsorted_input".equals( h.rule ) ) {
          List<Hallazgo> g = porConsumidor.get( h.step );
          if ( g == null ) {
            g = new ArrayList<Hallazgo>();
            porConsumidor.put( h.step, g );
          }
          g.add( h );
        } else if ( "empty_decimal_symbol".equals( h.rule ) ) {
          decimales.add( h );
        }
      }
      for ( Map.Entry<String, List<Hallazgo>> e : porConsumidor.entrySet() ) {
        orden( e.getKey(), e.getValue() );
      }
      for ( Hallazgo h : decimales ) {
        decimal( h );
      }
      prof.ms = System.currentTimeMillis() - inicio;
    }

    // ---------------------------------------------------------------- presupuesto, copias y lista blanca

    boolean cancelado() {
      return op.entorno.cancelado() || Thread.currentThread().isInterrupted();
    }

    /** null si queda tiempo para otra sonda; si no, el código del no evaluado. */
    String sinTiempo() {
      if ( cancelado() ) {
        return "deep_cancelled";
      }
      return fin - System.currentTimeMillis() <= 0 ? "deep_budget" : null;
    }

    long plazo() {
      return Math.max( 1, Math.min( MS_SONDA, fin - System.currentTimeMillis() ) );
    }

    TransMeta clon() {
      TransMeta c = (TransMeta) ReportTools.clonar( base );
      c.setRepository( base.getRepository() );
      c.setMetaStore( base.getMetaStore() );
      return c;
    }

    /** null si todo lo que quedó en la copia recortada es de solo lectura; si no, por qué no se corre. */
    String bloqueo( TransMeta c ) {
      for ( StepMeta s : c.getSteps() ) {
        if ( !op.entorno.soloLectura( s ) ) {
          return tx.t( "deep.ne.not_read_only", "at", s.getName(), "type", s.getStepID() );
        }
      }
      return null;
    }

    RunMotor.Filas correr( TransMeta c, String step, int max, long ms ) throws Exception {
      prof.probes++;
      return RunMotor.filas( c, step, max, ms, op.entorno::cancelado );
    }

    /** Por qué una corrida no sirve (o no entera). */
    String porQue( RunMotor.Filas f ) {
      if ( f.errores > 0 || "init_failed".equals( f.estado ) ) {
        return tx.t( "deep.why.failed", "detail", f.error == null ? "?" : f.error );
      }
      if ( "row_limit".equals( f.estado ) ) {
        return tx.t( "deep.why.row_limit", "n", f.filas.size() );
      }
      if ( "timeout".equals( f.estado ) ) {
        return tx.t( "deep.why.timeout", "s", Math.max( 1, f.ms / 1000 ) );
      }
      if ( "cancelled".equals( f.estado ) ) {
        return tx.t( "deep.why.cancelled" );
      }
      return f.estado;
    }

    String codigo( RunMotor.Filas f ) {
      if ( "cancelled".equals( f.estado ) ) {
        return "deep_cancelled";
      }
      if ( "timeout".equals( f.estado ) && fin - System.currentTimeMillis() <= 0 ) {
        return "deep_budget";
      }
      return "deep_failed";
    }

    // ---------------------------------------------------------------- resultados

    /** La sonda no se pudo correr: el hallazgo queda como lo dejó el estático y el motivo va a not_evaluated. */
    void noEvaluado( Hallazgo h, String code, String motivo ) {
      h.deep = Profundo.NOT_EVALUATED;
      h.evidencia( "deep_reason", motivo );
      NoEvaluado n = new NoEvaluado();
      n.rule = h.rule;
      n.step = h.step;
      n.code = code;
      n.reason = motivo;
      ne.add( n );
      prof.notEvaluated++;
    }

    void sinTiempo( Hallazgo h, String code ) {
      noEvaluado( h, code, "deep_budget".equals( code ) ? tx.t( "deep.ne.budget", "s", op.deepBudgetMs / 1000 )
        : tx.t( "deep.ne.cancelled" ) );
    }

    /** Lo que dijo la sonda: severidad (sube, baja o queda), Hallazgo.deep y una frase al final del mensaje. */
    void resultado( Hallazgo h, Sev sev, String deep, String texto ) {
      if ( sev != h.severity ) {
        if ( sev.almenos( h.severity ) ) {
          prof.raised++;
        } else {
          prof.lowered++;
        }
        h.evidencia( "static_severity", h.severity.codigo() );
        h.severity = sev;
        h.env = true; // ahora depende de los datos que hay en esta máquina (§2)
      } else {
        prof.unchanged++;
      }
      h.deep = deep;
      h.message = h.message + " " + tx.t( "deep.prefix" ) + " " + texto;
    }

    // ---------------------------------------------------------------- orden

    /** Los hallazgos de unsorted_input de un consumidor: la muestra de cada entrada y, si hace falta, una diferencial. */
    void orden( String consumidor, List<Hallazgo> grupo ) {
      StepMeta cons = base.findStep( consumidor );
      if ( cons == null ) {
        for ( Hallazgo h : grupo ) {
          noEvaluado( h, "deep_failed", tx.t( "deep.ne.no_step", "at", consumidor ) );
        }
        return;
      }
      boolean comparan = LintArreglos.COMPARAN.contains( cons.getStepID() );
      List<Hallazgo> aComparar = new ArrayList<Hallazgo>();
      for ( Hallazgo h : grupo ) {
        String causa = h.evidence.get( "cause" );
        if ( "data".equals( causa ) ) {
          continue; // el Data grid ya lo probó: es error desde el estático
        }
        String falta = sinTiempo();
        if ( falta != null ) {
          sinTiempo( h, falta );
          continue;
        }
        String input = h.evidence.get( "input" );
        if ( input == null || "multi".equals( causa ) ) {
          aComparar.add( h ); // varias entradas que se mezclan: no hay una sola que mirar
          continue;
        }
        try {
          muestra( h, cons, input, comparan, causa, aComparar );
        } catch ( Throwable e ) {
          noEvaluado( h, "deep_failed", tx.t( "deep.ne.failed", "detail", RunMotor.primeraLinea( e ) ) );
        }
      }
      if ( aComparar.isEmpty() ) {
        return;
      }
      Diferencial d;
      String falta = sinTiempo();
      if ( falta != null ) {
        d = Diferencial.fallo( falta, "deep_budget".equals( falta ) ? tx.t( "deep.ne.budget", "s", op.deepBudgetMs / 1000 )
          : tx.t( "deep.ne.cancelled" ) );
      } else {
        try {
          d = diferencial( cons, grupo );
        } catch ( Throwable e ) {
          d = Diferencial.fallo( "deep_failed", tx.t( "deep.why.failed", "detail", RunMotor.primeraLinea( e ) ) );
        }
      }
      for ( Hallazgo h : aComparar ) {
        String desorden = h.evidence.get( "deep_disorder" );
        String input = h.evidence.get( "input" );
        String antes = desorden == null ? "" : tx.t( "deep.order.disorder", "input", input, "detail", desorden ) + "; ";
        boolean fijo = !SIN_ORDEN_FIJO.contains( h.evidence.get( "cause" ) );
        if ( d.codigo == null ) {
          h.evidencia( "deep_rows", d.filas ).evidencia( "deep_rows_sorted", d.ordenadas );
        }
        if ( d.codigo == null && d.distinto ) {
          String clave = d.filas != d.ordenadas ? "deep.order.diff.rows" : "deep.order.diff.values";
          resultado( h, Sev.ERROR, Profundo.CONFIRMED, antes + tx.t( clave, "step", consumidor, "rows", d.filas,
            "sorted_rows", d.ordenadas ) + "." );
        } else if ( d.codigo == null ) {
          resultado( h, h.severity, Profundo.SAME_RESULT, antes + tx.t( fijo ? "deep.order.same" : "deep.order.same.nondet",
            "step", consumidor, "rows", d.filas ) + "." );
        } else if ( desorden != null ) {
          resultado( h, h.severity, Profundo.UNSORTED, antes + tx.t( "deep.order.incomplete", "why", d.motivo ) + "." );
        } else {
          noEvaluado( h, d.codigo, d.motivo );
        }
      }
    }

    /** Las filas que llegan desde una entrada: ordenadas (baja o queda) o desordenadas (va a la diferencial). */
    void muestra( Hallazgo h, StepMeta cons, String input, boolean comparan, String causa, List<Hallazgo> aComparar )
      throws Exception {
      List<String> claves = LintArreglos.partir( h.evidence.get( "keys" ) );
      TransMeta c = clon();
      StepMeta f = c.findStep( input );
      if ( f == null || claves.isEmpty() ) {
        noEvaluado( h, "deep_failed", tx.t( "deep.ne.no_step", "at", input ) );
        return;
      }
      RunMotor.recortar( c, f );
      String b = bloqueo( c );
      if ( b != null ) {
        noEvaluado( h, "deep_not_read_only", b );
        return;
      }
      RunMotor.Filas fs = correr( c, input, MAX_ORDEN, plazo() );
      if ( fs.errores > 0 || "init_failed".equals( fs.estado ) ) {
        noEvaluado( h, "deep_failed", tx.t( "deep.ne.failed", "detail", porQue( fs ) ) );
        return;
      }
      if ( fs.filas.isEmpty() ) {
        if ( fs.completa ) {
          noEvaluado( h, "deep_no_data", tx.t( "deep.ne.no_rows", "at", input ) );
        } else {
          noEvaluado( h, codigo( fs ), tx.t( "deep.ne.failed", "detail", porQue( fs ) ) );
        }
        return;
      }
      int[] idx = new int[ claves.size() ];
      for ( int k = 0; k < idx.length; k++ ) {
        idx[ k ] = fs.meta == null ? -1 : fs.meta.indexOfValue( claves.get( k ) );
        if ( idx[ k ] < 0 ) {
          noEvaluado( h, "deep_failed", tx.t( "deep.ne.no_key", "key", claves.get( k ), "at", input ) );
          return;
        }
      }
      Desorden d = desorden( fs.meta, fs.filas, idx, comparan, sentidos( cons, claves ) );
      h.evidencia( "deep_rows_read", fs.filas.size() );
      String keys = h.evidence.get( "keys" );
      if ( d == null ) {
        boolean fijo = !SIN_ORDEN_FIJO.contains( causa );
        if ( fs.completa && fijo ) {
          resultado( h, Sev.INFO, Profundo.SORTED, tx.t( "deep.order.sorted", "n", fs.filas.size(), "input", input,
            "keys", keys ) );
        } else if ( !fijo ) {
          resultado( h, h.severity, Profundo.SORTED_SAMPLE, tx.t( "deep.order.sorted_nondet", "n", fs.filas.size(),
            "input", input, "keys", keys ) );
        } else {
          resultado( h, h.severity, Profundo.SORTED_SAMPLE, tx.t( "deep.order.sorted_cut", "n", fs.filas.size(),
            "input", input, "keys", keys, "why", porQue( fs ) ) );
        }
        return;
      }
      h.evidencia( "deep_disorder", d.grupo ? tx.t( "deep.disorder.group", "value", d.valor, "row", d.fila )
        : tx.t( "deep.disorder.order", "value", d.valor, "row", d.fila, "prev", d.anterior ) );
      aComparar.add( h );
    }

    /** Sorted merge ordena cada clave en su sentido; los demás consumidores, ascendente. */
    boolean[] sentidos( StepMeta cons, List<String> claves ) {
      boolean[] asc = new boolean[ claves.size() ];
      Arrays.fill( asc, true );
      if ( "SortedMerge".equals( cons.getStepID() ) ) {
        try {
          for ( Element f : Nodos.hijos( Nodos.hijo( Nodos.parse( cons.getXML() ), "fields" ), "field" ) ) {
            int i = claves.indexOf( Nodos.txt( f, "name" ) );
            if ( i >= 0 ) {
              asc[ i ] = !"N".equalsIgnoreCase( Nodos.txt( f, "ascending" ) );
            }
          }
        } catch ( Exception ignored ) {
          // ascendente
        }
      }
      return asc;
    }

    /**
     * El consumidor tal cual y con un Sort rows delante de cada entrada marcada (insert_sort de apply_lint_fixes, en
     * otra copia): las dos corridas tienen que terminar solas para comparar. 30 s entre las dos.
     */
    Diferencial diferencial( StepMeta cons, List<Hallazgo> grupo ) throws Exception {
      String nombre = cons.getName();
      long hasta = System.currentTimeMillis() + plazo();
      TransMeta a = clon();
      RunMotor.recortar( a, a.findStep( nombre ) );
      String b = bloqueo( a );
      if ( b != null ) {
        return Diferencial.fallo( "deep_not_read_only", b );
      }
      for ( StepMeta s : a.getSteps() ) {
        if ( NO_DETERMINISTAS.contains( s.getStepID() ) ) {
          return Diferencial.fallo( "deep_failed", tx.t( "deep.why.random", "at", s.getName(), "type", s.getStepID() ) );
        }
      }
      TransMeta o = clon();
      LintArreglos.Aplicacion ap = new LintArreglos.Aplicacion( o, op.lang, UndoGrupo.sinSpoon( o ), o.getRepository(),
        o.getMetaStore() );
      for ( Hallazgo h : grupo ) {
        if ( h.fix == null || !Arreglo.INSERT_SORT.equals( h.fix.kind ) ) {
          continue;
        }
        try {
          new LintArreglos.Ordenar( ap, new LintArreglos.Elegido( h, null ) ).hacer( ap );
        } catch ( LintArreglos.Saltar e ) {
          return Diferencial.fallo( "deep_failed", tx.t( "deep.why.no_sort", "detail", e.getMessage() ) );
        }
      }
      RunMotor.recortar( o, o.findStep( nombre ) );
      b = bloqueo( o );
      if ( b != null ) {
        return Diferencial.fallo( "deep_not_read_only", b );
      }
      RunMotor.Filas fa = correr( a, nombre, MAX_ORDEN, Math.max( 1, hasta - System.currentTimeMillis() ) );
      if ( !fa.completa ) {
        return Diferencial.fallo( codigo( fa ), tx.t( "deep.why.as_is", "why", porQue( fa ) ) );
      }
      RunMotor.Filas fo = correr( o, nombre, MAX_ORDEN, Math.max( 1, hasta - System.currentTimeMillis() ) );
      if ( !fo.completa ) {
        return Diferencial.fallo( codigo( fo ), tx.t( "deep.why.sorted", "why", porQue( fo ) ) );
      }
      Diferencial d = new Diferencial();
      d.filas = fa.filas.size();
      d.ordenadas = fo.filas.size();
      d.distinto = !multiconjunto( fa.meta, fa.filas ).equals( multiconjunto( fo.meta, fo.filas ) );
      return d;
    }

    // ---------------------------------------------------------------- decimal

    void decimal( Hallazgo h ) {
      String falta = sinTiempo();
      if ( falta != null ) {
        sinTiempo( h, falta );
        return;
      }
      try {
        columna( h );
      } catch ( Throwable e ) {
        noEvaluado( h, "deep_failed", tx.t( "deep.ne.failed", "detail", RunMotor.primeraLinea( e ) ) );
      }
    }

    /** La columna como la lee el step, pero como String: cuántos valores traen '.' o ','. */
    void columna( Hallazgo h ) throws Exception {
      String campo = h.field != null ? h.field : h.attribute;
      TransMeta c = clon();
      StepMeta s = c.findStep( h.step );
      if ( s == null ) {
        noEvaluado( h, "deep_failed", tx.t( "deep.ne.no_step", "at", h.step ) );
        return;
      }
      Element raiz = Nodos.parse( s.getXML() );
      Element f = campo( raiz, campo );
      if ( f == null ) {
        noEvaluado( h, "deep_failed", tx.t( "deep.ne.no_field", "field", campo, "at", h.step ) );
        return;
      }
      LintArreglos.texto( f, "type", "String" ); // el texto que trae la fuente, partido por el step
      LintArreglos.texto( f, "format", "" );
      releer( c, s, raiz );
      RunMotor.recortar( c, s );
      String b = bloqueo( c );
      if ( b != null ) {
        noEvaluado( h, "deep_not_read_only", b );
        return;
      }
      RunMotor.Filas fs = correr( c, h.step, MAX_DECIMAL, plazo() );
      if ( fs.errores > 0 || "init_failed".equals( fs.estado ) ) {
        noEvaluado( h, "deep_failed", tx.t( "deep.ne.failed", "detail", porQue( fs ) ) );
        return;
      }
      int i = fs.meta == null ? -1 : fs.meta.indexOfValue( campo );
      if ( i < 0 && !fs.filas.isEmpty() ) {
        noEvaluado( h, "deep_failed", tx.t( "deep.ne.no_field", "field", campo, "at", h.step ) );
        return;
      }
      int valores = 0;
      int conSeparador = 0;
      String ejemplo = null;
      int filaEjemplo = -1;
      List<String> separados = new ArrayList<String>();
      for ( int r = 0; r < fs.filas.size(); r++ ) {
        Object[] fila = fs.filas.get( r );
        Object v = i < fila.length ? fila[ i ] : null;
        String x = v == null ? null : fs.meta.getValueMeta( i ).getString( v );
        if ( x == null || !DIGITO.matcher( x ).find() ) {
          continue; // vacíos, nulos y lo que no es un número no dicen nada del separador
        }
        valores++;
        x = x.trim();
        if ( x.indexOf( '.' ) >= 0 || x.indexOf( ',' ) >= 0 ) {
          conSeparador++;
          if ( ejemplo == null ) {
            ejemplo = x;
            filaEjemplo = r;
          }
          if ( separados.size() < 500 ) {
            separados.add( x );
          }
        }
      }
      h.evidencia( "deep_values", valores );
      if ( valores == 0 ) {
        if ( fs.completa ) {
          noEvaluado( h, "deep_no_data", tx.t( "deep.ne.no_values", "field", campo, "at", h.step ) );
        } else {
          noEvaluado( h, codigo( fs ), tx.t( "deep.ne.failed", "detail", porQue( fs ) ) );
        }
        return;
      }
      if ( conSeparador == 0 ) {
        if ( fs.completa ) {
          resultado( h, Sev.INFO, Profundo.INTEGERS, tx.t( "deep.decimal.integers", "values", valores, "field", campo ) );
        } else {
          resultado( h, h.severity, Profundo.INTEGERS_SAMPLE, tx.t( "deep.decimal.integers_cut", "values", valores,
            "field", campo, "why", porQue( fs ) ) );
        }
        return;
      }
      h.evidencia( "deep_with_separator", conSeparador ).evidencia( "deep_example", corto( ejemplo ) );
      String[] sep = separadores( separados );
      String texto = tx.t( "deep.decimal.separators", "with", conSeparador, "values", valores, "example", corto( ejemplo ) );
      String leido = comoLoLee( h, campo, filaEjemplo, corto( ejemplo ) );
      resultado( h, Sev.ERROR, Profundo.SEPARATORS, texto + ( leido == null ? "" : "; " + leido ) + "."
        + ( sep[ 0 ] == null ? "" : " " + tx.t( "deep.decimal.suggest", "symbol", sep[ 0 ] ) ) );
      if ( sep[ 0 ] != null && h.fix != null && Arreglo.SET_DECIMAL.equals( h.fix.kind ) ) {
        h.fix.vista( sep[ 0 ] ).param( "suggested", sep[ 0 ] ); // apply_lint_fixes: values[key] con este símbolo
      }
      if ( sep[ 1 ] != null ) {
        h.evidencia( "deep_grouping_symbol", sep[ 1 ] );
      }
    }

    /** Cómo lee esta JVM el primer valor con separador (el step tal cual), si queda tiempo. */
    String comoLoLee( Hallazgo h, String campo, int fila, String ejemplo ) {
      if ( sinTiempo() != null || fin - System.currentTimeMillis() < 1000 ) {
        return null;
      }
      String loc = Locale.getDefault( Locale.Category.FORMAT ).toLanguageTag();
      try {
        TransMeta c = clon();
        StepMeta s = c.findStep( h.step );
        RunMotor.recortar( c, s );
        if ( bloqueo( c ) != null ) {
          return null;
        }
        RunMotor.Filas fs = correr( c, h.step, Math.min( MAX_DECIMAL, fila + 1 ), plazo() );
        int i = fs.meta == null ? -1 : fs.meta.indexOfValue( campo );
        if ( fs.errores == 0 && i >= 0 && fs.filas.size() > fila && i < fs.filas.get( fila ).length ) {
          ValueMetaInterface vm = fs.meta.getValueMeta( i );
          Object v = fs.filas.get( fila )[ i ];
          try {
            Object n = vm.isStorageBinaryString() ? vm.convertToNormalStorageType( v ) : v; // conversión perezosa
            String leido = n instanceof BigDecimal ? ( (BigDecimal) n ).toPlainString() : String.valueOf( n );
            h.evidencia( "deep_read_as", leido ).evidencia( "deep_locale", loc );
            return tx.t( "deep.decimal.read_as", "locale", loc, "example", ejemplo, "read", leido );
          } catch ( KettleValueException e ) {
            return leeMal( h, loc, RunMotor.primeraLinea( e ) );
          }
        }
        if ( fs.errores > 0 ) {
          return leeMal( h, loc, fs.error == null ? "?" : fs.error );
        }
      } catch ( Throwable ignored ) {
        // es un dato de más: sin él, el hallazgo vale igual
      }
      return null;
    }

    private String leeMal( Hallazgo h, String loc, String error ) {
      h.evidencia( "deep_read_error", error ).evidencia( "deep_locale", loc );
      return tx.t( "deep.decimal.read_fails", "locale", loc, "error", error );
    }
  }

  // ---------------------------------------------------------------- utilidades (estáticas, para probarlas solas)

  /** El resultado de la prueba diferencial: codigo == null si las dos corridas terminaron solas. */
  static final class Diferencial {
    String codigo;
    String motivo;
    boolean distinto;
    int filas;
    int ordenadas;

    static Diferencial fallo( String codigo, String motivo ) {
      Diferencial d = new Diferencial();
      d.codigo = codigo;
      d.motivo = motivo;
      return d;
    }
  }

  /** Dónde se rompe el orden: fila (desde 1), las claves de esa fila y, si el orden no es ascendente, las de antes. */
  static final class Desorden {
    final int fila;
    final String valor;
    final String anterior;
    final boolean grupo;

    Desorden( int fila, String valor, String anterior, boolean grupo ) {
      this.fila = fila;
      this.valor = valor;
      this.anterior = anterior;
      this.grupo = grupo;
    }
  }

  /**
   * Compara las claves de filas consecutivas con ValueMeta.compare. Si el consumidor compara (Merge join…), una fila
   * que va antes que la anterior en el sentido pedido; si agrupa, un grupo que vuelve a aparecer después de cerrado.
   * null si están en orden.
   */
  static Desorden desorden( final RowMetaInterface rm, List<Object[]> filas, final int[] idx, boolean comparan,
                            final boolean[] asc ) {
    Comparator<Object[]> cmp = new Comparator<Object[]>() {
      @Override
      public int compare( Object[] a, Object[] b ) {
        for ( int k = 0; k < idx.length; k++ ) {
          int c;
          try {
            c = rm.getValueMeta( idx[ k ] ).compare( a[ idx[ k ] ], b[ idx[ k ] ] );
          } catch ( KettleValueException e ) {
            throw new IllegalStateException( e.getMessage(), e );
          }
          if ( c != 0 ) {
            return asc[ k ] ? c : -c;
          }
        }
        return 0;
      }
    };
    TreeSet<Object[]> cerrados = new TreeSet<Object[]>( cmp ); // iguales para Kettle = el mismo grupo
    for ( int i = 1; i < filas.size(); i++ ) {
      Object[] a = filas.get( i - 1 );
      Object[] b = filas.get( i );
      int c = cmp.compare( a, b );
      if ( c == 0 ) {
        continue;
      }
      if ( comparan && c > 0 ) {
        return new Desorden( i + 1, claves( rm, b, idx ), claves( rm, a, idx ), false );
      }
      cerrados.add( a );
      if ( cerrados.contains( b ) ) {
        return new Desorden( i + 1, claves( rm, b, idx ), null, true );
      }
    }
    return null;
  }

  static String claves( RowMetaInterface rm, Object[] fila, int[] idx ) {
    StringBuilder b = new StringBuilder( "[" );
    for ( int k = 0; k < idx.length; k++ ) {
      String s;
      try {
        s = rm.getValueMeta( idx[ k ] ).getString( fila[ idx[ k ] ] );
      } catch ( Exception e ) {
        s = String.valueOf( fila[ idx[ k ] ] );
      }
      b.append( k == 0 ? "" : ", " ).append( corto( s ) );
    }
    return b.append( "]" ).toString();
  }

  static String corto( String s ) {
    return s == null ? "null" : s.length() <= MAX_VALOR ? s : s.substring( 0, MAX_VALOR ) + "…";
  }

  /** Las filas como multiconjunto de textos (el texto de cada valor como lo da Kettle; null aparte). */
  static Map<String, Integer> multiconjunto( RowMetaInterface rm, List<Object[]> filas ) {
    Map<String, Integer> m = new HashMap<String, Integer>();
    for ( Object[] r : filas ) {
      StringBuilder b = new StringBuilder();
      int n = rm == null ? r.length : rm.size();
      for ( int i = 0; i < n; i++ ) {
        Object v = i < r.length ? r[ i ] : null;
        if ( v == null ) {
          b.append( '\u0000' );
        } else {
          try {
            b.append( rm.getValueMeta( i ).getString( v ) );
          } catch ( Exception e ) {
            b.append( String.valueOf( v ) );
          }
        }
        b.append( '\u0001' );
      }
      String k = b.toString();
      Integer x = m.get( k );
      m.put( k, x == null ? 1 : x + 1 );
    }
    return m;
  }

  /**
   * El símbolo decimal y el de agrupación que sugieren los valores: con los dos, el último es el decimal (1.234,56);
   * uno solo una vez, el decimal (2.50); uno solo repetido, el de agrupación (1.234.567). null si no se sabe o los
   * valores se contradicen. [NP: sin casos reales con los dos separadores en el corpus]
   */
  static String[] separadores( List<String> valores ) {
    int punto = 0;
    int coma = 0;
    String grupo = null;
    for ( String v : valores ) {
      int p = v.lastIndexOf( '.' );
      int c = v.lastIndexOf( ',' );
      if ( p >= 0 && c >= 0 ) {
        if ( p > c ) {
          punto++;
          grupo = votar( grupo, "," );
        } else {
          coma++;
          grupo = votar( grupo, "." );
        }
      } else if ( p >= 0 ) {
        if ( v.indexOf( '.' ) != p ) {
          grupo = votar( grupo, "." );
        } else {
          punto++;
        }
      } else if ( c >= 0 ) {
        if ( v.indexOf( ',' ) != c ) {
          grupo = votar( grupo, "," );
        } else {
          coma++;
        }
      }
    }
    String dec = punto > 0 && coma == 0 ? "." : coma > 0 && punto == 0 ? "," : null;
    if ( "?".equals( grupo ) ) {
      grupo = null;
    }
    if ( grupo != null && grupo.equals( dec ) ) { // el mismo símbolo para las dos cosas: no se sabe
      dec = null;
      grupo = null;
    }
    return new String[] { dec, grupo };
  }

  private static String votar( String actual, String nuevo ) {
    return actual == null ? nuevo : actual.equals( nuevo ) ? actual : "?";
  }

  /** El &lt;field&gt; de &lt;fields&gt; con ese nombre. */
  static Element campo( Element raiz, String nombre ) {
    for ( Element f : Nodos.hijos( Nodos.hijo( raiz, "fields" ), "field" ) ) {
      if ( Nodos.txt( f, "name" ).equals( nombre ) ) {
        return f;
      }
    }
    return null;
  }

  /** Relee la configuración del step desde su XML cambiado (en la copia: sin undo). */
  static void releer( TransMeta tm, StepMeta s, Element raiz ) throws Exception {
    StepMeta nuevo = new StepMeta( XMLHandler.getSubNode( XMLHandler.loadXMLString( Xml.texto( raiz ) ), "step" ),
      tm.getDatabases(), tm.getMetaStore() );
    if ( nuevo.isMissing() || nuevo.getStepMetaInterface() == null ) {
      throw new KettleException( "Kettle could not read '" + s.getName() + "' with the field as String" );
    }
    s.setStepMetaInterface( nuevo.getStepMetaInterface() );
    nuevo.getStepMetaInterface().setParentStepMeta( s );
    tm.clearCaches();
  }
}
