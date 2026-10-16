package dev.changeassurance.processing;

import java.util.List;

/** Ordered, disjoint regions covering all physical source lines. Boundaries are inclusive ends. */
record ParsedSource(List<Region> regions) {
    ParsedSource {
        regions = List.copyOf(regions);
    }

    record Region(int start, int end, List<String> context, List<Integer> boundaries) {
        Region {
            context = List.copyOf(context);
            boundaries = List.copyOf(boundaries);
        }
    }
}
