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

import java.util.Optional;

/**
 * The value returned by the setup dialog.
 *
 * <p>When the user dismisses or cancels the dialog {@link #confirmed()} is
 * {@code false} and {@link #request()} is empty — the copy must NOT start.
 * Only when {@link #confirmed()} is {@code true} may {@link CopyWorkflow} hand
 * the enclosed {@link CopyRequest} to {@link CopyService}.
 */
public record CopyDialogResult(boolean confirmed, CopyRequest request) {

    /** Factory for a user-cancelled result (no copy should start). */
    public static CopyDialogResult cancelled() {
        return new CopyDialogResult(false, null);
    }

    /** Factory for a user-confirmed result. */
    public static CopyDialogResult confirmed(CopyRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null when confirmed");
        return new CopyDialogResult(true, request);
    }

    /**
     * Returns the confirmed request, or empty if the dialog was cancelled.
     * Prefer this over {@link #request()} to avoid NPE on cancelled results.
     */
    public Optional<CopyRequest> requestOpt() {
        return Optional.ofNullable(request);
    }
}
