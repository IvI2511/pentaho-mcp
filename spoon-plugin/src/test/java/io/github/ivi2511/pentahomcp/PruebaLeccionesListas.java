package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepInterface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Modo clase, WP7: las lecciones listas de la skill ({@code claude/skills/pentaho-etl/lecciones/*.json}, L1-L3 en
 * es/en/pt) y la lección de la demo ({@code ejemplo/leccion/*.json}) se reproducen sin Spoon de punta a punta con
 * {@link LeccionMotor.SinSpoon}; el .ktr que queda se guarda junto a {@code datos/}, se relee y se ejecuta, y su
 * salida tiene que dar los números de §11 del diseño (y los 60 duplicados de la demo).
 *
 * @dueño clase
 */
public class PruebaLeccionesListas {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** claude/skills (está en el classpath de las pruebas: ahí está el catálogo pentaho-etl/). */
  private static File skills() throws Exception {
    URL u = PruebaLeccionesListas.class.getClassLoader().getResource( "pentaho-etl/SKILL.md" );
    noNulo( u, "pentaho-etl/SKILL.md en el classpath (claude/skills)" );
    return new File( u.toURI() ).getParentFile().getParentFile();
  }

  private static List<File> guiones( File dir, String prefijo ) {
    List<File> r = new ArrayList<File>();
    File[] fs = dir.listFiles();
    if ( fs != null ) {
      Arrays.sort( fs );
      for ( File f : fs ) {
        if ( f.getName().startsWith( prefijo ) && f.getName().endsWith( ".json" ) ) {
          r.add( f );
        }
      }
    }
    return r;
  }

  /** Reproduce el guion hasta el final, guarda, relee y ejecuta. Devuelve la transformación ya terminada. */
  private static Trans reproducir( File guion, File ktr ) throws Exception {
    EntornoKettle.iniciar();
    LeccionMotor.olvidarTodas();
    LeccionMotor.SinSpoon ent = new LeccionMotor.SinSpoon( 0 );
    ObjectNode g = (ObjectNode) JSON.readTree( new String( Files.readAllBytes( guion.toPath() ),
      StandardCharsets.UTF_8 ) );
    g.put( "pace", "manual" );
    TransMeta tm = new TransMeta();
    tm.setName( ktr.getName().replace( ".ktr", "" ) );
    tm.setFilename( ktr.getAbsolutePath() );
    tm.setInternalKettleVariables();
    String n = guion.getName();
    Leccion l = LeccionMotor.empezar( ent, g, tm );
    int pasos = g.path( "steps" ).size();
    igual( pasos, LeccionMotor.inicioJson( l ).path( "beats" ).asInt(), n + ": pasos" );
    cierto( pasos <= 8, n + ": como mucho 8 pasos (" + pasos + ")" );
    int pedidas = 0;
    int vistas = 0;
    for ( JsonNode p : g.path( "steps" ) ) {
      pedidas += p.path( "preview_rows" ).asInt( 0 ) > 0 ? 1 : 0;
    }
    String estado = "";
    for ( int k = 1; k <= pasos; k++ ) {
      ObjectNode r = LeccionMotor.avanzar( ent, l, false );
      igual( k, r.path( "position" ).asInt(), n + ": paso " + k + " " + r );
      estado = r.path( "state" ).asText();
      if ( r.has( "preview" ) && r.path( "preview" ).path( "rows" ).size() > 0 ) {
        vistas++;
      }
      cierto( !r.path( "done" ).toString().contains( "failed" ), n + ": paso " + k + " sin fallas: " + r.path( "done" ) );
    }
    igual( "done", estado, n + ": termina" );
    igual( pedidas, vistas, n + ": cada vista previa pedida trae filas" );
    igual( pasos, tm.nrSteps(), n + ": un step por paso" );
    igual( tm.nrNotes(), LeccionNotas.reconocer( tm ).size(), n + ": todas las notas son de la lección" );
    cierto( tm.nrNotes() >= pasos + 1, n + ": título + una nota por paso (" + tm.nrNotes() + ")" );
    LeccionMotor.olvidarTodas();

    // guardar, releer y ejecutar como Pan
    Files.write( ktr.toPath(), tm.getXML().getBytes( StandardCharsets.UTF_8 ) );
    TransMeta otra = new TransMeta( ktr.getAbsolutePath() );
    igual( tm.nrNotes(), LeccionNotas.reconocer( otra ).size(), n + ": releído, notas reconocidas" );
    Trans t = new Trans( otra );
    t.execute( null );
    t.waitUntilFinished();
    igual( 0, t.getErrors(), n + ": la transformación termina sin errores" );
    return t;
  }

  /** Copia datos/ de los ejemplos (o un archivo suelto) a una carpeta nueva para la lección. */
  private static File carpeta( String nombre ) throws Exception {
    File dir = EntornoKettle.salida( "lecciones-listas/" + nombre );
    if ( dir.exists() ) {
      EntornoKettle.borrar( dir.toPath() );
    }
    new File( dir, "datos" ).mkdirs();
    return dir;
  }

  private static List<String> filas( File dir, String prefijo ) throws Exception {
    File s = new File( dir, "salida" );
    File[] fs = s.listFiles();
    noNulo( fs, "salida/ en " + dir );
    for ( File f : fs ) {
      if ( f.getName().contains( prefijo ) ) {
        List<String> ls = new ArrayList<String>( Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 ) );
        ls.remove( 0 ); // encabezado
        return ls;
      }
    }
    throw new AssertionError( "no salida/" + prefijo + "* en " + dir );
  }

  private static String col0( String linea ) {
    String c = linea.split( ";" )[0];
    return c.replace( "\"", "" ).trim();
  }

  // ---------------------------------------------------------------- L1-L3

  public void pruebaLeccionesDeLaSkill() throws Exception {
    File lecciones = new File( new File( skills(), "pentaho-etl" ), "lecciones" );
    File datos = new File( EntornoKettle.ejemplos(), "datos" );
    String[][] casos = { { "L1-primer-etl", "clientes_filtrados" }, { "L2-agregar", "ventas_por_producto" },
      { "L3-unir-fuentes.a-lookup", "ventas_con_cliente" }, { "L3-unir-fuentes.b-join", "clientes_con_ventas" } };
    int total = 0;
    for ( String[] c : casos ) {
      List<File> gs = guiones( lecciones, c[0] + "." );
      igual( 3, gs.size(), c[0] + ": es, en y pt" );
      for ( File g : gs ) {
        String id = g.getName().replace( ".json", "" );
        File dir = carpeta( id );
        for ( File d : datos.listFiles() ) {
          Files.copy( d.toPath(), new File( new File( dir, "datos" ), d.getName() ).toPath(),
            StandardCopyOption.REPLACE_EXISTING );
        }
        reproducir( g, new File( dir, id + ".ktr" ) );
        List<String> fs = filas( dir, c[1] );
        String todo = String.join( "\n", fs );
        if ( c[0].startsWith( "L1" ) ) {
          igual( 4, fs.size(), id + ": 4 clientes filtrados: " + fs );
          List<String> ids = new ArrayList<String>();
          for ( String f : fs ) {
            ids.add( col0( f ) );
          }
          igual( "[1, 5, 7, 8]", ids.toString(), id + ": ids 1, 5, 7 y 8" );
        } else if ( c[0].startsWith( "L2" ) ) {
          igual( 4, fs.size(), id + ": 4 productos: " + fs );
          for ( String x : new String[] { "Café", "Medialuna", "Tostado", "Té" } ) {
            contiene( todo, x, id + ": producto " + x );
          }
          for ( String x : new String[] { "20", "21.6", "9.5", "14.4" } ) {
            contiene( todo.replace( ',', '.' ), x, id + ": total " + x + " en " + fs );
          }
        } else if ( c[0].startsWith( "L3-unir-fuentes.a" ) ) {
          igual( 10, fs.size(), id + ": 10 ventas" );
          String v108 = null;
          for ( String f : fs ) {
            v108 = f.startsWith( "108" ) || f.startsWith( "\"108" ) ? f : v108;
          }
          noNulo( v108, id + ": venta 108" );
          contiene( v108, "DESCONOCIDO", id + ": la 108 sin cliente" );
        } else {
          igual( 10, fs.size(), id + ": 10 filas del LEFT OUTER" );
          contiene( todo, "Fabián", id + ": Fabián sin ventas" );
          cierto( !fs.get( 0 ).contains( "cliente_id_1" ), id + ": sin la clave duplicada" );
        }
        total++;
      }
    }
    igual( 12, total, "12 guiones" );
  }

  // ---------------------------------------------------------------- demo

  public void pruebaLeccionDeLaDemo() throws Exception {
    File repo = skills().getParentFile().getParentFile();
    File demo = new File( repo, "ejemplo" );
    List<File> gs = guiones( new File( demo, "leccion" ), "leccion-" );
    igual( 1, gs.size(), "una lección de demo" );
    File dir = carpeta( "demo" );
    Files.copy( new File( new File( demo, "datos" ), "ventas_2026_09.csv" ).toPath(),
      new File( new File( dir, "datos" ), "ventas_2026_09.csv" ).toPath(), StandardCopyOption.REPLACE_EXISTING );
    File ktrDir = new File( dir, "leccion" );
    ktrDir.mkdirs();
    Trans t = reproducir( gs.get( 0 ), new File( ktrDir, "duplicados_y_rechazos.ktr" ) );
    List<String> rech = filas( dir, "rechazos_duplicados" );
    igual( 60, rech.size(), "60 duplicados" );
    for ( String r : rech ) {
      cierto( r.trim().endsWith( "DUPLICADO" ) || r.trim().endsWith( "DUPLICADO\"" ), "motivo DUPLICADO: " + r );
    }
    StepInterface unicas = t.findRunThread( "Líneas únicas" );
    noNulo( unicas, "Líneas únicas" );
    igual( 2940L, unicas.getLinesRead(), "2940 líneas únicas (3000 - 60)" );
  }
}
