package io.github.ivi2511.pentahomcp.lint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * El perfil del linter de un proyecto o de un curso (0.7, E5: convenciones compartidas): {@code
 * .pentaho-live/lint.yaml} (o {@code lint.yml} / {@code lint.json}), que se busca subiendo carpetas desde la del
 * .ktr/.kjb. Dice qué reglas corren y con qué gravedad, el puntaje mínimo, los prefijos de nombres por categoría de
 * step o de entrada, si cada step necesita descripción, qué variables tienen que usar las rutas y los hosts y reglas
 * propias del equipo por patrón. Todo es declarativo: el perfil nunca ejecuta nada (las reglas del equipo son
 * expresiones regulares con tope de largo y de tiempo).
 * <p>
 * El formato está en el manual de la skill (manual/13-proyecto.md) y en el JSON Schema lint/perfil.schema.json (que
 * {@code pentaho-live init} copia como .pentaho-live/lint.schema.json). Lo que el perfil no conoce (claves o ids de
 * regla de otra versión) va a {@link #avisos} y no impide usarlo; lo que está mal (una gravedad inválida, una regex que
 * no compila, un min_score fuera de 0-100) es un error con la clave que falla.
 */
public final class PerfilEquipo {

  public static final String CARPETA = ".pentaho-live";
  /** Los nombres que se buscan, en este orden, dentro de {@link #CARPETA}. */
  public static final List<String> ARCHIVOS = Collections.unmodifiableList( Arrays.asList( "lint.yaml", "lint.yml",
    "lint.json" ) );
  public static final int VERSION_FORMATO = 1;
  /** Un perfil más grande que esto no es un perfil. */
  public static final long MAX_BYTES = 256L * 1024;
  /** Carpetas que se suben como mucho buscando el perfil. */
  public static final int MAX_NIVELES = 40;
  public static final int MAX_REGLAS_EQUIPO = 50;
  public static final int MAX_PATRON = 500;

  /** Lo que se puede poner en rules.&lt;id&gt; además de una gravedad. */
  public static final String APAGADA = "off";
  public static final String ENCENDIDA = "on";

  /** Dónde mira una regla del equipo. */
  public static final List<String> DONDE = Collections.unmodifiableList( Arrays.asList( "xml", "name", "path", "sql",
    "description" ) );

  /** Ids de las reglas de este perfil (lint.reglas.ReglasPerfil). */
  public static final String NAMING = "naming_convention";
  public static final String STEP_DESCRIPTION = "step_without_description";
  public static final String REQUIRED_VARIABLE = "required_variable";
  public static final String TEAM_PATTERN = "team_pattern";

  private static final Set<String> CLAVES = new LinkedHashSet<String>( Arrays.asList( "version", "name",
    "description", "min_score", "robustness", "rules", "naming", "step_description", "required_variables",
    "team_rules", "$schema" ) );

  /** El archivo de donde salió (null si se armó en memoria). */
  public File archivo;
  public String nombre;
  public String descripcion;
  /** null = sin mínimo. */
  public Integer minScore;
  /** robustness: true = corre también la familia robustness (su sub-puntaje). */
  public boolean robustez;
  /** id → off | on | error | warning | info, en el orden del archivo. */
  public final Map<String, String> reglas = new LinkedHashMap<String, String>();
  /** Prefijos de steps: categoría (CategoriasStep) → prefijo. */
  public final Map<String, String> prefijosStep = new LinkedHashMap<String, String>();
  /** Prefijos de steps por id de tipo (TableInput…): manda sobre la categoría. */
  public final Map<String, String> prefijosStepTipo = new LinkedHashMap<String, String>();
  public final Map<String, String> prefijosEntrada = new LinkedHashMap<String, String>();
  public final Map<String, String> prefijosEntradaTipo = new LinkedHashMap<String, String>();
  /** step_description.required. */
  public boolean descripcionObligatoria;
  /** Tipos de step que no necesitan descripción (step_description.except). */
  public final Set<String> descripcionExcepto = new LinkedHashSet<String>();
  /** Variables que puede usar una ruta al principio (required_variables.paths). */
  public final List<String> variablesRuta = new ArrayList<String>();
  /** Variables que puede usar el host de una conexión (required_variables.hosts). */
  public final List<String> variablesHost = new ArrayList<String>();
  public final List<ReglaEquipo> equipo = new ArrayList<ReglaEquipo>();
  /** Lo que se ignoró (claves u ids desconocidos): el perfil sirve igual. */
  public final List<String> avisos = new ArrayList<String>();

