# API interna de Spoon (PDI 9.4.0.0-343) para el plugin Pentaho MCP

Documento de diseño. Todas las firmas salen de `javap -p` sobre los jars de
`C:\pdi\data-integration\lib` (`kettle-core`, `kettle-engine`,
`kettle-ui-swt` 9.4.0.0-343, SWT 3.121). Donde se describe *comportamiento* (diálogos que abre,
orden de llamadas) se leyó el bytecode con `javap -c`. Convención: `Spoon` = `org.pentaho.di.ui.spoon.Spoon`.

Paquetes abreviados:

| Abreviatura | Paquete |
|---|---|
| `core` | `org.pentaho.di.core` |
| `trans` | `org.pentaho.di.trans` |
| `job` | `org.pentaho.di.job` |
| `ui.spoon` | `org.pentaho.di.ui.spoon` |

---

## 0. Resumen de decisiones

| Tema | Decisión |
|---|---|
| Tipo de plugin | `@LifecyclePlugin` + `core.lifecycle.LifecycleListener` (igual que `kettle-version-checker`). |
| Arranque | `onStart(LifeEventHandler)` corre **en el hilo de UI**, dentro de `Spoon.createContents()`. Ahí se levanta el `HttpServer` y se vuelve enseguida. |
| Hilos | El HTTP atiende en un pool propio (daemon). Todo lo que toque `Spoon`, `TransMeta`/`JobMeta` abiertos o widgets va por `Display.asyncExec` + `CompletableFuture` con timeout (`UiThread.call`). |
| Edición | Mutar el `TransMeta` vivo en el hilo de UI → `spoon.addUndo*` → `meta.setChanged()` → `spoon.refreshTree()` + `spoon.refreshGraph()` (o `graph.redraw()`). |
| Preview de filas | En segundo plano: snapshot `realClone(false)` en UI, `new Trans(copia)` en un hilo worker, `RowListener` sobre el step. El preview "de la GUI" abre siempre un `PreviewRowsDialog` **modal** → no sirve para automatizar. |
| Run visible | `spoon.executeTransformation(...)` con `transMeta.setShowDialog(false)`; exige archivo guardado y sin cambios (si no, sale un modal "guardá antes de ejecutar"). |
| JSON | Jackson 2.9.10 (`lib/jackson-*.jar`) es visible desde el plugin (verificado). No empaquetar Jackson en `lib/` del plugin. |

---

## 1. Cómo arranca el plugin

### 1.1 Opciones evaluadas

| Opción | Anotación / interfaz | Cuándo se llama | Veredicto |
|---|---|---|---|
| **Lifecycle** | `@core.annotations.LifecyclePlugin(id, name, classLoaderGroup="")` + `core.lifecycle.LifecycleListener` | `onStart` en `Spoon.createContents()` (hilo UI) tras `init(null)` y `openSpoon()`, antes de `start(commandLineOptions)` y `loadLastUsedFiles()`. `onExit` en `Spoon.quitFile`. | **Elegida.** Probada por `kettle-version-checker`; tipo registrado en `KettleEnvironment.init()`; solo la usa Spoon (Pan/Kitchen/Carte no la disparan). |
| SpoonPlugin | `@ui.spoon.SpoonPlugin(...)` + `SpoonPluginInterface { applyToContainer(String, XulDomContainer); SpoonLifecycleListener getLifecycleListener(); SpoonPerspective getPerspective(); }` | `SpoonLifecycleListener.onEvent(SpoonLifeCycleEvent.STARTUP)` vía `SpoonPluginManager.notifyLifecycleListeners` | Funciona, pero obliga a implementar XUL/perspectiva (aunque sea devolviendo null) y su registro depende de `SpoonPluginType` que Spoon agrega en `main`. Más superficie para nada. |
| ExtensionPoint | `@core.extension.ExtensionPoint(id, extensionPointId="SpoonStart", description)` + `ExtensionPointInterface.callExtensionPoint(LogChannelInterface, Object)` | En `Spoon.start()` tras `open()`; el objeto es `CommandLineOption[]` | Viable (`KettleExtensionPoint.SpoonStart` existe), pero el tipo `ExtensionPointPluginType` se registra en `KettleClientEnvironment` y el punto se llama una vez ya abierta la ventana; no aporta nada sobre Lifecycle. Útil después para enganchar `TransChanged`/`JobChanged`/`SpoonOpenSaveNew` si hiciera falta. |

Secuencia real (bytecode de `Spoon`):

```
Spoon.main
  ├─ Executors.newCachedThreadPool().submit(Spoon$1)  → KettleClientEnvironment.setClient(SPOON); KettleEnvironment.init()
  │                                                     (registra LifecyclePluginType y escanea plugins/)
  ├─ ... future.get()
  ├─ staticSpoon = new Spoon()        ← constructor: lifecycleSupport = new LifecycleSupport()
  │                                     → PluginRegistry.loadClass(...) de cada @LifecyclePlugin (instancia tu clase)
  └─ staticSpoon.start()   → setBlockOnOpen(false); open()
                               └─ createContents(Composite)
                                    ├─ init(null); openSpoon()
                                    └─ lifecycleSupport.onStart(this)   ← TU onStart (hilo UI). handler == Spoon
                             checkEnvironment(); ExtensionPoint "SpoonStart"; loadLastUsedFiles(); waitForDispose()
```

* `Spoon.getInstance()` (`public static Spoon getInstance()`) ya es no-null en `onStart` (`staticSpoon` se asigna en `main` antes de `start()` y también en el constructor).
* `LifecycleException` severa lanzada desde `onStart` → `MessageBox` en Spoon. El esqueleto captura todo y solo loguea.
* `onStart` corre en el hilo de UI: **no bloquear** (arrancar el server y volver).

Firmas:

```java
// core.lifecycle
public interface LifecycleListener {
  void onStart(LifeEventHandler handler) throws LifecycleException;
  void onExit(LifeEventHandler handler) throws LifecycleException;
}
public interface LifeEventHandler { void consume(LifeEventInfo info); }   // Spoon implementa LifeEventHandler

// core.annotations
public @interface LifecyclePlugin { String id(); String name(); String classLoaderGroup() default ""; }
```

### 1.2 Cómo escanea Kettle la carpeta `plugins/` (no OSGi)

