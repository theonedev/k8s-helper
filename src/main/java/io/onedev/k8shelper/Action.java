package io.onedev.k8shelper;

import java.io.Serializable;

public class Action implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String name;
	
	private final ExecuteCondition condition;

	private final boolean optional;
	
	private final StepFacade executable;
	
	public Action(String name, StepFacade executable, ExecuteCondition condition, boolean optional) {
		this.name = name;
		this.executable = executable;
		this.condition = condition;
		this.optional = optional;
	}

	public String getName() {
		return name;
	}

	public boolean isOptional() {
		return optional;
	}

	public StepFacade getExecutable() {
		return executable;
	}

	public ExecuteCondition getCondition() {
		return condition;
	}
	
}
