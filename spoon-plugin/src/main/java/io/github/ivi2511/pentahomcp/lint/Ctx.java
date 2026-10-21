package io.github.ivi2511.pentahomcp.lint;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

/**
 * Lo que comparten las reglas durante un análisis: el documento (una copia desligada), el XML parseado de cada
 * step y entrada, las entradas de cada step y los campos (por el Entorno). Todo se memoiza: los campos se piden
 * una vez por step y, si uno se pasa del timeout, no se piden más (el hilo que espera a la BD sigue vivo).
 */
public final class Ctx {

  /** No se pudieron calcular los campos de un step; el mensaje ya está traducido. */
  public static final class SinCampos extends Exception {
    private static final long serialVersionUID = 1L;
    public final String code;

    SinCampos( String code, String mensaje ) {
      super( mensaje );
      this.code = code;
    }
  }

  private final AbstractMeta meta;
  private final TransMeta tm;
  private final JobMeta jm;
  private final Opciones op;
  private final Entorno env;
  private final Textos tx;
  private final Map<Object, Element> xml = new IdentityHashMap<Object, Element>();
  private final Map<StepMeta, List<StepMeta>> previos = new IdentityHashMap<StepMeta, List<StepMeta>>();
  private final Map<StepMeta, Object> entradas = new IdentityHashMap<StepMeta, Object>();
  private final Map<StepMeta, Object> salidas = new IdentityHashMap<StepMeta, Object>();
  private final List<NoEvaluado> noEvaluados = new ArrayList<NoEvaluado>();
  private SinCampos camposCaidos;
  private Object crudo;
  private Regla actual;
  boolean ciclo;

  Ctx( AbstractMeta meta, Opciones op ) {
    this.meta = meta;
    this.tm = meta instanceof TransMeta ? (TransMeta) meta : null;
    this.jm = meta instanceof JobMeta ? (JobMeta) meta : null;
    this.op = op;
    this.env = op.entorno;
    this.tx = Textos.de( op.lang );
  }

  public AbstractMeta meta() {
    return meta;
  }

  public TransMeta trans() {
    return tm;
  }

  public JobMeta job() {
    return jm;
  }

  public boolean esTrans() {
    return tm != null;
  }

  public Opciones opciones() {
    return op;
  }

  public Entorno entorno() {
    return env;
  }

  public Textos textos() {
    return tx;
  }

  public String t( String clave, Object... pares ) {
    return tx.t( clave, pares );
  }

  /** Si la transformación tiene un ciclo (lo deja la regla cycle, que corre primero). */
  public boolean hayCiclo() {
    return ciclo;
  }

  void regla( Regla r ) {
    actual = r;
  }

  List<NoEvaluado> noEvaluados() {
    return noEvaluados;
  }

  // ---------------------------------------------------------------- XML y grafo

  public Element xml( StepMeta s ) throws Exception {
    Element e = xml.get( s );
    if ( e == null ) {
      e = Nodos.parse( s.getXML() );
      xml.put( s, e );
    }
    return e;
  }

  public Element xml( JobEntryCopy c ) throws Exception {
    Element e = xml.get( c );
    if ( e == null ) {
      e = Nodos.parse( c.getXML() );
      xml.put( c, e );
    }
    return e;
  }

  /** Las entradas principales del step (sin info streams), por hops habilitados. */
  public List<StepMeta> previos( StepMeta s ) {
    List<StepMeta> l = previos.get( s );
    if ( l == null ) {
      l = tm.findPreviousSteps( s, false );
      previos.put( s, l );
    }
    return l;
  }

  // ---------------------------------------------------------------- campos

  public RowMetaInterface entrada( StepMeta s ) throws SinCampos {
    return campos( s, true );
  }

  public RowMetaInterface salida( StepMeta s ) throws SinCampos {
    return campos( s, false );
  }

  private RowMetaInterface campos( StepMeta s, boolean entrada ) throws SinCampos {
    Map<StepMeta, Object> memo = entrada ? entradas : salidas;
    Object m = memo.get( s );
    if ( m instanceof RowMetaInterface ) {
      return (RowMetaInterface) m;
    }
    if ( m instanceof SinCampos ) {
      throw (SinCampos) m;
    }
    if ( ciclo ) {
      throw new SinCampos( "cycle", t( "ne.cycle" ) );
    }
    if ( camposCaidos != null ) {
      throw camposCaidos;
    }
    if ( env.cancelado() || env.msRestantes() <= 0 ) {
      throw new SinCampos( "cancelled", t( "ne.cancelled" ) );
    }
    try {
      RowMetaInterface rm = entrada ? env.entrada( s ) : env.salida( s );
      if ( rm == null ) {
        throw new SinCampos( "fields", t( "ne.fields", "detail", "null" ) );
      }
      memo.put( s, rm );
      return rm;
    } catch ( SinCampos e ) {
      memo.put( s, e );
      throw e;
    } catch ( TiempoAgotado e ) {
      camposCaidos = new SinCampos( "fields", t( "ne.fields_timeout", "detail", primeraLinea( e ) ) );
      memo.put( s, camposCaidos );
      throw camposCaidos;
    } catch ( BaseFaltante e ) { // no se calculan: Kettle crearía vacía la base de archivo que falta
      SinCampos x = new SinCampos( "fields", t( "ne.fields_db_missing", "connection", e.conexion, "file", e.archivo ) );
      memo.put( s, x );
      throw x;
    } catch ( Throwable e ) { // incluye StackOverflowError: getPrevStepFields con un ciclo
      SinCampos x = new SinCampos( "fields", t( "ne.fields", "detail", primeraLinea( e ) ) );
      memo.put( s, x );
      throw x;
    }
  }

