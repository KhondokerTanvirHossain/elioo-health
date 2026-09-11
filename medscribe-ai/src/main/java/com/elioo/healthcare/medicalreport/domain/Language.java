package com.elioo.healthcare.medicalreport.domain;

/**
 * Supported languages for MedScribe AI content.
 *
 * <p>This enum defines the languages available for displaying
 * medical report content including clinical insights, risk assessments,
 * recommendations, educational content, and chat responses.</p>
 *
 * <p>Currently supported:</p>
 * <ul>
 *   <li>English (EN) - Default language, used for AI generation</li>
 *   <li>Bangla (BN) - Translated on-demand from English</li>
 * </ul>
 */
public enum Language {

    EN("en", "English", "English"),
    BN("bn", "Bangla", "বাংলা");

    private final String code;
    private final String englishName;
    private final String nativeName;

    Language(String code, String englishName, String nativeName) {
        this.code = code;
        this.englishName = englishName;
        this.nativeName = nativeName;
    }

    /**
     * Get the ISO 639-1 language code.
     *
     * @return Language code (e.g., "en", "bn")
     */
    public String getCode() {
        return code;
    }

    /**
     * Get the language name in English.
     *
     * @return English name (e.g., "English", "Bangla")
     */
    public String getEnglishName() {
        return englishName;
    }

    /**
     * Get the language name in the native script.
     *
     * @return Native name (e.g., "English", "বাংলা")
     */
    public String getNativeName() {
        return nativeName;
    }

    /**
     * Check if this is the default language (English).
     *
     * @return true if English, false otherwise
     */
    public boolean isDefault() {
        return this == EN;
    }

    /**
     * Get Language enum from ISO 639-1 language code.
     *
     * @param code Language code (case-insensitive)
     * @return Matching Language enum, or EN as default if not found
     */
    public static Language fromCode(String code) {
        if (code == null || code.isBlank()) {
            return EN;
        }
        for (Language lang : values()) {
            if (lang.code.equalsIgnoreCase(code.trim())) {
                return lang;
            }
        }
        return EN; // Default to English for unknown codes
    }

    /**
     * Check if the given code is a supported language.
     *
     * @param code Language code to check
     * @return true if supported, false otherwise
     */
    public static boolean isSupported(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (Language lang : values()) {
            if (lang.code.equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }
}
