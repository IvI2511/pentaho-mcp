package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Ctx.SinCampos;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * unsorted_input (§2): Group by, Merge join, Unique rows, Merge rows, Row denormaliser, Analytic query y Sorted
 * merge necesitan las filas ordenadas por la clave. Sube por la entrada principal hasta algo que fije el orden.
 * Estático: W (E solo si un Data grid prueba el desorden); el modo profundo (WP3) confirma o descarta.
 */
public final class ReglasOrden {

  static final Set<String> CONSUMIDORES = new HashSet<String>( Arrays.asList(
    "GroupBy", "MergeJoin", "Unique", "MergeRows", "Denormaliser", "AnalyticQuery", "SortedMerge" ) );
  /** Comparan con RowMeta.compare: además del conjunto de claves, el orden de las claves y el sentido. */
  static final Set<String> COMPARAN = new HashSet<String>( Arrays.asList( "MergeJoin", "MergeRows", "SortedMerge" ) );
  /** En el camino: no se puede juzgar estáticamente qué orden sale. */
  static final Set<String> ORDEN_DESCONOCIDO = new HashSet<String>( Arrays.asList( "MemoryGroupBy", "JoinRows",
    "Normaliser", "SplitFieldToRows3", "Mapping", "SimpleMapping", "TransExecutor", "ScriptValueMod",
    "UserDefinedJavaClass", "MultiwayMergeJoin" ) );

  private ReglasOrden() {
  }

  public static List<Regla> todas() {
    return Collections.<Regla>singletonList( new EntradaSinOrdenar() );
  }

  /** Lo que se encontró al subir: estado y datos para el mensaje. */
  static final class Veredicto {
    static final int OK = 0;
    static final int DESCONOCIDO = 1;
    static final int FUENTE = 2;
    static final int TABLA = 3;
    static final int OTRA_CLAVE = 4;
    static final int OTRO_ORDEN = 5;
    static final int COPIAS = 6;
    static final int MEZCLA = 7;
    static final int DATOS = 8;
    static final int SQL_CLAVES = 9;

    final int estado;
    final StepMeta donde;
    final String detalle;
    final List<String> claves; // las del Sort rows / ORDER BY encontrado

    Veredicto( int estado, StepMeta donde, String detalle, List<String> claves ) {
      this.estado = estado;
      this.donde = donde;
      this.detalle = detalle;
      this.claves = claves;
    }
  }

  static final class EntradaSinOrdenar extends ReglaBase {

    EntradaSinOrdenar() {
      super( "unsorted_input", Sev.ERROR, Regla.CORRECTNESS );
      conCampos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      TransMeta tm = ctx.trans();
      for ( StepMeta s : tm.getSteps() ) {
        if ( CONSUMIDORES.contains( s.getStepID() ) ) {
          consumidor( ctx, s, out );
        }
      }
    }

