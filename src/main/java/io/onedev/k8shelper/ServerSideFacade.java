package io.onedev.k8shelper;

import static io.onedev.k8shelper.KubernetesHelper.readPlaceholderValues;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.tools.ant.DirectoryScanner;

import io.onedev.commons.utils.FileUtils;

public class ServerSideFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private transient Object step;
	
	private final String sourcePath;
	
	private final Set<String> includeFiles;
	
	private final Set<String> excludeFiles;
	
	private final Collection<String> placeholders;
	
	public ServerSideFacade(Object step, @Nullable String sourcePath, 
			Set<String> includeFiles, Set<String> excludeFiles,  Collection<String> placeholders) {
		this.step = step;
		this.sourcePath = sourcePath;
		this.includeFiles = includeFiles;
		this.excludeFiles = excludeFiles;
		this.placeholders = placeholders;
	}

	public Object getStep() {
		return step;
	}

	public String getSourcePath() {
		return sourcePath;
	}

	public Set<String> getIncludeFiles() {
		return includeFiles;
	}

	public Set<String> getExcludeFiles() {
		return excludeFiles;
	}

	public Collection<String> getPlaceholders() {
		return placeholders;
	}

	public void execute(File buildHome, Runner runner) throws Exception {
		File filesDir = FileUtils.createTempDir();
		try {
			Collection<String> placeholders = getPlaceholders();
			Map<String, String> placeholderValues = readPlaceholderValues(buildHome, placeholders);
			
			File sourceDir = new File(buildHome, "workspace");
			if (getSourcePath() != null)
				sourceDir = new File(sourceDir, replacePlaceholders(getSourcePath(), placeholderValues));
			
			Collection<String> includeFiles = replacePlaceholders(getIncludeFiles(), placeholderValues);
			Collection<String> excludeFiles = replacePlaceholders(getExcludeFiles(), placeholderValues);

	    	DirectoryScanner scanner = new DirectoryScanner();
	    	scanner.setBasedir(sourceDir);
	    	scanner.setIncludes(includeFiles.toArray(new String[0]));
	    	scanner.setExcludes(excludeFiles.toArray(new String[0]));
	    	scanner.scan();
			
			for (String scanned: scanner.getIncludedFiles()) {
				try {
					FileUtils.copyFile(new File(sourceDir, scanned), new File(filesDir, scanned));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			
			Map<String, byte[]> outputFiles = runner.run(filesDir, placeholderValues);
			
			if (outputFiles != null) {
				for (Map.Entry<String, byte[]> entry: outputFiles.entrySet()) {
					FileUtils.writeByteArrayToFile(
							new File(buildHome, entry.getKey()), 
							entry.getValue());
				}
			}
		} finally {
			FileUtils.deleteDir(filesDir);
		}
	}
	
	public interface Runner {
		
		Map<String, byte[]> run(File inputDir, Map<String, String> placeholderValues);
		
	}
	
}
