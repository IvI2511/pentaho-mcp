package io.github.ivi2511.pentahomcp;

import java.math.BigDecimal;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;

/**
 * Compara la salida del alumno con la esperada de un caso (docs/corrector-diseno.md §4) y arma la
 * {@link Comparacion} del §6: columnas, filas, ejemplos y los puntajes {@code columns_score}, {@code row_count_ok}
 * y {@code values_score}. No usa Kettle: corre en el worker (C4) y en las pruebas.
 * <ul>
 * <li><b>Columnas</b>: por nombre, sin mayusculas y con trim. Con {@code ignore_column_order=false} cuentan como
 * presentes solo las que estan en orden (la subsecuencia comun mas larga) y se avisa {@code order_differs}.
 * {@code extra_columns=penalize}: las que sobran suman al denominador de {@code columns_score}.</li>
 * <li><b>Celdas</b>: el tipo sale de {@code output.columns} del pack (los tipos de la solucion); sin tipo, se
 * deduce de la esperada. Numeros con el formato que declaro el alumno en su Text file output (mascara, decimal,
 * miles, moneda; decimal vacio = el del locale de la hija) y tolerancia {@code |a-b| <= max(tol, rel*max)}.
 * Fechas con la mascara del pack, las de {@code date_formats} y las ISO (captura step/query); la que declaro el
 * alumno va primero si esta entre esas. Texto con trim y mayusculas segun el pack ({@code 007} no es {@code 7}).
 * Vacio = null. {@code strict_format}: numeros con coma, miles o moneda, y fechas del archivo del alumno con otra
 * mascara que la esperada, son distintos.</li>
 * <li><b>Filas</b>: por clave si el pack la da (clave repetida en el alumno = {@code duplicate_keys}, no cuenta; la
 * firma del Group by sin Sort, D5), con {@code ignore_row_order=false} ademas las filas fuera de orden (las que no
 * estan en la subsecuencia creciente mas larga) pasan a distintas con un ejemplo de columna
 * {@value #COLUMNA_POSICION}. Sin clave (o si la clave falta en la salida del alumno o se repite en la esperada):
 * multiconjunto (iguales exactas, despues voraz con tolerancia, despues emparejamiento parcial con al menos la mitad
 * de las celdas iguales; con mas de {@value #LIMITE_VORAZ} filas, ordenar y recorrer) o por posicion.</li>
 * <li><b>Puntajes</b>: {@code values_score = iguales / max(esperadas, iguales + sobrantes)}; las columnas que faltan
 * no restan en values (ya restan en columns).</li>
 * <li><b>Renombres</b>: columna que falta y otra que sobra con al menos {@value #MIN_RENOMBRE} de los mismos valores
 * en las filas emparejadas.</li>
 * </ul>
 * Avisos (para {@code ResultadoCaso.warnings}): los de la lectura de la tabla del alumno, {@code order_differs},
 * {@code extra_columns}, {@code comma_decimal} (tambien con strict_format, cuando la coma resta) y los propios de esta
 * clase. Su {@code detail} lleva solo datos (nombres
 * de columnas, conteos, factores), nunca valores de celdas ni frases: la devolucion de un caso oculto conserva los
 * avisos, y el texto lo traduce quien lo muestra por {@code code}.
 *
 * @dueño C2
 */
final class CorrectorComparar {

  /** Todas las diferencias de una columna numerica son el valor esperado por un factor (D3). detail "col x100". */
  static final String ESCALA = "scaled_values";
  /** Texto del alumno con U+FFFD o UTF-8 leido como Latin-1 ({@code CafÃ©}) que la esperada no tiene (P17). */
  static final String MOJIBAKE = "mojibake";
  /**
   * Fechas iguales escritas con otra mascara que la esperada: aceptadas por date_formats, o distintas con
   * strict_format. detail = columnas.
   */
  static final String FORMATO_FECHA = "date_format";
  /** Con clave y ignore_row_order=false: filas en otro orden (pasan a distintas). detail = cuantas. */
  static final String ORDEN_FILAS = "row_order_differs";
  /** Una columna clave falta en la salida del alumno (o en la esperada): se compara sin clave. detail = columnas. */
  static final String SIN_CLAVE = "key_columns_missing";
  /** La clave se repite en la salida esperada: se compara sin clave. detail = columnas clave. */
  static final String CLAVE_NO_UNICA = "expected_key_not_unique";

  /** Columna de los ejemplos de filas fuera de orden: expected/actual = numero de fila (desde 1). */
  static final String COLUMNA_POSICION = "#";
  /** Filas a partir de las cuales el multiconjunto ordena y recorre en lugar del voraz O(n*m). */
  static final int LIMITE_VORAZ = 5000;
  /** Fraccion de valores iguales para sugerir un renombre. */
  static final double MIN_RENOMBRE = 0.8;

  /** Formas sin mascara: la de la captura step/query ({@link CorrectorTabla#texto}) y la por defecto de Kettle. */
  static final List<String> FECHAS_POR_DEFECTO = Collections.unmodifiableList( Arrays.asList( "yyyy-MM-dd",
    "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy/MM/dd HH:mm:ss.SSS",
    "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd" ) );

  private static final Pattern PLANO = Pattern.compile( "[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d{1,4})?" );
  private static final Pattern RARO = Pattern.compile( "\\x{FFFD}|[\\x{C3}\\x{C2}][\\x{80}-\\x{BF}]|\\x{E2}\\x{20AC}" );
  /** Simbolos de moneda que se sacan antes de leer un numero (euro, dolar, libra, yen, generico). */
  private static final String MONEDAS = "" + (char) 0x20AC + "$" + (char) 0xA3 + (char) 0xA5 + (char) 0xA4;
  private static final double[] FACTORES = { 100, 1000, 0.01, 0.001 };
  private static final String[] NOMBRES_FACTOR = { "x100", "x1000", "/100", "/1000" };
  /** Celda vacia en las firmas y claves canonicas. */
  private static final String NULO = String.valueOf( (char) 0 );
  /** Separador de celdas en las firmas y claves canonicas. */
  private static final char SEP = (char) 1;

  enum Clase {
    TEXTO, NUMERO, FECHA, BOOLEANO
  }

  private final OpcionesComparar op;
  private final Locale loc;
  private final List<Pack.Columna> tipos;
  private final BigDecimal tolerancia;
  private final BigDecimal relativa;
  private final int maxEjemplos;
  private final Map<String, SimpleDateFormat> mascaras = new HashMap<>();

  private CorrectorComparar( List<Pack.Columna> tipos, OpcionesComparar opciones, Locale locale ) {
    this.op = opciones == null ? new OpcionesComparar() : opciones;
    this.loc = locale == null ? Locale.US : locale;
    this.tipos = tipos == null ? Collections.<Pack.Columna>emptyList() : tipos;
    this.tolerancia = decimal( op.toleranciaNumerica );
    this.relativa = decimal( op.toleranciaRelativa );
    this.maxEjemplos = Math.max( 0, op.maxEjemplos );
  }

