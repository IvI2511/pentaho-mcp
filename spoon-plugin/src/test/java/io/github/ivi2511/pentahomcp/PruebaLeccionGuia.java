package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.pentaho.di.trans.TransMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.navegador.Chromium;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Modo clase, WP6: la guia de la leccion (seccion lesson, {@link SecLeccion} + {@link LeccionGuia}). La L1 de
 * PruebaLeccionMotor reproducida sin Spoon; la guia desde la leccion en memoria (con las filas de clase), desde el
 * registro y las notas tras «reiniciar» (archivo releido, sin filas, tambien con las notas cortadas y sin src.K u
 * ocultas) y exportada por los renderizadores existentes (html, md, pptx, docx y pdf si hay navegador).
 *
 * @dueño clase
 */
public class PruebaLeccionGuia {

  private static final Pattern STEP = Pattern.compile( "<step>(.*?)</step>", Pattern.DOTALL );

  private static String config( String ejemplo, String step ) throws Exception {
    String t = new String( Files.readAllBytes( EntornoKettle.ejemplo( ejemplo ).toPath() ), StandardCharsets.UTF_8 );
    Matcher m = STEP.matcher( t );
    while ( m.find() ) {
      if ( m.group( 1 ).contains( "<name>" + step + "</name>" ) ) {
        return m.group( 1 );
      }
    }
    throw new IllegalStateException( "no step " + step );
  }

  private static ObjectNode paso( String type, String name, String after, String what, String why ) throws Exception {
    ObjectNode p = Tools.obj().put( "type", type ).put( "name", name ).put( "config_xml", config( "02", name ) );
    if ( after != null ) {
      p.put( "after", after );
    }
    return p.put( "what", what ).put( "why", why );
  }

  /** La L1 (filtrar clientes) en espanol, como en PruebaLeccionMotor. */
  private static ObjectNode guionL1() throws Exception {
    ObjectNode a = Tools.obj().put( "title", "Primer ETL: filtrar clientes" ).put( "level", "beginner" )
      .put( "language", "es" ).put( "pace", "manual" ).put( "intro", "Leemos un CSV, filtramos y escribimos." )
      .put( "outro", "Ejercicio: cambia el saldo mínimo y predice cuántas filas salen." );
    ArrayNode s = a.putArray( "steps" );
    s.add( paso( "CsvInput", "Leer clientes", null, "lee datos/clientes.csv, una fila por cliente con su saldo y "
      + "si esta activo", "es la entrada del flujo" ).put( "watch_out", "auto" ).put( "preview_rows", 5 ) );
    s.add( paso( "FilterRows", "Activos con saldo", "Leer clientes", "deja pasar los activos con saldo",
      "separa dos ramas" ).put( "tip", "send_true_to necesita su hop" ).put( "preview_rows", 3 ) );
    s.add( paso( "SelectValues", "Elegir columnas", "Activos con saldo", "elige y renombra columnas",
      "la salida solo lleva lo útil" ).put( "question", "¿Qué pasa si renombras un campo que no existe?" ) );
    s.add( paso( "TextFileOutput", "Escribir filtrados", "Elegir columnas", "escribe el CSV de salida",
      "es el resultado" ).put( "watch_out", "D8: los campos sin declarar no salen" ) );
    s.add( paso( "Dummy", "Descartados", "Activos con saldo", "recibe la rama falsa", "para verla en las métricas" ) );
    return a;
  }

  /** Transformacion vacia "guardada" junto a datos/ de la copia de los ejemplos, con la L1 entera reproducida. */
  private static Leccion l1( String nombre, int hasta ) throws Exception {
    EntornoKettle.iniciar();
    LeccionMotor.olvidarTodas();
    LeccionMotor.SinSpoon ent = new LeccionMotor.SinSpoon( 0 );
    TransMeta tm = new TransMeta();
    tm.setName( nombre );
    tm.setFilename( new File( EntornoKettle.ejemplos(), nombre + ".ktr" ).getAbsolutePath() );
    tm.setInternalKettleVariables();
    Leccion l = LeccionMotor.empezar( ent, guionL1(), tm );
    for ( int i = 0; i < hasta; i++ ) {
      LeccionMotor.avanzar( ent, l, false );
    }
    return l;
  }

