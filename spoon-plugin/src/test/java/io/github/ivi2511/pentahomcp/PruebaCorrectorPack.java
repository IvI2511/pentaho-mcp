package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.CorrectorPack.PackInvalido;
import io.github.ivi2511.pentahomcp.CorrectorPack.Problema;
import io.github.ivi2511.pentahomcp.CorrectorPack.ZipRechazado;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * El pack (C1, docs/corrector-diseno.md §2 y §7): los tres formatos (carpeta, .zip, .json inline) cargan igual;
 * rutas con .., absolutas o por un enlace que sale, rechazadas; zip slip, bombas, nombres de Windows y repetidos;
 * el esquema con un error legible por cosa (donde en pack.json + texto en en/es/pt); copia para alumnos. Sin
 * Kettle: CorrectorPack no lo necesita.
 *
 * @dueño C1
 */
public class PruebaCorrectorPack {

  private static final AtomicInteger N = new AtomicInteger();
  private static boolean limpio;

  // ------------------------------------------------------------------ los tres formatos

  public void pruebaCarpeta() throws Exception {
    File r = CorrectorRecursos.copia();
    File dir = new File( r, "packs/" + CorrectorRecursos.VENTAS );
    List<String> antes = listar( dir );
    try ( CorrectorPack p = CorrectorPack.abrir( dir ) ) {
      sinProblemas( p );
      cierto( p.valido(), "valido" );
      igual( CorrectorPack.FORMA_CARPETA, p.forma(), "forma" );
      igual( dir.getCanonicalFile(), p.raiz().getCanonicalFile(), "la raiz es la carpeta del docente" );
      igual( "es", p.idioma(), "idioma" );
      Caso visible = p.pack().caso( "visible" );
      Caso oculto = p.pack().caso( "oculto" );
      cierto( p.dirEntradas( visible ).isDirectory(), "carpeta de entradas del visible" );
      Map<String, File> e = p.entradas( oculto );
      igual( Collections.singletonList( "ventas.csv" ), new ArrayList<>( e.keySet() ), "entradas del oculto" );
      archivo( e.get( "ventas.csv" ), 50, "ventas.csv del oculto" );
      archivo( p.esperado( oculto ), 50, "esperado del oculto" );
      archivo( p.solucion(), 1000, "solucion" );
      contiene( p.textoEnunciado(), "Ventas por producto", "enunciado" );
      nulo( p.plantilla(), "sin plantilla (captura file)" );
      igual( "Ejercicio 3 - Ventas por producto · 2 casos (1 oculto) · 10 pts", p.resumen( "es" ), "resumen es" );
      igual( "Ejercicio 3 - Ventas por producto · 2 cases (1 hidden) · 10 pts", p.resumen( "en" ), "resumen en" );
      lanza( IllegalArgumentException.class, () -> p.archivo( "../fuera.csv" ), "archivo() no sale del pack" );
    }
    igual( antes, listar( dir ), "abrir no escribe nada en la carpeta del docente" );

    // el pack.json mismo: las rutas son relativas a su carpeta
    try ( CorrectorPack p = CorrectorPack.abrir( new File( dir, "pack.json" ) ) ) {
      sinProblemas( p );
      igual( CorrectorPack.FORMA_CARPETA, p.forma(), "pack.json suelto = carpeta" );
      igual( dir.getCanonicalFile(), p.raiz().getCanonicalFile(), "raiz del pack.json" );
    }

    // una carpeta que solo tiene la del pack adentro (zip descomprimido a mano)
    File envoltorio = new File( r, "envoltorio" );
    copiarCarpeta( dir, new File( envoltorio, CorrectorRecursos.VENTAS ) );
    try ( CorrectorPack p = CorrectorPack.abrir( envoltorio ) ) {
      sinProblemas( p );
      igual( new File( envoltorio, CorrectorRecursos.VENTAS ).getCanonicalFile(), p.raiz().getCanonicalFile(),
        "raiz en la unica subcarpeta" );
    }
    // packs/ tiene varias carpetas y ningun pack.json
    try ( CorrectorPack p = CorrectorPack.abrir( new File( r, "packs" ) ) ) {
      unError( p, CorrectorPack.SIN_PACK_JSON, "packs" );
      nulo( p.pack(), "sin pack" );
    }
  }

  public void pruebaTabla() throws Exception {
    File dir = new File( CorrectorRecursos.copia(), "packs/" + CorrectorRecursos.TABLA );
    try ( CorrectorPack p = CorrectorPack.abrir( dir ) ) {
      sinProblemas( p );
      igual( Pack.Salida.CAPTURA_QUERY, p.pack().salida.captura, "captura query" );
      archivo( p.plantilla(), 1000, "plantilla SQLite" );
      igual( "Ejercicio 3b - Totales por producto a una tabla · 1 caso · 10 pts", p.resumen( "es" ), "resumen" );
    }
  }

  public void pruebaInline() throws Exception {
    File json = new File( CorrectorRecursos.copia(), "packs/" + CorrectorRecursos.INLINE + ".json" );
    JsonNode arbol = CorrectorJson.mapper().readTree( json );
    File t = trabajo();
    try ( CorrectorPack p = CorrectorPack.abrir( json, t ) ) {
      sinProblemas( p );
      igual( CorrectorPack.FORMA_JSON, p.forma(), "forma" );
      igual( t.getCanonicalFile(), p.raiz().getCanonicalFile(), "raiz en la carpeta de trabajo" );
      int n = 0;
      for ( Iterator<Map.Entry<String, JsonNode>> it = arbol.get( "inline" ).fields(); it.hasNext(); n++ ) {
        Map.Entry<String, JsonNode> e = it.next();
        igual( e.getValue().asText(), leer( new File( t, e.getKey() ), StandardCharsets.UTF_8 ),
          "contenido de " + e.getKey() );
      }
      igual( 3, n, "tres archivos inline" );
      JsonNode escrito = CorrectorJson.mapper().readTree( new File( t, CorrectorPack.PACK_JSON ) );
      nulo( escrito.get( "inline" ), "el pack.json escrito no lleva inline" );
      igual( arbol.get( "rubric" ), escrito.get( "rubric" ), "el pack.json escrito es el del docente" );
      archivo( p.entradas( p.pack().caso( "visible" ) ).get( "ventas.csv" ), 50, "ventas.csv escrito" );
      nulo( p.solucion(), "copia para alumnos: sin solucion" );
      contiene( p.textoEnunciado(), "Ventas por producto", "enunciado inline" );
    }
    cierto( t.isDirectory(), "la carpeta de trabajo de quien llama queda despues de close()" );
    falso( new File( json.getParentFile(), "enunciado.md" ).exists(), "nada al lado del .json" );
    falso( new File( json.getParentFile(), "pack.json" ).exists(), "ni un pack.json al lado del .json" );

    // la carpeta escrita es un pack de carpeta valido (la que recibe el worker)
    try ( CorrectorPack q = CorrectorPack.abrir( t ) ) {
      sinProblemas( q );
      igual( "ventas-inline", q.pack().id, "id" );
    }

    // sin carpeta de trabajo: temporal, y close() la borra
    File raiz;
    try ( CorrectorPack p = CorrectorPack.abrir( json ) ) {
      sinProblemas( p );
      raiz = p.raiz();
      cierto( raiz.getCanonicalPath().startsWith( new File( System.getProperty( "java.io.tmpdir" ) )
        .getCanonicalPath() ), "temporal en java.io.tmpdir: " + raiz );
    }
    falso( raiz.exists(), "close() borra la temporal" );

    // el esperado inline se escribe con el encoding de expected_csv
    File latin = json( "latin", n -> {
      ( (ObjectNode) n.with( "output" ).with( "expected_csv" ) ).put( "encoding", "ISO-8859-1" );
    } );
    File t2 = trabajo();
    try ( CorrectorPack p = CorrectorPack.abrir( latin, t2 ) ) {
      sinProblemas( p );
      byte[] b = Files.readAllBytes( new File( t2, "esperado/visible.csv" ).toPath() );
      contiene( new String( b, Charset.forName( "ISO-8859-1" ) ), "Café;8;20.00", "esperado en Latin-1" );
      contiene( leer( new File( t2, "datos/visible/ventas.csv" ), StandardCharsets.UTF_8 ), "Café", "datos en UTF-8" );
    }

    lanza( IllegalArgumentException.class, () -> CorrectorPack.abrir( json, json.getParentFile() ),
      "la carpeta de trabajo tiene que estar vacia" );
  }

