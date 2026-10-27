package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.List;

/**
 * El resultado de traducir una transformación: los bloques en orden de ejecución y el contexto (imports, ayudantes,
 * VARS, conexiones). Los escritores ({@link EscritorPy}, {@link EscritorIpynb}) lo convierten en texto.
 */
public final class Programa {

  /** Nombre de la transformación. */
  public final String nombre;
  /** Ruta del .ktr (o null si no está guardada). */
  public final String archivo;
  public final CtxTrad ctx;
  public final List<Bloque> bloques = new ArrayList<>();

  public Programa( String nombre, String archivo, CtxTrad ctx ) {
    this.nombre = nombre;
    this.archivo = archivo;
    this.ctx = ctx;
  }

  public Destino destino() {
    return ctx.destino;
  }

  /** Cuántos bloques hay en cada estado. */
  public int cuantos( Estado e ) {
    int n = 0;
    for ( Bloque b : bloques ) {
      if ( b.estado == e ) {
        n++;
      }
    }
    return n;
  }

  /** El peor estado del programa (EXACTO si no tiene bloques). */
  public Estado estado() {
    Estado r = Estado.EXACTO;
    for ( Bloque b : bloques ) {
      r = r.peor( b.estado );
    }
    return r;
  }
}
