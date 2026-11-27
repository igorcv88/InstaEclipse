package ps.reso.instaeclipse.library.core;

import java.util.List;

/** Counts this selection only. A completed selection says nothing about the rest of the history. */
public record BatchCoverage(int candidates, int processed, int copied, int alreadyPresent, int failed,
                            int unknownMessage, int visited, int skipped) {
    public static BatchCoverage measure(int candidates, boolean hasMessage, int visited, int skipped,
                                        List<String> outcomes) {
        int copied = 0;
        int alreadyPresent = 0;
        for (String outcome : outcomes) {
            switch (outcome) {
                case "CREATED", "DEDUPLICATED", "REPAIRED" -> copied++;
                case "ALREADY_PRESENT" -> alreadyPresent++;
                default -> { }
            }
        }
        return new BatchCoverage(candidates, outcomes.size(), copied, alreadyPresent,
                outcomes.size() - copied - alreadyPresent, hasMessage ? 0 : copied + alreadyPresent, visited, skipped);
    }

    public boolean complete() { return processed == candidates; }
}
