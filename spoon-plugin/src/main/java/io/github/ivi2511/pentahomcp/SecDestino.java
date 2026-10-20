package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.encryption.Encr;
import org.pentaho.di.core.logging.LoggingObjectInterface;
import org.pentaho.di.core.logging.LoggingObjectType;
import org.pentaho.di.core.logging.SimpleLoggingObject;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.insertupdate.InsertUpdateMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Seccion target_model: modelo destino de los steps Table output e Insert/Update (diseno §2.4.4).
 * <p>
 * <b>Sin conexion</b> (por defecto) arma las tablas con lo que dice cada step: conexion, esquema,
 * tabla, truncate, el mapeo columna &lt;- campo, las claves de Insert/Update y el tipo SQL que
 * generaria Kettle ({@code DatabaseMeta.getFieldDefinition}). No usa {@code TransMeta.analyseImpact}
 * (en Table output lista los campos del stream, no las columnas).
 * <p>
 * <b>Con "consultar la base"</b> ({@code Pedido.destinoConsultarBase}) se conecta una vez por
 * conexion, con un tope de 15 s, y lee si la tabla existe, sus columnas, la PK y las FK (JDBC
 * getImportedKeys); desconecta siempre.
 * <p>
 * <b>Nunca crea una base de archivo.</b> SQLite, H2 embebido y HSQLDB crean una base vacia al
 * conectarse si el archivo no existe. Antes de conectar se chequea el archivo; y antes de calcular
 * los campos que llegan a un step (getPrevStepFields: un Table input aguas arriba se conecta para
 * saber sus columnas) se chequean las bases de archivo de todos los steps anteriores.
 *
 * @dueño WP5
 */
final class SecDestino implements Recolector {

  /** Area de Rotulos: textos/destino_*.properties. */
  static final String AREA = "destino";

  /** Tope de la consulta a la base (diseno §2.5). */
  static final long TOPE_BASE_MS = 15000L;

  /** Arriba de esto no se arma el erDiagram (queda la tabla alternativa). */
  static final int TOPE_ENTIDADES = 40;
  static final int TOPE_COLUMNAS_DIAGRAMA = 400;

  static final String TIPO_TABLE_OUTPUT = "TableOutput";
  static final String TIPO_INSERT_UPDATE = "InsertUpdate";

  /** Roles de una columna (van al JSON tal cual; el texto visible sale de rol.*). */
  static final String ROL_CLAVE = "lookup_key";
  static final String ROL_ACTUALIZA = "updated";
  static final String ROL_INSERTA = "insert_only";

  /** Estado de la lectura de la base por tabla (datos.tables[].database_check.status). */
  static final String BASE_NO_CONSULTADA = "not_queried";
  static final String BASE_OK = "ok";
  static final String BASE_TABLA_FALTA = "table_missing";
  static final String BASE_ARCHIVO_FALTA = "missing_file";
  static final String BASE_ERROR = "error";
  static final String BASE_TIMEOUT = "timeout";
  static final String BASE_CANCELADA = "cancelled";
  static final String BASE_SIN_CONEXION = "no_connection";

  static final String ORIGEN_FK = "foreign_key";
  static final String ORIGEN_LOOKUP = "lookup_key";

  private static final String PREFIJO_ID = "target-model-";

  private static final LoggingObjectInterface LOG_OBJ =
    new SimpleLoggingObject( "Pentaho Live reports", LoggingObjectType.SPOON, null );

  // ---------------------------------------------------------------- modelo interno

  /** Una columna de una tabla destino. */
  static final class Columna {
    String nombre;
    /** Campo del stream que la llena; null si el step no la escribe. */
    String campo;
    String tipoKettle;
    /** Tipo que generaria Kettle; null si no se pudo calcular. */
    String tipoSql;
    final List<String> roles = new ArrayList<>();
    boolean escrita = true;
    /** null = no se consulto; false = el step la escribe pero la tabla no la tiene. */
    Boolean enBase;
    String tipoBase;
    boolean pk;
    /** "tabla.columna" referenciada por una FK de la base. */
    String fk;

    Columna( String nombre ) {
      this.nombre = nombre;
    }

    void rol( String r ) {
      if ( !roles.contains( r ) ) {
        roles.add( r );
      }
    }
  }

  /** Un step que escribe en una tabla. */
  static final class Paso {
    String nombre;
    String tipo;
    boolean truncate;
    Boolean especificaCampos;
    String campoTabla;
  }

  /** Una tabla destino (una por conexion + esquema + tabla, aunque la escriban varios steps). */
  static final class Destino {
    String clave;
    String entidad;
    DatabaseMeta db;
    String conexion;
    String tipoBase;
    String esquema;
    String tabla;
    final List<Paso> pasos = new ArrayList<>();
    final LinkedHashMap<String, Columna> columnas = new LinkedHashMap<>();
    String estadoBase = BASE_NO_CONSULTADA;
    String mensajeBase;
    List<String> pkBase = new ArrayList<>();
    /** FK leidas de la base (null si no se consulto). */
    List<Fk> fksBase;

    Columna columna( String nombre ) {
      String k = nombre.toLowerCase( Locale.ROOT );
      Columna c = columnas.get( k );
      if ( c == null ) {
        c = new Columna( nombre );
        columnas.put( k, c );
      }
      return c;
    }

    String nombreCompleto() {
      return vacio( esquema ) ? tabla : esquema + "." + tabla;
    }
  }

  /** Relacion del erDiagram: padre ||--o{ hijo. */
  static final class Relacion {
    String padre;
    String hijo;
    String etiqueta;
    String origen;
    String tablaPadre;
    String columnasPadre;
  }

  /** Una FK leida de la base. */
  static final class Fk {
    String tablaPadre;
    final List<String> columnas = new ArrayList<>();
    final List<String> columnasPadre = new ArrayList<>();
  }

  /** Lo que devuelve la consulta de una tabla (lo arma el hilo de la base, lo lee el worker). */
  static final class Lectura {
    String estado = BASE_OK;
    String mensaje;
    /** Nombre en minuscula -> {nombre real, tipo SQL}. En el orden de la tabla. */
    final LinkedHashMap<String, String[]> columnas = new LinkedHashMap<>();
    final List<String> pk = new ArrayList<>();
    final List<Fk> fks = new ArrayList<>();
  }

  // ---------------------------------------------------------------- Recolector

