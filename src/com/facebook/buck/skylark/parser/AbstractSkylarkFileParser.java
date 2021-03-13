/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.model.label.LabelSyntaxException;
import com.facebook.buck.core.model.label.PackageIdentifier;
import com.facebook.buck.core.model.label.PathFragment;
import com.facebook.buck.core.model.label.RepositoryName;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.compatible.BuckStarlarkModule;
import com.facebook.buck.core.starlark.compatible.BuckStarlarkPrintHandler;
import com.facebook.buck.core.starlark.compatible.StarlarkExportable;
import com.facebook.buck.core.starlark.eventhandler.Event;
import com.facebook.buck.core.starlark.eventhandler.EventHandler;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.parser.api.FileManifest;
import com.facebook.buck.parser.api.FileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.implicit.ImplicitInclude;
import com.facebook.buck.parser.implicit.PackageImplicitIncludesFinder;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.skylark.function.LoadSymbolsContext;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.facebook.buck.skylark.parser.context.ReadConfigContext;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;
import org.immutables.value.Value;

/** Abstract parser for files written using Skylark syntax. */
abstract class AbstractSkylarkFileParser<T extends FileManifest> implements FileParser<T> {

  protected final ProjectBuildFileParserOptions options;
  protected final EventHandler eventHandler;
  protected final BuckGlobals buckGlobals;

  private final Cache<AbsPath, Program> astCache;
  private final Cache<AbsPath, ExtensionData> extensionDataCache;
  private final ConcurrentHashMap<Label, IncludesData> includesDataCache;
  private final PackageImplicitIncludesFinder packageImplicitIncludeFinder;

  // TODO(nga):
  //   * this `readConfig` context is used for `read_config` in bzl files.
  //       And read configs (top-level) are not tracked: recorded, but not fetched.
  //   * this class is supposed to be thread-safe, but this object is not.
  private final ReadConfigContext readConfigContext;

  AbstractSkylarkFileParser(
      ProjectBuildFileParserOptions options, BuckGlobals buckGlobals, EventHandler eventHandler) {
    this.options = options;
    this.eventHandler = eventHandler;
    this.buckGlobals = buckGlobals;

    this.astCache = CacheBuilder.newBuilder().build();
    this.extensionDataCache = CacheBuilder.newBuilder().build();

    this.includesDataCache = new ConcurrentHashMap<>();

    this.packageImplicitIncludeFinder =
        PackageImplicitIncludesFinder.fromConfiguration(options.getPackageImplicitIncludes());

    this.readConfigContext = new ReadConfigContext(options.getRawConfig());
  }

  abstract BuckOrPackage getBuckOrPackage();

  abstract ParseResult getParseResult(
      Path parseFile,
      ParseContext context,
      ReadConfigContext readConfigContext,
      Globber globber,
      ImmutableList<String> loadedPaths);

  abstract Globber getGlobber(AbsPath parseFile);

  private ImplicitlyLoadedExtension loadImplicitExtension(
      ForwardRelativePath basePath, Label containingLabel, LoadStack loadStack)
      throws IOException, InterruptedException {
    Optional<ImplicitInclude> implicitInclude =
        packageImplicitIncludeFinder.findIncludeForBuildFile(basePath);
    if (!implicitInclude.isPresent()) {
      return ImplicitlyLoadedExtension.empty();
    }

    // Only export requested symbols, and ensure that all requsted symbols are present.
    ExtensionData data =
        loadExtension(
            ImmutableLoadImport.ofImpl(
                containingLabel, implicitInclude.get().getLoadPath(), Location.BUILTIN),
            loadStack);
    Module symbols = data.getExtension();
    ImmutableMap<String, String> expectedSymbols = implicitInclude.get().getSymbols();
    Builder<String, Object> loaded = ImmutableMap.builderWithExpectedSize(expectedSymbols.size());
    for (Entry<String, String> kvp : expectedSymbols.entrySet()) {
      Object symbol = symbols.getGlobals().get(kvp.getValue());
      if (symbol == null) {
        throw BuildFileParseException.createForUnknownParseError(
            String.format(
                "Could not find symbol '%s' in implicitly loaded extension '%s'",
                kvp.getValue(), implicitInclude.get().getLoadPath()));
      }
      loaded.put(kvp.getKey(), symbol);
    }
    return ImmutableImplicitlyLoadedExtension.ofImpl(data, loaded.build());
  }

