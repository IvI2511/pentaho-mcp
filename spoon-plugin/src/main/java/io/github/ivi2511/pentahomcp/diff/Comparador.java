package io.github.ivi2511.pentahomcp.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ivi2511.pentahomcp.diff.Diferencia.Como;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Propiedad;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Que;
import io.github.ivi2511.pentahomcp.diff.Documento.Nodo;
import io.github.ivi2511.pentahomcp.diff.Documento.Nota;
import io.github.ivi2511.pentahomcp.diff.Documento.Salto;

/**
 * Compara dos versiones de una transformación o un job por significado (diseño E1): steps o entradas agregados,
 * quitados, renombrados (por tipo, configuración, vecinos y posición), propiedades que cambiaron, saltos, manejo de
 * errores, conexiones (sin contraseñas), parámetros, notas y propiedades del documento. Con ignorarPosiciones, mover
 * cajas no cuenta.
 */
public final class Comparador {

  /** Puntaje mínimo para dar por renombrado un step (0..1). */
  static final double UMBRAL_RENOMBRE = 0.6;
  /** Con una configuración propia así de parecida (y de 3 propiedades o más) también es un renombre. */
  static final double UMBRAL_CONFIG = 0.85;
  /** Distancia (px) a la que la posición ya no ayuda a reconocer un renombre. */
  static final double DISTANCIA = 400;

  private Comparador() {
  }

  public static ResultadoDiff comparar( Documento a, Documento b, boolean ignorarPosiciones ) {
    if ( !a.tipo.equals( b.tipo ) ) {
      throw new IllegalArgumentException( "Cannot compare a " + a.tipo + " with a " + b.tipo );
    }
    ResultadoDiff r = new ResultadoDiff();
    r.tipo = a.tipo;
    r.nombreAntes = a.nombre;
    r.nombreDespues = b.nombre;
    r.ignorarPosiciones = ignorarPosiciones;

    Map<String, String> parClaves = emparejar( a, b ); // clave de después -> clave de antes
    Map<String, String> renombres = new HashMap<String, String>(); // nombre de después -> nombre de antes
    for ( Map.Entry<String, String> e : parClaves.entrySet() ) {
      if ( !e.getKey().equals( e.getValue() ) ) {
        r.renombrados.put( e.getKey(), e.getValue() );
        renombres.put( b.nodos.get( e.getKey() ).nombre, a.nodos.get( e.getValue() ).nombre );
      }
    }

    documento( a, b, renombres, r );
    nodos( a, b, parClaves, renombres, ignorarPosiciones, r );
    saltos( a, b, parClaves, r );
    errores( a, b, renombres, r );
    conexiones( a, b, renombres, r );
    parametros( a, b, r );
    notas( a, b, ignorarPosiciones, r );
    return r;
  }

  // ================================================================ documento

  private static void documento( Documento a, Documento b, Map<String, String> ren, ResultadoDiff r ) {
    List<Propiedad> ps = new ArrayList<Propiedad>();
    if ( b.esJob() && !igual( a.nombre, b.nombre ) ) {
      ps.add( new Propiedad( Propiedad.VALOR, "name", a.nombre, b.nombre ) );
    }
    ps.addAll( propiedades( a.propiedades, b.propiedades, ren ) );
    if ( !ps.isEmpty() ) {
      Diferencia d = new Diferencia( Que.DOCUMENTO, Como.CAMBIADO, b.nombre );
      d.propiedades.addAll( ps );
      r.cambios.add( d );
    }
  }

  // ================================================================ steps y entradas

