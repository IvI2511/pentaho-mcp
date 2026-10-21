package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * El grafo de un diagrama propio (flujo, job o linaje de campos) tal como va en el modelo: nodos, aristas y,
 * en el linaje, los steps que agrupan los campos. Es la forma serializable de
 * {@code informes.diagramas.Grafo}: lo arman los recolectores (GrafoFlujo, GrafoLinaje) y cada renderizador
 * lo dibuja para su caja (pagina, diapositiva, pantalla) con {@code Diagramas.dibujar}. Sin coordenadas: la
 * disposicion se calcula al dibujar.
 *
 * @dueño WP6
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Esquema {

  /** flujo | job | linaje. */
  public String tipo;
  /** Nombre del diagrama (title y aria-label del SVG). */
  public String titulo;
  /** false = sin la leyenda dentro del dibujo. */
  public boolean leyenda = true;
  /** En el orden del documento (desempata la disposicion). */
  public List<Nodo> nodos = new ArrayList<>();
  public List<Arista> aristas = new ArrayList<>();
  /** Linaje: un grupo por step, en el orden del documento. */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  public List<Grupo> grupos = new ArrayList<>();

  /** Un step, una entrada de job o un campo del linaje. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Nodo {
    public String id;
    public String titulo;
    /** Lineas tenues bajo el titulo (tipo de step, "copia las filas a 3 salidas"...). */
    public List<String> lineas;
    /** Linaje: la operacion que crea el campo. */
    public String op;
    public boolean multi;
    public int copias = 1;
    public boolean falta;
    public boolean inicio;
    /** Linaje: indice del grupo (step) en {@link Esquema#grupos}; null fuera del linaje. */
    public Integer grupo;
  }

  /** Salto entre dos nodos (por id). */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Arista {
    public String a;
    public String b;
    /** normal | error | info | ok | falla | siempre | clave. */
    public String clase;
    public String rotulo;
    public boolean activa = true;
  }

  /** Un step del linaje. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Grupo {
    public String nombre;
    public String op;
    /** El step destino del linaje. */
    public boolean destino;
  }
}
