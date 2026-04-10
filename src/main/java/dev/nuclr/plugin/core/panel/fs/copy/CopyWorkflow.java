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
package dev.nuclr.plugin.core.panel.fs.copy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the complete copy workflow:
 * <ol>
 *   <li><b>Setup stage</b> (mandatory) — shows the copy configuration dialog
 *       via {@link SetupDialogFactory}; aborts if the user cancels.</li>
 *   <li><b>Execution stage</b> — submits {@link CopyService#execute} to a
 *       background {@link Executor} only after dialog confirmation.</li>
 *   <li><b>Cancellation</b> — pauses the copy, asks the user to confirm,
 *       then either aborts or resumes.</li>
 * </ol>
 *
 * <p>This class is entirely UI-agnostic: every user-interaction point is
 * fulfilled by a functional interface that callers implement (Swing dialogs,
 * JavaFX, headless tests, etc.).
 *
 * <h3>Wiring example</h3>
 * <pre>
 * var workflow = new CopyWorkflow(
 *     new CopyService(),
 *     Executors.newVirtualThreadPerTaskExecutor(),
 *     (sources, suggestedTarget) -&gt; LocalCopySetupDialog.show(parentComponent, sources, suggestedTarget),
 *     (baseMode, ctrl)          -&gt; LocalConflictDialog.handlerFor(parentComponent, baseMode, ctrl),
 *     ()                        -&gt; LocalConfirmDialog.askCancelCopy(parentComponent),
 *     snapshot                  -&gt; SwingUtilities.invokeLater(() -&gt; progressPanel.update(snapshot))
 * );
 * CancellationController ctrl = workflow.start(selectedPaths, oppositePanelPath);
 * // ctrl is null if the user cancelled the setup dialog
 * </pre>
 */
@Slf4j
public class CopyWorkflow {

    private final CopyService copyService;
    private final Executor executor;
    private final SetupDialogFactory setupDialogFactory;
    private final ConflictHandlerFactory conflictHandlerFactory;
    private final Supplier<Boolean> cancelConfirmSupplier;
    private final CopyProgressListener progressListener;

    /**
     * @param copyService            execution engine (shared, stateless)
     * @param executor               background thread pool or virtual-thread executor
     * @param setupDialogFactory     opens the copy configuration dialog
     * @param conflictHandlerFactory builds a per-run conflict strategy
     * @param cancelConfirmSupplier  asks "really cancel?" → {@code true} means yes
     * @param progressListener       receives live progress snapshots from the copy thread
     */
    public CopyWorkflow(
            CopyService copyService,
            Executor executor,
            SetupDialogFactory setupDialogFactory,
            ConflictHandlerFactory conflictHandlerFactory,
            Supplier<Boolean> cancelConfirmSupplier,
            CopyProgressListener progressListener) {
        this.copyService            = copyService;
        this.executor               = executor;
        this.setupDialogFactory     = setupDialogFactory;
        this.conflictHandlerFactory = conflictHandlerFactory;
        this.cancelConfirmSupplier  = cancelConfirmSupplier;
        this.progressListener       = progressListener;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Launch the copy workflow.
     *
     * <p><b>Must be called from the UI thread.</b>  The setup dialog is shown
     * synchronously (blocks until the user confirms or cancels).  If confirmed,
     * the actual file copy is submitted asynchronously to {@link #executor}
     * and this method returns immediately with the cancellation controller.
     *
     * @param sources         files / directories selected in the active panel
     * @param targetDirectory current directory of the opposite panel
     *                        (pre-filled target suggestion for the dialog)
     * @return a {@link CancellationController} the caller can use to request
     *         cancellation; {@code null} if the user cancelled the setup dialog
     *         (no copy was started)
     */
    public CancellationController start(List<Path> sources, Path targetDirectory) {

        // ---- 1. Mandatory setup stage — no copy starts before this ---------
        CopyDialogResult dialogResult = setupDialogFactory.show(sources, targetDirectory);

        if (!dialogResult.confirmed()) {
            log.debug("Copy cancelled at setup stage");
            return null;
        }

        CopyRequest request = dialogResult.request();
        log.info("Copy confirmed: {} source(s) → {}", sources.size(), request.targetDirectory());

        // ---- 2. Build per-run collaborators --------------------------------
        CancellationController ctrl     = new CancellationController();
        ConflictHandler conflictHandler = conflictHandlerFactory.create(
                request.options().conflictMode(), ctrl);

        // ---- 3. Execute on background thread — EDT stays free --------------
        executor.execute(() -> {
            log.debug("Copy execution started");
            CopyOutcome outcome = copyService.execute(request, progressListener, ctrl, conflictHandler);
            log.info("Copy execution finished: {}", outcome);
        });

        return ctrl;
    }

    /**
     * Request cancellation with user confirmation.
     *
     * <p>Call this when the user clicks "Cancel" in the progress dialog.
     * The method pauses the copy, asks for confirmation, and either aborts
     * or resumes.  It returns immediately if the copy is already cancelled.
     *
     * <p>Safe to call from any thread.
     *
     * @param ctrl the controller returned by {@link #start}; no-op if {@code null}
     */
    public void requestCancellation(CancellationController ctrl) {
        if (ctrl == null || ctrl.isCancelled()) return;

        ctrl.pause();                                    // freeze copy at next checkpoint

        boolean confirmed = Boolean.TRUE.equals(cancelConfirmSupplier.get());

        if (confirmed) {
            ctrl.cancel();                               // permanently cancel
        } else {
            ctrl.resume();                               // user changed mind — continue
        }
    }

    // -----------------------------------------------------------------------
    // Extension-point interfaces
    // -----------------------------------------------------------------------

    /**
     * Factory for the copy setup dialog.
     *
     * <p>Implementations must be modal: they block until the user confirms or
     * cancels, then return a {@link CopyDialogResult}.
     *
     * <p>The dialog is responsible for collecting all fields of {@link CopyOptions}
     * and the final target path, then returning either
     * {@link CopyDialogResult#confirmed(CopyRequest)} or
     * {@link CopyDialogResult#cancelled()}.
     */
    @FunctionalInterface
    public interface SetupDialogFactory {

        /**
         * @param sources         source paths to display in the dialog
         * @param suggestedTarget pre-filled target path (opposite-panel directory)
         * @return the user's decision
         */
        CopyDialogResult show(List<Path> sources, Path suggestedTarget);
    }

    /**
     * Factory for a per-run {@link ConflictHandler}.
     *
     * <p>The factory receives the baseline {@link ConflictMode} chosen in the
     * setup dialog and the shared {@link CancellationController} (so a
     * per-conflict dialog can trigger cancellation if the user chooses
     * "Abort all").
     *
     * <p>Typical implementation:
     * <pre>
     * (baseMode, ctrl) -&gt; {
     *     if (baseMode != ConflictMode.ASK) return ConflictHandler.fixed(baseMode);
     *     return (source, target) -&gt; LocalConflictDialog.ask(parent, source, target, ctrl);
     * }
     * </pre>
     */
    @FunctionalInterface
    public interface ConflictHandlerFactory {

        /**
         * @param baseMode the default policy from the setup dialog
         * @param ctrl     the cancellation controller for this run
         * @return a conflict handler; must never return {@code null}
         */
        ConflictHandler create(ConflictMode baseMode, CancellationController ctrl);
    }
}
