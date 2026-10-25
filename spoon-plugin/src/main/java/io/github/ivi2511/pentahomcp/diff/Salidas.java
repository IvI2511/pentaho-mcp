package io.github.ivi2511.pentahomcp.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.ivi2511.pentahomcp.diff.Diferencia.Como;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Propiedad;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Que;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Las tres formas de un diff: texto legible (para la consola, git y la IA), Markdown (para un PR o un informe) y JSON
 * (para programas). Textos en informes/textos/diff_*.properties. Las contraseñas nunca salen: un valor secreto se
 * muestra como «(oculta)» o «cambiada (oculta)».
 */
public final class Salidas {

  /** Área de Rotulos de todo lo del diff (motor, Spoon, informe y línea de comandos). */
  public static final String AREA = "diff";

  /** Largo máximo de un valor de una línea en el texto. */
  static final int MAX_VALOR = 160;
  /** Más líneas que esto en un valor de varias líneas: solo la cuenta. */
  static final int MAX_LINEAS = 400;

  /** El orden de las secciones. */
  static final Que[] ORDEN = { Que.DOCUMENTO, Que.STEP, Que.ENTRADA, Que.SALTO, Que.ERRORES, Que.CONEXION,
    Que.PARAMETRO, Que.NOTA };

  private Salidas() {
  }

  public static Rotulos rotulos( String idioma ) {
    return Rotulos.de( AREA, idioma );
  }

  // ================================================================ texto

  public static String texto( ResultadoDiff r, String idioma ) {
    Rotulos t = rotulos( idioma );
    StringBuilder sb = new StringBuilder();
    sb.append( titulo( r, t ) ).append( '\n' );
    if ( r.etiquetaAntes != null || r.etiquetaDespues != null ) {
      sb.append( "  " ).append( t.f( "before_after", nn( r.etiquetaAntes ), nn( r.etiquetaDespues ) ) ).append( '\n' );
    }
    if ( r.iguales() ) {
      sb.append( "  " ).append( t.s( r.ignorarPosiciones ? "identical_positions" : "identical" ) ).append( '\n' );
      return sb.toString();
    }
    sb.append( "  " ).append( resumen( r, t ) ).append( '\n' );
    for ( Que q : ORDEN ) {
      List<Diferencia> ds = r.de( q );
      if ( ds.isEmpty() ) {
        continue;
      }
      sb.append( '\n' ).append( t.s( "sec." + q.id ) ).append( '\n' );
      for ( Diferencia d : ds ) {
        sb.append( "  " ).append( d.como.simbolo ).append( ' ' ).append( cabecera( d, t ) ).append( '\n' );
        for ( Propiedad p : d.propiedades ) {
          lineasPropiedad( d, p, t, "      ", sb );
        }
      }
    }
    return sb.toString();
  }

  /** «Cambios en la transformación «x»» (con el nombre de antes si cambió). */
  public static String titulo( ResultadoDiff r, Rotulos t ) {
    String nombre = nn( r.nombreDespues );
    if ( r.nombreAntes != null && !r.nombreAntes.equals( r.nombreDespues ) ) {
      nombre = r.nombreAntes + " → " + nombre;
    }
    return t.f( Documento.JOB.equals( r.tipo ) ? "title_job" : "title_transformation", nombre );
  }

  /** «Steps: 1 agregado, 2 cambiados · Saltos: 1 quitado · posiciones ignoradas». */
  public static String resumen( ResultadoDiff r, Rotulos t ) {
    List<String> partes = new ArrayList<String>();
    for ( Que q : ORDEN ) {
      List<String> cs = new ArrayList<String>();
      for ( Como c : Como.values() ) {
        int n = r.cuenta( q, c );
        if ( n > 0 ) {
          cs.add( t.f( n == 1 ? "c." + c.id + ".1" : "c." + c.id, n ) );
        }
      }
      if ( !cs.isEmpty() ) {
        partes.add( t.s( "sec." + q.id ) + ": " + unir( cs, ", " ) );
      }
    }
    if ( r.ignorarPosiciones ) {
      partes.add( t.s( "positions_ignored" ) );
    }
    return unir( partes, " · " );
  }

