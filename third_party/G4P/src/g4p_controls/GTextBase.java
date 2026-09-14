/*
  Part of the G4P library for Processing 
  	http://www.lagers.org.uk/g4p/index.html
	http://sourceforge.net/projects/g4p/files/?source=navbar

  Copyright (c) 2013 Peter Lager

  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General
  Public License along with this library; if not, write to the
  Free Software Foundation, Inc., 59 Temple Place, Suite 330,
  Boston, MA  02111-1307  USA
 */

package g4p_controls;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.util.LinkedList;

import g4p_controls.StyledString.TextLayoutInfo;
import processing.core.PApplet;

/**
 * Base class for any control that uses styled text.
 * 
 * @author Peter Lager
 *
 */
public abstract class GTextBase extends GAbstractControl {

	protected float PAD = 2;

	protected Zone textZone = new Zone();
	// Alignment within zone
	protected GAlign textAlignH = GAlign.LEFT;
	protected GAlign textAlignV = GAlign.MIDDLE;

	/** The styled text used by this control */
	protected StyledString stext = new StyledString("");

	protected Font localFont = G4P.displayFont;

	/**
	 * Constructor
	 * 
	 * @param theApplet the main sketch or GWindow control for this control
	 * @param p0        x position based on control mode
	 * @param p1        y position based on control mode
	 * @param p2        x position or width based on control mode
	 * @param p3        y position or height based on control mode
	 */
	public GTextBase(PApplet theApplet, float p0, float p1, float p2, float p3) {
		super(theApplet, p0, p1, p2, p3);
		makeBuffer();
	}

	/**
	 * Set the text to be displayed.
	 * 
	 * @param text the text to display
	 */
	public void setText(String text) {
		setText(text, null, null);
	}

	/**
	 * Combines setting the text and text alignment in one method. <br>
	 * 
	 * If you want to set just one of the alignments then pass null in the other.
	 * 
	 * @param text the text to display
	 * @param horz GAlign.LEFT, CENTER, RIGHT or JUSTIFY
	 * @param vert GAlign.TOP, MIDDLE, BOTTOM
	 */
	public void setText(String text, GAlign horz, GAlign vert) {
		text = text == null || text.length() == 0 ? " " : text;
		stext = new StyledString(text, (int) textZone.w);
		setTextAlign(horz, vert);
		bufferInvalid = true;
	}

	protected void calcTextZone() {
		textZone.x = PAD;
		textZone.y = PAD;
		textZone.w = width - 2 * PAD;
		textZone.h = height - 2 * PAD;
		stext.setWrapWidth((int) textZone.w);
	}

	/**
	 * Set the horizontal and/or vertical text alignment. Use the constants in
	 * GAlign e.g. <b>GAlign.LEFT</b> <br>
	 * 
	 * If you want to set just one of these then pass null in the other
	 * 
	 * @param horz GAlign.LEFT, CENTER, RIGHT or JUSTIFY
	 * @param vert GAlign.TOP, MIDDLE, BOTTOM
	 */
	public void setTextAlign(GAlign horz, GAlign vert) {
		if (horz != null && horz.isHorzAlign()) {
			textAlignH = horz;
			stext.setJustify(textAlignH == GAlign.JUSTIFY);
		}
		if (vert != null && vert.isVertAlign()) {
			textAlignV = vert;
		}
		bufferInvalid = true;
	}

	/**
	 * Default implementation for displaying the control's text. Child classes may
	 * override this if needed.
	 */
	protected void displayText(Graphics2D g2d, LinkedList<TextLayoutInfo> lines, Color fore) {
		float sx = 0, tw = 0;
		// Get vertical position of text start based on alignment
		float textY;
		switch (textAlignV) {
		case TOP:
			textY = 0;
			break;
		case BOTTOM:
			textY = textZone.h - stext.getTextAreaHeight();
			break;
		case MIDDLE:
		default:
			textY = (textZone.h - stext.getTextAreaHeight()) / 2;
		}
//		System.out.println("####  "+tag);
//		System.out.println("stext wrap width:      " + stext.getWrapWidth());
//		System.out.println("stext area height:     " + stext.getTextAreaHeight());
//		System.out.println("stext max line length: " + stext.getMaxLineLength());
//		System.out.println("Text zone: " + textZone.toString());
		// Now translate to text start position
		buffer.translate(textZone.x, textZone.y + textY);
		// Now display each line
		for (TextLayoutInfo lineInfo : lines) {
			TextLayout layout = lineInfo.layout;
			buffer.translate(0, layout.getAscent());
			switch (textAlignH) {
			case CENTER:
				tw = layout.getVisibleAdvance();
				while (tw > textZone.w)
					tw -= textZone.w;
				// tw = (tw > textZone.w) ? tw - textZone.w : tw;
				sx = (textZone.w - tw) / 2;
				break;
			case RIGHT:
				tw = layout.getVisibleAdvance();
				while (tw > textZone.w)
					tw -= textZone.w;
				// tw = (tw > textZone.w) ? tw - textZone.w : tw;
				sx = textZone.w - tw;
				break;
			case LEFT:
			case JUSTIFY:
			default:
				tw = layout.getVisibleAdvance();
				tw = (tw > textZone.w) ? tw - textZone.w : tw;
				sx = 0;
			}
			// display text
			g2d.setColor(fore);
			layout.draw(g2d, sx, 0);
			buffer.translate(0, layout.getDescent() + layout.getLeading());
		}
	}

