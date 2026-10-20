package io.github.ivi2511.pentahomcp.informes.tema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Identidad y estilo del informe. Mapea 1 a 1 el archivo
 * {@code <KETTLE_HOME>\.kettle\pentaho-live\informes.json} (diseno §4.1); lo lee y escribe Ajustes
 * (WP6). Un {@code new Tema()} es la plantilla neutra (§4.2).
 * <p>
 * Hilos: el panel lo edita en la UI; a un worker se le pasa siempre una {@link #copia()}.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Tema {

  // --- plantilla neutra (§4.2): fijos, el usuario no los cambia
  public static final String FONDO = "#FFFFFF";
  public static final String TEXTO = "#1F2933";
  public static final String TEXTO_TENUE = "#6B7785";
  public static final String BORDE = "#D9DEE5";
  public static final String FUENTE = "\"Segoe UI\", Arial, sans-serif";
  public static final String FUENTE_CODIGO = "Consolas, \"Courier New\", monospace";

  // --- colores por defecto (el usuario los cambia)
  public static final String PRIMARIO = "#243B53";
  public static final String ACENTO = "#3F74D8";
  public static final String SECUNDARIO = "#1C9A80";

  /** Pie de una linea (§4.3), igual en todos los idiomas. El punto medio va literal, no escapado en HTML. */
  public static final String PIE = "Pentaho Live · IDC Studios";

  public static final String A4 = "A4";
  public static final String LETTER = "Letter";

  public static final String HTML_INTERACTIVO = "interactivo";
  public static final String HTML_LIVIANO = "liviano";

  public int version = 1;
  public Identidad identidad = new Identidad();
  /** Nombre del logo dentro de la carpeta de ajustes ("logo.png" o "logo.jpg"); null = sin logo. */
  public String logo;
  public Colores colores = new Colores();
  /** A4 | Letter. */
  public String pagina = A4;
  /** en | es | pt; null = el idioma de Spoon. */
  public String idioma;
  @JsonProperty( "marca_de_agua" )
  public boolean marcaDeAgua = true;
  @JsonProperty( "rotular_ia" )
  public boolean rotularIa = true;
  public Salida salida = new Salida();
  /** Ids de SeccionId marcados por defecto en el panel; null = los de cada seccion. */
  public List<String> secciones;
  public Perfil perfil = new Perfil();
  /** Ruta a msedge.exe o chrome.exe; "" = buscarlo (Chromium.ubicar). */
  public String navegador = "";

  /** Bytes del logo (PNG o JPG, hasta 2 MB); los carga Ajustes. No va al JSON. */
  @JsonIgnore
  public byte[] logoDatos;
  /** image/png | image/jpeg. No va al JSON. */
  @JsonIgnore
  public String logoMime;

  /** Autor, institucion... del usuario. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Identidad {
    public String autor = "";
    public String institucion = "";
    public String curso = "";
    public String contacto = "";
  }

  /** Colores del usuario, en "#RRGGBB". Paleta (WP6) deriva los tonos claros. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Colores {
    /** Titulos, banda de portada, cabecera de tablas. */
    public String primario = PRIMARIO;
    /** Series de los graficos. */
    public String acento = ACENTO;
    public String secundario = SECUNDARIO;
  }

  /** Preferencias de exportacion. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Salida {
    /** Carpeta por defecto; "" = la ultima usada o &lt;carpeta del .ktr&gt;\informes. */
    public String carpeta = "";
    /** Claves de Formato ("html", "pdf", "md", "pptx"...). */
    public List<String> formatos = new ArrayList<>( Arrays.asList( "html", "pdf" ) );
    /** interactivo | liviano. */
    public String html = HTML_INTERACTIVO;
    /** Agrega -AAAAMMDD-HHMM al nombre de los archivos. */
    @JsonProperty( "agregar_fecha" )
    public boolean agregarFecha;
  }

  /** Opciones de la seccion profile. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Perfil {
    public int filas = 5000;
    /** false = oculta valores frecuentes, minimos y maximos de texto (privacidad). */
    @JsonProperty( "mostrar_valores" )
    public boolean mostrarValores = true;
  }

  private static final ObjectMapper MAPPER = new ObjectMapper()
    .configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false );

  /** Copia profunda, logo incluido. */
  public Tema copia() {
    try {
      Tema t = MAPPER.treeToValue( MAPPER.valueToTree( this ), Tema.class );
      t.logoDatos = logoDatos == null ? null : logoDatos.clone();
      t.logoMime = logoMime;
      return t;
    } catch ( IOException | IllegalArgumentException e ) {
      throw new IllegalStateException( "No se pudo copiar el tema: " + e.getMessage(), e );
    }
  }
}
