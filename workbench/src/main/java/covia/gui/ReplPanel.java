package covia.gui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Maps;
import covia.grid.client.Covia;

public class ReplPanel extends JPanel {
    protected final Covia covia;
    private final JTextField opField;

    public ReplPanel(Covia covia) {
        super(new BorderLayout(8, 8));
        this.covia = covia;

        JTextArea outputArea = new JTextArea();
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        JScrollPane scrollPane = new JScrollPane(outputArea);

        // Operation ID entry
        JPanel opPanel = new JPanel(new BorderLayout(4, 4));
        JLabel opLabel = new JLabel("Operation ID:");
        opField = new JTextField();
        opField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        opPanel.add(opLabel, BorderLayout.WEST);
        opPanel.add(opField, BorderLayout.CENTER);

        // Input entry
        JTextField inputField = new JTextField();
        inputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        inputField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String opId = opField.getText().trim();
                String input = inputField.getText();
                outputArea.append("> op: " + opId + "\n");
                outputArea.append("> input: " + input + "\n");
                if (opId.isEmpty()) {
                    outputArea.append("Error: Operation ID is required.\n\n");
                    inputField.setText("");
                    return;
                }
                try {
                    ACell opInput = Maps.of("prompt",input);
                    
                    ((java.util.concurrent.CompletableFuture<Result>) covia.invoke(opId, opInput)).whenComplete((result, ex) -> {
                        if (ex != null) {
                            SwingUtilities.invokeLater(() -> {
                                outputArea.append("Error: " + ex.getMessage() + "\n\n");
                                outputArea.setCaretPosition(outputArea.getDocument().getLength());
                            });
                        } else {
                            SwingUtilities.invokeLater(() -> {
                                outputArea.append("Result: " + (result != null ? result.getValue() : "null") + "\n\n");
                                outputArea.setCaretPosition(outputArea.getDocument().getLength());
                            });
                        }
                    });
                } catch (Exception ex) {
                    outputArea.append("Error: " + ex.getMessage() + "\n\n");
                }
                inputField.setText("");
            }
        });

        JPanel inputPanel = new JPanel(new BorderLayout(4, 4));
        inputPanel.add(opPanel, BorderLayout.NORTH);
        inputPanel.add(inputField, BorderLayout.CENTER);

        this.add(scrollPane, BorderLayout.CENTER);
        this.add(inputPanel, BorderLayout.SOUTH);
    }

    public void setOperationId(String opId) {
        opField.setText(opId);
    }
} 