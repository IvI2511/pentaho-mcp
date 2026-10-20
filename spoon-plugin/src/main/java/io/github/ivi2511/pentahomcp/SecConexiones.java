package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Seccion connections (diseno §2.3): conexiones a bases (nombre, tipo, acceso, host, base y que
 * steps o entradas las usan; <b>nunca contrasenas ni usuarios</b>) y parametros con su valor por
 * defecto y su descripcion. Las conexiones compartidas (shared.xml) que el documento no usa no se
 * listan. No cuesta nada: no se conecta.
 *
 * @dueño WP2
 */
final class SecConexiones implements Recolector {

  @Override
  public String id() {
    return SeccionId.CONEXIONES;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos r = ctx.rotulos( InformeServicio.AREA );
    AbstractMeta m = ctx.captura().copia;
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), ctx.idioma() ) );
    ObjectNode datos = Txt.obj();
    datos.put( "section", id() );

    Map<DatabaseMeta, List<String>> usos = conexiones( m );
    ArrayNode dc = datos.putArray( "connections" );
    if ( !usos.isEmpty() ) {
      Tabla t = new Tabla( r.s( "con.col.nombre" ), r.s( "con.col.tipo" ), r.s( "con.col.acceso" ),
        r.s( "con.col.host" ), r.s( "con.col.base" ), r.s( "con.col.usada" ) );
      t.id = "connections-conexiones";
      t.titulo = r.s( "con.tabla" );
      t.nota = r.s( "con.nota" );
      for ( Map.Entry<DatabaseMeta, List<String>> e : usos.entrySet() ) {
        DatabaseMeta d = e.getKey(); // nunca getPassword ni getUsername
        String tipo = tipo( d );
        String acceso = acceso( d );
        String usada = unir( e.getValue() );
        t.fila( d.getName(), tipo, acceso, nulo( d.getHostname() ), nulo( d.getDatabaseName() ), usada );
        ObjectNode o = dc.addObject();
        o.put( "name", d.getName() );
        Txt.poner( o, "type", tipo );
        Txt.poner( o, "access", acceso );
        Txt.poner( o, "host", d.getHostname() );
        Txt.poner( o, "port", puerto( d ) );
        Txt.poner( o, "database", d.getDatabaseName() );
        ArrayNode u = o.putArray( "used_by" );
        for ( String x : e.getValue() ) {
          u.add( x );
        }
        if ( d.isShared() ) {
          o.put( "shared", true );
        }
      }
      s.con( t );
    }

    String[] ps = m.listParameters();
    ArrayNode dp = datos.putArray( "parameters" );
    int nPar = ps == null ? 0 : ps.length;
    if ( nPar > 0 ) {
      Tabla t = new Tabla( r.s( "con.par.col.nombre" ), r.s( "con.par.col.defecto" ), r.s( "con.par.col.desc" ) );
      t.id = "connections-parametros";
      t.titulo = r.s( "con.par.tabla" );
      for ( String p : ps ) {
        String def = null;
        String desc = null;
        try {
          def = m.getParameterDefault( p );
          desc = m.getParameterDescription( p );
        } catch ( Exception ignored ) {
          // parametro que desaparecio entre listar y leer
        }
        t.fila( p, nulo( def ), nulo( desc ) );
        ObjectNode o = dp.addObject();
        o.put( "name", p );
        Txt.poner( o, "default", def );
        Txt.poner( o, "description", desc );
      }
      s.con( t );
    }

    if ( usos.isEmpty() && nPar == 0 ) {
      s.resumen = r.s( "con.resumen.nada" );
    } else {
      s.resumen = r.f( "cuenta", Txt.n( r, "n.conexion", usos.size() ), Txt.n( r, "n.parametro", nPar ) );
    }
    s.datos = datos;
    return s;
  }

  /**
   * Las conexiones a informar, con los steps o entradas que las usan: las usadas y las propias del
   * documento (no compartidas) aunque nadie las use. En el orden del documento.
   */
  static Map<DatabaseMeta, List<String>> conexiones( AbstractMeta m ) {
    Map<String, List<String>> porNombre = new LinkedHashMap<String, List<String>>();
    if ( m instanceof TransMeta ) {
      for ( StepMeta sm : ( (TransMeta) m ).getSteps() ) {
        try {
          anotar( porNombre, sm.getStepMetaInterface().getUsedDatabaseConnections(), sm.getName() );
        } catch ( Throwable ignored ) {
          // step raro
        }
      }
    } else if ( m instanceof JobMeta ) {
      for ( JobEntryCopy c : ( (JobMeta) m ).getJobCopies() ) {
        try {
          if ( c.getEntry() != null ) {
            anotar( porNombre, c.getEntry().getUsedDatabaseConnections(), c.getName() );
          }
        } catch ( Throwable ignored ) {
          // entrada rara
        }
      }
    }
    Map<DatabaseMeta, List<String>> out = new LinkedHashMap<DatabaseMeta, List<String>>();
    List<DatabaseMeta> dbs = m.getDatabases();
    if ( dbs != null ) {
      for ( DatabaseMeta d : dbs ) {
        if ( d == null || d.getName() == null ) {
          continue;
        }
        List<String> u = porNombre.get( d.getName() );
        if ( u != null || !d.isShared() ) {
          out.put( d, u == null ? new ArrayList<String>() : u );
        }
      }
    }
    return out;
  }

  private static void anotar( Map<String, List<String>> m, DatabaseMeta[] dbs, String quien ) {
    if ( dbs == null ) {
      return;
    }
    for ( DatabaseMeta d : dbs ) {
      if ( d == null || d.getName() == null ) {
        continue;
      }
      List<String> l = m.get( d.getName() );
      if ( l == null ) {
        l = new ArrayList<String>();
        m.put( d.getName(), l );
      }
      if ( !l.contains( quien ) ) {
        l.add( quien );
      }
    }
  }

  private static String tipo( DatabaseMeta d ) {
    try {
      String n = d.getDatabaseInterface().getPluginName();
      if ( Txt.vacio( n ) != null ) {
        return n;
      }
    } catch ( Throwable ignored ) {
      // sin plugin
    }
    return nulo( d.getPluginId() );
  }

  private static String acceso( DatabaseMeta d ) {
    try {
      return nulo( d.getAccessTypeDesc() );
    } catch ( Throwable e ) {
      return "";
    }
  }

  private static String puerto( DatabaseMeta d ) {
    try {
      return d.getDatabasePortNumberString();
    } catch ( Throwable e ) {
      return null;
    }
  }

  private static String nulo( String s ) {
    return s == null ? "" : s;
  }

  private static String unir( List<String> l ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      sb.append( sb.length() == 0 ? "" : ", " ).append( s );
    }
    return sb.toString();
  }
}
