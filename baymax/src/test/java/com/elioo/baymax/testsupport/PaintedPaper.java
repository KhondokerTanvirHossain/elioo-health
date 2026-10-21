package com.elioo.baymax.testsupport;

import reactor.core.publisher.Sinks;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one painted page every image test photographs (wave 2 plan, Shared test support), lifted out of
 * {@code WebOnlyJourneyAcceptanceTest} so the journey, the image route and the real-browser probe check the same
 * picture by the same colours. The top-left quarter, where the stub OCR puts "Potassium 11.0" (x 10–140, y 10–40), is
 * {@link #WORDS} orange and the rest is {@link #PAPER} blue: a crop of the reading is all orange and smaller than the
 * page, and the page is both colours where painted. A crop that merely loaded, or that shows a neighbour's region, is
 * told apart from the reading's own by its content, never by its presence (DR-12).
 */
public final class PaintedPaper {

    /** Synthetic (DR-36): Potassium 11.0 against 3.5–5.1 is ≥ 2× high, the stopgap's NOW. */
    public static final String NOW_REPLY = """
            {"document_type":"lab_report","document_date":"2026-10-12","facility":"Shapla Diagnostics",
             "values":[{"name":"Potassium","value":"11.0","unit":"mmol/L","ref_low":"3.5","ref_high":"5.1",
                        "source_span":{"page":1,"start":0,"end":9}}],
             "medicines":[],"follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                                 "investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.95,"values":0.95,"medicines":1.0,"follow_up":1.0,"clinical_context":1.0}}
            """;

    /** How long the WhatsApp port must stay silent before a journey counts its calls: longer than any retry or follow-up. */
    public static final Duration SETTLE = Duration.ofSeconds(3);

    private static final int PAGE_W = 400;
    private static final int PAGE_H = 200;
    public static final Color WORDS = new Color(255, 140, 0);
    public static final Color PAPER = new Color(0, 40, 200);

    private PaintedPaper() {
    }

    /** The page the family photographs: the words' quarter {@link #WORDS}, the paper {@link #PAPER}. */
    public static byte[] paintedPage() {
        BufferedImage image = new BufferedImage(PAGE_W, PAGE_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(PAPER);
        g.fillRect(0, 0, PAGE_W, PAGE_H);
        g.setColor(WORDS);
        g.fillRect(0, 0, PAGE_W / 2, PAGE_H / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** {@link #WORDS} or {@link #PAPER} — whichever the pixel at this fraction of the picture is near (JPEG is lossy). */
    public static Color colourAt(BufferedImage image, double fx, double fy) {
        return named(new Color(image.getRGB((int) (image.getWidth() * fx), (int) (image.getHeight() * fy))));
    }

    /** {@link #WORDS}, {@link #PAPER}, or the colour itself when it is near neither. */
    private static Color named(Color c) {
        if (near(c, WORDS)) {
            return WORDS;
        }
        return near(c, PAPER) ? PAPER : c;
    }

    private static boolean near(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) < 40 && Math.abs(a.getGreen() - b.getGreen()) < 40
                && Math.abs(a.getBlue() - b.getBlue()) < 40;
    }

    /** The held journey's two signals: the chain's assessment waits for the page, the page's yields for a DONE view. */
    public static final class Hold {
        /** How long the page's own assessment yields for a DONE view: long enough that a view read alongside it wins. */
        public static final Duration YIELD = Duration.ofSeconds(2);
        public final Sinks.Empty<Void> pageDrawn = Sinks.empty();
        public final Sinks.Empty<Void> doneViewRead = Sinks.empty();
        public final AtomicInteger chainWaiting = new AtomicInteger();
        public final AtomicInteger sweeps = new AtomicInteger();
    }
}