  public void pruebaZip() throws Exception {
    File r = CorrectorRecursos.copia();
    File dir = new File( r, "packs/" + CorrectorRecursos.VENTAS );
    File zip = new File( r, "ventas.zip" );
    CorrectorPack.comprimir( dir, zip );
    File t = trabajo();
    try ( CorrectorPack p = CorrectorPack.abrir( zip, t ) ) {
      sinProblemas( p );
      igual( CorrectorPack.FORMA_ZIP, p.forma(), "forma" );
      igual( t.getCanonicalFile(), p.raiz().getCanonicalFile(), "raiz" );
      igual( listar( dir ), listar( t ), "mismos archivos que la carpeta" );
      igual( leer( new File( dir, "solucion/03_ventas_por_producto.ktr" ), StandardCharsets.UTF_8 ),
        leer( p.solucion(), StandardCharsets.UTF_8 ), "solucion identica" );
    }

    // como lo comprime el Explorador: todo dentro de una carpeta; mas la basura de macOS
    List<String> entradas = new ArrayList<>();
    for ( String rel : listar( dir ) ) {
      entradas.add( CorrectorRecursos.VENTAS + "/" + rel );
      entradas.add( leer( new File( dir, rel ), StandardCharsets.ISO_8859_1 ) );
    }
    entradas.addAll( Arrays.asList( "__MACOSX/" + CorrectorRecursos.VENTAS + "/._pack.json", "basura",
      CorrectorRecursos.VENTAS + "/.DS_Store", "basura" ) );
    File envuelto = zip( new File( r, "envuelto.zip" ), StandardCharsets.ISO_8859_1, entradas.toArray(
      new String[ 0 ] ) );
    File t2 = trabajo();
    try ( CorrectorPack p = CorrectorPack.abrir( envuelto, t2 ) ) {
      sinProblemas( p );
      igual( new File( t2, CorrectorRecursos.VENTAS ).getCanonicalFile(), p.raiz().getCanonicalFile(),
        "raiz en la carpeta del zip" );
      falso( new File( t2, "__MACOSX" ).exists(), "__MACOSX no se descomprime" );
      falso( new File( p.raiz(), ".DS_Store" ).exists(), ".DS_Store no se descomprime" );
    }

    // sin extension .zip: la firma PK alcanza; sin carpeta de trabajo, close() borra la temporal
    File bin = new File( r, "pack.bin" );
    Files.copy( zip.toPath(), bin.toPath() );
    File raiz;
    try ( CorrectorPack p = CorrectorPack.abrir( bin ) ) {
      sinProblemas( p );
      igual( CorrectorPack.FORMA_ZIP, p.forma(), "zip por la firma" );
      raiz = p.raiz();
    }
    falso( raiz.exists(), "close() borra lo descomprimido" );

    // inline dentro de un pack de carpeta o zip: se ignora con aviso
    File conInline = new File( r, "con-inline" );
    copiarCarpeta( dir, conInline );
    ObjectNode pj = (ObjectNode) CorrectorJson.mapper().readTree( new File( conInline, "pack.json" ) );
    pj.with( "inline" ).put( "datos/visible/ventas.csv", "x;y\n" );
    CorrectorJson.guardar( pj, new File( conInline, "pack.json" ) );
    try ( CorrectorPack p = CorrectorPack.abrir( conInline ) ) {
      igual( 0, p.errores().size(), p.textoProblemas( "en" ) );
      Problema a = unAviso( p, CorrectorPack.CAMPO_IGNORADO, "inline" );
      contiene( a.mensaje, "only read in a single .json pack", "aviso legible" );
      noContiene( leer( new File( conInline, "datos/visible/ventas.csv" ), StandardCharsets.UTF_8 ), "x;y",
        "no pisa los datos de la carpeta" );
    }
  }

  // ------------------------------------------------------------------ zip inseguro

  public void pruebaZipSlip() throws Exception {
    File base = trabajo();
    String packJson = leer( CorrectorRecursos.pack( CorrectorRecursos.INLINE ), StandardCharsets.UTF_8 );
    String[] malos = { "../fuera.txt", "../../fuera.txt", "datos/../../fuera.txt", "datos\\..\\..\\fuera.txt",
      "/fuera.txt", "\\fuera.txt", "C:/fuera.txt", "C:fuera.txt", "C:\\fuera.txt", "file:///C:/fuera.txt",
      "//servidor/c/fuera.txt", "datos/./../../fuera.txt" };
    for ( int i = 0; i < malos.length; i++ ) {
      String malo = malos[ i ];
      File zip = zip( new File( base, "slip" + i + ".zip" ), StandardCharsets.UTF_8, "pack.json", packJson,
        "datos/visible/ventas.csv", "a;b\n", malo, "maligno" );
      File dest = new File( base, "dest" + i );
      ZipRechazado z = lanza( ZipRechazado.class, () -> CorrectorPack.descomprimir( zip, dest, 1 << 20, 100 ),
        "zip slip: " + malo );
      igual( CorrectorPack.ZIP_SLIP, z.codigo, malo );
      igual( malo, z.entrada, "la entrada culpable" );
      falso( dest.exists(), "no se escribio nada: " + malo );
    }
    for ( File f : new File[] { new File( base, "fuera.txt" ), new File( base.getParentFile(), "fuera.txt" ),
      new File( base.getParentFile().getParentFile(), "fuera.txt" ), new File( "C:/fuera.txt" ) } ) {
      falso( f.exists(), "nada fuera del destino: " + f );
    }

    // por abrir(): error legible, la carpeta de trabajo queda vacia
    File zip = zip( new File( base, "slip.zip" ), StandardCharsets.UTF_8, "pack.json", packJson,
      "../../fuera.txt", "maligno" );
    File t = trabajo();
    try ( CorrectorPack p = CorrectorPack.abrir( zip, t ) ) {
      Problema e = unError( p, CorrectorPack.ZIP_SLIP, "slip.zip" );
      nulo( p.pack(), "sin pack" );
      falso( p.valido(), "invalido" );
      igual( "The zip entry \"../../fuera.txt\" would be written outside the destination folder (zip slip): the zip "
        + "is rejected.", e.mensaje, "mensaje" );
      contiene( e.texto( "es" ), "se escribiría fuera de la carpeta destino", "es" );
      contiene( e.texto( "pt" ), "seria escrita fora da pasta de destino", "pt" );
      igual( 0, t.list().length, "la carpeta de trabajo queda vacia" );
    }
  }

  public void pruebaZipNombresYRepetidos() throws Exception {
    File base = trabajo();
    Object[][] casos = {
      { CorrectorPack.ZIP_NOMBRE, new String[] { "pack.json", "{}", "datos/con.csv", "x" } },
      { CorrectorPack.ZIP_NOMBRE, new String[] { "pack.json", "{}", "datos/AUX", "x" } },
      { CorrectorPack.ZIP_NOMBRE, new String[] { "pack.json", "{}", "datos/a:b.csv", "x" } },
      { CorrectorPack.ZIP_NOMBRE, new String[] { "pack.json", "{}", "ventas.csv:flujo", "x" } },
      { CorrectorPack.ZIP_NOMBRE, new String[] { "pack.json", "{}", "datos/x?.csv", "x" } },
      { CorrectorPack.ZIP_NOMBRE, new String[] { "pack.json", "{}", "datos./x.csv", "x" } },
      { CorrectorPack.ZIP_REPETIDO, new String[] { "datos/A.csv", "1", "Datos/a.csv", "2" } },
      { CorrectorPack.ZIP_REPETIDO, new String[] { "datos", "1", "datos/x.csv", "2" } },
      { CorrectorPack.ZIP_REPETIDO, new String[] { "datos/x.csv", "2", "datos", "1" } },
    };
    for ( int i = 0; i < casos.length; i++ ) {
      String[] ent = (String[]) casos[ i ][ 1 ];
      File zip = zip( new File( base, "n" + i + ".zip" ), StandardCharsets.UTF_8, ent );
      File dest = new File( base, "d" + i );
      ZipRechazado z = lanza( ZipRechazado.class, () -> CorrectorPack.descomprimir( zip, dest, 1 << 20, 100 ),
        Arrays.toString( ent ) );
      igual( casos[ i ][ 0 ], z.codigo, Arrays.toString( ent ) );
      falso( dest.exists(), "nada escrito: " + Arrays.toString( ent ) );
    }

    // un archivo que ya esta en el destino no se pisa, y lo nuevo se borra
    File dest = new File( base, "existente" );
    EntornoKettle.escribir( new File( dest, "pack.json" ), "original" );
    File zip = zip( new File( base, "pisa.zip" ), StandardCharsets.UTF_8, "a.txt", "nuevo", "pack.json", "pisado" );
    ZipRechazado z = lanza( ZipRechazado.class, () -> CorrectorPack.descomprimir( zip, dest, 1 << 20, 100 ),
      "pisar" );
    igual( CorrectorPack.ZIP_REPETIDO, z.codigo, "pisar un archivo" );
    igual( "original", leer( new File( dest, "pack.json" ), StandardCharsets.UTF_8 ), "el archivo previo queda" );
    igual( Collections.singletonList( "pack.json" ), Arrays.asList( dest.list() ), "lo nuevo se borro" );

    // ./ y // se normalizan; las carpetas repetidas no molestan
    File ok = zip( new File( base, "ok.zip" ), StandardCharsets.UTF_8, "./pack.json", "{}", "datos//x.csv", "1",
      "datos/", "", "datos/y.csv", "2" );
    File d = new File( base, "ok" );
    CorrectorPack.descomprimir( ok, d, 1 << 20, 100 );
    igual( Arrays.asList( "datos/x.csv", "datos/y.csv", "pack.json" ), listar( d ), "normalizado" );

    // nombres en CP437 sin la marca UTF-8 (Windows viejo)
    File cp = zip( new File( base, "cp437.zip" ), Charset.forName( "IBM437" ), "pack.json", "{}",
      "datos/año.csv", "1" );
    File dcp = new File( base, "cp437" );
    CorrectorPack.descomprimir( cp, dcp, 1 << 20, 100 );
    archivo( new File( dcp, "datos/año.csv" ), 1, "nombre CP437 leido bien" );
  }