  /**
   * La captura del documento vivo, como CapturaUi.tomar (copia + registro). El lienzo pintado lo prueba PruebaServicio
   * (PropsUI con su propio Display); aca, con {@code lienzo}, una imagen PNG cualquiera en su lugar.
   */
  private static Captura captura( TransMeta vivo, boolean lienzo ) throws Exception {
    Captura c = new Captura();
    c.capturado = new Date();
    c.docId = Docs.id( vivo );
    c.nombre = Docs.nombre( vivo );
    c.archivo = vivo.getFilename();
    c.tipo = Captura.TRANSFORMACION;
    c.copia = ReportTools.clonar( vivo );
    LeccionGuia.copiarRegistro( vivo, c.copia );
    if ( lienzo ) {
      BufferedImage bi = new BufferedImage( 640, 360, BufferedImage.TYPE_INT_RGB );
      Graphics2D g = bi.createGraphics();
      g.setColor( new Color( 255, 248, 214 ) );
      g.fillRect( 0, 0, 640, 360 );
      g.setColor( new Color( 190, 160, 70 ) );
      g.drawRect( 40, 40, 220, 80 );
      g.dispose();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write( bi, "png", out );
      c.lienzo = new Imagen( Imagen.PNG, out.toByteArray(), 640, 360, 2f );
    }
    return c;
  }

  private static Pedido pedido( String idioma ) {
    Pedido p = new Pedido();
    p.idioma = idioma;
    p.secciones = new ArrayList<String>( Arrays.asList( SeccionId.LECCION ) );
    return p;
  }

  private static Bloque bloque( Seccion s, String id ) {
    for ( Bloque b : s.bloques ) {
      if ( id.equals( b.id ) ) {
        return b;
      }
    }
    return null;
  }

  private static String items( Seccion s, String id ) {
    Bloque b = bloque( s, id );
    noNulo( b, "bloque " + id );
    return ( (Parrafo) b ).texto;
  }

  // ---------------------------------------------------------------- desde la leccion en memoria

