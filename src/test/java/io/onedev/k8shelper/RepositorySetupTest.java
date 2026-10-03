package io.onedev.k8shelper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

@EnabledOnOs({OS.LINUX, OS.MAC})
class RepositorySetupTest {

	@TempDir
	Path temp;

	private final LineConsumer logger = new LineConsumer() {
		@Override
		public void consume(String line) {
		}
	};

	private Commandline command(Path dir) {
		return new Commandline("git").workingDir(dir.toFile()).envs(Map.of(
				"GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null",
				"GIT_ALLOW_PROTOCOL", "file", "GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C"));
	}

	private String git(Path dir, String... args) {
		var stdout = new ByteArrayOutputStream();
		command(dir).args(args).execute(stdout, logger).checkReturnCode();
		return stdout.toString(UTF_8).trim();
	}

	private Path repository(String name) throws Exception {
		var dir = Files.createDirectories(temp.resolve(name));
		git(dir, "init", "--template=", "-b", "main");
		Files.writeString(dir.resolve("tracked"), "source\n");
		commit(dir);
		return dir;
	}

	private void commit(Path dir) {
		git(dir, "add", ".");
		git(dir, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "test");
	}

	private void addSubmodule(Path repo, Path source, String path) {
		git(repo, "submodule", "add", source.toString(), path);
		commit(repo);
	}

