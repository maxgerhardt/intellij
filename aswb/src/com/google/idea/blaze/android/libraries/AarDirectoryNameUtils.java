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

import com.android.SdkConstants;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import com.google.idea.blaze.base.command.buildresult.OutputArtifact;
import com.google.idea.blaze.base.command.buildresult.SourceArtifact;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.PathUtil;
import java.io.File;

/** Utility methods to generate aar directory or its sub-directories names */
public final class AarDirectoryNameUtils {

  public static String getArtifactKey(BlazeArtifact artifact) {
    if (artifact instanceof OutputArtifact) {
      return ((OutputArtifact) artifact).getKey();
    }
    if (artifact instanceof SourceArtifact) {
      return ((SourceArtifact) artifact).getFile().getPath();
    }
    throw new RuntimeException("Unhandled BlazeArtifact type: " + artifact.getClass());
  }

  public static String getAarDirName(BlazeArtifact aar) {
    String key = getArtifactKey(aar);
    String name = FileUtil.getNameWithoutExtension(PathUtil.getFileName(key));
    return generateAarDirectoryName(/* name= */ name, /* hashCode= */ key.hashCode())
        + SdkConstants.DOT_AAR;
  }

  public static String generateAarDirectoryName(String name, int hashCode) {
    return name + "_" + Integer.toHexString(hashCode);
  }

  public static File getJarFile(File aarDir) {
    File jarsDirectory = new File(aarDir, SdkConstants.FD_JARS);
    // At this point, we don't know the name of the original jar, but we must give the cache
    // file a name. Just use a name similar to what bazel currently uses, and that conveys
    // the origin of the jar (merged from classes.jar and libs/*.jar).
    return new File(jarsDirectory, "classes_and_libs_merged.jar");
  }

  public static File getResDir(File aarDir) {
    return new File(aarDir, SdkConstants.FD_RES);
  }

  private AarDirectoryNameUtils() {}
}
