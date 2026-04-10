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
package dev.nuclr.plugin.core.panel.fs.plugin;

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

/**
 * Modeless progress dialog for a running delete operation.
 *
 * <p>Methods may be called from any thread — they dispatch to the EDT internally.
 * Updates are throttled to at most one repaint every {@value #UPDATE_INTERVAL_MS} ms
 * to avoid flooding the event queue when deleting thousands of small files.
 */
final class DeletionProgressDialog {

    private static final int DIALOG_WIDTH  = 460;
    private static final int PATH_MAX_CHARS = 55;
    /** Minimum interval between visual updates from the background thread. */
    private static final long UPDATE_INTERVAL_MS = 50;

    private final JDialog dialog;
    private final JLabel  phaseLabel;
    private final JLabel  itemLabel;
    private final JProgressBar progressBar;
    private final JLabel  countLabel;
    private final JButton cancelButton;

    private volatile long lastUpdateMs = 0;

    DeletionProgressDialog(Component parent, boolean permanent) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        dialog = new JDialog(owner,
                permanent ? "Permanent Delete" : "Safe Delete",
                Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        phaseLabel   = new JLabel(permanent ? "Scanning..." : "Moving to Trash...");
        itemLabel    = new JLabel(" ");
        progressBar  = new JProgressBar(0, 100);
        countLabel   = new JLabel(" ");
        cancelButton = new JButton("Cancel");

        progressBar.setStringPainted(true);
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(DIALOG_WIDTH - 32, 20));

        // ---- Layout ---------------------------------------------------------
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridx = 0;

        gc.gridy = 0; gc.insets = new Insets(0, 0, 10, 0);
        content.add(phaseLabel, gc);

        gc.gridy = 1; gc.insets = new Insets(0, 0, 2, 0);
        content.add(itemLabel, gc);

        gc.gridy = 2; gc.insets = new Insets(6, 0, 0, 0);
        content.add(progressBar, gc);

        gc.gridy = 3; gc.insets = new Insets(6, 0, 0, 0);
        content.add(countLabel, gc);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));
        buttons.add(cancelButton, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(buttons,  BorderLayout.SOUTH);

        bindEscape();
        dialog.setContentPane(root);
        dialog.setMinimumSize(new Dimension(DIALOG_WIDTH, 180));
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(parent);
    }

    // -------------------------------------------------------------------------
    // State transitions (thread-safe)
    // -------------------------------------------------------------------------

    /** Switch to the active-deletion state with a determinate progress bar. */
    void startDeleting(String phaseText, int total) {
        SwingUtilities.invokeLater(() -> {
            phaseLabel.setText(phaseText);
            progressBar.setIndeterminate(false);
            progressBar.setMaximum(Math.max(total, 1));
            progressBar.setValue(0);
            progressBar.setString("0%");
            countLabel.setText("0 of " + total + " items");
        });
    }

    /**
     * Update progress.  Throttled — excess calls from tight loops are dropped.
     * The final update (done == total) is always dispatched.
     *
     * @param currentItem path of the item currently being processed
     * @param done        number of items processed so far
     * @param total       total items counted during pre-scan
     */
    void update(Path currentItem, int done, int total) {
        long now = System.currentTimeMillis();
        boolean isLast = done >= total;
        if (!isLast && now - lastUpdateMs < UPDATE_INTERVAL_MS) {
            return;
        }
        lastUpdateMs = now;
        int pct = total > 0 ? Math.min(100, done * 100 / total) : 100;
        String itemText = currentItem != null ? truncate(currentItem, PATH_MAX_CHARS) : " ";
        SwingUtilities.invokeLater(() -> {
            itemLabel.setText(itemText);
            progressBar.setValue(done);
            progressBar.setString(pct + "%");
            countLabel.setText(done + " of " + total + " items");
        });
    }

    // -------------------------------------------------------------------------
    // Wiring
    // -------------------------------------------------------------------------

    /**
     * Register the action executed when Cancel is clicked or Escape pressed.
     * The button is disabled while {@code action} runs to prevent double-clicks.
     */
    void setCancelAction(Runnable action) {
        cancelButton.addActionListener(e -> {
            cancelButton.setEnabled(false);
            action.run();
            if (dialog.isVisible()) {
                cancelButton.setEnabled(true);
            }
        });
    }

    void show() {
        dialog.setVisible(true);
        SwingUtilities.invokeLater(cancelButton::requestFocusInWindow);
    }

    void close() {
        dialog.setVisible(false);
        dialog.dispose();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void bindEscape() {
        dialog.getRootPane()
              .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
              .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelDelete");
        dialog.getRootPane().getActionMap().put("cancelDelete", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancelButton.doClick();
            }
        });
    }

    private static String truncate(Path p, int max) {
        String s = p.toString();
        return s.length() <= max ? s : "\u2026" + s.substring(s.length() - max + 1);
    }
}
