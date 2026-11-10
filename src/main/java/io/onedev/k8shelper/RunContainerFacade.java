package io.onedev.k8shelper;

import java.io.File;
import java.util.ArrayList;
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

	private final List<VolumeMountFacade> volumeMounts;

	private final List<RegistryLoginFacade> registryLogins;

	private final boolean useTTY;

	public RunContainerFacade(String image, String runAs, @Nullable String args,
							  Map<String, String> envMap, @Nullable String workingDir,
							  List<VolumeMountFacade> volumeMounts, List<RegistryLoginFacade> registryLogins,
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

	public List<VolumeMountFacade> getVolumeMounts() {
		return volumeMounts;
	}

	public List<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}

	public boolean isUseTTY() {
		return useTTY;
	}

	public RunContainerFacade replacePlaceholders(File buildDir) {
		var image = KubernetesHelper.replacePlaceholders(this.image, buildDir);
		var runAs = this.runAs;
		if (runAs != null)
			runAs = KubernetesHelper.replacePlaceholders(runAs, buildDir);
		var args = this.args;
		if (args != null)
			args = KubernetesHelper.replacePlaceholders(args, buildDir);
		var workingDir = this.workingDir;
		if (workingDir != null)
			workingDir = KubernetesHelper.replacePlaceholders(workingDir, buildDir);
		var volumeMounts = new ArrayList<VolumeMountFacade>();
		for (var mount: this.volumeMounts) {
			volumeMounts.add(new VolumeMountFacade(
					KubernetesHelper.replacePlaceholders(mount.getSourcePath(), buildDir),
					KubernetesHelper.replacePlaceholders(mount.getTargetPath(), buildDir),
					mount.isReadOnly()));
		}
		return new RunContainerFacade(image, runAs, args, envMap, workingDir, volumeMounts, registryLogins, useTTY);
	}
	
}
