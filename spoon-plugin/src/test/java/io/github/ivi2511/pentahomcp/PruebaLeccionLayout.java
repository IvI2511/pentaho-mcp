package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.GCInterface;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.gui.PrimitiveGCInterface.EFont;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.LeccionLayout.Banda;
import io.github.ivi2511.pentahomcp.LeccionLayout.Densidad;
import io.github.ivi2511.pentahomcp.LeccionLayout.Entrada;
import io.github.ivi2511.pentahomcp.LeccionLayout.Pedida;
import io.github.ivi2511.pentahomcp.LeccionLayout.Plan;
import io.github.ivi2511.pentahomcp.LeccionLayout.Previas;
import io.github.ivi2511.pentahomcp.LeccionLayout.Tipo;
import io.github.ivi2511.pentahomcp.LeccionLayout.Ubicada;

/**
 * Modo clase, WP1: la regla de ubicacion de LeccionLayout (docs/modo-clase-diseno.md §4.2 a §4.4) con un
 * medidor de metricas fijas (7 px por letra, 8 en negrita, 15 por linea; nombres 6 x 14), asi los numeros
 * no dependen de las fuentes de la maquina. Reglas 1 a 7 de §4.3, densidad, corte de palabras, tope de
 * lineas y de pasos, y el adaptador desde un TransMeta. La verificacion de solapamientos de las pruebas es
 * propia (muestreo de los hops), no la de LeccionLayout.
 */
public class PruebaLeccionLayout {

  /** Metricas fijas: el corte y las alturas se pueden calcular a mano. */
  static final class MedidorFijo implements LeccionLayout.Medidor {
    static final int LETRA = 7;
    static final int LETRA_NEGRITA = 8;
    static final int LINEA = 15;
    static final int LETRA_NOMBRE = 6;
    static final int LINEA_NOMBRE = 14;

    @Override
    public Point texto( String texto, boolean negrita ) {
      String[] l = texto.split( "\n", -1 );
      int w = 0;
      for ( String s : l ) {
        w = Math.max( w, s.length() * ( negrita ? LETRA_NEGRITA : LETRA ) );
      }
      return new Point( w, l.length * LINEA );
    }

    @Override
    public Point nombre( String nombre ) {
      return new Point( nombre.length() * LETRA_NOMBRE, LINEA_NOMBRE );
    }
  }

  private final MedidorFijo m = new MedidorFijo();

  // ---------------------------------------------------------------- regla 1: columnas de 240

  public void pruebaTransLayoutNoCambia() {
    igual( 100, TransLayout.X0, "TransLayout.X0" );
    igual( 170, TransLayout.DX, "TransLayout.DX (el modo clase no lo toca)" );
    igual( 110, TransLayout.DY, "TransLayout.DY" );
  }

  public void pruebaRegla1ColumnasDe240() {
    Entrada e = ventasConCliente( 32 );
    Plan p = LeccionLayout.calcular( e, m );
    // lo mismo que TransLayout.calcular sobre el mismo grafo, con columnas de 240 y el flujo bajado entero
    List<int[]> aristas = new ArrayList<int[]>();
    for ( String[] h : e.hops ) {
      aristas.add( new int[] { e.steps.indexOf( h[ 0 ] ), e.steps.indexOf( h[ 1 ] ) } );
    }
    int[] col = new int[ e.steps.size() ];
    Point[] base = TransLayout.calcular( e.actuales, aristas, -1, col );
    int dy = p.posiciones[ 0 ].y - base[ 0 ].y;
    cierto( dy > 0, "con titulo y banda de arriba el flujo se baja: dy=" + dy );
    for ( int i = 0; i < col.length; i++ ) {
      String s = e.steps.get( i );
      igual( col[ i ], p.columna[ i ], "columna de '" + s + "'" );
      igual( 100 + col[ i ] * 240, p.posiciones[ i ].x, "x de '" + s + "' = 100 + col*240" );
      igual( 100 + ( base[ i ].x - 100 ) / 170 * 240, p.posiciones[ i ].x, "x de '" + s + "' desde la de TransLayout" );
      igual( base[ i ].y + dy, p.posiciones[ i ].y, "y de '" + s + "': la fila de TransLayout + dy" );
    }
    igual( 0, p.columna( "Leer ventas" ), "Leer ventas, columna 0" );
    igual( 0, p.columna( "Leer clientes" ), "Leer clientes (solo alimenta el lookup), columna 0" );
    igual( 1, p.columna( "Buscar cliente" ), "Buscar cliente, columna 1" );
    igual( 2, p.columna( "Escribir ventas" ), "Escribir ventas, columna 2" );
    igual( 3, p.columnas, "columnas" );
    sinChoques( e, p );
  }

  public void pruebaRegla1StepsQueYaExisten() {
    // steps ya creados en cualquier lado: el plan los pone en su lugar final igual que a los planificados
    Entrada e = nueva( 32 );
    e.step( "B", new Point( 900, 40 ) ).step( "A", new Point( 20, 500 ) ).step( "C", null );
    e.hop( "A", "B" ).hop( "B", "C" );
    e.nota( Tipo.PASO, "A", 1, "Paso uno" );
    e.nota( Tipo.PASO, "C", 3, "Paso tres" );
    Plan p = LeccionLayout.calcular( e, m );
    igual( 100, p.posicion( "A" ).x, "A en la columna 0" );
    igual( 340, p.posicion( "b" ).x, "B en la columna 1 (nombre sin distinguir mayusculas)" );
    igual( 580, p.posicion( "C" ).x, "C en la columna 2" );
    igual( p.posicion( "A" ).y, p.posicion( "C" ).y, "linea principal recta" );
    nulo( p.banda( "B" ), "B no lleva notas" );
    sinChoques( e, p );
  }

  // ---------------------------------------------------------------- reglas 2 y 3: columnas y bandas