* `PluginFolder.populateFolders(String)`: carpetas base = system property `KETTLE_PLUGIN_BASE_FOLDERS` (coma-separada) o por defecto `plugins` (relativo al cwd de Spoon = `data-integration`) + `~/.kettle/plugins`.
* `PluginFolder.findJarFiles(boolean)` recorre **recursivamente** buscando `*.jar`; **no entra** en carpetas llamadas `lib` (salvo `searchLibDir`) y se salta cualquier carpeta que contenga un archivo `.kettle-ignore`.
* `BasePluginType.findAnnotatedClassFiles(String annotationClassName)` usa `JarFileCache.getAnnotationDB(FileObject)` → `org.scannotation.AnnotationDB.getAnnotationIndex()`: lee el bytecode de cada `.class` buscando la anotación **runtime-visible**. No hace falta `plugin.xml` ni manifest especial.
* `BasePluginType.registerPluginJars()` → por cada clase anotada crea un `URLClassLoader` temporal (`createUrlClassLoader(URL jar, ClassLoader)`) con **el jar + todos los `*.jar` de `<carpeta-del-jar>/lib/`**, carga la clase, y `handlePluginAnnotation(Class, Annotation, List<String> libraries, boolean nativePlugin, URL pluginFolder)` registra el plugin con esa lista de librerías.
* Al instanciar (`PluginRegistry.loadClass(PluginInterface, Class<T>)` → `createClassLoader`): `new KettleURLClassLoader(URL[] libraries, PluginRegistry.class.getClassLoader(), description)`. `KettleURLClassLoader.loadClass` es **child-first** (`loadClassFromThisLoader` y luego `loadClassFromParent`). El padre es el classloader del launcher, que tiene `lib/*.jar`, `libswt/win64/swt.jar` y `classes/`.
* `plugin.xml` solo lo usan los plugins "XML" (ej. `pdi-pur-plugin`); con anotaciones no hace falta.

Estructura a copiar (igual que `kettle-version-checker`):

```
plugins/
  pentaho-mcp/
    pentaho-mcp-core-0.1.0.jar     ← clases anotadas, en la raíz de la carpeta
    lib/                           ← dependencias PROPIAS (vacío: todo lo que usamos está en data-integration/lib)
    version.xml                    ← <version>…</version> (informativo, lo traen todos los plugins)
```

Reglas:
* **No** poner en `lib/` jars que ya estén en `data-integration/lib` (kettle-*, jackson, swt…): el loader es child-first y tendrías dos copias de la misma clase (ClassCastException / LinkageError).
* La clase anotada necesita constructor público sin argumentos.
* Compilar a bytecode 8 (`--release 8`); Spoon corre aquí con Java 21 y lo acepta.
* Smoke test hecho fuera de Spoon (`KETTLE_PLUGIN_BASE_FOLDERS=plugin/dist`): el registry encontró `[PentahoMCP]`, lo cargó con `KettleURLClassLoader`, `onStart` abrió el puerto y `/ping` respondió `{"ok":true,"spoon":false,...,"jackson":"2.9.10-2"}`. El aviso `InaccessibleObjectException ... sun.net.www.protocol.jar` que aparece es de `closeClassLoader` sin `--add-opens`; `Spoon.bat` ya pasa `%JAVA_ADD_OPENS%`, y es inocuo.
* `com.sun.net.httpserver` (módulo `jdk.httpserver`) está disponible: en una app de classpath el JDK resuelve por defecto todos los módulos `jdk.*` que exportan API; compila con `--release 8`.

---

## 2. Spoon y el hilo de UI

```java
public static Spoon Spoon.getInstance();
public org.eclipse.swt.widgets.Display Spoon.getDisplay();
public org.eclipse.swt.widgets.Shell   Spoon.getShell();          // heredado de ApplicationWindow
public LogChannelInterface Spoon.getLog();
public Repository Spoon.getRepository();                           // null si no hay repo conectado
public DelegatingMetaStore Spoon.getMetaStore();
public PropsUI Spoon.getProperties();
public SpoonDelegates delegates;                                   // campo público
public TabSet tabfolder;                                           // campo público (pestañas de trans/jobs)

// SWT
public void Display.asyncExec(Runnable);
public void Display.syncExec(Runnable);
public Thread Display.getThread();
public boolean Display.isDisposed();
```

Patrón (implementado en `plugin/src/.../UiThread.java`):

```java
static <T> T call(Callable<T> task, long timeoutMs) throws Exception {
  Display d = Spoon.getInstance().getDisplay();
  if (d.getThread() == Thread.currentThread()) return task.call();
  CompletableFuture<T> f = new CompletableFuture<>();
  d.asyncExec(() -> { try { f.complete(task.call()); } catch (Throwable t) { f.completeExceptionally(t); } });
  return f.get(timeoutMs, TimeUnit.MILLISECONDS);
}
```

* Preferir `asyncExec`+timeout a `syncExec`: si el usuario tiene un modal abierto el runnable igual corre (los modales hacen `readAndDispatch`), pero si la UI está colgada el hilo HTTP no queda bloqueado para siempre.
* "UI lista": cuando corre `onStart` la shell ya existe. Para las peticiones, basta con `Spoon.getInstance() != null && getDisplay() != null && !getShell().isDisposed()`. Los archivos recientes se abren *después* de `onStart` (en `start()`), así que una petición muy temprana puede no ver pestañas todavía.
* **Nunca** llamar desde la UI a algo que espere al hilo HTTP (deadlock).
* Trabajo pesado (ejecutar, `getStepFields` sobre steps de BD, `checkSteps`) fuera del hilo UI: tomar snapshot en UI (`(TransMeta) transMeta.realClone(false)`) y procesar la copia en un worker.

Perspectiva: si el usuario está en otra (Schedule, Model…), `getActiveMeta()` pregunta a la perspectiva activa.

```java
SpoonPerspectiveManager.getInstance().activatePerspective(MainSpoonPerspective.class)   // throws KettleException
SpoonPerspectiveManager.getInstance().getActivePerspective()                             // SpoonPerspective
```

---

## 3. Pestañas y documento activo

```java
// Spoon
public EngineMetaInterface getActiveMeta();          // TransMeta o JobMeta de la pestaña activa (null si navegador/bienvenida)
public TransMeta getActiveTransformation();          // null si la activa no es trans
public JobMeta   getActiveJob();
public TransGraph getActiveTransGraph();
public JobGraph   getActiveJobGraph();
public TabItemInterface getActiveTabitem();
public String getActiveTabText();
public TabSet getTabSet();
public UndoInterface getActiveUndoInterface();

// delegates.tabs : ui.spoon.delegates.SpoonTabsDelegate
public List<TabMapEntry> getTabs();
public TabMapEntry getTab(TabItem);
public TabMapEntry findTabMapEntry(Object managedObject);                       // por TransMeta/JobMeta
public TabMapEntry findTabMapEntry(String tabText, TabMapEntry.ObjectType);
public TabMapEntry findTabForTransformation(TransMeta) throws KettleFileException;
public void renameTabs();
public String makeTabName(EngineMetaInterface, boolean showLocation);
public boolean tabClose(TabItem) throws KettleException;                        // puede preguntar "guardar cambios"

// ui.spoon.TabMapEntry
public TabItem getTabItem();
public TabItemInterface getObject();          // TransGraph | JobGraph | SpoonBrowser | ...
public TabMapEntry.ObjectType getObjectType(); // TRANSFORMATION_GRAPH, JOB_GRAPH, SLAVE_SERVER, BROWSER, PLUGIN
public String getFilename();
public String getObjectName();

// TransGraph / JobGraph (implementan TabItemInterface)
public TransMeta TransGraph.getTransMeta();      public JobMeta JobGraph.getJobMeta();
public Object getManagedObject();

// delegates.trans / delegates.jobs
public List<TransMeta> SpoonTransformationDelegate.getTransformationList();
public TransMeta[]     SpoonTransformationDelegate.getLoadedTransformations();
public TransGraph      SpoonTransformationDelegate.findTransGraphOfTransformation(TransMeta);
public List<JobMeta>   SpoonJobDelegate.getJobList();
public JobMeta[]       SpoonJobDelegate.getLoadedJobs();
public JobGraph        SpoonJobDelegate.findJobGraphOfJob(JobMeta);

// org.pentaho.xul.swt.tab.TabSet / TabItem
public TabItem TabSet.getSelected();
public int     TabSet.getSelectedIndex();
public void    TabSet.setSelected(TabItem);   // → CTabFolder.setSelection + notifySelectListeners → Spoon.tabSelected(...)
public String  TabItem.getText();
public boolean TabItem.isChanged();
```

