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

@EnabledOnOs({OS.LINUX, OS.MAC})
class WorkspaceScriptTest {

	@TempDir
	Path temp;

	private ScriptConfig scripts(String commands) {
		return new ScriptConfig(commands, commands, ".sh", "sh", new String[0]);
	}

	@Test
	void resetCannotOverwriteExistingOrDanglingScriptLinkTargets() throws Exception {
		var sentinel = Files.writeString(temp.resolve("sentinel"), "keep");
		var missing = temp.resolve("missing");
		for (var name : List.of("setup.sh", "teardown.sh")) {
			for (var target : List.of(sentinel, missing)) {
				var workspace = Files.createTempDirectory(temp, "workspace");
				WorkspaceHelper.writeScripts(workspace.toFile(), scripts("original"));
				Files.delete(workspace.resolve(name));
				Files.createSymbolicLink(workspace.resolve(name), target);
				assertThrows(ExplicitException.class,
						() -> WorkspaceHelper.writeScripts(workspace.toFile(), scripts("replacement")));
				assertEquals("keep", Files.readString(sentinel));
				assertFalse(Files.exists(missing));
			}
		}
	}

	@Test
	void createsAndUpdatesScriptsWithoutRejectingTrustedWorkspaceRoot() throws Exception {
		var actual = Files.createDirectory(temp.resolve("actual"));
		var workspace = Files.createSymbolicLink(temp.resolve("workspace"), actual);
		for (var commands : List.of("original", "replacement")) {
			WorkspaceHelper.writeScripts(workspace.toFile(), scripts(commands));
			assertEquals(commands, Files.readString(actual.resolve("setup.sh")));
			assertEquals(commands, Files.readString(actual.resolve("teardown.sh")));
		}
	}
}
