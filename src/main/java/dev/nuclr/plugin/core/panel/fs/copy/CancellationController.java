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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe lifecycle controller shared between the copy background thread
 * and the UI thread.
 *
 * <p>The copy engine calls {@link #isCancelled()} and {@link #checkPause()} at
 * designated checkpoints.  The UI layer calls the mutating methods in response
 * to user gestures.
 *
 * <p>Pause/resume design:
 * <ul>
 *   <li>The UI calls {@link #pause()} to freeze the copy at the next checkpoint.</li>
 *   <li>The UI then shows a confirmation dialog.</li>
 *   <li>If the user confirms cancel → {@link #cancel()} (which also unblocks the thread).</li>
 *   <li>If the user backs out → {@link #resume()} to continue.</li>
 * </ul>
 *
 * <p>This pattern means the UI never needs a one-shot boolean — it can let the
 * user reconsider multiple times.
 */
public final class CancellationController {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean paused = false;
    private final Object pauseLock = new Object();

    // -------------------------------------------------------------------------
    // UI-thread API
    // -------------------------------------------------------------------------

    /**
     * Request that the copy be permanently cancelled.
     * <p>Implicitly calls {@link #resume()} so that a paused copy thread
     * unblocks and can observe the cancellation flag.
     */
    public void cancel() {
        cancelled.set(true);
        resume();
    }

    /**
     * Temporarily freeze the copy at the next checkpoint without cancelling it.
     * <p>Safe to call from any thread.
     */
    public void pause() {
        paused = true;
    }

    /**
     * Unfreeze a paused copy.  No-op if not currently paused.
     * <p>Safe to call from any thread.
     */
    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    // -------------------------------------------------------------------------
    // Copy-thread API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if cancellation has been permanently requested.
     * <p>Cheap volatile read; safe to call in tight loops.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Blocks the calling (copy) thread while {@link #pause()} is active.
     * Returns immediately when the copy is not paused or when
     * {@link #cancel()} is called while blocked.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void checkPause() throws InterruptedException {
        if (!paused) return;
        synchronized (pauseLock) {
            while (paused && !cancelled.get()) {
                pauseLock.wait();
            }
        }
    }
}
