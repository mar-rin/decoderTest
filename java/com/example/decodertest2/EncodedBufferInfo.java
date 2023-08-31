package com.example.decodertest2;

import java.nio.ByteBuffer;

public class EncodedBufferInfo {
        public int offset;
        public int size;
        public long presentationTimeUs;
        public int flags;
        public ByteBuffer data; // Optional, if you want to also store the actual encoded data

        public EncodedBufferInfo(int offset, int size, long presentationTimeUs, int flags) {
            this.offset = offset;
            this.size = size;
            this.presentationTimeUs = presentationTimeUs;
            this.flags = flags;
        }
}
