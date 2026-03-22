package dev.nuclr.plugin.core.panel.fs;

import java.awt.Component;
import java.awt.Desktop;
import java.awt.KeyboardFocusManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

final class LocalFileDeletionService {

	boolean delete(Component parent, List<LocalFilePanelModel.Entry> selectedEntries, boolean permanent) throws IOException {
		if (!confirmDelete(parent, selectedEntries, permanent)) {
			return false;
		}
		if (permanent) {
			for (LocalFilePanelModel.Entry entry : selectedEntries) {
				deleteRecursively(entry.path());
			}
			return true;
		}
		moveEntriesToTrash(selectedEntries);
		return true;
	}

	private boolean confirmDelete(Component parent, List<LocalFilePanelModel.Entry> selectedEntries, boolean permanent) {
		String operation = permanent ? "Permanent Delete" : "Safe Delete";
		String modeDescription = permanent
				? "These items will be deleted permanently."
				: "These items will be moved to the Recycle Bin as a safe delete.";
		StringBuilder message = new StringBuilder();
		message.append(modeDescription).append('\n').append('\n');
		message.append(selectedEntries.size() == 1 ? "Item:" : "Items:").append('\n');
		for (String line : summarizeEntries(selectedEntries)) {
			message.append(line).append('\n');
		}
		message.append('\n').append("Continue?");
		Object[] options = {"Yes", "No"};
		JOptionPane optionPane = new JOptionPane(
				message.toString(),
				permanent ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE,
				JOptionPane.YES_NO_OPTION,
				null,
				options,
				options[1]);
		JDialog dialog = optionPane.createDialog(parent, operation);
		JButton yesButton = findButton(dialog, "Yes");
		JButton noButton = findButton(dialog, "No");
		configureArrowKeyFocus(dialog, yesButton, noButton);
		dialog.setLocationRelativeTo(parent);
		if (noButton != null) {
			SwingUtilities.invokeLater(noButton::requestFocusInWindow);
		}
		dialog.setVisible(true);
		dialog.dispose();
		Object value = optionPane.getValue();
		return "Yes".equals(value);
	}

	private void configureArrowKeyFocus(JDialog dialog, JButton yesButton, JButton noButton) {
		if (dialog.getRootPane() == null || yesButton == null || noButton == null) {
			return;
		}
		JComponent rootPane = dialog.getRootPane();
		rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("LEFT"), "focusPreviousDeleteOption");
		rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("RIGHT"), "focusNextDeleteOption");
		rootPane.getActionMap().put("focusPreviousDeleteOption", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				moveFocusBetweenButtons(yesButton, noButton);
			}
		});
		rootPane.getActionMap().put("focusNextDeleteOption", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				moveFocusBetweenButtons(yesButton, noButton);
			}
		});
	}

	private void moveFocusBetweenButtons(JButton yesButton, JButton noButton) {
		Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (focusOwner == yesButton) {
			noButton.requestFocusInWindow();
			return;
		}
		yesButton.requestFocusInWindow();
	}

	private JButton findButton(Component component, String text) {
		if (component instanceof JButton button && text.equals(button.getText())) {
			return button;
		}
		if (component instanceof java.awt.Container container) {
			for (Component child : container.getComponents()) {
				JButton button = findButton(child, text);
				if (button != null) {
					return button;
				}
			}
		}
		return null;
	}

	private List<String> summarizeEntries(List<LocalFilePanelModel.Entry> selectedEntries) {
		List<String> lines = new ArrayList<>();
		int limit = Math.min(selectedEntries.size(), 8);
		for (int i = 0; i < limit; i++) {
			LocalFilePanelModel.Entry entry = selectedEntries.get(i);
			lines.add((entry.directory() ? "[Folder] " : "[File] ") + entry.name());
		}
		if (selectedEntries.size() > limit) {
			lines.add("... and " + (selectedEntries.size() - limit) + " more");
		}
		return lines;
	}

	private void moveEntriesToTrash(List<LocalFilePanelModel.Entry> selectedEntries) throws IOException {
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
			throw new IOException("Moving files to the Recycle Bin is not supported on this platform.");
		}
		for (LocalFilePanelModel.Entry entry : selectedEntries) {
			File file = entry.path().toFile();
			if (!Desktop.getDesktop().moveToTrash(file)) {
				throw new IOException("Could not move " + entry.name() + " to the Recycle Bin.");
			}
		}
	}

	private void deleteRecursively(Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
