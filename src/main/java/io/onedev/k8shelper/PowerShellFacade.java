package io.onedev.k8shelper;

import io.onedev.commons.utils.command.Commandline;

import javax.annotation.Nullable;
import java.util.List;

public class PowerShellFacade extends CommandFacade {

	private static final long serialVersionUID = 1L;

	public PowerShellFacade(@Nullable String image, @Nullable String builtInRegistryAccessToken,
							List<String> commands, boolean useTTY) {
		super(image, builtInRegistryAccessToken, commands, useTTY);
	}

	@Override
	protected String getPauseInvokeCommand() {
		return "cmd /c $env:ONEDEV_WORKSPACE%\\..\\pause.bat";
	}

	@Override
	public Commandline getScriptInterpreter() {
		return new Commandline("powershell").addArgs("-executionpolicy", "remotesigned", "-file");
	}

	@Override
	public String[] getShell(boolean isLinux, String workingDir) {
		if (workingDir != null)
			return new String[]{"cmd", "/c", String.format("cd %s && powershell", workingDir)};
		else
			return new String[]{"powershell"};
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
