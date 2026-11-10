package io.onedev.k8shelper;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.onedev.commons.utils.command.Commandline;

/** A volume source, target, and access mode shared by executors and job steps. */
public class VolumeMountFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String sourcePath;

	private final String targetPath;

	private final boolean readOnly;

	public VolumeMountFacade(String sourcePath, String targetPath, boolean readOnly) {
		this.sourcePath = sourcePath;
		this.targetPath = targetPath;
		this.readOnly = readOnly;
	}

	public String getSourcePath() {
		return sourcePath;
	}

	public String getTargetPath() {
		return targetPath;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public static String getMountSpec(String source, String target, boolean readOnly) {
		// Quote entire CSV fields so paths cannot inject Docker mount options.
		return "type=bind,\"source=" + source.replace("\"", "\"\"") + "\",\"target="
				+ target.replace("\"", "\"\"") + "\",bind-propagation=rprivate,bind-recursive=disabled"
				+ (readOnly ? ",readonly" : "");
	}

	/**
	 * Mount executor-configured host paths without job placeholder interpolation.
	 * Jobs may write inside a writable mount, but must not be able to replace any part of the
	 * source path or its resolved path on the Docker host, including through other executors.
	 */
	public static void mountExecutorVolumes(Commandline docker, List<VolumeMountFacade> mounts,
											Function<String, String> hostPathResolver) {
		var args = new ArrayList<String>();
		for (var mount : mounts) {
			var source = Path.of(mount.sourcePath);
			if (!source.isAbsolute())
				throw new IllegalArgumentException("Executor volume mount source must be an absolute path: " + mount.sourcePath);
			if (!Path.of(mount.targetPath).isAbsolute())
				throw new IllegalArgumentException("Executor volume mount target must be an absolute path: " + mount.targetPath);
			var hostSource = hostPathResolver.apply(source.normalize().toString());
			args.add("--mount");
			// Fail closed on engines which do not support disabling recursive bind mounts.
			args.add(getMountSpec(hostSource, mount.targetPath, mount.readOnly));
		}
		docker.addArgs(args);
	}

}