  /**
   * La comparacion de un caso con la captura tables: la suma de la de cada tabla. Columnas: las de cada tabla como
   * {@code tabla.columna}, con el puntaje pesado por las columnas esperadas; filas: sumadas; valores: pesado por
   * {@code max(esperadas, iguales + sobrantes)} de cada tabla (una tabla grande pesa mas); cantidad de filas: bien si
   * esta bien en todas; ejemplos y diferencias por columna con la tabla delante de la clave o la columna.
   */
  static Comparacion juntar( List<ResultadoCaso.ComparacionTabla> tablas ) {
    Comparacion c = new Comparacion();
    c.modo = Comparacion.POR_CLAVE;
    c.cantidadFilasOk = true;
    double colNum = 0;
    double colDen = 0;
    double valNum = 0;
    double valDen = 0;
    for ( ResultadoCaso.ComparacionTabla t : tablas ) {
      Comparacion x = t.comparacion;
      if ( x == null ) {
        continue;
      }
      String p = t.tabla + ".";
      if ( !Comparacion.POR_CLAVE.equals( x.modo ) ) {
        c.modo = x.modo;
      }
      for ( String k : x.columnasClave ) {
        c.columnasClave.add( p + k );
      }
      for ( String k : x.columnas.esperadas ) {
        c.columnas.esperadas.add( p + k );
      }
      for ( String k : x.columnas.reales ) {
        c.columnas.reales.add( p + k );
      }
      for ( String k : x.columnas.faltan ) {
        c.columnas.faltan.add( p + k );
      }
      for ( String k : x.columnas.sobran ) {
        c.columnas.sobran.add( p + k );
      }
      for ( Comparacion.Renombre r : x.columnas.renombres ) {
        c.columnas.renombres.add( new Comparacion.Renombre( p + r.esperada, p + r.real, r.coincidencia ) );
      }
      c.columnas.ordenDistinto |= x.columnas.ordenDistinto;
      c.filas.esperadas += x.filas.esperadas;
      c.filas.reales += x.filas.reales;
      c.filas.iguales += x.filas.iguales;
      c.filas.distintas += x.filas.distintas;
      c.filas.faltan += x.filas.faltan;
      c.filas.sobran += x.filas.sobran;
      c.filas.clavesRepetidas += x.filas.clavesRepetidas;
      c.cantidadFilasOk &= x.cantidadFilasOk;
      for ( Map.Entry<String, Integer> e : x.diferenciasPorColumna.entrySet() ) {
        c.diferenciasPorColumna.put( p + e.getKey(), e.getValue() );
      }
      if ( x.ejemplos != null ) {
        copiarEjemplos( x.ejemplos.distintas, c.ejemplos.distintas, t.tabla );
        copiarEjemplos( x.ejemplos.faltan, c.ejemplos.faltan, t.tabla );
        copiarEjemplos( x.ejemplos.sobran, c.ejemplos.sobran, t.tabla );
        copiarEjemplos( x.ejemplos.clavesRepetidas, c.ejemplos.clavesRepetidas, t.tabla );
      }
      double ne = x.columnas.esperadas.size();
      colNum += x.puntajeColumnas * ne;
      colDen += ne;
      double den = Math.max( x.filas.esperadas, x.filas.iguales + x.filas.sobran );
      valNum += x.puntajeValores * den;
      valDen += den;
    }
    c.puntajeColumnas = colDen == 0 ? 1.0 : colNum / colDen;
    c.puntajeValores = valDen == 0 ? 1.0 : valNum / valDen;
    return c;
  }

  private static void copiarEjemplos( List<Comparacion.Ejemplo> de, List<Comparacion.Ejemplo> a, String tabla ) {
    for ( Comparacion.Ejemplo e : de ) {
      Comparacion.Ejemplo x = new Comparacion.Ejemplo();
      x.clave = e.clave == null ? tabla : tabla + " · " + e.clave;
      x.columna = e.columna;
      x.esperado = e.esperado;
      x.real = e.real;
      x.veces = e.veces;
      x.fila = e.fila;
      a.add( x );
    }
  }

  /** Con los tipos, las opciones y el locale ({@code policy.locale}) del pack. avisos puede ser null. */
  static Comparacion comparar( CorrectorTabla esperada, CorrectorTabla real, Pack pack, List<Aviso> avisos ) {
    if ( pack == null ) {
      return comparar( esperada, real, null, null, Locale.US, avisos );
    }
    return comparar( esperada, real, pack.salida == null ? null : pack.salida.columnas, pack.comparar,
      pack.politica == null ? Locale.US : pack.politica.localeJava(), avisos );
  }

  /**
   * Compara y agrega a {@code avisos} (si no es null) los de la lectura de {@code real} y los de la comparacion.
   *
   * @param tipos    output.columns del pack (tipo y mascara de la solucion); null = deducir de la esperada
   * @param locale   el de la hija (policy.locale): da el simbolo decimal de un {@code <decimal/>} vacio
   */
  static Comparacion comparar( CorrectorTabla esperada, CorrectorTabla real, List<Pack.Columna> tipos,
    OpcionesComparar opciones, Locale locale, List<Aviso> avisos ) {
    if ( esperada == null || real == null ) {
      throw new IllegalArgumentException( "Faltan la tabla esperada o la del alumno" );
    }
    return new CorrectorComparar( tipos, opciones, locale ).correr( esperada, real, avisos );
  }

  // ------------------------------------------------------------------ comparacion

