package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * El glosario del modo clase como recurso MCP (docs/modo-clase-diseno.md §7, WP5): pentaho://glossary entero,
 * pentaho://glossary/{lang}/{id} por step e idioma con los titulos de los codigos del manual de errores, y el
 * completado de {lang} e {id}. Que los textos esten completos y los codigos existan lo valida
 * tools/generador-catalogo/glosario.py; aca se prueba lo que ve el cliente.
 *
 * @dueño clase
 */
public class PruebaGlosario {

  public void pruebaListadoYArchivoEntero() throws Exception {
    McpResources r = new McpResources();
    JsonNode item = null;
    for ( JsonNode x : r.listar( null ).path( "resources" ) ) {
      if ( McpResources.GLOSARIO.equals( x.path( "uri" ).asText() ) ) {
        item = x;
      }
    }
    noNulo( item, "resources/list trae pentaho://glossary" );
    igual( "application/json", item.path( "mimeType" ).asText(), "mimeType" );
    JsonNode c = r.leer( McpResources.GLOSARIO ).path( "contents" ).path( 0 );
    igual( McpResources.GLOSARIO, c.path( "uri" ).asText(), "uri" );
    JsonNode g = McpHttpServer.JSON.readTree( c.path( "text" ).asText() );
    cierto( g.path( "steps" ).size() >= 40, "al menos 40 steps: " + g.path( "steps" ).size() );
    for ( String lang : new String[] { "en", "es", "pt" } ) {
      cierto( g.path( "steps" ).path( "CsvInput" ).path( lang ).path( "what" ).asText().length() > 10,
        "CsvInput tiene what en " + lang );
    }
  }

  public void pruebaUnStepEnUnIdioma() throws Exception {
    McpResources r = new McpResources();
    JsonNode c = r.leer( "pentaho://glossary/es/StreamLookup" ).path( "contents" ).path( 0 );
    igual( "pentaho://glossary/es/StreamLookup", c.path( "uri" ).asText(), "uri canonica" );
    JsonNode e = McpHttpServer.JSON.readTree( c.path( "text" ).asText() );
    igual( "StreamLookup", e.path( "id" ).asText(), "id" );
    igual( "es", e.path( "lang" ).asText(), "lang" );
    contiene( e.path( "analogy" ).asText(), "BUSCARV", "la analogia en castellano" );
    igual( "C18", e.path( "watch_out" ).path( 0 ).path( "code" ).asText(), "codigo del manual" );
    contiene( e.path( "watch_out" ).path( 0 ).path( "title" ).asText(), "Stream lookup",
      "titulo resuelto desde 03-errores-comunes.md" );
    igual( "pentaho://guide/common-errors", e.path( "errors_guide" ).asText(), "guia de errores" );
  }

  /** Cada codigo citado tiene su titulo en el manual (si no, el glosario apunta a un error que no existe). */
  public void pruebaTodosLosCodigosTienenTitulo() throws Exception {
    McpResources r = new McpResources();
    List<String> ids = r.idsGlosario();
    cierto( ids.size() >= 40, "ids: " + ids.size() );
    for ( String id : ids ) {
      for ( String lang : new String[] { "en", "es", "pt" } ) {
        ObjectNode e = r.glosario( lang, id );
        noNulo( e, id + "/" + lang );
        for ( JsonNode w : e.path( "watch_out" ) ) {
          cierto( w.path( "title" ).asText().length() > 0, id + ": sin titulo para " + w.path( "code" ).asText() );
        }
      }
    }
  }

  public void pruebaMayusculasYDesconocidos() throws Exception {
    McpResources r = new McpResources();
    igual( "pentaho://glossary/pt/GroupBy",
      r.leer( "pentaho://glossary/PT/groupby" ).path( "contents" ).path( 0 ).path( "uri" ).asText(),
      "idioma e id sin distinguir mayusculas" );
    nulo( r.leer( "pentaho://glossary/fr/GroupBy" ), "idioma no soportado" );
    nulo( r.leer( "pentaho://glossary/es/NoExiste" ), "id desconocido" );
    nulo( r.leer( "pentaho://glossary/es" ), "sin id" );
    nulo( r.glosario( "es", null ), "id nulo" );
  }

  public void pruebaPlantillaYCompletado() throws Exception {
    McpResources r = new McpResources();
    contiene( r.plantillas( null ).toString(), McpResources.PLANTILLA_GLOSARIO, "resources/templates/list" );
    ObjectNode p = McpHttpServer.JSON.createObjectNode();
    p.putObject( "ref" ).put( "type", "ref/resource" ).put( "uri", McpResources.PLANTILLA_GLOSARIO );
    p.putObject( "argument" ).put( "name", "lang" ).put( "value", "p" );
    igual( "pt", r.completar( p ).path( "completion" ).path( "values" ).path( 0 ).asText(), "completa lang" );
    ( (ObjectNode) p.path( "argument" ) ).put( "name", "id" ).put( "value", "filter" );
    igual( "FilterRows", r.completar( p ).path( "completion" ).path( "values" ).path( 0 ).asText(), "completa id" );
  }
}
