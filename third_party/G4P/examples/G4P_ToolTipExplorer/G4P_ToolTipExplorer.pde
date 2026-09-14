import g4p_controls.*;
import java.awt.Font;

/**
 This program demonstrates the two methods for setting the position of
 a tool tip.
 
 The sketch starts using the COMPASS style where the tip is either above
 (GAlign.NORTH) or below (GAlign.SOUTH). The horizontal alignment with the
 control is either GAlign.LEFT, GAlign.RIGHT or GAlign.CENTER.
 
 If the control is rotated then the RADIAL style is probably best for
 positioning the tip. The radial distance is the distance between the
 control and tip centres. The radial angle is the angle made by the vector
 control_centre --> tip_centre.
 
 for Processing V3+
 (c) 2024 Peter Lager
 */

public void setup() {
  size(600, 480, JAVA2D);
  codefont = new Font("Courier", G4P.PLAIN, 15);
  createGUI();
  customGUI();
  updateCompassStyle();
}

public void draw() {
  background(255, 255, 192);
  drawTipShadow();
  stroke(0, 0, 128);
  strokeWeight(3);
  fill(100, 255, 255);
  rect(10, 10, 120, 160, 6);
  rect(390, 10, 200, 150, 6);
  rect(390, 170, 200, 150, 6);
  rect(390, 330, 200, 60, 6);
}

public void drawTipShadow() {
  GToolTip tip = sdr.getTip();
  float sdrAng = sdr.getRotation();
  push();
  fill(0, 48);
  noStroke();
  translate(sdr.getCX(), sdr.getCY());
  rotate(sdrAng);
  translate(tip.getCX(), tip.getCY());
  if (tip.isFixedHorz())
    rotate(-sdrAng);
  rectMode(CENTER);
  rect(0, 0, tip.getWidth()-2, tip.getHeight()-2, 4);
  pop();
}

public void knbTurn(GKnob source, GEvent event) {
  float angle = source.getValueI();
  sdr.setRotation(radians(angle), GControlMode.CENTER);
  lblSdrAng.setText("" + angle);
}

public void resetAng(GButton source, GEvent event) {
  sdr.setRotation(0, GControlMode.CENTER);
  lblSdrAng.setText("0");
  knb.setValue(0);
}

public void updateCompassStyle() {
  GAlign h = GAlign.getFromText(listHorz.getSelectedText());
  GAlign v = GAlign.getFromText(listVert.getSelectedText());
  float g = sdrGap.getValueF();
  sdr.setTipPos(h, v, g);
  StringBuilder s = new StringBuilder("sdr.setTipPos(");
  s.append("GAlign.").append(listHorz.getSelectedText()).append(", ")
    .append("GAlign.").append(listVert.getSelectedText()).append(", ")
    .append(""+sdrGap.getValueI()).append(");");
  if (!cbxKeepLevel.isSelected()) {
    s.append("\nsdr.setTipHorz(false);");
  }
  if (!cbxEnableTip.isSelected()) {
    s.append("\nsdr.setTipEnabled(false);");
  }		
  tipPosSourceCode.setText(s.toString());
}

public void updateRadialStyle() {
  float rd  = sdrRadDist.getValueF();
  float ang = radians(sdrRadAng.getValueF());
  sdr.setTipPos(rd, ang);
  StringBuilder s = new StringBuilder("sdr.setTipPos(");
  s.append(""+sdrRadDist.getValueI()).append(", ")
    .append("radians("+sdrRadAng.getValueI()).append("));");
  if (!cbxKeepLevel.isSelected()) {
    s.append("\nsdr.setTipHorz(false);");
  }
  if (!cbxEnableTip.isSelected()) {
    s.append("\nsdr.setTipEnabled(false);");
  }
  tipPosSourceCode.setText(s.toString());
}

public void chooseCompassStyle(GOption source, GEvent event) {
  if (event == GEvent.SELECTED) {
    grpCompass.fadeTo(0, 250, 255);
    grpRadial.fadeTo(0, 250, 96);
    updateCompassStyle();
  }
}

public void horzAlign(GDropList source, GEvent event) {
  updateCompassStyle();
}

public void vertAlign(GDropList source, GEvent event) {
  updateCompassStyle();
}

public void changeGap(GSlider source, GEvent event) {
  lblGap.setText("" + source.getValueI());
  updateCompassStyle();
}

