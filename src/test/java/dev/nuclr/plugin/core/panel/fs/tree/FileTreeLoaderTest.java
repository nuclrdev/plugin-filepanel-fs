package dev.nuclr.plugin.core.panel.fs.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileTreeLoaderTest {

	private static final String SEPARATOR = String.valueOf((char) 92);

	@Test
	void listsFoldersBeforeFilesAndSortsNamesIgnoringCase(@TempDir Path root) throws IOException {
		Files.createDirectory(root.resolve("zebra"));
		Files.createDirectory(root.resolve("Alpha"));
		Files.writeString(root.resolve("beta.txt"), "b");
		Files.writeString(root.resolve("Able.txt"), "a");

		List<FileTreeLoader.Entry> entries = FileTreeLoader.list(root);

		assertEquals(List.of("Alpha", "zebra", "Able.txt", "beta.txt"),
				entries.stream().map(entry -> entry.path().getFileName().toString()).toList());
		assertTrue(entries.get(0).directory());
		assertTrue(entries.get(1).directory());
		assertFalse(entries.get(2).directory());
	}

	@Test
	void loadReturnsImmediatelyAndEnumeratesOffTheEdt(@TempDir Path root) throws Exception {
		Files.writeString(root.resolve("item.txt"), "x");
		var result = new AtomicReference<FileTreeLoader.Result>();
		var callbackWasOnEdt = new AtomicBoolean(true);
		var finished = new CountDownLatch(1);
		var worker = new AtomicReference<Thread>();

		SwingUtilities.invokeAndWait(() -> worker.set(FileTreeLoader.load(root, loaded -> {
			callbackWasOnEdt.set(SwingUtilities.isEventDispatchThread());
			result.set(loaded);
			finished.countDown();
		})));

		assertTrue(worker.get().isVirtual());
		assertTrue(finished.await(2, TimeUnit.SECONDS));
		assertFalse(callbackWasOnEdt.get());
		assertNull(result.get().error());
		assertEquals("item.txt", result.get().entries().get(0).path().getFileName().toString());
	}

	@Test
	void asynchronousFailureStillCompletes(@TempDir Path root) throws Exception {
		Path missing = root.resolve("missing");
		var result = new AtomicReference<FileTreeLoader.Result>();
		var finished = new CountDownLatch(1);

		FileTreeLoader.load(missing, loaded -> {
			result.set(loaded);
			finished.countDown();
		});

		assertTrue(finished.await(2, TimeUnit.SECONDS));
		assertTrue(result.get().entries().isEmpty());
		assertTrue(result.get().error() instanceof IOException);
	}

	@Test
	void childTowardsReturnsTheNextStepDownTheTree(@TempDir Path root) {
		Path target = root.resolve("a").resolve("b").resolve("c.txt");

		assertEquals(root.resolve("a"), FileTreeLoader.childTowards(root, target));
		assertEquals(root.resolve("a").resolve("b"),
				FileTreeLoader.childTowards(root.resolve("a"), target));
	}

	@Test
	void childTowardsReturnsTheParentWhenItIsAlreadyTheTarget(@TempDir Path root) {
		assertEquals(root, FileTreeLoader.childTowards(root, root));
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void childTowardsRefusesATargetUnderADifferentRootInsteadOfThrowing() {
		// A UNC share is not one of the roots getRootDirectories() enumerates, so the tree can
		// be asked to walk from a drive letter towards one. Path.relativize throws across roots.
		Path drive = Paths.get("C:" + SEPARATOR);
		Path unc = Paths.get(SEPARATOR + SEPARATOR + "server" + SEPARATOR + "share"
				+ SEPARATOR + "folder");

		assertThrows(IllegalArgumentException.class, () -> drive.relativize(unc));
		assertNull(FileTreeLoader.childTowards(drive, unc));
	}

	@Test
	void childTowardsHandlesNullsAndUnrelatedSiblings(@TempDir Path root) {
		assertNull(FileTreeLoader.childTowards(null, root));
		assertNull(FileTreeLoader.childTowards(root, null));
		assertNull(FileTreeLoader.childTowards(root.resolve("a"), root.resolve("b")));
	}
}
