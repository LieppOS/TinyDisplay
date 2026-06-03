package com.tinydisplay;

import com.tinydisplay.hal.TinyLcdHal;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Renders text and clock frames using a built-in 5x7 bitmap font.
 * Writes RGB565 big-endian bytes directly — no Canvas, no Bitmap,
 * no copyPixelsToBuffer, no byte-swapping. Pure raw bytes.
 */
public class RawFontRenderer {

    // Render at the panel's true geometry. The controller scans 340-wide rows,
    // so W is the row stride used for pixel indexing (NOT the 360 buffer width).
    static final int W = TinyLcdHal.PANEL_WIDTH;    // 340 (row stride)
    static final int H = TinyLcdHal.PANEL_HEIGHT;   // 340 (visible rows)
    private static final int GW = 5;  // glyph width in "pixels"
    private static final int GH = 7;  // glyph height in "pixels"

    // 5x7 bitmap font. Each glyph = 7 rows; each row = 5 bits (bit4=left, bit0=right).
    private static final int[][] G = new int[128][];

    static {
        G['0'] = new int[]{0x0E,0x11,0x13,0x15,0x19,0x11,0x0E};
        G['1'] = new int[]{0x04,0x0C,0x04,0x04,0x04,0x04,0x0E};
        G['2'] = new int[]{0x0E,0x11,0x01,0x02,0x04,0x08,0x1F};
        G['3'] = new int[]{0x0E,0x11,0x01,0x06,0x01,0x11,0x0E};
        G['4'] = new int[]{0x02,0x06,0x0A,0x12,0x1F,0x02,0x02};
        G['5'] = new int[]{0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E};
        G['6'] = new int[]{0x06,0x08,0x10,0x1E,0x11,0x11,0x0E};
        G['7'] = new int[]{0x1F,0x01,0x02,0x04,0x08,0x08,0x08};
        G['8'] = new int[]{0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E};
        G['9'] = new int[]{0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C};
        G[':'] = new int[]{0x00,0x04,0x04,0x00,0x04,0x04,0x00};
        G[';'] = new int[]{0x00,0x04,0x04,0x00,0x04,0x04,0x08};
        G[' '] = new int[]{0x00,0x00,0x00,0x00,0x00,0x00,0x00};
        G['%'] = new int[]{0x18,0x19,0x02,0x04,0x08,0x13,0x03};
        G['+'] = new int[]{0x00,0x04,0x04,0x1F,0x04,0x04,0x00};
        G['-'] = new int[]{0x00,0x00,0x00,0x0E,0x00,0x00,0x00};
        G[','] = new int[]{0x00,0x00,0x00,0x00,0x00,0x04,0x08};
        G['.'] = new int[]{0x00,0x00,0x00,0x00,0x00,0x00,0x04};
        G['/'] = new int[]{0x01,0x01,0x02,0x04,0x08,0x10,0x10};
        G['('] = new int[]{0x02,0x04,0x08,0x08,0x08,0x04,0x02};
        G[')'] = new int[]{0x08,0x04,0x02,0x02,0x02,0x04,0x08};
        G['!'] = new int[]{0x04,0x04,0x04,0x04,0x04,0x00,0x04};
        G['?'] = new int[]{0x0E,0x11,0x01,0x02,0x04,0x00,0x04};
        G['\''] = new int[]{0x04,0x04,0x08,0x00,0x00,0x00,0x00};
        G['\"'] = new int[]{0x0A,0x0A,0x00,0x00,0x00,0x00,0x00};
        G['='] = new int[]{0x00,0x1F,0x00,0x1F,0x00,0x00,0x00};
        G['*'] = new int[]{0x00,0x15,0x0E,0x1F,0x0E,0x15,0x00};
        G['#'] = new int[]{0x0A,0x1F,0x0A,0x0A,0x1F,0x0A,0x00};
        G['<'] = new int[]{0x02,0x04,0x08,0x10,0x08,0x04,0x02};
        G['>'] = new int[]{0x08,0x04,0x02,0x01,0x02,0x04,0x08};

        G['A'] = new int[]{0x0E,0x11,0x11,0x1F,0x11,0x11,0x11};
        G['B'] = new int[]{0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E};
        G['C'] = new int[]{0x0E,0x11,0x10,0x10,0x10,0x11,0x0E};
        G['D'] = new int[]{0x1C,0x12,0x11,0x11,0x11,0x12,0x1C};
        G['E'] = new int[]{0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F};
        G['F'] = new int[]{0x1F,0x10,0x10,0x1E,0x10,0x10,0x10};
        G['G'] = new int[]{0x0E,0x11,0x10,0x17,0x11,0x11,0x0F};
        G['H'] = new int[]{0x11,0x11,0x11,0x1F,0x11,0x11,0x11};
        G['I'] = new int[]{0x0E,0x04,0x04,0x04,0x04,0x04,0x0E};
        G['J'] = new int[]{0x07,0x02,0x02,0x02,0x02,0x12,0x0C};
        G['K'] = new int[]{0x11,0x12,0x14,0x18,0x14,0x12,0x11};
        G['L'] = new int[]{0x10,0x10,0x10,0x10,0x10,0x10,0x1F};
        G['M'] = new int[]{0x11,0x1B,0x15,0x15,0x11,0x11,0x11};
        G['N'] = new int[]{0x11,0x11,0x19,0x15,0x13,0x11,0x11};
        G['O'] = new int[]{0x0E,0x11,0x11,0x11,0x11,0x11,0x0E};
        G['P'] = new int[]{0x1E,0x11,0x11,0x1E,0x10,0x10,0x10};
        G['Q'] = new int[]{0x0E,0x11,0x11,0x11,0x15,0x12,0x0D};
        G['R'] = new int[]{0x1E,0x11,0x11,0x1E,0x14,0x12,0x11};
        G['S'] = new int[]{0x0E,0x11,0x10,0x0E,0x01,0x11,0x0E};
        G['T'] = new int[]{0x1F,0x04,0x04,0x04,0x04,0x04,0x04};
        G['U'] = new int[]{0x11,0x11,0x11,0x11,0x11,0x11,0x0E};
        G['V'] = new int[]{0x11,0x11,0x11,0x11,0x0A,0x0A,0x04};
        G['W'] = new int[]{0x11,0x11,0x11,0x15,0x15,0x15,0x0A};
        G['X'] = new int[]{0x11,0x11,0x0A,0x04,0x0A,0x11,0x11};
        G['Y'] = new int[]{0x11,0x11,0x0A,0x04,0x04,0x04,0x04};
        G['Z'] = new int[]{0x1F,0x01,0x02,0x04,0x08,0x10,0x1F};

        // Lowercase (descenders/ascenders approximated in 5x7)
        G['a'] = new int[]{0x00,0x00,0x0E,0x01,0x0F,0x11,0x0F};
        G['b'] = new int[]{0x10,0x10,0x1E,0x11,0x11,0x11,0x1E};
        G['c'] = new int[]{0x00,0x00,0x0E,0x11,0x10,0x11,0x0E};
        G['d'] = new int[]{0x01,0x01,0x0F,0x11,0x11,0x11,0x0F};
        G['e'] = new int[]{0x00,0x00,0x0E,0x11,0x1F,0x10,0x0E};
        G['f'] = new int[]{0x06,0x09,0x08,0x1C,0x08,0x08,0x08};
        G['g'] = new int[]{0x00,0x00,0x0F,0x11,0x0F,0x01,0x0E};
        G['h'] = new int[]{0x10,0x10,0x16,0x19,0x11,0x11,0x11};
        G['i'] = new int[]{0x04,0x00,0x0C,0x04,0x04,0x04,0x0E};
        G['j'] = new int[]{0x02,0x00,0x06,0x02,0x02,0x12,0x0C};
        G['k'] = new int[]{0x10,0x10,0x12,0x14,0x18,0x14,0x12};
        G['l'] = new int[]{0x0C,0x04,0x04,0x04,0x04,0x04,0x0E};
        G['m'] = new int[]{0x00,0x00,0x1A,0x15,0x15,0x11,0x11};
        G['n'] = new int[]{0x00,0x00,0x16,0x19,0x11,0x11,0x11};
        G['o'] = new int[]{0x00,0x00,0x0E,0x11,0x11,0x11,0x0E};
        G['p'] = new int[]{0x00,0x00,0x1E,0x11,0x1E,0x10,0x10};
        G['q'] = new int[]{0x00,0x00,0x0F,0x11,0x0F,0x01,0x01};
        G['r'] = new int[]{0x00,0x00,0x16,0x19,0x10,0x10,0x10};
        G['s'] = new int[]{0x00,0x00,0x0E,0x10,0x0E,0x01,0x1E};
        G['t'] = new int[]{0x08,0x08,0x1C,0x08,0x08,0x09,0x06};
        G['u'] = new int[]{0x00,0x00,0x11,0x11,0x11,0x13,0x0D};
        G['v'] = new int[]{0x00,0x00,0x11,0x11,0x11,0x0A,0x04};
        G['w'] = new int[]{0x00,0x00,0x11,0x11,0x15,0x15,0x0A};
        G['x'] = new int[]{0x00,0x00,0x11,0x0A,0x04,0x0A,0x11};
        G['y'] = new int[]{0x00,0x00,0x11,0x11,0x0F,0x01,0x0E};
        G['z'] = new int[]{0x00,0x00,0x1F,0x02,0x04,0x08,0x1F};
    }

