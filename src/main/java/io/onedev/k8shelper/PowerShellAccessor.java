package io.onedev.k8shelper;

import io.onedev.commons.utils.command.Commandline;

public class PowerShellAccessor extends ShellAccessor {

	private static final long serialVersionUID = 1L;

	private final String powershell;

	public PowerShellAccessor(String powershell) {
		this.powershell = powershell;
	}

	@Override
	public String getExecutable() {
		return powershell;
	}

	@Override
	public Commandline buildScriptCmdline() {
		return new Commandline(powershell).addArgs("-executionpolicy", "remotesigned", "-file");
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
