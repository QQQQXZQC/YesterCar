package g4p_controls;

import java.util.Collections;
import java.util.LinkedList;

import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PMatrix;
import processing.core.PMatrix3D;
import processing.event.KeyEvent;
import processing.event.MouseEvent;

/**
 * This class calls the appropriate methods for G4P controls for the main applet
 * window.
 * 
 * This will be created when the first control is added to the main window.
 * 
 * @author Peter Lager
 *
 */
public final class GWindowImpl implements GConstants, GConstantsInternal {

	public LinkedList<GAbstractControl> windowControls = new LinkedList<GAbstractControl>();
	// These next two lists are for controls that are to be added or remove 
	// since these actions must be performed outside the draw cycle to avoid
	// concurrent modification exceptions when changing windowControls
	public LinkedList<GAbstractControl> toRemove = new LinkedList<GAbstractControl>();
	public LinkedList<GAbstractControl> toAdd = new LinkedList<GAbstractControl>();

	PApplet app;
	PMatrix orgMatrix = null;
	int last_width, last_height;
	boolean is3D = false;

	public GWindowImpl(PApplet app) {
		this.app = app;
		last_width = this.app.width;
		last_height = this.app.height;
		PMatrix mat = app.getMatrix();
		if (mat instanceof PMatrix3D) {
			orgMatrix = mat;
			is3D = true;
		}
		registerMethods();
	}

	public boolean usesGL() {
		return app.getGraphics().isGL();
	}

	public void registerMethods() {
		app.registerMethod("pre", this);
		app.registerMethod("draw", this);
		app.registerMethod("post", this);
		app.registerMethod("mouseEvent", this);
		app.registerMethod("keyEvent", this);
		app.registerMethod("dispose", this);
	}

	protected void unregisterMethods() {
		app.unregisterMethod("pre", this);
		app.unregisterMethod("draw", this);
		app.unregisterMethod("post", this);
		app.unregisterMethod("mouseEvent", this);
		app.unregisterMethod("keyEvent", this);
	}

	protected void addToWindow(GAbstractControl control) {
		// Avoid adding duplicates
		if (!toAdd.contains(control) && !windowControls.contains(control)) {
			toAdd.add(control);
		}
	}

	protected void removeFromWindow(GAbstractControl control) {
		toRemove.add(control);
	}

	/**
	 * Invalidate the graphic buffers for all controls so they are forced to be
	 * redrawn
	 */
	void invalidateBuffers() {
		for (GAbstractControl control : windowControls)
			control.bufferInvalid = true;
	}

	/**
	 * Set the colour scheme to be used by all controls on this window.
	 * 
	 * @param cs colour scheme e.g. G4P.GREEN_SCHEME
	 */
	void setColorScheme(int cs) {
		for (GAbstractControl control : windowControls)
			control.setLocalColorScheme(cs);
	}

	/**
	 * Set the alpha level for all controls on this window. <br>
	 * 0 = fully transparent <br>
	 * 255 = fully opaque <br>
	 * Controls are disabled when alpha gets below G4P.ALPHA_BLOCK (128)
	 * 
	 * @param alpha 0-255 inclusive
	 */
	void setAlpha(int alpha) {
		for (GAbstractControl control : windowControls) {
			control.setAlpha(alpha);
		}
	}

	public void draw() {
		app.pushMatrix();
		if (orgMatrix != null) { // && app.getGraphics().is3D()) {
			app.setMatrix(orgMatrix);
		}
//		System.out.println("==============");
		for (GAbstractControl control : windowControls) {
//			System.out.print(control.tag + "  #  ");
			if ((control.registeredMethods & DRAW_METHOD) == DRAW_METHOD) {
				control.draw();
			}
		}
//		System.out.println();
		app.popMatrix();
	}

	/**
	 * The mouse method registered with Processing
	 * 
	 * @param event the mouse event to process
	 */
	public void mouseEvent(MouseEvent event) {
		for (GAbstractControl control : windowControls) {
			if ((control.registeredMethods & MOUSE_METHOD) == MOUSE_METHOD) {
				control.mouseEvent(event);
			}
		}
	}