  public void pruebaGuiaDesdeLaLeccionEnMemoria() throws Exception {
    Leccion l = l1( "guia_l1", 5 );
    igual( "done", l.estado.json, "la leccion termino" );
    Captura c = captura( l.tm, false );
    cierto( LeccionGuia.hay( c ), "la captura tiene leccion" );
    nulo( new SecLeccion().noAplica( c, "es" ), "aplica" );
    Seccion s = new SecLeccion().armar( new ContextoPrueba( c, pedido( "es" ), new Tema() ) );
    igual( Seccion.OK, s.estado, "ok: " + s.motivo );
    igual( "Guía de la lección", s.titulo, "titulo de la seccion" );
    contiene( ( (Parrafo) bloque( s, "lesson-titulo" ) ).texto, "**Lección:** Primer ETL: filtrar clientes · "
      + "principiante · 5 pasos", "titulo, nivel y pasos" );
    contiene( ( (Parrafo) bloque( s, "lesson-objetivo" ) ).texto, "Leemos un CSV", "objetivo (intro)" );
    nulo( bloque( s, "lesson-aviso-1" ), "terminada y con filas: sin avisos" );

    // paso 1: que, por que, ojo automatico (D3) con el codigo, glosario y 5 filas
    Parrafo p1 = (Parrafo) bloque( s, "lesson-paso-1" );
    contiene( p1.titulo, "1 · Leer clientes (", "N · Nombre (Tipo)" );
    String i1 = items( s, "lesson-paso-1" );
    contiene( i1, "**Qué:** lee datos/clientes.csv, una fila por cliente", "que" );
    contiene( i1, "**Por qué:** es la entrada del flujo", "por que" );
    contiene( i1, "**¡Ojo!** D3", "ojo automatico del glosario" );
    contiene( i1, "código del manual: D3", "con el codigo del manual" );
    contiene( i1, "**Glosario:** Lee un archivo de texto delimitado", "glosario del tipo en espanol" );
    Tabla f1 = (Tabla) bloque( s, "lesson-filas-1" );
    noNulo( f1, "filas del paso 1" );
    igual( 5, f1.filas.size(), "5 filas capturadas en clase" );
    contiene( f1.titulo, "«Leer clientes» (5)", "titulo de las filas" );
    cierto( f1.columnas.size() >= 3, "las columnas del CSV: " + f1.columnas );

    // paso 2: tip y la vista previa pospuesta del Filter rows (sale en su paso, con sus filas)
    contiene( items( s, "lesson-paso-2" ), "**Tip:** send_true_to necesita su hop", "tip" );
    Tabla f2 = (Tabla) bloque( s, "lesson-filas-2" );
    noNulo( f2, "la vista previa pospuesta del Filter rows queda en la guia de su paso" );
    cierto( f2.filas.size() > 0 && f2.filas.size() <= 3, "hasta 3 filas: " + f2.filas.size() );
    igual( s.bloques.indexOf( bloque( s, "lesson-paso-2" ) ) + 1, s.bloques.indexOf( f2 ), "las filas despues del "
      + "paso" );
    contiene( items( s, "lesson-paso-3" ), "**¿Pregunta?** ¿Qué pasa si renombras", "pregunta" );
    String i4 = items( s, "lesson-paso-4" );
    contiene( i4, "**¡Ojo!** D8: los campos sin declarar no salen", "ojo escrito a mano" );
    contiene( i4, "código del manual: D8", "codigo del ojo escrito a mano" );
    nulo( bloque( s, "lesson-filas-4" ), "sin vista previa pedida, sin filas" );

    // ejercicio y errores comunes, en orden
    Parrafo ej = (Parrafo) bloque( s, "lesson-ejercicio" );
    igual( "Ejercicio", ej.titulo, "titulo del ejercicio" );
    igual( "Cambia el saldo mínimo y predice cuántas filas salen.", ej.texto, "outro sin repetir «Ejercicio:»" );
    Tabla te = (Tabla) bloque( s, "lesson-errores" );
    noNulo( te, "tabla de errores comunes" );
    List<String> codigos = new ArrayList<String>();
    for ( List<String> f : te.filas ) {
      codigos.add( f.get( 0 ) );
    }
    cierto( codigos.containsAll( Arrays.asList( "C15", "D3", "C20" ) ), "errores de CsvInput y FilterRows: "
      + codigos );
    cierto( codigos.indexOf( "C15" ) < codigos.indexOf( "D3" ), "C antes que D" );
    cierto( s.bloques.indexOf( ej ) < s.bloques.indexOf( te ), "ejercicio y despues los errores" );
    cierto( s.bloques.indexOf( bloque( s, "lesson-paso-5" ) ) < s.bloques.indexOf( ej ), "los pasos antes" );

    // contrato LessonHandout
    JsonNode d = s.datos;
    igual( "lesson", d.path( "section" ).asText(), "section" );
    igual( "memory", d.path( "source" ).asText(), "de la memoria" );
    igual( 5, d.path( "steps" ).size(), "5 pasos" );
    igual( "CsvInput", d.path( "steps" ).get( 0 ).path( "type_id" ).asText(), "type_id" );
    igual( 5, d.path( "steps" ).get( 0 ).path( "preview" ).path( "rows" ).size(), "filas en el JSON" );
    igual( "D3", d.path( "steps" ).get( 0 ).path( "watch_out_codes" ).get( 0 ).asText(), "codigos en el JSON" );
    contiene( s.resumen, "2 vistas previas capturadas en clase", "resumen" );

    // en ingles: etiquetas y glosario en ingles, el texto del guion tal cual
    Seccion en = new SecLeccion().armar( new ContextoPrueba( c, pedido( "en" ), new Tema() ) );
    contiene( items( en, "lesson-paso-1" ), "**What:** lee datos/clientes.csv", "etiqueta en ingles" );
    contiene( items( en, "lesson-paso-1" ), "**Glossary:** Reads a delimited text file", "glosario en ingles" );
    igual( "Lesson handout", en.titulo, "titulo en ingles" );
  }

