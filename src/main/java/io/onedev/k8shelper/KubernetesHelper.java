package io.onedev.k8shelper;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import io.onedev.commons.utils.*;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.util.CertificateUtils;
import nl.altindag.ssl.util.HostnameVerifierUtils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.ws.rs.client.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getDecoder;
import static java.util.Base64.getEncoder;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static javax.ws.rs.core.Response.Status.*;
import static org.apache.commons.io.FileUtils.*;
import static org.apache.commons.lang3.SerializationUtils.deserialize;
import static org.apache.commons.lang3.SerializationUtils.serialize;

public class KubernetesHelper {

	public static final String IMAGE_REPO_PREFIX = "code.onedev.io/onedev/k8s-helper";
	
	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";
	
	public static final String ENV_JOB_TOKEN = "ONEDEV_JOB_TOKEN";
	
	public static final String ENV_OS_INFO = "ONEDEV_OS_INFO";

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
		if (SystemUtils.IS_OS_WINDOWS) 
			return new File("C:\\onedev-build");
		else 
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

	private static OsInfo getOsInfo() {
		try {
			return deserialize(Hex.decodeHex(System.getenv(ENV_OS_INFO).toCharArray()));
		} catch (DecoderException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static void generateCommandScript(List<Integer> position, String stepNames, 
			List<String> setupCommands, CommandFacade commandFacade, @Nullable File workingDir, 
			OsInfo osInfo) {
		try {
			String positionStr = stringifyStepPosition(position);
			File commandHome = getCommandDir();
			File stepScriptFile = new File(commandHome, "step-" + positionStr + commandFacade.getScriptExtension());
			OsExecution execution = commandFacade.getExecution(osInfo);
			FileUtils.writeStringToFile(stepScriptFile, commandFacade.convertCommands(execution.getCommands()), UTF_8);

 			if (SystemUtils.IS_OS_WINDOWS) { 
				StringBuilder escapedStepNames = new StringBuilder();
				for (int i=0; i<stepNames.length(); i++)
					escapedStepNames.append('^').append(stepNames.charAt(i));
				
				File setupScriptFile = new File(commandHome, "setup-" + positionStr + ".bat");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\r\n");
				
				File scriptFile = new File(commandHome, positionStr + ".bat");
				String markPrefix = getMarkDir().getAbsolutePath() + "\\" + positionStr;
				List<String> scriptContent = Lists.newArrayList(
						"@echo off",
						"set \"initialWorkingDir=%cd%\"",
						":wait",
						"if exist \"" + markPrefix + ".skip\" (",
						"  echo " + TaskLogger.wrapWithAnsiNotice("Step ^\"" + escapedStepNames + "^\" is skipped"),
						"  echo " + LOG_END_MESSAGE,
						"  goto :eof",
						")",
						"if exist \"" + markPrefix + ".error\" (",
						"  echo " + TaskLogger.wrapWithAnsiNotice("Running step ^\"" + escapedStepNames + "^\"..."),
						"  type " + markPrefix + ".error",
						"  copy /y nul " + markPrefix + ".failed > nul",
						"  echo " + LOG_END_MESSAGE,
						"  exit 1",
						")",
						"if exist \"" + markPrefix + ".start\" goto start",
						"ping 127.0.0.1 -n 2 > nul",
						"goto wait",
						":start",
						"cd " + (workingDir!=null? workingDir.getAbsolutePath(): "%initialWorkingDir%") 
								+ " && cmd /c " + setupScriptFile.getAbsolutePath()
								+ " && cmd /c echo " + TaskLogger.wrapWithAnsiNotice("Running step ^\"" + escapedStepNames + "^\"...")
								+ " && " + commandFacade.getScriptInterpreter() + " " + stepScriptFile.getAbsolutePath(), 
						"set exit_code=%errorlevel%",
						"if \"%exit_code%\"==\"0\" (",
						"	echo " + TaskLogger.wrapWithAnsiSuccess("Step ^\"" + escapedStepNames + "^\" is successful"),
						"	copy /y nul " + markPrefix + ".successful > nul",
						") else (",
						"	copy /y nul " + markPrefix + ".failed > nul",
						")",
						"echo " + LOG_END_MESSAGE,
						"exit %exit_code%");
				FileUtils.writeLines(scriptFile, scriptContent, "\r\n");
			} else {
				String escapedStepNames = stepNames.replace("'", "'\\''");
				
				File setupScriptFile = new File(commandHome, "setup-" + positionStr + ".sh");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\n");
				
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
						"  echo '" + TaskLogger.wrapWithAnsiNotice("Step \"" + escapedStepNames + "\" is skipped") + "'",
						"  echo " + LOG_END_MESSAGE,
						"  exit 0",
						"fi",
						"if [ -f " + markPrefix + ".error ]",
						"then",
						"  echo '" + TaskLogger.wrapWithAnsiNotice("Running step \"" + escapedStepNames + "\"...") + "'",
						"  cat " + markPrefix + ".error",
						"  touch " + markPrefix + ".failed",
						"  echo " + LOG_END_MESSAGE,
						"  exit 1",
						"fi",
						"cd " + (workingDir!=null? "'" + workingDir.getAbsolutePath() + "'": "$initialWorkingDir") 
								+ " && sh " + setupScriptFile.getAbsolutePath()
								+ " && echo '" + TaskLogger.wrapWithAnsiNotice("Running step \"" + escapedStepNames + "\"...") + "'" 
								+ " && " + commandFacade.getScriptInterpreter() + " " + stepScriptFile.getAbsolutePath(), 
						"exitCode=\"$?\"", 
						"if [ $exitCode -eq 0 ]",
						"then",
						"  echo '" + TaskLogger.wrapWithAnsiSuccess("Step \"" + escapedStepNames + "\" is successful") + "'",
						"  touch " + markPrefix + ".successful",
						"else",
						"  touch " + markPrefix + ".failed",
						"fi",						
						"echo " + LOG_END_MESSAGE,
						"exit $exitCode");
				FileUtils.writeLines(scriptFile, wrapperScriptContent, "\n");
			}
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
	
	public static String encodeAsCommandArg(Collection<String> list) {
		Collection<String> base64 = new ArrayList<>();
		for (String each: list) 
			base64.add(getEncoder().encodeToString(each.getBytes(UTF_8)));
		String commandArg = StringUtils.join(base64, "-");
		if (commandArg.length() == 0)
			commandArg = "-";
		return commandArg;
	}
	
	public static List<String> decodeCommandArgAsCollection(String commandArg) {
		List<String> decoded = new ArrayList<>();
		for (String each: Splitter.on('-').trimResults().omitEmptyStrings().split(commandArg)) 
			decoded.add(new String(getDecoder().decode(each), UTF_8));
		return decoded;
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
		OsInfo osInfo = getOsInfo();
		try {
			FileUtils.createDir(getCommandDir());
			FileUtils.createDir(getMarkDir());
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
				var commandsBuilder = new StringBuilder();
				if (SystemUtils.IS_OS_WINDOWS)  
					commandsBuilder.append("@echo off\n");
				commandsBuilder.append("echo hello from container\n");
				generateCommandScript(Lists.newArrayList(0), "test", Lists.newArrayList(), 
						new CommandFacade("any", null, commandsBuilder.toString(), true), getWorkspace(), osInfo);
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
					String stepNames = entryFacade.getNamesAsString(position);

					List<String> setupCommands = new ArrayList<>();
					if (SystemUtils.IS_OS_WINDOWS) {
						setupCommands.add("@echo off");
						setupCommands.add("xcopy /Y /S /K /Q /H /R C:\\Users\\%USERNAME%\\auth-info\\* C:\\Users\\%USERNAME% > nul");
					} else {
						setupCommands.add("cp -r -f -p /root/auth-info/. /root");
					}

					String positionStr = stringifyStepPosition(position);

					File workingDir = getWorkspace();
					CommandFacade commandFacade;
					if (facade instanceof CommandFacade) {
						commandFacade = (CommandFacade) facade;
					} else {
						String command;
						String classPath;
						if (SystemUtils.IS_OS_WINDOWS)
							classPath = "C:\\k8s-helper\\*";
						else
							classPath = "/k8s-helper/*";
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
							SetupCacheFacade setupCacheFacade = (SetupCacheFacade) facade;
							command = String.format("java -classpath \"%s\" io.onedev.k8shelper.SetupCache %s %s %s %s",
									classPath, positionStr,
									getEncoder().encodeToString(setupCacheFacade.getKey().getBytes(UTF_8)),
									encodeAsCommandArg(setupCacheFacade.getLoadKeys()),
									getEncoder().encodeToString(setupCacheFacade.getPath().getBytes(UTF_8)));
						} else {
							ServerSideFacade serverSideFacade = (ServerSideFacade) facade;

							String includeFiles = encodeAsCommandArg(serverSideFacade.getIncludeFiles());
							String excludeFiles = encodeAsCommandArg(serverSideFacade.getExcludeFiles());
							String placeholders = encodeAsCommandArg(serverSideFacade.getPlaceholders());
							command = String.format("java -classpath \"%s\" io.onedev.k8shelper.RunServerSideStep %s %s %s %s",
									classPath, positionStr, includeFiles, excludeFiles, placeholders);
							if (serverSideFacade.getSourcePath() != null) {
								byte[] bytes = serverSideFacade.getSourcePath().getBytes(UTF_8);
								command += " " + getEncoder().encodeToString(bytes);
							}
						}

						var commandsBuilder = new StringBuilder();
						if (SystemUtils.IS_OS_WINDOWS)
							commandsBuilder.append("@echo off\n");
						commandsBuilder.append(command).append("\n");

						commandFacade = new CommandFacade("any", null, commandsBuilder.toString(), true);
					}

					generateCommandScript(position, stepNames, setupCommands, commandFacade, workingDir, osInfo);

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
			} else {
				throw new RuntimeException("Http request failed with status " + status 
						+ ", check server log for details");
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
		ExecutionResult result = git.execute(infoLogger, new LineConsumer() {

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
			ExecutionResult result = git.execute(new LineConsumer() {

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

	public static void sidecar(String serverUrl, String jobToken, boolean test) {
		Map<String, SetupCacheFacade> cacheInfos = new HashMap<>();
		LeafHandler commandHandler = new LeafHandler() {

			@Override
			public boolean execute(LeafFacade facade, List<Integer> position) {
				String positionStr = stringifyStepPosition(position);
				if (facade instanceof SetupCacheFacade)
					cacheInfos.put(positionStr, (SetupCacheFacade) facade);

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

					if (facade instanceof CommandFacade) 
						((CommandFacade) facade).generatePauseCommand(getBuildHome());
					
					stepScript = replacePlaceholders(stepScript, getBuildHome());
					FileUtils.writeFile(stepScriptFile, stepScript, UTF_8.name());
					
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
					if (SystemUtils.IS_OS_WINDOWS)
						errorMessage = errorMessage.replace("\n", "\r\n");
					
					FileUtils.writeFile(file, errorMessage, UTF_8.name());
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
					"this does not matter", null, "this does not matter", false);
			facade.execute(commandHandler, Lists.newArrayList(0));
		} else {
			K8sJobData jobData = readJobData();
			List<Action> actions = jobData.getActions();

			if (new CompositeFacade(actions).execute(commandHandler, new ArrayList<>())) {
				var sslFactory = buildSSLFactory(getTrustCertsDir());
				Map<String, String> setupCachePositions = readSetupCachePositions();
				Set<String> hitCacheKeys = readHitCacheKeys();
				for (var entry: setupCachePositions.entrySet()) {
					var cacheKey = entry.getKey();
					var cacheInfo = cacheInfos.get(entry.getValue());
					if (cacheInfo != null && !hitCacheKeys.contains(cacheKey)) {
						var cachePath = cacheInfo.getPath();
						var cacheDir = getWorkspace().toPath().resolve(cachePath).toFile();
						if (uploadCache(serverUrl, jobToken, cacheKey, cachePath,
								cacheInfo.getUploadAccessToken(), cacheDir, sslFactory)) {
							logger.info("Uploaded cache (key: {}, path: {})", cacheKey, cachePath);
						} else {
							logger.warn("Not authorized to upload cache (key: {}, path: {})", cacheKey, cachePath);
						}
					}
				}
			}
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
		
		File userHome;
		if (SystemUtils.IS_OS_WINDOWS)
			userHome = new File(System.getProperty("user.home"));
		else
			userHome = new File("/root");
		
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

		// Also populate auth info into authInfoHome which will be shared 
		// with other containers. The setup script of other contains will 
		// move all auth data from authInfoHome into the user home so that 
		// git pull/push can be done without asking for credentials
		File authInfoDir = new File(userHome, "auth-info");
		Commandline anotherGit = new Commandline("git");
		anotherGit.environments().put("HOME", authInfoDir.getAbsolutePath());
		installGitCert(anotherGit, getTrustCertsDir(), trustCertsFile,
				trustCertsFile.getAbsolutePath(), infoLogger, errorLogger);
		cloneInfo.writeAuthData(authInfoDir, anotherGit, true, infoLogger, errorLogger);

		cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(), 
				jobData.getRefName(), jobData.getCommitHash(), withLfs, withSubmodules, cloneDepth, 
				infoLogger, errorLogger);
	}

	static void runServerStep(String serverUrl, String jobToken,
							  String positionStr, String encodedIncludeFiles,
							  String encodedExcludeFiles, String encodedPlaceholders,
							  @Nullable String encodedBasePath) {
		runServerStep(buildSSLFactory(getTrustCertsDir()), serverUrl,
				jobToken, positionStr, encodedIncludeFiles, encodedExcludeFiles,
				encodedPlaceholders, encodedBasePath);
	}

	private static void runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
									 String positionStr, String encodedIncludeFiles,
									 String encodedExcludeFiles, String encodedPlaceholders,
									 @Nullable String encodedBasePath) {
		List<Integer> position = parseStepPosition(positionStr);
		String basePath = null;
		if (encodedBasePath != null) {
			basePath = new String(getDecoder().decode(
					encodedBasePath.getBytes(UTF_8)), UTF_8);
		}
		Collection<String> includeFiles = decodeCommandArgAsCollection(encodedIncludeFiles);
		Collection<String> excludeFiles = decodeCommandArgAsCollection(encodedExcludeFiles);
		Collection<String> placeholders = decodeCommandArgAsCollection(encodedPlaceholders);
		
		TaskLogger logger = new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				KubernetesHelper.logger.info(message);
			}
			
		};
		runServerStep(sslFactory, serverUrl, jobToken, position, basePath, includeFiles, excludeFiles,
				placeholders, getBuildHome(), logger);
	}

	public static void runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
									 List<Integer> position, @Nullable String basePath,
									 Collection<String> includeFiles, Collection<String> excludeFiles,
									 Collection<String> placeholders, File buildHome, TaskLogger logger) {
		Map<String, String> placeholderValues = readPlaceholderValues(buildHome, placeholders);
		File baseDir = new File(buildHome, "workspace");
		if (basePath != null) 
			baseDir = new File(baseDir, replacePlaceholders(basePath, placeholderValues));
		
		includeFiles = replacePlaceholders(includeFiles, placeholderValues); 
		excludeFiles = replacePlaceholders(excludeFiles, placeholderValues); 
		
		Map<String, byte[]> files = runServerStep(sslFactory, serverUrl, jobToken, position, baseDir,
				includeFiles, excludeFiles, placeholderValues, logger);
		for (Map.Entry<String, byte[]> entry: files.entrySet()) {
			try {
				FileUtils.writeByteArrayToFile(
						new File(buildHome, entry.getKey()), 
						entry.getValue());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	public static Map<String, byte[]> runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
													List<Integer> position, File baseDir,
													Collection<String> includeFiles, Collection<String> excludeFiles,
													Map<String, String> placeholderValues, TaskLogger logger) {
		Client client = buildRestClient(sslFactory);
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/run-server-step")
					.queryParam("jobToken", jobToken);
			Invocation.Builder builder =  target.request();

			StreamingOutput os = new StreamingOutput() {

				@Override
				public void write(OutputStream os) throws IOException {
					writeInt(os, position.size());
					for (int each: position) 
						writeInt(os, each);
					
					writeInt(os, placeholderValues.size());
					for (Map.Entry<String, String> entry: placeholderValues.entrySet()) {
						writeString(os, entry.getKey());
						writeString(os, entry.getValue());
					}
					
					TarUtils.tar(baseDir, includeFiles, excludeFiles, os, false);
			   }				   
			   
			};
			
			try (Response response = builder.post(Entity.entity(os, MediaType.APPLICATION_OCTET_STREAM))) {
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

	private static File getSetupCachePositionsFile() {
		return new File(getMarkDir(), "setup-cache-positions");
	}

	private static File getHitCacheKeysFile() {
		return new File(getMarkDir(), "hit-cache-keys");
	}

	private static void writeSetupCachePositions(Map<String, String> setupCachePositions) {
		try {
			writeByteArrayToFile(
					getSetupCachePositionsFile(),
					serialize((Serializable) setupCachePositions));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void writeHitCacheKeys(Set<String> hitCacheKeys) {
		try {
			writeByteArrayToFile(
					getHitCacheKeysFile(),
					serialize((Serializable) hitCacheKeys));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Map<String, String> readSetupCachePositions() {
		var file = getSetupCachePositionsFile();
		if (file.exists()) {
			try {
				return deserialize(readFileToByteArray((file)));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			return new HashMap<>();
		}
	}

	private static Set<String> readHitCacheKeys() {
		var file = getHitCacheKeysFile();
		if (file.exists()) {
			try {
				return deserialize(readFileToByteArray((file)));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			return new HashSet<>();
		}
	}

	static void setupCache(String serverUrl, String jobToken, String positionStr,
						   String encodedCacheKey, String encodedCacheLoadKeys,
						   String encodedCachePath) {
		var cacheKey = new String(getDecoder().decode(encodedCacheKey.getBytes(UTF_8)), UTF_8);
		cacheKey = replacePlaceholders(cacheKey, getBuildHome());
		List<String> cacheLoadKeys = decodeCommandArgAsCollection(encodedCacheLoadKeys);
		cacheLoadKeys = cacheLoadKeys.stream()
				.map(it -> replacePlaceholders(it, getBuildHome()))
				.collect(toList());
		var cachePath = new String(getDecoder().decode(encodedCachePath.getBytes(UTF_8)), UTF_8);
		var cacheDir = getWorkspace().toPath().resolve(cachePath).toFile();
		var sslFactory = buildSSLFactory(getTrustCertsDir());

		Map<String, String> setupCachePositions = readSetupCachePositions();
		Set<String> hitCacheKeys = readHitCacheKeys();

		if (setupCachePositions.putIfAbsent(cacheKey, positionStr) != null)
			throw new ExplicitException("Duplicate cache key: " + cacheKey);

		if (downloadCache(serverUrl, jobToken, cacheKey, cachePath, cacheDir, sslFactory)) {
			logger.info("Hit cache (key: {}, path: {})", cacheKey, cachePath);
			hitCacheKeys.add(cacheKey);
			writeHitCacheKeys(hitCacheKeys);
		} else if (!cacheLoadKeys.isEmpty()) {
			if (downloadCache(serverUrl, jobToken, cacheLoadKeys, cachePath, cacheDir, sslFactory))
				logger.info("Matched cache (load keys: {}, path: {})", cacheLoadKeys, cachePath);
		}
		writeSetupCachePositions(setupCachePositions);
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
					TarUtils.untar(is, targetDir, false);
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
			builder.withHostnameVerifier(new HostnameVerifier() {
				@Override
				public boolean verify(String hostname, SSLSession session) {
					return basicVerifier.verify(hostname, session) || fenixVerifier.verify(hostname, session);
				}

			});
		}
		return builder.build();
	}

	private static boolean downloadCache(WebTarget target, File cacheDir) {
		Invocation.Builder builder =  target.request();
		try (Response response = builder.get()){
			checkStatus(response);
			try (InputStream is = response.readEntity(InputStream.class)) {
				if (is.read() == 1) {
					TarUtils.untar(is, cacheDir, true);
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
									String cachePath, File cacheDir, @Nullable SSLFactory sslFactory) {
		Client client = KubernetesHelper.buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/download-cache")
					.queryParam("jobToken", jobToken)
					.queryParam("cacheKey", cacheKey)
					.queryParam("cachePath", cachePath);
			return downloadCache(target, cacheDir);
		} finally {
			client.close();
		}
	}

	public static boolean downloadCache(String serverUrl, String jobToken, List<String> cacheLoadKeys,
									String cachePath, File cacheDir, @Nullable SSLFactory sslFactory) {
		Client client = KubernetesHelper.buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/download-cache")
					.queryParam("jobToken", jobToken)
					.queryParam("cacheLoadKeys", Joiner.on('\n').join(cacheLoadKeys))
					.queryParam("cachePath", cachePath);
			return downloadCache(target, cacheDir);
		} finally {
			client.close();
		}
	}

	public static boolean uploadCache(String serverUrl, String jobToken, String cacheKey,
							   String cachePath, @Nullable String accessToken,
							   File cacheDir, @Nullable SSLFactory sslFactory) {
		Client client = KubernetesHelper.buildRestClient(sslFactory);
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/upload-cache")
					.queryParam("jobToken", jobToken)
					.queryParam("cacheKey", cacheKey)
					.queryParam("cachePath", cachePath);
			Invocation.Builder builder = target.request();
			if (accessToken != null)
				builder.header(AUTHORIZATION, BEARER + " " + accessToken);
			try (Response response = builder.get()) {
				if (response.getStatus() == UNAUTHORIZED.getStatusCode())
					return false;
				KubernetesHelper.checkStatus(response);
			}

			StreamingOutput output = os -> TarUtils.tar(cacheDir, os, true);
			try (Response response = builder.post(entity(output, APPLICATION_OCTET_STREAM))) {
				KubernetesHelper.checkStatus(response);
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

}
