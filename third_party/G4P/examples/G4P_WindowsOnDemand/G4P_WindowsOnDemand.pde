/**
 This sketch shows how to create and show windows on demand. Rather than 
 close / re-open a window when needed again the window will be hidden when
 the user attempts to close it.
  
 Opening a new GWindow is resource hungry and might take a few seconds, 
 especially P2D and P3D windows so this feature (new for 4.3.5) can be 
 a much better option than cycling between opening and closing a window.
  
 The GWindow has the potential benefit of maintaining its state while 
 invisible to the user.
 
 for Processing V3+
 (c) 2019 Peter Lager 
 
 */

import g4p_controls.*;


GWindow winAWT = null, winOpenGL = null;

float ang = 0, angX = 0, angY = 0, angZ = 0;
float dang = 0.0097, dangX = 0.0031, dangY = 0.007, dangZ = 0.00497;

void setup() {
  size(360, 270, JAVA2D);
  surface.setTitle("Windows on Demand Demo");
  createGUI();
}

void draw() {
  background(230, 230, 255);
}

void win_awt_draw(PApplet appc, GWinData data) {
  appc.background(255, 230, 240);
  appc.translate(appc.width/2, appc.height/2);
  appc.rotate(ang);
  ang += dang;
  appc.stroke(0);
  appc.strokeWeight(2);
  appc.fill(128, 64, 128);
  appc.rectMode(CENTER);
  appc.rect(0, 0, 180, 60);
}

void win_opengl_draw(PApplet appc, GWinData data) {
  appc.background(32);
  appc.translate(appc.width/2, appc.height/2);
  appc.rotateX(angX);
  appc.rotateY(angY);
  appc.rotateZ(angZ);
  angX += dangX;
  angY += dangY;
  angZ += dangZ;
  appc.stroke(255, 0, 255);
  appc.strokeWeight(2);
  appc.fill(160, 96, 160);
  appc.box(120);
}

void btnAWT_click(GButton source, GEvent event) {
  if (winAWT == null) {
    createAWTwindow();
  } else {
    winAWT.setVisible(!winAWT.isVisible());
  }
}

void btnOpenGL_click(GButton source, GEvent event) {
  if (winOpenGL == null) {
    createOpenGLwindow();
  } else {
    winOpenGL.setVisible(!winOpenGL.isVisible());
  }
} 

void createAWTwindow() {
  winAWT = GWindow.getWindow(this, "Java AWT Window (JAVA2D)", 50, 50, 300, 300, JAVA2D);
  winAWT.setActionOnClose(G4P.HIDE_WINDOW);
  winAWT.addDrawHandler(this, "win_awt_draw");
}

void createOpenGLwindow() {
  winOpenGL = GWindow.getWindow(this, "OpenGL Window (P3D)", 360, 70, 300, 300, P3D);
  winOpenGL.setActionOnClose(G4P.HIDE_WINDOW);
  winOpenGL.addDrawHandler(this, "win_opengl_draw");
}

void createGUI() {
  G4P.messagesEnabled(false);
  G4P.setGlobalColorScheme(GCScheme.BLUE_SCHEME);
  G4P.setMouseOverEnabled(false);
  G4P.setDisplayFont("Arial", G4P.PLAIN, 16);
  surface.setTitle("Sketch Window");
  GButton btnAWTwindow = new GButton(this, 10, 230, 160, 30);
  btnAWTwindow.setText("SHOW / HIDE");
  btnAWTwindow.addEventHandler(this, "btnAWT_click");
  GButton btnOpenGLwindow = new GButton(this, 190, 230, 160, 30);
  btnOpenGLwindow.setText("SHOW / HIDE");
  btnOpenGLwindow.addEventHandler(this, "btnOpenGL_click");
  GLabel label1 = new GLabel(this, 10, 180, 160, 40);
  label1.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  label1.setText("Java AWT Window");
  label1.setOpaque(false);
  GLabel label2 = new GLabel(this, 190, 180, 160, 40);
  label2.setTextAlign(GAlign.CENTER, GAlign.MIDDLE);
  label2.setText("OpenGL Window");
  label2.setOpaque(false);
  String [] info = loadStrings("info.txt");
  GLabel lblGuide = new GLabel(this, 10, 10, 340, 150);
  lblGuide.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 12));
  lblGuide.setTextAlign(GAlign.JUSTIFY, GAlign.MIDDLE);
  lblGuide.setText(info[0]);
  lblGuide.setOpaque(true);
}
