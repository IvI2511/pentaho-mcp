package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Las entradas de job que no son transformaciones ni sub-jobs: cada una da el cuerpo de una función que devuelve True
 * (éxito) o False (fallo), como el resultado de la entrada en PDI. Archivos y carpetas con os / shutil / zipfile, bases
 * con SQLAlchemy (pandas) o JDBC (PySpark), procesos con subprocess. Lo que no tiene traducción queda MANUAL con su
 * configuración comentada (sin contraseñas).
 */
final class TradEntradasJob {

  private final JobEntryCopy e;
  private final Element x;
  private final CtxTrad c;
  private final JobMeta jm;
  private final Bloque b;

  private TradEntradasJob( JobEntryCopy e, Element x, CtxTrad c, JobMeta jm ) {
    this.e = e;
    this.x = x;
    this.c = c;
    this.jm = jm;
    this.b = new Bloque( e.getName(), e.getEntry() == null ? "?" : e.getEntry().getPluginId() );
  }

  static Bloque traducir( JobEntryCopy e, Element x, CtxTrad c, JobMeta jm ) {
    TradEntradasJob t = new TradEntradasJob( e, x, c, jm );
    try {
      t.traducir();
    } catch ( Exception | LinkageError ex ) {
      t.b.codigo.clear();
      t.manual( c.t( "No se pudo traducir: ", "Could not translate: ", "Não foi possível traduzir: " ) + ex );
    }
    String d = e.getDescription();
    if ( d != null && !d.trim().isEmpty() ) {
      t.b.explicacion = ( t.b.explicacion == null ? "" : t.b.explicacion + " " ) + d.trim().replace( '\n', ' ' );
    }
    return t.b;
  }

  private void traducir() throws Exception {
    switch ( b.tipo ) {
      case "SPECIAL": especial(); break;
      case "SUCCESS": exito(); break;
      case "ABORT": abortar(); break;
      case "WRITE_TO_LOG": escribirLog(); break;
      case "SET_VARIABLES": variables(); break;
      case "FILES_EXIST": archivosExisten(); break;
      case "FILE_EXISTS": archivoExiste(); break;
      case "FOLDER_IS_EMPTY": carpetaVacia(); break;
      case "CREATE_FOLDER": crearCarpeta(); break;
      case "CREATE_FILE": crearArchivo(); break;
      case "DELETE_FILE": borrarArchivo(); break;
      case "DELETE_FILES": borrarArchivos(); break;
      case "DELETE_FOLDERS": borrarCarpetas(); break;
      case "COPY_FILES": copiar( false ); break;
      case "MOVE_FILES": copiar( true ); break;
      case "ZIP_FILE": comprimir(); break;
      case "UNZIP": descomprimir(); break;
      case "FILE_COMPARE": compararArchivos(); break;
      case "DELAY": esperar(); break;
      case "WAIT_FOR_FILE": esperarArchivo(); break;
      case "WRITE_TO_FILE": escribirArchivo(); break;
      case "SQL": sql(); break;
      case "TABLE_EXISTS": tablaExiste(); break;
      case "COLUMNS_EXIST": columnasExisten(); break;
      case "CHECK_DB_CONNECTIONS": conexiones(); break;
      case "TRUNCATE_TABLES": vaciarTablas(); break;
      case "EVAL_TABLE_CONTENT": contarFilas(); break;
      case "SIMPLE_EVAL": evaluar(); break;
      case "EVAL_FILES_METRICS": metricasArchivos(); break;
      case "MAIL": correo(); break;
      case "HTTP": http(); break;
      case "WEBSERVICE_AVAILABLE": servicioWeb(); break;
      case "SHELL": shell(); break;
      case "PING": ping(); break;
      case "TELNET": telnet(); break;
      case "MSGBOX_INFO": mensaje(); break;
      case "EVAL": javascript(); break;
      case "ADD_RESULT_FILENAMES": case "DELETE_RESULT_FILENAMES": case "COPY_MOVE_RESULT_FILENAMES":
        archivosResultado(); break;
      default:
        manual( c.t( "La entrada «" + b.tipo + "» todavía no tiene traducción: complétala a mano.",
          "Entry type '" + b.tipo + "' has no translation yet: complete it by hand.",
          "A entrada «" + b.tipo + "» ainda não tem tradução: complete-a à mão." ) );
    }
  }

  // ================================================================ utilidades

  private String s( String tag ) {
    return CtxTrad.hijo( x, tag );
  }

  /** Valor con variables como expresión Python. */
  private String v( String tag ) {
    return c.texto( s( tag ) );
  }

  private boolean si( String tag ) {
    String t = s( tag ).trim();
    return "Y".equalsIgnoreCase( t ) || "T".equalsIgnoreCase( t ) || "true".equalsIgnoreCase( t );
  }

  private static String py( boolean v ) {
    return v ? "True" : "False";
  }

  private int entero( String tag, int def ) {
    try {
      return Integer.parseInt( s( tag ).trim() );
    } catch ( NumberFormatException ex ) {
      return def;
    }
  }

  /** Las filas de &lt;fields&gt;&lt;field&gt;: cada una, sus hijos (etiqueta → texto). */
  private List<Map<String, String>> campos() {
    List<Map<String, String>> r = new ArrayList<>();
    Node fs = XMLHandler.getSubNode( x, "fields" );
    if ( fs == null ) {
      fs = XMLHandler.getSubNode( x, "connections" );
    }
    if ( fs == null ) {
      return r;
    }
    for ( Node f = fs.getFirstChild(); f != null; f = f.getNextSibling() ) {
      if ( !( f instanceof Element ) ) {
        continue;
      }
      Map<String, String> m = new LinkedHashMap<>();
      for ( Node h = f.getFirstChild(); h != null; h = h.getNextSibling() ) {
        if ( h instanceof Element ) {
          m.put( h.getNodeName(), h.getTextContent() == null ? "" : h.getTextContent() );
        }
      }
      r.add( m );
    }
    return r;
  }

  private static String de( Map<String, String> m, String... tags ) {
    for ( String t : tags ) {
      String v = m.get( t );
      if ( v != null ) {
        return v;
      }
    }
    return "";
  }

  private void manual( String motivo ) {
    b.manual( motivo );
    b.l( "# TODO: " + c.t( "configuración de la entrada en PDI:", "entry settings in PDI:",
      "configuração da entrada no PDI:" ) );
    try {
      int n = 0;
      for ( String l : e.getXML().split( "\\r?\\n" ) ) {
        String tl = l.trim();
        if ( tl.isEmpty() || tl.toLowerCase().contains( "password" ) || tl.matches( "</?entry>|<(name|type|xloc|yloc|"
          + "nr|draw|parallel|attributes|attributes_kjc|description)\\b.*|</?attributes>|<attributes/>" ) ) {
          continue;
        }
        if ( ++n > 40 ) {
          b.l( "#   ..." );
          break;
        }
        b.l( "#   " + l.replaceAll( "\\s+$", "" ) );
      }
    } catch ( Exception ex ) {
      // sin XML
    }
    b.l( "log.warning(" + CtxTrad.lit( c.t( "Entrada sin traducir: ", "Untranslated entry: ", "Entrada sem tradução: " )
      + e.getName() ) + ")" );
    b.l( "return True" );
  }

  private String expl( String es, String en, String pt ) {
    b.explicacion = c.t( es, en, pt );
    return b.explicacion;
  }

