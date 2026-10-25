package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.diff.Diferencia;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Como;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Propiedad;
import io.github.ivi2511.pentahomcp.diff.Diferencia.Que;
import io.github.ivi2511.pentahomcp.diff.Diff;
import io.github.ivi2511.pentahomcp.diff.Documento;
import io.github.ivi2511.pentahomcp.diff.ResultadoDiff;
import io.github.ivi2511.pentahomcp.diff.Salidas;
import io.github.ivi2511.pentahomcp.diff.Volcado;

/**
 * El diff semántico (diseño E1) sobre pares de versiones de los ejemplos de la skill, modificados en la prueba: sin
 * cambios, condición del Filter rows, step renombrado (y las referencias a él), posiciones, steps y saltos agregados y
 * quitados, listas de campos (tipo, agregado, orden), parámetros, manejo de errores, notas, jobs, SQL de varias
 * líneas y las contraseñas, que no salen nunca (texto, Markdown, JSON ni textconv). Sin Kettle: solo el XML.
 */
public class PruebaDiff {

  /** La carpeta de la skill pentaho-etl del repositorio (solo lectura). */
  static File skill() {
    File recursos = new File( System.getProperty( "pruebas.recursos" ) );
    File repo = recursos.getAbsoluteFile().getParentFile().getParentFile().getParentFile().getParentFile();
    File s = new File( repo, "claude/skills/pentaho-etl" );
    cierto( s.isDirectory(), "skill en " + s );
    return s;
  }

  static String ejemplo( String ruta ) throws Exception {
    return new String( Files.readAllBytes( new File( skill(), ruta ).toPath() ), StandardCharsets.UTF_8 );
  }

  /** Reemplaza (todas las apariciones) y exige que estuviera. */
  static String cambiar( String xml, String de, String a ) {
    cierto( xml.contains( de ), "el ejemplo tiene «" + de + "»" );
    return xml.replace( de, a );
  }

  static ResultadoDiff diff( String antes, String despues, boolean ignorarPos ) throws Exception {
    return Diff.comparar( Diff.Fuente.texto( antes, "antes" ), Diff.Fuente.texto( despues, "después" ),
      ignorarPos );
  }

  static Diferencia una( ResultadoDiff r, Que que, String nombre ) {
    Diferencia hallada = null;
    for ( Diferencia d : r.cambios ) {
      if ( d.que == que && d.nombre.equals( nombre ) ) {
        cierto( hallada == null, "un solo cambio para " + que + " " + nombre );
        hallada = d;
      }
    }
    noNulo( hallada, "cambio de " + que + " «" + nombre + "» en " + Salidas.texto( r, "es" ) );
    return hallada;
  }

  static Propiedad prop( Diferencia d, String ruta ) {
    for ( Propiedad p : d.propiedades ) {
      if ( p.ruta.equals( ruta ) ) {
        return p;
      }
    }
    throw new AssertionError( "sin propiedad " + ruta + " en " + d.nombre + ": " + rutas( d ) );
  }

  static List<String> rutas( Diferencia d ) {
    List<String> l = new ArrayList<String>();
    for ( Propiedad p : d.propiedades ) {
      l.add( p.ruta );
    }
    return l;
  }

  // ================================================================ transformaciones

  /** La misma versión dos veces: sin cambios, en los tres formatos. */
  public void pruebaA_SinCambios() throws Exception {
    String x = ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    ResultadoDiff r = diff( x, x, false );
    cierto( r.iguales(), "sin cambios" );
    contiene( Salidas.texto( r, "es" ), "No hay diferencias de significado.", "texto" );
    contiene( Salidas.markdown( r, "en" ), "No differences in meaning.", "markdown" );
    JsonNode j = McpHttpServer.JSON.readTree( Salidas.json( r, "en" ) );
    cierto( j.path( "identical" ).asBoolean(), "json identical" );
    igual( 0, j.path( "changes" ).size(), "json sin cambios" );
    // solo cambian modified_date y la sangría: tampoco es un cambio
    String y = cambiar( x, "<modified_date>2026/10/20 10:00:00.000</modified_date>",
      "<modified_date>2026/10/25 18:31:07.123</modified_date>" ).replace( "\n    ", "\n\t" );
    cierto( diff( x, y, false ).iguales(), "fecha de modificación y sangría no cuentan" );
  }

