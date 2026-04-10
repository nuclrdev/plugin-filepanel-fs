/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.fs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.panel.fs.copy.CopyPhase;
import dev.nuclr.plugin.core.panel.fs.copy.CopyProgressSnapshot;

/**
 * Modeless progress dialog for a running copy operation.
 *
 * <p>All public methods except {@link #setCancelAction} and {@link #show} must
 * be called from the Swing EDT.  The copy background thread drives updates via
 * {@code SwingUtilities.invokeLater(() -> dialog.update(snap))}.
 *
 * <p>Extension point: replace with a richer dialog that shows a per-file list,
 * an elapsed-time / ETA counter, or a speed graph.  The
 * {@link #update(CopyProgressSnapshot)} contract is the only integration surface.
 */
final class CopyProgressDialog {

    private static final int DIALOG_WIDTH = 520;
    private static final int PATH_MAX_CHARS = 60;

    private final JDialog dialog;
    private final JLabel phaseLabel;
    private final JLabel fromValueLabel;
    private final JLabel toValueLabel;
    private final JProgressBar fileBar;
    private final JProgressBar totalBar;
    private final JLabel statsLabel;
    private final JButton cancelButton;

    CopyProgressDialog(Component parent) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        dialog = new JDialog(owner, "Copy", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        phaseLabel     = new JLabel("Scanning files...");
        fromValueLabel = new JLabel(" ");
        toValueLabel   = new JLabel(" ");
        statsLabel     = new JLabel(" ");
        cancelButton   = new JButton("Cancel");

        fileBar  = progressBar();
        totalBar = progressBar();

        // ---- Content panel (GridBagLayout) ----------------------------------
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;

        // Row 0: phase header
        row(content, gc, 0, new Insets(0, 0, 10, 0), phaseLabel);

        // Row 1-2: From / To path labels
        addLabeledRow(content, gc, 1, "From:", fromValueLabel);
        addLabeledRow(content, gc, 2, "To:",   toValueLabel);

        // Row 3-4: per-file progress
        row(content, gc, 3, new Insets(10, 0, 2, 0), new JLabel("File:"));
        row(content, gc, 4, new Insets(0, 0, 0, 0),  fileBar);

        // Row 5-6: total progress
        row(content, gc, 5, new Insets(8, 0, 2, 0),  new JLabel("Total:"));
        row(content, gc, 6, new Insets(0, 0, 0, 0),  totalBar);

        // Row 7: stats (N of M files · X of Y bytes)
        row(content, gc, 7, new Insets(8, 0, 0, 0), statsLabel);

        // ---- Button panel ---------------------------------------------------
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));
        buttons.add(cancelButton, BorderLayout.EAST);

        // ---- Root -----------------------------------------------------------
        JPanel root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(buttons,  BorderLayout.SOUTH);

        bindEscapeToCancel();

        dialog.setContentPane(root);
        dialog.setMinimumSize(new Dimension(DIALOG_WIDTH, 230));
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(parent);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Update all widgets to reflect {@code snap}.
     * Must be called on the EDT.
     */
    void update(CopyProgressSnapshot snap) {
        if (snap.phase() == CopyPhase.SCANNING) {
            phaseLabel.setText("Scanning files...");
            fileBar.setIndeterminate(true);
            totalBar.setIndeterminate(true);
            return;
        }

        fileBar.setIndeterminate(false);
        totalBar.setIndeterminate(false);
        phaseLabel.setText("Copying...");

        if (snap.currentSource() != null) {
            fromValueLabel.setText(truncatePath(snap.currentSource(), PATH_MAX_CHARS));
            toValueLabel.setText(truncatePath(snap.currentTarget(),   PATH_MAX_CHARS));
        }

        fileBar.setValue(snap.currentFilePercent());
        fileBar.setString(snap.currentFilePercent() + "%");

        totalBar.setValue(snap.totalPercent());
        totalBar.setString(snap.totalPercent() + "%");

        statsLabel.setText(
                snap.filesCopied() + " of " + snap.totalFiles() + " files  \u00b7  "
                + fmtBytes(snap.totalBytesCopied()) + " of " + fmtBytes(snap.totalBytes()));
    }

    /**
     * Wire the Cancel button.  The {@code action} is called on the EDT when
     * the button is clicked or Escape is pressed.
     *
     * <p>The button is disabled for the duration of the action to prevent
     * double-clicks, then re-enabled when the action returns.
     */
    void setCancelAction(Runnable action) {
        cancelButton.addActionListener(e -> {
            cancelButton.setEnabled(false);
            action.run();
            // Re-enable in case the user said "No" to the cancel confirmation.
            // If copy was actually cancelled, onComplete() will close this dialog shortly.
            cancelButton.setEnabled(true);
        });
    }

    /** Show the dialog and give focus to the Cancel button. */
    void show() {
        dialog.setVisible(true);
        SwingUtilities.invokeLater(cancelButton::requestFocusInWindow);
    }

    /** Hide and dispose the dialog. Safe to call even if never shown. */
    void close() {
        dialog.setVisible(false);
        dialog.dispose();
    }

    // -------------------------------------------------------------------------
    // Layout helpers
    // -------------------------------------------------------------------------

    private static JProgressBar progressBar() {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setPreferredSize(new Dimension(DIALOG_WIDTH - 32, 20));
        return bar;
    }

    /** Add a full-width row at the given grid y position. */
    private static void row(JPanel panel, GridBagConstraints gc, int y, Insets insets, Component comp) {
        gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2; gc.insets = insets;
        panel.add(comp, gc);
    }

    /** Add a "Label: value" two-column row. */
    private static void addLabeledRow(JPanel panel, GridBagConstraints gc, int y, String title, JLabel value) {
        gc.gridy = y; gc.insets = new Insets(1, 0, 1, 6);

        gc.gridx = 0; gc.gridwidth = 1; gc.weightx = 0;
        panel.add(new JLabel(title), gc);

        gc.gridx = 1; gc.gridwidth = 1; gc.weightx = 1.0;
        panel.add(value, gc);
    }

    /** Bind Escape key to the Cancel button action. */
    private void bindEscapeToCancel() {
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelCopy");
        dialog.getRootPane().getActionMap().put("cancelCopy", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancelButton.doClick();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Formatting utilities
    // -------------------------------------------------------------------------

    private static String fmtBytes(long bytes) {
        if (bytes < 1_024L)          return bytes + " B";
        if (bytes < 1_048_576L)      return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L)  return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    /** Truncate a path string to at most {@code maxChars}, prepending "…" if needed. */
    private static String truncatePath(Path p, int maxChars) {
        if (p == null) return " ";
        String s = p.toString();
        if (s.length() <= maxChars) return s;
        return "\u2026" + s.substring(s.length() - maxChars + 1);
    }
}
