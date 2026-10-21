package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.lint.Supresiones;
import io.github.ivi2511.pentahomcp.robustez.Balance;
import io.github.ivi2511.pentahomcp.robustez.Variantes;

/**
 * El sandbox de {@code stress_test} (docs/robustez-diseno.md §5.2): analiza la transformación (entradas de archivo y
 * sus primeras filas, salidas, conexiones, steps que no pueden correr, el grafo resumido para el balance) y escribe
 * las COPIAS del .ktr que corre la hija: entradas → la variante, salidas de archivo → {@code salida/<variante>/},
 * conexiones SQLite/H2 de archivo → una copia de la BD en {@code db/<variante>/}. Cualquier otra conexión usada →
 * no corre (nunca se conecta a una BD que no sea un archivo del sandbox). El documento original no se toca: se trabaja
 * sobre un clon y sobre su XML.
 *
 * @dueño R2c
 */
final class EstresSandbox {

  /** Steps de entrada de archivo que stress_test sabe ensuciar. */
  static final Set<String> ENTRADAS = new HashSet<String>( Arrays.asList( "CsvInput", "TextFileInput",
    "ExcelInput", "JsonInput" ) );
  /** Steps que escriben archivos: se redirigen al sandbox. */
  static final Set<String> SALIDAS_ARCHIVO = new HashSet<String>( Arrays.asList( "TextFileOutput", "ExcelOutput",
    "TypeExitExcelWriterStep", "JsonOutput", "XMLOutput" ) );
  /** Steps que cargan en una BD. */
  static final Set<String> CARGAS_BD = new HashSet<String>( Arrays.asList( "TableOutput", "InsertUpdate", "Update",
    "Delete", "DimensionLookup", "CombinationLookup", "SynchronizeAfterMerge" ) );
  /** Steps que salen de la máquina, ejecutan cosas o abren otras transformaciones: bloquean la corrida. */
  static final Set<String> BLOQUEAN = new HashSet<String>( Arrays.asList( "Http", "HTTPPOST", "Rest",
    "WebServiceLookup", "Mail", "ExecProcess", "SSH", "MetaInject", "TransExecutor", "JobExecutor", "Mapping",
    "SimpleMapping", "SingleThreader", "SocketWriter", "SocketReader", "DBProc", "CubeOutput", "SQLFileOutput",
    "AccessOutput", "PropertyOutput", "LDIFOutput", "SalesforceInsert", "SalesforceUpdate", "SalesforceUpsert",
    "SalesforceDelete", "MongoDbOutput", "KafkaProducerOutput", "JmsProducer", "MQTTProducer", "ShapeFileReader",
    "WriteToFile", "ProcessFiles", "ZipFile", "SetVariable" ) );
  /** Steps de SQL: solo sobre una conexión de archivo del sandbox. */
  static final Set<String> SQL = new HashSet<String>( Arrays.asList( "ExecSQL", "ExecSQLRow" ) );

  private EstresSandbox() {
  }

  /** Una entrada de archivo. */
  static final class Entrada {
    String paso;
    String tipo;
    /** text, json o excel. */
    String formato;
    File archivo;
    String extension;
    Variantes.FormatoTexto texto;
    String hoja;
    int filaInicio;
    boolean cabecera;
    Variantes.Tabla base;
    /** Por qué no se ensucia (null = se ensucia). */
    String noEvaluada;
  }

  /** Una salida de archivo (redirigida). */
  static final class Salida {
    String paso;
    String tipo;
    String extension;
    boolean rechazo;
    Variantes.FormatoTexto texto;
    /** Campos declarados (si no hay cabecera, sus nombres). */
    final List<String> campos = new ArrayList<String>();

    File archivo( File dirVariante ) {
      return new File( dirVariante, seguro( paso ) + ( extension == null || extension.isEmpty() ? "" : "."
        + extension ) );
    }
  }

