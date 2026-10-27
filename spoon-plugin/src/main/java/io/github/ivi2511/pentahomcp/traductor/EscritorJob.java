package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.database.DatabaseMeta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.traductor.ProgramaJob.Funcion;

/**
 * Escribe un {@link ProgramaJob} como script .py o como notebook: un solo preámbulo (imports, SparkSession, log, VARS,
 * RESULT, conexiones y ayudantes de todas las transformaciones), una función por entrada y main() con el flujo. En el
 * notebook, cada función va en su celda con una celda de texto encima, y la última celda ejecuta main().
 */
public final class EscritorJob {

  private EscritorJob() {
  }

  public static String escribir( ProgramaJob p, Opciones o ) throws Exception {
    return o != null && o.notebook() ? ipynb( p, o ) : py( p, o );
  }

  public static String py( ProgramaJob p, Opciones o ) {
    StringBuilder b = new StringBuilder( cabecera( p, o ) );
    for ( String l : preambulo( p ) ) {
      b.append( l ).append( '\n' );
    }
    for ( Funcion f : p.funciones ) {
      b.append( "\n\n" ).append( funcion( p, f, o ) );
    }
    b.append( "\n\n" ).append( principal( p ) );
    b.append( "\n\nif __name__ == \"__main__\":\n    sys.exit(0 if main() else 1)\n" );
    return b.toString();
  }

  public static String ipynb( ProgramaJob p, Opciones o ) throws Exception {
    CtxTrad c = p.ctx;
    ObjectMapper om = new ObjectMapper().enable( SerializationFeature.INDENT_OUTPUT );
    ObjectNode nb = om.createObjectNode();
    ArrayNode celdas = nb.putArray( "cells" );
    String titulo = o != null && o.titulo != null ? o.titulo : p.nombre;
    StringBuilder t = new StringBuilder( "# " + titulo + "\n\n" + cabecera( p, o ).replace( "\"\"\"", "" ).trim() );
    if ( o != null && o.introduccion != null ) {
      t.append( "\n\n" ).append( o.introduccion );
    }
    EscritorIpynb.texto( celdas, t.toString() );
    EscritorIpynb.codigo( celdas, String.join( "\n", preambulo( p ) ) );
    for ( Funcion f : p.funciones ) {
      Bloque k = f.bloque;
      StringBuilder m = new StringBuilder( "## " + k.paso + "\n\n`" + k.tipo + "`" );
      if ( k.estado != Estado.EXACTO ) {
        m.append( " · **" ).append( k.estado.id() ).append( "**" );
      }
      if ( k.explicacion != null && ( o == null || o.explicaciones ) ) {
        m.append( "\n\n" ).append( k.explicacion );
      }
      for ( String n : conNotas( o, k ) ? k.notas : new ArrayList<String>() ) {
        m.append( "\n\n> " ).append( c.t( "Nota: ", "Note: ", "Nota: " ) ).append( n );
      }
      EscritorIpynb.texto( celdas, m.toString() );
      EscritorIpynb.codigo( celdas, funcion( p, f, o ).replaceAll( "\n+$", "" ) );
    }
    StringBuilder m = new StringBuilder( "## " + c.t( "Flujo del job", "Job flow", "Fluxo do job" ) + "\n\n" + c.t(
      "main() sigue los hops desde START: los verdes si la entrada va bien, los rojos si falla, los negros siempre.",
      "main() follows the hops from START: green ones if the entry succeeds, red ones if it fails, black ones always.",
      "main() segue os hops a partir do START: os verdes se a entrada correr bem, os vermelhos se falhar, os pretos "
        + "sempre." ) );
    for ( String n : o == null || o.notas ? p.notas : new ArrayList<String>() ) {
      m.append( "\n\n> " ).append( c.t( "Nota: ", "Note: ", "Nota: " ) ).append( n );
    }
    EscritorIpynb.texto( celdas, m.toString() );
    EscritorIpynb.codigo( celdas, principal( p ).replaceAll( "\n+$", "" ) );
    EscritorIpynb.texto( celdas, "## " + c.t( "Ejecutar el job", "Run the job", "Executar o job" ) );
    EscritorIpynb.codigo( celdas, "ok = main()\nprint(" + CtxTrad.lit( c.t( "Resultado del job: ", "Job result: ",
      "Resultado do job: " ) ) + ", \"OK\" if ok else \"ERROR\")" );
    ObjectNode meta = nb.putObject( "metadata" );
    ObjectNode ks = meta.putObject( "kernelspec" );
    ks.put( "name", "python3" ).put( "display_name", "Python 3" ).put( "language", "python" );
    meta.putObject( "language_info" ).put( "name", "python" );
    nb.put( "nbformat", 4 ).put( "nbformat_minor", 5 );
    return om.writeValueAsString( nb );
  }

