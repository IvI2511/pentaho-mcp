package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.Props;
import org.pentaho.di.core.gui.AreaOwner;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.gui.PrimitiveGCInterface.EFont;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.TransPainter;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.core.PropsUI;
import org.pentaho.di.ui.spoon.SWTGC;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.LeccionLayout.Banda;
import io.github.ivi2511.pentahomcp.LeccionLayout.Densidad;
import io.github.ivi2511.pentahomcp.LeccionLayout.Medidor;
import io.github.ivi2511.pentahomcp.LeccionLayout.Plan;
import io.github.ivi2511.pentahomcp.LeccionLayout.Previas;
import io.github.ivi2511.pentahomcp.LeccionLayout.Tipo;
import io.github.ivi2511.pentahomcp.LeccionLayout.Ubicada;

/**
 * Pruebas headless del modo clase (WP8, docs/modo-clase-diseno.md §4.3, §4.4, §4.5 y §13). No van al jar: se
 * compilan contra un jar del plugin y corren con KettleEnvironment.init + un Display suelto + PropsUI.init, sin Spoon.
 * <ol>
 * <li><b>Lote</b>: a cada .ktr de las carpetas dadas le arma una leccion (titulo + una nota de paso por step con el
 * que/por que del glosario de clase + algunos ¡Ojo!), la ubica con {@link LeccionLayout} (como lesson_start, notas
 * previas apiladas), crea las notas con el estilo y el registro de {@link LeccionNotas}, pinta con el TransPainter real
 * y mide, sobre las areas que devuelve el painter: choques (nota +4 px contra iconos, nombres, hops y otras notas),
 * distancia nota - icono de su step (legible si &lt;= 300 px), que lo dibujado entre en lo reservado y la estabilidad
 * de un relayout posterior. Tres variantes: iconos de 32 con nota compacta (lo de hoy), iconos de 48, y 32 sin nota
 * en las columnas densas.</li>
 * <li><b>Funcionales</b> sobre copias de los ejemplos de la skill: un Ctrl+Z / Ctrl+Y de toda la leccion, guardar y
 * releer, Ctrl+Z de un relayout (CHANGE_NOTE repone clones), desfase con el registro (nota editada, step
 * renombrado), quitar y deshacer, ocultar / guardar / releer / mostrar y mostrar tras un Ctrl+Z del ocultar. Deja
 * {@code *_clase.ktr} y {@code *_oculta.ktr} para que correr.ps1 los pase por Pan.</li>
 * </ol>
 * Propiedades: clase.salida (carpeta de resultados), clase.lote (carpetas con .ktr separadas por ';'),
 * clase.ejemplos (copia de manual/ejemplos donde se escriben las copias anotadas). Codigo de salida: 0 si no hay
 * choques ni fallas funcionales; 1 si las hay. La metrica de densidad (distancia) se informa aparte.
 */
public final class PruebasClase {

  static final String IDIOMA = "es";
  static final int AIRE = LeccionLayout.AIRE;
  static final int LEGIBLE = LeccionLayout.DISTANCIA_LEGIBLE;

  static int fallas = 0;
  static final List<String> textoFallas = new ArrayList<String>();
  static McpResources recursos;

  private PruebasClase() {
  }

  // ---------------------------------------------------------------- arranque

  public static void main( String[] a ) throws Exception {
    File salida = new File( System.getProperty( "clase.salida" ) );
    File png = new File( salida, "png" );
    png.mkdirs();
    KettleEnvironment.init();
    Display display = new Display();
    PropsUI.init( display, Props.TYPE_PROPERTIES_SPOON );
    recursos = new McpResources();
    FontData fn = PropsUI.getInstance().getNoteFont();
    System.out.println( "[entorno] fuente de notas " + fn.getName() + " " + fn.getHeight() + " pt; icono de Spoon "
      + PropsUI.getInstance().getIconSize() + "; glosario " + recursos.idsGlosario().size() + " steps" );

    List<File> ktrs = new ArrayList<File>();
    for ( String d : System.getProperty( "clase.lote", "" ).split( ";" ) ) {
      if ( !d.trim().isEmpty() ) {
        buscar( new File( d.trim() ), ktrs );
      }
    }
    try ( PrintWriter tsv = new PrintWriter( new OutputStreamWriter( new FileOutputStream( new File( salida,
      "lote.tsv" ) ), StandardCharsets.UTF_8 ) ) ) {
      tsv.println( "variante\tarchivo\tsteps\tcolumnas\tnotas_lienzo\tcompactas\tfuera\tlaterales\tchoques"
        + "\tchoques_usuario\tdist_max\tlejos\tfuera_de_reserva\trelayout_steps\trelayout_notas\trelayout_choques\terror" );
      Variante[] vs = { new Variante( "icon32_compacta", 32, Densidad.COMPACTA, true ),
        new Variante( "icon48_compacta", 48, Densidad.COMPACTA, false ),
        new Variante( "icon32_sin_nota", 32, Densidad.SIN_NOTA, false ),
        new Variante( "icon32_acercar", 32, Densidad.COMPACTA, false ).acercando(),
        new Variante( "icon48_acercar", 48, Densidad.COMPACTA, false ).acercando() };
      String solo = System.getProperty( "clase.variantes", "" ).trim();
      for ( Variante v : vs ) {
        if ( solo.isEmpty() || ( "," + solo + "," ).contains( "," + v.nombre + "," ) ) {
          lote( display, ktrs, v, tsv, png );
        }
      }
    }

    String ej = System.getProperty( "clase.ejemplos" );
    if ( ej != null && !ej.isEmpty() ) {
      File[] fs = new File( ej ).listFiles();
      List<File> l = new ArrayList<File>();
      if ( fs != null ) {
        for ( File f : fs ) {
          String n = f.getName().toLowerCase( Locale.ROOT );
          if ( n.endsWith( ".ktr" ) && !n.endsWith( "_clase.ktr" ) && !n.endsWith( "_oculta.ktr" ) ) {
            l.add( f );
          }
        }
      }
      Collections.sort( l );
      for ( File f : l ) {
        funcionales( display, f, png );
      }
    }

    display.dispose();
    System.out.println();
    System.out.println( "[resultado] fallas=" + fallas );
    for ( String t : textoFallas ) {
      System.out.println( "  FALLA " + t );
    }
    System.exit( fallas == 0 ? 0 : 1 );
  }

  static final class Variante {
    final String nombre;
    final int icono;
    final Densidad densidad;
    final boolean relayout;
    /** Simula la regla propuesta: acercar a su step las notas completas que quedarian a mas de 300 px. */
    boolean acercar;

