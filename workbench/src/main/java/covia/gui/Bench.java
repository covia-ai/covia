package covia.gui;

import javax.swing.JComponent;
import javax.swing.JFrame;

import convex.core.util.Utils;
import covia.grid.client.Covia;
import covia.venue.Venue;
import covia.venue.server.VenueServer;

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

				String json = Utils.readResourceAsString("ollamaop.json");
				covia.addAsset(json).whenComplete((result, ex) -> {
					if (ex != null) {
						System.err.println("Asset upload failed: " + ex.getMessage());
					} else {
						System.out.println("Asset uploaded: " + result);

						String opId = result.toString();
						javax.swing.SwingUtilities.invokeLater(() -> replPanel.setOperationId(opId));
					}
				});

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
