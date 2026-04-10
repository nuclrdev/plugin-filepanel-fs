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
 * Strategy for resolving a file-exists conflict during copy.
 *
 * <p>Implementations may be:
 * <ul>
 *   <li><b>Policy-based</b> — always return the same {@link ConflictMode}
 *       (see {@link #fixed(ConflictMode)}).</li>
 *   <li><b>Dialog-based</b> — show a Swing/JavaFX dialog per conflict and
 *       return whatever the user chooses.  Wire this via
 *       {@link CopyWorkflow.ConflictHandlerFactory}.</li>
 * </ul>
 *
 * <p>Contract: implementations must NEVER return {@link ConflictMode#ASK}.
 * {@code ASK} is only valid as a default policy in {@link CopyOptions}; the
 * handler is responsible for resolving it to a concrete action before
 * returning.
 */
@FunctionalInterface
public interface ConflictHandler {

    /**
     * Called when {@code target} already exists.
     *
     * <p>This method is invoked from the copy background thread.  A
     * dialog-based implementation must block until the user responds —
     * use {@code CountDownLatch} or {@code SwingUtilities.invokeAndWait}
     * to synchronise.
     *
     * @param source the file being copied
     * @param target the conflicting destination path
     * @return the resolution to apply; must not be {@link ConflictMode#ASK}
     */
    ConflictMode resolve(Path source, Path target);

    /**
     * Returns a stateless handler that always applies the same mode.
     *
     * @param mode must not be {@link ConflictMode#ASK}
     */
    static ConflictHandler fixed(ConflictMode mode) {
        if (mode == ConflictMode.ASK) {
            throw new IllegalArgumentException("Fixed handler cannot use ASK — provide a concrete mode");
        }
        return (src, tgt) -> mode;
    }
}
