package io.github.ivi2511.pentahomcp.robustez;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.Comprobacion;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.Corrida;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.PasoCuenta;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.TablaCuenta;

/**
 * El balance de filas y las comprobaciones de {@code stress_test} (docs/robustez-diseno.md §5.4), sin Kettle: con el
 * grafo resumido ({@link Nodo}, lo arma EstresSandbox desde el TransMeta) y las cuentas de la hija ({@link Corrida}).
 * Garantía que se comprueba: cada step que no está declarado como descartador ni agrega por naturaleza entrega tantas
 * filas como recibe ({@code entra = recibidas − filas de sus flujos info}, {@code sale = puestas + puestas por error}).
 * Si no se puede saber cuántas filas info recibió (la fuente info reparte entre varios destinos) ese step queda
 * {@code not_evaluated}: nunca un falso «perdidas».
 *
 * @dueño R2c
 */
public final class Balance {

  /**
   * Steps que cambian la cantidad de filas por lo que son (agrupar, deduplicar, normalizar, unir, muestrear): se
   * informan («agrupa 31 → 12») y no cuentan como pérdida. Ids de plugin de Kettle.
   */
  public static final Set<String> AGREGAN = new HashSet<String>( Arrays.asList( "GroupBy", "MemoryGroupBy", "Unique",
    "UniqueRowsByHashSet", "Denormaliser", "Normaliser", "SplitFieldToRows3", "JoinRows", "MergeJoin", "SortedMerge",
    "Append", "MergeRows", "AnalyticQuery", "SampleRows", "FlattenerStep", "Flattener", "RowsToResult",
    "DetectEmptyStream", "DetectLastRow", "Delay", "XMLInputStream", "SortRows" ) );

  private Balance() {
  }

  /** Un step del grafo, resumido para el balance. */
  public static final class Nodo {
    public String nombre;
    public String tipo;
    /** Steps que le mandan filas por el flujo principal (sin info). */
    public final List<String> previos = new ArrayList<String>();
    /** Steps que le mandan filas por un flujo info (Stream lookup, Merge join…). */
    public final List<String> infos = new ArrayList<String>();
    /** A cuántos steps manda filas (flujo normal e info; sin el hop de error). */
    public int destinos;
    /** Lee un archivo que stress_test reemplaza por la variante. */
    public boolean entradaArchivo;
    /** Escribe filas buenas en una BD o un archivo (fuera de las ramas de rechazo). */
    public boolean carga;
    /** La carga es un archivo (Text file output, Excel…), no una BD. */
    public boolean archivo;
    /** Escribe un archivo en una rama de rechazo. */
    public boolean escritorRechazo;
    /** Aguas abajo de un hop de error o de la rama falsa de un filtro. */
    public boolean enRechazo;
    /** pentaho-live-robust/drops (o un silent_filter_drop silenciado): descarta filas a propósito. */
    public String declaracion;

    public Nodo() {
    }

    public Nodo( String nombre, String tipo ) {
      this.nombre = nombre;
      this.tipo = tipo;
    }

    boolean agrega() {
      return tipo != null && AGREGAN.contains( tipo );
    }
  }

  /** Lo que se leyó de un archivo de rechazo (lo lee el padre con el separador y el encoding de su step). */
  public static final class Rechazo {
    public String paso;
    public String archivo;
    public long filas;
    public long sinMotivo;
    /** Ninguna columna que parezca un motivo. */
    public boolean sinColumnaMotivo;
    public final List<String> muestras = new ArrayList<String>();
  }

  /** El balance y las comprobaciones de una variante. */
  public static final class Informe {
    /** Idioma de los mensajes y del resumen (en | es | pt); las claves y los códigos no cambian. */
    public String idioma = "en";
    public long leidas;
    public long cargadas;
    public long rechazadas;
    public final Map<String, Long> descartesDeclarados = new LinkedHashMap<String, Long>();
    public final Map<String, String> declaraciones = new LinkedHashMap<String, String>();
    public final Map<String, Long> perdidas = new LinkedHashMap<String, Long>();
    public final List<String> agrupa = new ArrayList<String>();
    public final List<String> noEvaluados = new ArrayList<String>();
    public final List<Comprobacion> comprobaciones = new ArrayList<Comprobacion>();