  private void ayudanteArchivos() {
    c.importar( "import re" );
    c.ayudante( "archivos", String.join( "\n",
      "def archivos(carpeta, mascara=None, subcarpetas=False):",
      "    \"\"\"" + c.t( "Archivos de la carpeta cuyo nombre cumple la expresión regular (las máscaras de PDI son "
        + "regex).", "Files in the folder whose name matches the regular expression (PDI masks are regexes).",
        "Arquivos da pasta cujo nome cumpre a expressão regular (as máscaras do PDI são regex)." ) + "\"\"\"",
      "    patron = re.compile(mascara) if mascara else None",
      "    for raiz, carpetas, nombres in os.walk(carpeta):",
      "        carpetas.sort()",
      "        for n in sorted(nombres):",
      "            if patron is None or patron.fullmatch(n):",
      "                yield os.path.join(raiz, n)",
      "        if not subcarpetas:",
      "            break" ) );
  }

  // ================================================================ generales

  private void especial() {
    boolean dummy = "Y".equalsIgnoreCase( s( "dummy" ) );
    if ( dummy ) {
      expl( "Dummy: no hace nada.", "Dummy: does nothing.", "Dummy: não faz nada." );
    } else {
      expl( "START: el comienzo del job (aquí se vuelve a él por un bucle).", "START: the beginning of the job (the "
        + "flow loops back to it here).", "START: o início do job (aqui volta-se a ele por um laço)." );
    }
    b.l( "return True" );
  }

  private void exito() {
    expl( "Success: termina este camino del job con éxito.", "Success: ends this path of the job successfully.",
      "Success: termina este caminho do job com sucesso." );
    b.l( "return True" );
  }

  private void abortar() {
    expl( "Abort job: corta el job entero con error (ningún otro camino sigue).", "Abort job: stops the whole job "
      + "with an error (no other path goes on).", "Abort job: interrompe o job inteiro com erro (nenhum outro caminho "
        + "continua)." );
    String m = s( "message" );
    b.l( "raise Abortar(" + ( m.isEmpty() ? CtxTrad.lit( c.t( "Job abortado", "Job aborted", "Job abortado" ) )
      : c.texto( m ) ) + ")" );
  }

  private void escribirLog() {
    expl( "Write to log: escribe un mensaje en el log.", "Write to log: writes a message to the log.",
      "Write to log: escreve uma mensagem no log." );
    String nivel = s( "loglevel" ).toLowerCase();
    String f = nivel.equals( "error" ) ? "error" : nivel.matches( "detailed|debug|rowlevel" ) ? "debug" : "info";
    String asunto = s( "logsubject" );
    String texto = asunto.isEmpty() ? s( "logmessage" ) : asunto + " - " + s( "logmessage" );
    if ( nivel.equals( "nothing" ) ) {
      b.l( "# " + c.t( "nivel «Nothing»: PDI no lo escribe", "level 'Nothing': PDI does not write it",
        "nível «Nothing»: o PDI não o escreve" ) );
      b.l( "return True" );
      return;
    }
    b.l( "log." + f + "(" + c.texto( texto ) + ")" );
    b.l( "return True" );
  }

  private void variables() {
    expl( "Set variables: da valor a variables (en VARS; las de ámbito JVM, también en os.environ).",
      "Set variables: sets variables (in VARS; JVM scope ones also in os.environ).",
      "Set variables: dá valor a variáveis (em VARS; as de âmbito JVM, também em os.environ)." );
    boolean sustituir = !"N".equalsIgnoreCase( s( "replacevars" ) );
    String archivo = s( "filename" );
    if ( !archivo.isEmpty() ) {
      String tipo = s( "file_variable_type" );
      b.l( "# " + c.t( "variables del archivo de propiedades", "variables from the properties file",
        "variáveis do arquivo de propriedades" ) + " (" + tipo + ")" );
      b.l( "with open(" + c.texto( archivo ) + ", encoding=\"utf-8\") as f:" );
      b.l( "    for linea in f:" );
      b.l( "        linea = linea.strip()" );
      b.l( "        if linea and not linea.startswith((\"#\", \"!\")) and \"=\" in linea:" );
      b.l( "            k, valor = (p.strip() for p in linea.split(\"=\", 1))" );
      b.l( "            VARS[k] = valor" );
      if ( "JVM".equalsIgnoreCase( tipo ) ) {
        b.l( "            os.environ[k] = valor" );
      }
    }
    for ( Map<String, String> f : campos() ) {
      String n = de( f, "variable_name", "name" );
      if ( n.isEmpty() ) {
        continue;
      }
      String valor = de( f, "variable_value", "value" );
      String tipo = de( f, "variable_type", "type" );
      String expr = sustituir ? c.texto( valor ) : CtxTrad.lit( valor );
      b.l( "VARS[" + CtxTrad.lit( n ) + "] = " + expr + ( tipo.isEmpty() ? "" : "  # " + tipo ) );
      if ( "JVM".equalsIgnoreCase( tipo ) ) {
        b.l( "os.environ[" + CtxTrad.lit( n ) + "] = VARS[" + CtxTrad.lit( n ) + "]" );
      }
      if ( tipo.toUpperCase().matches( "PARENT_JOB|GRAND_PARENT_JOB" ) ) {
        b.aproximado( c.t( "La variable " + n + " tiene ámbito " + tipo + ": en Python todas van a VARS (un solo "
          + "ámbito para todo el script).", "Variable " + n + " has scope " + tipo + ": in Python they all go to "
            + "VARS (one scope for the whole script).", "A variável " + n + " tem âmbito " + tipo + ": em Python "
              + "todas vão para VARS (um só âmbito para todo o script)." ) );
      }
    }
    b.l( "return True" );
  }

  // ================================================================ archivos y carpetas

  private void archivosExisten() {
    expl( "Checks if files exist: éxito si existen todos los archivos.", "Checks if files exist: success if all the "
      + "files exist.", "Checks if files exist: sucesso se existirem todos os arquivos." );
    List<String> fs = new ArrayList<>();
    for ( Map<String, String> f : campos() ) {
      String n = de( f, "name", "filename" );
      if ( !n.isEmpty() ) {
        fs.add( c.texto( n ) );
      }
    }
    if ( !s( "filename" ).isEmpty() ) {
      fs.add( v( "filename" ) );
    }
    b.l( "rutas = [" );
    for ( String f : fs ) {
      b.l( "    " + f + "," );
    }
    b.l( "]" );
    b.l( "faltan = [r for r in rutas if not os.path.exists(r)]" );
    b.l( "for r in faltan:" );
    b.l( "    log.warning(" + CtxTrad.lit( c.t( "No existe %s", "%s does not exist", "Não existe %s" ) ) + ", r)" );
    b.l( "return not faltan" );
  }

  private void archivoExiste() {
    expl( "File exists: éxito si el archivo existe.", "File exists: success if the file exists.",
      "File exists: sucesso se o arquivo existir." );
    b.l( "return os.path.exists(" + v( "filename" ) + ")" );
  }

  private void carpetaVacia() {
    expl( "Check if a folder is empty: éxito si la carpeta no tiene archivos.", "Check if a folder is empty: success "
      + "if the folder has no files.", "Check if a folder is empty: sucesso se a pasta não tiver arquivos." );
    ayudanteArchivos();
    String masc = si( "specify_wildcard" ) && !s( "wildcard" ).isEmpty() ? v( "wildcard" ) : "None";
    b.l( "carpeta = " + v( "foldername" ) );
    b.l( "if not os.path.isdir(carpeta):" );
    b.l( "    raise FileNotFoundError(carpeta)" );
    b.l( "return next(archivos(carpeta, " + masc + ", subcarpetas=" + py( si( "include_subfolders" ) ) + "), None) "
      + "is None" );
  }

