package io.onedev.k8shelper;

import static io.onedev.commons.utils.FileUtils.hasSymbolLinks;
import static io.onedev.k8shelper.KubernetesHelper.GIT_TRUST_ALL_DIRS;
import static io.onedev.k8shelper.KubernetesHelper.LOG_END_MESSAGE;
import static io.onedev.k8shelper.KubernetesHelper.WORKDIR;
import static io.onedev.k8shelper.KubernetesHelper.buildRestClient;
import static io.onedev.k8shelper.KubernetesHelper.buildSSLFactory;
import static io.onedev.k8shelper.KubernetesHelper.changeOwner;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.initRepository;
import static io.onedev.k8shelper.KubernetesHelper.newCacheProvisioner;
import static io.onedev.k8shelper.KubernetesHelper.newErrorLogger;
import static io.onedev.k8shelper.KubernetesHelper.newInfoLogger;
import static io.onedev.k8shelper.KubernetesHelper.readInt;
import static io.onedev.k8shelper.KubernetesHelper.readPlaceholderValues;
import static io.onedev.k8shelper.KubernetesHelper.readString;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.KubernetesHelper.setupGitCerts;
import static io.onedev.k8shelper.KubernetesHelper.writeInt;
import static io.onedev.k8shelper.KubernetesHelper.writeString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.Base64.getEncoder;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.io.FileUtils.readFileToByteArray;
import static org.apache.commons.io.FileUtils.readFileToString;
import static org.apache.commons.io.FileUtils.writeByteArrayToFile;
import static org.apache.commons.lang3.SerializationUtils.deserialize;
import static org.apache.commons.lang3.SerializationUtils.serialize;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.FilenameUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import nl.altindag.ssl.SSLFactory;

public class JobHelper {

	public static final String STEP_EVENT_PREFIX = "##onedev-step:";

	public static final String PHASE_PREFIX = "##onedev-phase:";

	public static final String INITIALIZATION = "initialization";

	public static final String FINALIZATION = "finalization";

	public static final Pattern STEP_ID_PATTERN = Pattern.compile("step-(0|[1-9][0-9]*)(-(0|[1-9][0-9]*))*");

	public enum StepEventKind { START, SUCCESSFUL, FAILED, CANCELLED, SKIP }

	public record StepEvent(StepEventKind kind, String step) {
	}

	public static String buildStepEventMessage(StepEventKind kind, String step) {
		return STEP_EVENT_PREFIX + kind + ":" + step;
	}