  private Comparacion correr( CorrectorTabla esp, CorrectorTabla real, List<Aviso> avisos ) {
    List<Aviso> av = new ArrayList<>( real.avisos );
    Comparacion c = new Comparacion();
    Comparacion.Columnas cc = c.columnas;
    int ne = esp.columnas.size();
    int nr = real.columnas.size();
    cc.esperadas.addAll( esp.columnas );
    cc.reales.addAll( real.columnas );

    // ---- columnas
    int[] mapa = new int[ ne ];
    boolean[] usada = new boolean[ nr ];
    int comunes = 0;
    for ( int i = 0; i < ne; i++ ) {
      mapa[ i ] = -1;
      for ( int j = 0; j < nr; j++ ) {
        if ( !usada[ j ] && real.columnas.get( j ).equalsIgnoreCase( esp.columnas.get( i ).trim() ) ) {
          mapa[ i ] = j;
          usada[ j ] = true;
          comunes++;
          break;
        }
      }
      if ( mapa[ i ] < 0 ) {
        cc.faltan.add( esp.columnas.get( i ) );
      }
    }
    for ( int j = 0; j < nr; j++ ) {
      if ( !usada[ j ] ) {
        cc.sobran.add( real.columnas.get( j ) );
      }
    }
    int presentes = comunes;
    if ( !op.ignorarOrdenColumnas && comunes > 0 ) {
      List<String> fuera = fueraDeOrden( esp.columnas, real.columnas );
      presentes = comunes - fuera.size();
      if ( !fuera.isEmpty() ) {
        cc.ordenDistinto = true;
        av.add( new Aviso( Aviso.ORDEN_COLUMNAS, unir( fuera ) ) );
      }
    }
    boolean penaliza = OpcionesComparar.EXTRA_RESTAR.equals( op.columnasDeMas );
    int denom = ne + ( penaliza ? cc.sobran.size() : 0 );
    c.puntajeColumnas = denom == 0 ? 1.0 : (double) presentes / denom;
    if ( !cc.sobran.isEmpty() && !penaliza ) {
      av.add( new Aviso( Aviso.COLUMNAS_DE_MAS, unir( cc.sobran ) ) );
    }

    List<Col> cols = new ArrayList<>();
    for ( int i = 0; i < ne; i++ ) {
      if ( mapa[ i ] >= 0 ) {
        cols.add( columna( esp, i, mapa[ i ] ) );
      }
    }

    // ---- celdas normalizadas
    int n = esp.cantidadFilas();
    int m = real.cantidadFilas();
    int nc = cols.size();
    Celda[][] e = new Celda[ n ][ nc ];
    Celda[][] r = new Celda[ m ][ nc ];
    Set<String> conComa = new LinkedHashSet<>();
    for ( int k = 0; k < nc; k++ ) {
      Col col = cols.get( k );
      for ( int i = 0; i < n; i++ ) {
        e[ i ][ k ] = celda( esp.celda( i, col.ie ), col, null, false );
      }
      CorrectorTabla.Formato f = real.formato( col.ir );
      for ( int j = 0; j < m; j++ ) {
        r[ j ][ k ] = celda( real.celda( j, col.ir ), col, f, true );
        if ( r[ j ][ k ].coma ) {
          conComa.add( col.nombre );
        }
      }
    }

    // ---- clave
    int[] clave = null;
    if ( op.conClave() ) {
      List<String> faltan = new ArrayList<>();
      int[] ks = new int[ op.columnasClave.size() ];
      for ( int q = 0; q < ks.length; q++ ) {
        String nombre = op.columnasClave.get( q );
        int ie = esp.indice( nombre );
        ks[ q ] = -1;
        for ( int k = 0; ie >= 0 && k < nc; k++ ) {
          if ( cols.get( k ).ie == ie ) {
            ks[ q ] = k;
          }
        }
        if ( ks[ q ] < 0 ) {
          faltan.add( nombre );
        }
      }
      if ( !faltan.isEmpty() ) {
        av.add( new Aviso( SIN_CLAVE, unir( faltan ) ) );
      } else if ( !clavesUnicas( e, ks ) ) {
        av.add( new Aviso( CLAVE_NO_UNICA, unir( op.columnasClave ) ) );
      } else {
        clave = ks;
      }
    }
    c.modo = clave != null ? Comparacion.POR_CLAVE
      : op.ignorarOrdenFilas ? Comparacion.MULTICONJUNTO : Comparacion.POR_POSICION;
    if ( clave != null ) {
      c.columnasClave.addAll( op.columnasClave );
    }

    // columnas que deciden si una fila emparejada es igual
    List<Integer> valores = new ArrayList<>();
    if ( clave != null ) {
      Set<Integer> enClave = new HashSet<>();
      for ( int k : clave ) {
        enClave.add( k );
      }
      for ( int k = 0; k < nc; k++ ) {
        if ( !enClave.contains( k ) ) {
          valores.add( k );
        }
      }
      if ( valores.isEmpty() && ne <= clave.length ) {
        for ( int k : clave ) {
          valores.add( k ); // la salida es solo la clave
        }
      }
    } else {
      for ( int k = 0; k < nc; k++ ) {
        valores.add( k );
      }
    }

    // ---- emparejar
    Comparacion.Filas filas = c.filas;
    Comparacion.Ejemplos ej = c.ejemplos;
    filas.esperadas = n;
    filas.reales = m;
    List<int[]> pares = new ArrayList<>();
    if ( clave != null ) {
      Map<String, List<Integer>> porClave = new LinkedHashMap<>();
      for ( int j = 0; j < m; j++ ) {
        String k = clave( r[ j ], clave );
        List<Integer> l = porClave.get( k );
        if ( l == null ) {
          porClave.put( k, l = new ArrayList<>() );
        }
        l.add( j );
      }
      Set<String> vistas = new HashSet<>();
      for ( int i = 0; i < n; i++ ) {
        String k = clave( e[ i ], clave );
        vistas.add( k );
        List<Integer> l = porClave.get( k );
        if ( l == null ) {
          filas.faltan++;
          agregar( ej.faltan, conClave( mostrarClave( esp, false, i, cols, clave ) ) );
        } else if ( l.size() > 1 ) {
          filas.clavesRepetidas++;
          String k2 = mostrarClave( esp, false, i, cols, clave );
          agregar( ej.clavesRepetidas, Comparacion.Ejemplo.repetida( k2, l.size() ) );
        } else {
          pares.add( new int[] { i, l.get( 0 ) } );
        }
      }
      for ( Map.Entry<String, List<Integer>> x : porClave.entrySet() ) {
        if ( vistas.contains( x.getKey() ) ) {
          continue;
        }
        for ( int j : x.getValue() ) {
          filas.sobran++;
          agregar( ej.sobran, conClave( mostrarClave( real, true, j, cols, clave ) ) );
        }
      }
    } else if ( !op.ignorarOrdenFilas ) {
      int min = Math.min( n, m );
      for ( int i = 0; i < min; i++ ) {
        pares.add( new int[] { i, i } );
      }
      for ( int i = min; i < n; i++ ) {
        filas.faltan++;
        agregar( ej.faltan, conFila( "#" + ( i + 1 ), esp, i ) );
      }
      for ( int j = min; j < m; j++ ) {
        filas.sobran++;
        agregar( ej.sobran, conFila( "#" + ( j + 1 ), real, j ) );
      }
    } else {
      multiconjunto( e, r, cols, pares );
      boolean[] parE = new boolean[ n ];
      boolean[] parR = new boolean[ m ];
      for ( int[] p : pares ) {
        parE[ p[ 0 ] ] = true;
        parR[ p[ 1 ] ] = true;
      }
      Collections.sort( pares, new Comparator<int[]>() {
        @Override
        public int compare( int[] a, int[] b ) {
          return Integer.compare( a[ 0 ], b[ 0 ] );
        }
      } );
      for ( int i = 0; i < n; i++ ) {
        if ( !parE[ i ] ) {
          filas.faltan++;
          agregar( ej.faltan, conFila( "#" + ( i + 1 ), esp, i ) );
        }
      }
      for ( int j = 0; j < m; j++ ) {
        if ( !parR[ j ] ) {
          filas.sobran++;
          agregar( ej.sobran, conFila( "#" + ( j + 1 ), real, j ) );
        }
      }
    }

    // ---- comparar las filas emparejadas
    int[] difPorCol = new int[ nc ];
    boolean[] igual = new boolean[ pares.size() ];
    Set<String> fechaOtraMascara = new LinkedHashSet<>();
    Map<Integer, Integer> factor = new LinkedHashMap<>(); // columna -> indice en FACTORES, -1 = no hay patron
    for ( int p = 0; p < pares.size(); p++ ) {
      int i = pares.get( p )[ 0 ];
      int j = pares.get( p )[ 1 ];
      boolean ok = !valores.isEmpty();
      String nombreFila = clave != null ? mostrarClave( esp, false, i, cols, clave ) : "#" + ( i + 1 );
      for ( int k : valores ) {
        Col col = cols.get( k );
        Celda a = e[ i ][ k ];
        Celda b = r[ j ][ k ];
        if ( col.clase == Clase.FECHA && a.convertido && b.convertido && a.valor.equals( b.valor )
          && !Objects.equals( a.patron, b.patron ) ) {
          fechaOtraMascara.add( col.nombre ); // misma fecha escrita con otra mascara (con strict_format resta)
        }
        if ( iguales( a, b, col ) ) {
          continue;
        }
        ok = false;
        difPorCol[ k ]++;
        agregar( ej.distintas, Comparacion.Ejemplo.diferencia( nombreFila, col.nombre, crudo( a ), crudo( b ) ) );
        if ( col.clase == Clase.NUMERO ) {
          anotarFactor( factor, k, a, b );
        }
      }
      igual[ p ] = ok;
    }
    if ( clave != null && !op.ignorarOrdenFilas && !pares.isEmpty() ) {
      int[] seq = new int[ pares.size() ];
      for ( int p = 0; p < seq.length; p++ ) {
        seq[ p ] = pares.get( p )[ 1 ];
      }
      boolean[] enOrden = subsecuenciaCreciente( seq );
      int fuera = 0;
      for ( int p = 0; p < seq.length; p++ ) {
        if ( enOrden[ p ] ) {
          continue;
        }
        fuera++;
        if ( igual[ p ] ) {
          igual[ p ] = false;
          int i = pares.get( p )[ 0 ];
          agregar( ej.distintas, Comparacion.Ejemplo.diferencia( mostrarClave( esp, false, i, cols, clave ),
            COLUMNA_POSICION, String.valueOf( i + 1 ), String.valueOf( seq[ p ] + 1 ) ) );
        }
      }
      if ( fuera > 0 ) {
        av.add( new Aviso( ORDEN_FILAS, String.valueOf( fuera ) ) );
      }
    }
    for ( boolean b : igual ) {
      if ( b ) {
        filas.iguales++;
      } else {
        filas.distintas++;
      }
    }
    for ( int k = 0; k < nc; k++ ) {
      if ( difPorCol[ k ] > 0 ) {
        c.diferenciasPorColumna.put( cols.get( k ).nombre, difPorCol[ k ] );
      }
    }

    // ---- renombres
    renombres( esp, real, mapa, usada, pares, cc );

    // ---- avisos de patrones (solo nombres de columnas)
    if ( !conComa.isEmpty() ) {
      av.add( new Aviso( Aviso.COMA_DECIMAL, unir( conComa ) ) );
    }
    if ( !fechaOtraMascara.isEmpty() ) {
      av.add( new Aviso( FORMATO_FECHA, unir( fechaOtraMascara ) ) );
    }
    List<String> escalas = new ArrayList<>();
    for ( Map.Entry<Integer, Integer> x : factor.entrySet() ) {
      if ( x.getValue() >= 0 ) {
        escalas.add( cols.get( x.getKey() ).nombre + " " + NOMBRES_FACTOR[ x.getValue() ] );
      }
    }
    if ( !escalas.isEmpty() ) {
      av.add( new Aviso( ESCALA, unir( escalas ) ) );
    }
    List<String> raras = mojibake( esp, real, cols );
    if ( !raras.isEmpty() ) {
      av.add( new Aviso( MOJIBAKE, unir( raras ) ) );
    }

    // ---- puntajes
    c.cantidadFilasOk = n == m;
    int d = Math.max( n, filas.iguales + filas.sobran );
    c.puntajeValores = d == 0 ? 1.0 : (double) filas.iguales / d;
    if ( avisos != null ) {
      avisos.addAll( av );
    }
    return c;
  }

