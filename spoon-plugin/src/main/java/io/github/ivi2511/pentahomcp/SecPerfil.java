package io.github.ivi2511.pentahomcp;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Seccion profile (diseno §2.4.3): ejecuta una COPIA recortada de la transformacion hasta el step
 * elegido (RunMotor.recortar: lo de abajo queda como Dummy) y un RowListener acumula estadisticas por
 * campo sin guardar filas: cuenta, nulos, vacios, largos, minimo y maximo (con metadata de storage
 * normal: el CSV con conversion perezosa entrega bytes), media, desvio y suma, distintos (con tope) y
 * los valores mas frecuentes. Por defecto, las primeras 5.000 filas; 60 s como mucho.
 * <p>
 * Los numeros y las fechas se muestran con el idioma del informe y nunca con el locale de la JVM ni
 * con la mascara del campo: las fechas en ISO (2024-05-01), los decimales con la coma o el punto del
 * idioma. En Seccion.datos van crudos (numeros JSON, fechas ISO).
 *
 * @dueño WP4
 */
final class SecPerfil implements Recolector {

  /** Tope de valores distintos que se cuentan por campo (despues es "al menos"). */
  static final int MAX_DISTINTOS = 5000;
  /** Valores mas frecuentes por campo. */
  static final int TOP = 5;
  /** Tope de tiempo de la seccion. */
  static final long TOPE_MS = 60000;
  /** Tope de filas aunque se pidan mas. */
  static final int MAX_FILAS = 1000000;
  /** Largo maximo de un valor de texto en las tablas. */
  static final int MAX_TEXTO = 60;
  /**
   * Largo con el que se cuentan los distintos de un texto: lo que pasa se corta y lleva una marca con su
   * hash (dos textos largos distintos siguen siendo distintos). Asi 5.000 valores de una columna CLOB no
   * ocupan 5.000 textos enteros en memoria.
   */
  static final int MAX_CLAVE = 200;
  /** Largo maximo de un texto en los datos crudos (JSON y get_report_data). */
  static final int MAX_DATOS = 200;

  /** Como termino la corrida del perfil. */
  static final String TERMINADA = "finished";
  static final String TOPE_FILAS = "row_limit";
  static final String TIEMPO = "timeout";
  static final String CANCELADA = "cancelled";
  static final String FALLO_INICIO = "init_failed";

  @Override
  public String id() {
    return SeccionId.PERFIL;
  }

  @Override
  public String noAplica( Captura c, String idioma ) {
    if ( c == null || !c.esTransformacion() || !( c.copia instanceof TransMeta ) ) {
      return Rotulos.de( SecMetricas.AREA, idioma ).s( "profile.only_trans" );
    }
    return null;
  }

  @Override
  public Seccion armar( Contexto ctx ) throws Exception {
    Rotulos r = ctx.rotulos( SecMetricas.AREA );
    String titulo = SeccionId.titulo( id(), ctx.idioma() );
    Captura c = ctx.captura();
    String motivo = noAplica( c, ctx.idioma() );
    if ( motivo != null ) {
      return Seccion.omitida( id(), titulo, motivo );
    }
    List<String> pasos = ctx.pedido().pasosPerfil();
    if ( pasos.isEmpty() ) {
      return Seccion.omitida( id(), titulo, r.s( "profile.no_step" ) );
    }
    int filas = ctx.pedido().perfilFilas > 0 ? ctx.pedido().perfilFilas
      : ( ctx.tema() != null && ctx.tema().perfil != null && ctx.tema().perfil.filas > 0 ? ctx.tema().perfil.filas : 5000 );
    filas = Math.min( filas, MAX_FILAS );
    boolean mostrar = ctx.tema() == null || ctx.tema().perfil == null || ctx.tema().perfil.mostrarValores;

    TransMeta copia = (TransMeta) ReportTools.clonar( c.copia );
    copia.setRepository( c.copia.getRepository() );
    copia.setMetaStore( c.copia.getMetaStore() );
    List<StepMeta> objetivos = new ArrayList<StepMeta>();
    List<String> faltan = new ArrayList<String>();
    for ( String step : pasos ) {
      StepMeta o = CorridaMedida.buscarStep( copia, step );
      if ( o == null ) {
        faltan.add( step );
      } else if ( !objetivos.contains( o ) ) {
        objetivos.add( o );
      }
    }
    if ( objetivos.isEmpty() ) {
      Seccion s = new Seccion( id(), titulo );
      s.estado = Seccion.ERROR;
      s.motivo = r.f( "profile.no_such_step", faltan.isEmpty() ? pasos.get( 0 ) : faltan.get( 0 ) );
      return s;
    }
    long espera = Math.max( 1000, Math.min( TOPE_MS, ctx.msRestantes() - 2000 ) );
    if ( objetivos.size() == 1 && faltan.isEmpty() ) {
      ctx.progreso().paso( r.f( "profile.running", objetivos.get( 0 ).getName() ), 0, 0 );
      Perfil p = perfilar( copia, objetivos.get( 0 ), filas, espera, SecMetricas.progreso( ctx ) );
      return seccion( p, titulo, r, mostrar );
    }
    List<String> nombres = new ArrayList<String>();
    for ( StepMeta o : objetivos ) {
      nombres.add( o.getName() );
    }
    ctx.progreso().paso( r.f( "profile.running", CorridaMedida.lista( nombres, r ) ), 0, 0 );
    List<Perfil> ps = perfilarVarios( copia, objetivos, filas, espera, SecMetricas.progreso( ctx ) );
    return seccionVarios( ps, faltan, titulo, r, mostrar );
  }