  @Override
  public String id() {
    return SeccionId.DESTINO;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    Rotulos r = Rotulos.de( AREA, idioma );
    if ( c == null || c.copia == null ) {
      return r.s( "no_aplica.sin_documento" );
    }
    if ( !( c.copia instanceof TransMeta ) ) {
      return r.s( "no_aplica.job" );
    }
    for ( StepMeta s : ( (TransMeta) c.copia ).getSteps() ) {
      if ( esDestino( s ) ) {
        return null;
      }
    }
    return r.s( "no_aplica.sin_steps" );
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    String titulo = SeccionId.titulo( id(), ctx.idioma() );
    String motivoNoAplica = noAplica( ctx.captura(), ctx.idioma() );
    if ( motivoNoAplica != null ) {
      return Seccion.omitida( id(), titulo, motivoNoAplica );
    }
    Rotulos r = ctx.rotulos( AREA );
    TransMeta tm = (TransMeta) ctx.captura().copia;
    boolean consultar = ctx.pedido() != null && ctx.pedido().destinoConsultarBase;
    List<Aviso> avisos = new ArrayList<>();
    Set<String> motivos = new LinkedHashSet<>();
    boolean cancelado = false;

    // 1) metadatos de cada step (sin conectar)
    List<StepMeta> pasos = new ArrayList<>();
    List<StepMeta> orden = ctx.orden();
    for ( StepMeta s : orden == null || orden.isEmpty() ? tm.getSteps() : orden ) {
      if ( esDestino( s ) ) {
        pasos.add( s );
      }
    }
    LinkedHashMap<String, Destino> destinos = new LinkedHashMap<>();
    for ( int i = 0; i < pasos.size(); i++ ) {
      if ( ctx.cancelado() ) {
        cancelado = true;
        break;
      }
      StepMeta s = pasos.get( i );
      ctx.progreso().paso( r.f( "progreso.step", s.getName() ), i, pasos.size() );
      leerStep( ctx, tm, s, r, destinos, avisos, motivos );
    }
    List<Destino> lista = new ArrayList<>( destinos.values() );
    nombrarEntidades( lista );

    // 2) la base (opcional)
    if ( consultar && !cancelado ) {
      cancelado = consultarBase( ctx, tm, lista, r, avisos, motivos );
    }

    // 3) relaciones: FK de la base y, entre tablas destino, claves de lookup con el mismo nombre
    List<Relacion> relaciones = relacionesFk( lista );
    relaciones.addAll( relacionesLookup( lista, relaciones ) );

    // 4) bloques
    Seccion sec = new Seccion( id(), titulo );
    if ( !lista.isEmpty() ) {
      sec.con( diagrama( lista, relaciones, r, avisos ) );
      int n = 0;
      for ( Destino d : lista ) {
        sec.con( tablaColumnas( d, ++n, consultar, r ) );
      }
    }
    if ( !consultar ) {
      avisos.add( 0, new Aviso( Aviso.INFO, r.s( "aviso.sin_base" ) ) );
    }
    if ( cancelado ) {
      avisos.add( new Aviso( Aviso.WARN, r.s( "aviso.cancelado" ) ) );
      motivos.add( r.s( "motivo.cancelado" ) );
    }
    Set<String> vistos = new HashSet<>();
    int na = 0;
    for ( Aviso a : avisos ) {
      if ( vistos.add( a.nivel + "|" + a.texto ) ) {
        a.id = PREFIJO_ID + "aviso-" + ( ++na );
        sec.con( a );
      }
    }
    sec.resumen = resumen( lista, relaciones, consultar, r );
    sec.datos = datos( lista, relaciones, consultar );
    if ( !motivos.isEmpty() ) {
      sec.estado = Seccion.PARCIAL;
      sec.motivo = String.join( " ", motivos );
    }
    return sec;
  }

  // ---------------------------------------------------------------- 1) metadatos

  static boolean esDestino( StepMeta s ) {
    if ( s == null ) {
      return false;
    }
    StepMetaInterface m = s.getStepMetaInterface();
    return m instanceof TableOutputMeta || m instanceof InsertUpdateMeta;
  }

  private void leerStep( Contexto ctx, TransMeta tm, StepMeta s, Rotulos r, Map<String, Destino> destinos,
    List<Aviso> avisos, Set<String> motivos ) {
    StepMetaInterface m = s.getStepMetaInterface();
    DatabaseMeta dm;
    String tabla;
    String esquema;
    Paso paso = new Paso();
    paso.nombre = s.getName();
    if ( m instanceof TableOutputMeta ) {
      TableOutputMeta t = (TableOutputMeta) m;
      dm = t.getDatabaseMeta();
      tabla = tm.environmentSubstitute( t.getTableName() );
      esquema = tm.environmentSubstitute( t.getSchemaName() );
      paso.tipo = TIPO_TABLE_OUTPUT;
      paso.truncate = t.truncateTable();
      paso.especificaCampos = t.specifyFields();
      if ( t.isTableNameInField() && !vacio( t.getTableNameField() ) ) {
        paso.campoTabla = t.getTableNameField();
      }
    } else {
      InsertUpdateMeta t = (InsertUpdateMeta) m;
      dm = t.getDatabaseMeta();
      tabla = tm.environmentSubstitute( t.getTableName() );
      esquema = tm.environmentSubstitute( t.getSchemaName() );
      paso.tipo = TIPO_INSERT_UPDATE;
    }
    if ( vacio( tabla ) && paso.campoTabla == null ) {
      avisos.add( new Aviso( Aviso.WARN, r.f( "aviso.sin_tabla", s.getName() ) ) );
      return;
    }
    if ( vacio( tabla ) ) {
      tabla = paso.campoTabla;
    }
    if ( paso.campoTabla != null ) {
      avisos.add( new Aviso( Aviso.INFO, r.f( "aviso.tabla_en_campo", s.getName(), paso.campoTabla, tabla ) ) );
    }
    if ( dm == null ) {
      avisos.add( new Aviso( Aviso.WARN, r.f( "aviso.sin_conexion", s.getName() ) ) );
    }
    tabla = tabla.trim();
    esquema = vacio( esquema ) ? null : esquema.trim();
    String conexion = dm == null ? r.s( "sin_conexion" ) : dm.getName();
    String clave = ( conexion + "|" + ( esquema == null ? "" : esquema ) + "|" + tabla ).toLowerCase( Locale.ROOT );
    Destino d = destinos.get( clave );
    if ( d == null ) {
      d = new Destino();
      d.clave = clave;
      d.db = dm;
      d.conexion = conexion;
      d.tipoBase = dm == null ? null : dm.getPluginId();
      d.esquema = esquema;
      d.tabla = tabla;
      destinos.put( clave, d );
    }
    d.pasos.add( paso );

    RowMetaInterface entrada = entrada( ctx, tm, s, r, avisos, motivos );
    if ( m instanceof TableOutputMeta ) {
      columnasTableOutput( (TableOutputMeta) m, entrada, d, dm, s, r, avisos );
    } else {
      columnasInsertUpdate( (InsertUpdateMeta) m, entrada, d, dm, s, r, avisos );
    }
  }

  /**
   * Los campos que llegan al step, o null. No los calcula si algun step anterior usa una base de
   * archivo que no existe: getPrevStepFields le pide los campos a un Table input, que se conecta y
   * crearia el archivo.
   */
  private RowMetaInterface entrada( Contexto ctx, TransMeta tm, StepMeta s, Rotulos r, List<Aviso> avisos,
    Set<String> motivos ) {
    List<String[]> faltan = basesFaltantesAntes( tm, s );
    if ( !faltan.isEmpty() ) {
      String[] f = faltan.get( 0 );
      avisos.add( new Aviso( Aviso.WARN, r.f( "aviso.campos_bloqueados", s.getName(), f[ 0 ], rutaVisible( f[ 1 ],
        ctx ) ) ) );
      motivos.add( r.s( "motivo.campos" ) );
      return null;
    }
    try {
      return ctx.entrada( s );
    } catch ( Throwable e ) {
      avisos.add( new Aviso( Aviso.WARN, r.f( "aviso.campos_error", s.getName(), RunMotor.primeraLinea( e ) ) ) );
      motivos.add( r.s( "motivo.campos" ) );
      return null;
    }
  }

  private void columnasTableOutput( TableOutputMeta t, RowMetaInterface entrada, Destino d, DatabaseMeta dm,
    StepMeta s, Rotulos r, List<Aviso> avisos ) {
    if ( t.specifyFields() ) {
      String[] cols = t.getFieldDatabase();
      String[] campos = t.getFieldStream();
      for ( int i = 0; cols != null && i < cols.length; i++ ) {
        if ( vacio( cols[ i ] ) ) {
          continue;
        }
        String campo = campos != null && i < campos.length && !vacio( campos[ i ] ) ? campos[ i ] : cols[ i ];
        agregar( d, cols[ i ].trim(), campo.trim(), null, entrada, dm, s, r, avisos );
      }
    } else if ( entrada != null ) {
      String excluir = t.isTableNameInField() && !t.isTableNameInTable() ? t.getTableNameField() : null;
      for ( ValueMetaInterface v : entrada.getValueMetaList() ) {
        if ( excluir != null && excluir.equalsIgnoreCase( v.getName() ) ) {
          continue;
        }
        agregar( d, v.getName(), v.getName(), null, entrada, dm, s, r, avisos );
      }
    }
  }

