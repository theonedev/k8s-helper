package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

public class JobData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String commitHash;
	
	private final List<Action> actions;
	
	public JobData(String commitHash, List<Action> actions) {
		this.commitHash = commitHash;
		this.actions = actions;
	}

	public String getCommitHash() {
		return commitHash;
	}

	public List<Action> getActions() {
		return actions;
	}
	
}
