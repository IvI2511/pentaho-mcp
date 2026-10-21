package io.github.ivi2511.pentahomcp.informes.diagramas;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.EntornoKettle;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Arista;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Grupo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;

/**
 * Motor de diagramas propios (informes.diagramas): cruces en grafos conocidos, proporcion de las cadenas
 * largas envueltas en bandas, sentido de lectura de la envoltura, linaje partido, determinismo, rotulos que
 * no tapan nodos, SVG apto para Batik/IE11 y PNG. Deja los SVG en {@code <prueba>\salida\diagramas} con
 * una pagina {@code diagramas.html} para mirarlos.
 *
 * @dueño WP6
 */
public class PruebaDiagramas {

  private final Map<String, String> muestras = new LinkedHashMap<>();

  public void cerrar() throws Exception {
    if ( muestras.isEmpty() ) {
      return;
    }
    File dir = EntornoKettle.salida( "diagramas" );
    StringBuilder sb = new StringBuilder( "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
      + "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"><title>Diagramas</title></head>"
      + "<body style=\"font-family:Segoe UI,Arial,sans-serif;background:#F3F5F8\">" );
    for ( Map.Entry<String, String> e : muestras.entrySet() ) {
      Files.write( new File( dir, e.getKey() + ".svg" ).toPath(), e.getValue().getBytes( StandardCharsets.UTF_8 ) );
      sb.append( "<h3 style=\"font-size:13px;margin:16px 0 4px\">" ).append( e.getKey() ).append( "</h3>\n" ).append(
        e.getValue() ).append( '\n' );
    }
    sb.append( "</body></html>\n" );
    Files.write( new File( dir, "diagramas.html" ).toPath(), sb.toString().getBytes( StandardCharsets.UTF_8 ) );
  }

  // ================================================================== grafos de prueba

  /** Cadena de n steps. */
  static Grafo cadena( int n ) {
    Grafo g = new Grafo( Grafo.FLUJO, "Cadena " + n );
    Nodo prev = null;
    for ( int i = 1; i <= n; i++ ) {
      Nodo x = g.nodo( "s" + i, "Paso numero " + i );
      x.lineas.add( "Seleccionar valores" );
      if ( prev != null ) {
        g.arista( prev, x, Grafo.NORMAL );
      }
      prev = x;
    }
    return g;
  }

  /**
   * Forma parecida a una carga de un data warehouse de 26 steps: lectura, reparto a 3 ramas (cada una une
   * con una tabla y carga una dimension) y un tramo final con dos busquedas en el flujo.
   */
  static Grafo almacen() {
    Grafo g = new Grafo( Grafo.FLUJO, "Carga del almacen" );
    Nodo leer = g.nodo( "leer", "Leer origen.txt" );
    leer.lineas.add( "CsvInput" );
    Nodo partir = paso( g, "partir", "Separar columnas", leer );
    Nodo convertir = paso( g, "convertir", "Convertir tipos", partir );
    Nodo repartir = paso( g, "repartir", "Repartir filas", convertir );
    repartir.multi = true;
    repartir.lineas.add( "copia las filas a 3 salidas" );
    String[] ramas = { "ventas", "clientes", "productos" };
    Nodo[] cargas = new Nodo[ 3 ];
    for ( int i = 0; i < 3; i++ ) {
      String r = ramas[ i ];
      Nodo k = paso( g, "cte_" + r, "Constante " + r, repartir );
      Nodo s = paso( g, "sel_" + r, "Seleccionar " + r, k );
      Nodo tabla = g.nodo( "tabla_" + r, "Leer " + r );
      tabla.lineas.add( "Entrada Tabla" );
      Nodo unir = paso( g, "unir_" + r, "Unir " + r, s );
      g.arista( tabla, unir, Grafo.NORMAL );
      Nodo clave = paso( g, "clave_" + r, "Clave " + r, unir );
      cargas[ i ] = paso( g, "cargar_" + r, "Cargar " + r, clave );
    }
    Nodo buscar1 = paso( g, "buscar1", "Buscar clave A", cargas[ 0 ] );
    g.arista( cargas[ 1 ], buscar1, Grafo.INFO ).rotulo = "búsqueda";
    Nodo buscar2 = paso( g, "buscar2", "Buscar clave B", buscar1 );
    g.arista( cargas[ 2 ], buscar2, Grafo.INFO ).rotulo = "búsqueda";
    Nodo esperar = paso( g, "esperar", "Esperar cargas", buscar2 );
    paso( g, "hechos", "Cargar hechos", esperar );
    return g;
  }

  private static Nodo paso( Grafo g, String id, String titulo, Nodo desde ) {
    Nodo n = g.nodo( id, titulo );
    n.lineas.add( "Tipo de step" );
    if ( desde != null ) {
      g.arista( desde, n, Grafo.NORMAL );
    }
    return n;
  }

