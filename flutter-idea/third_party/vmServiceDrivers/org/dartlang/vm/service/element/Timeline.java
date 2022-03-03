/*
 * Copyright (c) 2015, the Dart project authors.
 *
 * Licensed under the Eclipse Public License v1.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.dartlang.vm.service.element;

// This is a generated file.

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@SuppressWarnings({"WeakerAccess", "unused"})
public class Timeline extends Response {

  public Timeline(JsonObject json) {
    super(json);
  }

  /**
   * The duration of time covered by the timeline.
   */
  public int getTimeExtentMicros() {
    return getAsInt("timeExtentMicros");
  }

  /**
   * The start of the period of time in which traceEvents were collected.
   */
  public int getTimeOriginMicros() {
    return getAsInt("timeOriginMicros");
  }

  /**
   * A list of timeline events. No order is guaranteed for these events; in particular, these
   * events may be unordered with respect to their timestamps.
   */
  public ElementList<TimelineEvent> getTraceEvents() {
    return new ElementList<TimelineEvent>(json.get("traceEvents").getAsJsonArray()) {
      @Override
      protected TimelineEvent basicGet(JsonArray array, int index) {
        return new TimelineEvent(array.get(index).getAsJsonObject());
      }
    };
  }
}
