package io.onedev.k8shelper;

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;

import static io.onedev.k8shelper.ExecuteCondition.*;

public class CompositeFacade implements StepFacade {

	private static final long serialVersionUID = 1L;

	private final List<Action> actions;
	
	public CompositeFacade(List<Action> actions) {
		this.actions = actions;
	}

	public List<Action> getActions() {
		return actions;
	}

	@Override
	public boolean execute(LeafHandler handler, List<Integer> position) {
		boolean failed = false;
		for (int i = 0; i<actions.size(); i++) {
			Action action = actions.get(i);
			List<Integer> newPosition = new ArrayList<>(position);
			newPosition.add(i);
			if (action.getCondition() == ALWAYS 
					|| action.getCondition() == ALL_PREVIOUS_STEPS_WERE_SUCCESSFUL && !failed ) {
				if (!action.getExecutable().execute(handler, newPosition))
					failed = true;
			} else {
				action.getExecutable().skip(handler, newPosition);
			}
		}
		return !failed;
	}

	@Override
	public void skip(LeafHandler handler, List<Integer> position) {
		for (int i=0; i<actions.size(); i++) { 
			List<Integer> newPosition = new ArrayList<>(position);
			newPosition.add(i);
			actions.get(i).getExecutable().skip(handler, newPosition);
		}
	}

	@Override
	public <T> T traverse(LeafVisitor<T> visitor, List<Integer> position) {
		for (int i=0; i<actions.size(); i++) {
			List<Integer> newPosition = new ArrayList<>(position);
			newPosition.add(i);
			T result = actions.get(i).getExecutable().traverse(visitor, newPosition);
			if (result != null)
				return result;
		}
		return null;
	}
	
	public List<String> getNames(List<Integer> position) {
		Preconditions.checkArgument(!position.isEmpty());
		
		List<String> names = new ArrayList<>();
		List<Integer> positionCopy = new ArrayList<>(position);
		Action action = actions.get(positionCopy.remove(0));
		names.add(action.getName());
		if (!positionCopy.isEmpty()) {
			CompositeFacade executable = (CompositeFacade) action.getExecutable();
			names.addAll(executable.getNames(positionCopy));
		}
		return names;
	}
	
	public String getNamesAsString(List<Integer> position) {
		return Joiner.on(" -> ").join(getNames(position));
	}
	
}