  /** La línea de un cambio, sin el símbolo: «Ordenar (SortRows)», «a → b», «Leer CSV (CsvInput), antes «Leer»». */
  public static String cabecera( Diferencia d, Rotulos t ) {
    StringBuilder sb = new StringBuilder();
    if ( d.que == Que.SALTO ) {
      sb.append( d.desde ).append( " → " ).append( d.hasta );
      if ( d.como == Como.AGREGADO || d.como == Como.QUITADO ) {
        List<String> marcas = new ArrayList<String>();
        for ( Propiedad p : d.propiedades ) {
          String m = marcaSalto( p, t, d.como == Como.QUITADO );
          if ( m != null ) {
            marcas.add( m );
          }
        }
        if ( !marcas.isEmpty() ) {
          sb.append( " (" ).append( unir( marcas, ", " ) ).append( ')' );
        }
      }
      return sb.toString();
    }
    if ( d.que == Que.DOCUMENTO ) {
      return nn( d.nombre );
    }
    if ( d.que == Que.NOTA ) {
      return "«" + d.nombre + "»";
    }
    sb.append( d.nombre );
    if ( d.tipo != null && !d.tipo.isEmpty() ) {
      sb.append( " (" ).append( d.tipo ).append( ')' );
    }
    if ( d.como == Como.RENOMBRADO ) {
      sb.append( ", " ).append( t.f( "renamed_from", d.nombreAntes ) );
    }
    if ( d.como == Como.AGREGADO || d.como == Como.QUITADO ) {
      sb.append( ": " ).append( t.s( "w." + d.como.id ) );
    }
    return sb.toString();
  }

  /** Como {@link #cabecera} pero sin el «: añadido» / «: quitado» final (para tablas con una columna de cambio). */
  public static String cabeceraCorta( Diferencia d, Rotulos t ) {
    String c = cabecera( d, t );
    String fin = ": " + t.s( "w." + d.como.id );
    return c.endsWith( fin ) ? c.substring( 0, c.length() - fin.length() ) : c;
  }

  private static String marcaSalto( Propiedad p, Rotulos t, boolean quitado ) {
    String v = quitado ? p.antes : p.despues;
    if ( "enabled".equals( p.ruta ) && "N".equals( v ) ) {
      return t.s( "hop.disabled" );
    }
    if ( "error".equals( p.ruta ) && "Y".equals( v ) ) {
      return t.s( "hop.error" );
    }
    if ( "condition".equals( p.ruta ) && v != null ) {
      return t.s( "cond." + v );
    }
    return null;
  }