  public void pruebaZipBomba() throws Exception {
    File base = trabajo();
    byte[] ceros = new byte[ 3 * 1024 * 1024 ];
    File zip = new File( base, "bomba.zip" );
    zip.getParentFile().mkdirs();
    try ( ZipOutputStream z = new ZipOutputStream( new FileOutputStream( zip ), StandardCharsets.UTF_8 ) ) {
      z.putNextEntry( new ZipEntry( "pack.json" ) );
      z.write( "{}".getBytes( StandardCharsets.UTF_8 ) );
      z.closeEntry();
      z.putNextEntry( new ZipEntry( "datos/ceros.bin" ) );
      z.write( ceros );
      z.closeEntry();
    }
    cierto( zip.length() < 50 * 1024, "comprime mucho: " + zip.length() );

    File dest = new File( base, "d1" );
    ZipRechazado z = lanza( ZipRechazado.class, () -> CorrectorPack.descomprimir( zip, dest, 1024 * 1024, 100 ),
      "bomba" );
    igual( CorrectorPack.ZIP_GRANDE, z.codigo, "tope por lo declarado" );
    falso( dest.exists(), "nada escrito" );
    File d2 = new File( base, "d2" );
    CorrectorPack.descomprimir( zip, d2, 10L * 1024 * 1024, 100 );
    archivo( new File( d2, "datos/ceros.bin" ), ceros.length, "con un tope mayor descomprime" );

    // un zip que miente el tamaño en el directorio central: cuenta lo que realmente sale
    File mentiroso = new File( base, "mentiroso.zip" );
    Files.write( mentiroso.toPath(), mentirTamano( Files.readAllBytes( zip.toPath() ), "datos/ceros.bin", 100 ) );
    File d3 = new File( base, "d3" );
    ZipRechazado m = lanza( ZipRechazado.class, () -> CorrectorPack.descomprimir( mentiroso, d3, 1024 * 1024,
      100 ), "mentiroso" );
    igual( CorrectorPack.ZIP_GRANDE, m.codigo, "tope por los bytes reales: " + m.getMessage() );
    igual( "datos/ceros.bin", m.entrada, "la entrada que paso el tope" );
    falso( d3.exists(), "lo escrito se borra" );

    // demasiadas entradas
    List<String> muchas = new ArrayList<>();
    for ( int i = 0; i < 30; i++ ) {
      muchas.add( "f" + i + ".txt" );
      muchas.add( "x" );
    }
    File zm = zip( new File( base, "muchas.zip" ), StandardCharsets.UTF_8, muchas.toArray( new String[ 0 ] ) );
    ZipRechazado mm = lanza( ZipRechazado.class, () -> CorrectorPack.descomprimir( zm, new File( base, "d4" ),
      1 << 20, 20 ), "muchas" );
    igual( CorrectorPack.ZIP_MUCHAS, mm.codigo, "tope de entradas" );
    contiene( mm.texto( "es" ), "El zip tiene 30 entradas; el tope es 20.", "texto" );

    // por abrir(), con topes chicos
    try ( CorrectorPack p = CorrectorPack.abrir( zip, trabajo(), 1024 * 1024, 100 ) ) {
      Problema e = unError( p, CorrectorPack.ZIP_GRANDE, "bomba.zip" );
      contiene( e.mensaje, "more than 1 MB", "mensaje" );
    }
  }

  public void pruebaOrigenesMalos() throws Exception {
    File base = trabajo();
    base.mkdirs();
    File roto = new File( base, "roto.zip" );
    byte[] b = new byte[ 4096 ];
    new Random( 7 ).nextBytes( b );
    Files.write( roto.toPath(), b );
    try ( CorrectorPack p = CorrectorPack.abrir( roto, trabajo() ) ) {
      unError( p, CorrectorPack.ZIP_ROTO, "roto.zip" );
    }
    File dir = new File( CorrectorRecursos.copia(), "packs/" + CorrectorRecursos.VENTAS );
    File zip = new File( base, "bueno.zip" );
    CorrectorPack.comprimir( dir, zip );
    byte[] todo = Files.readAllBytes( zip.toPath() );
    File cortado = new File( base, "cortado.zip" );
    Files.write( cortado.toPath(), Arrays.copyOf( todo, todo.length / 2 ) );
    try ( CorrectorPack p = CorrectorPack.abrir( cortado, trabajo() ) ) {
      unError( p, CorrectorPack.ZIP_ROTO, "cortado.zip" );
    }
    File sinPack = zip( new File( base, "sin-pack.zip" ), StandardCharsets.UTF_8, "leeme.txt", "hola" );
    try ( CorrectorPack p = CorrectorPack.abrir( sinPack, trabajo() ) ) {
      unError( p, CorrectorPack.SIN_PACK_JSON, "sin-pack.zip" );
    }
    File ktr = new File( base, "entrega.ktr" );
    EntornoKettle.escribir( ktr, "<transformation/>" );
    try ( CorrectorPack p = CorrectorPack.abrir( ktr ) ) {
      Problema e = unError( p, CorrectorPack.NO_ES_PACK, "entrega.ktr" );
      contiene( e.mensaje, "use a folder with pack.json, a .zip or a single .json", "mensaje" );
    }
    try ( CorrectorPack p = CorrectorPack.abrir( new File( base, "no-existe" ) ) ) {
      unError( p, CorrectorPack.NO_EXISTE, "no-existe" );
      nulo( p.forma(), "sin forma" );
    }
    File vacia = new File( base, "vacia" );
    vacia.mkdirs();
    try ( CorrectorPack p = CorrectorPack.abrir( vacia ) ) {
      unError( p, CorrectorPack.SIN_PACK_JSON, "vacia" );
    }
  }

  // ------------------------------------------------------------------ packs invalidos y esquema

  public void pruebaPacksInvalidos() throws Exception {
    Object[][] casos = {
      { "ruta_con_puntos", CorrectorPack.RUTA_FUERA, "statement", "../../secreto.md",
        "sale de la carpeta del pack" },
      { "ruta_absoluta", CorrectorPack.RUTA_ABSOLUTA, "cases[0].expected", "C:/Users/profe/esperado.csv",
        "es una ruta absoluta" },
      { "formato_desconocido", CorrectorPack.FORMATO_DESCONOCIDO, "format", "pentaho-live-exercise-pack/9",
        "Formato desconocido" },
      { "inline_fuera", CorrectorPack.RUTA_FUERA, "inline[\"../fuera.csv\"]", "../fuera.csv",
        "sale de la carpeta del pack" },
      { "sin_casos", CorrectorPack.SIN_CASOS, "cases", null, "no tiene casos" },
      { "caso_repetido", CorrectorPack.CASO_REPETIDO, "cases[1].id", "visible", "está repetido" },
    };
    File r = CorrectorRecursos.copia();
    for ( Object[] c : casos ) {
      String id = (String) c[ 0 ];
      File t = trabajo();
      try ( CorrectorPack p = CorrectorPack.abrir( new File( r, "packs/invalidos/" + id + ".json" ), t ) ) {
        Problema e = unError( p, (String) c[ 1 ], (String) c[ 2 ] );
        igual( 0, p.avisos().size(), id + ": sin avisos\n" + p.textoProblemas( "en" ) );
        if ( c[ 3 ] != null ) {
          contiene( e.mensaje, "\"" + c[ 3 ] + "\"", id + ": el mensaje nombra el valor" );
        }
        contiene( e.linea( "es" ), (String) c[ 4 ], id + ": texto en castellano" );
        igual( c[ 2 ] + ": " + e.texto( "en" ), e.linea( "en" ), id + ": linea = donde + texto" );
        falso( p.valido(), id + ": invalido" );
      }
      if ( "inline_fuera".equals( id ) ) {
        falso( t.exists(), "inline invalido: no se escribe nada" );
        falso( new File( t.getParentFile(), "fuera.csv" ).exists(), "ni fuera" );
      }
    }
    try ( CorrectorPack p = CorrectorPack.abrir( new File( r, "packs/invalidos/rubrica_mala.json" ), trabajo() ) ) {
      igual( Arrays.asList( CorrectorPack.REGLA_DESCONOCIDA + " rubric[10].check", CorrectorPack.CHECK_DESCONOCIDO
        + " rubric[11].check" ), codigos( p.errores() ), p.textoProblemas( "en" ) );
      contiene( p.errores().get( 0 ).mensaje, "rule:no_existe names a rule that is not in rules", "regla" );
      contiene( p.errores().get( 1 ).texto( "es" ), "Check desconocido «magia»", "check" );
    }
  }