    /** Convert 8-bit RGB to 16-bit RGB565 value. */
    public static int rgb565(int r, int g, int b) {
        return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
    }

    /**
     * Byte index of pixel (x,y) within the frame, applying a 180° rotation.
     * The CO5300 panel's scan origin is the bottom-right corner, so logical
     * pixel (x,y) is stored at panel position (W-1-x, H-1-y). Without this the
     * image appears upside-down and mirrored ("OLLEH"). Row stride = W = 340.
     */
    private static int idx(int x, int y) {
        return ((H - 1 - y) * W + (W - 1 - x)) * 2;
    }

    /** Set a single pixel in the frame (RGB565 big-endian). */
    public static void setPixel(byte[] frame, int x, int y, int rgb565) {
        if (x < 0 || x >= W || y < 0 || y >= H) return;
        int i = idx(x, y);
        frame[i] = (byte) ((rgb565 >> 8) & 0xFF);
        frame[i + 1] = (byte) (rgb565 & 0xFF);
    }

    /** Fill a rectangle with a solid color. */
    public static void fillRect(byte[] frame, int x, int y, int w, int h, int color) {
        byte hi = (byte) ((color >> 8) & 0xFF);
        byte lo = (byte) (color & 0xFF);
        for (int dy = 0; dy < h; dy++) {
            int py = y + dy;
            if (py < 0 || py >= H) continue;
            for (int dx = 0; dx < w; dx++) {
                int px = x + dx;
                if (px < 0 || px >= W) continue;
                int i = idx(px, py);
                frame[i] = hi;
                frame[i + 1] = lo;
            }
        }
    }

