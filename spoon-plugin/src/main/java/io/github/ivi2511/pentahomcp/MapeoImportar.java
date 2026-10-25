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
 * para las que faltan:
 * <ul>
 * <li>el origen llega al step destino con otro nombre → un Calculator que copia el campo con el nombre destino;</li>
 * <li>sin origen y con valor por defecto → un Add constants;</li>
 * <li>el resto (una regla que hay que programar) → un marcador del boceto (Dummy) con una nota PENDIENTE que lista
 * las filas y sus reglas.</li>
 * </ul>
 * Por defecto es un ensayo (no cambia nada); con apply=true inserta esa cadena delante del step destino, en un solo
 * Ctrl+Z. Las reglas en lenguaje natural son ambiguas: lo que no es una copia o una constante no se adivina.
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
    /** rename | constant | placeholder | add_output (sin step destino) | null. */
    String accion;
    String campoOrigen;
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
      StepMeta d = tm.findStep( v.paso );
      RowMetaInterface llega = d == null ? null : lin.campos( d );
      String[] oc = partir( h.g( "source" ).split( ";" )[ 0 ] );
      ValueMetaInterface orig = llega == null || oc[ 1 ].isEmpty() ? null : buscarCampo( llega, oc[ 1 ] );
      if ( orig != null ) {
        v.accion = "rename";
        v.campoOrigen = orig.getName();
        v.detalle = r.f( "map.imp.copia", orig.getName(), v.campo );
      } else if ( !h.g( "default" ).isEmpty() && oc[ 1 ].isEmpty() ) {
        v.accion = "constant";
        v.detalle = r.f( "map.imp.constante", v.campo, h.g( "default" ) );
      } else {
        v.accion = "placeholder";
        v.detalle = h.g( "rule" ).isEmpty() ? r.f( "map.imp.marcador.sin_regla", v.campo ) : r.f( "map.imp.marcador",
          v.campo, h.g( "rule" ) );
      }
      out.add( v );
    }
    return out;
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
   * Inserta delante de cada step destino (con una sola entrada) la cadena que propone el ensayo: Calculator (copias),
   * Add constants, y un marcador con su nota PENDIENTE para las filas que hay que programar. Todo en u. (UI o sin
   * Spoon)
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
      for ( int i = 0; i < tm.nrTransHops(); i++ ) {
        TransHopMeta h = tm.getTransHop( i );
        if ( h.getToStep() == d && !h.isErrorHop() ) {
          entran.add( h );
        }
      }
      if ( d == null || entran.size() != 1 ) {
        ins.aviso = r.f( "map.imp.no_insertable", e.getKey(), entran.size() );
        continue;
      }
      TransHopMeta viejo = entran.get( 0 );
      StepMeta previo = viejo.getFromStep();
      List<String[]> cadena = new ArrayList<String[]>(); // {tipo, nombre, config}
      StringBuilder copias = new StringBuilder();
      StringBuilder constantes = new StringBuilder();
      List<Veredicto> pendientes = new ArrayList<Veredicto>();
      RowMetaInterface llega = new LinajePropio( tm, Mapeo.contexto( tm, idioma ) ).campos( d );
      for ( Veredicto v : e.getValue() ) {
        if ( "rename".equals( v.accion ) ) {
          ValueMetaInterface o = llega == null ? null : buscarCampo( llega, v.campoOrigen );
          copias.append( "<calculation><field_name>" ).append( x( v.campo ) ).append(
            "</field_name><calc_type>COPY_FIELD</calc_type><field_a>" ).append( x( v.campoOrigen ) ).append(
              "</field_a><field_b/><field_c/><value_type>" ).append( o == null ? "String" : o.getTypeDesc() ).append(
                "</value_type><value_length>" ).append( o == null ? -1 : o.getLength() ).append(
                  "</value_length><value_precision>" ).append( o == null ? -1 : o.getPrecision() ).append(
                    "</value_precision><remove>N</remove><conversion_mask/><decimal_symbol/><grouping_symbol/>"
                      + "<currency_symbol/></calculation>" );
        } else if ( "constant".equals( v.accion ) ) {
          String tipo = tipoKettle( v.fila.g( "type" ) );
          constantes.append( "<field><name>" ).append( x( v.campo ) ).append( "</name><type>" ).append( tipo ).append(
            "</type><format/><currency/><decimal/><group/><nullif>" ).append( x( v.fila.g( "default" ) ) ).append(
              "</nullif><length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field>" );
        } else {
          pendientes.add( v );
        }
      }
      String base = d.getName();
      if ( copias.length() > 0 ) {
        cadena.add( new String[] { "Calculator", r.f( "map.imp.paso.copia", base ), copias.toString() } );
      }
      if ( constantes.length() > 0 ) {
        cadena.add( new String[] { "Constant", r.f( "map.imp.paso.constante", base ), "<fields>" + constantes
          + "</fields>" } );
      }
      if ( !pendientes.isEmpty() ) {
        cadena.add( new String[] { ReglasBoceto.DUMMY, r.f( "map.imp.paso.marcador", base ), null } );
      }
      if ( cadena.isEmpty() ) {
        continue;
      }
      for ( String[] c : cadena ) {
        if ( tm.findStep( c[ 1 ] ) != null ) {
          throw new ToolError( "There is already a step named '" + c[ 1 ] + "': rename it or apply by hand" );
        }
      }
      // quitar el hop previo -> destino, crear la cadena y conectarla
      int ih = tm.indexOfTransHop( viejo );
      TransHopMeta clon = (TransHopMeta) viejo.clone();
      tm.removeTransHop( ih );
      TransOps.limpiarTrasQuitarHop( viejo );
      u.borrado( new Object[] { clon }, new int[] { ih } );
      List<StepMeta> nuevos = new ArrayList<StepMeta>();
      List<TransHopMeta> hops = new ArrayList<TransHopMeta>();
      String anterior = previo.getName();
      int x0 = d.getLocation() == null ? 100 : d.getLocation().x;
      int y0 = d.getLocation() == null ? 100 : d.getLocation().y;
      for ( int i = 0; i < cadena.size(); i++ ) {
        String[] c = cadena.get( i );
        TransOps.Agregado ag = TransOps.agregarStep( tm, c[ 0 ], c[ 1 ], c[ 2 ], x0 + i * 20, y0 + 90, anterior, null,
          ms );
        nuevos.add( ag.step );
        if ( ag.hop != null ) {
          hops.add( ag.hop );
        }
        anterior = ag.step.getName();
        ins.steps.add( ag.step.getName() );
        if ( ReglasBoceto.DUMMY.equals( c[ 0 ] ) ) {
          ag.step.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_MARCADOR, "mapping" );
        }
      }
      hops.add( TransOps.conectar( tm, nuevos.get( nuevos.size() - 1 ), d, true, false ) );
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
      if ( !pendientes.isEmpty() ) {
        StringBuilder t = new StringBuilder( PlanoConstruir.PREFIJO.get( Rotulos.normalizar( idioma ) ) ).append(
          r.f( "map.imp.paso.marcador", base ) );
        for ( Veredicto v : pendientes ) {
          String linea = v.campo + ( v.fila.g( "rule" ).isEmpty() ? "" : ": " + v.fila.g( "rule" ) );
          for ( String l : PlanoConstruir.envolver( linea, 40 ) ) {
            t.append( '\n' ).append( l );
          }
        }
        StepMeta m = nuevos.get( nuevos.size() - 1 );
        NotePadMeta n = new NotePadMeta( t.toString(), m.getLocation().x, m.getLocation().y + 70, 200, 40 );
        PlanoConstruir.estilizar( n, "reject" );
        tm.addNote( n );
        u.nuevo( new Object[] { n }, new int[] { tm.indexOfNote( n ) } );
      }
    }
    TransOps.resolverReferencias( tm );
    if ( !out.isEmpty() ) {
      TransLayout.Movimientos mov = TransLayout.ordenar( tm );
      if ( !mov.objetos.isEmpty() ) {
        u.posicion( mov.objetos(), mov.indices(), mov.antes(), mov.despues() );
      }
    }
    tm.clearCaches();
    tm.setChanged();
    return out;
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
