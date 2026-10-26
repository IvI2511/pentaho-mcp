package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.CategoriasStep;
import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.DescribirStep;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.NombresPorDefecto;
import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Rutas;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * Las reglas del perfil del proyecto o del curso (0.7, E5; {@link PerfilEquipo}): naming_convention (prefijo por
 * categoría de step o de entrada, con arreglo automático: renombrar, un Ctrl+Z), step_without_description,
 * required_variable (rutas y hosts con las variables del perfil) y team_pattern (las reglas propias del equipo, por
 * patrón). Apagadas por defecto: sin perfil no corren; el perfil las enciende cuando tiene su sección y entonces restan.
 */
public final class ReglasPerfil {

  /** Tope de lo que se le pasa a una regex del equipo, y de lo que puede tardar cada búsqueda. */
  static final int MAX_TEXTO = 200000;
  static final long MS_PATRON = 250;
  /** Referencias a otros ETL: son código, no datos (no piden la variable de datos). */
  static final Pattern ETL = Pattern.compile( "(?i).*\\.(ktr|kjb)$" );
  /** Carpetas de la JVM (%%java.io.tmpdir%% de Sort rows y Group by): no son datos del proyecto. */
  static final Pattern SISTEMA = Pattern.compile( "(\\$\\{|%%)(java|user)\\." );
  private static final Pattern ESQUEMA =Pattern.compile( "(?i)^(?!file:)[a-z][a-z0-9+.-]*://.*" );

  private ReglasPerfil() {
  }

  public static List<Regla> todas() {
    List<Regla> l = new ArrayList<Regla>();
    l.add( new Convencion() );
    l.add( new DescripcionDeStep() );
    l.add( new VariableObligatoria() );
    l.add( new PatronEquipo() );
    return l;
  }

  static PerfilEquipo perfil( Ctx ctx ) {
    return ctx.opciones().perfil;
  }

  /** La primera copia de cada entrada (las demás comparten nombre y descripción). */
  static List<JobEntryCopy> entradas( Ctx ctx ) {
    List<JobEntryCopy> l = new ArrayList<JobEntryCopy>();
    Map<Object, Boolean> vistas = new IdentityHashMap<Object, Boolean>();
    for ( JobEntryCopy c : ctx.job().getJobCopies() ) {
      Object e = c.getEntry() == null ? c : c.getEntry();
      if ( vistas.put( e, Boolean.TRUE ) == null ) {
        l.add( c );
      }
    }
    return l;
  }

  /** Las variables de host, puerto y base de una conexión (ver VariableObligatoria.variablesConexion). */
  public static String[] variablesConexion( String base, String conexion ) {
    return VariableObligatoria.variablesConexion( base, conexion );
  }

  /** El XML de un step para describirlo; null si no se puede leer (la descripción cae a la genérica). */
  static Element xmlOnull( Ctx ctx, StepMeta s ) {
    try {
      return ctx.xml( s );
    } catch ( Exception e ) {
      return null;
    }
  }

  static Element xmlOnull( Ctx ctx, JobEntryCopy c ) {
    try {
      return ctx.xml( c );
    } catch ( Exception e ) {
      return null;
    }
  }

  /** El nombre del tipo de step como lo muestra Spoon («Text file input»); el id si no está registrado. */
  static String nombreTipo( StepMeta s ) {
    try {
      org.pentaho.di.core.plugins.PluginInterface pi = org.pentaho.di.core.plugins.PluginRegistry.getInstance()
        .findPluginWithId( org.pentaho.di.core.plugins.StepPluginType.class, s.getStepID() );
      return pi == null ? s.getStepID() : pi.getName();
    } catch ( Throwable t ) {
      return s.getStepID();
    }
  }

  /** El host usa una de las variables del perfil, sola o con sufijo por conexión (${DB_HOST} o ${DB_HOST_VENTAS}). */
  static boolean usaHost( String host, List<String> vars ) {
    for ( String x : vars ) {
      if ( Pattern.compile( "(\\$\\{|%%)" + Pattern.quote( x ) + "(_[A-Za-z0-9_]+)?(\\}|%%)" ).matcher( host ).find() ) {
        return true;
      }
    }
    return false;
  }

  // ================================================================ naming_convention

  static final class Convencion extends ReglaBase {

