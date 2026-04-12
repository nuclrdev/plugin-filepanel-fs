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

/**
 * Final result of a move run returned by {@link MoveService#execute}.
 *
 * <p>Individual per-file errors during an otherwise-successful run are reported
 * via {@link MoveProgressListener#onError} and do not change the outcome to
 * {@code FAILED} — the engine continues with the remaining files.
 * {@code FAILED} is reserved for catastrophic, run-aborting errors such as a
 * pre-scan {@link java.io.IOException}.
 */
public enum MoveOutcome {

    /** All files were processed (some may have been skipped on conflict). */
    SUCCESS,

    /** The user interrupted the move before it finished. */
    CANCELLED,

    /** An unrecoverable error prevented the move from running at all. */
    FAILED
}