  /** «Filter rows: saldo >= 1000 → saldo > 1500»: la condición legible, en una sola propiedad. */
  public void pruebaB_CondicionDelFiltro() throws Exception {
    String x = ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    String y = cambiar( cambiar( x, "<function>&gt;=</function>", "<function>&gt;</function>" ),
      "<text>1000.0</text>", "<text>1500</text>" );
    ResultadoDiff r = diff( x, y, true );
    igual( 1, r.cambios.size(), "un cambio: " + Salidas.texto( r, "es" ) );
    Diferencia d = una( r, Que.STEP, "Activos con saldo" );
    igual( Como.CAMBIADO, d.como, "cambiado" );
    igual( "FilterRows", d.tipo, "tipo" );
    Propiedad p = prop( d, "compare/condition" );
    igual( "saldo >= 1000.0 AND activo = 'S'", p.antes, "antes" );
    igual( "saldo > 1500 AND activo = 'S'", p.despues, "después" );
    String t = Salidas.texto( r, "es" );
    contiene( t, "Cambios en la transformación «02_filtrar_clientes»", "título" );
    contiene( t, "~ Activos con saldo (FilterRows)", "cabecera del step" );
    contiene( t, "condición: saldo >= 1000.0 AND activo = 'S' → saldo > 1500 AND activo = 'S'", "la condición" );
    contiene( t, "Steps: 1 cambiado", "resumen" );
    String md = Salidas.markdown( r, "en" );
    contiene( md, "condition: `saldo >= 1000.0 AND activo = 'S'` → `saldo > 1500 AND activo = 'S'`", "markdown" );
    JsonNode j = McpHttpServer.JSON.readTree( Salidas.json( r, "en" ) );
    JsonNode c = j.path( "changes" ).get( 0 );
    igual( "step", c.path( "kind" ).asText(), "json kind" );
    igual( "changed", c.path( "change" ).asText(), "json change" );
    igual( "saldo > 1500 AND activo = 'S'", c.path( "properties" ).get( 0 ).path( "after" ).asText(), "json after" );
    igual( 1, j.path( "summary" ).path( "step" ).path( "changed" ).asInt(), "json summary" );
  }

  /** Un step renombrado: un solo cambio «renombrado»; los saltos y el send_true_to que lo nombran no cuentan. */
  public void pruebaC_Renombrado() throws Exception {
    String x = ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    String y = cambiar( x, "Elegir columnas", "Columnas finales" );
    ResultadoDiff r = diff( x, y, true );
    igual( 1, r.cambios.size(), "solo el renombre: " + Salidas.texto( r, "es" ) );
    Diferencia d = una( r, Que.STEP, "Columnas finales" );
    igual( Como.RENOMBRADO, d.como, "renombrado" );
    igual( "Elegir columnas", d.nombreAntes, "nombre de antes" );
    cierto( d.propiedades.isEmpty(), "sin otras propiedades: " + rutas( d ) );
    igual( "Elegir columnas", r.renombrados.get( "Columnas finales" ), "mapa de renombres" );
    contiene( Salidas.texto( r, "es" ), "» Columnas finales (SelectValues), renombrado, antes «Elegir columnas»",
      "texto" );
    // renombrado y además movido y con un campo de más: sigue siendo el mismo step
    String z = cambiar( cambiar( y, "<xloc>416</xloc>", "<xloc>600</xloc>" ), "<rename>id</rename>",
      "<rename>id_cliente</rename>" );
    ResultadoDiff r2 = diff( x, z, false );
    Diferencia d2 = una( r2, Que.STEP, "Columnas finales" );
    igual( Como.RENOMBRADO, d2.como, "renombrado con cambios" );
    igual( "id", prop( d2, "fields/field[cliente_id]/rename" ).antes, "rename antes" );
    igual( "id_cliente", prop( d2, "fields/field[cliente_id]/rename" ).despues, "rename después" );
    prop( d2, "position" );
    igual( 0, r2.cuenta( Que.SALTO, null ), "los saltos siguen iguales" );
  }

