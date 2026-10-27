package io.github.ivi2511.pentahomcp.traductor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Las bibliotecas que necesita el código generado: el requirements.txt que se deja al lado del script o del notebook
 * y la comprobación que el código hace antes de importar nada (si falta una, o la versión no alcanza, se corta con un
 * mensaje claro y el comando para instalarla, en vez de un ModuleNotFoundError a mitad de camino).
 */
public final class Requisitos {

  public static final String ARCHIVO = "requirements.txt";

  /** Paquete de pip → módulo que se importa (cuando no se llaman igual). */
  private static final Map<String, String> MODULO = new LinkedHashMap<>();

  static {
    MODULO.put( "psycopg2-binary", "psycopg2" );
    MODULO.put( "scikit-learn", "sklearn" );
    MODULO.put( "pyyaml", "yaml" );
    MODULO.put( "python-dateutil", "dateutil" );
    MODULO.put( "beautifulsoup4", "bs4" );
    MODULO.put( "pillow", "PIL" );
  }

  private Requisitos() {
  }

  /** Las bibliotecas de una transformación (requisitos de pip, sin comillas: «sqlalchemy&gt;=2.0.36»). */
  public static List<String> de( Programa p, Opciones o ) {
    return EscritorPy.paquetes( p, o );
  }

  /** Las de un job: las de su contexto (entradas, conexiones) más las de cada transformación que lleva dentro. */
  public static List<String> de( ProgramaJob p, Opciones o ) {
    if ( o == null ) {
      o = new Opciones();
      o.destino = p.ctx.destino;
      o.idioma = p.ctx.idioma;
    }
    Set<String> r = new LinkedHashSet<>();
    Programa propio = new Programa( p.nombre, p.archivo, p.ctx );
    r.addAll( EscritorPy.paquetes( propio, o ) );
    for ( Programa t : p.transformaciones() ) {
      r.addAll( EscritorPy.paquetes( t, o ) );
    }
    return unicos( new ArrayList<>( r ) );
  }

  /** Nombre del paquete sin versión ni extras («sqlalchemy&gt;=2.0.36» → «sqlalchemy»). */
  public static String nombre( String req ) {
    return req.replaceAll( "[\\[<>=!~ ;].*$", "" ).trim();
  }

  /** El módulo que se importa para ese requisito. */
  public static String modulo( String req ) {
    String n = nombre( req );
    String m = MODULO.get( n.toLowerCase( Locale.ROOT ) );
    return m != null ? m : n.replace( '-', '_' );
  }

  /** Un requisito por paquete (el último gana: el que lleva versión mínima reemplaza al que no). */
  static List<String> unicos( List<String> reqs ) {
    Map<String, String> m = new LinkedHashMap<>();
    for ( String r : reqs ) {
      String k = nombre( r ).toLowerCase( Locale.ROOT );
      String antes = m.get( k );
      if ( antes == null || !antes.contains( ">=" ) || r.contains( ">=" ) ) {
        m.put( k, r );
      }
    }
    return new ArrayList<>( m.values() );
  }

  /** El comando pip con cada requisito entre comillas si lleva versión (para que la shell no lea «&gt;»). */
  public static String pip( List<String> reqs ) {
    StringBuilder b = new StringBuilder( "pip install" );
    for ( String r : reqs ) {
      b.append( ' ' ).append( r.matches( ".*[<>=!~].*" ) ? "\"" + r + "\"" : r );
    }
    return b.toString();
  }

