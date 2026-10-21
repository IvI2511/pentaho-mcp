package io.github.ivi2511.pentahomcp;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.filterrows.FilterRowsMeta;
import org.pentaho.di.trans.steps.streamlookup.StreamLookupMeta;

import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo;

/**
 * El {@link Grafo} del diagrama propio de una transformacion o un job: lo mismo que dibujan
 * {@code ReportMotor.mermaidTrans} y {@code mermaidJob}, con los mismos textos ({@link ReportMotor.T}).
 * <ul>
 * <li>Transformacion: un nodo por step en el orden de lectura (titulo = nombre; lineas = tipo de step y
 * "copia/distribuye las filas a N salidas"; las copias en paralelo como marca «×N»; plugin que falta en
 * rojo). Saltos: error (rojo, "error"), verdadero/falso de un Filter rows, "lookup" hacia un Stream lookup
 * (clase info), deshabilitados en gris con "deshabilitado".</li>
 * <li>Job: START como pildora y sin tipo; los saltos sin texto (verde = si termina bien, rojo punteado = si
 * falla, gris = siempre; lo explica la leyenda); solo los deshabilitados llevan "deshabilitado".</li>
 * </ul>
 * Lee la definicion en memoria: no abre conexiones ni ejecuta nada.
 *
 * @dueño WP6
 */
final class GrafoFlujo {

  private GrafoFlujo() {
  }

  /** Grafo del flujo de la transformacion, con los steps en el orden dado (ReportMotor.ordenTrans). */
  static Grafo desde( TransMeta tm, List<StepMeta> orden, ReportMotor.T t ) {
    Grafo g = new Grafo( Grafo.FLUJO, Docs.nombre( tm ) );
    Map<StepMeta, Grafo.Nodo> nodos = new IdentityHashMap<>();
    int i = 1;
    for ( StepMeta sm : orden ) {
      Grafo.Nodo n = g.nodo( "s" + i++, sm.getName() );
      n.lineas.add( NombresPlugin.nombre( StepPluginType.class, sm.getStepID(), t.idioma ) );
      n.copias = Math.max( 1, sm.getCopies() );
      int salidas = TransOps.salidasNormales( tm, sm );
      if ( salidas >= 2 ) {
        n.multi = true;
        n.lineas.add( sm.isDistributes() ? t.n( "distributes", salidas ) : t.n( "copies_to", salidas ) );
      }
      if ( sm.isMissing() ) {
        n.falta = true;
        n.lineas.add( t.s( "missing_plugin" ) );
      }
      nodos.put( sm, n );
    }
    for ( int k = 0; k < tm.nrTransHops(); k++ ) {
      TransHopMeta h = tm.getTransHop( k );
      Grafo.Nodo a = nodos.get( h.getFromStep() );
      Grafo.Nodo b = nodos.get( h.getToStep() );
      if ( a == null || b == null ) {
        continue;
      }
      String clase = Grafo.NORMAL;
      String rotulo = null;
      if ( h.isErrorHop() ) {
        clase = Grafo.ERROR;
        rotulo = t.s( "error" );
      } else {
        String[] rol = rolHop( h.getFromStep(), h.getToStep(), t );
        if ( rol != null ) {
          clase = rol[ 0 ];
          rotulo = rol[ 1 ];
        }
      }
      Grafo.Arista e = g.arista( a, b, clase );
      e.activa = h.isEnabled();
      if ( !h.isEnabled() ) {
        rotulo = rotulo == null ? t.s( "disabled" ) : rotulo + ", " + t.s( "disabled" );
      }
      e.rotulo = rotulo;
    }
    return g;
  }

  /** Grafo del job, con las entradas en el orden dado (ReportMotor.ordenJob). */
  static Grafo desde( JobMeta jm, List<JobEntryCopy> orden, ReportMotor.T t ) {
    Grafo g = new Grafo( Grafo.JOB, Docs.nombre( jm ) );
    Map<JobEntryCopy, Grafo.Nodo> nodos = new IdentityHashMap<>();
    int i = 1;
    for ( JobEntryCopy c : orden ) {
      Grafo.Nodo n = g.nodo( "s" + i++, c.getName() );
      n.inicio = c.isStart();
      if ( !c.isStart() ) {
        String plugin = c.getEntry() == null ? null : c.getEntry().getPluginId();
        n.lineas.add( c.isDummy() ? "Dummy" : NombresPlugin.nombre( JobEntryPluginType.class, plugin, t.idioma ) );
      }
      if ( c.isMissing() ) {
        n.falta = true;
        n.lineas.add( t.s( "missing_plugin" ) );
      }
      nodos.put( c, n );
    }
    for ( int k = 0; k < jm.nrJobHops(); k++ ) {
      JobHopMeta h = jm.getJobHop( k );
      Grafo.Nodo a = nodos.get( h.getFromEntry() );
      Grafo.Nodo b = nodos.get( h.getToEntry() );
      if ( a == null || b == null ) {
        continue;
      }
      String clase = h.isUnconditional() ? Grafo.SIEMPRE : h.getEvaluation() ? Grafo.OK : Grafo.FALLA;
      Grafo.Arista e = g.arista( a, b, clase );
      e.activa = h.isEnabled();
      e.rotulo = h.isEnabled() ? null : t.s( "disabled" );
    }
    return g;
  }

  /**
   * {clase, rotulo} del salto segun los steps: verdadero/falso de un Filter rows, la fuente de un Stream
   * lookup; null si es un salto comun. (Lo mismo que ReportMotor.rolHop.)
   */
  private static String[] rolHop( StepMeta desde, StepMeta hacia, ReportMotor.T t ) {
    try {
      StepMetaInterface a = desde.getStepMetaInterface();
      if ( a instanceof FilterRowsMeta ) {
        FilterRowsMeta f = (FilterRowsMeta) a;
        if ( hacia.getName().equals( f.getTrueStepname() ) ) {
          return new String[] { Grafo.NORMAL, t.s( "true" ) };
        }
        if ( hacia.getName().equals( f.getFalseStepname() ) ) {
          return new String[] { Grafo.NORMAL, t.s( "false" ) };
        }
      }
      StepMetaInterface b = hacia.getStepMetaInterface();
      if ( b instanceof StreamLookupMeta ) {
        String fuente = ( (StreamLookupMeta) b ).getStepIOMeta().getInfoStreams().get( 0 ).getStepname();
        if ( desde.getName().equals( fuente ) ) {
          return new String[] { Grafo.INFO, t.s( "lookup" ) };
        }
      }
    } catch ( Exception ignored ) {
      // sin rol
    }
    return null;
  }
}
