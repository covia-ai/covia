package covia.venue;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import covia.venue.server.VenueServer;

/**
 * A system-tray presence for a windowless venue process: one icon per venue
 * with an Open / Close / Exit menu, so someone who started a venue from a
 * launcher can see it's running, jump to its status page, and stop it cleanly
 * without hunting for a process. Hovering shows the venue name, port and DID.
 *
 * <p>Strictly best-effort: on a headless JVM (Docker, CI, servers), an
 * unsupported desktop (some Linux DEs), or under {@code COVIA_NO_TRAY=1},
 * {@link #install} quietly installs nothing and the venue runs exactly as
 * before. A tray failure must never take a venue down.
 *
 * <p>"Close Venue" shuts down that one venue; when the last venue in the
 * process closes, the JVM exits (the AWT thread is non-daemon and would
 * otherwise keep a dead server process alive). "Exit" closes every venue in
 * the process. Both run the full {@link VenueServer#close()} flush, so state
 * is persisted exactly as on a normal shutdown.
 *
 * <p>On macOS the {@code apple.awt.UIElement} property is set before AWT
 * initialises, so the tray icon never drags a Dock icon along with it.
 */
public final class Tray {

	private static final Logger log = LoggerFactory.getLogger(Tray.class);

	/** Covia primary purple (brand colour, see covia-docs custom.css). */
	private static final Color COVIA_PURPLE = new Color(0x6B, 0x46, 0xC1);

	/** Windows caps tray tooltips at 127 chars; clamp so long names degrade gracefully. */
	private static final int TOOLTIP_MAX = 127;

	private Tray() {
	}

	/**
	 * Add a tray icon for each venue, with Open / Close / Exit menu items and a
	 * name + port + DID tooltip. Returns how many icons were actually installed
	 * (0 on headless/unsupported desktops or under {@code COVIA_NO_TRAY=1}).
	 */
	public static int install(List<VenueServer> servers) {
		// Must be set before any AWT class initialises: menu-bar-less on macOS.
		System.setProperty("apple.awt.UIElement", "true");
		return install(servers, GraphicsEnvironment.isHeadless());
	}

	/** The guard seam: tests pass {@code headless} explicitly so they never touch
	 *  the real desktop (a suite must not add icons to the developer's tray). */
	static int install(List<VenueServer> servers, boolean headless) {
		if ("1".equals(System.getenv("COVIA_NO_TRAY"))) return 0;
		try {
			if (headless || !SystemTray.isSupported()) {
				log.info("System tray not available — running without a tray icon");
				return 0;
			}
			// remaining counts live icons for the last-one-out exit; bumped as each
			// icon lands (not after the EDT block) so an immediate close can't race it.
			AtomicInteger remaining = new AtomicInteger(0);
			AtomicInteger installed = new AtomicInteger(0);
			EventQueue.invokeAndWait(() -> {
				for (VenueServer server : servers) {
					try {
						installIcon(server, servers, remaining);
						remaining.incrementAndGet();
						installed.incrementAndGet();
					} catch (Exception e) {
						log.warn("Could not install a tray icon: {}", e.toString());
					}
				}
			});
			if (installed.get() > 0) log.info("Tray icon installed for {} venue(s)", installed.get());
			return installed.get();
		} catch (Throwable t) {
			// AWT can fail in exotic ways (missing native libs, weird DEs) — never fatal.
			log.warn("System tray unavailable: {}", t.toString());
			return 0;
		}
	}

	/** Build and add one venue's icon. Runs on the EDT. */
	private static void installIcon(VenueServer server, List<VenueServer> all, AtomicInteger remaining) throws Exception {
		String name = venueName(server);
		String url = "http://127.0.0.1:" + server.port() + "/";

		PopupMenu menu = new PopupMenu();
		MenuItem open = new MenuItem("Open Venue");
		open.addActionListener(e -> openBrowser(url));
		menu.add(open);
		menu.addSeparator();

		// The icon reference is needed inside the close action; hold it in an array
		// so the lambda can capture it before the icon is constructed below.
		TrayIcon[] iconRef = new TrayIcon[1];

		MenuItem close = new MenuItem("Close Venue");
		close.addActionListener(e -> {
			log.info("Close requested from the tray menu for venue: {}", name);
			// Off the EDT: closing the venue joins server threads.
			Thread t = new Thread(() -> {
				try {
					server.close();
				} catch (Exception ex) {
					log.warn("Venue close from tray: {}", ex.toString());
				} finally {
					EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(iconRef[0]));
					// Last venue gone: exit, or the non-daemon AWT thread keeps
					// a dead server process alive.
					if (remaining.decrementAndGet() <= 0) System.exit(0);
				}
			}, "covia-tray-close");
			t.setDaemon(true);
			t.start();
		});
		menu.add(close);

