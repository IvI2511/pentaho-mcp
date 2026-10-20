package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.lint.Catalogo;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.NoEvaluado;
import io.github.ivi2511.pentahomcp.lint.NombresPorDefecto;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Penalidad;
import io.github.ivi2511.pentahomcp.lint.Puntaje;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Rutas;
import io.github.ivi2511.pentahomcp.lint.Profundo;
import io.github.ivi2511.pentahomcp.lint.Sev;
import io.github.ivi2511.pentahomcp.lint.Sondas;
import io.github.ivi2511.pentahomcp.lint.Supresiones;
import io.github.ivi2511.pentahomcp.lint.Textos;

/**
 * Arnés del motor del linter, sin Spoon (docs/linter-diseno.md §3, WP7). Inicializa Kettle, carga cada .ktr/.kjb
 * de los corpus como lo vería el plugin (new TransMeta/JobMeta + ReportTools.clonar) y corre LintMotor con
 * LintEntorno. Además, pruebas chicas del puntaje, las keys, las supresiones, los textos y los filtros, los
 * arreglos de apply_lint_fixes (WP5) sobre cada documento de los corpus y el modo profundo (WP3).
 *
 * Uso (lo arma correr.ps1): java ... io.github.ivi2511.pentahomcp.ArnesLint <salida> <corpus>=<carpeta> ...
 *   -Dlint.esperado=esperado.tsv   recall del corpus "mutaciones"
 *   -Dlint.base=linea-base.tsv     compara los conteos con la línea base (-Dlint.base.escribir=true la rehace)
 *   -Dlint.profundo=esperado-profundo.tsv  lo que tiene que dar el modo profundo en los casos deterministas
 * Sale con 1 si falla algo.
 */
public final class ArnesLint {

  static final List<String> FALLAS = new ArrayList<String>();
  static int pruebas;
  /** Un marcador de los textos que quedó sin reemplazar (no un ${var} del documento). */
  static final java.util.regex.Pattern MARCADOR = java.util.regex.Pattern.compile( "(?<!\\$)\\{(step|entry|keys|at|input"
    + "|sortkeys|detail|n|field|type|format|example|value|where|file|variable|steps|parameter|from|to|path|result|a|b"
    + "|op|typeb|targets|name|fields|default|key|rows|sorted_rows|with|values|locale|read|error|symbol|why|prev|row|s)\\}" );

  static final class Doc {
    String corpus;
    String archivo;
    Resultado r;
    String error;
  }

  public static void main( String[] args ) throws Exception {
    if ( args.length < 2 ) {
      System.err.println( "uso: ArnesLint <salida> <corpus>=<carpeta> ..." );
      System.exit( 2 );
    }
    long t0 = System.currentTimeMillis();
    KettleEnvironment.init( false );
    System.out.println( "Kettle: " + ( System.currentTimeMillis() - t0 ) + " ms; nombres por defecto del archivo: "
      + NombresPorDefecto.cuantosDelArchivo() );
    File salida = new File( args[ 0 ] );
    salida.mkdirs();
    Map<String, Path> corpus = new LinkedHashMap<String, Path>();
    for ( int i = 1; i < args.length; i++ ) {
      String[] kv = args[ i ].split( "=", 2 );
      corpus.put( kv[ 0 ], Paths.get( kv[ 1 ] ) );
    }

    pruebasSinCorpus();
    List<Doc> docs = new ArrayList<Doc>();
    long t1 = System.currentTimeMillis();
    for ( Map.Entry<String, Path> c : corpus.entrySet() ) {
      List<Path> archivos;
      try ( Stream<Path> s = Files.walk( c.getValue() ) ) {
        archivos = s.filter( p -> p.toString().endsWith( ".ktr" ) || p.toString().endsWith( ".kjb" ) ).sorted()
          .collect( Collectors.toList() );
      }
      for ( Path p : archivos ) {
        Doc d = new Doc();
        d.corpus = c.getKey();
        d.archivo = c.getValue().relativize( p ).toString().replace( '\\', '/' );
        try {
          d.r = analizar( cargar( p ), new Opciones() );
          revisarClaves( d );
        } catch ( Throwable e ) {
          d.error = e.getClass().getSimpleName() + ": " + String.valueOf( e.getMessage() ).split( "\n" )[ 0 ];
        }
        docs.add( d );
      }
    }
    long msCorpus = System.currentTimeMillis() - t1;
    if ( corpus.containsKey( "mutaciones" ) ) {
      pruebasConMutaciones( corpus.get( "mutaciones" ) );
      String esp = System.getProperty( "lint.esperado" );
      if ( esp != null ) {
        recall( docs, Paths.get( esp ) );
      }
    }
    pruebasArreglos( corpus, salida );
    pruebasProfundo( docs, corpus, salida );
    escribir( salida, docs, msCorpus );
    String base = System.getProperty( "lint.base" );
    if ( base != null ) {
      lineaBase( docs, new File( base ), Boolean.getBoolean( "lint.base.escribir" ) );
    }
    System.out.println();
    System.out.println( "pruebas: " + pruebas + ", fallas: " + FALLAS.size() + "; corpus en " + msCorpus + " ms; total "
      + ( System.currentTimeMillis() - t0 ) + " ms" );
    for ( String f : FALLAS ) {
      System.out.println( "FALLA " + f );
    }
    System.exit( FALLAS.isEmpty() ? 0 : 1 );
  }

  // ---------------------------------------------------------------- carga y análisis, como el plugin

