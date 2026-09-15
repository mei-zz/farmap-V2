package com.mei.zhgy.service.reference;

/** A safe, front-end-ready row from the read-only reference atlas. */
public record ReferenceImage(String refId, String refType, String stageCode, String diseaseKey,
                             String label, String imageUrl, String vigor, String match) { }
