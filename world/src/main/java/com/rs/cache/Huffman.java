package com.rs.cache;

import com.rs.lib.io.InputStream;
import com.rs.lib.io.OutputStream;

/**
 * Stub Huffman class. Provides method signatures for compilation.
 * TODO: Wire to the modern cache system for actual Huffman encoding/decoding.
 */
public final class Huffman {

	public Huffman() {
	}

	public int sendEncryptMessage(OutputStream stream, String message) {
		// Stub: write unencrypted for now
		int startOffset = stream.getOffset();
		byte[] data = message.getBytes();
		stream.writeSmart(data.length);
		stream.writeBytes(data);
		return stream.getOffset() - startOffset;
	}

	public final String readEncryptedMessage(InputStream stream) {
		return readEncryptedMessage(32767, stream);
	}

	public final String readEncryptedMessage(int maxLength, InputStream stream) {
		// Stub: read as raw bytes for now
		int length = stream.readSmart();
		if (length > maxLength) length = maxLength;
		byte[] data = new byte[length];
		for (int i = 0; i < length; i++)
			data[i] = (byte) stream.readByte();
		return new String(data);
	}

	public final int encryptMessage(int streamOffset, int messageDataLength, byte[] streamBuffer, int messageDataOffset, byte[] messageData) {
		// Stub: copy raw bytes
		int written = 0;
		for (int i = 0; i < messageDataLength; i++) {
			streamBuffer[streamOffset + i] = messageData[messageDataOffset + i];
			written++;
		}
		return written;
	}
}
