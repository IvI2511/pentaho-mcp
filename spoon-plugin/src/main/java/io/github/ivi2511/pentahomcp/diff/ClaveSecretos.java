package io.github.ivi2511.pentahomcp.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * La marca de las contraseñas en {@code pentaho-live textconv}. git pasa por textconv cada versión por separado, así
 * que «(oculta)» igual en las dos hacía invisible un cambio de contraseña en {@code git diff}. Ahora cada contraseña
 * sale con una marca corta, HMAC-SHA256 del valor ya oculto ({@link Aplanar#secreto}) con una clave aleatoria que vive
 * solo en ese clon ({@code <git-common-dir>/pentaho-live/textconv.key}, dentro de {@code .git}: nunca se commitea):
 * dos versiones con la misma contraseña dan la misma marca y una contraseña cambiada, otra. La marca no es la
 * contraseña ni su hash, y sin la clave no sirve para probar contraseñas. Fuera de un repositorio no hay clave: queda
 * «(oculta)» a secas.
 */
public final class ClaveSecretos {

  static final String ARCHIVO = "pentaho-live" + File.separator + "textconv.key";

  private ClaveSecretos() {
  }

  /** Ocho caracteres hexadecimales para un valor secreto (con prefijo {@link Aplanar#SECRETO}); null si no lo es. */
  public static String marca( byte[] clave, String secreto ) {
    if ( clave == null || !Aplanar.esSecreto( secreto ) ) {
      return null;
    }
    try {
      Mac mac = Mac.getInstance( "HmacSHA256" );
      mac.init( new SecretKeySpec( clave, "HmacSHA256" ) );
      byte[] h = mac.doFinal( secreto.getBytes( StandardCharsets.UTF_8 ) );
      StringBuilder sb = new StringBuilder();
      for ( int i = 0; i < 4; i++ ) {
        sb.append( String.format( Locale.ROOT, "%02x", h[ i ] ) );
      }
      return sb.toString();
    } catch ( Exception e ) {
      return null;
    }
  }

  /**
   * La clave del repositorio de git que contiene {@code desde} (o el de GIT_DIR / GIT_COMMON_DIR, que git pone al
   * llamar a textconv); se crea la primera vez. null fuera de un repositorio o si no se puede leer ni crear.
   */
  public static byte[] clave( File desde ) {
    File comun = dirComun( desde );
    if ( comun == null ) {
      return null;
    }
    File f = new File( comun, ARCHIVO );
    try {
      if ( f.isFile() ) {
        byte[] b = Files.readAllBytes( f.toPath() );
        if ( b.length >= 16 ) {
          return b;
        }
      }
      byte[] b = new byte[ 32 ];
      new SecureRandom().nextBytes( b );
      File dir = f.getParentFile();
      if ( !dir.isDirectory() && !dir.mkdirs() ) {
        return null;
      }
      File tmp = new File( dir, "textconv.key." + System.nanoTime() + ".tmp" );
      Files.write( tmp.toPath(), b );
      if ( !tmp.renameTo( f ) ) {
        // otro textconv la creó a la vez: vale la suya
        Files.deleteIfExists( tmp.toPath() );
        byte[] otra = f.isFile() ? Files.readAllBytes( f.toPath() ) : null;
        return otra != null && otra.length >= 16 ? otra : null;
      }
      return b;
    } catch ( IOException | RuntimeException e ) {
      return null;
    }
  }

  /** El directorio común de git (el .git del clon principal también desde un worktree); null si no hay. */
  static File dirComun( File desde ) {
    String env = System.getenv( "GIT_COMMON_DIR" );
    if ( env != null && !env.isEmpty() && new File( env ).isDirectory() ) {
      return new File( env ).getAbsoluteFile();
    }
    env = System.getenv( "GIT_DIR" );
    if ( env != null && !env.isEmpty() && new File( env ).isDirectory() ) {
      return comun( new File( env ).getAbsoluteFile() );
    }
    for ( File d = desde == null ? null : desde.getAbsoluteFile(); d != null; d = d.getParentFile() ) {
      File g = new File( d, ".git" );
      if ( g.isDirectory() ) {
        return comun( g );
      }
      if ( g.isFile() ) {
        try {
          String t = new String( Files.readAllBytes( g.toPath() ), StandardCharsets.UTF_8 ).trim();
          if ( t.startsWith( "gitdir:" ) ) {
            File gd = new File( t.substring( 7 ).trim() );
            if ( !gd.isAbsolute() ) {
              gd = new File( d, gd.getPath() );
            }
            return gd.isDirectory() ? comun( gd.getAbsoluteFile() ) : null;
          }
        } catch ( IOException e ) {
          return null;
        }
        return null;
      }
    }
    return null;
  }

  /** Un gitdir de worktree tiene un archivo commondir que apunta al .git principal. */
  private static File comun( File gitdir ) {
    File cd = new File( gitdir, "commondir" );
    if ( cd.isFile() ) {
      try {
        String t = new String( Files.readAllBytes( cd.toPath() ), StandardCharsets.UTF_8 ).trim();
        File c = new File( t );
        if ( !c.isAbsolute() ) {
          c = new File( gitdir, t );
        }
        if ( c.isDirectory() ) {
          return c.getAbsoluteFile();
        }
      } catch ( IOException e ) {
        // el del worktree
      }
    }
    return gitdir;
  }
}
