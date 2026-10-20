package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.NombresPorDefecto;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * Estilo y documentación (§2): default_step_name, default_entry_name, no_description, select_star,
 * undeclared_variable (C14, depende de la máquina) y parameter_without_default.
 */
public final class ReglasEstilo {

  static final Pattern SELECT_ESTRELLA = Pattern.compile( "(?is)\\bselect\\s+(distinct\\s+)?(\\w+\\.)?\\*" );
  static final Pattern CREDENCIAL = Pattern.compile( "(?i).*(pass|pwd|user|token|secret|key).*" );
  /** Entradas cuyo nombre por defecto ya explica todo (y START, que tiene que llamarse así). */
  static final Set<String> ENTRADAS_SIN_NOMBRE = new HashSet<String>( Arrays.asList( "SUCCESS", "ABORT", "DUMMY", "SPECIAL" ) );
  /** Los nombra otro archivo (el que llama al mapping): renombrar pide confirmación. */
  static final Set<String> NOMBRADOS_DE_AFUERA = new HashSet<String>( Arrays.asList( "MappingInput", "MappingOutput" ) );

  private ReglasEstilo() {
  }

  public static List<Regla> todas() {
    List<Regla> l = new ArrayList<Regla>();
    l.add( new NombreDeStepPorDefecto() );
    l.add( new NombreDeEntradaPorDefecto() );
    l.add( new SinDescripcion() );
    l.add( new SelectEstrella() );
    l.add( new VariableNoDeclarada() );
    l.add( new ParametroSinDefault() );
    return l;
  }

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  static final class NombreDeStepPorDefecto extends ReglaBase {

    NombreDeStepPorDefecto() {
      super( "default_step_name", Sev.INFO, Regla.STYLE );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( NombresPorDefecto.esDeStep( s.getName() ) ) {
          Hallazgo h = ctx.enStep( Sev.INFO, s, "name" );
          h.message = ctx.t( "default_step_name.msg", "step", s.getName() );
          // el nombre lo propone LintPropuestas (WP5) o Claude: hasta entonces falta el valor
          h.fix = Arreglo.auto( Arreglo.RENAME ).param( "type", s.getStepID() );
          h.fix.needsValue = true;
          h.fix.confirm = NOMBRADOS_DE_AFUERA.contains( s.getStepID() );
          out.add( h );
        }
      }
    }
  }

  static final class NombreDeEntradaPorDefecto extends ReglaBase {

    NombreDeEntradaPorDefecto() {
      super( "default_entry_name", Sev.INFO, Regla.STYLE );
      soloJobs();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      for ( JobEntryCopy c : ctx.job().getJobCopies() ) {
        if ( c.isStart() || ENTRADAS_SIN_NOMBRE.contains( ReglasJob.pid( c ) ) ) {
          continue;
        }
        if ( NombresPorDefecto.esDeEntrada( c.getName() ) ) {
          Hallazgo h = ctx.enEntrada( Sev.INFO, c, "name" );
          h.message = ctx.t( "default_entry_name.msg", "entry", c.getName() );
          h.fix = Arreglo.auto( Arreglo.RENAME_ENTRY ).param( "type", ReglasJob.pid( c ) );
          h.fix.needsValue = true;
          out.add( h );
        }
      }
    }
  }

  static final class SinDescripcion extends ReglaBase {

    SinDescripcion() {
      super( "no_description", Sev.INFO, Regla.STYLE );
      ambos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      AbstractMeta m = ctx.meta();
      if ( vacio( m.getDescription() ) && vacio( m.getExtendedDescription() ) && m.nrNotes() == 0 ) {
        Hallazgo h = ctx.enDoc( Sev.INFO, "description" );
        h.message = ctx.t( "no_description.msg" );
        h.fix = Arreglo.asistido( Arreglo.SET_DESCRIPTION ).sinDeshacer();
        out.add( h );
      }
    }
  }

  static final class SelectEstrella extends ReglaBase {

    SelectEstrella() {
      super( "select_star", Sev.INFO, Regla.STYLE );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( "TableInput".equals( s.getStepID() ) && SELECT_ESTRELLA.matcher( Nodos.txt( ctx.xml( s ), "sql" ) ).find() ) {
          Hallazgo h = ctx.enStep( Sev.INFO, s, "sql" );
          h.message = ctx.t( "select_star.msg", "step", s.getName() );
          out.add( h );
        }
      }
    }
  }

  /** ${X} usada que no es parámetro, ni Internal.*, ni está definida (kettle.properties, Spoon, la JVM). */
  static final class VariableNoDeclarada extends ReglaBase {

    VariableNoDeclarada() {
      super( "undeclared_variable", Sev.INFO, Regla.PORTABILITY );
      dependeDelEntorno();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      AbstractMeta m = ctx.meta();
      Set<String> params = new HashSet<String>( Arrays.asList( m.listParameters() ) );
      Map<String, List<String>> usadas = new LinkedHashMap<String, List<String>>();
      for ( StepMeta s : ctx.trans().getSteps() ) {
        Matcher mt = ReglasCalculo.VARIABLE.matcher( s.getXML() );
        while ( mt.find() ) {
          String v = mt.group( 1 ) != null ? mt.group( 1 ) : mt.group( 2 );
          List<String> l = usadas.get( v );
          if ( l == null ) {
            l = new ArrayList<String>();
            usadas.put( v, l );
          }
          if ( !l.contains( s.getName() ) ) {
            l.add( s.getName() );
          }
        }
      }
      for ( Map.Entry<String, List<String>> e : usadas.entrySet() ) {
        String v = e.getKey();
        if ( v.startsWith( "Internal." ) || params.contains( v ) || m.getVariable( v ) != null
          || System.getProperty( v ) != null || System.getenv( v ) != null ) {
          continue;
        }
        Hallazgo h = ctx.enDoc( Sev.INFO, v );
        h.object = v;
        h.message = ctx.t( "undeclared_variable.msg", "variable", v, "steps", ReglasOrden.unir( primeros( e.getValue(), 5 ) ) );
        h.evidencia( "steps", ReglasOrden.unir( primeros( e.getValue(), 5 ) ) );
        h.fix = Arreglo.asistido( Arreglo.ADD_PARAMETER ).param( "parameter", v ).sinDeshacer();
        out.add( h );
      }
    }

    private static List<String> primeros( List<String> l, int n ) {
      return l.size() <= n ? l : l.subList( 0, n );
    }
  }

  /** Parámetro sin default (con Pan queda vacío). Las credenciales no cuentan: no deben tener default. */
  static final class ParametroSinDefault extends ReglaBase {

    ParametroSinDefault() {
      super( "parameter_without_default", Sev.INFO, Regla.STYLE );
      ambos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      AbstractMeta m = ctx.meta();
      for ( String p : m.listParameters() ) {
        if ( vacio( m.getParameterDefault( p ) ) && !CREDENCIAL.matcher( p ).matches() ) {
          Hallazgo h = ctx.enDoc( Sev.INFO, p );
          h.object = p;
          h.message = ctx.t( "parameter_without_default.msg", "parameter", p );
          out.add( h );
        }
      }
    }
  }
}
