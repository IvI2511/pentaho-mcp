package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * El lienzo en modo boceto (D2): los marcadores que deja plan_build son steps Dummy con el nombre de una etapa del
 * plano y una nota «PENDIENTE». Esta familia tiene una sola regla, {@code placeholder_step}: un marcador que sigue en
 * el lienzo es una pieza sin construir y no puede llegar a producción.
 * <p>
 * Las marcas son atributos de Kettle (se guardan en el .ktr): en la transformación, el registro del plano (grupo
 * {@link #GRUPO}: {@link #K_PLANO}, {@link #K_TRANS}, {@link #K_ETAPAS} y {@code stage.N} = «id TAB nombre»); en
 * cada marcador, {@link #K_MARCADOR} = id de la etapa; y en el step que lo reemplazó (fill_placeholder),
 * {@link #K_ETAPA} = id de la etapa. Un Dummy sin la marca cuenta como marcador si se llama como una etapa del
 * registro (así cuentan también los que crea una lección, que no llevan atributos).
 */
public final class ReglasBoceto {

  public static final String GRUPO = "pentaho_live_plan";
  /** Transformación: ruta del plano. */
  public static final String K_PLANO = "plan";
  /** Transformación: nombre del plano. */
  public static final String K_NOMBRE = "plan_name";
  /** Transformación: la transformación del plano que se construyó aquí. */
  public static final String K_TRANS = "transformation";
  /** Transformación: cuántas etapas tiene el registro. */
  public static final String K_ETAPAS = "stages";
  /** Transformación: prefijo de cada etapa del registro ("stage.0" = "id\tnombre"). */
  public static final String P_ETAPA = "stage.";
  /** Step: es un marcador de la etapa (valor: id de la etapa). */
  public static final String K_MARCADOR = "placeholder";
  /** Step: reemplazó al marcador de la etapa (valor: id de la etapa). */
  public static final String K_ETAPA = "stage";
  /** Id de tipo de los marcadores. */
  public static final String DUMMY = "Dummy";

  private ReglasBoceto() {
  }

  public static List<Regla> todas() {
    List<Regla> l = new ArrayList<Regla>();
    l.add( new Marcador() );
    return l;
  }

  /** Las etapas del registro de la transformación, {id: nombre} en orden; vacío si no tiene plano. */
  public static Map<String, String> registro( TransMeta tm ) {
    Map<String, String> m = new LinkedHashMap<String, String>();
    if ( tm == null ) {
      return m;
    }
    String n = tm.getAttribute( GRUPO, K_ETAPAS );
    int total;
    try {
      total = n == null ? 0 : Integer.parseInt( n.trim() );
    } catch ( NumberFormatException e ) {
      total = 0;
    }
    for ( int i = 0; i < total; i++ ) {
      String v = tm.getAttribute( GRUPO, P_ETAPA + i );
      if ( v == null ) {
        continue;
      }
      int tab = v.indexOf( '\t' );
      if ( tab > 0 ) {
        m.put( v.substring( 0, tab ), v.substring( tab + 1 ) );
      }
    }
    return m;
  }

  /** La etapa de la que s es marcador (id), o null si no lo es. */
  public static String etapaDeMarcador( TransMeta tm, StepMeta s, Map<String, String> registro ) {
    if ( s == null || !DUMMY.equals( s.getStepID() ) ) {
      return null;
    }
    String a = s.getAttribute( GRUPO, K_MARCADOR );
    if ( a != null && !a.trim().isEmpty() ) {
      return a.trim();
    }
    String nombre = s.getName() == null ? "" : s.getName().trim().toLowerCase( Locale.ROOT );
    for ( Map.Entry<String, String> e : registro.entrySet() ) {
      if ( e.getValue().trim().toLowerCase( Locale.ROOT ).equals( nombre ) ) {
        return e.getKey();
      }
    }
    return null;
  }

  /** placeholder_step: un marcador del boceto sigue en el lienzo. */
  static final class Marcador extends ReglaBase {

    Marcador() {
      super( "placeholder_step", Sev.WARNING, Regla.STRUCTURE );
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) {
      TransMeta tm = ctx.trans();
      Map<String, String> reg = registro( tm );
      for ( StepMeta s : tm.getSteps() ) {
        String etapa = etapaDeMarcador( tm, s, reg );
        if ( etapa != null ) {
          Hallazgo h = ctx.enStep( Sev.WARNING, s, "type" );
          h.message = ctx.t( "placeholder_step.msg", "step", s.getName() );
          h.evidencia( "stage", etapa );
          out.add( h );
        }
      }
    }
  }
}
