package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

public class ServerExecutionResult implements Serializable {

	private static final long serialVersionUID = 1L;

	private final List<String> logMessages;
	
	private final Map<String, byte[]> outputFiles;
	
	public ServerExecutionResult(List<String> logMessages, @Nullable Map<String, byte[]> outputFiles) {
		this.logMessages = logMessages;
		this.outputFiles = outputFiles;
	}

	public List<String> getLogMessages() {
		return logMessages;
	}

	@Nullable
	public Map<String, byte[]> getOutputFiles() {
		return outputFiles;
	}
	
}
