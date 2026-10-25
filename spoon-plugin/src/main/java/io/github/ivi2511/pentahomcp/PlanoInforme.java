package io.github.ivi2511.pentahomcp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.ivi2511.pentahomcp.Plano.Destino;
import io.github.ivi2511.pentahomcp.Plano.Etapa;
import io.github.ivi2511.pentahomcp.Plano.Fuente;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Esquema;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * El plano como documento de los Informes (D1): portada, resumen, las tres vistas y las tablas de fuentes, destinos,
 * reglas, etapas, riesgos, decisiones y la revisión. Con ese {@link Informe} los renderizadores de siempre lo sacan en
 * HTML (la perspectiva Diseño lo muestra así), Markdown, PDF, Word, Excel o imágenes.
 * <p>
 * Las tres vistas van con el motor de diagramas propio (SVG sin navegador) y con su texto Mermaid (plegado en el
 * Markdown, para GitHub y Obsidian):
 * <ul>
 * <li><b>flujo de datos</b>: fuentes → etapas → almacenes; las búsquedas como flujo de información y los rechazos en
 * rojo;</li>
 * <li><b>actividad</b> (UML) de la orquestación: inicio, una acción por transformación en orden, y qué pasa si una
 * falla;</li>
 * <li><b>componentes y despliegue</b>: dónde vive cada cosa (carpetas, bases, el equipo que ejecuta) y quién lee y
 * escribe qué.</li>
 * </ul>
 */
final class PlanoInforme {

  /** Área de textos: informes/textos/plano_*.properties (plano, revisión, boceto, mapeo y la perspectiva). */
  static final String AREA = "plano";

  static final String S_RESUMEN = "plan_summary";
  static final String S_FLUJO = "plan_dataflow";
  static final String S_ACTIVIDAD = "plan_activity";
  static final String S_COMPONENTES = "plan_components";
  static final String S_FUENTES = "plan_sources";
  static final String S_DESTINOS = "plan_targets";
  static final String S_REGLAS = "plan_rules";
  static final String S_ETAPAS = "plan_stages";
  static final String S_RIESGOS = "plan_risks";
  static final String S_REVISION = "plan_review";
  static final String S_PROGRESO = "plan_progress";

  private PlanoInforme() {
  }

  /** El idioma del documento: el pedido, el del plano o inglés. */
  static String idioma( Plano p, String pedido ) {
    return Rotulos.normalizar( pedido != null ? pedido : p.idioma != null ? p.idioma : "en" );
  }

  /**
   * El informe del plano. progreso = filas {transformación, construidas, total, pendientes} del boceto en Spoon (o
   * null).
   */
  static Informe armar( Plano p, String idiomaPedido, List<String[]> progreso ) {
    String idioma = idioma( p, idiomaPedido );
    Rotulos r = Rotulos.de( AREA, idioma );
    Informe inf = new Informe();
    inf.idioma = idioma;
    inf.titulo = p.nombre;
    inf.subtitulo = r.s( "doc.subtitulo" );
    inf.nombreDoc = p.archivo != null ? p.archivo.getName() : p.nombre;
    inf.archivo = p.archivo == null ? null : p.archivo.getPath();
    inf.generado = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT ).format( new Date() );
    Rotulos est = Rotulos.de( InformeServicio.AREA, idioma );
    Rotulos render = Rotulos.de( "render", idioma );
    for ( String k : InformeServicio.CLAVES_ROTULOS ) {
      inf.rotulos.put( k, render.tiene( k ) ? render.s( k ) : est.s( "rotulo." + k ) );
    }

    // ---- portada
    Seccion portada = new Seccion( SeccionId.PORTADA, SeccionId.titulo( SeccionId.PORTADA, idioma ) );
    portada.resumen = PlanoRevision.vacio( p.objetivo ) ? r.s( "doc.sin_objetivo" ) : p.objetivo;
    Lista datos = new Lista();
    datos.id = "plan-cover-data";
    if ( p.archivo != null ) {
      datos.items.add( r.f( "doc.archivo", p.archivo.getName() ) );
    }
    datos.items.add( r.f( "doc.grano", nz( p.grano, r.s( "doc.sin_definir" ) ) ) );
    datos.items.add( r.f( "doc.carga", carga( p, r ) ) );
    datos.items.add( r.f( "doc.cuantos", p.fuentes.size(), p.destinos.size(), p.etapas.size(),
      p.transformaciones().size() ) );
    portada.con( datos );
    inf.secciones.add( portada );

