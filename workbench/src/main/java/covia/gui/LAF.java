package covia.gui;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTMaterialOceanicIJTheme;

public class LAF {

	static {
		try {
			LookAndFeel laf=installFlatLaf();
			UIManager.setLookAndFeel(laf);
			FlatMTMaterialOceanicIJTheme.setup();
		} catch (UnsupportedLookAndFeelException e) {
			e.printStackTrace();
		}

	}
	
	protected static javax.swing.LookAndFeel installFlatLaf() {
		System.setProperty("flatlaf.uiScale", "1.5");
		// System.setProperty("flatlaf.uiScale", Double.toString(SCALE));
		FlatDarculaLaf laf=new FlatDarculaLaf();
		return laf;
	}
	
	public static void init() {
		// Empty method, just triggers static initialisation
	}
}
