package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * El «plano» de un ETL (D1): un archivo de diseño pequeño y versionable, {@code <nombre>.plan.yaml} (o .plan.json),
 * que se escribe antes de abrir el lienzo. El esquema JSON está en {@code docs/plano.schema.json}; las claves van en
 * inglés (como todo lo que ve la IA) y los textos en el idioma del usuario.
 * <p>
 * Esta clase es el modelo, la lectura (YAML con SnakeYAML 1.7 de PDI, solo con {@link SafeConstructor}: nada de
 * etiquetas {@code !!clase}; o JSON con Jackson) con sus errores de forma, y la escritura determinista (el mismo
 * plano da el mismo YAML, para que el diff de git sea limpio). No sabe nada de Spoon: la revisión está en
 * {@link PlanoRevision}, los diagramas en {@link PlanoInforme}, la construcción en {@link PlanoConstruir} y la
 * deriva en {@link PlanoDeriva}.
 */
final class Plano {

  /** Versión del formato (clave {@code plan}). */
  static final int FORMATO = 1;
  /** Tope del archivo: un plano es un documento chico. */
  static final long MAX_BYTES = 512 * 1024;
  /** URL del esquema que va en el comentario de cabecera del YAML (lo usan los editores con yaml-language-server). */
  static final String URL_ESQUEMA = "https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/docs/plano.schema.json";

  static final List<String> FORMATOS = Collections.unmodifiableList( Arrays.asList( "csv", "text", "excel", "json",
    "xml", "table", "api", "other" ) );
  static final List<String> CLASES_DESTINO = Collections.unmodifiableList( Arrays.asList( "fact", "dimension", "table",
    "file", "other" ) );
  static final List<String> CARGAS_DESTINO = Collections.unmodifiableList( Arrays.asList( "insert", "append",
    "truncate_insert", "upsert", "scd1", "scd2", "overwrite" ) );
  static final List<String> CLASES_ETAPA = Collections.unmodifiableList( Arrays.asList( "extract", "clean", "validate",
    "dedupe", "lookup", "transform", "aggregate", "load", "reject", "other" ) );
  static final List<String> SI_FALTA = Collections.unmodifiableList( Arrays.asList( "reject", "default",
    "unknown_member", "fail" ) );
  static final List<String> CARGAS = Collections.unmodifiableList( Arrays.asList( "full", "incremental" ) );
  static final List<String> SI_FALLA = Collections.unmodifiableList( Arrays.asList( "abort", "continue", "notify" ) );

  private static final Pattern ID = Pattern.compile( "[A-Za-z0-9_.-]+" );

  // ================================================================ modelo

  String nombre;
  String idioma;
  String objetivo;
  String grano;
  /** full | incremental. */
  String carga;
  /** Campo o criterio de la carga incremental (la marca de agua). */
  String incrementalPor;
  final List<Fuente> fuentes = new ArrayList<Fuente>();
  final List<Destino> destinos = new ArrayList<Destino>();
  final List<Regla> reglas = new ArrayList<Regla>();
  final List<Etapa> etapas = new ArrayList<Etapa>();
  Orquestacion orquestacion;
  Entorno entorno;
  final List<Riesgo> riesgos = new ArrayList<Riesgo>();
  final List<Decision> decisiones = new ArrayList<Decision>();

  /** De dónde se leyó (null si vino en línea). No se escribe. */
  File archivo;

  static final class Fuente {
    String id;
    String nombre;
    String formato;
    String ubicacion;
    String volumen;
    String frecuencia;
    final List<String> clave = new ArrayList<String>();
    /** Dónde van las filas malas de esta fuente (el plan de rechazos). */
    String rechazos;
    String conexion;
    String notas;
  }

  static final class Destino {
    String id;
    String nombre;
    String clase;
    String ubicacion;
    String conexion;
    final List<String> clave = new ArrayList<String>();
    String carga;
    String notas;
  }

  static final class Regla {
    String id;
    String texto;
  }

  static final class Etapa {
    String id;
    String nombre;
    String clase;
    /** La transformación (.ktr) donde va; null = la única. */
    String transformacion;
    String descripcion;
    final List<String> despues = new ArrayList<String>();
    final List<String> lee = new ArrayList<String>();
    final List<String> escribe = new ArrayList<String>();
    /** Lookup: la fuente, destino o etapa contra la que se busca. */
    String busca;
    final List<String> clave = new ArrayList<String>();
    String siFalta;
    final List<String> reglas = new ArrayList<String>();
    /** Tipo de step sugerido (CsvInput, StreamLookup...), para plan_build mode=steps y plan_check. */
    String tipoStep;
  }

  static final class Orquestacion {
    String nombre;
    final List<String> orden = new ArrayList<String>();
    String siFalla;
    String avisar;
    /** Carpeta de los .ktr/.kjb, relativa al plano (default: la del plano). */
    String carpeta;
  }

  static final class Entorno {
    final List<String[]> conexiones = new ArrayList<String[]>(); // {name, type, host}
    final List<String[]> carpetas = new ArrayList<String[]>(); // {name, path}
    String ejecuta;
  }

