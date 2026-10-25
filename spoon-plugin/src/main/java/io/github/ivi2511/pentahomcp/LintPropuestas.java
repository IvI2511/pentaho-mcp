package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.NombresPorDefecto;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Rutas;
import io.github.ivi2511.pentahomcp.lint.Textos;

/**
 * Nombres propuestos sin IA (docs/linter-diseno.md §7, WP5) para default_step_name y default_entry_name, y para
 * los steps y entradas que crea apply_lint_fixes (Sort rows, Abort). Sale de la configuración del step: el archivo
 * que lee o escribe, la tabla, las claves de un orden, de un filtro o de un cruce, los campos que calcula… («Sort
 * rows» → «Ordenar por producto», «CSV file input» → «Leer ventas»). Lo que no dice nada por sí solo (Dummy,
 * Row denormaliser, System info…) queda sin propuesta: el nombre lo pone Claude o la persona (values[key]).
 *
 * Los textos van por idioma (lint_*.properties, claves name.*). Los nombres que salen de los datos del documento
 * (archivos, campos) son datos, no instrucciones. Vive en el paquete base, como los Lint* (§11).
 */
final class LintPropuestas {

  /** Cuántos campos se nombran como mucho; el resto va como «+n». */
  static final int MAX_CAMPOS = 3;
  /** Largo máximo de un nombre propuesto (los nombres largos tapan el lienzo). */
  static final int MAX_LARGO = 60;

  /** Entradas que leen archivos (los mismos ids que ReglasRutas.ENTRADAS_ARCHIVO). */
  static final Set<String> LEEN_ARCHIVO = new HashSet<String>( Arrays.asList( "CsvInput", "CubeInput",
    "ShapeFileReader", "FixedInput", "getXMLData", "ParallelGzipCsvInput", "HL7Input", "JsonInput", "LDIFInput",
    "LoadFileInput", "AccessInput", "ExcelInput", "PropertyInput", "SASInput", "TextFileInput", "OldTextFileInput",
    "XBaseInput", "XMLInputStream", "YamlInput", "AvroInputNew" ) );
  static final Set<String> ESCRIBEN_ARCHIVO = new HashSet<String>( Arrays.asList( "TextFileOutput",
    "TextFileOutputLegacy", "ExcelOutput", "TypeExitExcelWriterStep", "JsonOutput", "XMLOutput", "CubeOutput",
    "PropertyOutput", "SQLFileOutput", "AvroOutput" ) );
  /** Tags del archivo principal de un step o entrada, en orden de preferencia. */
  private static final List<String> TAGS_ARCHIVO = Arrays.asList( "filename", "file/name", "file_dbf", "file" );
  private static final Pattern FROM = Pattern.compile( "(?is)\\bfrom\\s+([\\w.\"`\\[\\]]+)" );
  private static final Pattern NUMERO = Pattern.compile( "\\d+" );

  private LintPropuestas() {
  }

  // ---------------------------------------------------------------- en el resultado del linter

  /**
   * Llena Arreglo.preview de los default_step_name / default_entry_name que no tienen propuesta, y les saca
   * needs_value cuando hay una. Los nombres no chocan con los del documento ni entre sí. m es el documento que se
   * analizó (la copia).
   */
  static void completar( Resultado r, AbstractMeta m, String lang ) {
    Textos t = Textos.de( lang );
    Set<String> tomados = nombresTomados( m );
    List<Hallazgo> todos = new ArrayList<Hallazgo>( r.findings );
    todos.addAll( r.suppressed );
    for ( Hallazgo h : todos ) {
      Arreglo a = h.fix;
      if ( a == null || a.preview != null || !a.needsValue ) {
        continue;
      }
      if ( "no_description".equals( h.rule ) ) {
        String d = descripcion( m, t );
        if ( d != null ) { // la persona la confirma (y la puede cambiar): no entra en all_auto
          a.preview = d;
          a.needsValue = false;
          a.param( "proposed_by", "rules" );
        }
        continue;
      }
      String p = null;
      if ( "default_step_name".equals( h.rule ) && m instanceof TransMeta && h.step != null ) {
        StepMeta s = ( (TransMeta) m ).findStep( h.step );
        p = s == null ? null : paraStep( s, t );
      } else if ( "default_entry_name".equals( h.rule ) && m instanceof JobMeta && h.entry != null ) {
        JobEntryCopy c = ( (JobMeta) m ).findJobEntry( h.entry, h.entryNr, true );
        p = c == null ? null : paraEntrada( c, t );
      }
      if ( p == null ) {
        continue;
      }
      p = conPrefijo( r.perfil, h, p );
      p = unico( p, tomados );
      tomados.add( p.toLowerCase( Locale.ROOT ) );
      a.preview = p;
      a.needsValue = false;
      a.param( "proposed_by", "rules" );
    }
  }

