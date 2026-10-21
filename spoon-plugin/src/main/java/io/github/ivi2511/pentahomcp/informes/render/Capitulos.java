package io.github.ivi2511.pentahomcp.informes.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Capitulo;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;

/**
 * Las secciones de un informe agrupadas por capitulo, en el orden en que se dibujan. Un informe simple da
 * un solo grupo (capitulo null) y todo sale como antes; uno combinado da la parte general (capitulo null:
 * portada e indice del conjunto) y despues un grupo por {@link Informe#capitulos}, cada uno con sus
 * secciones en el orden de {@link SeccionId#ORDEN}. Lo usan todos los renderizadores.
 *
 * @dueño M
 */
public final class Capitulos {

  /** Un grupo de secciones: la parte general o un documento. */
  public static final class Grupo {
    /** null = la parte general de un informe combinado, o el informe simple entero. */
    public final Capitulo capitulo;
    /** 1, 2... en los capitulos; 0 en la parte general. */
    public final int numero;
    /** La seccion cover del grupo, o null. */
    public Seccion portada;
    /** Las demas secciones (omitidas incluidas: el indice las nombra), en el orden del informe. */
    public final List<Seccion> secciones = new ArrayList<>();

    Grupo( Capitulo capitulo, int numero ) {
      this.capitulo = capitulo;
      this.numero = numero;
    }

    /** Las secciones que se dibujan: sin las omitidas. */
    public List<Seccion> vivas() {
      List<Seccion> l = new ArrayList<>();
      for ( Seccion s : secciones ) {
        if ( !Seccion.OMITIDA.equals( s.estado ) ) {
          l.add( s );
        }
      }
      return l;
    }
  }

  private Capitulos() {
  }

  /** Los grupos en orden: la parte general primero (si tiene algo) y despues los capitulos. */
  public static List<Grupo> grupos( Informe inf ) {
    Map<String, Grupo> porId = new LinkedHashMap<>();
    Grupo general = new Grupo( null, 0 );
    List<Grupo> out = new ArrayList<>();
    int n = 0;
    if ( inf.capitulos != null ) {
      for ( Capitulo c : inf.capitulos ) {
        if ( c != null && c.id != null && !porId.containsKey( c.id ) ) {
          porId.put( c.id, new Grupo( c, ++n ) );
        }
      }
    }
    for ( Seccion s : RenderHtml.ordenadas( inf ) ) {
      Grupo g = s.capitulo == null ? general : porId.get( s.capitulo );
      if ( g == null ) {
        g = general; // capitulo desconocido: a la parte general antes que perderla
      }
      if ( SeccionId.PORTADA.equals( s.id ) && g.portada == null ) {
        g.portada = s;
      } else {
        g.secciones.add( s );
      }
    }
    if ( porId.isEmpty() || general.portada != null || !general.secciones.isEmpty() ) {
      out.add( general );
    }
    out.addAll( porId.values() );
    return Collections.unmodifiableList( out );
  }

  /** Ancla estable de una seccion: "sec-flow", o "sec-d2-flow" en un capitulo. */
  public static String ancla( Seccion s ) {
    return "sec-" + ( s.capitulo == null ? "" : Html.idSeguro( s.capitulo ) + "-" ) + Html.idSeguro( s.id );
  }

  /**
   * "2.3" para la tercera seccion del segundo capitulo; null fuera de los capitulos (informe simple, parte
   * general): ahi los titulos van sin numero, como hasta ahora.
   */
  public static String numero( Grupo g, int indiceSeccion ) {
    return g.capitulo == null ? null : g.numero + "." + ( indiceSeccion + 1 );
  }
}
