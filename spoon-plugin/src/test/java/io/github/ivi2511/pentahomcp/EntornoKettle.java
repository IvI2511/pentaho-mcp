package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;

/**
 * Entorno de las pruebas headless (sin Spoon). test.ps1 lanza la JVM con:
 * <ul>
 * <li>cwd = {@code <Salida>\prueba} ({@link #carpeta()}), KETTLE_HOME = {@code <Salida>\prueba\kettle-home}
 * (variable de entorno y -D), user.home = {@code <Salida>\prueba\home}, PENTAHO_METASTORE_FOLDER propio;</li>
 * <li>KETTLE_PLUGIN_BASE_FOLDERS = {@code <PDI>\plugins} y los --add-opens de Spoon.bat;</li>
 * <li>-Dpruebas.recursos = spoon-plugin\src\test\resources, -Dpruebas.pdi = data-integration.</li>
 * </ul>
 * Nada escribe en data-integration ni en el ~\.kettle real: {@link #iniciar()} lo verifica.
 * Los ejemplos (ej\01..11) y dw.sqlite se usan siempre desde COPIAS bajo {@link #carpeta()}.
 *
 * @dueño WP0
 */
public final class EntornoKettle {

  private static boolean iniciado;
  private static File ejemplos;
  private static boolean dbLimpia;
  private static final AtomicInteger COPIAS = new AtomicInteger();

  private EntornoKettle() {
  }

  /** KettleEnvironment.init() una vez por JVM, con el cliente en SPOON (lo necesita metaverse). */
  public static synchronized void iniciar() throws Exception {
    if ( iniciado ) {
      return;
    }
    verificarAislado();
    KettleClientEnvironment.getInstance().setClient( KettleClientEnvironment.ClientType.SPOON );
    KettleEnvironment.init();
    iniciado = true;
  }

  /** Falla si Kettle escribiria fuera de la carpeta de prueba. */
  public static void verificarAislado() {
    String kd = Const.getKettleDirectory();
    String base = carpeta().getAbsolutePath();
    if ( kd == null || !new File( kd ).getAbsolutePath().toLowerCase().startsWith( base.toLowerCase() ) ) {
      throw new IllegalStateException( "KETTLE_HOME no apunta a la carpeta de prueba (" + kd + " no esta bajo "
        + base + "): correr con spoon-plugin\\test.ps1" );
    }
  }

  /** {@code <Salida>\prueba}: cwd de la JVM y raiz de todo lo que escriben las pruebas. */
  public static File carpeta() {
    String p = System.getProperty( "pruebas.dir" );
    File f = p == null ? new File( "" ).getAbsoluteFile() : new File( p ).getAbsoluteFile();
    f.mkdirs();
    return f;
  }

  /** spoon-plugin\src\test\resources (solo lectura: copiar antes de usar). */
  public static File recursos() {
    String p = System.getProperty( "pruebas.recursos" );
    if ( p == null ) {
      throw new IllegalStateException( "Falta -Dpruebas.recursos: correr con spoon-plugin\\test.ps1" );
    }
    return new File( p );
  }

  /** data-integration de PDI (solo lectura). */
  public static File pdi() {
    String p = System.getProperty( "pruebas.pdi" );
    if ( p == null ) {
      throw new IllegalStateException( "Falta -Dpruebas.pdi: correr con spoon-plugin\\test.ps1" );
    }
    return new File( p );
  }

  /** Subcarpeta de {@code <Salida>\prueba\salida} para dejar archivos generados (se crea). */
  public static File salida( String sub ) {
    File f = new File( new File( carpeta(), "salida" ), sub );
    f.mkdirs();
    return f;
  }

  /**
   * Copia fresca de src\test\resources\ej en {@code <prueba>\ej} (una vez por JVM). Los .ktr usan
   * ${Internal.Entry.Current.Directory}, asi que leen datos\ y escriben salida\ dentro de la copia.
   */
  public static synchronized File ejemplos() throws IOException {
    if ( ejemplos == null ) {
      File destino = new File( carpeta(), "ej" );
      borrar( destino.toPath() );
      copiar( new File( recursos(), "ej" ).toPath(), destino.toPath() );
      ejemplos = destino;
    }
    return ejemplos;
  }

