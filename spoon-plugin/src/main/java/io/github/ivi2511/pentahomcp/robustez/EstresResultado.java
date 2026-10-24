package io.github.ivi2511.pentahomcp.robustez;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Modelo de {@code stress_test} (docs/robustez-diseno.md §5.3-§5.4): lo que mide la hija en una variante
 * ({@link Corrida}, con las cuentas por step) y las comprobaciones del padre ({@link Comprobacion}). Sin Kettle: la hija
 * lo escribe en JSON y el padre lo lee, así las comprobaciones se prueban con JSON fijos.
 *
 * @dueño R2c
 */
public final class EstresResultado {

  public static final String PASA = "pass";
  public static final String AVISO = "warn";
  public static final String FALLA = "fail";
  public static final String NO_EVALUADA = "not_evaluated";

  private EstresResultado() {
  }

  /** Las cuentas de una copia de un step (las de BaseStep más las del RowListener). */
  public static final class PasoCuenta {
    public String nombre;
    public String tipo;
    public int copia;
    /** getLinesRead: filas que leyó de otros steps (incluye flujos info). */
    public long leidas;
    /** getLinesInput: filas que leyó de un archivo o una BD. */
    public long entrada;
    public long escritas;
    /** getLinesOutput: filas que escribió en un archivo o una BD. */
    public long salida;
    public long actualizadas;
    public long rechazadas;
    public long errores;
    /** rowWrittenEvent: filas que mandó por el flujo normal. */
    public long puestas;
    /** errorRowWrittenEvent: filas que mandó por el hop de error. */
    public long puestasError;
    /** rowReadEvent: filas que leyó de otros steps. */
    public long recibidas;
    /** Los mensajes de ERROR de este step en el log (sin la pila), o null si no falló o no hay. */
    public String mensaje;

    public ObjectNode json( ObjectMapper om ) {
      ObjectNode o = om.createObjectNode();
      o.put( "name", nombre ).put( "type", tipo ).put( "copy", copia ).put( "read", leidas ).put( "input", entrada )
        .put( "written", escritas ).put( "output", salida ).put( "updated", actualizadas )
        .put( "rejected", rechazadas ).put( "errors", errores ).put( "put", puestas ).put( "put_error", puestasError )
        .put( "received", recibidas );
      if ( mensaje != null ) {
        o.put( "error_message", mensaje );
      }
      return o;
    }

    public static PasoCuenta desde( JsonNode o ) {
      PasoCuenta p = new PasoCuenta();
      p.nombre = o.path( "name" ).asText();
      p.tipo = o.path( "type" ).asText();
      p.copia = o.path( "copy" ).asInt();
      p.leidas = o.path( "read" ).asLong();
      p.entrada = o.path( "input" ).asLong();
      p.escritas = o.path( "written" ).asLong();
      p.salida = o.path( "output" ).asLong();
      p.actualizadas = o.path( "updated" ).asLong();
      p.rechazadas = o.path( "rejected" ).asLong();
      p.errores = o.path( "errors" ).asLong();
      p.puestas = o.path( "put" ).asLong();
      p.puestasError = o.path( "put_error" ).asLong();
      p.recibidas = o.path( "received" ).asLong();
      p.mensaje = o.hasNonNull( "error_message" ) ? o.get( "error_message" ).asText() : null;
      return p;
    }

    /** Suma otra copia del mismo step. */
    void sumar( PasoCuenta x ) {
      leidas += x.leidas;
      entrada += x.entrada;
      escritas += x.escritas;
      salida += x.salida;
      actualizadas += x.actualizadas;
      rechazadas += x.rechazadas;
      errores += x.errores;
      puestas += x.puestas;
      puestasError += x.puestasError;
      recibidas += x.recibidas;
      if ( mensaje == null ) {
        mensaje = x.mensaje;
      }
    }
  }

  /** Filas y huellas de una tabla de una BD del sandbox (rerun). */
  public static final class TablaCuenta {
    public String conexion;
    public String tabla;
    public long filas;
    public String huellaClaves;
    public String huellaFilas;

    public static TablaCuenta desde( JsonNode o ) {
      TablaCuenta t = new TablaCuenta();
      t.conexion = o.path( "connection" ).asText();
      t.tabla = o.path( "table" ).asText();
      t.filas = o.path( "rows" ).asLong();
      t.huellaClaves = o.path( "keys_hash" ).asText( null );
      t.huellaFilas = o.path( "rows_hash" ).asText( null );
      return t;
    }