    private void consumidor( Ctx ctx, StepMeta s, List<Hallazgo> out ) throws Exception {
      TransMeta tm = ctx.trans();
      Element x = ctx.xml( s );
      String id = s.getStepID();
      List<StepMeta> fuentes = new ArrayList<StepMeta>();
      List<List<String>> claves = new ArrayList<List<String>>();
      List<String> atributos = new ArrayList<String>();
      boolean[] asc = null;
      if ( id.equals( "MergeJoin" ) ) {
        agregar( fuentes, claves, atributos, tm.findStep( Nodos.txt( x, "step1" ) ), Nodos.nombres( x, "keys_1", "key", null ), "step1" );
        agregar( fuentes, claves, atributos, tm.findStep( Nodos.txt( x, "step2" ) ), Nodos.nombres( x, "keys_2", "key", null ), "step2" );
      } else if ( id.equals( "MergeRows" ) ) {
        List<String> k = Nodos.nombres( x, "keys", "key", null );
        agregar( fuentes, claves, atributos, tm.findStep( Nodos.txt( x, "reference" ) ), k, "reference" );
        agregar( fuentes, claves, atributos, tm.findStep( Nodos.txt( x, "compare" ) ), k, "compare" );
      } else {
        List<String> k = id.equals( "Unique" ) || id.equals( "SortedMerge" )
          ? Nodos.nombres( x, "fields", "field", "name" ) : Nodos.nombres( x, "group", "field", "name" );
        if ( id.equals( "SortedMerge" ) ) {
          List<Element> fs = Nodos.hijos( Nodos.hijo( x, "fields" ), "field" );
          asc = new boolean[ fs.size() ];
          for ( int i = 0; i < fs.size(); i++ ) {
            asc[ i ] = !"N".equalsIgnoreCase( Nodos.txt( fs.get( i ), "ascending" ) );
          }
        }
        if ( k.isEmpty() ) {
          if ( !id.equals( "Unique" ) ) {
            return; // sin claves: un solo grupo, el orden no importa
          }
          // Unique rows sin campos compara la fila entera: las claves son todas
          try {
            RowMetaInterface rm = ctx.entrada( s );
            k = new ArrayList<String>( Arrays.asList( rm.getFieldNames() ) );
          } catch ( SinCampos e ) {
            ctx.noEvaluado( e, s );
            return;
          }
          if ( k.isEmpty() ) {
            return;
          }
        }
        List<StepMeta> previos = ctx.previos( s );
        if ( previos.size() > 1 && !id.equals( "SortedMerge" ) ) {
          Hallazgo h = ctx.enStep( Sev.WARNING, s, "inputs" );
          h.message = ctx.t( "unsorted_input.msg.multi", "step", s.getName(), "n", previos.size(), "keys", unir( k ) );
          h.evidencia( "keys", unir( k ) ).evidencia( "inputs", previos.size() );
          h.fix = Arreglo.conConfirmacion( Arreglo.INSERT_SORT ).param( "keys", unir( k ) ).param( "after_merge", "true" );
          out.add( h );
          return;
        }
        for ( StepMeta p : previos ) {
          agregar( fuentes, claves, atributos, p, k, "input" );
        }
      }
      boolean comparan = COMPARAN.contains( id );
      int varias = 0;
      for ( String a : atributos ) {
        varias += a.equals( "input" ) ? 1 : 0;
      }
      int n = 0;
      for ( int i = 0; i < fuentes.size(); i++ ) {
        StepMeta f = fuentes.get( i );
        List<String> k = claves.get( i );
        if ( f == null || k.isEmpty() ) {
          continue;
        }
        if ( atributos.get( i ).equals( "input" ) ) {
          n++;
        }
        boolean[] sentido = asc != null && asc.length >= k.size() ? asc : todosAsc( k.size() );
        Veredicto v = caminar( ctx, f, k, id, comparan, sentido );
        if ( v.estado == Veredicto.OK ) {
          continue;
        }
        if ( v.estado == Veredicto.DESCONOCIDO ) {
          ctx.noEvaluado( "order_unknown", s.getName(), null, ctx.t( "ne.order_unknown", "detail", v.detalle ) );
          continue;
        }
        Hallazgo h = ctx.enStep( v.estado == Veredicto.DATOS ? Sev.ERROR : Sev.WARNING, s, atributos.get( i ) );
        h.n = atributos.get( i ).equals( "input" ) && varias > 1 ? n : 0;
        String donde = v.donde == null ? "" : v.donde.getName();
        h.message = ctx.t( "unsorted_input.msg." + variante( v.estado ), "step", s.getName(), "input", f.getName(),
          "keys", unir( k ), "at", donde, "sortkeys", v.claves == null ? "" : unir( v.claves ), "detail", v.detalle );
        h.evidencia( "keys", unir( k ) ).evidencia( "input", f.getName() ).evidencia( "found_at", donde );
        if ( v.claves != null ) {
          h.evidencia( "sorted_by", unir( v.claves ) );
        }
        if ( v.detalle != null ) {
          h.evidencia( "detail", v.detalle );
        }
        if ( comparan ) {
          h.evidencia( "needs", "ascending, same key order" );
        }
        h.fix = Arreglo.conConfirmacion( Arreglo.INSERT_SORT ).param( "keys", unir( k ) ).param( "input", f.getName() );
        if ( v.estado == Veredicto.TABLA && v.donde != null ) {
          h.fix.param( "table_input", v.donde.getName() ).vista( "ORDER BY " + unir( k ) );
        }
        out.add( h );
      }
    }

    private static void agregar( List<StepMeta> fuentes, List<List<String>> claves, List<String> atributos,
                                 StepMeta f, List<String> k, String atributo ) {
      fuentes.add( f );
      claves.add( k );
      atributos.add( atributo );
    }