  static AbstractMeta cargar( Path p ) throws Exception {
    AbstractMeta m = p.toString().endsWith( ".ktr" ) ? new TransMeta( p.toString() ) : new JobMeta( p.toString(), null );
    return ReportTools.clonar( m ); // la copia desligada de validate/Informes
  }

  static Resultado analizar( AbstractMeta copia, Opciones op ) {
    if ( "en".equals( op.lang ) ) { // -Dlint.lang no pisa el idioma que pide una prueba
      op.lang = System.getProperty( "lint.lang", op.lang );
    }
    op.entorno = new LintEntorno( copia );
    return LintMotor.analizar( copia, op );
  }

  static void revisarClaves( Doc d ) {
    Set<String> ks = new HashSet<String>();
    List<Hallazgo> todos = new ArrayList<Hallazgo>( d.r.findings );
    todos.addAll( d.r.suppressed );
    for ( Hallazgo h : todos ) {
      if ( !ks.add( h.key ) ) {
        falla( "key repetida en " + d.corpus + "/" + d.archivo + ": " + h.key );
      }
      if ( h.message == null || h.message.contains( ".msg" ) || h.howToFix == null || h.howToFix.contains( ".fix" )
        || MARCADOR.matcher( h.message ).find() || MARCADOR.matcher( h.howToFix ).find() ) {
        falla( "texto sin resolver en " + d.archivo + ": " + h.key + " -> " + h.message + " / " + h.howToFix );
      }
    }
  }

  // ---------------------------------------------------------------- pruebas

  static void ok( boolean cond, String que ) {
    pruebas++;
    if ( !cond ) {
      falla( que );
    }
  }

  static void falla( String que ) {
    FALLAS.add( que );
  }

  static Hallazgo h( String rule, Sev sev ) {
    Hallazgo x = new Hallazgo();
    x.rule = rule;
    x.severity = sev;
    return x;
  }

  static Resultado puntaje( Hallazgo... hs ) {
    Resultado r = new Resultado();
    Puntaje.calcular( Arrays.asList( hs ), r );
    return r;
  }

  static void pruebasSinCorpus() {
    // puntaje (§4)
    Resultado r = puntaje( h( "absolute_path", Sev.WARNING ), h( "absolute_path", Sev.WARNING ), h( "absolute_path", Sev.WARNING ),
      h( "no_description", Sev.INFO ), h( "default_step_name", Sev.INFO ) );
    ok( r.score == 80 && r.grade.equals( "B" ), "puntaje BD1 Ejercicio 1 = 80 B, dio " + r.score );
    ok( r.penalties.get( 0 ).rule.equals( "absolute_path" ) && r.penalties.get( 0 ).points == 16, "penalidad absolute_path 16" );
    r = puntaje( h( "relative_path", Sev.ERROR ) );
    ok( r.score == 74 && r.gateApplied && r.grade.equals( "C" ), "compuerta: un error = 74, dio " + r.score );
    r = puntaje( h( "a", Sev.INFO ), h( "a", Sev.INFO ), h( "a", Sev.INFO ), h( "b", Sev.INFO ), h( "b", Sev.INFO ),
      h( "c", Sev.INFO ), h( "c", Sev.INFO ) );
    ok( r.score == 90 && r.infoPointsRaw == 12 && r.infoPoints == 10, "tope de info 10: 90, dio " + r.score );
    r = puntaje( h( "x", Sev.ERROR ), h( "x", Sev.ERROR ), h( "x", Sev.ERROR ), h( "y", Sev.WARNING ) );
    ok( r.score == 42 && r.grade.equals( "D" ), "error con tope 50 + warning 8 = 42, dio " + r.score );
    r = puntaje( h( "p", Sev.WARNING ), h( "p", Sev.INFO ) );
    ok( r.score == 90 && r.penalties.get( 0 ).severity == Sev.WARNING && r.penalties.get( 0 ).points == 10,
      "regla mixta W+I: suma 10 con tope de warning, dio " + r.score );
    ok( puntaje().score == 100 && puntaje().grade.equals( "A" ), "sin hallazgos = 100 A" );

    // propuestas de ruta (§7)
    String doc = "C:/Users/alumno/BD1/Ejercicio ETL/Ejercicio 1.ktr";
    ok( Rutas.propuesta( doc, "C:\\Users\\alumno\\BD1\\Ejercicio ETL\\Ficheros ejercicio 1\\fichero1.csv" )[ 0 ]
      .equals( "${Internal.Entry.Current.Directory}/Ficheros ejercicio 1/fichero1.csv" ), "propuesta: misma carpeta" );
    ok( Rutas.propuesta( "C:/Users/alumno/BD1/Ejercicio ETL/Entrega/Ejercicio 1.ktr",
      "C:/Users/alumno/BD1/Ejercicio ETL/Ficheros ejercicio 1/fichero1.csv" )[ 0 ]
      .equals( "${Internal.Entry.Current.Directory}/../Ficheros ejercicio 1/fichero1.csv" ), "propuesta: un nivel arriba" );
    String[] p = Rutas.propuesta( doc, "/tmp/json.txt" );
    ok( p[ 0 ].equals( "${DIR_DATOS}/json.txt" ) && "DIR_DATOS".equals( p[ 1 ] ) && "/tmp".equals( p[ 2 ] ), "propuesta: DIR_DATOS" );
    ok( Rutas.propuesta( "file:///C:/a/b/c/d/x.ktr", "file:///C:/z/y.csv" )[ 1 ] != null, "propuesta: más de 2 niveles = parámetro" );
    ok( Rutas.absoluta( "C:\\x\\y.csv" ) && Rutas.absoluta( "\\\\srv\\share\\a.csv" ) && Rutas.absoluta( "/home/u/a.csv" )
      && !Rutas.absoluta( "/transformations" ) && !Rutas.absoluta( "${Internal.Entry.Current.Directory}/a.csv" ), "rutas absolutas" );
    ok( Rutas.relativaSinVariable( "datos/a.csv" ) && Rutas.relativaSinVariable( "a.csv" ) && !Rutas.relativaSinVariable( "${X}/a.csv" )
      && !Rutas.relativaSinVariable( "http://x/a.csv" ) && !Rutas.relativaSinVariable( "/transformations" ), "rutas relativas" );

    // textos: los tres idiomas con las mismas claves; cada regla con título, por qué, arreglo y mensaje
    Set<String> en = Textos.de( "en" ).claves();
    for ( String l : Arrays.asList( "es", "pt" ) ) {
      Set<String> otro = Textos.de( l ).claves();
      Set<String> faltan = new TreeSet<String>( en );
      faltan.removeAll( otro );
      Set<String> sobran = new TreeSet<String>( otro );
      sobran.removeAll( en );
      ok( faltan.isEmpty() && sobran.isEmpty(), "textos " + l + ": faltan " + faltan + ", sobran " + sobran );
    }
    for ( Regla g : Catalogo.todas() ) {
      for ( String s : Arrays.asList( ".title", ".why", ".fix" ) ) {
        ok( en.contains( g.id() + s ), "texto " + g.id() + s );
      }
      boolean msg = g.id().equals( Catalogo.KETTLE_CHECK );
      for ( String k : en ) {
        msg |= k.equals( g.id() + ".msg" ) || k.startsWith( g.id() + ".msg." );
      }
      ok( msg, "mensaje de " + g.id() );
    }
    ok( Catalogo.todas().size() == 31, "31 reglas en el catálogo, hay " + Catalogo.todas().size() );
    ok( Catalogo.todas().get( 0 ).id().equals( "cycle" ), "cycle corre primero" );
    ok( LintJson.catalogo().get( "rules" ).size() == 31, "pentaho://lint/rules" );
    boolean malo = false;
    try {
      Catalogo.seleccionar( new HashSet<String>( Arrays.asList( "no_existe" ) ), false );
    } catch ( IllegalArgumentException e ) {
      malo = e.getMessage().contains( "Valid ids" );
    }
    ok( malo, "un id desconocido es un error con la lista" );
    ok( Catalogo.buscar( "lint:unsorted_input" ) != null, "ids lint:<id> del corrector" );
    ok( NombresPorDefecto.esDeStep( "Text file output 2" ) && NombresPorDefecto.esDeStep( "Ordenar filas" )
      && NombresPorDefecto.esDeStep( "CSV file input" ) && !NombresPorDefecto.esDeStep( "Leer ventas" )
      && NombresPorDefecto.esDeEntrada( "Transformation" ) && !NombresPorDefecto.esDeEntrada( "Cargar ventas" ),
      "nombres por defecto en/es/pt" );
  }