  /** Flujo con rotulos: filtro verdadero/falso, error, busqueda y un salto deshabilitado. */
  static Grafo conRotulos() {
    Grafo g = new Grafo( Grafo.FLUJO, "Con rotulos" );
    Nodo leer = paso( g, "leer", "Leer pedidos", null );
    Nodo validar = paso( g, "validar", "Validar pedido", leer );
    Nodo total = g.nodo( "total", "Calcular total" );
    total.copias = 4; // la tarjeta de atras no puede quedar debajo del rotulo "verdadero"
    Nodo rechazos = g.nodo( "rechazos", "Rechazos" );
    g.arista( validar, total, Grafo.NORMAL ).rotulo = "verdadero";
    g.arista( validar, rechazos, Grafo.NORMAL ).rotulo = "falso";
    Nodo precios = paso( g, "precios", "Leer precios", null );
    Nodo buscar = paso( g, "buscar", "Buscar precio", total );
    g.arista( precios, buscar, Grafo.INFO ).rotulo = "búsqueda";
    Nodo grabar = paso( g, "grabar", "Grabar pedidos", buscar );
    grabar.copias = 3;
    Nodo errores = paso( g, "errores", "Errores de grabado", null );
    g.arista( grabar, errores, Grafo.ERROR ).rotulo = "error";
    Nodo log = paso( g, "log", "Escribir log", rechazos );
    Arista off = g.arista( log, errores, Grafo.NORMAL );
    off.activa = false;
    off.rotulo = "deshabilitado";
    return g;
  }

  /** Job con un bus de "si falla" hacia el mismo aviso y un salto siempre. */
  static Grafo job( int pasos, boolean bucle ) {
    Grafo g = new Grafo( Grafo.JOB, "Job" );
    Nodo start = g.nodo( "start", "START" );
    start.inicio = true;
    Nodo aviso = g.nodo( "aviso", "Avisar error" );
    aviso.lineas.add( "Escribir en el log" );
    Nodo prev = start;
    Nodo primero = null;
    for ( int i = 1; i <= pasos; i++ ) {
      Nodo x = g.nodo( "e" + i, "Cargar tabla " + i );
      x.lineas.add( "Transformación" );
      g.arista( prev, x, prev == start ? Grafo.SIEMPRE : Grafo.OK );
      g.arista( x, aviso, Grafo.FALLA );
      if ( primero == null ) {
        primero = x;
      }
      prev = x;
    }
    Nodo fin = g.nodo( "fin", "Fin" );
    fin.lineas.add( "Éxito" );
    g.arista( prev, fin, Grafo.OK );
    if ( bucle ) {
      g.arista( prev, primero, Grafo.SIEMPRE );
    }
    return g;
  }

  /** Linaje: n campos de salida, cada uno de una fuente y un calculo propios; dos comparten la fuente. */
  static Grafo linaje( int n ) {
    Grafo g = new Grafo( Grafo.LINAJE, "Linaje hacia «Cargar»" );
    List<Grupo> fuentes = new ArrayList<>();
    for ( int i = 0; i < n; i++ ) {
      fuentes.add( g.grupo( "Leer tabla " + i ) );
    }
    Grupo calc = g.grupo( "Calcular" );
    Grupo dst = g.grupo( "Cargar" );
    dst.destino = true;
    Nodo compartido = g.campo( fuentes.get( 0 ), "f0b", "codigo" );
    for ( int i = 0; i < n; i++ ) {
      Nodo a = g.campo( fuentes.get( i ), "f" + i, "campo_origen_" + i );
      Nodo c = g.campo( calc, "c" + i, "valor_" + i );
      c.op = "ROUND(campo_origen_" + i + ", 2)";
      Nodo d = g.campo( dst, "d" + i, "valor_" + i );
      g.arista( a, c, Grafo.NORMAL );
      g.arista( c, d, Grafo.NORMAL );
    }
    // d1 tambien depende de la fuente 0: d0 y d1 van en el mismo racimo
    g.arista( compartido, g.buscar( "c1" ), Grafo.CLAVE );
    return g;
  }

  // ================================================================== cruces

  /** K2,2 entre dos capas: un cruce es inevitable y el orden no deja mas. */
  public void pruebaCrucesK22() {
    Grafo g = new Grafo( Grafo.FLUJO, "K22" );
    Nodo a1 = g.nodo( "a1", "A1" );
    Nodo a2 = g.nodo( "a2", "A2" );
    Nodo b1 = g.nodo( "b1", "B1" );
    Nodo b2 = g.nodo( "b2", "B2" );
    g.arista( a1, b1, null );
    g.arista( a1, b2, null );
    g.arista( a2, b1, null );
    g.arista( a2, b2, null );
    Grafo p = g.preparada();
    Tamanos.medir( p );
    Capas c = Capas.construir( p );
    igual( 1, Orden.minimizar( c ), "cruces del orden en K2,2" );
  }

