package io.github.ivi2511.pentahomcp.informes.tema;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pentaho.di.core.Const;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;

/**
 * Lee y escribe {@code Const.getKettleDirectory() + \pentaho-live\informes.json} y el logo (diseno
 * §4.1). {@code Const.getKettleDirectory()} respeta KETTLE_HOME (variable de entorno, despues system
 * property, despues user.home) y le suma {@code .kettle}.
 * <ul>
 * <li>Escritura atomica: {@code .tmp} y despues {@code move}; si el JSON viejo estaba roto se guarda
 * aparte como {@code informes.roto.json} antes de pisarlo.</li>
 * <li>Si el archivo no existe se usan los defaults sin aviso; si esta roto, los defaults y un aviso
 * traducido (el panel lo muestra una vez).</li>
 * <li>El logo es {@code logo.png} o {@code logo.jpg} en la misma carpeta: solo PNG o JPG (por los
 * bytes, no por la extension) de hasta 2 MB. SVG no.</li>
 * </ul>
 * Textos visibles: area {@code tema} de {@link Rotulos}.
 *
 * @dueño WP6
 */
public final class Ajustes {

  /** Subcarpeta dentro de {@code <KETTLE_HOME>\.kettle}. */
  public static final String SUBCARPETA = "pentaho-live";
  /** Nombre del archivo de configuracion. */
  public static final String ARCHIVO = "informes.json";
  /** Donde queda una copia del JSON roto antes de pisarlo. */
  public static final String ARCHIVO_ROTO = "informes.roto.json";
  public static final String LOGO_PNG = "logo.png";
  public static final String LOGO_JPG = "logo.jpg";
  public static final String MIME_PNG = "image/png";
  public static final String MIME_JPG = "image/jpeg";
  /** Tope del logo: 2 MB. */
  public static final long LOGO_MAX = 2L * 1024 * 1024;

  /** Area de textos de este paquete. */
  public static final String AREA = "tema";

  private static final Object CANDADO = new Object();

  private Ajustes() {
  }

  /** La carpeta {@code <KETTLE_HOME>\.kettle\pentaho-live} (puede no existir todavia). */
  public static File carpeta() {
    return new File( Const.getKettleDirectory(), SUBCARPETA );
  }

  /** {@code <carpeta>\informes.json} (puede no existir). */
  public static File archivo() {
    return new File( carpeta(), ARCHIVO );
  }

  /** El tema guardado, o la plantilla neutra si no hay archivo o esta roto. Nunca null. */
  public static Tema cargar() {
    return cargar( null, null );
  }

  /**
   * Como {@link #cargar()}, y deja en avisos (si no es null) los problemas encontrados, ya traducidos:
   * JSON roto, colores invalidos, logo faltante o invalido. Nunca lanza excepciones.
   */
  public static Tema cargar( String idioma, List<String> avisos ) {
    Rotulos r = Rotulos.de( AREA, idioma );
    synchronized ( CANDADO ) {
      File f;
      try {
        f = archivo();
      } catch ( RuntimeException e ) {
        avisar( avisos, r.f( "ajustes_roto", SUBCARPETA + File.separator + ARCHIVO, e.toString() ) );
        return new Tema();
      }
      if ( !f.isFile() ) {
        return new Tema();
      }
      Tema t;
      try {
        String json = new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
        if ( json.startsWith( "﻿" ) ) {
          json = json.substring( 1 );
        }
        t = ModeloJson.leer( json, Tema.class );
        if ( t == null ) {
          throw new IOException( "null" );
        }
      } catch ( IOException | RuntimeException e ) {
        avisar( avisos, r.f( "ajustes_roto", f.getAbsolutePath(), mensaje( e ) ) );
        return new Tema();
      }
      completar( t, r, avisos );
      leerLogo( t, f.getParentFile(), r, avisos );
      return t;
    }
  }

