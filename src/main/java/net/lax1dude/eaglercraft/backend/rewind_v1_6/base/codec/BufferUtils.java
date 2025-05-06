/*
 * Copyright (c) 2025 lax1dude, ayunami2000. All Rights Reserved.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */

package net.lax1dude.eaglercraft.backend.rewind_v1_6.base.codec;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.lax1dude.eaglercraft.backend.rewind_v1_6.base.RewindPlayer;

public class BufferUtils {

	public static final boolean CHARSEQ_SUPPORT;
	public static final boolean LITTLE_ENDIAN_SUPPORT;

	static {
		boolean b = false;
		try {
			ByteBuf.class.getMethod("readCharSequence", int.class, Charset.class);
			b = true;
		} catch (ReflectiveOperationException ex) {
		}
		CHARSEQ_SUPPORT = b;
		b = false;
		try {
			ByteBuf.class.getMethod("readIntLE");
			b = true;
		} catch (ReflectiveOperationException ex) {
		}
		LITTLE_ENDIAN_SUPPORT = b;
	}

	public static CharSequence readCharSequence(ByteBuf buffer, int len, Charset charset) {
		if (CHARSEQ_SUPPORT) {
			return buffer.readCharSequence(len, charset);
		} else {
			byte[] buf = new byte[len];
			buffer.readBytes(buf);
			return new String(buf, charset);
		}
	}

	public static int writeCharSequence(ByteBuf buffer, CharSequence seq, Charset charset) {
		if (CHARSEQ_SUPPORT) {
			return buffer.writeCharSequence(seq, charset);
		} else {
			byte[] bytes = seq.toString().getBytes(charset);
			buffer.writeBytes(bytes);
			return bytes.length;
		}
	}

	public static int readVarInt(ByteBuf buffer) {
		return readVarInt(buffer, 5);
	}

	public static int readVarInt(ByteBuf buffer, int maxBytes) {
		int out = 0;
		int bytes = 0;
		byte in;
		while (true) {
			in = buffer.readByte();

			out |= (in & 0x7F) << (bytes++ * 7);

			if (bytes > maxBytes) {
				throw new IndexOutOfBoundsException("VarInt too big (max " + maxBytes + ")");
			}

			if ((in & 0x80) != 0x80) {
				break;
			}
		}

		return out;
	}

	public static long readVarLong(ByteBuf buffer, int maxBytes) {
		long i = 0L;
		int j = 0;
		byte b0;
		while (true) {
			b0 = buffer.readByte();

			i |= (long) (b0 & 0x7F) << j++ * 7;

			if (j > maxBytes) {
				throw new IndexOutOfBoundsException("VarLong too big (max " + maxBytes + ")");
			}

			if ((b0 & 0x80) != 0x80) {
				break;
			}
		}

		return i;
	}

	public static void writeVarInt(ByteBuf buffer, int input) {
		while ((input & -128) != 0) {
			buffer.writeByte(input & 127 | 128);
			input >>>= 7;
		}

		buffer.writeByte(input);
	}

	public static void writeVarLong(ByteBuf buffer, long value) {
		while ((value & -128L) != 0L) {
			buffer.writeByte((int) (value & 127L) | 128);
			value >>>= 7;
		}

		buffer.writeByte((int) value);
	}

	public static int varIntLength(int val) {
		for (int i = 1; i < 5; ++i) {
			if ((val & -1 << i * 7) == 0) {
				return i;
			}
		}

		return 5;
	}

	public static String readLegacyMCString(ByteBuf buffer, int maxLen) {
		int len = buffer.readUnsignedShort();
		if (len > maxLen) {
			throw new IndexOutOfBoundsException("String too long");
		}
		char[] chars = new char[len];
		for (int i = 0; i < len; ++i) {
			chars[i] = buffer.readChar();
		}
		return new String(chars);
	}

	public static void writeLegacyMCString(ByteBuf buffer, String value, int maxLen) {
		int len = value.length();
		if (len > maxLen) {
			value = value.substring(0, maxLen);
			len = maxLen;
			// throw new IndexOutOfBoundsException();
		}
		buffer.writeShort(len);
		for (int i = 0; i < len; ++i) {
			buffer.writeChar(value.charAt(i));
		}
	}

	public static String readMCString(ByteBuf buffer, int maxLen) {
		int len = readVarInt(buffer);
		if (len > maxLen * 4) {
			throw new IndexOutOfBoundsException();
		}
		CharSequence ret = BufferUtils.readCharSequence(buffer, len, StandardCharsets.UTF_8);
		if (ret.length() > maxLen) {
			throw new IndexOutOfBoundsException();
		}
		return ret.toString();
	}

