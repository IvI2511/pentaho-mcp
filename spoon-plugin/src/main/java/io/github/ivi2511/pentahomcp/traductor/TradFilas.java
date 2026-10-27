package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.errorhandling.StreamInterface;
import org.pentaho.di.trans.steps.databasejoin.DatabaseJoinMeta;
import org.pentaho.di.trans.steps.databaselookup.DatabaseLookupMeta;
import org.pentaho.di.trans.steps.denormaliser.DenormaliserMeta;
import org.pentaho.di.trans.steps.denormaliser.DenormaliserTargetField;
import org.pentaho.di.trans.steps.flattener.FlattenerMeta;
import org.pentaho.di.trans.steps.fuzzymatch.FuzzyMatchMeta;
import org.pentaho.di.trans.steps.groupby.GroupByMeta;
import org.pentaho.di.trans.steps.memgroupby.MemoryGroupByMeta;
import org.pentaho.di.trans.steps.mergejoin.MergeJoinMeta;
import org.pentaho.di.trans.steps.mergerows.MergeRowsMeta;
import org.pentaho.di.trans.steps.multimerge.MultiMergeJoinMeta;
import org.pentaho.di.trans.steps.normaliser.NormaliserMeta;
import org.pentaho.di.trans.steps.reservoirsampling.ReservoirSamplingMeta;
import org.pentaho.di.trans.steps.rowsfromresult.RowsFromResultMeta;
import org.pentaho.di.trans.steps.samplerows.SampleRowsMeta;
import org.pentaho.di.trans.steps.sort.SortRowsMeta;
import org.pentaho.di.trans.steps.sortedmerge.SortedMergeMeta;
import org.pentaho.di.trans.steps.streamlookup.StreamLookupMeta;
import org.pentaho.di.trans.steps.uniquerows.UniqueRowsMeta;
import org.pentaho.di.trans.steps.uniquerowsbyhashset.UniqueRowsByHashSetMeta;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Traductores de los steps que trabajan con filas y flujos: ordenar, quitar duplicados, agrupar, pivotar, buscar,
 * unir, muestrear, bloquear y pasar filas entre transformaciones. Los steps que PDI resuelve «por rachas» (Group by,
 * Unique rows, Analytic query, Row denormaliser: comparan cada fila con la anterior y esperan la entrada ordenada) se
 * emulan tal cual, con una racha por cada tramo de filas seguidas con la misma clave.
 */
final class TradFilas {

  private TradFilas() {
  }

  static void registrar() {
    Registro.step( TradFilas::dummy, "Dummy" );
    Registro.step( TradFilas::ordenar, "SortRows" );
    Registro.step( TradFilas::unicas, "Unique" );
    Registro.step( TradFilas::unicasHash, "UniqueRowsByHashSet" );
    Registro.step( TradFilas::agrupar, "GroupBy" );
    Registro.step( TradFilas::agruparMemoria, "MemoryGroupBy" );
    Registro.step( TradFilas::analitica, "AnalyticQuery" );
    Registro.step( TradFilas::desnormalizar, "Denormaliser" );
    Registro.step( TradFilas::normalizar, "Normaliser" );
    Registro.step( TradFilas::aplanar, "Flattener", "Flatterner" );
    Registro.step( TradFilas::buscarFlujo, "StreamLookup" );
    Registro.step( TradFilas::buscarBase, "DBLookup" );
    Registro.step( TradFilas::unirBase, "DBJoin" );
    Registro.step( TradFilas::mergeJoin, "MergeJoin" );
    Registro.step( TradFilas::mergeRows, "MergeRows" );
    Registro.step( TradFilas::multiway, "MultiwayMergeJoin" );
    Registro.step( TradFilas::anexar, "Append" );
    Registro.step( TradFilas::mezclaOrdenada, "SortedMerge" );
    Registro.step( TradFilas::prioridad, "PrioritizeStreams" );
    Registro.step( TradFilas::fuzzy, "FuzzyMatch" );
    Registro.step( TradFilas::muestra, "SampleRows" );
    Registro.step( TradFilas::reservorio, "ReservoirSampling" );
    Registro.step( TradFilas::bloqueo, "BlockingStep" );
    Registro.step( TradFilas::esperar, "BlockUntilStepsFinish" );
    Registro.step( TradFilas::abortar, "Abort" );
    Registro.step( TradFilas::vacio, "DetectEmptyStream" );
    Registro.step( TradFilas::ultima, "DetectLastRow" );
    Registro.step( TradFilas::demora, "Delay" );
    Registro.step( TradFilas::aResultado, "RowsToResult" );
    Registro.step( TradFilas::deResultado, "RowsFromResult" );
    Registro.step( TradFilas::subtrans, "Mapping", "SimpleMapping", "TransExecutor", "JobExecutor",
      "MetaInject", "SingleThreader" );
  }

  // ================================================================ utilidades

  private static Bloque bloque( StepMeta s ) {
    return new Bloque( s.getName(), s.getStepID() );
  }

  /** Un DataFrame vacío (sin columnas). */
  private static String sinFilas( CtxTrad ctx ) {
    return ctx.pandas() ? "pd.DataFrame()" : "spark.createDataFrame([], T.StructType([]))";
  }

  /** La entrada principal (o varias unidas); un DataFrame vacío si no tiene. */
  private static String entrada( StepMeta s, CtxTrad ctx, Bloque b ) {
    String in = ctx.entradaUnida( s, b );
    return in == null ? sinFilas( ctx ) : in;
  }

  /** ["a", "b"] */
  static String lista( List<String> v ) {
    StringBuilder b = new StringBuilder( "[" );
    for ( String x : v ) {
      b.append( b.length() == 1 ? "" : ", " ).append( CtxTrad.lit( x ) );
    }
    return b.append( ']' ).toString();
  }

  static String lista( String[] v ) {
    return lista( v == null ? new ArrayList<String>() : Arrays.asList( v ) );
  }

  /** "a", "b" (para argumentos sueltos de PySpark). */
  static String args( List<String> v ) {
    String l = lista( v );
    return l.substring( 1, l.length() - 1 );
  }

  private static List<String> noVacios( String[] v ) {
    List<String> r = new ArrayList<>();
    if ( v != null ) {
      for ( String x : v ) {
        if ( x != null && !x.trim().isEmpty() ) {
          r.add( x );
        }
      }
    }
    return r;
  }

  private static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  /** Literal Python de un valor de PDI dado como texto, según el tipo de Kettle (para valores por defecto). */
  static String valor( String v, int tipo ) {
    if ( v == null || v.isEmpty() ) {
      return "None";
    }
    switch ( tipo ) {
      case ValueMetaInterface.TYPE_INTEGER:
        try {
          return Long.toString( Long.parseLong( v.trim() ) );
        } catch ( NumberFormatException e ) {
          return CtxTrad.lit( v );
        }
      case ValueMetaInterface.TYPE_NUMBER:
      case ValueMetaInterface.TYPE_BIGNUMBER:
        try {
          return Double.toString( Double.parseDouble( v.trim() ) );
        } catch ( NumberFormatException e ) {
          return CtxTrad.lit( v );
        }
      case ValueMetaInterface.TYPE_BOOLEAN:
        String t = v.trim().toUpperCase( Locale.ROOT );
        return t.startsWith( "Y" ) || t.startsWith( "T" ) || "1".equals( t ) ? "True" : "False";
      default:
        return CtxTrad.lit( v );
    }
  }

  /** Tipo de un campo de entrada del step (TYPE_NONE si no se conoce). */
  private static int tipo( StepMeta s, CtxTrad ctx, String campo ) {
    RowMetaInterface r = ctx.camposEntrada( s );
    ValueMetaInterface v = r.searchValueMeta( campo );
    return v == null ? ValueMetaInterface.TYPE_NONE : v.getType();
  }

  /** Nombre del step de la entrada de info número i (o null). */
  private static String infoNombre( StepMeta s, int i ) {
    List<StreamInterface> l = s.getStepMetaInterface().getStepIOMeta().getInfoStreams();
    if ( i >= l.size() ) {
      return null;
    }
    StreamInterface st = l.get( i );
    return st.getStepMeta() != null ? st.getStepMeta().getName() : st.getStepname();
  }

  /** La variable de un step de info, o un DataFrame vacío (y una nota) si falta. */
  private static String info( StepMeta s, CtxTrad ctx, Bloque b, String nombre ) {
    String v = nombre == null ? null : ctx.info( s, nombre );
    if ( v == null ) {
      b.manual( ctx.t( "Falta el step de búsqueda/entrada «" + nombre + "».", "Missing lookup/input step '" + nombre
        + "'.", "Falta o step de busca/entrada «" + nombre + "»." ) );
      return sinFilas( ctx );
    }
    return v;
  }

