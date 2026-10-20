package io.github.ivi2511.pentahomcp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.metaverse.api.ChangeType;
import org.pentaho.metaverse.api.ILineageClient;
import org.pentaho.metaverse.api.StepFieldOperations;
import org.pentaho.metaverse.api.model.IOperation;
import org.pentaho.metaverse.api.model.Operation;
import org.pentaho.metaverse.api.model.Operations;
import org.pentaho.platform.engine.core.system.PentahoSystem;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Linaje de campos con el plugin metaverse que trae PDI, sin OSGi (diseno §2.4.1, receta probada en
 * 01-06 fuera de Spoon):
 * <ol>
 * <li>el cliente sale de {@code PentahoSystem.get(ILineageClient.class)};</li>
 * <li>{@code TransExtensionPointUtil.addLineageGraph(clon)} por reflexion, con la clase cargada desde el
 * loader del cliente;</li>
 * <li>{@code LineageGraphMap.getInstance().get(clon).get(timeout)};</li>
 * <li>{@code getOperationPaths(clon, step, campos)} por cada destino;</li>
 * <li>{@code map.remove(clon)} en un finally.</li>
 * </ol>
 * El clon es propio y se renombra: un realClone es equals al documento y pisaria su entrada del mapa.
 * El nombre se pone en el campo, sin setName: realClone comparte con el original los listeners y el
 * VariableSpace, y setName avisaria a los listeners del documento vivo y le cambiaria la variable
 * Internal.Transformation.Name. Por la misma razon el clon recibe listeners propios.
 * <p>
 * Todo corre en el pool de RunMotor con timeout: metaverse tiene un lock global.
 *
 * @dueño WP3
 */
final class LinajeMetaverse {

  static final String MOTOR = "metaverse";

  static final String CLASE_UTIL = "org.pentaho.metaverse.analyzer.kettle.extensionpoints.trans.TransExtensionPointUtil";
  static final String CLASE_MAPA = "org.pentaho.metaverse.graph.LineageGraphMap";

  /** Problemas posibles (claves de rotulos "metaverse.*"). */
  static final String SIN_CLIENTE = "metaverse.sin_cliente";
  static final String SIN_TIPO_CLIENTE = "metaverse.sin_tipo_cliente";

  private static final AtomicInteger CLONES = new AtomicInteger();

  /** Lo que devolvio metaverse para un destino. */
  static final class Respuesta {
    /** campo -&gt; caminos (del origen al destino), ya clasificados. */
    final Map<String, List<List<LinajePropio.Nodo>>> caminos = new LinkedHashMap<>();
    /** Por que fallo este destino (texto tecnico); null si anduvo. */
    String error;
  }

  /** Lo que devolvio una corrida. */
  static final class Corrida {
    final Map<String, Respuesta> destinos = new LinkedHashMap<>();
    long msGrafo;
    long msTotal;
  }

  private LinajeMetaverse() {
  }

  // ---------------------------------------------------------------- disponibilidad

  /** El cliente de linaje de metaverse, o null si el plugin no esta. Nunca lanza. */
  static ILineageClient cliente() {
    try {
      return PentahoSystem.get( ILineageClient.class );
    } catch ( Throwable e ) {
      return null;
    }
  }

  /** null si metaverse se puede usar; si no, la clave de rotulos del motivo. Barato. */
  static String problema() {
    try {
      if ( KettleClientEnvironment.getInstance().getClient() == null ) {
        return SIN_TIPO_CLIENTE; // addLineageGraph hace getClient().toString()
      }
    } catch ( Throwable e ) {
      return SIN_TIPO_CLIENTE;
    }
    return cliente() == null ? SIN_CLIENTE : null;
  }

  /** Entradas que hay ahora en LineageGraphMap; -1 si no se puede leer. Para las pruebas y el log. */
  static int entradasEnMapa() {
    try {
      ILineageClient c = cliente();
      return c == null ? -1 : mapa( c.getClass().getClassLoader() ).size();
    } catch ( Throwable e ) {
      return -1;
    }
  }

  /** true si el mapa tiene una entrada para esta TransMeta (o una equals). */
  static boolean enMapa( TransMeta tm ) {
    try {
      ILineageClient c = cliente();
      return c != null && mapa( c.getClass().getClassLoader() ).containsKey( tm );
    } catch ( Throwable e ) {
      return false;
    }
  }

  @SuppressWarnings( "unchecked" )
  private static Map<Object, Future<?>> mapa( ClassLoader cl ) throws Exception {
    Class<?> c = Class.forName( CLASE_MAPA, true, cl );
    return (Map<Object, Future<?>>) c.getMethod( "getInstance" ).invoke( null );
  }

  // ---------------------------------------------------------------- corrida

