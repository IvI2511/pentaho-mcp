package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Seccion lesson, «Guia de la leccion» (docs/modo-clase-diseno.md §9, WP6): lo que el docente reparte despues de la
 * clase. En el orden de la leccion: titulo y objetivo, la imagen del lienzo con las notas (la de la captura, que
 * pinta ReportImagen), por paso «N · Nombre (Tipo)» con que, por que, ¡Ojo! con el codigo del manual, tip,
 * pregunta y glosario del tipo, y las filas de la vista previa capturadas en clase; al final el ejercicio (outro) y
 * la tabla de errores comunes de los tipos usados. Los datos salen de {@link LeccionGuia} (contrato LessonHandout,
 * tambien en {@code datos}). Solo bloques que ya dibujan todos los renderizadores (Parrafo, Tabla, Imagen, Aviso).
 * Nunca por defecto: se pide con sections=["lesson"]. Barata: no calcula campos ni ejecuta nada.
 *
 * @dueño clase
 */
final class SecLeccion implements Recolector {

  /** Area de Rotulos: textos/guia_&lt;idioma&gt;.properties. */
  static final String AREA = "guia";

  @Override
  public String id() {
    return SeccionId.LECCION;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    Rotulos r = Rotulos.de( AREA, idioma );
    if ( c != null && c.copia != null && !( c.copia instanceof TransMeta ) ) {
      return r.s( "motivo.job" );
    }
    return LeccionGuia.hay( c ) ? null : r.s( "motivo.sin_leccion" );
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos r = ctx.rotulos( AREA );
    String idioma = ctx.idioma();
    Captura c = ctx.captura();
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), idioma ) );
    LeccionGuia g = LeccionGuia.de( c, idioma );
    if ( g == null ) {
      return Seccion.omitida( id(), s.titulo, r.s( "motivo.sin_leccion" ) );
    }

    // titulo y objetivo
    Parrafo t = new Parrafo( r.f( "titulo", vacio( g.titulo, r.s( "sin_titulo" ) ), nivel( r, g.nivel ), Txt.n( r,
      "n.paso", g.pasos.size() ) ) );
    t.id = "lesson-titulo";
    s.con( t );
    if ( g.intro != null ) {
      Parrafo o = new Parrafo( r.f( "objetivo", g.intro ) );
      o.id = "lesson-objetivo";
      s.con( o );
    }
    avisos( s, r, g );

    // el lienzo con las notas
    Imagen img = lienzo( c, r );
    if ( img != null ) {
      s.con( img );
    }

    // los pasos
    for ( LeccionGuia.Paso x : g.pasos ) {
      s.con( paso( r, idioma, x ) );
      filas( s, r, x );
    }

    // ejercicio y errores comunes
    if ( g.outro != null ) {
      String titulo = r.s( "ejercicio.titulo" );
      Parrafo e = new Parrafo( sinRotulo( g.outro, titulo ) );
      e.id = "lesson-ejercicio";
      e.titulo = titulo;
      s.con( e );
    }
    if ( !g.errores.isEmpty() ) {
      Tabla te = new Tabla( r.s( "col.codigo" ), r.s( "col.error" ), r.s( "col.steps" ) );
      te.id = "lesson-errores";
      te.titulo = r.s( "errores.titulo" );
      for ( LeccionGuia.ErrorComun e : g.errores ) {
        te.fila( e.codigo, e.titulo == null ? "" : e.titulo.replace( "`", "" ), String.join( ", ", e.steps ) );
      }
      te.nota = r.s( "errores.nota" );
      s.con( te );
    }

    s.resumen = r.f( "resumen", vacio( g.titulo, r.s( "sin_titulo" ) ), nivel( r, g.nivel ), Txt.n( r, "n.paso",
      g.pasos.size() ), Txt.n( r, "n.vista", g.conFilas() ) );
    ObjectNode datos = g.json();
    datos.put( "section", id() );
    s.datos = datos;
    return s;
  }

  // ---------------------------------------------------------------- bloques

  private static void avisos( Seccion s, Rotulos r, LeccionGuia g ) {
    List<String> textos = new ArrayList<String>();
    if ( LeccionGuia.NOTAS.equals( g.origen ) ) {
      textos.add( r.s( "aviso.desde_notas" ) );
    } else if ( !g.completa() ) {
      textos.add( r.f( "aviso.a_medias", g.posicion, g.pasos.size() ) );
    }
    if ( g.notasOcultas > 0 && g.notasEnLienzo == 0 ) {
      textos.add( r.s( "aviso.ocultas" ) );
    }
    int i = 0;
    for ( String x : textos ) {
      Aviso a = new Aviso( Aviso.INFO, x );
      a.id = "lesson-aviso-" + ( ++i );
      s.con( a );
    }
  }

  /** Copia de la imagen del lienzo de la captura (sin duplicar los bytes), o null. */
  private static Imagen lienzo( Captura c, Rotulos r ) {
    if ( c.lienzoCrudo != null ) {
      InformeServicio.codificarLienzo( c );
    }
    Imagen l = c.lienzo;
    if ( l == null || l.datos == null ) {
      return null;
    }
    Imagen i = new Imagen( l.mime, l.datos, l.ancho, l.alto, l.escala );
    i.id = "lesson-lienzo";
    i.titulo = r.s( "lienzo.titulo" );
    i.alt = r.s( "lienzo.alt" );
    return i;
  }

  /**
   * «N · Nombre (Tipo)» con que, por que, ¡Ojo!, tip, pregunta y glosario: un Parrafo con una linea por cosa (no una
   * Lista: el PowerPoint aparta las listas que pasan de sus diapositivas por seccion, y un paso no es detalle).
   */
  private static Parrafo paso( Rotulos r, String idioma, LeccionGuia.Paso x ) {
    List<String> lineas = new ArrayList<String>();
    if ( x.que != null ) {
      lineas.add( "**" + LeccionNotas.etiqueta( "what", idioma ) + ":** " + x.que );
    }
    if ( x.porque != null ) {
      lineas.add( "**" + LeccionNotas.etiqueta( "why", idioma ) + ":** " + x.porque );
    }
    if ( x.ojo != null ) {
      // los titulos del manual traen `codigo`: el Parrafo solo entiende **negrita**
      String ojo = "**" + LeccionNotas.etiqueta( "watch_out", idioma ) + "** " + x.ojo.replace( "`", "" );
      if ( !x.codigos.isEmpty() ) {
        ojo += " " + r.f( "ojo.manual", String.join( ", ", x.codigos ) );
      }
      lineas.add( ojo );
    }
    if ( x.tip != null ) {
      lineas.add( "**" + LeccionNotas.etiqueta( "tip", idioma ) + ":** " + x.tip );
    }
    if ( x.pregunta != null ) {
      lineas.add( "**" + LeccionNotas.etiqueta( "question", idioma ) + "** " + x.pregunta );
    }
    if ( x.glosarioQue != null ) {
      String gl = "**" + r.s( "glosario" ) + ":** " + x.glosarioQue;
      if ( x.analogia != null ) {
        gl += " " + x.analogia;
      }
      lineas.add( gl );
    }
    if ( !x.dado ) {
      lineas.add( r.s( "no_dado" ) );
    }
    if ( lineas.isEmpty() ) {
      lineas.add( r.s( "sin_texto" ) );
    }
    Parrafo p = new Parrafo( String.join( "\n", lineas ) );
    p.id = "lesson-paso-" + x.n;
    p.titulo = x.n + " · " + x.step + ( x.tipo == null ? "" : " (" + x.tipo + ")" );
    return p;
  }

  /** La tabla con las filas de la vista previa del paso (o un aviso si fallo). */
  private static void filas( Seccion s, Rotulos r, LeccionGuia.Paso x ) {
    if ( x.vista == null ) {
      return;
    }
    JsonNode err = x.vista.get( "errors" );
    JsonNode filas = x.vista.get( "rows" );
    if ( ( filas == null || !filas.isArray() ) && err != null && err.isArray() && err.size() > 0 ) {
      Aviso a = new Aviso( Aviso.WARN, r.f( "filas.error", x.step, err.get( 0 ).asText() ) );
      a.id = "lesson-filas-" + x.n;
      s.con( a );
      return;
    }
    if ( filas == null || !filas.isArray() ) {
      return;
    }
    List<String> cols = new ArrayList<String>();
    for ( JsonNode f : x.vista.path( "fields" ) ) {
      cols.add( f.path( "name" ).asText( "" ) );
    }
    if ( cols.isEmpty() && filas.size() > 0 ) {
      for ( int i = 0; i < filas.get( 0 ).size(); i++ ) {
        cols.add( "#" + ( i + 1 ) );
      }
    }
    Tabla t = new Tabla( cols.toArray( new String[ 0 ] ) );
    t.id = "lesson-filas-" + x.n;
    t.titulo = Txt.n( r, "filas.titulo", filas.size() ).replace( "{step}", x.step );
    for ( JsonNode f : filas ) {
      String[] celdas = new String[ cols.size() ];
      for ( int i = 0; i < celdas.length; i++ ) {
        JsonNode v = f.get( i );
        celdas[ i ] = v == null || v.isNull() ? "" : v.asText();
      }
      t.fila( celdas );
    }
    if ( filas.size() == 0 ) {
      t.pie = r.s( "filas.vacio" );
    }
    s.con( t );
  }

  // ---------------------------------------------------------------- textos

  private static String nivel( Rotulos r, String nivel ) {
    String k = "nivel." + ( nivel == null ? "beginner" : nivel );
    return r.tiene( k ) ? r.s( k ) : nivel;
  }

  /** «Ejercicio: cambia…» bajo el titulo «Ejercicio» queda «cambia…» (Claude suele empezar el outro asi). */
  static String sinRotulo( String texto, String rotulo ) {
    String t = texto.trim();
    if ( t.length() > rotulo.length() + 1 && t.regionMatches( true, 0, rotulo, 0, rotulo.length() )
      && t.charAt( rotulo.length() ) == ':' ) {
      String resto = t.substring( rotulo.length() + 1 ).trim();
      return resto.isEmpty() ? t : Character.toUpperCase( resto.charAt( 0 ) ) + resto.substring( 1 );
    }
    return t;
  }

  private static String vacio( String s, String def ) {
    return s == null || s.trim().isEmpty() ? def : s.trim();
  }
}