  static Resultado lint( Path dir, String archivo, Opciones op ) throws Exception {
    return analizar( cargar( dir.resolve( archivo ) ), op );
  }

  static int cuenta( List<Hallazgo> l, String rule ) {
    int n = 0;
    for ( Hallazgo x : l ) {
      n += x.rule.equals( rule ) ? 1 : 0;
    }
    return n;
  }

  static void pruebasConMutaciones( Path dir ) throws Exception {
    // supresiones: en el step (sobrevive a la copia), en el documento, y el corrector las ignora
    TransMeta tm = new TransMeta( dir.resolve( "m09_ruta_absoluta.ktr" ).toString() );
    StepMeta leer = tm.findStep( "Leer ventas" );
    Supresiones.agregar( leer, "absolute_path", "carpeta del curso" );
    Resultado r = analizar( ReportTools.clonar( tm ), new Opciones() );
    int enLeer = 0;
    for ( Hallazgo x : r.suppressed ) {
      enLeer += x.rule.equals( "absolute_path" ) && "Leer ventas".equals( x.step ) ? 1 : 0;
    }
    ok( enLeer == 1 && cuenta( r.findings, "absolute_path" ) == 1, "silenciar en el step: 1 silenciado y 1 visible, dio "
      + enLeer + "/" + cuenta( r.findings, "absolute_path" ) );
    Opciones corrector = new Opciones();
    corrector.honorSuppressions = false;
    ok( cuenta( analizar( ReportTools.clonar( tm ), corrector ).findings, "absolute_path" ) == 2, "honorSuppressions=false" );
    Supresiones.agregar( tm, "absolute_path,no_description", null );
    r = analizar( ReportTools.clonar( tm ), new Opciones() );
    ok( cuenta( r.findings, "absolute_path" ) == 0 && cuenta( r.suppressed, "absolute_path" ) == 2, "silenciar en el documento" );
    Supresiones.quitar( leer, "absolute_path" );
    ok( leer.getAttributes( Supresiones.GRUPO ) == null, "quitar la última borra el grupo" );
    String xml = tm.getXML();
    ok( xml.contains( Supresiones.GRUPO ) && xml.contains( "no_description" ), "las supresiones del documento van en el XML" );

    // cambios sin guardar: modo archivo no corre; contraseñas en memoria = warning
    Opciones sg = new Opciones();
    sg.sinGuardar = true;
    r = lint( dir, "m14_nombres_duplicados.ktr", sg );
    boolean ne = false;
    for ( NoEvaluado n : r.notEvaluated ) {
      ne |= "duplicate_step_name".equals( n.rule ) && "unsaved".equals( n.code );
    }
    ok( ne && cuenta( r.findings, "duplicate_step_name" ) == 0, "duplicate_step_name con cambios sin guardar = no evaluada" );
    r = lint( dir, "m10_password_claro.ktr", sg );
    Hallazgo clave = null;
    for ( Hallazgo x : r.findings ) {
      clave = x.rule.equals( "password_in_file" ) ? x : clave;
    }
    ok( cuenta( r.findings, "password_in_file" ) == 1 && clave.severity == Sev.WARNING
      && !clave.message.contains( "secreto123" ), "contraseña en memoria = warning, sin el valor" );
    r = lint( dir, "m10_password_claro.ktr", new Opciones() );
    for ( Hallazgo x : r.findings ) {
      if ( x.rule.equals( "password_in_file" ) ) {
        ok( x.severity == Sev.ERROR && x.scope.equals( Hallazgo.CONNECTION ) && !x.message.contains( "secreto123" )
          && !LintJson.hallazgo( x ).toString().contains( "secreto123" ), "contraseña en claro en el archivo = error, sin el valor" );
      }
    }

    // ciclo primero: con un ciclo no se calculan campos
    Opciones solo = new Opciones();
    solo.rules = new HashSet<String>( Collections.singletonList( "calculator_integer_first" ) );
    r = lint( dir, "m18_ciclo.ktr", solo );
    ne = false;
    for ( NoEvaluado n : r.notEvaluated ) {
      ne |= "calculator_integer_first".equals( n.rule ) && "cycle".equals( n.code );
    }
    ok( ne && r.findings.isEmpty(), "rules=[calculator_integer_first] en un ciclo: no evaluada y sin mostrar cycle" );
    r = lint( dir, "m18_ciclo.ktr", new Opciones() );
    ok( cuenta( r.findings, "cycle" ) == 2, "cycle: un hallazgo por step del ciclo, dio " + cuenta( r.findings, "cycle" ) );

    // filtros: min_severity y steps no cambian el puntaje; las apagadas corren si se nombran
    Resultado todo = lint( dir, "m15_salida_sin_campos.ktr", new Opciones() ); // tiene un info
    Opciones w = new Opciones();
    w.minSeverity = Sev.WARNING;
    Resultado filtrado = lint( dir, "m15_salida_sin_campos.ktr", w );
    ok( filtrado.score == todo.score && filtrado.findings.size() < todo.findings.size() && filtrado.hidden > 0,
      "min_severity filtra sin cambiar el puntaje" );
    Opciones st = new Opciones();
    st.steps = new HashSet<String>( Collections.singletonList( "Leer ventas" ) );
    r = lint( dir, "m09_ruta_absoluta.ktr", st );
    boolean soloEse = true;
    for ( Hallazgo x : r.findings ) {
      soloEse &= "Leer ventas".equals( x.step );
    }
    ok( soloEse && !r.findings.isEmpty() && r.score == lint( dir, "m09_ruta_absoluta.ktr", new Opciones() ).score,
      "steps=[Leer ventas]" );
    ok( cuenta( lint( dir, "j05_trans_sin_rama_roja.kjb", new Opciones() ).findings, "job_entry_without_failure_hop" ) == 0,
      "las apagadas no corren si no se piden" );
    Opciones apagada = new Opciones();
    apagada.rules = new LinkedHashSet<String>( Arrays.asList( "job_entry_without_failure_hop" ) );
    r = lint( dir, "j05_trans_sin_rama_roja.kjb", apagada );
    ok( r.score == 100 && cuenta( r.findings, "job_entry_without_failure_hop" ) == 1 && r.findings.size() == 1,
      "las apagadas corren si se nombran y no restan: " + r.score + " " + r.findings );

    // keys y arreglos con la forma del §6
    r = lint( dir, "m21_calculadora_enteros.ktr", new Opciones() );
    Set<String> ks = new TreeSet<String>();
    for ( Hallazgo x : r.findings ) {
      if ( x.rule.equals( "calculator_integer_first" ) ) {
        ks.add( x.key + "|" + x.severity.codigo() + "|" + ( x.fix == null ? "-" : x.fix.kind ) );
      }
    }
    ok( ks.equals( new TreeSet<String>( Arrays.asList(
      "calculator_integer_first:step:Calcular importe:importe_mal|error|swap_operands",
      "calculator_integer_first:step:Calcular importe:ratio|warning|-" ) ) ), "keys de m21: " + ks );
    r = lint( dir, "m09_ruta_absoluta.ktr", new Opciones() );
    String j = LintJson.resultado( r ).toString();
    ok( j.contains( "\"rules_version\":\"0.6.0\"" ) && j.contains( "\"penalties\"" ) && j.contains( "\"not_evaluated\"" )
      && j.contains( "\"preview\":\"${DIR_DATOS}/ventas.csv\"" ) && j.contains( "\"default\":\"C:/Users/alumno/etl/datos\"" ),
      "JSON de lint_etl: " + j );
    Opciones es = new Opciones();
    es.lang = "es";
    r = analizar( cargar( dir.resolve( "m09_ruta_absoluta.ktr" ) ), es );
    ok( r.findings.get( 0 ).message.startsWith( "Ruta absoluta" ), "mensajes en español: " + r.findings.get( 0 ).message );
  }