  private void columnasInsertUpdate( InsertUpdateMeta t, RowMetaInterface entrada, Destino d, DatabaseMeta dm,
    StepMeta s, Rotulos r, List<Aviso> avisos ) {
    String[] claves = t.getKeyLookup();
    String[] camposClave = t.getKeyStream();
    for ( int i = 0; claves != null && i < claves.length; i++ ) {
      if ( vacio( claves[ i ] ) ) {
        continue;
      }
      String campo = camposClave != null && i < camposClave.length ? camposClave[ i ] : null;
      agregar( d, claves[ i ].trim(), vacio( campo ) ? null : campo.trim(), ROL_CLAVE, entrada, dm, s, r, avisos );
    }
    String[] cols = t.getUpdateLookup();
    String[] campos = t.getUpdateStream();
    Boolean[] actualiza = t.getUpdate();
    for ( int i = 0; cols != null && i < cols.length; i++ ) {
      if ( vacio( cols[ i ] ) ) {
        continue;
      }
      boolean upd = !t.isUpdateBypassed() && ( actualiza == null || i >= actualiza.length || actualiza[ i ] == null
        || actualiza[ i ] );
      String campo = campos != null && i < campos.length && !vacio( campos[ i ] ) ? campos[ i ].trim() : cols[ i ].trim();
      agregar( d, cols[ i ].trim(), campo, upd ? ROL_ACTUALIZA : ROL_INSERTA, entrada, dm, s, r, avisos );
    }
  }

  private void agregar( Destino d, String columna, String campo, String rol, RowMetaInterface entrada,
    DatabaseMeta dm, StepMeta s, Rotulos r, List<Aviso> avisos ) {
    Columna c = d.columna( columna );
    if ( rol != null ) {
      c.rol( rol );
    }
    if ( campo == null || c.campo != null ) {
      return;
    }
    c.campo = campo;
    if ( entrada == null ) {
      return;
    }
    ValueMetaInterface v = entrada.searchValueMeta( campo );
    if ( v == null ) {
      avisos.add( new Aviso( Aviso.WARN, r.f( "aviso.campo_falta", s.getName(), columna, campo ) ) );
      return;
    }
    c.tipoKettle = v.getTypeDesc();
    if ( dm != null ) {
      try {
        String def = dm.getFieldDefinition( v, null, null, false, false, false );
        c.tipoSql = vacio( def ) ? null : def.trim();
      } catch ( Throwable e ) {
        c.tipoSql = null; // tipo que la base no sabe escribir: queda "?"
      }
    }
  }

  // ---------------------------------------------------------------- bases de archivo

  /**
   * Para cada step anterior a s (todos los caminos, con los de info) que usa una base de archivo que
   * no existe: {conexion, ruta}. Vacia si no hay ninguna.
   */
  static List<String[]> basesFaltantesAntes( TransMeta tm, StepMeta s ) {
    List<String[]> faltan = new ArrayList<>();
    Set<StepMeta> vistos = Collections.newSetFromMap( new IdentityHashMap<StepMeta, Boolean>() );
    Deque<StepMeta> pendientes = new ArrayDeque<>();
    try {
      pendientes.addAll( tm.findPreviousSteps( s, true ) );
    } catch ( Throwable e ) {
      return faltan;
    }
    Set<String> dichas = new HashSet<>();
    while ( !pendientes.isEmpty() ) {
      StepMeta p = pendientes.poll();
      if ( p == null || !vistos.add( p ) ) {
        continue;
      }
      try {
        DatabaseMeta[] usadas = p.getStepMetaInterface() == null ? null
          : p.getStepMetaInterface().getUsedDatabaseConnections();
        for ( int i = 0; usadas != null && i < usadas.length; i++ ) {
          File f = archivoFaltante( usadas[ i ], tm );
          if ( f != null && dichas.add( usadas[ i ].getName() + "|" + f.getPath() ) ) {
            faltan.add( new String[] { usadas[ i ].getName(), f.getPath() } );
          }
        }
        pendientes.addAll( tm.findPreviousSteps( p, true ) );
      } catch ( Throwable e ) {
        // un step raro no corta la busqueda
      }
    }
    return faltan;
  }

  /**
   * La primera base de archivo faltante que tocaria calcular los campos de s, como {conexion, ruta}, o
   * null: la del propio step (si {@code conPropio}: getStepFields de un Table input se conecta) o la de
   * algun step anterior. Lo usan ContextoKettle (campos) y SecValidacion (checkSteps).
   */
  static String[] baseFaltantePara( TransMeta tm, StepMeta s, boolean conPropio ) {
    if ( conPropio && s != null && s.getStepMetaInterface() != null ) {
      try {
        DatabaseMeta[] usadas = s.getStepMetaInterface().getUsedDatabaseConnections();
        for ( int i = 0; usadas != null && i < usadas.length; i++ ) {
          File f = archivoFaltante( usadas[ i ], tm );
          if ( f != null ) {
            return new String[] { usadas[ i ].getName(), f.getPath() };
          }
        }
      } catch ( Throwable e ) {
        // un step raro: se mira lo de antes
      }
    }
    List<String[]> antes = basesFaltantesAntes( tm, s );
    return antes.isEmpty() ? null : antes.get( 0 );
  }

  /**
   * Si la conexion es a una base de archivo que se crea sola al conectarse (SQLite, H2 embebido,
   * HSQLDB en archivo, o JDBC generico con esas URL) y el archivo no existe, ese archivo; si no,
   * null. Las variables se resuelven con vars (la transformacion), igual que al conectar.
   */
  static File archivoFaltante( DatabaseMeta dm, VariableSpace vars ) {
    File f = archivoDeBase( dm, vars );
    return f != null && !existeBase( dm, f ) ? f : null;
  }

  /** El archivo principal de una base de archivo, o null si no es de archivo (o no se sabe). */
  static File archivoDeBase( DatabaseMeta dm, VariableSpace vars ) {
    if ( dm == null ) {
      return null;
    }
    try {
      if ( dm.getAccessType() != DatabaseMeta.TYPE_ACCESS_NATIVE ) {
        return null;
      }
      String tipo = dm.getPluginId();
      String base = sustituir( vars, dm, dm.getDatabaseName() );
      if ( "SQLITE".equals( tipo ) ) {
        return archivoSqlite( base );
      }
      if ( "H2".equals( tipo ) ) {
        String host = sustituir( vars, dm, dm.getHostname() );
        return vacio( host ) ? archivoH2( base ) : null;
      }
      if ( "HYPERSONIC".equals( tipo ) ) {
        String puerto = sustituir( vars, dm, dm.getDatabasePortNumberString() );
        String host = sustituir( vars, dm, dm.getHostname() );
        if ( !vacio( host ) && !vacio( puerto ) && !"-1".equals( puerto.trim() ) ) {
          return null;
        }
        return archivoHsql( base );
      }
      if ( "GENERIC".equals( tipo ) ) {
        String url = sustituir( vars, dm, dm.getAttributes().getProperty( "CUSTOM_URL" ) );
        if ( url == null ) {
          return null;
        }
        url = url.trim();
        if ( url.startsWith( "jdbc:sqlite:" ) ) {
          return archivoSqlite( url.substring( "jdbc:sqlite:".length() ) );
        }
        if ( url.startsWith( "jdbc:h2:" ) ) {
          return archivoH2( url.substring( "jdbc:h2:".length() ) );
        }
        if ( url.startsWith( "jdbc:hsqldb:" ) ) {
          return archivoHsql( url.substring( "jdbc:hsqldb:".length() ) );
        }
        if ( url.startsWith( "jdbc:duckdb:" ) ) {
          return archivoDuckdb( url.substring( "jdbc:duckdb:".length() ) );
        }
        if ( url.startsWith( "jdbc:derby:" ) ) {
          return archivoDerby( url.substring( "jdbc:derby:".length() ), url.toLowerCase( java.util.Locale.ROOT )
            .contains( ";create=true" ) );
        }
      }
      if ( "DERBY".equals( tipo ) ) {
        String host = sustituir( vars, dm, dm.getHostname() );
        if ( !vacio( host ) ) {
          return null; // Derby en red: el servidor decide
        }
        boolean crea = false;
        for ( Map.Entry<String, String> e : dm.getExtraOptions().entrySet() ) {
          crea |= e.getKey() != null && e.getKey().toLowerCase( java.util.Locale.ROOT ).endsWith( ".create" )
            && "true".equalsIgnoreCase( e.getValue() == null ? null : e.getValue().trim() );
        }
        return archivoDerby( base, crea || ( base != null && base.toLowerCase( java.util.Locale.ROOT ).contains(
          ";create=true" ) ) );
      }
    } catch ( Throwable e ) {
      // sin datos para decidir: no es de archivo
    }
    return null;
  }

