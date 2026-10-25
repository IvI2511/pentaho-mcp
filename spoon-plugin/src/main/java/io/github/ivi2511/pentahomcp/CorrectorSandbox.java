package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.resource.ResourceEntry;
import org.pentaho.di.resource.ResourceReference;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepIOMetaInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.errorhandling.StreamInterface;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.CorrectorPolitica.Entrega;
import io.github.ivi2511.pentahomcp.CorrectorPolitica.Hallazgo;
import io.github.ivi2511.pentahomcp.CorrectorTextos.Mensaje;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Rutas;

/**
 * El sandbox de un caso (docs/corrector-diseno.md §3 paso 3): una carpeta con {@code datos/} (copia de solo lectura
 * de las entradas del caso), {@code salida/}, {@code db/} (copia de la plantilla SQLite del pack), {@code tmp/} y la
 * copia reescrita del .ktr, cuyo {@code ${Internal.Entry.Current.Directory}} es la carpeta del caso:
 * <ul>
 * <li><b>Entradas</b>: toda hoja de texto de un {@code <step>} o de un {@code <default_value>} cuyo valor, con
 * {@code \} → {@code /}, es un archivo del pack o termina en {@code /<archivo>} (sin mayusculas) pasa a
 * {@code ${Internal.Entry.Current.Directory}/datos/<archivo>}, sin conocer el tipo de step. No se tocan el
 * {@code name}, {@code description} y {@code type} del step, las hojas de los subarboles de datos
 * ({@link #NO_SE_TOCAN}: campos, filas de un Data grid, condiciones...) ni los valores que son el nombre de un step
 * (P14). Los temporales en {@code %%java.io.tmpdir%%} pasan a {@code tmp/} del caso.</li>
 * <li><b>Temporales</b> ({@link #TEMPORALES}): la carpeta de temporales de Sort rows, Join Rows, Group by y Blocking
 * step (la que sea: {@code %%java.io.tmpdir%%}, una del alumno o vacia) pasa a la ruta <b>local</b> de {@code tmp/}
 * del caso, que existe, cuenta en {@code max_output_mb} y se borra con el sandbox. No puede ser
 * {@code ${Internal.Entry.Current.Directory}/tmp}: al correr esa variable es una URL ({@code file:///C:/...}) y Join
 * Rows y Group by crean sus temporales con {@code java.io.File} («Error creating temporary file(s)», 0.7.0).</li>
 * <li><b>Salidas a archivo</b> ({@link #SALIDAS}): {@code file/name} → {@code ${...}/salida/<nombre base>}; si dos
 * chocan, sufijo {@code _<step>}. La que toma el nombre de un campo bloquea ({@code unexpected_output}).</li>
 * <li><b>Conexiones</b>: con base en el pack, toda conexion SQLite usada pasa a {@code db/<plantilla>} (ruta absoluta,
 * acceso nativo, sin opciones extra ni pool); con bases de prueba (formato 2), cada conexion usada pasa a la base de
 * prueba que le corresponde ({@link CorrectorBases#emparejar}: por nombre, por base o por tipo y servidor), con el
 * servidor, el usuario y las opciones del pack y la contraseña vacia (el motor la pone en memoria); los esquemas de
 * los steps (Table output...) que nombran la base original pasan a la de prueba y el SQL de los steps no puede salir
 * de las bases de prueba ({@code foreign_schema}, {@code forbidden_sql}). Cualquier otra conexion usada bloquea
 * ({@code foreign_connection}); las que no usa nadie se sacan con un aviso (P15).</li>
 * <li><b>Entradas inesperadas</b>: toda ruta de un step (hojas con tag de archivo, la heuristica de
 * {@code absolute_path} del linter, y getResourceDependencies) que, con las variables resueltas, cae fuera de la
 * carpeta del caso y del tmp de la JVM, o usa un esquema VFS que no es {@code file:}, bloquea
 * ({@code unexpected_input}): asi nadie lee el {@code esperado/} del docente ni el sandbox de un companero.</li>
 * <li><b>Chequeo</b>: la copia se vuelve a cargar y tiene que tener los mismos steps, hops y referencias entre steps
 * que la entrega ({@code rewrite_failed} si no).</li>
 * </ul>
 * La copia sale del XML que Kettle regenera de lo que cargo ({@link Entrega#kettle}), no del texto del alumno: asi lo
 * que se revisa es lo que corre (un comentario, un tag repetido o en mayusculas no esconden nada). El archivo del
 * alumno no se toca. Corre en la JVM hija despues de {@link CorrectorPolitica#revisar}: la politica de steps no se
 * repite aca, salvo las rutas que salen de un campo. Escribe con un stream propio (P12). {@code datos/} queda de
 * solo lectura: se borra con {@link #borrar}, no con un borrado recursivo comun.
 *
 * @dueño C3
 */
final class CorrectorSandbox {

  static final String DATOS = "datos";
  static final String SALIDA = "salida";
  static final String DB = "db";
  static final String TMP = "tmp";
  /** Marca de una carpeta de caso: {@link #preparar} solo vacia carpetas que la tienen. */
  static final String MARCA = ".corrector-sandbox";
  static final String DIR_ACTUAL = "${Internal.Entry.Current.Directory}";

  /** Salidas a archivo cuyo {@code file/name} se redirige a salida/ (ids de plugin, sin mayusculas). */
  static final Set<String> SALIDAS = conjunto( "textfileoutput", "exceloutput", "typeexitexcelwriterstep",
    "jsonoutput", "xmloutput" );
  /** Steps con carpeta de temporales ({@code <directory>}): van siempre a tmp/ del caso (ids de plugin). */
  static final Set<String> TEMPORALES = conjunto( "sortrows", "joinrows", "groupby", "blockingstep" );
  /** Subarboles de un step que son datos, no rutas: ni se reescriben ni se revisan (§3, excepciones). */
  static final Set<String> NO_SE_TOCAN = conjunto( "fields", "data", "compare", "condition", "cases", "calculation",
    "group", "lookup", "attributes", "GUI", "partitioning", "remotesteps" );
  /** Hijos directos del step que nunca son rutas. */
  static final Set<String> HOJAS_PROPIAS = conjunto( "name", "description", "type", "copies", "distribute",
    "custom_distribution", "cluster_schema" );
  /**
   * Atributos que conserva una conexion SQLite al apuntarla a la base del pack (sin pool, extras, cluster ni SQL al
   * conectar: Kettle sustituye variables en ese SQL antes de correrlo, y la copia de analisis tampoco lo lleva).
   */
  static final Set<String> ATRIBUTOS_SQLITE = conjunto( "FORCE_IDENTIFIERS_TO_LOWERCASE",
    "FORCE_IDENTIFIERS_TO_UPPERCASE", "PRESERVE_RESERVED_WORD_CASE", "QUOTE_ALL_FIELDS", "SUPPORTS_BOOLEAN_DATA_TYPE",
    "SUPPORTS_TIMESTAMP_DATA_TYPE", "PORT_NUMBER" );

  /** Orden en que se elige el motivo cuando el sandbox encuentra varios problemas. */
  static final List<String> PRIORIDAD = Collections.unmodifiableList( Arrays.asList(
    ResultadoCaso.REESCRITURA_FALLIDA, ResultadoCaso.SALIDA_INESPERADA, ResultadoCaso.CONEXION_AJENA,
    ResultadoCaso.ENTRADA_INESPERADA, ResultadoCaso.SQL_PROHIBIDO ) );

  private static final Pattern ESQUEMA = Pattern.compile( "^([A-Za-z][A-Za-z0-9+.-]+):" );
  private static final Pattern PORCIENTO = Pattern.compile( "%([0-9A-Fa-f]{2})" );
  private static final Pattern NO_VALE_EN_NOMBRE = Pattern.compile( "[\\\\/:*?\"<>|\\x00-\\x1f]" );
  /** La extension que Kettle agrega al nombre de una salida: sin separadores ni puntos. */
  private static final Pattern EXTENSION_SEGURA = Pattern.compile( "[A-Za-z0-9_-]{1,16}" );
  /** Un separador en lo que Kettle agrega al nombre de una salida (fecha con formato, id de particion). */
  private static final Pattern SEPARADOR = Pattern.compile( "[\\\\/]" );

  private CorrectorSandbox() {
  }

  /** Lo que el worker puede ajustar. */
  static final class Opciones {

