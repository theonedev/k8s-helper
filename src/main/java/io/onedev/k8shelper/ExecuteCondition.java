package io.onedev.k8shelper;

import io.onedev.commons.utils.StringUtils;

public enum ExecuteCondition {

	ALWAYS, NEVER, PREVIOUS_WAS_SUCCESSFUL;

	public String getDisplayName() {
		return StringUtils.capitalize(name().replace('_', ' ').toLowerCase());
	}
	
}
