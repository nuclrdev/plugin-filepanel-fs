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
 * The value returned by the move setup dialog.
 *
 * <p>When the user dismisses or cancels the dialog {@link #confirmed()} is
 * {@code false} and {@link #request()} is {@code null} — the move must NOT start.
 */
public record MoveDialogResult(boolean confirmed, MoveRequest request) {

    /** Factory for a user-cancelled result (no move should start). */
    public static MoveDialogResult cancelled() {
        return new MoveDialogResult(false, null);
    }

    /** Factory for a user-confirmed result. */
    public static MoveDialogResult confirmed(MoveRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null when confirmed");
        return new MoveDialogResult(true, request);
    }
}
