package com.elioo.baymax.web.adapter.in.ui;

import java.util.Map;

/** The kit's 24 × 24 stroke icons (kit §1), always aria-hidden, coloured by the text around them. */
final class Icons {

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("chat", "<path d=\"M20 11.5a7.5 7.5 0 0 1-10.9 6.7L4 19.5l1.3-4.6A7.5 7.5 0 1 1 20 11.5z\"/>"),
            Map.entry("papers", "<path d=\"M8 3.5h6.5L19 8v11a1.5 1.5 0 0 1-1.5 1.5h-9.5A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5z\"/><path d=\"M14 3.5V8h5\"/><path d=\"M9.5 12.5h6M9.5 16h4\"/>"),
            Map.entry("family", "<circle cx=\"9\" cy=\"8\" r=\"3.25\"/><path d=\"M3 19.5a6 6 0 0 1 12 0\"/><circle cx=\"17\" cy=\"9.5\" r=\"2.5\"/><path d=\"M16.5 14.1a4.6 4.6 0 0 1 4.5 4.9\"/>"),
            Map.entry("calendar", "<rect x=\"3.5\" y=\"5\" width=\"17\" height=\"15.5\" rx=\"2\"/><path d=\"M3.5 10h17M8 3v4M16 3v4\"/>"),
            Map.entry("octagon", "<path d=\"M8 2.5h8l5.5 5.5v8L16 21.5H8L2.5 16V8z\"/><path d=\"M12 7.5v5.5M12 16.5h.01\"/>"),
            Map.entry("camera", "<path d=\"M4.5 8h2.8l1.8-2.5h5.8L16.7 8h2.8A1.5 1.5 0 0 1 21 9.5v8.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18V9.5A1.5 1.5 0 0 1 4.5 8z\"/><circle cx=\"12\" cy=\"13.5\" r=\"3.5\"/>"),
            Map.entry("chevron-down", "<path d=\"M6 9l6 6 6-6\"/>"),
            Map.entry("chevron-up", "<path d=\"M6 15l6-6 6 6\"/>"),
            Map.entry("plus", "<path d=\"M12 5v14M5 12h14\"/>"),
            Map.entry("chevron-right", "<path d=\"M9 6l6 6-6 6\"/>"),
            Map.entry("check", "<path d=\"M5 12.5l4.5 4.5L19 7.5\"/>"),
            Map.entry("page", "<path d=\"M7 3.5h7l4.5 4.5v12.5H7z\"/><path d=\"M14 3.5V8h4.5\"/>"),
            Map.entry("x", "<path d=\"M6 6l12 12M18 6L6 18\"/>"),
            // s07's three photo tips: the page lying flat, light, the whole page in the frame
            Map.entry("tip-flat", "<path d=\"M6 6h12v11H6z\"/><path d=\"M8.5 9h7M8.5 11.5h7M8.5 14h4\"/><path d=\"M3 20h18\"/>"),
            Map.entry("tip-light", "<circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4\"/>"),
            Map.entry("tip-frame", "<path d=\"M3.5 8V3.5H8M16 3.5h4.5V8M20.5 16v4.5H16M8 20.5H3.5V16\"/><path d=\"M8 7h8v10H8z\"/>"));

    private Icons() {
    }

    static String svg(String name, int size) {
        String paths = PATHS.get(name);
        if (paths == null) {
            throw new IllegalArgumentException("no icon " + name);
        }
        return "<svg aria-hidden=\"true\" width=\"" + size + "\" height=\"" + size + "\" viewBox=\"0 0 24 24\" fill=\"none\""
                + " stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" + paths + "</svg>";
    }
}
