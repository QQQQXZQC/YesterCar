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

import g4p_controls.HotSpot.HSrect;
import g4p_controls.StyledString.TextLayoutHitInfo;
import g4p_controls.StyledString.TextLayoutInfo;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.GeneralPath;
import java.util.LinkedList;

import processing.core.PApplet;
import processing.core.PGraphics;
import processing.event.KeyEvent;
import processing.event.MouseEvent;

/**
 * The text field component. <br>
 * 
 * This control allows the user to enter and edit a single line of text. The
 * control also allows default text and a horizontal scrollbar.
 * 
 * can be created to manage either a single line of text or multiple lines of
 * text. <br>
 * 
 * Enables user text input at runtime. Text can be selected using the mouse or
 * keyboard shortcuts and then copied or cut to the clipboard. Text can also be
 * pasted in. <br>
 * 
 * As well as text this control will accept numeric input either integer or
 * decimal within a user specified range. Invalid user input is highlighted in
 * the control to be corrected by the user.
 *
 * Fires SELECTION_CHANGED, CHANGED, ENTERED, LOST_FOCUS, GETS_FOCUS events.<br>
 * 
 * From V4.3.3 The text field can be used to validate numeric input within a
 * user defined range. The input can be either integer {@code int} or decimal
 * {@code float}, using the {@code setNumeric(,,)} method. If all three
 * parameters are integers then only integer values in the range are valid, if
 * <em>any</em> of the parameters is a {@code float} then any decimal value in
 * the range is valid. <br>
 * 
 * Decimal formats include numbers in exponential format e.g. -4.911e-2 <br>
 * 
 * @author Peter Lager
 *
 */
public class GTextField extends GEditableTextControl {

	private boolean showAsValid = true;
	private boolean valid = false;

	private float floatLow = -Float.MAX_VALUE;
	private float floatHigh = Float.MAX_VALUE;
	private float floatInvalid = 0;
	private float floatValue = 0;

	private int intLow = Integer.MIN_VALUE;
	private int intHigh = Integer.MAX_VALUE;
	private int intInvalid = 0;
	private int intValue = 0;

	// <0 any text : 0 = integer : >0 float
	private int filter = -1;

	/**
	 * Create a text field without a scrollbar.
	 * 
	 * @param theApplet the main sketch or GWindow control for this control
	 * @param p0        x position based on control mode
	 * @param p1        y position based on control mode
	 * @param p2        x position or width based on control mode
	 * @param p3        y position or height based on control mode
	 */
	public GTextField(PApplet theApplet, float p0, float p1, float p2, float p3) {
		this(theApplet, p0, p1, p2, p3, SCROLLBARS_NONE);
	}

