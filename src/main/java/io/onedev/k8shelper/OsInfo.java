package io.onedev.k8shelper;

import java.io.Serializable;

public class OsInfo implements Serializable {
	
	private static final long serialVersionUID = 1L;

	private final String osName;
	
	private final String osVersion;
	
	private final String osArch;
	
	public OsInfo(String osName, String osVersion, String osArch) {
		this.osName = osName;
		this.osVersion = osVersion;
		this.osArch = osArch;
	}

	public String getOsName() {
		return osName;
	}

	public String getOsVersion() {
		return osVersion;
	}

	public String getOsArch() {
		return osArch;
	}
		
	@Override
	public String toString() {
		return osName + " " + osVersion + " " + osArch;
	}
	
}