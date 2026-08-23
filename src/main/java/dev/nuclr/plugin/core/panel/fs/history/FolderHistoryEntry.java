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
package dev.nuclr.plugin.core.panel.fs.history;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of the folder history: a folder the user opened, and when they last opened it.
 *
 * <p>A folder appears in the history once, not once per visit — revisiting it moves the single
 * entry to the day it was last opened rather than adding a second line, which is what keeps the
 * list readable after a year of use.
 *
 * <p>The folder is kept as text rather than a {@link java.nio.file.Path} because the history
 * outlives the drive it names: an entry for a USB stick that is no longer plugged in must still
 * round-trip through the settings file and still be shown (and removable) in the list.
 *
 * @param path      the folder, as an absolute path in its own filesystem's syntax
 * @param visitedAt when it was last opened, in epoch milliseconds
 */
public record FolderHistoryEntry(String path, long visitedAt) {

	/** Persisted field names; also the JSON keys of one entry under the history setting. */
	static final String PathKey = "path";
	static final String VisitedAtKey = "visitedAt";

	public FolderHistoryEntry {
		path = path == null ? "" : path.trim();
	}

	/** The calendar day this visit falls on, which is the day heading it is listed under. */
	public LocalDate day(ZoneId zone) {
		return Instant.ofEpochMilli(visitedAt).atZone(zone == null ? ZoneId.systemDefault() : zone).toLocalDate();
	}

	/**
	 * Flatten to the JSON-friendly shape the settings store round-trips.
	 *
	 * @return a mutable map of a string and a number, never {@code null}
	 */
	public Map<String, Object> toMap() {
		var map = new LinkedHashMap<String, Object>();
		map.put(PathKey, path);
		map.put(VisitedAtKey, visitedAt);
		return map;
	}

	/**
	 * Rebuild an entry from its persisted map, tolerating whatever the file holds — a
	 * hand-edited settings file, or a timestamp that Jackson handed back as an {@code Integer},
	 * a {@code Double} or a string.
	 *
	 * @param map one entry as read back from the settings store; may be {@code null}
	 * @return the entry, or {@code null} if it carries no usable folder
	 */
	public static FolderHistoryEntry fromMap(Map<?, ?> map) {
		if (map == null) {
			return null;
		}
		Object path = map.get(PathKey);
		String text = path == null ? "" : path.toString().trim();
		if (text.isEmpty()) {
			return null;
		}
		return new FolderHistoryEntry(text, millis(map.get(VisitedAtKey)));
	}

	private static long millis(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null) {
			return 0L;
		}
		try {
			return Long.parseLong(value.toString().trim());
		} catch (NumberFormatException e) {
			return 0L;
		}
	}
}