Listar pestañas (en UI):

```java
for (TabMapEntry e : spoon.delegates.tabs.getTabs()) {
  Object o = e.getObject();
  if (o instanceof TransGraph) { TransMeta tm = ((TransGraph) o).getTransMeta(); ... }
  else if (o instanceof JobGraph) { JobMeta jm = ((JobGraph) o).getJobMeta(); ... }
  boolean active = e.getTabItem() == spoon.tabfolder.getSelected();
}
```

Activar una pestaña: `spoon.tabfolder.setSelected(spoon.delegates.tabs.findTabMapEntry(meta).getTabItem());`
(dispara el listener de selección de Spoon, que actualiza menús, árbol y título).

Identificador estable para la IA: no hay id propio; usar `filename` si existe, si no `name` + índice en `getTabs()`. Conviene que el plugin mantenga un `WeakHashMap<AbstractMeta,String>` con UUIDs propios.

---

## 4. Crear / abrir / guardar

```java
// Spoon
public void newTransFile();                // new TransMeta + addObserver(spoon) + shared objects + nombre por defecto + addTransGraph + refreshTree
public void newJobFile();
public void addTransGraph(TransMeta);      // abre una pestaña para un TransMeta ya construido (y la selecciona)
public void addJobGraph(JobMeta);
public void openFile(String fname, boolean importfile);
public void openFile(String fname, VariableSpace, boolean importfile);
public boolean saveToFile(EngineMetaInterface) throws KettleException;
public boolean saveToFile(EngineMetaInterface, boolean) throws KettleException;
public boolean saveFileAs(EngineMetaInterface) throws KettleException;   // ABRE DIÁLOGO
public boolean save(EngineMetaInterface, String fname, boolean export);
public boolean saveMeta(EngineMetaInterface, String fname);
public boolean saveMeta(EngineMetaInterface, String fname, boolean);
public boolean closeFile(); public boolean closeFile(boolean force);
public boolean tabClose(TabItem, boolean force);
public void refreshTree(); public void refreshTree(AbstractMeta); public void setShellText();

// AbstractMeta
public void setFilename(String);  public String getFilename();
public void setName(String);      public String getName();   public void nameFromFilename();
```

* **Nueva**: `spoon.newTransFile()` (en UI) → queda activa; leer con `spoon.getActiveTransformation()`. Alternativa con control total: `TransMeta tm = new TransMeta(); tm.setName(...); tm.addObserver(spoon); tm.setRepository(spoon.getRepository()); tm.setMetaStore(spoon.getMetaStore()); spoon.addTransGraph(tm); spoon.refreshTree();` (`addObserver(PDIObserver)` hace que el `*` del título de la pestaña siga a `setChanged`).
* **Abrir por ruta**: `spoon.openFile(path, false)`. Ojo, puede abrir modales: si el archivo no existe (`SimpleMessageDialog` "MissingRecentFile") y si la extensión/raíz XML no es conocida (`MessageBox` "UnknownFileType"). El plugin debe validar antes `new File(path).isFile()` y extensión `.ktr`/`.kjb`. Si faltan plugins de steps, se loguea `KettleMissingPluginsException` y se cargan como `MissingTrans`. Si el archivo ya estaba abierto, conviene buscarlo antes en `getTabs()` (comparando `getFilename()`) y solo activar su pestaña.
* **Guardar sin diálogo**: con filename seteado, `saveToFile` no abre diálogo:
  ```java
  meta.setFilename(path);              // ruta absoluta .ktr/.kjb
  spoon.saveToFile(meta);              // → save(meta, filename, false) → FileListener → saveMeta(...)
  spoon.delegates.tabs.renameTabs(); spoon.refreshTree();   // saveToFile ya hace renameTabs/refreshTree/enableMenus
  ```
  `saveMeta` escribe `XMLHandler.getXMLHeader() + meta.getXML()` vía `KettleVFS.getOutputStream`, agrega a "recientes", `clearChanged()` y `setShellText()`. Si el nombre es el por defecto, `nameFromFilename()`.
  * Si `getFilename()` es null/vacío, `saveToFile` cae en `saveAsNew` → **diálogo**.
  * `save()` rechaza con un `MessageBox` modal nombres de archivo con caracteres no ASCII (patrón `\p{ASCII}+`). Validar antes en el plugin (acentos, ñ).
  * Si la perspectiva activa implementa `SpoonPerspectiveOpenSaveInterface`, delega en ella; en la perspectiva principal no aplica.
  * Con repositorio conectado (`spoon.getRepository()!=null`) `saveToFile` guarda en el repo. Este diseño asume archivos.
* **Cerrar**: `spoon.tabClose(tabItem, true)` (force = no pregunta).

---

## 5. Editar una transformación

### 5.1 Firmas

