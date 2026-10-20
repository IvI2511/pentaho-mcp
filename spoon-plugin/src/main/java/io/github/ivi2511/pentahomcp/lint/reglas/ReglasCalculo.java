package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Ctx.SinCampos;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * Correctitud de valores (§2): calculator_integer_first (D4), empty_decimal_symbol (D3), variable_in_constant (D9)
 * y text_output_without_fields (D8).
 */
public final class ReglasCalculo {

  static final Pattern VARIABLE = Pattern.compile( "\\$\\{([^}]+)\\}|%%([A-Za-z0-9_.]+)%%" );
  /** Entradas de texto con <fields><field><decimal>. */
  static final Set<String> ENTRADAS_TEXTO = new HashSet<String>( Arrays.asList(
    "CsvInput", "TextFileInput", "OldTextFileInput", "FixedInput", "ParallelGzipCsvInput" ) );
  static final Set<String> SALIDAS_TEXTO = new HashSet<String>( Arrays.asList( "TextFileOutput", "TextFileOutputLegacy" ) );
  private static final Set<String> ARITMETICA = new HashSet<String>( Arrays.asList( "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE" ) );

  private ReglasCalculo() {
  }

  public static List<Regla> todas() {
    List<Regla> l = new ArrayList<Regla>();
    l.add( new CalculadoraEnteroPrimero() );
    l.add( new DecimalVacio() );
    l.add( new VariableEnConstante() );
    l.add( new SalidaSinCampos() );
    return l;
  }

  static boolean numerico( String tipo ) {
    return "Number".equals( tipo ) || "BigNumber".equals( tipo );
  }

  /** Calculator ADD/SUBTRACT/MULTIPLY/DIVIDE con A Integer y B Number: B se redondea (ValueDataUtil elige por A). */
  static final class CalculadoraEnteroPrimero extends ReglaBase {