  /** Una regla propia del equipo: un patrón sobre el nombre, las rutas, el SQL, la descripción o el XML de un step. */
  public static final class ReglaEquipo {
    public String id;
    public String titulo;
    /** El mensaje del hallazgo; marcadores {where} (step o entrada), {match} (lo que coincidió). */
    public String mensaje;
    /** Cómo se arregla (opcional). */
    public String arreglo;
    public Sev sev = Sev.WARNING;
    public String donde = "xml";
    /** Solo estos tipos de step/entrada; vacío = todos. */
    public final Set<String> tipos = new LinkedHashSet<String>();
    public Pattern patron;
    /** true = must: match (el patrón tiene que aparecer); false = must: not_match (no puede aparecer). */
    public boolean debeAparecer;
  }

  /** El resultado de buscar un perfil: el perfil, o el error que dio el archivo, o nada. */
  public static final class Busqueda {
    public PerfilEquipo perfil;
    /** El archivo encontrado (aunque tenga un error). */
    public File archivo;
    /** Desde dónde se buscó. */
    public File desde;
    public String error;
    /** Lo pidió quien llama (argumento profile): un error no se puede pasar por alto. */
    public boolean explicito;
    /** profile: none. */
    public boolean desactivado;
  }

  // ================================================================ búsqueda y carga

  /** El archivo de perfil más cercano subiendo desde carpeta (incluida); null si no hay. */
  public static File buscarArchivo( File carpeta ) {
    File d = carpeta == null ? null : carpeta.getAbsoluteFile();
    for ( int i = 0; d != null && i < MAX_NIVELES; i++, d = d.getParentFile() ) {
      File c = new File( d, CARPETA );
      if ( c.isDirectory() ) {
        for ( String n : ARCHIVOS ) {
          File f = new File( c, n );
          if ( f.isFile() ) {
            return f;
          }
        }
      }
    }
    return null;
  }

  /** Busca y carga desde carpeta. Un archivo con errores deja el error en la búsqueda, no lanza. */
  public static Busqueda buscar( File carpeta ) {
    Busqueda b = new Busqueda();
    b.desde = carpeta;
    b.archivo = buscarArchivo( carpeta );
    if ( b.archivo != null ) {
      try {
        b.perfil = cargar( b.archivo );
      } catch ( IllegalArgumentException | IOException e ) {
        b.error = e.getMessage();
      }
    }
    return b;
  }

  /**
   * Lo que pide el argumento profile de lint_etl o --profile: null/vacío = buscar desde la carpeta del documento;
   * «none» = sin perfil; una carpeta = buscar subiendo desde ella; un archivo = ese. Relativo: contra base (la carpeta
   * del documento o la de trabajo).
   */
  public static Busqueda resolver( String pedido, File carpetaDoc, File base ) {
    if ( pedido == null || pedido.trim().isEmpty() ) {
      Busqueda b = carpetaDoc == null ? new Busqueda() : buscar( carpetaDoc );
      b.desde = carpetaDoc;
      return b;
    }
    String p = pedido.trim();
    if ( p.equalsIgnoreCase( "none" ) ) {
      Busqueda b = new Busqueda();
      b.desactivado = true;
      b.explicito = true;
      return b;
    }
    File f = new File( p );
    if ( !f.isAbsolute() && base != null ) {
      f = new File( base, p );
    }
    Busqueda b;
    if ( f.isDirectory() ) {
      b = buscar( f );
      if ( b.archivo == null ) {
        b.error = "no " + CARPETA + "/lint.yaml (or lint.yml, lint.json) in " + f.getAbsolutePath()
          + " or the folders above it";
      }
    } else {
      b = new Busqueda();
      b.archivo = f.getAbsoluteFile();
      b.desde = f.getParentFile();
      try {
        b.perfil = cargar( f );
      } catch ( IllegalArgumentException | IOException e ) {
        b.error = e.getMessage();
      }
    }
    b.explicito = true;
    return b;
  }

