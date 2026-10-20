package io.github.ivi2511.pentahomcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;

/**
 * Seccion narrative («Conclusiones», diseno §1.5 y §2.3): el texto {@code Pedido.narrativa.conclusiones}
 * del usuario o de la IA, recortado a 2.000 caracteres, como un Parrafo con {@code ia} segun el origen.
 * Texto plano con poco Markdown: cada renderizador lo escapa (nunca HTML crudo). Sin texto queda
 * "omitida".
 *
 * @dueño WP2
 */
final class SecNarrativa implements Recolector {

  @Override
  public String id() {
    return SeccionId.NARRATIVA;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    String titulo = SeccionId.titulo( id(), ctx.idioma() );
    Narrativa n = ctx.pedido().narrativa;
    String texto = n == null ? null : Txt.recortar( n.conclusiones, InformeServicio.MAX_NARRATIVA );
    if ( texto == null ) {
      return Seccion.omitida( id(), titulo, ctx.rotulos( InformeServicio.AREA ).s( "nar.motivo.sin_texto" ) );
    }
    boolean ia = Narrativa.IA.equals( n.origen );
    Seccion s = new Seccion( id(), titulo );
    Parrafo p = new Parrafo( texto );
    p.id = "narrative-texto";
    p.ia = ia;
    s.con( p );
    ObjectNode datos = Txt.obj();
    datos.put( "section", id() );
    datos.put( "origin", ia ? Narrativa.IA : Narrativa.USUARIO );
    datos.put( "characters", texto.length() );
    s.datos = datos;
    return s;
  }
}
