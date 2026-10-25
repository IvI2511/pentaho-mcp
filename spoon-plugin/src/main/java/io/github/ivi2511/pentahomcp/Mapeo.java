package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.insertupdate.InsertUpdateMeta;
import org.pentaho.di.trans.steps.streamlookup.StreamLookupMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;
import org.pentaho.di.trans.steps.textfileoutput.TextFileField;
import org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * La hoja de mapeo origen-destino (D3, Source-to-Target Mapping) de una transformación que ya existe: una fila por
 * campo destino con origen, destino, tipo, regla, valor por defecto, validación, estado, responsable y notas. Sale del
 * linaje propio ({@link LinajePropio}: solo metadatos de Kettle, sin ejecutar nada).
 * <p>
 * Convención de las columnas Origen y Destino: {@code recurso.campo} (el último punto separa el campo), donde el
 * recurso es la tabla (de la consulta de un Table input, o de un Table output / Insert-Update) o el archivo; varios
 * orígenes van separados por «; ». Es lo que lee {@link MapeoImportar} de vuelta.
 */
final class Mapeo {

  /** Las columnas de la hoja, en orden (claves de texto map.col.*). */
  static final List<String> COLUMNAS = Arrays.asList( "source", "target", "type", "rule", "default", "validation",
    "status", "owner", "notes" );

  /** Steps de validación: aparecen en la columna Validación si el campo pasa por ellos. */
  static final Set<String> VALIDAN = new LinkedHashSet<String>( Arrays.asList( "FilterRows", "Validator",
    "JavaFilter", "SwitchCase", "DataValidator" ) );

  private static final Pattern FROM = Pattern.compile( "(?is)\\bfrom\\s+([\\w.\"\\[\\]`]+)" );

  private Mapeo() {
  }

  /** Una fila de la hoja. */
  static final class Fila {
    String origen = "";
    String destino = "";
    String tipo = "";
    String regla = "";
    String defecto = "";
    String validacion = "";
    String estado = "";
    String responsable = "";
    String notas = "";
    /** El step destino y el campo del flujo que llega a él (no van en la hoja). */
    String paso;
    String campoFlujo;
    String tabla;
    String campo;

    String[] celdas() {
      return new String[] { origen, destino, tipo, regla, defecto, validacion, estado, responsable, notas };
    }
  }

  /** Las filas de los steps destino (o de los finales que escriben, si destinos es null o vacío). */
  static List<Fila> filas( TransMeta tm, Contexto ctx, List<StepMeta> destinos ) {
    Rotulos r = ctx.rotulos( PlanoInforme.AREA );
    Rotulos rl = ctx.rotulos( SecLinaje.AREA );
    LinajePropio lin = new LinajePropio( tm, ctx );
    List<StepMeta> ds = destinos == null || destinos.isEmpty() ? LinajePropio.destinosPorDefecto( tm, ctx.orden() )
      : destinos;
    List<Fila> out = new ArrayList<Fila>();
    for ( StepMeta d : ds ) {
      if ( ctx.cancelado() ) {
        break;
      }
      String tabla = recursoDestino( tm, d );
      List<String[]> columnas = columnasDestino( tm, d, lin );
      LinajePropio.Destino ld = lin.destino( d );
      RowMetaInterface entrada = lin.campos( d );
      if ( ld.motivo != null && columnas.isEmpty() ) {
        Fila f = new Fila();
        f.destino = tabla + ".?";
        f.paso = d.getName();
        f.tabla = tabla;
        f.notas = r.f( "map.sin_campos", ld.motivo );
        f.estado = r.s( "map.estado.borrador" );
        out.add( f );
        continue;
      }
      for ( String[] col : columnas ) {
        Fila f = new Fila();
        f.paso = d.getName();
        f.campoFlujo = col[ 1 ];
        f.tabla = tabla;
        f.campo = col[ 0 ];
        f.destino = tabla + "." + col[ 0 ];
        f.estado = r.s( "map.estado.borrador" );
        LinajePropio.Campo c = ld.campo( col[ 1 ] );
        ValueMetaInterface v = entrada == null ? null : entrada.searchValueMeta( col[ 1 ] );
        f.tipo = tipo( v );
        List<String> notas = new ArrayList<String>();
        if ( c == null ) {
          notas.add( r.f( "map.sin_linaje", col[ 1 ] ) );
        } else {
          f.origen = origenes( tm, c, notas, r );
          String ops = SecLinaje.operaciones( c, rl );
          f.regla = ops;
          f.defecto = defecto( tm, c, col[ 1 ] );
          f.validacion = validaciones( tm, c, r );
          if ( c.truncado ) {
            notas.add( r.s( "map.truncado" ) );
          }
        }
        if ( !col[ 0 ].equals( col[ 1 ] ) ) {
          notas.add( r.f( "map.renombrado", col[ 1 ], col[ 0 ] ) );
        }
        f.notas = String.join( " ", notas );
        out.add( f );
      }
    }
    return out;
  }