    // ---- resumen y revisión corta
    List<PlanoRevision.Hallazgo> rev = PlanoRevision.revisar( p, idioma );
    Seccion res = new Seccion( S_RESUMEN, r.s( "sec.resumen" ) );
    res.resumen = r.f( "doc.resumen", p.fuentes.size(), p.destinos.size(), p.etapas.size(), cuenta( rev,
      PlanoRevision.ERROR ), cuenta( rev, PlanoRevision.AVISO ) );
    Tabla t = new Tabla( r.s( "col.campo" ), r.s( "col.valor" ) );
    t.id = "plan-summary";
    t.fila( r.s( "doc.k.objetivo" ), nz( p.objetivo, "—" ) );
    t.fila( r.s( "doc.k.grano" ), nz( p.grano, "—" ) );
    t.fila( r.s( "doc.k.carga" ), carga( p, r ) );
    List<String> ts = p.transformaciones();
    StringBuilder tsTxt = new StringBuilder();
    for ( String x : ts ) {
      tsTxt.append( tsTxt.length() == 0 ? "" : ", " ).append( x.isEmpty() ? r.s( "doc.sin_nombre" ) : x );
    }
    t.fila( r.s( "doc.k.transformaciones" ), tsTxt.toString() );
    if ( p.orquestacion != null && p.orquestacion.nombre != null ) {
      t.fila( r.s( "doc.k.job" ), p.orquestacion.nombre );
    }
    if ( p.entorno != null && p.entorno.ejecuta != null ) {
      t.fila( r.s( "doc.k.ejecuta" ), p.entorno.ejecuta );
    }
    res.con( t );
    inf.secciones.add( res );

    // ---- progreso del boceto (solo si viene de Spoon)
    if ( progreso != null && !progreso.isEmpty() ) {
      Seccion pg = new Seccion( S_PROGRESO, r.s( "sec.progreso" ) );
      Tabla tp = new Tabla( r.s( "col.transformacion" ), r.s( "col.construidas" ), r.s( "col.pendientes" ) );
      tp.id = "plan-progress";
      tp.alineacion = java.util.Arrays.asList( "l", "c", "l" );
      for ( String[] f : progreso ) {
        tp.fila( f[ 0 ], r.f( "boceto.de", Integer.parseInt( f[ 1 ] ), Integer.parseInt( f[ 2 ] ) ), f[ 3 ] );
      }
      pg.con( tp );
      inf.secciones.add( pg );
    }

    // ---- las tres vistas
    Seccion flujo = new Seccion( S_FLUJO, r.s( "sec.flujo" ) );
    flujo.resumen = r.s( "sec.flujo.resumen" );
    if ( ts.size() > 1 ) {
      // una vista por transformación: el plano entero en un solo dibujo quedaba partido en pedazos
      for ( String g : ts ) {
        flujo.con( diagramaFlujo( p, r, g ) );
      }
    } else {
      flujo.con( diagramaFlujo( p, r, null ) );
    }
    inf.secciones.add( flujo );
    Seccion act = new Seccion( S_ACTIVIDAD, r.s( "sec.actividad" ) );
    act.resumen = r.s( "sec.actividad.resumen" );
    act.con( diagramaActividad( p, r ) );
    inf.secciones.add( act );
    Seccion comp = new Seccion( S_COMPONENTES, r.s( "sec.componentes" ) );
    comp.resumen = r.s( "sec.componentes.resumen" );
    comp.con( diagramaComponentes( p, r ) );
    inf.secciones.add( comp );

    // ---- tablas
    Seccion sf = new Seccion( S_FUENTES, r.s( "sec.fuentes" ) );
    if ( p.fuentes.isEmpty() ) {
      sf.con( new Aviso( Aviso.WARN, r.s( "doc.vacio" ) ) );
    } else {
      Tabla tf = new Tabla( r.s( "col.id" ), r.s( "col.nombre" ), r.s( "col.formato" ), r.s( "col.ubicacion" ),
        r.s( "col.volumen" ), r.s( "col.frecuencia" ), r.s( "col.clave" ), r.s( "col.rechazos" ) );
      tf.id = "plan-sources";
      for ( Fuente f : p.fuentes ) {
        tf.fila( f.id, f.nombre, nz( f.formato, "" ), nz( f.ubicacion, "" ), nz( f.volumen, "" ), nz( f.frecuencia,
          "" ), String.join( ", ", f.clave ), nz( f.rechazos, "" ) );
      }
      sf.con( tf );
    }
    inf.secciones.add( sf );

    Seccion sd = new Seccion( S_DESTINOS, r.s( "sec.destinos" ) );
    if ( p.destinos.isEmpty() ) {
      sd.con( new Aviso( Aviso.WARN, r.s( "doc.vacio" ) ) );
    } else {
      Tabla td = new Tabla( r.s( "col.id" ), r.s( "col.nombre" ), r.s( "col.clase" ), r.s( "col.ubicacion" ),
        r.s( "col.clave" ), r.s( "col.carga" ) );
      td.id = "plan-targets";
      for ( Destino d : p.destinos ) {
        td.fila( d.id, d.nombre, d.clase == null ? "" : r.s( "clase_destino." + d.clase ), nz( d.ubicacion, "" ),
          String.join( ", ", d.clave ), d.carga == null ? "" : r.s( "carga_destino." + d.carga ) );
      }
      sd.con( td );
    }
    inf.secciones.add( sd );

