package dev.nuclr.plugin.core.panel.fs;

import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.event.PluginEvent;

public final class LocalMenuResource extends MenuResource {

	private final PluginEvent event;

	public LocalMenuResource(String name, String keyStroke, PluginEvent event) {
		setName(name);
		setKeyStroke(keyStroke);
		this.event = event;
	}

	@Override
	public PluginEvent getEvent() {
		return event;
	}
}