  private void crearCarpeta() {
    expl( "Create a folder: crea la carpeta.", "Create a folder: creates the folder.", "Create a folder: cria a pasta." );
    b.l( "carpeta = " + v( "foldername" ) );
    b.l( "if os.path.isdir(carpeta):" );
    if ( si( "fail_of_folder_exists" ) ) {
      b.l( "    log.error(" + CtxTrad.lit( c.t( "La carpeta ya existe: %s", "The folder already exists: %s",
        "A pasta já existe: %s" ) ) + ", carpeta)" );
      b.l( "    return False" );
    } else {
      b.l( "    return True" );
    }
    b.l( "os.makedirs(carpeta)" );
    b.l( "return True" );
  }

  private void crearArchivo() {
    expl( "Create file: crea un archivo vacío.", "Create file: creates an empty file.",
      "Create file: cria um arquivo vazio." );
    b.l( "archivo = " + v( "filename" ) );
    b.l( "if os.path.exists(archivo):" );
    b.l( "    return " + py( !si( "fail_if_file_exists" ) ) );
    b.l( "os.makedirs(os.path.dirname(os.path.abspath(archivo)), exist_ok=True)" );
    b.l( "open(archivo, \"w\").close()" );
    b.l( "return True" );
  }

  private void borrarArchivo() {
    expl( "Delete file: borra el archivo.", "Delete file: deletes the file.", "Delete file: apaga o arquivo." );
    b.l( "archivo = " + v( "filename" ) );
    b.l( "if not os.path.exists(archivo):" );
    b.l( "    return " + py( !si( "fail_if_file_not_exists" ) ) );
    b.l( "os.remove(archivo)" );
    b.l( "return True" );
  }

  private void borrarArchivos() {
    expl( "Delete files: borra los archivos (o los de cada carpeta que cumplen la máscara).", "Delete files: deletes "
      + "the files (or those in each folder matching the mask).", "Delete files: apaga os arquivos (ou os de cada "
        + "pasta que cumprem a máscara)." );
    ayudanteArchivos();
    resultadoAnterior();
    boolean sub = si( "include_subfolders" );
    for ( Map<String, String> f : campos() ) {
      String ruta = c.texto( de( f, "name" ) );
      String masc = de( f, "filemask" ).isEmpty() ? "None" : c.texto( de( f, "filemask" ) );
      b.l( "ruta = " + ruta );
      b.l( "if os.path.isfile(ruta):" );
      b.l( "    os.remove(ruta)" );
      b.l( "elif os.path.isdir(ruta):" );
      b.l( "    for a in list(archivos(ruta, " + masc + ", subcarpetas=" + py( sub ) + ")):" );
      b.l( "        os.remove(a)" );
    }
    b.l( "return True" );
  }

  private void borrarCarpetas() {
    expl( "Delete folders: borra las carpetas con todo su contenido.", "Delete folders: deletes the folders and all "
      + "their contents.", "Delete folders: apaga as pastas com todo o seu conteúdo." );
    c.importar( "import shutil" );
    resultadoAnterior();
    for ( Map<String, String> f : campos() ) {
      b.l( "shutil.rmtree(" + c.texto( de( f, "name" ) ) + ", ignore_errors=True)" );
    }
    if ( !"success_if_no_errors".equals( s( "success_condition" ) ) ) {
      b.aproximado( c.t( "Condición de éxito «" + s( "success_condition" ) + "»: aquí, éxito si no hay errores.",
        "Success condition '" + s( "success_condition" ) + "': here, success if there are no errors.",
        "Condição de sucesso «" + s( "success_condition" ) + "»: aqui, sucesso se não houver erros." ) );
    }
    b.l( "return True" );
  }

  private void resultadoAnterior() {
    if ( si( "arg_from_previous" ) ) {
      b.aproximado( c.t( "En PDI las rutas salen de las filas del resultado anterior: aquí se usan las de la entrada.",
        "In PDI the paths come from the previous result rows: here the entry's own are used.",
        "No PDI os caminhos vêm das linhas do resultado anterior: aqui usam-se os da entrada." ) );
    }
  }

  private void copiar( boolean mover ) {
    if ( mover ) {
      expl( "Move files: mueve archivos (o los de una carpeta que cumplen la máscara).", "Move files: moves files "
        + "(or those in a folder matching the mask).", "Move files: move arquivos (ou os de uma pasta que cumprem a "
          + "máscara)." );
    } else {
      expl( "Copy files: copia archivos (o los de una carpeta que cumplen la máscara).", "Copy files: copies files "
        + "(or those in a folder matching the mask).", "Copy files: copia arquivos (ou os de uma pasta que cumprem a "
          + "máscara)." );
    }
    ayudanteArchivos();
    c.importar( "import shutil" );
    c.ayudante( "copiar_archivos", String.join( "\n",
      "def copiar_archivos(origen, destino, mascara=None, subcarpetas=False, sobrescribir=False, crear_destino=False,",
      "                    destino_es_archivo=False, mover=False):",
      "    \"\"\"" + c.t( "Copia (o mueve) un archivo, o los archivos de una carpeta que cumplen la máscara, como Copy "
        + "/ Move files.", "Copies (or moves) a file, or the files of a folder matching the mask, like Copy / Move "
          + "files.", "Copia (ou move) um arquivo, ou os arquivos de uma pasta que cumprem a máscara, como Copy / "
            + "Move files." ) + "\"\"\"",
      "    if os.path.isfile(origen):",
      "        destino_final = destino if destino_es_archivo else os.path.join(destino, os.path.basename(origen))",
      "        pares = [(origen, destino_final)]",
      "    elif os.path.isdir(origen):",
      "        pares = [(a, os.path.join(destino, os.path.relpath(a, origen)))",
      "                 for a in archivos(origen, mascara, subcarpetas)]",
      "    else:",
      "        raise FileNotFoundError(origen)",
      "    carpeta = os.path.dirname(destino) if destino_es_archivo else destino",
      "    if carpeta and not os.path.isdir(carpeta):",
      "        if not crear_destino:",
      "            raise FileNotFoundError(carpeta)",
      "        os.makedirs(carpeta)",
      "    for desde, hasta in pares:",
      "        if os.path.exists(hasta) and not sobrescribir:",
      "            continue",
      "        os.makedirs(os.path.dirname(hasta), exist_ok=True)",
      "        shutil.copy2(desde, hasta)",
      "        if mover:",
      "            os.remove(desde)",
      "    return len(pares)" ) );
    resultadoAnterior();
    boolean sobrescribir;
    if ( mover ) {
      String si = s( "iffileexists" );
      sobrescribir = "overwrite_file".equals( si );
      if ( !si.matches( "do_nothing|overwrite_file|" ) ) {
        b.aproximado( c.t( "Si el archivo ya existe («" + si + "»): aquí se deja como está.", "If the file already "
          + "exists ('" + si + "'): here it is left as is.", "Se o arquivo já existir («" + si + "»): aqui fica como "
            + "está." ) );
      }
    } else {
      sobrescribir = si( "overwrite_files" );
    }
    String sub = py( si( "include_subfolders" ) );
    for ( Map<String, String> f : campos() ) {
      String o = c.texto( de( f, "source_filefolder", "name" ) );
      String d = c.texto( de( f, "destination_filefolder" ) );
      String w = de( f, "wildcard" ).isEmpty() ? "None" : c.texto( de( f, "wildcard" ) );
      b.l( "copiar_archivos(" + o + ", " + d + ", " + w + ", subcarpetas=" + sub + ", sobrescribir=" + py(
        sobrescribir ) + "," );
      b.l( "                crear_destino=" + py( si( "create_destination_folder" ) ) + ", destino_es_archivo=" + py(
        si( "destination_is_a_file" ) ) + ( mover || si( "remove_source_files" ) ? ", mover=True" : "" ) + ")" );
    }
    b.l( "return True" );
  }

