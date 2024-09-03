package io.onedev.k8shelper;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import io.onedev.commons.utils.*;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.util.CertificateUtils;
import nl.altindag.ssl.util.HostnameVerifierUtils;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.ws.rs.client.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.onedev.commons.utils.TarUtils.untar;
import static io.onedev.k8shelper.CacheHelper.tar;
import static io.onedev.k8shelper.CacheHelper.untar;
import static io.onedev.k8shelper.SetupCacheFacade.UploadStrategy.UPLOAD_IF_NOT_HIT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getEncoder;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static javax.ws.rs.core.Response.Status.*;
import static org.apache.commons.io.FileUtils.*;
import static org.apache.commons.lang3.SerializationUtils.deserialize;
import static org.apache.commons.lang3.SerializationUtils.serialize;

public class KubernetesHelper {

	public static final String IMAGE_REPO = "1dev/k8s-helper";
	
	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";
	
	public static final String ENV_JOB_TOKEN = "ONEDEV_JOB_TOKEN";

	public static final String AUTHORIZATION = "OneDevAuthorization";

	public static final String BEARER = "Bearer";
	
	public static final String LOG_END_MESSAGE = "===== End of OneDev K8s Helper Log =====";

	public static final String BUILD_VERSION = "buildVersion";
	
	public static final String PAUSE = "pause";
	
	public static final String WORKSPACE = "workspace";
	
	public static final String ATTRIBUTES = "attributes";

	public static final String PLACEHOLDER_PREFIX = "<&onedev#";
	
	public static final String PLACEHOLDER_SUFFIX = "#onedev&>";
	
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(PLACEHOLDER_PREFIX + "(.*?)" + PLACEHOLDER_SUFFIX);

	private static final Logger logger = LoggerFactory.getLogger(KubernetesHelper.class);
	
	private static File getBuildHome() {
		return new File("/onedev-build");
	}
	
	private static File getJobDataFile() {
		return new File(getBuildHome(), "job-data");
	}
	
	private static File getTrustCertsDir() {
		return new File(getBuildHome(), "trust-certs");
	}
	
	private static File getWorkspace() {
		return new File(getBuildHome(), WORKSPACE);
	}

	private static File getUserDir() {
		return new File(getBuildHome(), "user");
	}
	
	private static File getCommandDir() {
		return new File(getBuildHome(), "command");
	}
	
	private static File getMarkDir() {
		return new File(getBuildHome(), "mark");
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

	private static void generateCommandScript(List<Integer> position, String stepPath,
			CommandFacade commandFacade, @Nullable File workingDir) {
		try {
			String positionStr = stringifyStepPosition(position);
			File commandHome = getCommandDir();
			File stepScriptFile = new File(commandHome, "step-" + positionStr + commandFacade.getScriptExtension());
			FileUtils.writeStringToFile(stepScriptFile, commandFacade.normalizeCommands(commandFacade.getCommands()), UTF_8);

			String escapedStepPath = stepPath.replace("'", "'\\''");

			File scriptFile = new File(commandHome, positionStr + ".sh");
			String markPrefix = getMarkDir().getAbsolutePath() + "/" + positionStr;
			List<String> wrapperScriptContent = Lists.newArrayList(
					"initialWorkingDir=$(pwd)",
					"while [ ! -f " + markPrefix + ".start ] && [ ! -f " + markPrefix + ".skip ] && [ ! -f " + markPrefix + ".error ]",
					"do",
					"  sleep 0.1",
					"done",
					"if [ -f " + markPrefix + ".skip ]",
					"then",
					"  echo '" + TaskLogger.wrapWithAnsiNotice("Step \"" + escapedStepPath + "\" is skipped") + "'",
					"  echo " + LOG_END_MESSAGE,
					"  exit 0",
					"fi",
					"if [ -f " + markPrefix + ".error ]",
					"then",
					"  echo '" + TaskLogger.wrapWithAnsiNotice("Running step \"" + escapedStepPath + "\"...") + "'",
					"  cat " + markPrefix + ".error",
					"  touch " + markPrefix + ".failed",
					"  echo " + LOG_END_MESSAGE,
					"  exit 0",
					"fi",
					"cd " + (workingDir!=null? "'" + workingDir.getAbsolutePath() + "'": "$initialWorkingDir")
							+ " && test -w $HOME && cp -r -f -p /onedev-build/user/. $HOME || export HOME=/onedev-build/user"
							+ " && echo '" + TaskLogger.wrapWithAnsiNotice("Running step \"" + escapedStepPath + "\"...") + "'"
							+ " && " + commandFacade.getScriptInterpreter() + " " + stepScriptFile.getAbsolutePath(),
					"exitCode=\"$?\"",
					"if [ $exitCode -eq 0 ]",
					"then",
					"  echo '" + TaskLogger.wrapWithAnsiSuccess("Step \"" + escapedStepPath + "\" is successful") + "'",
					"  touch " + markPrefix + ".successful",
					"else",
					"  echo \"" + TaskLogger.wrapWithAnsiError("Command exited with code $exitCode") + "\"",
					"  echo '" + TaskLogger.wrapWithAnsiError("Step \"" + escapedStepPath + "\" is failed") + "'",
					"  touch " + markPrefix + ".failed",
					"fi",
					"echo " + LOG_END_MESSAGE,
					"exit 0");
			FileUtils.writeLines(scriptFile, wrapperScriptContent, "\n");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static LineConsumer newInfoLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.info(line);
			}
			
		};
	}
	
