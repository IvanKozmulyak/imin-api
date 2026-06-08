package com.imin.iminapi.dto;

/** One curated reference image as a multipart part: raw bytes + filename + MIME type. */
public record StyleReferencePart(byte[] bytes, String filename, String mimeType) {}
