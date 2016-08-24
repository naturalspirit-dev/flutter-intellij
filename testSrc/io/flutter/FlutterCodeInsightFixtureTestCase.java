/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter;

import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase;
import com.jetbrains.lang.dart.util.DartTestUtils;
import io.flutter.sdk.FlutterSdk;
import io.flutter.util.FlutterTestUtils;

abstract public class FlutterCodeInsightFixtureTestCase extends LightPlatformCodeInsightFixtureTestCase {
  protected void setUp() throws Exception {
    super.setUp();
    FlutterTestUtils.configureFlutterSdk(myModule, getTestRootDisposable(), true);
    FlutterSdk sdk = FlutterSdk.getFlutterSdk(myModule.getProject());
    assert(sdk != null);
    String path = sdk.getHomePath();
    String dartSdkPath = path + "/bin/cache/dart-sdk";
    String systemDartSdk = System.getProperty("dart.sdk");
    try {
      System.setProperty("dart.sdk", dartSdkPath);
      DartTestUtils.configureDartSdk(myModule, getTestRootDisposable(), true);
    } finally {
      System.setProperty("dart.sdk", systemDartSdk);
    }
  }

  @Override
  protected String getTestDataPath() {
    return FlutterTestUtils.BASE_TEST_DATA_PATH + "/" + getBasePath();
  }
}
