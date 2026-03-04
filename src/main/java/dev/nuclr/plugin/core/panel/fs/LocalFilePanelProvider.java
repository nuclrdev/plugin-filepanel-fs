package dev.nuclr.plugin.core.panel.fs;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import dev.nuclr.plugin.panel.CollisionAction;
import dev.nuclr.plugin.panel.CopyOptions;
import dev.nuclr.plugin.panel.CopyProgress;
import dev.nuclr.plugin.panel.CopyResult;
import dev.nuclr.plugin.panel.CopyStatus;
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

    @Override
    public boolean supportsPath(Path path) {
        return path != null && path.getFileSystem().equals(FileSystems.getDefault());
    }

    @Override
    public String validateCopy(List<Path> items, Path targetDirectory) {
        if (!supportsPath(targetDirectory)) {
            return "Target is not on the local filesystem.";
        }
        if (targetDirectory == null || !Files.isDirectory(targetDirectory)) {
            return "Target directory is not available.";
        }
        if (items == null || items.isEmpty()) {
            return "Nothing selected.";
        }
        return null;
    }

    @Override
    public CopyResult copy(List<Path> items, Path targetDirectory, CopyOptions options, CopyProgress progress) {
        var errors = new ArrayList<String>();
        int copied = 0;

        for (Path source : items) {
            if (progress.isCancelled()) {
                return CopyResult.cancelled(copied, errors);
            }

            String targetName = options.targetNameOverride() != null
                    ? options.targetNameOverride()
                    : source.getFileName().toString();
            Path target = targetDirectory.resolve(targetName);
            try {
                copied += copyPath(source, target, options, progress);
            } catch (IOException e) {
                errors.add(source + " -> " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            return copied > 0
                    ? CopyResult.partial(copied, errors)
                    : new CopyResult(CopyStatus.FAILED, copied, List.copyOf(errors), "Copy failed.");
        }
        return CopyResult.success(copied);
    }

    private int copyPath(Path source, Path target, CopyOptions options, CopyProgress progress) throws IOException {
        if (progress.isCancelled()) {
            return 0;
        }

        progress.onItemStarted(source, target);

        Path resolvedTarget = resolveTarget(target, options.collisionAction());
        if (resolvedTarget == null) {
            return 0;
        }

        if (Files.isDirectory(source)) {
            Files.createDirectories(resolvedTarget);
            int copied = 1;
            if (!options.recursive()) {
                progress.onItemCompleted(source, resolvedTarget);
                return copied;
            }
            try (var stream = Files.list(source)) {
                for (Path child : stream.toList()) {
                    copied += copyPath(child, resolvedTarget.resolve(child.getFileName().toString()), options, progress);
                }
            }
            progress.onItemCompleted(source, resolvedTarget);
            return copied;
        }

        Path parent = resolvedTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, resolvedTarget, StandardCopyOption.REPLACE_EXISTING);
        progress.onItemCompleted(source, resolvedTarget);
        return 1;
    }

    private Path resolveTarget(Path target, CollisionAction action) throws IOException {
        if (!Files.exists(target)) {
            return target;
        }

        return switch (action) {
            case OVERWRITE -> target;
            case SKIP -> null;
            case KEEP_BOTH -> uniqueSibling(target);
        };
    }

    private Path uniqueSibling(Path target) {
        String name = target.getFileName().toString();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }

        Path candidate = target.resolveSibling(base + " - Copy" + ext);
        int index = 2;
        while (Files.exists(candidate)) {
            candidate = target.resolveSibling(base + " - Copy (" + index + ")" + ext);
            index++;
        }
        return candidate;
    }
}
