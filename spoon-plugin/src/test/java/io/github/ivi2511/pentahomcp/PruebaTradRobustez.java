package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.datagrid.DataGridMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Opciones;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;

/**
 * Barrido de robustez del traductor (0.8.1): cada step de PDI 9.4 con su configuración por defecto, entre un Data grid
 * con nombres de campo raros y un Dummy, se traduce a pandas y PySpark sin excepciones y el .py compila; y todas las
 * .ktr/.kjb de las pruebas y del ejemplo se traducen en los dos destinos y formatos (los .py compilan, los notebooks
 * son nbformat válidos).
 */
public class PruebaTradRobustez {

  private static final String[] CAMPOS = { "id", "nombre con espacio", "año", "it's \"x\"" };
  private static final String[] TIPOS = { "Integer", "String", "Number", "Date" };

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static TransMeta trans( String nombre, StepMeta medio ) {
    TransMeta tm = new TransMeta();
    tm.setName( nombre );
    DataGridMeta g = new DataGridMeta();
    int n = CAMPOS.length;
    g.allocate( n );
    g.setFieldName( CAMPOS.clone() );
    g.setFieldType( TIPOS.clone() );
    String[] fmt = new String[ n ];
    String[] vacio = new String[ n ];
    String[] dec = new String[ n ];
    int[] menos = new int[ n ];
    for ( int i = 0; i < n; i++ ) {
      fmt[ i ] = TIPOS[ i ].equals( "Date" ) ? "yyyy-MM-dd" : "";
      vacio[ i ] = "";
      dec[ i ] = ".";
      menos[ i ] = -1;
    }
    g.setFieldFormat( fmt );
    g.setDecimal( dec );
    g.setGroup( vacio.clone() );
    g.setCurrency( vacio.clone() );
    g.setFieldNullIf( vacio.clone() );
    g.setFieldLength( menos );
    g.setFieldPrecision( menos.clone() );
    g.setEmptyString( new boolean[ n ] );
    List<List<String>> filas = new ArrayList<>();
    filas.add( new ArrayList<>( Arrays.asList( "1", "a b", "1.5", "2024-01-02" ) ) );
    filas.add( new ArrayList<>( Arrays.asList( "2", "", "", "" ) ) );
    g.setDataLines( filas );
    StepMeta sg = new StepMeta( "DataGrid", "Datos", g );
    sg.setLocation( 50, 50 );
    tm.addStep( sg );
    StepMeta fin = new StepMeta( "Dummy", "Fin", new DummyTransMeta() );
    fin.setLocation( 450, 50 );
    tm.addStep( fin );
    if ( medio != null ) {
      medio.setLocation( 250, 50 );
      tm.addStep( medio );
      tm.addTransHop( new TransHopMeta( sg, medio ) );
      tm.addTransHop( new TransHopMeta( medio, fin ) );
    } else {
      tm.addTransHop( new TransHopMeta( sg, fin ) );
    }
    return tm;
  }

  /** Compila todos los .py y valida todos los .ipynb de la carpeta en una sola llamada a Python. */
  static void compilarTodo( File dir ) throws Exception {
    if ( PythonPrueba.python() == null ) {
      return;
    }
    String codigo = "import sys,glob,os,py_compile,json\n"
      + "mal=[]\n"
      + "for f in sorted(glob.glob(os.path.join(sys.argv[1],'**','*.py'),recursive=True)):\n"
      + "    try: py_compile.compile(f,doraise=True)\n"
      + "    except Exception as e: mal.append(str(e))\n"
      + "import nbformat\n"
      + "for f in sorted(glob.glob(os.path.join(sys.argv[1],'**','*.ipynb'),recursive=True)):\n"
      + "    try:\n"
      + "        nb=nbformat.read(f,as_version=4); nbformat.validate(nb)\n"
      + "        for i,c in enumerate(nb.cells):\n"
      + "            if c.cell_type=='code':\n"
      + "                src='\\n'.join(l for l in c.source.splitlines() if not l.lstrip().startswith(('%','!')))\n"
      + "                compile(src,f+'#'+str(i),'exec')\n"
      + "    except Exception as e: mal.append(f+': '+repr(e)[:300])\n"
      + "print('\\n'.join(mal)); sys.exit(1 if mal else 0)\n";
    File s = new File( dir, "_comprobar.txt" );
    Files.write( s.toPath(), codigo.getBytes( StandardCharsets.UTF_8 ) );
    PythonPrueba.Resultado r = PythonPrueba.correr( null, 600, PythonPrueba.python(), s.getAbsolutePath(),
      dir.getAbsolutePath() );
    igual( "", r.codigo == 0 ? "" : r.salida.trim(), "los .py compilan y los notebooks son válidos" );
  }

