package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

public class UserDataFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String key;

	private final List<UserDataEntryFacade> entries;

	public UserDataFacade(String key, List<UserDataEntryFacade> entries) {
		this.key = key;
		this.entries = entries;
	}

	public String getKey() {
		return key;
	}

	public List<UserDataEntryFacade> getEntries() {
		return entries;
	}

}