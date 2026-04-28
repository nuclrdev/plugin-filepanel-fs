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
package dev.nuclr.plugin.core.panel.fs.plugin;

import java.awt.Component;
import java.awt.Desktop;
import java.awt.KeyboardFocusManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

final class LocalFileDeletionService {
    private static final boolean IS_MAC = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("mac");

    /**
     * Show the confirmation dialog, then run the deletion on a virtual thread
     * while displaying a live progress dialog.
     *
     * <p>Must be called from the Swing EDT.  Returns immediately after launching
     * the background thread; {@code onComplete} is called on the EDT when the
     * operation finishes (whether by success, error, or cancellation).
     *
     * @param parent          Swing parent for dialogs
     * @param selectedEntries items to delete
     * @param permanent       {@code true} = permanent delete,
     *                        {@code false} = move to Recycle Bin
     * @param onComplete      called on EDT when deletion is done (refresh hook)
     */
    void delete(
            Component parent,
            List<LocalFilePanelModel.Entry> selectedEntries,
            boolean permanent,
            Runnable onComplete) {

        if (!confirmDelete(parent, selectedEntries, permanent)) {
            return;
        }

        DeletionProgressDialog progressDialog = new DeletionProgressDialog(parent, permanent);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        progressDialog.setCancelAction(() -> cancelled.set(true));

        Thread.ofVirtual().start(() -> {
            try {
                if (permanent) {
                    deletePermWithProgress(parent, selectedEntries, progressDialog, cancelled);
                } else {
                    deleteToTrashWithProgress(parent, selectedEntries, progressDialog, cancelled);
                }
            } finally {
                // Always close dialog and refresh — even after error or cancel,
                // the panel should show the current (possibly partial) state.
                SwingUtilities.invokeLater(() -> {
                    progressDialog.close();
                    onComplete.run();
                });
            }
        });

        progressDialog.show();
    }

    // -------------------------------------------------------------------------
    // Permanent deletion (recursive, with progress)
    // -------------------------------------------------------------------------

    private static void deletePermWithProgress(
            Component parent,
            List<LocalFilePanelModel.Entry> entries,
            DeletionProgressDialog dialog,
            AtomicBoolean cancelled) {

        // ---- 1. Pre-scan: count total items ---------------------------------
        int total = scanTotal(entries);
        dialog.startDeleting("Deleting permanently...", total);

        // ---- 2. Delete each top-level entry ---------------------------------
        AtomicInteger done = new AtomicInteger(0);

        for (LocalFilePanelModel.Entry entry : entries) {
            if (cancelled.get()) break;
            try {
                deleteRecursively(entry.path(), dialog, done, total, cancelled);
            } catch (IOException ex) {
                showError(parent, "Permanent Delete",
                        "Could not delete \"" + entry.name() + "\": " + ex.getMessage());
                // Continue with remaining entries (best-effort)
            }
        }
    }

