package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Los steps que cambian el VALOR de un campo sin cambiarle el nombre (o que crean uno a partir de otros), para el
 * linaje propio ({@link LinajePropio}) y la clasificación de los caminos de metaverse: String operations, Replace in
 * string, Strings cut, Concat fields, Split fields, Split field to rows, User defined Java expression, Value mapper,
 * If field value is null, Null if, Number range, Set field value, Set field value to a constant, Add a checksum,
 * Regex evaluation y Modified JavaScript value. Sin estas reglas un campo que pasa por un String operations quedaba
 * «sin cambios» en la hoja de mapeo y en la sección de linaje.
 * <p>
 * Lee el XML de la configuración del step (como la regla de Formula de LinajePropio): no castea la meta, así sirve
 * igual para los steps que son plugins con su propio classloader. Nunca ejecuta nada.
 */
final class LinajeValores {

  /** Ids de step que cubre {@link #de}. */
  static final Set<String> TIPOS = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList(
    "StringOperations", "ReplaceString", "StringCut", "ConcatFields", "FieldSplitter", "SplitFieldToRows3", "Janino",
    "ValueMapper", "IfNull", "NullIf", "NumberRange", "SetValueField", "SetValueConstant", "CheckSum", "RegexEval",
    "ScriptValueMod" ) ) );

  private static final Pattern IDENT = Pattern.compile( "[A-Za-z_$][A-Za-z0-9_$]*" );
  private static final int MAX_ETIQUETA = 120;

  private LinajeValores() {
  }

  /** Un campo de salida del step: de qué campos de entrada sale (vacío = nace de un valor fijo) y cómo. */
  static final class Cambio {
    final String sale;
    final List<String> entran = new ArrayList<String>();
    final String op;

    Cambio( String sale, String op, String... entran ) {
      this.sale = sale;
      this.op = op;
      for ( String e : entran ) {
        if ( !vacio( e ) && !this.entran.contains( e ) ) {
          this.entran.add( e );
        }
      }
    }
  }

  /**
   * Los campos de salida a los que el step les cambia el valor (o que crea a partir de otros), con su origen y su
   * etiqueta. salida = los campos de salida del step (para If field value is null por tipos, Java y JavaScript);
   * puede ser null. Vacío si el step no es de los que cubre o no tiene configuración.
   */
  static Map<String, Cambio> de( StepMeta s, RowMetaInterface salida, Rotulos rot ) {
    Map<String, Cambio> out = new LinkedHashMap<String, Cambio>();
    if ( s == null || !TIPOS.contains( s.getStepID() ) ) {
      return out;
    }
    Node raiz;
    try {
      raiz = XMLHandler.getSubNode( XMLHandler.loadXMLString( "<step>" + s.getStepMetaInterface().getXML()
        + "</step>" ), "step" );
    } catch ( Throwable e ) {
      return out;
    }
    switch ( s.getStepID() ) {
      case "StringOperations":
        stringOperations( raiz, rot, out );
        break;
      case "ReplaceString":
        replaceString( raiz, rot, out );
        break;
      case "StringCut":
        for ( Node f : campos( raiz ) ) {
          String in = t( f, "in_stream_name" );
          poner( out, sale( in, t( f, "out_stream_name" ) ), rot.f( "val.corta", nz( t( f, "cut_from" ) ), nz( t( f,
            "cut_to" ) ) ), in );
        }
        break;
      case "ConcatFields": {
        Node cf = XMLHandler.getSubNode( raiz, "ConcatFields" );
        String destino = cf == null ? null : t( cf, "targetFieldName" );
        List<String> ins = new ArrayList<String>();
        for ( Node f : campos( raiz ) ) {
          if ( !vacio( t( f, "name" ) ) ) {
            ins.add( t( f, "name" ) );
          }
        }
        if ( !vacio( destino ) ) {
          poner( out, destino, rot.f( "val.concatena", unir( ins ), nz( t( raiz, "separator" ) ) ), ins.toArray(
            new String[ 0 ] ) );
        }
        break;
      }
      case "FieldSplitter": {
        String in = t( raiz, "splitfield" );
        int i = 1;
        for ( Node f : campos( raiz ) ) {
          String n = t( f, "name" );
          if ( !vacio( n ) ) {
            poner( out, n, rot.f( "val.parte", nz( in ), nz( t( raiz, "delimiter" ) ), i ), in );
          }
          i++;
        }
        break;
      }
      case "SplitFieldToRows3": {
        String in = t( raiz, "splitfield" );
        poner( out, vacio( t( raiz, "newfield" ) ) ? in : t( raiz, "newfield" ), rot.f( "val.filas", nz( t( raiz,
          "delimiter" ) ) ), in );
        break;
      }
      case "Janino":
        for ( Node f : XMLHandler.getNodes( raiz, "formula" ) ) {
          String expr = t( f, "formula_string" );
          String reemplaza = t( f, "replace_field" );
          String n = vacio( reemplaza ) ? t( f, "field_name" ) : reemplaza;
          poner( out, n, rot.f( "val.expresion", recortar( expr ) ), usados( expr, salida, n ).toArray(
            new String[ 0 ] ) );
        }
        break;
      case "ValueMapper": {
        String in = t( raiz, "field_to_use" );
        List<String> pares = new ArrayList<String>();
        for ( Node f : campos( raiz ) ) {
          pares.add( ( vacio( t( f, "source_value" ) ) ? "∅" : t( f, "source_value" ) ) + " → " + nz( t( f,
            "target_value" ) ) );
        }
        String op = rot.f( "val.mapea", recortar( String.join( ", ", pares ) ) );
        if ( !vacio( t( raiz, "non_match_default" ) ) ) {
          op += "; " + rot.f( "val.resto", t( raiz, "non_match_default" ) );
        }
        poner( out, sale( in, t( raiz, "target_field" ) ), op, in );
        break;
      }
      case "IfNull":
        ifNull( raiz, salida, rot, out );
        break;
      case "NullIf":
        for ( Node f : campos( raiz ) ) {
          poner( out, t( f, "name" ), rot.f( "val.nulo_si", nz( t( f, "value" ) ) ), t( f, "name" ) );
        }
        break;
      case "NumberRange": {
        String in = t( raiz, "inputField" );
        List<String> rangos = new ArrayList<String>();
        Node rs = XMLHandler.getSubNode( raiz, "rules" );
        for ( Node r : rs == null ? new ArrayList<Node>() : XMLHandler.getNodes( rs, "rule" ) ) {
          rangos.add( "[" + nz( t( r, "lower_bound" ) ) + ", " + nz( t( r, "upper_bound" ) ) + ") → " + nz( t( r,
            "value" ) ) );
        }
        String op = rot.f( "val.rango", recortar( String.join( ", ", rangos ) ) );
        if ( !vacio( t( raiz, "fallBackValue" ) ) ) {
          op += "; " + rot.f( "val.resto", t( raiz, "fallBackValue" ) );
        }
        poner( out, sale( in, t( raiz, "outputField" ) ), op, in );
        break;
      }
      case "SetValueField":
        for ( Node f : campos( raiz ) ) {
          poner( out, t( f, "name" ), rot.f( "val.copia", nz( t( f, "replaceby" ) ) ), t( f, "replaceby" ) );
        }
        break;
      case "SetValueConstant":
        for ( Node f : campos( raiz ) ) {
          poner( out, t( f, "name" ), rot.f( "val.fija", nz( t( f, "value" ) ) ) );
        }
        break;
      case "CheckSum": {
        List<String> ins = new ArrayList<String>();
        for ( Node f : campos( raiz ) ) {
          ins.add( t( f, "name" ) );
        }
        poner( out, t( raiz, "resultfieldName" ), rot.f( "val.checksum", nz( t( raiz, "checksumtype" ) ), unir( ins ) ),
          ins.toArray( new String[ 0 ] ) );
        break;
      }
      case "RegexEval": {
        String in = t( raiz, "matcher" );
        String re = recortar( t( raiz, "script" ) );
        poner( out, t( raiz, "resultfieldname" ), rot.f( "val.regex", re, nz( in ) ), in );
        if ( "Y".equalsIgnoreCase( t( raiz, "allowcapturegroups" ) ) ) {
          for ( Node f : campos( raiz ) ) {
            poner( out, t( f, "name" ), rot.f( "val.regex_grupo", re, nz( in ) ), in );
          }
        }
        break;
      }
      case "ScriptValueMod": {
        StringBuilder script = new StringBuilder();
        Node js = XMLHandler.getSubNode( raiz, "jsScripts" );
        for ( Node j : js == null ? new ArrayList<Node>() : XMLHandler.getNodes( js, "jsScript" ) ) {
          script.append( nz( t( j, "jsScript_script" ) ) ).append( '\n' );
        }
        for ( Node f : campos( raiz ) ) {
          String n = vacio( t( f, "rename" ) ) ? t( f, "name" ) : t( f, "rename" );
          poner( out, n, rot.s( "val.script" ), usados( script.toString(), salida, null ).toArray( new String[ 0 ] ) );
        }
        break;
      }
      default:
        break;
    }
    return out;
  }

  // ---------------------------------------------------------------- String operations, Replace in string

  private static void stringOperations( Node raiz, Rotulos rot, Map<String, Cambio> out ) {
    for ( Node f : campos( raiz ) ) {
      String in = t( f, "in_stream_name" );
      List<String> ops = new ArrayList<String>();
      String trim = low( t( f, "trim_type" ) );
      if ( "left".equals( trim ) || "right".equals( trim ) || "both".equals( trim ) ) {
        ops.add( rot.s( "val.trim." + trim ) );
      }
      String lu = low( t( f, "lower_upper" ) );
      if ( "upper".equals( lu ) || "lower".equals( lu ) ) {
        ops.add( rot.s( "val." + lu ) );
      }
      if ( "yes".equals( low( t( f, "init_cap" ) ) ) || "y".equals( low( t( f, "init_cap" ) ) ) ) {
        ops.add( rot.s( "val.initcap" ) );
      }
      String pad = low( t( f, "padding_type" ) );
      if ( "left".equals( pad ) || "right".equals( pad ) ) {
        ops.add( rot.f( "val.pad." + pad, nz( t( f, "pad_char" ) ), nz( t( f, "pad_len" ) ) ) );
      }
      String dig = low( t( f, "digits" ) );
      if ( "digits_only".equals( dig ) || "remove_digits".equals( dig ) ) {
        ops.add( rot.s( "val." + dig ) );
      }
      String esp = low( t( f, "remove_special_characters" ) );
      if ( !vacio( esp ) && !"none".equals( esp ) ) {
        ops.add( rot.f( "val.especiales", esp ) );
      }
      String mask = low( t( f, "mask_xml" ) );
      if ( !vacio( mask ) && !"none".equals( mask ) ) {
        ops.add( rot.f( "val.mascara", mask ) );
      }
      String sale = sale( in, t( f, "out_stream_name" ) );
      if ( ops.isEmpty() ) {
        if ( !vacio( in ) && !in.equals( sale ) ) {
          poner( out, sale, null, in ); // solo copia con otro nombre: renombre sin cambio de valor
        }
        continue;
      }
      poner( out, sale, String.join( ", ", ops ), in );
    }
  }

  private static void replaceString( Node raiz, Rotulos rot, Map<String, Cambio> out ) {
    for ( Node f : campos( raiz ) ) {
      String in = t( f, "in_stream_name" );
      String busca = nz( t( f, "replace_string" ) );
      String campo = t( f, "replace_field_by_string" );
      String por = nz( t( f, "replace_by_string" ) );
      String op;
      if ( !vacio( campo ) ) {
        op = rot.f( "val.reemplaza_campo", recortar( busca ), campo );
      } else if ( "yes".equals( low( t( f, "use_regex" ) ) ) || "y".equals( low( t( f, "use_regex" ) ) ) ) {
        op = rot.f( "val.reemplaza_regex", recortar( busca ), recortar( por ) );
      } else {
        op = rot.f( "val.reemplaza", recortar( busca ), recortar( por ) );
      }
      if ( vacio( campo ) ) {
        poner( out, sale( in, t( f, "out_stream_name" ) ), op, in );
      } else {
        poner( out, sale( in, t( f, "out_stream_name" ) ), op, in, campo );
      }
    }
  }

  // ---------------------------------------------------------------- If field value is null

  private static void ifNull( Node raiz, RowMetaInterface salida, Rotulos rot, Map<String, Cambio> out ) {
    boolean porCampo = "Y".equalsIgnoreCase( t( raiz, "selectFields" ) );
    boolean porTipo = "Y".equalsIgnoreCase( t( raiz, "selectValuesType" ) );
    if ( porCampo ) {
      for ( Node f : campos( raiz ) ) {
        String v = "Y".equalsIgnoreCase( t( f, "set_empty_string" ) ) ? "" : nz( t( f, "value" ) );
        poner( out, t( f, "name" ), rot.f( "val.si_nulo", v ), t( f, "name" ) );
      }
      return;
    }
    if ( salida == null ) {
      return;
    }
    Map<String, String> porTipos = new LinkedHashMap<String, String>();
    if ( porTipo ) {
      Node vts = XMLHandler.getSubNode( raiz, "valuetypes" );
      for ( Node vt : vts == null ? new ArrayList<Node>() : XMLHandler.getNodes( vts, "valuetype" ) ) {
        String v = "Y".equalsIgnoreCase( t( vt, "set_type_empty_string" ) ) ? "" : nz( t( vt, "value" ) );
        porTipos.put( low( t( vt, "name" ) ), v );
      }
    }
    for ( ValueMetaInterface v : salida.getValueMetaList() ) {
      String valor;
      if ( porTipo ) {
        valor = porTipos.get( low( v.getTypeDesc() ) );
        if ( valor == null ) {
          continue;
        }
      } else {
        valor = "Y".equalsIgnoreCase( t( raiz, "setEmptyStringAll" ) ) ? "" : t( raiz, "replaceAllByValue" );
        if ( valor == null ) {
          continue;
        }
      }
      poner( out, v.getName(), rot.f( "val.si_nulo", valor ), v.getName() );
    }
  }

  // ---------------------------------------------------------------- ayudas

  /** Los campos de entrada que nombra una expresión (Java o JavaScript): los identificadores que son campos. */
  static List<String> usados( String expr, RowMetaInterface salida, String excepto ) {
    List<String> l = new ArrayList<String>();
    if ( expr == null || salida == null ) {
      return l;
    }
    Set<String> nombres = new HashSet<String>();
    for ( ValueMetaInterface v : salida.getValueMetaList() ) {
      nombres.add( v.getName() );
    }
    String sinTextos = expr.replaceAll( "\"(?:[^\"\\\\]|\\\\.)*\"", " " ).replaceAll( "//[^\n]*", " " );
    Matcher m = IDENT.matcher( sinTextos );
    while ( m.find() ) {
      String id = m.group();
      boolean metodo = m.start() > 0 && sinTextos.charAt( m.start() - 1 ) == '.';
      if ( !metodo && nombres.contains( id ) && !l.contains( id ) ) {
        l.add( id );
      }
    }
    if ( excepto != null && l.isEmpty() && nombres.contains( excepto ) && expr.contains( excepto ) ) {
      l.add( excepto );
    }
    return l;
  }

  private static void poner( Map<String, Cambio> out, String sale, String op, String... entran ) {
    if ( vacio( sale ) || out.containsKey( sale ) ) {
      return;
    }
    out.put( sale, new Cambio( sale, op, entran ) );
  }

  private static List<Node> campos( Node raiz ) {
    Node fs = XMLHandler.getSubNode( raiz, "fields" );
    return fs == null ? new ArrayList<Node>() : XMLHandler.getNodes( fs, "field" );
  }

  private static String sale( String in, String out ) {
    return vacio( out ) ? in : out;
  }

  private static String t( Node n, String tag ) {
    String v = XMLHandler.getTagValue( n, tag );
    return v == null ? null : v.trim().isEmpty() && !tag.contains( "pad_char" ) && !tag.equals( "separator" )
      && !tag.equals( "delimiter" ) ? null : v;
  }

  private static String nz( String s ) {
    return s == null ? "" : s;
  }

  private static String low( String s ) {
    return s == null ? "" : s.trim().toLowerCase( Locale.ROOT );
  }

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  private static String unir( List<String> l ) {
    List<String> x = new ArrayList<String>();
    for ( String s : l ) {
      if ( !vacio( s ) ) {
        x.add( s );
      }
    }
    return String.join( ", ", x );
  }

  private static String recortar( String s ) {
    String x = s == null ? "" : s.replaceAll( "\\s+", " " ).trim();
    return x.length() <= MAX_ETIQUETA ? x : x.substring( 0, MAX_ETIQUETA - 1 ) + "…";
  }
}
