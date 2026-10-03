package io.onedev.k8shelper;

import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.commons.utils.command.StreamPumper;

import org.jspecify.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.List;

import static io.onedev.commons.utils.StringUtils.parseQuoteTokens;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;

public class BuildImageFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String buildPath;
	
	private final String dockerfile;
	
	private final Output output;

	private final List<RegistryLoginFacade> registryLogins;

	private final String platforms;

	private final String moreOptions;

	public BuildImageFacade(@Nullable String buildPath, @Nullable String dockerFile,
							Output output, List<RegistryLoginFacade> registryLogins,
							@Nullable String platforms, @Nullable String moreOptions) {
		this.buildPath = buildPath;
		this.dockerfile = dockerFile;
		this.output = output;
		this.registryLogins = registryLogins;
		this.platforms = platforms;
		this.moreOptions = moreOptions;
	}

	@Nullable
	public String getBuildPath() {
		return buildPath;
	}

	@Nullable
	public String getDockerfile() {
		return dockerfile;
	}

	public Output getOutput() {
		return output;
	}

	public List<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}

	@Nullable
	public String getPlatforms() {
		return platforms;
	}

	@Nullable
	public String getMoreOptions() {
		return moreOptions;
	}

	/** Validate expanded paths still controlled by the build specification. */
	public static File resolvePath(File hostBuildDir, String path) {
		if (path.isBlank() || !PathUtils.isSubPath(path) || path.contains(":") || path.contains("\\")
				|| path.indexOf('\0') != -1)
			throw new ExplicitException("Build image paths must be relative local paths without '..'");
		var workDir = new File(hostBuildDir, "work").getAbsoluteFile();
		var resolved = workDir.toPath().resolve(path).normalize().toFile();
		if (Files.isSymbolicLink(workDir.toPath()) || FileUtils.hasSymbolLinks(workDir, resolved))
			throw new ExplicitException("Build image paths must not contain symbolic links");
		try {
			if (!resolved.getCanonicalFile().toPath().startsWith(workDir.getCanonicalFile().toPath()))
				throw new ExplicitException("Build image paths must stay inside the job workdir");
		} catch (IOException e) {
			throw new ExplicitException("Unable to validate build image path: " + path, e);
		}
		return resolved;
	}

	public interface Output extends Serializable {

		/**
		 * Implementation of this method should add arguments to provided docker command to do the job
		 */
		void execute(Commandline docker, File hostBuildDir, LineConsumer infoLogger, LineConsumer errorLogger);

	}

	public static class RegistryOutput implements Output {

		private static final long serialVersionUID = 1L;

		private final String tags;

		public RegistryOutput(String tags) {
			this.tags = tags;
		}

		@Override
		public void execute(Commandline docker, File hostBuildDir, LineConsumer infoLogger, LineConsumer errorLogger) {
			docker.addArgs("--push");
			String[] parsedTags = parseQuoteTokens(replacePlaceholders(tags, hostBuildDir));
			for (String tag : parsedTags)
				docker.addArgs("-t", tag);
			docker.execute(infoLogger, errorLogger).checkReturnCode();
		}
	}

	public static class OCIOutput implements Output {

		private static final long serialVersionUID = 1L;

		private final String destPath;

		public OCIOutput(String destPath) {
			this.destPath = destPath;
		}

		@Override
		public void execute(Commandline docker, File hostBuildDir, LineConsumer infoLogger, LineConsumer errorLogger) {
			var destDir = resolvePath(hostBuildDir, replacePlaceholders(destPath, hostBuildDir));
			FileUtils.createDir(destDir);
			docker.addArgs("-o", "type=oci,dest=-");
			docker.execute(is -> Bootstrap.executorService.submit(() -> {
				try (is) {
					TarUtils.untar(is, destDir, false);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}), StreamPumper.pumpTo(errorLogger), null).checkReturnCode();
		}
	}

}
