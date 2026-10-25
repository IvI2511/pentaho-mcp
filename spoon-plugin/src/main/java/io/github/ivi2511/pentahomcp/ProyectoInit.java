package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;
import io.github.ivi2511.pentahomcp.lint.Textos;

/**
 * {@code pentaho-live init} (0.7, X1): la plantilla de un proyecto de equipo. Crea SOLO lo que falta (nunca pisa un
 * archivo ni toca uno que ya existe): etl/, datasets/, tests/, contracts/ y env/ (con dev.properties de ejemplo), el
 * .gitignore (secretos, salidas de las corridas, SQLite), el .gitattributes (con las líneas del driver de diff de
 * Pentaho Live, comentadas si este plugin todavía no trae {@code pentaho-live textconv}), el perfil del linter
 * (.pentaho-live/lint.yaml, «curso», y su JSON Schema), un README.md con las convenciones y el workflow de CI
 * (.github/workflows/pentaho-live.yml: el linter con el min_score del perfil sobre etl/).
 * <p>
 * La usan {@link ProyectoMain} (línea de comandos), la herramienta MCP project_init ({@link LintTools}) y el diálogo
 * «Preparar proyecto…» del menú IDC ({@link IdcProyecto}). Sin Spoon ni Kettle. Las plantillas están en
 * proyecto/ (recursos del jar), en en/es/pt las que tienen texto para leer.
 */
final class ProyectoInit {

  static final String RECURSOS = "io/github/ivi2511/pentahomcp/proyecto/";
  static final String CARPETAS = "etl,datasets,tests,contracts,env";
  /**
   * La clase de los comandos de git de Pentaho Live (normalize, diff, textconv: E2). Si está en el plugin, las líneas del
   * driver de diff van activas en .gitattributes; si no, comentadas.
   */
  static final String CLASE_TEXTCONV = "io.github.ivi2511.pentahomcp.diff.LineaComandos";
  static final String DRIVER_DIFF = "kettle";

  static final String CREADO = "created";
  static final String EXISTE = "exists";
  static final String SE_CREARIA = "would_create";

  /** Una cosa del proyecto y lo que pasó con ella. */
  static final class Item {
    /** Relativa a la carpeta del proyecto, con «/»; las carpetas terminan en «/». */
    final String ruta;
    final boolean carpeta;
    String estado;

    Item( String ruta, boolean carpeta ) {
      this.ruta = ruta;
      this.carpeta = carpeta;
    }
  }

  /** Lo que hizo (o haría, con ensayo) init. */
  static final class Hecho {
    File raiz;
    boolean ensayo;
    boolean textconv;
    final List<Item> items = new ArrayList<Item>();

    int cuantos( String estado ) {
      int n = 0;
      for ( Item i : items ) {
        n += estado.equals( i.estado ) ? 1 : 0;
      }
      return n;
    }

    ObjectNode json() {
      ObjectNode o = JsonNodeFactory.instance.objectNode();
      o.put( "folder", raiz.getAbsolutePath() ).put( "dry_run", ensayo ).put( "diff_driver", textconv ? "active"
        : "commented (this Pentaho Live has no textconv command yet)" );
      ArrayNode c = o.putArray( ensayo ? "would_create" : "created" );
      ArrayNode e = o.putArray( "existing" );
      for ( Item i : items ) {
        ( EXISTE.equals( i.estado ) ? e : c ).add( i.ruta );
      }
      return o;
    }
  }

  private ProyectoInit() {
  }

  /**
   * Prepara el proyecto en raiz (que se crea si no existe y su carpeta padre sí). nombre: el del perfil y el título del
   * README (null = el de la carpeta). idioma: en/es/pt de los textos. ensayo: solo dice qué crearía.
   */
  static Hecho crear( File raiz, String nombre, String idioma, boolean ensayo ) throws IOException {
    File r = raiz.getAbsoluteFile();
    if ( r.exists() && !r.isDirectory() ) {
      throw new IOException( "Not a folder: " + r );
    }
    if ( !r.exists() && ( r.getParentFile() == null || !r.getParentFile().isDirectory() ) ) {
      throw new IOException( "The parent folder does not exist: " + r.getParent() );
    }
    String lang = Textos.normalizar( idioma );
    String n = nombre == null || nombre.trim().isEmpty() ? nombreDe( r ) : nombre.trim();
    Hecho h = new Hecho();
    h.raiz = r;
    h.ensayo = ensayo;
    h.textconv = hayTextconv();
    if ( !ensayo && !r.exists() && !r.mkdir() ) {
      throw new IOException( "Cannot create " + r );
    }
    for ( String c : CARPETAS.split( "," ) ) {
      boolean nueva = carpeta( h, c );
      if ( nueva && !c.equals( "env" ) ) {
        // git no guarda carpetas vacías
        archivo( h, c + "/.gitkeep", "" );
      }
    }
    archivo( h, "env/dev.properties", plantilla( "dev_" + lang + ".properties" ) );
    archivo( h, ".gitignore", plantilla( "gitignore.txt" ) );
    archivo( h, ".gitattributes", plantilla( "gitattributes.txt" ).replace( "{{DIFF_LINES}}", lineasDiff( h.textconv ) ) );
    carpeta( h, PerfilEquipo.CARPETA );
    archivo( h, PerfilEquipo.CARPETA + "/lint.yaml", perfil( n, lang ) );
    archivo( h, PerfilEquipo.CARPETA + "/lint.schema.json", plantilla( "lint.schema.json" ) );
    archivo( h, "README.md", plantilla( "README_" + lang + ".md" ).replace( "{{NAME}}", n ).replace( "{{VERSION}}",
      version() ) );
    carpeta( h, ".github" );
    carpeta( h, ".github/workflows" );
    archivo( h, ".github/workflows/pentaho-live.yml", plantilla( "pentaho-live.yml" ).replace( "{{VERSION}}",
      version() ) );
    return h;
  }

