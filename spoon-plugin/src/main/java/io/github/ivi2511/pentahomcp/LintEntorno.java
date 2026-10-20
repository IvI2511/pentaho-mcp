package io.github.ivi2511.pentahomcp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.lint.Entorno;
import io.github.ivi2511.pentahomcp.lint.TiempoAgotado;

/**
 * Lo que el motor del linter (lint/) le pide al plugin: campos con timeout en el pool de RunMotor, las salidas
 * normales de TransOps y la lista blanca de solo lectura del modo profundo (docs/linter-diseno.md §3.3, §5). Vive
 * en el paquete base porque esas clases son package-private. Una instancia por análisis, sobre la copia.
 */
final class LintEntorno implements Entorno {

  /** Lo máximo que se espera por los campos de un step (getPrevStepFields puede conectarse a la BD). */
  static final long MS_CAMPOS = 20000;
  static final long MS_TOTAL = 120000;

  /**
   * Steps que solo leen (§3.3): entradas de archivo, Table input, Data grid y generadores; transformación,
   * filtro, orden, joins y búsquedas en memoria. Una lista negra no alcanza: RunMotor.escribe deja pasar en 9.4
   * Call DB procedure, Dynamic SQL row, REST/HTTP, Trans/Job executor, Mapping, Metadata injection, SSH, Zip,
   * SFTP put, Set session variables, JavaScript/UDJC y productores de colas. Sin probar contra los 216 tipos [NP].
   */
  static final Set<String> SOLO_LECTURA = new HashSet<String>( Arrays.asList(
    // entrada
    "CsvInput", "DataGrid", "FixedInput", "RandomValue", "RowGenerator", "getXMLData", "GetFileNames",
    "GetFilesRowsCount", "GetSubFolders", "SystemInfo", "ParallelGzipCsvInput", "JsonInput", "LoadFileInput",
    "AccessInput", "ExcelInput", "PropertyInput", "TableInput", "TextFileInput", "OldTextFileInput", "XBaseInput",
    "XMLInputStream", "YamlInput", "SASInput", "LDIFInput", "ShapeFileReader", "CubeInput",
    // transformación
    "CheckSum", "Constant", "FieldsChangeSequence", "AddXML", "Calculator", "ClosureGenerator", "ConcatFields",
    "NumberRange", "ReplaceString", "Denormaliser", "Flattener", "Normaliser", "SelectValues", "SetValueField",
    "SetValueConstant", "SortRows", "SplitFieldToRows3", "FieldSplitter", "StringOperations", "StringCut",
    "Unique", "UniqueRowsByHashSet", "ValueMapper", "Formula", "RegexEval",
    // flujo
    "Dummy", "FilterRows", "SwitchCase", "Append", "BlockUntilStepsFinish", "BlockingStep", "DetectEmptyStream",
    "DetectLastRow", "PrioritizeStreams", "Abort",
    // joins, búsquedas en memoria, estadística, utilidades sin efectos
    "JoinRows", "MergeJoin", "MergeRows", "MultiwayMergeJoin", "SortedMerge", "XMLJoin", "StreamLookup", "FuzzyMatch",
    "AnalyticQuery", "GroupBy", "MemoryGroupBy", "ReservoirSampling", "SampleRows", "UnivariateStats",
    "CloneRow", "IfNull", "NullIf", "WriteToLog", "StepMetastructure", "CreditCardValidator", "Validator",
    "GetVariable", "RowsFromResult" ) );

  private final TransMeta tm;
  private final long fin;
  private volatile boolean cancelado;

  LintEntorno( AbstractMeta copia ) {
    this( copia, MS_TOTAL );
  }

  LintEntorno( AbstractMeta copia, long presupuestoMs ) {
    this.tm = copia instanceof TransMeta ? (TransMeta) copia : null;
    this.fin = System.currentTimeMillis() + presupuestoMs;
  }

  void cancelar() {
    cancelado = true;
  }

  @Override
  public RowMetaInterface salida( StepMeta s ) throws KettleStepException {
    return campos( s, false );
  }

  @Override
  public RowMetaInterface entrada( StepMeta s ) throws KettleStepException {
    return campos( s, true );
  }

  private RowMetaInterface campos( final StepMeta s, final boolean entrada ) throws KettleStepException {
    if ( tm == null ) {
      throw new KettleStepException( "not a transformation" );
    }
    long ms = Math.min( MS_CAMPOS, msRestantes() );
    if ( ms <= 0 ) {
      throw new TiempoAgotado( "no time left" );
    }
    try {
      return RunMotor.enSegundoPlano( () -> entrada ? tm.getPrevStepFields( s ) : tm.getStepFields( s ), ms, null,
        "Computing the fields of '" + s.getName() + "'" );
    } catch ( ToolError e ) { // enSegundoPlano solo lanza ToolError al vencer el plazo
      throw new TiempoAgotado( e.getMessage() );
    } catch ( KettleStepException e ) {
      throw e;
    } catch ( Exception e ) {
      throw new KettleStepException( RunMotor.primeraLinea( e ), e );
    }
  }

  @Override
  public int salidasNormales( TransMeta t, StepMeta s ) {
    return TransOps.salidasNormales( t, s );
  }

  @Override
  public boolean soloLectura( StepMeta s ) {
    return s.getStepID() != null && SOLO_LECTURA.contains( s.getStepID() ) && !RunMotor.escribe( s );
  }

  @Override
  public boolean cancelado() {
    return cancelado;
  }

  @Override
  public long msRestantes() {
    return fin - System.currentTimeMillis();
  }
}