    Variante( String nombre, int icono, Densidad densidad, boolean relayout ) {
      this.nombre = nombre;
      this.icono = icono;
      this.densidad = densidad;
      this.relayout = relayout;
    }

    Variante acercando() {
      acercar = true;
      return this;
    }
  }

  // ---------------------------------------------------------------- A. lote

  static void lote( Display display, List<File> ktrs, Variante v, PrintWriter tsv, File png ) {
    int anotados = 0;
    int sinSteps = 0;
    int noCargan = 0;
    int rechazados = 0;
    int notasTot = 0;
    int compactas = 0;
    int fuera = 0;
    int laterales = 0;
    int choques = 0;
    int choquesUsu = 0;
    int lejos = 0;
    int archivosLejos = 0;
    int distMax = 0;
    String peorDist = null;
    int fueraReserva = 0;
    int relSteps = 0;
    int relNotas = 0;
    int relChoques = 0;
    int relArchivos = 0;
    int[] hist = new int[ 5 ]; // 0, 1-100, 101-200, 201-300, >300 (notas completas)
    int acercadas = 0;
    int maxSteps = -1;
    File grande = null;
    String peorChoque = null;
    for ( File f : ktrs ) {
      String nombre = relativo( f );
      TransMeta tm;
      try {
        tm = new TransMeta( f.getPath() );
      } catch ( Throwable t ) {
        noCargan++;
        System.out.println( "  [" + v.nombre + "] NO CARGA " + nombre + ": " + primera( t ) );
        tsv.println( v.nombre + "\t" + nombre + "\t\t\t\t\t\t\t\t\t\t\t\t\t\t\tno carga: " + primera( t ) );
        continue;
      }
      if ( tm.nrSteps() == 0 ) {
        sinSteps++;
        continue;
      }
      LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( -1 );
      try {
        Map<NotePadMeta, Ubicada> creadas = new IdentityHashMap<NotePadMeta, Ubicada>();
        Plan p;
        try {
          p = anotar( tm, m, v.icono, v.densidad, null, creadas, v.acercar );
          acercadas += ultimasAcercadas;
        } catch ( IllegalArgumentException rechazo ) {
          rechazados++;
          tsv.println( v.nombre + "\t" + nombre + "\t" + tm.nrSteps() + "\t\t\t\t\t\t\t\t\t\t\t\t\t\trechazado: "
            + rechazo.getMessage() );
          continue;
        }
        anotados++;
        boolean esGrande = tm.nrSteps() > maxSteps;
        if ( esGrande ) {
          maxSteps = tm.nrSteps();
          grande = f;
        }
        String base = f.getName().replace( ".ktr", "" );
        File archivoPng = null;
        if ( f.getPath().replace( '\\', '/' ).contains( "/ejemplos/" ) || base.startsWith( "Stream Lookup" ) ) {
          archivoPng = new File( png, v.nombre + "_" + limpio( base ) + ".png" );
        }
        Medida md = pintar( display, tm, v.icono, archivoPng );
        Conteo c = contar( tm, md, creadas, nombre, v.nombre );
        notasTot += c.notas;
        compactas += c.compactas;
        int f0 = 0;
        int lat = 0;
        for ( Ubicada u : p.notas ) {
          if ( u.banda == Banda.FUERA ) {
            f0++;
          } else if ( u.banda == Banda.LATERAL ) {
            lat++;
          }
        }
        fuera += f0;
        laterales += lat;
        choques += c.choques;
        choquesUsu += c.choquesUsuario;
        lejos += c.lejos;
        fueraReserva += c.fueraDeReserva;
        if ( c.lejos > 0 ) {
          archivosLejos++;
        }
        for ( int d : c.distanciasCompletas ) {
          hist[ d == 0 ? 0 : d <= 100 ? 1 : d <= 200 ? 2 : d <= LEGIBLE ? 3 : 4 ]++;
        }
        if ( c.distMax > distMax ) {
          distMax = c.distMax;
          peorDist = nombre;
        }
        if ( c.choques > 0 && peorChoque == null ) {
          peorChoque = nombre;
        }
        int rs = -1;
        int rn = -1;
        int rc = -1;
        if ( v.relayout ) {
          // lesson_notes relayout despues de la leccion: las previas quedan donde estan (keep)
          Map<NotePadMeta, Point> antes = new IdentityHashMap<NotePadMeta, Point>();
          for ( int i = 0; i < tm.nrNotes(); i++ ) {
            NotePadMeta n = tm.getNote( i );
            antes.put( n, new Point( n.getLocation().x, n.getLocation().y ) );
          }
          LeccionNotas.Resultado r = LeccionNotas.reacomodar( tm, m, null, -1 );
          rs = r.stepsMovidos;
          rn = 0;
          for ( int i = 0; i < tm.nrNotes(); i++ ) {
            NotePadMeta n = tm.getNote( i );
            Point a = antes.get( n );
            if ( a == null || a.x != n.getLocation().x || a.y != n.getLocation().y ) {
              rn++;
            }
          }
          Map<NotePadMeta, Ubicada> todas = new IdentityHashMap<NotePadMeta, Ubicada>();
          for ( NotePadMeta n : LeccionNotas.reconocer( tm ).keySet() ) {
            todas.put( n, creadas.get( n ) );
          }
          Conteo c2 = contar( tm, pintar( display, tm, v.icono, null ), todas, nombre, v.nombre + "/relayout" );
          rc = c2.choques;
          relSteps += rs;
          relNotas += rn;
          relChoques += rc;
          if ( rs > 0 || rn > 0 ) {
            relArchivos++;
          }
        }
        tsv.println( v.nombre + "\t" + nombre + "\t" + tm.nrSteps() + "\t" + p.columnas + "\t" + c.notas + "\t"
          + c.compactas + "\t" + f0 + "\t" + lat + "\t" + c.choques + "\t" + c.choquesUsuario + "\t" + c.distMax + "\t"
          + c.lejos + "\t" + c.fueraDeReserva + "\t" + ( rs < 0 ? "" : rs ) + "\t" + ( rn < 0 ? "" : rn ) + "\t"
          + ( rc < 0 ? "" : rc ) + "\t" );
      } catch ( Throwable t ) {
        fallar( v.nombre + " " + nombre + ": excepcion " + t );
        t.printStackTrace( System.out );
      } finally {
        m.cerrar();
      }
    }
    if ( grande != null ) {
      try {
        TransMeta tm = new TransMeta( grande.getPath() );
        LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( -1 );
        try {
          anotar( tm, m, v.icono, v.densidad, null, new IdentityHashMap<NotePadMeta, Ubicada>(), v.acercar );
        } finally {
          m.cerrar();
        }
        pintar( display, tm, v.icono, new File( png, v.nombre + "_mas_grande.png" ) );
      } catch ( Throwable t ) {
        System.out.println( "  (sin png del mas grande: " + primera( t ) + ")" );
      }
    }
    System.out.println( "[lote " + v.nombre + "] ktr=" + ktrs.size() + " anotados=" + anotados + " sin_steps=" + sinSteps
      + " no_cargan=" + noCargan + " rechazados=" + rechazados );
    System.out.println( "  notas en el lienzo=" + notasTot + " (compactas=" + compactas + ") fuera_del_lienzo=" + fuera
      + " laterales=" + laterales + ( v.acercar ? " acercadas_a_su_step=" + acercadas : "" ) );
    System.out.println( "  choques de notas de la leccion=" + choques + ( peorChoque == null ? "" : " (primero: "
      + peorChoque + ")" ) + "; choques de notas previas=" + choquesUsu + "; dibujado fuera de lo reservado="
      + fueraReserva );
    System.out.println( "  distancia nota-step: max=" + distMax + " px (" + peorDist + "); notas a mas de " + LEGIBLE
      + " px=" + lejos + " en " + archivosLejos + " archivos" );
    System.out.println( "  notas completas por distancia: 0 px=" + hist[ 0 ] + ", 1-100=" + hist[ 1 ] + ", 101-200="
      + hist[ 2 ] + ", 201-300=" + hist[ 3 ] + ", >300=" + hist[ 4 ] );
    System.out.println( "  mas grande: " + ( grande == null ? "-" : relativo( grande ) ) + " (" + maxSteps + " steps)" );
    if ( v.relayout ) {
      System.out.println( "  relayout despues de la leccion: archivos que cambian=" + relArchivos + " steps movidos="
        + relSteps + " notas movidas=" + relNotas + " choques despues=" + relChoques );
    }
    if ( choques > 0 ) {
      fallar( v.nombre + ": " + choques + " choques de notas de la leccion (ver CHOQUE arriba)" );
    }
    if ( fueraReserva > 0 ) {
      fallar( v.nombre + ": " + fueraReserva + " notas dibujadas fuera del rectangulo reservado" );
    }
  }