  /**
   * Con un perfil de proyecto que pide prefijos (naming_convention), el nombre propuesto ya los lleva: así el arreglo
   * de default_step_name deja también cumplida la convención («Leer ventas» → «IN_Leer ventas»).
   */
  static String conPrefijo( io.github.ivi2511.pentahomcp.lint.PerfilEquipo pf, Hallazgo h, String p ) {
    if ( pf == null || h.fix == null || pf.apaga( io.github.ivi2511.pentahomcp.lint.PerfilEquipo.NAMING ) ) {
      return p;
    }
    boolean step = "default_step_name".equals( h.rule );
    String tipo = h.fix.params.get( "type" );
    String pre = step ? pf.prefijoStep( tipo ) : pf.prefijoEntrada( tipo );
    return pre == null ? p : pf.conPrefijo( p, pre, step );
  }

  /** Cuántos steps o entradas nombra la descripción propuesta como mucho. */
  static final int MAX_DESCRIPCION = 4;

  /**
   * La descripción propuesta para no_description: qué lee y qué escribe una transformación, o qué ejecuta un job, con
   * los nombres de sus steps o entradas. null si no hay nada que decir (sin entradas ni salidas).
   */
  static String descripcion( AbstractMeta m, Textos t ) {
    if ( m instanceof TransMeta ) {
      List<String> lee = new ArrayList<String>();
      List<String> escribe = new ArrayList<String>();
      for ( StepMeta s : ( (TransMeta) m ).getSteps() ) {
        String id = s.getStepID();
        if ( LEEN_ARCHIVO.contains( id ) || "TableInput".equals( id ) ) {
          lee.add( t.t( "word.quoted", "name", s.getName() ) );
        } else if ( ESCRIBEN_ARCHIVO.contains( id ) || ESCRIBEN_TABLA.contains( id ) ) {
          escribe.add( t.t( "word.quoted", "name", s.getName() ) );
        }
      }
      if ( lee.isEmpty() && escribe.isEmpty() ) {
        return null;
      }
      if ( escribe.isEmpty() ) {
        return t.t( "desc.trans.in", "in", lista( lee ) );
      }
      if ( lee.isEmpty() ) {
        return t.t( "desc.trans.out", "out", lista( escribe ) );
      }
      return t.t( "desc.trans", "in", lista( lee ), "out", lista( escribe ) );
    }
    if ( m instanceof JobMeta ) {
      List<String> corre = new ArrayList<String>();
      for ( JobEntryCopy c : ( (JobMeta) m ).getJobCopies() ) {
        String pid = c.getEntry() == null ? "" : c.getEntry().getPluginId();
        if ( "TRANS".equals( pid ) || "JOB".equals( pid ) || "SQL".equals( pid ) || "SHELL".equals( pid ) ) {
          corre.add( t.t( "word.quoted", "name", c.getName() ) );
        }
      }
      return corre.isEmpty() ? null : t.t( "desc.job", "entries", lista( corre ) );
    }
    return null;
  }

  static final Set<String> ESCRIBEN_TABLA = new HashSet<String>( Arrays.asList( "TableOutput", "InsertUpdate", "Update",
    "Delete", "SynchronizeAfterMerge", "DimensionLookup", "CombinationLookup" ) );

  private static String lista( List<String> l ) {
    return l.size() <= MAX_DESCRIPCION ? String.join( ", ", l ) : String.join( ", ", l.subList( 0, MAX_DESCRIPCION ) )
      + " (+" + ( l.size() - MAX_DESCRIPCION ) + ")";
  }

