package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * Familia robustness (docs/robustez-diseno.md §3): lo que le falta a una transformación para tolerar filas sucias sin
 * pararse y sin perder filas en silencio. Todas apagadas por defecto (corren con lint_etl robustness=true o nombradas
 * en rules) y fuera del puntaje general: dan el sub-puntaje de robustez (LintMotor). Estáticas: XML de cada step y el
 * grafo de hops, sin campos ni conexiones (env = false). Cada hallazgo nombra la receta de harden_etl que lo arregla
 * (evidence.recipe) y el step donde se aplica (evidence.anchor); fix nulo, no van por apply_lint_fixes.
 *
 * Declaraciones: un step con el atributo pentaho-live-robust/drops descarta filas a propósito (duplicados, filtro,
 * agregado) y las reglas no lo marcan por eso; silenciar silent_filter_drop con suppress_lint es declararlo.
 */
public final class ReglasRobustez {

  /** Grupo de atributos de Kettle de la robustez (viaja en el .ktr, como pentaho-live-lint). */
  public static final String GRUPO = "pentaho-live-robust";
  public static final String DROPS = "drops";
  public static final String REASON = "reason";
  public static final String ADDED_BY = "added_by";

  static final Set<String> CARGAS = conjunto( "TableOutput", "InsertUpdate", "Update", "Delete", "SynchronizeAfterMerge",
    "DimensionLookup", "CombinationLookup" );
  /** Cargas con claves (lo que se pisa o se duplica si llegan dos filas con la misma clave). */
  static final Set<String> CARGAS_CON_CLAVE = conjunto( "InsertUpdate", "Update", "Delete", "SynchronizeAfterMerge",
    "DimensionLookup", "CombinationLookup" );
  static final Set<String> ENTRADAS_ARCHIVO = conjunto( "CsvInput", "TextFileInput", "ExcelInput", "JsonInput",
    "XMLInputStream", "getXMLData", "ParallelGzipCsvInput", "FixedInput", "LoadFileInput" );
  static final Set<String> BUSQUEDAS = conjunto( "DBLookup", "StreamLookup", "DBJoin", "FuzzyMatch" );
  /** Steps que escriben algo: un terminal de una rama de rechazo que es uno de estos no es un callejón sin salida. */
  public static final Set<String> ESCRITORES = conjunto( "TextFileOutput", "ExcelOutput", "TypeExitExcelWriterStep",
    "JsonOutput", "XMLOutput", "TableOutput", "InsertUpdate", "Update", "SynchronizeAfterMerge", "WriteToLog",
    "AccessOutput", "SQLFileOutput", "PropertyOutput", "CubeOutput", "MongoDbOutput", "TextFileOutputLegacy" );
  /** Quitan o juntan filas por diseño: deduplicar o agregar ya es una decisión, no una pérdida. */
  static final Set<String> DEDUPLICAN = conjunto( "UniqueRows", "UniqueRowsByHashSet", "GroupBy", "MemoryGroupBy" );
  static final Set<String> AGRUPAN = conjunto( "GroupBy", "MemoryGroupBy" );
  /** Tags del XML de una carga que nombran un campo del flujo (no banderas como update = N). */
  static final Set<String> TAGS_CAMPO = conjunto( "name", "rename", "field", "stream_name", "name2", "field_name",
    "stream_fieldname", "column_name" );
  /** Controles de nulos: Filter rows / Java filter con IS NULL / IS NOT NULL, Data validator. */
  static final Set<String> VALIDADORES = conjunto( "Validator" );
  /** Calculan un campo (un motivo, una marca) a partir de otros: pueden mirar si una búsqueda no encontró nada. */
  static final Set<String> CLASIFICAN = conjunto( "Janino", "ScriptValueMod", "Formula" );

  private static final Pattern STAGING = Pattern.compile( "(?i)(^|[._])(stg|tmp|temp|staging)(_|$)|staging" );
  private static final Pattern COMENTARIOS = Pattern.compile( "(?s)/\\*.*?\\*/|--[^\\r\\n]*" );
  private static final Pattern JOIN = Pattern.compile( "(?i)\\b(\\w+)\\s+join\\b|(^|[^\\w])join\\b" );
  private static final Pattern VARIABLE = Pattern.compile( "\\$\\{([^}]+)\\}|%%([^%]+)%%" );

  private ReglasRobustez() {
  }

  public static List<Regla> todas() {
    List<Regla> l = new ArrayList<Regla>();
    l.add( new SinRechazosEnCarga() );
    l.add( new SinRechazosEnConversion() );
    l.add( new RechazosSinMotivo() );
    l.add( new RechazosSinSalida() );
    l.add( new FiltroSilencioso() );
    l.add( new BusquedaQueDescarta() );
    l.add( new BusquedaSinControl() );
    l.add( new JoinInternoEnOrigen() );
    l.add( new SinObligatorios() );
    l.add( new ConversionPermisiva() );
    l.add( new DivisionFragil() );
    l.add( new SinDeduplicar() );
    l.add( new ContadorComoClave() );
    l.add( new SoloInsercion() );
    l.add( new HechoSinAgrupar() );
    l.add( new BusquedaAntesDeCargar() );
    l.add( new EntradaFija() );
    return l;
  }

  // ================================================================ base y grafo

  /** Regla de la familia: robustness, apagada por defecto, solo transformaciones. */
  abstract static class ReglaRobusta extends ReglaBase {