  // ------------------------------------------------------------------ multiconjunto

  /** Empareja sin clave: iguales exactas por firma, voraz con tolerancia y emparejamiento parcial. */
  private void multiconjunto( Celda[][] e, Celda[][] r, final List<Col> cols, List<int[]> pares ) {
    int n = e.length;
    int m = r.length;
    if ( cols.isEmpty() ) {
      return; // sin columnas en comun no hay con que emparejar
    }
    // 1) iguales exactas (firma canonica)
    Map<String, ArrayDeque<Integer>> firmas = new HashMap<>();
    for ( int j = 0; j < m; j++ ) {
      String f = firma( r[ j ], cols, true );
      if ( f == null ) {
        continue;
      }
      ArrayDeque<Integer> q = firmas.get( f );
      if ( q == null ) {
        firmas.put( f, q = new ArrayDeque<>() );
      }
      q.add( j );
    }
    boolean[] usadaR = new boolean[ m ];
    List<Integer> restoE = new ArrayList<>();
    for ( int i = 0; i < n; i++ ) {
      ArrayDeque<Integer> q = firmas.get( firma( e[ i ], cols, false ) );
      Integer j = q == null ? null : q.poll();
      if ( j != null ) {
        usadaR[ j ] = true;
        pares.add( new int[] { i, j } );
      } else {
        restoE.add( i );
      }
    }
    List<Integer> restoR = new ArrayList<>();
    for ( int j = 0; j < m; j++ ) {
      if ( !usadaR[ j ] ) {
        restoR.add( j );
      }
    }
    if ( restoE.isEmpty() || restoR.isEmpty() ) {
      return;
    }
    // 2) iguales con tolerancia
    boolean grande = (long) restoE.size() * restoR.size() > (long) LIMITE_VORAZ * LIMITE_VORAZ;
    if ( !grande ) {
      for ( Iterator<Integer> it = restoE.iterator(); it.hasNext(); ) {
        int i = it.next();
        for ( Iterator<Integer> jt = restoR.iterator(); jt.hasNext(); ) {
          int j = jt.next();
          if ( filasIguales( e[ i ], r[ j ], cols ) ) {
            pares.add( new int[] { i, j } );
            it.remove();
            jt.remove();
            break;
          }
        }
      }
    } else {
      ordenarYRecorrer( e, r, cols, restoE, restoR, pares );
      return; // con tablas grandes no se buscan parecidas
    }
    // 3) parecidas: al menos la mitad de las celdas iguales
    int minimo = Math.max( 1, ( cols.size() + 1 ) / 2 );
    for ( Iterator<Integer> it = restoE.iterator(); it.hasNext() && !restoR.isEmpty(); ) {
      int i = it.next();
      int mejor = -1;
      int mejorIguales = minimo - 1;
      for ( int j : restoR ) {
        int k = cuantasIguales( e[ i ], r[ j ], cols );
        if ( k > mejorIguales ) {
          mejorIguales = k;
          mejor = j;
        }
      }
      if ( mejor >= 0 ) {
        pares.add( new int[] { i, mejor } );
        it.remove();
        restoR.remove( Integer.valueOf( mejor ) );
      }
    }
  }

