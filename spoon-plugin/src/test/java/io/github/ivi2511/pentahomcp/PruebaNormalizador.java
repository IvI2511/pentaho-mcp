package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.pentaho.di.core.CheckResultInterface;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.diff.Diff;
import io.github.ivi2511.pentahomcp.diff.Normalizador;
import io.github.ivi2511.pentahomcp.diff.ResultadoDiff;
import io.github.ivi2511.pentahomcp.diff.Salidas;

/**
 * El normalizador (diseño E2) no cambia nada que Kettle lea: sobre todos los ejemplos y patrones de la skill,
 * <ul>
 * <li>es idempotente y el diff semántico entre el original y el normalizado está vacío (con posiciones);</li>
 * <li>Kettle lee los dos igual: el getXML() de lo cargado es idéntico, carácter por carácter, salvo los campos
 * volátiles (modified_user, modified_date);</li>
 * <li>validar (checkSteps / checkJobEntries) da los mismos avisos;</li>
 * <li>correr cada ejemplo y cada job de patrón en las dos copias da el mismo resultado (errores, filas) y los mismos
 * archivos de salida;</li>
 * <li>un guardado de Spoon (getXML) normalizado es estable: normalizar, cargar, guardar y normalizar da lo mismo.</li>
 * </ul>
 */
public class PruebaNormalizador {

