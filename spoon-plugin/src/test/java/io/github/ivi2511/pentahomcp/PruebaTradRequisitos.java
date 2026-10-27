package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;

import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorIpynb;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Opciones;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.Requisitos;
import io.github.ivi2511.pentahomcp.traductor.TraductorTrans;

/**
 * Las bibliotecas del código generado (0.8.0): el requirements.txt de al lado y la comprobación del principio, que
 * corta con un mensaje claro si falta una biblioteca (o su versión no alcanza) en vez de un ModuleNotFoundError a mitad
 * de camino; el driver de la conexión (pymysql para MySQL) entra en la lista.
 */
public class PruebaTradRequisitos {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /** Una transformación que lee de MySQL: necesita pandas, SQLAlchemy y pymysql. */
  static TransMeta conMysql() {
    TransMeta t = new TransMeta();
    t.setName( "lee_mysql" );
    DatabaseMeta db = new DatabaseMeta( "origen", "MYSQL", "Native", "localhost", "tienda", "3306", "lector", "" );
    t.addDatabase( db );
    TableInputMeta m = new TableInputMeta();
    m.setDefault();
    m.setDatabaseMeta( db );
    m.setSQL( "SELECT id, nombre FROM clientes" );
    StepMeta s = new StepMeta( "TableInput", "Leer clientes", m );
    t.addStep( s );
    return t;
  }

  public void pruebaListaYCodigo() throws Exception {
    Programa p = TraductorTrans.traducir( conMysql(), Destino.PANDAS, "es" );
    Opciones o = new Opciones();
    o.formato = "py";
    List<String> r = Requisitos.de( p, o );
    cierto( r.contains( "pandas" ), "pandas: " + r );
    cierto( r.contains( "pymysql" ), "el driver de MySQL: " + r );
    cierto( r.contains( "sqlalchemy>=2.0.36" ), "SQLAlchemy con versión mínima: " + r );
    igual( "psycopg2", Requisitos.modulo( "psycopg2-binary" ), "módulo de psycopg2-binary" );
    igual( "sqlalchemy", Requisitos.modulo( "sqlalchemy>=2.0.36" ), "módulo sin versión" );
    contiene( Requisitos.pip( r ), "\"sqlalchemy>=2.0.36\"", "pip con comillas en la versión" );

    String py = EscritorPy.escribir( p, o );
    int chequeo = py.indexOf( "_comprobar_bibliotecas([" );
    cierto( chequeo > 0 && chequeo < py.indexOf( "import pandas as pd" ), "la comprobación va antes de los imports" );
    contiene( py, "(\"pymysql\", \"pymysql\")", "comprueba pymysql" );
    contiene( py, "pip install -r requirements.txt", "cómo correrlo nombra el requirements.txt" );
    PythonPrueba.compila( escribir( "lee_mysql.py", py ) );

    String nb = EscritorIpynb.escribir( p, new Opciones() );
    cierto( nb.indexOf( "_comprobar_bibliotecas" ) < nb.indexOf( "import pandas as pd" ),
      "en el notebook, la celda de comprobación va antes del preámbulo" );
  }

  public void pruebaFaltaYVersion() throws Exception {
    if ( PythonPrueba.python() == null ) {
      saltear( "sin Python" );
      return;
    }
    PythonPrueba.Resultado falta = correr( Arrays.asList( "pandas", "paquete-que-no-existe-xyz" ), "es" );
    cierto( falta.codigo != 0, "termina con error si falta una biblioteca" );
    contiene( falta.salida, "Faltan bibliotecas de Python", "mensaje claro" );
    contiene( falta.salida, "pip install paquete-que-no-existe-xyz", "el comando para instalarla" );
    cierto( !falta.salida.contains( "pip install pandas" ), "no pide lo que ya está: " + falta.salida );

    PythonPrueba.Resultado vieja = correr( Arrays.asList( "pandas>=999.0" ), "en" );
    cierto( vieja.codigo != 0, "termina con error si la versión no alcanza" );
    contiene( vieja.salida, "Missing Python libraries (or too old)", "mensaje en el idioma elegido" );
    contiene( vieja.salida, "\"pandas>=999.0\"", "la versión pedida, entre comillas" );

    PythonPrueba.Resultado bien = correr( Arrays.asList( "pandas", "pandas>=1.0" ), "es" );
    igual( 0, bien.codigo, "con todo instalado sigue: " + bien.salida );
  }

  public void pruebaRequirementsSeCompleta() throws Exception {
    File dir = EntornoKettle.salida( "trad-requisitos/carpeta" );
    File f = new File( dir, Requisitos.ARCHIVO );
    Files.write( f.toPath(), "# del proyecto\nsqlalchemy\nrequests==2.31.0\n".getBytes( StandardCharsets.UTF_8 ) );
    Requisitos.escribir( dir, Arrays.asList( "pandas", "sqlalchemy>=2.0.36", "pymysql" ) );
    List<String> l = Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 );
    igual( Arrays.asList( "# del proyecto", "sqlalchemy>=2.0.36", "requests==2.31.0", "pandas", "pymysql" ), l,
      "conserva lo suyo, sube la versión y agrega lo que falta" );
    Requisitos.escribir( dir, Arrays.asList( "pandas", "sqlalchemy" ) );
    igual( l, Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 ), "no repite ni baja la versión" );
  }

  public void pruebaHerramientaEscribeRequirements() throws Exception {
    File dir = EntornoKettle.salida( "trad-requisitos/tool" );
    File ktr = new File( dir, "lee_mysql.ktr" );
    TransMeta t = conMysql();
    Files.write( ktr.toPath(), t.getXML().getBytes( StandardCharsets.UTF_8 ) );
    t.setFilename( ktr.getAbsolutePath() );
    new File( dir, Requisitos.ARCHIVO ).delete();
    Opciones o = new Opciones();
    o.formato = "py";
    TraducirTools.Hecho h = TraducirTools.traducir( t, o, new File( dir, "lee_mysql.py" ), true, false );
    cierto( h.requirements != null && h.requirements.isFile(), "escribió el requirements.txt" );
    String req = new String( Files.readAllBytes( h.requirements.toPath() ), StandardCharsets.UTF_8 );
    contiene( req, "pymysql", "con el driver" );
    contiene( TraducirTools.json( h, o ).toString(), "requirements_file", "la herramienta lo informa" );
  }

  private static PythonPrueba.Resultado correr( List<String> reqs, String idioma ) throws Exception {
    File f = escribir( "chequeo_" + Math.abs( reqs.hashCode() ) + ".py", String.join( "\n", Requisitos.comprobacion(
      reqs, idioma ) ) + "\nprint(\"SIGUE\")\n" );
    return PythonPrueba.correr( f.getParentFile(), 120, PythonPrueba.python(), f.getAbsolutePath() );
  }

  private static File escribir( String nombre, String texto ) throws Exception {
    File f = new File( EntornoKettle.salida( "trad-requisitos" ), nombre );
    Files.write( f.toPath(), texto.getBytes( StandardCharsets.UTF_8 ) );
    return f;
  }
}