  /** Un Dummy quitado y otro agregado en otro lado, con otros vecinos: no es un renombre. */
  public void pruebaD_NoEsRenombre() throws Exception {
    String x = ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    // el Dummy "Descartados" pasa a llamarse "Auditoría" y cuelga de otro step, lejos
    String y = cambiar( x, "<send_false_to>Descartados</send_false_to>", "<send_false_to/>" );
    y = cambiar( y, "<from>Activos con saldo</from>\n      <to>Descartados</to>",
      "<from>Leer clientes</from>\n      <to>Auditoría</to>" );
    y = cambiar( y, "<name>Descartados</name>", "<name>Auditoría</name>" );
    y = y.replaceFirst( "(<name>Auditoría</name>(?s:.*?))<xloc>\\d+</xloc>\\s*<yloc>\\d+</yloc>",
      "$1<xloc>900</xloc>\n      <yloc>700</yloc>" );
    ResultadoDiff r = diff( x, y, true );
    igual( Como.AGREGADO, una( r, Que.STEP, "Auditoría" ).como, "agregado: " + Salidas.texto( r, "es" ) );
    igual( Como.QUITADO, una( r, Que.STEP, "Descartados" ).como, "quitado" );
    igual( Como.AGREGADO, una( r, Que.SALTO, "Leer clientes -> Auditoría" ).como, "salto nuevo" );
    igual( Como.QUITADO, una( r, Que.SALTO, "Activos con saldo -> Descartados" ).como, "salto quitado" );
    Propiedad p = prop( una( r, Que.STEP, "Activos con saldo" ), "send_false_to" );
    igual( "Descartados", p.antes, "send_false_to antes" );
    igual( "", p.despues, "send_false_to después" );
    String t = Salidas.texto( r, "es" );
    contiene( t, "+ Auditoría (Dummy): añadido", "texto agregado" );
    contiene( t, "− Descartados (Dummy): quitado", "texto quitado" );
    contiene( t, "+ Leer clientes → Auditoría", "salto agregado" );
    contiene( t, "send_false_to: Descartados → (vacío)", "propiedad vaciada" );
  }

  /** Mover cajas: con «ignorar posiciones» no hay cambios; sin él, cada step movido. */
  public void pruebaE_Posiciones() throws Exception {
    String x = ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    String y = x.replaceAll( "<yloc>112</yloc>", "<yloc>160</yloc>" );
    cierto( !x.equals( y ), "algo movido" );
    cierto( diff( x, y, true ).iguales(), "posiciones ignoradas" );
    contiene( Salidas.texto( diff( x, y, true ), "es" ), "no se tienen en cuenta las posiciones", "lo dice" );
    ResultadoDiff r = diff( x, y, false );
    cierto( r.cuenta( Que.STEP, Como.MOVIDO ) >= 3, "movidos: " + Salidas.texto( r, "es" ) );
    Diferencia d = una( r, Que.STEP, "Leer clientes" );
    igual( "96,112", prop( d, "position" ).antes, "posición antes" );
    igual( "96,160", prop( d, "position" ).despues, "posición después" );
    contiene( Salidas.texto( r, "en" ), "↔ Leer clientes (CsvInput)", "símbolo de movido" );
  }

