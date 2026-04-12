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

import java.nio.file.Path;

/**
 * Immutable point-in-time snapshot of move progress.
 *
 * <p>For same-filesystem moves (atomic rename), byte fields are zero and
 * {@code currentFilePercent} is always 100 — the total progress bar advances
 * by counting moved file sizes.  For cross-filesystem moves, byte fields carry
 * live data from the buffered copy phase.
 *
 * @param phase                      current lifecycle phase
 * @param currentSource              source path being moved ({@code null} during scanning / after completion)
 * @param currentTarget              destination path ({@code null} during scanning / after completion)
 * @param currentFileBytesTransferred bytes transferred for the current file (cross-fs only)
 * @param currentFileSize             total size of the current file in bytes
 * @param currentFilePercent          per-file completion percentage (0–100)
 * @param filesMoved                  number of fully moved files so far
 * @param totalFiles                  total number of regular files to move (from pre-scan)
 * @param totalBytesTransferred       cumulative bytes transferred across all files
 * @param totalBytes                  total bytes to move (from pre-scan)
 * @param totalPercent                overall completion percentage (0–100)
 */
public record MoveProgressSnapshot(
        MovePhase phase,
        Path currentSource,
        Path currentTarget,
        long currentFileBytesTransferred,
        long currentFileSize,
        int currentFilePercent,
        int filesMoved,
        int totalFiles,
        long totalBytesTransferred,
        long totalBytes,
        int totalPercent
) {

    /** Snapshot emitted once just after the pre-scan finishes. */
    public static MoveProgressSnapshot postScan(int totalFiles, long totalBytes) {
        return new MoveProgressSnapshot(
                MovePhase.SCANNING, null, null,
                0, 0, 0,
                0, totalFiles,
                0, totalBytes, 0);
    }

    /** Terminal snapshot used for COMPLETED, CANCELLED, or FAILED phases. */
    public static MoveProgressSnapshot terminal(
            MovePhase phase,
            int filesMoved, int totalFiles,
            long bytesMoved, long totalBytes) {
        int pct = totalBytes > 0 ? (int) Math.min(100L, bytesMoved * 100L / totalBytes) : 100;
        return new MoveProgressSnapshot(
                phase, null, null,
                0, 0, 0,
                filesMoved, totalFiles,
                bytesMoved, totalBytes, pct);
    }
}
