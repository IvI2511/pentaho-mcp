package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.metastore.api.IMetaStore;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasBoceto;

/**
 * mapping_import (D3): lee una hoja de mapeo rellenada (xlsx o csv, con los encabezados en inglés, español o
 * portugués: la que exporta mapping_export o una hecha a mano), la compara con lo que la transformación hace hoy
 * ({@link Mapeo}) y dice qué filas están implementadas, cuáles difieren y cuáles faltan, con una propuesta de steps
 * para las que faltan, que arma {@link MapeoReglas} leyendo la regla, el valor por defecto, el tipo y la validación
 * (copia, constante, String operations, Replace in string, Value mapper, Calculator, Select values, Stream o
 * Database lookup, If field value is null y Filter rows con rechazos). Lo que no se entiende entero queda como
 * marcador del boceto (Dummy) con una nota PENDIENTE que lista las filas, sus reglas y el porqué.
 * Por defecto es un ensayo (no cambia nada); con apply=true inserta esa cadena delante del step destino, en un solo
 * Ctrl+Z. Las reglas en lenguaje natural son ambiguas: lo que no se entiende no se adivina.
 */
final class MapeoImportar {

  static final int MAX_FILAS = 2000;
  static final long MAX_BYTES = 20L * 1024 * 1024;

  static final String IMPLEMENTADA = "implemented";
  static final String DIFIERE = "differs";
  static final String FALTA = "missing";

  private MapeoImportar() {
  }

  /** Una fila de la hoja leída. */
  static final class FilaHoja {
    int numero;
    final Map<String, String> v = new LinkedHashMap<String, String>();

    String g( String k ) {
      String s = v.get( k );
      return s == null ? "" : s.trim();
    }
  }

  // ================================================================ leer la hoja

