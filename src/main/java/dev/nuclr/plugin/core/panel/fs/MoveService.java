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
import dev.nuclr.plugin.core.panel.fs.move.MoveDialogResult;
import dev.nuclr.plugin.core.panel.fs.move.MoveOptions;
import dev.nuclr.plugin.core.panel.fs.move.MoveOutcome;
import dev.nuclr.plugin.core.panel.fs.move.MoveProgressListener;
import dev.nuclr.plugin.core.panel.fs.move.MoveProgressSnapshot;
import dev.nuclr.plugin.core.panel.fs.move.MoveRequest;
import dev.nuclr.plugin.core.panel.fs.move.MoveWorkflow;
import dev.nuclr.plugin.core.panel.fs.plugin.LocalFilePanel;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin-level entry point for the move feature.
 *
 * <p>Owns a single reusable move engine and a shared virtual-thread executor.
 * Each call to {@link #move} creates its own {@link MoveWorkflow},
 * {@link MoveProgressDialog}, and {@link CancellationController}
 * so concurrent moves are fully isolated.
 *
 * <p>Must be called from the Swing EDT.
 */
@Slf4j
public class MoveService {

    /** One virtual thread per move run — no pool exhaustion, no queuing. */
    private static final Executor EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** Stateless execution engine; shared across all runs. */
    private final dev.nuclr.plugin.core.panel.fs.move.MoveService engine =
            new dev.nuclr.plugin.core.panel.fs.move.MoveService();

    // -------------------------------------------------------------------------
    // Public entry point (called from the EDT)
    // -------------------------------------------------------------------------

    /**
     * Start the full move workflow for the given resources.
     *
     * <p>Shows the setup dialog synchronously.  If the user confirms, shows a
     * live progress dialog and starts moving in the background.  On completion,
     * refreshes the target panel and — if provided — the source panel.
     *
     * @param parent        Swing component used as the dialog owner and for the
     *                      post-move refresh of the target panel
     * @param resources     selected files / directories from the source panel
     * @param targetDir     current directory of the target panel (dialog pre-fill)
     * @param refreshSource optional callback invoked on the EDT when the move
     *                      finishes to refresh the source panel; may be {@code null}
     * @return the cancellation controller, or {@code null} if no move was started
     */
    public CancellationController move(
            Component parent,
            List<NuclrResourcePath> resources,
            Path targetDir,
            Runnable refreshSource) {

        List<Path> sources = toPaths(resources);
        if (sources.isEmpty()) {
            log.debug("Move requested with no valid source paths — ignored");
            return null;
        }
        log.info("Starting move of {} item(s) to {}", sources.size(), targetDir);

        MoveProgressDialog progressDialog = new MoveProgressDialog(parent);

        MoveWorkflow workflow = new MoveWorkflow(
                engine,
                EXECUTOR,
                (srcs, suggested) -> showSetupDialog(parent, srcs, suggested),
                (mode, ctrl)      -> buildConflictHandler(parent, mode, ctrl),
                ()                -> confirmCancel(parent),
                buildProgressListener(parent, progressDialog, refreshSource));

        CancellationController ctrl = workflow.start(sources, targetDir);
        if (ctrl == null) {
            return null; // user cancelled at setup stage
        }

        progressDialog.setCancelAction(() -> workflow.requestCancellation(ctrl));
        progressDialog.show();
        return ctrl;
    }

    // -------------------------------------------------------------------------
    // Per-run progress listener
    // -------------------------------------------------------------------------

    private static MoveProgressListener buildProgressListener(
            Component parent,
            MoveProgressDialog dialog,
            Runnable refreshSource) {
        return new MoveProgressListener() {

            @Override
            public void onProgress(MoveProgressSnapshot snap) {
                SwingUtilities.invokeLater(() -> dialog.update(snap));
            }

            @Override
            public void onComplete(MoveOutcome outcome, MoveProgressSnapshot finalSnap) {
                SwingUtilities.invokeLater(() -> {
                    dialog.close();
                    refreshPanel(parent);
                    if (refreshSource != null) {
                        refreshSource.run();
                    }
                });
            }

            @Override
            public void onError(Path src, Path tgt, Exception ex) {
                log.warn("Move error {} \u2192 {}: {}", src, tgt, ex.getMessage());
            }
        };
    }

    /**
     * Reload the directory currently shown in the nearest {@link LocalFilePanel}
     * ancestor of {@code parent}.
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
     * Minimal move setup dialog.
     *
     * <p>Replace with a full dialog that exposes {@link MoveOptions} fields
     * as form controls.
     */
    private static MoveDialogResult showSetupDialog(
            Component parent, List<Path> sources, Path suggestedTarget) {

        String summary = sources.size() == 1
                ? "\"" + sources.get(0).getFileName() + "\""
                : sources.size() + " items";

        String targetInput = (String) JOptionPane.showInputDialog(
                parent,
                "Move " + summary + " to:",
                "Move",
                JOptionPane.PLAIN_MESSAGE,
                null, null,
                suggestedTarget != null ? suggestedTarget.toString() : "");

        if (targetInput == null || targetInput.isBlank()) {
            return MoveDialogResult.cancelled();
        }
        Path target = Path.of(targetInput.strip());
        return MoveDialogResult.confirmed(MoveRequest.of(sources, target, MoveOptions.defaults()));
    }

    /**
     * Build the per-run conflict handler.
     *
     * <p>For non-ASK modes returns a fixed policy.  For ASK, shows a per-file
     * modal dialog on the EDT via {@code invokeAndWait}.
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
     * Called from the EDT (Cancel button → {@link MoveWorkflow#requestCancellation}).
     */
    private static boolean confirmCancel(Component parent) {
        int choice = JOptionPane.showConfirmDialog(
                parent,
                "Do you really want to cancel the move operation?",
                "Cancel Move",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    // -------------------------------------------------------------------------
    // NuclrResourcePath → Path conversion
    // -------------------------------------------------------------------------

    static List<Path> toPaths(List<NuclrResourcePath> resources) {
        if (resources == null) return List.of();
        return resources.stream()
                .filter(r -> r != null && r.getPath() != null)
                .map(NuclrResourcePath::getPath)
                .collect(Collectors.toList());
    }
}
