package com.chasmet.plantravail;

import android.graphics.*;
import java.util.ArrayList;
import java.util.List;

/** Page layout is separate from Android's native PDF encoder so continuation can be tested. */
final class PdfTextLayout {
  static final int WIDTH = 595, HEIGHT = 842;
  final List<Page> pages = new ArrayList<>();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private String heading;
  private int nextNumber;
  private Page current;
  private float y;

  PdfTextLayout(int first, String heading) {
    nextNumber = first;
    this.heading = heading;
    newPage();
  }

  private void newPage() {
    current = new Page(nextNumber++, heading);
    pages.add(current);
    y = 64;
  }

  void section(String heading) {
    this.heading = heading;
    newPage();
  }

  void paragraph(String value, boolean bold, int color) {
    paint.setTextSize(10);
    paint.setFakeBoldText(bold);
    for (String line : value.replace("\r", "").split("\n", -1)) {
      String remaining = line;
      do {
        int count = paint.breakText(remaining, true, 531, null);
        if (count > 0 && count < remaining.length()) {
          if (Character.isHighSurrogate(remaining.charAt(count - 1))) count--;
          int space = remaining.lastIndexOf(' ', count);
          if (space > 0) count = space;
        }
        if (count < 1 && !remaining.isEmpty())
          count = Character.charCount(remaining.codePointAt(0));
        String fragment = remaining.substring(0, count);
        remaining = remaining.substring(count).replaceFirst("^ +", "");
        if (y > 792) newPage();
        current.lines.add(new Line(fragment, y, bold, color));
        y += 14;
      } while (!remaining.isEmpty());
    }
    y += 6;
  }

  static final class Line {
    final String text;
    final float y;
    final boolean bold;
    final int color;

    Line(String text, float y, boolean bold, int color) {
      this.text = text;
      this.y = y;
      this.bold = bold;
      this.color = color;
    }
  }

  static final class Page {
    final int number;
    final String heading;
    final List<Line> lines = new ArrayList<>();

    Page(int number, String heading) {
      this.number = number;
      this.heading = heading;
    }

    void draw(Canvas canvas) {
      canvas.drawColor(Color.WHITE);
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setColor(Color.BLACK);
      p.setTextSize(14);
      p.setFakeBoldText(true);
      canvas.drawText(heading, 32, 38, p);
      p.setFakeBoldText(false);
      p.setTextSize(8);
      canvas.drawText("Plan Travail Orsay • page " + number, 32, 821, p);
      for (Line line : lines) {
        p.setTextSize(10);
        p.setFakeBoldText(line.bold);
        p.setColor(line.color);
        canvas.drawText(line.text, 32, line.y, p);
      }
    }
  }
}
