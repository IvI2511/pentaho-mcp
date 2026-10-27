package io.github.ivi2511.pentahomcp.traductor;

/** Qué tan fiel es la traducción de un step o de una entrada de job. */
public enum Estado {
  /** Hace lo mismo que PDI con los mismos datos. */
  EXACTO,
  /** Hace lo mismo salvo diferencias conocidas y anotadas (redondeo, formatos, orden, nulos...). */
  APROXIMADO,
  /** Queda un esqueleto con TODO: la persona lo completa a mano (código propio, steps sin traducción). */
  MANUAL;

  public String id() {
    return name().toLowerCase();
  }

  /** El peor de los dos (MANUAL &gt; APROXIMADO &gt; EXACTO). */
  public Estado peor( Estado o ) {
    return o != null && o.ordinal() > ordinal() ? o : this;
  }
}