  /** Docstring de cabecera. */
  public static String cabecera( ProgramaJob p, Opciones o ) {
    CtxTrad c = p.ctx;
    StringBuilder b = new StringBuilder( "\"\"\"\n" );
    if ( o != null && o.titulo != null ) {
      b.append( sinComillas( o.titulo ) ).append( "\n\n" );
    }
    b.append( c.t( "Traducción a Python del job «", "Python translation of the job '",
      "Tradução para Python do job «" ) )
      .append( p.nombre ).append( c.t( "»", "'", "»" ) ).append( " (" ).append( p.destino().id() ).append( ").\n" );
    if ( p.archivo != null ) {
      b.append( c.t( "Origen: ", "Source: ", "Origem: " ) ).append( EscritorPy.nombreArchivo( p.archivo ) ).append(
        '\n' );
    }
    if ( o != null && o.autor != null ) {
      b.append( c.t( "Autor: ", "Author: ", "Autor: " ) ).append( sinComillas( o.autor ) ).append( '\n' );
    }
    int pasos = 0;
    int manual = 0;
    for ( Programa t : p.transformaciones() ) {
      pasos += t.bloques.size();
      manual += t.cuantos( Estado.MANUAL );
    }
    b.append( c.t( "Generado por Pentaho Live. Entradas: ", "Generated by Pentaho Live. Entries: ",
      "Gerado pelo Pentaho Live. Entradas: " ) ).append( p.cuantos( Estado.EXACTO ) + p.cuantos( Estado.APROXIMADO )
        + p.cuantos( Estado.MANUAL ) ).append( " (" ).append( p.cuantos( Estado.EXACTO ) ).append( c.t( " exactas, ",
          " exact, ", " exatas, " ) ).append( p.cuantos( Estado.APROXIMADO ) ).append( c.t( " aproximadas, ",
            " approximate, ", " aproximadas, " ) ).append( p.cuantos( Estado.MANUAL ) ).append( c.t( " a completar",
              " to complete", " a completar" ) ).append( "); " ).append( c.t( "transformaciones: ",
                "transformations: ", "transformações: " ) ).append( p.transformaciones().size() ).append( " (" )
      .append( pasos ).append( " steps, " ).append( manual ).append( c.t( " a completar).", " to complete).",
        " a completar)." ) ).append( '\n' );
    b.append( c.t( "Ejecútalo como Kitchen: python script.py (código de salida 0 si el job termina bien).",
      "Run it like Kitchen: python script.py (exit code 0 if the job ends well).",
      "Execute-o como o Kitchen: python script.py (código de saída 0 se o job terminar bem)." ) ).append( '\n' );
    return b.append( "\"\"\"\n" ).toString();
  }

  private static String sinComillas( String s ) {
    return s.replace( "\\", "/" ).replace( "\"\"\"", "\"" );
  }

  /** Imports, SparkSession, log, AQUI, VARS, RESULT, conexiones y ayudantes (de todo el job). */
  public static List<String> preambulo( ProgramaJob p ) {
    CtxTrad c = p.ctx;
    List<String> con = new ArrayList<>();
    for ( DatabaseMeta db : new ArrayList<>( c.conexiones.values() ) ) {
      con.addAll( Conexiones.crear( db, c ) );
    }
    List<String> r = new ArrayList<>( c.imports );
    r.add( "" );
    if ( c.spark() ) {
      r.add( "_builder = SparkSession.builder.appName(" + CtxTrad.lit( p.nombre ) + ")" );
      r.add( "if os.name == \"nt\":  # " + c.t(
        "Windows: Spark local en localhost (un nombre de equipo con _ lo rompe)",
        "Windows: local Spark on localhost (a host name with _ breaks it)",
        "Windows: Spark local em localhost (um nome de máquina com _ o quebra)" ) );
      r.add( "    _builder = _builder.config(\"spark.driver.host\", \"localhost\")"
        + ".config(\"spark.driver.bindAddress\", "
        + "\"127.0.0.1\")" );
      r.add( "spark = _builder.getOrCreate()" );
      r.add( "" );
    }
    r.add( "logging.basicConfig(level=logging.INFO, format=\"%(asctime)s %(levelname)s %(message)s\")" );
    r.add( "log = logging.getLogger(" + CtxTrad.lit( p.nombre ) + ")" );
    r.add( "" );
    r.add( "# " + c.t( "carpeta del script (o del notebook): la misma que la del .kjb",
      "folder of the script (or notebook): the same as the .kjb's",
      "pasta do script (ou do notebook): a mesma do .kjb" ) );
    r.add( "AQUI = os.path.dirname(os.path.abspath(__file__)) if \"__file__\" in globals() else os.getcwd()" );
    r.add( "VARS = {" );
    for ( Map.Entry<String, String> e : c.vars.entrySet() ) {
      r.add( "    " + CtxTrad.lit( e.getKey() ) + ": " + e.getValue() + "," );
    }
    r.add( "}" );
    r.add( "# " + c.t( "filas que una transformación deja en el resultado (Copy rows to result) para la siguiente",
      "rows a transformation leaves in the result (Copy rows to result) for the next one",
      "linhas que uma transformação deixa no resultado (Copy rows to result) para a seguinte" ) );
    r.add( "RESULT = {}" );
    if ( !con.isEmpty() ) {
      r.add( "" );
      r.addAll( con );
    }
    for ( String a : c.ayudantes.values() ) {
      r.add( "" );
      r.add( "" );
      for ( String l : a.split( "\n" ) ) {
        r.add( l );
      }
    }
    return r;
  }