  /** Lo que se sabe de la transformación antes de correr nada. */
  static final class Analisis {
    final List<Entrada> entradas = new ArrayList<Entrada>();
    final List<Salida> salidas = new ArrayList<Salida>();
    final List<Balance.Nodo> nodos = new ArrayList<Balance.Nodo>();
    /** "step (type): why". */
    final List<String> bloqueos = new ArrayList<String>();
    /** Conexiones que no son archivo: "connection (TYPE) used by step". */
    final List<String> bdNoArchivo = new ArrayList<String>();
    /** Conexión usada de archivo → el archivo original (puede no existir). */
    final Map<String, File> bdArchivo = new LinkedHashMap<String, File>();
    final Map<String, String> bdTipo = new LinkedHashMap<String, String>();
    final Map<String, DatabaseMeta> bdMeta = new LinkedHashMap<String, DatabaseMeta>();
    /** {connection, table, keys[]} de las cargas (rerun). */
    final List<String[]> tablas = new ArrayList<String[]>();
    final List<List<String>> claves = new ArrayList<List<String>>();
    /** Nombres de los campos de motivo de los hops de error (descriptions_valuename). */
    final Set<String> camposMotivo = new LinkedHashSet<String>();
    final List<String> notas = new ArrayList<String>();
    String xml;

    boolean corre() {
      return bloqueos.isEmpty() && bdNoArchivo.isEmpty();
    }

    Entrada entrada( String paso ) {
      for ( Entrada e : entradas ) {
        if ( e.paso.equals( paso ) ) {
          return e;
        }
      }
      return null;
    }
  }

  // ------------------------------------------------------------------ análisis

