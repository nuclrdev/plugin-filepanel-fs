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
 * Immutable copy-behaviour settings collected from the setup dialog.
 *
 * @param accessRights              how to set permissions on copied files
 * @param conflictMode              default behaviour when a destination exists;
 *                                  {@link ConflictMode#ASK} causes a per-file dialog
 * @param preserveTimestamps        copy last-modified time from source to destination
 * @param copySymlinkContents       follow symlinks and copy their targets instead of
 *                                  the link itself; when {@code false} symlinks are skipped
 * @param processMultipleDestinations allow the target field to contain a
 *                                  semicolon-separated list of destination paths
 */
public record CopyOptions(
        AccessRightsMode accessRights,
        ConflictMode conflictMode,
        boolean preserveTimestamps,
        boolean copySymlinkContents,
        boolean processMultipleDestinations
) {

    /** Sensible defaults: ask on conflict, preserve timestamps, skip symlinks. */
    public static CopyOptions defaults() {
        return new CopyOptions(
                AccessRightsMode.DEFAULT,
                ConflictMode.ASK,
                true,
                false,
                false
        );
    }
}