  // ---------------------------------------------------------------- ejecutar y escuchar

  /** Resultado de perfilar un step. */
  static final class Perfil {
    String step;
    int limite;
    long filas;
    String estado;
    long ms;
    int quitados;
    String falloInicio;
    String faltaPlugin;
    long errores;
    String log;
    final List<String> escritores = new ArrayList<String>();
    final List<Campo> campos = new ArrayList<Campo>();
  }

  /**
   * Perfila 'objetivo' sobre la copia (que queda recortada: tiene que ser propia). Corre solo los
   * ancestros del step; lo que escriben esos ancestros se escribe de verdad (la lista queda en
   * Perfil.escritores).
   */
  static Perfil perfilar( TransMeta copia, StepMeta objetivo, final int maxFilas, long esperaMs, Progreso pr )
    throws Exception {
    return perfilarVarios( copia, Collections.singletonList( objetivo ), maxFilas, esperaMs, pr ).get( 0 );
  }

  /**
   * Perfila varios steps en UNA corrida de la copia (recortada a los ancestros de todos): un RowListener y
   * una cuenta por step. Para cuando todos llegaron a su tope de filas, termina la corrida, se acaba el
   * tiempo o se cancela. Cada step tiene su propio estado; los escritores son la union de todos.
   */
  static List<Perfil> perfilarVarios( TransMeta copia, List<StepMeta> objetivos, final int maxFilas, long esperaMs,
    Progreso pr ) throws Exception {
    final List<Perfil> ps = new ArrayList<Perfil>();
    List<String> escritores = new ArrayList<String>();
    for ( StepMeta o : objetivos ) {
      for ( String w : CorridaMedida.escritoresAguasArriba( copia, o.getName() ) ) {
        if ( !escritores.contains( w ) ) {
          escritores.add( w );
        }
      }
    }
    for ( StepMeta o : objetivos ) {
      Perfil p = new Perfil();
      p.step = o.getName();
      p.limite = maxFilas;
      p.escritores.addAll( escritores );
      ps.add( p );
    }
    String falta = null;
    for ( StepMeta o : objetivos ) {
      if ( o.isMissing() ) {
        falta = o.getName();
        break;
      }
    }
    if ( falta == null ) {
      int quitados = RunMotor.recortar( copia, objetivos );
      for ( Perfil p : ps ) {
        p.quitados = quitados;
      }
      for ( StepMeta s : copia.getSteps() ) {
        if ( s.isMissing() ) {
          falta = s.getName();
          break;
        }
      }
    }
    if ( falta != null ) {
      for ( Perfil p : ps ) {
        p.faltaPlugin = falta;
        p.estado = FALLO_INICIO;
      }
      return ps;
    }
    final Trans t = new Trans( copia, RunMotor.padreDeLog() );
    t.setLogLevel( LogLevel.MINIMAL );
    t.setPreview( true );
    RunMotor.prepararVariables( t, copia );
    String canal = t.getLogChannelId();
    long inicio = System.currentTimeMillis();
    try {
      try {
        t.prepareExecution( null );
      } catch ( Exception e ) {
        String fallo = RunMotor.primeraLinea( e );
        String log = RunMotor.colaLog( t.getLogChannelId(), true );
        for ( Perfil p : ps ) {
          p.estado = FALLO_INICIO;
          p.falloInicio = fallo;
          p.errores = Math.max( 1, t.getErrors() );
          p.log = log;
        }
        return ps;
      }
      final long[] cuentas = new long[ ps.size() ];
      for ( int k = 0; k < ps.size(); k++ ) {
        final int j = k;
        final Perfil p = ps.get( k );
        for ( StepInterface si : t.findStepInterfaces( objetivos.get( k ).getName() ) ) {
          si.addRowListener( new RowAdapter() {
            @Override
            public void rowWrittenEvent( RowMetaInterface rm, Object[] row ) throws KettleStepException {
              synchronized ( ps ) { // varias copias del step (y varios steps) escriben a la vez
                if ( cuentas[ j ] >= maxFilas ) {
                  return;
                }
                try {
                  if ( p.campos.isEmpty() ) {
                    for ( ValueMetaInterface v : rm.getValueMetaList() ) {
                      p.campos.add( new Campo( v ) );
                    }
                  }
                  for ( int i = 0; i < p.campos.size(); i++ ) {
                    p.campos.get( i ).agregar( row != null && i < row.length ? row[ i ] : null );
                  }
                  cuentas[ j ]++;
                } catch ( Exception e ) {
                  throw new KettleStepException( e );
                }
              }
            }
          } );
        }
      }
      t.startThreads();
      long limite = inicio + esperaMs;
      String fin = TERMINADA;
      while ( !t.isFinished() ) {
        synchronized ( ps ) {
          boolean todos = true;
          for ( long n : cuentas ) {
            todos &= n >= maxFilas;
          }
          if ( todos ) {
            fin = TOPE_FILAS;
            break;
          }
        }
        if ( pr != null && pr.cancelado() ) {
          fin = CANCELADA;
          break;
        }
        if ( System.currentTimeMillis() > limite ) {
          fin = TIEMPO;
          break;
        }
        RunMotor.dormir( 20 );
      }
      if ( !t.isFinished() ) {
        t.stopAll();
        RunMotor.esperarFin( t, 10000 );
      }
      long ms = System.currentTimeMillis() - inicio;
      long erroresCorrida = t.getErrors();
      String log = erroresCorrida > 0 ? RunMotor.colaLog( t.getLogChannelId(), true ) : null;
      for ( int k = 0; k < ps.size(); k++ ) {
        Perfil p = ps.get( k );
        synchronized ( ps ) {
          p.filas = cuentas[ k ];
        }
        p.estado = p.filas >= maxFilas ? TOPE_FILAS : fin;
        p.ms = ms;
        p.errores = TOPE_FILAS.equals( p.estado ) || CANCELADA.equals( p.estado ) || TIEMPO.equals( p.estado ) ? 0
          : erroresCorrida;
        p.log = p.errores > 0 ? log : null;
        if ( p.campos.isEmpty() ) { // no salio ninguna fila: los campos igual se conocen
          try {
            RowMetaInterface rm = copia.getStepFields( objetivos.get( k ) );
            for ( ValueMetaInterface v : rm.getValueMetaList() ) {
              p.campos.add( new Campo( v ) );
            }
          } catch ( Exception ignored ) {
            // sin campos
          }
        }
      }
      return ps;
    } catch ( Exception e ) {
      t.stopAll();
      throw e;
    } finally {
      CorridaMedida.limpiar( canal );
    }
  }

