package io.onedev.k8shelper;

import java.util.ArrayList;
import java.util.List;

public class CompositeExecutable implements Executable {

	private static final long serialVersionUID = 1L;

	private final List<Action> actions;
	
	public CompositeExecutable(List<Action> actions) {
		this.actions = actions;
	}

	public List<Action> getActions() {
		return actions;
	}

	@Override
	public boolean execute(CommandHandler handler, List<Integer> position) {
		boolean failed = false;
		for (int i = 0; i<actions.size(); i++) {
			Action action = actions.get(i);
			List<Integer> newPosition = new ArrayList<>(position);
			newPosition.add(i+1);
			if (action.isExecuteAlways() || !failed) {
				if (!action.getExecutable().execute(handler, newPosition))
					failed = true;
			} else {
				action.getExecutable().skip(handler, newPosition);
			}
		}
		return !failed;
	}

	@Override
	public void skip(CommandHandler handler, List<Integer> position) {
		for (int i=0; i<actions.size(); i++) { 
			List<Integer> newPosition = new ArrayList<>(position);
			newPosition.add(i+1);
			actions.get(i).getExecutable().skip(handler, newPosition);
		}
	}

	@Override
	public <T> T traverse(CommandVisitor<T> visitor, List<Integer> position) {
		for (int i=0; i<actions.size(); i++) {
			List<Integer> newPosition = new ArrayList<>(position);
			newPosition.add(i+1);
			T result = actions.get(i).getExecutable().traverse(visitor, newPosition);
			if (result != null)
				return result;
		}
		return null;
	}
	
}
