/*
  Part of the G4P library for Processing 
  	http://www.lagers.org.uk/g4p/index.html
	http://sourceforge.net/projects/g4p/files/?source=navbar

  Copyright (c) 2015 Peter Lager

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

import processing.core.PConstants;
import processing.core.PMatrix;
import processing.core.PVector;
import processing.event.KeyEvent;

/**
 * Class for independent windows using P2D or P3D renderers. These can be used
 * to hold G4P GUI components or used for drawing or both combined. <br>
 * <br>
 * A number of examples are included in the library and more info can be found
 * at www.lagers.org.uk
 * 
 * Updated for Processing V3
 * 
 * @author Peter Lager
 *
 */
public final class GWindowNEWT extends GWindow {

	private PMatrix orgMatrix = null;

	protected GWindowNEWT(String title, int w, int h, boolean is3D) {
		super(title, w, h);
		this.is3D = is3D;
		renderer_type = is3D ? P3D : P2D;
	}

	/**
	 * Always returns true because these windows always use OpenGL
	 */
	public boolean usesGL() {
		return true;
	}

	public void setup() {
		super.setup();
		orgMatrix = getMatrix();
	}

	public void draw() {
		super.draw();
		pushMatrix();
		if (is3D) {
			// Has the window been resized, if yes get the new 3D matrix
			if (w != width || h != height) {
				orgMatrix = getMatrix();
				w = width;
				h = height;
			}
			hint(DISABLE_DEPTH_TEST);
			setMatrix(orgMatrix);
		}
		for (GAbstractControl control : windowControls) {
			if ((control.registeredMethods & DRAW_METHOD) == DRAW_METHOD)
				control.draw();
		}
		if (is3D) {
			hint(ENABLE_DEPTH_TEST);
		}
		popMatrix();
	}

	/*
	 * PROCESSING BUG FIX CODE
	 * 
	 * The following two methods override the one in GWindow as a fix for Processing
	 * issue no 4654
	 * 
	 * When a key is typed then 3 events are generated PRESS - TYPE - RELEASE The
	 * TYPE event is not being generated for Delete, Backspace or Enter/Return keys.
	 * These keys are identified by their keycode Delete (147), Backspace (8) Enter
	 * (10) this method creates the TYPE and sends it to the G4P controls before the
	 * RELEASE event.
	 * 
	 * The issue is several years old so obviously it must be low priority to them
	 * LOL
	 */
	public void keyEvent(KeyEvent event) {

		if (keyHandlerObject != null) {
			try {
				keyHandlerMethod.invoke(keyHandlerObject, new Object[] { this, data, event });
			} catch (Exception e) {
				GMessenger.message(EXCP_IN_HANDLER, new Object[] { keyHandlerObject, keyHandlerMethodName, e });
			}
		}

		KeyEvent ke = event;
		char keyChar = event.getKey();
		int keyCode = event.getKeyCode();
		int action = event.getAction();
		Object n_event = event.getNative();
		long millis = event.getMillis();
		int mods = event.getModifiers();
		// Make sure the keyChar and keyCode values used for BACKSPACE, ENTER, RETURN
		// and DELETE
		// are consistent and match those with JAVA2D mode
		switch (keyCode) {
		case 8:
			keyChar = PConstants.BACKSPACE;
			break;
		case 10:
			keyChar = PConstants.ENTER;
			break;
		case 13:
			keyChar = PConstants.RETURN;
			break;
		case 127:
		case 147: // Processing produces this for DELETE why??? Who knows LOL
			keyCode = 127;
			keyChar = PConstants.DELETE;
		}
		if (keyCode == 8 || keyCode == 10 || keyCode == 13 || keyCode == 127) {
			switch (action) {
			case KeyEvent.PRESS:
				ke = new KeyEvent(n_event, millis, KeyEvent.PRESS, mods, keyChar, keyCode);
				sendKeyEvent(ke);
				break;
			case KeyEvent.RELEASE:
				ke = new KeyEvent(null, millis - 1, KeyEvent.TYPE, mods, keyChar, keyCode);
				sendKeyEvent(ke);
				ke = new KeyEvent(n_event, millis, KeyEvent.RELEASE, mods, keyChar, keyCode);
				sendKeyEvent(ke);
			}
		} else { // Events for all other keys simply forward
			sendKeyEvent(event);
		}

	}

