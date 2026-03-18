package dev.nuclr.plugin.core.panel.fs;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.ApplicationPluginContext;
import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.PanelProviderPlugin;
import dev.nuclr.plugin.PluginManifest;
import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.event.PluginEvent;
import dev.nuclr.plugin.event.PluginThemeUpdatedEvent;
import dev.nuclr.plugin.event.bus.PluginEventListener;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link PanelProviderPlugin} for the local (default) filesystem.
 *
 * <p>
 * Provides one {@link LocalPathResource} per root directory reported by
 * {@link FileSystems#getDefault()} — e.g. {@code C:\}, {@code D:\} on Windows
 * or {@code /} on Linux/macOS.
 */
@Slf4j
public class LocalFilePanelProvider implements PanelProviderPlugin, PluginEventListener {

	private ApplicationPluginContext context;
	private LocalFilePanel panel;
	private boolean focused;

	// -------------------------------------------------------------------------
	// BasePlugin
	// -------------------------------------------------------------------------

	public PluginManifest getPluginInfo() {
		ObjectMapper objectMapper = context != null ? context.getObjectMapper() : new ObjectMapper();
		try (var is = getClass().getResourceAsStream("/plugin.json")) {
			if (is != null) {
				return objectMapper.readValue(is, PluginManifest.class);
			}
		} catch (Exception e) {
			log.error("Error reading /plugin.json for LocalFilePanelProvider", e);
		}
		return null;
	}

	@Override
	public JComponent getPanel() {
		if (panel == null) {
			panel = new LocalFilePanel(this::openDocumentation);
		}
		return panel;
	}

	@Override
	public List<MenuResource> getMenuItems(PluginPathResource source) {
		List<MenuResource> items = new ArrayList<>();
		boolean isDirectory = source != null && source.getPath() != null && Files.isDirectory(source.getPath());

		addDefaultMenuItems(items, source, isDirectory);
		addAltMenuItems(items, source);
		addCtrlMenuItems(items, source);
		addShiftMenuItems(items, source, isDirectory);
		return items;
	}

	@Override
	public void load(ApplicationPluginContext context) {
		this.context = context;
		context.getEventBus().subscribe(this);
		log.info("Local filesystem panel plugin loaded");
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		log.info("Local filesystem panel plugin unloaded");
	}

	// -------------------------------------------------------------------------
	// PanelProviderPlugin
	// -------------------------------------------------------------------------

	@Override
	public List<PluginPathResource> getChangeDriveResources() {
		var resources = new ArrayList<PluginPathResource>();
		FileSystems.getDefault().getRootDirectories().forEach(p -> {
			var res = new PluginPathResource();
			res.setPath(p);
			res.setName(p.toString());
			resources.add(res);
		});
		return resources;
	}

	@Override
	public boolean openItem(PluginPathResource resource, AtomicBoolean cancelled) {
		if (cancelled != null && cancelled.get()) {
			return false;
		}
		if (resource == null) {
			return false;
		}
		LocalFilePanel view = (LocalFilePanel) getPanel();
		Path path = resource.getPath();
		if (path != null && Files.isDirectory(path)) {
			view.showDirectory(path);
			return true;
		}
		return false;
	}

	@Override
	public boolean isMessageSupported(PluginEvent msg) {
		return msg instanceof PluginThemeUpdatedEvent || msg instanceof LocalMenuActionEvent;
	}

	@Override
	public void handleMessage(PluginEvent e) {
		if (e instanceof PluginThemeUpdatedEvent && panel != null) {
			panel.repaint();
			return;
		}
		if (focused && e instanceof LocalMenuActionEvent actionEvent && "makeFolder".equals(actionEvent.getActionId())) {
			Path sourcePath = actionEvent.getSource() != null ? actionEvent.getSource().getPath() : null;
			((LocalFilePanel) getPanel()).createNewFolder(sourcePath);
			return;
		}
		if (focused && e instanceof LocalMenuActionEvent actionEvent && "help".equals(actionEvent.getActionId())) {
			openDocumentation();
		}
	}

	private static MenuResource menu(String name, String keyStroke, String actionId, PluginPathResource source) {
		return new LocalMenuResource(name, keyStroke, new LocalMenuActionEvent(actionId, source));
	}

	private void openDocumentation() {
		String docUrl = getPluginInfo().getDocUrl();
		if (docUrl == null || docUrl.isBlank()) {
			log.warn("No documentation URL configured for {}", getPluginInfo().getId());
			return;
		}
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			log.warn("Desktop browse is not supported, cannot open {}", docUrl);
			return;
		}
		try {
			Desktop.getDesktop().browse(URI.create(docUrl));
		} catch (Exception ex) {
			log.warn("Cannot open documentation URL {}: {}", docUrl, ex.getMessage());
		}
	}

	private static void addDefaultMenuItems(List<MenuResource> items, PluginPathResource source, boolean isDirectory) {
		items.add(menu("Help", "F1", "help", source));
		items.add(menu("User Menu", "F2", "userMenu", source));
		items.add(menu("View", "F3", "view", source));
		items.add(menu("Edit", "F4", "edit", source));
		items.add(menu("Copy", "F5", "copy", source));
		items.add(menu(isDirectory ? "Move" : "Rename/Move", "F6", "move", source));
		items.add(menu("Make Folder", "F7", "makeFolder", source));
		items.add(menu("Delete", "F8", "delete", source));
		items.add(menu("Quit", "F10", "quit", source));
		items.add(menu("Plugins", "F11", "plugins", source));
		items.add(menu("Screen", "F12", "screen", source));
	}

	private static void addAltMenuItems(List<MenuResource> items, PluginPathResource source) {
		items.add(menu("Left", "Alt+F1", "left", source));
		items.add(menu("Right", "Alt+F2", "right", source));
		items.add(menu("Find", "Alt+F7", "find", source));
		items.add(menu("History", "Alt+F8", "history", source));
		items.add(menu("Fullscreen", "Alt+F9", "fullscreen", source));
		items.add(menu("Tree", "Alt+F10", "tree", source));
		items.add(menu("View History", "Alt+F11", "viewHistory", source));
		items.add(menu("Folder History", "Alt+F12", "folderHistory", source));
	}

	private static void addCtrlMenuItems(List<MenuResource> items, PluginPathResource source) {
		items.add(menu("Hide Left", "Ctrl+F1", "hideLeft", source));
		items.add(menu("Hide Right", "Ctrl+F2", "hideRight", source));
		items.add(menu("Sort by name", "Ctrl+F3", "sortByName", source));
		items.add(menu("Sort by extension", "Ctrl+F4", "sortByExtension", source));
		items.add(menu("Sort by modified", "Ctrl+F5", "sortByModifiedDate", source));
		items.add(menu("Sort by size", "Ctrl+F6", "sortBySize", source));
		items.add(menu("Unsort", "Ctrl+F7", "unsort", source));
		items.add(menu("Sort by create", "Ctrl+F8", "sortByCreateDate", source));
		items.add(menu("Sort by access", "Ctrl+F9", "sortByAccessTime", source));
		items.add(menu("Sort menu", "Ctrl+F12", "sortByAccessTime", source));
	}

	private static void addShiftMenuItems(List<MenuResource> items, PluginPathResource source, boolean isDirectory) {
		items.add(menu("Create archive", "Shift+F1", "createArchive", source));
		items.add(menu("Extract archive", "Shift+F2", "extractArchive", source));
		items.add(menu("Create file", "Shift+F4", "createFile", source));
		items.add(menu("Selection up", "Shift+F12", "selectionUp", source));
	}

	@Override
	public void onFocusGained() {
		focused = true;
		((LocalFilePanel) getPanel()).setPluginFocused(true);
	}

	@Override
	public void onFocusLost() {
		focused = false;
		if (panel != null) {
			panel.setPluginFocused(false);
		}
	}
}
