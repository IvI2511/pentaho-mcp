package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.metastore.api.IMetaStore;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.robustez.Cambio;
import io.github.ivi2511.pentahomcp.robustez.Propuesta;

/**
 * harden_etl, paso 2 (hilo de UI sobre el documento vivo; o un hilo de trabajo sobre una copia en dry_run y en las
 * pruebas): resuelve TODOS los nombres antes de tocar nada (una propuesta que ya no encaja va a skipped entera) y
 * aplica en un orden fijo, todo en un {@link UndoGrupo} = un Ctrl+Z: (1) steps nuevos, (2) hops que se quitan,
 * (3) hops nuevos, (4) reconfiguraciones, (5) manejo de errores, (6) parámetros (con {@link UndoEfecto}).
 */
final class RobustecerAplicar {

  private RobustecerAplicar() {
  }

  /** Lo que hizo aplicar(). */
  static final class Hecho {
    final ArrayNode aplicados = Tools.arr();
    /** {key, motivo}. */
    final List<String[]> saltados = new ArrayList<String[]>();
    final Set<String> notas = new LinkedHashSet<String>();
    int cambios;
    int pasosDeshacer;
  }

  static Hecho aplicar( TransMeta tm, List<Propuesta> elegidas, UndoGrupo undo, IMetaStore ms ) {
    return aplicar( tm, elegidas, undo, ms, null );
  }

  /** Igual, con los motivos y notas en el idioma de los textos (null = en). */
  static Hecho aplicar( TransMeta tm, List<Propuesta> elegidas, UndoGrupo undo, IMetaStore ms, String textos ) {
    Rotulos tx = RobustecerAnalisis.textos( textos );
    Hecho h = new Hecho();
    // 1) resolver: cada propuesta contra el documento tal como está, sin tocarlo
    List<Propuesta> ok = new ArrayList<Propuesta>();
    Map<String, String> hopsQuitados = new HashMap<String, String>();
    for ( Propuesta x : elegidas ) {
      String motivo = resolver( tm, x, hopsQuitados, tx );
      if ( motivo != null ) {
        h.saltados.add( new String[] { x.key, motivo } );
        continue;
      }
      for ( Cambio c : x.cambios ) {
        if ( Cambio.REMOVE_HOP.equals( c.op ) ) {
          hopsQuitados.put( c.firma(), x.key );
        }
      }
      ok.add( x );
    }
    // los cambios de todas, sin repetir (las colas compartidas se crean una vez)
    Map<String, Cambio> todos = new LinkedHashMap<String, Cambio>();
    for ( Propuesta x : ok ) {
      for ( Cambio c : x.cambios ) {
        if ( !todos.containsKey( c.firma() ) ) {
          todos.put( c.firma(), c );
        }
      }
    }
    if ( todos.isEmpty() ) {
      return h;
    }
    try {
      ejecutar( tm, new ArrayList<Cambio>( todos.values() ), undo, ms, h, tx );
      for ( Propuesta x : ok ) {
        ObjectNode o = h.aplicados.addObject();
        o.put( "key", x.key ).put( "recipe", x.receta ).put( "step", x.step );
      }
    } catch ( Exception e ) {
      h.saltados.add( new String[] { "*", tx.f( "apply_failed", RunMotor.primeraLinea( e ) ) } );
    }
    h.pasosDeshacer = Math.min( 1, undo.acciones() );
    return h;
  }

