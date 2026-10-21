package io.github.ivi2511.pentahomcp;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Esquema;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Seccion lineage: de donde sale cada campo de los steps destino (diseno §2.4.1).
 * <p>
 * Motor principal: metaverse ({@link LinajeMetaverse}); respaldo: {@link LinajePropio}, por destino y
 * por campo (lo que metaverse no resuelve lo resuelve el propio; el detalle tecnico de un fallo de metaverse
 * va al log, el informe solo dice que motor se uso y para que campos). Destinos: {@code Pedido.linajeStep}
 * o, si no, cada step final que escribe. Abre con un parrafo de para que sirve el linaje. Por destino van
 * un diagrama Mermaid ({@code flowchart LR}, un
 * subgraph por step, la operacion sobre la flecha; solo los nodos donde algo cambia) y una tabla; los
 * steps cuyos campos no se pudieron calcular dejan la seccion "parcial" con su motivo.
 * <p>
 * {@code datos}: {"section":"lineage","engine":"metaverse|kettle-metadata|mixed","targets":[{step, type,
 * engine, fields:[{name, type, engine, origins:[{step, field, kind, op, role}], paths:[[{step, field, kind,
 * op, role, metaverse}]]}]}], "unresolved_steps":[{step, reason}], "metaverse":{available, reason, ms}}.
 *
 * @dueño WP3
 */
final class SecLinaje implements Recolector {

  /** Area de Rotulos (textos/linaje_*.properties). */
  static final String AREA = "linaje";

  /** Tope de la seccion (diseno §2.5: linaje 60 s). */
  static final long TOPE_MS = 60000L;
  static final int MAX_DESTINOS = 8;
  static final int MAX_CAMPOS_DIAGRAMA = 40;
  static final int MAX_NODOS_DIAGRAMA = 150;

  static final String MOTOR_MIXTO = "mixed";