  /**
   * apply_lint_fixes (LintArreglos, WP5) sobre una copia de cada documento de los corpus, como si alguien confirmara
   * todo: los arreglos que no piden un valor (los auto y los confirm con propuesta). Falla si un arreglo revienta, si
   * una key aplicada sigue saliendo después o si el documento no se puede volver a abrir desde su XML. Cuenta qué
   * nombres por defecto tienen propuesta sin IA (el diseño midió 51 % con el prototipo).
   */
  static void pruebasArreglos( Map<String, Path> corpus, File salida ) throws Exception {
    long t0 = System.currentTimeMillis();
    Map<String, Integer> porTipo = new TreeMap<String, Integer>();
    Map<String, Integer> motivos = new TreeMap<String, Integer>();
    int docs = 0;
    int conCambios = 0;
    int porDefecto = 0;
    int conPropuesta = 0;
    int suben = 0;
    int bajan = 0;
    File dir = new File( salida, "arreglados" );
    dir.mkdirs();
    for ( Map.Entry<String, Path> c : corpus.entrySet() ) {
      List<Path> archivos;
      try ( Stream<Path> st = Files.walk( c.getValue() ) ) {
        archivos = st.filter( x -> x.toString().endsWith( ".ktr" ) || x.toString().endsWith( ".kjb" ) ).sorted()
          .collect( Collectors.toList() );
      }
      for ( Path p : archivos ) {
        String nombre = c.getKey() + "/" + c.getValue().relativize( p ).toString().replace( '\\', '/' );
        AbstractMeta doc;
        try {
          doc = cargar( p );
        } catch ( Throwable e ) {
          continue; // ya figura como error del corpus
        }
        docs++;
        try {
          // 1) qué se puede arreglar sin un valor
          LintArreglos.Pedido todo = new LintArreglos.Pedido();
          LintArreglos.Analisis an0 = LintArreglos.analizar( ReportTools.clonar( doc ), opcionesArreglo( doc ), todo );
          for ( Hallazgo x : an0.antes.findings ) {
            if ( x.fix != null && !x.fix.needsValue ) {
              todo.keys.add( x.key );
            }
            if ( x.rule.equals( "default_step_name" ) ) {
              porDefecto++;
              conPropuesta += x.fix != null && x.fix.preview != null ? 1 : 0;
            }
          }
          if ( todo.keys.isEmpty() ) {
            continue;
          }
          // 2) analizar y aplicar, como apply_lint_fixes
          AbstractMeta copia = ReportTools.clonar( doc );
          LintArreglos.Analisis an = LintArreglos.analizar( copia, opcionesArreglo( copia ), todo );
          LintArreglos.Hecho h = LintArreglos.aplicar( doc, an, "en", UndoGrupo.sinSpoon( doc ), null, null );
          Set<String> aplicadas = new HashSet<String>();
          for ( com.fasterxml.jackson.databind.JsonNode x : h.aplicados ) {
            aplicadas.add( x.path( "key" ).asText() );
          }
          for ( Hallazgo x : an.antes.findings ) {
            if ( aplicadas.contains( x.key ) ) {
              porTipo.merge( x.fix.kind, 1, Integer::sum );
            }
          }
          for ( String[] x : h.saltados ) {
            String m = x[ 1 ].replaceAll( "'[^']*'", "'…'" ).replaceAll( "\\(.*", "" );
            motivos.merge( m.length() > 70 ? m.substring( 0, 70 ) : m, 1, Integer::sum );
            ok( !x[ 1 ].startsWith( "failed:" ), "arreglo que revienta en " + nombre + ": " + x[ 0 ] + " -> " + x[ 1 ] );
          }
          if ( h.cambios == 0 ) {
            continue;
          }
          conCambios++;
          // 3) lo aplicado ya no sale
          Resultado despues = analizar( ReportTools.clonar( doc ), opcionesArreglo( doc ) );
          for ( Hallazgo x : despues.findings ) {
            ok( !aplicadas.contains( x.key ), "key aplicada que sigue saliendo en " + nombre + ": " + x.key );
          }
          suben += despues.score > an.antes.score ? 1 : 0;
          bajan += despues.score < an.antes.score ? 1 : 0;
          ok( despues.score >= an.antes.score, "el puntaje baja tras arreglar " + nombre + ": " + an.antes.score + " -> "
            + despues.score + " " + claves( despues.findings ) );
          // 4) se vuelve a abrir desde su XML
          String ext = doc instanceof TransMeta ? ".ktr" : ".kjb";
          File f = new File( dir, nombre.replaceAll( "[/\\\\:]", "_" ).replaceAll( "\\.(ktr|kjb)$", "" ) + ext );
          String xml = doc instanceof TransMeta ? ( (TransMeta) doc ).getXML() : ( (JobMeta) doc ).getXML();
          Files.write( f.toPath(), xml.getBytes( StandardCharsets.UTF_8 ) );
          AbstractMeta otra = doc instanceof TransMeta ? new TransMeta( f.getPath() ) : new JobMeta( f.getPath(), null );
          int n1 = doc instanceof TransMeta ? ( (TransMeta) doc ).nrSteps() : ( (JobMeta) doc ).nrJobEntries();
          int n2 = otra instanceof TransMeta ? ( (TransMeta) otra ).nrSteps() : ( (JobMeta) otra ).nrJobEntries();
          ok( n1 == n2, "se pierden steps al volver a abrir " + nombre + ": " + n1 + " -> " + n2 );
        } catch ( Throwable e ) {
          ok( false, "apply_lint_fixes reventó en " + nombre + ": " + e );
        }
      }
    }
    int pct = porDefecto == 0 ? 0 : 100 * conPropuesta / porDefecto;
    System.out.println( "arreglos: " + docs + " documentos, " + conCambios + " con cambios, " + porTipo + "; puntaje "
      + suben + " suben, " + bajan + " bajan; propuestas de nombre " + conPropuesta + "/" + porDefecto + " (" + pct
      + " %); " + ( System.currentTimeMillis() - t0 ) + " ms" );
    for ( Map.Entry<String, Integer> m : motivos.entrySet() ) {
      System.out.println( "  saltados " + m.getValue() + " x " + m.getKey() );
    }
    ok( porDefecto == 0 || pct >= 40, "propuestas de nombre para al menos 40 % de los nombres por defecto: " + pct + " %" );
  }

