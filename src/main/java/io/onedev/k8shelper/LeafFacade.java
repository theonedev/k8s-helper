package io.onedev.k8shelper;

import java.util.ArrayList;
import java.util.List;

public abstract class LeafFacade implements StepFacade {

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
		
	public static LeafFacade of(List<Action> actions, List<Integer> stepPosition) {
		StepFacade entryExecutable = new CompositeFacade(actions);
		
		LeafVisitor<LeafFacade> visitor = (executable, position) -> {
			if (position.equals(stepPosition))
				return executable;
			else
				return null;
		};
		
		return entryExecutable.traverse(visitor, new ArrayList<>());
	}
	
}