  /**
   * Escalera plana (cada fuente va a su destino y al siguiente) creada en un orden de documento desordenado:
   * el orden la deja sin cruces y las rutas tambien.
   */
  public void pruebaCrucesEscaleraDesordenada() {
    Grafo g = new Grafo( Grafo.FLUJO, "Escalera" );
    int n = 6;
    int[] perm = { 3, 0, 5, 1, 4, 2 };
    Nodo[] a = new Nodo[ n ];
    Nodo[] b = new Nodo[ n ];
    Nodo[] c = new Nodo[ n ];
    for ( int i : perm ) {
      c[ i ] = g.nodo( "c" + i, "C" + i );
    }
    for ( int i : perm ) {
      b[ i ] = g.nodo( "b" + i, "B" + i );
    }
    for ( int i : perm ) {
      a[ i ] = g.nodo( "a" + i, "A" + i );
    }
    for ( int k = n - 1; k >= 0; k-- ) {
      int i = perm[ k ];
      g.arista( a[ i ], b[ i ], null );
      g.arista( b[ i ], c[ i ], null );
      if ( i + 1 < n ) {
        g.arista( a[ i ], b[ i + 1 ], null );
        g.arista( b[ i ], c[ i + 1 ], null );
      }
    }
    Grafo p = g.preparada();
    Tamanos.medir( p );
    Capas cs = Capas.construir( p );
    igual( 0, Orden.minimizar( cs ), "cruces del orden en la escalera" );
    Disposicion d = Disposicion.fija( g.preparada(), false, 1 );
    igual( 0, d.cruces, "cruces de las rutas en la escalera" );
  }

  /** Los grafos de prueba terminan sin cruces (flujos) o con pocos (linaje con clave). */
  public void pruebaCrucesEnGrafosTipicos() {
    for ( Objetivo o : new Objetivo[] { Objetivo.A4, Objetivo.DIAPOSITIVA, Objetivo.PANTALLA } ) {
      for ( Dibujo d : Diagramas.dibujar( almacen(), o, "es" ) ) {
        igual( 0, d.cruces, "cruces del almacen en " + o + " " + d );
      }
      for ( Dibujo d : Diagramas.dibujar( conRotulos(), o, "es" ) ) {
        igual( 0, d.cruces, "cruces del flujo con rotulos en " + o );
      }
      for ( Dibujo d : Diagramas.dibujar( job( 8, false ), o, "es" ) ) {
        cierto( d.cruces <= 1, "cruces del job en " + o + ": " + d );
      }
    }
  }

  // ================================================================== forma

  /** Una cadena de 26 steps no queda como una tira: en A4 se envuelve y queda casi cuadrada y legible. */
  public void pruebaCadenaLargaCuadrada() {
    Grafo g = cadena( 26 );
    List<Dibujo> a4 = Diagramas.dibujar( g, Objetivo.A4, "es" );
    igual( 1, a4.size(), "en A4 la cadena va en una sola figura" );
    Dibujo d = a4.get( 0 );
    muestras.put( "cadena26_a4", d.svg );
    cierto( d.bandas > 1, "la cadena se envuelve en bandas: " + d );
    cierto( d.proporcion() > 0.45 && d.proporcion() < 1.6, "proporcion cerca de la de A4 (0,73): " + d );
    cierto( d.letra >= Objetivo.A4.letraMin, "letra minima en A4: " + d );
    cierto( d.altoFinal() <= Objetivo.A4.alto + 0.5 && d.anchoFinal() <= Objetivo.A4.ancho + 0.5,
      "entra en la caja de la hoja: " + d );
    // Mermaid la dibujaba en una fila: ~26 x 170 px de ancho por 60 de alto
    double antes = 26 * 170.0 / 60;
    cierto( d.proporcion() < antes / 10, "mucho mas cuadrada que una fila: " + d );
    for ( Dibujo x : Diagramas.dibujar( g, Objetivo.DIAPOSITIVA, "es" ) ) {
      cierto( x.letra >= Objetivo.DIAPOSITIVA.letraMin, "letra minima en la diapositiva: " + x );
      cierto( x.proporcion() > 1.0 && x.proporcion() < 4.0, "proporcion de diapositiva (2,26): " + x );
    }
    for ( Dibujo x : Diagramas.dibujar( g, Objetivo.PANTALLA, "es" ) ) {
      cierto( x.letra >= Objetivo.PANTALLA.letraMin, "letra minima en pantalla: " + x );
    }
  }

  /** El almacen (26 steps, 3 ramas) en A4: una figura, letra legible, proporcion de hoja. */
  public void pruebaAlmacenEnHojaYDiapositiva() {
    List<Dibujo> a4 = Diagramas.dibujar( almacen(), Objetivo.A4, "es" );
    for ( Dibujo d : a4 ) {
      muestras.put( "almacen_a4_" + d.parte, d.svg );
      cierto( d.letra >= Objetivo.A4.letraMin, "letra en A4: " + d );
      cierto( d.proporcion() < 2.0, "no es una tira: " + d );
    }
    List<Dibujo> diapo = Diagramas.dibujar( almacen(), Objetivo.DIAPOSITIVA, "es" );
    for ( Dibujo d : diapo ) {
      muestras.put( "almacen_diapo_" + d.parte, d.svg );
      cierto( d.letra >= Objetivo.DIAPOSITIVA.letraMin, "letra en la diapositiva: " + d );
    }
  }