  /** Empareja los nodos: primero por clave; después los renombrados, por tipo, configuración, vecinos y posición. */
  static Map<String, String> emparejar( Documento a, Documento b ) {
    Map<String, String> par = new LinkedHashMap<String, String>();
    for ( String k : b.nodos.keySet() ) {
      if ( a.nodos.containsKey( k ) ) {
        par.put( k, k );
      }
    }
    for ( int ronda = 0; ronda < 3; ronda++ ) {
      Set<String> usadosA = new HashSet<String>( par.values() );
      Map<String, Set<String>> vecA = vecinos( a, null );
      Map<String, Set<String>> vecB = vecinos( b, par );
      List<Object[]> candidatos = new ArrayList<Object[]>();
      for ( Nodo nb : b.nodos.values() ) {
        if ( par.containsKey( nb.clave ) ) {
          continue;
        }
        for ( Nodo na : a.nodos.values() ) {
          if ( usadosA.contains( na.clave ) || !na.tipo.equals( nb.tipo ) ) {
            continue;
          }
          double[] p = puntaje( na, nb, vecA.get( na.clave ), vecB.get( nb.clave ) );
          if ( p[ 0 ] >= UMBRAL_RENOMBRE || p[ 1 ] >= UMBRAL_CONFIG && p[ 2 ] >= 3 ) {
            candidatos.add( new Object[] { p[ 0 ], na.clave, nb.clave } );
          }
        }
      }
      if ( candidatos.isEmpty() ) {
        break;
      }
      Collections.sort( candidatos, ( x, y ) -> Double.compare( (Double) y[ 0 ], (Double) x[ 0 ] ) );
      Set<String> usadosB = new HashSet<String>();
      boolean algo = false;
      for ( Object[] c : candidatos ) {
        String ka = (String) c[ 1 ];
        String kb = (String) c[ 2 ];
        if ( usadosA.contains( ka ) || usadosB.contains( kb ) ) {
          continue;
        }
        par.put( kb, ka );
        usadosA.add( ka );
        usadosB.add( kb );
        algo = true;
      }
      if ( !algo ) {
        break;
      }
    }
    return par;
  }

  /** {puntaje total, parecido de la configuración propia, cuántas propiedades propias tiene el más chico}. */
  static double[] puntaje( Nodo a, Nodo b, Set<String> vecA, Set<String> vecB ) {
    Map<String, String> pa = a.propias();
    Map<String, String> pb = b.propias();
    double cfg;
    if ( pa.isEmpty() && pb.isEmpty() ) {
      cfg = 0.5; // sin configuración propia (un Dummy): no dice nada
    } else {
      Set<String> union = new HashSet<String>( pa.keySet() );
      union.addAll( pb.keySet() );
      int iguales = 0;
      for ( String k : union ) {
        if ( pa.containsKey( k ) && igual( pa.get( k ), pb.get( k ) ) ) {
          iguales++;
        }
      }
      cfg = union.isEmpty() ? 0.5 : (double) iguales / union.size();
    }
    double vec;
    Set<String> va = vecA == null ? Collections.<String>emptySet() : vecA;
    Set<String> vb = vecB == null ? Collections.<String>emptySet() : vecB;
    if ( va.isEmpty() && vb.isEmpty() ) {
      vec = 0.5;
    } else {
      Set<String> u = new HashSet<String>( va );
      u.addAll( vb );
      Set<String> i = new HashSet<String>( va );
      i.retainAll( vb );
      vec = (double) i.size() / u.size();
    }
    double d = Math.hypot( a.x - b.x, a.y - b.y );
    double pos = Math.max( 0, 1 - d / DISTANCIA );
    return new double[] { 0.55 * cfg + 0.3 * vec + 0.15 * pos, cfg, Math.min( pa.size(), pb.size() ) };
  }

  /** Vecinos de cada nodo ("&gt;destino", "&lt;origen"), con los nombres traducidos a la versión de antes. */
  private static Map<String, Set<String>> vecinos( Documento d, Map<String, String> traducir ) {
    Map<String, Set<String>> v = new HashMap<String, Set<String>>();
    for ( Salto s : d.saltos ) {
      String desde = traducir == null ? s.desde : traducir.containsKey( s.desde ) ? traducir.get( s.desde )
        : "?" + s.desde;
      String hasta = traducir == null ? s.hasta : traducir.containsKey( s.hasta ) ? traducir.get( s.hasta )
        : "?" + s.hasta;
      agregar( v, s.desde, ">" + hasta );
      agregar( v, s.hasta, "<" + desde );
    }
    return v;
  }

  private static void agregar( Map<String, Set<String>> m, String k, String v ) {
    Set<String> s = m.get( k );
    if ( s == null ) {
      s = new HashSet<String>();
      m.put( k, s );
    }
    s.add( v );
  }

