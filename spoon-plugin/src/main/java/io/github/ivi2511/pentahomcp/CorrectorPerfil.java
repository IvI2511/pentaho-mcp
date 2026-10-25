package io.github.ivi2511.pentahomcp;

import java.io.File;

import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.ReglaEvaluada;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * El renglón {@code lint:profile} de la rúbrica (0.7, E5): «cumple las convenciones del curso». El perfil del linter
 * va DENTRO del pack, en {@code <pack>/.pentaho-live/lint.yaml} (o .yml/.json): solo ahí se busca (nunca subiendo
 * carpetas: la nota no puede depender de dónde se descomprimió el pack). Aprobado si el puntaje del linter con ese perfil
 * llega a su min_score ({@link #MINIMO_SIN_PERFIL} si no lo dice). Como los demás {@code lint:<id>}: sin supresiones
 * (el alumno no se silencia), sin modo profundo y sin las reglas que dependen de la máquina.
 */
final class CorrectorPerfil {

  /** El id del renglón: {@code lint:profile}. */
  static final String ID = "profile";
  /** El mínimo si el perfil no tiene min_score: sin ningún error (con uno el puntaje no pasa de 74). */
  static final int MINIMO_SIN_PERFIL = 75;

  private CorrectorPerfil() {
  }

  /** El perfil del pack, o null si no tiene. Lanza si el archivo está pero no se puede leer. */
  static PerfilEquipo delPack( File dirPack ) throws Exception {
    if ( dirPack == null ) {
      return null;
    }
    File d = new File( dirPack, PerfilEquipo.CARPETA );
    for ( String n : PerfilEquipo.ARCHIVOS ) {
      File f = new File( d, n );
      if ( f.isFile() ) {
        return PerfilEquipo.cargar( f );
      }
    }
    return null;
  }

  static ReglaEvaluada evaluar( File dirPack, TransMeta tm, String idioma ) {
    Rotulos t = Rotulos.de( CorrectorNota.AREA, idioma );
    String titulo = t.s( "lint.perfil.titulo" );
    PerfilEquipo pf;
    try {
      pf = delPack( dirPack );
    } catch ( Throwable e ) {
      return no( titulo, t.f( "lint.perfil.ilegible", RunMotor.primeraLinea( e ) ) );
    }
    if ( pf == null ) {
      return no( titulo, t.s( "lint.perfil.falta" ) );
    }
    if ( tm == null ) {
      return no( titulo, t.s( "regla.sin_transformacion" ) );
    }
    Resultado res;
    try {
      Opciones op = new Opciones();
      op.perfil = pf;
      op.honorSuppressions = false;
      op.deep = false;
      op.kettleChecks = false;
      op.sinEntorno = true;
      op.minSeverity = Sev.INFO;
      op.lang = Rotulos.normalizar( idioma );
      op.entorno = new LintEntorno( tm, CorrectorNota.MS_LINT );
      res = LintMotor.analizar( tm, op );
    } catch ( Throwable e ) {
      return no( titulo, t.f( "lint.fallo", RunMotor.primeraLinea( e ) ) );
    }
    int min = pf.minScore != null ? pf.minScore : MINIMO_SIN_PERFIL;
    return new ReglaEvaluada( titulo, res.score >= min, t.f( "lint.perfil.detalle", res.score, res.grade, pf.nombre,
      min ) );
  }

  private static ReglaEvaluada no( String titulo, String detalle ) {
    ReglaEvaluada re = new ReglaEvaluada( titulo, false, detalle );
    re.evaluada = false;
    return re;
  }
}