  /** El recurso destino de un step: la tabla (schema.tabla) o el archivo; si no, el nombre del step. */
  static String recursoDestino( TransMeta tm, StepMeta d ) {
    StepMetaInterface m = d.getStepMetaInterface();
    try {
      if ( m instanceof TableOutputMeta ) {
        return tabla( tm, ( (TableOutputMeta) m ).getSchemaName(), ( (TableOutputMeta) m ).getTableName(), d );
      }
      if ( m instanceof InsertUpdateMeta ) {
        return tabla( tm, ( (InsertUpdateMeta) m ).getSchemaName(), ( (InsertUpdateMeta) m ).getTableName(), d );
      }
      String xml = d.getXML();
      String t = etiqueta( xml, "table" );
      if ( t != null && !t.isEmpty() ) {
        return tabla( tm, etiqueta( xml, "schema" ), t, d );
      }
      int f = xml.indexOf( "<file>" );
      String nombre = f < 0 ? null : etiqueta( xml.substring( f ), "name" );
      if ( nombre == null || nombre.isEmpty() ) {
        nombre = etiqueta( xml, "filename" );
      }
      if ( nombre != null && !nombre.isEmpty() ) {
        String n = tm.environmentSubstitute( nombre ).replace( '\\', '/' );
        n = n.substring( n.lastIndexOf( '/' ) + 1 );
        String ext = etiqueta( xml.substring( Math.max( 0, f ) ), "extention" );
        if ( ext != null && !ext.isEmpty() && !n.toLowerCase( Locale.ROOT ).endsWith( "." + ext.toLowerCase(
          Locale.ROOT ) ) ) {
          n = n + "." + ext;
        }
        return n;
      }
    } catch ( Exception ignored ) {
      // sin recurso: el nombre del step
    }
    return d.getName();
  }

  private static String tabla( TransMeta tm, String esquema, String tabla, StepMeta d ) {
    String t = tabla == null ? "" : tm.environmentSubstitute( tabla ).trim();
    if ( t.isEmpty() ) {
      return d.getName();
    }
    String s = esquema == null ? "" : tm.environmentSubstitute( esquema ).trim();
    return s.isEmpty() ? t : s + "." + t;
  }