    ReglaRobusta( String id, Sev sev ) {
      super( id, sev, Regla.ROBUSTNESS );
      apagadaPorDefecto();
    }

    /** Hallazgo en un step con la receta y el ancla en la evidencia. */
    Hallazgo hallazgo( Ctx ctx, Sev sev, StepMeta s, String atributo, String receta, String msg, Object... pares ) {
      Hallazgo h = ctx.enStep( sev, s, atributo );
      Object[] todos = new Object[ pares.length + 2 ];
      todos[ 0 ] = "step";
      todos[ 1 ] = s.getName();
      System.arraycopy( pares, 0, todos, 2, pares.length );
      h.message = ctx.t( msg, todos );
      h.evidencia( "recipe", receta ).evidencia( "anchor", s.getName() );
      for ( int i = 0; i + 1 < pares.length; i += 2 ) {
        if ( pares[ i + 1 ] != null && !String.valueOf( pares[ i + 1 ] ).isEmpty() ) {
          h.evidencia( String.valueOf( pares[ i ] ), pares[ i + 1 ] );
        }
      }
      return h;
    }
  }

  /** El grafo de la transformación por hops habilitados: normales, de error y ramas falsas de los filtros. */
  static final class Grafo {
    final TransMeta tm;
    final Map<StepMeta, List<StepMeta>> salen = new IdentityHashMap<StepMeta, List<StepMeta>>();
    final Map<StepMeta, List<StepMeta>> entran = new IdentityHashMap<StepMeta, List<StepMeta>>();
    /** Salidas por el flujo principal (sin los hops de error). */
    final Map<StepMeta, List<StepMeta>> salenSinError = new IdentityHashMap<StepMeta, List<StepMeta>>();
    /** Donde empieza una rama de rechazo: destino de un hop de error o de la rama falsa de un filtro. */
    final Set<StepMeta> iniciosRechazo = conjuntoIdentidad();
    private Set<StepMeta> enRechazo;

    Grafo( Ctx ctx ) {
      tm = ctx.trans();
      for ( StepMeta s : tm.getSteps() ) {
        salen.put( s, new ArrayList<StepMeta>() );
        entran.put( s, new ArrayList<StepMeta>() );
        salenSinError.put( s, new ArrayList<StepMeta>() );
      }
      for ( TransHopMeta h : tm.getTransHops() ) {
        StepMeta a = h.getFromStep();
        StepMeta b = h.getToStep();
        if ( a == null || b == null || !h.isEnabled() || !salen.containsKey( a ) || !salen.containsKey( b ) ) {
          continue;
        }
        salen.get( a ).add( b );
        entran.get( b ).add( a );
        if ( a.isSendingErrorRowsToStep( b ) ) {
          iniciosRechazo.add( b );
        } else {
          salenSinError.get( a ).add( b );
        }
      }
      for ( StepMeta s : tm.getSteps() ) {
        if ( "FilterRows".equals( s.getStepID() ) ) {
          StepMeta falso = destinoFalso( ctx, s );
          if ( falso != null ) {
            iniciosRechazo.add( falso );
          }
        }
      }
    }

    StepMeta destinoFalso( Ctx ctx, StepMeta filtro ) {
      try {
        String n = Nodos.crudo( ctx.xml( filtro ), "send_false_to" );
        return n.trim().isEmpty() ? null : tm.findStep( n );
      } catch ( Exception e ) {
        return null;
      }
    }

    /** Todos los que quedan aguas abajo (sin incluir s). */
    Set<StepMeta> abajo( StepMeta s, boolean conError ) {
      Set<StepMeta> vistos = conjuntoIdentidad();
      Deque<StepMeta> cola = new ArrayDeque<StepMeta>();
      cola.add( s );
      while ( !cola.isEmpty() ) {
        StepMeta x = cola.poll();
        for ( StepMeta y : ( conError ? salen : salenSinError ).get( x ) ) {
          if ( vistos.add( y ) ) {
            cola.add( y );
          }
        }
      }
      vistos.remove( s );
      return vistos;
    }

    /** Todos los que quedan aguas arriba (flujos info incluidos, sin incluir s). */
    Set<StepMeta> arriba( StepMeta s ) {
      Set<StepMeta> vistos = conjuntoIdentidad();
      Deque<StepMeta> cola = new ArrayDeque<StepMeta>();
      cola.add( s );
      while ( !cola.isEmpty() ) {
        StepMeta x = cola.poll();
        for ( StepMeta y : entran.get( x ) ) {
          if ( vistos.add( y ) ) {
            cola.add( y );
          }
        }
      }
      vistos.remove( s );
      return vistos;
    }

    /** Aguas abajo de un hop de error o de la rama falsa de un filtro. */
    boolean enRamaDeRechazo( StepMeta s ) {
      if ( enRechazo == null ) {
        enRechazo = conjuntoIdentidad();
        for ( StepMeta i : iniciosRechazo ) {
          enRechazo.add( i );
          enRechazo.addAll( abajo( i, true ) );
        }
      }
      return enRechazo.contains( s );
    }