    Convencion() {
      super( PerfilEquipo.NAMING, Sev.INFO, Regla.STYLE );
      ambos();
      apagadaPorDefecto();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      PerfilEquipo p = perfil( ctx );
      if ( p == null ) {
        return;
      }
      if ( ctx.esTrans() ) {
        // un nombre por defecto ya lo marca default_step_name, y su nombre propuesto lleva el prefijo
        boolean porDefecto = ctx.corre( "default_step_name" );
        for ( StepMeta s : ctx.trans().getSteps() ) {
          String pre = p.prefijoStep( s.getStepID() );
          String n = s.getName();
          if ( pre == null || n == null || n.startsWith( pre ) || ( porDefecto && NombresPorDefecto.esDeStep( n ) ) ) {
            continue;
          }
          Hallazgo h = ctx.enStep( Sev.INFO, s, "name" );
          String cat = categoria( CategoriasStep.deStep( s.getStepID() ), p.prefijosStepTipo.containsKey( s
            .getStepID() ) );
          h.message = ctx.t( "naming_convention.msg", "step", n, "prefix", pre, "category", cat );
          h.evidencia( "prefix", pre ).evidencia( "category", cat );
          h.fix = Arreglo.auto( Arreglo.RENAME ).vista( p.conPrefijo( n, pre, true ) ).param( "type", s.getStepID() );
          h.fix.confirm = ReglasEstilo.NOMBRADOS_DE_AFUERA.contains( s.getStepID() );
          out.add( h );
        }
        return;
      }
      boolean porDefecto = ctx.corre( "default_entry_name" );
      for ( JobEntryCopy c : entradas( ctx ) ) {
        String pid = ReglasJob.pid( c );
        if ( c.isStart() || ReglasEstilo.ENTRADAS_SIN_NOMBRE.contains( pid ) ) {
          continue;
        }
        String pre = p.prefijoEntrada( pid );
        String n = c.getName();
        if ( pre == null || n == null || n.startsWith( pre ) || ( porDefecto && NombresPorDefecto.esDeEntrada( n ) ) ) {
          continue;
        }
        Hallazgo h = ctx.enEntrada( Sev.INFO, c, "name" );
        String cat = categoria( CategoriasStep.deEntrada( pid ), p.prefijosEntradaTipo.containsKey( pid ) );
        h.message = ctx.t( "naming_convention.msg.entry", "entry", n, "prefix", pre, "category", cat );
        h.evidencia( "prefix", pre ).evidencia( "category", cat );
        h.fix = Arreglo.auto( Arreglo.RENAME_ENTRY ).vista( p.conPrefijo( n, pre, false ) ).param( "type", pid );
        out.add( h );
      }
    }

    private static String categoria( String cat, boolean porTipo ) {
      return porTipo ? "by_type" : cat == null ? CategoriasStep.OTRO : cat;
    }
  }

  // ================================================================ step_without_description

  static final class DescripcionDeStep extends ReglaBase {

    DescripcionDeStep() {
      super( PerfilEquipo.STEP_DESCRIPTION, Sev.INFO, Regla.STYLE );
      ambos();
      apagadaPorDefecto();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      PerfilEquipo p = perfil( ctx );
      if ( p == null || !p.descripcionObligatoria ) {
        return;
      }
      if ( ctx.esTrans() ) {
        for ( StepMeta s : ctx.trans().getSteps() ) {
          if ( !p.descripcionExcepto.contains( s.getStepID() ) && ReglasEstilo.vacio( s.getDescription() ) ) {
            Hallazgo h = ctx.enStep( Sev.INFO, s, "description" );
            h.message = ctx.t( "step_without_description.msg", "step", s.getName() );
            String d = DescribirStep.step( ctx, s.getStepID(), xmlOnull( ctx, s ), nombreTipo( s ) );
            h.fix = Arreglo.conConfirmacion( Arreglo.SET_STEP_DESCRIPTION ).vista( d ).param( "type", s.getStepID() );
            out.add( h );
          }
        }
        return;
      }
      for ( JobEntryCopy c : entradas( ctx ) ) {
        String pid = ReglasJob.pid( c );
        if ( c.isStart() || ReglasEstilo.ENTRADAS_SIN_NOMBRE.contains( pid ) || p.descripcionExcepto.contains( pid ) ) {
          continue;
        }
        if ( ReglasEstilo.vacio( c.getDescription() ) ) {
          Hallazgo h = ctx.enEntrada( Sev.INFO, c, "description" );
          h.message = ctx.t( "step_without_description.msg.entry", "entry", c.getName() );
          String d = DescribirStep.entrada( ctx, pid, xmlOnull( ctx, c ), c.getEntry() == null ? pid : c.getEntry()
            .getPluginId() );
          h.fix = Arreglo.conConfirmacion( Arreglo.SET_STEP_DESCRIPTION ).vista( d ).param( "type", pid );
          out.add( h );
        }
      }
    }
  }

