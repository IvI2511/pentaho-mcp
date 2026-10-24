package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.KettleLogStore;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.logging.LoggingObjectInterface;
import org.pentaho.di.core.logging.LoggingObjectType;
import org.pentaho.di.core.logging.SimpleLoggingObject;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowListener;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMetaDataCombi;

import io.github.ivi2511.pentahomcp.robustez.Balance;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.Corrida;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.PasoCuenta;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.TablaCuenta;

/**
 * La JVM hija de {@code stress_test} (docs/robustez-diseno.md §5.3): {@code EstresMain <trabajo.json>} corre, una tras
 * otra, las copias ya reescritas por EstresSandbox (una por variante; rerun, dos veces sobre la misma BD del sandbox),
 * cuenta por step con un RowListener y las cuentas de BaseStep, y escribe {@code resultado} de cada corrida en cuanto
 * termina (si el padre la mata por tiempo, lo ya escrito queda). Nunca la carga Spoon: la lanza EstresProcesos con
 * KETTLE_HOME, user.home y java.io.tmpdir del sandbox.
 * <p>
 * Trabajo: {@code {"timeout_ms": 60000, "variants": [{"id", "ktr", "result", "rounds", "tables": [{"connection",
 * "table", "keys": [..]}]}]}}.
 *
 * @dueño R2c
 */
public final class EstresMain {

  private static final ObjectMapper OM = new ObjectMapper();

  private EstresMain() {
  }

  public static void main( String[] args ) {
    int codigo = 0;
    try {
      JsonNode trabajo = OM.readTree( new File( args[ 0 ] ) );
      KettleEnvironment.init();
      long timeout = trabajo.path( "timeout_ms" ).asLong( 60000 );
      for ( JsonNode v : trabajo.path( "variants" ) ) {
        int vueltas = Math.max( 1, v.path( "rounds" ).asInt( 1 ) );
        for ( int r = 1; r <= vueltas; r++ ) {
          Corrida c = correr( v, r, timeout );
          String destino = v.path( "result" ).asText() + ( r > 1 ? "." + r : "" );
          File tmp = new File( destino + ".tmp" );
          Files.write( tmp.toPath(), OM.writerWithDefaultPrettyPrinter().writeValueAsBytes( c.json( OM ) ) );
          File f = new File( destino );
          if ( f.exists() ) {
            f.delete();
          }
          if ( !tmp.renameTo( f ) ) {
            Files.copy( tmp.toPath(), f.toPath() );
          }
          System.out.println( "=== " + c.variante + " round " + r + ": finished=" + c.terminada + " errors="
            + c.errores + " ms=" + c.ms );
        }
      }
    } catch ( Throwable t ) {
      t.printStackTrace( System.out );
      codigo = 1;
    }
    System.out.flush();
    System.exit( codigo );
  }

