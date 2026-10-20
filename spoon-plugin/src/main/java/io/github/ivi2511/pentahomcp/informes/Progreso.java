package io.github.ivi2511.pentahomcp.informes;

/**
 * Avance y cancelacion de una tarea larga. Se llama desde workers: la UI lo agrupa (1 asyncExec cada
 * 100 ms como mucho).
 *
 * @dueño WP0
 */
public interface Progreso {

  /** No muestra nada y nunca cancela. */
  Progreso NULO = new Progreso() {
    @Override
    public void paso( String texto, int hecho, int total ) {
    }

    @Override
    public boolean cancelado() {
      return false;
    }
  };

  /** Texto ya traducido ("Calculando linaje..."), con hecho de total (total = 0 si no se sabe). */
  void paso( String texto, int hecho, int total );

  boolean cancelado();
}