    private static String variante( int estado ) {
      switch ( estado ) {
        case Veredicto.FUENTE:
          return "source";
        case Veredicto.TABLA:
          return "table";
        case Veredicto.OTRA_CLAVE:
          return "wrong_keys";
        case Veredicto.OTRO_ORDEN:
          return "order";
        case Veredicto.COPIAS:
          return "copies";
        case Veredicto.MEZCLA:
          return "merges";
        case Veredicto.DATOS:
          return "data";
        case Veredicto.SQL_CLAVES:
          return "sql_keys";
        default:
          return "source";
      }
    }
  }

  static boolean[] todosAsc( int n ) {
    boolean[] a = new boolean[ n ];
    Arrays.fill( a, true );
    return a;
  }

  /** Sube por la entrada principal desde f hasta algo que fije (o rompa) el orden. */
  static Veredicto caminar( Ctx ctx, StepMeta f, List<String> clavesConsumidor, String consumidor, boolean comparan,
                            boolean[] asc ) throws Exception {
    List<String> keys = new ArrayList<String>( clavesConsumidor );
    StepMeta s = f;
    boolean copiasOk = consumidor.equals( "SortedMerge" ); // Sorted merge junta copias: es su trabajo
    for ( int prof = 0; prof < 60 && s != null; prof++ ) {
      Element x = ctx.xml( s );
      String id = s.getStepID();
      if ( id.equals( "SortRows" ) ) {
        List<Element> fs = Nodos.hijos( Nodos.hijo( x, "fields" ), "field" );
        List<String> sk = new ArrayList<String>();
        List<Boolean> sa = new ArrayList<Boolean>();
        for ( Element e : fs ) {
          sk.add( Nodos.txt( e, "name" ) );
          sa.add( !"N".equalsIgnoreCase( Nodos.txt( e, "ascending" ) ) );
        }
        if ( s.getCopies() > 1 && !copiasOk ) {
          return new Veredicto( Veredicto.COPIAS, s, String.valueOf( s.getCopies() ), null );
        }
        if ( cubre( keys, sk, sa, comparan, asc ) ) {
          return new Veredicto( Veredicto.OK, s, null, sk );
        }
        // ¿alguna clave no existe todavía a la salida del Sort rows? la crea un step posterior: no se juzga
        try {
          RowMetaInterface so = ctx.salida( s );
          for ( String k : keys ) {
            if ( so.indexOfValue( k ) < 0 ) {
              return new Veredicto( Veredicto.DESCONOCIDO, s, ctx.t( "ne.order_key_later", "key", k, "at", s.getName() ), null );
            }
          }
        } catch ( SinCampos e ) {
          return new Veredicto( Veredicto.DESCONOCIDO, s, e.getMessage(), null );
        }
        if ( comparan && mismoConjunto( keys, sk ) ) {
          return new Veredicto( Veredicto.OTRO_ORDEN, s, null, sk );
        }
        return new Veredicto( Veredicto.OTRA_CLAVE, s, null, sk );
      }
      if ( s.getCopies() > 1 && !copiasOk ) {
        return new Veredicto( Veredicto.COPIAS, s, String.valueOf( s.getCopies() ), null );
      }
      if ( id.equals( "TableInput" ) ) {
        return tabla( s, Nodos.txt( x, "sql" ), keys, comparan, asc );
      }
      if ( id.equals( "RowGenerator" ) ) {
        return new Veredicto( Veredicto.OK, s, null, null ); // filas iguales
      }
      if ( id.equals( "DataGrid" ) ) {
        String d = grilla( x, keys, comparan, asc );
        if ( d == null ) {
          return new Veredicto( Veredicto.OK, s, null, null );
        }
        if ( d.startsWith( "?" ) ) {
          return new Veredicto( Veredicto.DESCONOCIDO, s, d.substring( 1 ), null );
        }
        return new Veredicto( Veredicto.DATOS, s, d, null );
      }
      if ( id.equals( "GroupBy" ) || id.equals( "SortedMerge" ) ) {
        List<String> g = id.equals( "GroupBy" ) ? Nodos.nombres( x, "group", "field", "name" )
          : Nodos.nombres( x, "fields", "field", "name" );
        if ( !g.isEmpty() && cubre( keys, g, null, comparan, asc ) ) {
          return new Veredicto( Veredicto.OK, s, null, g );
        }
      }
      if ( id.equals( "MergeJoin" ) ) {
        List<String> g = Nodos.nombres( x, "keys_1", "key", null );
        if ( !g.isEmpty() && cubre( keys, g, null, comparan, asc ) ) {
          return new Veredicto( Veredicto.OK, s, null, g );
        }
      }
      if ( ORDEN_DESCONOCIDO.contains( id ) ) {
        return new Veredicto( Veredicto.DESCONOCIDO, s, ctx.t( "ne.order_step", "at", s.getName(), "type", id ), null );
      }
      List<StepMeta> prev = ctx.previos( s );
      if ( prev.isEmpty() ) {
        return new Veredicto( Veredicto.FUENTE, s, id, null );
      }
      if ( prev.size() > 1 ) {
        return new Veredicto( Veredicto.MEZCLA, s, String.valueOf( prev.size() ), null );
      }
      if ( id.equals( "SelectValues" ) ) { // renombres: la clave de abajo tiene otro nombre arriba
        for ( Element e : Nodos.hijos( Nodos.hijo( x, "fields" ), "field" ) ) {
          String nombre = Nodos.txt( e, "name" );
          String nuevo = Nodos.txt( e, "rename" );
          int i = nuevo.isEmpty() ? -1 : keys.indexOf( nuevo );
          if ( i >= 0 ) {
            keys.set( i, nombre );
          }
        }
      }
      s = prev.get( 0 );
    }
    return new Veredicto( Veredicto.DESCONOCIDO, s, ctx.t( "ne.order_depth" ), null );
  }