  /** Analiza el clon (no lo modifica salvo variables de parámetros sin valor): entradas, salidas, BD, grafo. */
  static Analisis analizar( TransMeta tm, int filas, Set<String> soloEntradas, List<String> obligatorios )
    throws Exception {
    Analisis a = new Analisis();
    if ( tm.getFilename() != null && !tm.getFilename().trim().isEmpty() ) {
      // las rutas relativas al .ktr se resuelven contra la carpeta del ORIGINAL (la copia vive en el sandbox)
      File ktr = new File( tm.environmentSubstitute( tm.getFilename() ) ).getAbsoluteFile();
      String carpeta = ktr.getParent();
      if ( carpeta != null ) {
        for ( String v : new String[] { "Internal.Entry.Current.Directory",
          "Internal.Transformation.Filename.Directory" } ) {
          String x = tm.getVariable( v );
          if ( x == null || x.trim().isEmpty() || x.contains( "${" ) || !new File( x ).isDirectory() ) {
            tm.setVariable( v, carpeta );
          }
        }
      }
    }
    for ( String p : tm.listParameters() ) {
      if ( tm.getVariable( p ) == null || tm.getVariable( p ).isEmpty() ) {
        String d = tm.getParameterDefault( p );
        if ( d != null ) {
          tm.setVariable( p, d );
        }
      }
    }
    a.xml = tm.getXML();
    Document doc = parse( a.xml );
    Map<String, Element> pasos = new LinkedHashMap<String, Element>();
    for ( Element s : hijos( doc.getDocumentElement(), "step" ) ) {
      pasos.put( texto( s, "name" ), s );
    }
    // tipos que un Select values de más abajo da a los campos que la entrada lee como texto
    Map<String, String> conversiones = new LinkedHashMap<String, String>();
    for ( Element s : pasos.values() ) {
      Element campos = "SelectValues".equals( texto( s, "type" ) ) ? hijo( s, "fields" ) : null;
      if ( campos != null ) {
        for ( Element m : hijos( campos, "meta" ) ) {
          String n = texto( m, "name" );
          String t = texto( m, "type" );
          if ( n != null && t != null && !t.trim().isEmpty() && !"None".equalsIgnoreCase( t.trim() ) ) {
            conversiones.put( n.trim().toLowerCase( Locale.ROOT ), t.trim() );
          }
        }
      }
    }

    // --- ramas de rechazo: aguas abajo de un hop de error o de la rama falsa de un filtro
    Set<String> rechazo = new HashSet<String>();
    List<StepMeta> semillas = new ArrayList<StepMeta>();
    for ( StepMeta s : tm.getSteps() ) {
      StepErrorMeta em = s.getStepErrorMeta();
      if ( em != null && em.isEnabled() && em.getTargetStep() != null ) {
        semillas.add( tm.findStep( em.getTargetStep().getName() ) );
        if ( em.getErrorDescriptionsValuename() != null && !em.getErrorDescriptionsValuename().isEmpty() ) {
          a.camposMotivo.add( em.getErrorDescriptionsValuename() );
        }
      }
      Element x = pasos.get( s.getName() );
      if ( x != null && "FilterRows".equals( s.getStepID() ) ) {
        String f = texto( x, "send_false_to" );
        if ( f != null && !f.isEmpty() && tm.findStep( f ) != null ) {
          semillas.add( tm.findStep( f ) );
        }
      }
    }
    List<StepMeta> cola = new ArrayList<StepMeta>();
    for ( StepMeta s : semillas ) {
      if ( s != null && rechazo.add( s.getName() ) ) {
        cola.add( s );
      }
    }
    while ( !cola.isEmpty() ) {
      StepMeta s = cola.remove( 0 );
      for ( StepMeta n : tm.findNextSteps( s ) ) {
        if ( rechazo.add( n.getName() ) ) {
          cola.add( n );
        }
      }
    }

    for ( StepMeta s : tm.getSteps() ) {
      String id = s.getStepID();
      Element x = pasos.get( s.getName() );
      // --- política
      if ( s.isMissing() ) {
        a.bloqueos.add( s.getName() + " (" + id + "): its plugin is not installed" );
      } else if ( BLOQUEAN.contains( id ) ) {
        a.bloqueos.add( s.getName() + " (" + id + "): it leaves the machine, runs other processes or other "
          + "transformations" );
      }
      // --- conexiones
      DatabaseMeta[] usadas = s.getStepMetaInterface().getUsedDatabaseConnections();
      for ( DatabaseMeta dm : usadas == null ? new DatabaseMeta[ 0 ] : usadas ) {
        if ( dm == null ) {
          continue;
        }
        String tipo = dm.getPluginId() == null ? "?" : dm.getPluginId().toUpperCase( Locale.ROOT );
        boolean archivo = ( "SQLITE".equals( tipo ) || "H2".equals( tipo ) )
          && dm.getAccessType() == DatabaseMeta.TYPE_ACCESS_NATIVE;
        if ( !archivo ) {
          String t = dm.getName() + " (" + tipo + ") used by '" + s.getName() + "'";
          if ( !a.bdNoArchivo.contains( t ) ) {
            a.bdNoArchivo.add( t );
          }
        } else if ( !a.bdArchivo.containsKey( dm.getName() ) ) {
          a.bdArchivo.put( dm.getName(), new File( tm.environmentSubstitute( dm.getDatabaseName() ) ) );
          a.bdTipo.put( dm.getName(), tipo );
          a.bdMeta.put( dm.getName(), dm );
        }
      }
      if ( SQL.contains( id ) && ( usadas == null || usadas.length == 0 ) ) {
        a.bloqueos.add( s.getName() + " (" + id + "): SQL without a file database" );
      }
      // --- tablas (rerun)
      if ( x != null && CARGAS_BD.contains( id ) ) {
        String con = texto( x, "connection" );
        Element lookup = hijo( x, "lookup" );
        String tabla = lookup != null && texto( lookup, "table" ) != null ? texto( lookup, "table" )
          : texto( x, "table" );
        if ( con != null && tabla != null && !tabla.isEmpty() ) {
          List<String> ks = new ArrayList<String>();
          if ( lookup != null ) {
            for ( Element k : hijos( lookup, "key" ) ) {
              String f = texto( k, "field" );
              if ( f != null && !f.isEmpty() ) {
                ks.add( f );
              }
            }
          }
          a.tablas.add( new String[] { con, tm.environmentSubstitute( tabla ) } );
          a.claves.add( ks );
        }
      }
      // --- nodo del balance
      Balance.Nodo n = new Balance.Nodo( s.getName(), id );
      List<StepMeta> principales = tm.findPreviousSteps( s, false );
      for ( StepMeta p : tm.findPreviousSteps( s, true ) ) {
        if ( principales.contains( p ) ) {
          n.previos.add( p.getName() );
        } else {
          n.infos.add( p.getName() );
        }
      }
      StepErrorMeta em = s.getStepErrorMeta();
      String destinoError = em != null && em.isEnabled() && em.getTargetStep() != null ? em.getTargetStep()
        .getName() : null;
      for ( StepMeta d : tm.findNextSteps( s ) ) {
        if ( !d.getName().equals( destinoError ) ) {
          n.destinos++;
        }
      }
      n.enRechazo = rechazo.contains( s.getName() );
      boolean salidaArchivo = SALIDAS_ARCHIVO.contains( id );
      n.escritorRechazo = n.enRechazo && salidaArchivo;
      n.carga = !n.enRechazo && ( salidaArchivo || CARGAS_BD.contains( id ) );
      n.archivo = salidaArchivo;
      n.declaracion = declaracion( s );
      a.nodos.add( n );

      // --- entradas
      if ( x != null && ENTRADAS.contains( id ) ) {
        Entrada e = entrada( tm, s, x, filas, obligatorios );
        if ( e.noEvaluada == null ) {
          Variantes.completarTipos( e.base, conversiones );
        }
        n.entradaArchivo = e.noEvaluada == null && ( soloEntradas == null || soloEntradas.contains( s.getName() ) );
        if ( soloEntradas != null && !soloEntradas.contains( s.getName() ) && e.noEvaluada == null ) {
          e.noEvaluada = "not in 'inputs'";
        }
        a.entradas.add( e );
      }
      // --- salidas
      if ( x != null && salidaArchivo ) {
        Salida o = new Salida();
        o.paso = s.getName();
        o.tipo = id;
        o.rechazo = n.enRechazo;
        o.extension = extension( x );
        Element file = hijo( x, "file" );
        if ( "Y".equalsIgnoreCase( texto( x, "fileNameInField" ) ) || ( file != null && "Y".equalsIgnoreCase(
          texto( file, "fileNameInField" ) ) ) ) {
          a.bloqueos.add( s.getName() + " (" + id + "): its file name comes from a field" );
        }
        if ( "TextFileOutput".equals( id ) ) {
          o.texto = new Variantes.FormatoTexto();
          o.texto.separador = nvl( tm.environmentSubstitute( texto( x, "separator" ) ), ";" );
          o.texto.encierro = nvl( tm.environmentSubstitute( texto( x, "enclosure" ) ), "" );
          o.texto.cabecera = "Y".equalsIgnoreCase( texto( x, "header" ) );
          o.texto.encoding = nvl( texto( x, "encoding" ), "UTF-8" );
          Element campos = hijo( x, "fields" );
          if ( campos != null ) {
            for ( Element f : hijos( campos, "field" ) ) {
              o.campos.add( texto( f, "name" ) );
            }
          }
        }
        a.salidas.add( o );
      }
    }
    return a;
  }