    /** Recorre aguas abajo por el flujo principal sin pasar por los steps que corta; true si llega a uno de destino. */
    StepMeta llegaA( StepMeta desde, Set<String> tiposDestino, Corte corte ) {
      Set<StepMeta> vistos = conjuntoIdentidad();
      Deque<StepMeta> cola = new ArrayDeque<StepMeta>( salenSinError.get( desde ) );
      while ( !cola.isEmpty() ) {
        StepMeta x = cola.poll();
        if ( !vistos.add( x ) || enRamaDeRechazo( x ) ) {
          continue;
        }
        if ( tiposDestino.contains( x.getStepID() ) && ( corte == null || corte.destino( x ) ) ) {
          return x;
        }
        if ( corte != null && corte.corta( x ) ) {
          continue;
        }
        cola.addAll( salenSinError.get( x ) );
      }
      return null;
    }
  }

  /** Dónde se corta un recorrido (un control) y qué destino cuenta. */
  abstract static class Corte {
    abstract boolean corta( StepMeta s );

    boolean destino( StepMeta s ) {
      return true;
    }
  }

  // ================================================================ reglas

  /** Carga a BD sin manejo de errores: un texto largo o un valor fuera de rango tumba la carga entera. */
  static final class SinRechazosEnCarga extends ReglaRobusta {

