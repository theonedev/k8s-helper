package io.onedev.k8shelper;

import static io.onedev.k8shelper.KubernetesHelper.GIT_TRUST_ALL_DIRS;
import static io.onedev.k8shelper.KubernetesHelper.buildRestClient;
import static io.onedev.k8shelper.KubernetesHelper.buildSSLFactory;
import static io.onedev.k8shelper.KubernetesHelper.changeOwner;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.initRepository;
import static io.onedev.k8shelper.KubernetesHelper.installGitLfs;
import static io.onedev.k8shelper.KubernetesHelper.newCacheProvisioner;
import static io.onedev.k8shelper.KubernetesHelper.newErrorLogger;
import static io.onedev.k8shelper.KubernetesHelper.newInfoLogger;
import static io.onedev.k8shelper.KubernetesHelper.readInt;
import static io.onedev.k8shelper.KubernetesHelper.setupGitCerts;
import static io.onedev.k8shelper.KubernetesHelper.setupOriginUrl;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static org.apache.commons.lang3.SerializationUtils.deserialize;
import static org.apache.commons.lang3.SerializationUtils.serialize;
import static org.glassfish.jersey.client.ClientProperties.REQUEST_ENTITY_PROCESSING;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import nl.altindag.ssl.SSLFactory;

public class WorkspaceHelper {

	public static final String ENV_WORKSPACE_TOKEN = "ONEDEV_WORKSPACE_TOKEN";
	
	public static final String ENV_RUNAS = "ONEDEV_RUNAS";

	public static final String ENV_ACCESS_TOKEN = "ONEDEV_ACCESS_TOKEN";

	public static final String ENV_TRUST_CERTS_FILE = "ONEDEV_TRUST_CERTS_FILE";

	public static final String ENV_WORKDIR = "ONEDEV_WORKDIR";

	public static final String WORKSPACE_PATH = "/onedev-workspace";

	public static final String SHUTDOWN_FILE = ".shutdown";

	public static final String CONTAINER_READY_FILE = ".container-ready";

	public static final String CONTAINER_READY_MESSAGE = "===== OneDev Workspace Container Ready =====";

	public static final String INIT_INFO_FILE = ".init-info";

	public static final String ENV_TERM = "TERM";

	private static final Logger logger = LoggerFactory.getLogger(WorkspaceHelper.class);

	private static File getWorkspaceDir() {
		return new File(WORKSPACE_PATH);
	}

	private static File getWorkDir() {
		return new File(getWorkspaceDir(), "work");
	}

	public static File getTrustCertsDir() {
		return new File(getWorkspaceDir(), "trust-certs");
	} 

	public static Map<String, String> buildEnvVars(
			Map<String, String> customEnvVars, String serverUrl, String accessToken, 
			@Nullable String trustCertsFilePath, String workDir) {
		var envVars = new HashMap<String, String>();
		if (customEnvVars != null)
			envVars.putAll(customEnvVars);
		envVars.put(ENV_TERM, "xterm-256color");
		envVars.put("LANG", "C.UTF-8");
		envVars.put(KubernetesHelper.ENV_SERVER_URL, serverUrl);
		envVars.put(ENV_ACCESS_TOKEN, accessToken);
		envVars.put(ENV_WORKDIR, workDir);
		if (trustCertsFilePath != null)
			envVars.put(ENV_TRUST_CERTS_FILE, trustCertsFilePath);
		return envVars;
	}

	public static String buildEntrypointArgs(@Nullable SetupScriptConfig setupScriptConfig, 
				boolean indicateSuccessfulViaFile) {
		var entrypointArgs = new StringBuilder(GIT_TRUST_ALL_DIRS);
		if (setupScriptConfig != null) {
			var containerScriptPath = WORKSPACE_PATH + "/setup" + setupScriptConfig.getScriptExtension();
			entrypointArgs
					.append(" && cd ")
					.append(WORKSPACE_PATH).append("/work")
					.append(" && echo Running setup commands... && ")
					.append(setupScriptConfig.getScriptExecutable());
			for (var option : setupScriptConfig.getScriptOptions())
				entrypointArgs.append(" ").append(option);
			entrypointArgs
					.append(" ")
					.append(containerScriptPath)
					.append(getReadyMarker(indicateSuccessfulViaFile));
			entrypointArgs.append(" || exit 1");
		} else {
			entrypointArgs.append(getReadyMarker(indicateSuccessfulViaFile));
		}
		entrypointArgs.append(" && tail -f /dev/null");
		return entrypointArgs.toString();
	}

	private static String getReadyMarker(boolean indicateSuccessfulViaFile) {
		if (indicateSuccessfulViaFile) {
			return new StringBuilder()
					.append(" && touch ")
					.append(WORKSPACE_PATH).append("/").append(CONTAINER_READY_FILE)
					.append(" && chmod +r ")
					.append(WORKSPACE_PATH).append("/").append(CONTAINER_READY_FILE)
					.toString();
		} else {
			return new StringBuilder()
					.append(" && echo " + CONTAINER_READY_MESSAGE)
					.toString();
		}
	}