  /** pentaho-live-robust/drops, o silent_filter_drop silenciado con suppress_lint (silenciar = declarar). */
  static String declaracion( StepMeta s ) {
    String d = s.getAttribute( "pentaho-live-robust", "drops" );
    if ( d != null && !d.trim().isEmpty() ) {
      return d.trim();
    }
    String ign = s.getAttribute( Supresiones.GRUPO, Supresiones.IGNORAR );
    if ( ign != null ) {
      for ( String r : ign.split( "," ) ) {
        if ( "silent_filter_drop".equals( r.trim() ) || Supresiones.TODAS.equals( r.trim() ) ) {
          return "filter";
        }
      }
    }
    return null;
  }

  private static Entrada entrada( TransMeta tm, StepMeta s, Element x, int filas, List<String> obligatorios ) {
    Entrada e = new Entrada();
    e.paso = s.getName();
    e.tipo = s.getStepID();
    e.base = new Variantes.Tabla();
    try {
      if ( !tm.findPreviousSteps( s, true ).isEmpty() ) {
        e.noEvaluada = "it takes its file names from a previous step";
        return e;
      }
      String nombre;
      Element file = hijo( x, "file" );
      if ( "CsvInput".equals( e.tipo ) ) {
        nombre = texto( x, "filename" );
      } else {
        nombre = file == null ? null : texto( file, "name" );
      }
      if ( nombre == null || nombre.trim().isEmpty() ) {
        e.noEvaluada = "no file name";
        return e;
      }
      e.archivo = new File( tm.environmentSubstitute( nombre ) );
      String n = e.archivo.getName();
      int p = n.lastIndexOf( '.' );
      e.extension = p > 0 ? n.substring( p + 1 ) : ( "ExcelInput".equals( e.tipo ) ? "xlsx" : "JsonInput".equals(
        e.tipo ) ? "json" : "csv" );
      Element campos = hijo( x, "fields" );
      if ( campos != null ) {
        for ( Element f : hijos( campos, "field" ) ) {
          Variantes.Campo c = new Variantes.Campo( texto( f, "name" ), tipoDe( texto( f, "type" ) ),
            texto( f, "format" ) );
          if ( "JsonInput".equals( e.tipo ) ) {
            c.nombre = claveJson( texto( f, "path" ), c.nombre );
          }
          e.base.campos.add( c );
        }
      }
      if ( !e.archivo.isFile() ) {
        e.noEvaluada = "the file does not exist (" + e.archivo.getName() + ")";
        return e;
      }
      if ( "CsvInput".equals( e.tipo ) || "TextFileInput".equals( e.tipo ) ) {
        if ( "TextFileInput".equals( e.tipo ) && ( "Fixed".equalsIgnoreCase( texto( x, "file_type" ) )
          || ( file != null && "Fixed".equalsIgnoreCase( texto( file, "type" ) ) ) ) ) {
          e.noEvaluada = "fixed-width files are not supported yet";
          return e;
        }
        e.formato = "text";
        e.texto = new Variantes.FormatoTexto();
        e.texto.separador = nvl( tm.environmentSubstitute( texto( x, "separator" ) ), ";" );
        e.texto.encierro = nvl( tm.environmentSubstitute( texto( x, "enclosure" ) ), "" );
        e.texto.cabecera = "Y".equalsIgnoreCase( texto( x, "header" ) );
        e.texto.encoding = nvl( texto( x, "encoding" ), "UTF-8" );
        String nh = texto( x, "nr_headerlines" );
        e.texto.lineasCabecera = nh == null ? 1 : Math.max( 1, entero( nh, 1 ) );
        List<String> cab = new ArrayList<String>();
        e.base.filas.addAll( Variantes.leerTexto( e.archivo, e.texto, filas, cab ) );
        e.base.cabecera = cab.isEmpty() ? null : cab;
        if ( e.base.campos.isEmpty() ) {
          for ( String c : cab ) {
            e.base.campos.add( new Variantes.Campo( c, "String", null ) );
          }
        }
      } else if ( "ExcelInput".equals( e.tipo ) ) {
        e.formato = "excel";
        e.cabecera = "Y".equalsIgnoreCase( texto( x, "header" ) );
        Element hojas = hijo( x, "sheets" );
        Element hoja = hojas == null ? null : hijo( hojas, "sheet" );
        e.hoja = hoja == null ? null : texto( hoja, "name" );
        e.filaInicio = hoja == null ? 0 : entero( texto( hoja, "startrow" ), 0 );
        List<String> cab = new ArrayList<String>();
        e.base.filas.addAll( Variantes.leerExcel( e.archivo, e.hoja, e.filaInicio, e.cabecera, Math.max( 1,
          e.base.campos.size() ), filas, cab ) );
        e.base.cabecera = cab.isEmpty() ? null : cab;
      } else {
        e.formato = "json";
        e.base.filas.addAll( Variantes.leerJson( e.archivo, e.base.campos, filas, McpHttpServer.JSON ) );
      }
      ajustarFilas( e.base );
    } catch ( Exception ex ) {
      e.noEvaluada = "cannot read it: " + ex.getMessage();
    }
    return e;
  }

