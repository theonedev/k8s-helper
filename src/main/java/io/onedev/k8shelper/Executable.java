package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

public interface Executable extends Serializable {

	boolean execute(CommandHandler handler, List<Integer> position);
	
	void skip(CommandHandler handler, List<Integer> position);
	
	<T> T traverse(CommandVisitor<T> visitor, List<Integer> position);
	
}