  private static void nodos( Documento a, Documento b, Map<String, String> par, Map<String, String> ren,
                             boolean ignorarPos, ResultadoDiff r ) {
    Que que = b.esJob() ? Que.ENTRADA : Que.STEP;
    Set<String> emparejadosA = new HashSet<String>( par.values() );
    for ( Nodo nb : b.nodos.values() ) {
      String ka = par.get( nb.clave );
      if ( ka == null ) {
        Diferencia d = new Diferencia( que, Como.AGREGADO, nb.clave );
        d.tipo = nb.tipo;
        d.posDespues = new int[] { nb.x, nb.y };
        r.cambios.add( d );
        continue;
      }
      Nodo na = a.nodos.get( ka );
      boolean renombrado = !ka.equals( nb.clave );
      List<Propiedad> ps = new ArrayList<Propiedad>();
      if ( !na.tipo.equals( nb.tipo ) ) {
        ps.add( new Propiedad( Propiedad.TIPO, "type", na.tipo, nb.tipo ) );
      } else {
        ps.addAll( propiedades( na.config, nb.config, ren ) );
      }
      boolean movido = na.x != nb.x || na.y != nb.y;
      if ( movido && !ignorarPos ) {
        ps.add( new Propiedad( Propiedad.POSICION, "position", na.x + "," + na.y, nb.x + "," + nb.y ) );
      }
      if ( !renombrado && ps.isEmpty() ) {
        continue;
      }
      boolean soloPosicion = ps.size() == 1 && Propiedad.POSICION.equals( ps.get( 0 ).clase );
      Como como = renombrado ? Como.RENOMBRADO : soloPosicion ? Como.MOVIDO : Como.CAMBIADO;
      Diferencia d = new Diferencia( que, como, nb.clave );
      d.nombreAntes = renombrado ? ka : null;
      d.tipo = nb.tipo;
      d.posAntes = new int[] { na.x, na.y };
      d.posDespues = new int[] { nb.x, nb.y };
      d.propiedades.addAll( ps );
      r.cambios.add( d );
    }
    for ( Nodo na : a.nodos.values() ) {
      if ( !emparejadosA.contains( na.clave ) ) {
        Diferencia d = new Diferencia( que, Como.QUITADO, na.clave );
        d.tipo = na.tipo;
        d.posAntes = new int[] { na.x, na.y };
        r.cambios.add( d );
      }
    }
  }

  // ================================================================ saltos

  private static void saltos( Documento a, Documento b, Map<String, String> par, ResultadoDiff r ) {
    Map<String, Salto> sa = new LinkedHashMap<String, Salto>();
    for ( Salto s : a.saltos ) {
      sa.put( s.desde + "\u0001" + s.hasta, s );
    }
    Set<String> vistos = new HashSet<String>();
    for ( Salto s : b.saltos ) {
      String da = par.containsKey( s.desde ) ? par.get( s.desde ) : "\u0002" + s.desde;
      String ha = par.containsKey( s.hasta ) ? par.get( s.hasta ) : "\u0002" + s.hasta;
      String k = da + "\u0001" + ha;
      Salto x = sa.get( k );
      if ( x == null ) {
        Diferencia d = salto( Como.AGREGADO, s );
        if ( !s.activo ) {
          d.con( new Propiedad( Propiedad.VALOR, "enabled", null, "N" ) );
        }
        if ( s.error ) {
          d.con( new Propiedad( Propiedad.VALOR, "error", null, "Y" ) );
        }
        if ( s.condicion != null ) {
          d.con( new Propiedad( Propiedad.VALOR, "condition", null, s.condicion ) );
        }
        r.cambios.add( d );
        continue;
      }
      vistos.add( k );
      Diferencia d = salto( Como.CAMBIADO, s );
      if ( x.activo != s.activo ) {
        d.con( new Propiedad( Propiedad.VALOR, "enabled", yn( x.activo ), yn( s.activo ) ) );
      }
      if ( x.error != s.error ) {
        d.con( new Propiedad( Propiedad.VALOR, "error", yn( x.error ), yn( s.error ) ) );
      }
      if ( !igual( x.condicion, s.condicion ) ) {
        d.con( new Propiedad( Propiedad.VALOR, "condition", x.condicion, s.condicion ) );
      }
      if ( !d.propiedades.isEmpty() ) {
        r.cambios.add( d );
      }
    }
    for ( Map.Entry<String, Salto> e : sa.entrySet() ) {
      if ( !vistos.contains( e.getKey() ) ) {
        Diferencia d = salto( Como.QUITADO, e.getValue() );
        if ( e.getValue().error ) {
          d.con( new Propiedad( Propiedad.VALOR, "error", "Y", null ) );
        }
        r.cambios.add( d );
      }
    }
  }