    if ( !p.reglas.isEmpty() ) {
      Seccion sr = new Seccion( S_REGLAS, r.s( "sec.reglas" ) );
      Tabla tr = new Tabla( r.s( "col.id" ), r.s( "col.regla" ), r.s( "col.etapas" ) );
      tr.id = "plan-rules";
      for ( Plano.Regla x : p.reglas ) {
        List<String> es = new ArrayList<String>();
        for ( Etapa e : p.etapas ) {
          if ( e.reglas.contains( x.id ) ) {
            es.add( e.nombre );
          }
        }
        tr.fila( x.id, x.texto, String.join( ", ", es ) );
      }
      sr.con( tr );
      inf.secciones.add( sr );
    }

    Seccion se = new Seccion( S_ETAPAS, r.s( "sec.etapas" ) );
    if ( p.etapas.isEmpty() ) {
      se.con( new Aviso( Aviso.WARN, r.s( "doc.vacio" ) ) );
    }
    for ( String tr : ts ) {
      List<Etapa> es = p.etapasDe( tr );
      if ( es.isEmpty() ) {
        continue;
      }
      Tabla te = new Tabla( r.s( "col.etapa" ), r.s( "col.clase" ), r.s( "col.despues" ), r.s( "col.lee" ),
        r.s( "col.escribe" ), r.s( "col.reglas" ), r.s( "col.step" ) );
      te.id = "plan-stages-" + Plano.slug( tr.isEmpty() ? "main" : tr );
      te.titulo = tr.isEmpty() ? null : tr;
      for ( Etapa e : es ) {
        String nombre = e.nombre + ( PlanoRevision.vacio( e.descripcion ) ? "" : " — " + e.descripcion );
        te.fila( nombre, e.clase == null ? "" : r.s( "clase_etapa." + e.clase ), nombres( p, e.despues ),
          nombres( p, e.lee ) + ( e.busca == null ? "" : ( e.lee.isEmpty() ? "" : "; " ) + r.f( "doc.busca",
            p.nombreDe( e.busca ), String.join( ", ", e.clave ) ) ), nombres( p, e.escribe ), String.join( ", ",
              e.reglas ), nz( e.tipoStep, "" ) );
      }
      se.con( te );
    }
    inf.secciones.add( se );

    if ( !p.riesgos.isEmpty() || !p.decisiones.isEmpty() ) {
      Seccion sx = new Seccion( S_RIESGOS, r.s( "sec.riesgos" ) );
      if ( !p.riesgos.isEmpty() ) {
        Tabla tx = new Tabla( r.s( "col.riesgo" ), r.s( "col.mitigacion" ) );
        tx.id = "plan-risks";
        for ( Plano.Riesgo x : p.riesgos ) {
          tx.fila( x.texto, nz( x.mitigacion, "—" ) );
        }
        sx.con( tx );
      }
      if ( !p.decisiones.isEmpty() ) {
        Tabla tx = new Tabla( r.s( "col.decision" ), r.s( "col.porque" ) );
        tx.id = "plan-decisions";
        for ( Plano.Decision x : p.decisiones ) {
          tx.fila( x.texto, nz( x.porque, "—" ) );
        }
        sx.con( tx );
      }
      inf.secciones.add( sx );
    }

