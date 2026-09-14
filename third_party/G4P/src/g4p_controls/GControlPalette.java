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

import java.util.LinkedList;

import g4p_controls.HotSpot.HSrect;
import processing.core.PApplet;
import processing.event.MouseEvent;

/**
 * The control has a central tab (a GIcon or GAnimIcon) and when the mouse is
 * over it one or more G4P controls will appear in a straight line radially from
 * the tab, this is called the palette. <br>
 * The palette can appear on any side of the tab (i.e. north, south east or
 * west) and the user specifies the preferred alignment when the control is
 * created. <br>
 * The user can move the control by dragging the tab with the mouse. <br>
 * The preferred alignment will always be used provided it fits on the screen,
 * if it doesn't then the control will use the opposite alignment if that can be
 * displayed fully. <br>
 * 
 * This control was based on an idea by mala 2020
 * 
 * 
 * @author Peter Lager
 *
 */
public class GControlPalette extends GAbstractControl {

	static protected int PALETTE = 1;
	static protected int TAB = 0;

	protected GIcon icon;
	protected GAlign prefAlign, currAlign;

	protected int padding = 3;
	protected float bdrWeight = 1;
	protected float bdrCorner = 4;

	protected float rX, rY, rW, rH;
	protected boolean rVisible = false;

	protected boolean beingDragged = false;

	private float pLengthEW, pLengthNS;
	private float maxWidth = 0, maxHeight = 0;

	/**
	 * Construct a control palette object.
	 * 
	 * @param theApplet the window responsible for drawing the control.
	 * @param icon      a simple or animated icon for the tab
	 * @param align     the preferred direction of the palette
	 * @param x         the tab horizontal position (uses icon centre)
	 * @param y         the tab vertical position (uses icon centre)
	 */
	public GControlPalette(PApplet theApplet, GIcon g_icon, GAlign align, float x, float y) {
		super(theApplet);
		// Create the list of children
		children = new LinkedList<GAbstractControl>();
		icon = (g_icon.owner != null) ? g_icon.copy() : g_icon;
		// Set the owner so it can mark it invalid during animation
		icon.owner = this;
		// Set tab dimensions
		cx = x;
		cy = y;
		width = icon.width();
		height = icon.height();
		halfWidth = width / 2;
		halfHeight = height / 2;
		this.x = cx - halfWidth;
		this.y = cy - halfHeight;
		makeBuffer();
		prefAlign = currAlign = align;
		calcHotSpots();
		z = Z_PANEL;

		registeredMethods = DRAW_METHOD | MOUSE_METHOD;
		cursorOver = HAND;
		allowToolTips = false;
		bufferInvalid = true;

		// Must register control
		G4P.registerControl(this);
	}

	/**
	 * Recalculate the control positions after a change of palette alignment or when
	 * a control is added to or removed from the palette.
	 */
	protected void updateControlPositions() {
		maxWidth = maxHeight = 0;
		pLengthEW = pLengthNS = 0;
		// Get the maximum width and maximum height
		for (GAbstractControl c : children) {
			if (c.getWidth() > maxWidth) {
				maxWidth = c.getWidth();
			}
			if (c.getHeight() > maxHeight) {
				maxHeight = c.getHeight();
			}
			pLengthEW += c.getWidth() + 2 * padding;
			pLengthNS += c.getHeight() + 2 * padding;
		}
		switch (currAlign) {
		case EAST:
			makeAlignEAST(maxWidth, maxHeight);
			break;
		case WEST:
			makeAlignWEST(maxWidth, maxHeight);
			break;
		case SOUTH:
			makeAlignSOUTH(maxWidth, maxHeight);
			break;
		case NORTH:
			makeAlignNORTH(maxWidth, maxHeight);
			break;
		default:
			break;
		}
	}

	/**
	 * Set the current palette alignment. The preferred alignment is unchanged.
	 * 
	 * @param align the current alignment to use.
	 */
	public void setAlign(GAlign align) {
		if (isValid(align) && align != currAlign) {
			currAlign = align;
			updateControlPositions();
		}
	}

	/**
	 * Set the preferred alignment for the palette. <br>
	 * If the palette does not fit the screen then it will not be shown until the
	 * control is moved to a position it can be seen.
	 * 
	 * @param align the new preferred alignment
	 */
	public void setPrefAlign(GAlign align) {
		if (isValid(align) && align != prefAlign) {
			prefAlign = align;
			adjustAlignment();
		}
	}

