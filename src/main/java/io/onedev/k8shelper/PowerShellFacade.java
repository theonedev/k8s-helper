package io.onedev.k8shelper;

import io.onedev.commons.utils.command.Commandline;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

public class PowerShellFacade extends CommandFacade {

	private static final long serialVersionUID = 1L;

	private final String powershell;

	public PowerShellFacade(@Nullable String image, @Nullable String runAs, List<RegistryLoginFacade> registryLogins,
							String powershell, String commands, Map<String, String> envMap, boolean useTTY) {
		super(image, runAs, registryLogins, commands, envMap, useTTY);
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