	protected void displayText(Graphics2D g2d, LinkedList<TextLayoutInfo> lines) {
		displayText(g2d, lines, palette[2]);
	}

	/**
	 * Load the styled string to be used by this control.
	 * 
	 * @param fname the name of the file to use
	 * @return true if loaded successfully else false
	 */
	public boolean loadText(String fname) {
		StyledString ss = StyledString.load(winApp, fname);
		if (ss != null) {
			setStyledText(ss);
			stext.startIdx = stext.endIdx = -1;
			bufferInvalid = true;
			return true;
		}
		return false;
	}

	/**
	 * Save the styled text used by this control to file.
	 * 
	 * @param fname the name of the file to use
	 * @return true if saved successfully else false
	 */
	public boolean saveText(String fname) {
		stext.startIdx = stext.endIdx = -1;
		StyledString.save(winApp, stext, fname);
		return true;
	}

	/**
	 * Set the font to be used in this control
	 * 
	 * @param font AWT font to use
	 */
	public void setFont(Font font) {
		if (font != null && localFont != font) {
			localFont = font;
			bufferInvalid = true;
		}
	}

	/**
	 * Allows the user to provide their own styled text for this component
	 * 
	 * @param ss the styled string to display
	 */
	public void setStyledText(StyledString ss) {
		if (ss != null) {
			stext = ss;
			stext.setWrapWidth((int) width - TPAD4);
			bufferInvalid = true;
		}
	}

	/**
	 * See comments in GAbstractControl class
	 */
	public void forceBufferUpdate() {
		if (stext != null)
			stext.invalidateText();
		bufferInvalid = true;
	}

	/**
	 * Clear <b>all</b> applied styles from the whole text.
	 */
	public void setTextPlain() {
		stext.clearAttributes();
		bufferInvalid = true;
	}

	/**
	 * Make the selected characters bold. <br>
	 * Characters affected are &ge; start and &lt; end
	 * 
	 * @param start the first character to style
	 * @param end   the first character not to style
	 */
	public void setTextBold(int start, int end) {
		addAttributeImpl(G4P.WEIGHT, G4P.WEIGHT_BOLD, start, end);
	}

	/**
	 * Make all the characters bold.
	 */
	public void setTextBold() {
		addAttributeImpl(G4P.WEIGHT, G4P.WEIGHT_BOLD);
	}

	/**
	 * Make the selected characters italic. <br>
	 * Characters affected are &ge; start and &lt; end
	 * 
	 * @param start the first character to style
	 * @param end   the first character not to style
	 */
	public void setTextItalic(int start, int end) {
		addAttributeImpl(G4P.POSTURE, G4P.POSTURE_OBLIQUE, start, end);
	}

	/**
	 * Make all the characters italic.
	 */
	public void setTextItalic() {
		addAttributeImpl(G4P.POSTURE, G4P.POSTURE_OBLIQUE);
	}

	/**
	 * Get the text used for this control.
	 * 
	 * @return the displayed text with styling
	 */
	public StyledString getStyledText() {
		return stext;
	}

	/**
	 * Get the text used for this control.
	 * 
	 * @return the displayed text without styling
	 */
	public String getText() {
		return stext.getPlainText();
	}

	/**
	 * Apply the style to the whole text
	 * 
	 * @param style the style attribute
	 * @param value 'amount' to apply
	 */
	protected void addAttributeImpl(TextAttribute style, Object value) {
		stext.addAttribute(style, value);
		bufferInvalid = true;
	}

	/**
	 * Apply the style to a portion of the string
	 * 
	 * @param style the style attribute
	 * @param value 'amount' to apply
	 * @param s     first character to be included for styling
	 * @param e     the first character not to be included for styling
	 */
	protected void addAttributeImpl(TextAttribute style, Object value, int s, int e) {
		if (s >= e)
			return;
		if (s < 0)
			s = 0;
		if (e > stext.length())
			e = stext.length();
		stext.addAttribute(style, value, s, e);
		bufferInvalid = true;
	}

	/**
	 * Resize the control so it just fits the text. This will also change the buffer
	 * size using global control mode.
	 */
	protected void resizeToFit() {
		 resizeToFit(G4P.control_mode);
	}
	
	/**
	 * Resize the control so it just fits the text. This will also change the buffer
	 * size using specified control mode
	 * 
	 * @param mode control mode CORNER, CORNERS or CENTER
	 */
	protected void resizeToFit(GControlMode mode) {
		// Update buffer to ensure we have the latest values for text size etc.
		forceBufferUpdate();
		updateBuffer();
		int high = Math.max(10, Math.round(stext.getTextAreaHeight() + 2 * PAD));
		int wide = Math.max(10, Math.round(stext.getMaxLineLength() + 4 * PAD));
		resize(wide, high, mode); // resize this control
		calcTextZone();
		stext = new StyledString(stext.getPlainText(), (int) textZone.w);
	}

	/**
	 * Simple class to used to define text and icon zones in this type of control
	 * 
	 * @author Peter Lager
	 *
	 */
	class Zone {

		public float x, y, w, h;

		public Zone() {
			x = y = w = h = 0;
		}

		public Zone(float x, float y, float w, float h) {
			super();
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}

		public void clear() {
			x = y = w = h = 0;
		}

		public String toString() {
			return "Zone " + x + "  " + y + "  " + w + "  " + h;
		}

	}
}
