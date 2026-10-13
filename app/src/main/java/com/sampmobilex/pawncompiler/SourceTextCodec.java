package com.sampmobilex.pawncompiler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;


final class SourceTextCodec {
    private SourceTextCodec() { }

    static final class Document {
        final String text;
        final Charset charset;
        final byte[] bom;
        final String lineEnding;
        Document(String text, Charset charset, byte[] bom, String lineEnding) {
            this.text = text;
            this.charset = charset;
            this.bom = bom;
            this.lineEnding = lineEnding;
        }
    }

    static Document decode(byte[] bytes, String preferredCharset) throws IOException {
        Charset charset = Charset.forName(preferredCharset);
        int skip = 0;
        if (bytes.length >= 3 && bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
            charset = Charset.forName("UTF-8");
            skip = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte)0xFF && bytes[1] == (byte)0xFE) {
            charset = Charset.forName("UTF-16LE");
            skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte)0xFE && bytes[1] == (byte)0xFF) {
            charset = Charset.forName("UTF-16BE");
            skip = 2;
        }
        try {
            String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
            String endings = text.contains("\r\n") ? "\r\n" : text.contains("\r") ? "\r" : "\n";
            return new Document(text, charset, Arrays.copyOf(bytes, skip), endings);
        } catch (CharacterCodingException error) {
            throw new IOException("Source is not valid " + charset.name()
                    + ". Choose its encoding before opening it; the original file has not been changed.", error);
        }
    }

    static byte[] encode(String text, Document original) throws IOException {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!original.lineEnding.equals("\n")) normalized = normalized.replace("\n", original.lineEnding);
        try {
            ByteBuffer data = original.charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(normalized));
            byte[] bytes = Arrays.copyOf(original.bom, original.bom.length + data.remaining());
            data.get(bytes, original.bom.length, data.remaining());
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IOException("Edited text cannot be saved as " + original.charset.name()
                    + ". Remove unsupported characters or use a UTF-8 source file; the original file has not been changed.", error);
        }
    }
}
