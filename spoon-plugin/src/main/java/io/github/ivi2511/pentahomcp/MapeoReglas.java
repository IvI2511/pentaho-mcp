package io.github.ivi2511.pentahomcp;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.row.ValueMetaInterface;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Lee la regla, el valor por defecto, el tipo y la validación de una fila de la hoja de mapeo (en inglés, español o
 * portugués) y propone los steps que la implementan, para mapping_import ({@link MapeoImportar}). Es conservador: solo
 * propone lo que entiende entero. Una regla con palabras que no reconoce (o que nombra campos que no llegan) queda como
 * marcador del boceto con el porqué; una regla en lenguaje natural nunca se adivina.
 * <ul>
 * <li>pares de valores ({@code M → Masculino, F → Femenino; si no, ?}) → Value mapper;</li>
 * <li>trim, mayúsculas, minúsculas, inicial mayúscula, solo dígitos → String operations;</li>
 * <li>reemplazar «x» por «y» / quitar «x» → Replace in string;</li>
 * <li>{@code a * b}, {@code a + b - c}, redondeado a n decimales, año / mes / día / día de la semana / trimestre de una
 * fecha → Calculator;</li>
 * <li>convertir a entero / número / fecha / texto, formato → Select values (cambio de tipo y formato);</li>
 * <li>buscar en una tabla por una clave → Stream lookup (si un step suelto de la transformación lee esa tabla) o
 * Database lookup (con la conexión del destino);</li>
 * <li>origen y valor por defecto → If field value is null; sin origen → Add constants;</li>
 * <li>la validación (no nulo, mayor que, entre, uno de...) → Filter rows con las filas que no cumplen a rechazos.</li>
 * </ul>
 */
final class MapeoReglas {

  // tipos de propuesta (los ve la IA en 'proposal' y 'steps')
  static final String COPIA = "rename";
  static final String CONSTANTE = "constant";
  static final String CALCULO = "calculator";
  static final String CADENAS = "string_operations";
  static final String REEMPLAZO = "replace_string";
  static final String MAPEO = "value_mapper";
  static final String TIPO = "select_values";
  static final String BUSCA_FLUJO = "stream_lookup";
  static final String BUSCA_BD = "database_lookup";
  static final String SI_NULO = "if_null";
  static final String FILTRO = "filter_rows";
  static final String MARCADOR = "placeholder";

  /** El orden de los steps en la cadena que se inserta delante del destino. */
  static final List<String> ORDEN = Arrays.asList( BUSCA_FLUJO, BUSCA_BD, COPIA, CALCULO, CADENAS, REEMPLAZO, MAPEO,
    TIPO, CONSTANTE, SI_NULO, MARCADOR, FILTRO );

  /** Una pieza de la propuesta de una fila. */
  static final class Accion {
    final String tipo;
    String entrada;
    String salida;
    /** CADENAS: none|left|right|both, none|upper|lower, init cap, digits. */
    String trim = "none";
    String mayus = "none";
    boolean inicial;
    String digitos = "none";
    /** REEMPLAZO. */
    String busca;
    String por;
    boolean regex;
    /** MAPEO. */
    final List<String[]> pares = new ArrayList<String[]>();
    String resto;
    /** CALCULO: {campo, calc_type, a, b, tipo, quitar(Y/N)}. */
    final List<String[]> funciones = new ArrayList<String[]>();
    /** TIPO. */
    String tipoKettle;
    String mascara;
    String decimal;
    /** CONSTANTE, SI_NULO. */
    String valor;
    /** BUSCA_*: step de donde se busca o conexión, tabla, clave del flujo, clave de la tabla, campo de la tabla. */
    String desde;
    String conexion;
    String tabla;
    String claveFlujo;
    String claveTabla;
    String campoTabla;
    /** FILTRO: {op, v1, v2...}: notnull, gt, ge, lt, le, eq, ne, between, in. */
    final List<String[]> validaciones = new ArrayList<String[]>();
    String textoValidacion;
    /** El tipo del campo que deja (para los cálculos y las constantes). */
    String tipoSale;

    Accion( String tipo ) {
      this.tipo = tipo;
    }
  }

  /** La propuesta de una fila: acciones, lo que no se entendió y el texto para la IA. */
  static final class Plan {
    final List<Accion> acciones = new ArrayList<Accion>();
    final List<String> dudas = new ArrayList<String>();
    final List<String> detalles = new ArrayList<String>();

    /** La propuesta principal (la primera acción que no es filtro ni si-nulo), o MARCADOR. */
    String principal() {
      if ( acciones.isEmpty() ) {
        return MARCADOR;
      }
      for ( Accion a : acciones ) {
        if ( !FILTRO.equals( a.tipo ) && !SI_NULO.equals( a.tipo ) && !TIPO.equals( a.tipo ) ) {
          return a.tipo;
        }
      }
      return acciones.get( 0 ).tipo;
    }

    boolean marcador() {
      return acciones.isEmpty() || !dudas.isEmpty();
    }
  }

  /** Lo que se sabe del destino al planear una fila. */
  static final class Ctx {
    /** Los campos disponibles delante del destino (los que llegan y los que crean las filas anteriores). */
    final Map<String, ValueMetaInterface> disponibles = new LinkedHashMap<String, ValueMetaInterface>();
    /** tabla (minúsculas) → step suelto que la lee (para Stream lookup). */
    final Map<String, String> pasosDeTabla = new LinkedHashMap<String, String>();
    /** tabla (minúsculas) → {campo → tipo} de un step suelto que la lee. */
    final Map<String, Map<String, ValueMetaInterface>> camposDeTabla = new LinkedHashMap<String, Map<String,
      ValueMetaInterface>>();
    /** La conexión para Database lookup (la del step destino, o la única de la transformación), o null. */
    String conexion;
    /** Por qué no se pudieron calcular los campos que llegan al destino (null si se pudieron). */
    String sinCampos;
    Rotulos r;

    ValueMetaInterface campo( String nombre ) {
      if ( nombre == null ) {
        return null;
      }
      ValueMetaInterface v = disponibles.get( nombre.toLowerCase( Locale.ROOT ) );
      return v;
    }

    String nombre( String n ) {
      ValueMetaInterface v = campo( n );
      return v == null ? null : v.getName();
    }
  }

  private MapeoReglas() {
  }

  // ================================================================ planear una fila

