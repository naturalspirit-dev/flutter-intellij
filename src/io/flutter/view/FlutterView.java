/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import icons.FlutterIcons;
import io.flutter.FlutterBundle;
import io.flutter.FlutterInitializer;
import io.flutter.run.OpenObservatoryAction;
import io.flutter.run.daemon.FlutterApp;
import org.dartlang.vm.service.VmServiceListener;
import org.dartlang.vm.service.element.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// TODO(devoncarew): Display an fps graph.
// TODO(devoncarew): Have a pref setting for opening when starting a debug session.

@com.intellij.openapi.components.State(
  name = "FlutterView",
  storages = {@Storage("$WORKSPACE_FILE$")}
)
public class FlutterView implements PersistentStateComponent<FlutterView.State>, Disposable {
  public static final String TOOL_WINDOW_ID = "Flutter";

  @NotNull
  private FlutterView.State state = new FlutterView.State();

  @NotNull
  private final Project myProject;

  @Nullable
  FlutterApp app;

  public FlutterView(@NotNull Project project) {
    myProject = project;
  }

  @Override
  public void dispose() {
  }

  @NotNull
  @Override
  public FlutterView.State getState() {
    return this.state;
  }

  @Override
  public void loadState(FlutterView.State state) {
    this.state = state;
  }

  public void initToolWindow(@NotNull ToolWindow toolWindow) {
    final ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();

    final Content toolContent = contentFactory.createContent(null, null, false);
    final SimpleToolWindowPanel toolWindowPanel = new SimpleToolWindowPanel(true, true);
    toolContent.setComponent(toolWindowPanel);
    //Disposer.register(this, toolWindowPanel);
    toolContent.setCloseable(false);

    final JPanel mainContent = new JPanel(new BorderLayout());
    toolWindowPanel.setContent(mainContent);

    final ToolWindowEx toolWindowEx = (ToolWindowEx)toolWindow;
    toolWindowEx.setTitleActions(
      new DebugDrawAction(this),
      new ToggleInspectModeAction(this),
      new TogglePlatformAction(this)
    );

    toolWindowEx.setAdditionalGearActions(new DefaultActionGroup(Arrays.asList(
      new PerformanceOverlayAction(this),
      new ShowPaintBaselinesAction(this),
      new RepaintRainbowAction(this),
      new TimeDilationAction(this),
      new HideSlowBannerAction(this),
      new Separator(),
      new ObservatoryTimelineAction(this),
      new MemoryDashboardAction(this)
    )));

    final ContentManager contentManager = toolWindow.getContentManager();
    contentManager.addContent(toolContent);
    contentManager.setSelectedContent(toolContent);
  }

  /**
   * Called when a debug connection starts.
   */
  public void debugActive(@NotNull FlutterViewMessages.FlutterDebugEvent event) {
    this.app = event.app;

    event.vmService.addVmServiceListener(new VmServiceListener() {
      @Override
      public void connectionOpened() {
      }

      @Override
      public void received(String s, Event event) {
      }

      @Override
      public void connectionClosed() {
        FlutterView.this.app = null;
        updateIcon();
      }
    });

    updateIcon();
  }

  FlutterApp getFlutterApp() {
    return app;
  }

  private void updateIcon() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (myProject.isDisposed()) {
        return;
      }

      final ToolWindow toolWindow = ToolWindowManager.getInstance(myProject).getToolWindow(TOOL_WINDOW_ID);
      if (toolWindow == null) return;

      if (app == null) {
        toolWindow.setIcon(FlutterIcons.Flutter_13);
      }
      else {
        toolWindow.setIcon(ExecutionUtil.getLiveIndicator(FlutterIcons.Flutter_13));
      }
    });
  }

  /**
   * State for the view.
   */
  class State {
  }
}

class DebugDrawAction extends AbstractToggleableAction {
  DebugDrawAction(@NotNull FlutterView view) {
    super(view, FlutterBundle.message("flutter.view.debugPaint.text"), FlutterBundle.message("flutter.view.debugPaint.description"),
          AllIcons.General.TbShown);
  }

  protected void perform(AnActionEvent event) {
    view.getFlutterApp().callBooleanExtension("ext.flutter.debugPaint", isSelected(event));
  }
}

class PerformanceOverlayAction extends AbstractToggleableAction {
  PerformanceOverlayAction(@NotNull FlutterView view) {
    super(view, "Toggle Performance Overlay", "Toggle Performance Overlay", AllIcons.General.LocateHover);
  }