  /** Cada fila con tantos valores como campos. */
  private static void ajustarFilas( Variantes.Tabla t ) {
    int n = t.campos.size();
    for ( int i = 0; i < t.filas.size(); i++ ) {
      String[] f = t.filas.get( i );
      if ( f.length != n ) {
        t.filas.set( i, Arrays.copyOf( f, n ) );
      }
    }
  }

  /** La clave de un path JSON simple ($.[*].nombre, $..nombre, $.datos[*].nombre). */
  static String claveJson( String path, String def ) {
    if ( path == null || path.trim().isEmpty() ) {
      return def;
    }
    String x = path.trim();
    int i = x.lastIndexOf( '.' );
    String k = i >= 0 ? x.substring( i + 1 ) : x;
    k = k.replace( "['", "" ).replace( "']", "" ).replace( "[*]", "" );
    return k.isEmpty() ? def : k;
  }

  private static String tipoDe( String t ) {
    return t == null || t.trim().isEmpty() || "None".equalsIgnoreCase( t.trim() ) ? "String" : t.trim();
  }

  private static String extension( Element x ) {
    Element file = hijo( x, "file" );
    String e = file == null ? null : texto( file, "extention" );
    if ( e == null && file != null ) {
      e = texto( file, "extension" );
    }
    if ( e == null ) {
      e = texto( x, "extention" );
    }
    if ( e == null ) {
      e = texto( x, "extension" );
    }
    return e == null ? "" : e.trim();
  }