  /** Cada step registrado, con setDefault(), se traduce en los dos destinos sin excepción y con salida sensata. */
  public void pruebaTodosLosSteps() throws Exception {
    File dir = EntornoKettle.salida( "trad-robustez/steps" );
    List<String> errores = new ArrayList<>();
    int n = 0;
    for ( PluginInterface pl : PluginRegistry.getInstance().getPlugins( StepPluginType.class ) ) {
      String id = pl.getIds()[ 0 ];
      StepMetaInterface m;
      try {
        m = (StepMetaInterface) PluginRegistry.getInstance().loadClass( pl );
        m.setDefault();
      } catch ( Throwable e ) {
        continue; // el step ni siquiera se crea en modo headless: no es cosa del traductor
      }
      StepMeta s = new StepMeta( id, "Paso " + id, m );
      TransMeta t;
      try {
        t = trans( "r_" + id, s );
      } catch ( Throwable e ) {
        continue;
      }
      n++;
      for ( Destino d : Destino.values() ) {
        try {
          Programa p = TraductorTrans.traducir( t, d, "es" );
          igual( 3, p.bloques.size(), id + ": un bloque por step" );
          for ( Bloque b : p.bloques ) {
            cierto( b.estado != null, id + ": estado" );
          }
          String py = EscritorPy.escribir( p, new Opciones() );
          File f = new File( dir, d.name().toLowerCase() + "_" + id.replaceAll( "[^A-Za-z0-9_]", "_" ) + ".py" );
          Files.write( f.toPath(), py.getBytes( StandardCharsets.UTF_8 ) );
        } catch ( Throwable e ) {
          errores.add( id + " (" + d + "): " + e );
        }
      }
    }
    cierto( n > 150, "steps probados: " + n );
    igual( Collections.emptyList(), errores, "steps que revientan al traducir" );
    compilarTodo( dir );
  }

  private static void buscar( File f, List<File> l ) {
    File[] hijos = f.listFiles();
    if ( hijos == null ) {
      return;
    }
    Arrays.sort( hijos );
    for ( File h : hijos ) {
      if ( h.isDirectory() ) {
        buscar( h, l );
      } else if ( h.getName().endsWith( ".ktr" ) || h.getName().endsWith( ".kjb" ) ) {
        l.add( h );
      }
    }
  }

  /** Todas las .ktr/.kjb de las pruebas y del ejemplo, en pandas y PySpark, como .py y como notebook. */
  public void pruebaArchivosDelRepo() throws Exception {
    File salida = EntornoKettle.salida( "trad-robustez/archivos" );
    List<File> l = new ArrayList<>();
    File rec = EntornoKettle.recursos().getAbsoluteFile();
    buscar( rec, l );
    buscar( new File( rec.getParentFile().getParentFile().getParentFile().getParentFile(), "ejemplo" ), l );
    cierto( l.size() > 20, "archivos encontrados: " + l.size() );
    List<String> errores = new ArrayList<>();
    int i = 0;
    for ( File f : l ) {
      i++;
      org.pentaho.di.base.AbstractMeta meta;
      try {
        meta = f.getName().endsWith( ".ktr" ) ? new TransMeta( f.getAbsolutePath() )
          : new JobMeta( f.getAbsolutePath(), null );
      } catch ( Throwable e ) {
        continue; // no es una .ktr/.kjb que PDI cargue (p. ej. las de pruebas de errores)
      }
      String base = String.format( "%03d_", i ) + f.getName().replaceAll( "[^A-Za-z0-9_]", "_" );
      for ( Destino d : Destino.values() ) {
        for ( String formato : new String[] { "py", "ipynb" } ) {
          Opciones o = new Opciones();
          o.destino = d;
          o.formato = formato;
          File destino = new File( salida, base + "_" + d.name().toLowerCase() + "." + formato );
          try {
            TraducirTools.traducir( meta, o, destino, true, false );
          } catch ( Throwable e ) {
            errores.add( f.getPath() + " (" + d + ", " + formato + "): " + e );
          }
        }
      }
    }
    igual( Collections.emptyList(), errores, "archivos que revientan al traducir" );
    compilarTodo( salida );
  }

