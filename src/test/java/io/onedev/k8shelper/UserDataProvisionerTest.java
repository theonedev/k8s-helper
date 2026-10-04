package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        Provisioner(String path) {
            super(List.of(new UserDataFacade("data", List.of(new UserDataEntryFacade(path, null)))));
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
        }

        @Override
        protected void notifyUploaded(String key) {
            notified = true;
        }
    }

    @Test
    void uploadRejectsReplacedUserDataDirectory() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("1"), "host secret");
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
        assertFalse(Files.exists(outside.resolve("1")));
    }

    @Test
    void rejectsLeafAndDanglingLinksBeforeTransferOrMount() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var dataDir = Files.createDirectory(workspace.resolve("user-data"));
        var outside = Files.createDirectory(temp.resolve("outside"));
        var file = Files.writeString(outside.resolve("secret"), "keep");
        for (var target: List.of(outside, file, outside.resolve("missing"))) {
            var link = Files.createSymbolicLink(dataDir.resolve("1"), target);
            var provisioner = new Provisioner("/home/user/data");
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
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var outside = Files.createDirectory(temp.resolve("outside"));
        Files.createSymbolicLink(workspace.resolve("user-data"), outside);
        var provisioner = new Provisioner("/home/user/data");
        assertThrows(ExplicitException.class,
                () -> provisioner.mountVolumes(new Commandline("docker"), workspace.toFile(), path -> path));
    }

    @Test
    void configuredPathsRemainLabelsForIndexedStorage() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        for (var path: List.of("/home/user/data", "../../outside", "C:\\outside")) {
            var provisioner = new Provisioner(path);
            assertEquals(workspace.resolve("user-data/1").toFile(), provisioner.getPathFile(workspace.toFile(), 1));
        }
    }

    @Test
    void roundTripsFilesAndDirectories() throws Exception {
        for (boolean directory: List.of(false, true)) {
            var workspace = Files.createTempDirectory(temp, "source");
            var dataDir = Files.createDirectory(workspace.resolve("user-data"));
            var data = dataDir.resolve("1");
            if (directory) {
                Files.createDirectory(data);
                Files.writeString(data.resolve("file"), "contents");
                Files.createSymbolicLink(data.resolve("link"), Path.of("file"));
            } else {
                Files.writeString(data, "contents");
            }
            var provisioner = new Provisioner("/home/user/data");
            provisioner.upload(workspace.toFile(), logger);
            assertTrue(provisioner.notified);
            var destination = Files.createTempDirectory(temp, "destination");
            provisioner.download(destination.toFile(), logger);
            var restored = destination.resolve("user-data/1");
            assertEquals("contents", Files.readString(directory ? restored.resolve("file") : restored));
            if (directory) {
                assertTrue(Files.isSymbolicLink(restored.resolve("link")));
                assertEquals("contents", Files.readString(restored.resolve("link")));
            }
        }
    }
}