  static final class Riesgo {
    String texto;
    String mitigacion;
  }

  static final class Decision {
    String texto;
    String porque;
  }

  // ================================================================ consultas

  Fuente fuente( String id ) {
    for ( Fuente f : fuentes ) {
      if ( f.id != null && f.id.equals( id ) ) {
        return f;
      }
    }
    return null;
  }

  Destino destino( String id ) {
    for ( Destino d : destinos ) {
      if ( d.id != null && d.id.equals( id ) ) {
        return d;
      }
    }
    return null;
  }

  Etapa etapa( String id ) {
    for ( Etapa e : etapas ) {
      if ( e.id != null && e.id.equals( id ) ) {
        return e;
      }
    }
    return null;
  }

  Regla regla( String id ) {
    for ( Regla r : reglas ) {
      if ( r.id != null && r.id.equals( id ) ) {
        return r;
      }
    }
    return null;
  }

  /** Nombre de algo del plano por id (fuente, destino, etapa o regla), o el id. */
  String nombreDe( String id ) {
    Fuente f = fuente( id );
    if ( f != null ) {
      return f.nombre;
    }
    Destino d = destino( id );
    if ( d != null ) {
      return d.nombre;
    }
    Etapa e = etapa( id );
    if ( e != null ) {
      return e.nombre;
    }
    return id;
  }

  /**
   * Las transformaciones del plano en orden: las de orchestration.order primero y luego las demás en el orden en que
   * aparecen en las etapas. "" = las etapas sin transformación (una sola transformación sin nombre).
   */
  List<String> transformaciones() {
    Set<String> l = new LinkedHashSet<String>();
    if ( orquestacion != null ) {
      for ( String t : orquestacion.orden ) {
        l.add( t );
      }
    }
    for ( Etapa e : etapas ) {
      l.add( e.transformacion == null ? "" : e.transformacion );
    }
    return new ArrayList<String>( l );
  }

  /** Las etapas de una transformación ("" = las que no dicen ninguna), en el orden del plano. */
  List<Etapa> etapasDe( String transformacion ) {
    String t = transformacion == null ? "" : transformacion;
    List<Etapa> l = new ArrayList<Etapa>();
    for ( Etapa e : etapas ) {
      if ( t.equals( e.transformacion == null ? "" : e.transformacion ) ) {
        l.add( e );
      }
    }
    return l;
  }

  /** La transformación pedida por nombre (sin mayúsculas ni .ktr), o null. */
  String buscarTransformacion( String pedida ) {
    if ( pedida == null ) {
      return null;
    }
    String p = sinKtr( pedida.trim() );
    for ( String t : transformaciones() ) {
      if ( t.equalsIgnoreCase( p ) ) {
        return t;
      }
    }
    return null;
  }

