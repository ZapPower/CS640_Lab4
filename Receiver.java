import static java.lang.System.exit;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

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

    /** Buffer writes until connection terminated to write */
    BufferedOutputStream out;

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

        // establish local socket
        try { 
            socket = new DatagramSocket(clientPort); 
        } catch (SocketException e) {
            System.err.println("Error: Failed connection to local port: " + clientPort);
        }

        try { 
            out = new BufferedOutputStream(new FileOutputStream(filename));
        } catch (FileNotFoundException e) {
            System.err.println("Error creating / opening the file to write to");
            exit(1);
        }
        // handles packet arrival
        receiver();
    }


    /**
     * Three-way handshake. Called by receiver() after packet with SYN
     * comes in. Sends an SYN/ACK packet in response and waits for a final ACK.
     * 
     * Did not know how to handle error, so exits instead.
     * @param packet w/ SYN flag, used to set remote port+IP and initiate connectino
     */
    private void establishConnection(DatagramPacket packet) {
        // skip flag checking (done in receiver())
        // clear checksum field
        ByteBuffer buf = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
        short checksum = buf.getShort(22);
        buf.putShort(22, (short)0);

        // recalculate and compare checksum value
        short recalculatedhecksum = Sender.calculateChecksum(buf);
        if (recalculatedhecksum != checksum) {
            System.err.println("Checksum field did not match. Droping packet");
            exit(1);
        }
        // set remote IP and Port
        this.IPAddr = packet.getAddress();
        this.remotePort = packet.getPort();
        this.ackNumber = 0;

        // send SYN + ACK packet
        long timestamp = buf.getLong(8);
        int flags = SYN & ACK;
        sendACK(timestamp, flags);

        // wait for final ACK packet
        try {
            socket.receive(packet);
        } catch (IOException e) {
            System.err.println("Error ingesting ACK packet");
            exit(1);
        }
        buf = ByteBuffer.wrap(packet.getData());

        // check that ACK #, checksum, and flags are as expected
        if (buf.getInt(4) != 0) {
            System.err.println("Wrong acknowledgment number");
            exit(1);
        }
        checksum = buf.getShort(22);
        buf.putShort(22, (short)0);
        recalculatedhecksum = Sender.calculateChecksum(buf);
        if (recalculatedhecksum != checksum) {
            System.err.println("Checksum field did not match. Droping packet");
            exit(1);
        }
        if ((buf.getInt(16) & ACK) == 0) {
            System.err.println("Expected ACK flag");
            exit(1);
        }
        

        return;
    }

    /**
     * Triggered by a FIN flag. Only sender can initiate a connection termination.
     * Sends and ACK of the FIN / SEQ # and then its own packet marked FIN
     * Four-way handshake
     */
    private void terminateConnection(DatagramPacket packet) {


        // 


        // flush and close outputstram
        try {
            out.flush();
            out.close();
        } catch (IOException e) {
            System.err.println("Error flushing the outputstream");
            exit(1);
        } finally {
            
        }
        return;
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
        int lenFlags = packetBuffer.getInt(16);
        int length = lenFlags & 0x1FFFFFFF;
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
        if ((lenFlags & FIN) != 0) {
            terminateConnection(packet);
            return;
        }

        // previous checks passed, write data to buffer
        byte[] data = new byte[length];
        packetBuffer.position(24);
        packetBuffer.get(data, 0, length);
        try {
            out.write(data);
        } catch (IOException e) {
            System.err.println("Error while writing to BufferedOutputStream");
        }

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

        ackBuffer.putInt(0);              // don't need to send back SEQ#, 0 for initation
        ackBuffer.putInt(this.ackNumber);       // next expected byte
        ackBuffer.putLong(timestamp);
        ackBuffer.putInt(ACK);                  // length 0
        ackBuffer.putShort((short)0);
        ackBuffer.putShort((short)0);

        checksum = Sender.calculateChecksum(ackBuffer);
        ackBuffer.putShort(22, checksum);

        // send data packet to 
        try {
            socket.send(new DatagramPacket(ackBuffer.array(), 24, IPAddr, remotePort));
        } catch (IOException e) {
            System.err.println("Error sending packet");
        }
    }

    /**
     * Runs a loop to catch incoming packets. Upon reception, SYN is checked
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
                flags = packetBuffer.getInt(16);
                // initialize socket
                if ((flags & SYN) != 0) {
                    establishConnection(packet);
                }
                // begin termination
                else if ((flags & FIN) != 0) {
                    terminateConnection(packet);
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