  // ---------------------------------------------------------------- la leccion (como lesson_start)

  /**
   * Arma una leccion sobre los steps del documento, la ubica con la regla (previas apiladas) y la aplica: steps y
   * previas a su lugar, notas nuevas con estilo y registro. Con 'u' registra todo como un Ctrl+Z.
   */
  static Plan anotar( TransMeta tm, Medidor m, int icono, Densidad d, UndoGrupo u, Map<NotePadMeta, Ubicada> creadas ) {
    return anotar( tm, m, icono, d, u, creadas, false );
  }

  /** Notas que movio la ultima llamada con acercar=true. */
  static int ultimasAcercadas;

  static Plan anotar( TransMeta tm, Medidor m, int icono, Densidad d, UndoGrupo u, Map<NotePadMeta, Ubicada> creadas,
    boolean acercar ) {
    LeccionLayout.Entrada e = LeccionLayout.desde( tm, m, null );
    e.icono = icono;
    e.densidad = d;
    e.modoPrevias = Previas.APILAR;
    String nombre = tm.getName() == null || tm.getName().isEmpty() ? "transformación" : tm.getName();
    e.nota( Tipo.TITULO, null, 0, LeccionNotas.etiqueta( "lesson", IDIOMA ) + " · " + nombre + "\nNivel principiante · "
      + tm.nrSteps() + " pasos · " + tm.nrTransHops() + " hops" );
    int k = 0;
    for ( StepMeta s : tm.getSteps() ) {
      k++;
      String[] g = glosario( s.getStepID() );
      e.nota( Tipo.PASO, s.getName(), k, LeccionNotas.textoPaso( IDIOMA, k, s.getName(), tipoDe( s ), g[ 0 ], g[ 1 ] ) );
      if ( k % 3 == 1 ) {
        e.nota( Tipo.OJO, s.getName(), k, LeccionNotas.textoSuelta( Tipo.OJO, IDIOMA, s.getName(), k, g[ 2 ] ) );
      }
      if ( k % 5 == 2 ) {
        e.nota( Tipo.PREGUNTA, s.getName(), k, LeccionNotas.textoSuelta( Tipo.PREGUNTA, IDIOMA, s.getName(), k,
          "¿Cuántas filas salen de este paso? Míralo en la vista previa." ) );
      }
    }
    Plan p = LeccionLayout.calcular( e, m );
    ultimasAcercadas = acercar ? acercar( p, e, m ) : 0;

    // steps (los de la entrada van en el orden de tm.getSteps())
    List<Object> so = new ArrayList<Object>();
    List<Integer> si = new ArrayList<Integer>();
    List<Point> sa = new ArrayList<Point>();
    List<Point> sd = new ArrayList<Point>();
    for ( int i = 0; i < p.steps.size(); i++ ) {
      StepMeta s = tm.getStep( i );
      Point np = p.posiciones[ i ];
      Point a = s.getLocation();
      if ( a != null && a.x == np.x && a.y == np.y ) {
        continue;
      }
      so.add( s );
      si.add( i );
      sa.add( a == null ? new Point( 0, 0 ) : new Point( a.x, a.y ) );
      sd.add( new Point( np.x, np.y ) );
      s.setLocation( np.x, np.y );
    }
    if ( u != null && !so.isEmpty() ) {
      u.posicion( so.toArray(), enteros( si ), sa.toArray( new Point[ 0 ] ), sd.toArray( new Point[ 0 ] ) );
    }
    // notas previas: apiladas arriba a la izquierda
    List<Object> po = new ArrayList<Object>();
    List<Integer> pi = new ArrayList<Integer>();
    List<Point> pa = new ArrayList<Point>();
    List<Point> pd = new ArrayList<Point>();
    for ( int i = 0; i < e.previas.size(); i++ ) {
      NotePadMeta n = (NotePadMeta) e.previas.get( i ).ref;
      Point np = p.previas[ i ];
      Point a = n.getLocation();
      if ( a != null && a.x == np.x && a.y == np.y ) {
        continue;
      }
      po.add( n );
      pi.add( tm.indexOfNote( n ) );
      pa.add( a == null ? new Point( 0, 0 ) : new Point( a.x, a.y ) );
      pd.add( new Point( np.x, np.y ) );
      n.setLocation( np.x, np.y );
    }
    if ( u != null && !po.isEmpty() ) {
      u.posicion( po.toArray(), enteros( pi ), pa.toArray( new Point[ 0 ] ), pd.toArray( new Point[ 0 ] ) );
    }
    // notas de la leccion
    LeccionNotas.asegurarGrupo( tm, IDIOMA );
    List<Object> nuevas = new ArrayList<Object>();
    List<Integer> ni = new ArrayList<Integer>();
    for ( Ubicada ub : p.notas ) {
      if ( !ub.enLienzo() || ub.texto == null ) {
        continue;
      }
      NotePadMeta n = new NotePadMeta( ub.texto, ub.x, ub.y, ub.ancho, ub.alto );
      LeccionNotas.estilizar( n, ub.pedida.tipo, -1 );
      n.setFontBold( ub.negrita );
      tm.addNote( n );
      nuevas.add( n );
      ni.add( tm.indexOfNote( n ) );
      creadas.put( n, ub );
      LeccionNotas.registrar( tm, n, ub.pedida.tipo, ub.pedida.step, ub.pedida.texto );
    }
    if ( u != null && !nuevas.isEmpty() ) {
      u.nuevo( nuevas.toArray(), enteros( ni ) );
    }
    tm.clearCaches();
    return p;
  }

