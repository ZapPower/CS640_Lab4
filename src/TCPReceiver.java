import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

public class TCPReceiver {
    private final DatagramSocket socket;
    private InetAddress senderIP;
    private int senderPort;

    private final String fileName;
    private final int mtu;
    private final int sws;

    // Receive buffer for out of order segments
    private final TCPSegment[] recvBuffer;
    private int base;           // next expected seq number
    private int lastAcked;      // last ACK we sent

    // Timing
    private final long startTime;

    // Stats
    private int totalPacketsReceived;
    private int totalBadChecksums;
    private int totalOutOfSequence;
    private long totalBytesReceived;

    public TCPReceiver(DatagramSocket socket, String fileName, int mtu, int sws) {
        this.socket = socket;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;

        this.recvBuffer = new TCPSegment[sws];
        this.base = 1;          // expect data starting at seq 1 after SYN
        this.lastAcked = 1;

        this.startTime = System.nanoTime();

        // Stats
        this.totalPacketsReceived = 0;
        this.totalBadChecksums = 0;
        this.totalOutOfSequence = 0;
        this.totalBytesReceived = 0;
    }

    /**
     * Starts receiving data from the sender
     * 
     * @throws Exception Any IO error that could occur
     */
    public void receive() throws Exception {
        // Wait for SYN first
        receiveSYN();

        // Open output file
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            // Main receive loop
            while (true) {
                byte[] buf = new byte[mtu + 24];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);

                TCPSegment seg = new TCPSegment(
                    Arrays.copyOf(pkt.getData(), pkt.getLength())
                );

                // Validate checksum
                if (!seg.verifyChecksum()) {
                    totalBadChecksums++;
                    continue;
                }

                logSegment(SEGTYPE.RCV, seg);
                totalPacketsReceived++;

                // Handle FIN
                if (seg.finFlag) {
                    handleFIN(seg, fos);
                    break;
                }

                // Handle data segment
                handleData(seg, fos);
            }
        }

        printStats();
    }

    /**
     * Waits for and processes the initial SYN from the sender
     * 
     * @throws Exception Any IO error that could occur
     */
    private void receiveSYN() throws Exception {
        System.out.println("Waiting for SYN...");
        while (true) {
            byte[] buf = new byte[mtu + 24];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            this.socket.receive(pkt);
            System.out.println("DATAGRAM RECEIVED");

            TCPSegment syn = new TCPSegment(
                Arrays.copyOf(pkt.getData(), pkt.getLength())
            );

            // Validate checksum
            if (!syn.verifyChecksum()) {
                this.totalBadChecksums++;
                System.out.println("BAD CHECKSUM");
                continue;
            }

            // Check it is actually a SYN with seq 0
            if (!syn.synFlag || syn.seqNum != 0) {
                continue;
            }

            // Record sender address for all future sends
            this.senderIP = pkt.getAddress();
            this.senderPort = pkt.getPort();

            logSegment(SEGTYPE.RCV, syn);
            this.totalPacketsReceived++;

            // Send SYN-ACK
            TCPSegment synAck = new TCPSegment(
                0,              // seqNum = our own ISN, using 0 for simplicity
                1,              // ackNum = sender's SYN seq + 1
                syn.timestamp,  // echo back timestamp so sender can compute RTT
                true,           // synFlag
                false,          // finFlag
                true,           // ackFlag
                null            // no data
            );
            synAck.computeChecksum();

            byte[] synAckBytes = synAck.serialize();
            DatagramPacket synAckPkt = new DatagramPacket(
                synAckBytes, synAckBytes.length, this.senderIP, this.senderPort
            );
            this.socket.send(synAckPkt);
            logSegment(SEGTYPE.RCV, synAck);

            // Wait for final ACK to complete handshake
            byte[] ackBuf = new byte[mtu + 24];
            DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
            this.socket.receive(ackPkt);

            TCPSegment ack = new TCPSegment(
                Arrays.copyOf(ackPkt.getData(), ackPkt.getLength())
            );

            if (!ack.verifyChecksum()) {
                this.totalBadChecksums++;
                // SYN-ACK will be retransmitted by sender on timeout
                continue;
            }

            if (ack.ackFlag && ack.seqNum == 1) {
                logSegment(SEGTYPE.RCV, ack);
                this.totalPacketsReceived++;
                return; // handshake complete
            }
        }
    }

    /**
     * Handles an incoming data segment, buffers out of order segments,
     * delivers in order segments to the file, and sends cumulative ACKs
     *
     * @param seg The incoming data segment
     * @param fos The output file stream to write delivered data to
     * @throws IOException Any IO error writing to the file
     */
    private void handleData(TCPSegment seg, FileOutputStream fos) throws IOException {
        int seqNum = seg.seqNum;

        // Outside receive window — discard
        if (seqNum < this.base || 
            seqNum >= this.base + (this.sws * this.mtu)) {
            this.totalOutOfSequence++;
            sendACK(this.lastAcked, seg.timestamp);
            return;
        }

        // Out of order but within window — buffer it
        if (seqNum > this.base) {
            this.totalOutOfSequence++;
            int slot = seqNum % this.sws;
            this.recvBuffer[slot] = seg;
            // Still send ACK for last contiguous byte received
            sendACK(this.lastAcked, seg.timestamp);
            return;
        }

        // In order segment — deliver it and check buffer for more
        // seqNum == base
        fos.write(seg.data);
        this.totalBytesReceived += seg.data.length;
        this.base += seg.data.length;
        this.lastAcked = this.base;

        // Drain any buffered segments that are now in order
        while (true) {
            int slot = this.base % this.sws;
            TCPSegment buffered = this.recvBuffer[slot];
            if (buffered == null || buffered.seqNum != this.base) {
                break;
            }
            fos.write(buffered.data);
            this.totalBytesReceived += buffered.data.length;
            this.base += buffered.data.length;
            this.lastAcked = this.base;
            this.recvBuffer[slot] = null;
        }

        // ACK all contiguous data delivered so far
        sendACK(this.lastAcked, seg.timestamp);
    }

    /**
     * Handles an incoming FIN segment, sends FIN-ACK, waits for final ACK
     *
     * @param fin The FIN segment received
     * @param fos The output file stream, flushed before closing
     * @throws Exception Any IO error that could occur
     */
    private void handleFIN(TCPSegment fin, FileOutputStream fos) throws Exception {
        fos.flush();

        // Send FIN-ACK
        TCPSegment finAck = new TCPSegment(
            this.lastAcked,          // seqNum = last acked byte
            fin.seqNum + 1,     // ackNum = FIN seq + 1 (FIN consumes a seq number)
            fin.timestamp,      // echo back timestamp
            false,              // synFlag
            true,               // finFlag
            true,               // ackFlag
            null                // no data
        );
        finAck.computeChecksum();

        byte[] finAckBytes = finAck.serialize();
        DatagramPacket finAckPkt = new DatagramPacket(
            finAckBytes, finAckBytes.length, this.senderIP, this.senderPort
        );

        // Retransmit FIN-ACK until we get the final ACK
        this.socket.setSoTimeout(5000);
        int attempts = 0;

        while (attempts < 16) {
            this.socket.send(finAckPkt);
            logSegment(SEGTYPE.SND, finAck);

            try {
                byte[] buf = new byte[mtu + 24];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                this.socket.receive(pkt);

                TCPSegment response = new TCPSegment(
                    Arrays.copyOf(pkt.getData(), pkt.getLength())
                );

                if (!response.verifyChecksum()) {
                    this.totalBadChecksums++;
                    attempts++;
                    continue;
                }

                logSegment(SEGTYPE.RCV, response);
                this.totalPacketsReceived++;

                // Final ACK received — connection closed
                if (response.ackFlag && !response.finFlag && 
                    response.ackNum == fin.seqNum + 2) {
                    this.socket.setSoTimeout(0);
                    return;
                }

                // Sender retransmitted FIN, send FIN-ACK again

            } catch (java.net.SocketTimeoutException e) {
                System.err.println("Final ACK timeout, retransmitting FIN-ACK... attempt " + (attempts + 1));
                attempts++;
            }
        }

        throw new Exception("FIN handshake failed after 16 attempts.");
    }

    /**
     * Sends a cumulative ACK for all bytes received up to ackNum
     *
     * @param ackNum  The cumulative ACK number to send
     * @param timestamp The timestamp echoed back from the segment being ACKed
     */
    private void sendACK(int ackNum, long timestamp) {
        TCPSegment ack = new TCPSegment(
            0,          // seqNum = 0, receiver doesnt send data
            ackNum,     // cumulative ACK
            timestamp,  // echo back sender's timestamp for RTT computation
            false,      // synFlag
            false,      // finFlag
            true,       // ackFlag
            null        // no data
        );
        ack.computeChecksum();

        try {
            byte[] ackBytes = ack.serialize();
            DatagramPacket ackPkt = new DatagramPacket(
                ackBytes, ackBytes.length, this.senderIP, this.senderPort
            );
            this.socket.send(ackPkt);
            logSegment(SEGTYPE.SND, ack);
        } catch (IOException e) {
            System.err.println("Failed to send ACK " + ackNum + ": " + e.getMessage());
        }
    }

    /**
     * Logs a segment in the required format
     *
     * @param direction "snd" or "rcv"
     * @param seg       The segment to log
     */
    private void logSegment(SEGTYPE type, TCPSegment seg) {
        double time = (System.nanoTime() - startTime) / 1_000_000_000.0;
        System.out.printf("%s %.3f %s %d %d %d%n",
            type.equals(SEGTYPE.RCV) ? "rcv" : "snd",
            time,
            seg.getFlagString(),
            seg.seqNum,
            seg.data.length,
            seg.ackNum
        );
    }

    /**
     * Closes the socket
     */
    public void close() {
        if (this.socket != null && !this.socket.isClosed()) {
            this.socket.close();
        }
        printStats();
    }

    private void printStats() {
        System.out.printf("%d %d %d %d %d %d%n",
            this.totalBytesReceived,
            this.totalPacketsReceived,
            this.totalOutOfSequence,
            this.totalBadChecksums,
            0,                  // retransmissions — always 0 on receiver
            0                   // dupACKs — always 0 on receiver
        );
    }

    private enum SEGTYPE {
        SND,
        RCV
    }
}
