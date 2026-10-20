package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.pentaho.di.core.Const;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.informes.tema.Paleta;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Ajustes (diseno §4.1, WP6): lectura y escritura de informes.json y del logo con un KETTLE_HOME
 * temporal (el de test.ps1), escritura atomica, defaults si falta o esta roto, logo solo PNG/JPG de
 * hasta 2 MB, colores invalidos. Al terminar deja la carpeta pentaho-live como estaba (sin ajustes),
 * asi las demas pruebas ven la plantilla neutra.
 *
 * @dueño WP6
 */
public class PruebaAjustes {

  public void preparar() throws IOException {
    EntornoKettle.verificarAislado();
    limpiar();
  }

  public void cerrar() throws IOException {
    limpiar();
  }

  public void pruebaCarpetaEnKettleHome() {
    File c = Ajustes.carpeta();
    igual( new File( Const.getKettleDirectory(), "pentaho-live" ).getAbsolutePath(), c.getAbsolutePath(),
      "carpeta = <KETTLE_HOME>\\.kettle\\pentaho-live" );
    cierto( c.getAbsolutePath().toLowerCase().startsWith( EntornoKettle.carpeta().getAbsolutePath().toLowerCase() ),
      "la carpeta de ajustes queda dentro de la de prueba: " + c );
    igual( new File( c, "informes.json" ), Ajustes.archivo(), "archivo" );
  }

  public void pruebaSinArchivo() throws IOException {
    limpiar();
    List<String> avisos = new ArrayList<>();
    Tema t = Ajustes.cargar( "es", avisos );
    noNulo( t, "cargar nunca da null" );
    igual( 0, avisos.size(), "sin archivo no hay avisos: " + avisos );
    igual( Tema.PRIMARIO, t.colores.primario, "primario por defecto" );
    igual( Tema.A4, t.pagina, "A4 por defecto" );
    cierto( t.marcaDeAgua, "marca de agua por defecto" );
    nulo( t.logoDatos, "sin logo" );
    noNulo( Ajustes.cargar(), "cargar() sin argumentos" );
  }

  public void pruebaIdaYVuelta() throws Exception {
    limpiar();
    Tema t = new Tema();
    t.identidad.autor = "Iván Castaño";
    t.identidad.institucion = "Universidad Europea de Madrid";
    t.identidad.curso = "Big Data I";
    t.colores.primario = "#3f74d8";
    t.colores.acento = "1C9A80";
    t.colores.secundario = "#a1b";
    t.pagina = Tema.LETTER;
    t.idioma = "pt";
    t.marcaDeAgua = false;
    t.rotularIa = false;
    t.salida.carpeta = "C:\\informes";
    t.salida.formatos = new ArrayList<>( Arrays.asList( "html", "md", "pptx" ) );
    t.salida.agregarFecha = true;
    t.secciones = Arrays.asList( "cover", "summary", "flow" );
    t.perfil.filas = 1234;
    t.perfil.mostrarValores = false;
    t.navegador = "C:\\Edge\\msedge.exe";
    byte[] logo = ModeloMuestra.png( 120, 40, "LOGO" );
    t.logoDatos = logo;
    Ajustes.guardar( t );

    File dir = Ajustes.carpeta();
    archivo( new File( dir, "informes.json" ), 50, "informes.json" );
    archivo( new File( dir, "logo.png" ), 100, "logo.png" );
    falso( new File( dir, "logo.jpg" ).exists(), "no hay logo.jpg" );
    falso( new File( dir, "informes.json.tmp" ).exists(), "no queda el .tmp" );
    falso( new File( dir, "logo.png.tmp" ).exists(), "no queda el .tmp del logo" );
    igual( "logo.png", t.logo, "guardar ajusta t.logo" );
    igual( Ajustes.MIME_PNG, t.logoMime, "guardar ajusta t.logoMime" );

    String json = EntornoKettle.leer( Ajustes.archivo() );
    JsonNode n = ModeloJson.mapper().readTree( json );
    igual( "logo.png", n.get( "logo" ).asText(), "logo en el JSON" );
    igual( "#3F74D8", n.get( "colores" ).get( "primario" ).asText(), "color normalizado en el JSON" );
    igual( false, n.get( "marca_de_agua" ).asBoolean(), "marca_de_agua en snake_case (§4.1)" );
    igual( true, n.get( "salida" ).get( "agregar_fecha" ).asBoolean(), "agregar_fecha" );
    falso( n.has( "logoDatos" ) || json.contains( "iVBORw0KGgo" ), "los bytes del logo no van al JSON" );
    contiene( json, "Iván Castaño", "UTF-8 sin escapar" );

    List<String> avisos = new ArrayList<>();
    Tema l = Ajustes.cargar( "es", avisos );
    igual( 0, avisos.size(), "sin avisos: " + avisos );
    igual( "Iván Castaño", l.identidad.autor, "autor" );
    igual( "Universidad Europea de Madrid", l.identidad.institucion, "institucion" );
    igual( "Big Data I", l.identidad.curso, "curso" );
    igual( "#3F74D8", l.colores.primario, "primario" );
    igual( "#1C9A80", l.colores.acento, "acento" );
    igual( "#AA11BB", l.colores.secundario, "secundario de 3 digitos" );
    igual( Tema.LETTER, l.pagina, "pagina" );
    igual( "pt", l.idioma, "idioma" );
    falso( l.marcaDeAgua, "marcaDeAgua" );
    falso( l.rotularIa, "rotularIa" );
    igual( "C:\\informes", l.salida.carpeta, "salida.carpeta" );
    igual( Arrays.asList( "html", "md", "pptx" ), l.salida.formatos, "formatos" );
    cierto( l.salida.agregarFecha, "agregarFecha" );
    igual( Arrays.asList( "cover", "summary", "flow" ), l.secciones, "secciones" );
    igual( 1234, l.perfil.filas, "perfil.filas" );
    falso( l.perfil.mostrarValores, "perfil.mostrarValores" );
    igual( "C:\\Edge\\msedge.exe", l.navegador, "navegador" );
    igual( "logo.png", l.logo, "logo" );
    igual( Ajustes.MIME_PNG, l.logoMime, "logoMime" );
    cierto( Arrays.equals( logo, l.logoDatos ), "los bytes del logo vuelven iguales" );

    // guardar otra vez lo mismo no cambia nada
    long antes = new File( dir, "logo.png" ).lastModified();
    Ajustes.guardar( l );
    igual( antes, new File( dir, "logo.png" ).lastModified(), "el logo igual no se reescribe" );
  }

