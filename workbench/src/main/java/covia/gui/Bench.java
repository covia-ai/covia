package covia.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import covia.client.Covia;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Strings;
import convex.core.util.JSONUtils;
import java.net.URI;
import convex.core.lang.RT;
import covia.venue.VenueServer;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Scanner;

public class Bench {

	public static void main(String[] args) {
		
		LAF.init();
		// Start Covia VenueServer on port 8089 in a background thread
		new Thread(() -> {
			VenueServer server = VenueServer.create(null);
			server.start(8089);
			// Upload asset after server starts
			try {
				Thread.sleep(1000); // Give server a moment to start
				InputStream is = Bench.class.getClassLoader().getResourceAsStream("ollamaop.json");
				if (is != null) {
					String json = new Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A").next();
					Covia coviaUpload = Covia.create(java.net.URI.create("http://localhost:8089"));
					((java.util.concurrent.CompletableFuture<convex.core.Result>) coviaUpload.addAsset(json)).whenComplete((result, ex) -> {
						if (ex != null) {
							System.err.println("Asset upload failed: " + ex.getMessage());
						} else {
							System.out.println("Asset uploaded: " + (result != null ? result.getValue() : "null"));
						}
					});
				} else {
					System.err.println("Could not find ollamaop.json in resources");
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, "CoviaVenueServer").start();
		Covia covia = Covia.create(java.net.URI.create("http://localhost:8089"));
		showMainFrame(new ReplPanel(covia));
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