	private static File getInitInfoFile() {
		return new File(getWorkspaceDir(), INIT_INFO_FILE);
	}
 
	private static TaskLogger newInfoTaskLogger() {	
		return new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				logger.info(message);
			}

		};

	}

	public static void init(String serverUrl, String workspaceToken, String runAs) {
		FileUtils.createDir(getWorkDir());
		FileUtils.deleteFile(getShutdownFile());

		SSLFactory sslFactory = buildSSLFactory(getTrustCertsDir());

		KubernetesWorkspaceData workspaceData = downloadWorkspaceData(serverUrl, workspaceToken, sslFactory);

		var cloneInfo = workspaceData.getCloneInfo();
		var cloneUrl = cloneInfo.getCloneUrl();
		setupRepository(getWorkspaceDir(), new Commandline("git"), 
				workspaceData.getUserName(), workspaceData.getUserEmail(), cloneInfo, workspaceData.getCommitHash(), 
				workspaceData.getBranch(), workspaceData.isRetrieveLfs(), getTrustCertsDir(), 
				WORKSPACE_PATH, cloneUrl, newInfoLogger(), newErrorLogger());

		new ConfigFileProvisioner(workspaceData.getConfigFiles()).provision(getWorkspaceDir(), newInfoTaskLogger());

		var cacheProvisioners = new ArrayList<CacheProvisioner>();
		for (var config : workspaceData.getCacheConfigs()) {
			var cacheProvisioner = newCacheProvisioner(serverUrl, "~api/worker/workspace-cache", 
					workspaceToken, config, getTrustCertsDir(), cacheProvisioners.size() + 1);
			cacheProvisioner.download(getWorkspaceDir(), newInfoTaskLogger());
			cacheProvisioners.add(cacheProvisioner);
		}

		var userDataProvisioner = newUserDataProvisioner(
				serverUrl, workspaceToken, workspaceData.getUserDatas());
		userDataProvisioner.download(getWorkspaceDir(), newInfoTaskLogger());

		if (workspaceData.getSetupScriptConfig() != null)
			writeSetupScript(getWorkspaceDir(), workspaceData.getSetupScriptConfig());

		writeInitInfo(new InitInfo(cacheProvisioners, userDataProvisioner));

		if (!runAs.equals("0:0"))
			changeOwner(getWorkspaceDir(), runAs);

		logger.info("Workspace initialized");
	}

	private static File getShutdownFile() {
		return new File(getWorkspaceDir(), SHUTDOWN_FILE);
	}

	public static void sidecar(String serverUrl, String workspaceToken) {
		File shutdownFile = getShutdownFile();
		while (!shutdownFile.exists()) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		InitInfo initInfo = readInitInfo();
		if (initInfo != null) {
			initInfo.userDataProvisioner.upload(getWorkspaceDir(), newInfoTaskLogger());

			for (var cacheProvisioner : initInfo.cacheProvisioners) {
				cacheProvisioner.upload(getWorkspaceDir(), newInfoTaskLogger());
			}
		}
	}

	private static KubernetesWorkspaceData downloadWorkspaceData(String serverUrl, String workspaceToken,
														   SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/worker/workspace-data")
					.queryParam("token", workspaceToken);
			Invocation.Builder builder = target.request();

			logger.info("Retrieving workspace data from {}...", serverUrl);
			byte[] dataBytes;
			try (Response response = builder.get()) {
				checkStatus(response);
				dataBytes = response.readEntity(byte[].class);
			}
			return deserialize(dataBytes);
		} finally {
			client.close();
		}
	}

	public static void setupRepository(File workspaceDir, Commandline git, String userName,
			String userEmail, CloneInfo cloneInfo, String commitHash, @Nullable String branch, 
			boolean retrieveLfs, File trustCertsDir, String runtimeWorkspaceDirPath, String fetchUrl,
			LineConsumer infoLogger, LineConsumer warningLogger) {
		infoLogger.consume("Initializing workspace git repository...");

		var workDir = new File(workspaceDir, "work");
		FileUtils.createDir(workDir);
		git.workingDir(workDir);

		initRepository(git, infoLogger, warningLogger);

		git.args("-c", "safe.directory=*", "config", "user.name", userName);
		git.execute(infoLogger, warningLogger).checkReturnCode();
		git.args("-c", "safe.directory=*", "config", "pull.rebase", "false");
		git.execute(infoLogger, warningLogger).checkReturnCode();
		git.args("-c", "safe.directory=*", "config", "user.email", userEmail);
		git.execute(infoLogger, warningLogger).checkReturnCode();

		var noCommits = new AtomicBoolean(false);
		git.args("-c", "safe.directory=*", "status");
		git.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("No commits yet")) {
					noCommits.set(true);
				} else if (!line.startsWith("On branch") && line.trim().length() != 0) {
					infoLogger.consume(line);
				}
			}

		}, warningLogger).checkReturnCode();

		git.clearArgs();
		var trustCertsFile = new File(workspaceDir, "trust-certs.pem");
		setupGitCerts(git, trustCertsDir, trustCertsFile,
				runtimeWorkspaceDirPath + "/" + trustCertsFile.getName(),
				infoLogger, warningLogger);
		cloneInfo.setupGitAuth(git, workspaceDir, runtimeWorkspaceDirPath,
				infoLogger, warningLogger);

		var remoteUrl = cloneInfo.getCloneUrl();
		if (noCommits.get()) {
			infoLogger.consume("Cloning repository...");
			cloneRepository(git, fetchUrl, remoteUrl, branch, commitHash,
					retrieveLfs, false, 0, infoLogger, warningLogger);
		} else {
			setupOriginUrl(git, remoteUrl, infoLogger, warningLogger);
			if (retrieveLfs)
				installGitLfs(git, infoLogger, warningLogger);
			infoLogger.consume("Repository already exists, skipping clone");
		}
	}

	public static void writeSetupScript(File workspaceDir, SetupScriptConfig setupScriptConfig) {
		File scriptFile = new File(workspaceDir, "setup" + setupScriptConfig.getScriptExtension());
		try {
			FileUtils.writeStringToFile(scriptFile, setupScriptConfig.getSetupCommands(), UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static boolean downloadUserData(String serverUrl, String apiPath, String token,
				String key, String path, File pathFile, @Nullable SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path(apiPath)
					.queryParam("token", token)
					.queryParam("key", key)
					.queryParam("path", path);
			Invocation.Builder builder = target.request();
			try (Response response = builder.get()) {
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					boolean dataAvailable = readInt(is) != 0;
					if (dataAvailable)
						TarUtils.untar(is, pathFile, false);
					return dataAvailable;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			client.close();
		}
	}

	public static void uploadUserData(String serverUrl, String apiPath, String token,
				String key, String path, File pathDir, List<String> excludes, @Nullable SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		client.property(REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path(apiPath)
					.queryParam("token", token)
					.queryParam("key", key)
					.queryParam("path", path);
			Invocation.Builder builder = target.request();
			StreamingOutput output = os -> TarUtils.tar(pathDir, excludes, os, false);
			try (Response response = builder.post(Entity.entity(output, APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
			}
		} finally {
			client.close();
		}
	}

	public static void notifyUserDataUploaded(String serverUrl, String apiPath, String token,
				String key, @Nullable SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path(apiPath)
					.queryParam("token", token)
					.queryParam("key", key);
			Invocation.Builder builder = target.request();
			try (Response response = builder.put(Entity.entity(new byte[0], APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
			}
		} finally {
			client.close();
		}
	}

	private static UserDataProvisioner newUserDataProvisioner(
			String serverUrl, String workspaceToken, List<UserDataFacade> userDatas) {
		return new UserDataProvisioner(userDatas) {

			private static final String API_PATH = "~api/worker/workspace-user-data";

			@Override
			protected void download(String key, String path, File pathFile) {
				var sslFactory = KubernetesHelper.buildSSLFactory(WorkspaceHelper.getTrustCertsDir());
				downloadUserData(serverUrl, API_PATH, workspaceToken, key, path, pathFile, sslFactory);
			}

			@Override
			protected void upload(String key, String path, File pathFile, List<String> excludes) {
				var sslFactory = KubernetesHelper.buildSSLFactory(WorkspaceHelper.getTrustCertsDir());
				uploadUserData(serverUrl, API_PATH, workspaceToken, key, path, pathFile, excludes, sslFactory);
			}

			@Override
			protected void notifyUploaded(String key) {
				var sslFactory = KubernetesHelper.buildSSLFactory(WorkspaceHelper.getTrustCertsDir());
				notifyUserDataUploaded(serverUrl, API_PATH, workspaceToken, key, sslFactory);
			}

		};
	}

	private static void writeInitInfo(InitInfo initInfo) {
		try {
			FileUtils.writeByteArrayToFile(getInitInfoFile(), serialize(initInfo));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Nullable
	private static InitInfo readInitInfo() {
		File file = getInitInfoFile();
		if (!file.exists())
			return null;
		try {
			byte[] bytes = org.apache.commons.io.FileUtils.readFileToByteArray(file);
			return deserialize(bytes);
		} catch (IOException e) {
			throw ExceptionUtils.unchecked(e);
		}
	}

	private static class InitInfo implements Serializable {

		private static final long serialVersionUID = 1L;

		final List<CacheProvisioner> cacheProvisioners;

		final UserDataProvisioner userDataProvisioner;

		public InitInfo(List<CacheProvisioner> cacheProvisioners, UserDataProvisioner userDataProvisioner) {
			this.cacheProvisioners = cacheProvisioners;
			this.userDataProvisioner = userDataProvisioner;
		}

	}

}