  @Override
  public String id() {
    return SeccionId.LINAJE;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    Rotulos rot = Rotulos.de( AREA, idioma );
    if ( c == null || !c.esTransformacion() || !( c.copia instanceof TransMeta ) ) {
      return rot.s( "no_aplica.solo_trans" );
    }
    if ( ( (TransMeta) c.copia ).nrSteps() == 0 ) {
      return rot.s( "no_aplica.sin_steps" );
    }
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos rot = ctx.rotulos( AREA );
    String titulo = SeccionId.titulo( id(), ctx.idioma() );
    String na = noAplica( ctx.captura(), ctx.idioma() );
    if ( na != null ) {
      return Seccion.omitida( id(), titulo, na );
    }
    TransMeta tm = (TransMeta) ctx.captura().copia;
    Seccion sec = new Seccion( id(), titulo );
    long inicio = System.currentTimeMillis();
    long plazo = Math.min( TOPE_MS, ctx.msRestantes() );
    if ( plazo <= 0 || ctx.cancelado() ) {
      sec.estado = Seccion.PARCIAL;
      sec.motivo = rot.s( ctx.cancelado() ? "motivo.cancelado" : "motivo.tiempo" );
      return sec;
    }
    LinajePropio propio = new LinajePropio( tm, ctx );
    List<StepMeta> orden = ctx.orden();
    List<Bloque> arriba = new ArrayList<>();

    // ---- destinos
    List<StepMeta> destinos = destinos( ctx, tm, orden, rot, arriba );
    if ( destinos.isEmpty() ) {
      sec.estado = Seccion.PARCIAL;
      sec.motivo = rot.s( "motivo.sin_destinos" );
      sec.bloques.addAll( arriba );
      return sec;
    }

    // ---- campos de cada destino (con el memo del contexto; sin abrir bases de archivo que faltan)
    Map<String, List<String>> campos = new LinkedHashMap<>();
    for ( StepMeta d : destinos ) {
      RowMetaInterface rm = propio.campos( d );
      if ( rm != null ) {
        campos.put( d.getName(), LinajeMetaverse.nombres( rm ) );
      }
    }

    // ---- metaverse
    ObjectNode mvDatos = ModeloJson.mapper().createObjectNode();
    String mvMotivo = null;
    LinajeMetaverse.Corrida corrida = null;
    String problema = LinajeMetaverse.problema();
    if ( problema == null && propio.hayBaseFaltante() ) {
      problema = "metaverse.base";
    }
    if ( problema != null ) {
      mvMotivo = rot.s( problema );
    } else if ( !campos.isEmpty() && !ctx.cancelado() ) {
      ctx.progreso().paso( rot.s( "progreso.metaverse" ), 0, destinos.size() );
      long resta = plazo - ( System.currentTimeMillis() - inicio );
      try {
        corrida = LinajeMetaverse.calcular( tm, campos, propio, resta );
        mvDatos.put( "ms", corrida.msTotal );
      } catch ( Throwable e ) {
        // el detalle tecnico (excepcion y pila) va al log y a los datos; el informe dice solo que motor se uso
        Throwable x = causa( e );
        LinajeMetaverse.log( null, null, x );
        mvDatos.put( "error", motivoCorto( x ) );
        mvMotivo = LinajeMetaverse.esTimeout( x ) ? rot.f( "metaverse.tiempo", Math.max( 1L, resta / 1000 ) )
          : rot.s( "metaverse.error" );
      }
    }
    mvDatos.put( "available", corrida != null );
    if ( mvMotivo != null ) {
      mvDatos.put( "reason", mvMotivo );
    }

    // ---- cada destino: metaverse primero, el motor propio de respaldo (por destino y por campo)
    List<LinajePropio.Destino> resultados = new ArrayList<>();
    Map<String, String> respaldo = new HashMap<>();
    boolean cortado = false;
    for ( int i = 0; i < destinos.size(); i++ ) {
      StepMeta d = destinos.get( i );
      if ( ctx.cancelado() || System.currentTimeMillis() - inicio > plazo ) {
        cortado = true;
        break;
      }
      ctx.progreso().paso( rot.f( "progreso.destino", d.getName() ), i + 1, destinos.size() );
      LinajeMetaverse.Respuesta mv = corrida == null ? null : corrida.destinos.get( d.getName() );
      if ( mv != null && mv.error != null ) {
        // mv.error es tecnico (ya esta en el log): al informe va la frase de siempre
        respaldo.put( d.getName(), rot.s( "metaverse.error" ) );
      }
      resultados.add( combinar( propio, d, mv, orden ) );
    }

    // ---- bloques: primero para que sirve el linaje (no todos lo conocen), despues los avisos
    Parrafo paraQue = new Parrafo( rot.s( "para_que" ) );
    paraQue.id = "lineage-purpose";
    sec.bloques.add( paraQue );
    if ( mvMotivo != null && corrida == null && problema == null ) {
      arriba.add( new Aviso( Aviso.INFO, rot.f( "aviso.metaverse", mvMotivo ) ) );
    }
    sec.bloques.addAll( arriba );
    int n = 0;
    for ( LinajePropio.Destino d : resultados ) {
      n++;
      if ( d.motivo != null ) {
        sec.bloques.add( new Aviso( Aviso.WARN, rot.f( "aviso.destino", d.step.getName(), d.motivo ) ) );
        continue;
      }
      Diagrama dg = diagrama( d, rot, orden, n );
      if ( dg != null ) {
        sec.bloques.add( dg );
      } else if ( !d.campos.isEmpty() ) {
        Aviso a = new Aviso( Aviso.INFO, rot.f( "aviso.grande", d.step.getName(), d.campos.size(), nodos( d ) ) );
        sec.bloques.add( a );
      }
      sec.bloques.add( tabla( d, rot, respaldo.get( d.step.getName() ), mvMotivo, n ) );
    }
    List<LinajePropio.Pendiente> pendientes = propio.pendientes();
    if ( !pendientes.isEmpty() ) {
      Tabla t = new Tabla( rot.s( "col.step" ), rot.s( "col.motivo" ) );
      t.id = "lineage-unresolved";
      t.titulo = rot.s( "titulo.pendientes" );
      for ( LinajePropio.Pendiente p : pendientes ) {
        t.fila( p.step, p.motivo );
      }
      sec.bloques.add( t );
    }

    // ---- estado, resumen y datos
    if ( cortado ) {
      sec.estado = Seccion.PARCIAL;
      sec.motivo = rot.s( ctx.cancelado() ? "motivo.cancelado" : "motivo.tiempo" );
    } else if ( !pendientes.isEmpty() ) {
      sec.estado = Seccion.PARCIAL;
      LinajePropio.Pendiente p = pendientes.get( 0 );
      sec.motivo = pendientes.size() == 1 ? rot.f( "motivo.pendiente.1", p.step, p.motivo )
        : rot.f( "motivo.pendiente.n", p.step, p.motivo, pendientes.size() );
    }
    sec.resumen = resumen( resultados, pendientes, rot );
    sec.datos = datos( resultados, pendientes, mvDatos );
    return sec;
  }

  // ---------------------------------------------------------------- destinos y motores

  /** Pedido.linajeStep si existe; si no, los steps finales que escriben (como mucho MAX_DESTINOS). */
  private static List<StepMeta> destinos( Contexto ctx, TransMeta tm, List<StepMeta> orden, Rotulos rot,
                                          List<Bloque> avisos ) {
    List<StepMeta> out = new ArrayList<>();
    String pedido = ctx.pedido() == null ? null : ctx.pedido().linajeStep;
    if ( !LinajePropio.vacio( pedido ) ) {
      StepMeta s = tm.findStep( pedido );
      if ( s == null ) {
        for ( StepMeta x : tm.getSteps() ) {
          if ( x.getName().trim().equalsIgnoreCase( pedido.trim() ) ) {
            s = x;
          }
        }
      }
      if ( s != null ) {
        out.add( s );
        return out;
      }
      avisos.add( new Aviso( Aviso.WARN, rot.f( "aviso.sin_step", pedido ) ) );
    }
    out.addAll( LinajePropio.destinosPorDefecto( tm, orden ) );
    if ( out.size() > MAX_DESTINOS ) {
      avisos.add( new Aviso( Aviso.INFO, rot.f( "aviso.muchos_destinos", out.size(), MAX_DESTINOS ) ) );
      return new ArrayList<>( out.subList( 0, MAX_DESTINOS ) );
    }
    return out;
  }

