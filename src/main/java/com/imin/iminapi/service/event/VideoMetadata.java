package com.imin.iminapi.service.event;

import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.containers.mp4.MP4Util;
import org.jcodec.containers.mp4.boxes.MovieBox;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

// Note: NIOUtils.readableChannel(ByteBuffer) does not exist in JCodec 0.2.5.
// Using ByteBufferSeekableByteChannel.readFromByteBuffer + MP4Util.parseMovieChannel instead.
@Component
public class VideoMetadata {

    /**
     * Returns the playback duration of an MP4 in seconds (rounded up).
     * Returns null if the bytes cannot be parsed (caller treats as "unknown").
     */
    public Integer probeMp4DurationSec(byte[] bytes) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            ByteBufferSeekableByteChannel ch = ByteBufferSeekableByteChannel.readFromByteBuffer(bb);
            MovieBox mov = MP4Util.parseMovieChannel(ch);
            if (mov == null || mov.getDuration() == 0 || mov.getTimescale() == 0) return null;
            double seconds = (double) mov.getDuration() / mov.getTimescale();
            return (int) Math.ceil(seconds);
        } catch (Exception e) {
            return null;
        }
    }
}