  private static File archivoSqlite( String base ) {
    if ( vacio( base ) ) {
      return null;
    }
    String b = base.trim();
    if ( b.startsWith( ":memory:" ) || b.startsWith( "file::memory" ) || b.contains( "mode=memory" ) ) {
      return null;
    }
    if ( b.startsWith( "file:" ) ) {
      // SQLite abre los "file:" como URI y decodifica los %XX. ${Internal.Entry.Current.Directory} es
      // justamente un URI: file:///C:/Mis%20datos/...
      b = rutaDeUri( b.replaceFirst( "[?#].*$", "" ) );
    }
    return vacio( b ) ? null : new File( b );
  }

  /**
   * "file:///C:/a%20b/x.db" -> "C:/a b/x.db"; "file:/home/x" -> "/home/x"; "file:x.db" -> "x.db". Como
   * SQLite: sin autoridad (o localhost) y con los %XX decodificados en UTF-8.
   */
  static String rutaDeUri( String uri ) {
    String s = uri.substring( "file:".length() ).replaceFirst( "^//(localhost)?(?=/)", "" );
    if ( s.indexOf( '%' ) >= 0 ) {
      try {
        s = java.net.URLDecoder.decode( s.replace( "+", "%2B" ), "UTF-8" );
      } catch ( Throwable e ) {
        // %XX mal formado: queda tal cual
      }
    }
    if ( s.matches( "^/[A-Za-z]:.*" ) ) {
      s = s.substring( 1 );
    }
    return s;
  }

  private static File archivoH2( String base ) {
    if ( vacio( base ) ) {
      return null;
    }
    String b = base.trim().replaceFirst( "^file:", "" );
    if ( b.matches( "(?i)^(mem|tcp|ssl|zip|nio\\w*|split|async|memfs|memlzf|retry):.*" ) ) {
      return null;
    }
    b = b.replaceFirst( ";.*$", "" );
    if ( b.startsWith( "~" ) ) {
      b = System.getProperty( "user.home" ) + b.substring( 1 );
    }
    return vacio( b ) ? null : new File( b + ".mv.db" );
  }

  private static File archivoHsql( String base ) {
    if ( vacio( base ) ) {
      return null;
    }
    String b = base.trim();
    if ( b.matches( "(?i)^(mem|res|hsql|hsqls|http|https):.*" ) ) {
      return null;
    }
    b = b.replaceFirst( "^file:", "" ).replaceFirst( ";.*$", "" );
    return vacio( b ) ? null : new File( b + ".properties" );
  }

  /**
   * DuckDB crea el archivo al conectarse: "jdbc:duckdb:C:/x/base.duckdb" (o con "?opciones"); vacio o
   * ":memory:" es en memoria.
   */
  static File archivoDuckdb( String resto ) {
    if ( vacio( resto ) ) {
      return null;
    }
    String b = resto.trim().replaceFirst( "[?#].*$", "" );
    if ( b.isEmpty() || b.startsWith( ":memory:" ) || b.equalsIgnoreCase( "memory" ) ) {
      return null;
    }
    if ( b.startsWith( "file:" ) ) {
      b = rutaDeUri( b );
    }
    return vacio( b ) ? null : new File( b );
  }

  /**
   * Derby embebido crea la base (una carpeta) solo con ";create=true": "jdbc:derby:C:/x/base;create=true",
   * "jdbc:derby:directory:base;create=true". En red ("//host") o en memoria, null.
   */
  static File archivoDerby( String resto, boolean crea ) {
    if ( !crea || vacio( resto ) ) {
      return null;
    }
    String b = resto.trim();
    if ( b.startsWith( "//" ) || b.regionMatches( true, 0, "memory:", 0, 7 ) || b.regionMatches( true, 0, "jar:", 0,
      4 ) || b.regionMatches( true, 0, "classpath:", 0, 10 ) ) {
      return null;
    }
    b = b.replaceFirst( "(?i)^directory:", "" ).replaceFirst( ";.*$", "" );
    return vacio( b ) ? null : new File( b );
  }

  /** H2 1.x usa .h2.db y HSQLDB puede tener solo el .script: tambien cuentan como existentes. */
  private static boolean existeBase( DatabaseMeta dm, File f ) {
    if ( f.exists() ) {
      return true;
    }
    String p = f.getPath();
    if ( p.endsWith( ".mv.db" ) ) {
      return new File( p.substring( 0, p.length() - ".mv.db".length() ) + ".h2.db" ).exists();
    }
    if ( p.endsWith( ".properties" ) ) {
      return new File( p.substring( 0, p.length() - ".properties".length() ) + ".script" ).exists();
    }
    return false;
  }

  /**
   * La ruta para el texto del informe: relativa a la carpeta del documento si esta adentro, asi no se ve
   * C:\Users\<usuario>\... (diseno §6, riesgo 20). Los datos crudos conservan la absoluta.
   */
  static String rutaVisible( String ruta, Contexto ctx ) {
    try {
      String doc = ctx == null || ctx.captura() == null ? null : ctx.captura().archivo;
      String rel = InformeServicio.Txt.relativa( ruta, doc );
      return rel == null ? ruta : rel;
    } catch ( RuntimeException e ) {
      return ruta;
    }
  }

  private static String sustituir( VariableSpace vars, DatabaseMeta dm, String s ) {
    if ( s == null ) {
      return null;
    }
    return vars != null ? vars.environmentSubstitute( s ) : dm.environmentSubstitute( s );
  }

  // ---------------------------------------------------------------- 2) la base

  /** Consulta cada conexion una vez. Devuelve true si se cancelo. */
  private boolean consultarBase( Contexto ctx, TransMeta tm, List<Destino> lista, Rotulos r, List<Aviso> avisos,
    Set<String> motivos ) {
    LinkedHashMap<DatabaseMeta, List<Destino>> porConexion = new LinkedHashMap<>();
    for ( Destino d : lista ) {
      if ( d.db == null ) {
        d.estadoBase = BASE_SIN_CONEXION;
        continue;
      }
      List<Destino> l = porConexion.get( d.db );
      if ( l == null ) {
        l = new ArrayList<>();
        porConexion.put( d.db, l );
      }
      l.add( d );
    }
    int i = 0;
    for ( Map.Entry<DatabaseMeta, List<Destino>> e : porConexion.entrySet() ) {
      DatabaseMeta dm = e.getKey();
      List<Destino> tablas = e.getValue();
      if ( ctx.cancelado() ) {
        marcar( tablas, BASE_CANCELADA, null );
        return true;
      }
      ctx.progreso().paso( r.f( "progreso.base", dm.getName() ), i++, porConexion.size() );
      File falta = archivoFaltante( dm, tm );
      if ( falta != null ) {
        String t = r.f( "aviso.archivo_falta", dm.getName(), rutaVisible( falta.getPath(), ctx ) );
        marcar( tablas, BASE_ARCHIVO_FALTA, t );
        avisos.add( new Aviso( Aviso.WARN, t ) );
        motivos.add( r.s( "motivo.archivo" ) );
        ctx.aviso( t );
        continue;
      }
      long tope = Math.min( TOPE_BASE_MS, ctx.msRestantes() );
      Map<Destino, Lectura> leidas;
      try {
        leidas = leerBase( dm, tm, tablas, tope, ctx );
      } catch ( TimeoutException te ) {
        String t = r.f( "aviso.base_timeout", dm.getName(), Math.max( 1L, tope / 1000L ) );
        marcar( tablas, BASE_TIMEOUT, t );
        avisos.add( new Aviso( Aviso.ERROR, t ) );
        motivos.add( r.s( "motivo.base" ) );
        ctx.aviso( t );
        continue;
      } catch ( InterruptedException ie ) {
        marcar( tablas, BASE_CANCELADA, null );
        return true;
      } catch ( Throwable ex ) {
        String t = r.f( "aviso.base_error", dm.getName(), detalle( ex, dm ) );
        marcar( tablas, BASE_ERROR, t );
        avisos.add( new Aviso( Aviso.ERROR, t ) );
        motivos.add( r.s( "motivo.base" ) );
        ctx.aviso( t );
        continue;
      }
      for ( Destino d : tablas ) {
        aplicarLectura( d, leidas.get( d ), r, avisos );
      }
    }
    return false;
  }

