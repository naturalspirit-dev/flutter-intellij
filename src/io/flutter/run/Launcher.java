/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run;

import com.intellij.execution.*;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderImpl;
import com.intellij.execution.filters.UrlFilter;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import com.jetbrains.lang.dart.ide.runner.DartRelativePathsConsoleFilter;
import com.jetbrains.lang.dart.util.DartUrlResolver;
import io.flutter.actions.OpenObservatoryAction;
import io.flutter.actions.OpenSimulatorAction;
import io.flutter.dart.DartPlugin;
import io.flutter.run.daemon.DeviceService;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.run.daemon.FlutterDevice;
import io.flutter.run.daemon.RunMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Launches a flutter app, showing it in the console.
 *
 * <p>Normally creates a debugging session, which is needed for hot reload.
 */
public class Launcher extends CommandLineState {
  private final @NotNull VirtualFile workDir;

  /**
   * The file or directory holding the Flutter app's source code.
   * This determines how the analysis server resolves URI's (for breakpoints, etc).
   *
   * <p>If a file, this should be the file containing the main() method.
   */
  private final @NotNull VirtualFile sourceLocation;

  private final @NotNull RunConfig runConfig;
  private final @NotNull Callback callback;

  public Launcher(@NotNull ExecutionEnvironment env,
                  @NotNull VirtualFile workDir,
                  @NotNull VirtualFile sourceLocation,
                  @NotNull RunConfig runConfig,
                  @NotNull Callback callback) {
    super(env);
    this.workDir = workDir;
    this.sourceLocation = sourceLocation;
    this.runConfig = runConfig;
    this.callback = callback;

    // Set up basic console filters. (Callers may add more.)
    final TextConsoleBuilder builder = getConsoleBuilder();
    if (builder instanceof TextConsoleBuilderImpl) {
      ((TextConsoleBuilderImpl)builder).setUsePredefinedMessageFilter(false);
    }
    builder.addFilter(new DartRelativePathsConsoleFilter(env.getProject(), workDir.getPath()));
    builder.addFilter(new UrlFilter());
  }

  @NotNull
  private RunContentDescriptor launch(@NotNull ExecutionEnvironment env) throws ExecutionException {
    FileDocumentManager.getInstance().saveAllDocuments();

    final Project project = getEnvironment().getProject();
    final FlutterDevice device = DeviceService.getInstance(project).getSelectedDevice();
    final FlutterApp app = callback.createApp(device);

    // Remember the run configuration that started this process.
    app.getProcessHandler().putUserData(KEY, runConfig);

    final ExecutionResult result = setUpConsoleAndActions(app);

    if (device != null && device.emulator() && device.isIOS()) {
      // Bring simulator to front.
      new OpenSimulatorAction(true).actionPerformed(null);
    }

    if (RunMode.fromEnv(getEnvironment()).isReloadEnabled()) {
      return createDebugSession(env, app, result).getRunContentDescriptor();
    } else {
      // Not used yet. See https://github.com/flutter/flutter-intellij/issues/410
      return new RunContentBuilder(result, env).showRunContent(env.getContentToReuse());
    }
  }

  @NotNull
  private XDebugSession createDebugSession(@NotNull final ExecutionEnvironment env,
                                                  final FlutterApp app,
                                                  final ExecutionResult executionResult)
    throws ExecutionException {
    final XDebuggerManager manager = XDebuggerManager.getInstance(env.getProject());
    final XDebugSession session = manager.startSession(env, new XDebugProcessStarter() {
      @Override
      @NotNull
      public XDebugProcess start(@NotNull final XDebugSession session) {

        final DartUrlResolver resolver = DartUrlResolver.getInstance(env.getProject(), sourceLocation);

        String executionContextId = null;
        final DartAnalysisServerService service = DartAnalysisServerService.getInstance();
        if (service.serverReadyForRequest(env.getProject())) {
          executionContextId = service.execution_createContext(sourceLocation.getPath());
        }

        return new FlutterDebugProcess(app, session, executionResult, resolver, executionContextId, workDir);
      }
    });

    if (app.getMode() != RunMode.DEBUG) {
      session.setBreakpointMuted(true);
    }

    return session;
  }

