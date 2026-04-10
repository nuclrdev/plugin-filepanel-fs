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
 * Immutable point-in-time snapshot of copy progress.
 *
 * <p>Instances are created by {@link CopyService} and passed to
 * {@link CopyProgressListener#onProgress}.  All percentage values are clamped
 * to {@code [0, 100]}.
 *
 * @param phase                  current lifecycle phase
 * @param currentSource          source path being copied ({@code null} during scanning or after completion)
 * @param currentTarget          destination path being written ({@code null} during scanning or after completion)
 * @param currentFileBytesCopied bytes written for the current file so far
 * @param currentFileSize        total size of the current file in bytes
 * @param currentFilePercent     per-file completion percentage (0–100)
 * @param filesCopied            number of fully copied files so far
 * @param totalFiles             total number of regular files to copy (from pre-scan)
 * @param totalBytesCopied       cumulative bytes written across all files
 * @param totalBytes             total bytes to copy (from pre-scan)
 * @param totalPercent           overall completion percentage (0–100)
 */
public record CopyProgressSnapshot(
        CopyPhase phase,
        Path currentSource,
        Path currentTarget,
        long currentFileBytesCopied,
        long currentFileSize,
        int currentFilePercent,
        int filesCopied,
        int totalFiles,
        long totalBytesCopied,
        long totalBytes,
        int totalPercent
) {

    /**
     * Snapshot emitted once just after the pre-scan finishes.
     *
     * @param totalFiles pre-scanned file count
     * @param totalBytes pre-scanned byte total
     */
    public static CopyProgressSnapshot postScan(int totalFiles, long totalBytes) {
        return new CopyProgressSnapshot(
                CopyPhase.SCANNING, null, null,
                0, 0, 0,
                0, totalFiles,
                0, totalBytes, 0);
    }

    /**
     * Terminal snapshot used for COMPLETED, CANCELLED, or FAILED phases.
     *
     * @param phase          the terminal phase
     * @param filesCopied    files completed at the time of termination
     * @param totalFiles     pre-scanned total file count
     * @param bytesCopied    bytes written at the time of termination
     * @param totalBytes     pre-scanned total byte count
     */
    public static CopyProgressSnapshot terminal(
            CopyPhase phase,
            int filesCopied, int totalFiles,
            long bytesCopied, long totalBytes) {
        int pct = totalBytes > 0 ? (int) Math.min(100L, bytesCopied * 100L / totalBytes) : 100;
        return new CopyProgressSnapshot(
                phase, null, null,
                0, 0, 0,
                filesCopied, totalFiles,
                bytesCopied, totalBytes, pct);
    }
}