	/**
	 * Will change the current alignment to the preferred alignment if it can be
	 * displayed fully on screen. If not it will attempt to use the opposite
	 * alignment.
	 */
	protected void adjustAlignment() {
		// Change to preferred alignment if possible
		if (currAlign != prefAlign) {
			if (fitsScreen(prefAlign)) {
				currAlign = prefAlign;
				updateControlPositions();
			}
		} else {
			// We are using the preferred align so see if it fits the screen.
			// If not change to the opposite alignment if that fits.but the
			if (!fitsScreen(prefAlign) && fitsScreen(opposite(prefAlign))) {
				currAlign = opposite(prefAlign);
				updateControlPositions();
			}
		}
	}

	/**
	 * For any valid alignment it returns the alignment on the opposite side of the
	 * tab (icon).
	 * 
	 * @param align any valid alignment
	 * @return the opposite alignment
	 */
	private GAlign opposite(GAlign align) {
		switch (align) {
		case NORTH:
			return GAlign.SOUTH;
		case SOUTH:
			return GAlign.NORTH;
		case EAST:
			return GAlign.WEST;
		case WEST:
			return GAlign.EAST;
		default:
			return null;
		}
	}

	/**
	 * Valid alignments are either NORTH, SOUTH, EAST or WEST
	 * 
	 * @param align the alignment to test
	 * @return true if valid alignment
	 */
	protected boolean isValid(GAlign align) {
		switch (align) {
		case NORTH:
		case SOUTH:
		case EAST:
		case WEST:
			return true;
		default:
			return false;
		}
	}

	/**
	 * Attempts to add a control to the end of the palette. If the position is
	 * invalid then it is silently ignored.
	 * 
	 * @param c the control to add
	 */
	public void addControl(GAbstractControl c) {
		if (addControlImpl(c))
			updateControlPositions();
	}

	/**
	 * Attempts to insert a control at a given index position. If the position is
	 * invalid or the control is a duplicate then it is silently ignored.
	 * 
	 * Duplicates are ignored.
	 * 
	 * @param pos must be &ge;0 and &le;number of controls already present
	 * @param c   the control to add
	 */
	public void addControl(int pos, GAbstractControl c) {
		if (addControlImpl(pos, c))
			updateControlPositions();
	}

	/**
	 * Attempts to add one or more controls to the end of the palette. If the
	 * position is invalid or the control is a duplicate then it is silently
	 * ignored.
	 * 
	 * @param controls the controls to add
	 * @return true if one of more controls have been added else false
	 */
	public void addControls(GAbstractControl... controls) {
		boolean success = false;
		for (GAbstractControl c : controls) {
			success |= addControlImpl(c);
		}
		if (success)
			updateControlPositions();
	}

	/**
	 * Attempts to add one or more controls to the end of the palette. If the
	 * position is invalid or the control is a duplicate then it is silently
	 * ignored.
	 * 
	 * @param pos
	 * @param controls the controls to add
	 */
	public void addControls(int pos, GAbstractControl... controls) {
		boolean success = false;
		for (GAbstractControl c : controls) {
			if (addControlImpl(pos, c)) {
				pos++;
				success = true;
			}
		}
		if (success)
			updateControlPositions();
	}

	/**
	 * Add a control to the end of the controls already present.
	 * 
	 * @param c the control to add
	 * @return true if added else false
	 */
	private boolean addControlImpl(GAbstractControl c) {
		if (!children.contains(c)) {
			prepareControl(c);
			children.addLast(c);
			return true;
		}
		return false;
	}

	/**
	 * Insert a control at a given index position
	 * 
	 * @param pos must be &ge;0 and &le;number of controls already present
	 * @param c   the control to add
	 * @return true if added else false
	 */
	private boolean addControlImpl(int pos, GAbstractControl c) {
		if (!children.contains(c) && pos >= 0 && pos <= children.size()) {
			prepareControl(c);
			children.add(pos, c);
			return true;
		}
		return false;
	}

	/**
	 * Prepares the control to be ready for adding to palette.
	 * 
	 * @param c the control to prepare
	 * @return the prepared control
	 */
	private GAbstractControl prepareControl(GAbstractControl c) {
		// Prepare control to be added to list
		c.parent = this;
		c.setZ(z);
		// Parent will now be responsible for drawing
		c.registeredMethods &= (ALL_METHOD - DRAW_METHOD);
		c.addToParent(this);
		return c;
	}

	/**
	 * Removes a control from the palette. If successful the control will unlinked
	 * from the palette and made invisible but not disposed of. <br>
	 * If the control is no longer needed then use the
	 * 
	 * <pre>
	 * control.dispose()
	 * </pre>
	 * 
	 * method after removal.
	 * 
	 * @param c the control to remove
	 * @return
	 */
	public boolean remove(GAbstractControl c) {
		if (children.remove(c)) {
			c.parent = null;
			c.setVisible(false);
			updateControlPositions();
			children.remove(c);
			return true;
		}
		return false;
	}

