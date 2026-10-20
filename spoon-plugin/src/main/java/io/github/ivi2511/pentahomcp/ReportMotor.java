package io.github.ivi2511.pentahomcp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.Condition;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.job.JobEntryJob;
import org.pentaho.di.job.entries.special.JobEntrySpecial;
import org.pentaho.di.job.entries.sql.JobEntrySQL;
import org.pentaho.di.job.entries.trans.JobEntryTrans;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.resource.ResourceEntry;
import org.pentaho.di.resource.ResourceReference;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.csvinput.CsvInputMeta;
import org.pentaho.di.trans.steps.fileinput.text.TextFileInputMeta;
import org.pentaho.di.trans.steps.filterrows.FilterRowsMeta;
import org.pentaho.di.trans.steps.groupby.GroupByMeta;
import org.pentaho.di.trans.steps.memgroupby.MemoryGroupByMeta;
import org.pentaho.di.trans.steps.mergejoin.MergeJoinMeta;
import org.pentaho.di.trans.steps.sort.SortRowsMeta;
import org.pentaho.di.trans.steps.streamlookup.StreamLookupMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;
import org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * La parte de generate_report que no toca Spoon: arma el informe Markdown (con diagrama Mermaid) a
 * partir de una COPIA de un TransMeta/JobMeta. Corre fuera del hilo de UI y se puede probar sin GUI.
 *
 * Los textos fijos (titulos, etiquetas) salen de un mapa por idioma; todo lo demas son nombres que
 * puso el usuario y van tal cual.
 */
final class ReportMotor {

  /** Tope de la SQL de un Table input en la tabla de steps. */
  private static final int MAX_SQL = 160;

  private ReportMotor() {
  }

  // ---------------------------------------------------------------- opciones y resultado

  static final class Opciones {
    String idioma = "en";
    boolean campos = true;
    /** Timeout total del calculo de campos (todos los steps). */
    long msCampos = 60000;
    /** Resultado de RunMotor.validar sobre la copia, o null para omitir la seccion. */
    ObjectNode validacion;
  }

  /** Lo que devuelve informe(): el Markdown y unos numeros para el resumen corto. */
  static final class Informe {
    String markdown;
    int nodos;
    int hops;
    int camposOk;
    int camposFallidos;
    List<String> avisos = new ArrayList<String>();
  }

  // ---------------------------------------------------------------- textos por idioma

  private static final Map<String, Map<String, String>> TEXTOS = new HashMap<String, Map<String, String>>();

  static {
    String[] claves = { "trans", "job", "file", "generated", "summary", "description", "diagram", "legend_trans",
      "legend_job", "steps", "entries", "col_name", "col_type", "col_does", "fields", "col_field", "connections",
      "col_host", "col_db", "params", "col_default", "notes", "validation", "error_label", "warning_label",
      "no_issues", "not_saved", "always", "on_success", "on_failure", "error", "disabled", "lookup", "true", "false",
      "copies_to", "distributes", "copies_n", "missing_plugin", "reads", "writes", "condition", "group_by",
      "lookup_keys", "lookup_in", "sql", "table", "sort_by", "join", "runs_trans", "runs_job", "connection",
      "fields_error", "fields_timeout", "no_fields", "start", "repeats", "files", "n_steps", "n_entries", "n_hops",
      "n_errors", "n_warnings", "no_steps" };
    String[] en = { "Transformation", "Job", "File", "Generated", "Summary", "Description", "Diagram",
      "Solid arrows carry rows; dotted red arrows are error hops; arrows ending in x are disabled hops.",
      "Green: on success; red dotted: on failure; black: always; arrows ending in x are disabled hops.",
      "Steps", "Job entries", "Name", "Type", "What it does", "Output fields", "Field", "Database connections",
      "Host", "Database", "Parameters", "Default", "Canvas notes", "Validation", "Error", "Warning",
      "No errors or warnings.", "(not saved)", "always", "on success", "on failure", "error", "disabled", "lookup",
      "true", "false", "copies rows to {n} outputs", "distributes rows over {n} outputs", "{n} copies",
      "missing plugin", "Reads", "Writes", "Condition", "Group by", "Lookup keys", "lookup in", "SQL", "Table",
      "Sort by", "Join", "Runs transformation", "Runs job", "Connection", "Could not compute the fields",
      "Not computed (timeout)", "(no fields)", "Start of the job", "repeats", "Files", "steps", "entries", "hops",
      "errors", "warnings", "The document is empty." };
    String[] es = { "Transformación", "Job", "Archivo", "Generado", "Resumen", "Descripción", "Diagrama",
      "Las flechas llenas llevan filas; las punteadas rojas son hops de error; las que terminan en x están "
        + "deshabilitadas.",
      "Verde: si termina bien; rojo punteado: si falla; negro: siempre; las que terminan en x están "
        + "deshabilitadas.",
      "Steps", "Entradas del job", "Nombre", "Tipo", "Qué hace", "Campos de salida", "Campo", "Conexiones a bases "
        + "de datos", "Host", "Base", "Parámetros", "Por defecto", "Notas del lienzo", "Validación", "Error",
      "Advertencia", "Sin errores ni advertencias.", "(sin guardar)", "siempre", "si termina bien", "si falla",
      "error", "deshabilitado", "búsqueda", "verdadero", "falso", "copia las filas a {n} salidas",
      "reparte las filas entre {n} salidas", "{n} copias", "falta el plugin", "Lee", "Escribe", "Condición",
      "Agrupa por", "Claves de búsqueda", "busca en", "SQL", "Tabla", "Ordena por", "Une", "Ejecuta la "
        + "transformación", "Ejecuta el job", "Conexión", "No se pudieron calcular los campos",
      "Sin calcular (timeout)", "(sin campos)", "Inicio del job", "se repite", "Archivos", "steps", "entradas",
      "hops", "errores", "advertencias", "El documento está vacío." };
    String[] pt = { "Transformação", "Job", "Arquivo", "Gerado", "Resumo", "Descrição", "Diagrama",
      "Setas cheias levam linhas; setas pontilhadas vermelhas são hops de erro; as que terminam em x estão "
        + "desabilitadas.",
      "Verde: em caso de sucesso; vermelho pontilhado: em caso de falha; preto: sempre; as que terminam em x "
        + "estão desabilitadas.",
      "Steps", "Entradas do job", "Nome", "Tipo", "O que faz", "Campos de saída", "Campo", "Conexões de banco de "
        + "dados", "Host", "Banco", "Parâmetros", "Padrão", "Notas do canvas", "Validação", "Erro", "Aviso",
      "Sem erros nem avisos.", "(não salvo)", "sempre", "em caso de sucesso", "em caso de falha", "erro",
      "desabilitado", "busca", "verdadeiro", "falso", "copia as linhas para {n} saídas",
      "distribui as linhas entre {n} saídas", "{n} cópias", "plugin ausente", "Lê", "Escreve", "Condição",
      "Agrupa por", "Chaves de busca", "busca em", "SQL", "Tabela", "Ordena por", "Junta", "Executa a "
        + "transformação", "Executa o job", "Conexão", "Não foi possível calcular os campos",
      "Não calculado (timeout)", "(sem campos)", "Início do job", "repete", "Arquivos", "steps", "entradas", "hops",
      "erros", "avisos", "O documento está vazio." };
    TEXTOS.put( "en", mapa( claves, en ) );
    TEXTOS.put( "es", mapa( claves, es ) );
    TEXTOS.put( "pt", mapa( claves, pt ) );
  }