  /**
   * La propuesta para una fila que falta. destino = el campo destino; origen = {recurso, campo} de la columna origen
   * (la primera si hay varias).
   */
  static Plan planear( MapeoImportar.FilaHoja h, String destino, String[] origen, Ctx c ) {
    Plan p = new Plan();
    Rotulos r = c.r;
    String regla = h.g( "rule" );
    String defecto = h.g( "default" );
    String fuente = c.nombre( origen[ 1 ] );
    if ( c.sinCampos != null && !( regla.isEmpty() && origen[ 1 ].isEmpty() && !defecto.isEmpty() ) ) {
      p.dudas.add( r.f( "map.duda.sin_campos", c.sinCampos ) ); // sin los campos no se puede comprobar nada
      return p;
    }
    if ( regla.isEmpty() ) {
      if ( fuente != null ) {
        if ( !fuente.equalsIgnoreCase( destino ) ) {
          Accion a = new Accion( COPIA );
          a.entrada = fuente;
          a.salida = destino;
          a.tipoSale = c.campo( fuente ).getTypeDesc();
          p.acciones.add( a );
          p.detalles.add( r.f( "map.imp.copia", fuente, destino ) );
        }
      } else if ( origen[ 1 ].isEmpty() && !defecto.isEmpty() ) {
        Accion a = new Accion( CONSTANTE );
        a.salida = destino;
        a.valor = defecto;
        a.tipoSale = MapeoImportar.tipoKettle( h.g( "type" ) );
        p.acciones.add( a );
        p.detalles.add( r.f( "map.imp.constante", destino, defecto ) );
        defecto = "";
      } else if ( !origen[ 1 ].isEmpty() ) {
        // tabla.campo que no llega y sin regla: buscarlo pide saber la clave, que no se adivina
        p.dudas.add( origen[ 0 ].isEmpty() ? r.f( "map.duda.no_llega", origen[ 1 ] ) : r.f( "map.duda.sin_clave",
          destino, origen[ 0 ] ) );
      } else {
        p.dudas.add( r.f( "map.imp.marcador.sin_regla", destino ) );
      }
    } else {
      Lector l = new Lector( regla, destino, fuente, origen, c, p );
      l.leer();
    }
    if ( p.marcador() ) {
      return p; // lo que falta lo hace una persona: ni defecto, ni tipo, ni validación a medias
    }
    // valor por defecto con origen: If field value is null (si no lo puso ya la búsqueda)
    if ( !defecto.isEmpty() && !tiene( p, BUSCA_BD ) && !tiene( p, BUSCA_FLUJO ) && !tiene( p, CONSTANTE ) ) {
      Accion a = new Accion( SI_NULO );
      a.salida = destino;
      a.valor = defecto;
      p.acciones.add( a );
      p.detalles.add( r.f( "map.imp.si_nulo", destino, defecto ) );
    }
    tipo( h, destino, c, p );
    validacion( h.g( "validation" ), destino, c, p );
    if ( p.marcador() ) {
      p.acciones.clear();
    }
    return p;
  }

  private static boolean tiene( Plan p, String tipo ) {
    for ( Accion a : p.acciones ) {
      if ( tipo.equals( a.tipo ) ) {
        return true;
      }
    }
    return false;
  }

  /** El tipo que deja el plan para el destino (el de la última acción que lo produce), o null. */
  static String tipoProducido( Plan p, String destino, Ctx c ) {
    String t = null;
    for ( Accion a : p.acciones ) {
      if ( a.salida != null && a.salida.equalsIgnoreCase( destino ) && a.tipoSale != null ) {
        t = a.tipoSale;
      } else if ( TIPO.equals( a.tipo ) && a.salida != null && a.salida.equalsIgnoreCase( destino ) ) {
        t = a.tipoKettle;
      }
    }
    return t;
  }

  /** El tipo de la hoja distinto del que sale: Select values si la conversión es segura; si no, una duda. */
  private static void tipo( MapeoImportar.FilaHoja h, String destino, Ctx c, Plan p ) {
    String hoja = h.g( "type" );
    String sale = tipoProducido( p, destino, c );
    if ( hoja.isEmpty() || sale == null || MapeoImportar.mismoTipo( hoja, sale ) || tiene( p, TIPO ) ) {
      return;
    }
    String quiere = MapeoImportar.tipoKettle( hoja );
    String regla = norm( h.g( "rule" ) );
    Accion a = new Accion( TIPO );
    a.salida = destino;
    a.tipoKettle = quiere;
    a.mascara = mascara( h.g( "rule" ) );
    a.decimal = decimal( regla );
    if ( "String".equals( sale ) && "Date".equals( quiere ) && a.mascara == null ) {
      p.dudas.add( c.r.f( "map.duda.formato_fecha", destino ) );
      return;
    }
    if ( "String".equals( sale ) && ( "Number".equals( quiere ) || "BigNumber".equals( quiere ) ) && a.decimal == null ) {
      p.dudas.add( c.r.f( "map.duda.decimal", destino ) );
      return;
    }
    if ( "String".equals( quiere ) && "Number".equals( sale ) && a.mascara == null ) {
      a.mascara = null; // a texto con el formato por defecto
    }
    p.acciones.add( a );
    p.detalles.add( c.r.f( "map.imp.convierte", destino, quiere + ( a.mascara == null ? "" : " (" + a.mascara + ")" ) ) );
  }

  // ================================================================ la regla

  /** Lee la regla: cada frase reconocida se borra del texto; lo que queda tiene que ser relleno. */
  private static final class Lector {
    final String original;
    final String destino;
    String fuente;
    final String[] origen;
    final Ctx c;
    final Plan p;
    final Rotulos r;
    /** El texto normalizado (sin acentos, minúsculas) donde se van borrando las frases reconocidas. */
    final StringBuilder resto;

    Lector( String regla, String destino, String fuente, String[] origen, Ctx c, Plan p ) {
      this.original = regla.trim();
      this.destino = destino;
      this.fuente = fuente;
      this.origen = origen;
      this.c = c;
      this.p = p;
      this.r = c.r;
      this.resto = new StringBuilder( norm( original ) );
    }

    void leer() {
      if ( pares() || aritmetica() || fecha() || busqueda() ) {
        return;
      }
      clausulas();
    }

    // ---------------------------------------------------------------- campo de entrada

    /** El campo que la regla transforma: el de la columna origen, o el único campo que nombra y que llega. */
    String entrada() {
      if ( fuente != null ) {
        return fuente;
      }
      List<String> l = camposNombrados( original );
      if ( l.size() == 1 ) {
        return l.get( 0 );
      }
      return null;
    }

    List<String> camposNombrados( String texto ) {
      List<String> l = new ArrayList<String>();
      Matcher m = Pattern.compile( "[A-Za-z_][A-Za-z0-9_]*" ).matcher( texto );
      while ( m.find() ) {
        String n = c.nombre( m.group() );
        if ( n != null && !l.contains( n ) && !( n.equalsIgnoreCase( destino ) && c.campo( destino ) == null ) ) {
          l.add( n );
        }
      }
      return l;
    }

    // ---------------------------------------------------------------- pares de valores