  // ---------------------------------------------------------------- acumulador por campo

  /** Estadisticas de un campo, sin guardar filas. Todo en el tipo nativo de Kettle. */
  static final class Campo {
    final ValueMetaInterface vm;
    /** La misma metadata con storage normal, para comparar y convertir. */
    final ValueMetaInterface normal;
    /** Binary o Serializable: solo cuenta y nulos. */
    final boolean opaco;
    long n;
    long nulos;
    long vacios;
    Object min;
    Object max;
    int largoMin = Integer.MAX_VALUE;
    int largoMax = -1;
    /** Welford: media y desvio sin perder precision con muchos valores. */
    long numericos;
    double media;
    double m2;
    double suma;
    BigDecimal sumaExacta;
    final Map<Object, long[]> cuenta = new HashMap<Object, long[]>();
    boolean desbordado;

    Campo( ValueMetaInterface vm ) {
      this.vm = vm;
      this.normal = vm.clone();
      this.normal.setStorageType( ValueMetaInterface.STORAGE_TYPE_NORMAL );
      int t = vm.getType();
      this.opaco = t == ValueMetaInterface.TYPE_BINARY || t == ValueMetaInterface.TYPE_SERIALIZABLE;
    }

    void agregar( Object crudo ) throws Exception {
      n++;
      if ( crudo == null || vm.isNull( crudo ) ) {
        nulos++;
        return;
      }
      Object v = vm.isStorageBinaryString() ? vm.convertBinaryStringToNativeType( (byte[]) crudo ) : crudo;
      if ( v == null ) {
        nulos++;
        return;
      }
      if ( opaco ) {
        return;
      }
      if ( v instanceof String ) {
        String s = (String) v;
        if ( s.trim().isEmpty() ) {
          vacios++;
        }
        largoMin = Math.min( largoMin, s.length() );
        largoMax = Math.max( largoMax, s.length() );
      }
      if ( min == null || normal.compare( v, min ) < 0 ) {
        min = v;
      }
      if ( max == null || normal.compare( v, max ) > 0 ) {
        max = v;
      }
      if ( normal.isNumeric() ) {
        Double d = normal.getNumber( v );
        if ( d != null && !d.isNaN() && !d.isInfinite() ) {
          numericos++;
          double delta = d - media;
          media += delta / numericos;
          m2 += delta * ( d - media );
          suma += d;
        }
        if ( v instanceof BigDecimal ) {
          sumaExacta = sumaExacta == null ? (BigDecimal) v : sumaExacta.add( (BigDecimal) v );
        }
      }
      Object k = clave( v );
      long[] c = cuenta.get( k );
      if ( c != null ) {
        c[ 0 ]++;
      } else if ( cuenta.size() < MAX_DISTINTOS ) {
        cuenta.put( k, new long[] { 1 } );
      } else {
        desbordado = true;
      }
    }