  /**
   * Regla propuesta por WP8 (no esta en LeccionLayout): si las notas completas de un step quedan a mas de 300 px de
   * su icono (bandas globales: la de arriba sobre el step mas alto de todo el flujo, la de abajo bajo el mas bajo),
   * se apilan junto a su step en su columna (abajo: bajo su nombre; arriba: 20 px sobre el icono), si ahi no cortan
   * iconos, nombres, hops, notas previas ni otras notas (+4 px). Si cortan, se quedan en la banda. Devuelve cuantas
   * notas movio.
   */
  static int acercar( Plan p, LeccionLayout.Entrada e, Medidor m ) {
    final int icono = p.icono;
    final int n = p.steps.size();
    List<int[]> rects = new ArrayList<int[]>();
    List<int[]> segs = new ArrayList<int[]>();
    int[] altoNombre = new int[ n ];
    for ( int i = 0; i < n; i++ ) {
      Point pos = p.posiciones[ i ];
      rects.add( new int[] { pos.x, pos.y, icono, icono } );
      Point ext = m.nombre( p.steps.get( i ) );
      altoNombre[ i ] = ext.y;
      rects.add( new int[] { pos.x + icono / 2 - ext.x / 2, pos.y + icono + LeccionLayout.SEP_NOMBRE, ext.x, ext.y } );
    }
    for ( String[] h : e.hops ) {
      Point a = p.posicion( h[ 0 ] );
      Point b = p.posicion( h[ 1 ] );
      if ( a != null && b != null && a != b ) {
        segs.add( new int[] { a.x + icono / 2, a.y + icono / 2, b.x + icono / 2, b.y + icono / 2 } );
      }
    }
    for ( int i = 0; i < e.previas.size(); i++ ) {
      LeccionLayout.Previa pv = e.previas.get( i );
      rects.add( new int[] { p.previas[ i ].x, p.previas[ i ].y, pv.ancho + 2 * LeccionLayout.MARGEN,
        pv.alto + 2 * LeccionLayout.MARGEN } );
    }
    Map<String, List<Ubicada>> porStep = new java.util.LinkedHashMap<String, List<Ubicada>>();
    for ( Ubicada u : p.notas ) {
      if ( u.pedida.step == null || ( u.banda != Banda.ARRIBA && u.banda != Banda.ABAJO ) ) {
        continue;
      }
      String k = u.pedida.step.trim().toLowerCase( Locale.ROOT );
      List<Ubicada> l = porStep.get( k );
      if ( l == null ) {
        porStep.put( k, l = new ArrayList<Ubicada>() );
      }
      if ( u.pedida.tipo == Tipo.PASO ) {
        l.add( 0, u ); // la del paso pegada al step
      } else {
        l.add( u );
      }
    }
    int movidas = 0;
    for ( List<Ubicada> l : porStep.values() ) {
      String step = l.get( 0 ).pedida.step;
      int si = -1;
      for ( int i = 0; i < n; i++ ) {
        if ( p.steps.get( i ).trim().equalsIgnoreCase( step.trim() ) ) {
          si = i;
        }
      }
      if ( si < 0 ) {
        continue;
      }
      Point pos = p.posiciones[ si ];
      int[] ic = new int[] { pos.x, pos.y, icono, icono };
      int peor = 0;
      for ( Ubicada u : l ) {
        peor = Math.max( peor, LeccionLayout.distancia( u.rect(), ic ) );
      }
      if ( peor <= LEGIBLE ) {
        continue;
      }
      boolean abajo = l.get( 0 ).banda == Banda.ABAJO;
      int[][] cand = new int[ l.size() ][];
      int yy = abajo ? pos.y + icono + LeccionLayout.SEP_NOMBRE + altoNombre[ si ] + LeccionLayout.AIRE_NOMBRE
        : pos.y - LeccionLayout.AIRE_FLUJO;
      for ( int k = 0; k < l.size(); k++ ) {
        Ubicada u = l.get( k );
        int h = u.alto + 2 * LeccionLayout.MARGEN;
        if ( abajo ) {
          cand[ k ] = new int[] { u.x, yy, u.ancho + 2 * LeccionLayout.MARGEN, h };
          yy += h + LeccionLayout.SEP;
        } else {
          yy -= h;
          cand[ k ] = new int[] { u.x, yy, u.ancho + 2 * LeccionLayout.MARGEN, h };
          yy -= LeccionLayout.SEP;
        }
      }
      boolean libre = true;
      for ( int k = 0; k < cand.length && libre; k++ ) {
        int[] r = LeccionLayout.inflar( cand[ k ], AIRE );
        for ( int[] o : rects ) {
          libre &= !LeccionLayout.cortan( r, o );
        }
        for ( int[] s : segs ) {
          libre &= !LeccionLayout.segmentoCorta( s, r );
        }
        for ( Ubicada o : p.notas ) {
          if ( o.enLienzo() && !l.contains( o ) ) {
            libre &= !LeccionLayout.cortan( r, o.rect() );
          }
        }
      }
      if ( libre ) {
        for ( int k = 0; k < cand.length; k++ ) {
          l.get( k ).y = cand[ k ][ 1 ];
          movidas++;
        }
      }
    }
    return movidas;
  }