  private static Diferencia salto( Como como, Salto s ) {
    Diferencia d = new Diferencia( Que.SALTO, como, s.desde + " -> " + s.hasta );
    d.desde = s.desde;
    d.hasta = s.hasta;
    return d;
  }

  private static String yn( boolean b ) {
    return b ? "Y" : "N";
  }

  // ================================================================ manejo de errores, conexiones, parámetros

  private static void errores( Documento a, Documento b, Map<String, String> ren, ResultadoDiff r ) {
    Set<String> vistos = new HashSet<String>();
    for ( Map.Entry<String, LinkedHashMap<String, String>> e : b.errores.entrySet() ) {
      String ka = ren.containsKey( e.getKey() ) ? ren.get( e.getKey() ) : e.getKey();
      LinkedHashMap<String, String> x = a.errores.get( ka );
      if ( x == null ) {
        Diferencia d = new Diferencia( Que.ERRORES, Como.AGREGADO, e.getKey() );
        for ( Map.Entry<String, String> p : e.getValue().entrySet() ) {
          if ( !p.getValue().isEmpty() ) {
            d.con( new Propiedad( Propiedad.VALOR, p.getKey(), null, p.getValue() ) );
          }
        }
        r.cambios.add( d );
        continue;
      }
      vistos.add( ka );
      List<Propiedad> ps = propiedades( x, e.getValue(), ren );
      if ( !ps.isEmpty() ) {
        Diferencia d = new Diferencia( Que.ERRORES, Como.CAMBIADO, e.getKey() );
        d.propiedades.addAll( ps );
        r.cambios.add( d );
      }
    }
    for ( String k : a.errores.keySet() ) {
      if ( !vistos.contains( k ) ) {
        r.cambios.add( new Diferencia( Que.ERRORES, Como.QUITADO, k ) );
      }
    }
  }

  private static void conexiones( Documento a, Documento b, Map<String, String> ren, ResultadoDiff r ) {
    for ( Map.Entry<String, LinkedHashMap<String, String>> e : b.conexiones.entrySet() ) {
      LinkedHashMap<String, String> x = a.conexiones.get( e.getKey() );
      if ( x == null ) {
        Diferencia d = new Diferencia( Que.CONEXION, Como.AGREGADO, e.getKey() );
        d.tipo = e.getValue().get( "type" );
        r.cambios.add( d );
        continue;
      }
      List<Propiedad> ps = propiedades( x, e.getValue(), ren );
      if ( !ps.isEmpty() ) {
        Diferencia d = new Diferencia( Que.CONEXION, Como.CAMBIADO, e.getKey() );
        d.tipo = e.getValue().get( "type" );
        d.propiedades.addAll( ps );
        r.cambios.add( d );
      }
    }
    for ( Map.Entry<String, LinkedHashMap<String, String>> e : a.conexiones.entrySet() ) {
      if ( !b.conexiones.containsKey( e.getKey() ) ) {
        Diferencia d = new Diferencia( Que.CONEXION, Como.QUITADO, e.getKey() );
        d.tipo = e.getValue().get( "type" );
        r.cambios.add( d );
      }
    }
  }

  private static void parametros( Documento a, Documento b, ResultadoDiff r ) {
    for ( Map.Entry<String, String[]> e : b.parametros.entrySet() ) {
      String[] x = a.parametros.get( e.getKey() );
      String[] y = e.getValue();
      if ( x == null ) {
        Diferencia d = new Diferencia( Que.PARAMETRO, Como.AGREGADO, e.getKey() );
        d.con( new Propiedad( Propiedad.VALOR, "default_value", null, y[ 0 ] ) );
        if ( !y[ 1 ].isEmpty() ) {
          d.con( new Propiedad( Propiedad.VALOR, "description", null, y[ 1 ] ) );
        }
        r.cambios.add( d );
        continue;
      }
      Diferencia d = new Diferencia( Que.PARAMETRO, Como.CAMBIADO, e.getKey() );
      if ( !igual( x[ 0 ], y[ 0 ] ) ) {
        d.con( new Propiedad( Propiedad.VALOR, "default_value", x[ 0 ], y[ 0 ] ) );
      }
      if ( !igual( x[ 1 ], y[ 1 ] ) ) {
        d.con( new Propiedad( Propiedad.VALOR, "description", x[ 1 ], y[ 1 ] ) );
      }
      if ( !d.propiedades.isEmpty() ) {
        r.cambios.add( d );
      }
    }
    for ( Map.Entry<String, String[]> e : a.parametros.entrySet() ) {
      if ( !b.parametros.containsKey( e.getKey() ) ) {
        Diferencia d = new Diferencia( Que.PARAMETRO, Como.QUITADO, e.getKey() );
        d.con( new Propiedad( Propiedad.VALOR, "default_value", e.getValue()[ 0 ], null ) );
        r.cambios.add( d );
      }
    }
  }

