import static java.lang.System.exit;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 
 */
public class Receiver {

    private static final int SYN = 1 << 29;
    private static final int ACK = 1 << 30;
    private static final int FIN = 1 << 31;

    /** port number at which the client will run */
    private int clientPort;
    /** maximum transmission unit (in bytes) */
    private int mtu;
    /** sliding windows size (in segments) */
    private int windowSize;
    /** file to be sent */
    private String filename;
    
    /** ACK is next byte expected  */
    private int ackNumber;
    /** Used to send out and receive packets */
    private DatagramSocket socket;
    
    /** IP addr of remote peer (sender) */
    private InetAddress IPAddr;
    /** port at which remote sender is running */
    private int remotePort;

    /** Queue for datagram packets at sws ~ not yet implemented code needs refactoring to use*/
    private Queue<DatagramPacket> q = new ArrayDeque<>();


    public Receiver(int clientPort, int mtu, int windowSize, String filename) {
        
        // same port range as Lab_1?
        if (clientPort < 1024 || clientPort > 65535) {
            System.err.println("Error: Client port must be in range 1024 through 65535");
            exit(1);
        }
        this.clientPort = clientPort;

        // instructions say not to go beyond 1430 unless environment supports larger sizes
        if (mtu <= 0 || mtu > 1430) {
            System.err.println("Error: value for maximum transmission size (bytes) is invalid");
            exit(1);
        }
        this.mtu = mtu;

        // no restrictions listed on instructions
        this.windowSize = windowSize;

        // ensures file exists
        if (!Files.exists(Path.of(filename))) {
            System.err.println("Error: filename does not exist");
            exit(1);
        }
        this.filename = filename;

        // est local socket
        try { 
            socket = new DatagramSocket(clientPort); 
        } catch (SocketException e) {
            System.err.println("Error: Failed connection to local port: " + clientPort);
        }

        // handles packet arrival
        receiver();
    }


    /**
     * Triggered by a SYN flag, establishes a conenction to the remote sender
     * by saving their IP + port. Also parses the initiator packet
     * and sends an acknowledgement (with SYN and ACK flags set).
     * @param packet
     */
    private void establishConnection(DatagramPacket packet) {
        
        // clear checksum field
        ByteBuffer buf = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
        short checksum = buf.getShort(22);
        buf.putShort(22, (short)0);

        // recalculate and compare checksum value
        short recalculatedhecksum = Sender.calculateChecksum(buf);
        if (recalculatedhecksum != checksum) {
            System.err.println("Checksum field did not match. Droping packet");
            return;
        }
        // set remote IP and Port
        this.IPAddr = packet.getAddress();
        this.remotePort = packet.getPort();
        this.ackNumber = 1;

        // send acknowledgement for initiation packet
        long timestamp = buf.getLong(8);
        int flags = SYN & ACK;
        sendACK(timestamp, flags);

        return;
    }

    /**
     * Triggered by a FIN flag. Only sender can initiate a connection termination.
     * Sends and ACK of the FIN / SEQ # and then its own packet marked FIN
     */
    private void terminateConnection(DatagramPacket packet) {



    }

    /**
     * Parses and handles packet data. Checks for duplication, correct checksum,
     * and flags in case termination is required.
     * @param packet
     */
    private void parsePacket(DatagramPacket packet) {

        ByteBuffer packetBuffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
        int packetSEQ = packetBuffer.getInt(0);
        long timestamp = packetBuffer.getLong(8);
        int length = packetBuffer.getInt(16) & 0x1FFFFFFF;

        // discard duplicate packet
        if (packetSEQ < ackNumber) {
            return;
        }
        // check checksum, drop packet if mismatch
        short checksum = packetBuffer.getShort(22);
        packetBuffer.putShort(22, (short)0);
        short recalculatedChecksum = Sender.calculateChecksum(packetBuffer);
        if (recalculatedChecksum != checksum) {
            return;
        }

        // initiate connection termination
        if ((packetBuffer.getInt(16) & FIN) != 0) {
            terminateConnection(packet);
            return;
        }

        // previous checks passed, write data


        // send acknowledgement w/ flag
        sendACK(timestamp, ACK);
    }

    /**
     * Builds a basic acknowledgment packet.
     * Only ACK flag is set and packet holds no data hence
     * ACK flag is not & to a value (length 0).
     * @param timestamp is copied over from incoming packet
     */
    private void sendACK(long timestamp, int flags) {

        short checksum = 0;
        ByteBuffer ackBuffer = ByteBuffer.allocate(24);

        ackBuffer.putInt(0);              // don't need to send back a SEQ#
        ackBuffer.putInt(this.ackNumber);       // next expected byte
        ackBuffer.putLong(timestamp);
        ackBuffer.putInt(ACK);                  // length 0
        ackBuffer.putShort((short)0);
        ackBuffer.putShort((short)0);

        checksum = Sender.calculateChecksum(ackBuffer);
        ackBuffer.putShort(22, checksum);
    }

    /**
     * Runs indefinetely to handle incoming packets. Upon reception, SYN is checked
     * to determine if packet should establish a connection, otherwise generic packet
     * parser is called.
     */
    private void receiver() {
        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
        ByteBuffer packetBuffer;
        int flags;

        while(true) {
            try {
                socket.receive(packet);
                // resize packet
                packetBuffer = ByteBuffer.wrap(packet.getData());
                flags = packetBuffer.getInt(16) >>> 29;
                // initialize socket
                if ((flags & SYN) != 0) {
                    establishConnection(packet);
                }
                else {
                    parsePacket(packet);
                }
            } catch (IOException e) {
                System.err.println("Error: problem receiving Datagram via socket");
            }
        }

    }
    
    // don't need threads piazza @217
}