package io.onedev.k8shelper;

import static org.junit.Assert.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

public class CacheAllocationRequestTest {

	@Test
	public void testStringConversion() {
		Map<CacheInstance, Date> instances = new HashMap<>();
		instances.put(new CacheInstance("maven-cache", UUID.randomUUID().toString()), new Date());
		instances.put(new CacheInstance("maven-cache", UUID.randomUUID().toString()), new Date());
		instances.put(new CacheInstance("npm-cache", UUID.randomUUID().toString()), new Date());
		instances.put(new CacheInstance("npm-cache", UUID.randomUUID().toString()), new Date());
		
		CacheAllocationRequest request = new CacheAllocationRequest(new Date(), instances);
		assertEquals(request.toString(), CacheAllocationRequest.fromString(request.toString()).toString());
	}

}