  /** La carpeta del archivo de un documento (getFilename de Kettle: ruta o URL file:), o null. */
  public static File carpetaDe( String filename ) {
    if ( filename == null || filename.trim().isEmpty() ) {
      return null;
    }
    String f = filename.trim();
    try {
      File x = f.regionMatches( true, 0, "file:", 0, 5 ) ? new File( new URI( f.replace( " ", "%20" ) ) ) : new File( f );
      File p = x.getAbsoluteFile().getParentFile();
      return p != null && p.isDirectory() ? p : null;
    } catch ( Exception e ) {
      return null; // otra URL de VFS (sftp://, zip:…): sin perfil
    }
  }

  public static PerfilEquipo cargar( File f ) throws IOException {
    if ( !f.isFile() ) {
      throw new IOException( "Lint profile not found: " + f.getAbsolutePath() );
    }
    if ( f.length() > MAX_BYTES ) {
      throw new IllegalArgumentException( "Lint profile " + f.getAbsolutePath() + ": bigger than " + MAX_BYTES / 1024
        + " KB" );
    }
    String texto;
    try ( InputStream in = new FileInputStream( f ) ) {
      texto = leer( in );
    }
    PerfilEquipo p;
    try {
      p = desdeTexto( texto, f.getName().toLowerCase( Locale.ROOT ).endsWith( ".json" ) );
    } catch ( IllegalArgumentException e ) {
      throw new IllegalArgumentException( "Lint profile " + f.getAbsolutePath() + ": " + e.getMessage(), e );
    }
    p.archivo = f.getAbsoluteFile();
    if ( p.nombre == null ) {
      // sin name: la carpeta que contiene .pentaho-live (el proyecto o el curso)
      File proyecto = p.archivo.getParentFile() == null ? null : p.archivo.getParentFile().getParentFile();
      p.nombre = proyecto != null && !proyecto.getName().isEmpty() ? proyecto.getName() : "lint";
    }
    return p;
  }

  /** Lee un perfil YAML (o JSON, que también es YAML) de un texto. */
  public static PerfilEquipo desdeTexto( String texto, boolean json ) {
    Object raiz;
    try {
      if ( json ) {
        raiz = new ObjectMapper().readValue( texto, Object.class );
      } else {
        // SafeConstructor: solo mapas, listas y escalares (nunca un !!clase de Java)
        raiz = new Yaml( new SafeConstructor() ).load( texto );
      }
    } catch ( Exception e ) {
      throw new IllegalArgumentException( ( json ? "invalid JSON: " : "invalid YAML: " ) + primeraLinea( e ) );
    }
    if ( raiz == null ) {
      raiz = new LinkedHashMap<String, Object>();
    }
    if ( !( raiz instanceof Map ) ) {
      throw new IllegalArgumentException( "the profile must be a mapping (key: value)" );
    }
    PerfilEquipo p = new PerfilEquipo();
    p.leer( mapa( raiz, "(root)" ) );
    return p;
  }

  // ================================================================ lectura

