/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.utils;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorColors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class UIUtils {

  @Nullable
  public static JComponent getComponentOfActionEvent(@NotNull AnActionEvent e) {
    final Presentation presentation = e.getPresentation();
    JComponent component = (JComponent)presentation.getClientProperty("button");
    if (component == null && e.getInputEvent().getSource() instanceof JComponent) {
      component = (JComponent)e.getInputEvent().getSource();
    }
    return component;
  }

  /**
   * All editor notifications in the Flutter plugin should get and set the background color from this method, which will ensure if any are
   * changed, they are all changed.
   */
  @NotNull
  public static ColorKey getEditorNotificationBackgroundColor() {
    return EditorColors.GUTTER_BACKGROUND;
  }
}