  /**
   * Envuelta en bandas, se sigue leyendo de izquierda a derecha y de arriba abajo: dentro de una banda cada
   * flecha avanza, y la que pasa a la banda siguiente baja (nunca vuelve hacia arriba).
   */
  public void pruebaEnvolturaSeLeeEnOrden() {
    for ( Grafo g : new Grafo[] { cadena( 26 ), almacen(), job( 8, false ) } ) {
      for ( boolean tb : new boolean[] { false, true } ) {
        for ( int r = 2; r <= 4; r++ ) {
          Disposicion d = Disposicion.fija( g.preparada(), tb, r );
          String que = g.titulo + ( tb ? " TB" : " LR" ) + " r=" + r;
          igual( r, d.bandas(), "bandas de " + que );
          for ( int b = 0; b + 1 < d.bandas(); b++ ) {
            cierto( d.bandaC[ b + 1 ] >= d.bandaC[ b ] + d.bandaAlto[ b ], "bandas apiladas en " + que );
          }
          for ( Arista e : d.c.g.aristas ) {
            if ( e.invertida ) {
              continue;
            }
            int ba = d.banda[ e.a.capa ];
            int bb = d.banda[ e.b.capa ];
            cierto( ba <= bb, "la flecha no vuelve a una banda anterior en " + que );
            if ( ba == bb ) {
              cierto( e.a.a1() <= e.b.a0() + 0.01, "dentro de la banda la flecha avanza en " + que );
            }
            List<double[]> ruta = Rutas.ruta( e );
            cierto( ruta.size() >= 2, "la flecha tiene ruta en " + que );
            // cada pedazo: adentro de una banda avanza (o es vertical); en un canal entre bandas baja o va
            // derecho, nunca sube
            for ( int i = 1; i < ruta.size(); i++ ) {
              double[] p = ruta.get( i - 1 );
              double[] q = ruta.get( i );
              boolean adentro = false;
              for ( int k = ba; k <= bb; k++ ) {
                adentro |= dentroDeBanda( d, k, p[ 1 ] ) && dentroDeBanda( d, k, q[ 1 ] );
              }
              if ( adentro ) {
                cierto( q[ 0 ] >= p[ 0 ] - 0.01, "dentro de la banda la ruta no retrocede en " + que + ": " + e.a.id
                  + "->" + e.b.id );
              } else {
                cierto( q[ 1 ] >= p[ 1 ] - 0.01, "entre bandas la ruta baja y no vuelve a subir en " + que + ": "
                  + e.a.id + "->" + e.b.id );
              }
            }
            cierto( ba == bb || ruta.get( ruta.size() - 1 )[ 1 ] > ruta.get( 0 )[ 1 ], "termina mas abajo en " + que );
          }
        }
      }
    }
  }

  /** La coordenada transversal cae dentro de la banda b (adentro de una banda la ruta puede subir). */
  private static boolean dentroDeBanda( Disposicion d, int b, double c ) {
    return c >= d.bandaC[ b ] - 0.01 && c <= d.bandaC[ b ] + d.bandaAlto[ b ] + 0.01;
  }

  // ================================================================== linaje

  /** Un linaje grande en la diapositiva se parte por racimos; cada campo de salida sale una sola vez. */
  public void pruebaLinajePartido() {
    Grafo g = linaje( 14 );
    List<Dibujo> diapo = Diagramas.dibujar( g, Objetivo.DIAPOSITIVA, "es" );
    cierto( diapo.size() > 1, "el linaje de 14 campos se parte en la diapositiva: " + diapo );
    cierto( diapo.size() <= 2 * Partir.MAX_PARTES_LINAJE, "partes acotadas: " + diapo.size() );
    Grafo p = g.preparada();
    List<Grafo> partes = Partir.linaje( p, Objetivo.DIAPOSITIVA, Rotulos.de( Diagramas.AREA, "es" ) );
    igual( diapo.size(), partes.size(), "Partir y Diagramas coinciden" );
    Set<String> vistos = new HashSet<>();
    for ( Grafo x : partes ) {
      Grupo dst = x.destino();
      noNulo( dst, "cada parte tiene el destino" );
      boolean d0 = false;
      boolean d1 = false;
      for ( Nodo n : dst.nodos ) {
        cierto( vistos.add( n.id ), "el campo " + n.id + " sale una sola vez" );
        d0 |= n.id.equals( "d0" );
        d1 |= n.id.equals( "d1" );
      }
      igual( d0, d1, "d0 y d1 comparten la fuente 0: van juntos" );
    }
    igual( 14, vistos.size(), "todos los campos de salida estan en alguna parte" );
    for ( Dibujo d : diapo ) {
      muestras.put( "linaje14_diapo_" + d.parte, d.svg );
      cierto( d.letra >= Objetivo.DIAPOSITIVA.letraMin, "letra minima de cada parte: " + d );
      contiene( d.titulo, "(" + d.parte + " de " + d.partes + ")", "titulo con la parte" );
    }
    // un linaje chico no se parte
    igual( 1, Diagramas.dibujar( linaje( 2 ), Objetivo.DIAPOSITIVA, "es" ).size(), "linaje de 2 campos entero" );
  }