	private void sendKeyEvent(KeyEvent event) {
		for (GAbstractControl control : windowControls) {
			if ((control.registeredMethods & KEY_METHOD) == KEY_METHOD) {
				control.keyEvent(event);
			}
		}
	}

	/**
	 * Remove all existing window listeners added by Processing and add our own
	 * custom listener.
	 */
	protected void initListeners() {
		// com.jogamp.newt.opengl.GLWindow newtCanvas =
		// (com.jogamp.newt.opengl.GLWindow) surface.getNative();
		com.jogamp.newt.opengl.GLWindow newtCanvas = getCanvas();
		for (com.jogamp.newt.event.WindowListener l : newtCanvas.getWindowListeners())
			if (l.getClass().getName().startsWith("processing"))
				newtCanvas.removeWindowListener(l);
		newtCanvas.addWindowListener(new WindowAdapterNEWT(this));
	}

	/**
	 * This sets what happens when the users attempts to close the window. <br>
	 * There are 3 possible actions depending on the value passed. <br>
	 * G4P.KEEP_OPEN - ignore attempt to close window (default action), <br>
	 * G4P.CLOSE_WINDOW - close this window,<br>
	 * G4P.EXIT_APP - exit the app, this will cause all windows to close. <br>
	 * 
	 * @param action the required close action
	 */
	public void setActionOnClose(int action) {
		com.jogamp.newt.opengl.GLWindow newtCanvas = getCanvas();
		if (action == KEEP_OPEN || action == HIDE_WINDOW)
			newtCanvas.setDefaultCloseOperation(
					com.jogamp.nativewindow.WindowClosingProtocol.WindowClosingMode.DO_NOTHING_ON_CLOSE);
		else
			newtCanvas.setDefaultCloseOperation(
					com.jogamp.nativewindow.WindowClosingProtocol.WindowClosingMode.DISPOSE_ON_CLOSE);
		actionOnClose = action;
	}

	private com.jogamp.newt.opengl.GLWindow getCanvas() {
		return (com.jogamp.newt.opengl.GLWindow) surface.getNative();
	}

	/**
	 * Fire a window closing event
	 */
	protected void fireCloseWindowEvent() {
		getCanvas().destroy();
	}

	/**
	 * Set the windows visibility
	 * 
	 * @param visible whether the window is to be visible or not
	 */
	public void setVisible(boolean visible) {
		getCanvas().setVisible(false, visible);
	}

	/**
	 * Returns true if the window is visible else false
	 */
	public boolean isVisible() {
		return getCanvas().isVisible();
	}

	/**
	 * Set the window title
	 * 
	 * @param title the window's title bar text
	 */
	public void setTitle(String title) {
		getCanvas().setTitle(title);
	}

	/**
	 * Get the window title
	 */
	public String getTitle() {
		return getCanvas().getTitle();
	}

	/**
	 * Set the windows position
	 * 
	 * @param x horizontal position of window
	 * @param y vertical position of window
	 */
	public void setLocation(int x, int y) {
		getCanvas().setPosition(x, y);
	}

	/**
	 * Get the window position.
	 */
	public PVector getPosition(PVector pos) {
		if (pos == null)
			pos = new PVector();
		pos.x = getCanvas().getX();
		pos.y = getCanvas().getY();
		return pos;
	}

	/**
	 * Sets whether this window should always be above other windows. If there are
	 * multiple always-on-top windows, their relative order is unspecified and
	 * platform dependent.
	 * 
	 * @param ontop if true the window will always remain above other windows
	 */
	public void setAlwaysOnTop(boolean ontop) {
		getCanvas().setAlwaysOnTop(ontop);
	}

}
