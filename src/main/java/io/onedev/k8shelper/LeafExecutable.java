package io.onedev.k8shelper;

import java.util.List;

public abstract class LeafExecutable implements Executable {

	private static final long serialVersionUID = 1L;

	@Override
	public boolean execute(LeafHandler handler, List<Integer> position) {
		return handler.execute(this, position);
	}
	
	@Override
	public void skip(LeafHandler handler, List<Integer> position) {
		handler.skip(this, position);
	}

	@Override
	public <T> T traverse(LeafVisitor<T> visitor, List<Integer> position) {
		return visitor.visit(this, position);
	}
		
}
