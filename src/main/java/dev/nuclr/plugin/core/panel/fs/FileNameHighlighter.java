package dev.nuclr.plugin.core.panel.fs;

import java.awt.Color;
import java.util.Locale;
import java.util.Set;

public final class FileNameHighlighter {

	private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "java", "py", "c", "cpp", "h", "html", "css", "js", "json", "xml", "csv", "log", "ini", "cfg", "conf", "properties", "yaml", "yml", "sh", "bat", "ps1", "pdf");
	private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp", "gif", "svg", "webp", "ico", "tiff", "psd", "ai", "eps");	
	private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav",  "xm", "mod", "s3m", "it", "669", "mid", "midi");
	private static final Set<String> ARCHIVE_EXTENSIONS = Set.of("zip", "jar", "rar", "tar", "gz");

	private final Color defaultColor;
	private final Color executableFileColor;
	private final Color hiddenFileColor;
	private final Color textFileColor;
	private final Color imageFileColor;
	private final Color audioFileColor;
	private final Color archiveFileColor;

	public FileNameHighlighter(Color defaultColor) {
		this.defaultColor = defaultColor;
		this.executableFileColor = new Color(231, 92, 92);
		this.hiddenFileColor = new Color(120, 120, 120);
		this.textFileColor = new Color(60, 150, 90);
		this.imageFileColor = new Color(40, 110, 180);
		this.audioFileColor = new Color(140, 70, 170);
		this.archiveFileColor = new Color(185, 110, 25);
	}

	public Color colorFor(LocalFilePanelModel.Entry entry) {
		if (entry.parent() || entry.directory()) {
			return defaultColor;
		}
		if (entry.hidden() || entry.system()) {
			return hiddenFileColor;
		}
		if (entry.executable()) {
			return executableFileColor;
		}

		String extension = extensionOf(entry.name());
		if (TEXT_EXTENSIONS.contains(extension)) {
			return textFileColor;
		}
		if (IMAGE_EXTENSIONS.contains(extension)) {
			return imageFileColor;
		}
		if (AUDIO_EXTENSIONS.contains(extension)) {
			return audioFileColor;
		}
		if (ARCHIVE_EXTENSIONS.contains(extension)) {
			return archiveFileColor;
		}
		return defaultColor;
	}

	private static String extensionOf(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return "";
		}
		return name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