  /**
   * Caminos de metaverse para los campos de cada destino. Clona la copia, arma el grafo y lo saca del
   * mapa al terminar, todo dentro del plazo. Los destinos que fallan llevan su error; si falla el grafo
   * entero lanza la excepcion (ToolError si se paso del plazo).
   *
   * @param copia     la copia de la captura (no se modifica)
   * @param campos    step destino -&gt; nombres de sus campos de salida
   * @param reglas    el motor propio sobre la misma copia: clasifica y etiqueta los nodos
   * @param timeoutMs plazo total
   */
  static Corrida calcular( final TransMeta copia, final Map<String, List<String>> campos, final LinajePropio reglas,
                           long timeoutMs ) throws Exception {
    final ILineageClient cliente = cliente();
    if ( cliente == null ) {
      throw new IllegalStateException( "PentahoSystem.get(ILineageClient) = null" );
    }
    final long plazo = Math.max( 1000L, timeoutMs );
    final long t0 = System.currentTimeMillis();
    // el clon se hace aca: si se vence el plazo, el hilo de metaverse solo sigue tocando el clon
    final TransMeta clon = clonar( copia );
    Map<String, Object> crudo = RunMotor.enSegundoPlano( new Callable<Map<String, Object>>() {
      @Override
      public Map<String, Object> call() throws Exception {
        return crudo( cliente, clon, campos, plazo, t0 );
      }
    }, plazo + 2000L, null, "metaverse" );
    Corrida c = new Corrida();
    c.msGrafo = (Long) crudo.remove( "\u0000ms" );
    for ( Map.Entry<String, List<String>> e : campos.entrySet() ) {
      Respuesta r = new Respuesta();
      Object o = crudo.get( e.getKey() );
      if ( o instanceof String ) {
        r.error = (String) o;
      } else if ( o != null ) {
        @SuppressWarnings( "unchecked" )
        Map<String, Set<List<StepFieldOperations>>> paths = (Map<String, Set<List<StepFieldOperations>>>) o;
        for ( String campo : e.getValue() ) {
          Set<List<StepFieldOperations>> ps = paths.get( campo );
          if ( ps != null && !ps.isEmpty() ) {
            r.caminos.put( campo, convertir( ps, reglas ) );
          }
        }
      }
      c.destinos.put( e.getKey(), r );
    }
    c.msTotal = System.currentTimeMillis() - t0;
    return c;
  }

  /** Corre en el pool: grafo y caminos crudos de metaverse. El clon sale del mapa siempre. */
  private static Map<String, Object> crudo( ILineageClient cliente, TransMeta clon, Map<String, List<String>> campos,
                                            long plazo, long t0 ) throws Exception {
    ClassLoader cl = cliente.getClass().getClassLoader();
    Method agregar = Class.forName( CLASE_UTIL, true, cl ).getMethod( "addLineageGraph", TransMeta.class );
    Map<Object, Future<?>> mapa = mapa( cl );
    Map<String, Object> out = new LinkedHashMap<>();
    try {
      try {
        agregar.invoke( null, clon );
      } catch ( java.lang.reflect.InvocationTargetException e ) {
        throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
      }
      Future<?> grafo = mapa.get( clon );
      if ( grafo == null ) {
        throw new IllegalStateException( "metaverse did not register a lineage graph" );
      }
      long resta = Math.max( 100L, plazo - ( System.currentTimeMillis() - t0 ) );
      grafo.get( resta, TimeUnit.MILLISECONDS );
      out.put( "\u0000ms", System.currentTimeMillis() - t0 );
      for ( Map.Entry<String, List<String>> e : campos.entrySet() ) {
        if ( e.getValue().isEmpty() ) {
          out.put( e.getKey(), Collections.emptyMap() );
          continue;
        }
        try {
          out.put( e.getKey(), cliente.getOperationPaths( clon, e.getKey(), e.getValue() ) );
        } catch ( Throwable x ) {
          out.put( e.getKey(), SecLinaje.motivoCorto( x ) );
        }
      }
      return out;
    } finally {
      mapa.remove( clon );
    }
  }

  /**
   * Clon de la copia con nombre unico, listeners propios y los hops reenganchados. No cambia nada del
   * original (ver la nota de la clase).
   */
  static TransMeta clonar( TransMeta copia ) throws Exception {
    TransMeta clon = (TransMeta) copia.realClone( false );
    RunMotor.desligar( clon );
    for ( String f : new String[] { "nameChangedListeners", "filenameChangedListeners", "contentChangedListeners",
      "currentDirectoryChangedListeners" } ) {
      Field campo = campo( AbstractMeta.class, f );
      if ( campo != null && Set.class.isAssignableFrom( campo.getType() ) ) {
        campo.set( clon, Collections.synchronizedSet( new HashSet<Object>() ) );
      }
    }
    Field nombre = campo( AbstractMeta.class, "name" );
    if ( nombre == null ) {
      throw new IllegalStateException( "AbstractMeta.name not found" );
    }
    nombre.set( clon, ( copia.getName() == null ? "" : copia.getName() ) + " [pentaho-live lineage "
      + CLONES.incrementAndGet() + "]" );
    if ( clon.equals( copia ) ) {
      throw new IllegalStateException( "the lineage copy is still equal to the document" );
    }
    return clon;
  }

