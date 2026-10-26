package io.github.ivi2511.pentahomcp.lint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cómo se arregla un hallazgo (§7). El motor solo lo describe; lo aplica apply_lint_fixes (LintArreglos, en el
 * paquete base) con UndoGrupo. auto = entra en all_auto; confirm = el cliente confirma antes con la persona;
 * needsValue = asistido: falta un valor de Claude o de la persona (values[key]).
 */
public final class Arreglo {

  public static final String SWAP_OPERANDS = "swap_operands";
  public static final String SET_OUTPUT_MODE = "set_output_mode";
  public static final String RENAME = "rename";
  public static final String RENAME_ENTRY = "rename_entry";
  public static final String REPLACE_VALUE = "replace_value";
  public static final String SET_DECIMAL = "set_decimal";
  public static final String DECLARE_OUTPUT_FIELDS = "declare_output_fields";
  public static final String INSERT_SORT = "insert_sort";
  public static final String RETARGET_HOP = "retarget_hop";
  public static final String SET_DESCRIPTION = "set_description";
  public static final String ADD_PARAMETER = "add_parameter";
  public static final String SET_PARAMETER_DEFAULT = "set_parameter_default";
  public static final String ENABLE_HOP = "enable_hop";
  public static final String DELETE_STEP = "delete_step";
  public static final String DELETE_ENTRY = "delete_entry";
  public static final String EXPAND_SELECT_STAR = "expand_select_star";
  /** step_without_description (0.7.1): la descripción propuesta del step o la entrada. */
  public static final String SET_STEP_DESCRIPTION = "set_step_description";
  /** required_variable de hosts (0.7.1): host, puerto y base de la conexión a variables del perfil. */
  public static final String USE_CONNECTION_VARIABLES = "use_connection_variables";

  public String kind;
  public boolean auto;
  public boolean confirm;
  public boolean needsValue;
  public boolean undoable = true;
  /** El valor nuevo que se propone, si se sabe sin preguntar. */
  public String preview;
  public final Map<String, String> params = new LinkedHashMap<String, String>();

  public static Arreglo auto( String kind ) {
    Arreglo a = new Arreglo();
    a.kind = kind;
    a.auto = true;
    return a;
  }

  /** auto+c: lo hace, pero cambia filas, formato, rutas o steps y el cliente confirma antes. */
  public static Arreglo conConfirmacion( String kind ) {
    Arreglo a = auto( kind );
    a.confirm = true;
    return a;
  }

  /** Asistido: necesita values[key]; después va como auto+c. */
  public static Arreglo asistido( String kind ) {
    Arreglo a = conConfirmacion( kind );
    a.needsValue = true;
    return a;
  }

  public Arreglo vista( String v ) {
    preview = v;
    return this;
  }

  public Arreglo param( String k, Object v ) {
    if ( v != null ) {
      params.put( k, String.valueOf( v ) );
    }
    return this;
  }

  public Arreglo sinDeshacer() {
    undoable = false;
    return this;
  }
}
