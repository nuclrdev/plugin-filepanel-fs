package dev.nuclr.plugin.core.panel.fs;

import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.event.PluginEvent;

public final class LocalMenuActionEvent extends PluginEvent {

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
