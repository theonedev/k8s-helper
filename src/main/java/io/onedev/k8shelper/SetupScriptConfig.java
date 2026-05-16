package io.onedev.k8shelper;

import java.io.Serializable;

public class SetupScriptConfig implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String setupCommands;

	private final String scriptExtension;

	private final String scriptExecutable;

	private final String[] scriptOptions;

	public SetupScriptConfig(String setupCommands, String scriptExtension,
			String scriptExecutable, String[] scriptOptions) {
		this.setupCommands = setupCommands;
		this.scriptExtension = scriptExtension;
		this.scriptExecutable = scriptExecutable;
		this.scriptOptions = scriptOptions;
	}

	public String getSetupCommands() {
		return setupCommands;
	}

	public String getScriptExtension() {
		return scriptExtension;
	}

	public String getScriptExecutable() {
		return scriptExecutable;
	}

	public String[] getScriptOptions() {
		return scriptOptions;
	}

}
