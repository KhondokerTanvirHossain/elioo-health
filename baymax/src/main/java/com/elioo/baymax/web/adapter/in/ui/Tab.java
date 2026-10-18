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
}
