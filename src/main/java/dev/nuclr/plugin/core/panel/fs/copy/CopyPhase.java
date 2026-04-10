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

/**
 * Lifecycle phase reported inside a {@link CopyProgressSnapshot}.
 *
 * <p>Typical state machine:
 * <pre>
 *   SCANNING → COPYING → COMPLETED
 *                     ↘ CANCELLED
 *                     ↘ FAILED
 * </pre>
 */
public enum CopyPhase {

    /** Pre-scan in progress: counting files and computing total size. */
    SCANNING,

    /** Actively copying files. */
    COPYING,

    /** All files copied successfully. */
    COMPLETED,

    /** Copy was interrupted by the user. */
    CANCELLED,

    /** Copy aborted due to an unrecoverable error. */
    FAILED
}