  private static void marcar( List<Destino> tablas, String estado, String mensaje ) {
    for ( Destino d : tablas ) {
      d.estadoBase = estado;
      d.mensajeBase = mensaje;
    }
  }

  /**
   * Conecta en un hilo daemon propio, lee todas las tablas de esa conexion y desconecta en finally.
   * Espera hasta tope ms (y corta antes si el pedido se cancela: InterruptedException).
   */
  static Map<Destino, Lectura> leerBase( DatabaseMeta original, final VariableSpace vars, final List<Destino> tablas,
    long tope, Contexto ctx ) throws Exception {
    final DatabaseMeta dm = (DatabaseMeta) original.clone();
    if ( vars != null ) {
      dm.shareVariablesWith( vars );
    }
    final AtomicReference<Database> ref = new AtomicReference<>();
    FutureTask<Map<Destino, Lectura>> tarea = new FutureTask<>( new Callable<Map<Destino, Lectura>>() {
      @Override
      public Map<Destino, Lectura> call() throws Exception {
        Database db = new Database( LOG_OBJ, dm );
        ref.set( db );
        try {
          if ( vars != null ) {
            db.shareVariablesWith( vars );
          }
          db.connect();
          Map<Destino, Lectura> res = new HashMap<>();
          for ( Destino d : tablas ) {
            if ( Thread.currentThread().isInterrupted() ) {
              break;
            }
            res.put( d, leerTabla( db, d.esquema, d.tabla ) );
          }
          return res;
        } finally {
          db.disconnect();
        }
      }
    } );
    Thread t = new Thread( tarea, "pentaho-live-destino" );
    t.setDaemon( true );
    t.start();
    long fin = System.currentTimeMillis() + Math.max( 0L, tope );
    try {
      while ( true ) {
        long resta = fin - System.currentTimeMillis();
        if ( resta <= 0 ) {
          throw new TimeoutException();
        }
        if ( ctx != null && ctx.cancelado() ) {
          throw new InterruptedException();
        }
        try {
          return tarea.get( Math.min( resta, 200L ), TimeUnit.MILLISECONDS );
        } catch ( TimeoutException e ) {
          // sigue esperando
        } catch ( ExecutionException e ) {
          Throwable c = e.getCause();
          if ( c instanceof Exception ) {
            throw (Exception) c;
          }
          throw new RuntimeException( c );
        }
      }
    } catch ( TimeoutException | InterruptedException e ) {
      Database db = ref.get();
      if ( db != null ) {
        try {
          db.cancelQuery();
        } catch ( Throwable ignored ) {
          // mejor esfuerzo: el hilo daemon desconecta en su finally
        }
      }
      tarea.cancel( true );
      throw e;
    }
  }

  /** Existencia, columnas, PK y FK de una tabla. (hilo de la base) */
  static Lectura leerTabla( Database db, String esquema, String tabla ) throws Exception {
    Lectura l = new Lectura();
    DatabaseMetaData md = db.getDatabaseMetaData();
    String[] real = buscarTabla( md, esquema, tabla );
    if ( real == null ) {
      boolean existe;
      try {
        existe = db.checkTableExists( esquema, tabla );
      } catch ( Throwable e ) {
        existe = false;
      }
      if ( !existe ) {
        l.estado = BASE_TABLA_FALTA;
        return l;
      }
      real = new String[] { esquema, tabla };
    }
    String sch = real[ 0 ];
    String nom = real[ 1 ];
    try ( ResultSet rs = md.getColumns( null, sch, escapar( md, nom ), null ) ) {
      while ( rs.next() ) {
        if ( !nom.equalsIgnoreCase( rs.getString( "TABLE_NAME" ) ) ) {
          continue;
        }
        String col = rs.getString( "COLUMN_NAME" );
        if ( col != null ) {
          l.columnas.put( col.toLowerCase( Locale.ROOT ), new String[] { col, tipoSql( rs ) } );
        }
      }
    } catch ( Throwable e ) {
      l.columnas.clear();
    }
    if ( l.columnas.isEmpty() ) {
      try {
        RowMetaInterface rm = db.getTableFieldsMeta( sch, nom );
        for ( int i = 0; rm != null && i < rm.size(); i++ ) {
          ValueMetaInterface v = rm.getValueMeta( i );
          l.columnas.put( v.getName().toLowerCase( Locale.ROOT ), new String[] { v.getName(), null } );
        }
      } catch ( Throwable e ) {
        // sin columnas: queda solo lo del step
      }
    }
    try ( ResultSet rs = md.getPrimaryKeys( null, sch, nom ) ) {
      PorSecuencia pk = new PorSecuencia();
      while ( rs.next() ) {
        pk.poner( rs.getShort( "KEY_SEQ" ), rs.getString( "COLUMN_NAME" ) );
      }
      l.pk.addAll( pk.valores() );
    } catch ( Throwable e ) {
      // abajo, con Kettle
    }
    if ( l.pk.isEmpty() ) {
      try {
        String[] pk = db.getPrimaryKeyColumnNames( nom );
        if ( pk != null ) {
          l.pk.addAll( Arrays.asList( pk ) );
        }
      } catch ( Throwable e ) {
        // la base no informa PK
      }
    }
    try ( ResultSet rs = md.getImportedKeys( null, sch, nom ) ) {
      Map<String, Fk> porNombre = new LinkedHashMap<>();
      Fk actual = null;
      while ( rs.next() ) {
        String nombreFk = rs.getString( "FK_NAME" );
        String padre = rs.getString( "PKTABLE_NAME" );
        short seq = rs.getShort( "KEY_SEQ" );
        Fk fk;
        if ( !vacio( nombreFk ) ) {
          fk = porNombre.get( nombreFk + "|" + padre );
          if ( fk == null ) {
            fk = new Fk();
            porNombre.put( nombreFk + "|" + padre, fk );
          }
        } else if ( actual != null && seq > 1 && padre != null && padre.equals( actual.tablaPadre ) ) {
          fk = actual;
        } else {
          fk = new Fk();
          porNombre.put( "#" + porNombre.size(), fk );
        }
        fk.tablaPadre = padre;
        String col = rs.getString( "FKCOLUMN_NAME" );
        String colPadre = rs.getString( "PKCOLUMN_NAME" );
        fk.columnas.add( col == null ? "" : col );
        fk.columnasPadre.add( colPadre == null ? "" : colPadre );
        actual = fk;
      }
      l.fks.addAll( porNombre.values() );
    } catch ( Throwable e ) {
      // la base no informa FK
    }
    return l;
  }

  /** PK en el orden de KEY_SEQ. */
  private static final class PorSecuencia {
    private final TreeMap<Integer, String> m = new TreeMap<>();

    void poner( int seq, String col ) {
      if ( col != null ) {
        m.put( m.containsKey( seq ) ? m.size() + 1000 : seq, col );
      }
    }

    List<String> valores() {
      return new ArrayList<>( m.values() );
    }
  }

  /** {esquema, nombre real} de la tabla, sin importar mayusculas; null si JDBC no la encuentra. */
  private static String[] buscarTabla( DatabaseMetaData md, String esquema, String tabla ) {
    List<String> candidatos = new ArrayList<>( new LinkedHashSet<>( Arrays.asList( tabla,
      tabla.toUpperCase( Locale.ROOT ), tabla.toLowerCase( Locale.ROOT ) ) ) );
    List<String> esquemas = new ArrayList<>();
    esquemas.add( esquema );
    if ( esquema != null ) {
      esquemas.add( esquema.toUpperCase( Locale.ROOT ) );
      esquemas.add( esquema.toLowerCase( Locale.ROOT ) );
    }
    for ( String sch : new LinkedHashSet<>( esquemas ) ) {
      for ( String c : candidatos ) {
        try ( ResultSet rs = md.getTables( null, sch, escapar( md, c ), null ) ) {
          while ( rs.next() ) {
            String nom = rs.getString( "TABLE_NAME" );
            if ( tabla.equalsIgnoreCase( nom ) ) {
              return new String[] { rs.getString( "TABLE_SCHEM" ), nom };
            }
          }
        } catch ( Throwable e ) {
          // probar el siguiente
        }
      }
    }
    return null;
  }