  /** {columna destino, campo del flujo} del step destino, en orden. */
  static List<String[]> columnasDestino( TransMeta tm, StepMeta d, LinajePropio lin ) {
    List<String[]> l = new ArrayList<String[]>();
    StepMetaInterface m = d.getStepMetaInterface();
    if ( m instanceof TableOutputMeta && ( (TableOutputMeta) m ).specifyFields() ) {
      String[] db = ( (TableOutputMeta) m ).getFieldDatabase();
      String[] st = ( (TableOutputMeta) m ).getFieldStream();
      for ( int i = 0; db != null && i < db.length; i++ ) {
        l.add( new String[] { db[ i ], st != null && i < st.length && st[ i ] != null ? st[ i ] : db[ i ] } );
      }
      return l;
    }
    if ( m instanceof InsertUpdateMeta ) {
      InsertUpdateMeta iu = (InsertUpdateMeta) m;
      String[] k = iu.getKeyLookup();
      String[] ks = iu.getKeyStream();
      for ( int i = 0; k != null && i < k.length; i++ ) {
        l.add( new String[] { k[ i ], ks != null && i < ks.length && ks[ i ] != null ? ks[ i ] : k[ i ] } );
      }
      String[] u = iu.getUpdateLookup();
      String[] us = iu.getUpdateStream();
      for ( int i = 0; u != null && i < u.length; i++ ) {
        boolean ya = false;
        for ( String[] x : l ) {
          ya |= x[ 0 ].equalsIgnoreCase( u[ i ] );
        }
        if ( !ya ) {
          l.add( new String[] { u[ i ], us != null && i < us.length && us[ i ] != null ? us[ i ] : u[ i ] } );
        }
      }
      return l;
    }
    if ( m instanceof TextFileOutputMeta ) {
      TextFileField[] fs = ( (TextFileOutputMeta) m ).getOutputFields();
      for ( int i = 0; fs != null && i < fs.length; i++ ) {
        if ( fs[ i ] != null && fs[ i ].getName() != null && !fs[ i ].getName().isEmpty() ) {
          l.add( new String[] { fs[ i ].getName(), fs[ i ].getName() } );
        }
      }
      if ( !l.isEmpty() ) {
        return l;
      }
    }
    RowMetaInterface rm = lin.campos( d );
    if ( rm != null ) {
      for ( ValueMetaInterface v : rm.getValueMetaList() ) {
        l.add( new String[] { v.getName(), v.getName() } );
      }
    }
    return l;
  }

  /** "Integer(9)", "String(100)", "Number(10,2)". */
  static String tipo( ValueMetaInterface v ) {
    if ( v == null ) {
      return "";
    }
    String t = v.getTypeDesc();
    if ( v.getLength() > 0 ) {
      t += "(" + v.getLength() + ( v.getPrecision() > 0 ? "," + v.getPrecision() : "" ) + ")";
    }
    return t;
  }

