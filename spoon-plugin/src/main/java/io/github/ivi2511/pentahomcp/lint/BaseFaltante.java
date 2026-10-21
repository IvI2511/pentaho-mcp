package io.github.ivi2511.pentahomcp.lint;

import org.pentaho.di.core.exception.KettleStepException;

/**
 * La lanza el Entorno en vez de calcular los campos de un step cuando eso se conectaría a una base de archivo que no
 * existe (SQLite, H2, HSQLDB): Kettle la crearía vacía (docs/informes-diseno.md §2.4.4). Ctx la traduce como
 * ne.fields_db_missing; las reglas que necesitan esos campos quedan sin evaluar.
 */
public class BaseFaltante extends KettleStepException {

  private static final long serialVersionUID = 1L;
  public final String conexion;
  public final String archivo;

  public BaseFaltante( String conexion, String archivo ) {
    super( "connection " + conexion + " points to a database file that does not exist (" + archivo
      + "): computing the fields would create it empty" );
    this.conexion = conexion;
    this.archivo = archivo;
  }
}