	public static void writeMCString(ByteBuf buffer, String value, int maxLen) {
		if (value.length() > maxLen) {
			throw new IndexOutOfBoundsException();
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeVarInt(buffer, bytes.length);
		buffer.writeBytes(bytes);
	}

	public static void convertMCString2Legacy(ByteBuf bufferIn, ByteBuf bufferOut, int maxLen) {
		convertMCString2Legacy(bufferIn, maxLen, bufferOut, maxLen);
	}

	public static void convertMCString2Legacy(ByteBuf bufferIn, int maxInputLen, ByteBuf bufferOut, int maxLen) {
		int len = readVarInt(bufferIn, 5);
		if (maxLen > 32767) {
			maxLen = 32767;
		}
		if (len > maxInputLen * 4) {
			throw new IndexOutOfBoundsException();
		}
		int writeLenAt = bufferOut.writerIndex();
		bufferOut.writeShort(0);
		int charsRead = 0;
		int charsWritten = 0;
		int cnt = 0;
		while (cnt < len && charsRead <= maxInputLen) {
			int b = bufferIn.readUnsignedByte();
			++cnt;
			if (b < 127) {
				++charsRead;
				if (charsWritten < maxLen) {
					bufferOut.writeChar(b);
					++charsWritten;
				}
			} else {
				switch ((b >> 4) & 0x7) {
				case 0b100:
				case 0b101:
					if (cnt < len) {
						int b2 = bufferIn.readUnsignedByte();
						++cnt;
						++charsRead;
						if (charsWritten < maxLen) {
							bufferOut.writeChar(((b & 0x1F) << 6) | (b2 & 0x3F));
							++charsWritten;
						}
					}
					break;
				case 0b110:
					if (cnt + 1 < len) {
						int b2 = bufferIn.readUnsignedByte();
						int b3 = bufferIn.readUnsignedByte();
						cnt += 2;
						++charsRead;
						if (charsWritten < maxLen) {
							bufferOut.writeChar(((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F));
							++charsWritten;
						}
					}
					break;
				case 0b111:
					if (cnt + 2 < len) {
						int b2 = bufferIn.readUnsignedByte();
						int b3 = bufferIn.readUnsignedByte();
						int b4 = bufferIn.readUnsignedByte();
						cnt += 3;
						++charsRead;
						int codepoint = (((b & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F));
						if (codepoint >= 0xD800 && codepoint <= 0xDFFF) {
							continue;
						} else if (codepoint < 0x10000) {
							if (charsWritten < maxLen) {
								bufferOut.writeChar(codepoint);
								++charsWritten;
							}
						} else {
							++charsRead;
							if (charsWritten + 1 < maxLen) {
								codepoint -= 0x10000;
								bufferOut.writeChar(0xD800 | (codepoint >> 10));
								bufferOut.writeChar(0xDC00 | (codepoint & 0x03FF));
								charsWritten += 2;
							}
						}
					}
					break;
				}
			}
		}
		if (charsRead > maxInputLen) {
			throw new IndexOutOfBoundsException();
		}
		if (charsWritten > 0) {
			bufferOut.setShort(writeLenAt, charsWritten);
		}
	}

	public static void convertLegacyMCString(ByteBuf bufferIn, ByteBuf bufferOut, int maxLen) {
		int len = bufferIn.readShort();
		if (len < 0 || len > maxLen) {
			throw new IndexOutOfBoundsException();
		}
		int startAt = bufferIn.readerIndex();
		int utf8Length = 0;
		for (int i = 0; i < len; ++i) {
			char c = bufferIn.getChar(startAt + (i << 1));
			if (c <= 0x7F) {
				++utf8Length;
			} else if (c <= 0x07FF) {
				utf8Length += 2;
			} else if (c <= 0xD7FF || c > 0xDFFF) {
				utf8Length += 3;
			} else {
				if (i + 1 < len) {
					char c2 = bufferIn.getChar(startAt + (++i << 1));
					if (c2 > 0xD7FF && c <= 0xDFFF && ((c & 0xFC00) == 0xD800) && ((c2 & 0xFC00) == 0xDC00)) {
						utf8Length += 4;
					}
				}
			}
		}
		BufferUtils.writeVarInt(bufferOut, utf8Length);
		for (int i = 0; i < len; ++i) {
			char c = bufferIn.getChar(startAt + (i << 1));
			if (c <= 0x7F) {
				bufferOut.writeByte(c);
			} else if (c <= 0x07FF) {
				bufferOut.writeByte(((c >>> 6) & 0x1F) | 0xC0);
				bufferOut.writeByte((c & 0x3F) | 0x80);
			} else if (c <= 0xD7FF || c > 0xDFFF) {
				bufferOut.writeByte(((c >>> 12) & 0x0F) | 0xE0);
				bufferOut.writeByte(((c >>> 6) & 0x3F) | 0x80);
				bufferOut.writeByte((c & 0x3F) | 0x80);
			} else {
				if (i + 1 < len) {
					char c2 = bufferIn.getChar(startAt + (++i << 1));
					if (c2 > 0xD7FF && c <= 0xDFFF && ((c & 0xFC00) == 0xD800) && ((c2 & 0xFC00) == 0xDC00)) {
						int codepoint = (((c & 0x03FF) << 10) | (c2 & 0x03FF)) + 0x10000;
						bufferOut.writeByte(((codepoint >>> 18) & 0x07) | 0xF0);
						bufferOut.writeByte(((codepoint >>> 12) & 0x3F) | 0x80);
						bufferOut.writeByte(((codepoint >>> 6) & 0x3F) | 0x80);
						bufferOut.writeByte((codepoint & 0x3F) | 0x80);
					}
				}
			}
		}
		bufferIn.readerIndex(startAt + (len << 1));
	}

	public static void convertSlot2Legacy(ByteBuf buffer, ByteBuf bb, RewindPlayer<?> context) {
		short blockId = buffer.readShort();
		blockId = (short) convertItem2Legacy(blockId);
		bb.writeShort(blockId);
		if (blockId == -1) {
			return;
		}
		byte itemCount = buffer.readByte();
		short itemDamage = buffer.readShort();
		bb.writeByte(itemCount);
		bb.writeShort(itemDamage);
		convertNBT2Legacy(buffer, bb, context);
	}

	public static void convertLegacySlot(ByteBuf buffer, ByteBuf bb, RewindPlayer<?> context) {
		short blockId = buffer.readShort();
		bb.writeShort(blockId);
		if (blockId == -1) {
			return;
		}
		byte itemCount = buffer.readByte();
		short itemDamage = buffer.readShort();
		bb.writeByte(itemCount);
		bb.writeShort(itemDamage);
		convertLegacyNBT(buffer, bb, context);
	}

	public static void convertNBT2Legacy(ByteBuf buffer, ByteBuf bb, RewindPlayer<?> context) {
		if (buffer.readUnsignedByte() == 0) {
			bb.writeShort(-1);
			return;
		}
		buffer.readerIndex(buffer.readerIndex() - 1);
		int wi = bb.writerIndex() + 2;
		bb.ensureWritable(2);
		bb.writerIndex(wi);

		try (ByteBufInputStream bbis = new ByteBufInputStream(buffer);
				DataOutputStream dos = context.createGZIPOutputStream(bb)) {

			RewindNBTVisitor.apply(context.getNBTContext(), bbis, dos, context.getComponentHelper());

			dos.close();

			bb.setShort(wi - 2, bb.writerIndex() - wi);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void convertLegacyNBT(ByteBuf buffer, ByteBuf bb, RewindPlayer<?> context) {
		short len1 = buffer.readShort();
		if (len1 == -1) {
			bb.writeByte(0);
			return;
		} else if (len1 < 0) {
			throw new IndexOutOfBoundsException();
		}
		int oldEnd = buffer.writerIndex();
		buffer.writerIndex(buffer.readerIndex() + len1);
		try (DataInputStream gzipIs = context.createGZIPInputStream(buffer, 65535);
				ByteBufOutputStream dos = new ByteBufOutputStream(bb)) {
			RewindNBTVisitorReverse.apply(context.getNBTContext(), gzipIs, dos);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
		buffer.writerIndex(oldEnd);
	}

	public static int calcChunkDataSize(final int count, final boolean light, final boolean sendBiomes) {
		int idlength = count * 2 * 16 * 16 * 16;
		int blocklightlength = (count * 16 * 16 * 16) / 2;
		int skylightlength = light ? ((count * 16 * 16 * 16) / 2) : 0;
		int biomeslength = sendBiomes ? 256 : 0;
		return idlength + blocklightlength + skylightlength + biomeslength;
	}

	public static void convertChunk2Legacy(int bitmap, int data18len, ByteBuf data18, ByteBuf bb) {
		int absInd = data18.readerIndex();
		int absWInd = bb.writerIndex();
		int count = Integer.bitCount(bitmap);
		int guh1 = 8192 * count;
		int guh = data18len - guh1;
		int guh2 = count * (4096 + 2048);
		int count2 = 8192 * count;
		int count3 = 4096 * count;
		bb.ensureWritable(guh2 + guh);

		if (LITTLE_ENDIAN_SUPPORT) {
			for (int i = 0; i < count2; i += 4) {
				int state = data18.getIntLE(absInd + i);
				bb.setShortLE(absWInd + (i >> 1),
						convertType2Legacy((state >>> 4) & 0xFFF) | (convertType2Legacy(state >>> 20) << 8));
				bb.setByte(absWInd + count3 + (i >> 2), (byte) ((state & 0xF) | (((state >>> 16) & 0xF) << 4)));
			}
		} else {
			for (int i = 0; i < count2; i += 4) {
				int stateA = data18.getUnsignedByte(absInd + i) | (data18.getUnsignedByte(absInd + i + 1) << 8);
				int stateB = data18.getUnsignedByte(absInd + i + 2) | (data18.getUnsignedByte(absInd + i + 3) << 8);
				bb.setByte(absWInd + (i >> 1), convertType2Legacy(stateA >> 4));
				bb.setByte(absWInd + (i >> 1) + 1, convertType2Legacy(stateB >> 4));
				bb.setByte(absWInd + count3 + (i >> 2), (byte) ((stateA & 0xF) | ((stateB & 0xF) << 4)));
			}
		}

		if (guh == 256 && data18.readableBytes() - (absInd + guh1) < 256) {
			bb.setZero(absWInd + guh2, 256);
			data18.skipBytes(data18len - 256);
		} else {
			data18.getBytes(absInd + guh1, bb, absWInd + guh2, guh);
			data18.skipBytes(data18len);
		}
		bb.writerIndex(absWInd + guh2 + guh);
	}

	public static int sizeEstimateNotDeflated(int srcLen) {
		return 16 + (srcLen >> 15) * 5 + srcLen;
	}

	public static int notDeflate(ByteBuf dataIn, ByteBuf dataOut, Deflater notDeflater) {
		notDeflater.reset();
		if (dataIn.hasArray()) {
			byte[] arr = dataIn.array();
			int arrIndex = dataIn.arrayOffset();
			notDeflater.setInput(arr, arrIndex + dataIn.readerIndex(), dataIn.readableBytes());
		} else if (dataIn.nioBufferCount() == 1) {
			notDeflater.setInput(dataIn.internalNioBuffer(dataIn.readerIndex(), dataIn.readableBytes()));
		} else {
			throw new IllegalStateException("Composite buffers not supported! (Input)");
		}
		notDeflater.finish();
		int len;
		if (dataOut.hasArray()) {
			byte[] arr = dataOut.array();
			int arrIndex = dataOut.arrayOffset();
			len = notDeflater.deflate(arr, arrIndex + dataOut.writerIndex(), dataOut.writableBytes());
		} else if (dataOut.nioBufferCount() == 1) {
			len = notDeflater.deflate(dataOut.internalNioBuffer(dataOut.writerIndex(), dataOut.writableBytes()));
		} else {
			throw new IllegalStateException("Composite buffers not supported! (Output)");
		}
		if ((len == 0 && notDeflater.needsInput()) || !notDeflater.finished()) {
			throw new IndexOutOfBoundsException();
		}
		dataIn.skipBytes(notDeflater.getTotalIn());
		dataOut.writerIndex(dataOut.writerIndex() + notDeflater.getTotalOut());
		return notDeflater.getTotalOut();
	}

	public static int posX(long position) {
		return (int) (position >> 38);
	}

	public static int posY(long position) {
		return (int) ((position >> 26) & 0xFFF);
	}

	public static int posZ(long position) {
		return (int) (position << 38 >> 38);
	}

	public static long createPosition(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
	}

	public static String convertMetadata2Legacy(ByteBuf buffer, ByteBuf bb, int entityType, RewindPlayer<?> context) {
		String playerNameWowie = null;

		if (entityType == -1) {
			bb.writeByte(0x7F);
			return null;
		}
		while (true) {
			int item = buffer.readUnsignedByte();
			if (item == 0x7F) {
				bb.writeByte(item);
				break;
			}
			int index = item & 0x1F;
			int type = item >> 5;
			if (type == 7) {
				buffer.readFloat();
				buffer.readFloat();
				buffer.readFloat();
				continue;
			}
			switch (type) {
			case 0:
				remapMeta(entityType, index, type, bb, buffer.readByte());
				break;
			case 1:
				remapMeta(entityType, index, type, bb, buffer.readShort());
				break;
			case 2:
				remapMeta(entityType, index, type, bb, buffer.readInt());
				break;
			case 3:
				remapMeta(entityType, index, type, bb, buffer.readFloat());
				break;
			case 4:
				PlayerNameHolder playerNameHolder = entityType == 300 ? new PlayerNameHolder() : null;
				remapMeta(entityType, index, type, bb, BufferUtils.readMCString(buffer, 32767), playerNameHolder);
				if (playerNameHolder != null) {
					playerNameWowie = playerNameHolder.name;
				}
				break;
			case 5:
				ByteBuf tmp = context.getChannel().alloc().buffer();
				try {
					BufferUtils.convertSlot2Legacy(buffer, tmp, context);
					remapMeta(entityType, index, type, bb, tmp);
				} finally {
					tmp.release();
				}
				break;
			case 6:
				remapMeta(entityType, index, type, bb,
						new int[] { buffer.readInt(), buffer.readInt(), buffer.readInt() });
				break;
			}
		}
		if (bb.getByte(bb.writerIndex() - 1) != 0x7F) {
			bb.writeByte(0x7F);
		}

		return playerNameWowie;
	}

	private static final class PlayerNameHolder {
		public String name;
	}

	private static void remapMeta(int entityType, int index, int entryType, ByteBuf bb, Object entryValue) {
		remapMeta(entityType, index, entryType, bb, entryValue, null);
	}

	private static void remapMeta(int entityType, int index, int entryType, ByteBuf bb, Object entryValue, PlayerNameHolder playerNameHolder) {
		boolean mobNotObject = entityType >= 100 && entityType <= 300;
		if (entityType >= 300) {
			entityType -= 300;
		} else if (entityType >= 100) {
			entityType -= 100;
		}
		/**
		 * If Object:
		 * 91 = Painting
		 * 92 = Experience orb
		 * 93 = Lightning Bolt
		 * If Mob:
		 * 0 = Player
		 * All else follows wiki.vg
		 */
		if (index == 2 && entryType == 4) {
			index = 5;
			if (entityType == 0 && playerNameHolder != null) {
				playerNameHolder.name = (String) entryValue;
			}
		} else if (index == 3 && entryType == 0) {
			index = 6;
		} else if (index == 7 && entryType == 2) {
			index = 8;
		} else if (index == 8 && entryType == 0) {
			index = 9;
		} else if (!mobNotObject && entityType != 71 && (index == 9 || index == 15) && entryType == 0) {
			return;
		} else if (entityType != 54 && index == 12 && entryType == 0) {
			entryType = 2;
			entryValue = (int) (byte) entryValue;
		} else if (mobNotObject) {
			if (entityType == 0 && index == 9 && entryType == 0) {
				index = 10;
			} else if (entityType == 0
					&& (((index == 10 || index == 16) && entryType == 0) || (index == 17 && entryType == 3))) {
				return;
			} else if (entityType == 0 && index == 18 && entryType == 2) {
				return;
			} else if (entityType == 54 && index == 14 && entryType == 0) {
				return;
			} else if (entityType == 58 && index == 16 && entryType == 1) {
				entryType = 0;
				entryValue = (byte) (short) entryValue;
			} else if (entityType == 60 || entityType == 94) {
				return;
			} else if ((entityType == 63 || entityType == 64) && index == 6 && entryType == 3) {
				index = 16;
				entryType = 2;
				entryValue = (int) (float) entryValue;
			} else if (entityType == 66 && index == 21 && entryType == 0) {
				return;
			} else if (entityType == 95 && (index == 18 || index == 6) && entryType == 3) {
				index = 18;
				entryType = 2;
				entryValue = (int) (float) entryValue;
			} else if (index == 16 && entryType == 2) {
				entryType = 0;
				entryValue = (byte) (int) entryValue;
			} else if (index == 6 && entryType == 3) {
				return;
			}
		} else {
			if ((entityType == 1 || entityType == 10 || entityType == 11 || entityType == 12) && index == 19
					&& entryType == 3) {
				entryType = 2;
				entryValue = (int) (float) entryValue;
			} else if (entityType == 51 && index == 8 && entryType == 2) {
				return;
			} else if (entityType == 60 && index == 16 && entryType == 0) {
				entryValue = (byte) 0;
			} else if (entityType == 71 && index == 8 && entryType == 5) {
				index = 2;
			} else if (entityType == 71 && index == 9 && entryType == 0) {
				index = 3;
				entryValue = (byte) (((int) (byte) entryValue) >> 1);
			} else if (entityType == 77 || entityType == 78 || entityType == 90) {
				return;
			} else if (entityType == 10 && index == 20 && entryType == 2) {
				int id = ((int) entryValue) & 0xFFFF;
				int data = ((int) entryValue) >> 12;
				entryValue = (data << 16) | id;
			}
		}

		bb.writeByte((entryType << 5) | index);
		switch (entryType) {
		case 0:
			bb.writeByte((byte) entryValue);
			break;
		case 1:
			bb.writeShort((short) entryValue);
			break;
		case 2:
			bb.writeInt((int) entryValue);
			break;
		case 3:
			bb.writeFloat((float) entryValue);
			break;
		case 4:
			BufferUtils.writeLegacyMCString(bb, (String) entryValue, 64);
			break;
		case 5:
			bb.writeBytes((ByteBuf) entryValue);
			break;
		case 6:
			int[] fard = (int[]) entryValue;
			bb.writeInt(fard[0]);
			bb.writeInt(fard[1]);
			bb.writeInt(fard[2]);
			break;
		}
	}

	public static int convertItem2Legacy(int item) {
		item = convertType2Legacy(item);
		switch (item) {
		case 409:
			return 318;
		case 410:
			return 289;
		case 411:
			return 365;
		case 412:
		case 423:
		case 424:
			return 366;
		case 413:
			return 282;
		case 414:
			return 376;
		case 415:
			return 334;
		case 416:
		case 420:
		case 421:
			return 280;
		case 425:
			return 323;
		case 427:
		case 428:
		case 429:
		case 430:
		case 431:
			return 324;
		case 422:
			return 328;
		case 417:
		case 418:
		case 419:
			return 329;
		}
		return item;
	}

	public static int convertType2Legacy(int type) {
		switch (type) {
		case 165:
			return 133;
		case 166:
		case 95:
			return 20;
		case 167:
			return 96;
		case 168:
			return 48;
		case 169:
			return 89;
		case 176:
			return 63;
		case 177:
			return 68;
		case 179:
			return 24;
		case 180:
			return 128;
		case 181:
			return 43;
		case 182:
			return 44;
		case 183:
		case 184:
		case 185:
		case 186:
		case 187:
			return 107;
		case 188:
		case 189:
		case 190:
		case 191:
		case 192:
			return 85;
		case 193:
		case 194:
		case 195:
		case 196:
		case 197:
			return 64;
		case 178:
			return 151;
		case 160:
			return 102;
		case 161:
			return 18;
		case 162:
			return 17;
		case 163:
		case 164:
			return 53;
		case 174:
			return 80;
		case 175:
			return 38;
		case 159:
			return 82;
		case 170:
			return 1;
		case 171:
			return 70;
		case 172:
			return 82;
		case 173:
			return 1;
		}
		return type;
	}

	public static byte convertMapColor2Legacy(byte color) {
		int realColor = (color & 0xFF) >> 2;
		switch (realColor) {
		case 14:
			realColor = 8;
			break;
		case 15:
		case 26:
		case 34:
		case 36:
			realColor = 10;
			break;
		case 16:
		case 17:
		case 23:
		case 24:
		case 25:
		case 31:
		case 32:
			realColor = 5;
			break;
		case 18:
		case 30:
			realColor = 2;
			break;
		case 19:
			realColor = 1;
			break;
		case 20:
		case 28:
		case 35:
			realColor = 4;
			break;
		case 21:
		case 22:
		case 29:
			realColor = 11;
			break;
		case 27:
		case 33:
			realColor = 7;
		}
		return (byte) ((realColor << 2) + (color & 0b11));
	}

	public static int convertTypeMeta2Legacy(int typeMeta) {
		int type = typeMeta >> 4;
		int meta = typeMeta & 15;
		type = convertType2Legacy(type);
		return (type << 4) | meta;
	}

	public static String readASCIIStr(ByteBuf in) {
		return BufferUtils.readCharSequence(in, in.readUnsignedShort(), StandardCharsets.US_ASCII).toString();
	}

}