    public long perdidasTotal() {
      long n = 0;
      for ( long x : perdidas.values() ) {
        n += x;
      }
      return n;
    }

    public boolean falla() {
      return cuenta( EstresResultado.FALLA ) > 0;
    }

    public int cuenta( String estado ) {
      int n = 0;
      for ( Comprobacion c : comprobaciones ) {
        if ( estado.equals( c.estado ) ) {
          n++;
        }
      }
      return n;
    }

    public Comprobacion comprobacion( String id ) {
      for ( Comprobacion c : comprobaciones ) {
        if ( c.id.equals( id ) ) {
          return c;
        }
      }
      return null;
    }

    /** La corrida abortó: el resumen lo dice en vez de cuadrar filas. */
    public boolean abortada;
    /** Abortó por el entorno (falta la BD o una tabla): el motivo; si no, null. */
    public String entorno;
    /** Con {@link #entorno}: además, otro step abortó por una fila mala (no_abort falla). */
    public boolean porFila;

    /** «31 read = 17 loaded + 12 rejected + 2 dropped (declared) + 0 lost». */
    public String resumen() {
      Rotulos r = textos( idioma );
      if ( entorno != null ) {
        return porFila ? r.f( "summary_env_bad_row", entorno ) : r.f( "summary_env", entorno );
      }
      if ( abortada ) {
        return r.f( "summary_aborted", leidas, cargadas, rechazadas );
      }
      long dd = 0;
      for ( long x : descartesDeclarados.values() ) {
        dd += x;
      }
      if ( leidas == cargadas + rechazadas + dd + perdidasTotal() ) {
        return r.f( "summary_balance", leidas, cargadas, rechazadas, dd, perdidasTotal() );
      }
      return r.f( "summary_fanout", leidas, cargadas, rechazadas, dd, perdidasTotal() );
    }

    public ObjectNode json( ObjectMapper om ) {
      ObjectNode b = om.createObjectNode();
      b.put( "read", leidas ).put( "loaded", cargadas ).put( "rejected", rechazadas );
      ArrayNode dd = b.putArray( "declared_drops" );
      for ( Map.Entry<String, Long> e : descartesDeclarados.entrySet() ) {
        dd.addObject().put( "step", e.getKey() ).put( "rows", e.getValue() )
          .put( "declaration", declaraciones.get( e.getKey() ) );
      }
      ArrayNode l = b.putArray( "lost" );
      for ( Map.Entry<String, Long> e : perdidas.entrySet() ) {
        l.addObject().put( "step", e.getKey() ).put( "rows", e.getValue() );
      }
      ArrayNode ag = b.putArray( "aggregates" );
      for ( String s : agrupa ) {
        ag.add( s );
      }
      if ( !noEvaluados.isEmpty() ) {
        ArrayNode ne = b.putArray( "not_evaluated" );
        for ( String s : noEvaluados ) {
          ne.add( s );
        }
      }
      if ( entorno != null ) {
        b.put( "environment_problem", entorno );
      }
      b.put( "summary", resumen() );
      return b;
    }
  }

  /**
   * Evalúa una corrida. {@code lineas}: filas de datos de la variante por step de entrada (sin cabecera);
   * {@code debenRechazarse}: filas rotas del manifiesto que deberían terminar en un rechazo; {@code rechazos}: los
   * archivos de rechazo leídos; {@code segunda}: la 2ª corrida de rerun (o null).
   */
  public static Informe evaluar( List<Nodo> nodos, Corrida c, Map<String, Integer> lineas, int debenRechazarse,
                                 List<Rechazo> rechazos, Corrida segunda ) {
    return evaluar( nodos, c, lineas, debenRechazarse, rechazos, segunda, null );
  }

