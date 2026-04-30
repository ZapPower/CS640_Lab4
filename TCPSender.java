
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Timer;

public class TCPSender {
    private DatagramSocket socket;
    private InetAddress remoteIP;
    private int remotePort;

    private byte[] fileData;

    // Window
    private int sws;
    private int mtu;
    private int base;
    private int next;

    // Retransmission
    // TODO: Implement TCPSegment (TCP pkt model)
    private TCPSegment[] windowBuffer;
    private long[] sendTimes;
    private Timer[] retransmitTimers;
    private int[] retransmitCounts;

    // Timeout
    private double ERTT, EDEV;
    private double timeout; // nanoseconds
    private boolean firstACK;

    // Stats
    private int totalPacketsSent;
    private int totalRetransmissions;
    private int totalDupACKs;

    // TODO: Finish
}