  /** Los orígenes {@code recurso.campo} del campo (sin las claves de búsqueda, salvo que no haya otro). */
  static String origenes( TransMeta tm, LinajePropio.Campo c, List<String> notas, Rotulos r ) {
    Set<String> out = new LinkedHashSet<String>();
    boolean hayValor = false;
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      hayValor |= !esClave( camino );
    }
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      if ( camino.isEmpty() || hayValor && esClave( camino ) ) {
        continue;
      }
      LinajePropio.Nodo o = camino.get( 0 );
      if ( LinajePropio.FUENTE.equals( o.clase ) ) {
        out.add( recursoOrigen( tm, o ) + "." + o.campo );
      } else if ( LinajePropio.CREA.equals( o.clase ) ) {
        notas.add( r.f( "map.creado", o.step ) );
      } else {
        notas.add( r.f( "map.desconocido", o.campo, o.step ) );
      }
    }
    return String.join( "; ", out );
  }

  /** El recurso de un nodo fuente: la tabla de la consulta, el archivo o el step. */
  static String recursoOrigen( TransMeta tm, LinajePropio.Nodo o ) {
    StepMeta s = tm.findStep( o.step );
    if ( s != null && s.getStepMetaInterface() instanceof TableInputMeta ) {
      String sql = ( (TableInputMeta) s.getStepMetaInterface() ).getSQL();
      Matcher m = FROM.matcher( sql == null ? "" : tm.environmentSubstitute( sql ) );
      if ( m.find() ) {
        return m.group( 1 ).replaceAll( "[\"\\[\\]`]", "" );
      }
    }
    if ( o.op != null && !o.op.trim().isEmpty() && o.op.indexOf( ' ' ) < 0 ) {
      return o.op.trim();
    }
    return o.step;
  }

  private static boolean esClave( List<LinajePropio.Nodo> camino ) {
    for ( LinajePropio.Nodo n : camino ) {
      if ( LinajePropio.ROL_CLAVE.equals( n.rol ) ) {
        return true;
      }
    }
    return false;
  }

  /** El valor por defecto que pone un Stream lookup del camino a ese campo, o "". */
  static String defecto( TransMeta tm, LinajePropio.Campo c, String campo ) {
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      for ( LinajePropio.Nodo n : camino ) {
        StepMeta s = tm.findStep( n.step );
        if ( s != null && s.getStepMetaInterface() instanceof StreamLookupMeta ) {
          StreamLookupMeta sl = (StreamLookupMeta) s.getStepMetaInterface();
          String[] vals = sl.getValue();
          String[] nombres = sl.getValueName();
          String[] defs = sl.getValueDefault();
          for ( int i = 0; vals != null && i < vals.length; i++ ) {
            String sale = nombres != null && i < nombres.length && nombres[ i ] != null && !nombres[ i ].isEmpty()
              ? nombres[ i ] : vals[ i ];
            if ( n.campo.equals( sale ) && defs != null && i < defs.length && defs[ i ] != null
              && !defs[ i ].isEmpty() ) {
              return defs[ i ];
            }
          }
        }
      }
    }
    return "";
  }

  /** Los steps de validación (o con manejo de errores) por los que pasa el campo. */
  static String validaciones( TransMeta tm, LinajePropio.Campo c, Rotulos r ) {
    Set<String> out = new LinkedHashSet<String>();
    for ( List<LinajePropio.Nodo> camino : c.caminos ) {
      for ( LinajePropio.Nodo n : camino ) {
        StepMeta s = tm.findStep( n.step );
        if ( s == null ) {
          continue;
        }
        if ( VALIDAN.contains( s.getStepID() ) ) {
          out.add( r.f( "map.valida", s.getName() ) );
        }
        if ( s.isDoingErrorHandling() ) {
          out.add( r.f( "map.errores", s.getName() ) );
        }
      }
      // los filtros entre el ultimo nodo del camino y el destino no crean nodos: los busca hacia atras
      if ( !camino.isEmpty() ) {
        String ultimo = camino.get( camino.size() - 1 ).step;
        StepMeta s = tm.findStep( ultimo );
        for ( StepMeta p : s == null ? new ArrayList<StepMeta>() : tm.findPreviousSteps( s, false ) ) {
          if ( VALIDAN.contains( p.getStepID() ) ) {
            out.add( r.f( "map.valida", p.getName() ) );
          }
        }
      }
    }
    return String.join( "; ", out );
  }

  private static String etiqueta( String xml, String tag ) {
    int a = xml.indexOf( "<" + tag + ">" );
    int b = a < 0 ? -1 : xml.indexOf( "</" + tag + ">", a );
    return a < 0 || b < 0 ? null : xml.substring( a + tag.length() + 2, b ).trim();
  }

  /** Un contexto de informe para una transformación suelta (mapping_export / mapping_import fuera del panel). */
  static Contexto contexto( TransMeta tm, String idioma ) {
    io.github.ivi2511.pentahomcp.informes.Captura c = new io.github.ivi2511.pentahomcp.informes.Captura();
    c.copia = tm;
    c.tipo = io.github.ivi2511.pentahomcp.informes.Captura.TRANSFORMACION;
    c.nombre = tm.getName();
    c.archivo = tm.getFilename();
    c.capturado = new java.util.Date();
    io.github.ivi2511.pentahomcp.informes.Pedido p = new io.github.ivi2511.pentahomcp.informes.Pedido();
    p.idioma = Rotulos.normalizar( idioma );
    return new ContextoKettle( c, p, null, null );
  }

  /** La tabla del informe (las 9 columnas, con los títulos en el idioma). */
  static Tabla tabla( List<Fila> filas, Rotulos r ) {
    String[] cols = new String[ COLUMNAS.size() ];
    for ( int i = 0; i < cols.length; i++ ) {
      cols[ i ] = r.s( "map.col." + COLUMNAS.get( i ) );
    }
    Tabla t = new Tabla( cols );
    for ( Fila f : filas ) {
      t.fila( f.celdas() );
    }
    return t;
  }

  /** Las filas como JSON (las claves de COLUMNAS, más step y stream_field). */
  static ArrayNode json( List<Fila> filas ) {
    ArrayNode a = Tools.arr();
    for ( Fila f : filas ) {
      ObjectNode o = a.addObject();
      String[] c = f.celdas();
      for ( int i = 0; i < c.length; i++ ) {
        o.put( COLUMNAS.get( i ), c[ i ] );
      }
      if ( f.paso != null ) {
        o.put( "step", f.paso );
      }
      if ( f.campoFlujo != null ) {
        o.put( "stream_field", f.campoFlujo );
      }
    }
    return a;
  }
}