		MenuItem exit = new MenuItem(all.size() > 1 ? "Exit (all venues)" : "Exit");
		exit.addActionListener(e -> {
			log.info("Exit requested from the tray menu");
			Thread t = new Thread(() -> {
				for (VenueServer s : all) {
					try {
						s.close(); // idempotent — already-closed venues no-op
					} catch (Exception ex) {
						log.warn("Venue close on exit from tray: {}", ex.toString());
					}
				}
				System.exit(0);
			}, "covia-tray-exit");
			t.setDaemon(true);
			t.start();
		});
		menu.add(exit);

		// Pre-scale to the tray's exact size with smooth interpolation —
		// TrayIcon's own auto-size scaling is crude and muddies the glyph.
		Image img = drawIcon();
		java.awt.Dimension sz = SystemTray.getSystemTray().getTrayIconSize();
		TrayIcon icon;
		String tooltip = tooltip(server, name);
		if (sz != null && sz.width > 0) {
			icon = new TrayIcon(img.getScaledInstance(sz.width, sz.height, Image.SCALE_SMOOTH), tooltip, menu);
		} else {
			icon = new TrayIcon(img, tooltip, menu);
			icon.setImageAutoSize(true);
		}
		iconRef[0] = icon;
		// The tray ACTION (double-click on Windows, Enter when focused)
		// opens the venue too — the "just get me back to it" gesture.
		icon.addActionListener(e -> openBrowser(url));
		SystemTray.getSystemTray().add(icon);
	}

	private static String venueName(VenueServer server) {
		try {
			Object name = server.getEngine().getName();
			if (name != null && !name.toString().isBlank()) return name.toString();
		} catch (Exception e) {
			// fall through to the generic label
		}
		return "Covia Venue";
	}

	/** Hover text: name, port, DID — clamped to the platform tooltip limit. */
	static String tooltip(VenueServer server, String name) {
		StringBuilder sb = new StringBuilder(name);
		sb.append("\nCovia Venue on port ").append(server.port());
		try {
			Object did = server.getEngine().getDIDString();
			if (did != null) sb.append('\n').append(did);
		} catch (Exception e) {
			// DID is decoration; the tooltip works without it
		}
		String tip = sb.toString();
		return (tip.length() <= TOOLTIP_MAX) ? tip : tip.substring(0, TOOLTIP_MAX - 1) + "…";
	}

	/** Launch the default browser at {@code url} — best-effort, off the EDT (the
	 *  browse call can shell out, e.g. xdg-open). A desktop with a tray but no
	 *  BROWSE support just logs; same contract as the rest of this class. */
	static void openBrowser(String url) {
		Thread t = new Thread(() -> {
			try {
				if (java.awt.Desktop.isDesktopSupported()
						&& java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
					java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
					log.info("Opened {} from the tray", url);
				} else {
					log.warn("Cannot open a browser on this desktop — the venue is at {}", url);
				}
			} catch (Exception e) {
				log.warn("Could not open {}: {}", url, e.toString());
			}
		}, "covia-tray-open");
		t.setDaemon(true);
		t.start();
	}

	/** The Covia mark: a white "C" on the brand-purple disc, painted at 64px and
	 *  scaled down to the tray size. Painted rather than shipped as a binary —
	 *  offscreen drawing is headless-safe and keeps the jar image-free. */
	static Image drawIcon() {
		int s = 64;
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(COVIA_PURPLE);
		g.fillOval(2, 2, s - 4, s - 4);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
		FontMetrics fm = g.getFontMetrics();
		String glyph = "C";
		int x = (s - fm.stringWidth(glyph)) / 2;
		int y = (s - fm.getHeight()) / 2 + fm.getAscent();
		g.drawString(glyph, x, y);
		g.dispose();
		return img;
	}
}