    /**
     * 2.50 y 2.5 son el mismo valor (BigDecimal.equals mira la escala). Un texto de mas de
     * {@link #MAX_CLAVE} caracteres se cuenta por su principio y un hash del texto entero.
     */
    static Object clave( Object v ) {
      if ( v instanceof BigDecimal ) {
        BigDecimal b = (BigDecimal) v;
        return b.signum() == 0 ? BigDecimal.ZERO : b.stripTrailingZeros();
      }
      if ( v instanceof String && ( (String) v ).length() > MAX_CLAVE ) {
        String t = (String) v;
        return t.substring( 0, MAX_CLAVE ) + "…#" + Integer.toHexString( t.hashCode() );
      }
      return v;
    }

    long noNulos() {
      return n - nulos;
    }

    double pctNulos() {
      return n == 0 ? 0 : 100.0 * nulos / n;
    }

    boolean unico() {
      return !opaco && !desbordado && cuenta.size() == noNulos() && noNulos() > 0;
    }

    /** Desvio estandar muestral (n - 1). */
    double desvio() {
      return numericos < 2 ? 0 : Math.sqrt( Math.max( 0, m2 / ( numericos - 1 ) ) );
    }

    boolean esTexto() {
      return normal.isString();
    }

    boolean esEntero() {
      return normal.getType() == ValueMetaInterface.TYPE_INTEGER;
    }

    /** Los mas frecuentes: por cuenta descendente y, ante empate, por valor ascendente. */
    List<Map.Entry<Object, long[]>> top() {
      List<Map.Entry<Object, long[]>> l = new ArrayList<Map.Entry<Object, long[]>>( cuenta.entrySet() );
      Collections.sort( l, new Comparator<Map.Entry<Object, long[]>>() {
        @Override
        public int compare( Map.Entry<Object, long[]> a, Map.Entry<Object, long[]> b ) {
          int c = Long.compare( b.getValue()[ 0 ], a.getValue()[ 0 ] );
          if ( c != 0 ) {
            return c;
          }
          try {
            return normal.compare( a.getKey(), b.getKey() );
          } catch ( Exception e ) {
            return 0;
          }
        }
      } );
      return l.size() > TOP ? new ArrayList<Map.Entry<Object, long[]>>( l.subList( 0, TOP ) ) : l;
    }
  }

  // ---------------------------------------------------------------- seccion

