import static java.lang.System.exit;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

/**
 * 
 */
public class Sender {

    private static final int SYN = 1 << 29;
    private static final int ACK = 1 << 30;
    private static final int FIN = 1 << 31;

    /** port number at which the client will run */
    private int clientPort;
    /** IP addr of remote peer (receiver) */
    private InetAddress IPAddr;
    /** port at which remote receive is running */
    private int remotePort;
    /** file to be sent */
    private String filename;
    /** maximum transmission unit (in bytes) */
    private int mtu;
    /** sliding windows size (in segments) */
    private int windowSize;
    /** Used to send out and receive packets */
    private DatagramSocket socket;
    /** Sequence Number of last sent byte */
    private int SEQ;
    // /** Acknoledgement Number */
    // private int ACK;
    /** Timeout time*/
    private double T0;
    /** Estimated round trip time */
    private double ERTT;
    /** Estimated deviation */
    private double EDEV;
    /** Smoothed round trip time */
    private double SRTT;
    /** Smoothed deviation time */
    private double SDEV;
    /** Queue for datagram packets at sws ~ not yet implemented code needs refactoring to use*/
    private Queue<DatagramPacket> queue = new ArrayDeque<>();

    /**
     * Constructor
     * 
     * @param clientPort
     * @param IPAddr
     * @param remotePort
     * @param filename
     * @param mtu
     * @param windowSize
     */
    Sender(int clientPort, InetAddress IPAddr, int remotePort, String filename, int mtu, int windowSize) {
        
        // same port range as Lab_1?
        if (clientPort < 1024 || clientPort > 65535) {
            System.err.println("Error: Client port must be in range 1024 through 65535");
            exit(1);
        }
        this.clientPort = clientPort;

        // alr checked for correctness in TCPend
        this.IPAddr = IPAddr;

        // same port range as Lab_1?
        if (remotePort < 1024 || remotePort > 65535) {
            System.err.println("Error: Remote port must be in range 1024 through 65535");
            exit(1);
        }
        this.remotePort = remotePort;
        
        // ensures file exists
        if (!Files.exists(Path.of(filename))) {
            System.err.println("Error: filename does not exist");
            exit(1);
        }
        this.filename = filename;
        
        // instructions say not to go beyond 1430 unless environment supports larger sizes
        if (mtu <= 0 || mtu > 1430) {
            System.err.println("Error: value for maximum transmission size (bytes) is invalid");
            exit(1);
        }
        this.mtu = mtu;

        // no restrictions listed on instructions
        this.windowSize = windowSize;

        // est local socket
        try { 
            socket = new DatagramSocket(this.clientPort); 
        } catch (SocketException e) {
            System.err.println("Error: Failed connection to local port: " + this.clientPort);
        }

        SEQ = 0;
        // ACK = 0;
        T0 = java.util.concurrent.TimeUnit.SECONDS.toNanos(5); // initialized to 5 seconds

        sender();
    }

    /**
     * Establish a connection with a remote host. Three-way handshake.
     * Sends connection initiator packet with SYN and SEQ 0. Blocks until 
     * receives packet, which should be acknowledgment.
     */
    private void establishConnection() {
        
        // send initial SYN packet
        byte[] data = new byte[0];
        ByteBuffer buf = buildPacket(data, SYN);

        DatagramPacket packet = new DatagramPacket(buf.array(), buf.array().length, IPAddr, remotePort);
        try {
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("Unable to send packet");
            exit(1);
        }
        // for new connection "set the sequence number to 0"
        this.SEQ = 0;


        // wait for receiver's SYN+ACK packet
        try {
            socket.receive(packet);
        } catch(IOException e) {
            System.err.println("Error waiting for initiator acknowledgment");
        }

        // parse packet, check flags, SEQ/ACK #s, and checksum
        buf = ByteBuffer.wrap(packet.getData());
        if (buf.getInt(0) != 0 || buf.getInt(4) != 0) {
            System.err.println("Wrong acknowledment number");
            exit(1);
        }
        if ((buf.getInt(16) & SYN & ACK) == 0) {
            System.err.println("Unexpected flags");
            exit(1);
        }
        short checksum = buf.getShort(22);
        buf.putShort(22, (short)0);
        short recalulatedChecksum = calculateChecksum(buf);
        if (recalulatedChecksum != checksum) {
            System.err.println("Checksum field did not match. Droping packet");
            exit(1);
        }
        
        // send final ACK to finalize connection
        buf = buildPacket(new byte[0], ACK);
        return;
        
    }