```java
// trans.TransMeta
public void addStep(StepMeta);                    // add + setParentTransMeta(this) + listeners + clearCaches(); NO dispara setChanged()
public void addStep(int, StepMeta);
public void addOrReplaceStep(StepMeta);
public void removeStep(int);
public void setStep(int, StepMeta);
public int  indexOfStep(StepMeta);
public StepMeta findStep(String name);
public StepMeta getStep(int);  public int nrSteps();  public List<StepMeta> getSteps();
public String getAlternativeStepname(String);     // nombre único ("X 2")
public void addTransHop(TransHopMeta);  public void addTransHop(int, TransHopMeta);
public void removeTransHop(int);        public void removeTransHop(TransHopMeta);
public int  indexOfTransHop(TransHopMeta);
public TransHopMeta findTransHop(StepMeta from, StepMeta to);
public TransHopMeta findTransHop(StepMeta from, StepMeta to, boolean includeDisabled);
public TransHopMeta findTransHop(String name);
public List<TransHopMeta> findAllTransHopFrom(StepMeta);
public TransHopMeta findTransHopTo(StepMeta);
public boolean hasLoop(StepMeta);                 // validar antes de agregar un hop
public boolean isStepUsedInTransHops(StepMeta);
public void selectAll(); public void unselectAll(); public List<StepMeta> getSelectedSteps();
public void notifyAllListeners(StepMeta oldMeta, StepMeta newMeta);   // tras renombrar
public void clearCaches();
public void setChanged(); public void clearChanged(); public boolean hasChanged();   // de AbstractMeta / override
public Object realClone(boolean doClear);

// trans.step.StepMeta
public StepMeta(String stepid, String stepname, StepMetaInterface stepMetaInterface);
public StepMeta(String stepname, StepMetaInterface);      // stepid se deduce del registry
public StepMeta(Node stepnode, List<DatabaseMeta> databases, IMetaStore metaStore) throws KettleXMLException, KettlePluginLoaderException;
public static StepMeta fromXml(String xml);                // <step> sin bases de datos (Collections.emptyList()); envuelve excepciones en RuntimeException
public String getXML() throws KettleException;            // <step>…</step> completo (incluye <GUI>)
public String getXML(boolean includeInterface) throws KettleException;
public String getStepID();  public String getTypeId();
public String getName(); public void setName(String);
public String getDescription(); public void setDescription(String);
public void setLocation(int x, int y); public void setLocation(Point); public Point getLocation();
public void setDraw(boolean); public boolean isDrawn();
public void setSelected(boolean); public boolean isSelected();
public void setCopies(int); public int getCopies(); public void setCopiesString(String);
public void setDistributes(boolean); public boolean isDistributes();
public StepMetaInterface getStepMetaInterface(); public void setStepMetaInterface(StepMetaInterface);
public void replaceMeta(StepMeta);
public Object clone();
public boolean isMissing();
public StepErrorMeta getStepErrorMeta(); public void setStepErrorMeta(StepErrorMeta);
public void setChanged();

// trans.TransHopMeta (extends base.BaseHopMeta<StepMeta>)
public TransHopMeta(StepMeta from, StepMeta to, boolean enabled);
public TransHopMeta(StepMeta from, StepMeta to);
public TransHopMeta(Node hopnode, List<StepMeta> steps) throws KettleXMLException;   // <hop><from/><to/><enabled/></hop>
public StepMeta getFromStep(); public StepMeta getToStep();
public void setEnabled(boolean); public boolean isEnabled();
public void setErrorHop(boolean); public boolean isErrorHop();
public String getXML();

// trans.step.StepMetaInterface
void setDefault();
String getXML() throws KettleException;                                           // solo el cuerpo (sin <step>)
void loadXML(Node stepnode, List<DatabaseMeta> databases, IMetaStore metaStore) throws KettleXMLException;
void searchInfoAndTargetSteps(List<StepMeta> steps);
StepIOMetaInterface getStepIOMeta(); void resetStepIoMeta();
void getFields(RowMetaInterface inputRowMeta, String name, RowMetaInterface[] info, StepMeta nextStep, VariableSpace space, Repository rep, IMetaStore metaStore) throws KettleStepException;
default boolean cleanAfterHopFromRemove(StepMeta); default boolean cleanAfterHopToRemove(StepMeta);
String getDialogClassName();

// core.plugins
public static PluginRegistry PluginRegistry.getInstance();
public PluginInterface findPluginWithId(Class<? extends PluginTypeInterface>, String id);
public <T extends PluginInterface, K extends PluginTypeInterface> List<T> getPlugins(Class<K>);
public Object loadClass(PluginInterface) throws KettlePluginException;
public <T> T loadClass(PluginInterface, Class<T>) throws KettlePluginException;
public String getPluginId(Class<? extends PluginTypeInterface>, Object);
// PluginInterface: String[] getIds(); String getName(); String getDescription(); String getCategory(); String getImageFile();
// tipos: core.plugins.StepPluginType.class, core.plugins.JobEntryPluginType.class
```

### 5.2 Construir un `StepMeta` desde (plugin id, nombre, XML)

`new StepMeta(Node, …)` (bytecode) espera el **elemento `<step>`** y hace: lee `<name>` y `<type>` → `PluginRegistry.findPluginWithId(StepPluginType, type, true)`; si no existe crea un `MissingTrans` (¡no lanza excepción!) → `loadClass(plugin)` → `StepMetaInterface.loadXML(stepnode, databases, metaStore)` → lee `description`, `copies`, `distribute`, `custom_distribution`, `attributes`, `GUI/xloc`, `GUI/yloc`, `GUI/draw`, `partitioning`, `cluster_schema`, `remotesteps`.

Receta recomendada (en UI, sobre el `TransMeta` vivo):

```java
PluginInterface p = PluginRegistry.getInstance().findPluginWithId(StepPluginType.class, pluginId);
if (p == null) throw new IllegalArgumentException("step plugin desconocido: " + pluginId);

// 1) XML por defecto del step, para no dejar campos en null (loadXML NO aplica setDefault sobre lo que falta)
StepMetaInterface smi = (StepMetaInterface) PluginRegistry.getInstance().loadClass(p);
smi.setDefault();
String defaultsBody = smi.getXML();                       // cuerpo sin <step>

// 2) Mezclar: tags de primer nivel que mande la IA reemplazan a los por defecto (merge por nombre de tag)
String body = mergeTopLevel(defaultsBody, xmlFragmentFromAI);

// 3) <step> completo
String name = transMeta.getAlternativeStepname(requestedName);
// XMLHandler.addTagValue(String tag, String val) escapa el valor y devuelve "<tag>val</tag>\n"
String xml = "<step>" + XMLHandler.addTagValue("name", name) + XMLHandler.addTagValue("type", pluginId)
           + "<copies>1</copies><distribute>Y</distribute>" + body
           + "<GUI><xloc>" + x + "</xloc><yloc>" + y + "</yloc><draw>Y</draw></GUI></step>";
Node node = XMLHandler.getSubNode(XMLHandler.loadXMLString(xml), "step");
StepMeta sm = new StepMeta(node, transMeta.getDatabases(), spoon.getMetaStore());
if (sm.isMissing()) throw ...;

transMeta.addStep(sm);
sm.getStepMetaInterface().searchInfoAndTargetSteps(transMeta.getSteps());   // ver 5.4
spoon.addUndoNew(transMeta, new Object[]{ sm }, new int[]{ transMeta.indexOfStep(sm) });
transMeta.setChanged();
spoon.refreshTree(); spoon.refreshGraph();
```

