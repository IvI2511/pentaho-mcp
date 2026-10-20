package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.Date;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Seccion cover (diseno §2.3): logo del tema, ficha del documento (archivo, tipo, version, creado,
 * modificado), identidad del usuario (autor, institucion, curso, contacto) y la descripcion. No cuesta
 * nada: solo lee getters de la copia y el tema.
 *
 * @dueño WP2
 */
final class SecPortada implements Recolector {

  @Override
  public String id() {
    return SeccionId.PORTADA;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos r = ctx.rotulos( InformeServicio.AREA );
    Captura c = ctx.captura();
    AbstractMeta m = c.copia;
    Tema tema = ctx.tema();
    boolean job = m instanceof JobMeta;
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), ctx.idioma() ) );
    ObjectNode datos = Txt.obj();
    datos.put( "section", id() );

    if ( tema != null && tema.logoDatos != null && tema.logoDatos.length > 0 ) {
      int[] wh = InformeServicio.medidas( tema.logoDatos );
      String mime = tema.logoMime != null ? tema.logoMime : ( tema.logoDatos.length > 1
        && ( tema.logoDatos[ 0 ] & 0xFF ) == 0xFF ? Imagen.JPG : Imagen.PNG );
      Imagen logo = new Imagen( mime, tema.logoDatos, wh[ 0 ], wh[ 1 ], 1f );
      logo.id = "cover-logo";
      logo.alt = Txt.vacio( tema.identidad == null ? null : tema.identidad.institucion ) != null
        ? tema.identidad.institucion : r.s( "portada.logo_alt" );
      s.con( logo );
    }

    String tipo = r.s( job ? "tipo.job" : "tipo.trans" );
    String nombre = c.nombre != null ? c.nombre : Docs.nombre( m );
    String archivo = c.archivo != null ? new File( c.archivo ).getName() : r.s( "sin_guardar" );
    String version = Txt.vacio( job ? ( (JobMeta) m ).getJobversion() : ( (TransMeta) m ).getTransversion() );

    Lista l = new Lista();
    l.id = "cover-datos";
    l.items.add( r.f( "portada.documento", archivo ) );
    l.items.add( r.f( "portada.tipo", tipo ) );
    if ( tema != null && tema.identidad != null ) {
      agregar( l, r, "portada.autor", tema.identidad.autor );
      agregar( l, r, "portada.institucion", tema.identidad.institucion );
      agregar( l, r, "portada.curso", tema.identidad.curso );
      agregar( l, r, "portada.contacto", tema.identidad.contacto );
      Txt.poner( datos, "author", Txt.vacio( tema.identidad.autor ) );
      Txt.poner( datos, "institution", Txt.vacio( tema.identidad.institucion ) );
      Txt.poner( datos, "course", Txt.vacio( tema.identidad.curso ) );
    }
    agregar( l, r, "portada.version", version );
    quien( l, r, "portada.creado", m.getCreatedDate(), m.getCreatedUser() );
    quien( l, r, "portada.modificado", m.getModifiedDate(), m.getModifiedUser() );
    // la fecha de generacion la pone cada formato en su portada (banda del HTML, PPTX, MD): repetirla en
    // la lista la mostraba dos veces; queda en los datos
    Txt.poner( datos, "captured", Txt.fecha( c.capturado != null ? c.capturado : new Date() ) );
    s.con( l );

    String desc = Txt.vacio( m.getDescription() );
    String ext = Txt.vacio( m.getExtendedDescription() );
    StringBuilder texto = new StringBuilder();
    if ( desc != null ) {
      texto.append( desc.trim() );
    }
    if ( ext != null && ( desc == null || !ext.trim().equals( desc.trim() ) ) ) {
      texto.append( texto.length() == 0 ? "" : "\n\n" ).append( ext.trim() );
    }
    if ( texto.length() > 0 ) {
      Parrafo p = new Parrafo( texto.toString() );
      p.id = "cover-descripcion";
      s.con( p );
    }

    s.resumen = version == null ? r.f( "portada.resumen", tipo, nombre ) : r.f( "portada.resumen.version", tipo,
      nombre, version );
    Txt.poner( datos, "name", nombre );
    datos.put( "type", c.tipo );
    Txt.poner( datos, "file", archivo );
    Txt.poner( datos, "version", version );
    Txt.poner( datos, "created_user", usuario( m.getCreatedUser() ) );
    Txt.poner( datos, "created_date", Txt.fecha( m.getCreatedDate() ) );
    Txt.poner( datos, "modified_user", usuario( m.getModifiedUser() ) );
    Txt.poner( datos, "modified_date", Txt.fecha( m.getModifiedDate() ) );
    Txt.poner( datos, "description", desc );
    Txt.poner( datos, "extended_description", ext );
    datos.put( "unsaved_changes", c.sinGuardar );
    s.datos = datos;
    return s;
  }

  private static void agregar( Lista l, Rotulos r, String clave, String valor ) {
    if ( Txt.vacio( valor ) != null ) {
      l.items.add( r.f( clave, valor.trim() ) );
    }
  }

  /** "Creado: 2026-10-20 14:32 por admin"; Kettle pone "-" cuando no sabe el usuario. */
  private static void quien( Lista l, Rotulos r, String clave, Date fecha, String usuario ) {
    String f = Txt.fecha( fecha );
    String u = usuario( usuario );
    if ( f == null ) {
      return;
    }
    l.items.add( u == null ? r.f( clave + "_fecha", f ) : r.f( clave, f, u ) );
  }

  private static String usuario( String u ) {
    String v = Txt.vacio( u );
    return v == null || "-".equals( v.trim() ) ? null : v.trim();
  }
}
