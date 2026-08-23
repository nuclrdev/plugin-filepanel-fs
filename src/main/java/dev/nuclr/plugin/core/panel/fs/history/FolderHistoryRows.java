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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the flat history into the lines the Alt+F12 list paints: the folders, oldest first,
 * with a day heading above each day's first folder.
 *
 * <p>Kept apart from the dialog because the grouping — not the painting — is the part with
 * rules worth pinning down: which day a visit belongs to, where a heading goes, and what the
 * filter leaves behind (a day with nothing left under it loses its heading too).
 *
 * <p>Oldest first, so the newest folder is the last line: that is where the cursor starts and
 * where the eye goes, and it means yesterday's folders keep their place instead of shuffling
 * down the list as today fills up.
 */
public final class FolderHistoryRows {

	private FolderHistoryRows() {
	}

	/** One line of the list: either a day heading or a folder. */
	public sealed interface Row permits Day, Visit {
	}

	/**
	 * A day heading — the separator drawn between one day's folders and the next.
	 *
	 * @param date the day the folders below it were opened on
	 */
	public record Day(LocalDate date) implements Row {
	}

	/**
	 * A folder line.
	 *
	 * @param entry the folder and the time it was last opened
	 */
	public record Visit(FolderHistoryEntry entry) implements Row {
	}

	/**
	 * Build the list's lines from the history.
	 *
	 * @param entries the history, in any order; may be {@code null}
	 * @param zone    the time zone the days are counted in; {@code null} means the system's
	 * @param filter  substring the folder must contain, case-insensitively; blank keeps all
	 * @return the lines, oldest first, each day's folders under their heading; never {@code null}
	 */
	public static List<Row> build(List<FolderHistoryEntry> entries, ZoneId zone, String filter) {

		var rows = new ArrayList<Row>();
		if (entries == null) {
			return rows;
		}

		var sorted = new ArrayList<>(entries);
		sorted.sort((left, right) -> Long.compare(left.visitedAt(), right.visitedAt()));

		LocalDate currentDay = null;
		for (FolderHistoryEntry entry : sorted) {
			if (!matches(entry, filter)) {
				continue;
			}
			LocalDate day = entry.day(zone);
			// The heading is emitted with the first folder that survives the filter, never
			// ahead of it, so filtering cannot leave an empty day behind.
			if (!day.equals(currentDay)) {
				rows.add(new Day(day));
				currentDay = day;
			}
			rows.add(new Visit(entry));
		}
		return rows;
	}

	/**
	 * Whether a folder should be shown for the given filter text.
	 *
	 * @param entry  the folder; {@code null} never matches
	 * @param filter substring to look for, case-insensitively; blank matches everything
	 * @return {@code true} when the folder should be listed
	 */
	public static boolean matches(FolderHistoryEntry entry, String filter) {
		if (entry == null) {
			return false;
		}
		if (filter == null || filter.isBlank()) {
			return true;
		}
		return entry.path().toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * The line the cursor should start on: the last folder, which is the most recently opened.
	 *
	 * @param rows the built lines
	 * @return its index, or {@code -1} when there are no folders to select
	 */
	public static int lastVisitIndex(List<Row> rows) {
		if (rows == null) {
			return -1;
		}
		for (int index = rows.size() - 1; index >= 0; index--) {
			if (rows.get(index) instanceof Visit) {
				return index;
			}
		}
		return -1;
	}
}