  // ------------------------------------------------------------------ la copia

  /**
   * Escribe la copia de una variante: entradas ensuciadas → sus archivos ({@code archivos}: step → archivo; las demás
   * entradas, a su archivo original resuelto), salidas → {@code dirSalida}, conexiones de archivo → copias en
   * {@code dirDb}.
   */
  static File escribirCopia( Analisis a, Map<String, File> archivos, File dirSalida, File dirDb, File destino )
    throws Exception {
    Document doc = parse( a.xml );
    Element raiz = doc.getDocumentElement();
    for ( Element s : hijos( raiz, "step" ) ) {
      String nombre = texto( s, "name" );
      String tipo = texto( s, "type" );
      Entrada e = a.entrada( nombre );
      if ( e != null && e.archivo != null ) {
        File f = archivos.containsKey( nombre ) ? archivos.get( nombre ) : e.archivo;
        String ruta = f.getAbsolutePath().replace( '\\', '/' );
        if ( "CsvInput".equals( tipo ) ) {
          poner( s, "filename", ruta );
        } else {
          Element file = hijo( s, "file" );
          if ( file != null ) {
            soloPrimero( file );
            poner( file, "name", ruta );
            poner( file, "filemask", "" );
            poner( file, "exclude_filemask", "" );
          }
        }
      }
      if ( SALIDAS_ARCHIVO.contains( tipo ) ) {
        Salida o = null;
        for ( Salida x : a.salidas ) {
          if ( x.paso.equals( nombre ) ) {
            o = x;
          }
        }
        Element file = hijo( s, "file" );
        if ( o != null && file != null ) {
          File f = new File( dirSalida, seguro( nombre ) );
          poner( file, "name", f.getAbsolutePath().replace( '\\', '/' ) );
          for ( String k : new String[] { "add_date", "add_time", "SpecifyFormat", "haspartno", "split",
            "add_to_result_filenames", "append", "DoNotOpenNewFileInit", "do_not_open_newfile_init" } ) {
            if ( hijo( file, k ) != null ) {
              poner( file, k, "N" );
            }
          }
          if ( hijo( file, "create_parent_folder" ) != null ) {
            poner( file, "create_parent_folder", "Y" );
          }
          if ( hijo( s, "create_parent_folder" ) != null ) {
            poner( s, "create_parent_folder", "Y" );
          }
        }
      }
    }
    // --- conexiones de archivo: copia de la BD en dirDb; la conexión se reescribe entera (también si era compartida)
    List<Element> conexiones = hijos( raiz, "connection" );
    for ( Map.Entry<String, File> b : a.bdArchivo.entrySet() ) {
      String nombre = b.getKey();
      File orig = b.getValue();
      File copia = new File( dirDb, seguro( nombre ) + ( "H2".equals( a.bdTipo.get( nombre ) ) ? "" : ".db" ) );
      if ( !dirDb.isDirectory() && !dirDb.mkdirs() ) {
        throw new IOException( "cannot create " + dirDb );
      }
      if ( "H2".equals( a.bdTipo.get( nombre ) ) ) {
        File mv = new File( orig.getPath() + ".mv.db" );
        if ( mv.isFile() && !new File( copia.getPath() + ".mv.db" ).isFile() ) {
          Files.copy( mv.toPath(), new File( copia.getPath() + ".mv.db" ).toPath() );
        }
      } else if ( orig.isFile() && !copia.isFile() ) {
        Files.copy( orig.toPath(), copia.toPath(), StandardCopyOption.COPY_ATTRIBUTES );
      }
      DatabaseMeta dm = (DatabaseMeta) a.bdMeta.get( nombre ).clone();
      dm.setDBName( copia.getAbsolutePath().replace( '\\', '/' ) );
      dm.setShared( false );
      Element nueva = (Element) doc.importNode( parse( dm.getXML() ).getDocumentElement(), true );
      Element vieja = null;
      for ( Element c : conexiones ) {
        if ( nombre.equals( texto( c, "name" ) ) ) {
          vieja = c;
        }
      }
      if ( vieja != null ) {
        raiz.replaceChild( nueva, vieja );
      } else {
        raiz.insertBefore( nueva, raiz.getFirstChild() );
      }
    }
    escribir( doc, destino );
    return destino;
  }

