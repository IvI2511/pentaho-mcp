package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.DBCache;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.rowgenerator.RowGeneratorMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.render.RenderHtml;
import io.github.ivi2511.pentahomcp.informes.render.RenderMarkdown;
import io.github.ivi2511.pentahomcp.informes.render.RenderPptx;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Pruebas de la integracion (las costuras entre paquetes que se cerraron al juntar WP1-WP10):
 * <ul>
 * <li>Ninguna seccion crea una base SQLite vacia: ContextoKettle no calcula campos ni SecValidacion le
 * pasa a checkSteps los steps que tocan una base de archivo que no existe (WP3/WP5 sobre WP2).</li>
 * <li>DbJdbc.avisoArchivo decodifica las rutas file:///...%20... (WP5 sobre 0.4.0).</li>
 * <li>El panel en Spoon usa CorridaMedida para Medir y para confirmar el perfil, y RenderPptx.problema (WP4,
 * WP9 sobre WP1).</li>
 * <li>Los graficos salen en el idioma del informe, no en el del tema (WP6 sobre WP7 y WP9).</li>
 * <li>La guia de informes es un recurso MCP (WP10).</li>
 * </ul>
 *
 * @dueño integracion
 */
public class PruebaIntegracion {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /**
   * 07 con la carpeta de la base presente (SQLite crearia demo.sqlite al conectarse): diccionario, linaje,
   * modelo destino y validacion no la crean; la validacion queda parcial con los steps que no valido.
   */
  public void pruebaBaseFaltanteNoSeCrea() throws Exception {
    File carpeta = new File( EntornoKettle.ejemplos(), "salida_integ_" + System.nanoTime() );
    cierto( carpeta.mkdirs(), "carpeta " + carpeta );
    File base = new File( carpeta, "demo.sqlite" );
    try {
      TransMeta tm = EntornoKettle.trans( "07" );
      DatabaseMeta dm = tm.findDatabase( "sqlite_demo" );
      noNulo( dm, "conexion sqlite_demo" );
      dm.setDBName( "${Internal.Entry.Current.Directory}/" + carpeta.getName() + "/demo.sqlite" );
      DBCache.getInstance().clear( "sqlite_demo" ); // sin cache, un calculo sin chequeo SI se conectaria
      Captura c = new Captura();
      c.copia = tm;
      c.docId = "prueba:07-integ";
      c.nombre = tm.getName();
      c.archivo = EntornoKettle.ejemplo( "07" ).getAbsolutePath();
      c.tipo = Captura.TRANSFORMACION;
      c.capturado = new Date();
      // cada seccion sola (asi una falla dice cual fue) y despues todas juntas
      for ( String sid : Arrays.asList( SeccionId.LINAJE, SeccionId.DICCIONARIO, SeccionId.DESTINO,
        SeccionId.VALIDACION ) ) {
        DBCache.getInstance().clear( "sqlite_demo" );
        Pedido p1 = new Pedido();
        p1.idioma = "es";
        p1.secciones = Arrays.asList( sid );
        InformeServicio.armarAhora( c, p1, new Tema(), Progreso.NULO );
        falso( base.exists(), sid + " no crea " + base );
      }
      DBCache.getInstance().clear( "sqlite_demo" );
      Pedido p = new Pedido();
      p.idioma = "es";
      p.secciones = Arrays.asList( SeccionId.LINAJE, SeccionId.DICCIONARIO, SeccionId.DESTINO, SeccionId.VALIDACION );
      Informe inf = InformeServicio.armarAhora( c, p, new Tema(), Progreso.NULO );
      falso( base.exists(), "ninguna seccion crea " + base );

      Seccion val = inf.seccion( SeccionId.VALIDACION );
      noNulo( val, "validation" );
      igual( Seccion.PARCIAL, val.estado, "validation parcial: " + val.motivo );
      contiene( val.motivo, "sqlite_demo", "el motivo nombra la conexion" );
      contiene( val.motivo, "Consultar SQLite", "y los steps que no valido" );
      JsonNode no = val.datos.path( "not_validated" );
      igual( 2, no.size(), "los dos steps de 07 dependen de la base: " + no );

      String dic = ModeloJson.escribir( inf.seccion( SeccionId.DICCIONARIO ), false );
      contiene( dic, "apunta a un archivo de base que no existe", "el diccionario dice por que no hay campos" );

      // con un step que no depende de la base, checkSteps corre solo sobre ese (seleccion de la copia)
      StepMeta suelto = new StepMeta( "Constantes sueltas", new RowGeneratorMeta() );
      ( (RowGeneratorMeta) suelto.getStepMetaInterface() ).setDefault();
      tm.addStep( suelto );
      DBCache.getInstance().clear( "sqlite_demo" );
      Informe conSuelto = InformeServicio.armarAhora( c, pedidoValidacion(), new Tema(), Progreso.NULO );
      falso( base.exists(), "validar con un step suelto tampoco la crea" );
      Seccion v1 = conSuelto.seccion( SeccionId.VALIDACION );
      igual( Seccion.PARCIAL, v1.estado, "sigue parcial" );
      igual( 2, v1.datos.path( "not_validated" ).size(), "el suelto si se valido" );
      for ( StepMeta sm : tm.getSteps() ) {
        falso( sm.isSelected(), "la seleccion de la copia queda como estaba: " + sm.getName() );
      }
      tm.removeStep( tm.indexOfStep( suelto ) );

      // y con la base presente, la validacion vuelve a revisar todo
      EntornoKettle.escribir( base, "" );
      Informe otro = InformeServicio.armarAhora( c, pedidoValidacion(), new Tema(), Progreso.NULO );
      Seccion v2 = otro.seccion( SeccionId.VALIDACION );
      falso( v2.datos.has( "not_validated" ), "con el archivo presente no se saltea nada" );
    } finally {
      EntornoKettle.borrar( carpeta.toPath() );
    }
  }

