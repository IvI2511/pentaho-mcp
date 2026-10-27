package io.github.ivi2511.pentahomcp.traductor;

/** Biblioteca de destino del código Python que genera el traductor (0.8.0). */
public enum Destino {
  /** pandas + numpy (+ matplotlib en el notebook): todo en memoria, como la mayoría de los ejercicios. */
  PANDAS,
  /** PySpark (DataFrame API): la misma lógica en Spark, para Big Data. */
  PYSPARK;

  public static Destino de( String s ) {
    return s != null && s.trim().equalsIgnoreCase( "pyspark" ) ? PYSPARK : PANDAS;
  }

  public String id() {
    return this == PYSPARK ? "pyspark" : "pandas";
  }
}