  /**
   * Como {@link #evaluar(List, Corrida, Map, int, List, Corrida)}; {@code bdsQueFaltan}: los archivos de BD que no
   * existían antes de la prueba. Si la corrida abortó porque falta la BD o una tabla, es un problema del entorno: las
   * comprobaciones quedan not_evaluated (no es una falla de robustez) y el informe lo dice.
   */
  public static Informe evaluar( List<Nodo> nodos, Corrida c, Map<String, Integer> lineas, int debenRechazarse,
                                 List<Rechazo> rechazos, Corrida segunda, Collection<String> bdsQueFaltan ) {
    return evaluar( nodos, c, lineas, debenRechazarse, rechazos, segunda, bdsQueFaltan, "en" );
  }

  /**
   * Como {@link #evaluar(List, Corrida, Map, int, List, Corrida, Collection)}, con los mensajes, los detalles y el
   * resumen en {@code idioma} (en | es | pt; otro = en). Los ids y los estados no cambian.
   */
  public static Informe evaluar( List<Nodo> nodos, Corrida c, Map<String, Integer> lineas, int debenRechazarse,
                                 List<Rechazo> rechazos, Corrida segunda, Collection<String> bdsQueFaltan,
                                 String idioma ) {
    Rotulos r = textos( idioma );
    Informe inf = new Informe();
    inf.idioma = r.idioma();
    String entorno = problemaDeEntorno( c, bdsQueFaltan, idioma );
    if ( entorno != null ) {
      inf.abortada = true;
      inf.entorno = entorno;
      // el entorno no tapa una fila mala: si otro step abortó por una fila, eso es una falla de robustez igual
      List<PasoCuenta> porFila = abortosPorFila( c );
      Comprobacion k;
      if ( porFila.isEmpty() ) {
        k = new Comprobacion( "no_abort", EstresResultado.NO_EVALUADA, r.f( "environment_reason", entorno ) );
        if ( c.mensajeError != null ) {
          k.detalles.add( r.f( "detail_error", recortar( c.mensajeError ) ) );
        }
      } else {
        inf.porFila = true;
        k = new Comprobacion( "no_abort", EstresResultado.FALLA, r.f( "no_abort_env_bad_row", entorno ) );
        for ( PasoCuenta p : porFila ) {
          k.detalles.add( r.f( "step_failed_msg", p.nombre, p.tipo, p.errores, recortar( p.mensaje ) ) );
        }
      }
      inf.comprobaciones.add( k );
      for ( String id : new String[] { "input_complete", "no_silent_loss", "rejects_have_reason",
        "bad_rows_rejected" } ) {
        inf.comprobaciones.add( new Comprobacion( id, EstresResultado.NO_EVALUADA, r.f( inf.porFila
          ? "env_problem_aborted" : "env_problem", entorno ) ) );
      }
      if ( segunda != null ) {
        inf.comprobaciones.add( new Comprobacion( "idempotent", EstresResultado.NO_EVALUADA, r.f( "env_problem",
          entorno ) ) );
      }
      return inf;
    }
    Map<String, PasoCuenta> cuentas = c.porPaso();
    Map<String, Nodo> porNombre = new LinkedHashMap<String, Nodo>();
    for ( Nodo n : nodos ) {
      porNombre.put( n.nombre, n );
    }

    // --- no_abort
    boolean abortada = !c.terminada || c.errores > 0 || c.vencida;
    inf.abortada = abortada;
    if ( abortada ) {
      Comprobacion k = new Comprobacion( "no_abort", EstresResultado.FALLA,
        c.vencida ? r.s( "no_abort_timeout" ) : c.mensajeError != null ? r.f( "no_abort_aborted_msg", c.errores,
          recortar( c.mensajeError ) ) : r.f( "no_abort_aborted", c.errores ) );
      for ( PasoCuenta p : cuentas.values() ) {
        if ( p.errores > 0 ) {
          k.detalles.add( r.f( "step_failed", p.nombre, p.tipo, p.errores ) );
        }
      }
      inf.comprobaciones.add( k );
    } else {
      inf.comprobaciones.add( new Comprobacion( "no_abort", EstresResultado.PASA, null ) );
    }

    // --- totales y balance por step
    Comprobacion completa = new Comprobacion( "input_complete", EstresResultado.PASA, null );
    for ( Nodo n : nodos ) {
      PasoCuenta p = cuentas.get( n.nombre );
      if ( p == null ) {
        continue;
      }
      long sale = p.puestas + p.puestasError;
      if ( n.entradaArchivo ) {
        inf.leidas += sale;
        Integer esperadas = lineas == null ? null : lineas.get( n.nombre );
        if ( esperadas != null && !abortada && sale < esperadas ) {
          completa.estado = EstresResultado.AVISO;
          completa.detalles.add( r.f( "input_swallowed", n.nombre, sale, esperadas, esperadas - sale ) );
        }
      }
      if ( n.carga && !n.enRechazo ) {
        // un archivo escribe toda fila que recibe (getLinesOutput cuenta también la cabecera); una BD, las que entraron
        inf.cargadas += n.archivo ? Math.max( p.recibidas, p.leidas ) : Math.max( 0, Math.max( p.recibidas, p.leidas )
          - p.puestasError );
      }
      if ( n.escritorRechazo ) {
        inf.rechazadas += p.recibidas > 0 ? p.recibidas : p.leidas;
      }
      if ( abortada || ( n.previos.isEmpty() && n.infos.isEmpty() ) ) {
        continue; // abortada: cuentas parciales; una entrada: no recibe filas de otros steps
      }
      if ( n.agrega() ) {
        long todas = Math.max( p.recibidas, p.leidas );
        if ( todas != sale ) {
          inf.agrupa.add( n.nombre + ": " + todas + " -> " + sale );
        }
        continue;
      }
      // filas info: las que puso la fuente info, si solo manda a este step
      long info = 0;
      boolean evaluable = true;
      for ( String i : n.infos ) {
        Nodo ni = porNombre.get( i );
        PasoCuenta pi = cuentas.get( i );
        if ( ni == null || pi == null || ni.destinos != 1 ) {
          evaluable = false;
          break;
        }
        info += pi.puestas;
      }
      long recibidas = Math.max( p.recibidas, p.leidas );
      if ( !evaluable ) {
        inf.noEvaluados.add( r.f( "not_eval_info_shared", n.nombre ) );
        continue;
      }
      long entra = recibidas - info;
      if ( entra < 0 ) {
        inf.noEvaluados.add( r.f( "not_eval_info_split", n.nombre ) );
        continue;
      }
      if ( entra > sale && !abortada ) {
        if ( n.declaracion != null ) {
          inf.descartesDeclarados.put( n.nombre, entra - sale );
          inf.declaraciones.put( n.nombre, n.declaracion );
        } else {
          inf.perdidas.put( n.nombre, entra - sale );
        }
      }
    }
    inf.comprobaciones.add( completa );

    // --- no_silent_loss
    if ( abortada ) {
      inf.comprobaciones.add( new Comprobacion( "no_silent_loss", EstresResultado.NO_EVALUADA,
        r.s( "loss_partial" ) ) );
    } else if ( inf.perdidas.isEmpty() ) {
      inf.comprobaciones.add( new Comprobacion( "no_silent_loss", EstresResultado.PASA, null ) );
    } else {
      Comprobacion k = new Comprobacion( "no_silent_loss", EstresResultado.FALLA, r.f( "loss",
        inf.perdidasTotal() ) );
      for ( Map.Entry<String, Long> e : inf.perdidas.entrySet() ) {
        Nodo n = porNombre.get( e.getKey() );
        k.detalles.add( r.f( n != null && "FilterRows".equals( n.tipo ) ? "loss_step_filter" : "loss_step",
          e.getKey(), n == null ? "?" : n.tipo, e.getValue() ) );
      }
      inf.comprobaciones.add( k );
    }

    // --- rejects_have_reason
    if ( rechazos == null || rechazos.isEmpty() ) {
      inf.comprobaciones.add( new Comprobacion( "rejects_have_reason", debenRechazarse > 0 && !abortada
        ? EstresResultado.AVISO : EstresResultado.NO_EVALUADA, r.s( "no_rejects_file" ) ) );
    } else {
      Comprobacion k = new Comprobacion( "rejects_have_reason", EstresResultado.PASA, null );
      for ( Rechazo x : rechazos ) {
        if ( x.sinColumnaMotivo && x.filas > 0 ) {
          k.estado = EstresResultado.AVISO;
          k.detalles.add( r.f( "reject_no_column", x.paso, x.filas ) );
        } else if ( x.sinMotivo > 0 ) {
          k.estado = EstresResultado.AVISO;
          k.detalles.add( r.f( "reject_empty_reason", x.paso, x.sinMotivo, x.filas ) );
        }
      }
      inf.comprobaciones.add( k );
    }

    // --- bad_rows_rejected
    if ( abortada || debenRechazarse <= 0 ) {
      inf.comprobaciones.add( new Comprobacion( "bad_rows_rejected", EstresResultado.NO_EVALUADA,
        abortada ? r.s( "bad_rows_aborted" ) : r.s( "bad_rows_none" ) ) );
    } else {
      long dd = 0;
      for ( long x : inf.descartesDeclarados.values() ) {
        dd += x;
      }
      long fuera = debenRechazarse - inf.rechazadas - dd;
      inf.comprobaciones.add( fuera > 0 ? new Comprobacion( "bad_rows_rejected", EstresResultado.AVISO, r.f(
        "bad_rows_not_rejected", fuera, debenRechazarse, inf.rechazadas ) )
        : new Comprobacion( "bad_rows_rejected", EstresResultado.PASA, null ) );
    }

    // --- idempotent (rerun)
    if ( segunda != null ) {
      Comprobacion k = new Comprobacion( "idempotent", EstresResultado.PASA, null );
      boolean segundaAborta = !segunda.terminada || segunda.errores > 0 || segunda.vencida;
      if ( segundaAborta ) {
        k.estado = EstresResultado.FALLA;
        k.mensaje = segunda.mensajeError != null ? r.f( "idem_second_aborted_msg", recortar( segunda.mensajeError ) )
          : r.s( "idem_second_aborted" );
      } else if ( c.tablas.isEmpty() ) {
        k.estado = EstresResultado.NO_EVALUADA;
        k.mensaje = r.s( "idem_no_table" );
      } else {
        Map<String, TablaCuenta> t2 = new LinkedHashMap<String, TablaCuenta>();
        for ( TablaCuenta t : segunda.tablas ) {
          t2.put( t.conexion + "." + t.tabla, t );
        }
        for ( TablaCuenta t : c.tablas ) {
          TablaCuenta b = t2.get( t.conexion + "." + t.tabla );
          if ( b == null ) {
            continue;
          }
          if ( b.filas > t.filas ) {
            k.estado = EstresResultado.FALLA;
            k.detalles.add( r.f( "idem_duplicates", t.tabla, t.filas, b.filas ) );
          } else if ( t.huellaClaves != null && !t.huellaClaves.equals( b.huellaClaves ) ) {
            if ( !EstresResultado.FALLA.equals( k.estado ) ) {
              k.estado = EstresResultado.AVISO;
            }
            k.detalles.add( r.f( "idem_keys_changed", t.tabla ) );
          }
        }
        if ( !k.detalles.isEmpty() ) {
          k.mensaje = r.s( "idem_not_idempotent" );
        }
      }
      inf.comprobaciones.add( k );
    }
    return inf;
  }

