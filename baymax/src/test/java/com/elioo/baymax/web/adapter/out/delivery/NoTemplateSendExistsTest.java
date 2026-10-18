package com.elioo.baymax.web.adapter.out.delivery;

import com.elioo.baymax.wa.adapter.out.WaGraphClient;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Codes are never sent as a WhatsApp template (PO 2026-10-18, DR-39 amended): authentication, utility and marketing
 * templates are all paid. "verifyNoMoreInteractions" on a mock cannot show that, because the port has no template
 * method to call — the guarantee is that nothing a login code can reach is able to send one. This makes it a test: a
 * template-send method appearing on the messaging port, on the Graph client behind it, or on the OTP delivery port
 * fails here, and whoever adds it has to come and decide, in the open, that codes still do not use it.
 */
class NoTemplateSendExistsTest {

    /** Everything a login code passes through on its way to WhatsApp. */
    private static final List<Class<?>> REACHABLE = List.of(OtpDeliveryPort.class, CompositeOtpDelivery.class,
            LogOtpDelivery.class, WaMessagingPort.class, WaGraphClient.class);

    static List<String> templateMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !Modifier.isPrivate(m.getModifiers()))
                .map(Method::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).contains("template"))
                .toList();
    }

    @Test
    void nothingALoginCodeCanReachHasATemplateSend() {
        for (Class<?> type : REACHABLE) {
            assertThat(templateMethods(type)).as(type.getSimpleName()).isEmpty();
        }
        // and the free-form send the codes do use is still there, so the sweep is not looking at the wrong classes
        assertThat(Arrays.stream(WaMessagingPort.class.getDeclaredMethods()).map(Method::getName)).contains("sendText");
    }

    /** The check can fail: a type with a template method is caught. */
    @Test
    void theSweepCatchesATemplateMethod() {
        interface WithTemplate {
            void sendTemplate(String to, String name);
        }
        assertThat(templateMethods(WithTemplate.class)).containsExactly("sendTemplate");
        assertThatThrownBy(() -> assertThat(templateMethods(WithTemplate.class)).isEmpty()).isInstanceOf(AssertionError.class);
    }
}