  /** Listas de campos: un tipo que cambia, un campo nuevo, uno quitado y el orden. */
  public void pruebaF_ListasDeCampos() throws Exception {
    String x = ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    // CSV input: saldo pasa a Integer; se quita "activo"... no: se cambia el orden de ciudad y nombre
    String y = x.replaceFirst( "(<name>saldo</name>\\s*<type>)Number(</type>)", "$1Integer$2" );
    int i = y.indexOf( "<field>\n        <name>nombre</name>" );
    int j = y.indexOf( "<field>\n        <name>ciudad</name>" );
    int k = y.indexOf( "<field>\n        <name>fecha_alta</name>" );
    cierto( i > 0 && j > i && k > j, "campos del CSV input en orden" );
    String nombre = y.substring( i, j );
    String ciudad = y.substring( j, k );
    y = y.substring( 0, i ) + ciudad + nombre + y.substring( k );
    // y uno nuevo al final de los campos del CSV
    y = y.replaceFirst( "(<name>activo</name>(?s:.*?)</field>)", "$1\n      <field>\n        <name>email</name>\n"
      + "        <type>String</type>\n        <length>80</length>\n      </field>" );
    ResultadoDiff r = diff( x, y, true );
    Diferencia d = una( r, Que.STEP, "Leer clientes" );
    Propiedad tipo = prop( d, "fields/field[saldo]/type" );
    igual( "Number", tipo.antes, "tipo antes" );
    igual( "Integer", tipo.despues, "tipo después" );
    Propiedad nuevo = prop( d, "fields/field[email]" );
    igual( Propiedad.ELEMENTO_AGREGADO, nuevo.clase, "campo agregado" );
    contiene( nuevo.despues, "type=String", "resumen del campo" );
    Propiedad orden = prop( d, "fields/field" );
    igual( Propiedad.ORDEN, orden.clase, "orden" );
    contiene( orden.antes, "nombre, ciudad", "orden antes" );
    contiene( orden.despues, "ciudad, nombre", "orden después" );
    String t = Salidas.texto( r, "es" );
    contiene( t, "fields › saldo › type: Number → Integer", "texto del tipo" );
    contiene( t, "fields › email: añadido (type=String, length=80)", "texto del campo nuevo" );
    contiene( t, "fields › field · orden: cliente_id, nombre, ciudad", "texto del orden" );
  }

  /** Parámetros y manejo de errores (ejemplo 06): valor por defecto, error desactivado y el salto de error. */
  public void pruebaG_ParametrosYErrores() throws Exception {
    String x = ejemplo( "manual/ejemplos/06_errores_y_parametros.ktr" );
    String y = x.replaceFirst( "(<name>SALDO_MINIMO</name>\\s*<default_value>)[^<]*(</default_value>)", "$1250$2" );
    y = y.replaceFirst( "(<target_step>Escribir errores</target_step>\\s*<is_enabled>)Y(</is_enabled>)", "$1N$2" );
    cierto( !y.equals( x ), "cambiado" );
    ResultadoDiff r = diff( x, y, true );
    Diferencia par = una( r, Que.PARAMETRO, "SALDO_MINIMO" );
    igual( "250", prop( par, "default_value" ).despues, "nuevo valor por defecto" );
    Diferencia err = una( r, Que.ERRORES, "Leer clientes con errores" );
    igual( "N", prop( err, "is_enabled" ).despues, "manejo de errores desactivado" );
    Diferencia salto = una( r, Que.SALTO, "Leer clientes con errores -> Escribir errores" );
    igual( "N", prop( salto, "error" ).despues, "el salto ya no es de error" );
    String t = Salidas.texto( r, "es" );
    contiene( t, "Parámetros", "sección parámetros" );
    contiene( t, "valor por defecto:", "etiqueta traducida" );
    contiene( t, "ya no lleva las filas con error", "salto de error" );
  }

  /** Un SQL de varias líneas: diff por líneas en texto y en Markdown. */
  public void pruebaH_SqlVariasLineas() throws Exception {
    String x = ejemplo( "patrones/03_carga_incremental/03b_cargar_delta.ktr" );
    int i = x.indexOf( "<sql>" );
    cierto( i > 0, "el patrón 03b tiene SQL" );
    int j = x.indexOf( "</sql>", i );
    String sql = x.substring( i + 5, j );
    String nuevo = sql.trim() + "\nAND 1 = 1";
    String y = x.substring( 0, i + 5 ) + nuevo + x.substring( j );
    ResultadoDiff r = diff( x, y, true );
    String t = Salidas.texto( r, "en" );
    contiene( t, "+ AND 1 = 1", "línea agregada: " + t );
    String md = Salidas.markdown( r, "en" );
    contiene( md, "```diff", "bloque diff en markdown" );
    contiene( md, "+AND 1 = 1", "línea en el bloque" );
  }