  private static void copiar( File de, File a ) throws Exception {
    a.mkdirs();
    File[] hijos = de.listFiles();
    if ( hijos == null ) {
      return;
    }
    for ( File h : hijos ) {
      if ( h.isDirectory() ) {
        copiar( h, new File( a, h.getName() ) );
      } else {
        Files.copy( h.toPath(), new File( a, h.getName() ).toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING );
      }
    }
  }

  private static void correr( File py, List<String> errores ) throws Exception {
    PythonPrueba.Resultado r = PythonPrueba.correr( py.getParentFile(), 300, PythonPrueba.python(),
      py.getAbsolutePath() );
    if ( r.codigo != 0 ) {
      String sal = r.salida.trim();
      errores.add( py.getName() + ": " + sal.substring( Math.max( 0, sal.length() - 600 ) ) );
    }
  }

  /**
   * Los scripts pandas del ejemplo que sólo leen archivos locales (y la SQLite de ../salida) corren de verdad, sobre
   * una copia: construido-en-vivo (su job, 01..05) y entregas-ejemplo (menos la de rutas absolutas, que apunta fuera).
   */
  public void pruebaPandasDelEjemploCorre() throws Exception {
    if ( PythonPrueba.python() == null ) {
      return;
    }
    File ej = new File( EntornoKettle.recursos().getAbsoluteFile().getParentFile().getParentFile().getParentFile()
      .getParentFile(), "ejemplo" );
    File copia = EntornoKettle.salida( "trad-robustez/corre" );
    for ( String c : new String[] { "construido-en-vivo", "entregas-ejemplo", "datos" } ) {
      copiar( new File( ej, c ), new File( copia, c ) );
    }
    new File( copia, "salida" ).mkdirs();
    // las entregas leen datos/ junto a ellas (como en la carpeta del alumno)
    copiar( new File( ej, "datos" ), new File( copia, "entregas-ejemplo/datos" ) );
    List<String> errores = new ArrayList<>();
    // construido-en-vivo: por su job (crea las tablas de la SQLite antes de cargarlas)
    File cv = new File( copia, "construido-en-vivo" );
    Opciones oj = new Opciones();
    oj.formato = "py";
    File job = new File( cv, "job_carga_dw.py" );
    TraducirTools.traducir( new JobMeta( new File( cv, "job_carga_dw.kjb" ).getAbsolutePath(), null ), oj, job, true,
      false );
    correr( job, errores );
    File[] ktrs = new File( copia, "entregas-ejemplo" ).listFiles( ( d, n ) -> n.endsWith( ".ktr" )
      && !n.contains( "absolutas" ) );
    Arrays.sort( ktrs );
    for ( File k : ktrs ) {
      Opciones o = new Opciones();
      o.formato = "py";
      File py = new File( k.getParentFile(), k.getName().replace( ".ktr", ".py" ) );
      TraducirTools.traducir( new TransMeta( k.getAbsolutePath() ), o, py, true, false );
      correr( py, errores );
    }
    igual( Collections.emptyList(), errores, "scripts pandas que fallan al correr" );
  }
}
