/**
User defined class for a custom tool tip.

The only essential methods are the two constructors to call the parent
constructors.

The methods showTip() and draw() refine the parent class methods of the same 
name while the methodBuffer() replaces (overrides) the parent class method.
(We say a method refines if it calls the parent class method and adds additional 
code of its own.
*/

import java.awt.*;
import java.util.LinkedList;
//import g4p_controls.StyledString;

public class MyToolTip extends GToolTip {

  protected float t = 0.5, time = 0;

  /**
   * Create an empty tool tip use setText to change the text.
   *
   * @param theApplet the main sketch or GWindow control for this control
   * @param p0        x position based on control mode
   * @param p1        y position based on control mode
   * @param p2        x position or width based on control mode
   * @param p3        y position or height based on control mode
   */
  public MyToolTip(PApplet theApplet, float p0, float p1, float p2, float p3) {
    this(theApplet, p0, p1, p2, p3, "       ");
  }

  /**
   * Create a tool tip control.
   *
   * @param theApplet the main sketch or GWindow control for this control
   * @param p0        x position based on control mode
   * @param p1        y position based on control mode
   * @param p2        x position or width based on control mode
   * @param p3        y position or height based on control mode
   * @param text      the initial text to display
   */
  public MyToolTip(PApplet theApplet, float p0, float p1, float p2, float p3, String text) {
    super(theApplet, p0, p1, p2, p3, text);
  }

  public void draw() {
    // Animation coding
    if (isVisible() && millis() - time > 60) {
      time = millis();
      t += 0.1;
      t = t > 1 ? 0.1 : t;
      bufferInvalid = true;
    }
    super.draw(); // call the GToolTip.draw() method
  }

  protected void showTip() {
    t = 0;
    super.showTip(); // call the GAbstractControl.showTip() method
  }

  protected void updateBuffer() {
    if (bufferInvalid) {
      bufferInvalid = false;
      buffer.beginDraw();
      buffer.clear();
      Graphics2D g2d = buffer.g2;
      g2d.setFont(localFont);
      // Get the latest lines of text
      LinkedList<StyledString.TextLayoutInfo> lines = stext.getLines(g2d);
      g2d.setColor(palette[2]);
      g2d.fillRoundRect(1, 1, buffer.width - 3, buffer.height - 3, 6, 6);
      g2d.setColor(palette[5]);
      g2d.setStroke(new BasicStroke(4));
      g2d.drawRoundRect(1, 1, buffer.width - 4, buffer.height - 4, 6, 6);
      // ###################################################################
      // Custom code for this class
      int bw2 = buffer.width/2;
      int bh2 = buffer.height/2;
      int wt2 = (int) (bw2 * t);
      int ht2 = (int) (bh2 * t);
      g2d.setColor(palette[6]);
      g2d.setStroke(new BasicStroke(2));
      g2d.drawRoundRect(bw2-wt2, bh2-ht2, 2*wt2, 2*ht2, 6, 6);
      // ###################################################################
      // Now draw the text
      displayText(g2d, lines, palette[10]);
      buffer.endDraw();
    }
  }
}