    /** Draw a single character at (x,y) with the given scale and color. */
    public static void drawChar(byte[] frame, char ch, int x, int y, int scale, int color) {
        int[] glyph = (ch < 128) ? G[ch] : null;
        if (glyph == null) glyph = G['?'];
        if (glyph == null) return;
        byte hi = (byte) ((color >> 8) & 0xFF);
        byte lo = (byte) (color & 0xFF);
        for (int row = 0; row < GH; row++) {
            int bits = glyph[row];
            for (int col = 0; col < GW; col++) {
                if ((bits & (0x10 >> col)) != 0) {
                    int bx = x + col * scale;
                    int by = y + row * scale;
                    for (int sy = 0; sy < scale; sy++) {
                        int py = by + sy;
                        if (py < 0 || py >= H) continue;
                        for (int sx = 0; sx < scale; sx++) {
                            int px = bx + sx;
                            if (px < 0 || px >= W) continue;
                            int i = idx(px, py);
                            frame[i] = hi;
                            frame[i + 1] = lo;
                        }
                    }
                }
            }
        }
    }

    /** Draw a string starting at (x,y). Returns total pixel width. */
    public static int drawString(byte[] frame, String text, int x, int y, int scale, int color) {
        if (text == null || text.isEmpty()) return 0;
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            drawChar(frame, text.charAt(i), cx, y, scale, color);
            cx += (GW + 1) * scale;
        }
        return cx - x;
    }

    /** Draw a string centered horizontally at the given vertical center. */
    public static void drawCentered(byte[] frame, String text, int centerY, int scale, int color) {
        if (text == null) text = "";
        int totalW = text.length() * (GW + 1) * scale - scale;
        int sx = (W - totalW) / 2;
        int sy = centerY - (GH * scale) / 2;
        drawString(frame, text, sx, sy, scale, color);
    }

    /** Measure the pixel width of a string at the given scale. */
    public static int measureWidth(String text, int scale) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() * (GW + 1) * scale - scale;
    }

    /**
     * Convert unsupported characters to placeholders and truncate to fit.
     */
    public static String fitText(String text, int maxWidth, int scale) {
        if (text == null) return "";
        String clean = sanitize(text);
        if (measureWidth(clean, scale) <= maxWidth) return clean;

        String suffix = "...";
        int keep = clean.length();
        while (keep > 0 && measureWidth(clean.substring(0, keep) + suffix, scale) > maxWidth) {
            keep--;
        }
        return keep > 0 ? clean.substring(0, keep) + suffix : "";
    }

    private static String sanitize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            appendSupported(out, mapCodePoint(cp));
        }
        // Keep notification layout stable when an emoji expands into words.
        return out.toString().replaceAll("\\s+", " ");
    }

    /** Map Unicode that the 5x7 font cannot draw into readable ASCII. */
    private static String mapCodePoint(int cp) {
        switch (cp) {
            // Lithuanian letters: readable transliteration for the tiny ASCII font.
            case 'ą': case 'Ą': return Character.isUpperCase(cp) ? "A" : "a";
            case 'č': case 'Č': return Character.isUpperCase(cp) ? "C" : "c";
            case 'ę': case 'Ę': return Character.isUpperCase(cp) ? "E" : "e";
            case 'ė': case 'Ė': return Character.isUpperCase(cp) ? "E" : "e";
            case 'į': case 'Į': return Character.isUpperCase(cp) ? "I" : "i";
            case 'š': case 'Š': return Character.isUpperCase(cp) ? "S" : "s";
            case 'ų': case 'Ų': return Character.isUpperCase(cp) ? "U" : "u";
            case 'ū': case 'Ū': return Character.isUpperCase(cp) ? "U" : "u";
            case 'ž': case 'Ž': return Character.isUpperCase(cp) ? "Z" : "z";

            // Common punctuation/symbols seen in messages.
            case 0x2018: case 0x2019: case 0x201A: case 0x201B: return "'";
            case 0x201C: case 0x201D: case 0x201E: case 0x201F: return "\"";
            case 0x2013: case 0x2014: case 0x2212: return "-";
            case 0x2026: return "...";
            case 0x20AC: return "EUR";
            case 0x00A3: return "GBP";
            case 0x00A9: return "(c)";
            case 0x00AE: return "(r)";
            case 0x2122: return "TM";

            // Emoji aliases. They are intentionally short so wrapping still works.
            case 0x2764: case 0x1F493: case 0x1F494: case 0x1F495: case 0x1F496:
            case 0x1F497: case 0x1F498: case 0x1F499: case 0x1F49A: case 0x1F49B:
            case 0x1F49C: case 0x1F5A4: return "(love)";
            case 0x1F600: case 0x1F601: case 0x1F603: case 0x1F604: case 0x1F642:
            case 0x1F60A: return ":)";
            case 0x1F602: case 0x1F923: return "LOL";
            case 0x1F609: return ";)";
            case 0x1F60D: case 0x1F970: return "(love)";
            case 0x1F622: case 0x1F62D: case 0x1F625: return ":(";
            case 0x1F620: case 0x1F621: case 0x1F92C: return "(angry)";
            case 0x1F44D: return "+1";
            case 0x1F44E: return "-1";
            case 0x1F44B: return "(wave)";
            case 0x1F64F: return "(pray)";
            case 0x1F525: return "(fire)";
            case 0x1F389: case 0x1F38A: return "(party)";
            case 0x1F4A9: return "(poop)";
            case 0x1F4DE: case 0x260E: return "(call)";
            case 0x1F4F7: case 0x1F4F8: return "(camera)";
            case 0x1F697: case 0x1F698: return "(car)";
        }
        if (cp >= 0xFE00 && cp <= 0xFE0F) return ""; // emoji variation selector
        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return ""; // flag regional indicator
        if (cp >= 32 && cp < 128 && G[cp] != null) return String.valueOf((char) cp);
        if (cp >= 0x1F300 && cp <= 0x1FAFF) return "(*)"; // unknown emoji
        return "?";
    }

    private static void appendSupported(StringBuilder out, String s) {
        for (int j = 0; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (ch >= 32 && ch < 128 && G[ch] != null) out.append(ch);
            else if (Character.isWhitespace(ch)) out.append(' ');
            else out.append('?');
        }
    }

    /** Draw a battery arc (270-degree sweep from bottom-left). */
    public static void drawBatteryArc(byte[] frame, int level, boolean charging) {
        int color;
        if (charging)        color = rgb565(33, 150, 243);   // blue
        else if (level > 50) color = rgb565(76, 175, 80);    // green
        else if (level > 20) color = rgb565(255, 193, 7);    // yellow/amber
        else                 color = rgb565(244, 67, 54);     // red

        int cx = W / 2, cy = H / 2, radius = 165;
        double startAngle = Math.toRadians(135);
        double sweep = Math.toRadians(270.0 * level / 100.0);
        int steps = Math.max((int) (sweep * radius), 1);

        for (int i = 0; i <= steps; i++) {
            double angle = startAngle + sweep * i / steps;
            int px = cx + (int) (radius * Math.cos(angle));
            int py = cy + (int) (radius * Math.sin(angle));
            // 3x3 thick dot
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++)
                    setPixel(frame, px + dx, py + dy, color);
        }
    }

    /**
     * Render a clock frame: time, date, battery, battery arc.
     * Returns a new 259200-byte RGB565 BE frame (black background).
     */
    public static byte[] renderClock(int batteryLevel, boolean isCharging) {
        byte[] frame = new byte[TinyLcdHal.FRAME_SIZE];

        Date now = new Date();
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
        String date = new SimpleDateFormat("EEE, MMM d", Locale.US)
                .format(now).toUpperCase(Locale.US);
        String batt = isCharging ? batteryLevel + "%+" : batteryLevel + "%";

        // Time — large, centered
        drawCentered(frame, fitText(time, 320, 10), H / 2 - 30, 10, 0xFFFF);

        // Date — medium, below time
        drawCentered(frame, fitText(date, 260, 4), H / 2 + 45, 4, rgb565(180, 180, 180));

        // Battery — small, below date
        drawCentered(frame, fitText(batt, 180, 4), H / 2 + 80, 4, rgb565(150, 150, 150));

        // Battery arc
        drawBatteryArc(frame, batteryLevel, isCharging);

        return frame;
    }

    /**
     * Render an incoming call frame.
     */
    public static byte[] renderIncomingCall(String callerName, String phoneNumber) {
        byte[] frame = new byte[TinyLcdHal.FRAME_SIZE];
        int bg = rgb565(20, 60, 20);
        byte hi = (byte) ((bg >> 8) & 0xFF);
        byte lo = (byte) (bg & 0xFF);
        for (int i = 0; i < frame.length; i += 2) {
            frame[i] = hi;
            frame[i + 1] = lo;
        }

        drawCentered(frame, "INCOMING CALL", H / 2 - 50, 4, rgb565(100, 200, 100));

        String name = callerName != null ? callerName.toUpperCase(Locale.getDefault()) : "UNKNOWN";
        name = fitText(name, 300, 6);
        drawCentered(frame, name, H / 2 + 10, 6, 0xFFFF);

        if (phoneNumber != null) {
            String num = fitText(phoneNumber, 260, 4);
            drawCentered(frame, num, H / 2 + 60, 4, rgb565(180, 180, 180));
        }

        return frame;
    }

    /**
     * Render arbitrary text centered on the screen (for testing).
     */
    public static byte[] renderText(String text, int scale) {
        byte[] frame = new byte[TinyLcdHal.FRAME_SIZE];
        drawCentered(frame, text, H / 2, scale, 0xFFFF);
        return frame;
    }

    /** Word-wrap text into at most maxLines lines that each fit maxWidth pixels. */
    public static java.util.List<String> wrap(String text, int scale, int maxWidth, int maxLines) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null) return lines;
        String clean = sanitize(text).trim();
        if (clean.isEmpty()) return lines;
        StringBuilder cur = new StringBuilder();
        for (String word : clean.split("\\s+")) {
            String w = word;
            // Hard-split a single word that is too wide on its own.
            while (measureWidth(w, scale) > maxWidth && w.length() > 1) {
                int keep = w.length();
                while (keep > 1 && measureWidth(w.substring(0, keep), scale) > maxWidth) keep--;
                if (cur.length() > 0) { lines.add(cur.toString()); cur.setLength(0); }
                if (lines.size() >= maxLines) return ellipsize(lines, scale, maxWidth);
                lines.add(w.substring(0, keep));
                w = w.substring(keep);
                if (lines.size() >= maxLines) return ellipsize(lines, scale, maxWidth);
            }
            String trial = cur.length() == 0 ? w : cur + " " + w;
            if (measureWidth(trial, scale) <= maxWidth) {
                cur.setLength(0);
                cur.append(trial);
            } else {
                if (cur.length() > 0) lines.add(cur.toString());
                cur.setLength(0);
                cur.append(w);
                if (lines.size() >= maxLines) return ellipsize(lines, scale, maxWidth);
            }
        }
        if (cur.length() > 0 && lines.size() < maxLines) lines.add(cur.toString());
        return lines;
    }

    private static java.util.List<String> ellipsize(java.util.List<String> lines, int scale, int maxWidth) {
        if (lines.isEmpty()) return lines;
        int last = lines.size() - 1;
        String s = lines.get(last);
        while (!s.isEmpty() && measureWidth(s + "...", scale) > maxWidth) s = s.substring(0, s.length() - 1);
        lines.set(last, s + "...");
        return lines;
    }

    /** Draw a block of pre-wrapped lines centered horizontally, starting at top Y. */
    private static int drawLines(byte[] frame, java.util.List<String> lines, int topY,
                                 int scale, int color) {
        int y = topY;
        int lineH = GH * scale + scale * 2;
        for (String line : lines) {
            int w = measureWidth(line, scale);
            drawString(frame, line, (W - w) / 2, y, scale, color);
            y += lineH;
        }
        return y;
    }

    /**
     * Render a full-screen notification: app name (accent), title (large white,
     * up to 2 lines), and body text (gray, up to 3 lines), all centered.
     */
    public static byte[] renderNotification(String appName, String title, String text) {
        byte[] frame = new byte[TinyLcdHal.FRAME_SIZE];
        int bg = rgb565(12, 14, 18);
        fillRect(frame, 0, 0, W, H, bg);

        int margin = 28;
        int maxW = W - margin * 2;
        int accent = rgb565(90, 170, 255);

        int titleScale = 3; // sender/name: smaller than before, body stays readable
        int bodyScale = 3;
        java.util.List<String> titleLines = wrap(title, titleScale, maxW, 2);
        java.util.List<String> textLines = wrap(text, bodyScale, maxW, 3);

        // Vertically center the whole stack around H/2.
        int appH = (appName != null && !appName.isEmpty()) ? (GH * 2 + 8) : 0;
        int titleH = titleLines.size() * (GH * titleScale + titleScale * 2);
        int textH = textLines.size() * (GH * bodyScale + bodyScale * 2);
        int gap = 14;
        int totalH = appH + (titleH > 0 ? gap + titleH : 0) + (textH > 0 ? gap + textH : 0);
        int y = (H - totalH) / 2;

        if (appH > 0) {
            String app = fitText(appName.toUpperCase(Locale.getDefault()), maxW, 2);
            drawCentered(frame, app, y + GH, 2, accent);
            y += appH + gap;
        }
        if (titleH > 0) {
            y = drawLines(frame, titleLines, y, titleScale, 0xFFFF);
            y += gap - 8;
        }
        if (textH > 0) {
            drawLines(frame, textLines, y, bodyScale, rgb565(230, 230, 235));
        }
        return frame;
    }

    /** Full-screen notification with a "current/total" badge for the queue page. */
    public static byte[] renderNotification(String appName, String title, String text,
                                            int index, int total) {
        byte[] frame = renderNotification(appName, title, text);
        if (total > 1) {
            String badge = (index + 1) + "/" + total;
            int w = measureWidth(badge, 2);
            drawString(frame, badge, (W - w) / 2, 18, 2, rgb565(120, 130, 150));
        }
        return frame;
    }

    // ── Extra clock faces ───────────────────────────────────────────

    /** Draw a thick line via Bresenham, used for analog hands. */
    private static void drawLine(byte[] frame, int x0, int y0, int x1, int y1, int thick, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            for (int oy = -thick; oy <= thick; oy++)
                for (int ox = -thick; ox <= thick; ox++)
                    setPixel(frame, x0 + ox, y0 + oy, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    private static void drawHand(byte[] frame, int cx, int cy, double angleDeg, int len, int thick, int color) {
        double a = Math.toRadians(angleDeg - 90); // 0deg = up
        int x1 = cx + (int) (len * Math.cos(a));
        int y1 = cy + (int) (len * Math.sin(a));
        drawLine(frame, cx, cy, x1, y1, thick, color);
    }

    /** Analog clock face: hour/minute/second hands + tick marks. */
    public static byte[] renderClockAnalog(int batteryLevel, boolean isCharging) {
        byte[] frame = new byte[TinyLcdHal.FRAME_SIZE];
        int cx = W / 2, cy = H / 2;
        int radius = 150;
        int dial = rgb565(70, 80, 95);
        // Hour ticks.
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30 - 90);
            int x0 = cx + (int) ((radius - 14) * Math.cos(a));
            int y0 = cy + (int) ((radius - 14) * Math.sin(a));
            int x1 = cx + (int) (radius * Math.cos(a));
            int y1 = cy + (int) (radius * Math.sin(a));
            drawLine(frame, x0, y0, x1, y1, 1, dial);
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        int h = c.get(java.util.Calendar.HOUR);
        int m = c.get(java.util.Calendar.MINUTE);
        int s = c.get(java.util.Calendar.SECOND);
        drawHand(frame, cx, cy, (h + m / 60.0) * 30, 80, 3, 0xFFFF);
        drawHand(frame, cx, cy, (m + s / 60.0) * 6, 120, 2, 0xFFFF);
        drawHand(frame, cx, cy, s * 6, 135, 0, rgb565(244, 67, 54));
        // Hub.
        for (int oy = -3; oy <= 3; oy++)
            for (int ox = -3; ox <= 3; ox++)
                setPixel(frame, cx + ox, cy + oy, rgb565(244, 67, 54));
        // Small battery readout at the bottom.
        String batt = isCharging ? batteryLevel + "%+" : batteryLevel + "%";
        drawCentered(frame, batt, cy + radius - 6, 3,
                isCharging ? rgb565(33, 150, 243) : rgb565(150, 160, 170));
        return frame;
    }

    /** Battery-ring face: big arc ring with time in the center. */
    public static byte[] renderClockRing(int batteryLevel, boolean isCharging) {
        byte[] frame = new byte[TinyLcdHal.FRAME_SIZE];
        int cx = W / 2, cy = H / 2, radius = 158;
        int track = rgb565(40, 45, 55);
        // Full faint track.
        for (int i = 0; i <= 360; i++) {
            double a = Math.toRadians(i - 90);
            int px = cx + (int) (radius * Math.cos(a));
            int py = cy + (int) (radius * Math.sin(a));
            for (int t = 0; t < 3; t++)
                setPixel(frame, px + (int) (t * Math.cos(a)), py + (int) (t * Math.sin(a)), track);
        }
        int color = isCharging ? rgb565(33, 150, 243)
                : batteryLevel > 50 ? rgb565(76, 175, 80)
                : batteryLevel > 20 ? rgb565(255, 193, 7) : rgb565(244, 67, 54);
        int sweep = (int) (360.0 * Math.max(0, Math.min(100, batteryLevel)) / 100.0);
        for (int i = 0; i <= sweep; i++) {
            double a = Math.toRadians(i - 90);
            int px = cx + (int) (radius * Math.cos(a));
            int py = cy + (int) (radius * Math.sin(a));
            for (int t = 0; t < 4; t++)
                setPixel(frame, px + (int) (t * Math.cos(a)), py + (int) (t * Math.sin(a)), color);
        }
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        drawCentered(frame, time, cy - 6, 9, 0xFFFF);
        String batt = isCharging ? batteryLevel + "%+" : batteryLevel + "%";
        drawCentered(frame, batt, cy + 70, 3, color);
        return frame;
    }

    /** Minimal dim face for always-on / pocket glance (time only). */
    public static byte[] renderClockAod() {
        byte[] frame = new byte[TinyLcdHal.FRAME_SIZE];
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        drawCentered(frame, time, H / 2, 8, rgb565(120, 120, 130));
        return frame;
    }
}
