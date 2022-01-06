package io.onedev.k8shelper;

import java.util.List;

public interface LeafVisitor<T> {

	T visit(LeafFacade executable, List<Integer> position);
	
}
