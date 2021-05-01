package io.onedev.k8shelper;

import java.io.Serializable;

public class Action implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String name;
	
	private final ExecuteCondition condition;
	
	private final Executable executable;
	
	public Action(String name, Executable executable, ExecuteCondition condition) {
		this.name = name;
		this.executable = executable;
		this.condition = condition;
	}

	public String getName() {
		return name;
	}

	public Executable getExecutable() {
		return executable;
	}

	public ExecuteCondition getCondition() {
		return condition;
	}
	
}
