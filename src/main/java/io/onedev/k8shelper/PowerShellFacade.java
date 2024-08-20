package io.onedev.k8shelper;

import io.onedev.commons.utils.command.Commandline;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;

public class PowerShellFacade extends CommandFacade {

	private static final long serialVersionUID = 1L;

	private final String powershell;

	public PowerShellFacade(@Nullable String image, @Nullable String runAs, @Nullable String builtInRegistryAccessToken,
							String powershell, String commands, boolean useTTY) {
		super(image, runAs, builtInRegistryAccessToken, commands, new HashMap<>(), useTTY);
		this.powershell = powershell;
	}

	@Override
	protected String getPauseInvokeCommand() {
		return "cmd /c $env:ONEDEV_WORKSPACE%\\..\\pause.bat";
	}

	@Override
	public Commandline getScriptInterpreter() {
		return new Commandline(powershell).addArgs("-executionpolicy", "remotesigned", "-file");
	}

	@Override
	public String[] getShell(boolean isLinux, String workingDir) {
		if (workingDir != null)
			return new String[]{"cmd", "/c", String.format("cd %s && %s", workingDir, powershell)};
		else
			return new String[]{powershell};
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