  public void pruebaRegla2y3Bandas() {
    Entrada e = ventasConCliente( 32 );
    Plan p = LeccionLayout.calcular( e, m );
    igual( Banda.ARRIBA, p.banda( "Leer ventas" ), "el de mas arriba de la columna 0: arriba" );
    igual( Banda.ABAJO, p.banda( "Leer clientes" ), "el segundo de la columna 0: abajo" );
    igual( Banda.ARRIBA, p.banda( "Buscar cliente" ), "unico de la columna 1: arriba" );
    igual( Banda.ARRIBA, p.banda( "Escribir ventas" ), "unico de la columna 2: arriba" );
    cierto( p.posicion( "Leer ventas" ).y < p.posicion( "Leer clientes" ).y, "en la columna, ordenados por y" );

    int top = minY( p );
    Ubicada paso3 = nota( p, Tipo.PASO, "Buscar cliente" );
    Ubicada ojo3 = nota( p, Tipo.OJO, "Buscar cliente" );
    Ubicada tip3 = nota( p, Tipo.TIP, "Buscar cliente" );
    igual( Banda.ARRIBA, ojo3.banda, "el ojo va en la banda de su paso" );
    igual( Banda.ARRIBA, tip3.banda, "el tip va en la banda de su paso" );
    igual( top - 20, paso3.y + paso3.alto + 10, "la nota del paso queda pegada al flujo (20 px)" );
    igual( paso3.y - 12, ojo3.y + ojo3.alto + 10, "el ojo, arriba de la del paso (del lado de afuera)" );
    igual( ojo3.y - 12, tip3.y + tip3.alto + 10, "el tip, arriba del ojo (en el orden del guion)" );
    igual( paso3.x, ojo3.x, "misma x en la columna" );

    Ubicada paso2 = nota( p, Tipo.PASO, "Leer clientes" );
    Ubicada ojo2 = nota( p, Tipo.OJO, "Leer clientes" );
    igual( p.yBandaAbajo, paso2.y, "abajo: la del paso arriba de todo (pegada al flujo)" );
    igual( paso2.y + paso2.alto + 10 + 12, ojo2.y, "abajo: el ojo debajo de la del paso" );
    sinChoques( e, p );
  }

  public void pruebaRegla2FilterRows() {
    // Filter rows con dos salidas en la misma columna: la verdadera (arriba) y la falsa
    Entrada e = nueva( 32 );
    e.step( "Leer", null ).step( "Filtrar", null ).step( "Activos", null ).step( "Descartados", null );
    e.hop( "Leer", "Filtrar" ).hop( "Filtrar", "Activos" ).hop( "Filtrar", "Descartados" );
    for ( int i = 0; i < 4; i++ ) {
      e.nota( Tipo.PASO, e.steps.get( i ), i + 1, "Paso " + ( i + 1 ) + " sobre " + e.steps.get( i ) );
    }
    Plan p = LeccionLayout.calcular( e, m );
    igual( p.columna( "Activos" ), p.columna( "Descartados" ), "las dos ramas en la misma columna" );
    Banda a = p.banda( "Activos" );
    Banda d = p.banda( "Descartados" );
    String arriba = p.posicion( "Activos" ).y < p.posicion( "Descartados" ).y ? "Activos" : "Descartados";
    igual( Banda.ARRIBA, p.banda( arriba ), "la rama de mas arriba va arriba" );
    cierto( a != d, "una arriba y otra abajo: " + a + "/" + d );
    sinChoques( e, p );
  }

  // ---------------------------------------------------------------- regla 4: x de la nota

  public void pruebaRegla4XDeLaNota() {
    Plan p = LeccionLayout.calcular( ventasConCliente( 32 ), m );
    igual( 10, nota( p, Tipo.PASO, "Leer ventas" ).x, "columna 0: 100 + 16 - 110 = 6, minimo 10" );
    igual( 10, nota( p, Tipo.PASO, "Leer clientes" ).x, "columna 0, banda de abajo: tambien 10" );
    igual( 340 + 16 - 110, nota( p, Tipo.PASO, "Buscar cliente" ).x, "columna 1: centro - 110" );
    igual( 580 + 16 - 110, nota( p, Tipo.PASO, "Escribir ventas" ).x, "columna 2: centro - 110" );
    igual( 220, nota( p, Tipo.PASO, "Buscar cliente" ).ancho, "ancho fijo de 220" );
    igual( 10, nota( p, Tipo.TITULO, null ).x, "el titulo, alineado con la columna 0" );
    igual( 220 + 2 * 240, nota( p, Tipo.TITULO, null ).ancho, "titulo de tres columnas de ancho" );
  }

  public void pruebaRegla4IconosChicosYGrandes() {
    // 16 px: la nota de la columna 0 se topa con el minimo; el flujo se corre para que no se pise con la 1
    Plan chico = LeccionLayout.calcular( ventasConCliente( 16 ), m );
    igual( 106, chico.posicion( "Leer ventas" ).x, "con iconos de 16 la columna 0 va en 106" );
    igual( 106 + 240, chico.posicion( "Buscar cliente" ).x, "y la 1, 240 mas" );
    for ( Ubicada u : chico.notas ) {
      cierto( u.banda != Banda.LATERAL, "con iconos de 16 nada va a la columna lateral: " + u.pedida.step );
    }
    sinChoques( ventasConCliente( 16 ), chico );

    Plan grande = LeccionLayout.calcular( ventasConCliente( 48 ), m );
    igual( 48, grande.icono, "icono de 48" );
    igual( 100, grande.posicion( "Leer ventas" ).x, "con iconos de 48 la columna 0 sigue en 100" );
    igual( 100 + 24 - 110, nota( grande, Tipo.PASO, "Leer ventas" ).x, "columna 0 con icono de 48: 14" );
    igual( 340 + 24 - 110, nota( grande, Tipo.PASO, "Buscar cliente" ).x, "columna 1 con icono de 48" );
    sinChoques( ventasConCliente( 48 ), grande );
  }

  // ---------------------------------------------------------------- regla 5: notas que ya habia

