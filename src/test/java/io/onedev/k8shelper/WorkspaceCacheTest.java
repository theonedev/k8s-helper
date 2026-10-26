package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.tuple.Pair;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;

class WorkspaceCacheTest {

    @TempDir
    Path workspace;

    private final TaskLogger logger = new TaskLogger() {
        @Override
        public void log(String message, String sessionId) {
        }
    };

    private static class Provisioner extends CacheProvisioner {
        int downloads;
        String uploaded;
        boolean failDownload;

        Provisioner(String path) {
            this(path, "key", UploadStrategy.UPLOAD_IF_CHANGED);
        }

        Provisioner(String path, String key, UploadStrategy strategy) {
            this(new CacheConfigFacade(key, null, List.of(new CacheEntryFacade(path, null)), strategy, null, null));
        }

        Provisioner(CacheConfigFacade config) {
            super(config);
        }

        @Override
        protected CacheAvailability download(String key, String checksum, String path, File pathDir) {
            downloads++;
            try {
                Files.writeString(pathDir.toPath().resolve("data"), "stored");
                if (failDownload)
                    throw new IllegalStateException("Interrupted restore");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return CacheAvailability.EXACT_MATCH;
        }

        @Override
        protected boolean upload(CacheConfigFacade config, String path, File pathDir, List<String> excludes) {
            try {
                uploaded = Files.readString(pathDir.toPath().resolve("data"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return true;
        }
    }

    @Test
    void interruptedRestoreIsRetried() {
        var initial = new Provisioner("cache");
        initial.failDownload = true;
        assertThrows(IllegalStateException.class, () -> initial.download(workspace.toFile(), logger, true));
        var retry = new Provisioner("cache");
        retry.download(workspace.toFile(), logger, true);
        assertEquals(1, retry.downloads);
    }

    @Test
    void reprovisionKeepsExactMatchAndChangeTracking() {
        for (var strategy : UploadStrategy.values()) {
            var path = strategy.name();
            var initial = new Provisioner(path, "key", strategy);
            initial.download(workspace.toFile(), logger, true);
            var resumed = new Provisioner(path, "key", strategy);
            resumed.download(workspace.toFile(), logger, true);
            resumed.upload(workspace.toFile(), logger);
            assertNull(resumed.uploaded, strategy.name());
        }
    }

    @Test
    void changedKeyRestoresIntoExistingPath() {
        var initial = new Provisioner("cache");
        initial.download(workspace.toFile(), logger, true);
        var changed = new Provisioner("cache", "new-key", UploadStrategy.UPLOAD_IF_CHANGED);
        changed.download(workspace.toFile(), logger, true);
        assertEquals(1, changed.downloads);
    }

    @Test
    void failedKeyChangeInvalidatesPreviousRestore() {
        var initial = new Provisioner("cache");
        initial.download(workspace.toFile(), logger, true);
        var changed = new Provisioner("cache", "new-key", UploadStrategy.UPLOAD_IF_CHANGED);
        changed.failDownload = true;
        assertThrows(IllegalStateException.class, () -> changed.download(workspace.toFile(), logger, true));
        var reverted = new Provisioner("cache");
        reverted.download(workspace.toFile(), logger, true);
        assertEquals(1, reverted.downloads);
    }

    @Test
    void changedAbsolutePathUsesDifferentStorage() throws Exception {
        var initial = new Provisioner("/first");
        initial.download(workspace.toFile(), logger, true);
        var firstData = initial.getPathDir(workspace.toFile(), "/first").toPath().resolve("data");
        Files.writeString(firstData, "local edits");
        var changed = new Provisioner("/second");
        assertNotEquals(initial.getPathDir(workspace.toFile(), "/first"), changed.getPathDir(workspace.toFile(), "/second"));
        changed.download(workspace.toFile(), logger, true);
        assertEquals(1, changed.downloads);
        assertEquals("local edits", Files.readString(firstData));
    }

    @Test
    void reorderedPathsReuseTheirContentsAndState() throws Exception {
        var initial = new Provisioner(new CacheConfigFacade("key", null,
                List.of(new CacheEntryFacade("/first", null), new CacheEntryFacade("/second", null)),
                UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH, null, null));
        initial.download(workspace.toFile(), logger, true);
        Files.writeString(initial.getPathDir(workspace.toFile(), "/first").toPath().resolve("data"), "first edits");
        Files.writeString(initial.getPathDir(workspace.toFile(), "/second").toPath().resolve("data"), "second edits");

        var resumed = new Provisioner(new CacheConfigFacade("key", null,
                List.of(new CacheEntryFacade("/second", null), new CacheEntryFacade("/first", null)),
                UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH, null, null));
        resumed.download(workspace.toFile(), logger, true);
        assertEquals(0, resumed.downloads);
        assertEquals("first edits", Files.readString(resumed.getPathDir(workspace.toFile(), "/first").toPath().resolve("data")));
        assertEquals("second edits", Files.readString(resumed.getPathDir(workspace.toFile(), "/second").toPath().resolve("data")));
        resumed.upload(workspace.toFile(), logger);
        assertNull(resumed.uploaded);
    }

    @Test
    void longAbsolutePathsUseBoundedNamesAndSeparateState() throws Exception {
        var path = "/" + "long-directory/".repeat(1000) + "cache";
        var provisioner = new Provisioner(path);
        assertTrue(provisioner.getDataPath(path).matches("cache/[0-9a-f]{64}/data"));
        provisioner.download(workspace.toFile(), logger, true);
        var data = provisioner.getPathDir(workspace.toFile(), path).toPath();
        assertTrue(Files.isRegularFile(data.resolve("data")));
        assertFalse(Files.exists(data.resolve("state")));
        var state = new Properties();
        try (var reader = Files.newBufferedReader(data.getParent().resolve("state"))) {
            state.load(reader);
        }
        assertEquals(Set.of("key", "exactMatch", "provisionDate"), state.stringPropertyNames());
        var changedKey = new Provisioner(path, "new-key", UploadStrategy.UPLOAD_IF_CHANGED);
        assertEquals(data.toFile(), changedKey.getPathDir(workspace.toFile(), path));
    }

    @Test
    void dockerMountsUseTheSamePathsAfterSerialization() {
        var provisioner = SerializationUtils.clone(new Provisioner(new CacheConfigFacade("key", null,
                List.of(new CacheEntryFacade("/absolute", null), new CacheEntryFacade("relative", null)),
                UploadStrategy.UPLOAD_IF_CHANGED, null, null)));
        var docker = new Commandline("docker");
        provisioner.mountVolumes(docker, workspace.toFile(), path -> path);
        assertEquals(List.of("-v", workspace.resolve(provisioner.getDataPath("/absolute")) + ":/absolute"), docker.args());
        assertEquals(workspace.resolve("work/relative").toFile(), provisioner.getPathDir(workspace.toFile(), "relative"));
    }

    @Test
    void cacheStorageAndStateRejectSymlinks() throws Exception {
        var outside = Files.createDirectory(workspace.resolve("outside"));
        var secret = Files.writeString(outside.resolve("secret"), "untouched");
        int index = 0;
        for (var path : List.of("relative", "/absolute")) {
            var provisioner = new Provisioner(path);
            var cachePath = Path.of(provisioner.getDataPath(path)).getParent();
            for (var link : List.of(Path.of("cache"), cachePath, cachePath.resolve("state"))) {
                var base = Files.createDirectory(workspace.resolve("symlink-" + index++));
                Files.createDirectories(base.resolve(link).getParent());
                Files.createSymbolicLink(base.resolve(link), link.endsWith("state") ? secret : outside);
                assertThrows(ExplicitException.class, () -> provisioner.download(base.toFile(), logger, true));
                assertEquals(0, provisioner.downloads);
                assertEquals("untouched", Files.readString(secret));
            }
        }
    }

    @Test
    void jobCachesUseTheSameLayoutWithoutPersistingState() throws Exception {
        var job = new Provisioner("/absolute");
        job.download(workspace.toFile(), logger);
        var data = workspace.resolve(job.getDataPath("/absolute"));
        assertTrue(Files.isRegularFile(data.resolve("data")));
        assertFalse(Files.exists(data.getParent().resolve("state")));
        var next = new Provisioner("/absolute");
        next.download(workspace.toFile(), logger);
        assertEquals(1, next.downloads);
        assertEquals(data.toFile(), next.getPathDir(workspace.toFile(), "/absolute"));
        assertFalse(Files.exists(data.getParent().resolve("state")));
    }

    @Test
    void changedChecksumPreservesLocalDataButAllowsUpload() throws Exception {
        var work = Files.createDirectories(workspace.resolve("work"));
        var lock = Files.writeString(work.resolve("lock"), "initial");
        var initial = new Provisioner(checksummedConfig());
        initial.download(workspace.toFile(), logger, true);
        var data = work.resolve("cache/data");
        Files.writeString(data, "local edits");
        Files.writeString(lock, "changed");

        var resumed = new Provisioner(checksummedConfig());
        resumed.download(workspace.toFile(), logger, true);
        assertEquals(0, resumed.downloads);
        assertNotEquals(initial.getConfig().getChecksum(), resumed.getConfig().getChecksum());
        resumed.upload(workspace.toFile(), logger);
        assertEquals("local edits", resumed.uploaded);
    }

    private CacheConfigFacade checksummedConfig() {
        return new CacheConfigFacade("key", Pair.of(Set.of("lock"), Set.of()),
                List.of(new CacheEntryFacade("cache", null)), UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH, null, null);
    }

    @Test
    void reprovisionPreservesAndUploadsChangesFromPreviousActivation() throws Exception {
        for (var path : List.of("relative", "/absolute")) {
            var initial = new Provisioner(path);
            initial.download(workspace.toFile(), logger, true);
            assertEquals(1, initial.downloads);
            var data = initial.getPathDir(workspace.toFile(), path).toPath().resolve("data");
            Files.writeString(data, "local edits");
            // Make the edit newer than the original restore but older than reprovisioning.
            var changedAt = System.currentTimeMillis() + 2;
            Files.setLastModifiedTime(data, FileTime.fromMillis(changedAt));
            while (System.currentTimeMillis() <= changedAt)
                Thread.sleep(1);

            var reprovisioned = new Provisioner(path);
            reprovisioned.download(workspace.toFile(), logger, true);
            assertEquals(0, reprovisioned.downloads);
            assertEquals("local edits", Files.readString(data));
            reprovisioned.upload(workspace.toFile(), logger);
            assertEquals("local edits", reprovisioned.uploaded);
        }
    }

    @Test
    void reprovisionRestoresMissingCachePaths() throws Exception {
        var provisioner = new Provisioner("missing");
        provisioner.download(workspace.toFile(), logger, true);
        assertEquals(1, provisioner.downloads);
        Files.delete(workspace.resolve("work/missing/data"));
        Files.delete(workspace.resolve("work/missing"));
        var resumed = new Provisioner("missing");
        resumed.download(workspace.toFile(), logger, true);
        assertEquals(1, resumed.downloads);
    }

    @Test
    void freshWorkspaceStillRestoresIntoExistingRepositoryDirectory() throws Exception {
        var cache = Files.createDirectories(workspace.resolve("work/cache"));
        Files.writeString(cache.resolve("data"), "repository data");
        var provisioner = new Provisioner("cache");
        provisioner.download(workspace.toFile(), logger, true);
        assertEquals(1, provisioner.downloads);
        assertEquals("stored", Files.readString(cache.resolve("data")));
    }

    @Test
    void jobRestoreStillDownloadsEveryTime() {
        var provisioner = new Provisioner("cache");
        provisioner.download(workspace.toFile(), logger, true);
        var job = new Provisioner("cache");
        job.download(workspace.toFile(), logger);
        assertEquals(1, job.downloads);
    }
}
