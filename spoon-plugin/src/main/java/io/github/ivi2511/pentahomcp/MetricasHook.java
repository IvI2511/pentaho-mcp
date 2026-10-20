package io.github.ivi2511.pentahomcp;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import org.pentaho.di.core.extension.ExtensionPoint;
import org.pentaho.di.core.extension.ExtensionPointInterface;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.util.EnvUtil;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransAdapter;
import org.pentaho.di.trans.TransMeta;

/**
 * OPCIONAL y APAGADO por defecto (diseno §2.4.2): con {@code PENTAHO_LIVE_METRICS=on} (system property,
 * linea en kettle.properties o variable de entorno) activa en TODAS las corridas la recoleccion de
 * metricas (lo mismo que "Gather performance metrics") y los snapshots de rendimiento, asi la seccion
 * de metricas de la ultima corrida tiene un Gantt real y la linea de filas por intervalo.
 * <p>
 * Los snapshots son un flag de la TransMeta (la del documento, en Spoon): se pone al preparar y se
 * restaura al terminar, porque si no se guardaria en el .ktr. Kettle lee el flag tambien al terminar
 * (para cancelar el Timer de snapshots), asi que no se puede restaurar antes. Si prepareExecution
 * falla antes de inicializar los steps (no se disparan los listeners de fin), la proxima corrida de esa
 * misma TransMeta lo restaura ({@link #PENDIENTES}).
 * <p>
 * Que el ExtensionPointPluginType encuentre esta clase en la carpeta del plugin y que funcione dentro
 * de Spoon: no probado [NP]. Fuera de Spoon se probo registrandola a mano (PruebaMetricas).
 *
 * @dueño WP4
 */
@ExtensionPoint( id = "PentahoLiveMetricas", extensionPointId = "TransformationPrepareExecution",
  description = "Pentaho Live: gathers performance metrics on every run (only with PENTAHO_LIVE_METRICS=on)" )
public class MetricasHook implements ExtensionPointInterface {

  /** Nombre de la propiedad que lo enciende (on, true, y, yes, 1). */
  public static final String PROPIEDAD = "PENTAHO_LIVE_METRICS";

  /** TransMeta a las que este hook les puso el flag de snapshots y todavia no se lo saco. */
  static final Map<TransMeta, Boolean> PENDIENTES = Collections.synchronizedMap( new WeakHashMap<TransMeta, Boolean>() );

  public MetricasHook() {
  }

  /** Si esta encendido. Nunca lanza. */
  public static boolean activo() {
    String v = null;
    try {
      v = EnvUtil.getSystemProperty( PROPIEDAD );
    } catch ( Throwable ignored ) {
      // EnvUtil siempre esta; por las dudas
    }
    if ( v == null || v.trim().isEmpty() ) {
      try {
        v = System.getenv( PROPIEDAD );
      } catch ( Throwable ignored ) {
        v = null;
      }
    }
    if ( v == null ) {
      return false;
    }
    String x = v.trim().toLowerCase( Locale.ROOT );
    return x.equals( "on" ) || x.equals( "true" ) || x.equals( "y" ) || x.equals( "yes" ) || x.equals( "1" );
  }

  @Override
  public void callExtensionPoint( LogChannelInterface log, Object o ) {
    try {
      if ( !( o instanceof Trans ) || !activo() ) {
        return;
      }
      preparar( (Trans) o );
    } catch ( Throwable e ) {
      // nunca cortar una corrida del usuario por esto
      try {
        if ( log != null ) {
          log.logDebug( "Pentaho Live metrics hook: " + e );
        }
      } catch ( Throwable ignored ) {
        // nada
      }
    }
  }

  /** Activa metricas y snapshots en la corrida, y deja programado devolver el flag de la TransMeta. */
  static void preparar( Trans t ) {
    t.setGatheringMetrics( true );
    final TransMeta tm = t.getTransMeta();
    if ( tm == null ) {
      return;
    }
    boolean nuestro = PENDIENTES.containsKey( tm );
    if ( tm.isCapturingStepPerformanceSnapShots() && !nuestro ) {
      return; // el usuario ya lo tenia activado: no tocar nada
    }
    tm.setCapturingStepPerformanceSnapShots( true );
    PENDIENTES.put( tm, Boolean.TRUE );
    t.addTransListener( new TransAdapter() {
      @Override
      public void transFinished( Trans x ) {
        // Kettle ya cancelo el Timer (su listener va primero): ahora se puede devolver el flag
        tm.setCapturingStepPerformanceSnapShots( false );
        PENDIENTES.remove( tm );
      }
    } );
  }
}
