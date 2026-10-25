package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * Donde estan las pruebas de una transformacion y cuales toca un cambio (0.7.0, P1 y P2). Sin SWT ni Spoon.
 * <ul>
 * <li><b>Carpetas</b>: {@code tests/} o {@code pruebas/} en la carpeta de la .ktr y en sus dos padres (un proyecto con
 * las transformaciones en una subcarpeta). Una prueba es de una .ktr si su {@code transformation} apunta a ella. Las
 * nuevas van a {@code <carpeta de la .ktr>/tests/}. Lo leido se guarda por carpeta y se vuelve a leer solo si cambio
 * algun archivo (el vigilante del guardado mira cada 2 s).</li>
 * <li><b>Ultimo resultado</b> de cada prueba en esta sesion de Spoon (memoria: no se escribe en el proyecto).</li>
 * <li><b>Que pruebas toca un cambio</b> (P2): la firma de una transformacion es un hash por step (su XML sin la
 * posicion en el lienzo, mas los saltos que le llegan) y uno de lo demas (parametros, conexiones, ajustes; sin notas
 * ni fechas). Entre dos firmas cambian unos steps; una prueba esta afectada si alguno es uno de sus steps capturados
 * o un antecesor por saltos habilitados (sin pasar de un step con dataset: lo de antes no le llega). Si cambio lo
 * demas, o no hay firma anterior, o la prueba compara tablas: todas.</li>
 * </ul>
 *
 * @dueño P1/P2 (0.7.0)
 */
final class PruebasArchivos {

  static final List<String> CARPETAS = Collections.unmodifiableList( Arrays.asList( "tests", "pruebas" ) );
  /** La clave de la firma con lo que no es de un step. */
  static final String GLOBAL = "\u0000transformation";

  private static final Pattern GUI = Pattern.compile( "(?s)<GUI>.*?</GUI>" );
  private static final Pattern FUERA = Pattern.compile( "(?s)<step>.*?</step>|<order>.*?</order>|<notepads>.*?"
    + "</notepads>|<(modified|created)_(date|user)>[^<]*</(modified|created)_(date|user)>" );

  /** Carpeta → lo leido. */
  private static final Map<String, Leida> CACHE = new HashMap<String, Leida>();
  /** Ruta canonica de la prueba → su ultimo resultado (esta sesion). */
  private static final Map<String, ResultadoPrueba> ULTIMOS = Collections.synchronizedMap(
    new LinkedHashMap<String, ResultadoPrueba>() );

  private PruebasArchivos() {
  }

  /** Lo leido de una carpeta de pruebas: cada archivo con la .ktr que prueba (canonica) o null si es invalida. */
  private static final class Leida {
    long huella;
    final Map<File, String> pruebas = new LinkedHashMap<File, String>();
  }

  // ------------------------------------------------------------------ carpetas

  /** Las carpetas de pruebas que existen para una .ktr (la suya y las de sus dos padres). */
  static List<File> carpetas( File ktr ) {
    List<File> l = new ArrayList<File>();
    File d = ktr == null ? null : ktr.getAbsoluteFile().getParentFile();
    for ( int nivel = 0; d != null && nivel < 3; nivel++, d = d.getParentFile() ) {
      for ( String n : CARPETAS ) {
        File c = new File( d, n );
        if ( c.isDirectory() ) {
          l.add( c );
        }
      }
    }
    return l;
  }

  /** {@code <carpeta de la .ktr>/tests}: donde van las pruebas nuevas. */
  static File carpetaPorDefecto( File ktr ) {
    return new File( ktr.getAbsoluteFile().getParentFile(), CARPETAS.get( 0 ) );
  }

  /** Los {@code *.test.json} de una carpeta, ordenados por nombre. */
  static List<File> deCarpeta( File carpeta ) {
    List<File> l = new ArrayList<File>();
    File[] fs = carpeta == null ? null : carpeta.listFiles( ( x, n ) -> n.toLowerCase( Locale.ROOT ).endsWith(
      DefPrueba.EXTENSION ) );
    if ( fs != null ) {
      l.addAll( Arrays.asList( fs ) );
      Collections.sort( l, ( a, b ) -> a.getName().compareToIgnoreCase( b.getName() ) );
    }
    return l;
  }

