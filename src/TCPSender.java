import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TCPSender {
    private final DatagramSocket socket;
    private final InetAddress remoteIP;
    private final int remotePort;

    private final String fileName;
    private byte[] fileData;

    // Window
    private final int sws;
    private final int mtu;
    private int base;
    private int next;

    // Retransmission
    private final TCPSegment[] windowBuffer; // Ring buffer for window
    ScheduledFuture<?>[] retransmitTimers;
    ScheduledExecutorService scheduler;
    private final int[] retransmitCounts;

    // 3Dup
    private int lastACK;
    private int dupACKCount; // # of times lastACK repeated

    // Timeout
    private double ERTT, EDEV;
    private double timeout; // nanoseconds
    private boolean firstACK;
    private final long startTime;

    // Stats
    private int totalPacketsSent;
    private int totalRetransmissions;
    private int totalDupACKs;
    private long totalBytesTransferred;  
    private int totalBadChecksums;       
    private int totalOutOfSequence;

    /**
     * Creates a new TCPSender object
     * 
     * @param socket
     * @param remoteIp
     * @param remotePort
     * @param fileName
     * @param mtu
     * @param sws
     */
    public TCPSender(DatagramSocket socket, InetAddress remoteIp, int remotePort, String fileName, int mtu, int sws) {
        this.socket = socket;
        this.remoteIP = remoteIp;
        this.remotePort = remotePort;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;


        // Window
        this.base = 0;
        this.next = 0;
        this.windowBuffer = new TCPSegment[sws];
        this.retransmitTimers = new ScheduledFuture[sws];
        this.scheduler = Executors.newScheduledThreadPool(sws);
        this.retransmitCounts = new int[sws];

        // Timeout
        this.timeout = 5_000_000_000L; // 5 seconds
        this.firstACK = true;
        this.ERTT = 0;
        this.EDEV = 0;
        this.startTime = System.nanoTime();

        // 3Dup
        this.lastACK = -1;
        this.dupACKCount = 0;

        // Stats
        this.totalPacketsSent = 0;
        this.totalRetransmissions = 0;
        this.totalDupACKs = 0;
        this.totalBadChecksums = 0;
        this.totalBytesTransferred = 0;
        this.totalOutOfSequence = 0;
    }

    /**
     * Begins sending the fileData to the receiver
     * 
     * @throws Exception Any IO exception that could occur
     */
    public void send() throws Exception {
        // Load file
        File file = new File(fileName);
        try (FileInputStream fis = new FileInputStream(file)) {
            fileData = new byte[(int) file.length()];
            fis.read(fileData);
        }

        sendSYN();

        Thread ackListener = new Thread(() -> {
            while (!this.socket.isClosed()) {
                try {
                    byte[] buf = new byte[this.mtu + 24];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    this.socket.receive(pkt);

                    TCPSegment ack = new TCPSegment(
                        Arrays.copyOf(pkt.getData(), pkt.getLength())
                    );

                    handleACK(ack);
                } catch (SocketException e) {
                    // Socket closed
                    break;
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    System.err.println("ACK listener error: " + e.getMessage());
                }
            }
        });
        ackListener.setDaemon(true);
        ackListener.start();

        // send loop
        while (true) { 
            synchronized (this) {
                // Wait if window is full
                while (this.next - this.base >= this.sws * this.mtu) {
                    if (base >= fileData.length + 1) {
                        break;
                    }
                    wait();
                }

                if (this.base >= this.fileData.length + 1) {
                    // All data ACKed
                    break;
                }

                if (this.next <= this.fileData.length) {
                    sendNextSegment();
                } else {
                    // All data sent but not yet all ACKed, just wait
                    wait();
                }
            }
        }

        // DONE
        ackListener.interrupt();
        this.socket.setSoTimeout(0);
        sendFIN();
    }

    /**
     * Closes the TCP connection
     */
    public void close() {
        // Stop retransmitters
        for (int i = 0; i < this.sws; i++) {
            if (this.retransmitTimers[i] != null && !this.retransmitTimers[i].isDone()) {
                retransmitTimers[i].cancel(false);
            }
            this.retransmitTimers[i] = null;
        }

        // stop scheduler (give any running timers 2 seconds to close)
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (this.socket != null && !this.socket.isClosed()) {
            this.socket.close();
        }

        printStats();
    }

    /**
     * Establishes a SYN; SYN-ACK; ACK handshake with the receiver
     * 
     * @throws Exception Any IO error that could happen
     */
    private void sendSYN() throws Exception {
        TCPSegment syn = new TCPSegment(
            0, 
            0, 
            System.nanoTime(), 
            true, 
            false, 
            false, 
            null
        );
        syn.computeChecksum();

        byte[] payload = syn.serialize();
        DatagramPacket dp = new DatagramPacket(payload, payload.length, remoteIP, remotePort);

        // Retransmission Loop
        try {
            this.socket.setSoTimeout(5000); // 5s timeout
        } catch (SocketException e) {
            throw new Exception("An error occurred when setting the SYN timeout...", 
                e);
        }
        int attempts = 0;

        while (attempts < 16) {
            // Send SYN
            try {
                this.socket.send(dp);
            } catch (IOException e) {
                throw new Exception("Error when sending SYN pkt...", e);
            }
            logSegment(SEGTYPE.SND, syn);
            this.totalPacketsSent++;

            // Wait for SYN-ACK
            try {
                byte[] buf = new byte[this.mtu + 24]; // header is 20 bytes
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                this.socket.receive(response);

                TCPSegment synAck = new TCPSegment(
                    java.util.Arrays.copyOf(response.getData(), response.getLength())
                );

                // Validate checksum
                if (!synAck.verifyChecksum()) {
                    System.err.println("SYN-ACK failed checksum, retrying...");
                    this.totalBadChecksums++;
                    attempts++;
                    continue;
                }


                // Make sure it is actually a SYN-ACK
                if (synAck.synFlag && synAck.ackFlag && synAck.ackNum == 1) {
                    logSegment(SEGTYPE.RCV, synAck);

                    // Update timeout using the echoed timestamp
                    long SRTT = System.nanoTime() - synAck.timestamp;
                    ERTT = SRTT;
                    EDEV = 0;
                    timeout = 2 * ERTT;
                    firstACK = false;

                    // Send the final ACK to complete the handshake
                    TCPSegment ack = new TCPSegment(
                        1,                 // seqNum = 1 (SYN consumed one seq number)
                        synAck.seqNum + 1, // ackNum = receiver's SYN seq + 1
                        synAck.timestamp,  // echo back the timestamp
                        false,             // synFlag
                        false,             // finFlag
                        true,              // ackFlag
                        null               // no data
                    );
                    ack.computeChecksum();

                    byte[] ackBytes = ack.serialize();
                    DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length, this.remoteIP, this.remotePort);
                    try {
                        this.socket.send(ackPacket);
                    } catch (IOException e) {
                        throw new Exception("Error when sending ACK...", e);
                    }
                    logSegment(SEGTYPE.SND, ack);
                    this.totalPacketsSent++;

                    // Handshake complete, clear the socket timeout for normal operation
                    this.socket.setSoTimeout(0);
                    
                    // Advance base and nextSeq past the SYN
                    this.base = 1;
                    this.next = 1;
                    return;
                }
            } catch (java.net.SocketTimeoutException e) {
                // Timeout expired, will loop and retry
                System.err.println("SYN-ACK timeout, retrying... attempt " + (attempts + 1));
                attempts++;
                this.totalRetransmissions++;
            }
        }

        throw new Exception("SYN failed after 16 attempts, aborting.");
    }

    private void logSegment(SEGTYPE st, TCPSegment segment) {
        // Get time in seconds
        double time = (System.nanoTime() - this.startTime) / 1_000_000_000.0;

        System.out.printf("%s %.3f %s %d %d %d%n", 
            st.equals(SEGTYPE.RCV) ? "rcv" : "snd",
            time,
            segment.getFlagString(),
            segment.seqNum,
            segment.data.length,
            segment.ackNum
        );
    }

    /**
     * Sends the next segment containing the next slice of fileData
     * 
     * @throws Exception Any IO error that might happen
     */
    private synchronized void sendNextSegment() throws Exception {
        // Check if there is anything left to send
        if (this.next - this.base >= 
            this.sws * this.mtu || 
            this.next >= this.fileData.length + 1) {
            return;
        }

        // Slice the file data for this segment
        int dataStart = this.next - 1; // nextSeq starts at 1 after SYN
        int dataEnd = Math.min(dataStart + this.mtu, this.fileData.length);
        
        if (dataStart >= this.fileData.length) {
            return;
        }

        byte[] segData = Arrays.copyOfRange(fileData, dataStart, dataEnd);

        // Build the segment
        TCPSegment seg = new TCPSegment(
            this.next,
            0,
            System.nanoTime(),
            false,
            false,
            true,               // must always be set post handshake
            segData
        );
        seg.computeChecksum();

        // Store in window buffer
        int slot = this.next % this.sws;
        windowBuffer[slot] = seg;
        retransmitCounts[slot] = 0;

        // send segment
        byte[] segBytes = seg.serialize();
        DatagramPacket packet = new DatagramPacket(segBytes, segBytes.length, this.remoteIP, this.remotePort);
        try {
            this.socket.send(packet);
        } catch (IOException e) {
            throw new Exception("Error when sending data for byte range [" + 
                dataStart + ":" + dataEnd + "]", e);
        }
        logSegment(SEGTYPE.SND, seg);
        this.totalPacketsSent++;

        // Start retransmit timer for this segment
        final int seqForTimer = this.next;
        this.retransmitTimers[slot] = this.scheduler.schedule(
            () -> { retransmit(seqForTimer); },
            (long) timeout,
            TimeUnit.NANOSECONDS
        );

        // Advance nextSeq by the number of bytes sent
        this.next += segData.length;
    }

    /**
     * Retransmits a TCP segment based on its sequence number. We
     * are assuming that the segment has already been added to the window buffer
     * 
     * @param seqNum Sequence number of the segment to retransmit
     */
    private synchronized void retransmit(int seqNum) {
        int slot = seqNum % this.sws;

        // Check if this segment has already been ACKed (window may have slid)
        // If so, the timer fired late and we can just ignore it
        if (seqNum < this.base) {
            return;
        }

        // Check max retransmissions
        if (retransmitCounts[slot] >= 16) {
            System.err.println("Max retransmissions reached for seq " + seqNum + ", aborting.");
            close();
            return;
        }

        // Cancel the old timer if still active
        if (this.retransmitTimers[slot] != null && !this.retransmitTimers[slot].isDone()) {
            this.retransmitTimers[slot].cancel(false);
        }

        // Grab the segment from the buffer, update timestamp, recompute checksum
        TCPSegment seg = this.windowBuffer[slot];
        if (seg == null) {
            return;
        }
        seg.timestamp = System.nanoTime();
        seg.computeChecksum();

        // Resend
        try {
            byte[] segBytes = seg.serialize();
            DatagramPacket packet = new DatagramPacket(segBytes, segBytes.length, this.remoteIP, this.remotePort);
            this.socket.send(packet);
            logSegment(SEGTYPE.SND, seg);
            this.totalPacketsSent++;
            this.totalRetransmissions++;
        } catch (IOException e) {
            System.err.println("Retransmit failed for seq " + seqNum + ": " + e.getMessage());
            return;
        }

        // Increment count before rescheduling
        this.retransmitCounts[slot]++;

        // Restart the timer — self rescheduling until ACKed or max retransmissions hit
        final int seqForTimer = seqNum;
        retransmitTimers[slot] = scheduler.schedule(
            () -> {
                retransmit(seqForTimer);
            },
            (long) timeout,
            TimeUnit.NANOSECONDS
        );
    }

    /**
     * Handles a given ACK received. Updates the sliding window and stats
     * 
     * @param ack The TCP ACK segment
     */
    private synchronized void handleACK(TCPSegment ack) {
        if (!ack.verifyChecksum()) {
            this.totalBadChecksums++;
            return;
        }

        logSegment(SEGTYPE.RCV, ack);

        // Check for stale ACK
        if (ack.ackNum < this.base) {
            this.totalDupACKs++;
            return;
        }

        // Check for out of order ACK (beyond window)
        if (ack.ackNum > this.base + (this.sws * this.mtu)) {
            this.totalOutOfSequence++;
        }

        // Update timeout using echoed timestamp
        long SRTT = System.nanoTime() - ack.timestamp;
        if (firstACK) {
            ERTT = SRTT;
            EDEV = 0;
            timeout = 2 * ERTT;
            firstACK = false;
        } else {
            double SDEV = Math.abs(SRTT - ERTT);
            ERTT = 0.875 * ERTT + 0.125 * SRTT;
            EDEV = 0.75  * EDEV + 0.25  * SDEV;
            timeout = ERTT + 4 * EDEV;
        }

        // Slide the window — cancel timers for all slots now ACKed
        // ack.ackNum is the next expected byte, so everything before it is ACKed
        int newBase = ack.ackNum;
        for (int seq = this.base; seq < newBase; seq += this.mtu) {
            int slot = seq % this.sws;
            if (this.retransmitTimers[slot] != null && !this.retransmitTimers[slot].isDone()) {
                this.retransmitTimers[slot].cancel(false);
                this.retransmitTimers[slot] = null;
            }
            this.windowBuffer[slot] = null;
            this.retransmitCounts[slot] = 0;
            this.totalBytesTransferred += Math.min(mtu, newBase - seq);
        }
        this.base = newBase;

        // Check for duplicate ACKs
        if (ack.ackNum == lastACK) {
            this.dupACKCount++;
            this.totalDupACKs++;
            if (this.dupACKCount >= 3) {
                fastRetransmit(lastACK);
            }
        } else {
            this.lastACK = ack.ackNum;
            this.dupACKCount = 0;
        }

        // let main thread know that window may have space now
        notifyAll();
    }

    /**
     * Quickly retransmits a missing segment instead of relying on retransmit timers
     * 
     * @param seqNum Sequence number of the missing segment
     */
    private synchronized void fastRetransmit(int seqNum) {
        dupACKCount = 0;  // reset to avoid triggering again immediately
        retransmit(seqNum);
    }

    /**
     * Initiates FIN ACK FIN ACK sequence to terminate TCP connection
     * 
     * @throws Exception Any IO error that could occur
     */
    private void sendFIN() throws Exception {
        // Build FIN segment
        TCPSegment fin = new TCPSegment(
            this.next,            // seqNum = next byte after all data
            0,                  // ackNum = 0, we arent acknowledging data
            System.nanoTime(),
            false,              // synFlag
            true,               // finFlag
            true,               // ackFlag — must be set post handshake
            null                // no data
        );
        fin.computeChecksum();

        byte[] finBytes = fin.serialize();
        DatagramPacket finPacket = new DatagramPacket(finBytes, finBytes.length, this.remoteIP, this.remotePort);

        // Use socket timeout for FIN handshake similar to SYN
        this.socket.setSoTimeout(5000);
        int attempts = 0;

        while (attempts < 16) {
            // Send FIN
            this.socket.send(finPacket);
            logSegment(SEGTYPE.SND, fin);
            this.totalPacketsSent++;

            // Wait for FIN-ACK
            try {
                byte[] buf = new byte[this.mtu + 24];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                this.socket.receive(response);

                TCPSegment finAck = new TCPSegment(
                    Arrays.copyOf(response.getData(), response.getLength())
                );

                // Validate checksum
                if (!finAck.verifyChecksum()) {
                    this.totalBadChecksums++;
                    attempts++;
                    continue;
                }

                logSegment(SEGTYPE.RCV, finAck);

                // Check it is a FIN-ACK
                if (finAck.finFlag && finAck.ackFlag && finAck.ackNum == this.next + 1) {

                    // Send final ACK
                    TCPSegment ack = new TCPSegment(
                        this.next + 1,            // seqNum = FIN consumed one seq number
                        finAck.seqNum + 2,      // ackNum = receiver's FIN seq + 1
                        finAck.timestamp,       // echo back timestamp
                        false,                  // synFlag
                        false,                  // finFlag
                        true,                   // ackFlag
                        null                    // no data
                    );
                    ack.computeChecksum();

                    byte[] ackBytes = ack.serialize();
                    DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length, this.remoteIP, this.remotePort);
                    this.socket.send(ackPacket);
                    logSegment(SEGTYPE.SND, ack);
                    this.totalPacketsSent++;

                    // wait before closing in case final ACK is lost
                    // and receiver retransmits FIN-ACK
                    this.socket.setSoTimeout((int)(2 * timeout / 1_000_000));
                    try {
                        byte[] waitBuf = new byte[this.mtu + 24];
                        DatagramPacket waitPacket = new DatagramPacket(waitBuf, waitBuf.length);
                        while (true) {
                            this.socket.receive(waitPacket);
                            TCPSegment retransmittedFin = new TCPSegment(
                                Arrays.copyOf(waitPacket.getData(), waitPacket.getLength())
                            );
                            // Receiver retransmitted FIN-ACK, send ACK again
                            if (retransmittedFin.finFlag) {
                                this.socket.send(ackPacket);
                                logSegment(SEGTYPE.SND, ack);
                                this.totalPacketsSent++;
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        // wait period expired, safe to close
                    }

                    return;
                }

            } catch (SocketTimeoutException e) {
                System.err.println("FIN-ACK timeout, retrying... attempt " + (attempts + 1));
                attempts++;
                this.totalRetransmissions++;
            }
        }

        throw new Exception("FIN failed after 16 attempts, aborting.");
    }

    private enum SEGTYPE {
        SND,
        RCV
    }

    private void printStats() {
        System.out.printf("%dMb %d %d %d %d %d%n",
            this.totalBytesTransferred,
            this.totalPacketsSent,
            this.totalOutOfSequence,
            this.totalBadChecksums,
            this.totalRetransmissions,
            this.totalDupACKs
        );
    }
}