  private void comprimir() {
    expl( "Zip file: comprime archivos en un .zip.", "Zip file: compresses files into a .zip.",
      "Zip file: comprime arquivos num .zip." );
    ayudanteArchivos();
    c.importar( "import zipfile" );
    c.importar( "import shutil" );
    String w = s( "wildcard" ).isEmpty() ? "None" : v( "wildcard" );
    String ex = s( "wildcardexclude" ).isEmpty() ? "None" : v( "wildcardexclude" );
    b.l( "destino = " + v( "zipfilename" ) );
    b.l( "origen = " + v( "sourcedirectory" ) );
    int si = entero( "ifzipfileexists", 2 );
    b.l( "modo = \"w\"" );
    b.l( "if os.path.exists(destino):" );
    switch ( si ) {
      case 0:
        c.importar( "import datetime" );
        b.l( "    raiz, ext = os.path.splitext(destino)" );
        b.l( "    destino = raiz + datetime.datetime.now().strftime(\"_%Y%m%d_%H%M%S\") + ext" );
        break;
      case 1:
        b.l( "    modo = \"a\"" );
        break;
      case 3:
        b.l( "    return False" );
        break;
      default:
        b.l( "    return True" );
    }
    if ( si( "createparentfolder" ) ) {
      b.l( "os.makedirs(os.path.dirname(os.path.abspath(destino)), exist_ok=True)" );
    }
    b.l( "if os.path.isfile(origen):" );
    b.l( "    lista, raiz = [origen], os.path.dirname(origen)" );
    b.l( "else:" );
    b.l( "    lista, raiz = list(archivos(origen, " + w + ", subcarpetas=" + py( si( "include_subfolders" ) ) + ")), "
      + "origen" );
    if ( !"None".equals( ex ) ) {
      b.l( "lista = [a for a in lista if not re.fullmatch(" + ex + ", os.path.basename(a))]" );
    }
    b.l( "with zipfile.ZipFile(destino, modo, zipfile.ZIP_DEFLATED) as z:" );
    b.l( "    for a in lista:" );
    b.l( "        z.write(a, os.path.relpath(a, raiz))" );
    int despues = entero( "afterzip", 0 );
    if ( despues == 1 ) {
      b.l( "for a in lista:" );
      b.l( "    os.remove(a)" );
    } else if ( despues == 2 ) {
      b.l( "os.makedirs(" + v( "movetodirectory" ) + ", exist_ok=True)" );
      b.l( "for a in lista:" );
      b.l( "    shutil.move(a, " + v( "movetodirectory" ) + ")" );
    }
    b.l( "return True" );
  }

  private void descomprimir() {
    expl( "Unzip file: extrae los archivos de un .zip.", "Unzip file: extracts the files of a .zip.",
      "Unzip file: extrai os arquivos de um .zip." );
    c.importar( "import zipfile" );
    c.importar( "import re" );
    String w = s( "wildcard" ).isEmpty() ? "None" : v( "wildcard" );
    String ex = s( "wildcardexclude" ).isEmpty() ? "None" : v( "wildcardexclude" );
    String si = s( "iffileexists" ).toUpperCase();
    b.l( "carpeta = " + v( "targetdirectory" ) );
    if ( si( "createfolder" ) ) {
      b.l( "os.makedirs(carpeta, exist_ok=True)" );
    }
    b.l( "zip_origen = " + v( "zipfilename" ) );
    if ( !"None".equals( w ) || !"None".equals( ex ) ) {
      c.importar( "import pathlib" );
      b.l( "# " + c.t( "PDI compara las máscaras con la URI completa de cada archivo del zip (zip:file:///...!/nombre)",
        "PDI matches the masks against the full URI of each file in the zip (zip:file:///...!/name)",
        "O PDI compara as máscaras com a URI completa de cada arquivo do zip (zip:file:///...!/nome)" ) );
      b.l( "uri = \"zip:\" + pathlib.Path(zip_origen).resolve().as_uri() + \"!/\"" );
    }
    b.l( "with zipfile.ZipFile(zip_origen) as z:" );
    b.l( "    for nombre in z.namelist():" );
    StringBuilder cond = new StringBuilder( "nombre.endswith(\"/\")" );
    if ( !"None".equals( w ) ) {
      cond.append( " or not re.fullmatch(" ).append( w ).append( ", uri + nombre)" );
    }
    if ( !"None".equals( ex ) ) {
      cond.append( " or re.fullmatch(" ).append( ex ).append( ", uri + nombre)" );
    }
    b.l( "        if " + cond + ":" );
    b.l( "            continue" );
    if ( !si.startsWith( "OVERWRITE" ) ) {
      b.l( "        if os.path.exists(os.path.join(carpeta, nombre)):" );
      b.l( "            " + ( "FAIL".equals( si ) ? "return False" : "continue" ) );
    }
    b.l( "        z.extract(nombre, carpeta)" );
    int despues = entero( "afterunzip", 0 );
    if ( despues == 1 ) {
      b.l( "os.remove(zip_origen)" );
    } else if ( despues == 2 ) {
      c.importar( "import shutil" );
      b.l( "shutil.move(zip_origen, " + v( "movetodirectory" ) + ")" );
    }
    if ( si.matches( "UNIQ|OVERWRITE_DIFF_SIZE|OVERWRITE_EQUAL_SIZE|OVERWRITE_ZIP_BIG|OVERWRITE_ZIP_BIG_EQUAL|"
      + "OVERWRITE_ZIP_SMALL|OVERWRITE_ZIP_SMALL_EQUAL" ) ) {
      b.aproximado( c.t( "Si el archivo ya existe («" + si + "»): aquí se sobrescribe o se salta sin comparar "
        + "tamaños.", "If the file already exists ('" + si + "'): here it is overwritten or skipped without comparing "
          + "sizes.", "Se o arquivo já existir («" + si + "»): aqui é sobrescrito ou saltado sem comparar "
            + "tamanhos." ) );
    }
    b.l( "return True" );
  }

  private void compararArchivos() {
    expl( "File compare: éxito si los dos archivos tienen el mismo contenido.", "File compare: success if both files "
      + "have the same content.", "File compare: sucesso se os dois arquivos tiverem o mesmo conteúdo." );
    c.importar( "import filecmp" );
    b.l( "return filecmp.cmp(" + v( "filename1" ) + ", " + v( "filename2" ) + ", shallow=False)" );
  }

  private void esperar() {
    expl( "Wait for: espera un tiempo.", "Wait for: waits for a while.", "Wait for: espera um tempo." );
    c.importar( "import time" );
    int escala = entero( "scaletime", 0 );
    String f = escala == 1 ? " * 60" : escala == 2 ? " * 3600" : "";
    String t = s( "maximumTimeout" ).trim();
    b.l( "time.sleep(float(" + ( t.matches( "[0-9.]+" ) ? t : c.texto( t ) ) + ")" + f + ")" );
    b.l( "return True" );
  }

  private void esperarArchivo() {
    expl( "Wait for file: espera hasta que el archivo exista (o se acabe el tiempo).", "Wait for file: waits until "
      + "the file exists (or time runs out).", "Wait for file: espera até que o arquivo exista (ou acabe o "
        + "tempo)." );
    c.importar( "import time" );
    c.ayudante( "esperar_archivo", String.join( "\n",
      "def esperar_archivo(ruta, maximo=0, ciclo=60, exito_si_expira=False):",
      "    \"\"\"" + c.t( "Espera a que exista el archivo, mirando cada «ciclo» segundos; maximo=0: sin límite.",
        "Waits for the file to exist, checking every 'ciclo' seconds; maximo=0: no limit.",
        "Espera que o arquivo exista, verificando a cada «ciclo» segundos; maximo=0: sem limite." ) + "\"\"\"",
      "    inicio = time.monotonic()",
      "    while not os.path.exists(ruta):",
      "        if maximo and time.monotonic() - inicio >= maximo:",
      "            return exito_si_expira",
      "        time.sleep(max(ciclo, 1))",
      "    return True" ) );
    b.l( "return esperar_archivo(" + v( "filename" ) + ", maximo=" + entero( "maximum_timeout", 0 ) + ", ciclo="
      + entero( "check_cycle_time", 60 ) + ", exito_si_expira=" + py( si( "success_on_timeout" ) ) + ")" );
    if ( si( "file_size_check" ) ) {
      b.aproximado( c.t( "PDI además espera a que el tamaño del archivo deje de cambiar.", "PDI also waits until the "
        + "file size stops changing.", "O PDI também espera que o tamanho do arquivo deixe de mudar." ) );
    }
  }