  /**
   * Veredicto de varias variantes: fragile (alguna falla o aviso, también una fila mala en una corrida que además tuvo un
   * problema de entorno), not_run (ninguna, pero alguna no se pudo evaluar por el entorno: falta la BD o una tabla),
   * robust (el resto).
   */
  public static String veredicto( List<Informe> informes ) {
    boolean entorno = false;
    for ( Informe i : informes ) {
      if ( i.cuenta( EstresResultado.FALLA ) > 0 || i.cuenta( EstresResultado.AVISO ) > 0 ) {
        return "fragile";
      }
      entorno |= i.entorno != null;
    }
    return entorno ? "not_run" : "robust";
  }

  /** Tabla que no existe, por motor: SQLite, H2, MySQL/MariaDB, PostgreSQL, Oracle, SQL Server. */
  private static final Pattern[] SIN_TABLA = {
    Pattern.compile( "(?i)no such table:?\\s*([\\w.$\"`\\[\\]]+)" ),
    Pattern.compile( "(?i)Table \"?([\\w.$]+)\"? not found" ),
    Pattern.compile( "(?i)Table '([^']+)' doesn't exist" ),
    Pattern.compile( "(?i)relation \"([^\"]+)\" does not exist" ),
    Pattern.compile( "(?i)Invalid object name '([^']+)'" ),
    Pattern.compile( "(?i)ORA-00942()" ) };
  /** Archivo de BD que no existe o no se puede abrir (SQLite, H2). */
  private static final Pattern[] SIN_BD = {
    Pattern.compile( "(?i)path to '([^']+)'[^\\n]*does not exist" ),
    Pattern.compile( "(?i)Database \"([^\"]+)\" not found" ),
    Pattern.compile( "(?i)unable to open database file()" ),
    Pattern.compile( "(?i)SQLITE_CANTOPEN()" ) };

