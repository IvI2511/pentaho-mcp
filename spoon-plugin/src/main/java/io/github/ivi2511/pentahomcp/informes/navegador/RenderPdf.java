package io.github.ivi2511.pentahomcp.informes.navegador;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Renderizador;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.render.RenderHtml;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * PDF: Chromium headless imprime el HTML interactivo para imprimir (diseno §3.2):
 * {@code --print-to-pdf --no-pdf-header-footer --virtual-time-budget=15000}. Se valida codigo 0,
 * {@code %PDF} y mas de 1 KB. El HTML y el PDF se arman en una carpeta temporal corta y el PDF se mueve
 * al destino al final (sin problemas de MAX_PATH ni de acentos en la ruta del usuario).
 * <p>
 * Sin navegador, o si el navegador falla, escribe {@code <nombre>.imprimir.html} (el mismo HTML con
 * {@code window.print()} al cargar, cuando Mermaid termino), lo devuelve como archivo principal y suma
 * un aviso traducido: abrirlo (Program.launch) le toca a la UI, porque aca no hay SWT.
 *
 * @dueño WP8
 */
public final class RenderPdf implements Renderizador {

  /** Area de textos de este paquete: textos/navegador_*.properties. */
  public static final String AREA = "navegador";
  /** Sufijo del HTML que se escribe cuando no se puede hacer el PDF. */
  public static final String SUFIJO_IMPRIMIR = ".imprimir.html";

  /** Script ES5 que imprime cuando todos los pre.mermaid estan procesados (o a los 8 s). */
  static final String JS_IMPRIMIR = "<script>(function(){function listo(){var p=document.querySelectorAll("
    + "'pre.mermaid');for(var i=0;i<p.length;i++){if(!p[i].getAttribute('data-processed')){return false;}}"
    + "return true;}function al(){var t0=new Date().getTime();(function esperar(){if(listo()||new Date()"
    + ".getTime()-t0>8000){setTimeout(function(){window.print();},300);}else{setTimeout(esperar,200);}})();}"
    + "if(window.addEventListener){window.addEventListener('load',al,false);}else{window.attachEvent('onload',al);}"
    + "})();</script>";

  public RenderPdf() {
  }

  @Override
  public Formato formato() {
    return Formato.PDF;
  }

