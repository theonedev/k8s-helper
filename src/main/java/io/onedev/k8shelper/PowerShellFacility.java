package io.onedev.k8shelper;

public class PowerShellFacility extends ShellFacility {

	private static final long serialVersionUID = 1L;

	private final String powershell;

	public PowerShellFacility(String powershell) {
		this.powershell = powershell;
	}

	@Override
	public String getExecutable() {
		return powershell;
	}

	@Override
	public String[] getScriptOptions() {
		return new String[] { "-executionpolicy", "remotesigned", "-file" };
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
