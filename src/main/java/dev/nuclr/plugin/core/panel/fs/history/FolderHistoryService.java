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
package dev.nuclr.plugin.core.panel.fs.history;

import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.Path;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin-level entry point for the Alt+F12 Folders history: the one class the panel plugin
 * needs to know about.
 *
 * <p>It wires the three pieces that have no reason to know about each other — the shared
 * {@link FolderHistory}, the {@link FolderHistoryDialog} the user sees, and the panel's own
 * navigation, handed in as a callback so the history package stays free of the platform's
 * resource and event types.
 */
@Slf4j
public class FolderHistoryService {

	private final NuclrPluginContext context;
	private final FolderHistory history;

	/** Sends the panel to a folder the user picked. */
	private final Consumer<String> navigate;

	/**
	 * @param context  the plugin context, supplying settings and sounds; may be {@code null}
	 * @param navigate opens a folder in the panel; called after the list has closed
	 */
	public FolderHistoryService(NuclrPluginContext context, Consumer<String> navigate) {
		this(context, navigate, FolderHistory.shared(context == null ? null : context.getSettings()));
	}

	/**
	 * @param context  the plugin context, supplying sounds; may be {@code null}
	 * @param navigate opens a folder in the panel; called after the list has closed
	 * @param history  the history to record into and list
	 */
	public FolderHistoryService(NuclrPluginContext context, Consumer<String> navigate, FolderHistory history) {
		this.context = context;
		this.navigate = navigate;
		this.history = history;
	}

	/**
	 * Remember that the panel opened a folder. Cheap and non-blocking — it is called for every
	 * directory the panel lists, including each step of a walk through a tree.
	 *
	 * @param folder the folder that was opened; {@code null} is ignored
	 */
	public void record(Path folder) {
		if (folder == null) {
			return;
		}
		try {
			history.record(folder.toAbsolutePath().toString());
		} catch (RuntimeException e) {
			// Recording where the user has been must never break going there.
			log.debug("Not recording {} in the folder history: {}", folder, e.getMessage());
		}
	}

	/**
	 * Open the Folders history list. Returns immediately; the dialog itself is modal and is
	 * shown on the EDT, anchored to the active commander window.
	 */
	public void open() {

		Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();

		SoundEvents.popup(context);
		SwingUtilities.invokeLater(() -> new FolderHistoryDialog(owner, history, this::go, context).setVisible(true));
	}

	/** Write anything still pending. Called when the plugin is unloaded. */
	public void flush() {
		history.flush();
	}

	private void go(String folder) {
		if (folder == null || folder.isBlank() || navigate == null) {
			return;
		}
		SoundEvents.confirmation(context);
		navigate.accept(folder);
	}
}
