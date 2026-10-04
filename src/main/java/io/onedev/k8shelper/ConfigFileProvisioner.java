package io.onedev.k8shelper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;

public class ConfigFileProvisioner {

	private final List<ConfigFileFacade> configFiles;

	private final Map<String, Integer> pathIndexes;

	public ConfigFileProvisioner(List<ConfigFileFacade> configFiles) {
		this.configFiles = configFiles;
		pathIndexes = new HashMap<>();
		var configFileIndex = 1;
		for (var configFile : configFiles) {
			pathIndexes.put(configFile.getPath(), configFileIndex++);
		}
	}

	public Map<String, Integer> getPathIndexes() {
		return pathIndexes;
	}

	public File getPathFile(File workspaceDir, int pathIndex) {
		var pathFile = new File(workspaceDir, "config-files/" + pathIndex);
		if (FileUtils.hasSymbolLinks(workspaceDir, pathFile))
			throw new ExplicitException("Config file path does not allow to contain symbol links: " + pathFile);
		return pathFile;
	}

	public String getSubPath(int pathIndex) {
		return "config-files/" + pathIndex;
	}

	public void provision(File workspaceDir, TaskLogger logger) {
		for (var configFile : configFiles) {
			logger.log("Provisioning config file '" + configFile.getPath() + "'...");
			var pathIndex = Preconditions.checkNotNull(pathIndexes.get(configFile.getPath()));			
			var pathFile = getPathFile(workspaceDir, pathIndex);
			try {
				FileUtils.writeStringToFile(pathFile, configFile.getContent(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public void mountVolumes(Commandline docker, File workspaceDir, Function<String, String> hostPathResolver) {
		for (var entry: pathIndexes.entrySet()) {
			var pathFile = getPathFile(workspaceDir, entry.getValue());
			docker.addArgs("-v", hostPathResolver.apply(pathFile.getAbsolutePath()) + ":" + entry.getKey());
		}
	}

}