  /**
   * Si la corrida abortó porque falta la BD o una tabla (no por una fila mala), el motivo para la persona; si no, null.
   * Mira el mensaje de error y la cola del log de la hija; {@code bdsQueFaltan}: archivos de BD que no existían antes
   * de la prueba (el motivo los nombra).
   */
  public static String problemaDeEntorno( Corrida c, Collection<String> bdsQueFaltan ) {
    return problemaDeEntorno( c, bdsQueFaltan, "en" );
  }

  /** Como {@link #problemaDeEntorno(Corrida, Collection)}, con el motivo en {@code idioma} (en | es | pt). */
  public static String problemaDeEntorno( Corrida c, Collection<String> bdsQueFaltan, String idioma ) {
    if ( c == null || c.vencida || ( c.terminada && c.errores == 0 ) ) {
      return null;
    }
    String texto = ( c.mensajeError == null ? "" : c.mensajeError ) + "\n" + ( c.colaLog == null ? "" : c.colaLog );
    String bd = null;
    for ( Pattern p : SIN_BD ) {
      Matcher m = p.matcher( texto );
      if ( m.find() ) {
        bd = m.group( 1 );
        break;
      }
    }
    String tabla = null;
    for ( Pattern p : SIN_TABLA ) {
      Matcher m = p.matcher( texto );
      if ( m.find() ) {
        tabla = m.group( 1 ).replaceAll( "[\"`\\[\\]]", "" );
        break;
      }
    }
    if ( bd == null && tabla == null ) {
      return null;
    }
    Rotulos r = textos( idioma );
    if ( bdsQueFaltan != null && !bdsQueFaltan.isEmpty() ) {
      String bds = String.join( ", ", bdsQueFaltan );
      return tabla != null && !tabla.isEmpty() ? r.f( "env_db_files_table", bds, tabla ) : r.f( "env_db_files", bds );
    }
    if ( bd != null ) {
      // un patrón sin grupo (unable to open database file, SQLITE_CANTOPEN): no se sabe el nombre
      return bd.isEmpty() ? r.s( "env_db_unnamed" ) : r.f( "env_db", bd );
    }
    return tabla.isEmpty() ? r.s( "env_table_unnamed" ) : r.f( "env_table", tabla );
  }

