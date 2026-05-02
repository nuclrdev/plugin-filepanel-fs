package dev.nuclr.plugin.core.panel.fs;

import java.awt.Component;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import dev.nuclr.plugin.core.panel.fs.newFolder.CreateNewFolderService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalFileSystemPlugin implements FilePanelNuclrPlugin, NuclrEventListener {

	private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

	private static final String GO_TO_PATH_SHORTCUT = IS_MAC ? "Shift+Cmd+G" : "Ctrl+Shift+G";

	private String uuid = java.util.UUID.randomUUID().toString();

	private static final String MENU_ACTION_EVENT_TYPE = "dev.nuclr.plugin.core.panel.fs.menuAction";

	private static final String EventMakeFolder = "dev.nuclr.plugin.core.panel.fs.makeFolder";
	private static final String EventDelete = "dev.nuclr.plugin.core.panel.fs.delete";
	private static final String EventDeletePermanently = "dev.nuclr.plugin.core.panel.fs.deletePermanently";

	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.panel.fs";
	private static final String PLUGIN_NAME = "Local Filesystem Panel";
	private static final String PLUGIN_VERSION = "1.0.0";
	private static final String PLUGIN_DESCRIPTION = "Provides local filesystem roots (drives/mount points) to the file panel.";
	private static final String PLUGIN_AUTHOR = "Nuclr Development Team";
	private static final String PLUGIN_LICENSE = "Apache-2.0";
	private static final String PLUGIN_WEBSITE = "https://nuclr.dev";
	private static final String PLUGIN_PAGE_URL = "https://nuclr.dev/plugins/core/filepanel-fs.html";
	private static final String PLUGIN_DOC_URL = PLUGIN_PAGE_URL;

	private NuclrPluginContext context;

	private NuclrResourcePath currentFolder;

	private final CopyService copyService = new CopyService();
	private final MoveService moveService = new MoveService();
	
	private boolean focused = false;

	@Override
	public String id() {
		return PLUGIN_ID;
	}

	@Override
	public String name() {
		return PLUGIN_NAME;
	}

	@Override
	public String version() {
		return PLUGIN_VERSION;
	}

	@Override
	public String description() {
		return PLUGIN_DESCRIPTION;
	}

	@Override
	public String author() {
		return PLUGIN_AUTHOR;
	}

	@Override
	public String license() {
		return PLUGIN_LICENSE;
	}

	@Override
	public String website() {
		return PLUGIN_WEBSITE;
	}

	@Override
	public String pageUrl() {
		return PLUGIN_PAGE_URL;
	}

	@Override
	public String docUrl() {
		return PLUGIN_DOC_URL;
	}

	@Override
	public Developer type() {
		return Developer.Official;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResourcePath source) {
		List<NuclrMenuResource> items = new ArrayList<>();
		boolean isDirectory = source != null && source.getPath() != null && Files.isDirectory(source.getPath());
		addDefaultMenuItems(items, isDirectory);
		addAltMenuItems(items);
		addCtrlMenuItems(items);
		addShiftMenuItems(items, isDirectory);
		return items;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		log.info("Local filesystem panel plugin loaded");
	}

	@Override
	public void init() {
		context.getEventBus().subscribe(this);
		log.info("Local filesystem panel plugin inited");
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		log.info("Local filesystem panel plugin unloaded");
	}

	@Override
	public List<PluginRoot> getPluginRoots() {
		var resources = new ArrayList<PluginRoot>();
		FileSystems.getDefault().getRootDirectories().forEach(p -> {
			var res = new PluginRoot();
			res.setObject(p);
			res.setText(p.toString());
			resources.add(res);
		});
		return resources;
	}

	@Override
	public boolean openResource(NuclrResourcePath resource, AtomicBoolean cancelled) {

		if (cancelled != null && cancelled.get()) {
			return false;
		}

		if (resource == null) {
			return false;
		}

		this.currentFolder = resource;

		return true;

	}

	@Override
	public boolean isMessageSupported(String type) {
		return true;
	}

	private static void markAccepted(Map<String, Object> event) {
		if (event != null && event.get("accepted") instanceof AtomicBoolean accepted) {
			accepted.set(true);
		}
	}

	public void copyIntoCurrentPanel(List<NuclrResourcePath> paths, Component component, Path targetDir) {
		copyService.copy(component, paths, targetDir);
	}

	public void moveIntoCurrentPanel(List<NuclrResourcePath> paths, Component component, Path targetDir) {
		moveService.move(component, paths, targetDir);
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> event) {

		// Ignore its own events
		if (source == this) {
			return;
		}

		log.info("Received message - Source: {}, Type: {}, Event: {}", type, event);

		if ("fs.copy".equals(type)) {
			@SuppressWarnings("unchecked")
			List<NuclrResourcePath> paths = (List<NuclrResourcePath>) event.get("paths");
			var component = (Component) event.get("component");
			var targetDir = (Path) event.get("targetDir");
			markAccepted(event);
			copyIntoCurrentPanel(paths, component, targetDir);
			return;
		}

		if ("fs.move".equals(type)) {
			@SuppressWarnings("unchecked")
			List<NuclrResourcePath> paths = (List<NuclrResourcePath>) event.get("paths");
			markAccepted(event);
			var component = (Component) event.get("component");
			var targetDir = (Path) event.get("targetDir");
			moveService.move(component, paths, targetDir);
			return;
		}

		if (!MENU_ACTION_EVENT_TYPE.equals(type) || !isFocused()) {
			return;
		}

		if (EventMakeFolder.equals(type)) {
			var component = (Component) event.get("component");
			new CreateNewFolderService().createNewFolder(component, this.currentFolder);
			return;
		}

		if (EventDelete.equals(type)) {
			var selection = (List<NuclrResourcePath>) event.get("selection");
			deleteSelection(false, selection);
			return;
		}

		if (EventDeletePermanently.equals(type)) {
			var selection = (List<NuclrResourcePath>) event.get("selection");
			deleteSelection(true, selection);
			return;
		}
		if ("goToPath".equals(type)) {
			showGoToPathDialog();
			return;
		}
	}

	private void deleteSelection(boolean b, List<NuclrResourcePath> selection) {
		// TODO Auto-generated method stub

	}

	private void showGoToPathDialog() {
		// TODO Auto-generated method stub

	}

	private static void addDefaultMenuItems(List<NuclrMenuResource> items, boolean isDirectory) {
		items.add(menu("View", "F3", "view"));
		items.add(menu("Edit", "F4", "edit"));
		items.add(menu("Copy", "F5", "copy"));
		items.add(menu(isDirectory ? "Move" : "Rename/Move", "F6", "move"));
		items.add(menu("Make Folder", "F7", "makeFolder"));
		items.add(menu("Delete", "F8", "delete"));
		items.add(menu("Quit", "F10", "quit"));
		items.add(menu("Plugins", "F11", "plugins"));
		items.add(menu("Screen", "F12", "screen"));
	}

	private static void addAltMenuItems(List<NuclrMenuResource> items) {
		items.add(menu("Left Panel", "Alt+F1", "left"));
		items.add(menu("Right Panel", "Alt+F2", "right"));
		items.add(menu("Find", "Alt+F7", "find"));
		items.add(menu("History", "Alt+F8", "history"));
		items.add(menu("Fullscreen", "Alt+F9", "fullscreen"));
		items.add(menu("Tree", "Alt+F10", "tree"));
		items.add(menu("View History", "Alt+F11", "viewHistory"));
		items.add(menu("Folder History", "Alt+F12", "folderHistory"));
	}

	private static void addCtrlMenuItems(List<NuclrMenuResource> items) {
		items.add(menu("Hide Left", "Ctrl+F1", "hideLeft"));
		items.add(menu("Hide Right", "Ctrl+F2", "hideRight"));
		items.add(menu("Sort by name", "Ctrl+F3", "sortByName"));
		items.add(menu("Sort by extension", "Ctrl+F4", "sortByExtension"));
		items.add(menu("Sort by modified", "Ctrl+F5", "sortByModifiedDate"));
		items.add(menu("Sort by size", "Ctrl+F6", "sortBySize"));
		items.add(menu("Unsort", "Ctrl+F7", "unsort"));
		items.add(menu("Sort by create", "Ctrl+F8", "sortByCreateDate"));
		items.add(menu("Sort by access", "Ctrl+F9", "sortByAccessTime"));
		items.add(menu("Sort menu", "Ctrl+F12", "sortMenu"));
	}

	private static void addShiftMenuItems(List<NuclrMenuResource> items,
			boolean isDirectory) {
		items.add(menu("Create file", "Shift+F4", "createFile"));
		items.add(menu("Go to path", GO_TO_PATH_SHORTCUT, "goToPath"));
		items.add(menu("Delete Permanently", "Shift+F8", "deletePermanent"));
	}
	
	private static NuclrMenuResource menu(String name, String functionKey, String eventType) {
		return new NuclrMenuResource(name, functionKey, eventType);
	}

	@Override
	public boolean onFocusGained() {
		setPluginFocused(true);
		return true;
	}

	@Override
	public void onFocusLost() {
		setPluginFocused(false);
	}

	private void setPluginFocused(boolean flag) {
		this.focused = flag;
	}

	@Override
	public boolean singleton() {
		return false;
	}

	@Override
	public boolean isFocused() {
		return this.focused;
	}

	@Override
	public NuclrResourcePath getCurrentResource() {
		return this.currentFolder;
	}

	@Override
	public String uuid() {
		return uuid;
	}

}
