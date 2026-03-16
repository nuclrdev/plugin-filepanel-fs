package dev.nuclr.plugin.core.panel.fs;

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
import dev.nuclr.plugin.PluginInfo;
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

	private static final PluginInfo FALLBACK_PLUGIN_INFO = buildFallbackPluginInfo();

	private ApplicationPluginContext context;
	private LocalFilePanel panel;

	// -------------------------------------------------------------------------
	// BasePlugin
	// -------------------------------------------------------------------------

	@Override
	public PluginInfo getPluginInfo() {
		ObjectMapper objectMapper = context != null ? context.getObjectMapper() : new ObjectMapper();
		try (var is = getClass().getResourceAsStream("/plugin.json")) {
			if (is != null) {
				return objectMapper.readValue(is, PluginInfo.class);
			}
		} catch (Exception e) {
			log.error("Error reading /plugin.json for LocalFilePanelProvider", e);
		}
		return FALLBACK_PLUGIN_INFO;
	}

	@Override
	public JComponent getPanel() {
		if (panel == null) {
			panel = new LocalFilePanel();
		}
		return panel;
	}

	@Override
	public List<MenuResource> getMenuItems(PluginPathResource source) {
		return List.of();
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
		return msg instanceof PluginThemeUpdatedEvent;
	}

	@Override
	public void handleMessage(PluginEvent e) {
		if (e instanceof PluginThemeUpdatedEvent && panel != null) {
			panel.repaint();
		}
	}

	private static PluginInfo buildFallbackPluginInfo() {
		PluginInfo info = new PluginInfo();
		info.setName("Local Filesystem Panel");
		info.setId("dev.nuclr.plugin.core.panel.fs");
		info.setVersion("1.0.0");
		info.setDescription("Provides local filesystem roots (drives/mount points) to the file panel.");
		info.setAuthor("Nuclr Development Team");
		info.setLicense("Apache-2.0");
		info.setWebsite("https://nuclr.dev");
		info.setPageUrl("https://nuclr.dev/plugins/core/filepanel-fs.html");
		info.setDocUrl("https://nuclr.dev/plugins/core/filepanel-fs.html");
		return info;
	}

	@Override
	public void onFocusGained() {
		((LocalFilePanel) getPanel()).setPluginFocused(true);
	}

	@Override
	public void onFocusLost() {
		if (panel != null) {
			panel.setPluginFocused(false);
		}
	}
}
