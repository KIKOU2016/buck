/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.core.rules.actions.lib.args;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.google.devtools.build.lib.actions.CommandLineItem;

/** Helper methods to convert / validate objects that are command line arguments for actions */
public class CommandLineArgStringifier {
  private CommandLineArgStringifier() {}

  /**
   * @return the string representation of an argument to pass to a command line application in an
   *     action
   */
  public static String asString(ArtifactFilesystem filesystem, Object object)
      throws CommandLineArgException {
    if (object instanceof String) {
      return (String) object;
    } else if (object instanceof Integer) {
      return object.toString();
    } else if (object instanceof CommandLineItem) {
      return CommandLineItem.expandToCommandLine(object);
    } else if (object instanceof Artifact) {
      return filesystem.stringifyForCommandLine((Artifact) object);
    } else {
      throw new CommandLineArgException(object);
    }
  }
}
