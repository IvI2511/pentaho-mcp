package io.github.ivi2511.pentahomcp.traductor;

/** Acceso de las pruebas a las conversiones de máscaras (paquete privado en el traductor). */
public final class TradFormatosAcceso {

  private TradFormatosAcceso() {
  }

  public static String strftime( String java ) {
    return TradCampos.strftime( java );
  }

  public static String fechaPy( String java ) {
    return TradSalidas.fechaPy( java );
  }

  /** Texto → fecha con máscara, como lo escribe el traductor (con los nombres de mes si la máscara los lee). */
  public static String aFecha( String x, String mascara, CtxTrad ctx ) {
    return TradCampos.aFecha( x, mascara, ctx );
  }
}
