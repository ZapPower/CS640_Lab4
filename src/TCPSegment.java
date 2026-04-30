import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class TCPSegment {
    public int seqNum;
    public int ackNum;        // 32-bit acknowledgment number
    public long timestamp;    // 64-bit timestamp in nanoseconds
    public int length;        // 29-bit data length (upper bits of the 32-bit length+flags word)
    public boolean synFlag;
    public boolean finFlag;
    public boolean ackFlag;
    public short checksum;    // 16-bit one's complement checksum
    public byte[] data;       // payload

    /**
     * Create a new TCPSegment
     * 
     * @param seqNum
     * @param ackNum
     * @param timestamp
     * @param synFlag
     * @param finFlag
     * @param ackFlag
     * @param data
     */
    public TCPSegment(int seqNum, int ackNum, long timestamp,
                    boolean synFlag, boolean finFlag, boolean ackFlag,
                    byte[] data) {
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.timestamp = timestamp;
        this.synFlag = synFlag;
        this.finFlag = finFlag;
        this.ackFlag = ackFlag;
        this.data = (data != null) ? data : new byte[0];
        this.length = this.data.length;
        this.checksum = 0; // computed separately after construction
    }

    /**
     * Parse bytes into a TCPSegment
     * 
     * @param rawBytes
     */
    public TCPSegment(byte[] rawBytes) {
        this.deserialize(rawBytes);
    }

    /**
     * Serialize this segment into a raw byte array
     * 
     * @return Byte array for the TCPSegment
     */
    public byte[] serialize() throws BufferOverflowException {
        // Header is always 20 bytes:
        // 4 bytes seqNum
        // 4 bytes ackNum
        // 8 bytes timestamp
        // 4 bytes length+flags
        // 2 bytes zeros + 2 bytes checksum
        int headerSize = 24;
        ByteBuffer buf = ByteBuffer.allocate(headerSize + data.length);

        buf.putInt(seqNum);
        buf.putInt(ackNum);
        buf.putLong(timestamp);

        // Pack length and flags into one 32-bit word
        // length occupies upper 29 bits, flags are bits 2 (S), 1 (F), 0 (A)
        int lengthAndFlags = (length << 3);
        if (synFlag) lengthAndFlags |= 0b100;
        if (finFlag) lengthAndFlags |= 0b010;
        if (ackFlag) lengthAndFlags |= 0b001;
        buf.putInt(lengthAndFlags);

        // 2 bytes zeros, 2 bytes checksum
        buf.putShort((short) 0);
        buf.putShort(checksum);

        buf.put(data);

        return buf.array();
    }

    private void deserialize(byte[] rawBytes) throws BufferUnderflowException {
        ByteBuffer buf = ByteBuffer.wrap(rawBytes);

        seqNum    = buf.getInt();
        ackNum    = buf.getInt();
        timestamp = buf.getLong();

        // Unpack length and flags
        int lengthAndFlags = buf.getInt();
        length = (lengthAndFlags >>> 3);  // upper 29 bits
        synFlag = ((lengthAndFlags & 0b100) != 0);
        finFlag = ((lengthAndFlags & 0b010) != 0);
        ackFlag = ((lengthAndFlags & 0b001) != 0);

        buf.getShort();                   // skip the two zero bytes
        checksum = buf.getShort();

        // Remaining bytes are data
        data = new byte[length];
        buf.get(data);
    }

    /**
     * Recomputes and sets the checksum for this segment
     */
    public void computeChecksum() {
        // Zero out checksum field before computing
        this.checksum = 0;
        byte[] bytes = serialize();

        int sum = 0;
        int i = 0;

        // Sum all 16-bit words
        while (i < bytes.length - 1) {
            int word = ((bytes[i] & 0xFF) << 8) | (bytes[i+1] & 0xFF);
            sum += word;
            // Fold carry back into LSB
            if ((sum & 0xFFFF0000) != 0) {
                sum = (sum & 0xFFFF) + 1;
            }
            i += 2;
        }

        // Handle odd byte at end by padding with 0x00
        if (i < bytes.length) {
            int word = (bytes[i] & 0xFF) << 8;
            sum += word;
            if ((sum & 0xFFFF0000) != 0) {
                sum = (sum & 0xFFFF) + 1;
            }
        }

        // Bitwise NOT of the final sum
        this.checksum = (short) (~sum & 0xFFFF);
    }

    /**
     * Checks whether the checksum on this segment is valid
     * 
     * @return true if successful, false otherwise
     */
    public boolean verifyChecksum() {
        short received = this.checksum;
        computeChecksum();               // recomputes over whole packet with checksum zeroed
        boolean valid = (this.checksum == received);
        this.checksum = received;        // restore original so we don't corrupt the segment
        return valid;
    }

    /**
     * Grabs the flag string associated with this segment for logging
     * 
     * @return SYN - S; ACK - A; FIN - F; DATA - D
     */
    public String getFlagString() {
        return String.format("%s %s %s %s",
            synFlag        ? "S" : "-",
            ackFlag        ? "A" : "-",
            finFlag        ? "F" : "-",
            data.length > 0 ? "D" : "-"
        );
    }
}
