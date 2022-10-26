package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

public class K8sJobData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String executorName;
	
	private final String refName;
	
	private final String commitHash;
	
	private final List<Action> actions;
	
	public K8sJobData(String executorName, String refName, String commitHash, List<Action> actions) {
		this.executorName = executorName;
		this.refName = refName;
		this.commitHash = commitHash;
		this.actions = actions;
	}

	public String getExecutorName() {
		return executorName;
	}

	public String getRefName() {
		return refName;
	}

	public String getCommitHash() {
		return commitHash;
	}

	public List<Action> getActions() {
		return actions;
	}
	
}