  public void pruebaLogoJpgYQuitar() throws Exception {
    limpiar();
    Tema t = new Tema();
    t.logoDatos = ModeloMuestra.png( 60, 30, "L" );
    Ajustes.guardar( t );
    File dir = Ajustes.carpeta();
    cierto( new File( dir, "logo.png" ).isFile(), "primero un PNG" );

    File jpg = new File( EntornoKettle.salida( "ajustes" ), "logo-elegido.jpeg" );
    Files.write( jpg.toPath(), jpg( 80, 40 ) );
    Ajustes.ponerLogo( t, jpg, "es" );
    igual( Ajustes.MIME_JPG, t.logoMime, "mime por el contenido" );
    igual( "logo.jpg", t.logo, "nombre del logo" );
    Ajustes.guardar( t );
    cierto( new File( dir, "logo.jpg" ).isFile(), "logo.jpg" );
    falso( new File( dir, "logo.png" ).exists(), "el PNG viejo se borra" );
    Tema l = Ajustes.cargar();
    igual( Ajustes.MIME_JPG, l.logoMime, "vuelve como JPG" );

    Ajustes.quitarLogo( l );
    Ajustes.guardar( l );
    falso( new File( dir, "logo.jpg" ).exists(), "quitar el logo borra el archivo" );
    falso( ModeloJson.mapper().readTree( EntornoKettle.leer( Ajustes.archivo() ) ).has( "logo" ),
      "sin logo en el JSON" );
    nulo( Ajustes.cargar().logoDatos, "sin logo al volver a cargar" );
  }

  public void pruebaLogoInvalido() throws Exception {
    limpiar();
    File sal = EntornoKettle.salida( "ajustes" );
    Tema t = new Tema();
    t.identidad.autor = "antes";
    Ajustes.guardar( t );

    File svg = new File( sal, "logo.svg" );
    Files.write( svg.toPath(), ModeloMuestra.svgMuestra( "X", 40, 20 ).getBytes( StandardCharsets.UTF_8 ) );
    IOException e = lanza( IOException.class, () -> Ajustes.ponerLogo( t, svg, "es" ), "SVG como logo" );
    contiene( e.getMessage(), "PNG o JPG", "mensaje traducido" );
    nulo( t.logoDatos, "el tema no cambia" );

    File grande = new File( sal, "grande.png" );
    byte[] b = new byte[ (int) Ajustes.LOGO_MAX + 1 ];
    System.arraycopy( ModeloMuestra.png( 10, 10, "" ), 0, b, 0, 8 );
    Files.write( grande.toPath(), b );
    e = lanza( IOException.class, () -> Ajustes.ponerLogo( t, grande, "en" ), "logo de mas de 2 MB" );
    contiene( e.getMessage(), "2 MB", "mensaje del tope" );

    lanza( IOException.class, () -> Ajustes.ponerLogo( t, new File( sal, "no-existe.png" ), "pt" ),
      "logo inexistente" );

    // guardar con bytes que no son imagen: falla y no toca lo guardado
    t.identidad.autor = "despues";
    t.logoDatos = "<svg/>".getBytes( StandardCharsets.UTF_8 );
    lanza( IOException.class, () -> Ajustes.guardar( t ), "guardar un logo que no es PNG/JPG" );
    igual( "antes", Ajustes.cargar().identidad.autor, "lo guardado sigue intacto" );
  }

