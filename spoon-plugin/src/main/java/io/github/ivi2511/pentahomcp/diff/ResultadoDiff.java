package io.github.ivi2511.pentahomcp.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lo que sale de {@link Comparador#comparar}: los cambios y lo que hace falta para mostrarlos. */
public final class ResultadoDiff {

  /** transformation | job. */
  public String tipo;
  public String nombreAntes;
  public String nombreDespues;
  /** De dónde sale cada versión (archivo, "guardado", "git HEAD"...), para los títulos. */
  public String etiquetaAntes;
  public String etiquetaDespues;
  public boolean ignorarPosiciones;
  public final List<Diferencia> cambios = new ArrayList<Diferencia>();
  /** Steps o entradas renombrados: clave de después -> clave de antes. */
  public final Map<String, String> renombrados = new LinkedHashMap<String, String>();

  public boolean iguales() {
    return cambios.isEmpty();
  }

  /** Cuántos cambios hay de ese tipo y esa clase (null = cualquiera). */
  public int cuenta( Diferencia.Que que, Diferencia.Como como ) {
    int n = 0;
    for ( Diferencia d : cambios ) {
      if ( ( que == null || d.que == que ) && ( como == null || d.como == como ) ) {
        n++;
      }
    }
    return n;
  }

  /** Los cambios de un tipo, en el orden del texto: agregados, quitados, renombrados, cambiados, movidos. */
  public List<Diferencia> de( Diferencia.Que que ) {
    List<Diferencia> out = new ArrayList<Diferencia>();
    for ( Diferencia.Como c : Diferencia.Como.values() ) {
      for ( Diferencia d : cambios ) {
        if ( d.que == que && d.como == c ) {
          out.add( d );
        }
      }
    }
    return out;
  }

  /** Steps o entradas, sea cual sea el tipo de documento. */
  public Diferencia.Que nodos() {
    return Documento.JOB.equals( tipo ) ? Diferencia.Que.ENTRADA : Diferencia.Que.STEP;
  }
}
