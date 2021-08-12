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
import static com.google.idea.blaze.android.libraries.AarCache.STAMP_FILE_NAME;

import com.android.SdkConstants;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.android.sync.model.AarLibrary;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact.LocalFileArtifact;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.prefetch.FetchExecutor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.io.ZipUtil;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Unzip prefetched aars to local cache directories and merge aars when necessary. AARs are
 * directories with many files. {@see
 * https://developer.android.com/studio/projects/android-library.html#aar-contents}, for a subset of
 * the contents (documentation may be outdated).
 *
 * <p>The IDE wants at least the following:
 *
 * <ul>
 *   <li>the res/ folder
 *   <li>the R.txt file adjacent to the res/ folder
 *   <li>See {@link com.android.tools.idea.resources.aar.AarSourceResourceRepository} for the
 *       dependency on R.txt.
 *   <li>jars: we use the merged output jar from Bazel instead of taking jars from the AAR. It gives
 *       us freedom in the future to use an ijar or header jar instead, which is more lightweight.
 *       It should be placed in a jars/ folder adjacent to the res/ folder. See {@link
 *       org.jetbrains.android.uipreview.ModuleClassLoader}, for that possible assumption.
 *   <li>The IDE may want the AndroidManifest.xml as well.
 * </ul>
 */
public final class Unpacker {
  private static final Logger logger = Logger.getInstance(Unpacker.class);
  private static final String DOT_MERGED_AAR = ".mergedaar";

  public static String getAarDirName(AarLibrary aarLibrary) {
    String key = aarLibrary.key.toString();
    return AarDirectoryNameUtils.generateAarDirectoryName(
            /* name= */ key, /* hashCode= */ key.hashCode())
        + DOT_MERGED_AAR;
  }

  /** Updated prefetched aars to destDir and merge aars directories that shares same library key. */
  public static void unpack(
      ImmutableMap<String, AarAndJar> toCache, Set<String> updatedKeys, File destDir)
      throws ExecutionException, InterruptedException {
    unpackAarsToDir(toCache, updatedKeys, destDir);
    removeNonAarDirectories(destDir);
    mergeAars(toCache, destDir);
  }

  private static void unpackAarsToDir(
      ImmutableMap<String, AarAndJar> toCache, Set<String> updatedKeys, File destDir)
      throws ExecutionException, InterruptedException {
    FileOperationProvider ops = FileOperationProvider.getInstance();
    List<ListenableFuture<?>> futures = new ArrayList<>();
    updatedKeys.forEach(
        key ->
            futures.add(
                FetchExecutor.EXECUTOR.submit(
                    () -> unpackAarToDir(ops, toCache.get(key), destDir))));
    Futures.allAsList(futures).get();
  }

  /**
   * Each .aar file will be unpacked as <key_from_artifact_location>.aar directories in cache
   * directory. A timestamp file will be created to decide if updated is needed when a new .aar file
   * with same name is found next time.
   */
  private static void unpackAarToDir(FileOperationProvider ops, AarAndJar aarAndJar, File destDir) {
    String aarDirName = AarDirectoryNameUtils.getAarDirName(aarAndJar.aar());
    File aarDir = new File(destDir, aarDirName);
    try {
      if (ops.exists(aarDir)) {
        ops.deleteRecursively(aarDir, true);
      }
      ops.mkdirs(aarDir);
      // TODO(brendandouglas): decompress via ZipInputStream so we don't require a local file
      File toCopy = getOrCreateLocalFile(aarAndJar.aar());
      ZipUtil.extract(
          toCopy,
          aarDir,
          // Skip jars. The merged jar will be synchronized by JarTraits.
          (dir, name) -> !name.endsWith(".jar"));

      createStampFile(ops, aarDir, aarAndJar.aar());

      // copy merged jar
      if (aarAndJar.jar() != null) {
        try (InputStream stream = aarAndJar.jar().getInputStream()) {
          Path destination =
              Paths.get(AarDirectoryNameUtils.getJarFile(new File(destDir, aarDirName)).getPath());
          ops.mkdirs(destination.getParent().toFile());
          Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }

    } catch (IOException e) {
      logger.warn(String.format("Failed to extract AAR %s to %s", aarAndJar.aar(), aarDir), e);
    }
  }

  private static void createStampFile(
      FileOperationProvider fileOps, File aarDir, BlazeArtifact aar) {
    File stampFile = new File(aarDir, STAMP_FILE_NAME);
    try {
      stampFile.createNewFile();
      if (!(aar instanceof LocalFileArtifact)) {
        // no need to set the timestamp for remote artifacts
        return;
      }
      long sourceTime = fileOps.getFileModifiedTime(((LocalFileArtifact) aar).getFile());
      if (!fileOps.setFileModifiedTime(stampFile, sourceTime)) {
        logger.warn("Failed to set AAR cache timestamp for " + aar);
      }
    } catch (IOException e) {
      logger.warn("Failed to set AAR cache timestamp for " + aar, e);
    }
  }

  /** Returns a locally-accessible file mirroring the contents of this {@link BlazeArtifact}. */
  private static File getOrCreateLocalFile(BlazeArtifact artifact) throws IOException {
    if (artifact instanceof LocalFileArtifact) {
      return ((LocalFileArtifact) artifact).getFile();
    }
    File tmpFile =
        FileUtil.createTempFile(
            "local-aar-file",
            Integer.toHexString(AarDirectoryNameUtils.getArtifactKey(artifact).hashCode()),
            /* deleteOnExit= */ true);
    try (InputStream stream = artifact.getInputStream()) {
      Files.copy(stream, Paths.get(tmpFile.getPath()), StandardCopyOption.REPLACE_EXISTING);
      return tmpFile;
    }
  }

  /**
   * Remove any directories that not end with .aar file. These directories are created by us which
   * is not unpacked from .aar file. We will re-create them every time.
   */
  private static void removeNonAarDirectories(File cacheDir)
      throws ExecutionException, InterruptedException {
    FileOperationProvider ops = FileOperationProvider.getInstance();
    File[] files = ops.listFiles(cacheDir);
    if (files == null) {
      return;
    }

    Futures.allAsList(
            Arrays.stream(files)
                .filter(file -> !file.getName().endsWith(SdkConstants.DOT_AAR))
                .map(
                    dir ->
                        FetchExecutor.EXECUTOR.submit(
                            () -> {
                              try {
                                ops.deleteRecursively(dir, true);
                              } catch (IOException e) {
                                logger.warn(e);
                              }
                            }))
                .collect(toImmutableList()))
        .get();
  }

  /**
   * Aars with same library key will be merged into <reosurce package name>.mergedaar and keeps file
   * structure.
   */
  private static void mergeAars(ImmutableMap<String, AarAndJar> toMerge, File cacheDir)
      throws ExecutionException, InterruptedException {
    Stopwatch timer = Stopwatch.createStarted();
    Map<String, List<BlazeArtifact>> resourcePackageToAar = new HashMap<>();
    toMerge.values().stream()
        .forEach(
            aarAndJar ->
                resourcePackageToAar
                    .computeIfAbsent(aarAndJar.libraryKey(), key -> new ArrayList<>())
                    .add(aarAndJar.aar()));

    List<ListenableFuture<?>> futures = new ArrayList<>();
    for (Map.Entry<String, List<BlazeArtifact>> entry : resourcePackageToAar.entrySet()) {
      File destDir =
          new File(
              cacheDir,
              AarDirectoryNameUtils.generateAarDirectoryName(
                      /* name= */ entry.getKey(), /* hashCode= */ entry.getKey().hashCode())
                  + DOT_MERGED_AAR);
      futures.add(
          FetchExecutor.EXECUTOR.submit(() -> copyFiles(entry.getValue(), cacheDir, destDir)));
    }
    Futures.allAsList(futures).get();
    logger.info("Merged " + toMerge.size() + "aars in " + timer.elapsed().getSeconds() + " sec.");
  }

  /**
   * Copy resources files from different aars to dest directory while keeping same directory
   * structure.
   */
  private static void copyFiles(List<BlazeArtifact> aars, File cacheDir, File dest) {
    FileOperationProvider ops = FileOperationProvider.getInstance();
    for (BlazeArtifact aar : aars) {
      String aarDir = AarDirectoryNameUtils.getAarDirName(aar);
      File srcDir = new File(cacheDir, aarDir);

      for (Path srcResourceFilePath : ops.listFilesRecursively(srcDir)) {
        // find the relative path to created in merged aar dir
        Path destResourceFilePath =
            dest.toPath().resolve(srcDir.toPath().relativize(srcResourceFilePath));
        try {
          Path parentDir = destResourceFilePath.getParent();
          ops.mkdirs(parentDir.toFile());
          if (Files.exists(destResourceFilePath)) {
            // destResourceFile may already exist if it's timestamp file or file with same content,
            // we can ignore them. Otherwise, we will rename it to <resource>-<src_aar_dir>.xml and
            // copy it to avoid replacing incorrectly.
            String destResourceFilename = destResourceFilePath.getFileName().toString();
            if (destResourceFilename.equals(STAMP_FILE_NAME)
                || isSameContent(destResourceFilePath, srcResourceFilePath)) {
              continue;
            }
            String name = FileUtil.getNameWithoutExtension(destResourceFilename);
            destResourceFilePath =
                parentDir.resolve(name + "-" + FileUtil.getNameWithoutExtension(aarDir) + ".xml");
          }
          ops.copy(srcResourceFilePath.toFile(), destResourceFilePath.toFile());
        } catch (IOException e) {
          logger.warn(
              "Fail to copy source file "
                  + srcResourceFilePath
                  + " to merged aar directory "
                  + destResourceFilePath,
              e);
        }
      }
    }
  }

  private static boolean isSameContent(Path file1, Path file2) throws IOException {
    final long size = Files.size(file1);
    if (size != Files.size(file2)) {
      return false;
    }

    if (size < 4096) {
      return Arrays.equals(Files.readAllBytes(file1), Files.readAllBytes(file2));
    }

    try (InputStream is1 = Files.newInputStream(file1);
        InputStream is2 = Files.newInputStream(file2)) {
      int data;
      while ((data = is1.read()) != -1) {
        if (data != is2.read()) {
          return false;
        }
      }
    }

    return true;
  }

  private Unpacker() {}
}
