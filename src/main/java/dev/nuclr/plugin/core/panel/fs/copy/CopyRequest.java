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
import java.util.List;

/**
 * Fully confirmed, immutable description of a copy operation.
 *
 * <p>A {@code CopyRequest} is only constructed after the user has confirmed the
 * setup dialog — it is the boundary that separates the setup stage from the
 * execution stage.  No filesystem changes may occur before this object exists.
 *
 * <p>When {@link CopyOptions#processMultipleDestinations()} is {@code true} the
 * caller may split {@code targetDirectory} on {@code ';'} to obtain multiple
 * destinations and run one {@link CopyService} execution per destination.
 *
 * @param sources         one or more source files / directories to copy
 * @param targetDirectory the destination directory (must already exist or be
 *                        created by the copy engine)
 * @param options         behaviour flags collected at the setup stage
 */
public record CopyRequest(
        List<Path> sources,
        Path targetDirectory,
        CopyOptions options
) {

    /**
     * Convenience factory for the common single-target case.
     *
     * @param sources   source paths
     * @param target    destination directory
     * @param options   copy options
     */
    public static CopyRequest of(List<Path> sources, Path target, CopyOptions options) {
        return new CopyRequest(List.copyOf(sources), target, options);
    }
}