  private void leer( Map<String, Object> m ) {
    for ( String k : m.keySet() ) {
      if ( !CLAVES.contains( k ) ) {
        avisos.add( "unknown key '" + k + "' (ignored)" );
      }
    }
    Object v = m.get( "version" );
    if ( v != null ) {
      int ver = entero( v, "version" );
      if ( ver > VERSION_FORMATO ) {
        avisos.add( "version " + ver + " is newer than this Pentaho Live (" + VERSION_FORMATO + "): unknown parts are "
          + "ignored" );
      }
    }
    nombre = texto( m.get( "name" ), "name" );
    descripcion = texto( m.get( "description" ), "description" );
    if ( m.get( "min_score" ) != null ) {
      int s = entero( m.get( "min_score" ), "min_score" );
      if ( s < 0 || s > 100 ) {
        throw new IllegalArgumentException( "min_score must be between 0 and 100, got " + s );
      }
      minScore = s;
    }
    robustez = booleano( m.get( "robustness" ), "robustness", false );
    if ( m.get( "rules" ) != null ) {
      for ( Map.Entry<String, Object> e : mapa( m.get( "rules" ), "rules" ).entrySet() ) {
        String id = e.getKey().trim().toLowerCase( Locale.ROOT );
        String val = valorRegla( e.getValue(), "rules." + e.getKey() );
        if ( Catalogo.buscar( id ) == null ) {
          avisos.add( "rules." + e.getKey() + ": unknown rule id (ignored)" );
          continue;
        }
        reglas.put( Catalogo.buscar( id ).id(), val );
      }
    }
    if ( m.get( "naming" ) != null ) {
      Map<String, Object> n = mapa( m.get( "naming" ), "naming" );
      for ( String k : n.keySet() ) {
        if ( !k.equals( "steps" ) && !k.equals( "entries" ) ) {
          avisos.add( "naming." + k + ": unknown key (ignored)" );
        }
      }
      prefijos( n.get( "steps" ), "naming.steps", CategoriasStep.CATEGORIAS_STEP, prefijosStep, prefijosStepTipo );
      prefijos( n.get( "entries" ), "naming.entries", CategoriasStep.CATEGORIAS_ENTRADA, prefijosEntrada,
        prefijosEntradaTipo );
    }
    if ( m.get( "step_description" ) != null ) {
      Object sd = m.get( "step_description" );
      if ( sd instanceof Boolean ) {
        descripcionObligatoria = (Boolean) sd;
      } else {
        Map<String, Object> d = mapa( sd, "step_description" );
        descripcionObligatoria = booleano( d.get( "required" ), "step_description.required", true );
        descripcionExcepto.addAll( lista( d.get( "except" ), "step_description.except" ) );
        for ( String k : d.keySet() ) {
          if ( !k.equals( "required" ) && !k.equals( "except" ) ) {
            avisos.add( "step_description." + k + ": unknown key (ignored)" );
          }
        }
      }
    }
    if ( m.get( "required_variables" ) != null ) {
      Map<String, Object> rv = mapa( m.get( "required_variables" ), "required_variables" );
      for ( String x : lista( rv.get( "paths" ), "required_variables.paths" ) ) {
        variablesRuta.add( variable( x, "required_variables.paths" ) );
      }
      for ( String x : lista( rv.get( "hosts" ), "required_variables.hosts" ) ) {
        variablesHost.add( variable( x, "required_variables.hosts" ) );
      }
      for ( String k : rv.keySet() ) {
        if ( !k.equals( "paths" ) && !k.equals( "hosts" ) ) {
          avisos.add( "required_variables." + k + ": unknown key (ignored)" );
        }
      }
    }
    if ( m.get( "team_rules" ) != null ) {
      Object tr = m.get( "team_rules" );
      if ( !( tr instanceof List ) ) {
        throw new IllegalArgumentException( "team_rules must be a list" );
      }
      List<?> l = (List<?>) tr;
      if ( l.size() > MAX_REGLAS_EQUIPO ) {
        throw new IllegalArgumentException( "team_rules: at most " + MAX_REGLAS_EQUIPO + " rules" );
      }
      Set<String> ids = new LinkedHashSet<String>();
      for ( int i = 0; i < l.size(); i++ ) {
        ReglaEquipo r = reglaEquipo( mapa( l.get( i ), "team_rules[" + i + "]" ), "team_rules[" + i + "]" );
        if ( !ids.add( r.id ) ) {
          throw new IllegalArgumentException( "team_rules[" + i + "]: id '" + r.id + "' repeated" );
        }
        equipo.add( r );
      }
    }
  }

  private void prefijos( Object o, String donde, List<String> categorias, Map<String, String> porCategoria,
                         Map<String, String> porTipo ) {
    if ( o == null ) {
      return;
    }
    for ( Map.Entry<String, Object> e : mapa( o, donde ).entrySet() ) {
      String k = e.getKey().trim();
      if ( k.equals( "by_type" ) ) {
        for ( Map.Entry<String, Object> t : mapa( e.getValue(), donde + ".by_type" ).entrySet() ) {
          porTipo.put( t.getKey().trim(), prefijo( t.getValue(), donde + ".by_type." + t.getKey() ) );
        }
        continue;
      }
      String cat = k.toLowerCase( Locale.ROOT );
      if ( !categorias.contains( cat ) ) {
        avisos.add( donde + "." + k + ": unknown category (ignored); valid: " + categorias + ", by_type" );
        continue;
      }
      String p = prefijo( e.getValue(), donde + "." + k );
      if ( !p.isEmpty() ) {
        porCategoria.put( cat, p );
      }
    }
  }

  private static String prefijo( Object v, String donde ) {
    String p = v == null ? "" : String.valueOf( v ).trim();
    if ( p.length() > 40 || p.contains( "\n" ) ) {
      throw new IllegalArgumentException( donde + ": a prefix is a short text such as IN_" );
    }
    return p;
  }