	/**
	 * The key method registered with Processing. It will forward the event to all
	 * controls visible on the main window.
	 * 
	 * @param event the key event to process
	 */
	// public void keyEvent(KeyEvent event) {
	// System.out.println(Util.key_event_desc(event));
	// for(GAbstractControl control : windowControls){
	// if( (control.registeredMethods & KEY_METHOD) == KEY_METHOD) {
	// control.keyEvent(event);
	// }
	// }
	// }

	/*
	 * PROCESSING BUG FIX CODE
	 * 
	 * The following two methods replace the one above as a fix for Processing issue
	 * no 4654
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
		if (!usesGL()) {
			// Using JAVA2D so simply forward event
			sendKeyEvent(event);
			return;
		}
		// Using P2D or P3D
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

	public void dispose() {
		for (GWindow window : G4P.allWindows) {
			window.forceClose();
		}
	}

	/**
	 * Manages allocating focus to the correct control
	 */
	public void pre() {
		// Has the window been resized, if yes get the new matrix
		if (is3D && (last_width != app.width || last_height != app.height)) {
			last_width = app.width;
			last_height = app.height;
			orgMatrix = app.getMatrix();
		}
		if (GAbstractControl.controlToTakeFocus != null && GAbstractControl.controlToTakeFocus.getPApplet() == app) {
			GAbstractControl.controlToTakeFocus.setFocus(true);
			GAbstractControl.controlToTakeFocus = null;
		}
		for (GAbstractControl control : windowControls) {
			if ((control.registeredMethods & PRE_METHOD) == PRE_METHOD) {
				control.pre();
			}
		}
	}

	/**
	 * Manages cursor icon changes, also adding and removing controls
	 */
	public void post() {
		if (G4P.cursorChangeEnabled) {
			// If the cursor is over a control and the control is in the main sketch window
			if (GAbstractControl.cursorIsOver != null && GAbstractControl.cursorIsOver.getPApplet() == app) {
				app.cursor(GAbstractControl.cursorIsOver.cursorOver);
			} else {
				app.cursor(G4P.mouseOff);
			}
		}
		for (GAbstractControl control : windowControls) {
			if ((control.registeredMethods & POST_METHOD) == POST_METHOD) {
				control.post();
			}
		}
		// =====================================================================================================
		// =====================================================================================================
		// This is where components are removed or added to the window to avoid
		// concurrent access violations
		// =====================================================================================================
		// =====================================================================================================

		synchronized (this) {
			// Dispose of any unwanted controls
			if (!toRemove.isEmpty()) {
				for (GAbstractControl control : toRemove) {
					// If the control has focus then lose it
					if (GAbstractControl.focusIsWith == control) {
						control.loseFocus(null);
					}
					// Clear control resources
					control.buffer = null;
					if (control.parent != null) {
						control.parent.children.remove(control);
						control.parent = null;
					}
					if (control.children != null) {
						control.children.clear();
					}
					control.palette = null;
					control.eventHandlerObject = null;
					control.eventHandlerMethod = null;
					control.winApp = null;
					windowControls.remove(control);
				}
				toRemove.clear();
				System.gc();
			}
			if (!toAdd.isEmpty()) {
				for (GAbstractControl control : toAdd) {
					windowControls.add(control);
				}
				toAdd.clear();
				Collections.sort(windowControls, G4P.zorder);
			}
		}
	}

	String actionString(MouseEvent e) {
		switch (e.getAction()) {
		default:
			return "UNKNOWN";
		case MouseEvent.CLICK:
			return "CLICK";
		case MouseEvent.DRAG:
			return "DRAG";
		case MouseEvent.ENTER:
			return "ENTER";
		case MouseEvent.EXIT:
			return "EXIT";
		case MouseEvent.MOVE:
			return "MOVE";
		case MouseEvent.PRESS:
			return "PRESS";
		case MouseEvent.RELEASE:
			return "RELEASE";
		case MouseEvent.WHEEL:
			return "WHEEL";
		}
	}

}