  /** Aristas entre campos del mismo step: se pliegan en la operacion y el campo hereda las entradas. */
  public void pruebaLinajePliegaElMismoStep() {
    Grafo g = new Grafo( Grafo.LINAJE, "Plegado" );
    Grupo leer = g.grupo( "Leer" );
    Grupo calc = g.grupo( "Calcular" );
    Grupo dst = g.grupo( "Cargar" );
    dst.destino = true;
    Nodo x = g.campo( leer, "x", "precio" );
    Nodo tmp = g.campo( calc, "tmp", "precio_iva" );
    tmp.op = "precio * 1.21";
    Nodo tot = g.campo( calc, "tot", "total" );
    tot.op = "ROUND";
    Nodo d = g.campo( dst, "d", "total" );
    g.arista( x, tmp, null );
    g.arista( tmp, tot, null );
    g.arista( tot, d, null );
    g.arista( x, x, null ); // lazo: se ignora
    Grafo p = g.preparada();
    for ( Arista e : p.aristas ) {
      falso( e.a.grupo == e.b.grupo, "no quedan aristas dentro de un step" );
    }
    Nodo tot2 = p.buscar( "tot" );
    contiene( tot2.op, "← precio_iva", "la operacion dice de que campo sale" );
    boolean desdeX = false;
    for ( Arista e : p.aristas ) {
      desdeX |= e.a.id.equals( "x" ) && e.b == tot2;
    }
    cierto( desdeX, "total hereda la entrada de precio_iva" );
    igual( null, p.buscar( "tmp" ), "el intermedio sin otras salidas se saca" );
    List<Dibujo> ds = Diagramas.dibujar( g, Objetivo.A4, "es" );
    igual( 1, ds.size(), "un dibujo" );
    muestras.put( "linaje_plegado", ds.get( 0 ).svg );
    // la misma operacion en todos los campos de un step va a la cabecera
    Grafo h = new Grafo( Grafo.LINAJE, "Cabecera" );
    Grupo a = h.grupo( "Leer" );
    Grupo u = h.grupo( "Unir ventas" );
    Grupo c = h.grupo( "Cargar" );
    c.destino = true;
    for ( int i = 0; i < 3; i++ ) {
      Nodo f = h.campo( a, "a" + i, "c" + i );
      Nodo m = h.campo( u, "u" + i, "c" + i );
      m.op = "une";
      h.arista( f, m, null );
      h.arista( m, h.campo( c, "d" + i, "c" + i ), null );
    }
    Grafo hp = h.preparada();
    igual( "une", hp.grupos.get( 1 ).op, "operacion comun en la cabecera" );
    igual( null, hp.buscar( "u0" ).op, "y no en cada campo" );
    String svg = Diagramas.dibujar( h, Objetivo.A4, "es" ).get( 0 ).svg;
    contiene( svg, "Unir ventas · une", "cabecera con la operacion" );
  }

  // ================================================================== determinismo

  /** La misma entrada da exactamente el mismo SVG (orden, semilla fija, sin hash de identidad). */
  public void pruebaDeterminismo() {
    Objetivo[] os = { Objetivo.A4, Objetivo.DIAPOSITIVA, Objetivo.PANTALLA, Objetivo.DOCX };
    for ( Objetivo o : os ) {
      List<String> a = svgs( Diagramas.dibujar( almacen(), o, "es" ) );
      List<String> b = svgs( Diagramas.dibujar( almacen(), o, "es" ) );
      igual( a, b, "almacen en " + o );
      Grafo j = job( 8, true );
      igual( svgs( Diagramas.dibujar( j, o, "es" ) ), svgs( Diagramas.dibujar( j, o, "es" ) ),
        "el mismo grafo dos veces en " + o );
      igual( svgs( Diagramas.dibujar( linaje( 14 ), o, "es" ) ), svgs( Diagramas.dibujar( linaje( 14 ), o, "es" ) ),
        "linaje en " + o );
    }
  }

  private static List<String> svgs( List<Dibujo> ds ) {
    List<String> out = new ArrayList<>();
    for ( Dibujo d : ds ) {
      out.add( d.svg );
    }
    return out;
  }

  // ================================================================== rotulos

