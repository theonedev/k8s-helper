package io.onedev.k8shelper;

public class CheckoutExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private final int cloneDepth;
	
	private final CloneInfo cloneInfo;
	
	public CheckoutExecutable(int cloneDepth, CloneInfo cloneInfo) {
		this.cloneDepth = cloneDepth;
		this.cloneInfo = cloneInfo;
	}

	public int getCloneDepth() {
		return cloneDepth;
	}

	public CloneInfo getCloneInfo() {
		return cloneInfo;
	}

}
