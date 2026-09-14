/*
  Part of the G4P library for Processing 
  	http://www.lagers.org.uk/g4p/index.html
	http://sourceforge.net/projects/g4p/files/?source=navbar

  Copyright (c) 2020 Peter Lager

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

import java.awt.Graphics2D;
import java.awt.font.TextLayout;
import java.awt.geom.GeneralPath;
import java.util.LinkedList;

import g4p_controls.HotSpot.HSrect;
import g4p_controls.StyledString.TextLayoutHitInfo;
import g4p_controls.StyledString.TextLayoutInfo;
import processing.core.PApplet;
import processing.core.PGraphics;
import processing.core.PImage;
import processing.event.KeyEvent;
import processing.event.MouseEvent;

/**
 * The spinner control can be used to enter any positive or negative integer
 * value either through the keyboard, the up/down spinner buttons or the mouse
 * wheel when over the control. <br>
 * 
 * After creating the spinner the user can set the initial value to display and
 * the minimum and maximum permitted values. The user also specifies the
 * increment/decrement value to apply when using the spinner buttons or mouse
 * wheel to change the value. <br>
 * 
 * When entering numbers from the keyboard the - key can be pressed at any time
 * to negate the current value.
 * 
 * @author Peter Lager
 *
 */
public class GSpinner extends GEditableTextControl {

	private static float MIN_BUTTON_WIDTH = 16;

	private int low = -100;
	private int high = 100;
	private int value = 0;
	private int lastValue = 0;
	private int delta = 10;

	private GButton btnInc, btnDec;

	/**
	 * Create a spinner control that accepts user keyboard input. <br>
	 * The button width will be 16px
	 * 
	 * @param theApplet   the main sketch or GWindow control for this control
	 * @param p0          x position based on control mode
	 * @param p1          y position based on control mode
	 * @param p2          x position or width based on control mode
	 * @param p3          y position or height based on control mode
	 * @param buttonWidth the width of the spinner buttons
	 */
	public GSpinner(PApplet theApplet, float p0, float p1, float p2, float p3) {
		this(theApplet, p0, p1, p2, p3, MIN_BUTTON_WIDTH);
	}

	/**
	 * Create a spinner control that accepts user keyboard input. <br>
	 * The button width must be &ge; 16 px
	 * 
	 * @param theApplet   the main sketch or GWindow control for this control
	 * @param p0          x position based on control mode
	 * @param p1          y position based on control mode
	 * @param p2          x position or width based on control mode
	 * @param p3          y position or height based on control mode
	 * @param buttonWidth the width of the spinner buttons
	 */
	public GSpinner(PApplet theApplet, float p0, float p1, float p2, float p3, float buttonWidth) {
		super(theApplet, p0, p1, p2, p3);
		children = new LinkedList<GAbstractControl>();
		tx = ty = 2;
		buttonWidth = Math.max(buttonWidth, MIN_BUTTON_WIDTH);
		tw = width - buttonWidth - 2 * 2;
		th = height - 2 * 2;
		wrapWidth = Integer.MAX_VALUE;
		gpTextDisplayArea = new GeneralPath();
		gpTextDisplayArea.moveTo(0, 0);
		gpTextDisplayArea.lineTo(0, th);
		gpTextDisplayArea.lineTo(tw, th);
		gpTextDisplayArea.lineTo(tw, 0);
		gpTextDisplayArea.closePath();

		hotspots = new HotSpot[] { new HSrect(1, tx, ty, tw, th), // typing area
				new HSrect(9, 0, 0, width, height) // control surface
		};

		G4P.pushStyle();
		G4P.showMessages = false;

		z = Z_STICKY;

		G4P.control_mode = GControlMode.CORNER;

		int bw = Math.round(buttonWidth);
		int bh2 = Math.round(th / 2);
		btnInc = new GButton(theApplet, 0, 0, buttonWidth, 1 + th / 2);
		GIcon gi = makeSpinnerButtonIcon(theApplet, bw, bh2, true);
		btnInc.setIcon(gi, GAlign.NORTH, GAlign.CENTER, GAlign.CENTER);
		btnInc.addEventHandler(this, "handleInc");
		addControlImpl(btnInc, tw + 2, 1, 0);
		btnDec = new GButton(theApplet, 0, 0, buttonWidth, 1 + th / 2);
		gi = makeSpinnerButtonIcon(theApplet, bw, bh2, false);
		btnDec.setIcon(gi, GAlign.NORTH, GAlign.CENTER, GAlign.CENTER);
		btnDec.addEventHandler(this, "handleDec");
		addControlImpl(btnDec, tw + 2, 1 + th / 2, 0);

		G4P.popStyle();
		setValue(0);

		createEventHandler(G4P.sketchWindow, "handleTextEvents",
				new Class<?>[] { GEditableTextControl.class, GEvent.class }, new String[] { "textcontrol", "event" });
		registeredMethods = PRE_METHOD | DRAW_METHOD | MOUSE_METHOD | KEY_METHOD;

		// Font to use
		localFont = G4P.inputFont;
		bufferInvalid = true;

		// Must register control
		G4P.registerControl(this);
	}