  private static Map<String, String> mapa( String[] claves, String[] valores ) {
    if ( claves.length != valores.length ) {
      throw new IllegalStateException( "ReportMotor: missing texts (" + claves.length + " keys, "
        + valores.length + " values)" );
    }
    Map<String, String> m = new HashMap<String, String>();
    for ( int i = 0; i < claves.length; i++ ) {
      m.put( claves[ i ], valores[ i ] );
    }
    return m;
  }

  static String idioma( String pedido ) throws ToolError {
    String l = pedido == null ? "en" : pedido.trim().toLowerCase();
    if ( !TEXTOS.containsKey( l ) ) {
      throw new ToolError( "'language' must be one of en, es, pt, got: " + pedido );
    }
    return l;
  }

  /**
   * Texto fijo en el idioma pedido. Package-private para que las secciones de Informes (SecFlujo)
   * reusen mermaidTrans, mermaidJob, queHaceStep y queHaceEntrada con los mismos rotulos.
   */
  static final class T {
    private final Map<String, String> m;
    /** en | es | pt: tambien el de los nombres de los tipos de step y de entrada (NombresPlugin). */
    final String idioma;

    T( String idioma ) {
      Map<String, String> x = TEXTOS.get( idioma );
      m = x == null ? TEXTOS.get( "en" ) : x;
      this.idioma = x == null ? "en" : idioma;
    }

    String s( String k ) {
      String v = m.get( k );
      return v == null ? k : v;
    }

    String n( String k, int n ) {
      return s( k ).replace( "{n}", String.valueOf( n ) );
    }
  }

  // ---------------------------------------------------------------- entrada

  /** Arma el informe de una copia. Fuera del hilo de UI. */
  static Informe informe( AbstractMeta copia, Opciones op ) throws Exception {
    T t = new T( op.idioma );
    Informe inf = new Informe();
    StringBuilder md = new StringBuilder();
    cabecera( md, copia, t );
    if ( copia instanceof TransMeta ) {
      transformacion( md, (TransMeta) copia, op, t, inf );
    } else {
      job( md, (JobMeta) copia, t, inf );
    }
    conexiones( md, copia, t );
    parametros( md, copia, t );
    notas( md, copia, t );
    if ( op.validacion != null ) {
      validacion( md, op.validacion, t );
    }
    inf.markdown = md.toString();
    return inf;
  }

  private static void cabecera( StringBuilder md, AbstractMeta m, T t ) {
    md.append( "# " ).append( texto( Docs.nombre( m ) ) ).append( "\n\n" );
    md.append( "- **" ).append( t.s( "col_type" ) ).append( ":** " )
      .append( m instanceof JobMeta ? t.s( "job" ) : t.s( "trans" ) ).append( '\n' );
    md.append( "- **" ).append( t.s( "file" ) ).append( ":** " )
      .append( m.getFilename() == null ? t.s( "not_saved" ) : codigo( m.getFilename() ) ).append( '\n' );
    md.append( "- **" ).append( t.s( "generated" ) ).append( ":** " )
      .append( new SimpleDateFormat( "yyyy-MM-dd HH:mm" ).format( new Date() ) ).append( '\n' );
    String desc = vacioANull( m.getDescription() );
    String ext = vacioANull( m.getExtendedDescription() );
    if ( desc != null || ext != null ) {
      md.append( "\n## " ).append( t.s( "description" ) ).append( "\n\n" );
      if ( desc != null ) {
        md.append( desc.trim() ).append( "\n\n" );
      }
      if ( ext != null && !ext.trim().equals( desc == null ? null : desc.trim() ) ) {
        md.append( ext.trim() ).append( "\n\n" );
      }
    } else {
      md.append( '\n' );
    }
  }

