/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.bazel;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import io.flutter.FlutterUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.PatternSyntaxException;

/**
 * An in-memory snapshot of the flutter.json file from a Bazel workspace.
 */
public class PluginConfig {
  private final @NotNull Fields fields;

  private PluginConfig(@NotNull Fields fields) {
    this.fields = fields;
  }

  @Nullable
  String getDaemonScript() {
    return fields.daemonScript;
  }

  @Nullable
  String getDoctorScript() {
    return fields.doctorScript;
  }

  @Nullable
  String getLaunchScript() {
    return fields.launchScript;
  }

  @Nullable
  String getTestScript() {
    return fields.testScript;
  }

  @Nullable
  String getRunScript() {
    return fields.runScript;
  }

  @Nullable
  String getSdkHome() {
    return fields.sdkHome;
  }

  @Nullable
  String getVersionFile() {
    return fields.versionFile;
  }

  @Nullable
  String getDevtoolsScript() {
    return fields.devtoolsScript;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof PluginConfig)) return false;
    final PluginConfig other = (PluginConfig)obj;
    return Objects.equal(fields, other.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(fields);
  }

  /**
   * Reads plugin configuration from a file, if possible.
   */
  @Nullable
  public static PluginConfig load(@NotNull VirtualFile file) {
    final Computable<PluginConfig> readAction = () -> {
      try (
        // Create the input stream in a try-with-resources statement. This will automatically close the stream
        // in an implicit finally section; this addresses a file handle leak issue we had on MacOS.
        final InputStreamReader input = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)
      ) {
        final Fields fields = GSON.fromJson(input, Fields.class);
        return new PluginConfig(fields);
      }
      catch (FileNotFoundException e) {
        LOG.info("Flutter plugin didn't find flutter.json at " + file.getPath());
        return null;
      }
      catch (IOException e) {
        FlutterUtils.warn(LOG, "Flutter plugin failed to load config file at " + file.getPath(), e);
        return null;
      }
      catch (JsonSyntaxException e) {
        FlutterUtils.warn(LOG, "Flutter plugin failed to parse JSON in config file at " + file.getPath());
        return null;
      }
      catch (PatternSyntaxException e) {
        FlutterUtils
          .warn(LOG, "Flutter plugin failed to parse directory pattern (" + e.getPattern() + ") in config file at " + file.getPath());
        return null;
      }
    };

    return ApplicationManager.getApplication().runReadAction(readAction);
  }

  @VisibleForTesting
  public static PluginConfig forTest(
    @Nullable String daemonScript,
    @Nullable String doctorScript,
    @Nullable String launchScript,
    @Nullable String testScript,
    @Nullable String runScript,
    @Nullable String sdkHome,
    @Nullable String versionFile,
    @Nullable String devtoolsScript
  ) {
    final Fields fields = new Fields(
      daemonScript,
      doctorScript,
      launchScript,
      testScript,
      runScript,
      sdkHome,
      versionFile,
      devtoolsScript
    );
    return new PluginConfig(fields);
  }

  /**
   * The JSON fields in a PluginConfig, as loaded from disk.
   */
  private static class Fields {
    /**
     * The script to run to start 'flutter daemon'.
     */
    @SerializedName("daemonScript")
    private String daemonScript;

    /**
     * The script to run to start 'flutter doctor'.
     */
    @SerializedName("doctorScript")
    private String doctorScript;

    /**
     * The script to run to start 'bazel'
     */
    @SerializedName("launchScript")
    private String launchScript;

    /**
     * The script to run to start 'flutter test'
     */
    @SerializedName("testScript")
    private String testScript;

    /**
     * The script to run to start 'flutter run'
     */
    @SerializedName("runScript")
    private String runScript;

    /**
     * The directory containing the SDK tools.
     */
    @SerializedName("sdkHome")
    private String sdkHome;

    /**
     * The file containing the Flutter version.
     */
    @SerializedName("versionFile")
    private String versionFile;

    /**
     * The bazel command to run to launch DevTools.
     */
    @SerializedName("devtoolsScript")
    private String devtoolsScript;

    Fields() {
    }

    /**
     * Convenience constructor that takes all parameters.
     */
    Fields(String daemonScript,
           String doctorScript,
           String launchScript,
           String testScript,
           String runScript,
           String sdkHome,
           String versionFile,
           String devtoolsScript) {
      this.daemonScript = daemonScript;
      this.doctorScript = doctorScript;
      this.launchScript = launchScript;
      this.testScript = testScript;
      this.runScript = runScript;
      this.sdkHome = sdkHome;
      this.versionFile = versionFile;
      this.devtoolsScript = devtoolsScript;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Fields)) return false;
      final Fields other = (Fields)obj;
      return Objects.equal(daemonScript, other.daemonScript)
             && Objects.equal(doctorScript, other.doctorScript)
             && Objects.equal(launchScript, other.launchScript)
             && Objects.equal(testScript, other.testScript)
             && Objects.equal(runScript, other.runScript)
             && Objects.equal(sdkHome, other.sdkHome)
             && Objects.equal(versionFile, other.versionFile)
             && Objects.equal(devtoolsScript, other.devtoolsScript);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(daemonScript, doctorScript, launchScript, testScript, runScript, sdkHome, versionFile, devtoolsScript);
    }
  }

  private static final Gson GSON = new Gson();
  private static final Logger LOG = Logger.getInstance(PluginConfig.class);
}