  public void pruebaRegla5NotasPreviasApiladas() {
    Entrada e = ventasConCliente( 32 );
    Object[] refs = new Object[ 5 ];
    int[][] tam = { { 200, 40 }, { 300, 60 }, { 100, 30 }, { 500, 20 }, { 50, 50 } };
    for ( int i = 0; i < 5; i++ ) {
      refs[ i ] = "nota " + i;
      e.previa( refs[ i ], 700 + i * 30, 900, tam[ i ][ 0 ], tam[ i ][ 1 ] );
    }
    Plan p = LeccionLayout.calcular( e, m );
    // fila 1: 10, 10+210+12=232, 232+310+12=554; la 4ta no entra en 4 columnas (970 px): fila 2
    punto( 10, 20, p.previas[ 0 ], "previa 0" );
    punto( 232, 20, p.previas[ 1 ], "previa 1" );
    punto( 554, 20, p.previas[ 2 ], "previa 2" );
    int fila2 = 20 + 70 + 12;
    punto( 10, fila2, p.previas[ 3 ], "previa 3, fila 2" );
    punto( 10 + 510 + 12, fila2, p.previas[ 4 ], "previa 4, fila 2" );
    Ubicada titulo = nota( p, Tipo.TITULO, null );
    igual( fila2 + 60 + 20, titulo.y, "el titulo va debajo de las previas" );
    contiene( String.join( "\n", p.avisos ), "5 existing notes were stacked at the top left", "aviso de las previas" );
    sinChoques( e, p );

    // hasta 4 por fila aunque entren mas
    Entrada chicas = nueva( 32 );
    chicas.step( "A", null );
    for ( int i = 0; i < 6; i++ ) {
      chicas.previa( "c" + i, 0, 0, 20, 20 );
    }
    Plan pc = LeccionLayout.calcular( chicas, m );
    igual( 20, pc.previas[ 3 ].y, "la 4ta todavia en la fila 1" );
    punto( 10, 20 + 30 + 12, pc.previas[ 4 ], "la 5ta abre la fila 2" );
    contiene( String.join( "\n", pc.avisos ), "6 existing notes", "aviso" );
  }

  public void pruebaRegla5NotasPreviasDejadas() {
    Entrada e = ventasConCliente( 32 );
    e.modoPrevias = Previas.DEJAR;
    e.previa( "lejos", 2000, 1500, 150, 40 );
    Plan p = LeccionLayout.calcular( e, m );
    punto( 2000, 1500, p.previas[ 0 ], "keep: la previa no se mueve" );
    igual( 20, nota( p, Tipo.TITULO, null ).y, "keep: el titulo arriba de todo" );
    for ( String a : p.avisos ) {
      falso( a.contains( "stacked" ), "keep no avisa de apilar: " + a );
    }
    igual( Previas.DEJAR, Previas.deJson( "keep" ), "deJson keep" );
    igual( Previas.APILAR, Previas.deJson( " STACK_TOP " ), "deJson stack_top" );
    igual( Previas.APILAR, Previas.deJson( null ), "deJson null" );
    nulo( Previas.deJson( "otra" ), "deJson invalido" );
  }

  // ---------------------------------------------------------------- regla 6: alturas

  public void pruebaRegla6Alturas() {
    for ( int icono : new int[] { 32, 48 } ) {
      Entrada e = ventasConCliente( icono );
      Plan p = LeccionLayout.calcular( e, m );
      Ubicada titulo = nota( p, Tipo.TITULO, null );
      igual( 20, titulo.y, "titulo en y=20 (icono " + icono + ")" );
      igual( 2 * 15 + 10, titulo.alto, "titulo de 2 lineas en negrita (icono " + icono + ")" );
      igual( 20 + titulo.alto + 10 + 20, p.yBandaArriba, "banda de arriba 20 px debajo del titulo dibujado" );
      int altoBanda = 0;
      for ( String s : new String[] { "Leer ventas", "Buscar cliente", "Escribir ventas" } ) {
        int h = -12;
        for ( Ubicada u : p.notas ) {
          if ( s.equals( u.pedida.step ) ) {
            h += u.alto + 10 + 12;
          }
        }
        altoBanda = Math.max( altoBanda, h );
      }
      igual( p.yBandaArriba + altoBanda + 20, minY( p ), "flujo = banda de arriba + su alto + 20 (icono " + icono + ")" );
      int altoNombre = MedidorFijo.LINEA_NOMBRE;
      igual( maxY( p ) + icono + 5 + altoNombre + 25, p.yBandaAbajo,
        "banda de abajo = y max + icono + 5 + nombre + 25 (icono " + icono + ")" );
      igual( p.yBandaAbajo, nota( p, Tipo.PASO, "Leer clientes" ).y, "la nota de abajo empieza ahi" );
      sinChoques( e, p );
    }
  }

  public void pruebaRegla6SinTituloNiBandaElFlujoNoSeMueve() {
    Entrada e = nueva( 32 );
    e.step( "A", null ).step( "B", null ).step( "C", null );
    e.hop( "A", "B" ).hop( "A", "C" );
    e.nota( Tipo.PASO, "C", 1, "Solo la rama de abajo" );
    Plan p = LeccionLayout.calcular( e, m );
    // B esta arriba de C pero no tiene notas: C es el de mas arriba de los anotados -> banda de arriba
    igual( Banda.ARRIBA, p.banda( "C" ), "el de mas arriba entre los anotados" );
    igual( 20, p.yBandaArriba, "sin titulo ni previas la banda empieza en 20" );
    Ubicada u = nota( p, Tipo.PASO, "C" );
    igual( minY( p ) - 20, u.y + u.alto + 10, "pegada al flujo" );
    cierto( u.y >= 20, "y la nota no se sale por arriba: " + u.y );
    sinChoques( e, p );
  }

  // ---------------------------------------------------------------- regla 7: verificacion y columna lateral

