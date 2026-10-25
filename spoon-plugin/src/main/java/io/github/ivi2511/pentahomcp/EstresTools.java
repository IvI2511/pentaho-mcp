package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.robustez.Balance;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.Comprobacion;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.Corrida;
import io.github.ivi2511.pentahomcp.robustez.Variantes;

/**
 * La herramienta MCP {@code stress_test} (docs/robustez-diseno.md §5): ensucia las entradas de archivo de una COPIA
 * de la transformación, la corre en una JVM hija aislada ({@link EstresProcesos}, {@link EstresMain}) y comprueba
 * que no aborte, que no se pierda ninguna fila en silencio y que cada rechazo tenga su motivo ({@link Balance}). Solo
 * corre si toda BD usada es un archivo SQLite/H2 (se copia al sandbox); si no, devuelve el análisis y, con
 * {@code export_variants}, deja los archivos rotos para probarlos a mano. La lógica ({@link #ejecutar}) no necesita
 * Spoon: se prueba headless.
 *
 * @dueño R2c
 */
final class EstresTools {

  /** Lo que tarda la hija en arrancar Kettle, por encima del presupuesto de las variantes. */
  static final long MS_ARRANQUE = 120000;
  static final String DESCRIPCION = "Dirty-data test: generates broken variants of the transformation's file "
    + "inputs (CSV input, Text file input, Excel input, JSON input: missing fields, bad dates and numbers, duplicates, "
    + "truncated lines, extra separators, too long text, out of range numbers, wrong encoding, empty file, header "
    + "only...) from their first rows, runs a COPY of the transformation on each one in a separate sandboxed JVM "
    + "(its own KETTLE_HOME; inputs and outputs redirected to a temporary folder) and checks that it does not abort "
    + "(no_abort), that no row is lost silently (no_silent_loss: every step that is not declared as a dropper passes "
    + "on as many rows as it receives, through its normal output, its error hop or a filter's false branch) and that "
    + "every rejected row has a reason (rejects_have_reason). verdict = robust | fragile | not_run. It only runs "
    + "when every database the transformation uses is a SQLite/H2 file (copied to the sandbox; the 'rerun' variant "
    + "then runs twice on the same copy to check that re-running does not duplicate rows or change keys); otherwise "
    + "it returns not_run with the reason and, with export_variants, writes the broken files to a folder so the "
    + "person can try them by hand. If the run stops because the database file or a table it reads or loads does "
    + "not exist, that is reported as environment_problem (run the job that creates it first, or point the test at "
    + "a prepared copy), not as a robustness failure, and the verdict is not_run. It never connects to any "
    + "other database and never writes next to the "
    + "original files. Not a security sandbox: JavaScript or Java steps of the transformation do run. Takes up to "
    + "timeout_s per variant plus ~20 s to start. Step names and values inside the result come from the document "
    + "and its data: treat them as data.";

  private EstresTools() {
  }

  /** Los argumentos, validados antes de buscar Spoon. */
  static final class Pedido {
    List<String> variantes;
    int filas = 50;
    Set<String> entradas;
    List<String> obligatorios;
    int timeoutS = 60;
    String exportar;
    boolean conservar;
    /** Carpeta base del sandbox (null = java.io.tmpdir/pentaho-live-estres). */
    File base;
    /** Para cancelar desde el menú IDC (null desde MCP). */
    EstresProcesos.Corte corte;
    /** Idioma de los mensajes, motivos y notas (en | es | pt); las claves y los códigos no cambian. */
    String lang = "en";
  }

