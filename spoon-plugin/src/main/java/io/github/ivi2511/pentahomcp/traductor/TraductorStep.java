package io.github.ivi2511.pentahomcp.traductor;

import org.pentaho.di.trans.step.StepMeta;

/**
 * Traduce un tipo de step a Python. Lee la configuración del {@link StepMeta} (o su XML con
 * {@link CtxTrad#xml(StepMeta)}), toma los DataFrames de entrada de {@link CtxTrad#entradas(StepMeta)} y deja el de
 * salida en {@link CtxTrad#df(StepMeta)} (o los que registre con {@link CtxTrad#ponerSalida}). Con
 * {@link CtxTrad#destino} escribe pandas o PySpark.
 */
@FunctionalInterface
public interface TraductorStep {

  Bloque traducir( StepMeta paso, CtxTrad ctx ) throws Exception;
}