  /** Nombres de steps o entradas del documento, en minúsculas (Kettle los busca sin distinguir mayúsculas). */
  static Set<String> nombresTomados( AbstractMeta m ) {
    Set<String> s = new HashSet<String>();
    if ( m instanceof TransMeta ) {
      for ( StepMeta x : ( (TransMeta) m ).getSteps() ) {
        s.add( x.getName().toLowerCase( Locale.ROOT ) );
      }
    } else if ( m instanceof JobMeta ) {
      for ( JobEntryCopy c : ( (JobMeta) m ).getJobCopies() ) {
        s.add( c.getName().toLowerCase( Locale.ROOT ) );
      }
    }
    return s;
  }

  /** «Ordenar por x», o «Ordenar por x 2» si ya existe. */
  static String unico( String nombre, Set<String> tomados ) {
    if ( !tomados.contains( nombre.toLowerCase( Locale.ROOT ) ) ) {
      return nombre;
    }
    for ( int i = 2; ; i++ ) {
      String n = nombre + " " + i;
      if ( !tomados.contains( n.toLowerCase( Locale.ROOT ) ) ) {
        return n;
      }
    }
  }

  // ---------------------------------------------------------------- steps y entradas que se crean

  /** Nombre del Sort rows que inserta apply_lint_fixes (unsorted_input). */
  static String nombreOrden( List<String> claves, String lang ) {
    return recortar( Textos.de( lang ).t( "name.sort", "fields", campos( claves ) ) );
  }

  /** Nombre de la entrada Abort que crea apply_lint_fixes (failure_branch_ends_in_success). */
  static String nombreAbort( String lang ) {
    return Textos.de( lang ).t( "name.abort" );
  }

  // ---------------------------------------------------------------- propuestas

  /** La propuesta para un step con nombre por defecto, o null si su configuración no dice qué hace. */
  static String paraStep( StepMeta s, Textos t ) {
    Element x;
    try {
      x = Nodos.parse( s.getXML() );
    } catch ( Exception e ) {
      return null;
    }
    String p = propuesta( s.getStepID(), x, t );
    if ( p == null ) {
      return null;
    }
    p = recortar( p );
    // una propuesta que también es un nombre por defecto no mejora nada
    return p.isEmpty() || NombresPorDefecto.esDeStep( p ) ? null : p;
  }

  private static String propuesta( String id, Element x, Textos t ) {
    if ( id == null ) {
      return null;
    }
    if ( LEEN_ARCHIVO.contains( id ) ) {
      if ( "Y".equalsIgnoreCase( Nodos.txt( x, "accept_filenames" ) ) || "Y".equalsIgnoreCase( Nodos.txt( x, "filefield" ) )
        || "Y".equalsIgnoreCase( Nodos.txt( x, "IsInFields" ) ) ) {
        return null; // el archivo viene de un campo
      }
      return conQue( t, "name.read", archivo( x ) );
    }
    if ( ESCRIBEN_ARCHIVO.contains( id ) ) {
      if ( "Y".equalsIgnoreCase( Nodos.txt( x, "fileNameInField" ) ) ) {
        return null;
      }
      return conQue( t, "name.write", archivo( x ) );
    }
    switch ( id ) {
      case "TableInput":
        return conQue( t, "name.read", tablaDeSql( Nodos.txt( x, "sql" ) ) );
      case "TableOutput":
        return conQue( t, "name.write", tabla( x ) );
      case "InsertUpdate":
        return conQue( t, "name.insert_update", tabla( x ) );
      case "Update":
        return conQue( t, "name.update", tabla( x ) );
      case "Delete":
        return conQue( t, "name.delete", tabla( x ) );
      case "SortRows":
        return conCampos( t, "name.sort", Nodos.nombres( x, "fields", "field", "name" ) );
      case "GroupBy":
      case "MemoryGroupBy": {
        List<String> g = Nodos.nombres( x, "group", "field", "name" );
        if ( g.isEmpty() ) {
          return Nodos.hijos( Nodos.hijo( x, "fields" ), "field" ).isEmpty() ? null : t.t( "name.totals" );
        }
        return conCampos( t, "name.group", g );
      }
      case "Unique":
      case "UniqueRowsByHashSet": {
        List<String> f = Nodos.nombres( x, "fields", "field", "name" );
        return f.isEmpty() ? t.t( "name.unique.all" ) : conCampos( t, "name.unique", f );
      }
      case "FilterRows":
        return conCampos( t, "name.filter", condicion( x ) );
      case "StreamLookup":
        return conCampos( t, "name.lookup", textos( Nodos.hijo( x, "lookup" ), "key", "name" ) );
      case "MergeJoin":
        return conCampos( t, "name.join", Nodos.nombres( x, "keys_1", "key", null ) );
      case "MergeRows":
        return conCampos( t, "name.compare", Nodos.nombres( x, "keys", "key", null ) );
      case "Calculator":
        return conCampos( t, "name.calculate", textos( x, "calculation", "field_name" ) );
      case "Formula":
        return conCampos( t, "name.calculate", textos( x, "formula", "field_name" ) );
      case "Constant":
        return conCampos( t, "name.add", Nodos.nombres( x, "fields", "field", "name" ) );
      case "RowGenerator": {
        String n = Nodos.txt( x, "limit" );
        return NUMERO.matcher( n ).matches() ? t.t( "name.generate", "n", n ) : null;
      }
      case "SelectValues":
        return seleccion( x, t );
      case "GetVariable":
        return conCampos( t, "name.variables", Nodos.nombres( x, "fields", "field", "name" ) );
      case "ValueMapper":
        return conCampos( t, "name.map", uno( Nodos.txt( x, "field_to_use" ) ) );
      case "SwitchCase":
        return conCampos( t, "name.switch", uno( Nodos.txt( x, "fieldname" ) ) );
      case "FieldSplitter":
        return conCampos( t, "name.split", uno( Nodos.txt( x, "splitfield" ) ) );
      case "SplitFieldToRows3":
        return conCampos( t, "name.split", uno( Nodos.txt( x, "splitfield" ) ) );
      default:
        return null;
    }
  }