  /** Las filas de la hoja (la primera pestaña con un encabezado que tenga la columna destino). */
  static List<FilaHoja> leer( File f ) throws Exception {
    if ( !f.isFile() ) {
      throw new ToolError( "Mapping sheet not found: " + f.getPath() );
    }
    if ( f.length() > MAX_BYTES ) {
      throw new ToolError( "The mapping sheet is too big (" + f.length() + " bytes)" );
    }
    String n = f.getName().toLowerCase( Locale.ROOT );
    List<List<String>> celdas;
    if ( n.endsWith( ".xlsx" ) || n.endsWith( ".xlsm" ) ) {
      celdas = null;
      List<List<List<String>>> hojas = xlsx( f );
      for ( List<List<String>> h : hojas ) {
        if ( encabezado( h ) != null ) {
          celdas = h;
          break;
        }
      }
      if ( celdas == null ) {
        throw new ToolError( "No sheet of " + f.getName() + " has a header with a Target / Destino column" );
      }
    } else if ( n.endsWith( ".csv" ) || n.endsWith( ".tsv" ) || n.endsWith( ".txt" ) ) {
      celdas = csv( new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ) );
    } else {
      throw new ToolError( "The mapping sheet must be .xlsx or .csv" );
    }
    return filas( celdas );
  }

  /** Las filas a partir de una tabla de celdas (busca el encabezado en las 15 primeras filas). */
  static List<FilaHoja> filas( List<List<String>> celdas ) throws ToolError {
    Object[] enc = encabezado( celdas );
    if ( enc == null ) {
      throw new ToolError( "The sheet has no header with a Target / Destino column (expected the columns of "
        + "mapping_export: Source, Target, Type, Rule, Default, Validation, Status, Owner, Notes)" );
    }
    int fila0 = (Integer) enc[ 0 ];
    @SuppressWarnings( "unchecked" )
    Map<Integer, String> cols = (Map<Integer, String>) enc[ 1 ];
    List<FilaHoja> out = new ArrayList<FilaHoja>();
    for ( int i = fila0 + 1; i < celdas.size(); i++ ) {
      List<String> c = celdas.get( i );
      FilaHoja f = new FilaHoja();
      f.numero = i + 1;
      boolean algo = false;
      for ( Map.Entry<Integer, String> e : cols.entrySet() ) {
        String x = e.getKey() < c.size() ? c.get( e.getKey() ) : null;
        if ( x != null && !x.trim().isEmpty() ) {
          f.v.put( e.getValue(), x.trim() );
          algo = true;
        }
      }
      if ( algo && !f.g( "target" ).isEmpty() ) {
        out.add( f );
      }
      if ( out.size() > MAX_FILAS ) {
        throw new ToolError( "The mapping sheet has more than " + MAX_FILAS + " rows" );
      }
    }
    return out;
  }

  /** {fila del encabezado, columna → clave} o null. */
  static Object[] encabezado( List<List<String>> celdas ) {
    Map<String, String> sinonimos = sinonimos();
    for ( int i = 0; i < Math.min( 15, celdas.size() ); i++ ) {
      Map<Integer, String> cols = new LinkedHashMap<Integer, String>();
      List<String> c = celdas.get( i );
      for ( int j = 0; j < c.size(); j++ ) {
        String k = sinonimos.get( norm( c.get( j ) ) );
        if ( k != null && !cols.containsValue( k ) ) {
          cols.put( j, k );
        }
      }
      if ( cols.containsValue( "target" ) && cols.size() >= 2 ) {
        return new Object[] { i, cols };
      }
    }
    return null;
  }

  /** Encabezado normalizado → clave de columna (los de los tres idiomas y algunos sinónimos habituales). */
  static Map<String, String> sinonimos() {
    Map<String, String> m = new HashMap<String, String>();
    for ( String idioma : new String[] { "en", "es", "pt" } ) {
      Rotulos r = Rotulos.de( PlanoInforme.AREA, idioma );
      for ( String k : Mapeo.COLUMNAS ) {
        m.put( norm( r.s( "map.col." + k ) ), k );
      }
    }
    String[][] extra = { { "origin", "source" }, { "source field", "source" }, { "campo origen", "source" },
      { "origen (tabla.campo)", "source" }, { "target field", "target" }, { "destination", "target" },
      { "campo destino", "target" }, { "data type", "type" }, { "tipo de dato", "type" }, { "tipo de dado", "type" },
      { "transformation rule", "rule" }, { "regla de transformacion", "rule" }, { "transformacion", "rule" },
      { "default value", "default" }, { "valor por defecto", "default" }, { "defecto", "default" },
      { "valor padrao", "default" }, { "validation rule", "validation" }, { "approval status", "status" },
      { "approved by", "owner" }, { "aprobado por", "owner" }, { "comments", "notes" }, { "comentarios", "notes" },
      { "observaciones", "notes" } };
    for ( String[] e : extra ) {
      m.put( norm( e[ 0 ] ), e[ 1 ] );
    }
    return m;
  }

  static String norm( String s ) {
    String x = Normalizer.normalize( s == null ? "" : s, Normalizer.Form.NFD ).replaceAll( "\\p{M}", "" );
    return x.toLowerCase( Locale.ROOT ).replaceAll( "\\s+", " " ).trim();
  }

  /** Todas las hojas de un .xlsx como celdas de texto. */
  static List<List<List<String>>> xlsx( File f ) throws Exception {
    ClassLoader previo = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader( MapeoImportar.class.getClassLoader() );
    try ( InputStream in = new FileInputStream( f );
          org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook( in ) ) {
      org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter( Locale.ROOT );
      List<List<List<String>>> hojas = new ArrayList<List<List<String>>>();
      for ( int h = 0; h < wb.getNumberOfSheets(); h++ ) {
        org.apache.poi.ss.usermodel.Sheet s = wb.getSheetAt( h );
        List<List<String>> filas = new ArrayList<List<String>>();
        for ( int i = 0; i <= Math.min( s.getLastRowNum(), MAX_FILAS + 20 ); i++ ) {
          org.apache.poi.ss.usermodel.Row r = s.getRow( i );
          List<String> c = new ArrayList<String>();
          if ( r != null ) {
            for ( int j = 0; j < Math.max( 0, r.getLastCellNum() ); j++ ) {
              org.apache.poi.ss.usermodel.Cell x = r.getCell( j );
              c.add( x == null ? "" : fmt.formatCellValue( x ) );
            }
          }
          filas.add( c );
        }
        hojas.add( filas );
      }
      return hojas;
    } finally {
      Thread.currentThread().setContextClassLoader( previo );
    }
  }

  /** CSV con comillas dobles; el separador (coma, punto y coma o tabulador) sale de la primera línea. */
  static List<List<String>> csv( String texto ) {
    String t = texto.replace( "﻿", "" );
    String primera = t.split( "\n", 2 )[ 0 ];
    char sep = cuenta( primera, ';' ) > cuenta( primera, ',' ) ? ';' : ',';
    if ( cuenta( primera, '\t' ) > Math.max( cuenta( primera, ';' ), cuenta( primera, ',' ) ) ) {
      sep = '\t';
    }
    List<List<String>> out = new ArrayList<List<String>>();
    List<String> fila = new ArrayList<String>();
    StringBuilder cel = new StringBuilder();
    boolean comillas = false;
    for ( int i = 0; i < t.length(); i++ ) {
      char c = t.charAt( i );
      if ( comillas ) {
        if ( c == '"' && i + 1 < t.length() && t.charAt( i + 1 ) == '"' ) {
          cel.append( '"' );
          i++;
        } else if ( c == '"' ) {
          comillas = false;
        } else {
          cel.append( c );
        }
      } else if ( c == '"' ) {
        comillas = true;
      } else if ( c == sep ) {
        fila.add( cel.toString() );
        cel.setLength( 0 );
      } else if ( c == '\n' ) {
        fila.add( cel.toString().replace( "\r", "" ) );
        cel.setLength( 0 );
        out.add( fila );
        fila = new ArrayList<String>();
      } else {
        cel.append( c );
      }
    }
    if ( cel.length() > 0 || !fila.isEmpty() ) {
      fila.add( cel.toString().replace( "\r", "" ) );
      out.add( fila );
    }
    return out;
  }

  private static int cuenta( String s, char c ) {
    int n = 0;
    for ( int i = 0; i < s.length(); i++ ) {
      n += s.charAt( i ) == c ? 1 : 0;
    }
    return n;
  }

  // ================================================================ comparar

  /** El resultado de una fila de la hoja. */
  static final class Veredicto {
    FilaHoja fila;
    String estado;
    String tabla;
    String campo;
    String detalle;
    /** El step destino de la tabla (o null si no hay ninguno). */
    String paso;
    /** La propuesta principal (MapeoReglas: rename, constant, value_mapper...), add_output (sin step destino) o null. */
    String accion;
    String campoOrigen;
    /** La propuesta completa de una fila que falta (null si está implementada o no tiene destino). */
    MapeoReglas.Plan plan;
  }

  /** Compara la hoja con las filas del ETL (Mapeo.filas). */
  static List<Veredicto> comparar( TransMeta tm, Contexto ctx, List<FilaHoja> hoja, List<Mapeo.Fila> etl,
                                   Rotulos r ) {
    Map<String, Mapeo.Fila> porDestino = new HashMap<String, Mapeo.Fila>();
    Map<String, List<Mapeo.Fila>> porCampo = new HashMap<String, List<Mapeo.Fila>>();
    Map<String, String> pasoDeTabla = new LinkedHashMap<String, String>();
    for ( Mapeo.Fila f : etl ) {
      if ( f.campo == null ) {
        continue;
      }
      porDestino.put( clave( f.tabla, f.campo ), f );
      List<Mapeo.Fila> l = porCampo.get( f.campo.toLowerCase( Locale.ROOT ) );
      if ( l == null ) {
        l = new ArrayList<Mapeo.Fila>();
        porCampo.put( f.campo.toLowerCase( Locale.ROOT ), l );
      }
      l.add( f );
      pasoDeTabla.put( f.tabla.toLowerCase( Locale.ROOT ), f.paso );
    }
    LinajePropio lin = new LinajePropio( tm, ctx );
    Map<String, MapeoReglas.Ctx> contextos = new HashMap<String, MapeoReglas.Ctx>();
    List<Veredicto> out = new ArrayList<Veredicto>();
    for ( FilaHoja h : hoja ) {
      Veredicto v = new Veredicto();
      v.fila = h;
      String[] tc = partir( h.g( "target" ) );
      v.tabla = tc[ 0 ];
      v.campo = tc[ 1 ];
      Mapeo.Fila e = porDestino.get( clave( v.tabla, v.campo ) );
      if ( e == null && v.tabla.isEmpty() ) {
        List<Mapeo.Fila> l = porCampo.get( v.campo.toLowerCase( Locale.ROOT ) );
        e = l != null && l.size() == 1 ? l.get( 0 ) : null;
      }
      v.paso = pasoDeTabla.get( v.tabla.toLowerCase( Locale.ROOT ) );
      if ( v.paso == null && pasoDeTabla.size() == 1 && ( v.tabla.isEmpty() || e != null ) ) {
        v.paso = pasoDeTabla.values().iterator().next();
      }
      if ( e != null ) {
        v.paso = e.paso;
        List<String> dif = new ArrayList<String>();
        String[] oc = partir( h.g( "source" ).split( ";" )[ 0 ] );
        if ( !oc[ 1 ].isEmpty() && !e.origen.toLowerCase( Locale.ROOT ).contains( oc[ 1 ].toLowerCase( Locale.ROOT ) ) ) {
          dif.add( r.f( "map.imp.origen", h.g( "source" ), e.origen.isEmpty() ? "—" : e.origen ) );
        }
        if ( !h.g( "type" ).isEmpty() && !mismoTipo( h.g( "type" ), e.tipo ) ) {
          dif.add( r.f( "map.imp.tipo", h.g( "type" ), e.tipo ) );
        }
        v.estado = dif.isEmpty() ? IMPLEMENTADA : DIFIERE;
        v.detalle = String.join( " ", dif );
        out.add( v );
        continue;
      }
      v.estado = FALTA;
      if ( v.paso == null ) {
        v.accion = "add_output";
        v.detalle = r.f( "map.imp.sin_destino", v.tabla.isEmpty() ? "?" : v.tabla );
        out.add( v );
        continue;
      }
      MapeoReglas.Ctx c = contextos.get( v.paso );
      if ( c == null ) {
        c = contexto( tm, tm.findStep( v.paso ), lin, r );
        contextos.put( v.paso, c );
      }
      String[] oc = partir( h.g( "source" ).split( ";" )[ 0 ] );
      v.plan = MapeoReglas.planear( h, v.campo, oc, c );
      if ( v.plan.marcador() ) {
        v.accion = "placeholder";
        v.detalle = h.g( "rule" ).isEmpty() ? r.f( "map.imp.marcador.sin_regla", v.campo ) : r.f( "map.imp.marcador",
          v.campo, h.g( "rule" ) );
      } else {
        v.accion = v.plan.principal();
        v.detalle = String.join( " ", v.plan.detalles );
        if ( MapeoReglas.COPIA.equals( v.accion ) ) {
          v.campoOrigen = v.plan.acciones.get( 0 ).entrada;
        }
        // el campo queda disponible para las filas siguientes (una regla puede usar otro destino ya calculado)
        String t = MapeoReglas.tipoProducido( v.plan, v.campo, c );
        c.disponibles.put( v.campo.toLowerCase( Locale.ROOT ), meta( v.campo, t == null ? "String" : t ) );
      }
      out.add( v );
    }
    return out;
  }

  /**
   * Lo que se sabe delante del step destino para planear las filas: los campos que le llegan, los steps sueltos que
   * leen una tabla o archivo (para un Stream lookup) y la conexión para un Database lookup.
   */
  static MapeoReglas.Ctx contexto( TransMeta tm, StepMeta d, LinajePropio lin, Rotulos r ) {
    MapeoReglas.Ctx c = new MapeoReglas.Ctx();
    c.r = r;
    RowMetaInterface llega = d == null ? null : lin.campos( d );
    if ( llega != null ) {
      for ( ValueMetaInterface v : llega.getValueMetaList() ) {
        c.disponibles.put( v.getName().toLowerCase( Locale.ROOT ), v );
      }
    }
    // steps de entrada sueltos (sin hops de salida): se pueden usar como origen de un Stream lookup sin tocar el flujo
    for ( StepMeta s : tm.getSteps() ) {
      if ( s == d || !tm.findPreviousSteps( s, true ).isEmpty() || !tm.findNextSteps( s ).isEmpty() ) {
        continue;
      }
      RowMetaInterface cs = lin.campos( s );
      if ( cs == null ) {
        continue;
      }
      Map<String, ValueMetaInterface> campos = new LinkedHashMap<String, ValueMetaInterface>();
      for ( ValueMetaInterface v : cs.getValueMetaList() ) {
        campos.put( v.getName(), v );
      }
      List<String> nombres = new ArrayList<String>();
      nombres.add( s.getName() );
      String rec = Mapeo.recursoOrigen( tm, new LinajePropio.Nodo( s.getName(), "", LinajePropio.FUENTE, lin.recurso(
        s ) ) );
      nombres.add( rec );
      nombres.add( rec.replaceAll( "\\.[A-Za-z0-9]{1,5}$", "" ) );
      for ( String n : nombres ) {
        String k = n.toLowerCase( Locale.ROOT );
        if ( !c.pasosDeTabla.containsKey( k ) ) {
          c.pasosDeTabla.put( k, s.getName() );
          c.camposDeTabla.put( k, campos );
        }
      }
    }
    // la conexión: la del step destino si escribe en una base; si no, la única de la transformación
    try {
      org.pentaho.di.core.database.DatabaseMeta[] dbs = d == null ? null : d.getStepMetaInterface()
        .getUsedDatabaseConnections();
      if ( dbs != null && dbs.length > 0 && dbs[ 0 ] != null ) {
        c.conexion = dbs[ 0 ].getName();
      }
    } catch ( Throwable ignorada ) {
      // sin conexión del destino
    }
    if ( c.conexion == null && tm.nrDatabases() == 1 ) {
      c.conexion = tm.getDatabase( 0 ).getName();
    }
    return c;
  }

  static ValueMetaInterface meta( String nombre, String tipo ) {
    try {
      return org.pentaho.di.core.row.value.ValueMetaFactory.createValueMeta( nombre,
        org.pentaho.di.core.row.value.ValueMetaFactory.getIdForValueMeta( tipo ) );
    } catch ( Exception e ) {
      return new org.pentaho.di.core.row.value.ValueMetaString( nombre );
    }
  }

  private static ValueMetaInterface buscarCampo( RowMetaInterface rm, String nombre ) {
    for ( ValueMetaInterface v : rm.getValueMetaList() ) {
      if ( v.getName().equalsIgnoreCase( nombre ) ) {
        return v;
      }
    }
    return null;
  }

  /** "dw.hechos.importe" → {"dw.hechos", "importe"}; sin punto, {"", texto}. */
  static String[] partir( String s ) {
    String x = s == null ? "" : s.trim();
    int p = x.lastIndexOf( '.' );
    if ( p <= 0 || p == x.length() - 1 ) {
      return new String[] { "", x };
    }
    return new String[] { x.substring( 0, p ).trim(), x.substring( p + 1 ).trim() };
  }

  private static String clave( String tabla, String campo ) {
    return ( tabla == null ? "" : tabla.toLowerCase( Locale.ROOT ) ) + "\u0000" + ( campo == null ? ""
      : campo.toLowerCase( Locale.ROOT ) );
  }

  /** Tipos equivalentes: integer/int/bigint ≈ Integer, number/decimal/numeric/float ≈ Number o BigNumber... */
  static boolean mismoTipo( String hoja, String etl ) {
    return familia( hoja ).equals( familia( etl ) );
  }

  private static String familia( String t ) {
    String x = t == null ? "" : t.toLowerCase( Locale.ROOT ).replaceAll( "\\(.*", "" ).trim();
    if ( x.matches( "int|integer|bigint|smallint|long|entero|inteiro" ) ) {
      return "int";
    }
    if ( x.matches( "number|bignumber|decimal|numeric|float|double|real|numero|número" ) ) {
      return "num";
    }
    if ( x.matches( "string|varchar|char|text|texto|nvarchar|cadena" ) ) {
      return "str";
    }
    if ( x.matches( "date|timestamp|datetime|fecha|data" ) ) {
      return "date";
    }
    if ( x.matches( "boolean|bool|booleano" ) ) {
      return "bool";
    }
    return x;
  }

  // ================================================================ aplicar

  /** Lo que se insertó delante de un step destino. */
  static final class Insercion {
    String destino;
    final List<String> steps = new ArrayList<String>();
    String aviso;
  }

  /**
   * Inserta delante de cada step destino (con una sola entrada) la cadena que proponen las filas que faltan, en el
   * orden de {@link MapeoReglas#ORDEN}: búsquedas (Stream / Database lookup), Calculator (copias, cuentas, partes de
   * fecha), String operations, Replace in string, Value mapper, Select values (tipos y formatos), Add constants, If
   * field value is null, el marcador PENDIENTE de lo que hay que programar y Filter rows con los rechazos a otro
   * marcador. Todo en u (un Ctrl+Z). (UI o sin Spoon)
   */
  static List<Insercion> aplicar( TransMeta tm, List<Veredicto> vs, String idioma, UndoGrupo u, IMetaStore ms )
    throws Exception {
    Rotulos r = Rotulos.de( PlanoInforme.AREA, idioma );
    Map<String, List<Veredicto>> porPaso = new LinkedHashMap<String, List<Veredicto>>();
    for ( Veredicto v : vs ) {
      if ( FALTA.equals( v.estado ) && v.paso != null && v.accion != null && !"add_output".equals( v.accion ) ) {
        List<Veredicto> l = porPaso.get( v.paso );
        if ( l == null ) {
          l = new ArrayList<Veredicto>();
          porPaso.put( v.paso, l );
        }
        l.add( v );
      }
    }
    List<Insercion> out = new ArrayList<Insercion>();
    for ( Map.Entry<String, List<Veredicto>> e : porPaso.entrySet() ) {
      Insercion ins = new Insercion();
      ins.destino = e.getKey();
      out.add( ins );
      StepMeta d = tm.findStep( e.getKey() );
      List<TransHopMeta> entran = new ArrayList<TransHopMeta>();
      for ( int i = 0; d != null && i < tm.nrTransHops(); i++ ) {
        TransHopMeta h = tm.getTransHop( i );
        if ( h.getToStep() == d && !h.isErrorHop() ) {
          entran.add( h );
        }
      }
      if ( d == null || entran.size() != 1 ) {
        ins.aviso = r.f( "map.imp.no_insertable", e.getKey(), entran.size() );
        continue;
      }
      Cadena cad = cadena( tm, d, e.getValue(), r );
      if ( cad.pasos.isEmpty() ) {
        continue;
      }
      for ( String[] c : cad.pasos ) {
        if ( tm.findStep( c[ 1 ] ) != null ) {
          throw new ToolError( "There is already a step named '" + c[ 1 ] + "': rename it or apply by hand" );
        }
      }
      if ( cad.rechazos != null && tm.findStep( cad.rechazos ) != null ) {
        throw new ToolError( "There is already a step named '" + cad.rechazos + "': rename it or apply by hand" );
      }
      TransHopMeta viejo = entran.get( 0 );
      StepMeta previo = viejo.getFromStep();
      // quitar el hop previo -> destino, crear la cadena y conectarla
      int ih = tm.indexOfTransHop( viejo );
      TransHopMeta clon = (TransHopMeta) viejo.clone();
      tm.removeTransHop( ih );
      TransOps.limpiarTrasQuitarHop( viejo );
      u.borrado( new Object[] { clon }, new int[] { ih } );
      List<StepMeta> nuevos = new ArrayList<StepMeta>();
      List<TransHopMeta> hops = new ArrayList<TransHopMeta>();
      String anterior = previo.getName();
      // la cadena va debajo del hop previo -> destino, sin mover nada de lo que ya estaba
      int xa = previo.getLocation() == null ? 100 : previo.getLocation().x;
      int xb = d.getLocation() == null ? xa + 200 : d.getLocation().x;
      int ya = previo.getLocation() == null ? 100 : previo.getLocation().y;
      int yb = d.getLocation() == null ? ya : d.getLocation().y;
      int x0 = Math.min( xa, xb ) + 40;
      int y0 = Math.max( ya, yb ) + 110;
      StepMeta marcador = null;
      StepMeta filtro = null;
      for ( int i = 0; i < cad.pasos.size(); i++ ) {
        String[] c = cad.pasos.get( i );
        TransOps.Agregado ag = TransOps.agregarStep( tm, c[ 0 ], c[ 1 ], c[ 2 ], x0 + i * 170, y0, anterior, null, ms );
        nuevos.add( ag.step );
        if ( ag.hop != null ) {
          hops.add( ag.hop );
        }
        anterior = ag.step.getName();
        ins.steps.add( ag.step.getName() );
        if ( ReglasBoceto.DUMMY.equals( c[ 0 ] ) ) {
          ag.step.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_MARCADOR, "mapping" );
          marcador = ag.step;
        }
        if ( "FilterRows".equals( c[ 0 ] ) ) {
          filtro = ag.step;
        }
      }
      StepMeta ultimo = nuevos.get( nuevos.size() - 1 );
      hops.add( TransOps.conectar( tm, ultimo, d, true, false ) );
      StepMeta rechazos = null;
      if ( filtro != null ) {
        TransOps.Agregado ag = TransOps.agregarStep( tm, ReglasBoceto.DUMMY, cad.rechazos, null, filtro.getLocation().x,
          filtro.getLocation().y + 110, null, null, ms );
        rechazos = ag.step;
        rechazos.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_MARCADOR, "mapping" );
        nuevos.add( rechazos );
        ins.steps.add( rechazos.getName() );
        hops.add( TransOps.conectar( tm, filtro, rechazos, true, false ) );
      }
      // Stream lookup: el step suelto que lee la tabla le manda sus filas por un hop
      for ( String[] x : cad.infos ) {
        StepMeta desde = tm.findStep( x[ 0 ] );
        StepMeta hacia = tm.findStep( x[ 1 ] );
        if ( desde != null && hacia != null && tm.findTransHop( desde, hacia, true ) == null ) {
          hops.add( TransOps.conectar( tm, desde, hacia, true, false ) );
        }
      }
      int[] is = new int[ nuevos.size() ];
      for ( int i = 0; i < is.length; i++ ) {
        is[ i ] = tm.indexOfStep( nuevos.get( i ) );
      }
      u.nuevo( nuevos.toArray(), is );
      hops.sort( ( a, b ) -> Integer.compare( tm.indexOfTransHop( a ), tm.indexOfTransHop( b ) ) );
      int[] ix = new int[ hops.size() ];
      for ( int i = 0; i < ix.length; i++ ) {
        ix[ i ] = tm.indexOfTransHop( hops.get( i ) );
      }
      u.nuevo( hops.toArray(), ix );
      TransOps.resolverReferencias( tm );
      List<NotePadMeta> notas = new ArrayList<NotePadMeta>();
      if ( marcador != null ) {
        notas.add( nota( marcador, cad.pendiente, idioma ) );
      }
      if ( rechazos != null ) {
        notas.add( nota( rechazos, cad.notaRechazos, idioma ) );
      }
      for ( NotePadMeta n : notas ) {
        PlanoConstruir.estilizar( n, "reject" );
        tm.addNote( n );
      }
      if ( !notas.isEmpty() ) {
        int[] in = new int[ notas.size() ];
        for ( int i = 0; i < in.length; i++ ) {
          in[ i ] = tm.indexOfNote( notas.get( i ) );
        }
        u.nuevo( notas.toArray(), in );
      }
    }
    TransOps.resolverReferencias( tm );
    tm.clearCaches();
    tm.setChanged();
    return out;
  }

  /** Una nota PENDIENTE debajo de un marcador: la primera línea con el prefijo y su nombre, después las líneas. */
  private static NotePadMeta nota( StepMeta m, List<String> lineas, String idioma ) {
    String pref = PlanoConstruir.PREFIJO.get( Rotulos.normalizar( idioma ) );
    StringBuilder t = new StringBuilder( pref == null ? PlanoConstruir.PREFIJO.get( "en" ) : pref ).append( m
      .getName() );
    for ( String linea : lineas ) {
      for ( String l : PlanoConstruir.envolver( linea, 40 ) ) {
        t.append( '\n' ).append( l );
      }
    }
    return new NotePadMeta( t.toString(), m.getLocation().x, m.getLocation().y + 70, 200, 40 );
  }

  /** La cadena de steps de un destino: {tipo, nombre, config}, los hops de info y los textos de las notas. */
  static final class Cadena {
    final List<String[]> pasos = new ArrayList<String[]>();
    /** {step origen, Stream lookup}. */
    final List<String[]> infos = new ArrayList<String[]>();
    final List<String> pendiente = new ArrayList<String>();
    final List<String> notaRechazos = new ArrayList<String>();
    String rechazos;
  }

  /** Arma la cadena a partir de las propuestas de las filas que faltan delante de d. */
  static Cadena cadena( TransMeta tm, StepMeta d, List<Veredicto> filas, Rotulos r ) throws Exception {
    Cadena cad = new Cadena();
    String base = d.getName();
    Map<String, List<Object[]>> porTipo = new LinkedHashMap<String, List<Object[]>>(); // tipo -> {accion, fila}
    for ( String t : MapeoReglas.ORDEN ) {
      porTipo.put( t, new ArrayList<Object[]>() );
    }
    for ( Veredicto v : filas ) {
      if ( v.plan == null || v.plan.marcador() ) {
        String linea = v.campo + ( v.fila.g( "rule" ).isEmpty() ? "" : ": " + v.fila.g( "rule" ) );
        if ( v.plan != null && !v.plan.dudas.isEmpty() ) {
          linea += " (" + String.join( "; ", v.plan.dudas ) + ")";
        }
        cad.pendiente.add( linea );
        continue;
      }
      for ( MapeoReglas.Accion a : v.plan.acciones ) {
        porTipo.get( a.tipo ).add( new Object[] { a, v } );
      }
    }
    // búsquedas: una por origen o tabla y clave
    Map<String, StringBuilder> busquedas = new LinkedHashMap<String, StringBuilder>();
    Map<String, String[]> cabeza = new LinkedHashMap<String, String[]>();
    for ( String t : new String[] { MapeoReglas.BUSCA_FLUJO, MapeoReglas.BUSCA_BD } ) {
      for ( Object[] o : porTipo.get( t ) ) {
        MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
        Veredicto v = (Veredicto) o[ 1 ];
        String k = t + "\u0000" + ( a.desde == null ? a.conexion : a.desde ) + "\u0000" + a.tabla + "\u0000"
          + a.claveFlujo + "\u0000" + a.claveTabla;
        StringBuilder vals = busquedas.get( k );
        if ( vals == null ) {
          vals = new StringBuilder();
          busquedas.put( k, vals );
          cabeza.put( k, new String[] { t, a.desde, a.conexion, a.tabla, a.claveFlujo, a.claveTabla } );
        }
        String tipo = a.tipoSale != null ? a.tipoSale : tipoKettle( v.fila.g( "type" ) );
        vals.append( "<value><name>" ).append( x( a.campoTabla ) ).append( "</name><rename>" ).append( x( a.salida ) )
          .append( "</rename><default>" ).append( x( v.fila.g( "default" ) ) ).append( "</default><type>" ).append(
            tipo ).append( "</type></value>" );
      }
    }
    for ( Map.Entry<String, StringBuilder> b : busquedas.entrySet() ) {
      String[] h = cabeza.get( b.getKey() );
      String tabla = h[ 3 ];
      String esquema = "";
      int p = tabla.lastIndexOf( '.' );
      if ( p > 0 && MapeoReglas.BUSCA_BD.equals( h[ 0 ] ) ) {
        esquema = tabla.substring( 0, p );
        tabla = tabla.substring( p + 1 );
      }
      String nombre = r.f( "map.imp.paso.busca", tabla, base );
      if ( MapeoReglas.BUSCA_FLUJO.equals( h[ 0 ] ) ) {
        cad.pasos.add( new String[] { "StreamLookup", nombre, "<from>" + x( h[ 1 ] ) + "</from><input_sorted>N"
          + "</input_sorted><preserve_memory>Y</preserve_memory><sorted_list>N</sorted_list><integer_pair>N"
          + "</integer_pair><lookup><key><name>" + x( h[ 4 ] ) + "</name><field>" + x( h[ 5 ] ) + "</field></key>"
          + b.getValue() + "</lookup>" } );
        cad.infos.add( new String[] { h[ 1 ], nombre } );
      } else {
        cad.pasos.add( new String[] { "DBLookup", nombre, "<connection>" + x( h[ 2 ] ) + "</connection><cache>Y</cache>"
          + "<cache_load_all>N</cache_load_all><cache_size>0</cache_size><lookup><schema>" + x( esquema ) + "</schema>"
          + "<table>" + x( tabla ) + "</table><orderby/><fail_on_multiple>N</fail_on_multiple><eat_row_on_failure>N"
          + "</eat_row_on_failure><key><name>" + x( h[ 4 ] ) + "</name><field>" + x( h[ 5 ] ) + "</field><condition>="
          + "</condition><name2/></key>" + b.getValue() + "</lookup>" } );
      }
    }
    // Calculator: copias y cuentas
    StringBuilder calc = new StringBuilder();
    for ( Object[] o : porTipo.get( MapeoReglas.COPIA ) ) {
      MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
      calc.append( funcion( a.salida, "COPY_FIELD", a.entrada, null, a.tipoSale == null ? "String" : a.tipoSale,
        false ) );
    }
    for ( Object[] o : porTipo.get( MapeoReglas.CALCULO ) ) {
      for ( String[] f : ( (MapeoReglas.Accion) o[ 0 ] ).funciones ) {
        calc.append( funcion( f[ 0 ], f[ 1 ], f[ 2 ], f[ 3 ], f[ 4 ], "Y".equals( f[ 5 ] ) ) );
      }
    }
    if ( calc.length() > 0 ) {
      cad.pasos.add( new String[] { "Calculator", porTipo.get( MapeoReglas.CALCULO ).isEmpty() ? r.f(
        "map.imp.paso.copia", base ) : r.f( "map.imp.paso.calculo", base ), calc.toString() } );
    }
    // String operations: una para todos los campos
    StringBuilder so = new StringBuilder();
    for ( Object[] o : porTipo.get( MapeoReglas.CADENAS ) ) {
      MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
      so.append( "<field><in_stream_name>" ).append( x( a.entrada ) ).append( "</in_stream_name><out_stream_name>" )
        .append( a.entrada.equalsIgnoreCase( a.salida ) ? "" : x( a.salida ) ).append( "</out_stream_name><trim_type>" )
        .append( a.trim ).append( "</trim_type><lower_upper>" ).append( a.mayus ).append( "</lower_upper><padding_type>"
          + "none</padding_type><pad_char/><pad_len/><init_cap>" ).append( a.inicial ? "yes" : "no" ).append(
            "</init_cap><mask_xml>none</mask_xml><digits>" ).append( a.digitos ).append( "</digits>"
              + "<remove_special_characters>none</remove_special_characters></field>" );
    }
    if ( so.length() > 0 ) {
      cad.pasos.add( new String[] { "StringOperations", r.f( "map.imp.paso.cadenas", base ), "<fields>" + so
        + "</fields>" } );
    }
    // Replace in string: un step mientras no se repita el campo (el segundo reemplazo del mismo campo va en otro)
    List<StringBuilder> rs = new ArrayList<StringBuilder>();
    List<java.util.Set<String>> campos = new ArrayList<java.util.Set<String>>();
    for ( Object[] o : porTipo.get( MapeoReglas.REEMPLAZO ) ) {
      MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
      String k = a.salida.toLowerCase( Locale.ROOT );
      if ( rs.isEmpty() || campos.get( campos.size() - 1 ).contains( k ) ) {
        rs.add( new StringBuilder() );
        campos.add( new java.util.HashSet<String>() );
      }
      campos.get( campos.size() - 1 ).add( k );
      rs.get( rs.size() - 1 ).append( "<field><in_stream_name>" ).append( x( a.entrada ) ).append(
        "</in_stream_name><out_stream_name>" ).append( a.entrada.equalsIgnoreCase( a.salida ) ? "" : x( a.salida ) )
        .append( "</out_stream_name><use_regex>" ).append( a.regex ? "yes" : "no" ).append(
          "</use_regex><replace_string>" ).append( x( a.busca ) ).append( "</replace_string><replace_by_string>" )
        .append( x( a.por ) ).append( "</replace_by_string><set_empty_string>N</set_empty_string>"
          + "<replace_field_by_string/><whole_word>no</whole_word><case_sensitive>yes</case_sensitive><is_unicode>no"
          + "</is_unicode></field>" );
    }
    for ( int i = 0; i < rs.size(); i++ ) {
      cad.pasos.add( new String[] { "ReplaceString", r.f( "map.imp.paso.reemplazo", base ) + ( i == 0 ? "" : " "
        + ( i + 1 ) ), "<fields>" + rs.get( i ) + "</fields>" } );
    }
    // Value mapper: uno por campo
    for ( Object[] o : porTipo.get( MapeoReglas.MAPEO ) ) {
      MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
      StringBuilder vm = new StringBuilder( "<field_to_use>" + x( a.entrada ) + "</field_to_use><target_field>"
        + ( a.entrada.equalsIgnoreCase( a.salida ) ? "" : x( a.salida ) ) + "</target_field><non_match_default>"
        + x( a.resto ) + "</non_match_default><fields>" );
      for ( String[] par : a.pares ) {
        vm.append( "<field><source_value>" ).append( x( par[ 0 ] ) ).append( "</source_value><target_value>" ).append(
          x( par[ 1 ] ) ).append( "</target_value></field>" );
      }
      cad.pasos.add( new String[] { "ValueMapper", r.f( "map.imp.paso.mapeo", a.salida, base ), vm + "</fields>" } );
    }
    // Select values: cambios de tipo y formato
    StringBuilder sv = new StringBuilder();
    for ( Object[] o : porTipo.get( MapeoReglas.TIPO ) ) {
      MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
      sv.append( "<meta><name>" ).append( x( a.salida ) ).append( "</name><rename>" ).append( x( a.salida ) ).append(
        "</rename><type>" ).append( a.tipoKettle ).append( "</type><length>-2</length><precision>-2</precision>"
          + "<conversion_mask>" ).append( x( a.mascara ) ).append( "</conversion_mask><date_format_lenient>false"
            + "</date_format_lenient><date_format_locale/><date_format_timezone/><lenient_string_to_number>false"
            + "</lenient_string_to_number><encoding/><decimal_symbol>" ).append( x( a.decimal ) ).append(
              "</decimal_symbol><grouping_symbol/><currency_symbol/><storage_type/></meta>" );
    }
    if ( sv.length() > 0 ) {
      cad.pasos.add( new String[] { "SelectValues", r.f( "map.imp.paso.tipo", base ), "<fields><select_unspecified>N"
        + "</select_unspecified>" + sv + "</fields>" } );
    }
    // Add constants
    StringBuilder ct = new StringBuilder();
    for ( Object[] o : porTipo.get( MapeoReglas.CONSTANTE ) ) {
      MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
      ct.append( "<field><name>" ).append( x( a.salida ) ).append( "</name><type>" ).append( a.tipoSale ).append(
        "</type><format/><currency/><decimal/><group/><nullif>" ).append( x( a.valor ) ).append(
          "</nullif><length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field>" );
    }
    if ( ct.length() > 0 ) {
      cad.pasos.add( new String[] { "Constant", r.f( "map.imp.paso.constante", base ), "<fields>" + ct + "</fields>" } );
    }
    // If field value is null
    StringBuilder sn = new StringBuilder();
    for ( Object[] o : porTipo.get( MapeoReglas.SI_NULO ) ) {
      MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
      sn.append( "<field><name>" ).append( x( a.salida ) ).append( "</name><value>" ).append( x( a.valor ) ).append(
        "</value><mask/><set_empty_string>N</set_empty_string></field>" );
    }
    if ( sn.length() > 0 ) {
      cad.pasos.add( new String[] { "IfNull", r.f( "map.imp.paso.si_nulo", base ), "<replaceAllByValue/>"
        + "<replaceAllMask/><selectFields>Y</selectFields><selectValuesType>N</selectValuesType><setEmptyStringAll>N"
        + "</setEmptyStringAll><valuetypes></valuetypes><fields>" + sn + "</fields>" } );
    }
    // lo que hay que programar
    if ( !cad.pendiente.isEmpty() ) {
      cad.pasos.add( new String[] { ReglasBoceto.DUMMY, r.f( "map.imp.paso.marcador", base ), null } );
    }
    // validaciones: Filter rows; las filas que no cumplen, a un marcador de rechazos
    List<org.pentaho.di.core.Condition> conds = new ArrayList<org.pentaho.di.core.Condition>();
    Map<String, String> tipos = tiposFinales( tm, d, filas );
    for ( Object[] o : porTipo.get( MapeoReglas.FILTRO ) ) {
      MapeoReglas.Accion a = (MapeoReglas.Accion) o[ 0 ];
      for ( String[] val : a.validaciones ) {
        conds.addAll( condiciones( a.salida, tipos.get( a.salida.toLowerCase( Locale.ROOT ) ), val ) );
      }
      cad.notaRechazos.add( a.salida + ": " + a.textoValidacion );
    }
    if ( !conds.isEmpty() ) {
      org.pentaho.di.core.Condition raiz;
      if ( conds.size() == 1 ) {
        raiz = conds.get( 0 );
      } else {
        raiz = new org.pentaho.di.core.Condition();
        for ( int i = 0; i < conds.size(); i++ ) {
          if ( i > 0 ) {
            conds.get( i ).setOperator( org.pentaho.di.core.Condition.OPERATOR_AND );
          }
          raiz.addCondition( conds.get( i ) );
        }
      }
      cad.rechazos = r.f( "map.imp.paso.rechazos", base );
      cad.notaRechazos.add( 0, r.s( "map.imp.rechazos.nota" ) );
      cad.pasos.add( new String[] { "FilterRows", r.f( "map.imp.paso.filtro", base ), "<send_true_to>" + x( base )
        + "</send_true_to><send_false_to>" + x( cad.rechazos ) + "</send_false_to><compare>" + raiz.getXML()
        + "</compare>" } );
    }
    return cad;
  }

  /** Los tipos de los campos delante del destino después de la cadena (los que llegan y los que crean las filas). */
  private static Map<String, String> tiposFinales( TransMeta tm, StepMeta d, List<Veredicto> filas ) {
    Map<String, String> m = new HashMap<String, String>();
    try {
      RowMetaInterface llega = tm.getPrevStepFields( d );
      for ( ValueMetaInterface v : llega.getValueMetaList() ) {
        m.put( v.getName().toLowerCase( Locale.ROOT ), v.getTypeDesc() );
      }
    } catch ( Exception ignorada ) {
      // sin los campos: texto
    }
    for ( Veredicto v : filas ) {
      if ( v.plan != null && !v.plan.marcador() ) {
        String t = MapeoReglas.tipoProducido( v.plan, v.campo, null );
        if ( t != null ) {
          m.put( v.campo.toLowerCase( Locale.ROOT ), t );
        }
      }
    }
    return m;
  }

  /** Las condiciones de Filter rows de una validación {op, valores...} sobre el campo. */
  static List<org.pentaho.di.core.Condition> condiciones( String campo, String tipo, String[] val ) throws Exception {
    List<org.pentaho.di.core.Condition> l = new ArrayList<org.pentaho.di.core.Condition>();
    switch ( val[ 0 ] ) {
      case "notnull":
        l.add( new org.pentaho.di.core.Condition( campo, org.pentaho.di.core.Condition.FUNC_NOT_NULL, null, null ) );
        break;
      case "between":
        l.add( new org.pentaho.di.core.Condition( campo, org.pentaho.di.core.Condition.FUNC_LARGER_EQUAL, null,
          constante( tipo, val[ 1 ] ) ) );
        l.add( new org.pentaho.di.core.Condition( campo, org.pentaho.di.core.Condition.FUNC_SMALLER_EQUAL, null,
          constante( tipo, val[ 2 ] ) ) );
        break;
      case "in": {
        List<String> vs = new ArrayList<String>( java.util.Arrays.asList( val ).subList( 1, val.length ) );
        l.add( new org.pentaho.di.core.Condition( campo, org.pentaho.di.core.Condition.FUNC_IN_LIST, null,
          new org.pentaho.di.core.row.ValueMetaAndData( new org.pentaho.di.core.row.value.ValueMetaString( "constant" ),
            String.join( ";", vs ) ) ) );
        break;
      }
      default: {
        int f = "gt".equals( val[ 0 ] ) ? org.pentaho.di.core.Condition.FUNC_LARGER : "ge".equals( val[ 0 ] )
          ? org.pentaho.di.core.Condition.FUNC_LARGER_EQUAL : "lt".equals( val[ 0 ] )
            ? org.pentaho.di.core.Condition.FUNC_SMALLER : "le".equals( val[ 0 ] )
              ? org.pentaho.di.core.Condition.FUNC_SMALLER_EQUAL : "eq".equals( val[ 0 ] )
                ? org.pentaho.di.core.Condition.FUNC_EQUAL : org.pentaho.di.core.Condition.FUNC_NOT_EQUAL;
        l.add( new org.pentaho.di.core.Condition( campo, f, null, constante( tipo, val[ 1 ] ) ) );
      }
    }
    return l;
  }

  /** Un valor fijo de la condición con el tipo del campo (texto si no se puede convertir). */
  private static org.pentaho.di.core.row.ValueMetaAndData constante( String tipo, String texto ) {
    try {
      String t = tipo == null ? "String" : tipo;
      if ( "Integer".equals( t ) || "Number".equals( t ) || "BigNumber".equals( t ) ) {
        ValueMetaInterface vm = meta( "constant", t );
        String n = texto.trim().replace( ',', '.' );
        Object dato = "Integer".equals( t ) ? (Object) Long.valueOf( n ) : "Number".equals( t ) ? (Object) Double
          .valueOf( n ) : new java.math.BigDecimal( n );
        return new org.pentaho.di.core.row.ValueMetaAndData( vm, dato );
      }
    } catch ( Exception e ) {
      // como texto
    }
    return new org.pentaho.di.core.row.ValueMetaAndData( new org.pentaho.di.core.row.value.ValueMetaString(
      "constant" ), texto.trim() );
  }

  /** Una función de Calculator. */
  private static String funcion( String campo, String tipoCalc, String a, String b, String tipo, boolean quitar ) {
    return "<calculation><field_name>" + x( campo ) + "</field_name><calc_type>" + tipoCalc + "</calc_type><field_a>"
      + x( a ) + "</field_a><field_b>" + x( b ) + "</field_b><field_c/><value_type>" + ( tipo == null ? "String" : tipo )
      + "</value_type><value_length>-1</value_length><value_precision>-1</value_precision><remove>" + ( quitar ? "Y"
        : "N" ) + "</remove><conversion_mask/><decimal_symbol/><grouping_symbol/><currency_symbol/></calculation>";
  }

  /** Tipo de Kettle para un tipo de la hoja (String si no se reconoce). */
  static String tipoKettle( String t ) {
    String x = t == null ? "" : t.toLowerCase( Locale.ROOT ).replaceAll( "\\(.*", "" ).trim();
    if ( x.matches( "int|integer|bigint|smallint|long|entero|inteiro" ) ) {
      return "Integer";
    }
    if ( x.matches( "number|decimal|numeric|float|double|real|numero|número" ) ) {
      return "Number";
    }
    if ( x.equals( "bignumber" ) ) {
      return "BigNumber";
    }
    if ( x.matches( "date|timestamp|datetime|fecha|data" ) ) {
      return "Date";
    }
    if ( x.matches( "boolean|bool|booleano" ) ) {
      return "Boolean";
    }
    return "String";
  }

  /** Texto seguro dentro de una etiqueta XML. */
  static String x( String s ) {
    return ( s == null ? "" : s ).replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" ).replace( "\"",
      "&quot;" );
  }

  // ================================================================ JSON

  static ObjectNode json( List<Veredicto> vs, List<Mapeo.Fila> etl, List<Insercion> hecho ) {
    ObjectNode o = Tools.obj();
    int im = 0;
    int di = 0;
    int fa = 0;
    ArrayNode a = o.putArray( "rows" );
    java.util.Set<String> enHoja = new java.util.HashSet<String>();
    for ( Veredicto v : vs ) {
      ObjectNode x = a.addObject();
      x.put( "row", v.fila.numero );
      x.put( "target", v.fila.g( "target" ) );
      if ( !v.fila.g( "source" ).isEmpty() ) {
        x.put( "source", v.fila.g( "source" ) );
      }
      x.put( "result", v.estado );
      if ( !v.fila.g( "status" ).isEmpty() ) {
        x.put( "sheet_status", v.fila.g( "status" ) );
      }
      if ( v.paso != null ) {
        x.put( "target_step", v.paso );
      }
      if ( v.accion != null ) {
        x.put( "proposal", v.accion );
      }
      if ( v.plan != null && !v.plan.marcador() && !v.plan.acciones.isEmpty() ) {
        ArrayNode st = x.putArray( "steps" );
        for ( MapeoReglas.Accion ac : v.plan.acciones ) {
          st.add( ac.tipo );
        }
      }
      if ( v.plan != null && !v.plan.dudas.isEmpty() ) {
        x.put( "why", String.join( "; ", v.plan.dudas ) );
      }
      if ( v.detalle != null && !v.detalle.isEmpty() ) {
        x.put( "detail", v.detalle );
      }
      if ( IMPLEMENTADA.equals( v.estado ) ) {
        im++;
      } else if ( DIFIERE.equals( v.estado ) ) {
        di++;
      } else {
        fa++;
      }
      enHoja.add( clave( v.tabla, v.campo ) );
      enHoja.add( clave( "", v.campo ) );
    }
    ArrayNode extra = Tools.arr();
    for ( Mapeo.Fila f : etl ) {
      if ( f.campo != null && !enHoja.contains( clave( f.tabla, f.campo ) ) && !enHoja.contains( clave( "",
        f.campo ) ) ) {
        extra.add( f.destino );
      }
    }
    o.put( "implemented", im );
    o.put( "differs", di );
    o.put( "missing", fa );
    if ( extra.size() > 0 ) {
      o.set( "in_etl_not_in_sheet", extra );
    }
    if ( hecho != null ) {
      ArrayNode h = o.putArray( "applied" );
      for ( Insercion ins : hecho ) {
        ObjectNode x = h.addObject();
        x.put( "before_step", ins.destino );
        ArrayNode s = x.putArray( "steps" );
        for ( String n : ins.steps ) {
          s.add( n );
        }
        if ( ins.aviso != null ) {
          x.put( "warning", ins.aviso );
        }
      }
    }
    return o;
  }

}
