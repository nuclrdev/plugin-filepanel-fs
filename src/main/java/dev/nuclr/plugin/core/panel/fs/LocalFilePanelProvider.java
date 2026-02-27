package dev.nuclr.plugin.core.panel.fs;

import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;

import dev.nuclr.plugin.panel.FilePanelProvider;
import dev.nuclr.plugin.panel.PanelRoot;

/**
 * {@link FilePanelProvider} for the local (default) filesystem.
 *
 * <p>Returns one {@link PanelRoot} per root directory reported by
 * {@link FileSystems#getDefault()} — e.g. {@code C:\}, {@code D:\} on Windows
 * or {@code /} on Linux/macOS.
 */
public class LocalFilePanelProvider implements FilePanelProvider {

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String displayName() {
        return "Local Filesystem";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public List<PanelRoot> roots() {
        var roots = new ArrayList<PanelRoot>();
        FileSystems.getDefault().getRootDirectories()
                .forEach(p -> roots.add(new PanelRoot(p.toString(), p)));
        return roots;
    }
}
