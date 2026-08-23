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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.nuclr.platform.NuclrSettings;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads and writes the folder history through the platform's own settings store, under this
 * plugin's namespace — the same mechanism the F2 user commands use, so the history lands in
 * {@code config/dev.nuclr.plugin.core.panel.fs/settings.json} beside them.
 *
 * <p>The whole history is one setting value: a list of flat maps (see
 * {@link FolderHistoryEntry#toMap}), oldest first, which is also the order the list shows.
 *
 * <p>Two rules keep the value from growing without bound, and both are applied on the way in
 * as well as on the way out, so a file written by an older build (or hand-edited) is cleaned
 * up the first time it is read:
 * <ul>
 *   <li>{@link #Retention} — visits older than a year are dropped, as the user asked.</li>
 *   <li>{@link #MaxEntries} — a hard ceiling, so a scripted walk over ten thousand folders
 *       cannot turn the settings file into something the commander has to parse at startup.</li>
 * </ul>
 *
 * <p>Reads never throw: a missing, empty or corrupt setting yields an empty history rather
 * than a panel that will not open.
 */
@Slf4j
public class FolderHistoryStore {

	/** Settings namespace; matches the plugin id so the value sits with the plugin's own config. */
	static final String Namespace = "dev.nuclr.plugin.core.panel.fs";

	/** Key holding the whole history within {@link #Namespace}. */
	static final String Key = "folderHistory";

	/** How long a visit is remembered. Anything older is purged on the next read or write. */
	public static final Duration Retention = Duration.ofDays(365);

	/** Ceiling on remembered folders, oldest dropped first. */
	public static final int MaxEntries = 1000;

	private final NuclrSettings settings;

	/**
	 * @param settings the platform settings store, from {@code NuclrPluginContext.getSettings()};
	 *                 may be {@code null}, in which case the history is simply not persisted
	 */
	public FolderHistoryStore(NuclrSettings settings) {
		this.settings = settings;
	}

	/**
	 * Load the remembered folders, oldest visit first, already pruned.
	 *
	 * @param now the current time in epoch milliseconds, against which retention is measured
	 * @return the entries, never {@code null}; empty when nothing is stored or it is unreadable
	 */
	public List<FolderHistoryEntry> load(long now) {

		if (settings == null) {
			return new ArrayList<>();
		}

		Object stored;
		try {
			stored = settings.get(Namespace, Key);
		} catch (RuntimeException e) {
			log.warn("Could not read the folder history: {}", e.getMessage(), e);
			return new ArrayList<>();
		}

		var entries = new ArrayList<FolderHistoryEntry>();
		if (!(stored instanceof Iterable<?> rows)) {
			return entries;
		}
		for (Object row : rows) {
			if (row instanceof Map<?, ?> map) {
				FolderHistoryEntry entry = FolderHistoryEntry.fromMap(map);
				if (entry != null) {
					entries.add(entry);
				}
			}
		}
		return prune(entries, now);
	}

	/**
	 * Replace the stored history with {@code entries}, pruned first.
	 *
	 * @param entries the entries to persist
	 * @param now     the current time in epoch milliseconds, against which retention is measured
	 * @return {@code true} if they were written, {@code false} if the store refused or is absent
	 */
	public boolean save(List<FolderHistoryEntry> entries, long now) {

		if (settings == null) {
			log.debug("No settings store available; folder history not persisted");
			return false;
		}

		var maps = new ArrayList<Map<String, Object>>();
		for (FolderHistoryEntry entry : prune(entries, now)) {
			maps.add(entry.toMap());
		}

		try {
			settings.set(Namespace, Key, maps);
			return true;
		} catch (RuntimeException e) {
			log.warn("Could not save the folder history: {}", e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Normalise a history: one entry per folder (the most recent visit wins), nothing older
	 * than {@link #Retention}, at most {@link #MaxEntries} entries, oldest first.
	 *
	 * <p>Also drops visits stamped in the future beyond a day's clock skew — a bad system
	 * clock would otherwise leave an entry pinned to the bottom of the list for a year.
	 *
	 * @param entries the entries to normalise; may be {@code null} and is never modified
	 * @param now     the current time in epoch milliseconds
	 * @return a fresh mutable list, never {@code null}
	 */
	public static List<FolderHistoryEntry> prune(List<FolderHistoryEntry> entries, long now) {

		long oldest = now - Retention.toMillis();
		long newest = now + Duration.ofDays(1).toMillis();

		// Keyed by folder, so a folder visited on three days keeps one line, at its last visit.
		var byPath = new LinkedHashMap<String, FolderHistoryEntry>();
		if (entries != null) {
			for (FolderHistoryEntry entry : entries) {
				if (entry == null || entry.path().isEmpty()) {
					continue;
				}
				if (entry.visitedAt() < oldest || entry.visitedAt() > newest) {
					continue;
				}
				FolderHistoryEntry existing = byPath.get(entry.path());
				if (existing == null || existing.visitedAt() <= entry.visitedAt()) {
					byPath.put(entry.path(), entry);
				}
			}
		}

		var kept = new ArrayList<>(byPath.values());
		kept.sort(Comparator.comparingLong(FolderHistoryEntry::visitedAt));

		// Over the ceiling, the oldest visits go: they are the ones the user is least likely
		// to be looking for, and the ones a purge would have taken next anyway.
		if (kept.size() > MaxEntries) {
			return new ArrayList<>(kept.subList(kept.size() - MaxEntries, kept.size()));
		}
		return kept;
	}
}
