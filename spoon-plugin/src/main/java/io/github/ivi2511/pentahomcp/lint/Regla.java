package io.github.ivi2511.pentahomcp.lint;

import java.util.List;

/**
 * Una regla del linter (un id del catálogo de docs/linter-diseno.md §2). Las reglas se agrupan por familia en
 * lint.reglas (ReglasOrden, ReglasRutas…): cada familia declara las suyas y Catalogo fija el orden en que corren.
 * revisar() corre en un hilo de trabajo, sobre una copia desligada del documento, y nunca abre diálogos.
 */
public interface Regla {

  /** correctness | portability | security | structure | style */
  String CORRECTNESS = "correctness";
  String PORTABILITY = "portability";
  String SECURITY = "security";
  String STRUCTURE = "structure";
  String STYLE = "style";

  /** Id en snake_case; es el que va en la key del hallazgo y en las supresiones. */
  String id();

  /** La peor severidad que puede emitir (la de la tabla del §2). */
  Sev severidad();

  String categoria();

  boolean paraTrans();

  boolean paraJobs();

  /** Calcula campos (getPrevStepFields): si la transformación tiene un ciclo, no corre (§3.5). */
  boolean necesitaCampos();

  /** Lee el archivo del disco: solo corre si el documento tiene archivo y no tiene cambios sin guardar (§2). */
  boolean soloArchivo();

  /** Apagada por defecto (las podadas de §2.1): corre solo si se pide en Opciones.rules y nunca resta. */
  boolean apagada();

  /** Sus hallazgos dependen de la máquina (archivos, variables, la BD): el corrector no los usa (§9). */
  boolean env();

  /** Si resta puntos: false para kettle_check y para las apagadas (§4). */
  boolean puntua();

  void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception;
}
