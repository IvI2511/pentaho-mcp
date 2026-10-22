package io.github.ivi2511.pentahomcp.robustez;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    /** «31 read = 17 loaded + 12 rejected + 2 dropped (declared) + 0 lost». */
    public String resumen() {
      if ( abortada ) {
        return "aborted after " + leidas + " rows read (" + cargadas + " loaded, " + rechazadas + " rejected before "
          + "the stop)";
      }
      long dd = 0;
      for ( long x : descartesDeclarados.values() ) {
        dd += x;
      }
      if ( leidas == cargadas + rechazadas + dd + perdidasTotal() ) {
        return leidas + " read = " + cargadas + " loaded + " + rechazadas + " rejected + " + dd
          + " dropped (declared) + " + perdidasTotal() + " lost";
      }
      return leidas + " read; " + cargadas + " reached the loads, " + rechazadas + " rejected, " + dd
        + " dropped (declared), " + perdidasTotal() + " lost (rows fan out to several loads or are merged: see "
        + "aggregates)";
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
    Informe inf = new Informe();
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
        c.vencida ? "the run did not finish in time (timeout)" : "the transformation aborted with " + c.errores
          + " error(s): a bad row stopped the load" + ( c.mensajeError != null ? ": " + recortar( c.mensajeError )
          : "" ) );
      for ( PasoCuenta p : cuentas.values() ) {
        if ( p.errores > 0 ) {
          k.detalles.add( "step '" + p.nombre + "' (" + p.tipo + ") failed with " + p.errores + " error(s)" );
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
          completa.detalles.add( "'" + n.nombre + "' produced " + sale + " rows from " + esperadas
            + " data lines: the input swallowed " + ( esperadas - sale ) + " (open enclosure or a cut line joined "
            + "to the next one)" );
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
        inf.noEvaluados.add( n.nombre + ": its info source sends rows to several steps" );
        continue;
      }
      long entra = recibidas - info;
      if ( entra < 0 ) {
        inf.noEvaluados.add( n.nombre + ": could not separate its main and info rows" );
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
        "the run aborted: the row counts are partial" ) );
    } else if ( inf.perdidas.isEmpty() ) {
      inf.comprobaciones.add( new Comprobacion( "no_silent_loss", EstresResultado.PASA, null ) );
    } else {
      Comprobacion k = new Comprobacion( "no_silent_loss", EstresResultado.FALLA, inf.perdidasTotal()
        + " row(s) disappeared without an error hop, a false branch or a declaration" );
      for ( Map.Entry<String, Long> e : inf.perdidas.entrySet() ) {
        Nodo n = porNombre.get( e.getKey() );
        k.detalles.add( "step '" + e.getKey() + "' (" + ( n == null ? "?" : n.tipo ) + ") lost " + e.getValue()
          + " row(s)" + ( n != null && "FilterRows".equals( n.tipo ) ? ": a filter without a false branch "
            + "drops the rows that do not match; send them to a rejects file with a reason, or declare the drop"
            : "" ) );
      }
      inf.comprobaciones.add( k );
    }

    // --- rejects_have_reason
    if ( rechazos == null || rechazos.isEmpty() ) {
      inf.comprobaciones.add( new Comprobacion( "rejects_have_reason", debenRechazarse > 0 && !abortada
        ? EstresResultado.AVISO : EstresResultado.NO_EVALUADA, "no rejects file in the transformation" ) );
    } else {
      Comprobacion k = new Comprobacion( "rejects_have_reason", EstresResultado.PASA, null );
      for ( Rechazo r : rechazos ) {
        if ( r.sinColumnaMotivo && r.filas > 0 ) {
          k.estado = EstresResultado.AVISO;
          k.detalles.add( "'" + r.paso + "' writes " + r.filas + " rejected row(s) without a reason column "
            + "(motivo/reason)" );
        } else if ( r.sinMotivo > 0 ) {
          k.estado = EstresResultado.AVISO;
          k.detalles.add( "'" + r.paso + "': " + r.sinMotivo + " of " + r.filas + " rejected row(s) have an empty "
            + "reason" );
        }
      }
      inf.comprobaciones.add( k );
    }

    // --- bad_rows_rejected
    if ( abortada || debenRechazarse <= 0 ) {
      inf.comprobaciones.add( new Comprobacion( "bad_rows_rejected", EstresResultado.NO_EVALUADA,
        abortada ? "the run aborted" : "the variant has no rows that must be rejected" ) );
    } else {
      long dd = 0;
      for ( long x : inf.descartesDeclarados.values() ) {
        dd += x;
      }
      long fuera = debenRechazarse - inf.rechazadas - dd;
      inf.comprobaciones.add( fuera > 0 ? new Comprobacion( "bad_rows_rejected", EstresResultado.AVISO, fuera
        + " broken row(s) were not rejected (" + debenRechazarse + " expected, " + inf.rechazadas
        + " rejected): they reached the outputs or were lost" )
        : new Comprobacion( "bad_rows_rejected", EstresResultado.PASA, null ) );
    }

    // --- idempotent (rerun)
    if ( segunda != null ) {
      Comprobacion k = new Comprobacion( "idempotent", EstresResultado.PASA, null );
      boolean segundaAborta = !segunda.terminada || segunda.errores > 0 || segunda.vencida;
      if ( segundaAborta ) {
        k.estado = EstresResultado.FALLA;
        k.mensaje = "the second run on the same database aborted"
          + ( segunda.mensajeError != null ? ": " + recortar( segunda.mensajeError ) : "" );
      } else if ( c.tablas.isEmpty() ) {
        k.estado = EstresResultado.NO_EVALUADA;
        k.mensaje = "no database table to compare";
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
            k.detalles.add( "table " + t.tabla + ": " + t.filas + " rows after the 1st run, " + b.filas
              + " after the 2nd (re-running duplicates rows)" );
          } else if ( t.huellaClaves != null && !t.huellaClaves.equals( b.huellaClaves ) ) {
            if ( !EstresResultado.FALLA.equals( k.estado ) ) {
              k.estado = EstresResultado.AVISO;
            }
            k.detalles.add( "table " + t.tabla + ": same rows but the keys changed (unstable surrogate keys)" );
          }
        }
        if ( !k.detalles.isEmpty() ) {
          k.mensaje = "re-running the load is not idempotent";
        }
      }
      inf.comprobaciones.add( k );
    }
    return inf;
  }

  /** Veredicto de varias variantes: robust (ninguna falla ni aviso), fragile (alguna). */
  public static String veredicto( List<Informe> informes ) {
    for ( Informe i : informes ) {
      if ( i.cuenta( EstresResultado.FALLA ) > 0 || i.cuenta( EstresResultado.AVISO ) > 0 ) {
        return "fragile";
      }
    }
    return "robust";
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

  static String recortar( String s ) {
    String x = s.replaceAll( "\\s+", " " ).trim();
    return x.length() > 400 ? x.substring( 0, 400 ) + "..." : x;
  }
}
