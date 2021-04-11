package io.onedev.k8shelper;

import java.io.Serializable;

public class Action implements Serializable {

	private static final long serialVersionUID = 1L;

	private final ExecuteCondition condition;
	
	private final Executable executable;
	
	public Action(Executable executable, ExecuteCondition condition) {
		this.executable = executable;
		this.condition = condition;
	}

	public Executable getExecutable() {
		return executable;
	}

	public ExecuteCondition getCondition() {
		return condition;
	}
	
}
