package io.github.ivi2511.pentahomcp.informes;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Capitulo;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Junta varios informes ya armados en uno combinado (un capitulo por documento). Funcion pura sobre el
 * modelo: no conoce Kettle ni SWT, asi la prueban las pruebas de renderizadores.
 * <ul>
 * <li>Parte general (capitulo null): portada del conjunto (titulo, identidad del tema) y la seccion
 * summary «Documentos del informe» con una tabla: n.º, documento, tipo, archivo, secciones y estado.</li>
 * <li>Cada parte: copia profunda; sus secciones llevan capitulo = "dN" y sus ids de bloque (y de la imagen
 * de un diagrama) el prefijo "dN-": anclas HTML, nombres de archivo del Markdown y claves del pre-render
 * quedan unicos.</li>
 * <li>Los avisos de cada parte van a su Capitulo, no a la portada general.</li>
 * </ul>
 *
 * @dueño M
 */
public final class Combinar {

  /** Area de Rotulos con los textos del informe combinado (combinado_*.properties). */
  static final String AREA = "combinado";

  private Combinar() {
  }

  /**
   * @param partes los informes de cada documento, en el orden del informe (al menos 2)
   * @param titulo el titulo pedido; null = los nombres ("Ejercicio 1 y Ejercicio 2") o "Informe de N documentos"
   */
  public static Informe combinar( List<Informe> partes, String titulo, String idioma, Tema tema ) {
    if ( partes == null || partes.size() < 2 ) {
      throw new IllegalArgumentException( "Hacen falta al menos dos informes" );
    }
    String i = Rotulos.normalizar( idioma != null ? idioma : partes.get( 0 ).idioma );
    Rotulos r = Rotulos.de( AREA, i );
    Informe out = new Informe();
    out.idioma = i;
    out.tipoDoc = Informe.COMPILACION;
    out.generado = ZonedDateTime.now().truncatedTo( ChronoUnit.SECONDS ).format( DateTimeFormatter.ISO_OFFSET_DATE_TIME );
    out.rotulos.putAll( partes.get( 0 ).rotulos );
    int trans = 0;
    int jobs = 0;
    List<String> nombres = new ArrayList<>();
    for ( Informe p : partes ) {
      nombres.add( p.nombreDoc != null ? p.nombreDoc : p.titulo );
      if ( Informe.JOB.equals( p.tipoDoc ) ) {
        jobs++;
      } else {
        trans++;
      }
    }
    out.titulo = titulo != null && !titulo.trim().isEmpty() ? titulo.trim() : nombres.size() <= 3 ? unir( r, nombres )
      : r.f( "combinado.titulo", partes.size() );
    out.nombreDoc = out.titulo;
    out.subtitulo = r.f( "combinado.subtitulo", cuenta( r, "combinado.trans", trans ), cuenta( r, "combinado.jobs",
      jobs ) ).replace( " ,", "" );
    if ( trans == 0 || jobs == 0 ) {
      out.subtitulo = cuenta( r, trans == 0 ? "combinado.jobs" : "combinado.trans", trans == 0 ? jobs : trans );
    }

    // portada general: la identidad del tema (el logo lo pone cada renderizador desde el tema)
    Seccion portada = new Seccion( SeccionId.PORTADA, SeccionId.titulo( SeccionId.PORTADA, i ) );
    Lista datos = new Lista();
    datos.id = "cover-datos";
    if ( tema != null && tema.identidad != null ) {
      agregar( datos, r, "combinado.autor", tema.identidad.autor );
      agregar( datos, r, "combinado.institucion", tema.identidad.institucion );
      agregar( datos, r, "combinado.curso", tema.identidad.curso );
      agregar( datos, r, "combinado.contacto", tema.identidad.contacto );
    }
    datos.items.add( r.f( "combinado.documentos", unir( r, nombres ) ) );
    portada.con( datos );
    out.secciones.add( portada );

    Seccion docs = new Seccion( SeccionId.RESUMEN, r.s( "combinado.resumen.titulo" ) );
    docs.resumen = r.f( "combinado.resumen", partes.size(), out.subtitulo );
    Tabla t = new Tabla( r.s( "combinado.col.n" ), r.s( "combinado.col.documento" ), r.s( "combinado.col.tipo" ), r.s(
      "combinado.col.archivo" ), r.s( "combinado.col.estado" ) );
    t.id = "combined-documents";
    t.alineacion = new ArrayList<>( java.util.Arrays.asList( "r", "l", "l", "l", "l" ) );
    Lista resumenes = new Lista();
    resumenes.id = "combined-summaries";
    resumenes.titulo = r.s( "combinado.resumenes" );
    out.secciones.add( docs );

    int n = 0;
    for ( Informe p : partes ) {
      n++;
      String id = "d" + n;
      Informe c = p.copia();
      Capitulo cap = new Capitulo( id, c.titulo );
      cap.subtitulo = c.subtitulo;
      cap.tipoDoc = c.tipoDoc;
      cap.nombreDoc = c.nombreDoc;
      cap.archivo = c.archivo;
      cap.avisos.addAll( c.avisos );
      out.capitulos.add( cap );
      int ok = 0;
      int mal = 0;
      String resumen = null;
      for ( Seccion s : c.secciones ) {
        if ( s == null ) {
          continue;
        }
        s.capitulo = id;
        for ( Bloque b : s.bloques ) {
          if ( b == null ) {
            continue;
          }
          b.id = b.id == null ? null : id + "-" + b.id;
          if ( b instanceof Diagrama && ( (Diagrama) b ).imagen != null && ( (Diagrama) b ).imagen.id != null ) {
            ( (Diagrama) b ).imagen.id = id + "-" + ( (Diagrama) b ).imagen.id;
          }
        }
        if ( Seccion.OK.equals( s.estado ) ) {
          ok++;
        } else if ( Seccion.PARCIAL.equals( s.estado ) || Seccion.ERROR.equals( s.estado ) ) {
          mal++;
        }
        if ( SeccionId.RESUMEN.equals( s.id ) && s.resumen != null ) {
          resumen = s.resumen;
        }
        out.secciones.add( s );
      }
      String tipo = Rotulos.de( "render", i ).s( Informe.JOB.equals( c.tipoDoc ) ? "doc_job" : "doc_transformation" );
      String archivo = c.archivo == null ? "" : new java.io.File( c.archivo ).getName();
      String estado = mal == 0 ? r.f( "combinado.estado.ok", ok ) : r.f( "combinado.estado.mal", ok, mal );
      t.fila( String.valueOf( n ), cap.titulo, tipo, archivo, estado );
      if ( resumen != null ) {
        resumenes.items.add( "**" + cap.titulo + ".** " + resumen );
      }
    }
    docs.con( t );
    if ( !resumenes.items.isEmpty() ) {
      docs.con( resumenes );
    }
    return out;
  }

  private static void agregar( Lista l, Rotulos r, String clave, String valor ) {
    if ( valor != null && !valor.trim().isEmpty() ) {
      l.items.add( r.f( clave, valor.trim() ) );
    }
  }

  private static String cuenta( Rotulos r, String base, int n ) {
    return n == 0 ? "" : r.f( base + ( n == 1 ? ".1" : ".n" ), n );
  }

  /** "a", "a y b", "a, b y c". */
  static String unir( Rotulos r, List<String> l ) {
    if ( l.size() == 1 ) {
      return l.get( 0 );
    }
    StringBuilder sb = new StringBuilder();
    for ( int k = 0; k < l.size() - 1; k++ ) {
      sb.append( k == 0 ? "" : ", " ).append( l.get( k ) );
    }
    return sb.append( ' ' ).append( r.s( "y" ) ).append( ' ' ).append( l.get( l.size() - 1 ) ).toString();
  }
}
