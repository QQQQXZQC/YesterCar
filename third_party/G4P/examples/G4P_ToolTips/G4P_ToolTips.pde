/**
 This sketch demonstrates the creation of tool tips for some G4P controls.
 
 Note:  Tool tips cannot be added to the following G4P classes (controls)
 GControlPalette, GDropList, GLabel, GPanel GPassword, GScrollbar and GToolTip
 
 All the other G4P controls can have their own tool tip. By default all tool 
 tips are horizontally aligned even if the control is rotated. There is an 
 option to enable the tool tip to be rotated with the control.
 
 There are other methods to set the default font and display time in the G4P 
 class.
 
 for Processing V3+
 created 2023 Peter Lager
 
 */

import g4p_controls.*;

public void setup() {
  size(480, 320, JAVA2D);
  surface.setTitle("Demo of Tool Tips");
  createGUI();
  makeToolTips();
}

public void draw() {
  background(180, 180, 220);
}

public void makeToolTips() {
  btn1.setTip("Bug report", GAlign.LEFT, GAlign.NORTH, 2);
  itb1.setTip("Light\nSwitch", GAlign.CENTER, GAlign.SOUTH, 2);
  knob1.setTip("Drag the mouse\nhorizontally\nto rotate the knob",
    knob1.getWidth()/2 + 22, PI * 1.5f);
  csdr1.setTip("Choose a number between 1 and 20", GAlign.RIGHT, GAlign.NORTH, 2);
}

public void createGUI() {
  G4P.messagesEnabled(false);
  G4P.setGlobalColorScheme(GCScheme.BLUE_SCHEME);
  G4P.setMouseOverEnabled(false);
  surface.setTitle("Sketch Window");
  btn1 = new GButton(this, 60, 90, 100, 50);
  btn1.setIcon("bug7.png", 1, GAlign.EAST, GAlign.CENTER, GAlign.MIDDLE);
  itb1 = new GImageToggleButton(this, 220, 90);
  csdr1 = new GCustomSlider(this, 60, 190, 360, 60, "red_yellow18px");
  csdr1.setLocalColorScheme(GCScheme.RED_SCHEME);
  csdr1.setShowValue(true);
  csdr1.setShowLimits(true);
  csdr1.setLimits(10, 1, 20);
  csdr1.setNbrTicks(18);
  csdr1.setStickToTicks(true);
  csdr1.setShowTicks(true);
  csdr1.setNumberFormat(G4P.INTEGER, 0);
  csdr1.setOpaque(true);
  knob1 = new GKnob(this, 320, 60, 100, 100, 0.8f);
  knob1.setTurnRange(110, 70);
  knob1.setTurnMode(GKnob.CTRL_HORIZONTAL);
  knob1.setSensitivity(1);
  knob1.setShowArcOnly(false);
  knob1.setOverArcOnly(false);
  knob1.setIncludeOverBezel(false);
  knob1.setShowTrack(true);
  knob1.setLimits(0.0, 0.0, 20.0);
  knob1.setNbrTicks(19);
  knob1.setStickToTicks(true);
  knob1.setShowTicks(true);
  knob1.setLocalColorScheme(GCScheme.GREEN_SCHEME);
  knob1.setOpaque(false);
}

GButton btn1;
GImageToggleButton itb1;
GCustomSlider csdr1;
GKnob knob1;
