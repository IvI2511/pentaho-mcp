package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Combinar;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.navegador.Chromium;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Varios steps y varios documentos (0.5.1): el perfil de dos steps en una sola corrida, las
 * transformaciones que ejecuta un job, el informe combinado (portada general, un capitulo por documento,
 * anclas unicas) y build_report con 'documents' (un juego de archivos por documento sin pisarse los
 * nombres, o un solo informe con combine). Sin navegador: solo formatos que no lo necesitan.
 *
 * @dueño M
 */
public class PruebaVariosDocumentos {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static JsonNode json( String s ) throws Exception {
    return McpHttpServer.JSON.readTree( s );
  }

  private static Informe armar( Captura c, Pedido p ) throws Exception {
    return InformeServicio.armar( c, p, new Tema(), Progreso.NULO ).get( 120, TimeUnit.SECONDS );
  }

  private static Pedido estaticas( String idioma ) {
    Pedido p = new Pedido();
    p.idioma = idioma;
    p.secciones = new ArrayList<String>( Arrays.asList( SeccionId.PORTADA, SeccionId.RESUMEN, SeccionId.FLUJO,
      SeccionId.DICCIONARIO ) );
    p.metricas = Pedido.METRICAS_NINGUNA;
    return p;
  }

  /** Sin Edge/Chrome durante la accion (las pruebas no abren navegadores). */
  private interface Accion {
    void correr() throws Exception;
  }

  private static void sinNavegador( Accion a ) throws Exception {
    String antes = System.getProperty( Chromium.PROPIEDAD );
    System.setProperty( Chromium.PROPIEDAD, "none" );
    try {
      a.correr();
    } finally {
      if ( antes == null ) {
        System.clearProperty( Chromium.PROPIEDAD );
      } else {
        System.setProperty( Chromium.PROPIEDAD, antes );
      }
    }
  }

  // ---------------------------------------------------------------- varios steps

  /** pasosPerfil junta perfilStep y perfilSteps, en orden, sin repetidos ni vacios. */
  public void pruebaPedidoPasosPerfil() {
    Pedido p = new Pedido();
    cierto( p.pasosPerfil().isEmpty(), "sin steps" );
    p.perfilStep = "Leer ventas";
    p.perfilSteps.addAll( Arrays.asList( "Calcular importe", " ", "Leer ventas", null ) );
    igual( Arrays.asList( "Leer ventas", "Calcular importe" ), p.pasosPerfil(), "union ordenada" );
  }