  /**
   * Las líneas de Python que comprueban las bibliotecas antes de los imports: si falta alguna, o tiene una versión
   * menor que la pedida, termina con un mensaje que dice cuáles y cómo instalarlas; con INSTALAR = True las instala
   * antes con pip (el Python que corre el código: en un notebook, el del kernel) y vuelve a comprobar.
   */
  public static List<String> comprobacion( List<String> reqs, String idioma ) {
    List<String> r = new ArrayList<>();
    r.add( "# " + EscritorPy.t( idioma, "Antes de nada: ¿están instaladas las bibliotecas que usa este código? (las "
      + "mismas que " + ARCHIVO + ")", "First of all: are the libraries this code uses installed? (the same as in "
        + ARCHIVO + ")", "Antes de tudo: as bibliotecas que este código usa estão instaladas? (as mesmas do " + ARCHIVO
          + ")" ) );
    r.add( "def _comprobar_bibliotecas(requisitos, instalar=False):" );
    r.add( "    import importlib, importlib.util, importlib.metadata, re, subprocess, sys, warnings" );
    r.add( "" );
    r.add( "    def faltantes():" );
    r.add( "        importlib.invalidate_caches()" );
    r.add( "        faltan = []" );
    r.add( "        for req, modulo in requisitos:" );
    r.add( "            if importlib.util.find_spec(modulo) is None:" );
    r.add( "                faltan.append(req)" );
    r.add( "                continue" );
    r.add( "            minima = re.match(r\"([A-Za-z0-9_.-]+)\\s*>=\\s*([0-9.]+)\", req)" );
    r.add( "            if minima:" );
    r.add( "                try:" );
    r.add( "                    tiene = tuple(int(x) for x in re.findall(r\"\\d+\", importlib.metadata.version("
      + "minima.group(1)))[:3])" );
    r.add( "                except Exception:" );
    r.add( "                    continue" );
    r.add( "                if tiene < tuple(int(x) for x in minima.group(2).split(\".\")):" );
    r.add( "                    faltan.append(req)" );
    r.add( "        return faltan" );
    r.add( "" );
    r.add( "    faltan = faltantes()" );
    r.add( "    if faltan and instalar:" );
    r.add( "        print(" + CtxTrad.lit( EscritorPy.t( idioma, "Instalando: ", "Installing: ", "Instalando: " ) )
      + " + \", \".join(faltan))" );
    r.add( "        # " + EscritorPy.t( idioma, "con el mismo Python que corre este código (en un notebook, el del kernel)",
      "with the same Python that runs this code (in a notebook, the kernel's)",
      "com o mesmo Python que roda este código (num notebook, o do kernel)" ) );
    r.add( "        subprocess.run([sys.executable, \"-m\", \"pip\", \"install\", *faltan])" );
    r.add( "        faltan = faltantes()" );
    r.add( "        if not faltan:" );
    r.add( "            print(" + CtxTrad.lit( EscritorPy.t( idioma, "Listo: ya están todas.", "Done: they are all "
      + "there now.", "Pronto: já estão todas." ) ) + ")" );
    r.add( "    if faltan:" );
    r.add( "        comando = \"pip install \" + \" \".join('\"%s\"' % f if \">\" in f else f for f in faltan)" );
    r.add( "        mensaje = (" + CtxTrad.lit( EscritorPy.t( idioma,
      "Faltan bibliotecas de Python (o su versión es antigua): ", "Missing Python libraries (or too old): ",
      "Faltam bibliotecas do Python (ou a versão é antiga): " ) ) + " + \", \".join(faltan) + \"\\n\" + " + CtxTrad.lit(
        EscritorPy.t( idioma, "Instálalas con: ", "Install them with: ", "Instale-as com: " ) ) + " + comando + " + CtxTrad
          .lit( EscritorPy.t( idioma, "  (o pip install -r " + ARCHIVO + "). ", "  (or pip install -r " + ARCHIVO
            + "). ", "  (ou pip install -r " + ARCHIVO + "). " ) ) + " + (" + CtxTrad.lit( EscritorPy.t( idioma,
              "No se pudieron instalar: ¿hay internet (o un repositorio local de paquetes)?", "They could not be "
                + "installed: is there internet (or a local package repository)?", "Não foi possível instalá-las: há "
                  + "internet (ou um repositório local de pacotes)?" ) ) + " if instalar else " + CtxTrad.lit(
                    EscritorPy.t( idioma, "O pon INSTALAR = True y vuelve a ejecutar (hace falta internet).",
                      "Or set INSTALAR = True and run again (it needs internet).",
                      "Ou ponha INSTALAR = True e execute de novo (é preciso internet)." ) ) + "))" );
    r.add( "        # " + EscritorPy.t( idioma, "en Jupyter, sin el aviso «To exit: use 'exit'...»",
      "in Jupyter, without the 'To exit: use 'exit'...' warning", "no Jupyter, sem o aviso «To exit: use 'exit'...»" ) );
    r.add( "        warnings.filterwarnings(\"ignore\", message=\"To exit\")" );
    r.add( "        raise SystemExit(mensaje)" );
    r.add( "" );
    r.add( "" );
    r.add( "INSTALAR = False  # " + EscritorPy.t( idioma, "pon True para que instale lo que falte (hace falta internet)",
      "set it to True to install what is missing (it needs internet)",
      "ponha True para instalar o que faltar (é preciso internet)" ) );
    StringBuilder lista = new StringBuilder( "_comprobar_bibliotecas([" );
    for ( int i = 0; i < reqs.size(); i++ ) {
      lista.append( i == 0 ? "" : ", " ).append( '(' ).append( CtxTrad.lit( reqs.get( i ) ) ).append( ", " ).append(
        CtxTrad.lit( modulo( reqs.get( i ) ) ) ).append( ')' );
    }
    r.add( lista.append( "], instalar=INSTALAR)" ).toString() );
    return r;
  }

  /** El contenido de requirements.txt. */
  public static String texto( List<String> reqs ) {
    StringBuilder b = new StringBuilder();
    for ( String r : reqs ) {
      b.append( r ).append( '\n' );
    }
    return b.toString();
  }

  /**
   * Escribe (o completa) el requirements.txt de la carpeta: conserva lo que ya tenía y agrega o actualiza los
   * paquetes de este programa, así varias traducciones en la misma carpeta comparten uno solo.
   */
  public static File escribir( File carpeta, List<String> reqs ) throws IOException {
    File f = new File( carpeta, ARCHIVO );
    List<String> lineas = new ArrayList<>();
    if ( f.isFile() ) {
      lineas.addAll( Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 ) );
    }
    for ( String r : reqs ) {
      String k = nombre( r ).toLowerCase( Locale.ROOT );
      boolean puesto = false;
      for ( int i = 0; i < lineas.size(); i++ ) {
        String l = lineas.get( i ).trim();
        if ( !l.isEmpty() && !l.startsWith( "#" ) && nombre( l ).toLowerCase( Locale.ROOT ).equals( k ) ) {
          if ( r.contains( ">=" ) && !l.contains( ">=" ) ) {
            lineas.set( i, r );
          }
          puesto = true;
        }
      }
      if ( !puesto ) {
        lineas.add( r );
      }
    }
    StringBuilder b = new StringBuilder();
    for ( String l : lineas ) {
      b.append( l ).append( '\n' );
    }
    Files.write( f.toPath(), b.toString().getBytes( StandardCharsets.UTF_8 ) );
    return f;
  }
}
