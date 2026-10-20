package io.github.ivi2511.pentahomcp.informes;

import java.util.List;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Lo que recibe un {@link Recolector} al armar su seccion (diseno §7.3). La implementacion real es
 * ContextoKettle (WP2); en las pruebas hay una simple en src/test (ContextoPrueba).
 *
 * @dueño WP0
 */
public interface Contexto {

  Captura captura();

  Pedido pedido();

  /** en | es | pt. */
  String idioma();

  Tema tema();

  /** Textos de textos/&lt;area&gt;_&lt;idioma&gt;.properties. */
  Rotulos rotulos( String area );

  /** Steps de la copia en orden topologico (ReportMotor.ordenTrans). Vacia si es un job. */
  List<StepMeta> orden();

  /** Campos de salida del step, con memo y timeout compartidos entre recolectores. */
  RowMetaInterface salida( StepMeta s ) throws KettleStepException;

  /** Campos de entrada del step, con memo y timeout compartidos entre recolectores. */
  RowMetaInterface entrada( StepMeta s ) throws KettleStepException;

  Progreso progreso();

  boolean cancelado();

  /** Lo que queda del timeout total del pedido, en ms (nunca negativo). */
  long msRestantes();

  /** Agrega un aviso (ya traducido) a Informe.avisos. */
  void aviso( String texto );
}
