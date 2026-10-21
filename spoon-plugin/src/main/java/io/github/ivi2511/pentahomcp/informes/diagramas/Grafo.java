package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ivi2511.pentahomcp.informes.modelo.Esquema;

/**
 * Grafo de un diagrama propio: el flujo de una transformacion, un job o el linaje de campos hacia un step.
 * Lo arman los recolectores (GrafoFlujo, el linaje) y lo dibuja {@link Diagramas}; no sabe nada de Kettle.
 * <ul>
 * <li>Flujo y job: un {@link Nodo} por step o entrada, en el orden del documento (el orden en que se
 * crean desempata la disposicion); una {@link Arista} por salto.</li>
 * <li>Linaje: un {@link Grupo} por step y un nodo por campo ({@link #campo}); la operacion que crea el
 * campo va en {@link Nodo#op} (se dibuja como segunda linea, tenue). Las claves de busqueda o de union van
 * como aristas {@link #CLAVE}. Las aristas entre campos del mismo step se pliegan al dibujar.</li>
 * </ul>
 * Las coordenadas internas de la disposicion son abstractas: "a" = eje de las capas (x en LR, y en TB),
 * "c" = eje transversal (y en LR, x en TB). El mismo grafo se puede dibujar varias veces (cada llamada
 * vuelve a medir y a disponer sobre una copia).
 *
 * @dueño WP6
 */
public final class Grafo {

  /** Flujo de una transformacion. */
  public static final String FLUJO = "flujo";
  /** Job: aristas de exito, falla y siempre (sin rotulo; los colores van en la leyenda). */
  public static final String JOB = "job";
  /** Linaje de campos: grupos por step y campos adentro. */
  public static final String LINAJE = "linaje";

  /** Salto normal de una transformacion (o paso de un campo en el linaje). */
  public static final String NORMAL = "normal";
  /** Salto de manejo de errores (rojo, punteado). */
  public static final String ERROR = "error";
  /** Flujo de informacion (por ejemplo, la fuente de un Stream lookup). */
  public static final String INFO = "info";
  /** Job: si termina bien (verde). */
  public static final String OK = "ok";
  /** Job: si falla (rojo, punteado). */
  public static final String FALLA = "falla";
  /** Job: siempre (gris). */
  public static final String SIEMPRE = "siempre";
  /** Linaje: clave de busqueda o de union (azul, punteado). */
  public static final String CLAVE = "clave";

  /** flujo | job | linaje. */
  public final String tipo;
  /** Nombre del diagrama (va como title y aria-label del SVG). */
  public String titulo;
  /**
   * Dibujar la leyenda (colores de los saltos del job, error, deshabilitado, claves) dentro del SVG; false
   * si el renderizador la pone como nota de la figura.
   */
  public boolean leyenda = true;

  final List<Nodo> nodos = new ArrayList<>();
  final List<Arista> aristas = new ArrayList<>();
  final List<Grupo> grupos = new ArrayList<>();
  private final Map<String, Nodo> porId = new LinkedHashMap<>();

  public Grafo( String tipo, String titulo ) {
    this.tipo = LINAJE.equals( tipo ) || JOB.equals( tipo ) ? tipo : FLUJO;
    this.titulo = titulo;
  }

  public boolean linaje() {
    return LINAJE.equals( tipo );
  }

  public boolean job() {
    return JOB.equals( tipo );
  }

  /** Nodo de un flujo o job (un step o una entrada). El id tiene que ser unico. */
  public Nodo nodo( String id, String titulo ) {
    if ( porId.containsKey( id ) ) {
      throw new IllegalArgumentException( "nodo repetido: " + id );
    }
    Nodo n = new Nodo( id );
    n.titulo = titulo == null ? "" : titulo;
    n.doc = nodos.size();
    nodos.add( n );
    porId.put( id, n );
    return n;
  }

  /** Grupo del linaje (un step), en el orden en que se dibujan de izquierda a derecha cuando no hay otra pista. */
  public Grupo grupo( String nombre ) {
    Grupo g = new Grupo( nombre == null ? "" : nombre );
    grupos.add( g );
    return g;
  }

  /** Campo del linaje dentro de su step. */
  public Nodo campo( Grupo g, String id, String nombre ) {
    Nodo n = nodo( id, nombre );
    n.grupo = g;
    g.nodos.add( n );
    return n;
  }

  /** Arista a -&gt; b de una de las clases de arriba (null = normal). Un lazo (a == b) no se dibuja. */
  public Arista arista( Nodo a, Nodo b, String clase ) {
    if ( a == null || b == null ) {
      throw new IllegalArgumentException( "arista sin extremo" );
    }
    Arista e = new Arista( a, b, clase == null ? NORMAL : clase );
    aristas.add( e );
    return e;
  }