  /** El perfil «curso» por defecto, con el nombre como texto YAML entre comillas. */
  static String perfil( String nombre, String idioma ) throws IOException {
    String esc = nombre.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ).replace( "\r", " " ).replace( "\n", " " );
    return plantilla( "lint_" + Textos.normalizar( idioma ) + ".yaml" ).replace( "{{NAME}}", "\"" + esc + "\"" );
  }

  static String lineasDiff( boolean activas ) {
    String p = activas ? "" : "# ";
    return p + "*.ktr diff=" + DRIVER_DIFF + "\n" + p + "*.kjb diff=" + DRIVER_DIFF;
  }

  static boolean hayTextconv() {
    try {
      Class.forName( CLASE_TEXTCONV, false, ProyectoInit.class.getClassLoader() );
      return true;
    } catch ( Throwable t ) {
      return false;
    }
  }

  /** El nombre de la carpeta, o «Proyecto ETL» si no tiene (la raíz de un disco). */
  static String nombreDe( File r ) {
    String n = r.getName();
    return n == null || n.trim().isEmpty() ? "ETL" : n.trim();
  }

  // ---------------------------------------------------------------- crear sin pisar

  private static boolean carpeta( Hecho h, String ruta ) throws IOException {
    Item i = new Item( ruta + "/", true );
    h.items.add( i );
    File f = new File( h.raiz, ruta );
    if ( f.isDirectory() ) {
      i.estado = EXISTE;
      return false;
    }
    if ( f.exists() ) {
      throw new IOException( "Exists and is not a folder: " + f );
    }
    if ( h.ensayo ) {
      i.estado = SE_CREARIA;
      return true;
    }
    if ( !f.mkdir() ) {
      throw new IOException( "Cannot create " + f );
    }
    i.estado = CREADO;
    return true;
  }

  /** Crea el archivo solo si no existe (CREATE_NEW: ni siquiera una carrera con otro proceso lo pisa). */
  private static void archivo( Hecho h, String ruta, String contenido ) throws IOException {
    Item i = new Item( ruta, false );
    h.items.add( i );
    File f = new File( h.raiz, ruta );
    if ( f.exists() ) {
      i.estado = EXISTE;
      return;
    }
    if ( h.ensayo ) {
      i.estado = SE_CREARIA;
      return;
    }
    try {
      Files.write( f.toPath(), contenido.getBytes( StandardCharsets.UTF_8 ), StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE );
      i.estado = CREADO;
    } catch ( FileAlreadyExistsException e ) {
      i.estado = EXISTE;
    }
  }

  static String plantilla( String nombre ) throws IOException {
    ClassLoader cl = ProyectoInit.class.getClassLoader();
    try ( InputStream in = cl == null ? null : cl.getResourceAsStream( RECURSOS + nombre ) ) {
      if ( in == null ) {
        throw new IOException( "Missing template " + RECURSOS + nombre + " in the plugin" );
      }
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] buf = new byte[ 8192 ];
      int k;
      while ( ( k = in.read( buf ) ) > 0 ) {
        bo.write( buf, 0, k );
      }
      return new String( bo.toByteArray(), StandardCharsets.UTF_8 ).replace( "\r\n", "\n" );
    }
  }

  /** Líneas para la consola: «+ etl/» creado, «= .gitignore» ya estaba, «? README.md» se crearía. */
  static String texto( Hecho h ) {
    StringBuilder b = new StringBuilder();
    for ( Item i : h.items ) {
      String marca = CREADO.equals( i.estado ) ? "+ " : SE_CREARIA.equals( i.estado ) ? "? " : "= ";
      b.append( marca ).append( i.ruta ).append( '\n' );
    }
    return b.toString();
  }

  /**
   * La versión del plugin (pentaho-mcp-version.txt, como McpLifecycleListener.VERSION, sin cargar esa clase: aquí no
   * hay Spoon ni SWT).
   */
  static String version() {
    try ( InputStream in = ProyectoInit.class.getResourceAsStream( "pentaho-mcp-version.txt" ) ) {
      if ( in != null ) {
        String v = new String( leer( in ), StandardCharsets.UTF_8 ).replace( "﻿", "" ).trim();
        if ( !v.isEmpty() ) {
          return v;
        }
      }
    } catch ( IOException e ) {
      // sin versión: la de respaldo
    }
    return "0.0.0";
  }

  private static byte[] leer( InputStream in ) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[ 8192 ];
    int k;
    while ( ( k = in.read( buf ) ) > 0 ) {
      bo.write( buf, 0, k );
    }
    return bo.toByteArray();
  }
}
