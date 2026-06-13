package io.onedev.k8shelper;

public class WindowsBatchFacility extends ShellFacility {

	private static final long serialVersionUID = 1L;

	@Override
	public String getExecutable() {
		return "cmd";
	}

	@Override
	public String[] getScriptOptions() {
		return new String[] {"/c"};
	}

	@Override
	public String getScriptExtension() {
		return ".bat";
	}

	@Override
	public String getEndOfLine() {
		return "\r\n";
	}

}
