package dev.aemdebug.aem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Values from the "Sling Settings" status printer. */
public final class SlingSettings {

    private static final Pattern LINE = Pattern.compile("^\\s*([A-Za-z .]+?)\\s*[=:]\\s*(.*)$");

    public String slingId;
    public String slingHome;
    public final List<String> runModes = new ArrayList<>();

    public static SlingSettings parse(String text) {
        SlingSettings settings = new SlingSettings();
        for (String raw : text.split("\\R")) {
            Matcher m = LINE.matcher(raw);
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1).trim().toLowerCase();
            String value = m.group(2).trim();
            switch (key) {
                case "sling id":
                    settings.slingId = value;
                    break;
                case "sling home":
                    settings.slingHome = value;
                    break;
                case "run modes":
                    String list = value;
                    if (list.startsWith("[") && list.endsWith("]")) {
                        list = list.substring(1, list.length() - 1);
                    }
                    for (String mode : list.split(",")) {
                        if (!mode.isBlank()) {
                            settings.runModes.add(mode.trim());
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return settings;
    }
}
