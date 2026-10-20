package io.github.ivi2511.pentahomcp.informes;

import java.util.Date;

import org.eclipse.swt.graphics.ImageData;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.Job;
import org.pentaho.di.trans.Trans;

import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;

/**
 * Foto del documento tomada en la UI (CapturaUi) o cargada de un archivo: la copia desligada, el
 * lienzo y la ultima corrida (diseno §1.4 y §7.3). Es la unica clase del contrato que nombra SWT, y
 * solo como dato.
 *
 * @dueño WP0
 */
public final class Captura {

  public static final String TRANSFORMACION = "transformation";
  public static final String JOB = "job";

  /** Copia desligada (ReportTools.clonar), con repo y metastore. TransMeta o JobMeta. */
  public AbstractMeta copia;
  /** Id de Docs (el mismo que usan las tools MCP). */
  public String docId;
  public String nombre;
  /** Ruta del .ktr/.kjb; null si nunca se guardo. */
  public String archivo;
  /** transformation | job. */
  public String tipo;
  /** true si el documento tenia cambios sin guardar al capturarlo. */
  public boolean sinGuardar;
  /** Lo deja la UI (ReportImagen.pintar, escala 2). Solo dato: nadie dibuja con esto fuera de la UI. */
  public ImageData lienzoCrudo;
  /** El worker codifica lienzoCrudo (ReportImagen.png), lo deja aca y vacia lienzoCrudo. */
  public Imagen lienzo;
  /**
   * Por que no se pudo pintar el lienzo de un documento con steps o entradas (null si se pinto o si el
   * documento esta vacio). El informe lo avisa: sin esto, un lienzo que faltaba pasaba en silencio.
   */
  public String lienzoFallo;
  /** Ultima corrida en Spoon (TransGraph.getTrans / JobGraph.getJob), o null. */
  public Trans ultimaTrans;
  public Job ultimoJob;
  public Date capturado;

  public boolean esTransformacion() {
    return TRANSFORMACION.equals( tipo );
  }

  public boolean esJob() {
    return JOB.equals( tipo );
  }
}
