package io.onedev.k8shelper;

import java.io.Serializable;

public class Action implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String name;
	
	private final ExecuteCondition condition;
	
	private final StepFacade executable;
	
	public Action(String name, StepFacade executable, ExecuteCondition condition) {
		this.name = name;
		this.executable = executable;
		this.condition = condition;
	}

	public String getName() {
		return name;
	}

	public StepFacade getExecutable() {
		return executable;
	}

	public ExecuteCondition getCondition() {
		return condition;
	}
	
}