    /**
     * Lo que vale {@code java.io.tmpdir} al correr (C4 lo pone en el tmp del worker): ahi tambien se puede leer y
     * escribir. null = la carpeta tmp/ del caso.
     */
    File dirTmpJvm;
    /** Las bases de prueba de esta JVM (con el sufijo del worker); null = las del pack sin sufijo. */
    CorrectorBases.Sesion bases;
  }

  /** Una salida a archivo redirigida: C4 la usa para la captura {@code file}. */
  static final class SalidaRedirigida {

    String step;
    /** Id de plugin (TextFileOutput...). */
    String tipo;
    /** El {@code file/name} de la entrega. */
    String original;
    /** El nuevo {@code file/name}: {@code ${Internal.Entry.Current.Directory}/salida/<base>}. */
    String valor;
    /** "salida/&lt;base&gt;", sin la extension (Kettle la agrega con buildFilename). */
    String relativa;
    /** El {@code file/extention} del step ("csv"), o "". */
    String extension;
  }

  /** El sandbox de un caso listo para correr, o bloqueado. */
  static final class Preparacion {

    File dirCaso;
    /** La copia reescrita del .ktr (en la raiz de la carpeta del caso). */
    File ktr;
    File datos;
    File salida;
    File db;
    File tmp;
    /** La copia de la plantilla SQLite del pack en db/, o null. */
    File baseDatos;
    /** "input 'Leer ventas': C:\...\ventas.csv -> datos/ventas.csv" y las demas (§6, {@code redirections}). */
    final List<String> redirecciones = new ArrayList<String>();
    /** Iguales en todos los casos de una entrega: van una sola vez en el resultado ({@link #aplicarAvisos}). */
    final List<Aviso> avisos = new ArrayList<Aviso>();
    final List<SalidaRedirigida> salidas = new ArrayList<SalidaRedirigida>();
    /** Parametros del pack que la entrega declara, listos para setParameterValue (con las entradas redirigidas). */
    final Map<String, String> parametros = new LinkedHashMap<String, String>();
    /** {@link ResultadoCaso#MOTIVOS}, o null si puede correr. */
    String motivo;
    /** Para el docente, en ingles. */
    String detalle;
    /** El mismo detalle, para traducirlo. */
    Mensaje mensaje;
    /** El step del problema, si hay uno. */
    String step;
    /** Conexion de la copia (nombre) → la base de prueba a la que apunta: el motor le pone la contraseña. */
    final Map<String, CorrectorBases.Destino> conexionesPrueba = new LinkedHashMap<String, CorrectorBases.Destino>();

    boolean bloqueada() {
      return motivo != null;
    }

    /** Redirecciones y, si esta bloqueada, el estado del caso. */
    void aplicar( ResultadoCaso c ) {
      if ( c.redirecciones == null ) {
        c.redirecciones = new ArrayList<String>();
      }
      c.redirecciones.addAll( redirecciones );
      if ( bloqueada() ) {
        if ( c.corrida == null ) {
          c.corrida = new ResultadoCaso.Corrida();
        }
        c.corrida.estado = ResultadoCaso.BLOQUEADO;
        c.corrida.motivo = motivo;
        if ( mensaje != null ) {
          mensaje.en( c.corrida );
        } else {
          c.corrida.detalle = detalle;
        }
      }
    }

    /** Los avisos en {@code warnings} de la entrega, sin repetir (codigo + detalle). */
    void aplicarAvisos( ResultadoCorreccion r ) {
      for ( Aviso a : avisos ) {
        boolean esta = false;
        for ( Aviso b : r.avisos ) {
          esta |= b != null && eq( a.codigo, b.codigo ) && eq( a.detalle, b.detalle );
        }
        if ( !esta ) {
          r.avisos.add( a );
        }
      }
    }
  }

  // ------------------------------------------------------------------ preparar

  /**
   * Arma el sandbox de un caso en {@code dirCaso} (que no existe, esta vacia o es un sandbox anterior: tiene
   * {@link #MARCA}) y reescribe la entrega. No ejecuta nada. Si {@link Preparacion#bloqueada()}, el caso no corre.
   *
   * @param dirPack carpeta del pack (datos/, plantilla/); puede ser null si las entradas vienen en {@code inline}
   * @throws IOException si el pack no tiene lo que nombra (plantilla) o la carpeta no es un sandbox
   */
  static Preparacion preparar( Pack pack, File dirPack, Caso caso, Entrega e, File dirCaso, Opciones op )
    throws Exception {
    Preparacion p = new Preparacion();
    p.dirCaso = dirCaso.getAbsoluteFile();
    limpiar( p.dirCaso );
    p.datos = carpeta( p.dirCaso, DATOS );
    p.salida = carpeta( p.dirCaso, SALIDA );
    p.db = carpeta( p.dirCaso, DB );
    p.tmp = carpeta( p.dirCaso, TMP );
    Files.write( new File( p.dirCaso, MARCA ).toPath(), new byte[ 0 ] );
    copiarEntradas( pack, dirPack, caso, p.datos );
    p.baseDatos = copiarPlantilla( pack, dirPack, p.db );
    File tmpJvm = op != null && op.dirTmpJvm != null ? op.dirTmpJvm.getAbsoluteFile() : p.tmp;

    List<Hallazgo> problemas = new ArrayList<Hallazgo>();
    CorrectorBases.Sesion bases = op != null && op.bases != null ? op.bases : pack.bases == null || pack.bases.isEmpty()
      ? null : new CorrectorBases.Sesion( pack, dirPack, "", null );
    List<String> entradas = pack.entradas == null ? Collections.<String>emptyList() : pack.entradas;
    Element doc = (Element) e.kettle.cloneNode( true ); // lo que Kettle entendio (ver Entrega)
    String so = CorrectorPolitica.objetosCompartidos( e.crudo );
    if ( !so.isEmpty() ) {
      p.redirecciones.add( "shared objects file: " + so + " -> (ignored)" );
    }
    Set<String> nombresSteps = new HashSet<String>();
    for ( Element s : Nodos.hijos( doc, "step" ) ) {
      nombresSteps.add( Nodos.crudo( s, "name" ).trim().toLowerCase( Locale.ROOT ) );
    }

    // parametros: los default que nombran un archivo del pack
    Map<String, String> declarados = new LinkedHashMap<String, String>();
    for ( Element par : Nodos.hijos( Nodos.hijo( Nodos.hijo( doc, "info" ), "parameters" ), "parameter" ) ) {
      String nombre = Nodos.txt( par, "name" );
      Element def = Nodos.hijo( par, "default_value" );
      String valor = def == null ? "" : def.getTextContent().trim();
      String nuevo = redirigirEntrada( valor, entradas );
      if ( nuevo != null && !nuevo.equals( valor ) ) {
        def.setTextContent( nuevo );
        p.redirecciones.add( "parameter " + q( nombre ) + ": " + valor + " -> " + relativa( nuevo ) );
        valor = nuevo;
      }
      if ( !nombre.isEmpty() ) {
        declarados.put( nombre, valor );
      }
    }
    if ( pack.parametros != null ) {
      for ( Map.Entry<String, String> par : pack.parametros.entrySet() ) {
        String v = par.getValue() == null ? "" : par.getValue();
        String nuevo = redirigirEntrada( v, entradas );
        if ( declarados.containsKey( par.getKey() ) ) {
          p.parametros.put( par.getKey(), nuevo == null ? v : nuevo );
        } else {
          p.avisos.add( new Aviso( Aviso.PARAMETRO_NO_DECLARADO, "the pack sets parameter " + q( par.getKey() )
            + " but the transformation does not declare it" ) );
        }
      }
    }
    Variables vars = variables( p, doc, declarados, tmpJvm );
    sqlSustituido( doc, vars, p, problemas );
    particiones( doc, p );

    // steps: salidas, rutas desde un campo y entradas
    Set<Element> hechos = new HashSet<Element>();
    Set<String> archivosSalida = new HashSet<String>();
    for ( Element s : Nodos.hijos( doc, "step" ) ) {
      String nombre = Nodos.crudo( s, "name" );
      String tipo = Nodos.txt( s, "type" );
      Hallazgo dc = CorrectorPolitica.desdeCampo( s, e.tm );
      if ( dc != null ) {
        problemas.add( dc );
      }
      if ( SALIDAS.contains( CorrectorPolitica.canonico( tipo ).toLowerCase( Locale.ROOT ) ) ) {
        redirigirSalida( s, nombre, tipo, vars, archivosSalida, hechos, p );
      }
      if ( TEMPORALES.contains( CorrectorPolitica.canonico( tipo ).toLowerCase( Locale.ROOT ) ) ) {
        Element d = Nodos.hijo( s, "directory" );
        if ( d != null ) {
          d.setTextContent( local( p.tmp ) );
          hechos.add( d );
        }
      }
      reescribirHojas( s, s, nombre, entradas, nombresSteps, hechos, p );
    }

    // conexiones, y con bases de prueba los esquemas y el SQL de los steps
    conexiones( doc, e, p, problemas, bases );
    if ( bases != null ) {
      esquemasYSql( doc, vars, p, problemas, bases );
    }

    // la copia
    p.ktr = new File( p.dirCaso, e.archivo == null ? "entrega.ktr" : e.archivo.getName() );
    escribir( doc, p.ktr );
    TransMeta tm2 = null;
    try {
      tm2 = new TransMeta( p.ktr.getAbsolutePath() );
      Mensaje dif = diferenciasMensaje( e.tm, tm2 );
      if ( dif != null ) {
        problemas.add( new Hallazgo( ResultadoCaso.REESCRITURA_FALLIDA, dif, null ) );
      }
    } catch ( Throwable t ) {
      problemas.add( new Hallazgo( ResultadoCaso.REESCRITURA_FALLIDA, new Mensaje( "det.rewrite.load", primeraLinea(
        t ) ), null ) );
    }

    // rutas fuera del sandbox
    List<Path> raices = new ArrayList<Path>();
    raices.add( canonica( p.dirCaso ) );
    raices.add( canonica( tmpJvm ) );
    for ( Element s : Nodos.hijos( doc, "step" ) ) {
      revisarRutas( s, vars, raices, problemas );
    }
    if ( problemas.isEmpty() && tm2 != null ) { // solo si todo lo demas paso: ninguna URL llega a VFS
      dependencias( tm2, p, vars, raices, problemas );
    }

    ordenarNotas( p.redirecciones );
    for ( String m : PRIORIDAD ) {
      for ( Hallazgo pr : problemas ) {
        if ( m.equals( pr.motivo ) && p.motivo == null ) {
          poner( p, pr );
        }
      }
    }
    for ( Hallazgo pr : problemas ) { // un motivo que no esta en PRIORIDAD
      if ( p.motivo == null ) {
        poner( p, pr );
      }
    }
    return p;
  }