  /** Los rotulos de las flechas no tapan ningun nodo ni a otro rotulo, en LR, TB y envuelto. */
  public void pruebaRotulosNoTapanNodos() {
    Rotulos rot = Rotulos.de( Diagramas.AREA, "es" );
    for ( Grafo g : new Grafo[] { conRotulos(), almacen(), job( 6, false ) } ) {
      for ( boolean tb : new boolean[] { false, true } ) {
        for ( int r = 1; r <= 3; r++ ) {
          Disposicion d = Disposicion.fija( g.preparada(), tb, r );
          DiagramaSvg s = DiagramaSvg.dibujar( d, g.titulo, rot );
          String que = g.titulo + ( tb ? " TB" : " LR" ) + " r=" + r;
          if ( g.titulo.equals( "Con rotulos" ) ) {
            cierto( s.cajasRotulos.size() >= 4, "se dibujan los rotulos en " + que + ": " + s.cajasRotulos.size() );
          }
          List<double[]> todas = new ArrayList<>( s.cajasNodos );
          todas.addAll( s.cajasCopias );
          for ( double[] q : s.cajasRotulos ) {
            for ( double[] n : todas ) {
              falso( seTocan( q, n ), "rotulo " + caja( q ) + " sobre el nodo " + caja( n ) + " en " + que );
            }
          }
          for ( int i = 0; i < s.cajasRotulos.size(); i++ ) {
            for ( int j = i + 1; j < s.cajasRotulos.size(); j++ ) {
              falso( seTocan( s.cajasRotulos.get( i ), s.cajasRotulos.get( j ) ), "dos rotulos encimados en " + que );
            }
          }
          for ( int i = 0; i < s.cajasNodos.size(); i++ ) {
            for ( int j = i + 1; j < s.cajasNodos.size(); j++ ) {
              falso( seTocan( s.cajasNodos.get( i ), s.cajasNodos.get( j ) ), "dos nodos encimados en " + que );
            }
          }
          if ( r == 1 ) {
            muestras.put( "rotulos_" + g.titulo.replace( ' ', '_' ) + ( tb ? "_tb" : "_lr" ), s.svg );
          }
        }
      }
    }
  }

  private static boolean seTocan( double[] a, double[] b ) {
    return a[ 0 ] < b[ 0 ] + b[ 2 ] - 0.5 && b[ 0 ] < a[ 0 ] + a[ 2 ] - 0.5 && a[ 1 ] < b[ 1 ] + b[ 3 ] - 0.5
      && b[ 1 ] < a[ 1 ] + a[ 3 ] - 0.5;
  }

  private static String caja( double[] r ) {
    return String.format( Locale.ROOT, "[%.0f,%.0f %.0fx%.0f]", r[ 0 ], r[ 1 ], r[ 2 ], r[ 3 ] );
  }

  // ================================================================== SVG y PNG

  /** SVG 1.1 apto para Batik/IE11/POI: XML valido, sin CSS ni marker, rect blanco primero, escapes; PNG. */
  public void pruebaSvgValidoYPng() throws Exception {
    Grafo g = conRotulos();
    Nodo raro = g.nodo( "raro", "Tabla <a&b> \"x\" 'y' \u0001 🚀" );
    g.arista( g.buscar( "grabar" ), raro, null );
    for ( Objetivo o : new Objetivo[] { Objetivo.A4, Objetivo.DIAPOSITIVA, Objetivo.PANTALLA, Objetivo.DOCX,
      Objetivo.LETTER } ) {
      for ( Dibujo d : Diagramas.dibujar( g, o, "es" ) ) {
        String svg = d.svg;
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse( new ByteArrayInputStream( svg
          .getBytes( StandardCharsets.UTF_8 ) ) );
        Element raiz = doc.getDocumentElement();
        igual( "svg", raiz.getTagName(), "raiz svg" );
        String w = raiz.getAttribute( "width" );
        String h = raiz.getAttribute( "height" );
        igual( "0 0 " + w + " " + h, raiz.getAttribute( "viewBox" ), "viewBox = width/height" );
        Node primero = raiz.getFirstChild();
        igual( "title", primero.getNodeName(), "primero el title" );
        Element fondo = (Element) primero.getNextSibling();
        igual( "rect", fondo.getTagName(), "despues el rect de fondo" );
        igual( "#FFFFFF", fondo.getAttribute( "fill" ), "fondo blanco" );
        igual( w, fondo.getAttribute( "width" ), "el fondo cubre el ancho" );
        for ( String malo : new String[] { "<style", "marker", "dominant-baseline", "class=", "var(", "\u0001" } ) {
          noContiene( svg, malo, "nada que Batik o IE11 no entiendan (" + malo + ")" );
        }
        contiene( svg, "Tabla &lt;a&amp;b&gt; &quot;x&quot; &#39;y&#39;", "escapes" );
        contiene( svg, "×3", "marca de copias" );
        contiene( svg, "3 copias en paralelo", "title de la marca de copias" );
        contiene( svg, "hop de error", "leyenda del salto de error" );
        contiene( svg, "hop deshabilitado", "leyenda del salto deshabilitado" );
        cierto( d.anchoFinal() <= o.ancho + 0.5 && d.altoFinal() <= o.alto + 0.5, "entra en la caja " + o + ": " + d );
        String esc = d.svgEscalado();
        contiene( esc, "width=\"" + DiagramaSvg.n( Math.round( d.anchoFinal() * 100 ) / 100.0 ) + "\"",
          "svgEscalado con el ancho final" );
        contiene( esc, "viewBox=\"0 0 " + w + " " + h + "\"", "svgEscalado conserva el viewBox" );
      }
    }
    Dibujo d = Diagramas.dibujar( g, Objetivo.DIAPOSITIVA, "es" ).get( 0 );
    BufferedImage img = ImageIO.read( new ByteArrayInputStream( d.png( 2 ) ) );
    noNulo( img, "PNG legible" );
    igual( (int) Math.round( d.anchoFinal() * 2 ), img.getWidth(), "ancho del PNG = final x 2" );
    muestras.put( "svg_valido", d.svg );
  }