  public void pruebaArchivoRoto() throws Exception {
    limpiar();
    File dir = Ajustes.carpeta();
    dir.mkdirs();
    EntornoKettle.escribir( Ajustes.archivo(), "{ \"identidad\": { roto" );
    List<String> avisos = new ArrayList<>();
    Tema t = Ajustes.cargar( "es", avisos );
    igual( Tema.PRIMARIO, t.colores.primario, "defaults" );
    igual( 1, avisos.size(), "un aviso" );
    contiene( avisos.get( 0 ), "No se pudieron leer los ajustes", "aviso en castellano" );

    t.identidad.autor = "nuevo";
    Ajustes.guardar( t );
    archivo( new File( dir, Ajustes.ARCHIVO_ROTO ), 5, "copia del JSON roto" );
    contiene( EntornoKettle.leer( new File( dir, Ajustes.ARCHIVO_ROTO ) ), "roto", "con el contenido viejo" );
    igual( "nuevo", Ajustes.cargar().identidad.autor, "el nuevo se lee bien" );

    // vacio o "null" tambien cuentan como roto
    EntornoKettle.escribir( Ajustes.archivo(), "null" );
    avisos.clear();
    noNulo( Ajustes.cargar( "en", avisos ), "null en el archivo" );
    igual( 1, avisos.size(), "aviso por null" );
  }

  public void pruebaValoresRaros() throws Exception {
    limpiar();
    Ajustes.carpeta().mkdirs();
    EntornoKettle.escribir( Ajustes.archivo(), "\uFEFF{\"version\": 7, \"identidad\": null, \"colores\": {\"primario\":"
      + " \"azul\", \"acento\": \"#38d\"}, \"pagina\": \"a5\", \"idioma\": \"es_AR\", \"salida\": {\"formatos\":"
      + " null, \"html\": \"raro\"}, \"perfil\": {\"filas\": -3}, \"navegador\": null, \"logo\": \"..\\\\..\\\\x.png\","
      + " \"desconocida\": 1}" );
    List<String> avisos = new ArrayList<>();
    Tema t = Ajustes.cargar( "es", avisos );
    noNulo( t.identidad, "identidad null se completa" );
    igual( "", t.identidad.autor, "autor vacio" );
    igual( Tema.PRIMARIO, t.colores.primario, "color invalido = default" );
    igual( "#3388DD", t.colores.acento, "#38d normalizado" );
    igual( Tema.SECUNDARIO, t.colores.secundario, "secundario que falta" );
    igual( Tema.A4, t.pagina, "pagina desconocida = A4" );
    igual( "es", t.idioma, "es_AR = es" );
    igual( Arrays.asList( "html", "pdf" ), t.salida.formatos, "formatos null = default" );
    igual( Tema.HTML_INTERACTIVO, t.salida.html, "html desconocido = interactivo" );
    igual( 5000, t.perfil.filas, "filas negativas = default" );
    igual( "", t.navegador, "navegador null = vacio" );
    nulo( t.logo, "un logo con ruta no se lee" );
    nulo( t.logoDatos, "ni sus bytes" );
    igual( 2, avisos.size(), "avisos por el color y por el logo: " + avisos );
    contiene( avisos.get( 0 ), "azul", "aviso del color" );
    contiene( avisos.get( 1 ), "logo.png o logo.jpg", "aviso del logo" );

    // logo nombrado pero que no esta
    EntornoKettle.escribir( Ajustes.archivo(), "{\"logo\": \"logo.png\"}" );
    avisos.clear();
    t = Ajustes.cargar( "en", avisos );
    nulo( t.logo, "logo faltante" );
    igual( 1, avisos.size(), "aviso del logo faltante" );
    contiene( avisos.get( 0 ), "not found", "aviso en ingles" );
    igual( Paleta.normalizar( "#38d", null ), "#3388DD", "Paleta.normalizar" );
  }

  // ------------------------------------------------------------------ ayudas

  private static byte[] jpg( int w, int h ) throws IOException {
    BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
    Graphics2D g = img.createGraphics();
    g.setColor( new Color( 0x24, 0x3B, 0x53 ) );
    g.fillRect( 0, 0, w, h );
    g.dispose();
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    ImageIO.write( img, "jpg", bo );
    return bo.toByteArray();
  }

  private static void limpiar() throws IOException {
    File c = Ajustes.carpeta();
    if ( c.exists() ) {
      EntornoKettle.borrar( c.toPath() );
    }
  }
}