  /**
   * El modo profundo (LintSondas, WP3) sobre los documentos de los corpus que tienen hallazgos con sonda: qué sube, qué
   * baja y qué queda (profundo.tsv), la calibración del puntaje con el modo profundo (el diseño, §4: samples
   * 177/30/1/0) y lo que tiene que dar en los casos deterministas (-Dlint.profundo, esperado-profundo.tsv). No entra en
   * la línea base: con varias copias o entradas el orden de las filas puede cambiar de una corrida a otra.
   */
  static void pruebasProfundo( List<Doc> docs, Map<String, Path> corpus, File salida ) throws Exception {
    long t0 = System.currentTimeMillis();
    List<Doc> conProfundo = new ArrayList<Doc>();
    Map<String, Hallazgo> porClave = new LinkedHashMap<String, Hallazgo>();
    Map<String, Integer> cambios = new TreeMap<String, Integer>();
    int previews = 0;
    try ( PrintWriter w = pw( new File( salida, "profundo.tsv" ) ) ) {
      w.println( "corpus\tarchivo\tkey\tstatic\tseverity\tdeep\tmessage" );
      for ( Doc d : docs ) {
        Doc p = new Doc();
        p.corpus = d.corpus;
        p.archivo = d.archivo;
        p.r = d.r;
        p.error = d.error;
        conProfundo.add( p );
        boolean sonda = false;
        Map<String, Sev> antes = new HashMap<String, Sev>();
        if ( d.r != null ) {
          for ( Hallazgo x : d.r.findings ) {
            sonda |= Sondas.REGLAS.contains( x.rule );
            antes.put( x.key, x.severity );
          }
        }
        if ( !sonda ) {
          continue; // el profundo no cambia nada: el resultado es el estático
        }
        try {
          Opciones op = new Opciones();
          op.deep = true;
          op.sondas = new LintSondas();
          p.r = analizar( cargar( corpus.get( d.corpus ).resolve( d.archivo ) ), op );
          revisarClaves( p );
          previews += p.r.deep.probes;
        } catch ( Throwable e ) {
          ok( false, "el modo profundo reventó en " + d.corpus + "/" + d.archivo + ": " + e );
          continue;
        }
        for ( Hallazgo x : p.r.findings ) {
          if ( !Sondas.REGLAS.contains( x.rule ) ) {
            continue;
          }
          porClave.put( d.corpus + "\t" + d.archivo + "\t" + x.key, x );
          Sev s0 = antes.get( x.key );
          String cambio = String.format( Locale.ROOT, "%-21s %s->%s %s", x.rule, s0 == null ? "?" : s0.codigo().substring( 0, 1 ),
            x.severity.codigo().substring( 0, 1 ), x.deep );
          cambios.merge( cambio, 1, Integer::sum );
          w.println( d.corpus + "\t" + d.archivo + "\t" + x.key + "\t" + ( s0 == null ? "?" : s0.codigo() ) + "\t"
            + x.severity.codigo() + "\t" + x.deep + "\t" + limpio( x.message ) );
          ok( x.deep != null || "data".equals( x.evidence.get( "cause" ) ), // el Data grid ya es la prueba
            "hallazgo con sonda sin resultado del modo profundo en " + d.archivo + ": " + x.key );
          ok( !Profundo.NOT_EVALUATED.equals( x.deep ) || x.severity == s0, "no evaluado y cambió la severidad: " + x.key );
        }
      }
    }
    System.out.println( "modo profundo: " + previews + " previews en " + ( System.currentTimeMillis() - t0 ) + " ms" );
    for ( String l : resumen( conProfundo ) ) {
      if ( l.isEmpty() ) {
        break; // solo los corpus: los conteos por regla ya están en profundo.tsv
      }
      System.out.println( "  profundo " + l.replaceAll( " ms=\\d+ \\(max \\d+\\)", "" ) );
    }
    for ( Map.Entry<String, Integer> c : cambios.entrySet() ) {
      System.out.println( String.format( Locale.ROOT, "  %3d x %s", c.getValue(), c.getKey() ) );
    }
    String esp = System.getProperty( "lint.profundo" );
    if ( esp == null ) {
      return;
    }
    int bien = 0;
    int total = 0;
    for ( String l : Files.readAllLines( Paths.get( esp ), StandardCharsets.UTF_8 ) ) {
      if ( l.startsWith( "#" ) || l.trim().isEmpty() ) {
        continue;
      }
      String[] c = l.split( "\t" );
      if ( !corpus.containsKey( c[ 0 ] ) ) {
        continue; // -SinSamples
      }
      total++;
      Hallazgo x = porClave.get( c[ 0 ] + "\t" + c[ 1 ] + "\t" + c[ 2 ] );
      boolean va = x != null && x.severity.codigo().equals( c[ 3 ] ) && c[ 4 ].equals( x.deep );
      bien += va ? 1 : 0;
      ok( va, "profundo: " + c[ 1 ] + " " + c[ 2 ] + " esperaba " + c[ 3 ] + "/" + c[ 4 ] + ", dio "
        + ( x == null ? "nada" : x.severity.codigo() + "/" + x.deep ) );
    }
    System.out.println( "modo profundo esperado: " + bien + "/" + total );
  }