  private static void poner( Preparacion p, Hallazgo h ) {
    p.motivo = h.motivo;
    p.mensaje = h.mensaje;
    p.detalle = h.detalle();
    p.step = h.step;
  }

  // ------------------------------------------------------------------ reescritura

  /**
   * El valor redirigido a {@code ${Internal.Entry.Current.Directory}/datos/<archivo>} si es un archivo del pack o
   * termina en {@code /<archivo>} (con {@code \} → {@code /}, sin mayusculas); null si no.
   */
  static String redirigirEntrada( String valor, List<String> entradas ) {
    if ( valor == null || entradas == null ) {
      return null;
    }
    String v = valor.trim().replace( '\\', '/' ).toLowerCase( Locale.ROOT );
    if ( v.isEmpty() ) {
      return null;
    }
    for ( String in : entradas ) {
      String a = in == null ? "" : in.trim().replace( '\\', '/' );
      while ( a.startsWith( "/" ) ) {
        a = a.substring( 1 );
      }
      if ( a.isEmpty() ) {
        continue;
      }
      String al = a.toLowerCase( Locale.ROOT );
      if ( v.equals( al ) || v.endsWith( "/" + al ) ) {
        return DIR_ACTUAL + "/" + DATOS + "/" + a;
      }
    }
    return null;
  }

  /** Las hojas del step (sin los subarboles de datos): temporales a tmp/ y archivos del pack a datos/. */
  private static void reescribirHojas( Element step, Element actual, String nombreStep, List<String> entradas,
    Set<String> nombresSteps, Set<Element> hechos, Preparacion p ) {
    for ( Element h : Nodos.hijos( actual, null ) ) {
      String tag = h.getTagName();
      if ( actual == step && HOJAS_PROPIAS.contains( tag.toLowerCase( Locale.ROOT ) )
        || NO_SE_TOCAN.contains( tag.toLowerCase( Locale.ROOT ) ) || hechos.contains( h ) ) {
        continue;
      }
      if ( !Nodos.esHoja( h ) ) {
        reescribirHojas( step, h, nombreStep, entradas, nombresSteps, hechos, p );
        continue;
      }
      String t = h.getTextContent().trim();
      if ( t.isEmpty() || tag.equals( "connection" )
        || !tagDeRuta( h ) && nombresSteps.contains( t.toLowerCase( Locale.ROOT ) ) ) {
        continue; // un valor que es el nombre de un step es una referencia a el (send_true_to, lookup...): P14
      }
      if ( t.equalsIgnoreCase( "%%java.io.tmpdir%%" ) || t.equalsIgnoreCase( "${java.io.tmpdir}" ) ) {
        h.setTextContent( local( p.tmp ) ); // cuentan en max_output_mb; ruta local, no la URL del directorio actual
        continue;
      }
      String nuevo = redirigirEntrada( t, entradas );
      if ( nuevo != null && !nuevo.equals( t ) ) {
        h.setTextContent( nuevo );
        p.redirecciones.add( "input " + q( nombreStep ) + ": " + t + " -> " + relativa( nuevo ) );
      }
    }
  }

  /** file/name de una salida a archivo → ${...}/salida/&lt;nombre base&gt; (con sufijo _&lt;step&gt; si choca). */
  private static void redirigirSalida( Element s, String nombre, String tipo, VariableSpace vars,
    Set<String> usados, Set<Element> hechos, Preparacion p ) {
    Element file = Nodos.hijo( s, "file" );
    Element n = Nodos.hijo( file, "name" );
    if ( n == null ) {
      return;
    }
    hechos.add( n );
    String original = n.getTextContent().trim();
    if ( original.isEmpty() ) {
      return; // JSON output sin archivo (outputvalue), o un step que igual falla al iniciar
    }
    String ext = extensionSegura( file, nombre, vars, p );
    fechaSegura( file, nombre, p );
    String base = limpio( nombreBase( vars.environmentSubstitute( original ) ) );
    if ( base.isEmpty() || base.contains( "${" ) || base.contains( "%%" ) ) {
      base = limpio( nombre );
    }
    if ( base.isEmpty() ) {
      base = "salida";
    }
    String elegido = base;
    for ( int i = 1; !usados.add( clave( elegido, ext ) ); i++ ) {
      elegido = base + "_" + limpio( nombre ) + ( i > 1 ? "_" + i : "" );
    }
    String nuevo = DIR_ACTUAL + "/" + SALIDA + "/" + elegido;
    n.setTextContent( nuevo );
    SalidaRedirigida sr = new SalidaRedirigida();
    sr.step = nombre;
    sr.tipo = tipo;
    sr.original = original;
    sr.valor = nuevo;
    sr.relativa = SALIDA + "/" + elegido;
    sr.extension = ext;
    p.salidas.add( sr );
    if ( !nuevo.equals( original ) ) {
      p.redirecciones.add( "output " + q( nombre ) + ": " + original + " -> " + sr.relativa
        + ( ext.isEmpty() ? "" : "." + ext ) );
    }
  }

  /**
   * El {@code file/extention} de una salida redirigida, con las variables ya sustituidas y fijo en la copia. Kettle
   * lo agrega al nombre al correr ({@code buildFilename}): con un separador («csv/../../x») escribiria fuera de
   * salida/. Si no es solo letras, numeros, _ o -, la salida queda sin extension.
   */
  private static String extensionSegura( Element file, String nombre, VariableSpace vars, Preparacion p ) {
    Element x = Nodos.hijo( file, "extention" );
    if ( x == null ) {
      return "";
    }
    String crudo = x.getTextContent().trim();
    String ext = sustituir( vars, crudo ).trim();
    if ( !ext.isEmpty() && !EXTENSION_SEGURA.matcher( ext ).matches() ) {
      p.redirecciones.add( "output " + q( nombre ) + ": extension " + crudo + " -> (none)" );
      ext = "";
    }
    if ( !ext.equals( x.getTextContent() ) ) {
      x.setTextContent( ext );
    }
    return ext;
  }

