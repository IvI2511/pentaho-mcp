package io.github.ivi2511.pentahomcp;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.InformeServicio.Txt;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Seccion validation (diseno §2.3): RunMotor.validar sobre la copia (checkSteps / checkJobEntries mas
 * los chequeos propios, lo mismo que la tool validate), con tope de {@link #MS_VALIDAR}. Indicadores
 * de errores y advertencias y una tabla con cada mensaje. Puede conectarse a la base (checkSteps).
 *
 * @dueño WP2
 */
final class SecValidacion implements Recolector {

  /** Tope de la validacion (diseno §2.5). */
  static final long MS_VALIDAR = 60000;

  @Override
  public String id() {
    return SeccionId.VALIDACION;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    return null;
  }

  /**
   * Algunos mensajes en castellano de PDI 9.4 llegan con U+FFFD en lugar de la vocal acentuada (el
   * properties de Kettle esta en Latin-1): «uno o m\uFFFDs espacios», «car\uFFFDcter». Se reparan las
   * palabras conocidas; lo demas queda como viene.
   */
  static String reparar( String texto ) {
    if ( texto == null || texto.indexOf( '\uFFFD' ) < 0 ) {
      return texto;
    }
    String[][] palabras = { { "m\uFFFDs", "más" }, { "car\uFFFDcter", "carácter" }, { "n\uFFFDmero", "número" },
      { "v\uFFFDlido", "válido" }, { "v\uFFFDlida", "válida" }, { "conexi\uFFFDn", "conexión" }, { "est\uFFFD",
        "está" }, { "informaci\uFFFDn", "información" }, { "tambi\uFFFDn", "también" }, { "b\uFFFDsqueda",
          "búsqueda" }, { "l\uFFFDnea", "línea" }, { "despu\uFFFDs", "después" }, { "s\uFFFDlo", "sólo" }, {
            "aqu\uFFFD", "aquí" }, { "a\uFFFDn", "aún" }, { "\uFFFDnico", "único" }, { "c\uFFFDdigo", "código" },
      { "par\uFFFDmetro", "parámetro" }, { "opci\uFFFDn", "opción" }, { "acci\uFFFDn", "acción" }, {
        "funci\uFFFDn", "función" }, { "versi\uFFFDn", "versión" }, { "posici\uFFFDn", "posición" } };
    String s = texto;
    for ( String[] p : palabras ) {
      s = s.replace( p[ 0 ], p[ 1 ] );
    }
    return s;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos r = ctx.rotulos( InformeServicio.AREA );
    final AbstractMeta copia = ctx.captura().copia;
    Seccion s = new Seccion( id(), SeccionId.titulo( id(), ctx.idioma() ) );
    long tope = Math.max( 1000L, Math.min( MS_VALIDAR, ctx.msRestantes() ) );
    // checkSteps se conecta (Table input...): los steps que tocarian una base de archivo que no existe
    // no se validan, porque Kettle la crearia vacia (diseno §2.4.4); la seccion queda parcial
    final Set<String> saltear = new LinkedHashSet<String>();
    String[] falta = null;
    if ( copia instanceof TransMeta ) {
      TransMeta tm = (TransMeta) copia;
      for ( StepMeta sm : tm.getSteps() ) {
        String[] f = SecDestino.baseFaltantePara( tm, sm, true );
        if ( f != null ) {
          saltear.add( sm.getName() );
          falta = falta == null ? f : falta;
        }
      }
    }
    final Rotulos rr = r;
    final String idioma = ctx.idioma();
    // los chequeos propios de Pentaho Live (reglas del linter) en el idioma del informe y sin la tool que lo
    // arregla (la tool validate la nombra y esta en ingles)
    final RunMotor.TextosChequeo textos = new RunMotor.TextosChequeo() {
      @Override
      public String texto( String clave, Object... args ) {
        return rr.f( "val.propio." + clave, args );
      }

      @Override
      public String idioma() {
        return idioma;
      }
    };
    ObjectNode v;
    try {
      v = RunMotor.enSegundoPlano( new Callable<ObjectNode>() {
        @Override
        public ObjectNode call() {
          return RunMotor.validar( copia, false, copia.getRepository(), copia.getMetaStore(), saltear, textos );
        }
      }, tope, null, "The validation" );
    } catch ( ToolError e ) {
      s.estado = Seccion.ERROR;
      s.motivo = r.f( "val.motivo.timeout", tope / 1000 );
      return s;
    }
    int errores = v.path( "errors" ).asInt();
    int advertencias = v.path( "warnings" ).asInt();
    // con steps sin validar, un 0 no prueba nada: las cuentas quedan neutras y se agrega cuantos faltan
    boolean incompleta = !saltear.isEmpty();
    int totalSteps = copia instanceof TransMeta ? ( (TransMeta) copia ).nrSteps() : 0;

    Indicadores ind = new Indicadores();
    ind.id = "validation-cuentas";
    ind.con( new Indicador( r.s( "val.ind.errores" ), String.format( r.locale(), "%,d", errores ), null,
      errores > 0 ? Indicador.ERROR : incompleta ? null : Indicador.OK ) );
    ind.con( new Indicador( r.s( "val.ind.advertencias" ), String.format( r.locale(), "%,d", advertencias ), null,
      advertencias > 0 ? Indicador.WARN : incompleta ? null : Indicador.OK ) );
    if ( incompleta ) {
      ind.con( new Indicador( r.s( "val.ind.sin_validar" ), String.format( r.locale(), "%,d", saltear.size() ),
        r.f( "val.ind.sin_validar.detalle", totalSteps ), Indicador.WARN ) );
    }
    s.con( ind );

    Tabla t = new Tabla( r.s( "val.col.donde" ), r.s( "val.col.nivel" ), r.s( "val.col.mensaje" ) );
    t.id = "validation-mensajes";
    JsonNode ps = v.path( "by_step" );
    for ( Iterator<Map.Entry<String, JsonNode>> it = ps.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      for ( JsonNode linea : e.getValue() ) {
        String texto = linea.asText();
        String nivel;
        if ( texto.startsWith( "ERROR: " ) ) {
          nivel = r.s( "val.nivel.error" );
          texto = texto.substring( 7 );
        } else if ( texto.startsWith( "WARNING: " ) ) {
          nivel = r.s( "val.nivel.adv" );
          texto = texto.substring( 9 );
        } else {
          continue; // OK y COMMENT no salen con detalle = false
        }
        t.fila( e.getKey(), nivel, reparar( texto ) );
      }
    }
    if ( !t.filas.isEmpty() ) {
      t.nota = r.s( "val.nota" );
      s.con( t );
    }

    String sinValidar = incompleta ? r.f( "val.resumen.sin_validar", saltear.size(), totalSteps ) : null;
    if ( errores + advertencias == 0 ) {
      // "Sin errores ni advertencias" solo si de verdad se valido todo
      s.resumen = incompleta ? sinValidar : r.s( "val.resumen.ok" );
    } else {
      s.resumen = r.f( "cuenta", Txt.n( r, "n.error", errores ), Txt.n( r, "n.adv", advertencias ) )
        + ( incompleta ? " " + sinValidar : "" );
    }
    ObjectNode datos = v.deepCopy();
    datos.put( "section", id() );
    if ( incompleta ) {
      s.estado = Seccion.PARCIAL;
      // la ruta relativa a la carpeta del documento (no mostrar el usuario de Windows en el informe)
      String ruta = Txt.relativa( falta[ 1 ], ctx.captura().archivo );
      s.motivo = r.f( "val.motivo.base_falta", saltear.size(), Txt.recortar( String.join( ", ", saltear ), 300 ),
        falta[ 0 ], ruta == null ? falta[ 1 ] : ruta );
      ArrayNode no = datos.putArray( "not_validated" );
      for ( String n : saltear ) {
        no.add( n );
      }
      // sin errores no quiere decir valida: esos steps no se revisaron
      if ( errores == 0 ) {
        datos.putNull( "valid" );
      } else {
        datos.put( "valid", false );
      }
      datos.put( "complete", false );
    }
    s.datos = datos;
    return s;
  }
}