    SinRechazosEnCarga() {
      super( "no_rejects_on_load", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !CARGAS.contains( s.getStepID() ) || g.entran.get( s ).isEmpty() || g.enRamaDeRechazo( s )
          || s.isDoingErrorHandling() ) {
          continue;
        }
        if ( "TableOutput".equals( s.getStepID() ) && esSqlite( ctx, s ) ) {
          out.add( hallazgo( ctx, Sev.WARNING, s, "error_handling", "idempotent_load", "no_rejects_on_load.msg.sqlite",
            "table", tabla( ctx, s ) ) );
        } else if ( s.supportsErrorHandling() ) {
          out.add( hallazgo( ctx, Sev.WARNING, s, "error_handling", "rejects_on_error", "no_rejects_on_load.msg",
            "table", tabla( ctx, s ) ) );
        }
      }
    }
  }

  /** Conversión de tipos sin manejo de errores: un valor mal escrito aborta (o, en la entrada, no se puede desviar). */
  static final class SinRechazosEnConversion extends ReglaRobusta {

    SinRechazosEnConversion() {
      super( "no_rejects_on_conversion", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( s.isDoingErrorHandling() || g.enRamaDeRechazo( s ) ) {
          continue;
        }
        String id = s.getStepID();
        List<String> tipados;
        if ( "SelectValues".equals( id ) ) {
          tipados = conversiones( ctx.xml( s ) );
        } else if ( "CsvInput".equals( id ) || "TextFileInput".equals( id ) ) {
          tipados = camposTipados( ctx.xml( s ) );
        } else {
          continue;
        }
        if ( tipados.isEmpty() ) {
          continue;
        }
        boolean entrada = !"SelectValues".equals( id );
        String msg = entrada && !s.supportsErrorHandling() ? "no_rejects_on_conversion.msg.input"
          : "no_rejects_on_conversion.msg";
        out.add( hallazgo( ctx, Sev.WARNING, s, "error_handling", entrada && !s.supportsErrorHandling()
          ? "strict_conversion" : "rejects_on_error", msg, "fields", unir( tipados ) ) );
      }
    }
  }

  /** Rechazos sin motivo: hop de error sin campo de descripción, o rama falsa a un archivo sin columna de motivo. */
  static final class RechazosSinMotivo extends ReglaRobusta {

    RechazosSinMotivo() {
      super( "rejects_without_reason", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        StepErrorMeta em = s.getStepErrorMeta();
        if ( s.isDoingErrorHandling() && em != null && vacio( em.getErrorDescriptionsValuename() )
          && !motivoEnLaRama( ctx, g, em.getTargetStep() ) ) {
          String destino = em.getTargetStep() == null ? "" : em.getTargetStep().getName();
          out.add( hallazgo( ctx, Sev.WARNING, s, "error_handling", "reject_reason", "rejects_without_reason.msg",
            "target", destino ) );
          continue;
        }
        if ( "FilterRows".equals( s.getStepID() ) ) {
          StepMeta falso = g.destinoFalso( ctx, s );
          if ( falso == null ) {
            continue;
          }
          for ( StepMeta w : escritoresDesde( g, falso ) ) {
            List<String> campos = Nodos.nombres( ctx.xml( w ), "fields", "field", "name" );
            if ( !campos.isEmpty() && !tieneMotivo( campos ) ) {
              out.add( hallazgo( ctx, Sev.WARNING, s, "send_false_to", "reject_reason",
                "rejects_without_reason.msg.filter", "target", w.getName() ) );
              break;
            }
          }
        }
      }
    }
  }

  /** Filas rechazadas que no van a ningún archivo: la rama termina en un Dummy o en un step que no escribe. */
  static final class RechazosSinSalida extends ReglaRobusta {

    RechazosSinSalida() {
      super( "rejects_dead_end", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        StepMeta inicio = null;
        String atributo = null;
        if ( s.isDoingErrorHandling() && s.getStepErrorMeta().getTargetStep() != null ) {
          inicio = s.getStepErrorMeta().getTargetStep();
          atributo = "error_handling";
        } else if ( "FilterRows".equals( s.getStepID() ) ) {
          inicio = g.destinoFalso( ctx, s );
          atributo = "send_false_to";
        }
        if ( inicio == null ) {
          continue;
        }
        StepMeta muerto = callejon( g, inicio );
        if ( muerto != null ) {
          out.add( hallazgo( ctx, Sev.WARNING, s, atributo, "rejects_on_error", "rejects_dead_end.msg", "target",
            muerto.getName() ) );
        }
      }
    }

    /** El primer terminal de la rama que no escribe y no está declarado, o null. */
    private StepMeta callejon( Grafo g, StepMeta inicio ) {
      Set<StepMeta> vistos = conjuntoIdentidad();
      Deque<StepMeta> cola = new ArrayDeque<StepMeta>();
      cola.add( inicio );
      while ( !cola.isEmpty() ) {
        StepMeta x = cola.poll();
        if ( !vistos.add( x ) ) {
          continue;
        }
        List<StepMeta> sig = g.salen.get( x );
        if ( sig.isEmpty() && !ESCRITORES.contains( x.getStepID() ) && declaracion( x ) == null ) {
          return x;
        }
        cola.addAll( sig );
      }
      return null;
    }
  }

  /** Filter rows sin rama falsa (o Switch / case sin default) en un flujo que carga: descarta sin rastro. */
  static final class FiltroSilencioso extends ReglaRobusta {

    FiltroSilencioso() {
      super( "silent_filter_drop", Sev.INFO );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      Set<String> salidas = new HashSet<String>( CARGAS );
      salidas.addAll( ESCRITORES );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        String id = s.getStepID();
        boolean sinFalso;
        if ( "FilterRows".equals( id ) ) {
          Element x = ctx.xml( s );
          sinFalso = Nodos.crudo( x, "send_false_to" ).trim().isEmpty() && tieneCondicion( x );
        } else if ( "SwitchCase".equals( id ) ) {
          sinFalso = Nodos.txt( ctx.xml( s ), "default_target_step" ).isEmpty();
        } else {
          continue;
        }
        if ( !sinFalso || declaracion( s ) != null || g.enRamaDeRechazo( s ) ) {
          continue;
        }
        if ( g.llegaA( s, salidas, null ) != null ) {
          out.add( hallazgo( ctx, Sev.INFO, s, "send_false_to", "reject_reason", "SwitchCase".equals( id )
            ? "silent_filter_drop.msg.switch" : "silent_filter_drop.msg" ) );
        }
      }
    }

    private static boolean tieneCondicion( Element filtro ) {
      Element c = Nodos.hijo( Nodos.hijo( filtro, "compare" ), "condition" );
      return c != null && ( !Nodos.txt( c, "leftvalue" ).isEmpty() || Nodos.hijo( c, "conditions" ) != null );
    }
  }

  /** Database lookup que se come la fila si no encuentra, o Merge join INNER antes de una carga. */
  static final class BusquedaQueDescarta extends ReglaRobusta {

    BusquedaQueDescarta() {
      super( "lookup_drops_rows", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( g.enRamaDeRechazo( s ) || declaracion( s ) != null ) {
          continue;
        }
        if ( "DBLookup".equals( s.getStepID() ) ) {
          if ( si( Nodos.txt( Nodos.hijo( ctx.xml( s ), "lookup" ), "eat_row_on_failure" ) ) ) {
            out.add( hallazgo( ctx, Sev.WARNING, s, "eat_row_on_failure", "orphans_to_rejects",
              "lookup_drops_rows.msg" ) );
          }
        } else if ( "MergeJoin".equals( s.getStepID() ) ) {
          String tipo = Nodos.txt( ctx.xml( s ), "join_type" );
          if ( "INNER".equalsIgnoreCase( tipo ) && g.llegaA( s, CARGAS, null ) != null ) {
            out.add( hallazgo( ctx, Sev.WARNING, s, "join_type", "orphans_to_rejects",
              "lookup_drops_rows.msg.merge_join" ) );
          }
        }
      }
    }
  }

  /** Clave devuelta por una búsqueda, sin valor por defecto, que llega a una carga sin un filtro de nulos. */
  static final class BusquedaSinControl extends ReglaRobusta {

    BusquedaSinControl() {
      super( "unchecked_lookup", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      final Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        String id = s.getStepID();
        if ( !( "DBLookup".equals( id ) || "StreamLookup".equals( id ) ) || g.enRamaDeRechazo( s ) ) {
          continue;
        }
        Element lk = Nodos.hijo( ctx.xml( s ), "lookup" );
        if ( "DBLookup".equals( id ) && si( Nodos.txt( lk, "eat_row_on_failure" ) ) ) {
          continue; // eso lo dice lookup_drops_rows
        }
        for ( Element v : Nodos.hijos( lk, "value" ) ) {
          if ( !Nodos.txt( v, "default" ).isEmpty() ) {
            continue; // miembro desconocido: la fila sigue con un valor
          }
          final String campo = Nodos.txt( v, "rename" ).isEmpty() ? Nodos.txt( v, "name" ) : Nodos.txt( v, "rename" );
          if ( campo.isEmpty() ) {
            continue;
          }
          final Ctx c = ctx;
          StepMeta carga = g.llegaA( s, CARGAS, new Corte() {
            @Override
            boolean corta( StepMeta x ) {
              return controlaNulo( c, x, campo );
            }

            @Override
            boolean destino( StepMeta x ) {
              return usaCampo( c, x, campo );
            }
          } );
          if ( carga != null ) {
            out.add( hallazgo( ctx, Sev.WARNING, s, "lookup", "orphans_to_rejects", "unchecked_lookup.msg", "field",
              campo, "load", carga.getName() ) );
          }
        }
      }
    }
  }

  /** Table input con un JOIN interno que alimenta una carga: los huérfanos se pierden antes de llegar. */
  static final class JoinInternoEnOrigen extends ReglaRobusta {

    JoinInternoEnOrigen() {
      super( "inner_join_in_source", Sev.INFO );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !"TableInput".equals( s.getStepID() ) || g.enRamaDeRechazo( s ) ) {
          continue;
        }
        String sql = COMENTARIOS.matcher( Nodos.txt( ctx.xml( s ), "sql" ) ).replaceAll( " " );
        if ( joinInterno( sql ) && g.llegaA( s, CARGAS, null ) != null ) {
          out.add( hallazgo( ctx, Sev.INFO, s, "sql", "orphans_to_rejects", "inner_join_in_source.msg" ) );
        }
      }
    }
  }

  /** Una entrada de archivo llega a una carga o búsqueda por clave sin ningún control de nulos en el camino. */
  static final class SinObligatorios extends ReglaRobusta {

    SinObligatorios() {
      super( "no_required_check", Sev.INFO );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      final Grafo g = new Grafo( ctx );
      Set<String> conClave = new HashSet<String>( CARGAS_CON_CLAVE );
      conClave.addAll( BUSQUEDAS );
      final Ctx c = ctx;
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !ENTRADAS_ARCHIVO.contains( s.getStepID() ) ) {
          continue;
        }
        StepMeta destino = g.llegaA( s, conClave, new Corte() {
          @Override
          boolean corta( StepMeta x ) {
            return VALIDADORES.contains( x.getStepID() ) || controlaNulo( c, x, null );
          }
        } );
        if ( destino != null ) {
          out.add( hallazgo( ctx, Sev.INFO, s, "fields", "required_fields", "no_required_check.msg", "target",
            destino.getName(), "keys", unir( clavesDe( ctx, destino ) ) ) );
        }
      }
    }
  }

  /** Fecha o número convertido en modo permisivo: un valor mal escrito se cuela sin error. */
  static final class ConversionPermisiva extends ReglaRobusta {

    ConversionPermisiva() {
      super( "lenient_conversion", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !"SelectValues".equals( s.getStepID() ) ) {
          continue;
        }
        List<String> permisivos = new ArrayList<String>();
        for ( Element m : Nodos.hijos( Nodos.hijo( ctx.xml( s ), "fields" ), "meta" ) ) {
          String tipo = Nodos.txt( m, "type" );
          boolean fecha = "Date".equalsIgnoreCase( tipo ) || "Timestamp".equalsIgnoreCase( tipo );
          boolean numero = esNumerico( tipo );
          if ( fecha && si( Nodos.txt( m, "date_format_lenient" ) )
            || numero && si( Nodos.txt( m, "lenient_string_to_number" ) ) ) {
            permisivos.add( Nodos.txt( m, "name" ) );
          }
        }
        if ( !permisivos.isEmpty() ) {
          out.add( hallazgo( ctx, Sev.WARNING, s, "lenient", "strict_conversion", "lenient_conversion.msg", "fields",
            unir( permisivos ) ) );
        }
      }
    }
  }

  /** Field splitter sobre datos de archivo sin manejo de errores. */
  static final class DivisionFragil extends ReglaRobusta {

    DivisionFragil() {
      super( "fragile_split", Sev.INFO );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !"FieldSplitter".equals( s.getStepID() ) || s.isDoingErrorHandling() ) {
          continue;
        }
        if ( contieneTipo( g.arriba( s ), ENTRADAS_ARCHIVO ) ) {
          out.add( hallazgo( ctx, Sev.INFO, s, "splitfield", "safe_split", "fragile_split.msg", "field",
            Nodos.txt( ctx.xml( s ), "splitfield" ) ) );
        }
      }
    }
  }

  /** Carga por clave alimentada por un archivo o por varias fuentes, sin deduplicar antes. */
  static final class SinDeduplicar extends ReglaRobusta {

    SinDeduplicar() {
      super( "no_dedupe_before_load", Sev.INFO );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !( CARGAS_CON_CLAVE.contains( s.getStepID() ) || "TableOutput".equals( s.getStepID() ) )
          || g.enRamaDeRechazo( s ) || g.entran.get( s ).isEmpty() ) {
          continue;
        }
        Set<StepMeta> arriba = g.arriba( s );
        boolean variasFuentes = false;
        for ( StepMeta x : arriba ) {
          variasFuentes |= "Append".equals( x.getStepID() ) || ctx.previos( x ).size() > 1 && !"JoinRows".equals( x
            .getStepID() ) && !"MergeJoin".equals( x.getStepID() );
        }
        if ( !( contieneTipo( arriba, ENTRADAS_ARCHIVO ) || variasFuentes ) || contieneTipo( arriba, DEDUPLICAN ) ) {
          continue;
        }
        out.add( hallazgo( ctx, Sev.INFO, s, "keys", "dedupe_before_load", "no_dedupe_before_load.msg", "keys",
          unir( clavesDe( ctx, s ) ) ) );
      }
    }
  }

  /** Sequence con contador como clave de una dimensión: vuelve a 1 en cada corrida. */
  static final class ContadorComoClave extends ReglaRobusta {

    ContadorComoClave() {
      super( "counter_as_surrogate_key", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      final Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !"Sequence".equals( s.getStepID() ) ) {
          continue;
        }
        Element x = ctx.xml( s );
        if ( si( Nodos.txt( x, "use_database" ) ) ) {
          continue; // secuencia de la base: no vuelve a empezar
        }
        final String campo = Nodos.txt( x, "valuename" );
        final Ctx c = ctx;
        StepMeta carga = g.llegaA( s, CARGAS, new Corte() {
          @Override
          boolean corta( StepMeta y ) {
            return false;
          }

          @Override
          boolean destino( StepMeta y ) {
            return usaCampo( c, y, campo );
          }
        } );
        if ( carga != null ) {
          out.add( hallazgo( ctx, Sev.WARNING, s, "use_counter", "stable_surrogate_keys",
            "counter_as_surrogate_key.msg", "field", campo, "load", carga.getName() ) );
        }
      }
    }
  }

  /** Table output sin truncar a una tabla que no es de staging: volver a correr duplica o choca con la clave. */
  static final class SoloInsercion extends ReglaRobusta {

    SoloInsercion() {
      super( "insert_only_load", Sev.INFO );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !"TableOutput".equals( s.getStepID() ) || g.enRamaDeRechazo( s ) ) {
          continue;
        }
        Element x = ctx.xml( s );
        String tabla = Nodos.txt( x, "table" );
        if ( si( Nodos.txt( x, "truncate" ) ) || STAGING.matcher( tabla ).find() ) {
          continue;
        }
        out.add( hallazgo( ctx, Sev.INFO, s, "truncate", "idempotent_load", "insert_only_load.msg", "table", tabla ) );
      }
    }
  }

  /** Carga con 2+ claves sin agrupar antes: dos filas con la misma clave se pisan o chocan. */
  static final class HechoSinAgrupar extends ReglaRobusta {

    HechoSinAgrupar() {
      super( "fact_not_grouped", Sev.INFO );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !"InsertUpdate".equals( s.getStepID() ) && !"Update".equals( s.getStepID() ) || g.enRamaDeRechazo( s ) ) {
          continue;
        }
        List<String> claves = clavesDe( ctx, s );
        if ( claves.size() < 2 || contieneTipo( g.arriba( s ), AGRUPAN ) ) {
          continue;
        }
        out.add( hallazgo( ctx, Sev.INFO, s, "keys", "aggregate_to_grain", "fact_not_grouped.msg", "keys", unir(
          claves ) ) );
      }
    }
  }

  /** Búsqueda en una tabla que la misma transformación escribe, sin Block until steps finish: puede leer antes. */
  static final class BusquedaAntesDeCargar extends ReglaRobusta {

    BusquedaAntesDeCargar() {
      super( "lookup_before_load_finishes", Sev.WARNING );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      Grafo g = new Grafo( ctx );
      List<StepMeta> escritores = new ArrayList<StepMeta>();
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( CARGAS.contains( s.getStepID() ) && !tabla( ctx, s ).isEmpty() ) {
          escritores.add( s );
        }
      }
      if ( escritores.isEmpty() ) {
        return;
      }
      for ( StepMeta s : ctx.trans().getSteps() ) {
        String id = s.getStepID();
        boolean lector = "DBLookup".equals( id ) || "DBJoin".equals( id )
          || "TableInput".equals( id ) && !g.entran.get( s ).isEmpty();
        if ( !lector ) {
          continue;
        }
        Element x = ctx.xml( s );
        String conexion = Nodos.txt( x, "connection" );
        String leida = "DBLookup".equals( id ) ? Nodos.txt( Nodos.hijo( x, "lookup" ), "table" ) : Nodos.txt( x, "sql" );
        Set<StepMeta> arriba = g.arriba( s );
        Set<StepMeta> abajo = g.abajo( s, true );
        for ( StepMeta w : escritores ) {
          if ( w == s || abajo.contains( w ) || !conexion.equalsIgnoreCase( Nodos.txt( ctx.xml( w ), "connection" ) ) ) {
            continue; // el escritor depende del lector: leer antes de escribir es lo que se quiere (claves estables)
          }
          String t = tabla( ctx, w );
          boolean lee = "DBLookup".equals( id ) ? t.equalsIgnoreCase( leida.trim() )
            : Pattern.compile( "(?i)\\b" + Pattern.quote( t ) + "\\b" ).matcher( leida ).find();
          if ( lee && !esperaA( ctx, arriba, w ) ) {
            out.add( hallazgo( ctx, Sev.WARNING, s, "table", "wait_for_loads", "lookup_before_load_finishes.msg",
              "table", t, "writer", w.getName() ) );
            break;
          }
        }
      }
    }

    private static boolean esperaA( Ctx ctx, Set<StepMeta> arriba, StepMeta escritor ) throws Exception {
      for ( StepMeta b : arriba ) {
        if ( "BlockUntilStepsFinish".equals( b.getStepID() ) ) {
          for ( Element st : Nodos.hijos( Nodos.hijo( ctx.xml( b ), "steps" ), "step" ) ) {
            if ( escritor.getName().equalsIgnoreCase( Nodos.txt( st, "name" ) ) ) {
              return true;
            }
          }
        }
      }
      return false;
    }
  }

  /** Entrada de archivo sin parámetro ni variable en el nombre: no se puede probar con otro archivo. */
  static final class EntradaFija extends ReglaRobusta {

    EntradaFija() {
      super( "fixed_input_file", Sev.INFO );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !ENTRADAS_ARCHIVO.contains( s.getStepID() ) ) {
          continue;
        }
        Element x = ctx.xml( s );
        if ( si( Nodos.txt( x, "accept_filenames" ) ) || !ctx.previos( s ).isEmpty() ) {
          continue; // el nombre llega de un step anterior: ya se puede cambiar sin tocar el ETL
        }
        List<String> rutas = new ArrayList<String>();
        if ( !Nodos.txt( x, "filename" ).isEmpty() ) {
          rutas.add( Nodos.txt( x, "filename" ) );
        }
        rutas.addAll( Nodos.nombres( x, "file", "name", null ) );
        if ( rutas.isEmpty() ) {
          continue;
        }
        boolean fija = true;
        for ( String r : rutas ) {
          fija &= !conVariablePropia( r );
        }
        if ( fija ) {
          out.add( hallazgo( ctx, Sev.INFO, s, "filename", "input_file_parameter", "fixed_input_file.msg", "file",
            rutas.get( 0 ) ) );
        }
      }
    }
  }

  // ================================================================ utilidades

  /** pentaho-live-robust/drops del step (duplicates, filter, aggregate, sample), o null. */
  public static String declaracion( StepMeta s ) {
    try {
      String v = s.getAttribute( GRUPO, DROPS );
      return v == null || v.trim().isEmpty() ? null : v.trim();
    } catch ( RuntimeException e ) {
      return null;
    }
  }

  static boolean esSqlite( Ctx ctx, StepMeta s ) throws Exception {
    DatabaseMeta dm = ctx.trans().findDatabase( Nodos.txt( ctx.xml( s ), "connection" ) );
    return dm != null && dm.getPluginId() != null && dm.getPluginId().toUpperCase( Locale.ROOT ).contains( "SQLITE" );
  }

  /** La tabla que escribe una carga (Table output: table; Insert/Update y demás: lookup/table). */
  static String tabla( Ctx ctx, StepMeta s ) throws Exception {
    Element x = ctx.xml( s );
    String t = Nodos.txt( x, "table" );
    if ( t.isEmpty() ) {
      t = Nodos.txt( Nodos.hijo( x, "lookup" ), "table" );
    }
    if ( t.isEmpty() ) {
      t = Nodos.txt( Nodos.hijo( x, "fields" ), "table" ); // Dimension lookup/update guarda la tabla ahí
    }
    return t;
  }

  /** Los campos del flujo que usa como clave una carga o búsqueda (key/name). */
  static List<String> clavesDe( Ctx ctx, StepMeta s ) throws Exception {
    Element x = ctx.xml( s );
    List<String> r = new ArrayList<String>();
    Element lk = Nodos.hijo( x, "lookup" );
    for ( Element k : Nodos.hijos( lk == null ? Nodos.hijo( x, "fields" ) : lk, "key" ) ) {
      String n = Nodos.txt( k, "name" );
      if ( !n.isEmpty() ) {
        r.add( n );
      }
    }
    if ( r.isEmpty() ) {
      for ( Element k : Nodos.hijos( Nodos.hijo( x, "fields" ), "key" ) ) {
        String n = Nodos.txt( k, "name" );
        if ( !n.isEmpty() ) {
          r.add( n );
        }
      }
    }
    return r;
  }

  /** Si la carga usa ese campo del flujo (lo nombra en su XML; Table output sin campos elegidos usa todos). */
  static boolean usaCampo( Ctx ctx, StepMeta carga, String campo ) {
    try {
      Element x = ctx.xml( carga );
      if ( "TableOutput".equals( carga.getStepID() ) && !si( Nodos.txt( x, "specify_fields" ) ) ) {
        return true;
      }
      for ( Element[] hp : Nodos.hojas( x ) ) {
        if ( TAGS_CAMPO.contains( hp[ 0 ].getTagName() ) && campo.equalsIgnoreCase( hp[ 0 ].getTextContent()
          .trim() ) ) {
          return true;
        }
      }
      return false;
    } catch ( Exception e ) {
      return true;
    }
  }

  /**
   * Filter rows o Java filter que mira si hay nulos (IS NULL / IS NOT NULL; con campo, sobre ese campo). Una rama
   * falsa ya no se recorre: lo que sigue por la verdadera está controlado.
   */
  static boolean controlaNulo( Ctx ctx, StepMeta s, String campo ) {
    try {
      if ( "FilterRows".equals( s.getStepID() ) ) {
        for ( Element[] hp : Nodos.hojas( Nodos.hijo( ctx.xml( s ), "compare" ) ) ) {
          if ( !"function".equals( hp[ 0 ].getTagName() ) ) {
            continue;
          }
          String f = hp[ 0 ].getTextContent().trim().toUpperCase( Locale.ROOT );
          if ( f.equals( "IS NOT NULL" ) || f.equals( "IS NULL" ) || f.equals( "IS NOT EMPTY" ) ) {
            if ( campo == null || campo.equalsIgnoreCase( Nodos.txt( hp[ 1 ], "leftvalue" ) ) ) {
              return true;
            }
          }
        }
        return false;
      }
      if ( "JavaFilter".equals( s.getStepID() ) ) {
        String c = Nodos.txt( ctx.xml( s ), "condition" );
        return c.contains( "null" ) && ( campo == null || c.contains( campo ) );
      }
      if ( campo != null && CLASIFICAN.contains( s.getStepID() ) ) {
        // un motivo calculado a partir de la búsqueda («sk_tienda == null ? "TIENDA_DESCONOCIDA" : ...») que un filtro
        // posterior separa: el huérfano ya está controlado
        String q = Pattern.quote( campo );
        return Pattern.compile( "(?i)(\\b" + q + "\\b\\s*[!=]=\\s*null|null\\s*[!=]=\\s*\\b" + q + "\\b|(ISNULL|ISBLANK)"
          + "\\s*\\(\\s*\\[?" + q + "\\b)" ).matcher( textoDe( s ) ).find();
      }
      return false;
    } catch ( Exception e ) {
      return false;
    }
  }

  private static String textoDe( StepMeta s ) {
    try {
      return s.getXML();
    } catch ( Exception e ) {
      return "";
    }
  }

  /** Metadatos de Select values que cambian el tipo (a algo que no es String). */
  static List<String> conversiones( Element x ) {
    List<String> r = new ArrayList<String>();
    for ( Element m : Nodos.hijos( Nodos.hijo( x, "fields" ), "meta" ) ) {
      String t = Nodos.txt( m, "type" );
      if ( !t.isEmpty() && !"String".equalsIgnoreCase( t ) && !"None".equalsIgnoreCase( t ) && !"-".equals( t ) ) {
        r.add( Nodos.txt( m, "name" ) );
      }
    }
    return r;
  }

  /** Campos de una entrada de archivo que no se leen como String. */
  public static List<String> camposTipados( Element x ) {
    List<String> r = new ArrayList<String>();
    for ( Element f : Nodos.hijos( Nodos.hijo( x, "fields" ), "field" ) ) {
      String t = Nodos.txt( f, "type" );
      if ( !t.isEmpty() && !"String".equalsIgnoreCase( t ) && !"None".equalsIgnoreCase( t ) ) {
        r.add( Nodos.txt( f, "name" ) );
      }
    }
    return r;
  }

  static boolean joinInterno( String sql ) {
    Matcher m = JOIN.matcher( sql );
    while ( m.find() ) {
      String antes = m.group( 1 ) == null ? "" : m.group( 1 ).toUpperCase( Locale.ROOT );
      if ( antes.isEmpty() || antes.equals( "INNER" ) || !Arrays.asList( "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
        "NATURAL" ).contains( antes ) ) {
        return true;
      }
    }
    return false;
  }

  /** Usa una variable o parámetro propio (no solo las Internal.*, que dicen dónde está el archivo). */
  static boolean conVariablePropia( String ruta ) {
    Matcher m = VARIABLE.matcher( ruta );
    while ( m.find() ) {
      String v = m.group( 1 ) != null ? m.group( 1 ) : m.group( 2 );
      if ( !v.startsWith( "Internal." ) ) {
        return true;
      }
    }
    return false;
  }

  public static boolean tieneMotivo( List<String> campos ) {
    for ( String c : campos ) {
      String x = c.toLowerCase( Locale.ROOT );
      if ( x.contains( "motivo" ) || x.contains( "reason" ) || x.contains( "error" ) || x.contains( "causa" )
        || x.contains( "razon" ) || x.contains( "razón" ) ) {
        return true;
      }
    }
    return false;
  }

  /**
   * La rama de rechazos pone su motivo aunque el hop de error no lo traiga: todos los escritores que alcanza declaran
   * sus campos y uno es un motivo (un Add constants con motivo_rechazo = DUPLICADO antes del archivo, por ejemplo).
   */
  static boolean motivoEnLaRama( Ctx ctx, Grafo g, StepMeta destino ) throws Exception {
    if ( destino == null ) {
      return false;
    }
    List<StepMeta> ws = escritoresDesde( g, destino );
    for ( StepMeta w : ws ) {
      List<String> campos = Nodos.nombres( ctx.xml( w ), "fields", "field", "name" );
      if ( campos.isEmpty() || !tieneMotivo( campos ) ) {
        return false;
      }
    }
    return !ws.isEmpty();
  }

  /** Los escritores que se alcanzan desde el inicio de una rama (él incluido). */
  static List<StepMeta> escritoresDesde( Grafo g, StepMeta inicio ) {
    List<StepMeta> r = new ArrayList<StepMeta>();
    Set<StepMeta> todos = g.abajo( inicio, true );
    todos.add( inicio );
    for ( StepMeta x : todos ) {
      if ( ESCRITORES.contains( x.getStepID() ) && !"WriteToLog".equals( x.getStepID() ) ) {
        r.add( x );
      }
    }
    return r;
  }

  static boolean esNumerico( String tipo ) {
    return "Number".equalsIgnoreCase( tipo ) || "Integer".equalsIgnoreCase( tipo ) || "BigNumber".equalsIgnoreCase(
      tipo );
  }

  static boolean contieneTipo( Set<StepMeta> steps, Set<String> tipos ) {
    for ( StepMeta s : steps ) {
      if ( tipos.contains( s.getStepID() ) ) {
        return true;
      }
    }
    return false;
  }

  static boolean si( String v ) {
    String x = v == null ? "" : v.trim().toLowerCase( Locale.ROOT );
    return x.equals( "y" ) || x.equals( "yes" ) || x.equals( "true" );
  }

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  static String unir( List<String> l ) {
    StringBuilder b = new StringBuilder();
    for ( String x : new LinkedHashSet<String>( l ) ) {
      b.append( b.length() == 0 ? "" : ", " ).append( x );
    }
    return b.toString();
  }

  private static Set<String> conjunto( String... ids ) {
    return new HashSet<String>( Arrays.asList( ids ) );
  }

  static Set<StepMeta> conjuntoIdentidad() {
    return java.util.Collections.newSetFromMap( new IdentityHashMap<StepMeta, Boolean>() );
  }
}
