package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Sección mapping (D3): la hoja de mapeo origen-destino de la transformación, una fila por campo destino
 * ({@link Mapeo}). No va por defecto: se pide con sections=["mapping"] (o mapping_export). Con lineage_step, la hoja
 * es la de ese step; si no, la de cada step final que escribe.
 */
final class SecMapeo implements Recolector {

  @Override
  public String id() {
    return SeccionId.MAPEO;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    if ( c != null && ( c.esJob() || !( c.copia instanceof TransMeta ) ) ) {
      return Rotulos.de( InformeServicio.AREA, idioma ).s( "no_aplica.solo_trans" );
    }
    if ( c != null && ( (TransMeta) c.copia ).nrSteps() == 0 ) {
      return Rotulos.de( PlanoInforme.AREA, idioma ).s( "map.no_aplica.vacia" );
    }
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos r = ctx.rotulos( PlanoInforme.AREA );
    String titulo = SeccionId.titulo( id(), ctx.idioma() );
    String na = noAplica( ctx.captura(), ctx.idioma() );
    if ( na != null ) {
      return Seccion.omitida( id(), titulo, na );
    }
    TransMeta tm = (TransMeta) ctx.captura().copia;
    List<StepMeta> destinos = new ArrayList<StepMeta>();
    String pedido = ctx.pedido() == null ? null : ctx.pedido().linajeStep;
    if ( pedido != null && tm.findStep( pedido ) != null ) {
      destinos.add( tm.findStep( pedido ) );
    }
    List<Mapeo.Fila> filas = Mapeo.filas( tm, ctx, destinos );
    Seccion s = new Seccion( id(), titulo );
    Tabla t = Mapeo.tabla( filas, r );
    t.id = "mapping-sheet";
    t.titulo = r.s( "map.titulo_tabla" );
    t.pie = r.s( "map.pie" );
    s.con( t );
    List<String> pasos = new ArrayList<String>();
    for ( Mapeo.Fila f : filas ) {
      if ( f.tabla != null && !pasos.contains( f.tabla ) ) {
        pasos.add( f.tabla );
      }
    }
    s.resumen = filas.isEmpty() ? r.s( "map.resumen.vacio" ) : r.f( "map.resumen", filas.size(), pasos.size(),
      String.join( ", ", pasos ) );
    if ( ctx.cancelado() ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = Rotulos.de( InformeServicio.AREA, ctx.idioma() ).s( "motivo.cancelado" );
    }
    ObjectNode datos = Tools.obj();
    datos.put( "section", id() );
    datos.set( "rows", Mapeo.json( filas ) );
    s.datos = datos;
    return s;
  }
}