  /**
   * El {@code file/date_time_format} de una salida: Kettle agrega al nombre la fecha con ese formato y el texto entre
   * comillas sale tal cual («'/../../x'»). Si la fecha formateada tiene un separador, la copia queda sin formato
   * (Kettle usa entonces add_date/add_time, que solo agregan digitos).
   */
  private static void fechaSegura( Element file, String nombre, Preparacion p ) {
    Element f = Nodos.hijo( file, "date_time_format" );
    String formato = f == null ? "" : f.getTextContent();
    if ( formato.trim().isEmpty() ) {
      return;
    }
    String hoy;
    try {
      hoy = new SimpleDateFormat( formato ).format( new Date() );
    } catch ( IllegalArgumentException ex ) {
      hoy = formato; // Kettle tampoco lo puede aplicar: se mira el texto
    }
    if ( SEPARADOR.matcher( hoy ).find() ) {
      f.setTextContent( "" );
      p.redirecciones.add( "output " + q( nombre ) + ": date_time_format " + formato.trim() + " -> (none)" );
    }
  }

  /**
   * Los ids de particion: Kettle los agrega al nombre de una salida con «partition number in filename». Un id con
   * separador pasa a su version {@link #limpio}.
   */
  private static void particiones( Element doc, Preparacion p ) {
    for ( Element ps : Nodos.hijos( Nodos.hijo( Nodos.hijo( doc, "info" ), "partitionschemas" ),
      "partitionschema" ) ) {
      for ( Element par : Nodos.hijos( ps, "partition" ) ) {
        Element id = Nodos.hijo( par, "id" );
        String v = id == null ? "" : id.getTextContent();
        if ( SEPARADOR.matcher( v ).find() ) {
          String nuevo = limpio( v );
          id.setTextContent( nuevo.isEmpty() ? "P" : nuevo );
          p.redirecciones.add( "partition " + q( Nodos.txt( ps, "name" ) ) + ": " + v.trim() + " -> "
            + id.getTextContent() );
        }
      }
    }
  }

  /**
   * El SQL prohibido (CorrectorPolitica.sqlProhibido) que solo aparece al sustituir variables: Kettle las sustituye
   * en el SQL de Table input y Execute SQL antes de correrlo, y un «ATT» en el default de un parametro mas
   * «${X}ACH DATABASE» en el SQL no esta en ninguna hoja. Solo si la entrega usa la base del pack.
   */
  private static void sqlSustituido( Element doc, VariableSpace vars, Preparacion p, List<Hallazgo> problemas ) {
    if ( p.baseDatos == null ) {
      return;
    }
    Set<Element> yaVistos = new HashSet<Element>();
    for ( Element[] hp : Nodos.hojas( doc ) ) {
      String t = hp[ 0 ].getTextContent();
      String sust = sustituir( vars, t );
      if ( sust.equals( t ) || CorrectorPolitica.sqlProhibido( t ) != null ) {
        continue; // sin variables, o ya lo bloqueo la politica
      }
      String prohibido = CorrectorPolitica.sqlProhibido( sust );
      if ( prohibido == null ) {
        continue;
      }
      Element dueno = CorrectorPolitica.hijoDeRaiz( doc, hp[ 0 ] );
      if ( dueno != null && !yaVistos.add( dueno ) ) {
        continue;
      }
      String step = dueno != null && "step".equals( dueno.getTagName() ) ? Nodos.crudo( dueno, "name" ) : null;
      Mensaje donde = step != null ? new Mensaje( "det.where.step_only", step )
        : dueno != null && "connection".equals( dueno.getTagName() ) ? new Mensaje( "det.where.connection", Nodos.txt(
          dueno, "name" ) ) : new Mensaje( "det.where.tag", hp[ 0 ].getTagName() );
      problemas.add( new Hallazgo( ResultadoCaso.SQL_PROHIBIDO, new Mensaje( "det.forbidden_sql.vars",
        donde.anidado(), prohibido ), step ) );
    }
  }

  /** El texto con las variables sustituidas (${...}, %%...%%, $[hex]) hasta que no cambie, como mucho 5 veces. */
  private static String sustituir( VariableSpace vars, String texto ) {
    String t = texto == null ? "" : texto;
    for ( int i = 0; i < 5; i++ ) {
      String s = vars.environmentSubstitute( t );
      if ( s == null || s.equals( t ) ) {
        break;
      }
      t = s;
    }
    return t;
  }

  /**
   * Conexiones usadas, ajenas y sin usar (§3); la SQLite usada pasa a la copia de la plantilla y, con bases de
   * prueba, cada una pasa a la suya (primero las que coinciden por nombre o por base, despues por tipo y servidor).
   */
  private static void conexiones( Element doc, Entrega e, Preparacion p, List<Hallazgo> problemas,
                                  CorrectorBases.Sesion bases ) {
    Set<String> usadas = new HashSet<String>();
    for ( String u : CorrectorPolitica.conexionesUsadas( e ) ) {
      usadas.add( u.toLowerCase( Locale.ROOT ) );
    }
    Set<String> definidas = new HashSet<String>();
    List<Element> orden = new ArrayList<Element>();
    List<Element> despues = new ArrayList<Element>();
    for ( Element c : Nodos.hijos( doc, "connection" ) ) {
      boolean directa = bases != null && CorrectorBases.emparejar( bases.pack, Nodos.txt( c, "name" ), Nodos.txt( c,
        "type" ), Nodos.txt( c, "server" ), Nodos.txt( c, "database" ), new HashSet<BaseDePrueba>(), true ) != null;
      ( directa || bases == null ? orden : despues ).add( c );
    }
    orden.addAll( despues );
    Set<BaseDePrueba> emparejadas = new HashSet<BaseDePrueba>();
    for ( Element c : orden ) {
      String nombre = Nodos.txt( c, "name" );
      String tipo = Nodos.txt( c, "type" );
      definidas.add( nombre.toLowerCase( Locale.ROOT ) );
      if ( !usadas.contains( nombre.toLowerCase( Locale.ROOT ) ) ) {
        doc.removeChild( c );
        p.avisos.add( new Mensaje( "det.connection.unused", nombre, tipo ).aviso( Aviso.CONEXION_QUITADA ) );
        continue;
      }
      if ( bases != null && !( p.baseDatos != null && "SQLITE".equalsIgnoreCase( tipo ) ) ) {
        BaseDePrueba b = CorrectorBases.emparejar( bases.pack, nombre, tipo, Nodos.txt( c, "server" ), Nodos.txt( c,
          "database" ), emparejadas, false );
        if ( b != null ) {
          emparejadas.add( b );
          aPrueba( c, nombre, tipo, bases.destino( b ), p );
          continue;
        }
        String step = stepQueUsa( e, nombre );
        List<String> nombres = new ArrayList<String>();
        for ( BaseDePrueba x : bases.pack.bases ) {
          nombres.add( x.conexion );
        }
        problemas.add( new Hallazgo( ResultadoCaso.CONEXION_AJENA, step == null ? new Mensaje(
          "det.connection.not_test", nombre, tipo, String.join( ", ", nombres ) ) : new Mensaje(
          "det.connection.not_test.step", nombre, tipo, step, String.join( ", ", nombres ) ), step ) );
        continue;
      }
      if ( p.baseDatos != null && "SQLITE".equalsIgnoreCase( tipo ) ) {
        String antes = Nodos.txt( c, "database" );
        poner( c, "database", p.baseDatos.getAbsolutePath() );
        poner( c, "access", "Native" );
        Element attrs = Nodos.hijo( c, "attributes" );
        for ( Element a : Nodos.hijos( attrs, "attribute" ) ) {
          if ( !ATRIBUTOS_SQLITE.contains( Nodos.txt( a, "code" ).toLowerCase( Locale.ROOT ) ) ) {
            attrs.removeChild( a );
            if ( "SQL_CONNECT".equalsIgnoreCase( Nodos.txt( a, "code" ) ) && !Nodos.txt( a, "attribute" ).isEmpty() ) {
              p.redirecciones.add( "connection " + q( nombre ) + " (SQLITE): connect SQL -> (none)" );
            }
          }
        }
        p.redirecciones.add( "connection " + q( nombre ) + " (SQLITE): " + antes + " -> " + DB + "/"
          + p.baseDatos.getName() );
      } else {
        String step = stepQueUsa( e, nombre );
        problemas.add( new Hallazgo( ResultadoCaso.CONEXION_AJENA, step == null ? new Mensaje(
          "det.connection.not_pack", nombre, tipo ) : new Mensaje( "det.connection.not_pack.step", nombre, tipo, step ),
          step ) );
      }
    }
    for ( DatabaseMeta d : e.tm.getDatabases() ) { // por si getXML no escribio alguna que Kettle cargo
      String k = d.getName() == null ? "" : d.getName().toLowerCase( Locale.ROOT );
      if ( !k.isEmpty() && !definidas.contains( k ) && !usadas.contains( k ) ) {
        definidas.add( k );
        p.avisos.add( new Mensaje( "det.connection.unused", d.getName(), d.getPluginId() ).aviso(
          Aviso.CONEXION_QUITADA ) );
      }
    }
    for ( String u : CorrectorPolitica.conexionesUsadas( e ) ) {
      if ( !definidas.contains( u.toLowerCase( Locale.ROOT ) ) ) {
        String step = stepQueUsa( e, u );
        problemas.add( new Hallazgo( ResultadoCaso.CONEXION_AJENA, step == null ? new Mensaje(
          "det.connection.undefined", u ) : new Mensaje( "det.connection.undefined.step", u, step ), step ) );
      }
    }
  }

