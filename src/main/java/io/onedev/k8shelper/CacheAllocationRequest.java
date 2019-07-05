package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;

public class CacheAllocationRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final Date currentTime;
	
	private final Map<CacheInstance, Date> instances;
	
	public CacheAllocationRequest(Date currentTime, Map<CacheInstance, Date> instances) {
		this.currentTime = currentTime;
		this.instances = instances;
	}

	public Date getCurrentTime() {
		return currentTime;
	}

	public Map<CacheInstance, Date> getInstances() {
		return instances;
	}
	
}
