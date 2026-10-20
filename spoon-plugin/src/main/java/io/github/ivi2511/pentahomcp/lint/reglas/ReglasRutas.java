package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.vfs2.FileObject;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.resource.ResourceEntry;
import org.pentaho.di.resource.ResourceReference;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Rutas;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * Portabilidad (§2): absolute_path, relative_path (C15), job_variable_in_transformation (D11) y
 * missing_input_file [NP] (depende de la máquina).
 */
public final class ReglasRutas {

  /**
   * Steps de la categoría Input de 9.4 que leen archivos (por id: el nombre de la categoría depende del idioma).
   * Sin Get file names, Get subfolders ni Get files rows count: trabajan con carpetas y máscaras, y su
   * getResourceDependencies lista la carpeta.
   */
  static final Set<String> ENTRADAS_ARCHIVO = new HashSet<String>( Arrays.asList( "CsvInput", "CubeInput",
    "ShapeFileReader", "FixedInput", "getXMLData", "ParallelGzipCsvInput", "HL7Input", "JsonInput", "LDIFInput",
    "LoadFileInput", "AccessInput", "ExcelInput", "PropertyInput", "SASInput", "TextFileInput", "OldTextFileInput",
    "XBaseInput", "XMLInputStream", "YamlInput", "AvroInputNew" ) );
  private static final Pattern ESQUEMA_REMOTO = Pattern.compile( "(?i)^(?!file:)[a-z][a-z0-9+.-]*://.*" );
  private static final Pattern COMODIN = Pattern.compile( "[*?\\[]" );

  private ReglasRutas() {
  }

  public static List<Regla> todas() {
    List<Regla> l = new ArrayList<Regla>();
    l.add( new RutaAbsoluta() );
    l.add( new RutaRelativa() );
    l.add( new VariableDeJob() );
    l.add( new ArchivoQueFalta() );
    return l;
  }

  /** Recorre las rutas de cada step o entrada y deja que la regla decida. */
  abstract static class PorRuta extends ReglaBase {

    PorRuta( String id, Sev sev ) {
      super( id, sev, Regla.PORTABILITY );
    }

    abstract Hallazgo mirar( Ctx ctx, Rutas.Ruta r, StepMeta s, JobEntryCopy c );

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      if ( ctx.esTrans() ) {
        for ( StepMeta s : ctx.trans().getSteps() ) {
          for ( Rutas.Ruta r : Rutas.de( ctx.xml( s ) ) ) {
            agregar( out, mirar( ctx, r, s, null ), r );
          }
        }
      } else {
        for ( JobEntryCopy c : ctx.job().getJobCopies() ) {
          for ( Rutas.Ruta r : Rutas.de( ctx.xml( c ) ) ) {
            agregar( out, mirar( ctx, r, null, c ), r );
          }
        }
      }
    }

    private static void agregar( List<Hallazgo> out, Hallazgo h, Rutas.Ruta r ) {
      if ( h != null ) {
        h.n = r.n;
        h.evidencia( "tag", r.tag ).evidencia( "value", r.valor );
        out.add( h );
      }
    }

    Hallazgo en( Ctx ctx, Sev sev, StepMeta s, JobEntryCopy c, String atributo ) {
      return s != null ? ctx.enStep( sev, s, atributo ) : ctx.enEntrada( sev, c, atributo );
    }

