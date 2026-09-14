import g4p_controls.*;

/**
 * This sketch demonstrates the GSpinner and modified GTextField controls
 * for numerical input appearing in G4P V4.3.3 
 * 
 * GSpinner
 * This is used to enter integer values within a user specified range. You can 
 * type a value in, use the spinner buttons or the mouse wheel when over the 
 * spinner.
 *
 * GTextField
 * This control has been modified so to accept a valid integer / decimal number
 * typed into the  control. Invalid numbers or numbers outside a user defined 
 * range result in the text and background colours being reversed.
 * 
 * for Processing V3
 * (c) 2020 Peter Lager
 *
 */

GSpinner spn0;
GTextField txf0, txf1;
GDropList drp0;

void setup() {
  size(520, 220);
  surface.setTitle("GSpinner and GTextField (numeric input) demo");
  G4P.messagesEnabled(false);
  G4P.setDisplayFont("Arial", G4P.PLAIN, 14); 
  G4P.setInputFont("Arial", G4P.PLAIN, 14); 
  cursor(CROSS);
  createSpinner();
  createNumericTextFieldControls();
}

void draw() {
  background(255, 255, 230);
  stroke(0xFF000088);
  strokeWeight(2);
  fill(0xFFAAAAFF);
  rect(4, 4, 300, 200, 6);
  rect(320, 4, 190, 200, 6);
}

// Create numeric text field controls for integer and decimal numbers
void createNumericTextFieldControls() {
  GLabel label = new GLabel(this, 10, 10, 280, 20);
  label.setText("Numeric Input using GTextField");
  label.setTextAlign(GAlign.CENTER, GAlign.CENTER);
  // Numeric fields
  label = new GLabel(this, 20, 40, 120, 38);
  label.setText("Integer\n-10,000 to 8000");
  label.setTextAlign(GAlign.CENTER, GAlign.CENTER);
  label.setOpaque(true);
  txf0 = new GTextField(this, 20, 80, 120, 20);
  txf0.setNumeric(-10000, 8000, 0);

  label = new GLabel(this, 160, 40, 120, 38);
  label.setText("Decimal\n-55.67 to 3141.59");
  label.setTextAlign(GAlign.CENTER, GAlign.CENTER);
  label.setOpaque(true);
  txf1 = new GTextField(this, 160, 80, 120, 20);
  txf1.setNumeric(-55.67f, 3141.59f, 0);
}

// Create a spinner
void createSpinner() {
  GLabel lbl1 = new GLabel(this, 335, 10, 150, 38);
  lbl1.setText("Numeric Input using GSpinner");
  lbl1.setTextAlign(GAlign.CENTER, GAlign.CENTER);
  lbl1 = new GLabel(this, 350, 50, 120, 20);
  lbl1.setText("-50 to 50 step 2");
  lbl1.setOpaque(true);
  lbl1.setTextAlign(GAlign.CENTER, GAlign.CENTER);
  spn0 = new GSpinner(this, 350, 70, 120, 20);
  spn0.setLimits(0, -50, 50, 2);
  lbl1 = new GLabel(this, 350, 114, 120, 44);
  lbl1.setText("Mouse wheel scroll direction");
  lbl1.setTextAlign(GAlign.CENTER, GAlign.CENTER);
  lbl1.setOpaque(true);
  drp0 = new GDropList(this, 350, 160, 120, 60, 2);
  drp0.setItems(new String[] { "FORWARD", "REVERSE" }, 0);
}

void handleDropListEvents(GDropList list, GEvent event) { 
  switch(list.getSelectedIndex()) {
  case 0:
    G4P.mouseWheelSpinnerDirection(G4P.FORWARD);
    break;
  case 1:
    G4P.mouseWheelSpinnerDirection(G4P.REVERSE);
    break;
  }
}

void handleTextEvents(GEditableTextControl source, GEvent event) { 
  if (source == spn0 && event == GEvent.CHANGED) {
    println("Spinner on panel value: " + spn0.getValue());
  } else if (source == txf0) {
    println("Integer input: " + txf0.getValueI());
  } else if (source == txf1) {
    println("Float input: " + txf1.getValueF());
  }
}