  /** {que, por que, ojo} del glosario de clase (es) para un tipo de step, o textos genericos si no esta. */
  static String[] glosario( String id ) {
    JsonNode g = recursos.glosario( IDIOMA, id );
    if ( g == null ) {
      return new String[] { "procesa cada fila que le llega por el hop y la pasa al siguiente.",
        "es lo que necesita el paso siguiente para seguir con el flujo.",
        "revisa la configuración de este step: es un error silencioso típico." };
    }
    String ojo = null;
    for ( JsonNode w : g.path( "watch_out" ) ) {
      if ( w.hasNonNull( "title" ) ) {
        ojo = w.get( "title" ).asText();
        break;
      }
    }
    return new String[] { g.path( "what" ).asText(), g.path( "why" ).asText(),
      ojo == null ? "revisa los nombres de los campos que espera este step." : ojo };
  }

  static String tipoDe( StepMeta s ) {
    PluginInterface pi = PluginRegistry.getInstance().findPluginWithId( StepPluginType.class, s.getStepID() );
    return pi == null ? s.getStepID() : pi.getName();
  }

  // ---------------------------------------------------------------- pintar con el TransPainter real

  /** Lo que pinto el TransPainter: areas de iconos y notas, nombres (getNamePosition + fuente GRAPH) y hops. */
  static final class Medida {
    final Map<StepMeta, int[]> iconos = new IdentityHashMap<StepMeta, int[]>();
    final Map<NotePadMeta, int[]> notas = new IdentityHashMap<NotePadMeta, int[]>();
    final List<int[]> nombres = new ArrayList<int[]>();
    final List<String> quienNombre = new ArrayList<String>();
    final List<int[]> hops = new ArrayList<int[]>();
    final List<String> quienHop = new ArrayList<String>();
  }

