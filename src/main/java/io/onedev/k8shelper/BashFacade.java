package io.onedev.k8shelper;

import java.util.List;

import javax.annotation.Nullable;

import io.onedev.commons.utils.command.Commandline;

public class BashFacade extends CommandFacade {

	private static final long serialVersionUID = 1L;

	public BashFacade(@Nullable String image, List<String> commands, boolean useTTY) {
		super(image, commands, useTTY);
	}

	@Override
	public Commandline getInterpreter() {
		return new Commandline("bash");
	}

	@Override
	public String getScriptExtension() {
		return ".sh";
	}

	public String getEndOfLine() {
		return "\n";
	}
	
}