	private void poison(Path repo) throws Exception {
		var script = temp.resolve("monitor");
		Files.writeString(script, "#!/bin/sh\ntouch '" + temp.resolve("executed") + "'\nexit 1\n");
		assertTrue(script.toFile().setExecutable(true));
		git(repo, "config", "core.fsmonitor", script.toString());
		var hook = repo.resolve(".git/hooks/post-checkout");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook, "#!/bin/sh\ntouch '" + temp.resolve("executed") + "'\n");
		assertTrue(hook.toFile().setExecutable(true));
	}

	private void checkout(Path target, Path source, boolean submodules) {
		var git = command(target);
		KubernetesHelper.initRepository(git, logger, logger);
		git.clearArgs();
		KubernetesHelper.cloneRepository(git, source.toString(), source.toString(), "main",
				git(source, "rev-parse", "HEAD"), false, submodules, 0, logger, logger);
	}

	private void setupWorkspace(Path workspace, Path source, Commandline git) {
		WorkspaceHelper.setupRepository(workspace.toFile(), git, "Test", "test@example.invalid",
				new DefaultCloneInfo("https://onedev.example/project", "test-token"), git(source, "rev-parse", "HEAD"),
				"main", false, true, temp.resolve("certs").toFile(), workspace.toString(),
				source.toString(), logger, logger);
	}

	private void assertHeader(Commandline command, List<String> presetArgs, String url, boolean expected) {
		var output = new ByteArrayOutputStream();
		command.args(presetArgs).addArgs("config", "--get-urlmatch", "http.extraHeader", url);
		var result = command.execute(output, logger);
		assertEquals(expected ? 0 : 1, result.getReturnCode(), url);
		assertEquals(expected ? "OneDevAuthorization: Bearer test-token" : "", output.toString(UTF_8).trim(), url);
	}

	@Test
	void scopesPersistedAndCommandLineHeadersForBothCredentialTypesAndSchemes() throws Exception {
		for (var scheme : new String[] {"http", "https"}) {
			var origin = scheme + "://onedev.example:6610/";
			var credentials = List.of(new DefaultCloneInfo(origin + "project", "test-token"),
					new HttpCloneInfo(origin + "project", "test-token"));
			for (var credential : credentials) {
				var target = repository(scheme + credential.getClass().getSimpleName());
				var command = command(target);
				credential.setupGitAuth(command, target.toFile(), target.toString(), logger, logger);
				var presetArgs = new ArrayList<>(command.args());
				// Check the saved configuration first, then the command-line override alone.
				for (var args : List.of(List.<String>of(), presetArgs)) {
					assertHeader(command, args, origin + "project", true);
					assertHeader(command, args, origin + "another-project", true);
					assertHeader(command, args, scheme + "://external.example:6610/project", false);
					assertHeader(command, args, scheme + "://onedev.example:6611/project", false);
					assertHeader(command, args, (scheme.equals("https") ? "http" : "https")
							+ "://onedev.example:6610/project", false);
					if (args.isEmpty())
						git(target, "config", "--unset-all", credential.getHttpExtraHeaderConfigKey());
				}
			}
		}
	}

	@Test
	void preservesHeaderScopeInRecursiveSubmodules() throws Exception {
		var leaf = repository("leaf");
		var module = repository("module");
		addSubmodule(module, leaf, "nested");
		var source = repository("source");
		addSubmodule(source, module, "module");
		var target = Files.createDirectories(temp.resolve("target"));
		var command = command(target);
		KubernetesHelper.initRepository(command, logger, logger);
		command.clearArgs();
		var credential = new DefaultCloneInfo("https://onedev.example/project", "test-token");
		credential.setupGitAuth(command, target.toFile(), target.toString(), logger, logger);
		KubernetesHelper.cloneRepository(command, source.toString(), credential.getCloneUrl(), "main",
				git(source, "rev-parse", "HEAD"), false, true, 0, logger, logger);
		for (var repo : List.of(target, target.resolve("module"), target.resolve("module/nested"))) {
			assertHeader(command(repo), List.of(), "https://onedev.example/another-project", true);
			assertHeader(command(repo), List.of(), "https://external.example/project", false);
			assertEquals(1, command(repo).args("config", "--get", "http.extraHeader")
					.execute(logger, logger).getReturnCode());
		}
	}

	@Test
	void discardsPoisonedMetadataAndSubmodulesButPreservesArtifacts() throws Exception {
		var leaf = repository("leaf");
		var module = repository("module");
		addSubmodule(module, leaf, "nested");
		var source = repository("source");
		addSubmodule(source, module, "deps/module name");
		var target = repository("target");
		git(target, "clone", module.toString(), "deps/module name");
		var existingModule = target.resolve("deps/module name");
		poison(existingModule);
		poison(target);
		Files.writeString(target.resolve("artifact"), "keep");
		Files.writeString(target.resolve("tracked"), "old working file");
		var outside = Files.writeString(temp.resolve("outside"), "keep outside");
		Files.createSymbolicLink(existingModule.resolve("outside-link"), outside);

		checkout(target, source, true);

		assertFalse(Files.exists(temp.resolve("executed")));
		assertEquals("keep", Files.readString(target.resolve("artifact")));
		assertEquals("keep outside", Files.readString(outside));
		assertEquals("source\n", Files.readString(target.resolve("tracked")));
		assertEquals("source\n", Files.readString(existingModule.resolve("nested/tracked")));
		assertFalse(Files.exists(existingModule.resolve("outside-link")));
		assertEquals(git(source, "rev-parse", "HEAD"), git(target, "rev-parse", "HEAD"));
	}

	@Test
	void replacesGitfilesAndLinksWithoutTouchingTheirTargets() throws Exception {
		var outside = repository("outside");
		var originalConfig = Files.readString(outside.resolve(".git/config"));
		for (var kind : new String[] {"gitfile", "symlink", "dangling"}) {
			var target = Files.createDirectories(temp.resolve(kind));
			var metadata = target.resolve(".git");
			if (kind.equals("gitfile"))
				Files.writeString(metadata, "gitdir: " + outside.resolve(".git") + "\n");
			else
				Files.createSymbolicLink(metadata, kind.equals("symlink") ? outside.resolve(".git") : temp.resolve("absent"));
			KubernetesHelper.initRepository(command(target), logger, logger);
			assertTrue(Files.isDirectory(metadata));
			assertFalse(Files.isSymbolicLink(metadata));
		}
		assertEquals(originalConfig, Files.readString(outside.resolve(".git/config")));
		assertFalse(Files.exists(temp.resolve("absent")));
	}

	@Test
	void rejectsSymlinkedSubmoduleAncestorsWithoutDeletingExternalFiles() throws Exception {
		var module = repository("module");
		var source = repository("source");
		addSubmodule(source, module, "deps/module");
		var target = Files.createDirectories(temp.resolve("target"));
		var outside = Files.createDirectories(temp.resolve("outside/module"));
		Files.writeString(outside.resolve("keep"), "keep");
		Files.createSymbolicLink(target.resolve("deps"), outside.getParent());
		assertThrows(ExplicitException.class, () -> checkout(target, source, true));
		assertEquals("keep", Files.readString(outside.resolve("keep")));
	}

	@Test
	void cleansSubmoduleLinksEvenWhenRetrievalIsDisabled() throws Exception {
		var module = repository("module");
		var source = repository("source");
		addSubmodule(source, module, "module");
		var target = Files.createDirectories(temp.resolve("target"));
		Files.createSymbolicLink(target.resolve("module"), module);
		checkout(target, source, false);
		assertFalse(Files.isSymbolicLink(target.resolve("module")));
		assertFalse(Files.exists(target.resolve("module/.git")));
		assertTrue(Files.exists(module.resolve(".git/config")));
	}

	@Test
	void completedWorkspaceRunsNoGitCommandsAndPreservesUserChanges() throws Exception {
		var source = repository("source");
		var workspace = Files.createDirectories(temp.resolve("workspace"));
		setupWorkspace(workspace, source, command(workspace));
		assertTrue(Files.isRegularFile(workspace.resolve(WorkspaceHelper.GIT_CLONE_SUCCESSFUL_FILE)));
		var work = workspace.resolve("work");
		poison(work);
		Files.writeString(work.resolve("tracked"), "user changes");
		var config = Files.readString(work.resolve(".git/config"));

		setupWorkspace(workspace, source, new Commandline(temp.resolve("nonexistent-git").toString()));

		assertEquals("user changes", Files.readString(work.resolve("tracked")));
		assertEquals(config, Files.readString(work.resolve(".git/config")));
		assertFalse(Files.exists(temp.resolve("executed")));
	}

	@Test
	void failedSubmoduleCloneIsNotMarkedSuccessfulAndRetryStartsFresh() throws Exception {
		var module = repository("module");
		var source = repository("source");
		addSubmodule(source, module, "module");
		git(source, "config", "-f", ".gitmodules", "submodule.module.url", temp.resolve("missing").toString());
		commit(source);
		var workspace = Files.createDirectories(temp.resolve("workspace"));
		assertThrows(RuntimeException.class, () -> setupWorkspace(workspace, source, command(workspace)));
		assertFalse(Files.exists(workspace.resolve(WorkspaceHelper.GIT_CLONE_SUCCESSFUL_FILE)));
		poison(workspace.resolve("work"));
		Files.writeString(workspace.resolve("work/artifact"), "keep");
		git(source, "config", "-f", ".gitmodules", "submodule.module.url", module.toString());
		commit(source);

		setupWorkspace(workspace, source, command(workspace));

		assertTrue(Files.isRegularFile(workspace.resolve(WorkspaceHelper.GIT_CLONE_SUCCESSFUL_FILE)));
		assertTrue(Files.exists(workspace.resolve("work/module/tracked")));
		assertEquals("keep", Files.readString(workspace.resolve("work/artifact")));
		assertFalse(Files.exists(temp.resolve("executed")));
	}

	@Test
	void doesNotFollowSuccessMarkerSymlinks() throws Exception {
		var source = repository("source");
		var workspace = Files.createDirectories(temp.resolve("workspace"));
		var outside = Files.writeString(temp.resolve("outside"), "keep");
		Files.createSymbolicLink(workspace.resolve(WorkspaceHelper.GIT_CLONE_SUCCESSFUL_FILE), outside);
		setupWorkspace(workspace, source, command(workspace));
		assertFalse(Files.isSymbolicLink(workspace.resolve(WorkspaceHelper.GIT_CLONE_SUCCESSFUL_FILE)));
		assertEquals("keep", Files.readString(outside));
	}

	@Test
	void rejectsRedirectedCredentialWrites() throws Exception {
		var outside = Files.writeString(temp.resolve("outside"), "keep");
		var certs = Files.createDirectories(temp.resolve("certs"));
		Files.writeString(certs.resolve("certificate.pem"), "certificate");
		var resource = Files.createDirectories(temp.resolve("resource"));
		var trustFile = resource.resolve("trust-certs.pem");
		Files.createSymbolicLink(trustFile, outside);
		assertThrows(ExplicitException.class, () -> KubernetesHelper.setupGitCerts(command(resource),
				certs.toFile(), trustFile.toFile(), trustFile.toString(), logger, logger));

		var ssh = new SshCloneInfo("ssh://example.invalid/repo", "private key", "known hosts");
		var sshDir = Files.createDirectories(resource.resolve(".ssh"));
		for (var name : new String[] {"id_rsa", "known_hosts"}) {
			var link = Files.createSymbolicLink(sshDir.resolve(name), outside);
			assertThrows(ExplicitException.class, () -> ssh.setupGitAuth(command(resource),
					resource.toFile(), resource.toString(), logger, logger));
			Files.delete(link);
		}
		Files.delete(sshDir);
		Files.createSymbolicLink(sshDir, temp);
		assertThrows(ExplicitException.class, () -> ssh.setupGitAuth(command(resource),
				resource.toFile(), resource.toString(), logger, logger));
		assertEquals("keep", Files.readString(outside));
	}
}