  private void escribirArchivo() {
    expl( "Write to file: escribe un texto en un archivo.", "Write to file: writes a text to a file.",
      "Write to file: escreve um texto num arquivo." );
    String enc = s( "encoding" ).isEmpty() ? "utf-8" : s( "encoding" );
    b.l( "archivo = " + v( "filename" ) );
    if ( si( "createParentFolder" ) ) {
      b.l( "os.makedirs(os.path.dirname(os.path.abspath(archivo)), exist_ok=True)" );
    }
    b.l( "with open(archivo, " + ( si( "appendFile" ) ? "\"a\"" : "\"w\"" ) + ", encoding=" + CtxTrad.lit( enc )
      + ", newline=\"\") as f:" );
    b.l( "    f.write(" + v( "content" ) + ")" );
    b.l( "return True" );
  }

  private void archivosResultado() {
    expl( "Lista de archivos del resultado: el script no la lleva.", "Result file list: the script does not keep it.",
      "Lista de arquivos do resultado: o script não a mantém." );
    b.aproximado( c.t( "La lista de archivos del resultado de PDI no existe en el script: la entrada no hace nada.",
      "PDI's result file list does not exist in the script: the entry does nothing.",
      "A lista de arquivos do resultado do PDI não existe no script: a entrada não faz nada." ) );
    b.l( "return True" );
  }

  // ================================================================ bases de datos

  private DatabaseMeta db( String nombre ) {
    DatabaseMeta d = c.conexiones.get( nombre );
    if ( d == null ) {
      d = jm.findDatabase( nombre );
      if ( d == null ) {
        throw new IllegalStateException( c.t( "no existe la conexión ", "connection not found: ",
          "conexão não encontrada: " ) + nombre );
      }
      d = TraductorJob.neutra( d );
      c.conexiones.put( d.getName(), d );
    }
    return d;
  }

  private void ayudanteSql() {
    c.ayudante( "sentencias_sql", String.join( "\n",
      "def sentencias_sql(sql):",
      "    \"\"\"" + c.t( "Separa un script SQL en sentencias por «;» (fuera de comillas), como Execute SQL script.",
        "Splits an SQL script into statements on ';' (outside quotes), like Execute SQL script.",
        "Separa um script SQL em instruções por «;» (fora de aspas), como Execute SQL script." ) + "\"\"\"",
      "    partes, actual, comilla = [], [], None",
      "    for ch in sql:",
      "        if comilla:",
      "            comilla = None if ch == comilla else comilla",
      "        elif ch in \"'\\\"\":",
      "            comilla = ch",
      "        elif ch == \";\":",
      "            partes.append(\"\".join(actual))",
      "            actual = []",
      "            continue",
      "        actual.append(ch)",
      "    partes.append(\"\".join(actual))",
      "    return [p.strip() for p in partes if p.strip()]" ) );
    if ( c.pandas() ) {
      c.ayudante( "ejecutar_sql", String.join( "\n",
        "def ejecutar_sql(engine, sql, una_sola=False):",
        "    \"\"\"" + c.t( "Ejecuta un script SQL en una transacción (sentencia a sentencia, o entero).",
          "Runs an SQL script in a transaction (statement by statement, or whole).",
          "Executa um script SQL numa transação (instrução a instrução, ou inteiro)." ) + "\"\"\"",
        "    with engine.begin() as con:",
        "        for sentencia in [sql] if una_sola else sentencias_sql(sql):",
        "            con.exec_driver_sql(sentencia)" ) );
    } else {
      c.ayudante( "ejecutar_sql", String.join( "\n",
        "def ejecutar_sql(jdbc, sql, una_sola=False):",
        "    \"\"\"" + c.t( "Ejecuta un script SQL por JDBC con la JVM de Spark (el driver JDBC tiene que estar en su "
          + "classpath).", "Runs an SQL script over JDBC with Spark's JVM (the JDBC driver must be on its classpath).",
          "Executa um script SQL por JDBC com a JVM do Spark (o driver JDBC tem de estar no seu classpath)." )
          + "\"\"\"",
        "    con = spark._jvm.java.sql.DriverManager.getConnection(jdbc[\"url\"], jdbc[\"user\"], jdbc[\"password\"])",
        "    try:",
        "        st = con.createStatement()",
        "        for sentencia in [sql] if una_sola else sentencias_sql(sql):",
        "            st.execute(sentencia)",
        "    finally:",
        "        con.close()" ) );
      b.aproximado( c.t( "PySpark no ejecuta SQL de modificación: se usa JDBC con la JVM de Spark (spark._jvm); el "
        + "driver tiene que estar en spark.driver.extraClassPath.", "PySpark does not run modifying SQL: JDBC is used "
          + "through Spark's JVM (spark._jvm); the driver must be on spark.driver.extraClassPath.",
        "O PySpark não executa SQL de modificação: usa-se JDBC com a JVM do Spark (spark._jvm); o driver tem de "
          + "estar em spark.driver.extraClassPath." ) );
    }
  }

  /** Un texto SQL largo como cadena de triple comilla (legible), o literal normal si no se puede. */
  private static String largo( String sql ) {
    if ( sql.contains( "\n" ) && !sql.contains( "\"\"\"" ) && !sql.contains( "\\" ) && !sql.endsWith( "\"" ) ) {
      return "\"\"\"\n" + sql + "\"\"\"";
    }
    return CtxTrad.lit( sql );
  }

  private void sql() {
    expl( "Execute SQL script: ejecuta sentencias SQL en la base.", "Execute SQL script: runs SQL statements on the "
      + "database.", "Execute SQL script: executa instruções SQL na base." );
    ayudanteSql();
    String con = c.conexion( db( s( "connection" ) ) );
    String texto;
    if ( si( "sqlfromfile" ) ) {
      b.l( "with open(" + v( "sqlfilename" ) + ", encoding=\"utf-8\") as f:" );
      b.l( "    sql = f.read()" );
      texto = "sql";
    } else {
      String q = s( "sql" );
      String expr = si( "useVariableSubstitution" ) ? c.texto( q ) : largo( q );
      List<String> ls = new ArrayList<>( java.util.Arrays.asList( ( "sql = " + expr ).split( "\n", -1 ) ) );
      for ( String l : ls ) {
        b.codigo.add( l );
      }
      texto = "sql";
    }
    b.l( "ejecutar_sql(" + con + ", " + texto + ( si( "sendOneStatement" ) ? ", una_sola=True" : "" ) + ")" );
    b.l( "return True" );
    b.escribe = true;
  }

  private String tablaSql( DatabaseMeta d, String esquema, String tabla ) {
    if ( esquema.contains( "$" ) || tabla.contains( "$" ) ) {
      return c.texto( ( esquema.isEmpty() ? "" : esquema + "." ) + tabla );
    }
    return CtxTrad.lit( d.getQuotedSchemaTableCombination( esquema, tabla ) );
  }

