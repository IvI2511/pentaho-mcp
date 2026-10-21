package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Recursos MCP (resources/list, resources/read, resources/templates/list y completion/complete): el
 * catalogo de steps y entradas de job y las guias de la skill pentaho-etl, para los clientes que no
 * tienen la skill (Codex, otros agentes).
 *
 * build.ps1 copia esos archivos dentro del jar bajo pentaho-etl/, con la misma estructura que
 * claude/skills/pentaho-etl (asi no se duplican en git y en las pruebas alcanza con poner
 * claude/skills en el classpath). Se leen con getResourceAsStream sobre esta clase (classloader del
 * plugin) y se cachean: son de solo lectura y no cambian mientras Spoon esta abierto.
 *
 * resources/list trae solo los documentos (indice, steps comunes y las guias) y el catalogo del linter
 * (pentaho://lint/rules, JSON armado en el momento: no es un archivo del classpath); los ~300 XML por
 * step / entrada van por plantilla (pentaho://catalog/steps/{id}), para no gastar tokens listandolos.
 * El glosario del modo clase (catalogo/glosario-clase.json) va entero en pentaho://glossary y por step e
 * idioma en pentaho://glossary/{lang}/{id}, con los titulos de sus codigos de 03-errores-comunes.md.
 */
final class McpResources {

  /** Raiz dentro del classpath: misma estructura que claude/skills/pentaho-etl. */
  private static final String RAIZ = "/pentaho-etl/";

  private static final String MD = "text/markdown";
  private static final String XML = "application/xml";
  private static final String JSON = "application/json";

  /** El catalogo de reglas de lint_etl (LintTools.catalogo): JSON armado en el momento, cacheado. */
  static final String LINT_REGLAS = "pentaho://lint/rules";
  /** Clave de LINT_REGLAS en la cache (no choca con las rutas del classpath, que no empiezan con ':'). */
  private static final String CACHE_LINT = ":lint/rules";

  static final String PLANTILLA_STEPS = "pentaho://catalog/steps/{id}";
  static final String PLANTILLA_ENTRADAS = "pentaho://catalog/job-entries/{id}";
  private static final String PREFIJO_STEPS = "pentaho://catalog/steps/";
  private static final String PREFIJO_ENTRADAS = "pentaho://catalog/job-entries/";
  private static final String CARPETA_STEPS = "catalogo/steps/";
  private static final String CARPETA_ENTRADAS = "catalogo/job-entries/";
  private static final String INDICE = "catalogo/INDICE.md";

  /**
   * El glosario del modo clase (docs/modo-clase-diseno.md §7): el archivo entero en resources/list y, por
   * plantilla, un step en un idioma con los titulos de sus codigos del manual de errores ya resueltos.
   */
  static final String GLOSARIO = "pentaho://glossary";
  static final String PLANTILLA_GLOSARIO = "pentaho://glossary/{lang}/{id}";
  private static final String PREFIJO_GLOSARIO = "pentaho://glossary/";
  private static final String RUTA_GLOSARIO = "catalogo/glosario-clase.json";
  private static final String RUTA_ERRORES = "manual/03-errores-comunes.md";
  private static final String[] IDIOMAS_GLOSARIO = { "en", "es", "pt" };
  /** Encabezado de un error del manual: "### C18. Stream lookup with ..." (grupo 1 el codigo, 2 el titulo). */
  private static final Pattern CODIGO_ERROR = Pattern.compile( "(?m)^### ([A-E]\\d+)\\. (.+?)\\s*$" );

  /** Items por pagina en resources/list y resources/templates/list (cursor = posicion). */
  private static final int PAGINA = 100;

  /** Tope de valores en completion/complete (lo fija el spec). */
  private static final int MAX_COMPLETAR = 100;

  /** Ids de step / entrada validos: tambien evita rutas con ../ dentro del classpath. */
  private static final Pattern ID = Pattern.compile( "[A-Za-z0-9_-]+" );

  /** Documento fijo (markdown) que aparece en resources/list. */
  private static final class Doc {
    final String uri;
    final String nombre;
    final String titulo;
    final String descripcion;
    final String ruta;

    Doc( String uri, String nombre, String titulo, String descripcion, String ruta ) {
      this.uri = uri;
      this.nombre = nombre;
      this.titulo = titulo;
      this.descripcion = descripcion;
      this.ruta = ruta;
    }
  }

  private static final Doc[] DOCS = {
    new Doc( "pentaho://catalog/index", "catalog-index", "Step and job entry catalog (index)",
      "Every step and job entry type of this PDI 9.4 installation with its plugin id (the value of <type>), "
        + "display name and origin, plus which ids do not resemble their name. Where the text says "
        + "steps/<id>.xml or job-entries/<id>.xml, read pentaho://catalog/steps/{id} or "
        + "pentaho://catalog/job-entries/{id}.", INDICE ),
    new Doc( "pentaho://catalog/common-steps", "common-steps", "Most-used steps (annotated)",
      "Annotated configuration XML of the ~45 steps of a typical ETL (CSV/table input, select values, "
        + "filter rows, calculator, lookups, joins, sorts, outputs...) with realistic examples; the fragment "
        + "shown is what add_step/configure_step take as config_xml.", "catalogo/steps-comunes.md" ),
    new Doc( "pentaho://guide/ktr-format", "ktr-format", "Transformation (.ktr) format",
      "How a PDI 9.4 transformation (.ktr) is written: skeleton, <info>, notes, hops, steps, field "
        + "declarations, connections, parameters and variables, per-step error handling and escaping; verified "
        + "with Pan.", "manual/01-formato-ktr.md" ),
    new Doc( "pentaho://guide/kjb-format", "kjb-format", "Job (.kjb) format",
      "How a PDI 9.4 job (.kjb) is written: entries, START/DUMMY, hops with conditions (always, on success, "
        + "on failure), parameters and calling transformations or other jobs, verified with Kitchen.",
      "manual/02-formato-kjb.md" ),
    new Doc( "pentaho://guide/common-errors", "common-errors", "Common errors",
      "Real Pan/Kitchen error messages (exit codes, environment and format errors, silent errors that exit 0 "
        + "with a wrong result), what triggers each one and how to fix it, plus a pre-run checklist.",
      "manual/03-errores-comunes.md" ),
    new Doc( "pentaho://guide/reports", "reports", "Reports (build_report, get_report_data, show_report)",
      "Which report tool to use, the usual flow, sections, formats and languages, and how to write the "
        + "optional narrative from get_report_data so that every statement rests on the real numbers.",
      "manual/04-informes.md" ),
    new Doc( "pentaho://guide/quality", "quality", "Technical quality (lint_etl, apply_lint_fixes, suppress_lint)",
      "When to run lint_etl and how to read its findings and its 0-100 score, the rules and the Pan/Kitchen error "
        + "each one prevents, the deep mode that reads the sources, applying fixes, silencing a rule, and the "
        + "quality section of the reports. The machine-readable rule catalog is pentaho://lint/rules.",
      "manual/06-calidad.md" )
  };

  /** Texto por ruta del classpath (relativa a RAIZ). */
  private final Map<String, String> cache = new ConcurrentHashMap<String, String>();

  /** Ids por carpeta del catalogo, ordenados. */
  private final Map<String, List<String>> ids = new ConcurrentHashMap<String, List<String>>();

  /** Por que fallo el catalogo del linter (para el texto de resources/read). */
  private volatile String errorLint;

  static void register( McpHttpServer server ) {
    server.resources( new McpResources() );
    try { // nunca cortar el arranque del servidor por un aviso
      if ( McpResources.class.getResource( RAIZ + INDICE ) == null ) {
        McpLifecycleListener.LOG.logError( "Pentaho MCP: el jar no trae el catalogo (" + RAIZ
          + "); los recursos pentaho:// quedan vacios. Compilar con build.ps1." );
      }
    } catch ( Throwable t ) {
      // sin log
    }
  }

  /** Resultado de resources/list. */
  ObjectNode listar( String cursor ) throws McpHttpServer.RpcError {
    List<ObjectNode> items = new ArrayList<ObjectNode>();
    for ( Doc d : DOCS ) {
      String texto = cargar( d.ruta );
      if ( texto == null ) { // build sin catalogo: no listar lo que no se puede leer
        continue;
      }
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      o.put( "uri", d.uri );
      o.put( "name", d.nombre );
      o.put( "title", d.titulo );
      o.put( "description", d.descripcion );
      o.put( "mimeType", MD );
      o.put( "size", texto.getBytes( StandardCharsets.UTF_8 ).length );
      items.add( o );
    }
    String reglas = catalogoLint();
    if ( reglas != null ) {
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      o.put( "uri", LINT_REGLAS );
      o.put( "name", "lint-rules" );
      o.put( "title", "Lint rules (catalog)" );
      o.put( "description", "The rules of lint_etl: id, severity, category, whether it is off by default or "
        + "depends on the computer, and what/why/how to fix in en, es and pt; plus the scoring formula." );
      o.put( "mimeType", JSON );
      o.put( "size", reglas.getBytes( StandardCharsets.UTF_8 ).length );
      items.add( o );
    }
    String glosario = cargar( RUTA_GLOSARIO );
    if ( glosario != null ) {
      ObjectNode o = McpHttpServer.JSON.createObjectNode();
      o.put( "uri", GLOSARIO );
      o.put( "name", "class-glossary" );
      o.put( "title", "Class glossary (steps for beginners)" );
      o.put( "description", "For ~50 common step types: a one-line what, the typical why and an Excel/SQL analogy in "
        + "en, es and pt, plus the codes of pentaho://guide/common-errors each step typically triggers (watch_out). "
        + "One step in one language, with the error titles: pentaho://glossary/{lang}/{id}." );
      o.put( "mimeType", JSON );
      o.put( "size", glosario.getBytes( StandardCharsets.UTF_8 ).length );
      items.add( o );
    }
    return pagina( items, cursor, "resources" );
  }

  /** Resultado de resources/templates/list. */
  ObjectNode plantillas( String cursor ) throws McpHttpServer.RpcError {
    List<ObjectNode> items = new ArrayList<ObjectNode>();
    if ( cargar( INDICE ) != null ) {
      items.add( plantilla( PLANTILLA_STEPS, "step-xml", "Step default XML",
        "Complete default <step> block of a step type, as StepMeta.getXML() writes it after setDefault(). "
          + "{id} is the plugin id (case-sensitive) from pentaho://catalog/index or list_step_types, e.g. "
          + "TableInput, CsvInput, SelectValues, FilterRows, TextFileOutput." ) );
      items.add( plantilla( PLANTILLA_ENTRADAS, "job-entry-xml", "Job entry default XML",
        "Complete default <entry> block of a job entry type, as JobEntryCopy.getXML() writes it. {id} is the "
          + "plugin id (case-sensitive) from pentaho://catalog/index or list_entry_types, e.g. TRANS, JOB, "
          + "SQL, WRITE_TO_LOG. START and DUMMY have no XML of their own: they are SPECIAL." ) );
    }
    if ( cargar( RUTA_GLOSARIO ) != null ) {
      items.add( plantilla( PLANTILLA_GLOSARIO, "class-glossary-step", "Class glossary entry",
        "One step type explained for a class: what, why and an Excel/SQL analogy in {lang} (en, es or pt), and "
          + "watch_out with the code and title of each typical error of pentaho://guide/common-errors. {id} is "
          + "the plugin id, e.g. CsvInput, FilterRows, StreamLookup, GroupBy.", JSON ) );
    }
    return pagina( items, cursor, "resourceTemplates" );
  }

  /** Resultado de resources/read; null si la URI no corresponde a ningun recurso. */
  ObjectNode leer( String uri ) {
    if ( LINT_REGLAS.equals( uri ) ) {
      String reglas = catalogoLint();
      ObjectNode res = McpHttpServer.JSON.createObjectNode();
      ObjectNode c = res.putArray( "contents" ).addObject().put( "uri", uri );
      if ( reglas != null ) {
        c.put( "mimeType", JSON ).put( "text", reglas );
      } else { // no deberia pasar: los textos viajan en el jar
        c.put( "mimeType", "text/plain" ).put( "text", "The lint rule catalog could not be built: "
          + errorLint );
      }
      return res;
    }
    if ( GLOSARIO.equals( uri ) || uri.startsWith( PREFIJO_GLOSARIO ) ) {
      String texto;
      String canonicaGlosario = uri;
      if ( GLOSARIO.equals( uri ) ) {
        texto = cargar( RUTA_GLOSARIO );
      } else {
        String resto = uri.substring( PREFIJO_GLOSARIO.length() );
        int barra = resto.indexOf( '/' );
        ObjectNode e = barra < 0 ? null : glosario( resto.substring( 0, barra ), resto.substring( barra + 1 ) );
        if ( e == null ) {
          return null;
        }
        texto = e.toString();
        canonicaGlosario = PREFIJO_GLOSARIO + e.path( "lang" ).asText() + "/" + e.path( "id" ).asText();
      }
      if ( texto == null ) {
        return null;
      }
      ObjectNode res = McpHttpServer.JSON.createObjectNode();
      res.putArray( "contents" ).addObject().put( "uri", canonicaGlosario ).put( "mimeType", JSON ).put( "text", texto );
      return res;
    }
    String ruta = null;
    String mime = MD;
    String canonica = uri;
    for ( Doc d : DOCS ) {
      if ( d.uri.equals( uri ) ) {
        ruta = d.ruta;
      }
    }
    if ( ruta == null ) {
      String prefijo;
      String carpeta;
      if ( uri.startsWith( PREFIJO_STEPS ) ) {
        prefijo = PREFIJO_STEPS;
        carpeta = CARPETA_STEPS;
      } else if ( uri.startsWith( PREFIJO_ENTRADAS ) ) {
        prefijo = PREFIJO_ENTRADAS;
        carpeta = CARPETA_ENTRADAS;
      } else {
        return null;
      }
      String id = uri.substring( prefijo.length() );
      if ( id.toLowerCase( Locale.ROOT ).endsWith( ".xml" ) ) { // la guia habla de steps/<id>.xml
        id = id.substring( 0, id.length() - 4 );
      }
      id = canonico( carpeta, id );
      if ( id == null ) {
        return null;
      }
      ruta = carpeta + id + ".xml";
      mime = XML;
      canonica = prefijo + id;
    }
    String texto = cargar( ruta );
    if ( texto == null ) {
      return null;
    }
    ObjectNode res = McpHttpServer.JSON.createObjectNode();
    res.putArray( "contents" ).addObject().put( "uri", canonica ).put( "mimeType", mime ).put( "text", texto );
    return res;
  }

  /** Resultado de completion/complete: ids que empiezan (y despues que contienen) lo escrito. */
  ObjectNode completar( JsonNode params ) {
    JsonNode ref = params.path( "ref" );
    JsonNode arg = params.path( "argument" );
    String carpeta = null;
    List<String> candidatos = null;
    if ( "ref/resource".equals( ref.path( "type" ).asText() ) ) {
      String uri = ref.path( "uri" ).asText();
      String nombre = arg.path( "name" ).asText();
      if ( "id".equals( nombre ) && PLANTILLA_STEPS.equals( uri ) ) {
        carpeta = CARPETA_STEPS;
      } else if ( "id".equals( nombre ) && PLANTILLA_ENTRADAS.equals( uri ) ) {
        carpeta = CARPETA_ENTRADAS;
      } else if ( PLANTILLA_GLOSARIO.equals( uri ) && "lang".equals( nombre ) ) {
        candidatos = Arrays.asList( IDIOMAS_GLOSARIO );
      } else if ( PLANTILLA_GLOSARIO.equals( uri ) && "id".equals( nombre ) ) {
        candidatos = idsGlosario();
      }
    }
    if ( carpeta != null ) {
      candidatos = ids( carpeta );
    }
    List<String> encontrados = new ArrayList<String>();
    if ( candidatos != null ) {
      String valor = arg.path( "value" ).asText( "" ).toLowerCase( Locale.ROOT );
      List<String> contienen = new ArrayList<String>();
      for ( String id : candidatos ) {
        String b = id.toLowerCase( Locale.ROOT );
        if ( b.startsWith( valor ) ) {
          encontrados.add( id );
        } else if ( b.contains( valor ) ) {
          contienen.add( id );
        }
      }
      encontrados.addAll( contienen );
    }
    ObjectNode res = McpHttpServer.JSON.createObjectNode();
    ObjectNode c = res.putObject( "completion" );
    ArrayNode valores = c.putArray( "values" );
    for ( int i = 0; i < Math.min( MAX_COMPLETAR, encontrados.size() ); i++ ) {
      valores.add( encontrados.get( i ) );
    }
    c.put( "total", encontrados.size() );
    c.put( "hasMore", encontrados.size() > MAX_COMPLETAR );
    return res;
  }

  private static ObjectNode plantilla( String uriTemplate, String nombre, String titulo, String descripcion ) {
    return plantilla( uriTemplate, nombre, titulo, descripcion, XML );
  }

  private static ObjectNode plantilla( String uriTemplate, String nombre, String titulo, String descripcion,
                                       String mime ) {
    ObjectNode o = McpHttpServer.JSON.createObjectNode();
    o.put( "uriTemplate", uriTemplate );
    o.put( "name", nombre );
    o.put( "title", titulo );
    o.put( "description", descripcion );
    o.put( "mimeType", mime );
    return o;
  }

  // ---------------------------------------------------------------- glosario del modo clase

  /**
   * Una entrada del glosario en un idioma: {id, lang, what, why, analogy, watch_out: [{code, title}], errors_guide};
   * null si el idioma no es en/es/pt, el id no esta en el glosario o el jar no lo trae. Idioma e id ignoran
   * mayusculas (devuelve los canonicos). Lo usa resources/read y lo usara watch_out "auto" de las lecciones.
   */
  ObjectNode glosario( String idioma, String id ) {
    JsonNode raiz = arbolGlosario();
    if ( raiz == null || idioma == null || id == null ) {
      return null;
    }
    String lang = null;
    for ( String l : IDIOMAS_GLOSARIO ) {
      if ( l.equalsIgnoreCase( idioma ) ) {
        lang = l;
      }
    }
    String canonico = null;
    for ( String s : idsGlosario() ) {
      if ( s.equals( id ) || ( canonico == null && s.equalsIgnoreCase( id ) ) ) {
        canonico = s;
      }
    }
    if ( lang == null || canonico == null ) {
      return null;
    }
    JsonNode e = raiz.path( "steps" ).path( canonico );
    JsonNode t = e.path( lang );
    if ( !t.isObject() ) {
      return null;
    }
    Map<String, String> titulos = titulosErrores();
    ObjectNode r = McpHttpServer.JSON.createObjectNode();
    r.put( "id", canonico ).put( "lang", lang );
    r.put( "what", t.path( "what" ).asText() );
    r.put( "why", t.path( "why" ).asText() );
    r.put( "analogy", t.path( "analogy" ).asText() );
    ArrayNode ojo = r.putArray( "watch_out" );
    for ( JsonNode c : e.path( "watch_out" ) ) {
      ObjectNode x = ojo.addObject().put( "code", c.asText() );
      String titulo = titulos.get( c.asText() );
      if ( titulo != null ) {
        x.put( "title", titulo );
      }
    }
    r.put( "errors_guide", "pentaho://guide/common-errors" );
    return r;
  }

  /** Ids del glosario, ordenados; vacia si el jar no lo trae. */
  List<String> idsGlosario() {
    List<String> r = new ArrayList<String>();
    JsonNode raiz = arbolGlosario();
    if ( raiz != null ) {
      for ( Iterator<String> it = raiz.path( "steps" ).fieldNames(); it.hasNext(); ) {
        r.add( it.next() );
      }
    }
    Collections.sort( r, String.CASE_INSENSITIVE_ORDER );
    return r;
  }

  private volatile JsonNode glosarioArbol;

  private JsonNode arbolGlosario() {
    JsonNode a = glosarioArbol;
    if ( a == null ) {
      String t = cargar( RUTA_GLOSARIO );
      if ( t == null ) {
        return null;
      }
      try {
        a = McpHttpServer.JSON.readTree( t );
      } catch ( IOException e ) {
        return null;
      }
      glosarioArbol = a;
    }
    return a;
  }

  /** Codigo -> titulo de los encabezados de 03-errores-comunes.md ("C18" -> "Stream lookup with a ..."). */
  private Map<String, String> titulosErrores() {
    Map<String, String> m = new HashMap<String, String>();
    String manual = cargar( RUTA_ERRORES );
    if ( manual != null ) {
      Matcher x = CODIGO_ERROR.matcher( manual );
      while ( x.find() ) {
        m.put( x.group( 1 ), x.group( 2 ) );
      }
    }
    return m;
  }

  /** Una pagina de items; el cursor es la posicion desde la que seguir (opaco para el cliente). */
  private static ObjectNode pagina( List<ObjectNode> items, String cursor, String clave )
    throws McpHttpServer.RpcError {
    int desde = 0;
    if ( cursor != null ) {
      try {
        desde = Integer.parseInt( cursor );
      } catch ( NumberFormatException e ) {
        desde = -1;
      }
      if ( desde < 0 || desde > items.size() ) {
        throw new McpHttpServer.RpcError( -32602, "Invalid cursor: " + cursor, null );
      }
    }
    int hasta = Math.min( items.size(), desde + PAGINA );
    ObjectNode res = McpHttpServer.JSON.createObjectNode();
    ArrayNode arr = res.putArray( clave );
    for ( int i = desde; i < hasta; i++ ) {
      arr.add( items.get( i ) );
    }
    if ( hasta < items.size() ) {
      res.put( "nextCursor", String.valueOf( hasta ) );
    }
    return res;
  }

  /** El id tal como esta en el catalogo (exacto o, si no, ignorando mayusculas); null si no existe. */
  private String canonico( String carpeta, String id ) {
    if ( !ID.matcher( id ).matches() ) {
      return null;
    }
    List<String> lista = ids( carpeta );
    if ( lista.isEmpty() ) { // no se pudo enumerar: se intenta tal cual al cargar
      return id;
    }
    if ( lista.contains( id ) ) {
      return id;
    }
    for ( String s : lista ) {
      if ( s.equalsIgnoreCase( id ) ) {
        return s;
      }
    }
    return null;
  }

  private List<String> ids( String carpeta ) {
    List<String> l = ids.get( carpeta );
    if ( l == null ) {
      l = enumerar( carpeta );
      ids.put( carpeta, l );
    }
    return l;
  }

  /**
   * Lista los .xml de una carpeta del catalogo: en el jar del plugin (URL jar:) recorre las entradas;
   * en las pruebas (carpeta en el classpath, URL file:) lista el directorio.
   */
  private static List<String> enumerar( String carpeta ) {
    List<String> r = new ArrayList<String>();
    try {
      URL u = McpResources.class.getResource( RAIZ + INDICE );
      if ( u == null ) {
        return r;
      }
      if ( "file".equals( u.getProtocol() ) ) {
        File base = new File( u.toURI() ).getParentFile().getParentFile(); // .../pentaho-etl
        File[] archivos = new File( base, carpeta ).listFiles();
        if ( archivos != null ) {
          for ( File f : archivos ) {
            agregarId( r, f.getName() );
          }
        }
      } else if ( "jar".equals( u.getProtocol() ) ) {
        URLConnection con = u.openConnection();
        con.setUseCaches( false ); // JarFile propio, que se cierra aca
        JarFile jar = ( (JarURLConnection) con ).getJarFile();
        try {
          String prefijo = RAIZ.substring( 1 ) + carpeta;
          for ( Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
            String n = e.nextElement().getName();
            if ( n.startsWith( prefijo ) && n.indexOf( '/', prefijo.length() ) < 0 ) {
              agregarId( r, n.substring( prefijo.length() ) );
            }
          }
        } finally {
          jar.close();
        }
      }
    } catch ( Exception e ) {
      // sin enumeracion igual se lee por id exacto
    }
    Collections.sort( r, String.CASE_INSENSITIVE_ORDER );
    return Collections.unmodifiableList( r );
  }

  private static void agregarId( List<String> r, String archivo ) {
    if ( archivo.endsWith( ".xml" ) ) {
      String id = archivo.substring( 0, archivo.length() - 4 );
      if ( ID.matcher( id ).matches() ) {
        r.add( id );
      }
    }
  }

  /**
   * El JSON de pentaho://lint/rules, cacheado (los textos no cambian mientras Spoon esta abierto); null si
   * no se pudo armar. Nunca lanza: resources/list y resources/read no declaran excepciones.
   */
  private String catalogoLint() {
    String t = cache.get( CACHE_LINT );
    if ( t != null ) {
      return t;
    }
    try {
      t = LintTools.catalogo();
    } catch ( Throwable e ) {
      errorLint = RunMotor.primeraLinea( e );
      return null;
    }
    cache.put( CACHE_LINT, t );
    return t;
  }

  /** Texto UTF-8 del recurso (ruta relativa a RAIZ), cacheado; null si no esta en el classpath. */
  private String cargar( String ruta ) {
    String t = cache.get( ruta );
    if ( t != null ) {
      return t;
    }
    InputStream in = McpResources.class.getResourceAsStream( RAIZ + ruta );
    if ( in == null ) {
      return null;
    }
    try {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ( ( n = in.read( b ) ) > 0 ) {
        buf.write( b, 0, n );
      }
      t = new String( buf.toByteArray(), StandardCharsets.UTF_8 );
    } catch ( IOException e ) {
      return null;
    } finally {
      try {
        in.close();
      } catch ( IOException e ) {
        // nada
      }
    }
    if ( t.startsWith( "\uFEFF" ) ) { // BOM de algun editor
      t = t.substring( 1 );
    }
    cache.put( ruta, t );
    return t;
  }
}