  /** En un {@code <file>} con varias entradas, deja solo la primera de cada tag. */
  private static void soloPrimero( Element file ) {
    Set<String> vistos = new HashSet<String>();
    List<Node> sobran = new ArrayList<Node>();
    for ( Node n = file.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && !vistos.add( n.getNodeName() ) ) {
        sobran.add( n );
      }
    }
    for ( Node n : sobran ) {
      file.removeChild( n );
    }
  }

  // ------------------------------------------------------------------ util

  /** Nombre de archivo seguro a partir de un nombre de step. */
  static String seguro( String s ) {
    String x = s == null ? "x" : s.replaceAll( "[^A-Za-z0-9_.-]+", "_" );
    return x.length() > 60 ? x.substring( 0, 60 ) : x;
  }

  static Document parse( String xml ) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
    f.setXIncludeAware( false );
    f.setExpandEntityReferences( false );
    return f.newDocumentBuilder().parse( new ByteArrayInputStream( xml.getBytes( StandardCharsets.UTF_8 ) ) );
  }

  private static void escribir( Document doc, File destino ) throws Exception {
    Transformer t = TransformerFactory.newInstance().newTransformer();
    t.setOutputProperty( OutputKeys.ENCODING, "UTF-8" );
    StringWriter w = new StringWriter();
    t.transform( new DOMSource( doc ), new StreamResult( w ) );
    File d = destino.getAbsoluteFile().getParentFile();
    if ( d != null && !d.isDirectory() && !d.mkdirs() ) {
      throw new IOException( "cannot create " + d );
    }
    Files.write( destino.toPath(), w.toString().getBytes( StandardCharsets.UTF_8 ) );
  }

  static List<Element> hijos( Element e, String tag ) {
    List<Element> l = new ArrayList<Element>();
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && tag.equals( n.getNodeName() ) ) {
        l.add( (Element) n );
      }
    }
    return l;
  }

  static Element hijo( Element e, String tag ) {
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && tag.equals( n.getNodeName() ) ) {
        return (Element) n;
      }
    }
    return null;
  }

  static String texto( Element e, String tag ) {
    Element h = hijo( e, tag );
    return h == null ? null : h.getTextContent();
  }

  private static void poner( Element e, String tag, String valor ) {
    Element h = hijo( e, tag );
    if ( h == null ) {
      h = e.getOwnerDocument().createElement( tag );
      e.appendChild( h );
    }
    h.setTextContent( valor );
  }

  private static String nvl( String s, String def ) {
    return s == null || s.isEmpty() ? def : s;
  }

  private static int entero( String s, int def ) {
    try {
      return Integer.parseInt( s.trim() );
    } catch ( Exception e ) {
      return def;
    }
  }
}
