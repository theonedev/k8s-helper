package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.SerializationUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;

@EnabledOnOs({OS.LINUX, OS.MAC})
class UserDataProvisionerTest {

    @TempDir
    Path temp;

    private final TaskLogger logger = new TaskLogger() {
        @Override
        public void log(String message, String sessionId) {
        }
    };

    private static class Provisioner extends UserDataProvisioner {
        byte[] archive;
        boolean transferred;
        boolean notified;
        final Map<List<String>, byte[]> uploads = new HashMap<>();

        Provisioner(String path) {
            this("data", path);
        }

        Provisioner(String key, String path) {
            this(List.of(new UserDataFacade(key, List.of(new UserDataEntryFacade(path, null)))));
        }

        Provisioner(List<UserDataFacade> userDatas) {
            super(userDatas);
        }

        @Override
        protected void download(String key, String path, File pathFile) {
            transferred = true;
            TarUtils.untar(new ByteArrayInputStream(archive), pathFile, false);
        }

        @Override
        protected void upload(String key, String path, File pathFile, List<String> excludes) {
            transferred = true;
            var output = new ByteArrayOutputStream();
            TarUtils.tar(pathFile, excludes, output, false);
            archive = output.toByteArray();
            uploads.put(List.of(key, path), archive);
        }

        @Override
        protected void notifyUploaded(String key) {
            notified = true;
        }
    }

    private Path writeData(Provisioner provisioner, Path workspace, String key, String path, String contents)
            throws Exception {
        var data = provisioner.getPathFile(workspace.toFile(), key, path).toPath();
        Files.createDirectories(data.getParent());
        return Files.writeString(data, contents);
    }

    private byte[] archive(Path source) {
        var output = new ByteArrayOutputStream();
        TarUtils.tar(source.toFile(), output, false);
        return output.toByteArray();
    }