  @NotNull
  private ExecutionResult setUpConsoleAndActions(@NotNull FlutterApp app) throws ExecutionException {
    final ConsoleView console = createConsole(getEnvironment().getExecutor());
    if (console != null) {
      app.setConsole(console);
      console.attachToProcess(app.getProcessHandler());
    }

    // Add observatory actions.
    // These actions are effectively added only to the Run tool window.
    // For Debug see FlutterDebugProcess.registerAdditionalActions()
    final List<AnAction> actions = new ArrayList<>(Arrays.asList(
      super.createActions(console, app.getProcessHandler(), getEnvironment().getExecutor())));
    actions.add(new Separator());
    actions.add(new OpenObservatoryAction(app.getConnector(), () -> !app.getProcessHandler().isProcessTerminated()));

    return new DefaultExecutionResult(console, app.getProcessHandler(), actions.toArray(new AnAction[actions.size()]));
  }

  @Override
  public @NotNull ExecutionResult execute(@NotNull Executor executor, @NotNull ProgramRunner runner) throws ExecutionException {
    throw new ExecutionException("not implemented"); // Not used; launch() does this.
  }

  @Override
  protected @NotNull ProcessHandler startProcess() throws ExecutionException {
    throw new ExecutionException("not implemented"); // Not used; callback does this.
  }

  /**
   * Starts the process and wraps it in a FlutterApp.
   *
   * The callback knows the appropriate command line arguments (bazel versus non-bazel).
   */
  public interface Callback {
    FlutterApp createApp(@Nullable FlutterDevice selection) throws ExecutionException;
  }

  /**
   * A run configuration that works with Launcher.
   */
  public interface RunConfig extends RunProfile {
    Project getProject();

    @NotNull
    Launcher getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) throws ExecutionException;
  }

  /**
   * A runner that automatically invokes {@link #launch}.
   */
  public static abstract class Runner<C extends RunConfig> extends GenericProgramRunner {
    private final Class<C> runConfigClass;

    public Runner(Class<C> runConfigClass) {
      this.runConfigClass = runConfigClass;
    }

    @SuppressWarnings("SimplifiableIfStatement")
    @Override
    public final boolean canRun(final @NotNull String executorId, final @NotNull RunProfile profile) {
      if (!DefaultRunExecutor.EXECUTOR_ID.equals(executorId) && !DefaultDebugExecutor.EXECUTOR_ID.equals(executorId)) {
        return false;
      }
      if (!(profile instanceof RunConfig)) return false;
      final RunConfig config = (RunConfig)profile;
      if (isRunning(config)) return false;
      if (DartPlugin.getDartSdk(config.getProject()) == null) return false;
      return runConfigClass.isInstance(profile) && canRun(runConfigClass.cast(profile));
    }

    /**
     * Subclass hook for additional checks.
     */
    protected boolean canRun(C config) {
      return true;
    }

    @Override
    protected final RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment env)
      throws ExecutionException {
      if (!(state instanceof Launcher)) {
        LOG.error("unexpected RunProfileState: " + state.getClass());
        return null;
      }
      final Launcher launcher = (Launcher)state;
      return launcher.launch(env);
    }

    /**
     * Returns true if any processes are running that were launched from the given RunConfig.
     */
    private static boolean isRunning(RunConfig config) {
      final Project project = config.getProject();

      final List<RunContentDescriptor> runningProcesses =
        ExecutionManager.getInstance(project).getContentManager().getAllDescriptors();

      for (RunContentDescriptor descriptor : runningProcesses) {
        final ProcessHandler process = descriptor.getProcessHandler();
        if (process != null && !process.isProcessTerminated() && process.getUserData(KEY) == config) {
          return true;
        }
      }
      return false;
    }
  }

  private static final Key<RunConfig> KEY = new Key<>("FLUTTER_RUN_CONFIG_KEY");
  private static final Logger LOG = Logger.getInstance(Launcher.class.getName());
}