  /**
   * Campos del destino: los caminos de metaverse donde los hay, los del motor propio donde no. Donde hay
   * de los dos, tambien los caminos del propio que metaverse no trajo (mismos steps y campos): metaverse a
   * veces pierde uno de los que pasan por un Append (probado con una practica de un DW: de una corrida a
   * otra faltaba la entrada del tail o una clave de busqueda) y el campo parecia venir de una sola entrada.
   */
  static LinajePropio.Destino combinar( LinajePropio propio, StepMeta d, LinajeMetaverse.Respuesta mv,
                                       List<StepMeta> orden ) {
    RowMetaInterface rm = propio.campos( d );
    if ( rm == null ) {
      return propio.destino( d ); // queda con su motivo y la causa en pendientes
    }
    LinajePropio.Destino r = new LinajePropio.Destino( d );
    int deMv = 0;
    boolean completados = false;
    for ( ValueMetaInterface v : rm.getValueMetaList() ) {
      List<List<LinajePropio.Nodo>> cs = mv == null || mv.error != null ? null : mv.caminos.get( v.getName() );
      LinajePropio.Campo c;
      if ( cs != null && !cs.isEmpty() ) {
        c = new LinajePropio.Campo( v.getName() );
        c.tipo = v.getTypeDesc();
        c.motor = LinajeMetaverse.MOTOR;
        List<List<LinajePropio.Nodo>> todos = new ArrayList<>( cs );
        Set<String> vistos = new HashSet<>();
        for ( List<LinajePropio.Nodo> camino : cs ) {
          vistos.add( recorrido( camino ) );
        }
        for ( List<LinajePropio.Nodo> camino : propio.campo( d, v ).caminos ) {
          // un camino que el propio no pudo resolver no completa nada: metaverse ya lo trazo
          if ( !LinajePropio.DESCONOCIDO.equals( camino.get( 0 ).clase ) && vistos.add( recorrido( camino ) ) ) {
            todos.add( camino );
            c.motor = MOTOR_MIXTO;
            completados = true;
          }
        }
        c.truncado = todos.size() > LinajePropio.MAX_CAMINOS;
        c.caminos.addAll( c.truncado ? todos.subList( 0, LinajePropio.MAX_CAMINOS ) : todos );
        deMv++;
      } else {
        c = propio.campo( d, v );
      }
      propio.ordenar( c.caminos, orden );
      r.campos.add( c );
    }
    r.motor = deMv == r.campos.size() && deMv > 0 && !completados ? LinajeMetaverse.MOTOR
      : deMv == 0 ? LinajePropio.MOTOR : MOTOR_MIXTO;
    return r;
  }

  /** "step\0campo\0step\0campo...": un camino sin las clases ni las operaciones (cada motor las rotula). */
  private static String recorrido( List<LinajePropio.Nodo> camino ) {
    StringBuilder sb = new StringBuilder();
    for ( LinajePropio.Nodo n : camino ) {
      sb.append( n.step ).append( '\u0000' ).append( n.campo ).append( '\u0000' );
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- tabla

  private static Tabla tabla( LinajePropio.Destino d, Rotulos rot, String respaldo, String mvMotivo, int n ) {
    Tabla t = new Tabla( rot.s( "col.campo" ), rot.s( "col.tipo" ), rot.s( "col.origen" ), rot.s( "col.ops" ) );
    t.id = "lineage-table-" + n;
    t.titulo = rot.f( "titulo.tabla", d.step.getName() );
    boolean truncado = false;
    for ( LinajePropio.Campo c : d.campos ) {
      t.fila( c.nombre, c.tipo, origenes( c, rot ), operaciones( c, rot ) );
      truncado |= c.truncado;
    }
    if ( truncado ) {
      t.pie = rot.f( "pie.truncado", LinajePropio.MAX_CAMINOS );
    }
    String motor = rot.s( "motor." + d.motor );
    List<String> propios = new ArrayList<>();
    for ( LinajePropio.Campo c : d.campos ) {
      if ( LinajePropio.MOTOR.equals( c.motor ) ) {
        propios.add( c.nombre );
      }
    }
    if ( MOTOR_MIXTO.equals( d.motor ) && !propios.isEmpty() ) {
      // metaverse anduvo con el resto: se nombran los campos que traza el motor propio
      t.nota = rot.f( "nota.motor_campos", motor, y( propios, rot ) );
    } else if ( !LinajeMetaverse.MOTOR.equals( d.motor ) ) {
      String porque = respaldo != null ? respaldo : mvMotivo;
      t.nota = porque == null ? rot.f( "nota.motor", motor ) : rot.f( "nota.motor_porque", motor, porque );
    } else {
      t.nota = rot.f( "nota.motor", motor );
    }
    return t;
  }

  /**
   * "Leer ventas (ventas.csv): precio_unitario, cantidad". Las claves de busqueda (role key) no son de
   * donde viene el campo: un cliente_id que pasa sin cambios desde ventas no «viene» de clientes.csv por
   * haberse usado para buscar ahi. Solo se listan si el campo no tiene otro origen.
   */
  static String origenes( LinajePropio.Campo c, Rotulos rot ) {
    Map<String, Set<String>> porStep = new LinkedHashMap<>();
    Map<String, String> recurso = new HashMap<>();
    boolean hayValor = false;
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      hayValor |= !esClave( camino );
    }
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      if ( hayValor && esClave( camino ) ) {
        continue;
      }
      LinajePropio.Nodo o = camino.get( 0 );
      String campo = o.campo;
      if ( esClave( camino ) ) {
        campo += " (" + rot.s( "clave" ) + ")";
      } else if ( LinajePropio.DESCONOCIDO.equals( o.clase ) ) {
        campo += " (?)";
      } else if ( LinajePropio.CREA.equals( o.clase ) ) {
        campo += " (" + rot.s( "creado" ) + ")";
      }
      Set<String> s = porStep.get( o.step );
      if ( s == null ) {
        s = new LinkedHashSet<>();
        porStep.put( o.step, s );
      }
      s.add( campo );
      if ( LinajePropio.FUENTE.equals( o.clase ) && o.op != null ) {
        recurso.put( o.step, o.op );
      }
    }
    List<String> partes = new ArrayList<>();
    for ( Map.Entry<String, Set<String>> e : porStep.entrySet() ) {
      String r = recurso.get( e.getKey() );
      partes.add( e.getKey() + ( r == null ? "" : " (" + r + ")" ) + ": " + unir( new ArrayList<>( e.getValue() ),
        ", " ) );
    }
    return unir( partes, "; " );
  }