  static List<String> claves( List<Hallazgo> l ) {
    List<String> r = new ArrayList<String>();
    for ( Hallazgo x : l ) {
      r.add( x.key );
    }
    return r;
  }

  static Opciones opcionesArreglo( AbstractMeta copia ) {
    Opciones op = new Opciones();
    op.sinGuardar = true; // el archivo del disco ya no es lo que se arregla
    op.entorno = new LintEntorno( copia );
    return op;
  }

  /** Cada mutación tiene que dar su regla con la severidad esperada (o no darla, si dice none). */
  static void recall( List<Doc> docs, Path esperado ) throws Exception {
    int total = 0;
    int bien = 0;
    for ( String l : Files.readAllLines( esperado, StandardCharsets.UTF_8 ) ) {
      if ( l.startsWith( "#" ) || l.trim().isEmpty() ) {
        continue;
      }
      String[] c = l.split( "\t" );
      Doc d = null;
      for ( Doc x : docs ) {
        if ( x.corpus.equals( "mutaciones" ) && x.archivo.equals( c[ 0 ] ) ) {
          d = x;
        }
      }
      total++;
      if ( d == null || d.r == null ) {
        falla( "recall: " + c[ 0 ] + " no se analizó" + ( d == null ? "" : ": " + d.error ) );
        continue;
      }
      Sev peor = null;
      for ( Hallazgo x : d.r.findings ) {
        if ( x.rule.equals( c[ 1 ] ) ) {
          peor = Sev.peor( peor, x.severity );
        }
      }
      boolean va = c[ 2 ].equals( "none" ) ? peor == null : peor != null && peor.codigo().equals( c[ 2 ] );
      if ( va ) {
        bien++;
      } else {
        falla( "recall: " + c[ 0 ] + " esperaba " + c[ 1 ] + " " + c[ 2 ] + ", dio " + ( peor == null ? "nada" : peor.codigo() ) );
      }
    }
    pruebas++;
    System.out.println( "recall en mutaciones: " + bien + "/" + total );
  }