	/**
	 * Removes a control from the palette at a given index position. If successful
	 * the control will unlinked from the palette and made invisible but not
	 * disposed of. <br>
	 * If the control is no longer needed then use the
	 * 
	 * <pre>
	 * control.dispose()
	 * </pre>
	 * 
	 * method after removal. <br>
	 * The index will be 0 (zero) for the control nearest the tab no matter what
	 * alignment is in effect. <br>
	 * 
	 * @param idx the control's index position
	 * @return the removed control or null index position is non-existent
	 */
	public GAbstractControl remove(int idx) {
		try {
			GAbstractControl c = children.remove(idx);
			c.parent = null;
			c.setVisible(false);
			updateControlPositions();
			return c;
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}

	/**
	 * See if the control fits inside the window based if it's current alignment is
	 * the same as passed in the parameter. <br>
	 * 
	 * @param align
	 * @return
	 */
	public boolean fitsScreen(GAlign align) {
		boolean inside = false;
		if (isValid(align)) {
			switch (align) {
			case NORTH:
				inside = cy - halfHeight - pLengthNS > 0;
				break;
			case SOUTH:
				inside = cy + halfHeight + pLengthNS < winApp.height;
				break;
			case EAST:
				inside = cx + halfWidth + pLengthEW < winApp.width;
				break;
			case WEST:
				inside = cx - halfWidth - pLengthEW > 0;
				break;
			default:
				break;
			}
		}
		return inside;
	}

	/** This method is ignored for GControlPalette */
	public void addControl(GAbstractControl c, float x, float y) {
	}

	/** This method is ignored for GControlPalette */
	public void addControl(GAbstractControl c, float x, float y, float angle) {
	}

	/**
	 * This needs to be called if the tab text is changed
	 */
	private void calcHotSpots() {
		hotspots = new HotSpot[] { new HSrect(TAB, -1, -1, width + 1, height + 1), // tab icon area
				new HSrect(PALETTE, 0, 0, 0, 0) // control palette region
		};
	}

	/**
	 * Set the padding round each control. Default is 3
	 * 
	 * @param pad must be &ge;2
	 */
	public void setPadding(int pad) {
		if (pad >= 2) {
			padding = pad;
		}
	}

	/**
	 * Set the border weight and the corner radii to apply. The border must be at
	 * least 1 pixel so you can see the palette area limits. A radii value of 0 will
	 * result in square corners.
	 * 
	 * @param weight must be &ge;1
	 * @param radii  must be &ge;0
	 */
	public void setBorder(float weight, float radii) {
		bdrWeight = weight;
		bdrCorner = radii;
	}

	public void mouseEvent(MouseEvent event) {
		if (!visible || !enabled || !available)
			return;

		calcTransformedOrigin(winApp.mouseX, winApp.mouseY);
		currSpot = whichHotSpot(ox, oy);

		// Only change cursor if over the tab
		if (currSpot == 0 || focusIsWith == this)
			cursorIsOver = this;
		else if (cursorIsOver == this)
			cursorIsOver = null;

		switch (event.getAction()) {
		case MouseEvent.PRESS:
			if (focusIsWith != this && currSpot == 0 && z >= focusObjectZ()) {
				takeFocus();
				beingDragged = false;
			}
			break;
		case MouseEvent.CLICK:
			if (focusIsWith == this) {
				rVisible = false;
				// fireEvent(this, GEvent.EXPANDED); // we could have an event if the tab is
				// clicked on
				bufferInvalid = true;
				beingDragged = false;
				// This component does not keep the focus when clicked
				loseFocus(parent);
			}
			break;
		case MouseEvent.RELEASE: // After dragging NOT clicking
			if (focusIsWith == this) {
				if (beingDragged) {
					rVisible = false;
					beingDragged = false;
					loseFocus(parent);
				}
			}
			break;
		case MouseEvent.MOVE:
			if (focusIsWith != this && currSpot == 0) {
				rVisible = true;
				takeFocus();
			}
			if (focusIsWith == this && currSpot < 0) {
				rVisible = false;
				loseFocus(parent);
			}
			break;
		case MouseEvent.DRAG:
			if (focusIsWith == this) {// && parent == null){
				// Maintain centre for drawing purposes
				cx += (winApp.mouseX - winApp.pmouseX);
				cy += (winApp.mouseY - winApp.pmouseY);
				// Update x and y positions
				x = cx - width / 2;
				y = cy - height / 2;
				beingDragged = true;
				adjustAlignment();
				// fireEvent(this, GEvent.DRAGGED);
			}
			break;
		}
	}

	/**
	 * Draw the icon and is the mouse is over it draw the palette..
	 */
	public void draw() {
		if (!visible)
			return;
		// Update buffer if invalid
		updateBuffer();

		winApp.push();
		// Perform the rotation
		winApp.translate(cx, cy);
		winApp.rotate(rotAngle);

		// Draw buffer
		winApp.imageMode(PApplet.CENTER);
		if (alphaLevel < 255) {
			winApp.tint(TINT_FOR_ALPHA, alphaLevel);
		}
		winApp.image(buffer, 0, 0);

		if (rVisible) {
			// Setup border
			winApp.stroke(palette[3].getRGB());
			winApp.strokeWeight(bdrWeight);
			// Setup fill
			if (opaque) {
				winApp.fill(palette[6].getRGB());
			} else {
				winApp.noFill();
			}
			winApp.rect(rX, rY, rW, rH, bdrCorner);
			// Draw the children
			drawChildren();
		}
		winApp.pop();
	}

	protected void updateBuffer() {
		if (bufferInvalid) {
			bufferInvalid = false;
			buffer.beginDraw();
			buffer.clear();
			// Draw the tab head
			if (opaque) {
				buffer.fill(palette[6].getRGB());
				buffer.noStroke();
				buffer.rect(0, 0, width, height);
			}
			buffer.image(icon.getFrame(), 0, 0);
			buffer.endDraw();
		}
	}

	/**
	 * Align the control palette on the NORTH side of the tab (icon).
	 * 
	 * @param max_width  the width of the widest control
	 * @param max_height the height of the tallest control
	 */
	private void makeAlignNORTH(float max_width, float max_height) {
		float x0, y0, px, py;
		x0 = halfWidth + 1;
		y0 = 0;
		py = y0;
		for (GAbstractControl c : children) {
			py -= padding + c.getHeight();
			px = x0 - c.getWidth() / 2;
			c.moveTo(px, py);
			py -= padding;
		}
		rW = max_width + 2 * padding;
		rH = y0 - py;
		rX = -rW / 2;
		rY = -halfHeight - rH;
		hotspots[1] = new HSrect(PALETTE, rX + halfWidth, rY + halfHeight, rW, rH);
	}

	/**
	 * Align the control palette on the SOUTH side of the tab (icon).
	 * 
	 * @param max_width  the width of the widest control
	 * @param max_height the height of the tallest control
	 */
	private void makeAlignSOUTH(float max_width, float max_height) {
		float x0, y0, px, py;
		x0 = halfWidth + 1;
		y0 = height;
		py = y0;
		for (GAbstractControl c : children) {
			py += padding;
			px = x0 - c.getWidth() / 2;
			c.moveTo(px, py);
			py += c.getHeight() + padding;
		}
		rW = max_width + 2 * padding;
		rH = py - y0;
		rX = -rW / 2;
		rY = halfHeight;
		hotspots[1] = new HSrect(PALETTE, rX + halfWidth, rY + halfHeight, rW, rH);
	}

	/**
	 * Align the control palette on the EAST side of the tab (icon).
	 * 
	 * @param max_width  the width of the widest control
	 * @param max_height the height of the tallest control
	 */
	private void makeAlignEAST(float max_width, float max_height) {
		float x0, y0, px, py;
		x0 = width;
		y0 = halfHeight + 1;
		px = x0;
		for (GAbstractControl c : children) {
			px += padding;
			py = y0 - c.getHeight() / 2;
			c.moveTo(px, py);
			px += c.getWidth() + padding;
		}
		rW = px - x0;
		rH = max_height + 2 * padding;
		rX = halfWidth;
		rY = -rH / 2;
		hotspots[1] = new HSrect(PALETTE, rX + halfWidth, rY + halfHeight, rW, rH);
	}

	/**
	 * Align the control palette on the WEST side of the tab (icon).
	 * 
	 * @param max_width  the width of the widest control
	 * @param max_height the height of the tallest control
	 */
	private void makeAlignWEST(float max_width, float max_height) {
		float x0, y0, px, py;
		x0 = 0;
		y0 = halfHeight + 1;
		px = x0;
		for (GAbstractControl c : children) {
			px -= padding + c.getWidth();
			py = y0 - c.getHeight() / 2;
			c.moveTo(px, py);
			px -= padding;
		}
		rW = x0 - px;
		rH = max_height + 2 * padding;
		rX = -halfWidth - rW;
		rY = -rH / 2;
		hotspots[1] = new HSrect(PALETTE, rX + halfWidth, rY + halfHeight, rW, rH);
	}
}
