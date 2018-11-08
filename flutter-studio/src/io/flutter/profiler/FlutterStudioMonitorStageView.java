/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.profiler;

import com.android.tools.adtui.*;
import com.android.tools.adtui.chart.linechart.LineChart;
import com.android.tools.adtui.chart.linechart.LineConfig;
import com.android.tools.adtui.model.*;
import com.android.tools.adtui.model.axis.ResizingAxisComponentModel;
import com.android.tools.adtui.model.formatter.BaseAxisFormatter;
import com.android.tools.adtui.model.formatter.MemoryAxisFormatter;
import com.android.tools.adtui.model.legend.LegendComponentModel;
import com.android.tools.adtui.model.legend.SeriesLegend;
import com.android.tools.profilers.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBPanel;
import io.flutter.server.vmService.VMServiceManager;
import io.flutter.utils.AsyncUtils;
import io.flutter.utils.StreamSubscription;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.dartlang.vm.service.VmService;
import org.dartlang.vm.service.consumer.AllocationProfileConsumer;
import org.dartlang.vm.service.consumer.GetIsolateConsumer;
import org.dartlang.vm.service.consumer.GetObjectConsumer;
import org.dartlang.vm.service.element.AllocationProfile;
import org.dartlang.vm.service.element.BoundField;
import org.dartlang.vm.service.element.ClassRef;
import org.dartlang.vm.service.element.ElementList;
import org.dartlang.vm.service.element.Event;
import org.dartlang.vm.service.element.Instance;
import org.dartlang.vm.service.element.InstanceKind;
import org.dartlang.vm.service.element.InstanceRef;
import org.dartlang.vm.service.element.Isolate;
import org.dartlang.vm.service.element.IsolateRef;
import org.dartlang.vm.service.element.LibraryRef;
import org.dartlang.vm.service.element.Obj;
import org.dartlang.vm.service.element.RPCError;
import org.dartlang.vm.service.element.Sentinel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

import static com.android.tools.adtui.common.AdtUiUtils.DEFAULT_HORIZONTAL_BORDERS;
import static com.android.tools.adtui.common.AdtUiUtils.DEFAULT_VERTICAL_BORDERS;
import static com.android.tools.profilers.ProfilerLayout.MARKER_LENGTH;
import static com.android.tools.profilers.ProfilerLayout.MONITOR_LABEL_PADDING;
import static com.android.tools.profilers.ProfilerLayout.Y_AXIS_TOP_MARGIN;
import static io.flutter.profiler.FilterLibraryDialog.DART_LIBRARY_PREFIX;
import static javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN;

/**
 * Bird eye view displaying high-level information across all profilers.
 * Refactored from Android Studio 3.2 adt-ui code.
 */
public class FlutterStudioMonitorStageView extends FlutterStageView<FlutterStudioMonitorStage> {
  private static final Logger LOG = Logger.getInstance(FlutterStudioMonitorStageView.class);

  private static final String TAB_NAME = "Memory";
  private static final String HEAP_LABEL = "Heap";

  private static final Color MEMORY_USED = new JBColor(new Color(0x56BFEC), new Color(0x2B7DA2));
  private static final Color MEMORY_EXTERNAL = new JBColor(new Color(0x56A5CB), new Color(0x226484));
  private static final Color MEMORY_CAPACITY = new JBColor(new Color(0x1B4D65), new Color(0xF6F6F6));

  static final BaseAxisFormatter MEMORY_AXIS_FORMATTER = new MemoryAxisFormatter(1, 5, 5);

  public static final Border MONITOR_BORDER = BorderFactory.createMatteBorder(0, 0, 1, 0, ProfilerColors.MONITOR_BORDER);

  private static final int AXIS_SIZE = 100000;

  JPanel classesPanel;                          // All classes information in this panel.
  JScrollPane heapObjectsScoller;               // Contains the JTree of heap objects.
  private final JLabel classesStatusArea;       // Classes status area.
  private final JTable classesTable;            // Display classes found in the heap snapshot.

  private JPanel instancesPanel;                // All instances info in panel (title, close and JTree).
  private JLabel instancesTitleArea;            // Display of all instances displayed.
  private JScrollPane instanceObjectsScoller;   // Contains the JTree of instance objects.
  private final JTree instanceObjects;          // Instances of all objects of the same object class type.

  private List<RangedContinuousSeries> rangedData;
  private LegendComponentModel legendComponentModel;
  private LegendComponent legendComponent;
  private Range timeGlobalRangeUs;

