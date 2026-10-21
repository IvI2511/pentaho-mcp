package io.github.ivi2511.pentahomcp.informes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;

/**
 * Que informe se pide (diseno §2.5). Lo arma el panel o la tool MCP.
 *
 * @dueño WP0
 */
public final class Pedido {

  public static final String METRICAS_ULTIMA = "last_run";
  public static final String METRICAS_COPIA = "run_copy";
  public static final String METRICAS_NINGUNA = "none";

  /** Ids de SeccionId. null = las marcadas por defecto que aplican. */
  public List<String> secciones;
  /** en | es | pt. El panel arranca con el idioma de Spoon. */
  public String idioma = "en";
  /** Si es null, el nombre del documento. */
  public String titulo;
  /** Step destino del linaje; null = los steps finales. */
  public String linajeStep;
  /** Step a perfilar; sin step la seccion profile no corre (ejecuta la transformacion). */
  public String perfilStep;
  /** Varios steps a perfilar (en una sola corrida de la copia); se suma a perfilStep. */
  public List<String> perfilSteps = new ArrayList<>();
  public int perfilFilas = 5000;
  /** last_run | run_copy | none. */
  public String metricas = METRICAS_ULTIMA;
  /** true = target_model se conecta a la base (PK y FK). */
  public boolean destinoConsultarBase;
  /**
   * true = quality corre tambien el modo profundo del linter: previews de solo lectura que leen hasta 5000 filas
   * de las fuentes para confirmar o descartar el desorden y el simbolo decimal (docs/linter-diseno.md §3.3).
   */
  public boolean calidadProfunda;
  public Narrativa narrativa;
  /**
   * Total en ms. Cada seccion tiene ademas su tope: campos 60 s, linaje 60 s, perfil 60 s, BD 15 s,
   * validacion 60 s, Chromium 60 s.
   */
  public long timeoutMs = 120000;

  /** Los steps a perfilar: perfilStep y perfilSteps juntos, en orden, sin repetidos ni vacios. */
  @JsonIgnore
  public List<String> pasosPerfil() {
    Set<String> l = new LinkedHashSet<>();
    if ( perfilStep != null && !perfilStep.trim().isEmpty() ) {
      l.add( perfilStep.trim() );
    }
    if ( perfilSteps != null ) {
      for ( String x : perfilSteps ) {
        if ( x != null && !x.trim().isEmpty() ) {
          l.add( x.trim() );
        }
      }
    }
    return new ArrayList<>( l );
  }
}