  /** "MULTIPLY(a, b) en «Calcular» → SUM(x) en «Totales»"; cadenas distintas separadas por "; ". */
  static String operaciones( LinajePropio.Campo c, Rotulos rot ) {
    Set<String> cadenas = new LinkedHashSet<>();
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      List<String> ops = new ArrayList<>();
      for ( int i = 0; i < camino.size(); i++ ) {
        LinajePropio.Nodo x = camino.get( i );
        boolean origenCreado = i == 0 && ( LinajePropio.CREA.equals( x.clase ) || LinajePropio.DESCONOCIDO.equals(
          x.clase ) && x.op != null );
        if ( origenCreado || i > 0 && !LinajePropio.PASA.equals( x.clase ) ) {
          String op = x.op != null ? x.op : rot.s( "clase." + x.clase );
          if ( !( i > 0 && LinajePropio.ROL_CLAVE.equals( x.rol ) ) ) {
            ops.add( rot.f( "op_en", op, x.step ) );
          }
        }
      }
      if ( !ops.isEmpty() ) {
        cadenas.add( unir( ops, " → " ) );
      }
    }
    return cadenas.isEmpty() ? rot.s( "sin_cambios" ) : unir( new ArrayList<>( cadenas ), "; " );
  }

  private static boolean esClave( List<LinajePropio.Nodo> camino ) {
    for ( LinajePropio.Nodo n : camino ) {
      if ( LinajePropio.ROL_CLAVE.equals( n.rol ) ) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- diagrama

  /** Nodos que se dibujan de un camino: el origen, donde algo cambia y el destino. */
  private static List<LinajePropio.Nodo> significativos( List<LinajePropio.Nodo> camino ) {
    List<LinajePropio.Nodo> out = new ArrayList<>();
    for ( int i = 0; i < camino.size(); i++ ) {
      LinajePropio.Nodo x = camino.get( i );
      if ( i == 0 || i == camino.size() - 1 || !LinajePropio.PASA.equals( x.clase ) ) {
        out.add( x );
      }
    }
    return out;
  }

  /** Cuantos nodos tendria el diagrama del destino. */
  static int nodos( LinajePropio.Destino d ) {
    Set<String> ids = new LinkedHashSet<>();
    for ( LinajePropio.Campo c : d.campos ) {
      for ( List<LinajePropio.Nodo> camino : c.caminos ) {
        for ( LinajePropio.Nodo x : significativos( camino ) ) {
          ids.add( x.step + "\u0000" + x.campo );
        }
      }
    }
    return ids.size();
  }

  /** Diagrama del destino, o null si no tiene campos o se pasa de los topes. */
  static Diagrama diagrama( LinajePropio.Destino d, Rotulos rot, List<StepMeta> orden, int n ) {
    if ( d.campos.isEmpty() || d.campos.size() > MAX_CAMPOS_DIAGRAMA || nodos( d ) > MAX_NODOS_DIAGRAMA ) {
      return null;
    }
    Diagrama g = new Diagrama();
    g.id = "lineage-diagram-" + n;
    g.clase = Diagrama.LINAJE;
    g.titulo = rot.f( "titulo.diagrama", d.step.getName() );
    g.alt = rot.f( "alt.diagrama", d.campos.size(), d.step.getName() );
    g.mermaid = mermaid( d, rot, orden );
    try {
      g.grafo = grafo( d, rot, orden ); // diagrama propio (sin grafo: el Mermaid de siempre)
    } catch ( RuntimeException e ) {
      g.grafo = null;
    }
    return g;
  }

  /**
   * El grafo del diagrama propio (informes.diagramas): los mismos nodos y caminos que {@link #mermaid}, con un
   * grupo por step, la operacion sobre el campo que crea (no sobre la flecha) y las claves de busqueda o de
   * union como aristas {@link Grafo#CLAVE}. Los pasos seguidos dentro de un step y la operacion comun a todos
   * los campos de un step los pliega el motor al dibujar.
   */
  static Esquema grafo( LinajePropio.Destino d, Rotulos rot, List<StepMeta> orden ) {
    Map<String, LinajePropio.Nodo> nodos = new LinkedHashMap<>(); // step\0campo -> nodo
    Map<String, List<String>> porStep = new LinkedHashMap<>();
    Map<String, Set<String>> ops = new HashMap<>();
    Set<String> aristas = new LinkedHashSet<>(); // a\0b\0clase
    for ( LinajePropio.Campo c : d.campos ) {
      for ( List<LinajePropio.Nodo> camino : c.caminos ) {
        if ( !esClave( camino ) ) {
          cadenaGrafo( significativos( camino ), nodos, porStep, ops, aristas, rot );
        }
      }
    }
    Set<String> pares = new HashSet<>();
    for ( LinajePropio.Campo c : d.campos ) {
      for ( List<LinajePropio.Nodo> camino : c.caminos ) {
        if ( !esClave( camino ) ) {
          continue;
        }
        List<LinajePropio.Nodo> sig = significativos( camino );
        int k = 0;
        while ( k < sig.size() && !LinajePropio.ROL_CLAVE.equals( sig.get( k ).rol ) ) {
          k++;
        }
        if ( k == 0 || k >= sig.size() ) {
          continue;
        }
        String step = sig.get( k ).step;
        List<String> destinos = new ArrayList<>();
        List<String> delStep = porStep.get( step );
        if ( delStep != null ) {
          for ( String x : delStep ) {
            LinajePropio.Nodo v = nodos.get( x );
            if ( ( LinajePropio.BUSCA.equals( v.clase ) || LinajePropio.UNE.equals( v.clase ) )
              && !LinajePropio.ROL_CLAVE.equals( v.rol ) ) {
              destinos.add( x );
            }
          }
        }
        if ( destinos.isEmpty() && delStep == null ) {
          continue; // el step de busqueda no aparece en el diagrama: la clave no aporta nada
        }
        String origen = cadenaGrafo( sig.subList( 0, k ), nodos, porStep, ops, aristas, rot );
        if ( destinos.isEmpty() ) {
          destinos.add( porStep.get( step ).get( 0 ) );
        }
        for ( String dest : destinos ) {
          if ( origen != null && !origen.equals( dest ) && pares.add( origen + "\u0000" + dest ) ) {
            aristas.add( origen + "\u0000" + dest + "\u0000" + Grafo.CLAVE );
          }
        }
      }
    }
    Grafo g = new Grafo( Grafo.LINAJE, rot.f( "titulo.diagrama", d.step.getName() ) );
    Map<String, Grafo.Nodo> ids = new HashMap<>();
    for ( String step : ordenar( porStep.keySet(), orden ) ) {
      Grafo.Grupo gr = g.grupo( step );
      gr.destino = step.equals( d.step.getName() );
      for ( String clave : porStep.get( step ) ) {
        Grafo.Nodo n = g.campo( gr, "c" + ids.size(), nodos.get( clave ).campo );
        Set<String> o = ops.get( clave );
        n.op = o == null || o.isEmpty() ? null : unir( new ArrayList<>( o ), " · " );
        ids.put( clave, n );
      }
    }
    for ( String a : aristas ) {
      String[] p = a.split( "\u0000", -1 );
      g.arista( ids.get( p[ 0 ] + "\u0000" + p[ 1 ] ), ids.get( p[ 2 ] + "\u0000" + p[ 3 ] ), p[ 4 ] );
    }
    return g.esquema();
  }

  /** Registra una cadena de nodos del grafo con sus aristas; devuelve la clave del ultimo (null si no hay). */
  private static String cadenaGrafo( List<LinajePropio.Nodo> camino, Map<String, LinajePropio.Nodo> nodos,
                                     Map<String, List<String>> porStep, Map<String, Set<String>> ops,
                                     Set<String> aristas, Rotulos rot ) {
    String anterior = null;
    for ( LinajePropio.Nodo x : camino ) {
      String clave = x.step + "\u0000" + x.campo;
      if ( !nodos.containsKey( clave ) ) {
        nodos.put( clave, x );
        List<String> l = porStep.get( x.step );
        if ( l == null ) {
          l = new ArrayList<>();
          porStep.put( x.step, l );
        }
        l.add( clave );
      }
      String op = null;
      if ( anterior != null && !anterior.equals( clave ) ) {
        op = LinajePropio.PASA.equals( x.clase ) ? null : x.op != null ? x.op : rot.s( "clase." + x.clase );
        aristas.add( anterior + "\u0000" + clave + "\u0000" + Grafo.NORMAL );
      } else if ( anterior == null && LinajePropio.CREA.equals( x.clase ) ) {
        op = x.op != null ? x.op : rot.s( "clase." + x.clase ); // nace en este step (constante, secuencia...)
      }
      if ( op != null && !op.trim().isEmpty() ) {
        Set<String> s = ops.get( clave );
        if ( s == null ) {
          s = new LinkedHashSet<>();
          ops.put( clave, s );
        }
        s.add( recortar( op.trim(), 80 ) );
      }
      anterior = clave;
    }
    return anterior;
  }

  /** flowchart LR con un subgraph por step y la operacion sobre la flecha (sintaxis de Mermaid 9+). */
  static String mermaid( LinajePropio.Destino d, Rotulos rot, List<StepMeta> orden ) {
    Nodos n = new Nodos();
    Set<String> aristas = new LinkedHashSet<>();
    // 1) los caminos de valor: de donde viene cada campo y que le pasa
    for ( LinajePropio.Campo c : d.campos ) {
      for ( List<LinajePropio.Nodo> camino : c.caminos ) {
        if ( !esClave( camino ) ) {
          cadena( significativos( camino ), n, aristas, rot );
        }
      }
    }
    // 2) las claves de busqueda o de union (role key): una flecha punteada desde el campo clave hacia los
    // valores que ese step busca o une, no hacia el campo clave del step (que suele pasar sin cambios y
    // haria creer que el campo de salida viene de la tabla de busqueda)
    Set<String> rotuladas = new HashSet<>();
    Set<String> paresClave = new HashSet<>();
    for ( LinajePropio.Campo c : d.campos ) {
      for ( List<LinajePropio.Nodo> camino : c.caminos ) {
        if ( !esClave( camino ) ) {
          continue;
        }
        List<LinajePropio.Nodo> sig = significativos( camino );
        int k = 0;
        while ( k < sig.size() && !LinajePropio.ROL_CLAVE.equals( sig.get( k ).rol ) ) {
          k++;
        }
        if ( k == 0 || k >= sig.size() ) {
          continue;
        }
        LinajePropio.Nodo kn = sig.get( k );
        List<String> destinos = n.valoresDe( kn.step );
        if ( destinos.isEmpty() && !n.porStep.containsKey( kn.step ) ) {
          continue; // el step de busqueda no aparece en el diagrama: la clave no aporta nada
        }
        String origen = cadena( sig.subList( 0, k ), n, aristas, rot );
        String et = kn.op != null ? kn.op : rot.s( "clase." + kn.clase );
        if ( destinos.isEmpty() ) {
          destinos.add( n.subgrafo( kn.step ) );
        }
        for ( String dest : destinos ) {
          if ( !paresClave.add( origen + "\u0000" + dest ) ) {
            continue; // otro campo llego por la misma clave: una sola flecha
          }
          StringBuilder a = new StringBuilder( "  " ).append( origen ).append( " -.->" );
          if ( rotuladas.add( origen + "\u0000" + kn.step ) ) {
            a.append( "|\"" ).append( ReportMotor.escaparMermaid( recortar( et, 80 ) ) ).append( "\"|" );
          }
          aristas.add( a.append( ' ' ).append( dest ).toString() );
        }
      }
    }
    StringBuilder sb = new StringBuilder( "flowchart LR\n" );
    for ( String step : ordenar( n.porStep.keySet(), orden ) ) {
      sb.append( "  subgraph " ).append( n.subgrafo( step ) ).append( " [\"" ).append( ReportMotor.escaparMermaid(
        step ) ).append( "\"]\n" );
      for ( String l : n.porStep.get( step ) ) {
        sb.append( l ).append( '\n' );
      }
      sb.append( "  end\n" );
    }
    for ( String a : aristas ) {
      sb.append( a ).append( '\n' );
    }
    return sb.toString();
  }

  /** Los nodos del diagrama: id por step y campo, las lineas de cada subgraph y la clase de cada nodo. */
  private static final class Nodos {
    final Map<String, String> ids = new LinkedHashMap<>();
    final Map<String, List<String>> porStep = new LinkedHashMap<>();
    final Map<String, List<String[]>> clasesPorStep = new HashMap<>();
    final Map<String, String> subgrafos = new HashMap<>();

    String id( LinajePropio.Nodo x ) {
      String clave = x.step + "\u0000" + x.campo;
      String id = ids.get( clave );
      if ( id == null ) {
        id = "c" + ids.size();
        ids.put( clave, id );
        List<String> l = porStep.get( x.step );
        if ( l == null ) {
          l = new ArrayList<>();
          porStep.put( x.step, l );
          clasesPorStep.put( x.step, new ArrayList<String[]>() );
        }
        l.add( "    " + id + "[\"" + ReportMotor.escaparMermaid( x.campo ) + "\"]" );
        clasesPorStep.get( x.step ).add( new String[] { id, x.clase, x.rol } );
      }
      return id;
    }

    /** Los nodos del step que son valores buscados o unidos (no claves). */
    List<String> valoresDe( String step ) {
      List<String> out = new ArrayList<>();
      List<String[]> l = clasesPorStep.get( step );
      if ( l != null ) {
        for ( String[] x : l ) {
          if ( ( LinajePropio.BUSCA.equals( x[ 1 ] ) || LinajePropio.UNE.equals( x[ 1 ] ) )
            && !LinajePropio.ROL_CLAVE.equals( x[ 2 ] ) ) {
            out.add( x[ 0 ] );
          }
        }
      }
      return out;
    }

    /** Id estable del subgraph de un step (las claves sin valores dibujados apuntan al subgraph). */
    String subgrafo( String step ) {
      String g = subgrafos.get( step );
      if ( g == null ) {
        g = "g" + subgrafos.size();
        subgrafos.put( step, g );
      }
      return g;
    }
  }

  /** Dibuja una cadena de nodos con sus flechas; devuelve el id del ultimo (null si no hay nodos). */
  private static String cadena( List<LinajePropio.Nodo> nodos, Nodos n, Set<String> aristas, Rotulos rot ) {
    String anterior = null;
    for ( LinajePropio.Nodo x : nodos ) {
      String id = n.id( x );
      if ( anterior != null && !anterior.equals( id ) ) {
        String et = LinajePropio.PASA.equals( x.clase ) ? null : x.op != null ? x.op : rot.s( "clase." + x.clase );
        StringBuilder a = new StringBuilder( "  " ).append( anterior ).append( " -->" );
        if ( et != null ) {
          a.append( "|\"" ).append( ReportMotor.escaparMermaid( recortar( et, 80 ) ) ).append( "\"|" );
        }
        aristas.add( a.append( ' ' ).append( id ).toString() );
      }
      anterior = id;
    }
    return anterior;
  }

  private static List<String> ordenar( Set<String> steps, List<StepMeta> orden ) {
    List<String> out = new ArrayList<>();
    if ( orden != null ) {
      for ( StepMeta s : orden ) {
        if ( steps.contains( s.getName() ) ) {
          out.add( s.getName() );
        }
      }
    }
    for ( String s : steps ) {
      if ( !out.contains( s ) ) {
        out.add( s );
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- resumen

  static String resumen( List<LinajePropio.Destino> ds, List<LinajePropio.Pendiente> pendientes, Rotulos rot ) {
    int campos = 0;
    List<String> nombres = new ArrayList<>();
    Map<String, String> fuentes = new LinkedHashMap<>();
    Map<String, Set<String>> ops = new LinkedHashMap<>();
    for ( String k : new String[] { LinajePropio.CALCULA, LinajePropio.AGREGA, LinajePropio.RENOMBRA,
      LinajePropio.CONVIERTE, LinajePropio.BUSCA, LinajePropio.UNE, LinajePropio.CREA } ) {
      ops.put( k, new LinkedHashSet<String>() );
    }
    for ( LinajePropio.Destino d : ds ) {
      if ( d.motivo != null ) {
        continue;
      }
      campos += d.campos.size();
      nombres.add( d.step.getName() );
      for ( LinajePropio.Campo c : d.campos ) {
        for ( List<LinajePropio.Nodo> camino : c.caminos ) {
          LinajePropio.Nodo o = camino.get( 0 );
          if ( LinajePropio.FUENTE.equals( o.clase ) && !fuentes.containsKey( o.step ) ) {
            fuentes.put( o.step, o.op );
          } else if ( LinajePropio.CREA.equals( o.clase ) ) {
            ops.get( LinajePropio.CREA ).add( o.step + "\u0000" + o.campo );
          }
          for ( int i = 1; i < camino.size(); i++ ) {
            LinajePropio.Nodo x = camino.get( i );
            Set<String> s = ops.get( x.clase );
            if ( s != null && !LinajePropio.CREA.equals( x.clase ) && !LinajePropio.ROL_CLAVE.equals( x.rol ) ) {
              boolean porStep = LinajePropio.BUSCA.equals( x.clase ) || LinajePropio.UNE.equals( x.clase );
              s.add( porStep ? x.step : x.step + "\u0000" + x.campo );
            }
          }
        }
      }
    }
    List<String> frases = new ArrayList<>();
    if ( campos > 0 ) {
      List<String> fs = new ArrayList<>();
      for ( Map.Entry<String, String> e : fuentes.entrySet() ) {
        fs.add( e.getValue() == null ? rot.f( "fuente", e.getKey() ) : rot.f( "fuente_recurso", e.getKey(), e
          .getValue() ) );
      }
      String fuentesTxt = fs.isEmpty() ? rot.s( "fuentes.ninguna" ) : y( fs, rot );
      String camposTxt = plural( rot, "n.campos", campos );
      frases.add( nombres.size() == 1 ? rot.f( "resumen.destino", camposTxt, nombres.get( 0 ), fuentesTxt )
        : rot.f( "resumen.destinos", camposTxt, nombres.size(), fuentesTxt ) );
      List<String> partes = new ArrayList<>();
      for ( Map.Entry<String, Set<String>> e : ops.entrySet() ) {
        if ( !e.getValue().isEmpty() ) {
          partes.add( plural( rot, "n." + e.getKey(), e.getValue().size() ) );
        }
      }
      frases.add( partes.isEmpty() ? rot.s( "resumen.sin_ops" ) : rot.f( "resumen.ops", y( partes, rot ) ) );
    }
    if ( !pendientes.isEmpty() ) {
      List<String> ps = new ArrayList<>();
      for ( LinajePropio.Pendiente p : pendientes ) {
        ps.add( p.step );
      }
      frases.add( pendientes.size() == 1 ? rot.f( "resumen.pendientes.1", ps.get( 0 ) )
        : rot.f( "resumen.pendientes.n", pendientes.size(), y( ps, rot ) ) );
    }
    return frases.isEmpty() ? null : unir( frases, " " );
  }

  /** rot.f( clave + ".1" | ".n", n ). */
  static String plural( Rotulos rot, String clave, long n ) {
    return rot.f( clave + ( n == 1 ? ".1" : ".n" ), n );
  }

  /** "a, b y c" con la conjuncion del idioma. */
  static String y( List<String> l, Rotulos rot ) {
    if ( l.size() <= 1 ) {
      return l.isEmpty() ? "" : l.get( 0 );
    }
    return unir( l.subList( 0, l.size() - 1 ), ", " ) + " " + rot.s( "y" ) + " " + l.get( l.size() - 1 );
  }

  // ---------------------------------------------------------------- datos

  static ObjectNode datos( List<LinajePropio.Destino> ds, List<LinajePropio.Pendiente> pendientes,
                           ObjectNode metaverse ) {
    ObjectNode o = ModeloJson.mapper().createObjectNode();
    o.put( "section", SeccionId.LINAJE );
    Set<String> motores = new LinkedHashSet<>();
    ArrayNode ts = o.putArray( "targets" );
    for ( LinajePropio.Destino d : ds ) {
      ObjectNode t = ts.addObject();
      t.put( "step", d.step.getName() );
      t.put( "type", d.step.getStepID() );
      if ( d.motor != null && d.motivo == null ) {
        t.put( "engine", d.motor );
      }
      if ( d.motivo != null ) {
        t.put( "unresolved", d.motivo );
      }
      ArrayNode fs = t.putArray( "fields" );
      for ( LinajePropio.Campo c : d.campos ) {
        motores.add( c.motor );
        ObjectNode f = fs.addObject();
        f.put( "name", c.nombre );
        f.put( "type", c.tipo );
        f.put( "engine", c.motor );
        ArrayNode or = f.putArray( "origins" );
        Set<String> vistos = new LinkedHashSet<>();
        for ( List<LinajePropio.Nodo> camino : c.caminos ) {
          LinajePropio.Nodo x = camino.get( 0 );
          String rol = esClave( camino ) ? LinajePropio.ROL_CLAVE : null;
          if ( vistos.add( x.step + "\u0000" + x.campo + "\u0000" + rol ) ) {
            ObjectNode y = nodo( or.addObject(), x );
            y.remove( "role" );
            if ( rol != null ) {
              y.put( "role", rol );
            }
          }
        }
        ArrayNode ps = f.putArray( "paths" );
        for ( List<LinajePropio.Nodo> camino : c.caminos ) {
          ArrayNode p = ps.addArray();
          for ( LinajePropio.Nodo x : camino ) {
            nodo( p.addObject(), x );
          }
        }
        if ( c.truncado ) {
          f.put( "truncated", true );
        }
      }
    }
    o.put( "engine", motores.isEmpty() ? ( metaverse.path( "available" ).asBoolean() ? LinajeMetaverse.MOTOR
      : LinajePropio.MOTOR ) : motores.size() == 1 ? motores.iterator().next() : MOTOR_MIXTO );
    ArrayNode us = o.putArray( "unresolved_steps" );
    for ( LinajePropio.Pendiente p : pendientes ) {
      us.addObject().put( "step", p.step ).put( "reason", p.motivo );
    }
    o.set( "metaverse", metaverse );
    return o;
  }

  private static ObjectNode nodo( ObjectNode y, LinajePropio.Nodo x ) {
    y.put( "step", x.step );
    y.put( "field", x.campo );
    y.put( "kind", x.clase );
    if ( x.op != null ) {
      y.put( "op", x.op );
    }
    if ( x.rol != null ) {
      y.put( "role", x.rol );
    }
    if ( x.metaverse != null ) {
      y.put( "metaverse", x.metaverse );
    }
    return y;
  }

  // ---------------------------------------------------------------- utilidades

  /**
   * Mensaje corto de una excepcion de Kettle: las primeras lineas con texto, sin el SQL ni el log que
   * traen pegados, en una linea de 240 caracteres como mucho.
   */
  static String motivoCorto( Throwable e ) {
    Throwable x = causa( e );
    String m = x.getMessage();
    List<String> lineas = new ArrayList<>();
    if ( m != null ) {
      for ( String l : m.split( "\\r?\\n" ) ) {
        l = l.trim();
        if ( l.isEmpty() || lineas.contains( l ) || l.matches( "(?is)^(select|union|from|where|with|insert|update|"
          + "delete|join|order by|group by|and|or)\\b.*" ) || l.startsWith( "at " ) ) {
          continue;
        }
        lineas.add( l );
        if ( lineas.size() == 2 ) {
          break;
        }
      }
    }
    String s = lineas.isEmpty() ? x.getClass().getSimpleName() : unir( lineas, " " );
    return recortar( s, 240 );
  }

  private static Throwable causa( Throwable e ) {
    Throwable x = e;
    while ( ( x instanceof InvocationTargetException || x instanceof ExecutionException ) && x.getCause() != null ) {
      x = x.getCause();
    }
    return x;
  }

  static String unir( List<String> l, String sep ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      if ( sb.length() > 0 ) {
        sb.append( sep );
      }
      sb.append( s );
    }
    return sb.toString();
  }

  static String recortar( String s, int max ) {
    if ( s == null ) {
      return "";
    }
    return s.length() <= max ? s : s.substring( 0, max - 1 ) + "…";
  }
}