  /**
   * Si un orden (claves sk, sentidos sa; null = ascendente) sirve para el consumidor: para agrupar, el conjunto de
   * claves como prefijo; si compara, además mismo orden de claves y el sentido que espera.
   */
  static boolean cubre( List<String> keys, List<String> sk, List<Boolean> sa, boolean comparan, boolean[] asc ) {
    if ( sk.size() < keys.size() ) {
      return false;
    }
    if ( !comparan ) {
      return mismoConjunto( keys, sk );
    }
    for ( int i = 0; i < keys.size(); i++ ) {
      if ( !keys.get( i ).equals( sk.get( i ) ) ) {
        return false;
      }
      boolean a = sa == null || sa.get( i );
      if ( a != asc[ i ] ) {
        return false;
      }
    }
    return true;
  }

  static boolean mismoConjunto( List<String> keys, List<String> sk ) {
    if ( sk.size() < keys.size() ) {
      return false;
    }
    return new HashSet<String>( keys ).equals( new HashSet<String>( sk.subList( 0, keys.size() ) ) );
  }

  private static final Pattern FIN_ORDER_BY = Pattern.compile(
    "(?is)\\s+(limit|offset|fetch|for\\s+update|union|intersect|except)\\b.*$|;.*$" );
  private static final Pattern SENTIDO = Pattern.compile( "(?is)^(.*?)\\s+(asc|desc)(\\s+nulls\\s+(first|last))?$" );

  /** Table input: ORDER BY que nombra las claves (y, si compara, en ese orden y ascendente). */
  static Veredicto tabla( StepMeta s, String sql, List<String> keys, boolean comparan, boolean[] asc ) {
    String low = sql.toLowerCase( Locale.ROOT );
    int ob = low.lastIndexOf( "order by" );
    if ( ob < 0 ) {
      return new Veredicto( Veredicto.TABLA, s, null, null );
    }
    String cola = FIN_ORDER_BY.matcher( sql.substring( ob + 8 ) ).replaceAll( "" ).trim();
    List<String> cols = new ArrayList<String>();
    List<Boolean> sentidos = new ArrayList<Boolean>();
    boolean raro = cola.contains( "(" );
    for ( String item : cola.split( "," ) ) {
      String it = item.trim();
      boolean a = true;
      Matcher m = SENTIDO.matcher( it );
      if ( m.matches() ) {
        it = m.group( 1 ).trim();
        a = !m.group( 2 ).equalsIgnoreCase( "desc" );
      }
      it = it.replaceAll( "[\"`\\[\\]]", "" );
      int punto = it.lastIndexOf( '.' );
      if ( punto >= 0 ) {
        it = it.substring( punto + 1 );
      }
      if ( it.isEmpty() || it.contains( " " ) || it.matches( "\\d+" ) ) { // ORDER BY 1, 2: por posición
        raro = true;
      }
      cols.add( it );
      sentidos.add( a );
    }
    if ( !raro && cubre( minusculas( keys ), minusculas( cols ), sentidos, comparan, asc ) ) {
      return new Veredicto( Veredicto.OK, s, null, cols );
    }
    // lo que no se pudo partir: alcanza con que el ORDER BY nombre las claves (como el prototipo)
    boolean todas = true;
    String c = cola.toLowerCase( Locale.ROOT );
    for ( String k : keys ) {
      todas &= c.contains( k.toLowerCase( Locale.ROOT ) );
    }
    if ( raro ) { // expresiones o posiciones (ORDER BY 1, 2): si nombra las claves se da por bueno
      return todas ? new Veredicto( Veredicto.OK, s, null, null )
        : new Veredicto( Veredicto.DESCONOCIDO, s, "ORDER BY " + ( cola.length() > 80 ? cola.substring( 0, 80 ) + "…" : cola ), null );
    }
    if ( comparan && todas ) {
      return new Veredicto( Veredicto.OTRO_ORDEN, s, null, cols );
    }
    return new Veredicto( Veredicto.SQL_CLAVES, s, null, cols );
  }