	@Nullable
	public static StepEvent parseStepEventMessage(String message) {
		if (!message.startsWith(STEP_EVENT_PREFIX))
			return null;
		var parts = message.substring(STEP_EVENT_PREFIX.length()).split(":", -1);
		if (parts.length != 2 || !STEP_ID_PATTERN.matcher(parts[1]).matches())
			return null;
		try {
			var kind = StepEventKind.valueOf(parts[0]);
			parseStepPosition(parts[1].substring("step-".length()));
			return new StepEvent(kind, parts[1]);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static String buildStepStartMessage(List<Integer> position) {
		return buildStepEventMessage(StepEventKind.START, "step-" + stringifyStepPosition(position));
	}

	public static String buildStepEndMessage(List<Integer> position, StepEventKind kind) {
		if (kind != StepEventKind.SUCCESSFUL && kind != StepEventKind.FAILED && kind != StepEventKind.CANCELLED)
			throw new IllegalArgumentException("Not a step outcome: " + kind);
		return buildStepEventMessage(kind, "step-" + stringifyStepPosition(position));
	}

	public static String buildStepSkipMessage(List<Integer> position) {
		return buildStepEventMessage(StepEventKind.SKIP, "step-" + stringifyStepPosition(position));
	}

	public static final String ENV_JOB_TOKEN = "ONEDEV_JOB_TOKEN";
		
	public static final String PAUSE = "pause";

	public static final String BUILD_PATH = "/onedev-build";

	private static final Logger logger = LoggerFactory.getLogger(JobHelper.class);
	
	private static File getBuildDir() {
		return new File(BUILD_PATH);
	}
	
	/**
	 * Resolve a path accessed by trusted code after a build container has run.
	 * The build directory itself is trusted, but its entries may have been replaced
	 * with symbolic links (including dangling links) by a previous step.
	 */
	public static File resolveBuildPath(File buildDir, String path) {
		if (path.contains("..") || FilenameUtils.getPrefixLength(path) != 0)
			throw new ExplicitException("Build path must be relative and must not contain '..': " + path);
		var file = new File(buildDir, path);
		if (Files.isSymbolicLink(buildDir.toPath()) || hasSymbolLinks(buildDir, file))
			throw new ExplicitException("Build path must not contain symbolic links: " + path);
		return file;
	}

	public static void resumeJob(File buildDir) {
		try {
			// createNewFile is atomic and never follows an existing link. The paused
			// container can still change this entry after resolveBuildPath checks it.
			resolveBuildPath(buildDir, "continue").createNewFile();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static File getJobDataFile() {
		return resolveBuildPath(getBuildDir(), "job-data");
	}
	
	private static File getTrustCertsDir() {
		return resolveBuildPath(getBuildDir(), "trust-certs");
	}
	
	private static File getWorkDir() {
		return new File(getBuildDir(), WORKDIR);
	}
	
	private static File getCommandDir() {
		return resolveBuildPath(getBuildDir(), "command");
	}
	
	private static File getMarkDir() {
		return resolveBuildPath(getBuildDir(), "mark");
	}
	
	private static TaskLogger newInfoTaskLogger() {
		return new TaskLogger() {
	
			@Override
			public void log(String message, String sessionId) {
				logger.info(message);
			}

		};
	}

	/** Returns a shell command that writes a step marker to the container log. */
	private static String logStepEvent(String eventMessage) {
		// Begin on a fresh line even when the command output has no trailing newline.
		return "  printf '\\n%s\\n' '" + eventMessage.replace("'", "'\\''") + "'";
	}

	private static void generateCommandScript(List<Integer> position,
			CommandFacade commandFacade, File workingDir) {
		try {
			String positionStr = stringifyStepPosition(position);
			File commandDir = getCommandDir();
			File stepScriptFile = new File(commandDir, "step-" + positionStr + commandFacade.getScriptExtension());
			FileUtils.writeStringToFile(stepScriptFile, commandFacade.normalizeCommands(commandFacade.getCommands()), UTF_8);

			File scriptFile = new File(commandDir, positionStr + ".sh");
			String markPrefix = getMarkDir().getAbsolutePath() + "/" + positionStr;
			List<String> wrapperScriptContent = Lists.newArrayList(
					"while [ ! -f " + markPrefix + ".start ] && [ ! -f " + markPrefix + ".skip ] && [ ! -f " + markPrefix + ".error ]",
					"do",
					"  sleep 0.1",
					"done",
					"if [ -f " + markPrefix + ".skip ]",
					"then",
					logStepEvent(buildStepSkipMessage(position)),
					"  echo " + LOG_END_MESSAGE,
					"  exit 0",
					"fi",
					logStepEvent(buildStepStartMessage(position)),
					"if [ -f " + markPrefix + ".error ]",
					"then",
					"  cat " + markPrefix + ".error",
					"  touch " + markPrefix + ".failed",
					logStepEvent(buildStepEndMessage(position, StepEventKind.FAILED)),
					"  echo " + LOG_END_MESSAGE,
					"  exit 0",
					"fi",
					"cd " + "'" + workingDir.getAbsolutePath() + "'",
					GIT_TRUST_ALL_DIRS,
					commandFacade.getExecutable() + " " + stream(commandFacade.getScriptOptions()).map(it -> it + " ").collect(joining()) + stepScriptFile.getAbsolutePath(),

					"exitCode=\"$?\"",
					"if [ $exitCode -eq 0 ]",
					"then",
					logStepEvent(buildStepEndMessage(position, StepEventKind.SUCCESSFUL)),
					"  touch " + markPrefix + ".successful",
					"else",
					"  echo \"" + TaskLogger.wrapWithAnsiError("Command exited with code $exitCode") + "\"",
					logStepEvent(buildStepEndMessage(position, StepEventKind.FAILED)),
					"  touch " + markPrefix + ".failed",
					"fi",
					"echo " + LOG_END_MESSAGE,
					"exit 0");
			FileUtils.writeLines(scriptFile, wrapperScriptContent, "\n");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static void init(String serverUrl, String jobToken) {
		SSLFactory sslFactory = buildSSLFactory(getTrustCertsDir());
		FileUtils.createDir(getCommandDir());
		FileUtils.createDir(getMarkDir());
		KubernetesJobData jobData;
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/worker/job-data")
					.queryParam("token", jobToken)
					.queryParam("workDir", getWorkDir().getAbsolutePath());
			Invocation.Builder builder =  target.request();

			logger.info("Retrieving job data from {}...", serverUrl);
			
			byte[] jobDataBytes;
			try (Response response = builder.get()) {
				checkStatus(response);
				jobDataBytes = response.readEntity(byte[].class);
			}
			
			FileUtils.writeByteArrayToFile(getJobDataFile(), jobDataBytes);
			jobData = deserialize(jobDataBytes);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			client.close();
		}
		
		File workDir = getWorkDir();
		FileUtils.createDir(workDir);
		
		logger.info("Generating command scripts...");
		
		CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
		entryFacade.traverse((LeafVisitor<Void>) (facade, position) -> {
			String positionStr = stringifyStepPosition(position);

			File workingDir = getWorkDir();
			CommandFacade commandFacade;
			if (facade instanceof CommandFacade) {
				commandFacade = (CommandFacade) facade;
			} else {
				String command;
				String classPath = "/k8s-helper/*";
				if (facade instanceof CheckoutFacade) {
					CheckoutFacade checkoutFacade = (CheckoutFacade) facade;
					command = String.format("java -classpath \"%s\" io.onedev.k8shelper.CheckoutCode %s %b %b %d %s",
							classPath, positionStr, checkoutFacade.isWithLfs(), checkoutFacade.isWithSubmodules(),
							checkoutFacade.getCloneDepth(), checkoutFacade.getCloneInfo().toString());
					if (checkoutFacade.getCheckoutPath() != null) {
						byte[] bytes = checkoutFacade.getCheckoutPath().getBytes(UTF_8);
						command += " " + getEncoder().encodeToString(bytes);
					}
				} else if (facade instanceof SetupCacheFacade) {
					command = String.format("java -classpath \"%s\" io.onedev.k8shelper.SetupJobCache %s",
							classPath, positionStr);
				} else {
					command = String.format("java -classpath \"%s\" io.onedev.k8shelper.RunServerSideStep %s",
							classPath, positionStr);
				}

				var commandsBuilder = new StringBuilder();
				commandsBuilder.append(command).append("\n");

				commandFacade = new CommandFacade("any", "0:0", new ArrayList<>(), new HashMap<>(), true, commandsBuilder.toString());
			}

			generateCommandScript(position, commandFacade, workingDir);

			return null;
		}, new ArrayList<>());
		
		logger.info("Downloading job dependencies from {}...", serverUrl);
		
		downloadDependencies(serverUrl, jobToken, workDir, sslFactory);
		logger.info("Job working directory initialized");
	}
	
	public static String stringifyStepPosition(List<Integer> stepPosition) {
		return StringUtils.join(stepPosition, "-");
	}
	
	public static List<Integer> parseStepPosition(String stepPosition) {
		return Splitter.on('-').splitToList(stepPosition)
				.stream()
				.map(it->Integer.parseInt(it))
				.collect(toList());
	}
	
	private static KubernetesJobData readJobData() {
		byte[] jobDataBytes;
		try {
			jobDataBytes = readFileToByteArray(getJobDataFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return deserialize(jobDataBytes);
	}
	
	public static boolean sidecar(String serverUrl, String jobToken) {
		LeafHandler commandHandler = new LeafHandler() {

			@Override
			public boolean execute(LeafFacade facade, List<Integer> position) {
				String positionStr = stringifyStepPosition(position);
				File file;
				File stepScriptFile = null;
				for (File eachFile: getCommandDir().listFiles()) {
					if (eachFile.getName().startsWith("step-" + positionStr + ".")) {
						stepScriptFile = resolveBuildPath(getBuildDir(), "command/" + eachFile.getName());
						break;
					}
				}
				Preconditions.checkState(stepScriptFile != null);

				try {
					String stepScript = readFileToString(stepScriptFile, UTF_8);

					if (facade instanceof CommandFacade) {
						CommandFacade commandFacade = (CommandFacade) facade;
						commandFacade.generatePauseCommand(getBuildDir());
						if (!commandFacade.getRunAs().equals("0:0"))
							changeOwner(getBuildDir(), commandFacade.getRunAs(), true);
					}

					stepScript = replacePlaceholders(stepScript, getBuildDir());
					FileUtils.writeFile(stepScriptFile, stepScript, UTF_8);
					
					file = resolveBuildPath(getBuildDir(), "mark/" + positionStr + ".start");
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (Exception e) {
					file = resolveBuildPath(getBuildDir(), "mark/" + positionStr + ".error");

					ExplicitException explicitException = ExceptionUtils.find(e, ExplicitException.class);
					String errorMessage;
					if (explicitException != null)
						errorMessage = explicitException.getMessage().trim();
					else 
						errorMessage = Throwables.getStackTraceAsString(e).trim();
					errorMessage += "\n";
					FileUtils.writeFile(file, errorMessage, UTF_8);
				}
			
				File successfulFile = resolveBuildPath(getBuildDir(), "mark/" + positionStr + ".successful");
				File failedFile = resolveBuildPath(getBuildDir(), "mark/" + positionStr + ".failed");
				while (!successfulFile.exists() && !failedFile.exists()) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
				return successfulFile.exists();
			}

			@Override
			public void skip(LeafFacade facade, List<Integer> position) {
				File file = resolveBuildPath(getBuildDir(), "mark/" + stringifyStepPosition(position) + ".skip");
				try {
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			
		};
		
		var jobData = readJobData();
		var actions = jobData.getActions();

		var successful = new CompositeFacade(actions).execute(commandHandler, new ArrayList<>());

		if (successful) {
			var cacheProvisioners = readCacheProvisioners();
			for (var cacheProvisioner : cacheProvisioners) {
				cacheProvisioner.upload(getBuildDir(), newInfoTaskLogger());
			}
		}
		return successful;
	}
		
	static void checkoutCode(String serverUrl, String jobToken, String positionStr,
			boolean withLfs, boolean withSubmodules, int cloneDepth, CloneInfo cloneInfo, 
			@Nullable String checkoutPath) throws IOException {
		KubernetesJobData jobData = readJobData();
		
		logger.info("Checking out code from {}...", cloneInfo.getCloneUrl());

		LineConsumer infoLogger = newInfoLogger();
		LineConsumer errorLogger = newErrorLogger();
		
		File workDir = getWorkDir();
		Commandline git = new Commandline("git");
		new CheckoutFacade(cloneDepth, withLfs, withSubmodules, cloneInfo, checkoutPath)
				.setupWorkingDir(git, workDir);

		initRepository(git, infoLogger, errorLogger);
		
		git.clearArgs();
		File trustCertsFile = new File(getBuildDir(), "trust-certs.pem");
		setupGitCerts(git, getTrustCertsDir(), trustCertsFile,
				trustCertsFile.getAbsolutePath(), infoLogger, errorLogger);

		var buildDir = getBuildDir();
		cloneInfo.setupGitAuth(git, buildDir, buildDir.getAbsolutePath(), infoLogger, errorLogger);

		cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(), 
				KubernetesHelper.ref2branch(jobData.getRefName()), jobData.getCommitHash(), 
				withLfs, withSubmodules, cloneDepth, infoLogger, errorLogger);
	}

	static boolean runServerStep(String serverUrl, String jobToken, String positionStr) {
		return runServerStep(buildSSLFactory(getTrustCertsDir()), serverUrl, jobToken, positionStr);
	}

	private static boolean runServerStep(SSLFactory sslFactory, String serverUrl,
										 String jobToken, String positionStr) {
		List<Integer> position = parseStepPosition(positionStr);
		var actions = readJobData().getActions();
		var serverSideFacade = (ServerSideFacade) new CompositeFacade(actions).getFacade(position);
		TaskLogger logger = newInfoTaskLogger();
		return runServerStep(sslFactory, serverUrl, jobToken, position, serverSideFacade, getBuildDir(), logger);
	}

	public static boolean runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
										List<Integer> position, ServerSideFacade serverSideFacade,
										File buildDir, TaskLogger logger) {
		Map<String, String> placeholderValues = readPlaceholderValues(buildDir, serverSideFacade.getPlaceholders());
		File baseDir = serverSideFacade.getSourceDir(buildDir, placeholderValues);
		
		var includeFiles = replacePlaceholders(serverSideFacade.getIncludeFiles(), placeholderValues);
		var excludeFiles = replacePlaceholders(serverSideFacade.getExcludeFiles(), placeholderValues);
		
		var result = runServerStep(sslFactory, serverUrl, jobToken, position, baseDir,
				includeFiles, excludeFiles, placeholderValues, logger);
		result.writeOutputFiles(buildDir);
		return result.isSuccessful();
	}
	
	public static ServerStepResult runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
												 List<Integer> position, File baseDir,
												 Collection<String> includeFiles, Collection<String> excludeFiles,
												 Map<String, String> placeholderValues, TaskLogger logger) {
		if (Files.isSymbolicLink(baseDir.toPath()))
			throw new ExplicitException("Source directory does not allow symbolic links: " + baseDir);
		Client client = buildRestClient(sslFactory);
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/worker/run-server-step")
					.queryParam("token", jobToken);
			Invocation.Builder builder = target.request();

			StreamingOutput output = os -> {
				writeInt(os, position.size());
				for (int each : position)
					writeInt(os, each);

				writeInt(os, placeholderValues.size());
				for (Map.Entry<String, String> entry : placeholderValues.entrySet()) {
					writeString(os, entry.getKey());
					writeString(os, entry.getValue());
				}

				TarUtils.tar(baseDir, includeFiles, excludeFiles, os, false, false);
			};

			try (Response response = builder.post(Entity.entity(output, MediaType.APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					while (readInt(is) == 1) {
						logger.log(readString(is));
					}
					byte[] bytes = new byte[readInt(is)];
					IOUtils.readFully(is, bytes);
					return deserialize(bytes);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			client.close();
		}
	}

	private static File getCacheProvisionersFile() {
		return resolveBuildPath(getBuildDir(), "mark/cache-provisioners");
	}

	private static void writeCacheProvisioners(List<CacheProvisioner> cacheProvisioners) {
		try {
			writeByteArrayToFile(getCacheProvisionersFile(), serialize((Serializable) cacheProvisioners));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static List<CacheProvisioner> readCacheProvisioners() {
		var file = getCacheProvisionersFile();
		if (file.exists()) {
			try {
				return deserialize(readFileToByteArray(file));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			return new ArrayList<>();
		}
	}

	private static int getCacheConfigIndex(CompositeFacade entryFacade, List<Integer> position) {
		var index = new AtomicInteger(1);
		var cacheConfigIndex = entryFacade.traverse((facade, eachPosition) -> {
			if (facade instanceof SetupCacheFacade) {
				int currentIndex = index.getAndIncrement();
				if (eachPosition.equals(position))
					return currentIndex;
			}
			return null;
		}, new ArrayList<>());
		return Preconditions.checkNotNull(cacheConfigIndex);
	}

	static void setupCache(String serverUrl, String jobToken, String positionStr) {
		var position = parseStepPosition(positionStr);
		var entryFacade = new CompositeFacade(readJobData().getActions());
		var cacheConfig = ((SetupCacheFacade) entryFacade.getFacade(position)).getCacheConfig();
		var cacheProvisioners = readCacheProvisioners();
		var cacheProvisioner = newCacheProvisioner(serverUrl, "~api/worker/job-cache", 
				jobToken, cacheConfig, getTrustCertsDir(), getCacheConfigIndex(entryFacade, position));
		cacheProvisioner.download(getBuildDir(), new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				JobHelper.logger.info(message);
			}

		});
		cacheProvisioners.add(cacheProvisioner);
		writeCacheProvisioners(cacheProvisioners);
	}

	public static void downloadDependencies(String serverUrl, String jobToken,
											File targetDir, SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/worker/dependencies")
					.queryParam("token", jobToken);
			Invocation.Builder builder =  target.request();
			try (Response response = builder.get()){
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					TarUtils.untar(is, targetDir, false);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			client.close();
		}
	}

	public static void logEndMessage(Logger logger) {
		logger.info(LOG_END_MESSAGE);
	}

}
