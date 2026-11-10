package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunContainerFacadeTest {

	@TempDir
	Path build;

	@Test
	void placeholderReplacementPreservesAccessModesAndRepeatedSources() throws Exception {
		var work = Files.createDirectory(build.resolve("work"));
		Files.writeString(work.resolve("source"), "data");
		Files.writeString(work.resolve("target"), "/input");
		var source = "<&onedev#work/source#onedev&>";
		var step = new RunContainerFacade("alpine", "0:0", null, Map.of(), null, List.of(
				new VolumeMountFacade(source, "<&onedev#work/target#onedev&>", true),
				new VolumeMountFacade(source, "/output", false)), List.of(), false);
		var mounts = step.replacePlaceholders(build.toFile()).getVolumeMounts();
		assertEquals(2, mounts.size());
		assertEquals("data", mounts.get(0).getSourcePath());
		assertEquals("/input", mounts.get(0).getTargetPath());
		assertTrue(mounts.get(0).isReadOnly());
		assertEquals("data", mounts.get(1).getSourcePath());
		assertEquals("/output", mounts.get(1).getTargetPath());
		assertFalse(mounts.get(1).isReadOnly());
	}

}
