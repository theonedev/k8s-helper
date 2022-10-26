package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.common.base.Splitter;

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

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append(currentTime.getTime()).append(";");
		for (Map.Entry<CacheInstance, Date> entry: instances.entrySet()) 
			builder.append(entry.getKey().toString()).append(":").append(entry.getValue().getTime()).append(";");
		return builder.toString();
	}

	public static CacheAllocationRequest fromString(String string) {
		int index = string.indexOf(';');
		Date currentTime = new Date(Long.parseLong(string.substring(0, index)));
		Map<CacheInstance, Date> instances = new LinkedHashMap<>();
		for (String entry: Splitter.on(';').omitEmptyStrings().split(string.substring(index+1))) {
			index = entry.indexOf(':');
			instances.put(
					CacheInstance.fromString(entry.substring(0, index)), 
					new Date(Long.parseLong(entry.substring(index+1))));
		}
		return new CacheAllocationRequest(currentTime, instances);
	}
	
}