  /** @return The parsed result defined in {@code parseFile}. */
  protected ParseResult parse(AbsPath parseFile)
      throws IOException, BuildFileParseException, InterruptedException {

    ForwardRelativePath basePath = getBasePath(parseFile);
    Label containingLabel = createContainingLabel(basePath);
    ImplicitlyLoadedExtension implicitLoad =
        loadImplicitExtension(
            basePath, containingLabel, LoadStack.top(Location.fromFile(parseFile.toString())));

    Program buildFileAst =
        parseSkylarkFile(
            parseFile,
            LoadStack.top(Location.fromFile(parseFile.toString())),
            getBuckOrPackage().fileKind,
            containingLabel);
    Globber globber = getGlobber(parseFile);
    PackageContext packageContext =
        createPackageContext(basePath, globber, implicitLoad.getLoadedSymbols());
    ParseContext parseContext = new ParseContext(packageContext);
    ReadConfigContext readConfigContext = new ReadConfigContext(packageContext.getRawConfig());
    try (Mutability mutability = Mutability.create("parsing " + parseFile)) {
      EnvironmentData envData =
          createBuildFileEvaluationEnvironment(
              parseFile,
              containingLabel,
              buildFileAst,
              mutability,
              parseContext,
              readConfigContext,
              implicitLoad.getExtensionData());

      Module module = makeModule(getBuckOrPackage().fileKind, containingLabel);
      exec(buildFileAst, module, envData.getEnvironment(), "file %s", parseFile);

      ImmutableList.Builder<String> loadedPaths =
          ImmutableList.builderWithExpectedSize(envData.getLoadedPaths().size() + 1);
      loadedPaths.add(parseFile.toString());
      loadedPaths.addAll(envData.getLoadedPaths());

      return getParseResult(
          parseFile.getPath(), parseContext, readConfigContext, globber, loadedPaths.build());
    }
  }