  /** ¿El mensaje habla de una BD o una tabla que no existe? */
  static boolean deEntorno( String texto ) {
    if ( texto == null ) {
      return false;
    }
    for ( Pattern p : SIN_BD ) {
      if ( p.matcher( texto ).find() ) {
        return true;
      }
    }
    for ( Pattern p : SIN_TABLA ) {
      if ( p.matcher( texto ).find() ) {
        return true;
      }
    }
    return false;
  }

  /**
   * Los steps que abortaron por una fila, no por el entorno: con errores y un mensaje propio (el de la hija o, si no lo
   * trae, sus líneas ERROR en la cola del log) que no habla de una BD ni una tabla que falta. Un step con errores pero
   * sin mensaje no cuenta (no se inventa una fila mala), y si la corrida no pasó de la inicialización no se procesó
   * ninguna fila.
   */
  public static List<PasoCuenta> abortosPorFila( Corrida c ) {
    List<PasoCuenta> l = new ArrayList<PasoCuenta>();
    if ( c == null || !c.iniciada ) {
      return l;
    }
    Map<String, String> delLog = erroresPorPaso( c.colaLog );
    for ( PasoCuenta p : c.porPaso().values() ) {
      if ( p.errores <= 0 ) {
        continue;
      }
      String m = p.mensaje != null ? p.mensaje : delLog.get( p.nombre );
      if ( m == null || deEntorno( m ) ) {
        continue;
      }
      p.mensaje = m;
      l.add( p );
    }
    return l;
  }

