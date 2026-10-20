package io.github.ivi2511.pentahomcp.informes.graficos;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;

import org.apache.batik.bridge.EmbededExternalResourceSecurity;
import org.apache.batik.bridge.ExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadScriptSecurity;
import org.apache.batik.bridge.ScriptSecurity;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.util.ParsedURL;

/**
 * SVG a PNG con Batik {@code PNGTranscoder} y {@code KEY_WIDTH} (diseno §3.6), para PPTX y el .png
 * opcional del Markdown. Usa el Batik 1.9.1 que ya trae {@code data-integration\lib} (no se empaqueta).
 * <p>
 * Seguro para SVG ajenos (por ejemplo el de Mermaid, §3.5): no carga recursos externos (solo
 * {@code data:} embebidos) ni corre scripts. Cada llamada usa su propio transcoder, asi que se puede
 * llamar desde varios hilos. Solo usa {@code BufferedImage}; nunca abre ventanas AWT.
 *
 * @dueño WP6
 */
public final class SvgAPng {

  /** Tope de ancho, para no pedirle a Batik una imagen gigante por error. */
  public static final int MAX_ANCHO = 8000;

  private SvgAPng() {
  }

  /** Bytes de un archivo PNG de anchoPx de ancho (el alto sale de la proporcion del SVG). */
  public static byte[] png( String svg, int anchoPx ) throws Exception {
    if ( svg == null || svg.trim().isEmpty() ) {
      throw new IllegalArgumentException( "SVG vacio" );
    }
    if ( anchoPx <= 0 || anchoPx > MAX_ANCHO ) {
      throw new IllegalArgumentException( "anchoPx fuera de rango (1-" + MAX_ANCHO + "): " + anchoPx );
    }
    PngSeguro t = new PngSeguro();
    t.addTranscodingHint( SVGAbstractTranscoder.KEY_WIDTH, (float) anchoPx );
    t.addTranscodingHint( SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, Boolean.FALSE );
    t.addTranscodingHint( SVGAbstractTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "" );
    t.addTranscodingHint( SVGAbstractTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, Boolean.TRUE );
    TranscoderInput in = new TranscoderInput( new StringReader( svg ) );
    // URI ficticia: Batik la pide para resolver referencias relativas (que de todos modos no carga)
    in.setURI( "file:///pentaho-live/grafico.svg" );
    ByteArrayOutputStream bo = new ByteArrayOutputStream( 64 * 1024 );
    t.transcode( in, new TranscoderOutput( bo ) );
    return bo.toByteArray();
  }

  /** PNGTranscoder con un agente que no carga nada de afuera ni ejecuta scripts. */
  private static final class PngSeguro extends PNGTranscoder {

    @Override
    protected UserAgent createUserAgent() {
      return new AgenteSeguro();
    }

    private final class AgenteSeguro extends SVGAbstractTranscoderUserAgent {

      @Override
      public ScriptSecurity getScriptSecurity( String tipo, ParsedURL script, ParsedURL doc ) {
        return new NoLoadScriptSecurity( tipo );
      }

      @Override
      public ExternalResourceSecurity getExternalResourceSecurity( ParsedURL recurso, ParsedURL doc ) {
        return new EmbededExternalResourceSecurity( recurso );
      }
    }
  }
}