	/**
	 * Make the icon for the spinner button
	 * 
	 * @param papp the main sketch or GWindow control for this control
	 * @param w    icon width
	 * @param h    icon height
	 * @param up   true if pointing up
	 * @return the GIcon created
	 */
	private GIcon makeSpinnerButtonIcon(PApplet papp, float w, float h, boolean up) {
		int[] x = new int[] { 3, 8, 12 };
		int[] y = up ? new int[] { 12, 2, 12 } : new int[] { 2, 12, 2 };
		PGraphics pg = papp.createGraphics(16, 16, JAVA2D);
		pg.beginDraw();
		pg.clear();
		pg.noStroke();
		pg.fill(0);
		pg.beginShape();
		for (int i = 0; i < 3; i++) {
			pg.vertex(x[i], y[i]);
		}
		pg.endShape();
		pg.endDraw();
		PImage pi = pg.get();
		pi.resize(Math.round(w), Math.round(h));
		return new GIcon(papp, pi, 1, 1);
	}

	/**
	 * Set the limits for this spinner control. <br>
	 * The range limits are swapped if high &lt low <br>
	 * The initial value will be constrained to the range &ge; low and &le; high.
	 * <br>
	 * The increment / decrement value will not be allowed to exceed the range i.e.
	 * high - low
	 * 
	 * @param init  the initial value to display
	 * @param low   the lowest valid value
	 * @param high  the highest valid value
	 * @param delta the inc/decrement value
	 */
	public void setLimits(int init, int low, int high, int delta) {
		this.low = Math.min(low, high);
		this.high = Math.max(low, high);
		init = PApplet.constrain(init, low, high);
		delta = Math.min(delta, high - low);
		this.delta = delta;
		this.lastValue = init;
		setValue(init);
	}

	/**
	 * Do not call this method instead use {@code dec()} <br>
	 * This method is used internally by the spinner button.
	 */
	public void handleDec(GButton button, GEvent event) {
//		loseFocus(parent);  // ticket 46
		if (event == GEvent.CLICKED)
			if (dec())
				fireEvent(this, GEvent.CHANGED);
	}

	/**
	 * Do not call this method instead use {@code inc()}
	 */
	public void handleInc(GButton button, GEvent event) {
//		loseFocus(parent);  // ticket 46
		if (event == GEvent.CLICKED)
			if (inc())
				fireEvent(this, GEvent.CHANGED);
	}

	/**
	 * Decrement the current value. It will be constrained to the current range
	 * limits
	 * 
	 * @return true if the value changes
	 */
	public boolean dec() {
		int newValue = PApplet.constrain(value - delta, low, high);
		boolean changed = newValue != value;
		setValue(newValue);
		return changed;
	}

	/**
	 * Increment the current value. It will be constrained to the current range
	 * limits
	 * 
	 * @return true if the value changes
	 */
	public boolean inc() {
		int newValue = PApplet.constrain(value + delta, low, high);
		boolean changed = newValue != value;
		setValue(newValue);
		return changed;
	}

	/**
	 * @return the current value
	 */
	public int getValue() {
		return value;
	}

	/**
	 * Sets the current value to be displayed. It will be constrained to the current
	 * range limits.
	 * 
	 * @param v the new value to display
	 */
	public void setValue(int newValue) {
		newValue = PApplet.constrain(newValue, low, high);
		if (value != newValue || stext.length() == 0) {
			lastValue = value;
			value = newValue;
			stext.setText("" + value, Integer.MAX_VALUE);
			bufferInvalid = true;
		}
	}

	/**
	 * This method is ignored for this control
	 */
	public void setStyledText(StyledString ss) {
	}

