package io.onedev.k8shelper;

import java.util.List;

import javax.annotation.Nullable;

import io.onedev.commons.utils.command.Commandline;

public class PowerShellExecutable extends CommandExecutable {

	private static final long serialVersionUID = 1L;

	public PowerShellExecutable(@Nullable String image, List<String> commands, boolean useTTY) {
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
	
	public String getEndOfLine() {
		return "\r\n";
	}
	
}
