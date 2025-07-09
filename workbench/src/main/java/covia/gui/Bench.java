package covia.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import covia.client.Covia;
import covia.venue.Venue;
import covia.venue.server.VenueServer;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Strings;
import convex.core.util.JSONUtils;
import java.net.URI;
import convex.core.lang.RT;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Scanner;

public class Bench {

	public static void main(String[] args) {
		
		LAF.init();
		// Start Covia VenueServer on port 8089 in a background thread
		Covia covia = Covia.create(java.net.URI.create("http://localhost:8089"));
		ReplPanel replPanel = new ReplPanel(covia);
		showMainFrame(replPanel);
		// Pass replPanel to the upload thread so we can set the operation ID after upload
		new Thread(() -> {
			VenueServer server = VenueServer.create(null);
			Venue.addDemoAssets(server.getVenue());
			server.start(8089);
			try {
				Thread.sleep(1000); // Give server a moment to start
				InputStream is = Bench.class.getClassLoader().getResourceAsStream("ollamaop.json");
				if (is != null) {
					String json = new Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A").next();
					((java.util.concurrent.CompletableFuture<convex.core.Result>) covia.addAsset(json)).whenComplete((result, ex) -> {
						if (ex != null) {
							System.err.println("Asset upload failed: " + ex.getMessage());
						} else {
							System.out.println("Asset uploaded: " + (result != null ? result.getValue() : "null"));
							// Set the operation ID in the REPL panel if possible
							if (result != null && result.getValue() != null) {
								String opId = result.getValue().toString();
								javax.swing.SwingUtilities.invokeLater(() -> replPanel.setOperationId(opId));
							}
						}
					});
				} else {
					System.err.println("Could not find ollamaop.json in resources");
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, "CoviaVenueServer").start();
	}

	public static void showMainFrame(JComponent comp) {
		JFrame frame = new JFrame("Workbench REPL");
		frame.getContentPane().add(comp);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(600, 400);
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}
}