  static Seccion seccion( Perfil p, String titulo, Rotulos r, boolean mostrar ) {
    Seccion s = new Seccion( SeccionId.PERFIL, titulo );
    s.datos = datos( p, mostrar );
    if ( p.faltaPlugin != null ) {
      s.estado = Seccion.ERROR;
      s.motivo = r.f( "profile.missing_plugin", p.faltaPlugin );
      return s;
    }
    if ( FALLO_INICIO.equals( p.estado ) ) {
      s.estado = Seccion.ERROR;
      s.motivo = r.f( "profile.init_failed", p.falloInicio );
      String e = SecMetricas.primerError( p.log );
      if ( e != null && !e.equals( p.falloInicio ) ) {
        s.con( SecMetricas.aviso( Aviso.ERROR, r.f( "metrics.first_error", e ), "profile-error" ) );
      }
      return s;
    }
    if ( TIEMPO.equals( p.estado ) ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.f( "profile.timeout", SecMetricas.duracion( p.ms, r ), p.filas );
    } else if ( CANCELADA.equals( p.estado ) ) {
      s.estado = Seccion.PARCIAL;
      s.motivo = r.f( "profile.cancelled", p.filas );
    }

    // resumen
    Campo masNulos = null;
    for ( Campo c : p.campos ) {
      if ( c.nulos > 0 && ( masNulos == null || c.nulos > masNulos.nulos ) ) {
        masNulos = c;
      }
    }
    StringBuilder res = new StringBuilder( r.f( TOPE_FILAS.equals( p.estado ) ? "summary.profile.sample"
      : "summary.profile.all", p.step, p.filas, p.campos.size() ) );
    if ( p.filas > 0 ) {
      res.append( ' ' );
      res.append( masNulos == null ? r.s( "summary.profile.no_nulls" )
        : r.f( "summary.profile.nulls", masNulos.vm.getName(), pct( masNulos.pctNulos(), r ) ) );
    }
    s.resumen = res.toString();

    // avisos: muestra, escritores, privacidad, errores
    if ( p.filas == 0 ) {
      s.con( SecMetricas.aviso( Aviso.WARN, r.f( "profile.no_rows", p.step ), "profile-no-rows" ) );
    } else {
      s.con( SecMetricas.aviso( Aviso.INFO, TOPE_FILAS.equals( p.estado ) ? r.f( "profile.sample", p.filas, p.step )
        : r.f( "profile.all", p.filas, p.step ), "profile-sample" ) );
    }
    if ( Seccion.PARCIAL.equals( s.estado ) ) {
      s.con( SecMetricas.aviso( Aviso.WARN, s.motivo, "profile-partial" ) );
    }
    if ( !p.escritores.isEmpty() ) {
      s.con( SecMetricas.aviso( Aviso.WARN, r.f( "profile.writers", CorridaMedida.lista( p.escritores, r ) ),
        "profile-writers" ) );
    }
    if ( p.errores > 0 ) {
      String e = SecMetricas.primerError( p.log );
      s.con( SecMetricas.aviso( Aviso.ERROR, r.f( "profile.errors", p.errores ) + ( e == null ? "" : " " + r.f(
        "metrics.first_error", e ) ), "profile-errors" ) );
    }
    if ( !mostrar ) {
      s.con( SecMetricas.aviso( Aviso.INFO, r.s( "profile.hidden" ), "profile-hidden" ) );
    }
    if ( p.campos.isEmpty() ) {
      return s;
    }

    // tabla principal: un campo por fila
    boolean desbordado = false;
    Tabla t = new Tabla( r.s( "col.field" ), r.s( "col.type" ), r.s( "col.nulls" ), r.s( "col.blanks" ), r.s(
      "col.distinct" ), r.s( "col.length" ), r.s( "col.min" ), r.s( "col.max" ) );
    t.id = "profile-fields";
    t.titulo = r.s( "table.profile.title" );
    t.alineacion = new ArrayList<String>( java.util.Arrays.asList( "l", "l", "r", "r", "r", "r", "l", "l" ) );
    for ( Campo c : p.campos ) {
      desbordado |= c.desbordado;
      String nulos = SecMetricas.entero( c.nulos, r ) + " (" + pct( c.pctNulos(), r ) + " %)";
      String distintos = c.opaco ? "" : ( c.desbordado ? "≥ " : "" ) + SecMetricas.entero( c.cuenta.size(), r )
        + ( c.unico() ? " " + r.s( "profile.unique" ) : "" );
      String largo = c.largoMax < 0 ? "" : c.largoMin == c.largoMax ? String.valueOf( c.largoMax )
        : c.largoMin + "–" + c.largoMax;
      boolean oculto = !mostrar && c.esTexto();
      t.fila( c.vm.getName(), c.vm.getTypeDesc(), nulos, c.esTexto() ? SecMetricas.entero( c.vacios, r ) : "",
        distintos, largo, oculto ? "—" : corto( valor( c.min, c, r.locale() ) ), oculto ? "—" : corto( valor(
          c.max, c, r.locale() ) ) );
    }
    t.pie = desbordado ? r.f( "profile.distinct_cap", MAX_DISTINTOS ) : null;
    s.con( t );

    // grafico: porcentaje de nulos por campo, de mas a menos
    List<Campo> orden = new ArrayList<Campo>( p.campos );
    Collections.sort( orden, new Comparator<Campo>() {
      @Override
      public int compare( Campo a, Campo b ) {
        return Double.compare( b.pctNulos(), a.pctNulos() );
      }
    } );
    Grafico g = new Grafico();
    g.id = "profile-nulls";
    g.clase = Grafico.BARRAS_H;
    g.titulo = r.s( "chart.nulls.title" );
    g.unidad = "%";
    Serie se = new Serie();
    se.nombre = r.s( "chart.nulls.series" );
    for ( int i = 0; i < orden.size() && i < SecMetricas.MAX_BARRAS; i++ ) {
      g.categorias.add( orden.get( i ).vm.getName() );
      se.valores.add( Math.round( orden.get( i ).pctNulos() * 10 ) / 10.0 );
    }
    g.series.add( se );
    if ( orden.size() > SecMetricas.MAX_BARRAS ) {
      g.nota = r.f( "chart.more", orden.size() - SecMetricas.MAX_BARRAS );
    }
    if ( masNulos != null ) { // sin nulos el grafico seria todo ceros: el resumen ya lo dice
      s.con( g );
    }

    // numericos: media, desvio y suma
    Tabla num = new Tabla( r.s( "col.field" ), r.s( "col.mean" ), r.s( "col.stddev" ), r.s( "col.sum" ) );
    num.id = "profile-numeric";
    num.titulo = r.s( "table.numeric.title" );
    num.anexo = true;
    num.alineacion = new ArrayList<String>( java.util.Arrays.asList( "l", "r", "r", "r" ) );
    for ( Campo c : p.campos ) {
      if ( c.numericos > 0 ) {
        num.fila( c.vm.getName(), estadistica( c.media, r.locale() ), estadistica( c.desvio(), r.locale() ), c.esEntero()
          ? SecMetricas.entero( Math.round( c.suma ), r ) : c.sumaExacta != null ? estadistica( c.sumaExacta, r.locale() )
            : estadistica( c.suma, r.locale() ) );
      }
    }
    if ( !num.filas.isEmpty() ) {
      s.con( num );
    }

    // valores mas frecuentes (datos reales: solo si se muestran)
    if ( mostrar && p.filas > 0 ) {
      Tabla top = new Tabla( r.s( "col.field" ), r.s( "col.top" ) );
      top.id = "profile-top";
      top.titulo = r.s( "table.top.title" );
      top.anexo = true;
      for ( Campo c : p.campos ) {
        List<Map.Entry<Object, long[]>> tops = c.opaco ? null : c.top();
        if ( tops == null || tops.isEmpty() || tops.get( 0 ).getValue()[ 0 ] < 2 ) {
          continue; // sin valores repetidos (ids, claves) la lista no dice nada
        }
        StringBuilder sb = new StringBuilder();
        for ( Map.Entry<Object, long[]> e : tops ) {
          sb.append( sb.length() == 0 ? "" : " · " ).append( corto( valor( e.getKey(), c, r.locale() ) ) ).append(
            " (" ).append( SecMetricas.entero( e.getValue()[ 0 ], r ) ).append( ')' );
        }
        top.fila( c.vm.getName(), sb.toString() );
      }
      if ( !top.filas.isEmpty() ) {
        s.con( top );
      }
    }
    return s;
  }