  private static Pedido pedidoValidacion() {
    Pedido p = new Pedido();
    p.idioma = "en";
    p.secciones = Arrays.asList( SeccionId.VALIDACION );
    return p;
  }

  /** test_connection ya no da un falso «el archivo no existia» con rutas file:///...%20... (las de 07). */
  public void pruebaAvisoArchivoUri() throws Exception {
    File dw = EntornoKettle.copiaDw();
    String uri = "file:///" + dw.getAbsolutePath().replace( '\\', '/' ).replace( " ", "%20" );
    cierto( uri.contains( "%20" ) || !dw.getAbsolutePath().contains( " " ), "la ruta de prueba tiene espacios" );
    DatabaseMeta existe = new DatabaseMeta( "dw", "SQLITE", "Native", "", uri, "-1", "", "" );
    nulo( DbJdbc.avisoArchivo( existe ), "la base existe: sin aviso" );
    DatabaseMeta falta = new DatabaseMeta( "dw", "SQLITE", "Native", "", uri.replace( ".sqlite", "-no.sqlite" ), "-1",
      "", "" );
    String aviso = DbJdbc.avisoArchivo( falta );
    noNulo( aviso, "la base no existe: aviso" );
    contiene( aviso, "did not exist", "texto del aviso" );
  }

  /** El servicio del panel en Spoon: Medir y perfil con CorridaMedida, PPTX con RenderPptx.problema. */
  public void pruebaServicioSpoon() throws Exception {
    ReportsPerspective.ServicioSpoon s = new ReportsPerspective.ServicioSpoon();
    Captura c = EntornoKettle.captura( "04" );
    igual( Arrays.asList( "Escribir ventas con cliente" ), s.pasosQueEscriben( c ), "steps que escriben en 04" );
    nulo( s.confirmacionPerfil( c, "Buscar cliente", "es" ), "nada escribe antes de Buscar cliente" );
    String t = s.confirmacionPerfil( c, "Escribir ventas con cliente", "es" );
    noNulo( t, "perfilar el step de salida pide confirmacion" );
    contiene( t, "Escribir ventas con cliente", "y lo nombra" );
    nulo( s.problemaPptx( "es" ), "XmlBeans 3.1 de PDI: PPTX disponible" );
    Captura job = EntornoKettle.captura( "10" );
    nulo( s.confirmacionPerfil( job, "x", "es" ), "un job no tiene perfil" );
    cierto( s.pasosQueEscriben( job ).size() > 0, "el job 10 llama transformaciones que escriben" );
  }

  /** Los graficos salen en el idioma del informe aunque el tema diga otro (o nada). */
  public void pruebaGraficosEnIdiomaDelInforme() throws Exception {
    Informe inf = ModeloMuestra.crear();
    igual( "es", inf.idioma, "la muestra es en castellano" );
    Tema tema = ModeloMuestra.tema();
    tema.idioma = "en";
    String es = "Los steps corren en paralelo";
    String en = "Steps run in parallel";
    File dir = EntornoKettle.salida( "integracion" );

    File html = new File( dir, "idioma.html" );
    new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).escribir( inf, tema, html, Progreso.NULO );
    String h = EntornoKettle.leer( html );
    contiene( h, es, "HTML: leyenda del Gantt en castellano" );
    noContiene( h, en, "HTML: nada en ingles" );

    File md = new File( dir, "idioma.md" );
    new RenderMarkdown().escribir( inf, tema, md, Progreso.NULO );
    File[] svgs = new File( dir, "idioma_archivos" ).listFiles( ( d, n ) -> n.endsWith( ".svg" ) );
    noNulo( svgs, "carpeta de archivos del Markdown" );
    StringBuilder todo = new StringBuilder();
    for ( File f : svgs ) {
      todo.append( EntornoKettle.leer( f ) );
    }
    contiene( todo.toString(), es, "Markdown: SVG del Gantt en castellano" );
    noContiene( todo.toString(), en, "Markdown: nada en ingles" );

    File pptx = new File( dir, "idioma.pptx" );
    new RenderPptx( null ).escribir( inf, tema, pptx, Progreso.NULO );
    StringBuilder media = new StringBuilder();
    try ( ZipFile z = new ZipFile( pptx ) ) {
      for ( Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
        ZipEntry x = e.nextElement();
        if ( x.getName().startsWith( "ppt/media/" ) && x.getName().endsWith( ".svg" ) ) {
          media.append( leer( z.getInputStream( x ) ) );
        }
      }
    }
    contiene( media.toString(), es, "PPTX: SVG del Gantt en castellano" );
    noContiene( media.toString(), en, "PPTX: nada en ingles" );
  }

  /** La guia 04-informes.md tambien es un recurso MCP (clientes sin la skill). */
  public void pruebaGuiaInformesComoRecurso() throws Exception {
    ObjectNode l = new McpResources().listar( null );
    contiene( l.toString(), "pentaho://guide/reports", "resources/list trae la guia de informes" );
  }

  private static String leer( InputStream in ) throws Exception {
    try ( InputStream i = in ) {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] buf = new byte[ 8192 ];
      int n;
      while ( ( n = i.read( buf ) ) > 0 ) {
        b.write( buf, 0, n );
      }
      return new String( b.toByteArray(), StandardCharsets.UTF_8 );
    }
  }
}