  /** null si la propuesta se puede aplicar tal cual; si no, el motivo. */
  private static String resolver( TransMeta tm, Propuesta x, Map<String, String> hopsQuitados, Rotulos tx ) {
    Set<String> nuevos = new HashSet<String>();
    for ( Cambio c : x.cambios ) {
      if ( Cambio.ADD_STEP.equals( c.op ) ) {
        nuevos.add( c.get( "name" ).toLowerCase( Locale.ROOT ) );
        if ( tm.findStep( c.get( "name" ) ) != null ) {
          return tx.f( "skip_step_exists", c.get( "name" ) );
        }
      }
    }
    for ( Cambio c : x.cambios ) {
      for ( String k : new String[] { "step", "from", "to", "target" } ) {
        String n = c.get( k );
        if ( n != null && !nuevos.contains( n.toLowerCase( Locale.ROOT ) ) && tm.findStep( n ) == null ) {
          return tx.f( "skip_no_step", n );
        }
      }
      if ( Cambio.REMOVE_HOP.equals( c.op ) ) {
        if ( hop( tm, c.get( "from" ), c.get( "to" ) ) == null ) {
          return tx.f( "skip_no_hop", c.get( "from" ), c.get( "to" ) );
        }
        String otra = hopsQuitados.get( c.firma() );
        if ( otra != null ) {
          return tx.f( "skip_conflict", otra, c.get( "from" ), c.get( "to" ) );
        }
      }
      if ( Cambio.ADD_HOP.equals( c.op ) && !nuevos.contains( c.get( "from" ).toLowerCase( Locale.ROOT ) )
        && !nuevos.contains( c.get( "to" ).toLowerCase( Locale.ROOT ) )
        && hop( tm, c.get( "from" ), c.get( "to" ) ) != null ) {
        return tx.f( "skip_hop_exists", c.get( "from" ), c.get( "to" ) );
      }
      if ( Cambio.ADD_PARAMETER.equals( c.op ) && LintArreglos.tieneParametro( tm, c.get( "name" ) ) ) {
        return tx.f( "skip_param_exists", c.get( "name" ) );
      }
    }
    return null;
  }

  private static TransHopMeta hop( TransMeta tm, String de, String a ) {
    StepMeta f = tm.findStep( de );
    StepMeta t = tm.findStep( a );
    return f == null || t == null ? null : tm.findTransHop( f, t, true );
  }

  /** Clon del step con su propio StepErrorMeta (StepMeta.clone lo comparte): para el undo. */
  static StepMeta instantanea( StepMeta s ) {
    StepMeta c = (StepMeta) s.clone();
    c.setStepErrorMeta( s.getStepErrorMeta() == null ? null : s.getStepErrorMeta().clone() );
    return c;
  }

  /**
   * El «antes» de un step al que se le pone manejo de errores. StepMeta.replaceMeta (lo que hace Ctrl+Z en Spoon) solo
   * copia el StepErrorMeta si el de antes no es null: con null, deshacer dejaría el manejo de errores habilitado hacia
   * un step que ya no está (y la transformación fallaría al correr). Kettle no deja volver a null, así que si no tenía,
   * el «antes» lleva un {@link SinManejoDeErrores}: apagado, sin destino y que no se escribe en el .ktr. Tras Ctrl+Z el
   * step corre como antes y guardar da el mismo XML que antes de harden_etl.
   */
  static StepMeta instantaneaSinErrores( TransMeta tm, StepMeta s ) {
    StepMeta c = instantanea( s );
    if ( c.getStepErrorMeta() == null ) {
      c.setStepErrorMeta( new SinManejoDeErrores( tm, c ) );
    }
    return c;
  }

  /**
   * «Este step no tenía manejo de errores», en el lugar del null que Ctrl+Z no sabe restaurar: apagado, sin destino y
   * con getXML vacío mientras siga así (TransMeta.getXML no escribe nada más por él, ni un salto de línea), así que el
   * .ktr guardado después de deshacer es idéntico al de antes. StepErrorMeta.clone es Object.clone: las copias de
   * replaceMeta siguen siendo de esta clase. Si alguien lo habilita o le pone destino (el diálogo de Spoon edita este
   * mismo objeto), se guarda como cualquier otro.
   */
  static final class SinManejoDeErrores extends StepErrorMeta {

    SinManejoDeErrores( TransMeta tm, StepMeta s ) {
      super( tm, s );
      setEnabled( false );
    }

    /** Sigue sin decir nada: apagado y sin destino. */
    boolean vacio() {
      return !isEnabled() && getTargetStep() == null;
    }

