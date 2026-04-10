package dev.nuclr.plugin.core.panel.fs.plugin;

import dev.nuclr.platform.plugin.NuclrResourcePath;

public final class LocalMenuActionEvent {

	private final String actionId;
	private final NuclrResourcePath source;

	public LocalMenuActionEvent(String actionId, NuclrResourcePath source) {
		this.actionId = actionId;
		this.source = source;
	}

	public String getActionId() {
		return actionId;
	}

	public NuclrResourcePath getSource() {
		return source;
	}
}
