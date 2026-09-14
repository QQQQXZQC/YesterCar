/*
  Part of the G4P library for Processing 
  	http://www.lagers.org.uk/g4p/index.html
	http://sourceforge.net/projects/g4p/files/?source=navbar

  Copyright (c) 2016 Peter Lager

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

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.util.LinkedList;

import g4p_controls.StyledString.TextLayoutInfo;
import processing.core.PApplet;

/**
 * The tool tip component. <br/>
 * 
 * Most G4P controls can have a tool tip which appears when the mouse enters the
 * control and disappears after a set time or when the mouse exits the control
 * whichever comes first.
 * <br/>
 * 
 * Tool tips cannot be added to the following types of control. <br/>
 * GControlPalette, GDropList, GLabel, GPanel GPassword, GScrollbar and GToolTip
 * <br/>
 * 
 * To create a tool tip for a particular control use one of the
 * <code> setTip(String, ...)</code> methods. This will satisfy the needs of
 * most users.<br/>
 * 
 * 
 * @author Peter Lager
 *
 */
public class GToolTip extends GTextBase {

	protected long showtime = G4P.defaultToolTipShowTime;
	protected boolean keepHorizontal = true;

	protected long tiptime = 0; // time when the tip becomes visible

	/**
	 * Create an empty tool tip use setText to change the text.
	 * 
	 * @param theApplet the main sketch or GWindow control for this control
	 * @param p0        x position based on control mode
	 * @param p1        y position based on control mode
	 * @param p2        x position or width based on control mode
	 * @param p3        y position or height based on control mode
	 */
	public GToolTip(PApplet theApplet, float p0, float p1, float p2, float p3) {
		this(theApplet, p0, p1, p2, p3, "       ");
	}

	/**
	 * Create a tool tip control.
	 * 
	 * @param theApplet the main sketch or GWindow control for this control
	 * @param p0        x position based on control mode
	 * @param p1        y position based on control mode
	 * @param p2        x position or width based on control mode
	 * @param p3        y position or height based on control mode
	 * @param text      the initial text to display
	 */
	public GToolTip(PApplet theApplet, float p0, float p1, float p2, float p3, String text) {
		super(theApplet, p0, p1, p2, p3);
		opaque = true;
		PAD = 5;
		textAlignH = GAlign.CENTER;
		// setTextBold();
		visible = false;
		tiptime = 0;
		// Font to use
		localFont = G4P.tooltipFont;

		calcTextZone();
		setText(text);
		resizeToFit();
		bufferInvalid = true;

		allowToolTips = false;
		allowChildren = false;

		registeredMethods = DRAW_METHOD;

		// Must register control
		G4P.registerControl(this);
	}

	public boolean isFixedHorz() {
		return keepHorizontal;
	}
	
	/**
	 * Set the tool tip text resizing this control to just fit the text.
	 * 
	 * @param text the text to display
	 */
	public void setText(String text) {
		setText(text, null, null);
		resizeToFit(GControlMode.CENTER);
		bufferInvalid = true;
	}

	/**
	 * Prevent user from directly setting the rotation angle for this tool tip
	 */
	public void setRotation(float angle, GControlMode mode) {
		System.err.println("Cannot directly set the rotation for GToolTip");
	}

	public void draw() {
		if (!visible || !enabled) 
			return;
		// update tooltip V4.3.9
		if (System.currentTimeMillis() - tiptime > showtime) {
			visible = false;
			tiptime = 0;
		}
		// Update buffer if invalid
		updateBuffer();

		winApp.push();
		// Perform the rotation
		winApp.translate(cx, cy);
		//System.out.println(getCX() + "   " + getCY());
		if(keepHorizontal)
			winApp.rotate(-parent.rotAngle);
		// Move matrix to line up with top-left corner
		winApp.translate(-halfWidth, -halfHeight);
		// Draw buffer
		winApp.imageMode(PApplet.CORNER);
		if (alphaLevel < 255)
			winApp.tint(TINT_FOR_ALPHA, alphaLevel);
		winApp.image(buffer, 0, 0);
		winApp.pop();
	}

	protected void updateBuffer() {
		if (bufferInvalid) {
			bufferInvalid = false;
			buffer.beginDraw();
			buffer.clear();
			Graphics2D g2d = buffer.g2;
			g2d.setFont(localFont);
			// Get the latest lines of text
			LinkedList<TextLayoutInfo> lines = stext.getLines(g2d);
			g2d.setColor(palette[2]);
			g2d.fillRoundRect(1, 1, buffer.width - 3, buffer.height - 3, 6, 6);
			g2d.setColor(palette[5]);
			g2d.setStroke(new BasicStroke(4));
			g2d.drawRoundRect(1, 1, buffer.width - 4, buffer.height - 4, 6, 6);
			// Now draw the text
			displayText(g2d, lines, palette[10]);
			buffer.endDraw();
		}
	}

};