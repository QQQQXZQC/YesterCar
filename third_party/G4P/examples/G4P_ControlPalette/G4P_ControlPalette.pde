import g4p_controls.*;

/**
 * This sketch demonstrates the GControlPalette control introduced in 
 * G4P V4.3.3 
 * GControlPalette
 * When collapsed the GControlPalette's position is shown by an icon (GIcon) 
 * when the mouse passes over the icon the palette expands in one of the 4  
 * cardinalcompass directions. The user can specify the preferred direction 
 * and this will be used if the entire palette is visible. If not it will use 
 * the diametrically opposite direction. The control can be moved by dragging 
 * its icon.
 * The controls in the top left control the preferred palette direction and its 
 * background opacity. The green / red dot indicates whether the palette for the
 * indicated direction would be fully visible at its current postion.
 *
 * for Processing V3+
 * (c) 2020 Peter Lager
 *
 */

GControlPalette cp0;
// Now the controls to appear on the palette
GButton btn0, btn1;
GCustomSlider sdr0;
GSpinner spn0;

void setup() {
  size(640, 480);
  surface.setTitle("Control Palette Demo");
  G4P.messagesEnabled(false);
  G4P.setDisplayFont("Arial", G4P.PLAIN, 14); 
  cursor(CROSS);
  textSize(16);
  createPalette();
  createPaletteAttributeGUI();
}

void draw() {
  background(255, 255, 230);
  stroke(0xFF000088);
  strokeWeight(2);
  fill(0xFFAAAAFF);
  rect(4, 4, 190, 155, 6);
  fill( cp0.fitsScreen(GAlign.NORTH) ? 0xFF00FF00 : 0xFFFF0000);
  stroke(0);
  strokeWeight(1);
  ellipse(60, 58, 8, 8);
  fill( cp0.fitsScreen(GAlign.SOUTH) ? 0xFF00FF00 : 0xFFFF0000);
  ellipse(60, 78, 8, 8);
  fill( cp0.fitsScreen(GAlign.EAST) ? 0xFF00FF00 : 0xFFFF0000);
  ellipse(60, 98, 8, 8);
  fill( cp0.fitsScreen(GAlign.WEST) ? 0xFF00FF00 : 0xFFFF0000);
  ellipse(60, 118, 8, 8);
}

// Create the control palette and and add some controls including a spinner
void createPalette() {
  GAnimIcon ai = new GAnimIcon(this, "watch50.png", 7, 4, 100);
  ai.animate("ALL");
  cp0 = new GControlPalette(this, ai, GAlign.EAST, width/2 - 30, height/2);
  cp0.setOpaque(true);
  btn0 = new GButton(this, 0, 0, 24, 18, "0");
  btn1 = new GButton(this, 0, 0, 24, 18, "1");
  sdr0 = new GCustomSlider(this, 0, 0, 80, 52);
  sdr0.setShowDecor(true, true, true, true);
  spn0 = new GSpinner(this, 0, 0, 80, 20, 20);
  spn0.setLimits(0, -1000, 1000, 50);
  cp0.addControls(btn0, sdr0, spn0, btn1);
}

void createPaletteAttributeGUI() {
  // Legend labels for palette configuration
  GLabel lbl = new GLabel(this, 8, 160, 122, 40);
  lbl.setRotation(1.5 * PI);
  lbl.setText("Palette fits on screen indicators");
  lbl.setTextAlign(GAlign.CENTER, GAlign.CENTER);
  lbl = new GLabel(this, 70, 8, 130, 40, "Prefered palette alignment");
  lbl.setTextAlign(GAlign.LEFT, GAlign.CENTER);
  // Controls to control palette alignment
  GToggleGroup tg = new GToggleGroup();
  GOption n, s, e, w;
  n = new GOption(this, 70, 50, 70, 16, "North");
  n.tag = "N";
  s = new GOption(this, 70, 70, 70, 16, "South");
  s.tag = "S";
  e = new GOption(this, 70, 90, 70, 16, "East");
  e.tag = "E";
  w = new GOption(this, 70, 110, 70, 16, "West");
  w.tag = "W";
  tg.addControls(n, s, e, w);
  e.setSelected(true);
  GCheckbox cbx = new GCheckbox(this, 70, 130, 80, 16, "Opaque");
  cbx.tag = "OPAQUE";
  cbx.setSelected(true);
}

void handleToggleControlEvents(GToggleControl option, GEvent event) { 
  switch(option.tag) {
  case "N":
    cp0.setPrefAlign(GAlign.NORTH);
    break;
  case "S":
    cp0.setPrefAlign(GAlign.SOUTH);
    break;
  case "E":
    cp0.setPrefAlign(GAlign.EAST);
    break;
  case "W":
    cp0.setPrefAlign(GAlign.WEST);
    break;
  case "OPAQUE":
    cp0.setOpaque(option.isSelected());
    break;
  }
}
