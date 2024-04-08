package io.onedev.k8shelper;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.List;

import static io.onedev.k8shelper.ExecuteCondition.ALL_PREVIOUS_STEPS_WERE_SUCCESSFUL;
import static io.onedev.k8shelper.ExecuteCondition.ALWAYS;

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
		var allPreviousStepsWereSuccessful = true;
		for (int i = 0; i<actions.size(); i++) {
			Action action = actions.get(i);
			List<Integer> newPosition = new ArrayList<>(position);
			newPosition.add(i);
			if (action.getCondition() == ALWAYS 
					|| action.getCondition() == ALL_PREVIOUS_STEPS_WERE_SUCCESSFUL && allPreviousStepsWereSuccessful) {
				var successful = action.getExecutable().execute(handler, newPosition);
				allPreviousStepsWereSuccessful &= successful;
			} else {
				action.getExecutable().skip(handler, newPosition);
			}
		}
		return allPreviousStepsWereSuccessful;
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

	public LeafFacade getFacade(List<Integer> aPosition) {
		return traverse((facade, position) -> {
			if (position.equals(aPosition))
				return facade;
			else
				return null;
		}, new ArrayList<>());
	}
	
	public List<String> getPath(List<Integer> position) {
		Preconditions.checkArgument(!position.isEmpty());
		
		List<String> path = new ArrayList<>();
		List<Integer> positionCopy = new ArrayList<>(position);
		Action action = actions.get(positionCopy.remove(0));
		path.add(action.getName());
		if (!positionCopy.isEmpty()) {
			CompositeFacade executable = (CompositeFacade) action.getExecutable();
			path.addAll(executable.getPath(positionCopy));
		}
		return path;
	}
	
	public String getPathAsString(List<Integer> position) {
		return Joiner.on(" -> ").join(getPath(position));
	}
	
}
