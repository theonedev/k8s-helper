package io.onedev.k8shelper;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;
import static javax.ws.rs.core.Response.Status.OK;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;
import static org.apache.commons.io.FileUtils.readFileToString;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.util.CertificateUtils;
import nl.altindag.ssl.util.HostnameVerifierUtils;

public class KubernetesHelper {

	private static final Logger logger = LoggerFactory.getLogger(KubernetesHelper.class);
	
	public static final String LOG_END_MESSAGE = "===== End of OneDev K8s Helper Log =====";

	public static final String IMAGE_REPO = "1dev/k8s-helper";

	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";

	public static final String AUTHORIZATION = "OneDevAuthorization";

	public static final String BEARER = "Bearer";

	public static final String BUILD_VERSION = "buildVersion";
	
	public static final String ATTRIBUTES = "attributes";
	
	public static final String PLACEHOLDER_PREFIX = "<&onedev#";
	
	public static final String PLACEHOLDER_SUFFIX = "#onedev&>";

	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(PLACEHOLDER_PREFIX + "(.*?)" + PLACEHOLDER_SUFFIX);

	private static final String ESCAPED_CR = "#![<CR>]!#";

	public static final String WORKDIR = "work";

	public static final String GIT_TRUST_ALL_DIRS = "(touch \"$HOME/.gitconfig\" "
				+ "&& (grep -q 'directory=\\*' \"$HOME/.gitconfig\" "
				+ "|| printf '[safe]\\n\\tdirectory=*\\n' >> \"$HOME/.gitconfig\"))";
	
