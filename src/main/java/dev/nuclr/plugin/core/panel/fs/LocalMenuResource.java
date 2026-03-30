package dev.nuclr.plugin.core.panel.fs;

import dev.nuclr.plugin.MenuResource;

public final class LocalMenuResource extends MenuResource {

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