  private static List<String> minusculas( List<String> l ) {
    List<String> r = new ArrayList<String>();
    for ( String x : l ) {
      r.add( x.toLowerCase( Locale.ROOT ) );
    }
    return r;
  }

  /**
   * Data grid: mira los datos. null si están agrupados (y, si compara, en orden); «?motivo» si no se puede saber;
   * si no, el texto de la prueba.
   */
  static String grilla( Element x, List<String> keys, boolean comparan, boolean[] asc ) {
    List<Element> fs = Nodos.hijos( Nodos.hijo( x, "fields" ), "field" );
    int[] idx = new int[ keys.size() ];
    boolean[] num = new boolean[ keys.size() ];
    for ( int k = 0; k < keys.size(); k++ ) {
      idx[ k ] = -1;
      for ( int i = 0; i < fs.size(); i++ ) {
        if ( Nodos.txt( fs.get( i ), "name" ).equals( keys.get( k ) ) ) {
          idx[ k ] = i;
          String t = Nodos.txt( fs.get( i ), "type" );
          num[ k ] = t.equals( "Integer" ) || t.equals( "Number" ) || t.equals( "BigNumber" );
        }
      }
      if ( idx[ k ] < 0 ) {
        return "?key " + keys.get( k ) + " is not a Data grid column";
      }
    }
    List<List<String>> filas = new ArrayList<List<String>>();
    for ( Element l : Nodos.hijos( Nodos.hijo( x, "data" ), "line" ) ) {
      List<Element> it = Nodos.hijos( l, "item" );
      List<String> r = new ArrayList<String>();
      for ( int k = 0; k < keys.size(); k++ ) {
        r.add( idx[ k ] < it.size() ? it.get( idx[ k ] ).getTextContent() : "" );
      }
      filas.add( r );
    }
    Set<List<String>> cerrados = new HashSet<List<String>>();
    for ( int i = 1; i < filas.size(); i++ ) {
      List<String> a = filas.get( i - 1 );
      List<String> b = filas.get( i );
      if ( !a.equals( b ) ) {
        cerrados.add( a );
        if ( cerrados.contains( b ) ) {
          return "group " + b + " appears again at row " + ( i + 1 );
        }
        if ( comparan && comparar( a, b, num, asc ) > 0 ) {
          return "row " + ( i + 1 ) + " " + b + " comes after " + a;
        }
      }
    }
    return null;
  }

  /** > 0 si a va después de b en el sentido pedido. */
  static int comparar( List<String> a, List<String> b, boolean[] num, boolean[] asc ) {
    for ( int i = 0; i < a.size(); i++ ) {
      int c;
      if ( num[ i ] ) {
        try {
          c = Double.compare( Double.parseDouble( a.get( i ).trim() ), Double.parseDouble( b.get( i ).trim() ) );
        } catch ( NumberFormatException e ) {
          c = a.get( i ).compareTo( b.get( i ) );
        }
      } else {
        c = a.get( i ).compareTo( b.get( i ) );
      }
      if ( c != 0 ) {
        return asc[ i ] ? c : -c;
      }
    }
    return 0;
  }

  static String unir( List<String> l ) {
    StringBuilder b = new StringBuilder();
    for ( String x : l ) {
      b.append( b.length() == 0 ? "" : ", " ).append( x );
    }
    return b.toString();
  }
}
