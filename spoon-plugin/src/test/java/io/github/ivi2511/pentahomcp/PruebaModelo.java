package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.distinto;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Contrato del modelo (diseno §8.1, WP0): ida y vuelta Jackson de ModeloMuestra, sinBinarios sin
 * byte[], Tema contra el JSON de §4.1, Rotulos en UTF-8 con respaldo en ingles. No necesita Kettle.
 *
 * @dueño WP0
 */
public class PruebaModelo {

  /** El PNG en base64 empieza siempre asi (89 50 4E 47 0D 0A 1A 0A). */
  private static final String PNG_BASE64 = "iVBORw0KGgo";

  public void pruebaIdaYVuelta() throws Exception {
    Informe a = ModeloMuestra.crear();
    String j1 = ModeloJson.escribir( a, false );
    Informe b = ModeloJson.leer( j1, Informe.class );
    String j2 = ModeloJson.escribir( b, false );
    igual( j1, j2, "el JSON tiene que ser identico despues de leerlo y volver a escribirlo" );
    JsonNode t1 = ModeloJson.mapper().readTree( j1 );
    igual( t1, ModeloJson.mapper().readTree( ModeloJson.escribir( a, true ) ), "indentado o no, mismo arbol" );

    igual( a.secciones.size(), b.secciones.size(), "secciones" );
    for ( int i = 0; i < a.secciones.size(); i++ ) {
      Seccion sa = a.secciones.get( i );
      Seccion sb = b.secciones.get( i );
      igual( sa.id, sb.id, "id de la seccion " + i );
      igual( sa.estado, sb.estado, "estado de " + sa.id );
      igual( sa.motivo, sb.motivo, "motivo de " + sa.id );
      igual( sa.datos, sb.datos, "datos crudos de " + sa.id );
      igual( sa.bloques.size(), sb.bloques.size(), "bloques de " + sa.id );
      for ( int k = 0; k < sa.bloques.size(); k++ ) {
        igual( sa.bloques.get( k ).getClass(), sb.bloques.get( k ).getClass(), "tipo del bloque " + sa.id + "/" + k );
        igual( sa.bloques.get( k ).ia, sb.bloques.get( k ).ia, "ia del bloque " + sa.id + "/" + k );
      }
    }
    Diagrama da = (Diagrama) a.seccion( SeccionId.FLUJO ).bloques.get( 0 );
    Diagrama db = (Diagrama) b.seccion( SeccionId.FLUJO ).bloques.get( 0 );
    cierto( Arrays.equals( da.imagen.datos, db.imagen.datos ), "los bytes del lienzo vuelven iguales" );
    igual( 2f, db.imagen.escala, "escala del lienzo" );
    igual( Tabla.class, db.alternativa.getClass(), "la alternativa vuelve como Tabla" );
    igual( da.mermaid, db.mermaid, "mermaid" );
    contiene( j1, "\"tipo\":\"parrafo\"", "el tipo de bloque va en la propiedad tipo" );
    contiene( j1, PNG_BASE64, "los byte[] salen en base64" );
    noContiene( j1, ":null", "las propiedades null no salen" );
  }

  public void pruebaLeerConPropiedadesDesconocidas() throws Exception {
    String j = "{\"modelo\":1,\"futuro\":true,\"secciones\":[{\"id\":\"summary\",\"x\":1,\"bloques\":"
      + "[{\"tipo\":\"aviso\",\"nivel\":\"warn\",\"texto\":\"hola\",\"nuevo\":[1,2]}]}]}";
    Informe inf = ModeloJson.leer( j, Informe.class );
    igual( "hola", ( (Aviso) inf.secciones.get( 0 ).bloques.get( 0 ) ).texto, "lee aunque haya propiedades nuevas" );
    igual( Seccion.OK, inf.secciones.get( 0 ).estado, "estado por defecto" );
  }