Alternativa sin XML: `new StepMeta(pluginId, name, smi)` + setters del `*Meta` concreto (requiere compilar contra cada clase; para una IA es más práctico el XML). Para saber qué tags lleva cada step, el repo ya tiene `catalogo/steps.json` / `catalogo/steps/`.

Conexiones referenciadas por nombre en el XML (`<connection>nombre</connection>`) se resuelven contra la lista `databases` que se pasa: la conexión tiene que existir antes en el `TransMeta` (ver §10).

### 5.3 Hops, borrar, renombrar, mover, seleccionar

```java
// hop
StepMeta a = transMeta.findStep("A"), b = transMeta.findStep("B");
if (transMeta.findTransHop(a, b, true) == null) {
  TransHopMeta h = new TransHopMeta(a, b, true);
  transMeta.addTransHop(h);
  if (transMeta.hasLoop(b)) { transMeta.removeTransHop(h); throw ...; }
  spoon.addUndoNew(transMeta, new Object[]{h}, new int[]{transMeta.indexOfTransHop(h)});
}

// borrar step (orden que usa Spoon en SpoonStepsDelegate.delSteps):
//  1) por cada hop que toca el step: clonar, idx = indexOfTransHop, removeTransHop(idx); acumular
//  2) spoon.addUndoDelete(transMeta, hopsClonados[], idxHops[])
//  3) idx = indexOfStep(step); removeStep(idx); spoon.addUndoDelete(transMeta, new Object[]{step}, new int[]{idx})
//  4) refreshTree(); refreshGraph()
// (o directamente spoon.delegates.steps.delStep(transMeta, stepMeta) / delSteps(transMeta, StepMeta[]) — sin diálogos,
//  incluye ExtensionPoint, undo y refresco)

// renombrar (como SpoonStepsDelegate.editStep)
StepMeta before = (StepMeta) sm.clone();
sm.setName(transMeta.getAlternativeStepname(nuevo));       // ojo: si ya se llama así, getAlternativeStepname lo cambia
transMeta.notifyAllListeners(before, sm);
spoon.addUndoChange(transMeta, new Object[]{before}, new Object[]{sm.clone()}, new int[]{transMeta.indexOfStep(sm)});

// mover
Point prev = sm.getLocation(); sm.setLocation(x, y);
spoon.addUndoPosition(transMeta, new Object[]{sm}, new int[]{idx}, new Point[]{prev}, new Point[]{new Point(x,y)});

// configurar (reemplazar la config completa de un step existente)
StepMeta before = (StepMeta) sm.clone();
StepMeta parsed = new StepMeta(nodoConMismoNombre, transMeta.getDatabases(), metaStore);
sm.setStepMetaInterface(parsed.getStepMetaInterface());   // o sm.replaceMeta(parsed) y restaurar location/name
sm.getStepMetaInterface().searchInfoAndTargetSteps(transMeta.getSteps());
spoon.addUndoChange(transMeta, new Object[]{before}, new Object[]{sm.clone()}, new int[]{idx});

// resaltar para que el usuario lo vea
transMeta.unselectAll(); sm.setSelected(true); graph.redraw();
// (no hay API pública para "hacer scroll hasta el step"; la ubicación la elige la IA dentro del área visible)
```

`Point` es `org.pentaho.di.core.gui.Point(int x, int y)` (no el de SWT).

### 5.4 Referencias entre steps (Filter rows `send_true_to`, Stream lookup `lookup_step`, Switch/Case…)

`TransMeta.loadXML(...)`: primero carga todos los `<step>`, después los `<step_error_handling><error>` (`new StepErrorMeta(VariableSpace, Node, List<StepMeta>)` → `sourceStep.setStepErrorMeta(...)`), y **después recorre todos los steps llamando `getStepMetaInterface().searchInfoAndTargetSteps(steps)`** antes de leer `<order><hop>`. Es decir: `loadXML` del step solo guarda el *nombre* del step referenciado; `searchInfoAndTargetSteps(List<StepMeta>)` resuelve el `StepMeta` real en los streams de `getStepIOMeta()`.

Consecuencias para el plugin:
* Después de agregar o reconfigurar un step con referencias, llamar `smi.searchInfoAndTargetSteps(transMeta.getSteps())` (y, para ser robusto, sobre **todos** los steps si se agregaron varios en lote: la referencia puede apuntar a un step creado después).
* El hop correspondiente (A→destino true/false) hay que crearlo igual; la referencia sola no dibuja el hop.
* Error handling: no viene dentro del `<step>`; se arma con `new StepErrorMeta(...)` y un hop con `setErrorHop(true)`.
* Al renombrar, `notifyAllListeners(old, new)` + `searchInfoAndTargetSteps` mantienen consistentes los steps que guardan nombre.

### 5.5 Undo/Redo para que Ctrl+Z del usuario funcione

```java
// base.AbstractMeta (TransMeta y JobMeta implementan core.gui.UndoInterface)
public static final int TYPE_UNDO_CHANGE, TYPE_UNDO_NEW, TYPE_UNDO_DELETE, TYPE_UNDO_POSITION;
public void addUndo(Object[] from, Object[] to, int[] pos, Point[] prev, Point[] curr, int type_of_change, boolean nextAlso);

// Spoon (preferir estas: además llaman setUndoMenu(ui) para habilitar Editar → Deshacer)
public void addUndoNew(UndoInterface, Object[] obj, int[] position);
public void addUndoNew(UndoInterface, Object[] obj, int[] position, boolean nextAlso);
public void addUndoDelete(UndoInterface, Object[] obj, int[] position);
public void addUndoDelete(UndoInterface, Object[] obj, int[] position, boolean nextAlso);
public void addUndoChange(UndoInterface, Object[] from, Object[] to, int[] position);
public void addUndoChange(UndoInterface, Object[] from, Object[] to, int[] position, boolean nextAlso);
public void addUndoPosition(UndoInterface, Object[] obj, int[] pos, Point[] prev, Point[] curr);
public void undoAction(UndoInterface); public void redoAction(UndoInterface);
```

Objetos soportados por el undo de trans: `StepMeta`, `TransHopMeta`, `NotePadMeta`, `DatabaseMeta`… (el `position` es el índice en su lista). `nextAlso=true` encadena la acción con la siguiente (útil para "agregar step + hop" como una sola operación de Ctrl+Z).

### 5.6 Refrescar la GUI

```java
public void Spoon.refreshGraph();      // redibuja SOLO la pestaña activa (TransGraph/JobGraph.redraw) + setShellText()
public void Spoon.refreshTree();       // árbol "View" de la izquierda
public void Spoon.setShellText();      // título de la ventana (incluye "*")
public void AbstractGraph.redraw();    // TransGraph/JobGraph extienden AbstractGraph; para pestañas no activas:
TransGraph g = spoon.delegates.trans.findTransGraphOfTransformation(transMeta); g.redraw();
public void SpoonTabsDelegate.renameTabs();   // texto de pestañas (tras cambiar nombre/filename)
public void AbstractMeta.setChanged();         // notifica a observers → Spoon marca la pestaña como cambiada
```

