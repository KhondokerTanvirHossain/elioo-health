package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * The whole UI is plain HTML and one inline stylesheet, rendered on the server: no framework, no build
 * step, nothing to download but the page itself and the images it names. Every value that came from a
 * document or a person passes through {@link #esc} before it reaches a page.
 *
 * <p>Design constraints from the ticket, checked here rather than remembered: legible on a 360px Android
 * over a slow connection (one small stylesheet, no fonts, images sized by CSS, no horizontal scroll), and
 * nothing interpretive anywhere — no explanation, no urgency, no advice. Labels are Bangla; values are shown
 * exactly as extracted.
 */
final class Html {

    private Html() {
    }

    static final String CSS = """
            *{box-sizing:border-box}
            html{-webkit-text-size-adjust:100%}
            body{margin:0;padding:12px;font:16px/1.5 system-ui,-apple-system,"Noto Sans Bengali","Segoe UI",sans-serif;color:#1a1a1a;background:#fafaf7;overflow-wrap:anywhere}
            main{max-width:520px;margin:0 auto}
            h1{font-size:1.25rem;margin:0 0 12px}
            h2{font-size:1.05rem;margin:20px 0 8px;border-bottom:1px solid #ddd;padding-bottom:4px}
            a{color:#0b5d8a}
            .top{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:12px}
            .top form{margin:0}
            .card{background:#fff;border:1px solid #e3e3de;border-radius:8px;padding:12px;margin:0 0 10px}
            .row{display:flex;gap:10px;align-items:flex-start}
            .row img{width:96px;height:96px;object-fit:cover;border-radius:6px;flex:none;background:#eee}
            .meta{color:#555;font-size:.9rem}
            .tag{display:inline-block;padding:1px 8px;border-radius:999px;background:#eef3f6;font-size:.85rem;margin-right:4px}
            .tag.retake{background:#fff1e6}
            .warn{background:#fff8e1;border:1px solid #f0d998;border-radius:8px;padding:10px;margin:10px 0}
            .err{background:#fdecec;border:1px solid #f1b8b8;border-radius:8px;padding:10px;margin:10px 0}
            .item{border-top:1px solid #eee;padding:10px 0}
            .item:first-of-type{border-top:0}
            .item .text{margin-bottom:6px}
            .crop{display:block;max-width:100%;height:auto;border:1px solid #ddd;border-radius:6px;background:#eee}
            .page{display:block;max-width:100%;height:auto;border:1px solid #ddd;border-radius:6px}
            label{display:block;margin:12px 0 4px;font-weight:600}
            input[type=text],input[type=tel],input[type=file]{width:100%;font-size:1.1rem;padding:10px;border:1px solid #bbb;border-radius:8px;background:#fff}
            button,.btn{display:inline-block;font-size:1rem;padding:10px 16px;border:0;border-radius:8px;background:#0b5d8a;color:#fff;text-decoration:none;cursor:pointer;margin-top:12px}
            button.danger{background:#a32d2d}
            button.quiet,.btn.quiet{background:#e8e8e3;color:#222}
            .small{font-size:.85rem;color:#666}
            """;

    static String page(String title, String body) {
        return page(title, body, 0);
    }

    /**
     * @param refreshSeconds when > 0 the page reloads itself after that many seconds — the timeline while a
     *                       document is still being read. Plain HTML, no script: the first real walkthrough
     *                       sat on "পড়া হচ্ছে…" long after the document had finished.
     */
    static String page(String title, String body, int refreshSeconds) {
        return "<!doctype html><html lang=\"bn\"><head><meta charset=\"utf-8\">"
                + (refreshSeconds > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + "\">" : "")
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\">"
                + "<title>" + esc(title) + "</title><style>" + CSS + "</style></head><body><main>"
                + body + "</main></body></html>";
    }

    static Mono<ServerResponse> ok(String title, String body) {
        return ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(page(title, body));
    }

    static Mono<ServerResponse> ok(String title, String body, int refreshSeconds) {
        return ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(page(title, body, refreshSeconds));
    }

    static Mono<ServerResponse> status(HttpStatus status, String title, String body) {
        return ServerResponse.status(status).contentType(MediaType.TEXT_HTML).bodyValue(page(title, body));
    }

    static Mono<ServerResponse> redirect(String path) {
        return ServerResponse.seeOther(URI.create(path)).build();
    }

    static String esc(Object value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(value).toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Bangla label for a document type; the raw value if unknown, never a guess. */
    static String documentType(String type) {
        if (type == null) {
            return "নথি";
        }
        return switch (type) {
            case "prescription" -> "প্রেসক্রিপশন";
            case "lab_report" -> "ল্যাব রিপোর্ট";
            case "discharge_summary" -> "ডিসচার্জ সামারি";
            case "imaging_report" -> "ইমেজিং রিপোর্ট";
            default -> esc(type);
        };
    }

    /**
     * Bangla label for a clinical-context section, or null for one the UI does not show. {@code advice} is the
     * doctor's own words transcribed from the page, shown under "as written on the prescription" (PO ruling,
     * 2026-09-18): "no advice in this UI" means no Baymax-generated advice, which still holds absolutely —
     * nothing here is composed, reordered or interpreted. {@code referral} is a single field, not a list, and
     * waits for BMX-6.
     */
    static String contextSection(String key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "chief_complaint" -> "কারণ";
            case "history" -> "ইতিহাস";
            case "examination" -> "পরীক্ষা";
            case "diagnosis" -> "রোগ নির্ণয়";
            case "investigations_advised" -> "পরীক্ষা করাতে বলা হয়েছে";
            case "advice" -> "প্রেসক্রিপশনে যা লেখা আছে";
            case "referral" -> null;
            default -> esc(key);
        };
    }

    static String status(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "DONE" -> "<span class=\"tag\">পড়া হয়েছে</span>";
            case "NEEDS_RETAKE" -> "<span class=\"tag retake\">আবার ছবি তুলুন</span>";
            case "FAILED" -> "<span class=\"tag retake\">পড়া যায়নি</span>";
            default -> "<span class=\"tag\">পড়া হচ্ছে…</span>";
        };
    }
}