    public ObjectNode json( ObjectMapper om ) {
      ObjectNode o = om.createObjectNode();
      o.put( "connection", conexion ).put( "table", tabla ).put( "rows", filas ).put( "keys_hash", huellaClaves )
        .put( "rows_hash", huellaFilas );
      return o;
    }
  }

  /** Una corrida de la hija sobre una variante (§5.3). */
  public static final class Corrida {
    public String variante;
    /** Número de corrida dentro de la variante (rerun corre dos veces: 1 y 2). */
    public int vuelta = 1;
    public boolean terminada;
    /**
     * Los steps llegaron a arrancar (pasó la inicialización). Si no, ninguna fila se procesó y un aborto no puede ser
     * por una fila mala. Sin el dato (un JSON de antes de 0.6.1), true.
     */
    public boolean iniciada = true;
    public long errores;
    public boolean vencida;
    public long ms;
    public String mensajeError;
    public String colaLog;
    public final List<PasoCuenta> pasos = new ArrayList<PasoCuenta>();
    public final List<TablaCuenta> tablas = new ArrayList<TablaCuenta>();

    public ObjectNode json( ObjectMapper om ) {
      ObjectNode o = om.createObjectNode();
      o.put( "variant", variante ).put( "round", vuelta ).put( "finished", terminada ).put( "started", iniciada ).put( "errors", errores )
        .put( "timed_out", vencida ).put( "ms", ms ).put( "error_message", mensajeError ).put( "log_tail", colaLog );
      ArrayNode a = o.putArray( "steps" );
      for ( PasoCuenta p : pasos ) {
        a.add( p.json( om ) );
      }
      ArrayNode t = o.putArray( "tables" );
      for ( TablaCuenta x : tablas ) {
        t.add( x.json( om ) );
      }
      return o;
    }

    public static Corrida desde( JsonNode o ) {
      Corrida c = new Corrida();
      c.variante = o.path( "variant" ).asText();
      c.vuelta = o.path( "round" ).asInt( 1 );
      c.terminada = o.path( "finished" ).asBoolean();
      c.iniciada = o.path( "started" ).asBoolean( true );
      c.errores = o.path( "errors" ).asLong();
      c.vencida = o.path( "timed_out" ).asBoolean();
      c.ms = o.path( "ms" ).asLong();
      c.mensajeError = o.hasNonNull( "error_message" ) ? o.get( "error_message" ).asText() : null;
      c.colaLog = o.hasNonNull( "log_tail" ) ? o.get( "log_tail" ).asText() : null;
      for ( JsonNode p : o.path( "steps" ) ) {
        c.pasos.add( PasoCuenta.desde( p ) );
      }
      for ( JsonNode t : o.path( "tables" ) ) {
        c.tablas.add( TablaCuenta.desde( t ) );
      }
      return c;
    }

    /** Las cuentas por step, sumando las copias (en el orden en que aparecen). */
    public Map<String, PasoCuenta> porPaso() {
      Map<String, PasoCuenta> m = new LinkedHashMap<String, PasoCuenta>();
      for ( PasoCuenta p : pasos ) {
        PasoCuenta x = m.get( p.nombre );
        if ( x == null ) {
          x = new PasoCuenta();
          x.nombre = p.nombre;
          x.tipo = p.tipo;
          m.put( p.nombre, x );
        }
        x.sumar( p );
      }
      return m;
    }
  }

  /** Una comprobación del padre (§5.4). */
  public static final class Comprobacion {
    public final String id;
    public String estado;
    public String mensaje;
    public final List<String> detalles = new ArrayList<String>();

    public Comprobacion( String id, String estado, String mensaje ) {
      this.id = id;
      this.estado = estado;
      this.mensaje = mensaje;
    }

    public ObjectNode json( ObjectMapper om ) {
      ObjectNode o = om.createObjectNode();
      o.put( "id", id ).put( "status", estado );
      if ( mensaje != null ) {
        o.put( "message", mensaje );
      }
      if ( !detalles.isEmpty() ) {
        ArrayNode a = o.putArray( "details" );
        for ( String d : detalles ) {
          a.add( d );
        }
      }
      return o;
    }
  }
}