  /** La propuesta para una entrada de job con nombre por defecto (Transformation, Job: el archivo que corre). */
  static String paraEntrada( JobEntryCopy c, Textos t ) {
    String id = c.getEntry() == null ? null : c.getEntry().getPluginId();
    if ( !"TRANS".equals( id ) && !"JOB".equals( id ) ) {
      return null;
    }
    Element x;
    try {
      x = Nodos.parse( c.getXML() );
    } catch ( Exception e ) {
      return null;
    }
    String que = nombreDeArchivo( Nodos.txt( x, "filename" ) );
    if ( que == null ) {
      que = limpio( Nodos.txt( x, "TRANS".equals( id ) ? "transname" : "jobname" ) );
    }
    String p = conQue( t, "name.run", que );
    if ( p == null ) {
      return null;
    }
    p = recortar( p );
    return NombresPorDefecto.esDeEntrada( p ) ? null : p;
  }

  // ---------------------------------------------------------------- de qué habla el step

  private static String conQue( Textos t, String clave, String que ) {
    return que == null ? null : t.t( clave, "what", que );
  }

  private static String conCampos( Textos t, String clave, List<String> campos ) {
    return campos.isEmpty() ? null : t.t( clave, "fields", campos( campos ) );
  }

  /** «a, b, c +2». */
  static String campos( List<String> l ) {
    Set<String> s = new LinkedHashSet<String>();
    for ( String x : l ) {
      String c = limpio( x );
      if ( c != null ) {
        s.add( c );
      }
    }
    List<String> u = new ArrayList<String>( s );
    StringBuilder b = new StringBuilder();
    for ( int i = 0; i < u.size() && i < MAX_CAMPOS; i++ ) {
      b.append( i == 0 ? "" : ", " ).append( u.get( i ) );
    }
    if ( u.size() > MAX_CAMPOS ) {
      b.append( " +" ).append( u.size() - MAX_CAMPOS );
    }
    return b.toString();
  }

  private static List<String> uno( String x ) {
    return x == null || x.trim().isEmpty() ? new ArrayList<String>() : new ArrayList<String>( Arrays.asList( x.trim() ) );
  }

  /** El archivo principal: el primer tag de archivo que Rutas reconoce, sin carpeta ni extensión. */
  static String archivo( Element x ) {
    List<Rutas.Ruta> rs = Rutas.de( x );
    for ( String tag : TAGS_ARCHIVO ) {
      for ( Rutas.Ruta r : rs ) {
        if ( r.tag.equals( tag ) ) {
          return nombreDeArchivo( r.valor );
        }
      }
    }
    return null;
  }

