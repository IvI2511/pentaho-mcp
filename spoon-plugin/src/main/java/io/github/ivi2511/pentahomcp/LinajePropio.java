package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.lineage.FieldnameLineage;
import org.pentaho.di.resource.ResourceEntry;
import org.pentaho.di.resource.ResourceReference;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.step.errorhandling.StreamInterface;
import org.pentaho.di.trans.steps.calculator.CalculatorMeta;
import org.pentaho.di.trans.steps.calculator.CalculatorMetaFunction;
import org.pentaho.di.trans.steps.constant.ConstantMeta;
import org.pentaho.di.trans.steps.getvariable.GetVariableMeta;
import org.pentaho.di.trans.steps.groupby.GroupByMeta;
import org.pentaho.di.trans.steps.memgroupby.MemoryGroupByMeta;
import org.pentaho.di.trans.steps.mergejoin.MergeJoinMeta;
import org.pentaho.di.trans.steps.multimerge.MultiMergeJoinMeta;
import org.pentaho.di.trans.steps.selectvalues.SelectMetadataChange;
import org.pentaho.di.trans.steps.selectvalues.SelectValuesMeta;
import org.pentaho.di.trans.steps.streamlookup.StreamLookupMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.informes.Contexto;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Linaje de campos propio, solo con metadatos de Kettle (diseno §2.4.1): el respaldo de metaverse.
 * <p>
 * Para cada step pide sus campos de salida a {@link Contexto#salida} (memo y timeout compartidos) y
 * anota de donde sale cada uno: step anterior, campo y operacion. Hay reglas para Select values,
 * Calculator (las constantes son valores, no campos), Group by, Memory group by, Stream lookup, Merge join,
 * Multiway merge join, Formula, los steps que cambian valores ({@link LinajeValores}: String operations, Replace in
 * string, Value mapper, If field value is null...) y las filas de error; para el resto, mismo nombre en un step anterior =
 * pasa igual, sin steps anteriores = fuente, y si no, creado por el step. Los steps anteriores son los de
 * {@code findPreviousSteps(s, true)} menos los de {@code getInfoStep}; si el campo no viene de ninguno, se
 * busca en los de {@code getInfoStep} (Append, Merge rows...: sus filas llegan por streams de info).
 * {@code ValueMetaInterface.getOrigin()} no se usa: es solo una pista.
 * <p>
 * Tambien clasifica los nodos que devuelve metaverse ({@link #clasificar}) y les pone la misma
 * etiqueta ({@link #etiqueta}), asi los dos motores dan caminos comparables.
 * <p>
 * Nunca abre una base de archivo (SQLite, H2) que no existe: al conectarse se crearia vacia. Ese step
 * y los que dependen de el quedan sin resolver, con el motivo.
 *
 * @dueño WP3
 */
final class LinajePropio {

  static final String MOTOR = "kettle-metadata";

  /** Clases de nodo ("kind" en el JSON). */
  static final String FUENTE = "source";
  static final String PASA = "pass";
  static final String RENOMBRA = "rename";
  static final String CONVIERTE = "convert";
  static final String CALCULA = "calc";
  static final String AGREGA = "aggregate";
  static final String BUSCA = "lookup";
  static final String UNE = "join";
  static final String CREA = "created";
  static final String DESCONOCIDO = "unknown";

  /** Rol de un nodo que llega como clave de lookup (metaverse la mezcla con los valores). */
  static final String ROL_CLAVE = "key";

  /** Tope de caminos por campo. */
  static final int MAX_CAMINOS = 25;

  private static final int MAX_PROFUNDIDAD = 80;
  private static final Pattern REF_FORMULA = Pattern.compile( "\\[([^\\]]+)\\]" );

  // ---------------------------------------------------------------- resultado

  /** Un paso de un camino: el campo en un step y como llego ahi desde el nodo anterior. */
  static final class Nodo {
    final String step;
    final String campo;
    /** Una de las clases (FUENTE, PASA...). */
    String clase;
    /** Etiqueta de la operacion (ya traducida); en el primer nodo, el recurso o como nace. */
    String op;
    /** ROL_CLAVE o null. */
    String rol;
    /** Descripcion cruda de las operaciones de metaverse; null en el motor propio. */
    String metaverse;

    Nodo( String step, String campo, String clase, String op ) {
      this.step = step;
      this.campo = campo;
      this.clase = clase;
      this.op = op;
    }

    /** "step:campo:clase:op:rol", para comparar caminos de los dos motores. */
    String firma() {
      return step + ":" + campo + ":" + clase + ":" + ( op == null ? "" : op ) + ":" + ( rol == null ? "" : rol );
    }
  }

  /** Un campo del step destino con sus caminos desde el origen. */
  static final class Campo {
    final String nombre;
    String tipo;
    /** MOTOR o LinajeMetaverse.MOTOR. */
    String motor;
    /** Cada camino va del origen (indice 0) al step destino (ultimo). */
    final List<List<Nodo>> caminos = new ArrayList<>();
    /** true si habia mas de MAX_CAMINOS caminos. */
    boolean truncado;

    Campo( String nombre ) {
      this.nombre = nombre;
    }
  }

  /** El linaje de un step destino. */
  static final class Destino {
    final StepMeta step;
    String motor;
    final List<Campo> campos = new ArrayList<>();
    /** Por que no se pudo calcular (traducido); null si se pudo. */
    String motivo;

    Destino( StepMeta step ) {
      this.step = step;
    }

    Campo campo( String nombre ) {
      for ( Campo c : campos ) {
        if ( c.nombre.equals( nombre ) ) {
          return c;
        }
      }
      return null;
    }
  }

  /** Un step cuyos campos no se pudieron calcular. */
  static final class Pendiente {
    final String step;
    final String motivo;

    Pendiente( String step, String motivo ) {
      this.step = step;
      this.motivo = motivo;
    }
  }

  // ---------------------------------------------------------------- estado interno

  /** Arista campo de salida &lt;- campo de un step anterior. */
  private static final class Ref {
    final String step;
    final String campo;
    final String clase;
    final String op;
    final String rol;

    Ref( String step, String campo, String clase, String op, String rol ) {
      this.step = step;
      this.campo = campo;
      this.clase = clase;
      this.op = op;
      this.rol = rol;
    }
  }

  /** Lo calculado para un step. */
  private static final class Info {
    final StepMeta sm;
    RowMetaInterface salida;
    String error;
    /** campo de salida -&gt; de donde viene. */
    final Map<String, List<Ref>> refs = new LinkedHashMap<>();
    /** campo de salida -&gt; {clase, op} si nace aca. */
    final Map<String, String[]> nace = new LinkedHashMap<>();

    Info( StepMeta sm ) {
      this.sm = sm;
    }
  }

  private final TransMeta tm;
  private final Contexto ctx;
  private final Rotulos rot;
  private final Map<String, Info> info = new HashMap<>();
  /** Campos que nacen en un step sin ser parte de su salida (las filas de error). */
  private final Map<String, String[]> extras = new HashMap<>();
  private final Map<String, String> bloqueos = new HashMap<>();
  private final Set<String> bloqueando = new HashSet<>();
  private final Map<String, Pendiente> pendientes = new LinkedHashMap<>();
  /** step -&gt; los campos a los que les cambia el valor ({@link LinajeValores}). */
  private final Map<String, Map<String, LinajeValores.Cambio>> cambios = new HashMap<>();

  LinajePropio( TransMeta tm, Contexto ctx ) {
    this.tm = tm;
    this.ctx = ctx;
    this.rot = ctx.rotulos( SecLinaje.AREA );
  }

  TransMeta trans() {
    return tm;
  }

  Rotulos rotulos() {
    return rot;
  }

  // ---------------------------------------------------------------- API

  /**
   * Campos de salida del step, o null si no se pueden calcular (queda en {@link #pendientes()}). No
   * abre bases de archivo que no existen.
   */
  RowMetaInterface campos( StepMeta s ) {
    Info in = info( s );
    return in.error == null ? in.salida : null;
  }

  /** Por que no se pudieron calcular los campos del step (traducido), o null. */
  String error( StepMeta s ) {
    return info( s ).error;
  }

  /** El linaje de todos los campos del step destino. */
  Destino destino( StepMeta s ) {
    Destino d = new Destino( s );
    d.motor = MOTOR;
    Info in = info( s );
    if ( in.error != null ) {
      d.motivo = in.error;
      explorarArriba( s );
      return d;
    }
    for ( ValueMetaInterface v : in.salida.getValueMetaList() ) {
      if ( ctx.cancelado() ) {
        break;
      }
      d.campos.add( campo( s, v ) );
    }
    return d;
  }

  /** El linaje de un campo del step destino. */
  Campo campo( StepMeta s, ValueMetaInterface v ) {
    Campo c = new Campo( v.getName() );
    c.tipo = v.getTypeDesc();
    c.motor = MOTOR;
    List<List<Nodo>> cs = caminos( s.getName(), v.getName() );
    if ( cs.size() > MAX_CAMINOS ) {
      c.truncado = true;
      cs = cs.subList( 0, MAX_CAMINOS );
    }
    c.caminos.addAll( cs );
    return c;
  }

  /** Steps que no se pudieron resolver, en el orden en que aparecieron. */
  List<Pendiente> pendientes() {
    return new ArrayList<>( pendientes.values() );
  }

  /** Steps finales que escriben; si ninguno escribe, todos los finales (en el orden del documento). */
  static List<StepMeta> destinosPorDefecto( TransMeta tm, List<StepMeta> orden ) {
    List<StepMeta> finales = new ArrayList<>();
    List<StepMeta> todos = orden == null || orden.isEmpty() ? tm.getSteps() : orden;
    for ( StepMeta s : todos ) {
      boolean enHops = tm.isStepUsedInTransHops( s ) || tm.nrSteps() == 1;
      if ( enHops && tm.findNextSteps( s ).isEmpty() ) {
        finales.add( s );
      }
    }
    List<StepMeta> escriben = new ArrayList<>();
    for ( StepMeta s : finales ) {
      if ( RunMotor.escribe( s ) ) {
        escriben.add( s );
      }
    }
    return escriben.isEmpty() ? finales : escriben;
  }

  // ---------------------------------------------------------------- analisis por step

  private Info info( StepMeta s ) {
    Info in = info.get( s.getName() );
    if ( in != null ) {
      return in;
    }
    in = new Info( s );
    info.put( s.getName(), in ); // antes de analizar: corta ciclos
    String b = bloqueo( s );
    if ( b != null ) {
      in.error = b;
      pendiente( s, b );
      return in;
    }
    try {
      in.salida = ctx.salida( s );
      if ( in.salida == null ) {
        in.error = rot.s( "pendiente.sin_campos" );
      }
    } catch ( Throwable e ) {
      in.error = SecLinaje.motivoCorto( e );
    }
    if ( in.error != null ) {
      pendiente( s, in.error );
      return in;
    }
    try {
      analizar( in );
    } catch ( Throwable e ) {
      // la regla del step fallo (configuracion rara): sus campos quedan sin origen conocido
      in.refs.clear();
      in.nace.clear();
      String m = SecLinaje.motivoCorto( e );
      for ( ValueMetaInterface v : in.salida.getValueMetaList() ) {
        in.nace.put( v.getName(), new String[] { DESCONOCIDO, m } );
      }
    }
    return in;
  }

  private void pendiente( StepMeta s, String motivo ) {
    if ( !pendientes.containsKey( s.getName() ) ) {
      pendientes.put( s.getName(), new Pendiente( s.getName(), motivo ) );
    }
  }

  /** Cuando el destino falla: calcula los steps de arriba para que la causa quede en pendientes. */
  private void explorarArriba( StepMeta s ) {
    List<StepMeta> cola = new ArrayList<>( tm.findPreviousSteps( s, true ) );
    Set<String> vistos = new HashSet<>();
    vistos.add( s.getName() );
    while ( !cola.isEmpty() && !ctx.cancelado() ) {
      StepMeta p = cola.remove( 0 );
      if ( !vistos.add( p.getName() ) ) {
        continue;
      }
      info( p );
      cola.addAll( tm.findPreviousSteps( p, true ) );
    }
    // el destino primero quedo como pendiente por arrastre: la causa raiz va antes
    Pendiente propio = pendientes.remove( s.getName() );
    if ( propio != null ) {
      pendientes.put( s.getName(), propio );
    }
  }

  private void ref( Info in, String campo, Ref r ) {
    List<Ref> l = in.refs.get( campo );
    if ( l == null ) {
      l = new ArrayList<>();
      in.refs.put( campo, l );
    }
    l.add( r );
  }

  /** Los steps anteriores por el flujo principal (sin los que llegan por un stream de info). */
  private List<StepMeta> anteriores( StepMeta s ) {
    List<StepMeta> todos = tm.findPreviousSteps( s, true );
    Set<String> deInfo = new HashSet<>();
    for ( StepMeta i : infos( s ) ) {
      deInfo.add( i.getName() );
    }
    List<StepMeta> main = new ArrayList<>();
    for ( StepMeta p : todos ) {
      if ( !deInfo.contains( p.getName() ) ) {
        main.add( p );
      }
    }
    return main;
  }

  /**
   * Los steps que llegan por un stream de info, en el orden del step: el head y el tail de Append, la
   * referencia y la comparacion de Merge rows, cada entrada de Multiway merge join, el lookup...
   */
  private List<StepMeta> infos( StepMeta s ) {
    List<StepMeta> l = new ArrayList<>();
    StepMeta[] infos = tm.getInfoStep( s );
    if ( infos != null ) {
      for ( StepMeta i : infos ) {
        if ( i != null && !l.contains( i ) ) {
          l.add( i );
        }
      }
    }
    return l;
  }

  private void analizar( Info in ) throws Exception {
    StepMeta s = in.sm;
    StepMetaInterface m = s.getStepMetaInterface();
    List<StepMeta> previos = anteriores( s );
    List<StepMeta> conError = new ArrayList<>();
    List<StepMeta> normales = new ArrayList<>();
    for ( StepMeta p : previos ) {
      if ( mandaErrores( p, s ) ) {
        conError.add( p );
      } else {
        normales.add( p );
      }
    }
    Set<String> hechos = new HashSet<>();

    if ( m instanceof SelectValuesMeta ) {
      for ( FieldnameLineage fl : ( (SelectValuesMeta) m ).getFieldnameLineage() ) {
        for ( StepMeta p : normales ) {
          ref( in, fl.getOutputFieldname(), new Ref( p.getName(), fl.getInputFieldname(), RENOMBRA,
            etiqueta( s, RENOMBRA, fl.getInputFieldname(), fl.getOutputFieldname() ), null ) );
        }
        hechos.add( fl.getOutputFieldname() );
      }
      SelectMetadataChange[] metas = ( (SelectValuesMeta) m ).getMeta();
      for ( int i = 0; metas != null && i < metas.length; i++ ) {
        SelectMetadataChange c = metas[ i ];
        String sale = vacio( c.getRename() ) ? c.getName() : c.getRename();
        if ( hechos.contains( sale ) || !convierte( c ) ) {
          continue;
        }
        for ( StepMeta p : normales ) {
          ref( in, sale, new Ref( p.getName(), c.getName(), CONVIERTE, etiqueta( s, CONVIERTE, c.getName(), sale ),
            null ) );
        }
        hechos.add( sale );
      }
    } else if ( m instanceof CalculatorMeta ) {
      Map<String, List<String>> intermedios = new HashMap<>();
      for ( CalculatorMetaFunction f : ( (CalculatorMeta) m ).getCalculation() ) {
        if ( f.getCalcType() == CalculatorMetaFunction.CALC_CONSTANT ) {
          // «Crear una constante»: field_a es el VALOR, no un campo (antes salia como un campo «1 (?)»)
          intermedios.put( f.getFieldName(), new ArrayList<String>() );
          in.nace.put( f.getFieldName(), new String[] { CREA, constante( f ) } );
          hechos.add( f.getFieldName() );
          continue;
        }
        List<String> fuentes = new ArrayList<>();
        for ( String a : noVacios( f.getFieldA(), f.getFieldB(), f.getFieldC() ) ) {
          if ( intermedios.containsKey( a ) ) {
            fuentes.addAll( intermedios.get( a ) ); // calculos encadenados del mismo step
          } else {
            fuentes.add( a );
          }
        }
        intermedios.put( f.getFieldName(), fuentes );
        String op = etiqueta( s, CALCULA, null, f.getFieldName() );
        for ( String a : new LinkedHashSet<>( fuentes ) ) {
          for ( StepMeta p : normales ) {
            ref( in, f.getFieldName(), new Ref( p.getName(), a, CALCULA, op, null ) );
          }
        }
        if ( fuentes.isEmpty() ) {
          in.nace.put( f.getFieldName(), new String[] { CREA, op } );
        }
        hechos.add( f.getFieldName() );
      }
    } else if ( m instanceof GroupByMeta || m instanceof MemoryGroupByMeta ) {
      String[] agregados = m instanceof GroupByMeta ? ( (GroupByMeta) m ).getAggregateField()
        : ( (MemoryGroupByMeta) m ).getAggregateField();
      String[] sujetos = m instanceof GroupByMeta ? ( (GroupByMeta) m ).getSubjectField()
        : ( (MemoryGroupByMeta) m ).getSubjectField();
      for ( int i = 0; agregados != null && i < agregados.length; i++ ) {
        String op = etiqueta( s, AGREGA, sujetos[ i ], agregados[ i ] );
        for ( StepMeta p : normales ) {
          ref( in, agregados[ i ], new Ref( p.getName(), sujetos[ i ], AGREGA, op, null ) );
        }
        hechos.add( agregados[ i ] );
      }
    } else if ( m instanceof StreamLookupMeta ) {
      StreamLookupMeta l = (StreamLookupMeta) m;
      String fuente = fuenteLookup( l );
      for ( int i = 0; fuente != null && l.getValue() != null && i < l.getValue().length; i++ ) {
        String sale = vacio( l.getValueName()[ i ] ) ? l.getValue()[ i ] : l.getValueName()[ i ];
        ref( in, sale, new Ref( fuente, l.getValue()[ i ], BUSCA, etiqueta( s, BUSCA, l.getValue()[ i ], sale ),
          null ) );
        hechos.add( sale );
      }
    } else if ( m instanceof MergeJoinMeta || m instanceof MultiMergeJoinMeta ) {
      // la salida son los campos de cada entrada, en orden (los repetidos renombrados con _1, _2...)
      List<StepMeta> entradas = new ArrayList<>();
      List<RowMetaInterface> filas = new ArrayList<>();
      for ( StreamInterface x : m.getStepIOMeta().getInfoStreams() ) {
        StepMeta e = x.getStepname() == null ? null : tm.findStep( x.getStepname() );
        RowMetaInterface ce = e == null ? null : campos( e );
        if ( ce == null ) {
          entradas.clear();
          break;
        }
        entradas.add( e );
        filas.add( ce );
      }
      int i = 0;
      for ( int k = 0; k < entradas.size(); k++ ) {
        for ( int j = 0; j < filas.get( k ).size() && i < in.salida.size(); j++, i++ ) {
          String f = in.salida.getValueMeta( i ).getName();
          String desde = filas.get( k ).getValueMeta( j ).getName();
          ref( in, f, new Ref( entradas.get( k ).getName(), desde, UNE, etiqueta( s, UNE, desde, f ), null ) );
          hechos.add( f );
        }
      }
    } else if ( "Formula".equals( s.getStepID() ) ) {
      // es un plugin con su propio classloader: se lee su XML en lugar de castear
      Document d = XMLHandler.loadXMLString( "<step>" + m.getXML() + "</step>" );
      Node raiz = XMLHandler.getSubNode( d, "step" );
      for ( Node f : XMLHandler.getNodes( raiz, "formula" ) ) {
        String nombre = XMLHandler.getTagValue( f, "field_name" );
        if ( vacio( nombre ) ) {
          continue;
        }
        String op = etiqueta( s, CALCULA, null, nombre );
        Set<String> usados = referenciasFormula( XMLHandler.getTagValue( f, "formula_string" ) );
        for ( String u : usados ) {
          for ( StepMeta p : normales ) {
            ref( in, nombre, new Ref( p.getName(), u, CALCULA, op, null ) );
          }
        }
        if ( usados.isEmpty() ) {
          in.nace.put( nombre, new String[] { CREA, op } );
        }
        hechos.add( nombre );
      }
    }

    // steps que cambian el valor sin cambiar el nombre (String operations, Value mapper, If field value is null...)
    // o que crean un campo a partir de otros (Concat fields, Split fields...): antes quedaban como «pasa igual»
    for ( LinajeValores.Cambio c : cambios( s ).values() ) {
      int k = in.salida.indexOfValue( c.sale );
      String sale = k < 0 ? null : in.salida.getValueMeta( k ).getName();
      if ( sale == null || hechos.contains( sale ) ) {
        continue;
      }
      hechos.add( sale );
      if ( c.entran.isEmpty() ) {
        in.nace.put( sale, new String[] { CREA, c.op } );
        continue;
      }
      for ( String a : c.entran ) {
        String clase = c.op != null ? CALCULA : a.equals( sale ) ? PASA : RENOMBRA;
        String op = c.op != null ? c.op : PASA.equals( clase ) ? null : a + " → " + sale;
        boolean alguno = false;
        for ( StepMeta p : normales ) {
          RowMetaInterface cp = campos( p );
          int i = cp == null ? -1 : cp.indexOfValue( a );
          if ( i >= 0 ) {
            ref( in, sale, new Ref( p.getName(), cp.getValueMeta( i ).getName(), clase, op, null ) );
            alguno = true;
          }
        }
        if ( !alguno ) {
          for ( StepMeta p : normales ) {
            ref( in, sale, new Ref( p.getName(), a, clase, op, null ) );
          }
        }
      }
    }

    // filas de error que llegan de un step anterior: sus campos de error nacen en ese step
    for ( StepMeta p : conError ) {
      StepErrorMeta em = p.getStepErrorMeta();
      String op = rot.f( "op.error", p.getName() );
      for ( String f : noVacios( em.getNrErrorsValuename(), em.getErrorDescriptionsValuename(),
        em.getErrorFieldsValuename(), em.getErrorCodesValuename() ) ) {
        f = tm.environmentSubstitute( f );
        if ( in.salida.indexOfValue( f ) >= 0 && !hechos.contains( f ) ) {
          extras.put( p.getName() + "\u0000" + f, new String[] { CREA, op } );
          ref( in, f, new Ref( p.getName(), f, PASA, null, null ) );
          hechos.add( f );
        }
      }
    }

    // regla general: mismo nombre en un step anterior = pasa igual. Si no esta en el flujo principal se
    // busca en los streams de info: Append (head y tail), Merge rows (diff), Fuzzy match... traen sus filas
    // por ahi y sin esto todo campo quedaba como «creado» por el step (salvo Stream lookup, que tiene su regla)
    List<StepMeta> porInfo = m instanceof StreamLookupMeta ? Collections.<StepMeta>emptyList() : infos( s );
    for ( ValueMetaInterface v : in.salida.getValueMetaList() ) {
      String f = v.getName();
      if ( hechos.contains( f ) ) {
        continue;
      }
      boolean pasa = false;
      for ( StepMeta p : normales ) {
        pasa |= pasa( in, s, p, f );
      }
      for ( StepMeta p : conError ) {
        // una fila de error trae la entrada del step que fallo: viene de los anteriores a ese step
        for ( StepMeta pp : anteriores( p ) ) {
          pasa |= pasa( in, s, pp, f );
        }
      }
      if ( !pasa ) {
        // todas las entradas de info que lo traen son origenes (las dos de un Append)
        for ( StepMeta p : porInfo ) {
          pasa |= pasa( in, s, p, f );
        }
      }
      if ( !pasa ) {
        in.nace.put( f, origen( s, f ) );
      }
    }

    // claves del Stream lookup: el campo del flujo principal tambien sale de la clave de busqueda
    if ( m instanceof StreamLookupMeta ) {
      StreamLookupMeta l = (StreamLookupMeta) m;
      String fuente = fuenteLookup( l );
      for ( int i = 0; fuente != null && l.getKeystream() != null && i < l.getKeystream().length; i++ ) {
        String clave = l.getKeystream()[ i ];
        if ( in.salida.indexOfValue( clave ) >= 0 && i < l.getKeylookup().length ) {
          ref( in, clave, new Ref( fuente, l.getKeylookup()[ i ], BUSCA, etiqueta( s, BUSCA, l.getKeylookup()[ i ],
            clave ), ROL_CLAVE ) );
        }
      }
    }
  }

  /**
   * Si el step anterior p tiene el campo f, anota que pasa de ahi y devuelve true. Kettle compara los
   * nombres sin mayusculas: si en p se llama «Fecha» y aca «fecha» (un Select values que solo cambia la
   * mayuscula no lo cuenta como renombre), la Ref va al nombre de p, como renombre; con el nombre de aca el
   * camino se cortaba en p con un origen desconocido.
   */
  private boolean pasa( Info in, StepMeta s, StepMeta p, String f ) {
    RowMetaInterface cp = campos( p );
    int i = cp == null ? -1 : cp.indexOfValue( f );
    if ( i < 0 ) {
      return false;
    }
    String alla = cp.getValueMeta( i ).getName();
    ref( in, f, alla.equals( f ) ? new Ref( p.getName(), f, PASA, null, null )
      : new Ref( p.getName(), alla, RENOMBRA, etiqueta( s, RENOMBRA, alla, f ), null ) );
    return true;
  }

  private boolean mandaErrores( StepMeta p, StepMeta s ) {
    StepErrorMeta em = p.getStepErrorMeta();
    return em != null && em.isEnabled() && em.getTargetStep() != null && em.getTargetStep().getName().equals( s
      .getName() );
  }

  /** true si el cambio de metadatos de Select values toca algo mas que el nombre. */
  private static boolean convierte( SelectMetadataChange c ) {
    return c.getType() != ValueMetaInterface.TYPE_NONE || c.getLength() != -2 || c.getPrecision() != -2
      || !vacio( c.getConversionMask() ) || !vacio( c.getDecimalSymbol() ) || !vacio( c.getGroupingSymbol() )
      || !vacio( c.getCurrencySymbol() ) || !vacio( c.getEncoding() ) || c.getStorageType() > 0;
  }

  /** Los cambios de valor del step (memo por step). */
  private Map<String, LinajeValores.Cambio> cambios( StepMeta s ) {
    Map<String, LinajeValores.Cambio> m = cambios.get( s.getName() );
    if ( m == null ) {
      m = LinajeValores.TIPOS.contains( s.getStepID() ) ? LinajeValores.de( s, campos( s ), rot )
        : new LinkedHashMap<String, LinajeValores.Cambio>();
      cambios.put( s.getName(), m );
    }
    return m;
  }

  /** Lo que el step le hace al valor del campo de salida (String operations, Value mapper...), o null. */
  LinajeValores.Cambio cambio( StepMeta s, String campo ) {
    if ( s == null || campo == null || !LinajeValores.TIPOS.contains( s.getStepID() ) ) {
      return null;
    }
    return sinMayusculas( cambios( s ), campo );
  }

  private static Set<String> referenciasFormula( String expr ) {
    Set<String> usados = new LinkedHashSet<>();
    Matcher mt = REF_FORMULA.matcher( expr == null ? "" : expr );
    while ( mt.find() ) {
      usados.add( mt.group( 1 ) );
    }
    return usados;
  }

  private static String fuenteLookup( StreamLookupMeta l ) {
    try {
      return l.getStepIOMeta().getInfoStreams().get( 0 ).getStepname();
    } catch ( Exception e ) {
      return null;
    }
  }

  // ---------------------------------------------------------------- caminos

  /** Caminos del campo hasta su o sus origenes; cada uno va del origen (indice 0) al step pedido. */
  List<List<Nodo>> caminos( String step, String campo ) {
    List<List<Nodo>> out = new ArrayList<>();
    caminos( step, campo, new ArrayList<String>(), out );
    return out;
  }

  /**
   * Agrega a out los caminos que terminan en (step, campo). La clase y la op de cada nodo salen de la
   * Ref del propio step que dice de donde viene el campo; el origen, de como nace.
   */
  private void caminos( String step, String campo, List<String> enCurso, List<List<Nodo>> out ) {
    if ( out.size() > MAX_CAMINOS ) {
      return;
    }
    String clave = step + "\u0000" + campo;
    StepMeta sm = tm.findStep( step );
    if ( sm == null || enCurso.contains( clave ) || enCurso.size() > MAX_PROFUNDIDAD ) {
      out.add( unNodo( new Nodo( step, campo, DESCONOCIDO, null ) ) );
      return;
    }
    Info in = info( sm );
    if ( in.error != null ) {
      out.add( unNodo( new Nodo( step, campo, DESCONOCIDO, in.error ) ) );
      return;
    }
    List<Ref> rs = sinMayusculas( in.refs, campo );
    if ( rs == null || rs.isEmpty() ) {
      String[] n = extras.get( clave );
      if ( n == null ) {
        n = sinMayusculas( in.nace, campo );
      }
      out.add( unNodo( n == null ? new Nodo( step, campo, DESCONOCIDO, null ) : new Nodo( step, campo, n[ 0 ],
        n[ 1 ] ) ) );
      return;
    }
    enCurso.add( clave );
    for ( Ref r : rs ) {
      int antes = out.size();
      caminos( r.step, r.campo, enCurso, out );
      for ( int i = antes; i < out.size(); i++ ) {
        Nodo x = new Nodo( step, campo, r.clase, r.op );
        x.rol = r.rol;
        out.get( i ).add( x );
      }
      if ( out.size() > MAX_CAMINOS ) {
        break;
      }
    }
    enCurso.remove( enCurso.size() - 1 );
  }

  /**
   * Orden estable de los caminos de un campo, igual para los dos motores (metaverse los da en un Set):
   * primero los del flujo principal y despues los de claves de lookup; despues por el step de origen
   * (orden topologico) y por la posicion del campo en la salida de ese step.
   */
  void ordenar( List<List<Nodo>> cs, List<StepMeta> orden ) {
    final Map<String, Integer> pos = new HashMap<>();
    for ( int i = 0; orden != null && i < orden.size(); i++ ) {
      pos.put( orden.get( i ).getName(), i );
    }
    Collections.sort( cs, ( a, b ) -> {
      int c = Boolean.compare( conClave( a ), conClave( b ) );
      if ( c == 0 ) {
        c = Integer.compare( indice( pos, a.get( 0 ).step ), indice( pos, b.get( 0 ).step ) );
      }
      if ( c == 0 ) {
        c = Integer.compare( posicion( a.get( 0 ) ), posicion( b.get( 0 ) ) );
      }
      return c != 0 ? c : LinajeMetaverse.firma( a ).compareTo( LinajeMetaverse.firma( b ) );
    } );
  }

  private static boolean conClave( List<Nodo> c ) {
    for ( Nodo n : c ) {
      if ( ROL_CLAVE.equals( n.rol ) ) {
        return true;
      }
    }
    return false;
  }

  private static int indice( Map<String, Integer> pos, String step ) {
    Integer i = pos.get( step );
    return i == null ? Integer.MAX_VALUE : i;
  }

  private int posicion( Nodo n ) {
    StepMeta s = tm.findStep( n.step );
    RowMetaInterface rm = s == null ? null : campos( s );
    int i = rm == null ? -1 : rm.indexOfValue( n.campo );
    return i < 0 ? Integer.MAX_VALUE : i;
  }

  /** El valor de la clave, o el de una que solo cambia en mayusculas (Kettle no las distingue). */
  private static <T> T sinMayusculas( Map<String, T> m, String clave ) {
    T v = m.get( clave );
    if ( v == null && clave != null ) {
      for ( Map.Entry<String, T> e : m.entrySet() ) {
        if ( clave.equalsIgnoreCase( e.getKey() ) ) {
          return e.getValue();
        }
      }
    }
    return v;
  }

  private static List<Nodo> unNodo( Nodo n ) {
    List<Nodo> c = new ArrayList<>();
    c.add( n );
    return c;
  }

  // ---------------------------------------------------------------- bases de archivo

  /**
   * Motivo (traducido) por el que no se calculan los campos del step: usa una base de archivo que no
   * existe, o depende de un step que la usa. null si no hay problema.
   */
  String bloqueo( StepMeta s ) {
    String nombre = s.getName();
    if ( bloqueos.containsKey( nombre ) ) {
      return bloqueos.get( nombre );
    }
    if ( !bloqueando.add( nombre ) ) {
      return null; // ciclo
    }
    String b = null;
    try {
      String falta = baseFaltante( s );
      if ( falta != null ) {
        b = rot.f( "pendiente.base", falta );
      } else {
        for ( StepMeta p : tm.findPreviousSteps( s, true ) ) {
          if ( bloqueo( p ) != null ) {
            b = rot.f( "pendiente.arriba", p.getName() );
            break;
          }
        }
      }
    } finally {
      bloqueando.remove( nombre );
    }
    bloqueos.put( nombre, b );
    return b;
  }

  /** true si algun step de la transformacion usa una base de archivo que no existe. */
  boolean hayBaseFaltante() {
    for ( StepMeta s : tm.getSteps() ) {
      if ( baseFaltante( s ) != null ) {
        return true;
      }
    }
    return false;
  }

  /**
   * Ruta de la base de archivo que usa el step y no existe, o null. Es la misma comprobacion que hacen el
   * diccionario y la validacion (SecDestino.archivoFaltante): SQLite, H2, HSQLDB y JDBC generico, con la
   * URI de ${Internal.Entry.Current.Directory} decodificada. La copia propia que habia aca no decodificaba
   * los %20 y daba por faltante cualquier base dentro de una carpeta con espacios.
   */
  private String baseFaltante( StepMeta s ) {
    DatabaseMeta[] dbs;
    try {
      dbs = s.getStepMetaInterface().getUsedDatabaseConnections();
    } catch ( Throwable e ) {
      return null;
    }
    if ( dbs == null ) {
      return null;
    }
    for ( DatabaseMeta db : dbs ) {
      File f = SecDestino.archivoFaltante( db, tm );
      if ( f != null ) {
        return relativa( f.getPath() );
      }
    }
    return null;
  }

  /** La ruta relativa a la carpeta del documento si esta adentro (no mostrar rutas de mas). */
  private String relativa( String ruta ) {
    try {
      String dir = tm.environmentSubstitute( "${Internal.Entry.Current.Directory}" );
      if ( !vacio( dir ) && !dir.contains( "${" ) ) {
        String a = new File( ruta ).getAbsolutePath();
        // ${Internal.Entry.Current.Directory} es una URI: una carpeta con espacios llega con %20.
        String b = new File( InformeServicio.Txt.local( dir ) ).getAbsolutePath() + File.separator;
        if ( a.toLowerCase().startsWith( b.toLowerCase() ) ) {
          return a.substring( b.length() ).replace( '\\', '/' );
        }
      }
    } catch ( Throwable ignored ) {
      // la ruta tal cual
    }
    return ruta;
  }

  // ---------------------------------------------------------------- clases y etiquetas

  /** {clase, op} de un campo que nace en el step: fuente si no tiene steps anteriores, si no creado. */
  String[] origen( StepMeta s, String campo ) {
    if ( s == null ) {
      return new String[] { DESCONOCIDO, null };
    }
    if ( tm.findPreviousSteps( s, true ).isEmpty() ) {
      return new String[] { FUENTE, recurso( s ) };
    }
    return new String[] { CREA, creado( s, campo ) };
  }

  /** Como nace el campo dentro del step: constante, variable, calculo sin entradas o "creado por". */
  private String creado( StepMeta s, String campo ) {
    try {
      StepMetaInterface m = s.getStepMetaInterface();
      if ( m instanceof ConstantMeta ) {
        ConstantMeta c = (ConstantMeta) m;
        for ( int i = 0; c.getFieldName() != null && i < c.getFieldName().length; i++ ) {
          if ( campo.equals( c.getFieldName()[ i ] ) ) {
            return rot.f( "op.constante", c.getValue()[ i ] == null ? "" : c.getValue()[ i ] );
          }
        }
      } else if ( m instanceof GetVariableMeta ) {
        for ( GetVariableMeta.FieldDefinition f : ( (GetVariableMeta) m ).getFieldDefinitions() ) {
          if ( campo.equals( f.getFieldName() ) ) {
            return rot.f( "op.variable", f.getVariableString() );
          }
        }
      } else if ( m instanceof CalculatorMeta || "Formula".equals( s.getStepID() ) ) {
        String e = etiqueta( s, CALCULA, null, campo );
        if ( e != null ) {
          return e;
        }
      }
    } catch ( Throwable ignored ) {
      // configuracion rara: la generica
    }
    return rot.f( "op.creado", NombresPlugin.nombre( StepPluginType.class, s.getStepID(), rot.idioma() ) );
  }

  /** Lo que lee un step de entrada: el archivo, la consulta a una conexion o el tipo de step. */
  String recurso( StepMeta s ) {
    try {
      StepMetaInterface m = s.getStepMetaInterface();
      if ( m instanceof TableInputMeta ) {
        DatabaseMeta db = ( (TableInputMeta) m ).getDatabaseMeta();
        return rot.f( "op.sql", db == null ? "?" : db.getName() );
      }
      List<ResourceReference> refs = m.getResourceDependencies( tm, s );
      Set<String> archivos = new LinkedHashSet<>();
      for ( ResourceReference r : refs == null ? Collections.<ResourceReference>emptyList() : refs ) {
        for ( ResourceEntry e : r.getEntries() ) {
          if ( e.getResourcetype() == ResourceEntry.ResourceType.FILE && !vacio( e.getResource() ) ) {
            String f = tm.environmentSubstitute( e.getResource() ).replace( '\\', '/' );
            archivos.add( f.substring( f.lastIndexOf( '/' ) + 1 ) );
          }
        }
      }
      if ( !archivos.isEmpty() ) {
        return SecLinaje.unir( new ArrayList<>( archivos ), ", " );
      }
    } catch ( Throwable ignored ) {
      // sin recurso: el tipo de step
    }
    return NombresPlugin.nombre( StepPluginType.class, s.getStepID(), rot.idioma() );
  }

  /**
   * Clase de un nodo de metaverse a partir del tipo de step, el nodo anterior y las operaciones que
   * informo metaverse (por categoria). Devuelve {clase, rol}.
   *
   * @param previo  nodo anterior del camino (no null)
   * @param agrega  metaverse informo una operacion de agregacion
   * @param calcula metaverse informo un calculo (u otra operacion de datos)
   * @param nombre  metaverse informo un cambio de nombre
   * @param meta    metaverse informo otros cambios de metadatos
   */
  String[] clasificar( StepMeta s, Nodo previo, String campo, boolean agrega, boolean calcula, boolean nombre,
                       boolean meta ) {
    StepMetaInterface m = s == null ? null : s.getStepMetaInterface();
    if ( agrega ) {
      return new String[] { AGREGA, null };
    }
    if ( calcula ) {
      return new String[] { CALCULA, null };
    }
    LinajeValores.Cambio cv = cambio( s, campo );
    if ( cv != null && cv.op != null && ( cv.entran.isEmpty() || contiene( cv.entran, previo.campo ) ) ) {
      return new String[] { CALCULA, null }; // metaverse no siempre informa la operacion de estos steps
    }
    if ( m instanceof StreamLookupMeta ) {
      StreamLookupMeta l = (StreamLookupMeta) m;
      if ( previo.step.equals( fuenteLookup( l ) ) ) {
        String[] ks = l.getKeystream();
        String[] kl = l.getKeylookup();
        for ( int i = 0; ks != null && kl != null && i < ks.length && i < kl.length; i++ ) {
          if ( campo.equals( ks[ i ] ) && previo.campo.equals( kl[ i ] ) && !esValor( l, previo.campo, campo ) ) {
            return new String[] { BUSCA, ROL_CLAVE };
          }
        }
        return new String[] { BUSCA, null };
      }
    }
    if ( m instanceof MergeJoinMeta || m instanceof MultiMergeJoinMeta ) {
      return new String[] { UNE, null };
    }
    if ( nombre || !campo.equals( previo.campo ) ) {
      return new String[] { RENOMBRA, null };
    }
    if ( meta ) {
      return new String[] { CONVIERTE, null };
    }
    return new String[] { PASA, null };
  }

  private static boolean contiene( List<String> l, String x ) {
    for ( String y : l ) {
      if ( y.equalsIgnoreCase( x ) ) {
        return true;
      }
    }
    return false;
  }

  private static boolean esValor( StreamLookupMeta l, String valor, String sale ) {
    for ( int i = 0; l.getValue() != null && i < l.getValue().length; i++ ) {
      String n = vacio( l.getValueName()[ i ] ) ? l.getValue()[ i ] : l.getValueName()[ i ];
      if ( valor.equals( l.getValue()[ i ] ) && sale.equals( n ) ) {
        return true;
      }
    }
    return false;
  }

  /**
   * Etiqueta (traducida) de la operacion que deja el campo "sale" en el step s, a partir de "entra".
   * null si la clase no lleva etiqueta o el step no la conoce (metaverse usa entonces su descripcion).
   */
  String etiqueta( StepMeta s, String clase, String entra, String sale ) {
    if ( s == null || clase == null || PASA.equals( clase ) ) {
      return null;
    }
    try {
      StepMetaInterface m = s.getStepMetaInterface();
      LinajeValores.Cambio cv = CALCULA.equals( clase ) ? cambio( s, sale ) : null;
      if ( cv != null && cv.op != null ) {
        return cv.op;
      }
      if ( CALCULA.equals( clase ) && m instanceof CalculatorMeta ) {
        CalculatorMetaFunction[] fs = ( (CalculatorMeta) m ).getCalculation();
        int k = definicion( fs, sale, fs.length );
        if ( k >= 0 ) {
          return fs[ k ].getCalcType() == CalculatorMetaFunction.CALC_CONSTANT ? constante( fs[ k ] )
            : expresion( fs, k, 0 );
        }
      } else if ( CALCULA.equals( clase ) && "Formula".equals( s.getStepID() ) ) {
        Document d = XMLHandler.loadXMLString( "<step>" + m.getXML() + "</step>" );
        for ( Node f : XMLHandler.getNodes( XMLHandler.getSubNode( d, "step" ), "formula" ) ) {
          if ( sale.equals( XMLHandler.getTagValue( f, "field_name" ) ) ) {
            return rot.f( "op.formula", SecLinaje.recortar( XMLHandler.getTagValue( f, "formula_string" ), 120 ) );
          }
        }
      } else if ( AGREGA.equals( clase ) && ( m instanceof GroupByMeta || m instanceof MemoryGroupByMeta ) ) {
        boolean gb = m instanceof GroupByMeta;
        String[] agr = gb ? ( (GroupByMeta) m ).getAggregateField() : ( (MemoryGroupByMeta) m ).getAggregateField();
        String[] suj = gb ? ( (GroupByMeta) m ).getSubjectField() : ( (MemoryGroupByMeta) m ).getSubjectField();
        int[] tipo = gb ? ( (GroupByMeta) m ).getAggregateType() : ( (MemoryGroupByMeta) m ).getAggregateType();
        for ( int i = 0; agr != null && i < agr.length; i++ ) {
          if ( sale.equals( agr[ i ] ) ) {
            String t = gb ? GroupByMeta.getTypeDesc( tipo[ i ] ) : MemoryGroupByMeta.getTypeDesc( tipo[ i ] );
            return t + "(" + suj[ i ] + ")";
          }
        }
      } else if ( BUSCA.equals( clase ) && m instanceof StreamLookupMeta ) {
        StreamLookupMeta l = (StreamLookupMeta) m;
        String claves = clavesLookup( l );
        if ( esValor( l, entra, sale ) ) {
          return entra.equals( sale ) ? rot.f( "op.busca", entra, claves ) : rot.f( "op.busca_como", entra, sale,
            claves );
        }
        return rot.f( "op.clave", claves );
      } else if ( UNE.equals( clase ) && m instanceof MergeJoinMeta ) {
        MergeJoinMeta mj = (MergeJoinMeta) m;
        return rot.f( "op.une", mj.getJoinType(), pares( mj.getKeyFields1(), mj.getKeyFields2() ) );
      } else if ( UNE.equals( clase ) && m instanceof MultiMergeJoinMeta ) {
        MultiMergeJoinMeta mj = (MultiMergeJoinMeta) m;
        String[] ks = mj.getKeyFields();
        return rot.f( "op.une", mj.getJoinType(), ks == null ? "" : SecLinaje.unir( Arrays.asList( ks ), " = " ) );
      } else if ( RENOMBRA.equals( clase ) ) {
        String conv = conversion( m, sale );
        return entra == null || entra.equals( sale ) ? conv : entra + " → " + sale + ( conv == null ? "" : ", " + conv );
      } else if ( CONVIERTE.equals( clase ) ) {
        return conversion( m, sale );
      }
    } catch ( Throwable ignored ) {
      // sin etiqueta
    }
    return null;
  }

  /** constante «1» de una funcion CONSTANT de Calculator (su field_a es el valor). */
  private String constante( CalculatorMetaFunction f ) {
    return rot.f( "op.constante", f.getFieldA() == null ? "" : f.getFieldA() );
  }

  /** Indice de la ultima funcion de Calculator antes de {@code antes} que calcula el campo, o -1. */
  private static int definicion( CalculatorMetaFunction[] fs, String campo, int antes ) {
    for ( int i = Math.min( antes, fs.length ) - 1; campo != null && i >= 0; i-- ) {
      if ( campo.equals( fs[ i ].getFieldName() ) ) {
        return i;
      }
    }
    return -1;
  }

  /**
   * La cuenta entera de una funcion de Calculator: las constantes con su valor y los temporales (los que
   * se quitan del resultado) desarmados, por ejemplo ADD(DIVIDE(SUBTRACT(mes, 1), 4), 1) en lugar de
   * ADD(cociente, c1). Un campo calculado que queda en la salida va por su nombre.
   */
  private static String expresion( CalculatorMetaFunction[] fs, int k, int prof ) {
    CalculatorMetaFunction f = fs[ k ];
    List<String> args = new ArrayList<>();
    for ( String a : noVacios( f.getFieldA(), f.getFieldB(), f.getFieldC() ) ) {
      int j = definicion( fs, a, k );
      if ( j >= 0 && fs[ j ].getCalcType() == CalculatorMetaFunction.CALC_CONSTANT ) {
        args.add( literal( fs[ j ].getFieldA() ) );
      } else if ( j >= 0 && fs[ j ].isRemovedFromResult() && prof < 6 ) {
        args.add( expresion( fs, j, prof + 1 ) );
      } else {
        args.add( a );
      }
    }
    return f.getCalcTypeDesc() + "(" + SecLinaje.unir( args, ", " ) + ")";
  }

  /** Un valor constante dentro de una cuenta: los numeros tal cual, el resto entre comillas simples. */
  private static String literal( String v ) {
    String x = v == null ? "" : v.trim();
    return x.matches( "[-+]?\\d+([.,]\\d+)?" ) ? x : "'" + x + "'";
  }

  /** "a String (#,##0.00)" para el cambio de metadatos de Select values sobre el campo; null si no hay. */
  private String conversion( StepMetaInterface m, String sale ) {
    if ( !( m instanceof SelectValuesMeta ) ) {
      return null;
    }
    SelectMetadataChange[] metas = ( (SelectValuesMeta) m ).getMeta();
    for ( int i = 0; metas != null && i < metas.length; i++ ) {
      SelectMetadataChange c = metas[ i ];
      String n = vacio( c.getRename() ) ? c.getName() : c.getRename();
      if ( sale.equals( n ) && convierte( c ) ) {
        String tipo = c.getType() == ValueMetaInterface.TYPE_NONE ? null
          : org.pentaho.di.core.row.value.ValueMetaFactory.getValueMetaName( c.getType() );
        String mascara = vacio( c.getConversionMask() ) ? null : c.getConversionMask();
        if ( tipo != null ) {
          return mascara == null ? rot.f( "op.convierte", tipo ) : rot.f( "op.convierte_mascara", tipo, mascara );
        }
        return mascara == null ? rot.s( "op.convierte_meta" ) : rot.f( "op.mascara", mascara );
      }
    }
    return null;
  }

  private static String clavesLookup( StreamLookupMeta l ) {
    return pares( l.getKeystream(), l.getKeylookup() );
  }

  private static String pares( String[] a, String[] b ) {
    List<String> l = new ArrayList<>();
    for ( int i = 0; a != null && i < a.length; i++ ) {
      String y = b != null && i < b.length ? b[ i ] : "?";
      l.add( a[ i ] + " = " + y );
    }
    return SecLinaje.unir( l, ", " );
  }

  // ---------------------------------------------------------------- utilidades

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  static String[] noVacios( String... xs ) {
    List<String> l = new ArrayList<>();
    for ( String x : xs ) {
      if ( !vacio( x ) ) {
        l.add( x );
      }
    }
    return l.toArray( new String[ 0 ] );
  }
}
