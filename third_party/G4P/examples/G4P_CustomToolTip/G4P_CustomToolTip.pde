/**
 This sketch demonstrates the creation of a user defined tool tips for some
 G4P controls.
 
 The technique requires the user to create their own class that extends
 the GToolTip class and override to refine some of its methods. This requires
 some knowledge of object orientation so not for the total novice programmer.
 
 In the example a new class called MyToolTip, has been created in it's own tab.
 
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
  size(480, 320);
  surface.setTitle("Custom Tool Tips");
  createGUI();
  G4P.setTipShowTime(4000);
  makeToolTips();
}

public void draw() {
  background(180, 180, 220);
}

public void makeToolTips() {
  MyToolTip mtt = new MyToolTip(this, 0, 0, 1000, 100, "Bug report");
  btn1.setTip(mtt, GAlign.LEFT, GAlign.NORTH, 2);

  mtt = new MyToolTip(this, 0, 0, 1000, 100, "Light\nSwitch");
  itb1.setTip(mtt, GAlign.CENTER, GAlign.SOUTH, 2);

  mtt = new MyToolTip(this, 0, 0, 1000, 100,
    "Drag the mouse\nhorizontally\nto rotate the knob");
  knob1.setTip(mtt, knob1.getWidth()/2 + 22, PI * 1.5f);

  mtt = new MyToolTip(this, 0, 0, 1000, 100, "Choose a number between 1 and 20");
  csdr1.setTip(mtt, GAlign.RIGHT, GAlign.SOUTH, 2);
}


public void createGUI() {
  G4P.messagesEnabled(false);
  G4P.setGlobalColorScheme(GCScheme.BLUE_SCHEME);
  G4P.setMouseOverEnabled(false);
  surface.setTitle("Sketch Window");
  btn1 = new GButton(this, 60, 90, 100, 50);
  btn1.setIcon("bug7.png", 1, GAlign.EAST, GAlign.CENTER, GAlign.MIDDLE);
  btn1.tag = "GButton";
  itb1 = new GImageToggleButton(this, 220, 90);
  itb1.tag = "GImageToggleButton";
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
  csdr1.tag = "GCustomSlider";
  knob1 = new GKnob(this, 320, 60, 100, 100, 0.8f);
  knob1.setTurnRange(110, 70);
  knob1.setTurnMode(GKnob.CTRL_HORIZONTAL);
  knob1.setSensitivity(1);
  knob1.setShowArcOnly(false);
  knob1.setOverArcOnly(false);
  knob1.setIncludeOverBezel(false);
  knob1.setShowTrack(true);
  knob1.setLimits(0.0f, 0.0f, 20.0f);
  knob1.setNbrTicks(19);
  knob1.setStickToTicks(true);
  knob1.setShowTicks(true);
  knob1.setLocalColorScheme(GCScheme.GREEN_SCHEME);
  knob1.setOpaque(false);
  knob1.tag = "GKnpb";
}

GButton btn1;
GImageToggleButton itb1;
GCustomSlider csdr1;
GKnob knob1;