  public void pruebaRegla7SinChoquesEnUnFlujoMediano() {
    Entrada e = nueva( 32 );
    e.step( "Generar filas", null ).step( "Leer ventas", null ).step( "Leer clientes", null )
      .step( "Calcular importe", null ).step( "Buscar cliente", null ).step( "Filtrar activos", null )
      .step( "Ordenar por producto", null ).step( "Totales por producto", null ).step( "Escribir totales", null )
      .step( "Descartados", null );
    e.hop( "Leer ventas", "Calcular importe" ).hop( "Calcular importe", "Buscar cliente" )
      .hop( "Leer clientes", "Buscar cliente" ).hop( "Buscar cliente", "Filtrar activos" )
      .hop( "Filtrar activos", "Ordenar por producto" ).hop( "Filtrar activos", "Descartados" )
      .hop( "Ordenar por producto", "Totales por producto" ).hop( "Totales por producto", "Escribir totales" )
      .hop( "Generar filas", "Escribir totales" );
    e.nota( Tipo.TITULO, null, 0, "Lección · Agregar sin mentir\nNivel intermedio · 9 pasos · Sort rows + Group by" );
    int n = 1;
    for ( String s : e.steps ) {
      e.nota( Tipo.PASO, s, n++, n + " · " + s + "\nQué: hace lo suyo con las filas que le llegan.\n"
        + "Por qué: porque el ejercicio lo pide y así se ve el resultado." );
    }
    e.nota( Tipo.OJO, "Totales por producto", 8, "¡Ojo! (paso 8) Group by sin ordenar antes no avisa: da grupos de más." );
    e.nota( Tipo.TIP, "Ordenar por producto", 7, "Tip (paso 7): con muchas filas usa archivos temporales." );
    Plan p = LeccionLayout.calcular( e, m );
    for ( Ubicada u : p.notas ) {
      cierto( u.banda != Banda.LATERAL, "nada a la columna lateral: " + u.pedida.step + " " + u.motivo );
    }
    igual( -1, p.xLateral, "sin columna lateral" );
    sinChoques( e, p );
  }

  public void pruebaRegla7ChoqueVaALaColumnaLateral() {
    Plan limpio = LeccionLayout.calcular( ventasConCliente( 32 ), m );
    Ubicada donde = nota( limpio, Tipo.PASO, "Buscar cliente" );
    Ubicada dondeFin = nota( limpio, Tipo.PASO, "Escribir ventas" );

    // una nota del usuario (keep) justo donde iria la del paso 3, y otra que tapa la del paso 4
    Entrada e = ventasConCliente( 32 );
    e.modoPrevias = Previas.DEJAR;
    e.previa( "usuario 1", donde.x, donde.y, donde.ancho, donde.alto );
    e.previa( "usuario 2", dondeFin.x + 30, dondeFin.y + 5, 60, 20 );
    Plan p = LeccionLayout.calcular( e, m );
    Ubicada paso3 = nota( p, Tipo.PASO, "Buscar cliente" );
    Ubicada paso4 = nota( p, Tipo.PASO, "Escribir ventas" );
    igual( Banda.LATERAL, paso3.banda, "la nota que corta a otra va a la columna lateral" );
    igual( Banda.LATERAL, paso4.banda, "la segunda tambien" );
    igual( Banda.LATERAL, p.banda( "Buscar cliente" ), "bandaStep del step" );
    contiene( paso3.motivo, "an existing note", "motivo" );
    igual( Banda.ARRIBA, nota( p, Tipo.OJO, "Buscar cliente" ).banda, "el ojo no cortaba nada: se queda" );
    cierto( p.xLateral > 0, "hay columna lateral" );
    igual( p.xLateral, paso3.x, "x de la lateral" );
    igual( p.xLateral, paso4.x, "las dos en la misma columna lateral" );
    int derecha = 0;
    for ( int i = 0; i < p.steps.size(); i++ ) {
      derecha = Math.max( derecha, p.posiciones[ i ].x + p.icono );
      int w = p.steps.get( i ).length() * MedidorFijo.LETRA_NOMBRE;
      derecha = Math.max( derecha, p.posiciones[ i ].x + p.icono / 2 - w / 2 + w );
    }
    for ( Ubicada u : p.notas ) {
      if ( u.banda == Banda.ARRIBA || u.banda == Banda.ABAJO ) {
        derecha = Math.max( derecha, u.x + u.ancho + 10 );
      }
    }
    igual( derecha + 40, p.xLateral, "la lateral, 40 px a la derecha de todo el flujo y sus notas" );
    cierto( paso4.y >= paso3.y + paso3.alto + 10 + 4, "apiladas sin pisarse: " + paso3.y + " / " + paso4.y );
    String avisos = String.join( "\n", p.avisos );
    contiene( avisos, "The step note of step 'Buscar cliente' overlapped an existing note: moved to a side column",
      "aviso de la lateral" );
    sinChoques( e, p );
  }

  public void pruebaRegla7ElTituloTambien() {
    Entrada e = ventasConCliente( 32 );
    e.modoPrevias = Previas.DEJAR;
    e.previa( "usuario", 100, 25, 100, 20 );
    Plan p = LeccionLayout.calcular( e, m );
    Ubicada t = nota( p, Tipo.TITULO, null );
    igual( Banda.LATERAL, t.banda, "el titulo que corta una nota del usuario va a la lateral" );
    contiene( String.join( "\n", p.avisos ), "The title note overlapped an existing note", "aviso del titulo" );
    sinChoques( e, p );
  }

  // ---------------------------------------------------------------- densidad (§4.4)