  private static String escapar( DatabaseMetaData md, String nombre ) {
    try {
      String esc = md.getSearchStringEscape();
      if ( vacio( esc ) ) {
        return nombre;
      }
      return nombre.replace( esc, esc + esc ).replace( "_", esc + "_" ).replace( "%", esc + "%" );
    } catch ( Throwable e ) {
      return nombre;
    }
  }

  /** TYPE_NAME con el largo para los CHAR y la precision para DECIMAL/NUMERIC. */
  private static String tipoSql( ResultSet rs ) {
    try {
      String t = rs.getString( "TYPE_NAME" );
      if ( t == null ) {
        return null;
      }
      String u = t.toUpperCase( Locale.ROOT );
      int largo = rs.getInt( "COLUMN_SIZE" );
      boolean sinLargo = rs.wasNull();
      if ( !sinLargo && u.contains( "CHAR" ) && largo > 0 && largo <= 65535 && !t.contains( "(" ) ) {
        return t + "(" + largo + ")";
      }
      if ( !sinLargo && ( u.startsWith( "DECIMAL" ) || u.startsWith( "NUMERIC" ) ) && largo > 0 && largo <= 1000
        && !t.contains( "(" ) ) {
        int dec = rs.getInt( "DECIMAL_DIGITS" );
        return t + "(" + largo + ( rs.wasNull() || dec <= 0 ? "" : "," + dec ) + ")";
      }
      return t;
    } catch ( Throwable e ) {
      return null;
    }
  }

  /** Mezcla lo leido de la base con lo que dicen los steps. (worker) */
  private static void aplicarLectura( Destino d, Lectura l, Rotulos r, List<Aviso> avisos ) {
    if ( l == null ) {
      d.estadoBase = BASE_CANCELADA;
      return;
    }
    d.estadoBase = l.estado;
    if ( BASE_TABLA_FALTA.equals( l.estado ) ) {
      String t = r.f( "aviso.tabla_no_existe", d.nombreCompleto(), d.conexion );
      d.mensajeBase = t;
      avisos.add( new Aviso( Aviso.WARN, t ) );
      for ( Columna c : d.columnas.values() ) {
        c.enBase = Boolean.FALSE;
      }
      return;
    }
    d.pkBase = new ArrayList<>( l.pk );
    Set<String> pk = new HashSet<>();
    for ( String p : l.pk ) {
      pk.add( p.toLowerCase( Locale.ROOT ) );
    }
    LinkedHashMap<String, Columna> nuevas = new LinkedHashMap<>();
    if ( !l.columnas.isEmpty() ) {
      for ( Map.Entry<String, String[]> e : l.columnas.entrySet() ) {
        Columna c = d.columnas.get( e.getKey() );
        if ( c == null ) {
          c = new Columna( e.getValue()[ 0 ] );
          c.escrita = false;
        }
        c.enBase = Boolean.TRUE;
        c.tipoBase = e.getValue()[ 1 ];
        nuevas.put( e.getKey(), c );
      }
      for ( Map.Entry<String, Columna> e : d.columnas.entrySet() ) {
        if ( !nuevas.containsKey( e.getKey() ) ) {
          Columna c = e.getValue();
          c.enBase = Boolean.FALSE;
          nuevas.put( e.getKey(), c );
          avisos.add( new Aviso( Aviso.WARN, r.f( "aviso.columna_falta", c.nombre, d.nombreCompleto() ) ) );
        }
      }
      d.columnas.clear();
      d.columnas.putAll( nuevas );
    }
    for ( Columna c : d.columnas.values() ) {
      c.pk = pk.contains( c.nombre.toLowerCase( Locale.ROOT ) );
    }
    d.fksBase = l.fks;
    for ( Fk fk : l.fks ) {
      for ( int i = 0; i < fk.columnas.size(); i++ ) {
        Columna c = d.columnas.get( fk.columnas.get( i ).toLowerCase( Locale.ROOT ) );
        if ( c != null ) {
          String cp = fk.columnasPadre.get( i );
          c.fk = fk.tablaPadre + ( vacio( cp ) ? "" : "." + cp );
        }
      }
    }
  }

  /** Mensaje de la causa mas profunda, en una linea y sin la contrasenia. */
  static String detalle( Throwable e, DatabaseMeta dm ) {
    String msg = null;
    List<Throwable> cadena = new ArrayList<>();
    for ( Throwable c = e; c != null && cadena.size() < 12 && !cadena.contains( c ); c = c.getCause() ) {
      cadena.add( c );
    }
    for ( int i = cadena.size() - 1; i >= 0 && msg == null; i-- ) {
      String m = cadena.get( i ).getMessage();
      if ( m != null && !m.trim().isEmpty() ) {
        msg = m;
      }
    }
    if ( msg == null ) {
      msg = cadena.isEmpty() ? "?" : cadena.get( cadena.size() - 1 ).getClass().getSimpleName();
    }
    msg = msg.replaceAll( "\\s+", " " ).trim();
    try {
      String pass = Encr.decryptPasswordOptionallyEncrypted( dm.environmentSubstitute( dm.getPassword() ) );
      if ( pass != null && pass.length() >= 3 ) {
        msg = msg.replace( pass, "****" );
      }
    } catch ( Throwable ignored ) {
      // sin contrasenia
    }
    msg = msg.replaceAll( "(?i)(password=)[^;&\\s]*", "$1****" );
    return msg.length() > 300 ? msg.substring( 0, 300 ) + "..." : msg;
  }

  // ---------------------------------------------------------------- 3) relaciones

  private static List<Relacion> relacionesFk( List<Destino> lista ) {
    List<Relacion> rels = new ArrayList<>();
    Set<String> usadas = new HashSet<>();
    for ( Destino d : lista ) {
      usadas.add( d.entidad );
    }
    Map<String, String> externas = new HashMap<>();
    Set<String> vistas = new HashSet<>();
    for ( Destino d : lista ) {
      if ( d.fksBase == null ) {
        continue;
      }
      for ( Fk fk : d.fksBase ) {
        if ( vacio( fk.tablaPadre ) ) {
          continue;
        }
        String padre = null;
        for ( Destino o : lista ) {
          if ( o.conexion.equals( d.conexion ) && o.tabla.equalsIgnoreCase( fk.tablaPadre ) ) {
            padre = o.entidad;
            break;
          }
        }
        if ( padre == null ) {
          String k = fk.tablaPadre.toLowerCase( Locale.ROOT );
          padre = externas.get( k );
          if ( padre == null ) {
            padre = unico( identificador( fk.tablaPadre, "t" ), usadas );
            externas.put( k, padre );
          }
        }
        Relacion rel = new Relacion();
        rel.padre = padre;
        rel.hijo = d.entidad;
        rel.etiqueta = String.join( ", ", fk.columnas );
        rel.origen = ORIGEN_FK;
        rel.tablaPadre = fk.tablaPadre;
        rel.columnasPadre = String.join( ", ", fk.columnasPadre );
        if ( vistas.add( rel.padre + "|" + rel.hijo + "|" + rel.etiqueta ) ) {
          rels.add( rel );
        }
      }
    }
    return rels;
  }