  /** Notas: texto cambiado y nota nueva. */
  public void pruebaI_Notas() throws Exception {
    String x = ejemplo( "patrones/01_csv_a_staging/01_csv_a_staging.ktr" );
    String y = cambiar( x, "The rest of the load continues.", "The load goes on." );
    ResultadoDiff r = diff( x, y, true );
    igual( 1, r.cuenta( Que.NOTA, Como.CAMBIADO ), "nota cambiada: " + Salidas.texto( r, "en" ) );
    contiene( Salidas.texto( r, "en" ), "+ with source_line and the reason. The load goes on.", "diff de la nota" );
  }

  // ================================================================ contraseñas

  /** Contraseñas (de conexión y de un step) cifradas o en claro: nunca salen, ni en textconv. */
  public void pruebaJ_ContrasenasNuncaSalen() throws Exception {
    String x = ejemplo( "manual/ejemplos/07_conexion_sqlite.ktr" );
    cierto( x.contains( "<connection>" ), "el 07 tiene una conexión" );
    String conPass = cambiar( cambiar( x, "<username/>", "<username>ana.lopez</username>" ), "<password/>",
      "<password>Encrypted 2be98afc86aa7f2e4cb79ce10df90acde</password>" );
    String otraPass = conPass.replace( "Encrypted 2be98afc86aa7f2e4cb79ce10df90acde", "hunter2-SECRETO" )
      .replace( "ana.lopez", "pedro.gil" );
    // y un step con una contraseña en claro
    String stepPass = otraPass.replaceFirst( "(<step>\\s*<name>[^<]*</name>)", "$1\n    <proxyPassword>Clave-Del-Proxy"
      + "</proxyPassword>" );
    ResultadoDiff r = diff( conPass, stepPass, true );
    List<String> salidas = new ArrayList<String>();
    for ( String idioma : new String[] { "en", "es", "pt" } ) {
      salidas.add( Salidas.texto( r, idioma ) );
      salidas.add( Salidas.markdown( r, idioma ) );
      salidas.add( Salidas.json( r, idioma ) );
    }
    salidas.add( Volcado.texto( Documento.leer( conPass ), true, "es" ) );
    salidas.add( Volcado.texto( Documento.leer( stepPass ), true, "en" ) );
    for ( String s : salidas ) {
      for ( String secreto : new String[] { "2be98afc86aa7f2e4cb79ce10df90acde", "hunter2", "SECRETO",
        "Clave-Del-Proxy", "ana.lopez", "pedro.gil", "\u0000", "secret:" } ) {
        noContiene( s, secreto, "nunca sale «" + secreto + "»" );
      }
    }
    String t = Salidas.texto( r, "es" );
    contiene( t, "password: cambiada (oculta)", "dice que cambió: " + t );
    contiene( t, "username: cambiada (oculta)", "el usuario tampoco sale" );
    contiene( t, "proxyPassword: puesta (oculta)", "la del step también" );
    JsonNode j = McpHttpServer.JSON.readTree( Salidas.json( r, "en" ) );
    JsonNode p = j.path( "changes" ).findValue( "properties" ).get( 0 );
    cierto( p.path( "secret" ).asBoolean(), "json secret" );
    falso( p.has( "before" ) || p.has( "after" ), "json sin valores" );
    // la misma contraseña en las dos: no es un cambio
    cierto( diff( conPass, conPass, true ).iguales(), "sin cambios" );
    // una variable sí se ve
    String conVar = conPass.replace( "Encrypted 2be98afc86aa7f2e4cb79ce10df90acde", "${DB_PASS}" );
    contiene( Salidas.texto( diff( conPass, conVar, true ), "en" ), "changed (hidden)", "cifrada -> variable" );
    contiene( Volcado.texto( Documento.leer( conVar ), false, "en" ), "${DB_PASS}", "la variable se ve" );
  }

