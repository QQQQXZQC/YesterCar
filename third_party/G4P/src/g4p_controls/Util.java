/*
  Part of the G4P library for Processing 
  	http://www.lagers.org.uk/g4p/index.html
	http://sourceforge.net/projects/g4p/files/?source=navbar

  Copyright (c) 2019 Peter Lager

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
import processing.event.KeyEvent;

/**
 * Simple utility class with various methods to help in debugging.
 * 
 * @author Peter Lager
 *
 */
public class Util implements PConstants {

	/**
	 * Get a textual description of a KeyEvent object
	 * 
	 * @param event the event to describe
	 * @return event description
	 */
	public static String key_event_desc(KeyEvent event) {
		StringBuilder s = new StringBuilder("KEY: ");
		s.append(" Type: ").append(key_action(event.getAction()));
		s.append(" Keycode: ").append(event.getKeyCode());
		int key = event.getKey();
		if (key >= 32 && key < 127) {
			s.append(" Key: '").append((char) key).append("'");
		} else {
			s.append(" ASCII value: ").append(key);
		}
		int mods = event.getModifiers();
		if (mods != 0) {
			s.append("[ ").append(mod(mods)).append("]");
		}
//		s.append(" Millis: ").append(event.getMillis());
		return s.toString();
	}

	/**
	 * Provide a textual list of modifiers associated with an event
	 * 
	 * @param modifiers the bit encoded modifiers
	 * @return the modifiers as text
	 */
	public static String mod(int modifiers) {
		String s = "";
		if ((modifiers & 1) != 0) {
			s = "shift ";
		}
		if ((modifiers & 2) != 0) {
			s += "ctrl  ";
		}
		if ((modifiers & 4) != 0) {
			s = "meta ";
		}
		if ((modifiers & 8) != 0) {
			s = "alt ";
		}
		return s;
	}

	/**
	 * Provide a text description of the KeyEvent action
	 * 
	 * @param action the action id
	 * @return the action as text
	 */
	public static String key_action(int action) {
		switch (action) {
		case 1:
			return "press";
		case 2:
			return "release";
		case 3:
			return "type";
		}
		return "type?";
	}

	/**
	 * Provide a text description of the MouseEvent action
	 * 
	 * @param action the action id
	 * @return action the action as text
	 */
	public static String mouse_action(int action) {
		switch (action) {
		case 1:
			return "press";
		case 2:
			return "release";
		case 3:
			return "click";
		case 4:
			return "drag";
		case 5:
			return "move";
		case 6:
			return "enter";
		case 7:
			return "exit";
		case 8:
			return "wheel";
		}
		return "type?";
	}

	/**
	 * Provide a text description of the event flavour
	 * 
	 * @param ef event flavour id
	 * @return the flavour as text
	 */
	public static String event_flavor(int ef) {
		switch (ef) {
		case 1:
			return "key";
		case 2:
			return "mouse";
		case 3:
			return "touch";
		}
		return "type?";
	}

}