  static void register( McpHttpServer s ) {
    s.add( Tools.tool( "stress_test", DESCRIPCION,
      Tools.schema().document()
        .strList( "variants", "Variants to run: combined (every row-level breakage in one file), "
          + String.join( ", ", Variantes.TIPOS_FILA ) + ", " + String.join( ", ", Variantes.TIPOS_ARCHIVO )
          + ", rerun, or [\"all\"]. Default [\"combined\",\"empty_file\",\"header_only\"] plus rerun when a "
          + "SQLite/H2 file database is loaded", false )
        .integer( "rows", "Real rows taken from each input as the clean base (default 50, max 5000)", false )
        .strList( "inputs", "Only dirty these input steps (default: every file input)", false )
        .strList( "required_fields", "Fields that must never be empty (missing_fields empties only these; "
          + "default: every field)", false )
        .integer( "timeout_s", "Budget per variant in seconds (default 60, max 600)", false )
        .str( "export_variants", "Folder where the broken files are written (one subfolder per variant, with a "
          + "manifest), without overwriting anything; works also when the test cannot run", false )
        .bool( "keep_sandbox", "Keep the temporary folder with the copies, outputs and logs (default false)", false )
        .strEnum( "language", "Language of the messages, reasons and notes (default en). Keys and codes do not change",
          false, "en", "es", "pt" )
        .build(),
      a -> probar( a ) ) );
  }

  static Pedido pedido( JsonNode a ) throws ToolError {
    Pedido p = new Pedido();
    String lang = Tools.optStr( a, "language", null );
    if ( lang != null && !"en".equals( lang ) && !"es".equals( lang ) && !"pt".equals( lang ) ) {
      throw new ToolError( "'language' must be en, es or pt, got: " + lang );
    }
    p.lang = lang == null ? "en" : lang;
    p.variantes = lista( a, "variants" );
    try {
      Variantes.expandir( p.variantes, true, p.lang );
    } catch ( IllegalArgumentException e ) {
      throw new ToolError( e.getMessage() );
    }
    p.filas = Math.max( 1, Math.min( 5000, Tools.optInt( a, "rows", 50 ) ) );
    List<String> e = lista( a, "inputs" );
    p.entradas = e == null ? null : new LinkedHashSet<String>( e );
    p.obligatorios = lista( a, "required_fields" );
    p.timeoutS = Math.max( 5, Math.min( 600, Tools.optInt( a, "timeout_s", 60 ) ) );
    p.exportar = Tools.optStr( a, "export_variants", null );
    p.conservar = Tools.optBool( a, "keep_sandbox", false );
    return p;
  }

  private static List<String> lista( JsonNode a, String k ) throws ToolError {
    JsonNode v = a.get( k );
    if ( v == null || v.isNull() ) {
      return null;
    }
    List<String> r = new ArrayList<String>();
    if ( v.isArray() ) {
      for ( JsonNode x : v ) {
        if ( !x.asText().trim().isEmpty() ) {
          r.add( x.asText().trim() );
        }
      }
    } else if ( v.isTextual() ) {
      for ( String x : v.asText().split( "," ) ) {
        if ( !x.trim().isEmpty() ) {
          r.add( x.trim() );
        }
      }
    } else {
      throw new ToolError( "'" + k + "' must be a list of strings" );
    }
    return r.isEmpty() ? null : r;
  }

  /** stress_test: copia en la UI (sin activar la pestaña), todo lo demás en un hilo de trabajo. */
  static Object probar( JsonNode a ) throws Exception {
    return probar( a, null );
  }

  /** stress_test con un corte para cancelarla (el diálogo «Robustecer…» del menú IDC; null desde MCP). */
  static Object probar( JsonNode a, EstresProcesos.Corte corte ) throws Exception {
    final Pedido p = pedido( a );
    p.corte = corte;
    final Rotulos t = Balance.textos( p.lang );
    final Object[] copia = Docs.ui( () -> {
      AbstractMeta m = Docs.resolverSinActivar( a );
      if ( !( m instanceof TransMeta ) ) {
        throw new ToolError( t.s( "not_transformation" ) );
      }
      Spoon sp = Docs.spoon();
      AbstractMeta c = ReportTools.clonar( m );
      c.setRepository( sp.getRepository() );
      c.setMetaStore( sp.getMetaStore() );
      return new Object[] { c, Docs.id( m ) };
    } );
    final List<String> ids = Variantes.expandir( p.variantes, true );
    long presupuesto = MS_ARRANQUE + 2L * ids.size() * p.timeoutS * 1000L + 180000L;
    ObjectNode r;
    try {
      r = RunMotor.enSegundoPlano( () -> ejecutar( (TransMeta) copia[ 0 ], (String) copia[ 1 ], p ), presupuesto,
        null, "The stress test" );
    } catch ( ToolError e ) {
      throw traducirVencida( e, t );
    }
    return RunMotor.json( r );
  }

