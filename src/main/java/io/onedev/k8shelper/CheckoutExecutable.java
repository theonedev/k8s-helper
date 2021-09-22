package io.onedev.k8shelper;

public class CheckoutExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private final int cloneDepth;
	
	private final boolean withLfs;
	
	private final boolean withSubmodules;
	
	private final CloneInfo cloneInfo;
	
	public CheckoutExecutable(int cloneDepth, boolean withLfs, boolean withSubmodules, CloneInfo cloneInfo) {
		this.cloneDepth = cloneDepth;
		this.withLfs = withLfs;
		this.withSubmodules = withSubmodules;
		this.cloneInfo = cloneInfo;
	}

	public boolean isWithLfs() {
		return withLfs;
	}

	public boolean isWithSubmodules() {
		return withSubmodules;
	}

	public int getCloneDepth() {
		return cloneDepth;
	}

	public CloneInfo getCloneInfo() {
		return cloneInfo;
	}

}