	/**
	 * Create a text field with the given scrollbar policy. <br>
	 * This policy can be one of these <br>
	 * <ul>
	 * <li>SCROLLBARS_NONE</li>
	 * <li>SCROLLBARS_HORIZONTAL_ONLY</li>
	 * </ul>
	 * If you want the scrollbar to auto hide then perform a logical or with
	 * <ul>
	 * <li>SCROLLBARS_AUTOHIDE</li>
	 * </ul>
	 * e.g. SCROLLBARS_HORIZONTAL_ONLY | SCROLLBARS_AUTOHIDE <br>
	 * 
	 * @param theApplet the main sketch or GWindow control for this control
	 * @param p0        x position based on control mode
	 * @param p1        y position based on control mode
	 * @param p2        x position or width based on control mode
	 * @param p3        y position or height based on control mode
	 * @param sbPolicy  scrollbar policy
	 */
	public GTextField(PApplet theApplet, float p0, float p1, float p2, float p3, int sbPolicy) {
		super(theApplet, p0, p1, p2, p3, sbPolicy);
		children = new LinkedList<GAbstractControl>();
		tx = ty = 2;
		tw = width - 2 * 2;
		th = height - ((scrollbarPolicy & SCROLLBAR_HORIZONTAL) != 0 ? 11 : 0);
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
		if ((scrollbarPolicy & SCROLLBAR_HORIZONTAL) != 0) {
			hsb = new GScrollbar(theApplet, 0, 0, tw, 10);
			addControlImpl(hsb, tx, ty + th - hsb.halfHeight + 2, 0);
			hsb.addEventHandler(this, "hsbEventHandler");
			hsb.setAutoHide(autoHide);
		}
		G4P.popStyle();
		setText("");

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
	 * Set the styled text for this textfield after ensuring that all EOL characters
	 * have been removed.
	 * 
	 * @param ss the styled text to be displayed
	 */
	public void setStyledText(StyledString ss) {
		cancelSelection();
		stext = ss.convertToSingleLineText();
		stext.getLines(buffer.g2);
		if (stext.getNbrLines() > 0) {
			endTLHI.tli = stext.getLines(buffer.g2).getFirst();
			endTLHI.thi = endTLHI.tli.layout.getNextLeftHit(1);
			startTLHI.copyFrom(endTLHI);
			calculateCaretPos(endTLHI);
			keepCursorInView = true;
		}
		ptx = pty = 0;
		// If needed update the horizontal scrollbar
		if (hsb != null) {
			if (stext.getMaxLineLength() < tw)
				hsb.setValue(0, 1);
			else
				hsb.setValue(0, tw / stext.getMaxLineLength());
		}
		testValidity(stext.getPlainText());
		bufferInvalid = true;
	}

	/**
	 * Set the text to be displayed.
	 * 
	 * @param text the text to be displayed
	 */
	public void setText(String text) {
		if (text != null) {
			cancelSelection();
			stext.setText(text, Integer.MAX_VALUE);
			setScrollbarValues(0, 0);
			bufferInvalid = true;
			testValidity(stext.getPlainText());
		}
	}

	
	/**
	 * Insert text at the display position specified. <br>
	 * 
	 * No events will be generated and the caret will be moved to the end of
	 * any inserted text. <br>
	 * 
	 * @param text     the text to insert
	 * @param charNo   the character position to insert text in display line
	 * @return true if some characters were inserted
	 */
	public boolean insertText(String text, int charNo) {
		if (text != null && text.length() > 0) {
			int pos = stext.getPos(0, charNo);
			int change = stext.insertCharacters(text, 0, charNo, false, false);
			// displayCaretPos("Caret starts at ");
			if (change != 0) {
				// Move caret to end of insert if possible
				pos += change;
				TextLayoutHitInfo tlhi = stext.getTLHIforCharPosition(pos);
				if (tlhi != null) {
					endTLHI.copyFrom(tlhi);
					moveCaretLeft(endTLHI);
					startTLHI.copyFrom(endTLHI);
					// displayCaretPos("Caret ends at ");
					calculateCaretPos(tlhi);
					keepCursorInView = true;
					showCaret = true;
				}
				setScrollbarValues(ptx, pty);
				bufferInvalid = true;
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Will cause the text field to show when an invalid integer has been entered
	 * into the field. <br>
	 * An invalid integer is any text that cannot be converted into an integer or is
	 * outside the range indicated by the first two parameters. <br>
	 * If {@code getValueI()} is called then it will return the current valid
	 * integer or if invalid the value passed in the third parameter. <br>
	 * Note: all the parameters must be of type {@code int} <br>
	 * 
	 * @param low           the lowest valid integer
	 * @param high          the highest valid integer
	 * @param default_value the value to be returned by {@code getValueI()} for
	 *                      invalid user input
	 */
	public void setNumeric(int low, int high, int default_value) {
		filter = INTEGER;
		intLow = Math.min(low, high);
		intHigh = Math.max(low, high);
		intInvalid = default_value;
		testValidity(stext.getPlainText());
	}

	/**
	 * Will cause the text field to show when an invalid float has been entered into
	 * the field. <br>
	 * An invalid float is any text that cannot be converted into a float or is
	 * outside the range indicated by the first two parameters. <br>
	 * If {@code getValueF()} is called then it will return the current valid float
	 * or if invalid the value passed in the third parameter. <br>
	 * Note: at least one of the parameters must be of type {@code float} <br>
	 * 
	 * @param low           the lowest valid float
	 * @param high          the highest valid float
	 * @param default_value the value to be returned by {@code getValueF()}for
	 *                      invalid user input
	 */
	public void setNumeric(float low, float high, float default_value) {
		filter = DECIMAL;
		floatLow = Math.min(low, high);
		floatHigh = Math.max(low, high);
		floatInvalid = default_value;
		testValidity(stext.getPlainText());
	}

	/**
	 * Sets the filter to be used, valid parameter values are G4P.INTEGER,
	 * G4P.DECIMAL or G4P.EXPONENT and the accepted range will be based on range of
	 * valid values for the Java data types {@code int} or {@code float}. <br>
	 * if the parameter is null or missing then it cancels the filter and all text
	 * values are considered valid.
	 * 
	 * @param f the filter to apply (can be null or omitted)
	 */
	public void setNumericType(int... f) {
		int n = f == null || f.length == 0 ? -1 : f[0];
		switch (n) {
		case INTEGER:
			filter = INTEGER;
			intLow = Integer.MIN_VALUE;
			intHigh = Integer.MAX_VALUE;
			intInvalid = 0;
			break;
		case DECIMAL:
		case EXPONENT:
			filter = DECIMAL;
			floatLow = -Float.MAX_VALUE;
			floatHigh = Float.MAX_VALUE;
			floatInvalid = 0;
			break;
		default:
			filter = -1;
		}
		testValidity(stext.getPlainText());
	}

	/**
	 * If the field is displaying a valid integer within the specified range return
	 * it. Otherwise the default int value.
	 */
	public int getValueI() {
		return valid ? intValue : intInvalid;
	}

	/**
	 * If the field is displaying a valid float within the specified range return
	 * it. Otherwise the default float value.
	 */
	public float getValueF() {
		return valid ? floatValue : floatInvalid;
	}

	/**
	 * 
	 * @return true if the visible input is valid for the filter
	 */
	public boolean isValid() {
		return valid;

	}

//	/**
//	 * Cancels any selection. So the selection box will disappear.
//	 */
//	protected void cancelSelection() {
//		startTLHI.cancelInfo();
//		endTLHI.cancelInfo();
//	}

	/**
	 * Add some plain text to the end of the existing text.
	 * 
	 * @param extraText the text to append
	 */
	public void appendText(String extraText) {
		if (extraText == null || extraText.equals(""))
			return;
		if (stext.insertCharacters(extraText, stext.length()) == 0)
			return;
		LinkedList<TextLayoutInfo> lines = stext.getLines(buffer.g2);
		endTLHI.tli = lines.getLast();
		endTLHI.thi = endTLHI.tli.layout.getNextRightHit(endTLHI.tli.nbrChars - 1);
		startTLHI.copyFrom(endTLHI);
		calculateCaretPos(endTLHI);
		if (hsb != null) {
			float hvalue = lines.getLast().layout.getVisibleAdvance();
			float hlinelength = stext.getMaxLineLength();
			float hfiller = Math.min(1, tw / hlinelength);
			if (caretX < tw)
				hsb.setValue(0, hfiller);
			else
				hsb.setValue(hvalue / hlinelength, hfiller);
			keepCursorInView = true;
		}
		bufferInvalid = true;
	}

	public PGraphics getSnapshot() {
		updateBuffer();
		PGraphics snap = winApp.createGraphics(buffer.width, buffer.height, PApplet.JAVA2D);
		snap.beginDraw();
		snap.image(buffer, 0, 0);
		if (hsb != null) {
			snap.pushMatrix();
			snap.translate(hsb.getX(), hsb.getY() - hsb.halfHeight + 4);
			snap.image(hsb.getBuffer(), 0, 0);
			snap.popMatrix();
		}
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
				if (horzScroll && hsb != null)
					hsb.setValue(ptx / (stext.getMaxLineLength() + 4));
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
		case MouseEvent.DRAG:
			if (focusIsWith == this) {
				keepCursorInView = true;
				dragging = true;
				endTLHI = stext.calculateFromXY(buffer.g2, ox + ptx, oy + pty);
				calculateCaretPos(endTLHI);
				fireEvent(this, GEvent.SELECTION_CHANGED);
				bufferInvalid = true;
			}
			break;
		}
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

			int startPos = pos, startNbr = nbr;

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
			if (startPos >= 0) {
				if (startPos != pos || startNbr != nbr)
					fireEvent(this, GEvent.SELECTION_CHANGED);
			}
			// Select either keyPressedProcess or keyTypeProcess. These two methods are
			// overridden in child classes
			if (keyID == KeyEvent.PRESS) {
				keyPressedProcess(keyCode, keyChar, shiftDown, ctrlDown);
				setScrollbarValues(ptx, pty);
			} else if (keyID == KeyEvent.TYPE) { // && e.getKey() != KeyEvent.CHAR_UNDEFINED && !ctrlDown){
				keyTypedProcess(keyCode, keyChar, shiftDown, ctrlDown);
				setScrollbarValues(ptx, pty);
			}
			if (textChanged) {
				changeText();
				testValidity(stext.getPlainText());
//				// Start here to check for valid numeric values
//				switch(filter) {
//				case INTEGER:
//					try {
//						intValue = Integer.parseInt(stext.getPlainText());
//						valid = intValue >= intLow && intValue <= intHigh;
//					}
//					catch(NumberFormatException nfe) {
//						valid = false;
//					}
//					break;
//				case DECIMAL:
//				case EXPONENT:
//					try {
//						floatValue = Float.parseFloat(stext.getPlainText());
//						valid = floatValue >= floatLow && floatValue <= floatHigh;
//					}
//					catch(NullPointerException | NumberFormatException ex) {
//						valid = false;
//					}
//					break;
//				default:
//					valid = true;
//				}
//				showAsValid = valid || stext.getPlainText().equals(" ");
				// End of numeric tests
				fireEvent(this, GEvent.CHANGED);
			}
		}
	}

	protected void testValidity(String text) {
		switch (filter) {
		case INTEGER:
			try {
				intValue = Integer.parseInt(text);
				valid = intValue >= intLow && intValue <= intHigh;
			} catch (NumberFormatException nfe) {
				valid = false;
			}
			break;
		case DECIMAL:
		case EXPONENT:
			try {
				floatValue = Float.parseFloat(text);
				valid = floatValue >= floatLow && floatValue <= floatHigh;
			} catch (NullPointerException | NumberFormatException ex) {
				valid = false;
			}
			break;
		default:
			valid = true;
		}
		showAsValid = valid || text.length() == 0 || text.equals(" ");
	}

	/**
	 * Do not call this method directly, G4P uses it to handle input from the
	 * horizontal scrollbar.
	 */
	public void hsbEventHandler(GScrollbar scrollbar, GEvent event) {
		keepCursorInView = false;
		ptx = hsb.getValue() * (stext.getMaxLineLength() + 4);
		bufferInvalid = true;
	}

	protected void keyPressedProcess(int keyCode, char keyChar, boolean shiftDown, boolean ctrlDown) {
		ksm.logKey(1);
		boolean validKeyCombo = true;

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
		case 'A':
			if (ctrlDown) { // Ctrl + A select all
				moveCaretStartOfLine(startTLHI);
				moveCaretEndOfLine(endTLHI);
				// Make shift down so that the start caret position is not
				// moved to match end caret position.
				shiftDown = true;
			} else
				validKeyCombo = false;
			break;
		case 'C':
			if (ctrlDown) // Ctrl + C copy selected text
				GClip.copy(getSelectedText());
			validKeyCombo = false;
			break;
		case 'V':
			if (ctrlDown) { // Ctrl + V paste selected text
				String p = GClip.paste();
				p.replaceAll("\n", "");
				if (p.length() > 0) {
					ksm.logKey(p.length());
					// delete selection and add
					if (hasSelection())
						stext.deleteCharacters(pos, nbr);
					stext.insertCharacters(p, pos);
					adjust = p.length();
					textChanged = true;
				}
			} else
				validKeyCombo = false;
			break;
		default:
			validKeyCombo = false;
		}
		calculateCaretPos(endTLHI);

		if (validKeyCombo) {
			if (!shiftDown) // Not extending selection
				startTLHI.copyFrom(endTLHI);
			bufferInvalid = true; // Selection changed
		}
	}

	protected void keyTypedProcess(int keyCode, char keyChar, boolean shiftDown, boolean ctrlDown) {
		ksm.logKey(1);
		int ascii = (int) keyChar;
		if (isDisplayable(ascii)) {
			if (hasSelection())
				stext.deleteCharacters(pos, nbr);
			stext.insertCharacters("" + keyChar, pos);
			adjust = 1;
			textChanged = true;
		} else if (keyChar == BACKSPACE) {
			if (hasSelection()) {
				stext.deleteCharacters(pos, nbr);
				adjust = 0;
				textChanged = true;
			} else if (stext.deleteCharacters(pos - 1, 1)) {
				adjust = -1;
				textChanged = true;
			}
		} else if (keyChar == DELETE) {
			if (hasSelection()) {
				stext.deleteCharacters(pos, nbr);
				adjust = 0;
				textChanged = true;
			} else if (stext.deleteCharacters(pos, 1)) {
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
			stext.insertCharacters(" ", 0);
			adjust++;
			textChanged = true;
		}
		// if(stext.length() == 0){
		// stext.insertCharacters(" ", 0);
		// adjust++; textChanged = true;
		// LinkedList<TextLayoutInfo> lines = stext.getLines(buffer.g2);
		// startTLHI = new TextLayoutHitInfo(lines.getFirst(), null);
		// startTLHI.thi = startTLHI.tli.layout.getNextLeftHit(1);
		//
		// endTLHI = new TextLayoutHitInfo(lines.getLast(), null);
		// int lastChar = endTLHI.tli.layout.getCharacterCount();
		// endTLHI.thi = startTLHI.tli.layout.getNextRightHit(lastChar-1);
		// }

	}

	protected boolean changeText() {
		if (!super.changeText())
			return false;
		startTLHI.copyFrom(endTLHI);
		return true;
	}

	/**   4.3.10
	 * Move the insertion point (caret) to the specified character. If the
	 * position is invalid then the caret is not moved. The text will be 
	 * scrolled so that the caret position is visible.
	 * 
	 * @param charNo the character position on the line (starts at 0)
	 */
	public void moveCaretTo(int charNo) { 
		try {
			TextLayoutHitInfo tlhi = stext.getTLHIforCharPosition(0, charNo);
			if (tlhi != null) {
				startTLHI.copyFrom(tlhi);
				endTLHI.copyFrom(tlhi);
				calculateCaretPos(tlhi);
				keepCursorInView = true;
				showCaret = true;
			}
		} 
		catch (Exception e) {
		}
	}

	/**
	 * Get the current caret position. <br>
	 * 
	 * The method will always return a 2 element array with the current caret
	 * position { line no, char no } <br>
	 * 
	 * If the current caret position is undefined then it will return the array {
	 * -1, -1 }
	 * 
	 * @return a two element int array holding the caret position.
	 */
	public int[] getCaretPos() {
		return getCaretPos(null);
	}

	/**
	 * Get the current caret position. <br>
	 * 
	 * If the parameter is a 2 element int array then it will be populated with the
	 * line number [0] and character no [1] of the caret's current position. <br>
	 * 
	 * The method will always return a 2 element array with the current caret
	 * position { line no, char no } <br>
	 * 
	 * If the current caret position is undefined then it will return the array {
	 * -1, -1 }
	 * 
	 * @param cpos array to be populated with caret position
	 * @return a two element int array holding the caret position.
	 */
	public int[] getCaretPos(int[] cpos) {
		if (cpos == null || cpos.length != 2)
			cpos = new int[2];
		if (endTLHI == null || endTLHI.tli == null || endTLHI.thi == null) {
			cpos[0] = cpos[1] = -1;
		} else {
			cpos[0] = endTLHI.tli.lineNo;
			cpos[1] = endTLHI.thi.getCharIndex();
		}
		return cpos;
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
				winApp.stroke(valid ? palette[12].getRGB() : palette[14].getRGB());
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

			// If needed update the horizontal scrollbar
			// if(hsb != null){
			// if(stext.getMaxLineLength() < tw)
			// hsb.setValue(0, 1);
			// else
			// hsb.setValue(0, tw/stext.getMaxLineLength());
			// }

			// Whole control surface if opaque
			if (opaque)
				buffer.background(palette[6].getRGB());
			else
				buffer.background(buffer.color(255, 0));

			// Now move to top left corner of text display area
			buffer.translate(tx, ty);

			// Typing area surface
			buffer.noStroke();
			buffer.fill(showAsValid ? palette[7].getRGB() : palette[2].getRGB());
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
			TextLayoutHitInfo startSelTLHI = null, endSelTLHI = null;

			if (hasSelection()) {
				if (endTLHI.compareTo(startTLHI) == -1) {
					startSelTLHI = endTLHI;
					endSelTLHI = startTLHI;
				} else {
					startSelTLHI = startTLHI;
					endSelTLHI = endTLHI;
				}
			}
			// Display selection and text
			for (TextLayoutInfo lineInfo : lines) {
				TextLayout layout = lineInfo.layout;
				buffer.translate(0, layout.getAscent());
				// Draw selection if any
				if (!usePromptText && hasSelection() && lineInfo.compareTo(startSelTLHI.tli) >= 0
						&& lineInfo.compareTo(endSelTLHI.tli) <= 0) {
					int ss = startSelTLHI.thi.getInsertionIndex();
					int ee = endSelTLHI.thi.getInsertionIndex();
					g2d.setColor(palette[14]);
					Shape selShape = layout.getLogicalHighlightShape(ss, ee);
					g2d.fill(selShape);
				}
				// Draw text
				g2d.setColor(showAsValid ? palette[2] : palette[7]);
				lineInfo.layout.draw(g2d, 0, 0);
				buffer.translate(0, layout.getDescent() + layout.getLeading());
			}
			g2d.setClip(null);
			buffer.endDraw();
		}
	}

}
