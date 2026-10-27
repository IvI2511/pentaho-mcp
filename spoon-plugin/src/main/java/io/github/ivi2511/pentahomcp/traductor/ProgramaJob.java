package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.List;

/**
 * El resultado de traducir un job: una función por entrada (las transformaciones con su {@link Programa} dentro, los
 * sub-jobs con su propio flujo), las ramas compartidas del flujo y el cuerpo de main(). El contexto ({@link #ctx}) es
 * el del job: imports, ayudantes, VARS y conexiones de las entradas; {@link EscritorJob} lo une con los de todas las
 * transformaciones en un solo preámbulo.
 */
public final class ProgramaJob {

  /** Nombre del job. */
  public final String nombre;
  /** Ruta del .kjb (o null si no está guardado). */
  public final String archivo;
  /** Contexto del job (sobre una transformación vacía: idioma, destino, imports, ayudantes, VARS, conexiones). */
  public final CtxTrad ctx;
  /** Funciones en orden de escritura (las de un sub-job antes que la función del sub-job). */
  public final List<Funcion> funciones = new ArrayList<>();
  /** Cuerpo de main(), sin sangría base. */
  public final List<String> principal = new ArrayList<>();
  /** Notas del flujo (paralelo, programación del START, bucles...). */
  public final List<String> notas = new ArrayList<>();

  public ProgramaJob( String nombre, String archivo, CtxTrad ctx ) {
    this.nombre = nombre;
    this.archivo = archivo;
    this.ctx = ctx;
  }

  public Destino destino() {
    return ctx.destino;
  }

  /** Las transformaciones traducidas (en el orden de las funciones). */
  public List<Programa> transformaciones() {
    List<Programa> r = new ArrayList<>();
    for ( Funcion f : funciones ) {
      if ( f.programa != null ) {
        r.add( f.programa );
      }
    }
    return r;
  }

  /** Cuántas entradas hay en cada estado. */
  public int cuantos( Estado e ) {
    int n = 0;
    for ( Funcion f : funciones ) {
      if ( f.entrada && f.bloque.estado == e ) {
        n++;
      }
    }
    return n;
  }

  /** El peor estado (de las entradas y de las transformaciones de dentro). */
  public Estado estado() {
    Estado r = Estado.EXACTO;
    for ( Funcion f : funciones ) {
      r = r.peor( f.bloque.estado );
      if ( f.programa != null ) {
        r = r.peor( f.programa.estado() );
      }
    }
    return r;
  }

  /** Una función del script: la de una entrada (decorada con @entrada), la de un sub-job o una rama del flujo. */
  public static final class Funcion {
    /** Nombre Python (tr_x, e_x, job_x, rama_x). */
    public final String nombre;
    /** Nombre, tipo, estado, explicación, notas y cuerpo (sin sangría base, sin la línea def). */
    public final Bloque bloque;
    /** ¿Es una entrada del job (lleva @entrada: una excepción cuenta como fallo)? */
    public final boolean entrada;
    /** La transformación de una entrada TRANS (su cuerpo va en {@link #posicion} del bloque), o null. */
    public Programa programa;
    /** Línea del bloque donde va el cuerpo de {@link #programa} y su sangría. */
    public int posicion = -1;
    public String sangria = "    ";

    public Funcion( String nombre, Bloque bloque, boolean entrada ) {
      this.nombre = nombre;
      this.bloque = bloque;
      this.entrada = entrada;
    }
  }
}
