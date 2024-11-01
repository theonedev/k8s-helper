package io.onedev.k8shelper;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.*;

public class RegistryLoginFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private static final String OFFICIAL_REGISTRY_URL = "https://index.docker.io/v1/";

	private final String registryUrl;
	
	private final String userName;
	
	private final String password;
	
	public RegistryLoginFacade(@Nullable String registryUrl, String userName, String password) {
		if (registryUrl != null)
			this.registryUrl = registryUrl;
		else
			this.registryUrl = OFFICIAL_REGISTRY_URL;
		this.userName = userName;
		this.password = password;
	}

	public String getRegistryUrl() {
		return registryUrl;
	}

	public String getUserName() {
		return userName;
	}

	public String getPassword() {
		return password;
	}

	public static List<RegistryLoginFacade> merge(Collection<RegistryLoginFacade> highPriority,
												  Collection<RegistryLoginFacade> lowPriority) {
		Map<String, RegistryLoginFacade> merged = new LinkedHashMap<>();
		for (var each: lowPriority)
			merged.put(each.getRegistryUrl(), each);
		for (var each: highPriority)
			merged.put(each.getRegistryUrl(), each);
		return new ArrayList<>(merged.values());
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof RegistryLoginFacade))
			return false;
		if (this == other)
			return true;
		var otherLogin = (RegistryLoginFacade) other;
		return new EqualsBuilder()
				.append(registryUrl, otherLogin.registryUrl)
				.append(userName, otherLogin.userName)
				.append(password, otherLogin.password)
				.isEquals();
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder(17, 37)
				.append(registryUrl)
				.append(userName)
				.append(password)
				.toHashCode();
	}

}
