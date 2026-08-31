/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.fs.gotofolder;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * What the Go-to-folder field means by what has been typed into it: the path it names, and the
 * folders that could still finish it.
 *
 * <p>Kept apart from the dialog because it is the half worth testing, and because every method
 * here touches the filesystem — the dialog calls them off the EDT, where a listing of a
 * disconnected network share is allowed to take its time.
 */
@Slf4j
public final class PathCompletion {

	/** Most suggestions offered at once; a folder of thousands need not be listed in full. */
	static final int MaxSuggestions = 200;

	private PathCompletion() {
	}

	/**
	 * The path the text names, or {@code null} when it names none.
	 *
	 * <p>Quotes around it are dropped (a path pasted from Explorer or a terminal arrives
	 * wrapped in them), a leading {@code ~} becomes the home directory, and anything not
	 * absolute is taken as relative to the folder the panel is showing.
	 *
	 * @param text          what the user typed; {@code null} or blank names nothing
	 * @param currentFolder the folder the panel is on, for relative input; may be {@code null}
	 * @return the absolute, normalized path, or {@code null} if the text cannot be one
	 */
	public static Path resolve(String text, Path currentFolder) {

		String trimmed = unquote(text);
		if (trimmed.isEmpty()) {
			return null;
		}

		try {
			Path path = expandHome(trimmed);
			if (path == null) {
				path = Path.of(trimmed);
			}
			if (!path.isAbsolute() && currentFolder != null) {
				path = currentFolder.resolve(path);
			}
			return path.toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			log.debug("Go to folder: '{}' is not a usable path: {}", trimmed, e.getMessage());
			return null;
		}
	}

	/**
	 * The folders that could finish what has been typed, in name order.
	 *
	 * <p>Text ending in a separator lists that folder's children; otherwise the last segment is
	 * a prefix matched case-insensitively against the children of the segment before it. Empty
	 * text lists the current folder, so the dialog opens showing somewhere to go rather than a
	 * blank list.
	 *
	 * @param text          what the user typed
	 * @param currentFolder the folder the panel is on; may be {@code null}
	 * @return matching directories, at most {@link #MaxSuggestions} of them, never {@code null}
	 */
	public static List<Path> suggestions(String text, Path currentFolder) {

		String trimmed = unquote(text);

		if (trimmed.isEmpty()) {
			return childDirectories(currentFolder, "");
		}

		if (endsWithSeparator(trimmed)) {
			return childDirectories(resolve(trimmed, currentFolder), "");
		}

		Path resolved = resolve(trimmed, currentFolder);
		if (resolved == null) {
			return List.of();
		}

		Path parent = resolved.getParent();
		Path name = resolved.getFileName();

		// A root ("C:\", "/") has no parent and no name of its own to match on: it is already
		// complete, so offer what is inside it.
		if (parent == null || name == null) {
			return childDirectories(resolved, "");
		}

		return childDirectories(parent, name.toString());
	}

	/**
	 * The longest text every suggestion agrees on, for Tab completion — the whole name when
	 * only one folder matches, the shared prefix when several do.
	 *
	 * @param suggestions the folders offered
	 * @return the common prefix of their names, empty when they share none
	 */
	public static String commonPrefix(List<Path> suggestions) {

		if (suggestions == null || suggestions.isEmpty()) {
			return "";
		}

		String prefix = nameOf(suggestions.get(0));

		for (Path suggestion : suggestions.subList(1, suggestions.size())) {
			String candidate = nameOf(suggestion);
			int shared = 0;
			int max = Math.min(prefix.length(), candidate.length());
			while (shared < max && Character.toLowerCase(prefix.charAt(shared)) == Character
					.toLowerCase(candidate.charAt(shared))) {
				shared++;
			}
			prefix = prefix.substring(0, shared);
			if (prefix.isEmpty()) {
				return "";
			}
		}

		return prefix;
	}

	/** Directories directly inside {@code folder} whose name starts with {@code prefix}. */
	private static List<Path> childDirectories(Path folder, String prefix) {

		if (folder == null || !Files.isDirectory(folder)) {
			return List.of();
		}

		String wanted = prefix.toLowerCase(Locale.ROOT);
		var matches = new ArrayList<Path>();

		try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
			for (Path child : children) {
				String name = nameOf(child);
				if (!name.toLowerCase(Locale.ROOT).startsWith(wanted)) {
					continue;
				}
				// Checked last: it is the expensive half, and on a slow share it need only be paid
				// for the entries whose name already matches.
				if (Files.isDirectory(child)) {
					matches.add(child);
				}
			}
		} catch (IOException | RuntimeException e) {
			// An unreadable or vanished folder simply has nothing to suggest.
			log.debug("Go to folder: cannot list {}: {}", folder, e.getMessage());
			return List.of();
		}

		matches.sort(Comparator.comparing(path -> nameOf(path).toLowerCase(Locale.ROOT)));
		return matches.size() > MaxSuggestions ? List.copyOf(matches.subList(0, MaxSuggestions)) : matches;
	}

	/** The file name of a path, or its whole text for a root, which has none. */
	static String nameOf(Path path) {
		Path name = path.getFileName();
		return name != null ? name.toString() : path.toString();
	}

	/** Strip surrounding whitespace and a matched pair of quotes. */
	private static String unquote(String text) {

		if (text == null) {
			return "";
		}

		String trimmed = text.trim();

		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
		}

		return trimmed;
	}

	/** Expand a leading {@code ~}, or return {@code null} when the text does not start with one. */
	private static Path expandHome(String text) {

		if (!text.startsWith("~")) {
			return null;
		}

		Path home = Path.of(System.getProperty("user.home", ""));
		String rest = text.substring(1);

		if (rest.isEmpty()) {
			return home;
		}

		if (rest.charAt(0) != '/' && rest.charAt(0) != '\\') {
			// "~name" is another user's home on Unix, which we do not resolve; leave it alone.
			return null;
		}

		String relative = rest.substring(1).replace('\\', '/');
		return relative.isEmpty() ? home : home.resolve(relative);
	}

	private static boolean endsWithSeparator(String text) {
		char last = text.charAt(text.length() - 1);
		return last == '/' || last == '\\';
	}
}