  /**
   * Relaciones inferidas: una tabla de Insert/Update buscada por la clave k (condicion "=") es padre de
   * otra tabla destino de la misma conexion que tiene una columna k que no es su propia clave ni su
   * PK. Se omite si ya hay una FK de la base entre las dos.
   */
  private static List<Relacion> relacionesLookup( List<Destino> lista, List<Relacion> fks ) {
    List<Relacion> rels = new ArrayList<>();
    Set<String> pares = new HashSet<>();
    for ( Relacion f : fks ) {
      pares.add( f.padre + "|" + f.hijo );
      pares.add( f.hijo + "|" + f.padre );
    }
    for ( Destino padre : lista ) {
      for ( Columna k : padre.columnas.values() ) {
        if ( !k.roles.contains( ROL_CLAVE ) ) {
          continue;
        }
        for ( Destino hijo : lista ) {
          if ( hijo == padre || !hijo.conexion.equals( padre.conexion )
            || pares.contains( padre.entidad + "|" + hijo.entidad ) ) {
            continue;
          }
          Columna c = hijo.columnas.get( k.nombre.toLowerCase( Locale.ROOT ) );
          if ( c == null || c.roles.contains( ROL_CLAVE ) || c.pk ) {
            continue;
          }
          Relacion rel = new Relacion();
          rel.padre = padre.entidad;
          rel.hijo = hijo.entidad;
          rel.etiqueta = c.nombre;
          rel.origen = ORIGEN_LOOKUP;
          rel.tablaPadre = padre.tabla;
          rel.columnasPadre = k.nombre;
          rels.add( rel );
          pares.add( padre.entidad + "|" + hijo.entidad );
          pares.add( hijo.entidad + "|" + padre.entidad );
        }
      }
    }
    return rels;
  }

  // ---------------------------------------------------------------- 4) bloques

  private static void nombrarEntidades( List<Destino> lista ) {
    Map<String, Integer> repetidas = new HashMap<>();
    for ( Destino d : lista ) {
      String k = identificador( d.tabla, "t" ).toLowerCase( Locale.ROOT );
      repetidas.put( k, repetidas.containsKey( k ) ? repetidas.get( k ) + 1 : 1 );
    }
    Set<String> usadas = new HashSet<>();
    for ( Destino d : lista ) {
      String base = identificador( d.tabla, "t" );
      if ( repetidas.get( base.toLowerCase( Locale.ROOT ) ) > 1 && !vacio( d.esquema ) ) {
        base = identificador( d.esquema + "_" + d.tabla, "t" );
      }
      d.entidad = unico( base, usadas );
    }
  }

  private Diagrama diagrama( List<Destino> lista, List<Relacion> rels, Rotulos r, List<Aviso> avisos ) {
    Diagrama d = new Diagrama();
    d.id = PREFIJO_ID + "er";
    d.clase = Diagrama.ER;
    d.alt = r.f( "diagrama.alt", lista.size() );
    int columnas = 0;
    for ( Destino x : lista ) {
      columnas += x.columnas.size();
    }
    if ( lista.size() > TOPE_ENTIDADES || columnas > TOPE_COLUMNAS_DIAGRAMA ) {
      avisos.add( new Aviso( Aviso.INFO, r.f( "aviso.grande", lista.size(), columnas ) ) );
    } else {
      d.mermaid = mermaid( lista, rels, r );
    }
    boolean hayLookup = false;
    for ( Relacion rel : rels ) {
      hayLookup |= ORIGEN_LOOKUP.equals( rel.origen );
    }
    if ( hayLookup ) {
      d.nota = r.s( "diagrama.nota_lookup" );
    }
    Tabla alt = new Tabla( r.s( "col.tabla" ), r.s( "col.columnas" ), r.s( "col.claves" ), r.s( "col.relaciones" ) );
    for ( Destino x : lista ) {
      List<String> cols = new ArrayList<>();
      List<String> claves = new ArrayList<>();
      for ( Columna c : x.columnas.values() ) {
        cols.add( c.nombre );
        if ( c.pk ) {
          claves.add( "PK " + c.nombre );
        } else if ( c.roles.contains( ROL_CLAVE ) ) {
          claves.add( "UK " + c.nombre );
        }
      }
      List<String> refs = new ArrayList<>();
      for ( Relacion rel : rels ) {
        if ( rel.hijo.equals( x.entidad ) ) {
          refs.add( rel.etiqueta + " → " + rel.tablaPadre + ( vacio( rel.columnasPadre ) ? "" : "." + rel.columnasPadre )
            + ( ORIGEN_LOOKUP.equals( rel.origen ) ? " (" + r.s( "rel.lookup" ) + ")" : "" ) );
        }
      }
      alt.fila( x.nombreCompleto(), String.join( ", ", cols ), String.join( ", ", claves ), String.join( "; ", refs ) );
    }
    alt.id = PREFIJO_ID + "er-alternativa";
    d.alternativa = alt;
    return d;
  }

  /** erDiagram compatible con Mermaid 9+: identificadores ASCII, una sola clave por atributo. */
  static String mermaid( List<Destino> lista, List<Relacion> rels, Rotulos r ) {
    StringBuilder sb = new StringBuilder( "erDiagram\n" );
    for ( Destino d : lista ) {
      sb.append( "  " ).append( d.entidad ).append( " {" );
      if ( d.columnas.isEmpty() ) {
        sb.append( " }\n" );
        continue;
      }
      sb.append( '\n' );
      Set<String> usados = new HashSet<>();
      for ( Columna c : d.columnas.values() ) {
        String nombre = unico( identificador( c.nombre, "c" ), usados );
        String tipo = identificador( tipoMostrado( c ), "unknown" );
        String clave = c.pk ? "PK" : c.fk != null ? "FK" : c.roles.contains( ROL_CLAVE ) ? "UK" : null;
        List<String> notas = new ArrayList<>();
        if ( !nombre.equals( c.nombre ) ) {
          notas.add( c.nombre );
        }
        notas.addAll( textosRoles( c, r ) );
        if ( !c.escrita ) {
          notas.add( r.s( "rol.no_escrita" ) );
        }
        if ( Boolean.FALSE.equals( c.enBase ) ) {
          notas.add( r.s( "rol.falta" ) );
        }
        sb.append( "    " ).append( tipo ).append( ' ' ).append( nombre );
        if ( clave != null ) {
          sb.append( ' ' ).append( clave );
        }
        if ( !notas.isEmpty() ) {
          sb.append( " \"" ).append( comentario( String.join( ", ", notas ) ) ).append( '"' );
        }
        sb.append( '\n' );
      }
      sb.append( "  }\n" );
    }
    for ( Relacion rel : rels ) {
      sb.append( "  " ).append( rel.padre ).append( ORIGEN_FK.equals( rel.origen ) ? " ||--o{ " : " ||..o{ " )
        .append( rel.hijo ).append( " : \"" ).append( comentario( rel.etiqueta ) ).append( "\"\n" );
    }
    return sb.toString();
  }

  private static Tabla tablaColumnas( Destino d, int n, boolean consultar, Rotulos r ) {
    boolean conBase = consultar && BASE_OK.equals( d.estadoBase );
    Tabla t = new Tabla( r.s( "col.columna" ), r.s( "col.campo" ), r.s( "col.tipo_kettle" ),
      r.s( conBase ? "col.tipo_base" : "col.tipo_sql" ), r.s( "col.clave" ) );
    t.id = PREFIJO_ID + "tabla-" + n;
    List<String> pasos = new ArrayList<>();
    for ( Paso p : d.pasos ) {
      String tipo = r.s( "tipo." + p.tipo );
      pasos.add( p.nombre + " (" + ( p.truncate ? tipo + ", " + r.s( "truncate" ) : tipo ) + ")" );
    }
    t.titulo = r.f( "tabla.titulo", d.conexion, d.nombreCompleto(), String.join( "; ", pasos ) );
    for ( Columna c : d.columnas.values() ) {
      List<String> uso = new ArrayList<>();
      if ( c.pk ) {
        uso.add( "PK" );
      }
      if ( c.fk != null ) {
        uso.add( r.f( "rol.fk", c.fk ) );
      }
      uso.addAll( textosRoles( c, r ) );
      if ( !c.escrita ) {
        uso.add( r.s( "rol.no_escrita" ) );
      }
      if ( Boolean.FALSE.equals( c.enBase ) ) {
        uso.add( r.s( "rol.falta" ) );
      }
      t.fila( c.nombre, c.campo == null ? "" : c.campo, c.tipoKettle == null ? "" : c.tipoKettle, tipoMostrado( c ),
        String.join( ", ", uso ) );
    }
    if ( BASE_TABLA_FALTA.equals( d.estadoBase ) ) {
      t.pie = r.s( "tabla.no_existe" );
    } else if ( d.columnas.isEmpty() ) {
      t.pie = r.s( "tabla.sin_columnas" );
    }
    return t;
  }