    private void terminateConnection() {

    }

    /**
     * Reads file into a byte array. Breaks file into chunks
     * and sends them in separate packets of size mtu until
     * all are sent. Incrementes global SEQ as 
     * each packet is sent.
     */
    private void sendDataPacket() {

        // entire file
        byte[] data = null;
        // segments of the file
        byte[] chunk = null;
        // current position in data file
        int position = 0;
        
        // open / read file into byte array
        try {
            data = Files.readAllBytes(Path.of(filename));
        } catch (IOException e) {
            System.err.println("IO error while reading from stream");
            return;
        }
        if (data == null)
            return;

        // refactor to also wait for sws segments
        // while there are bytes left, break into units of mtu and send
        while (data.length - position >= mtu) {
            chunk = Arrays.copyOfRange(data, position, position + mtu);
            ByteBuffer buf = buildPacket(chunk, ACK);
            // build DatagramPacket and send through local socket
            try {
                socket.send(new DatagramPacket(buf.array(), buf.array().length, IPAddr, remotePort));
            } catch (IOException e) {
                System.err.println("Unable to send packet");
            }
    
            position += mtu;
            this.SEQ += chunk.length;
        }
        if (data.length - position > 0) {
            chunk = Arrays.copyOfRange(data, position, data.length);
            ByteBuffer buf = buildPacket(chunk, ACK);
            // build DatagramPacket and send through local socket
            try {
                socket.send(new DatagramPacket(buf.array(), buf.array().length, IPAddr, remotePort));
            } catch (IOException e) {
                System.err.println("Unable to send packet");
            }
            this.SEQ += chunk.length;
        }


    }

    // private ByteBuffer receivePacket() {

    // }

    /**
     * Calculate the one's compliment checksum. Process the data in 16b segments, 
     * add caryover to least significant bit and finally take the bitwise not.
     * 
     * Also leaves the ByteBuffer buf rewound.
     * 
     * @param buf - packet bytes
     * @return checksum
     */
    public static short calculateChecksum(ByteBuffer buf) {

        buf.rewind();
        int sum = 0;

        // sum 16 bit segments, and 8bit if one remains
        while(buf.remaining() >= 2) {
            sum += buf.getShort() & 0xFFFF;
        }
        if (buf.remaining() == 1) {
            sum += buf.get() & 0xFF;
        }

        // carry over while bits above 16
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        buf.rewind();
        // bitwise not
        return (short) (~sum & 0xFFFF);
    }

    /**
     * Builds a binary packet according to assignment specifications.
     * 4B SEQ, 4B ACK, 8B timestamp, 29b length + 3 flag bits, 2B 0s, 2B checksum, n bytes data
     * @param data      - file to send in byte form
     * @param ACK   - flags to set in binary packet
     * @return buf      - assembled binary packet
     */
    private ByteBuffer buildPacket(byte[] data, int flags) { 
        int length = data.length;
        short checksum;

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 8 + 4 + 2 + 2 + length);
        buf.putInt(SEQ);                            // SEQ #
        buf.putInt(0);                        // ACK # ~ receiver does not send data
        buf.putLong(System.nanoTime());             // timestamp
        buf.putInt((length & 0x1FFFFFFF) | flags);  // length + bit flags
        buf.putShort((short)0);                     // padding
        buf.putShort((short)0);                     // checksum - must set again after calculation
        buf.put(data);

        // write checksum 22 bytes into packet
        checksum = calculateChecksum(buf);
        buf.putShort(22, checksum);

        return buf;
    }

    /**
     * Loop method, initiated by constructor. Initiates the 
     * connection establishment sequence and then transitions into
     * sending / waiting for acknowledgements.
     */
    public void sender() {

        // establish connection
        establishConnection();

        // loop


    }

    /**
     * Follows algorithm in Assignment Spec.
     * Updates timeouttime (T0).
     * @param T - packet timestamp
     */
    private void timeoutComputation(long T) {

        double a = 0.875;
        double b = 0.75;
        
        long C = System.nanoTime(); // current time

        if (this.SEQ == 0) {
            ERTT = C - T;
            EDEV = 0;
            T0 = 2 * ERTT;
        }
        else {
            SRTT = (C - T);
            SDEV = Math.abs(SRTT - ERTT);
            ERTT = a * EDEV + (1-a) * SRTT;
            EDEV = b * EDEV + (1-b) * SDEV;
            T0 = ERTT + 4 * EDEV;
        }

        return;
    }
}