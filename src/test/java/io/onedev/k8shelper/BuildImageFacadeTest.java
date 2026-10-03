package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;

class BuildImageFacadeTest {
	@TempDir
	Path buildDir;

	private void export(String path) {
		new BuildImageFacade.OCIOutput(path).execute(new Commandline("unused") {
			@Override
			public ExecutionResult execute(Function<InputStream, Future<?>> out,
					Function<InputStream, Future<?>> err, Function<OutputStream, Future<?>> in) {
				assertEquals(java.util.List.of("-o", "type=oci,dest=-"), args());
				return new ExecutionResult(this);
			}
		}, buildDir.toFile(), null, null);
	}

	@Test
	void validatesOutputAfterPlaceholderExpansion() throws Exception {
		Files.createDirectory(buildDir.resolve("work"));
		var destination = buildDir.resolve("work/destination");
		Files.writeString(destination, "../outside/output");
		assertThrows(ExplicitException.class, () -> export("<&onedev#work/destination#onedev&>"));
		assertFalse(Files.exists(buildDir.resolve("outside")));
		Files.writeString(destination, "output/layout");
		export("<&onedev#work/destination#onedev&>");
		assertTrue(Files.isDirectory(buildDir.resolve("work/output/layout")));
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void rejectsOutputSymlinksIncludingDanglingLinksAndWorkdir() throws Exception {
		var work = Files.createDirectory(buildDir.resolve("work"));
		var outside = Files.createDirectory(buildDir.resolve("outside"));
		Files.createSymbolicLink(work.resolve("linked"), outside);
		Files.createSymbolicLink(work.resolve("dangling"), buildDir.resolve("missing"));
		assertThrows(ExplicitException.class, () -> export("linked/output"));
		assertThrows(ExplicitException.class, () -> export("dangling/output"));
		assertFalse(Files.exists(outside.resolve("output")));
		Files.delete(work.resolve("linked"));
		Files.delete(work.resolve("dangling"));
		Files.delete(work);
		Files.createSymbolicLink(work, outside);
		assertThrows(ExplicitException.class, () -> export("output"));
	}
}
