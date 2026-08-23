/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.NuclrSettings;

/** Recording visits, forgetting them, and merging with what another commander has written. */
class FolderHistoryTest {

	/** In-memory {@link NuclrSettings}, standing in for the shared settings file. */
	private static final class FakeSettings implements NuclrSettings {

		private final Map<String, Object> values = new HashMap<>();

		@Override
		public void set(String namespace, String key, Object value) {
			values.put(namespace + "/" + key, value);
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T get(String namespace, String key) {
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
	private final FolderHistory history = new FolderHistory(new FolderHistoryStore(settings));

	private static List<String> paths(List<FolderHistoryEntry> entries) {
		var paths = new ArrayList<String>();
		entries.forEach(entry -> paths.add(entry.path()));
		return paths;
	}

	/** What a second commander would read out of the settings file. */
	private List<String> persisted() {
		return paths(new FolderHistoryStore(settings).load(Now));
	}

	@Test
	void recordsVisitsOldestFirst() {
		history.record("C:\\a", Now - 3_000);
		history.record("C:\\b", Now - 1_000);

		assertEquals(List.of("C:\\a", "C:\\b"), paths(history.entries()));
	}

	@Test
	void revisitingMovesTheFolderToTheEndRatherThanAddingALine() {
		history.record("C:\\a", Now - 3_000);
		history.record("C:\\b", Now - 2_000);
		history.record("C:\\a", Now - 1_000);

		assertEquals(List.of("C:\\b", "C:\\a"), paths(history.entries()));
	}

	@Test
	void anOlderVisitDoesNotMoveAFolderBackwards() {
		history.record("C:\\a", Now - 1_000);
		history.record("C:\\a", Now - 9_000);

		assertEquals(Now - 1_000, history.entries().get(0).visitedAt());
	}

	@Test
	void blankFoldersAreIgnored() {
		history.record(null, Now);
		history.record("   ", Now);

		assertTrue(history.entries().isEmpty());
	}

	@Test
	void flushWritesTheVisitsToTheSettingsStore() {
		history.record("C:\\a", Now - 1_000);
		history.flush();

		assertEquals(List.of("C:\\a"), persisted());
	}

	@Test
	void aSecondCommandersFoldersSurviveThisOnesWrite() {
		// Written straight into the settings file, as another running commander would.
		var rows = new ArrayList<Map<String, Object>>();
		rows.add(new FolderHistoryEntry("C:\\other", Now - 5_000).toMap());
		settings.set(FolderHistoryStore.Namespace, FolderHistoryStore.Key, rows);

		history.record("C:\\mine", Now - 1_000);
		history.flush();

		assertEquals(List.of("C:\\other", "C:\\mine"), persisted());
		assertEquals(List.of("C:\\other", "C:\\mine"), paths(history.entries()),
				"the merged view is adopted, so the other window's folders are listed too");
	}

	@Test
	void removingAFolderSticksEvenThoughTheWriteMerges() {
		history.record("C:\\gone", Now - 2_000);
		history.record("C:\\kept", Now - 1_000);
		history.flush();

		history.remove("C:\\gone");

		assertEquals(List.of("C:\\kept"), paths(history.entries()));
		assertEquals(List.of("C:\\kept"), persisted());
	}

	@Test
	void clearingForgetsEverythingIncludingWhatIsOnDisk() {
		history.record("C:\\a", Now - 2_000);
		history.flush();

		history.clear();

		assertTrue(history.entries().isEmpty());
		assertTrue(persisted().isEmpty());
	}

	@Test
	void whatIsAlreadyStoredIsLoadedOnStartup() {
		history.record("C:\\a", Now - 1_000);
		history.flush();

		var restarted = new FolderHistory(new FolderHistoryStore(settings));

		assertEquals(List.of("C:\\a"), paths(restarted.entries()));
	}

	@Test
	void theSharedHistoryIsOneInstanceForEveryPanel() {
		FolderHistory first = FolderHistory.shared(settings);
		FolderHistory second = FolderHistory.shared(settings);

		assertNotNull(first);
		assertSame(first, second);
	}
}
