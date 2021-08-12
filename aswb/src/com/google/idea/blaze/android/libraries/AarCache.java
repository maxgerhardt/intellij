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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.android.SdkConstants;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.prefetch.FetchExecutor;
import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Nullable;

/** Local cache of the aars referenced by the project. */
public class AarCache {
  private static final Logger logger = Logger.getInstance(AarCache.class);
  /** The state of the cache as of the last call to {@link #readFileState}. */
  private volatile ImmutableMap<String, File> cacheState = ImmutableMap.of();

  public static final String STAMP_FILE_NAME = "aar.timestamp";

  private final File cacheDir;

  public AarCache(File cacheDir) {
    this.cacheDir = cacheDir;
  }

  public File getCacheDir() {
    return cacheDir;
  }

  public boolean createCacheDir() {
    FileOperationProvider fileOpProvider = FileOperationProvider.getInstance();
    // Ensure the cache dir exists
    if (!fileOpProvider.exists(cacheDir)) {
      if (!fileOpProvider.mkdirs(cacheDir)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns a map of cache keys for the currently-cached files, along with a representative file
   * used for timestamp-based diffing.
   *
   * <p>We use a stamp file instead of the directory itself to stash the timestamp. Directory
   * timestamps are bit more brittle and can change whenever an operation is done to a child of the
   * directory.
   *
   * <p>Also sets the in-memory @link #cacheState}.
   */
  public ImmutableMap<String, File> readFileState() {
    FileOperationProvider ops = FileOperationProvider.getInstance();
    // Go through all of the aar directories, and get the stamp file.
    File[] unpackedAarDirectories = ops.listFiles(cacheDir);
    if (unpackedAarDirectories == null) {
      return ImmutableMap.of();
    }
    ImmutableMap<String, File> cachedFiles =
        Arrays.stream(unpackedAarDirectories)
            .collect(toImmutableMap(File::getName, dir -> new File(dir, STAMP_FILE_NAME)));
    cacheState = cachedFiles;
    return cachedFiles;
  }

  public Collection<ListenableFuture<?>> removeMissingFiles(Collection<String> aarsToKeep) {
    ImmutableSet<String> cacheKeys = this.cacheState.keySet();
    ImmutableSet<String> removedKeys =
        cacheKeys.stream()
            // aarsToKeep only contains files that end with .aar but cache files may not
            // we only need to care about *.aar directories here. Other directories will be
            // taken cared its creator.
            .filter(file -> !aarsToKeep.contains(file) && file.endsWith(SdkConstants.DOT_AAR))
            .collect(toImmutableSet());
    return deleteCacheEntries(removedKeys);
  }

  private ImmutableCollection<ListenableFuture<?>> deleteCacheEntries(Collection<String> subDirs) {
    FileOperationProvider ops = FileOperationProvider.getInstance();
    return subDirs.stream()
        .map(
            subDir ->
                FetchExecutor.EXECUTOR.submit(
                    () -> {
                      try {
                        ops.deleteRecursively(new File(cacheDir, subDir), true);
                      } catch (IOException e) {
                        logger.warn(e);
                      }
                    }))
        .collect(toImmutableList());
  }

  public void clearCache() {
    FileOperationProvider fileOperationProvider = FileOperationProvider.getInstance();
    if (fileOperationProvider.exists(cacheDir)) {
      try {
        fileOperationProvider.deleteRecursively(cacheDir, true);
      } catch (IOException e) {
        logger.warn("Failed to clear unpacked AAR directory: " + cacheDir, e);
      }
    }
    cacheState = ImmutableMap.of();
  }

  @Nullable
  public File getCachedAarDir(String aarDirName) {
    ImmutableMap<String, File> cacheState = this.cacheState;
    if (cacheState.isEmpty()) {
      logger.warn("Cache state is empty");
      return null;
    }
    return new File(cacheDir, aarDirName);
  }

  public ImmutableSet<String> getCachedKeys() {
    return cacheState.keySet();
  }
}