  // ---------------------------------------------------------------- archivo

  /** Si las reglas de modo archivo pueden leer el disco: el documento tiene archivo y no tiene cambios sin guardar. */
  public boolean archivoVigente() {
    return meta.getFilename() != null && !meta.getFilename().trim().isEmpty() && !op.sinGuardar;
  }

  /** El archivo del disco; null (y un no evaluado con el motivo) si no se puede usar. */
  public ArchivoCrudo crudo() {
    if ( crudo == null ) {
      if ( meta.getFilename() == null || meta.getFilename().trim().isEmpty() ) {
        crudo = new NoEvaluado[] { ne( "no_file", t( "ne.no_file" ) ) };
      } else if ( op.sinGuardar ) {
        crudo = new NoEvaluado[] { ne( "unsaved", t( "ne.unsaved" ) ) };
      } else {
        try {
          crudo = ArchivoCrudo.leer( meta );
        } catch ( Throwable e ) {
          crudo = new NoEvaluado[] { ne( "no_file", t( "ne.read_failed", "detail", primeraLinea( e ) ) ) };
        }
      }
    }
    if ( crudo instanceof ArchivoCrudo ) {
      return (ArchivoCrudo) crudo;
    }
    NoEvaluado base = ( (NoEvaluado[]) crudo )[ 0 ];
    noEvaluado( base.code, null, null, base.reason );
    return null;
  }

  private NoEvaluado ne( String code, String reason ) {
    NoEvaluado n = new NoEvaluado();
    n.code = code;
    n.reason = reason;
    return n;
  }

  // ---------------------------------------------------------------- hallazgos

  public Hallazgo nuevo( Sev sev, String ambito ) {
    Hallazgo h = new Hallazgo();
    h.rule = actual.id();
    h.category = actual.categoria();
    h.env = actual.env();
    h.severity = sev;
    h.scope = ambito;
    return h;
  }

  public Hallazgo enStep( Sev sev, StepMeta s, String atributo ) {
    Hallazgo h = nuevo( sev, Hallazgo.STEP );
    h.step = s.getName();
    h.attribute = atributo;
    return h;
  }

  /** Para lo que solo se ve en el archivo (el step ya no existe en memoria con ese nombre). */
  public Hallazgo enStep( Sev sev, String nombre, String atributo ) {
    Hallazgo h = nuevo( sev, Hallazgo.STEP );
    h.step = nombre;
    h.attribute = atributo;
    return h;
  }

  public Hallazgo enEntrada( Sev sev, JobEntryCopy c, String atributo ) {
    Hallazgo h = nuevo( sev, Hallazgo.ENTRY );
    h.entry = c.getName();
    h.entryNr = c.getNr();
    h.attribute = atributo;
    return h;
  }

  public Hallazgo enDoc( Sev sev, String atributo ) {
    Hallazgo h = nuevo( sev, Hallazgo.DOC );
    h.attribute = atributo;
    return h;
  }

  /** Hop de transformación: se silencia en el step de origen. */
  public Hallazgo enHop( Sev sev, StepMeta desde, StepMeta hasta, String atributo ) {
    Hallazgo h = nuevo( sev, Hallazgo.HOP );
    h.step = desde.getName();
    h.object = desde.getName() + " -> " + hasta.getName();
    h.attribute = atributo;
    return h;
  }

  /** Hop de job: se silencia en la entrada de origen. */
  public Hallazgo enHop( Sev sev, JobEntryCopy desde, JobEntryCopy hasta, String atributo ) {
    Hallazgo h = nuevo( sev, Hallazgo.HOP );
    h.entry = desde.getName();
    h.entryNr = desde.getNr();
    h.object = desde.getName() + " -> " + hasta.getName();
    h.attribute = atributo;
    return h;
  }

  public Hallazgo enObjeto( Sev sev, String ambito, String objeto, String atributo ) {
    Hallazgo h = nuevo( sev, ambito );
    h.object = objeto;
    h.attribute = atributo;
    return h;
  }

  /** La regla actual no pudo evaluar este step/entrada (o nada, si los dos son null). */
  public void noEvaluado( String code, String step, String entry, String motivo ) {
    NoEvaluado n = new NoEvaluado();
    n.rule = actual == null ? null : actual.id();
    n.step = step;
    n.entry = entry;
    n.code = code;
    n.reason = motivo;
    noEvaluados.add( n );
  }

  public void noEvaluado( SinCampos e, StepMeta s ) {
    noEvaluado( e.code, s == null ? null : s.getName(), null, e.getMessage() );
  }

  static String primeraLinea( Throwable e ) {
    String m = e.getMessage();
    if ( m == null || m.trim().isEmpty() ) {
      return e.getClass().getSimpleName();
    }
    for ( String l : m.split( "\\r?\\n" ) ) {
      if ( !l.trim().isEmpty() ) {
        return l.trim();
      }
    }
    return e.getClass().getSimpleName();
  }
}