  /** Las líneas de una propiedad que cambió (más de una si es un valor de varias líneas). */
  public static void lineasPropiedad( Diferencia d, Propiedad p, Rotulos t, String sangria, StringBuilder sb ) {
    if ( d.que == Que.SALTO ) {
      if ( d.como == Como.CAMBIADO ) {
        sb.append( sangria ).append( saltoCambio( p, t ) ).append( '\n' );
      }
      return;
    }
    if ( ( d.que == Que.PARAMETRO || d.que == Que.ERRORES ) && d.como != Como.CAMBIADO ) {
      String v = d.como == Como.QUITADO ? p.antes : p.despues;
      sb.append( sangria ).append( etiqueta( p.ruta, t ) ).append( ": " ).append( valor( v, t ) ).append( '\n' );
      return;
    }
    if ( d.que == Que.NOTA && d.como != Como.CAMBIADO ) {
      return; // el texto ya está en la cabecera
    }
    String et = etiqueta( p.ruta, t );
    if ( Propiedad.ELEMENTO_AGREGADO.equals( p.clase ) ) {
      sb.append( sangria ).append( et ).append( ": " ).append( t.s( "w.added" ) )
        .append( p.despues == null || p.despues.isEmpty() ? "" : " (" + p.despues + ")" ).append( '\n' );
      return;
    }
    if ( Propiedad.ELEMENTO_QUITADO.equals( p.clase ) ) {
      sb.append( sangria ).append( et ).append( ": " ).append( t.s( "w.removed" ) )
        .append( p.antes == null || p.antes.isEmpty() ? "" : " (" + p.antes + ")" ).append( '\n' );
      return;
    }
    if ( Propiedad.ORDEN.equals( p.clase ) ) {
      sb.append( sangria ).append( et ).append( " · " ).append( t.s( "w.order" ) ).append( ": " ).append( p.antes )
        .append( " → " ).append( p.despues ).append( '\n' );
      return;
    }
    if ( "format".equals( p.ruta ) && d.que == Que.NOTA ) {
      sb.append( sangria ).append( t.s( "note.format" ) ).append( '\n' );
      return;
    }
    if ( p.secreta() ) {
      sb.append( sangria ).append( et ).append( ": " ).append( t.s( secreto( p ) ) ).append( '\n' );
      return;
    }
    if ( multilinea( p.antes ) || multilinea( p.despues ) ) {
      sb.append( sangria ).append( et ).append( ':' ).append( '\n' );
      for ( String l : lineasDiff( p.antes, p.despues, t ) ) {
        sb.append( sangria ).append( "  " ).append( l ).append( '\n' );
      }
      return;
    }
    sb.append( sangria ).append( et ).append( ": " ).append( corto( valor( p.antes, t ) ) ).append( " → " )
      .append( corto( valor( p.despues, t ) ) ).append( '\n' );
  }

  /** La clave del texto de un secreto que cambió (o que se agregó o se quitó). */
  static String secreto( Propiedad p ) {
    if ( p.antes == null || p.antes.isEmpty() ) {
      return "secret.set";
    }
    if ( p.despues == null || p.despues.isEmpty() ) {
      return "secret.cleared";
    }
    return "secret.changed";
  }

  static String saltoCambio( Propiedad p, Rotulos t ) {
    if ( "enabled".equals( p.ruta ) ) {
      return t.s( "Y".equals( p.despues ) ? "hop.now_enabled" : "hop.now_disabled" );
    }
    if ( "error".equals( p.ruta ) ) {
      return t.s( "Y".equals( p.despues ) ? "hop.now_error" : "hop.no_error" );
    }
    if ( "condition".equals( p.ruta ) ) {
      return t.s( "w.condition" ) + ": " + t.s( "cond." + p.antes ) + " → " + t.s( "cond." + p.despues );
    }
    return p.ruta;
  }

  /** «fields › saldo › type»; las rutas conocidas, con su nombre traducido (condition, position, type...). */
  public static String etiqueta( String ruta, Rotulos t ) {
    if ( ruta == null ) {
      return "";
    }
    String r = ruta;
    if ( r.startsWith( "compare/" ) ) {
      r = r.substring( "compare/".length() );
    }
    if ( t.tiene( "p." + r ) ) {
      return t.s( "p." + r );
    }
    String[] seg = r.split( "/" );
    StringBuilder sb = new StringBuilder();
    for ( String s : seg ) {
      int i = s.indexOf( '[' );
      String parte = i >= 0 && s.endsWith( "]" ) ? s.substring( i + 1, s.length() - 1 ) : s;
      if ( i >= 0 && parte.matches( "\\d+" ) ) {
        parte = s.substring( 0, i ) + " " + parte;
      }
      sb.append( sb.length() == 0 ? "" : " › " ).append( parte );
    }
    return sb.toString();
  }

  /** Un valor para mostrar: (no estaba), (vacío), el secreto oculto, o el valor. */
  static String valor( String v, Rotulos t ) {
    if ( v == null ) {
      return t.s( "w.unset" );
    }
    if ( Aplanar.esSecreto( v ) ) {
      return t.s( "secret.hidden" );
    }
    if ( v.isEmpty() ) {
      return t.s( "w.empty" );
    }
    return v;
  }

