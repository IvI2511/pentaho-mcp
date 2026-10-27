package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.pentaho.di.base.AbstractMeta;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Opciones;
import io.github.ivi2511.pentahomcp.traductor.Verificacion;

/**
 * Herramientas MCP del traductor a Python (0.8.0): translate_to_python y translate_coverage. ESQUELETO (API para el
 * diálogo): se completa en esta rama.
 */
final class TraducirTools {

  private TraducirTools() {
  }

  /** Lo que deja una traducción (la herramienta y el diálogo). */
  static final class Hecho {
    File archivo;
    int exactos;
    int aproximados;
    int manuales;
    final List<Bloque> bloques = new ArrayList<>();
    /** null si no se pidió verificar. */
    Verificacion.Resultado verificacion;
    final List<String> avisos = new ArrayList<>();
  }

  static void register( McpHttpServer s ) {
    // 0.8.0: parte 5
  }

  /** El archivo por defecto: al lado del .ktr / .kjb, nombre.py / nombre.ipynb (nombre_pyspark.* con PySpark). */
  static File salidaPorDefecto( File origen, String nombre, Opciones o ) {
    return null;
  }

  /** El Python para verificar (ajuste o PENTAHO_LIVE_PYTHON, si no python del PATH); null si no hay. */
  static String python() {
    return null;
  }

  /**
   * Traduce (una copia de) la transformación o el job y escribe el archivo. Fuera del hilo de la UI: con verificar,
   * corre PDI y Python (puede tardar).
   */
  static Hecho traducir( AbstractMeta meta, Opciones o, File salida, boolean sobrescribir, boolean verificar )
    throws Exception {
    return new Hecho();
  }
}