  /**
   * Guarda el tema y el logo. El logo sale de {@link Tema#logoDatos}: si hay bytes se escriben como
   * logo.png o logo.jpg (segun el contenido) y se borra el de la otra extension; si es null, se borran
   * los dos y el JSON queda sin "logo". Ajusta {@code t.logo} y {@code t.logoMime} a lo guardado.
   *
   * @throws IOException si no se puede escribir, o si el logo no es PNG/JPG o pasa de 2 MB (mensaje
   *                     traducido segun {@code t.idioma})
   */
  public static void guardar( Tema t ) throws IOException {
    if ( t == null ) {
      throw new IllegalArgumentException( "tema null" );
    }
    Rotulos r = Rotulos.de( AREA, t.idioma );
    synchronized ( CANDADO ) {
      File dir = carpeta();
      if ( !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
        throw new IOException( r.f( "carpeta_no", dir.getAbsolutePath() ) );
      }
      // 1) logo: primero se valida, asi un logo malo no deja el JSON a medias
      String nombreLogo = null;
      String mime = null;
      if ( t.logoDatos != null ) {
        mime = validarLogo( t.logoDatos, r );
        nombreLogo = MIME_PNG.equals( mime ) ? LOGO_PNG : LOGO_JPG;
      }
      Tema c = t.copia();
      completar( c, r, null );
      c.logo = nombreLogo;
      String json = ModeloJson.escribir( c, true ) + System.lineSeparator();

      if ( nombreLogo != null ) {
        File destino = new File( dir, nombreLogo );
        if ( !destino.isFile() || !Arrays.equals( Files.readAllBytes( destino.toPath() ), t.logoDatos ) ) {
          escribirAtomico( destino, t.logoDatos );
        }
        Files.deleteIfExists( new File( dir, LOGO_PNG.equals( nombreLogo ) ? LOGO_JPG : LOGO_PNG ).toPath() );
      } else {
        Files.deleteIfExists( new File( dir, LOGO_PNG ).toPath() );
        Files.deleteIfExists( new File( dir, LOGO_JPG ).toPath() );
      }

      // 2) si el JSON viejo esta roto, se guarda aparte antes de pisarlo
      File f = new File( dir, ARCHIVO );
      if ( f.isFile() && !legible( f ) ) {
        Files.copy( f.toPath(), new File( dir, ARCHIVO_ROTO ).toPath(), StandardCopyOption.REPLACE_EXISTING );
      }
      escribirAtomico( f, json.getBytes( StandardCharsets.UTF_8 ) );
      t.logo = nombreLogo;
      t.logoMime = mime;
    }
  }

  /**
   * Lee un logo elegido por el usuario y lo pone en el tema (logoDatos, logoMime y logo). No escribe
   * nada: queda guardado con {@link #guardar}.
   *
   * @throws IOException con un mensaje traducido si no existe, no es PNG/JPG o pasa de 2 MB
   */
  public static void ponerLogo( Tema t, File archivo, String idioma ) throws IOException {
    Rotulos r = Rotulos.de( AREA, idioma );
    if ( archivo == null || !archivo.isFile() ) {
      throw new IOException( r.f( "logo_falta", archivo == null ? "" : archivo.getAbsolutePath() ) );
    }
    if ( archivo.length() > LOGO_MAX ) {
      throw new IOException( r.f( "logo_grande", archivo.getName(), LOGO_MAX / ( 1024 * 1024 ) ) );
    }
    byte[] b = Files.readAllBytes( archivo.toPath() );
    String mime = validarLogo( b, r );
    t.logoDatos = b;
    t.logoMime = mime;
    t.logo = MIME_PNG.equals( mime ) ? LOGO_PNG : LOGO_JPG;
  }

  /** Saca el logo del tema (al guardar se borra el archivo). */
  public static void quitarLogo( Tema t ) {
    t.logoDatos = null;
    t.logoMime = null;
    t.logo = null;
  }

  /** "image/png" o "image/jpeg" segun los primeros bytes; null si no es ninguno. */
  public static String mimeImagen( byte[] b ) {
    if ( b == null ) {
      return null;
    }
    if ( b.length >= 8 && ( b[ 0 ] & 0xFF ) == 0x89 && b[ 1 ] == 'P' && b[ 2 ] == 'N' && b[ 3 ] == 'G'
      && b[ 4 ] == 0x0D && b[ 5 ] == 0x0A && b[ 6 ] == 0x1A && b[ 7 ] == 0x0A ) {
      return MIME_PNG;
    }
    if ( b.length >= 3 && ( b[ 0 ] & 0xFF ) == 0xFF && ( b[ 1 ] & 0xFF ) == 0xD8 && ( b[ 2 ] & 0xFF ) == 0xFF ) {
      return MIME_JPG;
    }
    return null;
  }

  // ------------------------------------------------------------------ internos

  /** Valida tipo y tamano; devuelve el mime. */
  private static String validarLogo( byte[] b, Rotulos r ) throws IOException {
    if ( b.length > LOGO_MAX ) {
      throw new IOException( r.f( "logo_grande", "logo", LOGO_MAX / ( 1024 * 1024 ) ) );
    }
    String mime = mimeImagen( b );
    if ( mime == null ) {
      throw new IOException( r.s( "logo_tipo" ) );
    }
    return mime;
  }