  // ================================================================ required_variable

  static final class VariableObligatoria extends ReglaBase {

    VariableObligatoria() {
      super( PerfilEquipo.REQUIRED_VARIABLE, Sev.WARNING, Regla.PORTABILITY );
      ambos();
      apagadaPorDefecto();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      PerfilEquipo p = perfil( ctx );
      if ( p == null ) {
        return;
      }
      if ( !p.variablesRuta.isEmpty() ) {
        if ( ctx.esTrans() ) {
          for ( StepMeta s : ctx.trans().getSteps() ) {
            for ( Rutas.Ruta r : Rutas.de( ctx.xml( s ) ) ) {
              ruta( ctx, p, r, s, null, out );
            }
          }
        } else {
          for ( JobEntryCopy c : entradas( ctx ) ) {
            for ( Rutas.Ruta r : Rutas.de( ctx.xml( c ) ) ) {
              ruta( ctx, p, r, null, c, out );
            }
          }
        }
      }
      if ( !p.variablesHost.isEmpty() ) {
        for ( DatabaseMeta db : ctx.meta().getDatabases() ) {
          String host = db.getHostname();
          if ( host == null || host.trim().isEmpty() || usaHost( host, p.variablesHost ) ) {
            continue;
          }
          Hallazgo h = ctx.enObjeto( Sev.WARNING, Hallazgo.CONNECTION, db.getName(), "hostname" );
          String v = "${" + p.variablesHost.get( 0 ) + "}";
          h.message = ctx.t( "required_variable.msg.host", "connection", db.getName(), "value", host.trim(), "variable",
            v );
          h.howToFix = ctx.t( "required_variable.fix.host", "variable", v, "value", host.trim() );
          h.evidencia( "value", host.trim() ).evidencia( "variable", v );
          if ( !db.isShared() ) {
            // host, puerto y base a variables del perfil por conexión: ${DB_HOST_VENTAS}, ${DB_PORT_VENTAS}...
            String[] vars = variablesConexion( p.variablesHost.get( 0 ), db.getName() );
            String hv = "${" + vars[ 0 ] + "}";
            String pv = esVariable( db.getDatabasePortNumberString() ) ? db.getDatabasePortNumberString() : "${" + vars[ 1 ]
              + "}";
            String dv = esVariable( db.getDatabaseName() ) ? db.getDatabaseName() : "${" + vars[ 2 ] + "}";
            StringBuilder lineas = new StringBuilder();
            lineas.append( vars[ 0 ] ).append( '=' ).append( host.trim() );
            if ( !esVariable( db.getDatabasePortNumberString() ) ) {
              lineas.append( '\n' ).append( vars[ 1 ] ).append( '=' ).append( nn( db.getDatabasePortNumberString() ) );
            }
            if ( !esVariable( db.getDatabaseName() ) ) {
              lineas.append( '\n' ).append( vars[ 2 ] ).append( '=' ).append( nn( db.getDatabaseName() ) );
            }
            h.fix = Arreglo.conConfirmacion( Arreglo.USE_CONNECTION_VARIABLES ).vista( hv + " · " + pv + " · " + dv )
              .param( "host", hv ).param( "port", pv ).param( "database", dv ).param( "properties", lineas );
            h.howToFix = ctx.t( "required_variable.fix.host.auto", "host", hv, "port", pv, "database", dv );
          }
          out.add( h );
        }
      }
    }

