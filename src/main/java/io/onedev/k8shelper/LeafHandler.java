package io.onedev.k8shelper;

import java.util.List;

public interface LeafHandler {

	boolean execute(LeafFacade executable, List<Integer> position);
	
	void skip(LeafFacade executable, List<Integer> position);
	
}
