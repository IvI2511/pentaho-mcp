package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.diagramas.Diagramas;
import io.github.ivi2511.pentahomcp.informes.diagramas.Dibujo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Objetivo;

/**
 * GrafoFlujo: el grafo del diagrama propio de los ejemplos (copias en {@code <prueba>\ej}) tiene lo mismo
 * que el Mermaid de ReportMotor (steps, saltos, verdadero/falso, error, lookup, START y clases de los saltos
 * del job) y se dibuja en todos los objetivos. Deja los SVG en {@code <prueba>\salida\diagramas-ej}.
 *
 * @dueño WP6
 */
public class PruebaGrafoFlujo {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static Grafo trans( String ejemplo, String idioma ) throws Exception {
    TransMeta tm = EntornoKettle.trans( ejemplo );
    return GrafoFlujo.desde( tm, ReportMotor.ordenTrans( tm ), new ReportMotor.T( idioma ) );
  }

  private static Grafo job( String ejemplo, String idioma ) throws Exception {
    JobMeta jm = EntornoKettle.job( ejemplo );
    return GrafoFlujo.desde( jm, ReportMotor.ordenJob( jm ), new ReportMotor.T( idioma ) );
  }

  private static Grafo.Arista arista( Grafo g, String desde, String hacia ) {
    for ( Grafo.Arista e : g.aristas() ) {
      if ( e.a.titulo.equals( desde ) && e.b.titulo.equals( hacia ) ) {
        return e;
      }
    }
    return null;
  }

  /** Mismos nodos y saltos que el Mermaid; verdadero/falso del Filter rows en el idioma pedido. */
  public void pruebaFiltro() throws Exception {
    TransMeta tm = EntornoKettle.trans( "02" );
    Grafo g = trans( "02", "es" );
    igual( Grafo.FLUJO, g.tipo, "tipo" );
    igual( tm.nrSteps(), g.nodos().size(), "un nodo por step" );
    igual( tm.nrTransHops(), g.aristas().size(), "una arista por hop" );
    String mermaid = ReportMotor.mermaidTrans( tm, ReportMotor.ordenTrans( tm ), new ReportMotor.T( "es" ) );
    for ( Grafo.Nodo n : g.nodos() ) {
      contiene( mermaid, ReportMotor.escaparMermaid( n.titulo ), "el Mermaid tiene el step " + n.titulo );
      cierto( !n.lineas.isEmpty(), "linea con el tipo de step de " + n.titulo );
    }
    int verdadero = 0;
    int falso = 0;
    for ( Grafo.Arista e : g.aristas() ) {
      verdadero += "verdadero".equals( e.rotulo ) ? 1 : 0;
      falso += "falso".equals( e.rotulo ) ? 1 : 0;
    }
    igual( 1, verdadero, "rotulo verdadero" );
    igual( 1, falso, "rotulo falso" );
    dibujar( g, "02" );
  }

  /** El hop de error del CSV input: clase error con rotulo; la leyenda lo explica. */
  public void pruebaError() throws Exception {
    Grafo g = trans( "06", "en" );
    int errores = 0;
    for ( Grafo.Arista e : g.aristas() ) {
      if ( Grafo.ERROR.equals( e.clase ) ) {
        errores++;
        igual( "error", e.rotulo, "rotulo del hop de error" );
      }
    }
    igual( 1, errores, "un hop de error" );
    dibujar( g, "06" );
    String svg = Diagramas.dibujar( g, Objetivo.A4, "en" ).get( 0 ).svg;
    contiene( svg, "error hop", "leyenda del hop de error (en)" );
  }

  /** La fuente de un Stream lookup: clase info y "lookup"/"búsqueda". */
  public void pruebaLookup() throws Exception {
    Grafo g = trans( "04", "es" );
    int lookups = 0;
    for ( Grafo.Arista e : g.aristas() ) {
      if ( Grafo.INFO.equals( e.clase ) ) {
        lookups++;
        noNulo( e.rotulo, "rotulo del lookup" );
      }
    }
    igual( 1, lookups, "un salto de lookup" );
    dibujar( g, "04" );
  }

  /** Job: START como pildora, clases ok / falla / siempre, sin rotulos en los saltos habilitados. */
  public void pruebaJob() throws Exception {
    JobMeta jm = EntornoKettle.job( "10" );
    Grafo g = job( "10", "es" );
    igual( Grafo.JOB, g.tipo, "tipo" );
    igual( jm.nrJobEntries(), g.nodos().size(), "un nodo por entrada" );
    igual( jm.nrJobHops(), g.aristas().size(), "una arista por hop" );
    int inicio = 0;
    for ( Grafo.Nodo n : g.nodos() ) {
      inicio += n.inicio ? 1 : 0;
      if ( n.inicio ) {
        igual( 0, n.lineas.size(), "START sin tipo" );
      }
    }
    igual( 1, inicio, "un START" );
    boolean ok = false;
    boolean falla = false;
    boolean siempre = false;
    for ( Grafo.Arista e : g.aristas() ) {
      ok |= Grafo.OK.equals( e.clase );
      falla |= Grafo.FALLA.equals( e.clase );
      siempre |= Grafo.SIEMPRE.equals( e.clase );
      igual( null, e.rotulo, "sin rotulo en un salto habilitado" );
    }
    cierto( ok && falla && siempre, "las tres clases de salto" );
    String svg = dibujar( g, "10" ).get( 0 ).svg;
    contiene( svg, "si termina bien", "leyenda" );
    dibujar( job( "11", "pt" ), "11" );
  }

  /** Todos los ejemplos se dibujan en todos los objetivos y entran en su caja. */
  public void pruebaTodosLosEjemplos() throws Exception {
    for ( String e : new String[] { "01", "02", "03", "04", "05", "06", "07" } ) {
      dibujar( trans( e, "es" ), e );
    }
    for ( String e : new String[] { "10", "11" } ) {
      dibujar( job( e, "es" ), e );
    }
    // un salto deshabilitado lleva el rotulo
    TransMeta tm = EntornoKettle.trans( "02" );
    tm.getTransHop( 0 ).setEnabled( false );
    Grafo g = GrafoFlujo.desde( tm, ReportMotor.ordenTrans( tm ), new ReportMotor.T( "es" ) );
    Grafo.Arista off = arista( g, tm.getTransHop( 0 ).getFromStep().getName(), tm.getTransHop( 0 ).getToStep()
      .getName() );
    noNulo( off, "el hop deshabilitado" );
    cierto( !off.activa, "inactiva" );
    contiene( off.rotulo, "deshabilitado", "rotulo deshabilitado" );
  }

  private static List<Dibujo> dibujar( Grafo g, String nombre ) throws Exception {
    File dir = EntornoKettle.salida( "diagramas-ej" );
    List<Dibujo> a4 = null;
    for ( Objetivo o : new Objetivo[] { Objetivo.A4, Objetivo.DIAPOSITIVA, Objetivo.PANTALLA, Objetivo.DOCX } ) {
      List<Dibujo> ds = Diagramas.dibujar( g, o, "es" );
      for ( Dibujo d : ds ) {
        cierto( d.anchoFinal() <= o.ancho + 0.5 && d.altoFinal() <= o.alto + 0.5, nombre + " entra en " + o + ": " + d );
        cierto( d.letra >= o.letraMin, nombre + " legible en " + o + ": " + d );
        Files.write( new File( dir, nombre + "_" + o + "_" + d.parte + ".svg" ).toPath(), d.svg.getBytes(
          StandardCharsets.UTF_8 ) );
      }
      if ( a4 == null ) {
        a4 = ds;
      }
    }
    return a4;
  }
}
