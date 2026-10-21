package io.github.ivi2511.pentahomcp.lint.reglas;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.pentaho.di.cluster.SlaveServer;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.ArchivoCrudo;
import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Ctx;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.ReglaBase;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * password_in_file (§2): contraseña guardada en el archivo. En claro = E; «Encrypted 2be98…» = W (ofuscación
 * reversible); ${VAR} está bien. E solo se sabe en modo archivo (XML crudo): en memoria DatabaseMeta ya trae la
 * clave descifrada y Spoon guarda siempre Encrypted, así que ahí todo es W. Nunca se muestran los valores.
 */
public final class ReglasSeguridad {

  static final Pattern TAG_CLAVE = Pattern.compile( "(?i)(.*_)?(password|passwd|pwd)|.*password" );
  /** Solo variables (${A}, %%B%%, ${A}${B}): «S3cret!${SUFIJO}» es una clave en claro con una variable pegada. */
  static final Pattern SOLO_VARIABLES = Pattern.compile( "\\s*(\\$\\{[^}]+\\}|%%[^%\\s]+%%)+\\s*" );

  private ReglasSeguridad() {
  }

  public static List<Regla> todas() {
    return Collections.<Regla>singletonList( new ClaveEnArchivo() );
  }

  /** El valor entero es una o más variables de Kettle (nada de texto fijo alrededor). */
  public static boolean esVariable( String v ) {
    return v != null && SOLO_VARIABLES.matcher( v ).matches();
  }

  /** null si no es una contraseña guardada (vacía, Y/N, variable); si no, E (en claro) o W (Encrypted). */
  static Sev clasificar( String v ) {
    if ( v == null ) {
      return null;
    }
    String x = v.trim();
    if ( x.isEmpty() || x.equals( "Encrypted" ) || x.matches( "(?i)Y|N|true|false" ) || esVariable( x ) ) {
      return null;
    }
    return x.startsWith( "Encrypted " ) ? Sev.WARNING : Sev.ERROR;
  }

  static String variable( String nombre ) {
    String n = nombre == null ? "" : nombre.toUpperCase( Locale.ROOT ).replaceAll( "[^A-Z0-9]+", "_" ).replaceAll( "^_+|_+$", "" );
    return "${" + ( n.isEmpty() ? "DB" : n ) + "_PASSWORD}";
  }

  static final class ClaveEnArchivo extends ReglaBase {

    ClaveEnArchivo() {
      super( "password_in_file", Sev.ERROR, Regla.SECURITY );
      ambos();
    }

    @Override
    public void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception {
      ArchivoCrudo a = ctx.archivoVigente() ? ctx.crudo() : null;
      if ( a != null ) {
        enArchivo( ctx, a, out );
      } else {
        enMemoria( ctx, out );
      }
    }

    /** Modo archivo: hojas *password* del XML crudo, con el dueño más cercano (conexión, slave, step, entrada). */
    private void enArchivo( Ctx ctx, ArchivoCrudo a, List<Hallazgo> out ) {
      for ( Element[] ep : Nodos.hojas( a.raiz ) ) {
        Element e = ep[ 0 ];
        if ( !TAG_CLAVE.matcher( e.getTagName() ).matches() ) {
          continue;
        }
        Sev sev = clasificar( e.getTextContent() );
        if ( sev == null ) {
          continue;
        }
        Element dueno = Nodos.ancestro( e, "connection", "slaveserver", "step", "entry" );
        String tipo = dueno == null ? null : dueno.getTagName();
        String nombre = dueno == null ? null : Nodos.txt( dueno, "name" );
        Hallazgo h;
        if ( "connection".equals( tipo ) ) {
          h = ctx.enObjeto( sev, Hallazgo.CONNECTION, nombre, e.getTagName() );
        } else if ( "slaveserver".equals( tipo ) ) {
          h = ctx.enObjeto( sev, Hallazgo.SLAVE, nombre, e.getTagName() );
        } else if ( "step".equals( tipo ) ) {
          h = ctx.enStep( sev, nombre, e.getTagName() );
        } else if ( "entry".equals( tipo ) ) {
          h = ctx.nuevo( sev, Hallazgo.ENTRY );
          h.entry = nombre;
          h.entryNr = numero( Nodos.txt( dueno, "nr" ) );
          h.attribute = e.getTagName();
        } else {
          h = ctx.enDoc( sev, e.getTagName() );
        }
        terminar( ctx, h, tipo, nombre, sev );
        out.add( h );
      }
    }

    /** En memoria: no se distingue claro de Encrypted, todo W. Las conexiones compartidas (shared.xml) no son del archivo. */
    private void enMemoria( Ctx ctx, List<Hallazgo> out ) throws Exception {
      for ( DatabaseMeta db : ctx.meta().getDatabases() ) {
        if ( !db.isShared() && clasificar( db.getPassword() ) != null ) {
          Hallazgo h = ctx.enObjeto( Sev.WARNING, Hallazgo.CONNECTION, db.getName(), "password" );
          terminar( ctx, h, "connection", db.getName(), Sev.WARNING );
          out.add( h );
        }
      }
      for ( SlaveServer ss : ctx.meta().getSlaveServers() ) {
        if ( !ss.isShared() && clasificar( ss.getPassword() ) != null ) {
          Hallazgo h = ctx.enObjeto( Sev.WARNING, Hallazgo.SLAVE, ss.getName(), "password" );
          terminar( ctx, h, "slaveserver", ss.getName(), Sev.WARNING );
          out.add( h );
        }
      }
      if ( ctx.esTrans() ) {
        for ( StepMeta s : ctx.trans().getSteps() ) {
          for ( Element[] ep : Nodos.hojas( ctx.xml( s ) ) ) {
            if ( TAG_CLAVE.matcher( ep[ 0 ].getTagName() ).matches() && clasificar( ep[ 0 ].getTextContent() ) != null ) {
              Hallazgo h = ctx.enStep( Sev.WARNING, s, ep[ 0 ].getTagName() );
              terminar( ctx, h, "step", s.getName(), Sev.WARNING );
              out.add( h );
            }
          }
        }
      } else {
        for ( JobEntryCopy c : ctx.job().getJobCopies() ) {
          for ( Element[] ep : Nodos.hojas( ctx.xml( c ) ) ) {
            if ( TAG_CLAVE.matcher( ep[ 0 ].getTagName() ).matches() && clasificar( ep[ 0 ].getTextContent() ) != null ) {
              Hallazgo h = ctx.enEntrada( Sev.WARNING, c, ep[ 0 ].getTagName() );
              terminar( ctx, h, "entry", c.getName(), Sev.WARNING );
              out.add( h );
            }
          }
        }
      }
    }

    private void terminar( Ctx ctx, Hallazgo h, String tipo, String nombre, Sev sev ) {
      // las comillas del nombre van en el texto de cada idioma ('X' en inglés, «X» en es/pt)
      String donde = tipo == null ? ctx.t( "word.document" ) : ctx.t( "word." + tipo, "name", nombre );
      h.message = ctx.t( sev == Sev.ERROR ? "password_in_file.msg.plain" : "password_in_file.msg.encrypted", "where", donde );
      h.evidencia( "stored", sev == Sev.ERROR ? "plain text" : "Encrypted (reversible)" );
      String var = variable( nombre );
      h.fix = Arreglo.asistido( Arreglo.REPLACE_VALUE ).vista( var ).param( "variable", var.substring( 2, var.length() - 1 ) );
    }

    private static int numero( String s ) {
      try {
        return Integer.parseInt( s.trim() );
      } catch ( RuntimeException e ) {
        return 0;
      }
    }
  }
}