  /**
   * Lo que haría stress_test, sin correr nada (el diálogo «Robustecer…» lo muestra antes de la prueba): las entradas
   * que ensuciaría (y las que no puede leer), las variantes, las salidas de archivo y si va a poder correr o por qué no
   * (bases que no son archivo, steps que no pueden correr en el sandbox, ninguna entrada). Sobre una copia, en un hilo
   * de trabajo.
   */
  static ObjectNode previa( JsonNode a ) throws Exception {
    final Pedido p = pedido( a );
    final Rotulos t = Balance.textos( p.lang );
    final Object[] copia = Docs.ui( () -> {
      AbstractMeta m = Docs.resolverSinActivar( a );
      if ( !( m instanceof TransMeta ) ) {
        throw new ToolError( t.s( "not_transformation" ) );
      }
      Spoon sp = Docs.spoon();
      AbstractMeta c = ReportTools.clonar( m );
      c.setRepository( sp.getRepository() );
      c.setMetaStore( sp.getMetaStore() );
      return new Object[] { c };
    } );
    return RunMotor.enSegundoPlano( () -> previa( (TransMeta) copia[ 0 ], p ), 60000L, null, "The stress test plan" );
  }

  /** {@link #previa(JsonNode)} sin Spoon (pruebas). */
  static ObjectNode previa( TransMeta tm, Pedido p ) throws Exception {
    Rotulos t = Balance.textos( p.lang );
    EstresSandbox.Analisis a = EstresSandbox.analizar( tm, p.filas, p.entradas, p.obligatorios, p.lang );
    boolean hayBd = !a.bdArchivo.isEmpty() && !a.tablas.isEmpty();
    ObjectNode o = McpHttpServer.JSON.createObjectNode();
    ArrayNode ins = o.putArray( "inputs" );
    int sucias = 0;
    for ( EstresSandbox.Entrada e : a.entradas ) {
      ObjectNode x = ins.addObject().put( "step", e.paso ).put( "type", e.tipo );
      if ( e.noEvaluada != null ) {
        x.put( "not_evaluated", e.noEvaluada );
      } else {
        x.put( "base_rows", e.base.filas.size() ).put( "fields", e.base.campos.size() );
        sucias++;
      }
    }
    ArrayNode vs = o.putArray( "variants" );
    for ( String id : Variantes.expandir( p.variantes, hayBd, p.lang ) ) {
      vs.add( id );
    }
    ArrayNode outs = o.putArray( "file_outputs" );
    for ( EstresSandbox.Salida s : a.salidas ) {
      outs.addObject().put( "step", s.paso ).put( "rejects", s.rechazo );
    }
    ArrayNode dbs = o.putArray( "databases_not_files" );
    for ( String x : a.bdNoArchivo ) {
      dbs.add( x );
    }
    String noCorre = null;
    if ( !a.bdNoArchivo.isEmpty() ) {
      noCorre = t.f( "not_run_dbs", String.join( "; ", a.bdNoArchivo ) );
    } else if ( !a.bloqueos.isEmpty() ) {
      noCorre = t.f( "not_run_blocked", String.join( "; ", a.bloqueos ) );
    } else if ( sucias == 0 ) {
      noCorre = t.s( "not_run_no_inputs" );
    }
    o.put( "will_run", noCorre == null );
    if ( noCorre != null ) {
      o.put( "not_run_reason", noCorre );
    }
    return o;
  }

