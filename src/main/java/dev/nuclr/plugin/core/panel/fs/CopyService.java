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
package dev.nuclr.plugin.core.panel.fs;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrResourcePath;
import dev.nuclr.plugin.core.panel.fs.copy.CancellationController;
import dev.nuclr.plugin.core.panel.fs.copy.ConflictHandler;
import dev.nuclr.plugin.core.panel.fs.copy.ConflictMode;
import dev.nuclr.plugin.core.panel.fs.copy.CopyDialogResult;
import dev.nuclr.plugin.core.panel.fs.copy.CopyOptions;
import dev.nuclr.plugin.core.panel.fs.copy.CopyProgressListener;
import dev.nuclr.plugin.core.panel.fs.copy.CopyProgressSnapshot;
import dev.nuclr.plugin.core.panel.fs.copy.CopyRequest;
import dev.nuclr.plugin.core.panel.fs.copy.CopyWorkflow;
import dev.nuclr.plugin.core.panel.fs.plugin.LocalFilePanel;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin-level entry point for the copy feature.
 *
 * <p>Owns a single reusable copy engine and a shared virtual-thread executor.
 * Each call to {@link #copy} creates its own {@link CopyWorkflow} (with captured
 * per-run state), {@link CopyProgressDialog}, and {@link CancellationController}
 * so concurrent copies are fully isolated.
 *
 * <p>Must be called from the Swing EDT.
 */
@Slf4j
public class CopyService {

    /** One virtual thread per copy run — no pool exhaustion, no queuing. */
    private static final Executor EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** Stateless execution engine; shared across all runs. */
    private final dev.nuclr.plugin.core.panel.fs.copy.CopyService engine =
            new dev.nuclr.plugin.core.panel.fs.copy.CopyService();

    // -------------------------------------------------------------------------
    // Public entry point (called from the EDT)
    // -------------------------------------------------------------------------

    /**
     * Start the full copy workflow for the given resources.
     *
     * <p>Shows the setup dialog synchronously.  If the user confirms, shows a
     * live progress dialog and starts copying in the background.  Refreshes
     * {@code parent} (the target panel) when the copy completes or is cancelled.
     *
     * @param parent    Swing component used as the dialog owner and for the
     *                  post-copy directory refresh (typically the target panel)
     * @param resources selected files / directories from the source panel
     * @param targetDir current directory of the target panel (dialog pre-fill)
     * @return the cancellation controller, or {@code null} if no copy was started
     */
    public CancellationController copy(
            Component parent,
            List<NuclrResourcePath> resources,
            Path targetDir) {

        List<Path> sources = toPaths(resources);
        if (sources.isEmpty()) {
            log.debug("Copy requested with no valid source paths — ignored");
            return null;
        }
        log.info("Starting copy of {} item(s) to {}", sources.size(), targetDir);

        // Per-run progress dialog — created before workflow so the listener can capture it.
        CopyProgressDialog progressDialog = new CopyProgressDialog(parent);

        CopyWorkflow workflow = new CopyWorkflow(
                engine,
                EXECUTOR,
                (srcs, suggested) -> showSetupDialog(parent, srcs, suggested),
                (mode, ctrl)      -> buildConflictHandler(parent, mode, ctrl),
                ()                -> confirmCancel(parent),
                buildProgressListener(parent, progressDialog));

        // Setup dialog is shown here (blocks EDT until user confirms or cancels).
        CancellationController ctrl = workflow.start(sources, targetDir);
        if (ctrl == null) {
            return null; // user cancelled at setup stage — no copy, no progress dialog
        }

        progressDialog.setCancelAction(() -> workflow.requestCancellation(ctrl));
        progressDialog.show();
        return ctrl;
    }

    // -------------------------------------------------------------------------
    // Per-run progress listener
    // -------------------------------------------------------------------------

    private static CopyProgressListener buildProgressListener(
            Component parent, CopyProgressDialog dialog) {
        return new CopyProgressListener() {

            @Override
            public void onProgress(CopyProgressSnapshot snap) {
                SwingUtilities.invokeLater(() -> dialog.update(snap));
            }

            @Override
            public void onComplete(dev.nuclr.plugin.core.panel.fs.copy.CopyOutcome outcome,
                    CopyProgressSnapshot finalSnap) {
                SwingUtilities.invokeLater(() -> {
                    dialog.close();
                    refreshPanel(parent);
                });
            }

            @Override
            public void onError(Path src, Path tgt, Exception ex) {
                log.warn("Copy error {} \u2192 {}: {}", src, tgt, ex.getMessage());
            }
        };
    }