    @Override
    public String getXML() {
      return vacio() ? "" : super.getXML();
    }
  }

  /** El manejo de errores del step, o null si no tiene (o solo el «no tenía» que deja Ctrl+Z). */
  static StepErrorMeta manejoDeErrores( StepMeta s ) {
    StepErrorMeta m = s.getStepErrorMeta();
    return m instanceof SinManejoDeErrores && ( (SinManejoDeErrores) m ).vacio() ? null : m;
  }

  private static void ejecutar( TransMeta tm, List<Cambio> cambios, UndoGrupo undo, IMetaStore ms, Hecho h,
                                Rotulos tx )
    throws Exception {
    // (1) steps nuevos (construidos todos antes de agregar ninguno: si uno no carga, no se toca nada)
    List<StepMeta> nuevos = new ArrayList<StepMeta>();
    for ( Cambio c : cambios ) {
      if ( Cambio.ADD_STEP.equals( c.op ) ) {
        PluginInterface pl = TransOps.plugin( c.get( "type" ) );
        String cuerpo = Xml.mezclar( TransOps.plantilla( pl ), c.xml == null ? "" : c.xml, Xml.TAGS_ENVOLTORIO_STEP );
        StepMeta s = TransOps.construir( tm, TransOps.id( pl ), c.get( "name" ), null, 1,
          !"false".equals( c.get( "distribute" ) ), cuerpo, new Point( c.x, c.y ), ms );
        for ( Map.Entry<String, String> e : c.atributos.entrySet() ) {
          s.setAttribute( RobustecerAnalisis.GRUPO, e.getKey(), e.getValue() );
        }
        nuevos.add( s );
      }
    }
    if ( !nuevos.isEmpty() ) {
      Object[] objs = new Object[ nuevos.size() ];
      int[] idx = new int[ nuevos.size() ];
      for ( int i = 0; i < nuevos.size(); i++ ) {
        tm.addStep( nuevos.get( i ) );
      }
      for ( int i = 0; i < nuevos.size(); i++ ) {
        objs[ i ] = nuevos.get( i );
        idx[ i ] = tm.indexOfStep( nuevos.get( i ) );
      }
      undo.nuevo( objs, idx );
      h.cambios += nuevos.size();
    }
    // (2) hops que se quitan (por índice, de atrás para adelante)
    final List<TransHopMeta> viejos = new ArrayList<TransHopMeta>();
    for ( Cambio c : cambios ) {
      if ( Cambio.REMOVE_HOP.equals( c.op ) ) {
        TransHopMeta x = hop( tm, c.get( "from" ), c.get( "to" ) );
        if ( x != null && !viejos.contains( x ) ) {
          viejos.add( x );
        }
      }
    }
    if ( !viejos.isEmpty() ) {
      final TransMeta t = tm;
      Collections.sort( viejos, new Comparator<TransHopMeta>() {
        @Override
        public int compare( TransHopMeta a, TransHopMeta b ) {
          return t.indexOfTransHop( a ) - t.indexOfTransHop( b );
        }
      } );
      Object[] clones = new Object[ viejos.size() ];
      int[] idx = new int[ viejos.size() ];
      for ( int i = 0; i < viejos.size(); i++ ) {
        clones[ i ] = viejos.get( i ).clone();
        idx[ i ] = tm.indexOfTransHop( viejos.get( i ) );
      }
      for ( int i = viejos.size() - 1; i >= 0; i-- ) {
        tm.removeTransHop( idx[ i ] );
      }
      undo.borrado( clones, idx );
      h.cambios += viejos.size();
    }
    // (3) hops nuevos
    List<TransHopMeta> hops = new ArrayList<TransHopMeta>();
    for ( Cambio c : cambios ) {
      if ( Cambio.ADD_HOP.equals( c.op ) ) {
        TransHopMeta x = new TransHopMeta( tm.findStep( c.get( "from" ) ), tm.findStep( c.get( "to" ) ), true );
        x.setErrorHop( "true".equals( c.get( "error" ) ) );
        tm.addTransHop( x );
        hops.add( x );
      }
    }
    if ( !hops.isEmpty() ) {
      Object[] objs = new Object[ hops.size() ];
      int[] idx = new int[ hops.size() ];
      for ( int i = 0; i < hops.size(); i++ ) {
        objs[ i ] = hops.get( i );
        idx[ i ] = tm.indexOfTransHop( hops.get( i ) );
      }
      undo.nuevo( objs, idx );
      h.cambios += hops.size();
    }
    // (4) reconfiguraciones de steps existentes (mezcla sobre su configuración actual)
    for ( Cambio c : cambios ) {
      if ( Cambio.RECONFIGURE.equals( c.op ) ) {
        StepMeta s = tm.findStep( c.get( "step" ) );
        StepMeta antes = instantanea( s );
        String aviso = TransOps.reconfigurar( tm, s, c.xml, ms );
        if ( aviso != null ) {
          h.notas.add( aviso );
        }
        undo.cambio( new Object[] { antes }, new Object[] { instantanea( s ) }, new int[] { tm.indexOfStep( s ) } );
        h.cambios++;
      }
    }
    // (5) manejo de errores
    for ( Cambio c : cambios ) {
      if ( Cambio.SET_ERROR_HANDLING.equals( c.op ) ) {
        StepMeta s = tm.findStep( c.get( "step" ) );
        StepMeta destino = tm.findStep( c.get( "target" ) );
        StepMeta antes = instantaneaSinErrores( tm, s );
        StepErrorMeta sem = manejoDeErrores( s );
        // uno apagado (la persona lo configuró y destildó «Enable») se reusa, pero con los nombres de la cola que se
        // construyó (motivo, campos_error) y sin sus topes: si conservara desc_error, el Text file output de la cola
        // no encontraría 'motivo' y la corrida abortaría con la primera fila mala.
        boolean apagado = sem != null && !sem.isEnabled();
        if ( sem == null ) {
          sem = new StepErrorMeta( tm, s, destino );
        } else {
          sem = sem.clone();
        }
        sem.setSourceStep( s );
        sem.setTargetStep( destino );
        sem.setEnabled( true );
        if ( apagado ) {
          sem.setMaxErrors( "" );
          sem.setMaxPercentErrors( "" );
          sem.setMinPercentRows( "" );
        }
        if ( c.get( "description_field" ) != null && ( apagado || vacio( sem.getErrorDescriptionsValuename() ) ) ) {
          sem.setErrorDescriptionsValuename( c.get( "description_field" ) );
        }
        if ( c.get( "fields_field" ) != null && ( apagado || vacio( sem.getErrorFieldsValuename() ) ) ) {
          sem.setErrorFieldsValuename( c.get( "fields_field" ) );
        }
        s.setStepErrorMeta( sem );
        s.setChanged();
        undo.cambio( new Object[] { antes }, new Object[] { instantanea( s ) }, new int[] { tm.indexOfStep( s ) } );
        h.cambios++;
      }
    }
    TransOps.resolverReferencias( tm );
    // (6) parámetros: Spoon no los deshace; UndoEfecto los mete en el mismo Ctrl+Z
    UndoEfecto.Foto fotoAntes = null;
    List<String> parametros = new ArrayList<String>();
    for ( Cambio c : cambios ) {
      if ( Cambio.ADD_PARAMETER.equals( c.op ) ) {
        if ( fotoAntes == null ) {
          fotoAntes = UndoEfecto.Foto.de( tm );
        }
        tm.addParameterDefinition( c.get( "name" ), c.get( "default" ), c.get( "description" ) );
        parametros.add( c.get( "name" ) );
        h.cambios++;
      }
    }
    if ( fotoAntes != null ) {
      tm.activateParameters();
      if ( !UndoEfecto.registrar( undo, tm, fotoAntes ) ) {
        h.notas.add( tx.f( "note_parameter", String.join( ", ", parametros ) ) );
      }
    }
    tm.clearCaches();
    tm.setChanged();
  }

  private static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }
}
