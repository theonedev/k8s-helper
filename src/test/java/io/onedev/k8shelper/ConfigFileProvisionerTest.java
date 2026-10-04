package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ConfigFileProvisionerTest {

    @TempDir
    Path temp;

    private final TaskLogger logger = new TaskLogger() {
        @Override
        public void log(String message, String sessionId) {
        }
    };

    private ConfigFileProvisioner provisioner() {
        return new ConfigFileProvisioner(List.of(new ConfigFileFacade("/home/user/config", "config content")));
    }

    @Test
    void rejectsSymlinkedParentBeforeCreatingOrWritingFiles() throws Exception {
        var outside = Files.createDirectory(temp.resolve("outside"));
        var sentinel = Files.writeString(outside.resolve("1"), "keep");
        for (var target: List.of(outside, temp.resolve("missing"))) {
            var workspace = Files.createTempDirectory(temp, "workspace");
            Files.createSymbolicLink(workspace.resolve("config-files"), target);
            assertThrows(ExplicitException.class, () -> provisioner().provision(workspace.toFile(), logger));
            assertEquals("keep", Files.readString(sentinel));
            assertFalse(Files.exists(temp.resolve("missing")));
        }
    }

    @Test
    void rejectsExistingAndDanglingFileLinks() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var configDir = Files.createDirectory(workspace.resolve("config-files"));
        var sentinel = Files.writeString(temp.resolve("sentinel"), "keep");
        for (var target: List.of(sentinel, temp.resolve("missing"))) {
            var link = Files.createSymbolicLink(configDir.resolve("1"), target);
            assertThrows(ExplicitException.class, () -> provisioner().provision(workspace.toFile(), logger));
            assertEquals("keep", Files.readString(sentinel));
            assertFalse(Files.exists(temp.resolve("missing")));
            Files.delete(link);
        }
    }

    @Test
    void rechecksMountSourceAfterInitContainerCanReplaceIt() throws Exception {
        var outside = Files.createDirectory(temp.resolve("outside"));
        var sentinel = Files.writeString(outside.resolve("1"), "host secret");
        for (boolean replaceParent: List.of(false, true)) {
            var workspace = Files.createTempDirectory(temp, "workspace");
            var provisioner = provisioner();
            provisioner.provision(workspace.toFile(), logger);
            var configDir = workspace.resolve("config-files");
            Files.delete(configDir.resolve("1"));
            if (replaceParent) {
                Files.delete(configDir);
                Files.createSymbolicLink(configDir, outside);
            } else {
                Files.createSymbolicLink(configDir.resolve("1"), sentinel);
            }
            assertThrows(ExplicitException.class, () -> provisioner.mountVolumes(
                    new Commandline("docker"), workspace.toFile(), path -> fail("Unsafe mount source was resolved")));
            assertEquals("host secret", Files.readString(sentinel));
        }
    }

    @Test
    void writesAndUpdatesIndexedFilesInsteadOfConfiguredHostPaths() throws Exception {
        var workspace = Files.createDirectory(temp.resolve("workspace"));
        var outside = temp.resolve("outside");
        for (var path: List.of(outside.toString(), "../../outside", "C:\\outside")) {
            var provisioner = new ConfigFileProvisioner(List.of(new ConfigFileFacade(path, "config content")));
            provisioner.provision(workspace.toFile(), logger);
            var file = workspace.resolve("config-files/1");
            assertEquals("config content", Files.readString(file));
            assertEquals(file.toFile(), provisioner.getPathFile(workspace.toFile(), 1));
            assertFalse(Files.exists(outside));
            var docker = new Commandline("docker");
            provisioner.mountVolumes(docker, workspace.toFile(), hostPath -> hostPath);
            assertEquals(List.of("-v", file + ":" + path), docker.args());
        }
        new ConfigFileProvisioner(List.of(new ConfigFileFacade("/home/user/config", "updated")))
                .provision(workspace.toFile(), logger);
        assertEquals("updated", Files.readString(workspace.resolve("config-files/1")));
    }
}