  /** Un grafo vacio del mismo tipo, titulo y opciones. */
  Grafo vacioComo() {
    Grafo s = new Grafo( tipo, titulo );
    s.leyenda = leyenda;
    return s;
  }

  /** Agrega un nodo ya armado (copias de Partir), conservando su orden de documento. */
  void agregar( Nodo n ) {
    nodos.add( n );
    porId.put( n.id, n );
  }

  /** El nodo con ese id, o null. */
  public Nodo buscar( String id ) {
    return porId.get( id );
  }

  public List<Nodo> nodos() {
    return Collections.unmodifiableList( nodos );
  }

  public List<Arista> aristas() {
    return Collections.unmodifiableList( aristas );
  }

  public List<Grupo> grupos() {
    return Collections.unmodifiableList( grupos );
  }

  /** El grupo destino del linaje (el ultimo marcado), o null. */
  public Grupo destino() {
    Grupo d = null;
    for ( Grupo g : grupos ) {
      if ( g.destino ) {
        d = g;
      }
    }
    return d;
  }

  // ================================================================== modelo (Esquema)

  /** La forma serializable del grafo, para el modelo del informe (Diagrama.grafo). */
  public Esquema esquema() {
    Esquema e = new Esquema();
    e.tipo = tipo;
    e.titulo = titulo;
    e.leyenda = leyenda;
    Map<Grupo, Integer> indices = new IdentityHashMap<>();
    for ( Grupo g : grupos ) {
      Esquema.Grupo x = new Esquema.Grupo();
      x.nombre = g.nombre;
      x.op = g.op;
      x.destino = g.destino;
      indices.put( g, e.grupos.size() );
      e.grupos.add( x );
    }
    for ( Nodo n : nodos ) {
      Esquema.Nodo x = new Esquema.Nodo();
      x.id = n.id;
      x.titulo = n.titulo;
      x.lineas = n.lineas.isEmpty() ? null : new ArrayList<>( n.lineas );
      x.op = n.op;
      x.multi = n.multi;
      x.copias = n.copias;
      x.falta = n.falta;
      x.inicio = n.inicio;
      x.grupo = n.grupo == null ? null : indices.get( n.grupo );
      e.nodos.add( x );
    }
    for ( Arista a : aristas ) {
      Esquema.Arista x = new Esquema.Arista();
      x.a = a.a.id;
      x.b = a.b.id;
      x.clase = a.clase;
      x.rotulo = a.rotulo;
      x.activa = a.activa;
      e.aristas.add( x );
    }
    return e;
  }

  /**
   * El grafo de un Esquema del modelo. Nodos repetidos, aristas con un extremo que no existe y grupos fuera
   * de rango se ignoran (un JSON editado a mano no rompe el informe).
   */
  public static Grafo desde( Esquema e ) {
    Grafo g = new Grafo( e == null ? FLUJO : e.tipo, e == null ? null : e.titulo );
    if ( e == null ) {
      return g;
    }
    g.leyenda = e.leyenda;
    List<Grupo> gs = new ArrayList<>();
    if ( e.grupos != null ) {
      for ( Esquema.Grupo x : e.grupos ) {
        Grupo gr = g.grupo( x == null ? "" : x.nombre );
        if ( x != null ) {
          gr.op = x.op;
          gr.destino = x.destino;
        }
        gs.add( gr );
      }
    }
    if ( e.nodos != null ) {
      for ( Esquema.Nodo x : e.nodos ) {
        if ( x == null || x.id == null || g.buscar( x.id ) != null ) {
          continue;
        }
        Grupo gr = x.grupo != null && x.grupo >= 0 && x.grupo < gs.size() ? gs.get( x.grupo ) : null;
        Nodo n = gr != null ? g.campo( gr, x.id, x.titulo ) : g.nodo( x.id, x.titulo );
        if ( x.lineas != null ) {
          for ( String l : x.lineas ) {
            if ( l != null ) {
              n.lineas.add( l );
            }
          }
        }
        n.op = x.op;
        n.multi = x.multi;
        n.copias = Math.max( 1, x.copias );
        n.falta = x.falta;
        n.inicio = x.inicio;
      }
    }
    if ( e.aristas != null ) {
      for ( Esquema.Arista x : e.aristas ) {
        Nodo a = x == null ? null : g.buscar( x.a );
        Nodo b = x == null ? null : g.buscar( x.b );
        if ( a == null || b == null ) {
          continue;
        }
        Arista ar = g.arista( a, b, x.clase );
        ar.rotulo = x.rotulo;
        ar.activa = x.activa;
      }
    }
    return g;
  }

