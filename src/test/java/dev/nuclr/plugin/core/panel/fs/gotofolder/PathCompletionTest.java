package dev.nuclr.plugin.core.panel.fs.gotofolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathCompletionTest {

	@TempDir
	Path root;

	private Path projects;

	@BeforeEach
	void createTree() throws IOException {
		projects = Files.createDirectories(root.resolve("projects"));
		Files.createDirectories(projects.resolve("nuclr"));
		Files.createDirectories(projects.resolve("nuclr-plugins"));
		Files.createDirectories(projects.resolve("other"));
		Files.createFile(projects.resolve("notes.txt"));
	}

	@Test
	void resolvesAnAbsolutePathAsItIs() {
		assertEquals(projects, PathCompletion.resolve(projects.toString(), null));
	}

	@Test
	void resolvesRelativeInputAgainstTheCurrentFolder() {
		assertEquals(projects.resolve("nuclr"), PathCompletion.resolve("nuclr", projects));
		assertEquals(root, PathCompletion.resolve("..", projects));
	}

	@Test
	void dropsSurroundingQuotesAndWhitespace() {
		assertEquals(projects, PathCompletion.resolve("  \"" + projects + "\"  ", null));
	}

	@Test
	void expandsALeadingTildeToTheHomeDirectory() {
		Path home = Path.of(System.getProperty("user.home"));

		assertEquals(home, PathCompletion.resolve("~", null));
		assertEquals(home.resolve("Documents"), PathCompletion.resolve("~/Documents", null));
		assertEquals(home.resolve("Documents"), PathCompletion.resolve("~\\Documents", null));
	}

	@Test
	void blankTextNamesNothing() {
		assertNull(PathCompletion.resolve(null, projects));
		assertNull(PathCompletion.resolve("   ", projects));
	}

	@Test
	void suggestsFoldersMatchingTheLastSegmentIgnoringCase() {
		List<Path> found = PathCompletion.suggestions(projects.resolve("NUC").toString(), null);

		assertEquals(List.of(projects.resolve("nuclr"), projects.resolve("nuclr-plugins")), found);
	}

	@Test
	void suggestsEverythingInsideAFolderWhenTheTextEndsWithASeparator() {
		List<Path> found = PathCompletion.suggestions(projects + java.io.File.separator, null);

		assertEquals(List.of(projects.resolve("nuclr"), projects.resolve("nuclr-plugins"),
				projects.resolve("other")), found);
	}

	@Test
	void filesAreNeverSuggested() {
		List<Path> found = PathCompletion.suggestions(projects.resolve("not").toString(), null);

		assertTrue(found.isEmpty(), "notes.txt is a file, not somewhere to go: " + found);
	}

	@Test
	void emptyTextSuggestsTheCurrentFolderContents() {
		List<Path> found = PathCompletion.suggestions("", projects);

		assertEquals(List.of(projects.resolve("nuclr"), projects.resolve("nuclr-plugins"),
				projects.resolve("other")), found);
	}

	@Test
	void suggestionsForAnUnreadableOrMissingParentAreEmpty() {
		assertTrue(PathCompletion.suggestions(root.resolve("nowhere").resolve("x").toString(), null).isEmpty());
	}

	@Test
	void commonPrefixIsWhatTabCanFillIn() {
		List<Path> found = PathCompletion.suggestions(projects.resolve("nuc").toString(), null);

		assertEquals("nuclr", PathCompletion.commonPrefix(found));
		assertEquals("", PathCompletion.commonPrefix(List.of()));
		assertEquals("", PathCompletion.commonPrefix(
				List.of(projects.resolve("nuclr"), projects.resolve("other"))));
	}
}
