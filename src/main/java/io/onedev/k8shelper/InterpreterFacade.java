package io.onedev.k8shelper;

import java.io.Serializable;

public abstract class InterpreterFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String commands;

	public InterpreterFacade(String commands) {
		this.commands = commands;
	}

	public String getCommands() {	
		return commands;
	}

	public abstract ShellAccessor getShellAccessor();

	protected abstract String getPauseInvokeCommand();

}