  public void pruebaLeccionAMedias() throws Exception {
    Leccion l = l1( "guia_medias", 2 );
    Seccion s = new SecLeccion().armar( new ContextoPrueba( captura( l.tm, false ), pedido( "es" ), new Tema() ) );
    Aviso a = (Aviso) bloque( s, "lesson-aviso-1" );
    noNulo( a, "aviso de leccion a medias" );
    contiene( a.texto, "paso 2 de 5", "por donde va" );
    contiene( items( s, "lesson-paso-4" ), "todavía no se dio en clase", "los que faltan, marcados" );
    noNulo( bloque( s, "lesson-filas-1" ), "las filas del paso 1 ya estan" );
  }

  // ---------------------------------------------------------------- desde las notas (Spoon reiniciado)

  public void pruebaGuiaDesdeLasNotasTrasReiniciar() throws Exception {
    Leccion l = l1( "guia_notas", 5 );
    File ktr = new File( EntornoKettle.salida( "guia" ), "guia_notas.ktr" );
    EntornoKettle.escribir( ktr, l.tm.getXML() );
    LeccionMotor.olvidarTodas(); // «reiniciar Spoon»: no queda la leccion en memoria
    Captura c = InformeServicio.capturarArchivo( ktr.getPath(), null );
    cierto( LeccionGuia.hay( c ), "el registro viaja en el .ktr" );
    nulo( LeccionGuia.enMemoria( c ), "sin leccion en memoria" );
    Seccion s = new SecLeccion().armar( new ContextoPrueba( c, pedido( "es" ), new Tema() ) );
    igual( Seccion.OK, s.estado, "ok" );
    igual( "notes", s.datos.path( "source" ).asText(), "de las notas" );
    contiene( ( (Aviso) bloque( s, "lesson-aviso-1" ) ).texto, "sin las filas", "avisa que no hay filas" );
    contiene( ( (Parrafo) bloque( s, "lesson-titulo" ) ).texto, "Primer ETL: filtrar clientes · principiante · "
      + "5 pasos", "titulo del registro" );
    contiene( ( (Parrafo) bloque( s, "lesson-objetivo" ) ).texto, "Leemos un CSV", "intro de la nota del titulo" );
    String i1 = items( s, "lesson-paso-1" );
    contiene( i1, "**Qué:** lee datos/clientes.csv, una fila por cliente con su saldo y si esta activo",
      "que, de src.K" );
    contiene( i1, "**¡Ojo!** D3", "ojo de la nota" );
    contiene( ( (Parrafo) bloque( s, "lesson-paso-1" ) ).titulo, "1 · Leer clientes (", "cabecera con el tipo" );
    contiene( items( s, "lesson-paso-2" ), "**Tip:** send_true_to necesita su hop", "tip de la nota" );
    contiene( items( s, "lesson-paso-3" ), "**¿Pregunta?** ¿Qué pasa si renombras", "pregunta de la nota" );
    for ( Bloque b : s.bloques ) {
      falso( b.id != null && b.id.startsWith( "lesson-filas-" ), "sin filas: " + b.id );
    }
    nulo( bloque( s, "lesson-ejercicio" ), "el outro no es una nota" );
    noNulo( bloque( s, "lesson-errores" ), "errores comunes igual" );
    igual( 5, s.datos.path( "steps" ).size(), "5 pasos desde el registro" );

    // sin src.K: el texto sale de las notas del lienzo (cortadas en lineas) y, ocultas, del atributo hidden
    TransMeta tm = (TransMeta) c.copia;
    int n = LeccionNotas.entero( tm.getAttribute( LeccionNotas.GRUPO, LeccionNotas.K_NOTAS ), 0 );
    for ( int k = 1; k <= n; k++ ) {
      tm.setAttribute( LeccionNotas.GRUPO, LeccionNotas.P_SRC + k, "" );
    }
    cierto( tm.nrNotes() > 0 && tm.getNote( 1 ).getNote().contains( "\n" ), "notas cortadas en lineas" );
    Seccion s2 = new SecLeccion().armar( new ContextoPrueba( c, pedido( "es" ), new Tema() ) );
    contiene( items( s2, "lesson-paso-1" ), "**Qué:** lee datos/clientes.csv, una fila por cliente",
      "que de la nota cortada, con las lineas unidas" );
    // la nota del lienzo puede estar recortada (unas 6 lineas, §4.2): sale tal cual, con su «…»; el corte acorta el
    // parrafo mas largo (aqui el «Qué») y el «Por qué» queda entero
    contiene( items( s2, "lesson-paso-1" ), "**Por qué:** es la entrada", "por que de la nota" );
    LeccionNotas.ocultar( tm, new LeccionMotor.SinSpoon( 0 ).grupo( tm ) );
    igual( 0, LeccionNotas.reconocer( tm ).size(), "notas ocultas" );
    Seccion s3 = new SecLeccion().armar( new ContextoPrueba( c, pedido( "es" ), new Tema() ) );
    contiene( items( s3, "lesson-paso-2" ), "**Tip:** send_true_to necesita su hop", "tip de las ocultas" );
    boolean avisoOcultas = false;
    for ( Bloque b : s3.bloques ) {
      avisoOcultas |= b instanceof Aviso && ( (Aviso) b ).texto.contains( "ocultas" );
    }
    cierto( avisoOcultas, "avisa que la imagen no muestra las notas" );
  }