  /** Grafo vacio, un nodo, lazos y aristas repetidas: nunca excepcion. */
  public void pruebaCasosBorde() {
    Grafo vacio = new Grafo( Grafo.FLUJO, "Vacio" );
    List<Dibujo> ds = Diagramas.dibujar( vacio, Objetivo.A4, "es" );
    igual( 1, ds.size(), "un dibujo del grafo vacio" );
    contiene( ds.get( 0 ).svg, "<svg", "svg del grafo vacio" );
    Grafo uno = new Grafo( Grafo.FLUJO, "Uno" );
    Nodo a = uno.nodo( "a", "Solo" );
    uno.arista( a, a, null );
    igual( 1, Diagramas.dibujar( uno, Objetivo.PANTALLA, "en" ).size(), "un nodo con lazo" );
    Grafo rep = cadena( 3 );
    rep.arista( rep.buscar( "s1" ), rep.buscar( "s2" ), null );
    igual( 2, rep.preparada().aristas.size(), "aristas repetidas se juntan" );
    // un salto deshabilitado no comparte tronco con uno activo del mismo origen
    Grafo dos = new Grafo( Grafo.FLUJO, "Dos salidas" );
    Nodo o = dos.nodo( "o", "Origen" );
    dos.arista( o, dos.nodo( "b", "Activo" ), null );
    dos.arista( o, dos.nodo( "c", "Apagado" ), null ).activa = false;
    Disposicion dd = Disposicion.fija( dos.preparada(), false, 1 );
    igual( 2, Rutas.haces( dd.c, 0 ).size(), "un haz para el activo y otro para el deshabilitado" );
    Grafo vacioLinaje = new Grafo( Grafo.LINAJE, "Linaje vacio" );
    igual( 1, Diagramas.dibujar( vacioLinaje, Objetivo.DIAPOSITIVA, "pt" ).size(), "linaje vacio" );
    boolean lanzo = false;
    try {
      uno.nodo( "a", "otra vez" );
    } catch ( IllegalArgumentException e ) {
      lanzo = true;
    }
    cierto( lanzo, "id repetido" );
  }

  /** Job con un bucle: la arista que vuelve se invierte para la disposicion y la punta va en su destino. */
  public void pruebaJobConBucle() {
    Grafo g = job( 4, true );
    Grafo p = g.preparada();
    Disposicion d = Disposicion.fija( p, false, 1 );
    Arista vuelta = null;
    for ( Arista e : p.aristas ) {
      if ( e.a.id.equals( "e4" ) && e.b.id.equals( "e1" ) ) {
        vuelta = e;
      }
    }
    noNulo( vuelta, "la arista del bucle" );
    cierto( vuelta.invertida, "se invierte para la disposicion" );
    cierto( vuelta.a.capa > vuelta.b.capa, "el origen del bucle queda a la derecha" );
    DiagramaSvg s = DiagramaSvg.dibujar( d, g.titulo, Rotulos.de( Diagramas.AREA, "es" ) );
    // la punta del bucle esta en el extremo del destino original (e1), al principio de su primer tramo
    Tramo t = vuelta.tramos.get( 0 );
    cierto( Capas.invertido( t ), "el tramo va al reves" );
    igual( vuelta.b, t.a, "el tramo arranca en el destino original" );
    double[] q = d.xy( t.puntos.get( 0 )[ 0 ], t.puntos.get( 0 )[ 1 ] );
    contiene( s.svg, "<path d=\"M" + DiagramaSvg.n( q[ 0 ] ) + "," + DiagramaSvg.n( q[ 1 ] ) + " L",
      "triangulo de la punta en el destino original" );
    for ( Objetivo o : new Objetivo[] { Objetivo.A4, Objetivo.DIAPOSITIVA } ) {
      for ( Dibujo x : Diagramas.dibujar( g, o, "es" ) ) {
        contiene( x.svg, "si termina bien", "leyenda: exito" );
        contiene( x.svg, "si falla", "leyenda: falla" );
        contiene( x.svg, "siempre", "leyenda: siempre" );
        muestras.put( "job_bucle_" + o + "_" + x.parte, x.svg );
      }
    }
    String en = Diagramas.dibujar( g, Objetivo.A4, "en" ).get( 0 ).svg;
    contiene( en, "on failure", "leyenda en ingles" );
    g.leyenda = false;
    noContiene( Diagramas.dibujar( g, Objetivo.A4, "es" ).get( 0 ).svg, "si falla", "sin leyenda si se pide" );
  }