	/**
	 * This method is ignored for this control
	 */
	public void setText(String text) {
	}

	public PGraphics getSnapshot() {
		updateBuffer();
		PGraphics snap = winApp.createGraphics(buffer.width, buffer.height, PApplet.JAVA2D);
		snap.beginDraw();
		snap.image(buffer, 0, 0);
		snap.endDraw();
		return snap;
	}

	public void pre() {
		if (keepCursorInView) {
			boolean horzScroll = false;
			float max_ptx = caretX - tw + 2;
			if (endTLHI != null) {
				if (ptx > caretX) { // Scroll to the left (text moves right)
					ptx -= getScrollAmount();
					if (ptx < 0)
						ptx = 0;
					horzScroll = true;
				} else if (ptx < max_ptx) { // Scroll to the right (text moves left)?
					ptx += getScrollAmount();
					if (ptx > max_ptx)
						ptx = max_ptx;
					horzScroll = true;
				}
				// Ensure that we show as much text as possible keeping the caret in view
				// This is particularly important when deleting from the end of the text
				if (ptx > 0 && endTLHI.tli.layout.getAdvance() - ptx < tw - 2) {
					ptx = Math.max(0, endTLHI.tli.layout.getAdvance() - tw - 2);
					horzScroll = true;
				}
			}
			// If we have scrolled invalidate the buffer otherwise forget it
			if (horzScroll)
				bufferInvalid = true;
			else
				keepCursorInView = false;
		}
	}

	public void mouseEvent(MouseEvent event) {
		if (!visible || !enabled || !available)
			return;

		calcTransformedOrigin(winApp.mouseX, winApp.mouseY);
		ox -= tx;
		oy -= ty; // Remove translation

		currSpot = whichHotSpot(ox, oy);

		manageToolTip();

		if (currSpot == 1 || focusIsWith == this)
			cursorIsOver = this;
		else if (cursorIsOver == this)
			cursorIsOver = null;

		switch (event.getAction()) {
		case MouseEvent.PRESS:
			if (currSpot == 1) {
				if (focusIsWith != this && z >= focusObjectZ()) {
					keepCursorInView = true;
					takeFocus();
				}
				dragging = false;
				// If there is just a space then select it so it gets deleted on first key press
				if (stext.getPlainText().equals(""))
					stext.setText(" ");
				if (stext.getPlainText().equals(" ")) {
					LinkedList<TextLayoutInfo> lines = stext.getLines(buffer.g2);
					startTLHI = new TextLayoutHitInfo(lines.getFirst(), null);
					startTLHI.thi = startTLHI.tli.layout.getNextLeftHit(1);

					endTLHI = new TextLayoutHitInfo(lines.getLast(), null);
					int lastChar = endTLHI.tli.layout.getCharacterCount();
					endTLHI.thi = startTLHI.tli.layout.getNextRightHit(lastChar - 1);
				} else {
					endTLHI = stext.calculateFromXY(buffer.g2, ox + ptx, oy + pty);
					startTLHI = new TextLayoutHitInfo(endTLHI);
				}
				calculateCaretPos(endTLHI);
				bufferInvalid = true;
			} else { // Not over this control so if we have focus loose it
				if (focusIsWith == this)
					loseFocus(parent);
			}
			break;
		case MouseEvent.RELEASE:
			dragging = false;
			bufferInvalid = true;
			break;
		case MouseEvent.WHEEL:
			if (currSpot == 1) {
				if (focusIsWith == this)
					loseFocus(parent);
				int delta = event.getCount() * G4P.wheelForSpinner;
				if (delta != 0) {
					boolean changed = delta > 0 ? inc() : dec();
					if (changed) {
						fireEvent(this, GEvent.CHANGED);
					}
				}
			}
		}
	}

	/**
	 * Give up focus but if the text is only made from spaces then set it to null
	 * text. <br>
	 * Fire focus events for the GTextField and GTextArea controls
	 */
	protected void loseFocus(GAbstractControl grabber) {
		// If this control has focus then Fire a lost focus event
		if (focusIsWith == this) {
			int n0 = Integer.parseInt(stext.getPlainText());
			int n1 = PApplet.constrain(n0, low, high);
			if (n0 != n1) {
				super.setText("" + n1);
				System.out.println(n0 + " >> " + n1);
			}
			fireEvent(this, GEvent.LOST_FOCUS);
		}
		// Process mouse-over cursor
		if (cursorIsOver == this)
			cursorIsOver = null;
		focusIsWith = grabber;
		keepCursorInView = true;
		bufferInvalid = true;
	}