  private void ordenarYRecorrer( final Celda[][] e, final Celda[][] r, final List<Col> cols, List<Integer> restoE,
    List<Integer> restoR, List<int[]> pares ) {
    Collections.sort( restoE, new Comparator<Integer>() {
      @Override
      public int compare( Integer a, Integer b ) {
        return orden( e[ a ], e[ b ], cols );
      }
    } );
    Collections.sort( restoR, new Comparator<Integer>() {
      @Override
      public int compare( Integer a, Integer b ) {
        return orden( r[ a ], r[ b ], cols );
      }
    } );
    int x = 0;
    int y = 0;
    while ( x < restoE.size() && y < restoR.size() ) {
      Celda[] a = e[ restoE.get( x ) ];
      Celda[] b = r[ restoR.get( y ) ];
      if ( filasIguales( a, b, cols ) ) {
        pares.add( new int[] { restoE.get( x++ ), restoR.get( y++ ) } );
      } else if ( orden( a, b, cols ) < 0 ) {
        x++;
      } else {
        y++;
      }
    }
  }

  private boolean filasIguales( Celda[] a, Celda[] b, List<Col> cols ) {
    for ( int k = 0; k < cols.size(); k++ ) {
      if ( !iguales( a[ k ], b[ k ], cols.get( k ) ) ) {
        return false;
      }
    }
    return true;
  }

  private int cuantasIguales( Celda[] a, Celda[] b, List<Col> cols ) {
    int c = 0;
    for ( int k = 0; k < cols.size(); k++ ) {
      if ( iguales( a[ k ], b[ k ], cols.get( k ) ) ) {
        c++;
      }
    }
    return c;
  }

  /** Orden total para ordenar y recorrer: null primero, despues convertidos, despues por valor. */
  private static int orden( Celda[] a, Celda[] b, List<Col> cols ) {
    for ( int k = 0; k < cols.size(); k++ ) {
      int c = orden( a[ k ], b[ k ] );
      if ( c != 0 ) {
        return c;
      }
    }
    return 0;
  }

  @SuppressWarnings( { "unchecked", "rawtypes" } )
  private static int orden( Celda a, Celda b ) {
    if ( a.valor == null || b.valor == null ) {
      return a.valor == null ? ( b.valor == null ? 0 : -1 ) : 1;
    }
    if ( a.convertido != b.convertido ) {
      return a.convertido ? -1 : 1;
    }
    if ( a.valor.getClass() == b.valor.getClass() && a.valor instanceof Comparable ) {
      return ( (Comparable) a.valor ).compareTo( b.valor );
    }
    return a.valor.toString().compareTo( b.valor.toString() );
  }

  // ------------------------------------------------------------------ renombres

  private void renombres( CorrectorTabla esp, CorrectorTabla real, int[] mapa, boolean[] usada, List<int[]> pares,
    Comparacion.Columnas cc ) {
    if ( pares.isEmpty() || cc.faltan.isEmpty() || cc.sobran.isEmpty() ) {
      return;
    }
    List<double[]> candidatos = new ArrayList<>(); // {i esperada, j real, coincidencia}
    for ( int i = 0; i < mapa.length; i++ ) {
      if ( mapa[ i ] >= 0 ) {
        continue;
      }
      Col col = columna( esp, i, -1 );
      for ( int j = 0; j < usada.length; j++ ) {
        if ( usada[ j ] ) {
          continue;
        }
        CorrectorTabla.Formato f = real.formato( j );
        int conValor = 0;
        int iguales = 0;
        for ( int[] p : pares ) {
          Celda a = celda( esp.celda( p[ 0 ], i ), col, null, false );
          if ( a.valor == null ) {
            continue;
          }
          conValor++;
          if ( iguales( a, celda( real.celda( p[ 1 ], j ), col, f, true ), col ) ) {
            iguales++;
          }
        }
        double x = conValor == 0 ? 0 : (double) iguales / conValor;
        if ( x >= MIN_RENOMBRE ) {
          candidatos.add( new double[] { i, j, x } );
        }
      }
    }
    Collections.sort( candidatos, new Comparator<double[]>() {
      @Override
      public int compare( double[] a, double[] b ) {
        int c = Double.compare( b[ 2 ], a[ 2 ] );
        return c != 0 ? c : Double.compare( a[ 0 ], b[ 0 ] );
      }
    } );
    Map<Integer, double[]> elegidos = new HashMap<>();
    Set<Integer> reales = new HashSet<>();
    for ( double[] x : candidatos ) {
      int i = (int) x[ 0 ];
      int j = (int) x[ 1 ];
      if ( !elegidos.containsKey( i ) && !reales.contains( j ) ) {
        elegidos.put( i, x );
        reales.add( j );
      }
    }
    for ( int i = 0; i < mapa.length; i++ ) {
      double[] x = elegidos.get( i );
      if ( x != null ) {
        cc.renombres.add( new Comparacion.Renombre( esp.columnas.get( i ), real.columnas.get( (int) x[ 1 ] ),
          Math.round( x[ 2 ] * 1000 ) / 1000.0 ) );
      }
    }
  }

  // ------------------------------------------------------------------ patrones

  /** Lleva, por columna numerica, el factor comun de todas sus diferencias (-1 si no lo hay). */
  private void anotarFactor( Map<Integer, Integer> factor, int k, Celda a, Celda b ) {
    Integer previo = factor.get( k );
    if ( previo != null && previo < 0 ) {
      return;
    }
    int cual = -1;
    if ( a.convertido && b.convertido && a.valor != null && b.valor != null ) {
      BigDecimal x = (BigDecimal) a.valor;
      BigDecimal y = (BigDecimal) b.valor;
      if ( x.signum() != 0 ) {
        for ( int q = 0; q < FACTORES.length && cual < 0; q++ ) {
          BigDecimal fx = x.multiply( BigDecimal.valueOf( FACTORES[ q ] ) );
          BigDecimal cota = tolerancia.multiply( BigDecimal.valueOf( Math.max( 1, FACTORES[ q ] ) ) )
            .max( fx.abs().multiply( new BigDecimal( "0.001" ) ) );
          if ( y.subtract( fx ).abs().compareTo( cota ) <= 0 ) {
            cual = q;
          }
        }
      }
    }
    factor.put( k, previo == null || previo == cual ? cual : -1 );
  }

  /** Columnas de texto del alumno con U+FFFD o mojibake que la esperada no tiene. */
  private static List<String> mojibake( CorrectorTabla esp, CorrectorTabla real, List<Col> cols ) {
    List<String> l = new ArrayList<>();
    for ( Col col : cols ) {
      if ( col.clase != Clase.TEXTO ) {
        continue;
      }
      boolean enReal = false;
      for ( int j = 0; j < real.cantidadFilas() && !enReal; j++ ) {
        String v = real.celda( j, col.ir );
        enReal = v != null && RARO.matcher( v ).find();
      }
      if ( !enReal ) {
        continue;
      }
      boolean enEsperada = false;
      for ( int i = 0; i < esp.cantidadFilas() && !enEsperada; i++ ) {
        String v = esp.celda( i, col.ie );
        enEsperada = v != null && RARO.matcher( v ).find();
      }
      if ( !enEsperada ) {
        l.add( col.nombre );
      }
    }
    return l;
  }

  // ------------------------------------------------------------------ celdas

  /** Una columna comparada: nombre de la esperada, indices, clase y mascaras de fecha aceptadas. */
  private static final class Col {

    final String nombre;
    final int ie;
    final int ir;
    final Clase clase;
    /** Fechas: la del pack, date_formats y las por defecto, sin repetir. */
    final List<String> mascaras;

