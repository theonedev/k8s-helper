package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

public interface StepFacade extends Serializable {

	boolean execute(LeafHandler handler, List<Integer> position);
	
	void skip(LeafHandler handler, List<Integer> position);
	
	<T> T traverse(LeafVisitor<T> visitor, List<Integer> position);
	
}