    /** «A → 1, B → 2; si no, 0» (o =, =>, :) → Value mapper. Todos los trozos tienen que ser pares o el resto. */
    boolean pares() {
      String t = original.replaceAll( "(?i)^\\s*(map(ping)?|mapear|mapea|mapeia|mapeo|traducir|traduce|traduzir|"
        + "translate|c[oó]digos?|codes?)\\s*[:]?\\s*", "" );
      String restoValor = null;
      Matcher fin = Pattern.compile( "(?i)[;,\\n]\\s*(otherwise|else|default|any other|por defecto|si no|sino|en otro "
        + "caso|el resto|sen[aã]o|caso contr[aá]rio|o resto)\\s*[:,]?\\s*(?:→|->|=>|=)?\\s*['\"«“]?([^;,\\n]*?)['\"»”]?"
        + "\\.?\\s*$" ).matcher( t );
      if ( fin.find() ) {
        restoValor = fin.group( 2 ).trim();
        t = t.substring( 0, fin.start() );
      }
      String[] trozos = t.split( "\\s*[;,\\n]\\s*" );
      List<String[]> pares = new ArrayList<String[]>();
      Pattern par = Pattern.compile( "^['\"«“]?([^'\"»”=:→>]{1,40}?)['\"»”]?\\s*(?:->|→|=>|=|:)\\s*['\"«“]?([^'\"»”]{0,40}?)"
        + "['\"»”]?\\.?$" );
      Pattern otro = Pattern.compile( "(?i)^(otherwise|else|default|any other|por defecto|si no|sino|en otro caso|el resto|"
        + "resto|sen[aã]o|caso contr[aá]rio|o resto|outros?)\\s*[:,]?\\s*(?:→|->|=>|=)?\\s*['\"«“]?(.*?)['\"»”]?\\.?$" );
      for ( String x : trozos ) {
        if ( x.trim().isEmpty() ) {
          continue;
        }
        Matcher mo = otro.matcher( x.trim() );
        if ( mo.matches() ) {
          restoValor = mo.group( 2 ).trim();
          continue;
        }
        Matcher mp = par.matcher( x.trim() );
        if ( !mp.matches() ) {
          return false;
        }
        String a = mp.group( 1 ).trim();
        String b = mp.group( 2 ).trim();
        if ( a.split( "\\s+" ).length > 3 || b.split( "\\s+" ).length > 4 || a.isEmpty() ) {
          return false;
        }
        if ( b.matches( ".*[*/+×÷].*" ) || c.campo( a ) != null && !camposNombrados( b ).isEmpty()
          || a.equalsIgnoreCase( destino ) ) {
          return false; // «importe = precio * cantidad» o «a = b» no son pares de valores
        }
        pares.add( new String[] { a, b } );
      }
      if ( pares.size() < 2 && !( pares.size() == 1 && restoValor != null ) ) {
        return false;
      }
      String e = entrada();
      if ( e == null ) {
        p.dudas.add( r.f( "map.duda.sin_entrada", destino ) );
        return true;
      }
      Accion a = new Accion( MAPEO );
      a.entrada = e;
      a.salida = destino;
      a.pares.addAll( pares );
      a.resto = restoValor;
      a.tipoSale = "String";
      p.acciones.add( a );
      p.detalles.add( r.f( "map.imp.mapeo", destino, pares.size(), e ) );
      return true;
    }

    // ---------------------------------------------------------------- aritmética

    private final Pattern redondeo = Pattern.compile( "(?i)[,;]?\\s*(?:y\\s+|and\\s+|e\\s+)?(?:round(?:ed)?(?:\\s+to)?|"
      + "redondead[oa]s?\\s+a|redondear\\s+a|arredondad[oa]s?\\s+(?:a|para|com)|arredondar\\s+(?:a|para))\\s+(\\d+)\\s*"
      + "(?:decimal places|decimales|decimais|decimals?|places|digits|casas(?:\\s+decimais)?|d[ií]gitos)?\\b"
      + "(?:\\s*,?\\s*(?:con|with|com)\\s+(?:la\\s+|a\\s+)?mitad\\s+hacia\\s+arriba|\\s*,?\\s*half up)?\\.?" );

    /** «a * b», «precio_unitario × cantidad, redondeado a 2 decimales», «importe = a + b - c» → Calculator. */
    boolean aritmetica() {
      String t = original;
      Integer decimales = null;
      Matcher mr = redondeo.matcher( t );
      if ( mr.find() ) {
        decimales = Integer.valueOf( mr.group( 1 ) );
        t = t.substring( 0, mr.start() ) + t.substring( mr.end() );
      }
      Matcher fr = Pattern.compile( "(?i)^\\s*(?:round|redondear|arredondar)\\s*\\((.+),\\s*(\\d+)\\s*\\)\\s*$" ).matcher(
        t );
      if ( fr.matches() ) {
        t = fr.group( 1 );
        decimales = Integer.valueOf( fr.group( 2 ) );
      }
      t = t.trim().replaceAll( "\\.$", "" );
      Matcher asig = Pattern.compile( "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$" ).matcher( t );
      if ( asig.matches() && asig.group( 1 ).equalsIgnoreCase( destino ) ) {
        t = asig.group( 2 );
      }
      String operando = "([A-Za-z_][A-Za-z0-9_]*|[-+]?\\d+(?:[.,]\\d+)?)";
      String op = "\\s*([-+*/×x÷])\\s*";
      Matcher m = Pattern.compile( "^\\s*" + operando + op + operando + "(?:" + op + operando + ")?\\s*$" ).matcher( t );
      if ( !m.matches() ) {
        return false;
      }
      List<String> ops = new ArrayList<String>();
      List<String> vals = new ArrayList<String>();
      vals.add( m.group( 1 ) );
      ops.add( operador( m.group( 2 ) ) );
      vals.add( m.group( 3 ) );
      if ( m.group( 4 ) != null ) {
        ops.add( operador( m.group( 4 ) ) );
        vals.add( m.group( 5 ) );
      }
      if ( ops.contains( null ) ) {
        return false;
      }
      // los campos tienen que llegar; los números son constantes
      List<String> args = new ArrayList<String>();
      Accion a = new Accion( CALCULO );
      a.salida = destino;
      int k = 0;
      boolean entero = true;
      for ( String v : vals ) {
        if ( v.matches( "[-+]?\\d+(?:[.,]\\d+)?" ) ) {
          String nc = "__" + Plano.slug( destino ) + "_c" + ( k++ );
          String num = v.replace( ',', '.' );
          entero &= !num.contains( "." );
          a.funciones.add( new String[] { nc, "CONSTANT", num, null, num.contains( "." ) ? "Number" : "Integer", "Y" } );
          args.add( nc );
        } else {
          ValueMetaInterface vm = c.campo( v );
          if ( vm == null ) {
            p.dudas.add( r.f( "map.duda.no_llega", v ) );
            return true;
          }
          entero &= vm.getType() == ValueMetaInterface.TYPE_INTEGER;
          if ( !vm.isNumeric() ) {
            p.dudas.add( r.f( "map.duda.no_numero", vm.getName(), vm.getTypeDesc() ) );
            return true;
          }
          args.add( vm.getName() );
        }
      }
      if ( c.campo( destino ) != null ) {
        p.dudas.add( r.f( "map.duda.sobrescribe", destino ) );
        return true;
      }
      // precedencia: a + b * c = a + (b * c)
      String tipoRes = entero && !ops.contains( "DIVIDE" ) && decimales == null ? "Integer" : "Number";
      String izq = args.get( 0 );
      if ( ops.size() == 2 && precede( ops.get( 1 ), ops.get( 0 ) ) ) {
        String tmp = "__" + Plano.slug( destino ) + "_t";
        a.funciones.add( new String[] { tmp, ops.get( 1 ), args.get( 1 ), args.get( 2 ), tipoRes, "Y" } );
        a.funciones.add( new String[] { ultimo( decimales, destino ), ops.get( 0 ), izq, tmp, tipoRes, "N" } );
      } else if ( ops.size() == 2 ) {
        String tmp = "__" + Plano.slug( destino ) + "_t";
        a.funciones.add( new String[] { tmp, ops.get( 0 ), izq, args.get( 1 ), tipoRes, "Y" } );
        a.funciones.add( new String[] { ultimo( decimales, destino ), ops.get( 1 ), tmp, args.get( 2 ), tipoRes, "N" } );
      } else {
        a.funciones.add( new String[] { ultimo( decimales, destino ), ops.get( 0 ), izq, args.get( 1 ), tipoRes, "N" } );
      }
      if ( decimales != null ) {
        String nd = "__" + Plano.slug( destino ) + "_d";
        String[] fin = a.funciones.get( a.funciones.size() - 1 );
        fin[ 5 ] = "Y";
        a.funciones.add( new String[] { nd, "CONSTANT", decimales.toString(), null, "Integer", "Y" } );
        a.funciones.add( new String[] { destino, "ROUND_2", fin[ 0 ], nd, "Number", "N" } );
      }
      a.tipoSale = decimales != null ? "Number" : tipoRes;
      p.acciones.add( a );
      p.detalles.add( r.f( "map.imp.calculo", destino, original ) );
      return true;
    }

