package dev.nuclr.plugin.core.panel.fs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
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

	private static final String PARENT_LABEL = "..";

	private final JTable table;
	private final JLabel statusLabel;
	private final JLabel pathLabel;
	private final LocalFilePanelModel model;
	private final Border inactiveBorder;
	private final Border activeBorder;

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

		setLayout(new BorderLayout(0, 4));
		setBorder(inactiveBorder);

		pathLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

		table.setFillsViewportHeight(true);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		table.setRowSelectionAllowed(true);
		table.getColumnModel().getColumn(1).setPreferredWidth(100);
		table.getColumnModel().getColumn(1).setMaxWidth(120);
		table.getColumnModel().getColumn(2).setPreferredWidth(160);
		table.getColumnModel().getColumn(2).setMaxWidth(180);
		table.setDefaultRenderer(Object.class, new EntryRenderer());

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
		currentDirectory = path;
		pathLabel.setText(path == null ? " " : path.toString());
		model.setEntries(readEntries(path));
		if (model.getRowCount() > 0) {
			table.setRowSelectionInterval(0, 0);
		} else {
			statusLabel.setText(" ");
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
		return new LocalFilePanelModel.Entry(path, name, directory, false, sizeBytes, modifiedTime);
	}

	private void openSelectedEntry() {
		int row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
		if (entry.directory()) {
			showDirectory(entry.path());
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
			LocalFilePanelModel model = (LocalFilePanelModel) table.getModel();
			LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
			component.setFont(component.getFont().deriveFont(entry.directory() ? Font.BOLD : Font.PLAIN));
			if (component instanceof JLabel label) {
				label.setHorizontalAlignment(column == 1 ? SwingConstants.RIGHT : SwingConstants.LEFT);
			}
			return component;
		}
	}
}
