/*
 * Copyright 2021 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.idea.blaze.android.libraries;

import com.google.auto.value.AutoValue;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import javax.annotation.Nullable;

/* Information used to cache and unpack aars*/
@AutoValue
abstract class AarAndJar {
  static AarAndJar create(BlazeArtifact aar, @Nullable BlazeArtifact jar, String libraryKey) {
    return new AutoValue_AarAndJar(aar, jar, libraryKey);
  }

  abstract BlazeArtifact aar();

  @Nullable
  abstract BlazeArtifact jar();

  abstract String libraryKey();
}
