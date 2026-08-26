package dev.loadout.core.instance;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Just enough NBT to read what a launcher needs.
 *
 * <p>Minecraft stores a world's display name in {@code level.dat} and the server list in
 * {@code servers.dat}, both in Mojang's binary NBT format. Neither is guessable from the
 * filesystem -- a world folder is named after whatever the folder was called when it was
 * created, which is frequently not what the world is called now.
 *
 * <p>Read-only and deliberately partial. Writing NBT would mean owning the correctness of
 * files the game depends on, and nothing here needs to.
 */
public final class Nbt {
	private static final int END = 0;
	private static final int BYTE = 1;
	private static final int SHORT = 2;
	private static final int INT = 3;
	private static final int LONG = 4;
	private static final int FLOAT = 5;
	private static final int DOUBLE = 6;
	private static final int BYTE_ARRAY = 7;
	private static final int STRING = 8;
	private static final int LIST = 9;
	private static final int COMPOUND = 10;
	private static final int INT_ARRAY = 11;
	private static final int LONG_ARRAY = 12;

	/**
	 * A guard against a corrupt or hostile file describing an enormous structure.
	 *
	 * <p>Every length in NBT is a number read from the file itself, so a damaged one can
	 * claim a list of two billion entries and have this allocate until the process dies.
	 */
	private static final int MAX_ELEMENTS = 1 << 20;

	private Nbt() {
	}

	/**
	 * Reads a file, transparently handling gzip.
	 *
	 * <p>{@code level.dat} is gzipped and {@code servers.dat} is not, and nothing in either
	 * says which -- so the magic number decides.
	 *
	 * @return the root compound, or an empty map if the file is not readable as NBT
	 */
	public static Map<String, Object> read(Path file) throws IOException {
		try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
				PushbackInputStream peek = new PushbackInputStream(raw, 2)) {

			int first = peek.read();
			int second = peek.read();
			if (first < 0 || second < 0) {
				return Map.of();
			}
			peek.unread(second);
			peek.unread(first);

			boolean gzipped = first == 0x1f && second == 0x8b;
			try (DataInputStream in = new DataInputStream(
					gzipped ? new GZIPInputStream(peek) : peek)) {

				int type = in.readUnsignedByte();
				if (type != COMPOUND) {
					return Map.of();
				}
				in.readUTF();   // the root's name, which is conventionally empty
				return readCompound(in, 0);
			}
		} catch (EOFException | java.util.zip.ZipException e) {
			// A truncated or mislabelled file is not worth an exception to the caller: the
			// answer is simply that this world has no readable metadata.
			return Map.of();
		}
	}

	private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
		if (depth > 64) {
			throw new IOException("NBT nested too deeply");
		}

		Map<String, Object> compound = new LinkedHashMap<>();
		while (true) {
			int type = in.readUnsignedByte();
			if (type == END) {
				return compound;
			}
			compound.put(in.readUTF(), readValue(in, type, depth + 1));
		}
	}

	private static Object readValue(DataInputStream in, int type, int depth) throws IOException {
		switch (type) {
			case BYTE: return in.readByte();
			case SHORT: return in.readShort();
			case INT: return in.readInt();
			case LONG: return in.readLong();
			case FLOAT: return in.readFloat();
			case DOUBLE: return in.readDouble();
			case STRING: return in.readUTF();
			case COMPOUND: return readCompound(in, depth);

			case BYTE_ARRAY: {
				byte[] bytes = new byte[checked(in.readInt())];
				in.readFully(bytes);
				return bytes;
			}
			case INT_ARRAY: {
				int[] values = new int[checked(in.readInt())];
				for (int i = 0; i < values.length; i++) {
					values[i] = in.readInt();
				}
				return values;
			}
			case LONG_ARRAY: {
				long[] values = new long[checked(in.readInt())];
				for (int i = 0; i < values.length; i++) {
					values[i] = in.readLong();
				}
				return values;
			}
			case LIST: {
				int elementType = in.readUnsignedByte();
				int count = checked(in.readInt());
				List<Object> list = new ArrayList<>(Math.min(count, 1024));
				for (int i = 0; i < count; i++) {
					list.add(readValue(in, elementType, depth + 1));
				}
				return list;
			}
			default:
				throw new IOException("Unknown NBT tag type: " + type);
		}
	}

	private static int checked(int length) throws IOException {
		if (length < 0 || length > MAX_ELEMENTS) {
			throw new IOException("Implausible NBT length: " + length);
		}
		return length;
	}

	/** Walks a path of compound keys, returning null rather than throwing on any miss. */
	@SuppressWarnings("unchecked")
	public static Object path(Map<String, Object> root, String... keys) {
		Object current = root;
		for (String key : keys) {
			if (!(current instanceof Map)) {
				return null;
			}
			current = ((Map<String, Object>) current).get(key);
		}
		return current;
	}

	public static String string(Map<String, Object> root, String... keys) {
		Object value = path(root, keys);
		return value instanceof String text ? text : null;
	}
}