Orden típico al final de cada operación de escritura: `meta.setChanged(); spoon.refreshTree(); graph.redraw(); spoon.setShellText();`

---

## 6. Leer

```java
// pasos
for (StepMeta s : transMeta.getSteps()) {
  s.getName(); s.getStepID(); s.getLocation().x/.y; s.isDrawn(); s.getCopies(); s.isDistributes();
  s.isMissing(); s.getXML();         // <step> completo, el mismo formato que en el .ktr
}
// hops
for (TransHopMeta h : transMeta.getTransHops()) { h.getFromStep().getName(); h.getToStep().getName(); h.isEnabled(); h.isErrorHop(); }
// notas (AbstractMeta)
public List<NotePadMeta> getNotes(); public void addNote(NotePadMeta); public void removeNote(int); public int indexOfNote(NotePadMeta);
// NotePadMeta(String note, int x, int y, int w, int h);  getNote(), getLocation(), getWidth(), getHeight(), getXML()
// XML completo
public String TransMeta.getXML() throws KettleException;
public String TransMeta.getXML(boolean includeSteps, boolean includeDatabase, boolean includeSlaves, boolean includeClusters, boolean includePartitions) throws KettleException;

// campos
public RowMetaInterface TransMeta.getStepFields(String stepname) throws KettleStepException;        // SALIDA del step
public RowMetaInterface TransMeta.getStepFields(StepMeta) throws KettleStepException;
public RowMetaInterface TransMeta.getStepFields(StepMeta, ProgressMonitorListener) throws KettleStepException;
public RowMetaInterface TransMeta.getStepFields(StepMeta, StepMeta targetStep, ProgressMonitorListener) throws KettleStepException;
public RowMetaInterface TransMeta.getPrevStepFields(String stepname) throws KettleStepException;    // ENTRADA del step
public RowMetaInterface TransMeta.getPrevStepFields(StepMeta) throws KettleStepException;

// core.row.RowMetaInterface / ValueMetaInterface
List<ValueMetaInterface> getValueMetaList(); int size(); String[] getFieldNames(); ValueMetaInterface getValueMeta(int);
String getString(Object[] row, int index) throws KettleValueException; Object[] cloneRow(Object[]) throws KettleValueException;
// ValueMetaInterface
String getName(); int getType(); String getTypeDesc(); int getLength(); int getPrecision();
String getOrigin(); String getConversionMask(); String getComments(); String getString(Object) throws KettleValueException;
```

`getStepFields` usa caché (`clearCaches()` la limpia; `addStep` ya la limpia) y puede consultar bases de datos (Table input, etc.): hacerlo sobre un `realClone(false)` en un worker, no en la UI.

---

## 7. Validar

```java
public void TransMeta.checkSteps(List<CheckResultInterface> remarks, boolean onlySelected, ProgressMonitorListener monitor);
public void TransMeta.checkSteps(List<CheckResultInterface> remarks, boolean onlySelected, ProgressMonitorListener monitor,
                                 VariableSpace space, Repository repository, IMetaStore metaStore);
public void JobMeta.checkJobEntries(List<CheckResultInterface> remarks, boolean onlySelected, ProgressMonitorListener monitor,
                                    VariableSpace space, Repository repository, IMetaStore metaStore);

// core.CheckResultInterface
int TYPE_RESULT_NONE, TYPE_RESULT_OK, TYPE_RESULT_COMMENT, TYPE_RESULT_WARNING, TYPE_RESULT_ERROR;
int getType(); String getTypeDesc(); String getText(); String getErrorCode(); CheckResultSourceInterface getSourceInfo(); // getSourceInfo().getName() = step
```

Uso: `List<CheckResultInterface> r = new ArrayList<>(); copia.checkSteps(r, false, null, copia, spoon.getRepository(), spoon.getMetaStore());` (`monitor` puede ser null). En la GUI el equivalente es `TransGraph.checkTrans()` (abre su diálogo: no usar).

---

## 8. Ejecutar

### 8.a Visible en la GUI (como apretar Run)

```java
public void Spoon.executeTransformation(TransMeta, boolean local, boolean remote, boolean cluster,
                                        boolean preview, boolean debug, Date replayDate, boolean safe, LogLevel logLevel);
public void SpoonTransformationDelegate.executeTransformation(...same...) throws KettleException;
public TransExecutionConfiguration Spoon.getTransExecutionConfiguration();
public TransExecutionConfiguration Spoon.getTransPreviewExecutionConfiguration();
public synchronized void TransGraph.start(TransExecutionConfiguration) throws KettleException;
public synchronized void TransGraph.debug(TransExecutionConfiguration, TransDebugMeta);
public Trans TransGraph.getTrans();  public boolean TransGraph.isRunning();  public void TransGraph.stop();
public boolean AbstractMeta.isShowDialog(); public void AbstractMeta.setShowDialog(boolean);
```

Lo que hace el delegate (bytecode), con `preview=false, debug=false`:
1. Toma `spoon.getTransExecutionConfiguration()`, setea local/remote/cluster, repo, metastore, variables, safe mode, log level.
2. **Si `transMeta.isShowDialog()` → abre `TransExecutionConfigurationDialog` (modal).** Con `setShowDialog(false)` se lo salta.
3. Si local → `spoon.getActiveTransGraph().start(config)` → usa **la pestaña activa**: activar antes la pestaña del `TransMeta`.
4. `TransGraph.start` exige `getFilename()!=null` (o repo) **y `!hasChanged()`**; si no, `showSaveFileMessage()` → modal "Guardá la transformación antes de ejecutar". Por lo tanto: guardar (§4) antes de ejecutar.
5. Si ya está corriendo → modal "No iniciar dos veces": chequear `graph.isRunning()`.

Receta (en UI): `activar pestaña; if (meta.hasChanged()) spoon.saveToFile(meta); boolean prev = meta.isShowDialog(); meta.setShowDialog(false); try { spoon.executeTransformation(meta, true, false, false, false, false, null, false, LogLevel.BASIC); } finally { meta.setShowDialog(prev); }`
Luego, desde un worker: `Trans t = UiThread.call(() -> graph.getTrans())`, `t.addTransListener(...)` o sondear `t.isFinished()`; errores `t.getErrors()`, `t.getResult()`; log `KettleLogStore.getAppender().getBuffer(t.getLogChannelId(), false)`. El usuario ve las métricas por step y el log en el panel inferior.

Preview "de la GUI" (`preview=true` o `TransGraph.debug(...)` con un `TransDebugMeta` propio): **no conviene**. Aunque `TransGraph.debug` no exige guardar y acepta `TransDebugMeta.addBreakPointListers(BreakPointListener)` para capturar filas, el listener interno de `TransGraph` siempre hace `showPreview(...)` → `PreviewRowsDialog.open()` **modal** con botones Cerrar/Más filas/Detener. Y `executeTransformation(..., preview=true, ...)` además abre antes `TransDebugDialog` (modal).

