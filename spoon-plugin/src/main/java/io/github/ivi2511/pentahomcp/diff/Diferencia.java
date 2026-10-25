package io.github.ivi2511.pentahomcp.diff;

import java.util.ArrayList;
import java.util.List;

/** Un cambio con significado entre dos versiones: un step agregado, un salto desactivado, una propiedad que cambió... */
public final class Diferencia {

  /** Sobre qué es el cambio. El id va al JSON. */
  public enum Que {
    DOCUMENTO( "document" ), STEP( "step" ), ENTRADA( "entry" ), SALTO( "hop" ), ERRORES( "error_handling" ),
    CONEXION( "connection" ), PARAMETRO( "parameter" ), NOTA( "note" );

    public final String id;

    Que( String id ) {
      this.id = id;
    }
  }

  /** Qué le pasó. El id va al JSON; el símbolo, al texto y al lienzo. */
  public enum Como {
    AGREGADO( "added", "+" ), QUITADO( "removed", "−" ), CAMBIADO( "changed", "~" ),
    RENOMBRADO( "renamed", "»" ), MOVIDO( "moved", "↔" );

    public final String id;
    public final String simbolo;

    Como( String id, String simbolo ) {
      this.id = id;
      this.simbolo = simbolo;
    }
  }

  /** Una propiedad que cambió (o un elemento de una lista que se agregó o se quitó). */
  public static final class Propiedad {
    /** value | item_added | item_removed | order | position | type. */
    public static final String VALOR = "value";
    public static final String ELEMENTO_AGREGADO = "item_added";
    public static final String ELEMENTO_QUITADO = "item_removed";
    public static final String ORDEN = "order";
    public static final String POSICION = "position";
    public static final String TIPO = "type";

    public final String clase;
    /** La ruta aplanada (fields/field[saldo]/type) o el nombre de la propiedad. */
    public final String ruta;
    /** null = no estaba. Un secreto empieza por {@link Aplanar#SECRETO} y nunca se muestra. */
    public final String antes;
    public final String despues;

    public Propiedad( String clase, String ruta, String antes, String despues ) {
      this.clase = clase;
      this.ruta = ruta;
      this.antes = antes;
      this.despues = despues;
    }

    public boolean secreta() {
      return Aplanar.esSecreto( antes ) || Aplanar.esSecreto( despues );
    }
  }

  public final Que que;
  public final Como como;
  /** El nombre (el de después, salvo si se quitó). Para un salto: "desde -> hasta". */
  public final String nombre;
  /** Renombrado: el nombre de antes. */
  public String nombreAntes;
  /** El tipo de step o de entrada (o el de la conexión). */
  public String tipo;
  /** Salto: origen y destino (con los nombres de después; los de antes si se quitó). */
  public String desde;
  public String hasta;
  public final List<Propiedad> propiedades = new ArrayList<Propiedad>();
  /** Posición en el lienzo de antes y de después (steps, entradas, notas), o null. */
  public int[] posAntes;
  public int[] posDespues;

  public Diferencia( Que que, Como como, String nombre ) {
    this.que = que;
    this.como = como;
    this.nombre = nombre;
  }

  Diferencia con( Propiedad p ) {
    propiedades.add( p );
    return this;
  }
}
