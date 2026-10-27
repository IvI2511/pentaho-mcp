package io.github.ivi2511.pentahomcp.traductor;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Escribe un {@link Programa} como notebook de Jupyter (nbformat 4): una celda de texto con el título y el estado,
 * una de código con el preámbulo y, por cada step, una celda de texto (qué hace, notas) y una de código (el bloque y
 * la vista previa de su DataFrame).
 */
public final class EscritorIpynb {

  private EscritorIpynb() {
  }

  public static String escribir( Programa p ) throws Exception {
    ObjectMapper om = new ObjectMapper().enable( SerializationFeature.INDENT_OUTPUT );
    ObjectNode nb = om.createObjectNode();
    ArrayNode celdas = nb.putArray( "cells" );
    CtxTrad c = p.ctx;
    List<String> pre = EscritorPy.preambulo( p );
    texto( celdas, "# " + p.nombre + "\n\n" + EscritorPy.cabecera( p ).replace( "\"\"\"", "" ).trim() );
    codigo( celdas, String.join( "\n", pre ) );
    int i = 0;
    for ( Bloque k : p.bloques ) {
      i++;
      StringBuilder t = new StringBuilder( "## " + i + ". " + k.paso + "\n\n`" + k.tipo + "`" );
      if ( k.estado != Estado.EXACTO ) {
        t.append( " · **" ).append( k.estado.id() ).append( "**" );
      }
      if ( k.explicacion != null ) {
        t.append( "\n\n" ).append( k.explicacion );
      }
      for ( String n : k.notas ) {
        t.append( "\n\n> " ).append( c.t( "Nota: ", "Note: ", "Nota: " ) ).append( n );
      }
      texto( celdas, t.toString() );
      StringBuilder cod = new StringBuilder( String.join( "\n", k.codigo ) );
      if ( k.vista != null ) {
        cod.append( "\n" ).append( c.pandas() ? k.vista + ".head(10)" : k.vista + ".show(10)" );
      }
      codigo( celdas, cod.toString() );
    }
    ObjectNode meta = nb.putObject( "metadata" );
    ObjectNode ks = meta.putObject( "kernelspec" );
    ks.put( "name", "python3" ).put( "display_name", "Python 3" ).put( "language", "python" );
    meta.putObject( "language_info" ).put( "name", "python" );
    nb.put( "nbformat", 4 ).put( "nbformat_minor", 5 );
    return om.writeValueAsString( nb );
  }

  static void texto( ArrayNode celdas, String s ) {
    ObjectNode c = celdas.addObject();
    c.put( "cell_type", "markdown" );
    c.putObject( "metadata" );
    lineas( c.putArray( "source" ), s );
  }

  static void codigo( ArrayNode celdas, String s ) {
    ObjectNode c = celdas.addObject();
    c.put( "cell_type", "code" );
    c.putNull( "execution_count" );
    c.putObject( "metadata" );
    c.putArray( "outputs" );
    lineas( c.putArray( "source" ), s );
  }

  private static void lineas( ArrayNode a, String s ) {
    List<String> l = Arrays.asList( s.split( "\n", -1 ) );
    for ( int i = 0; i < l.size(); i++ ) {
      a.add( i < l.size() - 1 ? l.get( i ) + "\n" : l.get( i ) );
    }
  }
}