  public void pruebaDensidadNotaCompacta() {
    Entrada e = abanico( 3, Densidad.COMPACTA );
    e.nota( Tipo.OJO, "B1", 2, "¡Ojo! (paso 2) en una columna densa va a la pestaña" );
    Plan p = LeccionLayout.calcular( e, m );
    Ubicada b1 = nota( p, Tipo.PASO, "B1" );
    igual( Banda.COMPACTA, b1.banda, "B1: compacta" );
    igual( "2", b1.texto, "la compacta lleva solo el numero del paso" );
    cierto( b1.negrita, "en negrita" );
    igual( 8 + 10, b1.ancho, "ancho de la compacta: el numero medido + margenes" );
    igual( 15 + 10, b1.alto, "alto de la compacta" );
    Point icono = p.posicion( "B1" );
    igual( icono.x + 32 + 6, b1.x, "arriba a la derecha del icono (6 px de aire)" );
    igual( icono.y + 16 - 8, b1.y + b1.alto + 10, "su borde de abajo, 8 px arriba del centro del icono" );
    Ubicada ojo = nota( p, Tipo.OJO, "B1" );
    igual( Banda.FUERA, ojo.banda, "el ojo de una columna densa queda fuera del lienzo" );
    nulo( ojo.texto, "sin texto de lienzo" );
    igual( -1, ojo.distancia, "sin distancia" );
    // B2 y B3: sus hops a C suben en diagonal y pasan por donde iria la compacta
    Ubicada b2 = nota( p, Tipo.PASO, "B2" );
    Ubicada b3 = nota( p, Tipo.PASO, "B3" );
    igual( Banda.FUERA, b2.banda, "B2: la compacta cortaba un hop, fuera del lienzo" );
    igual( Banda.FUERA, b3.banda, "B3 igual" );
    contiene( b3.motivo, "the hop 'B3' -> 'C'", "motivo de B3" );
    igual( Banda.FUERA, p.banda( "B3" ), "bandaStep de B3" );
    String avisos = String.join( "\n", p.avisos );
    contiene( avisos, "Steps 'B1', 'B2', 'B3' share a column: only their step number goes on the canvas",
      "aviso de densidad" );
    contiene( avisos, "The compact note of step 'B3' overlapped the hop 'B3' -> 'C'", "aviso de la compacta" );
    // la compacta de B1 sale por encima del icono: el flujo deja lugar y no pisa la nota de R
    sinChoques( e, p );
  }

  public void pruebaDensidadSinNota() {
    Entrada e = abanico( 4, Densidad.SIN_NOTA );
    Plan p = LeccionLayout.calcular( e, m );
    for ( int i = 1; i <= 4; i++ ) {
      igual( Banda.FUERA, nota( p, Tipo.PASO, "B" + i ).banda, "B" + i + " fuera del lienzo" );
      igual( Banda.FUERA, p.banda( "B" + i ), "bandaStep B" + i );
    }
    igual( Banda.ARRIBA, nota( p, Tipo.PASO, "R" ).banda, "R (columna de 1) con su nota completa" );
    igual( Banda.ARRIBA, nota( p, Tipo.PASO, "C" ).banda, "C con su nota completa" );
    contiene( String.join( "\n", p.avisos ), "their notes stay off the canvas", "aviso de densidad sin nota" );
    sinChoques( e, p );
  }

  public void pruebaDosPorColumnaNoEsDensa() {
    Entrada e = abanico( 2, Densidad.COMPACTA );
    Plan p = LeccionLayout.calcular( e, m );
    igual( Banda.ARRIBA, p.banda( "B1" ), "dos por columna: nota completa arriba" );
    igual( Banda.ABAJO, p.banda( "B2" ), "y abajo" );
    sinChoques( e, p );
  }

  public void pruebaNotaLejosDeSuStep() {
    // columna de 5 steps con solo 2 anotados: la del segundo queda debajo del quinto
    Entrada e = nueva( 32 );
    e.step( "R", null );
    for ( int i = 1; i <= 5; i++ ) {
      e.step( "B" + i, null );
      e.hop( "R", "B" + i );
    }
    e.nota( Tipo.PASO, "B1", 1, "Primero" );
    e.nota( Tipo.PASO, "B2", 2, "Segundo" );
    Plan p = LeccionLayout.calcular( e, m );
    Ubicada b2 = nota( p, Tipo.PASO, "B2" );
    igual( Banda.ABAJO, b2.banda, "B2 abajo" );
    int esperado = p.yBandaAbajo - ( p.posicion( "B2" ).y + 32 );
    igual( esperado, b2.distancia, "distancia nota - icono" );
    cierto( b2.distancia > 300, "mas de 300 px: " + b2.distancia );
    contiene( String.join( "\n", p.avisos ), "1 note ends up more than 300 px from its step", "aviso de distancia" );
    cierto( nota( p, Tipo.PASO, "B1" ).distancia <= 300, "B1 cerca" );
  }

  public void pruebaMasDeDocePasos() {
    for ( int n : new int[] { 12, 13 } ) {
      Entrada e = nueva( 32 );
      for ( int i = 1; i <= n; i++ ) {
        e.step( "S" + i, null );
        if ( i > 1 ) {
          e.hop( "S" + ( i - 1 ), "S" + i );
        }
        e.nota( Tipo.PASO, "S" + i, i, "Paso " + i );
      }
      Plan p = LeccionLayout.calcular( e, m );
      String avisos = String.join( "\n", p.avisos );
      if ( n == 12 ) {
        falso( avisos.contains( "more than 12" ), "12 pasos no avisan: " + avisos );
      } else {
        contiene( avisos, "The lesson has 13 steps (more than 12): consider splitting it into stages", "aviso" );
      }
      sinChoques( e, p );
    }
  }

  // ---------------------------------------------------------------- texto (§4.2)

  public void pruebaCortePorPalabras() {
    // 7 px por letra: en 100 px entran 14
    igual( Arrays.asList( "uno dos tres", "cuatro cinco", "seis siete" ),
      LeccionLayout.envolver( m, "uno dos tres cuatro cinco seis siete", false, 100 ), "por palabras" );
    igual( Arrays.asList( "uno dos", "tres" ), LeccionLayout.envolver( m, "uno dos tres", true, 64 ),
      "en negrita mide con 8 px" );
    igual( Arrays.asList( "a", "", "b c" ), LeccionLayout.envolver( m, "a\r\n\r\nb  \t c", false, 100 ),
      "respeta los saltos (tambien \\r\\n) y junta los espacios" );
    igual( Arrays.asList( "" ), LeccionLayout.envolver( m, "", false, 100 ), "vacio" );
    String largo = "Por qué: el Stream lookup carga entero en memoria el flujo de búsqueda, así que con millones de"
      + " filas conviene un Database lookup o un Merge join con las dos entradas ordenadas por la clave.";
    for ( String l : LeccionLayout.envolver( m, largo, false, LeccionLayout.ANCHO_TEXTO ) ) {
      cierto( m.texto( l, false ).x <= LeccionLayout.ANCHO_TEXTO, "cada linea entra en 208 px: '" + l + "'" );
    }
  }