  // ---------------------------------------------------------------- transformaciones

  private static void transformacion( StringBuilder md, TransMeta tm, Opciones op, T t, Informe inf )
    throws Exception {
    List<StepMeta> orden = ordenTrans( tm );
    inf.nodos = orden.size();
    inf.hops = tm.nrTransHops();
    md.append( "## " ).append( t.s( "summary" ) ).append( "\n\n" )
      .append( orden.size() ).append( ' ' ).append( t.s( "n_steps" ) ).append( ", " )
      .append( tm.nrTransHops() ).append( ' ' ).append( t.s( "n_hops" ) ).append( ".\n\n" );
    if ( orden.isEmpty() ) {
      md.append( t.s( "no_steps" ) ).append( "\n\n" );
      return;
    }

    md.append( "## " ).append( t.s( "diagram" ) ).append( "\n\n" );
    md.append( "```mermaid\n" ).append( mermaidTrans( tm, orden, t ) ).append( "```\n\n" );
    md.append( "_" ).append( t.s( "legend_trans" ) ).append( "_\n\n" );

    md.append( "## " ).append( t.s( "steps" ) ).append( "\n\n" );
    md.append( "| # | " ).append( t.s( "col_name" ) ).append( " | " ).append( t.s( "col_type" ) ).append( " | " )
      .append( t.s( "col_does" ) ).append( " |\n|---|---|---|---|\n" );
    int i = 1;
    for ( StepMeta sm : orden ) {
      md.append( "| " ).append( i++ ).append( " | " ).append( celda( sm.getName() ) ).append( " | " )
        .append( celda( tipoStep( sm, t ) ) ).append( " | " ).append( queHaceStep( tm, sm, t ) ).append( " |\n" );
    }
    md.append( '\n' );

    if ( op.campos ) {
      Map<String, Object> campos = calcularCampos( tm, orden, op.msCampos );
      md.append( "## " ).append( t.s( "fields" ) ).append( "\n\n" );
      for ( StepMeta sm : orden ) {
        md.append( "### " ).append( texto( sm.getName() ) ).append( "\n\n" );
        Object c = campos.get( sm.getName() );
        if ( c == null ) {
          inf.camposFallidos++;
          md.append( "_" ).append( t.s( "fields_timeout" ) ).append( "_\n\n" );
        } else if ( c instanceof String ) {
          inf.camposFallidos++;
          inf.avisos.add( "fields of '" + sm.getName() + "': " + c );
          md.append( "_" ).append( t.s( "fields_error" ) ).append( ":_ " ).append( texto( (String) c ) )
            .append( "\n\n" );
        } else {
          inf.camposOk++;
          RowMetaInterface rm = (RowMetaInterface) c;
          if ( rm.size() == 0 ) {
            md.append( "_" ).append( t.s( "no_fields" ) ).append( "_\n\n" );
            continue;
          }
          md.append( "| " ).append( t.s( "col_field" ) ).append( " | " ).append( t.s( "col_type" ) )
            .append( " |\n|---|---|\n" );
          for ( ValueMetaInterface v : rm.getValueMetaList() ) {
            md.append( "| " ).append( celda( v.getName() ) ).append( " | " ).append( celda( tipoCampo( v ) ) )
              .append( " |\n" );
          }
          md.append( '\n' );
        }
      }
    }
  }

  /**
   * Campos de salida de cada step con getStepFields sobre la copia, con un timeout total. Valor por
   * nombre de step: RowMetaInterface si salio, String con el error si fallo, ausente si no llego.
   */
  static Map<String, Object> calcularCampos( final TransMeta tm, final List<StepMeta> orden, long timeoutMs )
    throws Exception {
    final Map<String, Object> out = Collections.synchronizedMap( new LinkedHashMap<String, Object>() );
    try {
      RunMotor.enSegundoPlano( () -> {
        for ( StepMeta sm : orden ) {
          if ( Thread.currentThread().isInterrupted() ) {
            break;
          }
          try {
            out.put( sm.getName(), tm.getStepFields( sm ) );
          } catch ( Throwable e ) {
            out.put( sm.getName(), recortar( RunMotor.mensaje( e ), 400 ) );
          }
        }
        return null;
      }, timeoutMs, null, "Computing the fields" );
    } catch ( ToolError e ) {
      // timeout: se informa lo que se llego a calcular
    }
    synchronized ( out ) {
      return new LinkedHashMap<String, Object>( out );
    }
  }

  private static String tipoCampo( ValueMetaInterface v ) {
    String s = v.getTypeDesc();
    if ( v.getLength() >= 0 ) {
      s += "(" + v.getLength() + ( v.getPrecision() >= 0 ? ", " + v.getPrecision() : "" ) + ")";
    }
    return s;
  }

