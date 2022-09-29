package io.onedev.k8shelper;

import java.util.List;

public class ContainerConfig {

	private final List<String> entrypoint;
	
	private final List<String> cmd;
	
	public ContainerConfig(List<String> entrypoint, List<String> cmd) {
		this.entrypoint = entrypoint;
		this.cmd = cmd;
	}

	public List<String> getEntrypoint() {
		return entrypoint;
	}

	public List<String> getCmd() {
		return cmd;
	}

}