	public void keyEvent(KeyEvent e) {
		if (!visible || !enabled || !textEditEnabled || !available)
			return;
		if (focusIsWith == this && endTLHI != null) {
			char keyChar = e.getKey();
			int keyCode = e.getKeyCode();
			int keyID = e.getAction();
			boolean shiftDown = e.isShiftDown();
			boolean ctrlDown = e.isControlDown();

			textChanged = false;
			keepCursorInView = true;

			// Get selection details
			endChar = endTLHI.tli.startCharIndex + endTLHI.thi.getInsertionIndex();
			startChar = (startTLHI != null) ? startTLHI.tli.startCharIndex + startTLHI.thi.getInsertionIndex()
					: endChar;
			pos = endChar;
			nbr = 0;
			adjust = 0;
			if (endChar != startChar) { // Have we some text selected?
				if (startChar < endChar) { // Forward selection
					pos = startChar;
					nbr = endChar - pos;
				} else if (startChar > endChar) { // Backward selection
					pos = endChar;
					nbr = startChar - pos;
				}
			}
			// Select either keyPressedProcess or keyTypeProcess. These two methods are
			// overridden in child classes
			if (keyID == KeyEvent.PRESS) {
				keyPressedProcess(keyCode, keyChar, shiftDown, ctrlDown);
			} else if (keyID == KeyEvent.TYPE) { // && e.getKey() != KeyEvent.CHAR_UNDEFINED && !ctrlDown){
				keyTypedProcess(keyCode, keyChar, shiftDown, ctrlDown);
			}
			if (textChanged) {
				changeText();
				fireEvent(this, GEvent.CHANGED);
			}
		}
	}

	protected void keyPressedProcess(int keyCode, char keyChar, boolean shiftDown, boolean ctrlDown) {
		ksm.logKey(1);
		switch (keyCode) {
		case LEFT:
			moveCaretLeft(endTLHI);
			break;
		case RIGHT:
			moveCaretRight(endTLHI);
			break;
		case GConstants.HOME:
			moveCaretStartOfLine(endTLHI);
			break;
		case GConstants.END:
			moveCaretEndOfLine(endTLHI);
			break;
		}
		calculateCaretPos(endTLHI);
	}

	protected void keyTypedProcess(int keyCode, char keyChar, boolean shiftDown, boolean ctrlDown) {
		ksm.logKey(1);
		int keyCharIdx = "0123456789-".indexOf(keyChar);
		if (keyCharIdx >= 0) {
			if (keyCharIdx < 10) { // 0123456789
				stext.insertCharacters("" + keyChar, pos);
				adjust = 1;
				textChanged = true;
			} else { // - (minus key)
				String pt = stext.getPlainText();
				if (!pt.startsWith("-")) { // Make negative
					stext.insertCharacters("-", 0);
					adjust = 1;
				} else { // make positive by removing negative sign
					stext.deleteCharacters(0, 1);
					adjust = 0;
				}
				textChanged = true;
			}
			// Remove leading zeroes
			int minusIdx = stext.getPlainText().startsWith("-") ? 1 : 0;
			System.out.println("Char at " + minusIdx + " = '" + stext.getPlainText().charAt(minusIdx) + "'   Len = "
					+ stext.length());
			while (stext.getPlainText().charAt(minusIdx) == '0' && stext.length() > minusIdx + 1) {
				stext.deleteCharacters(minusIdx, 1);
				adjust--;
				textChanged = true;
			}
		} else if (keyChar == BACKSPACE) {
			if (stext.deleteCharacters(pos - 1, 1)) {
				adjust = -1;
				textChanged = true;
			}
		} else if (keyChar == DELETE) {
			if (stext.deleteCharacters(pos, 1)) {
				adjust = 0;
				textChanged = true;
			}
		} else if (keyChar == ENTER || keyChar == RETURN) {
			fireEvent(this, GEvent.ENTERED);
			// If we have a tab manager and can tab forward then do so
			if (tabManager != null && tabManager.nextControl(this)) {
				startTLHI.copyFrom(endTLHI);
				return;
			}
		} else if (keyChar == TAB) {
			// If possible move to next text control
			if (tabManager != null) {
				boolean result = (shiftDown) ? tabManager.prevControl(this) : tabManager.nextControl(this);
				if (result) {
					startTLHI.copyFrom(endTLHI);
					return;
				}
			}
		}
		// If we have emptied the text then recreate a one character string (space)

		if (stext.length() == 0) {
			stext.insertCharacters("0", 0);
			adjust++;
			textChanged = true;
		} else if (stext.getPlainText().equals("-")) {
			stext.insertCharacters("0", 1);
			adjust++;
			textChanged = true;
		}

	}

