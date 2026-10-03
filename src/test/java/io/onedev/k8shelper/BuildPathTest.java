package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;

@EnabledOnOs({OS.LINUX, OS.MAC})
class BuildPathTest {

	@TempDir
	Path temp;

	@Test
	void rejectsSymlinkedParentsAndDanglingLinks() throws Exception {
		var build = Files.createDirectory(temp.resolve("build"));
		var outside = Files.createDirectory(temp.resolve("outside"));
		for (var path: List.of("command", "work", "mark")) {
			Files.createSymbolicLink(build.resolve(path), outside);
			assertThrows(ExplicitException.class,
					() -> JobHelper.resolveBuildPath(build.toFile(), path + "/new-file"));
		}
		Files.createSymbolicLink(build.resolve("continue"), outside.resolve("missing"));
		assertThrows(ExplicitException.class,
				() -> JobHelper.resolveBuildPath(build.toFile(), "continue"));
		assertArrayEquals(new String[0], outside.toFile().list());
	}

	@Test
	void rejectsEscapingPathsAndAllowsOrdinaryPaths() throws Exception {
		var build = Files.createDirectory(temp.resolve("build"));
		for (var path: List.of("../outside", "/outside", "C:\\outside", "work/../../outside")) {
			assertThrows(ExplicitException.class,
					() -> JobHelper.resolveBuildPath(build.toFile(), path));
		}
		assertEquals(build.resolve("work/new/file").toFile(),
				JobHelper.resolveBuildPath(build.toFile(), "work/new/file"));
	}

	@Test
	void pauseScriptsDoNotOverwriteSymlinkTargets() throws Exception {
		var build = Files.createDirectory(temp.resolve("build"));
		var outside = Files.writeString(temp.resolve("outside"), "keep");
		var command = new CommandFacade("image", "0:0", List.of(), Map.of(), false, "echo test");
		for (var path: List.of("pause.sh", "pause")) {
			Files.deleteIfExists(build.resolve(path));
			Files.createSymbolicLink(build.resolve(path), outside);
			assertThrows(ExplicitException.class, () -> command.generatePauseCommand(build.toFile()));
			assertEquals("keep", Files.readString(outside));
			Files.delete(build.resolve(path));
		}
	}

	@Test
	void serverOutputsDoNotFollowParentOrFileSymlinks() throws Exception {
		var build = Files.createDirectory(temp.resolve("build"));
		Files.createDirectory(build.resolve("work"));
		var outside = Files.createDirectory(temp.resolve("outside"));
		var sentinel = Files.writeString(outside.resolve("file"), "keep");
		Files.createSymbolicLink(build.resolve("output"), outside);
		Files.createSymbolicLink(build.resolve("leaf"), sentinel);
		Files.createSymbolicLink(build.resolve("dangling"), outside.resolve("missing"));
		var step = new ServerSideFacade(null, null, Set.of(), Set.of(), List.of());
		for (var path: List.of("output/file", "output/new/file", "leaf", "dangling", "../outside/file")) {
			var result = new ServerStepResult(true, Map.of(path, "changed".getBytes()));
			assertThrows(ExplicitException.class, () -> result.writeOutputFiles(build.toFile()));
			assertThrows(ExplicitException.class, () -> step.execute(build.toFile(), (input, values) -> result));
			assertEquals("keep", Files.readString(sentinel));
			assertArrayEquals(new String[] {"file"}, outside.toFile().list());
		}
		var result = new ServerStepResult(true, Map.of("work/new/file", "result".getBytes()));
		assertTrue(step.execute(build.toFile(), (input, values) -> result));
		assertEquals("result", Files.readString(build.resolve("work/new/file")));
	}
	@Test
	void resumeRejectsLinksAndAllowsRepeatedRequests() throws Exception {
		var build = Files.createDirectory(temp.resolve("build"));
		var target = temp.resolve("missing");
		Files.createSymbolicLink(build.resolve("continue"), target);
		assertThrows(ExplicitException.class, () -> JobHelper.resumeJob(build.toFile()));
		assertFalse(Files.exists(target));
		Files.delete(build.resolve("continue"));
		JobHelper.resumeJob(build.toFile());
		JobHelper.resumeJob(build.toFile());
		assertTrue(Files.isRegularFile(build.resolve("continue")));
	}

}
