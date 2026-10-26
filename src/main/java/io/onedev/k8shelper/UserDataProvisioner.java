package io.onedev.k8shelper;

import static io.onedev.commons.utils.StringUtils.parseQuoteTokens;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;

public abstract class UserDataProvisioner implements Serializable {

	private static final long serialVersionUID = 1L;

	protected static final String USER_DATA_DIR = "user-data";

	protected final List<UserDataFacade> userDatas;

	private final Map<String, Date> provisionDates = new HashMap<>();

	public UserDataProvisioner(List<UserDataFacade> userDatas) {
		this.userDatas = userDatas;
	}

	public File getPathFile(File workspaceDir, String key, String path) {
		var pathFile = new File(workspaceDir, getDataPath(key, path));
		if (FileUtils.hasSymbolLinks(workspaceDir, pathFile))
			throw new ExplicitException("User data path does not allow to contain symbol links: " + pathFile);
		return pathFile;
	}

	private String getUserDataDirName(String key, String path) {
		try {
			var name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest((key + "\0" + path).getBytes(UTF_8)));
			return USER_DATA_DIR + "/" + name;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public String getDataPath(String key, String path) {
		return getUserDataDirName(key, path) + "/data";
	}

	public String getStatePath(String key, String path) {
		return getUserDataDirName(key, path) + "/state";
	}

	private File getStateFile(File workspaceDir, String key, String path) {
		var stateFile = new File(workspaceDir, getStatePath(key, path));
		if (FileUtils.hasSymbolLinks(workspaceDir, stateFile))
			throw new ExplicitException("User data state path does not allow to contain symbol links: " + stateFile);
		if (stateFile.exists() && !stateFile.isFile())
			throw new ExplicitException("User data state should be a file: " + stateFile);
		return stateFile;
	}

	public void download(File workspaceDir, TaskLogger logger) {
		provisionDates.clear();
		for (var userData : userDatas) {
			var key = userData.getKey();
			var downloadLogged = false;
			for (var entry: userData.getEntries()) {
				var path = entry.getPath();
				var pathFile = getPathFile(workspaceDir, key, path);
				var stateFile = getStateFile(workspaceDir, key, path);
				if (!pathFile.exists()) {
					FileUtils.deletePath(stateFile);
					if (!downloadLogged) {
						logger.log("Downloading user data '" + key + "'...");
						downloadLogged = true;
					}
					download(key, path, pathFile);
					// Keep the restore baseline across activations without putting metadata inside user data.
					FileUtils.touchFile(stateFile);
				}
				if (stateFile.exists())
					provisionDates.put(getDataPath(key, path), new Date(stateFile.lastModified()));
			}
		}
	}

	public void upload(File workspaceDir, TaskLogger logger) {
		for (var userData : userDatas) {
			var key = userData.getKey();
			logger.log("Uploading user data '" + key + "'...");
			var uploaded = false;
			for (var entry : userData.getEntries()) {
				var path = entry.getPath();
				
				var excludes = Arrays.asList(parseQuoteTokens(entry.getExcludes()));				

				var pathFile = getPathFile(workspaceDir, key, path);
				if (!pathFile.exists())
					continue;
				var provisionDate = provisionDates.get(getDataPath(key, path));
				var changed = false;
				if (provisionDate == null) 
					changed = true;
				else if (pathFile.isDirectory()) 
					changed = FileUtils.hasChangedFiles(pathFile, provisionDate, excludes);
				else 
					changed = pathFile.lastModified() > provisionDate.getTime();
				
				if (changed) {
					logger.log(MessageFormat.format("User data changed (key: {0}, path: {1}), storing", key, path));
					upload(key, path, pathFile, excludes);
					uploaded = true;
				}
			}
			if (uploaded)
				notifyUploaded(key);
		}
	}

	protected abstract void download(String key, String path, File pathFile);

	protected abstract void upload(String key, String path, File pathFile, List<String> excludes);

	protected abstract void notifyUploaded(String key);

	public void mountVolumes(Commandline docker, File workspaceDir, Function<String, String> hostPathResolver) {
		for (var userData : userDatas) {
			for (var entry : userData.getEntries()) {
				var pathFile = getPathFile(workspaceDir, userData.getKey(), entry.getPath());
				docker.addArgs("-v", hostPathResolver.apply(pathFile.getAbsolutePath()) + ":" + entry.getPath());
			}
		}
	}

}
