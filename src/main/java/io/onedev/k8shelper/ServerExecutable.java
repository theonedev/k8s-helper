package io.onedev.k8shelper;

import java.util.Set;

public class ServerExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private transient Object step;
	
	private final Set<String> includeFiles;
	
	private final Set<String> excludeFiles;
	
	public ServerExecutable(Object step, Set<String> includeFiles, Set<String> excludeFiles) {
		this.step = step;
		this.includeFiles = includeFiles;
		this.excludeFiles = excludeFiles;
	}

	public Object getStep() {
		return step;
	}

	public Set<String> getIncludeFiles() {
		return includeFiles;
	}

	public Set<String> getExcludeFiles() {
		return excludeFiles;
	}

}
