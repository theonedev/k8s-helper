package io.onedev.k8shelper;

public class PowerShellFacade extends InterpreterFacade {

	private static final long serialVersionUID = 1L;

	private final String powershell;

	public PowerShellFacade(String commands, String powershell) {
		super(commands);
		this.powershell = powershell;
	}

	@Override
	public ShellAccessor getShellAccessor() {
		return new PowerShellAccessor(powershell);
	}

	@Override
	protected String getPauseInvokeCommand() {
		return "cmd /c $env:ONEDEV_WORKDIR%\\..\\pause.bat";
	}

}