  /**
   * Apunta una conexion de la copia a su base de prueba: tipo, acceso nativo, servidor, puerto, base (con el sufijo
   * del worker) y usuario del pack; contraseña vacia (el motor pone la de verdad en memoria, asi no queda en ningun
   * archivo); sin atributos salvo los de identificadores (fuera opciones extra, SQL al conectar, pool, cluster) y con
   * las opciones JDBC del pack.
   */
  private static void aPrueba( Element c, String nombre, String tipo, CorrectorBases.Destino d, Preparacion p ) {
    BaseDePrueba b = d.base;
    String antes = Nodos.txt( c, "database" );
    poner( c, "type", b.tipoNormal() );
    poner( c, "access", "Native" );
    poner( c, "server", CorrectorBases.n( b.servidor ) );
    poner( c, "port", CorrectorBases.n( b.puerto ) );
    poner( c, "database", d.nombre );
    poner( c, "username", CorrectorBases.n( b.usuario ) );
    poner( c, "password", "" );
    for ( String tag : new String[] { "servername", "data_tablespace", "index_tablespace" } ) {
      Element x = Nodos.hijo( c, tag );
      if ( x != null ) {
        x.setTextContent( "" );
      }
    }
    Element attrs = Nodos.hijo( c, "attributes" );
    if ( attrs == null ) {
      attrs = c.getOwnerDocument().createElement( "attributes" );
      c.appendChild( attrs );
    }
    for ( Element a : Nodos.hijos( attrs, "attribute" ) ) {
      if ( !CorrectorBases.ATRIBUTOS.contains( Nodos.txt( a, "code" ).toLowerCase( Locale.ROOT ) ) ) {
        attrs.removeChild( a );
      }
    }
    for ( Map.Entry<String, String> o : b.opciones.entrySet() ) {
      if ( o.getKey() == null || o.getKey().trim().isEmpty() ) {
        continue;
      }
      Element a = c.getOwnerDocument().createElement( "attribute" );
      Element code = c.getOwnerDocument().createElement( "code" );
      code.setTextContent( "EXTRA_OPTION_" + b.tipoNormal() + "." + o.getKey().trim() );
      Element val = c.getOwnerDocument().createElement( "attribute" );
      val.setTextContent( o.getValue() == null ? "" : o.getValue() );
      a.appendChild( code );
      a.appendChild( val );
      attrs.appendChild( a );
    }
    p.conexionesPrueba.put( nombre, d );
    p.redirecciones.add( "connection " + q( nombre ) + " (" + tipo + "): " + antes + " -> test database "
      + d.describir() );
  }

  /**
   * Con bases de prueba: los esquemas que nombran los steps (Table output, Insert/Update...: hojas schema) en una
   * conexion MySQL pasan de la base original a la de prueba; uno que no es de prueba bloquea
   * ({@code foreign_schema}). Y el SQL de los steps (hojas sql/query, con las variables sustituidas) no puede nombrar
   * otra base ni leer o escribir archivos del servidor ({@code forbidden_sql}).
   */
  private static void esquemasYSql( Element doc, VariableSpace vars, Preparacion p, List<Hallazgo> problemas,
                                    CorrectorBases.Sesion bases ) {
    Set<String> pruebas = bases.nombres();
    Set<String> prohibidas = bases.prohibidas();
    Map<String, String> renombres = bases.renombres();
    Map<String, CorrectorBases.Destino> porConexion = new HashMap<String, CorrectorBases.Destino>();
    for ( Map.Entry<String, CorrectorBases.Destino> en : p.conexionesPrueba.entrySet() ) {
      porConexion.put( en.getKey().toLowerCase( Locale.ROOT ), en.getValue() );
    }
    for ( Element s : Nodos.hijos( doc, "step" ) ) {
      String nombre = Nodos.crudo( s, "name" );
      String tipo = Nodos.txt( s, "type" );
      CorrectorBases.Destino d = null;
      for ( Element[] hp : Nodos.hojas( s ) ) {
        if ( "connection".equals( hp[ 0 ].getTagName() ) && d == null ) {
          d = porConexion.get( hp[ 0 ].getTextContent().trim().toLowerCase( Locale.ROOT ) );
        }
      }
      if ( d == null ) {
        continue;
      }
      boolean mysql = d.base.esMysql();
      for ( Element[] hp : Nodos.hojas( s ) ) {
        Element h = hp[ 0 ];
        String tag = h.getTagName().toLowerCase( Locale.ROOT );
        String crudo = h.getTextContent();
        String valor = sustituir( vars, crudo ).trim();
        if ( mysql && ( tag.equals( "schema" ) || tag.equals( "schemaname" ) || tag.equals( "schema_name" ) ) ) {
          if ( valor.isEmpty() ) {
            continue;
          }
          if ( valor.contains( "${" ) || valor.contains( "%%" ) ) {
            problemas.add( new Hallazgo( ResultadoCaso.ESQUEMA_AJENO, new Mensaje( "det.schema.variable", nombre, tipo,
              crudo.trim() ), nombre ) );
            continue;
          }
          String nuevo = renombres.get( valor.toLowerCase( Locale.ROOT ) );
          if ( nuevo != null ) {
            h.setTextContent( nuevo );
            p.redirecciones.add( "schema " + q( nombre ) + ": " + crudo.trim() + " -> " + nuevo );
          } else if ( !pruebas.contains( valor.toLowerCase( Locale.ROOT ) ) ) {
            problemas.add( new Hallazgo( ResultadoCaso.ESQUEMA_AJENO, new Mensaje( "det.schema.foreign", nombre, tipo,
              valor, String.join( ", ", pruebas ) ), nombre ) );
          }
          continue;
        }
        if ( mysql && ( tag.equals( "table" ) || tag.equals( "tablename" ) || tag.equals( "table_name" ) )
          && valor.indexOf( '.' ) > 0 ) {
          String esquema = valor.substring( 0, valor.indexOf( '.' ) ).replace( "`", "" ).trim();
          String nuevo = renombres.get( esquema.toLowerCase( Locale.ROOT ) );
          if ( nuevo != null ) {
            h.setTextContent( nuevo + valor.substring( valor.indexOf( '.' ) ) );
            p.redirecciones.add( "schema " + q( nombre ) + ": " + crudo.trim() + " -> " + h.getTextContent() );
          } else if ( !pruebas.contains( esquema.toLowerCase( Locale.ROOT ) ) ) {
            problemas.add( new Hallazgo( ResultadoCaso.ESQUEMA_AJENO, new Mensaje( "det.schema.foreign", nombre, tipo,
              esquema, String.join( ", ", pruebas ) ), nombre ) );
          }
          continue;
        }
        if ( tag.contains( "sql" ) || tag.equals( "query" ) || tag.equals( "statement" ) ) {
          // primero los nombres de las bases originales pasan a los de prueba (gym_dw.dim → gym_dw_correccion.dim)
          String nuevo = mysql ? CorrectorBases.renombrar( crudo, renombres ) : crudo;
          String ajeno = CorrectorBases.sqlAjeno( mysql ? CorrectorBases.renombrar( valor, renombres ) : valor,
            pruebas, prohibidas );
          if ( ajeno == null ) {
            ajeno = CorrectorBases.sqlAjeno( nuevo, pruebas, prohibidas );
          }
          if ( ajeno != null ) {
            problemas.add( new Hallazgo( ResultadoCaso.SQL_PROHIBIDO, new Mensaje( "det.sql.server", nombre, tipo,
              ajeno ), nombre ) );
          } else if ( !nuevo.equals( crudo ) ) {
            h.setTextContent( nuevo );
            p.redirecciones.add( "sql " + q( nombre ) + ": original database names -> test databases" );
          }
        }
      }
    }
  }