	private static LineConsumer newErrorLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.error(TaskLogger.wrapWithAnsiError(line));
			}
			
		};
	}

	public static void installGitCert(Commandline git, File trustCertsDir, File trustCertsFile,
									  String sslCAInfoPath, LineConsumer infoLogger, LineConsumer errorLogger) {
		if (trustCertsDir.exists()) {
			List<String> certLines = new ArrayList<>();
			for (var file: trustCertsDir.listFiles()) {
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
				git.addArgs("config", "--global", "http.sslCAInfo", sslCAInfoPath);
				git.execute(infoLogger, errorLogger).checkReturnCode();
				git.clearArgs();
			}
		}
	}
	
	public static void init(String serverUrl, String jobToken, boolean test) {
		SSLFactory sslFactory = buildSSLFactory(getTrustCertsDir());
		try {
			FileUtils.createDir(getCommandDir());
			FileUtils.createDir(getMarkDir());
			FileUtils.createDir(getUserDir());
			if (test) {
				logger.info("Connecting to server '{}'...", serverUrl);
				Client client = buildRestClient(sslFactory);
				try {
					WebTarget target = client.target(serverUrl)
							.path("~api/k8s/test")
							.queryParam("jobToken", jobToken);
					Invocation.Builder builder =  target.request();
					try (Response response = builder.get()) {
						checkStatus(response);
					} 
				} finally {
					client.close();
				}
				FileUtils.createDir(getWorkspace());
				var commandsBuilder = new StringBuilder("echo hello from container\n");
				generateCommandScript(Lists.newArrayList(0), "test",
						new CommandFacade("any", null, null, commandsBuilder.toString(), new HashMap<>(), true), getWorkspace());
			} else {
				K8sJobData jobData;
				Client client = buildRestClient(sslFactory);
				try {
					WebTarget target = client.target(serverUrl)
							.path("~api/k8s/job-data")
							.queryParam("jobToken", jobToken)
							.queryParam("jobWorkspace", getWorkspace().getAbsolutePath());
					Invocation.Builder builder =  target.request();

					logger.info("Retrieving job data from {}...", serverUrl);
					
					byte[] jobDataBytes;
					try (Response response = builder.get()) {
						checkStatus(response);
						jobDataBytes = response.readEntity(byte[].class);
					}
					
					FileUtils.writeByteArrayToFile(getJobDataFile(), jobDataBytes);
					jobData = deserialize(jobDataBytes);
				} finally {
					client.close();
				}
				
				File workspace = getWorkspace();
				FileUtils.createDir(workspace);
				
				logger.info("Generating command scripts...");
				
				CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
				entryFacade.traverse((LeafVisitor<Void>) (facade, position) -> {
					String stepPath = entryFacade.getPathAsString(position);

					String positionStr = stringifyStepPosition(position);

					File workingDir = getWorkspace();
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
							command = String.format("java -classpath \"%s\" io.onedev.k8shelper.SetupCache %s",
									classPath, positionStr);
						} else {
							ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
							command = String.format("java -classpath \"%s\" io.onedev.k8shelper.RunServerSideStep %s",
									classPath, positionStr);
						}

						var commandsBuilder = new StringBuilder();
						commandsBuilder.append(command).append("\n");

						commandFacade = new CommandFacade("any", null, null, commandsBuilder.toString(), new HashMap<>(), true);
					}

					generateCommandScript(position, stepPath, commandFacade, workingDir);

					return null;
				}, new ArrayList<>());
				
				logger.info("Downloading job dependencies from {}...", serverUrl);
				
				downloadDependencies(serverUrl, jobToken, workspace, sslFactory);
				logger.info("Job workspace initialized");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
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
	
	public static void checkStatus(Response response) {
		int status = response.getStatus();
		if (status != OK.getStatusCode() && status != NO_CONTENT.getStatusCode()) {
			String errorMessage = response.readEntity(String.class);
			if (StringUtils.isNotBlank(errorMessage)) {
				throw new RuntimeException(String.format("Http request failed (status code: %d, error message: %s)", 
						status, errorMessage));
			} else if (status >= 500){
				throw new RuntimeException("Http request failed with status " + status 
						+ ", check server log for details");
			} else {
				throw new RuntimeException("Http request failed with status " + status);
			}
		} 
	}

	public static void cloneRepository(Commandline git, String cloneUrl,
									   String remoteUrl, String refName, String commitHash,
									   boolean withLfs, boolean withSubmodules, int cloneDepth,
									   LineConsumer infoLogger, LineConsumer errorLogger) {
		List<String> initialArgs = new ArrayList<>(git.arguments());
		if (!new File(git.workingDir(), ".git").exists()) {
			git.addArgs("init", ".");
			git.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.startsWith("Initialized empty Git repository"))
						infoLogger.consume(line);
				}

			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.startsWith("hint:"))
						errorLogger.consume(line);
				}

			}).checkReturnCode();
		}

		git.arguments(initialArgs);
		git.addArgs("fetch", cloneUrl, "--force", "--quiet");
		if (cloneDepth != 0)
			git.addArgs("--depth=" + cloneDepth);
		git.addArgs(commitHash);
		git.execute(infoLogger, errorLogger).checkReturnCode();

		AtomicBoolean originExists = new AtomicBoolean(false);
		git.arguments(initialArgs);
		git.addArgs("remote", "add", "origin", remoteUrl);
		var result = git.execute(infoLogger, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.contains("remote origin already exists"))
					originExists.set(true);
				else
					errorLogger.consume(line);
			}

		});

		if (originExists.get()) {
			git.arguments(initialArgs);
			git.addArgs("remote", "set-url", "origin", remoteUrl);
			result = git.execute(infoLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					errorLogger.consume(line);
				}

			});
		}

		result.checkReturnCode();

		if (withLfs) {
			if (SystemUtils.IS_OS_MAC_OSX) {
				String path = System.getenv("PATH") + ":/usr/local/bin";
				git.environments().put("PATH", path);
			}

			git.arguments(initialArgs);
			git.addArgs("lfs", "install");
			git.execute(infoLogger, errorLogger).checkReturnCode();
		}

		git.arguments(initialArgs);
		git.addArgs("checkout", "--quiet", commitHash);
		git.execute(infoLogger, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("Filtering content:"))
					infoLogger.consume(line);
				else
					errorLogger.consume(line);
			}

		}).checkReturnCode();

		if (withSubmodules && new File(git.workingDir(), ".gitmodules").exists()) {
			// deinit submodules in case submodule url is changed
			git.arguments(initialArgs);
			git.addArgs("submodule", "deinit", "--all", "--force", "--quiet");
			git.execute(infoLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.contains("error: could not lock config file") &&
							!line.contains("warning: Could not unset core.worktree setting in submodule")) {
						errorLogger.consume(line);
					}
				}

			}).checkReturnCode();

			infoLogger.consume("Retrieving submodules...");

			git.arguments(initialArgs);
			git.addArgs("submodule", "update", "--init", "--recursive", "--force", "--quiet");
			if (cloneDepth != 0)
				git.addArgs("--depth=" + cloneDepth);
			git.execute(infoLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Submodule") && line.contains("registered for path")
							|| line.startsWith("From ") || line.startsWith(" * branch")
							|| line.startsWith(" +") && line.contains("->")) {
						infoLogger.consume(line);
					} else {
						errorLogger.consume(line);
					}
				}

			}).checkReturnCode();
		}

		if (refName.startsWith("refs/heads/")) {
			git.arguments(initialArgs);
			git.addArgs("update-ref", refName, commitHash);
			git.execute(infoLogger, errorLogger).checkReturnCode();

			String branch = refName.substring("refs/heads/".length());
			git.arguments(initialArgs);
			git.addArgs("checkout", branch);
			git.execute(infoLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Switched to branch"))
						infoLogger.consume(line);
					else
						errorLogger.consume(line);
				}

			}).checkReturnCode();

			git.arguments(initialArgs);
			git.addArgs("update-ref", "refs/remotes/origin/" + branch, commitHash);
			git.execute(infoLogger, errorLogger).checkReturnCode();

			git.arguments(initialArgs);
			git.addArgs("branch", "--set-upstream-to=origin/" + branch, branch);
			git.execute(infoLogger, errorLogger).checkReturnCode();
		}

		git.arguments(initialArgs);
	}
	
	private static K8sJobData readJobData() {
		byte[] jobDataBytes;
		try {
			jobDataBytes = readFileToByteArray(getJobDataFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return deserialize(jobDataBytes);
	}
	
	public static void testGitLfsAvailability(Commandline git, TaskLogger jobLogger) {
		File userHome = FileUtils.createTempDir("user");
		try {
			jobLogger.log("Checking if git-lfs exists...");
			git.clearArgs();
			git.environments().put("HOME", userHome.getAbsolutePath());
			if (SystemUtils.IS_OS_MAC_OSX) {
				String path = System.getenv("PATH") + ":/usr/local/bin";
				git.environments().put("PATH", path);
			}
			
			git.addArgs("lfs", "version");

			AtomicBoolean lfsExists = new AtomicBoolean(true);
			var result = git.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.startsWith("git: 'lfs' is not a git command"))
						lfsExists.set(false);
					if (lfsExists.get())
						jobLogger.error(line);
				}
				
			});
			if (lfsExists.get()) {
				result.checkReturnCode();
				jobLogger.log("git-lfs found");
			} else { 
				jobLogger.warning("WARNING: Executable 'git-lfs' not found. You will not be able to retrieve LFS files");
			}
		} finally {
			FileUtils.deleteDir(userHome, 3);
		}
	}

	public static void changeOwner(File dir, String owner) {
		var chown = new Commandline("chown");
		chown.addArgs("-R", owner, dir.getAbsolutePath());
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

	public static boolean sidecar(String serverUrl, String jobToken, boolean test) {
		LeafHandler commandHandler = new LeafHandler() {

			@Override
			public boolean execute(LeafFacade facade, List<Integer> position) {
				String positionStr = stringifyStepPosition(position);
				File file;
				File stepScriptFile = null;
				for (File eachFile: getCommandDir().listFiles()) {
					if (eachFile.getName().startsWith("step-" + positionStr + ".")) {
						stepScriptFile = eachFile;
						break;
					}
				}
				Preconditions.checkState(stepScriptFile != null);

				try {
					String stepScript = readFileToString(stepScriptFile, UTF_8);

					if (facade instanceof CommandFacade) {
						CommandFacade commandFacade = (CommandFacade) facade;
						commandFacade.generatePauseCommand(getBuildHome());
						if (commandFacade.getRunAs() != null)
							changeOwner(getBuildHome(), commandFacade.getRunAs());
					}

					stepScript = replacePlaceholders(stepScript, getBuildHome());
					FileUtils.writeFile(stepScriptFile, stepScript, UTF_8);
					
					file = new File(getMarkDir(), positionStr + ".start");
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (Exception e) {
					file = new File(getMarkDir(), positionStr + ".error");

					ExplicitException explicitException = ExceptionUtils.find(e, ExplicitException.class);
					String errorMessage;
					if (explicitException != null)
						errorMessage = explicitException.getMessage().trim();
					else 
						errorMessage = Throwables.getStackTraceAsString(e).trim();
					errorMessage += "\n";
					FileUtils.writeFile(file, errorMessage, UTF_8);
				}
			
				File successfulFile = new File(getMarkDir(), positionStr + ".successful");
				File failedFile = new File(getMarkDir(), positionStr + ".failed");
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
				File file = new File(getMarkDir(), stringifyStepPosition(position) + ".skip");
				try {
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			
		};
		
		if (test) {
			CommandFacade facade = new CommandFacade(
					"this does not matter", null, null, "this does not matter", new HashMap<>(), false);
			return facade.execute(commandHandler, Lists.newArrayList(0));
		} else {
			var jobData = readJobData();
			var actions = jobData.getActions();

			var successful = new CompositeFacade(actions).execute(commandHandler, new ArrayList<>());

			if (successful) {
				var sslFactory = buildSSLFactory(getTrustCertsDir());
				var cacheInfos = readCacheInfos();
				for (var cacheInfo : cacheInfos) {
					var cacheConfig = cacheInfo.getLeft();
					var uploadStrategy = cacheConfig.getUploadStrategy();
					var cacheDirs = new ArrayList<File>();
					for (var cachePath : cacheConfig.getPaths())
						cacheDirs.add(getWorkspace().toPath().resolve(cachePath).toFile());
					if (uploadStrategy == UPLOAD_IF_NOT_HIT) {
						if (!cacheInfo.getRight())
							uploadCacheThenLog(serverUrl, jobToken, cacheConfig, cacheDirs, sslFactory);
					} else {
						var changedFile = CacheHelper.getChangedFile(cacheDirs, cacheInfo.getMiddle(), cacheConfig);
						if (changedFile != null) {
							logger.info("Cache file changed: " + changedFile);
							uploadCacheThenLog(serverUrl, jobToken, cacheConfig, cacheDirs, sslFactory);
						}
					}
				}
			}
			return successful;
		}
	}

	private static void uploadCacheThenLog(String serverUrl, String jobToken, SetupCacheFacade cacheConfig,
							 List<File> cacheDirs, @Nullable SSLFactory sslFactory) {
		if (uploadCache(serverUrl, jobToken, cacheConfig, cacheDirs, sslFactory))
			logger.info("Uploaded " + cacheConfig.getUploadDescription());
		else
			logger.warn("Not authorized to upload " + cacheConfig.getUploadDescription());
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
			if (IOUtils.readFully(is, lengthBytes) != lengthBytes.length)
				throw new ExplicitException("Invalid input stream");
			int length = ByteBuffer.wrap(lengthBytes).getInt();
			byte[] stringBytes = new byte[length];
			if (IOUtils.readFully(is, stringBytes) != stringBytes.length)
				throw new ExplicitException("Invalid input stream");
			return new String(stringBytes, UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static int readInt(InputStream is) {
		try {
			byte[] intBytes = new byte[4];
			if (IOUtils.readFully(is, intBytes) != intBytes.length)
				throw new ExplicitException("Invalid input stream");
			return ByteBuffer.wrap(intBytes).getInt();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	static void checkoutCode(String serverUrl, String jobToken, String positionStr,
			boolean withLfs, boolean withSubmodules, int cloneDepth, CloneInfo cloneInfo, 
			@Nullable String checkoutPath) throws IOException {
		K8sJobData jobData = readJobData();
		
		logger.info("Checking out code from {}...", cloneInfo.getCloneUrl());

		LineConsumer infoLogger = newInfoLogger();
		LineConsumer errorLogger = newErrorLogger();
		
		File userHome = new File(System.getProperty("user.home"));

		File workspace = getWorkspace();
		Commandline git = new Commandline("git");
		if (checkoutPath != null) {
			if (checkoutPath.contains(".."))
				throw new ExplicitException("Checkout path should not contain '..'");
			git.workingDir(new File(workspace, checkoutPath));
			FileUtils.createDir(git.workingDir());
		} else {
			git.workingDir(workspace);
		}

		File trustCertsFile = new File(getBuildHome(), "trust-certs.pem");
		installGitCert(git, getTrustCertsDir(), trustCertsFile,
				trustCertsFile.getAbsolutePath(), infoLogger, errorLogger);
		cloneInfo.writeAuthData(userHome, git, true, infoLogger, errorLogger);

		// Also populate auth info into user dir which will be shared
		// with other containers. The setup script of other contains will 
		// move all auth data from buildUserHome into the user home so that
		// git pull/push can be done without asking for credentials
		File userDir = getUserDir();
		Commandline anotherGit = new Commandline("git");
		anotherGit.environments().put("HOME", userDir.getAbsolutePath());
		installGitCert(anotherGit, getTrustCertsDir(), trustCertsFile,
				trustCertsFile.getAbsolutePath(), infoLogger, errorLogger);
		cloneInfo.writeAuthData(userDir, anotherGit, true, infoLogger, errorLogger);

		cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(), 
				jobData.getRefName(), jobData.getCommitHash(), withLfs, withSubmodules, cloneDepth, 
				infoLogger, errorLogger);
	}

	static boolean runServerStep(String serverUrl, String jobToken, String positionStr) {
		return runServerStep(buildSSLFactory(getTrustCertsDir()), serverUrl, jobToken, positionStr);
	}

	private static boolean runServerStep(SSLFactory sslFactory, String serverUrl,
										 String jobToken, String positionStr) {
		List<Integer> position = parseStepPosition(positionStr);
		var actions = readJobData().getActions();
		var serverSideFacade = (ServerSideFacade) new CompositeFacade(actions).getFacade(position);
		TaskLogger logger = new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				KubernetesHelper.logger.info(message);
			}

		};
		return runServerStep(sslFactory, serverUrl, jobToken, position, serverSideFacade, getBuildHome(), logger);
	}

	public static boolean runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
										List<Integer> position, ServerSideFacade serverSideFacade,
										File buildHome, TaskLogger logger) {
		Map<String, String> placeholderValues = readPlaceholderValues(buildHome, serverSideFacade.getPlaceholders());
		File baseDir = new File(buildHome, "workspace");
		if (serverSideFacade.getSourcePath() != null)
			baseDir = new File(baseDir, replacePlaceholders(serverSideFacade.getSourcePath(), placeholderValues));
		
		var includeFiles = replacePlaceholders(serverSideFacade.getIncludeFiles(), placeholderValues);
		var excludeFiles = replacePlaceholders(serverSideFacade.getExcludeFiles(), placeholderValues);
		
		var result = runServerStep(sslFactory, serverUrl, jobToken, position, baseDir,
				includeFiles, excludeFiles, placeholderValues, logger);
		for (Map.Entry<String, byte[]> entry: result.getOutputFiles().entrySet()) {
			try {
				FileUtils.writeByteArrayToFile(
						new File(buildHome, entry.getKey()), 
						entry.getValue());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return result.isSuccessful();
	}
	
	public static ServerStepResult runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
												 List<Integer> position, File baseDir,
												 Collection<String> includeFiles, Collection<String> excludeFiles,
												 Map<String, String> placeholderValues, TaskLogger logger) {
		Client client = buildRestClient(sslFactory);
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/run-server-step")
					.queryParam("jobToken", jobToken);
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

				TarUtils.tar(baseDir, includeFiles, excludeFiles, os, false);
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

	private static File getCacheInfosFile() {
		return new File(getMarkDir(), "cache-infos");
	}

	private static void writeCacheInfos(List<Triple<SetupCacheFacade, Date, Boolean>> cacheInfos) {
		try {
			writeByteArrayToFile(getCacheInfosFile(), serialize((Serializable) cacheInfos));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static List<Triple<SetupCacheFacade, Date, Boolean>> readCacheInfos() {
		var file = getCacheInfosFile();
		if (file.exists()) {
			try {
				return deserialize(readFileToByteArray((file)));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			return new ArrayList<>();
		}
	}

	static void setupCache(String serverUrl, String jobToken, String positionStr) {
		var position = parseStepPosition(positionStr);
		var jobData = readJobData();
		var actions = jobData.getActions();
		var cacheConfig = (SetupCacheFacade) new CompositeFacade(actions).getFacade(position);

		var cacheKey = replacePlaceholders(cacheConfig.getKey(), getBuildHome());
		var loadKeys = cacheConfig.getLoadKeys().stream()
				.map(it -> replacePlaceholders(it, getBuildHome()))
				.collect(toList());
		var cachePaths = cacheConfig.getPaths();
		var sslFactory = buildSSLFactory(getTrustCertsDir());

		var cacheDirs = new ArrayList<File>();
		for (var cachePath: cachePaths) {
			var cacheDir = getWorkspace().toPath().resolve(cachePath).toFile();
			FileUtils.createDir(cacheDir);
			cacheDirs.add(cacheDir);
		}

		var cacheInfos = readCacheInfos();
		cacheConfig = new SetupCacheFacade(cacheKey, loadKeys, cachePaths,
				cacheConfig.getUploadStrategy(), cacheConfig.getChangeDetectionExcludes(),
				cacheConfig.getUploadProjectPath(), cacheConfig.getUploadAccessToken());

		boolean cacheHit = false;
		if (downloadCache(serverUrl, jobToken, cacheKey, cachePaths, cacheDirs, sslFactory)) {
			logger.info("Hit " + cacheConfig.getHitDescription());
			cacheHit = true;
		} else if (!loadKeys.isEmpty()) {
			if (downloadCache(serverUrl, jobToken, loadKeys, cachePaths, cacheDirs, sslFactory))
				logger.info("Matched " + cacheConfig.getMatchedDescription());
		}
		cacheInfos.add(new ImmutableTriple<>(cacheConfig, new Date(), cacheHit));
		writeCacheInfos(cacheInfos);
	}

	public static Collection<String> parsePlaceholders(String string) {
		Collection<String> placeholderFiles = new HashSet<>();
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(string);  
        while (matcher.find())   
        	placeholderFiles.add(matcher.group(1));
		return placeholderFiles;
	}

	public static Map<String, String> readPlaceholderValues(File dir, Collection<String> placeholders) {
		Map<String, String> placeholderValues = new HashMap<>();
		for (String placeholder: placeholders) {
			File file = new File(dir, placeholder);
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

	public static void downloadDependencies(String serverUrl, String jobToken,
											File targetDir, SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/download-dependencies")
					.queryParam("jobToken", jobToken);
			Invocation.Builder builder =  target.request();
			try (Response response = builder.get()){
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					untar(is, targetDir, false);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			client.close();
		}
	}

	public static String replacePlaceholders(String string, Map<String, String> placeholderValues) {
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(string);  
        StringBuffer buffer = new StringBuffer();  
        while (matcher.find()) {  
        	String placeholder = matcher.group(1);
        	String placeholderValue = placeholderValues.get(placeholder);
        	if (placeholderValue != null) {
        		matcher.appendReplacement(buffer, Matcher.quoteReplacement(placeholderValue));
        	} else if (placeholder.startsWith(WORKSPACE + "/")) {
        		throw new ExplicitException("Error replacing placeholder: unable to find file '" 
        				+ placeholder.substring(WORKSPACE.length()+1) + "' in workspace");
        	} else if (placeholder.startsWith(ATTRIBUTES + "/")) {
        		throw new ExplicitException("Error replacing placeholder: agent attribute '" 
        				+ placeholder.substring(ATTRIBUTES.length()+1) + "' does not exist");
        	} else if (placeholder.equals(BUILD_VERSION)){ 
        		throw new ExplicitException("Error replacing placeholder: build version not set yet");
        	}
         }  
         matcher.appendTail(buffer);  
         return buffer.toString();
	}
	
	public static String replacePlaceholders(String string, File buildHome) {
		Collection<String> placeholders = parsePlaceholders(string);
		Map<String, String> placeholderValues = readPlaceholderValues(buildHome, placeholders);
		return replacePlaceholders(string, placeholderValues);
	}
	
	public static Collection<String> replacePlaceholders(Collection<String> collection, 
			Map<String, String> placeholderValues) {
		Collection<String> replacedCollection = new ArrayList<>();
		for (String each: collection) 
			replacedCollection.add(replacePlaceholders(each, placeholderValues));
		return replacedCollection;
	}
	
	public static Collection<String> replacePlaceholders(Collection<String> collection, File buildHome) {
		Collection<String> replacedCollection = new ArrayList<>();
		for (String each: collection)
			replacedCollection.add(replacePlaceholders(each, buildHome));
		return replacedCollection;
	}

	public static SSLFactory buildSSLFactory(File trustCertsDir) {
		SSLFactory.Builder builder = SSLFactory.builder().withDefaultTrustMaterial();
		if (trustCertsDir.exists()) {
			for (var file: trustCertsDir.listFiles()) {
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
			HostnameVerifier fenixVerifier = HostnameVerifierUtils.createFenix();
			builder.withHostnameVerifier((hostname, session) -> basicVerifier.verify(hostname, session) || fenixVerifier.verify(hostname, session));
		}
		return builder.build();
	}

	private static boolean downloadCache(WebTarget target, List<File> cacheDirs) {
		Invocation.Builder builder =  target.request();
		try (Response response = builder.get()){
			checkStatus(response);
			try (var is = response.readEntity(InputStream.class)) {
				if (is.read() == 1) {
					untar(cacheDirs, is);
					return true;
				} else {
					return false;
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static boolean downloadCache(String serverUrl, String jobToken, String cacheKey,
										List<String> cachePaths, List<File> cacheDirs,
										@Nullable SSLFactory sslFactory) {
		Client client = KubernetesHelper.buildRestClient(sslFactory);
		try {
			var target = client.target(serverUrl)
					.path("~api/k8s/download-cache")
					.queryParam("jobToken", jobToken)
					.queryParam("cacheKey", cacheKey)
					.queryParam("cachePaths", Joiner.on('\n').join(cachePaths));
			return downloadCache(target, cacheDirs);
		} finally {
			client.close();
		}
	}

	public static boolean downloadCache(String serverUrl, String jobToken, List<String> loadKeys,
										List<String> cachePaths, List<File> cacheDirs,
										@Nullable SSLFactory sslFactory) {
		Client client = KubernetesHelper.buildRestClient(sslFactory);
		try {
			var target = client.target(serverUrl)
					.path("~api/k8s/download-cache")
					.queryParam("jobToken", jobToken)
					.queryParam("loadKeys", Joiner.on('\n').join(loadKeys))
					.queryParam("cachePaths", Joiner.on('\n').join(cachePaths));
			return downloadCache(target, cacheDirs);
		} finally {
			client.close();
		}
	}

	public static boolean uploadCache(String serverUrl, String jobToken, SetupCacheFacade cacheConfig,
									  List<File> cacheDirs, @Nullable SSLFactory sslFactory) {
		var cacheKey = cacheConfig.getKey();
		var cachePaths = cacheConfig.getPaths();
		var projectPath = cacheConfig.getUploadProjectPath();
		var accessToken = cacheConfig.getUploadAccessToken();
		Client client = KubernetesHelper.buildRestClient(sslFactory);
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/upload-cache")
					.queryParam("jobToken", jobToken)
					.queryParam("projectPath", projectPath);
			Invocation.Builder builder = target.request();
			if (accessToken != null)
				builder.header(AUTHORIZATION, BEARER + " " + accessToken);
			try (Response response = builder.get()) {
				if (response.getStatus() == UNAUTHORIZED.getStatusCode())
					return false;
				checkStatus(response);
			}

			builder = target
					.queryParam("cacheKey", cacheKey)
					.queryParam("cachePaths", Joiner.on('\n').join(cachePaths))
					.request();
			if (accessToken != null)
				builder.header(AUTHORIZATION, BEARER + " " + accessToken);
			StreamingOutput output = os -> tar(cacheDirs, os);
			try (Response response = builder.post(entity(output, APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				return true;
			}
		} finally {
			client.close();
		}
	}

	public static Client buildRestClient(@Nullable SSLFactory sslFactory) {
		var builder = ClientBuilder.newBuilder();
		if (sslFactory != null)
			builder.sslContext(sslFactory.getSslContext()).hostnameVerifier(sslFactory.getHostnameVerifier());
		return builder.build();
	}

	public static String formatDuration(long durationMillis) {
		if (durationMillis < 0)
			durationMillis = 0;
		return DurationFormatUtils.formatDurationWords(durationMillis, true, true);
	}

}
