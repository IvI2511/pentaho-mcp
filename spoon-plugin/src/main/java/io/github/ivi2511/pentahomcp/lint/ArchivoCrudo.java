package io.github.ivi2511.pentahomcp.lint;

import java.io.InputStream;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.vfs.KettleVFS;
import org.w3c.dom.Element;

/**
 * El .ktr/.kjb tal como está en el disco, para lo que el meta en memoria ya no muestra: nombres duplicados (al
 * cargar, uno desaparece) y contraseñas en claro frente a «Encrypted …» (en memoria ya vienen descifradas).
 * Solo vale si el documento no tiene cambios sin guardar (Opciones.sinGuardar).
 */
public final class ArchivoCrudo {

  public final String ruta;
  public final Element raiz;

  private ArchivoCrudo( String ruta, Element raiz ) {
    this.ruta = ruta;
    this.raiz = raiz;
  }

  /** Lee el archivo del documento; lanza Exception con el motivo si no se puede. */
  static ArchivoCrudo leer( AbstractMeta meta ) throws Exception {
    String fn = meta.getFilename();
    if ( fn == null || fn.trim().isEmpty() ) {
      throw new IllegalStateException( "no file" );
    }
    // el parser lee los bytes y respeta el encoding de la declaración (y el BOM)
    try ( InputStream in = KettleVFS.getInputStream( fn ) ) {
      return new ArchivoCrudo( fn, Nodos.parse( in ) );
    }
  }

  public boolean esTransformacion() {
    return "transformation".equals( raiz.getTagName() );
  }
}