  /** Un ejemplo de la copia por nombre exacto o por prefijo ("03" -> 03_ventas_por_producto.ktr). */
  public static File ejemplo( String nombre ) throws IOException {
    File dir = ejemplos();
    File exacto = new File( dir, nombre );
    if ( exacto.isFile() ) {
      return exacto;
    }
    File[] hijos = dir.listFiles();
    if ( hijos != null ) {
      for ( File f : hijos ) {
        if ( f.isFile() && f.getName().startsWith( nombre ) && ( f.getName().endsWith( ".ktr" ) || f.getName()
          .endsWith( ".kjb" ) ) ) {
          return f;
        }
      }
    }
    throw new IOException( "No hay ejemplo " + nombre + " en " + dir );
  }

  public static TransMeta trans( String ejemplo ) throws Exception {
    iniciar();
    return new TransMeta( ejemplo( ejemplo ).getAbsolutePath() );
  }

  public static JobMeta job( String ejemplo ) throws Exception {
    iniciar();
    return new JobMeta( ejemplo( ejemplo ).getAbsolutePath(), null );
  }

  /**
   * Captura de un ejemplo como la que haria capturarArchivo, pero sin lienzo (no hay Display) ni
   * ultima corrida.
   */
  public static Captura captura( String ejemplo ) throws Exception {
    File f = ejemplo( ejemplo );
    AbstractMeta m = f.getName().endsWith( ".kjb" ) ? job( ejemplo ) : trans( ejemplo );
    Captura c = new Captura();
    c.copia = m;
    c.docId = "prueba:" + f.getName();
    c.nombre = m.getName();
    c.archivo = f.getAbsolutePath();
    c.tipo = m instanceof TransMeta ? Captura.TRANSFORMACION : Captura.JOB;
    c.sinGuardar = false;
    c.capturado = new Date();
    return c;
  }

  /** Copia nueva de src\test\resources\db\dw.sqlite (cada llamada, un archivo distinto). */
  public static synchronized File copiaDw() throws IOException {
    File dir = new File( carpeta(), "db" );
    if ( !dbLimpia ) {
      borrar( dir.toPath() ); // las copias de corridas anteriores
      dbLimpia = true;
    }
    dir.mkdirs();
    File destino = new File( dir, "dw-" + System.currentTimeMillis() + "-" + COPIAS.incrementAndGet() + ".sqlite" );
    Files.copy( new File( recursos(), "db/dw.sqlite" ).toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING );
    return destino;
  }

  public static String leer( File f ) throws IOException {
    return new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
  }

  public static void escribir( File f, String texto ) throws IOException {
    File p = f.getAbsoluteFile().getParentFile();
    if ( p != null ) {
      p.mkdirs();
    }
    Files.write( f.toPath(), texto.getBytes( StandardCharsets.UTF_8 ) );
  }

  /** Borra un arbol (solo bajo {@link #carpeta()}, por seguridad). */
  public static void borrar( Path p ) throws IOException {
    if ( !Files.exists( p ) ) {
      return;
    }
    if ( !p.toAbsolutePath().normalize().startsWith( carpeta().toPath().toAbsolutePath().normalize() ) ) {
      throw new IOException( "No borro fuera de la carpeta de prueba: " + p );
    }
    Files.walkFileTree( p, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile( Path f, BasicFileAttributes a ) throws IOException {
        Files.delete( f );
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory( Path d, IOException e ) throws IOException {
        Files.delete( d );
        return FileVisitResult.CONTINUE;
      }
    } );
  }

  private static void copiar( final Path desde, final Path hacia ) throws IOException {
    Files.walkFileTree( desde, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory( Path d, BasicFileAttributes a ) throws IOException {
        Files.createDirectories( hacia.resolve( desde.relativize( d ).toString() ) );
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile( Path f, BasicFileAttributes a ) throws IOException {
        Files.copy( f, hacia.resolve( desde.relativize( f ).toString() ), StandardCopyOption.REPLACE_EXISTING );
        return FileVisitResult.CONTINUE;
      }
    } );
  }
}
