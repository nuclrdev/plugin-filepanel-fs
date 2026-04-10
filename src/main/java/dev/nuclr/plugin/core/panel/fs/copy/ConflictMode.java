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
 * Determines how a file conflict (destination already exists) is resolved.
 *
 * <p>{@code ASK} is only valid as a default policy stored in {@link CopyOptions};
 * a {@link ConflictHandler} must always resolve it to one of the concrete modes
 * before the copy engine acts.
 */
public enum ConflictMode {

    /** Show a per-conflict dialog and let the user decide (default policy). */
    ASK,

    /** Overwrite the existing destination file. */
    OVERWRITE,

    /** Leave the destination untouched and move on to the next file. */
    SKIP,

    /** Rename the incoming file (e.g. {@code report_1.pdf}) to avoid collision. */
    RENAME,

    /** Append source bytes to the end of the existing destination file. */
    APPEND,

    /** Abort the entire copy operation. */
    CANCEL
}