    private String ultimo( Integer decimales, String destino ) {
      return decimales == null ? destino : "__" + Plano.slug( destino ) + "_sin_redondeo";
    }

    // ---------------------------------------------------------------- partes de una fecha

    boolean fecha() {
      String t = norm( original ).replaceAll( "\\.$", "" ).trim();
      String[][] partes = { { "day of (?:the )?week|weekday|dia de la semana|dia da semana", "DAY_OF_WEEK" },
        { "day of (?:the )?year|dia del ano|dia do ano", "DAY_OF_YEAR" },
        { "iso week(?: of (?:the )?year)?|semana iso", "WEEK_OF_YEAR_ISO8601" },
        { "week(?: of (?:the )?year)?|semana(?: del ano| do ano)?", "WEEK_OF_YEAR" },
        { "quarter|trimestre", "QUARTER_OF_DATE" }, { "year|ano|anio", "YEAR_OF_DATE" },
        { "month|mes", "MONTH_OF_DATE" }, { "day(?: of (?:the )?month)?|dia(?: del mes| do mes)?", "DAY_OF_MONTH" } };
      for ( String[] x : partes ) {
        Matcher m = Pattern.compile( "^(?:(?:el|la|o|a|the)\\s+)?(?:" + x[ 0 ] + ")\\s*(?:\\(|of\\s+|de\\s+|del\\s+|"
          + "do\\s+|da\\s+)(?:(?:la|el|the|a|o)\\s+)?([a-z_][a-z0-9_]*)\\s*\\)?$" ).matcher( t );
        if ( !m.matches() ) {
          continue;
        }
        ValueMetaInterface vm = c.campo( m.group( 1 ) );
        if ( vm == null ) {
          p.dudas.add( r.f( "map.duda.no_llega", m.group( 1 ) ) );
          return true;
        }
        if ( vm.getType() != ValueMetaInterface.TYPE_DATE && vm.getType() != ValueMetaInterface.TYPE_TIMESTAMP ) {
          p.dudas.add( r.f( "map.duda.no_fecha", vm.getName(), vm.getTypeDesc() ) );
          return true;
        }
        if ( c.campo( destino ) != null ) {
          p.dudas.add( r.f( "map.duda.sobrescribe", destino ) );
          return true;
        }
        Accion a = new Accion( CALCULO );
        a.salida = destino;
        a.funciones.add( new String[] { destino, x[ 1 ], vm.getName(), null, "Integer", "N" } );
        a.tipoSale = "Integer";
        p.acciones.add( a );
        p.detalles.add( r.f( "map.imp.calculo", destino, x[ 1 ] + "(" + vm.getName() + ")" ) );
        return true;
      }
      return false;
    }

    // ---------------------------------------------------------------- búsqueda

    /** «buscar en dim_tienda por id_tienda», «lookup sk in dim_tienda by id_tienda = codigo» → Stream / DB lookup. */
    boolean busqueda() {
      String t = norm( original );
      if ( !t.matches( ".*\\b(look ?up|lookup|buscar|busca|busque|buscado|procurar|procura|join|cruzar|cruza)\\b.*" ) ) {
        return false;
      }
      Matcher mk = Pattern.compile( "\\b(?:by|on|using|por|usando|pelo|pela|mediante|with key|key|con clave|clave|"
        + "com chave|chave)\\s+(?:el\\s+|la\\s+|the\\s+|o\\s+|a\\s+)?(?:campo\\s+|field\\s+)?([a-z_][a-z0-9_]*)(?:\\s*=\\s*"
        + "([a-z_][a-z0-9_.]*))?" ).matcher( t );
      String tabla = origen[ 0 ].isEmpty() ? null : origen[ 0 ];
      // primero «en / in / from tabla»; «de» solo si parece un nombre de tabla («el nombre de la tienda» no lo es)
      for ( String prep : new String[] { "in|en|em|from|desde|na|no|on", "de|del|da|do|of" } ) {
        Matcher mt = Pattern.compile( "\\b(?:" + prep + ")\\s+(?:the\\s+|la\\s+|el\\s+|a\\s+|o\\s+)?(?:table\\s+|"
          + "tabla\\s+|tabela\\s+|dimension\\s+)?([a-z_][a-z0-9_.]*)" ).matcher( t );
        while ( tabla == null && mt.find() ) {
          String x = mt.group( 1 );
          boolean fuerte = prep.startsWith( "in|" );
          if ( c.campo( x ) == null && !esRelleno( x ) && ( fuerte || x.contains( "_" ) || x.contains( "." )
            || c.pasosDeTabla.containsKey( x ) ) ) {
            tabla = original.substring( mt.start( 1 ), mt.end( 1 ) );
          }
        }
      }
      if ( tabla == null ) {
        p.dudas.add( r.f( "map.duda.sin_tabla", destino ) );
        return true;
      }
      String clave = null;
      String claveTabla = null;
      while ( mk.find() ) {
        String k = c.nombre( mk.group( 1 ) );
        if ( k != null ) {
          clave = k;
          claveTabla = mk.group( 2 ) == null ? k : mk.group( 2 ).substring( mk.group( 2 ).lastIndexOf( '.' ) + 1 );
          break;
        }
      }
      if ( clave == null ) {
        p.dudas.add( r.f( "map.duda.sin_clave", destino, tabla ) );
        return true;
      }
      String campoTabla = origen[ 1 ].isEmpty() ? destino : origen[ 1 ];
      return lookup( tabla, clave, claveTabla, campoTabla, destino, c, p );
    }

