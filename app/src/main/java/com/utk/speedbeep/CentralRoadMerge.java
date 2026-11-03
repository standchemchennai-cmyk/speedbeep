package com.utk.speedbeep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Java road overlay logic, separately testable without Android. */
public final class CentralRoadMerge {
    private CentralRoadMerge() {}

    /** Newest edits come first in the overlay file and win over old edits. */
    public static List<RoadSegment> merge(List<RoadSegment> vanilla,
                                           List<RoadSegment> overrides) {
        List<RoadSegment> result = new ArrayList<>(vanilla);
        for (int i = overrides.size() - 1; i >= 0; i--) {
            RoadSegment edit = overrides.get(i);
            result = RoadLimitEditor.override(result, Collections.singletonList(edit),
                    edit.limitKph, edit.name);
        }
        return result;
    }

    /** Clip older local edits where the new local edit overlaps them. */
    public static List<RoadSegment> add(List<RoadSegment> previous,
                                        List<RoadSegment> selected,
                                        double limit, String name) {
        return RoadLimitEditor.override(previous, selected, limit, name);
    }
}
