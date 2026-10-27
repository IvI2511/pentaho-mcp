package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

/**
 * Traduce una transformación entera: recorre los steps en orden de los hops (topológico, desempate por posición en el
 * lienzo) y le pide a cada traductor del {@link Registro} su bloque. Lo que no tiene traductor, o falla al traducir,
 * queda como un bloque MANUAL que pasa las filas tal cual y deja un TODO con la configuración del step.
 */
public final class TraductorTrans {

  private TraductorTrans() {
  }

  public static Programa traducir( TransMeta t, Destino d, String idioma ) {
    CtxTrad ctx = new CtxTrad( t, d, idioma );
    Programa p = new Programa( t.getName(), t.getFilename(), ctx );
    for ( StepMeta s : orden( t, ctx ) ) {
      p.bloques.add( paso( s, ctx ) );
    }
    return p;
  }

  /** Los steps en orden de ejecución lógica: cada uno después de todos los que lo alimentan. */
  public static List<StepMeta> orden( TransMeta t, CtxTrad ctx ) {
    List<StepMeta> todos = new ArrayList<>( t.getSteps() );
    Comparator<StepMeta> lienzo = Comparator.comparingInt( (StepMeta s) -> s.getLocation() == null ? 0
      : s.getLocation().x ).thenComparingInt( s -> s.getLocation() == null ? 0 : s.getLocation().y );
    todos.sort( lienzo );
    Map<StepMeta, Integer> faltan = new HashMap<>();
    for ( StepMeta s : todos ) {
      faltan.put( s, ctx.anteriores( s ).size() );
    }
    List<StepMeta> r = new ArrayList<>();
    List<StepMeta> listos = new ArrayList<>();
    for ( StepMeta s : todos ) {
      if ( faltan.get( s ) == 0 ) {
        listos.add( s );
      }
    }
    while ( !listos.isEmpty() ) {
      listos.sort( lienzo );
      StepMeta s = listos.remove( 0 );
      r.add( s );
      for ( StepMeta n : ctx.siguientes( s ) ) {
        Integer f = faltan.get( n );
        if ( f != null ) {
          faltan.put( n, f - 1 );
          if ( f - 1 == 0 ) {
            listos.add( n );
          }
        }
      }
    }
    for ( StepMeta s : todos ) { // un ciclo (no debería haber): al final, en orden de lienzo
      if ( !r.contains( s ) ) {
        r.add( s );
      }
    }
    return r;
  }

  static Bloque paso( StepMeta s, CtxTrad ctx ) {
    String id = s.getStepID();
    TraductorStep tr = Registro.buscar( id );
    Bloque b;
    if ( tr == null ) {
      b = sinTraduccion( s, ctx, ctx.t( "El step «" + id + "» todavía no tiene traducción: complétalo a mano.",
        "Step type '" + id + "' has no translation yet: complete it by hand.",
        "O step «" + id + "» ainda não tem tradução: complete-o à mão." ) );
    } else {
      try {
        b = tr.traducir( s, ctx );
      } catch ( Exception | LinkageError e ) {
        b = sinTraduccion( s, ctx, ctx.t( "No se pudo traducir: ", "Could not translate: ", "Não foi possível traduzir: " )
          + e );
      }
    }
    errorSinTraducir( s, ctx, b );
    reparto( s, ctx, b );
    return b;
  }