  static String corto( String v ) {
    String x = v.replace( '\n', ' ' );
    return x.length() > MAX_VALOR ? x.substring( 0, MAX_VALOR - 1 ) + "…" : x;
  }

  static boolean multilinea( String v ) {
    return v != null && v.indexOf( '\n' ) >= 0 && !Aplanar.esSecreto( v );
  }

  /** Diff por líneas de un valor de varias líneas (SQL, scripts, notas): "- línea", "+ línea", "  línea". */
  static List<String> lineasDiff( String a, String b, Rotulos t ) {
    String[] la = a == null || a.isEmpty() ? new String[ 0 ] : a.split( "\n", -1 );
    String[] lb = b == null || b.isEmpty() ? new String[ 0 ] : b.split( "\n", -1 );
    List<String> out = new ArrayList<String>();
    if ( la.length > MAX_LINEAS || lb.length > MAX_LINEAS ) {
      out.add( t.f( "lines_changed", la.length, lb.length ) );
      return out;
    }
    int[][] lcs = new int[ la.length + 1 ][ lb.length + 1 ];
    for ( int i = la.length - 1; i >= 0; i-- ) {
      for ( int j = lb.length - 1; j >= 0; j-- ) {
        lcs[ i ][ j ] = la[ i ].equals( lb[ j ] ) ? lcs[ i + 1 ][ j + 1 ] + 1 : Math.max( lcs[ i + 1 ][ j ],
          lcs[ i ][ j + 1 ] );
      }
    }
    int i = 0;
    int j = 0;
    while ( i < la.length || j < lb.length ) {
      if ( i < la.length && j < lb.length && la[ i ].equals( lb[ j ] ) ) {
        out.add( "  " + la[ i ] );
        i++;
        j++;
      } else if ( j < lb.length && ( i == la.length || lcs[ i ][ j + 1 ] >= lcs[ i + 1 ][ j ] ) ) {
        out.add( "+ " + lb[ j++ ] );
      } else {
        out.add( "- " + la[ i++ ] );
      }
    }
    return out;
  }

  // ================================================================ Markdown

  public static String markdown( ResultadoDiff r, String idioma ) {
    Rotulos t = rotulos( idioma );
    StringBuilder sb = new StringBuilder();
    sb.append( "### " ).append( md( titulo( r, t ) ) ).append( "\n\n" );
    if ( r.etiquetaAntes != null || r.etiquetaDespues != null ) {
      sb.append( md( t.f( "before_after", nn( r.etiquetaAntes ), nn( r.etiquetaDespues ) ) ) ).append( "\n\n" );
    }
    if ( r.iguales() ) {
      sb.append( md( t.s( r.ignorarPosiciones ? "identical_positions" : "identical" ) ) ).append( '\n' );
      return sb.toString();
    }
    sb.append( "**" ).append( md( t.s( "w.summary" ) ) ).append( ":** " ).append( md( resumen( r, t ) ) )
      .append( "\n" );
    for ( Que q : ORDEN ) {
      List<Diferencia> ds = r.de( q );
      if ( ds.isEmpty() ) {
        continue;
      }
      sb.append( "\n#### " ).append( md( t.s( "sec." + q.id ) ) ).append( "\n\n" );
      for ( Diferencia d : ds ) {
        sb.append( "- **" ).append( d.como.simbolo ).append( "** " ).append( md( cabecera( d, t ) ) ).append( '\n' );
        for ( Propiedad p : d.propiedades ) {
          StringBuilder linea = new StringBuilder();
          if ( !p.secreta() && ( multilinea( p.antes ) || multilinea( p.despues ) ) && d.que != Que.NOTA ) {
            sb.append( "  - " ).append( md( etiqueta( p.ruta, t ) ) ).append( ":\n\n    ```diff\n" );
            for ( String l : lineasDiff( p.antes, p.despues, t ) ) {
              sb.append( "    " ).append( l.startsWith( "  " ) ? " " + l.substring( 2 ) : l.charAt( 0 ) + l
                .substring( 2 ) ).append( '\n' );
            }
            sb.append( "    ```\n" );
            continue;
          }
          lineasPropiedad( d, p, t, "", linea );
          for ( String l : linea.toString().split( "\n" ) ) {
            if ( !l.trim().isEmpty() ) {
              sb.append( "  - " ).append( mdValores( l ) ).append( '\n' );
            }
          }
        }
      }
    }
    return sb.toString();
  }

