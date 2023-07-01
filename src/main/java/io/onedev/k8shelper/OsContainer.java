package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.Map;

import javax.annotation.Nullable;

public class OsContainer implements Serializable {

	private static final long serialVersionUID = 1L;

	private final OsMatcher osMatcher;
	
	private final String image;

	private final String opts;

	private final String args;
	
	private final Map<String, String> envMap;
	
	private final String workingDir;
	
	private final Map<String, String> volumeMounts;

	public OsContainer(OsMatcher osMatcher, String image, @Nullable String opts,
					   @Nullable String args, Map<String, String> envMap,
					   @Nullable String workingDir, Map<String, String> volumeMounts) {
		this.osMatcher = osMatcher;
		this.image = image;
		this.opts = opts;
		this.args = args;
		this.envMap = envMap;
		this.workingDir = workingDir;
		this.volumeMounts = volumeMounts;
	}

	public OsMatcher getOsMatcher() {
		return osMatcher;
	}

	public String getImage() {
		return image;
	}

	@Nullable
	public String getOpts() {
		return opts;
	}

	@Nullable
	public String getArgs() {
		return args;
	}

	public Map<String, String> getEnvMap() {
		return envMap;
	}

	public String getWorkingDir() {
		return workingDir;
	}

	public Map<String, String> getVolumeMounts() {
		return volumeMounts;
	}

}
