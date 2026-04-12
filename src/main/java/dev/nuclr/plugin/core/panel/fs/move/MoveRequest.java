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
import java.util.List;

/**
 * Fully confirmed, immutable description of a move operation.
 *
 * <p>A {@code MoveRequest} is only constructed after the user has confirmed the
 * setup dialog — it is the boundary that separates the setup stage from the
 * execution stage.  No filesystem changes may occur before this object exists.
 *
 * @param sources         one or more source files / directories to move
 * @param targetDirectory the destination directory
 * @param options         behaviour flags collected at the setup stage
 */
public record MoveRequest(List<Path> sources, Path targetDirectory, MoveOptions options) {

    /** Convenience factory. */
    public static MoveRequest of(List<Path> sources, Path target, MoveOptions options) {
        return new MoveRequest(List.copyOf(sources), target, options);
    }
}