    private static void ruta( Ctx ctx, PerfilEquipo p, Rutas.Ruta r, StepMeta s, JobEntryCopy c, List<Hallazgo> out ) {
      String v = r.valor;
      if ( ETL.matcher( v ).matches() || ESQUEMA.matcher( v ).matches() || usa( v, p.variablesRuta, true )
        || SISTEMA.matcher( v ).lookingAt() ) {
        return;
      }
      String var = p.variablesRuta.get( 0 );
      Hallazgo h = s != null ? ctx.enStep( Sev.WARNING, s, r.tag ) : ctx.enEntrada( Sev.WARNING, c, r.tag );
      h.n = r.n;
      String donde = s != null ? s.getName() : c.getName();
      h.message = ctx.t( "required_variable.msg", "where", donde, "value", v, "variable", "${" + var + "}" );
      h.evidencia( "tag", r.tag ).evidencia( "value", v ).evidencia( "variable", "${" + var + "}" );
      if ( Rutas.absoluta( v ) ) {
        String[] pr = Rutas.propuestaConVariable( v, var );
        h.fix = Arreglo.conConfirmacion( Arreglo.REPLACE_VALUE ).vista( pr[ 0 ] ).param( "tag", r.tag ).param(
          "parameter", var ).param( "default", pr[ 1 ] );
        h.howToFix = ctx.t( "required_variable.fix.parameter", "variable", "${" + var + "}", "default", pr[ 1 ] );
      } else {
        h.fix = Arreglo.asistido( Arreglo.REPLACE_VALUE ).param( "tag", r.tag );
      }
      out.add( h );
    }

    /**
     * Las variables de una conexión a partir de la del perfil: DB_HOST y la conexión «ventas dw» dan DB_HOST_VENTAS_DW,
     * DB_PORT_VENTAS_DW y DB_NAME_VENTAS_DW (sin HOST en el nombre: X_VENTAS_DW, X_PORT_VENTAS_DW, X_NAME_VENTAS_DW).
     */
    static String[] variablesConexion( String base, String conexion ) {
      String c = conexion == null ? "" : java.text.Normalizer.normalize( conexion, java.text.Normalizer.Form.NFD )
        .replaceAll( "\\p{M}", "" ).toUpperCase( Locale.ROOT ).replaceAll( "[^A-Z0-9]+", "_" ).replaceAll( "^_+|_+$",
          "" );
      String suf = c.isEmpty() ? "" : "_" + c;
      String b = base.toUpperCase( Locale.ROOT );
      int i = b.lastIndexOf( "HOST" );
      String puerto = i >= 0 ? base.substring( 0, i ) + "PORT" + base.substring( i + 4 ) : base + "_PORT";
      String nombre = i >= 0 ? base.substring( 0, i ) + "NAME" + base.substring( i + 4 ) : base + "_NAME";
      return new String[] { base + suf, puerto + suf, nombre + suf };
    }

    static boolean esVariable( String v ) {
      return v != null && ( v.contains( "${" ) || v.contains( "%%" ) );
    }

    private static String nn( String v ) {
      return v == null ? "" : v.trim();
    }

    /** El valor empieza con una de las variables (o es solo esa variable); con alPrincipio=false, la usa en algún lado. */
    static boolean usa( String valor, List<String> vars, boolean alPrincipio ) {
      String v = valor.trim();
      for ( String x : vars ) {
        for ( String forma : Arrays.asList( "${" + x + "}", "%%" + x + "%%" ) ) {
          if ( alPrincipio ? v.startsWith( forma ) : v.contains( forma ) ) {
            return true;
          }
        }
      }
      return false;
    }
  }

  // ================================================================ team_pattern

  static final class PatronEquipo extends ReglaBase {

    PatronEquipo() {
      super( PerfilEquipo.TEAM_PATTERN, Sev.ERROR, Regla.STYLE );
      ambos();
      apagadaPorDefecto();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      PerfilEquipo p = perfil( ctx );
      if ( p == null ) {
        return;
      }
      for ( PerfilEquipo.ReglaEquipo r : p.equipo ) {
        if ( ctx.esTrans() ) {
          for ( StepMeta s : ctx.trans().getSteps() ) {
            if ( r.tipos.isEmpty() || r.tipos.contains( s.getStepID() ) ) {
              mirar( ctx, r, textos( ctx, r, s, null ), s, null, out );
            }
          }
        } else {
          for ( JobEntryCopy c : entradas( ctx ) ) {
            if ( c.isStart() || ReglasJob.pid( c ) == null ) {
              continue;
            }
            if ( r.tipos.isEmpty() || r.tipos.contains( ReglasJob.pid( c ) ) ) {
              mirar( ctx, r, textos( ctx, r, null, c ), null, c, out );
            }
          }
        }
      }
    }