  private ReglaEquipo reglaEquipo( Map<String, Object> m, String donde ) {
    ReglaEquipo r = new ReglaEquipo();
    r.id = texto( m.get( "id" ), donde + ".id" );
    if ( r.id == null || !r.id.matches( "[A-Za-z0-9_.-]{1,60}" ) ) {
      throw new IllegalArgumentException( donde + ".id is required (letters, digits, _ . -)" );
    }
    String d = donde + " (" + r.id + ")";
    r.titulo = texto( m.get( "title" ), d + ".title" );
    if ( r.titulo == null ) {
      r.titulo = r.id;
    }
    r.mensaje = texto( m.get( "message" ), d + ".message" );
    r.arreglo = texto( m.get( "fix" ), d + ".fix" );
    if ( m.get( "severity" ) != null ) {
      r.sev = Sev.desde( String.valueOf( m.get( "severity" ) ) );
      if ( r.sev == null ) {
        throw new IllegalArgumentException( d + ".severity must be error, warning or info" );
      }
    }
    if ( m.get( "where" ) != null ) {
      r.donde = String.valueOf( m.get( "where" ) ).trim().toLowerCase( Locale.ROOT );
      if ( !DONDE.contains( r.donde ) ) {
        throw new IllegalArgumentException( d + ".where must be one of " + DONDE );
      }
    }
    r.tipos.addAll( lista( m.get( "step_types" ), d + ".step_types" ) );
    r.tipos.addAll( lista( m.get( "entry_types" ), d + ".entry_types" ) );
    String pat = texto( m.get( "pattern" ), d + ".pattern" );
    if ( pat == null || pat.isEmpty() ) {
      throw new IllegalArgumentException( d + ".pattern is required" );
    }
    if ( pat.length() > MAX_PATRON ) {
      throw new IllegalArgumentException( d + ".pattern is longer than " + MAX_PATRON + " characters" );
    }
    try {
      r.patron = Pattern.compile( pat );
    } catch ( PatternSyntaxException e ) {
      throw new IllegalArgumentException( d + ".pattern is not a valid regular expression: " + primeraLinea( e ) );
    }
    String must = m.get( "must" ) == null ? "not_match" : String.valueOf( m.get( "must" ) ).trim().toLowerCase(
      Locale.ROOT );
    if ( !must.equals( "match" ) && !must.equals( "not_match" ) ) {
      throw new IllegalArgumentException( d + ".must must be match or not_match" );
    }
    r.debeAparecer = must.equals( "match" );
    for ( String k : m.keySet() ) {
      if ( !Arrays.asList( "id", "title", "message", "fix", "severity", "where", "step_types", "entry_types",
        "pattern", "must" ).contains( k ) ) {
        avisos.add( d + "." + k + ": unknown key (ignored)" );
      }
    }
    return r;
  }

  // ================================================================ qué corre

  /** off | on | error | warning | info, o null si el perfil no dice nada de esa regla. */
  public String ajuste( String id ) {
    return reglas.get( id );
  }

  public boolean apaga( String id ) {
    return APAGADA.equals( reglas.get( id ) );
  }

  /** La gravedad que el perfil le fija a una regla, o null. */
  public Sev gravedad( String id ) {
    String v = reglas.get( id );
    return v == null ? null : Sev.desde( v );
  }

  /** Las reglas de este perfil (ReglasPerfil) que tienen su sección configurada. */
  public boolean seccionActiva( String id ) {
    if ( NAMING.equals( id ) ) {
      return !prefijosStep.isEmpty() || !prefijosStepTipo.isEmpty() || !prefijosEntrada.isEmpty()
        || !prefijosEntradaTipo.isEmpty();
    }
    if ( STEP_DESCRIPTION.equals( id ) ) {
      return descripcionObligatoria;
    }
    if ( REQUIRED_VARIABLE.equals( id ) ) {
      return !variablesRuta.isEmpty() || !variablesHost.isEmpty();
    }
    if ( TEAM_PATTERN.equals( id ) ) {
      return !equipo.isEmpty();
    }
    return false;
  }

