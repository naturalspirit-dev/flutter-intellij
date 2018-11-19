/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.server.vmService;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.jetbrains.lang.dart.flutter.FlutterUtil;
import gnu.trove.THashMap;
import io.flutter.FlutterUtils;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.server.vmService.HeapMonitor.HeapListener;
import io.flutter.utils.EventStream;
import io.flutter.utils.StreamSubscription;
import io.flutter.utils.VmServiceListenerAdapter;
import org.dartlang.vm.service.VmService;
import org.dartlang.vm.service.consumer.GetIsolateConsumer;
import org.dartlang.vm.service.consumer.VMConsumer;
import org.dartlang.vm.service.element.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class VMServiceManager implements FlutterApp.FlutterAppListener {
  @NotNull private final FlutterApp app;
  @NotNull private final HeapMonitor heapMonitor;
  @NotNull private final FlutterFramesMonitor flutterFramesMonitor;
  @NotNull private final Map<String, EventStream<Boolean>> serviceExtensions = new THashMap<>();

  // TODO(jacobr): on attach to a running Flutter isolate query the VM for the
  // current state of each of the boolean service extensions we care about.
  /**
   * Boolean value applicable only for boolean service extensions indicating
   * whether the service extension is enabled or disabled.
   */
  @NotNull private final Map<String, EventStream<Boolean>> serviceExtensionState = new THashMap<>();

  private final EventStream<IsolateRef> flutterIsolateRefStream;

  private boolean isRunning;
  private int polledCount;

  public VMServiceManager(@NotNull FlutterApp app, @NotNull VmService vmService) {
    this.app = app;
    app.addStateListener(this);
    this.heapMonitor = new HeapMonitor(vmService, app.getFlutterDebugProcess());
    this.flutterFramesMonitor = new FlutterFramesMonitor(vmService);
    this.polledCount = 0;
    flutterIsolateRefStream = new EventStream<>();

    vmService.addVmServiceListener(new VmServiceListenerAdapter() {
      @Override
      public void received(String streamId, Event event) {
        onVmServiceReceived(streamId, event);
      }

      @Override
      public void connectionClosed() {
        onVmConnectionClosed();
      }
    });

    // Populate the service extensions info and look for any Flutter views.
    // TODO(devoncarew): This currently returns the first Flutter view found as the
    // current Flutter isolate, and ignores any other Flutter views running in the app.
    // In the future, we could add more first class support for multiple Flutter views.
    vmService.getVM(new VMConsumer() {
      @Override
      public void received(VM vm) {
        for (final IsolateRef isolateRef : vm.getIsolates()) {
          vmService.getIsolate(isolateRef.getId(), new GetIsolateConsumer() {
            @Override
            public void onError(RPCError error) {
            }

            @Override
            public void received(Isolate isolate) {
              // Populate flutter isolate info.
              if (flutterIsolateRefStream.getValue() == null) {
                if (isolate.getExtensionRPCs() != null) {
                  for (String extensionName : isolate.getExtensionRPCs()) {
                    if (extensionName.startsWith("ext.flutter.")) {
                      setFlutterIsolate(isolateRef);
                      break;
                    }
                  }
                }
              }
              addRegisteredExtensionRPCs(isolate);
            }

            @Override
            public void received(Sentinel sentinel) {
            }
          });
        }
      }

      @Override
      public void onError(RPCError error) {
      }
    });
  }

  public void addRegisteredExtensionRPCs(Isolate isolate) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (isolate.getExtensionRPCs() != null) {
        for (String extension : isolate.getExtensionRPCs()) {
          addServiceExtension(extension);
        }
      }
    });
  }

  /**
   * Start the Perf service.
   */
  public void start() {
    if (!isRunning) {
      // Start polling.
      heapMonitor.start();
      isRunning = true;
    }
  }

  /**
   * Returns a StreamSubscription providing the current Flutter isolate.
   * <p>
   * The current value of the subscription can be null occasionally during initial application startup and for a brief time when doing a
   * hot restart.
   */
  public StreamSubscription<IsolateRef> getCurrentFlutterIsolate(Consumer<IsolateRef> onValue, boolean onUIThread) {
    return flutterIsolateRefStream.listen(onValue, onUIThread);
  }

  /**
   * Return the current Flutter IsolateRef, if any.
   * <p>
   * Note that this may not be immediately populated at app startup for Flutter apps; clients that wish to
   * be notified when the Flutter isolate is discovered should prefer the StreamSubscription varient of this
   * method (getCurrentFlutterIsolate()).
   */
  public IsolateRef getCurrentFlutterIsolateRaw() {
    synchronized (flutterIsolateRefStream) {
      return flutterIsolateRefStream.getValue();
    }
  }

  /**
   * Stop the Perf service.
   */
  public void stop() {
    if (isRunning) {
      // The vmService does not have a way to remove listeners, so we can only stop paying attention.
      heapMonitor.stop();
    }
  }

  private void onVmConnectionClosed() {
    if (isRunning) {
      heapMonitor.stop();
    }

    isRunning = false;
  }

  private void setFlutterIsolate(IsolateRef ref) {
    synchronized (flutterIsolateRefStream) {
      final IsolateRef existing = flutterIsolateRefStream.getValue();
      if (existing == ref || (existing != null && ref != null && StringUtil.equals(existing.getId(), ref.getId()))) {
        // Isolate didn't change.
        return;
      }
      flutterIsolateRefStream.setValue(ref);
    }
  }

  private void resetAvailableExtensions() {
    final Iterable<EventStream<Boolean>> existingExtensions;
    synchronized (serviceExtensions) {
      existingExtensions = new ArrayList<>(serviceExtensions.values());
    }
    for (EventStream<Boolean> serviceExtension : existingExtensions) {
      serviceExtension.setValue(false);
    }
  }

  @SuppressWarnings("EmptyMethod")
  private void onVmServiceReceived(String streamId, Event event) {
    // Check for the current Flutter isolate exiting.
    final IsolateRef flutterIsolateRef = flutterIsolateRefStream.getValue();
    if (flutterIsolateRef != null) {
      if (event.getKind() == EventKind.IsolateExit && StringUtil.equals(event.getIsolate().getId(), flutterIsolateRef.getId())) {
        setFlutterIsolate(null);
        resetAvailableExtensions();
      }
    }

    if (event.getKind() == EventKind.ServiceExtensionAdded) {
      addServiceExtension(event.getExtensionRPC());
    }

    // Check to see if there's a new Flutter isolate.
    if (flutterIsolateRefStream.getValue() == null) {
      // Check for Flutter frame events.
      if (event.getKind() == EventKind.Extension && event.getExtensionKind().startsWith("Flutter.")) {
        // Flutter.FrameworkInitialization, Flutter.FirstFrame, Flutter.Frame
        setFlutterIsolate(event.getIsolate());
      }

      // Check for service extension registrations.
      if (event.getKind() == EventKind.ServiceExtensionAdded) {
        final String extensionName = event.getExtensionRPC();

        if (extensionName.startsWith("ext.flutter.")) {
          setFlutterIsolate(event.getIsolate());
        }
      }
    }

    if (!isRunning) {
      return;
    }

    if (StringUtil.equals(streamId, VmService.GC_STREAM_ID)) {
      final IsolateRef isolateRef = event.getIsolate();
      final HeapMonitor.HeapSpace newHeapSpace = new HeapMonitor.HeapSpace(event.getJson().getAsJsonObject("new"));
      final HeapMonitor.HeapSpace oldHeapSpace = new HeapMonitor.HeapSpace(event.getJson().getAsJsonObject("old"));

      heapMonitor.handleGCEvent(isolateRef, newHeapSpace, oldHeapSpace);
    }
  }

  /**
   * This method must only be called on the UI thread.
   */
  private void addServiceExtension(String name) {
    synchronized (serviceExtensions) {
      final EventStream<Boolean> stream = serviceExtensions.get(name);
      if (stream == null) {
        serviceExtensions.put(name, new EventStream<>(true));
      }
      else if (!stream.getValue()) {
        stream.setValue(true);
      }

      // Restore any previously true states by calling their service extensions.
      if (getServiceExtensionState(name).getValue()) {
        restoreServiceExtensionState(name);
      }
    }
  }

  private void restoreServiceExtensionState(String name) {
    if (app.isSessionActive()) {
      // We should not call the service extension for the follwing extensions.
      if (StringUtil.equals(name, "ext.flutter.inspector.show")
          || StringUtil.equals(name, "ext.flutter.platformOverride")) {
        // Do not call the service extension for these extensions. 1) We do not want to persist showing the
        // inspector on restart. 2) Restoring the platform override state is handled by [TogglePlatformAction].
        return;
      }
      else if (StringUtil.equals(name, "ext.flutter.timeDilation")) {
        final Map<String, Object> params = new HashMap<>();
        params.put("timeDilation", 5.0);
        app.callServiceExtension("ext.flutter.timeDilation", params);
      }
      else {
        app.callBooleanExtension(name, true);
      }
    }
  }

  @NotNull
  public FlutterFramesMonitor getFlutterFramesMonitor() {
    return flutterFramesMonitor;
  }

  /**
   * Add a listener for heap state updates.
   */
  public void addHeapListener(@NotNull HeapListener listener) {
    final boolean hadListeners = heapMonitor.hasListeners();

    heapMonitor.addListener(listener);

    if (!hadListeners) {
      start();
    }
  }

  /**
   * Remove a heap listener.
   */
  public void removeHeapListener(@NotNull HeapListener listener) {
    heapMonitor.removeListener(listener);

    if (!heapMonitor.hasListeners()) {
      stop();
    }
  }

  public @NotNull
  StreamSubscription<Boolean> hasServiceExtension(String name, Consumer<Boolean> onData) {
    EventStream<Boolean> stream = getStream(name, serviceExtensions);
    return stream.listen(onData, true);
  }

  public @NotNull
  EventStream<Boolean> getServiceExtensionState(String name) {
    return getStream(name, serviceExtensionState);
  }

  @NotNull
  private EventStream<Boolean> getStream(String name, Map<String, EventStream<Boolean>> streamMap) {
    EventStream<Boolean> stream;
    synchronized (streamMap) {
      stream = streamMap.get(name);
      if (stream == null) {
        stream = new EventStream<>(false);
        streamMap.put(name, stream);
      }
    }
    return stream;
  }

  /**
   * Returns whether a service extension matching the specified name has
   * already been registered.
   * <p>
   * If the service extension may be registered at some point in the future it
   * is bests use hasServiceExtension as well to listen for changes in whether
   * the extension is present.
   */
  public boolean hasServiceExtensionNow(String name) {
    synchronized (serviceExtensions) {
      final EventStream<Boolean> stream = serviceExtensions.get(name);
      return stream != null && stream.getValue() == Boolean.TRUE;
    }
  }

  public void hasServiceExtension(String name, Consumer<Boolean> onData, Disposable parentDisposable) {
    Disposer.register(parentDisposable, hasServiceExtension(name, onData));
  }

  public void addPollingClient() {
    polledCount++;
    resumePolling();
  }

  public void removePollingClient() {
    if (polledCount > 0) polledCount--;
    pausePolling();
  }

  private boolean anyPollingClients() {
    return polledCount > 0;
  }

  private void pausePolling() {
    if (isRunning && !anyPollingClients()) {
      heapMonitor.pausePolling();
    }
  }

  private void resumePolling() {
    if (isRunning && anyPollingClients()) {
      heapMonitor.resumePolling();
    }
  }

  @Override
  public void stateChanged(FlutterApp.State newState) {
    if (newState == FlutterApp.State.RESTARTING) {
      // The set of service extensions available may be different once the app
      // restarts and no service extensions will be availabe until the app is
      // suitably far along in the restart process. It turns out the
      // IsolateExit event cannot be relied on to track when a restart is
      // occurring for unclear reasons.
      resetAvailableExtensions();
    }
  }
}