    /**
     * Recursive delete that reports each item and checks cancellation between files.
     */
    private static void deleteRecursively(
            Path root,
            DeletionProgressDialog dialog,
            AtomicInteger done,
            int total,
            AtomicBoolean cancelled) throws IOException {

        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (cancelled.get()) return FileVisitResult.TERMINATE;
                Files.delete(file);
                dialog.update(file, done.incrementAndGet(), total);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Log and skip unreadable files rather than aborting the whole tree.
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (cancelled.get()) return FileVisitResult.TERMINATE;
                if (exc != null) throw exc;
                Files.delete(dir);
                dialog.update(dir, done.incrementAndGet(), total);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Walk entries to count files + directories without deleting anything.
     * Returns a best-effort total; inaccessible sub-trees are skipped.
     */
    private static int scanTotal(List<LocalFilePanelModel.Entry> entries) {
        int[] count = {0};
        for (LocalFilePanelModel.Entry entry : entries) {
            if (entry.directory() && !entry.link()) {
                try {
                    Files.walkFileTree(entry.path(), new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            count[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                            count[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFileFailed(Path f, IOException ex) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException ignored) {
                    count[0]++; // at least count the directory itself
                }
            } else {
                count[0]++;
            }
        }
        return Math.max(count[0], 1);
    }

    // -------------------------------------------------------------------------
    // Trash / safe deletion (per-entry, with progress)
    // -------------------------------------------------------------------------

    private static void deleteToTrashWithProgress(
            Component parent,
            List<LocalFilePanelModel.Entry> entries,
            DeletionProgressDialog dialog,
            AtomicBoolean cancelled) {

        int total = entries.size();
        dialog.startDeleting("Moving to Trash...", total);

        for (int i = 0; i < total; i++) {
            if (cancelled.get()) break;
            LocalFilePanelModel.Entry entry = entries.get(i);
            if (!moveToTrash(entry.path())) {
                showError(parent, "Safe Delete",
                        "Could not move \"" + entry.name() + "\" to the Trash.");
                // Continue with remaining entries
            }
            dialog.update(entry.path(), i + 1, total);
        }
    }

    private static boolean moveToTrash(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                try {
                    File file = path.toFile();
                    if (desktop.moveToTrash(file)) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // Fall through to macOS-specific fallback below.
                }
            }
        }
        if (IS_MAC) {
            try {
                moveToMacTrash(path);
                return true;
            } catch (IOException | SecurityException | InvalidPathException ignored) {
                return false;
            }
        }
        return false;
    }

    private static void moveToMacTrash(Path path) throws IOException {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IOException("User home directory is unavailable.");
        }
        Path trashDir = Path.of(home, ".Trash");
        Files.createDirectories(trashDir);
        Path target = uniqueTrashTarget(trashDir, path.getFileName() != null ? path.getFileName().toString() : "item");
        try {
            Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(path, target);
        }
    }

    private static Path uniqueTrashTarget(Path trashDir, String fileName) throws IOException {
        String safeName = (fileName == null || fileName.isBlank()) ? "item" : fileName;
        Path candidate = trashDir.resolve(safeName);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        String baseName = safeName;
        String extension = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0) {
            baseName = safeName.substring(0, dot);
            extension = safeName.substring(dot);
        }

        for (int suffix = 1; suffix < 10_000; suffix++) {
            candidate = trashDir.resolve(baseName + " " + suffix + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not allocate a unique Trash name for " + safeName + ".");
    }

    // -------------------------------------------------------------------------
    // Confirmation dialog (unchanged from original)
    // -------------------------------------------------------------------------

    private boolean confirmDelete(
            Component parent,
            List<LocalFilePanelModel.Entry> selectedEntries,
            boolean permanent) {

        String operation      = permanent ? "Permanent Delete" : "Safe Delete";
        String modeDescription = permanent
                ? "These items will be deleted permanently."
                : IS_MAC
                        ? "These items will be moved to the Trash as a safe delete."
                        : "These items will be moved to the Recycle Bin as a safe delete.";

        StringBuilder message = new StringBuilder();
        message.append(modeDescription).append('\n').append('\n');
        message.append(selectedEntries.size() == 1 ? "Item:" : "Items:").append('\n');
        for (String line : summarizeEntries(selectedEntries)) {
            message.append(line).append('\n');
        }
        message.append('\n').append("Continue?");

        Object[] options = {"Yes", "No"};
        JOptionPane optionPane = new JOptionPane(
                message.toString(),
                permanent ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE,
                JOptionPane.YES_NO_OPTION,
                null, options, options[1]);

        JDialog confirmDialog = optionPane.createDialog(parent, operation);
        JButton yesButton = findButton(confirmDialog, "Yes");
        JButton noButton  = findButton(confirmDialog, "No");
        configureArrowKeyFocus(confirmDialog, yesButton, noButton);
        confirmDialog.setLocationRelativeTo(parent);
        if (noButton != null) {
            SwingUtilities.invokeLater(noButton::requestFocusInWindow);
        }
        confirmDialog.setVisible(true);
        confirmDialog.dispose();
        return "Yes".equals(optionPane.getValue());
    }

    private void configureArrowKeyFocus(JDialog d, JButton yes, JButton no) {
        if (d.getRootPane() == null || yes == null || no == null) return;
        JComponent root = d.getRootPane();
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("LEFT"),  "focusPrev");
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("RIGHT"), "focusNext");
        AbstractAction toggle = new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                (f == yes ? no : yes).requestFocusInWindow();
            }
        };
        root.getActionMap().put("focusPrev", toggle);
        root.getActionMap().put("focusNext", toggle);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<String> summarizeEntries(List<LocalFilePanelModel.Entry> entries) {
        List<String> lines = new ArrayList<>();
        int limit = Math.min(entries.size(), 8);
        for (int i = 0; i < limit; i++) {
            LocalFilePanelModel.Entry e = entries.get(i);
            String kind = e.link() ? "[Link] " : e.directory() ? "[Folder] " : "[File] ";
            lines.add(kind + e.name());
        }
        if (entries.size() > limit) {
            lines.add("... and " + (entries.size() - limit) + " more");
        }
        return lines;
    }

    private static JButton findButton(Component c, String text) {
        if (c instanceof JButton b && text.equals(b.getText())) return b;
        if (c instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                JButton b = findButton(child, text);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static void showError(Component parent, String title, String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE));
    }
}