    Col( String nombre, int ie, int ir, Clase clase, List<String> mascaras ) {
      this.nombre = nombre;
      this.ie = ie;
      this.ir = ir;
      this.clase = clase;
      this.mascaras = mascaras;
    }
  }

  /** Una celda leida: texto original y valor normalizado. */
  private static final class Celda {

    final String crudo;
    /** BigDecimal, Long (fecha en ms, UTC), Boolean o String normalizado; null = vacia. */
    Object valor;
    /** true si valor es del tipo de la columna; false = texto de respaldo. */
    boolean convertido;
    /** Numero con coma decimal. */
    boolean coma;
    /** Numero escrito sin formato (punto decimal, sin miles ni moneda). */
    boolean plano = true;
    /** Fecha: mascara con la que se leyo. */
    String patron;
    /** Celda del alumno con formato declarado (captura file). */
    boolean declarado;

    Celda( String crudo ) {
      this.crudo = crudo;
    }
  }

  private Col columna( CorrectorTabla esp, int ie, int ir ) {
    String nombre = esp.columnas.get( ie );
    Pack.Columna t = tipo( nombre );
    Clase clase = clase( t );
    if ( clase == null ) {
      clase = deducir( esp, ie );
    }
    List<String> ms = new ArrayList<>();
    if ( clase == Clase.FECHA ) {
      LinkedHashSet<String> s = new LinkedHashSet<>();
      if ( t != null && !CorrectorTabla.vacio( t.formato ) ) {
        s.add( t.formato );
      }
      if ( op.formatosFecha != null ) {
        for ( String f : op.formatosFecha ) {
          if ( !CorrectorTabla.vacio( f ) ) {
            s.add( f );
          }
        }
      }
      s.addAll( FECHAS_POR_DEFECTO );
      ms.addAll( s );
    }
    return new Col( nombre, ie, ir, clase, ms );
  }

  private Pack.Columna tipo( String nombre ) {
    for ( Pack.Columna c : tipos ) {
      if ( c != null && c.nombre != null && c.nombre.trim().equalsIgnoreCase( nombre.trim() ) ) {
        return c;
      }
    }
    return null;
  }

  private static Clase clase( Pack.Columna t ) {
    if ( t == null || CorrectorTabla.vacio( t.tipo ) ) {
      return null;
    }
    if ( t.esNumerica() ) {
      return Clase.NUMERO;
    }
    if ( t.esFecha() ) {
      return Clase.FECHA;
    }
    if ( "boolean".equalsIgnoreCase( t.tipo.trim() ) ) {
      return Clase.BOOLEANO;
    }
    return Clase.TEXTO;
  }

  /** Sin tipo en el pack: numero si todos los valores de la esperada son numeros planos, fecha si son ISO. */
  private Clase deducir( CorrectorTabla esp, int ie ) {
    boolean numeros = true;
    boolean fechas = true;
    int conValor = 0;
    for ( int i = 0; i < esp.cantidadFilas() && ( numeros || fechas ); i++ ) {
      String v = esp.celda( i, ie );
      if ( v == null || v.trim().isEmpty() ) {
        continue;
      }
      conValor++;
      String s = v.trim();
      numeros &= PLANO.matcher( s ).matches();
      fechas &= fecha( s, FECHAS_POR_DEFECTO, new String[ 1 ] ) != null;
    }
    return conValor == 0 ? Clase.TEXTO : numeros ? Clase.NUMERO : fechas ? Clase.FECHA : Clase.TEXTO;
  }

  private Celda celda( String crudo, Col col, CorrectorTabla.Formato f, boolean real ) {
    Celda c = new Celda( crudo );
    c.declarado = real && f != null;
    if ( crudo == null ) {
      return c;
    }
    String t = op.recortar ? crudo.trim() : crudo;
    if ( t.isEmpty() ) {
      return c;
    }
    String s = crudo.trim();
    switch ( col.clase ) {
      case NUMERO:
        Numero x = numero( s, f, loc );
        if ( x != null ) {
          c.valor = x.valor;
          c.convertido = true;
          c.coma = real && x.coma;
          c.plano = x.plano;
          return c;
        }
        break;
      case FECHA:
        String[] patron = new String[ 1 ];
        Long ms = fecha( s, real ? mascarasDelAlumno( col, f ) : col.mascaras, patron );
        if ( ms != null ) {
          c.valor = ms;
          c.convertido = true;
          c.patron = patron[ 0 ];
          return c;
        }
        break;
      case BOOLEANO:
        Boolean b = booleano( s );
        if ( b != null ) {
          c.valor = b;
          c.convertido = true;
          return c;
        }
        break;
      default:
        break;
    }
    String n = Normalizer.normalize( t, Normalizer.Form.NFC );
    c.valor = op.distinguirMayusculas ? n : n.toLowerCase( Locale.ROOT );
    return c;
  }

  /** La mascara que declaro el alumno va primero si es una de las aceptadas (desempata 04/05 dia-mes o mes-dia). */
  private static List<String> mascarasDelAlumno( Col col, CorrectorTabla.Formato f ) {
    if ( f == null || CorrectorTabla.vacio( f.mascara ) || !col.mascaras.contains( f.mascara )
      || col.mascaras.get( 0 ).equals( f.mascara ) ) {
      return col.mascaras;
    }
    List<String> l = new ArrayList<>( col.mascaras.size() );
    l.add( f.mascara );
    for ( String m : col.mascaras ) {
      if ( !m.equals( f.mascara ) ) {
        l.add( m );
      }
    }
    return l;
  }

  private boolean iguales( Celda a, Celda b, Col col ) {
    if ( a.valor == null || b.valor == null ) {
      return a.valor == null && b.valor == null;
    }
    if ( a.convertido != b.convertido ) {
      return false;
    }
    if ( !a.convertido ) {
      return a.valor.equals( b.valor );
    }
    switch ( col.clase ) {
      case NUMERO:
        if ( op.formatoEstricto && !b.plano ) {
          return false;
        }
        return cerca( (BigDecimal) a.valor, (BigDecimal) b.valor );
      case FECHA:
        if ( op.formatoEstricto && b.declarado && !Objects.equals( a.patron, b.patron ) ) {
          return false;
        }
        return a.valor.equals( b.valor );
      default:
        return a.valor.equals( b.valor );
    }
  }

  /** |a - b| <= max(tolerancia, relativa * max(|a|, |b|)). */
  private boolean cerca( BigDecimal a, BigDecimal b ) {
    BigDecimal d = a.subtract( b ).abs();
    BigDecimal cota = tolerancia;
    if ( relativa.signum() > 0 ) {
      cota = cota.max( relativa.multiply( a.abs().max( b.abs() ) ) );
    }
    return d.compareTo( cota ) <= 0;
  }

  /**
   * Firma exacta de una fila para el primer paso del multiconjunto: dos filas con la misma firma son iguales. null
   * si la fila del alumno tiene un numero con formato y strict_format (no se iguala por firma).
   */
  private String firma( Celda[] fila, List<Col> cols, boolean real ) {
    StringBuilder sb = new StringBuilder();
    for ( int k = 0; k < cols.size(); k++ ) {
      Celda c = fila[ k ];
      sb.append( canonica( c ) );
      if ( op.formatoEstricto && c.valor != null && c.convertido ) {
        if ( real && cols.get( k ).clase == Clase.NUMERO && !c.plano ) {
          return null;
        }
        if ( cols.get( k ).clase == Clase.FECHA ) {
          sb.append( '|' ).append( c.patron );
        }
      }
      sb.append( SEP );
    }
    return sb.toString();
  }

