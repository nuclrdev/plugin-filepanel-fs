package dev.nuclr.plugin.core.panel.fs.plugin;

import dev.nuclr.plugin.NuclrMenuResource;

public final class LocalMenuResource extends NuclrMenuResource {

	private final String eventType;

	public LocalMenuResource(String name, String keyStroke, String eventType) {
		setName(name);
		setKeyStroke(keyStroke);
		this.eventType = eventType;
	}

	@Override
	public String getEventType() {
		return eventType;
	}
}