  public void pruebaCargarYValidar() throws Exception {
    File r = CorrectorRecursos.copia();
    File malo = new File( r, "packs/invalidos/ruta_con_puntos.json" );
    PackInvalido e = lanza( PackInvalido.class, () -> CorrectorPack.cargar( malo, trabajo() ), "cargar" );
    contiene( e.getMessage(), "The exercise pack " + malo.getPath() + " is not valid:", "encabezado" );
    contiene( e.getMessage(), "\n- statement: \"../../secreto.md\" leaves the pack folder: \"..\" is not allowed.",
      "el error, legible" );
    contiene( e.texto( "es" ), "no es válido:\n- statement: «../../secreto.md» sale de la carpeta del pack", "es" );
    contiene( e.texto( "pt" ), "não é válido", "pt" );
    igual( 1, e.problemas().size(), "un problema" );

    try ( CorrectorPack p = CorrectorPack.cargar( new File( r, "packs/" + CorrectorRecursos.VENTAS ) ) ) {
      cierto( p.valido(), "cargar un pack valido" );
      cierto( p == p.requerirValido(), "requerirValido devuelve this" );
    }
    igual( Collections.emptyList(), CorrectorPack.validar( new File( r, "packs/" + CorrectorRecursos.INLINE
      + ".json" ) ), "validar un pack bueno" );
    List<Problema> ps = CorrectorPack.validar( malo );
    igual( 1, ps.size(), "validar uno malo" );

    // el JSON de un problema (validate_exercise_pack): claves en ingles, sin los internos
    String j = CorrectorJson.escribir( ps.get( 0 ), false );
    igual( "{\"severity\":\"error\",\"code\":\"path_outside_pack\",\"where\":\"statement\",\"message\":"
      + "\"\\\"../../secreto.md\\\" leaves the pack folder: \\\"..\\\" is not allowed.\"}", j, "JSON" );
  }