    // ---------------------------------------------------------------- frases sueltas

    /** trim / mayúsculas / reemplazos / solo A, B o C / convertir / copiar, y nada más. */
    void clausulas() {
      List<Accion> nuevas = new ArrayList<Accion>();
      Accion cad = new Accion( CADENAS );
      if ( quitar( "\\b(capitali[sz]ed?|initcap|title case|inicial(?:es)? (?:en )?mayusculas?|inicial(?:es)? maiusculas?|"
        + "primera letra (?:en )?mayusculas?|primeira letra maiuscula|con inicial mayuscula|com inicial maiuscula)\\b" ) ) {
        cad.inicial = true;
      }
      if ( quitar( "\\b(trim(?:med)?|strip(?:ped)?|sin espacios(?: sobrantes)?|quit(?:ar|a|ando) (?:los )?espacios(?: "
        + "sobrantes)?|elimin(?:ar|a) (?:los )?espacios(?: sobrantes)?|espacios sobrantes|recort(?:ar|a|ado)(?: los "
        + "espacios)?|sem espacos(?: extras)?|remov(?:er|e|ido) (?:os )?espacos|tir(?:ar|a) (?:os )?espacos|aparar|remove "
        + "(?:the )?(?:extra |leading and trailing )?spaces|no (?:extra )?spaces|without (?:extra )?spaces)\\b" ) ) {
        cad.trim = "both";
      }
      if ( quitar( "\\b(upper ?case(?:d)?|upper|en mayusculas|mayusculas?|em maiusculas|maiusculas?|all caps|caps)\\b" ) ) {
        cad.mayus = "upper";
      }
      if ( quitar( "\\b(lower ?case(?:d)?|lower|en minusculas|minusculas?|em minusculas)\\b" ) ) {
        cad.mayus = "upper".equals( cad.mayus ) ? "none" : "lower";
      }
      if ( quitar( "\\b(only (?:the )?digits|digits only|solo (?:los )?digitos|solamente (?:los )?digitos|apenas (?:os )?"
        + "digitos|so (?:os )?digitos)\\b" ) ) {
        cad.digitos = "digits_only";
      }
      boolean hayCad = !"none".equals( cad.trim ) || !"none".equals( cad.mayus ) || cad.inicial || !"none".equals(
        cad.digitos );
      List<Accion> reemplazos = reemplazos();
      Accion lista = enLista();
      Accion conv = conversion();
      boolean copia = quitar( "\\b(copy|copiar|copia|copied|copiado|same(?: value)?|igual|tal cual|as[- ]is|directo|"
        + "directa|direct|direto|sin cambios|no change|unchanged|sem alteracoes|sem alteracao|mesmo valor|pass(?:es)? "
        + "through)\\b" );
      String sobra = sobrante();
      if ( !sobra.isEmpty() ) {
        p.dudas.add( r.f( "map.duda.no_entiendo", sobra ) );
        return;
      }
      if ( !hayCad && reemplazos.isEmpty() && lista == null && conv == null && !copia ) {
        p.dudas.add( r.f( "map.duda.no_entiendo", original ) );
        return;
      }
      String e = entrada();
      if ( e == null ) {
        p.dudas.add( origen[ 1 ].isEmpty() ? r.f( "map.duda.sin_entrada", destino ) : r.f( "map.duda.no_llega",
          origen[ 1 ] ) );
        return;
      }
      String actual = e;
      ValueMetaInterface vm = c.campo( e );
      if ( hayCad ) {
        if ( vm != null && !vm.isString() ) {
          p.dudas.add( r.f( "map.duda.no_texto", e, vm.getTypeDesc() ) );
          return;
        }
        cad.entrada = actual;
        cad.salida = destino;
        cad.tipoSale = "String";
        nuevas.add( cad );
        actual = destino;
        p.detalles.add( r.f( "map.imp.cadenas", destino, descripcionCadenas( cad, r ) ) );
      }
      for ( Accion x : reemplazos ) {
        if ( vm != null && !vm.isString() ) {
          p.dudas.add( r.f( "map.duda.no_texto", e, vm.getTypeDesc() ) );
          return;
        }
        x.entrada = actual;
        x.salida = destino;
        x.tipoSale = "String";
        nuevas.add( x );
        actual = destino;
        p.detalles.add( r.f( "map.imp.reemplazo", destino, x.busca, x.por ) );
      }
      if ( !actual.equalsIgnoreCase( destino ) ) {
        Accion cp = new Accion( COPIA );
        cp.entrada = actual;
        cp.salida = destino;
        cp.tipoSale = vm == null ? "String" : vm.getTypeDesc();
        nuevas.add( 0, cp );
        p.detalles.add( r.f( "map.imp.copia", actual, destino ) );
      }
      if ( conv != null ) {
        conv.salida = destino;
        nuevas.add( conv );
        p.detalles.add( r.f( "map.imp.convierte", destino, conv.tipoKettle + ( conv.mascara == null ? "" : " ("
          + conv.mascara + ")" ) ) );
      }
      if ( lista != null ) {
        lista.salida = destino;
        nuevas.add( lista );
        p.detalles.add( r.f( "map.imp.filtro", destino, lista.textoValidacion ) );
      }
      p.acciones.addAll( nuevas );
    }

