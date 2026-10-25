package io.github.ivi2511.pentahomcp;

import java.io.File;

import com.fasterxml.jackson.databind.JsonNode;

import org.pentaho.di.core.xml.XMLHandler;

import io.github.ivi2511.pentahomcp.diff.Diferencia;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Propiedad;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Que;
import io.github.ivi2511.pentahomcp.diff.Diff;
import io.github.ivi2511.pentahomcp.diff.ResultadoDiff;
import io.github.ivi2511.pentahomcp.diff.Salidas;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Sección changes (diseño E1): los cambios por significado del documento contra otra versión ({@code compare_with}
 * del pedido: "saved", "git:REV" o un archivo; si no se dice, el archivo guardado cuando hay cambios sin guardar y si
 * no la última revisión de git). Una tabla cambio / qué / detalle y, en los datos, el JSON del diff. Nunca por
 * defecto; barata (lee XML y, como mucho, corre git). Sin contraseñas.
 */
final class SecCambios implements Recolector {

  @Override
  public String id() {
    return SeccionId.CAMBIOS;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    if ( c == null || c.archivo == null || c.archivo.trim().isEmpty() ) {
      return Salidas.rotulos( idioma ).s( "rep.no_base" );
    }
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    String idioma = ctx.idioma();
    Rotulos r = Salidas.rotulos( idioma );
    Captura c = ctx.captura();
    String titulo = SeccionId.titulo( id(), idioma );
    String con = ctx.pedido() == null ? null : ctx.pedido().compararCon;
    File archivo = c.archivo == null ? null : new File( c.archivo );
    if ( con == null || con.trim().isEmpty() ) {
      con = c.sinGuardar && archivo != null && archivo.isFile() ? DiffTools.GUARDADO : "git:HEAD";
    }
    Diff.Fuente despues = Diff.Fuente.texto( XMLHandler.getXMLHeader() + c.copia.getXML(), c.sinGuardar
      ? r.s( "src.open_unsaved" ) : c.archivo != null ? c.archivo : r.s( "src.open" ) );
    DiffTools.Comparacion cmp;
    try {
      cmp = DiffTools.comparar( despues, archivo, con, true );
    } catch ( McpHttpServer.ToolError e ) {
      return Seccion.omitida( id(), titulo, r.f( "rep.error", e.getMessage() ) );
    }
    ResultadoDiff res = cmp.resultado;
    Seccion s = new Seccion( id(), titulo );
    String base = cmp.antes.etiqueta;
    if ( res.iguales() ) {
      s.resumen = r.f( "rep.identical", base );
    } else {
      s.resumen = r.f( "rep.compared", base ) + " " + Salidas.resumen( res, r ) + ".";
      Tabla t = new Tabla( r.s( "rep.col_change" ), r.s( "rep.col_what" ), r.s( "rep.col_detail" ) );
      t.id = "changes-cambios";
      t.titulo = r.s( "rep.table" );
      for ( Que q : new Que[] { Que.DOCUMENTO, Que.STEP, Que.ENTRADA, Que.SALTO, Que.ERRORES, Que.CONEXION,
        Que.PARAMETRO, Que.NOTA } ) {
        for ( Diferencia d : res.de( q ) ) {
          t.fila( d.como.simbolo + " " + r.s( "w." + d.como.id ), r.s( "sec." + q.id ) + " · " + Salidas.cabeceraCorta(
            d, r ), detalle( d, r ) );
        }
      }
      s.con( t );
    }
    s.con( new Parrafo( r.f( "before_after", base, despues.etiqueta ) ) );
    JsonNode datos = McpHttpServer.JSON.readTree( Salidas.json( res, idioma ) );
    ( (com.fasterxml.jackson.databind.node.ObjectNode) datos ).put( "section", id() );
    s.datos = datos;
    return s;
  }

  /** Las propiedades que cambiaron, en una celda. */
  static String detalle( Diferencia d, Rotulos r ) {
    StringBuilder sb = new StringBuilder();
    for ( Propiedad p : d.propiedades ) {
      StringBuilder una = new StringBuilder();
      Salidas.lineasPropiedad( d, p, r, "", una );
      String l = una.toString().trim().replace( "\n", " · " );
      if ( !l.isEmpty() ) {
        sb.append( sb.length() == 0 ? "" : "; " ).append( l );
      }
    }
    return sb.length() > 600 ? sb.substring( 0, 599 ) + "…" : sb.toString();
  }
}
