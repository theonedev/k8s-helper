package io.onedev.k8shelper;

public class ShellFacade extends InterpreterFacade {

	private static final long serialVersionUID = 1L;

	private final String shell;

	public ShellFacade(String commands, String shell) {
		super(commands);
		this.shell = shell;
	}

	@Override
	public ShellAccessor getShellAccessor() {
		return new LinuxShellAccessor(shell);
	}

	@Override
	protected String getPauseInvokeCommand() {
		return "sh $ONEDEV_WORKDIR/../pause.sh";
	}

}