    /** «reemplazar «,» por «.»», «replace '-' with ''», «quitar los guiones '-'» → Replace in string. */
    List<Accion> reemplazos() {
      List<Accion> l = new ArrayList<Accion>();
      String tok = "(\"[^\"]*\"|'[^']*'|«[^»]*»|“[^”]*”|comma|coma|virgula|dot|period|point|punto|ponto|space|espacio|"
        + "espaco|hyphen|guion|hifen|dash|slash|barra|nothing|nada|empty|vacio|vazio|[^\\w\\s\"'«»“”])";
      Pattern re = Pattern.compile( "\\b(?:replace|replaces|reemplaz(?:ar|a|ando)|remplaz(?:ar|a)|sustitu(?:ir|ye)|"
        + "substitu(?:ir|i|a)|cambi(?:ar|a)|troc(?:ar|a))\\s+(?:all\\s+|todos?\\s+|todas?\\s+)?(?:the\\s+|el\\s+|la\\s+|"
        + "los\\s+|las\\s+|o\\s+|a\\s+|os\\s+|as\\s+)?" + tok + "\\s+(?:with|by|for|por|con|para|pelo|pela)\\s+"
        + "(?:the\\s+|el\\s+|la\\s+|un\\s+|una\\s+|o\\s+|a\\s+|um\\s+|uma\\s+)?" + tok );
      Pattern quita = Pattern.compile( "\\b(?:remove|delete|quitar|quita|eliminar|elimina|remover|remove|tirar|tira)\\s+"
        + "(?:the\\s+|los\\s+|las\\s+|el\\s+|la\\s+|os\\s+|as\\s+|o\\s+|a\\s+)?(?:[a-z]+\\s+)?(\"[^\"]*\"|'[^']*'|«[^»]*»|"
        + "“[^”]*”)" );
      // se busca sobre el texto sin acentos pero con las comillas y los símbolos de la regla
      String t = norm( original );
      Matcher m = re.matcher( t );
      while ( m.find() ) {
        Accion a = new Accion( REEMPLAZO );
        a.busca = valorDe( original, m.start( 1 ), m.end( 1 ), m.group( 1 ) );
        a.por = valorDe( original, m.start( 2 ), m.end( 2 ), m.group( 2 ) );
        l.add( a );
        borrar( m.start(), m.end() );
      }
      m = quita.matcher( t );
      while ( m.find() ) {
        if ( estaBorrado( m.start() ) ) {
          continue;
        }
        Accion a = new Accion( REEMPLAZO );
        a.busca = valorDe( original, m.start( 1 ), m.end( 1 ), m.group( 1 ) );
        a.por = "";
        if ( a.busca.isEmpty() ) {
          continue;
        }
        l.add( a );
        borrar( m.start(), m.end() );
      }
      return l;
    }

    /** «solo TARJETA, EFECTIVO o BIZUM», «only A, B or C», «uno de (A, B)» → validación en lista. */
    Accion enLista() {
      Matcher m = Pattern.compile( "\\b(?:only|solo|solamente|unicamente|apenas|somente|one of|uno de|una de|um de|"
        + "uma de|must be one of|tiene que ser uno de|in)\\s*[:(]?\\s*((?:['\"«“]?[\\w.-]+['\"»”]?\\s*(?:,|\\bor\\b|\\bo\\b|"
        + "\\bou\\b|/)\\s*)+['\"«“]?[\\w.-]+['\"»”]?)\\s*\\)?" ).matcher( norm( original ) );
      if ( !m.find() ) {
        return null;
      }
      String crudo = original.substring( m.start( 1 ), m.end( 1 ) );
      List<String> vals = new ArrayList<String>();
      for ( String x : crudo.split( "\\s*(?:,|\\bor\\b|\\bo\\b|\\bou\\b|/)\\s*" ) ) {
        String v = x.replaceAll( "^['\"«“]|['\"»”]$", "" ).trim();
        if ( !v.isEmpty() ) {
          vals.add( v );
        }
      }
      if ( vals.size() < 2 ) {
        return null;
      }
      borrar( m.start(), m.end() );
      Accion a = new Accion( FILTRO );
      String[] v = new String[ vals.size() + 1 ];
      v[ 0 ] = "in";
      for ( int i = 0; i < vals.size(); i++ ) {
        v[ i + 1 ] = vals.get( i );
      }
      a.validaciones.add( v );
      a.textoValidacion = String.join( ", ", vals );
      return a;
    }

    /** «convertir a entero», «as date format dd/MM/yyyy», «a número con punto decimal» → Select values. */
    Accion conversion() {
      Matcher m = Pattern.compile( "\\b(?:convert(?:ed|ir|ido|er)?(?:lo|la)?|cast|as|to|como|a|para)\\s+(?:an?\\s+|"
        + "un\\s+|una\\s+|um\\s+|uma\\s+)?(integer|int|entero|inteiro|number|numero|decimal|float|date|fecha|string|"
        + "text|texto|boolean|booleano)\\b|\\b(?:convert\\w*|para)\\s+(?:uma\\s+)?(data)\\b" ).matcher( norm( original ) );
      String masc = mascara( original );
      if ( !m.find() ) {
        if ( masc != null ) {
          borrarMascara();
          Accion a = new Accion( TIPO );
          ValueMetaInterface vm = c.campo( entrada() );
          a.tipoKettle = vm != null && vm.getType() == ValueMetaInterface.TYPE_DATE ? "String" : "Date";
          a.mascara = masc;
          return a;
        }
        return null;
      }
      borrar( m.start(), m.end() );
      String palabra = m.group( 1 ) != null ? m.group( 1 ) : m.group( 2 );
      String tipo = MapeoImportar.tipoKettle( palabra );
      if ( palabra.matches( "number|numero|decimal|float" ) ) {
        tipo = "Number";
      } else if ( palabra.matches( "fecha|data|date" ) ) {
        tipo = "Date";
      } else if ( palabra.matches( "text|texto|string" ) ) {
        tipo = "String";
      }
      Accion a = new Accion( TIPO );
      a.tipoKettle = tipo;
      a.mascara = masc;
      if ( masc != null ) {
        borrarMascara();
      }
      a.decimal = decimal( norm( original ) );
      if ( a.decimal != null ) {
        quitar( "\\b(?:con |with |com )?(?:punto|coma|virgula|ponto) decimal|decimal (?:point|comma|dot)\\b" );
      }
      ValueMetaInterface vm = c.campo( entrada() );
      if ( vm != null && vm.isString() && "Date".equals( tipo ) && masc == null ) {
        p.dudas.add( r.f( "map.duda.formato_fecha", destino ) );
      } else if ( vm != null && vm.isString() && "Number".equals( tipo ) && a.decimal == null ) {
        p.dudas.add( r.f( "map.duda.decimal", destino ) );
      }
      a.tipoSale = tipo;
      return a;
    }

    private void borrarMascara() {
      quitar( "\\b(?:format|formato|mask|mascara|pattern|patron|padrao)\\s*:?\\s*['\"«“]?[ydmhsa/:. \\-]+['\"»”]?" );
    }

    // ---------------------------------------------------------------- el texto que queda

    private final Set<Integer> borrados = new HashSet<Integer>();

    boolean quitar( String regex ) {
      Matcher m = Pattern.compile( regex ).matcher( resto );
      boolean alguno = false;
      while ( m.find() ) {
        borrar( m.start(), m.end() );
        alguno = true;
      }
      return alguno;
    }

    void borrar( int a, int b ) {
      for ( int i = a; i < b && i < resto.length(); i++ ) {
        resto.setCharAt( i, ' ' );
        borrados.add( i );
      }
    }

    boolean estaBorrado( int i ) {
      return borrados.contains( i );
    }