  public void pruebaCortePalabrasLargas() {
    igual( Arrays.asList( "datos/entrada/", "ventas_2026_", "octubre.csv" ),
      LeccionLayout.envolver( m, "datos/entrada/ventas_2026_octubre.csv", false, 100 ), "despues de / y de _" );
    igual( Arrays.asList( "Ruta:", "C:\\datos\\", "entrada\\", "ventas.csv" ),
      LeccionLayout.envolver( m, "Ruta: C:\\datos\\entrada\\ventas.csv", false, 70 ), "despues de \\" );
    igual( Arrays.asList( "org.pentaho.", "di.trans.steps" ),
      LeccionLayout.envolver( m, "org.pentaho.di.trans.steps", false, 100 ), "despues de ." );
    igual( Arrays.asList( "abcdefghijklmn", "opqrstuvwxyz" ),
      LeccionLayout.envolver( m, "abcdefghijklmnopqrstuvwxyz", false, 100 ), "sin separador: por letra" );
    igual( Arrays.asList( "lee", "datos/", "ventas.csv", "y sigue" ),
      LeccionLayout.envolver( m, "lee datos/ventas.csv y sigue", false, 70 ), "la palabra larga empieza linea nueva" );
    igual( 1, LeccionLayout.cortePalabra( m, "xyz", false, 3 ), "si no entra ni una letra, una por linea" );
    igual( Arrays.asList( "x", "y", "z" ), LeccionLayout.envolver( m, "xyz", false, 3 ), "y no se cuelga" );
  }

  public void pruebaMaximoSeisLineas() {
    Entrada e = nueva( 32 );
    e.step( "Leer", null );
    StringBuilder t = new StringBuilder( "1 · Leer (CSV file input)" );
    for ( int i = 0; i < 12; i++ ) {
      t.append( "\nLínea número " ).append( i ).append( " del qué y del por qué" );
    }
    e.nota( Tipo.PASO, "Leer", 1, t.toString() );
    Plan p = LeccionLayout.calcular( e, m );
    Ubicada u = nota( p, Tipo.PASO, "Leer" );
    String[] lineas = u.texto.split( "\n", -1 );
    igual( 6, lineas.length, "maximo 6 lineas en el lienzo" );
    cierto( u.recortada, "marcada como recortada" );
    cierto( lineas[ 5 ].endsWith( "\u2026" ), "la ultima termina en ...: " + lineas[ 5 ] );
    for ( String l : lineas ) {
      cierto( m.texto( l, false ).x <= LeccionLayout.ANCHO_TEXTO, "cada linea entra: " + l );
    }
    igual( 6 * 15 + 10, u.alto, "alto de 6 lineas + margenes" );
    contiene( String.join( "\n", p.avisos ), "The step note of step 'Leer' was cut to 6 lines", "aviso del recorte" );

    // una linea que ya llena el ancho: los puntos suspensivos le quitan letras para entrar
    Entrada e2 = nueva( 32 );
    e2.step( "Leer", null );
    e2.nota( Tipo.PASO, "Leer", 1, "a\nb\nc\nd\ne\nabcdefghijklmnopqrstuvwxyzabc\ng" );
    Ubicada u2 = nota( LeccionLayout.calcular( e2, m ), Tipo.PASO, "Leer" );
    igual( "abcdefghijklmnopqrstuvwxyzab\u2026", u2.texto.split( "\n" )[ 5 ], "recorta la ultima para los puntos" );
  }

  // ---------------------------------------------------------------- medidor, adaptador y errores

  public void pruebaMedidorGc() {
    final List<String> llamadas = new ArrayList<String>();
    GCInterface gc = (GCInterface) Proxy.newProxyInstance( GCInterface.class.getClassLoader(),
      new Class<?>[] { GCInterface.class }, new InvocationHandler() {
        private boolean negrita;
        private boolean grafico;

        @Override
        public Object invoke( Object proxy, Method metodo, Object[] args ) {
          if ( metodo.getName().equals( "setFont" ) ) {
            llamadas.add( "setFont" + Arrays.toString( args ) );
            grafico = args.length == 1 && args[ 0 ] == EFont.GRAPH;
            negrita = args.length == 4 && Boolean.TRUE.equals( args[ 2 ] );
            return null;
          }
          if ( metodo.getName().equals( "textExtent" ) ) {
            int letra = grafico ? 6 : negrita ? 8 : 7;
            return new Point( ( (String) args[ 0 ] ).length() * letra, 15 );
          }
          return null;
        }
      } );
    LeccionLayout.MedidorGc med = new LeccionLayout.MedidorGc( gc, "Segoe UI", 14 );
    punto( 32, 15, med.texto( "hola", true ), "texto en negrita" );
    igual( "setFont[Segoe UI, 14, true, false]", llamadas.get( 0 ), "fuente de notas, tamano pedido, negrita" );
    punto( 28, 15, med.texto( "hola", false ), "texto normal" );
    punto( 24, 15, med.nombre( "hola" ), "nombre con la fuente del lienzo" );
    igual( "setFont[GRAPH]", llamadas.get( 2 ), "EFont.GRAPH para los nombres" );
    cierto( LeccionLayout.tamIcono() > 0, "tamIcono sin PropsUI: " + LeccionLayout.tamIcono() );
  }

