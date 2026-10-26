package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.i18n.LanguageChoice;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * La JVM de las pruebas (0.7.0, P1): {@code java ... PruebasMain trabajo.json}. La lanza {@link PruebasProcesos} con
 * {@link CorrectorProcesos#lanzarHija} (KETTLE_HOME, user.home, metastore y tmp propios y vacios; locale, zona y
 * encoding del {@code environment} de las pruebas), como un worker del corrector: nada de una prueba corre en Spoon.
 * Inicia Kettle una vez y hace las tareas en orden ({@code run}: {@link PruebasMotor#correr}; {@code create}:
 * {@link PruebasMotor#crear}); cada resultado se escribe de forma atomica apenas termina, asi el padre muestra el
 * progreso. Si existe {@code <trabajo>.cancel}, la tarea en curso se corta y las que faltan no corren.
 * <p>
 * Contraseñas de las bases de prueba: por el entorno del proceso ({@value #VARIABLE_CLAVE}{@code <tarea>_<base>}),
 * nunca por un archivo; el resultado no las lleva.
 *
 * @dueño P1 (0.7.0)
 */
public final class PruebasMain {

  public static final String FORMATO_TRABAJO = "pentaho-live-test-job/1";
  /** Prefijo de las variables de entorno con las contraseñas: + indice de tarea + "_" + indice de base. */
  static final String VARIABLE_CLAVE = "PENTAHO_LIVE_TEST_PW_";
  static final String CORRER = "run";
  static final String CREAR = "create";

  private PruebasMain() {
  }

  /** {@code trabajo.json}. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Trabajo {
    @JsonProperty( "format" )
    public String formato = FORMATO_TRABAJO;
    @JsonProperty( "tasks" )
    public List<Tarea> tareas = new ArrayList<Tarea>();
    @JsonProperty( "db_suffix" )
    public String sufijoBase;
    @JsonProperty( "plugin_version" )
    public String versionPlugin;
  }

  /** Una tarea: correr una prueba o crearla. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Tarea {
    /** {@value #CORRER} o {@value #CREAR}. */
    @JsonProperty( "kind" )
    public String tipo = CORRER;
    @JsonProperty( "test_file" )
    public String prueba;
    /** La copia del documento abierto que corre en lugar de la .ktr del disco; null = la del disco. */
    @JsonProperty( "ktr" )
    public String ktr;
    /** Donde va el resultado. */
    @JsonProperty( "result" )
    public String resultado;
    @JsonProperty( "create" )
    public Creacion crear;
  }

  /** Lo que pide test_create (PruebasMotor.Pedido con rutas de texto). */
  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Creacion {
    @JsonProperty( "folder" )
    public String carpeta;
    @JsonProperty( "name" )
    public String nombre;
    @JsonProperty( "description" )
    public String descripcion;
    @JsonProperty( "transformation" )
    public String original;
    @JsonProperty( "output_steps" )
    public List<String> salidas = new ArrayList<String>();
    /** null = las fuentes. */
    @JsonProperty( "input_steps" )
    public List<String> entradas;
    @JsonProperty( "key_columns" )
    public Map<String, List<String>> claves = new LinkedHashMap<String, List<String>>();
    @JsonProperty( "ignore_columns" )
    public Map<String, List<String>> ignorar = new LinkedHashMap<String, List<String>>();
    /** null = saltar los steps que escriben. */
    @JsonProperty( "tweaks" )
    public List<DefPrueba.Ajuste> ajustes;
    @JsonProperty( "variables" )
    public Map<String, String> variables = new LinkedHashMap<String, String>();
    @JsonProperty( "environment" )
    public DefPrueba.Entorno entorno;
    @JsonProperty( "test_databases" )
    public List<BaseDePrueba> bases = new ArrayList<BaseDePrueba>();
    @JsonProperty( "tables" )
    public List<TablaSalida> tablas = new ArrayList<TablaSalida>();
    /** Archivos que deja la corrida (0.7.1). */
    @JsonProperty( "files" )
    public List<String> archivos = new ArrayList<String>();
    @JsonProperty( "db_scripts" )
    public Map<String, List<String>> scriptsBase = new LinkedHashMap<String, List<String>>();
    @JsonProperty( "replace" )
    public boolean reemplazar;
    @JsonProperty( "ignore_unstable_columns" )
    public boolean ignorarInestables = true;
    @JsonProperty( "warnings" )
    public List<String> avisos = new ArrayList<String>();

    PruebasMotor.Pedido pedido() {
      PruebasMotor.Pedido p = new PruebasMotor.Pedido();
      p.carpeta = new File( carpeta );
      p.nombre = nombre;
      p.descripcion = descripcion;
      p.original = new File( original );
      p.salidas.addAll( salidas );
      p.entradas = entradas == null ? null : new ArrayList<String>( entradas );
      for ( Map.Entry<String, List<String>> e : claves.entrySet() ) {
        p.claves.put( PruebasMotor.clave( e.getKey() ), e.getValue() );
      }
      for ( Map.Entry<String, List<String>> e : ignorar.entrySet() ) {
        p.ignorar.put( PruebasMotor.clave( e.getKey() ), e.getValue() );
      }
      p.ajustes = ajustes;
      p.variables.putAll( variables );
      if ( entorno != null ) {
        p.entorno = entorno;
      }
      p.bases.addAll( bases );
      p.tablas.addAll( tablas );
      p.archivos.addAll( archivos );
      p.scriptsBase.putAll( scriptsBase );
      p.reemplazar = reemplazar;
      p.ignorarInestables = ignorarInestables;
      p.avisos.addAll( avisos );
      return p;
    }
  }

  public static void main( String[] args ) {
    int codigo;
    try {
      codigo = correr( new File( args[ 0 ] ) );
    } catch ( Throwable t ) {
      t.printStackTrace( System.out );
      codigo = 1;
    }
    System.out.flush();
    System.err.flush();
    System.exit( codigo ); // hilos de una transformacion que no paran no frenan la salida
  }

  static int correr( File archivo ) throws Exception {
    Trabajo tr = CorrectorJson.leer( archivo, Trabajo.class );
    if ( tr == null || !FORMATO_TRABAJO.equals( tr.formato ) ) {
      System.out.println( "[tests] invalid job file" );
      return 1;
    }
    final File cancelar = new File( archivo.getPath() + ".cancel" );
    long t0 = System.nanoTime();
    KettleClientEnvironment.getInstance().setClient( KettleClientEnvironment.ClientType.PAN );
    LanguageChoice.getInstance().setDefaultLocale( Locale.US ); // logs en ingles; numeros con el locale del entorno
    KettleEnvironment.init( false );
    System.out.println( "[tests] Kettle ready in " + Math.round( ( System.nanoTime() - t0 ) / 1e7 ) / 100.0 + " s" );
    // lo que se copio del KETTLE_HOME del usuario (si se copio): sus contraseñas no salen en ningun resultado
    List<String> secretos = PruebasKettleHome.secretos( new File( org.pentaho.di.core.Const.getKettleDirectory() ) );
    for ( int i = 0; i < tr.tareas.size(); i++ ) {
      Tarea ta = tr.tareas.get( i );
      if ( cancelar.exists() ) {
        CorrectorJson.guardar( ResultadoPrueba.sin( nombre( ta ), ta.prueba, ResultadoPrueba.CANCELADA, "Cancelled" ),
          new File( ta.resultado ) );
        continue;
      }
      PruebasMotor.Opciones op = new PruebasMotor.Opciones();
      op.ktr = ta.ktr == null ? null : new File( ta.ktr );
      op.sufijoBase = tr.sufijoBase;
      op.versionPlugin = tr.versionPlugin;
      op.cancelada = cancelar::exists;
      for ( int s = 0; s < secretos.size(); s++ ) {
        op.claves.put( "#kettle-home-" + s, secretos.get( s ) );
      }
      ResultadoPrueba r;
      if ( CREAR.equals( ta.tipo ) && ta.crear != null ) {
        claves( op, i, ta.crear.bases );
        r = PruebasMotor.crear( ta.crear.pedido(), op );
      } else {
        File f = new File( ta.prueba );
        try {
          claves( op, i, PruebasMotor.cargar( f ).bases );
        } catch ( Exception ignored ) {
          // la prueba invalida la informa correr
        }
        r = PruebasMotor.correr( f, op );
      }
      CorrectorJson.guardar( r, new File( ta.resultado ) );
      System.out.println( "[tests] " + r.prueba + ": " + r.estado + " in " + r.segundos + " s" + ( r.mensaje == null
        ? "" : " - " + r.mensaje.split( "\n" )[ 0 ] ) );
    }
    return 0;
  }

  private static String nombre( Tarea ta ) {
    if ( ta.crear != null && ta.crear.nombre != null ) {
      return ta.crear.nombre;
    }
    return ta.prueba == null ? "test" : PruebasMotor.nombreDeArchivo( new File( ta.prueba ) );
  }

  /** Las contraseñas de las bases de la tarea i, de su entorno. */
  private static void claves( PruebasMotor.Opciones op, int tarea, List<BaseDePrueba> bases ) {
    for ( int b = 0; bases != null && b < bases.size(); b++ ) {
      String c = System.getenv( VARIABLE_CLAVE + tarea + "_" + b );
      BaseDePrueba x = bases.get( b );
      if ( c != null && x != null && x.conexion != null ) {
        op.claves.put( x.conexion.trim().toLowerCase( Locale.ROOT ), c );
      }
    }
  }
}
