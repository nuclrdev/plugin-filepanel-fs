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

import dev.nuclr.plugin.core.panel.fs.copy.ConflictMode;

/**
 * Immutable move-behaviour settings collected from the setup dialog.
 *
 * @param conflictMode default behaviour when a destination already exists;
 *                     {@link ConflictMode#ASK} causes a per-file dialog
 */
public record MoveOptions(ConflictMode conflictMode) {

    /** Sensible defaults: ask on conflict. */
    public static MoveOptions defaults() {
        return new MoveOptions(ConflictMode.ASK);
    }
}