  /** Hijos directos con ese tag. */
  static List<Element> hijos( Element e, String tag ) {
    List<Element> r = new ArrayList<>();
    if ( e == null ) {
      return r;
    }
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && tag.equals( n.getNodeName() ) ) {
        r.add( (Element) n );
      }
    }
    return r;
  }

  static Element hijoE( Element e, String tag ) {
    List<Element> l = hijos( e, tag );
    return l.isEmpty() ? null : l.get( 0 );
  }

  /** Número de un valor de PDI (con variables: float(...) sobre el texto). */
  private static String numero( CtxTrad ctx, String v, String porDefecto ) {
    if ( vacio( v ) ) {
      return porDefecto;
    }
    try {
      double d = Double.parseDouble( v.trim() );
      return d == Math.rint( d ) && !v.contains( "." ) ? Long.toString( (long) d ) : Double.toString( d );
    } catch ( NumberFormatException e ) {
      return "float(" + ctx.texto( v ) + ")";
    }
  }

  // ================================================================ funciones auxiliares de Python

  private static void ayudaCambia( CtxTrad ctx ) {
    if ( ctx.pandas() ) {
      ctx.ayudante( "pdi_cambia", "def pdi_cambia(claves):\n"
        + "    \"\"\"" + ctx.t( "True en la primera fila de cada racha de filas seguidas con las mismas claves "
          + "(PDI compara cada fila con la anterior; nulo = nulo).", "True on the first row of each run of "
            + "consecutive rows with the same keys (PDI compares each row with the previous one; null = null).",
          "True na primeira linha de cada sequência de linhas seguidas com as mesmas chaves (o PDI compara cada "
            + "linha com a anterior; nulo = nulo)." ) + "\"\"\"\n"
        + "    antes = claves.shift()\n"
        + "    distinto = claves.ne(antes).astype(\"boolean\").fillna(True) & ~(claves.isna() & antes.isna())\n"
        + "    return distinto.any(axis=1) | (np.arange(len(claves)) == 0)" );
    } else {
      ctx.ayudante( "pdi_rachas", "def pdi_rachas(df, claves):\n"
        + "    \"\"\"" + ctx.t( "Añade _orden (orden de llegada) y _racha: el número de racha de filas seguidas con "
          + "las mismas claves, como agrupa PDI (compara cada fila con la anterior; nulo = nulo).",
          "Adds _orden (arrival order) and _racha: the number of the run of consecutive rows with the same keys, "
            + "as PDI groups (it compares each row with the previous one; null = null).",
          "Adiciona _orden (ordem de chegada) e _racha: o número da sequência de linhas seguidas com as mesmas "
            + "chaves, como o PDI agrupa (compara cada linha com a anterior; nulo = nulo)." ) + "\"\"\"\n"
        + "    df = df.withColumn(\"_orden\", F.monotonically_increasing_id())\n"
        + "    w = Window.orderBy(\"_orden\")\n"
        + "    igual = F.lag(\"_orden\").over(w).isNotNull()\n"
        + "    for c in claves:\n"
        + "        igual = igual & F.col(c).eqNullSafe(F.lag(c).over(w))\n"
        + "    return df.withColumn(\"_racha\", F.sum(F.when(igual, 0).otherwise(1)).over(w))" );
    }
  }

  private static void ayudaPercentil( CtxTrad ctx ) {
    String doc = ctx.t( "Percentil como PDI (Apache Commons Math): posición p·(n+1)/100 e interpolación lineal.",
      "Percentile as PDI computes it (Apache Commons Math): position p·(n+1)/100 and linear interpolation.",
      "Percentil como o PDI (Apache Commons Math): posição p·(n+1)/100 e interpolação linear." );
    if ( ctx.pandas() ) {
      ctx.ayudante( "pdi_percentil", "def pdi_percentil(s, p):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    v = np.sort(s.dropna().to_numpy(dtype=float))\n"
        + "    n = len(v)\n"
        + "    if n == 0:\n"
        + "        return np.nan\n"
        + "    pos = p * (n + 1) / 100\n"
        + "    if pos < 1:\n"
        + "        return v[0]\n"
        + "    if pos >= n:\n"
        + "        return v[-1]\n"
        + "    i = int(pos)\n"
        + "    return v[i - 1] + (pos - i) * (v[i] - v[i - 1])" );
    } else {
      ctx.ayudante( "pdi_percentil", "def pdi_percentil(c, p):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    v = F.array_sort(F.collect_list(c.cast(\"double\")))\n"
        + "    n = F.size(v)\n"
        + "    pos = F.lit(p / 100) * (n + 1)\n"
        + "    i = F.floor(pos).cast(\"int\")\n"
        + "    bajo = F.try_element_at(v, F.greatest(i, F.lit(1)))\n"
        + "    alto = F.try_element_at(v, F.least(i + 1, n))\n"
        + "    return (F.when(n == 0, F.lit(None).cast(\"double\")).when(pos < 1, F.try_element_at(v, F.lit(1)))\n"
        + "            .when(pos >= n, F.try_element_at(v, n)).otherwise(bajo + (pos - i) * (alto - bajo)))" );
    }
  }

  private static void ayudaRango( CtxTrad ctx ) {
    String doc = ctx.t( "Percentil por rango más cercano: el valor ordenado en la posición ⌈p·n/100⌉.",
      "Nearest-rank percentile: the sorted value at position ⌈p·n/100⌉.",
      "Percentil pelo posto mais próximo: o valor ordenado na posição ⌈p·n/100⌉." );
    if ( ctx.pandas() ) {
      ctx.ayudante( "pdi_percentil_rango", "def pdi_percentil_rango(s, p):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    v = np.sort(s.dropna().to_numpy(dtype=float))\n"
        + "    if len(v) == 0:\n"
        + "        return np.nan\n"
        + "    return v[max(int(np.ceil(p * len(v) / 100)), 1) - 1]" );
    } else {
      ctx.ayudante( "pdi_percentil_rango", "def pdi_percentil_rango(c, p):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    v = F.array_sort(F.collect_list(c.cast(\"double\")))\n"
        + "    return F.try_element_at(v, F.greatest(F.ceil(F.lit(p) * F.size(v) / 100).cast(\"int\"), F.lit(1)))" );
    }
  }

  private static void ayudaConcat( CtxTrad ctx ) {
    String doc = ctx.t( "Concatena los valores no nulos en el orden de llegada (texto vacío = nulo, como PDI).",
      "Concatenates the non-null values in arrival order (empty text = null, as in PDI).",
      "Concatena os valores não nulos na ordem de chegada (texto vazio = nulo, como no PDI)." );
    if ( ctx.pandas() ) {
      ctx.ayudante( "pdi_concatenar", "def pdi_concatenar(s, sep):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    texto = sep.join(s.dropna().astype(str))\n"
        + "    return texto if texto else None" );
    } else {
      ctx.ayudante( "pdi_concatenar", "def pdi_concatenar(c, sep):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    v = F.array_sort(F.collect_list(F.struct(F.col(\"_orden\"), c.cast(\"string\").alias(\"v\"))))\n"
        + "    return F.nullif(F.concat_ws(sep, v.getField(\"v\")), F.lit(\"\"))" );
    }
  }

  // ================================================================ Dummy

  /** Dummy (do nothing) → la misma variable. */
  static Bloque dummy( StepMeta s, CtxTrad ctx ) {
    Bloque b = bloque( s );
    String in = ctx.entradaUnida( s, b );
    b.l( ctx.df( s ) + " = " + ( in == null ? sinFilas( ctx ) : in ) );
    b.vista = ctx.df( s );
    return b;
  }

  // ================================================================ Sort rows

  /** Sort rows → sort_values estable (nulos primero al subir, como PDI) / orderBy. */
  static Bloque ordenar( StepMeta s, CtxTrad ctx ) {
    SortRowsMeta m = (SortRowsMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String[] campos = m.getFieldName();
    boolean[] asc = m.getAscending();
    boolean[] caso = m.getCaseSensitive();
    List<String> nombres = new ArrayList<>();
    List<String> sinCaso = new ArrayList<>();
    StringBuilder orden = new StringBuilder();
    boolean todosAsc = true;
    boolean todosDesc = true;
    for ( int i = 0; i < campos.length; i++ ) {
      nombres.add( campos[i] );
      boolean a = asc == null || i >= asc.length || asc[i];
      todosAsc &= a;
      todosDesc &= !a;
      orden.append( i == 0 ? "" : ", " ).append( a ? "True" : "False" );
      boolean cs = caso == null || i >= caso.length || caso[i];
      if ( !cs && tipo( s, ctx, campos[i] ) == ValueMetaInterface.TYPE_STRING ) {
        sinCaso.add( campos[i] );
      }
    }
    if ( campos.length == 0 ) {
      b.l( out + " = " + in );
      b.vista = out;
      return b;
    }
    if ( ctx.pandas() ) {
      String clave = sinCaso.isEmpty() ? "" : ", key=lambda c: c.str.lower() if c.name in " + lista( sinCaso )
        + " else c";
      b.l( out + " = " + in + ".sort_values(" + lista( nombres ) + ", ascending=" + ( todosAsc ? "True" : todosDesc
        ? "False" : "[" + orden + "]" ) + ", na_position=" + ( todosDesc ? "\"last\"" : "\"first\"" )
        + ", kind=\"stable\"" + clave + ")" );
      if ( m.isOnlyPassingUniqueRows() ) {
        b.l( out + " = " + out + ".drop_duplicates(subset=" + lista( nombres ) + ")" );
      }
      b.l( out + " = " + out + ".reset_index(drop=True)" );
      if ( !todosAsc && !todosDesc ) {
        b.aproximado( ctx.t( "Con orden mixto, pandas pone los nulos primero en todas las columnas; PDI los pone al "
          + "final en las descendentes.", "With mixed order, pandas puts nulls first in every column; PDI puts them "
            + "last in descending ones.", "Com ordem mista, o pandas põe os nulos primeiro em todas as colunas; o PDI "
              + "põe-nos no fim nas descendentes." ) );
      }
    } else {
      StringBuilder cols = new StringBuilder();
      for ( int i = 0; i < campos.length; i++ ) {
        boolean a = asc == null || i >= asc.length || asc[i];
        String c = sinCaso.contains( campos[i] ) ? "F.lower(F.col(" + CtxTrad.lit( campos[i] ) + "))" : "F.col("
          + CtxTrad.lit( campos[i] ) + ")";
        cols.append( i == 0 ? "" : ", " ).append( c ).append( a ? ".asc_nulls_first()" : ".desc_nulls_last()" );
      }
      b.l( out + " = " + in + ".orderBy(" + cols + ")" );
      if ( m.isOnlyPassingUniqueRows() ) {
        ayudaCambia( ctx );
        b.l( out + " = pdi_rachas(" + out + ", " + lista( nombres ) + ").filter(F.col(\"_orden\") == F.min(\"_orden\")"
          + ".over(Window.partitionBy(\"_racha\"))).orderBy(\"_orden\").drop(\"_orden\", \"_racha\")" );
      }
      b.nota( ctx.t( "Spark no garantiza un orden estable entre filas con la misma clave.", "Spark does not guarantee "
        + "a stable order between rows with the same key.", "O Spark não garante ordem estável entre linhas com a "
          + "mesma chave." ) );
    }
    b.vista = out;
    return b;
  }

  // ================================================================ Unique rows

  /** Unique rows: quita las filas iguales a la ANTERIOR (espera la entrada ordenada), con contador y errores. */
  static Bloque unicas( StepMeta s, CtxTrad ctx ) {
    UniqueRowsMeta m = (UniqueRowsMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    List<String> campos = new ArrayList<>();
    List<String> sinCaso = new ArrayList<>();
    String[] cf = m.getCompareFields();
    boolean[] ci = m.getCaseInsensitive();
    for ( int i = 0; cf != null && i < cf.length; i++ ) {
      if ( !vacio( cf[i] ) ) {
        campos.add( cf[i] );
        if ( ci != null && i < ci.length && ci[i] ) {
          sinCaso.add( cf[i] );
        }
      }
    }
    boolean contar = m.isCountRows() && !vacio( m.getCountField() );
    boolean error = m.isRejectDuplicateRow() && CtxTrad.tieneError( s );
    b.l( "# " + ctx.t( "PDI compara cada fila con la anterior: sólo quita duplicados SEGUIDOS (entrada ordenada).",
      "PDI compares each row with the previous one: it only removes CONSECUTIVE duplicates (sorted input).",
      "O PDI compara cada linha com a anterior: só remove duplicados SEGUIDOS (entrada ordenada)." ) );
    ayudaCambia( ctx );
    if ( ctx.pandas() ) {
      String claves = campos.isEmpty() ? in : in + "[" + lista( campos ) + "]";
      if ( !sinCaso.isEmpty() ) {
        claves = claves + ".apply(lambda c: c.str.lower() if c.name in " + lista( sinCaso ) + " else c)";
      }
      String nueva = ctx.nuevo( "_nueva" );
      b.l( nueva + " = pdi_cambia(" + claves + ")" );
      b.l( out + " = " + in + "[" + nueva + "]" );
      if ( contar ) {
        b.l( out + " = " + out + ".assign(**{" + CtxTrad.lit( m.getCountField() ) + ": " + nueva + ".groupby(" + nueva
          + ".cumsum()).size().to_numpy()})" );
      }
      if ( error ) {
        String e = ctx.dfError( s );
        b.l( e + " = " + in + "[~" + nueva + "].reset_index(drop=True)" );
        ctx.ponerSalidaError( s, e );
      }
      b.l( out + " = " + out + ".reset_index(drop=True)" );
    } else {
      List<String> claves = new ArrayList<>( campos );
      String d = ctx.nuevo( "_d" );
      String base = in;
      if ( !sinCaso.isEmpty() ) {
        StringBuilder w = new StringBuilder( in );
        for ( String c : sinCaso ) {
          w.append( ".withColumn(" ).append( CtxTrad.lit( "_min_" + c ) ).append( ", F.lower(F.col(" ).append(
            CtxTrad.lit( c ) ).append( ")))" );
          claves.set( claves.indexOf( c ), "_min_" + c );
        }
        base = w.toString();
      }
      b.l( d + " = pdi_rachas(" + base + ", " + ( campos.isEmpty() ? in + ".columns" : lista( claves ) ) + ")" );
      b.l( d + " = " + d + ".withColumn(\"_primera\", F.col(\"_orden\") == F.min(\"_orden\").over(Window.partitionBy("
        + "\"_racha\")))" + ( contar ? ".withColumn(" + CtxTrad.lit( m.getCountField() ) + ", F.count(F.lit(1))"
          + ".over(Window.partitionBy(\"_racha\")))" : "" ) );
      List<String> fuera = new ArrayList<>( Arrays.asList( "_orden", "_racha", "_primera" ) );
      for ( String c : sinCaso ) {
        fuera.add( "_min_" + c );
      }
      b.l( out + " = " + d + ".filter(\"_primera\").orderBy(\"_orden\").drop(" + args( fuera ) + ")" );
      if ( error ) {
        String e = ctx.dfError( s );
        b.l( e + " = " + d + ".filter(~F.col(\"_primera\")).orderBy(\"_orden\").drop(" + args( fuera ) + ( contar
          ? ", " + CtxTrad.lit( m.getCountField() ) : "" ) + ")" );
        ctx.ponerSalidaError( s, e );
      }
    }
    if ( m.isRejectDuplicateRow() && !CtxTrad.tieneError( s ) ) {
      b.nota( ctx.t( "Los duplicados rechazados no tienen hop de error: se descartan.", "Rejected duplicates have no "
        + "error hop: they are dropped.", "Os duplicados rejeitados não têm hop de erro: são descartados." ) );
    }
    b.vista = out;
    return b;
  }

  /** Unique rows (HashSet): quita duplicados en todo el flujo (se queda la primera aparición). */
  static Bloque unicasHash( StepMeta s, CtxTrad ctx ) {
    UniqueRowsByHashSetMeta m = (UniqueRowsByHashSetMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    List<String> campos = noVacios( m.getCompareFields() );
    String sub = campos.isEmpty() ? "" : "subset=" + lista( campos );
    boolean error = m.isRejectDuplicateRow() && CtxTrad.tieneError( s );
    if ( ctx.pandas() ) {
      b.l( out + " = " + in + ".drop_duplicates(" + sub + ").reset_index(drop=True)" );
      if ( error ) {
        String e = ctx.dfError( s );
        b.l( e + " = " + in + "[" + in + ".duplicated(" + sub + ")].reset_index(drop=True)" );
        ctx.ponerSalidaError( s, e );
      }
    } else {
      if ( campos.isEmpty() ) {
        b.l( out + " = " + in + ".dropDuplicates()" );
      } else {
        String d = ctx.nuevo( "_d" );
        b.l( d + " = " + in + ".withColumn(\"_orden\", F.monotonically_increasing_id())" );
        b.l( out + " = " + d + ".filter(F.col(\"_orden\") == F.min(\"_orden\").over(Window.partitionBy("
          + args( campos ) + "))).orderBy(\"_orden\").drop(\"_orden\")" );
      }
      if ( error ) {
        String e = ctx.dfError( s );
        b.l( e + " = " + in + ".exceptAll(" + out + ")" );
        ctx.ponerSalidaError( s, e );
      }
      b.nota( ctx.t( "Spark no conserva el orden de llegada de las filas.", "Spark does not keep the arrival order of "
        + "the rows.", "O Spark não mantém a ordem de chegada das linhas." ) );
    }
    b.vista = out;
    return b;
  }

  // ================================================================ Group by

  /** Group by: agrega por rachas de claves iguales SEGUIDAS, como PDI (que espera la entrada ordenada). */
  static Bloque agrupar( StepMeta s, CtxTrad ctx ) {
    GroupByMeta m = (GroupByMeta) s.getStepMetaInterface();
    int n = m.getAggregateField() == null ? 0 : m.getAggregateField().length;
    int[] tipos = new int[n];
    for ( int i = 0; i < n; i++ ) {
      tipos[i] = m.getAggregateType()[i];
    }
    return agregar( s, ctx, noVacios( m.getGroupField() ), m.getAggregateField(), m.getSubjectField(), tipos,
      m.getValueField(), m.passAllRows(), m.isAddingLineNrInGroup() ? m.getLineNrInGroupField() : null,
      m.isAlwaysGivingBackOneRow(), true );
  }

  /** Memory group by: un group by de verdad (no necesita la entrada ordenada). */
  static Bloque agruparMemoria( StepMeta s, CtxTrad ctx ) {
    MemoryGroupByMeta m = (MemoryGroupByMeta) s.getStepMetaInterface();
    int n = m.getAggregateField() == null ? 0 : m.getAggregateField().length;
    int[] tipos = new int[n];
    for ( int i = 0; i < n; i++ ) {
      tipos[i] = GroupByMeta.getType( MemoryGroupByMeta.getTypeDesc( m.getAggregateType()[i] ) );
    }
    Bloque b = agregar( s, ctx, noVacios( m.getGroupField() ), m.getAggregateField(), m.getSubjectField(), tipos,
      m.getValueField(), false, null, m.isAlwaysGivingBackOneRow(), false );
    b.nota( ctx.t( "PDI devuelve los grupos en el orden de su tabla hash; aquí salen en orden de aparición.",
      "PDI returns the groups in its hash table order; here they come in order of appearance.",
      "O PDI devolve os grupos na ordem da sua tabela hash; aqui saem por ordem de aparição." ) );
    return b;
  }

  /** ¿El step anterior (único) es un Sort rows que empieza por estas claves? Entonces las rachas = los grupos. */
  private static boolean ordenadaPor( StepMeta s, CtxTrad ctx, List<String> claves ) {
    List<StepMeta> ant = ctx.anteriores( s );
    if ( ant.size() != 1 || !( ant.get( 0 ).getStepMetaInterface() instanceof SortRowsMeta ) || claves.isEmpty() ) {
      return false;
    }
    SortRowsMeta o = (SortRowsMeta) ant.get( 0 ).getStepMetaInterface();
    String[] f = o.getFieldName();
    if ( f == null || f.length < claves.size() ) {
      return false;
    }
    List<String> pre = new ArrayList<>();
    for ( int i = 0; i < claves.size(); i++ ) {
      pre.add( f[i] );
    }
    return pre.containsAll( claves );
  }

  private static boolean acumulado( int t ) {
    return t == GroupByMeta.TYPE_GROUP_CUMULATIVE_SUM || t == GroupByMeta.TYPE_GROUP_CUMULATIVE_AVERAGE;
  }

  private static boolean cuenta( int t ) {
    return t == GroupByMeta.TYPE_GROUP_COUNT_ALL || t == GroupByMeta.TYPE_GROUP_COUNT_ANY
      || t == GroupByMeta.TYPE_GROUP_COUNT_DISTINCT;
  }

  static Bloque agregar( StepMeta s, CtxTrad ctx, List<String> claves, String[] nombres, String[] sujetos,
    int[] tipos, String[] valores, boolean todas, String lineaNr, boolean siempreUna, boolean porRachas ) {
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean ordenada = !porRachas || ordenadaPor( s, ctx, claves );
    if ( porRachas ) {
      b.l( "# " + ctx.t( "Group by de PDI agrupa filas SEGUIDAS con la misma clave (espera la entrada ordenada).",
        "PDI's Group by groups CONSECUTIVE rows with the same key (it expects sorted input).",
        "O Group by do PDI agrupa linhas SEGUIDAS com a mesma chave (espera a entrada ordenada)." ) );
    }
    int n = nombres == null ? 0 : nombres.length;
    if ( ctx.pandas() ) {
      String grupos;
      String racha = null;
      if ( claves.isEmpty() ) {
        grupos = "np.zeros(len(" + in + "), dtype=int)";
      } else if ( ordenada ) {
        grupos = lista( claves );
      } else {
        ayudaCambia( ctx );
        racha = ctx.nuevo( "_racha" );
        b.l( racha + " = pdi_cambia(" + in + "[" + lista( claves ) + "]).cumsum()" );
        grupos = "[" + racha + ", " + args( claves ) + "]";
      }
      String g = in + ".groupby(" + grupos + ", sort=False" + ( claves.isEmpty() ? "" : ", dropna=False" ) + ")";
      if ( todas ) {
        String gv = ctx.nuevo( "_g" );
        b.l( gv + " = " + g );
        b.l( out + " = " + in + ".assign(**{" );
        for ( int i = 0; i < n; i++ ) {
          b.l( "    " + CtxTrad.lit( nombres[i] ) + ": " + aggPandasTodas( s, ctx, b, gv, sujetos[i], tipos[i],
            valores == null ? null : valores[i] ) + "," );
        }
        if ( lineaNr != null && !lineaNr.isEmpty() ) {
          b.l( "    " + CtxTrad.lit( lineaNr ) + ": " + gv + ".cumcount() + 1," );
        }
        b.l( "})" );
      } else {
        b.l( out + " = " + g + ".agg(**{" );
        for ( int i = 0; i < n; i++ ) {
          b.l( "    " + CtxTrad.lit( nombres[i] ) + ": (" + CtxTrad.lit( sujetos[i] ) + ", " + aggPandas( s, ctx, b,
            sujetos[i], tipos[i], valores == null ? null : valores[i] ) + ")," );
        }
        b.l( "})" + ( claves.isEmpty() ? ".reset_index(drop=True)" : racha == null ? ".reset_index()"
          : ".reset_index(level=" + lista( claves ) + ").reset_index(drop=True)" ) );
        if ( claves.isEmpty() && siempreUna ) {
          StringBuilder fila = new StringBuilder();
          for ( int i = 0; i < n; i++ ) {
            fila.append( i == 0 ? "" : ", " ).append( CtxTrad.lit( nombres[i] ) ).append( ": " ).append( cuenta(
              tipos[i] ) ? "0" : "None" );
          }
          b.l( "if " + out + ".empty:  # " + ctx.t( "sin filas: PDI devuelve igual una fila", "no rows: PDI still "
            + "returns one row", "sem linhas: o PDI devolve uma linha mesmo assim" ) );
          b.l( "    " + out + " = pd.DataFrame([{" + fila + "}])" );
        }
      }
    } else {
      ayudaCambia( ctx );
      String d = ctx.nuevo( "_d" );
      b.l( d + " = pdi_rachas(" + in + ", " + lista( claves ) + ")" );
      List<String> aggs = new ArrayList<>();
      List<String> acum = new ArrayList<>();
      for ( int i = 0; i < n; i++ ) {
        if ( todas && acumulado( tipos[i] ) ) {
          String w = "Window.partitionBy(\"_racha\").orderBy(\"_orden\")";
          String c = "F.col(" + CtxTrad.lit( sujetos[i] ) + ")";
          acum.add( ".withColumn(" + CtxTrad.lit( nombres[i] ) + ", " + ( tipos[i]
            == GroupByMeta.TYPE_GROUP_CUMULATIVE_SUM ? "F.sum(" + c + ").over(" + w + ")" : "F.avg(" + c + ").over("
              + w + ")" ) + ")" );
        } else {
          aggs.add( aggSpark( s, ctx, b, sujetos[i], tipos[i], valores == null ? null : valores[i] ) + ".alias("
            + CtxTrad.lit( nombres[i] ) + ")" );
        }
      }
      if ( todas ) {
        String a = ctx.nuevo( "_agg" );
        b.l( a + " = " + d + ".groupBy(\"_racha\").agg(" );
        for ( String x : aggs ) {
          b.l( "    " + x + "," );
        }
        b.l( ")" );
        StringBuilder sel = new StringBuilder( "*" + in + ".columns" );
        for ( int i = 0; i < n; i++ ) {
          sel.append( ", " ).append( CtxTrad.lit( nombres[i] ) );
        }
        String linea = "";
        if ( lineaNr != null && !lineaNr.isEmpty() ) {
          linea = ".withColumn(" + CtxTrad.lit( lineaNr ) + ", F.row_number().over(Window.partitionBy(\"_racha\")"
            + ".orderBy(\"_orden\")))";
          sel.append( ", " ).append( CtxTrad.lit( lineaNr ) );
        }
        b.l( out + " = " + d + ".join(" + a + ", \"_racha\", \"left\")" + String.join( "", acum ) + linea );
        b.l( out + " = " + out + ".orderBy(\"_orden\").select(" + sel + ")" );
      } else {
        String gb = claves.isEmpty() && siempreUna ? d : d + ".groupBy(\"_racha\"" + ( claves.isEmpty() ? "" : ", "
          + args( claves ) ) + ")";
        b.l( out + " = " + gb + ".agg(" );
        for ( String x : aggs ) {
          b.l( "    " + x + "," );
        }
        b.l( ")" + ( claves.isEmpty() && siempreUna ? "" : ".orderBy(\"_racha\").drop(\"_racha\")" ) );
      }
    }
    b.vista = out;
    return b;
  }

  /** Función de agregación de pandas (nombre o lambda) para groupby().agg. */
  private static String aggPandas( StepMeta s, CtxTrad ctx, Bloque b, String sujeto, int t, String valor ) {
    switch ( t ) {
      case GroupByMeta.TYPE_GROUP_SUM:
        return "lambda s: s.sum(min_count=1)";
      case GroupByMeta.TYPE_GROUP_CUMULATIVE_SUM:
      case GroupByMeta.TYPE_GROUP_CUMULATIVE_AVERAGE:
        return "lambda s: None"; // sin «todas las filas», PDI deja el acumulado vacío
      case GroupByMeta.TYPE_GROUP_AVERAGE:
        return mediaEntera( s, ctx, sujeto ) ? "lambda s: np.trunc(s.sum() / s.count()) if s.count() else None"
          : "\"mean\"";
      case GroupByMeta.TYPE_GROUP_MEDIAN:
        ayudaPercentil( ctx );
        return "lambda s: pdi_percentil(s, 50)";
      case GroupByMeta.TYPE_GROUP_PERCENTILE:
        ayudaPercentil( ctx );
        return "lambda s: pdi_percentil(s, " + numero( ctx, valor, "50" ) + ")";
      case GroupByMeta.TYPE_GROUP_PERCENTILE_NEAREST_RANK:
        ayudaRango( ctx );
        return "lambda s: pdi_percentil_rango(s, " + numero( ctx, valor, "50" ) + ")";
      case GroupByMeta.TYPE_GROUP_MIN:
        return tipo( s, ctx, sujeto ) == ValueMetaInterface.TYPE_STRING ? "lambda s: s.dropna().min()" : "\"min\"";
      case GroupByMeta.TYPE_GROUP_MAX:
        return tipo( s, ctx, sujeto ) == ValueMetaInterface.TYPE_STRING ? "lambda s: s.dropna().max()" : "\"max\"";
      case GroupByMeta.TYPE_GROUP_COUNT_ALL:
        return "\"count\""; // «Number of values (N)»: los no nulos
      case GroupByMeta.TYPE_GROUP_COUNT_ANY:
        return "\"size\""; // «Number of rows»: todas las filas
      case GroupByMeta.TYPE_GROUP_COUNT_DISTINCT:
        return "\"nunique\"";
      case GroupByMeta.TYPE_GROUP_CONCAT_COMMA:
        ayudaConcat( ctx );
        return "lambda s: pdi_concatenar(s, \", \")";
      case GroupByMeta.TYPE_GROUP_CONCAT_STRING:
        ayudaConcat( ctx );
        return "lambda s: pdi_concatenar(s, " + ctx.texto( valor == null ? "" : valor ) + ")";
      case GroupByMeta.TYPE_GROUP_FIRST:
        return "\"first\"";
      case GroupByMeta.TYPE_GROUP_LAST:
        return "\"last\"";
      case GroupByMeta.TYPE_GROUP_FIRST_INCL_NULL:
        return "lambda s: s.iloc[0]";
      case GroupByMeta.TYPE_GROUP_LAST_INCL_NULL:
        return "lambda s: s.iloc[-1]";
      case GroupByMeta.TYPE_GROUP_STANDARD_DEVIATION:
        return "lambda s: s.std(ddof=0)";
      case GroupByMeta.TYPE_GROUP_STANDARD_DEVIATION_SAMPLE:
        return "\"std\"";
      default:
        b.manual( ctx.t( "Agregación sin traducir: ", "Untranslated aggregation: ", "Agregação sem tradução: " ) + t );
        return "\"first\"";
    }
  }

  /** La agregación de un campo con «incluir todas las filas»: el valor del grupo repetido (o acumulado) en cada fila. */
  private static String aggPandasTodas( StepMeta s, CtxTrad ctx, Bloque b, String g, String sujeto, int t,
    String valor ) {
    String c = g + "[" + CtxTrad.lit( sujeto ) + "]";
    if ( t == GroupByMeta.TYPE_GROUP_CUMULATIVE_SUM ) {
      return c + ".transform(lambda s: s.cumsum().ffill())";
    }
    if ( t == GroupByMeta.TYPE_GROUP_CUMULATIVE_AVERAGE ) {
      return c + ".transform(lambda s: (s.cumsum() / s.notna().cumsum()).ffill())";
    }
    return c + ".transform(" + aggPandas( s, ctx, b, sujeto, t, valor ) + ")";
  }

  /** PDI divide la suma entre la cuenta con el tipo del campo: la media de un Integer es entera (truncada). */
  private static boolean mediaEntera( StepMeta s, CtxTrad ctx, String sujeto ) {
    return MEDIA_ENTERA && tipo( s, ctx, sujeto ) == ValueMetaInterface.TYPE_INTEGER;
  }

  static boolean MEDIA_ENTERA = true;

  /** La agregación de Spark (una Column). Usa _orden y _racha de pdi_rachas. */
  private static String aggSpark( StepMeta s, CtxTrad ctx, Bloque b, String sujeto, int t, String valor ) {
    String c = "F.col(" + CtxTrad.lit( sujeto ) + ")";
    switch ( t ) {
      case GroupByMeta.TYPE_GROUP_SUM:
        return "F.sum(" + c + ")";
      case GroupByMeta.TYPE_GROUP_CUMULATIVE_SUM:
      case GroupByMeta.TYPE_GROUP_CUMULATIVE_AVERAGE:
        return "F.lit(None).cast(\"double\")"; // sin «todas las filas», PDI deja el acumulado vacío
      case GroupByMeta.TYPE_GROUP_AVERAGE:
        return mediaEntera( s, ctx, sujeto ) ? "(F.sum(" + c + ") / F.count(" + c + ")).cast(\"long\")" : "F.avg("
          + c + ")";
      case GroupByMeta.TYPE_GROUP_MEDIAN:
        ayudaPercentil( ctx );
        return "pdi_percentil(" + c + ", 50)";
      case GroupByMeta.TYPE_GROUP_PERCENTILE:
        ayudaPercentil( ctx );
        return "pdi_percentil(" + c + ", " + numero( ctx, valor, "50" ) + ")";
      case GroupByMeta.TYPE_GROUP_PERCENTILE_NEAREST_RANK:
        ayudaRango( ctx );
        return "pdi_percentil_rango(" + c + ", " + numero( ctx, valor, "50" ) + ")";
      case GroupByMeta.TYPE_GROUP_MIN:
        return "F.min(" + c + ")";
      case GroupByMeta.TYPE_GROUP_MAX:
        return "F.max(" + c + ")";
      case GroupByMeta.TYPE_GROUP_COUNT_ALL:
        return "F.count(" + c + ")";
      case GroupByMeta.TYPE_GROUP_COUNT_ANY:
        return "F.count(F.lit(1))";
      case GroupByMeta.TYPE_GROUP_COUNT_DISTINCT:
        return "F.count_distinct(" + c + ")";
      case GroupByMeta.TYPE_GROUP_CONCAT_COMMA:
        ayudaConcat( ctx );
        return "pdi_concatenar(" + c + ", \", \")";
      case GroupByMeta.TYPE_GROUP_CONCAT_STRING:
        ayudaConcat( ctx );
        return "pdi_concatenar(" + c + ", " + ctx.texto( valor == null ? "" : valor ) + ")";
      case GroupByMeta.TYPE_GROUP_FIRST:
        return "F.min_by(" + c + ", F.when(" + c + ".isNotNull(), F.col(\"_orden\")))";
      case GroupByMeta.TYPE_GROUP_LAST:
        return "F.max_by(" + c + ", F.when(" + c + ".isNotNull(), F.col(\"_orden\")))";
      case GroupByMeta.TYPE_GROUP_FIRST_INCL_NULL:
        return "F.min_by(" + c + ", F.col(\"_orden\"))";
      case GroupByMeta.TYPE_GROUP_LAST_INCL_NULL:
        return "F.max_by(" + c + ", F.col(\"_orden\"))";
      case GroupByMeta.TYPE_GROUP_STANDARD_DEVIATION:
        return "F.stddev_pop(" + c + ")";
      case GroupByMeta.TYPE_GROUP_STANDARD_DEVIATION_SAMPLE:
        return "F.stddev_samp(" + c + ")";
      default:
        b.manual( ctx.t( "Agregación sin traducir: ", "Untranslated aggregation: ", "Agregação sem tradução: " ) + t );
        return "F.first(" + c + ")";
    }
  }

  // ================================================================ Analytic query

  /** Analytic query: LAG / LEAD de N filas dentro de cada grupo (rachas seguidas, como Group by). */
  static Bloque analitica( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    List<String> claves = new ArrayList<>();
    for ( Element f : hijos( hijoE( x, "group" ), "field" ) ) {
      claves.add( CtxTrad.hijo( f, "name" ) );
    }
    List<String[]> campos = new ArrayList<>();
    for ( Element f : hijos( hijoE( x, "fields" ), "field" ) ) {
      campos.add( new String[] { CtxTrad.hijo( f, "aggregate" ), CtxTrad.hijo( f, "subject" ), CtxTrad.hijo( f,
        "type" ), CtxTrad.hijo( f, "valuefield" ) } );
    }
    b.l( "# " + ctx.t( "Los grupos son rachas de filas seguidas con la misma clave (entrada ordenada, como PDI).",
      "Groups are runs of consecutive rows with the same key (sorted input, as in PDI).",
      "Os grupos são sequências de linhas seguidas com a mesma chave (entrada ordenada, como no PDI)." ) );
    ayudaCambia( ctx );
    if ( ctx.pandas() ) {
      String g = ctx.nuevo( "_g" );
      b.l( g + " = " + in + ".groupby(" + ( claves.isEmpty() ? "np.zeros(len(" + in + "), dtype=int)" : "pdi_cambia(" + in + "["
        + lista( claves ) + "]).cumsum()" ) + ", sort=False)" );
      b.l( out + " = " + in + ".assign(**{" );
      for ( String[] c : campos ) {
        int k = vacio( c[3] ) ? 1 : Integer.parseInt( c[3].trim() );
        boolean lead = c[2].toUpperCase( Locale.ROOT ).contains( "LEAD" );
        b.l( "    " + CtxTrad.lit( c[0] ) + ": " + g + "[" + CtxTrad.lit( c[1] ) + "].shift(" + ( lead ? -k : k )
          + ")," );
      }
      b.l( "})" );
    } else {
      String d = ctx.nuevo( "_d" );
      b.l( d + " = pdi_rachas(" + in + ", " + lista( claves ) + ")" );
      b.l( "_w = Window.partitionBy(\"_racha\").orderBy(\"_orden\")" );
      StringBuilder w = new StringBuilder( d );
      for ( String[] c : campos ) {
        int k = vacio( c[3] ) ? 1 : Integer.parseInt( c[3].trim() );
        boolean lead = c[2].toUpperCase( Locale.ROOT ).contains( "LEAD" );
        w.append( "\n    .withColumn(" ).append( CtxTrad.lit( c[0] ) ).append( ", F." ).append( lead ? "lead"
          : "lag" ).append( "(" ).append( CtxTrad.lit( c[1] ) ).append( ", " ).append( k ).append( ").over(_w))" );
      }
      b.l( out + " = (" + w + "\n    .orderBy(\"_orden\").drop(\"_orden\", \"_racha\"))" );
    }
    b.vista = out;
    return b;
  }

  // ================================================================ Row denormaliser / normaliser / flattener

  /** Row denormaliser: de filas (clave, valor) a columnas, por rachas de grupo seguidas. */
  static Bloque desnormalizar( StepMeta s, CtxTrad ctx ) {
    DenormaliserMeta m = (DenormaliserMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    List<String> claves = noVacios( m.getGroupField() );
    String clave = m.getKeyField();
    DenormaliserTargetField[] ts = m.getDenormaliserTargetField();
    List<String> fuera = new ArrayList<>();
    fuera.add( clave );
    for ( DenormaliserTargetField t : ts ) {
      if ( !fuera.contains( t.getFieldName() ) ) {
        fuera.add( t.getFieldName() );
      }
    }
    // los demás campos (ni grupo, ni clave, ni valor) siguen: PDI les pone el valor de la última fila del grupo
    List<String> resto = new ArrayList<>();
    RowMetaInterface ent = ctx.camposEntrada( s );
    for ( String f : ent.getFieldNames() ) {
      if ( !fuera.contains( f ) && !claves.contains( f ) ) {
        resto.add( f );
      }
    }
    b.l( "# " + ctx.t( "PDI junta filas SEGUIDAS con el mismo grupo (espera la entrada ordenada).",
      "PDI joins CONSECUTIVE rows with the same group (it expects sorted input).",
      "O PDI junta linhas SEGUIDAS com o mesmo grupo (espera a entrada ordenada)." ) );
    ayudaCambia( ctx );
    if ( ctx.pandas() ) {
      String k = ctx.nuevo( "_clave" );
      b.l( k + " = " + in + "[" + CtxTrad.lit( clave ) + "].astype(\"string\")" );
      String racha = ctx.nuevo( "_racha" );
      b.l( racha + " = pdi_cambia(" + in + "[" + lista( claves ) + "]).cumsum()" );
      b.l( out + " = " + in + ".assign(**{" );
      for ( DenormaliserTargetField t : ts ) {
        String v = in + "[" + CtxTrad.lit( t.getFieldName() ) + "].where(" + k + " == " + CtxTrad.lit( t
          .getKeyValue() ) + ")";
        String conv = antesDeAgregar( v, t.getTargetType() );
        b.l( "    " + CtxTrad.lit( t.getTargetName() ) + ": " + ( conv == null ? v : conv ) + "," );
      }
      b.l( "}).groupby([" + racha + ( claves.isEmpty() ? "" : ", " + args( claves ) ) + "], sort=False, dropna=False)"
        + ".agg(**{" );
      for ( String r : resto ) {
        b.l( "    " + CtxTrad.lit( r ) + ": (" + CtxTrad.lit( r ) + ", lambda s: s.iloc[-1])," );
      }
      for ( DenormaliserTargetField t : ts ) {
        b.l( "    " + CtxTrad.lit( t.getTargetName() ) + ": (" + CtxTrad.lit( t.getTargetName() ) + ", "
          + aggDesnormPandas( ctx, t.getTargetAggregationType() ) + ")," );
      }
      b.l( "})" + ( claves.isEmpty() ? ".reset_index(drop=True)" : ".reset_index(level=" + lista( claves )
        + ").reset_index(drop=True)" ) );
      for ( DenormaliserTargetField t : ts ) {
        if ( t.getTargetType() == ValueMetaInterface.TYPE_INTEGER && t.getTargetAggregationType()
          != DenormaliserTargetField.TYPE_AGGR_COUNT_ALL ) {
          b.l( out + "[" + CtxTrad.lit( t.getTargetName() ) + "] = " + out + "[" + CtxTrad.lit( t.getTargetName() )
            + "].astype(\"Int64\")" );
        }
      }
    } else {
      String d = ctx.nuevo( "_d" );
      b.l( d + " = pdi_rachas(" + in + ", " + lista( claves ) + ")" );
      b.l( out + " = " + d + ".groupBy(\"_racha\"" + ( claves.isEmpty() ? "" : ", " + args( claves ) ) + ").agg(" );
      for ( String r : resto ) {
        b.l( "    F.max_by(" + CtxTrad.lit( r ) + ", \"_orden\").alias(" + CtxTrad.lit( r ) + ")," );
      }
      for ( DenormaliserTargetField t : ts ) {
        String conv = sparkTipo( t.getTargetType() );
        String c = "F.when(F.col(" + CtxTrad.lit( clave ) + ").cast(\"string\") == " + CtxTrad.lit( t.getKeyValue() )
          + ", F.col(" + CtxTrad.lit( t.getFieldName() ) + ")" + ( conv == null ? "" : ".cast(\"" + conv + "\")" )
          + ")";
        String a = aggDesnormSpark( ctx, t.getTargetAggregationType(), c );
        if ( conv != null && t.getTargetAggregationType() != DenormaliserTargetField.TYPE_AGGR_COUNT_ALL ) {
          a = a + ".cast(\"" + conv + "\")";
        }
        b.l( "    " + a + ".alias(" + CtxTrad.lit( t.getTargetName() ) + ")," );
      }
      b.l( ").orderBy(\"_racha\").drop(\"_racha\")" );
    }
    b.vista = out;
    return b;
  }

  private static String aggDesnormPandas( CtxTrad ctx, int t ) {
    switch ( t ) {
      case DenormaliserTargetField.TYPE_AGGR_SUM:
        return "lambda s: s.sum(min_count=1)";
      case DenormaliserTargetField.TYPE_AGGR_AVERAGE:
        return "\"mean\"";
      case DenormaliserTargetField.TYPE_AGGR_MIN:
        return "\"min\"";
      case DenormaliserTargetField.TYPE_AGGR_MAX:
        return "\"max\"";
      case DenormaliserTargetField.TYPE_AGGR_COUNT_ALL:
        return "\"count\"";
      case DenormaliserTargetField.TYPE_AGGR_CONCAT_COMMA:
        ayudaConcat( ctx );
        return "lambda s: pdi_concatenar(s, \",\")";
      default:
        return "\"last\"";
    }
  }

  private static String aggDesnormSpark( CtxTrad ctx, int t, String c ) {
    switch ( t ) {
      case DenormaliserTargetField.TYPE_AGGR_SUM:
        return "F.sum(" + c + ")";
      case DenormaliserTargetField.TYPE_AGGR_AVERAGE:
        return "F.avg(" + c + ")";
      case DenormaliserTargetField.TYPE_AGGR_MIN:
        return "F.min(" + c + ")";
      case DenormaliserTargetField.TYPE_AGGR_MAX:
        return "F.max(" + c + ")";
      case DenormaliserTargetField.TYPE_AGGR_COUNT_ALL:
        return "F.count(" + c + ")";
      case DenormaliserTargetField.TYPE_AGGR_CONCAT_COMMA:
        ayudaConcat( ctx );
        return "pdi_concatenar(" + c + ", \",\")";
      default:
        return "F.max_by(" + c + ", F.when(" + c + ".isNotNull(), F.col(\"_orden\")))";
    }
  }

  /** Conversión pandas de los valores al tipo destino de Kettle antes de agregar (null = sin conversión). */
  private static String antesDeAgregar( String col, int tipo ) {
    switch ( tipo ) {
      case ValueMetaInterface.TYPE_INTEGER:
      case ValueMetaInterface.TYPE_NUMBER:
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return "pd.to_numeric(" + col + ")";
      default:
        return null;
    }
  }

  private static String sparkTipo( int tipo ) {
    switch ( tipo ) {
      case ValueMetaInterface.TYPE_INTEGER:
        return "long";
      case ValueMetaInterface.TYPE_NUMBER:
        return "double";
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return "decimal(38,10)";
      case ValueMetaInterface.TYPE_STRING:
        return "string";
      case ValueMetaInterface.TYPE_BOOLEAN:
        return "boolean";
      default:
        return null;
    }
  }

  /** Row normaliser: de columnas a filas (una fila por cada tipo, en el orden de la configuración). */
  static Bloque normalizar( StepMeta s, CtxTrad ctx ) {
    NormaliserMeta m = (NormaliserMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    List<String> tiposV = new ArrayList<>();
    List<String> normas = new ArrayList<>();
    List<String> origen = new ArrayList<>();
    for ( NormaliserMeta.NormaliserField f : m.getNormaliserFields() ) {
      if ( !tiposV.contains( f.getValue() ) ) {
        tiposV.add( f.getValue() );
      }
      if ( !normas.contains( f.getNorm() ) ) {
        normas.add( f.getNorm() );
      }
      origen.add( f.getName() );
    }
    String tf = m.getTypeField();
    if ( ctx.pandas() ) {
      String r = ctx.nuevo( "_resto" );
      b.l( r + " = " + in + ".drop(columns=" + lista( origen ) + ")" );
      b.l( out + " = pd.concat([" );
      for ( String tv : tiposV ) {
        StringBuilder a = new StringBuilder( CtxTrad.lit( tf ) + ": " + CtxTrad.lit( tv ) );
        for ( String nf : normas ) {
          String src = null;
          for ( NormaliserMeta.NormaliserField f : m.getNormaliserFields() ) {
            if ( f.getValue().equals( tv ) && f.getNorm().equals( nf ) ) {
              src = f.getName();
            }
          }
          a.append( ", " ).append( CtxTrad.lit( nf ) ).append( ": " ).append( src == null ? "None" : in + "["
            + CtxTrad.lit( src ) + "]" );
        }
        b.l( "    " + r + ".assign(**{" + a + "})," );
      }
      b.l( "]).sort_index(kind=\"stable\").reset_index(drop=True)  # " + ctx.t( "por cada fila, un tipo tras otro",
        "for each row, one type after another", "para cada linha, um tipo após o outro" ) );
    } else {
      StringBuilder arr = new StringBuilder();
      for ( String tv : tiposV ) {
        StringBuilder st = new StringBuilder( "F.struct(F.lit(" + CtxTrad.lit( tv ) + ").alias(" + CtxTrad.lit( tf )
          + ")" );
        for ( String nf : normas ) {
          String src = null;
          for ( NormaliserMeta.NormaliserField f : m.getNormaliserFields() ) {
            if ( f.getValue().equals( tv ) && f.getNorm().equals( nf ) ) {
              src = f.getName();
            }
          }
          st.append( ", " ).append( src == null ? "F.lit(None)" : "F.col(" + CtxTrad.lit( src ) + ")" ).append(
            ".alias(" ).append( CtxTrad.lit( nf ) ).append( ")" );
        }
        arr.append( "\n    " ).append( st ).append( ")," );
      }
      b.l( out + " = " + in + ".withColumn(\"_n\", F.explode(F.array(" + arr + "\n)))" );
      b.l( out + " = " + out + ".select(*[c for c in " + in + ".columns if c not in " + lista( origen )
        + "], \"_n.*\")" );
    }
    b.vista = out;
    return b;
  }

  /** Row flattener: cada N filas seguidas pasan a ser una (el campo se reparte en N columnas). */
  static Bloque aplanar( StepMeta s, CtxTrad ctx ) {
    FlattenerMeta m = (FlattenerMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String f = m.getFieldName();
    String[] t = m.getTargetField();
    int n = t.length;
    if ( ctx.pandas() ) {
      String v = ctx.nuevo( "_v" );
      b.l( v + " = " + in + "[" + CtxTrad.lit( f ) + "].reset_index(drop=True)" );
      b.l( "# " + ctx.t( "los demás campos salen de la última fila de cada bloque, como en PDI", "the other fields come "
        + "from the last row of each block, as in PDI", "os outros campos vêm da última linha de cada bloco, como no "
          + "PDI" ) );
      b.l( out + " = " + in + ".drop(columns=" + CtxTrad.lit( f ) + ").groupby(np.arange(len(" + in + ")) // " + n
        + ").tail(1).reset_index(drop=True)" );
      b.l( out + " = " + out + ".assign(**{" );
      for ( int i = 0; i < n; i++ ) {
        b.l( "    " + CtxTrad.lit( t[i] ) + ": " + v + ".iloc[" + i + "::" + n + "].reset_index(drop=True)," );
      }
      b.l( "})" );
    } else {
      String d = ctx.nuevo( "_d" );
      b.l( d + " = " + in + ".withColumn(\"_i\", F.row_number().over(Window.orderBy(F.monotonically_increasing_id()))"
        + " - 1)" );
      b.l( d + " = " + d + ".withColumn(\"_bloque\", F.floor(F.col(\"_i\") / " + n + ")).withColumn(\"_pos\", "
        + "F.col(\"_i\") % " + n + ")" );
      b.l( out + " = " + d + ".groupBy(\"_bloque\").agg(" );
      b.l( "    *[F.max_by(c, \"_i\").alias(c) for c in " + in + ".columns if c != " + CtxTrad.lit( f ) + "]," );
      for ( int i = 0; i < n; i++ ) {
        b.l( "    F.max(F.when(F.col(\"_pos\") == " + i + ", F.col(" + CtxTrad.lit( f ) + "))).alias(" + CtxTrad.lit(
          t[i] ) + ")," );
      }
      b.l( ").orderBy(\"_bloque\").drop(\"_bloque\")" );
    }
    b.vista = out;
    return b;
  }

  // ================================================================ búsquedas

  /** Stream lookup: left join contra el flujo de búsqueda, con valores por defecto si no encuentra. */
  static Bloque buscarFlujo( StepMeta s, CtxTrad ctx ) {
    StreamLookupMeta m = (StreamLookupMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String lk = info( s, ctx, b, infoNombre( s, 0 ) );
    String[] ks = m.getKeystream();
    String[] kl = m.getKeylookup();
    String[] vals = m.getValue();
    String[] nuevos = m.getValueName();
    String[] defs = m.getValueDefault();
    int[] tdef = m.getValueDefaultType();
    List<String> izq = Arrays.asList( ks );
    b.l( "# " + ctx.t( "Si la clave se repite en el flujo de búsqueda, PDI se queda con la última fila.",
      "If the key repeats in the lookup stream, PDI keeps the last row.",
      "Se a chave se repete no fluxo de busca, o PDI fica com a última linha." ) );
    unirBusqueda( ctx, b, in, out, lk, izq, Arrays.asList( kl ), vals, nuevos, defs, tdef, "last", false );
    b.vista = out;
    return b;
  }

  /**
   * Busca valores en una tabla (o flujo) {@code lk} por claves iguales: left join (inner si {@code comer}) con las
   * columnas renombradas, una sola fila por clave (la {@code queda}: "first" o "last") y defaults si no encuentra.
   */
  private static void unirBusqueda( CtxTrad ctx, Bloque b, String in, String out, String lk, List<String> izq,
    List<String> der, String[] vals, String[] nuevos, String[] defs, int[] tdef, String queda, boolean comer ) {
    List<String> kk = new ArrayList<>();
    for ( int i = 0; i < der.size(); i++ ) {
      kk.add( "_k" + i );
    }
    String t = ctx.nuevo( "_busca" );
    boolean hayDef = false;
    for ( int i = 0; defs != null && i < defs.length && i < vals.length; i++ ) {
      hayDef |= !vacio( defs[i] );
    }
    if ( ctx.pandas() ) {
      StringBuilder ren = new StringBuilder();
      StringBuilder cols = new StringBuilder();
      for ( int i = 0; i < der.size(); i++ ) {
        ren.append( ren.length() == 0 ? "" : ", " ).append( CtxTrad.lit( der.get( i ) ) ).append( ": " ).append(
          CtxTrad.lit( kk.get( i ) ) );
        cols.append( cols.length() == 0 ? "" : ", " ).append( CtxTrad.lit( der.get( i ) ) );
      }
      for ( int i = 0; i < vals.length; i++ ) {
        String nn = nuevos != null && i < nuevos.length && !vacio( nuevos[i] ) ? nuevos[i] : vals[i];
        cols.append( ", " ).append( CtxTrad.lit( vals[i] ) );
        if ( !nn.equals( vals[i] ) || der.contains( vals[i] ) ) {
          ren.append( ", " ).append( CtxTrad.lit( vals[i] ) ).append( ": " ).append( CtxTrad.lit( nn ) );
        }
      }
      // una clave puede ser también un valor devuelto: se copia antes de renombrar
      List<String> dup = new ArrayList<>();
      for ( int i = 0; i < vals.length; i++ ) {
        if ( der.contains( vals[i] ) ) {
          dup.add( vals[i] );
        }
      }
      if ( dup.isEmpty() ) {
        b.l( t + " = " + lk + "[[" + cols + "]].rename(columns={" + ren + "})" );
      } else {
        StringBuilder r2 = new StringBuilder();
        StringBuilder c2 = new StringBuilder();
        for ( int i = 0; i < der.size(); i++ ) {
          c2.append( c2.length() == 0 ? "" : ", " ).append( CtxTrad.lit( kk.get( i ) ) ).append( ": " ).append( lk )
            .append( "[" ).append( CtxTrad.lit( der.get( i ) ) ).append( "]" );
        }
        for ( int i = 0; i < vals.length; i++ ) {
          String nn = nuevos != null && i < nuevos.length && !vacio( nuevos[i] ) ? nuevos[i] : vals[i];
          r2.append( r2.length() == 0 ? "" : ", " ).append( CtxTrad.lit( nn ) ).append( ": " ).append( lk ).append(
            "[" ).append( CtxTrad.lit( vals[i] ) ).append( "]" );
        }
        b.l( t + " = pd.DataFrame({" + c2 + ", " + r2 + "})" );
      }
      b.l( t + " = " + t + ".drop_duplicates(subset=" + lista( kk ) + ", keep=" + CtxTrad.lit( queda ) + ")"
        + ( hayDef && !comer ? ".assign(_hay=True)" : "" ) );
      b.l( out + " = " + in + ".merge(" + t + ", how=" + ( comer ? "\"inner\"" : "\"left\"" ) + ", left_on="
        + lista( izq ) + ", right_on=" + lista( kk ) + ").drop(columns=" + lista( kk ) + ")" );
      if ( hayDef && !comer ) {
        b.l( out + " = " + out + ".assign(**{" );
        for ( int i = 0; i < vals.length; i++ ) {
          if ( defs != null && i < defs.length && !vacio( defs[i] ) ) {
            String nn = nuevos != null && i < nuevos.length && !vacio( nuevos[i] ) ? nuevos[i] : vals[i];
            b.l( "    " + CtxTrad.lit( nn ) + ": " + out + "[" + CtxTrad.lit( nn ) + "].where(" + out
              + "[\"_hay\"].notna(), " + valor( defs[i], tdef != null && i < tdef.length ? tdef[i] : 2 ) + ")," );
          }
        }
        b.l( "}).drop(columns=\"_hay\")" );
      }
    } else {
      StringBuilder sel = new StringBuilder();
      for ( int i = 0; i < der.size(); i++ ) {
        sel.append( sel.length() == 0 ? "" : ", " ).append( "F.col(" ).append( CtxTrad.lit( der.get( i ) ) ).append(
          ").alias(" ).append( CtxTrad.lit( kk.get( i ) ) ).append( ")" );
      }
      for ( int i = 0; i < vals.length; i++ ) {
        String nn = nuevos != null && i < nuevos.length && !vacio( nuevos[i] ) ? nuevos[i] : vals[i];
        sel.append( ", F.col(" ).append( CtxTrad.lit( vals[i] ) ).append( ").alias(" ).append( CtxTrad.lit( nn ) )
          .append( ")" );
      }
      b.l( t + " = " + lk + ".withColumn(\"_orden\", F.monotonically_increasing_id())" );
      b.l( t + " = " + t + ".withColumn(\"_ultima\", F." + ( "last".equals( queda ) ? "max" : "min" )
        + "(\"_orden\").over(Window.partitionBy(" + args( der ) + ")))" );
      b.l( t + " = " + t + ".filter(F.col(\"_orden\") == F.col(\"_ultima\")).select(" + sel + ", F.lit(True).alias("
        + "\"_hay\"))" );
      StringBuilder cond = new StringBuilder();
      for ( int i = 0; i < izq.size(); i++ ) {
        cond.append( i == 0 ? "" : " & " ).append( "F.col(" ).append( CtxTrad.lit( izq.get( i ) ) ).append(
          ").eqNullSafe(F.col(" ).append( CtxTrad.lit( kk.get( i ) ) ).append( "))" );
      }
      b.l( out + " = " + in + ".join(F.broadcast(" + t + "), " + cond + ", " + ( comer ? "\"inner\"" : "\"left\"" )
        + ")" );
      for ( int i = 0; i < vals.length; i++ ) {
        if ( defs != null && i < defs.length && !vacio( defs[i] ) && !comer ) {
          String nn = nuevos != null && i < nuevos.length && !vacio( nuevos[i] ) ? nuevos[i] : vals[i];
          b.l( out + " = " + out + ".withColumn(" + CtxTrad.lit( nn ) + ", F.when(F.col(\"_hay\").isNull(), F.lit("
            + valor( defs[i], tdef != null && i < tdef.length ? tdef[i] : 2 ) + ")).otherwise(F.col(" + CtxTrad.lit(
              nn ) + ")))" );
        }
      }
      kk.add( "_hay" );
      b.l( out + " = " + out + ".drop(" + args( kk ) + ")" );
    }
  }

  /** Database lookup: lee la tabla una vez y la cruza con el flujo (o, con condiciones que no son =, filtra). */
  static Bloque buscarBase( StepMeta s, CtxTrad ctx ) {
    DatabaseLookupMeta m = (DatabaseLookupMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    b.lee = true;
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String con = ctx.conexion( m.getDatabaseMeta() );
    String tabla = ( vacio( m.getSchemaName() ) ? "" : m.getSchemaName() + "." ) + m.getTablename();
    String[] tk = m.getTableKeyField();
    String[] cond = m.getKeyCondition();
    String[] k1 = m.getStreamKeyField1();
    String[] k2 = m.getStreamKeyField2();
    String[] vals = m.getReturnValueField();
    boolean soloIgual = true;
    for ( String c : cond ) {
      soloIgual &= "=".equals( c.trim() );
    }
    String sql;
    if ( soloIgual ) {
      StringBuilder q = new StringBuilder( "SELECT " );
      List<String> sel = new ArrayList<>();
      for ( String k : tk ) {
        if ( !sel.contains( k ) ) {
          sel.add( k );
        }
      }
      for ( String v : vals ) {
        if ( !sel.contains( v ) ) {
          sel.add( v );
        }
      }
      q.append( String.join( ", ", sel ) ).append( " FROM " ).append( tabla );
      if ( !vacio( m.getOrderByClause() ) ) {
        q.append( " ORDER BY " ).append( m.getOrderByClause() );
      }
      sql = q.toString();
    } else {
      sql = "SELECT * FROM " + tabla + ( vacio( m.getOrderByClause() ) ? "" : " ORDER BY " + m.getOrderByClause() );
    }
    String t = ctx.nuevo( "_tabla" );
    if ( ctx.pandas() ) {
      b.l( t + " = pd.read_sql(text(" + ctx.texto( sql ) + "), " + con + ")" );
    } else {
      b.l( t + " = spark.read.format(\"jdbc\").options(**" + con + ").option(\"query\", " + ctx.texto( sql )
        + ").load()" );
    }
    if ( soloIgual ) {
      if ( m.isFailingOnMultipleResults() ) {
        if ( ctx.pandas() ) {
          b.l( "if " + t + ".duplicated(subset=" + lista( tk ) + ").any():" );
        } else {
          b.l( "if " + t + ".groupBy(" + args( Arrays.asList( tk ) ) + ").count().filter(\"count > 1\").limit(1)"
            + ".count():" );
        }
        b.l( "    raise ValueError(" + CtxTrad.lit( ctx.t( "Database lookup: más de un resultado para una clave",
          "Database lookup: more than one result for a key", "Database lookup: mais de um resultado para uma chave" ) )
          + ")" );
      }
      unirBusqueda( ctx, b, in, out, t, Arrays.asList( k1 ), Arrays.asList( tk ), vals, m.getReturnValueNewName(),
        m.getReturnValueDefault(), m.getReturnValueDefaultType(), "first", m.isEatingRowOnLookupFailure() );
      if ( ctx.spark() ) {
        b.nota( ctx.t( "En Spark la «primera» fila de la tabla según ORDER BY no está garantizada tras leer.",
          "In Spark the 'first' table row by ORDER BY is not guaranteed after reading.",
          "No Spark a «primeira» linha da tabela pelo ORDER BY não é garantida após a leitura." ) );
      }
    } else {
      condicionesBusqueda( ctx, b, in, out, t, tk, cond, k1, k2, vals, m.getReturnValueNewName(),
        m.getReturnValueDefault(), m.getReturnValueDefaultType(), m.isEatingRowOnLookupFailure() );
    }
    b.nota( ctx.t( "La tabla se lee una vez entera (como la caché «cargar todo» de PDI).", "The table is read once "
      + "in full (like PDI's 'load all data' cache).", "A tabela é lida uma vez inteira (como o cache «carregar tudo» "
        + "do PDI)." ) );
    b.vista = out;
    return b;
  }

  /** Database lookup con condiciones distintas de =: producto cruzado, filtro y primera coincidencia por fila. */
  private static void condicionesBusqueda( CtxTrad ctx, Bloque b, String in, String out, String t, String[] tk,
    String[] cond, String[] k1, String[] k2, String[] vals, String[] nuevos, String[] defs, int[] tdef,
    boolean comer ) {
    String x = ctx.nuevo( "_cruce" );
    boolean pd = ctx.pandas();
    List<String> filtros = new ArrayList<>();
    for ( int i = 0; i < tk.length; i++ ) {
      String c = cond[i].trim().toUpperCase( Locale.ROOT );
      String tcol = pd ? x + "[" + CtxTrad.lit( "_t_" + tk[i] ) + "]" : "F.col(" + CtxTrad.lit( "_t_" + tk[i] ) + ")";
      String s1 = pd ? x + "[" + CtxTrad.lit( k1[i] ) + "]" : "F.col(" + CtxTrad.lit( k1[i] ) + ")";
      String s2 = k2 == null || i >= k2.length || vacio( k2[i] ) ? null : pd ? x + "[" + CtxTrad.lit( k2[i] ) + "]"
        : "F.col(" + CtxTrad.lit( k2[i] ) + ")";
      switch ( c ) {
        case "=": filtros.add( "(" + tcol + " == " + s1 + ")" ); break;
        case "<>": filtros.add( "(" + tcol + " != " + s1 + ")" ); break;
        case "<": filtros.add( "(" + tcol + " < " + s1 + ")" ); break;
        case "<=": filtros.add( "(" + tcol + " <= " + s1 + ")" ); break;
        case ">": filtros.add( "(" + tcol + " > " + s1 + ")" ); break;
        case ">=": filtros.add( "(" + tcol + " >= " + s1 + ")" ); break;
        case "BETWEEN": filtros.add( pd ? "" + tcol + ".between(" + s1 + ", " + s2 + ")" : tcol + ".between(" + s1
          + ", " + s2 + ")" ); break;
        case "IS NULL": filtros.add( tcol + ( pd ? ".isna()" : ".isNull()" ) ); break;
        case "IS NOT NULL": filtros.add( tcol + ( pd ? ".notna()" : ".isNotNull()" ) ); break;
        case "LIKE":
          filtros.add( pd ? tcol + ".astype(str).str.match(" + s1 + ".astype(str).str.replace(\"%\", \".*\")"
            + ".str.replace(\"_\", \".\"))" : tcol + ".like(" + s1 + ")" );
          b.aproximado( "LIKE" );
          break;
        default: b.manual( c );
      }
    }
    if ( pd ) {
      b.l( x + " = " + in + ".reset_index(drop=True).rename_axis(\"_fila\").reset_index().merge(" + t
        + ".add_prefix(\"_t_\"), how=\"cross\")" );
      b.l( x + " = " + x + "[" + String.join( " & ", filtros ) + "].drop_duplicates(\"_fila\")" );
      StringBuilder ren = new StringBuilder();
      for ( int i = 0; i < vals.length; i++ ) {
        String nn = nuevos != null && i < nuevos.length && !vacio( nuevos[i] ) ? nuevos[i] : vals[i];
        ren.append( ren.length() == 0 ? "" : ", " ).append( CtxTrad.lit( "_t_" + vals[i] ) ).append( ": " ).append(
          CtxTrad.lit( nn ) );
      }
      List<String> cols = new ArrayList<>();
      cols.add( "_fila" );
      for ( String v : vals ) {
        cols.add( "_t_" + v );
      }
      b.l( out + " = " + in + ".reset_index(drop=True).merge(" + x + "[" + lista( cols ) + "].set_index(\"_fila\")"
        + ".rename(columns={" + ren + "}), how=" + ( comer ? "\"inner\"" : "\"left\"" ) + ", left_index=True, "
          + "right_index=True)" );
      if ( !comer ) {
        for ( int i = 0; i < vals.length; i++ ) {
          if ( defs != null && i < defs.length && !vacio( defs[i] ) ) {
            String nn = nuevos != null && i < nuevos.length && !vacio( nuevos[i] ) ? nuevos[i] : vals[i];
            b.l( out + " = " + out + ".fillna({" + CtxTrad.lit( nn ) + ": " + valor( defs[i], tdef[i] ) + "})" );
          }
        }
      }
      b.l( out + " = " + out + ".reset_index(drop=True)" );
    } else {
      b.l( x + " = " + in + ".withColumn(\"_fila\", F.monotonically_increasing_id()).crossJoin(" + t
        + ".select([F.col(c).alias(\"_t_\" + c) for c in " + t + ".columns]))" );
      b.l( x + " = " + x + ".filter(" + String.join( " & ", filtros ) + ")" );
      StringBuilder sel = new StringBuilder( "\"_fila\"" );
      for ( int i = 0; i < vals.length; i++ ) {
        String nn = nuevos != null && i < nuevos.length && !vacio( nuevos[i] ) ? nuevos[i] : vals[i];
        sel.append( ", F.min_by(" ).append( CtxTrad.lit( "_t_" + vals[i] ) ).append( ", F.lit(0)).alias(" ).append(
          CtxTrad.lit( nn ) ).append( ")" );
      }
      b.l( out + " = " + in + ".withColumn(\"_fila\", F.monotonically_increasing_id()).join(" + x + ".groupBy("
        + "\"_fila\").agg(" + sel.substring( 9 ) + "), \"_fila\", " + ( comer ? "\"inner\"" : "\"left\"" ) + ")"
        + ".drop(\"_fila\")" );
      b.aproximado( ctx.t( "En Spark la primera coincidencia por fila no sigue el orden de la tabla.", "In Spark the "
        + "first match per row does not follow the table order.", "No Spark a primeira correspondência por linha não "
          + "segue a ordem da tabela." ) );
    }
  }

  /** Database join: la consulta (con ? = campos del flujo) se ejecuta por cada fila. */
  static Bloque unirBase( StepMeta s, CtxTrad ctx ) {
    DatabaseJoinMeta m = (DatabaseJoinMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    b.lee = true;
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String con = ctx.conexion( m.getDatabaseMeta() );
    String sql = m.isVariableReplace() ? ctx.texto( m.getSql() ) : CtxTrad.lit( m.getSql() );
    String[] p = m.getParameterField();
    String doc = ctx.t( "Database join: ejecuta la consulta por cada fila (los ? toman los campos dados, en orden) y "
      + "añade sus columnas.", "Database join: runs the query for each row (the ? take the given fields, in order) "
        + "and adds its columns.", "Database join: executa a consulta para cada linha (os ? recebem os campos dados, "
          + "em ordem) e adiciona as suas colunas." );
    if ( ctx.pandas() ) {
      ctx.ayudante( "pdi_unir_bd", "def pdi_unir_bd(df, engine, sql, campos, externa=False, limite=0):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    partes = sql.split(\"?\")\n"
        + "    consulta = text(\"\".join(p + (f\":p{i}\" if i < len(partes) - 1 else \"\") for i, p in "
        + "enumerate(partes)))\n"
        + "    trozos = []\n"
        + "    with engine.connect() as con:\n"
        + "        for i, fila in enumerate(df[campos].itertuples(index=False)):\n"
        + "            valores = {f\"p{j}\": (None if pd.isna(v) else v.item() if hasattr(v, \"item\") else v)\n"
        + "                       for j, v in enumerate(fila)}\n"
        + "            r = pd.read_sql(consulta, con, params=valores)\n"
        + "            trozos.append((r.head(limite) if limite > 0 else r).assign(_fila=i))\n"
        + "    if not trozos:\n"
        + "        return df\n"
        + "    res = pd.concat(trozos, ignore_index=True)\n"
        + "    unido = df.reset_index(drop=True).merge(res, how=\"left\" if externa else \"inner\", left_index=True, "
        + "right_on=\"_fila\")\n"
        + "    return unido.drop(columns=\"_fila\").reset_index(drop=True)" );
      b.l( out + " = pdi_unir_bd(" + in + ", " + con + ", " + sql + ", " + lista( p ) + ", externa=" + ( m
        .isOuterJoin() ? "True" : "False" ) + ( m.getRowLimit() > 0 ? ", limite=" + m.getRowLimit() : "" ) + ")" );
    } else {
      ctx.ayudante( "pdi_unir_bd", "def pdi_unir_bd(df, jdbc, sql, campos, externa=False, limite=0):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    def literal(v):\n"
        + "        return \"NULL\" if v is None else str(v) if isinstance(v, (int, float)) else \"'\" + str(v)"
        + ".replace(\"'\", \"''\") + \"'\"\n"
        + "    trozos = []\n"
        + "    for fila in df.select(*campos).distinct().collect():\n"
        + "        partes = sql.split(\"?\")\n"
        + "        q = \"\".join(p + (literal(fila[i]) if i < len(partes) - 1 else \"\") for i, p in "
        + "enumerate(partes))\n"
        + "        r = spark.read.format(\"jdbc\").options(**jdbc).option(\"query\", q).load()\n"
        + "        r = r.limit(limite) if limite > 0 else r\n"
        + "        trozos.append(r.select(*[F.lit(fila[i]).alias(f\"_p{i}\") for i in range(len(campos))], \"*\"))\n"
        + "    if not trozos:\n"
        + "        return df\n"
        + "    res = trozos[0]\n"
        + "    for t in trozos[1:]:\n"
        + "        res = res.unionByName(t)\n"
        + "    cond = [F.col(c).eqNullSafe(F.col(f\"_p{i}\")) for i, c in enumerate(campos)]\n"
        + "    unido = df.join(res, cond, \"left\" if externa else \"inner\")\n"
        + "    return unido.drop(*[f\"_p{i}\" for i in range(len(campos))])" );
      b.l( out + " = pdi_unir_bd(" + in + ", " + con + ", " + sql + ", " + lista( p ) + ", externa=" + ( m
        .isOuterJoin() ? "True" : "False" ) + ( m.getRowLimit() > 0 ? ", limite=" + m.getRowLimit() : "" ) + ")" );
      b.aproximado( ctx.t( "En Spark los parámetros se escriben como literales en la consulta (una lectura JDBC por "
        + "combinación distinta).", "In Spark the parameters are written as literals in the query (one JDBC read per "
          + "distinct combination).", "No Spark os parâmetros são escritos como literais na consulta (uma leitura JDBC "
            + "por combinação distinta)." ) );
    }
    b.vista = out;
    return b;
  }

  // ================================================================ uniones de flujos

  /** Renombra las columnas de {@code der} que chocan con las de {@code izq} (sufijo _1, como PDI). */
  private static String sinChoque( CtxTrad ctx, Bloque b, String izq, String der, String sufijo ) {
    String v = ctx.nuevo( "_der" );
    if ( ctx.pandas() ) {
      b.l( v + " = " + der + ".rename(columns={c: c + " + CtxTrad.lit( sufijo ) + " for c in " + der + ".columns if c in "
        + izq + ".columns})" );
    } else {
      b.l( v + " = " + der + ".select([F.col(c).alias(c + " + CtxTrad.lit( sufijo ) + " if c in " + izq
        + ".columns else c) for c in " + der + ".columns])" );
    }
    return v;
  }

  /** Merge join: inner / left / right / full outer entre los dos flujos (ya ordenados por la clave). */
  static Bloque mergeJoin( StepMeta s, CtxTrad ctx ) {
    MergeJoinMeta m = (MergeJoinMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String out = ctx.df( s );
    String uno = info( s, ctx, b, infoNombre( s, 0 ) );
    String dos = info( s, ctx, b, infoNombre( s, 1 ) );
    String tipo = m.getJoinType() == null ? "INNER" : m.getJoinType().toUpperCase( Locale.ROOT );
    String how = tipo.startsWith( "LEFT" ) ? "left" : tipo.startsWith( "RIGHT" ) ? "right" : tipo.startsWith( "FULL" )
      ? "outer" : "inner";
    List<String> k1 = Arrays.asList( m.getKeyFields1() );
    List<String> k2 = new ArrayList<>( Arrays.asList( m.getKeyFields2() ) );
    String d = sinChoque( ctx, b, uno, dos, "_1" );
    if ( ctx.pandas() ) {
      String k2v = "[c + \"_1\" if c in " + uno + ".columns else c for c in " + lista( k2 ) + "]";
      b.l( out + " = " + uno + ".merge(" + d + ", how=" + CtxTrad.lit( how ) + ", left_on=" + lista( k1 )
        + ", right_on=" + k2v + ")" );
    } else {
      StringBuilder cond = new StringBuilder( "[" );
      for ( int i = 0; i < k1.size(); i++ ) {
        cond.append( i == 0 ? "" : ", " ).append( uno ).append( "[" ).append( CtxTrad.lit( k1.get( i ) ) ).append(
          "].eqNullSafe(" ).append( d ).append( "[" ).append( k2.get( i ) == null ? "None" : "(" + CtxTrad.lit( k2.get(
            i ) ) + " + \"_1\") if " + CtxTrad.lit( k2.get( i ) ) + " in " + uno + ".columns else " + CtxTrad.lit( k2
              .get( i ) ) ).append( "])" );
      }
      cond.append( "]" );
      b.l( out + " = " + uno + ".join(" + d + ", " + cond + ", " + CtxTrad.lit( how.equals( "outer" ) ? "full" : how )
        + ")" );
      b.nota( ctx.t( "Spark no conserva el orden de las filas.", "Spark does not keep row order.",
        "O Spark não mantém a ordem das linhas." ) );
    }
    b.nota( ctx.t( "PDI exige las dos entradas ordenadas por la clave; aquí no hace falta.", "PDI needs both inputs "
      + "sorted by the key; not needed here.", "O PDI exige as duas entradas ordenadas pela chave; aqui não é preciso." ) );
    b.vista = out;
    return b;
  }

  /** Merge rows (diff): compara el flujo de referencia con el nuevo y marca identical / changed / new / deleted. */
  static Bloque mergeRows( StepMeta s, CtxTrad ctx ) {
    MergeRowsMeta m = (MergeRowsMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String out = ctx.df( s );
    String ref = info( s, ctx, b, infoNombre( s, 0 ) );
    String cmp = info( s, ctx, b, infoNombre( s, 1 ) );
    String doc = ctx.t( "Merge rows (diff): marca cada clave como identical, changed, new o deleted (identical y "
      + "deleted traen la fila de referencia; changed y new, la nueva).", "Merge rows (diff): flags each key as "
        + "identical, changed, new or deleted (identical and deleted carry the reference row; changed and new, the "
        + "compare row).", "Merge rows (diff): marca cada chave como identical, changed, new ou deleted (identical e "
          + "deleted trazem a linha de referência; changed e new, a nova)." );
    if ( ctx.pandas() ) {
      ctx.ayudante( "pdi_comparar_filas", "def pdi_comparar_filas(ref, nuevo, claves, valores, bandera):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    m = ref.merge(nuevo, on=claves, how=\"outer\", suffixes=(\"_ref\", \"\"), indicator=True)\n"
        + "    cols = [c for c in nuevo.columns]\n"
        + "    igual = pd.Series(True, index=m.index)\n"
        + "    for v in valores:\n"
        + "        a, b = m[v + \"_ref\"], m[v]\n"
        + "        igual &= ((a == b) | (a.isna() & b.isna())).fillna(False).astype(bool)\n"
        + "    m[bandera] = np.select([m[\"_merge\"] == \"left_only\", m[\"_merge\"] == \"right_only\", igual],\n"
        + "                           [\"deleted\", \"new\", \"identical\"], \"changed\")\n"
        + "    desde_ref = m[bandera].isin([\"deleted\", \"identical\"])\n"
        + "    for c in cols:\n"
        + "        if c + \"_ref\" in m.columns:\n"
        + "            m[c] = m[c + \"_ref\"].where(desde_ref, m[c])\n"
        + "    return m[cols + [bandera]].reset_index(drop=True)" );
      b.l( out + " = pdi_comparar_filas(" + ref + ", " + cmp + ", " + lista( m.getKeyFields() ) + ", " + lista( m
        .getValueFields() ) + ", " + CtxTrad.lit( m.getFlagField() ) + ")" );
    } else {
      ctx.ayudante( "pdi_comparar_filas", "def pdi_comparar_filas(ref, nuevo, claves, valores, bandera):\n"
        + "    \"\"\"" + doc + "\"\"\"\n"
        + "    r = ref.select([F.col(c).alias(c + \"_ref\") for c in ref.columns]).withColumn(\"_r\", F.lit(True))\n"
        + "    n = nuevo.withColumn(\"_n\", F.lit(True))\n"
        + "    cond = [F.col(c).eqNullSafe(F.col(c + \"_ref\")) for c in claves]\n"
        + "    m = r.join(n, cond, \"full\")\n"
        + "    igual = F.lit(True)\n"
        + "    for v in valores:\n"
        + "        igual = igual & F.col(v).eqNullSafe(F.col(v + \"_ref\"))\n"
        + "    flag = (F.when(F.col(\"_n\").isNull(), \"deleted\").when(F.col(\"_r\").isNull(), \"new\")\n"
        + "            .when(igual, \"identical\").otherwise(\"changed\"))\n"
        + "    m = m.withColumn(bandera, flag)\n"
        + "    desde_ref = F.col(bandera).isin(\"deleted\", \"identical\")\n"
        + "    cols = [F.when(desde_ref, F.col(c + \"_ref\")).otherwise(F.col(c)).alias(c) if c in ref.columns\n"
        + "            else F.col(c) for c in nuevo.columns]\n"
        + "    return m.select(*cols, bandera).orderBy(*claves)" );
      b.l( out + " = pdi_comparar_filas(" + ref + ", " + cmp + ", " + lista( m.getKeyFields() ) + ", " + lista( m
        .getValueFields() ) + ", " + CtxTrad.lit( m.getFlagField() ) + ")" );
    }
    b.nota( ctx.t( "PDI exige las dos entradas ordenadas por la clave; el resultado sale ordenado por la clave.",
      "PDI needs both inputs sorted by the key; the result comes sorted by the key.",
      "O PDI exige as duas entradas ordenadas pela chave; o resultado sai ordenado pela chave." ) );
    b.vista = out;
    return b;
  }

  /** Multiway merge join: varios flujos unidos por sus claves (INNER o FULL OUTER). */
  static Bloque multiway( StepMeta s, CtxTrad ctx ) {
    MultiMergeJoinMeta m = (MultiMergeJoinMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String out = ctx.df( s );
    String[] pasos = m.getInputSteps();
    String[] claves = m.getKeyFields();
    boolean full = m.getJoinType() != null && m.getJoinType().toUpperCase( Locale.ROOT ).startsWith( "FULL" );
    String how = full ? ( ctx.pandas() ? "outer" : "full" ) : "inner";
    String acc = null;
    List<String> kAcc = null;
    for ( int i = 0; i < pasos.length; i++ ) {
      String v = info( s, ctx, b, pasos[i] );
      List<String> k = new ArrayList<>();
      for ( String x : ( claves != null && i < claves.length && claves[i] != null ? claves[i] : "" ).split( "," ) ) {
        if ( !x.trim().isEmpty() ) {
          k.add( x.trim() );
        }
      }
      if ( acc == null ) {
        acc = out;
        b.l( out + " = " + v );
        kAcc = k;
        continue;
      }
      String d = sinChoque( ctx, b, out, v, "_" + i );
      if ( ctx.pandas() ) {
        b.l( out + " = " + out + ".merge(" + d + ", how=" + CtxTrad.lit( how ) + ", left_on=" + lista( kAcc )
          + ", right_on=[c + " + CtxTrad.lit( "_" + i ) + " if c in " + out + ".columns else c for c in " + lista( k )
          + "])" );
      } else {
        StringBuilder cond = new StringBuilder( "[" );
        for ( int j = 0; j < kAcc.size() && j < k.size(); j++ ) {
          cond.append( j == 0 ? "" : ", " ).append( out ).append( "[" ).append( CtxTrad.lit( kAcc.get( j ) ) ).append(
            "].eqNullSafe(" ).append( d ).append( "[" ).append( d ).append( ".columns[" ).append( v ).append(
              ".columns.index(" ).append( CtxTrad.lit( k.get( j ) ) ).append( ")]])" );
        }
        cond.append( "]" );
        b.l( out + " = " + out + ".join(" + d + ", " + cond + ", " + CtxTrad.lit( how ) + ")" );
      }
    }
    if ( acc == null ) {
      b.l( out + " = " + sinFilas( ctx ) );
    }
    b.nota( ctx.t( "Los campos repetidos de cada flujo llevan el sufijo _<número del flujo>.", "Repeated fields of each "
      + "stream get the suffix _<stream number>.", "Os campos repetidos de cada fluxo levam o sufixo _<número do "
        + "fluxo>." ) );
    b.vista = out;
    return b;
  }

  /** Append streams: primero todas las filas de la cabeza, después las de la cola. */
  static Bloque anexar( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = bloque( s );
    String out = ctx.df( s );
    String cab = info( s, ctx, b, CtxTrad.hijo( x, "head_name" ) );
    String cola = info( s, ctx, b, CtxTrad.hijo( x, "tail_name" ) );
    if ( ctx.pandas() ) {
      b.l( out + " = pd.concat([" + cab + ", " + cola + "], ignore_index=True)" );
    } else {
      b.l( out + " = " + cab + ".unionByName(" + cola + ")" );
    }
    b.vista = out;
    return b;
  }

  /** Sorted merge: une varios flujos ya ordenados en uno ordenado. */
  static Bloque mezclaOrdenada( StepMeta s, CtxTrad ctx ) {
    SortedMergeMeta m = (SortedMergeMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String out = ctx.df( s );
    List<String> e = ctx.entradas( s );
    List<String> campos = Arrays.asList( m.getFieldName() );
    StringBuilder asc = new StringBuilder();
    StringBuilder cols = new StringBuilder();
    for ( int i = 0; i < campos.size(); i++ ) {
      boolean a = m.getAscending() == null || i >= m.getAscending().length || m.getAscending()[i];
      asc.append( i == 0 ? "" : ", " ).append( a ? "True" : "False" );
      cols.append( i == 0 ? "" : ", " ).append( "F.col(" ).append( CtxTrad.lit( campos.get( i ) ) ).append( a
        ? ").asc_nulls_first()" : ").desc_nulls_last()" );
    }
    if ( ctx.pandas() ) {
      b.l( out + " = pd.concat([" + String.join( ", ", e ) + "], ignore_index=True).sort_values(" + lista( campos )
        + ", ascending=[" + asc + "], kind=\"stable\").reset_index(drop=True)" );
    } else {
      StringBuilder u = new StringBuilder( e.isEmpty() ? sinFilas( ctx ) : e.get( 0 ) );
      for ( int i = 1; i < e.size(); i++ ) {
        u.append( ".unionByName(" ).append( e.get( i ) ).append( ")" );
      }
      b.l( out + " = " + u + ".orderBy(" + cols + ")" );
    }
    b.nota( ctx.t( "Entre filas con la misma clave, PDI alterna los flujos; aquí van en el orden de los hops.",
      "Between rows with the same key PDI alternates the streams; here they follow the hop order.",
      "Entre linhas com a mesma chave o PDI alterna os fluxos; aqui seguem a ordem dos hops." ) );
    b.vista = out;
    return b;
  }

  /** Prioritize streams: todas las filas del primer flujo, luego las del segundo... */
  static Bloque prioridad( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = bloque( s );
    String out = ctx.df( s );
    List<String> vs = new ArrayList<>();
    for ( Element st : hijos( hijoE( x, "steps" ), "step" ) ) {
      StepMeta p = ctx.trans.findStep( CtxTrad.hijo( st, "name" ) );
      if ( p != null ) {
        vs.add( ctx.salidaHacia( p, s ) );
      }
    }
    if ( vs.isEmpty() ) {
      vs = ctx.entradas( s );
    }
    if ( ctx.pandas() ) {
      b.l( out + " = pd.concat([" + String.join( ", ", vs ) + "], ignore_index=True)" );
    } else {
      StringBuilder u = new StringBuilder( vs.isEmpty() ? sinFilas( ctx ) : vs.get( 0 ) );
      for ( int i = 1; i < vs.size(); i++ ) {
        u.append( ".unionByName(" ).append( vs.get( i ) ).append( ")" );
      }
      b.l( out + " = " + u );
    }
    b.vista = out;
    return b;
  }

  /** Fuzzy match: el valor más parecido del flujo de búsqueda (Levenshtein, Jaro-Winkler...). */
  static Bloque fuzzy( StepMeta s, CtxTrad ctx ) {
    FuzzyMatchMeta m = (FuzzyMatchMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String lk = info( s, ctx, b, infoNombre( s, 0 ) );
    String alg = FuzzyMatchMeta.algorithmCode[m.getAlgorithmType()];
    ctx.ayudante( "pdi_similitud", "def pdi_similitud(a, b, algoritmo):\n"
      + "    \"\"\"" + ctx.t( "Distancia (levenshtein, dameraulevenshtein) o similitud (jaro, jarowinkler, "
        + "pairsimilarity) entre dos textos; otros algoritmos: similitud de difflib.", "Distance (levenshtein, "
          + "dameraulevenshtein) or similarity (jaro, jarowinkler, pairsimilarity) between two texts; other "
          + "algorithms: difflib similarity.", "Distância (levenshtein, dameraulevenshtein) ou semelhança (jaro, "
            + "jarowinkler, pairsimilarity) entre dois textos; outros algoritmos: semelhança do difflib." ) + "\"\"\"\n"
      + "    if algoritmo in (\"levenshtein\", \"dameraulevenshtein\"):\n"
      + "        d = [[i + j if i * j == 0 else 0 for j in range(len(b) + 1)] for i in range(len(a) + 1)]\n"
      + "        for i in range(1, len(a) + 1):\n"
      + "            for j in range(1, len(b) + 1):\n"
      + "                c = 0 if a[i - 1] == b[j - 1] else 1\n"
      + "                d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)\n"
      + "                if algoritmo != \"levenshtein\" and i > 1 and j > 1 and a[i - 1] == b[j - 2] and "
      + "a[i - 2] == b[j - 1]:\n"
      + "                    d[i][j] = min(d[i][j], d[i - 2][j - 2] + c)\n"
      + "        return d[len(a)][len(b)]\n"
      + "    if algoritmo in (\"jaro\", \"jarowinkler\"):\n"
      + "        if not a or not b:\n"
      + "            return 0.0\n"
      + "        r = max(max(len(a), len(b)) // 2 - 1, 0)\n"
      + "        ma, mb = [False] * len(a), [False] * len(b)\n"
      + "        m = 0\n"
      + "        for i, ch in enumerate(a):\n"
      + "            for j in range(max(0, i - r), min(len(b), i + r + 1)):\n"
      + "                if not mb[j] and b[j] == ch:\n"
      + "                    ma[i] = mb[j] = True\n"
      + "                    m += 1\n"
      + "                    break\n"
      + "        if m == 0:\n"
      + "            return 0.0\n"
      + "        sa = [ch for ch, x in zip(a, ma) if x]\n"
      + "        sb = [ch for ch, x in zip(b, mb) if x]\n"
      + "        t = sum(x != y for x, y in zip(sa, sb)) / 2\n"
      + "        j = (m / len(a) + m / len(b) + (m - t) / m) / 3\n"
      + "        if algoritmo == \"jaro\":\n"
      + "            return j\n"
      + "        p = 0\n"
      + "        while p < min(4, len(a), len(b)) and a[p] == b[p]:\n"
      + "            p += 1\n"
      + "        return j + p * 0.1 * (1 - j)\n"
      + "    if algoritmo == \"pairsimilarity\":\n"
      + "        pa = {a[i:i + 2] for i in range(len(a) - 1)}\n"
      + "        pb = {b[i:i + 2] for i in range(len(b) - 1)}\n"
      + "        return 2 * len(pa & pb) / (len(pa) + len(pb)) if pa or pb else 0.0\n"
      + "    import difflib\n"
      + "    return difflib.SequenceMatcher(None, a, b).ratio()" );
    ctx.ayudante( "pdi_fuzzy", "def pdi_fuzzy(valor, candidatos, algoritmo, minimo, maximo, distancia, caso):\n"
      + "    \"\"\"" + ctx.t( "El candidato más cercano dentro de [mínimo, máximo] y su medida (o None, None).",
        "The closest candidate within [minimum, maximum] and its measure (or None, None).",
        "O candidato mais próximo dentro de [mínimo, máximo] e a sua medida (ou None, None)." ) + "\"\"\"\n"
      + "    if valor is None or (isinstance(valor, float) and valor != valor):\n"
      + "        return None, None\n"
      + "    mejor, medida = None, None\n"
      + "    for c in candidatos:\n"
      + "        x = pdi_similitud(str(valor) if caso else str(valor).lower(), str(c) if caso else str(c).lower(), "
      + "algoritmo)\n"
      + "        if minimo <= x <= maximo and (medida is None or (x < medida if distancia else x > medida)):\n"
      + "            mejor, medida = c, x\n"
      + "    return mejor, medida" );
    boolean distancia = alg.toLowerCase( Locale.ROOT ).contains( "levenshtein" );
    String mf = vacio( m.getOutputMatchField() ) ? "match" : m.getOutputMatchField();
    String vf = vacio( m.getOutputValueField() ) ? "measure value" : m.getOutputValueField();
    String args = CtxTrad.lit( alg.toLowerCase( Locale.ROOT ) ) + ", " + numero( ctx, m.getMinimalValue(), "0" ) + ", "
      + numero( ctx, m.getMaximalValue(), "1" ) + ", " + ( distancia ? "True" : "False" ) + ", " + ( m
        .isCaseSensitive() ? "True" : "False" );
    String cand = ctx.nuevo( "_candidatos" );
    if ( ctx.pandas() ) {
      b.l( cand + " = " + lk + "[" + CtxTrad.lit( m.getLookupField() ) + "].dropna().drop_duplicates().tolist()" );
      String r = ctx.nuevo( "_r" );
      b.l( r + " = [pdi_fuzzy(v, " + cand + ", " + args + ") for v in " + in + "[" + CtxTrad.lit( m
        .getMainStreamField() ) + "]]" );
      b.l( out + " = " + in + ".assign(**{" + CtxTrad.lit( mf ) + ": [x[0] for x in " + r + "], " + CtxTrad.lit( vf )
        + ": [x[1] for x in " + r + "]})" );
    } else {
      b.l( cand + " = [f[0] for f in " + lk + ".select(" + CtxTrad.lit( m.getLookupField() ) + ").dropna().distinct()"
        + ".collect()]" );
      String u = ctx.nuevo( "_fuzzy" );
      b.l( u + " = F.udf(lambda v: pdi_fuzzy(v, " + cand + ", " + args + "), \"m string, v double\")" );
      b.l( out + " = " + in + ".withColumn(\"_f\", " + u + "(F.col(" + CtxTrad.lit( m.getMainStreamField() ) + ")))"
        + ".withColumn(" + CtxTrad.lit( mf ) + ", F.col(\"_f.m\")).withColumn(" + CtxTrad.lit( vf ) + ", F.col(\"_f.v\"))"
        + ".drop(\"_f\")" );
    }
    if ( m.getValue() != null && m.getValue().length > 0 ) {
      b.aproximado( ctx.t( "Los campos extra del flujo de búsqueda no se añaden: únelos por el campo coincidente.",
        "Extra fields from the lookup stream are not added: join them on the match field.",
        "Os campos extra do fluxo de busca não são adicionados: junta-os pelo campo coincidente." ) );
    }
    b.aproximado( ctx.t( "Fuzzy match: las medidas siguen las fórmulas habituales (las de PDI vienen de librerías "
      + "Java y pueden diferir en detalles); con varios candidatos empatados se queda el primero.", "Fuzzy match: "
        + "measures follow the usual formulas (PDI's come from Java libraries and may differ in details); among tied "
        + "candidates the first one is kept.", "Fuzzy match: as medidas seguem as fórmulas habituais (as do PDI vêm de "
          + "bibliotecas Java e podem diferir em detalhes); entre candidatos empatados fica o primeiro." ) );
    b.vista = out;
    return b;
  }

  // ================================================================ muestras y control del flujo

  /** Sample rows: las filas cuyos números (desde 1) están en los rangos «1..3,7». */
  static Bloque muestra( StepMeta s, CtxTrad ctx ) {
    SampleRowsMeta m = (SampleRowsMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    ctx.ayudante( "pdi_lineas", "def pdi_lineas(rangos):\n"
      + "    \"\"\"" + ctx.t( "Números de línea de un texto de rangos de PDI, como «1..3,7».", "Line numbers of a PDI "
        + "range text such as '1..3,7'.", "Números de linha de um texto de intervalos do PDI, como «1..3,7»." )
      + "\"\"\"\n"
      + "    r = []\n"
      + "    for parte in str(rangos).split(\",\"):\n"
      + "        a, _, b = parte.strip().partition(\"..\")\n"
      + "        if a:\n"
      + "            r.extend(range(int(a), int(b or a) + 1))\n"
      + "    return r" );
    String campo = m.getLineNumberField();
    String rango = ctx.texto( m.getLinesRange() );
    if ( ctx.pandas() ) {
      String nn = ctx.nuevo( "_n" );
      b.l( nn + " = np.arange(1, len(" + in + ") + 1)" );
      b.l( out + " = " + in + "[np.isin(" + nn + ", pdi_lineas(" + rango + "))]" + ( vacio( campo ) ? "" : ".assign(**{"
        + CtxTrad.lit( campo ) + ": " + nn + "[np.isin(" + nn + ", pdi_lineas(" + rango + "))]})" )
        + ".reset_index(drop=True)" );
    } else {
      b.l( out + " = " + in + ".withColumn(\"_n\", F.row_number().over(Window.orderBy(F.monotonically_increasing_id"
        + "())))" );
      b.l( out + " = " + out + ".filter(F.col(\"_n\").isin(pdi_lineas(" + rango + ")))" + ( vacio( campo )
        ? ".drop(\"_n\")" : ".withColumnRenamed(\"_n\", " + CtxTrad.lit( campo ) + ")" ) );
    }
    b.vista = out;
    return b;
  }

  /** Reservoir sampling: N filas al azar. */
  static Bloque reservorio( StepMeta s, CtxTrad ctx ) {
    ReservoirSamplingMeta m = (ReservoirSamplingMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String n = numero( ctx, m.getSampleSize(), "100" );
    String semilla = numero( ctx, m.getSeed(), "1" );
    if ( ctx.pandas() ) {
      b.l( out + " = " + in + ".sample(n=min(int(" + n + "), len(" + in + ")), random_state=int(" + semilla
        + ")).sort_index().reset_index(drop=True)" );
    } else {
      b.l( out + " = " + in + ".orderBy(F.rand(int(" + semilla + "))).limit(int(" + n + "))" );
    }
    b.aproximado( ctx.t( "Muestra al azar con la misma semilla, pero no las mismas filas que el generador de Java de "
      + "PDI.", "Random sample with the same seed, but not the same rows as PDI's Java generator.",
      "Amostra aleatória com a mesma semente, mas não as mesmas linhas que o gerador Java do PDI." ) );
    b.vista = out;
    return b;
  }

  /** Blocking step: espera a todas las filas; sin «pasar todas», sólo deja pasar la última. */
  static Bloque bloqueo( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    boolean todas = !"N".equalsIgnoreCase( CtxTrad.hijo( x, "pass_all_rows" ).trim() );
    if ( todas ) {
      b.l( out + " = " + in + "  # " + ctx.t( "en Python cada paso ya termina antes del siguiente", "in Python each "
        + "step already ends before the next one", "em Python cada passo já termina antes do seguinte" ) );
    } else if ( ctx.pandas() ) {
      b.l( out + " = " + in + ".tail(1).reset_index(drop=True)  # " + ctx.t( "sólo la última fila", "only the last "
        + "row", "só a última linha" ) );
    } else {
      b.l( out + " = " + in + ".withColumn(\"_orden\", F.monotonically_increasing_id()).orderBy(F.desc(\"_orden\"))"
        + ".limit(1).drop(\"_orden\")" );
    }
    b.vista = out;
    return b;
  }

  /** Block this step until steps finish: en un script secuencial no hace falta esperar. */
  static Bloque esperar( StepMeta s, CtxTrad ctx ) {
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    b.l( ctx.df( s ) + " = " + in + "  # " + ctx.t( "los steps anteriores ya terminaron: el script es secuencial",
      "the previous steps already finished: the script is sequential",
      "os steps anteriores já terminaram: o script é sequencial" ) );
    b.vista = ctx.df( s );
    return b;
  }

  /** Abort: corta el programa si llegan más filas que el umbral. */
  static Bloque abortar( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String umbral = numero( ctx, CtxTrad.hijo( x, "row_threshold" ), "0" );
    String msg = CtxTrad.hijo( x, "message" );
    String opcion = CtxTrad.hijo( x, "abort_option" );
    if ( vacio( msg ) ) {
      msg = ctx.t( "Abort: la transformación se cortó por el step «" + s.getName() + "».", "Abort: the transformation "
        + "was stopped by step '" + s.getName() + "'.", "Abort: a transformação foi interrompida pelo step «" + s
          .getName() + "»." );
    }
    String n = ctx.pandas() ? "len(" + in + ")" : in + ".count()";
    if ( "SAFE_STOP".equalsIgnoreCase( opcion ) ) {
      b.l( "if " + n + " > " + umbral + ":" );
      b.l( "    print(" + ctx.texto( msg ) + ")" );
      b.l( out + " = " + ( ctx.pandas() ? in + ".head(" + umbral + ")" : in + ".limit(" + umbral + ")" ) );
      b.aproximado( ctx.t( "Parada segura: se quedan las filas hasta el umbral; PDI detiene todos los steps.",
        "Safe stop: rows up to the threshold are kept; PDI stops every step.",
        "Paragem segura: ficam as linhas até ao limite; o PDI pára todos os steps." ) );
    } else {
      b.l( "if " + n + " > " + umbral + ":" );
      b.l( "    raise RuntimeError(" + ctx.texto( msg ) + ")" );
      b.l( out + " = " + in );
    }
    b.vista = out;
    return b;
  }

  /** Detect empty stream: una fila de nulos si la entrada está vacía; ninguna si no. */
  static Bloque vacio( StepMeta s, CtxTrad ctx ) {
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    if ( ctx.pandas() ) {
      b.l( out + " = pd.DataFrame([[None] * len(" + in + ".columns)], columns=" + in + ".columns) if " + in
        + ".empty else " + in + ".iloc[0:0]" );
    } else {
      b.l( out + " = spark.createDataFrame([[None] * len(" + in + ".columns)], " + in + ".schema) if " + in
        + ".isEmpty() else " + in + ".limit(0)" );
    }
    b.vista = out;
    return b;
  }

  /** Identify last row in a stream: un booleano que sólo es True en la última fila. */
  static Bloque ultima( StepMeta s, CtxTrad ctx ) {
    DetectLastRowMetaLector m = new DetectLastRowMetaLector( s );
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String f = CtxTrad.lit( m.campo );
    if ( ctx.pandas() ) {
      b.l( out + " = " + in + ".assign(**{" + f + ": np.arange(len(" + in + ")) == len(" + in + ") - 1})" );
    } else {
      b.l( out + " = " + in + ".withColumn(\"_orden\", F.monotonically_increasing_id())" );
      b.l( out + " = " + out + ".withColumn(" + f + ", F.col(\"_orden\") == F.max(\"_orden\").over(Window"
        + ".partitionBy())).orderBy(\"_orden\").drop(\"_orden\")" );
    }
    b.vista = out;
    return b;
  }

  /** Lee el campo resultado del DetectLastRowMeta sin depender del setter. */
  private static final class DetectLastRowMetaLector {
    final String campo;

    DetectLastRowMetaLector( StepMeta s ) {
      String c = null;
      if ( s.getStepMetaInterface() instanceof org.pentaho.di.trans.steps.detectlastrow.DetectLastRowMeta ) {
        c = ( (org.pentaho.di.trans.steps.detectlastrow.DetectLastRowMeta) s.getStepMetaInterface() )
          .getResultFieldName();
      }
      campo = vacio( c ) ? "last" : c;
    }
  }

  /** Delay row: PDI espera un tiempo por fila; en el script no se espera (no cambia los datos). */
  static Bloque demora( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String t = CtxTrad.hijo( x, "timeout" );
    String esc = CtxTrad.hijo( x, "scaletime" );
    double seg = 1;
    try {
      seg = Double.parseDouble( t.trim() );
    } catch ( NumberFormatException e ) {
      seg = 1;
    }
    String e = esc.trim().toLowerCase( Locale.ROOT );
    if ( "0".equals( e ) || e.startsWith( "milli" ) ) {
      seg /= 1000;
    } else if ( "2".equals( e ) || e.startsWith( "min" ) ) {
      seg *= 60;
    } else if ( "3".equals( e ) || e.startsWith( "hour" ) ) {
      seg *= 3600;
    }
    ctx.importar( "import time" );
    b.l( "# time.sleep(" + seg + " * " + ( ctx.pandas() ? "len(" + in + ")" : in + ".count()" ) + ")  # " + ctx.t(
      "PDI espera esto por fila; descomenta si lo necesitas", "PDI waits this per row; uncomment it if you need it",
      "o PDI espera isto por linha; tira o comentário se precisares" ) );
    b.l( ctx.df( s ) + " = " + in );
    b.vista = ctx.df( s );
    return b;
  }

  private static void resultado( CtxTrad ctx ) {
    ctx.ayudante( "RESULT", "RESULT = globals().setdefault(\"RESULT\", {})" );
  }

  /** Copy rows to result: deja las filas en RESULT["rows"] para la siguiente entrada del job. */
  static Bloque aResultado( StepMeta s, CtxTrad ctx ) {
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    resultado( ctx );
    b.l( "RESULT[\"rows\"] = " + in + "  # " + ctx.t( "las filas del resultado, para el job", "the result rows, for "
      + "the job", "as linhas do resultado, para o job" ) );
    b.l( ctx.df( s ) + " = " + in );
    b.vista = ctx.df( s );
    return b;
  }

  /** Get rows from result: las filas que dejó la transformación anterior del job en RESULT["rows"]. */
  static Bloque deResultado( StepMeta s, CtxTrad ctx ) {
    RowsFromResultMeta m = (RowsFromResultMeta) s.getStepMetaInterface();
    Bloque b = bloque( s );
    resultado( ctx );
    String out = ctx.df( s );
    List<String> campos = Arrays.asList( m.getFieldname() == null ? new String[0] : m.getFieldname() );
    if ( ctx.pandas() ) {
      b.l( out + " = RESULT.get(\"rows\", pd.DataFrame(columns=" + lista( campos ) + "))" );
    } else {
      StringBuilder esq = new StringBuilder();
      for ( int i = 0; i < campos.size(); i++ ) {
        String t = sparkTipo( m.getType()[i] );
        esq.append( i == 0 ? "" : ", " ).append( "`" ).append( campos.get( i ) ).append( "` " ).append( t == null
          ? "string" : t );
      }
      b.l( out + " = RESULT.get(\"rows\")" );
      b.l( "if " + out + " is None:" );
      b.l( "    " + out + " = spark.createDataFrame([], " + CtxTrad.lit( esq.toString() ) + ")" );
    }
    b.lee = true;
    b.vista = out;
    return b;
  }

  /** Mapping / Transformation executor / Job executor: otra transformación o job; se llama a su traducción. */
  static Bloque subtrans( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = bloque( s );
    String in = entrada( s, ctx, b );
    String out = ctx.df( s );
    String f = CtxTrad.hijo( x, "filename" );
    if ( vacio( f ) ) {
      f = CtxTrad.hijo( x, "trans_name" ) + CtxTrad.hijo( x, "job_name" );
    }
    String nombre = f.replace( '\\', '/' );
    nombre = nombre.substring( nombre.lastIndexOf( '/' ) + 1 ).replaceAll( "\\.(ktr|kjb)$", "" );
    String fn = ( s.getStepID().equals( "JobExecutor" ) ? "job_" : "trans_" ) + CtxTrad.ident( nombre.isEmpty()
      ? "sub" : nombre );
    b.l( "# TODO: " + ctx.t( "traduce «" + f + "» con Pentaho Live y llama a su función con estas filas, por ejemplo:",
      "translate '" + f + "' with Pentaho Live and call its function with these rows, for example:", "traduz «" + f
        + "» com o Pentaho Live e chama a sua função com estas linhas, por exemplo:" ) );
    b.l( "# " + out + " = " + fn + "(" + in + ")" );
    b.l( out + " = " + in );
    b.manual( ctx.t( "«" + s.getStepID() + "» ejecuta otra transformación o job (" + f + "): tradúcelo aparte y "
      + "conecta sus entradas y salidas.", "'" + s.getStepID() + "' runs another transformation or job (" + f
        + "): translate it separately and wire its inputs and outputs.", "«" + s.getStepID() + "» executa outra "
          + "transformação ou job (" + f + "): traduz à parte e liga as suas entradas e saídas." ) );
    b.vista = out;
    return b;
  }
}