  private void exec(
      Program program, Module module, StarlarkThread thread, String what, Object... whatArgs)
      throws InterruptedException {
    try {
      Starlark.execFileProgram(program, module, thread);
    } catch (EvalException e) {
      String whatFormatted = String.format(what, whatArgs);
      throw BuildFileParseException.createForUnknownParseError(
          "Cannot evaluate " + whatFormatted + "\n" + e.getMessageWithStack());
    } catch (InterruptedException | BuildFileParseException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof Starlark.UncheckedEvalException
          && e.getCause() instanceof BuildFileParseException) {
        // thrown by post-assign hook
        throw (BuildFileParseException) e.getCause();
      }
      throw new BuckUncheckedExecutionException(e, "When evaluating " + what, whatArgs);
    }
  }

  /**
   * @return The environment that can be used for evaluating build files. It includes built-in
   *     functions like {@code glob} and native rules like {@code java_library}.
   */
  private EnvironmentData createBuildFileEvaluationEnvironment(
      AbsPath buildFilePath,
      Label containingLabel,
      Program buildFileAst,
      Mutability mutability,
      ParseContext parseContext,
      ReadConfigContext readConfigContext,
      @Nullable ExtensionData implicitLoadExtensionData)
      throws IOException, InterruptedException, BuildFileParseException {
    ImmutableMap<String, ExtensionData> dependencies =
        loadExtensions(containingLabel, getImports(buildFileAst, containingLabel), LoadStack.EMPTY);
    StarlarkThread env = new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);
    env.setPrintHandler(new BuckStarlarkPrintHandler(eventHandler));
    env.setLoader(Maps.transformValues(dependencies, ExtensionData::getExtension)::get);

    parseContext.setup(env);
    readConfigContext.setup(env);

    return ImmutableEnvironmentData.ofImpl(
        env, toLoadedPaths(buildFilePath, dependencies.values(), implicitLoadExtensionData));
  }

  private PackageContext createPackageContext(
      ForwardRelativePath basePath,
      Globber globber,
      ImmutableMap<String, Object> implicitlyLoadedSymbols) {
    return PackageContext.of(
        globber,
        options.getRawConfig(),
        options.getCellName(),
        basePath,
        eventHandler,
        implicitlyLoadedSymbols);
  }

  protected Label createContainingLabel(ForwardRelativePath basePath) {
    return Label.createUnvalidated(
        PackageIdentifier.create(
            RepositoryName.createFromValidStrippedName(options.getCellName()),
            PathFragment.createAlreadyNormalized(basePath.toString())),
        "BUCK");
  }

  /**
   * @param containingPath the path of the build or extension file that has provided dependencies.
   * @param dependencies the list of extension dependencies that {@code containingPath} has.
   * @return transitive closure of all paths loaded during parsing of {@code containingPath}
   *     including {@code containingPath} itself as the first element.
   */
  private ImmutableList<String> toLoadedPaths(
      AbsPath containingPath,
      ImmutableCollection<ExtensionData> dependencies,
      @Nullable ExtensionData implicitLoadExtensionData) {
    // expected size is used to reduce the number of unnecessary resize invocations
    int expectedSize = 1;
    if (implicitLoadExtensionData != null) {
      expectedSize += implicitLoadExtensionData.getLoadTransitiveClosure().size();
    }
    for (ExtensionData dependency : dependencies) {
      expectedSize += dependency.getLoadTransitiveClosure().size();
    }
    ImmutableList.Builder<String> loadedPathsBuilder =
        ImmutableList.builderWithExpectedSize(expectedSize);
    // for loop is used instead of foreach to avoid iterator overhead, since it's a hot spot
    loadedPathsBuilder.add(containingPath.toString());
    for (ExtensionData dependency : dependencies) {
      loadedPathsBuilder.addAll(dependency.getLoadTransitiveClosure());
    }
    if (implicitLoadExtensionData != null) {
      loadedPathsBuilder.addAll(implicitLoadExtensionData.getLoadTransitiveClosure());
    }
    return loadedPathsBuilder.build();
  }

  /**
   * Reads file and returns abstract syntax tree for that file.
   *
   * @param path file path to read the data from.
   * @return abstract syntax tree; does not handle any errors.
   */
  @VisibleForTesting
  protected StarlarkFile readSkylarkAST(AbsPath path) throws IOException {
    ParserInput input = ParserInput.fromUTF8(Files.readAllBytes(path.getPath()), path.toString());
    StarlarkFile file = StarlarkFile.parse(input);
    Event.replayEventsOn(eventHandler, file.errors());
    return file;
  }

  private Module makeModule(FileKind fileKind, Label label) {
    Module module =
        fileKind == FileKind.BZL
            ? buckGlobals.makeBuckLoadContextGlobals()
            : buckGlobals.makeBuckBuildFileContextGlobals();
    BuckStarlarkModule.setClientData(module, label);
    return module;
  }

  private Program parseSkylarkFile(
      AbsPath path, LoadStack loadStack, FileKind fileKind, Label label)
      throws BuildFileParseException, IOException {
    Program result = astCache.getIfPresent(path);
    if (result == null) {
      StarlarkFile starlarkFile;
      try {
        starlarkFile = readSkylarkAST(path);
      } catch (NoSuchFileException e) {
        throw BuildFileParseException.createForUnknownParseError(
            loadStack.toDependencyStack(), "%s cannot be loaded because it does not exist", path);
      }
      if (!starlarkFile.errors().isEmpty()) {
        throw BuildFileParseException.createForUnknownParseError(
            loadStack.toDependencyStack(), "Cannot parse %s", path);
      }

      Event.replayEventsOn(eventHandler, starlarkFile.errors());

      if (!starlarkFile.errors().isEmpty()) {
        throw BuildFileParseException.createForUnknownParseError(
            loadStack.toDependencyStack(), "Cannot parse %s", path);
      }

      if (fileKind != FileKind.BZL) {
        if (!StarlarkBuckFileSyntax.checkBuildSyntax(starlarkFile, eventHandler)) {
          throw BuildFileParseException.createForUnknownParseError(
              loadStack.toDependencyStack(), "Cannot parse %s", path);
        }
      }

      try {
        result = Program.compileFile(starlarkFile, makeModule(fileKind, label));
      } catch (SyntaxError.Exception e) {
        Event.replayEventsOn(eventHandler, starlarkFile.errors());
        throw BuildFileParseException.createForUnknownParseError(
            loadStack.toDependencyStack(), "Cannot parse %s", path);
      }

      if (!starlarkFile.errors().isEmpty()) {
        throw BuildFileParseException.createForUnknownParseError(
            loadStack.toDependencyStack(), "Cannot parse %s", path);
      }

      astCache.put(path, result);
    }
    return result;
  }

  private static ImmutableList<LoadImport> getImports(Program file, Label fileLabel) {
    return IntStream.range(0, file.getLoads().size())
        .mapToObj(
            i -> {
              String load = file.getLoads().get(i);
              Location location = file.getLoadLocation(i);
              return ImmutableLoadImport.ofImpl(fileLabel, load, location);
            })
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Creates an {@code IncludesData} object from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private IncludesData loadIncludeImpl(LoadImport loadImport, LoadStack loadStack)
      throws IOException, BuildFileParseException, InterruptedException {
    Label label = loadImport.getLabel();
    AbsPath filePath = getImportPath(label, loadImport.getImport());

    Program fileAst = parseSkylarkFile(filePath, loadStack, FileKind.BZL, label);
    ImmutableList<IncludesData> dependencies =
        loadIncludes(label, getImports(fileAst, label), loadStack);

    return ImmutableIncludesData.ofImpl(
        filePath, dependencies, toIncludedPaths(filePath.toString(), dependencies, null));
  }

  /**
   * Creates an {@code IncludesData} object from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private IncludesData loadInclude(LoadImport loadImport, LoadStack loadStack)
      throws IOException, BuildFileParseException, InterruptedException {
    IncludesData includesData = includesDataCache.get(loadImport.getLabel());
    if (includesData != null) {
      return includesData;
    }
    includesData = loadIncludeImpl(loadImport, loadStack);
    includesDataCache.put(loadImport.getLabel(), includesData);
    return includesData;
  }

  /** Collects all the included files identified by corresponding Starlark imports. */
  private ImmutableList<IncludesData> loadIncludes(
      Label containingLabel, ImmutableList<LoadImport> skylarkImports, LoadStack loadStack)
      throws BuildFileParseException, IOException, InterruptedException {
    Set<String> processed = new HashSet<>(skylarkImports.size());
    ImmutableList.Builder<IncludesData> includes =
        ImmutableList.builderWithExpectedSize(skylarkImports.size());
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < skylarkImports.size(); ++i) {
      LoadImport skylarkImport = skylarkImports.get(i);

      Preconditions.checkState(containingLabel.equals(skylarkImport.getContainingLabel()));

      // sometimes users include the same extension multiple times...
      if (!processed.add(skylarkImport.getImport())) continue;
      try {
        includes.add(
            loadInclude(skylarkImport, loadStack.child(skylarkImport.getImportLocation())));
      } catch (UncheckedExecutionException e) {
        propagateRootCause(e);
      }
    }
    return includes.build();
  }

  private ImmutableSet<String> toIncludedPaths(
      String containingPath,
      ImmutableList<IncludesData> dependencies,
      @Nullable ExtensionData implicitLoadExtensionData) {
    ImmutableSet.Builder<String> includedPathsBuilder = ImmutableSet.builder();
    includedPathsBuilder.add(containingPath);
    // for loop is used instead of foreach to avoid iterator overhead, since it's a hot spot
    for (int i = 0; i < dependencies.size(); ++i) {
      includedPathsBuilder.addAll(dependencies.get(i).getLoadTransitiveClosure());
    }
    if (implicitLoadExtensionData != null) {
      includedPathsBuilder.addAll(implicitLoadExtensionData.getLoadTransitiveClosure());
    }
    return includedPathsBuilder.build();
  }

  /** Loads all extensions identified by corresponding imports. */
  protected ImmutableMap<String, ExtensionData> loadExtensions(
      Label containingLabel, ImmutableList<LoadImport> skylarkImports, LoadStack loadStack)
      throws BuildFileParseException, IOException, InterruptedException {
    Set<String> processed = new HashSet<>(skylarkImports.size());
    ImmutableMap.Builder<String, ExtensionData> extensions =
        ImmutableMap.builderWithExpectedSize(skylarkImports.size());
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < skylarkImports.size(); ++i) {
      LoadImport skylarkImport = skylarkImports.get(i);

      Preconditions.checkState(containingLabel.equals(skylarkImport.getContainingLabel()));

      // sometimes users include the same extension multiple times...
      if (!processed.add(skylarkImport.getImport())) continue;
      try {
        extensions.put(
            skylarkImport.getImport(),
            loadExtension(skylarkImport, loadStack.child(skylarkImport.getImportLocation())));
      } catch (UncheckedExecutionException e) {
        propagateRootCause(e);
      }
    }
    return extensions.build();
  }

  /**
   * Propagates underlying parse exception from {@link UncheckedExecutionException}.
   *
   * <p>This is an unfortunate consequence of having to use {@link
   * LoadingCache#getUnchecked(Object)} in when using stream transformations :(
   *
   * <p>TODO(ttsugrii): the logic of extracting root causes to make them user-friendly should be
   * happening somewhere in {@link com.facebook.buck.cli.MainRunner}, since this behavior is not
   * unique to parsing.
   */
  private void propagateRootCause(UncheckedExecutionException e)
      throws IOException, InterruptedException {
    Throwable rootCause = Throwables.getRootCause(e);
    if (rootCause instanceof BuildFileParseException) {
      throw (BuildFileParseException) rootCause;
    }
    if (rootCause instanceof IOException) {
      throw (IOException) rootCause;
    }
    if (rootCause instanceof InterruptedException) {
      throw (InterruptedException) rootCause;
    }
    throw e;
  }

  /**
   * A struct like class to help loadExtension implementation to represent the state needed for load
   * of a single extension/file.
   */
  @VisibleForTesting
  class ExtensionLoadState {
    // Extension key being loaded.
    private final LoadImport load;
    // Path for the extension.
    private final AbsPath path;
    // Load path
    private final LoadStack loadStack;
    // List of dependencies this extension uses.
    private final Set<LoadImport> dependencies;
    // This extension AST.
    private @Nullable Program ast;

    private ExtensionLoadState(LoadImport load, AbsPath extensionPath, LoadStack loadStack) {
      this.load = load;
      this.path = extensionPath;
      this.loadStack = loadStack;
      this.dependencies = new HashSet<LoadImport>();
      this.ast = null;
    }

    public AbsPath getPath() {
      return path;
    }

    // Methods to get/set/test AST for this extension.
    public boolean haveAST() {
      return ast != null;
    }

    public void setAST(Program ast) {
      Preconditions.checkArgument(!haveAST(), "AST can be set only once");
      this.ast = ast;
    }

    public Program getAST() {
      Preconditions.checkNotNull(ast);
      return ast;
    }

    // Adds a single dependency key for this extension.
    public void addDependency(LoadImport dependency) {
      Preconditions.checkArgument(dependency.getContainingLabel().equals(load.getLabel()));
      dependencies.add(dependency);
    }

    // Returns the list of dependencies for this extension.
    public Set<LoadImport> getDependencies() {
      return dependencies;
    }

    // Returns the label of the file including this extension.
    public Label getParentLabel() {
      return load.getContainingLabel();
    }

    // Returns this extensions label.
    public Label getLabel() {
      return load.getLabel();
    }

    // Returns starlark import string for this extension load
    public String getSkylarkImport() {
      return load.getImport();
    }
  }

  /*
   * Given the list of load imports, returns the list of extension data corresponding to those loads.
   * Requires all of the extensions are available in the extension data  cache.
   *
   * @param label {@link Label} identifying extension with dependencies
   * @param dependencies list of load import dependencies
   * @returns list of ExtensionData
   */
  private ImmutableMap<String, ExtensionData> getDependenciesExtensionData(
      Label label, Set<LoadImport> dependencies) throws BuildFileParseException {
    HashMap<String, ExtensionData> depBuilder = new HashMap<>();

    for (LoadImport dependency : dependencies) {
      ExtensionData extension =
          lookupExtensionForImport(getImportPath(dependency.getLabel(), dependency.getImport()));

      if (extension == null) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate extension file %s; missing dependency is %s",
            label, dependency.getLabel());
      }

      depBuilder.putIfAbsent(dependency.getImport(), extension);
    }

    return ImmutableMap.copyOf(depBuilder);
  }

  /**
   * Retrieves extension data from the cache, and returns a copy suitable for the specified skylark
   * import string.
   *
   * @param path a path for the extension to lookup
   * @return {@link ExtensionData} suitable for the requested extension and importString, or null if
   *     no such extension found.
   */
  private @Nullable ExtensionData lookupExtensionForImport(AbsPath path) {
    return extensionDataCache.getIfPresent(path);
  }

  /**
   * Loads extensions abstract syntax tree if needed.
   *
   * @param load {@link ExtensionLoadState} representing the extension being loaded.
   * @returns true if AST was loaded, false otherwise.
   */
  private boolean maybeLoadAST(ExtensionLoadState load, LoadStack loadStack) throws IOException {
    if (load.haveAST()) {
      return false;
    }
    load.setAST(parseSkylarkFile(load.getPath(), loadStack, FileKind.BZL, load.getLabel()));
    return true;
  }

  /**
   * Updates extension load state with the list of its dependencies, and schedules any unsatisfied
   * dependencies to be loaded by adding those dependencies to the work queue.
   *
   * @param load {@link ExtensionLoadState} representing extension currently loaded
   * @param queue a work queue of extensions that still need to be loaded.
   * @return true if this extension has any unsatisfied dependencies
   */
  private boolean processExtensionDependencies(
      ExtensionLoadState load, ArrayDeque<ExtensionLoadState> queue) {
    // Update this load state with the list of its dependencies.
    // Schedule missing dependencies to be loaded.
    boolean haveUnsatisfiedDeps = false;

    ImmutableList<LoadImport> imports = getImports(load.getAST(), load.getLabel());

    for (int i = 0; i < imports.size(); ++i) {
      LoadImport dependency = imports.get(i);

      // Record dependency for this load.
      load.addDependency(dependency);
      AbsPath extensionPath = getImportPath(dependency.getLabel(), dependency.getImport());
      if (extensionDataCache.getIfPresent(extensionPath) == null) {
        // Schedule dependency to be loaded if needed.
        haveUnsatisfiedDeps = true;
        queue.push(
            new ExtensionLoadState(
                dependency, extensionPath, load.loadStack.child(dependency.getImportLocation())));
      }
    }
    return haveUnsatisfiedDeps;
  }

  /**
   * Given fully loaded extension represented by {@link ExtensionLoadState}, evaluates extension and
   * returns {@link ExtensionData}
   *
   * @param load {@link ExtensionLoadState} representing loaded extension
   * @returns {@link ExtensionData} for this extions.
   */
  @VisibleForTesting
  protected ExtensionData buildExtensionData(ExtensionLoadState load) throws InterruptedException {
    ImmutableMap<String, ExtensionData> dependencies =
        getDependenciesExtensionData(load.getLabel(), load.getDependencies());
    Module loadedExtension;
    try (Mutability mutability = Mutability.create("importing extension")) {
      //      StarlarkThread.Builder envBuilder =
      //          StarlarkThread.builder(mutability)
      //              .setEventHandler(eventHandler)
      //
      // .setGlobals(buckGlobals.getBuckLoadContextGlobals().withLabel(load.getLabel()));
      //      envBuilder.setImportedExtensions(
      //          Maps.transformValues(dependencies, ExtensionData::getExtension));

      // Create this extension.
      StarlarkThread extensionEnv =
          new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);
      extensionEnv.setPrintHandler(new BuckStarlarkPrintHandler(eventHandler));
      extensionEnv.setLoader(Maps.transformValues(dependencies, ExtensionData::getExtension)::get);

      readConfigContext.setup(extensionEnv);

      LoadSymbolsContext loadSymbolsContext = new LoadSymbolsContext();

      loadSymbolsContext.setup(extensionEnv);

      extensionEnv.setPostAssignHook(
          (n, v) -> {
            try {
              ensureExportedIfExportable(load.getLabel(), n, v);
            } catch (EvalException e) {
              // TODO(nga): what about stack trace
              eventHandler.handle(Event.error(e.getDeprecatedLocation(), e.getMessage()));
              throw new BuildFileParseException(e, e.getMessage());
            }
          });

      Program ast = load.getAST();
      buckGlobals.getKnownUserDefinedRuleTypes().invalidateExtension(load.getLabel());
      Module module = makeModule(FileKind.BZL, load.getLabel());
      exec(
          ast,
          module,
          extensionEnv,
          "extension %s referenced from %s",
          load.getLabel(),
          load.getParentLabel());

      for (Entry<String, Object> entry : loadSymbolsContext.getLoadedSymbols().entrySet()) {
        module.setGlobal(entry.getKey(), entry.getValue());
      }

      extensionEnv.mutability().freeze();

      loadedExtension = module;
    }

    return ImmutableExtensionData.ofImpl(
        loadedExtension,
        load.getPath(),
        dependencies.values(),
        toLoadedPaths(load.getPath(), dependencies.values(), null));
  }

  /**
   * Call {@link StarlarkExportable#export(Label, String)} on any objects that are assigned to
   *
   * <p>This is primarily used to make sure that {@link SkylarkUserDefinedRule} and {@link
   * com.facebook.buck.core.rules.providers.impl.UserDefinedProvider} instances set their name
   * properly upon assignment
   *
   * @param identifier the name of the variable
   * @param lookedUp exported value
   */
  private void ensureExportedIfExportable(Label extensionLabel, String identifier, Object lookedUp)
      throws BuildFileParseException, EvalException {
    if (lookedUp instanceof StarlarkExportable) {
      StarlarkExportable exportable = (StarlarkExportable) lookedUp;
      if (!exportable.isExported()) {
        Preconditions.checkState(extensionLabel != null);
        exportable.export(extensionLabel, identifier);
        if (lookedUp instanceof SkylarkUserDefinedRule) {
          this.buckGlobals
              .getKnownUserDefinedRuleTypes()
              .addRule((SkylarkUserDefinedRule) exportable);
        }
      }
    }
  }

  /**
   * Creates an extension from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private ExtensionData loadExtension(LoadImport loadImport, LoadStack loadStack)
      throws IOException, BuildFileParseException, InterruptedException {

    ExtensionData extension = null;
    ArrayDeque<ExtensionLoadState> work = new ArrayDeque<>();
    AbsPath extensionPath = getImportPath(loadImport.getLabel(), loadImport.getImport());
    work.push(new ExtensionLoadState(loadImport, extensionPath, loadStack));

    while (!work.isEmpty()) {
      ExtensionLoadState load = work.peek();
      extension = lookupExtensionForImport(load.getPath());

      if (extension != null) {
        // It's possible that some lower level dependencies already loaded
        // this work item.  We're done with it, so pop the queue.
        work.pop();
        continue;
      }

      // Load BuildFileAST if needed.
      boolean astLoaded = maybeLoadAST(load, load.loadStack);
      boolean haveUnsatisfiedDeps = astLoaded && processExtensionDependencies(load, work);

      // NB: If we have unsatisfied dependencies, we don't do anything;
      // more importantly we do not pop the work queue in this case.
      // This load is kept on the queue until all of its dependencies are satisfied.

      if (!haveUnsatisfiedDeps) {
        // We are done with this load; build it and cache it.
        work.removeFirst();
        extension = buildExtensionData(load);
        extensionDataCache.put(load.getPath(), extension);
      }
    }

    Preconditions.checkNotNull(extension);
    return extension;
  }

  /**
   * @return The path to a Skylark extension. For example, for {@code load("//pkg:foo.bzl", "foo")}
   *     import it would return {@code /path/to/repo/pkg/foo.bzl} and for {@code
   *     load("@repo//pkg:foo.bzl", "foo")} it would return {@code /repo/pkg/foo.bzl} assuming that
   *     {@code repo} is located at {@code /repo}.
   */
  private AbsPath getImportPath(Label containingLabel, String skylarkImport)
      throws BuildFileParseException {
    if (isRelativeLoad(skylarkImport) && skylarkImport.contains("/")) {
      throw BuildFileParseException.createForUnknownParseError(
          "Relative loads work only for files in the same directory but "
              + skylarkImport
              + " is trying to load a file from a nested directory. "
              + "Please use absolute label instead ([cell]//pkg[/pkg]:target).");
    }
    PathFragment relativeExtensionPath = containingLabel.toPathFragment();
    RepositoryName repository = containingLabel.getPackageIdentifier().getRepository();
    if (repository.isMain()) {
      return options.getProjectRoot().resolve(relativeExtensionPath.toString());
    }
    // Skylark repositories have an "@" prefix, but Buck roots do not, so ignore it
    String repositoryName = repository.getName().substring(1);
    @Nullable AbsPath repositoryPath = options.getCellRoots().get(repositoryName);
    if (repositoryPath == null) {
      throw BuildFileParseException.createForUnknownParseError(
          skylarkImport + " references an unknown repository " + repositoryName);
    }
    return repositoryPath.resolve(relativeExtensionPath.toString());
  }

  private boolean isRelativeLoad(String skylarkImport) {
    return skylarkImport.startsWith(":");
  }

  /**
   * @return The path path of the provided {@code buildFile}. For example, for {@code
   *     /Users/foo/repo/src/bar/BUCK}, where {@code /Users/foo/repo} is the path to the repo, it
   *     would return {@code src/bar}.
   */
  protected ForwardRelativePath getBasePath(AbsPath buildFile) {
    return Optional.ofNullable(options.getProjectRoot().relativize(buildFile).getParent())
        .map(ForwardRelativePath::ofRelPath)
        .orElse(ForwardRelativePath.EMPTY);
  }

  @Override
  public ImmutableSortedSet<String> getIncludedFiles(AbsPath parseFile)
      throws BuildFileParseException, InterruptedException, IOException {

    ForwardRelativePath basePath = getBasePath(parseFile);
    Label containingLabel = createContainingLabel(basePath);
    ImplicitlyLoadedExtension implicitLoad =
        loadImplicitExtension(basePath, containingLabel, LoadStack.EMPTY);
    Program buildFileAst =
        parseSkylarkFile(parseFile, LoadStack.EMPTY, getBuckOrPackage().fileKind, containingLabel);
    ImmutableList<IncludesData> dependencies =
        loadIncludes(containingLabel, getImports(buildFileAst, containingLabel), LoadStack.EMPTY);

    // it might be potentially faster to keep sorted sets for each dependency separately and just
    // merge sorted lists as we aggregate transitive close up
    // But Guava does not seem to have a built-in way of merging sorted lists/sets
    return ImmutableSortedSet.copyOf(
        toIncludedPaths(parseFile.toString(), dependencies, implicitLoad.getExtensionData()));
  }

  @Override
  public void close() throws BuildFileParseException {
    // nothing to do
  }

  /**
   * A value object for information about load function import, since import string does not provide
   * enough context. For instance, the same import string can represent different logical imports
   * depending on which repository it is resolved in.
   */
  @BuckStyleValue
  abstract static class LoadImport {
    /** Returns a label of the file containing this import. */
    abstract Label getContainingLabel();

    /** Returns a Skylark import. */
    abstract String getImport();

    abstract Location getImportLocation();

    /** Returns a label of current import file. */
    @Value.Derived
    Label getLabel() {
      try {
        return getContainingLabel().getRelativeWithRemapping(getImport());
      } catch (LabelSyntaxException e) {
        throw BuildFileParseException.createForUnknownParseError(
            "Incorrect load location in %s: %s", getImportLocation(), e.getMessage());
      }
    }
  }

  /**
   * A value object for information about implicit loads. This allows us to both validate implicit
   * import information, and return some additional information needed to setup build file
   * environments in one swoop.
   */
  @BuckStyleValue
  abstract static class ImplicitlyLoadedExtension {
    abstract @Nullable ExtensionData getExtensionData();

    abstract ImmutableMap<String, Object> getLoadedSymbols();

    @Value.Lazy
    static ImplicitlyLoadedExtension empty() {
      return ImmutableImplicitlyLoadedExtension.ofImpl(null, ImmutableMap.of());
    }
  }
}