  public void pruebaMuestraCompleta() throws Exception {
    Informe inf = ModeloMuestra.crear();
    Set<String> tiposEsperados = new TreeSet<>();
    for ( JsonSubTypes.Type t : Bloque.class.getAnnotation( JsonSubTypes.class ).value() ) {
      tiposEsperados.add( t.name() );
    }
    igual( 8, tiposEsperados.size(), "ocho tipos de bloque" );
    Set<String> tipos = new TreeSet<>();
    Set<String> estados = new TreeSet<>();
    Set<String> graficos = new TreeSet<>();
    Set<String> diagramas = new TreeSet<>();
    Set<String> avisos = new TreeSet<>();
    Set<String> niveles = new TreeSet<>();
    Set<String> ids = new TreeSet<>();
    boolean ia = false;
    for ( Seccion s : inf.secciones ) {
      cierto( SeccionId.valido( s.id ), "id de seccion valido: " + s.id );
      estados.add( s.estado );
      for ( Bloque b : s.bloques ) {
        JsonNode n = ModeloJson.mapper().valueToTree( b );
        tipos.add( n.get( "tipo" ).asText() );
        noNulo( b.id, "todo bloque de la muestra tiene id (" + s.id + ")" );
        cierto( ids.add( b.id ), "id de bloque repetido: " + b.id );
        ia |= b.ia;
        if ( b instanceof Grafico ) {
          graficos.add( ( (Grafico) b ).clase );
        } else if ( b instanceof Diagrama ) {
          Diagrama d = (Diagrama) b;
          diagramas.add( d.clase );
          if ( d.imagen != null ) {
            tipos.add( Bloque.IMAGEN );
          }
        } else if ( b instanceof Aviso ) {
          avisos.add( ( (Aviso) b ).nivel );
        } else if ( b instanceof Indicadores ) {
          for ( Indicador i : ( (Indicadores) b ).items ) {
            niveles.add( String.valueOf( i.nivel ) );
          }
        }
      }
    }
    igual( tiposEsperados, tipos, "la muestra usa todos los tipos de bloque" );
    igual( new TreeSet<>( Arrays.asList( Seccion.OK, Seccion.PARCIAL, Seccion.OMITIDA, Seccion.ERROR ) ), estados,
      "todos los estados" );
    igual( new TreeSet<>( Arrays.asList( Grafico.BARRAS_H, Grafico.GANTT, Grafico.LINEA ) ), graficos,
      "todas las clases de grafico" );
    cierto( diagramas.containsAll( Arrays.asList( Diagrama.FLUJO, Diagrama.LINAJE, Diagrama.ER ) ),
      "diagramas flujo, linaje y er: " + diagramas );
    igual( new TreeSet<>( Arrays.asList( Aviso.INFO, Aviso.WARN, Aviso.ERROR ) ), avisos, "niveles de aviso" );
    cierto( niveles.containsAll( Arrays.asList( "null", Indicador.OK, Indicador.WARN, Indicador.ERROR ) ),
      "niveles de indicador: " + niveles );
    cierto( ia, "algun bloque con ia = true" );
    igual( SeccionId.ORDEN.size(), inf.secciones.size(), "una seccion por id" );
    for ( int i = 0; i < SeccionId.ORDEN.size(); i++ ) {
      igual( SeccionId.ORDEN.get( i ), inf.secciones.get( i ).id, "orden de las secciones" );
    }
    Map<String, String> svg = ModeloMuestra.svgPrerender( inf );
    igual( 3, svg.size(), "un SVG de muestra por diagrama con mermaid" );
    contiene( svg.get( "lineage-diagrama" ), "<rect x=\"0\" y=\"0\"", "SVG con rect de fondo" );
    ModeloJson.mapper().readTree( "\"x\"" ); // el mapper esta vivo
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
      new org.xml.sax.InputSource( new java.io.StringReader( svg.get( "flow-diagrama" ) ) ) );
    igual( 3, ModeloMuestra.pngPrerender( inf ).size(), "un PNG de muestra por diagrama con mermaid" );
  }

  public void pruebaSinBinarios() throws Exception {
    Informe inf = ModeloMuestra.crear();
    int conBytes = contarBytes( inf, new IdentityHashMap<Object, Boolean>() );
    cierto( conBytes >= 2, "la muestra tiene imagenes (" + conBytes + ")" );
    String antes = ModeloJson.escribir( inf, false );

    Informe sin = InformeServicio.sinBinarios( inf );
    igual( 0, contarBytes( sin, new IdentityHashMap<Object, Boolean>() ), "sinBinarios no deja ningun byte[]" );
    String json = ModeloJson.escribir( sin, false );
    noContiene( json, PNG_BASE64, "sin base64 de PNG en el JSON" );
    cierto( json.length() * 2 < antes.length(), "el JSON sin binarios es mucho mas chico (" + json.length() + " vs "
      + antes.length() + ")" );
    igual( antes, ModeloJson.escribir( inf, false ), "el original no cambia" );
    igual( inf.secciones.size(), sin.secciones.size(), "mismas secciones" );
    Diagrama d = (Diagrama) sin.seccion( SeccionId.FLUJO ).bloques.get( 0 );
    nulo( d.imagen.datos, "lienzo sin bytes" );
    igual( 1600, d.imagen.ancho, "pero conserva el ancho" );
    igual( Imagen.PNG, d.imagen.mime, "y el mime" );
    nulo( InformeServicio.sinBinarios( null ), "null -> null" );
  }

  public void pruebaTema() throws Exception {
    Tema t = new Tema();
    JsonNode n = ModeloJson.mapper().valueToTree( t );
    for ( String k : new String[] { "version", "identidad", "colores", "pagina", "marca_de_agua", "rotular_ia", "salida",
      "perfil", "navegador" } ) {
      cierto( n.has( k ), "Tema serializa \"" + k + "\" como en §4.1: " + n );
    }
    igual( Tema.PRIMARIO, n.get( "colores" ).get( "primario" ).asText(), "primario por defecto" );
    cierto( n.get( "salida" ).has( "agregar_fecha" ), "salida.agregar_fecha" );
    cierto( n.get( "perfil" ).has( "mostrar_valores" ), "perfil.mostrar_valores" );

    String ejemplo = "{\"version\":1,\"identidad\":{\"autor\":\"Nombre Apellido\",\"institucion\":\"Universidad o "
      + "empresa\",\"curso\":\"Big Data I\",\"contacto\":\"\"},\"logo\":\"logo.png\",\"colores\":{\"primario\":"
      + "\"#112233\",\"acento\":\"#3F74D8\",\"secundario\":\"#1C9A80\"},\"pagina\":\"Letter\",\"idioma\":\"es\","
      + "\"marca_de_agua\":false,\"rotular_ia\":true,\"salida\":{\"carpeta\":\"\",\"formatos\":[\"html\",\"pdf\"],"
      + "\"html\":\"interactivo\",\"agregar_fecha\":true},\"secciones\":[\"cover\",\"summary\"],\"perfil\":{\"filas\":"
      + "5000,\"mostrar_valores\":false},\"navegador\":\"\",\"desconocida\":1}";
    Tema l = ModeloJson.leer( ejemplo, Tema.class );
    igual( "Nombre Apellido", l.identidad.autor, "autor" );
    igual( "#112233", l.colores.primario, "primario" );
    igual( Tema.LETTER, l.pagina, "pagina" );
    igual( false, l.marcaDeAgua, "marca_de_agua" );
    igual( true, l.salida.agregarFecha, "agregar_fecha" );
    igual( false, l.perfil.mostrarValores, "mostrar_valores" );
    igual( Arrays.asList( "cover", "summary" ), l.secciones, "secciones" );

    Tema m = ModeloMuestra.tema();
    Tema c = m.copia();
    noNulo( c.logoDatos, "la copia lleva el logo" );
    cierto( Arrays.equals( m.logoDatos, c.logoDatos ) && m.logoDatos != c.logoDatos, "logo copiado, no compartido" );
    c.colores.primario = "#000000";
    c.identidad.autor = "Otro";
    distinto( "#000000", m.colores.primario, "la copia no toca el original" );
    igual( "Nombre Apellido", m.identidad.autor, "la copia no toca el original" );
    noContiene( ModeloJson.escribir( m, false ), "logoDatos", "los bytes del logo no van al JSON" );
    contiene( Tema.PIE, "·", "el pie lleva el punto medio literal" );
  }

  public void pruebaRotulos() throws Exception {
    igual( "Linaje de campos", Rotulos.de( SeccionId.AREA, "es" ).s( SeccionId.LINAJE ), "es en UTF-8" );
    igual( "Métricas de ejecución", SeccionId.titulo( SeccionId.METRICAS, "es_AR" ), "acentos en UTF-8" );
    igual( "Dicionário de dados", SeccionId.titulo( SeccionId.DICCIONARIO, "pt" ), "pt" );
    igual( "Field lineage", SeccionId.titulo( SeccionId.LINAJE, "fr" ), "otro idioma -> en" );
    igual( "Field lineage", SeccionId.titulo( SeccionId.LINAJE, null ), "null -> en" );
    igual( "no_existe", Rotulos.de( SeccionId.AREA, "es" ).s( "no_existe" ), "clave que falta -> la clave" );
    igual( "x", Rotulos.de( "area_que_no_existe", "pt" ).s( "x" ), "area que falta -> la clave" );
    // f(): String.format con el Locale del idioma (la clave inexistente hace de patron)
    igual( "400.000 filas en 2,7 s", Rotulos.de( SeccionId.AREA, "es" ).f( "%,d filas en %.1f s", 400000, 2.7 ),
      "miles y decimales en es" );
    igual( "400,000 rows in 2.7 s", Rotulos.de( SeccionId.AREA, "en" ).f( "%,d rows in %.1f s", 400000, 2.7 ),
      "miles y decimales en en" );
    igual( "400.000 linhas", Rotulos.de( SeccionId.AREA, "pt" ).f( "%,d linhas", 400000 ), "miles en pt" );
    contiene( Rotulos.de( SeccionId.AREA, "es" ).f( "%d %d", 1 ), "%d %d", "formato roto: no lanza" );
    for ( String id : SeccionId.ORDEN ) {
      for ( String idi : new String[] { "en", "es", "pt" } ) {
        Rotulos r = Rotulos.de( SeccionId.AREA, idi );
        cierto( r.tiene( id ) && !r.s( id ).equals( id ), "titulo de " + id + " en " + idi );
      }
    }
  }

  public void pruebaContratoChico() throws Exception {
    igual( Formato.HTML_ESTATICO, Formato.deClave( "html_light" ), "clave html_light" );
    igual( Formato.MARKDOWN, Formato.deClave( "MD" ), "clave md" );
    nulo( Formato.deClave( "odt" ), "clave desconocida" );
    igual( 14, SeccionId.ORDEN.size(), "catorce secciones (quality, de la fase de calidad; lesson, del modo clase)" );
    // los recolectores de WP3-WP5 (stubs o reales) declaran un id valido
    for ( Recolector r : new Recolector[] { new SecLinaje(), new SecMetricas(), new SecPerfil(), new SecDestino() } ) {
      cierto( SeccionId.valido( r.id() ), r.getClass().getSimpleName() + ".id() valido: " + r.id() );
    }
    try {
      SeccionId.ORDEN.add( "x" );
      falla( "ORDEN tiene que ser inmodificable" );
    } catch ( UnsupportedOperationException esperado ) {
      // bien
    }
  }

  /** Cuenta los byte[] no nulos alcanzables por campos (modelo y JsonNode excluidos). */
  private static int contarBytes( Object o, Map<Object, Boolean> vistos ) throws IllegalAccessException {
    if ( o == null || vistos.containsKey( o ) || o instanceof String || o instanceof Number || o instanceof Boolean
      || o instanceof JsonNode || o instanceof Enum ) {
      return 0;
    }
    if ( o instanceof byte[] ) {
      return 1;
    }
    vistos.put( o, Boolean.TRUE );
    int n = 0;
    if ( o instanceof Collection ) {
      for ( Object x : (Collection<?>) o ) {
        n += contarBytes( x, vistos );
      }
      return n;
    }
    if ( o instanceof Map ) {
      for ( Object x : ( (Map<?, ?>) o ).values() ) {
        n += contarBytes( x, vistos );
      }
      return n;
    }
    for ( Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass() ) {
      for ( Field f : c.getDeclaredFields() ) {
        if ( Modifier.isStatic( f.getModifiers() ) || f.getType().isPrimitive() ) {
          continue;
        }
        f.setAccessible( true );
        n += contarBytes( f.get( o ), vistos );
      }
    }
    return n;
  }
}