  protected void perform(AnActionEvent event) {
    view.getFlutterApp().callBooleanExtension("ext.flutter.showPerformanceOverlay", isSelected(event));
  }
}

class TogglePlatformAction extends FlutterViewAction {
  TogglePlatformAction(@NotNull FlutterView view) {
    super(view, FlutterBundle.message("flutter.view.togglePlatform.text"), FlutterBundle.message("flutter.view.togglePlatform.description"),
          AllIcons.RunConfigurations.Application);
  }

  @Override
  public void actionPerformed(AnActionEvent event) {
    final FlutterApp app = view.getFlutterApp();
    if (app == null) {
      return;
    }

    app.togglePlatform().thenAccept(isAndroid -> {
      if (isAndroid == null) {
        return;
      }

      app.togglePlatform(!isAndroid).thenAccept(isNowAndroid -> {
        if (app.getConsole() != null && isNowAndroid != null) {
          app.getConsole().print(
            FlutterBundle.message("flutter.view.togglePlatform.output",
                                  isNowAndroid ? "Android" : "iOS"),
            ConsoleViewContentType.SYSTEM_OUTPUT);
        }
      });
    });
  }
}

class RepaintRainbowAction extends AbstractToggleableAction {
  RepaintRainbowAction(@NotNull FlutterView view) {
    super(view, "Enable Repaint Rainbow");
  }

  protected void perform(AnActionEvent event) {
    view.getFlutterApp().callBooleanExtension("ext.flutter.repaintRainbow", isSelected(event));
  }
}

class TimeDilationAction extends AbstractToggleableAction {
  TimeDilationAction(@NotNull FlutterView view) {
    super(view, "Enable Slow Animations");
  }

  protected void perform(AnActionEvent event) {
    final Map<String, Object> params = new HashMap<>();
    params.put("timeDilation", isSelected(event) ? 5.0 : 1.0);
    view.getFlutterApp().callServiceExtension("ext.flutter.timeDilation", params);
  }
}

class ToggleInspectModeAction extends AbstractToggleableAction {
  ToggleInspectModeAction(@NotNull FlutterView view) {
    super(view, "Toggle Inspect Mode", "Toggle Inspect Mode", AllIcons.Actions.Menu_find);
  }

  protected void perform(AnActionEvent event) {
    view.getFlutterApp().callBooleanExtension("ext.flutter.widgetInspector", isSelected(event));
  }
}

class HideSlowBannerAction extends AbstractToggleableAction {
  HideSlowBannerAction(@NotNull FlutterView view) {
    super(view, "Hide Slow Mode Banner");
  }

  @Override
  protected void perform(AnActionEvent event) {
    view.getFlutterApp().callBooleanExtension("ext.flutter.debugAllowBanner", !isSelected(event));
  }
}

class ShowPaintBaselinesAction extends AbstractToggleableAction {
  ShowPaintBaselinesAction(@NotNull FlutterView view) {
    super(view, "Show Paint Baselines");
  }

  @Override
  protected void perform(AnActionEvent event) {
    view.getFlutterApp().callBooleanExtension("ext.flutter.debugPaintBaselinesEnabled", isSelected(event));
  }
}

class ObservatoryTimelineAction extends FlutterViewAction {
  ObservatoryTimelineAction(@NotNull FlutterView view) {
    super(view, "Open Observatory Timeline");
  }

  @Override
  public void actionPerformed(AnActionEvent event) {
    FlutterInitializer.sendAnalyticsAction(this);

    final String httpUrl = view.getFlutterApp().getConnector().getBrowserUrl();
    if (httpUrl != null) {
      OpenObservatoryAction.openInAnyChromeFamilyBrowser(httpUrl + "/#/timeline");
    }
  }
}

class MemoryDashboardAction extends FlutterViewAction {
  MemoryDashboardAction(@NotNull FlutterView view) {
    super(view, "Open Memory Dashboard");
  }

  @Override
  public void actionPerformed(AnActionEvent event) {
    FlutterInitializer.sendAnalyticsAction(this);

    final String httpUrl = view.getFlutterApp().getConnector().getBrowserUrl();
    if (httpUrl != null) {
      OpenObservatoryAction.openInAnyChromeFamilyBrowser(httpUrl + "/#/memory-dashboard");
    }
  }
}
