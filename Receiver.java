import static java.lang.System.exit;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 
 */
public class Receiver {

    /** port number at which the client will run */
    private int clientPort;
    /** maximum transmission unit (in bytes) */
    private int mtu;
    /** sliding windows size (in segments) */
    private int windowSize;
    /** file to be sent */
    private String filename;
    /** Sequence Number of last sent byte */
    private int SEQ;
    /** Acknoledgement Number */
    private int ACK;
    /** Used to send out and receive packets */
    private DatagramSocket socket;
    /** IP addr of remote peer (sender) */
    private InetAddress IPAddr;
    /** port at which remote sender is running */
    private int remotePort;
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

        IPAddr = null;
        remotePort = -1;
        // handles packet arrival
        receiver();
    }


    private void establishConnection(DatagramPacket packet) {

    }

    private void terminateConnection() {

    }

    private void parsePacket(DatagramPacket packet) {

        packet.

    }

    private void receiver() {
        DatagramPacket packet = null;

        while(true) {
            try {
                socket.receive(packet);
                if (IPAddr == null || remotePort == -1) {
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