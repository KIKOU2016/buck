/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractVersionedAliasDescription
    implements DescriptionWithTargetGraph<VersionedAliasDescriptionArg> {

  @Override
  public Class<VersionedAliasDescriptionArg> getConstructorArgType() {
    return VersionedAliasDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      VersionedAliasDescriptionArg args) {
    throw new IllegalStateException(
        String.format("%s: `versioned_alias()` rules cannot produce build rules", buildTarget));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractVersionedAliasDescriptionArg extends CommonDescriptionArg {
    ImmutableMap<Version, BuildTarget> getVersions();
  }
}
