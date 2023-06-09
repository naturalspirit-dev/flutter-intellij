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

// This file is generated by the script: pkg/vm_service/tool/generate.dart in dart-lang/sdk.

import com.google.gson.JsonObject;

/**
 * A {@link Parameter} is a representation of a function parameter.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class Parameter extends Element {

  public Parameter(JsonObject json) {
    super(json);
  }

  /**
   * Represents whether or not this parameter is fixed or optional.
   */
  public boolean getFixed() {
    return getAsBoolean("fixed");
  }

  /**
   * The name of a named optional parameter.
   *
   * Can return <code>null</code>.
   */
  public String getName() {
    return getAsString("name");
  }

  /**
   * The type of the parameter.
   */
  public InstanceRef getParameterType() {
    return new InstanceRef((JsonObject) json.get("parameterType"));
  }

  /**
   * Whether or not this named optional parameter is marked as required.
   *
   * Can return <code>null</code>.
   */
  public boolean getRequired() {
    return getAsBoolean("required");
  }
}