  private static Field campo( Class<?> c, String nombre ) {
    try {
      Field f = c.getDeclaredField( nombre );
      f.setAccessible( true );
      return f;
    } catch ( Exception e ) {
      return null;
    }
  }

  // ---------------------------------------------------------------- conversion

  /** Caminos de metaverse como nodos clasificados con las reglas del motor propio, en orden estable. */
  static List<List<LinajePropio.Nodo>> convertir( Collection<List<StepFieldOperations>> ps, LinajePropio reglas ) {
    List<List<LinajePropio.Nodo>> out = new ArrayList<>();
    for ( List<StepFieldOperations> p : ps ) {
      if ( p == null || p.isEmpty() ) {
        continue;
      }
      List<LinajePropio.Nodo> c = new ArrayList<>();
      for ( int i = 0; i < p.size(); i++ ) {
        StepFieldOperations x = p.get( i );
        StepMeta sm = reglas.trans().findStep( x.getStepName() );
        LinajePropio.Nodo n;
        if ( i == 0 ) {
          String[] o = reglas.origen( sm, x.getFieldName() );
          n = new LinajePropio.Nodo( x.getStepName(), x.getFieldName(), o[ 0 ], o[ 1 ] );
        } else {
          Ops ops = new Ops( x.getOperations() );
          String[] k = reglas.clasificar( sm, c.get( i - 1 ), x.getFieldName(), ops.agrega, ops.calcula, ops.nombre,
            ops.meta );
          n = new LinajePropio.Nodo( x.getStepName(), x.getFieldName(), k[ 0 ], null );
          n.rol = k[ 1 ];
          n.op = reglas.etiqueta( sm, k[ 0 ], c.get( i - 1 ).campo, x.getFieldName() );
          if ( n.op == null && !LinajePropio.PASA.equals( k[ 0 ] ) ) {
            n.op = ops.texto;
          }
          n.metaverse = ops.texto;
        }
        c.add( n );
      }
      out.add( c );
    }
    Collections.sort( out, ( a, b ) -> firma( a ).compareTo( firma( b ) ) );
    return out;
  }

  static String firma( List<LinajePropio.Nodo> c ) {
    StringBuilder sb = new StringBuilder();
    for ( LinajePropio.Nodo n : c ) {
      sb.append( n.firma() ).append( '|' );
    }
    return sb.toString();
  }

  /** Lo que se lee de las Operations de un nodo: categorias y una descripcion de una linea. */
  private static final class Ops {
    boolean agrega;
    boolean calcula;
    boolean nombre;
    boolean meta;
    String texto;

    Ops( Operations ops ) {
      if ( ops == null || ops.isEmpty() ) {
        return;
      }
      Set<String> partes = new LinkedHashSet<>();
      for ( Map.Entry<ChangeType, List<IOperation>> e : ops.entrySet() ) {
        if ( e.getValue() == null ) {
          continue;
        }
        for ( IOperation o : e.getValue() ) {
          String cat = o.getCategory() == null ? "" : o.getCategory();
          String desc = o instanceof Operation ? ( (Operation) o ).getDescription() : null;
          String nom = o instanceof Operation ? ( (Operation) o ).getName() : null;
          if ( e.getKey() == ChangeType.METADATA ) {
            if ( desc != null && ( "name".equals( desc.trim() ) || desc.matches( "(^|.*,)\\s*name\\s*(,.*|$)" ) ) ) {
              nombre = true;
            }
            if ( desc == null || !desc.trim().equals( "name" ) ) {
              meta = true;
            }
          } else if ( e.getKey() == ChangeType.DATA ) {
            if ( IOperation.AGG_CATEGORY.equals( cat ) ) {
              agrega = true;
            } else {
              calcula = true;
            }
          }
          String t = e.getKey() == ChangeType.METADATA && nom != null && desc != null ? nom + " " + desc
            : desc != null ? desc : nom;
          if ( t != null && !t.trim().isEmpty() ) {
            partes.add( t.trim() );
          }
        }
      }
      texto = partes.isEmpty() ? null : SecLinaje.unir( new ArrayList<>( partes ), "; " );
    }
  }

  /** Nombres de los campos de un RowMeta. */
  static List<String> nombres( RowMetaInterface rm ) {
    List<String> l = new ArrayList<>();
    if ( rm != null ) {
      for ( ValueMetaInterface v : rm.getValueMetaList() ) {
        l.add( v.getName() );
      }
    }
    return l;
  }

  /** Para el mensaje de timeout de RunMotor.enSegundoPlano. */
  static boolean esTimeout( Throwable e ) {
    return e instanceof ToolError || e instanceof java.util.concurrent.TimeoutException;
  }
}