  /** Escapa lo que Markdown interpretaría en un nombre. */
  static String md( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for ( char c : s.toCharArray() ) {
      if ( "\\`*_[]<>|#".indexOf( c ) >= 0 ) {
        sb.append( '\\' );
      }
      sb.append( c );
    }
    return sb.toString();
  }

  /** «etiqueta: a → b» con los valores en código. */
  static String mdValores( String linea ) {
    int dos = linea.indexOf( ": " );
    if ( dos < 0 ) {
      return md( linea );
    }
    String et = linea.substring( 0, dos );
    String resto = linea.substring( dos + 2 );
    int flecha = resto.indexOf( " → " );
    if ( flecha < 0 ) {
      return md( et ) + ": " + codigo( resto );
    }
    return md( et ) + ": " + codigo( resto.substring( 0, flecha ) ) + " → " + codigo( resto.substring( flecha + 3 ) );
  }

  static String codigo( String v ) {
    if ( v.startsWith( "(" ) && v.endsWith( ")" ) ) {
      return "_" + md( v ) + "_";
    }
    String cerca = v.contains( "`" ) ? "`` " : "`";
    String lejos = v.contains( "`" ) ? " ``" : "`";
    return cerca + v + lejos;
  }

  // ================================================================ JSON

  /** El diff como JSON (sin valores secretos: solo "secret": true). */
  public static String json( ResultadoDiff r, String idioma ) {
    Rotulos t = rotulos( idioma );
    Json j = new Json();
    j.abrir();
    j.campo( "document_type", r.tipo );
    j.campo( "identical", r.iguales() );
    j.campo( "ignore_positions", r.ignorarPosiciones );
    j.nombre( "before" ).abrir().campo( "name", r.nombreAntes ).campo( "source", r.etiquetaAntes ).cerrar();
    j.nombre( "after" ).abrir().campo( "name", r.nombreDespues ).campo( "source", r.etiquetaDespues ).cerrar();
    j.nombre( "summary" ).abrir();
    for ( Que q : ORDEN ) {
      boolean hay = r.cuenta( q, null ) > 0;
      if ( !hay ) {
        continue;
      }
      j.nombre( q.id ).abrir();
      for ( Como c : Como.values() ) {
        int n = r.cuenta( q, c );
        if ( n > 0 ) {
          j.campo( c.id, n );
        }
      }
      j.cerrar();
    }
    j.cerrar();
    j.campo( "summary_text", resumen( r, t ) );
    j.nombre( "changes" ).abrirLista();
    for ( Que q : ORDEN ) {
      for ( Diferencia d : r.de( q ) ) {
        j.abrir();
        j.campo( "kind", d.que.id ).campo( "change", d.como.id ).campo( "name", d.nombre );
        if ( d.nombreAntes != null ) {
          j.campo( "old_name", d.nombreAntes );
        }
        if ( d.tipo != null && !d.tipo.isEmpty() ) {
          j.campo( "type", d.tipo );
        }
        if ( d.desde != null ) {
          j.campo( "from", d.desde ).campo( "to", d.hasta );
        }
        if ( d.posAntes != null && !r.ignorarPosiciones || d.posAntes != null && d.como == Como.QUITADO ) {
          j.nombre( "position_before" ).abrirLista().valor( d.posAntes[ 0 ] ).valor( d.posAntes[ 1 ] ).cerrarLista();
        }
        if ( d.posDespues != null && !r.ignorarPosiciones || d.posDespues != null && d.como == Como.AGREGADO ) {
          j.nombre( "position_after" ).abrirLista().valor( d.posDespues[ 0 ] ).valor( d.posDespues[ 1 ] )
            .cerrarLista();
        }
        if ( !d.propiedades.isEmpty() ) {
          j.nombre( "properties" ).abrirLista();
          for ( Propiedad p : d.propiedades ) {
            j.abrir().campo( "property", p.ruta ).campo( "label", etiqueta( p.ruta, t ) );
            if ( !Propiedad.VALOR.equals( p.clase ) ) {
              j.campo( "kind", p.clase );
            }
            if ( p.secreta() ) {
              j.campo( "secret", true ).campo( "change", secreto( p ).substring( "secret.".length() ) );
            } else {
              j.campo( "before", p.antes ).campo( "after", p.despues );
            }
            j.cerrar();
          }
          j.cerrarLista();
        }
        j.campo( "text", d.como.simbolo + " " + cabecera( d, t ) );
        j.cerrar();
      }
    }
    j.cerrarLista();
    j.cerrar();
    return j.toString();
  }

