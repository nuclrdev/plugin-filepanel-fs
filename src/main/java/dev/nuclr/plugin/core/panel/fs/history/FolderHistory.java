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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.NuclrSettings;
import lombok.extern.slf4j.Slf4j;

/**
 * The folder history the panels record into and the Alt+F12 list reads from: one shared,
 * in-memory copy in front of {@link FolderHistoryStore}.
 *
 * <p>It is shared ({@link #shared}) because both panels — and the temp panel — are separate
 * plugin instances writing one history. Were each to keep its own copy, the last one to save
 * would overwrite the other's folders. Being a static within the plugin's own classloader,
 * the instance lives and dies with the plugin.
 *
 * <p>Writes are deferred by {@link #WriteDelayMillis} and coalesced. Recording happens for
 * every directory a panel opens, so it must cost nothing on the caller's thread: a settings
 * write takes a cross-process lock and rewrites the file, and walking a tree with the arrow
 * keys would otherwise do that once per keystroke.
 *
 * <p>A save merges with what is on disk rather than replacing it, so a second commander
 * running against the same config keeps its folders. Removals are held as tombstones until the
 * write that applies them, so a merge cannot resurrect what the user has just deleted.
 */
@Slf4j
public final class FolderHistory {

	/** How long recording waits for further visits before writing them out. */
	static final long WriteDelayMillis = 3_000;

	private static FolderHistory instance;

	private final FolderHistoryStore store;

	/** Keyed by folder; one entry per folder, as the list shows it. Guarded by {@code this}. */
	private final LinkedHashMap<String, FolderHistoryEntry> entries = new LinkedHashMap<>();

	/** Folders removed since the last write, withheld from the next merge. Guarded by {@code this}. */
	private final Set<String> removed = new HashSet<>();

	/** Set by {@link #clear()}: the next write replaces the stored history instead of merging. */
	private boolean cleared;

	/** True while a deferred write is pending, so further visits do not schedule another. */
	private final AtomicBoolean writeScheduled = new AtomicBoolean();

	private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "folder-history-writer");
		thread.setDaemon(true);
		return thread;
	});

	FolderHistory(FolderHistoryStore store) {
		this.store = store;
		for (FolderHistoryEntry entry : store.load(System.currentTimeMillis())) {
			entries.put(entry.path(), entry);
		}
	}

	/**
	 * The history shared by every panel in this commander, created from the first settings
	 * store handed to it.
	 *
	 * @param settings the platform settings store; may be {@code null}, giving a history that
	 *                 lives only as long as the session
	 * @return the shared instance, never {@code null}
	 */
	public static synchronized FolderHistory shared(NuclrSettings settings) {
		if (instance == null) {
			instance = new FolderHistory(new FolderHistoryStore(settings));
		}
		return instance;
	}

	/**
	 * Remember that a folder was opened, or move it to now if it is already remembered.
	 * Returns immediately; the settings file is written a few seconds later.
	 *
	 * @param path the folder as an absolute path; ignored when blank
	 */
	public void record(String path) {
		record(path, System.currentTimeMillis());
	}

	/**
	 * Remember a visit at a given time — the entry point tests use, and the one
	 * {@link #record(String)} delegates to.
	 *
	 * @param path      the folder as an absolute path; ignored when blank
	 * @param visitedAt the visit time in epoch milliseconds
	 */
	public void record(String path, long visitedAt) {

		if (path == null || path.isBlank()) {
			return;
		}
		String folder = path.trim();

		synchronized (this) {
			FolderHistoryEntry existing = entries.get(folder);
			if (existing != null && existing.visitedAt() >= visitedAt) {
				return;
			}
			// Re-inserting rather than replacing keeps the map in visit order, which is the
			// order the list is built in.
			entries.remove(folder);
			entries.put(folder, new FolderHistoryEntry(folder, visitedAt));
			removed.remove(folder);
		}

		scheduleWrite();
	}

	/**
	 * The remembered folders, oldest visit first — the order the Alt+F12 list shows them in.
	 *
	 * @return a snapshot, safe to hold and iterate; never {@code null}
	 */
	public synchronized List<FolderHistoryEntry> entries() {
		var snapshot = new ArrayList<>(entries.values());
		snapshot.sort(Comparator.comparingLong(FolderHistoryEntry::visitedAt));
		return snapshot;
	}

	/**
	 * Forget one folder. Written out at once rather than deferred: the user asked for it to
	 * be gone, and it should be gone even if the commander does not shut down cleanly.
	 *
	 * @param path the folder to forget
	 */
	public void remove(String path) {
		if (path == null || path.isBlank()) {
			return;
		}
		synchronized (this) {
			if (entries.remove(path.trim()) == null) {
				return;
			}
			removed.add(path.trim());
		}
		flush();
	}

	/** Forget every folder, replacing the stored history rather than merging with it. */
	public void clear() {
		synchronized (this) {
			entries.clear();
			removed.clear();
			cleared = true;
		}
		flush();
	}

	/** Write any pending changes now, on the calling thread. Called on unload. */
	public void flush() {
		writeScheduled.set(false);
		try {
			write();
		} catch (RuntimeException e) {
			log.warn("Could not write the folder history: {}", e.getMessage(), e);
		}
	}

	private void scheduleWrite() {
		if (!writeScheduled.compareAndSet(false, true)) {
			return;
		}
		try {
			writer.schedule(this::flush, WriteDelayMillis, TimeUnit.MILLISECONDS);
		} catch (RuntimeException e) {
			// A rejected task (the executor is shutting down) must not cost the visit itself.
			writeScheduled.set(false);
			log.debug("Folder history write not scheduled: {}", e.getMessage());
		}
	}

	/**
	 * Merge this session's folders with whatever is on disk and store the result, so a second
	 * commander editing the same file keeps its own visits.
	 */
	private void write() {

		long now = System.currentTimeMillis();

		boolean replace;
		synchronized (this) {
			replace = cleared;
		}
		List<FolderHistoryEntry> stored = replace ? List.of() : store.load(now);

		List<FolderHistoryEntry> toWrite;
		synchronized (this) {
			var merged = new LinkedHashMap<String, FolderHistoryEntry>();
			for (FolderHistoryEntry entry : stored) {
				if (!removed.contains(entry.path())) {
					merged.put(entry.path(), entry);
				}
			}
			for (FolderHistoryEntry entry : entries.values()) {
				FolderHistoryEntry existing = merged.get(entry.path());
				if (existing == null || existing.visitedAt() < entry.visitedAt()) {
					merged.put(entry.path(), entry);
				}
			}
			toWrite = FolderHistoryStore.prune(new ArrayList<>(merged.values()), now);

			// Adopt the merged view, so the list shows the other window's folders too.
			entries.clear();
			for (FolderHistoryEntry entry : toWrite) {
				entries.put(entry.path(), entry);
			}
			removed.clear();
			cleared = false;
		}

		store.save(toWrite, now);
	}
}
