package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.StringUtils;

public class OsInfo implements Serializable {
	
	private static final long serialVersionUID = 1L;

	public static final Map<Integer, String> WINDOWS_VERSIONS = new LinkedHashMap<>();
	
	static {
		// update this according to 
		// https://docs.microsoft.com/en-us/virtualization/windowscontainers/deploy-containers/version-compatibility
		WINDOWS_VERSIONS.put(17763, "1809");
		WINDOWS_VERSIONS.put(18362, "1903");
		WINDOWS_VERSIONS.put(18363, "1909");
		WINDOWS_VERSIONS.put(19041, "2004");
		WINDOWS_VERSIONS.put(19042, "20H2");
		WINDOWS_VERSIONS.put(19043, "2004");
	}
	
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

	public boolean isLinux() {
		return osName.equals("Linux");
	}
	
	public boolean isWindows() {
		return osName.equals("Windows");
	}
	
	public int getWindowsBuild() {
		Preconditions.checkState(isWindows());
		return Integer.parseInt(StringUtils.substringAfterLast(osVersion, "."));
	}
	
	public String getCacheHome() {
		if (osName.equalsIgnoreCase("linux"))
			return "/var/cache/onedev-build"; 
		else
			return "C:\\ProgramData\\onedev-build\\cache";
	}
	
	public static OsInfo getBaseline(Collection<OsInfo> osInfos) {
		if (osInfos.iterator().next().isLinux()) {
			for (OsInfo osInfo: osInfos) {
				if (!osInfo.isLinux())
					throw new ExplicitException("Linux and non-linux nodes should not be included in same executor");
			}
			return osInfos.iterator().next();
		} else if (osInfos.iterator().next().isWindows()) {
			OsInfo baseline = null;
			for (OsInfo osInfo: osInfos) {
				if (!osInfo.isWindows())
					throw new ExplicitException("Windows and non-windows nodes should not be included in same executor");
				if (baseline == null || baseline.getWindowsBuild() > osInfo.getWindowsBuild())
					baseline = osInfo;
			}
			return baseline;
		} else {
			throw new ExplicitException("Either Windows or Linux nodes can be included in an executor");
		}
	}
	
	@Override
	public String toString() {
		return osName + " " + osVersion + " " + osArch;
	}
	
}