public void chooseRadialStyle(GOption source, GEvent event) {
  println("RADIAL - GOption >> GEvent." + event + " @ " + millis());
  if (event == GEvent.SELECTED) {
    grpCompass.fadeTo(0, 250, 96);
    grpRadial.fadeTo(0, 250, 255);
    updateRadialStyle();
  }
}

public void radDistChange(GSlider source, GEvent event) {
  lblRadDist.setText("" + source.getValueI());
  updateRadialStyle();
}

public void sdrRadAngChange(GSlider source, GEvent event) {
  float angle = source.getValueI();
  lblRadAng.setText("" + round(angle));
  updateRadialStyle();
}

public void keepTipLevel(GCheckbox source, GEvent event) {
  sdr.setTipHorz(source.isSelected());
  if (optCompass.isSelected())
    updateCompassStyle();
  else
    updateRadialStyle();
}

public void enableTips(GCheckbox source, GEvent event) {
  sdr.setTipEnabled(source.isSelected());
  if (optCompass.isSelected())
    updateCompassStyle();
  else
    updateRadialStyle();
}

public void customGUI() {
  knb.setTip("Rotate slider", 40, 1.5f * PI);
  sdr.setTip("Examination mark (%)", GAlign.CENTER, GAlign.NORTH, 0);
  grpCompass = new GGroup(this);
  grpCompass.addControls(listHorz, listVert, sdrGap, _alignment$, _gap$, lblGap);
  grpRadial = new GGroup(this);
  grpRadial.addControls(sdrRadDist, sdrRadAng, _radDist$, _angle$, lblRadDist, lblRadAng );
  grpRadial.fadeTo(10, 200, 127);
}

