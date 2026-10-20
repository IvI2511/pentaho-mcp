package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;
import org.pentaho.di.trans.steps.textfileoutput.TextFileField;
import org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Seccion dictionary (diseno §2.3): una tabla por cada salida final (los steps de los que no sale
 * ningun hop habilitado) con nombre, tipo, largo, precision, mascara, origen y comentario de cada
 * campo, y un anexo con los campos de los demas steps. Usa ctx.salida (memo y timeout compartidos con
 * linaje y destino). Si algun step no da sus campos (una base que no responde) queda "parcial".
 *
 * @dueño WP2
 */
final class SecDiccionario implements Recolector {

  /** Con mas steps que esto el anexo se omite (el informe se haria enorme). */
  static final int MAX_ANEXO = 40;

  @Override
  public String id() {
    return SeccionId.DICCIONARIO;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    if ( c != null && ( c.esJob() || !( c.copia instanceof TransMeta ) ) ) {
      return Rotulos.de( InformeServicio.AREA, idioma ).s( "no_aplica.solo_trans" );
    }
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos r = ctx.rotulos( InformeServicio.AREA );
    TransMeta tm = (TransMeta) ctx.captura().copia;
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), ctx.idioma() ) );
    ObjectNode datos = Txt.obj();
    datos.put( "section", id() );
    List<StepMeta> orden = ctx.orden();
    if ( orden.isEmpty() ) {
      s.resumen = r.s( "resumen.vacio" );
      s.datos = datos;
      return s;
    }
    List<StepMeta> finales = finales( tm, orden );
    List<String> fallidos = new ArrayList<String>();
    int camposFinales = 0;
    int salidasOk = 0;
    String primera = null;
    ArrayNode dsal = datos.putArray( "outputs" );
    int n = 0;
    for ( StepMeta sm : finales ) {
      if ( ctx.cancelado() ) {
        break;
      }
      n++;
      RowMetaInterface rm = campos( ctx, sm, r, s, fallidos );
      ObjectNode o = dsal.addObject();
      o.put( "step", sm.getName() );
      if ( rm == null ) {
        o.put( "computed", false );
        continue;
      }
      salidasOk++;
      if ( primera == null ) {
        primera = sm.getName();
      }
      o.set( "fields", camposJson( rm ) );
      // lo que el step escribe de verdad (orden, formato, largo) si lo declara; si no, el flujo que le llega
      Tabla t = declarados( r, sm, rm, o );
      if ( t != null ) {
        t.pie = r.f( "dic.pie.declarados", sm.getName() );
      } else {
        t = tabla( r, rm, true );
        t.pie = r.s( "dic.pie" );
      }
      camposFinales += t.filas.size();
      t.id = "dictionary-salida-" + n;
      t.titulo = r.f( "dic.salida", sm.getName() );
      if ( rm.size() == 0 && t.filas.isEmpty() ) {
        Parrafo vacia = new Parrafo( r.f( "dic.sin_campos", sm.getName() ) );
        vacia.id = "dictionary-vacia-" + n;
        s.con( vacia );
      } else {
        s.con( t );
      }
    }

    List<StepMeta> otros = new ArrayList<StepMeta>();
    for ( StepMeta sm : orden ) {
      if ( !finales.contains( sm ) ) {
        otros.add( sm );
      }
    }
    if ( !otros.isEmpty() && !ctx.cancelado() ) {
      if ( orden.size() > MAX_ANEXO ) {
        Aviso a = new Aviso( Aviso.INFO, r.f( "dic.anexo.omitido", orden.size(), MAX_ANEXO ) );
        a.id = "dictionary-anexo-omitido";
        s.con( a );
      } else {
        Parrafo titulo = new Parrafo( "**" + r.s( "dic.anexo" ) + "**" );
        titulo.id = "dictionary-anexo";
        List<Tabla> tablas = new ArrayList<Tabla>();
        ArrayNode dst = datos.putArray( "steps" );
        int k = 0;
        for ( StepMeta sm : otros ) {
          if ( ctx.cancelado() ) {
            break;
          }
          k++;
          RowMetaInterface rm = campos( ctx, sm, r, null, fallidos );
          ObjectNode o = dst.addObject();
          o.put( "step", sm.getName() );
          if ( rm == null ) {
            o.put( "computed", false );
            continue;
          }
          o.set( "fields", camposJson( rm ) );
          if ( rm.size() > 0 ) {
            Tabla t = tabla( r, rm, false );
            t.id = "dictionary-anexo-" + k;
            t.titulo = sm.getName();
            tablas.add( t );
          }
        }
        if ( !tablas.isEmpty() ) {
          s.con( titulo );
          for ( Tabla t : tablas ) {
            s.con( t );
          }
        }
      }
    }

    if ( !fallidos.isEmpty() ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.f( fallidos.size() == 1 ? "dic.motivo.1" : "dic.motivo.n", fallidos.size(), unir( r, fallidos ) );
    }
    if ( ctx.cancelado() ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.s( "motivo.cancelado" );
    }
    if ( salidasOk == 0 ) {
      s.resumen = r.s( "dic.resumen.vacio" );
    } else if ( finales.size() == 1 ) {
      s.resumen = r.f( "dic.resumen.1", 1, primera, Txt.n( r, "n.campo", camposFinales ) );
    } else {
      s.resumen = r.f( "dic.resumen.n", finales.size(), Txt.n( r, "n.campo", camposFinales ) );
    }
    datos.put( "final_outputs", finales.size() );
    datos.put( "final_fields", camposFinales );
    ArrayNode df = datos.putArray( "failed_steps" );
    for ( String f : fallidos ) {
      df.add( f );
    }
    s.datos = datos;
    return s;
  }

  /** Steps de los que no sale ningun hop habilitado (salidas finales), en el orden del flujo. */
  static List<StepMeta> finales( TransMeta tm, List<StepMeta> orden ) {
    List<StepMeta> out = new ArrayList<StepMeta>();
    for ( StepMeta sm : orden ) {
      boolean sale = false;
      for ( int i = 0; i < tm.nrTransHops() && !sale; i++ ) {
        TransHopMeta h = tm.getTransHop( i );
        sale = h.isEnabled() && h.getFromStep() == sm && h.getToStep() != null;
      }
      if ( !sale ) {
        out.add( sm );
      }
    }
    return out;
  }

  /** Campos de salida del step, o null si fallo (con el aviso en la seccion, si se pasa). */
  private static RowMetaInterface campos( Contexto ctx, StepMeta sm, Rotulos r, Seccion s, List<String> fallidos ) {
    try {
      return ctx.salida( sm );
    } catch ( KettleStepException e ) {
      fallidos.add( sm.getName() );
      if ( s != null ) {
        Aviso a = new Aviso( Aviso.WARN, r.f( "dic.error", sm.getName(), Txt.recortar( RunMotor.mensaje( e ),
          300 ) ) );
        a.id = "dictionary-error-" + fallidos.size();
        s.con( a );
      }
      return null;
    } catch ( RuntimeException e ) {
      fallidos.add( sm.getName() );
      return null;
    }
  }

  private static Tabla tabla( Rotulos r, RowMetaInterface rm, boolean completa ) {
    Tabla t = completa
      ? new Tabla( r.s( "dic.col.campo" ), r.s( "dic.col.tipo" ), r.s( "dic.col.largo" ), r.s( "dic.col.precision" ),
        r.s( "dic.col.mascara" ), r.s( "dic.col.origen" ), r.s( "dic.col.comentario" ) )
      : new Tabla( r.s( "dic.col.campo" ), r.s( "dic.col.tipo" ), r.s( "dic.col.largo" ), r.s( "dic.col.precision" ),
        r.s( "dic.col.origen" ) );
    t.alineacion = completa ? new ArrayList<String>( Arrays.asList( "l", "l", "r", "r", "l", "l", "l" ) )
      : new ArrayList<String>( Arrays.asList( "l", "l", "r", "r", "l" ) );
    for ( ValueMetaInterface v : rm == null ? new ArrayList<ValueMetaInterface>() : rm.getValueMetaList() ) {
      String largo = v.getLength() >= 0 ? String.valueOf( v.getLength() ) : "";
      String prec = v.getPrecision() >= 0 ? String.valueOf( v.getPrecision() ) : "";
      if ( completa ) {
        t.fila( v.getName(), v.getTypeDesc(), largo, prec, nulo( v.getConversionMask() ), nulo( v.getOrigin() ), nulo(
          v.getComments() ) );
      } else {
        t.fila( v.getName(), v.getTypeDesc(), largo, prec, nulo( v.getOrigin() ) );
      }
    }
    return t;
  }

  private static ArrayNode camposJson( RowMetaInterface rm ) {
    ArrayNode a = Txt.obj().arrayNode();
    for ( ValueMetaInterface v : rm.getValueMetaList() ) {
      ObjectNode c = a.addObject();
      c.put( "name", v.getName() );
      c.put( "type", v.getTypeDesc() );
      if ( v.getLength() >= 0 ) {
        c.put( "length", v.getLength() );
      }
      if ( v.getPrecision() >= 0 ) {
        c.put( "precision", v.getPrecision() );
      }
      Txt.poner( c, "format", v.getConversionMask() );
      Txt.poner( c, "origin", v.getOrigin() );
      Txt.poner( c, "comment", v.getComments() );
    }
    return a;
  }

  private static String nulo( String s ) {
    return s == null ? "" : s;
  }

  /** «a», «b» con las comillas del idioma (“a”, “b” en ingles y portugues). */
  private static String unir( Rotulos r, List<String> l ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      sb.append( sb.length() == 0 ? "" : ", " ).append( r.f( "cita", s ) );
    }
    return sb.toString();
  }

  /**
   * La tabla de lo que el step final escribe, si declara sus campos: Text file output con campos (nombre,
   * orden, formato, largo y precision como van al archivo) o Table output con «especificar campos»
   * (columna de la tabla y el campo que la llena). El tipo, el origen y el comentario salen del flujo que
   * llega al step. null si el step no declara campos (escribe el flujo tal cual): entonces va el flujo.
   * Deja en {@code o} los campos escritos ("written_fields").
   */
  static Tabla declarados( Rotulos r, StepMeta sm, RowMetaInterface flujo, ObjectNode o ) {
    try {
      Object m = sm.getStepMetaInterface();
      if ( m instanceof TextFileOutputMeta ) {
        TextFileField[] fs = ( (TextFileOutputMeta) m ).getOutputFields();
        if ( fs == null || fs.length == 0 ) {
          return null;
        }
        Tabla t = tabla( r, null, true );
        ArrayNode dw = o.putArray( "written_fields" );
        for ( TextFileField f : fs ) {
          if ( f == null || f.getName() == null ) {
            continue;
          }
          ValueMetaInterface v = flujo == null ? null : flujo.searchValueMeta( f.getName() );
          String tipo = f.getType() > 0 ? f.getTypeDesc() : v != null ? v.getTypeDesc() : "";
          int largo = f.getLength() >= 0 ? f.getLength() : v != null ? v.getLength() : -1;
          int prec = f.getPrecision() >= 0 ? f.getPrecision() : v != null ? v.getPrecision() : -1;
          String formato = vacio( f.getFormat() ) ? v == null ? "" : nulo( v.getConversionMask() ) : f.getFormat();
          String comentario = v == null ? r.s( "dic.no_llega" ) : nulo( v.getComments() );
          t.fila( f.getName(), tipo, largo >= 0 ? String.valueOf( largo ) : "", prec >= 0 ? String.valueOf( prec ) : "",
            formato, v == null ? "" : nulo( v.getOrigin() ), comentario );
          ObjectNode x = dw.addObject();
          x.put( "name", f.getName() );
          Txt.poner( x, "type", tipo );
          if ( largo >= 0 ) {
            x.put( "length", largo );
          }
          if ( prec >= 0 ) {
            x.put( "precision", prec );
          }
          Txt.poner( x, "format", formato );
          if ( v == null ) {
            x.put( "in_stream", false );
          }
        }
        return t.filas.isEmpty() ? null : t;
      }
      if ( m instanceof TableOutputMeta && ( (TableOutputMeta) m ).specifyFields() ) {
        TableOutputMeta to = (TableOutputMeta) m;
        String[] cols = to.getFieldDatabase();
        String[] campos = to.getFieldStream();
        if ( cols == null || cols.length == 0 ) {
          return null;
        }
        Tabla t = tabla( r, null, true );
        ArrayNode dw = o.putArray( "written_fields" );
        for ( int i = 0; i < cols.length; i++ ) {
          String col = cols[ i ];
          String campo = campos != null && i < campos.length ? campos[ i ] : null;
          if ( col == null ) {
            continue;
          }
          ValueMetaInterface v = flujo == null || campo == null ? null : flujo.searchValueMeta( campo );
          String comentario = v == null ? r.s( "dic.no_llega" ) : campo.equals( col ) ? nulo( v.getComments() )
            : r.f( "dic.desde_campo", campo );
          t.fila( col, v == null ? "" : v.getTypeDesc(), v != null && v.getLength() >= 0 ? String.valueOf( v
            .getLength() ) : "", v != null && v.getPrecision() >= 0 ? String.valueOf( v.getPrecision() ) : "", v == null
            ? "" : nulo( v.getConversionMask() ), v == null ? "" : nulo( v.getOrigin() ), comentario );
          ObjectNode x = dw.addObject();
          x.put( "name", col );
          Txt.poner( x, "stream_field", campo );
          if ( v != null ) {
            x.put( "type", v.getTypeDesc() );
          } else {
            x.put( "in_stream", false );
          }
        }
        return t.filas.isEmpty() ? null : t;
      }
    } catch ( Throwable e ) {
      // configuracion rara: el flujo
    }
    return null;
  }

  private static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }
}
