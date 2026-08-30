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
package dev.nuclr.plugin.core.panel.fs.tree;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Reads one level of a filesystem tree without ever doing filesystem work on the EDT. */
final class FileTreeLoader {

	record Entry(Path path, boolean directory) {
	}

	record Result(List<Entry> entries, IOException error) {
		static Result success(List<Entry> entries) {
			return new Result(List.copyOf(entries), null);
		}

		static Result failure(IOException error) {
			return new Result(List.of(), error);
		}
	}

	private static final Comparator<Entry> EntryOrder = Comparator
			.comparing(Entry::directory).reversed()
			.thenComparing(entry -> displayName(entry.path()), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(entry -> displayName(entry.path()));

	private FileTreeLoader() {
	}

	/** Start one directory enumeration on a virtual thread and return immediately. */
	static Thread load(Path directory, Consumer<Result> completed) {
		return Thread.ofVirtual().name("file-tree-load").start(() -> {
			try {
				completed.accept(Result.success(list(directory)));
			} catch (IOException e) {
				completed.accept(Result.failure(e));
			} catch (DirectoryIteratorException e) {
				completed.accept(Result.failure(e.getCause()));
			} catch (RuntimeException e) {
				completed.accept(Result.failure(new IOException(e.getMessage(), e)));
			}
		});
	}

	/** Package-visible for focused tests; callers in the UI use {@link #load}. */
	static List<Entry> list(Path directory) throws IOException {
		var entries = new ArrayList<Entry>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
			for (Path child : stream) {
				try {
					BasicFileAttributes attributes = Files.readAttributes(
							child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					// Symlinked directories are leaves. Following them could turn an innocent tree
					// expansion into a cycle or a trip to a disconnected network target.
					entries.add(new Entry(child, attributes.isDirectory()));
				} catch (IOException | SecurityException ignored) {
					// A directory can change while it is enumerated. Keep the rest of the tree
					// useful instead of failing the complete level for one vanished entry.
				}
			}
		}
		entries.sort(EntryOrder);
		return List.copyOf(entries);
	}

	/**
	 * The immediate child of {@code parent} that leads towards {@code target}, or {@code null}
	 * when target does not lie under parent. Guarded with {@link Path#startsWith} on purpose:
	 * relativizing across two different roots throws, and a UNC share is not one of the roots
	 * {@code FileSystems.getDefault().getRootDirectories()} enumerates.
	 */
	static Path childTowards(Path parent, Path target) {
		if (parent == null || target == null || !target.startsWith(parent)) {
			return null;
		}
		Path relative = parent.relativize(target);
		return relative.getNameCount() == 0 ? parent : parent.resolve(relative.getName(0)).normalize();
	}

	static String displayName(Path path) {
		if (path == null) {
			return "";
		}
		Path name = path.getFileName();
		return name == null ? path.toString() : name.toString();
	}
}