  /** Bloque MANUAL: pasa las filas tal cual (o un DataFrame vacío) y deja la configuración comentada. */
  public static Bloque sinTraduccion( StepMeta s, CtxTrad ctx, String motivo ) {
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    b.manual( motivo );
    String in = ctx.entradaUnida( s, b );
    String out = ctx.df( s );
    b.l( "# TODO: " + ctx.t( "configuración del step en PDI:", "step settings in PDI:", "configuração do step no PDI:" ) );
    try {
      String x = s.getXML();
      String[] lineas = x.split( "\\r?\\n" );
      int n = 0;
      boolean saltar = false;
      for ( String l : lineas ) {
        String tl = l.trim();
        if ( tl.startsWith( "<partitioning>" ) || tl.startsWith( "<remotesteps>" ) || tl.startsWith( "<GUI>" ) ) {
          saltar = true;
        }
        if ( saltar ) {
          saltar = !( tl.startsWith( "</partitioning>" ) || tl.startsWith( "</remotesteps>" ) || tl.startsWith(
            "</GUI>" ) );
          continue;
        }
        if ( tl.isEmpty() || l.contains( "<password>" ) || tl.matches( "</?step>|<(name|type|distribute|copies|"
          + "custom_distribution|cluster_schema|description)\\b.*|<row-meta>.*" ) ) {
          continue;
        }
        if ( ++n > 40 ) {
          b.l( "#   ..." );
          break;
        }
        b.l( "#   " + l.replaceAll( "\\s+$", "" ) );
      }
    } catch ( Exception e ) {
      // sin XML
    }
    if ( in != null ) {
      b.l( out + " = " + in + ( ctx.pandas() ? ".copy()" : "" ) );
    } else if ( ctx.pandas() ) {
      b.l( out + " = pd.DataFrame()" );
    } else {
      b.l( out + " = spark.createDataFrame([], T.StructType([]))" );
    }
    b.vista = out;
    return b;
  }

  /** El step manda errores por un hop y su traductor no los separó: el hop recibe un DataFrame vacío. */
  private static void errorSinTraducir( StepMeta s, CtxTrad ctx, Bloque b ) {
    if ( !CtxTrad.tieneError( s ) || ctx.salidaError( s ) != null ) {
      return;
    }
    String v = ctx.dfError( s );
    ctx.ponerSalidaError( s, v );
    b.l( v + " = " + ctx.df( s ) + ( ctx.pandas() ? ".iloc[0:0].copy()" : ".limit(0)" ) + "  # "
      + ctx.t( "filas con error: en Python un error corta el programa", "error rows: in Python an error stops the "
        + "program", "linhas com erro: em Python um erro interrompe o programa" ) );
    b.aproximado( ctx.t( "El manejo de errores de «" + s.getName() + "» no se traduce fila a fila: el hop de error "
      + "recibe un DataFrame vacío.", "Error handling of '" + s.getName() + "' is not translated row by row: the error "
        + "hop gets an empty DataFrame.", "O tratamento de erros de «" + s.getName() + "» não é traduzido linha a "
          + "linha: o hop de erro recebe um DataFrame vazio." ) );
  }

  /** Un step que distribuye (no copia) a varios destinos: reparto por turnos como PDI (round robin). */
  private static void reparto( StepMeta s, CtxTrad ctx, Bloque b ) {
    List<StepMeta> sig = ctx.siguientesPrincipales( s );
    if ( !s.isDistributes() || sig.size() < 2 ) {
      return;
    }
    boolean registrado = false;
    for ( StepMeta d : sig ) {
      if ( !ctx.salidaHacia( s, d ).equals( ctx.df( s ) ) ) {
        registrado = true;
      }
    }
    if ( registrado ) {
      return; // el traductor ya decidió a quién va cada fila (Filter rows, Switch / Case...)
    }
    int n = sig.size();
    for ( int i = 0; i < n; i++ ) {
      String v = ctx.nuevo( ctx.df( s ) + "_" + CtxTrad.ident( sig.get( i ).getName() ) );
      if ( ctx.pandas() ) {
        b.l( v + " = " + ctx.df( s ) + ".iloc[" + i + "::" + n + "].reset_index(drop=True)" );
      } else {
        b.l( v + " = " + ctx.df( s ) + ".filter((F.monotonically_increasing_id() % " + n + ") == " + i + ")" );
      }
      ctx.ponerSalida( s, sig.get( i ), v );
    }
    b.aproximado( ctx.t( "«" + s.getName() + "» distribuye las filas por turnos entre " + n + " destinos (no las copia).",
      "'" + s.getName() + "' distributes rows round robin over " + n + " targets (it does not copy them).",
      "«" + s.getName() + "» distribui as linhas em rodízio entre " + n + " destinos (não as copia)." ) );
  }
}
