package io.github.ivi2511.pentahomcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.gui.Point;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.ui.spoon.Spoon;

/**
 * Herramientas para editar jobs (.kjb) abiertos en Spoon: entradas y hops. La logica vive en
 * {@link JobSoporte} (sin GUI); aca se resuelve el documento, se corre en el hilo de UI, se
 * registra el undo en Spoon y se refresca el lienzo.
 *
 * ordenar_lienzo y agregar_nota (tambien para jobs) estan en TransTools.
 */
final class JobTools {

  private static final String CONDICION = "Cuando sigue el hop: si_ok (verde, solo si la entrada anterior "
    + "termino bien), si_falla (rojo, solo si fallo) o siempre (negro, incondicional). Desde START es siempre "
    + "incondicional.";

  private JobTools() {
  }

  /** Undo que va al historial de Spoon (Editar > Deshacer, Ctrl+Z). */
  private static JobSoporte.Undo undoSpoon( final Spoon sp, final JobMeta jm ) {
    return new JobSoporte.Undo() {
      @Override
      public void nuevo( Object[] objs, int[] idx, boolean nextAlso ) {
        sp.addUndoNew( jm, objs, idx, nextAlso );
      }

      @Override
      public void borrado( Object[] objs, int[] idx ) {
        sp.addUndoDelete( jm, objs, idx );
      }

      @Override
      public void cambio( Object[] antes, Object[] despues, int[] idx ) {
        sp.addUndoChange( jm, antes, despues, idx );
      }

      @Override
      public void posicion( Object[] objs, int[] idx, Point[] antes, Point[] despues ) {
        sp.addUndoPosition( jm, objs, idx, antes, despues );
      }
    };
  }

  /** Una edicion sobre el job: corre en UI, con undo de Spoon, y despues refresca. */
  private interface Edicion {
    ObjectNode aplicar( Spoon sp, JobMeta jm, JobSoporte.Undo undo ) throws Exception;
  }

  private static Object editar( final JsonNode a, final Edicion e ) throws Exception {
    return Docs.ui( () -> {
      Spoon sp = Docs.spoon();
      JobMeta jm = Docs.job( a );
      ObjectNode o = e.aplicar( sp, jm, undoSpoon( sp, jm ) );
      Docs.touched( jm );
      o.put( "documento", Docs.id( jm ) );
      return o;
    } );
  }

