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

public class Bench {

	public static void main(String[] args) {
		
		LAF.init();
		// Start Covia VenueServer on port 8089 in a background thread
		new Thread(() -> {
			VenueServer server = VenueServer.create(null);
			server.start(8089);
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
