package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.Map;

import javax.annotation.Nullable;

public class OsContainer implements Serializable {

	private static final long serialVersionUID = 1L;

	private final OsMatcher osMatcher;
	
	private final String image;

	private final String args;
	
	private final Map<String, String> envMap;
	
	private final String workingDir;

	public OsContainer(OsMatcher osMatcher, String image, @Nullable String args, 
			Map<String, String> envMap, @Nullable String workingDir) {
		this.osMatcher = osMatcher;
		this.image = image;
		this.args = args;
		this.envMap = envMap;
		this.workingDir = workingDir;
	}

	public OsMatcher getOsMatcher() {
		return osMatcher;
	}

	public String getImage() {
		return image;
	}

	public String getArgs() {
		return args;
	}

	public Map<String, String> getEnvMap() {
		return envMap;
	}

	public String getWorkingDir() {
		return workingDir;
	}

}