  // ---------------------------------------------------------------- salida

  static void escribir( File dir, List<Doc> docs, long ms ) throws Exception {
    try ( PrintWriter h = pw( new File( dir, "hallazgos.tsv" ) ); PrintWriter p = pw( new File( dir, "puntajes.tsv" ) );
          PrintWriter n = pw( new File( dir, "no_evaluados.tsv" ) ) ) {
      h.println( "corpus\tarchivo\trule\tseverity\tkey\tmessage" );
      p.println( "corpus\tarchivo\tkind\tscore\tgrade\terror\twarning\tinfo\tsuppressed\tnot_evaluated\tms\tpenalties" );
      n.println( "corpus\tarchivo\trule\tstep\tcode\treason" );
      for ( Doc d : docs ) {
        if ( d.r == null ) {
          p.println( d.corpus + "\t" + d.archivo + "\t-\t-\t-\t\t\t\t\t\t\t" + d.error );
          continue;
        }
        for ( Hallazgo x : d.r.findings ) {
          h.println( d.corpus + "\t" + d.archivo + "\t" + x.rule + "\t" + x.severity.codigo() + "\t" + x.key + "\t" + limpio( x.message ) );
        }
        for ( NoEvaluado x : d.r.notEvaluated ) {
          n.println( d.corpus + "\t" + d.archivo + "\t" + x.rule + "\t" + ( x.step == null ? "" : x.step ) + "\t" + x.code + "\t"
            + limpio( x.reason ) );
        }
        StringBuilder pen = new StringBuilder();
        for ( Penalidad x : d.r.penalties ) {
          pen.append( pen.length() == 0 ? "" : " " ).append( x.rule ).append( '=' ).append( x.points );
        }
        p.println( d.corpus + "\t" + d.archivo + "\t" + d.r.kind + "\t" + d.r.score + "\t" + d.r.grade + "\t"
          + d.r.counts.get( "error" ) + "\t" + d.r.counts.get( "warning" ) + "\t" + d.r.counts.get( "info" ) + "\t"
          + d.r.suppressed.size() + "\t" + d.r.notEvaluated.size() + "\t" + d.r.ms + "\t" + pen );
      }
    }
    try ( PrintWriter w = pw( new File( dir, "resumen.txt" ) ) ) {
      for ( String l : resumen( docs ) ) {
        w.println( l );
        System.out.println( l );
      }
      w.println( "corpus en " + ms + " ms" );
    }
    File js = new File( dir, "json" );
    js.mkdirs();
    for ( Doc d : docs ) {
      if ( d.r != null && !d.corpus.equals( "samples" ) ) {
        try ( PrintWriter w = pw( new File( js, d.corpus + "_" + d.archivo.replace( '/', '_' ) + ".json" ) ) ) {
          w.println( RunMotor.json( LintJson.resultado( d.r ) ) );
        }
      }
    }
  }