  /**
   * Los mensajes de ERROR del log de Kettle por step («2026/10/22 00:03:01 - Convert types.0 - ERROR (version …) :
   * mensaje», sin la copia), con la primera línea de detalle tras una excepción y sin la pila, unidos con " | " (como
   * mucho 5 por step).
   */
  public static Map<String, String> erroresPorPaso( String log ) {
    Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
    if ( log == null ) {
      return new LinkedHashMap<String, String>();
    }
    String ultimo = null;
    boolean tras = false;
    for ( String x : log.split( "\r?\n" ) ) {
      String resto = x.replaceFirst( "^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2} - ", "" );
      boolean conFecha = !resto.equals( x );
      String paso = null;
      if ( conFecha ) {
        int g = resto.indexOf( " - " );
        if ( g >= 0 ) {
          paso = resto.substring( 0, g ).trim().replaceFirst( "\\.\\d+$", "" );
          resto = resto.substring( g + 3 );
        }
      }
      String t = resto.trim();
      if ( conFecha && t.startsWith( "ERROR" ) && paso != null ) {
        int dos = t.indexOf( ") : " );
        String msg = dos >= 0 ? t.substring( dos + 4 ).trim() : t.substring( 5 ).trim();
        tras = msg.isEmpty() || msg.endsWith( ":" ) || msg.endsWith( "Exception" );
        ultimo = paso;
        agregarA( m, paso, msg );
      } else if ( conFecha ) {
        tras = false;
        ultimo = null;
      } else if ( tras && ultimo != null && !t.isEmpty() && !t.startsWith( "at " ) && !t.startsWith( "..." ) ) {
        agregarA( m, ultimo, t );
        tras = false;
      }
    }
    Map<String, String> r = new LinkedHashMap<String, String>();
    for ( Map.Entry<String, List<String>> e : m.entrySet() ) {
      if ( !e.getValue().isEmpty() ) {
        r.put( e.getKey(), String.join( " | ", e.getValue() ) );
      }
    }
    return r;
  }

  private static void agregarA( Map<String, List<String>> m, String paso, String t ) {
    List<String> l = m.get( paso );
    if ( l == null ) {
      l = new ArrayList<String>();
      m.put( paso, l );
    }
    if ( t != null && !t.isEmpty() && !l.contains( t ) && l.size() < 5 ) {
      l.add( t );
    }
  }

  /** ¿La columna parece un motivo de rechazo? */
  public static boolean esColumnaMotivo( String nombre, Set<String> extra ) {
    if ( nombre == null ) {
      return false;
    }
    String n = nombre.trim().toLowerCase( Locale.ROOT );
    if ( extra != null ) {
      for ( String e : extra ) {
        if ( e != null && e.trim().equalsIgnoreCase( n ) ) {
          return true;
        }
      }
    }
    return n.equals( "motivo" ) || n.equals( "reason" ) || n.startsWith( "motivo_" ) || n.endsWith( "_motivo" )
      || n.equals( "error_description" ) || n.equals( "error_desc" ) || n.equals( "errordescription" )
      || n.equals( "reject_reason" ) || n.equals( "motivo_rechazo" ) || n.equals( "motivo_do_rejeito" );
  }

  /** Los textos de stress_test (área «estres») en {@code idioma} (en | es | pt; otro = en). */
  public static Rotulos textos( String idioma ) {
    return Rotulos.de( "estres", idioma );
  }

  static String recortar( String s ) {
    String x = s.replaceAll( "\\s+", " " ).trim();
    return x.length() > 400 ? x.substring( 0, 400 ) + "..." : x;
  }
}