    /** Las palabras que quedan sin reconocer (ni relleno, ni campos, ni la tabla, ni números). */
    String sobrante() {
      List<String> l = new ArrayList<String>();
      Matcher m = Pattern.compile( "[a-z0-9_]+" ).matcher( resto );
      while ( m.find() ) {
        String w = m.group();
        if ( esRelleno( w ) || w.matches( "\\d+" ) || c.campo( w ) != null || w.equalsIgnoreCase( destino )
          || w.equalsIgnoreCase( origen[ 1 ] ) || w.equalsIgnoreCase( origen[ 0 ] ) || w.matches( "r\\d+" ) ) {
          continue;
        }
        l.add( w );
      }
      return String.join( " ", l );
    }
  }

  // ================================================================ búsquedas

  /** La búsqueda de campoTabla en tabla por clave: Stream lookup si hay un step suelto que la lee; si no, DB lookup. */
  static boolean lookup( String tabla, String clave, String claveTabla, String campoTabla, String destino, Ctx c,
                         Plan p ) {
    Rotulos r = c.r;
    String t = tabla.toLowerCase( Locale.ROOT );
    String corta = t.substring( t.lastIndexOf( '.' ) + 1 );
    String paso = c.pasosDeTabla.get( t ) != null ? c.pasosDeTabla.get( t ) : c.pasosDeTabla.get( corta );
    Accion a;
    if ( paso != null ) {
      a = new Accion( BUSCA_FLUJO );
      a.desde = paso;
      Map<String, ValueMetaInterface> cs = c.camposDeTabla.get( c.pasosDeTabla.get( t ) != null ? t : corta );
      ValueMetaInterface vm = cs == null ? null : buscar( cs, campoTabla );
      if ( vm == null || cs == null || buscar( cs, claveTabla ) == null ) {
        p.dudas.add( r.f( "map.duda.no_en_tabla", vm == null ? campoTabla : claveTabla, paso ) );
        return true;
      }
      a.campoTabla = vm.getName();
      a.claveTabla = buscar( cs, claveTabla ).getName();
      a.tipoSale = vm.getTypeDesc();
      p.detalles.add( r.f( "map.imp.busca_flujo", destino, paso, clave ) );
    } else {
      if ( c.conexion == null ) {
        p.dudas.add( r.f( "map.duda.sin_conexion", tabla ) );
        return true;
      }
      a = new Accion( BUSCA_BD );
      a.conexion = c.conexion;
      a.campoTabla = campoTabla;
      a.claveTabla = claveTabla;
      a.tipoSale = null; // no se conoce sin conectar: String por defecto en el step
      p.detalles.add( r.f( "map.imp.busca_bd", destino, tabla, clave, c.conexion ) );
    }
    a.tabla = tabla;
    a.claveFlujo = clave;
    a.salida = destino;
    p.acciones.add( a );
    return true;
  }

  private static ValueMetaInterface buscar( Map<String, ValueMetaInterface> cs, String n ) {
    if ( n == null ) {
      return null;
    }
    for ( Map.Entry<String, ValueMetaInterface> e : cs.entrySet() ) {
      if ( e.getKey().equalsIgnoreCase( n ) ) {
        return e.getValue();
      }
    }
    return null;
  }

  // ================================================================ validación

  /** La columna Validación: no nulo, >, >=, <, <=, =, <>, entre, uno de, positivo. */
  static void validacion( String texto, String destino, Ctx c, Plan p ) {
    if ( texto == null || texto.trim().isEmpty() ) {
      return;
    }
    List<String[]> vs = leerValidacion( texto );
    if ( vs == null ) {
      p.dudas.add( c.r.f( "map.duda.validacion", texto ) );
      return;
    }
    Accion previo = null;
    for ( Accion a : p.acciones ) {
      if ( FILTRO.equals( a.tipo ) ) {
        previo = a;
      }
    }
    Accion a = previo != null ? previo : new Accion( FILTRO );
    a.salida = destino;
    a.validaciones.addAll( vs );
    a.textoValidacion = a.textoValidacion == null ? texto.trim() : a.textoValidacion + "; " + texto.trim();
    if ( previo == null ) {
      p.acciones.add( a );
    }
    p.detalles.add( c.r.f( "map.imp.filtro", destino, texto.trim() ) );
  }

  /** Las condiciones de una validación, o null si queda algo sin entender. */
  static List<String[]> leerValidacion( String texto ) {
    List<String[]> out = new ArrayList<String[]>();
    // «entre 1 y 100» no se parte por la «y»
    String todo = norm( texto ).replaceAll( "\\b(between|entre)\\s+([-+]?[\\d.,]+)\\s+(?:and|y|e)\\s+", "$1 $2 hasta " );
    for ( String parte : todo.split("\\s*(?:;|\\band\\b|\\by\\b|\\be\\b|,\\s*(?=(?:not|no|nao|>|<|=|!|between|"
      + "entre|positive|positivo|required|obligatorio|obrigatorio|mandatory)))\\s*" ) ) {
      String x = parte.trim().replaceAll( "\\.$", "" );
      if ( x.isEmpty() ) {
        continue;
      }
      if ( x.matches( "(?:is |must be |tiene que ser |debe ser |deve ser )?(?:not null|not empty|non[- ]empty|required|"
        + "mandatory|no nulo|no vacio|no puede ser nulo|obligatorio|nao nulo|nao vazio|obrigatorio|presente|filled)" ) ) {
        out.add( new String[] { "notnull" } );
        continue;
      }
      if ( x.matches( "(?:is |must be |tiene que ser |debe ser |deve ser )?(?:positive|positivo|mayor que cero|"
        + "maior que zero|greater than zero|> ?0)" ) ) {
        out.add( new String[] { "gt", "0" } );
        continue;
      }
      if ( x.matches( "(?:is |must be |debe ser |deve ser )?(?:non[- ]negative|no negativo|nao negativo|>= ?0)" ) ) {
        out.add( new String[] { "ge", "0" } );
        continue;
      }
      Matcher m = Pattern.compile( "^(?:value |valor )?(>=|<=|<>|!=|>|<|=)\\s*['\"]?([^'\"]+?)['\"]?$" ).matcher( x );
      if ( m.matches() ) {
        String op = m.group( 1 );
        out.add( new String[] { ">=".equals( op ) ? "ge" : "<=".equals( op ) ? "le" : ">".equals( op ) ? "gt" : "<"
          .equals( op ) ? "lt" : "=".equals( op ) ? "eq" : "ne", valorOriginal( texto, m.group( 2 ) ) } );
        continue;
      }
      m = Pattern.compile( "^(?:between|entre)\\s+([-+]?[\\d.,]+)\\s+hasta\\s+([-+]?[\\d.,]+)$" ).matcher( x );
      if ( m.matches() ) {
        out.add( new String[] { "between", m.group( 1 ), m.group( 2 ) } );
        continue;
      }
      m = Pattern.compile( "^(?:(?:is |must be |tiene que ser |debe ser |deve ser |es )?(?:in|one of|uno de|una de|um "
        + "de|uma de|only|solo|apenas))\\s*[:(]?\\s*(.+?)\\)?$" ).matcher( x );
      if ( m.matches() ) {
        List<String> vals = new ArrayList<String>();
        for ( String v : valorOriginal( texto, m.group( 1 ) ).split( "\\s*(?:,|\\bor\\b|\\bo\\b|\\bou\\b|/)\\s*" ) ) {
          String y = v.replaceAll( "^['\"«“]|['\"»”]$", "" ).trim();
          if ( !y.isEmpty() ) {
            vals.add( y );
          }
        }
        if ( vals.size() >= 1 ) {
          vals.add( 0, "in" );
          out.add( vals.toArray( new String[ 0 ] ) );
          continue;
        }
      }
      return null;
    }
    return out.isEmpty() ? null : out;
  }

