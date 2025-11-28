package com.example.tassmud.util;

import java.util.List;

public class HelpPage {
    public String name;
    public String summary;
    public String visibility; // "public" or "gm"
    public List<String> synopsis;
    public String body;

    public HelpPage() {}

    public String formatManPage() {
        if (body != null && !body.isEmpty()) return body;
        StringBuilder sb = new StringBuilder();
        sb.append("NAME\n    ").append(name == null ? "" : name).append(" - ").append(summary == null ? "" : summary).append("\n\n");
        if (synopsis != null && !synopsis.isEmpty()) {
            sb.append("SYNOPSIS\n");
            for (String s : synopsis) {
                sb.append("    ").append(s).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