  static String sinKtr( String s ) {
    String x = s.replace( '\\', '/' );
    x = x.substring( x.lastIndexOf( '/' ) + 1 );
    return x.toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) ? x.substring( 0, x.length() - 4 ) : x;
  }

  // ================================================================ lectura

  /** Lo que sale de leer: el plano (null si hubo errores), los errores de forma y los avisos (claves desconocidas). */
  static final class Lectura {
    Plano plano;
    final List<String> errores = new ArrayList<String>();
    final List<String> avisos = new ArrayList<String>();
  }

  /** Lee un archivo .plan.yaml / .plan.yml / .plan.json. */
  static Lectura leer( File f ) throws IOException {
    if ( !f.isFile() ) {
      throw new IOException( "Plan file not found: " + f.getPath() );
    }
    if ( f.length() > MAX_BYTES ) {
      throw new IOException( "The plan file is too big (" + f.length() + " bytes, at most " + MAX_BYTES + ")" );
    }
    String texto = new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
    Lectura l = leerTexto( texto );
    if ( l.plano != null ) {
      l.plano.archivo = f.getAbsoluteFile();
    }
    return l;
  }

  /** Lee el texto de un plano (YAML o JSON). */
  static Lectura leerTexto( String texto ) {
    Lectura l = new Lectura();
    if ( texto == null || texto.trim().isEmpty() ) {
      l.errores.add( "The plan is empty" );
      return l;
    }
    String t = texto.replace( "﻿", "" );
    JsonNode raiz;
    try {
      if ( t.trim().startsWith( "{" ) ) {
        raiz = mapper().readTree( t );
      } else {
        Object o = new Yaml( new SafeConstructor() ).load( t );
        raiz = mapper().valueToTree( escalares( o ) );
      }
    } catch ( Throwable e ) {
      l.errores.add( "The plan is not valid YAML/JSON: " + primeraLinea( e.getMessage() ) );
      return l;
    }
    return desdeJson( raiz, l );
  }

  /** Lee un plano ya en JSON (el argumento 'plan' de plan_create). */
  static Lectura desdeJson( JsonNode raiz ) {
    return desdeJson( raiz, new Lectura() );
  }

  private static Lectura desdeJson( JsonNode raiz, Lectura l ) {
    if ( raiz == null || !raiz.isObject() ) {
      l.errores.add( "The plan must be an object with keys plan, name, goal, grain, sources, targets, stages..." );
      return l;
    }
    Lector r = new Lector( l );
    Plano p = new Plano();
    r.claves( raiz, "", "plan", "name", "language", "goal", "grain", "load", "incremental_by", "sources", "targets",
      "rules", "stages", "orchestration", "environment", "risks", "decisions" );
    JsonNode v = raiz.get( "plan" );
    if ( v != null && !v.isNull() && v.asInt( -1 ) != FORMATO ) {
      l.errores.add( "plan: unknown format version '" + v.asText() + "' (this Pentaho Live reads version " + FORMATO
        + ")" );
    }
    p.nombre = r.texto( raiz, "name", "", true );
    p.idioma = r.enumOpc( raiz, "language", "", Arrays.asList( "en", "es", "pt" ) );
    p.objetivo = r.texto( raiz, "goal", "", false );
    p.grano = r.texto( raiz, "grain", "", false );
    p.carga = r.enumOpc( raiz, "load", "", CARGAS );
    p.incrementalPor = r.texto( raiz, "incremental_by", "", false );

    int i = 0;
    for ( JsonNode n : r.lista( raiz, "sources" ) ) {
      String w = "sources[" + i++ + "]";
      if ( !r.objeto( n, w ) ) {
        continue;
      }
      r.claves( n, w, "id", "name", "format", "location", "volume", "frequency", "key", "rejects", "connection",
        "notes" );
      Fuente f = new Fuente();
      f.nombre = r.texto( n, "name", w, true );
      f.id = r.id( n, w, f.nombre );
      f.formato = r.enumOpc( n, "format", w, FORMATOS );
      f.ubicacion = r.texto( n, "location", w, false );
      f.volumen = r.texto( n, "volume", w, false );
      f.frecuencia = r.texto( n, "frequency", w, false );
      f.clave.addAll( r.textos( n, "key", w ) );
      f.rechazos = r.texto( n, "rejects", w, false );
      f.conexion = r.texto( n, "connection", w, false );
      f.notas = r.texto( n, "notes", w, false );
      p.fuentes.add( f );
    }
    i = 0;
    for ( JsonNode n : r.lista( raiz, "targets" ) ) {
      String w = "targets[" + i++ + "]";
      if ( !r.objeto( n, w ) ) {
        continue;
      }
      r.claves( n, w, "id", "name", "kind", "location", "connection", "key", "load", "notes" );
      Destino d = new Destino();
      d.nombre = r.texto( n, "name", w, true );
      d.id = r.id( n, w, d.nombre );
      d.clase = r.enumOpc( n, "kind", w, CLASES_DESTINO );
      d.ubicacion = r.texto( n, "location", w, false );
      d.conexion = r.texto( n, "connection", w, false );
      d.clave.addAll( r.textos( n, "key", w ) );
      d.carga = r.enumOpc( n, "load", w, CARGAS_DESTINO );
      d.notas = r.texto( n, "notes", w, false );
      p.destinos.add( d );
    }
    i = 0;
    for ( JsonNode n : r.lista( raiz, "rules" ) ) {
      String w = "rules[" + i++ + "]";
      Regla x = new Regla();
      if ( n != null && n.isTextual() ) {
        x.texto = n.asText().trim();
        x.id = "R" + i;
      } else {
        if ( !r.objeto( n, w ) ) {
          continue;
        }
        r.claves( n, w, "id", "text" );
        x.texto = r.texto( n, "text", w, true );
        x.id = r.texto( n, "id", w, false );
        if ( x.id == null ) {
          x.id = "R" + i;
        }
      }
      p.reglas.add( x );
    }
    i = 0;
    for ( JsonNode n : r.lista( raiz, "stages" ) ) {
      String w = "stages[" + i++ + "]";
      if ( !r.objeto( n, w ) ) {
        continue;
      }
      r.claves( n, w, "id", "name", "kind", "transformation", "description", "after", "reads", "writes", "lookup",
        "key", "on_missing", "rules", "step_type" );
      Etapa e = new Etapa();
      e.nombre = r.texto( n, "name", w, true );
      e.id = r.id( n, w, e.nombre );
      e.clase = r.enumOpc( n, "kind", w, CLASES_ETAPA );
      String t = r.texto( n, "transformation", w, false );
      e.transformacion = t == null ? null : sinKtr( t );
      e.descripcion = r.texto( n, "description", w, false );
      e.despues.addAll( r.textos( n, "after", w ) );
      e.lee.addAll( r.textos( n, "reads", w ) );
      e.escribe.addAll( r.textos( n, "writes", w ) );
      e.busca = r.texto( n, "lookup", w, false );
      e.clave.addAll( r.textos( n, "key", w ) );
      e.siFalta = r.enumOpc( n, "on_missing", w, SI_FALTA );
      e.reglas.addAll( r.textos( n, "rules", w ) );
      e.tipoStep = r.texto( n, "step_type", w, false );
      p.etapas.add( e );
    }
    JsonNode o = raiz.get( "orchestration" );
    if ( o != null && !o.isNull() && r.objeto( o, "orchestration" ) ) {
      r.claves( o, "orchestration", "name", "order", "on_failure", "notify", "folder" );
      Orquestacion x = new Orquestacion();
      x.nombre = r.texto( o, "name", "orchestration", false );
      for ( String s : r.textos( o, "order", "orchestration" ) ) {
        x.orden.add( sinKtr( s ) );
      }
      x.siFalla = r.enumOpc( o, "on_failure", "orchestration", SI_FALLA );
      x.avisar = r.texto( o, "notify", "orchestration", false );
      x.carpeta = r.texto( o, "folder", "orchestration", false );
      p.orquestacion = x;
    }
    JsonNode en = raiz.get( "environment" );
    if ( en != null && !en.isNull() && r.objeto( en, "environment" ) ) {
      r.claves( en, "environment", "connections", "folders", "runner" );
      Entorno x = new Entorno();
      int k = 0;
      for ( JsonNode c : r.lista( en, "connections" ) ) {
        String w = "environment.connections[" + k++ + "]";
        if ( r.objeto( c, w ) ) {
          r.claves( c, w, "name", "type", "host" );
          x.conexiones.add( new String[] { r.texto( c, "name", w, true ), r.texto( c, "type", w, false ), r.texto( c,
            "host", w, false ) } );
        }
      }
      k = 0;
      for ( JsonNode c : r.lista( en, "folders" ) ) {
        String w = "environment.folders[" + k++ + "]";
        if ( r.objeto( c, w ) ) {
          r.claves( c, w, "name", "path" );
          x.carpetas.add( new String[] { r.texto( c, "name", w, true ), r.texto( c, "path", w, false ) } );
        }
      }
      x.ejecuta = r.texto( en, "runner", "environment", false );
      p.entorno = x;
    }
    i = 0;
    for ( JsonNode n : r.lista( raiz, "risks" ) ) {
      String w = "risks[" + i++ + "]";
      Riesgo x = new Riesgo();
      if ( n != null && n.isTextual() ) {
        x.texto = n.asText().trim();
      } else {
        if ( !r.objeto( n, w ) ) {
          continue;
        }
        r.claves( n, w, "text", "mitigation" );
        x.texto = r.texto( n, "text", w, true );
        x.mitigacion = r.texto( n, "mitigation", w, false );
      }
      p.riesgos.add( x );
    }
    i = 0;
    for ( JsonNode n : r.lista( raiz, "decisions" ) ) {
      String w = "decisions[" + i++ + "]";
      Decision x = new Decision();
      if ( n != null && n.isTextual() ) {
        x.texto = n.asText().trim();
      } else {
        if ( !r.objeto( n, w ) ) {
          continue;
        }
        r.claves( n, w, "text", "why" );
        x.texto = r.texto( n, "text", w, true );
        x.porque = r.texto( n, "why", w, false );
      }
      p.decisiones.add( x );
    }
    referencias( p, l.errores );
    if ( l.errores.isEmpty() ) {
      l.plano = p;
    }
    return l;
  }

  /** Ids repetidos y referencias que no existen (errores: el plano no se puede dibujar ni construir así). */
  static void referencias( Plano p, List<String> errores ) {
    Set<String> ids = new HashSet<String>();
    for ( Fuente f : p.fuentes ) {
      unico( ids, f.id, "sources", errores );
    }
    for ( Destino d : p.destinos ) {
      unico( ids, d.id, "targets", errores );
    }
    for ( Etapa e : p.etapas ) {
      unico( ids, e.id, "stages", errores );
    }
    Set<String> reglas = new HashSet<String>();
    for ( Regla r : p.reglas ) {
      if ( !reglas.add( r.id ) ) {
        errores.add( "rules: id '" + r.id + "' is repeated" );
      }
    }
    Set<String> nombres = new HashSet<String>();
    for ( Etapa e : p.etapas ) {
      String clave = ( e.transformacion == null ? "" : e.transformacion.toLowerCase( Locale.ROOT ) ) + "\u0000"
        + e.nombre.toLowerCase( Locale.ROOT );
      if ( !nombres.add( clave ) ) {
        errores.add( "stages: two stages of the same transformation are called '" + e.nombre + "' (they become steps: "
          + "the names must be unique)" );
      }
      for ( String a : e.despues ) {
        if ( p.etapa( a ) == null ) {
          errores.add( "stages '" + e.id + "'.after: there is no stage '" + a + "'" );
        } else if ( a.equals( e.id ) ) {
          errores.add( "stages '" + e.id + "'.after: a stage cannot come after itself" );
        }
      }
      for ( String a : e.lee ) {
        if ( p.fuente( a ) == null && p.destino( a ) == null ) {
          errores.add( "stages '" + e.id + "'.reads: there is no source or target '" + a + "'" );
        }
      }
      for ( String a : e.escribe ) {
        if ( p.destino( a ) == null ) {
          errores.add( "stages '" + e.id + "'.writes: there is no target '" + a + "'" );
        }
      }
      if ( e.busca != null && p.fuente( e.busca ) == null && p.destino( e.busca ) == null
        && p.etapa( e.busca ) == null ) {
        errores.add( "stages '" + e.id + "'.lookup: there is no source, target or stage '" + e.busca + "'" );
      }
      for ( String a : e.reglas ) {
        if ( p.regla( a ) == null ) {
          errores.add( "stages '" + e.id + "'.rules: there is no rule '" + a + "'" );
        }
      }
    }
    if ( p.orquestacion != null ) {
      Set<String> ts = new HashSet<String>();
      for ( Etapa e : p.etapas ) {
        if ( e.transformacion != null ) {
          ts.add( e.transformacion.toLowerCase( Locale.ROOT ) );
        }
      }
      for ( String t : p.orquestacion.orden ) {
        if ( !ts.contains( t.toLowerCase( Locale.ROOT ) ) ) {
          errores.add( "orchestration.order: no stage has transformation '" + t + "'" );
        }
      }
    }
    String ciclo = ciclo( p );
    if ( ciclo != null ) {
      errores.add( "stages: the 'after' links make a loop (" + ciclo + ")" );
    }
  }

  private static void unico( Set<String> ids, String id, String donde, List<String> errores ) {
    if ( id != null && !ids.add( id ) ) {
      errores.add( donde + ": id '" + id + "' is used twice (ids are shared by sources, targets and stages)" );
    }
  }

  /** Un ciclo en los 'after' ("a → b → a"), o null. */
  static String ciclo( Plano p ) {
    Map<String, Integer> estado = new LinkedHashMap<String, Integer>();
    for ( Etapa e : p.etapas ) {
      List<String> pila = new ArrayList<String>();
      String c = dfs( p, e.id, estado, pila );
      if ( c != null ) {
        return c;
      }
    }
    return null;
  }

  private static String dfs( Plano p, String id, Map<String, Integer> estado, List<String> pila ) {
    Integer s = estado.get( id );
    if ( s != null && s == 2 ) {
      return null;
    }
    if ( s != null && s == 1 ) {
      int k = pila.indexOf( id );
      List<String> c = new ArrayList<String>( pila.subList( Math.max( 0, k ), pila.size() ) );
      c.add( id );
      return String.join( " → ", c );
    }
    estado.put( id, 1 );
    pila.add( id );
    Etapa e = p.etapa( id );
    if ( e != null ) {
      for ( String a : e.despues ) {
        if ( p.etapa( a ) != null ) {
          String c = dfs( p, a, estado, pila );
          if ( c != null ) {
            return c;
          }
        }
      }
    }
    pila.remove( pila.size() - 1 );
    estado.put( id, 2 );
    return null;
  }

  /** Lee nodos con mensajes de error con la ruta ("stages[2].after"). */
  private static final class Lector {
    final Lectura l;

    Lector( Lectura l ) {
      this.l = l;
    }

    String ruta( String w, String k ) {
      return w.isEmpty() ? k : w + "." + k;
    }

    void claves( JsonNode n, String w, String... validas ) {
      Set<String> ok = new HashSet<String>( Arrays.asList( validas ) );
      java.util.Iterator<String> it = n.fieldNames();
      while ( it.hasNext() ) {
        String k = it.next();
        if ( !ok.contains( k ) ) {
          l.avisos.add( ruta( w, k ) + ": unknown key, ignored (valid: " + String.join( ", ", validas ) + ")" );
        }
      }
    }

    boolean objeto( JsonNode n, String w ) {
      if ( n == null || !n.isObject() ) {
        l.errores.add( ( w.isEmpty() ? "plan" : w ) + ": must be an object" );
        return false;
      }
      return true;
    }

    String texto( JsonNode n, String k, String w, boolean obligatorio ) {
      JsonNode v = n.get( k );
      if ( v == null || v.isNull() || v.isTextual() && v.asText().trim().isEmpty() ) {
        if ( obligatorio ) {
          l.errores.add( ruta( w, k ) + ": is required" );
        }
        return null;
      }
      if ( v.isContainerNode() ) {
        l.errores.add( ruta( w, k ) + ": must be text" );
        return null;
      }
      return v.asText().trim();
    }

    String enumOpc( JsonNode n, String k, String w, List<String> validos ) {
      String s = texto( n, k, w, false );
      if ( s == null ) {
        return null;
      }
      String x = s.toLowerCase( Locale.ROOT );
      if ( !validos.contains( x ) ) {
        l.errores.add( ruta( w, k ) + ": '" + s + "' is not valid (use " + String.join( ", ", validos ) + ")" );
        return null;
      }
      return x;
    }

    String id( JsonNode n, String w, String nombre ) {
      String s = texto( n, "id", w, false );
      if ( s == null ) {
        return nombre == null ? null : slug( nombre );
      }
      if ( !ID.matcher( s ).matches() ) {
        l.errores.add( ruta( w, "id" ) + ": '" + s + "' may only have letters, digits, '_', '.' and '-'" );
      }
      return s;
    }

    /** Lista de textos: acepta un texto solo o una lista; las comas no parten (los nombres pueden tenerlas). */
    List<String> textos( JsonNode n, String k, String w ) {
      List<String> out = new ArrayList<String>();
      JsonNode v = n.get( k );
      if ( v == null || v.isNull() ) {
        return out;
      }
      if ( v.isArray() ) {
        for ( JsonNode x : v ) {
          if ( x != null && !x.isNull() && !x.isContainerNode() && !x.asText().trim().isEmpty() ) {
            out.add( x.asText().trim() );
          } else if ( x != null && x.isContainerNode() ) {
            l.errores.add( ruta( w, k ) + ": must be a list of texts" );
          }
        }
        return out;
      }
      if ( v.isContainerNode() ) {
        l.errores.add( ruta( w, k ) + ": must be a text or a list of texts" );
        return out;
      }
      if ( !v.asText().trim().isEmpty() ) {
        out.add( v.asText().trim() );
      }
      return out;
    }

    List<JsonNode> lista( JsonNode n, String k ) {
      List<JsonNode> out = new ArrayList<JsonNode>();
      JsonNode v = n.get( k );
      if ( v == null || v.isNull() ) {
        return out;
      }
      if ( !v.isArray() ) {
        l.errores.add( k + ": must be a list" );
        return out;
      }
      for ( JsonNode x : v ) {
        out.add( x );
      }
      return out;
    }
  }

  /** "Leer ventas (CSV)" → "leer_ventas_csv": id a partir de un nombre. */
  static String slug( String nombre ) {
    String s = java.text.Normalizer.normalize( nombre == null ? "" : nombre, java.text.Normalizer.Form.NFD )
      .replaceAll( "\\p{M}", "" ).toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9]+", "_" ).replaceAll( "^_+|_+$",
        "" );
    return s.isEmpty() ? "x" : s;
  }

  /** SnakeYAML da Date, Integer, Boolean...: todo lo que no es número ni booleano pasa a texto (las fechas en ISO). */
  private static Object escalares( Object o ) {
    if ( o instanceof Map ) {
      Map<String, Object> m = new LinkedHashMap<String, Object>();
      for ( Map.Entry<?, ?> e : ( (Map<?, ?>) o ).entrySet() ) {
        m.put( String.valueOf( e.getKey() ), escalares( e.getValue() ) );
      }
      return m;
    }
    if ( o instanceof List ) {
      List<Object> l = new ArrayList<Object>();
      for ( Object x : (List<?>) o ) {
        l.add( escalares( x ) );
      }
      return l;
    }
    if ( o instanceof Date ) {
      SimpleDateFormat f = new SimpleDateFormat( "yyyy-MM-dd", Locale.ROOT );
      f.setTimeZone( java.util.TimeZone.getTimeZone( "UTC" ) );
      return f.format( (Date) o );
    }
    if ( o == null || o instanceof Number || o instanceof Boolean || o instanceof String ) {
      return o;
    }
    return String.valueOf( o );
  }

  private static String primeraLinea( String s ) {
    if ( s == null ) {
      return "";
    }
    String t = s.trim();
    int i = t.indexOf( '\n' );
    return i < 0 ? t : t.substring( 0, i ).trim() + " ...";
  }

  static ObjectMapper mapper() {
    return McpHttpServer.JSON;
  }

  // ================================================================ JSON

  /** El plano como JSON con las claves del esquema (lo que devuelve plan_get). */
  ObjectNode aJson() {
    ObjectNode o = mapper().createObjectNode();
    o.put( "plan", FORMATO );
    poner( o, "name", nombre );
    poner( o, "language", idioma );
    poner( o, "goal", objetivo );
    poner( o, "grain", grano );
    poner( o, "load", carga );
    poner( o, "incremental_by", incrementalPor );
    if ( !fuentes.isEmpty() ) {
      ArrayNode a = o.putArray( "sources" );
      for ( Fuente f : fuentes ) {
        ObjectNode x = a.addObject();
        poner( x, "id", f.id );
        poner( x, "name", f.nombre );
        poner( x, "format", f.formato );
        poner( x, "location", f.ubicacion );
        poner( x, "volume", f.volumen );
        poner( x, "frequency", f.frecuencia );
        lista( x, "key", f.clave );
        poner( x, "rejects", f.rechazos );
        poner( x, "connection", f.conexion );
        poner( x, "notes", f.notas );
      }
    }
    if ( !destinos.isEmpty() ) {
      ArrayNode a = o.putArray( "targets" );
      for ( Destino d : destinos ) {
        ObjectNode x = a.addObject();
        poner( x, "id", d.id );
        poner( x, "name", d.nombre );
        poner( x, "kind", d.clase );
        poner( x, "location", d.ubicacion );
        poner( x, "connection", d.conexion );
        lista( x, "key", d.clave );
        poner( x, "load", d.carga );
        poner( x, "notes", d.notas );
      }
    }
    if ( !reglas.isEmpty() ) {
      ArrayNode a = o.putArray( "rules" );
      for ( Regla r : reglas ) {
        ObjectNode x = a.addObject();
        poner( x, "id", r.id );
        poner( x, "text", r.texto );
      }
    }
    if ( !etapas.isEmpty() ) {
      ArrayNode a = o.putArray( "stages" );
      for ( Etapa e : etapas ) {
        ObjectNode x = a.addObject();
        poner( x, "id", e.id );
        poner( x, "name", e.nombre );
        poner( x, "kind", e.clase );
        poner( x, "transformation", e.transformacion );
        poner( x, "description", e.descripcion );
        lista( x, "after", e.despues );
        lista( x, "reads", e.lee );
        lista( x, "writes", e.escribe );
        poner( x, "lookup", e.busca );
        lista( x, "key", e.clave );
        poner( x, "on_missing", e.siFalta );
        lista( x, "rules", e.reglas );
        poner( x, "step_type", e.tipoStep );
      }
    }
    if ( orquestacion != null ) {
      ObjectNode x = o.putObject( "orchestration" );
      poner( x, "name", orquestacion.nombre );
      lista( x, "order", orquestacion.orden );
      poner( x, "on_failure", orquestacion.siFalla );
      poner( x, "notify", orquestacion.avisar );
      poner( x, "folder", orquestacion.carpeta );
    }
    if ( entorno != null ) {
      ObjectNode x = o.putObject( "environment" );
      if ( !entorno.conexiones.isEmpty() ) {
        ArrayNode a = x.putArray( "connections" );
        for ( String[] c : entorno.conexiones ) {
          ObjectNode y = a.addObject();
          poner( y, "name", c[ 0 ] );
          poner( y, "type", c[ 1 ] );
          poner( y, "host", c[ 2 ] );
        }
      }
      if ( !entorno.carpetas.isEmpty() ) {
        ArrayNode a = x.putArray( "folders" );
        for ( String[] c : entorno.carpetas ) {
          ObjectNode y = a.addObject();
          poner( y, "name", c[ 0 ] );
          poner( y, "path", c[ 1 ] );
        }
      }
      poner( x, "runner", entorno.ejecuta );
    }
    if ( !riesgos.isEmpty() ) {
      ArrayNode a = o.putArray( "risks" );
      for ( Riesgo r : riesgos ) {
        ObjectNode x = a.addObject();
        poner( x, "text", r.texto );
        poner( x, "mitigation", r.mitigacion );
      }
    }
    if ( !decisiones.isEmpty() ) {
      ArrayNode a = o.putArray( "decisions" );
      for ( Decision d : decisiones ) {
        ObjectNode x = a.addObject();
        poner( x, "text", d.texto );
        poner( x, "why", d.porque );
      }
    }
    return o;
  }

  private static void poner( ObjectNode o, String k, String v ) {
    if ( v != null && !v.trim().isEmpty() ) {
      o.put( k, v );
    }
  }

  private static void lista( ObjectNode o, String k, List<String> v ) {
    if ( v != null && !v.isEmpty() ) {
      ArrayNode a = o.putArray( k );
      for ( String s : v ) {
        a.add( s );
      }
    }
  }

  // ================================================================ YAML

  /**
   * El plano en YAML, determinista y legible: una cabecera con el esquema, las claves en el orden del esquema, listas
   * cortas en línea y textos con comillas solo cuando hacen falta.
   */
  String aYaml() {
    StringBuilder sb = new StringBuilder( 4096 );
    sb.append( "# yaml-language-server: $schema=" ).append( URL_ESQUEMA ).append( '\n' );
    sb.append( "# Plano ETL de Pentaho Live (plan_create / plan_review / plan_build / plan_check)\n" );
    YamlEscritor.objeto( sb, aJson(), 0, false );
    return sb.toString();
  }

  /** Escribe el plano en el archivo (UTF-8, sin BOM, saltos \n). */
  void guardar( File f ) throws IOException {
    File d = f.getAbsoluteFile().getParentFile();
    if ( d != null && !d.isDirectory() && !d.mkdirs() ) {
      throw new IOException( "Could not create the folder " + d );
    }
    String texto = f.getName().toLowerCase( Locale.ROOT ).endsWith( ".json" )
      ? mapper().writerWithDefaultPrettyPrinter().writeValueAsString( aJson() ) + "\n" : aYaml();
    try ( OutputStream o = new FileOutputStream( f ) ) {
      o.write( texto.getBytes( StandardCharsets.UTF_8 ) );
    }
    archivo = f.getAbsoluteFile();
  }

  /** true si el nombre es el de un plano: .plan.yaml, .plan.yml o .plan.json. */
  static boolean esNombreDePlano( String nombre ) {
    String n = nombre == null ? "" : nombre.toLowerCase( Locale.ROOT );
    return n.endsWith( ".plan.yaml" ) || n.endsWith( ".plan.yml" ) || n.endsWith( ".plan.json" );
  }

  /** Escritor de YAML para el árbol JSON del plano (objetos, listas y escalares de texto o número). */
  static final class YamlEscritor {

    private static final Set<String> RESERVADAS = new HashSet<String>( Arrays.asList( "true", "false", "yes", "no",
      "on", "off", "null", "y", "n", "~" ) );

    private YamlEscritor() {
    }

    static void objeto( StringBuilder sb, JsonNode o, int sangria, boolean enLista ) {
      boolean primero = true;
      java.util.Iterator<Map.Entry<String, JsonNode>> it = o.fields();
      while ( it.hasNext() ) {
        Map.Entry<String, JsonNode> e = it.next();
        if ( enLista && primero ) {
          // la primera clave va en la misma línea que el guion
        } else {
          espacios( sb, sangria );
        }
        primero = false;
        sb.append( e.getKey() ).append( ':' );
        valor( sb, e.getValue(), sangria, e.getKey() );
      }
    }

    private static void valor( StringBuilder sb, JsonNode v, int sangria, String clave ) {
      if ( v.isObject() ) {
        sb.append( '\n' );
        objeto( sb, v, sangria + 2, false );
      } else if ( v.isArray() ) {
        if ( enLinea( v ) ) {
          sb.append( " [" );
          for ( int i = 0; i < v.size(); i++ ) {
            sb.append( i == 0 ? "" : ", " ).append( escalar( v.get( i ), true ) );
          }
          sb.append( "]\n" );
        } else {
          sb.append( '\n' );
          for ( JsonNode x : v ) {
            espacios( sb, sangria + 2 );
            sb.append( "- " );
            if ( x.isObject() ) {
              objeto( sb, x, sangria + 4, true );
            } else {
              sb.append( escalar( x, false ) ).append( '\n' );
            }
          }
        }
      } else if ( v.isTextual() && v.asText().indexOf( '\n' ) >= 0 ) {
        sb.append( " |\n" );
        for ( String linea : v.asText().split( "\n", -1 ) ) {
          espacios( sb, sangria + 2 );
          sb.append( linea.replaceAll( "\\s+$", "" ) ).append( '\n' );
        }
      } else {
        sb.append( ' ' ).append( escalar( v, false ) ).append( '\n' );
      }
    }

    /** Listas de textos cortos van en línea: [a, b, c]. */
    private static boolean enLinea( JsonNode v ) {
      int largo = 0;
      for ( JsonNode x : v ) {
        if ( x.isContainerNode() || x.asText().indexOf( '\n' ) >= 0 ) {
          return false;
        }
        largo += x.asText().length() + 2;
      }
      return largo <= 100;
    }

    static String escalar( JsonNode v, boolean enFlujo ) {
      if ( v.isNumber() || v.isBoolean() ) {
        return v.asText();
      }
      String s = v.asText();
      if ( plano( s, enFlujo ) ) {
        return s;
      }
      return "\"" + s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ).replace( "\t", "\\t" ).replace( "\r", "" )
        .replace( "\n", "\\n" ) + "\"";
    }

    /**
     * Si el texto se puede escribir sin comillas en YAML 1.1 (el de SnakeYAML 1.7) y se vuelve a leer igual: no empieza
     * por un indicador, no tiene ": " ni " #", no parece número, fecha, booleano ni null, y en una lista en línea no
     * tiene comas ni corchetes.
     */
    static boolean plano( String s, boolean enFlujo ) {
      if ( s.isEmpty() || !s.equals( s.trim() ) ) {
        return false;
      }
      if ( "-?:,[]{}#&*!|>'\"%@`".indexOf( s.charAt( 0 ) ) >= 0 ) {
        return false;
      }
      if ( s.contains( ": " ) || s.contains( " #" ) || s.endsWith( ":" ) || s.indexOf( '\t' ) >= 0
        || s.indexOf( '\n' ) >= 0 || s.indexOf( '\r' ) >= 0 ) {
        return false;
      }
      if ( enFlujo && ( s.indexOf( ',' ) >= 0 || s.indexOf( '[' ) >= 0 || s.indexOf( ']' ) >= 0 || s.indexOf( '{' ) >= 0
        || s.indexOf( '}' ) >= 0 ) ) {
        return false;
      }
      if ( RESERVADAS.contains( s.toLowerCase( Locale.ROOT ) ) ) {
        return false;
      }
      return !s.matches( "[-+]?[0-9][0-9_.:eE+-]*" ) && !s.matches( "[-+]?0[xob][0-9a-fA-F_]+" ) && !s.matches(
        "[-+]?[.](inf|Inf|INF|nan|NaN|NAN)" ) && !s.matches( "[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}.*" );
    }

    private static void espacios( StringBuilder sb, int n ) {
      for ( int i = 0; i < n; i++ ) {
        sb.append( ' ' );
      }
    }
  }
}
