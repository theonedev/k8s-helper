package io.onedev.k8shelper;

public class PosixInterpreterFacade extends InterpreterFacade {

	private static final long serialVersionUID = 1L;

	private final String shell;

	public PosixInterpreterFacade(String commands) {
		this(commands, "sh");
	}

	public PosixInterpreterFacade(String commands, String shell) {
		super(commands);
		this.shell = shell;
	}

	@Override
	public ShellFacility getShellFacility() {
		return new PosixFacility(shell);
	}

	@Override
	protected String getPauseInvokeCommand() {
		return "sh $ONEDEV_WORKDIR/../pause.sh";
	}

}
