package io.github.ivi2511.pentahomcp.informes;

/**
 * Si una seccion aplica a la captura y cuanto cuesta (diseno §2.3, "Disponibilidad"). Lo devuelve
 * InformeServicio.disponibles; el panel lo usa para habilitar los checks y explicar por que no.
 *
 * @dueño WP0
 */
public final class Disponibilidad {

  /** Nada: solo lee la copia. */
  public static final String COSTO_NADA = "none";
  /** Pinta en la UI (lienzo). */
  public static final String COSTO_UI = "ui";
  /** Calcula campos (getStepFields: puede conectarse a la base). */
  public static final String COSTO_CAMPOS = "fields";
  /** Se conecta a la base. */
  public static final String COSTO_BASE = "database";
  /** Ejecuta una copia de la transformacion o el job (puede escribir). */
  public static final String COSTO_EJECUTA = "runs";

  /** Uno de SeccionId. */
  public String id;
  public boolean aplica;
  /** Si no aplica, por que (traducido); si no, null. */
  public String motivo;
  /** Marcada por defecto. */
  public boolean porDefecto;
  /** Una de las constantes COSTO_*. */
  public String costo;

  public Disponibilidad() {
  }

  public Disponibilidad( String id, boolean aplica, String motivo, boolean porDefecto, String costo ) {
    this.id = id;
    this.aplica = aplica;
    this.motivo = motivo;
    this.porDefecto = porDefecto;
    this.costo = costo;
  }
}