  private static String clave( Celda[] fila, int[] clave ) {
    StringBuilder sb = new StringBuilder();
    for ( int k : clave ) {
      sb.append( canonica( fila[ k ] ) ).append( SEP );
    }
    return sb.toString();
  }

  private static String canonica( Celda c ) {
    if ( c.valor == null ) {
      return NULO;
    }
    if ( !c.convertido ) {
      return "T" + c.valor;
    }
    if ( c.valor instanceof BigDecimal ) {
      return "N" + ( (BigDecimal) c.valor ).stripTrailingZeros().toPlainString();
    }
    if ( c.valor instanceof Long ) {
      return "F" + c.valor;
    }
    return "B" + c.valor;
  }

  private boolean clavesUnicas( Celda[][] e, int[] clave ) {
    Set<String> vistas = new HashSet<>();
    for ( Celda[] f : e ) {
      if ( !vistas.add( clave( f, clave ) ) ) {
        return false;
      }
    }
    return true;
  }

  // ------------------------------------------------------------------ numeros, fechas, booleanos

  /** Un numero leido y como estaba escrito. */
  static final class Numero {

    final BigDecimal valor;
    /** El decimal era la coma. */
    final boolean coma;
    /** Escrito como BigDecimal plano: punto decimal, sin miles, moneda ni porcentaje. */
    final boolean plano;

    Numero( BigDecimal valor, boolean coma, boolean plano ) {
      this.valor = valor;
      this.coma = coma;
      this.plano = plano;
    }
  }

  /**
   * Lee un numero con el formato que declaro el alumno (null = sin formato): primero plano ({@code 7.5}, salvo que su
   * mascara use el punto como separador de miles), despues con su mascara y simbolos (decimal y miles vacios = los
   * del locale), y si no, por heuristica (el ultimo de {@code .} y {@code ,} es el decimal; uno solo repetido es de
   * miles). null si no es un numero.
   */
  static Numero numero( String texto, CorrectorTabla.Formato f, Locale locale ) {
    if ( texto == null ) {
      return null;
    }
    String s = texto.trim();
    if ( s.isEmpty() ) {
      return null;
    }
    Locale loc = locale == null ? Locale.US : locale;
    if ( PLANO.matcher( s ).matches() && ( f == null || !puntoEsMiles( f, loc ) ) ) {
      BigDecimal v = decimal( s );
      if ( v != null ) {
        return new Numero( v, false, true );
      }
    }
    if ( f != null && f.declaraNumero() ) {
      Numero x = conFormato( s, f, loc );
      if ( x != null ) {
        return x;
      }
    }
    return heuristica( s, f, loc );
  }

  private static boolean puntoEsMiles( CorrectorTabla.Formato f, Locale loc ) {
    String m = sinLiterales( f.mascara );
    if ( m.indexOf( ',' ) < 0 ) {
      return false;
    }
    DecimalFormatSymbols dfs = DecimalFormatSymbols.getInstance( loc );
    char dec = CorrectorTabla.vacio( f.decimal ) ? dfs.getDecimalSeparator() : f.decimal.charAt( 0 );
    char grp = CorrectorTabla.vacio( f.grupo ) ? dfs.getGroupingSeparator() : f.grupo.charAt( 0 );
    return grp == '.' && dec != '.';
  }

  private static Numero conFormato( String texto, CorrectorTabla.Formato f, Locale loc ) {
    DecimalFormatSymbols dfs = DecimalFormatSymbols.getInstance( loc );
    char dec = CorrectorTabla.vacio( f.decimal ) ? dfs.getDecimalSeparator() : f.decimal.charAt( 0 );
    char grp = CorrectorTabla.vacio( f.grupo ) ? dfs.getGroupingSeparator() : f.grupo.charAt( 0 );
    String m = sinLiterales( f.mascara );
    String s = limpiar( texto, f.moneda, dfs );
    boolean negativo = s.length() > 2 && s.startsWith( "(" ) && s.endsWith( ")" );
    if ( negativo ) {
      s = s.substring( 1, s.length() - 1 );
    }
    boolean porcentaje = m.indexOf( '%' ) >= 0
      && ( s.endsWith( "%" ) || s.endsWith( String.valueOf( dfs.getPercent() ) ) );
    if ( porcentaje ) {
      s = s.substring( 0, s.length() - 1 );
    }
    if ( grp != dec && ( m.isEmpty() || m.indexOf( ',' ) >= 0 ) ) {
      s = s.replace( String.valueOf( grp ), "" );
    }
    boolean coma = false;
    if ( dec != '.' ) {
      if ( s.indexOf( '.' ) >= 0 ) {
        return null;
      }
      coma = dec == ',' && s.indexOf( ',' ) >= 0;
      s = s.replace( dec, '.' );
    }
    BigDecimal v = PLANO.matcher( s ).matches() ? decimal( s ) : null;
    if ( v == null ) {
      return null;
    }
    if ( negativo ) {
      v = v.negate();
    }
    if ( porcentaje ) {
      v = v.movePointLeft( 2 );
    }
    return new Numero( v, coma, false );
  }

  private static Numero heuristica( String texto, CorrectorTabla.Formato f, Locale loc ) {
    String s = limpiar( texto, f == null ? null : f.moneda, DecimalFormatSymbols.getInstance( loc ) );
    boolean negativo = s.length() > 2 && s.startsWith( "(" ) && s.endsWith( ")" );
    if ( negativo ) {
      s = s.substring( 1, s.length() - 1 );
    }
    int puntos = cuenta( s, '.' );
    int comas = cuenta( s, ',' );
    boolean coma = false;
    if ( puntos > 0 && comas > 0 ) {
      if ( s.lastIndexOf( ',' ) > s.lastIndexOf( '.' ) ) {
        s = s.replace( ".", "" ).replace( ',', '.' );
        coma = true;
      } else {
        s = s.replace( ",", "" );
      }
    } else if ( comas == 1 ) {
      s = s.replace( ',', '.' );
      coma = true;
    } else if ( comas > 1 ) {
      s = s.replace( ",", "" );
    } else if ( puntos > 1 ) {
      s = s.replace( ".", "" );
    }
    BigDecimal v = PLANO.matcher( s ).matches() ? decimal( s ) : null;
    if ( v == null ) {
      return null;
    }
    return new Numero( negativo ? v.negate() : v, coma, false );
  }

  /** Saca moneda, espacios (tambien los duros), apostrofes de miles y el menos tipografico. */
  private static String limpiar( String texto, String moneda, DecimalFormatSymbols dfs ) {
    String s = texto;
    if ( !CorrectorTabla.vacio( moneda ) ) {
      s = s.replace( moneda, "" );
    }
    String simbolo = dfs.getCurrencySymbol();
    if ( !CorrectorTabla.vacio( simbolo ) ) {
      s = s.replace( simbolo, "" );
    }
    StringBuilder sb = new StringBuilder( s.length() );
    for ( int i = 0; i < s.length(); i++ ) {
      char ch = s.charAt( i );
      if ( Character.isWhitespace( ch ) || Character.isSpaceChar( ch ) || ch == '\'' || ch == (char) 0x2019
        || MONEDAS.indexOf( ch ) >= 0 ) {
        continue;
      }
      sb.append( ch == (char) 0x2212 ? '-' : ch );
    }
    return sb.toString();
  }