  /**
   * El perfil enciende esta regla: la nombra con on o una gravedad, o es una de las suyas con la sección puesta (y no
   * está en off). Las encendidas por el perfil restan aunque estén apagadas por defecto (salvo kettle_check y la
   * familia robustness, que tiene su sub-puntaje).
   */
  public boolean enciende( Regla r ) {
    String v = reglas.get( r.id() );
    if ( APAGADA.equals( v ) ) {
      return false;
    }
    return v != null || seccionActiva( r.id() );
  }

  /**
   * Las reglas que corren con este perfil, en el orden del catálogo: las de base (Catalogo.seleccionar) menos las que
   * el perfil apaga, más las que enciende. Si alguna necesita campos, cycle corre aunque esté apagada (decide si las
   * demás pueden calcularlos; LintMotor no muestra sus hallazgos).
   */
  public List<Regla> ajustar( List<Regla> base ) {
    Set<Regla> van = new LinkedHashSet<Regla>();
    boolean campos = false;
    for ( Regla r : Catalogo.todas() ) {
      boolean va = base.contains( r );
      if ( apaga( r.id() ) ) {
        va = false;
      } else if ( enciende( r ) ) {
        va = true;
      }
      if ( va ) {
        van.add( r );
        campos |= r.necesitaCampos();
      }
    }
    List<Regla> out = new ArrayList<Regla>();
    for ( Regla r : Catalogo.todas() ) {
      if ( van.contains( r ) || ( Catalogo.CYCLE.equals( r.id() ) && campos ) ) {
        out.add( r );
      }
    }
    return out;
  }

  /** El prefijo que pide el perfil para un step de este tipo (by_type primero, después la categoría), o null. */
  public String prefijoStep( String tipo ) {
    if ( tipo == null ) {
      return null;
    }
    String p = prefijosStepTipo.get( tipo );
    if ( p != null ) {
      return p.isEmpty() ? null : p;
    }
    String c = CategoriasStep.deStep( tipo );
    return c == null ? prefijosStep.get( CategoriasStep.OTRO ) : prefijosStep.get( c );
  }

  public String prefijoEntrada( String tipo ) {
    if ( tipo == null ) {
      return null;
    }
    String p = prefijosEntradaTipo.get( tipo );
    if ( p != null ) {
      return p.isEmpty() ? null : p;
    }
    String c = CategoriasStep.deEntrada( tipo );
    return c == null ? prefijosEntrada.get( CategoriasStep.OTRO ) : prefijosEntrada.get( c );
  }

  /** Todos los prefijos de steps (o de entradas) del perfil: para quitar uno equivocado antes de poner el bueno. */
  public Set<String> todosLosPrefijos( boolean steps ) {
    Set<String> s = new LinkedHashSet<String>();
    s.addAll( steps ? prefijosStep.values() : prefijosEntrada.values() );
    s.addAll( steps ? prefijosStepTipo.values() : prefijosEntradaTipo.values() );
    s.remove( "" );
    return s;
  }

  /**
   * El nombre con el prefijo pedido: si ya empieza con otro prefijo del perfil, se cambia; si no, se agrega
   * («Leer ventas» → «IN_Leer ventas», «OUT_ventas» → «IN_ventas»).
   */
  public String conPrefijo( String nombre, String prefijo, boolean steps ) {
    String n = nombre == null ? "" : nombre.trim();
    if ( prefijo == null || n.startsWith( prefijo ) ) {
      return n;
    }
    String mejor = null;
    for ( String p : todosLosPrefijos( steps ) ) {
      if ( n.startsWith( p ) && ( mejor == null || p.length() > mejor.length() ) ) {
        mejor = p;
      }
    }
    if ( mejor != null && n.length() > mejor.length() ) {
      n = n.substring( mejor.length() );
    }
    return prefijo + n;
  }

  // ================================================================ JSON (lint_profile, --show)

