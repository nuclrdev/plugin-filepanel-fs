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
package dev.nuclr.plugin.core.panel.fs.move;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import dev.nuclr.plugin.core.panel.fs.copy.CancellationController;
import dev.nuclr.plugin.core.panel.fs.copy.ConflictHandler;
import dev.nuclr.plugin.core.panel.fs.copy.ConflictMode;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the complete move workflow:
 * <ol>
 *   <li><b>Setup stage</b> (mandatory) — shows the move configuration dialog
 *       via {@link SetupDialogFactory}; aborts if the user cancels.</li>
 *   <li><b>Execution stage</b> — submits {@link MoveService#execute} to a
 *       background {@link Executor} only after dialog confirmation.</li>
 *   <li><b>Cancellation</b> — pauses the move, asks the user to confirm,
 *       then either aborts or resumes.</li>
 * </ol>
 *
 * <p>This class is entirely UI-agnostic: every user-interaction point is
 * fulfilled by a functional interface that callers implement.
 *
 * <h3>Wiring example</h3>
 * <pre>
 * var workflow = new MoveWorkflow(
 *     new MoveService(),
 *     Executors.newVirtualThreadPerTaskExecutor(),
 *     (sources, suggestedTarget) -&gt; LocalMoveSetupDialog.show(parent, sources, suggestedTarget),
 *     (baseMode, ctrl)          -&gt; LocalConflictDialog.handlerFor(parent, baseMode, ctrl),
 *     ()                        -&gt; LocalConfirmDialog.askCancelMove(parent),
 *     snapshot                  -&gt; SwingUtilities.invokeLater(() -&gt; progressPanel.update(snapshot))
 * );
 * CancellationController ctrl = workflow.start(selectedPaths, oppositePanelPath);
 * // ctrl is null if the user cancelled the setup dialog
 * </pre>
 */
@Slf4j
public class MoveWorkflow {

    private final MoveService            moveService;
    private final Executor               executor;
    private final SetupDialogFactory     setupDialogFactory;
    private final ConflictHandlerFactory conflictHandlerFactory;
    private final Supplier<Boolean>      cancelConfirmSupplier;
    private final MoveProgressListener   progressListener;

    public MoveWorkflow(
            MoveService moveService,
            Executor executor,
            SetupDialogFactory setupDialogFactory,
            ConflictHandlerFactory conflictHandlerFactory,
            Supplier<Boolean> cancelConfirmSupplier,
            MoveProgressListener progressListener) {
        this.moveService            = moveService;
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
     * Launch the move workflow.
     *
     * <p><b>Must be called from the UI thread.</b>  The setup dialog is shown
     * synchronously (blocks until the user confirms or cancels).  If confirmed,
     * the actual file move is submitted asynchronously to {@link #executor}
     * and this method returns immediately with the cancellation controller.
     *
     * @param sources         files / directories selected in the active panel
     * @param targetDirectory current directory of the opposite panel
     * @return a {@link CancellationController} the caller can use to request
     *         cancellation; {@code null} if the user cancelled the setup dialog
     */
    public CancellationController start(List<Path> sources, Path targetDirectory) {

        // ---- 1. Mandatory setup stage — no move starts before this ---------
        MoveDialogResult dialogResult = setupDialogFactory.show(sources, targetDirectory);

        if (!dialogResult.confirmed()) {
            log.debug("Move cancelled at setup stage");
            return null;
        }

        MoveRequest request = dialogResult.request();
        log.info("Move confirmed: {} source(s) → {}", sources.size(), request.targetDirectory());

        // ---- 2. Build per-run collaborators --------------------------------
        CancellationController ctrl     = new CancellationController();
        ConflictHandler conflictHandler = conflictHandlerFactory.create(
                request.options().conflictMode(), ctrl);

        // ---- 3. Execute on background thread — EDT stays free --------------
        executor.execute(() -> {
            log.debug("Move execution started");
            MoveOutcome outcome = moveService.execute(request, progressListener, ctrl, conflictHandler);
            log.info("Move execution finished: {}", outcome);
        });

        return ctrl;
    }

    /**
     * Request cancellation with user confirmation.
     *
     * <p>Call this when the user clicks "Cancel" in the progress dialog.
     * The method pauses the move, asks for confirmation, and either aborts
     * or resumes.  Safe to call from any thread.
     *
     * @param ctrl the controller returned by {@link #start}; no-op if {@code null}
     */
    public void requestCancellation(CancellationController ctrl) {
        if (ctrl == null || ctrl.isCancelled()) return;

        ctrl.pause();

        boolean confirmed = Boolean.TRUE.equals(cancelConfirmSupplier.get());

        if (confirmed) {
            ctrl.cancel();
        } else {
            ctrl.resume();
        }
    }

    // -----------------------------------------------------------------------
    // Extension-point interfaces
    // -----------------------------------------------------------------------

    /**
     * Factory for the move setup dialog.
     *
     * <p>Implementations must be modal: they block until the user confirms or
     * cancels, then return a {@link MoveDialogResult}.
     */
    @FunctionalInterface
    public interface SetupDialogFactory {

        /**
         * @param sources         source paths to display in the dialog
         * @param suggestedTarget pre-filled target path (opposite-panel directory)
         * @return the user's decision
         */
        MoveDialogResult show(List<Path> sources, Path suggestedTarget);
    }

    /**
     * Factory for a per-run {@link ConflictHandler}.
     *
     * <p>Receives the baseline {@link ConflictMode} and the shared
     * {@link CancellationController} (so a per-conflict dialog can trigger
     * cancellation if the user chooses "Abort all").
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