  /**
   * Orden de lectura: topologico por los hops (sin contar los deshabilitados), y ante empate el del
   * documento. Lo que quede en ciclos va al final en el orden del documento.
   */
  static List<StepMeta> ordenTrans( TransMeta tm ) {
    List<StepMeta> steps = new ArrayList<StepMeta>( tm.getSteps() );
    Map<StepMeta, Integer> entrantes = new IdentityHashMap<StepMeta, Integer>();
    for ( StepMeta s : steps ) {
      entrantes.put( s, 0 );
    }
    List<StepMeta[]> aristas = new ArrayList<StepMeta[]>();
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() != null && h.getToStep() != null && entrantes.containsKey( h.getFromStep() )
        && entrantes.containsKey( h.getToStep() ) ) {
        aristas.add( new StepMeta[] { h.getFromStep(), h.getToStep() } );
        entrantes.put( h.getToStep(), entrantes.get( h.getToStep() ) + 1 );
      }
    }
    return topologico( steps, entrantes, aristas );
  }

  private static <N> List<N> topologico( List<N> nodos, Map<N, Integer> entrantes, List<N[]> aristas ) {
    List<N> out = new ArrayList<N>();
    List<N> pendientes = new ArrayList<N>( nodos );
    boolean avanzo = true;
    while ( !pendientes.isEmpty() && avanzo ) {
      avanzo = false;
      for ( Iterator<N> it = pendientes.iterator(); it.hasNext(); ) {
        N n = it.next();
        if ( entrantes.get( n ) == 0 ) {
          it.remove();
          out.add( n );
          for ( N[] a : aristas ) {
            if ( a[ 0 ] == n ) {
              entrantes.put( a[ 1 ], entrantes.get( a[ 1 ] ) - 1 );
            }
          }
          avanzo = true;
          break; // volver a empezar: respeta el orden del documento entre los disponibles
        }
      }
    }
    out.addAll( pendientes );
    return out;
  }

  static String mermaidTrans( TransMeta tm, List<StepMeta> orden, T t ) {
    StringBuilder sb = new StringBuilder( "flowchart LR\n" );
    Map<StepMeta, String> ids = new IdentityHashMap<StepMeta, String>();
    List<String> multi = new ArrayList<String>();
    List<String> faltan = new ArrayList<String>();
    int n = 1;
    for ( StepMeta sm : orden ) {
      String id = "s" + n++;
      ids.put( sm, id );
      StringBuilder et = new StringBuilder( sm.getName() );
      if ( sm.getCopies() > 1 ) {
        et.append( " (" ).append( t.n( "copies_n", sm.getCopies() ) ).append( ')' );
      }
      List<String> lineas = new ArrayList<String>();
      lineas.add( et.toString() );
      lineas.add( NombresPlugin.nombre( StepPluginType.class, sm.getStepID(), t.idioma ) );
      int salidas = TransOps.salidasNormales( tm, sm );
      if ( salidas >= 2 ) {
        lineas.add( sm.isDistributes() ? t.n( "distributes", salidas ) : t.n( "copies_to", salidas ) );
        multi.add( id );
      }
      if ( sm.isMissing() ) {
        lineas.add( t.s( "missing_plugin" ) );
        faltan.add( id );
      }
      sb.append( "  " ).append( id ).append( "[\"" ).append( etiqueta( lineas ) ).append( "\"]\n" );
    }
    List<String> estilos = new ArrayList<String>();
    int arista = 0;
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      String a = ids.get( h.getFromStep() );
      String b = ids.get( h.getToStep() );
      if ( a == null || b == null ) {
        continue;
      }
      List<String> textos = new ArrayList<String>();
      String flecha = "-->";
      String estilo = null;
      if ( h.isErrorHop() ) {
        textos.add( t.s( "error" ) );
        flecha = "-.->";
        estilo = "stroke:#d33,color:#d33";
      } else {
        String rol = rolHop( h.getFromStep(), h.getToStep(), t );
        if ( rol != null ) {
          textos.add( rol );
        }
      }
      if ( !h.isEnabled() ) {
        textos.add( t.s( "disabled" ) );
        flecha = "--x";
        estilo = "stroke:#999,stroke-dasharray:4 3,color:#999";
      }
      sb.append( "  " ).append( a ).append( ' ' ).append( flecha );
      if ( !textos.isEmpty() ) {
        sb.append( "|\"" ).append( escaparMermaid( unir( textos, ", " ) ) ).append( "\"|" );
      }
      sb.append( ' ' ).append( b ).append( '\n' );
      if ( estilo != null ) {
        estilos.add( "  linkStyle " + arista + " " + estilo + "\n" );
      }
      arista++;
    }
    for ( String e : estilos ) {
      sb.append( e );
    }
    clases( sb, "multi", "stroke-width:3px", multi );
    clases( sb, "missing", "stroke:#d33,stroke-dasharray:4 3", faltan );
    return sb.toString();
  }

  /** Etiqueta de la flecha segun el step: true/false de un Filter rows, lookup de un Stream lookup. */
  private static String rolHop( StepMeta desde, StepMeta hacia, T t ) {
    try {
      StepMetaInterface a = desde.getStepMetaInterface();
      if ( a instanceof FilterRowsMeta ) {
        FilterRowsMeta f = (FilterRowsMeta) a;
        if ( hacia.getName().equals( f.getTrueStepname() ) ) {
          return t.s( "true" );
        }
        if ( hacia.getName().equals( f.getFalseStepname() ) ) {
          return t.s( "false" );
        }
      }
      StepMetaInterface b = hacia.getStepMetaInterface();
      if ( b instanceof StreamLookupMeta ) {
        String fuente = fuenteLookup( (StreamLookupMeta) b );
        if ( desde.getName().equals( fuente ) ) {
          return t.s( "lookup" );
        }
      }
    } catch ( Exception ignored ) {
      // sin etiqueta
    }
    return null;
  }

  private static String fuenteLookup( StreamLookupMeta m ) {
    try {
      return m.getStepIOMeta().getInfoStreams().get( 0 ).getStepname();
    } catch ( Exception e ) {
      return null;
    }
  }

  private static String tipoStep( StepMeta sm, T t ) {
    return NombresPlugin.conId( StepPluginType.class, sm.getStepID(), t.idioma );
  }

  /** Una linea de que hace el step, desde su configuracion; si no se conoce, la descripcion del plugin. */
  static String queHaceStep( TransMeta tm, StepMeta sm, T t ) {
    List<String> partes = new ArrayList<String>();
    try {
      StepMetaInterface m = sm.getStepMetaInterface();
      if ( m instanceof CsvInputMeta ) {
        partes.add( t.s( "reads" ) + ": " + codigo( ( (CsvInputMeta) m ).getFilename() ) );
      } else if ( m instanceof TextFileInputMeta ) {
        String[] f = ( (TextFileInputMeta) m ).inputFiles.fileName;
        if ( f != null && f.length > 0 ) {
          partes.add( t.s( "reads" ) + ": " + listaCodigo( f ) );
        }
      } else if ( m instanceof TextFileOutputMeta ) {
        TextFileOutputMeta o = (TextFileOutputMeta) m;
        String ext = vacioANull( o.getExtension() );
        partes.add( t.s( "writes" ) + ": " + codigo( o.getFileName() + ( ext == null ? "" : "." + ext ) ) );
      } else if ( m instanceof FilterRowsMeta ) {
        Condition c = ( (FilterRowsMeta) m ).getCondition();
        if ( c != null && !c.isEmpty() ) {
          partes.add( t.s( "condition" ) + ": " + codigo( condicion( c ) ) );
        }
      } else if ( m instanceof GroupByMeta ) {
        partes.add( t.s( "group_by" ) + ": " + listaCodigo( ( (GroupByMeta) m ).getGroupField() ) );
      } else if ( m instanceof MemoryGroupByMeta ) {
        partes.add( t.s( "group_by" ) + ": " + listaCodigo( ( (MemoryGroupByMeta) m ).getGroupField() ) );
      } else if ( m instanceof StreamLookupMeta ) {
        StreamLookupMeta l = (StreamLookupMeta) m;
        String fuente = fuenteLookup( l );
        StringBuilder sb = new StringBuilder( t.s( "lookup_keys" ) ).append( ": " );
        String[] ks = l.getKeystream();
        String[] kl = l.getKeylookup();
        for ( int i = 0; ks != null && i < ks.length; i++ ) {
          sb.append( i == 0 ? "" : ", " ).append( codigo( ks[ i ] + " = " + ( kl != null && i < kl.length
            ? kl[ i ] : "?" ) ) );
        }
        if ( fuente != null ) {
          sb.append( " (" ).append( t.s( "lookup_in" ) ).append( ' ' ).append( celda( fuente ) ).append( ')' );
        }
        partes.add( sb.toString() );
      } else if ( m instanceof TableInputMeta ) {
        TableInputMeta ti = (TableInputMeta) m;
        if ( ti.getDatabaseMeta() != null ) {
          partes.add( t.s( "connection" ) + ": " + celda( ti.getDatabaseMeta().getName() ) );
        }
        partes.add( t.s( "sql" ) + ": " + codigo( recortar( ti.getSQL(), MAX_SQL ) ) );
      } else if ( m instanceof TableOutputMeta ) {
        TableOutputMeta to = (TableOutputMeta) m;
        String tabla = vacioANull( to.getSchemaName() ) == null ? to.getTableName()
          : to.getSchemaName() + "." + to.getTableName();
        partes.add( t.s( "table" ) + ": " + codigo( tabla ) );
        if ( to.getDatabaseMeta() != null ) {
          partes.add( t.s( "connection" ) + ": " + celda( to.getDatabaseMeta().getName() ) );
        }
      } else if ( m instanceof SortRowsMeta ) {
        partes.add( t.s( "sort_by" ) + ": " + listaCodigo( ( (SortRowsMeta) m ).getFieldName() ) );
      } else if ( m instanceof MergeJoinMeta ) {
        MergeJoinMeta j = (MergeJoinMeta) m;
        partes.add( t.s( "join" ) + " " + celda( j.getJoinType() ) + ": " + listaCodigo( j.getKeyFields1() )
          + " = " + listaCodigo( j.getKeyFields2() ) );
      } else {
        String archivos = archivosDe( m.getResourceDependencies( tm, sm ) );
        if ( archivos != null ) {
          partes.add( t.s( "files" ) + ": " + archivos );
        }
      }
    } catch ( Throwable e ) {
      partes.clear(); // configuracion rara: caer a la descripcion del plugin
    }
    if ( partes.isEmpty() ) {
      return celda( descripcionPlugin( StepPluginType.class, sm.getStepID(), t ) );
    }
    return unir( partes, "; " );
  }

  /** Archivos que declara el step como dependencia (generico: ResourceReference de tipo FILE). */
  private static String archivosDe( List<ResourceReference> refs ) {
    if ( refs == null ) {
      return null;
    }
    List<String> f = new ArrayList<String>();
    for ( ResourceReference r : refs ) {
      for ( ResourceEntry e : r.getEntries() ) {
        if ( e.getResourcetype() == ResourceEntry.ResourceType.FILE && vacioANull( e.getResource() ) != null ) {
          f.add( e.getResource() );
        }
      }
    }
    return f.isEmpty() ? null : listaCodigo( f.toArray( new String[ 0 ] ) );
  }

  /** Condicion compacta en una linea: "(a > b AND NOT c IS NULL)". */
  static String condicion( Condition c ) {
    if ( c.isAtomic() ) {
      StringBuilder sb = new StringBuilder();
      if ( c.isNegated() ) {
        sb.append( "NOT " );
      }
      sb.append( nulo( c.getLeftValuename() ) ).append( ' ' ).append( c.getFunctionDesc() );
      String der = vacioANull( c.getRightValuename() );
      if ( der != null ) {
        sb.append( ' ' ).append( der );
      } else if ( c.getRightExact() != null ) {
        sb.append( " [" ).append( nulo( c.getRightExactString() ) ).append( ']' );
      }
      return sb.toString();
    }
    StringBuilder sb = new StringBuilder();
    if ( c.isNegated() ) {
      sb.append( "NOT " );
    }
    List<Condition> hijos = c.getChildren();
    if ( hijos.size() == 1 && !c.isNegated() ) {
      return condicion( hijos.get( 0 ) );
    }
    sb.append( '(' );
    for ( int i = 0; i < hijos.size(); i++ ) {
      Condition h = hijos.get( i );
      if ( i > 0 ) {
        sb.append( ' ' ).append( h.getOperatorDesc().trim() ).append( ' ' );
      }
      sb.append( condicion( h ) );
    }
    return sb.append( ')' ).toString();
  }

  // ---------------------------------------------------------------- jobs

  private static void job( StringBuilder md, JobMeta jm, T t, Informe inf ) {
    List<JobEntryCopy> orden = ordenJob( jm );
    inf.nodos = orden.size();
    inf.hops = jm.nrJobHops();
    md.append( "## " ).append( t.s( "summary" ) ).append( "\n\n" )
      .append( orden.size() ).append( ' ' ).append( t.s( "n_entries" ) ).append( ", " )
      .append( jm.nrJobHops() ).append( ' ' ).append( t.s( "n_hops" ) ).append( ".\n\n" );
    if ( orden.isEmpty() ) {
      md.append( t.s( "no_steps" ) ).append( "\n\n" );
      return;
    }
    md.append( "## " ).append( t.s( "diagram" ) ).append( "\n\n" );
    md.append( "```mermaid\n" ).append( mermaidJob( jm, orden, t ) ).append( "```\n\n" );
    md.append( "_" ).append( t.s( "legend_job" ) ).append( "_\n\n" );

    md.append( "## " ).append( t.s( "entries" ) ).append( "\n\n" );
    md.append( "| # | " ).append( t.s( "col_name" ) ).append( " | " ).append( t.s( "col_type" ) ).append( " | " )
      .append( t.s( "col_does" ) ).append( " |\n|---|---|---|---|\n" );
    int i = 1;
    for ( JobEntryCopy c : orden ) {
      md.append( "| " ).append( i++ ).append( " | " ).append( celda( c.getName() ) ).append( " | " )
        .append( celda( tipoEntrada( c, t ) ) ).append( " | " ).append( queHaceEntrada( c, t ) ).append( " |\n" );
    }
    md.append( '\n' );
  }

  static List<JobEntryCopy> ordenJob( JobMeta jm ) {
    List<JobEntryCopy> nodos = new ArrayList<JobEntryCopy>( jm.getJobCopies() );
    // START primero, aunque no sea el primero del documento
    for ( int i = 0; i < nodos.size(); i++ ) {
      if ( nodos.get( i ).isStart() ) {
        nodos.add( 0, nodos.remove( i ) );
        break;
      }
    }
    Map<JobEntryCopy, Integer> entrantes = new IdentityHashMap<JobEntryCopy, Integer>();
    for ( JobEntryCopy c : nodos ) {
      entrantes.put( c, 0 );
    }
    List<JobEntryCopy[]> aristas = new ArrayList<JobEntryCopy[]>();
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      if ( h.getFromEntry() != null && h.getToEntry() != null && entrantes.containsKey( h.getFromEntry() )
        && entrantes.containsKey( h.getToEntry() ) ) {
        aristas.add( new JobEntryCopy[] { h.getFromEntry(), h.getToEntry() } );
        entrantes.put( h.getToEntry(), entrantes.get( h.getToEntry() ) + 1 );
      }
    }
    return topologico( nodos, entrantes, aristas );
  }

  static String mermaidJob( JobMeta jm, List<JobEntryCopy> orden, T t ) {
    StringBuilder sb = new StringBuilder( "flowchart LR\n" );
    Map<JobEntryCopy, String> ids = new IdentityHashMap<JobEntryCopy, String>();
    List<String> faltan = new ArrayList<String>();
    int n = 1;
    for ( JobEntryCopy c : orden ) {
      String id = "s" + n++;
      ids.put( c, id );
      List<String> lineas = new ArrayList<String>();
      lineas.add( c.getName() );
      String plugin = c.getEntry() == null ? null : c.getEntry().getPluginId();
      if ( !c.isStart() ) {
        lineas.add( c.isDummy() ? "Dummy" : NombresPlugin.nombre( JobEntryPluginType.class, plugin, t.idioma ) );
      }
      if ( c.isMissing() ) {
        lineas.add( t.s( "missing_plugin" ) );
        faltan.add( id );
      }
      String eti = etiqueta( lineas );
      if ( c.isStart() ) {
        sb.append( "  " ).append( id ).append( "([\"" ).append( eti ).append( "\"])\n" );
      } else {
        sb.append( "  " ).append( id ).append( "[\"" ).append( eti ).append( "\"]\n" );
      }
    }
    List<String> estilos = new ArrayList<String>();
    int arista = 0;
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      String a = ids.get( h.getFromEntry() );
      String b = ids.get( h.getToEntry() );
      if ( a == null || b == null ) {
        continue;
      }
      String texto;
      String flecha = "-->";
      String estilo = null;
      if ( h.isUnconditional() ) {
        texto = t.s( "always" );
      } else if ( h.getEvaluation() ) {
        texto = t.s( "on_success" );
        estilo = "stroke:#2a2,color:#2a2";
      } else {
        texto = t.s( "on_failure" );
        flecha = "-.->";
        estilo = "stroke:#d33,color:#d33";
      }
      if ( !h.isEnabled() ) {
        texto += ", " + t.s( "disabled" );
        flecha = "--x";
        estilo = "stroke:#999,stroke-dasharray:4 3,color:#999";
      }
      sb.append( "  " ).append( a ).append( ' ' ).append( flecha ).append( "|\"" ).append( escaparMermaid( texto ) )
        .append( "\"| " ).append( b ).append( '\n' );
      if ( estilo != null ) {
        estilos.add( "  linkStyle " + arista + " " + estilo + "\n" );
      }
      arista++;
    }
    for ( String e : estilos ) {
      sb.append( e );
    }
    clases( sb, "missing", "stroke:#d33,stroke-dasharray:4 3", faltan );
    return sb.toString();
  }

  private static String tipoEntrada( JobEntryCopy c, T t ) {
    String id = c.getEntry() == null ? null : c.getEntry().getPluginId();
    return NombresPlugin.conId( JobEntryPluginType.class, id, t.idioma );
  }

  static String queHaceEntrada( JobEntryCopy c, T t ) {
    JobEntryInterface e = c.getEntry();
    List<String> partes = new ArrayList<String>();
    try {
      if ( e instanceof JobEntrySpecial && c.isStart() ) {
        String s = t.s( "start" );
        if ( ( (JobEntrySpecial) e ).isRepeat() ) {
          s += " (" + t.s( "repeats" ) + ")";
        }
        partes.add( s );
      } else if ( e instanceof JobEntryTrans ) {
        partes.add( t.s( "runs_trans" ) + ": " + codigo( ( (JobEntryTrans) e ).getFilename() ) );
      } else if ( e instanceof JobEntryJob ) {
        partes.add( t.s( "runs_job" ) + ": " + codigo( ( (JobEntryJob) e ).getFilename() ) );
      } else if ( e instanceof JobEntrySQL ) {
        JobEntrySQL q = (JobEntrySQL) e;
        if ( q.getDatabase() != null ) {
          partes.add( t.s( "connection" ) + ": " + celda( q.getDatabase().getName() ) );
        }
        partes.add( q.getSQLFromFile() ? t.s( "files" ) + ": " + codigo( q.getSQLFilename() )
          : t.s( "sql" ) + ": " + codigo( recortar( q.getSQL(), MAX_SQL ) ) );
      }
    } catch ( Throwable ex ) {
      partes.clear();
    }
    if ( partes.isEmpty() ) {
      return celda( descripcionPlugin( JobEntryPluginType.class, e == null ? null : e.getPluginId(), t ) );
    }
    return unir( partes, "; " );
  }

  // ---------------------------------------------------------------- comunes

  private static void conexiones( StringBuilder md, AbstractMeta m, T t ) {
    List<DatabaseMeta> dbs = m.getDatabases();
    if ( dbs == null || dbs.isEmpty() ) {
      return;
    }
    md.append( "## " ).append( t.s( "connections" ) ).append( "\n\n" );
    md.append( "| " ).append( t.s( "col_name" ) ).append( " | " ).append( t.s( "col_type" ) ).append( " | " )
      .append( t.s( "col_host" ) ).append( " | " ).append( t.s( "col_db" ) ).append( " |\n|---|---|---|---|\n" );
    for ( DatabaseMeta d : dbs ) { // nunca la contrasenia
      md.append( "| " ).append( celda( d.getName() ) ).append( " | " ).append( celda( d.getPluginId() ) )
        .append( " | " ).append( celda( d.getHostname() ) ).append( " | " ).append( celda( d.getDatabaseName() ) )
        .append( " |\n" );
    }
    md.append( '\n' );
  }

  private static void parametros( StringBuilder md, AbstractMeta m, T t ) throws Exception {
    String[] ps = m.listParameters();
    if ( ps == null || ps.length == 0 ) {
      return;
    }
    md.append( "## " ).append( t.s( "params" ) ).append( "\n\n" );
    md.append( "| " ).append( t.s( "col_name" ) ).append( " | " ).append( t.s( "col_default" ) ).append( " | " )
      .append( t.s( "description" ) ).append( " |\n|---|---|---|\n" );
    for ( String p : ps ) {
      String def = m.getParameterDefault( p );
      md.append( "| " ).append( codigo( p ) ).append( " | " ).append( def == null || def.isEmpty() ? "" : codigo( def ) )
        .append( " | " ).append( celda( m.getParameterDescription( p ) ) ).append( " |\n" );
    }
    md.append( '\n' );
  }

  private static void notas( StringBuilder md, AbstractMeta m, T t ) {
    List<String> textos = new ArrayList<String>();
    for ( int i = 0; i < m.nrNotes(); i++ ) {
      NotePadMeta n = m.getNote( i );
      if ( n != null && vacioANull( n.getNote() ) != null ) {
        textos.add( n.getNote().trim() );
      }
    }
    if ( textos.isEmpty() ) {
      return;
    }
    md.append( "## " ).append( t.s( "notes" ) ).append( "\n\n" );
    for ( String s : textos ) {
      for ( String linea : s.replace( "\r", "" ).split( "\n", -1 ) ) {
        md.append( "> " ).append( linea ).append( '\n' );
      }
      md.append( '\n' );
    }
  }

  private static void validacion( StringBuilder md, ObjectNode v, T t ) {
    md.append( "## " ).append( t.s( "validation" ) ).append( "\n\n" );
    int err = v.path( "errors" ).asInt();
    int adv = v.path( "warnings" ).asInt();
    md.append( "**" ).append( err ).append( ' ' ).append( t.s( "n_errors" ) ).append( ", " ).append( adv ).append( ' ' )
      .append( t.s( "n_warnings" ) ).append( ".**\n\n" );
    JsonNode ps = v.path( "by_step" );
    if ( err + adv == 0 || !ps.isObject() || ps.size() == 0 ) {
      md.append( t.s( "no_issues" ) ).append( "\n\n" );
      return;
    }
    for ( Iterator<Map.Entry<String, JsonNode>> it = ps.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      for ( JsonNode linea : e.getValue() ) {
        String s = linea.asText();
        String tipo = "";
        if ( s.startsWith( "ERROR: " ) ) {
          tipo = "**" + t.s( "error_label" ) + ":** ";
          s = s.substring( 7 );
        } else if ( s.startsWith( "WARNING: " ) ) {
          tipo = "**" + t.s( "warning_label" ) + ":** ";
          s = s.substring( 9 );
        }
        md.append( "- " ).append( texto( e.getKey() ) ).append( " — " ).append( tipo ).append( texto( s ) )
          .append( '\n' );
      }
    }
    md.append( '\n' );
  }

  // ---------------------------------------------------------------- plugins

  static String nombrePlugin( Class<? extends org.pentaho.di.core.plugins.PluginTypeInterface> tipo, String id ) {
    if ( id == null ) {
      return "?";
    }
    try {
      PluginInterface p = PluginRegistry.getInstance().findPluginWithId( tipo, id );
      if ( p != null && vacioANull( p.getName() ) != null ) {
        return p.getName().trim();
      }
    } catch ( Exception ignored ) {
      // sin registro: el id
    }
    return id;
  }

  /**
   * La descripcion del tipo en el idioma de t (no en el de Spoon: NombresPlugin); si Kettle no la trae
   * traducida, el nombre del tipo en ese idioma.
   */
  private static String descripcionPlugin( Class<? extends org.pentaho.di.core.plugins.PluginTypeInterface> tipo,
                                           String id, T t ) {
    if ( id == null ) {
      return "";
    }
    String d = NombresPlugin.descripcion( tipo, id, t.idioma );
    return d != null ? unaLinea( d ) : NombresPlugin.nombre( tipo, id, t.idioma );
  }

  // ---------------------------------------------------------------- escapes

  /** Varias lineas de etiqueta de nodo, escapadas y unidas con &lt;br/&gt;. */
  private static String etiqueta( List<String> lineas ) {
    StringBuilder sb = new StringBuilder();
    for ( String l : lineas ) {
      if ( l == null || l.trim().isEmpty() ) {
        continue;
      }
      sb.append( sb.length() == 0 ? "" : "<br/>" ).append( escaparMermaid( l ) );
    }
    return sb.toString();
  }

  /**
   * Texto seguro dentro de "..." en Mermaid: comillas, &lt; &gt; &amp; # y backtick van como codigos de
   * entidad (#34; ...), los saltos de linea como espacio.
   */
  static String escaparMermaid( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      switch ( c ) {
        case '"':
          sb.append( "#34;" );
          break;
        case '#':
          sb.append( "#35;" );
          break;
        case '&':
          sb.append( "#38;" );
          break;
        case '<':
          sb.append( "#60;" );
          break;
        case '>':
          sb.append( "#62;" );
          break;
        case '`':
          sb.append( "#96;" );
          break;
        case '\r':
        case '\n':
        case '\t':
          sb.append( ' ' );
          break;
        default:
          sb.append( c < 0x20 ? ' ' : c );
      }
    }
    return sb.toString().trim();
  }

  private static void clases( StringBuilder sb, String clase, String estilo, List<String> ids ) {
    if ( ids.isEmpty() ) {
      return;
    }
    sb.append( "  classDef " ).append( clase ).append( ' ' ).append( estilo ).append( '\n' );
    sb.append( "  class " ).append( unir( ids, "," ) ).append( ' ' ).append( clase ).append( '\n' );
  }

  /** Celda de tabla Markdown: una linea, con | escapado. */
  static String celda( String s ) {
    return s == null ? "" : texto( s ).replace( "|", "\\|" );
  }

  /** Valor como codigo en linea (rutas, SQL, nombres de campo), apto para tablas. */
  static String codigo( String s ) {
    if ( s == null || s.trim().isEmpty() ) {
      return "";
    }
    String v = unaLinea( s ).replace( "|", "\\|" );
    String marca = v.contains( "`" ) ? "``" : "`";
    String esp = v.startsWith( "`" ) || v.endsWith( "`" ) ? " " : "";
    return marca + esp + v + esp + marca;
  }

  private static String listaCodigo( String[] vs ) {
    if ( vs == null || vs.length == 0 ) {
      return "";
    }
    List<String> out = new ArrayList<String>();
    for ( String v : vs ) {
      if ( vacioANull( v ) != null ) {
        out.add( codigo( v ) );
      }
    }
    return unir( out, ", " );
  }

  private static String unaLinea( String s ) {
    return s == null ? "" : s.replaceAll( "\\s+", " " ).trim();
  }

  /** Texto suelto de Markdown (titulos, celdas): una linea y sin que &lt; &gt; se lean como HTML. */
  private static String texto( String s ) {
    return unaLinea( s ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
  }

  private static String recortar( String s, int max ) {
    String v = unaLinea( s );
    return v.length() <= max ? v : v.substring( 0, max - 3 ).trim() + "...";
  }

  private static String vacioANull( String s ) {
    return s == null || s.trim().isEmpty() ? null : s;
  }

  private static String nulo( String s ) {
    return s == null ? "" : s;
  }

  private static String unir( List<String> l, String sep ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      sb.append( sb.length() == 0 ? "" : sep ).append( s );
    }
    return sb.toString();
  }
}