  /** El perfil efectivo: lo que lint_profile devuelve y lo que muestra pentaho-live lint --show-profile. */
  public ObjectNode json() {
    JsonNodeFactory f = JsonNodeFactory.instance;
    ObjectNode o = f.objectNode();
    o.put( "name", nombre );
    if ( descripcion != null ) {
      o.put( "description", descripcion );
    }
    if ( archivo != null ) {
      o.put( "path", archivo.getAbsolutePath() );
    }
    if ( minScore != null ) {
      o.put( "min_score", minScore );
    } else {
      o.putNull( "min_score" );
    }
    o.put( "robustness", robustez );
    ObjectNode rs = o.putObject( "rules" );
    for ( Map.Entry<String, String> e : reglas.entrySet() ) {
      rs.put( e.getKey(), e.getValue() );
    }
    ArrayNode act = o.putArray( "profile_rules_on" );
    for ( String id : Arrays.asList( NAMING, STEP_DESCRIPTION, REQUIRED_VARIABLE, TEAM_PATTERN ) ) {
      if ( !apaga( id ) && seccionActiva( id ) ) {
        act.add( id );
      }
    }
    ObjectNode nm = o.putObject( "naming" );
    nm.set( "steps", mapaJson( prefijosStep, prefijosStepTipo ) );
    nm.set( "entries", mapaJson( prefijosEntrada, prefijosEntradaTipo ) );
    ObjectNode sd = o.putObject( "step_description" );
    sd.put( "required", descripcionObligatoria );
    ArrayNode ex = sd.putArray( "except" );
    for ( String x : descripcionExcepto ) {
      ex.add( x );
    }
    ObjectNode rv = o.putObject( "required_variables" );
    ArrayNode rp = rv.putArray( "paths" );
    for ( String x : variablesRuta ) {
      rp.add( x );
    }
    ArrayNode rh = rv.putArray( "hosts" );
    for ( String x : variablesHost ) {
      rh.add( x );
    }
    ArrayNode tr = o.putArray( "team_rules" );
    for ( ReglaEquipo r : equipo ) {
      ObjectNode x = tr.addObject();
      x.put( "id", r.id ).put( "title", r.titulo ).put( "severity", r.sev.codigo() ).put( "where", r.donde ).put(
        "pattern", r.patron.pattern() ).put( "must", r.debeAparecer ? "match" : "not_match" );
      if ( !r.tipos.isEmpty() ) {
        ArrayNode t = x.putArray( "types" );
        for ( String y : r.tipos ) {
          t.add( y );
        }
      }
    }
    if ( !avisos.isEmpty() ) {
      ArrayNode w = o.putArray( "warnings" );
      for ( String a : avisos ) {
        w.add( a );
      }
    }
    return o;
  }

  /**
   * Lo que devuelven lint_profile y {@code pentaho-live profile}: {found, searched_from, path, profile | error}.
   */
  public static ObjectNode busquedaJson( Busqueda b ) {
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    o.put( "found", b.perfil != null );
    if ( b.desactivado ) {
      o.put( "disabled", true );
    }
    if ( b.desde != null ) {
      o.put( "searched_from", b.desde.getAbsolutePath() );
    }
    if ( b.archivo != null ) {
      o.put( "path", b.archivo.getAbsolutePath() );
    }
    if ( b.perfil != null ) {
      o.set( "profile", b.perfil.json() );
    } else if ( b.error != null ) {
      o.put( "error", b.error );
    } else if ( !b.desactivado ) {
      o.put( "hint", "No " + CARPETA + "/lint.yaml here or in the folders above: lint_etl uses its default rules. "
        + "'pentaho-live init' (or project_init) creates one." );
    }
    return o;
  }

  private static ObjectNode mapaJson( Map<String, String> cat, Map<String, String> tipo ) {
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    for ( Map.Entry<String, String> e : cat.entrySet() ) {
      o.put( e.getKey(), e.getValue() );
    }
    if ( !tipo.isEmpty() ) {
      ObjectNode t = o.putObject( "by_type" );
      for ( Map.Entry<String, String> e : tipo.entrySet() ) {
        t.put( e.getKey(), e.getValue() );
      }
    }
    return o;
  }

  // ================================================================ ayudas

  @SuppressWarnings( "unchecked" )
  private static Map<String, Object> mapa( Object o, String donde ) {
    if ( o == null ) {
      return new LinkedHashMap<String, Object>();
    }
    if ( !( o instanceof Map ) ) {
      throw new IllegalArgumentException( donde + " must be a mapping (key: value)" );
    }
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    for ( Map.Entry<Object, Object> e : ( (Map<Object, Object>) o ).entrySet() ) {
      out.put( String.valueOf( e.getKey() ), e.getValue() );
    }
    return out;
  }