  private void tablaExiste() {
    expl( "Table exists: éxito si la tabla existe.", "Table exists: success if the table exists.",
      "Table exists: sucesso se a tabela existir." );
    DatabaseMeta d = db( s( "connection" ) );
    String con = c.conexion( d );
    if ( c.pandas() ) {
      c.importar( "import sqlalchemy" );
      b.l( "return sqlalchemy.inspect(" + con + ").has_table(" + v( "tablename" ) + ", schema=" + ( s( "schemaname" )
        .isEmpty() ? "None" : v( "schemaname" ) ) + ")" );
    } else {
      b.l( "try:" );
      b.l( "    spark.read.format(\"jdbc\").options(**" + con + ").option(\"query\", \"SELECT * FROM \" + "
        + tablaSql( d, s( "schemaname" ), s( "tablename" ) ) + " + \" WHERE 1 = 0\").load()" );
      b.l( "    return True" );
      b.l( "except Exception:" );
      b.l( "    return False" );
    }
  }

  private void columnasExisten() {
    expl( "Columns exist in a table: éxito si la tabla tiene todas las columnas.", "Columns exist in a table: success "
      + "if the table has all the columns.", "Columns exist in a table: sucesso se a tabela tiver todas as "
        + "colunas." );
    DatabaseMeta d = db( s( "connection" ) );
    String con = c.conexion( d );
    StringBuilder cols = new StringBuilder();
    for ( Map<String, String> f : campos() ) {
      cols.append( cols.length() == 0 ? "" : ", " ).append( c.texto( de( f, "name" ) ) );
    }
    if ( c.pandas() ) {
      c.importar( "import sqlalchemy" );
      b.l( "existentes = {col[\"name\"].lower() for col in sqlalchemy.inspect(" + con + ").get_columns(" + v(
        "tablename" ) + ", schema=" + ( s( "schemaname" ).isEmpty() ? "None" : v( "schemaname" ) ) + ")}" );
    } else {
      b.l( "existentes = {col.lower() for col in spark.read.format(\"jdbc\").options(**" + con + ").option(\"query\", "
        + "\"SELECT * FROM \" + " + tablaSql( d, s( "schemaname" ), s( "tablename" ) ) + " + \" WHERE 1 = 0\").load()"
        + ".columns}" );
    }
    b.l( "return all(col.lower() in existentes for col in [" + cols + "])" );
  }

  private void conexiones() {
    expl( "Check DB connections: éxito si se puede conectar a todas las bases.", "Check DB connections: success if "
      + "all the databases can be connected to.", "Check DB connections: sucesso se for possível ligar a todas as "
        + "bases." );
    for ( Map<String, String> f : campos() ) {
      String n = de( f, "name" );
      if ( n.isEmpty() ) {
        continue;
      }
      String con = c.conexion( db( n ) );
      if ( c.pandas() ) {
        b.l( "with " + con + ".connect():" );
        b.l( "    pass" );
      } else {
        b.l( "spark.read.format(\"jdbc\").options(**" + con + ").option(\"query\", \"SELECT 1\").load().count()" );
      }
    }
    b.l( "return True" );
  }

  private void vaciarTablas() {
    expl( "Truncate tables: vacía las tablas.", "Truncate tables: empties the tables.", "Truncate tables: esvazia as "
      + "tabelas." );
    ayudanteSql();
    DatabaseMeta d = db( s( "connection" ) );
    String con = c.conexion( d );
    resultadoAnterior();
    boolean sqlite = "SQLITE".equalsIgnoreCase( d.getPluginId() );
    for ( Map<String, String> f : campos() ) {
      String t = tablaSql( d, de( f, "schemaname" ), de( f, "name" ) );
      b.l( "ejecutar_sql(" + con + ", " + CtxTrad.lit( sqlite ? "DELETE FROM " : "TRUNCATE TABLE " ) + " + " + t
        + ", una_sola=True)" );
    }
    b.l( "return True" );
    b.escribe = true;
  }

  private static final Map<String, String> COMPARA = new LinkedHashMap<>();

  static {
    COMPARA.put( "equal", "==" );
    COMPARA.put( "different", "!=" );
    COMPARA.put( "smaller", "<" );
    COMPARA.put( "smallequal", "<=" );
    COMPARA.put( "smaller_equal", "<=" );
    COMPARA.put( "greater", ">" );
    COMPARA.put( "greaterequal", ">=" );
    COMPARA.put( "greater_equal", ">=" );
  }

  private void contarFilas() {
    expl( "Evaluate rows number in a table: compara el número de filas con un límite.", "Evaluate rows number in a "
      + "table: compares the number of rows with a limit.", "Evaluate rows number in a table: compara o número de "
        + "linhas com um limite." );
    DatabaseMeta d = db( s( "connection" ) );
    String con = c.conexion( d );
    String cond = s( "success_condition" ).replace( "rows_count_", "" );
    String op = COMPARA.containsKey( cond ) ? COMPARA.get( cond ) : ">";
    String lim = s( "limit" ).trim().isEmpty() ? "0" : s( "limit" ).trim().matches( "-?[0-9]+" ) ? s( "limit" ).trim()
      : "int(" + v( "limit" ) + ")";
    boolean propia = si( "is_custom_sql" );
    String q = propia ? ( si( "is_usevars" ) ? v( "custom_sql" ) : CtxTrad.lit( s( "custom_sql" ) ) )
      : "\"SELECT COUNT(*) FROM \" + " + tablaSql( d, s( "schemaname" ), s( "tablename" ) );
    if ( c.pandas() ) {
      b.l( "with " + con + ".connect() as con:" );
      b.l( propia ? "    filas = len(con.exec_driver_sql(" + q + ").fetchall())"
        : "    filas = con.exec_driver_sql(" + q + ").scalar()" );
    } else {
      String lee = "spark.read.format(\"jdbc\").options(**" + con + ").option(\"query\", " + q + ").load()";
      b.l( propia ? "filas = " + lee + ".count()" : "filas = " + lee + ".first()[0]" );
    }
    if ( si( "add_rows_result" ) ) {
      b.aproximado( c.t( "PDI además deja las filas en el resultado: aquí no.", "PDI also puts the rows in the "
        + "result: not here.", "O PDI também deixa as linhas no resultado: aqui não." ) );
    }
    b.l( "return filas " + op + " " + lim );
  }

  // ================================================================ condiciones