### 8.b En segundo plano dentro de la JVM (recomendado para preview de filas)

```java
// trans.Trans
public Trans(TransMeta); public Trans(TransMeta, LoggingObjectInterface parent);
public void prepareExecution(String[] arguments) throws KettleException;
public void startThreads() throws KettleException;
public void execute(String[] arguments) throws KettleException;     // = prepareExecution + startThreads
public void waitUntilFinished();
public boolean isFinished(); public boolean isFinishedOrStopped(); public boolean isRunning(); public boolean isStopped();
public void stopAll(); public void killAll(); public void cleanup();
public int getErrors(); public Result getResult();
public StepInterface getStepInterface(String stepname, int copy);
public List<StepInterface> findStepInterfaces(String stepname);   // todas las copias
public List<StepMetaDataCombi> getSteps();
public String getLogChannelId(); public LogChannelInterface getLogChannel();
public void setLogLevel(LogLevel); public void setPreview(boolean); public void setSafeModeEnabled(boolean);
public void setRepository(Repository); public void setMetaStore(IMetaStore);
public void setParameterValue(String, String) throws UnknownParamException; public void activateParameters();
public void addTransListener(TransListener);   // transStarted / transActive / transFinished(Trans)

// trans.step
StepInterface: void addRowListener(RowListener); String getStepname(); int getCopy(); long getErrors();
               long getLinesRead(); long getLinesWritten(); long getLinesInput(); long getLinesOutput(); long getLinesRejected();
RowListener:  void rowReadEvent(RowMetaInterface, Object[]); void rowWrittenEvent(RowMetaInterface, Object[]);
              void errorRowWrittenEvent(RowMetaInterface, Object[]);     (todas throws KettleStepException)
RowAdapter implements RowListener (métodos vacíos)

// core.logging
public static LoggingBuffer KettleLogStore.getAppender();
public StringBuffer LoggingBuffer.getBuffer(String channelId, boolean includeGeneral);
public static void KettleLogStore.discardLines(String channelId, boolean includeGeneralMessages);
public static List<KettleLoggingEvent> KettleLogStore.getLogBufferFromTo(String parentLogChannelId, boolean includeGeneral, int from, int to);
```

Receta segura:

```java
// 1) UI: snapshot (no ejecutar el TransMeta vivo: el usuario/IA lo puede estar editando)
TransMeta copy = UiThread.call(() -> {
  TransMeta tm = spoon.getActiveTransformation();
  TransMeta c = (TransMeta) tm.realClone(false);
  c.setRepository(spoon.getRepository()); c.setMetaStore(spoon.getMetaStore());
  return c;
}, 5000);

// 2) worker (ExecutorService propio, NUNCA el hilo UI ni el hilo HTTP si se quiere timeout)
Trans trans = new Trans(copy);
trans.setLogLevel(LogLevel.BASIC);
trans.setPreview(true);                      // los steps de salida que lo respetan no escriben
trans.prepareExecution(null);                // lanza KettleException si no inicializa (errores de config)
final List<Object[]> rows = Collections.synchronizedList(new ArrayList<>());
final AtomicReference<RowMetaInterface> meta = new AtomicReference<>();
for (StepInterface si : trans.findStepInterfaces(stepName)) {
  si.addRowListener(new RowAdapter() {
    @Override public void rowWrittenEvent(RowMetaInterface rm, Object[] row) throws KettleStepException {
      meta.compareAndSet(null, rm.clone());
      if (rows.size() < maxRows) try { rows.add(rm.cloneRow(row)); } catch (KettleValueException e) { throw new KettleStepException(e); }
    }
  });
}
trans.startThreads();
long deadline = System.currentTimeMillis() + timeoutMs;
while (!trans.isFinished()) {
  if (rows.size() >= maxRows || System.currentTimeMillis() > deadline) { trans.stopAll(); break; }
  Thread.sleep(50);
}
trans.waitUntilFinished();
int errors = trans.getErrors();
String log = KettleLogStore.getAppender().getBuffer(trans.getLogChannelId(), false).toString();
KettleLogStore.discardLines(trans.getLogChannelId(), false);
// formatear con meta.get().getValueMeta(i).getString(row[i]) (respeta máscaras)
```

* `cloneRow` es necesario: los steps reutilizan los arrays.
* Parar desde el hilo controlador, no dentro del `RowListener`.
* `setPreview(true)` no impide efectos de todos los steps (p.ej. Execute SQL, Table output con algunos plugins): advertir en la tool o no permitir preview si hay steps de salida, o reemplazarlos por Dummy en la copia.
* Para previsualizar un solo step sin hops existe `TransPreviewFactory.generatePreviewTransformation(VariableSpace, StepMetaInterface, String stepname)` (arma una trans con ese step + Dummy).

**Cuál conviene**: 8.b para la tool "preview/ejecutar y devolver filas/errores" (sin modales, con timeout, no toca el documento); 8.a para "ejecutá para que yo vea" (requiere guardar, no devuelve filas).

---

## 9. Jobs

