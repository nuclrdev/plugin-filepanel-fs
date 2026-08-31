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
package dev.nuclr.plugin.core.panel.fs.gotofolder;

import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.Path;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;

/** Plugin-level entry point for the Shift+Alt+G "Go to folder" prompt. */
public final class GoToFolderService {

	private final NuclrPluginContext context;
	private final Consumer<Path> navigate;

	/**
	 * @param context  the plugin context, supplying sounds; may be {@code null}
	 * @param navigate sends the panel to the chosen path; called after the dialog has closed
	 */
	public GoToFolderService(NuclrPluginContext context, Consumer<Path> navigate) {
		this.context = context;
		this.navigate = navigate;
	}

	/**
	 * Open the modal prompt on the EDT; all path lookups it makes stay off it.
	 *
	 * @param currentFolder the folder the panel is showing, for relative paths; may be {@code null}
	 */
	public void open(Path currentFolder) {
		Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		SoundEvents.popup(context);
		SwingUtilities.invokeLater(() -> new GoToFolderDialog(owner, currentFolder, this::go).setVisible(true));
	}

	private void go(Path path) {
		if (path == null || navigate == null) {
			return;
		}
		SoundEvents.confirmation(context);
		navigate.accept(path);
	}
}
