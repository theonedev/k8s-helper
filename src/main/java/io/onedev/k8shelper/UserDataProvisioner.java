package io.onedev.k8shelper;

import static io.onedev.commons.utils.StringUtils.parseQuoteTokens;

import java.io.File;
import java.io.Serializable;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;

public abstract class UserDataProvisioner implements Serializable {

	private static final long serialVersionUID = 1L;

	protected static final String USER_DATA_DIR = "user-data";

	protected final List<UserDataFacade> userDatas;

	protected final Map<String, Integer> pathIndexes;
	
	private Date provisionDate;

	public UserDataProvisioner(List<UserDataFacade> userDatas) {
		this.userDatas = userDatas;
		pathIndexes = new HashMap<String, Integer>();
		var pathIndex = 1;
		for (var userData : userDatas) {
			for (var entry : userData.getEntries()) 
				pathIndexes.put(entry.getPath(), pathIndex++);
		}
	}

	public Map<String, Integer> getPathIndexes() {
		return pathIndexes;
	}

	public File getPathFile(File workspaceDir, int pathIndex) {
		return new File(workspaceDir, USER_DATA_DIR + "/" + pathIndex);
	}

	public String getSubPath(int pathIndex) {
		return USER_DATA_DIR + "/" + pathIndex;
	}

	public void download(File workspaceDir, TaskLogger logger) {
		for (var userData : userDatas) {
			var key = userData.getKey();
			logger.log("Downloading user data '" + key + "'...");
			for (var entry: userData.getEntries()) {
				var path = entry.getPath();
				var pathIndex = Preconditions.checkNotNull(pathIndexes.get(path));
				var pathFile = getPathFile(workspaceDir, pathIndex);
				if (!pathFile.exists())
					download(key, path, pathFile);
			}
		}
		provisionDate = new Date();
	}

	public void upload(File workspaceDir, TaskLogger logger) {
		for (var userData : userDatas) {
			var key = userData.getKey();
			logger.log("Uploading user data '" + key + "'...");
			var uploaded = false;
			for (var entry : userData.getEntries()) {
				var path = entry.getPath();
				
				var excludes = Arrays.asList(parseQuoteTokens(entry.getExcludes()));				

				var pathIndex = Preconditions.checkNotNull(pathIndexes.get(path));
				var pathFile = getPathFile(workspaceDir, pathIndex);
				if (!pathFile.exists())
					continue;
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
		for (var entry: pathIndexes.entrySet()) {
			var pathFile = getPathFile(workspaceDir, entry.getValue());
			docker.addArgs("-v", hostPathResolver.apply(pathFile.getAbsolutePath()) + ":" + entry.getKey());
		}
	}

}
