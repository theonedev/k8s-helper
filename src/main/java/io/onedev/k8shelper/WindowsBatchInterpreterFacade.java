package io.onedev.k8shelper;

public class WindowsBatchInterpreterFacade extends InterpreterFacade {

	private static final long serialVersionUID = 1L;

	public WindowsBatchInterpreterFacade(String commands) {
		super(commands);
	}

	@Override
	public ShellFacility getShellFacility() {
		return new WindowsBatchFacility();
	}

	@Override
	protected String getPauseInvokeCommand() {
		return "cmd /c \"%ONEDEV_WORKDIR%\\..\\pause.bat\"";
	}

}