  // ================================================================== copia y preparacion

  /**
   * Copia lista para disponer: sin lazos ni aristas repetidas, y en el linaje con las aristas entre campos
   * del mismo step plegadas (el campo hereda las entradas del otro y su operacion dice de cual sale), los
   * grupos vacios fuera y la operacion comun de un step en su cabecera.
   */
  Grafo preparada() {
    Grafo s = vacioComo();
    Map<Nodo, Nodo> copia = new IdentityHashMap<>();
    Map<Grupo, Grupo> grupoCopia = new IdentityHashMap<>();
    for ( Grupo g : grupos ) {
      Grupo ng = new Grupo( g.nombre );
      ng.op = g.op;
      ng.destino = g.destino;
      grupoCopia.put( g, ng );
    }
    for ( Nodo n : nodos ) {
      Nodo m = n.copiar( n.id );
      m.doc = n.doc;
      if ( n.grupo != null ) {
        m.grupo = grupoCopia.get( n.grupo );
        m.grupo.nodos.add( m );
      }
      s.nodos.add( m );
      s.porId.put( m.id, m );
      copia.put( n, m );
    }
    for ( Grupo g : grupos ) {
      s.grupos.add( grupoCopia.get( g ) );
    }
    Set<String> vistas = new LinkedHashSet<>();
    for ( Arista e : aristas ) {
      Nodo a = copia.get( e.a );
      Nodo b = copia.get( e.b );
      if ( a == null || b == null || a == b ) {
        continue;
      }
      if ( !vistas.add( a.id + "\u0000" + b.id + "\u0000" + e.clase ) ) {
        continue;
      }
      Arista x = new Arista( a, b, e.clase );
      x.rotulo = e.rotulo;
      x.activa = e.activa;
      s.aristas.add( x );
    }
    if ( linaje() ) {
      s.plegar();
      s.opComun();
    }
    return s;
  }

  /**
   * Linaje: una arista entre dos campos del mismo step no se puede dibujar (los campos de un step van en la
   * misma columna). El campo destino recibe las entradas del origen y "← origen" en su operacion; si el
   * origen ya no tiene salidas a otros steps y no es del destino, se saca (era un intermedio del step).
   */
  private void plegar() {
    boolean cambio = true;
    int vueltas = 0;
    while ( cambio && vueltas++ < 50 ) {
      cambio = false;
      for ( Arista e : new ArrayList<>( aristas ) ) {
        if ( e.a.grupo == null || e.a.grupo != e.b.grupo ) {
          continue;
        }
        aristas.remove( e );
        Nodo a = e.a;
        Nodo b = e.b;
        for ( Arista p : new ArrayList<>( aristas ) ) {
          if ( p.b == a && p.a != b && !existe( p.a, b, p.clase ) ) {
            Arista x = new Arista( p.a, b, p.clase );
            x.rotulo = p.rotulo;
            x.activa = p.activa;
            aristas.add( x );
          }
        }
        String de = "← " + a.titulo;
        b.op = b.op == null || b.op.isEmpty() ? de : b.op.contains( de ) ? b.op : b.op + " " + de;
        boolean usado = a.grupo.destino;
        for ( Arista o : aristas ) {
          usado |= o.a == a;
        }
        if ( !usado ) {
          quitar( a );
        }
        cambio = true;
        break;
      }
    }
    for ( Grupo g : new ArrayList<>( grupos ) ) {
      if ( g.nodos.isEmpty() ) {
        grupos.remove( g );
      }
    }
  }

  private boolean existe( Nodo a, Nodo b, String clase ) {
    for ( Arista e : aristas ) {
      if ( e.a == a && e.b == b && e.clase.equals( clase ) ) {
        return true;
      }
    }
    return false;
  }

  private void quitar( Nodo n ) {
    nodos.remove( n );
    porId.remove( n.id );
    if ( n.grupo != null ) {
      n.grupo.nodos.remove( n );
    }
    aristas.removeIf( e -> e.a == n || e.b == n );
  }

  /** La misma operacion en todos los campos de un step (2 o mas) va una sola vez, en la cabecera. */
  private void opComun() {
    for ( Grupo g : grupos ) {
      if ( g.op != null || g.nodos.size() < 2 ) {
        continue;
      }
      String op = g.nodos.get( 0 ).op;
      boolean igual = op != null && !op.isEmpty();
      for ( Nodo n : g.nodos ) {
        igual &= op != null && op.equals( n.op );
      }
      if ( igual ) {
        g.op = op;
        for ( Nodo n : g.nodos ) {
          n.op = null;
        }
      }
    }
  }