  // ================================================================ notas

  private static void notas( Documento a, Documento b, boolean ignorarPos, ResultadoDiff r ) {
    List<Nota> restA = new ArrayList<Nota>( a.notas );
    List<Nota> restB = new ArrayList<Nota>();
    for ( Nota nb : b.notas ) {
      Nota par = null;
      for ( Nota na : restA ) {
        if ( na.texto.equals( nb.texto ) ) {
          par = na;
          break;
        }
      }
      if ( par == null ) {
        restB.add( nb );
        continue;
      }
      restA.remove( par );
      List<Propiedad> ps = new ArrayList<Propiedad>();
      if ( !igual( par.formato, nb.formato ) ) {
        ps.add( new Propiedad( Propiedad.VALOR, "format", null, null ) );
      }
      boolean movida = par.x != nb.x || par.y != nb.y;
      if ( movida && !ignorarPos ) {
        ps.add( new Propiedad( Propiedad.POSICION, "position", par.x + "," + par.y, nb.x + "," + nb.y ) );
      }
      if ( !ps.isEmpty() ) {
        boolean soloPos = ps.size() == 1 && Propiedad.POSICION.equals( ps.get( 0 ).clase );
        Diferencia d = new Diferencia( Que.NOTA, soloPos ? Como.MOVIDO : Como.CAMBIADO, titulo( nb.texto ) );
        d.posAntes = new int[] { par.x, par.y };
        d.posDespues = new int[] { nb.x, nb.y };
        d.propiedades.addAll( ps );
        r.cambios.add( d );
      }
    }
    // las que cambiaron de texto: se emparejan en orden
    int n = Math.min( restA.size(), restB.size() );
    for ( int i = 0; i < n; i++ ) {
      Nota na = restA.get( i );
      Nota nb = restB.get( i );
      Diferencia d = new Diferencia( Que.NOTA, Como.CAMBIADO, titulo( nb.texto ) );
      d.posAntes = new int[] { na.x, na.y };
      d.posDespues = new int[] { nb.x, nb.y };
      d.con( new Propiedad( Propiedad.VALOR, "text", na.texto, nb.texto ) );
      r.cambios.add( d );
    }
    for ( int i = n; i < restB.size(); i++ ) {
      Nota nb = restB.get( i );
      Diferencia d = new Diferencia( Que.NOTA, Como.AGREGADO, titulo( nb.texto ) );
      d.posDespues = new int[] { nb.x, nb.y };
      d.con( new Propiedad( Propiedad.VALOR, "text", null, nb.texto ) );
      r.cambios.add( d );
    }
    for ( int i = n; i < restA.size(); i++ ) {
      Nota na = restA.get( i );
      Diferencia d = new Diferencia( Que.NOTA, Como.QUITADO, titulo( na.texto ) );
      d.posAntes = new int[] { na.x, na.y };
      d.con( new Propiedad( Propiedad.VALOR, "text", na.texto, null ) );
      r.cambios.add( d );
    }
  }

  /** La primera línea de una nota, cortada. */
  static String titulo( String texto ) {
    String t = texto == null ? "" : texto.trim();
    int nl = t.indexOf( '\n' );
    if ( nl >= 0 ) {
      t = t.substring( 0, nl ).trim() + " …";
    }
    return t.length() > 60 ? t.substring( 0, 57 ) + "…" : t;
  }

  // ================================================================ propiedades

