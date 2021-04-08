package io.onedev.k8shelper;

import java.util.List;

public interface CommandVisitor<T> {

	T visit(CommandExecutable executable, List<Integer> position);
	
}