  @Override
  public Resultado escribir( Informe inf, Tema tema, File destino, Progreso p ) throws Exception {
    Progreso pr = p == null ? Progreso.NULO : p;
    Tema t = tema == null ? new Tema() : tema;
    Rotulos r = Rotulos.de( AREA, inf == null ? null : inf.idioma );
    Resultado res = new Resultado();
    File exe = Chromium.ubicar( t.navegador );
    File carpeta = Chromium.carpetaTrabajo();
    try {
      pr.paso( r.s( "preparando" ), 0, 2 );
      File html = new File( carpeta, "informe.html" );
      new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, true ).escribir( inf, t, html, pr );
      if ( exe == null ) {
        File imp = imprimible( html, destino );
        res.archivo( imp );
        res.avisos.add( r.f( "pdf_sin_navegador", imp.getName() ) );
        return res;
      }
      if ( pr.cancelado() ) {
        throw new CancellationException( "Cancelado" );
      }
      pr.paso( r.s( "imprimiendo" ), 1, 2 );
      File pdf = new File( carpeta, "informe.pdf" );
      try {
        pdfDeHtml( html, pdf, exe, pr );
      } catch ( CancellationException | InterruptedException e ) {
        throw e;
      } catch ( Exception e ) {
        File imp = imprimible( html, destino );
        res.archivo( imp );
        res.avisos.add( r.f( "pdf_fallo", motivo( e ), imp.getName() ) );
        return res;
      }
      mover( pdf, destino );
      res.archivo( destino );
      pr.paso( r.s( "imprimiendo" ), 2, 2 );
      return res;
    } finally {
      Chromium.borrar( carpeta );
    }
  }

  /**
   * Imprime un HTML local a PDF con Chromium headless y valida el resultado (codigo 0, existe, empieza
   * con %PDF, mide mas de 1 KB). Sirve tambien para HTML que no salio de RenderHtml (pruebas).
   *
   * @param exe el navegador; null = {@link Chromium#ubicar()}.
   * @return el mismo {@code pdf}.
   * @throws IOException si el navegador fallo o el archivo no es un PDF valido.
   */
  public static File pdfDeHtml( File html, File pdf, File exe, Progreso p ) throws Exception {
    File nav = exe != null ? exe : Chromium.ubicar();
    if ( nav == null ) {
      throw new IllegalStateException( "No hay Edge ni Chrome" );
    }
    if ( pdf.exists() && !pdf.delete() ) {
      throw new IOException( "No se puede reemplazar " + pdf );
    }
    File dir = pdf.getAbsoluteFile().getParentFile();
    if ( dir != null ) {
      dir.mkdirs();
    }
    Chromium.Corrida c = Chromium.ejecutar( nav, Arrays.asList( "--no-pdf-header-footer", "--print-to-pdf-no-header",
      "--virtual-time-budget=" + PrerenderMermaid.PRESUPUESTO_MS, "--print-to-pdf=" + pdf.getAbsolutePath(),
      Chromium.url( html ) ), Chromium.TIMEOUT_MS, false, p );
    if ( c.codigo != 0 ) {
      throw new IOException( "El navegador termino con codigo " + c.codigo + ultimaLinea( c.errores ) );
    }
    if ( !pdf.isFile() ) {
      throw new IOException( "El navegador no escribio el PDF" + ultimaLinea( c.errores ) );
    }
    if ( pdf.length() <= 1024 || !empiezaConPdf( pdf ) ) {
      throw new IOException( "El archivo no es un PDF valido (" + pdf.length() + " bytes)" );
    }
    return pdf;
  }

  /** Copia el HTML a &lt;destino sin extension&gt;.imprimir.html, con el script que imprime al cargar. */
  static File imprimible( File html, File destino ) throws IOException {
    String texto = new String( Chromium.leer( html ), StandardCharsets.UTF_8 );
    int i = texto.toLowerCase( Locale.ROOT ).lastIndexOf( "</body>" );
    texto = i >= 0 ? texto.substring( 0, i ) + JS_IMPRIMIR + texto.substring( i ) : texto + JS_IMPRIMIR;
    File imp = new File( destino.getAbsoluteFile().getParentFile(), base( destino.getName() ) + SUFIJO_IMPRIMIR );
    if ( imp.getParentFile() != null ) {
      imp.getParentFile().mkdirs();
    }
    try ( OutputStream out = new FileOutputStream( imp ) ) {
      out.write( texto.getBytes( StandardCharsets.UTF_8 ) );
    }
    return imp;
  }

  private static String base( String nombre ) {
    int i = nombre.lastIndexOf( '.' );
    return i > 0 ? nombre.substring( 0, i ) : nombre;
  }

  private static void mover( File desde, File hacia ) throws IOException {
    File dir = hacia.getAbsoluteFile().getParentFile();
    if ( dir != null ) {
      dir.mkdirs();
    }
    Files.move( desde.toPath(), hacia.toPath(), StandardCopyOption.REPLACE_EXISTING );
  }

  private static boolean empiezaConPdf( File f ) throws IOException {
    byte[] b = new byte[ 4 ];
    try ( InputStream in = Files.newInputStream( f.toPath() ) ) {
      int n = 0;
      while ( n < 4 ) {
        int k = in.read( b, n, 4 - n );
        if ( k < 0 ) {
          break;
        }
        n += k;
      }
      return n == 4 && b[ 0 ] == '%' && b[ 1 ] == 'P' && b[ 2 ] == 'D' && b[ 3 ] == 'F';
    }
  }

  private static String ultimaLinea( String errores ) {
    if ( errores == null || errores.trim().isEmpty() ) {
      return "";
    }
    String[] l = errores.trim().split( "\\r?\\n" );
    return ": " + l[ l.length - 1 ];
  }

  private static String motivo( Exception e ) {
    if ( e instanceof Chromium.Agotado ) {
      return "timeout";
    }
    String m = e.getMessage();
    return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
  }
}
