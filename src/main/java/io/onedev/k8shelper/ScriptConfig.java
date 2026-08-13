package io.onedev.k8shelper;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;

public class ScriptConfig implements Serializable {

	private static final long serialVersionUID = 1L;

	@Nullable
	private final String setupCommands;

	@Nullable
	private final String teardownCommands;

	private final String scriptExtension;

	private final String scriptExecutable;

	private final String[] scriptOptions;

	public ScriptConfig(@Nullable String setupCommands, @Nullable String teardownCommands,
			String scriptExtension, String scriptExecutable, String[] scriptOptions) {
		this.setupCommands = setupCommands;
		this.teardownCommands = teardownCommands;
		this.scriptExtension = scriptExtension;
		this.scriptExecutable = scriptExecutable;
		this.scriptOptions = scriptOptions;
	}

	@Nullable
	public String getSetupCommands() {
		return setupCommands;
	}

	@Nullable
	public String getTeardownCommands() {
		return teardownCommands;
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
