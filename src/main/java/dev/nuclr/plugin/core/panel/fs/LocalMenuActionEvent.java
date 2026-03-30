package dev.nuclr.plugin.core.panel.fs;

import dev.nuclr.plugin.PluginPathResource;

public final class LocalMenuActionEvent {

	private final String actionId;
	private final PluginPathResource source;

	public LocalMenuActionEvent(String actionId, PluginPathResource source) {
		this.actionId = actionId;
		this.source = source;
	}

	public String getActionId() {
		return actionId;
	}

	public PluginPathResource getSource() {
		return source;
	}
}