    /**
     * Reload the directory currently shown in {@code parent}.
     * Works by finding the nearest {@link LocalFilePanel} ancestor.
     */
    private static void refreshPanel(Component parent) {
        Component cur = parent;
        while (cur != null) {
            if (cur instanceof LocalFilePanel lfp) {
                Path dir = lfp.getCurrentDirectory();
                if (dir != null) {
                    lfp.showDirectory(dir);
                }
                return;
            }
            cur = cur.getParent();
        }
    }

    // -------------------------------------------------------------------------
    // Dialog factory implementations (Swing)
    // -------------------------------------------------------------------------

    /**
     * Minimal copy setup dialog.
     *
     * <p>Replace with a full {@code LocalCopySetupDialog} that exposes all
     * {@link CopyOptions} fields (conflict mode, access rights, timestamps…)
     * as form controls.
     */
    private static CopyDialogResult showSetupDialog(
            Component parent, List<Path> sources, Path suggestedTarget) {

        String summary = sources.size() == 1
                ? "\"" + sources.get(0).getFileName() + "\""
                : sources.size() + " items";

        String targetInput = (String) JOptionPane.showInputDialog(
                parent,
                "Copy " + summary + " to:",
                "Copy",
                JOptionPane.PLAIN_MESSAGE,
                null, null,
                suggestedTarget != null ? suggestedTarget.toString() : "");

        if (targetInput == null || targetInput.isBlank()) {
            return CopyDialogResult.cancelled();
        }
        Path target = Path.of(targetInput.strip());
        return CopyDialogResult.confirmed(CopyRequest.of(sources, target, CopyOptions.defaults()));
    }

    /**
     * Build the per-run conflict handler.
     *
     * <p>For non-ASK modes, returns a fixed policy immediately.  For ASK, shows
     * a per-file modal dialog on the EDT (called from the copy background thread
     * via {@code invokeAndWait}).
     */
    private static ConflictHandler buildConflictHandler(
            Component parent, ConflictMode baseMode, CancellationController ctrl) {

        if (baseMode != ConflictMode.ASK) {
            return ConflictHandler.fixed(baseMode);
        }

        return (source, target) -> {
            ConflictMode[] result = {ConflictMode.SKIP};
            try {
                SwingUtilities.invokeAndWait(() -> {
                    Object[] options = {"Overwrite", "Skip", "Rename", "Cancel all"};
                    int choice = JOptionPane.showOptionDialog(
                            parent,
                            "\"" + target.getFileName() + "\" already exists.\nWhat should be done?",
                            "File Conflict",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                            null, options, options[1]);
                    result[0] = switch (choice) {
                        case 0  -> ConflictMode.OVERWRITE;
                        case 2  -> ConflictMode.RENAME;
                        case 3  -> ConflictMode.CANCEL;
                        default -> ConflictMode.SKIP;
                    };
                });
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                result[0] = ConflictMode.CANCEL;
            } catch (java.lang.reflect.InvocationTargetException ex) {
                log.warn("Conflict dialog failed", ex);
                result[0] = ConflictMode.SKIP;
            }
            return result[0];
        };
    }

    /**
     * Ask the user to confirm cancellation.
     * Called from the EDT (Cancel button → {@link CopyWorkflow#requestCancellation}).
     */
    private static boolean confirmCancel(Component parent) {
        int choice = JOptionPane.showConfirmDialog(
                parent,
                "Do you really want to cancel the copy operation?",
                "Cancel Copy",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    // -------------------------------------------------------------------------
    // NuclrResourcePath → Path conversion (single responsibility method)
    // -------------------------------------------------------------------------

    /**
     * Extract {@link java.nio.file.Path} from each resource.
     * Resources without a backing path (virtual/remote) are silently skipped.
     */
    static List<Path> toPaths(List<NuclrResourcePath> resources) {
        if (resources == null) return List.of();
        return resources.stream()
                .filter(r -> r != null && r.getPath() != null)
                .map(NuclrResourcePath::getPath)
                .collect(Collectors.toList());
    }
}