  /** La mascara sin los literales entre comillas simples. */
  private static String sinLiterales( String mascara ) {
    return mascara == null ? "" : mascara.replaceAll( "'[^']*'", "" );
  }

  private static int cuenta( String s, char c ) {
    int n = 0;
    for ( int i = 0; i < s.length(); i++ ) {
      if ( s.charAt( i ) == c ) {
        n++;
      }
    }
    return n;
  }

  private static BigDecimal decimal( String s ) {
    try {
      return new BigDecimal( s );
    } catch ( NumberFormatException e ) {
      return null;
    }
  }

  private static BigDecimal decimal( double d ) {
    return Double.isNaN( d ) || Double.isInfinite( d ) || d <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf( d );
  }

  /** La fecha en ms (UTC, no importa la zona: se comparan campos) con la primera mascara que lee todo el texto. */
  private Long fecha( String s, List<String> lista, String[] patron ) {
    for ( String m : lista ) {
      SimpleDateFormat f = formato( m );
      if ( f == null ) {
        continue;
      }
      ParsePosition pp = new ParsePosition( 0 );
      Date d = f.parse( s, pp );
      if ( d != null && pp.getIndex() == s.length() && pp.getErrorIndex() < 0 ) {
        patron[ 0 ] = m;
        return d.getTime();
      }
    }
    return null;
  }

  private SimpleDateFormat formato( String mascara ) {
    if ( mascaras.containsKey( mascara ) ) {
      return mascaras.get( mascara );
    }
    SimpleDateFormat f;
    try {
      f = new SimpleDateFormat( mascara, loc );
      f.setLenient( false );
      f.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
    } catch ( IllegalArgumentException e ) {
      f = null; // mascara invalida en el pack
    }
    mascaras.put( mascara, f );
    return f;
  }

  static Boolean booleano( String s ) {
    switch ( s.trim().toUpperCase( Locale.ROOT ).replace( (char) 0xCD, 'I' ) ) { // SI con tilde
      case "Y":
      case "YES":
      case "TRUE":
      case "T":
      case "S":
      case "SI":
      case "V":
      case "VERDADERO":
      case "1":
        return Boolean.TRUE;
      case "N":
      case "NO":
      case "FALSE":
      case "F":
      case "FALSO":
      case "0":
        return Boolean.FALSE;
      default:
        return null;
    }
  }

  // ------------------------------------------------------------------ ejemplos y utilidades

  private void agregar( List<Comparacion.Ejemplo> l, Comparacion.Ejemplo e ) {
    if ( l.size() < maxEjemplos ) {
      l.add( e );
    }
  }

  private static Comparacion.Ejemplo conClave( String clave ) {
    Comparacion.Ejemplo e = new Comparacion.Ejemplo();
    e.clave = clave;
    return e;
  }

  private static Comparacion.Ejemplo conFila( String clave, CorrectorTabla t, int fila ) {
    Comparacion.Ejemplo e = conClave( clave );
    e.fila = new LinkedHashMap<>();
    for ( int k = 0; k < t.columnas.size(); k++ ) {
      String v = t.celda( fila, k );
      e.fila.put( t.columnas.get( k ), v == null ? "" : v );
    }
    return e;
  }

  /** Los valores de la clave como se leyeron (con trim), unidos con " | ". real: la tabla es la del alumno. */
  private static String mostrarClave( CorrectorTabla t, boolean real, int fila, List<Col> cols, int[] clave ) {
    StringBuilder sb = new StringBuilder();
    for ( int q = 0; q < clave.length; q++ ) {
      Col col = cols.get( clave[ q ] );
      String v = t.celda( fila, real ? col.ir : col.ie );
      if ( q > 0 ) {
        sb.append( " | " );
      }
      sb.append( v == null ? "" : v.trim() );
    }
    return sb.toString();
  }

  private static String crudo( Celda c ) {
    return c.crudo == null ? "" : c.crudo;
  }

  private static String unir( Collection<String> l ) {
    StringBuilder sb = new StringBuilder();
    for ( String s : l ) {
      if ( sb.length() > 0 ) {
        sb.append( ", " );
      }
      sb.append( s );
    }
    return sb.toString();
  }

  /**
   * Las esperadas que estan en las reales pero fuera de orden: las que no entran en la subsecuencia comun mas larga
   * (cada esperada toma la primera real libre con el mismo nombre, sin mayusculas). CorrectorNota la usa para
   * mantener la penalizacion por orden con {@code extra_columns=penalize}.
   */
  static List<String> fueraDeOrden( List<String> esperadas, List<String> reales ) {
    List<String> fuera = new ArrayList<>();
    if ( esperadas == null || reales == null ) {
      return fuera;
    }
    int ne = esperadas.size();
    int nr = reales.size();
    boolean[] usada = new boolean[ nr ];
    int[] seq = new int[ ne ];
    int[] cual = new int[ ne ];
    int comunes = 0;
    for ( int i = 0; i < ne; i++ ) {
      String e = esperadas.get( i ) == null ? "" : esperadas.get( i ).trim();
      for ( int j = 0; j < nr; j++ ) {
        if ( !usada[ j ] && reales.get( j ) != null && reales.get( j ).equalsIgnoreCase( e ) ) {
          usada[ j ] = true;
          cual[ comunes ] = i;
          seq[ comunes++ ] = j;
          break;
        }
      }
    }
    if ( comunes == 0 ) {
      return fuera;
    }
    boolean[] enOrden = subsecuenciaCreciente( Arrays.copyOf( seq, comunes ) );
    for ( int k = 0; k < comunes; k++ ) {
      if ( !enOrden[ k ] ) {
        fuera.add( esperadas.get( cual[ k ] ) );
      }
    }
    return fuera;
  }

  /** Marca los elementos de la subsecuencia estrictamente creciente mas larga (valores distintos). */
  static boolean[] subsecuenciaCreciente( int[] seq ) {
    int n = seq.length;
    boolean[] en = new boolean[ n ];
    if ( n == 0 ) {
      return en;
    }
    int[] colas = new int[ n ]; // indice del ultimo elemento de la subsecuencia de largo k+1
    int[] previo = new int[ n ];
    int largo = 0;
    for ( int i = 0; i < n; i++ ) {
      int lo = 0;
      int hi = largo;
      while ( lo < hi ) {
        int mid = ( lo + hi ) >>> 1;
        if ( seq[ colas[ mid ] ] < seq[ i ] ) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      previo[ i ] = lo > 0 ? colas[ lo - 1 ] : -1;
      colas[ lo ] = i;
      if ( lo == largo ) {
        largo++;
      }
    }
    for ( int i = colas[ largo - 1 ]; i >= 0; i = previo[ i ] ) {
      en[ i ] = true;
    }
    return en;
  }
}
