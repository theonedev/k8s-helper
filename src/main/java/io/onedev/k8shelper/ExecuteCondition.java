package io.onedev.k8shelper;

import io.onedev.commons.utils.StringUtils;

public enum ExecuteCondition {

	ALL_PREVIOUS_STEPS_WERE_SUCCESSFUL, ALWAYS, NEVER;

	public String getDisplayName() {
		return StringUtils.capitalize(name().replace('_', ' ').toLowerCase());
	}
	
}