	protected boolean changeText() {
		if (!super.changeText())
			return false;
		try {
			value = Integer.parseInt(stext.getPlainText());
			lastValue = value;
			startTLHI.copyFrom(endTLHI);
		} catch (NumberFormatException e) {
			setValue(lastValue);
			loseFocus(parent);
		}
		return true;
	}

	public void draw() {
		if (!visible)
			return;
		updateBuffer();
		winApp.push();

		winApp.translate(cx, cy);
		winApp.rotate(rotAngle);

		// Draw buffer
		winApp.push();
		winApp.translate(-halfWidth, -halfHeight);
		winApp.imageMode(PApplet.CORNER);
		if (alphaLevel < 255)
			winApp.tint(TINT_FOR_ALPHA, alphaLevel);
		winApp.image(buffer, 0, 0);

		// Draw caret if text display area
		if (focusIsWith == this && showCaret && endTLHI.tli != null) {
			float[] cinfo = endTLHI.tli.layout.getCaretInfo(endTLHI.thi);
			float x_left = -ptx + cinfo[0];
			float y_top = -pty + endTLHI.tli.yPosInPara;
			float y_bot = y_top - cinfo[3] + cinfo[5];
			if (x_left >= 0 && x_left <= tw && y_top >= 0 && y_bot <= th) {
				winApp.strokeWeight(1.5f);
				winApp.stroke(palette[12].getRGB());
				winApp.line(tx + x_left, ty + Math.max(0, y_top), tx + x_left, ty + Math.min(th, y_bot));
			}
		}
		winApp.pop();

		drawChildren();

		winApp.pop();
	}

	/**
	 * If the buffer is invalid then redraw it.
	 * 
	 * @TODO need to use palette for colours
	 */
	protected void updateBuffer() {
		if (bufferInvalid) {
			bufferInvalid = false;
			buffer.beginDraw();
			Graphics2D g2d = buffer.g2;
			g2d.setFont(localFont);

			// Get the latest lines of text
			LinkedList<TextLayoutInfo> lines = stext.getLines(g2d);

			boolean usePromptText = promptText != null && !hasFocus()
					&& (lines.isEmpty() || stext.getPlainText().equals("") || stext.getPlainText().equals(" "));
			if (usePromptText)
				lines = promptText.getLines(g2d);
			// Whole control surface if opaque
			if (opaque)
				buffer.background(palette[6].getRGB());
			else
				buffer.background(buffer.color(255, 0));

			// Now move to top left corner of text display area
			buffer.translate(tx, ty);

			// Typing area surface
			buffer.noStroke();
			buffer.fill(palette[7].getRGB());
			buffer.rect(-1, -1, tw + 2, th + 2);

			g2d.setClip(gpTextDisplayArea);
			buffer.translate(-ptx, -pty);
			// Translate in preparation for display selection and text

			if (hasFocus() && stext.getPlainText().equals(" ")) {
				lines = stext.getLines(buffer.g2);
				startTLHI = new TextLayoutHitInfo(lines.getFirst(), null);
				startTLHI.thi = startTLHI.tli.layout.getNextLeftHit(1);

				endTLHI = new TextLayoutHitInfo(lines.getLast(), null);
				int lastChar = endTLHI.tli.layout.getCharacterCount();
				endTLHI.thi = startTLHI.tli.layout.getNextRightHit(lastChar - 1);
			}

			// Display selection and text
			for (TextLayoutInfo lineInfo : lines) {
				TextLayout layout = lineInfo.layout;
				buffer.translate(0, layout.getAscent());
				// Draw text
				g2d.setColor(palette[2]);
				lineInfo.layout.draw(g2d, 0, 0);
				buffer.translate(0, layout.getDescent() + layout.getLeading());
			}
			g2d.setClip(null);
			buffer.endDraw();
		}
	}

}