    @Test
    void reprovisionUploadsEditsFromPreviousActivationWithoutDownloading() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var initial = new Provisioner("/home/user/data");
        initial.archive = archive(Files.writeString(temp.resolve("source"), "stored"));
        initial.download(workspace.toFile(), logger);
        var state = workspace.resolve(initial.getStatePath("data", "/home/user/data"));
        var baseline = Files.getLastModifiedTime(state).toMillis();
        var data = writeData(initial, workspace, "data", "/home/user/data", "local edits");
        var changedAt = baseline + 2;
        Files.setLastModifiedTime(data, FileTime.fromMillis(changedAt));
        while (System.currentTimeMillis() <= changedAt)
            Thread.sleep(1);
        var provisioner = new Provisioner("/home/user/data");
        provisioner.download(workspace.toFile(), logger);
        assertFalse(provisioner.transferred);
        assertEquals("local edits", Files.readString(data));
        assertEquals(baseline, Files.getLastModifiedTime(state).toMillis());
        provisioner.upload(workspace.toFile(), logger);
        assertTrue(provisioner.transferred);
        assertTrue(provisioner.notified);
    }

    @Test
    void unchangedFilesAndDirectoriesAreNotUploadedAfterReprovisioning() throws Exception {
        for (boolean directory : List.of(false, true)) {
            var workspace = Files.createTempDirectory(temp, "workspace");
            var source = Files.createTempDirectory(temp, "source");
            var file = Files.writeString(source.resolve("file"), "stored");
            var initial = new Provisioner("/home/user/data");
            initial.archive = archive(directory ? source : file);
            initial.download(workspace.toFile(), logger);
            var state = workspace.resolve(initial.getStatePath("data", "/home/user/data"));
            var baseline = Files.getLastModifiedTime(state);
            assertTrue(Files.isRegularFile(state));
            var serialized = SerializationUtils.clone(initial);
            serialized.upload(workspace.toFile(), logger);
            assertTrue(serialized.uploads.isEmpty());
            var resumed = new Provisioner("/home/user/data");
            resumed.download(workspace.toFile(), logger);
            resumed.upload(workspace.toFile(), logger);
            assertFalse(resumed.transferred);
            assertFalse(resumed.notified);
            assertEquals(baseline, Files.getLastModifiedTime(state));
        }
    }

    @Test
    void changedOrUnknownEntryDoesNotForceOtherEntriesToUpload() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var config = List.of(new UserDataFacade("data", List.of(
                new UserDataEntryFacade("/changed", null), new UserDataEntryFacade("/unchanged", null))));
        var initial = new Provisioner(config);
        initial.archive = archive(Files.writeString(temp.resolve("source"), "stored"));
        initial.download(workspace.toFile(), logger);
        var changed = writeData(initial, workspace, "data", "/changed", "edits");
        Files.setLastModifiedTime(changed, FileTime.fromMillis(System.currentTimeMillis() + 2));
        var resumed = new Provisioner(List.of(new UserDataFacade("data", List.of(
                new UserDataEntryFacade("/changed", null), new UserDataEntryFacade("/unchanged", null),
                new UserDataEntryFacade("/fresh", null), new UserDataEntryFacade("/unknown", null)))));
        resumed.archive = initial.archive;
        var unknown = writeData(resumed, workspace, "data", "/unknown", "old edits without baseline");
        Files.setLastModifiedTime(unknown, FileTime.fromMillis(1));
        resumed.download(workspace.toFile(), logger);
        resumed.upload(workspace.toFile(), logger);
        assertEquals(Set.of(List.of("data", "/changed"), List.of("data", "/unknown")), resumed.uploads.keySet());
    }

    @Test
    void restoringMissingDataReplacesItsOldBaseline() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var initial = new Provisioner("/data");
        initial.archive = archive(Files.writeString(temp.resolve("source"), "stored"));
        initial.download(workspace.toFile(), logger);
        var state = workspace.resolve(initial.getStatePath("data", "/data"));
        Files.setLastModifiedTime(state, FileTime.fromMillis(1));
        Files.delete(initial.getPathFile(workspace.toFile(), "data", "/data").toPath());
        var resumed = new Provisioner("/data");
        resumed.archive = initial.archive;
        resumed.download(workspace.toFile(), logger);
        resumed.upload(workspace.toFile(), logger);
        assertTrue(Files.getLastModifiedTime(state).toMillis() > 1);
        assertTrue(resumed.uploads.isEmpty());
    }

    @Test
    void stateSymlinksAreRejectedBeforeRestoringOrReusingData() throws Exception {
        var outside = Files.writeString(temp.resolve("outside"), "host data");
        var originalTime = Files.getLastModifiedTime(outside);
        for (boolean existing : List.of(false, true)) {
            for (var target : List.of(outside, temp.resolve("missing"))) {
                var workspace = Files.createTempDirectory(temp, "workspace");
                var provisioner = new Provisioner("/data");
                if (existing)
                    writeData(provisioner, workspace, "data", "/data", "local data");
                var state = workspace.resolve(provisioner.getStatePath("data", "/data"));
                Files.createDirectories(state.getParent());
                Files.createSymbolicLink(state, target);
                assertThrows(ExplicitException.class, () -> provisioner.download(workspace.toFile(), logger));
                assertFalse(provisioner.transferred);
            }
        }
        assertEquals("host data", Files.readString(outside));
        assertEquals(originalTime, Files.getLastModifiedTime(outside));
        assertFalse(Files.exists(temp.resolve("missing")));
    }

    @Test
    void uploadRejectsReplacedUserDataDirectory() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("data"), "host secret");
        Files.createSymbolicLink(workspace.resolve("user-data"), outside);
        var provisioner = new Provisioner("/home/user/data");
        assertThrows(ExplicitException.class, () -> provisioner.upload(workspace.toFile(), logger));
        assertFalse(provisioner.transferred);
        assertFalse(provisioner.notified);
    }

    @Test
    void downloadRejectsReplacedUserDataDirectory() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var outside = Files.createDirectory(temp.resolve("outside"));
        Files.createSymbolicLink(workspace.resolve("user-data"), outside);
        var provisioner = new Provisioner("/home/user/data");
        var source = Files.writeString(temp.resolve("source"), "downloaded data");
        var archive = new ByteArrayOutputStream();
        TarUtils.tar(source.toFile(), archive, false);
        provisioner.archive = archive.toByteArray();
        assertThrows(ExplicitException.class, () -> provisioner.download(workspace.toFile(), logger));
        assertFalse(provisioner.transferred);
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsLeafAndDanglingLinksBeforeTransferOrMount() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var provisioner = new Provisioner("/home/user/data");
        var data = provisioner.getPathFile(workspace.toFile(), "data", "/home/user/data").toPath();
        Files.createDirectories(data.getParent());
        var outside = Files.createDirectory(temp.resolve("outside"));
        var file = Files.writeString(outside.resolve("secret"), "keep");
        for (var target: List.of(outside, file, outside.resolve("missing"))) {
            var link = Files.createSymbolicLink(data, target);
            assertThrows(ExplicitException.class, () -> provisioner.upload(workspace.toFile(), logger));
            assertThrows(ExplicitException.class, () -> provisioner.download(workspace.toFile(), logger));
            assertThrows(ExplicitException.class,
                    () -> provisioner.mountVolumes(new Commandline("docker"), workspace.toFile(), path -> path));
            assertFalse(provisioner.transferred);
            assertFalse(provisioner.notified);
            Files.delete(link);
        }
        assertEquals("keep", Files.readString(file));
        assertFalse(Files.exists(outside.resolve("missing")));
    }

    @Test
    void mountRejectsReplacedParent() throws Exception {
        var outside = Files.createDirectory(temp.resolve("outside"));
        var provisioner = new Provisioner("/home/user/data");
        for (var parent : List.of(Path.of("user-data"), Path.of(provisioner.getDataPath("data", "/home/user/data")).getParent())) {
            var workspace = Files.createTempDirectory(temp, "workspace");
            Files.createDirectories(workspace.resolve(parent).getParent());
            Files.createSymbolicLink(workspace.resolve(parent), outside);
            assertThrows(ExplicitException.class,
                    () -> provisioner.mountVolumes(new Commandline("docker"), workspace.toFile(), path -> path));
            assertThrows(ExplicitException.class, () -> provisioner.download(workspace.toFile(), logger));
            assertThrows(ExplicitException.class, () -> provisioner.upload(workspace.toFile(), logger));
        }
    }

    @Test
    void configuredKeysAndPathsUseBoundedStorageNames() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        for (var path: List.of("/home/user/data", "../../outside", "C:\\outside", "/long".repeat(1000))) {
            var key = "../key/".repeat(1000);
            var provisioner = new Provisioner(key, path);
            var subPath = provisioner.getDataPath(key, path);
            assertTrue(subPath.matches("user-data/[0-9a-f]{64}/data"));
            assertEquals(Path.of(subPath).resolveSibling("state"), Path.of(provisioner.getStatePath(key, path)));
            var data = writeData(provisioner, workspace, key, path, "contents");
            assertEquals(workspace.resolve(subPath), data);
            assertEquals("contents", Files.readString(data));
        }
    }

    @Test
    void reorderingEntriesPreservesContentsAndUploadDestinations() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var first = new UserDataEntryFacade("/first", null);
        var second = new UserDataEntryFacade("/second", null);
        var third = new UserDataEntryFacade("/third", null);
        var initial = new Provisioner(List.of(new UserDataFacade("alpha", List.of(first, second)),
                new UserDataFacade("beta", List.of(third))));
        for (var identity : List.of(List.of("alpha", "/first"), List.of("alpha", "/second"), List.of("beta", "/third"))) {
            var data = writeData(initial, workspace, identity.get(0), identity.get(1), identity.toString());
            Files.setLastModifiedTime(data, FileTime.fromMillis(1));
        }
        var resumed = new Provisioner(List.of(new UserDataFacade("beta", List.of(third)),
                new UserDataFacade("alpha", List.of(second, first))));
        resumed.download(workspace.toFile(), logger);
        assertFalse(resumed.transferred);
        resumed.upload(workspace.toFile(), logger);
        assertEquals(3, resumed.uploads.size());
        for (var entry : resumed.uploads.entrySet()) {
            var restored = temp.resolve("restored");
            TarUtils.untar(new ByteArrayInputStream(entry.getValue()), restored.toFile(), false);
            assertEquals(entry.getKey().toString(), Files.readString(restored));
        }
    }

    @Test
    void changedKeyOrPathDownloadsSeparateData() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var original = new Provisioner("old", "/first");
        var originalData = writeData(original, workspace, "old", "/first", "local edits");
        var source = Files.writeString(temp.resolve("source"), "downloaded contents");
        var archive = new ByteArrayOutputStream();
        TarUtils.tar(source.toFile(), archive, false);
        for (var identity : List.of(List.of("new", "/first"), List.of("old", "/second"))) {
            var changed = new Provisioner(identity.get(0), identity.get(1));
            changed.archive = archive.toByteArray();
            changed.download(workspace.toFile(), logger);
            assertTrue(changed.transferred);
            var data = changed.getPathFile(workspace.toFile(), identity.get(0), identity.get(1)).toPath();
            assertEquals("downloaded contents", Files.readString(data));
            assertEquals("local edits", Files.readString(originalData));
        }
        assertNotEquals(original.getDataPath("key", "/path"), original.getDataPath("key/", "path"));
    }

    @Test
    void dockerMountsUseTheSamePathsAfterSerialization() {
        var provisioner = SerializationUtils.clone(new Provisioner(List.of(
                new UserDataFacade("alpha", List.of(new UserDataEntryFacade("/first", null))),
                new UserDataFacade("beta", List.of(new UserDataEntryFacade("/second", null))))));
        var docker = new Commandline("docker");
        provisioner.mountVolumes(docker, temp.toFile(), path -> path);
        assertEquals(List.of("-v", temp.resolve(provisioner.getDataPath("alpha", "/first")) + ":/first",
                "-v", temp.resolve(provisioner.getDataPath("beta", "/second")) + ":/second"), docker.args());
    }

    @Test
    void roundTripsFilesAndDirectories() throws Exception {
        for (boolean directory: List.of(false, true)) {
            var workspace = Files.createTempDirectory(temp, "source");
            var provisioner = new Provisioner("/home/user/data");
            var data = provisioner.getPathFile(workspace.toFile(), "data", "/home/user/data").toPath();
            Files.createDirectories(data.getParent());
            if (directory) {
                Files.createDirectory(data);
                Files.writeString(data.resolve("file"), "contents");
                Files.createSymbolicLink(data.resolve("link"), Path.of("file"));
            } else {
                Files.writeString(data, "contents");
            }
            provisioner.upload(workspace.toFile(), logger);
            assertTrue(provisioner.notified);
            var destination = Files.createTempDirectory(temp, "destination");
            provisioner.download(destination.toFile(), logger);
            var restored = destination.resolve(provisioner.getDataPath("data", "/home/user/data"));
            assertEquals("contents", Files.readString(directory ? restored.resolve("file") : restored));
            if (directory) {
                assertTrue(Files.isSymbolicLink(restored.resolve("link")));
                assertEquals("contents", Files.readString(restored.resolve("link")));
            }
        }
    }
}
