package io.onedev.k8shelper;

import javax.annotation.Nullable;
import java.util.Map;

public class RunContainerFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String image;

	private final String runAs;

	private final String args;

	private final Map<String, String> envMap;

	private final String workingDir;

	private final Map<String, String> volumeMounts;

	private final String builtInRegistryAccessToken;

	private final boolean useTTY;

	public RunContainerFacade(String image, @Nullable String runAs, @Nullable String args,
							  Map<String, String> envMap, @Nullable String workingDir,
							  Map<String, String> volumeMounts, @Nullable String builtInRegistryAccessToken,
							  boolean useTTY) {
		this.image = image;
		this.runAs = runAs;
		this.args = args;
		this.envMap = envMap;
		this.workingDir = workingDir;
		this.volumeMounts = volumeMounts;
		this.builtInRegistryAccessToken = builtInRegistryAccessToken;
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

	@Nullable
	public String getBuiltInRegistryAccessToken() {
		return builtInRegistryAccessToken;
	}

	public boolean isUseTTY() {
		return useTTY;
	}
}