    /** Lo que mira la regla en el step o la entrada, según where. */
    private static List<String> textos( Ctx ctx, PerfilEquipo.ReglaEquipo r, StepMeta s, JobEntryCopy c )
      throws Exception {
      String d = r.donde;
      if ( "name".equals( d ) ) {
        return Collections.singletonList( s != null ? s.getName() : c.getName() );
      }
      if ( "description".equals( d ) ) {
        String x = s != null ? s.getDescription() : c.getDescription();
        return Collections.singletonList( x == null ? "" : x );
      }
      Element raiz = s != null ? ctx.xml( s ) : ctx.xml( c );
      if ( "path".equals( d ) ) {
        List<String> l = new ArrayList<String>();
        for ( Rutas.Ruta x : Rutas.de( raiz ) ) {
          l.add( x.valor );
        }
        return l;
      }
      if ( "sql".equals( d ) ) {
        List<String> l = new ArrayList<String>();
        for ( Element[] ep : Nodos.hojas( raiz ) ) {
          if ( ep[ 0 ].getTagName().toLowerCase( Locale.ROOT ).equals( "sql" ) ) {
            l.add( ep[ 0 ].getTextContent() );
          }
        }
        return l;
      }
      return Collections.singletonList( s != null ? s.getXML() : c.getXML() );
    }

    private static void mirar( Ctx ctx, PerfilEquipo.ReglaEquipo r, List<String> textos, StepMeta s, JobEntryCopy c,
                               List<Hallazgo> out ) {
      String hallado = null;
      boolean aparece = false;
      for ( String t : textos ) {
        String x = t.length() > MAX_TEXTO ? t.substring( 0, MAX_TEXTO ) : t;
        Matcher m = r.patron.matcher( new Plazo( x, System.nanoTime() + MS_PATRON * 1000000L ) );
        try {
          if ( m.find() ) {
            aparece = true;
            hallado = m.group();
            break;
          }
        } catch ( Plazo.Vencido e ) {
          ctx.noEvaluado( "timeout", s != null ? s.getName() : null, c != null ? c.getName() : null, ctx.t(
            "team_pattern.timeout", "rule", r.id ) );
          return;
        }
      }
      if ( aparece == r.debeAparecer ) {
        return;
      }
      String donde = s != null ? s.getName() : c.getName();
      Hallazgo h = s != null ? ctx.enStep( r.sev, s, r.id ) : ctx.enEntrada( r.sev, c, r.id );
      String corto = hallado == null ? "" : hallado.length() > 80 ? hallado.substring( 0, 80 ) + "…" : hallado;
      if ( r.mensaje != null ) {
        h.message = r.mensaje.replace( "{where}", donde ).replace( "{step}", donde ).replace( "{entry}", donde )
          .replace( "{match}", corto );
      } else {
        h.message = ctx.t( r.debeAparecer ? "team_pattern.msg.missing" : "team_pattern.msg", "where", donde, "rule",
          r.titulo, "match", corto );
      }
      h.howToFix = r.arreglo != null ? r.arreglo : ctx.t( "team_pattern.fix", "rule", r.titulo );
      h.evidencia( "team_rule", r.id ).evidencia( "title", r.titulo ).evidencia( "where", r.donde );
      if ( hallado != null ) {
        h.evidencia( "match", corto );
      }
      out.add( h );
    }
  }

  /**
   * Un texto para una regex del equipo que corta la búsqueda cuando se vence su plazo: una expresión con backtracking
   * catastrófico no puede colgar el linter (la regex es del archivo del perfil, que es dato).
   */
  static final class Plazo implements CharSequence {

    static final class Vencido extends RuntimeException {
      private static final long serialVersionUID = 1L;
    }

    private final CharSequence s;
    private final long fin;
    private int cuenta;

    Plazo( CharSequence s, long fin ) {
      this.s = s;
      this.fin = fin;
    }

    @Override
    public char charAt( int i ) {
      if ( ( ++cuenta & 0x3FF ) == 0 && System.nanoTime() > fin ) {
        throw new Vencido();
      }
      return s.charAt( i );
    }

    @Override
    public int length() {
      return s.length();
    }

    @Override
    public CharSequence subSequence( int a, int b ) {
      return new Plazo( s.subSequence( a, b ), fin );
    }

    @Override
    public String toString() {
      return s.toString();
    }
  }
}