  // ================================================================ ayudas

  private static final Set<String> RELLENO = new HashSet<String>( Arrays.asList( "the", "a", "an", "of", "to", "in",
    "on", "at", "and", "or", "with", "by", "from", "for", "as", "is", "be", "it", "its", "this", "that", "field",
    "fields", "value", "values", "column", "columns", "text", "all", "each", "every", "always", "then", "also", "must",
    "should", "into", "same", "campo", "campos", "valor", "valores", "columna", "columnas", "coluna", "colunas", "el",
    "la", "los", "las", "lo", "un", "una", "unos", "unas", "de", "del", "al", "en", "y", "o", "u", "con", "por", "para",
    "sin", "que", "se", "su", "sus", "es", "son", "le", "les", "os", "as", "um", "uma", "uns", "umas", "do", "da",
    "dos", "das", "no", "na", "nos", "nas", "em", "e", "ou", "com", "pelo", "pela", "sem", "seu", "sua", "ao", "aos",
    "tambien", "todo", "todos", "toda", "todas", "cada", "siempre", "sempre", "luego", "despues", "depois", "texto",
    "textos", "pasa", "pasan", "passa", "queda", "quedan", "fica", "deja", "dejar", "leave", "keep", "mantener", "manter",
    "ya", "ja", "already", "vienen", "viene", "vem", "come", "comes", "llega", "llegan", "chega", "arrives", "tabla",
    "table", "tabela", "debe", "deben", "deve", "devem", "ser", "estar", "esta", "estan" ) );

  static boolean esRelleno( String w ) {
    return RELLENO.contains( w );
  }

  /** Sin acentos y en minúsculas, con la misma longitud (para borrar por posición sobre el original). */
  static String norm( String s ) {
    String x = s == null ? "" : s;
    StringBuilder sb = new StringBuilder( x.length() );
    for ( int i = 0; i < x.length(); i++ ) {
      String ch = Normalizer.normalize( String.valueOf( x.charAt( i ) ), Normalizer.Form.NFD ).replaceAll( "\\p{M}",
        "" );
      sb.append( ch.length() == 1 ? Character.toLowerCase( ch.charAt( 0 ) ) : Character.toLowerCase( x.charAt( i ) ) );
    }
    return sb.toString();
  }

  /** El texto de la regla en esa posición (con mayúsculas y acentos), sin comillas; o el símbolo de la palabra. */
  static String valorDe( String original, int a, int b, String normalizado ) {
    String x = original.substring( a, Math.min( b, original.length() ) ).trim();
    if ( x.length() >= 2 && ( x.startsWith( "\"" ) && x.endsWith( "\"" ) || x.startsWith( "'" ) && x.endsWith( "'" )
      || x.startsWith( "«" ) && x.endsWith( "»" ) || x.startsWith( "“" ) && x.endsWith( "”" ) ) ) {
      return x.substring( 1, x.length() - 1 );
    }
    switch ( normalizado ) {
      case "comma":
      case "coma":
      case "virgula":
        return ",";
      case "dot":
      case "period":
      case "point":
      case "punto":
      case "ponto":
        return ".";
      case "space":
      case "espacio":
      case "espaco":
        return " ";
      case "hyphen":
      case "guion":
      case "hifen":
      case "dash":
        return "-";
      case "slash":
      case "barra":
        return "/";
      case "nothing":
      case "nada":
      case "empty":
      case "vacio":
      case "vazio":
        return "";
      default:
        return x;
    }
  }

  /** Recupera el texto original (mayúsculas, acentos) de un trozo normalizado de la validación. */
  private static String valorOriginal( String original, String normal ) {
    int i = norm( original ).indexOf( normal );
    return i < 0 ? normal : original.substring( i, i + normal.length() );
  }

  private static String operador( String s ) {
    switch ( s ) {
      case "+":
        return "ADD";
      case "-":
        return "SUBTRACT";
      case "*":
      case "×":
      case "x":
        return "MULTIPLY";
      case "/":
      case "÷":
        return "DIVIDE";
      default:
        return null;
    }
  }

  private static boolean precede( String a, String b ) {
    boolean ma = "MULTIPLY".equals( a ) || "DIVIDE".equals( a );
    boolean mb = "MULTIPLY".equals( b ) || "DIVIDE".equals( b );
    return ma && !mb;
  }

  /** La máscara de fecha o número de la regla («formato aaaa-MM-dd» → yyyy-MM-dd), o null. */
  static String mascara( String regla ) {
    Matcher m = Pattern.compile( "(?i)\\b(?:format|formato|mask|m[aá]scara|pattern|patr[oó]n|padr[aã]o)\\s*:?\\s*['\"«“]?"
      + "([yYaAMdDHhmsS/:. \\-]{4,})['\"»”]?" ).matcher( regla == null ? "" : regla );
    if ( !m.find() ) {
      return null;
    }
    String x = m.group( 1 ).trim();
    if ( !x.matches( ".*[yYaAdD].*" ) ) {
      return null;
    }
    return x.replace( "aaaa", "yyyy" ).replace( "AAAA", "yyyy" ).replace( "aa", "yy" ).replace( "DD", "dd" ).replace(
      "YYYY", "yyyy" );
  }

  /** "." o "," si la regla dice el símbolo decimal; null si no lo dice. */
  static String decimal( String reglaNormal ) {
    String x = reglaNormal == null ? "" : reglaNormal;
    if ( x.matches( ".*\\b(punto decimal|decimal point|decimal dot|ponto decimal)\\b.*" ) ) {
      return ".";
    }
    if ( x.matches( ".*\\b(coma decimal|decimal comma|virgula decimal)\\b.*" ) ) {
      return ",";
    }
    return null;
  }

  static String descripcionCadenas( Accion a, Rotulos r ) {
    List<String> l = new ArrayList<String>();
    if ( !"none".equals( a.trim ) ) {
      l.add( r.s( "map.cad.trim" ) );
    }
    if ( !"none".equals( a.mayus ) ) {
      l.add( r.s( "map.cad." + a.mayus ) );
    }
    if ( a.inicial ) {
      l.add( r.s( "map.cad.inicial" ) );
    }
    if ( !"none".equals( a.digitos ) ) {
      l.add( r.s( "map.cad.digitos" ) );
    }
    return String.join( ", ", l );
  }
}