  private void evaluar() {
    expl( "Simple evaluation: compara el valor de una variable (o de un campo del resultado) con una condición.",
      "Simple evaluation: compares the value of a variable (or of a result field) with a condition.",
      "Simple evaluation: compara o valor de uma variável (ou de um campo do resultado) com uma condição." );
    boolean variable = "variable".equalsIgnoreCase( s( "valuetype" ) );
    String nombre = variable ? s( "variablename" ) : s( "fieldname" );
    if ( variable && si( "successwhenvarset" ) ) {
      String n = nombre.replaceAll( "^\\$\\{|\\}$", "" );
      b.l( "return " + CtxTrad.lit( n ) + " in VARS or " + CtxTrad.lit( n ) + " in os.environ" );
      return;
    }
    if ( variable ) {
      b.l( "valor = " + c.texto( nombre ) );
    } else {
      b.aproximado( c.t( "El valor sale del campo «" + nombre + "» de la primera fila del resultado anterior "
        + "(RESULT[\"rows\"]).", "The value comes from field '" + nombre + "' of the first row of the previous result "
          + "(RESULT[\"rows\"]).", "O valor vem do campo «" + nombre + "» da primeira linha do resultado anterior "
            + "(RESULT[\"rows\"])." ) );
      b.l( c.pandas() ? "valor = str(RESULT[\"rows\"][" + CtxTrad.lit( nombre ) + "].iloc[0])"
        : "valor = str(RESULT[\"rows\"].first()[" + CtxTrad.lit( nombre ) + "])" );
    }
    String tipo = s( "fieldtype" ).toLowerCase();
    String cmp = v( "comparevalue" );
    if ( tipo.equals( "number" ) ) {
      String cond = s( "successnumbercondition" );
      if ( "between".equals( cond ) ) {
        b.l( "return float(" + v( "minvalue" ) + ") <= float(valor) <= float(" + v( "maxvalue" ) + ")" );
      } else if ( cond.matches( "inlist|notinlist" ) ) {
        b.l( "lista = [float(x) for x in " + cmp + ".split(\",\")]" );
        b.l( "return float(valor) " + ( cond.equals( "inlist" ) ? "in" : "not in" ) + " lista" );
      } else {
        String op = COMPARA.containsKey( cond ) ? COMPARA.get( cond ) : "==";
        b.l( "return float(valor) " + op + " float(" + cmp + ")" );
      }
      return;
    }
    if ( tipo.equals( "boolean" ) ) {
      b.l( "return valor.strip().upper() " + ( "true".equalsIgnoreCase( s( "successbooleancondition" ) ) ? "in"
        : "not in" ) + " (\"Y\", \"TRUE\", \"1\")" );
      return;
    }
    if ( tipo.equals( "datetime" ) ) {
      b.aproximado( c.t( "Fechas: se comparan como texto (completa la conversión con la máscara " + s( "mask" ) + ").",
        "Dates: compared as text (complete the conversion with mask " + s( "mask" ) + ").",
        "Datas: comparadas como texto (complete a conversão com a máscara " + s( "mask" ) + ")." ) );
    }
    String cond = s( "successcondition" );
    switch ( cond ) {
      case "different": b.l( "return valor != " + cmp ); break;
      case "contains": b.l( "return " + cmp + " in valor" ); break;
      case "notcontains": b.l( "return " + cmp + " not in valor" ); break;
      case "startswith": b.l( "return valor.startswith(" + cmp + ")" ); break;
      case "notstatwith": case "notstartwith": b.l( "return not valor.startswith(" + cmp + ")" ); break;
      case "endswith": b.l( "return valor.endswith(" + cmp + ")" ); break;
      case "notendwith": b.l( "return not valor.endswith(" + cmp + ")" ); break;
      case "regexp":
        c.importar( "import re" );
        b.l( "return re.fullmatch(" + cmp + ", valor) is not None" );
        break;
      case "inlist": b.l( "return valor in [x.strip() for x in " + cmp + ".split(\",\")]" ); break;
      case "notinlist": b.l( "return valor not in [x.strip() for x in " + cmp + ".split(\",\")]" ); break;
      case "smaller": case "smallequal": case "greater": case "greaterequal":
        b.l( "return valor " + COMPARA.get( cond ) + " " + cmp );
        break;
      default: b.l( "return valor == " + cmp );
    }
  }

  private void metricasArchivos() {
    expl( "Evaluate files metrics: compara el tamaño total (o el número) de unos archivos con un valor.",
      "Evaluate files metrics: compares the total size (or number) of some files with a value.",
      "Evaluate files metrics: compara o tamanho total (ou o número) de alguns arquivos com um valor." );
    ayudanteArchivos();
    if ( !"files".equals( s( "source_files" ) ) ) {
      b.aproximado( c.t( "Los archivos salen del resultado anterior en PDI: aquí, de la lista de la entrada.",
        "In PDI the files come from the previous result: here, from the entry's list.",
        "No PDI os arquivos vêm do resultado anterior: aqui, da lista da entrada." ) );
    }
    b.l( "lista = []" );
    for ( Map<String, String> f : campos() ) {
      String o = c.texto( de( f, "source_filefolder", "name" ) );
      String w = de( f, "wildcard" ).isEmpty() ? "None" : c.texto( de( f, "wildcard" ) );
      boolean sub = "Y".equalsIgnoreCase( de( f, "include_subFolders", "include_subfolders" ) );
      b.l( "ruta = " + o );
      b.l( "lista += [ruta] if os.path.isfile(ruta) else list(archivos(ruta, " + w + ", subcarpetas=" + py( sub )
        + "))" );
    }
    boolean cuenta = "count".equalsIgnoreCase( s( "evaluation_type" ) );
    if ( cuenta ) {
      b.l( "valor = len(lista)" );
    } else {
      String esc = s( "scale" );
      String div = "kbytes".equals( esc ) ? " / 1024" : "mbytes".equals( esc ) ? " / 1024 ** 2" : "gbytes".equals(
        esc ) ? " / 1024 ** 3" : "";
      b.l( "valor = sum(os.path.getsize(a) for a in lista)" + div );
    }
    String cond = s( "successnumbercondition" );
    if ( "between".equals( cond ) ) {
      b.l( "return float(" + v( "minvalue" ) + ") <= valor <= float(" + v( "maxvalue" ) + ")" );
    } else {
      b.l( "return valor " + ( COMPARA.containsKey( cond ) ? COMPARA.get( cond ) : ">" ) + " float(" + v(
        "comparevalue" ) + ")" );
    }
  }

  // ================================================================ red y procesos

  private void correo() {
    expl( "Mail: envía un correo (esqueleto con smtplib; la contraseña sale de PDI_MAIL_PASSWORD).", "Mail: sends an "
      + "email (smtplib skeleton; the password comes from PDI_MAIL_PASSWORD).", "Mail: envia um e-mail (esqueleto "
        + "com smtplib; a senha vem de PDI_MAIL_PASSWORD)." );
    c.importar( "import smtplib" );
    c.importar( "from email.message import EmailMessage" );
    b.l( "msg = EmailMessage()" );
    b.l( "msg[\"Subject\"] = " + v( "subject" ) );
    b.l( "msg[\"From\"] = " + ( s( "replyto" ).isEmpty() ? v( "auth_user" ) : v( "replyto" ) ) );
    b.l( "msg[\"To\"] = " + v( "destination" ) );
    if ( !s( "destinationCc" ).isEmpty() ) {
      b.l( "msg[\"Cc\"] = " + v( "destinationCc" ) );
    }
    if ( !s( "destinationBCc" ).isEmpty() ) {
      b.l( "msg[\"Bcc\"] = " + v( "destinationBCc" ) );
    }
    b.l( "msg.set_content(" + v( "comment" ) + ( si( "use_HTML" ) ? ", subtype=\"html\"" : "" ) + ")" );
    String puerto = s( "port" ).trim().isEmpty() ? "25" : s( "port" ).trim().matches( "[0-9]+" ) ? s( "port" ).trim()
      : "int(" + v( "port" ) + ")";
    boolean ssl = si( "use_secure_auth" ) && "SSL".equalsIgnoreCase( s( "secureconnectiontype" ) );
    b.l( "with smtplib." + ( ssl ? "SMTP_SSL" : "SMTP" ) + "(" + v( "server" ) + ", " + puerto + ") as smtp:" );
    if ( si( "use_secure_auth" ) && !ssl ) {
      b.l( "    smtp.starttls()" );
    }
    if ( si( "use_auth" ) ) {
      b.l( "    smtp.login(" + v( "auth_user" ) + ", os.environ.get(\"PDI_MAIL_PASSWORD\", \"\"))" );
    }
    b.l( "    smtp.send_message(msg)" );
    b.l( "return True" );
    b.aproximado( c.t( "Esqueleto: PDI añade además (según la configuración) fecha, contacto, archivos del resultado "
      + "e imágenes.", "Skeleton: PDI also adds (depending on settings) date, contact, result files and images.",
      "Esqueleto: o PDI também acrescenta (conforme a configuração) data, contacto, arquivos do resultado e "
        + "imagens." ) );
  }