    Seccion sv = new Seccion( S_REVISION, r.s( "sec.revision" ) );
    if ( rev.isEmpty() ) {
      sv.resumen = r.s( "rev.vacia" );
    } else {
      sv.resumen = r.f( "rev.resumen", cuenta( rev, PlanoRevision.ERROR ), cuenta( rev, PlanoRevision.AVISO ),
        cuenta( rev, PlanoRevision.INFO ) );
      int k = 0;
      for ( PlanoRevision.Hallazgo h : rev ) {
        String nivel = PlanoRevision.ERROR.equals( h.severidad ) ? Aviso.ERROR : PlanoRevision.AVISO.equals(
          h.severidad ) ? Aviso.WARN : Aviso.INFO;
        StringBuilder sb = new StringBuilder( h.mensaje );
        if ( h.arreglo != null ) {
          sb.append( ' ' ).append( h.arreglo );
        }
        if ( h.pregunta != null ) {
          sb.append( ' ' ).append( r.f( "rev.pregunta", h.pregunta ) );
        }
        Aviso a = new Aviso( nivel, sb.toString() );
        a.id = "plan-review-" + ( ++k );
        sv.con( a );
      }
    }
    inf.secciones.add( sv );
    return inf;
  }

  // ================================================================ diagramas

  /** Flujo de datos de todo el plano (grupo null) o de una transformación: fuentes, etapas y almacenes. */
  static Diagrama diagramaFlujo( Plano p, Rotulos r, String grupo ) {
    Vista v = vista( p, grupo );
    Esquema e = new Esquema();
    e.tipo = Diagrama.FLUJO;
    e.titulo = r.f( "diag.flujo", grupo == null ? p.nombre : PlanoConstruir.nombreGrupo( grupo ) );
    for ( Fuente f : v.fuentes ) {
      Esquema.Nodo n = nodo( e, "s_" + f.id, f.nombre );
      n.lineas = lineas( r.f( "diag.fuente", f.formato == null ? r.s( "doc.fuente" ) : r.s( "formato." + f.formato ) ),
        unir( f.volumen, f.frecuencia ) );
      n.inicio = true;
    }
    for ( Etapa x : v.etapas ) {
      Esquema.Nodo n = nodo( e, "e_" + x.id, x.nombre );
      n.lineas = lineas( x.clase == null ? null : r.s( "clase_etapa." + x.clase ), grupo == null ? x.transformacion
        : null );
    }
    for ( Destino d : v.destinos ) {
      Esquema.Nodo n = nodo( e, "d_" + d.id, d.nombre );
      n.lineas = lineas( r.f( "diag.almacen", d.clase == null ? r.s( "doc.destino" ) : r.s( "clase_destino."
        + d.clase ) ), d.carga == null ? null : r.s( "carga_destino." + d.carga ) );
    }
    for ( Etapa x : v.etapas ) {
      for ( String s : x.lee ) {
        arista( e, ( p.fuente( s ) != null ? "s_" : "d_" ) + s, "e_" + x.id, null, null );
      }
      for ( String a : x.despues ) {
        Etapa y = p.etapa( a );
        if ( y == null || !v.etapas.contains( y ) ) {
          continue; // viene de otra transformación: lo cuenta la actividad
        }
        String rot = !mismaTrans( x, y ) ? r.s( "diag.otra_trans" ) : null;
        arista( e, "e_" + a, "e_" + x.id, "reject".equals( x.clase ) ? "error" : null, rot );
      }
      if ( x.busca != null ) {
        Etapa y = p.etapa( x.busca );
        if ( y == null || v.etapas.contains( y ) ) {
          String id = p.fuente( x.busca ) != null ? "s_" + x.busca : p.destino( x.busca ) != null ? "d_" + x.busca
            : "e_" + x.busca;
          arista( e, id, "e_" + x.id, "info", x.clave.isEmpty() ? r.s( "diag.busca" ) : String.join( ", ", x.clave ) );
        }
      }
      for ( String d : x.escribe ) {
        arista( e, "e_" + x.id, "d_" + d, "reject".equals( x.clase ) ? "error" : null, null );
      }
    }
    Diagrama d = new Diagrama();
    d.id = grupo == null ? "plan-dataflow" : "plan-dataflow-" + Plano.slug( grupo.isEmpty() ? "main" : grupo );
    d.clase = Diagrama.FLUJO;
    d.titulo = e.titulo;
    d.grafo = e;
    d.alt = r.f( "diag.flujo.alt", v.fuentes.size(), v.etapas.size(), v.destinos.size() );
    d.mermaid = mermaidFlujo( p, r, v );
    return d;
  }

  /** Lo que entra en un diagrama de flujo: las etapas y las fuentes y destinos que usan. */
  static final class Vista {
    final List<Etapa> etapas = new ArrayList<Etapa>();
    final List<Fuente> fuentes = new ArrayList<Fuente>();
    final List<Destino> destinos = new ArrayList<Destino>();
  }

  static Vista vista( Plano p, String grupo ) {
    Vista v = new Vista();
    v.etapas.addAll( grupo == null ? p.etapas : p.etapasDe( grupo ) );
    if ( grupo == null ) {
      v.fuentes.addAll( p.fuentes );
      v.destinos.addAll( p.destinos );
      return v;
    }
    Set<String> ids = new LinkedHashSet<String>();
    for ( Etapa x : v.etapas ) {
      ids.addAll( x.lee );
      ids.addAll( x.escribe );
      if ( x.busca != null ) {
        ids.add( x.busca );
      }
    }
    for ( Fuente f : p.fuentes ) {
      if ( ids.contains( f.id ) ) {
        v.fuentes.add( f );
      }
    }
    for ( Destino d : p.destinos ) {
      if ( ids.contains( d.id ) ) {
        v.destinos.add( d );
      }
    }
    return v;
  }

  /** Actividad (UML) de la orquestación: inicio → transformaciones en orden → fin, y la rama de falla. */
  static Diagrama diagramaActividad( Plano p, Rotulos r ) {
    Esquema e = new Esquema();
    e.tipo = Diagrama.JOB;
    String nombreJob = p.orquestacion != null && p.orquestacion.nombre != null ? p.orquestacion.nombre : p.nombre;
    e.titulo = r.f( "diag.actividad", nombreJob );
    Esquema.Nodo ini = nodo( e, "inicio", r.s( "act.inicio" ) );
    ini.inicio = true;
    List<String> ts = p.transformaciones();
    String siFalla = p.orquestacion == null || p.orquestacion.siFalla == null ? "abort" : p.orquestacion.siFalla;
    String previo = "inicio";
    int k = 0;
    for ( String t : ts ) {
      String id = "t" + ( k++ );
      Esquema.Nodo n = nodo( e, id, t.isEmpty() ? p.nombre : t );
      n.lineas = lineas( r.f( "act.etapas", p.etapasDe( t ).size() ) );
      arista( e, previo, id, previo.equals( "inicio" ) ? "siempre" : "ok", null );
      previo = id;
    }
    Esquema.Nodo fin = nodo( e, "fin", r.s( "act.fin" ) );
    fin.lineas = lineas( r.s( "act.fin.linea" ) );
    arista( e, previo, "fin", previo.equals( "inicio" ) ? "siempre" : "ok", null );
    if ( !ts.isEmpty() ) {
      String aviso = p.orquestacion != null && p.orquestacion.avisar != null ? p.orquestacion.avisar
        : r.s( "act.registrar" );
      Esquema.Nodo f = nodo( e, "falla", aviso );
      f.lineas = lineas( r.s( "act.si_falla." + siFalla ) );
      for ( int i = 0; i < ts.size(); i++ ) {
        arista( e, "t" + i, "falla", "falla", null );
      }
      if ( "abort".equals( siFalla ) ) {
        Esquema.Nodo ab = nodo( e, "abortar", r.s( "act.abortar" ) );
        ab.lineas = lineas( r.s( "act.abortar.linea" ) );
        arista( e, "falla", "abortar", "siempre", null );
      } else if ( "continue".equals( siFalla ) ) {
        arista( e, "falla", "fin", "siempre", null );
      }
    }
    Diagrama d = new Diagrama();
    d.id = "plan-activity";
    d.clase = Diagrama.JOB;
    d.titulo = e.titulo;
    d.grafo = e;
    d.alt = r.f( "diag.actividad.alt", ts.size() );
    d.mermaid = mermaidActividad( p, r, ts, siFalla );
    return d;
  }

  /** Componentes y despliegue: un grupo por sitio (carpeta, base, equipo que ejecuta) con lo que vive ahí. */
  static Diagrama diagramaComponentes( Plano p, Rotulos r ) {
    Componentes c = componentes( p, r );
    Esquema e = new Esquema();
    e.tipo = Diagrama.LINAJE;
    e.titulo = r.f( "diag.componentes", p.nombre );
    e.leyenda = false;
    Map<String, Integer> grupos = new LinkedHashMap<String, Integer>();
    for ( Map.Entry<String, List<String[]>> g : c.sitios.entrySet() ) {
      Esquema.Grupo gr = new Esquema.Grupo();
      gr.nombre = g.getKey();
      gr.op = c.tipoSitio.get( g.getKey() );
      gr.destino = c.esEquipo.contains( g.getKey() );
      grupos.put( g.getKey(), e.grupos.size() );
      e.grupos.add( gr );
      for ( String[] n : g.getValue() ) {
        Esquema.Nodo x = nodo( e, n[ 0 ], n[ 1 ] );
        x.grupo = grupos.get( g.getKey() );
        if ( n[ 2 ] != null ) {
          x.op = n[ 2 ];
        }
      }
    }
    for ( String[] a : c.usos ) {
      arista( e, a[ 0 ], a[ 1 ], null, null );
    }
    Diagrama d = new Diagrama();
    d.id = "plan-components";
    d.clase = Diagrama.LINAJE;
    d.titulo = e.titulo;
    d.grafo = e;
    d.alt = r.f( "diag.componentes.alt", c.sitios.size() );
    d.mermaid = mermaidComponentes( c );
    return d;
  }

  /** Sitios (grupos) y sus componentes {id, nombre, tipo}, y los usos {desde, hasta}. */
  static final class Componentes {
    final Map<String, List<String[]>> sitios = new LinkedHashMap<String, List<String[]>>();
    final Map<String, String> tipoSitio = new LinkedHashMap<String, String>();
    final Set<String> esEquipo = new LinkedHashSet<String>();
    final List<String[]> usos = new ArrayList<String[]>();

    void poner( String sitio, String tipo, String[] componente ) {
      List<String[]> l = sitios.get( sitio );
      if ( l == null ) {
        l = new ArrayList<String[]>();
        sitios.put( sitio, l );
        tipoSitio.put( sitio, tipo );
      }
      for ( String[] x : l ) {
        if ( x[ 0 ].equals( componente[ 0 ] ) ) {
          return;
        }
      }
      l.add( componente );
    }
  }

  static Componentes componentes( Plano p, Rotulos r ) {
    Componentes c = new Componentes();
    String equipo = p.entorno != null && p.entorno.ejecuta != null ? p.entorno.ejecuta : r.s( "comp.equipo" );
    c.esEquipo.add( equipo );
    List<String> ts = p.transformaciones();
    if ( p.orquestacion != null && p.orquestacion.nombre != null ) {
      c.poner( equipo, r.s( "comp.tipo.equipo" ), new String[] { "job", p.orquestacion.nombre + ".kjb", r.s(
        "comp.job" ) } );
    }
    int k = 0;
    for ( String t : ts ) {
      c.poner( equipo, r.s( "comp.tipo.equipo" ), new String[] { "t" + ( k++ ), ( t.isEmpty() ? p.nombre : t )
        + ".ktr", r.s( "comp.trans" ) } );
    }
    for ( Fuente f : p.fuentes ) {
      String[] s = sitio( p, f.conexion, f.ubicacion, f.formato, r );
      c.poner( s[ 0 ], s[ 1 ], new String[] { "s_" + f.id, f.nombre, r.s( "doc.fuente" ) } );
      if ( !PlanoRevision.vacio( f.rechazos ) && destinoEn( p, f.rechazos ) == null ) {
        String[] sr = sitio( p, null, f.rechazos, "text", r );
        c.poner( sr[ 0 ], sr[ 1 ], new String[] { "rj_" + f.id, base( f.rechazos ), r.s( "comp.rechazos" ) } );
      }
    }
    for ( Destino d : p.destinos ) {
      String[] s = sitio( p, d.conexion, d.ubicacion, "file".equals( d.clase ) ? "text" : "table", r );
      c.poner( s[ 0 ], s[ 1 ], new String[] { "d_" + d.id, d.nombre, d.clase == null ? r.s( "doc.destino" )
        : r.s( "clase_destino." + d.clase ) } );
    }
    // usos: fuente -> transformacion que la lee; transformacion -> destino que escribe
    for ( Etapa e : p.etapas ) {
      String t = "t" + ts.indexOf( e.transformacion == null ? "" : e.transformacion );
      for ( String s : e.lee ) {
        agregarUso( c, ( p.fuente( s ) != null ? "s_" : "d_" ) + s, t );
      }
      if ( e.busca != null && ( p.fuente( e.busca ) != null || p.destino( e.busca ) != null ) ) {
        agregarUso( c, ( p.fuente( e.busca ) != null ? "s_" : "d_" ) + e.busca, t );
      }
      for ( String d : e.escribe ) {
        agregarUso( c, t, "d_" + d );
      }
    }
    for ( Fuente f : p.fuentes ) {
      if ( !PlanoRevision.vacio( f.rechazos ) && destinoEn( p, f.rechazos ) == null ) {
        for ( Etapa e : p.etapas ) {
          if ( e.lee.contains( f.id ) ) {
            agregarUso( c, "t" + ts.indexOf( e.transformacion == null ? "" : e.transformacion ), "rj_" + f.id );
            break;
          }
        }
      }
    }
    // job -> transformaciones no se dibuja: viven en el mismo sitio (la actividad ya lo cuenta)
    return c;
  }

  /** El destino que está en esa ubicación (el archivo de rechazos declarado también como destino), o null. */
  static Destino destinoEn( Plano p, String ubicacion ) {
    String u = ubicacion.trim().replace( '\\', '/' ).toLowerCase( Locale.ROOT );
    for ( Destino d : p.destinos ) {
      String x = d.ubicacion == null ? "" : d.ubicacion.trim().replace( '\\', '/' ).toLowerCase( Locale.ROOT );
      if ( x.equals( u ) ) {
        return d;
      }
    }
    return null;
  }

  private static void agregarUso( Componentes c, String a, String b ) {
    for ( String[] u : c.usos ) {
      if ( u[ 0 ].equals( a ) && u[ 1 ].equals( b ) ) {
        return;
      }
    }
    c.usos.add( new String[] { a, b } );
  }

  /**
   * El sitio de una fuente o destino: la conexión (y su tipo, del entorno) si tiene; si no, la carpeta de la
   * ubicación (las del entorno primero) o «Archivos».
   */
  static String[] sitio( Plano p, String conexion, String ubicacion, String formato, Rotulos r ) {
    if ( !PlanoRevision.vacio( conexion ) ) {
      String tipo = null;
      if ( p.entorno != null ) {
        for ( String[] c : p.entorno.conexiones ) {
          if ( c[ 0 ].equalsIgnoreCase( conexion ) ) {
            tipo = unir( c[ 1 ], c[ 2 ] );
          }
        }
      }
      return new String[] { r.f( "comp.base", conexion ), tipo == null ? r.s( "comp.tipo.base" ) : tipo };
    }
    if ( "table".equals( formato ) && PlanoRevision.vacio( ubicacion ) ) {
      return new String[] { r.s( "comp.base_sin_nombre" ), r.s( "comp.tipo.base" ) };
    }
    String u = ubicacion == null ? "" : ubicacion.replace( '\\', '/' );
    if ( p.entorno != null ) {
      for ( String[] c : p.entorno.carpetas ) {
        String ruta = c[ 1 ] == null ? "" : c[ 1 ].replace( '\\', '/' );
        if ( !ruta.isEmpty() && u.startsWith( ruta ) || c[ 0 ].equalsIgnoreCase( carpeta( u ) ) ) {
          return new String[] { r.f( "comp.carpeta", c[ 0 ] ), c[ 1 ] == null ? r.s( "comp.tipo.carpeta" ) : c[ 1 ] };
        }
      }
    }
    String dir = carpeta( u );
    if ( "api".equals( formato ) ) {
      return new String[] { r.s( "comp.servicio" ), r.s( "comp.tipo.servicio" ) };
    }
    return new String[] { dir.isEmpty() ? r.s( "comp.archivos" ) : r.f( "comp.carpeta", dir ),
      r.s( "comp.tipo.carpeta" ) };
  }

  private static String carpeta( String u ) {
    int i = u.lastIndexOf( '/' );
    return i <= 0 ? "" : u.substring( 0, i );
  }

  private static String base( String u ) {
    String x = u.replace( '\\', '/' );
    return x.substring( x.lastIndexOf( '/' ) + 1 );
  }

  // ================================================================ Mermaid

  static String mermaidFlujo( Plano p, Rotulos r, Vista v ) {
    StringBuilder sb = new StringBuilder( "flowchart LR\n" );
    for ( Fuente f : v.fuentes ) {
      sb.append( "  " ).append( mid( "s_" + f.id ) ).append( "[/\"" ).append( esc( f.nombre ) ).append( "<br/>" )
        .append( esc( f.formato == null ? r.s( "doc.fuente" ) : r.s( "formato." + f.formato ) ) ).append( "\"/]\n" );
    }
    for ( Etapa x : v.etapas ) {
      sb.append( "  " ).append( mid( "e_" + x.id ) ).append( "(\"" ).append( esc( x.nombre ) );
      if ( x.clase != null ) {
        sb.append( "<br/>" ).append( esc( r.s( "clase_etapa." + x.clase ) ) );
      }
      sb.append( "\")\n" );
    }
    for ( Destino d : v.destinos ) {
      sb.append( "  " ).append( mid( "d_" + d.id ) ).append( "[(\"" ).append( esc( d.nombre ) ).append( "\")]\n" );
    }
    for ( Etapa x : v.etapas ) {
      for ( String s : x.lee ) {
        sb.append( "  " ).append( mid( ( p.fuente( s ) != null ? "s_" : "d_" ) + s ) ).append( " --> " ).append( mid(
          "e_" + x.id ) ).append( '\n' );
      }
      for ( String a : x.despues ) {
        if ( p.etapa( a ) == null || !v.etapas.contains( p.etapa( a ) ) ) {
          continue;
        }
        sb.append( "  " ).append( mid( "e_" + a ) ).append( "reject".equals( x.clase ) ? " -.-> " : " --> " ).append(
          mid( "e_" + x.id ) ).append( '\n' );
      }
      if ( x.busca != null && ( p.etapa( x.busca ) == null || v.etapas.contains( p.etapa( x.busca ) ) ) ) {
        String id = p.fuente( x.busca ) != null ? "s_" + x.busca : p.destino( x.busca ) != null ? "d_" + x.busca : "e_"
          + x.busca;
        sb.append( "  " ).append( mid( id ) ).append( " -. \"" ).append( esc( x.clave.isEmpty() ? r.s( "diag.busca" )
          : String.join( ", ", x.clave ) ) ).append( "\" .-> " ).append( mid( "e_" + x.id ) ).append( '\n' );
      }
      for ( String d : x.escribe ) {
        sb.append( "  " ).append( mid( "e_" + x.id ) ).append( " --> " ).append( mid( "d_" + d ) ).append( '\n' );
      }
    }
    return sb.toString();
  }

  static String mermaidActividad( Plano p, Rotulos r, List<String> ts, String siFalla ) {
    StringBuilder sb = new StringBuilder( "flowchart TD\n" );
    sb.append( "  inicio((\"" ).append( esc( r.s( "act.inicio" ) ) ).append( "\"))\n" );
    String previo = "inicio";
    for ( int i = 0; i < ts.size(); i++ ) {
      String t = ts.get( i );
      sb.append( "  t" ).append( i ).append( "(\"" ).append( esc( t.isEmpty() ? p.nombre : t ) ).append( "\")\n" );
      sb.append( "  " ).append( previo ).append( " --> t" ).append( i ).append( '\n' );
      previo = "t" + i;
    }
    sb.append( "  fin(((\"" ).append( esc( r.s( "act.fin" ) ) ).append( "\")))\n" );
    sb.append( "  " ).append( previo ).append( " -->|" ).append( esc( r.s( "act.ok" ) ) ).append( "| fin\n" );
    if ( !ts.isEmpty() ) {
      String aviso = p.orquestacion != null && p.orquestacion.avisar != null ? p.orquestacion.avisar
        : r.s( "act.registrar" );
      sb.append( "  falla[\"" ).append( esc( aviso ) ).append( "\"]\n" );
      for ( int i = 0; i < ts.size(); i++ ) {
        sb.append( "  t" ).append( i ).append( " -.->|" ).append( esc( r.s( "act.falla" ) ) ).append( "| falla\n" );
      }
      if ( "abort".equals( siFalla ) ) {
        sb.append( "  abortar(((\"" ).append( esc( r.s( "act.abortar" ) ) ).append( "\")))\n" );
        sb.append( "  falla --> abortar\n" );
      } else if ( "continue".equals( siFalla ) ) {
        sb.append( "  falla --> fin\n" );
      }
    }
    return sb.toString();
  }

  static String mermaidComponentes( Componentes c ) {
    StringBuilder sb = new StringBuilder( "flowchart LR\n" );
    int k = 0;
    for ( Map.Entry<String, List<String[]>> g : c.sitios.entrySet() ) {
      sb.append( "  subgraph g" ).append( k++ ).append( "[\"" ).append( esc( g.getKey() ) );
      String tipo = c.tipoSitio.get( g.getKey() );
      if ( tipo != null ) {
        sb.append( " · " ).append( esc( tipo ) );
      }
      sb.append( "\"]\n" );
      for ( String[] n : g.getValue() ) {
        sb.append( "    " ).append( mid( n[ 0 ] ) ).append( "[\"" ).append( esc( n[ 1 ] ) ).append( "\"]\n" );
      }
      sb.append( "  end\n" );
    }
    for ( String[] u : c.usos ) {
      sb.append( "  " ).append( mid( u[ 0 ] ) ).append( " --> " ).append( mid( u[ 1 ] ) ).append( '\n' );
    }
    return sb.toString();
  }

  /** Id seguro para Mermaid (letras, dígitos y _). */
  static String mid( String id ) {
    return "n_" + id.replaceAll( "[^A-Za-z0-9_]", "_" );
  }

  private static String esc( String s ) {
    return ReportMotor.escaparMermaid( s );
  }

  // ================================================================ apoyo

  private static Esquema.Nodo nodo( Esquema e, String id, String titulo ) {
    Esquema.Nodo n = new Esquema.Nodo();
    n.id = id;
    n.titulo = titulo == null ? "" : titulo;
    e.nodos.add( n );
    return n;
  }

  private static void arista( Esquema e, String a, String b, String clase, String rotulo ) {
    for ( Esquema.Arista x : e.aristas ) {
      if ( x.a.equals( a ) && x.b.equals( b ) ) {
        return;
      }
    }
    Esquema.Arista x = new Esquema.Arista();
    x.a = a;
    x.b = b;
    x.clase = clase;
    x.rotulo = rotulo;
    e.aristas.add( x );
  }

  private static List<String> lineas( String... ls ) {
    List<String> l = new ArrayList<String>();
    for ( String s : ls ) {
      if ( s != null && !s.trim().isEmpty() ) {
        l.add( s.trim() );
      }
    }
    return l.isEmpty() ? null : l;
  }

  private static boolean mismaTrans( Etapa a, Etapa b ) {
    String x = a.transformacion == null ? "" : a.transformacion;
    String y = b.transformacion == null ? "" : b.transformacion;
    return x.equalsIgnoreCase( y );
  }

  static String carga( Plano p, Rotulos r ) {
    if ( p.carga == null ) {
      return r.s( "doc.sin_definir" );
    }
    String s = r.s( "carga." + p.carga );
    return p.incrementalPor == null ? s : r.f( "doc.carga_por", s, p.incrementalPor );
  }

  private static String nombres( Plano p, List<String> ids ) {
    List<String> l = new ArrayList<String>();
    for ( String id : ids ) {
      l.add( p.nombreDe( id ) );
    }
    return String.join( ", ", l );
  }

  private static int cuenta( List<PlanoRevision.Hallazgo> l, String sev ) {
    int n = 0;
    for ( PlanoRevision.Hallazgo h : l ) {
      if ( sev.equals( h.severidad ) ) {
        n++;
      }
    }
    return n;
  }

  static String nz( String s, String def ) {
    return s == null || s.trim().isEmpty() ? def : s;
  }

  private static String unir( String a, String b ) {
    if ( PlanoRevision.vacio( a ) ) {
      return PlanoRevision.vacio( b ) ? null : b;
    }
    return PlanoRevision.vacio( b ) ? a : a + " · " + b;
  }
}