  /** Rellena lo que falte o este mal en un tema leido de disco (null anidados, colores, pagina). */
  private static void completar( Tema t, Rotulos r, List<String> avisos ) {
    if ( t.identidad == null ) {
      t.identidad = new Tema.Identidad();
    }
    Tema.Identidad id = t.identidad;
    id.autor = id.autor == null ? "" : id.autor;
    id.institucion = id.institucion == null ? "" : id.institucion;
    id.curso = id.curso == null ? "" : id.curso;
    id.contacto = id.contacto == null ? "" : id.contacto;
    if ( t.colores == null ) {
      t.colores = new Tema.Colores();
    }
    t.colores.primario = color( t.colores.primario, Tema.PRIMARIO, r, avisos );
    t.colores.acento = color( t.colores.acento, Tema.ACENTO, r, avisos );
    t.colores.secundario = color( t.colores.secundario, Tema.SECUNDARIO, r, avisos );
    if ( Tema.LETTER.equalsIgnoreCase( t.pagina ) ) {
      t.pagina = Tema.LETTER;
    } else {
      t.pagina = Tema.A4;
    }
    if ( t.idioma != null && t.idioma.trim().isEmpty() ) {
      t.idioma = null;
    } else if ( t.idioma != null ) {
      t.idioma = Rotulos.normalizar( t.idioma );
    }
    if ( t.salida == null ) {
      t.salida = new Tema.Salida();
    }
    if ( t.salida.carpeta == null ) {
      t.salida.carpeta = "";
    }
    if ( t.salida.formatos == null ) {
      t.salida.formatos = new Tema.Salida().formatos;
    } else {
      List<String> fs = new ArrayList<>();
      for ( String s : t.salida.formatos ) {
        if ( s != null && !s.trim().isEmpty() && !fs.contains( s.trim() ) ) {
          fs.add( s.trim() );
        }
      }
      t.salida.formatos = fs;
    }
    if ( !Tema.HTML_LIVIANO.equals( t.salida.html ) ) {
      t.salida.html = Tema.HTML_INTERACTIVO;
    }
    if ( t.perfil == null ) {
      t.perfil = new Tema.Perfil();
    }
    if ( t.perfil.filas <= 0 ) {
      t.perfil.filas = new Tema.Perfil().filas;
    }
    if ( t.navegador == null ) {
      t.navegador = "";
    }
  }

  private static String color( String valor, String defecto, Rotulos r, List<String> avisos ) {
    if ( valor == null || valor.trim().isEmpty() ) {
      return defecto;
    }
    if ( !Paleta.valido( valor ) ) {
      avisar( avisos, r.f( "color_invalido", valor, defecto ) );
      return defecto;
    }
    return Paleta.normalizar( valor, defecto );
  }

  /** Carga el logo nombrado en t.logo; solo nombres simples logo.png / logo.jpg dentro de la carpeta. */
  private static void leerLogo( Tema t, File dir, Rotulos r, List<String> avisos ) {
    t.logoDatos = null;
    t.logoMime = null;
    if ( t.logo == null || t.logo.trim().isEmpty() ) {
      t.logo = null;
      return;
    }
    String nombre = t.logo.trim();
    if ( !LOGO_PNG.equalsIgnoreCase( nombre ) && !LOGO_JPG.equalsIgnoreCase( nombre ) ) {
      avisar( avisos, r.f( "logo_nombre", nombre ) );
      t.logo = null;
      return;
    }
    File f = new File( dir, nombre.toLowerCase( java.util.Locale.ROOT ) );
    try {
      if ( !f.isFile() ) {
        throw new IOException( r.f( "logo_falta", f.getAbsolutePath() ) );
      }
      if ( f.length() > LOGO_MAX ) {
        throw new IOException( r.f( "logo_grande", f.getName(), LOGO_MAX / ( 1024 * 1024 ) ) );
      }
      byte[] b = Files.readAllBytes( f.toPath() );
      t.logoMime = validarLogo( b, r );
      t.logoDatos = b;
      t.logo = f.getName();
    } catch ( IOException | RuntimeException e ) {
      avisar( avisos, mensaje( e ) );
      t.logo = null;
      t.logoDatos = null;
      t.logoMime = null;
    }
  }

  private static boolean legible( File f ) {
    try {
      String json = new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
      if ( json.startsWith( "﻿" ) ) {
        json = json.substring( 1 );
      }
      return ModeloJson.leer( json, Tema.class ) != null;
    } catch ( IOException | RuntimeException e ) {
      return false;
    }
  }

  /**
   * Escribe en {@code <destino>.tmp} y lo mueve encima del destino. En Windows un antivirus o OneDrive
   * pueden tener el archivo abierto un instante: se reintenta unas veces.
   */
  static void escribirAtomico( File destino, byte[] datos ) throws IOException {
    Path dst = destino.toPath();
    Path tmp = new File( destino.getParentFile(), destino.getName() + ".tmp" ).toPath();
    Files.write( tmp, datos );
    IOException ultima = null;
    for ( int intento = 0; intento < 5; intento++ ) {
      try {
        try {
          Files.move( tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING );
        } catch ( AtomicMoveNotSupportedException e ) {
          Files.move( tmp, dst, StandardCopyOption.REPLACE_EXISTING );
        }
        return;
      } catch ( FileSystemException e ) {
        // AccessDeniedException y compania: el archivo esta abierto por otro proceso
        ultima = e;
      }
      try {
        Thread.sleep( 60L * ( intento + 1 ) );
      } catch ( InterruptedException e ) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    Files.deleteIfExists( tmp );
    throw ultima != null ? ultima : new IOException( "move " + tmp + " -> " + dst );
  }

  private static void avisar( List<String> avisos, String texto ) {
    if ( avisos != null && texto != null && !avisos.contains( texto ) ) {
      avisos.add( texto );
    }
  }

  private static String mensaje( Throwable e ) {
    String m = e.getMessage();
    if ( m == null || m.trim().isEmpty() ) {
      return e.getClass().getSimpleName();
    }
    // Jackson agrega la ubicacion en varias lineas: alcanza la primera
    int nl = m.indexOf( '\n' );
    return nl > 0 ? m.substring( 0, nl ).trim() : m;
  }
}