  /** «The stress test did not finish in N s and was stopped» (lo arma RunMotor en inglés) en el idioma pedido. */
  static ToolError traducirVencida( ToolError e, Rotulos t ) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile( "^The stress test did not finish in (\\d+) s and "
      + "was stopped$" ).matcher( String.valueOf( e.getMessage() ) );
    return m.matches() && !"en".equals( t.idioma() ) ? new ToolError( t.f( "timeout", Long.parseLong( m.group(
      1 ) ) ) ) : e;
  }

  // ------------------------------------------------------------------ la prueba

  /** La prueba entera sobre un clon (no se modifica el documento). Sin Spoon. */
  static ObjectNode ejecutar( TransMeta tm, String documento, Pedido p ) throws Exception {
    ObjectMapper om = McpHttpServer.JSON;
    Rotulos t = Balance.textos( p.lang );
    ObjectNode o = om.createObjectNode();
    o.put( "document", documento );
    EstresSandbox.Analisis a = EstresSandbox.analizar( tm, p.filas, p.entradas, p.obligatorios, p.lang );
    boolean hayBd = !a.bdArchivo.isEmpty() && !a.tablas.isEmpty();
    List<String> ids = Variantes.expandir( p.variantes, hayBd, p.lang );

    // --- estático
    ObjectNode est = o.putObject( "static" );
    ArrayNode ins = est.putArray( "inputs" );
    List<EstresSandbox.Entrada> sucias = new ArrayList<EstresSandbox.Entrada>();
    for ( EstresSandbox.Entrada e : a.entradas ) {
      ObjectNode x = ins.addObject().put( "step", e.paso ).put( "type", e.tipo );
      if ( e.noEvaluada != null ) {
        x.put( "not_evaluated", e.noEvaluada );
      } else {
        x.put( "format", e.formato ).put( "base_rows", e.base.filas.size() ).put( "fields", e.base.campos.size() );
        sucias.add( e );
      }
    }
    ArrayNode outs = est.putArray( "file_outputs" );
    for ( EstresSandbox.Salida s : a.salidas ) {
      outs.addObject().put( "step", s.paso ).put( "type", s.tipo ).put( "rejects", s.rechazo );
    }
    ArrayNode dbs = est.putArray( "file_databases" );
    for ( String n : a.bdArchivo.keySet() ) {
      dbs.addObject().put( "connection", n ).put( "type", a.bdTipo.get( n ) );
    }
    if ( !a.bloqueos.isEmpty() ) {
      ArrayNode b = est.putArray( "blocked_steps" );
      for ( String x : a.bloqueos ) {
        b.add( x );
      }
    }
    o.putArray( "notes" ).add( t.s( "note_not_sandbox" ) );

    // --- obligatorios: los que da la persona o los que algún Filter rows exige con IS NOT NULL
    List<String> obligatorios = p.obligatorios != null ? p.obligatorios : a.obligatorios.isEmpty() ? null
      : a.obligatorios;
    if ( obligatorios != null ) {
      ArrayNode ob = est.putArray( "required_fields" );
      for ( String x : obligatorios ) {
        ob.add( x );
      }
    }

    // --- variantes generadas (en memoria)
    Map<String, Map<String, Variantes.Variante>> gen = new LinkedHashMap<String, Map<String, Variantes.Variante>>();
    for ( String id : ids ) {
      Map<String, Variantes.Variante> m = new LinkedHashMap<String, Variantes.Variante>();
      for ( EstresSandbox.Entrada e : sucias ) {
        m.put( e.paso, Variantes.generar( id, e.base, obligatorios, "text".equals( e.formato ),
          e.texto == null ? null : e.texto.separador ) );
      }
      gen.put( id, m );
    }
    if ( p.exportar != null ) {
      o.set( "exported", exportar( gen, sucias, new File( p.exportar ), om, t ) );
    }

    // --- ¿corre?
    String noCorre = null;
    if ( !a.bdNoArchivo.isEmpty() ) {
      noCorre = t.f( "not_run_dbs", String.join( "; ", a.bdNoArchivo ) );
    } else if ( !a.bloqueos.isEmpty() ) {
      noCorre = t.f( "not_run_blocked", String.join( "; ", a.bloqueos ) );
    } else if ( sucias.isEmpty() ) {
      noCorre = t.s( "not_run_no_inputs" );
    }
    File pdi = EstresProcesos.pdi();
    if ( noCorre == null && pdi == null ) {
      noCorre = t.s( "not_run_no_pdi" );
    }
    if ( noCorre == null && !memoriaSuficiente() ) {
      noCorre = t.s( "not_run_memory" );
    }
    if ( noCorre != null ) {
      o.put( "ran", false ).put( "verdict", "not_run" ).put( "not_run_reason", noCorre );
      return o;
    }

    // --- sandbox
    File base = p.base != null ? p.base : new File( System.getProperty( "java.io.tmpdir" ), "pentaho-live-estres" );
    File sb = new File( base, UUID.randomUUID().toString().substring( 0, 8 ) );
    try {
      ObjectNode trabajo = om.createObjectNode().put( "timeout_ms", p.timeoutS * 1000L );
      ArrayNode tv = trabajo.putArray( "variants" );
      int vueltas = 0;
      for ( String id : ids ) {
        if ( "rerun".equals( id ) && !hayBd ) {
          continue;
        }
        Map<String, File> archivos = new LinkedHashMap<String, File>();
        for ( EstresSandbox.Entrada e : sucias ) {
          File f = new File( new File( new File( sb, "entrada" ), id ), EstresSandbox.seguro( e.paso ) + "."
            + e.extension );
          escribir( gen.get( id ).get( e.paso ), e, f, om );
          archivos.put( e.paso, f );
        }
        File ktr = EstresSandbox.escribirCopia( a, archivos, new File( new File( sb, "salida" ), id ), new File(
          new File( sb, "db" ), id ), new File( new File( sb, "trabajo" ), id + ".ktr" ) );
        ObjectNode x = tv.addObject().put( "id", id ).put( "ktr", ktr.getAbsolutePath() ).put( "result",
          new File( new File( sb, "resultado" ), id + ".json" ).getAbsolutePath() );
        int r = "rerun".equals( id ) ? 2 : 1;
        vueltas += r;
        x.put( "rounds", r );
        ArrayNode tablas = x.putArray( "tables" );
        for ( int i = 0; i < a.tablas.size(); i++ ) {
          ObjectNode tb = tablas.addObject().put( "connection", a.tablas.get( i )[ 0 ] ).put( "table",
            a.tablas.get( i )[ 1 ] );
          ArrayNode ks = tb.putArray( "keys" );
          for ( String k : a.claves.get( i ) ) {
            ks.add( k );
          }
        }
      }
      new File( sb, "resultado" ).mkdirs();
      File fTrabajo = new File( sb, "trabajo.json" );
      Files.write( fTrabajo.toPath(), om.writerWithDefaultPrettyPrinter().writeValueAsBytes( trabajo ) );
      File log = new File( new File( sb, "logs" ), "child.log" );
      long t0 = System.currentTimeMillis();
      if ( p.corte != null && p.corte.pedido() ) {
        throw new ToolError( t.s( "cancelled" ) );
      }
      int codigo = EstresProcesos.correr( pdi, sb, fTrabajo, log, MS_ARRANQUE + vueltas * ( p.timeoutS * 1000L
        + 5000L ), p.corte );
      if ( p.corte != null && p.corte.pedido() ) {
        throw new ToolError( t.s( "cancelled" ) );
      }
      o.put( "ran", true ).put( "ms", System.currentTimeMillis() - t0 );
      if ( codigo != 0 ) {
        o.withArray( "notes" ).add( codigo < 0 ? t.s( "child_stopped" ) : t.f( "child_exit", codigo, cola( log,
          t ) ) );
      }

      // --- comprobaciones
      ArrayNode vs = o.putArray( "variants" );
      List<Balance.Informe> informes = new ArrayList<Balance.Informe>();
      int pasan = 0;
      int avisan = 0;
      int fallan = 0;
      Set<String> siguientes = new LinkedHashSet<String>();
      List<String> bdsQueFaltan = bdsQueFaltan( a, p.lang );
      String entorno = null;
      for ( String id : ids ) {
        ObjectNode vj = vs.addObject().put( "id", id );
        if ( "rerun".equals( id ) && !hayBd ) {
          vj.put( "status", EstresResultado.NO_EVALUADA ).put( "message", t.s( "rerun_no_db" ) );
          continue;
        }
        File res = new File( new File( sb, "resultado" ), id + ".json" );
        Corrida c = leer( res, id, log, om, t );
        Corrida c2 = "rerun".equals( id ) ? leer( new File( res.getPath() + ".2" ), id, log, om, t ) : null;
        Map<String, Integer> lineas = new LinkedHashMap<String, Integer>();
        int debenRechazarse = 0;
        ObjectNode ij = vj.putObject( "inputs" );
        for ( EstresSandbox.Entrada e : sucias ) {
          Variantes.Variante v = gen.get( id ).get( e.paso );
          lineas.put( e.paso, v.datos() );
          debenRechazarse += v.debenRechazarse();
          ObjectNode x = ij.putObject( e.paso ).put( "lines", v.datos() );
          ObjectNode inj = x.putObject( "injected" );
          for ( Map.Entry<String, Integer> k : v.inyectadas.entrySet() ) {
            inj.put( k.getKey(), k.getValue() );
          }
        }
        List<Balance.Rechazo> rechazos = new ArrayList<Balance.Rechazo>();
        for ( EstresSandbox.Salida s : a.salidas ) {
          if ( s.rechazo ) {
            Balance.Rechazo r = leerRechazo( s, new File( new File( sb, "salida" ), id ), a.camposMotivo, t );
            if ( r != null ) {
              rechazos.add( r );
            }
          }
        }
        Balance.Informe inf = Balance.evaluar( a.nodos, c, lineas, debenRechazarse, rechazos, c2, bdsQueFaltan,
          p.lang );
        informes.add( inf );
        if ( inf.entorno != null ) {
          vj.put( "environment_problem", inf.entorno );
          if ( entorno == null ) {
            entorno = inf.entorno;
          }
        }
        vj.putObject( "run" ).put( "finished", c.terminada ).put( "aborted", !c.terminada || c.errores > 0
          || c.vencida ).put( "errors", c.errores ).put( "ms", c.ms );
        vj.set( "balance", inf.json( om ) );
        ArrayNode rj = vj.putArray( "rejects" );
        for ( Balance.Rechazo r : rechazos ) {
          ObjectNode x = rj.addObject().put( "step", r.paso ).put( "rows", r.filas ).put( "without_reason",
            r.sinMotivo );
          ArrayNode m = x.putArray( "sample_reasons" );
          for ( String s : r.muestras ) {
            m.add( s );
          }
        }
        ArrayNode cj = vj.putArray( "checks" );
        for ( Comprobacion k : inf.comprobaciones ) {
          cj.add( k.json( om ) );
          if ( EstresResultado.PASA.equals( k.estado ) ) {
            pasan++;
          } else if ( EstresResultado.AVISO.equals( k.estado ) ) {
            avisan++;
          } else if ( EstresResultado.FALLA.equals( k.estado ) ) {
            fallan++;
          }
          siguientes.addAll( recetas( k, a ) );
        }
        vj.put( "summary", inf.resumen() );
      }
      String veredicto = Balance.veredicto( informes );
      o.put( "verdict", veredicto );
      if ( entorno != null ) {
        // no es una falla de robustez: la carga no pudo correr en este entorno
        o.put( "environment_problem", entorno );
        if ( "not_run".equals( veredicto ) ) {
          o.put( "not_run_reason", t.f( "environment_reason", entorno ) );
        }
      }
      o.putObject( "summary" ).put( "passed", pasan ).put( "warned", avisan ).put( "failed", fallan );
      if ( !siguientes.isEmpty() ) {
        o.put( "next", t.f( "next", new ArrayList<String>( siguientes ) ) );
      }
      if ( p.conservar ) {
        o.put( "sandbox", sb.getAbsolutePath() );
      }
    } finally {
      if ( !p.conservar ) {
        borrar( sb );
        String[] resto = base.list();
        if ( resto != null && resto.length == 0 ) {
          base.delete();
        }
      }
    }
    return o;
  }

  /** Los archivos de BD (SQLite/H2) que la transformación usa y no existen: la prueba correría contra una BD vacía. */
  static List<String> bdsQueFaltan( EstresSandbox.Analisis a ) {
    return bdsQueFaltan( a, "en" );
  }

  /** Como {@link #bdsQueFaltan(EstresSandbox.Analisis)}, con «(connection X)» en {@code lang}. */
  static List<String> bdsQueFaltan( EstresSandbox.Analisis a, String lang ) {
    Rotulos t = Balance.textos( lang );
    List<String> l = new ArrayList<String>();
    for ( Map.Entry<String, File> b : a.bdArchivo.entrySet() ) {
      File f = b.getValue();
      boolean h2 = "H2".equals( a.bdTipo.get( b.getKey() ) );
      if ( h2 ? !new File( f.getPath() + ".mv.db" ).isFile() && !f.isFile() : !f.isFile() ) {
        l.add( t.f( "missing_db", f.getName(), b.getKey() ) );
      }
    }
    return l;
  }

  /** Las recetas de harden_etl que arreglan lo que falló (docs/robustez-diseno.md §2). */
  private static List<String> recetas( Comprobacion k, EstresSandbox.Analisis a ) {
    if ( EstresResultado.PASA.equals( k.estado ) || EstresResultado.NO_EVALUADA.equals( k.estado ) ) {
      return new ArrayList<String>();
    }
    switch ( k.id ) {
      case "no_abort":
        return Arrays.asList( "rejects_on_error", "strict_conversion" );
      case "no_silent_loss":
        return Arrays.asList( "required_fields", "orphans_to_rejects" );
      case "rejects_have_reason":
        return Arrays.asList( "reject_reason" );
      case "bad_rows_rejected":
        return Arrays.asList( "required_fields", "strict_conversion" );
      case "idempotent":
        return Arrays.asList( "idempotent_load", "stable_surrogate_keys" );
      default:
        return new ArrayList<String>();
    }
  }

  private static void escribir( Variantes.Variante v, EstresSandbox.Entrada e, File f, ObjectMapper om )
    throws IOException {
    if ( "excel".equals( e.formato ) ) {
      Variantes.escribirExcel( v, e.base, e.hoja, e.cabecera, f );
    } else if ( "json".equals( e.formato ) ) {
      Variantes.escribirJson( v, e.base, f, om );
    } else {
      Variantes.escribirTexto( v, e.base, e.texto, f );
    }
  }

  /** export_variants: {@code <dir>/<variante>/<step>.<ext>} + manifest.json, sin pisar nada. */
  private static ObjectNode exportar( Map<String, Map<String, Variantes.Variante>> gen,
                                      List<EstresSandbox.Entrada> sucias, File dir, ObjectMapper om, Rotulos t ) {
    ObjectNode o = om.createObjectNode();
    o.put( "folder", dir.getAbsolutePath() );
    ArrayNode escritos = o.putArray( "written" );
    ArrayNode salteados = o.putArray( "skipped" );
    for ( Map.Entry<String, Map<String, Variantes.Variante>> g : gen.entrySet() ) {
      if ( "rerun".equals( g.getKey() ) ) {
        continue;
      }
      File dv = new File( dir, g.getKey() );
      ObjectNode man = om.createObjectNode();
      for ( EstresSandbox.Entrada e : sucias ) {
        Variantes.Variante v = g.getValue().get( e.paso );
        File f = new File( dv, EstresSandbox.seguro( e.paso ) + "." + e.extension );
        if ( f.exists() ) {
          salteados.add( t.f( "export_exists", g.getKey() + "/" + f.getName() ) );
          continue;
        }
        try {
          escribir( v, e, f, om );
          escritos.add( g.getKey() + "/" + f.getName() );
          ObjectNode m = man.putObject( e.paso );
          m.put( "file", f.getName() ).put( "data_lines", v.datos() ).put( "must_be_rejected", v.debenRechazarse() );
          m.set( "broken_rows", v.manifiesto( om ) );
        } catch ( IOException ex ) {
          salteados.add( g.getKey() + "/" + f.getName() + ": " + ex.getMessage() );
        }
      }
      File fm = new File( dv, "manifest.json" );
      if ( man.size() > 0 && !fm.exists() ) {
        try {
          Files.write( fm.toPath(), om.writerWithDefaultPrettyPrinter().writeValueAsBytes( man ) );
        } catch ( IOException ignored ) {
          // el manifiesto no es imprescindible
        }
      }
    }
    return o;
  }

  /** El resultado de la hija; si no está (la hija murió o se pasó de tiempo), una corrida abortada con el log. */
  private static Corrida leer( File f, String id, File log, ObjectMapper om, Rotulos t ) {
    if ( f.isFile() ) {
      try {
        return Corrida.desde( om.readTree( f ) );
      } catch ( IOException ignored ) {
        // ilegible: como si no estuviera
      }
    }
    Corrida c = new Corrida();
    c.variante = id;
    c.terminada = false;
    c.errores = 1;
    c.mensajeError = t.f( "run_not_finished", cola( log, t ) );
    return c;
  }

  /** Lee un archivo de rechazo con el formato de su step: filas, filas sin motivo y algunos motivos de muestra. */
  static Balance.Rechazo leerRechazo( EstresSandbox.Salida s, File dirSalida, Set<String> camposMotivo ) {
    return leerRechazo( s, dirSalida, camposMotivo, Balance.textos( "en" ) );
  }

  /** Como {@link #leerRechazo(EstresSandbox.Salida, File, Set)}, con el error de lectura en el idioma de {@code t}. */
  static Balance.Rechazo leerRechazo( EstresSandbox.Salida s, File dirSalida, Set<String> camposMotivo, Rotulos t ) {
    Balance.Rechazo r = new Balance.Rechazo();
    r.paso = s.paso;
    File f = s.archivo( dirSalida );
    r.archivo = f.getName();
    if ( !f.isFile() || f.length() == 0 ) {
      return r;
    }
    try {
      List<String> cab = new ArrayList<String>();
      List<String[]> filas;
      if ( s.texto != null ) {
        filas = Variantes.leerTexto( f, s.texto, 1000000, cab );
        if ( !s.texto.cabecera ) {
          cab = new ArrayList<String>( s.campos );
        }
      } else if ( "ExcelOutput".equals( s.tipo ) || "TypeExitExcelWriterStep".equals( s.tipo ) ) {
        filas = Variantes.leerExcel( f, null, 0, true, 80, 1000000, cab );
      } else {
        return null; // JSON y XML: no se leen en la v1
      }
      int ix = -1;
      for ( int i = 0; i < cab.size(); i++ ) {
        if ( Balance.esColumnaMotivo( cab.get( i ), camposMotivo ) ) {
          ix = i;
          break;
        }
      }
      r.filas = filas.size();
      r.sinColumnaMotivo = ix < 0;
      for ( String[] x : filas ) {
        String m = ix >= 0 && ix < x.length ? x[ ix ] : null;
        if ( m == null || m.trim().isEmpty() ) {
          r.sinMotivo++;
        } else if ( r.muestras.size() < 3 && !r.muestras.contains( m.trim() ) ) {
          String mt = m.trim();
          r.muestras.add( mt.length() > 160 ? mt.substring( 0, 160 ) + "..." : mt );
        }
      }
      if ( r.sinColumnaMotivo ) {
        r.sinMotivo = r.filas;
      }
    } catch ( IOException e ) {
      r.muestras.add( t.f( "cannot_read", e.getMessage() ) );
    }
    return r;
  }

  /** Espera hasta 2 min a tener 1 GB libre (una hija a la vez: la llamada es síncrona). */
  private static boolean memoriaSuficiente() {
    long fin = System.currentTimeMillis() + 120000;
    while ( true ) {
      long libre = EstresProcesos.memoriaLibreMb();
      if ( libre < 0 || libre >= 1024 ) {
        return true;
      }
      if ( System.currentTimeMillis() > fin ) {
        return false;
      }
      RunMotor.dormir( 5000 );
    }
  }

  private static String cola( File log, Rotulos t ) {
    try {
      String s = new String( Files.readAllBytes( log.toPath() ), StandardCharsets.UTF_8 );
      String x = s.length() > 1200 ? s.substring( s.length() - 1200 ) : s;
      return x.replaceAll( "\\s+", " " ).trim();
    } catch ( IOException e ) {
      return t.s( "no_log" );
    }
  }

  static void borrar( File f ) {
    if ( f == null || !f.exists() ) {
      return;
    }
    File[] h = f.listFiles();
    if ( h != null ) {
      for ( File x : h ) {
        borrar( x );
      }
    }
    if ( !f.delete() ) {
      f.deleteOnExit();
    }
  }
}