  public void pruebaNoAplica() throws Exception {
    EntornoKettle.iniciar();
    LeccionMotor.olvidarTodas();
    Captura c = EntornoKettle.captura( "03" );
    falso( LeccionGuia.hay( c ), "un ejemplo sin leccion" );
    contiene( new SecLeccion().noAplica( c, "es" ), "no tiene una lección", "motivo" );
    contiene( new SecLeccion().noAplica( EntornoKettle.captura( "11" ), "es" ), "solo para transformaciones",
      "jobs" );
    Pedido p = new Pedido();
    p.idioma = "es";
    falso( InformeServicio.seleccion( c, p, new Tema(), new ArrayList<String>() ).contains( SeccionId.LECCION ),
      "nunca por defecto" );
    falso( InformeServicio.porDefecto( SeccionId.LECCION, c ), "no marcada por defecto" );
    Informe inf = InformeServicio.armarAhora( c, pedido( "es" ), new Tema(), null );
    igual( 1, inf.secciones.size(), "pedida explicita: sale" );
    igual( Seccion.OMITIDA, inf.secciones.get( 0 ).estado, "omitida con su motivo" );
  }

  // ---------------------------------------------------------------- exportar con los renderizadores

  public void pruebaExportaEnTodosLosFormatos() throws Exception {
    Leccion l = l1( "guia_export", 5 );
    Captura c = captura( l.tm, true );
    Informe inf = InformeServicio.armarAhora( c, pedido( "es" ), new Tema(), null );
    igual( 1, inf.secciones.size(), "solo la guia" );
    Seccion s = inf.secciones.get( 0 );
    igual( Seccion.OK, s.estado, "ok: " + s.motivo );
    Imagen img = (Imagen) bloque( s, "lesson-lienzo" );
    noNulo( img, "imagen del lienzo con las notas" );
    cierto( s.bloques.indexOf( img ) < s.bloques.indexOf( bloque( s, "lesson-paso-1" ) ), "la imagen antes de los "
      + "pasos" );
    File dir = EntornoKettle.salida( "guia-export" );
    Tema t = new Tema();

    File md = new File( dir, "guia.md" );
    exportar( inf, t, Formato.MARKDOWN, md );
    String m = EntornoKettle.leer( md );
    contiene( m, "## ", "seccion en Markdown" );
    contiene( m, "### 1 · Leer clientes (", "paso como subtitulo" );
    contiene( m, "**Qué:** lee datos/clientes.csv", "que" );
    contiene( m, "### Filas tras «Leer clientes» (5)", "filas" );
    contiene( m, "### Ejercicio", "ejercicio" );
    contiene( m, "| C15 |", "errores comunes" );
    noContiene( m, EntornoKettle.ejemplos().getAbsolutePath(), "sin rutas locales" );

    File html = new File( dir, "guia.html" );
    exportar( inf, t, Formato.HTML, html );
    String h = EntornoKettle.leer( html );
    contiene( h, "Leer clientes", "html con los pasos" );
    contiene( h, "data:image/png;base64", "html con el lienzo" );

    File docx = new File( dir, "guia.docx" );
    exportar( inf, t, Formato.DOCX, docx );
    String w = parte( docx, "word/document.xml" );
    contiene( w, "Leer clientes", "docx con los pasos" );
    contiene( w, "Ejercicio", "docx con el ejercicio" );
    cierto( entradas( docx, "word/media/" ) >= 1, "docx con la imagen del lienzo" );

    File pptx = new File( dir, "guia.pptx" );
    exportar( inf, t, Formato.PPTX, pptx );
    int diapos = entradas( pptx, "ppt/slides/slide" );
    cierto( diapos >= 3, "pptx con diapositivas: " + diapos );
    StringBuilder todo = new StringBuilder();
    try ( ZipFile z = new ZipFile( pptx ) ) {
      for ( ZipEntry e : java.util.Collections.list( z.entries() ) ) {
        if ( e.getName().startsWith( "ppt/slides/slide" ) && e.getName().endsWith( ".xml" ) ) {
          todo.append( leer( z, e ) );
        }
      }
    }
    contiene( todo.toString(), "Leer clientes", "pptx con los pasos" );
    contiene( todo.toString(), "5 · Descartados", "pptx con todos los pasos (un paso no es detalle de consulta)" );

    File xlsx = new File( dir, "guia.xlsx" );
    exportar( inf, t, Formato.XLSX, xlsx );
    cierto( xlsx.length() > 0, "xlsx" );

    if ( Chromium.ubicar() != null ) {
      File pdf = new File( dir, "guia.pdf" );
      exportar( inf, t, Formato.PDF, pdf );
      cierto( pdf.length() > 10000, "pdf con la imagen: " + pdf.length() );
    } else {
      System.out.println( "  (sin navegador: el PDF no se prueba)" );
    }
  }

