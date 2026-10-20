package io.github.ivi2511.pentahomcp.lint;

/**
 * Base de las reglas: el id, la severidad, la categoría y las banderas se fijan en el constructor de cada
 * regla; queda por escribir solo revisar(). Por defecto: solo transformaciones, sin campos, puntúa.
 */
public abstract class ReglaBase implements Regla {

  private final String id;
  private final Sev severidad;
  private final String categoria;
  private boolean trans = true;
  private boolean jobs;
  private boolean campos;
  private boolean archivo;
  private boolean apagada;
  private boolean env;
  private boolean puntua = true;

  protected ReglaBase( String id, Sev severidad, String categoria ) {
    this.id = id;
    this.severidad = severidad;
    this.categoria = categoria;
  }

  protected final ReglaBase soloJobs() {
    trans = false;
    jobs = true;
    return this;
  }

  protected final ReglaBase ambos() {
    trans = true;
    jobs = true;
    return this;
  }

  protected final ReglaBase conCampos() {
    campos = true;
    return this;
  }

  protected final ReglaBase deArchivo() {
    archivo = true;
    return this;
  }

  protected final ReglaBase apagadaPorDefecto() {
    apagada = true;
    puntua = false;
    return this;
  }

  protected final ReglaBase dependeDelEntorno() {
    env = true;
    return this;
  }

  protected final ReglaBase sinPuntaje() {
    puntua = false;
    return this;
  }

  @Override
  public final String id() {
    return id;
  }

  @Override
  public final Sev severidad() {
    return severidad;
  }

  @Override
  public final String categoria() {
    return categoria;
  }

  @Override
  public final boolean paraTrans() {
    return trans;
  }

  @Override
  public final boolean paraJobs() {
    return jobs;
  }

  @Override
  public final boolean necesitaCampos() {
    return campos;
  }

  @Override
  public final boolean soloArchivo() {
    return archivo;
  }

  @Override
  public final boolean apagada() {
    return apagada;
  }

  @Override
  public final boolean env() {
    return env;
  }

  @Override
  public final boolean puntua() {
    return puntua;
  }

  @Override
  public String toString() {
    return id;
  }
}
