package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

public class JobData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String executorName;
	
	private final String commitHash;
	
	private final List<Action> actions;
	
	public JobData(String executorName, String commitHash, List<Action> actions) {
		this.executorName = executorName;
		this.commitHash = commitHash;
		this.actions = actions;
	}

	public String getExecutorName() {
		return executorName;
	}

	public String getCommitHash() {
		return commitHash;
	}

	public List<Action> getActions() {
		return actions;
	}
	
}