  /**
   * Las propiedades que cambiaron entre dos mapas aplanados. Un elemento de lista que está en uno solo sale como uno
   * agregado o quitado (no como cada una de sus propiedades); el orden de una lista con nombres, solo si cambió el de
   * los elementos que están en las dos. Un valor que es el nombre nuevo de un step renombrado vale lo mismo que el
   * nombre viejo.
   */
  static List<Propiedad> propiedades( Map<String, String> a, Map<String, String> b, Map<String, String> ren ) {
    List<Propiedad> out = new ArrayList<Propiedad>();
    Set<String> claves = new LinkedHashSet<String>( a.keySet() );
    claves.addAll( b.keySet() );
    Set<String> elementosHechos = new HashSet<String>();
    for ( String k : claves ) {
      if ( k.endsWith( Aplanar.ORDEN ) ) {
        orden( k, a.get( k ), b.get( k ), out );
        continue;
      }
      String elem = elemento( k );
      if ( elem != null ) {
        if ( elementosHechos.contains( elem ) ) {
          continue;
        }
        boolean enA = tiene( a, elem );
        boolean enB = tiene( b, elem );
        if ( enA != enB ) {
          elementosHechos.add( elem );
          Map<String, String> m = enA ? a : b;
          String resumen = resumen( m, elem );
          out.add( new Propiedad( enA ? Propiedad.ELEMENTO_QUITADO : Propiedad.ELEMENTO_AGREGADO, elem,
            enA ? resumen : null, enA ? null : resumen ) );
          continue;
        }
      }
      String va = a.get( k );
      String vb = b.get( k );
      if ( igualConRenombres( va, vb, ren ) ) {
        continue;
      }
      out.add( new Propiedad( Propiedad.VALOR, k, va, vb ) );
    }
    return out;
  }

  /** La ruta del elemento de lista con nombre que contiene a k ("fields/field[saldo]"), o null. */
  static String elemento( String k ) {
    String[] seg = k.split( "/" );
    StringBuilder sb = new StringBuilder();
    for ( String s : seg ) {
      sb.append( sb.length() == 0 ? "" : "/" ).append( s );
      if ( s.endsWith( "]" ) ) {
        return sb.toString();
      }
    }
    return null;
  }

  private static boolean tiene( Map<String, String> m, String elem ) {
    for ( String k : m.keySet() ) {
      if ( k.equals( elem ) || k.startsWith( elem + "/" ) ) {
        return true;
      }
    }
    return false;
  }

  /** "tipo=String, length=50" con las propiedades no vacías de un elemento (sin secretos), hasta 6. */
  static String resumen( Map<String, String> m, String elem ) {
    StringBuilder sb = new StringBuilder();
    int n = 0;
    for ( Map.Entry<String, String> e : m.entrySet() ) {
      String k = e.getKey();
      if ( !( k.equals( elem ) || k.startsWith( elem + "/" ) ) || e.getValue().isEmpty()
        || k.endsWith( Aplanar.ORDEN ) ) {
        continue;
      }
      String sub = k.equals( elem ) ? "" : k.substring( elem.length() + 1 );
      if ( sub.equals( "name" ) ) {
        continue;
      }
      String v = Aplanar.esSecreto( e.getValue() ) ? "***" : e.getValue().replace( '\n', ' ' );
      if ( v.length() > 40 ) {
        v = v.substring( 0, 37 ) + "…";
      }
      if ( n++ == 6 ) {
        sb.append( ", …" );
        break;
      }
      sb.append( sb.length() == 0 ? "" : ", " ).append( sub.isEmpty() ? v : sub + "=" + v );
    }
    return sb.toString();
  }

  private static void orden( String k, String a, String b, List<Propiedad> out ) {
    if ( a == null || b == null || a.equals( b ) ) {
      return;
    }
    List<String> la = lista( a );
    List<String> lb = lista( b );
    List<String> comunesA = new ArrayList<String>( la );
    comunesA.retainAll( lb );
    List<String> comunesB = new ArrayList<String>( lb );
    comunesB.retainAll( la );
    if ( !comunesA.equals( comunesB ) ) {
      out.add( new Propiedad( Propiedad.ORDEN, k.substring( 0, k.length() - Aplanar.ORDEN.length() ), a, b ) );
    }
  }

  private static List<String> lista( String s ) {
    List<String> l = new ArrayList<String>();
    for ( String x : s.split( ", " ) ) {
      l.add( x );
    }
    return l;
  }

  static boolean igualConRenombres( String va, String vb, Map<String, String> ren ) {
    if ( igual( va, vb ) ) {
      return true;
    }
    return vb != null && ren != null && ren.containsKey( vb ) && ren.get( vb ).equals( va );
  }

  /** Iguales; null y "" valen lo mismo (Kettle escribe un tag vacío o no lo escribe). */
  static boolean igual( String a, String b ) {
    String x = a == null ? "" : a;
    String y = b == null ? "" : b;
    return x.equals( y );
  }
}