    CalculadoraEnteroPrimero() {
      super( "calculator_integer_first", Sev.ERROR, Regla.CORRECTNESS );
      conCampos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !"Calculator".equals( s.getStepID() ) ) {
          continue;
        }
        List<Element> calcs = Nodos.hijos( ctx.xml( s ), "calculation" );
        if ( calcs.isEmpty() ) {
          continue;
        }
        RowMetaInterface rm;
        try {
          rm = ctx.entrada( s );
        } catch ( SinCampos e ) {
          ctx.noEvaluado( e, s );
          continue;
        }
        Map<String, String> tipos = new HashMap<String, String>();
        for ( ValueMetaInterface v : rm.getValueMetaList() ) {
          tipos.put( v.getName(), v.getTypeDesc() );
        }
        for ( int i = 0; i < calcs.size(); i++ ) {
          Element c = calcs.get( i );
          String op = Nodos.txt( c, "calc_type" );
          String a = Nodos.txt( c, "field_a" );
          String b = Nodos.txt( c, "field_b" );
          String res = Nodos.txt( c, "field_name" );
          String vt = Nodos.txt( c, "value_type" );
          String ta = tipos.get( a );
          String tb = tipos.get( b );
          Hallazgo h = null;
          if ( ARITMETICA.contains( op ) && "Integer".equals( ta ) && numerico( tb ) ) {
            h = ctx.enStep( Sev.ERROR, s, res.isEmpty() ? "calculation" : res );
            h.message = ctx.t( "calculator_integer_first.msg", "step", s.getName(), "result", res, "a", a, "b", b,
              "op", op, "typeb", tb );
            if ( op.equals( "ADD" ) || op.equals( "MULTIPLY" ) ) {
              h.fix = Arreglo.auto( Arreglo.SWAP_OPERANDS ).param( "calculation", i + 1 ).param( "field_a", b )
                .param( "field_b", a );
              h.howToFix = ctx.t( "calculator_integer_first.fix.swap", "a", a, "b", b );
            } else {
              h.howToFix = ctx.t( "calculator_integer_first.fix.convert", "a", a );
            }
          } else if ( op.equals( "DIVIDE" ) && "Integer".equals( ta ) && "Integer".equals( tb ) && numerico( vt ) ) {
            h = ctx.enStep( Sev.WARNING, s, res.isEmpty() ? "calculation" : res );
            h.message = ctx.t( "calculator_integer_first.msg.division", "step", s.getName(), "result", res, "a", a,
              "b", b, "type", vt );
            h.howToFix = ctx.t( "calculator_integer_first.fix.convert", "a", a );
          }
          if ( h != null ) {
            if ( res.isEmpty() ) {
              h.n = i + 1;
            }
            h.field = res;
            h.evidencia( "calculation", i + 1 ).evidencia( "calc_type", op ).evidencia( "field_a", a + " (" + ta + ")" )
              .evidencia( "field_b", b + " (" + tb + ")" );
            out.add( h );
          }
          if ( !res.isEmpty() && !vt.isEmpty() ) {
            tipos.put( res, vt ); // los cálculos siguientes ven el resultado con el tipo declarado
          }
        }
      }
    }
  }

  /** Number/BigNumber leído de texto con el símbolo decimal vacío: usa el del locale de la máquina. */
  static final class DecimalVacio extends ReglaBase {

    DecimalVacio() {
      super( "empty_decimal_symbol", Sev.WARNING, Regla.CORRECTNESS );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        String id = s.getStepID();
        if ( ENTRADAS_TEXTO.contains( id ) ) {
          for ( Element f : Nodos.hijos( Nodos.hijo( ctx.xml( s ), "fields" ), "field" ) ) {
            String t = Nodos.txt( f, "type" );
            if ( numerico( t ) && Nodos.txt( f, "decimal" ).isEmpty() ) {
              String campo = Nodos.txt( f, "name" );
              Hallazgo h = ctx.enStep( Sev.WARNING, s, campo );
              h.field = campo;
              String formato = Nodos.txt( f, "format" );
              h.message = ctx.t( "empty_decimal_symbol.msg", "step", s.getName(), "field", campo, "type", t,
                "format", formato.isEmpty() ? "-" : formato );
              h.evidencia( "type", t ).evidencia( "format", formato );
              h.fix = Arreglo.asistido( Arreglo.SET_DECIMAL ).param( "field", campo ).param( "suggested", "." );
              out.add( h );
            }
          }
        } else if ( id.equals( "DataGrid" ) || id.equals( "RowGenerator" ) ) {
          Element x = ctx.xml( s );
          List<Element> fs = Nodos.hijos( Nodos.hijo( x, "fields" ), "field" );
          for ( int i = 0; i < fs.size(); i++ ) {
            Element f = fs.get( i );
            String t = Nodos.txt( f, "type" );
            if ( !numerico( t ) || !Nodos.txt( f, "decimal" ).isEmpty() ) {
              continue;
            }
            // con Data grid y Generate rows se ven los valores: solo importa si alguno trae '.' o ','
            List<String> vals = new ArrayList<String>();
            if ( id.equals( "RowGenerator" ) ) {
              vals.add( Nodos.txt( f, "nullif" ) );
            } else {
              for ( Element l : Nodos.hijos( Nodos.hijo( x, "data" ), "line" ) ) {
                List<Element> it = Nodos.hijos( l, "item" );
                if ( i < it.size() ) {
                  vals.add( it.get( i ).getTextContent() );
                }
              }
            }
            boolean punto = false;
            boolean coma = false;
            String ejemplo = null;
            for ( String v : vals ) {
              if ( v.contains( "." ) || v.contains( "," ) ) {
                punto |= v.contains( "." );
                coma |= v.contains( "," );
                ejemplo = ejemplo == null ? v : ejemplo;
              }
            }
            if ( ejemplo == null ) {
              continue;
            }
            String campo = Nodos.txt( f, "name" );
            Hallazgo h = ctx.enStep( Sev.WARNING, s, campo );
            h.field = campo;
            h.message = ctx.t( "empty_decimal_symbol.msg.literal", "step", s.getName(), "field", campo, "type", t,
              "example", ejemplo );
            h.evidencia( "type", t ).evidencia( "example", ejemplo );
            if ( punto != coma ) {
              h.fix = Arreglo.conConfirmacion( Arreglo.SET_DECIMAL ).param( "field", campo ).vista( punto ? "." : "," );
            } else {
              h.fix = Arreglo.asistido( Arreglo.SET_DECIMAL ).param( "field", campo );
            }
            out.add( h );
          }
        }
      }
    }
  }

  /** ${X} o %%X%% en el valor de Add constants o Generate rows: sale literal. En Data grid no (lo resuelve quien lo usa). */
  static final class VariableEnConstante extends ReglaBase {

    VariableEnConstante() {
      super( "variable_in_constant", Sev.ERROR, Regla.CORRECTNESS );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        String id = s.getStepID();
        if ( !id.equals( "Constant" ) && !id.equals( "RowGenerator" ) ) {
          continue;
        }
        for ( Element f : Nodos.hijos( Nodos.hijo( ctx.xml( s ), "fields" ), "field" ) ) {
          String v = Nodos.txt( f, "nullif" );
          if ( VARIABLE.matcher( v ).find() ) {
            String campo = Nodos.txt( f, "name" );
            Hallazgo h = ctx.enStep( Sev.ERROR, s, campo );
            h.field = campo;
            h.message = ctx.t( "variable_in_constant.msg", "step", s.getName(), "field", campo, "value", v );
            h.evidencia( "value", v );
            out.add( h );
          }
        }
      }
    }
  }

  /** Text file output sin campos declarados: rellena los String con longitud hasta su largo (D8). */
  static final class SalidaSinCampos extends ReglaBase {

    SalidaSinCampos() {
      super( "text_output_without_fields", Sev.WARNING, Regla.CORRECTNESS );
      conCampos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      for ( StepMeta s : ctx.trans().getSteps() ) {
        if ( !SALIDAS_TEXTO.contains( s.getStepID() ) ) {
          continue;
        }
        if ( !Nodos.hijos( Nodos.hijo( ctx.xml( s ), "fields" ), "field" ).isEmpty() ) {
          continue;
        }
        Hallazgo h;
        try {
          RowMetaInterface rm = ctx.entrada( s );
          List<String> conLargo = new ArrayList<String>();
          for ( ValueMetaInterface v : rm.getValueMetaList() ) {
            if ( v.getType() == ValueMetaInterface.TYPE_STRING && v.getLength() > 0 ) {
              conLargo.add( v.getName() + "(" + v.getLength() + ")" );
            }
          }
          if ( conLargo.isEmpty() ) {
            h = ctx.enStep( Sev.INFO, s, "fields" );
            h.message = ctx.t( "text_output_without_fields.msg.no_padding", "step", s.getName() );
          } else {
            h = ctx.enStep( Sev.WARNING, s, "fields" );
            h.message = ctx.t( "text_output_without_fields.msg", "step", s.getName(), "fields", ReglasOrden.unir( conLargo ) );
            h.evidencia( "padded", ReglasOrden.unir( conLargo ) );
          }
        } catch ( SinCampos e ) {
          h = ctx.enStep( Sev.WARNING, s, "fields" );
          h.message = ctx.t( "text_output_without_fields.msg.unknown", "step", s.getName() );
          h.evidencia( "fields_unknown", e.getMessage() );
        }
        h.fix = Arreglo.conConfirmacion( Arreglo.DECLARE_OUTPUT_FIELDS );
        out.add( h );
      }
    }
  }
}
