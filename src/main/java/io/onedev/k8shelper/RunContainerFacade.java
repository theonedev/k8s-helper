package io.onedev.k8shelper;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

public class RunContainerFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String image;

	private final String runAs;

	private final String args;

	private final Map<String, String> envMap;

	private final String workingDir;

	private final Map<String, String> volumeMounts;

	private final List<RegistryLoginFacade> registryLogins;

	private final boolean useTTY;

	public RunContainerFacade(String image, @Nullable String runAs, @Nullable String args,
							  Map<String, String> envMap, @Nullable String workingDir,
							  Map<String, String> volumeMounts, List<RegistryLoginFacade> registryLogins,
							  boolean useTTY) {
		this.image = image;
		this.runAs = runAs;
		this.args = args;
		this.envMap = envMap;
		this.workingDir = workingDir;
		this.volumeMounts = volumeMounts;
		this.registryLogins = registryLogins;
		this.useTTY = useTTY;
	}

	public String getImage() {
		return image;
	}

	@Nullable
	public String getRunAs() {
		return runAs;
	}

	@Nullable
	public String getArgs() {
		return args;
	}

	public Map<String, String> getEnvMap() {
		return envMap;
	}

	@Nullable
	public String getWorkingDir() {
		return workingDir;
	}

	public Map<String, String> getVolumeMounts() {
		return volumeMounts;
	}

	public List<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}

	public boolean isUseTTY() {
		return useTTY;
	}

	public RunContainerFacade replacePlaceholders(File buildHome) {
		var image = KubernetesHelper.replacePlaceholders(this.image, buildHome);
		var runAs = this.runAs;
		if (runAs != null)
			runAs = KubernetesHelper.replacePlaceholders(runAs, buildHome);
		var args = this.args;
		if (args != null)
			args = KubernetesHelper.replacePlaceholders(args, buildHome);
		var workingDir = this.workingDir;
		if (workingDir != null)
			workingDir = KubernetesHelper.replacePlaceholders(workingDir, buildHome);
		var volumeMounts = new HashMap<String, String>();
		for (var entry: this.volumeMounts.entrySet()) {
			volumeMounts.put(
				KubernetesHelper.replacePlaceholders(entry.getKey(), buildHome), 
				KubernetesHelper.replacePlaceholders(entry.getValue(), buildHome));
		}
		return new RunContainerFacade(image, runAs, args, envMap, workingDir, volumeMounts, registryLogins, useTTY);
	}
	
}