  private void http() {
    expl( "HTTP: descarga una URL a un archivo.", "HTTP: downloads a URL to a file.",
      "HTTP: descarrega uma URL para um arquivo." );
    c.importar( "import urllib.request" );
    if ( !s( "uploadfilename" ).isEmpty() || si( "run_every_row" ) ) {
      b.aproximado( c.t( "Subida de archivo o URL por fila: completa a mano (aquí sólo la descarga).", "File upload or "
        + "URL per row: complete by hand (only the download here).", "Envio de arquivo ou URL por linha: complete à "
          + "mão (aqui só o download)." ) );
    }
    if ( !s( "username" ).isEmpty() ) {
      b.aproximado( c.t( "La URL pide usuario: añade la autenticación (contraseña desde una variable de entorno).",
        "The URL needs a user: add authentication (password from an environment variable).",
        "A URL pede utilizador: acrescente a autenticação (senha de uma variável de ambiente)." ) );
    }
    b.l( "with urllib.request.urlopen(" + v( "url" ) + ") as r, open(" + v( "targetfilename" ) + ", " + ( si(
      "file_appended" ) ? "\"ab\"" : "\"wb\"" ) + ") as f:" );
    b.l( "    f.write(r.read())" );
    b.l( "return True" );
  }

  private void servicioWeb() {
    expl( "Check webservice availability: éxito si la URL responde.", "Check webservice availability: success if the "
      + "URL answers.", "Check webservice availability: sucesso se a URL responder." );
    c.importar( "import urllib.request" );
    int t = Math.max( entero( "connectTimeOut", 0 ), entero( "readTimeOut", 0 ) );
    b.l( "try:" );
    b.l( "    with urllib.request.urlopen(" + v( "url" ) + ( t > 0 ? ", timeout=" + ( t / 1000.0 ) : "" ) + "):" );
    b.l( "        return True" );
    b.l( "except OSError:" );
    b.l( "    return False" );
  }

  private void shell() {
    expl( "Shell: ejecuta un script o un programa; éxito si termina con código 0.", "Shell: runs a script or a "
      + "program; success if it exits with code 0.", "Shell: executa um script ou um programa; sucesso se terminar "
        + "com código 0." );
    c.importar( "import subprocess" );
    String dir = s( "work_directory" ).isEmpty() ? "None" : v( "work_directory" );
    if ( si( "insertScript" ) ) {
      c.importar( "import tempfile" );
      String sc = s( "script" );
      String expr = sc.contains( "\n" ) && !sc.contains( "\"\"\"" ) && !sc.contains( "\\" ) && !sc.contains( "${" )
        ? "\"\"\"\n" + sc + "\"\"\"" : c.texto( sc );
      List<String> ls = java.util.Arrays.asList( ( "script = " + expr ).split( "\n", -1 ) );
      b.codigo.addAll( ls );
      b.l( "sufijo = \".bat\" if os.name == \"nt\" else \".sh\"" );
      b.l( "with tempfile.NamedTemporaryFile(\"w\", suffix=sufijo, delete=False) as f:" );
      b.l( "    f.write(script)" );
      b.l( "try:" );
      b.l( "    r = subprocess.run([f.name] if os.name == \"nt\" else [\"sh\", f.name], cwd=" + dir + ", "
        + "capture_output=True, text=True)" );
      b.l( "finally:" );
      b.l( "    os.remove(f.name)" );
    } else {
      StringBuilder args = new StringBuilder();
      for ( Node n = x.getFirstChild(); n != null; n = n.getNextSibling() ) {
        if ( n instanceof Element && n.getNodeName().matches( "argument[0-9]+" ) ) {
          args.append( ", " ).append( c.texto( n.getTextContent() ) );
        }
      }
      Node as = XMLHandler.getSubNode( x, "arguments" );
      if ( as != null ) {
        for ( Node n : XMLHandler.getNodes( as, "argument" ) ) {
          args.append( ", " ).append( c.texto( n.getTextContent() ) );
        }
      }
      b.l( "r = subprocess.run([" + v( "filename" ) + args + "], cwd=" + dir + ", capture_output=True, text=True)" );
    }
    b.l( "if r.stdout:" );
    b.l( "    log.info(r.stdout.rstrip())" );
    b.l( "if r.stderr:" );
    b.l( "    log.warning(r.stderr.rstrip())" );
    b.l( "return r.returncode == 0" );
    resultadoAnterior();
    if ( si( "exec_per_row" ) ) {
      b.aproximado( c.t( "En PDI se ejecuta una vez por fila del resultado anterior: aquí, una sola vez.",
        "In PDI it runs once per row of the previous result: here, only once.",
        "No PDI é executado uma vez por linha do resultado anterior: aqui, uma só vez." ) );
    }
  }

  private void ping() {
    expl( "Ping a host: éxito si el equipo responde.", "Ping a host: success if the host answers.",
      "Ping a host: sucesso se a máquina responder." );
    c.importar( "import subprocess" );
    int n = entero( "nbr_packets", 2 );
    b.l( "opcion = \"-n\" if os.name == \"nt\" else \"-c\"" );
    b.l( "r = subprocess.run([\"ping\", opcion, \"" + n + "\", " + v( "hostname" ) + "], capture_output=True)" );
    b.l( "return r.returncode == 0" );
    if ( !"systemPing".equals( s( "pingtype" ) ) ) {
      b.aproximado( c.t( "PDI hace un ping «clásico» (InetAddress.isReachable): aquí, el ping del sistema.",
        "PDI does a 'classic' ping (InetAddress.isReachable): here, the system ping.",
        "O PDI faz um ping «clássico» (InetAddress.isReachable): aqui, o ping do sistema." ) );
    }
  }

  private void telnet() {
    expl( "Telnet a host: éxito si el puerto acepta conexiones.", "Telnet a host: success if the port accepts "
      + "connections.", "Telnet a host: sucesso se a porta aceitar ligações." );
    c.importar( "import socket" );
    String p = s( "port" ).trim().matches( "[0-9]+" ) ? s( "port" ).trim() : "int(" + v( "port" ) + ")";
    b.l( "try:" );
    b.l( "    with socket.create_connection((" + v( "hostname" ) + ", " + p + "), timeout=" + ( entero( "timeout",
      3000 ) / 1000.0 ) + "):" );
    b.l( "        return True" );
    b.l( "except OSError:" );
    b.l( "    return False" );
  }

  private void mensaje() {
    expl( "Display msgbox info: en PDI abre una ventana; aquí lo escribe en el log.", "Display msgbox info: in PDI it "
      + "opens a window; here it is written to the log.", "Display msgbox info: no PDI abre uma janela; aqui é "
        + "escrito no log." );
    b.l( "log.info(" + c.texto( s( "titremessage" ) + ": " + s( "bodymessage" ) ) + ")" );
    b.l( "return True" );
  }

  private void javascript() {
    expl( "JavaScript: evalúa un script que devuelve true o false.", "JavaScript: evaluates a script that returns "
      + "true or false.", "JavaScript: avalia um script que devolve true ou false." );
    b.manual( c.t( "Código JavaScript: pásalo a Python a mano (el resultado es el valor de la última expresión).",
      "JavaScript code: port it to Python by hand (the result is the value of the last expression).",
      "Código JavaScript: passe-o para Python à mão (o resultado é o valor da última expressão)." ) );
    b.l( "# TODO: " + c.t( "el script de PDI:", "the PDI script:", "o script do PDI:" ) );
    for ( String l : s( "script" ).split( "\\r?\\n" ) ) {
      b.l( "#   " + l );
    }
    b.l( "return True" );
  }
}
