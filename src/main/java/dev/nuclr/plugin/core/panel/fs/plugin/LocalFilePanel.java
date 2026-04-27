package dev.nuclr.plugin.core.panel.fs.plugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.OverlayLayout;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import dev.nuclr.plugin.core.panel.fs.LocalFileSystemPlugin;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class LocalFilePanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final String FILE_HILIGHT_PREFIX = "file-hilight-";
	private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
			"CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
	private static final Set<String> WINDOWS_EXECUTABLE_EXTENSIONS = Set.of(
			"exe", "com", "bat", "cmd");

	private NuclrEventBus eventBus;
	private final JTable table;
	private final JLabel statusLabel;
	private final JLabel pathLabel;
	private final LocalFilePanelModel model;
	private final Border inactiveBorder;
	private final Border activeBorder;
	private final LocalFileSystemPlugin provider;
	private final Runnable helpAction;
	private final JLabel searchLabel;
	private final LocalFileDeletionService deletionService;
	private final JScrollPane tableScrollPane;
	private final JPanel centerPanel;
	private final JPanel loadingOverlay;
	private final Timer loadingOverlayTimer;
	private final AtomicLong directoryLoadGeneration;

	private Path currentDirectory;
	private List<LocalFilePanelModel.Entry> loadedEntries = List.of();
	private SortMode sortMode = SortMode.NAME;
	private boolean sortReversed;
	private StringBuilder searchQuery;
	private Popup searchPopup;
	private boolean altSearchActive;
	private int rightDragAnchorRow = -1;
	private boolean rightDragOccurred;
	private NuclrThemeScheme themeScheme;

	public LocalFilePanel(LocalFileSystemPlugin provider, Runnable helpAction) {
		model = new LocalFilePanelModel();
		table = new JTable(model);
		table.setFocusTraversalKeysEnabled(false);
		
		// Remove the "selectNextColumnCell" action tied to the TAB key
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		     .put(KeyStroke.getKeyStroke("TAB"), "none");

		// Remove the "selectPreviousColumnCell" action tied to SHIFT-TAB
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		     .put(KeyStroke.getKeyStroke("shift TAB"), "none");
		
		statusLabel = new JLabel(" ");
		pathLabel = new JLabel(" ");
		searchLabel = new JLabel();
		tableScrollPane = new JScrollPane(table);
		centerPanel = new JPanel();
		loadingOverlay = createLoadingOverlay();
		this.provider = provider;
		this.helpAction = helpAction;
		deletionService = new LocalFileDeletionService();
		directoryLoadGeneration = new AtomicLong();
		loadingOverlayTimer = new Timer(150, e -> setLoadingOverlayVisible(true));
		loadingOverlayTimer.setRepeats(false);
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
		centerPanel.setLayout(new OverlayLayout(centerPanel));

		pathLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		searchLabel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(UIManager.getColor("Table.selectionBackground") != null
						? UIManager.getColor("Table.selectionBackground")
						: java.awt.Color.GRAY),
				BorderFactory.createEmptyBorder(4, 8, 4, 8)));
		searchLabel.setOpaque(true);
		searchLabel.setBackground(UIManager.getColor("Panel.background"));
		searchLabel.setForeground(UIManager.getColor("Label.foreground"));

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
				openSelectedEntry(false);
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("shift ENTER"), "openSelectedShift");
		table.getActionMap().put("openSelectedShift", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				openSelectedEntry(true);
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("F1"), "openHelp");
		table.getActionMap().put("openHelp", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (helpAction != null) {
					helpAction.run();
				}
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
		
		// View
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("F3"), "viewFile");
		table.getActionMap().put("viewFile", new AbstractAction() {
			private static final long serialVersionUID = 1L;
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {

				if (getSelectedResource() != null) {
					eventBus.emit(LocalFilePanel.this, "fs.view", Map.of("path", getSelectedResource()));
				}

			}
		});

		// Edit
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("F4"), "editFile");
		table.getActionMap().put("editFile", new AbstractAction() {
			private static final long serialVersionUID = 1L;
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {

				if (getSelectedResource() != null) {
					eventBus.emit(LocalFilePanel.this, "fs.edit", Map.of("path", getSelectedResource()));
				}

			}
		});


		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("F5"), "copyFiles");
		table.getActionMap().put("copyFiles", new AbstractAction() {
			private static final long serialVersionUID = 1L;
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				List<NuclrResourcePath> resources = getSelectedResources();
				AtomicBoolean accepted = new AtomicBoolean(false);
				Map<String, Object> payload = new HashMap<>();
				payload.put("paths", resources);
				payload.put("accepted", accepted);
				eventBus.emit(LocalFilePanel.this, "fs.copy", payload);
				if (!accepted.get()) {
					provider.copyIntoCurrentPanel(resources);
				}
			}
		});

		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("F6"), "moveFiles");
		table.getActionMap().put("moveFiles", new AbstractAction() {
			private static final long serialVersionUID = 1L;
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				List<NuclrResourcePath> resources = getSelectedResources();
				AtomicBoolean accepted = new AtomicBoolean(false);
				Map<String, Object> payload = new HashMap<>();
				payload.put("paths", resources);
				payload.put("accepted", accepted);
				eventBus.emit(LocalFilePanel.this, "fs.move", payload);
				if (!accepted.get()) {
					provider.moveIntoCurrentPanel(resources);
				}
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
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("F8"), "deleteSelection");
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("DELETE"), "deleteSelection");
		table.getActionMap().put("deleteSelection", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				deleteSelection(false);
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("shift F8"), "deleteSelectionPermanently");
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("shift DELETE"), "deleteSelectionPermanently");
		table.getActionMap().put("deleteSelectionPermanently", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				deleteSelection(true);
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("INSERT"), "toggleSelectionAndAdvance");
		table.getActionMap().put("toggleSelectionAndAdvance", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				toggleSelectionAndAdvance();
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_MULTIPLY, 0), "invertSelection");
		table.getActionMap().put("invertSelection", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				invertSelection();
			}
		});
		table.getInputMap(JComponent.WHEN_FOCUSED)
				.put(KeyStroke.getKeyStroke("ctrl C"), "copyPathToClipboard");
		table.getInputMap(JComponent.WHEN_FOCUSED)
				.put(KeyStroke.getKeyStroke("ctrl V"), "pasteFromClipboard");
		table.getActionMap().put("pasteFromClipboard", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (provider != null) provider.pasteFromClipboard();
			}
		});
		table.getActionMap().put("copyPathToClipboard", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (table.getSelectedRowCount() == 0) return;
				int row = table.getSelectionModel().getLeadSelectionIndex();
				java.awt.Rectangle rect = row >= 0
						? table.getCellRect(row, 0, true)
						: new java.awt.Rectangle(0, 0, 0, 0);
				showClipboardPopup(rect.x, rect.y + rect.height);
			}
		});
		bindSortShortcut("ctrl F3", "sortByNameShortcut", SortMode.NAME);
		bindSortShortcut("ctrl F4", "sortByExtensionShortcut", SortMode.EXTENSION);
		bindSortShortcut("ctrl F5", "sortByModifiedShortcut", SortMode.MODIFIED_DATE);
		bindSortShortcut("ctrl F6", "sortBySizeShortcut", SortMode.SIZE);
		bindSortShortcut("ctrl F7", "unsortShortcut", SortMode.UNSORTED);
		bindSortShortcut("ctrl F8", "sortByCreateShortcut", SortMode.CREATED_DATE);
		bindSortShortcut("ctrl F9", "sortByAccessShortcut", SortMode.ACCESS_TIME);
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("ctrl F12"), "showSortMenu");
		table.getActionMap().put("showSortMenu", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				showSortMenu();
			}
		});

		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateStatus();
				if (eventBus != null) {
					Path selectedPath = getSelectedEntryPath();
					if (selectedPath != null) {
						eventBus.emit(LocalFileSystemPlugin.PLUGIN_ID, "fs.path.selected",
								Map.of("path", selectedPath));
					}
				}				
			}
		});
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isRightMouseButton(e)) {
					int row = table.rowAtPoint(e.getPoint());
					if (row >= 0) {
						rightDragAnchorRow = row;
						rightDragOccurred = false;
						if (!table.isRowSelected(row)) {
							table.setRowSelectionInterval(row, row);
						}
						table.scrollRectToVisible(table.getCellRect(row, 0, true));
					}
				}
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (isOpenWithMouseGesture(e)) {
					openSelectedEntry(e.isShiftDown());
				}
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (SwingUtilities.isRightMouseButton(e)) {
					boolean wasDrag = rightDragOccurred;
					rightDragAnchorRow = -1;
					rightDragOccurred = false;
					if (!wasDrag && table.getSelectedRowCount() > 0) {
						showClipboardPopup(e.getX(), e.getY());
					}
				}
			}
		});
		table.addMouseMotionListener(new MouseMotionAdapter() {
			@Override
			public void mouseDragged(MouseEvent e) {
				if (!SwingUtilities.isRightMouseButton(e) || rightDragAnchorRow < 0) {
					return;
				}
				rightDragOccurred = true;
				int row = table.rowAtPoint(e.getPoint());
				if (row < 0) {
					row = e.getPoint().y < 0 ? 0 : model.getRowCount() - 1;
				}
				if (row >= 0 && model.getRowCount() > 0) {
					int start = Math.min(rightDragAnchorRow, row);
					int end = Math.max(rightDragAnchorRow, row);
					table.setRowSelectionInterval(start, end);
					table.scrollRectToVisible(table.getCellRect(row, 0, true));
				}
			}
		});
		table.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				handleSearchKeyPressed(e);
			}

			@Override
			public void keyTyped(KeyEvent e) {
				handleSearchKeyTyped(e);
			}

			@Override
			public void keyReleased(KeyEvent e) {
				if (isSearchKeyCode(e.getKeyCode())) {
					if (altSearchActive) {
						e.consume();
					}
					hideSearchPopup();
				}
			}
		});

		centerPanel.add(loadingOverlay);
		centerPanel.add(tableScrollPane);
		add(pathLabel, BorderLayout.NORTH);
		add(centerPanel, BorderLayout.CENTER);
		add(statusLabel, BorderLayout.SOUTH);
	}

	public void setThemeScheme(NuclrThemeScheme themeScheme) {
		this.themeScheme = themeScheme;
	}

	private Color colorFor(LocalFilePanelModel.Entry entry) {
		Color defaultColor = table.getForeground();
		if (entry.parent() || entry.directory()) {
			return defaultColor;
		}
		if (entry.hidden() || entry.system()) {
			return themeScheme != null
					? themeScheme.color(FILE_HILIGHT_PREFIX + "hidden", new Color(120, 120, 120))
					: new Color(120, 120, 120);
		}

		String extension = extensionOf(entry.name());
		Color fallback = entry.executable()
				? themeScheme != null
						? themeScheme.color(FILE_HILIGHT_PREFIX + "executable", new Color(231, 92, 92))
						: new Color(231, 92, 92)
				: defaultColor;
		return themeScheme != null ? themeScheme.color(fileHighlightKey(extension), fallback) : fallback;
	}

	private static String fileHighlightKey(String extension) {
		if (extension == null) {
			return FILE_HILIGHT_PREFIX;
		}
		String normalized = extension.trim().toLowerCase(Locale.ROOT);
		if (normalized.startsWith(".")) {
			normalized = normalized.substring(1);
		}
		return FILE_HILIGHT_PREFIX + normalized;
	}

	private static String extensionOf(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return "";
		}
		return name.substring(dot + 1).toLowerCase(Locale.ROOT);
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
//		setBorder(focused ? activeBorder : inactiveBorder);
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
		hideSearchPopup();
		long loadGeneration = directoryLoadGeneration.incrementAndGet();
		startDirectoryLoadIndicator(path);
		Thread.ofVirtual().start(() -> {
			DirectoryReadResult result = readEntries(path);
			SwingUtilities.invokeLater(() -> applyDirectoryReadResult(loadGeneration, path, selectedPath, result));
		});
	}

	public Path getCurrentDirectory() {
		return currentDirectory;
	}

	public void sortByName() {
		setSortMode(SortMode.NAME);
	}

	public void sortByExtension() {
		setSortMode(SortMode.EXTENSION);
	}

	public void sortByModifiedDate() {
		setSortMode(SortMode.MODIFIED_DATE);
	}

	public void sortBySize() {
		setSortMode(SortMode.SIZE);
	}

	public void unsort() {
		setSortMode(SortMode.UNSORTED);
	}

	public void sortByCreateDate() {
		setSortMode(SortMode.CREATED_DATE);
	}

	public void sortByAccessTime() {
		setSortMode(SortMode.ACCESS_TIME);
	}

	public void showSortMenu() {
		SortMode selectedMode = (SortMode) JOptionPane.showInputDialog(
				this,
				"Select file sort mode:",
				"Sort Menu",
				JOptionPane.PLAIN_MESSAGE,
				null,
				SortMode.values(),
				sortMode);
		if (selectedMode != null) {
			setSortMode(selectedMode);
		}
	}

	public NuclrResourcePath getSelectedResource() {
		List<NuclrResourcePath> selected = getSelectedResources();
		return selected.isEmpty() ? null : selected.get(0);
	}

	public List<NuclrResourcePath> getSelectedResources() {
		int[] selectedRows = table.getSelectedRows();
		List<NuclrResourcePath> resources = new ArrayList<>();
		for (int selectedRow : selectedRows) {
			LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(selectedRow));
			if (!entry.parent()) {
				resources.add(toResource(entry));
			}
		}
		return resources;
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
			showError("Create New Folder", "No current directory is open.");
			return;
		}
		if (!Files.exists(currentDirectory)) {
			showError("Create New Folder", "The current directory does not exist.");
			return;
		}
		if (!Files.isDirectory(currentDirectory)) {
			showError("Create New Folder", "The current path is not a directory.");
			return;
		}

		if (!Files.isWritable(currentDirectory)) {
			showError("Create New Folder", "You do not have permission to create folders in this directory.");
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
			showError("Create New Folder", validationError);
			return;
		}

		String normalizedName = folderName.trim();
		Path newFolder = currentDirectory.resolve(normalizedName);
		try {
			Files.createDirectory(newFolder);
			showDirectory(currentDirectory, newFolder);
		} catch (FileAlreadyExistsException ex) {
			showError("Create New Folder", "A file or folder with that name already exists.");
		} catch (AccessDeniedException ex) {
			showError("Create New Folder", "Access denied while creating the folder.");
		} catch (ReadOnlyFileSystemException ex) {
			showError("Create New Folder", "The current filesystem is read-only.");
		} catch (SecurityException ex) {
			showError("Create New Folder", "Security policy denied folder creation.");
		} catch (IOException ex) {
			showError("Create New Folder", "Cannot create folder: " + ex.getMessage());
		}
	}

	public void deleteSelection(boolean permanent) {
		List<LocalFilePanelModel.Entry> selectedEntries = getSelectedEntriesForDelete();
		if (selectedEntries.isEmpty()) {
			showError("Delete", "No files or folders selected.");
			return;
		}
		deletionService.delete(this, selectedEntries, permanent, () -> showDirectory(currentDirectory));
	}

	private DirectoryReadResult readEntries(Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return new DirectoryReadResult(List.of(), null);
		}

		List<LocalFilePanelModel.Entry> entries = new ArrayList<>();
		Path parent = directory.getParent();
		if (parent != null) {
			entries.add(LocalFilePanelModel.Entry.parent(parent));
		}

		try (var stream = Files.list(directory)) {
			stream.forEach(path -> entries.add(toEntry(path)));
		} catch (IOException ex) {
			return new DirectoryReadResult(List.of(), "Cannot read " + directory + ": " + ex.getMessage());
		}

		return new DirectoryReadResult(entries, null);
	}

	private NuclrResourcePath toResource(LocalFilePanelModel.Entry entry) {
		NuclrResourcePath resource = new NuclrResourcePath();
		resource.setPath(entry.path());
		resource.setName(entry.name());
		resource.setSizeBytes(entry.sizeBytes());
		return resource;
	}

	private List<LocalFilePanelModel.Entry> getSelectedEntriesForDelete() {
		int[] selectedRows = table.getSelectedRows();
		List<LocalFilePanelModel.Entry> selectedEntries = new ArrayList<>();
		for (int selectedRow : selectedRows) {
			LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(selectedRow));
			if (!entry.parent()) {
				selectedEntries.add(entry);
			}
		}
		return selectedEntries;
	}

	private LocalFilePanelModel.Entry toEntry(Path path) {
		boolean link = isLink(path);
		boolean directory = !link && Files.isDirectory(path);
		boolean hidden = isHidden(path);
		boolean system = isSystem(path);
		boolean executable = isExecutable(path, directory);
		long sizeBytes = 0L;
		FileTime modifiedTime = null;
		FileTime createdTime = null;
		FileTime accessTime = null;
		try {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			modifiedTime = attributes.lastModifiedTime();
			createdTime = attributes.creationTime();
			accessTime = attributes.lastAccessTime();
			if (!directory) {
				sizeBytes = attributes.size();
			}
		} catch (IOException ignored) {
			// Keep listing usable even when some attributes cannot be read.
		}
		String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
		return new LocalFilePanelModel.Entry(
				path,
				name,
				directory,
				link,
				false,
				hidden,
				system,
				executable,
				sizeBytes,
				modifiedTime,
				createdTime,
				accessTime);
	}

	private void openSelectedEntry(boolean shiftDown) {
		int row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
		if (entry.parent() && shiftDown) {
			revealCurrentDirectoryInSystemExplorer();
			return;
		}
		if (entry.link()) {
			Path resolvedPath = resolveLinkedPath(entry.path());
				if (resolvedPath != null && Files.isDirectory(resolvedPath)) {
				showDirectory(resolvedPath);
				selectFirstRowAndScrollToTop();
				return;
			}
			if (resolvedPath != null && provider != null && provider.requestOpen(resolvedPath)) {
				return;
			}
			if (resolvedPath != null) {
				openPathWithDefaultApplication(resolvedPath);
				return;
			}
			openPathWithDefaultApplication(entry.path());
			return;
		}
		if (entry.directory()) {
			Path selectionAfterOpen = entry.parent() && currentDirectory != null ? currentDirectory : null;
			showDirectory(entry.path(), selectionAfterOpen);
			if (!entry.parent()) {
				selectFirstRowAndScrollToTop();
			}
			return;
		}
		
		// Find any plugins
		
		
		
		if (provider != null && provider.requestOpen(entry.path())) {
			return;
		}
		openPathWithDefaultApplication(entry.path());
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

	private void toggleSelectionAndAdvance() {
		int rowCount = model.getRowCount();
		if (rowCount == 0) {
			return;
		}

		int currentRow = table.getSelectionModel().getLeadSelectionIndex();
		if (currentRow < 0) {
			currentRow = table.getSelectedRow();
		}
		if (currentRow < 0) {
			currentRow = 0;
		}

		if (!table.isRowSelected(currentRow)) {
			table.addRowSelectionInterval(currentRow, currentRow);
		}

		int nextRow = Math.min(currentRow + 1, rowCount - 1);
		table.getSelectionModel().setLeadSelectionIndex(nextRow);
		table.getSelectionModel().setAnchorSelectionIndex(nextRow);
		table.getColumnModel().getSelectionModel().setLeadSelectionIndex(0);
		table.getColumnModel().getSelectionModel().setAnchorSelectionIndex(0);
		table.repaint();
		table.scrollRectToVisible(table.getCellRect(nextRow, 0, true));
	}

	private void invertSelection() {
		int rowCount = model.getRowCount();
		if (rowCount == 0) {
			return;
		}

		int currentRow = table.getSelectionModel().getLeadSelectionIndex();
		if (currentRow < 0) {
			currentRow = table.getSelectedRow();
		}
		if (currentRow < 0) {
			currentRow = 0;
		}

		List<Integer> rowsToSelect = new ArrayList<>();
		for (int row = 0; row < rowCount; row++) {
			LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
			if (!entry.parent() && !table.isRowSelected(row)) {
				rowsToSelect.add(row);
			}
		}

		table.clearSelection();
		for (int row : rowsToSelect) {
			table.addRowSelectionInterval(row, row);
		}

		table.getSelectionModel().setLeadSelectionIndex(currentRow);
		table.getSelectionModel().setAnchorSelectionIndex(currentRow);
		table.getColumnModel().getSelectionModel().setLeadSelectionIndex(0);
		table.getColumnModel().getSelectionModel().setAnchorSelectionIndex(0);
		table.repaint();
		table.scrollRectToVisible(table.getCellRect(currentRow, 0, true));
	}

	private void updateStatus() {
		int[] selectedRows = table.getSelectedRows();
		if (selectedRows.length == 0) {
			statusLabel.setText(currentDirectory == null ? " " : currentDirectory.toString());
			return;
		}

		List<LocalFilePanelModel.Entry> selected = new ArrayList<>();
		boolean parentSelected = false;
		for (int row : selectedRows) {
			LocalFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
			if (entry.parent()) {
				parentSelected = true;
			} else {
				selected.add(entry);
			}
		}

		if (selected.isEmpty()) {
			statusLabel.setText("Go to parent directory");
			return;
		}

		if (selected.size() == 1 && !parentSelected) {
			LocalFilePanelModel.Entry entry = selected.get(0);
			String type = entry.link() ? "Link" : (entry.directory() ? "Folder" : humanReadableSize(entry.sizeBytes()));
			statusLabel.setText(entry.name() + "  |  " + type);
			return;
		}

		long totalBytes = 0;
		int fileCount = 0;
		int folderCount = 0;
		for (LocalFilePanelModel.Entry entry : selected) {
			if (entry.directory()) {
				folderCount++;
			} else {
				fileCount++;
				totalBytes += entry.sizeBytes();
			}
		}
		statusLabel.setText(
				"Bytes: " + humanReadableSize(totalBytes)
				+ ",  files: " + fileCount
				+ ",  folders: " + folderCount);
	}

	private boolean selectPath(Path selectedPath) {
		for (int row = 0; row < model.getRowCount(); row++) {
			LocalFilePanelModel.Entry entry = model.getEntryAt(row);
			if (selectedPath.equals(entry.path())) {
				selectRow(row);
				return true;
			}
		}
		return false;
	}

	private void selectFirstRowAndScrollToTop() {
		selectRow(0);
		if (table.getParent() instanceof JViewport viewport) {
			viewport.setViewPosition(new Point(0, 0));
		}
	}

	private boolean selectRow(int row) {
		int rowCount = table.getRowCount();
		if (rowCount == 0) {
			table.clearSelection();
			updateStatus();
			return false;
		}
		int boundedRow = Math.max(0, Math.min(row, rowCount - 1));
		table.setRowSelectionInterval(boundedRow, boundedRow);
		table.scrollRectToVisible(table.getCellRect(boundedRow, 0, true));
		return true;
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

	private static boolean isLink(Path path) {
		if (Files.isSymbolicLink(path)) {
			return true;
		}
		try {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return attributes.isSymbolicLink() || (attributes.isDirectory() && attributes.isOther());
		} catch (Exception ex) {
			return false;
		}
	}

	private static Path resolveLinkedPath(Path path) {
		try {
			Path resolved = path.toRealPath();
			return resolved.equals(path) ? null : resolved;
		} catch (Exception ex) {
			return null;
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

	private void showError(String title, String message) {
		JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
	}

	private static boolean isSearchKeyCode(int keyCode) {
		return IS_MAC ? keyCode == KeyEvent.VK_META : keyCode == KeyEvent.VK_ALT;
	}

	private static boolean isSearchModifierDown(KeyEvent event) {
		return IS_MAC ? event.isMetaDown() : event.isAltDown();
	}

	private void handleSearchKeyPressed(KeyEvent event) {
		if (isSearchKeyCode(event.getKeyCode())) {
			altSearchActive = true;
			event.consume();
			return;
		}
		if (!isSearchModifierDown(event)) {
			return;
		}
		int keyCode = event.getKeyCode();
		if (isSearchKeyCode(keyCode)
				|| keyCode == KeyEvent.VK_ENTER
				|| keyCode == KeyEvent.VK_LEFT
				|| keyCode == KeyEvent.VK_RIGHT
				|| keyCode == KeyEvent.VK_UP
				|| keyCode == KeyEvent.VK_DOWN
				|| keyCode == KeyEvent.VK_SHIFT
				|| keyCode == KeyEvent.VK_CONTROL) {
			return;
		}
		if (keyCode == KeyEvent.VK_BACK_SPACE) {
			if (searchQuery != null && searchQuery.length() > 0) {
				searchQuery.deleteCharAt(searchQuery.length() - 1);
				if (searchQuery.length() == 0) {
					hideSearchPopup();
				} else {
					updateSearchPopup();
					selectFirstMatch(searchQuery.toString());
				}
			}
			event.consume();
			return;
		}
		altSearchActive = true;
		event.consume();
	}

	private void handleSearchKeyTyped(KeyEvent event) {
		if (!isSearchModifierDown(event)) {
			return;
		}
		char typedChar = event.getKeyChar();
		if (Character.isISOControl(typedChar)) {
			return;
		}
		if (searchQuery == null) {
			searchQuery = new StringBuilder();
		}
		searchQuery.append(Character.toLowerCase(typedChar));
		updateSearchPopup();
		selectFirstMatch(searchQuery.toString());
		event.consume();
		altSearchActive = true;
	}

	private void updateSearchPopup() {
		if (searchQuery == null || searchQuery.length() == 0) {
			hideSearchPopup();
			return;
		}
		if (searchPopup != null) {
			searchPopup.hide();
		}
		searchLabel.setText("Search: " + searchQuery);
		try {
			Point location = table.getLocationOnScreen();
			int x = location.x + 8;
			int y = location.y + Math.max(8, table.getHeight() - searchLabel.getPreferredSize().height - 8);
			searchPopup = PopupFactory.getSharedInstance().getPopup(table, searchLabel, x, y);
			searchPopup.show();
		} catch (IllegalComponentStateException ignored) {
			searchPopup = null;
		}
	}

	private void hideSearchPopup() {
		if (searchPopup != null) {
			searchPopup.hide();
			searchPopup = null;
		}
		searchQuery = null;
		altSearchActive = false;
	}

	private void selectFirstMatch(String query) {
		String needle = query.toLowerCase(Locale.ROOT);
		int rowCount = model.getRowCount();
		if (rowCount == 0) {
			return;
		}
		int currentRow = table.getSelectedRow();
		if (currentRow < 0) {
			currentRow = -1;
		}
		for (int offset = 1; offset <= rowCount; offset++) {
			int row = Math.floorMod(currentRow + offset, rowCount);
			LocalFilePanelModel.Entry entry = model.getEntryAt(row);
			if (entry.name().toLowerCase(Locale.ROOT).startsWith(needle)) {
				table.setRowSelectionInterval(row, row);
				table.scrollRectToVisible(table.getCellRect(row, 0, true));
				return;
			}
		}
	}

	private void openPathWithDefaultApplication(Path path) {
		eventBus.emit(this, "fs.path.opened", Map.of("path", path));
	}

	private void revealCurrentDirectoryInSystemExplorer() {
		if (currentDirectory == null || !Files.isDirectory(currentDirectory)) {
			showError("Error", "No current directory is open.");
			return;
		}

		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			if (osName.contains("win")) {
				new ProcessBuilder("explorer.exe", currentDirectory.toString()).start();
				return;
			}
			if (osName.contains("mac")) {
				new ProcessBuilder("open", currentDirectory.toString()).start();
				return;
			}
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
				Desktop.getDesktop().open(currentDirectory.toFile());
				return;
			}
		} catch (Exception ex) {
			showError("Error", "Cannot open the system file explorer: " + ex.getMessage());
			return;
		}

		showError("Error", "Opening the system file explorer is not supported on this platform.");
	}

	private boolean isOpenWithMouseGesture(MouseEvent event) {
		return event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event);
	}

	private JPanel createLoadingOverlay() {
		JPanel overlay = new JPanel();
		overlay.setOpaque(true);
		overlay.setBackground(new Color(0, 0, 0, 0));
		overlay.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
		overlay.setLayout(new BoxLayout(overlay, BoxLayout.Y_AXIS));
		overlay.setAlignmentX(0.5f);
		overlay.setAlignmentY(0.5f);

		JLabel title = new JLabel("Loading folder...");
		title.setAlignmentX(Component.CENTER_ALIGNMENT);
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD));

		JProgressBar progressBar = new JProgressBar();
		progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
		progressBar.setIndeterminate(true);
		progressBar.setMaximumSize(new java.awt.Dimension(290, 18));
		progressBar.setPreferredSize(new java.awt.Dimension(290, 18));

		JLabel hint = new JLabel("Large or sleeping drives can take a moment.");
		hint.setAlignmentX(Component.CENTER_ALIGNMENT);
		hint.setForeground(new Color(230, 230, 230));

		JPanel card = new JPanel();
		card.setOpaque(true);
		card.setBackground(new Color(32, 32, 32, 235));
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(new Color(90, 90, 90)),
				BorderFactory.createEmptyBorder(14, 18, 14, 18)));
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setMaximumSize(new java.awt.Dimension(340, 120));
		card.add(title);
		card.add(Box.createVerticalStrut(10));
		card.add(progressBar);
		card.add(Box.createVerticalStrut(10));
		card.add(hint);

		overlay.add(Box.createVerticalGlue());
		card.setAlignmentX(Component.CENTER_ALIGNMENT);
		overlay.add(card);
		overlay.add(Box.createVerticalGlue());
		overlay.setVisible(false);
		return overlay;
	}

	private void startDirectoryLoadIndicator(Path path) {
		loadingOverlayTimer.stop();
		setLoadingOverlayVisible(false);
		statusLabel.setText(path == null ? " " : "Opening " + path + "...");
		loadingOverlayTimer.start();
	}

	private void applyDirectoryReadResult(long loadGeneration, Path path, Path selectedPath, DirectoryReadResult result) {
		if (loadGeneration != directoryLoadGeneration.get() || !pathEquals(path, currentDirectory)) {
			return;
		}
		loadingOverlayTimer.stop();
		setLoadingOverlayVisible(false);
		loadedEntries = result.entries();
		if (result.errorMessage() != null) {
			model.setEntries(List.of());
			statusLabel.setText(result.errorMessage());
			focusTable();
			return;
		}
		refreshDisplayedEntries(selectedPath);
	}

	private void setLoadingOverlayVisible(boolean visible) {
		loadingOverlay.setVisible(visible);
		loadingOverlay.repaint();
	}

	private void bindSortShortcut(String keyStroke, String actionId, SortMode mode) {
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke(keyStroke), actionId);
		table.getActionMap().put(actionId, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				setSortMode(mode);
			}
		});
	}

	private void setSortMode(SortMode newSortMode) {
		if (sortMode == newSortMode) {
			sortReversed = !sortReversed;
		} else {
			sortMode = newSortMode;
			sortReversed = false;
		}
		refreshDisplayedEntries(getSelectedEntryPath());
	}

	private void refreshDisplayedEntries(Path selectedPath) {
		model.setEntries(sortedEntries(loadedEntries));
		if (model.getRowCount() > 0) {
			if (selectedPath != null && selectPath(selectedPath)) {
				focusTable();
				return;
			}
			selectFirstRowAndScrollToTop();
			focusTable();
			return;
		}
		statusLabel.setText(" ");
		focusTable();
	}

	private List<LocalFilePanelModel.Entry> sortedEntries(List<LocalFilePanelModel.Entry> entries) {
		List<LocalFilePanelModel.Entry> sorted = new ArrayList<>(entries);
		if (sortMode == SortMode.UNSORTED) {
			if (sortReversed) {
				reverseEntriesPreservingParent(sorted);
			}
			return sorted;
		}
		sorted.sort(entryComparator());
		return sorted;
	}

	private Comparator<LocalFilePanelModel.Entry> entryComparator() {
		Comparator<LocalFilePanelModel.Entry> groupingComparator = Comparator
				.comparing((LocalFilePanelModel.Entry entry) -> entry.parent() ? 0 : (entry.directory() ? 1 : 2));
		Comparator<LocalFilePanelModel.Entry> detailComparator = switch (sortMode) {
			case NAME -> Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT));
			case EXTENSION -> Comparator
					.comparing(this::extensionKey)
					.thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));
			case MODIFIED_DATE -> Comparator
					.comparing(LocalFilePanelModel.Entry::modifiedTime, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));
			case SIZE -> Comparator
					.comparing((LocalFilePanelModel.Entry entry) -> entry.directory() ? Long.valueOf(-1L) : Long.valueOf(entry.sizeBytes()),
							Comparator.reverseOrder())
					.thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));
			case CREATED_DATE -> Comparator
					.comparing(LocalFilePanelModel.Entry::createdTime, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));
			case ACCESS_TIME -> Comparator
					.comparing(LocalFilePanelModel.Entry::accessTime, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));
			case UNSORTED -> Comparator.comparing(entry -> 0);
		};
		if (sortReversed) {
			detailComparator = detailComparator.reversed();
		}
		return groupingComparator.thenComparing(detailComparator);
	}

	private Path getSelectedEntryPath() {
		int selectedRow = table.getSelectedRow();
		if (selectedRow < 0) {
			return null;
		}
		return model.getEntryAt(table.convertRowIndexToModel(selectedRow)).path();
	}

	private String extensionKey(LocalFilePanelModel.Entry entry) {
		if (entry.directory()) {
			return "";
		}
		String name = entry.name();
		int dot = name.lastIndexOf('.');
		return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
	}

	private void reverseEntriesPreservingParent(List<LocalFilePanelModel.Entry> entries) {
		if (entries.size() <= 1) {
			return;
		}
		int startIndex = !entries.isEmpty() && entries.get(0).parent() ? 1 : 0;
		for (int left = startIndex, right = entries.size() - 1; left < right; left++, right--) {
			LocalFilePanelModel.Entry tmp = entries.get(left);
			entries.set(left, entries.get(right));
			entries.set(right, tmp);
		}
	}

	private static boolean pathEquals(Path first, Path second) {
		return first == null ? second == null : first.equals(second);
	}

	private void showClipboardPopup(int x, int y) {
		int[] selectedRows = table.getSelectedRows();
		if (selectedRows.length == 0) return;

		List<LocalFilePanelModel.Entry> entries = new ArrayList<>();
		for (int row : selectedRows) {
			entries.add(model.getEntryAt(table.convertRowIndexToModel(row)));
		}

		List<LocalFilePanelModel.Entry> nonParent = entries.stream()
				.filter(en -> !en.parent())
				.collect(Collectors.toList());
		boolean onlyParent = nonParent.isEmpty();
		boolean singleFile = nonParent.size() == 1 && !nonParent.get(0).directory();

		JPopupMenu popup = new JPopupMenu();

		JMenuItem copyNames = new JMenuItem("Copy name(s)");
		copyNames.addActionListener(ev -> {
			List<String> names = entries.stream().map(en -> {
				if (en.parent()) {
					Path dir = currentDirectory;
					return (dir != null && dir.getFileName() != null) ? dir.getFileName().toString() : null;
				}
				return en.name();
			}).filter(n -> n != null).collect(Collectors.toList());
			setClipboardText(String.join("\n", names));
		});
		popup.add(copyNames);

		JMenuItem copyPaths = new JMenuItem("Copy path(s)");
		copyPaths.addActionListener(ev -> {
			List<String> paths = entries.stream().map(en -> {
				if (en.parent()) {
					return currentDirectory != null ? currentDirectory.toString() : null;
				}
				return en.path() != null ? en.path().toString() : null;
			}).filter(p -> p != null).collect(Collectors.toList());
			setClipboardText(String.join("\n", paths));
		});
		popup.add(copyPaths);

		JMenuItem copyFiles = new JMenuItem("Copy file(s)");
		copyFiles.setEnabled(!onlyParent);
		copyFiles.addActionListener(ev -> {
			List<java.io.File> fileList = nonParent.stream()
					.filter(en -> en.path() != null)
					.map(en -> en.path().toFile())
					.collect(Collectors.toList());
			setClipboardFileList(fileList);
		});
		popup.add(copyFiles);

		popup.addSeparator();

		JMenuItem copyContent = new JMenuItem("Copy content as text");
		copyContent.setEnabled(singleFile);
		copyContent.addActionListener(ev -> {
			try {
				String content = Files.readString(nonParent.get(0).path());
				setClipboardText(content);
			} catch (IOException ex) {
				log.warn("Could not read file for clipboard: {}", ex.getMessage());
				JOptionPane.showMessageDialog(LocalFilePanel.this,
						"Could not read file: " + ex.getMessage(),
						"Error", JOptionPane.ERROR_MESSAGE);
			}
		});
		popup.add(copyContent);

		popup.show(table, x, y);
	}

	private void setClipboardText(String text) {
		Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(text), null);
	}

	private void setClipboardFileList(List<java.io.File> files) {
		if (files.isEmpty()) return;
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
			@Override
			public DataFlavor[] getTransferDataFlavors() {
				return new DataFlavor[]{DataFlavor.javaFileListFlavor};
			}
			@Override
			public boolean isDataFlavorSupported(DataFlavor flavor) {
				return DataFlavor.javaFileListFlavor.equals(flavor);
			}
			@Override
			public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
				if (!DataFlavor.javaFileListFlavor.equals(flavor)) throw new UnsupportedFlavorException(flavor);
				return files;
			}
		}, null);
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
					label.setForeground(panel.colorFor(entry));
				}
			}
			return component;
		}
	}

	private record DirectoryReadResult(List<LocalFilePanelModel.Entry> entries, String errorMessage) {
	}

	private enum SortMode {
		NAME("Sort by name"),
		EXTENSION("Sort by extension"),
		MODIFIED_DATE("Sort by modified"),
		SIZE("Sort by size"),
		UNSORTED("Unsort"),
		CREATED_DATE("Sort by create"),
		ACCESS_TIME("Sort by access");

		private final String displayName;

		SortMode(String displayName) {
			this.displayName = displayName;
		}

		@Override
		public String toString() {
			return displayName;
		}
	}

}