  /** Las pruebas de una .ktr (en todas sus carpetas de pruebas). */
  static List<File> deTransformacion( File ktr ) {
    List<File> l = new ArrayList<File>();
    String objetivo = canonica( ktr );
    if ( objetivo == null ) {
      return l;
    }
    for ( File c : carpetas( ktr ) ) {
      for ( Map.Entry<File, String> e : leer( c ).pruebas.entrySet() ) {
        if ( objetivo.equalsIgnoreCase( e.getValue() == null ? "" : e.getValue() ) && !l.contains( e.getKey() ) ) {
          l.add( e.getKey() );
        }
      }
    }
    return l;
  }

  /** La .ktr que prueba un archivo (canonica), o null si no se puede leer. */
  static String transformacionDe( File prueba ) {
    for ( Map.Entry<File, String> e : leer( prueba.getAbsoluteFile().getParentFile() ).pruebas.entrySet() ) {
      if ( e.getKey().getAbsoluteFile().equals( prueba.getAbsoluteFile() ) ) {
        return e.getValue();
      }
    }
    return null;
  }

  private static Leida leer( File carpeta ) {
    List<File> fs = deCarpeta( carpeta );
    long huella = carpeta.lastModified();
    for ( File f : fs ) {
      huella = huella * 31 + f.lastModified() + f.length() + f.getName().hashCode();
    }
    String k = canonica( carpeta );
    synchronized ( CACHE ) {
      Leida x = CACHE.get( k );
      if ( x != null && x.huella == huella ) {
        return x;
      }
      Leida n = new Leida();
      n.huella = huella;
      for ( File f : fs ) {
        String t = null;
        try {
          JsonNode j = CorrectorJson.mapper().readTree( contenido( f ) );
          String ruta = j.path( "transformation" ).asText( "" );
          if ( !ruta.trim().isEmpty() ) {
            t = canonica( PruebasMotor.resolver( carpeta, ruta ) );
          }
        } catch ( Exception ignored ) {
          // invalida: se lista igual (el error lo da al correrla)
        }
        n.pruebas.put( f.getAbsoluteFile(), t );
      }
      CACHE.put( k, n );
      return n;
    }
  }

