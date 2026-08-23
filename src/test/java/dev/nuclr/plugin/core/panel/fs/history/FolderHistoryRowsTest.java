/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.fs.history.FolderHistoryRows.Day;
import dev.nuclr.plugin.core.panel.fs.history.FolderHistoryRows.Row;
import dev.nuclr.plugin.core.panel.fs.history.FolderHistoryRows.Visit;

/** Grouping the history into the lines the Alt+F12 list paints. */
class FolderHistoryRowsTest {

	private static final ZoneId Zone = ZoneId.of("UTC");

	private static FolderHistoryEntry at(String path, String date, int hour) {
		long millis = LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(hour, 0))
				.atZone(Zone).toInstant().toEpochMilli();
		return new FolderHistoryEntry(path, millis);
	}

	/** Renders the rows as text, so the shape of the whole list is what the test asserts on. */
	private static List<String> lines(List<Row> rows) {
		var lines = new ArrayList<String>();
		for (Row row : rows) {
			lines.add(row instanceof Day day ? "-- " + day.date() : ((Visit) row).entry().path());
		}
		return lines;
	}

	@Test
	void groupsByDayOldestFirstWithAHeadingAboveEachDay() {
		var entries = List.of(
				at("C:\\two", "2026-08-22", 9),
				at("C:\\one", "2026-08-21", 17),
				at("C:\\three", "2026-08-22", 14));

		assertEquals(List.of(
				"-- 2026-08-21", "C:\\one",
				"-- 2026-08-22", "C:\\two", "C:\\three"),
				lines(FolderHistoryRows.build(entries, Zone, "")));
	}

	@Test
	void aDayWithNoMatchesLosesItsHeadingToo() {
		var entries = List.of(
				at("C:\\nuclr\\sources", "2026-08-21", 9),
				at("C:\\temp", "2026-08-22", 9),
				at("C:\\nuclr\\commander", "2026-08-23", 9));

		assertEquals(List.of(
				"-- 2026-08-21", "C:\\nuclr\\sources",
				"-- 2026-08-23", "C:\\nuclr\\commander"),
				lines(FolderHistoryRows.build(entries, Zone, "nuclr")));
	}

	@Test
	void filteringIsCaseInsensitiveAndMatchesAnywhereInThePath() {
		assertTrue(FolderHistoryRows.matches(at("C:\\Nuclr\\Sources", "2026-08-21", 9), "sources"));
		assertTrue(FolderHistoryRows.matches(at("C:\\Nuclr\\Sources", "2026-08-21", 9), "  NUCLR "));
		assertFalse(FolderHistoryRows.matches(at("C:\\temp", "2026-08-21", 9), "nuclr"));
		assertTrue(FolderHistoryRows.matches(at("C:\\temp", "2026-08-21", 9), "   "));
	}

	@Test
	void theCursorStartsOnTheMostRecentFolder() {
		var rows = FolderHistoryRows.build(List.of(
				at("C:\\old", "2026-08-21", 9),
				at("C:\\newest", "2026-08-23", 9)), Zone, "");

		int index = FolderHistoryRows.lastVisitIndex(rows);

		assertEquals("C:\\newest", ((Visit) rows.get(index)).entry().path());
		assertEquals(rows.size() - 1, index, "the most recent folder is the last line");
	}

	@Test
	void anEmptyHistoryHasNoLinesAndNothingToSelect() {
		assertTrue(FolderHistoryRows.build(List.of(), Zone, "").isEmpty());
		assertTrue(FolderHistoryRows.build(null, Zone, "").isEmpty());
		assertEquals(-1, FolderHistoryRows.lastVisitIndex(List.of()));
		assertEquals(-1, FolderHistoryRows.lastVisitIndex(null));
	}

	@Test
	void aFilterThatMatchesNothingLeavesNoHeadings() {
		var rows = FolderHistoryRows.build(List.of(at("C:\\temp", "2026-08-21", 9)), Zone, "zzz");

		assertTrue(rows.isEmpty());
		assertEquals(-1, FolderHistoryRows.lastVisitIndex(rows));
	}
}