  // ================================================================ util

  static String nn( String s ) {
    return s == null ? "" : s;
  }

  static String unir( List<String> l, String sep ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      if ( s == null ) {
        continue;
      }
      sb.append( sb.length() == 0 ? "" : sep ).append( s );
    }
    return sb.toString();
  }

  /** Un escritor de JSON mínimo (sin Jackson: la línea de comandos corre solo con el jar del plugin). */
  static final class Json {
    private final StringBuilder sb = new StringBuilder();
    /** true si lo próximo que se escribe en el nivel actual necesita coma. */
    private final List<Boolean> coma = new ArrayList<Boolean>();
    private boolean despuesDeNombre;

    private void separar() {
      if ( despuesDeNombre ) {
        despuesDeNombre = false;
        return;
      }
      if ( !coma.isEmpty() ) {
        int i = coma.size() - 1;
        if ( coma.get( i ) ) {
          sb.append( ',' );
        }
        coma.set( i, true );
      }
    }

    Json abrir() {
      separar();
      sb.append( '{' );
      coma.add( false );
      return this;
    }

    Json cerrar() {
      sb.append( '}' );
      coma.remove( coma.size() - 1 );
      return this;
    }

    Json abrirLista() {
      separar();
      sb.append( '[' );
      coma.add( false );
      return this;
    }

    Json cerrarLista() {
      sb.append( ']' );
      coma.remove( coma.size() - 1 );
      return this;
    }

    Json nombre( String n ) {
      separar();
      cadena( n );
      sb.append( ':' );
      despuesDeNombre = true;
      return this;
    }

    Json campo( String n, String v ) {
      nombre( n );
      return valor( v );
    }

    Json campo( String n, boolean v ) {
      nombre( n );
      separar();
      sb.append( v );
      return this;
    }

    Json campo( String n, int v ) {
      nombre( n );
      return valor( v );
    }

    Json valor( int v ) {
      separar();
      sb.append( v );
      return this;
    }

    /** Un valor ya escrito como JSON (el de {@link Salidas#json} de un archivo). */
    Json crudo( String json ) {
      separar();
      sb.append( json.trim() );
      return this;
    }

    Json valor( String v ) {
      separar();
      if ( v == null ) {
        sb.append( "null" );
      } else {
        cadena( v );
      }
      return this;
    }

    private void cadena( String s ) {
      sb.append( '"' );
      for ( int i = 0; i < s.length(); i++ ) {
        char c = s.charAt( i );
        switch ( c ) {
          case '"':
            sb.append( "\\\"" );
            break;
          case '\\':
            sb.append( "\\\\" );
            break;
          case '\n':
            sb.append( "\\n" );
            break;
          case '\r':
            sb.append( "\\r" );
            break;
          case '\t':
            sb.append( "\\t" );
            break;
          default:
            if ( c < 0x20 ) {
              sb.append( String.format( Locale.ROOT, "\\u%04x", (int) c ) );
            } else {
              sb.append( c );
            }
        }
      }
      sb.append( '"' );
    }

    @Override
    public String toString() {
      return sb.toString();
    }
  }
}