  private static String contenido( File f ) throws IOException {
    String s = new String( java.nio.file.Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
    return s.startsWith( "﻿" ) ? s.substring( 1 ) : s;
  }

  static String canonica( File f ) {
    if ( f == null ) {
      return null;
    }
    try {
      return f.getCanonicalPath();
    } catch ( IOException e ) {
      return f.getAbsolutePath();
    }
  }

  /**
   * Una prueba por nombre o ruta: una ruta a un .test.json; si no, el nombre (sin la extension) entre las pruebas de
   * la .ktr (si se paso) o de la carpeta.
   */
  static File buscar( String ref, File ktr, File carpeta ) {
    if ( ref == null || ref.trim().isEmpty() ) {
      return null;
    }
    String r = ref.trim();
    File f = new File( r );
    if ( f.isAbsolute() && f.isFile() ) {
      return f;
    }
    if ( carpeta != null && new File( carpeta, r ).isFile() ) {
      return new File( carpeta, r );
    }
    String nombre = r.toLowerCase( Locale.ROOT ).endsWith( DefPrueba.EXTENSION ) ? r.substring( 0, r.length()
      - DefPrueba.EXTENSION.length() ) : r;
    List<File> candidatas = new ArrayList<File>();
    if ( ktr != null ) {
      candidatas.addAll( deTransformacion( ktr ) );
    }
    if ( carpeta != null ) {
      candidatas.addAll( deCarpeta( carpeta ) );
    }
    for ( File c : candidatas ) {
      if ( PruebasMotor.nombreDeArchivo( c ).equalsIgnoreCase( nombre ) ) {
        return c;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ ultimos resultados

  static void recordar( ResultadoPrueba r ) {
    if ( r != null && r.archivoPrueba != null ) {
      ULTIMOS.put( canonica( new File( r.archivoPrueba ) ), r );
    }
  }

  static ResultadoPrueba ultimo( File prueba ) {
    return ULTIMOS.get( canonica( prueba ) );
  }

  // ------------------------------------------------------------------ firmas y pruebas afectadas (P2)

  /** step → hash de su XML sin la posicion y de los saltos que le llegan; {@link #GLOBAL} → lo demas. */
  static Map<String, String> firma( TransMeta tm ) {
    Map<String, String> f = new LinkedHashMap<String, String>();
    Map<String, StringBuilder> entradas = new HashMap<String, StringBuilder>();
    for ( TransHopMeta h : tm.getTransHops() ) {
      if ( h.getToStep() == null || h.getFromStep() == null ) {
        continue;
      }
      entradas.computeIfAbsent( h.getToStep().getName(), k -> new StringBuilder() ).append( h.getFromStep().getName() )
        .append( h.isEnabled() ? "+" : "-" ).append( '|' );
    }
    for ( StepMeta sm : tm.getSteps() ) {
      String xml;
      try {
        xml = GUI.matcher( sm.getXML() ).replaceAll( "" );
      } catch ( Throwable t ) {
        xml = String.valueOf( System.nanoTime() ); // ilegible: cuenta como cambiado
      }
      StringBuilder e = entradas.get( sm.getName() );
      f.put( sm.getName(), hash( xml + "\u0000" + ( e == null ? "" : e.toString() ) ) );
    }
    String resto;
    try {
      resto = FUERA.matcher( tm.getXML() ).replaceAll( "" );
    } catch ( Throwable t ) {
      resto = String.valueOf( System.nanoTime() );
    }
    f.put( GLOBAL, hash( resto ) );
    return f;
  }

  /** Los steps que cambiaron entre dos firmas (agregados, quitados o distintos); contiene {@link #GLOBAL} si lo demas. */
  static Set<String> cambiados( Map<String, String> antes, Map<String, String> despues ) {
    Set<String> s = new LinkedHashSet<String>();
    for ( Map.Entry<String, String> e : despues.entrySet() ) {
      if ( !e.getValue().equals( antes.get( e.getKey() ) ) ) {
        s.add( e.getKey() );
      }
    }
    for ( String k : antes.keySet() ) {
      if ( !despues.containsKey( k ) ) {
        s.add( k );
      }
    }
    return s;
  }

  /**
   * Si una prueba esta afectada por los cambios: alguno es uno de sus steps capturados o un antecesor (por saltos
   * habilitados de la version nueva, sin pasar de los steps con dataset). cambiados null = no se sabe: true.
   */
  static boolean afectada( DefPrueba d, TransMeta tm, Set<String> cambiados ) {
    if ( cambiados == null || cambiados.contains( GLOBAL ) || !d.tablas.isEmpty() || d.todaLaTransformacion ) {
      return true;
    }
    if ( cambiados.isEmpty() ) {
      return false;
    }
    Set<String> cambiadosMin = new java.util.HashSet<String>();
    for ( String c : cambiados ) {
      cambiadosMin.add( c.toLowerCase( Locale.ROOT ) );
    }
    Set<String> conDataset = new java.util.HashSet<String>();
    for ( DefPrueba.Dataset ds : d.datasets ) {
      conDataset.add( ds.step == null ? "" : ds.step.trim().toLowerCase( Locale.ROOT ) );
    }
    Set<String> vistos = new LinkedHashSet<String>();
    List<String> pendientes = new ArrayList<String>();
    for ( DefPrueba.Salida s : d.salidas ) {
      pendientes.add( s.step == null ? "" : s.step.trim().toLowerCase( Locale.ROOT ) );
    }
    for ( DefPrueba.Ajuste a : d.ajustes ) {
      if ( a.deStep() && a.step != null && cambiadosMin.contains( a.step.trim().toLowerCase( Locale.ROOT ) ) ) {
        return true; // un step ajustado cambio (o se quito): la prueba lo nombra
      }
    }
    while ( !pendientes.isEmpty() ) {
      String n = pendientes.remove( pendientes.size() - 1 );
      if ( !vistos.add( n ) ) {
        continue;
      }
      if ( cambiadosMin.contains( n ) ) {
        return true;
      }
      if ( conDataset.contains( n ) ) {
        continue; // lo de antes de un dataset no llega
      }
      for ( TransHopMeta h : tm.getTransHops() ) {
        if ( h.isEnabled() && h.getToStep() != null && h.getFromStep() != null && h.getToStep().getName().trim()
          .toLowerCase( Locale.ROOT ).equals( n ) ) {
          pendientes.add( h.getFromStep().getName().trim().toLowerCase( Locale.ROOT ) );
        }
      }
    }
    return false;
  }

  private static String hash( String s ) {
    try {
      byte[] b = MessageDigest.getInstance( "SHA-1" ).digest( s.getBytes( StandardCharsets.UTF_8 ) );
      StringBuilder x = new StringBuilder();
      for ( int i = 0; i < 10; i++ ) {
        x.append( String.format( "%02x", b[ i ] ) );
      }
      return x.toString();
    } catch ( Exception e ) {
      return String.valueOf( s.hashCode() );
    }
  }
}
