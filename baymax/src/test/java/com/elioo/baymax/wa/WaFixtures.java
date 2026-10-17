package com.elioo.baymax.wa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * WhatsApp ids shaped like Meta's: base64 of 0x1c 0x18 len, the sender's digits, then an opaque tail. A wamid CARRIES
 * the phone number, which is why no log may print one; a fixture like "wamid.OK" carries nothing and hides the leak.
 */
public final class WaFixtures {

    private WaFixtures() {
    }

    public static String wamid(String digits) {
        return wamid(digits, 1);
    }

    public static String wamid(String digits, int n) {
        byte[] number = digits.getBytes(StandardCharsets.US_ASCII);
        byte[] tail = String.format("%020X", n).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x1c);
        b.write(0x18);
        b.write(number.length);
        b.writeBytes(number);
        b.write(0x15);
        b.write(0x02);
        b.write(0x00);
        b.write(0x12);
        b.write(0x18);
        b.write(tail.length);
        b.writeBytes(tail);
        return "wamid." + Base64.getEncoder().encodeToString(b.toByteArray());
    }

    /** What anyone reading a leaked wamid recovers: the digits inside it. */
    public static String decodedDigits(String wamid) {
        byte[] raw = Base64.getDecoder().decode(wamid.substring("wamid.".length()));
        return new String(raw, 3, raw[2], StandardCharsets.US_ASCII);
    }
}