  /**
   * La seccion de un perfil de varios steps (una sola corrida): un resumen comun; los escritores que
   * corrieron, los errores y el aviso de valores ocultos una sola vez; y por cada step sus avisos, tablas y
   * grafico con el nombre del step en el titulo y el id con el sufijo "-k" (1, 2...).
   */
  static Seccion seccionVarios( List<Perfil> ps, List<String> faltan, String titulo, Rotulos r, boolean mostrar ) {
    Perfil p0 = ps.get( 0 );
    if ( p0.faltaPlugin != null || FALLO_INICIO.equals( p0.estado ) ) {
      Seccion e = seccion( p0, titulo, r, mostrar ); // la corrida es una: el mismo fallo para todos
      e.datos = datosVarios( ps, mostrar );
      return e;
    }
    Seccion s = new Seccion( SeccionId.PERFIL, titulo );
    s.datos = datosVarios( ps, mostrar );
    List<String> nombres = new ArrayList<String>();
    for ( Perfil p : ps ) {
      nombres.add( p.step );
    }
    s.resumen = r.f( "summary.profile.multi", ps.size(), CorridaMedida.lista( nombres, r ) );
    int m = 0;
    for ( String f : faltan ) {
      s.estado = Seccion.PARCIAL;
      String motivo = r.f( "profile.no_such_step", f );
      s.motivo = s.motivo == null ? motivo : s.motivo;
      s.con( SecMetricas.aviso( Aviso.ERROR, motivo, "profile-missing-" + ( ++m ) ) );
    }
    if ( !p0.escritores.isEmpty() ) {
      s.con( SecMetricas.aviso( Aviso.WARN, r.f( "profile.writers", CorridaMedida.lista( p0.escritores, r ) ),
        "profile-writers" ) );
    }
    for ( Perfil p : ps ) {
      if ( p.errores > 0 ) {
        String e = SecMetricas.primerError( p.log );
        s.con( SecMetricas.aviso( Aviso.ERROR, r.f( "profile.errors", p.errores ) + ( e == null ? "" : " " + r.f(
          "metrics.first_error", e ) ), "profile-errors" ) );
        break;
      }
    }
    if ( !mostrar ) {
      s.con( SecMetricas.aviso( Aviso.INFO, r.s( "profile.hidden" ), "profile-hidden" ) );
    }
    int k = 0;
    for ( Perfil p : ps ) {
      k++;
      Seccion u = seccion( p, titulo, r, mostrar );
      if ( Seccion.PARCIAL.equals( u.estado ) && !Seccion.PARCIAL.equals( s.estado ) ) {
        s.estado = Seccion.PARCIAL;
        s.motivo = u.motivo;
      }
      for ( Bloque b : u.bloques ) {
        if ( b == null || "profile-writers".equals( b.id ) || "profile-errors".equals( b.id ) || "profile-hidden"
          .equals( b.id ) ) {
          continue;
        }
        if ( "profile-fields".equals( b.id ) ) {
          b.titulo = r.f( "table.profile.title_step", p.step );
        } else if ( "profile-nulls".equals( b.id ) ) {
          b.titulo = r.f( "chart.nulls.title_step", p.step );
        } else if ( "profile-numeric".equals( b.id ) ) {
          b.titulo = r.f( "table.numeric.title_step", p.step );
        } else if ( "profile-top".equals( b.id ) ) {
          b.titulo = r.f( "table.top.title_step", p.step );
        }
        b.id = b.id == null ? null : b.id + "-" + k;
        s.con( b );
      }
    }
    return s;
  }