  /** «${Internal.Entry.Current.Directory}/datos/ventas.csv» → «ventas»; null si es una variable o un comodín. */
  static String nombreDeArchivo( String ruta ) {
    if ( ruta == null || ruta.trim().isEmpty() ) {
      return null;
    }
    String v = ruta.trim().replace( '\\', '/' );
    while ( v.endsWith( "/" ) ) {
      v = v.substring( 0, v.length() - 1 );
    }
    int b = v.lastIndexOf( '/' );
    String n = b >= 0 ? v.substring( b + 1 ) : v;
    int p = n.lastIndexOf( '.' );
    if ( p > 0 && n.length() - p <= 6 ) {
      n = n.substring( 0, p );
    }
    return limpio( n );
  }

  /** La primera tabla del FROM de la consulta (sin esquema ni comillas). */
  static String tablaDeSql( String sql ) {
    if ( sql == null ) {
      return null;
    }
    Matcher m = FROM.matcher( sql );
    if ( !m.find() ) {
      return null;
    }
    String t = m.group( 1 ).replaceAll( "[\"`\\[\\]]", "" );
    int p = t.lastIndexOf( '.' );
    return limpio( p >= 0 ? t.substring( p + 1 ) : t );
  }

  /** La primera hoja <table> (Table output la tiene arriba; Insert/update, Update y Delete dentro de <lookup>). */
  private static String tabla( Element x ) {
    for ( Element[] h : Nodos.hojas( x ) ) {
      if ( h[ 0 ].getTagName().equals( "table" ) ) {
        String v = limpio( h[ 0 ].getTextContent() );
        if ( v != null ) {
          return v;
        }
      }
    }
    return null;
  }

  /** Los campos que compara la condición de un Filter rows (leftvalue y rightvalue, a cualquier profundidad). */
  private static List<String> condicion( Element x ) {
    List<String> l = new ArrayList<String>();
    Element c = Nodos.hijo( x, "compare" );
    if ( c == null ) {
      return l;
    }
    for ( Element[] h : Nodos.hojas( c ) ) {
      String t = h[ 0 ].getTagName();
      if ( t.equals( "leftvalue" ) || t.equals( "rightvalue" ) ) {
        String v = h[ 0 ].getTextContent().trim();
        if ( !v.isEmpty() && !l.contains( v ) ) {
          l.add( v );
        }
      }
    }
    return l;
  }

  /** El texto de tagNombre en cada hijo «item» de padre (p. ej. cada <calculation> de la raíz). */
  private static List<String> textos( Element padre, String item, String tagNombre ) {
    List<String> l = new ArrayList<String>();
    for ( Element e : Nodos.hijos( padre, item ) ) {
      String v = Nodos.txt( e, tagNombre );
      if ( !v.isEmpty() ) {
        l.add( v );
      }
    }
    return l;
  }

  /** Select values: lo que elige (con los nombres de salida), o lo que quita, o a qué le cambia el tipo. */
  private static String seleccion( Element x, Textos t ) {
    Element fs = Nodos.hijo( x, "fields" );
    List<String> elegidos = new ArrayList<String>();
    for ( Element f : Nodos.hijos( fs, "field" ) ) {
      String n = Nodos.txt( f, "rename" );
      elegidos.add( n.isEmpty() ? Nodos.txt( f, "name" ) : n );
    }
    elegidos.removeAll( Arrays.asList( "" ) );
    if ( !elegidos.isEmpty() ) {
      return conCampos( t, "name.select", elegidos );
    }
    List<String> quitados = textos( fs, "remove", "name" );
    if ( !quitados.isEmpty() ) {
      return conCampos( t, "name.remove", quitados );
    }
    List<String> meta = textos( fs, "meta", "name" );
    return conCampos( t, "name.retype", meta );
  }

  /** Sin saltos de línea ni espacios repetidos; null si queda vacío o si es una variable. */
  static String limpio( String s ) {
    if ( s == null ) {
      return null;
    }
    String v = s.replaceAll( "\\s+", " " ).trim();
    if ( v.isEmpty() || v.contains( "${" ) || v.contains( "%%" ) || v.contains( "*" ) || v.contains( "?" ) ) {
      return null;
    }
    return v;
  }

  static String recortar( String s ) {
    String v = s.replaceAll( "\\s+", " " ).trim();
    return v.length() <= MAX_LARGO ? v : v.substring( 0, MAX_LARGO - 1 ).trim() + "…";
  }
}
