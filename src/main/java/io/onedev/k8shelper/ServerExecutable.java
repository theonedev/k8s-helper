package io.onedev.k8shelper;

import java.util.Collection;
import java.util.Set;

public class ServerExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private transient Object step;
	
	private final Set<String> includeFiles;
	
	private final Set<String> excludeFiles;
	
	private final Collection<String> placeholders;
	
	public ServerExecutable(Object step, Set<String> includeFiles, Set<String> excludeFiles, Collection<String> placeholders) {
		this.step = step;
		this.includeFiles = includeFiles;
		this.excludeFiles = excludeFiles;
		this.placeholders = placeholders;
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

	public Collection<String> getPlaceholders() {
		return placeholders;
	}

}
