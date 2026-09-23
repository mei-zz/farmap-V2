package com.mei.zhgy.service.reference;

import java.util.List;

/** Structured result for a reference-atlas lookup; empty is not ambiguous. */
public record ReferenceImageLookup(String status, List<ReferenceImage> images,
                                   int matchedRows, int acceptedRows, String match) {
    public ReferenceImageLookup {
        images = images == null ? List.of() : List.copyOf(images);
        status = status == null || status.isBlank() ? "UNAVAILABLE" : status;
    }

    public static ReferenceImageLookup notRequested() {
        return new ReferenceImageLookup("NOT_REQUESTED", List.of(), 0, 0, null);
    }

    public static ReferenceImageLookup unavailable(String match) {
        return new ReferenceImageLookup("UNAVAILABLE", List.of(), 0, 0, match);
    }

    public static ReferenceImageLookup noMatch(String match, int matchedRows, int acceptedRows) {
        return new ReferenceImageLookup("NO_MATCH", List.of(), matchedRows, acceptedRows, match);
    }

    public static ReferenceImageLookup ready(List<ReferenceImage> images, int matchedRows,
                                             int acceptedRows, String match) {
        return new ReferenceImageLookup("READY", images, matchedRows, acceptedRows, match);
    }

    public ReferenceImageLookup withImages(List<ReferenceImage> nextImages) {
        List<ReferenceImage> safeImages = nextImages == null ? List.of() : List.copyOf(nextImages);
        return safeImages.isEmpty()
                ? new ReferenceImageLookup(status, safeImages, matchedRows, acceptedRows, match)
                : new ReferenceImageLookup("READY", safeImages, matchedRows, acceptedRows, match);
    }
}