  /** profile_steps conserva mayusculas, se suma a profile_step y agrega la seccion profile. */
  public void pruebaPeticionProfileSteps() throws Exception {
    Tema tema = new Tema();
    ReportTools.Peticion op = ReportTools.peticion( json( "{\"formats\":[\"docx\",\"xlsx\",\"images\"],"
      + "\"sections\":[\"summary\"],\"profile_step\":\"Leer ventas\",\"profile_steps\":[\"Calcular importe\","
      + "\"Leer ventas\"]}" ), tema, ReportTools.Modo.CONSTRUIR );
    igual( Arrays.asList( "Leer ventas", "Calcular importe" ), op.pedido.pasosPerfil(), "steps tal cual" );
    cierto( op.pedido.secciones.contains( SeccionId.PERFIL ), "profile_steps agrega la seccion" );
    igual( Arrays.asList( Formato.DOCX, Formato.XLSX, Formato.IMAGENES ), op.formatos, "formatos nuevos" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[\"html\"],\"profile_steps\":[\"a\","
      + "\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"]}" ), tema, ReportTools.Modo.CONSTRUIR ),
      "mas de 10 steps" );
  }

  /** 'documents' con 'document', o con opciones de un solo documento, es un error claro. */
  public void pruebaPeticionDocumentos() throws Exception {
    Tema tema = new Tema();
    ReportTools.Peticion op = ReportTools.peticion( json( "{\"formats\":[\"md\"],\"documents\":[\"A\",\"B\",\"A\"],"
      + "\"combine\":true,\"pptx_appendix\":true}" ), tema, ReportTools.Modo.CONSTRUIR );
    igual( Arrays.asList( "A", "B" ), op.documentos, "sin repetidos" );
    cierto( op.combinar, "combine" );
    igual( Boolean.TRUE, op.pptxAnexo, "pptx_appendix" );
    contiene( lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[\"md\"],\"document\":\"A\","
      + "\"documents\":[\"A\",\"B\"]}" ), tema, ReportTools.Modo.CONSTRUIR ), "document y documents" ).getMessage(),
      "not both", "mensaje" );
    contiene( lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[\"md\"],\"documents\":[\"A\","
      + "\"B\"],\"lineage_step\":\"x\"}" ), tema, ReportTools.Modo.CONSTRUIR ), "lineage_step con varios" )
      .getMessage(), "single document", "mensaje linaje" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[\"md\"],\"documents\":[\"A\",\"B\"],"
      + "\"profile_steps\":[\"x\"]}" ), tema, ReportTools.Modo.CONSTRUIR ), "perfil con varios" );
    lanza( ToolError.class, () -> ReportTools.peticion( json( "{\"formats\":[\"md\"],\"documents\":[\"A\",\"B\"],"
      + "\"metrics\":\"run_copy\"}" ), tema, ReportTools.Modo.CONSTRUIR ), "run_copy con varios" );
  }

  /** El esquema de build_report trae documents, combine, profile_steps y los formatos nuevos. */
  public void pruebaEsquemaBuildReport() throws Exception {
    Method m = ReportTools.class.getDeclaredMethod( "esquema", ReportTools.Modo.class );
    m.setAccessible( true );
    ObjectNode sc = (ObjectNode) m.invoke( null, ReportTools.Modo.CONSTRUIR );
    JsonNode props = sc.get( "properties" );
    for ( String k : new String[] { "documents", "combine", "include_job_transformations", "profile_steps",
      "pptx_appendix", "profile_step", "document" } ) {
      noNulo( props.get( k ), "propiedad " + k );
    }
    String formatos = props.get( "formats" ).toString();
    for ( String f : new String[] { "docx", "xlsx", "images" } ) {
      contiene( formatos, "\"" + f + "\"", "formato " + f + " en el enum" );
    }
    igual( "array", props.get( "documents" ).get( "type" ).asText(), "documents es array" );
  }

  /** Perfil de dos steps de 03 en UNA corrida: un estado y una tabla por step, ids con sufijo. */
  public void pruebaPerfilDosSteps() throws Exception {
    Pedido p = new Pedido();
    p.idioma = "es";
    p.perfilStep = "Leer ventas";
    p.perfilSteps.add( "Calcular importe" );
    p.perfilFilas = 100;
    ContextoPrueba ctx = new ContextoPrueba( EntornoKettle.captura( "03" ), p, new Tema() );
    Seccion s = new SecPerfil().armar( ctx );
    cierto( Seccion.OK.equals( s.estado ) || Seccion.PARCIAL.equals( s.estado ), "estado " + s.estado + " ("
      + s.motivo + ")" );
    contiene( s.resumen, "2 steps", "resumen de varios" );
    JsonNode d = s.datos;
    igual( 2, d.get( "steps" ).size(), "steps en datos" );
    igual( 2, d.get( "profiles" ).size(), "un perfil por step" );
    igual( "Leer ventas", d.get( "profiles" ).get( 0 ).get( "step" ).asText(), "primero" );
    igual( "Calcular importe", d.get( "profiles" ).get( 1 ).get( "step" ).asText(), "segundo" );
    cierto( d.get( "profiles" ).get( 1 ).get( "sample_rows" ).asLong() > 0, "el segundo tambien leyo filas" );
    Set<String> ids = new HashSet<String>();
    boolean t1 = false;
    boolean t2 = false;
    for ( Bloque b : s.bloques ) {
      if ( b.id != null ) {
        cierto( ids.add( b.id ), "id repetido " + b.id );
      }
      t1 |= "profile-fields-1".equals( b.id ) && b.titulo != null && b.titulo.contains( "Leer ventas" );
      t2 |= "profile-fields-2".equals( b.id ) && b.titulo != null && b.titulo.contains( "Calcular importe" );
    }
    cierto( t1 && t2, "una tabla de campos por step con su nombre: " + ids );
  }

  // ---------------------------------------------------------------- varios documentos

  /** 11 (job padre) ejecuta 10, y 10 ejecuta 01 y 03: en orden, sin repetidos, sin el propio job. */
  public void pruebaTransformacionesDeJob() throws Exception {
    List<String> l = InformeServicio.transformacionesDeJob( EntornoKettle.captura( "11" ), true );
    List<String> nombres = new ArrayList<String>();
    for ( String x : l ) {
      nombres.add( new File( x ).getName() );
      cierto( new File( x ).isFile(), "existe " + x );
    }
    cierto( nombres.contains( "10_job_secuencia.kjb" ), "el sub-job: " + nombres );
    cierto( nombres.contains( "01_generar_constantes.ktr" ) && nombres.contains( "03_ventas_por_producto.ktr" ),
      "lo que corre el sub-job: " + nombres );
    falso( nombres.contains( "11_job_padre.kjb" ), "no el propio job" );
    igual( nombres.size(), new HashSet<String>( nombres ).size(), "sin repetidos" );
    cierto( nombres.indexOf( "10_job_secuencia.kjb" ) < nombres.indexOf( "03_ventas_por_producto.ktr" ),
      "en orden de ejecucion" );
    List<String> uno = InformeServicio.transformacionesDeJob( EntornoKettle.captura( "11" ), false );
    igual( Arrays.asList( "10_job_secuencia.kjb" ), nombresDe( uno ), "sin recursion, solo el primer nivel" );
    cierto( InformeServicio.transformacionesDeJob( EntornoKettle.captura( "03" ), true ).isEmpty(),
      "una transformacion no ejecuta nada" );
  }

  private static List<String> nombresDe( List<String> rutas ) {
    List<String> out = new ArrayList<String>();
    for ( String x : rutas ) {
      out.add( new File( x ).getName() );
    }
    return out;
  }

  /** Combinar: portada general, documentos del informe y un capitulo por documento con ids unicos. */
  public void pruebaCombinadoTieneLosDosCapitulos() throws Exception {
    Informe a = armar( EntornoKettle.captura( "03" ), estaticas( "es" ) );
    Informe b = armar( EntornoKettle.captura( "10" ), estaticas( "es" ) );
    Informe c = Combinar.combinar( Arrays.asList( a, b ), null, "es", new Tema() );
    igual( Informe.COMPILACION, c.tipoDoc, "tipo compilacion" );
    cierto( c.combinado(), "combinado()" );
    igual( 2, c.capitulos.size(), "dos capitulos" );
    igual( "d1", c.capitulos.get( 0 ).id, "id d1" );
    igual( a.titulo, c.capitulos.get( 0 ).titulo, "titulo del capitulo 1" );
    igual( b.titulo, c.capitulos.get( 1 ).titulo, "titulo del capitulo 2" );
    contiene( c.titulo, a.nombreDoc, "titulo por defecto con el primero" );
    contiene( c.titulo, b.nombreDoc, "titulo por defecto con el segundo" );
    int d1 = 0;
    int d2 = 0;
    int general = 0;
    Set<String> ids = new HashSet<String>();
    for ( Seccion s : c.secciones ) {
      if ( s.capitulo == null ) {
        general++;
      } else if ( "d1".equals( s.capitulo ) ) {
        d1++;
      } else if ( "d2".equals( s.capitulo ) ) {
        d2++;
      }
      for ( Bloque x : s.bloques ) {
        if ( x != null && x.id != null ) {
          cierto( ids.add( x.id ), "id de bloque repetido: " + x.id );
        }
      }
    }
    igual( 2, general, "parte general: portada y documentos del informe" );
    igual( a.secciones.size(), d1, "todas las secciones del primero" );
    igual( b.secciones.size(), d2, "todas las secciones del segundo" );
    // los originales no se tocan (copia profunda)
    for ( Seccion s : a.secciones ) {
      igual( null, s.capitulo, "el original queda sin capitulo" );
    }
    lanza( IllegalArgumentException.class, () -> Combinar.combinar( Arrays.asList( a ), null, "es", new Tema() ),
      "un solo informe no se combina" );
  }

  /** build_report con documents: un juego de archivos por documento; dos con el mismo nombre no se pisan. */
  public void pruebaConstruirVariosSeparados() throws Exception {
    File otra = new File( EntornoKettle.carpeta(), "otra_carpeta" );
    otra.mkdirs();
    File copia = new File( otra, "03_ventas_por_producto.ktr" );
    Files.copy( EntornoKettle.ejemplo( "03" ).toPath(), copia.toPath(), StandardCopyOption.REPLACE_EXISTING );
    final File dir = EntornoKettle.salida( "varios_separados" );
    final Tema tema = new Tema();
    sinNavegador( () -> {
      ReportTools.Peticion op = ReportTools.peticion( json( "{\"formats\":[\"md\",\"json\"],\"sections\":[\"cover\","
        + "\"summary\",\"flow\"],\"metrics\":\"none\",\"file_name\":\"ignorado\",\"output_dir\":" + McpHttpServer.JSON
          .writeValueAsString( dir.getAbsolutePath() ) + ",\"documents\":[\"x\",\"y\"]}" ), tema,
        ReportTools.Modo.CONSTRUIR );
      List<Captura> cs = Arrays.asList( EntornoKettle.captura( "03" ), PruebaPerfil.capturaDe( copia ) );
      ObjectNode o = ReportTools.construirVarios( cs, op, tema );
      falso( o.get( "combined" ).asBoolean(), "no combinado" );
      igual( 4, o.get( "files" ).size(), "2 documentos x 2 formatos: " + o );
      igual( 2, o.get( "documents" ).size(), "un registro por documento" );
      Set<String> rutas = new HashSet<String>();
      for ( JsonNode f : o.get( "files" ) ) {
        cierto( rutas.add( f.get( "path" ).asText().toLowerCase() ), "archivo pisado: " + f );
        cierto( new File( f.get( "path" ).asText() ).isFile(), "existe " + f );
      }
      cierto( o.get( "documents" ).get( 1 ).get( "files" ).get( 0 ).get( "path" ).asText().contains( "-2." ),
        "el segundo con -2: " + o.get( "documents" ).get( 1 ) );
      contiene( o.get( "warnings" ).toString(), "file_name is ignored", "aviso de file_name" );
      igual( 3, o.get( "documents" ).get( 0 ).get( "sections" ).size(), "secciones del primero" );
    } );
  }

  /** build_report con combine: un solo juego de archivos con los dos capitulos (Markdown, Word y JSON). */
  public void pruebaConstruirVariosCombinado() throws Exception {
    final File dir = EntornoKettle.salida( "varios_combinado" );
    final Tema tema = new Tema();
    sinNavegador( () -> {
      ReportTools.Peticion op = ReportTools.peticion( json( "{\"formats\":[\"md\",\"json\",\"docx\"],\"sections\":"
        + "[\"cover\",\"summary\",\"flow\",\"dictionary\"],\"metrics\":\"none\",\"combine\":true,\"file_name\":"
        + "\"Entrega.docx\",\"title\":\"Entrega final\",\"output_dir\":" + McpHttpServer.JSON.writeValueAsString( dir
          .getAbsolutePath() ) + ",\"documents\":[\"x\",\"y\"]}" ), tema, ReportTools.Modo.CONSTRUIR );
      List<Captura> cs = Arrays.asList( EntornoKettle.captura( "10" ), EntornoKettle.captura( "03" ) );
      ObjectNode o = ReportTools.construirVarios( cs, op, tema );
      cierto( o.get( "combined" ).asBoolean(), "combinado" );
      igual( 3, o.get( "files" ).size(), "un archivo por formato: " + o );
      igual( "Entrega final", o.get( "name" ).asText(), "el titulo pedido" );
      for ( JsonNode f : o.get( "files" ) ) {
        cierto( new File( f.get( "path" ).asText() ).getName().startsWith( "Entrega." ), "base del file_name: " + f );
      }
      Set<String> capitulos = new HashSet<String>();
      for ( JsonNode s : o.get( "sections" ) ) {
        if ( s.has( "chapter" ) ) {
          capitulos.add( s.get( "chapter" ).asText() );
        }
      }
      igual( new HashSet<String>( Arrays.asList( "d1", "d2" ) ), capitulos, "secciones con su capitulo" );
      String md = EntornoKettle.leer( new File( dir, "Entrega.md" ) );
      contiene( md, cs.get( 0 ).nombre, "el Markdown tiene el primer documento" );
      contiene( md, cs.get( 1 ).nombre, "el Markdown tiene el segundo documento" );
      JsonNode modelo = McpHttpServer.JSON.readTree( new File( dir, "Entrega.json" ) );
      noNulo( modelo, "json" );
      File docx = new File( dir, "Entrega.docx" );
      cierto( docx.isFile() && docx.length() > 5000, "Word escrito: " + o.get( "warnings" ) );
      String texto = textoDocx( docx );
      contiene( texto, cs.get( 0 ).nombre, "el Word tiene el capitulo del job" );
      contiene( texto, cs.get( 1 ).nombre, "el Word tiene el capitulo de la transformacion" );
    } );
  }

  /** El texto de document.xml de un .docx (sin XML). */
  private static String textoDocx( File f ) throws Exception {
    try ( java.util.zip.ZipFile z = new java.util.zip.ZipFile( f ) ) {
      java.util.zip.ZipEntry e = z.getEntry( "word/document.xml" );
      byte[] b = new byte[ (int) e.getSize() ];
      try ( java.io.DataInputStream in = new java.io.DataInputStream( z.getInputStream( e ) ) ) {
        in.readFully( b );
      }
      return new String( b, java.nio.charset.StandardCharsets.UTF_8 ).replaceAll( "<[^>]+>", "" );
    }
  }

  /** Los nombres base sin repetir, sin distinguir mayusculas. */
  public void pruebaBasesUnicas() {
    igual( Arrays.asList( "a", "A-2", "b", "a-3" ), InformeServicio.basesUnicas( Arrays.asList( "a", "A", "b", "a" ) ),
      "sufijos" );
  }
}
