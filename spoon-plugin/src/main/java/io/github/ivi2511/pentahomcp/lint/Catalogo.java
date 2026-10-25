package io.github.ivi2511.pentahomcp.lint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.ivi2511.pentahomcp.lint.reglas.ReglasBoceto;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasCalculo;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasEstilo;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasEstructura;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasJob;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasOrden;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasPerfil;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasRobustez;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasRutas;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasSeguridad;

/**
 * Todas las reglas, en el orden en que corren: cycle primero (si hay ciclo, las que calculan campos no corren)
 * y kettle_check al final (puede conectarse a la BD). Es también el recurso pentaho://lint/rules (LintJson).
 */
public final class Catalogo {

  public static final String KETTLE_CHECK = "kettle_check";
  public static final String CYCLE = "cycle";

  private static final List<Regla> TODAS;

  static {
    List<Regla> l = new ArrayList<Regla>();
    l.add( ReglasEstructura.ciclo() );
    l.addAll( ReglasEstructura.todas() );
    l.addAll( ReglasJob.todas() );
    l.addAll( ReglasOrden.todas() );
    l.addAll( ReglasCalculo.todas() );
    l.addAll( ReglasRutas.todas() );
    l.addAll( ReglasSeguridad.todas() );
    l.addAll( ReglasEstilo.todas() );
    l.addAll( ReglasPerfil.todas() ); // apagadas: las enciende el perfil del proyecto (.pentaho-live/lint.yaml)
    l.addAll( ReglasBoceto.todas() ); // modo boceto (plan_build): placeholder_step
    l.addAll( ReglasRobustez.todas() ); // apagadas: corren con robustness=true y dan su sub-puntaje
    l.add( ReglasEstructura.kettleCheck() );
    Set<String> ids = new HashSet<String>();
    for ( Regla r : l ) {
      if ( !ids.add( r.id() ) ) {
        throw new IllegalStateException( "lint: rule id repeated: " + r.id() );
      }
    }
    TODAS = Collections.unmodifiableList( l );
  }

  private Catalogo() {
  }

  public static List<Regla> todas() {
    return TODAS;
  }

  public static Regla buscar( String id ) {
    if ( id == null ) {
      return null;
    }
    String x = id.trim().toLowerCase( Locale.ROOT );
    if ( x.startsWith( "lint:" ) ) {
      x = x.substring( 5 ); // el corrector los nombra lint:<id>
    }
    for ( Regla r : TODAS ) {
      if ( r.id().equals( x ) ) {
        return r;
      }
    }
    return null;
  }

  /**
   * Las reglas a correr. Sin ids: todas las que no están apagadas (kettle_check solo con kettleChecks). Con ids:
   * esas (también las apagadas). Un id que no existe es un error, con la lista de los válidos.
   */
  public static List<Regla> seleccionar( Set<String> ids, boolean kettleChecks ) {
    return seleccionar( ids, kettleChecks, false );
  }

  /**
   * Como {@link #seleccionar(Set, boolean)}; con robustez, además todas las reglas de la familia robustness (las
   * pedidas en ids siguen corriendo: sin ids, las de siempre + la familia).
   */
  public static List<Regla> seleccionar( Set<String> ids, boolean kettleChecks, boolean robustez ) {
    Set<String> pedidas = new LinkedHashSet<String>();
    if ( ids != null ) {
      List<String> malos = new ArrayList<String>();
      for ( String id : ids ) {
        Regla r = buscar( id );
        if ( r == null ) {
          malos.add( id );
        } else {
          pedidas.add( r.id() );
        }
      }
      if ( !malos.isEmpty() ) {
        throw new IllegalArgumentException( "Unknown lint rule(s): " + malos + ". Valid ids: " + idsValidos() );
      }
    }
    List<Regla> out = new ArrayList<Regla>();
    for ( Regla r : TODAS ) {
      boolean kc = r.id().equals( KETTLE_CHECK );
      boolean va = pedidas.isEmpty() ? !r.apagada() && !kc : pedidas.contains( r.id() );
      if ( kc && kettleChecks ) {
        va = true;
      }
      if ( robustez && Regla.ROBUSTNESS.equals( r.categoria() ) ) {
        va = true;
      }
      // cycle corre siempre que haga falta campos: decide si las demás pueden calcularlos
      if ( r.id().equals( CYCLE ) && !va && necesitaCiclo( pedidas, kettleChecks ) ) {
        va = true;
      }
      if ( va ) {
        out.add( r );
      }
    }
    return out;
  }

  private static boolean necesitaCiclo( Set<String> pedidas, boolean kettleChecks ) {
    if ( kettleChecks ) {
      return true;
    }
    for ( String id : pedidas ) {
      Regla r = buscar( id );
      if ( r != null && r.necesitaCampos() ) {
        return true;
      }
    }
    return false;
  }

  /** Los ids de la familia robustness, en el orden del catálogo. */
  public static List<String> idsRobustez() {
    List<String> l = new ArrayList<String>();
    for ( Regla r : TODAS ) {
      if ( Regla.ROBUSTNESS.equals( r.categoria() ) ) {
        l.add( r.id() );
      }
    }
    return l;
  }

  public static String idsValidos() {
    StringBuilder b = new StringBuilder();
    for ( Regla r : TODAS ) {
      b.append( b.length() == 0 ? "" : ", " ).append( r.id() );
    }
    return b.toString();
  }
}