  @NotNull private final JBSplitter myMainSplitter = new JBSplitter(false);
  @NotNull private final JBSplitter myChartCaptureSplitter = new JBSplitter(true);
  @NotNull private final JBSplitter myInstanceDetailsSplitter = new JBSplitter(true);

  String isolateId;
  static final String GRAPH_EVENTS = "_Graph";

  // Libraries to filter in ClassRefs.
  Set<String> filteredLibraries = new HashSet<String>();

  // Memory objects currently active.
  Memory memorySnapshot;

  // Used to manage fetching instances status.
  boolean runningComputingInstances;

  // All Dart Libraries associated with the Flutter application, key is name and value is Uri as String.
  final Map<String, LibraryRef> allLibraries = new HashMap<>();
  boolean hideDartLibraries = true;
  final Map<String, LibraryRef> dartLibraries = new HashMap<>();

  // TODO(terry): Remove below debugging before checking in.
  LineChartModel debugModel;

  private void DEBUG_dumpChartModels() {
    List<SeriesData<Long>> usedSeriesData = ((List<SeriesData<Long>>)(debugModel.getSeries().get(0).getSeries()));
    List<SeriesData<Long>> capacitySeriesData = ((List<SeriesData<Long>>)(debugModel.getSeries().get(1).getSeries()));
    List<SeriesData<Long>> externalSeriesData = ((List<SeriesData<Long>>)(debugModel.getSeries().get(2).getSeries()));

    assert (usedSeriesData.size() == capacitySeriesData.size() && usedSeriesData.size() == externalSeriesData.size());
    for (int i = 0; i < usedSeriesData.size(); i++) {
      long usedTime = usedSeriesData.get(i).x;
      long capacityTime = capacitySeriesData.get(i).x;
      long externalTime = externalSeriesData.get(i).x;

      long usedValue = usedSeriesData.get(i).value;
      long capacityValue = capacitySeriesData.get(i).value;
      long externalValue = externalSeriesData.get(i).value;

      System.out.println("DUMP time [" + i + "] " + usedTime);
      if (usedTime == capacityTime && usedTime == externalTime) {
        System.out.println("    " + usedValue + ", " + capacityValue + ", " + externalValue);
      }
      else {
        System.out.println("ERROR: timestamps don't match for entry " + i);
      }
    }
  }