  public void pruebaDesdeTransMeta() throws Exception {
    TransMeta tm = EntornoKettle.trans( "04" );
    NotePadMeta usuario = new NotePadMeta( "Nota del usuario", 40, 40, 30, 30 );
    NotePadMeta deLeccion = new NotePadMeta( "1 · ya era de la leccion", 400, 400, 220, 40 );
    tm.addNote( usuario );
    tm.addNote( deLeccion );
    Entrada e = LeccionLayout.desde( tm, m, Collections.singletonList( deLeccion ) );
    e.icono = 32;
    igual( tm.nrSteps(), e.steps.size(), "todos los steps" );
    igual( tm.nrTransHops(), e.hops.size(), "todos los hops" );
    igual( tm.nrNotes() - 1, e.previas.size(), "las notas de la leccion no van como previas" );
    LeccionLayout.Previa pu = null;
    for ( LeccionLayout.Previa pr : e.previas ) {
      if ( pr.ref == usuario ) {
        pu = pr;
      }
    }
    noNulo( pu, "la nota del usuario es previa" );
    igual( "Nota del usuario".length() * 7 + 10, pu.ancho, "ancho: el texto medido manda sobre width=30" );
    igual( 30, pu.alto, "alto: width/height del painter si es mayor (30 > 15 + 10)" );

    int n = 1;
    e.nota( Tipo.TITULO, null, 0, "Lección · Cada venta con su cliente\nNivel principiante · Stream lookup" );
    for ( String s : e.steps ) {
      e.nota( Tipo.PASO, s, n, n + " · " + s + "\nQué: lo que hace este step con cada fila.\nPor qué: lo que aporta." );
      n++;
    }
    e.nota( Tipo.OJO, e.steps.get( 0 ), 1, "¡Ojo! (paso 1) <decimal>.</decimal> vacío y Windows en español: 2.50 → 250." );
    Plan p = LeccionLayout.calcular( e, m );
    for ( Ubicada u : p.notas ) {
      cierto( u.banda != Banda.LATERAL && u.banda != Banda.FUERA, "en una banda: " + u.pedida.step + " " + u.banda );
    }
    sinChoques( e, p );
  }

  public void pruebaErrores() {
    lanza( IllegalArgumentException.class, () -> {
      Entrada e = nueva( 32 );
      e.step( "Leer", null ).step( " leer ", null );
      LeccionLayout.calcular( e, m );
    }, "nombres repetidos (sin distinguir mayusculas)" );
    lanza( IllegalArgumentException.class, () -> {
      Entrada e = nueva( 32 );
      e.step( "A", null ).hop( "A", "B" );
      LeccionLayout.calcular( e, m );
    }, "hop a un step que no esta" );
    IllegalArgumentException x = lanza( IllegalArgumentException.class, () -> {
      Entrada e = nueva( 32 );
      e.step( "A", null );
      e.nota( Tipo.PASO, "Fantasma", 1, "x" );
      LeccionLayout.calcular( e, m );
    }, "nota de un step que no esta" );
    contiene( x.getMessage(), "'Fantasma'", "el mensaje nombra el step" );
    lanza( IllegalArgumentException.class, () -> {
      Entrada e = nueva( 32 );
      e.step( "A", null );
      e.nota( Tipo.TITULO, null, 0, "uno" );
      e.nota( Tipo.TITULO, null, 0, "dos" );
      LeccionLayout.calcular( e, m );
    }, "dos titulos" );
    lanza( IllegalArgumentException.class, () -> {
      Entrada e = nueva( 32 );
      e.step( "A", null );
      e.nota( Tipo.OJO, null, 1, "sin step" );
      LeccionLayout.calcular( e, m );
    }, "un ojo sin step" );
  }

  public void pruebaSoloTitulo() {
    Entrada e = nueva( 32 );
    e.nota( Tipo.TITULO, null, 0, "Lección vacía" );
    Plan p = LeccionLayout.calcular( e, m );
    Ubicada t = nota( p, Tipo.TITULO, null );
    igual( Banda.TITULO, t.banda, "titulo" );
    punto( 10, 20, new Point( t.x, t.y ), "arriba a la izquierda" );
    igual( 220, t.ancho, "sin columnas, ancho de una nota" );
  }

  // ---------------------------------------------------------------- ayudas

  private static Entrada nueva( int icono ) {
    Entrada e = new Entrada();
    e.icono = icono;
    return e;
  }

  /** La transformacion de 04_ventas_con_cliente, planificada desde cero, con notas como las de L3. */
  private static Entrada ventasConCliente( int icono ) {
    Entrada e = nueva( icono );
    e.step( "Leer ventas", null ).step( "Leer clientes", null ).step( "Buscar cliente", null )
      .step( "Escribir ventas", null );
    e.hop( "Leer ventas", "Buscar cliente" ).hop( "Leer clientes", "Buscar cliente" )
      .hop( "Buscar cliente", "Escribir ventas" );
    e.nota( Tipo.TITULO, null, 0, "Lección · Cada venta con su cliente\nNivel principiante · 4 pasos · Stream lookup" );
    e.nota( Tipo.PASO, "Leer ventas", 1, "1 · Leer ventas (CSV file input)\nQué: lee datos/ventas.csv, una fila por"
      + " venta.\nPor qué: es el flujo principal." );
    e.nota( Tipo.PASO, "Leer clientes", 2, "2 · Leer clientes (CSV file input)\nQué: lee datos/clientes.csv.\n"
      + "Por qué: es la tabla donde se busca." );
    e.nota( Tipo.OJO, "Leer clientes", 2, "¡Ojo! (paso 2) La ruta relativa sin variable depende de la carpeta"
      + " desde la que se lanza Spoon." );
    e.nota( Tipo.PASO, "Buscar cliente", 3, "3 · Buscar cliente (Stream lookup)\nQué: por cada venta busca su"
      + " cliente.\nPor qué: une dos fuentes sin base de datos." );
    e.nota( Tipo.OJO, "Buscar cliente", 3, "¡Ojo! (paso 3) En <from> va el nombre exacto de «Leer clientes»." );
    e.nota( Tipo.TIP, "Buscar cliente", 3, "Tip (paso 3): el flujo de búsqueda va entero a memoria." );
    e.nota( Tipo.PASO, "Escribir ventas", 4, "4 · Escribir ventas (Text file output)\nQué: escribe el resultado." );
    return e;
  }