  static Medida pintar( Display display, TransMeta tm, int icono, File png ) throws Exception {
    PropsUI props = PropsUI.getInstance();
    FontData nota = props.getNoteFont();
    Point max = ReportImagen.maximo( tm );
    Point area = new Point( max.x + 300, max.y + 150 );
    SWTGC gc = new SWTGC( display, area, icono );
    List<AreaOwner> areas = new ArrayList<AreaOwner>();
    TransPainter tp = new TransPainter( gc, tm, area, null, null, null, null, null, areas, new ArrayList<StepMeta>(),
      icono, props.getLineWidth(), 1, 0, props.isAntiAliasingEnabled(), nota.getName(), nota.getHeight(), null, false );
    tp.setMagnification( 1.0f );
    tp.buildTransformationImage();
    Medida md = new Medida();
    for ( AreaOwner ao : areas ) {
      int[] r = new int[] { ao.getArea().x, ao.getArea().y, ao.getArea().width, ao.getArea().height };
      if ( ao.getAreaType() == AreaOwner.AreaType.STEP_ICON && ao.getOwner() instanceof StepMeta ) {
        md.iconos.put( (StepMeta) ao.getOwner(), r );
      } else if ( ao.getAreaType() == AreaOwner.AreaType.NOTE && ao.getOwner() instanceof NotePadMeta ) {
        md.notas.put( (NotePadMeta) ao.getOwner(), r );
      }
    }
    // el painter deja el GC desechado: los nombres se miden con otro, con la misma fuente GRAPH
    SWTGC m = new SWTGC( display, new Point( 10, 10 ), icono );
    TransPainter tp2 = new TransPainter( m, tm, new Point( 10, 10 ), null, null, null, null, null,
      new ArrayList<AreaOwner>(), new ArrayList<StepMeta>(), icono, props.getLineWidth(), 1, 0,
      props.isAntiAliasingEnabled(), nota.getName(), nota.getHeight(), null, false );
    m.setFont( EFont.GRAPH );
    for ( StepMeta s : tm.getSteps() ) {
      Point ext = m.textExtent( s.getName() );
      Point p = tp2.getNamePosition( s.getName(), s.getLocation(), icono );
      md.nombres.add( new int[] { p.x, p.y, ext.x, ext.y } );
      md.quienNombre.add( s.getName() );
    }
    m.dispose();
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() == null || h.getToStep() == null || h.getFromStep() == h.getToStep() ) {
        continue;
      }
      Point f = h.getFromStep().getLocation();
      Point t = h.getToStep().getLocation();
      md.hops.add( new int[] { f.x + icono / 2, f.y + icono / 2, t.x + icono / 2, t.y + icono / 2 } );
      md.quienHop.add( h.getFromStep().getName() + " -> " + h.getToStep().getName() );
    }
    Image img = (Image) gc.getImage();
    byte[] bytes = png == null ? null : ReportImagen.png( img.getImageData() );
    try {
      gc.dispose();
    } catch ( Exception yaDesechado ) {
      // nada
    }
    img.dispose();
    if ( png != null ) {
      try ( FileOutputStream f = new FileOutputStream( png ) ) {
        f.write( bytes );
      }
    }
    return md;
  }

  // ---------------------------------------------------------------- medir

  static final class Conteo {
    int notas;
    int compactas;
    int choques;
    int choquesUsuario;
    int distMax;
    int lejos;
    int fueraDeReserva;
    final List<Integer> distanciasCompletas = new ArrayList<Integer>();
  }

  /**
   * Choques (nota de la leccion +4 px contra iconos, nombres, hops y cualquier otra nota; nota previa contra iconos,
   * nombres y hops), distancia de cada nota de la leccion al icono de su step y si lo dibujado entra en lo
   * reservado por la regla. 'deLeccion' mapea cada nota de la leccion a su ubicacion planificada (o null).
   */
  static Conteo contar( TransMeta tm, Medida md, Map<NotePadMeta, Ubicada> deLeccion, String archivo, String variante ) {
    Conteo c = new Conteo();
    List<NotePadMeta> todas = new ArrayList<NotePadMeta>( md.notas.keySet() );
    for ( int i = 0; i < todas.size(); i++ ) {
      NotePadMeta n = todas.get( i );
      int[] r = md.notas.get( n );
      boolean leccion = deLeccion.containsKey( n );
      int[] ri = LeccionLayout.inflar( r, AIRE );
      List<String> contra = new ArrayList<String>();
      for ( Map.Entry<StepMeta, int[]> x : md.iconos.entrySet() ) {
        if ( LeccionLayout.cortan( ri, x.getValue() ) ) {
          contra.add( "icono de '" + x.getKey().getName() + "'" );
        }
      }
      for ( int k = 0; k < md.nombres.size(); k++ ) {
        if ( LeccionLayout.cortan( ri, md.nombres.get( k ) ) ) {
          contra.add( "nombre de '" + md.quienNombre.get( k ) + "'" );
        }
      }
      for ( int k = 0; k < md.hops.size(); k++ ) {
        if ( LeccionLayout.segmentoCorta( md.hops.get( k ), ri ) ) {
          contra.add( "hop " + md.quienHop.get( k ) );
        }
      }
      if ( leccion ) {
        for ( int k = 0; k < todas.size(); k++ ) {
          NotePadMeta o = todas.get( k );
          if ( k == i || ( deLeccion.containsKey( o ) && k < i ) ) {
            continue; // cada par de la leccion una vez
          }
          if ( LeccionLayout.cortan( ri, md.notas.get( o ) ) ) {
            contra.add( ( deLeccion.containsKey( o ) ? "nota de la leccion " : "nota previa " ) + rect( md.notas.get( o ) ) );
          }
        }
        c.notas++;
        c.choques += contra.size();
        Ubicada u = deLeccion.get( n );
        if ( u != null && u.banda == Banda.COMPACTA ) {
          c.compactas++;
        }
        if ( u != null && !variante.endsWith( "/relayout" ) ) {
          int[] res = u.rect();
          if ( r[ 0 ] < res[ 0 ] || r[ 1 ] < res[ 1 ] || r[ 0 ] + r[ 2 ] > res[ 0 ] + res[ 2 ] || r[ 1 ] + r[ 3 ] > res[ 1 ]
            + res[ 3 ] ) {
            c.fueraDeReserva++;
            System.out.println( "  [" + variante + "] FUERA DE RESERVA " + archivo + " dibujada " + rect( r )
              + " reservada " + rect( res ) );
          }
        }
        Ubicada ub = u;
        String step = ub == null ? null : ub.pedida.step;
        if ( step != null ) {
          StepMeta s = tm.findStep( step );
          int[] ic = s == null ? null : md.iconos.get( s );
          if ( ic != null ) {
            int d = LeccionLayout.distancia( r, ic );
            c.distMax = Math.max( c.distMax, d );
            if ( ub.banda != Banda.COMPACTA ) {
              c.distanciasCompletas.add( d );
            }
            if ( d > LEGIBLE ) {
              c.lejos++;
            }
          }
        }
      } else {
        c.choquesUsuario += contra.size();
      }
      for ( String x : contra ) {
        System.out.println( "  [" + variante + "] CHOQUE " + archivo + ": " + ( leccion ? "nota de la leccion " : "nota previa " )
          + rect( r ) + " con " + x );
      }
    }
    return c;
  }

  // ---------------------------------------------------------------- B. funcionales sobre un ejemplo

  static void funcionales( Display display, File f, File png ) {
    String n = f.getName();
    String base = n.substring( 0, n.length() - 4 );
    System.out.println( "[funcionales] " + n );
    LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( -1 );
    try {
      TransMeta tm = new TransMeta( f.getPath() );
      // una nota del usuario que la leccion no es duena
      NotePadMeta usuario = new NotePadMeta( "Nota del usuario (no es de la lección)", 40, 40, 160, 30 );
      tm.addNote( usuario );
      Map<String, Point> pos0 = posiciones( tm );
      int notas0 = tm.nrNotes();
      tm.clearUndo();

      // 1. la leccion entera es un Ctrl+Z (y un Ctrl+Y)
      Map<NotePadMeta, Ubicada> creadas = new IdentityHashMap<NotePadMeta, Ubicada>();
      Plan p = anotar( tm, m, 32, Densidad.COMPACTA, UndoGrupo.sinSpoon( tm ), creadas );
      int enLienzo = creadas.size();
      cierto( enLienzo > 0, n + ": hay notas de la leccion en el lienzo" );
      igual( enLienzo, LeccionNotas.reconocer( tm ).size(), n + ": todas reconocidas por huella" );
      Map<String, Point> posPlan = posiciones( tm );
      Point locUsuario = new Point( usuario.getLocation().x, usuario.getLocation().y );
      int deshechas = deshacerGrupo( tm );
      cierto( deshechas >= 1, n + ": hay un grupo para deshacer" );
      igual( null, tm.viewThisUndo(), n + ": un solo Ctrl+Z vacia el historial" );
      igual( notas0, tm.nrNotes(), n + ": Ctrl+Z quita todas las notas de la leccion" );
      igual( pos0, posiciones( tm ), n + ": Ctrl+Z devuelve los steps a su lugar" );
      igual( 0, LeccionNotas.reconocer( tm ).size(), n + ": tras Ctrl+Z no queda ninguna reconocida" );
      igual( enLienzo, cuenta( LeccionNotas.listar( tm ), "missing" ), n + ": el registro queda y las da como missing" );
      rehacerGrupo( tm );
      igual( notas0 + enLienzo, tm.nrNotes(), n + ": Ctrl+Y las repone" );
      igual( posPlan, posiciones( tm ), n + ": Ctrl+Y repone las posiciones del plan" );
      igual( locUsuario.x + "," + locUsuario.y, usuario.getLocation().x + "," + usuario.getLocation().y,
        n + ": Ctrl+Y vuelve a apilar la nota del usuario" );
      igual( enLienzo, cuenta( LeccionNotas.listar( tm ), "present" ), n + ": tras Ctrl+Y todas present" );

      // 2. guardar y releer: es la copia anotada que pasa por Pan
      File clase = new File( f.getParentFile(), base + "_clase.ktr" );
      escribir( clase, tm );
      TransMeta re = new TransMeta( clase.getPath() );
      igual( tm.nrNotes(), re.nrNotes(), n + ": mismas notas tras releer" );
      igual( enLienzo, LeccionNotas.reconocer( re ).size(), n + ": reconocidas tras releer" );
      igual( "es", re.getAttribute( LeccionNotas.GRUPO, "language" ), n + ": idioma en el registro" );
      Map<NotePadMeta, Ubicada> rec = new IdentityHashMap<NotePadMeta, Ubicada>();
      for ( NotePadMeta x : LeccionNotas.reconocer( re ).keySet() ) {
        rec.put( x, null );
      }
      Conteo c = contar( re, pintar( display, re, 32, new File( png, "ejemplo_" + limpio( base ) + "_clase.png" ) ), rec,
        clase.getName(), "funcionales" );
      igual( 0, c.choques, n + ": 0 choques en la copia anotada releida" );

      // 3. relayout y su Ctrl+Z (CHANGE_NOTE repone clones): siguen reconocidas
      re.clearUndo();
      re.getStep( 0 ).setLocation( re.getStep( 0 ).getLocation().x + 37, re.getStep( 0 ).getLocation().y + 11 );
      LeccionNotas.MedidorCerrable m14 = LeccionNotas.medidor( 14 );
      try {
        LeccionNotas.Resultado rl = LeccionNotas.reacomodar( re, m14, UndoGrupo.sinSpoon( re ), 14 );
        cierto( rl.stepsMovidos >= 1, n + ": relayout devuelve el step corrido a la grilla" );
      } finally {
        m14.cerrar();
      }
      igual( enLienzo, LeccionNotas.reconocer( re ).size(), n + ": reconocidas tras relayout con fuente 14" );
      int acciones = deshacerGrupo( re );
      cierto( acciones >= 1, n + ": el relayout es un Ctrl+Z" );
      igual( null, re.viewThisUndo(), n + ": un solo Ctrl+Z deshace el relayout" );
      igual( enLienzo, LeccionNotas.reconocer( re ).size(), n + ": reconocidas tras el Ctrl+Z del relayout (clones)" );
      igual( 0, cuenta( LeccionNotas.listar( re ), "missing" ), n + ": nada missing tras relayout + Ctrl+Z" );

      // 4. desfase: el usuario edita una nota de paso -> pasa a ser suya; remove la respeta; Ctrl+Z las devuelve
      TransMeta d = new TransMeta( clase.getPath() );
      NotePadMeta editada = null;
      for ( Map.Entry<NotePadMeta, LeccionNotas.Entrada> x : LeccionNotas.reconocer( d ).entrySet() ) {
        if ( x.getValue().tipo == Tipo.PASO && editada == null && x.getKey().getNote().contains( "\n" ) ) {
          editada = x.getKey();
        }
      }
      if ( editada != null ) {
        int reg = LeccionNotas.registro( d ).size();
        editada.setNote( editada.getNote() + "\n(apunte del docente)" );
        igual( enLienzo - 1, LeccionNotas.reconocer( d ).size(), n + ": la editada deja de ser de la leccion" );
        igual( 1, cuenta( LeccionNotas.listar( d ), "missing" ), n + ": la editada figura missing" );
        igual( reg, LeccionNotas.registro( d ).size(), n + ": el registro no cambia por una edicion" );
        int antes = d.nrNotes();
        d.clearUndo();
        LeccionNotas.Resultado q = LeccionNotas.quitar( d, false, UndoGrupo.sinSpoon( d ) );
        igual( enLienzo - 1, q.notas, n + ": remove quita las reconocidas" );
        igual( antes - ( enLienzo - 1 ), d.nrNotes(), n + ": quedan la del usuario y la editada" );
        cierto( d.indexOfNote( editada ) >= 0, n + ": la editada sigue en el lienzo" );
        deshacerGrupo( d );
        igual( enLienzo - 1, LeccionNotas.reconocer( d ).size(), n + ": Ctrl+Z del remove las devuelve reconocidas" );
      }
      // step renombrado: sus entradas pasan a step_missing y relayout no revienta
      TransMeta r2 = new TransMeta( clase.getPath() );
      StepMeta s0 = r2.getStep( 0 );
      String viejo = s0.getName();
      int delStep = 0;
      for ( LeccionNotas.Entrada x : LeccionNotas.registro( r2 ) ) {
        if ( x.step.equalsIgnoreCase( viejo ) ) {
          delStep++;
        }
      }
      s0.setName( viejo + " (renombrado)" );
      igual( delStep, cuenta( LeccionNotas.listar( r2 ), "step_missing" ), n + ": step renombrado -> step_missing" );
      LeccionNotas.Resultado r3 = LeccionNotas.reacomodar( r2, m, null, -1 );
      cierto( contiene( r3.avisos, "not in the transformation any more" ) || delStep == 0,
        n + ": relayout avisa del step que ya no esta" );

      // 5. ocultar, guardar, releer, mostrar; y mostrar tras un Ctrl+Z del ocultar no duplica
      TransMeta h = new TransMeta( clase.getPath() );
      int total = h.nrNotes();
      h.clearUndo();
      LeccionNotas.Resultado oc = LeccionNotas.ocultar( h, UndoGrupo.sinSpoon( h ) );
      igual( enLienzo, oc.notas, n + ": hide saca todas" );
      igual( total - enLienzo, h.nrNotes(), n + ": en el lienzo solo las del usuario" );
      File oculta = new File( f.getParentFile(), base + "_oculta.ktr" );
      escribir( oculta, h );
      TransMeta ho = new TransMeta( oculta.getPath() );
      igual( total - enLienzo, ho.nrNotes(), n + ": ocultas tras releer" );
      igual( enLienzo, cuenta( LeccionNotas.listar( ho ), "hidden" ), n + ": list las da hidden" );
      LeccionNotas.Resultado mo = LeccionNotas.mostrar( ho, null );
      igual( enLienzo, mo.notas, n + ": show repone todas" );
      igual( enLienzo, LeccionNotas.reconocer( ho ).size(), n + ": reconocidas tras show" );
      deshacerGrupo( h ); // Ctrl+Z del hide: vuelven, hidden sigue lleno
      igual( total, h.nrNotes(), n + ": Ctrl+Z del hide las devuelve" );
      LeccionNotas.Resultado mo2 = LeccionNotas.mostrar( h, null );
      igual( 0, mo2.notas, n + ": show tras el Ctrl+Z no duplica" );
      igual( total, h.nrNotes(), n + ": sin duplicados" );
    } catch ( Throwable t ) {
      fallar( n + ": excepcion " + t );
      t.printStackTrace( System.out );
    } finally {
      m.cerrar();
    }
  }

  // ---------------------------------------------------------------- undo / redo como Spoon

  /** Ctrl+Z de Spoon: deshace la ultima accion y, si tenia getNextAlso(), la anterior (y asi). Devuelve cuantas. */
  static int deshacerGrupo( TransMeta tm ) {
    int n = 0;
    TransAction ta;
    do {
      ta = tm.previousUndo();
      if ( ta == null ) {
        break;
      }
      deshacer( tm, ta );
      n++;
    } while ( ta.getNextAlso() );
    return n;
  }

  /** Ctrl+Y de Spoon: rehace la siguiente y, mientras la que sigue tenga getNextAlso(), esa tambien. */
  static void rehacerGrupo( TransMeta tm ) {
    TransAction ta = tm.nextUndo();
    while ( ta != null ) {
      rehacer( tm, ta );
      TransAction sig = tm.viewNextUndo();
      ta = sig != null && sig.getNextAlso() ? tm.nextUndo() : null;
    }
  }

  static void deshacer( TransMeta tm, TransAction ta ) {
    Object[] prev = ta.getPrevious();
    Object[] cur = ta.getCurrent();
    int[] idx = ta.getCurrentIndex();
    switch ( ta.getType() ) {
      case TransAction.TYPE_ACTION_NEW_NOTE:
        for ( int i = idx.length - 1; i >= 0; i-- ) {
          tm.removeNote( idx[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_DELETE_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.addNote( idx[ i ], (NotePadMeta) cur[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_CHANGE_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.removeNote( idx[ i ] );
          tm.addNote( idx[ i ], (NotePadMeta) ( (NotePadMeta) prev[ i ] ).clone() );
        }
        break;
      case TransAction.TYPE_ACTION_POSITION_STEP:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.getStep( idx[ i ] ).setLocation( ta.getPreviousLocation()[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_POSITION_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.getNote( idx[ i ] ).setLocation( ta.getPreviousLocation()[ i ] );
        }
        break;
      default:
        throw new IllegalStateException( "tipo de undo no simulado: " + ta.getType() );
    }
  }

  static void rehacer( TransMeta tm, TransAction ta ) {
    Object[] cur = ta.getCurrent();
    int[] idx = ta.getCurrentIndex();
    switch ( ta.getType() ) {
      case TransAction.TYPE_ACTION_NEW_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.addNote( idx[ i ], (NotePadMeta) cur[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_DELETE_NOTE:
        for ( int i = idx.length - 1; i >= 0; i-- ) {
          tm.removeNote( idx[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_CHANGE_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.removeNote( idx[ i ] );
          tm.addNote( idx[ i ], (NotePadMeta) ( (NotePadMeta) cur[ i ] ).clone() );
        }
        break;
      case TransAction.TYPE_ACTION_POSITION_STEP:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.getStep( idx[ i ] ).setLocation( ta.getCurrentLocation()[ i ] );
        }
        break;
      case TransAction.TYPE_ACTION_POSITION_NOTE:
        for ( int i = 0; i < idx.length; i++ ) {
          tm.getNote( idx[ i ] ).setLocation( ta.getCurrentLocation()[ i ] );
        }
        break;
      default:
        throw new IllegalStateException( "tipo de redo no simulado: " + ta.getType() );
    }
  }

  // ---------------------------------------------------------------- ayudas

  static Map<String, Point> posiciones( TransMeta tm ) {
    Map<String, Point> r = new java.util.TreeMap<String, Point>();
    for ( StepMeta s : tm.getSteps() ) {
      r.put( s.getName(), new Point( s.getLocation().x, s.getLocation().y ) );
    }
    return r;
  }

  static int cuenta( List<String[]> l, String estado ) {
    int n = 0;
    for ( String[] e : l ) {
      if ( estado.equals( e[ 3 ] ) ) {
        n++;
      }
    }
    return n;
  }

  static boolean contiene( List<String> l, String t ) {
    for ( String s : l ) {
      if ( s.contains( t ) ) {
        return true;
      }
    }
    return false;
  }

  static void escribir( File f, TransMeta tm ) throws Exception {
    try ( Writer w = new OutputStreamWriter( new FileOutputStream( f ), StandardCharsets.UTF_8 ) ) {
      w.write( XMLHandler.getXMLHeader() + tm.getXML() );
    }
  }

  static void cierto( boolean b, String que ) {
    if ( !b ) {
      fallar( que );
    }
  }

  static void igual( Object esperado, Object real, String que ) {
    boolean ok = esperado == null ? real == null : esperado.equals( real );
    if ( esperado instanceof Map && real instanceof Map ) {
      ok = puntos( (Map<?, ?>) esperado ).equals( puntos( (Map<?, ?>) real ) );
    }
    if ( !ok ) {
      fallar( que + " (esperado " + texto( esperado ) + ", real " + texto( real ) + ")" );
    }
  }

  static String puntos( Map<?, ?> m ) {
    StringBuilder b = new StringBuilder();
    for ( Map.Entry<?, ?> e : m.entrySet() ) {
      Object v = e.getValue();
      b.append( e.getKey() ).append( '=' ).append( v instanceof Point ? ( (Point) v ).x + "," + ( (Point) v ).y : v )
        .append( ';' );
    }
    return b.toString();
  }

  static String texto( Object o ) {
    return o instanceof Map ? puntos( (Map<?, ?>) o ) : String.valueOf( o );
  }

  static void fallar( String que ) {
    fallas++;
    textoFallas.add( que );
    System.out.println( "  FALLA " + que );
  }

  static String rect( int[] r ) {
    return "[" + r[ 0 ] + "," + r[ 1 ] + "," + r[ 2 ] + "," + r[ 3 ] + "]";
  }

  static String primera( Throwable t ) {
    String m = String.valueOf( t.getMessage() ).trim();
    int i = m.indexOf( '\n' );
    return t.getClass().getSimpleName() + ": " + ( i < 0 ? m : m.substring( 0, i ) );
  }

  static String limpio( String s ) {
    return s.replaceAll( "[^A-Za-z0-9_-]+", "_" );
  }

  /** Ruta corta para los informes: desde samples/ o desde ejemplos/ (nunca la ruta personal entera). */
  static String relativo( File f ) {
    String p = f.getPath().replace( '\\', '/' );
    for ( String marca : new String[] { "/samples/", "/ejemplos/" } ) {
      int i = p.indexOf( marca );
      if ( i >= 0 ) {
        return p.substring( i + 1 );
      }
    }
    return f.getName();
  }

  static int[] enteros( List<Integer> l ) {
    int[] r = new int[ l.size() ];
    for ( int i = 0; i < r.length; i++ ) {
      r[ i ] = l.get( i );
    }
    return r;
  }

  static void buscar( File d, List<File> l ) {
    File[] hs = d.listFiles();
    if ( hs == null ) {
      return;
    }
    java.util.Arrays.sort( hs );
    for ( File h : hs ) {
      if ( h.isDirectory() ) {
        buscar( h, l );
      } else if ( h.getName().toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) ) {
        l.add( h );
      }
    }
  }
}
