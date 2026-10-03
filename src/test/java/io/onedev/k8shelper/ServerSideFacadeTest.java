package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ServerSideFacadeTest {

	@TempDir
	Path buildDir;

	private ServerSideFacade step(String sourcePath, String pattern) {
		return new ServerSideFacade(null, sourcePath, Set.of(pattern), Set.of(), List.of());
	}

	private void assertRejected(ServerSideFacade step, String message) {
		var localError = assertThrows(ExplicitException.class, () -> step.execute(buildDir.toFile(),
				(inputDir, placeholders) -> fail("Server step must not run")));
		assertTrue(localError.getMessage().contains(message), localError.getMessage());
		// Invalid inputs must also be rejected before opening an HTTP connection.
		var remoteError = assertThrows(ExplicitException.class, () -> JobHelper.runServerStep(
				null, "http://unused.invalid", "token", List.of(0), step, buildDir.toFile(), null));
		assertTrue(remoteError.getMessage().contains(message), remoteError.getMessage());
	}

	private void assertNoFilesCollected(ServerSideFacade step) {
		assertTrue(step.execute(buildDir.toFile(), (inputDir, placeholders) -> {
			assertArrayEquals(new String[0], inputDir.list());
			return new ServerStepResult(true);
		}));
	}

	@Test
	void rejectsSymlinkedWorkingDirectory() throws Exception {
		var target = Files.createDirectory(buildDir.resolve("target"));
		Files.writeString(target.resolve("report.txt"), "report");
		Files.createSymbolicLink(buildDir.resolve("work"), target);
		assertRejected(step(null, "**"), "Source directory does not allow symbolic links");
	}

	@Test
	void rejectsSymlinkedSourceAndItsAncestors() throws Exception {
		var work = Files.createDirectory(buildDir.resolve("work"));
		var target = Files.createDirectory(buildDir.resolve("target"));
		Files.createDirectory(target.resolve("reports"));
		Files.createSymbolicLink(work.resolve("link"), target);
		assertRejected(step("link", "**"), "Source directory does not allow symbolic links");
		assertRejected(step("link/reports", "**"), "Source directory does not allow symbolic links");
		Files.createSymbolicLink(work.resolve("dangling"), buildDir.resolve("missing"));
		assertRejected(step("dangling", "**"), "Source directory does not allow symbolic links");
	}

	@Test
	void skipsMatchedSymlinks() throws Exception {
		var work = Files.createDirectory(buildDir.resolve("work"));
		var target = Files.writeString(work.resolve("target.txt"), "report");
		Files.createSymbolicLink(work.resolve("link.txt"), target);
		assertNoFilesCollected(step(null, "link.txt"));
		Files.delete(target);
		assertNoFilesCollected(step(null, "link.txt"));
	}

	@Test
	void skipsMatchedFilesUnderSymlinkedDirectories() throws Exception {
		var work = Files.createDirectory(buildDir.resolve("work"));
		var target = Files.createDirectory(buildDir.resolve("target"));
		Files.writeString(target.resolve("report.txt"), "report");
		Files.createSymbolicLink(work.resolve("link"), target);
		assertNoFilesCollected(step(null, "**/*.txt"));
		assertNoFilesCollected(step(null, "link/report.txt"));
	}

	@Test
	void rejectsParentTraversalInSource() throws Exception {
		Files.createDirectory(buildDir.resolve("work"));
		assertRejected(step("../target", "**"), "Source path should not contain '..'");
	}

	@Test
	void collectsRegularFilesAndHonorsExclusions() throws Exception {
		var source = Files.createDirectories(buildDir.resolve("work/reports"));
		Files.writeString(source.resolve("report.txt"), "report");
		Files.writeString(source.resolve("other.log"), "log");
		Files.createSymbolicLink(source.resolve("excluded.txt"), source.resolve("report.txt"));
		var step = new ServerSideFacade(null, "reports", Set.of("**/*.txt"),
				Set.of("excluded.txt"), List.of());
		assertTrue(step.execute(buildDir.toFile(), (inputDir, placeholders) -> {
			assertArrayEquals(new String[] {"report.txt"}, inputDir.list());
			try {
				assertEquals("report", Files.readString(inputDir.toPath().resolve("report.txt")));
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
			return new ServerStepResult(true);
		}));
	}
}