  public void pruebaJsonRoto() throws Exception {
    File base = trabajo();
    File roto = escribir( new File( base, "roto.json" ),
      "{ \"format\": \"pentaho-live-exercise-pack/1\", \"id\": \"x\",\n  \"cases\": [ }" );
    try ( CorrectorPack p = CorrectorPack.abrir( roto, trabajo() ) ) {
      Problema e = unError( p, CorrectorPack.JSON_MALO, "roto.json" );
      contiene( e.mensaje, "Not valid JSON (line 2, column ", "linea" );
      contiene( e.texto( "es" ), "No es un JSON válido (línea 2", "es" );
    }
    try ( CorrectorPack p = CorrectorPack.abrir( escribir( new File( base, "vacio.json" ), "" ), trabajo() ) ) {
      igual( "The file is empty.", unError( p, CorrectorPack.JSON_MALO, "vacio.json" ).mensaje, "vacio" );
    }
    try ( CorrectorPack p = CorrectorPack.abrir( escribir( new File( base, "lista.json" ), "[1, 2]" ), trabajo() ) ) {
      igual( "The JSON must be an object {...}.", unError( p, CorrectorPack.JSON_MALO, "lista.json" ).mensaje,
        "raiz" );
    }
    File tipo = json( "tipo", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "weight", "uno" ) );
    try ( CorrectorPack p = CorrectorPack.abrir( tipo, trabajo() ) ) {
      Problema e = unError( p, CorrectorPack.TIPO_MALO, "cases[0].weight" );
      contiene( e.mensaje, "\"uno\" is not a number (line ", "en" );
      contiene( e.texto( "es" ), "«uno» no es un número (línea ", "es" );
    }
    File forma = json( "forma", n -> n.set( "cases", CorrectorJson.mapper().createObjectNode() ) );
    try ( CorrectorPack p = CorrectorPack.abrir( forma, trabajo() ) ) {
      contiene( unError( p, CorrectorPack.TIPO_MALO, "cases" ).mensaje, "Expected a list [...] (line ", "lista" );
    }
    File sinFormato = json( "sin-formato", n -> n.remove( "format" ) );
    try ( CorrectorPack p = CorrectorPack.abrir( sinFormato, trabajo() ) ) {
      unError( p, CorrectorPack.FALTA_VALOR, "format" );
    }
    // BOM del Bloc de notas y comentarios del docente: valen
    String texto = leer( CorrectorRecursos.pack( CorrectorRecursos.INLINE ), StandardCharsets.UTF_8 );
    File bom = escribir( new File( base, "bom.json" ), "\uFEFF// pack del ejercicio 3\n" + texto.replaceFirst( "\\{",
      "{ /* comentario */" ) );
    try ( CorrectorPack p = CorrectorPack.abrir( bom, trabajo() ) ) {
      sinProblemas( p );
    }
  }

  public void pruebaClavesDesconocidas() throws Exception {
    File f = json( "claves", n -> {
      ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "hiden", true );
      n.putArray( "rubrik" );
      n.with( "output" ).putArray( "colums" );
      n.with( "policy" ).put( "timeout", 30 );
      n.put( "zzz", 1 );
      n.with( "parameters" ).put( "OTRO", "x" ); // mapas libres: no se revisan
    } );
    try ( CorrectorPack p = CorrectorPack.abrir( f, trabajo() ) ) {
      igual( 0, p.errores().size(), p.textoProblemas( "en" ) );
      igual( new TreeSet<>( Arrays.asList( "unknown_key cases[0].hiden", "unknown_key output.colums",
        "unknown_key policy.timeout", "unknown_key rubrik", "unknown_key zzz" ) ), new TreeSet<>( codigos(
        p.avisos() ) ), p.textoProblemas( "en" ) );
      igual( "Unknown key \"hiden\" (did you mean \"hidden\"?): it is ignored.", aviso( p, "cases[0].hiden" ).mensaje,
        "sugerencia" );
      contiene( aviso( p, "rubrik" ).mensaje, "did you mean \"rubric\"", "rubrik" );
      contiene( aviso( p, "output.colums" ).mensaje, "did you mean \"columns\"", "colums" );
      contiene( aviso( p, "policy.timeout" ).mensaje, "did you mean \"timeout_s\"", "timeout" );
      igual( "Unknown key \"zzz\": it is ignored.", aviso( p, "zzz" ).mensaje, "sin sugerencia" );
      contiene( aviso( p, "cases[0].hiden" ).texto( "es" ), "¿quisiste decir «hidden»?", "es" );
    }
  }

  public void pruebaEsquema() throws Exception {
    // un error por cosa, con donde y codigo
    error( "captura", n -> n.with( "output" ).put( "capture", "csv" ), CorrectorPack.VALOR_MALO, "output.capture",
      "\"csv\" is not valid; the possible values are: file, step, query, tables." );
    error( "kind", n -> n.put( "kind", "jobs" ), CorrectorPack.VALOR_MALO, "kind", "transformation, job" );
    error( "sin-id", n -> n.remove( "id" ), CorrectorPack.FALTA_VALOR, "id", "A value is required here." );
    error( "clave", n -> n.with( "compare" ).putArray( "key_columns" ).add( "product" ),
      CorrectorPack.CLAVE_SIN_COLUMNA, "compare.key_columns[0]", "\"product\" is not in output.columns (producto, "
        + "unidades, importe_total, num_ventas, importe_medio, ultima_venta)" );
    error( "fecha", n -> n.with( "compare" ).withArray( "date_formats" ).add( "yyyy-qq" ),
      CorrectorPack.FORMATO_FECHA, "compare.date_formats[2]", "\"yyyy-qq\" is not a valid date mask" );
    error( "tolerancia", n -> n.with( "compare" ).put( "numeric_tolerance", -1 ), CorrectorPack.VALOR_MALO,
      "compare.numeric_tolerance", "-1 is not valid: the minimum is 0." );
    error( "extras", n -> n.with( "compare" ).put( "extra_columns", "ignore" ), CorrectorPack.VALOR_MALO,
      "compare.extra_columns", "warn, penalize" );
    error( "ejemplos", n -> n.with( "compare" ).put( "max_examples", -2 ), CorrectorPack.VALOR_MALO,
      "compare.max_examples", "-2" );
    error( "locale", n -> n.with( "policy" ).put( "locale", "español" ), CorrectorPack.LOCALE_MALO, "policy.locale",
      "like es_ES or en_US" );
    error( "pais", n -> n.with( "policy" ).put( "locale", "es_XX" ), CorrectorPack.LOCALE_MALO, "policy.locale",
      "es_XX" );
    error( "zona", n -> n.with( "policy" ).put( "timezone", "Madrid" ), CorrectorPack.ZONA_MALA, "policy.timezone",
      "\"Madrid\" is not a time zone" );
    error( "charset", n -> n.with( "policy" ).put( "encoding", "UTF-9" ), CorrectorPack.CHARSET_MALO,
      "policy.encoding", "UTF-9" );
    error( "timeout", n -> n.with( "policy" ).put( "timeout_s", 0 ), CorrectorPack.VALOR_MALO, "policy.timeout_s",
      "the minimum is 1" );
    error( "filas", n -> n.with( "policy" ).put( "max_rows", 0 ), CorrectorPack.VALOR_MALO, "policy.max_rows", "0" );
    error( "salida-mb", n -> n.with( "policy" ).put( "max_output_mb", -5 ), CorrectorPack.VALOR_MALO,
      "policy.max_output_mb", "-5" );
    error( "desconocidos", n -> n.with( "policy" ).put( "unknown_steps", "ask" ), CorrectorPack.VALOR_MALO,
      "policy.unknown_steps", "block, allow" );
    error( "permitir", n -> n.with( "policy" ).withArray( "allow_steps" ).add( " " ), CorrectorPack.FALTA_VALOR,
      "policy.allow_steps[1]", "required" );
    error( "escala", n -> n.put( "scale", 0 ), CorrectorPack.VALOR_MALO, "scale", "greater than 0" );
    error( "csv-charset", n -> n.with( "output" ).with( "expected_csv" ).put( "encoding", "LATIN-99" ),
      CorrectorPack.CHARSET_MALO, "output.expected_csv.encoding", "LATIN-99" );
    error( "separador", n -> n.with( "output" ).with( "expected_csv" ).put( "separator", "" ),
      CorrectorPack.FALTA_VALOR, "output.expected_csv.separator", "required" );

    // casos
    error( "peso", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "weight", -1 ),
      CorrectorPack.VALOR_MALO, "cases[0].weight", "-1 is not valid" );
    error( "pesos-cero", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "weight", 0 ),
      CorrectorPack.VALOR_MALO, "cases", "All cases weigh 0" );
    error( "id-caso", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "id", "caso visible" ),
      CorrectorPack.ID_CASO_MALO, "cases[0].id", "\"caso visible\" is not valid" );
    error( "id-con", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "id", "CON" ),
      CorrectorPack.ID_CASO_MALO, "cases[0].id", "CON" );
    error( "esperado-carpeta", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "expected",
      "datos/visible" ), CorrectorPack.FALTA_ARCHIVO, "cases[0].expected", "\"datos/visible\" is a folder, not a file." );
    error( "entradas-archivo", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "inputs_dir",
      "esperado/visible.csv" ), CorrectorPack.FALTA_CARPETA, "cases[0].inputs_dir", "is a file, not a folder" );
    error( "entradas-falta", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "inputs_dir",
      "datos/otro" ), CorrectorPack.FALTA_CARPETA, "cases[0].inputs_dir", "The folder \"datos/otro\" is not in the pack." );
    error( "sin-dir", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).remove( "inputs_dir" ),
      CorrectorPack.FALTA_VALOR, "cases[0].inputs_dir", "required" );
    error( "sin-esperado", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).remove( "expected" ),
      CorrectorPack.FALTA_VALOR, "cases[0].expected", "required" );
    error( "entrada-carpeta", n -> n.putArray( "inputs" ).add( "datos/ventas.csv" ), CorrectorPack.VALOR_MALO,
      "inputs[0]", "\"datos/ventas.csv\" must be a file name, without folders.",
      "unlisted_input cases[0].inputs_dir" );
    error( "entrada-repetida", n -> n.withArray( "inputs" ).add( "VENTAS.csv" ), CorrectorPack.ENTRADA_REPETIDA,
      "inputs[1]", "VENTAS.csv" );
    error( "entrada-falta", n -> n.withArray( "inputs" ).add( "clientes.csv" ), CorrectorPack.FALTA_ENTRADA,
      "cases[0].inputs_dir", "The input \"clientes.csv\" is not in datos/visible." );

    // reglas
    error( "regla-repetida", n -> n.withArray( "rules" ).addObject().put( "id", "usa_group_by" ).put( "type",
      "must_use" ).putArray( "steps" ).add( "GroupBy" ), CorrectorPack.REGLA_REPETIDA, "rules[5].id",
      "The rule id \"usa_group_by\" is repeated." );
    error( "sin-steps", n -> ( (ObjectNode) n.withArray( "rules" ).get( 0 ) ).putArray( "steps" ),
      CorrectorPack.FALTA_VALOR, "rules[0].steps", "required" );
    error( "max-cero", n -> ( (ObjectNode) n.withArray( "rules" ).get( 2 ) ).put( "value", 0 ),
      CorrectorPack.VALOR_MALO, "rules[2].value", "the minimum is 1" );
    error( "sin-max", n -> ( (ObjectNode) n.withArray( "rules" ).get( 2 ) ).remove( "value" ),
      CorrectorPack.FALTA_VALOR, "rules[2].value", "required" );
    error( "tipo-regla", n -> ( (ObjectNode) n.withArray( "rules" ).get( 0 ) ).put( "type", "must_have" ),
      CorrectorPack.VALOR_MALO, "rules[0].type", "must_use, must_not_use, max_steps, error_handling, lint" );
    error( "lint-regla", n -> ( (ObjectNode) n.withArray( "rules" ).get( 4 ) ).put( "rule", "unsorted_inputs" ),
      CorrectorPack.LINT_DESCONOCIDA, "rules[4].rule", "\"unsorted_inputs\" is not a linter rule. Valid ids: " );
    Problema lint = error( "lint-check", n -> ( (ObjectNode) n.withArray( "rubric" ).get( 9 ) ).put( "check",
      "lint:no_such_rule" ), CorrectorPack.LINT_DESCONOCIDA, "rubric[9].check", "no_such_rule" );
    contiene( lint.mensaje, "unsorted_input", "lista los ids validos del linter" );

    // rubrica
    error( "check-repetido", n -> ( (ObjectNode) n.withArray( "rubric" ).get( 1 ) ).put( "check", "runs" ),
      CorrectorPack.CHECK_REPETIDO, "rubric[1].check", "\"runs\" is repeated" );
    error( "puntos", n -> ( (ObjectNode) n.withArray( "rubric" ).get( 0 ) ).put( "points", -1 ),
      CorrectorPack.VALOR_MALO, "rubric[0].points", "-1" );
    error( "requiere-solo", n -> ( (ObjectNode) n.withArray( "rubric" ).get( 4 ) ).putArray( "requires" ).add(
      "rule:usa_group_by" ), CorrectorPack.REQUIERE_MALO, "rubric[4].requires[0]",
      "\"rule:usa_group_by\" cannot require itself." );
    error( "ciclo", n -> {
      ( (ObjectNode) n.withArray( "rubric" ).get( 4 ) ).withArray( "requires" ).add( "rule:orden" );
      ( (ObjectNode) n.withArray( "rubric" ).get( 8 ) ).withArray( "requires" ).add( "rule:usa_group_by" );
    }, CorrectorPack.REQUIERE_MALO, "rubric", "The requires form a loop: rule:usa_group_by -> rule:orden -> "
      + "rule:usa_group_by." );
    error( "requiere-desconocido", n -> ( (ObjectNode) n.withArray( "rubric" ).get( 4 ) ).putArray( "requires" )
      .add( "corre" ), CorrectorPack.CHECK_DESCONOCIDO, "rubric[4].requires[0]", "Unknown check \"corre\"" );
    error( "rubrica-vacia", n -> n.putArray( "rubric" ), CorrectorPack.SIN_RUBRICA, "rubric", "nothing to grade",
      "unused_rule rules[0]", "unused_rule rules[1]", "unused_rule rules[2]", "unused_rule rules[3]",
      "unused_rule rules[4]" );

    // columnas de la salida
    error( "columna-repetida", n -> ( (ObjectNode) n.with( "output" ).withArray( "columns" ).get( 1 ) ).put( "name",
      "PRODUCTO" ), CorrectorPack.COLUMNA_REPETIDA, "output.columns[1].name", "PRODUCTO",
      "expected_header cases[0].expected" );
    error( "mascara-fecha", n -> ( (ObjectNode) n.with( "output" ).withArray( "columns" ).get( 5 ) ).put( "format",
      "yyyy-qq" ), CorrectorPack.FORMATO_FECHA, "output.columns[5].format", "yyyy-qq" );
    error( "mascara-numero", n -> ( (ObjectNode) n.with( "output" ).withArray( "columns" ).get( 2 ) ).put( "format",
      "0.00.0" ), CorrectorPack.VALOR_MALO, "output.columns[2].format", "\"0.00.0\" is not a valid number mask" );

    // rutas
    error( "solucion-falta", n -> n.put( "solution", "solucion/x.ktr" ), CorrectorPack.FALTA_ARCHIVO, "solution",
      "The file \"solucion/x.ktr\" is not in the pack." );
    error( "entrega-fuera", n -> n.put( "submission_file", "../x.ktr" ), CorrectorPack.RUTA_FUERA,
      "submission_file", "../x.ktr" );
    error( "inline-reservado", n -> n.with( "inline" ).put( "./pack.json", "{}" ), CorrectorPack.INLINE_RESERVADO,
      "inline[\"./pack.json\"]", "cannot be an inline file" );
    error( "inline-repetido", n -> n.with( "inline" ).put( "Datos\\visible\\ventas.csv", "x" ),
      CorrectorPack.INLINE_REPETIDO, "inline[\"Datos\\visible\\ventas.csv\"]", "is repeated" );
    char[] mucho = new char[ (int) Pack.TOPE_INLINE + 10 ];
    Arrays.fill( mucho, 'x' );
    error( "inline-grande", n -> n.with( "inline" ).put( "datos/visible/grande.csv", new String( mucho ) ),
      CorrectorPack.INLINE_GRANDE, "inline", "the limit is 2 M" );
    error( "inline-absoluto", n -> n.with( "inline" ).put( "C:\\Windows\\x.csv", "x" ), CorrectorPack.RUTA_ABSOLUTA,
      "inline[\"C:\\Windows\\x.csv\"]", "is an absolute path" );

    // avisos sin errores
    aviso( "oculto", n -> ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "hidden", true ),
      "no_visible_case cases" );
    aviso( "no-listada", n -> n.with( "inline" ).put( "datos/visible/clientes.csv", "id\n1\n" ),
      "unlisted_input cases[0].inputs_dir" );
    aviso( "puntos-cero", n -> ( (ObjectNode) n.withArray( "rubric" ).get( 5 ) ).put( "points", 0 ),
      "zero_points rubric[5].points" );
    aviso( "regla-sin-uso", n -> n.withArray( "rubric" ).remove( 7 ), "unused_rule rules[3]" );
    aviso( "tipo-columna", n -> ( (ObjectNode) n.with( "output" ).withArray( "columns" ).get( 0 ) ).put( "type",
      "Texto" ), "unknown_column_type output.columns[0].type" );
    aviso( "sin-tipo", n -> ( (ObjectNode) n.with( "output" ).withArray( "columns" ).get( 0 ) ).remove( "type" ),
      "unknown_column_type output.columns[0].type" );
    aviso( "sin-columnas", n -> {
      n.with( "output" ).putArray( "columns" );
    }, "no_columns output.columns" );
    aviso( "encabezado", n -> n.with( "inline" ).put( "esperado/visible.csv", leerInline( "esperado/visible.csv" )
      .replace( "importe_total", "importe" ) ), "expected_header cases[0].expected" );
    aviso( "base-ignorada", n -> n.with( "output" ).with( "database" ).put( "template", "x.sqlite" ),
      "ignored_field output.database" );
    aviso( "sin-enunciado", n -> n.remove( "statement" ), "no_statement statement" );
    aviso( "sin-titulo", n -> n.remove( "title" ), "no_title title" );
    aviso( "idioma", n -> n.put( "language", "fr" ), "unknown_language language" );
    aviso( "extension", n -> n.put( "solution", "enunciado.md" ), "solution_extension solution" );
    Problema h = aviso( "encabezado2", n -> n.with( "inline" ).put( "esperado/visible.csv", leerInline(
      "esperado/visible.csv" ).replace( "importe_total", "importe" ) ), "expected_header cases[0].expected" );
    igual( "The header of esperado/visible.csv does not match output.columns: missing importe_total; extra importe.",
      h.mensaje, "aviso del encabezado" );

    // valores validos que no son los de siempre
    sinProblemas( "validos", n -> {
      n.with( "policy" ).put( "locale", "es-AR" ).put( "timezone", "GMT-03:00" ).put( "encoding", "windows-1252" );
      ( (ObjectNode) n.withArray( "cases" ).get( 0 ) ).put( "id", "caso_1.v2" ).put( "inputs_dir", "./datos\\visible/" );
      n.with( "compare" ).put( "extra_columns", "penalize" );
    } );
    sinProblemas( "locales", n -> n.with( "policy" ).put( "locale", "es_419" ).put( "timezone", "EST" ) );
    sinProblemas( "solo-idioma", n -> n.with( "policy" ).put( "locale", "pt" ).put( "timezone", "America/Sao_Paulo" ) );
  }

  /** Captura query: lo que exige y lo que se ignora con las otras capturas. */
  public void pruebaCapturaQuery() throws Exception {
    File f = json( "query", n -> n.with( "output" ).put( "capture", "query" ) );
    try ( CorrectorPack p = CorrectorPack.abrir( f, trabajo() ) ) {
      igual( Arrays.asList( "missing_value output.database", "missing_value output.sql" ), codigos( p.errores() ),
        p.textoProblemas( "en" ) );
    }
    File g = json( "query-mysql", n -> {
      n.with( "output" ).put( "capture", "query" ).put( "sql", "SELECT 1" );
      n.with( "output" ).with( "database" ).put( "template", "plantilla/no.sqlite" ).put( "type", "MYSQL" );
    } );
    try ( CorrectorPack p = CorrectorPack.abrir( g, trabajo() ) ) {
      igual( Arrays.asList( "missing_file output.database.template", "bad_value output.database.type" ), codigos(
        p.errores() ), p.textoProblemas( "en" ) );
    }
    File h = json( "file-sql", n -> n.with( "output" ).put( "sql", "SELECT 1" ) );
    try ( CorrectorPack p = CorrectorPack.abrir( h, trabajo() ) ) {
      igual( 0, p.errores().size(), p.textoProblemas( "en" ) );
      contiene( unAviso( p, CorrectorPack.CAMPO_IGNORADO, "output.sql" ).mensaje, "this pack uses \"step\"",
        "aviso" );
    }
  }

  public void pruebaRutas() {
    String[] buenas = { "datos/visible", "./datos/visible/", "datos\\visible\\ventas.csv", "a", "esperado/v.csv",
      "datos/año 2024/ventas (1).csv", "${X}/a.csv" };
    for ( String b : buenas ) {
      nulo( CorrectorPack.motivoRuta( b ), "valida: " + b );
    }
    String[][] malas = {
      { "..", CorrectorPack.RUTA_FUERA }, { "../x", CorrectorPack.RUTA_FUERA }, { "a/../b", CorrectorPack.RUTA_FUERA },
      { "a\\..\\..\\b", CorrectorPack.RUTA_FUERA }, { "a/ ../b", CorrectorPack.RUTA_FUERA },
      { "/etc/passwd", CorrectorPack.RUTA_ABSOLUTA }, { "\\\\srv\\share\\x", CorrectorPack.RUTA_ABSOLUTA },
      { "C:\\x.csv", CorrectorPack.RUTA_ABSOLUTA }, { "c:x.csv", CorrectorPack.RUTA_ABSOLUTA },
      { "file:///C:/x", CorrectorPack.RUTA_ABSOLUTA }, { "http://h/x.csv", CorrectorPack.RUTA_ABSOLUTA },
      { "zip:/x.zip!/a", CorrectorPack.RUTA_ABSOLUTA },
      { "", CorrectorPack.RUTA_MALA }, { "  ", CorrectorPack.RUTA_MALA }, { null, CorrectorPack.RUTA_MALA },
      { "a:b", CorrectorPack.RUTA_ABSOLUTA }, { "datos/a:b", CorrectorPack.RUTA_MALA },
      { "ventas.csv:flujo", CorrectorPack.RUTA_MALA },
      { "x?.csv", CorrectorPack.RUTA_MALA }, { "a*b", CorrectorPack.RUTA_MALA }, { "a<b", CorrectorPack.RUTA_MALA },
      { "a|b", CorrectorPack.RUTA_MALA }, { "a\"b", CorrectorPack.RUTA_MALA }, { "a\tb", CorrectorPack.RUTA_MALA },
      { "CON", CorrectorPack.RUTA_MALA }, { "datos/nul.csv", CorrectorPack.RUTA_MALA },
      { "datos/com1", CorrectorPack.RUTA_MALA }, { "LPT9.txt", CorrectorPack.RUTA_MALA },
      { "datos./x", CorrectorPack.RUTA_MALA }, { "datos /x", CorrectorPack.RUTA_MALA },
      { "...", CorrectorPack.RUTA_MALA },
    };
    for ( String[] m : malas ) {
      igual( m[ 1 ], CorrectorPack.motivoRuta( m[ 0 ] ), "motivo de <" + m[ 0 ] + ">" );
    }
    nulo( CorrectorPack.motivoRuta( "console.csv" ), "console no es CON" );
    nulo( CorrectorPack.motivoRuta( "com10.csv" ), "com10 no es reservado" );
    igual( "datos/visible", CorrectorPack.normalizarRuta( "./datos\\\\visible/" ), "normalizar" );
    igual( "a/b", CorrectorPack.normalizarRuta( " a//./b/ " ), "normalizar 2" );
    igual( "", CorrectorPack.normalizarRuta( "./" ), "normalizar vacio" );
    cierto( CorrectorPack.localeValido( "es_ES" ) && CorrectorPack.localeValido( "pt-BR" ) && CorrectorPack
      .localeValido( "en" ) && CorrectorPack.localeValido( "es_419" ), "locales validos" );
    falso( CorrectorPack.localeValido( "xx_ES" ) || CorrectorPack.localeValido( "es_ESP" ) || CorrectorPack
      .localeValido( "Spanish" ), "locales invalidos" );
    cierto( CorrectorPack.zonaValida( "UTC" ) && CorrectorPack.zonaValida( "Europe/Madrid" ) && CorrectorPack
      .zonaValida( "EST" ) && CorrectorPack.zonaValida( "GMT+3" ), "zonas validas" );
    falso( CorrectorPack.zonaValida( "Europa/Madrid" ), "zona invalida" );
    igual( "hidden", CorrectorPack.parecida( "Hidden", Arrays.asList( "id", "hidden", "weight" ) ), "parecida" );
    igual( "key_columns", CorrectorPack.parecida( "keyColumns", Arrays.asList( "key_columns", "trim" ) ), "sin _" );
    nulo( CorrectorPack.parecida( "zzz", Arrays.asList( "id", "hidden" ) ), "nada parecido" );
  }

  /** Una junction de Windows que lleva la carpeta de entradas fuera del pack: rechazada aunque el nombre sea bueno. */
  public void pruebaEnlaceFuera() throws Exception {
    File r = CorrectorRecursos.copia();
    File dir = new File( r, "packs/" + CorrectorRecursos.VENTAS );
    File fuera = new File( r, "fuera-del-pack" );
    copiarCarpeta( new File( dir, "datos/visible" ), fuera );
    File visible = new File( dir, "datos/visible" );
    CorrectorPack.borrar( visible );
    if ( !junction( visible, fuera ) ) {
      saltear( "no se pudo crear una junction (mklink /J)" );
    }
    try ( CorrectorPack p = CorrectorPack.abrir( dir ) ) {
      Problema e = unError( p, CorrectorPack.RUTA_FUERA, "cases[0].inputs_dir" );
      igual( "\"datos/visible\" points outside the pack folder through a link or a junction.", e.mensaje, "mensaje" );
    } finally {
      CorrectorPack.borrar( visible ); // solo el enlace
    }
    archivo( new File( fuera, "ventas.csv" ), 50, "borrar() no siguio la junction" );
  }

  public void pruebaCopiaParaAlumnos() throws Exception {
    File r = CorrectorRecursos.copia();
    File dir = new File( r, "packs/" + CorrectorRecursos.VENTAS );
    File alumnos = new File( r, "alumnos" );
    copiarCarpeta( dir, alumnos );
    CorrectorPack.borrar( new File( alumnos, "solucion" ) );
    CorrectorPack.borrar( new File( alumnos, "datos/oculto" ) );
    CorrectorPack.borrar( new File( alumnos, "esperado/oculto.csv" ) );
    // con el pack.json del docente no valida
    try ( CorrectorPack p = CorrectorPack.abrir( alumnos ) ) {
      igual( Arrays.asList( "missing_file solution", "missing_folder cases[1].inputs_dir",
        "missing_file cases[1].expected" ), codigos( p.errores() ), p.textoProblemas( "en" ) );
    }
    // con paraAlumnos(): sin el oculto ni la solucion, valida
    Pack pa = CorrectorJson.leer( new File( dir, "pack.json" ), Pack.class ).paraAlumnos();
    CorrectorJson.guardar( pa, new File( alumnos, "pack.json" ) );
    try ( CorrectorPack p = CorrectorPack.abrir( alumnos ) ) {
      sinProblemas( p );
      igual( 1, p.pack().casos.size(), "solo el visible" );
      nulo( p.solucion(), "sin solucion" );
      igual( "Ejercicio 3 - Ventas por producto · 1 caso · 10 pts", p.resumen( "es" ), "resumen del alumno" );
    }
    // y comprimida
    File zip = new File( r, "alumnos.zip" );
    CorrectorPack.comprimir( alumnos, zip );
    try ( CorrectorPack p = CorrectorPack.abrir( zip, trabajo() ) ) {
      sinProblemas( p );
    }
  }

  public void pruebaComprimirIdaYVuelta() throws Exception {
    File r = CorrectorRecursos.copia();
    File dir = new File( r, "packs/" + CorrectorRecursos.TABLA );
    new File( dir, "vacia" ).mkdirs();
    File zip = new File( dir, "dentro.zip" ); // el zip dentro de la carpeta no se mete a si mismo
    CorrectorPack.comprimir( dir, zip );
    File dest = trabajo();
    CorrectorPack.descomprimir( zip, dest, CorrectorPack.TOPE_ZIP_BYTES, CorrectorPack.TOPE_ZIP_ENTRADAS );
    List<String> esperado = listar( dir );
    esperado.remove( "dentro.zip" );
    igual( esperado, listar( dest ), "mismos archivos" );
    cierto( new File( dest, "vacia" ).isDirectory(), "carpeta vacia incluida" );
    for ( String rel : esperado ) {
      cierto( Arrays.equals( Files.readAllBytes( new File( dir, rel ).toPath() ), Files.readAllBytes( new File( dest,
        rel ).toPath() ) ), "mismos bytes: " + rel );
    }
    falso( new File( dest, "dentro.zip" ).exists(), "sin el zip" );
    for ( String n : dir.list() ) {
      falso( n.endsWith( ".tmp" ), "sin temporales: " + n );
    }
  }

  /** Cada codigo tiene texto en en/es/pt, con las mismas claves y los mismos marcadores en los tres. */
  public void pruebaTextos() throws Exception {
    Properties en = textos( "en" );
    Properties es = textos( "es" );
    Properties pt = textos( "pt" );
    igual( en.stringPropertyNames(), es.stringPropertyNames(), "mismas claves pack.* en es" );
    igual( en.stringPropertyNames(), pt.stringPropertyNames(), "mismas claves pack.* en pt" );
    for ( String c : CorrectorPack.CODIGOS ) {
      cierto( en.containsKey( "pack." + c ), "texto de " + c );
    }
    Pattern marca = Pattern.compile( "%[sd]" );
    for ( String k : en.stringPropertyNames() ) {
      igual( marcas( marca, en.getProperty( k ) ), marcas( marca, es.getProperty( k ) ), "marcadores es de " + k );
      igual( marcas( marca, en.getProperty( k ) ), marcas( marca, pt.getProperty( k ) ), "marcadores pt de " + k );
    }
    igual( new TreeSet<>( CorrectorPack.CODIGOS ).size(), CorrectorPack.CODIGOS.size(), "codigos sin repetir" );
    igual( "Aviso", Rotulos.de( CorrectorPack.AREA_TEXTOS, "es" ).s( "pack.severity.warning" ), "Rotulos lee el area" );
  }

  // ------------------------------------------------------------------ ayudas

  private interface Edicion {
    void editar( ObjectNode n ) throws Exception;
  }

  /** Una copia de ventas-inline.json con un cambio, en la carpeta de prueba. */
  private static File json( String nombre, Edicion e ) throws Exception {
    ObjectNode n = (ObjectNode) CorrectorJson.mapper().readTree( CorrectorRecursos.pack( CorrectorRecursos.INLINE ) );
    e.editar( n );
    File f = new File( trabajo(), nombre + ".json" );
    CorrectorJson.guardar( n, f );
    return f;
  }

  private static String leerInline( String clave ) throws IOException {
    return CorrectorJson.mapper().readTree( CorrectorRecursos.pack( CorrectorRecursos.INLINE ) ).get( "inline" ).get(
      clave ).asText();
  }

  /**
   * El pack editado tiene exactamente un error con ese codigo y ese donde, cuyo mensaje (en ingles) contiene el
   * texto; y exactamente esos avisos ("codigo donde"). Con codigo null solo carga.
   */
  private static Problema error( String nombre, Edicion e, String codigo, String donde, String texto,
                                 Object... avisos ) throws Exception {
    File f = json( nombre, e );
    try ( CorrectorPack p = CorrectorPack.abrir( f, trabajo() ) ) {
      if ( codigo == null ) {
        return null;
      }
      Problema pr = unError( p, codigo, donde );
      contiene( pr.mensaje, texto, nombre + ": mensaje" );
      List<String> esperados = new ArrayList<>();
      for ( Object a : avisos ) {
        if ( a instanceof String ) {
          esperados.add( (String) a );
        }
      }
      List<String> hay = codigos( p.avisos() );
      for ( String a : esperados ) {
        cierto( hay.stream().anyMatch( h -> h.startsWith( a ) ), nombre + ": falta el aviso " + a + "\n"
          + p.textoProblemas( "en" ) );
      }
      igual( esperados.size(), hay.size(), nombre + ": avisos\n" + p.textoProblemas( "en" ) );
      return pr;
    }
  }

  /** Sin errores y con un solo aviso "codigo donde". */
  private static Problema aviso( String nombre, Edicion e, String codigoDonde ) throws Exception {
    File f = json( nombre, e );
    try ( CorrectorPack p = CorrectorPack.abrir( f, trabajo() ) ) {
      igual( 0, p.errores().size(), nombre + ": sin errores\n" + p.textoProblemas( "en" ) );
      igual( Collections.singletonList( codigoDonde ), codigos( p.avisos() ), nombre + ": avisos" );
      cierto( p.valido(), nombre + ": un aviso no invalida" );
      return p.avisos().get( 0 );
    }
  }

  private static void sinProblemas( String nombre, Edicion e ) throws Exception {
    try ( CorrectorPack p = CorrectorPack.abrir( json( nombre, e ), trabajo() ) ) {
      sinProblemas( p );
    }
  }

  private static void sinProblemas( CorrectorPack p ) {
    if ( !p.problemas().isEmpty() ) {
      falla( "se esperaba un pack sin problemas (" + p.origen() + "):\n" + p.textoProblemas( "en" ) );
    }
    cierto( p.valido(), "valido" );
  }

  /** Exactamente un error, con ese codigo y ese donde; lo devuelve. */
  private static Problema unError( CorrectorPack p, String codigo, String donde ) {
    List<Problema> e = p.errores();
    if ( e.size() != 1 || !codigo.equals( e.get( 0 ).codigo ) || !donde.equals( e.get( 0 ).donde ) ) {
      falla( "se esperaba solo el error " + codigo + " en " + donde + " y hay:\n" + p.textoProblemas( "en" ) );
    }
    falso( p.valido(), "con un error no es valido" );
    Problema p0 = e.get( 0 );
    noNulo( p0.mensaje, "mensaje" );
    falso( p0.mensaje.equals( p0.clave ) || p0.mensaje.startsWith( p0.codigo ), "el mensaje no es la clave ni el "
      + "codigo (falta el texto): " + p0.mensaje );
    return p0;
  }

  private static Problema unAviso( CorrectorPack p, String codigo, String donde ) {
    for ( Problema a : p.avisos() ) {
      if ( codigo.equals( a.codigo ) && donde.equals( a.donde ) ) {
        return a;
      }
    }
    falla( "falta el aviso " + codigo + " en " + donde + ":\n" + p.textoProblemas( "en" ) );
    return null;
  }

  private static Problema aviso( CorrectorPack p, String donde ) {
    return unAviso( p, CorrectorPack.CLAVE_DESCONOCIDA, donde );
  }

  /** "codigo donde" de cada problema, en orden. */
  private static List<String> codigos( List<Problema> l ) {
    return l.stream().map( p -> p.codigo + " " + p.donde ).collect( Collectors.toList() );
  }

  /** Carpeta nueva (que no existe) bajo {@code <prueba>/corrector-pack}. */
  private static synchronized File trabajo() {
    File base = new File( EntornoKettle.carpeta(), "corrector-pack" );
    if ( !limpio ) {
      CorrectorPack.borrar( base );
      limpio = true;
    }
    return new File( base, "t" + N.incrementAndGet() );
  }

  /** Archivos (no carpetas) bajo dir, relativos con /, ordenados. */
  private static List<String> listar( File dir ) throws IOException {
    final Path base = dir.toPath();
    try ( Stream<Path> s = Files.walk( base ) ) {
      return s.filter( Files::isRegularFile ).map( p -> base.relativize( p ).toString().replace( '\\', '/' ) )
        .sorted().collect( Collectors.toList() );
    }
  }

  private static void copiarCarpeta( File desde, File hacia ) throws IOException {
    final Path d = desde.toPath();
    final Path h = hacia.toPath();
    try ( Stream<Path> s = Files.walk( d ) ) {
      for ( Path p : s.sorted().collect( Collectors.toList() ) ) {
        Path q = h.resolve( d.relativize( p ).toString() );
        if ( Files.isDirectory( p ) ) {
          Files.createDirectories( q );
        } else {
          Files.copy( p, q );
        }
      }
    }
  }

  private static String leer( File f, Charset cs ) throws IOException {
    return new String( Files.readAllBytes( f.toPath() ), cs );
  }

  private static File escribir( File f, String texto ) throws IOException {
    EntornoKettle.escribir( f, texto );
    return f;
  }

  /** Un .zip con pares nombre, contenido (bytes en ISO-8859-1 para poder llevar binarios en un String). */
  private static File zip( File f, Charset nombres, String... pares ) throws IOException {
    f.getAbsoluteFile().getParentFile().mkdirs();
    try ( ZipOutputStream z = new ZipOutputStream( new FileOutputStream( f ), nombres ) ) {
      for ( int i = 0; i < pares.length; i += 2 ) {
        z.putNextEntry( new ZipEntry( pares[ i ] ) );
        z.write( pares[ i + 1 ].getBytes( StandardCharsets.ISO_8859_1.equals( nombres ) ? StandardCharsets.ISO_8859_1
          : StandardCharsets.UTF_8 ) );
        z.closeEntry();
      }
    }
    return f;
  }

  /** Cambia el tamaño descomprimido que declara el directorio central para esa entrada. */
  private static byte[] mentirTamano( byte[] b, String entrada, int tamano ) {
    byte[] nombre = entrada.getBytes( StandardCharsets.UTF_8 );
    for ( int i = 0; i + 46 < b.length; i++ ) {
      if ( b[ i ] == 0x50 && b[ i + 1 ] == 0x4b && b[ i + 2 ] == 0x01 && b[ i + 3 ] == 0x02 ) {
        int largo = ( b[ i + 28 ] & 0xFF ) | ( ( b[ i + 29 ] & 0xFF ) << 8 );
        if ( largo == nombre.length && Arrays.equals( Arrays.copyOfRange( b, i + 46, i + 46 + largo ), nombre ) ) {
          for ( int k = 0; k < 4; k++ ) {
            b[ i + 24 + k ] = (byte) ( tamano >>> ( 8 * k ) );
          }
          return b;
        }
      }
    }
    throw new IllegalStateException( "no esta la entrada " + entrada );
  }

  private static boolean junction( File enlace, File destino ) {
    try {
      Process pr = new ProcessBuilder( "cmd", "/c", "mklink", "/J", enlace.getAbsolutePath(),
        destino.getAbsolutePath() ).redirectErrorStream( true ).start();
      try ( InputStream in = pr.getInputStream() ) {
        while ( in.read() >= 0 ) {
          // consumir la salida
        }
      }
      return pr.waitFor( 30, TimeUnit.SECONDS ) && pr.exitValue() == 0 && enlace.isDirectory();
    } catch ( IOException e ) {
      return false;
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Las claves pack.* del area corrector en un idioma (sin el respaldo en ingles de Rotulos). */
  private static Properties textos( String idioma ) throws IOException {
    Properties todo = new Properties();
    try ( InputStream in = PruebaCorrectorPack.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA
      + CorrectorPack.AREA_TEXTOS + "_" + idioma + ".properties" ) ) {
      noNulo( in, "corrector_" + idioma + ".properties" );
      todo.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    Properties p = new Properties();
    for ( String k : todo.stringPropertyNames() ) {
      if ( k.startsWith( "pack." ) ) {
        p.setProperty( k, todo.getProperty( k ) );
      }
    }
    return p;
  }

  private static List<String> marcas( Pattern p, String s ) {
    List<String> l = new ArrayList<>();
    Matcher m = p.matcher( s );
    while ( m.find() ) {
      l.add( m.group() );
    }
    return l;
  }
}
