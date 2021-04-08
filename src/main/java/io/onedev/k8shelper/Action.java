package io.onedev.k8shelper;

import java.io.Serializable;

public class Action implements Serializable {

	private static final long serialVersionUID = 1L;

	private final boolean executeAlways;
	
	private final Executable executable;
	
	public Action(boolean executeAlways, Executable executable) {
		this.executeAlways = executeAlways;
		this.executable = executable;
	}

	public boolean isExecuteAlways() {
		return executeAlways;
	}

	public Executable getExecutable() {
		return executable;
	}
	
}