  private File base;
  private File orig;
  private File canon;

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    base = EntornoKettle.salida( "normalizador" );
    EntornoKettle.borrar( base.toPath() );
    orig = new File( base, "orig" );
    canon = new File( base, "canon" );
    File skill = PruebaDiff.skill();
    for ( String d : new String[] { "manual/ejemplos", "patrones" } ) {
      copiar( new File( skill, d ).toPath(), new File( orig, d ).toPath() );
      copiar( new File( skill, d ).toPath(), new File( canon, d ).toPath() );
    }
    for ( File f : kettle( canon ) ) {
      byte[] b = Files.readAllBytes( f.toPath() );
      Files.write( f.toPath(), Normalizador.normalizar( b ).getBytes( StandardCharsets.UTF_8 ) );
    }
  }

  /** Los .ktr/.kjb bajo una carpeta, ordenados. */
  static List<File> kettle( File dir ) throws IOException {
    final List<File> out = new ArrayList<File>();
    Files.walkFileTree( dir.toPath(), new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile( Path f, BasicFileAttributes a ) {
        String n = f.getFileName().toString();
        if ( n.endsWith( ".ktr" ) || n.endsWith( ".kjb" ) ) {
          out.add( f.toFile() );
        }
        return FileVisitResult.CONTINUE;
      }
    } );
    Collections.sort( out );
    return out;
  }

  private File par( File f ) {
    return new File( orig, canon.toPath().relativize( f.toPath() ).toString() );
  }

  static String leer( File f ) throws IOException {
    return new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
  }

  /** Idempotente, LF, sin volátiles, y el diff semántico con el original vacío (posiciones incluidas). */
  public void pruebaA_IdempotenteYSinCambiosDeSignificado() throws Exception {
    List<File> fs = kettle( canon );
    cierto( fs.size() >= 20, "ejemplos y patrones: " + fs.size() );
    for ( File c : fs ) {
      String n = leer( c );
      igual( n, Normalizador.normalizar( n ), "idempotente: " + c.getName() );
      cierto( n.startsWith( Normalizador.DECLARACION + "\n" ), "declaración: " + c.getName() );
      noContiene( n, "\r", "LF: " + c.getName() );
      noContiene( n, "<modified_date>", "sin modified_date: " + c.getName() );
      ResultadoDiff r = Diff.comparar( Diff.archivo( par( c ) ), Diff.archivo( c ), false );
      cierto( r.iguales(), "mismo significado: " + c.getName() + "\n" + Salidas.texto( r, "es" ) );
    }
    // las sangrías mezcladas, el CRLF y las comillas sin escapar quedan como las escribe Kettle
    String raro = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<transformation>\r\n\t<info><name>x</name>"
      + "<modified_user>ana</modified_user><description>a \"b\" 'c' &amp; d</description>\r\n"
      + "<parameters>\r\n</parameters></info><step><name>s</name><separator> </separator><sql>SELECT 1\r\nFROM t"
      + "</sql><cr>a&#xd;b</cr></step></transformation>";
    String n = Normalizador.normalizar( raro );
    igual( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<transformation>\n  <info>\n    <name>x</name>\n"
      + "    <description>a &#34;b&#34; &#39;c&#39; &amp; d</description>\n    <parameters>\n    </parameters>\n"
      + "  </info>\n  <step>\n    <name>s</name>\n    <separator> </separator>\n    <sql>SELECT 1\nFROM t</sql>\n"
      + "    <cr>a&#xd;b</cr>\n  </step>\n</transformation>\n", n, "canónico" );
  }

  /** Kettle lee igual el original y el normalizado: el mismo getXML() salvo modified_user y modified_date. */
  public void pruebaB_KettleLeeLoMismo() throws Exception {
    int n = 0;
    for ( File c : kettle( canon ) ) {
      String a = sinVolatiles( xmlKettle( par( c ) ) );
      String b = sinVolatiles( xmlKettle( c ) );
      igual( a, b, "getXML de " + c.getName() );
      n++;
    }
    cierto( n >= 20, "comparados: " + n );
  }

  /** Lo que guarda Spoon (getXML), normalizado, es estable al cargarlo y guardarlo otra vez. */
  public void pruebaC_GuardadoDeSpoonEstable() throws Exception {
    File dir = new File( base, "spoon" );
    for ( File c : kettle( canon ) ) {
      File g = new File( dir, canon.toPath().relativize( c.toPath() ).toString() );
      g.getParentFile().mkdirs();
      String n1 = Normalizador.normalizar( xmlKettle( c ) );
      Files.write( g.toPath(), n1.getBytes( StandardCharsets.UTF_8 ) );
      String n2 = Normalizador.normalizar( xmlKettle( g ) );
      igual( n1, n2, "estable: " + c.getName() );
    }
  }

  /** Validar da los mismos avisos con el original y con el normalizado. */
  public void pruebaD_ValidarIgual() throws Exception {
    for ( File c : kettle( canon ) ) {
      igual( avisos( par( c ) ), avisos( c ), "avisos de " + c.getName() );
    }
  }

  /** Correr los ejemplos y los jobs de los patrones en las dos copias: mismo resultado y mismas salidas. */
  public void pruebaE_CorrerIgual() throws Exception {
    List<String> corridos = new ArrayList<String>();
    for ( File c : kettle( new File( canon, "manual/ejemplos" ) ) ) {
      String a = correr( par( c ) );
      String b = correr( c );
      igual( a, b, "resultado de " + c.getName() );
      corridos.add( c.getName() + ": " + b );
    }
    List<File> jobs = new ArrayList<File>();
    for ( File c : kettle( new File( canon, "patrones" ) ) ) {
      if ( c.getName().endsWith( ".kjb" ) ) {
        // el que prepara el origen de un patrón va antes que el que lo carga
        jobs.add( c.getName().contains( "preparar" ) ? Math.max( 0, jobs.size() - 1 ) : jobs.size(), c );
      }
    }
    for ( File c : jobs ) {
      String a = correr( par( c ) );
      String b = correr( c );
      igual( a, b, "resultado de " + c.getName() );
      corridos.add( c.getName() + ": " + b );
    }
    cierto( corridos.size() >= 12, "corridos: " + corridos );
    // las salidas de texto, iguales
    Map<String, String> sa = salidas( orig );
    Map<String, String> sb = salidas( canon );
    cierto( sa.size() >= 5, "archivos de salida: " + sa.keySet() );
    igual( sa.keySet(), sb.keySet(), "los mismos archivos de salida" );
    for ( String k : sa.keySet() ) {
      igual( sa.get( k ), sb.get( k ), "contenido de " + k );
    }
    contiene( corridos.toString(), "errors=0", "alguno corre bien" );
  }

  // ================================================================ util

  static String xmlKettle( File f ) throws Exception {
    if ( f.getName().endsWith( ".kjb" ) ) {
      return new JobMeta( f.getAbsolutePath(), null ).getXML();
    }
    return new TransMeta( f.getAbsolutePath() ).getXML();
  }

  static String sinVolatiles( String xml ) {
    return xml.replaceAll( "[ \\t]*<modified_(user|date)>[^<]*</modified_(user|date)>\\r?\\n", "" )
      .replaceAll( "[ \\t]*<modified_(user|date)/>\\r?\\n", "" );
  }

  static List<String> avisos( File f ) throws Exception {
    List<CheckResultInterface> rs = new ArrayList<CheckResultInterface>();
    if ( f.getName().endsWith( ".kjb" ) ) {
      JobMeta jm = new JobMeta( f.getAbsolutePath(), null );
      jm.checkJobEntries( rs, false, null, new Variables(), null, null );
    } else {
      TransMeta tm = new TransMeta( f.getAbsolutePath() );
      tm.checkSteps( rs, false, null, new Variables(), null, null );
    }
    List<String> out = new ArrayList<String>();
    for ( CheckResultInterface r : rs ) {
      String txt = r.getText() == null ? "" : r.getText();
      // las rutas de las dos copias difieren solo en la carpeta orig/canon
      txt = txt.replace( "\\canon\\", "\\X\\" ).replace( "\\orig\\", "\\X\\" ).replace( "/canon/", "/X/" )
        .replace( "/orig/", "/X/" );
      out.add( r.getType() + " " + ( r.getSourceInfo() == null ? "" : r.getSourceInfo().getName() ) + " " + txt );
    }
    return out;
  }

  /** Corre una transformación o un job y resume el resultado. */
  static String correr( File f ) throws Exception {
    if ( f.getName().endsWith( ".kjb" ) ) {
      JobMeta jm = new JobMeta( f.getAbsolutePath(), null );
      Job j = new Job( null, jm );
      j.setLogLevel( LogLevel.MINIMAL );
      j.initializeVariablesFrom( null );
      jm.setInternalKettleVariables( j );
      j.copyParametersFrom( jm );
      j.activateParameters();
      j.start();
      j.waitUntilFinished();
      return "errors=" + j.getResult().getNrErrors() + " result=" + j.getResult().getResult();
    }
    TransMeta tm = new TransMeta( f.getAbsolutePath() );
    Trans t = new Trans( tm );
    t.setLogLevel( LogLevel.MINIMAL );
    t.execute( null );
    t.waitUntilFinished();
    return "errors=" + t.getErrors() + " written=" + t.getResult().getNrLinesWritten() + " output="
      + t.getResult().getNrLinesOutput();
  }

  /** Los archivos de texto que escribieron las corridas (ruta relativa -> contenido). */
  static Map<String, String> salidas( final File raiz ) throws IOException {
    final Map<String, String> m = new TreeMap<String, String>();
    Files.walkFileTree( raiz.toPath(), new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile( Path f, BasicFileAttributes a ) throws IOException {
        String rel = raiz.toPath().relativize( f ).toString().replace( '\\', '/' );
        String n = f.getFileName().toString().toLowerCase();
        if ( rel.contains( "/salida/" ) && ( n.endsWith( ".csv" ) || n.endsWith( ".txt" ) ) ) {
          m.put( rel, new String( Files.readAllBytes( f ), StandardCharsets.UTF_8 ) );
        }
        return FileVisitResult.CONTINUE;
      }
    } );
    return m;
  }

  static void copiar( final Path desde, final Path hacia ) throws IOException {
    Files.walkFileTree( desde, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory( Path d, BasicFileAttributes a ) throws IOException {
        String n = d.getFileName() == null ? "" : d.getFileName().toString();
        if ( n.equals( "salida" ) ) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Files.createDirectories( hacia.resolve( desde.relativize( d ).toString() ) );
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile( Path f, BasicFileAttributes a ) throws IOException {
        String n = f.getFileName().toString();
        if ( n.endsWith( ".sqlite" ) || n.endsWith( ".db" ) ) {
          return FileVisitResult.CONTINUE;
        }
        Files.copy( f, hacia.resolve( desde.relativize( f ).toString() ), StandardCopyOption.REPLACE_EXISTING );
        return FileVisitResult.CONTINUE;
      }
    } );
  }
}
