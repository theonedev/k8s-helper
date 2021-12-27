package io.onedev.k8shelper;

import java.util.Map;

import javax.annotation.Nullable;

public class ContainerExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private final String image;

	private final String args;
	
	private final Map<String, String> envMap;
	
	private final String workingDir;
	
	private final boolean useTTY;
	
	public ContainerExecutable(String image, @Nullable String args, Map<String, String> envMap, 
			@Nullable String workingDir, boolean useTTY) {
		this.image = image;
		this.args = args;
		this.envMap = envMap;
		this.workingDir = workingDir;
		this.useTTY = useTTY;
	}

	public String getImage() {
		return image;
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

	public boolean isUseTTY() {
		return useTTY;
	}
	
}