  private static void exportar( Informe inf, Tema t, Formato f, File destino ) throws Exception {
    Resultado r = InformeServicio.exportar( inf, t, f, destino, Progreso.NULO );
    noNulo( r, "resultado de " + f );
    cierto( destino.isFile() && destino.length() > 0, f + " escrito: " + destino );
  }

  private static String parte( File zip, String nombre ) throws Exception {
    try ( ZipFile z = new ZipFile( zip ) ) {
      ZipEntry e = z.getEntry( nombre );
      noNulo( e, nombre + " en " + zip.getName() );
      return leer( z, e );
    }
  }

  private static String leer( ZipFile z, ZipEntry e ) throws Exception {
    try ( InputStream in = z.getInputStream( e ) ) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[ 8192 ];
      int n;
      while ( ( n = in.read( b ) ) > 0 ) {
        out.write( b, 0, n );
      }
      return new String( out.toByteArray(), StandardCharsets.UTF_8 );
    }
  }

  private static int entradas( File zip, String prefijo ) throws Exception {
    int n = 0;
    try ( ZipFile z = new ZipFile( zip ) ) {
      for ( ZipEntry e : java.util.Collections.list( z.entries() ) ) {
        if ( e.getName().startsWith( prefijo ) && !e.getName().endsWith( ".rels" ) ) {
          n++;
        }
      }
    }
    return n;
  }
}