public void createGUI() {
  G4P.messagesEnabled(false);
  G4P.setGlobalColorScheme(GCScheme.BLUE_SCHEME);
  G4P.setMouseOverEnabled(false);
  surface.setTitle("Tool Tip Explorer");
  sdr = new GSlider(this, 100, 270, 200, 60, 12);
  sdr.setShowValue(true);
  sdr.setShowLimits(true);
  sdr.setLimits(40, 0, 100);
  sdr.setShowTicks(true);
  sdr.setNumberFormat(G4P.INTEGER, 0);
  sdr.setLocalColorScheme(GCScheme.PURPLE_SCHEME);
  sdr.setOpaque(true);
  knb = new GKnob(this, 20, 20, 100, 100, 0.8f);
  knb.setTurnRange(110, 70);
  knb.setTurnMode(GKnob.CTRL_HORIZONTAL);
  knb.setSensitivity(1);
  knb.setShowArcOnly(false);
  knb.setOverArcOnly(false);
  knb.setIncludeOverBezel(false);
  knb.setShowTrack(true);
  knb.setLimits(0f, 0f, 360f);
  knb.setNbrTicks(37);
  knb.setStickToTicks(true);
  knb.setShowTicks(true);
  //knb.setOpaque(true);
  knb.addEventHandler(this, "knbTurn");
  lblSdrAng = new GLabel(this, 20, 120, 40, 20);
  lblSdrAng.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  lblSdrAng.setText("0");
  lblSdrAng.setOpaque(true);
  _degrees$ = new GLabel(this, 60, 120, 60, 20);
  _degrees$.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  _degrees$.setText("Degrees");
  _degrees$.setOpaque(true);
  rbtnResetAng = new GButton(this, 20, 140, 100, 20);
  rbtnResetAng.setText("RESET");
  rbtnResetAng.addEventHandler(this, "resetAng");
  // RADIAL style
  optRadial = new GOption(this, 400, 180, 180, 30);
  optRadial.setIconPos(GAlign.EAST);
  optRadial.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  optRadial.setText("RADIAL STYLE");
  optRadial.setOpaque(true);
  optRadial.addEventHandler(this, "chooseRadialStyle");
  _radDist$ = new GLabel(this, 400, 220, 140, 20);
  _radDist$.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  _radDist$.setText("Radial Distance");
  _radDist$.setOpaque(true);
  lblRadDist = new GLabel(this, 540, 220, 40, 20);
  lblRadDist.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  lblRadDist.setText("40");
  lblRadDist.setOpaque(true);
  sdrRadDist = new GSlider(this, 400, 240, 180, 20, 10);
  sdrRadDist.setLimits(40, 0, 150);
  sdrRadDist.setNbrTicks(16);
  sdrRadDist.setShowTicks(true);
  sdrRadDist.setNumberFormat(G4P.INTEGER, 0);
  sdrRadDist.addEventHandler(this, "radDistChange");
  _angle$ = new GLabel(this, 400, 270, 140, 20);
  _angle$.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  _angle$.setText("Angle");
  _angle$.setOpaque(true);
  lblRadAng = new GLabel(this, 540, 270, 40, 20);
  lblRadAng.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  lblRadAng.setText("90");
  lblRadAng.setOpaque(true);
  sdrRadAng = new GSlider(this, 400, 290, 180, 20, 10);
  sdrRadAng.setLimits(90, 0, 360);
  sdrRadAng.setNbrTicks(19);
  sdrRadAng.setShowTicks(true);
  sdrRadAng.setNumberFormat(G4P.INTEGER, 0);
  sdrRadAng.addEventHandler(this, "sdrRadAngChange");

  // COMPASS style
  optCompass = new GOption(this, 400, 20, 180, 30);
  optCompass.setIconPos(GAlign.EAST);
  optCompass.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  optCompass.setText("COMPASS STYLE");
  optCompass.setOpaque(true);
  optCompass.addEventHandler(this, "chooseCompassStyle");
  _alignment$ = new GLabel(this, 400, 60, 180, 20);
  _alignment$.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  _alignment$.setText("Alignment");
  _alignment$.setOpaque(true);
  listHorz = new GDropList(this, 400, 80, 90, 80, 3, 10);
  listHorz.setItems(new String[] { "LEFT", "CENTER", "RIGHT" }, 1);
  listHorz.addEventHandler(this, "horzAlign");
  listVert = new GDropList(this, 490, 80, 90, 80, 3, 10);
  listVert.setItems(new String[] { "NORTH", "SOUTH" }, 0);
  listVert.addEventHandler(this, "vertAlign");
  _gap$ = new GLabel(this, 400, 110, 40, 20);
  _gap$.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  _gap$.setText("Gap");
  _gap$.setOpaque(true);
  sdrGap = new GSlider(this, 440, 110, 140, 40, 10);
  sdrGap.setLimits(0, 0, 100);
  sdrGap.setNbrTicks(11);
  sdrGap.setShowTicks(true);
  sdrGap.setNumberFormat(G4P.INTEGER, 0);
  sdrGap.addEventHandler(this, "changeGap");
  lblGap = new GLabel(this, 400, 130, 40, 20);
  lblGap.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  lblGap.setText("0");
  lblGap.setOpaque(true);

  cbxKeepLevel = new GCheckbox(this, 400, 340, 180, 20);
  cbxKeepLevel.setOpaque(true);
  cbxKeepLevel.setText("Keep Tip Level");
  cbxKeepLevel.setSelected(true);
  cbxKeepLevel.addEventHandler(this, "keepTipLevel");

  cbxEnableTip = new GCheckbox(this, 400, 360, 180, 20);
  cbxEnableTip.setOpaque(true);
  cbxEnableTip.setText("Enable SLIDER Tool Tip");
  cbxEnableTip.setSelected(true);
  cbxEnableTip.addEventHandler(this, "enableTips");

  tipPosSourceCode = new GLabel(this, 20, 405, 560, 64);
  tipPosSourceCode.setTextAlign(GAlign.LEFT, GAlign.CENTER);
  tipPosSourceCode.setText(" ----  ");
  tipPosSourceCode.setLocalColorScheme(GCScheme.GREEN_SCHEME);
  tipPosSourceCode.setOpaque(true);
  tipPosSourceCode.setFont(codefont);

  togStyle = new GToggleGroup();
  togStyle.addControls(optCompass, optRadial);
  optCompass.setSelected(true);
}



GSlider sdr, sdrRadDist, sdrRadAng, sdrGap;
GKnob knb;
GLabel lblSdrAng, lblGap, lblRadDist, lblRadAng, tipPosSourceCode;
GLabel _degrees$, _gap$, _radDist$, _angle$, _alignment$;
GButton rbtnResetAng;
GDropList listHorz, listVert;
GCheckbox cbxKeepLevel, cbxEnableTip;
GToggleGroup togStyle;
GOption optRadial, optCompass;
GGroup grpCompass, grpRadial;
Font codefont;