  /** Una corrida de una variante: nunca lanza; lo que falla queda en la Corrida. */
  static Corrida correr( JsonNode v, int vuelta, long timeoutMs ) {
    Corrida c = new Corrida();
    c.variante = v.path( "id" ).asText();
    c.vuelta = vuelta;
    c.iniciada = false; // hasta que los steps arranquen
    long t0 = System.currentTimeMillis();
    Trans t = null;
    List<Contador> contadores = new ArrayList<Contador>();
    TransMeta tm = null;
    try {
      tm = new TransMeta( v.path( "ktr" ).asText() );
      for ( Iterator<Map.Entry<String, JsonNode>> it = v.path( "params" ).fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        tm.setParameterValue( e.getKey(), e.getValue().asText() );
      }
      for ( Iterator<Map.Entry<String, JsonNode>> it = v.path( "variables" ).fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        tm.setVariable( e.getKey(), e.getValue().asText() );
      }
      t = new Trans( tm );
      for ( Iterator<Map.Entry<String, JsonNode>> it = v.path( "variables" ).fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        t.setVariable( e.getKey(), e.getValue().asText() );
      }
      t.setLogLevel( LogLevel.BASIC );
      t.activateParameters();
      t.prepareExecution( null );
      for ( StepMetaDataCombi combi : t.getSteps() ) {
        Contador k = new Contador( combi );
        combi.step.addRowListener( k );
        contadores.add( k );
      }
      t.startThreads();
      c.iniciada = true;
      long fin = t0 + timeoutMs;
      while ( !t.isFinished() && System.currentTimeMillis() < fin ) {
        Thread.sleep( 50 );
      }
      if ( !t.isFinished() ) {
        c.vencida = true;
        t.stopAll();
        t.waitUntilFinished();
      }
      c.errores = t.getErrors();
      c.terminada = !c.vencida && t.isFinished();
    } catch ( Throwable e ) {
      c.terminada = false;
      c.errores = Math.max( 1, c.errores );
      c.mensajeError = e.getMessage() == null ? e.toString() : e.getMessage();
      if ( t != null ) {
        try {
          t.stopAll();
        } catch ( Throwable ignored ) {
          // ya paró
        }
      }
    }
    c.ms = System.currentTimeMillis() - t0;
    if ( t != null ) {
      for ( Contador k : contadores ) {
        StepInterface s = k.combi.step;
        PasoCuenta p = new PasoCuenta();
        p.nombre = k.combi.stepname;
        p.tipo = k.combi.stepMeta.getStepID();
        p.copia = k.combi.copy;
        p.leidas = s.getLinesRead();
        p.entrada = s.getLinesInput();
        p.escritas = s.getLinesWritten();
        p.salida = s.getLinesOutput();
        p.actualizadas = s.getLinesUpdated();
        p.rechazadas = s.getLinesRejected();
        p.errores = s.getErrors();
        p.puestas = k.puestas.get();
        p.puestasError = k.error.get();
        p.recibidas = k.recibidas.get();
        c.pasos.add( p );
      }
      String log = log( t );
      c.colaLog = log.length() > 3000 ? log.substring( log.length() - 3000 ) : log;
      // el mensaje de cada step que falló, del log entero: el padre separa una fila mala de una BD que falta
      Map<String, String> porPaso = Balance.erroresPorPaso( log );
      for ( PasoCuenta p : c.pasos ) {
        if ( p.errores > 0 ) {
          p.mensaje = porPaso.get( p.nombre );
        }
      }
      if ( c.errores > 0 && c.mensajeError == null ) {
        c.mensajeError = primerError( log );
      }
    }
    if ( tm != null ) {
      for ( JsonNode x : v.path( "tables" ) ) {
        TablaCuenta tc = contarTabla( tm, x );
        if ( tc != null ) {
          c.tablas.add( tc );
        }
      }
    }
    return c;
  }

  private static String log( Trans t ) {
    try {
      return KettleLogStore.getAppender().getBuffer( t.getLogChannelId(), false ).toString();
    } catch ( Throwable e ) {
      return "";
    }
  }

  /**
   * Los primeros mensajes de ERROR distintos del log (sin fecha ni prefijo de versión) y, tras una excepción, su
   * primera línea de detalle (las siguientes no llevan prefijo de log), unidos con " | ".
   */
  static String primerError( String log ) {
    List<String> l = new ArrayList<String>();
    boolean tras = false;
    for ( String x : log.split( "\r?\n" ) ) {
      // "2026/10/22 00:03:01 - Convert types.0 - ERROR (version …) : mensaje" o una línea de detalle sin ERROR
      String resto = x.replaceFirst( "^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2} - ", "" );
      boolean conFecha = !resto.equals( x );
      String paso = "";
      if ( conFecha ) {
        int g = resto.indexOf( " - " );
        if ( g >= 0 ) {
          paso = resto.substring( 0, g ).trim();
          resto = resto.substring( g + 3 );
        }
      }
      String t = resto.trim();
      if ( t.startsWith( "ERROR" ) ) {
        int dos = t.indexOf( ") : " );
        String m = dos >= 0 ? t.substring( dos + 4 ).trim() : t.substring( 5 ).trim();
        tras = m.isEmpty() || m.endsWith( ":" ) || m.endsWith( "Exception" );
        agregar( l, m.isEmpty() ? null : ( paso.isEmpty() ? "" : paso + ": " ) + m );
      } else if ( tras && !t.isEmpty() && !t.startsWith( "at " ) && !t.startsWith( "..." ) ) {
        agregar( l, t );
        tras = false;
      }
      if ( l.size() >= 5 ) {
        break;
      }
    }
    return l.isEmpty() ? null : String.join( " | ", l );
  }

