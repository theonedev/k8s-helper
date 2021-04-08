package io.onedev.k8shelper;

import java.util.List;

public interface CommandHandler {

	boolean execute(CommandExecutable executable, List<Integer> position);
	
	void skip(CommandExecutable executable, List<Integer> position);
	
}