  // ================================================================== piezas publicas

  /** Un step, una entrada de job o un campo del linaje. */
  public static final class Nodo {
    /** Identificador unico dentro del grafo (no se muestra). */
    public final String id;
    /** Lo que se ve en negrita (nombre del step o del campo). */
    public String titulo;
    /** Lineas tenues debajo del titulo (tipo de step, "copia las filas a 3 salidas"...). */
    public final List<String> lineas = new ArrayList<>();
    /** Linaje: la operacion que crea el campo (segunda linea, tenue). */
    public String op;
    /** Reparte o copia las filas a varias salidas (borde grueso). */
    public boolean multi;
    /** Copias en paralelo del step (tarjeta detras y la marca «×N»). */
    public int copias = 1;
    /** Plugin que falta en esta instalacion (borde rojo punteado). */
    public boolean falta;
    /** Entrada START de un job (forma de pildora). */
    public boolean inicio;
    /** Referencia a un step que esta en otra parte del diagrama partido (gris, punteado). */
    public boolean fantasma;

    Grupo grupo;
    /** Orden en el documento: desempata la disposicion. */
    int doc;

    // ---- medidas y disposicion (las recalcula cada dibujo)
    boolean ficticio;
    double w;
    double h;
    final List<String> tit = new ArrayList<>();
    final List<String> sub = new ArrayList<>();
    int capa = -1;
    double tamA;
    double tamC;
    double offA;
    double offC;
    Unidad unidad;
    int ranura;
    int indice;
    final List<Tramo> salen = new ArrayList<>();
    final List<Tramo> entran = new ArrayList<>();

    Nodo( String id ) {
      this.id = id;
    }

    public Grupo grupo() {
      return grupo;
    }

    Nodo copiar( String nuevoId ) {
      Nodo m = new Nodo( nuevoId );
      m.titulo = titulo;
      m.lineas.addAll( lineas );
      m.op = op;
      m.multi = multi;
      m.copias = copias;
      m.falta = falta;
      m.inicio = inicio;
      m.fantasma = fantasma;
      m.doc = doc;
      return m;
    }

    double a0() {
      return unidad.posA + offA;
    }

    double a1() {
      return a0() + tamA;
    }

    double cc() {
      return unidad.posC + offC + tamC / 2;
    }

    @Override
    public String toString() {
      return ficticio ? "·" + id : id;
    }
  }

  /** Salto entre dos nodos. */
  public static final class Arista {
    public final Nodo a;
    public final Nodo b;
    /** normal | error | info | ok | falla | siempre | clave. */
    public final String clase;
    /** Texto junto a la flecha ("verdadero", "error", "deshabilitado"); null = sin texto. */
    public String rotulo;
    /** false = salto deshabilitado (gris claro, punteado, con una cruz en la punta). */
    public boolean activa = true;

    boolean invertida;
    final List<Tramo> tramos = new ArrayList<>();

    Arista( Nodo a, Nodo b, String clase ) {
      this.a = a;
      this.b = b;
      this.clase = clase;
    }
  }

  /** Un step del linaje con sus campos. */
  public static final class Grupo {
    public final String nombre;
    /** Operacion comun a todos sus campos ("une", "busca"); va en la cabecera. */
    public String op;
    /** El step destino del linaje (borde azul). */
    public boolean destino;

    final List<Nodo> nodos = new ArrayList<>();
    Unidad unidad;
    final List<String> tit = new ArrayList<>();

    Grupo( String nombre ) {
      this.nombre = nombre;
    }

    public List<Nodo> campos() {
      return Collections.unmodifiableList( nodos );
    }
  }

  // ================================================================== piezas de la disposicion

  /** Pedazo de arista entre dos capas seguidas (los nodos ficticios parten las aristas largas). */
  static final class Tramo {
    final Nodo a;
    final Nodo b;
    final String clase;
    final int indice;
    final List<Arista> originales = new ArrayList<>();
    List<double[]> puntos;

    Tramo( Nodo a, Nodo b, String clase, int indice ) {
      this.a = a;
      this.b = b;
      this.clase = clase;
      this.indice = indice;
    }
  }

  /** Lo que se ordena y se mueve dentro de una capa: un nodo suelto o un grupo (step del linaje). */
  static final class Unidad {
    Grupo grupo;
    final List<Nodo> nodos = new ArrayList<>();
    double tamA;
    double tamC;
    double posA;
    double posC;
    double cab;
    int capa;
    double bar;
    int primera = Integer.MAX_VALUE;

    boolean ficticia() {
      return grupo == null && nodos.get( 0 ).ficticio;
    }
  }
}