  /** El job de 8 pasos con el bus de "si falla": legible en A4 y en la diapositiva. */
  public void pruebaJobConBus() {
    for ( Objetivo o : new Objetivo[] { Objetivo.A4, Objetivo.DIAPOSITIVA } ) {
      for ( Dibujo d : Diagramas.dibujar( job( 8, false ), o, "es" ) ) {
        muestras.put( "job_bus_" + o + "_" + d.parte, d.svg );
        cierto( d.letra >= o.letraMin, "letra del job en " + o + ": " + d );
      }
    }
  }

  // ================================================================== partir flujos

  /** Un flujo que no entra en la diapositiva se parte: fantasmas "sigue en"/"viene de" a los dos lados. */
  public void pruebaFlujoPartidoConFantasmas() {
    Grafo g = cadena( 40 );
    Rotulos rot = Rotulos.de( Diagramas.AREA, "es" );
    List<Grafo> partes = Partir.flujo( g.preparada(), Objetivo.DIAPOSITIVA, rot );
    cierto( partes.size() > 1, "40 steps no entran en una diapositiva: " + partes.size() );
    Set<String> reales = new HashSet<>();
    int sigue = 0;
    int viene = 0;
    for ( Grafo x : partes ) {
      for ( Nodo n : x.nodos ) {
        if ( n.fantasma ) {
          String l = n.lineas.get( 0 );
          sigue += l.startsWith( "sigue en " ) ? 1 : 0;
          viene += l.startsWith( "viene de " ) ? 1 : 0;
        } else {
          cierto( reales.add( n.id ), "cada step en una sola parte: " + n.id );
        }
      }
    }
    igual( 40, reales.size(), "todos los steps" );
    igual( partes.size() - 1, sigue, "un 'sigue en' por corte" );
    igual( partes.size() - 1, viene, "un 'viene de' por corte" );
    List<Dibujo> ds = Diagramas.dibujar( g, Objetivo.DIAPOSITIVA, "es" );
    igual( partes.size(), ds.size(), "Diagramas devuelve una figura por parte" );
    for ( Dibujo d : ds ) {
      cierto( d.letra >= Objetivo.DIAPOSITIVA.letraMin, "letra de cada parte: " + d );
      muestras.put( "cadena40_diapo_" + d.parte, d.svg );
    }
    contiene( ds.get( 0 ).svg, "sigue en 2/" + ds.size(), "fantasma en la primera parte" );
    // en ingles
    contiene( Diagramas.dibujar( g, Objetivo.DIAPOSITIVA, "en" ).get( 0 ).svg, "continues in 2/", "fantasma en ingles" );
  }

  // ================================================================== textos y tiempos

  /** Todas las claves del area en los tres idiomas, con formato que cierra. */
  public void pruebaTextosEnTresIdiomas() {
    String[] claves = { "fantasma.sigue", "fantasma.viene", "parte", "copias", "leyenda.ok", "leyenda.falla",
      "leyenda.siempre", "leyenda.error", "leyenda.deshabilitado", "leyenda.valor", "leyenda.clave" };
    for ( String idioma : new String[] { "en", "es", "pt" } ) {
      Rotulos r = Rotulos.de( Diagramas.AREA, idioma );
      for ( String k : claves ) {
        noNulo( r.propio( k ), "clave " + k + " en " + idioma );
      }
      contiene( r.f( "fantasma.sigue", 2, 3 ), "2/3", "formato en " + idioma );
      contiene( r.f( "parte", 1, 3 ), "1", "parte en " + idioma );
    }
  }

  /** Disponer y partir es rapido (sin PNG). */
  public void pruebaTiempos() {
    for ( Objetivo o : new Objetivo[] { Objetivo.A4, Objetivo.DIAPOSITIVA, Objetivo.PANTALLA, Objetivo.DOCX } ) {
      long t0 = System.nanoTime();
      Diagramas.dibujar( almacen(), o, "es" );
      Diagramas.dibujar( linaje( 14 ), o, "es" );
      long ms = ( System.nanoTime() - t0 ) / 1000000;
      System.out.println( "    tiempo " + o + ": " + ms + " ms (almacen + linaje de 14)" );
      cierto( ms < 8000, "tiempo en " + o + ": " + ms + " ms" );
    }
  }
}
