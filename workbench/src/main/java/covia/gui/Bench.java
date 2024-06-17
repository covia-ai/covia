package covia.gui;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;

public class Bench {

	public static void main(String[] args) {
		
		LAF.init();
		showMainFrame(new JLabel("test"));
	}

	public static void showMainFrame(JComponent comp) {
		 JFrame frame = new JFrame("Test Frame"); 
		 frame.getContentPane().add(comp);
		 frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		 frame.pack();
		 frame.setVisible(true);
	}
}