  private static List<String> lista( Object o, String donde ) {
    List<String> l = new ArrayList<String>();
    if ( o == null ) {
      return l;
    }
    if ( o instanceof List ) {
      for ( Object x : (List<?>) o ) {
        if ( x != null && !String.valueOf( x ).trim().isEmpty() ) {
          l.add( String.valueOf( x ).trim() );
        }
      }
      return l;
    }
    if ( o instanceof Map ) {
      throw new IllegalArgumentException( donde + " must be a list or a text" );
    }
    for ( String x : String.valueOf( o ).split( "," ) ) {
      if ( !x.trim().isEmpty() ) {
        l.add( x.trim() );
      }
    }
    return l;
  }

  /** «DIR_DATOS» o «${DIR_DATOS}» → DIR_DATOS. */
  private static String variable( String v, String donde ) {
    String x = v.trim();
    if ( x.startsWith( "${" ) && x.endsWith( "}" ) ) {
      x = x.substring( 2, x.length() - 1 ).trim();
    } else if ( x.startsWith( "%%" ) && x.endsWith( "%%" ) && x.length() > 4 ) {
      x = x.substring( 2, x.length() - 2 ).trim();
    }
    if ( !x.matches( "[A-Za-z_][A-Za-z0-9_.]{0,80}" ) ) {
      throw new IllegalArgumentException( donde + ": '" + v + "' is not a variable name" );
    }
    return x;
  }

  private static String valorRegla( Object v, String donde ) {
    if ( v instanceof Boolean ) { // YAML 1.1: on/off/yes/no llegan como booleanos
      return (Boolean) v ? ENCENDIDA : APAGADA;
    }
    String s = v == null ? "" : String.valueOf( v ).trim().toLowerCase( Locale.ROOT );
    if ( s.equals( APAGADA ) || s.equals( "false" ) || s.equals( "no" ) ) {
      return APAGADA;
    }
    if ( s.equals( ENCENDIDA ) || s.equals( "true" ) || s.equals( "yes" ) ) {
      return ENCENDIDA;
    }
    Sev sev = Sev.desde( s );
    if ( sev == null ) {
      throw new IllegalArgumentException( donde + " must be off, on, error, warning or info, got '" + v + "'" );
    }
    return sev.codigo();
  }

  private static String texto( Object v, String donde ) {
    if ( v == null ) {
      return null;
    }
    if ( v instanceof Map || v instanceof List ) {
      throw new IllegalArgumentException( donde + " must be a text" );
    }
    String s = String.valueOf( v ).trim();
    return s.isEmpty() ? null : s;
  }

  private static int entero( Object v, String donde ) {
    if ( v instanceof Number ) {
      return ( (Number) v ).intValue();
    }
    try {
      return Integer.parseInt( String.valueOf( v ).trim() );
    } catch ( NumberFormatException e ) {
      throw new IllegalArgumentException( donde + " must be a whole number, got '" + v + "'" );
    }
  }

  private static boolean booleano( Object v, String donde, boolean siFalta ) {
    if ( v == null ) {
      return siFalta;
    }
    if ( v instanceof Boolean ) {
      return (Boolean) v;
    }
    String s = String.valueOf( v ).trim().toLowerCase( Locale.ROOT );
    if ( s.equals( "true" ) || s.equals( "yes" ) || s.equals( "on" ) ) {
      return true;
    }
    if ( s.equals( "false" ) || s.equals( "no" ) || s.equals( "off" ) ) {
      return false;
    }
    throw new IllegalArgumentException( donde + " must be true or false, got '" + v + "'" );
  }

  static String leer( InputStream in ) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[ 8192 ];
    int n;
    while ( ( n = in.read( buf ) ) > 0 ) {
      bo.write( buf, 0, n );
      if ( bo.size() > MAX_BYTES ) {
        throw new IllegalArgumentException( "bigger than " + MAX_BYTES / 1024 + " KB" );
      }
    }
    String t = new String( bo.toByteArray(), StandardCharsets.UTF_8 );
    return t.startsWith( "﻿" ) ? t.substring( 1 ) : t;
  }

  private static String primeraLinea( Throwable e ) {
    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    int i = m.indexOf( '\n' );
    String l = i < 0 ? m : m.substring( 0, i );
    // SnakeYAML: la posición va en otra línea; se agrega si está
    java.util.regex.Matcher mt = Pattern.compile( "line (\\d+), column (\\d+)" ).matcher( m );
    if ( mt.find() && !l.contains( "line " ) ) {
      l += " (line " + mt.group( 1 ) + ", column " + mt.group( 2 ) + ")";
    }
    return l.trim();
  }
}