  /** R -> B1..Bk (una columna de k) -> C: los hops de B2.. a C suben en diagonal. */
  private static Entrada abanico( int k, Densidad d ) {
    Entrada e = nueva( 32 );
    e.densidad = d;
    e.step( "R", null );
    for ( int i = 1; i <= k; i++ ) {
      e.step( "B" + i, null );
    }
    e.step( "C", null );
    for ( int i = 1; i <= k; i++ ) {
      e.hop( "R", "B" + i ).hop( "B" + i, "C" );
    }
    e.nota( Tipo.PASO, "R", 1, "1 · R (Generate rows)\nQué: genera filas de prueba." );
    for ( int i = 1; i <= k; i++ ) {
      e.nota( Tipo.PASO, "B" + i, i + 1, ( i + 1 ) + " · B" + i + "\nQué: una rama." );
    }
    e.nota( Tipo.PASO, "C", k + 2, ( k + 2 ) + " · C (Dummy)\nQué: junta las ramas." );
    return e;
  }

  private static Ubicada nota( Plan p, Tipo t, String step ) {
    for ( Ubicada u : p.notas ) {
      if ( u.pedida.tipo == t && ( step == null ? u.pedida.step == null : step.equals( u.pedida.step ) ) ) {
        return u;
      }
    }
    falla( "no hay nota " + t + " de " + step );
    return null;
  }

  /** Point de Kettle no tiene equals. */
  private static void punto( int x, int y, Point real, String msg ) {
    noNulo( real, msg );
    igual( x + "," + y, real.x + "," + real.y, msg );
  }

  private static int minY( Plan p ) {
    int y = Integer.MAX_VALUE;
    for ( Point q : p.posiciones ) {
      y = Math.min( y, q.y );
    }
    return y;
  }

  private static int maxY( Plan p ) {
    int y = Integer.MIN_VALUE;
    for ( Point q : p.posiciones ) {
      y = Math.max( y, q.y );
    }
    return y;
  }

  /**
   * Verificacion propia de la regla 7 (no la de LeccionLayout): ninguna nota dibujada (+4 px) corta un icono,
   * un nombre, una previa, otra nota o un hop (muestreado de centro a centro). Ademas, nada con x o y negativa.
   */
  private void sinChoques( Entrada e, Plan p ) {
    int ic = p.icono;
    List<int[]> fijos = new ArrayList<int[]>();
    List<String> quienes = new ArrayList<String>();
    for ( int i = 0; i < p.steps.size(); i++ ) {
      Point q = p.posiciones[ i ];
      fijos.add( new int[] { q.x, q.y, ic, ic } );
      quienes.add( "icono " + p.steps.get( i ) );
      Point ext = m.nombre( p.steps.get( i ) );
      fijos.add( new int[] { q.x + ic / 2 - ext.x / 2, q.y + ic + 5, ext.x, ext.y } );
      quienes.add( "nombre " + p.steps.get( i ) );
    }
    for ( int i = 0; i < p.previas.length; i++ ) {
      LeccionLayout.Previa pr = e.previas.get( i );
      fijos.add( new int[] { p.previas[ i ].x, p.previas[ i ].y, pr.ancho + 10, pr.alto + 10 } );
      quienes.add( "previa " + i );
    }
    List<int[]> hops = new ArrayList<int[]>();
    for ( String[] h : e.hops ) {
      Point a = p.posicion( h[ 0 ] );
      Point b = p.posicion( h[ 1 ] );
      hops.add( new int[] { a.x + ic / 2, a.y + ic / 2, b.x + ic / 2, b.y + ic / 2 } );
    }
    List<Ubicada> enLienzo = new ArrayList<Ubicada>();
    for ( Ubicada u : p.notas ) {
      if ( u.enLienzo() ) {
        enLienzo.add( u );
        cierto( u.x >= 0 && u.y >= 0, "nota dentro del lienzo: " + u.pedida.step + " en " + u.x + "," + u.y );
        cierto( u.texto != null && !u.texto.isEmpty(), "nota con texto: " + u.pedida.step );
      }
    }
    for ( int k = 0; k < enLienzo.size(); k++ ) {
      Ubicada u = enLienzo.get( k );
      int[] r = { u.x - 4, u.y - 4, u.ancho + 10 + 8, u.alto + 10 + 8 };
      String quien = u.pedida.tipo + " " + u.pedida.step + " " + Arrays.toString( u.rect() );
      for ( int i = 0; i < fijos.size(); i++ ) {
        falso( corta( r, fijos.get( i ) ), "choque de " + quien + " con " + quienes.get( i ) );
      }
      for ( int j = 0; j < enLienzo.size(); j++ ) {
        if ( j != k ) {
          falso( corta( r, enLienzo.get( j ).rect() ), "choque de " + quien + " con la nota "
            + enLienzo.get( j ).pedida.tipo + " " + enLienzo.get( j ).pedida.step );
        }
      }
      for ( int[] h : hops ) {
        for ( int t = 0; t <= 400; t++ ) {
          double x = h[ 0 ] + ( h[ 2 ] - h[ 0 ] ) * t / 400.0;
          double y = h[ 1 ] + ( h[ 3 ] - h[ 1 ] ) * t / 400.0;
          if ( x >= r[ 0 ] && x <= r[ 0 ] + r[ 2 ] && y >= r[ 1 ] && y <= r[ 1 ] + r[ 3 ] ) {
            falla( "choque de " + quien + " con el hop " + Arrays.toString( h ) );
          }
        }
      }
    }
  }

  private static boolean corta( int[] a, int[] b ) {
    return a[ 0 ] < b[ 0 ] + b[ 2 ] && b[ 0 ] < a[ 0 ] + a[ 2 ] && a[ 1 ] < b[ 1 ] + b[ 3 ] && b[ 1 ] < a[ 1 ] + a[ 3 ];
  }
}