  /** Por corpus: documentos, mediana, A/B/C/D; por regla y severidad: hallazgos/archivos. */
  static List<String> resumen( List<Doc> docs ) {
    List<String> out = new ArrayList<String>();
    Map<String, List<Doc>> por = new LinkedHashMap<String, List<Doc>>();
    for ( Doc d : docs ) {
      List<Doc> l = por.get( d.corpus );
      if ( l == null ) {
        l = new ArrayList<Doc>();
        por.put( d.corpus, l );
      }
      l.add( d );
    }
    for ( Map.Entry<String, List<Doc>> e : por.entrySet() ) {
      List<Integer> s = new ArrayList<Integer>();
      int[] b = new int[ 4 ];
      int fallos = 0;
      long ms = 0;
      long max = 0;
      for ( Doc d : e.getValue() ) {
        if ( d.r == null ) {
          fallos++;
          continue;
        }
        s.add( d.r.score );
        b[ "ABCD".indexOf( d.r.grade ) ]++;
        ms += d.r.ms;
        max = Math.max( max, d.r.ms );
      }
      Collections.sort( s );
      double med = s.isEmpty() ? 0 : s.size() % 2 == 1 ? s.get( s.size() / 2 )
        : ( s.get( s.size() / 2 - 1 ) + s.get( s.size() / 2 ) ) / 2.0;
      out.add( String.format( Locale.ROOT, "%-11s docs=%3d mediana=%5.1f min=%3d A/B/C/D=%d/%d/%d/%d sin_cargar=%d ms=%d (max %d)",
        e.getKey(), e.getValue().size(), med, s.isEmpty() ? 0 : s.get( 0 ), b[ 0 ], b[ 1 ], b[ 2 ], b[ 3 ], fallos, ms, max ) );
    }
    out.add( "" );
    for ( String l : conteos( docs ).values() ) {
      out.add( l );
    }
    return out;
  }

  /** regla severidad -> "regla severidad corpus=h/a ..." (lo que compara la línea base). */
  static Map<String, String> conteos( List<Doc> docs ) {
    Map<String, Map<String, int[]>> c = new TreeMap<String, Map<String, int[]>>();
    Map<String, Map<String, Set<String>>> a = new TreeMap<String, Map<String, Set<String>>>();
    for ( Doc d : docs ) {
      if ( d.r == null ) {
        continue;
      }
      for ( Hallazgo x : d.r.findings ) {
        String k = String.format( Locale.ROOT, "%-34s %-7s", x.rule, x.severity.codigo() );
        Map<String, int[]> m = c.computeIfAbsent( k, z -> new TreeMap<String, int[]>() );
        m.computeIfAbsent( d.corpus, z -> new int[ 1 ] )[ 0 ]++;
        a.computeIfAbsent( k, z -> new TreeMap<String, Set<String>>() ).computeIfAbsent( d.corpus, z -> new HashSet<String>() )
          .add( d.archivo );
      }
    }
    Map<String, String> out = new TreeMap<String, String>();
    for ( String k : c.keySet() ) {
      StringBuilder b = new StringBuilder( k );
      for ( Map.Entry<String, int[]> e : c.get( k ).entrySet() ) {
        b.append( ' ' ).append( e.getKey() ).append( '=' ).append( e.getValue()[ 0 ] ).append( '/' )
          .append( a.get( k ).get( e.getKey() ).size() );
      }
      out.put( k, b.toString() );
    }
    return out;
  }

  /** La línea base: conteos por regla y bandas por corpus. Si cambia algo, falla (rehacerla a propósito). */
  static void lineaBase( List<Doc> docs, File f, boolean escribir ) throws Exception {
    List<String> actual = new ArrayList<String>();
    for ( String l : resumen( docs ) ) {
      if ( !l.isEmpty() ) {
        actual.add( l.replaceAll( " ms=\\d+ \\(max \\d+\\)", "" ) );
      }
    }
    if ( escribir || !f.isFile() ) {
      try ( PrintWriter w = pw( f ) ) {
        w.println( "# Linea base del linter (tools/lint-corpus/correr.ps1). Se rehace con -EscribirBase cuando un cambio es a proposito." );
        for ( String l : actual ) {
          w.println( l );
        }
      }
      System.out.println( "linea base escrita: " + f );
      return;
    }
    List<String> base = new ArrayList<String>();
    for ( String l : Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 ) ) {
      if ( !l.startsWith( "#" ) && !l.trim().isEmpty() ) {
        base.add( l );
      }
    }
    pruebas++;
    if ( !base.equals( actual ) ) {
      Set<String> antes = new LinkedHashSet<String>( base );
      antes.removeAll( actual );
      Set<String> ahora = new LinkedHashSet<String>( actual );
      ahora.removeAll( base );
      falla( "la línea base cambió:\n  antes: " + String.join( "\n  antes: ", antes ) + "\n  ahora: " + String.join( "\n  ahora: ", ahora ) );
    }
  }

  static String limpio( String s ) {
    return s == null ? "" : s.replace( '\t', ' ' ).replace( '\n', ' ' ).replace( '\r', ' ' );
  }

  static PrintWriter pw( File f ) throws Exception {
    return new PrintWriter( new OutputStreamWriter( new FileOutputStream( f ), StandardCharsets.UTF_8 ) );
  }
}
