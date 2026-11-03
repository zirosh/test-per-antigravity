package app.caricasolare.tesla;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Proto {
    private Proto() {
    }

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void varint(long value) {
            while ((value & -128L) != 0) {
                this.out.write((int) ((value & 127L) | 128L));
                value >>>= 7;
            }
            this.out.write((int) value);
        }

        private void key(int field, int wireType) {
            varint(((long) wireType) | (((long) field) << 3));
        }

        public Writer uint(int field, long value) {
            if (value != 0) {
                key(field, 0);
                varint(value);
            }
            return this;
        }

        public Writer int32(int field, int value) {
            if (value != 0) {
                key(field, 0);
                varint(value < 0 ? value : ((long) value) & 0xFFFFFFFFL);
            }
            return this;
        }

        public Writer fixed32(int field, long value) {
            if (value != 0) {
                key(field, 5);
                for (int i = 0; i < 4; i++) {
                    this.out.write((int) ((value >>> (i * 8)) & 255));
                }
            }
            return this;
        }

        public Writer bytes(int field, byte[] data) {
            if (data != null && data.length > 0) {
                key(field, 2);
                varint(data.length);
                this.out.write(data, 0, data.length);
            }
            return this;
        }

        public Writer message(int field, byte[] data) {
            key(field, 2);
            varint(data.length);
            this.out.write(data, 0, data.length);
            return this;
        }

        public Writer message(int field, Writer nested) {
            return message(field, nested.toBytes());
        }

        public byte[] toBytes() {
            return this.out.toByteArray();
        }
    }

    public static final class Message {
        private final Map<Integer, List<Object>> fields = new LinkedHashMap<>();

        public static Message parse(byte[] data) {
            Message message = new Message();
            int pos = 0;
            while (pos < data.length) {
                long[] tagVarint = readVarint(data, pos);
                int nextPos = (int) tagVarint[1];
                long tag = tagVarint[0];
                int fieldNumber = (int) (tag >>> 3);
                int wireType = (int) (tag & 7);
                Object value;
                if (wireType == 0) {
                    long[] valVarint = readVarint(data, nextPos);
                    nextPos = (int) valVarint[1];
                    value = valVarint[0];
                } else if (wireType == 1) {
                    long fixed64 = 0;
                    for (int i = 0; i < 8; i++) {
                        fixed64 |= (((long) data[nextPos + i]) & 255L) << (i * 8);
                    }
                    nextPos += 8;
                    value = fixed64;
                } else if (wireType == 2) {
                    long[] lenVarint = readVarint(data, nextPos);
                    int contentStart = (int) lenVarint[1];
                    int length = (int) lenVarint[0];
                    if (length < 0 || contentStart + length > data.length) {
                        throw new IllegalArgumentException("protobuf troncato");
                    }
                    byte[] slice = new byte[length];
                    System.arraycopy(data, contentStart, slice, 0, length);
                    nextPos = contentStart + length;
                    value = slice;
                } else if (wireType == 5) {
                    long fixed32 = 0;
                    for (int i = 0; i < 4; i++) {
                        fixed32 |= (((long) data[nextPos + i]) & 255L) << (i * 8);
                    }
                    nextPos += 4;
                    value = fixed32;
                } else {
                    throw new IllegalArgumentException("tipo protobuf non gestito: " + wireType);
                }
                message.fields.computeIfAbsent(fieldNumber, k -> new ArrayList<>()).add(value);
                pos = nextPos;
            }
            return message;
        }

        private static long[] readVarint(byte[] data, int pos) {
            long result = 0;
            int shift = 0;
            while (pos < data.length) {
                int next = pos + 1;
                byte b = data[pos];
                result |= ((long) (b & 127)) << shift;
                if ((b & 128) == 0) {
                    return new long[]{result, next};
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalArgumentException("varint troppo lungo");
                }
                pos = next;
            }
            throw new IllegalArgumentException("protobuf troncato");
        }

        public boolean has(int field) {
            return this.fields.containsKey(field);
        }

        public long num(int field) {
            List<Object> list = this.fields.get(field);
            if (list == null) {
                return 0L;
            }
            return (Long) list.get(list.size() - 1);
        }

        public byte[] bytes(int field) {
            List<Object> list = this.fields.get(field);
            if (list == null) {
                return null;
            }
            return (byte[]) list.get(list.size() - 1);
        }

        public Message msg(int field) {
            byte[] raw = bytes(field);
            return raw == null ? null : parse(raw);
        }
    }
}

