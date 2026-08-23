/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.NuclrSettings;

/** Persisting, pruning and de-duplicating the folder history through the settings store. */
class FolderHistoryStoreTest {

	/** In-memory {@link NuclrSettings} standing in for the commander's JSON-backed one. */
	private static final class FakeSettings implements NuclrSettings {

		private final Map<String, Object> values = new HashMap<>();
		private RuntimeException failure;

		@Override
		public void set(String namespace, String key, Object value) {
			if (failure != null) {
				throw failure;
			}
			values.put(namespace + "/" + key, value);
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T get(String namespace, String key) {
			if (failure != null) {
				throw failure;
			}
			return (T) values.get(namespace + "/" + key);
		}

		@Override
		public <T> T getOrDefault(String namespace, String key, T defaultValue) {
			T value = get(namespace, key);
			return value == null ? defaultValue : value;
		}

		@Override
		public boolean isDeveloperModeOn() {
			return false;
		}
	}

	private static final long Now = 1_770_000_000_000L;

	private final FakeSettings settings = new FakeSettings();
	private final FolderHistoryStore store = new FolderHistoryStore(settings);

	private static FolderHistoryEntry entry(String path, long daysAgo) {
		return new FolderHistoryEntry(path, Now - Duration.ofDays(daysAgo).toMillis());
	}

	private static List<String> paths(List<FolderHistoryEntry> entries) {
		var paths = new ArrayList<String>();
		entries.forEach(entry -> paths.add(entry.path()));
		return paths;
	}

	@Test
	void savesAndLoadsOldestFirst() {
		store.save(List.of(entry("C:\\b", 1), entry("C:\\a", 5)), Now);

		assertEquals(List.of("C:\\a", "C:\\b"), paths(store.load(Now)));
	}

	@Test
	void keepsOneEntryPerFolderAtItsLatestVisit() {
		var kept = FolderHistoryStore.prune(
				List.of(entry("C:\\a", 10), entry("C:\\b", 5), entry("C:\\a", 1)), Now);

		assertEquals(List.of("C:\\b", "C:\\a"), paths(kept));
		assertEquals(Now - Duration.ofDays(1).toMillis(), kept.get(1).visitedAt());
	}

	@Test
	void purgesVisitsOlderThanAYear() {
		var kept = FolderHistoryStore.prune(List.of(entry("C:\\old", 366), entry("C:\\recent", 364)), Now);

		assertEquals(List.of("C:\\recent"), paths(kept));
	}

	@Test
	void purgesOnReadSoAnOlderFileIsCleanedUp() {
		// Written straight into the settings store, as a build without retention would have.
		var rows = new ArrayList<Map<String, Object>>();
		rows.add(entry("C:\\old", 400).toMap());
		rows.add(entry("C:\\recent", 2).toMap());
		settings.set(FolderHistoryStore.Namespace, FolderHistoryStore.Key, rows);

		assertEquals(List.of("C:\\recent"), paths(store.load(Now)));
	}

	@Test
	void dropsVisitsStampedInTheFuture() {
		var kept = FolderHistoryStore.prune(
				List.of(new FolderHistoryEntry("C:\\skewed", Now + Duration.ofDays(3).toMillis()),
						entry("C:\\here", 1)),
				Now);

		assertEquals(List.of("C:\\here"), paths(kept));
	}

	@Test
	void keepsAtMostTheCeilingDroppingTheOldest() {
		int total = FolderHistoryStore.MaxEntries + 20;
		var many = new ArrayList<FolderHistoryEntry>();
		for (int index = 0; index < total; index++) {
			// Index 0 is the oldest visit; the first 20 are the ones the ceiling should drop.
			many.add(new FolderHistoryEntry("C:\\folder" + index, Now - (total - index) * 1_000L));
		}

		var kept = FolderHistoryStore.prune(many, Now);

		assertEquals(FolderHistoryStore.MaxEntries, kept.size());
		assertFalse(paths(kept).contains("C:\\folder0"));
		assertFalse(paths(kept).contains("C:\\folder19"));
		assertEquals("C:\\folder20", kept.get(0).path());
		assertEquals("C:\\folder" + (total - 1), kept.get(kept.size() - 1).path());
	}

	@Test
	void unreadableSettingsGiveAnEmptyHistory() {
		settings.failure = new IllegalStateException("settings file is locked");

		assertTrue(store.load(Now).isEmpty());
		assertFalse(store.save(List.of(entry("C:\\a", 1)), Now));
	}

	@Test
	void withoutASettingsStoreNothingIsPersisted() {
		var detached = new FolderHistoryStore(null);

		assertFalse(detached.save(List.of(entry("C:\\a", 1)), Now));
		assertTrue(detached.load(Now).isEmpty());
	}

	@Test
	void skipsRowsThatAreNotEntries() {
		var rows = new ArrayList<Object>();
		rows.add("not a map");
		rows.add(new HashMap<>(Map.of("visitedAt", Now)));
		rows.add(entry("C:\\a", 1).toMap());
		settings.set(FolderHistoryStore.Namespace, FolderHistoryStore.Key, rows);

		assertEquals(List.of("C:\\a"), paths(store.load(Now)));
	}
}