  private static void agregar( List<String> l, String t ) {
    if ( t != null && !l.contains( t ) ) {
      l.add( t );
    }
  }

  /** Filas y huellas (claves y filas, ordenadas) de una tabla de una conexión de archivo del sandbox. */
  static TablaCuenta contarTabla( TransMeta tm, JsonNode x ) {
    String con = x.path( "connection" ).asText();
    String tabla = x.path( "table" ).asText();
    DatabaseMeta dm = tm.findDatabase( con );
    if ( dm == null || tabla.isEmpty() ) {
      return null;
    }
    LoggingObjectInterface lo = new SimpleLoggingObject( "stress_test", LoggingObjectType.GENERAL, null );
    Database db = new Database( lo, dm );
    TablaCuenta t = new TablaCuenta();
    t.conexion = con;
    t.tabla = tabla;
    try {
      db.connect();
      Connection cn = db.getConnection();
      List<String> claves = new ArrayList<String>();
      for ( JsonNode k : x.path( "keys" ) ) {
        claves.add( k.asText().toLowerCase( java.util.Locale.ROOT ) );
      }
      List<String> filas = new ArrayList<String>();
      List<String> llaves = new ArrayList<String>();
      try ( Statement st = cn.createStatement(); ResultSet rs = st.executeQuery( "SELECT * FROM " + tabla ) ) {
        ResultSetMetaData md = rs.getMetaData();
        while ( rs.next() ) {
          StringBuilder f = new StringBuilder();
          StringBuilder k = new StringBuilder();
          for ( int i = 1; i <= md.getColumnCount(); i++ ) {
            String val = String.valueOf( rs.getObject( i ) );
            f.append( val ).append( '\u0001' );
            String col = md.getColumnName( i ).toLowerCase( java.util.Locale.ROOT );
            if ( claves.contains( col ) || ( claves.isEmpty() && ( col.startsWith( "pk" ) || col.startsWith( "sk" )
              || col.endsWith( "_id" ) || col.equals( "id" ) ) ) ) {
              k.append( col ).append( '=' ).append( val ).append( '\u0001' );
            }
          }
          filas.add( f.toString() );
          llaves.add( k.toString() );
        }
      }
      t.filas = filas.size();
      Collections.sort( filas );
      Collections.sort( llaves );
      t.huellaFilas = huella( filas );
      t.huellaClaves = huella( llaves );
    } catch ( Throwable e ) {
      t.filas = -1;
      t.huellaFilas = "error: " + e.getMessage();
    } finally {
      db.disconnect();
    }
    return t;
  }

  private static String huella( List<String> l ) throws Exception {
    MessageDigest md = MessageDigest.getInstance( "SHA-256" );
    for ( String s : l ) {
      md.update( s.getBytes( StandardCharsets.UTF_8 ) );
      md.update( (byte) '\n' );
    }
    StringBuilder sb = new StringBuilder();
    byte[] b = md.digest();
    for ( int i = 0; i < 8; i++ ) {
      sb.append( String.format( "%02x", b[ i ] ) );
    }
    return sb.toString();
  }

  /** Cuenta lo que una copia de un step lee, manda y manda por error. */
  private static final class Contador implements RowListener {
    final StepMetaDataCombi combi;
    final AtomicLong recibidas = new AtomicLong();
    final AtomicLong puestas = new AtomicLong();
    final AtomicLong error = new AtomicLong();

    Contador( StepMetaDataCombi combi ) {
      this.combi = combi;
    }

    @Override
    public void rowReadEvent( RowMetaInterface rowMeta, Object[] row ) throws KettleStepException {
      recibidas.incrementAndGet();
    }

    @Override
    public void rowWrittenEvent( RowMetaInterface rowMeta, Object[] row ) throws KettleStepException {
      puestas.incrementAndGet();
    }

    @Override
    public void errorRowWrittenEvent( RowMetaInterface rowMeta, Object[] row ) throws KettleStepException {
      error.incrementAndGet();
    }
  }
}