  /** El primer step que usa esa conexion (en el XML o por getUsedDatabaseConnections), o null (log, maxdate...). */
  private static String stepQueUsa( Entrega e, String conexion ) {
    for ( Element[] hp : Nodos.hojas( e.kettle ) ) {
      if ( "connection".equals( hp[ 0 ].getTagName() )
        && hp[ 0 ].getTextContent().trim().equalsIgnoreCase( conexion ) ) {
        Element dueno = CorrectorPolitica.hijoDeRaiz( e.kettle, hp[ 0 ] );
        if ( dueno != null && "step".equals( dueno.getTagName() ) ) {
          return Nodos.crudo( dueno, "name" );
        }
      }
    }
    for ( StepMeta s : e.tm.getSteps() ) {
      try {
        for ( DatabaseMeta d : s.getStepMetaInterface().getUsedDatabaseConnections() ) {
          if ( d != null && conexion.equalsIgnoreCase( d.getName() ) ) {
            return s.getName();
          }
        }
      } catch ( Throwable ignored ) {
        // sigue
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ chequeos

  /** Que la copia tenga los mismos steps, hops y referencias entre steps que la entrega (P14); null si si. */
  static String diferencias( TransMeta a, TransMeta b ) {
    Mensaje m = diferenciasMensaje( a, b );
    return m == null ? null : m.en();
  }

  static Mensaje diferenciasMensaje( TransMeta a, TransMeta b ) {
    List<String> na = nombres( a );
    List<String> nb = nombres( b );
    if ( !na.equals( nb ) ) {
      return new Mensaje( "det.rewrite.names", na, nb );
    }
    List<String> ha = hops( a );
    List<String> hb = hops( b );
    if ( !ha.equals( hb ) ) {
      return new Mensaje( "det.rewrite.hops", ha, hb );
    }
    for ( StepMeta sa : a.getSteps() ) {
      StepMeta sb = b.findStep( sa.getName() );
      String ra = referencias( sa );
      String rb = sb == null ? null : referencias( sb );
      if ( ra != null && rb != null && !ra.equals( rb ) ) {
        return new Mensaje( "det.rewrite.refs", sa.getName(), ra, rb );
      }
    }
    return null;
  }

  private static List<String> nombres( TransMeta tm ) {
    List<String> l = new ArrayList<String>();
    for ( StepMeta s : tm.getSteps() ) {
      l.add( s.getName() + " (" + s.getStepID() + ")" );
    }
    Collections.sort( l );
    return l;
  }

  private static List<String> hops( TransMeta tm ) {
    List<String> l = new ArrayList<String>();
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      l.add( ( h.getFromStep() == null ? "?" : h.getFromStep().getName() ) + " -> "
        + ( h.getToStep() == null ? "?" : h.getToStep().getName() ) + ( h.isEnabled() ? "" : " (disabled)" ) );
    }
    Collections.sort( l );
    return l;
  }

  /** Los steps que el step nombra (info, destinos, manejo de errores); null si no se puede saber. */
  private static String referencias( StepMeta s ) {
    try {
      List<String> l = new ArrayList<String>();
      StepIOMetaInterface io = s.getStepMetaInterface().getStepIOMeta();
      if ( io != null ) {
        for ( StreamInterface st : io.getInfoStreams() ) {
          l.add( "info:" + st.getStepname() );
        }
        for ( StreamInterface st : io.getTargetStreams() ) {
          l.add( "target:" + st.getStepname() );
        }
      }
      if ( s.getStepErrorMeta() != null && s.getStepErrorMeta().getTargetStep() != null ) {
        l.add( "error:" + s.getStepErrorMeta().getTargetStep().getName() );
      }
      return l.toString();
    } catch ( Throwable t ) {
      return null;
    }
  }

  /** Las hojas de ruta del step (heuristica de absolute_path del linter) que caen fuera del sandbox. */
  private static void revisarRutas( Element s, VariableSpace vars, List<Path> raices, List<Hallazgo> problemas ) {
    String nombre = Nodos.crudo( s, "name" );
    String tipo = Nodos.txt( s, "type" );
    boolean salida = SALIDAS.contains( CorrectorPolitica.canonico( tipo ).toLowerCase( Locale.ROOT ) );
    for ( Rutas.Ruta r : Rutas.de( s ) ) {
      if ( deDatos( s, r.elemento ) || plantillaApagada( r.elemento ) || esBandera( r.valor ) ) {
        continue;
      }
      Mensaje fuera = rutaFueraMensaje( r.valor, vars, raices );
      if ( fuera != null ) {
        String tag = r.tag.toLowerCase( Locale.ROOT );
        boolean escribe = salida && tag.equals( "file/name" ) || tag.contains( "destination" )
          || tag.contains( "tempdirectory" );
        problemas.add( new Hallazgo( escribe ? ResultadoCaso.SALIDA_INESPERADA : ResultadoCaso.ENTRADA_INESPERADA,
          new Mensaje( "det.path", nombre, tipo, r.tag, fuera.anidado() ), nombre ) );
      }
    }
  }

  /**
   * Lo que declaran los steps con getResourceDependencies (como ReportMotor.archivosDe), con las variables del
   * caso. Solo si las hojas ya pasaron: asi una URL nunca llega a VFS (no se abre ninguna conexion).
   */
  private static void dependencias( TransMeta tm, Preparacion p, VariableSpace vars, List<Path> raices,
    List<Hallazgo> problemas ) {
    try {
      tm.setVariable( "java.io.tmpdir", vars.getVariable( "java.io.tmpdir" ) );
      for ( Map.Entry<String, String> par : p.parametros.entrySet() ) {
        tm.setParameterValue( par.getKey(), par.getValue() );
      }
      tm.activateParameters();
    } catch ( Throwable ignored ) {
      // quedan los default
    }
    for ( StepMeta s : tm.getSteps() ) {
      if ( SALIDAS.contains( CorrectorPolitica.canonico( s.getStepID() ).toLowerCase( Locale.ROOT ) ) ) {
        continue;
      }
      List<ResourceReference> refs;
      try {
        refs = s.getStepMetaInterface().getResourceDependencies( tm, s );
      } catch ( Throwable t ) {
        continue;
      }
      if ( refs == null ) {
        continue;
      }
      for ( ResourceReference ref : refs ) {
        for ( ResourceEntry en : ref.getEntries() ) {
          if ( en.getResourcetype() != ResourceEntry.ResourceType.FILE || en.getResource() == null
            || en.getResource().trim().isEmpty() ) {
            continue;
          }
          Mensaje fuera = rutaFueraMensaje( en.getResource(), vars, raices );
          if ( fuera != null ) {
            problemas.add( new Hallazgo( ResultadoCaso.ENTRADA_INESPERADA, new Mensaje( "det.reads", s.getName(),
              s.getStepID(), fuera.anidado() ), s.getName() ) );
          }
        }
      }
    }
  }

  /**
   * Por que la ruta queda fuera del sandbox (con las variables resueltas), o null si cae dentro de alguna de las
   * raices: variable sin resolver, esquema VFS que no es {@code file:} (http, ftp, zip...), ruta invalida o fuera.
   * Una ruta relativa se resuelve contra el directorio de trabajo de la JVM (la carpeta de PDI en el worker).
   */
  static String rutaFuera( String valor, VariableSpace vars, List<Path> raices ) {
    Mensaje m = rutaFueraMensaje( valor, vars, raices );
    return m == null ? null : m.en();
  }

  static Mensaje rutaFueraMensaje( String valor, VariableSpace vars, List<Path> raices ) {
    String v0 = valor == null ? "" : valor.trim(); // en el detalle va lo que escribio el alumno, no la ruta resuelta
    String s = vars == null ? v0 : vars.environmentSubstitute( v0 );
    s = s == null ? "" : s.trim();
    if ( s.isEmpty() ) {
      return null;
    }
    if ( s.contains( "${" ) || s.contains( "%%" ) ) {
      return new Mensaje( "det.route.variable", v0 );
    }
    String esquema = esquema( s );
    if ( esquema != null && !esquema.equals( "file" ) ) {
      return new Mensaje( "det.route.scheme", v0, esquema );
    }
    Path p;
    try {
      File f = aArchivo( s ).getAbsoluteFile();
      if ( red( f.getPath() ) ) {
        // ni se canonicaliza: Windows abriria una conexion SMB (y mandaria las credenciales) al servidor que diga
        return new Mensaje( "det.route.network", v0 );
      }
      p = f.getCanonicalFile().toPath();
    } catch ( Exception ex ) {
      return new Mensaje( "det.route.invalid", v0 );
    }
    for ( Path r : raices ) {
      if ( r != null && p.startsWith( r ) ) {
        return null;
      }
    }
    return new Mensaje( "det.route.outside", v0 );
  }

  /** Ruta UNC o de dispositivo (\\servidor\recurso, //servidor, \\?\, \\.\): no se resuelve en el disco local. */
  static boolean red( String ruta ) {
    String t = ruta == null ? "" : ruta.trim();
    return t.startsWith( "\\\\" ) || t.startsWith( "//" ) || t.startsWith( "\\/" ) || t.startsWith( "/\\" );
  }

  /** El esquema VFS en minusculas ("http", "file", "zip"), o null; una letra de unidad ("C:") no es esquema. */
  static String esquema( String s ) {
    Matcher m = ESQUEMA.matcher( s == null ? "" : s.trim() );
    return m.find() ? m.group( 1 ).toLowerCase( Locale.ROOT ) : null;
  }

  /**
   * Un archivo local a partir de una ruta o una URL {@code file:} (tambien sin codificar: «file:///C:/a b/x»).
   * {@code file://servidor/recurso} es una ruta UNC.
   */
  static File aArchivo( String ruta ) {
    String t = ruta.trim();
    if ( !t.regionMatches( true, 0, "file:", 0, 5 ) ) {
      return new File( t );
    }
    try {
      URI u = new URI( t );
      if ( u.getAuthority() == null || u.getAuthority().isEmpty() ) {
        return new File( u );
      }
    } catch ( Exception ignored ) {
      // sin codificar o con autoridad: a mano
    }
    String r = t.substring( 5 );
    int barras = 0;
    while ( barras < r.length() && ( r.charAt( barras ) == '/' || r.charAt( barras ) == '\\' ) ) {
      barras++;
    }
    String resto = decodificar( r.substring( barras ) );
    if ( barras == 2 ) {
      return new File( "\\\\" + resto.replace( '/', '\\' ) );
    }
    if ( resto.length() >= 2 && resto.charAt( 1 ) == ':' ) {
      return new File( resto );
    }
    return new File( "/" + resto );
  }

  // ------------------------------------------------------------------ archivos

  /** Borra una carpeta de sandbox (o un archivo): quita el solo-lectura de datos/ y no entra en enlaces ni junctions. */
  static void borrar( File f ) throws IOException {
    Path p = f.toPath();
    BasicFileAttributes a;
    try {
      a = Files.readAttributes( p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS );
    } catch ( NoSuchFileException ex ) {
      return;
    }
    if ( a.isDirectory() && !a.isSymbolicLink() && !a.isOther() ) {
      File[] hijos = f.listFiles();
      if ( hijos != null ) {
        for ( File h : hijos ) {
          borrar( h );
        }
      }
    }
    f.setWritable( true );
    Files.deleteIfExists( p );
  }

  /** Vacia la carpeta del caso si es un sandbox anterior (tiene la marca); si tiene otra cosa, no la toca. */
  private static void limpiar( File dir ) throws IOException {
    if ( dir.exists() ) {
      String[] hijos = dir.list();
      if ( hijos == null || hijos.length > 0 && !new File( dir, MARCA ).isFile() ) {
        throw new IOException( "refusing to clear " + dir + ": it is not a grader sandbox (no " + MARCA + ")" );
      }
      borrar( dir );
    }
    if ( !dir.mkdirs() && !dir.isDirectory() ) {
      throw new IOException( "cannot create " + dir );
    }
  }

  private static File carpeta( File padre, String nombre ) throws IOException {
    File d = new File( padre, nombre );
    if ( !d.mkdirs() && !d.isDirectory() ) {
      throw new IOException( "cannot create " + d );
    }
    return d;
  }

  /**
   * Copia las entradas del caso a datos/ y las deja de solo lectura: la carpeta {@code inputs_dir} del pack si existe;
   * si no, las claves de {@code inline} que empiezan con ella (texto UTF-8).
   */
  static void copiarEntradas( Pack pack, File dirPack, Caso caso, File datos ) throws IOException {
    String rel = caso == null || caso.dirEntradas == null ? "" : normalizar( caso.dirEntradas );
    if ( rel.isEmpty() ) {
      return;
    }
    File origen = dirPack == null ? null : new File( dirPack, rel );
    if ( origen != null && origen.isDirectory() ) {
      if ( !canonica( origen ).startsWith( canonica( dirPack ) ) ) {
        throw new IOException( "inputs_dir is outside the pack: " + caso.dirEntradas );
      }
      copiarArbol( origen, datos );
    } else if ( pack.inline != null ) {
      String pref = rel + "/";
      for ( Map.Entry<String, String> en : pack.inline.entrySet() ) {
        String k = normalizar( en.getKey() );
        if ( !k.startsWith( pref ) || en.getValue() == null ) {
          continue;
        }
        String resto = k.substring( pref.length() );
        if ( resto.isEmpty() || ( "/" + resto + "/" ).contains( "/../" ) || esquema( resto ) != null ) {
          throw new IOException( "inline file is outside its folder: " + en.getKey() );
        }
        File f = new File( datos, resto );
        File padre = f.getParentFile();
        if ( padre != null && !padre.mkdirs() && !padre.isDirectory() ) {
          throw new IOException( "cannot create " + padre );
        }
        Files.write( f.toPath(), en.getValue().getBytes( StandardCharsets.UTF_8 ) );
      }
    }
    soloLectura( datos );
  }

  /** La plantilla SQLite del pack copiada a db/; null si el pack no tiene base. */
  private static File copiarPlantilla( Pack pack, File dirPack, File db ) throws IOException {
    if ( pack.salida == null || pack.salida.base == null || pack.salida.base.plantilla == null
      || pack.salida.base.plantilla.trim().isEmpty() ) {
      return null;
    }
    File src = dirPack == null ? null : new File( dirPack, normalizar( pack.salida.base.plantilla ) );
    if ( src == null || !src.isFile() ) {
      throw new IOException( "the pack database template is missing: " + pack.salida.base.plantilla );
    }
    if ( !canonica( src ).startsWith( canonica( dirPack ) ) ) {
      throw new IOException( "the pack database template is outside the pack: " + pack.salida.base.plantilla );
    }
    File dst = new File( db, src.getName() );
    Files.copy( src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING );
    return dst;
  }

  private static void copiarArbol( File desde, File hacia ) throws IOException {
    File[] hijos = desde.listFiles();
    if ( hijos == null ) {
      return;
    }
    for ( File h : hijos ) {
      File d = new File( hacia, h.getName() );
      if ( Files.isSymbolicLink( h.toPath() ) ) {
        continue;
      }
      if ( h.isDirectory() ) {
        carpeta( hacia, h.getName() );
        copiarArbol( h, d );
      } else {
        Files.copy( h.toPath(), d.toPath(), StandardCopyOption.REPLACE_EXISTING );
      }
    }
  }

  private static void soloLectura( File dir ) {
    File[] hijos = dir.listFiles();
    if ( hijos == null ) {
      return;
    }
    for ( File h : hijos ) {
      if ( h.isDirectory() ) {
        soloLectura( h );
      } else {
        h.setWritable( false );
      }
    }
  }

  /** Con un FileOutputStream propio: un StreamResult(File) deja el archivo abierto en Windows (P12). */
  static void escribir( Element doc, File destino ) throws Exception {
    TransformerFactory tf = TransformerFactory.newInstance();
    try {
      tf.setFeature( XMLConstants.FEATURE_SECURE_PROCESSING, true );
    } catch ( Exception ignored ) {
      // el que venga en el classpath puede no conocerla
    }
    Transformer t = tf.newTransformer();
    t.setOutputProperty( OutputKeys.ENCODING, "UTF-8" );
    t.setOutputProperty( OutputKeys.METHOD, "xml" );
    try ( OutputStream os = new FileOutputStream( destino ) ) {
      t.transform( new DOMSource( doc ), new StreamResult( os ) );
    }
  }

  // ------------------------------------------------------------------ ayudas

  /**
   * Las variables con que se resuelven las rutas: las del sistema, las internas del caso (rutas planas de la carpeta
   * del caso), java.io.tmpdir de la corrida y los parametros (default de la entrega y valores del pack).
   */
  private static Variables variables( Preparacion p, Element doc, Map<String, String> declarados, File tmpJvm ) {
    Variables v = new Variables();
    v.initializeVariablesFrom( null );
    String dir = p.dirCaso.getAbsolutePath().replace( '\\', '/' );
    v.setVariable( "java.io.tmpdir", tmpJvm.getAbsolutePath() );
    v.setVariable( "Internal.Entry.Current.Directory", dir );
    v.setVariable( "Internal.Transformation.Filename.Directory", dir );
    v.setVariable( "Internal.Transformation.Name", Nodos.txt( Nodos.hijo( doc, "info" ), "name" ) );
    for ( Map.Entry<String, String> d : declarados.entrySet() ) {
      v.setVariable( d.getKey(), d.getValue() );
    }
    for ( Map.Entry<String, String> d : p.parametros.entrySet() ) {
      v.setVariable( d.getKey(), d.getValue() );
    }
    return v;
  }

  /** La ruta local de una carpeta con «/» ("C:/.../tmp"): la entienden java.io.File y VFS. */
  static String local( File f ) {
    return f.getAbsolutePath().replace( '\\', '/' );
  }

  /** La hoja esta en un subarbol de datos del step ({@link #NO_SE_TOCAN}). */
  private static boolean deDatos( Element step, Element hoja ) {
    for ( Node n = hoja.getParentNode(); n instanceof Element && n != step; n = n.getParentNode() ) {
      if ( NO_SE_TOCAN.contains( ( (Element) n ).getTagName().toLowerCase( Locale.ROOT ) ) ) {
        return true;
      }
    }
    return false;
  }

  /** template/filename de Excel output o Excel writer con la plantilla apagada (el default trae «template.xls»). */
  private static boolean plantillaApagada( Element hoja ) {
    Node padre = hoja.getParentNode();
    if ( padre instanceof Element && "template".equals( ( (Element) padre ).getTagName() ) ) {
      return !Nodos.txt( (Element) padre, "enabled" ).equalsIgnoreCase( "Y" );
    }
    return false;
  }

  /** Las redirecciones por clase (entradas, salidas, conexiones, parametros) y, dentro de cada una, como salieron. */
  private static void ordenarNotas( List<String> notas ) {
    final List<String> orden = Arrays.asList( "input ", "output ", "connection ", "parameter ", "shared " );
    Collections.sort( notas, new java.util.Comparator<String>() {
      @Override
      public int compare( String a, String b ) {
        return clase( a ) - clase( b );
      }

      private int clase( String s ) {
        for ( int i = 0; i < orden.size(); i++ ) {
          if ( s.startsWith( orden.get( i ) ) ) {
            return i;
          }
        }
        return orden.size();
      }
    } );
  }

  /** Tag de archivo o carpeta ({@code filename}, {@code directory}, {@code file/name}...): nunca es un nombre de step. */
  private static boolean tagDeRuta( Element h ) {
    String t = h.getTagName().toLowerCase( Locale.ROOT );
    Node padre = h.getParentNode();
    return t.contains( "file" ) || t.contains( "dir" ) || t.contains( "folder" ) || t.contains( "path" )
      || t.equals( "name" ) && padre instanceof Element && "file".equals( ( (Element) padre ).getTagName() );
  }

  private static boolean esBandera( String v ) {
    String t = v.trim();
    return t.equalsIgnoreCase( "Y" ) || t.equalsIgnoreCase( "N" ) || t.equalsIgnoreCase( "true" )
      || t.equalsIgnoreCase( "false" );
  }

  /** Lo que va despues de la ultima barra (sin barras finales); %XX decodificado si es una URL file:. */
  static String nombreBase( String ruta ) {
    String t = ruta == null ? "" : ruta.trim();
    while ( t.endsWith( "/" ) || t.endsWith( "\\" ) ) {
      t = t.substring( 0, t.length() - 1 );
    }
    int i = Math.max( t.lastIndexOf( '/' ), t.lastIndexOf( '\\' ) );
    String b = i < 0 ? t : t.substring( i + 1 );
    return ruta != null && ruta.trim().regionMatches( true, 0, "file:", 0, 5 ) ? decodificar( b ) : b;
  }

  /** Un nombre de archivo sin barras ni caracteres que Windows no acepta; "" si queda vacio, "." o "..". */
  static String limpio( String nombre ) {
    String t = NO_VALE_EN_NOMBRE.matcher( nombre == null ? "" : nombre ).replaceAll( "_" ).trim();
    while ( t.endsWith( "." ) || t.endsWith( " " ) ) {
      t = t.substring( 0, t.length() - 1 );
    }
    return t.equals( "." ) || t.equals( ".." ) ? "" : t;
  }

  private static String clave( String base, String ext ) {
    return ( base + ( ext == null || ext.isEmpty() ? "" : "." + ext ) ).toLowerCase( Locale.ROOT );
  }

  /** "datos/ventas.csv" a partir de "${Internal.Entry.Current.Directory}/datos/ventas.csv". */
  private static String relativa( String valor ) {
    return valor.startsWith( DIR_ACTUAL + "/" ) ? valor.substring( DIR_ACTUAL.length() + 1 ) : valor;
  }

  private static String decodificar( String s ) {
    if ( s.indexOf( '%' ) < 0 ) {
      return s;
    }
    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
    Matcher m = PORCIENTO.matcher( s );
    int desde = 0;
    while ( m.find() ) {
      byte[] antes = s.substring( desde, m.start() ).getBytes( StandardCharsets.UTF_8 );
      b.write( antes, 0, antes.length );
      b.write( Integer.parseInt( m.group( 1 ), 16 ) );
      desde = m.end();
    }
    byte[] resto = s.substring( desde ).getBytes( StandardCharsets.UTF_8 );
    b.write( resto, 0, resto.length );
    return new String( b.toByteArray(), StandardCharsets.UTF_8 );
  }

  /** Ruta relativa con / y sin ./ ni / al principio o al final. */
  private static String normalizar( String ruta ) {
    String r = ruta == null ? "" : ruta.trim().replace( '\\', '/' );
    while ( r.startsWith( "./" ) ) {
      r = r.substring( 2 );
    }
    while ( r.startsWith( "/" ) ) {
      r = r.substring( 1 );
    }
    while ( r.endsWith( "/" ) ) {
      r = r.substring( 0, r.length() - 1 );
    }
    return r;
  }

  static Path canonica( File f ) {
    try {
      return f.getAbsoluteFile().getCanonicalFile().toPath();
    } catch ( IOException e ) {
      return f.getAbsoluteFile().toPath().normalize();
    }
  }

  private static void poner( Element padre, String tag, String valor ) {
    Element h = Nodos.hijo( padre, tag );
    if ( h == null ) {
      h = padre.getOwnerDocument().createElement( tag );
      padre.appendChild( h );
    }
    h.setTextContent( valor );
  }

  private static Set<String> conjunto( String... s ) {
    Set<String> c = new HashSet<String>();
    for ( String x : s ) {
      c.add( x.toLowerCase( Locale.ROOT ) );
    }
    return Collections.unmodifiableSet( c );
  }

  private static String q( String s ) {
    return CorrectorPolitica.q( s );
  }

  private static boolean eq( String a, String b ) {
    return a == null ? b == null : a.equals( b );
  }

  private static String primeraLinea( Throwable t ) {
    String m = t.getMessage() == null ? t.toString() : t.getMessage().trim();
    int i = m.indexOf( '\n' );
    return i < 0 ? m : m.substring( 0, i ).trim();
  }
}
