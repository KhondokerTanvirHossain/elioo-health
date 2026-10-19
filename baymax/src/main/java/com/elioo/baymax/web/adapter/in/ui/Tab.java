package com.elioo.baymax.web.adapter.in.ui;

/** Spec §3: the three always-labelled tabs. চ্যাট keeps /app/home, where sign-in already lands. */
enum Tab {
    CHAT(PageChrome.BASE + "/home", "tab.chat", "chat"),
    PAPERS(PageChrome.BASE + "/timeline", "tab.papers", "papers"),
    FAMILY(PageChrome.BASE + "/family", "tab.family", "family");

    final String path;
    final String labelKey;
    final String icon;

    Tab(String path, String labelKey, String icon) {
        this.path = path;
        this.labelKey = labelKey;
        this.icon = icon;
    }

    /**
     * The tab a local path belongs to, for a sheet's no-JS URL drawn over the page it was opened from: the tab's own
     * path (with a query or below it), a document under কাগজপত্র, anything else চ্যাট.
     */
    static Tab of(String localPath) {
        for (Tab t : values()) {
            if (localPath.equals(t.path) || localPath.startsWith(t.path + "?") || localPath.startsWith(t.path + "/")) {
                return t;
            }
        }
        return localPath.startsWith(PageChrome.BASE + "/documents/") || localPath.startsWith(PageChrome.BASE + "/patients/") ? PAPERS : CHAT;
    }
}
