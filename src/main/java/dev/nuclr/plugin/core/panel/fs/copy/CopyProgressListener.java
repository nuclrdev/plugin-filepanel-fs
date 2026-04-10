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

/**
 * Receives live updates from a running {@link CopyService} execution.
 *
 * <p>All methods are called from the copy background thread.  Swing
 * implementations must marshal calls to the EDT via
 * {@code SwingUtilities.invokeLater}.
 *
 * <p>Extension point: implement this interface to wire any progress UI —
 * a dialog, a status-bar label, or a headless test recorder.
 */
public interface CopyProgressListener {

    /**
     * Called after each buffer chunk and after each file is started.
     * Fired frequently — implementations should be lightweight.
     *
     * @param snapshot current progress state
     */
    void onProgress(CopyProgressSnapshot snapshot);

    /**
     * Called exactly once when the copy run finishes (for any reason).
     *
     * @param outcome       how the run ended
     * @param finalSnapshot last known progress state
     */
    default void onComplete(CopyOutcome outcome, CopyProgressSnapshot finalSnapshot) {}

    /**
     * Called for each file that could not be copied.  The copy engine
     * continues with the remaining files after invoking this callback.
     *
     * @param source the source path that failed ({@code null} for pre-scan errors)
     * @param target the destination path that failed ({@code null} for pre-scan errors)
     * @param error  the exception that caused the failure
     */
    default void onError(Path source, Path target, Exception error) {}
}