  /** datos de varios steps: el primero arriba (compatibilidad con un solo step), mas "steps" y "profiles". */
  static ObjectNode datosVarios( List<Perfil> ps, boolean mostrar ) {
    ObjectNode o = datos( ps.get( 0 ), mostrar );
    ArrayNode st = o.putArray( "steps" );
    ArrayNode pr = o.putArray( "profiles" );
    for ( Perfil p : ps ) {
      st.add( p.step );
      pr.add( datos( p, mostrar ) );
    }
    return o;
  }

  // ---------------------------------------------------------------- JSON

  /** Forma {@code data_profile}: valores crudos (numeros JSON, fechas ISO); sin valores si no se muestran. */
  static ObjectNode datos( Perfil p, boolean mostrar ) {
    ObjectNode o = ModeloJson.mapper().createObjectNode();
    o.put( "section", "profile" );
    o.put( "shape", "data_profile" );
    o.put( "step", p.step );
    o.put( "row_limit", p.limite );
    o.put( "sample_rows", p.filas );
    o.put( "state", p.estado );
    o.put( "ms", p.ms );
    o.put( "steps_excluded", p.quitados );
    o.put( "values_hidden", !mostrar );
    if ( p.errores > 0 ) {
      o.put( "errors", p.errores );
    }
    if ( p.falloInicio != null ) {
      o.put( "init_error", p.falloInicio );
    }
    if ( p.faltaPlugin != null ) {
      o.put( "missing_plugin", p.faltaPlugin );
    }
    ArrayNode w = o.putArray( "writers_ran" );
    for ( String x : p.escritores ) {
      w.add( x );
    }
    ArrayNode fs = o.putArray( "fields" );
    for ( Campo c : p.campos ) {
      ObjectNode x = fs.addObject();
      x.put( "name", c.vm.getName() );
      x.put( "type", c.vm.getTypeDesc() );
      x.put( "count", c.n );
      x.put( "nulls", c.nulos );
      x.put( "null_pct", Math.round( c.pctNulos() * 10 ) / 10.0 );
      if ( c.esTexto() ) {
        x.put( "blanks", c.vacios );
        if ( c.largoMax >= 0 ) {
          x.put( "min_length", c.largoMin );
          x.put( "max_length", c.largoMax );
        }
      }
      if ( !c.opaco ) {
        x.put( "distinct", c.cuenta.size() );
        x.put( "distinct_is_lower_bound", c.desbordado );
        x.put( "unique", c.unico() );
      }
      boolean oculto = !mostrar && c.esTexto();
      if ( c.min != null && !oculto ) {
        crudo( x, "min", c.min );
        crudo( x, "max", c.max );
      }
      if ( c.numericos > 0 ) {
        x.put( "mean", redondear( c.media ) );
        x.put( "stddev", redondear( c.desvio() ) );
        if ( c.sumaExacta != null ) {
          x.put( "sum", c.sumaExacta );
        } else {
          x.put( "sum", redondear( c.suma ) );
        }
      }
      if ( mostrar && !c.opaco ) {
        ArrayNode t = x.putArray( "top" );
        for ( Map.Entry<Object, long[]> e : c.top() ) {
          ObjectNode v = t.addObject();
          crudo( v, "value", e.getKey() );
          v.put( "count", e.getValue()[ 0 ] );
        }
      }
    }
    return o;
  }