```java
// job.JobMeta (extends AbstractMeta)
public JobMeta();
public static JobEntryCopy createStartEntry(); public static JobEntryCopy createDummyEntry();
public JobEntryCopy getStart(); public JobEntryCopy findStart();
public void addJobEntry(JobEntryCopy); public void addJobEntry(int, JobEntryCopy);
public void removeJobEntry(int); public int indexOfJobEntry(JobEntryCopy); public void setJobEntry(int, JobEntryCopy);
public JobEntryCopy getJobEntry(int); public int nrJobEntries(); public List<JobEntryCopy> getJobCopies();
public JobEntryCopy findJobEntry(String name); public JobEntryCopy findJobEntry(String name, int nr, boolean searchHiddenToo);
public void renameJobEntryIfNameCollides(JobEntryCopy);
public void addJobHop(JobHopMeta); public void removeJobHop(int); public void removeJobHop(JobHopMeta);
public int indexOfJobHop(JobHopMeta); public JobHopMeta getJobHop(int); public int nrJobHops(); public List<JobHopMeta> getJobhops();
public JobHopMeta findJobHop(JobEntryCopy from, JobEntryCopy to); public JobHopMeta findJobHop(JobEntryCopy, JobEntryCopy, boolean includeDisabled);
public boolean hasLoop(JobEntryCopy); public boolean isEntryUsedInHops(JobEntryCopy);
public String getXML();
public void checkJobEntries(List<CheckResultInterface>, boolean, ProgressMonitorListener, VariableSpace, Repository, IMetaStore);

// job.entry.JobEntryCopy
public JobEntryCopy(); public JobEntryCopy(JobEntryInterface);
public JobEntryCopy(Node entrynode, List<DatabaseMeta> databases, List<SlaveServer> slaveServers, Repository rep, IMetaStore metaStore) throws KettleXMLException;
public String getXML();                                 // <entry>…</entry>
public JobEntryInterface getEntry(); public void setEntry(JobEntryInterface);
public String getName(); public void setName(String);
public void setLocation(int, int); public Point getLocation();
public void setDrawn(boolean); public boolean isDrawn(); public void setSelected(boolean);
public int getNr(); public void setNr(int); public void setLaunchingInParallel(boolean);
public boolean isStart(); public boolean isDummy(); public boolean isMissing();
public void setParentJobMeta(JobMeta);

// job.JobHopMeta (extends BaseHopMeta<JobEntryCopy>)
public JobHopMeta(JobEntryCopy from, JobEntryCopy to);
public JobHopMeta(Node hopnode, JobMeta job) throws KettleXMLException;
public void setUnconditional(); public void setUnconditional(boolean); public boolean isUnconditional();
public void setConditional();
public void setEvaluation(boolean); public boolean getEvaluation();   // true = "si OK" (verde), false = "si falla" (rojo)
public void setEnabled(boolean); public JobEntryCopy getFromEntry(); public JobEntryCopy getToEntry();

// Spoon / delegates.jobs
public void Spoon.executeJob(JobMeta, boolean local, boolean remote, Date replayDate, boolean safe, String startCopyName, int startCopyNr);
// SpoonJobDelegate.executeJob: si jobMeta.isShowDialog() → JobExecutionConfigurationDialog (modal); luego getActiveJobGraph().startJob(config)
public synchronized void JobGraph.startJob(JobExecutionConfiguration) throws KettleException;
public Job JobGraph.getJob(); public JobMeta JobGraph.getJobMeta(); public synchronized void JobGraph.stopJob();
public void SpoonJobDelegate.deleteJobEntryCopies(JobMeta, JobEntryCopy);   // borrar con undo/refresh
```

Crear una entrada desde XML: igual que steps, con el elemento **`<entry>`**: `<entry><name>…</name><type>PLUGIN_ID</type>… config …<parallel>N</parallel><draw>Y</draw><nr>0</nr><xloc>…</xloc><yloc>…</yloc></entry>` → `new JobEntryCopy(node, jobMeta.getDatabases(), jobMeta.getSlaveServers(), spoon.getRepository(), spoon.getMetaStore())` → `jobMeta.addJobEntry(copy)`; `renameJobEntryIfNameCollides(copy)`; `copy.setParentJobMeta(jobMeta)`. Validar el id antes con `findPluginWithId(JobEntryPluginType.class, id)` (si no, queda `isMissing()`). Por defecto: `JobEntryInterface jei = PluginRegistry.getInstance().loadClass(p, JobEntryInterface.class)` + `getXML()`.

Hops: `JobHopMeta h = new JobHopMeta(from, to); h.setUnconditional(false); h.setEvaluation(true); jobMeta.addJobHop(h);`. Desde START el hop debe ser incondicional. Undo/refresh igual que en trans (`spoon.addUndoNew(jobMeta, …)`, `spoon.delegates.jobs.findJobGraphOfJob(jobMeta).redraw()`).

Ejecutar en segundo plano: `job.Job(Repository, JobMeta)` + `start()` + `waitUntilFinished()` + `getResult()` (mismo esquema que 8.b; no hay filas que capturar).

---

## 10. Conexiones de base de datos

```java
// core.database.DatabaseMeta
public DatabaseMeta(String xml) throws KettleXMLException;   // <connection>…</connection>
public DatabaseMeta(Node con) throws KettleXMLException;
public DatabaseMeta(String name, String type, String access, String host, String db, String port, String user, String pass);
public String getXML(); public String getName(); public String getPluginId(); public String getAccessTypeDesc();
public String getHostname(); public String getDatabaseName();
public String testConnection();                       // bloquea (red): en worker
public DatabaseTestResults testConnectionSuccess();

// AbstractMeta
public void addOrReplaceDatabase(DatabaseMeta);  public void addDatabase(DatabaseMeta);
public List<DatabaseMeta> getDatabases(); public String[] getDatabaseNames();
public DatabaseMeta findDatabase(String name); public int indexOfDatabase(DatabaseMeta); public void removeDatabase(int);
public List<DatabaseMeta> Spoon.getActiveDatabases();
```

Agregar: `transMeta.addOrReplaceDatabase(new DatabaseMeta(xml)); transMeta.setChanged(); spoon.refreshTree();`. Las contraseñas en el XML van como `Encrypted …` (formato de `Encr`); no devolver `getXML()` de conexiones a la IA sin enmascarar `<password>`. Compartir (`setShared(true)` + `saveSharedObjects()`) escribe `~/.kettle/shared.xml`: evitarlo salvo pedido explícito.

---

## 11. JSON en el classpath

* `data-integration/lib`: `jackson-core-2.9.10`, `jackson-databind-2.9.10.2`, `jackson-annotations-2.9.10`, `json-simple-1.1`, `flexjson-2.1`, `json-9.4.0.0-343`, `jsonpath-1.0`.
* El classloader del plugin (`KettleURLClassLoader`, child-first) tiene como padre al classloader de `PluginRegistry`, que es el del launcher con `lib/*.jar`: **Jackson y json-simple son visibles**. Verificado en el smoke test: `/ping` serializa con `com.fasterxml.jackson.databind.ObjectMapper` y reporta `PackageVersion.VERSION = 2.9.10-2`.
* Usar Jackson (`ObjectMapper`, `JsonNode`, `ObjectNode`) para JSON-RPC. Compilar contra el jar de `lib/`, no empaquetarlo.

---

## 12. Notas para el servidor MCP

* Transporte Streamable HTTP: un único endpoint (`POST /mcp` con `Accept: application/json, text/event-stream`; respuesta `application/json` alcanza si no hay streaming; `GET /mcp` puede responder 405). Manejar `Mcp-Session-Id` si se quiere sesión.
* Seguridad: escuchar solo en `InetAddress.getLoopbackAddress()` y validar `Origin` (el esqueleto ya rechaza orígenes que no sean localhost: protección contra DNS rebinding, como pide la spec).
* Cada tool de escritura = una tarea en UI (`UiThread.call`) que hace mutación + undo + refresh, y devuelve un resumen chico (nombre, id, posición) para que la IA siga.
* Errores de Kettle (`KettleXMLException`, `KettleStepException`…) → `isError: true` en el resultado de la tool con el mensaje, no error JSON-RPC.
* Diálogos a evitar (todos modales): `saveFileAs`, `openFile` con ruta inválida, `save` con nombre no ASCII, `executeTransformation` con `isShowDialog()==true` o sin guardar, preview/debug de la GUI, `editStep`, `checkTrans`, `tabClose(…, false)`.