  public FlutterStudioMonitorStageView(@NotNull FlutterStudioProfilersView profilersView,
                                       @NotNull FlutterStudioMonitorStage stage) {
    super(profilersView, stage);

    memorySnapshot = new Memory();

    initializeVM();

    // Hookup splitters.
    myMainSplitter.getDivider().setBorder(DEFAULT_VERTICAL_BORDERS);
    myChartCaptureSplitter.getDivider().setBorder(DEFAULT_HORIZONTAL_BORDERS);
    myInstanceDetailsSplitter.getDivider().setBorder(DEFAULT_HORIZONTAL_BORDERS);

    myChartCaptureSplitter.setFirstComponent(buildUI(stage));

    classesTable = new JTable(memorySnapshot.getClassesTableModel());
    classesTable.setVisible(true);
    classesTable.setAutoCreateRowSorter(true);
    classesTable.getRowSorter().toggleSortOrder(1);   // Sort by number of instances in descending order.
    classesTable.getColumnModel().getColumn(0).setPreferredWidth(400);
    classesTable.getColumnModel().getColumn(1).setPreferredWidth(200);
    classesTable.getColumnModel().getColumn(2).setPreferredWidth(AUTO_RESIZE_LAST_COLUMN);
    classesTable.doLayout();

    heapObjectsScoller = new JScrollPane(classesTable);

    FlutterStudioMonitorStageView view = (FlutterStudioMonitorStageView)(this);

    boolean computingInstances = false;

    classesTable.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        // Only allow one compute all instances of a particular ClassRef one at a
        // time.
        if (runningComputingInstances) {
          view.updateClassesStatus("Ignored - other instances computation in process...");
          return;
        }

        memorySnapshot.removeAllInstanceChildren(true);

        // Find the selected item in the JTable.
        JTable selectedUi = (JTable)(e.getSource());

        int col = selectedUi.columnAtPoint(e.getPoint());

        int uiRowIndex = selectedUi.getSelectionModel().getMinSelectionIndex();
        int modelIndex = selectedUi.convertRowIndexToModel(uiRowIndex);

        Memory.ClassesTableModel tableModel = (Memory.ClassesTableModel)(selectedUi.getModel());
        Memory.ClassNode classNode = tableModel.getClassNode(modelIndex);
        ClassRef cls = classNode.getClassRef();

        String classRef = cls.getId();

        int instanceLimit = classNode.getInstancesCount();

        view.updateClassesStatus("Fetching " + instanceLimit + " instances.");

        AsyncUtils.whenCompleteUiThread(getInstances(classRef, instanceLimit), (JsonObject response, Throwable exception) -> {

          JsonArray instances = response.getAsJsonArray("samples");
          int totalInstances = response.get("totalCount").getAsInt();
          List<String> instanceIds = new ArrayList<String>(totalInstances);

          Iterator it = instances.iterator();
          int gettingInstanceCount = 0;

          while (it.hasNext()) {
            gettingInstanceCount++;
            view.updateClassesStatus("Processing " + gettingInstanceCount + " of " + instanceLimit + " instances.");

            JsonObject instance = (JsonObject)(it.next());
            String objectRef = instance.get("id").getAsString();

            instanceIds.add(objectRef);

            final Map<String, Object> objectParams = new HashMap<>();
            objectParams.put("objectId", objectRef);
            // TODO(terry): Display returned instances.
          }

          LOG.info("Instances returned " + instanceIds.size());

          classNode.addInstances(instanceIds);
          memorySnapshot.decodeInstances(view, instanceIds, instanceObjects);

          runningComputingInstances = false;
          view.updateClassesStatus("All Instances Processed.");
          view.setClassForInstancesTitle(cls.getName());

          // Update the UI
          instanceObjects.setVisible(true);
          instancesPanel.setVisible(true);
        });
      }
    });

    instanceObjects = new JTree();
    instanceObjects.setVisible(true);

    instanceObjects.setEditable(false);
    instanceObjects.getSelectionModel().setSelectionMode
      (TreeSelectionModel.SINGLE_TREE_SELECTION);
    instanceObjects.setShowsRootHandles(false);

    instanceObjects.addTreeExpansionListener(new TreeExpansionListener() {
      @Override
      public void treeExpanded(TreeExpansionEvent event) {
        TreePath expanded = event.getPath();
        DefaultMutableTreeNode node = (DefaultMutableTreeNode)(expanded.getLastPathComponent());

        // Only compute if not computed
        // TODO(terry): Better check like UserObject not null???
        if (node.getChildCount() == 1) {
          if (node.getFirstChild().toString() == "") {
            // TODO(terry): Don't inundate the VM with more than more one at a time.
            inspectInstance(node);
          }
          else {
            DefaultMutableTreeNode childNode = (DefaultMutableTreeNode)(node.getFirstChild());
            Object userObj = childNode.getUserObject();
            if (userObj instanceof String) {
              String objectRef = (String)(userObj);
              if (objectRef.startsWith("objects/")) {
                getObject(node, objectRef);
              }
            }
          }
        }
      }

      @Override
      public void treeCollapsed(TreeExpansionEvent event) { }
    });

    classesPanel = new JPanel();
    classesPanel.setVisible(false);
    classesPanel.setLayout(new BoxLayout(classesPanel, BoxLayout.PAGE_AXIS));

    JPanel classesToolbar = new JPanel();
    classesToolbar.setLayout(new BorderLayout());
    classesToolbar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

    classesStatusArea = new JLabel();
    classesStatusArea.setText("Computing...");
    classesToolbar.add(classesStatusArea, BorderLayout.WEST);

    JButton closeClasses = new JButton("x");
    closeClasses.setToolTipText("Close Classes");
    closeClasses.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        classesPanel.setVisible(false);
        instancesPanel.setVisible(false);
      }
    });

    closeClasses.setBorder(new EmptyBorder(0, 0, 0, 0));
    closeClasses.setPreferredSize(new Dimension(20, 20));
    closeClasses.setMaximumSize(new Dimension(20, 20));
    classesToolbar.add(closeClasses, BorderLayout.EAST);

    classesPanel.add(classesToolbar, BorderLayout.PAGE_END);
    classesPanel.add(heapObjectsScoller);

    instanceObjectsScoller = new JScrollPane(instanceObjects);

    instancesPanel = new JPanel();
    instancesPanel.setVisible(false);
    instancesPanel.setLayout(new BoxLayout(instancesPanel, BoxLayout.PAGE_AXIS));

    JPanel instancesToolbar = new JPanel();
    instancesToolbar.setLayout(new BorderLayout());
    instancesToolbar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

    instancesTitleArea = new JLabel();
    setClassForInstancesTitle("");
    instancesToolbar.add(instancesTitleArea, BorderLayout.WEST);

    JButton closeInstances = new JButton("x");
    closeInstances.setToolTipText("Close Instances");
    closeInstances.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        instancesPanel.setVisible(false);
      }
    });

    closeInstances.setBorder(new EmptyBorder(0, 0, 0, 0));
    closeInstances.setPreferredSize(new Dimension(20, 20));
    closeInstances.setMaximumSize(new Dimension(20, 20));
    instancesToolbar.add(closeInstances, BorderLayout.EAST);

    instancesPanel.add(instancesToolbar, BorderLayout.PAGE_END);
    instancesPanel.add(instanceObjectsScoller);

    instancesPanel.setVisible(false);

    myInstanceDetailsSplitter.setOpaque(true);
    myInstanceDetailsSplitter.setFirstComponent(instancesPanel);

    myChartCaptureSplitter.setSecondComponent(classesPanel);

    myInstanceDetailsSplitter.setOpaque(true);
    myInstanceDetailsSplitter.setFirstComponent(instancesPanel);

    myMainSplitter.setFirstComponent(myChartCaptureSplitter);
    myMainSplitter.setSecondComponent(myInstanceDetailsSplitter);
    myMainSplitter.setProportion(0.6f);

    // Display in the Inspector Memory tab.
    getComponent().add(myMainSplitter, BorderLayout.CENTER);
  }

  public JTable getClassesTable() { return classesTable; }

  void inspectInstance(@NotNull DefaultMutableTreeNode node) {
    // Is it one of our models?  Otherwise its synthesized nodes from other getObjects.
    if (node.getUserObject() instanceof Memory.InstanceNode) {
      Memory.InstanceNode instanceNode = (Memory.InstanceNode)(node.getUserObject());
      String objectRef = instanceNode.getObjectRef();
      getObject(node, objectRef);
    }
  }

  void updateClassesStatus(String newStatus) {
    classesStatusArea.setText(newStatus);
  }

  void setClassForInstancesTitle(String className) {
    instancesTitleArea.setText("Instances for Class " + className);
  }

  public CompletableFuture<Isolate> vmGetLibraries() {
    FlutterStudioProfilers profilers = getStage().getStudioProfilers();

    CompletableFuture<Isolate> isolateFuture = new CompletableFuture<>();
    profilers.getApp().getVmService().getIsolate(isolateId, new GetIsolateConsumer() {
      @Override
      public void onError(RPCError error) {
        isolateFuture.completeExceptionally(new RuntimeException(error.getMessage()));
      }

      @Override
      public void received(Isolate response) {
        ElementList<LibraryRef> libraryRefs = response.getLibraries();
        Iterator<LibraryRef> iterator = libraryRefs.iterator();
        while (iterator.hasNext()) {
          LibraryRef ref = iterator.next();

          if (ref.getName().length() > 0) {
            // Non-empty string is the library name (use it for key)
            if (ref.getName().startsWith("dart.")) {
              dartLibraries.put(ref.getName(), ref);
            }
            else if (ref.getUri().startsWith("file:///")) {
              // Is user code (not in a package or library)
              // TODO(terry): Need to store local file names for each libraryRef we'll always display classes from user code.
              allLibraries.put(ref.getName(), ref);
            }
            else {
              if (ref.getUri().startsWith(DART_LIBRARY_PREFIX)) {
                // Library named 'nativewrappers' but URI is 'dart:nativewrappers' is a Dart library.
                dartLibraries.put(ref.getName(), ref);
              }
              else {
                allLibraries.put(ref.getUri(), ref);
              }
            }
          }
        }
        allLibraries.put("dart:*", null);   // All Dart libraries are in this entry.

        // The initial list of selected libraries is all of them.
        getProfilersView().setInitialSelectedLibraries(allLibraries.keySet());
        isolateFuture.complete(response);
      }

      @Override
      public void received(Sentinel response) {
        // Unable to get the isolate.
        isolateFuture.complete(null);
      }
    });
    return isolateFuture;
  }

  public CompletableFuture<JsonObject> vmGetObject(String classOrInstanceRefId) {
    final CompletableFuture<JsonObject> future = new CompletableFuture<JsonObject>();

    FlutterStudioProfilers profilers = getStage().getStudioProfilers();

    profilers.getApp().getVmService().getObject(isolateId, classOrInstanceRefId, new GetObjectConsumer() {
                                                  @Override
                                                  public void onError(RPCError error) {
                                                    future.completeExceptionally(new RuntimeException(error.toString()));
                                                  }

                                                  @Override
                                                  public void received(Obj response) {
                                                    updateClassesStatus("getObject " + classOrInstanceRefId + " processed.");
                                                    future.complete((response.getJson()));
                                                  }

                                                  @Override
                                                  public void received(Sentinel response) {
                                                    future.completeExceptionally(new RuntimeException(response.toString()));
                                                  }
                                                }
    );

    return future;
  }

  public CompletableFuture<JsonObject> getInstances(String classRef, int maxInstances) {
    final Map<String, Object> params = new HashMap<>();
    params.put("classId", classRef);
    params.put("limit", maxInstances);

    FlutterStudioProfilers profilers = getStage().getStudioProfilers();
    return profilers.getApp().callServiceExtension("_getInstances", params)
      .thenApply((response) -> response)
      .exceptionally(err -> {
        LOG.warn(err);
        return null;
      });
  }

  private void initializeVM() {
    FlutterStudioProfilers profilers = getStage().getStudioProfilers();
    VMServiceManager vmServiceMgr = profilers.getApp().getVMServiceManager();

    StreamSubscription<IsolateRef> subscription = vmServiceMgr.getCurrentFlutterIsolate((isolate) -> {
      CompletableFuture<Object> libraryRef = new CompletableFuture<>();
      if (libraryRef.isDone()) {
        libraryRef = new CompletableFuture<>();
      }

      if (isolate != null) {
        isolateId = isolate.getId();

        // Known libraries used for this application.
        vmGetLibraries();
      }
    }, true);
  }

  protected void closeClassObjectDetails() {
    memorySnapshot.removeAllInstanceChildren(true);
    memorySnapshot.removeAllClassChildren(true);

    memorySnapshot._myClassesTreeModel.reload();
    memorySnapshot._myInstancesTreeModel.reload();

    //heapObjectsScoller.setVisible(false);
    classesPanel.setVisible(false);
    instanceObjectsScoller.setVisible(false);
  }

  protected void displaySnapshot(FlutterStudioMonitorStageView view) {
    memorySnapshot.removeAllClassChildren(true);
    classesTable.updateUI();

    classesPanel.setVisible(true);

    // Let's do a snapshot...
    FlutterStudioProfilers profilers = getStage().getStudioProfilers();
    VmService vmService = profilers.getApp().getVmService();

    // TODO(terry): For now call AllocationProfile however, we need to request snapshot (binary data).

    final CompletableFuture<AllocationProfile> future = new CompletableFuture<AllocationProfile>();

    vmService.getAllocationProfile(isolateId, new AllocationProfileConsumer() {

      @Override
      public void onError(RPCError error) {
        LOG.error("Allocation Profile - " + error.getDetails());
        future.completeExceptionally(new RuntimeException(error.toString()));
      }

      @Override
      public void received(AllocationProfile response) {
        future.complete(response);

        updateClassesStatus("Allocations Received");

        memorySnapshot.decodeClassesInHeap(view, response, classesTable);
      }
    });
  }

  private JPanel buildUI(@NotNull FlutterStudioMonitorStage stage) {
    ProfilerTimeline timeline = stage.getStudioProfilers().getTimeline();
    Range viewRange = getTimeline().getViewRange();

    SelectionModel selectionModel = new SelectionModel(timeline.getSelectionRange());
    SelectionComponent selection = new SelectionComponent(selectionModel, timeline.getViewRange());
    selection.setCursorSetter(ProfilerLayeredPane::setCursorOnProfilerLayeredPane);
    selectionModel.addListener(new SelectionListener() {
      @Override
      public void selectionCreated() {
        // TODO(terry): Bring up the memory object list view using getTimeline().getSelectionRange().getMin() .. getMax().
        // TODO(terry): Need to record all memory statistics then the snapshot shows the collected range will need VM support.
        //displaySnapshot();
      }

      @Override
      public void selectionCleared() {
        // Clear stuff here
      }
    });

    RangeTooltipComponent
      tooltip = new RangeTooltipComponent(timeline.getTooltipRange(), timeline.getViewRange(),
                                          timeline.getDataRange(), getTooltipPanel(),
                                          getProfilersView().getComponent(), () -> true);

    TabularLayout layout = new TabularLayout("*");
    JPanel panel = new JBPanel(layout);
    panel.setBackground(ProfilerColors.DEFAULT_BACKGROUND);

    ProfilerScrollbar sb = new ProfilerScrollbar(timeline, panel);
    panel.add(sb, new TabularLayout.Constraint(3, 0));

    FlutterStudioProfilers profilers = stage.getStudioProfilers();
    JComponent timeAxis = buildTimeAxis(profilers);

    panel.add(timeAxis, new TabularLayout.Constraint(2, 0));

    JPanel monitorPanel = new JBPanel(new TabularLayout("*", "*"));
    monitorPanel.setOpaque(false);
    monitorPanel.setBorder(MONITOR_BORDER);
    final JLabel label = new JLabel(TAB_NAME);
    label.setBorder(MONITOR_LABEL_PADDING);
    label.setVerticalAlignment(JLabel.TOP);
    label.setForeground(ProfilerColors.MONITORS_HEADER_TEXT);

    final JPanel lineChartPanel = new JBPanel(new BorderLayout());
    lineChartPanel.setOpaque(false);

    // Initial size of Y-axis in MB set a range of 100 MB multiply is 1024 (1K), so upper range is 100 MB
    // to start with.
    Range yRange1Animatable = new Range(0, AXIS_SIZE);
    AxisComponent yAxisBytes;

    ResizingAxisComponentModel yAxisAxisBytesModel =
      new ResizingAxisComponentModel.Builder(yRange1Animatable, MemoryAxisFormatter.DEFAULT).setLabel(HEAP_LABEL).build();
    yAxisBytes = new AxisComponent(yAxisAxisBytesModel, AxisComponent.AxisOrientation.RIGHT);

    yAxisBytes.setShowMax(true);
    yAxisBytes.setShowUnitAtMax(true);
    yAxisBytes.setHideTickAtMin(true);
    yAxisBytes.setMarkerLengths(MARKER_LENGTH, MARKER_LENGTH);
    yAxisBytes.setMargins(0, Y_AXIS_TOP_MARGIN);

    LineChartModel model = new LineChartModel();

    FlutterAllMemoryData.ThreadSafeData memoryUsedDataSeries = stage.getUsedDataSeries();
    FlutterAllMemoryData.ThreadSafeData memoryMaxDataSeries = stage.getCapacityDataSeries();
    FlutterAllMemoryData.ThreadSafeData memoryExternalDataSeries = stage.getExternalDataSeries();

    Range dataRanges = new Range(0, 1024 * 1024 * 100);
    RangedContinuousSeries usedMemoryRange =
      new RangedContinuousSeries("Memory", getTimeline().getViewRange(), dataRanges, memoryUsedDataSeries);
    RangedContinuousSeries maxMemoryRange =
      new RangedContinuousSeries("MemoryMax", getTimeline().getViewRange(), dataRanges, memoryMaxDataSeries);
    RangedContinuousSeries externalMemoryRange =
      new RangedContinuousSeries("MemoryExtern", getTimeline().getViewRange(), dataRanges, memoryExternalDataSeries);

    model.add(maxMemoryRange);        // Plot total size of allocated heap.
    model.add(externalMemoryRange);   // Plot total size of external memory (bottom of stacked chart).
    model.add(usedMemoryRange);       // Plot used memory (top of stacked chart).

    debugModel = model;

    getStage().getStudioProfilers().getUpdater().register(model);
    LineChart mLineChart = new LineChart(model);
    mLineChart.setBackground(JBColor.background());

    mLineChart.configure(maxMemoryRange, new LineConfig(MEMORY_CAPACITY)
      .setStroke(LineConfig.DEFAULT_DASH_STROKE).setLegendIconType(LegendConfig.IconType.DASHED_LINE));

    // Stacked chart of external and used memory.
    configureStackedFilledLine(mLineChart, MEMORY_USED, usedMemoryRange);
    configureStackedFilledLine(mLineChart, MEMORY_EXTERNAL, externalMemoryRange);

    mLineChart.setRenderOffset(0, (int)LineConfig.DEFAULT_DASH_STROKE.getLineWidth() / 2);
    mLineChart.setTopPadding(Y_AXIS_TOP_MARGIN);
    mLineChart.setFillEndGap(true);

    final JPanel axisPanel = new JBPanel(new BorderLayout());
    axisPanel.setOpaque(false);
    axisPanel.add(yAxisBytes, BorderLayout.WEST);

    // Build the legend.
    FlutterAllMemoryData.ThreadSafeData memoryMax = stage.getCapacityDataSeries();
    FlutterAllMemoryData.ThreadSafeData memoryExternal = stage.getExternalDataSeries();
    FlutterAllMemoryData.ThreadSafeData memoryUsed = stage.getUsedDataSeries();

    Range allData = getTimeline().getDataRange();

    legendComponentModel = new LegendComponentModel(new Range(100.0, 100.0));
    timeGlobalRangeUs = new Range(0, 0);

    RangedContinuousSeries maxHeapRangedData = new RangedContinuousSeries("Max Heap", timeGlobalRangeUs, allData, memoryMax);
    RangedContinuousSeries usedHeapRangedData = new RangedContinuousSeries("Used Heap", timeGlobalRangeUs, allData, memoryUsed);
    RangedContinuousSeries externalHeapRangedData = new RangedContinuousSeries("External", timeGlobalRangeUs, allData, memoryExternal);

    SeriesLegend legendMax = new SeriesLegend(maxHeapRangedData, MEMORY_AXIS_FORMATTER, timeGlobalRangeUs);
    legendComponentModel.add(legendMax);
    SeriesLegend legendUsed = new SeriesLegend(usedHeapRangedData, MEMORY_AXIS_FORMATTER, timeGlobalRangeUs);
    legendComponentModel.add(legendUsed);
    SeriesLegend legendExternal = new SeriesLegend(externalHeapRangedData, MEMORY_AXIS_FORMATTER, timeGlobalRangeUs);
    legendComponentModel.add(legendExternal);

    legendComponent = new LegendComponent(legendComponentModel);

    legendComponent.configure(legendMax, new LegendConfig(LegendConfig.IconType.DASHED_LINE, MEMORY_CAPACITY));
    legendComponent.configure(legendUsed, new LegendConfig(LegendConfig.IconType.BOX, MEMORY_USED));
    legendComponent.configure(legendExternal, new LegendConfig(LegendConfig.IconType.BOX, MEMORY_EXTERNAL));

    // Place legend in a panel.
    final JPanel legendPanel = new JBPanel(new BorderLayout());
    legendPanel.setOpaque(false);
    legendPanel.add(legendComponent, BorderLayout.EAST);

    // Make the legend visible.
    monitorPanel.add(legendPanel, new TabularLayout.Constraint(0, 0));

    monitorPanel.add(tooltip, new TabularLayout.Constraint(0, 0));
    monitorPanel.add(selection, new TabularLayout.Constraint(0, 0));
    monitorPanel.add(yAxisBytes, new TabularLayout.Constraint(0, 0));
    monitorPanel.add(mLineChart, new TabularLayout.Constraint(0, 0));

    layout.setRowSizing(1, "*"); // Give monitor as much space as possible
    panel.add(monitorPanel, new TabularLayout.Constraint(1, 0));

    return panel;
  }

  private static void configureStackedFilledLine(LineChart chart, Color color, RangedContinuousSeries series) {
    chart.configure(series, new LineConfig(color).setFilled(true).setStacked(true).setLegendIconType(LegendConfig.IconType.BOX));
  }

  private void expandMonitor(ProfilerMonitor monitor) {
    // Track first, so current stage is sent with the event
    // TODO(terry): Needed to go from minimized to selected zoomed out view.
    //getStage().getStudioProfilers().getIdeServices().getFeatureTracker().trackSelectMonitor();
    monitor.expand();
  }

  @Override
  public JComponent getToolbar() {
    // TODO(terry): What should I return here?
    return new JPanel();
  }

  @Override
  public boolean needsProcessSelection() {
    return true;
  }

  private DefaultMutableTreeNode addNode(DefaultMutableTreeNode parent, String fieldName, String value) {
    final DefaultMutableTreeNode node = new DefaultMutableTreeNode(fieldName + value);

    SwingUtilities.invokeLater(() -> {
      parent.insert(node, parent.getChildCount());

      DefaultTreeModel model = (DefaultTreeModel)instanceObjects.getModel();
      model.reload(node);
      instanceObjects.getAccessibleContext().getAccessibleSelection().clearAccessibleSelection();
    });

    return node;
  }

  // Node Place holder for an object that we have not yet ask the VM to interrogated viewing its values.
  private DefaultMutableTreeNode addPlaceHodlerNode(DefaultMutableTreeNode parent, String objectRefName) {
    final DefaultMutableTreeNode node = new DefaultMutableTreeNode(objectRefName);

    SwingUtilities.invokeLater(() -> {
      parent.insert(node, parent.getChildCount());
      DefaultTreeModel model = (DefaultTreeModel)instanceObjects.getModel();
      model.reload(node);
    });

    return node;
  }

  void getObject(DefaultMutableTreeNode parent, String objectRef) {
    // Remove our place holder node.
    parent.remove(0);

    // Interrogate the values of the object.
    AsyncUtils.whenCompleteUiThread(vmGetObject(objectRef), (JsonObject response, Throwable exception) -> {
      Stack<String> objectStack = new Stack<String>();

      if (exception instanceof RuntimeException && exception.getMessage().startsWith("org.dartlang.vm.service.element.Sentinel@")) {
        // Object is now a sentinel signal that change.
        Memory.InstanceNode userNode = (Memory.InstanceNode)(parent.getUserObject());
        String userNodeName = userNode.getObjectRef();
        if (!userNodeName.endsWith(" [Sentinel]")) {
          userNode.setObjectRef(userNodeName + " [Sentinel]");
          SwingUtilities.invokeLater(() -> {
            memorySnapshot._myInstancesTreeModel.reload(parent);
          });
        }
        return;
      }

      Instance instance = new Instance(response);

      ElementList<BoundField> fields = instance.getFields();
      if (!fields.isEmpty()) {
        final Iterator<BoundField> iter = fields.iterator();
        ;
        while (iter.hasNext()) {
          BoundField field = iter.next();
          String fieldName = field.getDecl().getName();
          InstanceRef valueRef = field.getValue();
          InstanceKind valueKind = valueRef.getKind();

          switch (valueKind) {
            case BoundedType:
              // TODO(terry): Not sure what this is?
              addNode(parent, fieldName, " = [BoundType]");
              break;

            case Closure:
              // TODO(terry): Should we should the function (or at least be able to navigate to the function)?
              addNode(parent, fieldName, " = [Closure]");
              break;

            // Primitive Dart Types display raw value
            case Bool:
            case Double:
            case Float32List:
            case Float32x4:
            case Float32x4List:
            case Float64List:
            case Float64x2:
            case Float64x2List:
            case Int:
            case Int16List:
            case Int32List:
            case Int32x4:
            case Int32x4List:
            case Int64List:
            case Int8List:
            case Null:
            case String:
            case Uint16List:
            case Uint32List:
            case Uint64List:
            case Uint8ClampedList:
            case Uint8List:
              try {
                final String fieldValue = valueRef.getValueAsString();
                addNode(parent, fieldName, " = " + fieldValue);
              }
              catch (Exception e) {
                LOG.error("Error getting value " + valueRef.getKind(), e);
              }
              break;

            case List:
            case Map:
              // TODO(terry): Should show the Map/List as an object to be drilled into.
              addNode(parent, fieldName, " = [List/Map]");
              break;

            case MirrorReference:
              // TODO(terry): Not sure what to show other than its a mirror.
              addNode(parent, fieldName, " = [Mirror]");
              break;

            // A general instance of the Dart class Object.
            case PlainInstance:
              // Pointing to a nested class.
              if (valueRef == null) {
                // TODO(terry): This shouldn't happen.
                LOG.error("ValueRef is NULL");
              }
              final String nestedObjectRef = valueRef.getId();    // Pull the object/Class we're pointing too.
              final DefaultMutableTreeNode node = addNode(parent, fieldName, " [" + nestedObjectRef + "]");

              // Add a placeholder for this object being interogated iff the user clicks on the expand then we'll
              // call the VM to drill into the object.
              addPlaceHodlerNode(node, nestedObjectRef);
              break;

            case RegExp:
            case StackTrace:
            case Type:
            case TypeParameter:
            case TypeRef:
            case WeakProperty:
              // TODO(terry): Should we show something other than special?
              addNode(parent, fieldName, " = [SPECIAL]");
              break;

            case Unknown:
              addNode(parent, fieldName, " = [UNKNOWN]");
              break;
          }
        }

        SwingUtilities.invokeLater(() -> {
          memorySnapshot._myInstancesTreeModel.reload(parent);
        });
      }
    });
  }
}
