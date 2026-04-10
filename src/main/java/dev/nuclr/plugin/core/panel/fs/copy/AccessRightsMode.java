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
 * Controls how file-system permissions are set on copied files.
 *
 * <p>Platforms that do not support POSIX permissions (e.g. Windows without
 * ACL tooling) will silently skip permission transfer for {@code COPY} mode.
 */
public enum AccessRightsMode {

    /**
     * Let the operating system apply its default umask / ACL inheritance —
     * no explicit permission transfer is performed.
     */
    DEFAULT,

    /**
     * Copy the source file's POSIX permissions (or equivalent) to the
     * destination exactly.
     */
    COPY,

    /**
     * Allow the destination directory's ACL inheritance rules to apply;
     * semantically equivalent to {@code DEFAULT} on most systems.
     */
    INHERIT
}
