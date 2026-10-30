package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.outbound.application.service.Copy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * {@link ChatCopyPort} over the two bundles: the WhatsApp message lines ({@code messages_{bn,en}}) for the urgency lines and
 * the red-flag line, so the web answer words them byte for byte as WhatsApp and the paper page do, and the UI bundles
 * ({@link UiCopy#plain}, unescaped) for everything else.
 */
@Component
@RequiredArgsConstructor
public class UiChatCopyAdapter implements ChatCopyPort {

    static final Set<String> MESSAGE_KEYS = Set.of("urgency.now", "urgency.this_week", "safety.red_flag_line");

    private final UiCopy ui;
    private final Copy messages;

    @Override
    public String line(String lang, String key, Object... args) {
        if (MESSAGE_KEYS.contains(key)) {
            return "en".equals(lang) ? messages.en(key, Map.of()) : messages.bn(key, Map.of());
        }
        return ui.plain(Lang.parse(lang), key, args);
    }
}