  // ================================================================ jobs

  /** Job: entrada renombrada, condición de un salto, salto desactivado. */
  public void pruebaK_Job() throws Exception {
    String x = ejemplo( "manual/ejemplos/10_job_secuencia.kjb" );
    String y = cambiar( x, "Ventas por producto", "Ventas agregadas" );
    y = y.replaceFirst( "(<from>Generar constantes</from>\\s*<to>Abortar</to>(?s:.*?)<enabled>)Y(</enabled>)",
      "$1N$2" );
    y = y.replaceFirst( "(<from>Ventas agregadas</from>\\s*<to>Success</to>(?s:.*?)<unconditional>)N(</unconditional>)",
      "$1Y$2" );
    ResultadoDiff r = diff( x, y, true );
    igual( Documento.JOB, r.tipo, "job" );
    Diferencia e = una( r, Que.ENTRADA, "Ventas agregadas" );
    igual( Como.RENOMBRADO, e.como, "entrada renombrada: " + Salidas.texto( r, "es" ) );
    igual( "N", prop( una( r, Que.SALTO, "Generar constantes -> Abortar" ), "enabled" ).despues, "desactivado" );
    Propiedad c = prop( una( r, Que.SALTO, "Ventas agregadas -> Success" ), "condition" );
    igual( "success", c.antes, "condición antes" );
    igual( "always", c.despues, "condición después" );
    String t = Salidas.texto( r, "es" );
    contiene( t, "Cambios en el job «10_job_secuencia»", "título del job" );
    contiene( t, "Entradas del job", "sección" );
    contiene( t, "condición: si termina bien → siempre", "condición legible" );
    // un job con una transformación: no se comparan
    String ktr = ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    try {
      diff( x, ktr, true );
      throw new AssertionError( "job contra transformación" );
    } catch ( io.github.ivi2511.pentahomcp.diff.Xmls.XmlInvalido esperado ) {
      contiene( esperado.getMessage(), "Cannot compare a job", "mensaje" );
    }
  }

  /** textconv: una propiedad por línea, sin volátiles; mover cajas no cambia nada si no se piden posiciones. */
  public void pruebaL_Volcado() throws Exception {
    String x = ejemplo( "manual/ejemplos/02_filtrar_clientes.ktr" );
    String v = Volcado.texto( Documento.leer( x ), false, "en" );
    contiene( v, "transformation: 02_filtrar_clientes", "cabecera" );
    contiene( v, "  Activos con saldo [FilterRows]", "step" );
    contiene( v, "    condition: saldo >= 1000.0 AND activo = 'S'", "condición" );
    contiene( v, "  Activos con saldo -> Descartados", "salto" );
    noContiene( v, "modified_date", "sin volátiles" );
    noContiene( v, "distribute: Y", "sin valores por defecto del envoltorio" );
    String y = x.replaceAll( "<yloc>112</yloc>", "<yloc>300</yloc>" );
    igual( v, Volcado.texto( Documento.leer( y ), false, "en" ), "mover no cambia el volcado" );
    contiene( Volcado.texto( Documento.leer( y ), true, "en" ), "@ 96,300", "con posiciones" );
    nulo( null, "-" );
  }

  /** La guía 11-versiones.md es un recurso MCP (pentaho://guide/versions) y nombra las piezas. */
  public void pruebaM_Guia() throws Exception {
    McpResources r = new McpResources();
    contiene( r.listar( null ).toString(), "pentaho://guide/versions", "resources/list trae la guía de versiones" );
    String g = r.leer( "pentaho://guide/versions" ).path( "contents" ).path( 0 ).path( "text" ).asText();
    for ( String n : new String[] { "diff_documents", "compare_with", "show_on_canvas", "pentaho-live normalize",
      "diff.kettle.textconv", "pentaho-live pre-commit", "*.ktr diff=kettle", "changes" } ) {
      contiene( g, n, "la guía nombra " + n );
    }
  }
}
