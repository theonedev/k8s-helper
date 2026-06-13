package io.onedev.k8shelper;

public class PosixFacility extends ShellFacility {

	private static final long serialVersionUID = 1L;

	private final String shell;

	public PosixFacility() {
		this("sh");
	}

	public PosixFacility(String shell) {
		this.shell = shell;
	}

	@Override
	public String getExecutable() {
		return shell;
	}

	@Override
	public String[] getScriptOptions() {
		return new String[0];
	}

	@Override
	public String getScriptExtension() {
		return ".sh";
	}

	@Override
	public String getEndOfLine() {
		return "\n";
	}

}