  /** Una función: decorador, def, docstring, notas y cuerpo (con la transformación dentro si la tiene). */
  public static String funcion( ProgramaJob p, Funcion f ) {
    return funcion( p, f, null );
  }

  /** ¿Van las notas del bloque? (las de un bloque MANUAL siempre: dicen qué completar). */
  static boolean conNotas( Opciones o, Bloque k ) {
    return o == null || o.notas || k.estado == Estado.MANUAL;
  }

  /**
   * Con opciones (null: como antes): notas si o.notas; la transformación de dentro con sus explicaciones (o
   * DescribirStep) y sus notas según las opciones.
   */
  public static String funcion( ProgramaJob p, Funcion f, Opciones o ) {
    CtxTrad c = p.ctx;
    Bloque k = f.bloque;
    StringBuilder b = new StringBuilder();
    if ( f.entrada ) {
      b.append( "@entrada\n" );
    }
    b.append( "def " ).append( f.nombre ).append( "():\n" );
    String doc = k.paso + ( f.entrada ? " (" + k.tipo + ")" : "" ) + ( k.explicacion == null ? "" : ": "
      + k.explicacion );
    doc = sinComillas( doc ).replace( '\n', ' ' );
    b.append( "    \"\"\"" ).append( doc ).append( doc.endsWith( "\"" ) ? " " : "" ).append( "\"\"\"\n" );
    if ( k.estado != Estado.EXACTO ) {
      b.append( "    # " ).append( c.t( "estado: ", "status: ", "estado: " ) ).append( k.estado.id() ).append( '\n' );
    }
    for ( String n : conNotas( o, k ) ? k.notas : new ArrayList<String>() ) {
      b.append( "    # " ).append( c.t( "NOTA: ", "NOTE: ", "NOTA: " ) ).append( n.replace( '\n', ' ' ) ).append(
        '\n' );
    }
    for ( int i = 0; i <= k.codigo.size(); i++ ) {
      if ( i == f.posicion && f.programa != null ) {
        b.append( o == null ? EscritorPy.cuerpo( f.programa, f.sangria ) : EscritorPy.cuerpo( f.programa, f.sangria,
          o ) );
      }
      if ( i < k.codigo.size() ) {
        String l = k.codigo.get( i );
        b.append( l.isEmpty() ? "" : "    " + l ).append( '\n' );
      }
    }
    return b.toString().replaceAll( "\n{2,}$", "\n" );
  }

  /** def main() con el flujo del job principal. */
  public static String principal( ProgramaJob p ) {
    CtxTrad c = p.ctx;
    StringBuilder b = new StringBuilder( "def main():\n" );
    b.append( "    \"\"\"" ).append( c.t( "Flujo del job «", "Flow of the job '", "Fluxo do job «" ) ).append(
      sinComillas( p.nombre ) ).append( c.t( "»: True si termina bien (como el código 0 de Kitchen).",
        "': True if it ends well (like Kitchen's exit code 0).", "»: True se terminar bem (como o código 0 do "
          + "Kitchen)." ) ).append( "\"\"\"\n" );
    for ( String l : p.principal ) {
      b.append( l.isEmpty() ? "" : "    " + l ).append( '\n' );
    }
    return b.toString();
  }
}