  static void register( McpHttpServer s ) {

    s.add( Tools.tool( "tipos_de_entrada",
      "Busca tipos de entrada de job (job entries) instalados en este Spoon. Usala antes de agregar_entrada "
        + "cuando no sepas el id (<type>) exacto. Sin argumentos devuelve las categorias con su cantidad; con "
        + "'buscar' (palabras en id, nombre o descripcion) o 'categoria' devuelve id, nombre en pantalla, "
        + "categoria y descripcion. Ids comunes: TRANS (ejecutar transformacion), JOB (ejecutar job), SUCCESS, "
        + "ABORT, SQL, SET_VARIABLES, WRITE_TO_LOG, FILE_EXISTS, EVAL, SIMPLE_EVAL. START y DUMMY son "
        + "especiales: se crean con agregar_entrada tipo=START / tipo=DUMMY.",
      Tools.schema().str( "buscar", "Palabras a buscar (ej. 'file exists', 'sql', 'mail')", false )
        .str( "categoria", "Categoria (o parte), ej. 'General', 'File management', 'Conditions'", false ).build(),
      a -> JobSoporte.tipos( Tools.optStr( a, "buscar", null ), Tools.optStr( a, "categoria", null ) ) ) );

    s.add( Tools.tool( "plantilla_entrada",
      "XML de configuracion por defecto de un tipo de entrada de job, sin el envoltorio (<name>, <type>, "
        + "posicion). Usala antes de agregar_entrada o configurar_entrada para saber que tags lleva: despues "
        + "manda en config_xml solo los que cambian. Ejemplo TRANS: <specification_method>filename"
        + "</specification_method><filename>${Internal.Entry.Current.Directory}/carga.ktr</filename>.",
      Tools.schema().str( "tipo", "Id del tipo (TRANS, JOB, SUCCESS, ABORT..., o START/DUMMY) o su nombre en "
        + "pantalla", true ).build(),
      a -> {
        String t = JobSoporte.resolverTipo( Tools.str( a, "tipo" ) );
        if ( JobSoporte.SPECIAL.equals( t ) ) {
          t = JobSoporte.START;
        }
        return "<!-- " + t + " -->\n" + JobSoporte.plantilla( t );
      } ) );

    s.add( Tools.tool( "agregar_entrada",
      "Agrega una entrada al job abierto (aparece en vivo en el lienzo, seleccionada) y, con despues_de, la "
        + "conecta con un hop. Es la forma de armar un job paso a paso: START primero (tipo=START, uno solo por "
        + "job), despues cada entrada con despues_de=<la anterior>. Posicion: con despues_de, a su derecha "
        + "(+150; si el lugar esta ocupado, mas abajo); sin nada, a la derecha de la ultima entrada; x/y la "
        + "fuerzan. config_xml: solo los tags que cambian respecto de plantilla_entrada. En una entrada "
        + "Transformation (TRANS) o Job (JOB) la ruta conviene relativa a la carpeta del job: "
        + "<specification_method>filename</specification_method><filename>${Internal.Entry.Current.Directory}"
        + "/archivo.ktr</filename> (para JOB, .kjb). Parametros a la transformacion: <parameters>"
        + "<pass_all_parameters>Y</pass_all_parameters><parameter><name>P</name><stream_name/><value>v</value>"
        + "</parameter></parameters>. Para cerrar un job: SUCCESS (si_ok) y ABORT (si_falla).",
      Tools.schema().documento()
        .str( "tipo", "Id del tipo de entrada: START, DUMMY, TRANS, JOB, SUCCESS, ABORT, SQL... (ver "
          + "tipos_de_entrada)", true )
        .str( "nombre", "Nombre de la entrada, unico en el job", true )
        .str( "config_xml", "Configuracion: tags de primer nivel que reemplazan a los de la plantilla "
          + "(opcional)", false )
        .integer( "x", "Posicion x en el lienzo (opcional)", false )
        .integer( "y", "Posicion y en el lienzo (opcional)", false )
        .str( "despues_de", "Entrada existente desde la que sale un hop hacia la nueva (opcional)", false )
        .strEnum( "condicion", CONDICION + " Por defecto si_ok.", false, "siempre", "si_ok", "si_falla" ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.agregar( jm, Tools.str( a, "tipo" ), Tools.optStr( a, "nombre",
        null ), Tools.optStr( a, "config_xml", null ), Tools.optInteger( a, "x" ), Tools.optInteger( a, "y" ),
        Tools.optStr( a, "despues_de", null ), Tools.optStr( a, "condicion", null ), sp.getRepository(),
        sp.getMetaStore(), u ) ) ) );

    s.add( Tools.tool( "configurar_entrada",
      "Cambia la configuracion de una entrada existente: mezcla config_xml sobre su configuracion ACTUAL "
        + "(cada tag que mandes reemplaza al que habia; lo demas queda). Conserva nombre y posicion; se "
        + "deshace con Ctrl+Z. Usala para corregir la ruta de una TRANS/JOB, el mensaje de un ABORT, etc. Mira "
        + "antes ver_entrada. Para renombrar usa renombrar_entrada.",
      Tools.schema().documento().str( "entrada", "Nombre de la entrada", true )
        .str( "config_xml", "Tags de primer nivel a reemplazar, ej. <filename>${Internal.Entry.Current.Directory}"
          + "/otra.ktr</filename>", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.configurar( jm, Tools.str( a, "entrada" ),
        Tools.str( a, "config_xml" ), sp.getRepository(), sp.getMetaStore(), u ) ) ) );

    s.add( Tools.tool( "ver_entrada",
      "Lee una entrada del job: tipo, posicion, su configuracion en XML (contrasenias enmascaradas) y los "
        + "hops que entran y salen con su condicion. Usala antes de configurar_entrada o para revisar un job "
        + "que abrio el usuario. Para ver el job entero usa ver_documento.",
      Tools.schema().documento().str( "entrada", "Nombre de la entrada", true ).build(),
      a -> Docs.ui( () -> {
        JobMeta jm = Docs.job( a );
        ObjectNode o = JobSoporte.ver( jm, Tools.str( a, "entrada" ) );
        o.put( "documento", Docs.id( jm ) );
        return o;
      } ) ) );

    s.add( Tools.tool( "renombrar_entrada",
      "Cambia el nombre de una entrada del job (los hops la siguen). Falla si el nombre ya existe. Se "
        + "deshace con Ctrl+Z.",
      Tools.schema().documento().str( "entrada", "Nombre actual", true )
        .str( "nuevo_nombre", "Nombre nuevo, unico en el job", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.renombrar( jm, Tools.str( a, "entrada" ),
        Tools.str( a, "nuevo_nombre" ), u ) ) ) );

    s.add( Tools.tool( "mover_entrada",
      "Mueve una entrada del job a otra posicion del lienzo (x, y en pixeles). Para acomodar todo el job "
        + "de una vez usa ordenar_lienzo.",
      Tools.schema().documento().str( "entrada", "Nombre de la entrada", true )
        .integer( "x", "Posicion x", true ).integer( "y", "Posicion y", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.mover( jm, Tools.str( a, "entrada" ), Tools.integer( a, "x" ),
        Tools.integer( a, "y" ), u ) ) ) );

    s.add( Tools.tool( "borrar_entrada",
      "Borra una entrada del job junto con todos sus hops (entrantes y salientes). Un Ctrl+Z la restaura "
        + "con sus hops.",
      Tools.schema().documento().str( "entrada", "Nombre de la entrada", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.borrar( jm, Tools.str( a, "entrada" ), u ) ) ) );

    s.add( Tools.tool( "conectar_entradas",
      "Crea un hop entre dos entradas existentes del job. Para ramas: el mismo origen con un hop si_ok y "
        + "otro si_falla (p.ej. hacia un ABORT). Rechaza hops repetidos, hacia START y los que cierran un "
        + "bucle (salvo permitir_bucle=true, para reintentos intencionales).",
      Tools.schema().documento().str( "desde", "Entrada de origen", true ).str( "hacia", "Entrada de destino", true )
        .strEnum( "condicion", CONDICION + " Por defecto si_ok.", false, "siempre", "si_ok", "si_falla" )
        .bool( "permitir_bucle", "Aceptar que el hop cierre un ciclo (por defecto false)", false ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.conectar( jm, Tools.str( a, "desde" ), Tools.str( a, "hacia" ),
        Tools.optStr( a, "condicion", null ), Tools.optBool( a, "permitir_bucle", false ), u ) ) ) );

    s.add( Tools.tool( "desconectar_entradas",
      "Borra el hop entre dos entradas del job (las entradas quedan).",
      Tools.schema().documento().str( "desde", "Entrada de origen", true ).str( "hacia", "Entrada de destino", true )
        .build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.desconectar( jm, Tools.str( a, "desde" ), Tools.str( a, "hacia" ),
        u ) ) ) );

    s.add( Tools.tool( "cambiar_condicion",
      "Cambia cuando se sigue un hop existente del job (siempre / si_ok / si_falla) y/o lo habilita o "
        + "deshabilita. Usala para convertir un hop verde en rama de error, o para apagar una rama sin "
        + "borrarla.",
      Tools.schema().documento().str( "desde", "Entrada de origen", true ).str( "hacia", "Entrada de destino", true )
        .strEnum( "condicion", CONDICION, false, "siempre", "si_ok", "si_falla" )
        .bool( "habilitado", "false para deshabilitar el hop (queda gris), true para habilitarlo", false ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.cambiarCondicion( jm, Tools.str( a, "desde" ),
        Tools.str( a, "hacia" ), Tools.optStr( a, "condicion", null ),
        a.hasNonNull( "habilitado" ) ? Tools.optBool( a, "habilitado", true ) : null, u ) ) ) );
  }
}
