package io.onedev.k8shelper;

import java.util.List;

import javax.annotation.Nullable;

import io.onedev.commons.utils.command.Commandline;

public class PowerShellFacade extends CommandFacade {

	private static final long serialVersionUID = 1L;

	public PowerShellFacade(@Nullable String image, List<String> commands, boolean useTTY) {
		super(image, commands, useTTY);
	}

	@Override
	public Commandline getInterpreter() {
		return new Commandline("powershell").addArgs("-executionpolicy", "remotesigned", "-file");
	}

	@Override
	public String getScriptExtension() {
		return ".ps1";
	}
	
	@Override
	public String getEndOfLine() {
		return "\r\n";
	}
	
}
