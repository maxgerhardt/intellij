/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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

import static com.android.SdkConstants.FN_LINT_JAR;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.android.sync.model.AarLibrary;
import com.google.idea.blaze.base.async.FutureUtil;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact.LocalFileArtifact;
import com.google.idea.blaze.base.command.buildresult.RemoteOutputArtifact;
import com.google.idea.blaze.base.filecache.FileCache;
import com.google.idea.blaze.base.filecache.FileCacheDiffer;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.model.BlazeLibrary;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.RemoteOutputArtifacts;
import com.google.idea.blaze.base.prefetch.FetchExecutor;
import com.google.idea.blaze.base.prefetch.RemoteArtifactPrefetcher;
import com.google.idea.blaze.base.projectview.ProjectViewManager;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.scope.scopes.TimingScope.EventType;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager;
import com.google.idea.blaze.base.sync.SyncMode;
import com.google.idea.blaze.base.sync.aspects.BlazeBuildOutputs;
import com.google.idea.blaze.base.sync.data.BlazeDataStorage;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.libraries.BlazeLibraryCollector;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Local copy of unzipped AARs that are part of a project's libraries. Updated whenever the original
 * AAR is changed. Unpacked AARs are directories with many files. {@see
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
 *   <li>jars: we use the merged output jar from Bazel instead of taking jars from the AAR. It
 *       should be placed in a jars/ folder adjacent to the res/ folder. See {@link
 *       org.jetbrains.android.uipreview.ModuleClassLoader}, for that possible assumption.
 *   <li>The IDE may want the AndroidManifest.xml as well.
 * </ul>
 */
public class UnpackedAars {
  private static final Logger logger = Logger.getInstance(UnpackedAars.class);

  private final Project project;
  private final File cacheDir;

  /** The state of the cache as of the last call to {@link #readFileState}. */
  private volatile ImmutableMap<String, File> cacheState = ImmutableMap.of();

  public static UnpackedAars getInstance(Project project) {
    return ServiceManager.getService(project, UnpackedAars.class);
  }

  public UnpackedAars(Project project) {
    BlazeImportSettings importSettings =
        BlazeImportSettingsManager.getInstance(project).getImportSettings();
    this.project = project;
    this.cacheDir = getCacheDir(importSettings);
  }

  @VisibleForTesting
  public File getCacheDir() {
    return this.cacheDir;
  }

  private static File getCacheDir(BlazeImportSettings importSettings) {
    return new File(BlazeDataStorage.getProjectDataDir(importSettings), "aar_libraries");
  }

  void onSync(
      BlazeContext context,
      ProjectViewSet projectViewSet,
      BlazeProjectData projectData,
      @Nullable BlazeProjectData oldProjectData,
      SyncMode syncMode) {
    boolean fullRefresh = syncMode == SyncMode.FULL;
    if (fullRefresh) {
      clearCache();
    }

    // TODO(brendandouglas): add a mechanism for removing missing files for partial syncs
    boolean removeMissingFiles = syncMode == SyncMode.INCREMENTAL;
    refresh(
        context,
        projectViewSet,
        projectData,
        RemoteOutputArtifacts.fromProjectData(oldProjectData),
        removeMissingFiles);
  }

  private void refresh(
      BlazeContext context,
      ProjectViewSet viewSet,
      BlazeProjectData projectData,
      RemoteOutputArtifacts previousOutputs,
      boolean removeMissingFiles) {
    FileOperationProvider fileOpProvider = FileOperationProvider.getInstance();

    // Ensure the cache dir exists
    if (!fileOpProvider.exists(cacheDir)) {
      if (!fileOpProvider.mkdirs(cacheDir)) {
        logger.warn("Could not create unpacked AAR directory: " + cacheDir);
        return;
      }
    }

    ImmutableMap<String, File> cacheFiles = readFileState();
    ImmutableMap<String, AarLibraryContents> projectState =
        getArtifactsToCache(viewSet, projectData);
    ImmutableMap<String, BlazeArtifact> aarOutputs =
        projectState.entrySet().stream()
            .collect(toImmutableMap(Map.Entry::getKey, e -> e.getValue().aar()));
    try {

      Set<String> updatedKeys =
          FileCacheDiffer.findUpdatedOutputs(aarOutputs, cacheFiles, previousOutputs).keySet();
      Set<BlazeArtifact> artifactsToDownload = new HashSet<>();

      for (String key : updatedKeys) {
        artifactsToDownload.add(projectState.get(key).aar());
        BlazeArtifact jar = projectState.get(key).jar();
        // jar file is introduced as a separate artifact (not jar in aar) which asks to download
        // separately. Only update jar when we decide that aar need to be updated.
        if (jar != null) {
          artifactsToDownload.add(jar);
        }
      }

      Set<String> removedKeys = new HashSet<>();
      if (removeMissingFiles) {
        removedKeys =
            cacheFiles.keySet().stream()
                .filter(file -> !projectState.containsKey(file))
                .collect(toImmutableSet());
      }

      // Prefetch all libraries to local before reading and copying content
      ListenableFuture<?> downloadArtifactsFuture =
          RemoteArtifactPrefetcher.getInstance()
              .downloadArtifacts(
                  /* projectName= */ project.getName(),
                  /* outputArtifacts= */ BlazeArtifact.getRemoteArtifacts(artifactsToDownload));

      FutureUtil.waitForFuture(context, downloadArtifactsFuture)
          .timed("FetchAars", EventType.Prefetching)
          .withProgressMessage("Fetching aar files...")
          .run();

      // update cache files, and remove files if required
      List<ListenableFuture<?>> futures = new ArrayList<>(copyLocally(projectState, updatedKeys));
      if (removeMissingFiles) {
        futures.addAll(deleteCacheEntries(removedKeys));
      }

      Futures.allAsList(futures).get();
      if (!updatedKeys.isEmpty()) {
        context.output(PrintOutput.log(String.format("Copied %d AARs", updatedKeys.size())));
      }
      if (!removedKeys.isEmpty()) {
        context.output(PrintOutput.log(String.format("Removed %d AARs", removedKeys.size())));
      }

    } catch (InterruptedException e) {
      context.setCancelled();
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      logger.warn("Unpacked AAR synchronization didn't complete", e);
    } finally {
      // update the in-memory record of which files are cached
      readFileState();
    }
  }

  /** Returns the merged jar derived from an AAR, in the unpacked AAR directory. */
  @Nullable
  public File getClassJar(ArtifactLocationDecoder decoder, AarLibrary library) {
    if (library.libraryArtifact == null) {
      return null;
    }
    ImmutableMap<String, File> cacheState = this.cacheState;
    BlazeArtifact jar = decoder.resolveOutput(library.libraryArtifact.jarForIntellijLibrary());
    if (cacheState.isEmpty()) {
      logger.warn("Cache state is empty");
      return getFallbackFile(jar);
    }

    BlazeArtifact aar = decoder.resolveOutput(library.aarArtifact);
    File aarDir = getAarDir(decoder, library);
    // check if it was actually cached
    if (aarDir == null) {
      // if artifact is RemoteOutputArtifact, we can only find it in aar cache. So it's expected
      // that the aar directory has been cached. It's unexpected when it runs into this case and
      // cannot find any fallback file.
      if (jar instanceof RemoteOutputArtifact) {
        logger.warn(
            String.format(
                "Fail to look up %s from cache state for library [aarArtifact = %s, jar = %s]",
                aarDir, aar, jar));
        logger.debug("Cache state contains the following keys: " + cacheState.keySet());
      }
      return getFallbackFile(jar);
    }
    return UnpackedAarUtils.getJarFile(aarDir);
  }

  /** Returns the res/ directory corresponding to an unpacked AAR file. */
  @Nullable
  public File getResourceDirectory(ArtifactLocationDecoder decoder, AarLibrary library) {
    File aarDir = getAarDir(decoder, library);
    return aarDir == null ? aarDir : UnpackedAarUtils.getResDir(aarDir);
  }

  @Nullable
  public File getAarDir(String cacheKey) {
    ImmutableMap<String, File> cacheState = this.cacheState;
    if (!cacheState.containsKey(cacheKey)) {
      return null;
    }
    return aarDirForKey(cacheKey);
  }

  @Nullable
  public File getAarDir(ArtifactLocationDecoder decoder, AarLibrary library) {
    BlazeArtifact artifact = decoder.resolveOutput(library.aarArtifact);
    String aarDirName = UnpackedAarUtils.getAarDirName(artifact);
    return getAarDir(aarDirName);
  }

  private File aarDirForKey(String key) {
    return new File(cacheDir, key);
  }

  /** The file to return if there's no locally cached version. */
  private static File getFallbackFile(BlazeArtifact output) {
    if (output instanceof RemoteOutputArtifact) {
      // TODO(brendandouglas): copy locally on the fly?
      throw new RuntimeException("The AAR cache must be enabled when syncing remotely");
    }
    return ((LocalFileArtifact) output).getFile();
  }

  private void clearCache() {
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

  static class FileCacheAdapter implements FileCache {
    @Override
    public String getName() {
      return "Unpacked AAR libraries";
    }

    @Override
    public void onSync(
        Project project,
        BlazeContext context,
        ProjectViewSet projectViewSet,
        BlazeProjectData projectData,
        @Nullable BlazeProjectData oldProjectData,
        SyncMode syncMode) {
      getInstance(project).onSync(context, projectViewSet, projectData, oldProjectData, syncMode);
    }

    @Override
    public void refreshFiles(
        Project project, BlazeContext context, BlazeBuildOutputs buildOutputs) {
      ProjectViewSet viewSet = ProjectViewManager.getInstance(project).getProjectViewSet();
      BlazeProjectData projectData =
          BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
      if (viewSet == null || projectData == null || !projectData.getRemoteOutputs().isEmpty()) {
        // if we have remote artifacts, only refresh during sync
        return;
      }
      getInstance(project)
          .refresh(
              context,
              viewSet,
              projectData,
              projectData.getRemoteOutputs(),
              /* removeMissingFiles= */ false);
    }

    @Override
    public void initialize(Project project) {
      getInstance(project).readFileState();
    }
  }

  /**
   * Returns a map from cache key to {@link AarLibraryContents}, for all the artifacts which should
   * be cached.
   */
  private static ImmutableMap<String, AarLibraryContents> getArtifactsToCache(
      ProjectViewSet projectViewSet, BlazeProjectData projectData) {
    Collection<BlazeLibrary> libraries =
        BlazeLibraryCollector.getLibraries(projectViewSet, projectData);
    List<AarLibrary> aarLibraries =
        libraries.stream()
            .filter(library -> library instanceof AarLibrary)
            .map(library -> (AarLibrary) library)
            .collect(Collectors.toList());

    ArtifactLocationDecoder decoder = projectData.getArtifactLocationDecoder();
    Map<String, AarLibraryContents> outputs = new HashMap<>();
    for (AarLibrary library : aarLibraries) {
      BlazeArtifact aar = decoder.resolveOutput(library.aarArtifact);
      BlazeArtifact jar =
          library.libraryArtifact != null
              ? decoder.resolveOutput(library.libraryArtifact.jarForIntellijLibrary())
              : null;
      outputs.put(UnpackedAarUtils.getAarDirName(aar), AarLibraryContents.create(aar, jar));
    }
    return ImmutableMap.copyOf(outputs);
  }

  private static final String STAMP_FILE_NAME = "aar.timestamp";

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
  private ImmutableMap<String, File> readFileState() {
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

  private Collection<ListenableFuture<?>> copyLocally(
      ImmutableMap<String, AarLibraryContents> toCache, Set<String> updatedKeys) {
    FileOperationProvider ops = FileOperationProvider.getInstance();
    List<ListenableFuture<?>> futures = new ArrayList<>();
    updatedKeys.forEach(
        key ->
            futures.add(FetchExecutor.EXECUTOR.submit(() -> copyLocally(ops, toCache.get(key)))));
    return futures;
  }

  private void copyLocally(FileOperationProvider ops, AarLibraryContents aarAndJar) {
    String cacheKey = UnpackedAarUtils.getAarDirName(aarAndJar.aar());
    File aarDir = aarDirForKey(cacheKey);
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
          // Skip jars except lint.jar. We will copy jar in AarLibraryContents instead.
          // That could give us freedom in the future to use an ijar or header jar instead,
          // which is more lightweight. But it's not applied to lint.jar
          (dir, name) -> name.equals(FN_LINT_JAR) || !name.endsWith(".jar"));

      createStampFile(ops, aarDir, aarAndJar.aar());

      // copy merged jar
      if (aarAndJar.jar() != null) {
        try (InputStream stream = aarAndJar.jar().getInputStream()) {
          Path destination = Paths.get(UnpackedAarUtils.getJarFile(aarDir).getPath());
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

  private Collection<ListenableFuture<?>> deleteCacheEntries(Collection<String> cacheKeys) {
    FileOperationProvider ops = FileOperationProvider.getInstance();
    return cacheKeys.stream()
        .map(
            key ->
                FetchExecutor.EXECUTOR.submit(
                    () -> {
                      try {
                        ops.deleteRecursively(aarDirForKey(key), true);
                      } catch (IOException e) {
                        logger.warn(e);
                      }
                    }))
        .collect(toImmutableList());
  }

  /** Returns a locally-accessible file mirroring the contents of this {@link BlazeArtifact}. */
  private static File getOrCreateLocalFile(BlazeArtifact artifact) throws IOException {
    if (artifact instanceof LocalFileArtifact) {
      return ((LocalFileArtifact) artifact).getFile();
    }
    File tmpFile =
        FileUtil.createTempFile(
            "local-aar-file",
            Integer.toHexString(UnpackedAarUtils.getArtifactKey(artifact).hashCode()),
            /* deleteOnExit= */ true);
    try (InputStream stream = artifact.getInputStream()) {
      Files.copy(stream, Paths.get(tmpFile.getPath()), StandardCopyOption.REPLACE_EXISTING);
      return tmpFile;
    }
  }
}