    static String nombre( StepMeta s, JobEntryCopy c ) {
      return s != null ? s.getName() : c.getName();
    }
  }

  /** C:\…, \\servidor\…, file:///…, /home/…: solo anda en esa máquina. */
  static final class RutaAbsoluta extends PorRuta {

    RutaAbsoluta() {
      super( "absolute_path", Sev.WARNING );
      ambos();
    }

    @Override
    Hallazgo mirar( Ctx ctx, Rutas.Ruta r, StepMeta s, JobEntryCopy c ) {
      if ( !Rutas.absoluta( r.valor ) ) {
        return null;
      }
      Hallazgo h = en( ctx, Sev.WARNING, s, c, r.tag );
      h.message = ctx.t( "absolute_path.msg", "where", nombre( s, c ), "value", r.valor );
      String[] p = Rutas.propuesta( ctx.meta().getFilename(), r.valor );
      h.fix = Arreglo.conConfirmacion( Arreglo.REPLACE_VALUE ).vista( p[ 0 ] ).param( "tag", r.tag );
      if ( p[ 1 ] != null ) {
        h.fix.param( "parameter", p[ 1 ] ).param( "default", p[ 2 ] );
        h.howToFix = ctx.t( "absolute_path.fix.parameter", "parameter", p[ 1 ], "default", p[ 2 ] );
      }
      return h;
    }
  }

  /** Relativa sin variable: se resuelve contra la carpeta de PDI, no contra la del .ktr. */
  static final class RutaRelativa extends PorRuta {

    RutaRelativa() {
      super( "relative_path", Sev.ERROR );
      ambos();
    }

    @Override
    Hallazgo mirar( Ctx ctx, Rutas.Ruta r, StepMeta s, JobEntryCopy c ) {
      if ( !Rutas.relativaSinVariable( r.valor ) ) {
        return null;
      }
      Hallazgo h = en( ctx, Sev.ERROR, s, c, r.tag );
      h.message = ctx.t( "relative_path.msg", "where", nombre( s, c ), "value", r.valor );
      h.fix = Arreglo.conConfirmacion( Arreglo.REPLACE_VALUE ).param( "tag", r.tag )
        .vista( Rutas.DIR_ACTUAL + "/" + r.valor.replace( '\\', '/' ).replaceFirst( "^\\./", "" ) );
      return h;
    }
  }

  /** ${Internal.Job.*} en un .ktr: con Pan vale otra cosa (D11). */
  static final class VariableDeJob extends PorRuta {

    VariableDeJob() {
      super( "job_variable_in_transformation", Sev.WARNING );
    }

    @Override
    Hallazgo mirar( Ctx ctx, Rutas.Ruta r, StepMeta s, JobEntryCopy c ) {
      if ( !r.valor.contains( "${Internal.Job." ) ) {
        return null;
      }
      Hallazgo h = en( ctx, Sev.WARNING, s, c, r.tag );
      h.message = ctx.t( "job_variable_in_transformation.msg", "where", nombre( s, c ), "value", r.valor );
      h.fix = Arreglo.conConfirmacion( Arreglo.REPLACE_VALUE ).param( "tag", r.tag )
        .vista( r.valor.replace( "${Internal.Job.Filename.Directory}", Rutas.DIR_ACTUAL ) );
      return h;
    }
  }

  /** Archivo de entrada que no existe tras resolver variables [NP]. Depende de la máquina. */
  static final class ArchivoQueFalta extends ReglaBase {

    ArchivoQueFalta() {
      super( "missing_input_file", Sev.WARNING, Regla.PORTABILITY );
      dependeDelEntorno();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      TransMeta tm = ctx.trans();
      for ( StepMeta s : tm.getSteps() ) {
        if ( !ENTRADAS_ARCHIVO.contains( s.getStepID() ) ) {
          continue;
        }
        Element x = ctx.xml( s );
        // nombres de archivo que vienen de un campo: no se saben sin correr
        if ( "Y".equalsIgnoreCase( Nodos.txt( x, "accept_filenames" ) )
          || "Y".equalsIgnoreCase( Nodos.txt( x, "filefield" ) ) || "Y".equalsIgnoreCase( Nodos.txt( x, "IsInFields" ) ) ) {
          continue;
        }
        // rutas con variables que nadie define o de otro sistema (sftp://, zip://…): no se pueden mirar, y Kettle
        // llenaría el log de errores al intentarlo
        boolean mirable = true;
        for ( Rutas.Ruta r : Rutas.de( x ) ) {
          String v = tm.environmentSubstitute( r.valor );
          mirable &= !v.contains( "${" ) && !v.contains( "%%" ) && !ESQUEMA_REMOTO.matcher( v ).matches();
        }
        if ( !mirable ) {
          continue;
        }
        List<ResourceReference> refs;
        try {
          refs = s.getStepMetaInterface().getResourceDependencies( tm, s );
        } catch ( Throwable e ) {
          continue;
        }
        if ( refs == null ) {
          continue;
        }
        Set<String> vistos = new HashSet<String>();
        for ( ResourceReference ref : refs ) {
          for ( ResourceEntry e : ref.getEntries() ) {
            if ( e.getResourcetype() != ResourceEntry.ResourceType.FILE ) {
              continue;
            }
            String ruta = tm.environmentSubstitute( e.getResource() );
            if ( ruta == null || ruta.trim().isEmpty() || ruta.contains( "${" ) || ruta.contains( "%%" )
              || ESQUEMA_REMOTO.matcher( ruta ).matches() || COMODIN.matcher( ruta ).find() || !vistos.add( ruta ) ) {
              continue;
            }
            boolean existe;
            try {
              FileObject fo = KettleVFS.getFileObject( ruta );
              existe = fo.exists();
            } catch ( Throwable t ) {
              continue;
            }
            if ( !existe ) {
              Hallazgo h = ctx.enStep( Sev.WARNING, s, "file" );
              h.message = ctx.t( "missing_input_file.msg", "step", s.getName(), "file", ruta );
              h.evidencia( "file", ruta );
              out.add( h );
            }
          }
        }
      }
    }
  }
}
