package io.onedev.k8shelper;

import java.io.Serializable;

public class OsMatcher implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final OsMatcher ALL = new OsMatcher(".*", false, ".*", false, ".*", false);
	
	public static final OsMatcher WINDOWS = new OsMatcher("(?i).*windows.*", false, ".*", false, ".*", false);
	
	public static final OsMatcher NON_WINDOWS = new OsMatcher("(?i).*windows.*", true, ".*", false, ".*", false);
	
	private final String osNamePattern;
	
	private final boolean negativeOsNameMatch;
	
	private final String osVersionPattern;
	
	private final boolean negativeOsVersionMatch;
	
	private final String osArchPattern;
	
	private final boolean negativeOsArchMatch;
	
	public OsMatcher(String osNamePattern, boolean negativeOsNameMatch, String osVersionPattern, 
			boolean negativeOsVersionMatch, String osArchPattern, boolean negativeOsArchMatch) {
		this.osNamePattern= osNamePattern;
		this.negativeOsNameMatch = negativeOsNameMatch;
		this.osVersionPattern = osVersionPattern;
		this.negativeOsVersionMatch = negativeOsVersionMatch;
		this.osArchPattern = osArchPattern;
		this.negativeOsArchMatch = negativeOsArchMatch;
	}
	
	public OsMatcher(String osNamePattern, String osVersionPattern, String osArchPattern) {
		this(osNamePattern, false, osVersionPattern, false, osArchPattern, false);
	}
	
	public boolean match(OsInfo osInfo) {
		return (!negativeOsNameMatch && osInfo.getOsName().matches(osNamePattern) 
						|| negativeOsNameMatch && !osInfo.getOsName().matches(osNamePattern)) 
				&& (!negativeOsVersionMatch && osInfo.getOsVersion().matches(osVersionPattern) 
						|| negativeOsVersionMatch && !osInfo.getOsVersion().matches(osVersionPattern)) 
				&& (!negativeOsArchMatch && osInfo.getOsArch().matches(osArchPattern) 
						|| negativeOsArchMatch && !osInfo.getOsArch().matches(osArchPattern));
	}
	
}