  private static List<String> textosRoles( Columna c, Rotulos r ) {
    List<String> l = new ArrayList<>();
    if ( c.roles.contains( ROL_CLAVE ) ) {
      l.add( r.s( "rol.clave" ) );
    }
    if ( c.roles.contains( ROL_ACTUALIZA ) ) {
      l.add( r.s( "rol.actualiza" ) );
    } else if ( c.roles.contains( ROL_INSERTA ) ) {
      l.add( r.s( "rol.inserta" ) );
    }
    return l;
  }

  private static String tipoMostrado( Columna c ) {
    if ( c.tipoBase != null ) {
      return c.tipoBase;
    }
    return c.tipoSql == null ? "?" : c.tipoSql;
  }

  private static String resumen( List<Destino> lista, List<Relacion> rels, boolean consultar, Rotulos r ) {
    if ( lista.isEmpty() ) {
      return r.s( "resumen.vacio" );
    }
    int escritas = 0;
    for ( Destino d : lista ) {
      for ( Columna c : d.columnas.values() ) {
        if ( c.escrita ) {
          escritas++;
        }
      }
    }
    String cols = plural( r, "n.columnas", escritas );
    StringBuilder sb = new StringBuilder();
    if ( lista.size() == 1 ) {
      sb.append( r.f( "resumen.una", lista.get( 0 ).nombreCompleto(), lista.get( 0 ).conexion, cols ) );
    } else {
      List<String> nombres = new ArrayList<>();
      for ( int i = 0; i < lista.size() && i < 5; i++ ) {
        nombres.add( lista.get( i ).nombreCompleto() );
      }
      String l = String.join( ", ", nombres ) + ( lista.size() > 5 ? ", …" : "" );
      sb.append( r.f( "resumen.varias", lista.size(), l, cols ) );
    }
    sb.append( ' ' );
    if ( !consultar ) {
      sb.append( r.s( "resumen.sin_base" ) );
    } else {
      int ok = 0;
      int pks = 0;
      int fks = 0;
      for ( Destino d : lista ) {
        if ( BASE_OK.equals( d.estadoBase ) ) {
          ok++;
          pks += d.pkBase.isEmpty() ? 0 : 1;
        }
      }
      for ( Relacion rel : rels ) {
        fks += ORIGEN_FK.equals( rel.origen ) ? 1 : 0;
      }
      sb.append( ok == 0 ? r.s( "resumen.base_fallo" )
        : r.f( "resumen.con_base", plural( r, "n.tablas", pks ), plural( r, "n.fks", fks ) ) );
    }
    return sb.toString();
  }

  /** Texto con numero: clave.1 para uno, clave.n para el resto ("1 columna", "3 columnas"). */
  private static String plural( Rotulos r, String clave, int n ) {
    return r.f( clave + ( n == 1 ? ".1" : ".n" ), n );
  }

  private static ObjectNode datos( List<Destino> lista, List<Relacion> rels, boolean consultar ) {
    ObjectNode o = ModeloJson.mapper().createObjectNode();
    o.put( "section", SeccionId.DESTINO );
    o.put( "database_queried", consultar );
    ArrayNode tablas = o.putArray( "tables" );
    for ( Destino d : lista ) {
      ObjectNode t = tablas.addObject();
      t.put( "entity", d.entidad );
      t.put( "connection", d.conexion );
      if ( d.tipoBase != null ) {
        t.put( "database_type", d.tipoBase );
      }
      if ( d.esquema != null ) {
        t.put( "schema", d.esquema );
      }
      t.put( "table", d.tabla );
      ArrayNode pasos = t.putArray( "steps" );
      for ( Paso p : d.pasos ) {
        ObjectNode x = pasos.addObject();
        x.put( "name", p.nombre );
        x.put( "type", p.tipo );
        if ( TIPO_TABLE_OUTPUT.equals( p.tipo ) ) {
          x.put( "truncate", p.truncate );
          x.put( "specify_fields", Boolean.TRUE.equals( p.especificaCampos ) );
          if ( p.campoTabla != null ) {
            x.put( "table_name_field", p.campoTabla );
          }
        }
      }
      ArrayNode cols = t.putArray( "columns" );
      for ( Columna c : d.columnas.values() ) {
        ObjectNode x = cols.addObject();
        x.put( "name", c.nombre );
        if ( c.campo != null ) {
          x.put( "field", c.campo );
        }
        if ( c.tipoKettle != null ) {
          x.put( "field_type", c.tipoKettle );
        }
        if ( c.tipoSql != null ) {
          x.put( "sql_type", c.tipoSql );
        }
        ArrayNode roles = x.putArray( "roles" );
        for ( String rol : c.roles ) {
          roles.add( rol );
        }
        x.put( "written", c.escrita );
        if ( c.enBase != null ) {
          x.put( "in_database", c.enBase );
        }
        if ( c.tipoBase != null ) {
          x.put( "database_sql_type", c.tipoBase );
        }
        if ( c.pk ) {
          x.put( "primary_key", true );
        }
        if ( c.fk != null ) {
          x.put( "references", c.fk );
        }
      }
      ObjectNode b = t.putObject( "database_check" );
      b.put( "status", d.estadoBase );
      if ( BASE_OK.equals( d.estadoBase ) ) {
        ArrayNode pk = b.putArray( "primary_key" );
        for ( String p : d.pkBase ) {
          pk.add( p );
        }
      }
      if ( d.mensajeBase != null ) {
        b.put( "message", d.mensajeBase );
      }
    }
    ArrayNode rs = o.putArray( "relationships" );
    for ( Relacion rel : rels ) {
      ObjectNode x = rs.addObject();
      x.put( "parent", rel.padre );
      x.put( "child", rel.hijo );
      x.put( "columns", rel.etiqueta );
      x.put( "parent_table", rel.tablaPadre );
      if ( !vacio( rel.columnasPadre ) ) {
        x.put( "parent_columns", rel.columnasPadre );
      }
      x.put( "source", rel.origen );
    }
    return o;
  }

  // ---------------------------------------------------------------- utilidades de Mermaid

  /**
   * Identificador para Mermaid 9+: solo [A-Za-z0-9_], empieza con letra, sin acentos. "año" -> "ano",
   * "VARCHAR(100)" -> "VARCHAR_100", "2026" -> "c_2026", "PK" -> "PK_".
   */
  static String identificador( String s, String porDefecto ) {
    String t = s == null ? "" : Normalizer.normalize( s, Normalizer.Form.NFD ).replaceAll( "\\p{M}+", "" );
    t = t.replaceAll( "[^A-Za-z0-9_]+", "_" ).replaceAll( "^_+|_+$", "" );
    if ( t.isEmpty() ) {
      return porDefecto;
    }
    char c0 = t.charAt( 0 );
    if ( !( ( c0 >= 'A' && c0 <= 'Z' ) || ( c0 >= 'a' && c0 <= 'z' ) ) ) {
      t = porDefecto + "_" + t;
    }
    if ( t.equals( "PK" ) || t.equals( "FK" ) || t.equals( "UK" ) || t.equalsIgnoreCase( "erDiagram" ) ) {
      t = t + "_";
    }
    return t;
  }

  /** Texto entre comillas de Mermaid: sin comillas dobles ni saltos, hasta 80 caracteres. */
  static String comentario( String s ) {
    String t = s == null ? "" : s.replace( '"', '\'' ).replaceAll( "[\\r\\n\\t]+", " " ).replace( "#", "" )
      .replace( ";", "," ).trim();
    return t.length() > 80 ? t.substring( 0, 79 ) + "…" : t;
  }

  private static String unico( String base, Set<String> usados ) {
    String n = base;
    int i = 2;
    while ( !usados.add( n.toLowerCase( Locale.ROOT ) ) ) {
      n = base + "_" + i++;
    }
    return n;
  }

  private static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }
}
