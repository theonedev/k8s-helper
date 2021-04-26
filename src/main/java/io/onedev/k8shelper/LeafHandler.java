package io.onedev.k8shelper;

import java.util.List;

public interface LeafHandler {

	boolean execute(LeafExecutable executable, List<Integer> position);
	
	void skip(LeafExecutable executable, List<Integer> position);
	
}