  private static double redondear( double d ) {
    return Math.round( d * 1e4 ) / 1e4;
  }

  private static void crudo( ObjectNode o, String k, Object v ) {
    if ( v == null ) {
      o.putNull( k );
    } else if ( v instanceof Long || v instanceof Integer ) {
      o.put( k, ( (Number) v ).longValue() );
    } else if ( v instanceof Double ) {
      Double d = (Double) v;
      if ( d.isNaN() || d.isInfinite() ) {
        o.put( k, String.valueOf( d ) ); // NaN no es JSON valido
      } else {
        o.put( k, d );
      }
    } else if ( v instanceof BigDecimal ) {
      o.put( k, (BigDecimal) v );
    } else if ( v instanceof Boolean ) {
      o.put( k, (Boolean) v );
    } else if ( v instanceof Date ) {
      o.put( k, fechaIso( (Date) v ) );
    } else {
      o.put( k, recortarDato( String.valueOf( v ) ) );
    }
  }

  /** Un texto para los datos crudos: como mucho {@link #MAX_DATOS} caracteres (y «…»). */
  static String recortarDato( String s ) {
    return s.length() > MAX_DATOS ? s.substring( 0, MAX_DATOS ) + "…" : s;
  }

  // ---------------------------------------------------------------- formato (idioma del informe)

  /**
   * Un valor del campo para mostrar: fechas ISO, decimales con el separador del idioma y sin
   * agrupar (es un dato, no una cifra), enteros tal cual, textos tal cual.
   */
  static String valor( Object v, Campo c, Locale loc ) {
    if ( v == null ) {
      return "";
    }
    if ( v instanceof Date ) {
      return fechaIso( (Date) v );
    }
    if ( v instanceof Long || v instanceof Integer ) {
      return String.valueOf( v );
    }
    if ( v instanceof Double ) {
      double d = (Double) v;
      if ( d == Math.rint( d ) && Math.abs( d ) < 1e15 ) {
        return String.valueOf( (long) d );
      }
      return conSeparador( BigDecimal.valueOf( d ).stripTrailingZeros().toPlainString(), loc );
    }
    if ( v instanceof BigDecimal ) {
      BigDecimal b = (BigDecimal) v;
      return conSeparador( ( b.signum() == 0 ? BigDecimal.ZERO : b.stripTrailingZeros() ).toPlainString(), loc );
    }
    if ( v instanceof String || v instanceof Boolean ) {
      return String.valueOf( v );
    }
    try {
      String s = c.normal.getString( v );
      return s == null ? String.valueOf( v ) : s;
    } catch ( Exception e ) {
      return String.valueOf( v );
    }
  }

  private static String conSeparador( String plano, Locale loc ) {
    char d = DecimalFormatSymbols.getInstance( loc ).getDecimalSeparator();
    return d == '.' ? plano : plano.replace( '.', d );
  }

  /** Cifra calculada (media, desvio, suma): miles y 2 decimales del idioma. */
  static String estadistica( double d, Locale loc ) {
    DecimalFormat f = new DecimalFormat( "#,##0.##", DecimalFormatSymbols.getInstance( loc ) );
    return f.format( d );
  }

  static String estadistica( BigDecimal d, Locale loc ) {
    DecimalFormat f = new DecimalFormat( "#,##0.##", DecimalFormatSymbols.getInstance( loc ) );
    return f.format( d );
  }

  /** Porcentaje con un decimal del idioma ("10,0" en es). */
  static String pct( double d, Rotulos r ) {
    return String.format( r.locale(), "%.1f", d );
  }

  /** 2024-05-01 si es medianoche; si no, 2024-05-01 13:45:10 (y los ms si los hay). Digitos ASCII. */
  static String fechaIso( Date d ) {
    Calendar cal = Calendar.getInstance();
    cal.setTime( d );
    int ms = cal.get( Calendar.MILLISECOND );
    boolean medianoche = cal.get( Calendar.HOUR_OF_DAY ) == 0 && cal.get( Calendar.MINUTE ) == 0 && cal.get(
      Calendar.SECOND ) == 0 && ms == 0;
    String patron = medianoche ? "yyyy-MM-dd" : ( ms == 0 ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm:ss.SSS" );
    return new SimpleDateFormat( patron, Locale.ROOT ).format( d );
  }

  private static String corto( String s ) {
    if ( s == null ) {
      return "";
    }
    String x = s.replaceAll( "\\s+", " " );
    return x.length() > MAX_TEXTO ? x.substring( 0, MAX_TEXTO - 1 ) + "…" : x;
  }
}