	/**
	 * This method does two things:
	 * 1. Set up git config file to trust the certificates. This way git operations inside
	 * command build step or workspace can trust certificates without using extra options
	 * 2. Set up git command line to add arguments to trust certificates for git operations
	 * preparing git repository to be used by command build step or workspace
	 */
	public static void setupGitCerts(Commandline git, File trustCertsDir, File trustCertsFile,
			String runtimeTrustCertsFilePath, LineConsumer stdoutLogger,
			LineConsumer stderrLogger) {
		var presetArgs = new ArrayList<String>(git.args());
		if (trustCertsDir.exists()) {
			List<String> certLines = new ArrayList<>();
			for (var file : trustCertsDir.listFiles()) {
				if (file.isFile() && !file.isHidden()) {
					try {
						certLines.addAll(FileUtils.readLines(file, UTF_8));
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}

			if (!certLines.isEmpty()) {
				try {
					FileUtils.writeLines(trustCertsFile, certLines, "\n");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				runtimeTrustCertsFilePath = runtimeTrustCertsFilePath.replace("\\", "/");
				var trustCertsFilePath = trustCertsFile.getAbsolutePath().replace("\\", "/");
				git.args("-c", "safe.directory=*", "config", "http.sslCAInfo", runtimeTrustCertsFilePath);
				git.execute(stdoutLogger, stderrLogger).checkReturnCode();
				git.args(presetArgs);
				git.addArgs("-c", "http.sslCAInfo=" + trustCertsFilePath);
			}
		}
	}

	public static void checkStatus(Response response) {
		int status = response.getStatus();
		if (status != OK.getStatusCode() && status != NO_CONTENT.getStatusCode()) {
			String errorMessage = response.readEntity(String.class);
			if (StringUtils.isNotBlank(errorMessage)) {
				throw new RuntimeException(String.format("Http request failed (status code: %d, error message: %s)",
						status, errorMessage));
			} else if (status >= 500) {
				throw new RuntimeException("Http request failed with status " + status
						+ ", check server log for details");
			} else {
				throw new RuntimeException("Http request failed with status " + status);
			}
		}
	}

	public static void installGitLfs(Commandline git, LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		git.args("-c", "safe.directory=*", "lfs", "install", "--force");
		git.execute(stdoutLogger, stderrLogger).checkReturnCode();
	}

	public static void initRepository(Commandline git, LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		if (!new File(git.workingDir(), ".git").exists()) {
			git.args("-c", "safe.directory=*", "init", "-b", "main", ".");
			git.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.startsWith("Initialized empty Git repository"))
						stdoutLogger.consume(line);
				}

			}, stderrLogger).checkReturnCode();
		}
	}

	public static void setupOriginUrl(Commandline git, String remoteUrl, LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		var originExists = new AtomicBoolean(false);
		git.args("-c", "safe.directory=*", "remote", "add", "origin", remoteUrl);
		var result = git.execute(stdoutLogger, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.contains("remote origin already exists"))
					originExists.set(true);
				else
					stderrLogger.consume(line);
			}

		});

		if (originExists.get()) {
			git.args("-c", "safe.directory=*", "remote", "set-url", "origin", remoteUrl);
			result = git.execute(stdoutLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					stderrLogger.consume(line);
				}

			});
		}
		result.checkReturnCode();
		git.clearArgs();
	}

	/**
	 * The git arguments will be initialized with remote access arguments before calling this method.
	 * Also .git/config inside the git working directory should also be set up for runtime (while job
	 * or workspace runs) remote access
	 */
	public static void cloneRepository(Commandline git, String cloneUrl, String remoteUrl,
			String refName, @Nullable String commitHash, boolean withLfs, boolean withSubmodules,
			int cloneDepth, LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		var presetArgs = new ArrayList<>(git.args());

		String configContent;
		try {
			var configFile = new File(git.workingDir(), ".git/config");
			configContent = FileUtils.readFileToString(configFile, UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		git.addArgs("-c", "safe.directory=*", "fetch", cloneUrl, "--force", "--progress", "--quiet");
		if (cloneDepth != 0)
			git.addArgs("--depth=" + cloneDepth);
		git.addArgs(commitHash != null ? commitHash : refName);
		git.execute(stdoutLogger, stderrLogger).checkReturnCode();

		setupOriginUrl(git, remoteUrl, stdoutLogger, stderrLogger);

		if (withLfs)
			installGitLfs(git, stdoutLogger, stderrLogger);

		var fetched = commitHash != null ? commitHash : "FETCH_HEAD";

		git.args(presetArgs);
		git.addArgs("-c", "safe.directory=*", "checkout", "--progress", "--quiet", fetched);
		git.execute(stdoutLogger, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("Filtering content:"))
					stdoutLogger.consume(line);
				else
					stderrLogger.consume(line);
			}

		}).checkReturnCode();

		if (withSubmodules && new File(git.workingDir(), ".gitmodules").exists()) {
			// deinit submodules in case submodule url is changed
			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "submodule", "deinit", "--all", "--force", "--quiet");
			git.execute(stdoutLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.contains("error: could not lock config file") &&
							!line.contains("warning: Could not unset core.worktree setting in submodule")) {
						stderrLogger.consume(line);
					}
				}

			}).checkReturnCode();

			stdoutLogger.consume("Retrieving submodules...");

			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "submodule", "update", "--init", "--recursive", "--force", "--quiet");
			if (cloneDepth != 0)
				git.addArgs("--depth=" + cloneDepth);
			git.execute(stdoutLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Submodule") && line.contains("registered for path")
							|| line.startsWith("From ") || line.startsWith(" * branch")
							|| line.startsWith(" +") && line.contains("->")) {
						stdoutLogger.consume(line);
					} else {
						stderrLogger.consume(line);
					}
				}

			}).checkReturnCode();

			if (configContent != null) {
				var modulesDir = new File(git.workingDir(), ".git/modules");
				if (modulesDir.isDirectory())
					writeConfigToSubmodules(modulesDir, configContent);
			}
		}

		if (refName.startsWith("refs/heads/")) {
			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "update-ref", refName, fetched);
			git.execute(stdoutLogger, stderrLogger).checkReturnCode();

			String branch = refName.substring("refs/heads/".length());
			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "checkout", branch);
			git.execute(stdoutLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Switched to branch"))
						stdoutLogger.consume(line);
					else
						stderrLogger.consume(line);
				}

			}).checkReturnCode();

			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "update-ref", "refs/remotes/origin/" + branch, fetched);
			git.execute(stdoutLogger, stderrLogger).checkReturnCode();

			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "branch", "--set-upstream-to=origin/" + branch, branch);
			git.execute(stdoutLogger, stderrLogger).checkReturnCode();
		}
		git.args(presetArgs);
	}

	private static void writeConfigToSubmodules(File modulesDir, String configContent) {
		for (File child : modulesDir.listFiles()) {
			if (child.isDirectory()) {
				try {
					FileUtils.writeStringToFile(new File(child, "config"), configContent, UTF_8);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				var nestedModulesDir = new File(child, "modules");
				if (nestedModulesDir.isDirectory())
					writeConfigToSubmodules(nestedModulesDir, configContent);
			}
		}
	}

	public static SSLFactory buildSSLFactory(File trustCertsDir) {
		SSLFactory.Builder builder = SSLFactory.builder().withDefaultTrustMaterial();
		if (trustCertsDir.exists()) {
			for (var file : trustCertsDir.listFiles()) {
				if (file.isFile() && !file.isHidden()) {
					String certContent = null;
					try {
						certContent = readFileToString(file, UTF_8);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					var certificates = CertificateUtils.parsePemCertificate(certContent);
					if (!certificates.isEmpty()) {
						builder.withTrustMaterial(certificates);
					} else {
						throw new ExplicitException("Base64 encoded PEM certificate beginning with -----BEGIN CERTIFICATE----- and ending with -----END CERTIFICATE----- is expected: " + file.getAbsolutePath());
					}
				}
			}

			HostnameVerifier basicVerifier = HostnameVerifierUtils.createBasic();
			HostnameVerifier fenixVerifier = HostnameVerifierUtils.createDefault();
			builder.withHostnameVerifier((hostname, session) -> basicVerifier.verify(hostname, session) || fenixVerifier.verify(hostname, session));
		}
		return builder.build();
	}

	public static Client buildRestClient(@Nullable SSLFactory sslFactory) {
		var builder = ClientBuilder.newBuilder();
		if (sslFactory != null)
			builder.sslContext(sslFactory.getSslContext()).hostnameVerifier(sslFactory.getHostnameVerifier());
		return builder.build();
	}

	public static CacheAvailability downloadCache(String serverUrl, String apiPath, 
			String token, String key, @Nullable String checksum, String path,
			File cacheDir, @Nullable SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path(apiPath)
					.queryParam("token", token)
					.queryParam("key", key)
					.queryParam("checksum", checksum)
					.queryParam("path", path);
			Invocation.Builder builder = target.request();
			try (Response response = builder.get()) {
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					CacheAvailability availability = CacheAvailability.values()[is.read()];
					if (availability != CacheAvailability.NOT_FOUND)
						TarUtils.untar(is, cacheDir, false);
					return availability;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			client.close();
		}
	}

	public static boolean uploadCache(String serverUrl, String apiPath, String token,
			CacheConfigFacade cacheConfig, String path, File cacheDir, @Nullable SSLFactory sslFactory) {
		var key = cacheConfig.getKey();
		var checksum = cacheConfig.getChecksum();
		var projectPath = cacheConfig.getUploadProjectPath();
		Client client = buildRestClient(sslFactory);
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path(apiPath)
					.queryParam("token", token)
					.queryParam("projectPath", projectPath);
			Invocation.Builder builder = target.request();
			var accessToken = cacheConfig.getUploadAccessToken();
			if (accessToken != null)
				builder.header(AUTHORIZATION, BEARER + " " + accessToken);
			try (Response response = builder.head()) {
				if (response.getStatus() == UNAUTHORIZED.getStatusCode())
					return false;
				checkStatus(response);
			}

			builder = target
					.queryParam("key", key)
					.queryParam("checksum", checksum)
					.queryParam("path", path)
					.request();
			if (accessToken != null)
				builder.header(AUTHORIZATION, BEARER + " " + accessToken);
			StreamingOutput output = os -> TarUtils.tar(cacheDir, os, false);
			try (Response response = builder.post(entity(output, APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				return true;
			}
		} finally {
			client.close();
		}
	}

	public static void uploadCacheThenLog(String serverUrl, String apiPath, String token, CacheConfigFacade cacheConfig,
				String path, File cacheDir, @Nullable SSLFactory sslFactory) {
		if (uploadCache(serverUrl, apiPath, token, cacheConfig, path, cacheDir, sslFactory))
			logger.info("Uploaded " + cacheConfig.describeUpload(path));
		else
			logger.warn("Not authorized to upload " + cacheConfig.describeUpload(path));
	}

	public static String requireServerUrl() {
		return checkNotNull(System.getenv(ENV_SERVER_URL));
	}

	public static void logFailure(Logger logger, Throwable e) {
		logger.error(TaskLogger.wrapWithAnsiError(TaskLogger.toString(null, e)));
	}

	public static void changeOwner(File dirOrFile, String owner) {
		var chown = new Commandline("chown");
		chown.addArgs("-R", owner, dirOrFile.getAbsolutePath());
		chown.execute(new LineConsumer() {
			@Override
			public void consume(String line) {
				logger.info(line);
			}
		}, new LineConsumer() {
			@Override
			public void consume(String line) {
				logger.error(line);
			}

		}).checkReturnCode();
	}

	public static String formatDuration(long durationMillis) {
		if (durationMillis < 0)
			durationMillis = 0;
		return DurationFormatUtils.formatDurationWords(durationMillis, true, true);
	}

	public static Collection<String> parsePlaceholders(String string) {
		Collection<String> placeholderFiles = new HashSet<>();
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(string);  
        while (matcher.find())   
        	placeholderFiles.add(matcher.group(1));
		return placeholderFiles;
	}

	public static Map<String, String> readPlaceholderValues(File baseDir, Collection<String> placeholders) {
		Map<String, String> placeholderValues = new HashMap<>();
		for (String placeholder: placeholders) {
			File file = new File(baseDir, placeholder);
			if (file.exists()) {
				try {
					placeholderValues.put(placeholder, readFileToString(file, UTF_8).trim());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return placeholderValues;
	}

	public static String replacePlaceholders(String string, Map<String, String> placeholderValues) {
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(string);  
        StringBuffer buffer = new StringBuffer();  
        while (matcher.find()) {  
        	String placeholder = matcher.group(1);
        	String placeholderValue = placeholderValues.get(placeholder);
        	if (placeholderValue != null) {
        		matcher.appendReplacement(buffer, Matcher.quoteReplacement(placeholderValue));
        	} else if (placeholder.startsWith(WORKDIR + "/")) {
        		throw new ExplicitException("Error replacing placeholder: unable to find file '" 
        				+ placeholder.substring(WORKDIR.length() + 1) + "' in workdir");
        	} else if (placeholder.startsWith(ATTRIBUTES + "/")) {
        		throw new ExplicitException("Error replacing placeholder: agent attribute '" 
        				+ placeholder.substring(ATTRIBUTES.length() + 1) + "' does not exist");
        	} else if (placeholder.equals(BUILD_VERSION)){ 
        		throw new ExplicitException("Error replacing placeholder: build version not set yet");
        	}
         }  
         matcher.appendTail(buffer);  
         return buffer.toString();
	}
	
	public static String replacePlaceholders(String string, File baseDir) {
		Collection<String> placeholders = parsePlaceholders(string);
		Map<String, String> placeholderValues = readPlaceholderValues(baseDir, placeholders);
		return replacePlaceholders(string, placeholderValues);
	}
	
	public static Collection<String> replacePlaceholders(Collection<String> collection, 
			Map<String, String> placeholderValues) {
		Collection<String> replacedCollection = new ArrayList<>();
		for (String each: collection) 
			replacedCollection.add(replacePlaceholders(each, placeholderValues));
		return replacedCollection;
	}
	
	public static Collection<String> replacePlaceholders(Collection<String> collection, File baseDir) {
		Collection<String> replacedCollection = new ArrayList<>();
		for (String each: collection)
			replacedCollection.add(replacePlaceholders(each, baseDir));
		return replacedCollection;
	}

	public static String getVersion() {
		try (InputStream is = KubernetesHelper.class.getClassLoader().getResourceAsStream("k8s-helper-version.properties")) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			IOUtils.copy(is, baos);
			return baos.toString();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static CacheProvisioner newCacheProvisioner(String serverUrl, String apiPath, 
				String token, CacheConfigFacade config, File trustCertsDir, int configIndex) {
		return new CacheProvisioner(config, configIndex) {

			@Override
			protected CacheAvailability download(String key, @Nullable String checksum,
					String path, File pathDir) {
				var sslFactory = KubernetesHelper.buildSSLFactory(trustCertsDir);
				return KubernetesHelper.downloadCache(serverUrl, apiPath, token,
						key, checksum, path, pathDir, sslFactory);
			}

			@Override
			protected boolean upload(CacheConfigFacade config, String path, File pathDir) {
				var sslFactory = KubernetesHelper.buildSSLFactory(trustCertsDir);
				return KubernetesHelper.uploadCache(serverUrl, apiPath, token, config,
						path, pathDir, sslFactory);
			}
			
		};
	}

	public static int readInt(InputStream is) {
		try {
			byte[] intBytes = new byte[4];
			if (IOUtils.read(is, intBytes) != intBytes.length)
				throw new ExplicitException("Invalid input stream");
			return ByteBuffer.wrap(intBytes).getInt();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void writeInt(OutputStream os, int value) {
		try {
			os.write(ByteBuffer.allocate(4).putInt(value).array());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void writeString(OutputStream os, String value) {
		try {
			byte[] valueBytes = value.getBytes(UTF_8);
			os.write(ByteBuffer.allocate(4).putInt(valueBytes.length).array());
			os.write(valueBytes);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static String readString(InputStream is) {
		try {
			byte[] lengthBytes = new byte[4];
			if (IOUtils.read(is, lengthBytes) != lengthBytes.length)
				throw new ExplicitException("Invalid input stream");
			int length = ByteBuffer.wrap(lengthBytes).getInt();
			byte[] stringBytes = new byte[length];
			if (IOUtils.read(is, stringBytes) != stringBytes.length)
				throw new ExplicitException("Invalid input stream");
			return new String(stringBytes, UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static String escapeStartCR(String line, Function<String, String> messageTransformer) {
		if (line.startsWith("\r"))
			line = ESCAPED_CR + messageTransformer.apply(line.substring(1));
		return line;
	}

	public static String unescapeStartCR(String line) {
		if (line.startsWith(ESCAPED_CR))
			line = "\r" + line.substring(ESCAPED_CR.length());
		return line;
	}

	static LineConsumer newInfoLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				System.out.println(escapeStartCR(line, Function.identity()));
			}

		};
	}

	static LineConsumer newErrorLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				System.out.println(escapeStartCR(line, TaskLogger::wrapWithAnsiWarning));
			}

		};
	}
	
}
