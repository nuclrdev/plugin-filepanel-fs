package dev.nuclr.plugin.core.panel.fs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.DosFileAttributes;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;

public class LocalFilePanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
			"CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
	private static final Set<String> WINDOWS_EXECUTABLE_EXTENSIONS = Set.of(
			"exe", "com", "bat", "cmd");

	private final JTable table;
	private final JLabel statusLabel;
	private final JLabel pathLabel;
	private final LocalFilePanelModel model;
	private final Border inactiveBorder;
	private final Border activeBorder;
	private final FileNameHighlighter fileNameHighlighter;

	private Path currentDirectory;

	public LocalFilePanel() {
		model = new LocalFilePanelModel();
		table = new JTable(model);
		statusLabel = new JLabel(" ");
		pathLabel = new JLabel(" ");
		inactiveBorder = BorderFactory.createEmptyBorder(4, 4, 4, 4);
		activeBorder = BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(
						UIManager.getColor("Table.selectionBackground") != null
								? UIManager.getColor("Table.selectionBackground")
								: UIManager.getColor("Component.focusColor") != null
										? UIManager.getColor("Component.focusColor")
										: java.awt.Color.GRAY),
				BorderFactory.createEmptyBorder(3, 3, 3, 3));
		fileNameHighlighter = new FileNameHighlighter(table.getForeground());

		setLayout(new BorderLayout(0, 4));
		setBorder(inactiveBorder);

		pathLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

		table.setFillsViewportHeight(true);
		table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		table.setRowSelectionAllowed(true);
		table.getColumnModel().getColumn(1).setPreferredWidth(100);
		table.getColumnModel().getColumn(1).setMaxWidth(120);
		table.getColumnModel().getColumn(2).setPreferredWidth(160);
		table.getColumnModel().getColumn(2).setMaxWidth(180);
		table.setDefaultRenderer(Object.class, new EntryRenderer());
		applyUiFonts();

		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("ENTER"), "openSelected");
		table.getActionMap().put("openSelected", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				openSelectedEntry();
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("LEFT"), "pageUpSelection");
		table.getActionMap().put("pageUpSelection", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				moveSelectionByPage(-1);
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("RIGHT"), "pageDownSelection");
		table.getActionMap().put("pageDownSelection", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				moveSelectionByPage(1);
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("F7"), "createNewFolder");
		table.getActionMap().put("createNewFolder", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				createNewFolder();
			}
		});

		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateStatus();
			}
		});
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
					openSelectedEntry();
				}
			}
		});

		add(pathLabel, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);
		add(statusLabel, BorderLayout.SOUTH);
	}

	@Override
	public void updateUI() {
		super.updateUI();
		if (table != null) {
			applyUiFonts();
		}
	}

	public void focusTable() {
		if (model.getRowCount() > 0 && table.getSelectedRow() < 0) {
			table.setRowSelectionInterval(0, 0);
		}
		table.requestFocusInWindow();
	}

	public void setPluginFocused(boolean focused) {
		setBorder(focused ? activeBorder : inactiveBorder);
		if (focused) {
			focusTable();
		}
		revalidate();
		repaint();
	}

	public void showDirectory(Path path) {
		showDirectory(path, null);
	}

	public void showDirectory(Path path, Path selectedPath) {
		currentDirectory = path;
		pathLabel.setText(path == null ? " " : path.toString());
		model.setEntries(readEntries(path));
		if (model.getRowCount() > 0) {
			if (selectedPath != null && selectPath(selectedPath)) {
				return;
			}
			table.setRowSelectionInterval(0, 0);
		} else {
			statusLabel.setText(" ");
		}
	}

	public void createNewFolder() {
		createNewFolder(currentDirectory);
	}

	public void createNewFolder(Path contextPath) {
		Path resolvedDirectory = resolveFolderCreationDirectory(currentDirectory, contextPath);
		if (resolvedDirectory != null) {
			currentDirectory = resolvedDirectory;
			pathLabel.setText(currentDirectory.toString());
		}

		if (currentDirectory == null) {
			showError("No current directory is open.");
			return;
		}
		if (!Files.exists(currentDirectory)) {
			showError("The current directory does not exist.");
			return;
		}
		if (!Files.isDirectory(currentDirectory)) {
			showError("The current path is not a directory.");
			return;
		}

		try {
			if (Files.getFileStore(currentDirectory).isReadOnly()) {
				showError("The current directory is on a read-only filesystem.");
				return;
			}
		} catch (IOException ex) {
			showError("Cannot determine whether the current filesystem is writable: " + ex.getMessage());
			return;
		}

		if (!Files.isWritable(currentDirectory)) {
			showError("You do not have permission to create folders in this directory.");
			return;
		}

		String folderName = JOptionPane.showInputDialog(
				this,
				"Create new folder in:\n" + currentDirectory,
				"Create New Folder",
				JOptionPane.PLAIN_MESSAGE);
		if (folderName == null) {
			return;
		}

		String validationError = validateFolderName(folderName);
		if (validationError != null) {
			showError(validationError);
			return;
		}

		String normalizedName = folderName.trim();
		Path newFolder = currentDirectory.resolve(normalizedName);
		try {
			Files.createDirectory(newFolder);
			showDirectory(currentDirectory, newFolder);
		} catch (FileAlreadyExistsException ex) {
			showError("A file or folder with that name already exists.");
		} catch (AccessDeniedException ex) {
			showError("Access denied while creating the folder.");
		} catch (ReadOnlyFileSystemException ex) {
			showError("The current filesystem is read-only.");
		} catch (SecurityException ex) {
			showError("Security policy denied folder creation.");
		} catch (IOException ex) {
			showError("Cannot create folder: " + ex.getMessage());
		}
	}

	private List<LocalFilePanelModel.Entry> readEntries(Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return List.of();
		}

		List<LocalFilePanelModel.Entry> entries = new ArrayList<>();
		Path parent = directory.getParent();
		if (parent != null) {
			entries.add(LocalFilePanelModel.Entry.parent(parent));
		}

		try (var stream = Files.list(directory)) {
			stream.sorted(Comparator
					.comparing((Path path) -> !Files.isDirectory(path))
					.thenComparing(path -> path.getFileName() == null ? path.toString() : path.getFileName().toString(),
							String.CASE_INSENSITIVE_ORDER))
					.forEach(path -> entries.add(toEntry(path)));
		} catch (IOException ex) {
			entries.clear();
			statusLabel.setText("Cannot read " + directory + ": " + ex.getMessage());
		}

		return entries;
	}

	private LocalFilePanelModel.Entry toEntry(Path path) {
		boolean directory = Files.isDirectory(path);
		boolean hidden = isHidden(path);
		boolean system = isSystem(path);
		boolean executable = isExecutable(path, directory);
		long sizeBytes = 0L;
		FileTime modifiedTime = null;
		try {
			if (!directory) {
				sizeBytes = Files.size(path);
			}
			modifiedTime = Files.getLastModifiedTime(path);
		} catch (IOException ignored) {
			// Keep listing usable even when some attributes cannot be read.
		}
		String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
		return new LocalFilePanelModel.Entry(path, name, directory, false, hidden, system, executable, sizeBytes, modifiedTime);
	}

	private void openSelectedEntry() {
		int row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
		if (entry.directory()) {
			Path selectionAfterOpen = entry.parent() && currentDirectory != null ? currentDirectory : null;
			showDirectory(entry.path(), selectionAfterOpen);
		}
	}

	private void moveSelectionByPage(int direction) {
		int rowCount = model.getRowCount();
		if (rowCount == 0) {
			return;
		}

		int currentRow = table.getSelectedRow();
		if (currentRow < 0) {
			currentRow = 0;
		}

		int visibleRows = Math.max(1, table.getVisibleRect().height / Math.max(1, table.getRowHeight()));
		int targetRow = currentRow + (visibleRows * direction);
		targetRow = Math.max(0, Math.min(rowCount - 1, targetRow));

		table.setRowSelectionInterval(targetRow, targetRow);
		table.scrollRectToVisible(table.getCellRect(targetRow, 0, true));
	}

	private void updateStatus() {
		int row = table.getSelectedRow();
		if (row < 0) {
			statusLabel.setText(currentDirectory == null ? " " : currentDirectory.toString());
			return;
		}
		LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
		if (entry.parent()) {
			statusLabel.setText("Go to parent directory");
			return;
		}
		String type = entry.directory() ? "Folder" : humanReadableSize(entry.sizeBytes());
		statusLabel.setText(entry.name() + "  |  " + type);
	}

	private boolean selectPath(Path selectedPath) {
		for (int row = 0; row < model.getRowCount(); row++) {
			LocalFilePanelModel.Entry entry = model.getEntryAt(row);
			if (selectedPath.equals(entry.path())) {
				table.setRowSelectionInterval(row, row);
				table.scrollRectToVisible(table.getCellRect(row, 0, true));
				return true;
			}
		}
		return false;
	}

	private Path resolveFolderCreationDirectory(Path preferredDirectory, Path fallbackContextPath) {
		if (preferredDirectory != null && Files.isDirectory(preferredDirectory)) {
			return preferredDirectory;
		}
		if (fallbackContextPath == null) {
			return preferredDirectory;
		}
		if (Files.isDirectory(fallbackContextPath)) {
			return fallbackContextPath;
		}
		Path parent = fallbackContextPath.getParent();
		if (parent != null && Files.isDirectory(parent)) {
			return parent;
		}
		return preferredDirectory;
	}

	private static String humanReadableSize(long sizeBytes) {
		if (sizeBytes < 1024) {
			return sizeBytes + " B";
		}
		double value = sizeBytes;
		String[] units = {"KB", "MB", "GB", "TB", "PB"};
		int unitIndex = -1;
		while (value >= 1024 && unitIndex < units.length - 1) {
			value /= 1024;
			unitIndex++;
		}
		return String.format(Locale.ROOT, unitIndex == 0 ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
	}

	private static boolean isHidden(Path path) {
		try {
			return Files.isHidden(path);
		} catch (IOException ex) {
			return false;
		}
	}

	private static boolean isSystem(Path path) {
		try {
			return Files.readAttributes(path, DosFileAttributes.class).isSystem();
		} catch (Exception ex) {
			return false;
		}
	}

	private static boolean isExecutable(Path path, boolean directory) {
		if (directory) {
			return false;
		}
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (osName.contains("win")) {
			String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
			int dot = fileName.lastIndexOf('.');
			if (dot >= 0 && dot < fileName.length() - 1) {
				String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
				return WINDOWS_EXECUTABLE_EXTENSIONS.contains(extension);
			}
			return false;
		}
		return Files.isExecutable(path);
	}

	private String validateFolderName(String folderName) {
		if (folderName == null) {
			return "Folder name is required.";
		}
		String trimmed = folderName.trim();
		if (trimmed.isEmpty()) {
			return "Folder name cannot be blank.";
		}
		if (".".equals(trimmed) || "..".equals(trimmed)) {
			return "Folder name cannot be '.' or '..'.";
		}
		if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
			return "Folder name cannot contain path separators.";
		}
		for (int i = 0; i < trimmed.length(); i++) {
			if (Character.isISOControl(trimmed.charAt(i))) {
				return "Folder name cannot contain control characters.";
			}
		}

		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (osName.contains("win")) {
			for (char c : "<>:\"|?*".toCharArray()) {
				if (trimmed.indexOf(c) >= 0) {
					return "Folder name contains characters not allowed on Windows.";
				}
			}
			if (trimmed.endsWith(" ") || trimmed.endsWith(".")) {
				return "Windows folder names cannot end with a space or period.";
			}
			if (WINDOWS_RESERVED_NAMES.contains(trimmed.toUpperCase(Locale.ROOT))) {
				return "Folder name is reserved on Windows.";
			}
		}

		Path target = currentDirectory.resolve(trimmed);
		if (Files.exists(target)) {
			return "A file or folder with that name already exists.";
		}
		return null;
	}

	private void showError(String message) {
		JOptionPane.showMessageDialog(this, message, "Create New Folder", JOptionPane.ERROR_MESSAGE);
	}

	private void applyUiFonts() {
		Font baseFont = resolveBaseFont();
		table.setFont(baseFont);
		table.getTableHeader().setFont(baseFont);
		pathLabel.setFont(baseFont);
		statusLabel.setFont(baseFont);
		table.setRowHeight(Math.max(20, table.getFontMetrics(baseFont).getHeight() + 6));
	}

	private Font resolveBaseFont() {
		Font tableFont = UIManager.getFont("Table.font");
		if (tableFont != null) {
			return tableFont;
		}
		Font labelFont = UIManager.getFont("Label.font");
		if (labelFont != null) {
			return labelFont;
		}
		return getFont() != null ? getFont() : new Font(Font.DIALOG, Font.PLAIN, 12);
	}

	private static final class EntryRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(
				JTable table,
				Object value,
				boolean isSelected,
				boolean hasFocus,
				int row,
				int column) {
			Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			LocalFilePanel panel = (LocalFilePanel) SwingUtilities.getAncestorOfClass(LocalFilePanel.class, table);
			LocalFilePanelModel model = (LocalFilePanelModel) table.getModel();
			LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
			component.setFont(table.getFont().deriveFont(entry.directory() ? Font.BOLD : Font.PLAIN));
			if (component instanceof JLabel label) {
				label.setHorizontalAlignment(column == 1 ? SwingConstants.RIGHT : SwingConstants.LEFT);
				if (!isSelected && column == 0 && panel != null) {
					label.setForeground(panel.fileNameHighlighter.colorFor(entry));
				}
			}
			return component;
		}
	}
}
